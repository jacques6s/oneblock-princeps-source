/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process.builder;

import org.junit.Test;
import static org.junit.Assert.*;
import static princeps.process.builder.BuilderProgressWatch.*;

public class BuilderProgressWatchTest {
    private static BuilderProgressWatch watch() { return new BuilderProgressWatch(5, 60, 2); }
    private static Sample sample(double distance, Object route, int index) {
        return new Sample(Phase.WORK, 7L, distance, route, index, 0, null, 0);
    }
    @Test public void oneBlockPendulumAndEquivalentGoalReconstructionsStop() {
        var watch = watch(); Decision decision = null;
        for (int second = 0; second <= 65; second++) {
            decision = watch.observe(second, sample(10 + second % 2, new String("same-path"), second % 2));
        }
        assertEquals(Decision.STOP, decision);
    }
    @Test public void changingTargetsAndCreatingRoutesDoNotRefreshTheDeadline() {
        var watch = watch(); Decision decision = null;
        for (int second = 0; second <= 61; second++) {
            decision = watch.observe(second, new Sample(Phase.WORK, (long) second, 10, "new-route-" + second, 0, 0, null, 0));
        }
        assertEquals(Decision.STOP, decision);
    }
    @Test public void fiveSecondsWithoutTwoBlocksApproachOnlyDiagnoses() {
        var watch = watch(); watch.observe(0, sample(10, null, -1));
        assertEquals(Decision.DIAGNOSE, watch.observe(5, sample(9.5, null, -1)));
        assertEquals(Decision.CONTINUE, watch.observe(6, sample(9.5, null, -1)));
        assertEquals(Decision.CONTINUE, watch.observe(10, sample(7.9, null, -1)));
    }
    @Test public void aLongValidDetourKeepsAdvancingEvenWhileStraightLineDistanceGrows() {
        var watch = watch();
        for (int second = 0; second <= 600; second++) {
            assertNotEquals(Decision.STOP, watch.observe(second, sample(10 + second, "detour", second)));
        }
    }
    @Test public void shortenedPathsCanCreditAPhysicallyReachedNodeOnTheirPredecessor() {
        var watch = watch(); watch.beginRoute("path-0", 0);
        watch.observe(0, sample(10, "path-0", 0));
        for (int second = 1; second <= 300; second++) {
            watch.beginRoute("path-" + second, 0);
            assertNotEquals(Decision.STOP, watch.observe(second, sample(10 + second, "path-" + (second - 1), 1)));
        }
    }
    @Test public void moreThan128DistinctRouteAndTargetIdentitiesStillReceiveRealProgress() {
        var watch = watch();
        for (int index = 0; index < 300; index++) {
            watch.beginRoute("route-" + index, 0);
            watch.observe(index, new Sample(Phase.WORK, (long) index, 10, "route-" + index, 1, 0, null, 0));
        }
        assertTrue(watch.advancedLastSample());
        assertEquals(0, watch.quietNanos());
    }
    @Test public void explicitPauseWithoutAnyIntermediateSampleDoesNotChargePausedWallTime() {
        var watch = watch(); watch.observe(0, sample(10, null, -1));
        watch.observe(4, sample(10, null, -1)); watch.pause();
        assertNotEquals(Decision.STOP, watch.observe(10000, sample(10, null, -1)));
        assertEquals(4, watch.quietNanos());
        assertEquals(Decision.STOP, watch.observe(10056, sample(10, null, -1)));
    }
    @Test public void waitReasonsAreNotProgressAndCannotMaskAStallForever() {
        for (Phase phase : new Phase[]{Phase.WAIT_CHUNK, Phase.WAIT_MATERIAL, Phase.WAIT_CONFIRMATION}) {
            var watch = watch();
            watch.observe(0, new Sample(phase, 7L, 10, "route", 0, 0, null, 0));
            assertEquals(Decision.STOP, watch.observe(60, new Sample(phase, 7L, 0, "route", 50, 0, null, 0)));
        }
    }
    @Test public void switchingBetweenWaitReasonsDoesNotRestartTheirBudget() {
        var watch = watch();
        for (int second = 0; second < 60; second++) {
            watch.observe(second, new Sample(second % 2 == 0 ? Phase.WAIT_CHUNK : Phase.WAIT_MATERIAL, 7L, 10, null, -1, 0, null, 0));
        }
        assertEquals(Decision.STOP, watch.observe(60, sample(10, null, -1)));
    }
    @Test public void slowMiningMakesProgressWithoutAnyMovementOrFinishedBlock() {
        var watch = watch();
        for (int second = 0; second <= 600; second++) {
            assertNotEquals(Decision.STOP, watch.observe(second, new Sample(Phase.MINING, 7L, 2, null, -1, 0, 8L, second / 1000.0)));
        }
    }
    @Test public void restartingTheSameUnconfirmedMiningAnimationIsNotFreshProgress() {
        var watch = watch(); Decision decision = null;
        for (int second = 0; second < 100; second++) {
            decision = watch.observe(second, new Sample(Phase.MINING, 7L, 2, null, -1, 0, 8L, second % 10 / 10.0));
        }
        assertEquals(Decision.STOP, decision);
    }
    @Test public void confirmedActionResetsTheWatchButRepeatedSameRevisionDoesNot() {
        var watch = watch(); watch.observe(0, sample(10, null, -1));
        assertEquals(Decision.CONTINUE, watch.observe(59, new Sample(Phase.WAIT_CONFIRMATION, 7L, 10, null, -1, 1, null, 0)));
        assertEquals(Decision.STOP, watch.observe(119, new Sample(Phase.WAIT_CONFIRMATION, 7L, 10, null, -1, 1, null, 0)));
    }
    @Test public void completedOldRedstoneAndPowerCyclesCannotCreditServerPacketsAgain() {
        var actions = new ConfirmedBuildActions<String>(String::equals);
        actions.arm(7, "air", "repeater-off");
        assertFalse(actions.serverChanged(7, "repeater-on"));
        assertTrue(actions.serverChanged(7, "repeater-off"));
        for (int i = 0; i < 100; i++) {
            assertFalse(actions.serverChanged(7, i % 2 == 0 ? "repeater-on" : "repeater-off"));
        }
        actions.arm(8, "stone", "air");
        assertFalse(actions.serverChanged(8, "powered-stone"));
        assertTrue(actions.serverChanged(8, "air"));
        assertFalse(actions.serverChanged(8, "air"));
    }
    @Test public void duplicateRequestsCannotOverwriteAnOutstandingExpectedBlockWithAPredictedWrongBlock() {
        var actions = new ConfirmedBuildActions<String>(String::equals);
        actions.arm(7, "air", "piston-up"); actions.arm(7, "air", "piston-east");
        assertFalse(actions.serverChanged(7, "piston-east"));
        assertTrue(actions.serverChanged(7, "piston-up"));
        actions.arm(7, "piston-up", "piston-up");
        assertFalse(actions.serverChanged(7, "piston-up"));
    }
}
