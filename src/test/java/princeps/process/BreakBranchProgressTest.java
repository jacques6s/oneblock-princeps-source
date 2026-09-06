/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import org.junit.Test;

import static org.junit.Assert.*;

public class BreakBranchProgressTest {
    private static final int IDLE_LIMIT = 120;
    private static final int YIELD_TICKS = 100;
    private static final Cell TARGET = new Cell(68, -56, 72);

    private record Cell(int x, int y, int z) {}

    @Test
    public void observedCompletedCellsKeepProductiveMiningRunningForThousandsOfTicks() {
        BreakBranchProgress guard = guard();
        for (int block = 0; block < 200; block++) {
            Cell target = new Cell(68 + block, -56, 72);
            for (int attempt = 0; attempt < 10; attempt++) {
                assertFalse("healthy block " + block, tick(guard, true, target, Float.NaN));
            }
            // The caller observed the claimed block become AIR; a click intent alone cannot call this.
            guard.observedProgress();
            assertEquals(0, guard.nonProgressTicks());
            assertEquals(0, guard.yieldRemaining());
        }
        assertEquals(2000L, guard.activeTick());
    }

    @Test
    public void slowIncreasingDamageSurvivesLongMiningAndPauseResumeWithoutFalseYield() {
        BreakBranchProgress guard = guard();
        for (int frame = 1; frame <= 600; frame++) {
            // Equivalent fresh coordinate objects still identify the same controller target.
            assertFalse(tick(guard, true, new Cell(68, -56, 72), frame / 1000.0F));
        }
        assertEquals("only acquiring the initial controller sample was nonprogress", 1, guard.nonProgressTicks());
        for (int frame = 0; frame < 900; frame++) {
            guard.beginTick(true, false, false);
            guard.shouldYield(true, TARGET, 0.9F);
        }
        assertEquals(600L, guard.activeTick());
        assertEquals(1, guard.nonProgressTicks());

        // Pausing stopped the controller. Reacquiring and starting its damage counter costs real active frames.
        assertFalse(tick(guard, true, TARGET, Float.NaN));
        assertFalse(tick(guard, true, TARGET, 0.0F));
        assertEquals(3, guard.nonProgressTicks());
        for (int frame = 1; frame <= 800; frame++) {
            assertFalse(tick(guard, true, TARGET, frame / 1000.0F));
        }
        assertEquals("real damage freezes failed time without erasing it", 3, guard.nonProgressTicks());
    }

    @Test
    public void increasingDamagePausesAgingButDoesNotForgiveEarlierFailedFrames() {
        BreakBranchProgress guard = guard();
        unsuccessfulClaims(guard, 50);
        assertFalse(tick(guard, true, TARGET, 0.0F));
        for (int frame = 1; frame <= 500; frame++) {
            assertFalse(tick(guard, true, TARGET, frame / 1000.0F));
        }
        assertEquals(51, guard.nonProgressTicks());
        for (int frame = 0; frame < 69; frame++) {
            assertFalse(tick(guard, true, TARGET, 0.5F));
        }
        assertEquals(IDLE_LIMIT, guard.nonProgressTicks());
        assertTrue("stalled damage eventually spends the remaining budget", tick(guard, true, TARGET, 0.5F));
    }

    @Test
    public void alternatingTargetsCannotBorrowEachOthersIncreasingDamage() {
        BreakBranchProgress guard = guard();
        Cell other = new Cell(69, -56, 72);
        for (int frame = 1; frame <= IDLE_LIMIT; frame++) {
            assertFalse(tick(guard, true, frame % 2 == 0 ? TARGET : other, frame / 1000.0F));
            assertEquals("a new target does not reset failed time", frame, guard.nonProgressTicks());
        }
        assertTrue(tick(guard, true, other, 0.121F));
    }

    @Test
    public void clickRequestsWithoutValidControllerDamageStillYield() {
        BreakBranchProgress guard = guard();
        // Holding attack and choosing a reachable target are claims, not controller progress.
        unsuccessfulClaims(guard, IDLE_LIMIT);
        assertEquals(IDLE_LIMIT, guard.nonProgressTicks());
        assertTrue(tick(guard, true, TARGET, Float.NaN));
        assertTrue(guard.startedYield());
    }

    @Test
    public void repeatedPositiveButFlatDamageStillYields() {
        BreakBranchProgress guard = guard();
        for (int frame = 0; frame < IDLE_LIMIT; frame++) {
            assertFalse(tick(guard, true, TARGET, 0.5F));
        }
        assertTrue("a live-looking but frozen damage counter is not progress", tick(guard, true, TARGET, 0.5F));
    }

