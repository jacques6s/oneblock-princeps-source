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

import princeps.api.utils.Rotation;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The actions beyond placing, tested where they can be tested: the posture rules, the click arithmetic, the aim gate
 * and every refusal.
 *
 * <p>These are the failures that produce a build which LOOKS finished — a repeater at the wrong delay, a blank sign,
 * a helper block still standing, two single chests where a double one was drawn. None of them throws, none of them
 * logs, and all of them pass a "did a block get placed here" check, which is why they are worth a test each rather
 * than a play session.
 *
 * <h2>What is NOT covered here, and why</h2>
 *
 * <p>Everything below the {@link ActionRunner.Directive} boundary needs a live client and is stated rather than
 * faked, because a fake of it would test the fake:
 *
 * <ul>
 *   <li><b>That forcing {@code CLICK_RIGHT} actually reaches {@code processRightClickBlock}</b>, and that arming
 *       {@code expectMainHandPlacement} in the same {@code onTick} survives {@code BlockPlaceHelper}'s read-and-null.
 *       The runner guarantees the two travel in one object; that the executor honours it is an in-game check.</li>
 *   <li><b>That {@code CLICK_LEFT} breaks the cell the crosshair is on and not a neighbour.</b> The ray is an
 *       observation here; the real one is {@code ctx.objectMouseOver()}, a fresh {@code level.clip()} per call.</li>
 *   <li><b>That typing into {@code AbstractSignEditScreen} produces the {@code ServerboundSignUpdatePacket}.</b> The
 *       path is the screen's own {@code charTyped} / {@code keyPressed} / {@code onClose}; the packet is built by
 *       vanilla's {@code removed()}. Needs a screen, therefore a client.</li>
 *   <li><b>That the accessor mixin reports the right sign.</b> A mixin is not loaded under JUnit at all.</li>
 *   <li><b>Timing against the real throttles.</b> {@code isThrottled()} is non-deterministic under humanised look —
 *       {@code max(1, base + round(g*1.5))} with {@code g} a sum of three uniforms — which is exactly why the runner
 *       polls it instead of counting ticks, and why a tick-count assertion here would be asserting a number that
 *       does not exist.</li>
 * </ul>
 */
public class ActionRunnerTest {

    /** Real vanilla block states, headless. Nothing else in the game works after this, and nothing else is needed. */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos CELL = new BlockPos(10, 64, -20);
    private static final BlockPos STANCE = new BlockPos(10, 64, -19);
    private static final Vec3 APPROACH = new Vec3(10.5D, 64.0D, -19.5D);
    private static final Vec3 AIM = new Vec3(10.5D, 64.5D, -19.98D);
    private static final Rotation LOOK = new Rotation(180.0F, 12.0F);
    private static final BlockPos FLUID_AGAINST = CELL.below();
    private static final Direction FLUID_FACE = Direction.UP;
    private static final int LAYER = 64;

    // ------------------------------------------------------------------------------------- posture: sneak and hand

    /**
     * The rule that a wrongly filled field cannot express: an interaction stands up and holds a hand that cannot
     * place. A crouched right-click does not step a repeater, it puts the held block down on it.
     */
    @Test
    public void anInteractionStandsUpAndHoldsANonPlacingHand() {
        ActionRunner.Posture posture = ActionRunner.postureFor(interact(delay(1), delay(3), 2));
        assertFalse("a crouched right-click places the held block instead of stepping the state", posture.sneak());
        assertEquals("the hand that cannot place", HotbarSchedule.PICKAXE_SLOT, posture.handSlot());
        assertEquals(ActionRunner.Button.RIGHT, posture.button());
        assertEquals("one confirmation per planned click", 2, posture.clicks());
    }

