/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

public class PlacementTargetLockTest {

    @Test
    public void alternatingCandidatesCannotStealCommittedTarget() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(60, 80, 240);

        assertTrue(lock.acquire(11L, "redstone"));
        for (int tick = 0; tick < 10_000; tick++) {
            long candidate = (tick & 1) == 0 ? 22L : 11L;
            assertEquals(candidate == 11L, lock.acquire(candidate, "piston-" + tick));
            assertTrue(lock.owns(11L));
            assertEquals("redstone", lock.attempt().orElse(null));
        }
    }

    @Test
    public void aimTimeoutTransitionsExactlyOnceToRecovery() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        assertTrue(lock.acquire(7L, "click-plan"));

        assertFalse(lock.markAimBlocked(7L, 100L));
        assertFalse(lock.markAimBlocked(7L, 100L));
        assertTrue(lock.markAimBlocked(7L, 100L));
        assertTrue(lock.isRecovering());
        assertFalse(lock.attempt().isPresent());
        assertFalse(lock.mayTryStance(100L));

        // Recovery remains owned by the same target; a neighbour cannot reset its timeout/history.
        assertFalse(lock.acquire(8L, "neighbour"));
        assertTrue(lock.owns(7L));
        assertEquals(1, lock.rejectedStanceCount());
    }

    @Test
    public void refreshingGeometryKeepsTargetAndDoesNotResetAimTimeout() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        assertTrue(lock.acquire(7L, "ray-at-x-0.20"));

        assertFalse(lock.markAimBlocked(7L, 100L));
        lock.refreshAttempt(7L, "ray-at-x-0.35");
        assertFalse(lock.markAimBlocked(7L, 100L));
        lock.refreshAttempt(7L, "ray-at-x-0.49");

        assertEquals("ray-at-x-0.49", lock.attempt().orElse(null));
        assertTrue(lock.owns(7L));
        assertTrue(lock.markAimBlocked(7L, 100L));
        assertTrue(lock.isRecovering());
        assertFalse(lock.mayTryStance(100L));
    }

    @Test
    public void refreshedGeometryCannotChangeTargetOwnership() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        assertTrue(lock.acquire(7L, "first-ray"));

        lock.refreshAttempt(7L, "same-target-new-ray");

        assertEquals("same-target-new-ray", lock.attempt().orElse(null));
        assertTrue(lock.owns(7L));
        assertThrows(IllegalStateException.class, () -> lock.refreshAttempt(8L, "stolen-ray"));
        assertTrue(lock.owns(7L));
    }

    @Test
    public void improvingAimNeverTimesOutWhileGeometryRefreshes() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        assertTrue(lock.acquire(7L, "ray-0"));

        for (int tick = 0; tick < 100; tick++) {
            lock.refreshAttempt(7L, "ray-" + tick);
            assertFalse(lock.markAimConverging(7L, 180.0D - tick, 10));
            assertTrue(lock.owns(7L));
            assertFalse(lock.isRecovering());
        }
    }

    @Test
    public void stagnantUnalignedAimIsBoundedWithoutImplicitRecoveryPolicy() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        assertTrue(lock.acquire(7L, "ray"));

        for (int tick = 0; tick < 10; tick++) {
            assertFalse(lock.markAimConverging(7L, 42.0D, 10));
        }
        assertTrue(lock.markAimConverging(7L, 42.0D, 10));

        // The Minecraft-aware caller chooses ordinary yield versus strict stance recovery.
        assertTrue(lock.owns(7L));
        assertFalse(lock.isRecovering());
    }

    @Test
    public void recoveryWithoutFailedStanceKeepsOwnershipAndDoesNotRejectAStance() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        assertTrue(lock.acquire(7L, "click-plan"));

        lock.startRecovery();

        assertTrue(lock.isActive());
        assertTrue(lock.isRecovering());
        assertTrue(lock.owns(7L));
        assertFalse(lock.attempt().isPresent());
        assertEquals(0, lock.rejectedStanceCount());
        assertTrue(lock.mayTryStance(100L));
        assertTrue(lock.mayTryStance(101L));
    }

    @Test
    public void routeRequiresRealDistanceProgressAndRejectsStalledStance() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 3, 20);
        lock.acquireForRecovery(7L, 100L);
        lock.planStance(101L, 25L);

        assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(25L, false));
        assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(24L, false));
        assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(24L, false));
        assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(24L, false));
        assertEquals(PlacementTargetLock.RouteDecision.RETRY_STANCE, lock.routeTick(24L, false));
        assertFalse(lock.hasPlannedStance());
        assertFalse(lock.mayTryStance(101L));
        assertTrue(lock.mayTryStance(102L));
    }

    @Test
    public void routeDeadlineCannotRejectWhileDistanceKeepsImproving() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 3, 4);
        lock.acquireForRecovery(7L, 100L);
        lock.planStance(101L, 100L);

        for (long distance = 99L; distance >= 90L; distance--) {
            assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(distance, false));
            assertTrue(lock.hasPlannedStance());
        }

        assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(90L, false));
        assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(90L, false));
        assertEquals(PlacementTargetLock.RouteDecision.RETRY_STANCE, lock.routeTick(90L, false));
        assertFalse(lock.mayTryStance(101L));
    }

    @Test
    public void arrivalCanInstallNewAttemptForSameTarget() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        lock.acquireForRecovery(7L, 100L);
        lock.planStance(101L, 1L);

        assertEquals(PlacementTargetLock.RouteDecision.ARRIVED, lock.routeTick(0L, true));
        lock.setAttempt(7L, "opposite-side-click");

        assertFalse(lock.isRecovering());
        assertEquals("opposite-side-click", lock.attempt().orElse(null));
        assertTrue(lock.owns(7L));
    }

    @Test
    public void terminalReleaseIsTheOnlyWayAnotherTargetCanOwnLock() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 5, 20);
        assertTrue(lock.acquire(1L, "first"));
        assertFalse(lock.acquire(2L, "second"));

        lock.release();

        assertFalse(lock.isActive());
        assertTrue(lock.acquire(2L, "second"));
        assertTrue(lock.owns(2L));
    }

    @Test
    public void rejectedRecoveryStancesDoNotPermitTargetTheft() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 2, 4);
        assertTrue(lock.acquire(1L, "first"));
        lock.startRecovery();

        for (long stance = 100L; stance < 104L; stance++) {
            lock.planStance(stance, 10L);
            assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(10L, false));
            assertEquals(PlacementTargetLock.RouteDecision.RETRY_STANCE, lock.routeTick(10L, false));

            assertTrue(lock.isActive());
            assertTrue(lock.isRecovering());
            assertTrue(lock.owns(1L));
            assertFalse(lock.acquire(2L, "second"));
            assertFalse(lock.attempt().isPresent());
        }

        assertEquals(4, lock.rejectedStanceCount());
        assertTrue(lock.owns(1L));
    }

    @Test
    public void rejectedStanceExhaustionDoesNotImplicitlyReleaseTarget() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(3, 2, 4);
        assertTrue(lock.acquire(1L, "first"));
        lock.startRecovery();

        for (long stance = 100L; stance < 103L; stance++) {
            lock.planStance(stance, 10L);
            lock.rejectPlannedStance();
        }

        assertTrue(lock.isActive());
        assertTrue(lock.isRecovering());
        assertTrue(lock.owns(1L));
        assertFalse(lock.hasPlannedStance());
        assertFalse(lock.acquire(2L, "second"));

        lock.release();

        assertFalse(lock.isActive());
        assertTrue(lock.acquire(2L, "second"));
        assertTrue(lock.owns(2L));
    }

    @Test
    public void aMeasuredDetourKeepsTheSameStancePastTheFormerHardDeadline() {
        PlacementTargetLock<String> lock = new PlacementTargetLock<>(7, 11, 37);
        lock.acquireForRecovery(1L, 2L);
        lock.planStance(3L, 10L);
        for (int tick = 0; tick < 1200; tick++) {
            assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(10L + tick, false, true));
        }
        assertTrue(lock.owns(1L));
        assertEquals(3L, lock.plannedStanceKey());
        for (int tick = 0; tick < 10; tick++) {
            assertEquals(PlacementTargetLock.RouteDecision.KEEP_MOVING, lock.routeTick(2000L, false, false));
        }
        assertEquals(PlacementTargetLock.RouteDecision.RETRY_STANCE, lock.routeTick(2000L, false, false));
    }

    @Test
    public void randomizedTargetFlappingNeverBreaksOwnershipInvariant() {
        Random random = new Random(0x5C4E6A71CL);
        PlacementTargetLock<Integer> lock = new PlacementTargetLock<>(7, 11, 37);
        long expectedOwner = -1L;

        for (int tick = 0; tick < 250_000; tick++) {
            if (!lock.isActive()) {
                expectedOwner = random.nextInt(32);
                assertTrue(lock.acquire(expectedOwner, tick));
            } else if (random.nextInt(97) == 0) {
                lock.release();
                expectedOwner = -1L;
                continue;
            } else {
                long candidate = random.nextInt(32);
                boolean accepted = lock.acquire(candidate, -tick);
                assertEquals(candidate == expectedOwner, accepted);
                assertTrue(lock.owns(expectedOwner));
            }
        }
    }
}
