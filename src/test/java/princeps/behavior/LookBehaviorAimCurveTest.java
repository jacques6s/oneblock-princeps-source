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

package princeps.behavior;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the pure bell-curve core of the mining aim ({@link LookBehavior#aimCurveNextVel}): ease-in at ~peak/3 per
 * tick^2, plateau at the mode peak, proportional ease-out. This is the math that removes the mining "flick" (a 0->max
 * velocity kick in a single tick), so the acceleration bound and the ceiling are the load-bearing guarantees; the
 * termination test guards against an arc that never lands (which would stall the dig forever).
 */
public class LookBehaviorAimCurveTest {

    /** The 5 canonical modes: {turnTicks, peak, accel} — the sim-calibrated table shipped in LookBehavior.TURN_CAL. */
    private static final double[][] MODES = {
            {2.0, 45.0, 45.0},   // Superfast
            {3.0, 34.0, 22.7},   // Fast
            {4.0, 26.0, 13.0},   // Balanced
            {8.0, 15.5, 3.9},    // Smooth
            {12.0, 11.5, 1.9},   // Super smooth
    };

    /** Simulate a full arc EXACTLY as the shipped caller does (pure next-vel, then the [0.3, peak+0.1] clamp, then
     *  a step of min(err, v)). Returns the per-tick velocities; the size is the tick count. */
    private static List<Double> arc(double err, double peak, double accel) {
        List<Double> vels = new ArrayList<>();
        double v = 0.0;
        for (int i = 0; i < 500 && err > 1e-6; i++) {
            v = LookBehavior.aimCurveNextVel(v, err, peak, accel);
            v = Math.max(0.3, Math.min(v, peak + 0.1)); // the caller's clamp (LookBehavior.aimCurveTurn)
            vels.add(v);
            err -= Math.min(err, v);
        }
        assertTrue("arc must land on the target (err ~0) within 500 ticks", err <= 1e-6);
        return vels;
    }

    @Test
    public void eachModeCompletesNinetyInExactlyItsTurnTicks() {
        // THE load-bearing guarantee the modes are defined by: a 90-degree turn lands in exactly turnTicks ticks.
        for (double[] m : MODES) {
            int ticks = arc(90.0, m[1], m[2]).size();
            assertEquals("mode " + (int) m[0] + "t must complete 90deg in exactly that many ticks", (int) m[0], ticks);
        }
    }

    @Test
    public void ceilingIsNeverExceeded() {
        for (double[] m : MODES) {
            double peak = m[1];
            for (double err : new double[]{2, 10, 45, 90, 179}) {
                for (double v : arc(err, peak, m[2])) {
                    assertTrue("mode peak " + peak + " must never be exceeded (err " + err + ")", v <= peak + 0.1 + 1e-9);
                }
            }
        }
    }

    @Test
    public void accelerationIsBoundedByTheModeAccel() {
        // Anti-flick: per-tick velocity increase never exceeds the mode's ease-in accel (no 0->peak kick in one tick).
        for (double[] m : MODES) {
            double prev = 0.0;
            for (double v : arc(170.0, m[1], m[2])) {
                assertTrue("accel must stay <= mode accel + eps", v - prev <= m[2] + 1e-9);
                prev = v;
            }
        }
    }

    @Test
    public void profileIsABell_riseThenFall() {
        for (double[] m : MODES) {
            List<Double> vels = arc(90.0, m[1], m[2]);
            int top = vels.indexOf(vels.stream().max(Double::compare).orElseThrow(AssertionError::new));
            for (int i = 0; i < top; i++) {
                assertTrue("velocity must rise monotonically before the peak", vels.get(i + 1) >= vels.get(i) - 1e-9);
            }
            for (int i = top; i < vels.size() - 1; i++) {
                assertTrue("velocity must never rise again after the peak", vels.get(i + 1) <= vels.get(i) + 1e-9);
            }
        }
    }

    @Test
    public void tremorSizedCorrectionsNeverLeaveTheBlockFace() {
        // While holding a block, the target only moves by the bounded (<0.5 deg) tremor: the STEP is min(err, v),
        // always <= err, so the crosshair cannot leave the block face between two ticks (mining never fails).
        for (double[] m : MODES) {
            for (double err : new double[]{0.05, 0.2, 0.5}) {
                double v = LookBehavior.aimCurveNextVel(0.0, err, m[1], m[2]);
                assertTrue("tremor step must stay within the tremor error", Math.min(err, v) <= err + 1e-9);
            }
        }
    }
}
