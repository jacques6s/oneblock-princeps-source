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

    private static final double[] PEAKS = {5.0, 9.0, 20.0}; // superSmooth / standard / fast

    /** simulate a full arc; returns the per-tick velocities. */
    private static List<Double> arc(double err, double peak) {
        List<Double> vels = new ArrayList<>();
        double v = 0.0;
        for (int i = 0; i < 500 && err > 1e-3; i++) {
            v = LookBehavior.aimCurveNextVel(v, err, peak);
            vels.add(v);
            err -= Math.min(err, v);
        }
        assertTrue("arc must land on the target (err ~0) within 500 ticks", err <= 1e-3);
        return vels;
    }

    @Test
    public void ceilingIsNeverExceeded() {
        for (double peak : PEAKS) {
            for (double err : new double[]{2, 10, 45, 90, 179}) {
                for (double v : arc(err, peak)) {
                    assertTrue("mode peak " + peak + " must never be exceeded (err " + err + ")", v <= peak + 1e-9);
                }
            }
        }
    }

    @Test
    public void accelerationIsBoundedByAThirdOfPeak() {
        // THE anti-flick guarantee: per-tick velocity increase <= peak/3 (the old flat rate-limit kicked 0->peak
        // in one tick; the old exact aim snapped 0->err). Sim showed 3x/30x lower peak acceleration respectively.
        for (double peak : PEAKS) {
            List<Double> vels = arc(170, peak);
            double prev = 0.0;
            for (double v : vels) {
                assertTrue("accel must stay <= peak/3 + eps", v - prev <= peak / 3.0 + 1e-9);
                prev = v;
            }
        }
    }

    @Test
    public void profileIsABell_riseThenFall() {
        for (double peak : PEAKS) {
            List<Double> vels = arc(90, peak);
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
    public void smallReAimsNeverReachThePeak() {
        // a 10-deg correction must stay a gentle bump (the bell never spins up): max well under the standard peak
        List<Double> vels = arc(10, 9.0);
        double top = vels.stream().max(Double::compare).orElse(0.0);
        assertTrue("10-deg re-aim should top out under 5 deg/tick, was " + top, top < 5.0);
    }

    @Test
    public void tremorSizedCorrectionsStaySubDegree() {
        // while holding a block, the target only moves by the bounded (<0.5 deg) tremor: the correction step must
        // stay sub-degree so the crosshair cannot leave the block face between two ticks (mining never fails).
        for (double err : new double[]{0.05, 0.2, 0.5}) {
            double v = LookBehavior.aimCurveNextVel(0.0, err, 9.0);
            assertTrue("tremor correction must be sub-degree", Math.min(err, v) <= Math.max(err, 0.9) + 1e-9);
            assertTrue("first step from rest is bounded by the ease-in accel", v <= 3.0 + 1e-9);
        }
    }

    @Test
    public void standardModeMatchesTheNinDegreeCap() {
        // the standard mode plateau must be exactly the 9-deg/tick cap the rest of the look system is tuned around
        List<Double> vels = arc(120, 9.0);
        assertEquals(9.0, vels.stream().max(Double::compare).orElse(0.0), 1e-9);
    }
}