    @Test
    public void RepeatedDamageRestartsCannotRenewTheBudget() {
        BreakBranchProgress guard = guard();
        for (int restart = 1; restart <= IDLE_LIMIT; restart++) {
            assertFalse(tick(guard, true, TARGET, 0.0F));
            assertEquals(restart, guard.nonProgressTicks());
            assertFalse(tick(guard, true, TARGET, 0.01F));
            assertEquals("a little damage after each restart does not clear prior failures",
                    restart, guard.nonProgressTicks());
        }
        assertTrue(tick(guard, true, TARGET, 0.0F));
    }

    @Test
    public void invalidDamageNeitherCreditsProgressNorBridgesConsecutiveSamples() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                -0.1F, 1.1F}) {
            BreakBranchProgress guard = guard();
            assertFalse(tick(guard, true, TARGET, 0.0F));
            assertFalse(tick(guard, true, TARGET, invalid));
            assertEquals("invalid controller sample " + invalid, 2, guard.nonProgressTicks());
            assertFalse(tick(guard, true, TARGET, 0.5F));
            assertEquals("a missing valid predecessor requires reacquisition", 3, guard.nonProgressTicks());
            assertFalse(tick(guard, true, TARGET, 0.6F));
            assertEquals(3, guard.nonProgressTicks());
        }
    }

    @Test
    public void increasingNumbersWithoutAControllerTargetAreNotProgress() {
        BreakBranchProgress guard = guard();
        for (int frame = 1; frame <= IDLE_LIMIT; frame++) {
            assertFalse(tick(guard, true, null, frame / 1000.0F));
        }
        assertTrue(tick(guard, true, null, 0.121F));
    }

    @Test
    public void repeatedQueriesWithinOneActiveTickCannotAgeOrConsumeYieldTwice() {
        BreakBranchProgress guard = guard();
        for (int frame = 1; frame <= IDLE_LIMIT; frame++) {
            guard.beginTick(false, false, false);
            for (int query = 0; query < 5; query++) {
                assertFalse(guard.shouldYield(true, TARGET, Float.NaN));
                assertEquals(frame, guard.nonProgressTicks());
            }
        }
        guard.beginTick(false, false, false);
        assertTrue(guard.shouldYield(true, TARGET, Float.NaN));
        int remaining = guard.yieldRemaining();
        for (int query = 0; query < 5; query++) {
            assertTrue(guard.shouldYield(true, TARGET, Float.NaN));
            assertEquals(remaining, guard.yieldRemaining());
            assertEquals(0, guard.nonProgressTicks());
        }
        assertEquals(121L, guard.activeTick());
    }

    @Test
    public void yieldOccupiesExactlyOneHundredActiveFramesAndDoesNotCountTowardTheNextYield() {
        BreakBranchProgress guard = guard();
        startYield(guard);
        assertEquals(YIELD_TICKS, guard.yieldRemaining());
        for (int elapsed = 1; elapsed < YIELD_TICKS; elapsed++) {
            assertTrue(tick(guard, true, TARGET, Float.NaN));
            assertEquals(YIELD_TICKS - elapsed, guard.yieldRemaining());
            assertEquals("the placement/recovery turn is not a starving break turn", 0, guard.nonProgressTicks());
            assertFalse("only the first frame announces this yield", guard.startedYield());
        }
        assertFalse(tick(guard, true, TARGET, Float.NaN));
        assertEquals(0, guard.yieldRemaining());
        assertEquals(1, guard.nonProgressTicks());
        unsuccessfulClaims(guard, IDLE_LIMIT - 1);
        assertEquals(IDLE_LIMIT, guard.nonProgressTicks());
        assertTrue("the complete next budget is available, not only 22 ticks", tick(guard, true, TARGET, Float.NaN));
        assertTrue(guard.startedYield());
    }

    @Test
    public void repeatedInterruptionsFreezeFailedTimeWithoutResettingTheRemainingBudget() {
        BreakBranchProgress guard = guard();
        unsuccessfulClaims(guard, IDLE_LIMIT - 1);
        freezeWithEveryInterruption(guard, 50);
        assertEquals(119L, guard.activeTick());
        assertEquals(119, guard.nonProgressTicks());
        assertFalse(tick(guard, true, TARGET, Float.NaN));
        freezeWithEveryInterruption(guard, 50);
        assertEquals(120L, guard.activeTick());
        assertEquals(120, guard.nonProgressTicks());
        assertTrue(tick(guard, true, TARGET, Float.NaN));
    }

    @Test
    public void pauseBorrowingAndConsumptionAlsoFreezeAnAlreadyActiveYield() {
        BreakBranchProgress guard = guard();
        startYield(guard);
        for (int frame = 0; frame < 9; frame++) {
            assertTrue(tick(guard, true, TARGET, Float.NaN));
        }
        assertEquals(91, guard.yieldRemaining());
        long beforePause = guard.activeTick();
        freezeWithEveryInterruption(guard, 80);
        assertEquals(beforePause, guard.activeTick());
        assertEquals(91, guard.yieldRemaining());
        assertEquals(0, guard.nonProgressTicks());
        for (int frame = 0; frame < 90; frame++) {
            assertTrue(tick(guard, true, TARGET, Float.NaN));
        }
        assertFalse(tick(guard, true, TARGET, Float.NaN));
        assertEquals(1, guard.nonProgressTicks());
    }

    @Test
    public void observedWorldProgressClearsBothFailedTimeAndAnOutstandingYield() {
        BreakBranchProgress guard = guard();
        unsuccessfulClaims(guard, 95);
        guard.observedProgress();
        assertEquals(0, guard.nonProgressTicks());
        startYield(guard);
        guard.beginTick(true, false, false);
        guard.observedProgress();
        assertEquals("real world observations remain valid during pause", 0, guard.nonProgressTicks());
        assertEquals(0, guard.yieldRemaining());
        assertFalse(tick(guard, true, TARGET, Float.NaN));
        assertEquals(1, guard.nonProgressTicks());
        assertFalse(guard.startedYield());
    }

    @Test
    public void MissingOrIneligibleClaimsBreakTheConsecutiveStarvationEpisode() {
        for (boolean missingCall : new boolean[]{false, true}) {
            BreakBranchProgress guard = guard();
            unsuccessfulClaims(guard, IDLE_LIMIT);
            guard.beginTick(false, false, false);
            if (!missingCall) {
                assertFalse(guard.shouldYield(false, TARGET, Float.NaN));
            }
            // A tick that returned before the branch must be detected at the following beginTick, too.
            unsuccessfulClaims(guard, IDLE_LIMIT);
            assertEquals("fresh consecutive episode after missingCall=" + missingCall,
                    IDLE_LIMIT, guard.nonProgressTicks());
            assertTrue(tick(guard, true, TARGET, Float.NaN));
        }
    }

    @Test
    public void clearingForANewJobIsIdempotentAndDropsYieldClockAndDamageHistory() {
        BreakBranchProgress guard = guard();
        startYield(guard);
        guard.clear();
        guard.clear();
        assertEquals(0L, guard.activeTick());
        assertEquals(0, guard.nonProgressTicks());
        assertEquals(0, guard.yieldRemaining());
        assertFalse(guard.startedYield());
        unsuccessfulClaims(guard, IDLE_LIMIT);
        assertTrue(tick(guard, true, TARGET, Float.NaN));

        guard.clear();
        assertFalse(tick(guard, true, TARGET, 0.4F));
        assertFalse(tick(guard, true, TARGET, 0.5F));
        assertEquals(1, guard.nonProgressTicks());
        guard.clear();
        guard.clear();
        assertFalse(tick(guard, true, TARGET, 0.6F));
        assertEquals("the old job's lower sample cannot grant the new job progress", 1, guard.nonProgressTicks());
        assertEquals(1L, guard.activeTick());
    }

    private static BreakBranchProgress guard() {
        return new BreakBranchProgress(IDLE_LIMIT, YIELD_TICKS);
    }

    private static boolean tick(BreakBranchProgress guard, boolean claims, Object target, float damage) {
        guard.beginTick(false, false, false);
        return guard.shouldYield(claims, target, damage);
    }

    private static void unsuccessfulClaims(BreakBranchProgress guard, int count) {
        for (int frame = 0; frame < count; frame++) {
            assertFalse("claim " + (frame + 1) + " of " + count, tick(guard, true, TARGET, Float.NaN));
        }
    }

    private static void startYield(BreakBranchProgress guard) {
        unsuccessfulClaims(guard, IDLE_LIMIT);
        assertTrue(tick(guard, true, TARGET, Float.NaN));
        assertEquals(0, guard.nonProgressTicks());
        assertTrue(guard.startedYield());
    }

    private static void freezeWithEveryInterruption(BreakBranchProgress guard, int repetitions) {
        long activeTick = guard.activeTick();
        int failed = guard.nonProgressTicks();
        int remaining = guard.yieldRemaining();
        for (int repeat = 0; repeat < repetitions; repeat++) {
            for (int flags = 1; flags < 8; flags++) {
                guard.beginTick((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
                // Even a poll reporting no eligible target during borrowed hands must not reset the frozen run.
                guard.shouldYield(false, null, Float.NaN);
                assertEquals(activeTick, guard.activeTick());
                assertEquals(failed, guard.nonProgressTicks());
                assertEquals(remaining, guard.yieldRemaining());
            }
        }
    }
}
