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

package princeps.pathing.path;

import princeps.Princeps;
import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.movement.ActionCosts;
import princeps.api.pathing.movement.IMovement;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.pathing.path.IPathExecutor;
import princeps.api.utils.*;
import princeps.api.utils.input.Input;
import princeps.behavior.PathingBehavior;
import princeps.pathing.calc.AbstractNodeCostSearch;
import princeps.pathing.movement.Movement;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.movement.movements.*;
import princeps.process.BuilderProcess.ModelProtection;
import princeps.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.phys.Vec3;
import java.util.*;

import static princeps.api.pathing.movement.MovementStatus.*;

/**
 * Behavior to execute a precomputed path
 *
 * @author leijurv
 */
public class PathExecutor implements IPathExecutor, Helper {

    private static final double MAX_MAX_DIST_FROM_PATH = 3;
    private static final double MAX_DIST_FROM_PATH = 2;

    /**
     * Default value is equal to 10 seconds. It's find to decrease it, but it must be at least 5.5s (110 ticks).
     * For more information, see issue #102.
     *
     * @see <a href="https://github.com/cabaletta/princeps/issues/102">Issue #102</a>
     * @see <a href="https://i.imgur.com/5s5GLnI.png">Anime</a>
     */
    private static final double MAX_TICKS_AWAY = 200;

    private final IPath path;
    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    private boolean failed;
    private boolean recalcBP = true;
    private HashSet<BlockPos> toBreak = new HashSet<>();
    private HashSet<BlockPos> toPlace = new HashSet<>();
    private HashSet<BlockPos> toWalkInto = new HashSet<>();

    private final PathingBehavior behavior;
    private final IPlayerContext ctx;

    private boolean sprintNextTick;

    /**
     * The rules this route was planned under, captured once and never re-read.
     *
     * <p>Final on purpose. The thing it replaces — a predicate on the builder that the executor consulted live —
     * could change while the route was in flight, and did: every placement reset the fields its answer depended on,
     * so a route that was allowed to bridge lost that permission the moment it bridged. A field that cannot change
     * cannot do that.
     */
    private final PlacementLicence placementLicence;

    /** The other half of the same promise, and final for the same reason. See {@link WadeLicence}. */
    private final WadeLicence wadeLicence;
    private final ModelProtection modelProtection;
    private final Object excavationApproachToken;

    public PathExecutor(PathingBehavior behavior, IPath path) {
        this(behavior, path, PlacementLicence.UNRESTRICTED, WadeLicence.NONE);
    }

    /**
     * @param placementLicence the licence of the CalculationContext that produced {@code path}. Passed in rather
     *                         than read from the behavior, because by the time a search's callback runs, the
     *                         behavior's own context field may already belong to a newer command.
     * @param wadeLicence      likewise, and it must come from the SAME context object: a search that priced a step
     *                         through water and a driver that then tries to mine that water is the two-stroke stall
     *                         the two licences exist to make impossible.
     */
    public PathExecutor(PathingBehavior behavior, IPath path, PlacementLicence placementLicence,
                        WadeLicence wadeLicence) {
        this(behavior, path, placementLicence, wadeLicence, null);
    }

    public PathExecutor(PathingBehavior behavior, IPath path, PlacementLicence placementLicence,
                        WadeLicence wadeLicence, ModelProtection modelProtection) {
        this(behavior, path, placementLicence, wadeLicence, modelProtection, null);
    }

    public PathExecutor(PathingBehavior behavior, IPath path, PlacementLicence placementLicence,
                        WadeLicence wadeLicence, Object excavationApproachToken) {
        this(behavior, path, placementLicence, wadeLicence, null, excavationApproachToken);
    }

