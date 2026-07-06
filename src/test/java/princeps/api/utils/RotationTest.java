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

package princeps.api.utils;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

/**
 * Regression coverage for the rotation-closeness family the humanized-look gates depend on.
 *
 * <p>The humanized look adds a bounded per-tick micro-tremor (up to ~0.42&deg; yaw / ~0.30&deg; pitch) to every
 * applied rotation. Movement gates that fire an action once the applied rotation is "close enough" to the target
 * therefore must tolerate that tremor. {@link Rotation#isReallyCloseTo} (0.01&deg;) never fires again once tremor is
 * on, which silently stalls those gates; {@link Rotation#isCloseTo(Rotation, float, float)} exists precisely to give
 * them an explicit, tremor-tolerant tolerance. These tests pin that behaviour, including the &plusmn;180&deg; wrap.
 */
public class RotationTest {

    // The tolerance the tremor-tolerant CLICK_LEFT gates use, and the tremor bound they must survive.
    private static final float GATE_YAW_TOL = 0.75f;
    private static final float GATE_PITCH_TOL = 0.55f;
    private static final float TREMOR_YAW = 0.42f;
    private static final float TREMOR_PITCH = 0.30f;

    @Test
    public void isCloseTo_withinBothTolerances_isTrue() {
        assertTrue(new Rotation(10f, 0f).isCloseTo(new Rotation(12f, 1f), 3f, 3f));
    }

    @Test
    public void isCloseTo_yawOutsideTolerance_isFalse() {
        assertFalse(new Rotation(10f, 0f).isCloseTo(new Rotation(15f, 0f), 3f, 3f));
    }

    @Test
    public void isCloseTo_pitchOutsideTolerance_isFalse() {
        // yaw is within tolerance but pitch is not — both axes must be close
        assertFalse(new Rotation(10f, 0f).isCloseTo(new Rotation(11f, 5f), 3f, 3f));
    }

    @Test
    public void isCloseTo_survivesTheTremorBound() {
        // the whole point: an applied rotation offset by the max tremor is still "close" to the target,
        // so the CLICK_LEFT gate still fires (does not stall) while humanizedLook is on.
        assertTrue(new Rotation(0f, 0f)
                .isCloseTo(new Rotation(TREMOR_YAW, TREMOR_PITCH), GATE_YAW_TOL, GATE_PITCH_TOL));
    }

    @Test
    public void isCloseTo_justOutsideGate_isFalse() {
        assertFalse(new Rotation(0f, 0f).isCloseTo(new Rotation(0.8f, 0f), GATE_YAW_TOL, GATE_PITCH_TOL));
    }

    @Test
    public void isCloseTo_wrapsAcrossPlusMinus180() {
        // 179 and -179 are 2 deg apart across the seam, not 358 — must read as close
        assertTrue(new Rotation(179f, 0f).isCloseTo(new Rotation(-179f, 0f), 3f, 3f));
        assertFalse(new Rotation(179f, 0f).isCloseTo(new Rotation(-170f, 0f), 3f, 3f));
    }

    @Test
    public void isReallyCloseTo_documentsWhyTremorBreaksTheOldGate() {
        // exact match is close; a mere 0.1 deg offset (well below one tremor step) already is NOT — which is
        // exactly why gates keyed on isReallyCloseTo alone go dead once the humanized tremor is applied.
        assertTrue(new Rotation(5f, -3f).isReallyCloseTo(new Rotation(5f, -3f)));
        assertFalse(new Rotation(0f, 0f).isReallyCloseTo(new Rotation(0.1f, 0f)));
    }

    @Test
    public void yawIsReallyClose_wrapsAcrossSeam() {
        assertTrue(new Rotation(180f, 0f).yawIsReallyClose(new Rotation(-180f, 0f)));
        assertFalse(new Rotation(179.5f, 0f).yawIsReallyClose(new Rotation(-179.5f, 0f)));
    }

    @Test
    public void normalizeYaw_wrapsIntoMinus180To180() {
        assertEquals(10f, Rotation.normalizeYaw(370f), 1e-4f);
        assertEquals(-10f, Rotation.normalizeYaw(-370f), 1e-4f);
        assertEquals(-170f, Rotation.normalizeYaw(190f), 1e-4f);
        assertEquals(170f, Rotation.normalizeYaw(-190f), 1e-4f);
        assertEquals(0f, Rotation.normalizeYaw(720f), 1e-4f);
    }

    @Test
    public void clampPitch_clampsToNinetyDegrees() {
        assertEquals(90f, Rotation.clampPitch(120f), 1e-4f);
        assertEquals(-90f, Rotation.clampPitch(-120f), 1e-4f);
        assertEquals(42f, Rotation.clampPitch(42f), 1e-4f);
    }
}
