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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The seam between {@link CellExecutor} and {@link ActionRunner}: who is handed which tick, what an open screen stops,
 * how a directive maps back onto the executor's own states, and how many clicks the whole thing actually sends.
 *
 * <p>Everything asserted here used to be a comment. {@code CellExecutor} answered {@code INTERACT} and
 * {@code FILL_FLUID} with {@code BLOCKED} at its click gate and nothing ever constructed an {@code ActionRunner}, so
 * {@code PlannedBuilderProcess} carried a RUNNABLE PREFIX that stopped the frozen order at the first of them — 32
 * repeater interactions costing 3489 placements on etz-basalt. The prefix is gone; these are the rules that replaced
 * it.
 *
 * <h2>What needs a live client and is not faked here</h2>
 *
 * <p>A fake of any of these would test the fake, so they are named instead:
 *
 * <ul>
 *   <li><b>That a forced {@code CLICK_RIGHT} with no armed expectation reaches {@code processRightClickBlock} and
 *       steps the repeater.</b> {@code BlockPlaceHelper.tick} reads {@code ctx.objectMouseOver()} and calls the
 *       controller; there is no controller under JUnit.</li>
 *   <li><b>That {@code expectMainHandPlacement} being left UNARMED is what makes an interaction click survive.</b>
 *       The reasoning is in {@code fireInteraction}: the guard demands {@code hit.relative(face) == target}, which a
 *       repeater step cannot satisfy. It is a claim about vanilla's helper, verifiable only against it.</li>
 *   <li><b>That typing into {@code AbstractSignEditScreen} produces {@code ServerboundSignUpdatePacket}.</b> The path
 *       is the screen's own {@code charTyped(CharacterEvent)} / {@code keyPressed(KeyEvent)} and then vanilla's
 *       {@code removed()}. Needs a screen, therefore a client.</li>
 *   <li><b>That the accessor mixin names the open editor's sign.</b> A mixin is not loaded under JUnit at all, which
 *       is exactly why {@code screenState} reports a mixin-less editor as an ANONYMOUS editor rather than as some
 *       other screen: the first is refused by name, the second would be closed and the text lost.</li>
 *   <li><b>That releasing sneak between {@code APPROACH} and the interaction click leaves the body where the proof
 *       put it.</b> Standing up changes the eye by 0.35 blocks and nothing else; the runner's live-ray gate is what
 *       actually decides, and that gate needs a real {@code level.clip()}.</li>
 *   <li><b>{@code ServerAck}'s sign evidence.</b> Pinned in {@link ServerAckTest} against packets, not here.</li>
 * </ul>
 */
