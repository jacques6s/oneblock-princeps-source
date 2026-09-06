package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ExcavationRepairPolicyTest {
    private static BlockState AIR;
    private static BlockState STONE;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
    }

    private static ExcavationRepairPolicy.Bounds narrow(int width, int length) {
        return new ExcavationRepairPolicy.Bounds(10, 9 + width, 20, 25, 30, 29 + length, 20, 25, true);
    }

    private static ExcavationRepairPolicy.Census inspect(ExcavationRepairPolicy.Bounds bounds,
                                                        Map<BlockPos, BlockState> changes) {
        // Vanilla and BetterBlockPos compare equal but use different hash functions. Model world coordinates,
        // not the concrete position object's hash, just as the production BlockStateInterface lookup does.
        Map<Long, BlockState> cells = new HashMap<>();
        changes.forEach((pos, state) -> cells.put(pos.asLong(), state));
        return ExcavationRepairPolicy.inspect(bounds, pos -> cells.getOrDefault(pos.asLong(),
                        bounds.inside(pos.getX(), pos.getY(), pos.getZ()) ? AIR : STONE),
                pos -> {
                    BlockState state = cells.getOrDefault(pos.asLong(),
                            bounds.inside(pos.getX(), pos.getY(), pos.getZ()) ? AIR : STONE);
                    return state.isAir() || BuilderProcess.snakeTreatAsFluid(state, true);
                });
    }

    @Test
    public void permanentOutsideInputRequiresSealingEvenWhenTheInsideHasOnlyFallingFlow() {
        for (int width : new int[] {1, 2}) {
            var bounds = narrow(width, 30);
            for (BlockState liquid : new BlockState[] {Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState()}) {
                Map<BlockPos, BlockState> world = new HashMap<>();
                BlockPos inlet = new BlockPos(9, 23, 58);
                BlockPos tail = new BlockPos(10, 23, 58);
                world.put(inlet, liquid);
                world.put(tail, liquid.setValue(BlockStateProperties.LEVEL, 8));
                var wet = inspect(bounds, world);
                assertFalse(wet.clean());
                assertEquals(1, wet.repairs().size());
                assertEquals(inlet, wet.repairs().getFirst().pos());
                assertEquals(ExcavationRepairPolicy.Kind.SHELL_SOURCE, wet.repairs().getFirst().kind());

                world.put(inlet, Blocks.COBBLESTONE.defaultBlockState());
                var draining = inspect(bounds, world);
                assertTrue("the flow tail must not be plugged repeatedly", draining.repairs().isEmpty());
                assertEquals(1, draining.flowing());
                assertFalse("a sent seal is not a dry world", draining.clean());
                world.put(tail, AIR);
                assertTrue(inspect(bounds, world).clean());
            }
        }
    }

    @Test
    public void sourceBeyondTheOwnedShellIsStoppedAtItsFlowingBoundaryCell() {
        var bounds = narrow(1, 7);
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(new BlockPos(8, 23, 33), Blocks.WATER.defaultBlockState());
        BlockPos boundary = new BlockPos(9, 23, 33);
        world.put(boundary, Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 2));
        var census = inspect(bounds, world);
        assertEquals(1, census.repairs().size());
        assertEquals(boundary, census.repairs().getFirst().pos());
        assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP, census.repairs().getFirst().kind());
    }

    @Test
    public void finiteFlowWaitsUntilAirWithoutCreatingAPlacementGoal() {
        var bounds = narrow(1, 1);
        BlockPos interior = new BlockPos(10, 22, 30);
        for (BlockState liquid : new BlockState[] {Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState()}) {
            for (int level = 1; level <= 15; level++) {
                var census = inspect(bounds, Map.of(interior, liquid.setValue(BlockStateProperties.LEVEL, level)));
                assertFalse(census.clean());
                assertEquals(1, census.flowing());
                assertTrue(census.repairs().isEmpty());
            }
        }
        assertTrue(inspect(bounds, Map.of(interior, AIR)).clean());
    }

    @Test
    public void sourcePlugMustBeObservedThenMinedBeforeTheBandCanFinish() {
        var bounds = narrow(2, 7);
        BlockPos interior = new BlockPos(10, 22, 33);
        BlockState wetSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        var solid = inspect(bounds, Map.of(interior, wetSlab));
        assertFalse(solid.clean());
        assertEquals(1, solid.solid());
        assertTrue(solid.repairs().isEmpty());
        var source = inspect(bounds, Map.of(interior, wetSlab.getFluidState().createLegacyBlock()));
        assertEquals(ExcavationRepairPolicy.Kind.INTERNAL_SOURCE, source.repairs().getFirst().kind());
        var plugged = inspect(bounds, Map.of(interior, Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(plugged.clean());
        assertEquals(1, plugged.solid());
        assertTrue(inspect(bounds, Map.of()).clean());
    }

    @Test
    public void fullCensusIncludesRemoteWallsRoofAndBottomFloorButNotOutsideCorners() {
        var bounds = narrow(1, 7);
        Map<BlockPos, BlockState> gaps = new HashMap<>();
        for (BlockPos pos : new BlockPos[] {
                new BlockPos(9, 20, 36), new BlockPos(11, 25, 30), new BlockPos(10, 23, 29),
                new BlockPos(10, 23, 37), new BlockPos(10, 26, 36), new BlockPos(10, 19, 36),
                new BlockPos(9, 23, 29), new BlockPos(8, 23, 33)
        }) gaps.put(pos, AIR);
        var census = inspect(bounds, gaps);
        assertFalse(census.clean());
        assertEquals(6, census.repairs().size());
        assertEquals(1, census.repairs().stream().filter(r -> r.kind() == ExcavationRepairPolicy.Kind.BRIDGE).count());
    }

    @Test
    public void middleBandDoesNotFillTheFutureLayerOrRevisitTheTopRoof() {
        var bounds = new ExcavationRepairPolicy.Bounds(10, 11, 10, 30, 40, 46, 20, 22, true);
        for (BlockPos pos : new BlockPos[] {new BlockPos(10, 19, 43), new BlockPos(10, 31, 43)}) {
            assertNull(ExcavationRepairPolicy.repair(bounds, pos.getX(), pos.getY(), pos.getZ(), AIR, true, true));
            assertNull(ExcavationRepairPolicy.repair(bounds, pos.getX(), pos.getY(), pos.getZ(),
                    Blocks.WATER.defaultBlockState(), true, true));
        }
    }

    @Test
    public void everyNarrowRepairApproachStaysOnTheCurrentLevelInsideTheSelection() {
        for (var bounds : new ExcavationRepairPolicy.Bounds[] {narrow(1, 30), narrow(2, 30), narrow(1, 1), narrow(30, 2)}) {
            BetterBlockPos feet = new BetterBlockPos(bounds.minX(), 22, bounds.minZ());
            for (BlockPos repair : new BlockPos[] {
                    new BlockPos(bounds.minX() - 1, 20, bounds.maxZ()),
                    new BlockPos(bounds.maxX() + 1, 25, bounds.maxZ()),
                    new BlockPos(bounds.maxX(), 26, bounds.minZ() - 1),
                    new BlockPos(bounds.maxX(), 19, bounds.maxZ() + 1)
            }) {
                BetterBlockPos goal = bounds.approach(feet, repair);
                assertNotNull(goal);
                assertTrue(bounds.inside(goal.x, goal.y, goal.z));
                BetterBlockPos step = BuilderProcess.nextSnakeWaypoint(feet, goal);
                assertTrue(bounds.inside(step.x, step.y, step.z));
                assertEquals(feet.y, step.y);
                assertTrue(Math.abs(step.x - feet.x) + Math.abs(step.z - feet.z) <= 1);
            }
            assertNull(bounds.approach(new BetterBlockPos(bounds.minX() - 1, 22, bounds.minZ()), BlockPos.ZERO));
            assertNull(bounds.approach(new BetterBlockPos(bounds.minX(), 19, bounds.minZ()), BlockPos.ZERO));
        }
    }

    @Test
    public void sharedPolicyPreservesSnakeAndConstructionClassification() {
        var bounds = new ExcavationRepairPolicy.Bounds(10, 20, 10, 30, 40, 50, 20, 22, false);
        assertNull("the existing snake floor policy stays unchanged",
                ExcavationRepairPolicy.repair(bounds, 15, 19, 45, AIR, true, false));
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        assertEquals(ExcavationRepairPolicy.Kind.INTERNAL_SOURCE,
                ExcavationRepairPolicy.repair(bounds, 15, 18, 45, wet, false, false));
        assertNull(ExcavationRepairPolicy.repair(bounds, 15, 18, 45, wet, false, true));
        for (int x = 9; x <= 21; x++) for (int y = 19; y <= 23; y++) for (int z = 39; z <= 51; z++) {
            boolean expected = BuilderProcess.snakeShellCoordinate(x, y, z, 10, 20, 40, 50, 20, 22, 30);
            assertEquals(expected, ExcavationRepairPolicy.repair(bounds, x, y, z, AIR, true, false) != null);
        }
    }

    @Test
    public void ordinaryNavigationCannotMineAnOutsideAccessTunnelOrARepairedBoundary() {
        assertTrue(ExcavationRepairPolicy.navigationMayMine(true, AIR));
        assertFalse(ExcavationRepairPolicy.navigationMayMine(true, null));
        assertFalse(ExcavationRepairPolicy.navigationMayMine(true, STONE));
        assertTrue(ExcavationRepairPolicy.navigationMayMine(false, null));
        assertTrue(ExcavationRepairPolicy.navigationMayMine(false, STONE));
    }
}
