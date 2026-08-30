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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;
import princeps.pathing.movement.MovementHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Helper blocks: where one may stand, what it buys, and the removal that is planned in the same breath as the
 * placement.
 *
 * <h2>Under a hard layer bound, scaffold is not an emergency — it is the only exit</h2>
 *
 * <p>Layer L+1 may not be started early. So a cell in layer L whose click face belongs to a neighbour in L+1 has no
 * legal neighbour to click, and no reordering inside L can produce one. Scaffold is the answer, and because the
 * planner decides it in the dry run rather than the executor discovering it at runtime, it is a planned action type
 * with a proof, not a rescue path with a backoff timer. That is the whole difference between this and V2's
 * "place something and hope": V2's emergency placements went down with whatever facing the bot happened to be
 * looking with, and every wrongly rotated piston in the finished world came from one.
 *
 * <h2>The five rules, and what each one is scar tissue from</h2>
 *
 * <ol>
 *   <li><b>Only the reserved throwaway block</b>, of a type that appears in NO schematic cell. Using a schematic
 *       block as scaffold is how a helper block became a finished cell in the wrong orientation and was never
 *       revisited, because the audit could not tell it from a real one.</li>
 *   <li><b>A schematic cell may hold scaffold only when its real block belongs to a LATER layer.</b> Never the
 *       current layer, never a finished one. This rule was too strict in the plan's first draft; under the hard layer
 *       bound it is the NORMAL case — the neighbour from L+1 is stood in for by a helper block, clicked, and taken
 *       back out, which is exactly what the upward-look family does 592 times.</li>
 *   <li><b>Never structural support.</b> If any schematic block would need the helper block to satisfy
 *       {@code canSurvive} — torch, repeater, wire, carpet, door, rail — or would rest on it — sand, gravel, concrete
 *       powder, anvil — it falls when the helper block comes out. Those cells get a different click face or a
 *       different order; they never get scaffold.</li>
 *   <li><b>Every helper block emits its own removal into the same plan</b>, with its own stance, aim and
 *       confirmation, ordered in reverse placement order before the layer bound. The layer is not finished while a
 *       helper block stands, and the bench audit counts leftovers — it must be 0.</li>
 *   <li><b>Lossy removals are booked.</b> Glass drops nothing. The ledger reports the extra material need in the plan
 *       report rather than letting the build discover it two thousand cells later.</li>
 * </ol>
 *
 * <h2>Why invariant S is nearly free here</h2>
 *
 * <p>While layer L is being built, every layer above L+1 is empty. A helper block on L+1 therefore has a clear air
 * column over it, and the bot can break it standing on L from above or from the side inside the 4.5 reach. Being
 * walled in horizontally on its own layer does not block access from above. Only two cases break that assumption and
 * only those two are actually checked: a helper block at or below L, which its own layer can wall in, and
 * pre-existing terrain above L+1, which is a fact about the real world rather than about the plan and is checked
 * against the snapshot.
 *
 * <h2>Where the removal is emitted, and why not at the layer gate</h2>
 *
 * <p>A helper block that exists only to be clicked is finished the moment the cell it serves stands, and
 * {@link #escalate} therefore emits its {@link BuildAction.RemoveScaffold} directly behind that cell's own actions —
 * the {@code PLACE_SCAFFOLD -> PLACE -> REMOVE_SCAFFOLD} triple of plan 5.7.2, all three from one stance with no walk
 * between them. That is not a stylistic choice. A click surface at {@code T+UP} sits in layer L+1, which is exactly
 * the head room of every stance that builds the REST of layer L: leaving 448 of them standing until the layer gate
 * would make most of the remaining layer unplaceable, and would demand 448 simultaneous units of the throwaway item
 * the material ledger reports as a peak. The layer gate ({@link #closeLayer}) still exists and still owns every helper
 * block whose removal cannot be immediate — above all the stepping stone of {@link UpwardLook}, which the bot is
 * standing ON while it works, and which therefore cannot be broken until it has walked away.
 */
public final class ScaffoldPlanner {

    /** Cardinal order for the conservative no-mutation reachability proof. Fixed for byte-identical plans. */
    private static final Direction[] ACCESS_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** Same-height first, then the two vanilla one-block step choices. */
    private static final int[] ACCESS_STEP_Y = {0, 1, -1};

    /** A bounded flood is a proof when it reaches the target and a conservative "unknown" when it does not. */
    private static final int ACCESS_FLOOD_BUDGET = 65_536;

    /** Horizontal window in which one temporary step may be placed around the last proven stance. */
    private static final int ACCESS_SCAFFOLD_RADIUS = 3;

    /**
     * The open helper blocks of the current layer, in placement order.
     *
     * <p>A list rather than a set, and the order is the contract: removal happens in reverse placement order, because
     * a helper block placed to reach another helper block has to come out first or the second one is unreachable.
     * That is not hypothetical — the upward family's stepping stone on y-2 exists to reach a stance from which the
     * helper block on T+UP is placed.
     */
    public static final class Ledger {

        /** Still standing, in placement order. An {@link ArrayList} and never a set, for the reason in the class
         *  javadoc: the ORDER is the contract, and a set has none. */
        private final List<BlockPos> open = new ArrayList<>();

        /** Every helper block this layer has held, in placement order — including the ones already removed, because
         *  {@link PlanReport.LayerSummary} reports what the layer COST and an emptied ledger costs nothing. */
        private final List<PlanReport.ScaffoldNote> notes = new ArrayList<>();

        private int placedTotal;

        public Ledger() {
        }

        /** Record a helper block. {@code occupiesSchematicLayer} is {@link PlanReport.ScaffoldNote#NO_SCHEMATIC_CELL}
         *  when it stands in empty space, and a layer Y strictly greater than the current one otherwise — rule 2. */
        public void place(BlockPos pos, BlockPos serves, int occupiesSchematicLayer) {
            Objects.requireNonNull(pos, "pos");
            if (indexOf(pos) >= 0) {
                // Two placements at one position without a removal between them means the second one is a click on a
                // cell that is already full: the server refuses it, the executor waits for a confirmation that cannot
                // come, and the ledger loses track of which of the two the removal belongs to. Refused at plan time,
                // where it costs a sentence.
                throw new IllegalStateException("a helper block already stands at " + BuildAction.describePos(pos)
                        + "; placing a second one would leave the first unaccounted for");
            }
            this.open.add(pos);
            this.notes.add(new PlanReport.ScaffoldNote(pos, serves, occupiesSchematicLayer));
            this.placedTotal++;
        }

        /** Mark one as removed. Called by the layer gate as it emits the removals, so that a partial gate leaves an
         *  accurate ledger rather than an empty one. */
        public void remove(BlockPos pos) {
            int at = indexOf(pos);
            if (at < 0) {
                // A removal of a block the ledger does not own is a plan that breaks something it did not place, and
                // the only candidates in reach are schematic blocks. Loud, not silent.
                throw new IllegalStateException("no helper block is booked at " + BuildAction.describePos(pos)
                        + "; a removal here would break a block the plan does not own");
            }
            this.open.remove(at);
        }

        /** Still standing, in placement order. */
        public List<BlockPos> open() {
            // A copy, not a view. The layer gate walks this list while calling remove() for each entry, and a live
            // view would throw ConcurrentModificationException in the middle of the one path that reports leftovers.
            return List.copyOf(this.open);
        }

        /** Still standing, in the order they must come out — {@link #open()} reversed. */
        public List<BlockPos> removalOrder() {
            List<BlockPos> reversed = new ArrayList<>(this.open);
            java.util.Collections.reverse(reversed);
            return List.copyOf(reversed);
        }

        /** The report lines: {@code scaffold 112,-56,93 (schematic cell of layer -56) serves 112,-57,93}. Six of
         *  these is a footnote; six hundred is a design problem, and the point of printing them all is that the
         *  difference is visible before the build runs rather than after it. */
        public List<PlanReport.ScaffoldNote> notes() {
            return List.copyOf(this.notes);
        }

        public Optional<PlanReport.ScaffoldNote> noteFor(BlockPos pos) {
            if (pos == null) {
                return Optional.empty();
            }
            // Backwards: a position may have held several helper blocks over one layer, and the interesting one is
            // always the most recent — the one that is standing now, or the one that came out last.
            long key = PlacementGeometry.positionKey(pos);
            for (int index = this.notes.size() - 1; index >= 0; index--) {
                if (PlacementGeometry.positionKey(this.notes.get(index).scaffold()) == key) {
                    return Optional.of(this.notes.get(index));
                }
            }
            return Optional.empty();
        }

        public boolean holds(BlockPos pos) {
            return indexOf(pos) >= 0;
        }

        public boolean isEmpty() {
            return this.open.isEmpty();
        }

        public int size() {
            return this.open.size();
        }

        /** How many helper blocks this ledger has EVER held, across the layer — the per-layer figure in
         *  {@link BuildPlan.Counts#scaffoldByLayer}, which {@link #size} cannot give once removals have run. */
        public int placedTotal() {
            return this.placedTotal;
        }

        /** Reset for the next layer. Refuses a non-empty ledger: an empty ledger at the layer bound is the invariant,
         *  and clearing one silently is how a leftover helper block becomes a permanent block in the finished world
         *  that no audit can attribute to anything. */
        public void closeLayer() {
            if (!this.open.isEmpty()) {
                throw new IllegalStateException(this.open.size() + " helper block(s) still standing at the layer "
                        + "bound, first at " + BuildAction.describePos(this.open.get(0))
                        + "; the layer cannot close and clearing the ledger would hide them");
            }
            this.notes.clear();
            this.placedTotal = 0;
        }

        /** By coordinate value, never by {@code equals}: a {@code BetterBlockPos} hashes and compares differently from
         *  a plain {@code BlockPos} with the same coordinates (trap 1.14), and both reach this class. */
        private int indexOf(BlockPos pos) {
            if (pos == null) {
                return -1;
            }
            long key = PlacementGeometry.positionKey(pos);
            for (int index = 0; index < this.open.size(); index++) {
                if (PlacementGeometry.positionKey(this.open.get(index)) == key) {
                    return index;
                }
            }
            return -1;
        }
    }

    /**
     * One escalation: the cell that was stuck, the solution scaffold unlocked, and the actions in execution order.
     *
     * @param actions {@code PLACE_SCAFFOLD...}, then the cell's own actions, then the removals of every helper block
     *                that is finished the moment the cell stands. A helper block the bot is standing on — the upward
     *                family's stepping stone — has no removal here and is left to the layer gate, which is the one
     *                place reverse order across the whole layer is decided
     */
    public record Escalation(BlockPos cell, PlacementSolution solution, List<PlacementSolution> scaffolds,
                             List<BuildAction> actions) {

        public Escalation {
            scaffolds = List.copyOf(scaffolds);
            actions = List.copyOf(actions);
        }
    }

    /** The three exhaustive answers to "can the body reach this already-proven placement stance?" */
    public enum AccessKind {
        DIRECT,
        VIA_SCAFFOLD,
        UNREACHABLE
    }

    /**
     * A route decision for one target placement.
     *
     * <p>{@link AccessKind#DIRECT} carries the original target, {@link AccessKind#VIA_SCAFFOLD} carries the target
     * re-proven in the staged helper world, and {@link AccessKind#UNREACHABLE} carries the original target so the
     * order planner can defer precisely that triple until some other action changes the world. A navigation scaffold
     * whose removal stance is reachable with the helper standing and remains connected to the target after it is gone
     * carries that cleanup in this value. The order planner can then travel onto the upper component, remove the stair,
     * and execute the original target proof in the original helper-free world. When that early cleanup is not provable
     * the scaffold remains owned by the layer ledger, and {@link #closeLayer} retains the final responsibility.
     */
    public record Access(AccessKind kind, PlacementSolution target, PlacementSolution scaffold,
                         BuildAction.RemoveScaffold cleanup) {

        public Access {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(target, "target");
            if ((kind == AccessKind.VIA_SCAFFOLD) != (scaffold != null)) {
                throw new IllegalArgumentException("only VIA_SCAFFOLD carries a scaffold placement");
            }
            if (cleanup != null && kind != AccessKind.VIA_SCAFFOLD) {
                throw new IllegalArgumentException("only VIA_SCAFFOLD may carry navigation cleanup");
            }
        }

        private static Access direct(PlacementSolution target) {
            return new Access(AccessKind.DIRECT, target, null, null);
        }

        private static Access viaScaffold(PlacementSolution scaffold, PlacementSolution target,
                                          BuildAction.RemoveScaffold cleanup) {
            return new Access(AccessKind.VIA_SCAFFOLD, target, scaffold, cleanup);
        }

        private static Access unreachable(PlacementSolution target) {
            return new Access(AccessKind.UNREACHABLE, target, null, null);
        }
    }

    /** Why a candidate position may not hold a helper block. Named per case because the report distinguishes
     *  "the schematic wants a block there in THIS layer" from "something would fall", and the fixes differ. */
    public enum Refusal {
        NONE,
        NOT_REPLACEABLE,
        SCHEMATIC_CELL_OF_CURRENT_OR_EARLIER_LAYER,
        WOULD_BE_STRUCTURAL_SUPPORT,
        CARRIES_A_GRAVITY_BLOCK,
        NOT_REMOVABLE_LATER,
        OUT_OF_BOUNDS
    }

    /**
     * The faces of a helper block the removal search tries, in order.
     *
     * <p>UP first because the normal removal stance is beside and above the block — under the hard layer bound every
     * layer over L+1 is empty, so the top face is the one that is reliably clear. DOWN is last: it needs a stance
     * below the block, which for a helper block at {@code T+UP} means the foot column the placement used.
     */
    private static final Direction[] REMOVAL_FACES = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    /** Horizontal half-width of the removal search's stance window, matching the oracle's placement window so that a
     *  helper block that could be placed can normally also be broken. */
    private static final int REMOVAL_STANCE_RADIUS = 3;

    /** Lowest and highest removal stance relative to the helper block. Two down covers breaking the click surface at
     *  {@code T+UP} from the upward family's foot column at {@code T.y-1}; two up covers taking a navigation stair
     *  back from the upper surface it just reached. Every candidate still passes the ordinary reach and ray proof. */
    private static final int REMOVAL_STANCE_LOWEST = -2;
    private static final int REMOVAL_STANCE_HIGHEST = 2;

    /**
     * The yaw every rotation in this class is wrapped relative to — the same fixed reference {@link PlacementOracle}
     * uses, and it has to be the same one. A plan is proven hours before the bot stands there, so there is no current
     * yaw to keep the turn short against; a fixed reference makes the stored angle canonical, so two runs of the same
     * build write the same number into {@code plan.txt} instead of two representatives of one angle.
     */
    private static final Rotation PLANNING_REFERENCE = new Rotation(0.0F, 0.0F);

    private final PlacementOracle oracle;
    private final V3Settings settings;
    private final SolveBudget budget;
    private final Item scaffoldItem;
    private final int scaffoldSlot;

    /** The state the helper block lands in, derived once. {@code null} when no throwaway could be named, which makes
     *  every escalation return empty rather than sprinkling a null check through eight methods. */
    private final BlockState scaffoldState;

    /**
     * An inventory holding NOTHING but the helper block. Handed to the oracle for helper-block placements so that a
     * scaffold solve resolves the HELPER item and cannot wander onto a schematic material that happens to place the
     * same state — which is scaffold rule 1 (a helper block's type appears in no schematic cell) enforced through the
     * oracle's own material search rather than checked afterwards.
     *
     * <p>The slot it sits in is no longer load-bearing: {@link PlacementSolution} carries the item and
     * {@link HotbarSchedule#weave} assigns the hand slot from the schedule. It is kept at {@link #scaffoldSlot} so the
     * list still reads as the bar the bot will have.
     *
     * <p>Built lazily, and that is the one non-final field in this class. {@code new ItemStack(item)} throws
     * {@code NullPointerException: Components not bound yet} in a JVM that has only run {@code Bootstrap.bootStrap()},
     * so building it in the constructor would make the whole class — including {@link #eligibility},
     * {@link #isStructuralSupport}, {@link #removalDrops} and {@link Ledger}, none of which touch an item —
     * unconstructible from a unit test, for exactly the reason {@code Princeps.settings()} is (trap 1.7). Anything
     * that actually needs the stack needs the oracle too, and that is untestable headless regardless.
     */
    private List<ItemStack> scaffoldHotbar;

    /**
     * @param scaffoldItem must be an acceptable throwaway AND absent from every schematic cell. Both halves matter:
     *                     absent from the schematic so the audit can tell helper blocks from real ones, and an
     *                     acceptable throwaway because {@code InventoryBehavior} owns
     *                     {@link HotbarSchedule#THROWAWAY_SLOT} and will evict anything else from it every tick
     */
    public ScaffoldPlanner(PlacementOracle oracle, V3Settings settings, SolveBudget budget, Item scaffoldItem,
                           int scaffoldSlot) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.scaffoldItem = scaffoldItem;
        this.scaffoldSlot = scaffoldSlot;
        this.scaffoldState = helperState(scaffoldItem);
    }

    /** @see #scaffoldHotbar */
    private List<ItemStack> scaffoldHotbar() {
        if (this.scaffoldHotbar == null) {
            this.scaffoldHotbar = helperHotbar(this.scaffoldItem, this.scaffoldSlot);
        }
        return this.scaffoldHotbar;
    }

    /**
     * Pick a throwaway type that appears in no schematic cell and that the inventory actually holds.
     *
     * <p>Returns empty when every candidate is also a schematic material — a real possibility for a cobblestone
     * schematic, and one that must be a named limitation in the report rather than a silent fallback to "use it
     * anyway", because using it anyway is precisely rule 1's failure mode.
     */
    public static Optional<Item> chooseScaffoldItem(SchematicView view, HotbarSchedule.InventorySnapshot inventory,
                                                    List<Item> acceptableThrowaways) {
        if (view == null || inventory == null || acceptableThrowaways == null) {
            return Optional.empty();
        }
        // The caller's order is the preference order and is preserved exactly — it is Settings.acceptableThrowawayItems,
        // which the user wrote down. Re-sorting it here would silently overrule that, and any sort of ours would have
        // to be deterministic anyway, so the list is simply walked.
        for (Item candidate : acceptableThrowaways) {
            if (candidate == null || !(candidate instanceof BlockItem) || inventory.count(candidate) <= 0) {
                continue;
            }
            if (!appearsInSchematic(view, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------- the escalation

    /**
     * The 5.1 escalation branch: nothing in {@code todo} is solvable as the world stands, so find the one cell that
     * scaffold unlocks most cheaply and return it with its helper blocks.
     *
     * <p>Cheapest first, and cheap means FEWEST HELPER BLOCKS, then the order {@code todo} already carries. That
     * second half is a deliberate narrowing of the skeleton's "then highest margin then lexicographic": {@code todo}
     * arrives pre-sorted by {@link UpwardLook#layerOrder} and then lexicographically, which IS the plan's own
     * preference order, so following it makes an escalated cell take the same place in the plan it would have taken
     * had it needed no scaffold. Ranking cells against each other by MARGIN would do the opposite — it would let a
     * hundredth of a block of geometry decide which cell comes next, and the margin's job is to rank the solutions of
     * ONE cell, which the oracle already does.
     *
     * <p>The scan stops at the first cell that needs a single helper block, because one is the floor: the frontier was
     * empty, so no cell in {@code todo} is solvable with none.
     *
     * @return empty when scaffold cannot unlock anything either; the layer is then a named blocker and the planner
     *         stops rather than dropping cells
     */
    public Optional<Escalation> escalate(PredictedWorld world, OrderPlanner.PlannerState state, List<BlockPos> todo) {
        if (this.scaffoldState == null || todo == null || todo.isEmpty()) {
            return Optional.empty();
        }
        Escalation best = null;
        for (BlockPos cell : todo) {
            // The target the main solve was refused on, so escalation searches for the placement that actually exists.
            BlockState desired = state.view().placementTarget(world, cell);
            if (desired == null || desired.isAir() || state.view().satisfied(world, this.settings, cell)) {
                continue;
            }
            Escalation candidate = UpwardLook.applies(desired)
                    ? this.upwardEscalation(world, state, cell, desired)
                    : this.lateralEscalation(world, state, cell, desired);
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.scaffolds().size() < best.scaffolds().size()) {
                best = candidate;
            }
            if (best.scaffolds().size() <= 1) {
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Prove one helper block placement: it is eligible, it is reachable, and it will come back out.
     *
     * <p>The removal is proven HERE, at placement time, not deferred to the layer gate. A helper block whose removal
     * turns out to be impossible at the gate is a violated layer bound with no legal recovery — the plan would have
     * to be abandoned mid-layer, which is the one outcome the dry run exists to make impossible.
     */
    public Optional<PlacementSolution> planScaffoldAt(PredictedWorld world, OrderPlanner.PlannerState state,
                                                      BlockPos scaffoldCell, BlockPos serves) {
        if (this.scaffoldState == null
                || eligibilityOf(world, state.view(), scaffoldCell, state.currentLayer()) != Refusal.NONE) {
            return Optional.empty();
        }
        Optional<PlacementSolution> placement = helperPlacement(this.oracle, world, this.budget, scaffoldCell,
                this.scaffoldState, this.scaffoldHotbar(), state.journal());
        if (placement.isEmpty()) {
            return Optional.empty();
        }
        // Ask against a world in which the helper really stands. An empty cell has no collision bit and can make an
        // impossible removal look visible; staging the exact predicted state makes this a proof of a block, not a
        // proof of the air that preceded it. The served cell is staged by the composing escalation and re-proven there.
        Scratch scratch = new Scratch(world);
        try {
            scratch.apply(scaffoldCell, placement.get().predicted());
            if (removalOf(world, this.oracle.pose(), this.budget, scaffoldCell, placement.get().predicted(),
                    placement.get().stance(), state.currentLayer()).isEmpty()) {
                return Optional.empty();
            }
        } finally {
            scratch.restore();
        }
        return placement;
    }

    /**
     * Plan one temporary stair when a higher stance has no route that preserves the predicted world.
     *
     * <p>This is navigation scaffold, not placement scaffold: it buys no click face. The search therefore proves
     * three separate facts in execution order:
     *
     * <ol>
     *   <li>the helper itself can be placed from a stance reachable without any mutation;</li>
     *   <li>with that exact helper standing, a safe removal stance on the target's original walk component becomes
     *       reachable;</li>
     *   <li>after the helper is removed, the original target stance remains reachable without mutation, so the target
     *       placement executes against the exact helper-free world in which it was proved.</li>
     * </ol>
     *
     * <p>Only a single helper is considered here. Normal layer-to-layer promotion is one block at a time; a gap of
     * two means exactly one missing stair. Larger gaps are not silently improvised by the movement layer and remain a
     * named no-path instead of growing an unproved tower.
     */
    public Access accessToStance(PredictedWorld world, OrderPlanner.PlannerState state, PlacementSolution target) {
        long started = System.nanoTime();
        this.accessCalls++;
        try {
            return accessToStanceCounted(world, state, target);
        } finally {
            this.accessNanos += System.nanoTime() - started;
        }
    }

    private Access accessToStanceCounted(PredictedWorld world, OrderPlanner.PlannerState state,
                                         PlacementSolution target) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(target, "target");
        BlockPos from = state.lastStance();
        if (from == null) {
            return Access.unreachable(target);
        }
        BlockPos routeFrom = from;
        // Filled at most once per call, and only on the in-snapshot path. Declared here because the loop below needs
        // it; forced inside the branch because on the initial-anchor path routeFrom is not from, and flooding from a
        // start outside the snapshot returns the empty set -- which would then reject every helper at the membership
        // test and turn a reachable target into UNREACHABLE.
        LongOpenHashSet routeComponent = null;
        if (!world.inBounds(from.getX(), from.getY(), from.getZ())) {
            // The snapshot deliberately covers the build plus eight blocks, not a ten-thousand-block trip to it.
            // Only the very first anchor may therefore enter through a boundary-connected stance. Once one planned
            // action exists, every anchor is a proved in-snapshot stance and an outside value is an invariant failure.
            if (!state.isInitialRouteAnchor()) {
                return Access.unreachable(target);
            }
            LongOpenHashSet exterior = reachableStancesFromBoundary(world);
            if (exterior.contains(PlacementGeometry.positionKey(target.stance()))) {
                return Access.direct(target);
            }
            routeFrom = nearestExteriorAnchor(world, exterior, target.stance());
            if (routeFrom == null) {
                return Access.unreachable(target);
            }
        } else if (from.equals(target.stance()) && accessStandable(world, from)) {
            // Exactly the reflexive branch of reaches, and exactly as cheap: it answers without flooding at all. On
            // the bottom basalt layer this is 690 of 936 calls -- the body is already standing where the next cell is
            // clicked from. Losing it is not a small regression, it is THE regression: OrderPlanner :1086-1091
            // measured a version that flooded here instead, at 936 floods over 5.8M nodes against 246 over 1.5M, and
            // the layer went from 6.2s to 15.4s.
            //
            // The guard is not decoration. reaches checks inBounds and accessStandable on BOTH sides before it looks
            // at the equality, and accessStandable tests inBounds of the stance and of the block above it, so this
            // one call is the whole conjunction. Reading the equality first would answer DIRECT for a body that
            // cannot occupy the stance at all -- and nothing downstream catches that, because turning an UNREACHABLE
            // into a DIRECT makes the layer's cell count RISE, while the dry-run guard only refuses a count that falls.
            return Access.direct(target);
        } else {
            // Same question reaches asks, answered off the component instead of by a second flood of the same start.
            routeComponent = reachableFrom(world, from);
            if (within(routeComponent, target.stance())) {
                return Access.direct(target);
            }
        }

        // Both of these used to be computed unconditionally, and on the path that dominates the expensive layers
        // neither was needed: measured over 66 stall windows of layer -58, every non-direct call cost exactly three
        // floods -- this DIRECT probe, the target component, and the route component -- and not one helper candidate
        // survived its guards, so the target component was flooded and then never read. Its only reader is the
        // removal predicate below, which needs a helper that got that far.
        LongOpenHashSet targetComponent = null;
        if (routeComponent == null) {
            routeComponent = reachableFrom(world, routeFrom);
        }
        List<BlockPos> helpers = accessHelperCandidates(world, routeFrom, target.stance());
        for (BlockPos helper : helpers) {
            Optional<PlacementSolution> placed = this.planScaffoldAt(world, state, helper, target.cell());
            if (placed.isEmpty()
                    || !within(routeComponent, placed.get().stance())
                    || Invariants.explain(world, state, placed.get()).isPresent()) {
                continue;
            }
            // Forced here and nowhere later: this is still the UNSTAGED world -- the Scratch below is applied after
            // this point and restored in its finally, planScaffoldAt balances its own, and the speculative
            // apply/revert inside Invariants.explain is balanced too. One line further down, after scratch.apply, it
            // would flood the STAGED world and the removal predicate would be proved against a world with the helper
            // still standing in it. That does not crash; it quietly proves the wrong thing.
            if (targetComponent == null) {
                targetComponent = reachableFrom(world, target.stance());
            }
            LongOpenHashSet reachedWithoutHelper = targetComponent;

            Scratch scratch = new Scratch(world);
            try {
                scratch.apply(helper, placed.get().predicted());
                // One flood for both questions. These were two calls from the same start through the same staged
                // world -- the reachability test and the component the removal is proved against -- and the second
                // recomputed exactly what the first had just thrown away.
                LongOpenHashSet withHelper = reachableFrom(world, placed.get().stance());
                if (!within(withHelper, target.stance())) {
                    continue;
                }
                BlockState occupant = world.get(target.cell());
                if (!occupant.canBeReplaced()) {
                    // An access helper must not smuggle an unowned break into the target transaction. The ordinary
                    // BREAK action remains the only owner of a non-replaceable schematic occupant.
                    continue;
                }
                Optional<BuildAction.RemoveScaffold> cleanup = removalOf(world, this.oracle.pose(), this.budget,
                        helper, placed.get().predicted(), target.stance(), state.currentLayer(),
                        stance -> withHelper.contains(PlacementGeometry.positionKey(stance))
                                && reachedWithoutHelper.contains(PlacementGeometry.positionKey(stance)));
                if (cleanup.isPresent()) {
                    return Access.viaScaffold(placed.get(), target, cleanup.get());
                }

                // Fallback for a helper that really has to stay: the target is re-proved in the staged world and the
                // ledger keeps ownership until a later cleanup point (ultimately the hard layer gate).
                PlacementOracle.Solve solved = state.oracle().solveFrom(world, target.cell(), target.desired(),
                        state.stacks(), state.budget(), target.stance(), state.journal());
                state.addRays(solved.raysCast());
                PlacementSolution reproved = solved.best().orElse(null);
                if (reproved == null || Invariants.explain(world, state, reproved).isPresent()) {
                    continue;
                }
                return Access.viaScaffold(placed.get(), reproved, null);
            } finally {
                scratch.restore();
            }
        }
        return Access.unreachable(target);
    }

    /** Does a cardinal, at-most-one-block step walk connect two stances without any world mutation? */
    static boolean stanceReachableWithoutMutation(PredictedWorld world, BlockPos start, BlockPos target) {
        if (world == null || start == null || target == null
                || !world.inBounds(start.getX(), start.getY(), start.getZ())
                || !world.inBounds(target.getX(), target.getY(), target.getZ())
                || !accessStandable(world, start)
                || !accessStandable(world, target)) {
            return false;
        }
        if (start.equals(target)) {
            return true;
        }
        return reachableStancesWithoutMutation(world, start).contains(PlacementGeometry.positionKey(target));
    }

    /**
     * Cost census, read per layer by {@link OrderPlanner} and printed beside the layer's timing.
     *
     * <p>It exists because the alternative was an afternoon of thread dumps. A five-minute layer is indistinguishable
     * from a hung one in the log, and the answer -- 27 of 30 samples inside {@code accessToStance}, and inside that
     * the flood -- had to be sampled off a live client with jstack. These five numbers say it in one line.
     *
     * <p>Plain longs, no synchronisation: the planner runs on the game thread and the dry run is one thread.
     */
    private long accessCalls;
    private long accessNanos;
    private long floodCalls;
    private long floodNodes;
    private long floodNanos;

    /** {@code accessCalls, accessNanos, floodCalls, floodNodes, floodNanos}. */
    long[] costCensus() {
        return new long[] {this.accessCalls, this.accessNanos, this.floodCalls, this.floodNodes, this.floodNanos};
    }

    /**
     * The counted form of {@link #reachableStancesWithoutMutation}.
     *
     * <p>This was a memo, keyed on the world's write counter, and it is not one any more: measured against the real
     * basalt plan it scored ZERO hits. The reason is worth leaving here so nobody rebuilds it. Every candidate that
     * reaches this code has just had a speculative apply-and-revert run through {@code Invariants.explain}, and a
     * balanced pair still moves any honest write counter twice — so the key differed on every single lookup while the
     * content did not. The saving that was actually available is not a cache at all but the hoist in
     * {@code accessToStance}: the same flood, from a start that does not move, through a world that does not change.
     */
    private LongOpenHashSet reachableFrom(PredictedWorld world, BlockPos start) {
        long started = System.nanoTime();
        LongOpenHashSet component = reachableStancesWithoutMutation(world, start);
        this.floodCalls++;
        this.floodNodes += component.size();
        this.floodNanos += System.nanoTime() - started;
        return component;
    }

    /**
     * Membership in an already-computed component, which is exactly what {@link #reaches} reduces to.
     *
     * <p>Not a weaker test: the flood only ever admits a position that is in bounds and access-standable, and it
     * seeds itself with its own start only after the same two guards, so an empty component answers no for every
     * question including the reflexive one — which is what {@code reaches} does too.
     */
    private static boolean within(LongOpenHashSet component, BlockPos stance) {
        return component.contains(PlacementGeometry.positionKey(stance));
    }

    /** The counted form of {@link #stanceReachableWithoutMutation}; same guards, same answer. */
    private boolean reaches(PredictedWorld world, BlockPos start, BlockPos target) {
        if (world == null || start == null || target == null
                || !world.inBounds(start.getX(), start.getY(), start.getZ())
                || !world.inBounds(target.getX(), target.getY(), target.getZ())
                || !accessStandable(world, start)
                || !accessStandable(world, target)) {
            return false;
        }
        if (start.equals(target)) {
            return true;
        }
        return reachableFrom(world, start).contains(PlacementGeometry.positionKey(target));
    }

    /** Conservative access component in the staged world; reaching a member is the proof, exhausting it is unknown. */
    private static LongOpenHashSet reachableStancesWithoutMutation(PredictedWorld world, BlockPos start) {
        LongOpenHashSet visited = new LongOpenHashSet();
        if (world == null || start == null
                || !world.inBounds(start.getX(), start.getY(), start.getZ())
                || !accessStandable(world, start)) {
            return visited;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        visited.add(PlacementGeometry.positionKey(start));
        floodReachableStances(world, queue, visited);
        return visited;
    }

    /**
     * The in-snapshot component an initial, out-of-snapshot player may enter without asking the planner to copy the
     * entire journey. Horizontal boundary stances are the only entry portals; the bounded flood proves everything
     * after that portal and the ordinary pathfinder remains responsible for reaching it from the real initial body.
     */
    private static LongOpenHashSet reachableStancesFromBoundary(PredictedWorld world) {
        LongOpenHashSet visited = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (int y = world.minY(); y <= world.maxY(); y++) {
            for (int x = world.minX(); x <= world.maxX(); x++) {
                seedBoundaryStance(world, new BlockPos(x, y, world.minZ()), queue, visited);
                if (world.maxZ() != world.minZ()) {
                    seedBoundaryStance(world, new BlockPos(x, y, world.maxZ()), queue, visited);
                }
            }
            for (int z = world.minZ() + 1; z < world.maxZ(); z++) {
                seedBoundaryStance(world, new BlockPos(world.minX(), y, z), queue, visited);
                if (world.maxX() != world.minX()) {
                    seedBoundaryStance(world, new BlockPos(world.maxX(), y, z), queue, visited);
                }
            }
        }
        floodReachableStances(world, queue, visited);
        return visited;
    }

    private static void seedBoundaryStance(PredictedWorld world, BlockPos stance, ArrayDeque<BlockPos> queue,
                                           LongOpenHashSet visited) {
        long key = PlacementGeometry.positionKey(stance);
        if (accessStandable(world, stance) && visited.add(key)) {
            queue.addLast(stance);
        }
    }

    private static void floodReachableStances(PredictedWorld world, ArrayDeque<BlockPos> queue,
                                              LongOpenHashSet visited) {
        int examined = 0;
        while (!queue.isEmpty() && examined++ < ACCESS_FLOOD_BUDGET) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : ACCESS_DIRECTIONS) {
                for (int dy : ACCESS_STEP_Y) {
                    BlockPos next = current.relative(direction).offset(0, dy, 0);
                    if (!world.inBounds(next.getX(), next.getY(), next.getZ())
                            || !accessStandable(world, next)) {
                        continue;
                    }
                    long key = PlacementGeometry.positionKey(next);
                    if (!visited.add(key)) {
                        continue;
                    }
                    queue.addLast(next.immutable());
                }
            }
        }
    }

    /**
     * Deterministic nearby anchor in the exterior component from which one helper could bridge to the target.
     * Looking farther would imply a multi-helper access structure, which this planner deliberately does not improvise.
     */
    private static BlockPos nearestExteriorAnchor(PredictedWorld world, LongOpenHashSet exterior, BlockPos target) {
        BlockPos best = null;
        long bestDistance = Long.MAX_VALUE;
        int radius = ACCESS_SCAFFOLD_RADIUS * 2;
        for (int y = target.getY() - 2; y <= target.getY() + 2; y++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    BlockPos candidate = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
                    if (!world.inBounds(candidate.getX(), candidate.getY(), candidate.getZ())
                            || !exterior.contains(PlacementGeometry.positionKey(candidate))) {
                        continue;
                    }
                    long distance = squaredDistance(candidate, target);
                    if (distance < bestDistance || distance == bestDistance
                            && (best == null || PlacementGeometry.positionKey(candidate)
                            < PlacementGeometry.positionKey(best))) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The planner's route proof must use the builder's no-door/no-gate policy, not the state-only walk predicate
     * which calls those blocks passable on the promise that a movement will operate them.
     */
    private static boolean accessStandable(PredictedWorld world, BlockPos stance) {
        return world.inBounds(stance.getX(), stance.getY(), stance.getZ())
                && world.inBounds(stance.getX(), stance.getY() + 1, stance.getZ())
                && world.isStandable(stance.getX(), stance.getY(), stance.getZ())
                && !MovementHelper.isPathingBarrier(world.get(stance))
                && !MovementHelper.isPathingBarrier(world.get(stance.above()));
    }

    /** Deterministic one-step candidates, nearest and lowest first. */
    private List<BlockPos> accessHelperCandidates(PredictedWorld world, BlockPos from, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        int lowest = Math.min(from.getY() - 1, target.getY() - 1);
        int highest = Math.max(from.getY() - 1, target.getY() - 1);
        // One helper may bridge one missing floor or stair, never improvise an unproved tower.
        if (highest - lowest > 2) {
            return candidates;
        }
        for (int y = lowest; y <= highest; y++) {
            for (int dz = -ACCESS_SCAFFOLD_RADIUS; dz <= ACCESS_SCAFFOLD_RADIUS; dz++) {
                for (int dx = -ACCESS_SCAFFOLD_RADIUS; dx <= ACCESS_SCAFFOLD_RADIUS; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(from.getX() + dx, y, from.getZ() + dz);
                    if (world.inBounds(candidate.getX(), candidate.getY(), candidate.getZ())) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - from.getY()))
                .thenComparingLong(pos -> squaredDistance(from, pos))
                .thenComparingLong(PlacementGeometry::positionKey));
        return candidates;
    }

    /** The removal action paired with a placement, proven against the world as it will stand when the gate runs —
     *  which is not the world at placement time, since the whole rest of the layer goes in between. */
    public Optional<BuildAction.RemoveScaffold> pairedRemoval(PredictedWorld world, OrderPlanner.PlannerState state,
                                                              BlockPos scaffoldCell, int layer) {
        return pairedRemoval(world, state, scaffoldCell, state.lastStance(), layer);
    }

    private Optional<BuildAction.RemoveScaffold> pairedRemoval(PredictedWorld world,
                                                               OrderPlanner.PlannerState state,
                                                               BlockPos scaffoldCell, BlockPos from, int layer) {
        BlockState standing = world.get(scaffoldCell);
        LongOpenHashSet reachable = reachableFrom(world, from);
        return removalOf(world, this.oracle.pose(), this.budget, scaffoldCell, standing, from, layer,
                stance -> reachable.contains(PlacementGeometry.positionKey(stance)));
    }

    /**
     * The layer gate of 5.1: every open helper block's removal, in reverse placement order, appended before the layer
     * advances.
     *
     * <p>Emitting these is also the moment invariant S is asserted against the world as it now stands rather than as
     * it was predicted to stand. If one is not removable the plan is INCOMPLETE with that helper block named — a
     * refusal, never a shrug.
     */
    public List<BuildAction> closeLayer(PredictedWorld world, OrderPlanner.PlannerState state, int layer) {
        List<BuildAction> removals = new ArrayList<>();
        Scratch scratch = new Scratch(world);
        BlockPos cursor = state.lastStance();
        try {
            for (BlockPos open : state.ledger().removalOrder()) {
                Optional<BuildAction.RemoveScaffold> removal = this.pairedRemoval(world, state, open, cursor, layer);
                if (removal.isPresent()) {
                    BuildAction.RemoveScaffold planned = removal.get();
                    removals.add(planned);
                    cursor = planned.stance();
                    // Emptied in the scratch world before the next one is proven, so each removal is judged against
                    // the world it will actually meet: the entries are walked in reverse placement order, so a helper
                    // block that was reached by standing on an earlier one is proven while that one still stands.
                    scratch.apply(open, Blocks.AIR.defaultBlockState());
                    continue;
                }
                state.blocked(new PlanReport.Blocker(open, world.get(open), PlacementOracle.Rejection.RAY_OBSTRUCTED,
                        new int[0], 0, List.of(),
                        "a helper block placed for layer " + layer + " has no reachable breaking stance with a clear "
                                + "line of sight, so the layer bound cannot be met; it is dropped from the ledger "
                                + "here so that this reason is reported instead of the layer gate's generic leftover"));
                state.ledger().remove(open);
            }
        } finally {
            scratch.restore();
        }
        return removals;
    }

    // ------------------------------------------------------------------- eligibility

    /** All of {@link Refusal} in one call, {@link Refusal#NONE} when the position may hold a helper block. */
    public Refusal eligibility(PredictedWorld world, SchematicView view, BlockPos pos, int currentLayer) {
        return eligibilityOf(world, view, pos, currentLayer);
    }

    /** {@code eligibility(...) == NONE}. */
    public boolean eligible(PredictedWorld world, SchematicView view, BlockPos pos, int currentLayer) {
        return eligibilityOf(world, view, pos, currentLayer) == Refusal.NONE;
    }

    /**
     * The static form of {@link #eligibility}, so that {@link UpwardLook} — which is handed the throwaway item and its
     * slot but never the planner instance — asks the same five questions in the same order rather than a second
     * opinion that drifts.
     *
     * <p>The order is the order of the rules in the class javadoc, and it is fixed so that a position refused for two
     * reasons is always reported against the same one.
     */
    static Refusal eligibilityOf(PredictedWorld world, SchematicView view, BlockPos pos, int currentLayer) {
        if (pos == null || !world.inBounds(pos.getX(), pos.getY(), pos.getZ())
                || !world.isLoaded(pos.getX(), pos.getY(), pos.getZ())) {
            // An unloaded column is folded into OUT_OF_BOUNDS rather than given its own value: both mean "the snapshot
            // cannot answer", and a helper block proven against imagined air is exactly the proof this design refuses.
            return Refusal.OUT_OF_BOUNDS;
        }
        // RemoveScaffold's proven post-state is exactly AIR; it does not restore whatever replaceable state occupied
        // the cell before the helper. Replacing water, grass, snow or another replaceable non-air state would therefore
        // be a destructive edit disguised as temporary scaffold. Only pristine live AIR is lossless.
        if (!world.get(pos).is(Blocks.AIR)) {
            return Refusal.NOT_REPLACEABLE;
        }
        if (view != null && view.wantsBlock(pos) && view.layerOf(pos) <= currentLayer) {
            // Rule 2. A cell the schematic calls AIR is NOT a schematic cell for this purpose -- that is what makes
            // the upward family's stepping stone on y-2 legal -- so the question is wantsBlock, never covers.
            return Refusal.SCHEMATIC_CELL_OF_CURRENT_OR_EARLIER_LAYER;
        }
        if (isStructuralSupport(world, view, pos)) {
            return Refusal.WOULD_BE_STRUCTURAL_SUPPORT;
        }
        if (carriesGravityBlock(world, view, pos)) {
            return Refusal.CARRIES_A_GRAVITY_BLOCK;
        }
        if (wouldBeEntombed(world, view, pos, currentLayer)) {
            return Refusal.NOT_REMOVABLE_LATER;
        }
        return Refusal.NONE;
    }

    /**
     * Rule 3, first half: would any schematic block need this position to satisfy {@code canSurvive}?
     *
     * <p>Checks the six neighbours and the cell above against
     * {@link PlacementGeometry#requiredSupportDirection} — a torch on the side, a repeater or carpet or door on top,
     * a rail. Static and pure so it can be tested against a hand-built neighbourhood, which is the only way to be
     * confident about a rule whose failure mode is a block that falls silently three minutes after the click.
     */
    public static boolean isStructuralSupport(PredictedWorld world, SchematicView view, BlockPos scaffold) {
        for (Direction side : Direction.values()) {
            BlockPos neighbour = scaffold.relative(side);
            // Both the schematic's intention and the world's fact, because they fail differently: the schematic names
            // a block that is not there yet and would be placed onto this helper block, and the world names one that
            // is already standing and would drop when it comes out.
            if (needsSupportFrom(world.get(neighbour), side)
                    || (standsBeforeRemoval(view, neighbour, scaffold)
                            && needsSupportFrom(view.desired(neighbour), side))) {
                return true;
            }
        }
        return false;
    }

    /** Rule 3, second half: does a gravity-affected block — sand, gravel, concrete powder, anvil — rest on this
     *  position, directly or through a column of them? Separate from {@link #isStructuralSupport} because
     *  {@code canSurvive} says nothing about falling blocks: they survive perfectly well and then fall. */
    public static boolean carriesGravityBlock(PredictedWorld world, SchematicView view, BlockPos scaffold) {
        BlockPos above = scaffold.above();
        // A column of sand is decided entirely by its bottom block: everything over it rests on IT, so if the cell
        // directly above is not a falling block then nothing higher can be resting on this position either -- an air
        // gap in between would already have fallen. One read, not a walk.
        return isFalling(world.get(above))
                || (standsBeforeRemoval(view, above, scaffold) && isFalling(view.desired(above)));
    }

    /**
     * Rule 5: does breaking this helper block yield its item back? False for glass and its relatives, and the input
     * to the {@link PlanReport.MaterialNeed#forReplacement} column.
     *
     * <p>Delegates to {@link BreakPlanner#drops}, which owns this rule for every {@code BREAK} in the plan. Two
     * implementations of "does this come back" is precisely the seam the ledger cannot survive: they would agree on
     * glass, disagree on ores and infested stone, and the report would print one number while the run consumed
     * another. One owner per piece of state (E-C) applies to knowledge as much as to fields, and a helper block is
     * just a break the plan happens to have placed itself.
     */
    public static boolean removalDrops(BlockState state) {
        return BreakPlanner.drops(state);
    }

    // ------------------------------------------------------------------- the two escalation shapes

    /**
     * The upward-look family's escalation: the click surface is a helper block above the target, and the three
     * actions come off one stance with no walk between them.
     *
     * <p>{@link UpwardLook#solve} proves the target and its removal against a scratch world; this method re-stages the
     * same world in the same order to prove the helper blocks THEMSELVES, which is a question {@code UpwardLook} does
     * not answer: it knows the geometry of the click, not the eligibility rules or the reserved slot.
     */
    private Escalation upwardEscalation(PredictedWorld world, OrderPlanner.PlannerState state, BlockPos cell,
                                        BlockState desired) {
        Optional<UpwardLook.Solved> maybe = UpwardLook.solve(this.oracle, world, state.view(), cell, desired,
                state.stacks(), this.budget, this.scaffoldItem, this.scaffoldSlot);
        if (maybe.isEmpty()) {
            return null;
        }
        UpwardLook.Solved solved = maybe.get();
        int layer = state.currentLayer();
        int helpers = solved.helpersInPlacementOrder().size();
        List<PlacementSolution> scaffolds = new ArrayList<>(helpers);
        List<BuildAction> actions = new ArrayList<>(2 * helpers + 2);
        Scratch scratch = new Scratch(world);
        try {
            // Placement order is world order: each helper block is proven against the world the ones before it have
            // already changed, because the stepping stone is what makes the foot column standable and each support of
            // the chain is what gives the next one -- and finally the click surface at T+UP -- something to be placed
            // against.
            for (BlockPos helper : solved.helpersInPlacementOrder()) {
                Optional<PlacementSolution> placement = this.planScaffoldAt(world, state, helper, cell);
                if (placement.isEmpty()) {
                    return null;
                }
                scaffolds.add(placement.get());
                actions.add(new BuildAction.PlaceScaffold(placement.get(), cell, true,
                        BuildAction.UNASSIGNED_SLOT, layer));
                scratch.apply(helper, this.scaffoldState);
            }
        } finally {
            scratch.restore();
        }
        actions.addAll(this.cellActions(world, state, solved.solution()));
        actions.add(solved.scaffoldRemoval());
        // The chain's own removals, already in reverse placement order and already a prefix: whatever UpwardLook could
        // not reach from this stance stays in the ledger for the layer gate.
        actions.addAll(solved.supportRemovals());
        return new Escalation(cell, solved.solution(), scaffolds, actions);
    }

    /**
     * The ordinary escalation: one helper block stands in for the neighbour from layer L+1 that the cell needs to
     * click, and comes back out the moment the cell stands.
     *
     * <p>The faces are tried in {@link PlacementGeometry#supportDirectionsFor} order, which for a state whose facing
     * comes from the look puts the face that makes the aim dominant by the full block first. The solve is narrowed to
     * the face the helper block actually provides: a solution that used some OTHER face would have been found by the
     * frontier before the escalation was reached, so accepting one here would book a helper block that bought nothing.
     */
    private Escalation lateralEscalation(PredictedWorld world, OrderPlanner.PlannerState state, BlockPos cell,
                                         BlockState desired) {
        for (Direction support : PlacementGeometry.supportDirectionsFor(desired)) {
            BlockPos against = cell.relative(support);
            Escalation single = this.singleHelperEscalation(world, state, cell, desired, against,
                    support.getOpposite());
            if (single != null) {
                return single;
            }
            Escalation paired = this.twoHelperEscalation(world, state, cell, desired, against,
                    support.getOpposite());
            if (paired != null) {
                return paired;
            }
        }
        return null;
    }

    /** The ordinary one-click-surface shape. Kept separate so the two-helper fallback proves a fresh world chain. */
    private Escalation singleHelperEscalation(PredictedWorld world, OrderPlanner.PlannerState state, BlockPos cell,
                                              BlockState desired, BlockPos against, Direction face) {
        Optional<PlacementSolution> helper = this.planScaffoldAt(world, state, against, cell);
        if (helper.isEmpty()) {
            return null;
        }
        int layer = state.currentLayer();
        Scratch scratch = new Scratch(world);
        Optional<PlacementSolution> placed;
        Optional<BuildAction.RemoveScaffold> removal = Optional.empty();
        try {
            scratch.apply(against, helper.get().predicted());
            placed = this.oracle.solve(world, cell, desired, state.stacks(), this.budget,
                    narrowed(state.journal(), cell, face)).best();
            if (placed.isPresent()) {
                scratch.apply(cell, placed.get().predicted());
                removal = removalOf(world, this.oracle.pose(), this.budget, against, helper.get().predicted(),
                        placed.get().stance(), layer);
            }
        } finally {
            scratch.restore();
        }
        if (placed.isEmpty() || removal.isEmpty()) {
            return null;
        }
        List<BuildAction> actions = new ArrayList<>(4);
        actions.add(new BuildAction.PlaceScaffold(helper.get(), cell, true, BuildAction.UNASSIGNED_SLOT, layer));
        actions.addAll(this.cellActions(world, state, placed.get()));
        actions.add(removal.get());
        return new Escalation(cell, placed.get(), List.of(helper.get()), actions);
    }

    /**
     * General lateral fallback: one helper supplies the click face and a second supplies the chosen stance's floor.
     *
     * <p>The candidate stances come from the oracle's own deterministic ordering. For each one the execution world is
     * staged in execution order (floor, click surface, target), then both removals are proved against that actual end
     * world and emitted in reverse placement order. Consequently the ordinary commit path books and clears the ledger
     * in exactly the same order as the geometry proof.
     */
    private Escalation twoHelperEscalation(PredictedWorld world, OrderPlanner.PlannerState state, BlockPos cell,
                                           BlockState desired, BlockPos against, Direction face) {
        int layer = state.currentLayer();
        if (eligibilityOf(world, state.view(), against, layer) != Refusal.NONE) {
            return null;
        }
        for (BlockPos stance : this.oracle.candidateStances(world, cell, desired, this.budget)) {
            BlockPos floor = stance.below();
            if (floor.equals(against)
                    || eligibilityOf(world, state.view(), floor, layer) != Refusal.NONE) {
                continue;
            }

            // Cheap authoritative pre-screen. Most candidates do not become a legal stance even with a full floor
            // cube; do not spend two placement solves on those.
            Scratch scratch = new Scratch(world);
            try {
                scratch.apply(floor, this.scaffoldState);
                if (!world.isStandable(stance.getX(), stance.getY(), stance.getZ())) {
                    continue;
                }
            } finally {
                scratch.restore();
            }

            Optional<PlacementSolution> floorPlacement;
            Optional<PlacementSolution> clickPlacement;
            Optional<PlacementSolution> targetPlacement = Optional.empty();
            Optional<BuildAction.RemoveScaffold> clickRemoval = Optional.empty();
            Optional<BuildAction.RemoveScaffold> floorRemoval = Optional.empty();
            scratch = new Scratch(world);
            try {
                floorPlacement = this.planScaffoldAt(world, state, floor, cell);
                if (floorPlacement.isEmpty()) {
                    continue;
                }
                scratch.apply(floor, floorPlacement.get().predicted());

                // Re-solve the click helper after the floor exists: the new cube may be its support, its occluder, or
                // both. Reusing the one-helper proof would reason about a world execution never sees.
                clickPlacement = this.planScaffoldAt(world, state, against, cell);
                if (clickPlacement.isEmpty()) {
                    continue;
                }
                scratch.apply(against, clickPlacement.get().predicted());
                if (!world.isStandable(stance.getX(), stance.getY(), stance.getZ())) {
                    continue;
                }

                targetPlacement = this.oracle.solve(world, cell, desired, state.stacks(), focused(this.budget),
                        narrowed(state.journal(), cell, face, stance)).best();
                if (targetPlacement.isEmpty()) {
                    continue;
                }
                scratch.apply(cell, targetPlacement.get().predicted());

                clickRemoval = removalOf(world, this.oracle.pose(), this.budget, against,
                        clickPlacement.get().predicted(), targetPlacement.get().stance(), layer);
                if (clickRemoval.isEmpty()) {
                    continue;
                }
                scratch.apply(against, Blocks.AIR.defaultBlockState());

                floorRemoval = removalOf(world, this.oracle.pose(), this.budget, floor,
                        floorPlacement.get().predicted(), clickRemoval.get().stance(), layer);
                if (floorRemoval.isEmpty()) {
                    continue;
                }
            } finally {
                scratch.restore();
            }

            List<BuildAction> actions = new ArrayList<>(6);
            actions.add(new BuildAction.PlaceScaffold(floorPlacement.orElseThrow(), cell, true,
                    BuildAction.UNASSIGNED_SLOT, layer));
            actions.add(new BuildAction.PlaceScaffold(clickPlacement.orElseThrow(), cell, true,
                    BuildAction.UNASSIGNED_SLOT, layer));
            actions.addAll(this.cellActions(world, state, targetPlacement.orElseThrow()));
            actions.add(clickRemoval.orElseThrow());
            actions.add(floorRemoval.orElseThrow());
            return new Escalation(cell, targetPlacement.orElseThrow(),
                    List.of(floorPlacement.orElseThrow(), clickPlacement.orElseThrow()), actions);
        }
        return null;
    }

    /**
     * The cell's own actions inside an escalation: the placement, and the right-clicks that finish a state placement
     * cannot set.
     *
     * <p>No {@code BREAK} is emitted and none is needed: {@link PlacementOracle}'s precheck refuses a cell whose
     * current block is not replaceable, so a cell an escalation can solve at all is a cell nothing has to come out of
     * first. Deliberately narrower than {@code OrderPlanner.actionsFor}, which serves the frontier path where the
     * same cell may have arrived through a break.
     */
    private List<BuildAction> cellActions(PredictedWorld world, OrderPlanner.PlannerState state,
                                          PlacementSolution solution) {
        List<BuildAction> actions = new ArrayList<>(2);
        BlockPos cell = solution.cell();
        int layer = state.view().layerOf(cell);
        // Sneak for everything except a chest: sneak forces ChestBlock.getStateForPlacement to SINGLE, so a crouched
        // click on either half of a planned double chest silently builds two singles. BuildAction.Place refuses that
        // combination in its own constructor rather than trusting this line.
        boolean sneak = PlacementFamilies.classify(solution.desired()) != PlacementFamilies.Family.CHEST_TYPE;
        // UNASSIGNED_SLOT: the schedule owns the slot, not the solution. See BuildAction.UNASSIGNED_SLOT.
        actions.add(new BuildAction.Place(solution, sneak, BuildAction.UNASSIGNED_SLOT, layer));

        int clicks = PlacementGeometry.interactionClicks(solution.predicted(), solution.desired());
        if (clicks <= 0) {
            return actions;
        }
        Scratch scratch = new Scratch(world);
        Optional<Aimed> aimed;
        try {
            // Aimed at the block as it will stand, from the stance the placement already walked to, so an interaction
            // that follows its own placement pays no walk at all.
            //
            // STANDING, and this is the only aim in the engine that is. A crouched right-click does not step a
            // repeater, it places the held block on it -- so the click goes out standing, and an aim proven from the
            // crouched eye 0.35 blocks lower is not the ray that will be cast. Mirrors OrderPlanner.actionsFor; the
            // two must stay the same or one of them plans a click the other could not have made.
            scratch.apply(cell, solution.predicted());
            aimed = aimAtBlock(world, PlayerPose.STANDING, this.budget, cell, solution.stance(), false);
        } finally {
            scratch.restore();
        }
        if (aimed.isPresent()) {
            Aimed at = aimed.get();
            // Never sneaking and never holding a placeable item: a crouched right-click puts the held block down
            // instead of stepping the state, which is a wrong build rather than a slow one.
            actions.add(new BuildAction.Interact(cell, at.stance(), at.approach(), at.point(), at.rotation(),
                    at.face(), clicks, solution.desired(), false, HotbarSchedule.PICKAXE_SLOT, layer));
        }
        return actions;
    }

    // ------------------------------------------------------------------- shared machinery

    /** One proven click on a block that already stands: where to stand, exactly where inside that cell, which face and
     *  the exact look. The break-and-interact counterpart of {@link PlacementSolution}, which solves for a cell that is
     *  still empty and therefore cannot answer this. */
    record Aimed(BlockPos stance, Vec3 approach, Direction face, Vec3 point, Rotation rotation) {
    }

    /**
     * The state a helper block lands in, or {@code null} when the item cannot place one.
     *
     * <p>Read off the item rather than taken as a parameter so that the helper block the plan books and the helper
     * block the executor clicks are the same block by construction — {@code BlockPlaceHelper.matches} compares item
     * identity, and a mismatch there voids the click with no log line at all.
     */
    static BlockState helperState(Item item) {
        return item instanceof BlockItem blockItem ? blockItem.getBlock().defaultBlockState() : null;
    }

    /** Nine slots, all empty but the reserved one. See {@link #scaffoldHotbar} for why the other eight are cleared
     *  rather than copied from the inventory. */
    static List<ItemStack> helperHotbar(Item item, int slot) {
        List<ItemStack> hotbar = new ArrayList<>(HotbarSchedule.HOTBAR_SLOTS);
        for (int index = 0; index < HotbarSchedule.HOTBAR_SLOTS; index++) {
            hotbar.add(item != null && index == slot ? new ItemStack(item) : ItemStack.EMPTY);
        }
        return List.copyOf(hotbar);
    }

    /** Solve a helper block placement and nothing else — the eligibility rules and the removal proof are
     *  {@link #planScaffoldAt}'s, so that {@link UpwardLook} can ask the cheap half of the question while it is still
     *  choosing between foot columns. */
    static Optional<PlacementSolution> helperPlacement(PlacementOracle oracle, PredictedWorld world, SolveBudget budget,
                                                       BlockPos cell, BlockState helper, List<ItemStack> hotbar,
                                                       PlacementOracle.StanceFilter filter) {
        if (helper == null) {
            return Optional.empty();
        }
        return oracle.solve(world, cell, helper, hotbar, budget,
                filter == null ? PlacementOracle.StanceFilter.ALL : filter).best();
    }

    /**
     * The removal of a block that stands, as a planned action, or empty when nothing can see it.
     *
     * @param preferred a stance to try before the search, so a helper block removed straight after the cell it served
     *                  is broken from the stance the bot is already standing in
     */
    static Optional<BuildAction.RemoveScaffold> removalOf(PredictedWorld world, PlayerPose pose, SolveBudget budget,
                                                          BlockPos cell, BlockState expected, BlockPos preferred,
                                                          int layer) {
        return removalOf(world, pose, budget, cell, expected, preferred, layer, ignored -> true);
    }

    private static Optional<BuildAction.RemoveScaffold> removalOf(PredictedWorld world, PlayerPose pose,
                                                                  SolveBudget budget, BlockPos cell,
                                                                  BlockState expected, BlockPos preferred, int layer,
                                                                  Predicate<BlockPos> stanceAllowed) {
        return aimAtBlock(world, pose, budget, cell, preferred, true, stanceAllowed)
                .map(at -> new BuildAction.RemoveScaffold(
                cell, at.stance(), at.approach(), at.point(), at.rotation(), expected,
                // Crouched, because the geometry above was proven from the crouched eye and a standing click casts a
                // different ray from the one the proof was written against.
                true, HotbarSchedule.PICKAXE_SLOT, layer));
    }

    /**
     * Find a stance and a look that put the crosshair on a block that already stands.
     *
     * <p>Deliberately narrower than the placement search: the cell centre rather than the optimised approach grid, and
     * the first workable face rather than a ranking. There is nothing for a margin to protect here — a break has no
     * orientation to get wrong and an interaction's outcome does not depend on where on the face the click lands — so
     * the extra rays would buy a number nobody reads.
     *
     * @param avoidStandingOn refuse the stance directly on top of the block. Set for every removal: breaking the block
     *                        under one's own feet drops the bot a block, and the next action's stance was proven from
     *                        the floor that just disappeared. The upward family's stepping stone is exactly this case,
     *                        which is why it is the one helper block the layer gate still owns
     */
    static Optional<Aimed> aimAtBlock(PredictedWorld world, PlayerPose pose, SolveBudget budget, BlockPos cell,
                                      BlockPos preferred, boolean avoidStandingOn) {
        return aimAtBlock(world, pose, budget, cell, preferred, avoidStandingOn, ignored -> true);
    }

    private static Optional<Aimed> aimAtBlock(PredictedWorld world, PlayerPose pose, SolveBudget budget, BlockPos cell,
                                              BlockPos preferred, boolean avoidStandingOn,
                                              Predicate<BlockPos> stanceAllowed) {
        AABB target = boxOf(world, cell);
        for (BlockPos stance : removalStances(cell, preferred)) {
            if (avoidStandingOn && stance.below().equals(cell)) {
                continue;
            }
            if (!stanceAllowed.test(stance)) {
                continue;
            }
            if (!world.isLoaded(stance.getX(), stance.getY(), stance.getZ())
                    || !world.isStandable(stance.getX(), stance.getY(), stance.getZ())) {
                continue;
            }
            Vec3 approach = new Vec3(stance.getX() + 0.5D, PlacementOracle.footY(world, stance),
                    stance.getZ() + 0.5D);
            if (!PlacementOracle.approachEnvelopeSupported(
                    world, pose, stance, approach, FineApproach.TOLERANCE)) {
                continue;
            }
            if (pose.bodyAt(approach).intersects(target)) {
                continue;   // check A1 again: the bot cannot be inside the block it is clicking
            }
            if (avoidStandingOn) {
                Scratch scratch = new Scratch(world);
                boolean supported;
                try {
                    scratch.apply(cell, Blocks.AIR.defaultBlockState());
                    supported = PlacementOracle.arrivalDiskSupported(
                            world, pose, approach, FineApproach.TOLERANCE);
                } finally {
                    scratch.restore();
                }
                if (!supported) {
                    continue;
                }
            }
            Vec3 eye = pose.eyeAt(approach);
            for (Direction face : REMOVAL_FACES) {
                BlockPos outside = cell.relative(face);
                if (world.isSolidFullCube(outside.getX(), outside.getY(), outside.getZ())) {
                    continue;   // that face is buried; the ray would stop on the neighbour
                }
                Vec3 point = faceAim(target, face);
                if (eye.distanceTo(point) > budget.maxReach()) {
                    continue;
                }
                GridRay.Hit obstruction = world.clip(eye, point);
                if (obstruction != null && !hits(obstruction, cell, face)) {
                    continue;
                }
                Rotation rotation = RotationUtils.calcRotationFromVec3d(eye, point, PLANNING_REFERENCE);
                if (!crosshairReaches(world, budget, cell, face, eye, rotation)) {
                    continue;
                }
                return Optional.of(new Aimed(stance, approach, face, point, rotation));
            }
        }
        return Optional.empty();
    }

    /**
     * A scratch edit of the predicted world that can be undone exactly.
     *
     * <p>{@link PredictedWorld#revert} restores the SNAPSHOT, not the previous delta, so using it to undo a
     * speculative placement over a cell the plan had already broken would resurrect the terrain that was broken there
     * — silently, and thousands of cells before anything looks wrong. This records what was actually read and puts
     * exactly that back, in reverse order, so a position edited twice ends where it started.
     */
    static final class Scratch {

        private final PredictedWorld world;
        private final List<BlockPos> positions = new ArrayList<>(4);
        private final List<BlockState> before = new ArrayList<>(4);

        Scratch(PredictedWorld world) {
            this.world = world;
        }

        void apply(BlockPos pos, BlockState state) {
            this.positions.add(pos);
            this.before.add(this.world.get(pos));
            this.world.apply(pos, state);
        }

        void restore() {
            while (!this.positions.isEmpty()) {
                restoreLast();
            }
        }

        /** Undo the most recent edit only — for a candidate that was staged, tried and rejected while the edits
         *  underneath it are still wanted. */
        void restoreLast() {
            int index = this.positions.size() - 1;
            if (index < 0) {
                return;
            }
            BlockPos pos = this.positions.remove(index);
            BlockState original = this.before.remove(index);
            if (this.world.original(pos.getX(), pos.getY(), pos.getZ()) == original) {
                // The cell carried no delta before this scratch touched it, so dropping the delta is the exact undo
                // and leaves the delta count where the planner's own bookkeeping expects it.
                this.world.revert(pos);
            } else {
                this.world.apply(pos, original);
            }
        }
    }

    // ------------------------------------------------------------------- internals

    /** Does any schematic material place the same block as this item? Compared through
     *  {@link PlacementGeometry#itemCanPlaceBlock} rather than by block identity, because one torch item makes both
     *  {@code torch} and {@code wall_torch} and rule 1 is about the ITEM. */
    private static boolean appearsInSchematic(SchematicView view, Item candidate) {
        BlockState helper = helperState(candidate);
        if (helper == null) {
            return true;
        }
        for (BlockState wanted : view.distinctStates()) {
            if (PlacementGeometry.itemCanPlaceBlock(helper, wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Will the schematic's block at {@code neighbour} already be standing when this helper block comes back out?
     *
     * <p>Rule 3 is about blocks that FALL when the helper block goes, and a block that has not been placed yet cannot
     * fall. The bound is the helper block's own layer: it is removed before the gate of the layer being built, which
     * is at or below its own, so every schematic cell strictly above it is still empty at that moment. Without this
     * test the rule refuses far more than it should — etz-basalt has 1081 repeaters, and one of them one layer above
     * {@code T+UP} would veto the click surface of every upward-look cell under it, for a block the plan will not lay
     * for another whole layer.
     *
     * <p>Cells at or below the helper block's layer are counted, which is conservative in the other direction: a
     * lateral neighbour on the SAME layer is usually not built either, but "usually" is not a proof and the cost of
     * refusing one candidate position is one alternative face.
     */
    private static boolean standsBeforeRemoval(SchematicView view, BlockPos neighbour, BlockPos scaffold) {
        return view != null && view.wantsBlock(neighbour) && view.layerOf(neighbour) <= scaffold.getY();
    }

    /** Would a block at {@code side} of the helper block need it to stand? {@code side} points from the helper block
     *  to the neighbour, so the support the neighbour names has to point back. */
    private static boolean needsSupportFrom(BlockState neighbour, Direction side) {
        if (neighbour == null || neighbour.isAir()) {
            return false;
        }
        Direction support = PlacementGeometry.requiredSupportDirection(neighbour);
        if (support != null && support == side.getOpposite()) {
            return true;
        }
        // requiredSupportDirection answers for everything that names its support in a property -- buttons, levers,
        // wall torches, hoppers -- and null for the whole floor-standing family, because a repeater, a carpet, a door,
        // a rail, redstone wire and a standing torch carry no ATTACH_FACE. Every one of them still drops the instant
        // the block under it goes, so the cell directly above is asked separately.
        return side == Direction.UP && restsOnBlockBelow(neighbour);
    }

    /** The families whose {@code canSurvive} is "there is a solid block underneath me", none of which say so in a
     *  property. Wall torches are excluded explicitly: they hang on a side and are already covered by
     *  {@link PlacementGeometry#requiredSupportDirection}, and {@code WallTorchBlock} extends {@code TorchBlock}. */
    private static boolean restsOnBlockBelow(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BaseRailBlock
                || block instanceof CarpetBlock
                || block instanceof DiodeBlock
                || block instanceof DoorBlock
                || block instanceof VegetationBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof RedStoneWireBlock
                || block instanceof SnowLayerBlock
                || block instanceof StandingSignBlock
                || block instanceof AbstractCandleBlock
                || block instanceof CakeBlock
                || block instanceof FlowerPotBlock
                || (block instanceof TorchBlock && !(block instanceof WallTorchBlock));
    }

    private static boolean isFalling(BlockState state) {
        return state != null && state.getBlock() instanceof FallingBlock;
    }

    /**
     * Would the helper block be walled in by the time the layer gate asks for it back?
     *
     * <p>The cheap half of invariant S, asked before any ray is spent. A helper block is removable while any one of
     * its six neighbours is still open at the moment of the gate, and at that moment the built world is: what stands
     * now, plus every schematic cell of this layer and every finished one. A cell of a LATER layer is still empty then
     * and is therefore an opening, which is precisely why the normal case — a helper block on L+1 under an empty sky —
     * costs two bitset reads and passes.
     */
    private static boolean wouldBeEntombed(PredictedWorld world, SchematicView view, BlockPos pos, int currentLayer) {
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            boolean closed = world.isSolidFullCube(neighbour.getX(), neighbour.getY(), neighbour.getZ())
                    || (view != null && view.wantsBlock(neighbour) && view.layerOf(neighbour) <= currentLayer);
            if (!closed) {
                return false;
            }
        }
        return true;
    }

    /** A solve narrowed to one clicked face, with the planner's journal still in force. The journal is ANDed rather
     *  than replaced: a triple the executor has already seen refused must stay refused inside an escalation too, or
     *  the re-plan proposes the same rejected click for ever. */
    private static PlacementOracle.StanceFilter narrowed(PlacementOracle.StanceFilter journal, BlockPos cell,
                                                         Direction face) {
        return (candidate, stance, clicked) -> clicked == face && cell.equals(candidate)
                && (journal == null || journal.allows(candidate, stance, clicked));
    }

    /** As {@link #narrowed(PlacementOracle.StanceFilter, BlockPos, Direction)}, additionally pinned to one stance. */
    private static PlacementOracle.StanceFilter narrowed(PlacementOracle.StanceFilter journal, BlockPos cell,
                                                         Direction face, BlockPos stance) {
        return (candidate, candidateStance, clicked) -> stance.equals(candidateStance)
                && clicked == face && cell.equals(candidate)
                && (journal == null || journal.allows(candidate, candidateStance, clicked));
    }

    /**
     * Budget for a solve narrowed to exactly one stance and face.
     *
     * <p>Filtered standable stances still count against {@code maxStances} inside the oracle even though they cast no
     * ray. Lifting that cap and the refinement cap cannot widen this search; it merely ensures the one allowed triple
     * is reached and gets the same approach optimisation as an early candidate.
     */
    private static SolveBudget focused(SolveBudget budget) {
        return new SolveBudget(Integer.MAX_VALUE, budget.maxRays(), budget.minMargin(), budget.maxReach(),
                budget.approachStep(), budget.approachInset(), Integer.MAX_VALUE, false);
    }

    /** Candidate feet cells for a click on a block that stands, nearest first, with an optional preferred stance at
     *  the head. Ranked on the TARGET rather than on where the bot happens to be, so the same removal plans the same
     *  way wherever in the order it falls. */
    private static List<BlockPos> removalStances(BlockPos cell, BlockPos preferred) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -REMOVAL_STANCE_RADIUS; dx <= REMOVAL_STANCE_RADIUS; dx++) {
            for (int dz = -REMOVAL_STANCE_RADIUS; dz <= REMOVAL_STANCE_RADIUS; dz++) {
                for (int dy = REMOVAL_STANCE_LOWEST; dy <= REMOVAL_STANCE_HIGHEST; dy++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) {
                        continue;   // feet or head inside the block being clicked
                    }
                    candidates.add(cell.offset(dx, dy, dz));
                }
            }
        }
        candidates.sort(Comparator.comparingLong((BlockPos stance) -> squaredDistance(cell, stance))
                .thenComparingLong(PlacementGeometry::positionKey));
        if (preferred != null && !preferred.equals(cell)) {
            candidates.removeIf(preferred::equals);
            candidates.add(0, preferred);
        }
        return candidates;
    }

    private static long squaredDistance(BlockPos from, BlockPos to) {
        long dx = from.getX() - to.getX();
        long dy = from.getY() - to.getY();
        long dz = from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** The collision box of what stands in the cell, in world coordinates, falling back to the full cube. A torch or a
     *  repeater has no collision shape at all, and the degenerate box the world hands back would put the aim point on
     *  a corner; the executor's live gate re-runs the real {@code VoxelShape} before it clicks. */
    private static AABB boxOf(PredictedWorld world, BlockPos cell) {
        AABB box = world.collisionBox(cell.getX(), cell.getY(), cell.getZ());
        if (box == null || box.getSize() <= 0.0D) {
            return new AABB(cell.getX(), cell.getY(), cell.getZ(),
                    cell.getX() + 1.0D, cell.getY() + 1.0D, cell.getZ() + 1.0D);
        }
        return box;
    }

    /** The centre of one face of a box, pulled inside by {@link PlacementGeometry#FACE_INSET} so a ray to it cannot be
     *  judged to have missed by a rounding error at the edge. */
    private static Vec3 faceAim(AABB box, Direction face) {
        double x = (box.minX + box.maxX) / 2.0D;
        double y = (box.minY + box.maxY) / 2.0D;
        double z = (box.minZ + box.maxZ) / 2.0D;
        double inset = PlacementGeometry.FACE_INSET;
        return switch (face) {
            case EAST -> new Vec3(box.maxX - inset, y, z);
            case WEST -> new Vec3(box.minX + inset, y, z);
            case UP -> new Vec3(x, box.maxY - inset, z);
            case DOWN -> new Vec3(x, box.minY + inset, z);
            case SOUTH -> new Vec3(x, y, box.maxZ - inset);
            case NORTH -> new Vec3(x, y, box.minZ + inset);
        };
    }

    /**
     * Would the crosshair actually rest on this block?
     *
     * <p>Cast along the look for the full reach rather than to the aim point, because {@link GridRay} is half-open at
     * its end and a segment that stops on the face cannot report what it stopped against. Skipped when the target is
     * not a full cube: partial shapes are absent from the solid bitset by design, so the caster has nothing to answer
     * with and a deferred answer beats a wrong one.
     */
    private static boolean crosshairReaches(PredictedWorld world, SolveBudget budget, BlockPos cell, Direction face,
                                             Vec3 eye, Rotation rotation) {
        if (!world.isSolidFullCube(cell.getX(), cell.getY(), cell.getZ())) {
            return true;
        }
        Vec3 look = RotationUtils.calcLookDirectionFromRotation(rotation);
        double reach = budget.maxReach();
        GridRay.Hit hit = world.clip(eye, eye.add(look.x * reach, look.y * reach, look.z * reach));
        return hit != null && hits(hit, cell, face);
    }

    /** Does a full-cube ray enter the intended block through the intended visible face? */
    static boolean hits(GridRay.Hit hit, BlockPos cell, Direction face) {
        return hit.x() == cell.getX() && hit.y() == cell.getY() && hit.z() == cell.getZ()
                && hit.face() == face;
    }
}