public class CellExecutorDelegationTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos CELL = new BlockPos(75, -59, 105);
    private static final BlockPos STANCE = CELL.south();
    private static final Vec3 APPROACH = new Vec3(75.5D, -59.0D, 105.5D);
    private static final Vec3 AIM = new Vec3(75.5D, -58.9D, 105.98D);
    private static final Rotation LOOK = new Rotation(180.0F, 20.0F);
    private static final int LAYER = -59;

    // -------------------------------------------------------------------------------------------------- routing

    /**
     * The runner takes over at {@code AIM}, and that boundary is the fix for the worst open path in the engine.
     *
     * <p>{@code WRITE_SIGN} carries no rotation, so {@code CellExecutor.aim} answers "the action carries no rotation"
     * and DIVERGES. A divergence re-plans — and by then the sign BLOCK is standing, so the cell reads as satisfied,
     * the new plan emits nothing for it, and the text is lost with the build reporting success. Routing before
     * {@code AIM} makes that branch unreachable for every kind the runner owns.
     */
    @Test
    public void theRunnerTakesOverAtAimAndNotOneStateLater() {
        assertTrue(CellExecutor.delegated(CellExecutor.State.AIM));
        assertTrue(CellExecutor.delegated(CellExecutor.State.GATE));
        assertTrue(CellExecutor.delegated(CellExecutor.State.CLICK));
        assertTrue(CellExecutor.delegated(CellExecutor.State.CONFIRM));

        // The hotbar, the path and the exact sub-block approach point are the executor's for every action alike; the
        // runner touches no ctx and could not walk anywhere if it wanted to.
        assertFalse(CellExecutor.delegated(CellExecutor.State.SELECT));
        assertFalse(CellExecutor.delegated(CellExecutor.State.EQUIP));
        assertFalse(CellExecutor.delegated(CellExecutor.State.TRAVEL));
        assertFalse(CellExecutor.delegated(CellExecutor.State.APPROACH));
        assertFalse(CellExecutor.delegated(CellExecutor.State.DONE));
        assertFalse(CellExecutor.delegated(CellExecutor.State.DIVERGED));
        assertFalse(CellExecutor.delegated(CellExecutor.State.BLOCKED));
    }

    /**
     * An open screen stops the build — except for the two cases where stopping is a deadlock.
     *
     * <p>A sign placement's own {@code CONFIRM} runs UNDER the editor the server opened for it, so a guard that
     * stopped {@code CONFIRM} would freeze the placement whose retirement is the only way the {@code WRITE_SIGN}
     * behind it is ever reached. And an action the runner owns is never stopped because its whole tick is a decision
     * about the screen: the sign types into it, the others CLOSE it — standing still instead is the 1800-tick stall,
     * because {@code InventoryBehavior.onTick} bails while a container is up and no hotbar swap is ever granted
     * again.
     */
    @Test
    public void onlyTheActionThatOwnsAScreenMayWaitOnIt() {
        BuildAction placement = place(Blocks.STONE.defaultBlockState());

        // The defect this replaces: an ordinary placement owns no screen, so it must never WAIT on one -- nobody else
        // is going to close it. The old rule blocked here in every state but CONFIRM, and a basalt run stopped after
        // four blocks with a finished path standing ready and velocity exactly zero.
        assertFalse("a placement must close a stray screen, not wait on it",
                CellExecutor.screenBlocks(placement, null));
        assertFalse("still true when some unrelated action follows",
                CellExecutor.screenBlocks(placement, place(Blocks.STONE.defaultBlockState())));

        // The one exception, and it is about the NEXT action rather than the current state: a sign's editor opens on
        // placement and the WRITE_SIGN behind it is what fills it. Closing that screen throws away the one its own
        // follow-up is walking toward -- and a sign whose text never arrives still STANDS, so the cell reads as
        // satisfied and the loss is silent.
        assertTrue("the editor queued for the sign behind this placement must be left alone",
                CellExecutor.screenBlocks(placement, writeSign(List.of("a"))));

        // Actions the runner owns answer their own screens.
        assertTrue(CellExecutor.screenBlocks(writeSign(List.of("a")), null));
        assertTrue(CellExecutor.screenBlocks(interact(delay(1), delay(2), 1), null));
        assertTrue(CellExecutor.screenBlocks(fillFluid(), null));
    }

    /** Every directive maps onto a state the trace already knows how to print, so {@code act=} keeps meaning the same
     *  thing whichever half of the seam produced the tick. */
    @Test
    public void everyDirectiveHasAnHonestStateForTheTrace() {
        assertEquals(CellExecutor.State.AIM,
                CellExecutor.stateFor(new ActionRunner.Directive.Aim(LOOK, false, "turning")));
        assertEquals(CellExecutor.State.GATE,
                CellExecutor.stateFor(new ActionRunner.Directive.Wait(false, "throttled")));
        assertEquals(CellExecutor.State.GATE,
                CellExecutor.stateFor(new ActionRunner.Directive.CloseScreen("a chest opened")));
        assertEquals(CellExecutor.State.CLICK,
                CellExecutor.stateFor(new ActionRunner.Directive.ClickRight(0, false, false, "step")));
        assertEquals("typing is this action's click", CellExecutor.State.CLICK,
                CellExecutor.stateFor(new ActionRunner.Directive.TypeSign(CELL, List.of("a"), true)));
        assertEquals(CellExecutor.State.CONFIRM,
                CellExecutor.stateFor(new ActionRunner.Directive.Finished("done")));
        assertEquals(CellExecutor.State.BLOCKED,
                CellExecutor.stateFor(new ActionRunner.Directive.Blocked("no")));
    }

    // ------------------------------------------------------------------------------------------- the proof pose

    /**
     * The eye a precondition re-checks a ray from has to be the eye the plan proved it from.
     *
     * <p>{@code OrderPlanner} asks {@code BreakPlanner.aim} for {@link PlayerPose#STANDING} on an interaction and for
     * {@link PlayerPose#CROUCHED} on everything else, because vanilla forces the interaction to stand — a crouched
     * right-click on a repeater PLACES the held block instead of stepping the delay. Re-checking that ray from the
     * crouched eye moves its origin by 0.35 blocks and reports a divergence that is arithmetic rather than a changed
     * world; the run would then re-plan its way around a repeater that was never unreachable.
     *
     * <p>Keyed on the KIND and never on {@code sneak}, because the two disagree exactly once: a paired chest is
     * PLACED standing so vanilla does not force it to SINGLE, but its geometry was still proven crouched like every
     * other placement.
     */
    @Test
    public void onlyTheInteractionIsRecheckedFromTheStandingEye() {
        assertSame(PlayerPose.STANDING, CellExecutor.proofPose(interact(delay(1), delay(2), 1)));
        assertSame(PlayerPose.CROUCHED, CellExecutor.proofPose(fillFluid()));
        assertSame(PlayerPose.CROUCHED, CellExecutor.proofPose(writeSign(List.of("a"))));
        assertSame(PlayerPose.CROUCHED, CellExecutor.proofPose(place(Blocks.COBBLESTONE.defaultBlockState())));

        BuildAction chest = place(Blocks.CHEST.defaultBlockState());
        assertFalse("a chest is placed standing so vanilla does not force it to SINGLE", chest.sneak());
        assertSame("and its geometry was still proven from the crouched eye",
                PlayerPose.CROUCHED, CellExecutor.proofPose(chest));

        assertEquals(0.35D, PlayerPose.STANDING.eyeAt(Vec3.ZERO).y - PlayerPose.CROUCHED.eyeAt(Vec3.ZERO).y, 1.0E-9D);
    }

    /**
     * A bucket's ray target is its solid NEIGHBOUR, never its destination.
     *
     * <p>The destination is empty by definition — that is what the bucket is for — so a precondition that re-cast the
     * sight line at the destination would find it terminating on the very block the plan means to click and report
     * the ray as blocked, before the bot had taken a step.
     */
    @Test
    public void theBucketRayAimsAtTheNeighbourAndEverythingElseAtItsOwnCell() {
        BuildAction.FillFluid fluid = fillFluid();
        assertEquals(CELL.below(), ActionRunner.rayTargetOf(fluid));
        assertEquals(CELL, ActionRunner.rayTargetOf(interact(delay(1), delay(2), 1)));
        assertEquals(CELL, ActionRunner.rayTargetOf(place(Blocks.STONE.defaultBlockState())));
        assertSame("a sign's text is typed, not aimed at", null,
                ActionRunner.rayTargetOf(writeSign(List.of("a"))));
    }

    // ----------------------------------------------------------------------------------- sneak and hand per kind

    /**
     * The posture table, total over the eight kinds, because every entry in it is a silent failure when wrong.
     *
     * <p>A crouched right-click on a repeater places the held block instead of stepping the delay; sneak forces a
     * planned double chest to two singles; a standing bucket aimed at a chest opens the chest and freezes every
     * future hotbar swap. None of those throws, none logs, and all of them pass a "did a block get placed here"
     * check.
     */
    @Test
    public void theSneakAndHandDecisionIsTotalOverEveryActionKind() {
        assertPosture(interact(delay(1), delay(3), 2), false, HotbarSchedule.PICKAXE_SLOT,
                ActionRunner.Button.RIGHT, 2);
        assertPosture(fillFluid(), true, 3, ActionRunner.Button.RIGHT, 1);
        assertPosture(writeSign(List.of("a")), false, HotbarSchedule.PICKAXE_SLOT, ActionRunner.Button.NONE, 0);
        assertPosture(place(Blocks.COBBLESTONE.defaultBlockState()), true, 2, ActionRunner.Button.RIGHT, 1);
        assertPosture(place(Blocks.CHEST.defaultBlockState()), false, 2, ActionRunner.Button.RIGHT, 1);
        assertPosture(scaffold(), true, HotbarSchedule.THROWAWAY_SLOT, ActionRunner.Button.RIGHT, 1);
        assertPosture(breakAction(), true, HotbarSchedule.PICKAXE_SLOT, ActionRunner.Button.LEFT,
                ActionRunner.Posture.HELD);
        assertPosture(removeScaffold(), true, HotbarSchedule.PICKAXE_SLOT, ActionRunner.Button.LEFT,
                ActionRunner.Posture.HELD);
        assertPosture(new BuildAction.SwapHotbar(4, Items.COBBLESTONE, 14, false, 4, LAYER), false, 4,
                ActionRunner.Button.NONE, 0);

        // And the plan says the same thing the rule does, for every one of them, before the bot moves.
        assertTrue(ActionRunner.postureViolations(List.of(
                interact(delay(1), delay(3), 2), fillFluid(), writeSign(List.of("a")), scaffold(), breakAction(),
                removeScaffold(), place(Blocks.CHEST.defaultBlockState()))).isEmpty());
    }

    // ------------------------------------------------------------------------------------------- click counting

    /**
     * Three delay steps, three clicks, and not a fourth.
     *
     * <p>A repeater wraps 4 back to 1, so one click too many is not a small error — it is three more clicks, and the
     * run would look like it was working the whole time. The count is re-derived from the LIVE state on every tick
     * rather than decremented, which is also what makes a swallowed click cost nothing.
     */
    @Test
    public void aThreeStepInteractionSendsExactlyThreeClicks() {
        assertEquals(3, PlacementGeometry.interactionClicks(delay(1), delay(4)));
        Steps run = drive(interact(delay(1), delay(4), 3), delay(1), CellExecutorDelegationTest::stepRepeater);
        assertEquals("one click per confirmed step, no more", 3, run.clicks());
        assertTrue(run.finished());
    }

    /** One bucket, one click. The empty destination is confirmed by the FLUID that appears in it and never by the
     *  click having been sent. */
    @Test
    public void aFluidCellIsFilledWithExactlyOneClick() {
        Steps run = drive(fillFluid(), Blocks.AIR.defaultBlockState(),
                state -> Blocks.WATER.defaultBlockState());
        assertEquals(1, run.clicks());
        assertTrue(run.finished());
    }

    /** A wrap-around is still one click per step: 23 to 1 on a note block is three, never twenty-two. */
    @Test
    public void aWrappingInteractionStillSendsOneClickPerStep() {
        BlockState from = Blocks.NOTE_BLOCK.defaultBlockState()
                .setValue(net.minecraft.world.level.block.NoteBlock.NOTE, 23);
        BlockState to = Blocks.NOTE_BLOCK.defaultBlockState()
                .setValue(net.minecraft.world.level.block.NoteBlock.NOTE, 1);
        assertEquals(3, PlacementGeometry.interactionClicks(from, to));

        Steps run = drive(new BuildAction.Interact(CELL, STANCE, APPROACH, AIM, LOOK, Direction.NORTH, 3, to, false,
                HotbarSchedule.PICKAXE_SLOT, LAYER), from, CellExecutorDelegationTest::stepNote);
        assertEquals(3, run.clicks());
        assertTrue(run.finished());
    }

    /** Typing a sign presses nothing at the world. Its confirmation is the editor closing, which is why the executor
     *  makes it the one retirement that also waits for the server's own answer. */
    @Test
    public void writingASignSendsNoClickAtTheWorld() {
        BuildAction.WriteSign sign = writeSign(List.of("first", "second"));
        ActionRunner runner = new ActionRunner(sign);
        BlockState placed = Blocks.OAK_SIGN.defaultBlockState();
        ActionRunner.Directive typed = runner.tick(
                observation(sign, placed, 0, ActionRunner.ScreenState.signEditor(CELL, true)));
        assertTrue(typed.describe(), typed instanceof ActionRunner.Directive.TypeSign);
        assertEquals(CellExecutor.State.CLICK, CellExecutor.stateFor(typed));

        ActionRunner.Directive done = runner.tick(
                observation(sign, placed, 1, ActionRunner.ScreenState.NONE));
        assertTrue(done.describe(), done instanceof ActionRunner.Directive.Finished);
    }

    // ---------------------------------------------------------------------------------------------- sign lines

    /** Four lines, blanks included, because that is what the editor will send and therefore what the acknowledgement
     *  has to be asked to confirm. A shorter planned list means blank remainder lines and never "leave as is". */
    @Test
    public void aSignAlwaysSendsFourLines() {
        assertEquals(List.of("a", "b", "", ""), CellExecutor.signLines(List.of("a", "b")));
        assertEquals(List.of("", "", "", ""), CellExecutor.signLines(List.of()));
        assertEquals(List.of("a", "b", "c", "d"), CellExecutor.signLines(List.of("a", "b", "c", "d")));
        assertEquals(SignNbt.LINES, CellExecutor.signLines(List.of("only one")).size());
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    /** How far one run of the runner got: how many clicks it asked the world for, and whether it ended confirmed. */
    private record Steps(int clicks, boolean finished) {
    }

    /** One tick of a very simple server: a click that went out last tick has landed by this one. */
    @FunctionalInterface
    private interface ServerStep {

        BlockState after(BlockState clicked);
    }

    /**
     * Drive one runner to settlement against a world that answers each click on the following tick.
     *
     * <p>Deliberately no throttling, no screens and no reverts — those have their own tests. What this measures is
     * the one number a wrapping state makes expensive to get wrong: how many clicks the whole action costs.
     */
    private static Steps drive(BuildAction action, BlockState initial, ServerStep server) {
        ActionRunner runner = new ActionRunner(action);
        BlockState live = initial;
        boolean clickPending = false;
        int clicks = 0;
        ActionRunner.Directive last = null;
        for (long tick = 0; tick < 200 && !runner.settled(); tick++) {
            if (clickPending) {
                live = server.after(live);
                clickPending = false;
            }
            last = runner.tick(observation(action, live, tick, ActionRunner.ScreenState.NONE));
            if (last instanceof ActionRunner.Directive.ClickRight) {
                clicks++;
                clickPending = true;
            }
        }
        assertNotNull(last);
        return new Steps(clicks, last instanceof ActionRunner.Directive.Finished);
    }

    private static BlockState stepRepeater(BlockState live) {
        return live.setValue(RepeaterBlock.DELAY, (live.getValue(RepeaterBlock.DELAY) & 3) + 1);
    }

    private static BlockState stepNote(BlockState live) {
        return live.setValue(net.minecraft.world.level.block.NoteBlock.NOTE,
                (live.getValue(net.minecraft.world.level.block.NoteBlock.NOTE) + 1) % 25);
    }

    /**
     * The common tick: head settled on the steered angle, live ray on the action's own ray target and face, the slot
     * the posture rule demands, the hand that action requires, nothing throttled.
     *
     * <p>Every field is derived from the action rather than fixed, and the bucket is why: its ray target is the solid
     * NEIGHBOUR and not its own empty cell, and its hand is a filled bucket where an interaction's is a pickaxe. A
     * hard-coded observation would silently test the interaction twice.
     */
    private static ActionRunner.Observation observation(BuildAction action, BlockState live, long tick,
                                                        ActionRunner.ScreenState screen) {
        ActionRunner.Posture posture = ActionRunner.postureFor(action);
        Item hand = action instanceof BuildAction.FillFluid fluid ? fluid.bucket() : Items.STONE_PICKAXE;
        return new ActionRunner.Observation(tick, live, LOOK, LOOK, ActionRunner.rayTargetOf(action),
                ActionRunner.faceOf(action), posture.handSlot(), hand, false, false, screen);
    }

    private static void assertPosture(BuildAction action, boolean sneak, int handSlot, ActionRunner.Button button,
                                      int clicks) {
        ActionRunner.Posture posture = ActionRunner.postureFor(action);
        assertEquals(action.kind() + " sneak", sneak, posture.sneak());
        assertEquals(action.kind() + " hand slot", handSlot, posture.handSlot());
        assertEquals(action.kind() + " button", button, posture.button());
        assertEquals(action.kind() + " clicks", clicks, posture.clicks());
    }

    private static BlockState delay(int ticks) {
        return Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, ticks);
    }

    private static PlacementSolution solution(BlockState desired) {
        return new PlacementSolution(CELL, desired, STANCE, APPROACH, CELL.below(), Direction.UP, AIM, LOOK,
                PlacementSolution.UNCONSTRAINED_MARGIN, desired, desired.getBlock().asItem());
    }

    private static BuildAction.Place place(BlockState desired) {
        boolean sneak = ActionRunner.postureFor(new BuildAction.Place(solution(desired), false, 2, LAYER)).sneak();
        return new BuildAction.Place(solution(desired), sneak, 2, LAYER);
    }

    private static BuildAction.PlaceScaffold scaffold() {
        return new BuildAction.PlaceScaffold(solution(Blocks.DIRT.defaultBlockState()), CELL.above(), true,
                HotbarSchedule.THROWAWAY_SLOT, LAYER);
    }

    private static BuildAction.Break breakAction() {
        return new BuildAction.Break(CELL, STANCE, APPROACH, AIM, LOOK, Direction.UP,
                Blocks.COBBLESTONE.defaultBlockState(), true, true, HotbarSchedule.PICKAXE_SLOT, LAYER);
    }

    private static BuildAction.RemoveScaffold removeScaffold() {
        return new BuildAction.RemoveScaffold(CELL, STANCE, APPROACH, AIM, LOOK,
                Blocks.DIRT.defaultBlockState(), true, HotbarSchedule.PICKAXE_SLOT, LAYER);
    }

    private static BuildAction.Interact interact(BlockState from, BlockState to, int clicks) {
        assertEquals("the fixture must ask for the number of clicks the geometry says", clicks,
                PlacementGeometry.interactionClicks(from, to));
        return new BuildAction.Interact(CELL, STANCE, APPROACH, AIM, LOOK, Direction.UP, clicks, to, false,
                HotbarSchedule.PICKAXE_SLOT, LAYER);
    }

    private static BuildAction.WriteSign writeSign(List<String> lines) {
        return new BuildAction.WriteSign(CELL, lines, true, false, HotbarSchedule.PICKAXE_SLOT, LAYER);
    }

    private static BuildAction.FillFluid fillFluid() {
        Item bucket = Items.WATER_BUCKET;
        return new BuildAction.FillFluid(CELL, CELL.below(), STANCE, APPROACH, AIM, LOOK, Direction.UP, bucket,
                Blocks.WATER.defaultBlockState(), true, 3, LAYER);
    }
}
