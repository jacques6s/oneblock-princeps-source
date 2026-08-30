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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The dry run end to end — {@link OrderPlanner#plan} over a real {@link PlacementOracle}, and the determinism section
 * 13 of the plan promises.
 *
 * <h2>Why this test was thought impossible, and what actually blocks it</h2>
 *
 * <p>Every implementer of this package recorded that the oracle "cannot be called from JUnit at all". Two separate
 * obstacles were rolled into that sentence, and only one of them is real:
 *
 * <ul>
 *   <li><b>Not a blocker.</b> {@code new ItemStack(item)} throws {@code NullPointerException: Components not bound
 *       yet} after {@code Bootstrap.bootStrap()}, because item components are bound by a data-pack load no headless
 *       test performs. {@link #bootstrapMinecraft} binds them by hand, as {@code HotbarScheduleTest} already does.
 *       A hotbar is therefore constructible and {@link PlacementOracle#solve} is callable.</li>
 *   <li><b>The real blocker, and it is narrow.</b> {@code PlacementOracle.fastSolve} tests every candidate stance
 *       with {@link PredictedWorld#isStandable}, which calls {@code MovementHelper.canWalkThroughBlockState} on the
 *       FEET and HEAD cells. That method returns early — with no settings read — for air and for the fifteen block
 *       types listed at {@code MovementHelper.java:146}; everything else falls through to
 *       {@code Princeps.settings().blocksToAvoid} at {@code :155} and dies in a static initialiser (trap 1.7).
 *       Verified here by stack trace, not assumed.</li>
 * </ul>
 *
 * <p>So the constraint is not "no oracle in a test". It is: <b>no block may appear in a candidate stance's feet or
 * head cell unless it short-circuits that method.</b> These worlds are built from exactly two materials chosen against
 * that rule, and the choice is the whole reason they look strange:
 *
 * <ul>
 *   <li><b>Floor: azalea.</b> The one block that is walkable WITHOUT a settings read — {@code canWalkOnBlockState}
 *       answers YES at {@code :418} before reaching {@code allowWalkOnMagmaBlocks} — and simultaneously refuses to be
 *       walked THROUGH at {@code :146}, so a stance sunk into the floor is rejected rather than crashing.</li>
 *   <li><b>Material: shulker box.</b> A full solid cube, so the placement geometry is the ordinary one, and in the
 *       {@code :146} early-NO list, so a stance standing where a placed block already is costs a rejection instead of
 *       an {@code ExceptionInInitializerError}.</li>
 * </ul>
 *
 * <p>Nothing about determinism depends on which blocks these are. If {@code isStandable} ever loses its settings
 * reads, replace them with stone and the assertions stand unchanged.
 *
 * <h2>What the determinism tests do and do not prove</h2>
 *
 * <p>Stated because an over-read of a green test here is worse than no test. Two runs inside ONE JVM catch a clock,
 * {@code Math.random}, a container iterated in an order that depends on what was inserted before it, and any state
 * leaking between runs. They do <b>not</b> catch a {@code HashMap} whose iteration order is arbitrary but stable:
 * identical insertions into identical hash containers in one JVM enumerate identically, so a hash-order defect passes
 * here and would only show up across JVMs or across versions. {@link #theOrderIsInvariantToWhereTheMaterialSitsInTheInventory}
 * exists to attack precisely that gap from the one angle a single JVM allows — it changes the input's arrangement
 * without changing its content, so anything reading the inventory in encounter order rather than the documented sorted
 * order diverges.
 */
public class OrderPlannerTest {

    /**
     * Bootstrap plus the component binding that makes {@link ItemStack} constructible. See the class javadoc.
     *
     * <p>Side effect worth knowing: {@code getMaxStackSize()} answers 1 for everything afterwards, because
     * {@link DataComponentMap#EMPTY} carries no {@code MAX_STACK_SIZE}. Nothing on the planning path reads it.
     */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    private static final Vec3i ORIGIN = new Vec3i(0, 65, 0);

    /** One below the schematic — the surface the bot stands on to build the first layer. */
    private static final int FLOOR_Y = 64;

    /** Far enough out to be off the build and on the floor, so the first stance is a real choice rather than the
     *  cell the bot already occupies. */
    private static final BlockPos BOT_START = new BlockPos(-2, 65, -2);

    // ------------------------------------------------------------------------------------------------- the fixture

    /** A solid {@code width x height x depth} block of one material at {@link #ORIGIN}. */
    private static ISchematic box(int width, int height, int depth, BlockState state) {
        return new ISchematic() {

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                return state;
            }

            @Override
            public int widthX() {
                return width;
            }

            @Override
            public int heightY() {
                return height;
            }

            @Override
            public int lengthZ() {
                return depth;
            }
        };
    }

    /** Flat azalea at {@link #FLOOR_Y}, air everywhere else, every column loaded. */
    private static PredictedWorld flatWorld(ISchematic schematic) {
        Vec3i max = new Vec3i(
                ORIGIN.getX() + schematic.widthX() - 1,
                ORIGIN.getY() + schematic.heightY() - 1,
                ORIGIN.getZ() + schematic.lengthZ() - 1);
        BlockState floor = Blocks.AZALEA.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture((x, y, z) -> y == FLOOR_Y ? floor : air,
                (x, z) -> true, ORIGIN, max, PredictedWorld.DEFAULT_MARGIN);
    }

    /** A pickaxe in slot 0 and the material in slot 1 — the arrangement {@code HotbarSchedule} would choose anyway,
     *  so the plan it produces is the one the permutation test below has to reproduce. */
    private static HotbarSchedule.InventorySnapshot tidyInventory() {
        List<ItemStack> slots = emptySlots();
        slots.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.DIAMOND_PICKAXE));
        slots.set(1, new ItemStack(Items.SHULKER_BOX, 64));
        return new HotbarSchedule.InventorySnapshot(slots);
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> slots = new ArrayList<>(HotbarSchedule.INVENTORY_SLOTS);
        for (int index = 0; index < HotbarSchedule.INVENTORY_SLOTS; index++) {
            slots.add(ItemStack.EMPTY);
        }
        return slots;
    }

    /**
     * One whole dry run, built from scratch every time.
     *
     * <p>Nothing is shared between calls — not the world, not the oracle, not the view. Reusing any of them would
     * make the determinism tests compare a run against itself: {@code plan()} mutates the world it is given, and an
     * oracle that memoised anything would answer the second run from the first one's cache.
     */
    private static PlanReport planOnce(ISchematic schematic, HotbarSchedule.InventorySnapshot inventory) {
        return planOnce(schematic, inventory, PlacementOracle.StanceFilter.ALL);
    }

    /** The same run with a veto that outlives the plan — the shape {@code PlannedBuilderProcess} re-plans through. */
    private static PlanReport planOnce(ISchematic schematic, HotbarSchedule.InventorySnapshot inventory,
                                       PlacementOracle.StanceFilter filter) {
        // Netherrack as the helper block: it appears in no schematic here, which is scaffold rule 1, and these builds
        // need none anyway — a scaffold block appearing in one of these plans would itself be the news.
        return planOnce(schematic, inventory, filter, Items.NETHERRACK);
    }

    /**
     * As above, with the helper material chosen by the caller. {@code null} disables the scaffold escalation
     * altogether, which is the real behaviour of a run whose inventory holds no throwaway the schematic does not
     * itself use — {@code ScaffoldPlanner.escalate} returns empty for a null helper state and the layer is reported
     * as a blocker.
     *
     * <p>Two of the journal tests below need that, and the reason is the fixture's own material rule rather than
     * anything about the journal. The escalation solves for a stance to place the HELPER from, and that search walks
     * candidate cells outside the little world these tests capture — where {@code isStandable} reaches
     * {@code MovementHelper.canWalkThroughBlockState} and dies in {@code Princeps.settings()}, the exact static
     * initialiser this whole fixture is built to stay clear of. Escalation is exercised by the dry run against the
     * real schematic, not here.
     */
    private static PlanReport planOnce(ISchematic schematic, HotbarSchedule.InventorySnapshot inventory,
                                       PlacementOracle.StanceFilter filter, Item helper) {
        PredictedWorld world = flatWorld(schematic);
        SchematicView view = SchematicView.capture("test", schematic, ORIGIN, world, List.of());
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT,
                helper, HotbarSchedule.THROWAWAY_SLOT);
        return new OrderPlanner(oracle, scaffold)
                .plan(view, world, settings, SolveBudget.DEFAULT, inventory, BOT_START, filter);
    }

    /** One shulker box on the floor: the smallest thing with a real placement in it, and the only fixture where a
     *  single vetoed stance cannot strand a neighbouring cell as a side effect. */
    private static ISchematic oneCell() {
        return box(1, 1, 1, Blocks.SHULKER_BOX.defaultBlockState());
    }

    /** One target suspended one block over the floor, forcing a temporary support beneath it. */
    private static ISchematic suspendedCell() {
        return new ISchematic() {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                return y == 1 ? Blocks.BLACK_SHULKER_BOX.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }

            @Override
            public int widthX() {
                return 1;
            }

            @Override
            public int heightY() {
                return 2;
            }

            @Override
            public int lengthZ() {
                return 1;
            }
        };
    }

    private static HotbarSchedule.InventorySnapshot suspendedInventory() {
        List<ItemStack> slots = emptySlots();
        slots.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.DIAMOND_PICKAXE));
        slots.set(1, new ItemStack(Items.BLACK_SHULKER_BOX, 64));
        // Azalea is deliberately used for this headless fixture: MovementHelper can both
        // stand on it and reject walking through it without touching Princeps.settings().
        slots.set(HotbarSchedule.THROWAWAY_SLOT, new ItemStack(Items.AZALEA, 64));
        return new HotbarSchedule.InventorySnapshot(slots);
    }

    /** A 3x3 slab resting on the floor. Solvable from every side, so it comes out READY. */
    private static PlanReport flatSlab() {
        return planOnce(box(3, 1, 3, Blocks.SHULKER_BOX.defaultBlockState()), tidyInventory());
    }

    /**
     * The same slab three layers high — INCOMPLETE, and deliberately so.
     *
     * <p>Two layers is NOT enough, and the earlier premise here said it was: it claimed a shulker box is not walkable
     * ({@code canWalkOnBlockState} falls through to NO), so once the first layer is down there is no stance at the
     * second layer's height. The first half is true, the conclusion is not — the bot does not need to stand ON the
     * slab to build y=66. Every y=66 cell of this fixture is placed from the azalea floor at y=65 OUTSIDE the 3x3
     * footprint, clicking the up face of the y=65 cell below it: {@code 1,66,1} from {@code 1,65,3}, the crouched eye
     * at 66.27 grazing over the one-high slab. Dumped from this fixture, not reasoned about.
     *
     * <p>The first layer that genuinely has no stance is y=67, because a y=67 cell really does need feet on top of
     * the y=66 boxes — all nine cells report 145 of 145 candidate stances rejected as STANCE_NOT_STANDABLE. So the
     * box has to be three high for this to stay an INCOMPLETE fixture. It is used here only because an INCOMPLETE
     * report exercises the blocker list, which is a different rendering path from a READY one and has its own
     * ordering to keep stable.
     */
    private static PlanReport blockedThirdLayer() {
        return planOnce(box(3, 3, 3, Blocks.SHULKER_BOX.defaultBlockState()), tidyInventory());
    }

    // ------------------------------------------------------------------------------------- the plan comes out sane

    @Test
    public void aFlatLayerPlansReadyWithEveryCellProvenAndNoScaffold() {
        PlanReport report = flatSlab();

        assertEquals("a 3x3 slab on open floor has no reason to be blocked: " + report.headline(),
                PlanReport.Status.READY, report.status());
        assertEquals(9, report.cellsTotal());
        // Every column is loaded in this world, so PROVEN is the whole of it — a provisional cell here would mean the
        // footprint rule of 5.10 had started answering false for terrain the snapshot definitely saw.
        assertEquals(9, report.proven());
        assertEquals(0, report.provisional());
        assertTrue(report.blockers().isEmpty());

        // Nine cells, nine placements: no cell planned twice, and no helper block for a build that needs none.
        assertEquals(9, report.plan().size());
        assertEquals(0, report.plan().counts().scaffoldBlocks());
    }

    /**
     * Invariant E must ask about the stance the candidate will be MADE from, never about the stance the previous
     * action ended on. Pinned here directly rather than through {@link #flatSlab}, because through the big fixture
     * this defect does not name itself: it reports a line of sight it destroyed itself, three placements later.
     *
     * <p>The fixture is the state the flat 3x3 run reaches at its eighth action. Seven of the eight ring cells are
     * down; the last free one is where the bot will stand; the centre is still air, and the bot's previous stance was
     * the centre — which is the ORDINARY case in a flat build, not an edge case, because the middle of the footprint
     * is where a ring is most conveniently placed from. The centre's own solution stands on that free cell; the bot
     * walks out before it clicks. Reading {@code lastStance()} here made {@code EnclosureTracker.wouldSeal}'s
     * {@code filled.equals(botCell)} shortcut fire on a cell the bot was about to leave, the planner banned that
     * (cell, stance, face) triple, re-solved, was refused again on the same false ground, and the centre fell out of
     * the frontier for good. The last cell was then filled too, and only THEN was the centre genuinely unreachable —
     * which is why the report blamed a line of sight the engine had destroyed itself.
     *
     * <p>The free cell is an EDGE and not a corner, and that changed when {@link FineApproach#ACHIEVABLE_TOLERANCE}
     * became the floor under every tolerance. From a corner the sight line to the centre is diagonal and grazes the
     * top edge of a ring block; it survives a 0.02 arrival ball and nothing wider, so the centre is now refused by
     * name as {@link PlacementOracle.Rejection#OCCLUSION_BELOW_BODY_FLOOR}. That refusal is correct — the old
     * "solution" was an instruction the fine approach could not carry out, and an exhaustive search (8192 rays, 200
     * stances) finds no other candidate — but a cell with no solution cannot pin anything about invariant E. The edge
     * gives the same shortcut the same cell to fire on, over a sight line the body can actually stand for.
     */
    @Test
    public void eAsksAboutTheCandidatesOwnStanceAndNotTheOneTheBotIsAboutToLeave() {
        ISchematic schematic = box(3, 1, 3, Blocks.SHULKER_BOX.defaultBlockState());
        PredictedWorld world = flatWorld(schematic);
        SchematicView view = SchematicView.capture("stranded-centre", schematic, ORIGIN, world, List.of());
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        BlockState material = Blocks.SHULKER_BOX.defaultBlockState();
        BlockPos centre = new BlockPos(ORIGIN.getX() + 1, ORIGIN.getY(), ORIGIN.getZ() + 1);
        BlockPos freeEdge = new BlockPos(ORIGIN.getX() + 1, ORIGIN.getY(), ORIGIN.getZ());

        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                BlockPos cell = new BlockPos(ORIGIN.getX() + x, ORIGIN.getY(), ORIGIN.getZ() + z);
                if (!cell.equals(centre) && !cell.equals(freeEdge)) {
                    world.apply(cell, material);
                }
            }
        }

        PlacementOracle.Solve solve = oracle.solve(world, centre, material, tidyInventory().slots(),
                SolveBudget.DEFAULT);
        assertTrue("the ringed centre still has a solution: " + solve.explain(), solve.solved());
        PlacementSolution candidate = solve.best().orElseThrow();
        assertNotEquals("the bot leaves the centre to place into it, which is the whole point of this fixture",
                centre, candidate.stance());

        // botStart seeds PlannerState.lastStance, so this is a state whose previous action ended on the centre.
        OrderPlanner.PlannerState state = new OrderPlanner.PlannerState(oracle, view, settings, SolveBudget.DEFAULT,
                tidyInventory(), centre);

        assertTrue("E refused a placement the bot walks out of the cell to make",
                Invariants.enclosureFree(world, state, candidate));
        assertEquals("no guard has anything to say about the last cell of an open ring",
                Optional.empty(), Invariants.explain(world, state, candidate));
    }

    @Test
    public void aSuspendedCellGetsAPlannedTemporarySupportAndItsRemoval() {
        ISchematic schematic = suspendedCell();
        PredictedWorld world = flatWorld(schematic);
        SchematicView view = SchematicView.capture("suspended", schematic, ORIGIN, world, List.of());
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT,
                Items.AZALEA, HotbarSchedule.THROWAWAY_SLOT);
        BlockPos helperCell = new BlockPos(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
        BlockPos targetCell = helperCell.above();

        assertEquals(ScaffoldPlanner.Refusal.NONE,
                scaffold.eligibility(world, view, helperCell, targetCell.getY()));
        PlacementOracle.Solve helperSolve = oracle.solve(world, helperCell, Blocks.AZALEA.defaultBlockState(),
                ScaffoldPlanner.helperHotbar(Items.AZALEA, HotbarSchedule.THROWAWAY_SLOT),
                SolveBudget.DEFAULT);
        assertTrue(helperSolve.explain(), helperSolve.solved());
        assertTrue("the helper is visible from a removable stance before it is staged",
                ScaffoldPlanner.removalOf(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT, helperCell,
                        Blocks.AZALEA.defaultBlockState(), helperSolve.best().orElseThrow().stance(),
                        targetCell.getY()).isPresent());
        OrderPlanner.PlannerState plannerState = new OrderPlanner.PlannerState(oracle, view, settings,
                SolveBudget.DEFAULT, suspendedInventory(), BOT_START);
        assertTrue("the composed scaffold proof rejected a helper whose eligibility, placement and removal all pass",
                scaffold.planScaffoldAt(world, plannerState, helperCell, targetCell).isPresent());
        world.apply(helperCell, Blocks.AZALEA.defaultBlockState());
        assertEquals(Blocks.AZALEA.defaultBlockState(), world.get(helperCell));
        assertFalse(world.get(helperCell).canBeReplaced());
        assertTrue(world.collisionBox(helperCell.getX(), helperCell.getY(), helperCell.getZ()).getSize() > 0.0D);
        assertTrue(List.of(PlacementGeometry.supportDirectionsFor(
                Blocks.BLACK_SHULKER_BOX.defaultBlockState())).contains(Direction.DOWN));
        PlacementOracle.Solve targetSolve = oracle.solve(world, targetCell,
                Blocks.BLACK_SHULKER_BOX.defaultBlockState(), suspendedInventory().slots(), SolveBudget.DEFAULT);
        assertTrue(targetSolve.explain(), targetSolve.solved());
        world.apply(targetCell, targetSolve.best().orElseThrow().predicted());
        assertTrue("placing the served cell made its temporary support impossible to remove",
                ScaffoldPlanner.removalOf(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT, helperCell,
                        Blocks.AZALEA.defaultBlockState(), targetSolve.best().orElseThrow().stance(),
                        targetCell.getY()).isPresent());
        world.revert(targetCell);
        world.revert(helperCell);

        PlanReport report = new OrderPlanner(oracle, scaffold).plan(view, world, settings, SolveBudget.DEFAULT,
                suspendedInventory(), BOT_START);

        assertEquals(report.render(), PlanReport.Status.READY, report.status());
        assertEquals(1, report.plan().counts().cells());
        assertEquals(1, report.plan().counts().scaffoldBlocks());
        assertEquals(0, report.blockers().size());
        assertTrue(report.plan().toPlanLines().stream().anyMatch(line -> line.contains("SCAFFOLD+")));
        assertTrue(report.plan().toPlanLines().stream().anyMatch(line -> line.contains("SCAFFOLD-")));
    }

    @Test
    public void everyCellOfTheSchematicAppearsExactlyOnceInTheOrder() {
        PlanReport report = flatSlab();

        List<BlockPos> placed = new ArrayList<>();
        for (int index = 0; index < report.plan().size(); index++) {
            BuildAction action = report.plan().action(index);
            assertTrue("a slab on bare floor should need nothing but placements, got " + action.kind(),
                    action instanceof BuildAction.Place);
            assertFalse("cell planned twice: " + action.cell(), placed.contains(action.cell()));
            placed.add(action.cell());
        }
        assertEquals(9, placed.size());
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                BlockPos cell = new BlockPos(ORIGIN.getX() + x, ORIGIN.getY(), ORIGIN.getZ() + z);
                assertTrue("cell missing from the order: " + cell, placed.contains(cell));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- determinism

    /**
     * The promise of section 13, on the READY path: same input, byte-identical artefacts.
     *
     * <p>All three files are compared and not just the plan. The proof carries the numbers the plan rounds away — a
     * search that reached the same conclusion by a different route shows up here and nowhere else — and the report
     * carries the material ledger and the layer summaries, which are built from different containers again.
     */
    @Test
    public void twoRunsOfAReadyPlanProduceByteIdenticalArtefacts() {
        PlanReport first = flatSlab();
        PlanReport second = flatSlab();

        assertEquals(PlanReport.Status.READY, first.status());
        assertEquals(first.plan().toPlanLines(), second.plan().toPlanLines());
        assertEquals(first.plan().toProofLines(), second.plan().toProofLines());
        assertEquals(first.render(), second.render());
    }

    /**
     * The same promise on the INCOMPLETE path, which is the one a human actually reads.
     *
     * <p>A blocker list that reorders between runs would make two identical failures look like two different ones,
     * and "the report changed" is how a real regression gets dismissed as noise.
     */
    @Test
    public void twoRunsOfAnIncompletePlanProduceByteIdenticalArtefacts() {
        PlanReport first = blockedThirdLayer();
        PlanReport second = blockedThirdLayer();

        assertEquals("this fixture exists to exercise the blocker path", PlanReport.Status.INCOMPLETE,
                first.status());
        assertFalse(first.blockers().isEmpty());
        assertEquals(first.plan().toPlanLines(), second.plan().toPlanLines());
        assertEquals(first.plan().toProofLines(), second.plan().toProofLines());
        assertEquals(first.render(), second.render());
    }

    /**
     * The structural property: the outer loop stops at the first blocked layer, and nothing above it is planned or
     * even summarised.
     *
     * <p>The layer number is {@code ORIGIN.getY() + 2} and not {@code + 1}, for the reason
     * {@link #blockedThirdLayer} spells out — y=66 is buildable from the floor beside the slab, y=67 is the first
     * layer that needs feet on shulker boxes. The property under test has not moved; only the height at which this
     * fixture's material stops the planner has.
     */
    @Test
    public void aBlockedLayerStopsTheOuterLoopBeforeAnyHigherLayerIsPlanned() {
        PlanReport report = planOnce(box(3, 4, 3, Blocks.SHULKER_BOX.defaultBlockState()), tidyInventory());

        assertEquals(PlanReport.Status.INCOMPLETE, report.status());
        assertFalse(report.blockers().isEmpty());
        assertFalse(report.layers().isEmpty());
        assertEquals("the first blocked layer is y=" + (ORIGIN.getY() + 2)
                        + "; the hard layer rule forbids even summarising y=" + (ORIGIN.getY() + 3),
                ORIGIN.getY() + 2, report.layers().get(report.layers().size() - 1).layer());
        for (int index = 0; index < report.plan().size(); index++) {
            assertTrue("action escaped above the blocked layer: " + report.plan().action(index).describe(),
                    report.plan().action(index).layer() <= ORIGIN.getY() + 2);
        }
    }

    /**
     * The order is a property of the world and the schematic, not of how the bag is packed.
     *
     * <p>The strongest determinism check available inside one JVM: same contents, different arrangement. A hash
     * container enumerated in encounter order gives the same answer twice when the input is identical — which is why
     * the test above cannot catch it — but gives a different answer when the same items arrive in a different order,
     * which is what this changes.
     *
     * <p>The hotbar SLOT is expected to differ and is stripped before comparing: the schedule selects the material
     * where it actually is — slot 1 in the tidy arrangement, slot 4 in the scattered one — so a plan naming slot 1
     * for an item sitting in slot 4 would be the bug. Everything else on the line — the cell, the block against,
     * the face, the stance, the approach point, the aim point, the rotation and the margin — must be identical.
     * The final assertion pins that the slot really did move, so this cannot quietly pass by the permutation having
     * had no effect at all.
     */
    @Test
    public void theOrderIsInvariantToWhereTheMaterialSitsInTheInventory() {
        ISchematic schematic = box(3, 1, 3, Blocks.SHULKER_BOX.defaultBlockState());

        PlanReport tidy = planOnce(schematic, tidyInventory());

        // Same 64 shulker boxes, same pickaxe, scattered: split across two stacks, one of them in the backpack, and
        // the pickaxe out of slot 0 entirely.
        List<ItemStack> scattered = emptySlots();
        scattered.set(4, new ItemStack(Items.SHULKER_BOX, 32));
        scattered.set(20, new ItemStack(Items.SHULKER_BOX, 32));
        scattered.set(30, new ItemStack(Items.DIAMOND_PICKAXE));
        PlanReport shuffled = planOnce(schematic, new HotbarSchedule.InventorySnapshot(scattered));

        assertEquals(PlanReport.Status.READY, tidy.status());
        assertEquals(PlanReport.Status.READY, shuffled.status());
        assertEquals(stripSlots(tidy.plan().toPlanLines()), stripSlots(shuffled.plan().toPlanLines()));
        // The proof line carries no slot at all -- the slot is the schedule's answer and lives on the action,
        // while the proof is about the geometry -- so it is compared unstripped. Everything the proof adds over
        // the plan, the approach point and the margin included, has to match exactly.
        assertEquals(tidy.plan().toProofLines(), shuffled.plan().toProofLines());

        // The permutation must actually have reached the plan, or the comparison above proved nothing.
        assertNotEquals("the material moved but the plan still names the old slot",
                tidy.plan().toPlanLines(), shuffled.plan().toPlanLines());
    }

    // ------------------------------------------------------------------------- the two-stage slot resolution

    /**
     * <b>A material in the backpack is a material the planner places</b>, by fetching it onto the hotbar exactly once.
     *
     * <p>This test used to assert the opposite, and it was right to: {@code PlannerState} handed the oracle
     * {@code inventory.hotbar()} - the nine physical slots - while its own javadoc promised "the invariant is simply
     * that the item exists somewhere in the inventory". With slot 0 the pickaxe and slot 8 the throwaway, a plan could
     * only ever use the seven materials that happened to be on the bar; etz-basalt needs 34, and the dry run came back
     * with 10 480 of 15 004 cells refused for {@link PlacementOracle.Rejection#NO_ITEM} and the remaining 3 352
     * blockers cascading off them.
     *
     * <p>What made it more than a widened loop is that the two ends want different things and neither can be given up:
     * {@code BuildAction.checkHandSlot} must keep refusing a backpack index, because selecting slot 20 is not a thing
     * the hotbar can do; and the hotbar slot a placement uses is genuinely unknowable at solve time, because it is
     * whatever {@link HotbarSchedule#belady} evicts for it once the WHOLE order is known. So the resolution is in two
     * stages - {@link PlacementOracle} carries the {@link PlacementSolution#item}, and {@link HotbarSchedule#weave}
     * writes {@link BuildAction#handSlot} - and this test pins both halves: the fetch happens, and the slot the
     * placements name is the slot the fetch filled.
     */
    @Test
    public void aMaterialThatIsOnlyInTheBackpackIsFetchedOntoTheHotbarAndPlaced() {
        ISchematic schematic = box(3, 1, 3, Blocks.SHULKER_BOX.defaultBlockState());

        List<ItemStack> backpackOnly = emptySlots();
        backpackOnly.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.DIAMOND_PICKAXE));
        // Slot 9 is the first backpack slot. The bot is holding 64 shulker boxes and nothing is on the bar.
        backpackOnly.set(9, new ItemStack(Items.SHULKER_BOX, 64));
        PlanReport report = planOnce(schematic, new HotbarSchedule.InventorySnapshot(backpackOnly));

        assertEquals("the bot is carrying the material; where in the bag is not the planner's business: "
                + report.headline(), PlanReport.Status.READY, report.status());
        assertTrue(report.blockers().isEmpty());
        assertEquals(9, report.proven());

        // One fetch for nine placements - Belady's answer for a single-material build, and the number that would
        // silently become nine if weave stopped reusing the resident slot.
        assertEquals(1, report.plan().counts().hotbarSwaps());
        List<BuildAction.Place> placements = new ArrayList<>();
        BuildAction.SwapHotbar swap = null;
        for (int index = 0; index < report.plan().size(); index++) {
            switch (report.plan().action(index)) {
                case BuildAction.Place place -> placements.add(place);
                case BuildAction.SwapHotbar fetch -> swap = fetch;
                default -> fail("a slab on bare floor needs nothing but a fetch and nine placements, got "
                        + report.plan().action(index).describe());
            }
        }
        assertEquals(9, placements.size());
        assertNotNull("the material was in the backpack and no fetch was planned", swap);
        assertEquals(Items.SHULKER_BOX, swap.item());
        assertEquals("the hint should point at the backpack slot the stack is actually in", 9,
                swap.inventorySlotHint());

        for (BuildAction.Place place : placements) {
            // The invariant checkHandSlot exists for: never a backpack index, never the unresolved sentinel, and the
            // same slot the fetch filled - BlockPlaceHelper compares item identity, so a placement clicking any other
            // slot is voided with no log line at all.
            assertNotEquals("weave left a placement unresolved", BuildAction.UNASSIGNED_SLOT, place.handSlot());
            assertEquals("the placement must click the slot the fetch filled", swap.hotbarSlot(), place.handSlot());
            assertEquals(Items.SHULKER_BOX, place.solution().item());
        }
    }

    /**
     * The fetch is the ONLY difference a backpack makes: same geometry, same slot, one extra action.
     *
     * <p>Compared against {@link #flatSlab}, whose material sits in hotbar slot 1 - which is the slot
     * {@link HotbarSchedule#victimSlot} fills first, so the placements come out byte-identical rather than merely
     * equivalent. A change that closed the material gap by taking a different stance or a different face would pass
     * the test above and fail this one.
     */
    @Test
    public void reachingIntoTheBackpackCostsOneFetchAndChangesNothingElse() {
        List<ItemStack> backpackOnly = emptySlots();
        backpackOnly.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.DIAMOND_PICKAXE));
        backpackOnly.set(9, new ItemStack(Items.SHULKER_BOX, 64));
        PlanReport fromBackpack = planOnce(box(3, 1, 3, Blocks.SHULKER_BOX.defaultBlockState()),
                new HotbarSchedule.InventorySnapshot(backpackOnly));
        PlanReport fromHotbar = flatSlab();

        assertEquals(0, fromHotbar.plan().counts().hotbarSwaps());
        assertEquals(placements(fromHotbar.plan().toPlanLines()), placements(fromBackpack.plan().toPlanLines()));
        // The proof carries no slot and no swap, so every one of its lines has to match — the whole geometry of the
        // build, unfiltered beyond the index the extra action shifts.
        assertEquals(body(fromHotbar.plan().toProofLines()), body(fromBackpack.plan().toProofLines()));
    }

    /**
     * A plan or proof file without its header and without the running action index.
     *
     * <p>Both are dropped for the same reason: one of the two plans has an extra action in it, so the count in the
     * header and every index after the fetch differ by construction, and neither difference says anything about the
     * build. Everything that describes an action survives, hand slot included.
     */
    private static List<String> body(List<String> lines) {
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.matches("^\\d{6}  .*")) {
                kept.add(line.substring(8));
            }
        }
        return kept;
    }

    /** {@link #body}, narrowed to the placements. */
    private static List<String> placements(List<String> lines) {
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : body(lines)) {
            if (line.contains("PLACE ")) {
                kept.add(line);
            }
        }
        return kept;
    }

    /** Blank out every {@code slot <n>} so two plans can be compared on geometry alone. */
    private static List<String> stripSlots(List<String> lines) {
        List<String> stripped = new ArrayList<>(lines.size());
        for (String line : lines) {
            stripped.add(line.replaceAll("slot \\d+", "slot ?"));
        }
        return stripped;
    }

    // ------------------------------------------------------------- the journal as a planner input (the re-plan seam)

    /*
     * These three pin the mechanism decision E-D rests on: a divergence is answered by re-planning, and re-planning
     * terminates only because what the server refused is an INPUT to the next plan rather than something checked
     * afterwards. Everything below the process is pure, so the seam itself is testable even though the process that
     * drives it is not.
     */

    /**
     * An empty journal is not merely permitted, it is invisible.
     *
     * <p>The guard against the worst way this seam could go wrong: a filter that perturbed the search order would
     * change every plan the moment the executor existed, and every determinism assertion above would still pass
     * because both sides of them would have moved together. Compared against the {@code StanceFilter.ALL} baseline,
     * byte for byte.
     */
    @Test
    public void anEmptyJournalLeavesThePlanExactlyAsItWas() {
        PlanReport baseline = flatSlab();
        PlanReport withJournal = planOnce(box(3, 1, 3, Blocks.SHULKER_BOX.defaultBlockState()), tidyInventory(),
                new EvidenceJournal());

        assertEquals(PlanReport.Status.READY, withJournal.status());
        assertEquals(baseline.plan().toPlanLines(), withJournal.plan().toPlanLines());
        assertEquals(baseline.plan().toProofLines(), withJournal.plan().toProofLines());
    }

    /**
     * A refused triple is not proposed again — the property that makes "diverge, re-plan" converge instead of looping
     * at click cadence.
     *
     * <p>The build stays READY, and that is half the point: one cell on open floor has many ways to be placed, so
     * refusing one of them costs a different stance and nothing else. A journal that banned too much would show up
     * here as an INCOMPLETE report rather than as a subtly different plan.
     */
    @Test
    public void aTripleTheJournalRefusedIsNeverPlannedAgain() {
        PlanReport baseline = planOnce(oneCell(), tidyInventory());
        BuildAction.Place refused = firstPlace(baseline);
        BlockPos cell = refused.solution().cell();
        BlockPos stance = refused.solution().stance();
        Direction face = refused.solution().face();

        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(cell, stance, face, EvidenceJournal.Reason.REVERTED, 100L);

        PlanReport replanned = planOnce(oneCell(), tidyInventory(), journal);

        assertEquals("one refused stance out of many must not block a single cell on open floor: "
                        + replanned.headline(), PlanReport.Status.READY, replanned.status());
        for (int index = 0; index < replanned.plan().size(); index++) {
            if (!(replanned.plan().action(index) instanceof BuildAction.Place place)) {
                continue;
            }
            PlacementSolution solution = place.solution();
            boolean sameTriple = solution.cell().equals(cell) && solution.stance().equals(stance)
                    && solution.face() == face;
            assertFalse("the re-plan proposed the very triple the server refused: " + solution.toProofLine(),
                    sameTriple);
        }
        // And it really did have to move: the baseline used that triple, so an unchanged plan would mean the filter
        // was never consulted.
        assertNotEquals(baseline.plan().toProofLines(), replanned.plan().toProofLines());
    }

    /**
     * A cell refused three structurally different ways stops having a solution at all, and comes back through the
     * ordinary blocker channel.
     *
     * <p>This is what stops the fourth plan from existing. The alternative — letting the planner keep finding stance
     * number four, five and six — is a build that spends the rest of the afternoon learning the same thing about a
     * protection region, which is the failure the whole acknowledgement layer was built to see once and then stop.
     */
    @Test
    public void aCellTheJournalCallsExternallyBlockedComesBackAsABlocker() {
        PlanReport baseline = planOnce(oneCell(), tidyInventory());
        BlockPos cell = firstPlace(baseline).solution().cell();

        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(cell, cell.north(), Direction.SOUTH, EvidenceJournal.Reason.REVERTED, 100L);
        journal.reject(cell, cell.south(), Direction.NORTH, EvidenceJournal.Reason.REVERTED, 101L);
        EvidenceJournal.CellStatus status =
                journal.reject(cell, cell.east(), Direction.WEST, EvidenceJournal.Reason.REVERTED, 102L);
        assertEquals(EvidenceJournal.CellStatus.EXTERNALLY_BLOCKED, status);

        // No helper material: with the cell unsolvable the frontier empties, and the scaffold escalation that would
        // otherwise run is the one path this fixture cannot survive. See planOnce(…, helper).
        PlanReport replanned = planOnce(oneCell(), tidyInventory(), journal, null);

        assertEquals(PlanReport.Status.INCOMPLETE, replanned.status());
        boolean named = false;
        for (PlanReport.Blocker blocker : replanned.blockers()) {
            named |= blocker.cell().equals(cell);
        }
        assertTrue("the externally blocked cell must be named in the report, not silently dropped: "
                + replanned.headline(), named);
    }

    /** The first placement of a plan, for a test that needs one real proven triple. */
    private static BuildAction.Place firstPlace(PlanReport report) {
        for (int index = 0; index < report.plan().size(); index++) {
            if (report.plan().action(index) instanceof BuildAction.Place place) {
                return place;
            }
        }
        fail("the fixture plan contains no placement at all: " + report.headline());
        throw new AssertionError("unreachable");
    }
}
