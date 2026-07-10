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

package princeps.flownav;

import org.junit.Test;
import princeps.flownav.math.Vec3;
import princeps.flownav.traj.FlowLine;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure geometry tests for {@link FlowLine} — the arc-rounded line both the path renderer draws and
 * the flat-segment steering ({@code MovementHelper.moveAlongPath}) follows.
 */
public class FlowLineTest {

    private static Vec3 v(double x, double z) {
        return new Vec3(x, 64, z);
    }

    @Test
    public void endpointsAreExact() {
        List<Vec3> line = FlowLine.smoothPoints(Arrays.asList(v(0.5, 0.5), v(5.5, 0.5), v(5.5, 6.5)), 1.0);
        assertEquals(0.5, line.get(0).x(), 1e-9);
        assertEquals(0.5, line.get(0).z(), 1e-9);
        assertEquals(5.5, line.get(line.size() - 1).x(), 1e-9);
        assertEquals(6.5, line.get(line.size() - 1).z(), 1e-9);
    }

    @Test
    public void straightRunStaysOnTheLine() {
        List<Vec3> line = FlowLine.smoothPoints(Arrays.asList(v(0.5, 0.5), v(10.5, 0.5)), 1.0);
        for (Vec3 p : line) {
            assertEquals("a straight run must not be bent", 0.5, p.z(), 1e-9);
        }
    }

    @Test
    public void cornerIsRoundedInsideTheTurn() {
        // 90-degree corner at (5.5, 0.5): the fillet must replace the sharp apex — no output point
        // may sit exactly on the corner, and all points stay within the corner's block column bounds.
        List<Vec3> line = FlowLine.smoothPoints(Arrays.asList(v(0.5, 0.5), v(5.5, 0.5), v(5.5, 5.5)), 1.0);
        boolean sharpApex = false;
        for (Vec3 p : line) {
            if (Math.abs(p.x() - 5.5) < 1e-6 && Math.abs(p.z() - 0.5) < 1e-6) {
                sharpApex = true;
            }
            // The cut may pull at most ~0.45 blocks inside the corner (MAX_SEGMENT_FRACTION):
            // x never exceeds the corner column, z never undercuts the first leg's line by more than the cut.
            assertTrue("x beyond the corner: " + p.x(), p.x() <= 5.5 + 1e-9);
            assertTrue("z before the first leg: " + p.z(), p.z() >= 0.5 - 1e-9);
        }
        assertTrue("the sharp corner apex must be rounded away", !sharpApex);
    }

    @Test
    public void pointsAreDenselySpaced() {
        List<Vec3> line = FlowLine.smoothPoints(Arrays.asList(v(0.5, 0.5), v(8.5, 0.5), v(8.5, 8.5)), 1.0);
        for (int i = 1; i < line.size(); i++) {
            double gap = line.get(i).horizontalDistanceTo(line.get(i - 1));
            assertTrue("gap too large for stable projection: " + gap, gap <= 0.25 + 1e-6);
        }
    }

    @Test
    public void degenerateInputsDoNotThrow() {
        assertTrue(FlowLine.smoothPoints(Arrays.asList(v(0.5, 0.5), v(0.5, 0.5)), 1.0).size() >= 2);
        assertTrue(FlowLine.smoothPoints(Arrays.asList(v(0.5, 0.5), v(1.5, 0.5)), 1.0).size() >= 2);
    }
}
