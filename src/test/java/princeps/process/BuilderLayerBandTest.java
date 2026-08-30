/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure arithmetic for the layer band of the v0.4 build algorithm, for both build directions.
 *
 * <p>There is no Minecraft in this test on purpose. The band is a closed interval of LOCAL schematic y, and every
 * bug this test exists to catch is an off-by-one in that interval -- not something a world, a schematic or a
 * settings object contributes to.
 *
 * <p><b>Why this test exists.</b> After the v0.4 rework the same band is derived in four independent places: the
 * schematic mask in {@code BuilderProcess.onTick}, {@code countScaffoldInLayer}, the coming per-layer verification,
 * and the client-side progress readout. {@code countScaffoldInLayer} computes the bottom-up band unconditionally,
 * so with {@code layerOrder=true} (top-down) it already names the wrong rows. If two of the four derivations drift
 * by one, the layer verification checks a band that was never built and the global abort fires on correct work --
 * under the one message that looks least like an arithmetic mistake. Nailing the interval down here, as arithmetic,
 * is what keeps the four in step.
 *
 * <p><b>{@link #layerBand(int, int, int, boolean)} is the single admissible definition of the band, and every
 * derivation in {@code BuilderProcess} must mirror it.</b> The bands are EXCLUSIVE: layer E covers only its own
 * rows, never the rows of the layers already finished. (The mask in {@code onTick} is deliberately CUMULATIVE --
 * it exposes everything built so far so already-placed blocks stay "correct" -- but its moving edge, the one row
 * the layer is allowed to grow into, is exactly this band's edge.) In local coordinates, with
 * {@code h = max(1, layerHeight)} and layers numbered from 1:
 *
 * <ul>
 *   <li>bottom-up (layerOrder=false): {@code [ (E-1)*h , E*h - 1 ]}</li>
 *   <li>top-down  (layerOrder=true):  {@code [ height - E*h , height - 1 - (E-1)*h ]}</li>
 * </ul>
 *
 * <p>Both are then clamped to {@code [0, height-1]}, which is what absorbs a height that is not a whole multiple of
 * the layer height. The short band lands on the LAST layer in both directions -- at the top for bottom-up, at the
 * bottom for top-down -- never on the first.
 */
public class BuilderLayerBandTest {

    /** Inclusive local-y interval a layer covers. {@code lo > hi} would mean an empty band, which must never occur. */
    private record Band(int lo, int hi) {
        int size() {
            return hi - lo + 1;
        }
    }

    /**
     * THE definition of the layer band. Every band derivation in {@code BuilderProcess} -- the mask edge in
     * {@code onTick}, {@code countScaffoldInLayer}, the layer verification, and whatever the client reports --
     * must agree with this method for both values of {@code layerOrder}. Do not re-derive it anywhere; mirror it.
     *
     * @param height      schematic height in blocks, {@code schematic.heightY()}
     * @param layerHeight rows per layer, {@code settings().layerHeight}; values {@code <= 0} are clamped to 1
     * @param layer       1-based layer number, 1 .. {@link #layerCount(int, int)}
     * @param topDown     {@code settings().layerOrder} -- true builds from the top row downwards
     * @return the inclusive local-y interval this layer owns, clamped to {@code [0, height-1]}
     */
    private static Band layerBand(int height, int layerHeight, int layer, boolean topDown) {
        int h = Math.max(1, layerHeight);
        int lo;
        int hi;
        if (topDown) {
            lo = height - layer * h;
            hi = height - 1 - (layer - 1) * h;
        } else {
            lo = (layer - 1) * h;
            hi = layer * h - 1;
        }
        return new Band(Math.max(0, lo), Math.min(height - 1, hi));
    }

    /** Number of layers a schematic of this height is cut into. Same clamp on {@code layerHeight} as the band. */
    private static int layerCount(int height, int layerHeight) {
        int h = Math.max(1, layerHeight);
        return (height + h - 1) / h;
    }

    /** Height / layerHeight pairs: even splits, ragged splits, layerHeight of 1, and a layer taller than the build. */
    private static final int[][] SHAPES = {
            {1, 1}, {5, 1}, {8, 2}, {16, 4}, {12, 3},
            {7, 3}, {10, 3}, {9, 2}, {17, 5}, {3, 5}, {1, 8},
    };

    @Test
    public void firstLayerIsTheBottomRowBuildingUpwards() {
        for (int[] shape : SHAPES) {
            int height = shape[0];
            int h = shape[1];
            Band first = layerBand(height, h, 1, false);
            assertEquals("bottom-up layer 1 must start at the lowest row, height=" + height + " h=" + h,
                    0, first.lo());
            assertEquals("bottom-up layer 1 must end one row below the second layer, height=" + height + " h=" + h,
                    Math.min(height - 1, h - 1), first.hi());
        }
    }

    @Test
    public void firstLayerIsTheTopRowBuildingDownwards() {
        for (int[] shape : SHAPES) {
            int height = shape[0];
            int h = shape[1];
            Band first = layerBand(height, h, 1, true);
            assertEquals("top-down layer 1 must end at the highest row, height=" + height + " h=" + h,
                    height - 1, first.hi());
            assertEquals("top-down layer 1 must start one row above the second layer, height=" + height + " h=" + h,
                    Math.max(0, height - h), first.lo());
        }
    }

    @Test
    public void lastLayerReachesTheOppositeEnd() {
        for (int[] shape : SHAPES) {
            int height = shape[0];
            int h = shape[1];
            int last = layerCount(height, h);

            Band up = layerBand(height, h, last, false);
            assertEquals("bottom-up last layer must end at the highest row, height=" + height + " h=" + h,
                    height - 1, up.hi());

            Band down = layerBand(height, h, last, true);
            assertEquals("top-down last layer must start at the lowest row, height=" + height + " h=" + h,
                    0, down.lo());
        }
    }

    @Test
    public void consecutiveBandsAreDisjointAndTouch() {
        for (int[] shape : SHAPES) {
            int height = shape[0];
            int h = shape[1];
            int count = layerCount(height, h);
            for (int layer = 1; layer < count; layer++) {
                Band up = layerBand(height, h, layer, false);
                Band upNext = layerBand(height, h, layer + 1, false);
                assertEquals("bottom-up bands must abut without gap or overlap between layer " + layer + " and "
                                + (layer + 1) + ", height=" + height + " h=" + h,
                        up.hi() + 1, upNext.lo());

                Band down = layerBand(height, h, layer, true);
                Band downNext = layerBand(height, h, layer + 1, true);
                assertEquals("top-down bands must abut without gap or overlap between layer " + layer + " and "
                                + (layer + 1) + ", height=" + height + " h=" + h,
                        down.lo() - 1, downNext.hi());
            }
        }
    }

    @Test
    public void everyBandIsNonEmptyAndInsideTheSchematic() {
        for (int[] shape : SHAPES) {
            int height = shape[0];
            int h = shape[1];
            int count = layerCount(height, h);
            for (boolean topDown : new boolean[]{false, true}) {
                for (int layer = 1; layer <= count; layer++) {
                    Band band = layerBand(height, h, layer, topDown);
                    String where = "height=" + height + " h=" + h + " layer=" + layer + " topDown=" + topDown;
                    assertTrue("band must not be empty, " + where, band.lo() <= band.hi());
                    assertTrue("band must not reach below the schematic, " + where, band.lo() >= 0);
                    assertTrue("band must not reach above the schematic, " + where, band.hi() <= height - 1);
                    assertTrue("band must not be taller than the layer height, " + where, band.size() <= h);
                }
            }
        }
    }

    @Test
    public void allBandsTogetherCoverEveryRowExactlyOnce() {
        for (int[] shape : SHAPES) {
            int height = shape[0];
            int h = shape[1];
            int count = layerCount(height, h);
            for (boolean topDown : new boolean[]{false, true}) {
                int[] covered = new int[height];
                for (int layer = 1; layer <= count; layer++) {
                    Band band = layerBand(height, h, layer, topDown);
                    for (int y = band.lo(); y <= band.hi(); y++) {
                        covered[y]++;
                    }
                }
                for (int y = 0; y < height; y++) {
                    assertEquals("row " + y + " must be owned by exactly one layer, height=" + height
                                    + " h=" + h + " topDown=" + topDown,
                            1, covered[y]);
                }
            }
        }
    }

    @Test
    public void raggedHeightPutsTheShortBandOnTheLastLayerInBothDirections() {
        // height 10, layerHeight 3 -> layers of 3, 3, 3, 1. The remainder must never land on layer 1.
        assertEquals(4, layerCount(10, 3));

        assertBand(0, 2, layerBand(10, 3, 1, false));
        assertBand(3, 5, layerBand(10, 3, 2, false));
        assertBand(6, 8, layerBand(10, 3, 3, false));
        assertBand(9, 9, layerBand(10, 3, 4, false));

        assertBand(7, 9, layerBand(10, 3, 1, true));
        assertBand(4, 6, layerBand(10, 3, 2, true));
        assertBand(1, 3, layerBand(10, 3, 3, true));
        assertBand(0, 0, layerBand(10, 3, 4, true));
    }

    @Test
    public void layerHeightOfOneGivesOneRowPerLayer() {
        for (int layer = 1; layer <= 5; layer++) {
            assertBand(layer - 1, layer - 1, layerBand(5, 1, layer, false));
            assertBand(5 - layer, 5 - layer, layerBand(5, 1, layer, true));
        }
    }

    @Test
    public void nonPositiveLayerHeightIsClampedToOne() {
        for (int layerHeight : new int[]{0, -1, -7, Integer.MIN_VALUE}) {
            assertEquals("layerCount must clamp layerHeight " + layerHeight + " to 1",
                    layerCount(13, 1), layerCount(13, layerHeight));
            for (boolean topDown : new boolean[]{false, true}) {
                for (int layer = 1; layer <= 13; layer++) {
                    Band clamped = layerBand(13, layerHeight, layer, topDown);
                    Band expected = layerBand(13, 1, layer, topDown);
                    assertArrayEquals("layerHeight " + layerHeight + " must behave as 1, layer=" + layer
                                    + " topDown=" + topDown,
                            new int[]{expected.lo(), expected.hi()},
                            new int[]{clamped.lo(), clamped.hi()});
                }
            }
        }
    }

    @Test
    public void theTwoDirectionsAreMirrorImagesOfEachOther() {
        for (int[] shape : SHAPES) {
            int height = shape[0];
            int h = shape[1];
            int count = layerCount(height, h);
            for (int layer = 1; layer <= count; layer++) {
                Band up = layerBand(height, h, layer, false);
                Band down = layerBand(height, h, layer, true);
                // Mirroring local y around the middle of the schematic must turn one direction into the other.
                assertBand(height - 1 - up.hi(), height - 1 - up.lo(), down);
            }
        }
    }

    private static void assertBand(int lo, int hi, Band actual) {
        assertArrayEquals("expected band [" + lo + "," + hi + "] but was [" + actual.lo() + "," + actual.hi() + "]",
                new int[]{lo, hi}, new int[]{actual.lo(), actual.hi()});
    }
}
