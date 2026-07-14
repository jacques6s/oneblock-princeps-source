package princeps.process;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElytraProcessTest {

    @Test
    public void voidCruiseSkipsTheUnusableBelowFloorSolver() {
        assertFalse(ElytraProcess.shouldComputeInitialFlightRoute(true, false, -65.0, -64));
    }

    @Test
    public void regularAndRoofedFlightsKeepUsingTheNativeSolver() {
        assertTrue(ElytraProcess.shouldComputeInitialFlightRoute(true, false, -64.0, -64));
        assertTrue(ElytraProcess.shouldComputeInitialFlightRoute(true, true, -65.0, -64));
        assertTrue(ElytraProcess.shouldComputeInitialFlightRoute(false, false, -65.0, -64));
    }
}
