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
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The three guards of 5.4, each against the world it was written for.
 *
 * <p>Every test here builds its world by hand and calls the parameter-taking form of the invariant rather than the
 * {@code PlannerState} one. That is not a shortcut around the real code path — it is the only path that exists
 * headless, and the reasons are worth restating because they shaped the signatures:
 *
 * <ul>
 *   <li>{@code new ItemStack(Items.PISTON)} throws {@code NullPointerException: Components not bound yet} after
 *       {@code Bootstrap.bootStrap()}, so no stack that can place anything exists in a test JVM by default.
 *       Invariant P therefore takes an {@link Invariants.CellSolver}, and the piston test states the upward-family
 *       rule from plan 5.7 directly instead of asking an oracle.
 *       <p><b>Corrected:</b> this bullet used to end "and {@link PlacementOracle} cannot be called from JUnit at
 *       all", which is not true. Binding the item components by hand makes {@link net.minecraft.world.item.ItemStack}
 *       constructible, and {@code OrderPlannerTest} runs the whole planner over a real oracle on that basis. The
 *       {@link Invariants.CellSolver} seam is still the right design — it keeps P a pure function and lets the piston
 *       test state 5.7's rule as a sentence — but it is a choice, not the only option. What genuinely constrains an
 *       oracle test is much narrower and is documented on {@code OrderPlannerTest}: no candidate stance's feet or head
 *       may hold a block that reaches {@code MovementHelper.java:155}.</li>
 *   <li>{@link PredictedWorld#isStandable} reaches {@code Princeps.settings().blocksToAvoid} for every state that is
 *       not air, which dies in a static initialiser under JUnit — trap 1.7, re-verified against this build. Invariant
 *       S uses its own settings-free stance test, and these worlds are made of stone so that the test would notice
 *       if that ever changed.</li>
 * </ul>
 *
 * <p>What is being pinned is the SET behaviour, not the geometry: {@code PlacementGeometryTest} already owns the
 * question of which way round vanilla reads a look. These three tests own the question the old builder never asked —
 * whether a sequence of individually legal placements is legal as a sequence.
 */
public class InvariantsTest {

    /** Real vanilla block states, headless. Nothing in this file works before it runs. */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final PlayerPose POSE = PlayerPose.CROUCHED;

    // ------------------------------------------------------------------------------------------------- invariant E

    /**
     * The y=112 avalanche in miniature: a 3x3x3 room with one doorway, the bot inside it, and the doorway as the
     * candidate. Filling it is a perfectly legal placement that nothing else in the planner would refuse.
     */
    @Test
    public void enclosureRejectsThePlacementThatSealsTheBotIn() {
        Cells cells = room();
        PredictedWorld world = world(cells);
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);

        assertTrue("the room is open before the doorway is filled", tracker.connectedToOutside(INSIDE));
        assertFalse("filling the doorway from inside seals the bot in",
                Invariants.enclosureFree(world, tracker, INSIDE, DOORWAY, stone()));
    }

    /**
     * The same placement, made from the retreat direction. One block, one world, one difference — where the bot is
     * standing — and that is the whole of what the retreat ordering buys.
     */
    @Test
    public void enclosureAcceptsTheSamePlacementMadeFromTheRetreatDirection() {
        PredictedWorld world = world(room());
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);

        assertTrue("standing outside the doorway, filling it seals nothing the bot is in",
                Invariants.enclosureFree(world, tracker, OUTSIDE, DOORWAY, stone()));
    }

    /** The marking has to survive the commit, not only the speculation: after the seal the room is off the outside
     *  component, and the count says by how much. */
    @Test
    public void committingTheSealTakesTheWholeRoomOffTheOutsideComponent() {
        Cells cells = room();
        PredictedWorld world = world(cells);
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);
        int before = tracker.reachableCells();

        // The order PlannerState.fill uses: the world first, then the marking.
        world.apply(DOORWAY, stone());
        tracker.apply(world, DOORWAY);

        assertFalse("the bot's cell is no longer connected to the outside", tracker.connectedToOutside(INSIDE));
        // 27 interior cells plus the doorway itself.
        assertEquals(28, before - tracker.reachableCells());
    }

    /** And back out again: breaking the doorway open re-merges the pocket with the outside, which is the only
     *  direction {@code free} has to get right. */
    @Test
    public void breakingTheSealMergesThePocketBackIntoTheOutside() {
        PredictedWorld world = world(room());
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);
        int open = tracker.reachableCells();

        world.apply(DOORWAY, stone());
        tracker.apply(world, DOORWAY);
        world.apply(DOORWAY, Blocks.AIR.defaultBlockState());
        tracker.free(world, DOORWAY);

        assertTrue(tracker.connectedToOutside(INSIDE));
        assertEquals(open, tracker.reachableCells());
    }

    /** A placement in open air is the case that runs a hundred thousand times per dry run, and it must not cost a
     *  flood fill or produce a verdict. */
    @Test
    public void enclosureIgnoresAPlacementInOpenAir() {
        PredictedWorld world = world(room());
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);

        assertTrue(Invariants.enclosureFree(world, tracker, INSIDE, new BlockPos(5, 5, 5), stone()));
        assertTrue(tracker.connectedToOutside(INSIDE));
    }

    /**
     * A torch in the doorway is not a wall. The marking is {@link PredictedWorld#isSolidFullCube}, so E must ask what
     * LANDS rather than what is placed, or every redstone component in the schematic would be treated as masonry.
     */
    @Test
    public void enclosureIgnoresAPlacementThatDoesNotFillItsCell() {
        PredictedWorld world = world(room());
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);

        assertTrue(Invariants.enclosureFree(world, tracker, INSIDE, DOORWAY, Blocks.TORCH.defaultBlockState()));
    }

    /** A bot that BEGINS inside a closed room is an ordinary interior build, and E must not turn into a policy that
     *  forbids one. */
    @Test
    public void enclosureIsDisarmedWhenTheBotStartedSealedIn() {
        Cells cells = room().set(DOORWAY, Blocks.STONE);
        PredictedWorld world = world(cells);
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);

        assertFalse(tracker.connectedToOutside(INSIDE));
        assertTrue("every placement would otherwise be refused for a seal the plan did not make",
                Invariants.enclosureFree(world, tracker, INSIDE, new BlockPos(1, 1, 1), stone()));
    }

    // ------------------------------------------------------------------------------------------------- invariant P

    /**
     * THE case, the one etz-basalt sets 448 times.
     *
     * <p>A {@code facing=down} piston at T is clicked on the underside of the block at {@code T+UP}, from a stance one
     * layer below and one cell to the side; the crouched head then occupies that side neighbour of T. Three of the
     * four laterals are already stone. Filling the fourth is a legal placement, its own geometry is fine, and it
     * leaves the piston with nowhere to put the bot's head.
     */
    @Test
    public void lastSolutionRejectsFillingTheFourthLateralOfAnOpenPiston() {
        PredictedWorld world = pistonWorld();
        DependencyIndex index = new DependencyIndex();
        index.put(PISTON, pistonSolution(), POSE, world);

        // The piston is solvable while the head room is open — otherwise the test below would prove nothing.
        assertTrue(UPWARD_FAMILY.hasSolution(world, PISTON));

        Optional<Invariants.Violation> violation = Invariants.lastSolutionsPreserved(
                world, index, HEAD_ROOM, stone(), UPWARD_FAMILY);

        assertTrue("filling the last free lateral takes the piston's last solution", violation.isPresent());
        assertEquals(Invariants.Which.P, violation.get().invariant());
        assertEquals(PISTON, violation.get().victim());
        assertEquals(HEAD_ROOM, violation.get().candidate());
        assertTrue("the report names the resource that was lost, not merely that one was: " + violation.get().detail(),
                violation.get().detail().contains("head room"));
    }

    /**
     * The same cell, the same placement, after the piston has gone in — which is what the frontier does with it
     * anyway, because a piston whose head room is open is IN the frontier and its neighbours are not blocked.
     *
     * <p>{@code forget} is the planner's own step: {@code PlannerState.fill} invalidates a cell the moment it is
     * applied, because a finished cell cannot lose a solution and an index that kept it would veto this placement for
     * the rest of the layer.
     */
    @Test
    public void lastSolutionAcceptsTheSameLateralOnceThePistonStands() {
        PredictedWorld world = pistonWorld();
        DependencyIndex index = new DependencyIndex();
        index.put(PISTON, pistonSolution(), POSE, world);

        world.apply(PISTON, pistonDown());
        index.forget(PISTON);

        assertEquals(Optional.empty(),
                Invariants.lastSolutionsPreserved(world, index, HEAD_ROOM, stone(), UPWARD_FAMILY));
    }

    /** The speculative apply has to come back out again — every candidate P refuses is a candidate the layer loop
     *  goes on planning around, and a world left holding a refused placement would poison every later proof. */
    @Test
    public void lastSolutionLeavesTheWorldExactlyAsItFoundIt() {
        PredictedWorld world = pistonWorld();
        DependencyIndex index = new DependencyIndex();
        index.put(PISTON, pistonSolution(), POSE, world);
        int deltas = world.deltaCount();

        Invariants.lastSolutionsPreserved(world, index, HEAD_ROOM, stone(), UPWARD_FAMILY);

        assertTrue(world.get(HEAD_ROOM).isAir());
        assertEquals(deltas, world.deltaCount());
    }

    /** And it must restore a DELTA, not the snapshot: {@code revert} puts the captured terrain back, which is the
     *  right answer only for a cell the plan has not written yet. Here an earlier BREAK emptied the click face, and
     *  P speculating on the same cell must not quietly undo it. */
    @Test
    public void lastSolutionRestoresAnEarlierActionsWriteRatherThanTheSnapshot() {
        PredictedWorld world = pistonWorld();
        DependencyIndex index = new DependencyIndex();
        index.put(PISTON, pistonSolution(), POSE, world);

        BlockPos clickFace = new BlockPos(0, 66, 0);
        world.apply(clickFace, Blocks.AIR.defaultBlockState());
        Invariants.lastSolutionsPreserved(world, index, clickFace, stone(), UPWARD_FAMILY);

        assertTrue("the break survived the invariant's speculation", world.get(clickFace).isAir());
    }

    /** Nothing depends on most positions, and that answer must not cost a solve. */
    @Test
    public void lastSolutionIsSilentWhereNothingDependsOnThePosition() {
        PredictedWorld world = pistonWorld();
        DependencyIndex index = new DependencyIndex();
        index.put(PISTON, pistonSolution(), POSE, world);

        assertEquals(Optional.empty(), Invariants.lastSolutionsPreserved(world, index, new BlockPos(4, 65, 4),
                stone(), (ignored, cell) -> {
                    throw new AssertionError("no solve may be spent on a position with no dependents");
                }));
    }

    // ------------------------------------------------------------------------------------------------- invariant S

    /**
     * The normal case under the hard layer rule: a helper block on L+1 with nothing over it. Plan 5.5's whole
     * argument is that this needs no search at all, and the test exists to keep the shortcut honest by contrast with
     * the next one.
     */
    @Test
    public void scaffoldAtLayerAboveIsRemovableUnderOpenSky() {
        PredictedWorld world = world(platform().set(HELPER, Blocks.STONE));

        assertEquals(Optional.empty(), Invariants.scaffoldRemovable(world, POSE, List.of(HELPER), LAYER,
                CANDIDATE_CELL, stone()));
    }

    /**
     * The one case the layer rule does not cover: the schematic sits in a real world, and this one has bedrock over
     * the build site. "Everything above L+1 is empty" is a statement about the plan; the snapshot is the only place
     * the world gets a say.
     *
     * <p>The two worlds differ by exactly one thing — the bedrock slab at L+2 — so the rejection cannot be coming
     * from anywhere else. With it there, every stance beside the helper block has its head in bedrock and every
     * stance above it has no floor.
     */
    @Test
    public void scaffoldAtLayerAboveIsNotRemovableUnderPreExistingBedrock() {
        PredictedWorld world = world(platform().set(HELPER, Blocks.STONE)
                .fillSquare(LAYER + 2, 2, Blocks.BEDROCK));

        Optional<Invariants.Violation> violation = Invariants.scaffoldRemovable(world, POSE, List.of(HELPER), LAYER,
                CANDIDATE_CELL, stone());

        assertTrue("pre-existing terrain overhead removes the last breaking stance", violation.isPresent());
        assertEquals(Invariants.Which.S, violation.get().invariant());
        assertEquals(HELPER, violation.get().victim());
        assertTrue(violation.get().detail().contains("pre-existing terrain"));
    }

    /**
     * A helper block at or below L is the other checked case, and the check has to be able to answer YES or the
     * rejection above is vacuous. Standing on the platform beside it, the bot looks down at its top face over a clear
     * line.
     */
    @Test
    public void scaffoldAtTheCurrentLayerIsCheckedAndCanStillPass() {
        PredictedWorld world = world(platform());

        assertEquals(Optional.empty(), Invariants.scaffoldRemovable(world, POSE, List.of(new BlockPos(0, LAYER, 0)),
                LAYER, CANDIDATE_CELL, stone()));
    }

    /** No open helper blocks is the answer for every layer that never escalated, and it costs nothing. */
    @Test
    public void scaffoldIsSilentWithAnEmptyLedger() {
        PredictedWorld world = world(platform());

        assertEquals(Optional.empty(), Invariants.scaffoldRemovable(world, POSE, List.of(), LAYER, CANDIDATE_CELL,
                stone()));
    }

    /** Same speculation, same obligation to put the world back. */
    @Test
    public void scaffoldLeavesTheWorldExactlyAsItFoundIt() {
        PredictedWorld world = world(platform().set(HELPER, Blocks.STONE)
                .fillSquare(LAYER + 2, 2, Blocks.BEDROCK));
        int deltas = world.deltaCount();

        Invariants.scaffoldRemovable(world, POSE, List.of(HELPER), LAYER, CANDIDATE_CELL, stone());

        assertTrue(world.get(CANDIDATE_CELL).isAir());
        assertEquals(deltas, world.deltaCount());
    }

    // ------------------------------------------------------------------------------------------- retreat ordering

    /** Deepest first, backing out towards the exit — and by walked distance, so a corridor is ordered by the path and
     *  not by the straight line. */
    @Test
    public void retreatOrderRunsFromTheDeepestCellBackToTheExit() {
        PredictedWorld world = world(new Cells());
        List<BlockPos> corridor = List.of(new BlockPos(2, 0, 0), new BlockPos(4, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(3, 0, 0));

        assertEquals(List.of(new BlockPos(4, 0, 0), new BlockPos(3, 0, 0), new BlockPos(2, 0, 0),
                        new BlockPos(1, 0, 0)),
                Invariants.retreatOrder(world, corridor, BlockPos.ZERO));
    }

    /** The input order must not reach the output. Two callers with the same set and different iteration orders are
     *  the way a hash container's order gets into a plan without anybody writing it down. */
    @Test
    public void retreatOrderDoesNotDependOnTheInputOrder() {
        PredictedWorld world = world(new Cells());
        List<BlockPos> forwards = List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0));
        List<BlockPos> backwards = List.of(new BlockPos(3, 0, 0), new BlockPos(2, 0, 0), new BlockPos(1, 0, 0));

        assertEquals(Invariants.retreatOrder(world, forwards, BlockPos.ZERO),
                Invariants.retreatOrder(world, backwards, BlockPos.ZERO));
    }

    /** Two cells the flood cannot reach are still ordered, and ordered identically every run: the sentinel sorts
     *  first and the positionKey tiebreak decides between them. */
    @Test
    public void retreatOrderPutsUnreachableCellsFirstAndBreaksTiesByPosition() {
        // A sealed pair inside solid rock: the flood from the exit never arrives.
        Cells cells = new Cells();
        for (int x = 2; x <= 6; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    cells.set(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        cells.set(new BlockPos(4, 0, 0), Blocks.AIR).set(new BlockPos(4, 0, 1), Blocks.AIR);
        PredictedWorld world = world(cells);

        List<BlockPos> sealed = List.of(new BlockPos(4, 0, 1), new BlockPos(4, 0, 0));
        assertEquals(List.of(new BlockPos(4, 0, 0), new BlockPos(4, 0, 1)),
                Invariants.retreatOrder(world, sealed, BlockPos.ZERO));
    }

    // ---------------------------------------------------------------------------------------------- determinism

    /** The contract of section 13, at the smallest scale it can be asserted at: the same world twice, byte for byte
     *  the same answers, including the incremental marking's own arithmetic. */
    @Test
    public void twoIdenticalRunsAgreeOnEverything() {
        assertEquals(sealRun(), sealRun());
    }

    private static String sealRun() {
        PredictedWorld world = world(room());
        Invariants.EnclosureTracker tracker = new Invariants.EnclosureTracker(world, INSIDE);
        StringBuilder trace = new StringBuilder();
        trace.append(tracker.reachableCells()).append('|');
        for (BlockPos cell : List.of(new BlockPos(5, 5, 5), DOORWAY, new BlockPos(0, 3, 0))) {
            trace.append(Invariants.enclosureFree(world, tracker, INSIDE, cell, stone())).append('|');
            world.apply(cell, stone());
            tracker.apply(world, cell);
            trace.append(tracker.reachableCells()).append('|')
                    .append(tracker.connectedToOutside(INSIDE)).append(';');
        }
        return trace.toString();
    }

    // ------------------------------------------------------------------------------------------------- the worlds

    /** The bot's cell, in the middle of the room. */
    private static final BlockPos INSIDE = new BlockPos(0, 0, 0);

    /** The one gap in the room's wall. */
    private static final BlockPos DOORWAY = new BlockPos(2, 0, 0);

    /** One step out through the doorway — where the bot stands after retreating. */
    private static final BlockPos OUTSIDE = new BlockPos(3, 0, 0);

    /** A 3x3x3 room with a stone shell and a single doorway. Everything beyond the shell is air out to the box
     *  boundary, which is what {@code EnclosureTracker} calls the outside. */
    private static Cells room() {
        Cells cells = new Cells();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) == 2) {
                        cells.set(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        return cells.set(DOORWAY, Blocks.AIR);
    }

    // ---- the piston row, one cell of it

    /** T, the {@code facing=down} piston. */
    private static final BlockPos PISTON = new BlockPos(0, 65, 0);

    /** The lateral the crouched head occupies — the resource the greedy frontier takes. */
    private static final BlockPos HEAD_ROOM = new BlockPos(1, 65, 0);

    /** One of the three laterals that are already stone. */
    private static final BlockPos SOLID_LATERAL = new BlockPos(-1, 65, 0);

    /**
     * The upward family of plan 5.7, as a sentence: a piston {@code facing=down} is clicked on the underside of the
     * block at {@code T+UP}, from a stance one layer below whose crouched head stands in one of T's four laterals. So
     * it is solvable exactly when there is something to click above and somewhere to put the head beside.
     *
     * <p>This is what {@link PlacementOracle} would answer, written out, because the oracle cannot be constructed in
     * a headless test — see the class javadoc. What is under test is P's machinery, not this rule.
     */
    private static final Invariants.CellSolver UPWARD_FAMILY = (world, cell) -> {
        if (!world.isSolidFullCube(cell.getX(), cell.getY() + 1, cell.getZ())) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos lateral = cell.relative(side);
            if (!world.isSolidFullCube(lateral.getX(), lateral.getY(), lateral.getZ())) {
                return true;
            }
        }
        return false;
    };

    /** Stone at {@code T+UP} to click, three of the four laterals filled, the fourth open. */
    private static PredictedWorld pistonWorld() {
        Cells cells = new Cells()
                .set(new BlockPos(0, 66, 0), Blocks.STONE)
                .set(SOLID_LATERAL, Blocks.STONE)
                .set(new BlockPos(0, 65, -1), Blocks.STONE)
                .set(new BlockPos(0, 65, 1), Blocks.STONE);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                cells.set(new BlockPos(x, 63, z), Blocks.STONE);   // the floor the stance rests on
            }
        }
        return PredictedWorld.capture(cells::at, (x, z) -> true, new Vec3i(-8, 58, -8), new Vec3i(8, 72, 8), 0);
    }

    /**
     * The solution the oracle would return for that piston, written out so the dependency index has a real footprint
     * to record: stance one layer below and one cell east, approach pushed to the inset so the look is dominantly UP,
     * clicking the underside of the block above T.
     *
     * <p>The numbers are the worked example of plan 5.7.2 — approach 0.3 off centre, aim 0.02 in from the far edge of
     * the face, margin 0.41 — and what matters for this test is only that the footprint contains
     * {@link DependencyIndex.Use#HEAD_ROOM} at {@link #HEAD_ROOM}.
     */
    private static PlacementSolution pistonSolution() {
        return new PlacementSolution(PISTON, pistonDown(), new BlockPos(1, 64, 0),
                new Vec3(1.3D, 64.0D, 0.5D), new BlockPos(0, 66, 0), Direction.DOWN,
                new Vec3(0.98D, 66.0D, 0.5D), new Rotation(0.0F, -75.0F), 0.41D, pistonDown(), Items.PISTON);
    }

    private static BlockState pistonDown() {
        return Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN);
    }

    // ---- the scaffold platform

    /** The layer being built. Zero so the platform sits inside the same captured box as everything else here; the
     *  arithmetic of 5.5 is about L, L+1 and L+2 and does not care what L is. */
    private static final int LAYER = 0;

    /** The helper block, on L+1. */
    private static final BlockPos HELPER = new BlockPos(0, LAYER + 1, 0);

    /** The cell the candidate placement fills — an ordinary hole in the layer being built, and irrelevant to the
     *  helper block's reachability, which is the point: S must answer about the LEDGER, not about the candidate. */
    private static final BlockPos CANDIDATE_CELL = new BlockPos(2, LAYER, 0);

    /** Layer L as a finished 5x5 platform with one hole. The helper block is added by the tests that have one, so
     *  that the layer-L case can use the very same platform without a block standing over its head. */
    private static Cells platform() {
        return new Cells()
                .fillSquare(LAYER, 2, Blocks.STONE)
                .set(CANDIDATE_CELL, Blocks.AIR);
    }

    // ------------------------------------------------------------------------------------------------- plumbing

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static PredictedWorld world(Cells cells) {
        return PredictedWorld.capture(cells::at, (x, z) -> true, new Vec3i(-8, -8, -8), new Vec3i(8, 8, 8), 0);
    }

    /** A hand-built neighbourhood. Everything not set is air. */
    private static final class Cells {

        private final Map<Long, BlockState> states = new HashMap<>();

        Cells set(BlockPos pos, net.minecraft.world.level.block.Block block) {
            this.states.put(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()), block.defaultBlockState());
            return this;
        }

        /** A square slab centred on the origin column, for platforms and ceilings. */
        Cells fillSquare(int y, int radius, net.minecraft.world.level.block.Block block) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    this.set(new BlockPos(x, y, z), block);
                }
            }
            return this;
        }

        BlockState at(int x, int y, int z) {
            return this.states.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }
    }
}
