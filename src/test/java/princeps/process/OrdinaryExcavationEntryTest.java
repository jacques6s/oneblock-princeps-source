package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;

import static org.junit.Assert.*;

public class OrdinaryExcavationEntryTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ExcavationRepairPolicy.Bounds bounds(int width, int height, int layerHeight,
                                                        int layer, boolean topDown) {
        BuilderProcess.LayerBand band = BuilderProcess.layerBand(height, layerHeight, layer, topDown);
        return new ExcavationRepairPolicy.Bounds(10, 9 + width, 20, 19 + height,
                30, 36, 20 + band.lo(), 20 + band.hi(), true);
    }

    @Test
    public void emptyStartLayerCannotSealTheOccupiedRoofBeforeMiningIsOffered() {
        for (int width : new int[] {1, 2, 7}) {
            for (int height : new int[] {1, 2, 6}) {
                for (int layerHeight : new int[] {1, 3}) {
                    for (boolean topDown : new boolean[] {true, false}) {
                        var initial = bounds(width, height, layerHeight, 0, topDown);
                        assertFalse(initial.hasActiveBand());
                        assertNull("the empty layer has no roof-repair action",
                                ExcavationRepairPolicy.repair(initial, 10, 20 + height, 31,
                                        Blocks.AIR.defaultBlockState(), true, true));
                        var census = ExcavationRepairPolicy.inspect(initial, pos -> {
                            fail("the start-layer sentinel must not inspect or repair the world");
                            return Blocks.STONE.defaultBlockState();
                        }, pos -> { fail("no placement query belongs to an empty band"); return false; });
                        assertTrue(census.clean());
                        assertEquals(0, census.solid());
                    }
                }
            }
        }
    }

    @Test
    public void firstRealBandRestoresMiningAndStillOwesTheOriginalRoofHole() {
        for (int width : new int[] {1, 2}) {
            var first = bounds(width, 6, 3, 1, true);
            BetterBlockPos entry = new BetterBlockPos(10, 26, 31);
            BlockPos cut = entry.below();
            BlockState stone = Blocks.STONE.defaultBlockState();
            assertTrue(first.hasActiveBand());
            assertTrue(first.inside(cut.getX(), cut.getY(), cut.getZ()));
            assertTrue(cut.getY() >= first.bandFloor() && cut.getY() <= first.bandTop());
            assertFalse("the existing downward break goal must descend from the access hole",
                    new BuilderProcess.GoalBreak(cut).isInGoal(entry.x, entry.y, entry.z));
            assertTrue(new BuilderProcess.GoalBreak(cut).isInGoal(cut.getX(), cut.getY(), cut.getZ()));
            assertTrue(ExcavationRepairPolicy.ordinaryBreakAllowed(true, true, false));
            assertFalse("the entry remains a single cut", ExcavationRepairPolicy.ordinaryBreakAllowed(true, true, true));
            var census = ExcavationRepairPolicy.inspect(first,
                    pos -> pos.asLong() == entry.asLong() ? Blocks.AIR.defaultBlockState() : stone,
                    pos -> pos.asLong() == entry.asLong());
            assertEquals(3 * width * 7, census.solid());
            assertFalse("the opening is deferred by startup, never forgiven at completion", census.clean());
            assertEquals(1, census.repairs().size());
            assertEquals(entry.asLong(), census.repairs().getFirst().pos().asLong());
            assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP, census.repairs().getFirst().kind());
            assertFalse(ExcavationRepairPolicy.navigationMayMine(true, null));
        }
    }

    @Test
    public void completedAndPastEndBandsDoNotInventAnotherRoofButLiveBandStillCountsSolids() {
        var last = bounds(1, 6, 3, 2, true);
        assertTrue(last.hasActiveBand());
        var census = ExcavationRepairPolicy.inspect(last, pos -> Blocks.STONE.defaultBlockState(), pos -> false);
        assertEquals(21, census.solid());
        assertFalse(census.clean());
        var after = bounds(1, 6, 3, 3, true);
        assertFalse(after.hasActiveBand());
        assertNull(ExcavationRepairPolicy.repair(after, 10, 26, 31,
                Blocks.AIR.defaultBlockState(), true, true));
    }
}
