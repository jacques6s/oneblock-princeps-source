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

package princeps.process.builder.v3;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The three guards checked after every planned action, before it is committed to the order.
 *
 * <p>All three are guards, not policies: when one fires, the candidate is discarded and the next entry of the
 * frontier is tried. None of them is a heuristic, none has a tunable threshold, and none of them ever changes WHICH
 * cell is preferred — that is entirely 5.3's job. The distinction is worth keeping sharp, because the reactive
 * builder's backoff timers began life as guards and ended as the policy that produced the y=112 avalanche.
 *
 * <p>Every method here is a pure function of the predicted world and the planner's state. Nothing is sampled,
 * nothing reads a clock, nothing reads settings — {@link OrderPlanner.PlannerState} carries the
 * {@link V3Settings} snapshot for the acceptance rules that need one, so these stay unit-testable against a
 * hand-built world.
 *
 * <h2>E — enclosure freedom</h2>
 *
 * <p>The free-space component containing the bot must still touch the outside. This is the invariant that makes the
 * old system's y=112 avalanche — 78 cells, backoff escalating 40 to 640 ticks, the bot boxed into its own build —
 * not merely caught but <i>unplannable</i>. It is maintained by incremental flood-fill marking rather than recomputed,
 * amortised O(1) per action, because recomputing a component over a 63x63 build per candidate placement is the kind
 * of cost that turns a two-second dry run into a two-minute one.
 *
 * <h2>P — no placement takes another cell's last solution</h2>
 *
 * <p>The one failure the frontier cannot see by itself, and etz-basalt presents it 448 times. A {@code facing=down}
 * piston's diagonal stance needs ONE solid side neighbour to click and ONE perpendicular side neighbour to stay
 * EMPTY, because that is where the crouched head goes. A frontier that greedily fills all four sides makes every
 * individual placement legal and the SET illegal. P is the guard for exactly that: every placement was fine, the
 * combination was not.
 *
 * <p>It is affordable only because of {@link DependencyIndex}: applying C re-solves {@code index[C]} — the cells that
 * were actually using the position C fills — and not the layer. If any of them loses its last solution, C is
 * discarded.
 *
 * <h2>S — scaffold stays removable</h2>
 *
 * <p>Every open helper block must still have a reachable breaking stance with a clear line of sight, or the layer
 * bound of 5.1 cannot be met. Under the hard layer rule this is almost always trivially true, and that observation is
 * what turns it from an expensive invariant into a cheap one: while layer L is being built every layer above L+1 is
 * empty, so a helper block on L+1 has a free air column over it and is breakable from above at reach 4.5, and being
 * enclosed horizontally on its own layer does not block that. So the check is spent only on the two cases where the
 * assumption does not hold — a helper block at or below L, which its own layer can wall in, and pre-existing terrain
 * above L+1, which is a fact about the real world and is checked against the snapshot rather than against the
 * assumption. In the normal case it is a comparison against L and nine snapshot reads, and no search at all.
 *
 * <h2>Three parameters, not a planner — and why that shape is forced</h2>
 *
 * <p>Each guard exists twice: once taking {@link OrderPlanner.PlannerState}, which is how the layer loop calls it, and
 * once taking only the handful of values it actually reads. The second form is not a convenience — the first is
 * unreachable from a unit test, for two reasons that were measured against this build rather than assumed:
 *
 * <ol>
 *   <li>A {@code PlannerState} carries a {@link PlacementOracle}, and the oracle needs a hotbar. {@code new
 *       ItemStack(Items.PISTON)} throws {@code NullPointerException: Components not bound yet} after
 *       {@code Bootstrap.bootStrap()} — item components are bound by a data-pack load that no headless test performs,
 *       so no stack that can place anything exists in a test JVM. The oracle is therefore not callable from JUnit at
 *       all, which is why P takes a {@link CellSolver} rather than an oracle.</li>
 *   <li>{@link PredictedWorld#isStandable} resolves {@code MovementHelper.canWalkThroughBlockState}, whose fourth
 *       screen is {@code Princeps.settings().blocksToAvoid} (MovementHelper:155). Every state that is not air and not
 *       one of its dozen early cases reaches it, so a stance test over stone dies in the static initialiser exactly as
 *       trap 1.7 describes — verified here, not inherited from the fact sheet. S therefore uses its own stance
 *       predicate; see {@link #canStandAt}.</li>
 * </ol>
 *
 * <p>The split is what the whole {@link V3Settings} design is for, applied one level further down: the guard logic —
 * which cells are consulted, in what order, and what counts as a loss — is separated from how a solution is found and
 * from where the planner keeps its bookkeeping, and only the separated half needs to be right.
 */
public final class Invariants {

    private Invariants() {
    }

    /** Which guard fired. */
    public enum Which {

        /** Enclosure freedom. */
        E,

        /** No placement takes another cell's last solution. */
        P,

        /** Scaffold stays removable. */
        S
    }

    /**
     * A guard firing, with enough detail for the trace to say why.
     *
     * @param candidate the cell whose placement was refused
     * @param victim    for {@link Which#P}, the cell that would have lost its last solution; for {@link Which#S}, the
     *                  helper block that would become unreachable; for {@link Which#E}, the cell that would be
     *                  sealed off. Never null — a violation that cannot name a victim is a violation nobody can act
     *                  on, and V2's deferrals were exactly that
     * @param detail    free text; for P it names the {@link DependencyIndex.Use} that was lost, which is the
     *                  difference between "it lost its last solution" and "its head room at 113,-57,93 was filled"
     */
    public record Violation(Which invariant, BlockPos candidate, BlockPos victim, String detail) {
    }

    /**
     * "Does this cell still have at least one way to be placed" — the one question invariant P asks of the world it
     * has speculatively changed.
     *
     * <p>A seam rather than a {@link PlacementOracle} parameter for the reason the class javadoc gives: an
     * {@code ItemStack} cannot be constructed in a headless test, so a P that called the oracle directly would be
     * untestable for the same structural reason {@code Princeps.settings()} makes its callers untestable. The layer
     * loop passes the real oracle through {@link #solverFor}; a test passes the rule it is testing against.
     *
     * <p>Called with the world AFTER the candidate has been applied, and it must not mutate it.
     */
    @FunctionalInterface
    public interface CellSolver {

        boolean hasSolution(PredictedWorld world, BlockPos cell);
    }

    /** Neighbour order for every walk in this class, fixed so two runs enumerate a component identically. Down first
     *  because floors are the commonest way out of a pocket and finding the exit early ends the walk early. */
    private static final Direction[] NEIGHBOURS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** The faces of a helper block invariant S tries to see, in a fixed order. UP first: the normal breaking stance
     *  under the hard layer rule stands on layer L beside the helper block and looks slightly down at it. */
    private static final Direction[] BREAK_FACES = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    /** Horizontal half-width of the stance window invariant S searches for a breaking stance, matching the placement
     *  oracle's window so that "somewhere to stand" means the same thing in both. */
    private static final int BREAK_STANCE_RADIUS = 3;

    /** Lowest and highest breaking stance relative to a helper block. Two down covers breaking one at head height
     *  from below; one up covers standing on the layer above it. The same pair {@code OrderPlanner} uses. */
    private static final int BREAK_STANCE_LOWEST = -2;
    private static final int BREAK_STANCE_HIGHEST = 1;

    /** How far the retreat ordering's distance flood may reach before a cell is called unreachable. The exit is by
     *  construction a few blocks from the cells being ordered; a distance beyond this is not a corridor, it is a
     *  different room. */
    private static final int RETREAT_FLOOD_BUDGET = 65_536;

    /** The distance {@link #retreatOrder} gives a cell the flood never reached. Ordered FIRST, because a cell that
     *  cannot be reached from the exit at all is deeper than any cell that can. */
    private static final int UNREACHABLE = Integer.MAX_VALUE;

    // ------------------------------------------------------------------- the three predicates

    /**
     * E. Does the bot's free-space component still reach the outside after {@code candidate} is placed?
     *
     * <p>Reads the incremental component marking held in {@link OrderPlanner.PlannerState#enclosure()}; it does not
     * build one, because building one per candidate is the cost this design avoids.
     *
     * <p>The cell asked about is {@link PlacementSolution#stance()} — where the bot will stand to make THIS click —
     * and not {@link OrderPlanner.PlannerState#lastStance()}, where it stood for the previous action. The bot walks
     * to the stance before it clicks, so the previous stance is not where it is when the block lands, and using it
     * makes E answer a question nobody asked. In a flat build "the next cell to fill is the cell I just stood in" is
     * the ordinary case, not an edge case: the {@code filled.equals(botCell)} shortcut in
     * {@link EnclosureTracker#wouldSeal} then fires on every such candidate, the planner bans that
     * cell/stance/face triple, re-solves, is vetoed again on the same false ground, and the cell drops out of the
     * frontier — after which its neighbours really are placed around it and the trace blames the line of sight it
     * destroyed itself. Verified on the 3x3 fixture: the centre was vetoed twice this way and reported
     * RAY_OBSTRUCTED for a world the veto had manufactured.
     */
    public static boolean enclosureFree(PredictedWorld world, OrderPlanner.PlannerState state,
                                        PlacementSolution candidate) {
        return enclosureFree(world, state.enclosure(), candidate.stance(), candidate.cell(), candidate.predicted());
    }

    /**
     * P. Does every cell in {@code index[candidate.cell()]} still have at least one solution after
     * {@code candidate} is placed?
     *
     * <p>Re-solves only the indexed dependents, in {@link DependencyIndex#dependents} order, which is sorted — so
     * which dependent is found to fail first, and therefore what the trace reports, is the same on every run.
     */
    public static boolean lastSolutionsPreserved(PredictedWorld world, OrderPlanner.PlannerState state,
                                                 PlacementSolution candidate) {
        return explainP(world, state, candidate).isEmpty();
    }

    /**
     * S. Does every open helper block in the ledger still have a reachable breaking stance with a clear line of
     * sight after {@code candidate} is placed?
     *
     * <p>Short-circuits on the common case first — helper block above the current layer, nothing pre-existing over
     * it — and only then spends a stance search. See the class javadoc for why that shortcut is sound rather than
     * optimistic.
     */
    public static boolean scaffoldRemovable(PredictedWorld world, OrderPlanner.PlannerState state,
                                            PlacementSolution candidate) {
        return explainS(world, state, candidate).isEmpty();
    }

    /** All three, in the order E, P, S — cheapest first, and the order is fixed so that a candidate refused by two
     *  guards is always reported against the same one. */
    public static boolean all(PredictedWorld world, OrderPlanner.PlannerState state, PlacementSolution candidate) {
        return explain(world, state, candidate).isEmpty();
    }

    /** {@link #all} with the reason. Empty means all three hold. */
    public static Optional<Violation> explain(PredictedWorld world, OrderPlanner.PlannerState state,
                                              PlacementSolution candidate) {
        if (!enclosureFree(world, state, candidate)) {
            // The victim is the candidate's OWN stance, for the same reason enclosureFree asks about it: that is
            // where the bot will be standing when this block lands.
            return Optional.of(new Violation(Which.E, candidate.cell(), candidate.stance(),
                    "placing " + BuildAction.describeState(candidate.predicted()) + " at "
                            + BuildAction.describePos(candidate.cell())
                            + " closes the last way out of the free space the bot stands in at "
                            + BuildAction.describePos(candidate.stance())));
        }
        Optional<Violation> lost = explainP(world, state, candidate);
        if (lost.isPresent()) {
            return lost;
        }
        return explainS(world, state, candidate);
    }

    // ------------------------------------------------------------------- E, without a planner

    /**
     * E over nothing but the marking, the bot's cell and the placement — the form a test can build.
     *
     * <p>A placement that does not produce a full cube is waved through without consulting the marking at all. The
     * marking is {@link PredictedWorld#isSolidFullCube}, so a torch, a repeater or a carpet takes no free space away
     * and cannot seal anything; asking anyway would spend a flood fill per redstone component for an answer that is
     * fixed in advance.
     *
     * @param botCell where the bot stands when the block lands — {@link PlacementSolution#stance()} of the candidate
     *                in the layer loop, NOT {@link OrderPlanner.PlannerState#lastStance()}: the previous stance is a
     *                place the bot has already left by the time it clicks
     * @param landing the state that will actually land, not the schematic's desired state; a slab that lands as a
     *                bottom slab does not fill its cell and a plan that assumed it did would refuse legal placements
     */
    public static boolean enclosureFree(PredictedWorld world, EnclosureTracker tracker, BlockPos botCell,
                                        BlockPos filled, BlockState landing) {
        if (!fillsCell(world, filled, landing)) {
            return true;
        }
        return !tracker.wouldSeal(world, filled, botCell);
    }

    // ------------------------------------------------------------------- P, without a planner

    /**
     * P over the index, the placement and a way to re-solve a cell — the whole of the invariant, and the form a test
     * can build.
     *
     * <p>The candidate is applied to {@code world} for the duration of the check and taken out again, which is what
     * {@link PredictedWorld#revert} exists for: asking "does this cell still have a solution" against a world where
     * the placement has NOT happened is asking a question whose answer is already known to be yes.
     *
     * <p>Only {@link DependencyIndex#dependents} is walked, and it is walked in its sorted order. That set is the
     * invariant's whole affordability — 2 189 cells in etz-basalt's y=-57 layer, a median of 3 dependents per
     * placement on the piston row — and its order decides which victim the report names when two of them fail.
     *
     * @param filled  the cell the candidate would fill
     * @param landing the state that would land there
     * @return the violation, naming the victim and which use of the position it lost; empty when P holds
     */
    public static Optional<Violation> lastSolutionsPreserved(PredictedWorld world, DependencyIndex index,
                                                             BlockPos filled, BlockState landing, CellSolver solver) {
        List<BlockPos> dependents = index.dependents(filled);
        if (dependents.isEmpty() || landing == null || !world.inBounds(filled.getX(), filled.getY(), filled.getZ())) {
            // The overwhelmingly common answer, and it costs one hash lookup. Nothing was using this position, so
            // nothing can lose it.
            return Optional.empty();
        }
        BlockState before = world.get(filled);
        boolean fromSnapshot = before == world.original(filled.getX(), filled.getY(), filled.getZ());
        world.apply(filled, landing);
        try {
            for (BlockPos dependent : dependents) {
                if (dependent.equals(filled)) {
                    // A cell may be indexed against its own footprint through a ray that grazes it; it is the cell
                    // being placed, so it is not a victim of its own placement.
                    continue;
                }
                if (!solver.hasSolution(world, dependent)) {
                    return Optional.of(new Violation(Which.P, filled, dependent,
                            "lost its last solution: " + lostUses(index, filled, dependent) + " at "
                                    + BuildAction.describePos(filled) + " was filled with "
                                    + BuildAction.describeState(landing)));
                }
            }
        } finally {
            // revert() restores the SNAPSHOT, which is only the same thing as "what was here a moment ago" when the
            // plan had not already written this cell. Anything else — a cell emptied by a BREAK, a cell a cascade
            // rewrote — has to be put back by hand or P would silently undo an earlier action of its own plan.
            if (fromSnapshot) {
                world.revert(filled);
            } else {
                world.apply(filled, before);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------- S, without a planner

    /**
     * S over the open helper blocks alone — the form a test can build.
     *
     * <p>The two cheap screens of 5.5 come first and answer for almost every helper block: one at or below the
     * current layer is exposed to being walled in by its own layer and gets the real check, one above it gets the
     * real check only when the snapshot holds pre-existing terrain overhead. Everything else is the normal case the
     * hard layer rule guarantees — clear air above, breakable from the side while standing on L — and costs two
     * reads.
     *
     * <p>It is a post-condition, not a delta: a helper block that was ALREADY unreachable before this candidate makes
     * every subsequent candidate fail. That is deliberate. At that point the layer bound of 5.1 is already broken and
     * the honest outcome is the blocker report, not a plan that keeps placing blocks around a helper block it can
     * never take back out.
     *
     * @param openScaffold the ledger's open helper blocks, in placement order
     * @param layer        the layer currently being built — L in 5.5's argument
     */
    public static Optional<Violation> scaffoldRemovable(PredictedWorld world, PlayerPose pose,
                                                        List<BlockPos> openScaffold, int layer, BlockPos filled,
                                                        BlockState landing) {
        if (openScaffold.isEmpty() || landing == null
                || !world.inBounds(filled.getX(), filled.getY(), filled.getZ())) {
            return Optional.empty();
        }
        BlockState before = world.get(filled);
        boolean fromSnapshot = before == world.original(filled.getX(), filled.getY(), filled.getZ());
        world.apply(filled, landing);
        try {
            for (BlockPos helper : openScaffold) {
                if (helper.getY() > layer && !terrainOverhead(world, helper)) {
                    continue;   // 5.5's normal case: everything above L+1 is empty and stays that way
                }
                if (!removable(world, pose, helper)) {
                    return Optional.of(new Violation(Which.S, filled, helper,
                            "the helper block at " + BuildAction.describePos(helper)
                                    + " has no breaking stance left once " + BuildAction.describePos(filled)
                                    + " holds " + BuildAction.describeState(landing)
                                    + (helper.getY() > layer
                                    ? " — pre-existing terrain stands over it, so the layer rule's clear air column "
                                    + "does not apply"
                                    : " — it sits at or below layer " + layer + ", which its own layer can wall in")));
                }
            }
        } finally {
            if (fromSnapshot) {
                world.revert(filled);
            } else {
                world.apply(filled, before);
            }
        }
        return Optional.empty();
    }

    /**
     * Is there a stance from which this block can be broken — somewhere to stand, within reach, with a clear line to
     * one of its faces?
     *
     * <p>Deliberately NOT {@link PredictedWorld#isStandable}: that method resolves
     * {@code MovementHelper.canWalkThroughBlockState}, which reads {@code Princeps.settings().blocksToAvoid} for
     * every state that is not air and not one of its dozen early cases, and reaching a settings read makes this class
     * — and therefore invariant S — permanently untestable (trap 1.7, and verified against this build rather than
     * taken on trust). {@link #canStandAt} is the settings-free substitute and it is STRICTER: a full solid cube
     * underfoot rather than anything the pathfinder would walk on, so slabs, stairs and ladders do not count as
     * floors here. Strict is the safe direction for this invariant — a stance it declines is a plan that reports a
     * blocker, where a stance it wrongly accepts is a helper block left standing in the finished world.
     */
    private static boolean removable(PredictedWorld world, PlayerPose pose, BlockPos helper) {
        // Fixed loop order, and the loop order IS the order: nothing here is collected into a container that could
        // reorder it, so the stance this returns for a given world is the same on every run.
        for (int dy = BREAK_STANCE_HIGHEST; dy >= BREAK_STANCE_LOWEST; dy--) {
            for (int dz = -BREAK_STANCE_RADIUS; dz <= BREAK_STANCE_RADIUS; dz++) {
                for (int dx = -BREAK_STANCE_RADIUS; dx <= BREAK_STANCE_RADIUS; dx++) {
                    BlockPos stance = helper.offset(dx, dy, dz);
                    if (!canStandAt(world, stance)) {
                        continue;
                    }
                    Vec3 eye = pose.eyeAt(new Vec3(stance.getX() + 0.5D, stance.getY(), stance.getZ() + 0.5D));
                    for (Direction face : BREAK_FACES) {
                        Vec3 aim = faceCentre(helper, face);
                        if (eye.distanceTo(aim) > pose.reach()) {
                            continue;
                        }
                        // GridRay is half-open at the aim point, so the helper block does not report itself as the
                        // thing blocking the view of its own face — the same convention the placement oracle's line
                        // of sight check relies on, and it has to be the same one.
                        if (GridRay.cast(world.solidTest(), eye, aim) == null) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** The settings-free stance test: feet and head clear of full cubes, a full cube underfoot. See
     *  {@link #removable} for why this exists beside {@link PredictedWorld#isStandable} rather than calling it. */
    private static boolean canStandAt(PredictedWorld world, BlockPos feet) {
        return world.inBounds(feet.getX(), feet.getY(), feet.getZ())
                && !world.isSolidFullCube(feet.getX(), feet.getY(), feet.getZ())
                && !world.isSolidFullCube(feet.getX(), feet.getY() + 1, feet.getZ())
                && world.isSolidFullCube(feet.getX(), feet.getY() - 1, feet.getZ());
    }

    /**
     * Case 2 of 5.5: does the SNAPSHOT hold anything in the 3x3 slab directly over this helper block?
     *
     * <p>Against {@link PredictedWorld#original}, never against the predicted world, and that is the whole point of
     * the check. "Everything above L+1 is empty" is a statement about the PLAN, and it is true; it says nothing about
     * the world the schematic was dropped into. Natural stone over the build site makes it false, and the only place
     * that fact exists is the snapshot.
     *
     * <p>Non-air rather than solid-full-cube: a snapshot cell holding anything at all is enough to make the clear
     * air column argument inapplicable, and the real check is cheap enough that erring towards running it costs
     * nothing worth measuring.
     */
    private static boolean terrainOverhead(PredictedWorld world, BlockPos helper) {
        int y = helper.getY() + 1;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!world.original(helper.getX() + dx, y, helper.getZ() + dz).isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- the planner-facing halves

    /** The real re-solve, wired to the planner's oracle, view, hotbar, budget and rejection journal. */
    private static CellSolver solverFor(OrderPlanner.PlannerState state) {
        return (world, cell) -> {
            SchematicView view = state.view();
            if (!view.covers(cell) || !view.wantsBlock(cell)) {
                // Terrain, or a cell the schematic wants empty. It has no placement to lose.
                return true;
            }
            if (view.satisfied(world, state.settings(), cell)) {
                return true;
            }
            // The rays this spends are not booked against PlannerState.raysCast: the counter is the planner's and P
            // is not the planner. The trace's ray figure is therefore the SEARCH's, which is the number the cost
            // model is about anyway.
            // placementTarget, not desired, and it is required rather than cosmetic: P re-solves every cell whose
            // footprint a candidate touches, and against the raw schematic every half of a double chest is the
            // un-landable type=left. P would then report a violation on every one of them.
            return state.oracle().solve(world, cell, view.placementTarget(world, cell), state.stacks(), state.budget(),
                    state.journal()).solved();
        };
    }

    private static Optional<Violation> explainP(PredictedWorld world, OrderPlanner.PlannerState state,
                                                PlacementSolution candidate) {
        return lastSolutionsPreserved(world, state.index(), candidate.cell(), candidate.predicted(),
                solverFor(state));
    }

    private static Optional<Violation> explainS(PredictedWorld world, OrderPlanner.PlannerState state,
                                                PlacementSolution candidate) {
        return scaffoldRemovable(world, state.oracle().pose(), state.ledger().open(), state.currentLayer(),
                candidate.cell(), candidate.predicted());
    }

    /** "its head room" / "its stance and its head room" — the uses of {@code position} that {@code victim}'s solution
     *  was holding, named, because "it lost its last solution" is not something a reader can act on. */
    private static String lostUses(DependencyIndex index, BlockPos position, BlockPos victim) {
        StringBuilder names = new StringBuilder();
        for (DependencyIndex.Dependency edge : index.edgesAt(position)) {
            if (!edge.cell().equals(victim)) {
                continue;
            }
            names.append(names.isEmpty() ? "its " : " and its ")
                    .append(edge.use().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        return names.isEmpty() ? "a position it needed" : names.toString();
    }

    /**
     * Would this state, landed at this cell, actually take the cell's free space away?
     *
     * <p>Asked by applying it and reading {@link PredictedWorld#isSolidFullCube} back, rather than by evaluating the
     * collision shape here. There is exactly one definition of solidity in this package — the bitset {@link GridRay}
     * walks — and a second opinion that disagreed about a single family would show up as an enclosure the marking
     * does not believe in, which is the hardest possible bug to attribute.
     */
    private static boolean fillsCell(PredictedWorld world, BlockPos pos, BlockState landing) {
        if (landing == null || landing.isAir() || !world.inBounds(pos.getX(), pos.getY(), pos.getZ())) {
            return false;
        }
        BlockState before = world.get(pos);
        boolean fromSnapshot = before == world.original(pos.getX(), pos.getY(), pos.getZ());
        world.apply(pos, landing);
        boolean solid = world.isSolidFullCube(pos.getX(), pos.getY(), pos.getZ());
        if (fromSnapshot) {
            world.revert(pos);
        } else {
            world.apply(pos, before);
        }
        return solid;
    }

    private static Vec3 faceCentre(BlockPos pos, Direction face) {
        return new Vec3(pos.getX() + 0.5D + face.getStepX() * 0.5D,
                pos.getY() + 0.5D + face.getStepY() * 0.5D,
                pos.getZ() + 0.5D + face.getStepZ() * 0.5D);
    }

    // ------------------------------------------------------------------- E's machinery

    /**
     * The incremental free-space component marking behind invariant E.
     *
     * <p>Kept in {@link OrderPlanner.PlannerState} and updated as the plan advances, rather than recomputed per
     * candidate. Placement only ever REMOVES free space, so the update is a local test — a filled cell can split a
     * component only if it was itself a cut vertex of the free graph, which is decided by looking at its
     * neighbourhood — and a full re-flood is needed only when the local test is inconclusive.
     *
     * <p>{@link #wouldSeal} is speculative and must not mutate: it is asked for every candidate and only a small
     * fraction of candidates are committed. {@link #apply} is the commit.
     *
     * <h2>What "outside" means, exactly</h2>
     *
     * <p>A free cell on the SHELL of the captured box. The box is the schematic plus {@link
     * PredictedWorld#DEFAULT_MARGIN} blocks on every side, and that margin exists partly for this: a cell on the shell
     * is eight blocks clear of anything the plan will ever place, so calling it exterior is a statement about the
     * plan rather than a guess about the world. The marking answers "is this free cell connected, through free cells,
     * to the shell".
     *
     * <h2>Connectivity is cell connectivity, and that over-approximates on purpose</h2>
     *
     * <p>Six-neighbour connectivity through cells that are not full solid cubes. A one-block-high slit therefore
     * counts as a way out even though a 1.5-block-tall crouched body could not travel it. The error is entirely in
     * the direction of NOT firing, which is the right direction for a guard whose false positive would abort a
     * three-hour build over a gap the bot could have mined through in two seconds. What it does catch — with no
     * threshold and no shape rule — is the total seal, and the total seal is what the y=112 avalanche was.
     */
    public static final class EnclosureTracker {

        /**
         * How many marked cells the cheap reconnection probe may visit before it gives up and the expensive path
         * runs. In open space the free neighbours of a placed block are all within two steps of one another, which is
         * a ball of about twenty-five cells; 128 is comfortably past that and still nothing next to the cost of the
         * component walk it is there to avoid.
         */
        private static final int LOCAL_RECONNECT_BUDGET = 128;

        /**
         * How many cells a component walk may visit before the answer is "this is the outside".
         *
         * <p>8 192 free cells is a room twenty blocks on a side. A pocket that large is not something the bot boxed
         * itself into by placing one block, and walking the genuine outside component of a captured box — half a
         * million cells — per candidate is the cost this whole class exists to avoid. Exceeding the budget therefore
         * answers "not sealed", and that is a documented over-approximation in the same direction as the connectivity
         * rule above.
         */
        private static final int COMPONENT_BUDGET = 8_192;

        /** The bot's starting cell. Kept to decide {@link #armed} and for nothing else. */
        private final BlockPos seed;

        private int minX;
        private int minY;
        private int minZ;
        private int sizeX;
        private int sizeY;
        private int sizeZ;

        /** One bit per cell of the box: is this a free cell currently connected to the shell. Null until a world has
         *  been supplied — see the constructor. */
        private long[] outside;

        private int reachable;

        /**
         * Was the seed connected to the outside when the marking was built?
         *
         * <p>When it was not — the bot began inside a closed room, which is a perfectly ordinary way to start an
         * interior build — E is disarmed. An invariant that refused every placement because the bot was already
         * enclosed before the plan started would have stopped being a guard and become a policy, and that is the
         * exact transition this design is written against.
         */
        private boolean armed;

        /**
         * @param seed the bot's starting free cell; the component containing it is "inside", everything reachable
         *             from outside the snapshot box is "outside"
         *
         * <p>{@code world} may be null, and {@link OrderPlanner.PlannerState}'s constructor passes null: it builds
         * the tracker before it has ever seen a {@link PredictedWorld}, because the world is a parameter of
         * {@code plan} and not of the state. The marking is therefore built on the first call that supplies one. That
         * is not laziness for its own sake — a tracker that demanded a world at construction would force the state
         * object to carry one, and the state object is deliberately world-free so the same state can be stepped
         * through a world the caller owns.
         */
        public EnclosureTracker(PredictedWorld world, BlockPos seed) {
            this.seed = seed;
            if (world != null) {
                this.build(world);
            }
        }

        /** Is this free cell currently in the component that touches the outside? */
        public boolean connectedToOutside(BlockPos cell) {
            if (this.outside == null || cell == null) {
                // No world has been seen yet, so nothing is known and nothing may be refused on the strength of it.
                return true;
            }
            if (!this.inBox(cell.getX(), cell.getY(), cell.getZ())) {
                return true;   // beyond the captured box IS the outside
            }
            return this.marked(this.index(cell.getX(), cell.getY(), cell.getZ()));
        }

        /** Speculative: would filling {@code filled} cut {@code botCell} off from the outside? Does not mutate. */
        public boolean wouldSeal(PredictedWorld world, BlockPos filled, BlockPos botCell) {
            this.ensure(world);
            if (!this.armed || botCell == null || filled == null) {
                return false;
            }
            if (!this.inBox(filled.getX(), filled.getY(), filled.getZ())
                    || !this.free(world, filled.getX(), filled.getY(), filled.getZ())) {
                return false;   // outside the box, or already solid: this placement changes no connectivity
            }
            if (!this.connectedToOutside(botCell)) {
                // The bot is already in a pocket. Blaming the candidate for it would veto every remaining placement
                // in the layer for a seal some earlier action caused, and the earlier action is where the report has
                // to point.
                return false;
            }
            if (filled.equals(botCell)) {
                return true;
            }
            if (!this.inBox(botCell.getX(), botCell.getY(), botCell.getZ())) {
                // The bot stands beyond the captured box, which is the exterior by definition. Nothing placed inside
                // the box can cut it off, and indexing a cell that is not in the box would read past the bitset.
                return false;
            }
            int excluded = this.index(filled.getX(), filled.getY(), filled.getZ());
            IntArrayList neighbours = this.markedNeighbours(filled);
            if (neighbours.size() <= 1) {
                // A cell with at most one free neighbour cannot be a cut vertex of the free graph, so nothing can be
                // separated by filling it. This is the answer for the overwhelming majority of candidates and it
                // costs six bitset reads.
                return false;
            }
            if (this.locallyReconnected(neighbours, excluded)) {
                return false;
            }
            // Inconclusive: the neighbourhood really is split, or the probe ran out of budget. Now it is worth
            // walking the bot's own component, which is bounded and is the only walk that can answer the question.
            return !this.reachesShell(this.index(botCell.getX(), botCell.getY(), botCell.getZ()), excluded, null);
        }

        /** Commit a placement. */
        public void apply(PredictedWorld world, BlockPos filled) {
            this.ensure(world);
            if (this.outside == null || !this.inBox(filled.getX(), filled.getY(), filled.getZ())) {
                return;
            }
            int index = this.index(filled.getX(), filled.getY(), filled.getZ());
            if (!this.marked(index)) {
                // It was already solid, or it was free but already cut off from the shell. Neither can disconnect a
                // cell that IS connected: any path through it would have marked it.
                return;
            }
            this.clear(index);
            IntArrayList neighbours = this.markedNeighbours(filled);
            if (neighbours.size() <= 1 || this.locallyReconnected(neighbours, index)) {
                return;
            }
            // The cheap probe could not put the neighbourhood back together, so each surviving side is walked once.
            // A side that turns out to be finite and never touches the shell IS the sealed component — the walk has
            // already enumerated it, so clearing it is exact rather than approximate.
            IntOpenHashSet confirmed = new IntOpenHashSet();
            for (int at = 0; at < neighbours.size(); at++) {
                int start = neighbours.getInt(at);
                if (confirmed.contains(start)) {
                    continue;
                }
                IntArrayList visited = new IntArrayList();
                if (this.reachesShell(start, index, visited)) {
                    // Everything this walk touched is connected to the start, hence to the shell. One walk therefore
                    // answers for every neighbour it happened to reach.
                    confirmed.addAll(visited);
                    continue;
                }
                // Iterated only to clear bits, which is commutative: the resulting marking does not depend on the
                // order fastutil hands the cells back, and no output of this class is derived from that order.
                for (int at2 = 0; at2 < visited.size(); at2++) {
                    this.clear(visited.getInt(at2));
                }
            }
        }

        /** Commit a removal — a break or a scaffold removal, which can only ever ADD free space and therefore only
         *  ever merge components. */
        public void free(PredictedWorld world, BlockPos emptied) {
            this.ensure(world);
            if (this.outside == null || !this.inBox(emptied.getX(), emptied.getY(), emptied.getZ())) {
                return;
            }
            if (!this.free(world, emptied.getX(), emptied.getY(), emptied.getZ())) {
                return;   // whatever replaced it is still a full cube
            }
            int index = this.index(emptied.getX(), emptied.getY(), emptied.getZ());
            if (this.marked(index)) {
                return;
            }
            boolean touchesOutside = this.onShell(index);
            for (Direction side : NEIGHBOURS) {
                BlockPos neighbour = emptied.relative(side);
                if (this.inBox(neighbour.getX(), neighbour.getY(), neighbour.getZ())
                        && this.marked(this.index(neighbour.getX(), neighbour.getY(), neighbour.getZ()))) {
                    touchesOutside = true;
                    break;
                }
            }
            if (!touchesOutside) {
                return;   // the hole joins an inside pocket; it is still a pocket
            }
            // A removal that reconnects a pocket marks the whole pocket. Unbounded, and it may be: every cell it
            // marks was unmarked and is never unmarked again by the same removal, so the total cost of every merge
            // across a whole plan is bounded by the size of the box.
            this.mark(index);
            IntArrayList queue = new IntArrayList();
            queue.add(index);
            int at = 0;
            while (at < queue.size()) {
                int current = queue.getInt(at++);
                IntArrayList open = this.freeNeighbours(world, current);
                for (int side = 0; side < open.size(); side++) {
                    int neighbour = open.getInt(side);
                    if (!this.marked(neighbour)) {
                        this.mark(neighbour);
                        queue.add(neighbour);
                    }
                }
            }
        }

        /** How many free cells are currently reachable from the outside. Traced per layer: a number that collapses
         *  is the shape of an enclosure forming, visible before the plan gets stuck. */
        public int reachableCells() {
            return this.reachable;
        }

        // --------------------------------------------------------------- internals

        /** Build the marking the first time a world turns up, and rebuild it if a DIFFERENT box does. The second case
         *  is a re-plan against a fresh snapshot, where carrying the old marking forward would be a proof about a
         *  world that no longer exists. */
        private void ensure(PredictedWorld world) {
            if (world == null) {
                return;
            }
            if (this.outside != null
                    && this.minX == world.minX() && this.minY == world.minY() && this.minZ == world.minZ()
                    && this.sizeX == world.maxX() - world.minX() + 1
                    && this.sizeY == world.maxY() - world.minY() + 1
                    && this.sizeZ == world.maxZ() - world.minZ() + 1) {
                return;
            }
            this.build(world);
        }

        /** One flood from every free cell on the shell, inwards. The only unbounded walk in this class, paid once. */
        private void build(PredictedWorld world) {
            this.minX = world.minX();
            this.minY = world.minY();
            this.minZ = world.minZ();
            this.sizeX = world.maxX() - world.minX() + 1;
            this.sizeY = world.maxY() - world.minY() + 1;
            this.sizeZ = world.maxZ() - world.minZ() + 1;
            this.outside = new long[((this.sizeX * this.sizeY * this.sizeZ) + 63) >>> 6];
            this.reachable = 0;

            IntArrayList queue = new IntArrayList();
            for (int y = 0; y < this.sizeY; y++) {
                for (int z = 0; z < this.sizeZ; z++) {
                    for (int x = 0; x < this.sizeX; x++) {
                        boolean shell = x == 0 || y == 0 || z == 0
                                || x == this.sizeX - 1 || y == this.sizeY - 1 || z == this.sizeZ - 1;
                        if (!shell) {
                            // The interior is reached by the flood, not by this scan; walking it here would visit
                            // every cell of a million-cell box twice.
                            continue;
                        }
                        if (this.free(world, this.minX + x, this.minY + y, this.minZ + z)) {
                            int index = (y * this.sizeZ + z) * this.sizeX + x;
                            if (!this.marked(index)) {
                                this.mark(index);
                                queue.add(index);
                            }
                        }
                    }
                }
            }
            int at = 0;
            while (at < queue.size()) {
                int current = queue.getInt(at++);
                IntArrayList open = this.freeNeighbours(world, current);
                for (int side = 0; side < open.size(); side++) {
                    int neighbour = open.getInt(side);
                    if (!this.marked(neighbour)) {
                        this.mark(neighbour);
                        queue.add(neighbour);
                    }
                }
            }
            this.armed = this.connectedToOutside(this.seed);
        }

        /**
         * Can the marked neighbours of a filled cell still reach each other WITHOUT going through it?
         *
         * <p>The cheap half of the update. If they can, the free graph lost a vertex but no component, and neither
         * {@link #apply} nor {@link #wouldSeal} has anything else to do. Bounded by
         * {@link #LOCAL_RECONNECT_BUDGET}: running out is not an answer, it falls through to the walk.
         */
        private boolean locallyReconnected(IntArrayList neighbours, int excluded) {
            IntOpenHashSet wanted = new IntOpenHashSet(neighbours);
            int start = neighbours.getInt(0);
            wanted.remove(start);
            IntOpenHashSet seen = new IntOpenHashSet();
            IntArrayList queue = new IntArrayList();
            seen.add(start);
            queue.add(start);
            int at = 0;
            while (at < queue.size()) {
                if (at >= LOCAL_RECONNECT_BUDGET) {
                    return false;
                }
                int current = queue.getInt(at++);
                IntArrayList open = this.markedNeighbours(current);
                for (int side = 0; side < open.size(); side++) {
                    int neighbour = open.getInt(side);
                    if (neighbour == excluded || !seen.add(neighbour)) {
                        continue;
                    }
                    wanted.remove(neighbour);
                    if (wanted.isEmpty()) {
                        return true;
                    }
                    queue.add(neighbour);
                }
            }
            return wanted.isEmpty();
        }

        /**
         * Walk the marked component containing {@code start}, ignoring {@code excluded}, and answer whether it
         * touches the shell.
         *
         * <p>True also when {@link #COMPONENT_BUDGET} runs out — a component that big is the outside, and the
         * over-approximation is documented on the constant. False means the walk enumerated a finite region that
         * never touched the shell, which is a sealed pocket and {@code visited} then holds exactly its cells.
         */
        private boolean reachesShell(int start, int excluded, IntArrayList visited) {
            if (start == excluded || !this.marked(start)) {
                return false;
            }
            IntOpenHashSet seen = new IntOpenHashSet();
            IntArrayList queue = new IntArrayList();
            seen.add(start);
            queue.add(start);
            int at = 0;
            while (at < queue.size()) {
                if (queue.size() > COMPONENT_BUDGET) {
                    return true;
                }
                int current = queue.getInt(at++);
                if (this.onShell(current)) {
                    return true;
                }
                if (visited != null) {
                    visited.add(current);
                }
                IntArrayList open = this.markedNeighbours(current);
                for (int side = 0; side < open.size(); side++) {
                    int neighbour = open.getInt(side);
                    if (neighbour != excluded && seen.add(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            return false;
        }

        /** The marked neighbours of a cell given as a flat index, in {@link #NEIGHBOURS} order. */
        private IntArrayList markedNeighbours(int index) {
            int x = index % this.sizeX;
            int y = index / (this.sizeX * this.sizeZ);
            int z = (index / this.sizeX) % this.sizeZ;
            IntArrayList found = new IntArrayList(6);
            for (Direction side : NEIGHBOURS) {
                int nx = x + side.getStepX();
                int ny = y + side.getStepY();
                int nz = z + side.getStepZ();
                if (nx < 0 || ny < 0 || nz < 0 || nx >= this.sizeX || ny >= this.sizeY || nz >= this.sizeZ) {
                    continue;
                }
                int neighbour = (ny * this.sizeZ + nz) * this.sizeX + nx;
                if (this.marked(neighbour)) {
                    found.add(neighbour);
                }
            }
            return found;
        }

        /** The marked neighbours of a cell given as a position. */
        private IntArrayList markedNeighbours(BlockPos pos) {
            return this.markedNeighbours(this.index(pos.getX(), pos.getY(), pos.getZ()));
        }

        /** The free neighbours of a cell, marked or not — the merge direction, where unmarked is the whole point. */
        private IntArrayList freeNeighbours(PredictedWorld world, int index) {
            int x = index % this.sizeX;
            int y = index / (this.sizeX * this.sizeZ);
            int z = (index / this.sizeX) % this.sizeZ;
            IntArrayList found = new IntArrayList(6);
            for (Direction side : NEIGHBOURS) {
                int nx = x + side.getStepX();
                int ny = y + side.getStepY();
                int nz = z + side.getStepZ();
                if (nx < 0 || ny < 0 || nz < 0 || nx >= this.sizeX || ny >= this.sizeY || nz >= this.sizeZ) {
                    continue;
                }
                if (this.free(world, this.minX + nx, this.minY + ny, this.minZ + nz)) {
                    found.add((ny * this.sizeZ + nz) * this.sizeX + nx);
                }
            }
            return found;
        }

        private boolean free(PredictedWorld world, int x, int y, int z) {
            return world.inBounds(x, y, z) && !world.isSolidFullCube(x, y, z);
        }

        private boolean onShell(int index) {
            int x = index % this.sizeX;
            int y = index / (this.sizeX * this.sizeZ);
            int z = (index / this.sizeX) % this.sizeZ;
            return x == 0 || y == 0 || z == 0
                    || x == this.sizeX - 1 || y == this.sizeY - 1 || z == this.sizeZ - 1;
        }

        private boolean inBox(int x, int y, int z) {
            return x >= this.minX && x < this.minX + this.sizeX
                    && y >= this.minY && y < this.minY + this.sizeY
                    && z >= this.minZ && z < this.minZ + this.sizeZ;
        }

        /** Same layout as {@link PredictedWorld}'s own index, so the two never disagree about which cell is which. */
        private int index(int x, int y, int z) {
            return ((y - this.minY) * this.sizeZ + (z - this.minZ)) * this.sizeX + (x - this.minX);
        }

        private boolean marked(int index) {
            return (this.outside[index >>> 6] & (1L << (index & 63))) != 0L;
        }

        private void mark(int index) {
            this.outside[index >>> 6] |= 1L << (index & 63);
            this.reachable++;
        }

        private void clear(int index) {
            this.outside[index >>> 6] &= ~(1L << (index & 63));
            this.reachable--;
        }
    }

    /**
     * The retreat order for a cell set reachable only from inside — an inner wall, a corridor, a spiral.
     *
     * <p>Descending free-space distance from the exit, always working from the exit-connected side, backing out
     * rather than in. That covers arbitrarily deep geometry uniformly and without a per-shape rule, which is the
     * reason it is stated as an ordering rather than as a special case: the alternative is a list of shapes somebody
     * has to keep extending.
     *
     * <p>Distance is measured over the free cells of the predicted world by breadth-first flood from {@code exit}, so
     * it is the distance the BOT would travel and not a straight line — the whole point in a spiral, where the two
     * differ by an order of magnitude. Cells the flood never reaches sort first: they are deeper than anything it
     * did reach. Ties break by {@link PlacementGeometry#positionKey}, because two cells at the same depth are
     * genuinely interchangeable and leaving them in input order would put the caller's order into the plan.
     */
    public static List<BlockPos> retreatOrder(PredictedWorld world, List<BlockPos> cells, BlockPos exit) {
        Long2IntOpenHashMap distance = new Long2IntOpenHashMap();
        distance.defaultReturnValue(UNREACHABLE);
        if (exit != null && world.inBounds(exit.getX(), exit.getY(), exit.getZ())) {
            List<BlockPos> queue = new ArrayList<>();
            queue.add(exit);
            distance.put(PlacementGeometry.positionKey(exit), 0);
            int at = 0;
            while (at < queue.size() && queue.size() < RETREAT_FLOOD_BUDGET) {
                BlockPos current = queue.get(at++);
                int next = distance.get(PlacementGeometry.positionKey(current)) + 1;
                for (Direction side : NEIGHBOURS) {
                    BlockPos neighbour = current.relative(side);
                    if (!world.inBounds(neighbour.getX(), neighbour.getY(), neighbour.getZ())
                            || world.isSolidFullCube(neighbour.getX(), neighbour.getY(), neighbour.getZ())) {
                        continue;
                    }
                    long key = PlacementGeometry.positionKey(neighbour);
                    if (distance.get(key) != UNREACHABLE) {
                        continue;
                    }
                    distance.put(key, next);
                    queue.add(neighbour);
                }
            }
        }
        List<BlockPos> ordered = new ArrayList<>(cells);
        // Negated rather than reversed so the unreachable sentinel, which is the largest distance, stays the first
        // entry under the same comparison instead of needing a case of its own.
        ordered.sort(Comparator
                .comparingInt((BlockPos cell) -> -distance.get(PlacementGeometry.positionKey(cell)))
                .thenComparingLong(PlacementGeometry::positionKey));
        return List.copyOf(ordered);
    }
}
