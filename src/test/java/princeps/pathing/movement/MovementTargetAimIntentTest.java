/*
 * This file is part of Princeps.
 */
package princeps.pathing.movement;

import org.junit.Test;
import princeps.api.behavior.look.AimIntent;
import princeps.api.utils.Rotation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pins the intent carried from a movement decision into LookBehavior. */
public class MovementTargetAimIntentTest {

    @Test
    public void namedFactoriesCannotConfuseBreakAndPlacement() {
        Rotation rotation = new Rotation(37.0F, 61.0F);
        MovementState.MovementTarget breaking = MovementState.MovementTarget.forBreak(rotation);
        MovementState.MovementTarget placing = MovementState.MovementTarget.forPlacement(rotation);

        assertEquals(AimIntent.BREAK, breaking.getAimIntent());
        assertTrue(breaking.isBreakIntent());
        assertFalse(breaking.isPlaceIntent());
        assertEquals(AimIntent.PLACE, placing.getAimIntent());
        assertFalse(placing.isBreakIntent());
        assertTrue(placing.isPlaceIntent());
        assertTrue(placing.hasToForceRotations());
    }

    @Test(expected = IllegalArgumentException.class)
    public void legacyFlagsRejectAnImpossibleDualInteraction() {
        AimIntent.fromLegacy(true, true);
    }

    @Test
    public void ordinaryMovementHasNoInteractionIntent() {
        MovementState.MovementTarget target = new MovementState.MovementTarget(new Rotation(0.0F, 0.0F), false);
        assertEquals(AimIntent.NONE, target.getAimIntent());
    }
}