    public PathExecutor(PathingBehavior behavior, IPath path, PlacementLicence placementLicence,
                        WadeLicence wadeLicence, ModelProtection modelProtection, Object excavationApproachToken) {
        this.behavior = behavior;
        this.ctx = behavior.ctx;
        this.path = path;
        this.pathPosition = 0;
        this.placementLicence = placementLicence == null ? PlacementLicence.UNRESTRICTED : placementLicence;
        this.wadeLicence = wadeLicence == null ? WadeLicence.NONE : wadeLicence;
        this.modelProtection = modelProtection;
        this.excavationApproachToken = excavationApproachToken;
    }

    public Object excavationApproachToken() {
        return excavationApproachToken;
    }

    public boolean allowsModelRemoval(BlockPos target, princeps.api.process.IPrincepsProcess controlling,
                                      java.util.function.BooleanSupplier continuing) {
        return modelProtection == null || modelProtection.allowsRemoval(target, ctx, controlling, continuing);
    }

    @Override
    public PlacementLicence placementLicence() {
        return placementLicence;
    }

    @Override
    public WadeLicence wadeLicence() {
        return wadeLicence;
    }

    /** Same-tick re-entrancy depth of {@link #onTick()} (SUCCESS-advance / skips recurse); 0 = outermost call. */
    private int tickRecursionDepth;
    /** Floor for the backward rewind scan within ONE tick: a movement that just reported SUCCESS this tick must
     *  never be rewound to in the SAME tick — with wide chord corridors (which contain cells past their own dest)
     *  a success->rewind->success cycle otherwise mutually recurses to a StackOverflowError (review-confirmed). */
    private int noRewindBelow;

    /**
     * Tick this executor
     *
     * @return True if a movement just finished (and the player is therefore in a "stable" state, like,
     * not sneaking out over lava), false otherwise
     */
    public boolean onTick() {
        if (tickRecursionDepth == 0) {
            noRewindBelow = 0;
        }
        if (tickRecursionDepth >= 8) {
            // Defense-in-depth: no success/skip/rewind pairing may ever recurse unboundedly within one game tick.
            // Bail out and let the next real tick re-evaluate from settled state instead of overflowing the stack.
            return false;
        }
        tickRecursionDepth++;
        try {
            return onTickInternal();
        } finally {
            tickRecursionDepth--;
        }
    }

