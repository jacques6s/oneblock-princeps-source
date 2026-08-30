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

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The forward simulation: one pass over the schematic that produces the whole frozen order, before the bot takes a
 * step.
 *
 * <pre>
 * world = PredictedWorld(snapshot of the real world)
 * order = []
 *
 * for each layer L, bottom to top:
 *     todo = non-air schematic cells in L, minus the ones already correct
 *     while todo not empty:
 *         # 21.1 -- while the stance still serves, nothing walks
 *         s = far-to-near first of { solveFrom(world, c, stance) : c in todo }
 *         if s is none:
 *             frontier = { c in todo : solve(world, c) yields a solution with margin &gt;= MIN }
 *             s = pick(frontier)                      # which STANCE next, 5.3 / 21.1
 *         if s is not none:
 *             if not invariantsHold(world, s):        # 5.4
 *                 veto (s.cell, s.stance, s.face); continue
 *             order += actionsFor(s.cell, s)          # a LIST, not one action
 *             world.apply(s.cell, s.predicted)
 *             todo -= s.cell
 *             continue
 *
 *         # nothing in this layer is solvable as the world stands -&gt; scaffold, 5.5
 *         (c, s, scaffolds) = solveWithScaffold(world, todo)
 *         if none: -&gt; PLAN INCOMPLETE, report every blocker, stop
 *         order += scaffolds.map(PLACE_SCAFFOLD) + actionsFor(c, s)
 *         ledger += scaffolds
 *         world.apply(...)
 *         todo -= c
 *
 *     # LAYER GATE -- the layer is not finished while a helper block still stands
 *     order += ledger.removalOrder().map(REMOVE_SCAFFOLD)
 *     assert every ledger entry removable in the world as it now stands   # invariant S
 *     ledger.clear()
 * </pre>
 *
 * <h2>Why the order is computed rather than scanned</h2>
 *
 * <p>The execution really is "this block, then the next one". But which one is next cannot be "x ascending, then z
 * ascending", and that is measured rather than assumed. etz-basalt's y=-57 holds 448 pistons with
 * {@code facing=down}. Each needs a solid side neighbour to click AND a perpendicular free side neighbour one layer
 * down to stand in. Counted against the schematic:
 *
 * <table><caption>etz-basalt y=-57, 448 pistons</caption>
 * <tr><td>pistons with 2 solid side neighbours in the schematic</td><td>400</td></tr>
 * <tr><td>with 3</td><td>48</td></tr>
 * <tr><td>with 0 or 1</td><td><b>none</b></td></tr>
 * <tr><td>with a valid diagonal stance once those neighbours stand</td><td><b>448 of 448</b></td></tr>
 * </table>
 *
 * <p>The dependency chain is complete and acyclic — but only in the right order, and the right order is not the scan
 * order. V2 lost 1 274 cells at this exact spot in one stroke and roughly 10 000 more in the cascade.
 *
 * <p>The forward simulation solves it with no piston rule at all: the neighbours are solvable and the piston is not,
 * so the frontier takes the neighbours first, and the piston walks in the moment they stand. The two-phase ordering
 * everyone reaches for — "first everything without an orientation problem, then the oriented ones" — falls out as
 * BEHAVIOUR without a single rule for it, and it covers every dependency nobody thought of as well as the one
 * everybody did.
 *
 * <h2>The frontier is maintained, not recomputed</h2>
 *
 * <p>The pseudocode above recomputes the frontier every round, and taken literally that is 2 189 solves per placement
 * on the piston row — 4.8 million solves for one layer, which the dry run cannot pay. The implementation therefore
 * keeps each cell's best solution in {@link PlannerState} and re-solves only what a committed action can have changed:
 * the cells the {@link DependencyIndex} says were using a touched position, plus every remaining cell within
 * {@link #INVALIDATION_RADIUS} of it. Both, not either — the index knows about cells that HAVE a solution, and a cell
 * that had none is exactly the one a new neighbour is likely to unblock.
 *
 * <p>The two sets are unioned rather than trusted individually because they fail in opposite directions and the
 * failure is silent either way: an index-only invalidation never revisits an unsolvable cell, and a radius-only one
 * rests entirely on a constant somebody has to keep true. Before escalating to scaffold — the one decision that is
 * expensive to take wrongly — {@link #frontier} recomputes the whole layer from scratch, so "the frontier is empty"
 * is never merely the incremental bookkeeping's opinion.
 *
 * <h2>Termination</h2>
 *
 * <p>Every iteration removes a cell either from {@code todo} or from {@code frontier}, both finite, and the scaffold
 * branch always shrinks {@code todo}. There is no path that leaves both unchanged, which is what makes "the dry run
 * either produces a plan or names a blocker" a property rather than a hope. The frontier removal is a journal veto and
 * the journal only grows, so a cell can be refused at most (stances x faces) times before it has no candidate left and
 * drops out of the frontier for good.
 *
 * <p>That argument is asserted rather than trusted: {@link #planLayer} throws if an iteration ends with neither
 * {@code todo} smaller nor the journal larger. A dry run that hangs is indistinguishable from a slow one, and this
 * project has paid for that confusion before.
 *
 * <h2>Determinism is the contract</h2>
 *
 * <p>The same schematic against the same snapshot must produce a byte-identical plan, and under the no-jitter
 * decision that extends to an identical tick count. Nothing in this class or anything it calls may iterate a hash
 * container, sample a clock, call {@code Math.random} or read an identity hash: the selection policy's third tiebreak
 * exists precisely so that two candidates are never merely "equal", and it is worthless if an earlier stage already
 * shuffled them. This is the sharpest regression test the project can have, and it only holds if nothing anywhere in
 * the chain is sampled.
 */
public final class OrderPlanner {

    /**
     * How far a committed action can reach into the layer, in cells, for the purpose of invalidating cached solutions.
     *
     * <p>Three for the stance window ({@code PlacementOracle.STANCE_WINDOW_RADIUS}), one for the click face that sits
     * between the stance and the cell, and one for the slack between that and the planning reach of 4.2 — a ray from a
     * stance three cells out can be obstructed by a cell fractionally further away than the stance itself. Five is the
     * smallest value that is provably conservative for the current oracle, and it is deliberately not derived from
     * {@code SolveBudget} at run time: this must not silently widen when somebody raises the reach, it must be
     * revisited.
     */
    private static final int INVALIDATION_RADIUS = 5;

    /**
     * How far from a change an upper stance has to be re-offered — see {@link #rearmElevatedStances}.
     *
     * <p>The sum of the two radii already trusted separately, and conservative for the same reason each of them is: a
     * change can only alter the status of a cell within {@link #INVALIDATION_RADIUS} of it, and a stance can only
     * serve a cell within {@link PlacementOracle#STANCE_WINDOW_RADIUS} of itself. A stance further away than the sum
     * from every position a commit touched therefore cannot have gained a solution it did not already have.
     */
    private static final int ELEVATED_REARM_RADIUS = INVALIDATION_RADIUS + PlacementOracle.STANCE_WINDOW_RADIUS;

    /**
     * How many candidate stances {@link #pick} measures for real before it moves.
     *
     * <p>Every one of them costs a {@link PlacementOracle#solveFrom} per open cell in its window — at most
     * forty-nine within a layer — and a stance is only chosen when the current one is EMPTY, so this is paid once per
     * walk and not once per placement. That is what makes measuring more than one affordable at all.
     *
     * <p>Sixteen because the curve flattens there, measured on etz-basalt end to end rather than reasoned about:
     *
     * <table><caption>walks / actions per stance / rays, whole build</caption>
     * <tr><td>8</td><td>1 200</td><td>3.85</td><td>3.28 M</td></tr>
     * <tr><td>16</td><td>1 169</td><td>3.95</td><td>3.58 M</td></tr>
     * <tr><td>24</td><td>1 147</td><td>4.03</td><td>3.84 M</td></tr>
     * </table>
     *
     * <p>Eight to sixteen buys 31 walks for 0.3 M rays; sixteen to twenty-four buys 22 more for another 0.26 M. The
     * shortlist is ordered by a PROXY — how many open cells name a stance as their own best — and past this point
     * taking more of a proxy's ranking is not the same as taking a better answer.
     */
    private static final int MOVE_SHORTLIST = 16;

    /**
     * Everything the planner carries between actions — and the second argument of every invariant, which is why it
     * is a named type rather than a handful of locals.
     *
     * <p>It is mutable on purpose: the forward simulation IS a mutation of the predicted world plus this. Making it
     * immutable would mean copying a dependency index and a flood-fill marking per candidate, and there are of the
     * order of a hundred thousand candidates in one etz-basalt dry run.
     */
    public static final class PlannerState {

        private final PlacementOracle oracle;
        private final SchematicView view;
        private final V3Settings settings;
        private final SolveBudget budget;
        private final HotbarSchedule.InventorySnapshot inventory;
        private final List<ItemStack> stacks;
        private final DependencyIndex index;
        private final ScaffoldPlanner.Ledger ledger;
        private final Invariants.EnclosureTracker enclosure;

        /**
         * The rejection journal, as a membership set.
         *
         * <p>A {@link HashSet} is permitted here and only here because it is never iterated: it answers
         * {@code contains} for {@link PlacementOracle.StanceFilter} and reports its size to the termination
         * assertion. Both are order-free. Anything that walked it would put a hash order into the plan.
         */
        private final Set<Veto> vetoes = new HashSet<>();

        /**
         * Route-only refusals for the current predicted world.
         *
         * <p>Unlike {@link #vetoes}, these are not evidence that a click is bad. They say only that the body cannot
         * reach that placement stance yet, so every committed world mutation releases them and makes their cells
         * dirty. Keeping the two sets separate is what prevents a temporarily sealed stair from becoming a permanent
         * placement veto.
         */
        private final Set<Veto> routeDeferrals = new HashSet<>();
        private final PlacementOracle.StanceFilter journal;

        /** Cached best solution per cell, keyed by {@link PlacementGeometry#positionKey}. Lookup only. */
        private final Long2ObjectOpenHashMap<PlacementSolution> best = new Long2ObjectOpenHashMap<>();

        private final List<BuildAction> order = new ArrayList<>();
        private final List<PlanReport.Blocker> blockers = new ArrayList<>();
        private final List<PlanReport.TightCell> tight = new ArrayList<>();

        /** Cells already reported TIGHT. The frontier re-solves a cell many times over a layer and the report must
         *  name it once. */
        private final LongOpenHashSet tightSeen = new LongOpenHashSet();
        private final List<PlanReport.LayerSummary> layerSummaries = new ArrayList<>();

        /** Named exceptions gathered while planning — the {@link PlanReport#limitations} lines. Collected as they are
         *  discovered rather than derived afterwards, because the moment a thing could not be proven is the only
         *  moment the reason is still in hand. */
        private final List<String> limitations = new ArrayList<>();

        /** Positions the world was changed at since the layer loop last looked. Drives cache invalidation; the layer
         *  loop drains it. */
        private final List<BlockPos> touched = new ArrayList<>();

        private BlockPos lastStance;

        /** The aim point of the last committed placement, or null before the first. Only ever read to ORDER the next
         *  candidates by how far the head has to travel; nothing is proven from it, so it costs no correctness. */
        private Vec3 lastAim;

        private Item heldItem;
        private int currentLayer;
        private boolean layerOpen;
        private long raysCast;

        /** One vetoed (cell, stance, face) triple. Coordinates as longs and the face as an ordinal so that equality is
         *  value equality and cannot be broken by a {@code BetterBlockPos} whose {@code hashCode} is {@code longHash}
         *  (trap 1.14). */
        private record Veto(long cell, long stance, int face) {
        }

        public PlannerState(PlacementOracle oracle, SchematicView view, V3Settings settings, SolveBudget budget,
                            HotbarSchedule.InventorySnapshot inventory, BlockPos botStart) {
            this(oracle, view, settings, budget, inventory, botStart, PlacementOracle.StanceFilter.ALL);
        }

        /**
         * As above, plus a veto that outlives this plan.
         *
         * @param external the executor's {@link EvidenceJournal}, or {@link PlacementOracle.StanceFilter#ALL} for a
         *                 first plan. It is ANDed with the within-plan vetoes rather than replacing them: the two are
         *                 the two re-entries of decision E-D and both must hold, so a triple the server refused an hour
         *                 ago stays refused even though this plan has never tried it
         */
        public PlannerState(PlacementOracle oracle, SchematicView view, V3Settings settings, SolveBudget budget,
                            HotbarSchedule.InventorySnapshot inventory, BlockPos botStart,
                            PlacementOracle.StanceFilter external) {
            this.oracle = oracle;
            this.view = view;
            this.settings = settings;
            this.budget = budget;
            this.inventory = inventory;
            this.stacks = inventory.slots();
            this.index = new DependencyIndex();
            this.ledger = new ScaffoldPlanner.Ledger();
            // Seeded with a null world ON PURPOSE, and the tracker has to cope: this state is constructed before the
            // layer loop has a world to hand it -- plan() owns the PredictedWorld and passes it per call, which is
            // what keeps every invariant a pure function of (world, state, candidate). The marking is therefore built
            // on the first apply/wouldSeal, both of which receive the world; only the seed cell is known this early.
            this.enclosure = new Invariants.EnclosureTracker(null, botStart);
            PlacementOracle.StanceFilter carried = external == null ? PlacementOracle.StanceFilter.ALL : external;
            this.journal = (cell, stance, face) -> !this.vetoes.contains(vetoOf(cell, stance, face))
                    && !this.routeDeferrals.contains(vetoOf(cell, stance, face))
                    && carried.allows(cell, stance, face);
            this.lastStance = botStart;
            this.currentLayer = view.layers().isEmpty() ? view.min().getY() : view.layers().get(0);
        }

        public PlacementOracle oracle() {
            return this.oracle;
        }

        public SchematicView view() {
            return this.view;
        }

        /** The frozen acceptance rules. The ONLY way settings reach this package below
         *  {@link PlannedBuilderProcess}; nothing here may call {@code Princeps.settings()}. */
        public V3Settings settings() {
            return this.settings;
        }

        public SolveBudget budget() {
            return this.budget;
        }

        /**
          * What the oracle solves its material question against: all 36 slots, not the nine hotbar ones.
          *
          * <p>The invariant during planning is that the item exists SOMEWHERE in the inventory. Which hotbar slot it
          * will be selected from is {@link HotbarSchedule#belady}'s answer, computed over the finished order, and
          * written into the actions by {@link HotbarSchedule#weave} — so asking the nine physical slots here would be
          * asking a question the plan does not depend on, and answering it would cap every build at the seven
          * materials that happened to be on the bar when the run started.
          */
        public List<ItemStack> stacks() {
            return this.stacks;
        }

        public HotbarSchedule.InventorySnapshot inventory() {
            return this.inventory;
        }

        /** {@code position -> cells whose current best solution uses it}. See {@link DependencyIndex}. */
        public DependencyIndex index() {
            return this.index;
        }

        /** The open helper blocks of the current layer. */
        public ScaffoldPlanner.Ledger ledger() {
            return this.ledger;
        }

        /** The incremental free-space marking behind invariant E. */
        public Invariants.EnclosureTracker enclosure() {
            return this.enclosure;
        }

        /**
         * The veto handed to every solve.
         *
         * <p>During the dry run it starts as {@link PlacementOracle.StanceFilter#ALL} and grows as candidates are
         * refused, which is what makes a re-plan converge instead of proposing the same rejected triple forever.
         * At runtime the same filter carries the executor's rejection journal, and that shared mechanism is the
         * whole of decision E-D: two re-entries, one mechanism, no emergency path.
         */
        public PlacementOracle.StanceFilter journal() {
            return this.journal;
        }

        /** Veto a triple for the rest of this plan. */
        public void reject(BlockPos cell, BlockPos stance, net.minecraft.core.Direction face) {
            this.vetoes.add(vetoOf(cell, stance, face));
        }

        /**
         * Defer one route without turning it into lasting placement evidence — and park the CELL while doing it.
         *
         * <p>Parking is the whole of the fix for the basalt livelock. A deferral is keyed on the triple, so deferring
         * one used to invalidate the cell, the next solve produced the same cell from its next-best stance, and that
         * was deferred too: the planner enumerated the cell's entire stance-by-face space one accessToStance call at
         * a time. Measured on layer -59 that was 290 deferrals and zero placements per ten seconds, for as long as
         * anyone watched.
         *
         * <p>Deferring is only meaningful because a LATER COMMIT may open the walk graph — {@link
         * #releaseRouteDeferrals} is called from nowhere else. So while no commit has happened, re-solving a cell
         * whose route just failed cannot produce a reachable answer: the body has not moved and the world has not
         * changed. Parking states exactly that, and a commit unparks everything.
         *
         * <p>It costs no solution. Every stance parked here is offered again the moment anything commits, and the
         * bound it buys is hard: at most one deferral per remaining cell between commits, instead of one per triple.
         */
        boolean deferRoute(PlacementSolution solution) {
            Veto route = vetoOf(solution.cell(), solution.stance(), solution.face());
            if (!this.routeDeferrals.add(route)) {
                return false;
            }
            this.routeParked.add(PlacementGeometry.positionKey(solution.cell()));
            return true;
        }

        /** Does the current predicted world already know this exact placement stance has no mutation-free route? */
        boolean routeDeferred(PlacementSolution solution) {
            return solution != null && this.routeDeferrals.contains(
                    vetoOf(solution.cell(), solution.stance(), solution.face()));
        }

        /** Is this cell waiting for a commit to change the walk graph? See {@link #deferRoute}. */
        boolean routeParked(BlockPos cell) {
            return !this.routeParked.isEmpty() && this.routeParked.contains(PlacementGeometry.positionKey(cell));
        }

        private final LongOpenHashSet routeParked = new LongOpenHashSet();

        /**
         * A committed action changed both the walk graph and the body's anchor. Re-open every route-only decision and
         * force its cell through the next incremental solve; permanent placement vetoes deliberately remain.
         */
        /** @return the stances of the released deferrals, which are re-offered by name because a deferral is cleared
         *          by ANY commit rather than by one within a radius. Never null; empty when nothing was deferred. */
        List<BlockPos> releaseRouteDeferrals(LongOpenHashSet dirty) {
            if (this.routeDeferrals.isEmpty()) {
                return List.of();
            }
            List<BlockPos> releasedStances = new ArrayList<>(this.routeDeferrals.size());
            for (Veto deferred : this.routeDeferrals) {
                BlockPos cell = BlockPos.of(deferred.cell());
                this.invalidate(cell);
                dirty.add(deferred.cell());
                if (deferred.stance() != Long.MIN_VALUE) {
                    releasedStances.add(BlockPos.of(deferred.stance()));
                }
            }
            this.routeDeferrals.clear();
            this.routeParked.clear();
            return releasedStances;
        }

        /**
         * Where the bot stands after the last committed action — and since 21.1 the ANCHOR of the selection policy
         * rather than merely a distance to measure from.
         *
         * <p>The planner does not plan a walk while any currently plannable cell has a solution from here; only when
         * this stance is empty is a new one chosen, and it is chosen by yield rather than by nearness. Travel is
         * roughly 13 ticks a block and the second largest cost item in a build, and it used to be paid in full for
         * 82.9 % of cells to place exactly one block.
         */
        public BlockPos lastStance() {
            return this.lastStance;
        }

        /**
         * Before the first action, the player may legitimately be outside the bounded proof snapshot. After an action
         * has been frozen, every stance comes from that snapshot and an outside anchor is a broken planner invariant,
         * not permission to call every later route direct.
         */
        boolean isInitialRouteAnchor() {
            return this.order.isEmpty();
        }

        /** @see #lastAim */
        public Vec3 lastAim() {
            return this.lastAim;
        }

        /** The item currently in hand after the last committed action, for the material-affinity tiebreak. Never
         *  changes a dependency — it only breaks ties — which is why it is safe to let it influence the order at
         *  all. */
        public net.minecraft.world.item.Item heldItem() {
            return this.heldItem;
        }

        public int currentLayer() {
            return this.currentLayer;
        }

        /** The cached best solution for a cell, as recorded in the dependency index. Empty when the cell has not
         *  been solved since the last change to its footprint. */
        public Optional<PlacementSolution> bestSolution(BlockPos cell) {
            return cell == null ? Optional.empty()
                    : Optional.ofNullable(this.best.get(PlacementGeometry.positionKey(cell)));
        }

        /** Record a cell's best solution and index its footprint. One call, because a solution stored without its
         *  index entries is invisible to invariant P and the failure is silent. */
        public void remember(BlockPos cell, PlacementSolution solution, PredictedWorld world) {
            this.best.put(PlacementGeometry.positionKey(cell), solution);
            this.index.put(cell, solution, this.oracle.pose(), world);
        }

        /** Commit an action list: append to the order, advance the world, update the index, the enclosure marking,
         *  the stance and the held item. The single place the forward simulation moves forward. */
        public void commit(PredictedWorld world, List<BuildAction> actions) {
            for (BuildAction action : actions) {
                this.order.add(action);
                // Exhaustive over the sealed interface on purpose: a ninth action type added without a branch here
                // would otherwise advance the order without advancing the world, and every later solve would be
                // proven against a world that is missing it.
                switch (action) {
                    case BuildAction.Place place -> {
                        fill(world, place.cell(), place.solution().predicted());
                        fillSecondaryHalf(world, place.cell(), place.solution().predicted());
                        completeChestPair(world, place.cell(), place.solution().predicted());
                        this.lastStance = place.solution().stance();
                        this.lastAim = place.solution().aimPoint();
                        this.heldItem = place.solution().item();
                    }
                    // The same world change as a PLACE, and one extra fact: the body ends the action standing ON the
                    // block it just placed rather than beside it, because that is where a jump lands. Recording the
                    // stance it started from would send the next placement's walk-cost comparison to the wrong place.
                    case BuildAction.JumpPlace jump -> {
                        fill(world, jump.cell(), jump.solution().predicted());
                        fillSecondaryHalf(world, jump.cell(), jump.solution().predicted());
                        completeChestPair(world, jump.cell(), jump.solution().predicted());
                        this.lastStance = jump.cell().above();
                        this.lastAim = jump.solution().aimPoint();
                        this.heldItem = jump.solution().item();
                    }
                    case BuildAction.PlaceScaffold placed -> {
                        fill(world, placed.cell(), placed.solution().predicted());
                        // The ledger is booked HERE and nowhere else. Exactly one owner per piece of state (E-C):
                        // the scaffold planner proves the helper block, the commit records it, and a second booking
                        // site is how a leftover helper block ends up in the finished world with nothing to blame.
                        this.ledger.place(placed.cell(), placed.serves(), occupiedSchematicLayer(placed.cell()));
                        this.lastStance = placed.solution().stance();
                        this.heldItem = placed.solution().item();
                    }
                    case BuildAction.Break broken -> {
                        vacate(world, broken.cell());
                        this.lastStance = broken.stance();
                        // The pickaxe, not a material: leaving the previous material here would let the affinity
                        // tiebreak group cells around an item the bot is not actually holding any more.
                        this.heldItem = null;
                    }
                    case BuildAction.RemoveScaffold removed -> {
                        vacate(world, removed.cell());
                        this.ledger.remove(removed.cell());
                        this.lastStance = removed.stance();
                        this.heldItem = null;
                    }
                    case BuildAction.Interact interact -> {
                        // A right-click that steps a repeater changes the state the next satisfied() check reads. Not
                        // applying it would leave the cell looking unfinished to the layer gate for ever.
                        world.apply(interact.cell(), interact.target());
                        this.lastStance = interact.stance();
                        this.heldItem = null;
                    }
                    case BuildAction.FillFluid fluid -> {
                        fill(world, fluid.cell(), fluid.expected());
                        this.lastStance = fluid.stance();
                        this.heldItem = this.inventory.instabuild()
                                ? fluid.bucket() : FluidPlan.emptied(fluid.bucket());
                    }
                    case BuildAction.WriteSign ignored -> {
                        // Text, not geometry: nothing in the world changes and the stance is the placement's own.
                    }
                    case BuildAction.SwapHotbar swap -> this.heldItem = swap.item();
                }
            }
        }

        /** The order so far, in execution sequence. */
        public List<BuildAction> order() {
            return Collections.unmodifiableList(this.order);
        }

        /** Blockers accumulated so far. Every one of them, not the first: a report that names one cell teaches you
         *  one cell per run, and the size of the problem is the thing worth learning. */
        public List<PlanReport.Blocker> blockers() {
            return Collections.unmodifiableList(this.blockers);
        }

        public void blocked(PlanReport.Blocker blocker) {
            this.blockers.add(blocker);
        }

        /** Cells that solved only under {@link SolveBudget#minMargin()} — reported with their numbers rather than
         *  taken silently, because a margin of 0.02 blocks is a coin flip and the report is where a coin flip is
         *  declared. */
        public List<PlanReport.TightCell> tight() {
            return Collections.unmodifiableList(this.tight);
        }

        /** Rays spent across the whole dry run, for the cost model and for {@link BuildPlan.Counts#raysCast}. */
        public long raysCast() {
            return this.raysCast;
        }

        /** Advance to the next layer: assert the ledger empty, clear the index, record the layer summary. */
        public void enterLayer(int layer) {
            finishLayer();
            this.currentLayer = layer;
            this.layerOpen = true;
        }

        /** Per-layer cell and helper-block counts, in build order. */
        public List<PlanReport.LayerSummary> layerSummaries() {
            return Collections.unmodifiableList(this.layerSummaries);
        }

        // --------------------------------------------------------------- the planner's own handles
        //
        // Private, and reachable anyway: OrderPlanner is the enclosing class, so it can use these without any of them
        // being public API. That is the point -- the layer loop needs eight more verbs than the outside world does,
        // and every one of them exposed would be a promise to a caller that has no business making these calls.

        /**
         * Close the open layer: record its summary, assert the ledger empty, clear the dependency index.
         *
         * <p>The layer bound of 5.1 in one method. A ledger that is NOT empty here is a violated bound, and the
         * honest response is a named blocker per leftover helper block rather than an exception that loses the other
         * fourteen thousand cells of a report: {@code Ledger.closeLayer} refuses a non-empty ledger by design, so the
         * leftovers are named and dropped from it first.
         */
        private void finishLayer() {
            if (!this.layerOpen) {
                return;
            }
            this.layerSummaries.add(new PlanReport.LayerSummary(this.currentLayer,
                    this.view.cellsInLayer(this.currentLayer).size(), this.ledger.placedTotal(), this.ledger.notes()));
            // Iterated over a COPY, because the body removes from the very ledger being walked. Ledger.open is
            // documented as "still standing, in placement order" and the natural implementation of that is an
            // unmodifiable view of the internal list, which throws ConcurrentModificationException here -- at the layer
            // bound, in the one code path that only runs when the build is already going wrong.
            for (BlockPos leftover : List.copyOf(this.ledger.open())) {
                this.blockers.add(new PlanReport.Blocker(leftover, null, null, new int[0], 0, List.of(),
                        "a helper block was still standing at the layer bound of layer " + this.currentLayer
                                + " — the layer cannot close and the bench audit counts it as a leftover"));
                this.ledger.remove(leftover);
            }
            this.ledger.closeLayer();
            // Nothing from layer L may constrain L+1: every cell in L is finished or reported, so every edge in the
            // index is stale by construction and a stale edge makes invariant P fail closed.
            this.index.clear();
            this.layerOpen = false;
        }

        /** Drop a cell's cached solution and its index edges. Called when the world changed underneath it, and when a
         *  re-solve found nothing — a cell with no solution whose old edges survive vetoes placements on the strength
         *  of a stance it no longer has. */
        private void invalidate(BlockPos cell) {
            this.best.remove(PlacementGeometry.positionKey(cell));
            this.index.forget(cell);
        }

        void addRays(long rays) {
            this.raysCast += rays;
        }

        private int vetoCount() {
            return this.vetoes.size();
        }

        private int decisionCount() {
            return this.vetoes.size() + this.routeDeferrals.size();
        }

        /** Record a cell that solved only below the acceptance margin, once. */
        private void recordTight(BlockPos cell, BlockState desired, double margin) {
            if (this.tightSeen.add(PlacementGeometry.positionKey(cell))) {
                this.tight.add(new PlanReport.TightCell(cell, desired, margin));
            }
        }

        private void note(String limitation) {
            if (!this.limitations.contains(limitation)) {
                this.limitations.add(limitation);
            }
        }

        /** Place a state and keep invariant E's marking and the invalidation list in step. */
        private void fill(PredictedWorld world, BlockPos pos, BlockState state) {
            world.apply(pos, state);
            this.enclosure.apply(world, pos);
            this.invalidate(pos);
            this.touched.add(pos);
        }

        /** Empty a cell — a break, a scaffold removal, or a block that popped off in a cascade. */
        private void vacate(PredictedWorld world, BlockPos pos) {
            world.apply(pos, Blocks.AIR.defaultBlockState());
            this.enclosure.free(world, pos);
            this.invalidate(pos);
            this.touched.add(pos);
        }

        /**
         * Fill in the half vanilla places for free.
         *
         * <p>The second cell is a physical consequence of the placement, not a property of the schematic mask. A
         * sparse schematic may deliberately contain only a door's LOWER cell; Vanilla still creates the UPPER cell,
         * and every later ray, stance and path proof must see it. Looking up a secondary cell in {@link SchematicView}
         * therefore under-models exactly those sparse schematics and freezes actions through space that will be
         * occupied at execution time.
         *
         * <p>Derive both position and state from what the click is predicted to land. This covers doors and all other
         * lower/upper double-block placements through {@link BlockStateProperties#DOUBLE_BLOCK_HALF}, plus a bed's
         * FOOT-to-HEAD placement. When the schematic explicitly contains the secondary cell the derived state must
         * still be used: it is what Vanilla creates, so an inconsistent schematic remains visibly unsatisfied instead
         * of being made true by the simulator.
         */
        private void fillSecondaryHalf(PredictedWorld world, BlockPos primary, BlockState landed) {
            PlacementConsequences.Secondary half = PlacementConsequences.secondaryOf(primary, landed);
            if (half != null) {
                fill(world, half.cell(), half.state());
            }
        }

        /**
         * The half vanilla RE-writes for free.
         *
         * <p>{@code ChestBlock.updateShape} turns the SINGLE chest already standing beside this one into its mirror the
         * moment the second half lands. {@link PredictedWorld#apply} is a raw write with no neighbour propagation, so
         * the forward simulation has to do it here — otherwise the first half stays SINGLE in the planner's own world
         * forever, {@link SchematicView#satisfied} never becomes true, the cell never leaves {@code remaining}, and the
         * planner re-solves and possibly re-BREAKS a chest that is already correct.
         *
         * <p>Through {@link #fill} and never {@link PredictedWorld#apply} directly, so {@code enclosure},
         * {@code invalidate} and {@code touched} stay in step; {@code touched} then feeds {@code drainTouched}, which
         * re-solves the neighbourhood. That is how the ring cascades.
         */
        private void completeChestPair(PredictedWorld world, BlockPos placed, BlockState landed) {
            BlockPos partner = PlacementFamilies.chestPairPartner(landed, placed);
            if (partner == null || !world.inBounds(partner.getX(), partner.getY(), partner.getZ())) {
                return;
            }
            BlockState completed = PlacementFamilies.chestPairCompletion(landed, world.get(partner));
            if (completed != null) {
                fill(world, partner, completed);
            }
        }

        /** Which layer's schematic cell a helper block temporarily occupies, for the report line. */
        private int occupiedSchematicLayer(BlockPos scaffold) {
            return this.view.wantsBlock(scaffold)
                    ? this.view.layerOf(scaffold) : PlanReport.ScaffoldNote.NO_SCHEMATIC_CELL;
        }

        private static Veto vetoOf(BlockPos cell, BlockPos stance, Direction face) {
            return new Veto(cell == null ? Long.MIN_VALUE : PlacementGeometry.positionKey(cell),
                    stance == null ? Long.MIN_VALUE : PlacementGeometry.positionKey(stance),
                    face == null ? -1 : face.ordinal());
        }
    }

    /** What one layer's loop produced. Returned rather than folded straight into the state so that
     *  {@link #planLayer} is testable on a single layer without running a whole build. */
    public record LayerResult(int layer, List<BuildAction> actions, List<BlockPos> placed,
                              List<PlanReport.Blocker> blockers, int scaffoldBlocks) {

        public LayerResult {
            actions = List.copyOf(actions);
            placed = List.copyOf(placed);
            blockers = List.copyOf(blockers);
        }

        /** A layer closes cleanly when nothing in it was left unexplained. An INCOMPLETE layer does not stop the
         *  dry run — the remaining layers are still planned, because the report's job is to name every blocker in
         *  one pass and stopping at the first one is the behaviour that made V2's failures cost a run each. */
        public boolean complete() {
            return this.blockers.isEmpty();
        }
    }

    private final PlacementOracle oracle;
    private final ScaffoldPlanner scaffold;

    /**
     * @param oracle   already carrying its {@link V3Settings} snapshot and {@link PlayerPose}
     * @param scaffold the helper-block planner, sharing the same oracle so that a helper block placement is proven
     *                 by exactly the same geometry as a schematic placement — two placement proofs that could
     *                 disagree is the seam a wrongly rotated block slips through
     */
    public OrderPlanner(PlacementOracle oracle, ScaffoldPlanner scaffold) {
        this.oracle = oracle;
        this.scaffold = scaffold;
    }

    /**
     * Where a running plan says how far it has got. Silence by default, so tests and the report path are unchanged.
     *
     * <p>It exists because a plan over a real schematic is minutes of one thread that emits nothing, which in a log
     * is indistinguishable from a hang — and was taken for one. A layer that takes five minutes should say so while
     * it is taking them, not afterwards and not never.
     */
    public OrderPlanner onProgress(Consumer<String> progress) {
        this.progress = progress == null ? line -> { } : progress;
        return this;
    }

    private Consumer<String> progress = line -> { };

    /**
     * Plan at most this many layers and leave the rest for the next dry run.
     *
     * <p>The whole basalt plan is tens of minutes of one thread and the bot cannot move until it finishes, which is a
     * long time to watch a body stand still. The bottom layer alone plans in six seconds and is 936 blocks of work —
     * minutes of building — so planning the next layer during that is time the build was going to spend anyway.
     *
     * <p>It needs no new resume machinery, which is the reason it is only a counter: {@code todo} is defined as every
     * cell that is not already correct, so the next dry run re-derives the remainder from the world the bot has by
     * then actually built. A truncated plan is not an incomplete one — see {@link #truncated()}.
     */
    public OrderPlanner planAtMostLayers(int layers) {
        this.layerBudget = layers <= 0 ? Integer.MAX_VALUE : layers;
        return this;
    }

    /** Did {@link #plan} stop because of {@link #planAtMostLayers} rather than because the schematic ran out? */
    public boolean truncated() {
        return this.truncated;
    }

    private int layerBudget = Integer.MAX_VALUE;
    private boolean truncated;

    /**
     * One line per finished layer: the size of the problem, what it cost, and where the cost went.
     *
     * <p>The three quantities are the ones that would have answered today's question on sight. Milliseconds per cell
     * is the number to compare across layers, because a planner whose cost is linear in the layer holds it steady and
     * one that is quadratic does not. The access and flood columns name the suspect directly.
     */
    /**
     * A line every {@link #PROGRESS_SLICE} cells INSIDE a layer, which is the measurement a layer-end line cannot make.
     *
     * <p>The bottom basalt layer plans in six seconds and the one above it did not finish in five minutes, and from
     * the outside those two are the same silence. Reporting per slice separates the two questions that matter: a
     * planner whose cost is linear holds the seconds-per-slice steady, and one that is quadratic shows it climbing
     * slice by slice while it is still climbing — early enough to be worth watching.
     */
    private static final int PROGRESS_SLICE = 200;

    /**
     * And a line at least this often even if the cell count has not moved.
     *
     * <p>The cell trigger alone reports nothing at all in the case that most needs reporting. The bottom basalt layer
     * plans 200 cells a second and the layer above it did not reach 200 cells in four minutes, so a purely cell-based
     * slice went completely silent exactly where the cost was — and silence is the one thing this must never emit.
     */
    private static final long PROGRESS_INTERVAL_NANOS = 10_000_000_000L;

    private long sliceStarted;
    private long[] sliceCensus;
    private int sliceVetoes;
    private int sliceDecisions;

    private void reportSlice(int layer, int done, int since, int total, PlannerState state) {
        long[] now = this.scaffold.costCensus();
        double seconds = (System.nanoTime() - this.sliceStarted) / 1_000_000_000.0D;
        int vetoes = state.vetoCount() - this.sliceVetoes;
        // The two ways an iteration can count as progress without placing anything. Separating them is the whole
        // question when a layer stops advancing: vetoes say the CLICK keeps being refused, deferrals say the WALK
        // does — and those are opposite repairs. Guessing which cost an afternoon.
        int deferrals = (state.decisionCount() - state.vetoCount()) - (this.sliceDecisions - this.sliceVetoes);
        this.progress.accept(String.format(Locale.ROOT,
                "v3 plan: layer %d — %d/%d cells, last %d in %.1fs (%.0f ms/cell); access %.1fs; "
                        + "floods %d over %d nodes %.1fs; vetoes %d, route deferrals %d",
                layer, done, total, since, seconds, since == 0 ? 0.0D : seconds * 1000.0D / since,
                (now[1] - this.sliceCensus[1]) / 1_000_000_000.0D,
                now[2] - this.sliceCensus[2], now[3] - this.sliceCensus[3],
                (now[4] - this.sliceCensus[4]) / 1_000_000_000.0D,
                vetoes, deferrals));
        this.sliceStarted = System.nanoTime();
        this.sliceCensus = now;
        this.sliceVetoes = state.vetoCount();
        this.sliceDecisions = state.decisionCount();
    }

    private void reportLayer(int layer, int cells, long layerStarted, long planStarted, long[] before) {
        long[] after = this.scaffold.costCensus();
        double layerMs = (System.nanoTime() - layerStarted) / 1_000_000.0D;
        this.progress.accept(String.format(Locale.ROOT,
                "v3 plan: layer %d — %d cells in %.1fs (%.1f ms/cell), total %.1fs; "
                        + "access %d calls %.1fs; floods %d over %d nodes %.1fs",
                layer, cells, layerMs / 1000.0D, cells == 0 ? 0.0D : layerMs / cells,
                (System.nanoTime() - planStarted) / 1_000_000_000.0D,
                after[0] - before[0], (after[1] - before[1]) / 1_000_000_000.0D,
                after[2] - before[2], after[3] - before[3], (after[4] - before[4]) / 1_000_000_000.0D));
    }

    // ------------------------------------------------------------------- the entry point

    /**
     * The dry run. Plans the whole build to completion and returns the verdict a human reads before deciding to let
     * three hours of walking start.
     *
     * <p>Returns a {@link PlanReport} rather than a {@link BuildPlan} in both outcomes, and the INCOMPLETE case
     * carries a plan too: the prefix that WOULD run is itself information, and so is its length. Every blocker is in
     * the report, not the first one.
     *
     * <p>{@code world} is mutated — it is the forward simulation's working copy and ends holding the finished build.
     * Callers that need the snapshot afterwards call {@link PredictedWorld#clearDeltas}. Stated here because a
     * planner that quietly consumed its input world would be found out by the second caller, not the first.
     *
     * @param view      the schematic, already through the user transforms, in absolute coordinates
     * @param world     the snapshot to plan against; mutated, see above
     * @param settings  the frozen acceptance rules
     * @param budget    what each solve may spend and the margin it judges by
     * @param inventory what the bot is carrying; drives the hotbar schedule and the capacity check
     * @param botStart  where the bot stands when the build begins — the seed of invariant E's component and the
     *                  first value of {@link PlannerState#lastStance}
     */
    public PlanReport plan(SchematicView view, PredictedWorld world, V3Settings settings, SolveBudget budget,
                           HotbarSchedule.InventorySnapshot inventory, BlockPos botStart) {
        return this.plan(view, world, settings, budget, inventory, botStart, PlacementOracle.StanceFilter.ALL);
    }

    /**
     * The same dry run, plus a veto that outlives the plan.
     *
     * <p>This is the second re-entry of decision E-D and the reason "re-plan on rejection" terminates instead of
     * looping at click cadence: a triple the server refused is refused for every later plan too, and a cell refused
     * {@link EvidenceJournal#REJECTIONS_BEFORE_BLOCKED} structurally different ways allows nothing at all — so it comes
     * back through the ordinary blocker channel rather than as a fourth click nobody learns anything from.
     *
     * @param external the executor's {@link EvidenceJournal}, or {@link PlacementOracle.StanceFilter#ALL}
     */
    public PlanReport plan(SchematicView view, PredictedWorld world, V3Settings settings, SolveBudget budget,
                           HotbarSchedule.InventorySnapshot inventory, BlockPos botStart,
                           PlacementOracle.StanceFilter external) {
        PlannerState state = new PlannerState(this.oracle, view, settings, budget, inventory, botStart, external);
        Comparator<BlockPos> layerOrder = UpwardLook.layerOrder(view);
        long planStarted = System.nanoTime();
        this.truncated = false;
        int layersPlanned = 0;

        for (int layer : view.layers()) {
            if (layersPlanned >= this.layerBudget) {
                // Deferred, NOT blocked. Nothing about these layers has been judged: they are simply not this dry
                // run's business, and the next one derives them from the world this plan will have built.
                this.truncated = true;
                break;
            }
            long layerStarted = System.nanoTime();
            long[] censusBefore = this.scaffold.costCensus();
            int blockersBefore = state.blockers.size();
            state.enterLayer(layer);
            List<BlockPos> todo = new ArrayList<>();
            for (BlockPos cell : view.primaryCellsInLayer(layer)) {
                // "todo is everything that is not already correct" is also the whole of the resume story (Z1): a
                // restart after a disconnect re-derives the remainder instead of restoring a saved plan that the
                // world may have moved out from under.
                if (!view.satisfied(world, settings, cell)) {
                    todo.add(cell);
                }
            }
            // The one family-specific rule in the planner, and it is a pre-sort rather than a term in the selection
            // policy: upward-look cells clip a helper block above themselves, so they need no side neighbour and can
            // go first, which leaves their head room free by construction (5.7.5).
            // A layer the world already satisfies costs nothing and must not spend the budget, or a resumed build
            // would hand the body back its legs once per already-finished layer and re-snapshot the world each time.
            if (!todo.isEmpty()) {
                layersPlanned++;
            }
            todo.sort(layerOrder);
            planLayer(world, state, layer, todo);
            // The layer gate. Removals are emitted here, for the whole layer at once, so that "reverse placement
            // order" is decided in one place rather than per escalation.
            commitAll(world, state, this.scaffold.closeLayer(world, state, layer));
            reportLayer(layer, todo.size(), layerStarted, planStarted, censusBefore);
            if (state.blockers.size() > blockersBefore) {
                // The hard layer rule is an outer-loop bound, not merely an executor assertion. Once a cell in L is
                // unproven, planning L+1 would prove actions against a world in which L is knowingly incomplete and
                // would make the emitted prefix violate the very order it claims to certify.
                break;
            }
        }
        state.finishLayer();

        return report(view, world, state, inventory);
    }

    // ------------------------------------------------------------------- the three testable pieces

    /**
     * One layer, bottom of the loop to the layer gate: frontier, selection, invariants, scaffold escalation,
     * removals.
     *
     * <p>Separate from {@link #plan} because a layer is the largest unit that can be set up by hand in a test. The
     * piston row is one layer, the enclosure avalanche was one layer, and every interesting property of this design
     * is a property of a layer rather than of a build.
     *
     * @param todo the layer's cells, already {@link UpwardLook#layerOrder}-sorted and already minus the ones the
     *             world satisfies
     */
    public LayerResult planLayer(PredictedWorld world, PlannerState state, int layer, List<BlockPos> todo) {
        List<BlockPos> remaining = new ArrayList<>(todo);
        List<BlockPos> placed = new ArrayList<>();
        List<PlanReport.Blocker> blocked = new ArrayList<>();
        int firstAction = state.order.size();
        int scaffoldBefore = state.ledger.placedTotal();
        ArrayDeque<BlockPos> elevatedStances = new ArrayDeque<>();
        // CURRENTLY QUEUED, not ever seen — see enqueueElevatedStance for why the difference is the whole design.
        LongOpenHashSet queuedElevatedStances = new LongOpenHashSet();
        seedElevatedStances(world, state, layer, elevatedStances, queuedElevatedStances);
        // Hoisted: rearmElevatedStances walks it after every commit, and the view rebuilds the list on each call.
        List<BlockPos> layerCells = state.view().primaryCellsInLayer(layer);

        // Everything starts dirty: nothing in this layer has been solved against the world as the previous layer left
        // it, and a cached solution from an earlier layer would be a proof against a world that no longer exists.
        LongOpenHashSet dirty = new LongOpenHashSet();
        for (BlockPos cell : remaining) {
            dirty.add(PlacementGeometry.positionKey(cell));
        }

        boolean emptinessConfirmed = false;
        int previousTodo = Integer.MAX_VALUE;
        int previousDecisions = -1;
        this.sliceStarted = System.nanoTime();
        this.sliceCensus = this.scaffold.costCensus();
        this.sliceVetoes = state.vetoCount();
        this.sliceDecisions = state.decisionCount();
        int reportedAt = 0;

        while (!remaining.isEmpty()) {
            // The termination proof, asserted rather than trusted. Every iteration must finish a cell, permanently
            // veto a bad click, or temporarily defer an unreachable route. A route deferral is cleared only by a
            // committed mutation, which also shrinks the work set, so counting it here preserves the proof without
            // laundering a no-path into permanent placement evidence.
            if (remaining.size() >= previousTodo && state.decisionCount() <= previousDecisions) {
                throw new IllegalStateException("planner made no progress in layer " + layer + ": "
                        + remaining.size() + " cells left, " + state.vetoCount() + " vetoes and "
                        + (state.decisionCount() - state.vetoCount()) + " route deferrals, first cell "
                        + BuildAction.describePos(remaining.get(0)));
            }
            previousTodo = remaining.size();
            previousDecisions = state.decisionCount();

            if (placed.size() - reportedAt >= PROGRESS_SLICE
                    || System.nanoTime() - this.sliceStarted >= PROGRESS_INTERVAL_NANOS) {
                reportSlice(layer, placed.size(), placed.size() - reportedAt, todo.size(), state);
                reportedAt = placed.size();
            }

            refresh(world, state, remaining, dirty);

            BlockPos currentStance = state.lastStance();
            boolean ownsUpperSurface = currentStance != null && currentStance.getY() == layer + 1;

            // Below the layer, promotion owns the first choice: climb while the natural stair is still open instead
            // of exhausting the low stance and sealing it behind the body. Once promoted, the exact stance owns the
            // first choice so the bot walks the surface rather than hopping between equivalent positions.
            PlacementSolution candidate = ownsUpperSurface
                    ? first(solvableFrom(world, state, remaining, currentStance))
                    : elevatedCandidate(world, state, layer, remaining, elevatedStances, queuedElevatedStances);

            // If the exact upper stance is exhausted, another useful stance on the SAME work surface still outranks
            // every low solution. This is the part a score bonus cannot guarantee: the unrestricted oracle normally
            // caches one nearby low solution for an orientation-free cube, so the upper stance must be asked
            // explicitly before the ordinary frontier is allowed to make the body descend.
            if (candidate == null && ownsUpperSurface) {
                candidate = elevatedCandidate(world, state, layer, remaining, elevatedStances, queuedElevatedStances);
            }

            // Before promotion, or when no upper stance can serve anything, retain the ordinary stance anchor.
            if (candidate == null && !ownsUpperSurface) {
                candidate = first(solvableFrom(world, state, remaining, currentStance));
            }

            if (candidate == null) {
                List<PlacementSolution> frontier = cachedFrontier(state, remaining);
                if (frontier.isEmpty() && !emptinessConfirmed) {
                    // Escalating to scaffold costs two extra actions and a removal obligation, so "the frontier is
                    // empty" is confirmed against a full re-solve rather than against the incremental bookkeeping.
                    // Once per escalation, not once per iteration.
                    frontier = frontier(world, state, remaining);
                    emptinessConfirmed = true;
                }
                candidate = pick(world, state, remaining, frontier).orElse(null);
            }

            if (candidate != null) {
                emptinessConfirmed = false;
                Optional<Invariants.Violation> violation = Invariants.explain(world, state, candidate);
                if (violation.isPresent()) {
                    // A guard fired: the candidate is discarded and the next entry of the frontier is tried. The veto
                    // is what stops the next solve from proposing the same triple, and it is also what makes this
                    // iteration count as progress.
                    state.reject(candidate.cell(), candidate.stance(), candidate.face());
                    state.invalidate(candidate.cell());
                    dirty.add(PlacementGeometry.positionKey(candidate.cell()));
                    if (candidate.stance().getY() == layer + 1) {
                        enqueueElevatedStance(world, candidate.stance(), elevatedStances, queuedElevatedStances,
                                false);
                    }
                    continue;
                }
                List<BuildAction> candidateActions = new ArrayList<>();
                // Hoisting the body's walk component out of here and reusing it across candidates was tried and
                // MEASURED WORSE: 936 floods over 5.8M nodes against 246 over 1.5M, and the bottom basalt layer went
                // from 6.2s to 15.4s. The per-candidate call is cheap precisely because it usually does not flood at
                // all — its guards answer first — so precomputing the component pays for a flood that was not
                // happening. The floods that do cost sit inside the helper search, from other starts and through the
                // staged world, and no amount of caching the body's own component touches them.
                ScaffoldPlanner.Access access = this.scaffold.accessToStance(world, state, candidate);
                switch (access.kind()) {
                    case UNREACHABLE -> {
                        // No click failed. The walk graph as it stands cannot reach this triple, so let another cell
                        // mutate the world and retry it afterwards. The route journal is separate from the permanent
                        // evidence journal precisely so this is a deferral rather than a lifetime veto.
                        if (!state.deferRoute(candidate)) {
                            throw new IllegalStateException("duplicate route deferral for "
                                    + BuildAction.describePos(candidate.cell()) + " from "
                                    + BuildAction.describePos(candidate.stance()));
                        }
                        // Deliberately NOT dirtied. The cell is parked until something commits, and re-solving it
                        // before then is what the enumeration was made of: the next-best stance is in the same
                        // unchanged walk graph as the one that just failed. releaseRouteDeferrals dirties it again.
                        emptinessConfirmed = false;
                        continue;
                    }
                    case DIRECT -> {
                        candidate = access.target();
                        candidateActions.addAll(actionsFor(world, state, candidate));
                    }
                    case VIA_SCAFFOLD -> {
                        candidate = access.target();
                        candidateActions.add(new BuildAction.PlaceScaffold(access.scaffold(), candidate.cell(), true,
                                BuildAction.UNASSIGNED_SLOT, layer));
                        if (access.cleanup() != null) {
                            // The cleanup stance is reachable only after this stair stands, but belongs to the target's
                            // helper-free component. Travel there, take the stair back out, then execute the target in
                            // precisely the original world against which its click was proved.
                            candidateActions.add(access.cleanup());
                            candidateActions.addAll(actionsFor(world, state, candidate));
                        } else {
                            // No early cleanup was provable. This helper remains ledger-owned, so compile the target
                            // against the same staged world execution will meet and let the hard layer gate remove it.
                            ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(world);
                            try {
                                scratch.apply(access.scaffold().cell(), access.scaffold().predicted());
                                candidateActions.addAll(actionsFor(world, state, candidate));
                            } finally {
                                scratch.restore();
                            }
                        }
                    }
                }
                commitAll(world, state, candidateActions);
                placed.add(candidate.cell());
                remaining.remove(candidate.cell());
                List<BlockPos> released = state.releaseRouteDeferrals(dirty);
                rememberElevatedStance(world, candidate.cell().above(), elevatedStances, queuedElevatedStances);
                // Ahead of drainTouched, which is what consumes state.touched.
                rearmElevatedStances(world, layerCells, state.touched, released, elevatedStances,
                        queuedElevatedStances);
                drainTouched(state, remaining, dirty);
                continue;
            }

            Optional<ScaffoldPlanner.Escalation> escalation = escalate(world, state, remaining);
            if (escalation.isPresent()) {
                ScaffoldPlanner.Escalation unlocked = escalation.get();
                commitAll(world, state, unlocked.actions());
                placed.add(unlocked.cell());
                remaining.remove(unlocked.cell());
                List<BlockPos> released = state.releaseRouteDeferrals(dirty);
                rememberElevatedStance(world, unlocked.cell().above(), elevatedStances, queuedElevatedStances);
                // Ahead of drainTouched, which is what consumes state.touched.
                rearmElevatedStances(world, layerCells, state.touched, released, elevatedStances,
                        queuedElevatedStances);
                drainTouched(state, remaining, dirty);
                emptinessConfirmed = false;
                continue;
            }

            // Nothing in this layer is solvable and scaffold unlocks nothing either. Every remaining cell is named,
            // not the first one: plan 5.7.4 is explicit that one run must teach the size of the problem, and V2's
            // habit of stopping at the first failure is why each of its failures cost a whole run.
            for (BlockPos cell : remaining) {
                PlanReport.Blocker blocker = explainBlocked(world, state, cell);
                state.blocked(blocker);
                blocked.add(blocker);
            }
            remaining.clear();
        }

        List<BuildAction> emitted = new ArrayList<>(state.order.subList(firstAction, state.order.size()));
        return new LayerResult(layer, emitted, placed, blocked, state.ledger.placedTotal() - scaffoldBefore);
    }

    /**
     * Seed the upper work surface with blocks that were already correct when this plan began.
     *
     * <p>Resume is the load-bearing case: after a disconnect the current layer may be almost complete, so looking
     * only at blocks emitted by this invocation would forget the natural stair the world already offers. Nearest
     * first keeps the first promotion local; the position key makes the order total and reproducible.
     */
    private static void seedElevatedStances(PredictedWorld world, PlannerState state, int layer,
                                            ArrayDeque<BlockPos> pending, LongOpenHashSet known) {
        List<BlockPos> seeded = new ArrayList<>();
        for (BlockPos cell : state.view().primaryCellsInLayer(layer)) {
            BlockPos stance = cell.above();
            long key = PlacementGeometry.positionKey(stance);
            if (world.isStandable(stance.getX(), stance.getY(), stance.getZ()) && known.add(key)) {
                seeded.add(stance);
            }
        }
        BlockPos from = state.lastStance();
        seeded.sort(Comparator.comparingLong((BlockPos stance) -> distanceSquared(from, stance))
                .thenComparingLong(PlacementGeometry::positionKey));
        pending.addAll(seeded);
    }

    /** Add the upper stance a just-committed block created, newest first because it is normally nearest the body. */
    private static void rememberElevatedStance(PredictedWorld world, BlockPos stance,
                                               ArrayDeque<BlockPos> pending, LongOpenHashSet queued) {
        enqueueElevatedStance(world, stance, pending, queued, true);
    }

    /**
     * Offer one stance to {@link #elevatedCandidate}, if it is standable and not already waiting in the queue.
     *
     * <p>{@code queued} means CURRENTLY IN THE QUEUE, not EVER SEEN. That distinction is the whole of this design: a
     * stance that was tried and found useless has to be able to come back when the world that made it useless
     * changes, and a set that remembers it for ever is exactly what forces the caller to rescan everything instead.
     */
    private static boolean enqueueElevatedStance(PredictedWorld world, BlockPos stance, ArrayDeque<BlockPos> pending,
                                                 LongOpenHashSet queued, boolean nearest) {
        if (!world.isStandable(stance.getX(), stance.getY(), stance.getZ())
                || !queued.add(PlacementGeometry.positionKey(stance))) {
            return false;
        }
        if (nearest) {
            pending.addFirst(stance);
        } else {
            pending.addLast(stance);
        }
        return true;
    }

    /**
     * Re-offer the upper stances a commit can have made useful — and only those.
     *
     * <p>A stance is one-shot in the queue, but the world is not: a block placed later can create the click face or
     * the access route that makes a stance rejected earlier useful after all. That fact used to be honoured by
     * rescanning the WHOLE layer before every low candidate, which is correct and unaffordable. It is a full oracle
     * solve from every cell of the layer, once per placed block, so its cost grows with the SQUARE of the layer: one
     * second on the 40-cell ring that vetted it, and on the 936-cell bottom layer of etz-basalt the client sat at
     * 100 % of a core for five and a half minutes without finishing a single layer. No bench could see it, because
     * the largest one is 120 cells.
     *
     * <p>Targeting it costs nothing in strength. A commit changes named positions; a change alters the status of a
     * cell only within {@link #INVALIDATION_RADIUS}; a stance serves a cell only within
     * {@link PlacementOracle#STANCE_WINDOW_RADIUS}. So a stance further than {@link #ELEVATED_REARM_RADIUS} from
     * every touched position cannot have gained a solution, and re-offering the ones inside that radius says exactly
     * what the sweep said. Route deferrals are the one thing not bounded by distance -- ANY commit clears all of
     * them -- so their stances are re-offered by name instead.
     *
     * <p>Called with {@code state.touched} before {@link #drainTouched} consumes it, and with the same layer's cells
     * the seed used, so the set of stances that can ever enter the queue is unchanged: the top of a cell of THIS
     * layer, never arbitrary standable ground at that height.
     */
    private static void rearmElevatedStances(PredictedWorld world, List<BlockPos> layerCells, List<BlockPos> touched,
                                             List<BlockPos> releasedStances, ArrayDeque<BlockPos> pending,
                                             LongOpenHashSet queued) {
        for (BlockPos stance : releasedStances) {
            enqueueElevatedStance(world, stance, pending, queued, false);
        }
        if (touched.isEmpty()) {
            return;
        }
        for (BlockPos cell : layerCells) {
            for (BlockPos position : touched) {
                if (Math.abs(cell.getX() - position.getX()) <= ELEVATED_REARM_RADIUS
                        && Math.abs(cell.getY() - position.getY()) <= ELEVATED_REARM_RADIUS
                        && Math.abs(cell.getZ() - position.getZ()) <= ELEVATED_REARM_RADIUS) {
                    enqueueElevatedStance(world, cell.above(), pending, queued, false);
                    break;
                }
            }
        }
    }

    /**
     * Find the first useful stance on top of the layer while the body is at or below that work surface.
     *
     * <p>The queue is the whole answer. It is seeded with every stance the world already offers, grows by the stance
     * each committed block creates, and is re-armed by {@link #rearmElevatedStances} wherever a commit can have
     * changed what a stance can reach. A stance leaves {@code queued} when it is taken, so it is free to come back.
     */
    private PlacementSolution elevatedCandidate(PredictedWorld world, PlannerState state, int layer,
                                                 List<BlockPos> remaining, ArrayDeque<BlockPos> pending,
                                                 LongOpenHashSet queued) {
        BlockPos from = state.lastStance();
        if (from == null || from.getY() > layer + 1) {
            return null;
        }
        while (!pending.isEmpty()) {
            BlockPos stance = pending.removeFirst();
            queued.remove(PlacementGeometry.positionKey(stance));
            List<PlacementSolution> solutions = solvableFrom(world, state, remaining, stance);
            if (!solutions.isEmpty()) {
                return solutions.get(0);
            }
        }
        return null;
    }

    private static PlacementSolution first(List<PlacementSolution> solutions) {
        return solutions.isEmpty() ? null : solutions.get(0);
    }

    /**
     * The frontier: the cells of {@code todo} that have a solution against the world AS IT NOW STANDS, with margin at
     * or above {@link SolveBudget#minMargin()}, best solution first.
     *
     * <p>This is the whole DEPENDENCY mechanism and it is deliberately dumb — since 21.1 it decides what is DUE
     * rather than what is next, because the hold phase serves a held stance before the frontier is consulted at all.
     * It does not know about pistons, it does not count how many other cells a placement would unlock, and it has no
     * notion of a family. The unlock count
     * was in an earlier draft and was cut as a premature optimisation: the frontier already achieves it exactly and
     * for free, because a piston with no solid side neighbour has no solution and therefore is not IN the frontier,
     * while its neighbours are and go first. It re-enters by itself the moment they stand.
     *
     * <p>What the frontier cannot see is the converse — that a placement takes ANOTHER cell's last solution. That is
     * invariant P's job, and P is a guard rather than a term in this ranking.
     *
     * <p>Solutions found here are remembered through {@link PlannerState#remember}, which is also what fills the
     * dependency index; the index is thus a by-product of work that had to happen anyway.
     *
     * <p>This is the FULL form — every cell of {@code todo} is re-solved. {@link #planLayer} calls it once per layer
     * and once more before each escalation, and maintains the frontier incrementally in between; see the class
     * javadoc for why the literal per-round recomputation is unaffordable.
     */
    public List<PlacementSolution> frontier(PredictedWorld world, PlannerState state, List<BlockPos> todo) {
        List<PlacementSolution> solutions = new ArrayList<>();
        for (BlockPos cell : todo) {
            if (state.routeParked(cell)) {
                continue;
            }
            Optional<PlacementSolution> solved = solve(world, state, cell);
            if (solved.isEmpty()) {
                // The ordinary search has just failed, which for a block whose facing is UP is the expected answer
                // rather than a surprise: every stance it would need is above a layer that does not exist yet. Only
                // now is the jump offered, so it can never outrank a placement a settled body could have made.
                solved = jumpPlace(world, state, cell);
            }
            solved.filter(solution -> !state.routeDeferred(solution)).ifPresent(solutions::add);
        }
        return solutions;
    }

    /**
     * The jump-place fallback, asked only of a cell the ordinary search could not answer.
     *
     * <p>Deliberately last. A cell that a standing body can fill is filled that way; the jump exists for the family
     * that has no such option at all — {@code provenLook == DOWN}, which is pistons, dispensers and droppers at
     * {@code facing=up} and observers at {@code facing=down}. {@link PlacementOracle#solveJumpPlace} refuses
     * everything else, so this call is safe to make for every unsolved cell rather than only for ones guessed to
     * qualify.
     */
    private Optional<PlacementSolution> jumpPlace(PredictedWorld world, PlannerState state, BlockPos cell) {
        BlockState desired = state.view().placementTarget(world, cell);
        if (desired == null) {
            return Optional.empty();
        }
        return state.oracle().solveJumpPlace(world, cell, desired, desired.getBlock().asItem())
                .filter(solution -> !state.routeDeferred(solution));
    }

    /**
     * The selection policy of 5.3, deterministic — and since 21.1, stance-anchored rather than distance-greedy.
     *
     * <p>This method is only reached once the current stance is EMPTY: {@link #planLayer} asks
     * {@link #solvableFrom} first and does not walk while it answers. So the question here is not "which cell next"
     * but <b>which stance next</b>, and the answer is the stance from which the most open cells are solvable — not
     * the nearest one.
     *
     * <ol>
     *   <li><b>The shortlist.</b> Every stance named by a frontier solution is a candidate; they are ordered by how
     *       many frontier cells name them, then by distance from {@link PlannerState#lastStance}, then
     *       lexicographically, and the first {@link #MOVE_SHORTLIST} of them are measured for real. The vote count is
     *       free — it is a histogram over solutions that have already been solved — and it is a proxy rather than the
     *       answer, which is why it only decides WHICH stances get measured.</li>
     *   <li><b>The yield.</b> For each shortlisted stance, {@link #solvableFrom} counts the open cells that genuinely
     *       have a solution from it. Highest yield wins; a tie keeps the earlier shortlist entry, so nearer and
     *       better-voted comes first among equals.</li>
     *   <li><b>A stance the build still wants filled loses to one it does not,</b> whatever the yields. See below —
     *       this is the one term that outranks the yield, and it is a correctness term rather than a cost one.</li>
     *   <li><b>The first cell.</b> The winner's own list is already ordered far to near, so its head is returned and
     *       the rest are placed by the hold phase over the following iterations.</li>
     * </ol>
     *
     * <p><b>Why standing in an open cell is refused while an alternative exists.</b> A stance the schematic still
     * wants a block in is a cell the bot cannot place while it is standing there, so a held visit spends itself
     * consuming exactly that cell's own candidate stances — its neighbours — and ends with the anchor and its last
     * free neighbour able to serve only each other. Invariant P is a one-step check: it sees the second-to-last
     * placement as harmless, because at that moment both cells still have a solution, and by the last one the choice
     * is only which of the two to lose. Measured on a solid 5x5 slab, where every stance inside the footprint is such
     * a cell: 24 of 25 cells without this term, 25 of 25 with it. Plan 21.2 asks for the same thing on separate
     * grounds — a bot standing in the layer it fills refuses its own placements through vanilla's
     * {@code isUnobstructed}.
     *
     * <p>A preference and not a rule: when no shortlisted stance spares the build, the best-yielding one is taken
     * anyway. Somewhere is where the bot has to stand, and refusing to stand anywhere is not an improvement.
     *
     * <p>Why not the nearest stance any more: nearest minimises the cost of ONE step and never forces a stance to be
     * emptied, which is measured in 21.5 as 3 817 walks for 4 623 placements. Maximising the yield optimises the
     * quantity that is actually paid for — the number of walks — and the hold phase is what collects on it.
     *
     * <p><b>The fallback is not decoration.</b> When no shortlisted stance yields anything — which
     * {@link PlacementOracle#solveFrom} can produce where the unrestricted search found its solution through a face
     * the narrowed one gives up on — the old four-term comparison over the frontier decides. The set of plannable
     * cells may not change because the ORDER changed, so a stance-anchored selection that finds nothing must still
     * hand back the cell the distance-greedy one would have taken.
     */
    public Optional<PlacementSolution> pick(PredictedWorld world, PlannerState state, List<BlockPos> remaining,
                                            List<PlacementSolution> frontier) {
        if (frontier.isEmpty()) {
            return Optional.empty();
        }
        LongOpenHashSet open = new LongOpenHashSet(remaining.size());
        for (BlockPos cell : remaining) {
            open.add(PlacementGeometry.positionKey(cell));
        }
        List<PlacementSolution> best = List.of();
        List<PlacementSolution> bestSpare = List.of();
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestSpareScore = Double.NEGATIVE_INFINITY;
        BlockPos from = state.lastStance();
        // Measured once, scored once. The two passes exist because RARITY is a property of the whole shortlist: how
        // many of the measured stances can serve a given cell is only known after every one of them has answered,
        // and it is the number that says which opportunities are about to be lost.
        List<BlockPos> candidates = shortlist(state, frontier);
        List<List<PlacementSolution>> yields = new ArrayList<>(candidates.size());
        Long2IntOpenHashMap servedBy = new Long2IntOpenHashMap();
        for (BlockPos stance : candidates) {
            List<PlacementSolution> measured = solvableFrom(world, state, remaining, stance);
            yields.add(measured);
            for (PlacementSolution solution : measured) {
                servedBy.addTo(PlacementGeometry.positionKey(solution.cell()), 1);
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            BlockPos stance = candidates.get(i);
            List<PlacementSolution> yield = yields.get(i);
            // Yield MINUS what the walk costs, rather than yield alone. Pure yield is what sent the bot thirty-five
            // blocks across the farm for one extra cell: a distant stance offering five beat a neighbouring one
            // offering four, every time, and travel became the largest single term in the whole engine at 4.98 ticks
            // per block. Netting the walk out makes the comparison the one that actually matters -- ticks.
            double score = yield.isEmpty() ? Double.NEGATIVE_INFINITY
                    : yield.size() - walkCostInCells(from, stance) + onLayerBonus(state, stance)
                            - slipperyPenalty(world, stance)
                            + rarityBonus(yield, servedBy)
                            + footprintBonus(from, open, yield);
            // Strictly greater, so a tie keeps the EARLIER shortlist entry -- which is the better-voted and, among
            // equal votes, the nearer one. Every shortlisted stance is measured; stopping at the first that yields
            // anything would be "the nearest stance that works", which is the policy this change exists to replace.
            if (score > bestScore) {
                bestScore = score;
                best = yield;
            }
            if (!open.contains(PlacementGeometry.positionKey(stance)) && score > bestSpareScore) {
                bestSpareScore = score;
                bestSpare = yield;
            }
        }
        if (!bestSpare.isEmpty()) {
            return Optional.of(bestSpare.get(0));
        }
        return best.isEmpty() ? Optional.ofNullable(nearest(state, frontier)) : Optional.of(best.get(0));
    }

    /**
     * The stances worth measuring, best-voted first — {@link #MOVE_SHORTLIST} of them at most.
     *
     * <p>Bounded because measuring a stance costs one {@link PlacementOracle#solveFrom} per open cell in its window,
     * and a full layer of etz-basalt offers upwards of two thousand distinct stances. Ordered by vote, then by
     * distance from the last stance, then lexicographically: the last term is what makes the choice reproducible, and
     * the middle one is what keeps a tie local instead of sending the bot across the build for the same yield.
     */
    /**
     * Credit for serving cells that few other stances can serve.
     *
     * <p>A cell reachable from twenty stances can wait; one reachable from two has to be taken while the body
     * happens to be at one of them, or it becomes a thirty-block round trip later. That is the hole-leaving the owner
     * watched: nothing was ever wrong with the cells left behind, they were simply always available -- until they
     * were not.
     *
     * <p>Costs nothing to know. The count comes out of the measurement {@link #pick} already performs on every
     * shortlisted stance; it was being thrown away.
     *
     * <p>Bounded rather than summed. A stance serving thirty ordinary cells must not out-score one serving the last
     * two reachable ones purely on volume, and an unbounded sum would let it: what matters is whether a stance is
     * holding SOMETHING scarce, not how much bulk it carries.
     */
    private static double rarityBonus(List<PlacementSolution> yield, Long2IntOpenHashMap servedBy) {
        double credit = 0.0D;
        for (PlacementSolution solution : yield) {
            int servers = servedBy.get(PlacementGeometry.positionKey(solution.cell()));
            if (servers > 0 && servers <= RARE_STANCE_COUNT) {
                credit += (RARE_STANCE_COUNT + 1 - servers) * RARITY_UNIT;
            }
        }
        return Math.min(credit, RARITY_CAP);
    }

    /**
     * Credit for a stance that can close the cell the body is standing IN.
     *
     * <p>The owner's case exactly: the body occupies a cell the schematic still wants filled, so the cell cannot be
     * proven while it stands there, and walking away turns one block into a return trip across the build. The fix is
     * not to forbid standing there -- sometimes there is nowhere else -- but to make the NEXT stance the one that
     * closes it, which costs a step and saves a journey.
     *
     * <p>Only paid when the footprint is genuinely still open, so it is silent on every stance that has no debt.
     */
    private static double footprintBonus(BlockPos from, LongOpenHashSet open, List<PlacementSolution> yield) {
        if (from == null || !open.contains(PlacementGeometry.positionKey(from))) {
            return 0.0D;
        }
        for (PlacementSolution solution : yield) {
            if (solution.cell().equals(from)) {
                return FOOTPRINT_BONUS;
            }
        }
        return 0.0D;
    }

    /** At or below this many serving stances a cell counts as scarce. Four: past that there is reliably another way
     *  back to it, and below it the opportunity is genuinely at risk of being the last one. */
    private static final int RARE_STANCE_COUNT = 4;

    /** What one step of scarcity is worth, in placements. Small on purpose -- this decides between stances that are
     *  otherwise close, and must never drag the body across the build for a single rare cell. */
    private static final double RARITY_UNIT = 0.25D;

    /** Ceiling on the whole rarity term, so a stance cannot win on accumulated scarcity alone. */
    private static final double RARITY_CAP = 2.0D;

    /** Worth three placements: closing the cell you are standing in beats a couple of ordinary ones, because the
     *  alternative is not "later" but "after walking back". */
    private static final double FOOTPRINT_BONUS = 3.0D;

    /**
     * A thumb on the scale for standing ON the layer being built rather than beside or below it.
     *
     * <p>The owner's preference, and it is a preference rather than a rule on purpose: a body on top of the current
     * layer sees the work from above, places downward instead of across, and is not standing in the cells it still
     * has to fill. All three make the build cleaner. None of them is worth failing a layer over, so this is worth one
     * placement — enough to break a tie or beat a step of walking, not enough to send the body somewhere it cannot
     * usefully work.
     *
     * <p>Nothing here is a constraint the proof has to honour. It reorders candidates that are all already provable;
     * a stance that cannot serve a cell scores {@code NEGATIVE_INFINITY} long before this is added to it.
     */
    /**
     * What standing on slippery ground costs, in placements.
     *
     * <p>A soft preference and not a ban, because a farm can be built mostly out of ice and a ban would refuse to
     * build it at all. There is almost always a normal block a step away, and this is enough to choose it. The hard
     * half of the answer lives in the oracle, which refuses to ask a drifting body for precision it cannot hold.
     */
    private static double slipperyPenalty(PredictedWorld world, BlockPos stance) {
        return world.get(stance.below()).getBlock().getFriction() > PlacementOracle.NORMAL_FRICTION + 1.0E-4F
                ? SLIPPERY_STANCE_PENALTY : 0.0D;
    }

    /** Worth two placements. Enough to prefer any reachable normal block, not enough to refuse an ice-only farm. */
    private static final double SLIPPERY_STANCE_PENALTY = 2.0D;

    private static double onLayerBonus(PlannerState state, BlockPos stance) {
        return stance != null && stance.getY() == state.currentLayer() + 1 ? ON_LAYER_BONUS : 0.0D;
    }

    /** Worth one placement. See {@link #onLayerBonus}. */
    private static final double ON_LAYER_BONUS = 1.0D;

    /**
     * What walking to this stance costs, expressed in placements so it can be subtracted from a yield.
     *
     * <p>Measured, not guessed. The longest walks in the trace run 192 ticks over a path of 35 steps, so a step of
     * path costs about 5.5 ticks; a placement costs about 15. Three blocks of walking is therefore worth roughly one
     * placement, which is the divisor below. Straight-line distance rather than path length because the path is not
     * known until the pathfinder is asked, and asking it once per shortlisted stance per placement is not affordable.
     * It under-estimates a walk around an obstacle, which errs toward staying put -- the safe direction.
     */
    private static double walkCostInCells(BlockPos from, BlockPos to) {
        if (from == null) {
            return 0.0D;
        }
        return Math.sqrt(distanceSquared(from, to)) / WALK_BLOCKS_PER_PLACEMENT;
    }

    /** Blocks of walking that cost as much as one placement. See {@link #walkCostInCells}. */
    private static final double WALK_BLOCKS_PER_PLACEMENT = 3.0D;

    private static List<BlockPos> shortlist(PlannerState state, List<PlacementSolution> frontier) {
        // Counted into a long-keyed map and collected into a LIST in first-appearance order. Walking the map itself
        // would put a hash order into the plan, which is the one thing determinism cannot survive.
        Long2IntOpenHashMap votes = new Long2IntOpenHashMap();
        List<BlockPos> stances = new ArrayList<>();
        for (PlacementSolution solution : frontier) {
            long key = PlacementGeometry.positionKey(solution.stance());
            if (votes.addTo(key, 1) == 0) {
                stances.add(solution.stance());
            }
        }
        BlockPos from = state.lastStance();
        // Votes MINUS what the walk costs, in the same currency and with the same price pick() settles on. Votes
        // alone was the reason the walk-cost comparison in pick() could not bite: the stance the bot is already
        // standing on is worth zero walk and is very often worth only two or three votes, so with a cut at sixteen it
        // was not in the list at all and could not be chosen however cheap it was. The bot could not stay put because
        // staying put was never on the ballot.
        stances.sort(Comparator
                .comparingDouble((BlockPos stance) -> -(votes.get(PlacementGeometry.positionKey(stance))
                        - walkCostInCells(from, stance) + onLayerBonus(state, stance)))
                .thenComparingLong(stance -> distanceSquared(from, stance))
                .thenComparingLong(PlacementGeometry::positionKey));
        List<BlockPos> shortlisted = stances.size() <= MOVE_SHORTLIST
                ? stances : new ArrayList<>(stances.subList(0, MOVE_SHORTLIST));
        // And the stance underfoot is on the ballot unconditionally, whatever it scored. It is the only candidate
        // whose walk is free, so the one case that must never be missed is the one where it can still serve a cell.
        if (from != null && !shortlisted.contains(from)
                && votes.containsKey(PlacementGeometry.positionKey(from))) {
            shortlisted.add(0, from);
        }
        return shortlisted;
    }

    /**
     * The open cells this stance can place right now, ordered FAR TO NEAR along the sight line.
     *
     * <p>Two halves, and both are load-bearing.
     *
     * <p><b>Solvable from here</b> means {@link PlacementOracle#solveFrom} returns a solution for the cell with this
     * stance and nothing else in the window — the same precheck, the same margin threshold, the same occlusion proof
     * a solution from anywhere else has to clear. A cell that is only solvable from here BELOW the acceptance floor
     * is not in this list: the oracle records it as tight and returns nothing, exactly as it would for the
     * unrestricted search. That is what stops "stay where you are" from turning into "take the coin flip rather than
     * walk", which would be trading the one guarantee this engine has for a few seconds of travel.
     *
     * <p><b>Far to near</b> because the bot is about to fill several cells along roughly the same sight line, and a
     * block placed at two blocks' distance stands between the crouched eye and the aim point at four. Plan 5.5
     * already records this for scaffold groups; holding a stance makes it the normal case. Worked at the ordinary
     * geometry of a floor layer: feet on the layer, crouched eye 1.27 above it, aiming at the top face of the cell
     * below a target three cells out — the sight line passes 0.85 above the layer at one cell out, so a block placed
     * there first cuts the ray to everything behind it. Near-first would leave those cells for a later walk, which is
     * the very cost this change exists to remove.
     *
     * <p>Nothing is remembered: {@link PlannerState#remember} is deliberately not called for these solutions. The
     * cached best solution and the dependency index behind invariant P are the answer to "what is this cell's best
     * placement anywhere", and overwriting that with a stance-local answer would both bias the next stance choice and
     * make P reason about a footprint the planner is not committed to.
     */
    /**
     * Width of one distance band, in blocks. Cells whose reach falls in the same band are at the same distance from
     * the eye, cannot occlude one another, and are therefore free to be ordered by head turn instead.
     *
     * <p>This is the inverse of the arrangement tried first, and the first one was wrong. Bucketing by DIRECTION and
     * ordering far-to-near inside a direction sounds equivalent and is not: at the range a stance actually works at,
     * neighbouring cells subtend enormous angles -- a cell one block out and a cell two blocks out sit 45 degrees
     * apart -- so direction buckets split exactly the pairs that DO occlude each other, and the plan came back
     * INCOMPLETE at 24 of 25 cells. Distance bands cannot do that: far still strictly precedes near, which is the
     * whole of the occlusion protection, and the turn only decides the order of cells that are equidistant.
     *
     * <p>Half a block, because that is tight enough that a band cannot contain a pair with a meaningful depth
     * difference and wide enough that a band usually holds several cells to reorder.
     */
    private static final double REACH_BAND_BLOCKS = 0.5D;

    /** Which distance band a reach falls in, counted so that a LOWER band number means FARTHER away and the
     *  comparator's natural ascending order keeps far-to-near. */
    private static int reachBand(double reach) {
        return -(int) Math.floor(reach / REACH_BAND_BLOCKS);
    }

    /**
     * How many buckets of head turn away from the previous aim this one is. 0 means "the head is already pointing
     * there".
     *
     * <p>Yaw only. A build is overwhelmingly horizontal, the pitch range within one stance window is a few degrees,
     * and mixing the two axes into one number would need a weighting that nothing measured supports.
     *
     * <p>Returns 0 for the first placement of a build, when there is no previous aim: with no head history there is
     * no turn to minimise and the far-to-near order decides alone, exactly as it did before.
     */
    private static double turnDegrees(Vec3 fromAim, Vec3 eye, Vec3 toAim) {
        if (fromAim == null) {
            return 0.0D;
        }
        double previous = Math.toDegrees(Math.atan2(fromAim.z - eye.z, fromAim.x - eye.x));
        double next = Math.toDegrees(Math.atan2(toAim.z - eye.z, toAim.x - eye.x));
        return Math.abs(Math.IEEEremainder(next - previous, 360.0D));
    }

    private List<PlacementSolution> solvableFrom(PredictedWorld world, PlannerState state, List<BlockPos> remaining,
                                                 BlockPos stance) {
        if (stance == null) {
            return List.of();
        }
        List<PlacementSolution> solvable = new ArrayList<>();
        for (BlockPos cell : remaining) {
            if (PlacementOracle.withinStanceWindow(stance, cell)) {
                solveFrom(world, state, cell, stance)
                        .filter(solution -> !state.routeDeferred(solution))
                        .ifPresent(solvable::add);
            }
        }
        PlayerPose pose = state.oracle().pose();
        // Only the horizontal centre of the stance matters here: turnBucket compares yaw, and the eye height cancels
        // out of an atan2 over x and z. Inlined rather than reaching into the oracle for a private helper.
        Vec3 eye = new Vec3(stance.getX() + 0.5D, stance.getY(), stance.getZ() + 0.5D);
        Vec3 from = state.lastAim();
        solvable.sort(Comparator
                // Far to near FIRST, banded. This is not a convention, it is occlusion protection: a cell filled at
                // two blocks' distance stands between the crouched eye and an aim point at four, so near-first would
                // cut the ray to everything behind it. Banding it changes nothing about that -- far still strictly
                // precedes near across bands.
                .comparingInt((PlacementSolution solution) -> reachBand(solution.reach(pose)))
                // Inside one band the cells are equidistant, and the sort's own older comment already establishes
                // what that means: "two cells the same distance from one eye are not on one sight line and cannot
                // occlude each other". So this is exactly the freedom the order has always had, spent on the head
                // instead of on nothing: among cells that cannot affect each other, take the one the head is already
                // closest to. That is where the 180-degree ping-pong within a stance lived.
                .thenComparingDouble(solution -> turnDegrees(from, eye, solution.aimPoint()))
                // Reproducibility, as before: a total order is what makes two runs produce the same plan.
                .thenComparingLong(solution -> PlacementGeometry.positionKey(solution.cell())));
        return solvable;
    }

    /**
     * The pre-21.1 policy, kept whole as {@link #pick}'s fallback: scaffold-free first, then nearest to the previous
     * stance, then material affinity, then lexicographic.
     *
     * <p>A linear scan rather than a sort or a heap, and that is not laziness: term 2 is measured from
     * {@link PlannerState#lastStance}, which moves after every commit, so any pre-built ordering would have to be
     * rebuilt on every placement anyway.
     */
    private static PlacementSolution nearest(PlannerState state, List<PlacementSolution> frontier) {
        PlacementSolution best = null;
        for (PlacementSolution candidate : frontier) {
            if (best == null || compare(state, candidate, best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The scaffold escalation of 5.1: nothing in {@code todo} is solvable as the world stands, so find the cell that
     * helper blocks unlock most cheaply and return the placements plus the cell's own actions.
     *
     * <p>Reached only when the frontier is empty, which under a hard layer bound is not an exception but the normal
     * way a layer whose click faces live in L+1 gets built. Delegates to {@link ScaffoldPlanner#escalate}; it is a
     * method here so that "the frontier was empty and this is what happened next" is one step in one place rather
     * than a branch inside the layer loop.
     *
     * <p>The upward-look family is served through this same door. Its answer — a helper block on {@code T+UP}, clicked
     * from underneath — needs the reserved throwaway item and its slot, and both of those live in
     * {@link ScaffoldPlanner}, so {@link UpwardLook#solve} is that class's call to make and not this one's.
     *
     * @return empty when scaffold unlocks nothing either — the layer is then blocked and every cell in it is named
     */
    public Optional<ScaffoldPlanner.Escalation> escalate(PredictedWorld world, PlannerState state,
                                                         List<BlockPos> todo) {
        return this.scaffold.escalate(world, state, todo);
    }

    // ------------------------------------------------------------------- action compilation

    /**
     * The action LIST one cell compiles to — never one action.
     *
     * <p>A repeater is a placement plus N right-clicks for its delay; a sign is a placement plus its text; a
     * secondary half is placed by its primary and contributes nothing of its own. Keeping them in ONE list in the
     * frozen order is what makes a repeater's delay structurally impossible to lose: there is no follow-up pass that
     * can be abandoned, because there is no follow-up pass.
     */
    public List<BuildAction> actionsFor(PredictedWorld world, PlannerState state, PlacementSolution solution) {
        List<BuildAction> actions = new ArrayList<>(2);
        BlockPos cell = solution.cell();
        int layer = state.view().layerOf(cell);
        breakFor(world, state, cell).ifPresent(actions::add);

        // Sneak for everything except a chest. Sneak forces ChestBlock.getStateForPlacement to SINGLE, so a crouched
        // click on either half of a planned double chest silently builds two singles that pass every "is a chest
        // there" check; BuildAction.Place refuses that combination in its constructor rather than trusting this line.
        boolean sneak = PlacementFamilies.classify(solution.desired()) != PlacementFamilies.Family.CHEST_TYPE;
        // UNASSIGNED_SLOT and not a guess: the solution names the ITEM, and HotbarSchedule.weave writes the slot in
        // once belady has decided which resident to evict for it. See BuildAction.UNASSIGNED_SLOT.
        if (solution.stance().equals(solution.cell())) {
            // Standing in the cell it fills is a thing only a jump-place may say, and PlacementOracle only ever
            // produces it from solveJumpPlace. No extra field is needed to tell the two apart: the geometry is the
            // discriminator, and BuildAction.JumpPlace re-checks it in its own constructor.
            actions.add(new BuildAction.JumpPlace(solution, BuildAction.UNASSIGNED_SLOT, layer));
        } else {
            actions.add(new BuildAction.Place(solution, sneak, BuildAction.UNASSIGNED_SLOT, layer));
        }

        // Waterlogging is a second, separately acknowledged mutation. The block is first placed in the dry state the
        // item can actually produce, then the water bucket is used ON that standing block. The cell is not final and
        // is not credited in the proof until this action commits its exact schematic state.
        if (FluidPlan.wantsWaterlogging(solution.desired())) {
            Optional<BreakPlanner.Aim> aimed = BreakPlanner.aim(world, PlayerPose.CROUCHED, state.budget(), cell,
                    solution.stance(), solution.predicted());
            if (aimed.isEmpty()) {
                // PlacementOracle refuses such a candidate before it reaches the frontier. Reaching this branch means
                // those two proof paths drifted; emitting only the dry placement would be a silently wrong build.
                throw new IllegalStateException("waterlogging aim disappeared for "
                        + BuildAction.describePos(cell) + " after the placement solution was accepted");
            }
            BreakPlanner.Aim at = aimed.get();
            actions.add(new BuildAction.FillFluid(cell, cell, at.stance(), at.approach(), at.point(), at.rotation(),
                    at.face(), Items.WATER_BUCKET, solution.desired(), true, BuildAction.UNASSIGNED_SLOT, layer));
        }

        // The interaction-settable half of the state: repeater delay, comparator mode, note pitch, an open trapdoor.
        // Emitted in the same list as the placement, which is what makes it impossible to lose.
        int clicks = PlacementGeometry.interactionClicks(solution.predicted(), solution.desired());
        if (clicks > 0) {
            // The same stance-and-look search a break uses, which is why it lives in BreakPlanner: an interaction and
            // a break differ in which mouse button goes down, not in the geometry that has to be proven first. The
            // desired state is passed as `placing` because the block is not in the world yet -- this action's click
            // goes out against what the placement immediately above it will have put there.
            //
            // STANDING, and this is the one place in the engine that is: a crouched right-click does not step a
            // repeater, it places the held block on it. So the click goes out from the standing eye, and an aim
            // proven from the crouched one -- 0.35 blocks lower -- is not the ray that will be cast. Every other
            // action in the plan is proven crouched and clicked crouched; this one is proven standing and clicked
            // standing, and the two must not be mixed.
            Optional<BreakPlanner.Aim> aimed = BreakPlanner.aim(world, PlayerPose.STANDING, state.budget(), cell,
                    solution.stance(), solution.desired());
            if (aimed.isPresent()) {
                BreakPlanner.Aim at = aimed.get();
                // Never sneaking and never holding a placeable item: a crouched right-click puts the held block down
                // instead of stepping the state, which is a wrong build rather than a slow one. The pickaxe slot is
                // the hand that cannot place, and InventoryBehavior keeps it stocked for us anyway.
                actions.add(new BuildAction.Interact(cell, at.stance(), at.approach(), at.point(), at.rotation(),
                        at.face(), clicks, solution.desired(), false, HotbarSchedule.PICKAXE_SLOT, layer));
            } else {
                state.note("no provable interaction aim at " + BuildAction.describePos(cell) + " — "
                        + BuildAction.describeState(solution.desired()) + " will be placed but its "
                        + clicks + " right-click(s) are not planned");
            }
        }

        // The sign's text, in the same list as the sign. Placing a sign is what OPENS its edit screen, so the two are
        // one event as far as vanilla is concerned and separating them into a later pass is exactly how the text gets
        // lost -- the screen would be long closed by the time the pass ran.
        signActionFor(state, cell, layer).ifPresent(actions::add);
        return actions;
    }

    /**
     * The {@link BuildAction.WriteSign} for a cell, when the schematic actually carries text for it.
     *
     * <p>Emitted only for a sign whose block-entity NBT holds a non-blank FRONT side. Every other case is silence,
     * and each of them for a reason worth stating once:
     *
     * <ul>
     *   <li><b>No NBT at all.</b> Most formats drop it at parse time and a blank sign is what the schematic then
     *       genuinely describes. Reported once per build through {@link PlanReport#limitations}, never once per
     *       cell.</li>
     *   <li><b>A blank side.</b> An action whose confirmation is "the four lines are still empty" would open a
     *       screen, type nothing and close it — a screen round trip per sign for the state that already existed.</li>
     *   <li><b>Unreadable text.</b> A component that will not parse is named and skipped. Typing a diagnostic string
     *       onto the user's build, or silently typing four blank lines over words the schematic asked for, are both
     *       worse than saying so.</li>
     *   <li><b>The BACK side.</b> {@code SignItem} opens a freshly placed sign's editor with {@code frontText = true}
     *       unconditionally; the back opens only for a player standing behind the sign and right-clicking it. That is
     *       a second stance and a second proof, and it is named up front rather than attempted and abandoned.</li>
     * </ul>
     */
    private Optional<BuildAction.WriteSign> signActionFor(PlannerState state, BlockPos cell, int layer) {
        SchematicView view = state.view();
        if (!SignNbt.isSign(view.blockEntity(cell))) {
            return Optional.empty();
        }
        List<String> back = view.signLines(cell, false);
        if (!back.isEmpty()) {
            state.note("sign back-side text at " + BuildAction.describePos(cell) + " is not written: a freshly "
                    + "placed sign always opens its FRONT editor, and the back is reachable only by right-clicking "
                    + "the sign from a stance behind it");
        }
        List<String> lines = view.signLines(cell, true);
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        if (SignNbt.unreadable(lines)) {
            state.note("sign text at " + BuildAction.describePos(cell) + " could not be decoded and is left blank");
            return Optional.empty();
        }
        // Standing, and holding the pickaxe: nothing is clicked at the world here. The screen is already open and the
        // four lines go in through its own key handling, so the posture is only about not disturbing anything.
        return Optional.of(new BuildAction.WriteSign(cell, lines, true, false, HotbarSchedule.PICKAXE_SLOT, layer));
    }

    /**
     * Is a cell already correct, or does something have to come out of it first?
     *
     * <p>The {@code todo} filter and the {@code BREAK} trigger in one place, because they are one question asked
     * twice. Scope is fixed by 5.9: the engine breaks only what stands in the way of a planned action, and never
     * empties every cell the schematic happens to call air. Clearing a volume stays an explicit command
     * ({@code clearArea}), never a silent side effect.
     *
     * <p>Refuses to plan a break under a falling block. Sand, gravel, concrete powder and an anvil all land in the
     * very cell the break was trying to empty, so the restoration would have to break them again — a cycle, and E3
     * forbids tearing down to correct. The cell is reported instead, which is the answer the dry run exists to give.
     */
    public Optional<BuildAction.Break> breakFor(PredictedWorld world, PlannerState state, BlockPos cell) {
        int layer = state.view().covers(cell) ? state.view().layerOf(cell) : state.currentLayer();
        BreakPlanner.Attempt attempt = BreakPlanner.plan(world, state.view(), state.settings(),
                state.oracle().pose(), state.budget(), cell, state.lastStance(),
                BreakPlanner.Reason.SCHEMATIC_CELL, layer);
        if (attempt.note() != null) {
            // A refusal that carries a sentence is one a human can act on -- go and move the sand, or accept that the
            // cell is unreachable. Dropping it here would turn the most useful line in the report into an empty
            // Optional, which is the shape V2's break story had and the reason nobody could tell WHY a cell was left.
            state.note(attempt.note());
        }
        return attempt.optional();
    }

    /**
     * Run a break's consequences through the predicted world: gravity blocks fall, neighbours that fail
     * {@code canSurvive} pop off.
     *
     * <p>If a SCHEMATIC block is lost to the cascade, its re-placement is emitted as an explicit action rather than
     * left for a later pass to notice. A cascade the plan does not model is a divergence at execution time, and the
     * executor's only honest response to a divergence is to stop.
     *
     * <p>Gravity is handled by refusal rather than by simulation — see {@link #breakFor}. A falling column that
     * nevertheless turns up here is a bug in that refusal, so it is reported as a limitation rather than quietly
     * mismodelled: the world would then hold a block the plan believes is elsewhere, and every proof after it would
     * be against a fiction.
     */
    public List<BuildAction> cascade(PredictedWorld world, PlannerState state, BlockPos broken) {
        // The world edits go through the state's own two verbs rather than through world.apply, so the enclosure
        // marking, the dependency index and the invalidation list move with the cascade. BreakPlanner decides WHAT
        // moves; the state remains the single owner of the predicted world (E-C).
        BreakPlanner.Cascade cascade = BreakPlanner.cascade(world, state.view(), state.settings(), broken,
                state.currentLayer(), new BreakPlanner.Edits() {

                    @Override
                    public void vacate(BlockPos pos) {
                        state.vacate(world, pos);
                    }

                    @Override
                    public void fill(BlockPos pos, BlockState settled) {
                        state.fill(world, pos, settled);
                    }
                });
        for (String note : cascade.notes()) {
            state.note(note);
        }

        List<BuildAction> restorations = new ArrayList<>();
        for (BlockPos victim : cascade.lost()) {
            // A schematic block of a finished or current layer lost its block. Its re-placement is an ACTION in this
            // plan, at this point in the order, or the layer gate finds a hole nobody can explain.
            Optional<PlacementSolution> again = solve(world, state, victim);
            if (again.isPresent()) {
                restorations.addAll(actionsFor(world, state, again.get()));
            } else {
                state.note("breaking " + BuildAction.describePos(broken) + " drops "
                        + BuildAction.describePos(victim) + " and it cannot be replaced from here");
            }
        }
        return restorations;
    }

    // ------------------------------------------------------------------- proven vs provisional

    /**
     * {@link BuildPlan.Confidence#PROVEN} when the cell's entire dependency footprint — itself, its six neighbours,
     * every candidate stance, every ray path, everything within reach plus one — lay in loaded chunks when the
     * snapshot was taken. {@link BuildPlan.Confidence#PROVISIONAL} otherwise.
     *
     * <p>This distinction is the whole value of "proven before the run", and it exists because
     * {@code BlockStateInterface.get0} returns AIR for an unloaded chunk — no null, no exception, no log line. A
     * plan that did not carry loadedness explicitly would be a proof against imagined air, and nothing anywhere would
     * say so. For etz-basalt, fully in view from the anchor, proven is 100 %; the rule exists for everything larger.
     *
     * <p>Walked as COLUMNS rather than as cells. {@link PredictedWorld#isLoaded} is per column by construction — the
     * snapshot asks {@code worldContainsLoadedChunk} once per column because the answer cannot vary with Y — so the
     * (2r+1)^3 cells of the footprint carry only (2r+1)^2 distinct answers, and asking for the other 2 000 of them per
     * cell would multiply the whole dry run by the height of the box for no new information.
     */
    public BuildPlan.Confidence confidenceOf(PredictedWorld world, PlannerState state, PlacementSolution solution) {
        int radius = (int) Math.ceil(state.budget().maxReach()) + 1;
        BlockPos cell = solution.cell();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!world.isLoaded(cell.getX() + dx, cell.getY(), cell.getZ() + dz)) {
                    return BuildPlan.Confidence.PROVISIONAL;
                }
            }
        }
        // The stance sits inside that radius by construction (the window is three cells), but its own column is
        // checked at ITS Y as well: the box is bounded vertically too, and a stance three below a cell at the bottom
        // of the snapshot is outside it, which isLoaded reports as not loaded.
        BlockPos stance = solution.stance();
        return world.isLoaded(stance.getX(), stance.getY(), stance.getZ())
                && world.isLoaded(stance.getX(), stance.getY() + 1, stance.getZ())
                ? BuildPlan.Confidence.PROVEN : BuildPlan.Confidence.PROVISIONAL;
    }

    // ------------------------------------------------------------------- the layer loop's machinery

    /**
     * Solve one cell against the world as it now stands, remember the result, and account for the rays. The single
     * place a solve happens, so the ray counter, the TIGHT report and the dependency index cannot drift apart.
     *
     * <p>"As it now stands" has one exception, and without it the whole {@code BREAK} action type is dead code. The
     * oracle refuses an occupied cell in its precheck — {@code PlacementOracle.java:554}, {@code CELL_OCCUPIED},
     * before a single stance is tried — so a cell holding a foreign block has NO solution, therefore never reaches
     * {@link #actionsFor}, therefore never reaches {@link #breakFor}, therefore is reported as a blocker rather than
     * broken. Trigger one of 5.9 ("a schematic cell contains something other than desired") would have been
     * unreachable by construction, and the symptom is not an error: it is V2's behaviour, silently.
     *
     * <p>So a cell whose occupant CAN be taken out is solved against the world the break will leave behind. The
     * clearing is speculative and reverted before returning — {@link #actionsFor} plans the break for real, at commit
     * time, and puts it in front of the placement in the same action list. Reverted with an explicit
     * {@link PredictedWorld#apply} rather than {@link PredictedWorld#revert}: revert restores the SNAPSHOT, and this
     * cell may hold a delta the plan put there itself.
     */
    private Optional<PlacementSolution> solve(PredictedWorld world, PlannerState state, BlockPos cell) {
        // placementTarget, not desired: the first half of a double chest can only be placed as type=single, and the
        // second half's click converts it. See SchematicView.placementTarget.
        BlockState desired = state.view().placementTarget(world, cell);
        if (desired == null || desired.isAir()) {
            return Optional.empty();
        }
        BlockState occupant = world.get(cell);
        // canBeReplaced, not isAir, and it is the same test the oracle's precheck makes: tall grass and water are
        // replaced by the click itself, so clearing them would plan a break that buys nothing.
        boolean clearing = !occupant.canBeReplaced() && breakFor(world, state, cell).isPresent();
        if (clearing) {
            world.apply(cell, Blocks.AIR.defaultBlockState());
        }
        try {
            PlacementOracle.Solve solve = state.oracle().solve(world, cell, desired, state.stacks(), state.budget(),
                    state.journal());
            state.addRays(solve.raysCast());
            Optional<PlacementSolution> best = solve.best();
            if (best.isPresent()) {
                state.remember(cell, best.get(), world);
                return best;
            }
            state.invalidate(cell);
            if (!solve.tight().isEmpty()) {
                // A cell that solves only at 0.04 is a coin flip, and the report is where a coin flip gets declared.
                // The best of the tight solutions is the honest number to print -- it is the one the bot would have
                // taken.
                double margin = Double.NEGATIVE_INFINITY;
                for (PlacementSolution tight : solve.tight()) {
                    margin = Math.max(margin, tight.margin());
                }
                state.recordTight(cell, desired, margin);
            }
            return Optional.empty();
        } finally {
            if (clearing) {
                world.apply(cell, occupant);
            }
        }
    }

    /**
     * Solve one cell from ONE stance, and account for the rays. The stance-anchored counterpart of {@link #solve},
     * and deliberately not a variant of it.
     *
     * <p>Three things {@link #solve} does are left out here, and each of them would be a bug if it were kept:
     *
     * <ul>
     *   <li><b>No {@code remember}.</b> The cache and the dependency index answer "this cell's best placement
     *       anywhere". A stance-local answer written there would bias the next stance choice by its own votes and
     *       would give invariant P a footprint the planner never committed to.</li>
     *   <li><b>No {@code invalidate} on failure.</b> "Not placeable from here" is not "not placeable"; dropping the
     *       cell's real solution and its index edges because one stance could not serve it would take invariant P's
     *       evidence away from it.</li>
     *   <li><b>No TIGHT record.</b> A cell that only just fails from here may be comfortable from its own stance,
     *       and a report line that named it would be describing a placement the plan is not going to make.</li>
     * </ul>
     *
     * <p>The speculative clearing IS kept, and for the same reason {@link #solve} has it: the oracle refuses an
     * occupied cell in its precheck, so a cell whose occupant the plan is going to break has to be asked against the
     * world that break will leave behind, or it could never be taken from a held stance at all.
     */
    private Optional<PlacementSolution> solveFrom(PredictedWorld world, PlannerState state, BlockPos cell,
                                                  BlockPos stance) {
        BlockState desired = state.view().placementTarget(world, cell);
        if (desired == null || desired.isAir()) {
            return Optional.empty();
        }
        BlockState occupant = world.get(cell);
        boolean clearing = !occupant.canBeReplaced() && breakFor(world, state, cell).isPresent();
        if (clearing) {
            world.apply(cell, Blocks.AIR.defaultBlockState());
        }
        try {
            PlacementOracle.Solve solve = state.oracle().solveFrom(world, cell, desired, state.stacks(),
                    state.budget(), stance, state.journal());
            state.addRays(solve.raysCast());
            return solve.best();
        } finally {
            if (clearing) {
                world.apply(cell, occupant);
            }
        }
    }

    /** Re-solve every dirty cell that is still outstanding, and clear the dirty set. */
    private void refresh(PredictedWorld world, PlannerState state, List<BlockPos> remaining, LongOpenHashSet dirty) {
        if (dirty.isEmpty()) {
            return;
        }
        for (BlockPos cell : remaining) {
            if (dirty.contains(PlacementGeometry.positionKey(cell)) && !state.routeParked(cell)) {
                solve(world, state, cell);
            }
        }
        dirty.clear();
    }

    /** The frontier straight out of the cache, in {@code remaining} order — which is the layer's own order, so the
     *  upward family's pre-sort survives into the candidate list. */
    private List<PlacementSolution> cachedFrontier(PlannerState state, List<BlockPos> remaining) {
        List<PlacementSolution> frontier = new ArrayList<>();
        for (BlockPos cell : remaining) {
            if (state.routeParked(cell)) {
                continue;
            }
            state.bestSolution(cell)
                    .filter(solution -> !state.routeDeferred(solution))
                    .ifPresent(frontier::add);
        }
        return frontier;
    }

    /**
     * Consume the positions the last commit changed and mark everything they can have affected for re-solving.
     *
     * <p>Union of two sets that fail in opposite directions: {@link DependencyIndex#dependents} knows precisely which
     * cells were USING the position, which is the only affordable answer for a 2 189-cell layer, and it structurally
     * cannot know about a cell that had no solution at all — the exact cell a new neighbour is most likely to unblock.
     * The radius covers those. Neither alone is safe and the failure of either is silent.
     */
    private void drainTouched(PlannerState state, List<BlockPos> remaining, LongOpenHashSet dirty) {
        if (state.touched.isEmpty()) {
            return;
        }
        for (BlockPos position : state.touched) {
            for (BlockPos dependent : state.index().dependents(position)) {
                state.invalidate(dependent);
                dirty.add(PlacementGeometry.positionKey(dependent));
            }
            for (BlockPos cell : remaining) {
                if (Math.abs(cell.getX() - position.getX()) <= INVALIDATION_RADIUS
                        && Math.abs(cell.getY() - position.getY()) <= INVALIDATION_RADIUS
                        && Math.abs(cell.getZ() - position.getZ()) <= INVALIDATION_RADIUS) {
                    state.invalidate(cell);
                    dirty.add(PlacementGeometry.positionKey(cell));
                }
            }
        }
        state.touched.clear();
    }

    /**
     * Commit an action list, chasing the consequences of every break.
     *
     * <p>The cascade is driven from here rather than from {@link PlannerState#commit} because it needs the oracle to
     * re-solve what fell, and the state is deliberately a value that carries no planner. Everything still passes
     * through the one commit, so the order, the world, the index and the enclosure marking advance together.
     */
    private void commitAll(PredictedWorld world, PlannerState state, List<BuildAction> actions) {
        for (BuildAction action : actions) {
            state.commit(world, List.of(action));
            if (action instanceof BuildAction.Break broken) {
                commitAll(world, state, cascade(world, state, broken.cell()));
            }
        }
    }

    /** The pre-21.1 comparison, in the four terms of 5.3, now reached only through {@link #nearest}. Negative when
     *  {@code first} is preferred. */
    /** 0 for a block that lands the same whatever the placer looks at, 1 for one that does not. The engine already
     *  answers this question for the aim search — a cell with no look constraint scores
     *  {@link PlacementSolution#UNCONSTRAINED_MARGIN} — so nothing new is being decided here, only used earlier. */
    private static int orientationRank(PlacementSolution solution) {
        return PlacementGeometry.yawLookDirection(solution.desired()) == null
                && PlacementGeometry.requiredLookDirections(solution.desired()) == null ? 0 : 1;
    }

    private static int compare(PlannerState state, PlacementSolution first, PlacementSolution second) {
        // 1. scaffold-free before scaffold-dependent. A solution whose click face is an open helper block owes a
        //    removal that a solution clicking a real neighbour does not.
        int rank = Integer.compare(scaffoldRank(state, first), scaffoldRank(state, second));
        if (rank != 0) {
            return rank;
        }
        // 1b. an ORIENTATION-FREE block before an oriented one. A block whose landed state does not depend on the
        //     look can be placed from anywhere that reaches it; an oriented one needs a particular look, and often a
        //     particular side to look from. Placing the free ones first means the oriented one finds neighbours to
        //     stand on and click against that were not there when it was first considered — which is exactly the
        //     shape of the basalt blocker, whose own report named a same-layer neighbour as one of "the 2 cells that
        //     would unblock it". Below the scaffold rank so it can never buy an avoidable helper block, and above
        //     travel so it is not traded away for a couple of ticks of walking.
        int orientation = Integer.compare(orientationRank(first), orientationRank(second));
        if (orientation != 0) {
            return orientation;
        }
        // 2. nearest to the previous stance. ~13 ticks per block walked, the second largest cost item in a build.
        int travel = Long.compare(distanceSquared(state.lastStance(), first.stance()),
                distanceSquared(state.lastStance(), second.stance()));
        if (travel != 0) {
            return travel;
        }
        // 3. material affinity, a TIEBREAK and never anything more: it can only reorder cells that are already
        //    interchangeable, so it cannot move a cell ahead of something it depends on.
        int affinity = Integer.compare(first.item() == state.heldItem() ? 0 : 1,
                second.item() == state.heldItem() ? 0 : 1);
        if (affinity != 0) {
            return affinity;
        }
        // 4. lexicographic. Y is in there after (x, z) purely to make the order total for a caller that hands in two
        //    layers at once; within one layer it never decides anything.
        int x = Integer.compare(first.cell().getX(), second.cell().getX());
        if (x != 0) {
            return x;
        }
        int z = Integer.compare(first.cell().getZ(), second.cell().getZ());
        return z != 0 ? z : Integer.compare(first.cell().getY(), second.cell().getY());
    }

    /**
     * The cell one placement also fills for free — a door's UPPER, a bed's HEAD — or {@code null} when the placement
     * fills only its own cell.
     *
     * <p>One owner for the rule (E-C): the forward simulation has to put that state into the predicted world, and the
     * report has to count that cell as covered. Those are two callers of one question, and answering it twice is how
     * the simulation and the report end up disagreeing about how many cells the plan accounts for.
     *
     * <p>{@code above} first, then the horizontals in {@link Direction.Plane#HORIZONTAL}'s fixed order — a door is the
     * common case and its half is always above, and a fixed order is what keeps a bed placed against two candidate
     * halves resolving the same way in every run.
     */
    private static BlockPos secondaryHalfOf(SchematicView view, BlockPos primary) {
        BlockPos above = primary.above();
        if (view.isSecondaryHalf(above) && primary.equals(view.primaryOf(above))) {
            return above;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = primary.relative(side);
            if (view.isSecondaryHalf(neighbour) && primary.equals(view.primaryOf(neighbour))) {
                return neighbour;
            }
        }
        return null;
    }

    private static int scaffoldRank(PlannerState state, PlacementSolution solution) {
        return state.ledger().holds(solution.against()) ? 1 : 0;
    }

    /** Squared cell distance as a long, not {@code BlockPos.distSqr}'s double: the comparison decides the order of the
     *  whole plan and integer arithmetic cannot round two different distances into one. */
    private static long distanceSquared(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return 0L;
        }
        long dx = from.getX() - to.getX();
        long dy = from.getY() - to.getY();
        long dz = from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------- blocked cells

    /**
     * Why one cell could not be planned, in the words a human acts on.
     *
     * <p>Re-solved under {@link SolveBudget#exhaustive()}: the fast path would hide which stage refused the cell, and
     * the whole value of the line is that "no stance yields the required look" and "every stance was out of reach"
     * are different problems with different fixes.
     */
    private PlanReport.Blocker explainBlocked(PredictedWorld world, PlannerState state, BlockPos cell) {
        // The same target #solve was refused on. Explaining a cell against a state the planner never asked for would
        // report a rejection nothing produced -- for a chest, the un-landable type=left rather than the real reason.
        BlockState desired = state.view().placementTarget(world, cell);
        List<UpwardLook.FootColumn> columns = null;
        if (UpwardLook.applies(desired)) {
            columns = UpwardLook.classifyFootColumns(world, state.view(), cell);
            Optional<PlanReport.Blocker> q1 = UpwardLook.q1(world, state.view(), cell, desired, columns);
            if (q1.isPresent()) {
                // The one case in this family scaffold cannot reach: what is missing is a HOLE, and scaffold only
                // adds. Reported rather than solved, by decision D6, and reported for every affected cell in one pass.
                return q1.get();
            }
        }
        PlacementOracle.Solve solve = state.oracle().solve(world, cell, desired, state.stacks(),
                state.budget().exhaustive(), state.journal());
        state.addRays(solve.raysCast());
        List<BlockPos> unblocking = columns != null
                ? UpwardLook.unblocking(world, state.view(), cell, columns)
                : unblockingFor(world, state, cell, desired);
        return new PlanReport.Blocker(cell, desired, solve.dominantRejection(), solve.rejections(),
                solve.stancesTried(), unblocking, detailFor(columns, solve));
    }

    /**
     * The blocker's detail line, labelled with WHICH search produced it.
     *
     * <p>For most families the solve above IS the one the planner ran, and its census is the explanation. For the
     * upward-look family it is not: the planner reaches those cells through
     * {@code ScaffoldPlanner.upwardEscalation} → {@code UpwardLook.solve}, which runs a FOCUSED budget narrowed to one
     * foot column and the DOWN face, with no stance cap at all. Printing an unfocused 243-stance no-scaffold census
     * for such a cell and calling it "the reason" invents a plausible cause and hides the real refusal — which lives
     * in {@code eligibilityOf} / the support-chain search / {@code removalOf} and is not measured here.
     *
     * <p>So the census is still printed — it is real, and its {@code LOOK_NOT_DOMINANT} count is a genuine fact about
     * the geometry — but it says whose it is. A diagnostic that names its own scope is worth more than one that reads
     * like a verdict, and this family's 32 cells are the first the engine has ever had to explain.
     */
    private static String detailFor(List<UpwardLook.FootColumn> columns, PlacementOracle.Solve solve) {
        if (columns == null) {
            return solve.explain();
        }
        return solve.explain()
                + " -- NOTE: this census is from a generic unfocused solve with no scaffold, NOT from the search the "
                + "planner actually ran for this cell. An upward-look cell is attempted through "
                + "ScaffoldPlanner.upwardEscalation -> UpwardLook.solve, which is focused to a single foot column and "
                + "the DOWN face and has no stance cap; its refusal is not reported by the numbers above.";
    }

    /**
     * The cells whose placement would give this one a solution — the most actionable line in the report.
     *
     * <p>Answered structurally rather than by trial: the cell needs something solid on one of the faces its state
     * allows to be clicked, so the neighbours that are currently empty AND that the schematic wants a block in are
     * exactly the placements that would supply one. Its absence is information too — no such neighbour means no
     * ordering inside this layer can help, which is precisely when scaffold or a report is the only answer left.
     */
    private List<BlockPos> unblockingFor(PredictedWorld world, PlannerState state, BlockPos cell, BlockState desired) {
        List<BlockPos> unblocking = new ArrayList<>();
        for (Direction support : PlacementGeometry.supportDirectionsFor(desired)) {
            BlockPos neighbour = cell.relative(support);
            if (!world.isSolidFullCube(neighbour.getX(), neighbour.getY(), neighbour.getZ())
                    && state.view().wantsBlock(neighbour)) {
                unblocking.add(neighbour);
            }
        }
        unblocking.sort(Comparator.comparingLong(PlacementGeometry::positionKey));
        return unblocking;
    }

    // ------------------------------------------------------------------- the report

    /**
     * Assemble the verdict: weave the hotbar schedule into the order, count everything the report prints, and decide
     * READY against INCOMPLETE.
     *
     * <p>The hotbar schedule runs LAST, over the finished order, and that is the whole reason it can be optimal:
     * Belady needs the complete future of material demand, which exists only once the order is frozen. Weaving its
     * swaps in afterwards cannot reorder anything — a swap is inserted immediately before the action that needs it,
     * so no placement moves relative to another.
     */
    private PlanReport report(SchematicView view, PredictedWorld world, PlannerState state,
                              HotbarSchedule.InventorySnapshot inventory) {
        List<BuildAction> order = state.order();
        List<HotbarSchedule.Demand> demand = HotbarSchedule.demandSequence(order);
        HotbarSchedule.Schedule schedule = HotbarSchedule.belady(demand, inventory);
        List<BuildAction> woven = HotbarSchedule.weave(order, schedule);

        Map<Long, BuildPlan.CellProof> proofs = new LinkedHashMap<>();
        Map<Integer, Integer> scaffoldByLayer = new LinkedHashMap<>();
        int placements = 0;
        int breaks = 0;
        int scaffolds = 0;
        int swaps = 0;
        int interactions = 0;
        int proven = 0;
        int provisional = 0;
        Map<Long, PlacementSolution> awaitingFluidProof = new LinkedHashMap<>();

        for (int index = 0; index < woven.size(); index++) {
            BuildAction action = woven.get(index);
            switch (action.kind()) {
                case PLACE -> {
                    placements++;
                    PlacementSolution solution = ((BuildAction.Place) action).solution();
                    if (FluidPlan.wantsWaterlogging(solution.desired())) {
                        awaitingFluidProof.put(PlacementGeometry.positionKey(solution.cell()), solution);
                        break;
                    }
                    BuildPlan.Confidence confidence = confidenceOf(world, state, solution);
                    // The half vanilla fills for free is a schematic cell in its own right and is counted in
                    // SchematicView.cellCount, but it never gets a PLACE of its own -- so it is credited to the
                    // placement that fills it, at that placement's confidence, because the two cells share one proof.
                    // Without this the headline line of the report ("15004 cells: 15004 proven, 0 provisional") is
                    // short by one per door and per bed on a READY plan, and nothing in the report says why.
                    int covered = secondaryHalfOf(view, solution.cell()) == null ? 1 : 2;
                    if (confidence == BuildPlan.Confidence.PROVEN) {
                        proven += covered;
                    } else {
                        provisional += covered;
                    }
                    proofs.put(PlacementGeometry.positionKey(solution.cell()),
                            new BuildPlan.CellProof(solution.cell(), solution.desired(), solution, confidence,
                                    action.layer(), index));
                }
                case BREAK -> breaks++;
                case PLACE_SCAFFOLD -> {
                    scaffolds++;
                    scaffoldByLayer.merge(action.layer(), 1, Integer::sum);
                }
                case SWAP_HOTBAR -> swaps++;
                case INTERACT -> interactions++;
                case FILL_FLUID -> {
                    BuildAction.FillFluid fluid = (BuildAction.FillFluid) action;
                    PlacementSolution solution =
                            awaitingFluidProof.remove(PlacementGeometry.positionKey(fluid.cell()));
                    if (solution == null) {
                        break;   // a standalone source fill has no block-placement proof to retire
                    }
                    BuildPlan.Confidence confidence = confidenceOf(world, state, solution);
                    if (confidence == BuildPlan.Confidence.PROVEN) {
                        proven++;
                    } else {
                        provisional++;
                    }
                    // actionIndex is the bucket action, not the preceding dry placement. A proof that points at the
                    // Place line would claim the cell final one action too early.
                    proofs.put(PlacementGeometry.positionKey(solution.cell()),
                            new BuildPlan.CellProof(solution.cell(), solution.desired(), solution, confidence,
                                    action.layer(), index));
                }
                default -> {
                    // REMOVE_SCAFFOLD and WRITE_SIGN carry no counter of their own; removals are pinned to the
                    // placements by the layer bound and are counted through scaffoldBlocks.
                }
            }
        }
        if (!awaitingFluidProof.isEmpty()) {
            throw new IllegalStateException(awaitingFluidProof.size()
                    + " waterlogged placement(s) have no final FillFluid action");
        }

        BuildPlan.Counts counts = new BuildPlan.Counts(view.cellCount(), proven, provisional, placements, breaks,
                scaffolds, scaffoldByLayer, swaps, interactions, state.raysCast(), BuildPlan.estimateTicks(woven));
        BuildPlan plan = new BuildPlan(view.name(), view.origin(), woven, view.layers(), proofs, counts);

        List<PlanReport.MaterialNeed> materials = HotbarSchedule.materialLedger(woven, inventory);
        List<String> limitations = new ArrayList<>(state.limitations);
        limitations.addAll(namedExceptions(view));
        // The frozen order checked against the vanilla posture rules, once, before the bot moves. A posture mismatch
        // has no runtime symptom worth the name -- the build simply comes out wrong somewhere, in a way that passes
        // every "is there a block here" check -- so the only useful moment to catch one is now. It cannot fire while
        // the emitters agree with ActionRunner.postureFor, which is exactly why it is worth having: the day someone
        // edits one of the two, this says so instead of the audit saying it three hours later.
        limitations.addAll(ActionRunner.postureViolations(woven));

        // The capacity check of 5.8, asked BEFORE the run rather than discovered during it. 36 slots, two of them
        // held by InventoryBehavior every tick, and one per distinct placeable type: a schematic whose material set
        // does not fit cannot be built from one inventory load however good the schedule is, and the useful moment to
        // learn that is now rather than after three hours of walking.
        HotbarSchedule.CapacityCheck capacity = HotbarSchedule.capacity(view, state.settings(), inventory,
                HotbarSchedule.scaffoldItemOf(woven), woven);
        if (!capacity.fits()) {
            limitations.add(capacity.describe());
        }
        // Trap 1.32: two breaks of one position inside four seconds blacklist it PERMANENTLY, and nothing in src/
        // ever clears the blacklist. The plan cannot know the real interval, so this is a warning rather than a
        // refusal -- but a plan with no repeated position cannot hit it at all.
        List<BlockPos> repeated = BreakPlanner.repeatedBreaks(woven);
        if (!repeated.isEmpty()) {
            StringBuilder line = new StringBuilder(repeated.size()
                    + " position(s) are broken more than once; BlockBreakHelper blacklists a position permanently if "
                    + "two breaks fall inside 4 s and nothing ever clears that blacklist:");
            for (BlockPos position : repeated) {
                line.append(' ').append(BuildAction.describePos(position));
            }
            limitations.add(line.toString());
        }
        if (breaks > 0) {
            limitations.add(BreakPlanner.LOSS_LIMITATION);
        }

        boolean materialShortfall = false;
        for (PlanReport.MaterialNeed need : materials) {
            if (need.shortfall() > 0) {
                materialShortfall = true;
                limitations.add(String.format(Locale.ROOT, "%s: %d short of the %d the build needs",
                        BuildAction.describeItem(need.item()), need.shortfall(), need.needed()));
            }
        }
        if (provisional > 0) {
            limitations.add(provisional + " cells rest on terrain the snapshot never saw and are re-proven on "
                    + "approach; a failure there is a named halt, not a fallback");
        }
        PlanReport.Status status = state.blockers().isEmpty() && capacity.fits() && !materialShortfall
                ? PlanReport.Status.READY : PlanReport.Status.INCOMPLETE;
        return new PlanReport(status, plan, view.cellCount(), proven, provisional, state.blockers(), state.tight(),
                state.layerSummaries(), materials, limitations);
    }

    /**
     * The things this engine cannot do, counted over the schematic and stated BEFORE the run.
     *
     * <p>Every line here describes a cell the plan will visit and leave in a state the schematic did not ask for. None
     * of them is a failure at execution time — the placement lands, the block is there, and only one property is
     * wrong — which is exactly why they have to be said in advance: a limitation discovered by staring at the finished
     * build is a limitation that cost a full run to learn.
     *
     * <p>Counted, never enumerated. "40 iron trapdoors" is the sentence that lets a human decide; forty coordinates
     * is a wall of text that hides the number.
     */
    private static List<String> namedExceptions(SchematicView view) {
        int lockedOpen = 0;
        int unbuildableFluid = 0;
        int fillableSources = 0;
        int waterlogged = 0;
        int signs = 0;
        int impossiblePairs = 0;
        for (BlockPos cell : view.cells()) {
            BlockState desired = view.desired(cell);
            if (desired == null) {
                continue;
            }
            // Iron doors and iron trapdoors move only under redstone. No click reaches their `open`, so a cell that
            // wants one open is built CLOSED and is correct in every other respect. Stated here, and never turned
            // into a break-and-replace: interactionClicks refuses them, and a cell that looks placeable but can never
            // be satisfied is the loop that broke and re-placed the same 40 trapdoors for a whole run.
            if (desired.hasProperty(BlockStateProperties.OPEN)
                    && desired.getValue(BlockStateProperties.OPEN)
                    && PlacementGeometry.beyondOurControl(desired.getBlock(), "open")) {
                lockedOpen++;
            }
            if (FluidPlan.sourceBucketFor(desired) != null) {
                fillableSources++;
            } else if (FluidPlan.wantsWaterlogging(desired)) {
                waterlogged++;
            } else if (FluidPlan.isUnbuildableFluid(desired)) {
                unbuildableFluid++;
            }
            if (SignNbt.isSign(view.blockEntity(cell))) {
                signs++;
            }
            // A chest half whose schematic partner is not the other half of one double chest vanilla could build:
            // nothing there at all, a different block, a different facing, or both halves claiming the same side. The
            // planner does NOT downgrade these to type=single (SchematicView.placementTarget withholds it), so they
            // stay blocked -- and this line is the difference between "the click would land a state the acceptance
            // rules refuse", which reads like an engine limit, and the truth, which is that the file asks for
            // something the game cannot make.
            BlockPos chestPartner = PlacementFamilies.chestPairPartner(desired, cell);
            if (chestPartner != null
                    && !PlacementFamilies.chestPairConsistent(desired, view.desired(chestPartner))) {
                impossiblePairs++;
            }
        }
        List<String> exceptions = new ArrayList<>();
        if (impossiblePairs > 0) {
            exceptions.add(impossiblePairs + " chest half/halves whose schematic partner is not the matching half of a "
                    + "double chest (nothing there, a different block, a different facing, or the same side claimed "
                    + "twice); vanilla cannot pair those, so they are reported as blockers rather than built wrong");
        }
        if (lockedOpen > 0) {
            exceptions.add(lockedOpen + " iron door(s)/trapdoor(s) want open=true; only redstone moves those, so they "
                    + "are built closed and are correct in every other property");
        }
        if (fillableSources > 0) {
            exceptions.add(fillableSources + " water/lava source cell(s) are filled with a bucket, which the run must be "
                    + "carrying; every emptying costs a refill trip the plan does not route");
        }
        if (waterlogged > 0) {
            exceptions.add(waterlogged + " waterlogged block(s) are planned as exact place-then-bucket pairs; each "
                    + "pair consumes one non-stackable water bucket and is credited only after the wet state lands");
        }
        if (unbuildableFluid > 0) {
            exceptions.add(unbuildableFluid + " fluid cell(s) cannot be placed at all: flowing levels re-derive "
                    + "themselves from their source and bubble columns follow from the block beneath them");
        }
        if (signs > 0 && !view.carriesBlockEntities()) {
            exceptions.add(signs + " sign(s) will be placed blank: this schematic format carries no block-entity data");
        }
        return exceptions;
    }
}
