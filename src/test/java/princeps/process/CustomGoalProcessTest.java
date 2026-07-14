package princeps.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CustomGoalProcessTest {

    @Test
    public void voidTravelOverridesGeneralAutoElytraOptOut() {
        assertTrue(CustomGoalProcess.autoElytraDispatchAllowed(false, false, true));
    }

    @Test
    public void voidTravelTakesOverAnExistingGlide() {
        assertTrue(CustomGoalProcess.autoElytraDispatchAllowed(true, true, true));
    }

    @Test
    public void normalTravelStillRespectsGeneralAutoElytraOptOut() {
        assertFalse(CustomGoalProcess.autoElytraDispatchAllowed(false, false, false));
    }

    @Test
    public void normalTravelDoesNotHijackAManualGlide() {
        assertFalse(CustomGoalProcess.autoElytraDispatchAllowed(true, true, false));
    }

    @Test
    public void goalXZUsesAnInWorldHeightWhenStartingInTheVoid() {
        assertEquals(64, CustomGoalProcess.goalXZFlightY(-70, -64, 320, true));
        assertEquals(19, CustomGoalProcess.goalXZFlightY(-10, 0, 20, true));
        assertEquals(72, CustomGoalProcess.goalXZFlightY(72, -64, 320, false));
    }
}
