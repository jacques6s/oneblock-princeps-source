/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.process;

import princeps.Princeps;
import princeps.api.IPrinceps;
import princeps.api.event.events.*;
import princeps.api.event.events.type.EventState;
import princeps.api.event.listener.AbstractGameEventListener;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.goals.GoalXZ;
import princeps.api.pathing.goals.GoalYLevel;
import princeps.api.pathing.movement.IMovement;
import princeps.api.pathing.path.IPathExecutor;
import princeps.api.process.IPrincepsProcess;
import princeps.api.process.IElytraProcess;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.movement.movements.MovementFall;
import princeps.process.elytra.ElytraBehavior;
import princeps.process.elytra.NetherPathfinderContext;
import princeps.process.elytra.NullElytraProcess;
import princeps.utils.PrincepsProcessHelper;
import princeps.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static princeps.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends PrincepsProcessHelper implements IPrincepsProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private boolean predictingTerrain;

    // Vertical rocket takeoff ("sky launch"): position under a free-sky column, look straight up,
    // jump, deploy, and rocket vertically through the opening before normal flight takes over.
    private boolean skyLaunchChecked;
    private BetterBlockPos skyLaunchSpot;
    private double skyLaunchTargetY;
    private int skyLaunchFireworkCooldown;
    private int skyLaunchGroundTicks;
    /**
     * Set by the auto-elytra dispatcher: this flight was chosen BY the bot, so the takeoff must be fully
     * autonomous — the cliff auto-jump engages regardless of the user's manual {@code elytraAutoJump}
     * preference (which only governs manually issued {@code #elytra} journeys).
     */
    private boolean autoTakeoff;

    @Override
    public void onLostControl() {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.reachedGoal = false;
        this.goal = null;
        this.skyLaunchChecked = false;
        this.skyLaunchSpot = null;
        this.skyLaunchFireworkCooldown = 0;
        this.skyLaunchGroundTicks = 0;
        this.autoTakeoff = false;
        destroyBehaviorAsync();
    }

    private ElytraProcess(Princeps princeps) {
        super(princeps);
        princeps.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Princeps princeps) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(princeps)
                : new NullElytraProcess(princeps);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        this.onLostControl();
        if (destination != null) {
            this.pathTo(destination);
            this.repackChunks();
        }
    }

    @Override
    public boolean isLanding() {
        return this.goingToLandingSpot || this.state == State.LANDING;
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        final long seedSetting = Princeps.settings().elytraNetherSeed.value;
        if (seedSetting != this.behavior.context.getSeed()) {
            logDirect("Nether seed changed, recalculating path");
            this.resetState();
        }
        if (predictingTerrain != Princeps.settings().elytraPredictTerrain.value) {
            logDirect("elytraPredictTerrain setting changed, recalculating path");
            predictingTerrain = Princeps.settings().elytraPredictTerrain.value;
            this.resetState();
        }

        this.behavior.onTick();

        if (calcFailed) {
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean safetyLanding = false;
        if (ctx.player().isFallFlying() && shouldLandForSafety()) {
            if (Princeps.settings().elytraAllowEmergencyLand.value) {
                logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        if (ctx.player().isFallFlying() && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                logDirect("Path complete, picking a nearby safe landing spot...");
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                if (landingSpot != null) {
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                }
                this.goingToLandingSpot = true;
            }

            if (last != null && ctx.player().position().distanceToSqr(last.getCenter()) < 1) {
                if (Princeps.settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification("Pathing complete", false);
                }
                if (Princeps.settings().disconnectOnArrival.value && !reachedGoal) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[Princeps] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot) {
                    this.state = State.LANDING;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                princeps.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - LANDING_COLUMN_HEIGHT) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                }
            }
        }

        // Vertical rocket takeoff, phase 2: jump straight up under the opening, deploy at the apex,
        // and rocket vertically until clear of the surrounding terrain — then normal flight takes over.
        // Must run BEFORE the generic isFallFlying branch below, which would otherwise steer horizontally.
        if (this.state == State.SKY_LAUNCH_ASCEND) {
            if (!isSafeToCancel) {
                princeps.getPathingBehavior().secretInternalSegmentCancel();
            }
            princeps.getInputOverrideHandler().clearAllKeys();
            // Exact aim straight up (blockInteract=true bypasses the humanizer — a rocket climb must not wander).
            princeps.getLookBehavior().updateTarget(new Rotation(ctx.playerRotations().getYaw(), -90.0F), true);
            final double vy = ctx.player().getDeltaMovement().y;
            if (ctx.player().isFallFlying()) {
                if (ctx.player().position().y >= this.skyLaunchTargetY) {
                    this.state = State.FLYING; // clear of the opening: hand over to the flight controller
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if (this.skyLaunchFireworkCooldown > 0) {
                    this.skyLaunchFireworkCooldown--;
                } else if (vy < 1.2) {
                    if (princeps.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks)) {
                        ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
                        this.skyLaunchFireworkCooldown = 10; // matches the flight controller's firework cooldown
                    } else {
                        this.state = State.FLYING; // out of rockets mid-climb: glide, flight controller handles it
                    }
                }
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (ctx.player().onGround()) {
                if (++this.skyLaunchGroundTicks > 60) {
                    // could not get airborne (blocked jump, missing elytra...): fall back to the cliff takeoff
                    this.state = State.LOCATE_JUMP;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                princeps.getInputOverrideHandler().setInputForceState(Input.JUMP, true); // jump straight up
            } else if (vy < -0.05) {
                // past the apex: a fresh JUMP press (it was released while rising) deploys the elytra
                princeps.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Vertical rocket takeoff, phase 1: walk to the free-sky column found at takeoff time.
        if (this.state == State.SKY_LAUNCH_WALK) {
            final BetterBlockPos feet = ctx.playerFeet();
            if (feet.x == this.skyLaunchSpot.x && feet.z == this.skyLaunchSpot.z && ctx.player().onGround()) {
                this.state = State.SKY_LAUNCH_ASCEND;
                this.skyLaunchGroundTicks = 0;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(new GoalBlock(this.skyLaunchSpot), PathingCommandType.SET_GOAL_AND_PATH);
        }

        if (ctx.player().isFallFlying()) {
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            princeps.getInputOverrideHandler().clearAllKeys();
            behavior.tick();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            princeps.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            // A bot-chosen flight (auto-elytra dispatch) must take off autonomously: the cliff auto-jump
            // engages even when the user's manual elytraAutoJump preference is off.
            this.state = ctx.player().onGround()
                    && (Princeps.settings().elytraAutoJump.value || this.autoTakeoff)
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
            // Vertical rocket takeoff ("sky launch"): preferred start whenever a free-sky column is
            // available — current column first, else the nearest walkable one. In roofed dimensions
            // (the nether) ONLY standing on top of the bedrock roof; below it the cliff takeoff stays.
            // Checked once per activation (the column scan is not per-tick work).
            if (ctx.player().onGround() && Princeps.settings().elytraVerticalTakeoff.value
                    && !this.skyLaunchChecked && !shouldLandForSafety()) {
                this.skyLaunchChecked = true;
                final BetterBlockPos spot = findSkyLaunchSpot();
                if (spot != null) {
                    this.skyLaunchSpot = spot;
                    this.skyLaunchTargetY = computeSkyLaunchTargetY(spot);
                    this.skyLaunchGroundTicks = 0;
                    this.state = State.SKY_LAUNCH_WALK;
                    logDirect("Sky launch: rocketing up through the opening at " + spot.x + "," + spot.y + "," + spot.z);
                }
            }
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.goal == null) {
                this.goal = new GoalYLevel(31);
            }
            final IPathExecutor executor = princeps.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                    this.state = State.PAUSE;
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(princeps));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = princeps.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                princeps.getPathingBehavior().secretInternalSegmentCancel();
            }
            princeps.getInputOverrideHandler().clearAllKeys();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                princeps.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Whether a vertical rocket takeoff is possible from the player's current surroundings — used by
     * the auto-elytra dispatcher to check takeoff feasibility before committing to a flight.
     */
    public boolean canSkyLaunchHere() {
        return Princeps.settings().elytraVerticalTakeoff.value && findSkyLaunchSpot() != null;
    }

    /** Marks the current journey as bot-chosen: the takeoff runs fully autonomously (see {@link #autoTakeoff}). */
    public void enableAutoTakeoff() {
        this.autoTakeoff = true;
    }

    /**
     * A standable position whose column is fully open to the sky, for the vertical rocket takeoff:
     * the player's own column first, else the nearest walkable spot within a small radius. In a
     * roofed dimension (the nether) only positions ON TOP of the bedrock roof qualify — the launch
     * is never used below the ceiling, where the regular cliff takeoff remains the right method.
     */
    private BetterBlockPos findSkyLaunchSpot() {
        final BetterBlockPos feet = ctx.playerFeet();
        final boolean roofed = ctx.world().dimensionType().hasCeiling();
        if (roofed && feet.y < 120) {
            return null; // inside the nether: vertical launch is exclusively a roof maneuver
        }
        if (columnFreeToSky(feet)) {
            return feet;
        }
        for (int r = 1; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // ring only: nearest spots first
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        final BetterBlockPos pos = new BetterBlockPos(feet.x + dx, feet.y + dy, feet.z + dz);
                        if (roofed && pos.y < 120) {
                            continue;
                        }
                        if (MovementHelper.canWalkOn(ctx, pos.below())
                                && MovementHelper.canWalkThrough(ctx, pos)
                                && MovementHelper.canWalkThrough(ctx, pos.above())
                                && columnFreeToSky(pos)) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** True when every block above the head is passable up to the build limit — a clear vertical shaft. */
    private boolean columnFreeToSky(BetterBlockPos feet) {
        final int maxY = ctx.world().getMaxY();
        // Quick reject on the first blocks so the ring scan doesn't full-scan blocked columns.
        for (int y = feet.y + 2; y < maxY; y++) {
            if (!MovementHelper.canWalkThrough(ctx, new BetterBlockPos(feet.x, y, feet.z))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Climb target: safely above both the opening and the surrounding terrain (sampled via the
     * heightmap in a ring around the spot), so the handover to normal flight happens in open air.
     */
    private double computeSkyLaunchTargetY(BetterBlockPos spot) {
        int best = spot.y + 24;
        for (int dx = -16; dx <= 16; dx += 8) {
            for (int dz = -16; dz <= 16; dz += 8) {
                final int h = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, spot.x + dx, spot.z + dz);
                if (h + 8 > best) {
                    best = h + 8;
                }
            }
        }
        return Math.min(best, ctx.world().getMaxY() - 8);
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Princeps.getExecutor().execute(behavior::destroy);
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.behavior != null) {
            this.behavior.repackChunks();
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public void pathTo(BlockPos destination) {
        this.pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        // elytra navigation now works in any dimension (Nether/End/Overworld); the per-dimension flyable
        // space + Y-offset is handled by NetherPathfinderContext.forLevel.
        if (ctx.player() == null) {
            return;
        }
        this.onLostControl();
        this.predictingTerrain = Princeps.settings().elytraPredictTerrain.value;
        this.behavior = new ElytraBehavior(this.princeps, this, destination, appendDestination);
        if (ctx.world() != null) {
            this.behavior.repackChunks();
        }
        this.behavior.pathTo();
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }
        if (y < ctx.world().getMinY() || y >= ctx.world().getMaxY()) {
            throw new IllegalArgumentException("The y of the goal is outside the world height");
        }
        this.pathTo(new BlockPos(x, y, z));
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < Princeps.settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= Princeps.settings().elytraMinFireworksBeforeLanding.value) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING
                || this.state == State.SKY_LAUNCH_ASCEND);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        SKY_LAUNCH_WALK("Walking under a sky opening"),
        SKY_LAUNCH_ASCEND("Rocketing up through the opening"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            destroyBehaviorAsync();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IPrincepsProcess procThisTick = princeps.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IPrinceps princeps) {
            super(princeps, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = 8;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private boolean isInBounds(BlockPos pos) {
        return pos.getY() >= ctx.world().getMinY() && pos.getY() < ctx.world().getMaxY();
    }

    /**
     * Whether the elytra may land on the block at {@code pos}: the Nether landing blocks (unchanged), or
     * in any dimension any solid full-cube, non-hazard block.
     */
    private boolean isSafeBlock(BlockPos pos) {
        final BlockState state = ctx.world().getBlockState(pos);
        final Block block = state.getBlock();
        if (block == Blocks.NETHERRACK || block == Blocks.GRAVEL
                || (block == Blocks.NETHER_BRICKS && Princeps.settings().elytraAllowLandOnNetherFortress.value)) {
            return true;
        }
        if (block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POWDER_SNOW) {
            return false;
        }
        return state.isCollisionShapeFullBlock(ctx.world(), pos);
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= ctx.world().getMinY()) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(mut)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private static final int LANDING_COLUMN_HEIGHT = 15;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();

    // Hard cap on positions the landing search may explore. ~4000 horizontal cells ≈ a 35-block radius,
    // which is plenty to find a clear spot near the player, while guaranteeing the search can never stall
    // the game thread (the old uncapped 3D search took 5-8s in the open, freezing SP and desyncing on
    // servers). Worst case is now a single sub-tick pass; the common case returns on the first iteration.
    private static final int MAX_LANDING_SEARCH = 4000;

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        Queue<BetterBlockPos> queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - start.x) * (pos.x - start.x) + (pos.z - start.z) * (pos.z - start.z)).thenComparingInt(pos -> -pos.y));
        Set<BetterBlockPos> visited = new HashSet<>();
        LongOpenHashSet checkedPositions = new LongOpenHashSet();
        queue.add(start);
        int explored = 0;

        while (!queue.isEmpty() && explored++ < MAX_LANDING_SEARCH) {
            BetterBlockPos pos = queue.poll();
            if (ctx.world().isLoaded(pos) && isInBounds(pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, checkedPositions);
                if (actualLandingSpot != null && isColumnAir(actualLandingSpot, LANDING_COLUMN_HEIGHT) && hasAirBubble(actualLandingSpot.above(LANDING_COLUMN_HEIGHT)) && !badLandingSpots.contains(actualLandingSpot.above(LANDING_COLUMN_HEIGHT))) {
                    return actualLandingSpot.above(LANDING_COLUMN_HEIGHT);
                }
                // Horizontal frontier only: checkLandingSpot already walks the whole column downward from
                // each (x,z) air cell, so we never need to expand above/below — that 3D expansion is what
                // ballooned the search through open sky. A 2D frontier reaches every landing column at O(r^2).
                if (visited.add(pos.north())) queue.add(pos.north());
                if (visited.add(pos.east())) queue.add(pos.east());
                if (visited.add(pos.south())) queue.add(pos.south());
                if (visited.add(pos.west())) queue.add(pos.west());
            }
        }
        // The bounded land search found nothing nearby. If we're flying over open water, landing in the sea
        // would drown an unattended player — so look for the nearest reachable LAND instead (see below).
        return findNearestShore(start);
    }

    /**
     * Over open water the bounded land search finds nothing, but ditching in the sea would drown an
     * unattended player. So find the nearest standable LAND surface around the water level: scan down to the
     * water surface, then spiral outward (nearest-first) over the whole plane at that level looking for a
     * solid block you could stand on; if that plane has no land, raise it one block and retry, up to 5 above
     * the water. Searched out to render distance with cheap single-block checks (no per-cell column scan), so
     * it stays fast. Returns the elytra approach point above the shore, or null if we weren't over water /
     * there is genuinely no land in range.
     */
    private BetterBlockPos findNearestShore(BetterBlockPos start) {
        final int waterY = waterSurfaceY(start);
        if (waterY == Integer.MIN_VALUE) {
            return null; // not over water
        }
        final int maxR = Math.min(ctx.minecraft().options.getEffectiveRenderDistance(), 12) * 16;
        for (int dy = 0; dy <= 5; dy++) {
            final int y = waterY + dy;
            for (int r = 0; r <= maxR; r++) {
                final BetterBlockPos spot = scanShoreRing(start.x, start.z, y, r);
                if (spot != null) {
                    return spot;
                }
            }
        }
        return null;
    }

    /** Y of the first water block straight down from {@code start}, or {@link Integer#MIN_VALUE} if none. */
    private int waterSurfaceY(BetterBlockPos start) {
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(start.x, start.y, start.z);
        final int min = ctx.world().getMinY();
        while (mut.getY() >= min) {
            if (ctx.world().getBlockState(mut).getFluidState().is(FluidTags.WATER)) {
                return mut.getY();
            }
            mut.setY(mut.getY() - 1);
        }
        return Integer.MIN_VALUE;
    }

    /** Square ring of radius {@code r} centred on (cx,cz) at height {@code y}; the first standable shore wins. */
    private BetterBlockPos scanShoreRing(int cx, int cz, int y, int r) {
        if (r == 0) {
            return shoreSpotAt(cx, y, cz);
        }
        for (int dx = -r; dx <= r; dx++) {
            final BetterBlockPos a = shoreSpotAt(cx + dx, y, cz - r);
            if (a != null) return a;
            final BetterBlockPos b = shoreSpotAt(cx + dx, y, cz + r);
            if (b != null) return b;
        }
        for (int dz = -r + 1; dz <= r - 1; dz++) {
            final BetterBlockPos a = shoreSpotAt(cx - r, y, cz + dz);
            if (a != null) return a;
            final BetterBlockPos b = shoreSpotAt(cx + r, y, cz + dz);
            if (b != null) return b;
        }
        return null;
    }

    /** A standable land surface at (x,y,z) with open sky above -> the elytra approach point above it, else null. */
    private BetterBlockPos shoreSpotAt(int x, int y, int z) {
        final BlockPos surface = new BlockPos(x, y, z);
        if (!ctx.world().isLoaded(surface) || !isSafeBlock(surface)
                || !ctx.world().getBlockState(surface.above()).isAir()) {
            return null;
        }
        final BetterBlockPos landing = new BetterBlockPos(surface);
        final BetterBlockPos approach = landing.above(LANDING_COLUMN_HEIGHT);
        if (isColumnAir(landing, LANDING_COLUMN_HEIGHT) && hasAirBubble(approach) && !badLandingSpots.contains(approach)) {
            return approach;
        }
        return null;
    }
}