    private boolean onTickInternal() {
        if (pathPosition == path.length() - 1) {
            pathPosition++;
        }
        if (pathPosition >= path.length()) {
            return true; // stop bugging me, I'm done
        }
        Movement movement = (Movement) path.movements().get(pathPosition);
        BetterBlockPos whereAmI = ctx.playerFeet();
        if (!movement.getValidPositions().contains(whereAmI)) {
            for (int i = noRewindBelow; i < pathPosition && i < path.length(); i++) {//this happens for example when you lag out and get teleported back a couple blocks
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    int previousPos = pathPosition;
                    pathPosition = i;
                    for (int j = pathPosition; j <= previousPos; j++) {
                        path.movements().get(j).reset();
                    }
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
            // Forward skip. The original rule (start at +3, land-on-node i-1 semantics: "the movement tells us
            // when it's done, e.g. sneak placing") stays for plain lattice chains — but any-angle chords need two
            // additional, bench-derived recoveries at +1/+2 (executor-sim, 300 maps, 0 fails only with BOTH):
            //  (a) feet INSIDE a chord candidate's swept corridor: momentum from a fall/splice lands the body
            //      several blocks INTO the next chord (live-reproduced: "taken too long (114 ticks, expected
            //      13.17)" + FAR AWAY drift arcs) — jump straight ONTO the chord (p = i; the classic i-1 would
            //      strand, its dest is already behind the feet).
            //  (b) feet EXACTLY ON a lattice candidate's dest cell: sprint momentum barrels through a short
            //      lattice step sandwiched behind a chord — exact dest-cell equality (including y) means that
            //      walk is factually complete; a body cannot stand there while still mid-action on the current
            //      movement. Jump onto it (p = i; it reports SUCCESS immediately).
            // FAR skips (>= +3) never enter a chord: the wide corridors of PARALLEL path rows (switchbacks)
            // overlap, and a far jump onto the wrong end of one ping-pongs the executor (bench-caught).
            for (int i = pathPosition + 1; i < path.length() - 1; i++) {
                final Movement candidate = (Movement) path.movements().get(i);
                final boolean chordCandidate = candidate instanceof SmoothTraverse;
                boolean jumpTo = false;
                if (i < pathPosition + 3) {
                    jumpTo = (chordCandidate && candidate.getValidPositions().contains(whereAmI))
                            || (!chordCandidate && candidate.getDest().equals(whereAmI));
                    if (!jumpTo) {
                        continue;
                    }
                } else if (chordCandidate) {
                    continue;
                }
                if (jumpTo || candidate.getValidPositions().contains(whereAmI)) {
                    if (i - pathPosition > 2) {
                        logDebug("Skipping forward " + (i - pathPosition) + " steps, to " + i);
                    }
                    //System.out.println("Double skip sundae")
                    pathPosition = jumpTo ? i : i - 1;
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
        }
        Tuple<Double, BlockPos> status = closestPathPos(path);
        if (possiblyOffPath(status, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            System.out.println("FAR AWAY FROM PATH FOR " + ticksAway + " TICKS. Current distance: " + status.getA() + ". Threshold: " + MAX_DIST_FROM_PATH);
            if (ticksAway > MAX_TICKS_AWAY) {
                logDebug("Too far away from path for too long, cancelling path");
                cancel();
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(status, MAX_MAX_DIST_FROM_PATH)) { // ok, stop right away, we're way too far.
            logDebug("too far from path");
            cancel();
            return false;
        }
        //long start = System.nanoTime() / 1000000L;
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
            if (i < 0 || i >= path.movements().size()) {
                continue;
            }
            Movement m = (Movement) path.movements().get(i);
            List<BlockPos> prevBreak = m.toBreak(bsi);
            List<BlockPos> prevPlace = m.toPlace(bsi);
            List<BlockPos> prevWalkInto = m.toWalkInto(bsi);
            m.resetBlockCache();
            if (!prevBreak.equals(m.toBreak(bsi))) {
                recalcBP = true;
            }
            if (!prevPlace.equals(m.toPlace(bsi))) {
                recalcBP = true;
            }
            if (!prevWalkInto.equals(m.toWalkInto(bsi))) {
                recalcBP = true;
            }
        }
        if (recalcBP) {
            HashSet<BlockPos> newBreak = new HashSet<>();
            HashSet<BlockPos> newPlace = new HashSet<>();
            HashSet<BlockPos> newWalkInto = new HashSet<>();
            for (int i = pathPosition; i < path.movements().size(); i++) {
                Movement m = (Movement) path.movements().get(i);
                newBreak.addAll(m.toBreak(bsi));
                newPlace.addAll(m.toPlace(bsi));
                newWalkInto.addAll(m.toWalkInto(bsi));
            }
            toBreak = newBreak;
            toPlace = newPlace;
            toWalkInto = newWalkInto;
            recalcBP = false;
        }
        /*long end = System.nanoTime() / 1000000L;
        if (end - start > 0) {
            System.out.println("Recalculating break and place took " + (end - start) + "ms");
        }*/
        if (pathPosition < path.movements().size() - 1) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (!behavior.princeps.bsi.worldContainsLoadedChunk(next.getDest().x, next.getDest().z)) {
                logDebug("Pausing since destination is at edge of loaded chunks");
                clearKeys();
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            costEstimateIndex = pathPosition;
            // do this only once, when the movement starts, and deliberately get the cost as cached when this path was calculated, not the cost as it is right now
            currentMovementOriginalCostEstimate = movement.getCost();
            for (int i = 1; i < Princeps.settings().costVerificationLookahead.value && pathPosition + i < path.length() - 1; i++) {
                if (((Movement) path.movements().get(pathPosition + i)).calculateCost(behavior.secretInternalGetCalculationContext()) >= ActionCosts.COST_INF && canCancel) {
                    logDebug("Something has changed in the world and a future movement has become impossible. Cancelling.");
                    cancel();
                    return true;
                }
            }
        }
        double currentCost = movement.recalculateCost(behavior.secretInternalGetCalculationContext());
        if (currentCost >= ActionCosts.COST_INF && canCancel) {
            logDebug("Something has changed in the world and this movement has become impossible. Cancelling.");
            cancel();
            return true;
        }
        if (!movement.calculatedWhileLoaded() && currentCost - currentMovementOriginalCostEstimate > Princeps.settings().maxCostIncrease.value && canCancel) {
            // don't do this if the movement was calculated while loaded
            // that means that this isn't a cache error, it's just part of the path interfering with a later part
            logDebug("Original cost " + currentMovementOriginalCostEstimate + " current cost " + currentCost + ". Cancelling.");
            cancel();
            return true;
        }
        if (shouldPause()) {
            logDebug("Pausing since current best path is a backtrack");
            clearKeys();
            return true;
        }
        MovementStatus movementStatus = movement.update();
        if (movementStatus == UNREACHABLE || movementStatus == FAILED) {
            logDebug("Movement returns status " + movementStatus);
            cancel();
            return true;
        }
        if (movementStatus == SUCCESS) {
            //System.out.println("Movement done, next path");
            pathPosition++;
            // A movement that just SUCCEEDED must not be rewound to within the SAME tick: a chord's wide corridor
            // contains cells past its own dest, so the backward scan would otherwise bounce success<->rewind in
            // unbounded same-tick recursion (review-confirmed StackOverflowError). Cross-tick rewinds (real lag
            // teleports) are unaffected — the floor resets on the next outermost tick.
            noRewindBelow = Math.max(noRewindBelow, pathPosition);
            onChangeInPathPosition();
            onTick();
            return true;
        } else {
            sprintNextTick = shouldSprintNextTick();
            if (!sprintNextTick) {
                ctx.player().setSprinting(false); // letting go of control doesn't make you stop sprinting actually
            }
            ticksOnCurrent++;
            if (ticksOnCurrent > currentMovementOriginalCostEstimate + Princeps.settings().movementTimeoutTicks.value) {
                // only cancel if the total time has exceeded the initial estimate
                // as you break the blocks required, the remaining cost goes down, to the point where
                // ticksOnCurrent is greater than recalculateCost + 100
                // this is why we cache cost at the beginning, and don't recalculate for this comparison every tick
                logDebug("This movement has taken too long (" + ticksOnCurrent + " ticks, expected " + currentMovementOriginalCostEstimate + "). Cancelling.");
                cancel();
                return true;
            }
        }
        return canCancel; // movement is in progress, but if it reports cancellable, PathingBehavior is good to cut onto the next path
    }

    private Tuple<Double, BlockPos> closestPathPos(IPath path) {
        final List<IMovement> movements = path.movements();
        final int nearbyFrom = Math.max(0, pathPosition - 3);
        final int nearbyTo = Math.min(movements.size(), pathPosition + 8);
        double best = -1;
        BlockPos bestPos = null;
        for (int i = nearbyFrom; i < nearbyTo; i++) {
            for (BlockPos pos : ((Movement) movements.get(i)).getValidPositions()) {
                double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
                if (dist < best || best == -1) {
                    best = dist;
                    bestPos = pos;
                }
            }
        }
        if (best >= 0 && best <= MAX_DIST_FROM_PATH) {
            return new Tuple<>(best, bestPos);
        }

        // Teleports and large lag corrections can place the player far along the path. Preserve the
        // original full-route answer as a fallback, but avoid that O(path) scan during normal movement.
        best = -1;
        bestPos = null;
        for (IMovement movement : movements) {
            for (BlockPos pos : ((Movement) movement).getValidPositions()) {
                double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
                if (dist < best || best == -1) {
                    best = dist;
                    bestPos = pos;
                }
            }
        }
        return new Tuple<>(best, bestPos);
    }

    private boolean shouldPause() {
        Optional<AbstractNodeCostSearch> current = behavior.getInProgress();
        if (!current.isPresent()) {
            return false;
        }
        if (!ctx.player().onGround()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, ctx.playerFeet().below())) {
            // we're in some kind of sketchy situation, maybe parkouring
            return false;
        }
        if (!MovementHelper.canWalkThrough(ctx, ctx.playerFeet()) || !MovementHelper.canWalkThrough(ctx, ctx.playerFeet().above())) {
            // suffocating?
            return false;
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        Optional<IPath> currentBest = current.get().bestPathSoFar();
        if (!currentBest.isPresent()) {
            return false;
        }
        List<BetterBlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // not long enough yet to justify pausing, its far from certain we'll actually take this route
        }
        // the first block of the next path will always overlap
        // no need to pause our very last movement when it would have otherwise cleanly exited with MovementStatus SUCCESS
        positions = positions.subList(1, positions.size());
        return positions.contains(ctx.playerFeet());
    }

    private boolean possiblyOffPath(Tuple<Double, BlockPos> status, double leniency) {
        double distanceFromPath = status.getA();
        if (distanceFromPath > leniency) {
            // when we're midair in the middle of a fall, we're very far from both the beginning and the end, but we aren't actually off path
            if (path.movements().get(pathPosition) instanceof MovementFall) {
                BlockPos fallDest = path.positions().get(pathPosition + 1); // .get(pathPosition) is the block we fell off of
                return VecUtils.entityFlatDistanceToCenter(ctx.player(), fallDest) >= leniency; // ignore Y by using flat distance
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * Regardless of current path position, snap to the current player feet if possible
     *
     * @return Whether or not it was possible to snap to the current player feet
     */
    public boolean snipsnapifpossible() {
        if (!ctx.player().onGround() && ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
            // if we're falling in the air, and not in water, don't splice
            return false;
        } else {
            // we are either onGround or in liquid
            if (ctx.player().getDeltaMovement().y < -0.1) {
                // if we are strictly moving downwards (not stationary)
                // we could be falling through water, which could be unsafe to splice
                return false; // so don't
            }
        }
        int index = path.positions().indexOf(ctx.playerFeet());
        if (index == -1) {
            return false;
        }
        pathPosition = index; // jump directly to current position
        clearKeys();
        return true;
    }

    private boolean shouldSprintNextTick() {
        boolean requested = behavior.princeps.getInputOverrideHandler().isInputForcedDown(Input.SPRINT);

        // we'll take it from here, no need for minecraft to see we're holding down control and sprint for us
        behavior.princeps.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        // first and foremost, if allowSprint is off, or if we don't have enough hunger, don't try and sprint
        if (!Princeps.settings().allowSprint.value || ctx.player().getFoodData().getFoodLevel() <= 6) {
            return false;
        }
        IMovement current = path.movements().get(pathPosition);

        // traverse requests sprinting, so we need to do this check first
        if (current instanceof MovementTraverse && pathPosition < path.length() - 3) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend && sprintableAscend(ctx, (MovementTraverse) current, (MovementAscend) next, path.movements().get(pathPosition + 2))) {
                if (skipNow(ctx, current)) {
                    logDebug("Skipping traverse to straight ascend");
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    behavior.princeps.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    return true;
                } else {
                    logDebug("Too far to the side to safely sprint ascend");
                }
            }
        }

        // if the movement requested sprinting, then we're done
        if (requested) {
            return true;
        }

        // however, descend and ascend don't request sprinting, because they don't know the context of what movement comes after it
        if (current instanceof MovementDescend) {

            if (pathPosition < path.length() - 2) {
                // keep this out of onTick, even if that means a tick of delay before it has an effect
                IMovement next = path.movements().get(pathPosition + 1);
                if (MovementHelper.canUseFrostWalker(ctx, next.getDest().below())) {
                    // frostwalker only works if you cross the edge of the block on ground so in some cases we may not overshoot
                    // Since MovementDescend can't know the next movement we have to tell it
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = Princeps.settings().allowPlace.value && behavior.princeps.getInventoryBehavior().hasGenericThrowaway() && next instanceof MovementParkour; // traverse doesn't react fast enough
                        // this is true if the next movement does not ascend or descends and goes into the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend
                        // in that case current.getDirection() is e.g. (0, -1, 1) and next.getDirection() is e.g. (0, 0, 3) so the cross product of (0, 0, 1) and (0, 0, 3) is taken, which is (0, 0, 0) because the vectors are colinear (don't form a plane)
                        // since movements in exactly the opposite direction (e.g. descend (0, -1, 1) and traverse (0, 0, -1)) would also pass this check we also have to rule out that case
                        // we can do that by adding the directions because traverse is always 1 long like descend and parkour can't jump through current.getSrc().down()
                        boolean sameFlatDirection = !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO); // here's why you learn maths in school
                        if (sameFlatDirection && !couldPlaceInstead) {
                            ((MovementDescend) current).forceSafeMode();
                        }
                    }
                }
            }
            if (((MovementDescend) current).safeMode() && !((MovementDescend) current).skipToAscend()) {
                logDebug("Sprinting would be unsafe");
                return false;
            }

            if (pathPosition < path.length() - 2) {
                IMovement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend && current.getDirection().above().equals(next.getDirection().below())) {
                    // a descend then an ascend in the same direction
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    // okay to skip clearKeys and / or onChangeInPathPosition here since this isn't possible to repeat, since it's asymmetric
                    logDebug("Skipping descend to straight ascend");
                    return true;
                }
                if (canSprintFromDescendInto(ctx, current, next)) {

                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        IMovement next_next = path.movements().get(pathPosition + 2);
                        if (next_next instanceof MovementDescend && !canSprintFromDescendInto(ctx, next, next_next)) {
                            return false;
                        }

                    }
                    if (ctx.playerFeet().equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        onTick();
                    }

                    return true;
                }
                //logDebug("Turning off sprinting " + movement + " " + next + " " + movement.getDirection() + " " + next.getDirection().down() + " " + next.getDirection().down().equals(movement.getDirection()));
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            IMovement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend && prev.getDirection().above().equals(current.getDirection().below())) {
                BlockPos center = current.getSrc().above();
                // playerFeet adds 0.1251 to account for soul sand
                // farmland is 0.9375
                // 0.07 is to account for farmland
                if (ctx.player().position().y >= center.getY() - 0.07) {
                    behavior.princeps.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse && sprintableAscend(ctx, (MovementTraverse) prev, (MovementAscend) current, path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        if (current instanceof MovementFall) {
            Tuple<Vec3, BlockPos> data = overrideFall((MovementFall) current);
            if (data != null) {
                BetterBlockPos fallDest = new BetterBlockPos(data.getB());
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException(String.format(
                            "Fall override at %s %s %s returned illegal destination %s %s %s",
                            current.getSrc(), fallDest));
                }
                if (ctx.playerFeet().equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    onTick();
                    return true;
                }
                clearKeys();
                behavior.princeps.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), data.getA(), ctx.playerRotations()), false);
                behavior.princeps.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return true;
            }
        }
        return false;
    }

