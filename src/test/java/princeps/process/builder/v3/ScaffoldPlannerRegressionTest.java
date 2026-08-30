/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder.v3;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;
import princeps.api.utils.RotationUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Focused regressions for the scaffold geometry that the Basalt dry run depends on. */
public class ScaffoldPlannerRegressionTest {

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

    private static PredictedWorld world(PredictedWorld.StateSource states) {
        return world(states, (x, z) -> true);
    }

    private static PredictedWorld world(PredictedWorld.StateSource states, PredictedWorld.LoadedTest loaded) {
        return PredictedWorld.capture(states, loaded, new Vec3i(-5, 60, -5), new Vec3i(5, 72, 5), 0,
                V3Settings.defaults());
    }

    @Test
    public void standingDirtIsAValidRemovalTargetAndItsEnteredFaceIsPreserved() {
        BlockPos cell = new BlockPos(0, 66, 0);
        PredictedWorld world = world((x, y, z) -> y == 65
                ? Blocks.AZALEA.defaultBlockState() : Blocks.AIR.defaultBlockState());
        world.apply(cell, Blocks.DIRT.defaultBlockState());

        ScaffoldPlanner.Aimed aimed = ScaffoldPlanner.aimAtBlock(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT,
                cell, new BlockPos(2, 66, 0), true).orElseThrow();
        Vec3 eye = PlayerPose.CROUCHED.eyeAt(aimed.approach());
        GridRay.Hit shortHit = world.clip(eye, aimed.point());

        assertTrue("the inset endpoint is inside the standing full cube", shortHit != null);
        assertTrue(ScaffoldPlanner.hits(shortHit, cell, aimed.face()));
        assertTrue(ScaffoldPlanner.removalOf(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT, cell,
                Blocks.DIRT.defaultBlockState(), aimed.stance(), 66).isPresent());
    }

    @Test
    public void partialAzaleaRemainsRemovable() {
        BlockPos cell = new BlockPos(0, 66, 0);
        PredictedWorld world = world((x, y, z) -> y == 65
                ? Blocks.AZALEA.defaultBlockState() : Blocks.AIR.defaultBlockState());
        world.apply(cell, Blocks.AZALEA.defaultBlockState());

        assertTrue(ScaffoldPlanner.removalOf(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT, cell,
                Blocks.AZALEA.defaultBlockState(), new BlockPos(2, 66, 0), 66).isPresent());
    }

    @Test
    public void aRealOccluderStillRejectsTheOnlyLoadedRemovalStance() {
        BlockPos cell = new BlockPos(0, 66, 0);
        PredictedWorld world = world((x, y, z) -> {
            if (x == 1 && z == 0 && (y == 66 || y == 67)) {
                return Blocks.DIRT.defaultBlockState();
            }
            return y == 65 ? Blocks.AZALEA.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }, (x, z) -> x == 3 && z == 0);
        world.apply(cell, Blocks.DIRT.defaultBlockState());

        assertTrue(ScaffoldPlanner.aimAtBlock(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT, cell,
                new BlockPos(3, 66, 0), true).isEmpty());
    }

    @Test
    public void theTargetCellWithTheWrongEnteredFaceIsNotAccepted() {
        BlockPos cell = new BlockPos(0, 66, 0);
        PredictedWorld world = world((x, y, z) -> Blocks.AIR.defaultBlockState());
        world.apply(cell, Blocks.DIRT.defaultBlockState());
        Vec3 eye = new Vec3(3.5D, 66.5D, 0.5D);
        GridRay.Hit hit = world.clip(eye, new Vec3(0.5D, 66.5D, 0.5D));

        assertTrue(hit != null);
        assertEquals(Direction.EAST, hit.face());
        assertTrue(ScaffoldPlanner.hits(hit, cell, Direction.EAST));
        assertFalse(ScaffoldPlanner.hits(hit, cell, Direction.WEST));
    }

