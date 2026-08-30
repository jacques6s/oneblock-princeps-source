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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The one block family that gets its own rule: everything placed by looking UP — pistons and sticky pistons with
 * {@code facing=down}, observers with {@code facing=up}, droppers and dispensers pointing down.
 *
 * <p>592 cells in etz-basalt, 448 of them in a single row at y=-57, and the row where the old builder lost 1 274
 * cells at once and about 10 000 in the cascade that followed. It is 3.9 % of the build and it was most of the
 * failure.
 *
 * <h2>The arithmetic that fixes it</h2>
 *
 * <p>A piston lands {@code facing=down} only when the dominant axis of the look is UP, so the eye must be BELOW the
 * click point. The highest click point available on any neighbour of T is the UNDERSIDE of a block at T+UP, at
 * exactly {@code T.y + 1.0}. With the crouched eye at feet + 1.27:
 *
 * <pre>
 * feet + 1.27 &lt; T.y + 1.0   =&gt;   feet &lt; T.y - 0.27   =&gt;   feet &lt;= T.y - 1
 * </pre>
 *
 * <p><b>Every upward stance stands at least one layer below the target, without exception.</b> Directly below T is
 * excluded on top of that, because the head would then be inside T. That single inequality is what
 * {@link PlacementGeometry#lowestStanceOffset} widens the stance window to -3 for, and it is why the family cannot
 * be served by the same reasoning as everything else.
 *
 * <h2>Why the click surface is scaffold above T, and why that is better rather than merely available</h2>
 *
 * <p>The instinct is to wait for a side neighbour to be built and click its side face. Measured, from feet at
 * T+East+Down with the approach optimiser allowed to work:
 *
 * <pre>
 * eye   (T.x+1.30 ; T.y+0.27 ; T.z+0.50)
 * aim   (T.x+0.98 ; T.y+1.00 ; T.z+0.50)
 * d   = (-0.32    ; +0.73    ;  0.00)      UP dominant, margin 0.41, reach 0.80
 * </pre>
 *
 * <p>The side-face stance yields 0.31 optimised and <b>0.19 from the cell centre</b>, because a side face tops out at
 * {@code T.y+0.98} and pins one horizontal axis to the plane of the face. The underside at T+UP is higher, is
 * available over its whole area, and lets BOTH horizontal axes be clamped toward the eye. 0.41 against 0.19 is the
 * difference between a certainty and a coin flip, and it is the return that made {@code approach} a plan parameter.
 *
 * <p>T+UP is in layer y+1, which is empty while layer y is being built, so scaffold is allowed there by the rule in
 * 5.5 and its removal is the trivial case of invariant S. Three actions per piston, all from one stance, no walking
 * between them: {@code PLACE_SCAFFOLD(T+UP) -> PLACE(T) -> REMOVE_SCAFFOLD(T+UP)}. The ledger never holds more than
 * one block.
 *
 * <h2>The helper block above T needs a helper block of its own — the gap in 5.7.2</h2>
 *
 * <p>Vanilla places a block by clicking a face of an EXISTING one, so a helper block at {@code T+UP} needs a solid
 * neighbour of {@code T+UP}: that is {@code T} itself (the empty cell we are trying to fill), the four laterals on
 * layer y+1, or {@code T+2UP} — and under the hard layer bound every one of them is empty. 5.7.2 states where the
 * helper block goes and never says what it is placed against, and in an empty layer y+1 the answer is: nothing. It
 * cannot be placed at all.
 *
 * <p>So the click surface is reached by a <b>support chain</b>: the shortest run of helper blocks, at most
 * {@link #MAX_SUPPORT_HELPERS} of them, from something that already stands to a neighbour of {@code T+UP}. Zero when
 * {@code T+UP} can be clicked against straight away; one — a bridge on top of a standing lateral neighbour of T — for
 * the 448 pistons of y=-57, which 5.2 measured always have two solid side neighbours or more; and, measured on the
 * farm, TWO for every {@code observer[facing=up]} in y=-58.
 *
 * <p>That last number is why the chain is a search and not a special case. Those 28 observers sit in a pocket: all
 * four laterals on their own layer are schematic AIR, so there is no standing neighbour to bridge from, and a search
 * that knows only "lateral neighbour plus one bridge" refuses every one of them. What DOES stand near them is a cell
 * of the finished layer below — the repeater at {@code T+DOWN+NORTH} in the worked case — so the chain climbs
 * {@code T+NORTH} (layer y, schematic air, rule 2 allows it), then {@code T+NORTH+UP} (layer y+1, a later layer, rule
 * 2 allows it), and clicks the last one's side to put the click surface at {@code T+UP}.
 *
 * <p>The consequence for 5.7.5 is stated here rather than buried: with a non-empty chain the family does NOT come
 * first in its layer, because the chain needs something that has already been placed. The pre-sort in
 * {@link #layerOrder} still puts these cells at the head of the layer, which is what the ordinary frontier wants, but
 * the escalation path cannot honour it and does not pretend to.
 *
 * <h2>And the consequence that does survive: head room</h2>
 *
 * <p>Once the click surface is scaffold, the piston needs no side neighbour to CLICK — only, when the chain is
 * non-empty, something to start the chain from. Its head room on layer y is the cell above the foot column, and 5.2's
 * own count is what guarantees one is free: a piston with three solid side neighbours still leaves the fourth, and the
 * four corner columns are usually gaps in a farm layout. That is the second thing the eight-column search buys, and it
 * is why {@link #classifyFootColumns} never stops at the first workable column: in the blocked case the report's whole
 * value is the list of what was tried.
 */
public final class UpwardLook {

    private UpwardLook() {
    }

    /** Four laterals and four corners at each of the three depths the oracle permits for this family. */
    public static final int FOOT_COLUMNS = 24;

    /**
     * The twenty-four feet offsets from the target, in preference order: shallow before deep, and at each depth the
     * four laterals before the four corners.
     *
     * <p>Both groups produce <b>the same 0.41 margin</b> — the corner clamps both horizontal axes symmetrically, so
     * nothing is lost diagonally — but the corner's reach is 0.86 against the lateral's 0.80, and among equals the
     * shorter ray is the one with fewer cells to be obstructed by. Laterals therefore rank first, and the corners are
     * not a fallback but a doubling of the candidate set: in farm layouts the diagonal is more often the gap than the
     * side is.
     *
     * <p>The corner ray crosses the edge between two side neighbours on layer y. That would be blocked if they were
     * already standing — and under the "family goes first" rule they are not, which is the second thing that rule
     * buys.
     *
     * <p>Within each group the order is lexicographic by (dx, dz), fixed here so that two runs pick the same column
     * out of several workable ones. The inequality gives the shallow bound {@code dy <= -1}; the oracle's widened
     * stance window gives the other bound, {@code dy >= -3}.
     */
    public static final List<Vec3i> FOOT_OFFSETS = footOffsets();

    private static List<Vec3i> footOffsets() {
        int[][] horizontal = {
                {-1, 0}, {0, -1}, {0, 1}, {1, 0},
                {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
        };
        List<Vec3i> offsets = new ArrayList<>(FOOT_COLUMNS);
        for (int dy = -1; dy >= -3; dy--) {
            for (int[] xz : horizontal) {
                offsets.add(new Vec3i(xz[0], dy, xz[1]));
            }
        }
        return List.copyOf(offsets);
    }

    /** Why one foot column cannot be used, or that it can. The three-row table of 5.7.4, made explicit because the
     *  three rows have three different answers and V2 conflated them into one deferral. */
    public enum Status {

        /** Feet cell is free and there is something to stand on beneath it. Nothing to do. */
        USABLE,

        /** Feet cell is free but so is the cell below it. Solvable: a helper block on y-2 is a stepping stone, and a
         *  cell the schematic calls AIR is not a schematic cell, so scaffold is allowed there. Removed before the
         *  layer bound like any other. */
        NEEDS_STEPPING_STONE,

        /** The schematic wants a block in the feet cell. This is the one scaffold cannot fix, because the shortfall
         *  is a HOLE and scaffold only adds. When all eight columns read this, the cell is a Q1 report. */
        BLOCKED_BY_SCHEMATIC,

        /** Pre-existing terrain occupies the feet cell and the schematic does not want it there — a {@code BREAK},
         *  not a blocker, and deliberately distinguished from {@link #BLOCKED_BY_SCHEMATIC} because the answers are
         *  opposite. */
        BLOCKED_BY_TERRAIN,

        /** The column falls outside the snapshot, so the answer would be a guess. Feeds
         *  {@link BuildPlan.Confidence#PROVISIONAL}, never a blocker. */
        OUT_OF_BOUNDS
    }

    /**
     * One candidate foot column, classified.
     *
     * @param corner true for the four diagonal offsets; carried because the report distinguishes "no lateral column"
     *               from "no column at all", and the first is normal
     */
    public record FootColumn(BlockPos feet, Vec3i offset, boolean corner, Status status) {
    }

    /**
     * A solved upward-look cell: where the helper blocks go, which column the bot stands in, and the placement it
     * enables.
     *
     * @param scaffold        always {@code target.above()} — see {@link #clickSurface}
     * @param supports        the support chain, in placement order: the helper blocks that have to stand before
     *                        {@code scaffold} has anything to be clicked against, empty when it already had. See the
     *                        class javadoc — this component is not in plan 5.7.2, which never says what the helper
     *                        block above T is placed against
     * @param steppingStone   the y-2 helper block when {@link Status#NEEDS_STEPPING_STONE} applied, else {@code null}
     * @param scaffoldRemoval the paired removal, emitted into the same plan; a helper block without one violates the
     *                        layer bound and the bench audit checks for exactly that
     * @param supportRemovals the chain's removals that can be taken from the same stance, in the order they are
     *                        emitted — reverse placement order, and a prefix of the chain rather than all of it,
     *                        because a support that cannot be reached from here buries everything under it and the
     *                        layer gate owns the remainder. Never a reason to refuse the cell: the gate is the fallback
     *                        that exists precisely so that a removal which needs a walk is still in the plan
     */
    public record Solved(BlockPos target, BlockPos scaffold, List<BlockPos> supports, BlockPos steppingStone,
                         FootColumn column, PlacementSolution solution, BuildAction.RemoveScaffold scaffoldRemoval,
                         List<BuildAction.RemoveScaffold> supportRemovals) {

        public Solved {
            supports = List.copyOf(supports);
            supportRemovals = List.copyOf(supportRemovals);
        }

        /**
         * Every helper block this cell needs, in the order they have to go down.
         *
         * <p>The order is world order and not a preference: the stepping stone is what makes the foot column standable
         * at all, each support is what the next one is placed against, and {@code scaffold} is what the target is
         * clicked against. Proving them in any other order proves them against a world that will not exist.
         */
        public List<BlockPos> helpersInPlacementOrder() {
            List<BlockPos> helpers = new ArrayList<>(this.supports.size() + 2);
            if (this.steppingStone != null) {
                helpers.add(this.steppingStone);
            }
            helpers.addAll(this.supports);
            helpers.add(this.scaffold);
            return List.copyOf(helpers);
        }
    }

    /**
     * How many helper blocks the support chain may stack under the click surface.
     *
     * <p>Three, which is what it takes to climb out of the deepest pocket the farm actually has: a cell of the
     * finished layer y-1 is the last thing that stands, and reaching {@code T+UP} from there is one helper on y-1, one
     * on y and one on y+1. A fourth would be a chain whose foot is two finished layers down, which no cell in
     * etz-basalt needs and which would cost the search a sixfold in solves for a case nothing has ever asked for.
     * A cell the chain cannot reach in three is a named blocker, which is the answer this design prefers.
     */
    public static final int MAX_SUPPORT_HELPERS = 3;

    /**
     * The order the chain looks for something to stand on, fixed so that two runs build the same chain out of several
     * workable ones.
     *
     * <p>DOWN first, and that is the whole heuristic: the chain is climbing OUT of empty layers toward the finished
     * ones, and the block under a candidate is the only neighbour guaranteed to be in a layer that is already built.
     * The laterals follow in compass order, and UP is last because it can only succeed against pre-existing terrain —
     * every schematic layer above the one being built is empty by the hard layer bound.
     */
    private static final Direction[] SUPPORT_DIRECTIONS = {
            Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP
    };

    // ------------------------------------------------------------------- classification

    /** Does this state need a look whose dominant axis is UP? Delegates to
     *  {@link PlacementGeometry#needsUpwardLook} — the family test lives with the rest of the vanilla knowledge and
     *  is named here so that the planner reads the rule rather than the mechanism. */
    public static boolean applies(BlockState desired) {
        return desired != null && PlacementGeometry.needsUpwardLook(desired);
    }

    /**
     * The 5.7.5 rule, as a comparator: within a layer, upward-look cells sort before everything else, and everything
     * else keeps the (x, z) lexicographic order that makes the plan reproducible.
     *
     * <p>This is a PRE-sort of the layer's cell list, not a policy applied inside the frontier. The distinction
     * matters: the frontier already handles ordinary dependency, and folding a family preference into it would make
     * the selection policy of 5.3 family-aware — which is exactly the special-casing this design removed. Sorting the
     * input leaves the frontier general and still produces the "pistons first" behaviour.
     *
     * <p>Total rather than merely stable. {@code List.sort} is stable and the layer list arrives in (x, z) order, so
     * the trailing terms decide nothing today — but a caller that hands in a set, or two layers at once, would
     * otherwise get an order that depends on how it built its list, and the byte-identical-plan property would be
     * gone with no test able to see it.
     */
    public static Comparator<BlockPos> layerOrder(SchematicView view) {
        return Comparator.comparingInt((BlockPos cell) -> sortsFirst(view, cell) ? 0 : 1)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getY);
    }

    /** Does this cell sort ahead of the rest of its layer? */
    public static boolean sortsFirst(SchematicView view, BlockPos cell) {
        return view != null && cell != null && applies(view.desired(cell));
    }

    /** The cell the helper block goes in: {@code target.above()}, always, for every member of the family. Named
     *  rather than inlined so the two places that must agree — the placement and its removal — cannot drift. */
    public static BlockPos clickSurface(BlockPos target) {
        return target.above();
    }

    /** {@link Direction#DOWN}: the face of the helper block that is clicked is its underside, the only face whose
     *  every point lies at {@code target.y + 1.0}. */
    public static Direction clickFace() {
        return Direction.DOWN;
    }

    /** The twenty-four candidate feet cells, in {@link #FOOT_OFFSETS} order, without looking at the world. */
    public static List<BlockPos> footColumns(BlockPos target) {
        List<BlockPos> feet = new ArrayList<>(FOOT_COLUMNS);
        for (Vec3i offset : FOOT_OFFSETS) {
            feet.add(target.offset(offset));
        }
        return List.copyOf(feet);
    }

    /** All twenty-four, each with a {@link Status}. Never short-circuited on the first usable one: the
     *  report's whole value in the blocked case is that it lists what was tried, and a search that stopped early can
     *  only say that it stopped. */
    public static List<FootColumn> classifyFootColumns(PredictedWorld world, SchematicView view, BlockPos target) {
        List<FootColumn> columns = new ArrayList<>(FOOT_COLUMNS);
        for (Vec3i offset : FOOT_OFFSETS) {
            BlockPos feet = target.offset(offset);
            boolean corner = offset.getX() != 0 && offset.getZ() != 0;
            columns.add(new FootColumn(feet, offset, corner, classify(world, view, feet)));
        }
        return List.copyOf(columns);
    }

    /**
     * Settings-free structural census for the headless fixture only.
     *
     * <p>This is deliberately not used to choose a planner stance or to produce Q1. It exists so the litematic parser
     * and historical census can still be unit-tested without loading {@code PrincepsAPI}; the real planner and the
     * shim-backed dry run use {@link #classifyFootColumns} and therefore the pathfinder's authoritative predicate.
     */
    static List<FootColumn> classifyFootColumnsStructurally(PredictedWorld world, SchematicView view,
                                                            BlockPos target) {
        List<FootColumn> columns = new ArrayList<>(FOOT_COLUMNS);
        for (Vec3i offset : FOOT_OFFSETS) {
            BlockPos feet = target.offset(offset);
            boolean corner = offset.getX() != 0 && offset.getZ() != 0;
            columns.add(new FootColumn(feet, offset, corner, classifyStructurally(world, view, feet)));
        }
        return List.copyOf(columns);
    }

    /**
     * One column's authoritative verdict, in the order the three rows of 5.7.4 have to be asked.
     *
     * <p>{@link PredictedWorld#isStandable} is the first and final authority. A finished schematic cell at the feet is
     * not automatically an obstruction: wire, rails and other passable states may legally occupy it. Conversely,
     * merely finding something below the feet does not make that floor walkable. The old structural approximation got
     * both cases wrong.
     *
     * <p>If the live column is not standable and its floor is pristine air, a full stepping stone is staged and the
     * same authoritative question is asked again. Only a positive answer earns
     * {@link Status#NEEDS_STEPPING_STONE}.
     */
    private static Status classify(PredictedWorld world, SchematicView view, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!world.inBounds(feet.getX(), feet.getY(), feet.getZ())
                || !world.inBounds(floor.getX(), floor.getY(), floor.getZ())
                || !world.isLoaded(feet.getX(), feet.getY(), feet.getZ())
                || !world.isLoaded(head.getX(), head.getY(), head.getZ())) {
            return Status.OUT_OF_BOUNDS;
        }
        if (world.isStandable(feet.getX(), feet.getY(), feet.getZ())) {
            return Status.USABLE;
        }

        // A missing floor is only a stepping-stone case if a real full cube there makes the pathfinder's exact stance
        // predicate true. "Feet/head replaceable and something below" is not a second, approximate definition of
        // standability: it misclassifies passable schematic states and unwalkable floors in opposite directions.
        if (world.get(floor).is(Blocks.AIR) && (view == null || !view.wantsBlock(floor))) {
            ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(world);
            try {
                scratch.apply(floor, Blocks.DIRT.defaultBlockState());
                if (world.isStandable(feet.getX(), feet.getY(), feet.getZ())) {
                    return Status.NEEDS_STEPPING_STONE;
                }
            } finally {
                scratch.restore();
            }
        }

        // Only live occupants count. A desired block in the current layer that is still air cannot obstruct this solve;
        // a finished lower-layer block which really stands can.
        if ((view != null && view.wantsBlock(feet) && !world.get(feet).is(Blocks.AIR))
                || (view != null && view.wantsBlock(head) && !world.get(head).is(Blocks.AIR))
                || (view != null && view.wantsBlock(floor) && !world.get(floor).is(Blocks.AIR))) {
            return Status.BLOCKED_BY_SCHEMATIC;
        }
        return Status.BLOCKED_BY_TERRAIN;
    }

    /** The old cheap approximation, quarantined behind {@link #classifyFootColumnsStructurally}. */
    private static Status classifyStructurally(PredictedWorld world, SchematicView view, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!world.inBounds(feet.getX(), feet.getY(), feet.getZ())
                || !world.inBounds(floor.getX(), floor.getY(), floor.getZ())
                || !world.isLoaded(feet.getX(), feet.getY(), feet.getZ())
                || !world.isLoaded(head.getX(), head.getY(), head.getZ())) {
            return Status.OUT_OF_BOUNDS;
        }
        if (view != null && view.wantsBlock(feet)) {
            return Status.BLOCKED_BY_SCHEMATIC;
        }
        if (!free(world, feet) || !free(world, head)) {
            return Status.BLOCKED_BY_TERRAIN;
        }
        if (free(world, floor) && (view == null || !view.wantsBlock(floor))) {
            return Status.NEEDS_STEPPING_STONE;
        }
        return Status.USABLE;
    }

    // ------------------------------------------------------------------- solving

    /**
     * Solve one upward-look cell: choose the foot column, place the helper block above the target, prove the
     * placement from the underside, and pair the removal.
     *
     * <p>Every world edit here is scratch work on {@link PredictedWorld} that is undone before returning — the
     * caller's world comes back exactly as it was handed over, deltas included. The helper blocks are applied and not
     * merely assumed because the proof has to be the real one: the target's own solve reads standability, occlusion
     * and the clicked face's collision box out of the world, and a proof against a world that does not contain the
     * block being clicked is not a proof.
     *
     * @param scaffoldItem the reserved throwaway; its type appears in no schematic cell by the rule in 5.5
     * @param scaffoldSlot where that item sits — {@link HotbarSchedule#THROWAWAY_SLOT} under the existing convention
     * @return empty when no column works; the caller then asks {@link #q1} for the reason to report
     */
    public static Optional<Solved> solve(PlacementOracle oracle, PredictedWorld world, SchematicView view,
                                         BlockPos target, BlockState desired, List<ItemStack> hotbar,
                                         SolveBudget budget, Item scaffoldItem, int scaffoldSlot) {
        BlockState helper = ScaffoldPlanner.helperState(scaffoldItem);
        if (oracle == null || world == null || view == null || target == null || helper == null || !applies(desired)) {
            return Optional.empty();
        }
        int layer = view.layerOf(target);
        BlockPos scaffold = clickSurface(target);
        if (ScaffoldPlanner.eligibilityOf(world, view, scaffold, layer) != ScaffoldPlanner.Refusal.NONE) {
            return Optional.empty();
        }
        List<ItemStack> helperHotbar = ScaffoldPlanner.helperHotbar(scaffoldItem, scaffoldSlot);

        // Once for the cell, not once per foot column. The chain reaches from what stands to a neighbour of T+UP and
        // is a property of the world around T alone; asking it inside the column loop would repeat the same search up
        // to twenty-four times over an unchanged neighbourhood.
        List<BlockPos> supports = supportChainFor(oracle, world, view, budget, scaffold, helper, helperHotbar, layer);
        if (supports == null) {
            return Optional.empty();   // nothing to place the click surface against; see the class javadoc
        }
        for (FootColumn column : classifyFootColumns(world, view, target)) {
            if (column.status() != Status.USABLE && column.status() != Status.NEEDS_STEPPING_STONE) {
                continue;
            }
            Optional<Solved> solved = solveFrom(oracle, world, view, target, desired, hotbar, helperHotbar, budget,
                    helper, column, scaffold, supports, layer);
            if (solved.isPresent()) {
                return solved;
            }
        }
        return Optional.empty();
    }

    /**
     * One foot column, staged and proven end to end.
     *
     * <p>The staging order is the execution order — stepping stone, support chain, click surface, target — because
     * each step changes what the next one can see. Everything is undone in the {@code finally}, including on the paths
     * that give up half way, so a column that fails costs the caller's world nothing.
     */
    private static Optional<Solved> solveFrom(PlacementOracle oracle, PredictedWorld world, SchematicView view,
                                              BlockPos target, BlockState desired, List<ItemStack> hotbar,
                                              List<ItemStack> helperHotbar, SolveBudget budget, BlockState helper,
                                              FootColumn column, BlockPos scaffold, List<BlockPos> supports,
                                              int layer) {
        BlockPos feet = column.feet();
        BlockPos stone = column.status() == Status.NEEDS_STEPPING_STONE ? feet.below() : null;
        if (stone != null && ScaffoldPlanner.eligibilityOf(world, view, stone, layer) != ScaffoldPlanner.Refusal.NONE) {
            return Optional.empty();
        }
        ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(world);
        try {
            if (stone != null) {
                scratch.apply(stone, helper);
            }
            // Re-assert the classifier's authoritative result against the exact helper state and the exact staged
            // world used by the solve. The classifier probes a generic full cube; this is the throwaway actually held.
            if (!world.isStandable(feet.getX(), feet.getY(), feet.getZ())) {
                return Optional.empty();
            }
            for (BlockPos support : supports) {
                if (!world.get(support).is(Blocks.AIR)) {
                    // The stepping stone landed on a cell the chain wanted. Refuse THIS column rather than build a
                    // chain over a block the plan is about to book twice: another column will not need that stone.
                    return Optional.empty();
                }
                scratch.apply(support, helper);
            }
            scratch.apply(scaffold, helper);

            // The focused budget is spent HERE and nowhere else in this method: it lifts two caps that only make
            // sense over an unfiltered search, and the helper-block placements above run unfiltered on the planner's
            // own budget, where lifting them would put the full approach grid on all 243 stances of every candidate.
            PlacementOracle.Solve solve = oracle.solve(world, target, desired, hotbar, focused(budget),
                    fromColumn(target, feet));
            Optional<PlacementSolution> best = solve.best();
            if (best.isEmpty() || !scaffold.equals(best.get().against())) {
                return Optional.empty();
            }
            scratch.apply(target, best.get().predicted());
            Optional<BuildAction.RemoveScaffold> removal = ScaffoldPlanner.removalOf(world, oracle.pose(), budget,
                    scaffold, helper, feet, layer);
            if (removal.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Solved(target, scaffold, supports, stone, column, best.get(), removal.get(),
                    supportRemovals(world, oracle, budget, supports, helper, feet, layer, scaffold, scratch)));
        } finally {
            scratch.restore();
        }
    }

    /**
     * The chain's removals that can be taken from this stance, in the order they are emitted.
     *
     * <p>Reverse placement order, because a support is buried by the one placed on top of it, and each is proven
     * against a world the earlier removals have already emptied — starting with the click surface, whose disappearance
     * is what frees the line of sight to the rest and is therefore the reason none of this can be asked any earlier.
     *
     * <p>Stops at the first support that cannot be reached rather than skipping it: everything under it is still
     * buried, so a removal proven past that point would be proven against a world that will not exist. The layer gate
     * owns the remainder, which is exactly the fallback it is there for.
     */
    private static List<BuildAction.RemoveScaffold> supportRemovals(PredictedWorld world, PlacementOracle oracle,
                                                                    SolveBudget budget, List<BlockPos> supports,
                                                                    BlockState helper, BlockPos feet, int layer,
                                                                    BlockPos scaffold,
                                                                    ScaffoldPlanner.Scratch scratch) {
        if (supports.isEmpty()) {
            return List.of();
        }
        scratch.apply(scaffold, Blocks.AIR.defaultBlockState());
        List<BuildAction.RemoveScaffold> removals = new ArrayList<>(supports.size());
        for (int index = supports.size() - 1; index >= 0; index--) {
            BlockPos support = supports.get(index);
            Optional<BuildAction.RemoveScaffold> removal = ScaffoldPlanner.removalOf(world, oracle.pose(), budget,
                    support, helper, feet, layer);
            if (removal.isEmpty()) {
                break;
            }
            removals.add(removal.get());
            scratch.apply(support, Blocks.AIR.defaultBlockState());
        }
        return List.copyOf(removals);
    }

    /**
     * The support chain for {@code cell}: the helper blocks that have to stand before it can be placed at all, in
     * placement order.
     *
     * <p>Empty when {@code cell} is placeable as the world stands — the one-helper shape of 5.7.2. {@code null} when no
     * chain of at most {@link #MAX_SUPPORT_HELPERS} reaches something that already stands, which is the honest answer
     * and the point at which the cell becomes a named blocker rather than an escalation.
     *
     * <p>The scratch world this searches in is restored before returning, so the caller gets its world back untouched
     * and stages the chain itself in the order it will be executed.
     */
    private static List<BlockPos> supportChainFor(PlacementOracle oracle, PredictedWorld world, SchematicView view,
                                                  SolveBudget budget, BlockPos cell, BlockState helper,
                                                  List<ItemStack> helperHotbar, int layer) {
        ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(world);
        try {
            return supportChain(oracle, world, view, budget, cell, helper, helperHotbar, layer,
                    MAX_SUPPORT_HELPERS, new ArrayList<>(List.of(cell)), scratch);
        } finally {
            scratch.restore();
        }
    }

    /**
     * One frame of the chain search, depth-first in {@link #SUPPORT_DIRECTIONS} order.
     *
     * <p>On success the returned chain is left STAGED in {@code scratch}, because the frame above proves its own cell
     * against a world that contains it. On failure every edit this frame made is unwound, so a rejected branch costs
     * the sibling branches nothing.
     *
     * @param path the cells already on the current branch. Guards against a chain that walks back into a cell it is
     *             itself trying to fill — such a branch can never succeed, since an ancestor is unstaged while its
     *             descendants run and would therefore be asked the same question against the same world, but without
     *             the guard it costs the full depth to find that out six directions at a time
     */
    private static List<BlockPos> supportChain(PlacementOracle oracle, PredictedWorld world, SchematicView view,
                                               SolveBudget budget, BlockPos cell, BlockState helper,
                                               List<ItemStack> helperHotbar, int layer, int depth,
                                               List<BlockPos> path, ScaffoldPlanner.Scratch scratch) {
        if (ScaffoldPlanner.helperPlacement(oracle, world, budget, cell, helper, helperHotbar, null).isPresent()) {
            return List.of();
        }
        if (depth <= 0) {
            return null;
        }
        for (Direction side : SUPPORT_DIRECTIONS) {
            BlockPos support = cell.relative(side);
            if (contains(path, support)
                    || ScaffoldPlanner.eligibilityOf(world, view, support, layer) != ScaffoldPlanner.Refusal.NONE) {
                continue;
            }
            path.add(support);
            List<BlockPos> deeper;
            try {
                deeper = supportChain(oracle, world, view, budget, support, helper, helperHotbar, layer, depth - 1,
                        path, scratch);
            } finally {
                path.remove(path.size() - 1);
            }
            if (deeper == null) {
                continue;
            }
            scratch.apply(support, helper);
            if (ScaffoldPlanner.helperPlacement(oracle, world, budget, cell, helper, helperHotbar, null).isPresent()) {
                List<BlockPos> chain = new ArrayList<>(deeper.size() + 1);
                chain.addAll(deeper);
                chain.add(support);
                return List.copyOf(chain);
            }
            // The support stands and its own chain under it stands, and the cell STILL cannot be placed against it.
            // Unwind this whole branch -- the support plus everything the recursion staged beneath it -- rather than
            // leaving helper blocks in the scratch world that the next direction would then be proven against.
            for (int undo = 0; undo <= deeper.size(); undo++) {
                scratch.restoreLast();
            }
        }
        return null;
    }

    /**
     * The budget the family's own solve runs on: the planner's, with both caps lifted and the fast path off.
     *
     * <p>Not a widening of the search — {@link #fromColumn} has already narrowed it to ONE stance and ONE face, so the
     * caps can only ever bite by counting candidates the filter is about to refuse anyway. Lifting them is what makes
     * the worked geometry of 5.7.2 actually come out: {@code PlacementOracle} decides whether a stance gets the
     * approach grid by how many stances it has EVALUATED so far, and the foot column is typically the sixth standable
     * stance of the 243 in the window — past {@code approachRefineTopN = 3}. Judged from the cell centre the same
     * click measures margin 0.21 instead of 0.41, which is a solution the planner would accept and a coin flip the
     * design spent the whole approach parameter to avoid.
     */
    private static SolveBudget focused(SolveBudget budget) {
        return new SolveBudget(Integer.MAX_VALUE, budget.maxRays(), budget.minMargin(), budget.maxReach(),
                budget.approachStep(), budget.approachInset(), Integer.MAX_VALUE, false);
    }

    /** The solve, narrowed to the one stance and the one face this family is built around. Everything else in the
     *  stance window is refused before it can spend a ray. */
    static PlacementOracle.StanceFilter fromColumn(BlockPos target, BlockPos feet) {
        return (cell, stance, face) -> face == clickFace() && target.equals(cell) && feet.equals(stance);
    }

    /** Air, water and grass: something a body can be in. Deliberately coarser than
     *  {@link PredictedWorld#isStandable}, which is the authority and is asked separately — this one only has to
     *  decide whether a column is worth staging a stepping stone for. */
    private static boolean free(PredictedWorld world, BlockPos pos) {
        BlockState state = world.get(pos);
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------- the report

    /** Are all candidate columns {@link Status#BLOCKED_BY_SCHEMATIC} — the one case in this family that scaffold
     *  cannot reach, because what is missing is a hole and scaffold only adds? */
    public static boolean allColumnsAreSchematic(List<FootColumn> columns) {
        if (columns == null || columns.size() != FOOT_COLUMNS) {
            return false;
        }
        for (FootColumn column : columns) {
            if (column.status() != Status.BLOCKED_BY_SCHEMATIC) {
                return false;
            }
        }
        return true;
    }

    /** Compatibility name for callers written when the search covered only eight columns at {@code dy=-1}. */
    @Deprecated
    public static boolean allEightAreSchematic(List<FootColumn> columns) {
        return allColumnsAreSchematic(columns);
    }

    /**
     * The Q1 report for a cell whose twenty-four foot columns are all blocked by standing schematic geometry.
     *
     * <p>Report, do not solve — decision D6, and the reporting is the deliverable rather than a placeholder for it.
     * The dry run lists EVERY affected cell in one pass, not the first: one run that says "these 37 cells have no
     * foot column" tells you the size of the problem, and 37 runs that each name one cell tell you nothing you can
     * act on. The measurement that decides whether planned excavation is worth building is this list's length.
     *
     * <p>The eventual fix is recorded so that it is not rediscovered as an idea: break exactly one blocking cell,
     * place T, restore the broken cell from above. It is not "tear down to fix" — which E3 forbids — but a counted,
     * named excavation whose restoration is in the same plan, the same action type as scaffold with the sign
     * reversed. Two conditions come with it: the broken block must be identically restorable, and nothing may rest on
     * it. The recursion worry (the block to break is itself an upward cell) terminates at the lowest schematic layer
     * and only bites when all candidate columns come from this 3.9 % family — which is the extra column this same report
     * measures.
     *
     * @return the blocker, or empty when at least one column is usable and there is nothing to report
     */
    public static Optional<PlanReport.Blocker> q1(PredictedWorld world, SchematicView view, BlockPos target,
                                                  BlockState desired, List<FootColumn> columns) {
        if (!allColumnsAreSchematic(columns)) {
            return Optional.empty();
        }
        int recursive = 0;
        for (FootColumn column : columns) {
            if (applies(view.desired(column.feet()))) {
                recursive++;
            }
        }
        // One counter, on the rejection the columns actually failed: every candidate stance for this family is a
        // foot column, and every one of them intersects the finished build. The tally reads "24 not standable,
        // 0 workable", which is the true shape of the failure rather than a search that was never run.
        int[] rejections = new int[PlacementOracle.Rejection.values().length];
        rejections[PlacementOracle.Rejection.STANCE_NOT_STANDABLE.ordinal()] = FOOT_COLUMNS;
        String detail = "all " + FOOT_COLUMNS + " candidate foot columns across y-1 through y-3 are blocked by "
                + "standing schematic geometry, so every stance this family can use intersects the finished build; "
                + "the cells listed above are the ones whose ABSENCE would unblock it, not their placement, and "
                + "scaffold cannot help because what is missing is a hole. "
                + recursive + " of the " + FOOT_COLUMNS + " foot cells are themselves upward-look cells"
                + (recursive > 0 ? ", so planned excavation would recurse into this same family there" : "")
                + ". Planned excavation -- break one foot cell, place " + BuildAction.describePos(target)
                + ", restore it from above -- is the recorded direction and is deliberately not implemented (D6).";
        return Optional.of(new PlanReport.Blocker(target, desired, PlacementOracle.Rejection.STANCE_NOT_STANDABLE,
                rejections, FOOT_COLUMNS, unblocking(world, view, target, columns), detail));
    }

    /** The cells whose placement would give {@code target} a usable foot column — the "the 3 cells that would unblock
     *  it" line of {@link PlanReport.Blocker#unblocking}, and for this family it is usually the reverse: cells whose
     *  ABSENCE would. Empty when nothing in the plan's control would help, which is itself the honest answer. */
    public static List<BlockPos> unblocking(PredictedWorld world, SchematicView view, BlockPos target,
                                            List<FootColumn> columns) {
        List<BlockPos> cells = new ArrayList<>();
        for (FootColumn column : columns) {
            if (column.status() != Status.BLOCKED_BY_SCHEMATIC && column.status() != Status.BLOCKED_BY_TERRAIN) {
                continue;
            }
            // Whichever of the two cells of the column is the one that is actually in the way: the feet cell normally,
            // the head room on layer y when the feet are clear. Naming the wrong one of the two sends a reader to look
            // at a cell that is empty, which is worse than naming none.
            BlockPos blocking = free(world, column.feet()) ? column.feet().above() : column.feet();
            if (!contains(cells, blocking)) {
                cells.add(blocking);
            }
        }
        cells.sort(Comparator.comparingLong(PlacementGeometry::positionKey));
        return List.copyOf(cells);
    }

    /** By coordinate value rather than by {@code equals}, for the reason trap 1.14 gives: a {@code BetterBlockPos} and
     *  a {@code BlockPos} with the same coordinates are not interchangeable in a hash container and only accidentally
     *  interchangeable in a list. */
    private static boolean contains(List<BlockPos> cells, BlockPos pos) {
        long key = PlacementGeometry.positionKey(pos);
        for (BlockPos cell : cells) {
            if (PlacementGeometry.positionKey(cell) == key) {
                return true;
            }
        }
        return false;
    }
}
