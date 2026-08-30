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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins who may be pulled towards the walking pitch band ({@link LookBehavior#mayNudgePitchToLevel}).
 *
 * <p>The nudge exists for ONE caller shape: a cruising target that had no opinion about pitch and passed the
 * player's own through. Equal pitches are the only evidence that this is such a target, and that evidence is
 * worthless for an aim that means its pitch -- an arrived aim always has equal pitches. Applying it to those turned
 * a held straight-down excavation entry aim into a level one inside the reach PREDICTION, so the predicted ray flew
 * over the roof and missed; the break site publishes its look target only once that prediction hits, so the target
 * was never published, the head never turned, and the run stood still for its whole budget with the crosshair
 * resting on the correct block. These cases are the fence around that.
 */
public class LookBehaviorPitchNudgeTest {

    private static final float STRAIGHT_DOWN = 90.0F;

    /** The one intended caller: cruising, no interaction, pitch echoed back from the player. */
    @Test
    public void cruisingTargetThatEchoedThePlayerPitchIsNudged() {
        assertTrue(LookBehavior.mayNudgePitchToLevel(false, false, 42.0F, 42.0F));
    }

    /** An arrived aim is the normal state of a working excavation, not a signal that pitch is negotiable. */
    @Test
    public void anArrivedPreciseAimIsNeverNudged() {
        assertFalse("a precise interaction that has ARRIVED is exactly when the old guess misfired",
                LookBehavior.mayNudgePitchToLevel(false, true, STRAIGHT_DOWN, STRAIGHT_DOWN));
    }

    /** Reach and placement planning ray-trace against the prediction; moving its pitch moves where it points. */
    @Test
    public void anExactPredictionIsNeverNudged() {
        assertFalse(LookBehavior.mayNudgePitchToLevel(true, false, STRAIGHT_DOWN, STRAIGHT_DOWN));
        assertFalse(LookBehavior.mayNudgePitchToLevel(true, true, STRAIGHT_DOWN, STRAIGHT_DOWN));
    }

    /** Nothing changes for a target that genuinely wants a different pitch than the head currently holds. */
    @Test
    public void aTargetThatDisagreesWithTheHeadIsNeverNudged() {
        assertFalse(LookBehavior.mayNudgePitchToLevel(false, false, STRAIGHT_DOWN, 0.0F));
        assertFalse(LookBehavior.mayNudgePitchToLevel(false, true, STRAIGHT_DOWN, 0.0F));
    }
}
