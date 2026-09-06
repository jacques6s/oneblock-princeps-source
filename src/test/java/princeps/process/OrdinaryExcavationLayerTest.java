/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** Band permissions and actual standing-body roof geometry; not a simulated live pathfinder run. */
public class OrdinaryExcavationLayerTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void coveredOrdinaryEntryCanClearFeetAndHeadWithoutMiningTheOutsideRoof() {
        int height = 6;
        var oldBand = BuilderProcess.layerBand(height, 1, 1, true);
        assertEquals(5, oldBand.lo());
        assertEquals(5, oldBand.hi());
        assertTrue("one layer below the fixed roof cannot hold the player's body",
                ShallowExcavationPolicy.roofBlocksStanding(Blocks.STONE.defaultBlockState(),
                        EmptyBlockGetter.INSTANCE, new BlockPos(0, height, 0)));
        assertFalse("the old layer refuses the in-volume block needed to descend to standing height",
                mayMine(oldBand, height, 4));

        int stride = BuilderProcess.effectiveLayerHeight(1, true, true, 1, height);
        var band = BuilderProcess.layerBand(height, stride, 1, true);
        assertEquals(4, band.lo());
        assertEquals(5, band.hi());
        assertTrue(mayMine(band, height, 4));
        assertTrue(mayMine(band, height, 5));
        assertFalse("the outside roof remains outside navigation's AIR permission", mayMine(band, height, 6));
        assertFalse("the lower band remains protected", mayMine(band, height, 3));
        assertFalse("after those permitted cuts, the head cell is genuinely open",
                ShallowExcavationPolicy.roofBlocksStanding(Blocks.AIR.defaultBlockState(),
                        EmptyBlockGetter.INSTANCE, new BlockPos(0, band.lo() + 1, 0)));
    }

    @Test
    public void completeOneHighOpenAndCoveredSelectionsKeepTheirExistingSurfaceRules() {
        assertEquals(1, BuilderProcess.effectiveLayerHeight(1, true, true, 1, 1));
        var band = BuilderProcess.layerBand(1, 1, 1, true);
        assertTrue(mayMine(band, 1, 0));
        assertFalse(mayMine(band, 1, 1));
        assertFalse(mayMine(band, 1, -1));
        var bounds = new ExcavationRepairPolicy.Bounds(0, 6, 0, 0, 0, 6, 0, 0, true);
        assertNull("an existing open roof is not sealed over a one-high complete selection",
                ExcavationRepairPolicy.repair(bounds, 3, 1, 3, Blocks.AIR.defaultBlockState(), true, true));
        assertTrue(ShallowExcavationPolicy.surfaceMode(true, 1));
        assertFalse(ShallowExcavationPolicy.roofBlocksStanding(Blocks.AIR.defaultBlockState(),
                EmptyBlockGetter.INSTANCE, new BlockPos(3, 1, 3)));
        assertTrue(ShallowExcavationPolicy.roofBlocksStanding(Blocks.STONE.defaultBlockState(),
                EmptyBlockGetter.INSTANCE, new BlockPos(3, 1, 3)));
        assertTrue("a failed route under a fixed outside roof is still an explicit blocked result",
                ShallowExcavationPolicy.stopAfterFailedRoute(true, true, false, true, false));
    }

    @Test
    public void everyOrdinaryBandPartitionsTheVolumeOnceAndUsesTheSameCompletionStride() {
        for (int height = 1; height <= 33; height++) {
            for (int requested : new int[] {-1, 0, 1, 2, 3, 4, 8}) {
                int stride = BuilderProcess.effectiveLayerHeight(requested, true, true, 1, height);
                int layers = BuilderProcess.layerCount(height, stride);
                int[] visits = new int[height];
                assertTrue(BuilderProcess.layerBand(height, stride, 0, true).isEmpty());
                for (int layer = 1; layer <= layers; layer++) {
                    var band = BuilderProcess.layerBand(height, stride, layer, true);
                    assertFalse(band.isEmpty());
                    assertTrue(band.lo() >= 0);
                    assertTrue(band.hi() < height);
                    for (int y = band.lo(); y <= band.hi(); y++) visits[y]++;
                    assertEquals("the production advance condition agrees with the same band stride",
                            layer < layers, layer * stride < height);
                }
                for (int visitsAtY : visits) assertEquals(1, visitsAtY);
                assertTrue(BuilderProcess.layerBand(height, stride, layers + 1, true).isEmpty());
            }
        }
    }

    @Test
    public void normalFollowingBandsAndTheLastSingleRowKeepPreviouslyClearedHeadroom() {
        for (int height : new int[] {2, 3, 5, 6, 9}) {
            int stride = BuilderProcess.effectiveLayerHeight(1, true, true, 1, height);
            boolean[] cleared = new boolean[height];
            int layers = BuilderProcess.layerCount(height, stride);
            for (int layer = 1; layer <= layers; layer++) {
                var band = BuilderProcess.layerBand(height, stride, layer, true);
                for (int y = band.lo(); y <= band.hi(); y++) cleared[y] = true;
                int feet = band.lo();
                assertTrue("the body stays wholly inside this multi-high selection", feet + 1 < height);
                assertTrue(cleared[feet]);
                assertTrue("a final one-row band borrows already-cleared air, never an outside roof cut", cleared[feet + 1]);
                if (band.lo() == band.hi()) {
                    assertEquals(layers, layer);
                    assertFalse("completed headroom is not added back into the active mining mask",
                            mayMine(band, height, feet + 1));
                }
            }
            assertFalse("the full selection did not turn into a one-high surface on its last band",
                    ShallowExcavationPolicy.surfaceMode(true, height));
        }
    }

    @Test
    public void constructionShardBottomUpAndExistingLargerBandsKeepTheirConfiguredHeights() {
        for (int height : new int[] {1, 2, 3, 6, 30}) {
            for (int requested : new int[] {1, 2, 3, 7}) {
                assertEquals(requested, BuilderProcess.effectiveLayerHeight(requested, false, true, 1, height));
                assertEquals(requested, BuilderProcess.effectiveLayerHeight(requested, false, false, 1, height));
                assertEquals(requested, BuilderProcess.effectiveLayerHeight(requested, true, false, 1, height));
                assertEquals(requested, BuilderProcess.effectiveLayerHeight(requested, true, true, 3, height));
                if (requested >= 2) {
                    assertEquals(requested, BuilderProcess.effectiveLayerHeight(requested, true, true, 1, height));
                }
            }
        }
    }

    @Test
    public void narrowFallbackAndExplicitOrdinaryModeUseTheSameBodyRuleWithoutWideningTheTool() {
        for (int requestedArea : new int[] {1, 3}) {
            int area = BuilderProcess.effectiveAreaBreakSize(requestedArea, true, 1, 6, 7);
            assertEquals(1, area);
            assertEquals(2, BuilderProcess.effectiveLayerHeight(1, true, true, area, 6));
            assertEquals("the existing three-high narrow fallback stays three-high", 3,
                    BuilderProcess.effectiveLayerHeight(3, true, true, area, 6));
        }
    }

    private static boolean mayMine(BuilderProcess.LayerBand band, int height, int y) {
        BlockState desired = y >= 0 && y < height && y >= band.lo() && y <= band.hi()
                ? Blocks.AIR.defaultBlockState() : null;
        return ExcavationRepairPolicy.navigationMayMine(true, desired);
    }
}
