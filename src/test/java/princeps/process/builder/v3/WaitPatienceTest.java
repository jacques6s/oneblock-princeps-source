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

package princeps.process.builder.v3;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every wait ends. That is the whole of this file.
 *
 * <h2>What it is for</h2>
 * <p>The etz-basalt run froze for 44 000 ticks in APPROACH with velocity zero and produced no divergence and no log
 * line. The state HAD a patience counter — 60 ticks — and the branch the bot was in returned before reaching it. That
 * is the general shape of the defect and not a detail of one method: a guard placed inside a state bounds only the
 * paths through that state that execute it, and the frozen path never does, by definition.
 *
 * <p>So the timing moved to one place, {@link CellExecutor#impatience}, which runs at the top of the tick before any
 * branch can return. It is pure, which is why this test can exist at all: the executor itself needs a live
 * {@code Princeps} and a live world, and a property phrased as "and then nothing happens for ever" cannot be observed
 * by running something.
 *
 * <h2>The two clocks and why one is not enough</h2>
 * <p>Per-state patience catches a state that is stuck. It cannot catch states that are not stuck individually and
 * pass the action between themselves — APPROACH returns to TRAVEL when the feet leave the stance cell, TRAVEL returns
 * to APPROACH when they are back, and {@code enter()} resets the state clock on each hop, so both counters sit at
 * zero for ever. The per-ACTION clock is not reset by a transition and is the only bound that sees that loop.
 */
public class WaitPatienceTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos CELL = new BlockPos(4, 61, -12);

    // -------------------------------------------------------------------------------- every state is bounded

    /**
     * No state the executor ticks may wait for ever, and the table saying so is exhaustive over the enum.
     *
     * <p>Written as a loop over {@code values()} rather than as a list of the states that happen to exist today: a
     * ninth state added without a patience is what this assertion is here to catch, and a hand-written list would not
     * catch it.
     */
    @Test
    public void everyStateTheExecutorTicksHasAFinitePatience() {
        for (CellExecutor.State state : CellExecutor.State.values()) {
            int patience = CellExecutor.patienceTicks(state);
            if (state == CellExecutor.State.DONE || state == CellExecutor.State.DIVERGED
                    || state == CellExecutor.State.BLOCKED) {
                assertEquals(state + " is terminal and is never ticked",
                        CellExecutor.TERMINAL_STATE, patience);
                continue;
            }
            assertTrue(state + " must carry a finite, positive patience, got " + patience, patience > 0);
            assertTrue(state + " patience " + patience + " is not a bound, it is a lifetime", patience <= 6000);
        }
    }

    /**
     * A state that cannot progress DIVERGES, and says which state and what it was waiting for.
     *
     * <p>The sentence matters as much as the divergence. "The plan underneath it was what was wrong" is a conclusion
     * somebody reached from a trace, and a run that stops without naming the state it stopped in gives them nothing
     * to reach it from.
     */
    @Test
    public void aStateHeldPastItsPatienceProducesANamedDivergence() {
        BuildAction action = placement();
        for (CellExecutor.State state : CellExecutor.State.values()) {
            int patience = CellExecutor.patienceTicks(state);
            if (patience == CellExecutor.TERMINAL_STATE) {
                continue;
            }
            assertNull(state + " must still be patient at exactly its limit",
                    CellExecutor.impatience(state, patience, patience, action, 7, null));
            String reason = CellExecutor.impatience(state, patience + 1L, patience + 1L, action, 7, null);
            assertNotNull(state + " must diverge one tick past its limit", reason);
            assertTrue("the reason must name the state, got: " + reason, reason.contains(state.name()));
            assertTrue("and say how long it waited, got: " + reason, reason.contains(String.valueOf(patience + 1)));
        }
    }

    /**
     * A state that left a NOTE is explained by the note, not by the generic sentence.
     *
     * <p>Measured on the bench, not reasoned about: the old in-state check produced "the gate said ENTITY_IN_CELL for
     * 60 ticks" and the first version of the central one produced "the gate never opened for 72,-59,67". Same bound,
     * same stand-down, and the second sentence does not tell a human that a mob is standing in the cell. So the state
     * records what it is waiting on and the note wins whenever there is one.
     */
    @Test
    public void aStateThatLeftANoteIsExplainedByTheNote() {
        String reason = CellExecutor.impatience(CellExecutor.State.GATE, 10_000L, 10L, placement(), 3,
                "ENTITY_IN_CELL");
        assertNotNull(reason);
        assertTrue("the cause, not the symptom: " + reason, reason.contains("ENTITY_IN_CELL"));
        assertTrue("and still the state and the tick count", reason.contains("GATE") && reason.contains("10000"));
        assertFalse("the generic sentence is replaced, not appended",
                reason.contains("the gate never opened"));
    }

    /** The approach's own sentence still names the point and the tolerance, for the branches that leave no note. */
    @Test
    public void theApproachSentenceStillNamesThePointAndTheTolerance() {
        String reason = CellExecutor.impatience(CellExecutor.State.APPROACH, 10_000L, 10L, placement(), 3, null);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("approach point"));
        assertTrue(reason, reason.contains(BuildAction.describePoint(APPROACH)));
        assertTrue(reason, reason.contains(BuildAction.describeTolerance(FineApproach.TOLERANCE)));
    }

    // ---------------------------------------------------------------------------- the loop no state clock sees

    /**
     * Two states handing one action back and forth for ever, with neither of them ever stuck.
     *
     * <p>This is the case that made the per-action clock necessary, and it is not hypothetical: it is APPROACH and
     * TRAVEL, and a body oscillating on the stance cell's boundary rides it with both state counters permanently at
     * zero. The simulation below hops every tick, so {@code ticksInState} never exceeds one, and asserts that the run
     * still ends.
     */
    @Test
    public void statesPassingAnActionBackAndForthStillDiverge() {
        BuildAction action = placement();
        CellExecutor.State[] loop = {CellExecutor.State.APPROACH, CellExecutor.State.TRAVEL};
        String reason = null;
        long tick = 0L;
        for (; tick < 100_000L && reason == null; tick++) {
            // ticksInState is 1 every tick: each hop resets the state clock, which is exactly what enter() does.
            reason = CellExecutor.impatience(loop[(int) (tick % 2)], 1L, tick, action, 11, null);
        }
        assertNotNull("a loop that resets every state counter must still end", reason);
        assertTrue("it ends on the per-action clock and says so, got: " + reason,
                reason.contains("handing it back and forth"));
        assertTrue("and it ends in minutes, not in the 44 000 ticks the last run spent: " + tick, tick < 12_000L);
    }

    /** The per-action clock is what fired, so it must be bigger than every per-state one — otherwise it would
     *  pre-empt the precise sentences and every divergence would read the same. */
    @Test
    public void thePerActionCeilingIsLooserThanEveryPerStatePatience() {
        BuildAction action = placement();
        for (CellExecutor.State state : CellExecutor.State.values()) {
            int patience = CellExecutor.patienceTicks(state);
            if (patience == CellExecutor.TERMINAL_STATE) {
                continue;
            }
            String reason = CellExecutor.impatience(state, patience + 1L, patience + 1L, action, 2, null);
            assertNotNull(reason);
            assertTrue(state + " must diverge with its OWN sentence, not the per-action one: " + reason,
                    !reason.contains("handing it back and forth"));
        }
    }

    /** A swap carries no geometry at all, and a divergence sentence that throws while describing one is a crash in
     *  the guard that exists to prevent a hang. */
    @Test
    public void anActionWithNoGeometryStillGetsASentence() {
        BuildAction swap = new BuildAction.SwapHotbar(3, Items.STONE, 12, false, 3, 0);
        for (CellExecutor.State state : CellExecutor.State.values()) {
            if (CellExecutor.patienceTicks(state) == CellExecutor.TERMINAL_STATE) {
                continue;
            }
            String reason = CellExecutor.impatience(state, 100_000L, 100_000L, swap, 0, null);
            assertNotNull(state.name(), reason);
        }
    }

    // ------------------------------------------------------------------------- the runner's own unbounded waits

    /**
     * The runner's three waits with no ceiling now have one.
     *
     * <p>A drifted hotbar slot, a bot mid-meal and a throttled place helper are all conditions {@link ActionRunner}
     * cannot influence — it touches no inventory and cannot stop the bot chewing — so each was a wait that printed its
     * own reason once a tick and, left alone, printed it for ever. The executor's javadoc named the first of them as
     * "a wait with no ceiling" and that was the accurate description.
     */
    @Test
    public void theRunnerStopsWaitingOnSomethingItCannotChange() {
        BuildAction.Interact interact = interaction();
        ActionRunner runner = new ActionRunner(interact);
        ActionRunner.Directive last = null;
        for (long tick = 0; tick <= ActionRunner.WAIT_PATIENCE_TICKS + 2; tick++) {
            last = runner.tick(eatingForever(interact, tick));
            if (last instanceof ActionRunner.Directive.Blocked) {
                assertTrue("it must stop after its patience, not before: tick " + tick,
                        tick >= ActionRunner.WAIT_PATIENCE_TICKS);
                assertTrue("and name what it was waiting on",
                        ((ActionRunner.Directive.Blocked) last).reason().contains("eating"));
                return;
            }
            assertTrue("until then it waits", last instanceof ActionRunner.Directive.Wait);
        }
        throw new AssertionError("the runner waited past its own patience: " + last.describe());
    }

    /**
     * The ceiling is over CONSECUTIVE waits, and a run broken by anything else does not accumulate.
     *
     * <p>Without this half the fix would trade a hang for a false divergence: an interaction that waits out a bite of
     * food, turns its head, waits again and turns again is doing exactly what it should, and summing those waits over
     * the whole action would stop it. The alternation below runs for three times the patience and never approaches
     * it, because every second tick answers {@link ActionRunner.Directive.Aim} instead.
     */
    @Test
    public void aWaitBrokenByProgressDoesNotAccumulate() {
        BuildAction.Interact interact = interaction();
        ActionRunner runner = new ActionRunner(interact);
        for (long tick = 0; tick < ActionRunner.WAIT_PATIENCE_TICKS * 3L; tick++) {
            // Even: the ray is on the cell and the bot is eating — the wait it cannot influence.
            // Odd: the ray has slipped off the cell, so the runner is turning its head, which is progress.
            ActionRunner.Directive directive = runner.tick(tick % 2 == 0
                    ? eatingForever(interact, tick) : rayElsewhere(interact, tick));
            assertTrue("a run broken by progress must never be blocked, tick " + tick + ": " + directive.describe(),
                    !(directive instanceof ActionRunner.Directive.Blocked));
        }
    }

    // -------------------------------------------------------------------------------------------- fixtures

    private static final BlockPos STANCE = CELL.south();
    private static final Vec3 APPROACH = new Vec3(4.5D, 61.0D, -10.5D);
    private static final Vec3 AIM = new Vec3(4.5D, 61.5D, -11.02D);
    private static final Rotation ROTATION = new Rotation(0.0F, 8.0F);
    private static final Direction FACE = Direction.SOUTH;

    private static BuildAction placement() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        return new BuildAction.Place(new PlacementSolution(CELL, stone, STANCE, APPROACH, CELL.north(),
                Direction.SOUTH, AIM, ROTATION, PlacementSolution.UNCONSTRAINED_MARGIN, stone, Items.STONE),
                true, 2, 0);
    }

    /** A repeater at delay 1 that the plan wants at delay 3 — two clicks of honest work for the runner to wait
     *  about. */
    private static BuildAction.Interact interaction() {
        return new BuildAction.Interact(CELL, STANCE, APPROACH, AIM, ROTATION, FACE, 2, delay(3), false,
                HotbarSchedule.PICKAXE_SLOT, 0);
    }

    private static BlockState delay(int ticks) {
        return Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, ticks);
    }

    /** The bot is eating and never stops — the wait the runner cannot act on. */
    private static ActionRunner.Observation eatingForever(BuildAction.Interact interact, long tick) {
        return observation(interact, tick, true);
    }

    /** The head has slipped off the cell, so the runner is turning it back — progress, and not a wait. */
    private static ActionRunner.Observation rayElsewhere(BuildAction.Interact interact, long tick) {
        return observation(interact, tick, false, interact.cell().above().above());
    }

    private static ActionRunner.Observation observation(BuildAction.Interact interact, long tick,
                                                        boolean consuming) {
        return observation(interact, tick, consuming, interact.cell());
    }

    private static ActionRunner.Observation observation(BuildAction.Interact interact, long tick,
                                                        boolean consuming, BlockPos rayHit) {
        return new ActionRunner.Observation(
                tick,
                delay(1),
                ROTATION,
                ROTATION,
                rayHit,
                FACE,
                interact.handSlot(),
                Items.STICK,
                false,
                consuming,
                ActionRunner.ScreenState.NONE);
    }
}
