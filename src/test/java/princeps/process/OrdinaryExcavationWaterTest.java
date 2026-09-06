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
import java.util.function.Function;

import static org.junit.Assert.*;

public class OrdinaryExcavationWaterTest {
    private static BlockState AIR;
    private static BlockState WATER;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
    }

    private static ExcavationRepairPolicy.Bounds bounds(int width, int length) {
        return new ExcavationRepairPolicy.Bounds(10, 9 + width, 20, 25, 30, 29 + length, 20, 25, true);
    }

    @Test
    public void remoteWorkAcrossNarrowWaterGetsCardinalStepsUntilTheFirstSolidBodyCell() {
        for (int[] size : new int[][] {{1, 30}, {2, 30}, {30, 1}, {30, 2}}) {
            var bounds = bounds(size[0], size[1]);
            BetterBlockPos feet = new BetterBlockPos(bounds.minX(), 22, bounds.minZ());
            BlockPos work = new BlockPos(bounds.maxX(), 23, bounds.maxZ());
            Function<BlockPos, BlockState> world = pos -> {
                assertTrue("the body probe must not borrow an outside column",
                        bounds.inside(pos.getX(), pos.getY(), pos.getZ()));
                assertTrue("floor repair remains owned by the existing bridge licence",
                        pos.getY() == 22 || pos.getY() == 23);
                if (pos.equals(work)) return Blocks.STONE.defaultBlockState();
                return pos.getY() == 22 ? WATER.setValue(BlockStateProperties.LEVEL, 2) : AIR;
            };
            int distance = Math.abs(work.getX() - feet.x) + Math.abs(work.getZ() - feet.z);
            while (distance > 1) {
                BetterBlockPos next = ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, world);
                assertNotNull(next);
                assertEquals(1, Math.abs(next.x - feet.x) + Math.abs(next.z - feet.z));
                assertEquals(feet.y, next.y);
                assertTrue(bounds.inside(next.x, next.y, next.z));
                feet = next;
                assertEquals(--distance, Math.abs(work.getX() - feet.x) + Math.abs(work.getZ() - feet.z));
            }
            assertNull("the step licence never authorizes breaking the remote obstacle",
                    ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, world));
        }
    }

    @Test
    public void enteringAndLeavingWaterUseTheSameStepButDryTravelKeepsOrdinaryRouting() {
        var bounds = bounds(1, 7);
        BetterBlockPos feet = new BetterBlockPos(10, 22, 30);
        BetterBlockPos next = feet.south();
        BlockPos work = new BlockPos(10, 23, 36);
        for (BlockPos wet : new BlockPos[] {feet, feet.above(), next, next.above()}) {
            for (int level = 0; level <= 15; level++) {
                BlockState fluid = WATER.setValue(BlockStateProperties.LEVEL, level);
                assertEquals(next, ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work,
                        pos -> pos.equals(wet) ? fluid : AIR));
            }
        }
        assertNull(ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, pos -> AIR));
    }

    @Test
    public void waterPermissionCannotPassLavaWaterloggedGeometryOrAnUnclearedPlant() {
        var bounds = bounds(1, 7);
        BetterBlockPos feet = new BetterBlockPos(10, 22, 30);
        BlockPos work = new BlockPos(10, 23, 36);
        for (BlockState obstacle : new BlockState[] {
                Blocks.STONE.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, 8),
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.SEAGRASS.defaultBlockState(), Blocks.BUBBLE_COLUMN.defaultBlockState()
        }) {
            for (BlockPos blocked : new BlockPos[] {feet, feet.above(), feet.south(), feet.south().above()}) {
                assertNull(obstacle.toString(), ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work,
                        pos -> pos.equals(blocked) ? obstacle : WATER));
            }
        }
    }

    @Test
    public void wetStepCannotEscapeTheCurrentBandOrBorrowAnOutsideOrVerticalTarget() {
        var bounds = new ExcavationRepairPolicy.Bounds(10, 10, 10, 30, 30, 36, 20, 22, true);
        BetterBlockPos feet = new BetterBlockPos(10, 21, 30);
        BlockPos work = new BlockPos(10, 22, 36);
        for (BlockPos invalid : new BlockPos[] {
                work.west(), work.east(), work.south(), work.above(), new BlockPos(10, 19, 36), feet.above()
        }) {
            assertNull(ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, invalid, pos -> {
                fail("an invalid target must not issue a world/body probe");
                return WATER;
            }));
        }
        for (BetterBlockPos invalidFeet : new BetterBlockPos[] {
                feet.west(), feet.north(), new BetterBlockPos(10, 19, 30), new BetterBlockPos(10, 23, 30)
        }) {
            assertNull(ExcavationRepairPolicy.ordinaryWetStep(bounds, invalidFeet, work, pos -> WATER));
        }
        var wide = new ExcavationRepairPolicy.Bounds(10, 20, 20, 25, 30, 40, 20, 25, false);
        assertNull("snake and construction route selection is unchanged",
                ExcavationRepairPolicy.ordinaryWetStep(wide, feet, work, pos -> WATER));
    }

    @Test
    public void laterWetGeometryChangesRevokeTheNextStep() {
        var bounds = bounds(1, 7);
        BetterBlockPos feet = new BetterBlockPos(10, 22, 30);
        BlockPos work = new BlockPos(10, 23, 36);
        Map<Long, BlockState> changes = new HashMap<>();
        Function<BlockPos, BlockState> world = pos -> changes.getOrDefault(pos.asLong(), WATER);
        assertEquals(feet.south(), ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, world));
        changes.put(feet.south().above().asLong(), Blocks.GRAVEL.defaultBlockState());
        assertNull("a falling block changes the next decision back to ordinary clearing",
                ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, world));
        changes.clear();
        assertEquals(feet.south(), ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, world));
    }

    @Test
    public void lateToolOrMaskChangesVetoBothDirectAndNavigationBreaks() {
        assertTrue(ExcavationRepairPolicy.ordinaryBreakAllowed(true, true, false));
        assertFalse("a later slot override must not turn a single cut into 3x3",
                ExcavationRepairPolicy.ordinaryBreakAllowed(true, true, true));
        for (boolean shardHeld : new boolean[] {false, true}) {
            assertFalse("an outside wall or a now-inactive band has no AIR licence",
                    ExcavationRepairPolicy.ordinaryBreakAllowed(true, false, shardHeld));
            assertTrue("construction and deliberate wide work retain their existing executor",
                    ExcavationRepairPolicy.ordinaryBreakAllowed(false, false, shardHeld));
        }
        assertTrue(BuilderProcess.snakeMiningPostureReady(false, true, true, false));
        assertFalse(BuilderProcess.snakeMiningPostureReady(false, false, true, false));
        assertFalse(BuilderProcess.snakeMiningPostureReady(false, true, false, false));
    }
}