    @Test
    public void helperEligibilityRequiresExactLiveAir() {
        BlockPos water = new BlockPos(0, 66, 0);
        BlockPos grass = new BlockPos(1, 66, 0);
        BlockPos air = new BlockPos(2, 66, 0);
        PredictedWorld world = world((x, y, z) -> {
            if (x == water.getX() && y == water.getY() && z == water.getZ()) {
                return Blocks.WATER.defaultBlockState();
            }
            if (x == grass.getX() && y == grass.getY() && z == grass.getZ()) {
                return Blocks.SHORT_GRASS.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        });

        assertEquals(ScaffoldPlanner.Refusal.NOT_REPLACEABLE,
                ScaffoldPlanner.eligibilityOf(world, null, water, 66));
        assertEquals(ScaffoldPlanner.Refusal.NOT_REPLACEABLE,
                ScaffoldPlanner.eligibilityOf(world, null, grass, 66));
        assertEquals(ScaffoldPlanner.Refusal.NONE,
                ScaffoldPlanner.eligibilityOf(world, null, air, 66));
    }

    @Test
    public void upwardFacingPistonUsesClickHelperPlusStanceFloorAndRemovesThemInReverse() {
        BlockPos target = new BlockPos(0, 67, 0);
        BlockState desired = Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        ISchematic schematic = oneCell(desired);
        PredictedWorld world = world((x, y, z) -> y == 65
                ? Blocks.AZALEA.defaultBlockState() : Blocks.AIR.defaultBlockState());
        SchematicView view = SchematicView.capture("two-helper", schematic, target, world, List.of());
        HotbarSchedule.InventorySnapshot inventory = inventory(Items.PISTON, Items.DIRT);
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        ScaffoldPlanner planner = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, Items.DIRT,
                HotbarSchedule.THROWAWAY_SLOT);
        OrderPlanner.PlannerState state = new OrderPlanner.PlannerState(oracle, view, settings, SolveBudget.DEFAULT,
                inventory, new BlockPos(-3, 66, -3));

        ScaffoldPlanner.Escalation escalation = planner.escalate(world, state, List.of(target)).orElseThrow();
        BlockPos clickHelper = target.below();
        BlockPos stanceFloor = escalation.solution().stance().below();

        assertEquals(2, escalation.scaffolds().size());
        assertEquals(stanceFloor, escalation.scaffolds().get(0).cell());
        assertEquals(clickHelper, escalation.scaffolds().get(1).cell());
        assertEquals(clickHelper, escalation.solution().against());
        assertFalse(stanceFloor.equals(clickHelper));
        assertEquals(5, escalation.actions().size());
        assertTrue(escalation.actions().get(0) instanceof BuildAction.PlaceScaffold);
        assertTrue(escalation.actions().get(1) instanceof BuildAction.PlaceScaffold);
        assertEquals(clickHelper, escalation.actions().get(3).cell());
        assertEquals(stanceFloor, escalation.actions().get(4).cell());

        for (BuildAction action : escalation.actions()) {
            state.commit(world, List.of(action));
        }
        assertTrue(state.ledger().open().isEmpty());
        assertTrue(world.get(clickHelper).is(Blocks.AIR));
        assertTrue(world.get(stanceFloor).is(Blocks.AIR));
        assertEquals(desired, world.get(target));
    }

    @Test
    public void oneBlockHigherButDisconnectedStancePlansOwnedAccessInsteadOfTakingAHeightShortcut() {
        BlockPos start = new BlockPos(0, 66, 0);
        BlockPos targetStance = new BlockPos(2, 67, 0);
        BlockPos target = new BlockPos(2, 67, 2);
        BlockState stone = Blocks.STONE.defaultBlockState();
        PredictedWorld world = world((x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            return pos.equals(start.below()) || pos.equals(targetStance.below()) || pos.equals(target.below())
                    ? stone : Blocks.AIR.defaultBlockState();
        });
        HotbarSchedule.InventorySnapshot inventory = inventory(Items.STONE, Items.DIRT);
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        SchematicView view = SchematicView.capture("one-step-access", oneCell(stone), target, world, List.of());
        OrderPlanner.PlannerState state = new OrderPlanner.PlannerState(oracle, view, settings, SolveBudget.DEFAULT,
                inventory, start);
        ScaffoldPlanner planner = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, Items.DIRT,
                HotbarSchedule.THROWAWAY_SLOT);

        PlacementOracle.Solve solved = oracle.solveFrom(world, target, stone, inventory.slots(),
                SolveBudget.DEFAULT, targetStance, state.journal());
        assertTrue(solved.explain(), solved.solved());
        PlacementSolution candidate = solved.best().orElseThrow();
        assertFalse("the target really is disconnected before the helper stands",
                ScaffoldPlanner.stanceReachableWithoutMutation(world, start, targetStance));

        ScaffoldPlanner.Access access = planner.accessToStance(world, state, candidate);

