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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The actions beyond placing — the right-click that steps a repeater, the four lines of a sign, the bucket, and the
 * break that takes a helper block back out.
 *
 * <p>One instance runs exactly ONE {@link BuildAction} to its confirmation and is then thrown away. It holds the only
 * state that action has: how far it has got, and since when. The frozen order and the index into it belong to the
 * executor; the decision of what to press this tick belongs here.
 *
 * <h2>Why this class touches nothing</h2>
 *
 * <p>Every method takes an {@link Observation} — a plain record of what the client saw this tick — and returns a
 * {@link Directive} — a plain record of what the client should do this tick. There is no {@code ctx}, no
 * {@code Minecraft}, no {@code Princeps.settings()}. That is not architectural taste: a class that reads
 * {@code Princeps.settings()} throws {@code ExceptionInInitializerError} under JUnit and is untestable forever, and
 * the failures this class exists to prevent are all of the silent kind, which is precisely the kind a test catches
 * and a play session does not. A repeater left at delay 1, a blank sign, a helper block still standing in the
 * finished build: none of those crash, none of those log, and all of them pass a "did the block get placed" check.
 *
 * <h2>One directive per tick, and what that buys</h2>
 *
 * <p>{@link #tick} returns a single directive. That is the whole defence against the executor trap that costs the
 * most and shows the least: {@code CLICK_LEFT} clears {@code CLICK_RIGHT} in {@code InputOverrideHandler} BEFORE the
 * helpers tick, so forcing both in one tick hands {@code BlockPlaceHelper} a {@code tick(false)} — which does not
 * merely skip the click, it consumes the pending {@code ExpectedPlacement} with it, silently. A runner that could
 * return "break and place" is a runner that will eventually return it. This one cannot express the sentence.
 *
 * <p>The same shape closes the sibling trap: {@code expectMainHandPlacement} is read-and-nulled at the TOP of
 * {@code BlockPlaceHelper.tick}, before the "was a right-click actually requested" gate, so a commit made without
 * forcing {@code CLICK_RIGHT} in the SAME {@code onTick} evaporates with zero diagnostics. A
 * {@link Directive.ClickRight} is one object carrying both halves; the executor cannot do one without the other
 * because there is nothing to do them separately with.
 *
 * <h2>Confirmation, not optimism</h2>
 *
 * <p>Nothing here counts a click. Everything here counts a CONFIRMED CHANGE in the live world, held for
 * {@link #CONFIRM_HOLD_TICKS}. The reason is the same one that motivates the whole engine: the client predicts a
 * placement optimistically and a protection plugin reverts it two to six ticks later, and
 * {@code successfulBlockInteractions} — the only counter the helpers offer — increments on the CLIENT's prediction,
 * so a rollback produces no change to it and no event on any bus. An interaction counted by clicks sent is an
 * interaction that is wrong exactly when the server disagrees, which is the case the counting was for.
 *
 * <p>For {@link BuildAction.Interact} this has a second, sharper consequence. A repeater's delay wraps 4 to 1 and a
 * note block's note wraps 24 to 0, so a click too many is not a small error — it is three more clicks, or twenty-four.
 * {@link PlacementGeometry#interactionClicks} is therefore re-asked of the LIVE state every tick and the runner sends
 * the next click only once the previous one has landed. Clicks sent blind would chase a wrapping value around its
 * cycle, and the run would look like it was working the whole time.
 *
 * <h2>What this class does NOT own</h2>
 *
 * <p>Travel, the fine approach, the aim curve itself, the hotbar fetch, and the placement click all belong to the
 * executor. This runner asks for an aim and asks for a click; it does not turn the head and does not swing the arm.
 * It also refuses to run {@link BuildAction.Place}, {@link BuildAction.PlaceScaffold}, {@link BuildAction.Break},
 * {@link BuildAction.RemoveScaffold} and {@link BuildAction.SwapHotbar} rather than half-running them — see
 * {@link #owns}.
 *
 * <h2>Every break belongs to the executor</h2>
 *
 * <p>{@link BuildAction.RemoveScaffold} used to be claimed here as well as by {@code CellExecutor.isBreak}, and two
 * owners of {@code CLICK_LEFT} is precisely the shape this engine exists to have removed. The executor is the owner,
 * and the deciding argument is not seniority but evidence: a break there is armed against {@link ServerAck} and
 * cannot start at all while the acknowledgement channel is unavailable, whereas this class has no acknowledgement
 * seam and would confirm a destruction from the client's own optimism. A destructive action may not be the one place
 * in the engine where server evidence is optional. A break and a scaffold removal are also the same physical
 * operation, so splitting them across two classes would mean two answers to "what may this bot destroy".
 */
public final class ActionRunner {

    // ------------------------------------------------------------------------------------------------ constants

    /**
     * How long a confirmed state has to hold before an action is done.
     *
     * <p>Three ticks, because the client shows a placement the instant it predicts one and a server-side revert
     * arrives two to six ticks later. Zero would accept the prediction, which is the bookkeeping that let V2 mark
     * cells finished that a protection plugin had already undone.
     */
    public static final int CONFIRM_HOLD_TICKS = 3;

    /**
     * Aim tolerance, yaw and pitch, in degrees.
     *
     * <p>Never {@code isReallyCloseTo}: its tolerance is 0.01 degrees while humanised look adds up to ~0.42 yaw and
     * ~0.30 pitch of tremor plus up to 0.80 of micro-jitter on a non-precise target. A gate on that number does not
     * fail loudly, it never fires — an infinite stall with nothing in the log.
     */
    public static final float YAW_TOLERANCE = 0.75F;

    /** @see #YAW_TOLERANCE */
    public static final float PITCH_TOLERANCE = 0.55F;

    /**
     * Consecutive ticks a special action may ask for the same proven aim without the live ray reaching its target.
     *
     * <p>Four seconds is generous for the humanised look curve and finite by construction. A bad partial-block aim
     * otherwise returns {@link Directive.Aim} forever: no click is sent, no click timeout starts, and the run produces
     * no refusal to diagnose.
     */
    public static final int AIM_PATIENCE_TICKS = 80;

    /**
     * Consecutive ticks the runner may answer {@link Directive.Wait} before the waiting is itself the answer.
     *
     * <p>Ten seconds. The three unbounded waits it closes — a drifted hotbar slot, a bot mid-meal, a throttled place
     * helper — are all conditions the runner cannot influence, so waiting on one is either resolved by somebody else
     * within a second or two or never. @see #bounded
     */
    public static final int WAIT_PATIENCE_TICKS = 200;

    /**
     * How long to wait for the sign editor to open after the sign has landed.
     *
     * <p>Two seconds. The screen is opened by the server, so this covers a slow round trip; past it, the screen is
     * not coming — a waxed sign, a protection plugin, a server that suppresses the editor — and waiting longer only
     * postpones the sentence.
     */
    public static final int SCREEN_WAIT_TICKS = 40;

    /**
     * How long to wait for one click to show up in the world before treating it as swallowed.
     *
     * <p>Not a tick budget for the action, a budget for ONE click. Never a hard-coded number of ticks for a whole
     * interaction: the place cooldown is {@code max(1, base + round(g*1.5))} with {@code g} a sum of three uniforms,
     * so it is not deterministic, and {@code isThrottled()} is the only honest way to ask.
     */
    public static final int CLICK_RESPONSE_TICKS = 40;

    /**
     * How many times one click may be re-sent after being swallowed.
     *
     * <p>Three, and then the action is blocked by name. A retry loop with no ceiling is how a build spends 1740 ticks
     * frozen and produces not one line explaining it.
     */
    public static final int MAX_CLICK_ATTEMPTS = 3;

    // ------------------------------------------------------------------------------------ the pure posture rules

    /** Which mouse button an action's confirmation comes from. */
    public enum Button {

        /** Nothing is clicked — a hotbar fetch, a sign's text. */
        NONE,

        /** Place, interact, empty a bucket. */
        RIGHT,

        /** Break, remove a helper block. */
        LEFT
    }

    /**
     * The body posture and click shape an action requires, derived from the action's TYPE and its desired STATE —
     * never read back off the action's own fields.
     *
     * <p>Deriving it is the point. {@link BuildAction} carries {@code sneak} and {@code handSlot} because vanilla's
     * answer to a right-click depends on both, but a field can be filled in wrongly and a wrongly filled field is
     * invisible: a crouched click on a repeater places a block instead of stepping it, and the build is wrong in a
     * way that still looks like a build. This function is the one statement of the rule, {@link #postureViolation}
     * checks a plan against it, and the two together turn "remember to stand up for chests" into a test.
     *
     * @param sneak    whether SNEAK is held while the click goes out
     * @param handSlot the hotbar slot selected for the click
     * @param button   which button, if any
     * @param clicks   confirmations required; {@link #HELD} for a break, whose click is held rather than counted
     */
    public record Posture(boolean sneak, int handSlot, Button button, int clicks) {

        /** {@link #clicks} for an action whose button is held until the world changes rather than pressed a fixed
         *  number of times — every break. */
        public static final int HELD = -1;

        /** Is this a held-button action rather than a counted-click one? */
        public boolean isHeld() {
            return this.clicks == HELD;
        }
    }

    /**
     * The posture the action requires. Pure, total over the sealed type, and the single statement of every vanilla
     * rule in this area.
     *
     * <ul>
     *   <li><b>A chest is placed STANDING.</b> Sneak makes {@code ChestBlock.getStateForPlacement} return SINGLE, so
     *       a crouched click on either half of a planned double chest builds two single chests — which pass every "is
     *       there a chest here" check and fail only the audit. Every OTHER placement sneaks, because standing means
     *       the clicked block's own use fires first and clicking a chest, a lever or a repeater with a block in hand
     *       opens or steps it instead of placing. The rule is per FAMILY and not per chest type, deliberately: the
     *       oracle simulates every chest standing, so a SINGLE chest next to a same-facing neighbour is PREDICTED to
     *       pair and is reported as a cell the plan cannot satisfy — which is a named refusal before the run rather
     *       than a double chest discovered in the audit after it.</li>
     *   <li><b>An interaction does NOT sneak and holds a non-placing item.</b> A crouched right-click skips the
     *       block's use entirely and places whatever is in the hand, so the repeater ends up buried under a
     *       cobblestone rather than stepped. The empty hand is the second half of the same defence: a click whose ray
     *       has drifted one face off the repeater places a block there if the hand can place, and does nothing at all
     *       if it cannot.</li>
     *   <li><b>A bucket DOES sneak.</b> {@code BucketItem} empties through {@code Item.use}, which vanilla reaches
     *       only after the clicked block's own {@code useItemOn} has declined. Standing, a bucket aimed at a chest
     *       opens the chest — and an open screen means no hotbar swap can ever be granted again, which is a stall
     *       with no error. Sneak makes the block's use step aside.</li>
     *   <li><b>Breaks sneak and hold the pickaxe.</b> Not for the breaking — sneak does nothing to that — but for the
     *       RAY: {@code BreakPlanner.aim} proves its geometry from the crouched eye at 1.27, and a standing click
     *       casts from 1.62, which over a four-block reach moves the hit point by more than the margins this design
     *       is built on. The stance a proof was written against is part of the proof.</li>
     * </ul>
     *
     * <p>Which makes the interaction the one action in the engine that is proven STANDING, because vanilla leaves no
     * choice: it must not sneak, so it must not be proven crouched either. {@link PlayerPose#STANDING} exists for
     * exactly that one call, and mixing the two poses is a divergence that compiles, runs, and misses.</p>
     *
     * <p>The returned {@code handSlot} is the slot the RULE fixes where a rule exists — the pickaxe, for everything
     * that breaks, interacts or types — and the action's own slot where it does not. The slot of a placement is not a
     * rule at all: it is whatever {@code HotbarSchedule.belady} evicted for it over the whole frozen order, so
     * asserting a value there would be asserting against the schedule rather than against vanilla, and the placement
     * would fail the check for being scheduled well.
     */
    public static Posture postureFor(BuildAction action) {
        Objects.requireNonNull(action, "action");
        return switch (action) {
            case BuildAction.Place place -> new Posture(
                    PlacementFamilies.classify(place.solution().desired()) != PlacementFamilies.Family.CHEST_TYPE,
                    place.handSlot(), Button.RIGHT, 1);
            // Never crouched: the click is the same one a player makes when pillaring, and a jump-place fills a cell
            // the body is leaving rather than a face beside it, so the crouch has nothing here to protect against.
            case BuildAction.JumpPlace jump -> new Posture(false, jump.handSlot(), Button.RIGHT, 1);
            // A helper block is never a chest — the reserved throwaway is chosen precisely because its type appears
            // in no schematic cell — so it takes the ordinary sneaking placement with no exception to test for.
            case BuildAction.PlaceScaffold scaffold -> new Posture(true, scaffold.handSlot(), Button.RIGHT, 1);
            case BuildAction.Break ignored -> new Posture(true, HotbarSchedule.PICKAXE_SLOT, Button.LEFT,
                    Posture.HELD);
            case BuildAction.RemoveScaffold ignored -> new Posture(true, HotbarSchedule.PICKAXE_SLOT, Button.LEFT,
                    Posture.HELD);
            case BuildAction.Interact interact -> new Posture(false, HotbarSchedule.PICKAXE_SLOT, Button.RIGHT,
                    interact.clicks());
            case BuildAction.FillFluid fluid -> new Posture(true, fluid.handSlot(), Button.RIGHT, 1);
            // Typing is not clicking: the sign editor is already open and the four lines go in through the screen's
            // own key handling. Nothing is held, nothing is pressed at the world.
            case BuildAction.WriteSign ignored -> new Posture(false, HotbarSchedule.PICKAXE_SLOT, Button.NONE, 0);
            case BuildAction.SwapHotbar swap -> new Posture(false, swap.handSlot(), Button.NONE, 0);
        };
    }

    /**
     * Does this action's own {@code sneak} / {@code handSlot} agree with {@link #postureFor}? Returns the sentence to
     * print, or {@code null} when it does.
     *
     * <p>Checked over the whole frozen order at plan time rather than discovered at the click. A posture mismatch has
     * no runtime symptom worth the name — the build simply comes out wrong somewhere — so the only useful moment to
     * catch it is before the bot moves.
     */
    public static String postureViolation(BuildAction action) {
        Posture required = postureFor(action);
        if (action.sneak() != required.sneak()) {
            return action.kind() + " at " + BuildAction.describePos(action.cell()) + " must be performed "
                    + (required.sneak() ? "SNEAKING" : "STANDING") + " and the plan says "
                    + (action.sneak() ? "sneaking" : "standing");
        }
        if (action.handSlot() != required.handSlot()) {
            return action.kind() + " at " + BuildAction.describePos(action.cell()) + " must hold slot "
                    + required.handSlot() + " and the plan says slot " + action.handSlot();
        }
        return null;
    }

    /** Every posture violation in a frozen order, in order. Empty for a plan that is internally consistent. */
    public static List<String> postureViolations(List<BuildAction> order) {
        return order.stream().map(ActionRunner::postureViolation).filter(Objects::nonNull).toList();
    }

    // ------------------------------------------------------------------------------------ geometry accessors

    /**
     * Where the bot stands for an action, or {@code null} for the two that have no place in the world.
     *
     * <p>These five accessors exist because {@link BuildAction} deliberately does not declare them on the interface:
     * a {@link BuildAction.SwapHotbar} has no stance and a {@link BuildAction.WriteSign} inherits its placement's,
     * so an interface method would have to answer {@code null} for both and every caller would have to know which.
     * Switching over the sealed type here says the same thing once, exhaustively, where the compiler checks it.
     */
    public static BlockPos stanceOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().stance();
            case BuildAction.JumpPlace jump -> jump.solution().stance();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().stance();
            case BuildAction.Break broken -> broken.stance();
            case BuildAction.RemoveScaffold removed -> removed.stance();
            case BuildAction.Interact interact -> interact.stance();
            case BuildAction.FillFluid fluid -> fluid.stance();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /** The exact sub-block point the fine approach walks to, or {@code null}. @see #stanceOf */
    public static Vec3 approachOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().approach();
            case BuildAction.JumpPlace jump -> jump.solution().approach();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().approach();
            case BuildAction.Break broken -> broken.approach();
            case BuildAction.RemoveScaffold removed -> removed.approach();
            case BuildAction.Interact interact -> interact.approach();
            case BuildAction.FillFluid fluid -> fluid.approach();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /** The point on the target the ray must reach, or {@code null}. @see #stanceOf */
    public static Vec3 aimPointOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().aimPoint();
            case BuildAction.JumpPlace jump -> jump.solution().aimPoint();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().aimPoint();
            case BuildAction.Break broken -> broken.aimPoint();
            case BuildAction.RemoveScaffold removed -> removed.aimPoint();
            case BuildAction.Interact interact -> interact.aimPoint();
            case BuildAction.FillFluid fluid -> fluid.aimPoint();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /**
     * The rotation the PLAN recorded, or {@code null}. @see #stanceOf
     *
     * <p>This is the plan's record of what the geometry was proved with, and it is <b>not</b> the angle to steer to.
     * The angle that goes out is re-derived at click time from the live eye toward {@link #aimPointOf} — see
     * {@code CellExecutor.rotationOf} and §19.3 V1 — because a proof holds for a POINT and the executor fires from up
     * to a tolerance away from where the point was proved. The two disagree by exactly the margin that stopped the
     * first basalt run at action 102, so wiring this static up as the aim source would reinstate that defect for every
     * delegated action. It survives because the geometry accessors are total over the sealed type and that totality is
     * pinned; the live path reaches the runner through {@code Observation.aimRotation} instead.
     */
    public static Rotation plannedRotationOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().rotation();
            case BuildAction.JumpPlace jump -> jump.solution().rotation();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().rotation();
            case BuildAction.Break broken -> broken.rotation();
            case BuildAction.RemoveScaffold removed -> removed.rotation();
            case BuildAction.Interact interact -> interact.rotation();
            case BuildAction.FillFluid fluid -> fluid.rotation();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /**
     * The face the live ray must hit, or {@code null} when the action does not constrain it.
     *
     * <p>{@link BuildAction.RemoveScaffold} genuinely does not: any face of the cell breaks the same block, so
     * demanding a particular one would refuse a ray that is perfectly correct. Which is also why the block that gets
     * broken is decided by the crosshair alone — see {@link #tick}.
     */
    public static Direction faceOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().face();
            case BuildAction.JumpPlace jump -> jump.solution().face();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().face();
            case BuildAction.Break broken -> broken.face();
            case BuildAction.Interact interact -> interact.face();
            case BuildAction.FillFluid fluid -> fluid.face();
            case BuildAction.RemoveScaffold ignored -> null;
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /**
     * The block the live ray must hit.
     *
     * <p>Usually the action's cell. A bucket is the exception that motivated making the distinction explicit: its cell
     * is empty before the click, so vanilla targets the neighbouring {@link BuildAction.FillFluid#against()} and puts
     * the fluid on the far side of the hit face.
     */
    public static BlockPos rayTargetOf(BuildAction action) {
        return switch (action) {
            case BuildAction.FillFluid fluid -> fluid.against();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
            default -> action.cell();
        };
    }

    /**
     * Is this one of the actions beyond placing — the ones this runner executes?
     *
     * <p>The three it owns are {@code INTERACT}, {@code WRITE_SIGN} and {@code FILL_FLUID}: the right-clicks and the
     * typing. Everything that PLACES or BREAKS belongs to the executor and is not half-implemented here — two classes
     * that both nearly own the click is how the aim ends up being set twice in one tick by two consumers with no
     * precedence between them, which is a failure mode this engine exists to have removed. {@code REMOVE_SCAFFOLD} is
     * a break and therefore the executor's; see the class javadoc for why that one is not a coin toss.
     *
     * <p>This predicate is the ONLY routing rule. {@code CellExecutor.onTick} delegates exactly the kinds it answers
     * true for and runs the rest itself, so an action can never be claimed twice and can never be claimed by nobody.
     */
    public static boolean owns(BuildAction action) {
        return switch (action.kind()) {
            case INTERACT, WRITE_SIGN, FILL_FLUID -> true;
            case PLACE, JUMP_PLACE, PLACE_SCAFFOLD, BREAK, REMOVE_SCAFFOLD, SWAP_HOTBAR -> false;
        };
    }

    // ------------------------------------------------------------------------------------------ what a tick sees

    /**
     * What the client currently reports about the screen stack.
     *
     * @param anyOpen      any screen at all. While one is open the builder must not aim, must not walk and — the
     *                     expensive part — cannot get a hotbar swap granted, ever, because
     *                     {@code InventoryBehavior.onTick} bails while a container menu is up. A lighthouse run spent
     *                     1800 ticks standing still in front of a crafting table nobody could see.
     * @param signEditor   the open screen is a sign editor
     * @param signPos      which sign it edits, from the accessor mixin, or {@code null} when it cannot be read
     * @param signFront    which side it edits. An observation, never a choice: a freshly placed sign always opens its
     *                     FRONT, and the back side opens only for a player standing behind it
     */
    public record ScreenState(boolean anyOpen, boolean signEditor, BlockPos signPos, boolean signFront) {

        /** Nothing is open — the normal case, and the only one in which the builder may act on the world. */
        public static final ScreenState NONE = new ScreenState(false, false, null, false);

        /** A sign editor for a known sign and side. */
        public static ScreenState signEditor(BlockPos pos, boolean front) {
            return new ScreenState(true, true, pos, front);
        }

        /** Any other screen: a container, a crafting table, an anvil, the pause menu. */
        public static ScreenState other() {
            return new ScreenState(true, false, null, false);
        }
    }

    /**
     * Everything the runner is allowed to know about this tick. Values only — no live objects, nothing that could be
     * read again later and answer differently.
     *
     * @param tick           the executor's build tick, monotone; deadlines are differences of it
     * @param liveState      the state at the action's cell right now, never {@code null}
     * @param liveRotation   the rotation the ray is actually being cast from. NOT the rotation requested this tick:
     *                       a rotation asked for in tick N is written in {@code onPlayerUpdate} and is visible to
     *                       {@code objectMouseOver} only in tick N+1, which is exactly why the gate is a gate
     * @param aimRotation    the rotation the executor is actually driving the head toward this tick, or {@code null}
     *                       when it is driving the action's stored angle. These are not always the same number: the
     *                       executor re-derives the angle from the LIVE eye toward the PLANNED aim point, because the
     *                       point on the face is what was proven and the stored angle is only one way of reaching it
     *                       from one particular eye. A "has the head arrived" test against the stored angle would
     *                       then wait for a number nothing is steering to — an aim that never settles, no click, and
     *                       a refusal eighty ticks later blaming the ray
     * @param rayHitCell     the block position the LIVE ray hits, or {@code null}. Kept as a position rather than a
     *                       pre-computed boolean because a fluid action targets its solid neighbour, not its empty
     *                       destination; {@link #rayTargetOf} is the single definition of the required target
     * @param rayFace        the face that ray hit, or {@code null} when it hit nothing
     * @param selectedSlot   the hotbar slot currently selected
     * @param heldItem       the item in the main hand, or {@code null}. Item identity and not a slot number, because
     *                       {@code BlockPlaceHelper.matches} compares the item and a swap between plan and click
     *                       voids the click with no log line
     * @param placeThrottled {@code BlockPlaceHelper.isThrottled()} — a right-click sent while throttled is discarded
     *                       in silence, which is why V2's traces show 793 episodes that look like repeated clicking
     * @param consuming      {@code SurvivalBehavior.isConsuming()}. It gates BOTH helpers: while eating, they are
     *                       ticked with {@code false}, which does not merely skip the click but destroys the pending
     *                       commit for a reason the process never asked for
     * @param screen         the screen stack
     */
    public record Observation(
            long tick,
            BlockState liveState,
            Rotation liveRotation,
            Rotation aimRotation,
            BlockPos rayHitCell,
            Direction rayFace,
            int selectedSlot,
            Item heldItem,
            boolean placeThrottled,
            boolean consuming,
            ScreenState screen
    ) {

        public Observation {
            Objects.requireNonNull(liveState, "liveState");
            Objects.requireNonNull(screen, "screen");
        }
    }

    // ------------------------------------------------------------------------------------ what a tick asks for

    /**
     * Exactly one thing to do this tick.
     *
     * <p>A sealed one-of rather than a set of flags, and that is the design: flags can say "click left and right",
     * which compiles, runs, and quietly eats a pending placement every time. This cannot.
     */
    public sealed interface Directive permits Directive.Aim, Directive.Wait, Directive.ClickRight,
            Directive.TypeSign, Directive.CloseScreen, Directive.Finished, Directive.Blocked {

        /** One line for the trace. Every tick of every action is one of these, so a run reads as a sentence. */
        String describe();

        /**
         * Turn the head toward the planned rotation through the aim curve.
         *
         * <p>Several ticks, monotone, deterministic — the same curve mining uses, which is what makes the click land
         * on a settled head rather than a swinging one. No jitter is added anywhere: the variation is already real
         * and free, because every cell has a different geometry and therefore a different turn.
         *
         * @param sneak sneak is asserted while aiming too, so the stance the fine approach reached is not lost while
         *              the head turns — the body must not drift off the approach point during the last few ticks
         */
        record Aim(Rotation rotation, boolean sneak, String why) implements Directive {
            @Override
            public String describe() {
                return "AIM " + BuildAction.describeRotation(this.rotation) + " (" + this.why + ")";
            }
        }

        /** Hold the stance and press nothing. Always carries the reason, because a builder that stands still without
         *  saying why is the single most expensive thing in this project's history. */
        record Wait(boolean sneak, String why) implements Directive {
            @Override
            public String describe() {
                return "WAIT " + this.why;
            }
        }

        /**
         * One right-click, this tick, from this slot, in this stance.
         *
         * @param armPlacement arm {@code expectMainHandPlacement} in the SAME tick. True only for a placement: the
         *                     expectation is read-and-nulled at the top of {@code BlockPlaceHelper.tick} before the
         *                     "was a right-click requested" gate, so arming without clicking loses it silently — and
         *                     arming for an interaction would claim a placement that is not going to happen
         */
        record ClickRight(int handSlot, boolean sneak, boolean armPlacement, String why) implements Directive {
            @Override
            public String describe() {
                return "CLICK_RIGHT slot " + this.handSlot + (this.sneak ? " sneak" : " stand")
                        + (this.armPlacement ? " armed" : "") + " (" + this.why + ")";
            }
        }

        /**
         * Type the four lines into the open sign editor and confirm.
         *
         * <p>Driven through the screen's own {@code charTyped} / {@code keyPressed} / {@code onClose}, which is the
         * path a keyboard takes: the packet that carries the text is vanilla's, built by {@code removed()} from the
         * screen's own four strings. Nothing is constructed by hand.
         */
        record TypeSign(BlockPos cell, List<String> lines, boolean frontSide) implements Directive {

            public TypeSign {
                lines = List.copyOf(lines);
            }

            @Override
            public String describe() {
                return "TYPE_SIGN " + BuildAction.describePos(this.cell) + " "
                        + (this.frontSide ? "front" : "back") + " " + this.lines;
            }
        }

        /**
         * Close whatever screen is open, because it is not one this action wants.
         *
         * <p>Not politeness. While any container screen is up no hotbar swap can be granted, so the build does not
         * fail — it waits, forever, for a material that will never arrive. The sign editor slipped through V2's
         * container check for years because it has no menu behind it and therefore left {@code containerMenu}
         * untouched.
         */
        record CloseScreen(String why) implements Directive {
            @Override
            public String describe() {
                return "CLOSE_SCREEN " + this.why;
            }
        }

        /** The action is confirmed. Advance the frozen order. */
        record Finished(String what) implements Directive {
            @Override
            public String describe() {
                return "DONE " + this.what;
            }
        }

        /**
         * The action cannot be completed, and here is the sentence.
         *
         * <p>Loud and terminal on purpose. There is no honest quiet channel: the bench scores {@code isPaused()}
         * as FAILURE, so an engine that signals "I am stuck" by pausing is an engine that reports a failure it did
         * not have — and one that says nothing is the engine being replaced.
         */
        record Blocked(String reason) implements Directive {
            @Override
            public String describe() {
                return "BLOCKED " + this.reason;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- state

    /** Where one action has got to. Deliberately small: an action that needs more than this is an action that is
     *  making decisions, and actions do not make decisions. */
    private enum Phase {

        /** Turning toward the planned rotation, or waiting for the throttle. */
        WORKING,

        /** A click has gone out and its effect has not appeared yet. */
        AWAITING_EFFECT,

        /** The world says what it should; counting out {@link #CONFIRM_HOLD_TICKS}. */
        HOLDING,

        /** Confirmed, or refused. */
        SETTLED
    }

    private final BuildAction action;
    private final Posture posture;

    private Phase phase = Phase.WORKING;
    /** "Never set". Compared with {@code ==}, never subtracted from the clock: {@code tick - Long.MIN_VALUE}
     *  overflows to a NEGATIVE number, so a {@code <} throttle reads as permanently running and a {@code >}
     *  timeout never fires. The first shape stranded this engine's first live bench run for 1940 ticks. */
    private static final long NEVER = Long.MIN_VALUE;

    private long startedTick = NEVER;
    private long phaseTick = NEVER;

    /** First and most recent tick of a consecutive run of failed live-ray checks. */
    private long aimStartedTick = NEVER;
    private long lastAimTick = NEVER;

    /** First and most recent tick of the current unbroken run of {@link Directive.Wait}s. @see #bounded */
    private long waitingSinceTick = NEVER;
    private long lastWaitTick = NEVER;

    /** Confirmed steps, for a counted-click action. Never clicks SENT — see the class javadoc. */
    private int confirmed;

    /** How often the current step's click has been re-sent after being swallowed. */
    private int attempts;

    /** The live state the last click was sent against, so "did anything happen" is a comparison and not a guess. */
    private BlockState stateAtClick;

    /** True once the four lines have gone into the editor, so a screen that lingers a tick is not typed into twice. */
    private boolean signTyped;

    /**
     * @param action one of the three this runner owns; see {@link #owns}
     * @throws IllegalArgumentException for a placement, any break, or a hotbar fetch — refused rather than silently
     *                                  ignored, because an action nobody runs is a cell nobody builds and the frozen
     *                                  order would advance past it looking healthy
     */
    public ActionRunner(BuildAction action) {
        this.action = Objects.requireNonNull(action, "action");
        if (!owns(action)) {
            throw new IllegalArgumentException("ActionRunner runs the actions beyond placing; " + action.kind()
                    + " belongs to the executor");
        }
        this.posture = postureFor(action);
    }

    /** The action being run. */
    public BuildAction action() {
        return this.action;
    }

    /** Its required posture. */
    public Posture posture() {
        return this.posture;
    }

    /** How many of a counted-click action's steps the LIVE world has confirmed. */
    public int confirmedSteps() {
        return this.confirmed;
    }

    /** Has this runner reached {@link Directive.Finished} or {@link Directive.Blocked}? */
    public boolean settled() {
        return this.phase == Phase.SETTLED;
    }

    // -------------------------------------------------------------------------------------------- the state machine

    /**
     * One tick. Returns exactly one thing to do.
     *
     * <p>Order of the checks is itself load-bearing, and it is the same for every action type:
     *
     * <ol>
     *   <li><b>Screens first.</b> Nothing else may happen while one is open — not the aim, not the walk, and above
     *       all not a click, which would go into the screen rather than the world.</li>
     *   <li><b>Terminal conditions second.</b> Already correct, or provably unreachable. Asking the live world before
     *       acting is what makes a divergence a fact rather than a suspicion, and it costs one state read.</li>
     *   <li><b>Aim third, and never a click on an unverified ray.</b> The rotation requested in tick N is not
     *       raycast-visible until tick N+1, so the only sound gate is the LIVE ray, and the only sound moment to
     *       click is the first tick after it says the right thing.</li>
     *   <li><b>Throttle last.</b> A click sent while the helper is throttled is discarded without a word, so the
     *       runner waits for {@code isThrottled()} to fall rather than pressing again and hoping.</li>
     * </ol>
     */
    public Directive tick(Observation obs) {
        Objects.requireNonNull(obs, "observation");
        if (this.startedTick == NEVER) {
            this.startedTick = obs.tick();
            this.phaseTick = obs.tick();
        }
        if (this.phase == Phase.SETTLED) {
            // Idempotent rather than throwing: the executor advances its index when it sees Finished, and a tick that
            // crosses that boundary must not be the thing that ends the run.
            return new Directive.Finished(this.action.kind() + " already settled");
        }

        Directive screens = handleScreens(obs);
        if (screens != null) {
            return screens;
        }

        return bounded(obs, switch (this.action) {
            case BuildAction.Interact interact -> tickInteract(interact, obs);
            case BuildAction.WriteSign sign -> tickWriteSign(sign, obs);
            case BuildAction.FillFluid fluid -> tickFillFluid(fluid, obs);
            // Unreachable: the constructor refuses everything else. Stated rather than defaulted so that a ninth
            // action type is a compile error here instead of an action that silently does nothing.
            default -> blocked("no runner for " + this.action.kind());
        });
    }

    /**
     * A ceiling on standing still, applied to whatever the action's own branch decided.
     *
     * <p>{@link Directive.Aim} carries {@link #AIM_PATIENCE_TICKS} and every {@link Directive.Wait} the sign branch
     * emits is bounded by {@link #SCREEN_WAIT_TICKS} or {@link #CLICK_RESPONSE_TICKS}. Three are not bounded by
     * anything: "slot N selected, this needs slot M", "eating", and "place helper throttled". None of them is
     * something the runner can act on — it touches no inventory and cannot stop the bot chewing — so each is a wait
     * that prints its own reason once a tick and, left alone, prints it for ever.
     *
     * <p>The counter is over CONSECUTIVE waits and is reset by any directive that does something, so a counted
     * interaction that clicks, waits for the effect, and clicks again never approaches it. Ten seconds: past two
     * helpings of food and every place cooldown, and finite, which is the property that matters.
     */
    private Directive bounded(Observation obs, Directive decided) {
        if (!(decided instanceof Directive.Wait waiting)) {
            this.waitingSinceTick = NEVER;
            this.lastWaitTick = NEVER;
            return decided;
        }
        // Contiguity, exactly as requireAim measures it: the executor does not tick the runner on a tick it spends
        // cancelling a live path, and counting wall ticks across that gap would charge the wait for time it was not
        // even asked a question.
        if (this.waitingSinceTick == NEVER || obs.tick() != this.lastWaitTick + 1L) {
            this.waitingSinceTick = obs.tick();
        }
        this.lastWaitTick = obs.tick();
        long waited = obs.tick() - this.waitingSinceTick + 1L;
        if (waited > WAIT_PATIENCE_TICKS) {
            return blocked(this.action.kind() + " at " + BuildAction.describePos(this.action.cell())
                    + " waited " + waited + " consecutive ticks (patience " + WAIT_PATIENCE_TICKS
                    + ") on a condition it cannot change: " + waiting.why());
        }
        return decided;
    }

    /**
     * The one rule that applies to every action: while a screen is open, the world is not reachable.
     *
     * <p>Returns {@code null} when nothing is open and the action may proceed. For {@link BuildAction.WriteSign} an
     * open editor is not an obstacle but the point, so that case is handed to its own branch.
     */
    private Directive handleScreens(Observation obs) {
        ScreenState screen = obs.screen();
        if (!screen.anyOpen()) {
            return null;
        }
        if (this.action instanceof BuildAction.WriteSign) {
            return null;    // its branch decides; an open editor is what it has been waiting for
        }
        // Anything else: a click now goes into a GUI, and a container left open silently freezes every future hotbar
        // swap. Closing is the only outcome that does not end in a stall.
        return new Directive.CloseScreen("a " + (screen.signEditor() ? "sign editor" : "screen")
                + " is open and " + this.action.kind() + " needs the world");
    }

    // ------------------------------------------------------------------------------------------------ INTERACT

    /**
     * Right-click a placed block N times to reach a state placement cannot set.
     *
     * <p>The remaining count is re-derived from the LIVE state on every tick rather than decremented, which makes the
     * loop self-correcting in the two ways it needs to be: a click the server swallowed leaves the count where it
     * was, and a click too many is absorbed by the same wrap-around arithmetic that produced the count in the first
     * place. Decrementing a planned number would be right until the first disagreement and wrong forever after.
     */
    private Directive tickInteract(BuildAction.Interact interact, Observation obs) {
        // No hand requirement. Vanilla decides between "use the block" and "place what I am holding" by the CROUCH,
        // not by the hand: a right-click on a repeater steps its delay whatever the hand holds, and only a sneaking
        // click places instead. Interact actions are emitted with sneak=false for exactly that reason, so the held
        // item is irrelevant and demanding a non-placing one only cost a hotbar slot the schedule needs.
        //
        // This used to be a fatal check, on the theory that a ray which has slipped one face off the target would
        // place the held block. requireAim below is what actually answers that: the crosshair is re-derived from the
        // live ray and has to be on the intended face of the intended block before any click goes out, so a slipped
        // ray never reaches the click regardless of what is in the hand. The check was belt on top of braces, and it
        // cost a full basalt run -- 947 blocks in, with 1080 repeaters left unset, because the bench stocks slot 0
        // with material and no pickaxe exists to switch to.
        if (obs.selectedSlot() != this.posture.handSlot()) {
            return new Directive.Wait(this.posture.sneak(), "slot " + obs.selectedSlot()
                    + " selected, interaction needs slot " + this.posture.handSlot());
        }

        int remaining = PlacementGeometry.interactionClicks(obs.liveState(), interact.target());
        if (remaining < 0) {
            return blocked("no longer interaction-fixable at " + BuildAction.describePos(interact.cell()) + ": "
                    + BuildAction.describeState(obs.liveState()) + " cannot be stepped to "
                    + BuildAction.describeState(interact.target()));
        }
        if (remaining == 0) {
            return hold(obs, "interaction at " + BuildAction.describePos(interact.cell()));
        }
        if (this.phase == Phase.HOLDING) {
            // The state slipped back out of the target between the confirmation and the hold expiring — redstone
            // moved it, or the server reverted the click. Back to work rather than declaring it done.
            this.phase = Phase.WORKING;
            this.phaseTick = obs.tick();
        }

        Directive pending = awaitEffect(obs, "interaction");
        if (pending != null) {
            return pending;
        }
        Directive aimed = requireAim(interact.cell(), interact.rotation(), interact.face(), obs,
                this.posture.sneak());
        if (aimed != null) {
            return aimed;
        }
        if (obs.consuming()) {
            // While eating, InputOverrideHandler ticks both helpers with false. The click would not merely be
            // skipped; it would be eaten along with anything pending.
            return new Directive.Wait(this.posture.sneak(),
                    "eating — both helpers are ticked false while consuming");
        }
        if (obs.placeThrottled()) {
            return new Directive.Wait(this.posture.sneak(),
                    "place helper throttled; a click sent now is discarded in silence");
        }
        return click(obs, new Directive.ClickRight(this.posture.handSlot(), this.posture.sneak(), false,
                remaining + " right-click(s) left to " + BuildAction.describeState(interact.target())));
    }

    // ---------------------------------------------------------------------------------------------- WRITE_SIGN

    /**
     * Fill in a sign's four lines and confirm.
     *
     * <p>The editor is not opened by this action — it was opened by the server when the sign landed, which is why
     * this action sits directly behind the placement in the frozen order rather than in a pass afterwards. Placing a
     * sign and writing it are one event as far as vanilla is concerned, and separating them is how the text gets lost.
     */
    private Directive tickWriteSign(BuildAction.WriteSign sign, Observation obs) {
        if (sign.lines().isEmpty()) {
            return blocked("a sign action with no lines at " + BuildAction.describePos(sign.cell())
                    + " — the planner emitted an action whose confirmation cannot arrive");
        }
        ScreenState screen = obs.screen();

        if (this.signTyped) {
            // Typing routes through the screen's own onClose, so the editor closing IS the confirmation that the
            // text went out. A screen still up a tick later is normal; still up much later is not.
            if (!screen.anyOpen()) {
                return finished("sign written at " + BuildAction.describePos(sign.cell()));
            }
            if (this.phaseTick != NEVER && obs.tick() - this.phaseTick > SCREEN_WAIT_TICKS) {
                return blocked("the sign editor at " + BuildAction.describePos(sign.cell())
                        + " did not close after the text was typed");
            }
            return new Directive.Wait(this.posture.sneak(), "sign editor closing");
        }

        if (!screen.anyOpen()) {
            if (this.startedTick != NEVER && obs.tick() - this.startedTick > SCREEN_WAIT_TICKS) {
                return blocked("no sign editor opened for " + BuildAction.describePos(sign.cell())
                        + " within " + SCREEN_WAIT_TICKS + " ticks — a waxed sign, a protection plugin, or a server "
                        + "that suppresses the editor; the sign stands blank");
            }
            return new Directive.Wait(this.posture.sneak(), "waiting for the sign editor to open");
        }
        if (!screen.signEditor()) {
            return new Directive.CloseScreen("a non-sign screen opened over the sign at "
                    + BuildAction.describePos(sign.cell()));
        }
        // Typing blind is how one sign's text lands on its neighbour. When the accessor cannot name the sign the
        // position is null, and an unnamed editor is refused rather than guessed at.
        if (screen.signPos() == null) {
            return blocked("the open sign editor does not say which sign it edits; refusing to type into it");
        }
        if (!screen.signPos().equals(sign.cell())) {
            return new Directive.CloseScreen("the open sign editor belongs to "
                    + BuildAction.describePos(screen.signPos()) + ", not to "
                    + BuildAction.describePos(sign.cell()));
        }
        if (screen.signFront() != sign.frontSide()) {
            // Which side opens is the game's decision, not ours: SignItem opens a freshly placed sign's FRONT
            // unconditionally, and the back opens only for a player standing behind it. A plan asking for the back
            // needs a stance behind the sign; it is reported here rather than typed onto the wrong face.
            return blocked("the editor at " + BuildAction.describePos(sign.cell()) + " is editing the "
                    + (screen.signFront() ? "front" : "back") + " and the plan asks for the "
                    + (sign.frontSide() ? "front" : "back") + " — the back side is reachable only by right-clicking "
                    + "the sign from behind it");
        }

        this.signTyped = true;
        this.phaseTick = obs.tick();
        return new Directive.TypeSign(sign.cell(), sign.lines(), sign.frontSide());
    }

    // ---------------------------------------------------------------------------------------------- FILL_FLUID

    /**
     * Empty a bucket into a cell.
     *
     * <p>Sneaking, and that is the whole subtlety: {@code BucketItem} empties through {@code Item.use}, which vanilla
     * reaches only once the clicked block's own {@code useItemOn} has declined. Standing, a bucket aimed at a chest
     * opens the chest instead — and an open container means no hotbar swap can ever be granted again.
     */
    private Directive tickFillFluid(BuildAction.FillFluid fluid, Observation obs) {
        if (FluidPlan.satisfies(obs.liveState(), fluid.expected())) {
            return hold(obs, "fluid at " + BuildAction.describePos(fluid.cell()));
        }
        if (this.phase == Phase.HOLDING) {
            this.phase = Phase.WORKING;
            this.phaseTick = obs.tick();
        }
        if (obs.heldItem() != fluid.bucket()) {
            // Item identity, never the slot number: the hotbar moves under the plan and the place helper compares the
            // item, so a click with the right slot and the wrong item is voided with no log line.
            if (this.startedTick != NEVER && obs.tick() - this.startedTick > CLICK_RESPONSE_TICKS) {
                return blocked("the " + BuildAction.describeItem(fluid.bucket()) + " for "
                        + BuildAction.describePos(fluid.cell()) + " never reached the hand; the hand holds "
                        + BuildAction.describeItem(obs.heldItem()));
            }
            return new Directive.Wait(this.posture.sneak(), "waiting for "
                    + BuildAction.describeItem(fluid.bucket()) + " in slot " + this.posture.handSlot());
        }
        if (fluid.against().equals(fluid.cell())
                && !fluid.cell().equals(FluidPlan.destination(
                        obs.liveState(), fluid.against(), fluid.face(), fluid.bucket()))) {
            return blocked("the live block at " + BuildAction.describePos(fluid.cell())
                    + " can no longer be waterlogged with " + BuildAction.describeItem(fluid.bucket()) + ": "
                    + BuildAction.describeState(obs.liveState()));
        }

        Directive pending = awaitEffect(obs, "bucket");
        if (pending != null) {
            return pending;
        }
        Directive aimed = requireAim(fluid.against(), fluid.rotation(), fluid.face(), obs, this.posture.sneak());
        if (aimed != null) {
            return aimed;
        }
        if (obs.consuming()) {
            return new Directive.Wait(this.posture.sneak(),
                    "eating — both helpers are ticked false while consuming");
        }
        if (obs.placeThrottled()) {
            return new Directive.Wait(this.posture.sneak(),
                    "place helper throttled; a click sent now is discarded in silence");
        }
        return click(obs, new Directive.ClickRight(this.posture.handSlot(), this.posture.sneak(), false,
                "emptying " + BuildAction.describeItem(fluid.bucket()) + " into "
                        + BuildAction.describePos(fluid.cell())));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /**
     * The aim gate: result equivalence against the LIVE ray, never angle equality against the planned one.
     *
     * <p>Both halves are needed and they answer different questions. The rotation test says the head has stopped
     * moving; the ray test says it has stopped in the right place. Testing only the angle clicks on a ray that is one
     * tick stale — the rotation requested in tick N is written in {@code onPlayerUpdate}, which runs after
     * {@code onTick} has already dispatched, so it is raycast-visible only in tick N+1. Testing only the ray clicks
     * on a head that is still swinging, and the click reaches the server at an angle the planner never computed.
     *
     * @param target the block the live ray must hit; for a bucket this is its solid neighbour, not the destination
     * @param face   {@code null} when the action does not care which face was hit
     * @return the directive to return this tick, or {@code null} when the aim is good and the caller may proceed
     */
    private Directive requireAim(BlockPos target, Rotation planned, Direction face, Observation obs, boolean sneak) {
        // The angle the head is being STEERED to, which is the only angle it can be expected to arrive at. It equals
        // the planned one unless the executor re-derived it from the live eye; either way the ray test below is what
        // decides correctness, and this one only decides whether the head has stopped moving.
        Rotation driven = obs.aimRotation() == null ? planned : obs.aimRotation();
        boolean settled = obs.liveRotation() != null
                && obs.liveRotation().isCloseTo(driven, YAW_TOLERANCE, PITCH_TOLERANCE);
        String waiting = null;
        if (!settled) {
            waiting = "head still turning toward " + BuildAction.describePos(target);
        } else if (!target.equals(obs.rayHitCell())) {
            waiting = "head settled, live ray hits "
                    + (obs.rayHitCell() == null ? "nothing" : BuildAction.describePos(obs.rayHitCell()))
                    + ", not " + BuildAction.describePos(target);
        } else if (face != null && obs.rayFace() != face) {
            waiting = "live ray hits face " + BuildAction.describeFace(obs.rayFace()) + ", the plan needs "
                    + BuildAction.describeFace(face) + " on " + BuildAction.describePos(target);
        }
        if (waiting == null) {
            resetAimPatience();
            return null;
        }
        if (this.lastAimTick == NEVER || obs.tick() != this.lastAimTick + 1L) {
            this.aimStartedTick = obs.tick();
        }
        this.lastAimTick = obs.tick();
        long waited = this.aimStartedTick == NEVER ? 1L : obs.tick() - this.aimStartedTick + 1L;
        if (waited >= AIM_PATIENCE_TICKS) {
            return blocked("the live ray did not reach " + BuildAction.describePos(target) + " face "
                    + BuildAction.describeFace(face) + " after " + waited + " consecutive ticks: " + waiting);
        }
        return new Directive.Aim(driven, sneak, waiting + " (" + waited + "/" + AIM_PATIENCE_TICKS + ")");
    }

    private void resetAimPatience() {
        this.aimStartedTick = NEVER;
        this.lastAimTick = NEVER;
    }

    /**
     * After a click: has the world moved yet?
     *
     * <p>A click whose effect never appears is re-sent up to {@link #MAX_CLICK_ATTEMPTS} times and then named. It is
     * re-sent rather than assumed lost because the two indistinguishable causes — the server discarded it, or the
     * client throttle ate it — both call for the same answer, and it is named rather than re-sent forever because a
     * retry loop with no ceiling is how a run freezes for 1740 ticks without producing a line to read.
     *
     * @return the directive to return this tick, or {@code null} when the caller may proceed to click again
     */
    private Directive awaitEffect(Observation obs, String what) {
        if (this.phase != Phase.AWAITING_EFFECT) {
            return null;
        }
        if (!obs.liveState().equals(this.stateAtClick)) {
            // The world moved. One step of the action is confirmed by the world rather than by the click count.
            this.confirmed++;
            this.attempts = 0;
            this.phase = Phase.WORKING;
            this.phaseTick = obs.tick();
            return null;
        }
        if (this.phaseTick != NEVER && obs.tick() - this.phaseTick <= CLICK_RESPONSE_TICKS) {
            return new Directive.Wait(this.posture.sneak(), "waiting for the " + what + " click to land");
        }
        if (++this.attempts >= MAX_CLICK_ATTEMPTS) {
            return blocked("the " + what + " click at " + BuildAction.describePos(this.action.cell())
                    + " produced no change after " + this.attempts + " attempts; the state is still "
                    + BuildAction.describeState(obs.liveState()));
        }
        this.phase = Phase.WORKING;
        this.phaseTick = obs.tick();
        return null;
    }

    /** Record that a click is going out this tick and start waiting for its effect. */
    private Directive click(Observation obs, Directive directive) {
        this.stateAtClick = obs.liveState();
        this.phase = Phase.AWAITING_EFFECT;
        this.phaseTick = obs.tick();
        return directive;
    }

    /**
     * The world says what it should. Count out {@link #CONFIRM_HOLD_TICKS} before believing it.
     *
     * <p>The client renders a placement the moment it predicts one; a server with a protection plugin reverts it two
     * to six ticks later, and by then a builder that trusted its own prediction has walked away. Three ticks of the
     * state simply continuing to be true is the cheapest thing that distinguishes the two.
     */
    private Directive hold(Observation obs, String what) {
        if (this.phase != Phase.HOLDING) {
            this.phase = Phase.HOLDING;
            this.phaseTick = obs.tick();
        }
        // Ticks the state has been OBSERVED true, the entering tick included — three observations, not three ticks of
        // waiting after one. The distinction is a whole tick per action across fifteen thousand of them, and the
        // guarantee is the same: the state was still true two ticks after it first appeared.
        long held = this.phaseTick == NEVER ? 1L : obs.tick() - this.phaseTick + 1;
        if (held < CONFIRM_HOLD_TICKS) {
            return new Directive.Wait(this.posture.sneak(), String.format(Locale.ROOT,
                    "holding %s (%d/%d)", what, held, CONFIRM_HOLD_TICKS));
        }
        return finished(what);
    }

    private Directive finished(String what) {
        this.phase = Phase.SETTLED;
        return new Directive.Finished(what);
    }

    private Directive blocked(String reason) {
        this.phase = Phase.SETTLED;
        return new Directive.Blocked(reason);
    }
}
