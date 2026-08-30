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

package princeps.pathing.movement;

import princeps.Princeps;
import princeps.api.IPrinceps;
import princeps.api.pathing.movement.IMovement;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.utils.*;
import princeps.api.utils.input.Input;
import princeps.behavior.PathingBehavior;
import princeps.utils.BlockStateInterface;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IPrinceps princeps;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    protected Movement(IPrinceps princeps, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.princeps = princeps;
        this.ctx = princeps.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IPrinceps princeps, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(princeps, src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) princeps.getPathingBehavior()).pathStart());
    }

    /**
     * Look-gate tolerances for firing a facing-critical jump. Yaw must be within {@link #LOOK_GATE_JUMP_YAW}
     * of the heading; pitch is lenient ({@code moveTowards} pins pitch near the current pitch). Chosen ABOVE
     * the humanization noise floor (tremor &lt;0.5°, cruise micro-jitter &le;0.80°) so the gate can actually
     * latch, and loose enough (~one bell-curve turn tick) that a normal, already-aligning approach passes
     * immediately — only a badly mis-oriented arrival is held.
     */
    protected static final float LOOK_GATE_JUMP_YAW = 8.0f;
    protected static final float LOOK_GATE_JUMP_PITCH = 20.0f;

    /**
     * True when the server-side rotation has converged on this tick's look target (within tolerance), or when
     * the movement demands no facing this tick. Reads {@code ctx.playerRotations()}, which resolves to the
     * packet-captured {@code serverRotation} under freeLook/SILENT mode (NOT the stale visible rotation), so
     * it is correct in both look modes. Used to hold facing-critical jumps until the humanized turn arrives.
     */
    protected boolean lookConverged(MovementState state, float yawTol, float pitchTol) {
        return state.getTarget().getRotation()
                .map(target -> ctx.playerRotations().isCloseTo(target, yawTol, pitchTol))
                .orElse(true);
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        // DO NOT SWIM OUT OF A CORRIDOR YOU ARE MEANT TO STAND IN. This is the generic anti-drowning reflex: feet in
        // liquid, so hold JUMP until the body is 0.6 above the destination. For an excavation it is precisely wrong,
        // and the arithmetic is the proof. Run e71652ca: the corridor step had dest.y = -51, so this let go at
        // -50.4 -- and the bot was found parked at y = -50.282, floating, for 1191 consecutive ticks.
        //
        // Everything the digger needs dies at that moment, and all of it for the same reason: it is no longer ON
        // anything. snakeReadyToSwing wants onGround, so no face is ever struck; MovementTraverse's bridge branch
        // wants standingOnABlock, so the one licensed floor block can never be placed. A flooded band therefore ends
        // the run even though the rock under the corridor was never gone -- only hidden under half a metre of water.
        //
        // A route that licensed wading through this cell has already judged it safe to have a body in (see
        // WadeLicence, which names the two BODY cells of one corridor step and never the floor). Letting go of JUMP
        // is all that is needed here: a player with no input sinks in water, so the body settles back down.
        //
        // AND THE LICENCE IS ABOUT A CELL, NOT ABOUT WHAT IS IN IT. That distinction is the difference between a
        // slow corridor and a dead bot: a licence naming this cell says nothing about the fluid that arrived in it
        // afterwards, and lava arrives the same way water does. Suppressing the climb-out for a licensed cell full
        // of LAVA would take away the one reflex that saves the run. Every other reader of this licence asks the
        // same water question (MovementHelper.getMiningDurationTicks, prepared below); this one must too.
        final net.minecraft.world.level.block.state.BlockState atTheFeet =
                BlockStateInterface.get(ctx, ctx.playerFeet());
        final boolean mayStandInThisFluid =
                MovementHelper.currentRouteWadeLicence(princeps).permitsWading(ctx.playerFeet())
                        && MovementHelper.isWater(atTheFeet)
                        && atTheFeet.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) && ctx.player().position().y < dest.y + 0.6
                && !mayStandInThisFluid) {
            currentState.setInput(Input.JUMP, true);
        }
        if (ctx.player().isInWall()) {
            ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
        currentState.getTarget().getRotation().ifPresent(rotation ->
                princeps.getLookBehavior().updateTarget(
                        rotation,
                        currentState.getTarget().hasToForceRotations(),
                        currentState.getTarget().getAimIntent()));
        princeps.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) -> {
            princeps.getInputOverrideHandler().setInputForceState(input, forced);
        });
        currentState.getInputStates().clear();

        // If the current status indicates a completed movement
        if (currentState.getStatus().isComplete()) {
            princeps.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // While actually FALLING (opt-in per movement type, see skipFallPassedBlocks), ignore to-break column
        // blocks the player has already fallen PAST: water or gravel pouring into the vacated shaft above can
        // never be reached again, but the blind fallback below would aim the look rigidly straight up at it for
        // the entire fall AND hold this movement in PREPPING — starving updateState, so the landing centering
        // never ran. Blocks at or below the player keep the full handling (they genuinely block the landing).
        final boolean falling = this.skipFallPassedBlocks()
                && !ctx.player().onGround()
                && ctx.player().getDeltaMovement().y < -0.1;
        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (falling && blockPos.y > ctx.playerFeet().y + 1) {
                continue;
            }
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() && Princeps.settings().pauseMiningForFallingBlocks.value) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
                // ...and can't break water either. The search that produced this route priced this exact cell as a
                // step through the water rather than as a wall (WadeLicence), so treating it as something to mine
                // here would be the two-stroke the licences exist to prevent: a plan the driver refuses, a refusal
                // that does not change the plan. Unlicensed cells fall through and behave exactly as before --
                // including lava, which no licence ever names.
                // Water only, and only where the block IS the water: a waterlogged stair also answers isWater and
                // would be skipped here as "nothing to mine" while standing in the way as a wall.
                final net.minecraft.world.level.block.state.BlockState inTheWay =
                        BlockStateInterface.get(ctx, blockPos);
                if (MovementHelper.currentRouteWadeLicence(princeps).permitsWading(blockPos)
                        && MovementHelper.isWater(inTheWay)
                        && inTheWay.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                    continue;
                }
                somethingInTheWay = true;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(MovementState.MovementTarget.forBreak(rotTowardsBlock));
                    if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    return false;
                }
                //get rekt minecraft
                //i'm doing it anyway
                //i dont care if theres snow in the way!!!!!!!
                //you dont own me!!!!
                state.setTarget(MovementState.MovementTarget.forBreak(RotationUtils.calcRotationFromVec3d(
                        ctx.playerHead(), VecUtils.getBlockPosCenter(blockPos), ctx.playerRotations())));
                // don't check selectedblock on this one, this is a fallback when we can't see any face directly, it's intended to be breaking the "incorrect" block
                // ...but only press once the aim has ARRIVED at the intended rotation (tremor-tolerant closeness —
                // isLookingAt can never pass here, no visible face). With the bell-curve arc the aim sweeps over
                // several ticks, and a held press would blind-dig every foreground block the crosshair crosses on
                // the way; at arrival the deliberate blind dig proceeds exactly as before.
                if (ctx.playerRotations().isCloseTo(state.getTarget().rotation, 0.75f, 0.55f)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    /**
     * Whether {@link #prepared} may skip to-break blocks the player has already fallen past while airborne.
     * Default false; only movements whose to-break column is anchored at the drop TOP (MovementFall) opt in —
     * movements that legitimately break blocks ABOVE while airborne (pillar/ascend/parkour at a jump apex) must not.
     */
    protected boolean skipFallPassedBlocks() {
        return false;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        // Null-safe: a movement synthesized outside runBackwards (e.g. a SmoothTraverse chord) could miss its
        // checkLoadedChunk call, and unboxing null here crashed the whole game (live-test crash 2026-07-06).
        // The creation sites are fixed to call checkLoadedChunk, but a conservative false (= keep the executor's
        // cost-increase check active) must never be a crash.
        return calculatedWhileLoaded != null && calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