        assertEquals("a one-block vertical delta is not evidence of an actual route",
                ScaffoldPlanner.AccessKind.VIA_SCAFFOLD, access.kind());
        assertNotNull(access.scaffold());
        ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(world);
        try {
            scratch.apply(access.scaffold().cell(), access.scaffold().predicted());
            assertTrue("the staged helper must connect its placement stance to the target stance",
                    ScaffoldPlanner.stanceReachableWithoutMutation(
                            world, access.scaffold().stance(), access.target().stance()));
        } finally {
            scratch.restore();
        }
    }

    @Test
    public void routeDeferralExpiresAfterACommittedWorldChange() {
        BlockPos cell = new BlockPos(0, 66, 0);
        BlockPos stance = new BlockPos(2, 66, 0);
        BlockPos against = cell.below();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PredictedWorld world = world((x, y, z) -> y == 65 ? stone : Blocks.AIR.defaultBlockState());
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        SchematicView view = SchematicView.capture("route-deferral", oneCell(stone), cell, world, List.of());
        OrderPlanner.PlannerState state = new OrderPlanner.PlannerState(oracle, view, settings, SolveBudget.DEFAULT,
                inventory(Items.STONE, Items.DIRT), stance);
        Vec3 approach = new Vec3(stance.getX() + 0.5D, stance.getY(), stance.getZ() + 0.5D);
        Vec3 aim = new Vec3(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.01D);
        PlacementSolution candidate = new PlacementSolution(cell, stone, stance, approach, against, Direction.UP,
                aim, RotationUtils.calcRotationFromVec3d(PlayerPose.CROUCHED.eyeAt(approach), aim,
                new princeps.api.utils.Rotation(0.0F, 0.0F)), 1.0D, stone, Items.STONE);

        assertTrue(state.journal().allows(cell, stance, Direction.UP));
        assertTrue(state.deferRoute(candidate));
        assertFalse("route-only evidence must suppress the exact triple in the unchanged world",
                state.journal().allows(cell, stance, Direction.UP));

        LongOpenHashSet dirty = new LongOpenHashSet();
        state.releaseRouteDeferrals(dirty);

        assertTrue("a world mutation must reopen the route without erasing permanent click vetoes",
                state.journal().allows(cell, stance, Direction.UP));
        assertTrue("the reopened cell must be re-solved against the new walk graph",
                dirty.contains(PlacementGeometry.positionKey(cell)));
    }

    @Test
    public void initialAnchorOutsideTheBoundedSnapshotEntersThroughItsExteriorComponentOnly() {
        BlockPos outside = new BlockPos(-50, 66, 0);
        BlockPos target = new BlockPos(2, 66, 2);
        BlockPos targetStance = new BlockPos(2, 66, 0);
        BlockState stone = Blocks.STONE.defaultBlockState();
        PredictedWorld world = world((x, y, z) -> y == 65 ? stone : Blocks.AIR.defaultBlockState());
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        HotbarSchedule.InventorySnapshot inventory = inventory(Items.STONE, Items.DIRT);
        SchematicView view = SchematicView.capture("outside-entry", oneCell(stone), target, world, List.of());
        OrderPlanner.PlannerState state = new OrderPlanner.PlannerState(oracle, view, settings, SolveBudget.DEFAULT,
                inventory, outside);
        ScaffoldPlanner planner = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, Items.DIRT,
                HotbarSchedule.THROWAWAY_SLOT);
        PlacementSolution candidate = oracle.solveFrom(world, target, stone, inventory.slots(), SolveBudget.DEFAULT,
                targetStance, state.journal()).best().orElseThrow();

        assertEquals("the finite proof starts at a boundary portal; it does not need a snapshot of the whole journey",
                ScaffoldPlanner.AccessKind.DIRECT, planner.accessToStance(world, state, candidate).kind());

        state.commit(world, List.of(new BuildAction.SwapHotbar(
                1, Items.STONE, 1, false, 1, target.getY())));
        assertEquals("only the real initial anchor receives boundary-entry semantics",
                ScaffoldPlanner.AccessKind.UNREACHABLE, planner.accessToStance(world, state, candidate).kind());
    }

    @Test
    public void unreachableScaffoldRemovalStanceIsRefused() {
        RemovalFixture fixture = removalFixture(false);
        BlockPos far = new BlockPos(6, 66, 0);
        fixture.state().ledger().place(far, far, PlanReport.ScaffoldNote.NO_SCHEMATIC_CELL);

        List<BuildAction> removals = fixture.planner().closeLayer(fixture.world(), fixture.state(), 66);

        assertTrue("a visible stance in a disconnected component is not an executable SCAFFOLD-",
                removals.isEmpty());
        assertEquals(1, fixture.state().blockers().size());
        assertTrue(fixture.state().ledger().isEmpty());
    }

    /**
     * A flat floor with a two-high wall at {@code x == 3}: the body starts west of it and the only stance that can
     * reach the target cell is east of it, so {@code accessToStance} has to take the non-direct path.
     */
    private static PredictedWorld walledWorld() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        return world((x, y, z) -> {
            if (y == 65) {
                return stone;
            }
            if (x == 3 && (y == 66 || y == 67)) {
                return stone;
            }
            return Blocks.AIR.defaultBlockState();
        });
    }

    private static OrderPlanner.PlannerState walledState(PredictedWorld world, BlockPos start) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        SchematicView view = SchematicView.capture("walled", oneCell(stone), new BlockPos(5, 66, 0), world, List.of());
        return new OrderPlanner.PlannerState(oracle, view, settings, SolveBudget.DEFAULT,
                inventory(Items.STONE, Items.DIRT), start);
    }

    /**
     * The route component is flooded ONCE per {@code accessToStance}, not twice.
     *
     * <p>{@code reaches} at the DIRECT test and the route component below it flood from the same start, through the
     * same world, inside one call — the second recomputes exactly what the first threw away. Measured on the basalt
     * dry run, layer -58 spent 637 s of 1343 s in floods, and in the regime that dominates its first thousand cells
     * the ratio is exactly three floods per deferral: the DIRECT probe, the target component, and the route
     * component. Two of those three are avoidable, and this pins that they stay avoided.
     *
     * <p>The bound is on ONE call so the count cannot drift with the fixture's helper candidates: a call that reaches
     * no helper floods once for the route, and the target component is only needed once a helper survives its guards.
     */
    @Test
    public void aNonDirectAccessFloodsTheRouteComponentOnlyOnce() {
        PredictedWorld world = walledWorld();
        BlockPos start = new BlockPos(0, 66, 0);
        BlockPos stance = new BlockPos(4, 66, 0);
        OrderPlanner.PlannerState state = walledState(world, start);
        ScaffoldPlanner planner = new ScaffoldPlanner(state.oracle(), V3Settings.defaults(), SolveBudget.DEFAULT,
                Items.DIRT, HotbarSchedule.THROWAWAY_SLOT);
        PlacementSolution candidate = state.oracle().solveFrom(world, new BlockPos(5, 66, 0),
                Blocks.STONE.defaultBlockState(), state.stacks(), state.budget(), stance, state.journal())
                .best().orElseThrow();

        long before = planner.costCensus()[2];
        ScaffoldPlanner.Access access = planner.accessToStance(world, state, candidate);
        long floods = planner.costCensus()[2] - before;

        assertEquals("the wall makes this the non-direct path; if it is DIRECT the fixture proves nothing",
                ScaffoldPlanner.AccessKind.UNREACHABLE, access.kind());
        assertEquals("one flood for the route component, and no second flood of the same start", 1L, floods);
    }

    /**
     * Standing on the target stance is not the same as being able to stand there.
     *
     * <p>{@code reaches} checks its guards BEFORE its reflexive branch: {@code inBounds} and {@code accessStandable}
     * on both sides, and only then {@code start.equals(target)}. A short-circuit that reads the equality first would
     * answer DIRECT for a body that cannot occupy the stance at all — and nothing downstream would catch it, because
     * the cell count RISES when an UNREACHABLE turns into a DIRECT and the dry-run guard only refuses a count that
     * falls.
     */
    @Test
    public void aStanceTheBodyCannotOccupyIsNotDirectEvenWhenItIsWhereTheBodyStands() {
        PredictedWorld world = walledWorld();
        BlockPos stance = new BlockPos(4, 66, 0);
        OrderPlanner.PlannerState state = walledState(world, stance);
        ScaffoldPlanner planner = new ScaffoldPlanner(state.oracle(), V3Settings.defaults(), SolveBudget.DEFAULT,
                Items.DIRT, HotbarSchedule.THROWAWAY_SLOT);
        PlacementSolution candidate = state.oracle().solveFrom(world, new BlockPos(5, 66, 0),
                Blocks.STONE.defaultBlockState(), state.stacks(), state.budget(), stance, state.journal())
                .best().orElseThrow();

        // The stance the body is recorded at is filled in after the solve: same position, no longer standable.
        world.apply(stance, Blocks.STONE.defaultBlockState());

        assertEquals("a stance that is not access-standable is unreachable, even reflexively",
                ScaffoldPlanner.AccessKind.UNREACHABLE, planner.accessToStance(world, state, candidate).kind());
    }

    @Test
    public void scaffoldRemovalsAreProvenSequentiallyFromTheLocalCursor() {
        RemovalFixture fixture = removalFixture(true);
        BlockPos far = new BlockPos(6, 66, 0);
        BlockPos gate = new BlockPos(2, 66, 0);
        fixture.state().ledger().place(far, far, PlanReport.ScaffoldNote.NO_SCHEMATIC_CELL);
        fixture.state().ledger().place(gate, far, PlanReport.ScaffoldNote.NO_SCHEMATIC_CELL);

        List<BuildAction> removals = fixture.planner().closeLayer(fixture.world(), fixture.state(), 66);

        assertEquals(2, removals.size());
        BuildAction.RemoveScaffold first = (BuildAction.RemoveScaffold) removals.get(0);
        BuildAction.RemoveScaffold second = (BuildAction.RemoveScaffold) removals.get(1);
        assertEquals(gate, first.cell());
        assertEquals(far, second.cell());
        assertFalse("before the gate removal, the second action's stance is disconnected",
                ScaffoldPlanner.stanceReachableWithoutMutation(
                        fixture.world(), fixture.state().lastStance(), second.stance()));

        ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(fixture.world());
        try {
            scratch.apply(gate, Blocks.AIR.defaultBlockState());
            assertTrue("after the first SCAFFOLD-, the local cursor can reach the second stance without mutation",
                    ScaffoldPlanner.stanceReachableWithoutMutation(
                            fixture.world(), first.stance(), second.stance()));
        } finally {
            scratch.restore();
        }
        assertTrue(fixture.state().blockers().isEmpty());
    }

    private static RemovalFixture removalFixture(boolean removableGate) {
        BlockPos start = new BlockPos(0, 66, 0);
        BlockPos far = new BlockPos(6, 66, 0);
        BlockPos gate = new BlockPos(2, 66, 0);
        PredictedWorld world = PredictedWorld.capture((x, y, z) -> {
            if (z == 0 && y == 65 && x >= 0 && x <= 6) {
                return Blocks.STONE.defaultBlockState();
            }
            if (new BlockPos(x, y, z).equals(far)) {
                return Blocks.DIRT.defaultBlockState();
            }
            if (new BlockPos(x, y, z).equals(gate)) {
                return removableGate ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
            }
            if (new BlockPos(x, y, z).equals(gate.above(2))) {
                // The gate block must be a wall, not a one-block stair the conservative walker can simply climb.
                return Blocks.STONE.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }, (x, z) -> true, new Vec3i(-1, 63, -1), new Vec3i(7, 70, 2), 0, V3Settings.defaults());
        BlockPos schematicCell = new BlockPos(0, 66, 2);
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        SchematicView view = SchematicView.capture("removal-route", oneCell(Blocks.STONE.defaultBlockState()),
                schematicCell, world, List.of());
        OrderPlanner.PlannerState state = new OrderPlanner.PlannerState(oracle, view, settings, SolveBudget.DEFAULT,
                inventory(Items.STONE, Items.DIRT), start);
        ScaffoldPlanner planner = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, Items.DIRT,
                HotbarSchedule.THROWAWAY_SLOT);
        return new RemovalFixture(world, state, planner);
    }

    private record RemovalFixture(PredictedWorld world, OrderPlanner.PlannerState state, ScaffoldPlanner planner) {
    }

    private static ISchematic oneCell(BlockState desired) {
        return new ISchematic() {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                return desired;
            }

            @Override
            public int widthX() {
                return 1;
            }

            @Override
            public int heightY() {
                return 1;
            }

            @Override
            public int lengthZ() {
                return 1;
            }
        };
    }

    private static HotbarSchedule.InventorySnapshot inventory(Item target, Item scaffold) {
        List<ItemStack> slots = new ArrayList<>(HotbarSchedule.INVENTORY_SLOTS);
        for (int index = 0; index < HotbarSchedule.INVENTORY_SLOTS; index++) {
            slots.add(ItemStack.EMPTY);
        }
        slots.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.DIAMOND_PICKAXE));
        slots.set(1, new ItemStack(target, 64));
        slots.set(HotbarSchedule.THROWAWAY_SLOT, new ItemStack(scaffold, 64));
        return new HotbarSchedule.InventorySnapshot(slots);
    }
}
