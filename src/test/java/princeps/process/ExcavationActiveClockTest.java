/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ExcavationActiveClockTest {
    private static final BlockPos PLUG = new BlockPos(10, 22, 30);
    private static BlockState WATER;
    private static BlockState COBBLESTONE;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WATER = Blocks.WATER.defaultBlockState();
        COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    }

    @Test
    public void everyInterruptionCombinationFreezesTheClockAndAnActiveTickCountsOnce() {
        ExcavationActiveClock clock = new ExcavationActiveClock();
        assertEquals(0, clock.now());
        advance(clock, 7);
        for (int flags = 1; flags < 8; flags++) {
            clock.tick((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
            assertEquals("any interruption freezes active time: " + flags, 7, clock.now());
        }
        clock.tick(false, false, false);
        assertEquals(8, clock.now());
        assertEquals("reading the clock cannot advance it", 8, clock.now());
    }

    @Test
    public void longPausePreservesTheRemainingHazardDeadlineInsteadOfAgingOrResettingIt() {
        ExcavationActiveClock clock = new ExcavationActiveClock();
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        ExcavationFluidPlugs.Hazard hazard = hazard();
        advance(clock, 100);
        assertFalse(plugs.waitExpired(hazard, clock.now()));
        advance(clock, 75);
        assertFalse(plugs.waitExpired(hazard, clock.now()));

        for (int tick = 0; tick < 850; tick++) {
            clock.tick(true, false, false);
            assertFalse(plugs.waitExpired(hazard, clock.now()));
        }
        assertEquals(175, clock.now());
        advance(clock, 124);
        assertFalse(plugs.waitExpired(hazard, clock.now()));
        advance(clock, 1);
        assertTrue("the remaining 125 active ticks still expire the original wait",
                plugs.waitExpired(hazard, clock.now()));
        clock.tick(true, false, false);
        assertTrue("pausing an expired wait does not renew it", plugs.waitExpired(hazard, clock.now()));
    }

    @Test
    public void repeatedPauseResumeAndPlacementRetriesDoNotExtendTheActiveHazardDeadline() {
        ExcavationActiveClock clock = new ExcavationActiveClock();
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        ExcavationFluidPlugs.Hazard hazard = hazard();
        advance(clock, 100);
        plugs.record(PLUG, WATER, COBBLESTONE, clock.now());
        assertFalse(plugs.waitExpired(hazard, clock.now()));

        for (int elapsed = 1; elapsed <= 200; elapsed++) {
            clock.tick(true, false, false);
            clock.tick(false, true, false);
            clock.tick(false, false, true);
            plugs.record(PLUG, WATER, COBBLESTONE, clock.now());
            plugs.observe(PLUG, WATER);
            assertFalse("paused polls and retries are not progress", plugs.waitExpired(hazard, clock.now()));
            clock.tick(false, false, false);
            assertEquals("only active elapsed time controls expiry", elapsed == 200,
                    plugs.waitExpired(hazard, clock.now()));
        }
        assertEquals(300, clock.now());
    }

    @Test
    public void inventoryBorrowingAndConsumptionFreezeTheOriginalPendingConfirmationDeadline() {
        ExcavationActiveClock clock = new ExcavationActiveClock();
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        advance(clock, 100);
        plugs.record(PLUG, WATER, COBBLESTONE, clock.now());
        advance(clock, 399);

        for (int tick = 0; tick < 450; tick++) {
            clock.tick(false, true, false);
            clock.tick(false, false, true);
            plugs.record(PLUG, WATER, COBBLESTONE, clock.now());
            plugs.observe(PLUG, WATER);
            assertTrue(plugs.unconfirmedBefore(clock.now() - 400).isEmpty());
        }
        assertEquals(499, clock.now());
        advance(clock, 1);
        assertTrue("the pending cutoff is strict at exactly 400 active ticks",
                plugs.unconfirmedBefore(clock.now() - 400).isEmpty());
        advance(clock, 1);
        assertEquals("retries cannot replace the original request time", PLUG,
                plugs.unconfirmedBefore(clock.now() - 400).orElseThrow());
    }

    @Test
    public void firstServerConfirmationDuringInterruptionRemainsRealProgress() {
        ExcavationActiveClock clock = new ExcavationActiveClock();
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        ExcavationFluidPlugs.Hazard hazard = hazard();
        advance(clock, 100);
        plugs.record(PLUG, WATER, COBBLESTONE, clock.now());
        assertFalse(plugs.waitExpired(hazard, clock.now()));
        advance(clock, 199);
        assertFalse(plugs.waitExpired(hazard, clock.now()));
        for (int tick = 0; tick < 450; tick++) clock.tick(false, true, true);

        plugs.observe(PLUG, COBBLESTONE);
        assertTrue(plugs.unconfirmedBefore(Long.MAX_VALUE).isEmpty());
        assertFalse("a first confirmed seal legitimately renews the hazard wait",
                plugs.waitExpired(hazard, clock.now()));
        for (int tick = 0; tick < 450; tick++) {
            clock.tick(true, false, false);
            plugs.observe(PLUG, COBBLESTONE);
            assertFalse(plugs.waitExpired(hazard, clock.now()));
        }
        assertEquals(299, clock.now());
        for (int tick = 0; tick < 199; tick++) {
            advance(clock, 1);
            plugs.observe(PLUG, COBBLESTONE);
            assertFalse("duplicate confirmations cannot renew the deadline",
                    plugs.waitExpired(hazard, clock.now()));
        }
        advance(clock, 1);
        assertTrue(plugs.waitExpired(hazard, clock.now()));
    }

    @Test
    public void newExcavationClearsBothClockAndPlugDeadlines() {
        ExcavationActiveClock clock = new ExcavationActiveClock();
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        ExcavationFluidPlugs.Hazard hazard = hazard();
        advance(clock, 100);
        plugs.record(PLUG, WATER, COBBLESTONE, clock.now());
        assertFalse(plugs.waitExpired(hazard, clock.now()));
        advance(clock, 199);
        clock.clear();
        plugs.clear();
        assertEquals(0, clock.now());
        assertEquals(0, plugs.size());
        assertTrue(plugs.unconfirmedBefore(Long.MAX_VALUE).isEmpty());

        plugs.record(PLUG, WATER, COBBLESTONE, clock.now());
        assertFalse(plugs.waitExpired(hazard, clock.now()));
        advance(clock, 199);
        assertFalse(plugs.waitExpired(hazard, clock.now()));
        advance(clock, 1);
        assertTrue(plugs.waitExpired(hazard, clock.now()));
        advance(clock, 200);
        assertTrue(plugs.unconfirmedBefore(clock.now() - 400).isEmpty());
        advance(clock, 1);
        assertEquals(PLUG, plugs.unconfirmedBefore(clock.now() - 400).orElseThrow());
    }

    private static ExcavationFluidPlugs.Hazard hazard() {
        return new ExcavationFluidPlugs.Hazard(PLUG, List.of(PLUG.north(), PLUG.west()));
    }

    private static void advance(ExcavationActiveClock clock, int ticks) {
        for (int tick = 0; tick < ticks; tick++) clock.tick(false, false, false);
    }
}