    /** The constructor is the second line of defence and refuses the combination outright. */
    @Test
    public void aSneakingInteractionCannotBeConstructed() {
        try {
            new BuildAction.Interact(CELL, STANCE, APPROACH, AIM, LOOK, Direction.NORTH, 1, delay(2), true,
                    HotbarSchedule.PICKAXE_SLOT, LAYER);
            fail("a sneaking interaction must not construct");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("must not sneak"));
        }
    }

    /** Sneak forces {@code ChestBlock.getStateForPlacement} to SINGLE, so a planned double chest is placed standing
     *  while everything around it crouches. */
    @Test
    public void aChestIsPlacedStandingAndEverythingElseSneaks() {
        assertFalse("sneak would force SINGLE and build two single chests",
                ActionRunner.postureFor(place(Blocks.CHEST.defaultBlockState())).sneak());
        assertFalse("a trapped chest pairs by the same rule",
                ActionRunner.postureFor(place(Blocks.TRAPPED_CHEST.defaultBlockState())).sneak());
        assertTrue("an ender chest has no TYPE and does not pair",
                ActionRunner.postureFor(place(Blocks.ENDER_CHEST.defaultBlockState())).sneak());
        assertTrue(ActionRunner.postureFor(place(Blocks.COBBLESTONE.defaultBlockState())).sneak());
    }

    /**
     * A bucket sneaks, and this is the one people get backwards. {@code BucketItem} empties through {@code Item.use},
     * which vanilla reaches only after the clicked block's own {@code useItemOn} has declined — so a standing bucket
     * aimed at a chest opens the chest, and an open container freezes every future hotbar swap.
     */
    @Test
    public void aBucketSneaksSoTheClickedBlockDoesNotUseItself() {
        ActionRunner.Posture posture = ActionRunner.postureFor(fillFluid(Items.WATER_BUCKET, 3));
        assertTrue("standing, the clicked block's own use fires first", posture.sneak());
        assertEquals(ActionRunner.Button.RIGHT, posture.button());
        assertEquals(3, posture.handSlot());
    }

    /**
     * A break holds the button rather than counting clicks, holds the pickaxe, and CROUCHES — not because sneak does
     * anything to breaking, but because {@code BreakPlanner.aim} proves its geometry from the crouched eye at 1.27
     * and a standing click casts from 1.62. The stance a proof was written against is part of the proof.
     */
    @Test
    public void aScaffoldRemovalHoldsTheButtonAndKeepsTheStanceItsAimWasProvenIn() {
        ActionRunner.Posture posture = ActionRunner.postureFor(removeScaffold(Blocks.DIRT.defaultBlockState()));
        assertEquals(ActionRunner.Button.LEFT, posture.button());
        assertEquals(HotbarSchedule.PICKAXE_SLOT, posture.handSlot());
        assertTrue("a break is held until the world changes, not pressed a fixed number of times", posture.isHeld());
        assertTrue("the aim was proven from the crouched eye", posture.sneak());
    }

    /**
     * The pose split, pinned. Every action in the engine is proven crouched and clicked crouched EXCEPT the
     * interaction, which vanilla forces to stand — so it is proven standing too. Mixing the two moves the hit point
     * by 0.35 blocks of eye height, which over a four-block reach is far more than the margins the design rests on,
     * and it compiles, runs and misses.
     */
    @Test
    public void theInteractionIsTheOnlyActionProvenFromTheStandingEye() {
        assertFalse("vanilla leaves no choice: a crouched right-click places instead of steps",
                ActionRunner.postureFor(interact(delay(1), delay(2), 1)).sneak());
        assertTrue(ActionRunner.postureFor(removeScaffold(Blocks.DIRT.defaultBlockState())).sneak());
        assertTrue(ActionRunner.postureFor(place(Blocks.COBBLESTONE.defaultBlockState())).sneak());

        Vec3 feet = new Vec3(0.5D, 64.0D, 0.5D);
        assertEquals(64.0D + 1.27D, PlayerPose.CROUCHED.eyeAt(feet).y, 1.0E-9D);
        assertEquals(64.0D + 1.62D, PlayerPose.STANDING.eyeAt(feet).y, 1.0E-9D);
    }

    /** Writing a sign presses nothing at the world: the screen is already open and the lines go in through it. */
    @Test
    public void writingASignClicksNothing() {
        ActionRunner.Posture posture = ActionRunner.postureFor(writeSign(List.of("a", "b", "", "")));
        assertEquals(ActionRunner.Button.NONE, posture.button());
        assertEquals(0, posture.clicks());
    }

    /** A plan whose posture contradicts the rule is named at plan time, where it is cheap. */
    @Test
    public void aPostureViolationIsNamedRatherThanClicked() {
        BuildAction sneakingChest = new BuildAction.Place(
                solution(Blocks.CHEST.defaultBlockState()), true, 2, LAYER);
        String violation = ActionRunner.postureViolation(sneakingChest);
        assertNotNull("a crouched chest placement must be reported", violation);
        assertTrue(violation, violation.contains("STANDING"));
        assertEquals(1, ActionRunner.postureViolations(List.of(sneakingChest, place(
                Blocks.COBBLESTONE.defaultBlockState()))).size());
        assertTrue("a consistent plan reports nothing",
                ActionRunner.postureViolations(List.of(place(Blocks.STONE.defaultBlockState()))).isEmpty());
    }

    /** A double chest placed standing does construct; the sneaking form does not. Both halves carry LEFT/RIGHT. */
    @Test
    public void aPairedChestRefusesToBeConstructedSneaking() {
        BlockState leftHalf = Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        try {
            new BuildAction.Place(solution(leftHalf), true, 2, LAYER);
            fail("a crouched click on a paired chest builds two singles");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("standing"));
        }
        assertFalse(ActionRunner.postureFor(new BuildAction.Place(solution(leftHalf), false, 2, LAYER)).sneak());
    }

    // ---------------------------------------------------------------------------------------- ownership boundary

    /** The runner refuses a placement rather than half-running it. Two owners of one click is the failure mode the
     *  whole engine exists to have removed. */
    @Test
    public void theRunnerRefusesTheActionsItDoesNotOwn() {
        assertTrue(ActionRunner.owns(interact(delay(1), delay(2), 1)));
        assertTrue(ActionRunner.owns(writeSign(List.of("x"))));
        assertTrue(ActionRunner.owns(fillFluid(Items.LAVA_BUCKET, 4)));
        assertFalse(ActionRunner.owns(place(Blocks.STONE.defaultBlockState())));
        try {
            new ActionRunner(place(Blocks.STONE.defaultBlockState()));
            fail("a placement is the executor's");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("PLACE"));
        }
    }

    /**
     * Exactly one owner for every kind, and the executor gets every break.
     *
     * <p>{@code REMOVE_SCAFFOLD} used to be claimed here AND by {@code CellExecutor.isBreak}, which is two owners of
     * {@code CLICK_LEFT}. The executor won on evidence rather than on seniority: a break there cannot start at all
     * while the packet-backed acknowledgement is unavailable, and this class has no acknowledgement seam, so routing
     * a destruction here would make server evidence optional for the one operation that cannot be undone.
     */
    @Test
    public void everyBreakBelongsToTheExecutorAndNothingIsOwnedTwice() {
        BuildAction.RemoveScaffold removal = removeScaffold(Blocks.DIRT.defaultBlockState());
        assertFalse("a scaffold removal is a break and every break is the executor's",
                ActionRunner.owns(removal));
        try {
            new ActionRunner(removal);
            fail("the runner must refuse the break it no longer owns");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("REMOVE_SCAFFOLD"));
        }
        // Every kind, so a ninth action type cannot slip in owned by nobody or owned by both.
        for (BuildAction.Kind kind : BuildAction.Kind.values()) {
            boolean expected = switch (kind) {
                case INTERACT, WRITE_SIGN, FILL_FLUID -> true;
                case PLACE, JUMP_PLACE, PLACE_SCAFFOLD, BREAK, REMOVE_SCAFFOLD, SWAP_HOTBAR -> false;
            };
            assertEquals(kind.name(), expected, ActionRunner.owns(actionOfKind(kind)));
        }
    }

    // ------------------------------------------------------------------------------------------- click arithmetic

    /**
     * A repeater's delay wraps 4 to 1, so a click too many is three more clicks. The runner sends one, waits for the
     * world to move, and re-derives the remaining count from the LIVE state — never from a decrementing counter.
     */
    @Test
    public void anInteractionSendsOneClickPerConfirmedStep() {
        BuildAction.Interact action = interact(delay(1), delay(3), 2);
        ActionRunner runner = new ActionRunner(action);
        BlockState live = delay(1);

        // Tick 0: aimed and unthrottled, so the first click goes out.
        ActionRunner.Directive first = runner.tick(aimedAt(live, 0));
        assertTrue(first.describe(), first instanceof ActionRunner.Directive.ClickRight);
        assertFalse("the click must not sneak", ((ActionRunner.Directive.ClickRight) first).sneak());

        // Tick 1: the world has not moved yet. A second click here would overshoot into the wrap.
        ActionRunner.Directive second = runner.tick(aimedAt(live, 1));
        assertTrue(second.describe(), second instanceof ActionRunner.Directive.Wait);
        assertEquals(0, runner.confirmedSteps());

        // Tick 2: delay stepped to 2. One step confirmed by the WORLD, and the second click goes out.
        live = delay(2);
        ActionRunner.Directive third = runner.tick(aimedAt(live, 2));
        assertEquals(1, runner.confirmedSteps());
        assertTrue(third.describe(), third instanceof ActionRunner.Directive.ClickRight);

        // Tick 3: delay 3 — the target. Now it must HOLD rather than believe the client's prediction.
        live = delay(3);
        assertTrue(runner.tick(aimedAt(live, 3)) instanceof ActionRunner.Directive.Wait);
        assertTrue(runner.tick(aimedAt(live, 4)) instanceof ActionRunner.Directive.Wait);
        ActionRunner.Directive done = runner.tick(aimedAt(live, 5));
        assertTrue(done.describe(), done instanceof ActionRunner.Directive.Finished);
        assertTrue(runner.settled());
    }

    /** Three ticks of the state simply continuing to be true is what distinguishes a placement from a placement the
     *  server is about to revert. A revert inside the window puts the runner back to work. */
    @Test
    public void aRevertInsideTheHoldWindowDoesNotCountAsDone() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        runner.tick(aimedAt(delay(1), 0));                       // click
        runner.tick(aimedAt(delay(2), 1));                       // confirmed, holding
        ActionRunner.Directive reverted = runner.tick(aimedAt(delay(1), 2));
        assertFalse("a reverted state is not a finished action", reverted instanceof ActionRunner.Directive.Finished);
        assertFalse(runner.settled());
    }

    /** A note block wraps 24 to 0, and the count is the wrap-around distance, never the difference. */
    @Test
    public void theNoteBlockWrapArithmeticIsTheOneTheRunnerUses() {
        BlockState from = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 23);
        BlockState to = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 1);
        assertEquals("23 -> 24 -> 0 -> 1", 3, PlacementGeometry.interactionClicks(from, to));
        assertEquals(3, ActionRunner.postureFor(interact(from, to, 3)).clicks());
    }

    /** A trapdoor is one click, and the runner confirms it against the world rather than against the click. */
    @Test
    public void aTrapdoorIsOneConfirmedClick() {
        BlockState closed = Blocks.OAK_TRAPDOOR.defaultBlockState();
        BlockState open = closed.setValue(TrapDoorBlock.OPEN, true);
        ActionRunner runner = new ActionRunner(interact(closed, open, 1));
        assertTrue(runner.tick(aimedAt(closed, 0)) instanceof ActionRunner.Directive.ClickRight);
        // Held for three, then finished.
        assertTrue(runner.tick(aimedAt(open, 1)) instanceof ActionRunner.Directive.Wait);
        assertTrue(runner.tick(aimedAt(open, 2)) instanceof ActionRunner.Directive.Wait);
        assertTrue(runner.tick(aimedAt(open, 3)) instanceof ActionRunner.Directive.Finished);
    }

    // ------------------------------------------------------------------------------------------------- the gates

    /** The aim gate is result equivalence against the LIVE ray, never angle equality: the rotation requested in tick
     *  N is not raycast-visible until N+1, so a click on the angle alone lands on a stale ray. */
    @Test
    public void noClickGoesOutBeforeTheLiveRayAgrees() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Observation stillTurning = observation(delay(1), 0)
                .withRotation(new Rotation(90.0F, 12.0F))
                .build();
        assertTrue(runner.tick(stillTurning) instanceof ActionRunner.Directive.Aim);

        ActionRunner.Observation settledButOffTarget = observation(delay(1), 1).withRayHit(false).build();
        assertTrue("the head has stopped, the ray has not arrived",
                runner.tick(settledButOffTarget) instanceof ActionRunner.Directive.Aim);

        // Right cell, wrong face. A repeater's own click face decides nothing, but a placement's decides everything,
        // and the gate is one rule for all of them rather than a per-action exception nobody maintains.
        ActionRunner.Observation wrongFace = observation(delay(1), 2).withRayFace(Direction.UP).build();
        assertTrue(runner.tick(wrongFace) instanceof ActionRunner.Directive.Aim);

        assertTrue(runner.tick(aimedAt(delay(1), 3)) instanceof ActionRunner.Directive.ClickRight);
    }

    /** A geometric proof can become unreachable after the world changes. Special actions therefore spend a bounded
     *  patience window aiming and then stop with the exact target, rather than looking at empty space forever. */
    @Test
    public void anAimThatNeverReachesItsTargetIsNamedAndBounded() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive last = null;
        long tick = 0;
        while (!runner.settled() && tick <= ActionRunner.AIM_PATIENCE_TICKS + 2L) {
            last = runner.tick(observation(delay(1), tick++).withRayHit(false).build());
        }

        assertNotNull(last);
        assertTrue(last.describe(), last instanceof ActionRunner.Directive.Blocked);
        assertTrue(last.describe(), last.describe().contains("did not reach"));
        assertTrue("the refusal must happen within the advertised consecutive patience window",
                tick <= ActionRunner.AIM_PATIENCE_TICKS + 1L);
    }

    /** The tolerance is the humanised one. {@code isReallyCloseTo}'s 0.01 degrees never fires under tremor, and a
     *  gate that never fires is an infinite stall with nothing in the log. */
    @Test
    public void theAimToleranceIsTheHumanisedOneAndNotTheExactOne() {
        Rotation drifted = new Rotation(LOOK.getYaw() + 0.4F, LOOK.getPitch() - 0.3F);
        assertFalse("this is precisely the drift isReallyCloseTo refuses", drifted.isReallyCloseTo(LOOK));
        assertTrue(drifted.isCloseTo(LOOK, ActionRunner.YAW_TOLERANCE, ActionRunner.PITCH_TOLERANCE));

        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        assertTrue("real tremor must not stall the gate",
                runner.tick(observation(delay(1), 0).withRotation(drifted).build())
                        instanceof ActionRunner.Directive.ClickRight);
    }

    /** A click sent while throttled is discarded in silence, which is why the runner waits for the throttle instead
     *  of pressing again. */
    @Test
    public void aThrottledHelperIsWaitedForRatherThanClickedAt() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive throttled = runner.tick(observation(delay(1), 0).withPlaceThrottled(true).build());
        assertTrue(throttled.describe(), throttled instanceof ActionRunner.Directive.Wait);
        assertTrue(throttled.describe(), throttled.describe().contains("throttled"));
    }

    /** While eating, {@code InputOverrideHandler} ticks both helpers with false — which does not skip the click, it
     *  eats anything pending along with it. */
    @Test
    public void nothingIsClickedWhileConsuming() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive eating = runner.tick(observation(delay(1), 0).withConsuming(true).build());
        assertTrue(eating.describe(), eating instanceof ActionRunner.Directive.Wait);
    }

    /**
     * One directive per tick is the structural defence against {@code CLICK_LEFT} clearing {@code CLICK_RIGHT} before
     * the helpers tick, which does not merely skip the placement but consumes its pending commit with it.
     *
     * <p>The type system is the proof and it got stronger, not weaker, when every break moved to the executor: a
     * {@link ActionRunner.Directive} is now one of SEVEN records and not one of them can even name the left button,
     * so the runner cannot ask for a break in the same tick as a click by any route at all.
     */
    @Test
    public void oneTickProducesExactlyOneDirectiveAndNeverALeftClick() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive only = runner.tick(aimedAt(delay(1), 0));
        assertTrue(only.describe(), only instanceof ActionRunner.Directive.ClickRight);
        assertEquals("no directive names the left button", 7,
                ActionRunner.Directive.class.getPermittedSubclasses().length);
    }

    /**
     * The gate follows the angle the executor is STEERING to, not the one the plan stored.
     *
     * <p>The executor re-derives the aim from the live eye toward the planned aim POINT, because the point on the
     * face is what was proven and the stored angle is only one way of reaching it from one particular eye. A "has the
     * head arrived" test against the stored angle would then wait for a number nothing is steering to: the head sits
     * still on the derived angle, the test never passes, no click is sent, and eighty ticks later the refusal blames
     * the ray.
     */
    @Test
    public void theHeadIsJudgedAgainstTheAngleTheExecutorIsActuallySteeringTo() {
        Rotation derived = new Rotation(LOOK.getYaw() + 4.0F, LOOK.getPitch() - 3.0F);
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive click = runner.tick(
                observation(delay(1), 0).withRotation(derived).withAimRotation(derived).build());
        assertTrue("the head is on the angle it was steered to and the live ray is on the target: " + click.describe(),
                click instanceof ActionRunner.Directive.ClickRight);

        // The stored angle remains the answer when nothing re-derived one.
        ActionRunner stored = new ActionRunner(interact(delay(1), delay(2), 1));
        assertTrue(stored.tick(observation(delay(1), 0).withRotation(derived).withAimRotation(null).build())
                instanceof ActionRunner.Directive.Aim);
    }

    // ------------------------------------------------------------------------------------------------- refusals

    /**
     * A block in hand does NOT stop an interaction, because vanilla decides between "use the block" and "place what I
     * am holding" by the CROUCH and not by the hand: an un-sneaked right-click on a repeater steps its delay whatever
     * the hand holds.
     *
     * <p>This used to be a fatal refusal, on the theory that a ray which had slipped one face off the target would
     * place the held block instead. requireAim is what actually answers that — the crosshair is re-derived from the
     * live ray and has to be on the intended face before any click goes out — so the hand rule was belt on top of
     * braces. It cost a full basalt run: 947 blocks in, 1080 repeaters left unset, because the bench stocks slot 0
     * with material and no pickaxe existed to switch to.
     */
    @Test
    public void anInteractionProceedsWithABlockInHandBecauseOnlyCrouchingPlaces() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive directive =
                runner.tick(observation(delay(1), 0).withHeld(Items.COBBLESTONE).build());

        assertFalse(directive.describe(), directive instanceof ActionRunner.Directive.Blocked);
        // And the half that DOES matter is asserted instead: a sneaking click would place the cobblestone, so an
        // interaction is emitted un-sneaked and that is now the whole of the safety rule.
        assertFalse("an interaction must never sneak, whatever it holds",
                interact(delay(1), delay(2), 1).sneak());
    }

    /** An iron trapdoor's {@code open} is unreachable by clicking, so a state that drifted into one is a stop and not
     *  a break-and-replace — the loop that broke and re-placed the same 40 trapdoors for a whole run. */
    @Test
    public void aStateThatCanNoLongerBeSteppedIsNamedRatherThanRetried() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive blocked = runner.tick(aimedAt(Blocks.COMPARATOR.defaultBlockState(), 0));
        assertTrue(blocked.describe(), blocked instanceof ActionRunner.Directive.Blocked);
        assertTrue(blocked.describe(), blocked.describe().contains("interaction-fixable"));

        BlockState ironClosed = Blocks.IRON_TRAPDOOR.defaultBlockState();
        BlockState ironOpen = ironClosed.setValue(TrapDoorBlock.OPEN, true);
        assertEquals("only redstone moves an iron trapdoor",
                -1, PlacementGeometry.interactionClicks(ironClosed, ironOpen));
    }

    /** A click whose effect never appears is re-sent a bounded number of times and then named. A retry loop with no
     *  ceiling is how a run freezes for 1740 ticks without producing a line to read. */
    @Test
    public void aSwallowedClickIsRetriedABoundedNumberOfTimesAndThenNamed() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        BlockState live = delay(1);
        long tick = 0;
        int clicks = 0;
        ActionRunner.Directive last = null;
        for (int i = 0; i < 400 && !runner.settled(); i++) {
            last = runner.tick(aimedAt(live, tick++));
            if (last instanceof ActionRunner.Directive.ClickRight) {
                clicks++;
            }
        }
        assertNotNull(last);
        assertTrue(last.describe(), last instanceof ActionRunner.Directive.Blocked);
        assertTrue(last.describe(), last.describe().contains("no change"));
        assertTrue("bounded, not endless: " + clicks, clicks <= ActionRunner.MAX_CLICK_ATTEMPTS + 1);
    }

    /** Any screen at all stops everything that is not the sign editor this action is waiting for. A container left
     *  open means no hotbar swap can ever be granted again. */
    @Test
    public void anyOpenScreenStopsEveryActionThatIsNotWritingASign() {
        ActionRunner runner = new ActionRunner(interact(delay(1), delay(2), 1));
        ActionRunner.Directive closing = runner.tick(
                observation(delay(1), 0).withScreen(ActionRunner.ScreenState.other()).build());
        assertTrue(closing.describe(), closing instanceof ActionRunner.Directive.CloseScreen);
    }

    // ---------------------------------------------------------------------------------------------- sign writing

    /** The happy path: the editor opens for our cell and our side, the lines go in, the screen closes. */
    @Test
    public void aSignIsTypedOnlyIntoItsOwnEditor() {
        BuildAction.WriteSign action = writeSign(List.of("first", "second", "", ""));
        ActionRunner runner = new ActionRunner(action);

        assertTrue("the editor is opened by the server after the placement",
                runner.tick(signObservation(ActionRunner.ScreenState.NONE, 0))
                        instanceof ActionRunner.Directive.Wait);

        ActionRunner.Directive typed = runner.tick(
                signObservation(ActionRunner.ScreenState.signEditor(CELL, true), 1));
        assertTrue(typed.describe(), typed instanceof ActionRunner.Directive.TypeSign);
        assertEquals(List.of("first", "second", "", ""), ((ActionRunner.Directive.TypeSign) typed).lines());

        ActionRunner.Directive done = runner.tick(signObservation(ActionRunner.ScreenState.NONE, 2));
        assertTrue(done.describe(), done instanceof ActionRunner.Directive.Finished);
    }

    /** An editor for a different sign is closed, never typed into. This is how one sign's text lands on another. */
    @Test
    public void anEditorForAnotherSignIsClosedRatherThanTypedInto() {
        ActionRunner runner = new ActionRunner(writeSign(List.of("hello")));
        ActionRunner.Directive directive = runner.tick(
                signObservation(ActionRunner.ScreenState.signEditor(CELL.above(), true), 0));
        assertTrue(directive.describe(), directive instanceof ActionRunner.Directive.CloseScreen);
    }

    /** An editor that cannot say which sign it edits is refused outright. */
    @Test
    public void anAnonymousEditorIsRefused() {
        ActionRunner runner = new ActionRunner(writeSign(List.of("hello")));
        ActionRunner.Directive directive = runner.tick(
                signObservation(new ActionRunner.ScreenState(true, true, null, true), 0));
        assertTrue(directive.describe(), directive instanceof ActionRunner.Directive.Blocked);
    }

    /** {@code SignItem} opens a freshly placed sign's FRONT unconditionally, so a plan asking for the back gets a
     *  sentence rather than its text on the wrong face. */
    @Test
    public void theBackSideIsNotWrittenThroughAFrontEditor() {
        BuildAction.WriteSign back = new BuildAction.WriteSign(CELL, List.of("behind"), false, false,
                HotbarSchedule.PICKAXE_SLOT, LAYER);
        ActionRunner runner = new ActionRunner(back);
        ActionRunner.Directive directive = runner.tick(
                signObservation(ActionRunner.ScreenState.signEditor(CELL, true), 0));
        assertTrue(directive.describe(), directive instanceof ActionRunner.Directive.Blocked);
        assertTrue(directive.describe(), directive.describe().contains("from behind"));
    }

    /** An editor that never opens — a waxed sign, a protection plugin — is a named stop and not an endless wait. */
    @Test
    public void anEditorThatNeverOpensIsNamed() {
        ActionRunner runner = new ActionRunner(writeSign(List.of("hello")));
        ActionRunner.Directive last = null;
        for (long tick = 0; tick <= ActionRunner.SCREEN_WAIT_TICKS + 2 && !runner.settled(); tick++) {
            last = runner.tick(signObservation(ActionRunner.ScreenState.NONE, tick));
        }
        assertNotNull(last);
        assertTrue(last.describe(), last instanceof ActionRunner.Directive.Blocked);
        assertTrue(last.describe(), last.describe().contains("blank"));
    }

    // ------------------------------------------------------------------------------------------------ sign NBT

    /** The current on-disk shape: {@code front_text.messages} holding four raw component tags. */
    @Test
    public void modernPlainComponentTagsAreDecoded() {
        CompoundTag sign = modernSignTags(
                StringTag.valueOf("top"),
                StringTag.valueOf("middle"),
                StringTag.valueOf(""),
                StringTag.valueOf("bottom"));
        assertTrue(SignNbt.isSign(sign));
        assertEquals(List.of("top", "middle", "", "bottom"), SignNbt.lines(sign, true));
        assertEquals("the back side is a separate compound and this file has none",
                List.of(), SignNbt.lines(sign, false));
    }

    /** Styled text is a structured raw NBT component. Reading it through {@code ListTag#getString} silently returns
     *  blank; reading the raw tag through the component codec preserves its visible text. */
    @Test
    public void modernStructuredComponentTagsAreDecoded() {
        Tag styled = encodedComponent(Component.literal("middle").withStyle(ChatFormatting.BOLD));
        assertTrue("the fixture must exercise the structured-tag path", styled instanceof CompoundTag);

        CompoundTag sign = modernSignTags(styled);
        assertEquals(List.of("middle", "", "", ""), SignNbt.lines(sign, true));
    }

    /** Early two-sided sign files put component JSON inside StringTags. It remains a supported migration shape. */
    @Test
    public void jsonInsideModernStringTagsStillDecodes() {
        CompoundTag sign = modernSign("\"top\"", "{\"text\":\"middle\",\"bold\":true}", "\"\"", "\"bottom\"");
        assertEquals(List.of("top", "middle", "", "bottom"), SignNbt.lines(sign, true));
    }

    /** Pre-1.20 files are still in circulation and this parser applies no datafixer, so the legacy shape is read
     *  too — otherwise every older schematic reads as blank, which looks exactly like a schematic with blank signs. */
    @Test
    public void legacySignNbtIsDecodedToo() {
        CompoundTag sign = new CompoundTag();
        sign.putString("Text1", "\"one\"");
        sign.putString("Text2", "\"two\"");
        sign.putString("Text3", "\"\"");
        sign.putString("Text4", "\"\"");
        assertTrue(SignNbt.isSign(sign));
        assertEquals(List.of("one", "two", "", ""), SignNbt.lines(sign, true));
    }

    /** A blank side answers empty rather than four empty strings, so the planner can express "no action" instead of
     *  re-deriving it. */
    @Test
    public void aBlankSignProducesNoLinesAndThereforeNoAction() {
        CompoundTag sign = modernSign("\"\"", "\"\"", "\"\"", "\"\"");
        assertTrue(SignNbt.isSign(sign));
        assertTrue(SignNbt.lines(sign, true).isEmpty());
        assertFalse(SignNbt.isSign(null));
        assertFalse("a chest is not a sign", SignNbt.isSign(new CompoundTag()));
    }

    /** Unreadable is not blank. Typing four empty lines over words the schematic asked for is the silent failure
     *  this distinction exists to prevent. */
    @Test
    public void unreadableTextIsNotSilentlyBlank() {
        CompoundTag sign = modernSign("\"fine\"", "{not json at all", "\"\"", "\"\"");
        List<String> lines = SignNbt.lines(sign, true);
        assertTrue(SignNbt.unreadable(lines));
        assertEquals("fine", lines.get(0));

        List<String> impossibleTag = SignNbt.lines(modernSignTags(IntTag.valueOf(7)), true);
        assertTrue("a non-component raw NBT tag is a refusal, not a blank line",
                SignNbt.unreadable(impossibleTag));
        assertFalse(SignNbt.unreadable(SignNbt.lines(modernSign("\"a\"", "\"\"", "\"\"", "\"\""), true)));
    }

    // ------------------------------------------------------------------------------------------------- fluids

    /** Only a genuine SOURCE of a still fluid is fillable. Everything else a fluid can look like is refused, because
     *  mistaking a non-fluid cell for a fluid one is the only way this feature could make anything worse. */
    @Test
    public void onlyStillSourcesAreFillable() {
        assertSame(Items.WATER_BUCKET, FluidPlan.sourceBucketFor(Blocks.WATER.defaultBlockState()));
        assertSame(Items.LAVA_BUCKET, FluidPlan.sourceBucketFor(Blocks.LAVA.defaultBlockState()));
        assertSame(null, FluidPlan.sourceBucketFor(Blocks.COBBLESTONE.defaultBlockState()));

        BlockState flowing = Blocks.WATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3);
        assertSame("a flowing level re-derives itself from its source", null, FluidPlan.sourceBucketFor(flowing));
        assertTrue(FluidPlan.isUnbuildableFluid(flowing));
        assertTrue("a bubble column follows from the block beneath it",
                FluidPlan.isUnbuildableFluid(Blocks.BUBBLE_COLUMN.defaultBlockState()));
        assertFalse(FluidPlan.isUnbuildableFluid(Blocks.COBBLESTONE.defaultBlockState()));
    }

    /** Waterlogging is a property of another block, not a cell — named, never half-done. */
    @Test
    public void waterloggingTargetsAndFinishesTheAlreadyPlacedBlock() {
        BlockState waterloggedStairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        BlockState dry = waterloggedStairs
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false);
        assertTrue(FluidPlan.isFillable(waterloggedStairs));
        assertFalse(FluidPlan.isUnbuildableFluid(waterloggedStairs));
        assertSame(Items.WATER_BUCKET, FluidPlan.waterloggingBucketFor(waterloggedStairs));
        assertEquals(waterloggedStairs, FluidPlan.waterloggedResult(dry, Items.WATER_BUCKET));
        assertEquals(CELL, FluidPlan.destination(dry, CELL, Direction.NORTH, Items.WATER_BUCKET));
    }

    /** The bucket is confirmed by the FLUID and not by an equality test against a hand-built state, and the empty
     *  bucket comes back for the ledger. */
    @Test
    public void aBucketIsConfirmedByItsExactExpectedState() {
        assertTrue(FluidPlan.satisfies(Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState()));
        assertFalse(FluidPlan.satisfies(Blocks.LAVA.defaultBlockState(), Blocks.WATER.defaultBlockState()));
        assertFalse(FluidPlan.satisfies(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState()));
        assertSame(Items.BUCKET, FluidPlan.emptied(Items.LAVA_BUCKET));
    }

    /** The destination is empty and cannot be clicked. A fluid action is valid only when its clicked neighbour and
     *  exposed face lead exactly into that destination. */
    @Test
    public void aFluidActionRejectsImpossibleClickGeometry() {
        try {
            new BuildAction.FillFluid(CELL, CELL.north(), STANCE, APPROACH, AIM, LOOK, Direction.UP,
                    Items.WATER_BUCKET, Blocks.WATER.defaultBlockState(), true, 3, LAYER);
            fail("a click whose face does not lead into the destination must not construct");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("fluid action"));
        }
    }

    /** The whole fluid action, end to end: the bucket has to be in hand, one click goes out, and the fluid is held
     *  before the action is done. */
    @Test
    public void aFluidCellIsFilledOnceAndConfirmedByTheWorld() {
        ActionRunner runner = new ActionRunner(fillFluid(Items.WATER_BUCKET, 3));
        BlockState empty = Blocks.AIR.defaultBlockState();

        ActionRunner.Directive waiting = runner.tick(
                observation(empty, 0).withHeld(Items.COBBLESTONE).withSlot(3).build());
        assertTrue(waiting.describe(), waiting instanceof ActionRunner.Directive.Wait);

        ActionRunner.Directive stillAiming = runner.tick(
                observation(empty, 1).withHeld(Items.WATER_BUCKET).withSlot(3).build());
        assertTrue("the empty destination is not the bucket's clickable target: " + stillAiming.describe(),
                stillAiming instanceof ActionRunner.Directive.Aim);

        ActionRunner.Directive click = runner.tick(
                observation(empty, 2).withHeld(Items.WATER_BUCKET).withSlot(3)
                        .withRayCell(FLUID_AGAINST).withRayFace(FLUID_FACE).build());
        assertTrue(click.describe(), click instanceof ActionRunner.Directive.ClickRight);
        assertTrue("a bucket sneaks", ((ActionRunner.Directive.ClickRight) click).sneak());

        // The fluid appears after the click and is held until it has been observed CONFIRM_HOLD_TICKS times — the
        // client
        // renders a placement the instant it predicts one, and a protection plugin reverts it two to six ticks later.
        BlockState water = Blocks.WATER.defaultBlockState();
        long tick = 3;
        for (int observed = 1; observed < ActionRunner.CONFIRM_HOLD_TICKS; observed++, tick++) {
            assertTrue(runner.tick(observation(water, tick).withHeld(Items.BUCKET).withSlot(3).build())
                    instanceof ActionRunner.Directive.Wait);
        }
        assertTrue(runner.tick(observation(water, tick).withHeld(Items.BUCKET).withSlot(3).build())
                instanceof ActionRunner.Directive.Finished);
    }

    // -------------------------------------------------------------------------------------- geometry accessors

    /** The five accessors answer for the actions that have a place in the world and {@code null} for the two that do
     *  not, so no caller has to know which is which. */
    @Test
    public void theGeometryAccessorsAreTotalOverTheSealedType() {
        BuildAction.Interact action = interact(delay(1), delay(2), 1);
        assertEquals(STANCE, ActionRunner.stanceOf(action));
        assertEquals(APPROACH, ActionRunner.approachOf(action));
        assertEquals(AIM, ActionRunner.aimPointOf(action));
        assertEquals(LOOK, ActionRunner.plannedRotationOf(action));
        assertEquals(Direction.NORTH, ActionRunner.faceOf(action));
        assertEquals(CELL, ActionRunner.rayTargetOf(action));

        BuildAction.FillFluid fluid = fillFluid(Items.WATER_BUCKET, 3);
        assertEquals("the empty destination is not the bucket's ray target",
                FLUID_AGAINST, ActionRunner.rayTargetOf(fluid));
        assertEquals(FLUID_FACE, ActionRunner.faceOf(fluid));

        BuildAction sign = writeSign(List.of("x"));
        assertSame(null, ActionRunner.stanceOf(sign));
        assertSame(null, ActionRunner.plannedRotationOf(sign));
        assertSame(null, ActionRunner.rayTargetOf(sign));
        assertSame("any face of a cell breaks the same block",
                null, ActionRunner.faceOf(removeScaffold(Blocks.DIRT.defaultBlockState())));
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private static BlockState delay(int ticks) {
        return Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, ticks);
    }

    private static PlacementSolution solution(BlockState desired) {
        return new PlacementSolution(CELL, desired, STANCE, APPROACH, CELL.north(), Direction.SOUTH, AIM, LOOK,
                PlacementSolution.UNCONSTRAINED_MARGIN, desired, desired.getBlock().asItem());
    }

    /** A jump-place: the body stands IN the cell it fills and clicks the top face of the block below. Those two
     *  facts are what BuildAction.JumpPlace checks in its constructor, so this helper has to state both. */
    private static BuildAction.JumpPlace jumpPlace(BlockState desired) {
        PlacementSolution solution = new PlacementSolution(CELL, desired, CELL,
                new Vec3(CELL.getX() + 0.5D, CELL.getY(), CELL.getZ() + 0.5D), CELL.below(), Direction.UP,
                new Vec3(CELL.getX() + 0.5D, CELL.getY(), CELL.getZ() + 0.5D), new Rotation(0.0F, 90.0F),
                PlacementSolution.UNCONSTRAINED_MARGIN, desired, desired.getBlock().asItem());
        return new BuildAction.JumpPlace(solution, 2, LAYER);
    }

    private static BuildAction.Place place(BlockState desired) {
        ActionRunner.Posture required = ActionRunner.postureFor(
                new BuildAction.Place(solution(desired), false, 2, LAYER));
        return new BuildAction.Place(solution(desired), required.sneak(), 2, LAYER);
    }

    private static BuildAction.Interact interact(BlockState from, BlockState to, int clicks) {
        return new BuildAction.Interact(CELL, STANCE, APPROACH, AIM, LOOK, Direction.NORTH, clicks, to, false,
                HotbarSchedule.PICKAXE_SLOT, LAYER);
    }

    private static BuildAction.WriteSign writeSign(List<String> lines) {
        return new BuildAction.WriteSign(CELL, lines, true, false, HotbarSchedule.PICKAXE_SLOT, LAYER);
    }

    private static BuildAction.FillFluid fillFluid(Item bucket, int slot) {
        BlockState expected = bucket == Items.LAVA_BUCKET
                ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
        return new BuildAction.FillFluid(CELL, FLUID_AGAINST, STANCE, APPROACH, AIM, LOOK, FLUID_FACE, bucket,
                expected, true, slot, LAYER);
    }

    private static BuildAction.RemoveScaffold removeScaffold(BlockState expected) {
        return new BuildAction.RemoveScaffold(CELL, STANCE, APPROACH, AIM, LOOK, expected, false,
                HotbarSchedule.PICKAXE_SLOT, LAYER);
    }

    /** One representative action of every kind, so an ownership assertion can be made total over the enum rather
     *  than over the six kinds somebody remembered to list. */
    private static BuildAction actionOfKind(BuildAction.Kind kind) {
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        return switch (kind) {
            case PLACE -> place(Blocks.STONE.defaultBlockState());
            case JUMP_PLACE -> jumpPlace(Blocks.STONE.defaultBlockState());
            case BREAK -> new BuildAction.Break(CELL, STANCE, APPROACH, AIM, LOOK, Direction.NORTH, dirt, true,
                    true, HotbarSchedule.PICKAXE_SLOT, LAYER);
            case PLACE_SCAFFOLD -> new BuildAction.PlaceScaffold(solution(dirt), CELL.above(), true,
                    HotbarSchedule.THROWAWAY_SLOT, LAYER);
            case REMOVE_SCAFFOLD -> removeScaffold(dirt);
            case INTERACT -> interact(delay(1), delay(2), 1);
            case WRITE_SIGN -> writeSign(List.of("x"));
            case FILL_FLUID -> fillFluid(Items.WATER_BUCKET, 3);
            case SWAP_HOTBAR -> new BuildAction.SwapHotbar(3, Items.COBBLESTONE, 12, false, 3, LAYER);
        };
    }

    private static CompoundTag modernSign(String... messages) {
        Tag[] tags = new Tag[messages.length];
        for (int index = 0; index < messages.length; index++) {
            tags[index] = StringTag.valueOf(messages[index]);
        }
        return modernSignTags(tags);
    }

    private static CompoundTag modernSignTags(Tag... messages) {
        ListTag list = new ListTag();
        for (Tag message : messages) {
            list.add(message);
        }
        CompoundTag side = new CompoundTag();
        side.put("messages", list);
        CompoundTag sign = new CompoundTag();
        sign.put("front_text", side);
        return sign;
    }

    private static Tag encodedComponent(Component component) {
        return ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, component).result().orElseThrow();
    }

    /** The common case: head settled on the planned rotation, live ray on the cell and its face, pickaxe in hand,
     *  nothing throttled, no screen. */
    private static ActionRunner.Observation aimedAt(BlockState live, long tick) {
        return observation(live, tick).build();
    }

    private static ActionRunner.Observation signObservation(ActionRunner.ScreenState screen, long tick) {
        return observation(Blocks.OAK_SIGN.defaultBlockState(), tick).withScreen(screen).build();
    }

    private static Obs observation(BlockState live, long tick) {
        return new Obs(live, tick);
    }

    /**
     * A mutable builder over the {@link ActionRunner.Observation} record.
     *
     * <p>Twelve fields is the honest width of what one tick of a client says, and twelve positional arguments per
     * assertion would make every test read as a row of booleans nobody can check. The builder names them; the record
     * keeps them immutable once they cross into the runner.
     */
    private static final class Obs {

        private final long tick;
        private BlockState live;
        private Rotation rotation = LOOK;
        private Rotation aimRotation = LOOK;
        private BlockPos rayHitCell = CELL;
        private Direction rayFace = Direction.NORTH;
        private int slot = HotbarSchedule.PICKAXE_SLOT;
        private Item held = Items.STONE_PICKAXE;
        private boolean placeThrottled;
        private boolean consuming;
        private ActionRunner.ScreenState screen = ActionRunner.ScreenState.NONE;

        private Obs(BlockState live, long tick) {
            this.live = live;
            this.tick = tick;
        }

        private Obs withRotation(Rotation value) {
            this.rotation = value;
            return this;
        }

        private Obs withAimRotation(Rotation value) {
            this.aimRotation = value;
            return this;
        }

        private Obs withRayHit(boolean value) {
            this.rayHitCell = value ? CELL : null;
            return this;
        }

        private Obs withRayCell(BlockPos value) {
            this.rayHitCell = value;
            return this;
        }

        private Obs withRayFace(Direction value) {
            this.rayFace = value;
            return this;
        }

        private Obs withSlot(int value) {
            this.slot = value;
            return this;
        }

        private Obs withHeld(Item value) {
            this.held = value;
            return this;
        }

        private Obs withPlaceThrottled(boolean value) {
            this.placeThrottled = value;
            return this;
        }

        private Obs withConsuming(boolean value) {
            this.consuming = value;
            return this;
        }

        private Obs withScreen(ActionRunner.ScreenState value) {
            this.screen = value;
            return this;
        }

        private ActionRunner.Observation build() {
            return new ActionRunner.Observation(this.tick, this.live, this.rotation, this.aimRotation,
                    this.rayHitCell, this.rayFace, this.slot, this.held, this.placeThrottled, this.consuming,
                    this.screen);
        }
    }
}
