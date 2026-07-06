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

package princeps.pathing.movement.movements;

import org.junit.Test;
import princeps.api.utils.BetterBlockPos;

import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Pins the pure geometry of {@link SmoothTraverse#sweptCells}, which is BOTH the merged movement's valid-position set
 * (the executor gate the bot's feet must always be inside) AND the collision-safety predicate's cell list. It is
 * static and MC-free, so it is unit-testable even though the movement's executor integration is only live-testable.
 * If this set ever fails to cover the body's actual path, the executor would reject the bot mid-chord (strand) OR the
 * safety check would miss a cell — so this coverage is load-bearing.
 */
public class SmoothTraverseTest {

    private static final double HALF = 0.35;

    @Test
    public void alwaysContainsEndpoints() {
        BetterBlockPos a = new BetterBlockPos(3, 64, -2);
        BetterBlockPos b = new BetterBlockPos(11, 64, 5);
        Set<BetterBlockPos> cells = SmoothTraverse.sweptCells(a, b, HALF);
        assertTrue("src must be a valid position", cells.contains(a));
        assertTrue("dest must be a valid position", cells.contains(b));
    }

    @Test
    public void allCellsAreAtTheFlatY() {
        BetterBlockPos a = new BetterBlockPos(0, 72, 0);
        BetterBlockPos b = new BetterBlockPos(9, 72, 4);
        for (BetterBlockPos c : SmoothTraverse.sweptCells(a, b, HALF)) {
            assertEquals("swept cells stay on the flat run's Y", 72, c.y);
        }
    }

    @Test
    public void straightChordCoversEveryCellBetween() {
        // a straight east chord: the feet walk cells x=0..6 at z=0 — every one must be in the set (no gap the
        // executor's containment gate could reject the bot in)
        BetterBlockPos a = new BetterBlockPos(0, 64, 0);
        BetterBlockPos b = new BetterBlockPos(6, 64, 0);
        Set<BetterBlockPos> cells = SmoothTraverse.sweptCells(a, b, HALF);
        for (int x = 0; x <= 6; x++) {
            assertTrue("straight chord must cover cell x=" + x, cells.contains(new BetterBlockPos(x, 64, 0)));
        }
    }

    @Test
    public void diagonalChordIsContiguous_noGapAlongTheBody() {
        // sampling the exact segment at 0.05 steps, every body-corner cell must be in the swept set — proving the
        // 0.1-step supercover leaves no hole a continuously-moving 0.6-wide body could fall through.
        BetterBlockPos a = new BetterBlockPos(0, 64, 0);
        BetterBlockPos b = new BetterBlockPos(13, 64, 7);
        Set<BetterBlockPos> cells = SmoothTraverse.sweptCells(a, b, HALF);
        double ax = a.x + 0.5, az = a.z + 0.5, bx = b.x + 0.5, bz = b.z + 0.5;
        int steps = (int) (Math.hypot(bx - ax, bz - az) / 0.05);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = ax + (bx - ax) * t, z = az + (bz - az) * t;
            for (double ox = -HALF; ox <= HALF; ox += 2 * HALF) {
                for (double oz = -HALF; oz <= HALF; oz += 2 * HALF) {
                    int cx = (int) Math.floor(x + ox), cz = (int) Math.floor(z + oz);
                    // note: build the message from ints, not BetterBlockPos.toString() — its toString touches an MC
                    // class whose static init is absent in the unit env (would mask a real assertion with an init error)
                    assertTrue("no gap along the body sweep at t=" + t + " cell=(" + cx + "," + cz + ")",
                            cells.contains(new BetterBlockPos(cx, 64, cz)));
                }
            }
        }
    }

    @Test
    public void degenerateSameCellChordIsJustThatCell() {
        BetterBlockPos a = new BetterBlockPos(4, 64, 4);
        Set<BetterBlockPos> cells = SmoothTraverse.sweptCells(a, a, HALF);
        assertTrue(cells.contains(a));
        assertFalse("a zero-length chord must not sweep foreign cells", cells.contains(new BetterBlockPos(6, 64, 6)));
    }

    @Test
    public void safetyMarginCatchesTheKnifeEdgeGraze() {
        // A concrete grazing chord (0,64,0)->(8,64,3) passes right past cell (3,64,0). Verified in the navbench margin
        // probe against the EXACT swept-cell test: at half=0.30 (the exact 0.6-wide body, ZERO margin) the body clears
        // (3,0) by a knife's edge so it is NOT swept; at the deployed half=0.35 the body overlaps it, so it IS swept
        // and chordSafe() will test it and (it being solid) reject the body-clipping merge. Pins WHY the deployed
        // margin is 0.35 not 0.30: the extra 0.05 rejects exactly the grazes a jittering real body could clip. If
        // anyone loosens the margin toward 0.30, this test fails and flags the safety loss.
        BetterBlockPos src = new BetterBlockPos(0, 64, 0);
        BetterBlockPos dest = new BetterBlockPos(8, 64, 3);
        BetterBlockPos graze = new BetterBlockPos(3, 64, 0);
        assertFalse("half=0.30 (zero margin) must NOT sweep the graze cell — the body clears it by a knife's edge",
                SmoothTraverse.sweptCells(src, dest, 0.30).contains(graze));
        assertTrue("deployed half=0.35 MUST sweep the graze cell so chordSafe rejects a body-clipping chord",
                SmoothTraverse.sweptCells(src, dest, 0.35).contains(graze));
    }

    @Test
    public void exactSweepHasNoCornerGrazeGap() {
        // Regression for the point-sampling collision gap: the OLD corner-sampled sweep MISSED cells a body grazes at
        // a corner between samples (the random-terrain stress bench found 30/600 such unsafe merges). The exact test
        // must include EVERY cell whose expanded rect the chord truly crosses. Here the chord (0,64,0)->(9,64,4)
        // grazes cell (4,64,3) — the exact sweep contains it, whereas a 0.1 point-sampled sweep dropped it (verified
        // in the navbench exact-vs-sampled diff).
        Set<BetterBlockPos> cells = SmoothTraverse.sweptCells(
                new BetterBlockPos(0, 64, 0), new BetterBlockPos(9, 64, 4), 0.35);
        assertTrue("exact sweep must not leave a corner-graze gap", cells.contains(new BetterBlockPos(4, 64, 3)));
    }
}