    private Tuple<Vec3, BlockPos> overrideFall(MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return null;
        }
        if (!movement.toBreakCached.isEmpty()) {
            return null; // it's breaking
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            IMovement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
                break;
            }
        }
        i--;
        if (i == pathPosition) {
            return null; // no valid extension exists
        }
        double len = i - pathPosition - 0.4;
        return new Tuple<>(
                new Vec3(flatDir.getX() * len + movement.getDest().x + 0.5, movement.getDest().y, flatDir.getZ() * len + movement.getDest().z + 0.5),
                movement.getDest().offset(flatDir.getX() * (i - pathPosition), 0, flatDir.getZ() * (i - pathPosition)));
    }

    private static boolean skipNow(IPlayerContext ctx, IMovement current) {
        double offTarget = Math.abs(current.getDirection().getX() * (current.getSrc().z + 0.5D - ctx.player().position().z)) + Math.abs(current.getDirection().getZ() * (current.getSrc().x + 0.5D - ctx.player().position().x));
        if (offTarget > 0.1) {
            return false;
        }
        // we are centered
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(ctx, headBonk)) {
            return true;
        }
        // wait 0.3
        double flatDist = Math.abs(current.getDirection().getX() * (headBonk.getX() + 0.5D - ctx.player().position().x)) + Math.abs(current.getDirection().getZ() * (headBonk.getZ() + 0.5 - ctx.player().position().z));
        return flatDist > 0.8;
    }

    private static boolean sprintableAscend(IPlayerContext ctx, MovementTraverse current, MovementAscend next, IMovement nextnext) {
        if (!Princeps.settings().sprintAscends.value) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return false;
        }
        if (nextnext.getDirection().getX() != next.getDirection().getX() || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().below())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
            return false;
        }
        if (!next.toBreakCached.isEmpty()) {
            return false; // it's breaking
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(current.getSrc().above(3)))) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(ctx.world().getBlockState(next.getDest().above(2))); // codacy smh my head
    }

    private static boolean canSprintFromDescendInto(IPlayerContext ctx, IMovement current, IMovement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && Princeps.settings().allowOvershootDiagonalDescend.value;
    }

    private void onChangeInPathPosition() {
        clearKeys();
        ticksOnCurrent = 0;
    }

    private void clearKeys() {
        // i'm just sick and tired of this snippet being everywhere lol
        behavior.princeps.getInputOverrideHandler().clearAllKeys();
    }

    private void cancel() {
        clearKeys();
        behavior.princeps.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        pathPosition = path.length() + 3;
        failed = true;
    }

    @Override
    public int getPosition() {
        return pathPosition;
    }

    public PathExecutor trySplice(PathExecutor next) {
        if (next == null) {
            return cutIfTooLong();
        }
        if (modelProtection == null ? next.modelProtection != null
                : !modelProtection.sameBinding(next.modelProtection)) return this;
        if (excavationApproachToken != next.excavationApproachToken) return this;
        return SplicedPath.trySplice(path, next.path, false).map(path -> {
            if (!path.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after splicing",
                        path.getDest(), next.getPath().getDest()));
            }
            // Inherits the licences: a spliced or cut route is the SAME route under the same rules.
            PathExecutor ret = new PathExecutor(behavior, path, placementLicence, wadeLicence, modelProtection,
                    excavationApproachToken);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }).orElseGet(this::cutIfTooLong); // dont actually call cutIfTooLong every tick if we won't actually use it, use a method reference
    }

    private PathExecutor cutIfTooLong() {
        int maxHistory = Princeps.settings().maxPathHistoryLength.value;
        return pathPosition > maxHistory ? cutIfTooLong(maxHistory, Princeps.settings().pathHistoryCutoffAmount.value) : this;
    }

    private PathExecutor cutIfTooLong(int maxHistory, int cutoffAmt) {
        if (pathPosition > maxHistory) {
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after trimming its start",
                        newPath.getDest(), path.getDest()));
            }
            logDebug("Discarding earliest segment movements, length cut from " + path.length() + " to " + newPath.length());
            // Inherits the licences: a spliced or cut route is the SAME route under the same rules.
            PathExecutor ret = new PathExecutor(behavior, newPath, placementLicence, wadeLicence, modelProtection,
                    excavationApproachToken);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }
        return this;
    }

    @Override
    public IPath getPath() {
        return path;
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    public Set<BlockPos> toBreak() {
        return Collections.unmodifiableSet(toBreak);
    }

    public Set<BlockPos> toPlace() {
        return Collections.unmodifiableSet(toPlace);
    }

    public Set<BlockPos> toWalkInto() {
        return Collections.unmodifiableSet(toWalkInto);
    }

    public boolean isSprinting() {
        return sprintNextTick;
    }
}
