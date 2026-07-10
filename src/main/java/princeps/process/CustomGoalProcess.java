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
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalXZ;
import princeps.api.process.ICustomGoalProcess;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.interfaces.IGoalRenderPos;
import princeps.process.elytra.ElytraBehavior;
import princeps.utils.PrincepsProcessHelper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * As set by ExamplePrincepsControl or something idk
 *
 * @author leijurv
 */
public final class CustomGoalProcess extends PrincepsProcessHelper implements ICustomGoalProcess {

    /**
     * The current goal
     */
    private Goal goal;

    /**
     * The most recent goal. Not invalidated upon {@link #onLostControl()}
     */
    private Goal mostRecentGoal;

    /**
     * The current process state.
     *
     * @see State
     */
    private State state;

    public CustomGoalProcess(Princeps princeps) {
        super(princeps);
    }

    @Override
    public void setGoal(Goal goal) {
        this.goal = goal;
        this.mostRecentGoal = goal;
        if (princeps.getElytraProcess().isActive()) {
            princeps.getElytraProcess().pathTo(goal);
        }
        if (this.state == State.NONE) {
            this.state = State.GOAL_SET;
        }
        if (this.state == State.EXECUTING) {
            this.state = State.PATH_REQUESTED;
        }
    }

    @Override
    public void path() {
        this.state = State.PATH_REQUESTED;
        maybeDispatchElytra();
    }

    /**
     * Auto-elytra dispatcher: like allowBreak/allowPlace/allowParkour, {@code allowElytra} lets the bot
     * choose elytra travel on its own. When the goal is far enough for the current region (overworld
     * surface / nether interior / nether roof / end — each with its own distance threshold), the player
     * has an elytra equipped and enough rockets for the trip, and a takeoff is actually feasible (a
     * free-sky column for the vertical launch, or the cliff auto-jump), the elytra process is engaged.
     * The walking goal stays set underneath: the elytra process has higher priority while active, and
     * when it lands and releases control this process resumes and walks the last stretch to the exact
     * goal — a seamless fly-then-walk journey. In caves (no sky access, no auto-jump) this never fires.
     */
    private void maybeDispatchElytra() {
        if (!Princeps.settings().allowElytra.value || this.goal == null || ctx.player() == null) {
            return;
        }
        if (princeps.getElytraProcess().isActive() || ctx.player().isFallFlying()) {
            return;
        }
        if (!(princeps.getElytraProcess() instanceof ElytraProcess elytra)) {
            return; // elytra unsupported on this system (NullElytraProcess)
        }
        final BetterBlockPos pos;
        if (this.goal instanceof IGoalRenderPos renderPos) {
            pos = new BetterBlockPos(renderPos.getGoalPos());
        } else if (this.goal instanceof GoalXZ xz) {
            pos = new BetterBlockPos(xz.getX(), ctx.playerFeet().y, xz.getZ());
        } else {
            return; // no spatial target (e.g. a pure y-level goal): walking handles it
        }
        final double dist = Math.hypot(
                pos.x + 0.5 - ctx.player().position().x,
                pos.z + 0.5 - ctx.player().position().z);
        final double threshold = elytraAutoThreshold();
        if (threshold <= 0 || dist < threshold) {
            return;
        }
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        final boolean chestUsable = chest.getItem() == Items.ELYTRA
                && chest.getMaxDamage() - chest.getDamageValue() >= Princeps.settings().elytraMinimumDurability.value;
        if (!chestUsable) {
            // Auto-equip: swap a usable elytra from the inventory into the chest slot before takeoff.
            if (!Princeps.settings().elytraAutoEquip.value
                    || !((Princeps) princeps).getInventoryBehavior().equipElytraFromInventory()) {
                return;
            }
        }
        // Enough rockets for the whole trip plus a reserve — never strand mid-flight.
        final int needed = (int) Math.ceil(dist / Math.max(1.0, Princeps.settings().elytraAutoBlocksPerFirework.value))
                + Math.max(0, Princeps.settings().elytraAutoFireworkReserve.value);
        if (countFireworks() < needed) {
            return;
        }
        // Takeoff feasibility. Inside the nether (below the roof) and in overworld caves the cliff
        // auto-jump is the always-attemptable method — dispatch unconditionally; if no jump-off spot or
        // no flyable space is found, the elytra process cancels itself and this walking goal simply
        // resumes (automatic fallback). On open surfaces (overworld, nether roof, end) require a
        // free-sky column for the vertical launch.
        final boolean roofed = ctx.world().dimensionType().hasCeiling();
        final boolean netherInterior = roofed && ctx.playerFeet().y < 120;
        final boolean overworldCave = !roofed && !ctx.world().dimensionType().hasEnderDragonFight()
                && !ctx.world().canSeeSky(ctx.playerFeet().above());
        if (!netherInterior && !overworldCave && !elytra.canSkyLaunchHere()) {
            return;
        }
        logDirect(String.format("Auto-elytra: %.0f blocks to goal (threshold %.0f) — flying", dist, threshold));
        elytra.pathTo(pos);
        elytra.enableAutoTakeoff();
    }

    /** Region-specific auto-elytra distance threshold; {@code <= 0} disables the region. */
    private double elytraAutoThreshold() {
        if (ctx.world().dimensionType().hasCeiling()) { // the nether
            return ctx.playerFeet().y >= 120
                    ? Princeps.settings().elytraAutoDistanceNetherRoof.value
                    : Princeps.settings().elytraAutoDistanceNether.value;
        }
        if (ctx.world().dimensionType().hasEnderDragonFight()) { // the end
            return Princeps.settings().elytraAutoDistanceEnd.value;
        }
        // Overworld: open surface vs cave (no sky above the player).
        return ctx.world().canSeeSky(ctx.playerFeet().above())
                ? Princeps.settings().elytraAutoDistanceOverworld.value
                : Princeps.settings().elytraAutoDistanceCaves.value;
    }

    private int countFireworks() {
        final NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        return qty;
    }

    @Override
    public Goal getGoal() {
        return this.goal;
    }

    @Override
    public Goal mostRecentGoal() {
        return this.mostRecentGoal;
    }

    @Override
    public boolean isActive() {
        return this.state != State.NONE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (this.state) {
            case GOAL_SET:
                return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            case PATH_REQUESTED:
                // return FORCE_REVALIDATE_GOAL_AND_PATH just once
                PathingCommand ret = new PathingCommand(this.goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
                this.state = State.EXECUTING;
                return ret;
            case EXECUTING:
                if (calcFailed) {
                    onLostControl();
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if (this.goal == null || (this.goal.isInGoal(ctx.playerFeet()) && this.goal.isInGoal(princeps.getPathingBehavior().pathStart()))) {
                    onLostControl(); // we're there xd
                    if (Princeps.settings().disconnectOnArrival.value) {
                        if (ctx.world() instanceof ClientLevel clientLevel) {
                            clientLevel.disconnect(Component.literal("[Princeps] Arrived at goal!"));
                        }
                    }
                    if (Princeps.settings().notificationOnPathComplete.value) {
                        logNotification("Pathing complete", false);
                    }
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
            default:
                throw new IllegalStateException("Unexpected state " + this.state);
        }
    }

    @Override
    public void onLostControl() {
        this.state = State.NONE;
        this.goal = null;
    }

    @Override
    public String displayName0() {
        return "Custom Goal " + this.goal;
    }

    protected enum State {
        NONE,
        GOAL_SET,
        PATH_REQUESTED,
        EXECUTING
    }
}
