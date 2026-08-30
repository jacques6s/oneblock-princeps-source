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

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import princeps.Princeps;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.utils.Helper;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.MovementHelper;
import princeps.process.builder.BlockItemPlacementHelper;
import princeps.pathing.precompute.Ternary;
import princeps.utils.BlockBreakHelper;
import princeps.utils.BlockPlaceHelper;
import princeps.utils.PathingCommandContext;
import princeps.utils.accessor.ISignEditScreen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The executor. It makes no decisions — it runs the frozen order, and it reports divergence.
 *
 * <h2>One owner per state</h2>
 * <p>This table is the whole design, and it is the fix for the cell a traced V2 run broke and rebuilt forty-eight
 * times. Nothing there was broken in isolation: movement, look and hotbar each had two consumers with no precedence,
 * so the recovery path and the normal path took turns undoing each other, and the trace showed a builder fighting
 * itself with no single line to point at.
 *
 * <pre>
 * state       movement      look          hotbar
 * SELECT      -             -             -
 * EQUIP       -             -             executor
 * TRAVEL      pathfinder    pathfinder    locked
 * APPROACH    executor      executor      locked
 * AIM         still         executor      locked
 * GATE/CLICK  still         executor      locked
 * CONFIRM     still         executor holds locked
 * </pre>
 *
 * <p>"Locked" means the executor selected the slot in EQUIP and nothing may change it afterwards — a swap between the
 * plan and the click voids the click silently, because {@code BlockPlaceHelper.matches} compares item identity.
 * "Executor holds" in CONFIRM means the aim is re-issued every tick rather than released: the look target auto-clears
 * at {@code PlayerUpdateEvent.POST}, so letting go is an active choice that would let the head drift while the server
 * is still deciding.
 *
 * <h2>The five traps this class exists to not fall into</h2>
 * <ul>
 *   <li>{@code expectMainHandPlacement} is read AND nulled before the "was a right-click requested" gate, so the
 *       commit and the forced {@code CLICK_RIGHT} must happen in the same {@code onTick}. They do, in
 *       {@link #fireClick}, three statements apart with nothing between them.</li>
 *   <li>{@code CLICK_LEFT} clears {@code CLICK_RIGHT} before the helpers tick. One action is executed at a time and
 *       breaks and places are different action kinds, so the two keys are never forced in one tick — structurally,
 *       not by a check that could be removed.</li>
 *   <li>{@code SurvivalBehavior.isConsuming()} feeds both helpers {@code tick(false)}, which destroys a pending
 *       commit. It is a gate condition, so the click is never armed while it holds.</li>
 *   <li>Manual WASD is a two-phase handoff. Killing a live path and setting keys in the same tick wipes the keys —
 *       measured as a 1740-tick freeze with no log line. {@link #pathAlive()} splits the two phases for every state
 *       that forces a key, not only for the one that walks.</li>
 *   <li>{@code BlockBreakHelper} has no target and no expectation: it breaks whatever the crosshair rests on. So a
 *       break converges and VERIFIES the live ray before {@code CLICK_LEFT} is enabled, and it refuses a position the
 *       helper has blacklisted rather than holding the key against it forever.</li>
 * </ul>
 *
 * <h2>The one thing it does not run itself</h2>
 * <p>{@code INTERACT}, {@code WRITE_SIGN} and {@code FILL_FLUID} are handed to {@link ActionRunner} from {@code AIM}
 * onward — {@link ActionRunner#owns} is the only routing rule and this class is the only caller of it. Everything
 * before that is still done here, because a right-click needs the hotbar locked, a path to the proven stance and the
 * exact sub-block approach point, and the runner touches no {@code ctx} by design: the decisions it makes are the
 * ones that fail SILENTLY — a repeater left at delay 1, a blank sign, a bucket in the wrong cell — and a class that
 * reads {@code ctx} cannot be tested at all.
 *
 * <p>Every PLACEMENT and every BREAK stays here, {@code REMOVE_SCAFFOLD} included. That is not a division by
 * seniority: a break here is armed against {@link ServerAck} and cannot start while the acknowledgement channel is
 * unavailable, and the runner has no acknowledgement seam, so routing a destruction there would make server evidence
 * optional for the one operation that cannot be undone.
 *
 * <h2>Divergence instead of watchdogs</h2>
 * <p>Before every action the plan's preconditions are checked against the LIVE world — target still free, click
 * neighbour still there and still presenting that face, stance still standable, ray still clear. A mismatch is a
 * divergence and a re-plan, immediately, with no backoff and no retry ladder. That is eight state reads, and it
 * replaces every watchdog, every deferral and every retirement list the previous engine carried.
 *
 * <p>The waiting states do carry patience counters, and they are not watchdogs in disguise: exceeding one produces a
 * divergence, i.e. a re-plan against the world as it now is, which is the same answer the design gives to everything
 * else. Nothing here waits forever and nothing here quietly sets a cell aside.
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It does not pause. The bench scores {@code isPaused() == true} as a failure verdict, so an honest "blocked at
 * cell X" says so through {@link Outcome} and lets the process stand down. It does not read
 * {@code Princeps.settings()}. It does not touch {@link PlannedBuilderProcess} — the process wires it in, hands it the
 * plan, and translates {@link Outcome} into its own counters and its own stand-down.
 */
public final class CellExecutor implements Helper {

    // ------------------------------------------------------------------------------------------------- constants

    /** Compatibility name for the minimum hold owned by {@link ServerAck}. The executor never applies a second hold:
     *  {@link Acknowledgements.Ack#CONFIRMED} already means server evidence plus the adaptive hold completed. */
    public static final int CONFIRM_HOLD_TICKS = ServerAck.MIN_HOLD_TICKS;

    /**
     * Last-resort guard for a broken acknowledgement adapter. Production {@link ServerAck} settles much earlier, with
     * its own latency-adaptive timeout (at most roughly 163 ticks with the capped RTT sample). This larger bound never
     * races a legitimate slow server; it only prevents an implementation returning UNKNOWN forever.
     */
    private static final int ACK_FAILSAFE_TIMEOUT_TICKS = 240;

    /** Ticks the fine approach may spend before the stance is treated as unreachable in practice. Sneak friction
     *  settles in one to three ticks and the distance is a fraction of a block, so this is an order of magnitude of
     *  headroom, not a budget the normal case spends. */
    private static final int APPROACH_PATIENCE_TICKS = 60;

    /**
     * How far the body's physical feet plane may sit from the proven one, in blocks — checked ON ARRIVAL, and that
     * placement is the whole of the repair, not the number.
     *
     * <p>A stance CELL does not have one foot plane. Basalt run {@code 701fed58} ended at 464 of 15 004 cells on
     * stance {@code 91,-59,105}: soul sand beneath it tops out at {@code -59.125}, the blue ice one cell west tops out
     * at {@code -59.000}, and a 0.6-wide body straddling the boundary rests on the ice. The pathfinder left the body
     * at {@code x = 91.298} — a footprint reaching {@code 90.998}, two millimetres of ice — and the plane check, which
     * used to run on the tick the body entered the cell, called that a divergence against the plan's {@code -59.125}.
     * Three re-plans later the run stood down. Every one of the twelve ticks in the 699-file trace corpus where the
     * feet cell sat over soul sand is such a straddling position, and every one measured {@code -59.000}.
     *
     * <p>Neither side of that was wrong. {@link PlacementOracle#footY} answers for the point the planner picked and
     * answers correctly, and {@code MixedFootPlaneStanceTest} shows the granted arrival ball never straddles the two
     * planes — the body simply had not walked into it yet. The fine approach exists to cover exactly that fraction of
     * a block. So the plane is now required where the plan claims it: inside the ball.
     *
     * <p>0.02 unchanged, and it is not a settle allowance — the body is on the ground and inside the tolerance by the
     * time it is read. It absorbs the round trip of the approach point through a text plan file, which prints two
     * decimals.
     */
    private static final double FOOT_PLANE_EPSILON = 0.02D;

    /** Ticks the aim may spend converging. The build turn profile is three ticks per 90 degrees; a full reversal plus
     *  the one-tick raycast lag is well inside this. */
    private static final int AIM_PATIENCE_TICKS = 40;

    /**
     * Ticks the SENT look axis must have stood still before AIM hands over, for the blocks whose landed facing the
     * SERVER derives from ITS OWN copy of the rotation.
     *
     * <p>{@code ServerboundUseItemOnPacket} carries hand, hit and sequence — no rotation. Read that against its
     * sibling: {@code ServerboundUseItemPacket} does carry yRot/xRot and {@code handleUseItemOn} applies it, while
     * {@code handleUseItemOn} never touches the rotation at all. So the server answers
     * {@code getNearestLookingDirection} out of the entity copy that only {@code handleMovePlayer} fills — and the
     * click leaves the client in {@code Minecraft.tick():377} while the rotation packet of that same tick only goes
     * out at {@code :440}. The skew is structural, not latency.
     *
     * <p>Measured over 869 piston clicks in three runs, with D counted to and including the click tick: 55 refusals,
     * every one at D &lt;= 4, and none in 502 clicks at D &gt;= 5. In 49 of 49 the server's facing was exactly the
     * opposite of the axis the client held three to four ticks earlier. The hold is five and not three because one
     * refusal needed a six-tick reach-back, and the clean band above five rests on only 35 samples.
     *
     * <p>{@code D = this + 2}: AIM hands over, GATE runs, CLICK runs.
     */
    private static final int AXIS_FACING_LOOK_HOLD_TICKS = 5;

    /**
     * After this many ticks of a converged aim the click goes out regardless of the hold.
     *
     * <p>The fuse is what keeps the hold from ever being worse than no hold: it degrades to today's behaviour — a
     * click at whatever D was reached — instead of to a divergence and its re-plan. It cannot reach
     * {@link #AIM_PATIENCE_TICKS}, and that is measured rather than argued: over 2748 AIM episodes the longest was
     * nine ticks and p99 was eight, so convergence plus this fuse is seventeen of forty in the worst case seen.
     */
    private static final int AXIS_FACING_LOOK_HOLD_CAP_TICKS = 8;

    /** Ticks the gate may keep saying WAIT. Covers the place cooldown, a mob wandering through the cell, and a bite
     *  of food. Beyond it the reason is structural and a re-plan is the answer. */
    private static final int GATE_PATIENCE_TICKS = 60;

    /**
     * Ticks EQUIP may spend. {@link #SWAP_ATTEMPTS} swaps at {@link #SWAP_COOLDOWN_TICKS} apart is twenty, so this is
     * ten times the working case — and it is here because the two conditions EQUIP waits on are both silent:
     * a body that never {@link FineApproach#settled} never spends an attempt at all, and the attempt counter it would
     * otherwise be bounded by is therefore never reached.
     */
    private static final int EQUIP_PATIENCE_TICKS = 200;

    /**
     * Ticks one TRAVEL leg may take. Two minutes: a leg is one cell of one layer, the pathfinder has already proved
     * the stance reachable at plan time, and a walk that has not arrived in two minutes is not walking.
     *
     * <p>Generous because TRAVEL is the one state whose honest duration is a distance, and stingy compared with the
     * 44 000 ticks the run before this one spent standing still.
     */
    private static final int TRAVEL_PATIENCE_TICKS = 2400;

    /** Ticks CLICK may last. The click went out; the next tick enters CONFIRM. Anything above a couple of ticks here
     *  means the transition itself is stuck. */
    private static final int CLICK_PATIENCE_TICKS = 20;

    /**
     * Ticks CONFIRM may last. Above {@link #ACK_FAILSAFE_TIMEOUT_TICKS} times four, which is the longest failsafe any
     * confirmation carries (a hard block being broken), so this never pre-empts a more precise sentence — it only
     * catches a path through CONFIRM that reaches no failsafe at all.
     */
    private static final int CONFIRM_PATIENCE_TICKS = ACK_FAILSAFE_TIMEOUT_TICKS * 5;

    /**
     * Ticks a state that is pure bookkeeping may last.
     *
     * <p>Was 2, on the reasoning that SELECT falls through into EQUIP inside the same tick so anything above zero is
     * already pathological. The reasoning is right about the happy path and wrong about what a guard is for. It ended
     * a 4271-cell basalt run after 370 placements with
     * {@code SELECT waited 3 ticks (patience 2)} — three ticks after a re-plan, three re-plans in a row, and the build
     * stopped. A guard that fires on the engine's own correct behaviour is worse than no guard: it converts slack into
     * a halt, and the halt looks exactly like the pathology it was written to catch.
     *
     * <p>The number is now calibrated to the FAILURE it detects rather than to the success it interrupts. What it
     * exists for is the freeze that cost the run before this one: 44 000 ticks, velocity zero, not a line in the log.
     * Any value from twenty to two hundred catches that identically and instantly on a human clock, while two catches
     * a re-plan boundary as well.
     *
     * <p>Honest limit of this change: the exact path that holds SELECT for three ticks after a re-plan is NOT
     * explained. Every early return in {@code onTick} that leaves the state at SELECT either advances the action —
     * which resets this clock through {@code enter} — or diverges. The margin makes the guard correct for its purpose
     * regardless; it does not make the three ticks understood, and that is still worth understanding.
     */
    private static final int BOOKKEEPING_PATIENCE_TICKS = 40;

    /**
     * Ticks an open screen may hold the whole tick before the wait is the divergence.
     *
     * <p>Its own number because it is its own wait: it happens BEFORE the state machine is dispatched at all, so no
     * state's patience is running and the state clock is not even moving. Five seconds is far past any container
     * animation and far short of forever.
     */
    private static final int SCREEN_PATIENCE_TICKS = 100;

    /**
     * Ticks ONE action may take end to end, whatever states it passes through.
     *
     * <p>The structural guarantee, and the reason the per-state numbers above are not the whole answer. Every one of
     * them is measured from {@link #stateEnteredTick}, which {@link #enter} resets — so two states that hand the
     * action back and forth reset each other for ever and neither counter ever fires. That is not hypothetical:
     * APPROACH returns to TRAVEL whenever the feet leave the stance cell, and TRAVEL returns to APPROACH the moment
     * they are back in it, and a body oscillating on the boundary rides that loop with both patiences permanently at
     * zero.
     *
     * <p>Five minutes. Nothing legitimate comes close — the longest honest action in the engine is a hard block being
     * broken, bounded at 960 ticks by its own failsafe — and it is finite, which is the only property this constant
     * is really for.
     */
    private static final int ACTION_PATIENCE_TICKS = 6000;

    /** A state that cannot wait because it is terminal. Not {@link Integer#MAX_VALUE} as a number to compare against:
     *  it is the sentinel {@link #patienceTicks} returns for the three states the executor never ticks. */
    static final int TERMINAL_STATE = -1;

    /** Ticks between two container clicks of our own. The executor does its own throttling rather than reading
     *  {@code ticksBetweenInventoryMoves}, because it may not read settings at all — and because the plan's swaps are
     *  Belady-minimal, so there are few enough of them that a fixed, conservative cadence costs nothing. */
    private static final int SWAP_COOLDOWN_TICKS = 4;

    /** How many times a planned swap may be re-clicked before it is a divergence. */
    private static final int SWAP_ATTEMPTS = 5;

    /** Yaw and pitch tolerance for "the head has arrived". Never {@code isReallyCloseTo}: its tolerance is 0.01
     *  degrees and the humanized look carries up to 0.42 of yaw tremor, so it never fires — a silent infinite stall,
     *  not a crash. */
    private static final float YAW_TOLERANCE = 0.75F;

    private static final float PITCH_TOLERANCE = 0.55F;

    /** Added to a pitch that would otherwise exactly equal the current one. A pitch equal to the live pitch is read
     *  as "pitch-agnostic" and dragged toward the walking pitch band at one degree a tick, which walks the crosshair
     *  off the face while the gate waits for it. {@code RotationUtils.reachable} defends with the same epsilon. */
    private static final float PITCH_EPSILON = 0.0001F;

    // ------------------------------------------------------------------------------------------------- seams

    /**
     * Server acknowledgement. Arming lives on this seam as well as polling: a placement is armed immediately before
     * its one forced click, and a break is armed immediately before the first held left-click. That ordering is what
     * lets {@link ServerAck} associate the outbound packet sequence with the correct action.
     */
    @FunctionalInterface
    public interface Acknowledgements {

        enum Ack {

            /** No answer yet — keep holding. */
            UNKNOWN,

            /** The server confirmed the change at this position. */
            CONFIRMED,

            /** The server sent the cell back to something else. */
            REVERTED,

            /** The latency-adaptive server window elapsed without authoritative evidence. */
            TIMED_OUT,

            /** A pending expectation was deliberately cleared, for example by pause or reset. */
            CANCELLED,

            /** The local helper/controller never emitted the matching packet. */
            NOT_SENT,

            /** The expectation's chunk unloaded before it could be verified. */
            UNLOADED,

            /** No packet-backed acknowledgement service is installed. No world-changing action may start. */
            UNAVAILABLE
        }

        /** Whether a world-changing action may be armed at all. */
        default boolean available() {
            return false;
        }

        /**
         * True while a placement has authoritative server evidence but its revert watch has not expired yet.
         *
         * <p>The state the executor used to spend standing still in. Evidence in hand means the next cell may be
         * selected, walked to, aimed at and equipped for; only the next CLICK has to wait, because a click is the one
         * act that cannot be taken back if the watch turns out badly.
         */
        default boolean watching() {
            return false;
        }

        /** Arm one placement before the forced right-click. */
        default boolean expectPlacement(BlockPos cell, BlockPos against, Direction face, BlockState expectedAfter,
                                        long armedTick) {
            return false;
        }

        /** Arm one break before the first forced left-click. */
        default boolean expectBreak(BlockPos cell, BlockState expectedAfter, long armedTick) {
            return false;
        }

        /**
         * Arm one sign-text update in the same tick the four lines go into the editor.
         *
         * <p>The only acknowledgement in the engine that is about metadata rather than about a block. It matters more
         * than the others, not less: a sign whose text never arrived still STANDS, so the cell reads as satisfied, the
         * re-plan emits nothing for it, and the build reports success with a blank sign in it.
         *
         * @param exactLines exactly {@link SignNbt#LINES} lines, blanks included — the same four the editor will send
         */
        default boolean expectSign(BlockPos cell, boolean frontSide, List<String> exactLines, long armedTick) {
            return false;
        }

        /**
         * @param cell      the cell a click was aimed at
         * @param armedTick the executor tick the click went out on
         */
        Ack poll(BlockPos cell, long armedTick);

        /** Cancel and forget the active expectation. */
        default void clear() {
        }

        /** Safe default: without packet plumbing no irreversible click is permitted. */
        Acknowledgements NONE = (cell, armedTick) -> Ack.UNAVAILABLE;
    }

    /** Everything the process wants to count, as it happens. Every method is a no-op by default so a caller can take
     *  only what it needs, and none of them may throw — they run inside the tick. */
    public interface Listener {

        /** A click went out. One per placement in a run with no divergences; that is the fidelity numerator. */
        default void onClickSent(BuildAction action) {
        }

        /** The cell holds what the plan wanted and held it for {@link #CONFIRM_HOLD_TICKS}.
         *
         *  @param ideal the whole action ran plan-to-confirmation with one click and no divergence and no re-equip */
        default void onCellConfirmed(BuildAction action, boolean ideal) {
        }

        /** Something landed at the cell and it is not what was wanted. */
        default void onLandedWrong(BuildAction action, BlockState found) {
        }

        /** A block was broken and the world confirms it gone. */
        default void onBlockBroken(BlockPos cell) {
        }

        /** A TRAVEL leg began. */
        default void onWalkStarted(BuildAction action) {
        }

        /** A TRAVEL leg ended at the stance it was aimed at, rather than being abandoned. */
        default void onWalkArrived(BuildAction action) {
        }

        /** The live world contradicted the plan. */
        default void onDivergence(BuildAction action, String reason) {
        }

        Listener NONE = new Listener() {
        };
    }

    /** What the executor did this tick, and what the process must do about it. */
    public enum Status {

        /** Carry on; {@link Outcome#command()} is the pathing command to return. */
        RUNNING,

        /** Every action is done. */
        FINISHED,

        /** The live world disagrees with the plan. Re-plan from the current world, immediately. */
        DIVERGED,

        /** Named, terminal, loud. The process stands down; it must not pause. */
        BLOCKED
    }

    /**
     * @param command never null — the control manager throws on a null command from an active process
     * @param reason  why, for DIVERGED and BLOCKED; null otherwise
     */
    public record Outcome(Status status, PathingCommand command, State state, String reason) {

        public Outcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(command, "command");
        }
    }

    /** The cell automaton, mirroring {@link PlannedBuilderProcess.State} one for one. Declared here rather than
     *  reused from there because the executor is the thing that HAS states and the process only reports them; the
     *  process's copy is what its trace column prints and the two are checked against each other in
     *  {@link #processState()}. */
    public enum State {
        SELECT, EQUIP, TRAVEL, APPROACH, AIM, GATE, CLICK, CONFIRM, DONE, DIVERGED, BLOCKED
    }

    // ------------------------------------------------------------------------------------------------- collaborators

    private final Princeps princeps;
    private final IPlayerContext ctx;
    private final PlacementOracle oracle;
    private final V3Settings settings;
    private final SchematicView view;
    private final Supplier<CalculationContext> calcContext;
    private final Listener listener;
    private Acknowledgements acks = Acknowledgements.NONE;

    // ------------------------------------------------------------------------------------------------- run state

    private BuildPlan plan;
    private int actionIndex;
    private State state = State.SELECT;
    private long tick;
    private String stopReason;

    /** Tick the current state was entered on — the base of every patience counter, so a counter can never be left
     *  running by a transition that forgot to reset it. */
    private long stateEnteredTick;

    /** Tick the current ACTION was selected on. Deliberately NOT reset by {@link #enter}: it is the one clock two
     *  states passing an action back and forth cannot restart, and it is therefore the only bound that catches them.
     *  @see #ACTION_PATIENCE_TICKS */
    private long actionStartedTick;

    /** First tick of the current unbroken run of ticks an open screen has held, or {@link #NEVER}. */
    private long screenBlockedSinceTick = NEVER;

    /**
     * What the current state last said it was waiting for, or null.
     *
     * <p>Held as an {@code Object} and stringified only when a patience actually runs out, because it is written on
     * every tick of every wait and read once per build at most. The values put here are things that already exist —
     * a {@link PlaceGate.Verdict}, an {@link ActionRunner.Directive}, a string literal — so writing it allocates
     * nothing.
     *
     * <p>It exists because the generic sentence names the state and the cell while the note names the CAUSE, and the
     * bench proved the difference matters: "the gate said ENTITY_IN_CELL" tells a human to shoo the mob, and "the
     * gate never opened for 72,-59,67" does not.
     */
    private Object waitNote;

    /** Executor tick the irreversible action was armed on; used to reject a stale acknowledgement token. */
    /** {@link #NEVER} until a click is armed. Defaulting to 0 would read as "armed on the first tick of the build",
     *  which is a live failsafe window around an action that has not been sent. */
    private long armedTick = NEVER;

    /** Ticks the body has been inside the tolerance with the keys released. */
    private int settleTicks;

    /**
     * The pathfinder may hand FineApproach a body with residual travel velocity. No manual step is emitted until one
     * complete key-free crouched tick has established the zero-velocity start the support envelope was proved for.
     */
    private boolean approachEntrySettled;

    /**
     * "This stamp was never set." Compared with {@code ==} at every site, NEVER by subtracting it from the clock.
     *
     * <p>{@code tick - Long.MIN_VALUE} overflows. It is not a large number, it is a NEGATIVE one, and the sign
     * decides the branch both ways round:
     *
     * <ul>
     *   <li>a cooldown asks {@code tick - stamp < COOLDOWN}. Overflowed, that is negative, which is less than any
     *       cooldown, so the throttle reads as permanently running and the state never advances;
     *   <li>a timeout asks {@code tick - stamp > TIMEOUT}. Overflowed, that is negative, which exceeds nothing, so
     *       the failsafe never fires.
     * </ul>
     *
     * <p>The first shape is what stranded the first live bench run of this engine: a proven 22-cell plan, and the
     * executor sat in EQUIP for all 1940 ticks waiting out a swap cooldown that had never started. The trace read
     * {@code act=equip} 1939 times with the body motionless and no swap ever attempted. The second shape is the
     * same defect wearing the opposite sign, and it is worse, because it removes a guard silently instead of
     * stopping the build loudly.
     */
    private static final long NEVER = Long.MIN_VALUE;

    private int swapAttempts;
    private long lastSwapTick = NEVER;

    /** False as soon as anything about the current action stops being the plan's ideal path: a re-equip, a second
     *  click, a divergence. The plan-fidelity numerator counts the actions that keep it. */
    private boolean idealSoFar = true;

    /** True once a TRAVEL leg has begun for the current action, so arrival is counted once. */
    private boolean walking;
    /** TRAVEL is proven in a paused calculation before any movement is allowed to begin. */
    private boolean travelPrecheckRequested;
    private boolean travelPrecheckReady;

    /** Index of the last layer boundary that passed its live-world gate, so a 15 000-cell layer is verified once
     *  rather than once per tick. */
    private int gatedBoundary = -1;

    /** K1's non-reentrancy assertion around the one live-player rotation mutation in V3. */
    private boolean simulatingPlacement;

    /** The runner driving the current INTERACT, WRITE_SIGN or FILL_FLUID, or null. Built lazily and dropped by
     *  {@link #resetPerAction}, so "one runner runs exactly one action" is enforced by the lifecycle rather than by
     *  remembering to reset its fields. */
    private ActionRunner runner;

    public CellExecutor(Princeps princeps, IPlayerContext ctx, PlacementOracle oracle, V3Settings settings,
                        SchematicView view,
                        Supplier<CalculationContext> calcContext, Listener listener) {
        this.princeps = Objects.requireNonNull(princeps, "princeps");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.view = Objects.requireNonNull(view, "view");
        this.calcContext = Objects.requireNonNull(calcContext, "calcContext");
        this.listener = listener == null ? Listener.NONE : listener;
    }

    /** Install the packet-backed acknowledgement adapter before {@link #begin}; the safe default refuses clicks. */
    public void acknowledgements(Acknowledgements acknowledgements) {
        this.acks = acknowledgements == null ? Acknowledgements.NONE : acknowledgements;
    }

    // ------------------------------------------------------------------------------------------------- lifecycle

    /** Start executing a proven plan from its first action. */
    public void begin(BuildPlan plan) {
        this.acks.clear();
        this.plan = Objects.requireNonNull(plan, "plan");
        this.actionIndex = 0;
        this.tick = 0L;
        this.stopReason = null;
        this.gatedBoundary = -1;
        enter(State.SELECT);
        resetPerAction();
    }

    /** Drop everything. Must leave the executor safe to query — the process calls this from {@code onLostControl},
     *  which also runs on world exit. */
    public void reset() {
        this.acks.clear();
        this.plan = null;
        this.actionIndex = 0;
        this.state = State.SELECT;
        this.stopReason = null;
        this.simulatingPlacement = false;
        resetPerAction();
    }

    public State state() {
        return this.state;
    }

    /** The same state in the process's vocabulary, for its trace column. The two enums are declared separately and
     *  mapped here rather than shared, so a state added on one side cannot silently mean something else on the
     *  other. */
    public PlannedBuilderProcess.State processState() {
        return switch (this.state) {
            case SELECT -> PlannedBuilderProcess.State.SELECT;
            case EQUIP -> PlannedBuilderProcess.State.EQUIP;
            case TRAVEL -> PlannedBuilderProcess.State.TRAVEL;
            case APPROACH -> PlannedBuilderProcess.State.APPROACH;
            case AIM -> PlannedBuilderProcess.State.AIM;
            case GATE -> PlannedBuilderProcess.State.GATE;
            case CLICK -> PlannedBuilderProcess.State.CLICK;
            case CONFIRM -> PlannedBuilderProcess.State.CONFIRM;
            case DONE -> PlannedBuilderProcess.State.DONE;
            case DIVERGED -> PlannedBuilderProcess.State.DIVERGED;
            case BLOCKED -> PlannedBuilderProcess.State.BLOCKED;
        };
    }

    public int actionIndex() {
        return this.actionIndex;
    }

    /** The action being executed, or null before {@link #begin} and after the last one. */
    public BuildAction currentAction() {
        if (this.plan == null || this.actionIndex < 0 || this.actionIndex >= this.plan.size()) {
            return null;
        }
        return this.plan.action(this.actionIndex);
    }

    // ------------------------------------------------------------------------------------------------- the tick

    /**
     * One tick of the automaton.
     *
     * <p><b>Precondition:</b> the caller has already called {@code clearAllKeys()} this tick. Forced inputs are
     * sticky and nothing clears them for you, so every state re-asserts what it wants held and nothing else — the
     * discipline the V2 builder keeps at the top of its own {@code onTick}. The executor does NOT clear them itself,
     * because doing so would also wipe keys the process set for its own reasons and there would be two owners of the
     * input map again.
     */
    /** The placement that has server evidence but whose revert watch has not expired, or null. Retired by
     *  {@link #resolveWatch()}; while it is set no new click may go out. */
    private BuildAction watchedAction;

    /** The executor arm stamp {@link #watchedAction} was armed with, so the watch polls its OWN expectation and can
     *  never read a later one's verdict. {@link #NEVER} when nothing is watched. */
    private long watchedArmedTick = NEVER;

    /** The look axis the SENT rotation resolved to last tick, and how many ticks it has held. Advanced once per tick
     *  for every state, because the hold that matters spans AIM's predecessors: where {@code confirmRotation} already
     *  turned the head toward this action during the previous CONFIRM, the axis is long since standing and the hold
     *  costs nothing. Deliberately NOT reset per action for exactly that reason. */
    private Direction lastSentAxis;

    private int lookAxisStableTicks;

    /** First tick of the current uninterrupted converged aim, or {@link #NEVER}. Per-action, because a fuse that
     *  inherited its start from an action that is over would fire as an opening move instead of a last resort. */
    private long axisHoldSince = NEVER;

    public Outcome onTick(boolean calcFailed, boolean isSafeToCancel) {
        this.tick++;
        if (this.plan == null) {
            return blocked("no plan — begin() was never called");
        }
        if (this.state == State.BLOCKED) {
            return blocked(this.stopReason);
        }
        if (this.state == State.DIVERGED) {
            return new Outcome(Status.DIVERGED, hold(), this.state, this.stopReason);
        }
        Outcome watchVerdict = resolveWatch();
        if (watchVerdict != null) {
            return watchVerdict;
        }
        if (this.actionIndex >= this.plan.size()) {
            enter(State.DONE);
            return new Outcome(Status.FINISHED, hold(), this.state, null);
        }
        BuildAction action = this.plan.action(this.actionIndex);

        // Before anything else looks at this action: has it been waiting too long? Asked here, ahead of the screen
        // guard and ahead of the dispatch, because both of those can return without reaching any state's own code —
        // and a guard that the stuck path never executes is exactly the guard that was missing.
        String impatient = impatience(this.state, this.tick - this.stateEnteredTick,
                this.tick - this.actionStartedTick, action, this.actionIndex, this.waitNote);
        if (impatient != null) {
            return diverge(action, impatient);
        }

        // Whose tick is this? The routing question is asked once, here, and ActionRunner.owns is its only answer.
        boolean delegating = ActionRunner.owns(action) && delegated(this.state);

        // A screen owns the keyboard and the mouse. Nothing is aimed, walked or clicked while one is open, and a
        // forced key underneath one is typed into the sign. See screenBlocks for the two exceptions and why each of
        // them is a deadlock rather than a nicety.
        if (ctx.minecraft().screen != null) {
            if (screenBlocks(action, this.actionIndex + 1 < this.plan.size()
                    ? this.plan.action(this.actionIndex + 1) : null)) {
                // A wait of its own, with a bound of its own, because it returns BEFORE the dispatch: the state clock
                // is still running but the state's code never executes, so nothing inside it could ever notice. The
                // counter is on the BLOCKING and not on the screen — CONFIRM legitimately runs for the length of an
                // acknowledgement with the sign editor the server just opened standing over it, and that is not a
                // wait, it is the normal way a sign is placed.
                if (this.screenBlockedSinceTick == NEVER) {
                    this.screenBlockedSinceTick = this.tick;
                }
                if (this.tick - this.screenBlockedSinceTick > SCREEN_PATIENCE_TICKS) {
                    return diverge(action, "a screen has held the tick for "
                            + (this.tick - this.screenBlockedSinceTick) + " ticks (patience " + SCREEN_PATIENCE_TICKS
                            + ") while " + describeAction(action) + " was in " + this.state);
                }
                return running(hold());
            }
            this.screenBlockedSinceTick = NEVER;
            if (!delegating && !(action instanceof BuildAction.WriteSign)) {
                // An action the runner owns, whose turn has not come: it is still being equipped or walked to, so
                // nobody would answer the screen this tick and holding is not neutral — while any container is up
                // InventoryBehavior.onTick bails, its move clock stops, and no hotbar swap is ever granted again.
                // WRITE_SIGN is excluded by name and not by luck: the editor open here is the one it is walking
                // toward, and closing it is precisely how a sign's text is lost.
                logMechanic("v3: closing a screen that opened while " + action.kind() + " at "
                        + BuildAction.describePos(action.cell()) + " was still on its way");
                ctx.minecraft().setScreen(null);
                return running(hold());
            }
        } else {
            this.screenBlockedSinceTick = NEVER;
        }

        // The hard layer bound, before anything of the new layer is touched — including before the skip below, or a
        // boundary whose first action happens to be satisfied already would never be gated at all.
        if (this.state == State.SELECT && this.plan.isLayerBoundary(this.actionIndex)
                && this.gatedBoundary != this.actionIndex) {
            String open = layerGate(action.layer());
            if (open != null) {
                return diverge(action, open);
            }
            this.gatedBoundary = this.actionIndex;
        }

        // Asked BEFORE the preconditions, and it has to be in that order: an action whose result is already in the
        // world would otherwise fail the very next check as "the target cell is occupied" and re-plan — and the
        // re-plan would produce the same action again, because the planner defines `todo` as everything not yet
        // correct. That is an infinite loop at click cadence, in the one mechanism that replaced every watchdog.
        // Asked in every pre-click state and not only in SELECT, because the cell can be filled by something else
        // while the bot is still walking to it.
        //
        // NEVER while the runner is mid-action, and that exclusion is load-bearing rather than tidy. An interaction
        // reaches its target state one tick after the click and spends the next three watching it stay true, because
        // the client renders a right-click's effect the moment it predicts one and a server revert lands two to six
        // ticks later. This check runs in GATE, sees the predicted state, and would retire the cell on the optimism
        // the hold exists to disbelieve — and a repeater the server sent back to delay 1 would be recorded as done.
        if (!delegating && preClick(this.state) && alreadySatisfied(action)) {
            return advance(action);
        }

        // 6.5, before every action and every tick of it, up to the moment the click goes out. Skipped from CLICK
        // onward for the one reason that matters: after a successful placement the target cell is no longer free,
        // and a check that cannot tell that from a divergence would report one on every cell it just built. Skipped
        // for a delegated action for the same reason one step earlier: the runner is already past its click.
        if (!delegating && preClick(this.state)) {
            String divergence = checkPreconditions(action);
            if (divergence != null) {
                return diverge(action, divergence);
            }
        }

        if (delegating) {
            return delegate(action);
        }

        // Before the dispatch, not inside AIM: a counter that only ran while aiming would read a thirty-tick travel
        // gap as contiguous and hand over on an axis the server never saw stand still.
        Vec3 sentLook = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations());
        Direction sentAxis = Direction.getApproximateNearest(sentLook.x, sentLook.y, sentLook.z);
        this.lookAxisStableTicks = sentAxis == this.lastSentAxis ? this.lookAxisStableTicks + 1 : 1;
        this.lastSentAxis = sentAxis;

        return switch (this.state) {
            case SELECT -> select(action);
            case EQUIP -> equip(action);
            case TRAVEL -> travel(action, calcFailed);
            case APPROACH -> approach(action, isSafeToCancel);
            case AIM -> aim(action);
            case GATE -> gate(action);
            case CLICK -> {
                // The click went out last tick; CLICK_RIGHT was cleared by the caller at the top of this one, which
                // is what makes it exactly one click. Nothing else belongs here.
                enter(State.CONFIRM);
                yield confirm(action);
            }
            case CONFIRM -> confirm(action);
            case DONE -> advance(action);
            case DIVERGED, BLOCKED -> running(hold());
        };
    }

    // --------------------------------------------------------------------------------------- bounded patience

    /**
     * How long this state may go without progressing, in ticks.
     *
     * <p>A switch over the whole enum with no {@code default}, deliberately: a state added without a number here is a
     * compile error rather than a state that waits for ever. That is the shape of the defect this table exists to
     * close — the freeze that cost the last basalt run was not a number set too high, it was a wait nobody had given
     * a number at all, and it produced 44 000 ticks with velocity zero and not one line in the log.
     */
    static int patienceTicks(State state) {
        return switch (state) {
            case SELECT -> BOOKKEEPING_PATIENCE_TICKS;
            case EQUIP -> EQUIP_PATIENCE_TICKS;
            case TRAVEL -> TRAVEL_PATIENCE_TICKS;
            case APPROACH -> APPROACH_PATIENCE_TICKS;
            case AIM -> AIM_PATIENCE_TICKS;
            case GATE -> GATE_PATIENCE_TICKS;
            case CLICK -> CLICK_PATIENCE_TICKS;
            case CONFIRM -> CONFIRM_PATIENCE_TICKS;
            // Never ticked: onTick answers all three before it reaches the dispatch, so there is no wait to bound.
            case DONE, DIVERGED, BLOCKED -> TERMINAL_STATE;
        };
    }

    /**
     * Has this action waited too long, and what is the sentence?
     *
     * <p>The single owner of "how long is too long" in the executor, and pure, so the property that matters — every
     * state that can wait ends in a NAMED divergence rather than in silence — is a headless assertion instead of a
     * live observation. Both bounds are checked here and they answer different questions: the per-state one asks
     * whether this state is stuck, and the per-action one asks whether the states are passing the action between
     * themselves without any of them being stuck. Only the second catches the APPROACH/TRAVEL loop, because every
     * transition resets the first.
     *
     * @param ticksInState  ticks since {@link #enter}
     * @param ticksInAction ticks since the action was selected — never reset by a transition
     * @return the divergence reason, or null while the wait is still legitimate
     */
    static String impatience(State state, long ticksInState, long ticksInAction, BuildAction action, int index,
                             Object waitingOn) {
        if (ticksInAction > ACTION_PATIENCE_TICKS) {
            return "action " + index + " (" + describeAction(action) + ") spent " + ticksInAction
                    + " ticks without retiring, the last of them in " + state + "; no single state exceeded its own "
                    + "patience, so the states were handing it back and forth"
                    + (waitingOn == null ? "" : " — last waiting on " + waitingOn);
        }
        int patience = patienceTicks(state);
        if (patience == TERMINAL_STATE || ticksInState <= patience) {
            return null;
        }
        // The state's own note wins over the generic sentence when it has one. The generic sentence names the state
        // and the cell; the note names the CAUSE, which is the difference between "the gate never opened for
        // 72,-59,67" and "the gate said ENTITY_IN_CELL" — and only the second one tells a human to shoo the mob.
        return state + " waited " + ticksInState + " ticks (patience " + patience + ") on "
                + describeAction(action) + ": "
                + (waitingOn == null ? stuckDetail(state, action) : waitingOn);
    }

    /** What the state was waiting FOR when it left no note of its own. The timing is one owner's; the sentence is the
     *  state's. */
    private static String stuckDetail(State state, BuildAction action) {
        return switch (state) {
            case SELECT -> "bookkeeping that decides nothing did not fall through into EQUIP";
            case EQUIP -> "hotbar slot " + action.handSlot() + " never came to hold the planned item";
            case TRAVEL -> "the pathfinder never delivered the body to the proven stance "
                    + BuildAction.describePos(stanceOf(action));
            case APPROACH -> "could not settle on the approach point "
                    + BuildAction.describePoint(approachOf(action)) + " within "
                    + BuildAction.describeTolerance(action.approachTolerance());
            case AIM -> "the aim never converged on " + BuildAction.describeRotation(plannedRotationOf(action));
            case GATE -> "the gate never opened for " + BuildAction.describePos(action.cell());
            case CLICK -> "the click went out and the tick after it never entered CONFIRM";
            case CONFIRM -> "no acknowledgement and no live answer for " + BuildAction.describePos(action.cell());
            case DONE, DIVERGED, BLOCKED -> "a terminal state was ticked";
        };
    }

    /** Kind and cell, for a sentence about an action that is not progressing. */
    private static String describeAction(BuildAction action) {
        return action.cell() == null ? action.kind().toString()
                : action.kind() + " at " + BuildAction.describePos(action.cell());
    }

    /** Is this a state in which the target cell should still be empty? */
    private static boolean preClick(State state) {
        return state == State.SELECT || state == State.EQUIP || state == State.TRAVEL
                || state == State.APPROACH || state == State.AIM || state == State.GATE;
    }

    /**
     * The phases {@link ActionRunner} owns for an action it owns: everything from the head turn onward.
     *
     * <p>{@code SELECT}, {@code EQUIP}, {@code TRAVEL} and {@code APPROACH} stay here, because a right-click still
     * needs the hotbar locked, a path to the proven stance and the exact sub-block approach point the geometry was
     * proved from — none of which the runner can reach, by design: it touches no {@code ctx}.
     *
     * <p>Handing over at {@code AIM} rather than at {@code GATE} is the fix for the worst open path in the engine.
     * {@link BuildAction.WriteSign} carries no rotation, so {@link #aim} answers "the action carries no rotation" and
     * DIVERGES — and by then the sign BLOCK is standing, so the cell reads as satisfied, the re-plan emits nothing
     * for it, and the text is lost with the build reporting success. Routing before {@code AIM} means that branch is
     * unreachable for every kind the runner owns.
     */
    static boolean delegated(State state) {
        return state == State.AIM || state == State.GATE || state == State.CLICK || state == State.CONFIRM;
    }

    /**
     * Does an open screen stop this tick?
     *
     * <p>Two exceptions, and both of them are deadlocks rather than conveniences.
     *
     * <p><b>An action the runner owns is never stopped.</b> Its whole tick is a decision about the screen:
     * {@code WRITE_SIGN} types into the editor the server opened, and {@code INTERACT} and {@code FILL_FLUID} answer
     * a stray container by CLOSING it. Standing still instead is the 1800-tick lighthouse stall — while any container
     * is open {@code InventoryBehavior.onTick} bails, its move clock stops, and no hotbar swap can ever be granted
     * again.
     *
     * <p><b>{@code CONFIRM} is never stopped.</b> It touches nothing: it polls the acknowledgement and re-issues a
     * look. The one screen a build legitimately opens is the sign editor, and the server opens it DURING the sign
     * placement's own confirmation window — so a guard that stopped {@code CONFIRM} would freeze the placement that
     * opened the screen, which is a {@code WRITE_SIGN} that is never reached and a build that never ends.
     */
    /**
     * May this tick WAIT on an open screen, or must it close it?
     *
     * <p>Only the action that owns the screen may wait on it. Everything else closes it, in every state — because
     * nobody else is ever going to, and a wait for someone who is not coming is a deadlock with a patience counter
     * bolted on.
     *
     * <p>This read the other way round and it stopped a basalt run after four blocks. The old rule was
     * {@code !owns(action) && state != CONFIRM}, so an ordinary PLACE — which owns nothing — BLOCKED on any screen in
     * every state but one, and the branch that closes screens sat in the else and could therefore never run for it.
     * The trace is unambiguous: 165 ticks in TRAVEL with {@code path=len35} standing ready and velocity exactly zero,
     * and one single "closing a screen" line in the whole run, from the one state the old condition let through.
     *
     * <p>The sign is the reason the rule cannot simply be "always close". A sign's editor opens on placement and the
     * WRITE_SIGN action that fills it is the NEXT action, so a close during the placement's CONFIRM throws away the
     * screen its own follow-up is walking toward — and a sign whose text never arrives still STANDS, so the cell reads
     * as satisfied and the loss is silent. That case is excluded by looking at what comes next, not by excluding a
     * whole state.
     */
    static boolean screenBlocks(BuildAction action, BuildAction next) {
        if (ActionRunner.owns(action)) {
            return true;   // its screen, its business
        }
        // The editor that just opened belongs to the WRITE_SIGN queued behind this placement. Neither wait on it nor
        // close it: let the dispatch run, and the sign's own action will answer it a moment from now.
        return next instanceof BuildAction.WriteSign sign && sign.cell().equals(action.cell());
    }

    // ------------------------------------------------------------------------------------------------- SELECT

    /**
     * Take the next action. The only state that is pure bookkeeping, so it falls straight through into EQUIP in the
     * same tick — a tick spent deciding nothing is a tick the ETA pays for and the trace cannot explain.
     */
    private Outcome select(BuildAction action) {
        // The layer gate and the already-satisfied skip both ran in onTick, ahead of the preconditions, because both
        // have to be answered before anything else looks at this action. What is left is genuinely nothing, so it
        // falls straight through into EQUIP rather than spending a tick to say so.
        enter(State.EQUIP);
        return equip(action);
    }

    // ------------------------------------------------------------------------------------------------- EQUIP

    /**
     * The hotbar, and the only state allowed to touch the inventory.
     *
     * <p>Selecting a slot is free: it is a number key, no throttle, no stationarity requirement, nothing to fight
     * over. FETCHING an item onto the hotbar is the expensive one and it is a planned action of its own, which is why
     * this state has two shapes.
     */
    private Outcome equip(BuildAction action) {
        if (action instanceof BuildAction.SwapHotbar swap) {
            princeps.getInventoryBehavior().lockBuilderHotbarSlot(swap.hotbarSlot());
            return fetch(swap);
        }
        princeps.getInventoryBehavior().lockBuilderHotbarSlot(action.handSlot());
        PlacementSolution placement = solutionOf(action);
        if (placement != null && hotbarStack(action.handSlot()).getItem() != placement.item()) {
            return restorePlannedItem(action, placement.item(), action.handSlot());
        }
        // Slots 1..7 by construction of the schedule, so this never fights InventoryBehavior's per-tick upkeep of
        // slot 0 (the pickaxe) and slot 8 (the throwaway or the totem).
        if (ctx.player().getInventory().getSelectedSlot() != action.handSlot()) {
            ctx.player().getInventory().setSelectedSlot(action.handSlot());
        }
        enter(State.TRAVEL);
        return running(hold());
    }

    /**
     * Repair a slot that changed after the frozen schedule was made. Unlike a plain re-select, this converges or
     * diverges after a finite number of verified swaps; it cannot bounce GATE -> EQUIP forever.
     */
    private Outcome restorePlannedItem(BuildAction action, Item item, int slot) {
        if (this.swapAttempts >= SWAP_ATTEMPTS) {
            return diverge(action, "hotbar slot " + slot + " still does not hold "
                    + BuildAction.describeItem(item) + " after " + this.swapAttempts + " restore attempts");
        }
        if (this.lastSwapTick != NEVER && this.tick - this.lastSwapTick < SWAP_COOLDOWN_TICKS) {
            return running(hold());
        }
        if (!FineApproach.settled(ctx.player().getDeltaMovement())) {
            return running(hold());
        }
        int source = findForRestore(item, slot);
        if (source < 0) {
            return diverge(action, "planned item " + BuildAction.describeItem(item)
                    + " is no longer anywhere in the inventory for hotbar slot " + slot);
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,
                source < 9 ? source + 36 : source, slot, ContainerInput.SWAP, ctx.player());
        this.lastSwapTick = this.tick;
        this.swapAttempts++;
        this.idealSoFar = false;
        logMechanic("v3: restoring " + BuildAction.describeItem(item) + " from slot " + source
                + " to planned hotbar slot " + slot);
        return running(hold());
    }

    /** Search all inventory slots, including another hotbar slot where upkeep or a user may have moved the stack. */
    private int findForRestore(Item item, int targetSlot) {
        for (int slot = 0; slot < HotbarSchedule.INVENTORY_SLOTS; slot++) {
            if (slot != targetSlot && stackAt(slot).getItem() == item) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Move an item from the backpack onto a hotbar slot, with our own throttle and our own verification.
     *
     * <p>Neither {@code InventoryBehavior.requestSwapWithHotBar} nor {@code throwaway(select=true, …)} is usable
     * here: the first is private on a final class, and the second reports success unconditionally while ignoring the
     * return value of the swap it just asked for, so the bot ends up holding whatever happened to be in slot 7 and
     * says it worked. Nothing anywhere verifies a swap landed, which is why this one does.
     *
     * <p>{@code stationaryForInventoryMove()} is never called, not even to ask: merely asking sets a pause request
     * that halts the bot on the next tick at priority 5.1. Standing still is checked directly instead.
     */
    private Outcome fetch(BuildAction.SwapHotbar swap) {
        ItemStack inSlot = hotbarStack(swap.hotbarSlot());
        if (inSlot.getItem() == swap.item()) {
            ctx.player().getInventory().setSelectedSlot(swap.hotbarSlot());
            return advance(swap);
        }
        if (this.swapAttempts >= SWAP_ATTEMPTS) {
            return diverge(swap, "hotbar slot " + swap.hotbarSlot() + " still does not hold "
                    + BuildAction.describeItem(swap.item()) + " after " + this.swapAttempts + " swaps");
        }
        if (this.lastSwapTick != NEVER && this.tick - this.lastSwapTick < SWAP_COOLDOWN_TICKS) {
            return running(hold());
        }
        if (!FineApproach.settled(ctx.player().getDeltaMovement())) {
            // Standing still is asked of the body, not of InventoryBehavior — see the javadoc.
            return running(hold());
        }
        int source = findInInventory(swap.item(), swap.inventorySlotHint());
        if (source < 0) {
            return diverge(swap, "no " + BuildAction.describeItem(swap.item()) + " left in the inventory for slot "
                    + swap.hotbarSlot());
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,
                source < 9 ? source + 36 : source, swap.hotbarSlot(), ContainerInput.SWAP, ctx.player());
        this.lastSwapTick = this.tick;
        this.swapAttempts++;
        logMechanic("v3: swap slot " + source + " -> hotbar " + swap.hotbarSlot() + " ("
                + BuildAction.describeItem(swap.item()) + ")");
        return running(hold());
    }

    /** The hint first, then a scan. The hint is where the planner expected the item and it is usually right; the scan
     *  exists because the real inventory moves and a plan that insisted on the hint would fail on the first pickup. */
    private int findInInventory(Item item, int hint) {
        if (hint >= 0 && hint < HotbarSchedule.INVENTORY_SLOTS && stackAt(hint).getItem() == item) {
            return hint;
        }
        for (int slot = 9; slot < HotbarSchedule.INVENTORY_SLOTS; slot++) {
            if (stackAt(slot).getItem() == item) {
                return slot;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------- TRAVEL

    /**
     * The pathfinder owns everything here. {@code GoalBlock} on the exact stance cell, never an adjacent goal: a goal
     * that can already be satisfied where the bot stands produces no path and no arrival, which is the 486-tick
     * standstill of the V2 trace in one sentence.
     *
     * <p>The command is always a {@link PathingCommandContext} over the process's calculation context. A plain
     * {@code PathingCommand} falls back to the generic one, which prices a throwaway placement inside the blueprint at
     * {@code blockPlacementPenalty} instead of {@code COST_INF} — and rule N1 quietly stops holding.
     */
    /**
     * A walk that is not walking, answered rather than waited out.
     *
     * <p>Measured shape of the failure: the path executor sits on node 0 of 15 calling itself running, the head is
     * within two degrees of the yaw that would reach the next node, nothing is in the way and no collision is
     * reported -- and the index never advances, so the body presses forward on some ticks, nothing on others, and
     * goes nowhere for the rest of the run. The route out of that node was a DIAGONAL the movement layer would not
     * execute from that corner.
     *
     * <p>The answer here is deliberately not a fix in the movement layer. What this class can say with certainty is
     * "no progress, for this long, while holding a live path", and the honest response to that is to throw the path
     * away and ask for a fresh one -- a route computed from where the body actually stands now, which is not the
     * route that wedged. If a fresh route wedges too, the cell diverges with the reason named instead of the run
     * ending in a silent stall.
     *
     * <p>Progress is BOTH the executor's index and the body's position: the index alone would call a body that is
     * sliding along without advancing a node "stuck", and the position alone would call a body legitimately waiting
     * out a break or a jump "stuck". Neither moving is what nothing-is-happening actually looks like.
     */
    private Outcome breakTravelDeadlock(BlockPos stance) {
        int index = -1;
        if (princeps.getPathingBehavior().getCurrent() != null) {
            index = princeps.getPathingBehavior().getCurrent().getPosition();
        }
        Vec3 here = ctx.player().position();
        boolean moved = this.lastTravelIndex != index
                || this.lastTravelPos == null
                || here.distanceToSqr(this.lastTravelPos) > TRAVEL_PROGRESS_EPSILON * TRAVEL_PROGRESS_EPSILON;
        this.lastTravelIndex = index;
        this.lastTravelPos = here;
        if (moved) {
            this.travelStuckTicks = 0;
            return null;
        }
        if (++this.travelStuckTicks < TRAVEL_STUCK_TICKS) {
            return null;
        }
        this.travelStuckTicks = 0;
        if (++this.travelRepaths > TRAVEL_REPATH_ATTEMPTS) {
            return diverge(currentAction(), "the walk to " + BuildAction.describePos(stance)
                    + " made no progress across " + TRAVEL_REPATH_ATTEMPTS + " fresh paths"
                    + " — the body held a live route and never advanced along it");
        }
        // Drop the wedged route and start the pre-check over, so the next tick asks for a path from where the body
        // actually is rather than resuming one it cannot follow. Capture its last proven CURRENT node before cancel:
        // cancellation destroys the route, and recomputing this target afterwards fell back to the far final stance
        // on seven of eight recovery ticks -- straight through the same door that caused the wedge.
        this.unwedgeAim = captureUnwedgeTarget();
        this.travelPrecheckReady = false;
        this.travelPrecheckRequested = false;
        this.unwedgeTicks = this.unwedgeAim == null ? 0 : UNWEDGE_TICKS;
        this.waitNote = "no progress on the path, requesting a fresh route (attempt "
                + this.travelRepaths + " of " + TRAVEL_REPATH_ATTEMPTS + ")";
        return running(new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL));
    }

    /** Ticks of neither index nor position changing before the route is treated as wedged. Two seconds: long enough
     *  that a legitimate pause -- a jump arc, a mid-path break -- is never mistaken for one. */
    private static final int TRAVEL_STUCK_TICKS = 40;

    /** How many fresh routes a single walk may ask for before the cell diverges and says so. */
    private static final int TRAVEL_REPATH_ATTEMPTS = 3;

    /** Movement below this in a tick is drift, not travel. */
    private static final double TRAVEL_PROGRESS_EPSILON = 0.02D;

    /**
     * Where to steer while unwedging: back to the CURRENT node of the route that wedged.
     *
     * <p>The next node is exactly the place whose transition failed -- in the measured case it was occupied by the
     * closed door. The current node is the last corridor cell the route already proved and is therefore the one
     * defensible local retreat. Null means there is no live route to prove such a retreat; in that case no manual
     * recovery is attempted and the fresh-path pre-check starts immediately.
     */
    private Vec3 captureUnwedgeTarget() {
        var current = princeps.getPathingBehavior().getCurrent();
        if (current != null && current.getPath() != null) {
            int at = current.getPosition();
            var positions = current.getPath().positions();
            if (at >= 0 && at < positions.size()) {
                BlockPos node = positions.get(at);
                return new Vec3(node.getX() + 0.5D, node.getY(), node.getZ() + 0.5D);
            }
        }
        return null;
    }

    /** Ticks spent steering out of a corner before a fresh route is asked for. Eight: far enough to clear
     *  a body's own width and the block edge it caught on, short enough that it cannot become travel in its own
     *  right. */
    private static final int UNWEDGE_TICKS = 8;

    private int unwedgeTicks;
    /** Frozen before the wedged route is cancelled; never re-derived from an already-destroyed path. */
    private Vec3 unwedgeAim;

    private int lastTravelIndex = -1;
    private Vec3 lastTravelPos;
    private int travelStuckTicks;
    private int travelRepaths;

    private Outcome travel(BuildAction action, boolean calcFailed) {
        BlockPos stance = stanceOf(action);
        if (stance == null) {
            enter(State.AIM);
            return running(hold());
        }
        if (ctx.playerFeet().equals(stance) && ctx.player().onGround()) {
            if (this.walking) {
                this.listener.onWalkArrived(action);
                this.walking = false;
            }
            enter(State.APPROACH);
            return running(hold());
        }
        if (this.unwedgeTicks > 0) {
            // The deadlock tick already cancelled the live route. Recover while NO path owns the keys, then run the
            // paused pre-check from the body's new position. Starting that calculation first and cancelling it once
            // per recovery tick both wasted the calculation and erased the first recovery input.
            Vec3 escape = this.unwedgeAim;
            if (escape == null) {
                // Defensive only: ticks are armed iff the snapshot exists. Never substitute the distant stance here;
                // that was the defect this snapshot removes.
                this.unwedgeTicks = 0;
            } else {
                this.unwedgeTicks--;
                FineApproach.Keys keys = FineApproach.chooseKeys(ctx.playerRotations().getYaw(),
                        ctx.player().position(), escape);
                if (keys.first() != null) {
                    princeps.getInputOverrideHandler().setInputForceState(keys.first(), true);
                }
                if (keys.second() != null) {
                    princeps.getInputOverrideHandler().setInputForceState(keys.second(), true);
                }
                this.waitNote = "steering out of a wedge toward " + String.format(java.util.Locale.ROOT,
                        "%.1f,%.1f", escape.x, escape.z);
                if (this.unwedgeTicks == 0) {
                    this.unwedgeAim = null;
                }
                return running(hold());
            }
        }
        if (calcFailed) {
            // The planner asked the pathfinder for this stance before the run started, so a live failure is the world
            // having changed, not a stance that was never reachable. Re-plan rather than try another one here: trying
            // another one here is the decision-making this class is not allowed to do.
            return diverge(action, "no path to the proven stance " + BuildAction.describePos(stance));
        }
        PathingCommandContext pathCommand = new PathingCommandContext(new GoalBlock(stance),
                this.travelPrecheckReady ? PathingCommandType.SET_GOAL_AND_PATH
                        : PathingCommandType.SET_GOAL_AND_PAUSE,
                this.calcContext.get());
        if (!this.travelPrecheckReady) {
            if (!this.travelPrecheckRequested) {
                this.travelPrecheckRequested = true;
                return running(pathCommand);
            }
            if (!pathAlive()) {
                // The SAME goal, paused again -- never a null goal. Clearing it discarded the calculation this
                // branch is waiting for, and a route of eleven blocks takes more than one tick to compute, so every
                // wait threw away the answer and started over. The trace showed it exactly: path flickering between
                // len15 and goal-no-path, keys pressed NONE, for hundreds of ticks, eleven blocks from the goal.
                //
                // Short routes finished inside a single tick and hid this for thousands of placements. That is why
                // it only ever bit at one spot and looked like the door standing there.
                //
                // The pre-check's purpose is untouched: PAUSE still means the body may not move until a path is
                // proven to exist. A goal that genuinely has none now finishes its calculation and says so, which
                // TRAVEL already handles as a named divergence -- an answer instead of an endless wait.
                this.waitNote = "the paused pre-check has not produced a path to the stance yet";
                return running(pathCommand);
            }
            // A complete path exists and has not moved because every preceding tick requested pause. Releasing the
            // pause with the same goal starts only a route already known to exist.
            this.travelPrecheckReady = true;
            pathCommand = new PathingCommandContext(new GoalBlock(stance), PathingCommandType.SET_GOAL_AND_PATH,
                    this.calcContext.get());
        }
        Outcome unstick = breakTravelDeadlock(stance);
        if (unstick != null) {
            return unstick;
        }
        if (!this.walking) {
            this.walking = true;
            this.listener.onWalkStarted(action);
        }
        return running(pathCommand);
    }

    // ------------------------------------------------------------------------------------------------- APPROACH

    /**
     * The last half block, sneak-walked to the exact planned point. A regular state, not a rescue: the sub-block
     * position is half the proof, and the pathfinder cannot deliver it because {@code GoalBlock} is satisfied
     * anywhere inside the cell.
     *
     * <p>The two-phase handoff is the whole reason this method looks the way it does. Returning
     * {@code CANCEL_AND_SET_GOAL} while a path is live runs {@code clearAllKeys()} inside the cancel, which wipes the
     * keys set in the same tick. So: tick N kills the path and sets nothing; tick N+1 sets keys.
     */
    private Outcome approach(BuildAction action, boolean isSafeToCancel) {
        BlockPos stance = stanceOf(action);
        Vec3 target = approachOf(action);
        if (stance == null || target == null) {
            enter(State.AIM);
            return running(hold());
        }
        if (pathAlive()) {
            if (!isSafeToCancel) {
                // Mid-movement; cancelling now would drop the bot off whatever it is traversing. Hold the goal and
                // ask again — this is the one wait in the executor that is not about the world, and it is the branch
                // the 44 000-tick freeze ran through, which is why it now leaves a note as well as being bounded.
                this.waitNote = "a live path is mid-movement and may not be cancelled yet";
                return running(new PathingCommandContext(new GoalBlock(stance),
                        PathingCommandType.REVALIDATE_GOAL_AND_PATH, this.calcContext.get()));
            }
            // Phase one: kill the path, set NO keys. Anything forced here dies inside the cancel.
            return running(new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL));
        }
        if (!ctx.playerFeet().equals(stance)) {
            // Drifted out of the stance cell — the pathfinder owns getting back into it.
            enter(State.TRAVEL);
            return running(hold());
        }
        // The patience for this state is checked at the top of onTick and not here, and the difference is the whole
        // of the second defect: every branch above this line returns, so a check placed here bounds only the branch
        // that reaches it. The 44 000-tick freeze ran through the pathAlive/!isSafeToCancel branch, which never did.
        //
        // Phase two. Sneak is forced first and unconditionally: it is what kills the residual momentum, and it is
        // also the posture the plan proved the eye height and the reach from.
        princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        if (!ctx.player().onGround()) {
            this.settleTicks = 0;
            return running(hold());
        }
        Vec3 position = ctx.player().position();
        if (!this.oracle.approachSupported(ctx.world(), position)) {
            return diverge(action, "the live body has no collision surface at its fine-approach position "
                    + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                    position.x, position.y, position.z));
        }
        if (!this.approachEntrySettled) {
            if (!FineApproach.settled(ctx.player().getDeltaMovement())) {
                this.waitNote = brakeMomentum(position)
                        ? "braking path-travel momentum into the proved fine approach"
                        : "waiting for path-travel momentum to stop before the proved fine approach";
                return running(hold());
            }
            // One complete key-free crouched tick. Starting the key on this same tick would make the state boolean a
            // label rather than an observed hand-off.
            this.approachEntrySettled = true;
            return running(hold());
        }
        // The tolerance comes off the ACTION, not off the engine. It is the radius the planner proved this click's
        // sight line over, so arriving inside it means standing somewhere the click was proven from -- plan 19 V3,
        // and the answer to open question F2: the strictness has no global value, it is derived per action.
        if (!FineApproach.arrived(position, target, action.approachTolerance())) {
            this.settleTicks = 0;
            FineApproach.Keys keys = FineApproach.chooseKeys(ctx.playerRotations().getYaw(), position, target);
            Vec3 step = FineApproach.stepVector(ctx.playerRotations().getYaw(), keys);
            Vec3 halfway = position.add(step.scale(0.5D));
            Vec3 next = position.add(step);
            if (!stepSupported(halfway, target)
                    || !stepSupported(next, target)
                    || !ctx.world().noCollision(ctx.player(), ctx.player().getBoundingBox().move(step))) {
                return diverge(action, "the next fine-approach step would leave its live support envelope or collide"
                        + " (from " + String.format(java.util.Locale.ROOT, "%.3f,%.3f", position.x, position.z)
                        + " toward " + String.format(java.util.Locale.ROOT, "%.3f,%.3f", target.x, target.z) + ")");
            }
            if (keys.first() != null) {
                princeps.getInputOverrideHandler().setInputForceState(keys.first(), true);
            }
            if (keys.second() != null) {
                princeps.getInputOverrideHandler().setInputForceState(keys.second(), true);
            }
            probeApproach(position, target, keys);
            return running(hold());
        }
        // Inside the ball, and therefore the first moment the proven feet plane is a claim about where this body is.
        // Asking it any earlier is the defect that ended basalt run 701fed58 at 464 of 15 004 cells: see
        // FOOT_PLANE_EPSILON.
        if (Math.abs(position.y - target.y) > FOOT_PLANE_EPSILON) {
            return diverge(action, "the fine approach reached the proved point for stance "
                    + BuildAction.describePos(stance)
                    + " on physical feet plane " + String.format(java.util.Locale.ROOT, "%.3f", position.y)
                    + ", but the proof requires " + String.format(java.util.Locale.ROOT, "%.3f", target.y));
        }
        // Inside the tolerance. Require the velocity to be dead, then one complete key-free crouched tick. The old
        // `settleTicks < 2 && !settled` condition admitted every body on tick two regardless of its velocity.
        if (!FineApproach.settled(ctx.player().getDeltaMovement())) {
            this.settleTicks = 0;
            // Inside the tolerance and still drifting. Braking here cannot cost the arrival: it only ever reduces the
            // speed that would carry the body out of it, and if a tick of it does leave the ball the branch above
            // simply steps back in.
            brakeMomentum(position);
            return running(hold());
        }
        this.settleTicks++;
        if (this.settleTicks < 2) {
            return running(hold());
        }
        enter(State.AIM);
        return running(hold());
    }

    /**
     * May the fine approach put the body at this point — judged at the plane it is standing on now, OR at the plane
     * the plan proved, and at no other.
     *
     * <p>The second half is what the one-plane assumption cost. Judged only at the live plane, the step out of
     * {@code 91.298} toward the proved {@code 91.50} is refused: once the footprint clears the blue ice at
     * {@code x = 91.3} nothing holds the body at {@code -59.000} any more, and the guard reads that as "would leave
     * its live support envelope". What it actually is is the body arriving — the soul sand is right there, an eighth
     * of a block down, which is where the plan wants it.
     *
     * <p>Two planes and not a range, because two is all a legal walk can need: the one the body is on and the one it
     * is proved to end on. A destination held by neither is still a hole and still a divergence. And it cannot be
     * used to climb: an intruding top ABOVE the live plane puts the body inside that block, which the caller's
     * {@code noCollision} on the same step refuses before this is consulted.
     *
     * <p>{@link #brakeMomentum} deliberately does NOT get this: a brake pushes AWAY from the proved point, so the
     * proved plane is not a destination it can legitimately be heading for, and a refused brake merely coasts.
     */
    private boolean stepSupported(Vec3 at, Vec3 target) {
        return this.oracle.approachSupported(ctx.world(), at)
                || this.oracle.approachSupported(ctx.world(), new Vec3(at.x, target.y, at.z));
    }

    /**
     * Push against residual momentum instead of waiting for friction to eat it.
     *
     * <p>Measured over a live basalt run, waiting was {@code 20.7 %} of every tick the builder spent — the single
     * largest item in its whole budget, and every one of those ticks was the body standing still watching its own
     * velocity decay. It has the eight-direction key solver already; it simply was not asked.
     *
     * <p>Aimed one block AGAINST the horizontal motion, which is the one target that is unambiguous: it reduces the
     * speed whatever the yaw and wherever the stance is, and it walks back down the path the body just came, so it
     * cannot wander somewhere the plan never looked. The same support and collision envelope as an ordinary
     * fine-approach step has to hold, and if it does not the body coasts exactly as it did before — braking is an
     * accelerator, never a new way to fall off a ledge.
     *
     * @return whether a brake was actually applied, for the trace note
     */
    private boolean brakeMomentum(Vec3 position) {
        Vec3 motion = ctx.player().getDeltaMovement();
        if (!FineApproach.worthBraking(motion)) {
            return false;
        }
        Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
        float yaw = ctx.playerRotations().getYaw();
        FineApproach.Keys keys = FineApproach.chooseKeys(yaw, position, position.subtract(horizontal.normalize()));
        if (!keys.any()) {
            return false;
        }
        Vec3 step = FineApproach.stepVector(yaw, keys);
        Vec3 halfway = position.add(step.scale(0.5D));
        Vec3 next = position.add(step);
        if (!this.oracle.approachSupported(ctx.world(), halfway)
                || !this.oracle.approachSupported(ctx.world(), next)
                || !ctx.world().noCollision(ctx.player(), ctx.player().getBoundingBox().move(step))) {
            return false;
        }
        if (keys.first() != null) {
            princeps.getInputOverrideHandler().setInputForceState(keys.first(), true);
        }
        if (keys.second() != null) {
            princeps.getInputOverrideHandler().setInputForceState(keys.second(), true);
        }
        return true;
    }

    /** Permanent fine-approach forensics, sampled after key selection at the physical input seam. */
    private void probeApproach(Vec3 position, Vec3 target, FineApproach.Keys keys) {
        net.minecraft.client.player.LocalPlayer p = ctx.player();
        BlockPos below = BlockPos.containing(position.x, position.y - 0.5D, position.z);
        BlockState belowState = ctx.world().getBlockState(below);
        Object move = p.input == null ? null : p.input.keyPresses;
        AABB box = p.getBoundingBox();
        double step = p.maxUpStep();
        boolean edgeX = ctx.world().noCollision(p, box.move(target.x - position.x > 0 ? 0.05D : -0.05D, -step, 0.0D));
        boolean edgeZ = ctx.world().noCollision(p, box.move(0.0D, -step, target.z - position.z > 0 ? 0.05D : -0.05D));
        logMechanic(String.format(java.util.Locale.ROOT,
                "PROBE d=%.4f pos=%.4f,%.4f,%.4f yaw=%.2f keys=%s/%s inputClass=%s move=%s vel=%.4f,%.4f "
                        + "grd=%b hcol=%b crouch=%b slow=%b sprint=%b using=%b speed=%.4f below=%s fric=%.3f "
                        + "edgeX=%b edgeZ=%b",
                Math.sqrt(FineApproach.horizontalDistanceSq(position, target)),
                position.x, position.y, position.z, ctx.playerRotations().getYaw(),
                keys.first(), keys.second(),
                p.input == null ? "null" : p.input.getClass().getSimpleName(),
                move == null ? "null" : move.toString(),
                p.getDeltaMovement().x, p.getDeltaMovement().z,
                p.onGround(), p.horizontalCollision, p.isCrouching(), p.isMovingSlowly(), p.isSprinting(),
                p.isUsingItem(), p.getSpeed(), belowState.getBlock().getName().getString(),
                belowState.getBlock().getFriction(), edgeX, edgeZ));
    }

    // ------------------------------------------------------------------------------------------------- AIM

    /**
     * Turn to the proven rotation through the aim curve — several ticks, monotone, deterministic, and the same curve
     * the mining aim uses.
     *
     * <p>{@code placeIntent} is the fourth argument and it is the only thing that turns the curve on for a placement.
     * It is not a general "place aims are slow now": the pathfinder's bridging and pillaring place the block they are
     * about to stand on in the same tick they commit to the step, and they reach the look behaviour through
     * {@code MovementTarget}, which carries no such flag. So they keep the one-tick snap and this keeps the arc.
     */
    private Outcome aim(BuildAction action) {
        Rotation rotation = rotationOf(action);
        if (rotation == null) {
            return diverge(action, "the action carries no rotation");
        }
        if (pathAlive()) {
            return running(new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL));
        }
        holdPosture(action);
        princeps.getLookBehavior().updateTarget(rotation, true, false, true, true);
        // Patience is owned by onTick, ahead of the pathAlive() cancel above — which returns, and therefore skipped
        // any check written here.
        //
        // The rotation requested this tick is written in onPlayerUpdate, which runs after this dispatch, so the live
        // rotation always lags a tick. Reading it is therefore the honest convergence test — and the gate below
        // re-derives everything from the live ray anyway, so a slightly early transition costs a tick, never a
        // wrong click.
        if (!ctx.playerRotations().isCloseTo(rotation, YAW_TOLERANCE, PITCH_TOLERANCE)) {
            // Convergence itself is unchanged, and AIM_PATIENCE_TICKS still owns it.
            this.axisHoldSince = NEVER;
            return running(hold());
        }
        if (this.axisHoldSince == NEVER) {
            this.axisHoldSince = this.tick;
        }
        int wanted = requiredLookHold(action);
        boolean held = this.lookAxisStableTicks >= wanted;
        boolean fused = this.tick - this.axisHoldSince >= AXIS_FACING_LOOK_HOLD_CAP_TICKS;
        if (held || fused) {
            if (!held) {
                logMechanic("v3: SHORTHOLD clicking " + BuildAction.describePos(action.cell())
                        + " with the sent look axis stable for only " + this.lookAxisStableTicks
                        + " ticks, wanted " + wanted);
            }
            enter(State.GATE);
        }
        return running(hold());
    }

    /**
     * How long the sent look axis must stand still before this action's click.
     *
     * <p>One for everything whose landed state travels WITH the click — face and hit vector are in the packet, and no
     * amount of waiting makes them truer. The full hold only for the four families vanilla resolves through
     * {@code getNearestLookingDirection}, which reads the server's own entity rotation and therefore whatever the
     * last movement packet happened to leave there.
     *
     * <p>{@link PlacementGeometry#requiredLookDirections} is used purely as a membership test. Its CONTENT is not
     * consulted here on purpose: an observer resolves its facing to the direction looked at while a piston resolves
     * to the opposite, and this hold has no business knowing the difference — that stays where it already is.
     */
    private static int requiredLookHold(BuildAction action) {
        BlockState placed = placedStateOf(action);
        return placed != null && PlacementGeometry.requiredLookDirections(placed) != null
                ? AXIS_FACING_LOOK_HOLD_TICKS : 1;
    }

    // ------------------------------------------------------------------------------------------------- GATE

    /** Places and breaks split here and nowhere else, because they are the two things that must never share a tick. */
    private Outcome gate(BuildAction action) {
        if (pathAlive()) {
            return running(new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL));
        }
        holdPosture(action);
        Rotation rotation = rotationOf(action);
        if (rotation != null) {
            // Re-issued every tick: the look target auto-clears at PlayerUpdateEvent.POST, so not re-issuing it is an
            // active decision to let the head drift while the gate waits.
            princeps.getLookBehavior().updateTarget(rotation, true, isBreak(action), !isBreak(action), true);
        }
        if (action.kind() == BuildAction.Kind.JUMP_PLACE) {
            return jumpGate(action);
        }
        return isBreak(action) ? breakGate(action) : placeGate(action);
    }

    /**
     * The gate for the one action whose click goes out in mid-air.
     *
     * <p>Three states in one method, and the order is the whole of it: on the ground and not yet launched, press
     * JUMP and remember the height it launched from; airborne but not yet clear of the cell, wait; clear of the cell,
     * hand straight over to {@link #placeGate} — the SAME live verification and the SAME click every other placement
     * gets. Nothing about the click is special-cased. What is special is only when it is allowed to happen.
     *
     * <p>The clearance is read from the live body rather than counted in ticks off the jump. {@link JumpArc} proved
     * that a window exists and how wide it is, which is what the PLANNER needed; what authorises the click is the
     * body actually being a full block above where it launched. A client that drops a tick, a server that rubber-bands
     * the position, a jump boost nobody planned for — none of them can produce a click from inside the cell, because
     * the height is measured and not assumed.
     *
     * <p>A landing without a click re-arms rather than fails. That costs a second jump and nothing else, and it is
     * the honest answer to the case the window was missed in.
     */
    private Outcome jumpGate(BuildAction action) {
        double height = ctx.player().getY();
        if (this.jumpLaunchY == NO_LAUNCH) {
            if (!ctx.player().onGround()) {
                this.waitNote = "waiting to be back on the ground before the jump";
                return running(hold());
            }
            this.jumpLaunchY = height;
            princeps.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            this.waitNote = "jumping to clear " + BuildAction.describePos(action.cell());
            return running(hold());
        }
        if (height - this.jumpLaunchY >= JUMP_CLEARANCE) {
            return placeGate(action);
        }
        if (ctx.player().onGround() && height <= this.jumpLaunchY + LANDED_EPSILON) {
            // Back down without ever reaching the window. Re-arm; a second jump is cheap and a click from inside the
            // cell is not.
            this.jumpLaunchY = NO_LAUNCH;
            this.waitNote = "landed without clearing the cell, jumping again";
            return running(hold());
        }
        this.waitNote = "airborne, " + String.format(java.util.Locale.ROOT, "%.3f", height - this.jumpLaunchY)
                + " of " + JUMP_CLEARANCE + " cleared";
        return running(hold());
    }

    /** No jump is in flight. A sentinel and not zero, because zero is a legal world height. */
    private static final double NO_LAUNCH = Double.NEGATIVE_INFINITY;

    /** How far above its launch the body must be before the click is allowed: one full block, which is exactly the
     *  cell it is vacating. Measured live; {@link JumpArc} only proves it is reachable. */
    private static final double JUMP_CLEARANCE = 1.0D;

    /** Slack for "the body is back where it started". A landing settles a hair below the launch height on blocks the
     *  body sinks into, so an exact comparison would never re-arm. */
    private static final double LANDED_EPSILON = 1.0E-3D;

    /** The world height the current jump launched from, or {@link #NO_LAUNCH}. */
    private double jumpLaunchY = NO_LAUNCH;

    private Outcome placeGate(BuildAction action) {
        if (this.watchedAction != null) {
            // The previous placement has server evidence but its revert watch is still open. Everything else about
            // this cell is ready; only the irreversible part waits. In practice this branch is almost never reached,
            // because preparing a cell outlasts the watch -- which is exactly why moving the watch off the critical
            // path was free.
            this.waitNote = "the previous placement at " + BuildAction.describePos(this.watchedAction.cell())
                    + " is still under its revert watch";
            return running(hold());
        }
        PlacementSolution solution = solutionOf(action);
        if (solution == null) {
            // Unreachable: onTick routes every kind ActionRunner.owns away from this gate, and every other kind
            // carries a solution. Stated rather than assumed, because reaching it would mean the routing predicate
            // and the action types had drifted apart.
            return blocked("action kind " + action.kind() + " reached the placement gate with no placement solution");
        }
        BlockHitResult hit = liveBlockHit();
        PlaceGate.Live live = new PlaceGate.Live(
                hit == null ? null : hit.getBlockPos(),
                hit == null ? null : hit.getDirection(),
                hit == null ? null : hit.getLocation(),
                ctx.playerRotations(),
                ctx.player().getMainHandItem(),
                ctx.player().getInventory().getSelectedSlot(),
                ctx.player().isCrouching(),
                placeHelper().isThrottled(),
                consuming(),
                ctx.player().isHandsBusy(),
                entityClear(solution.cell(), solution.desired()),
                hit);
        // The slot comes off the ACTION, not off the solution: the oracle proves a cell placeable whenever the item
        // exists anywhere in the 36 inventory slots, and which hotbar slot the click comes out of is Belady's answer,
        // written in by the schedule.
        PlaceGate.Verdict verdict = PlaceGate.evaluate(solution, action.handSlot(), live, this.settings,
                liveState(solution.cell()), this::simulateVanillaPlacement);
        return switch (verdict.response()) {
            case FIRE -> this.acks.available()
                    ? fireClick(action, solution)
                    : blocked("server acknowledgement is unavailable; refusing to place "
                            + BuildAction.describePos(solution.cell()));
            case RE_EQUIP -> {
                this.idealSoFar = false;
                logMechanic("v3: " + verdict + " at " + BuildAction.describePos(solution.cell()) + " — re-equipping");
                enter(State.EQUIP);
                yield running(hold());
            }
            case DIVERGE -> diverge(action, "the live ray would not produce the proven result: " + verdict
                    + " — " + describeGateMiss(solution, live));
            // No timing here: GATE's patience is checked at the top of onTick, which is also reached by the branches
            // of this state that return before the verdict is asked for at all — the pathAlive() cancel above, and
            // breakGate's wait on `consuming`. What IS recorded here is the verdict, so the divergence names the
            // cause rather than the symptom.
            case WAIT -> {
                // The verdict alone names the SYMPTOM. What a human needs in order to derive the cause without
                // guessing is where the ray actually went against where the plan wanted it, so both are recorded.
                // A whole session was spent reconstructing "WRONG_FACE" by hand from a plan file and a tick trace.
                this.waitNote = verdict + " — " + describeGateMiss(solution, live);
                yield running(hold());
            }
        };
    }

    /**
     * Arm the expectation and force the click, in this tick, in this order, with nothing in between.
     *
     * <p>{@code BlockPlaceHelper.tick} reads the expectation into a local and nulls the field BEFORE it asks whether
     * a right-click was requested. Commit without forcing the key in the same process tick and the commit is gone,
     * with no log line, no counter and no way to tell from the trace that it ever existed.
     */
    private Outcome fireClick(BuildAction action, PlacementSolution solution) {
        if (!this.acks.expectPlacement(solution.cell(), solution.against(), solution.face(), solution.predicted(),
                this.tick)) {
            return diverge(action, "another server expectation is still pending; placement was not sent");
        }
        placeHelper().expectDeterministicMainHandPlacement(solution.against(), solution.face(), solution.cell(),
                action.handSlot(), solution.item());
        princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        this.armedTick = this.tick;
        this.listener.onClickSent(action);
        logMechanic("v3: click " + BuildAction.describePos(solution.cell()) + " against "
                + BuildAction.describePos(solution.against()) + " face " + BuildAction.describeFace(solution.face()));
        enter(State.CLICK);
        return running(hold());
    }

    /**
     * The break gate. {@code BlockBreakHelper} has no target and no expectation — it breaks whatever the crosshair
     * rests on — so the aim is verified against the live ray BEFORE the key goes down, and the key is then held,
     * because a break takes many ticks and a break is not a single click.
     *
     * <p>A position the helper has blacklisted is refused rather than hammered: breaking the same position twice
     * inside four seconds blacklists it PERMANENTLY, and nothing in the codebase ever clears that list, so a bot that
     * kept trying would hold the attack key against an unbreakable cell for the rest of the run.
     */
    private Outcome breakGate(BuildAction action) {
        BlockPos cell = action.cell();
        BlockState live = liveState(cell);
        if (live.isAir()) {
            return advance(action);
        }
        String changed = unexpectedBreakState(action, live);
        if (changed != null) {
            return diverge(action, changed);
        }
        if (breakHelper().isBlacklisted(cell)) {
            return diverge(action, "the break helper has blacklisted " + BuildAction.describePos(cell)
                    + " — it cannot be broken again this session");
        }
        BlockHitResult hit = liveBlockHit();
        if (hit == null || !hit.getBlockPos().equals(cell)) {
            this.waitNote = "the crosshair never reached the cell";
            return running(hold());   // bounded by GATE's patience in onTick
        }
        if (consuming()) {
            this.waitNote = "eating — both helpers are ticked false while consuming";
            return running(hold());
        }
        if (!this.acks.available()) {
            return blocked("server acknowledgement is unavailable; refusing to break "
                    + BuildAction.describePos(cell));
        }
        // CLICK_LEFT and CLICK_RIGHT are never both forced: the two are different action kinds and one action runs at
        // a time. The input handler would clear CLICK_RIGHT anyway, taking any pending placement commit with it.
        if (!this.acks.expectBreak(cell, Blocks.AIR.defaultBlockState(), this.tick)) {
            return diverge(action, "another server expectation is still pending; break was not started");
        }
        breakHelper().requestDeterministicBreak();
        princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        this.armedTick = this.tick;
        enter(State.CONFIRM);
        return running(hold());
    }

    // ------------------------------------------------------------------------------------------------- CONFIRM

    /**
     * The server's answer, not the client's optimism.
     *
     * <p>{@code successfulBlockInteractions} counts what {@code processRightClickBlock} returned, which is a client
     * PREDICTION: the block appears immediately, a protection plugin reverts it two to six ticks later, and a builder
     * that trusts the counter has already walked away. {@link Acknowledgements.Ack#CONFIRMED} is therefore the sole
     * positive answer: it already means authoritative server evidence followed by ServerAck's adaptive hold.
     */
    /**
     * Where the head looks while the server is being waited on.
     *
     * <p>For a BREAK it is the block being broken: letting the head drift there cancels the dig, so that case holds
     * its own aim and buys nothing.
     *
     * <p>For a placement the click has already gone out and the head has no further business with this cell, so the
     * NEXT action's rotation is issued instead and the humanised turn starts up to four ticks early. This is the
     * largest throughput item in the engine and the two halves of it are idle at the same time: over 342 measured
     * placements AIM ran a median of five ticks and CONFIRM a median of four, back to back, and in neither does
     * anything about the world change. Running them concurrently is what a bricklayer does when they look for the
     * next brick while the mortar sets.
     *
     * <p>Speculative and free. The head is the one thing this engine can move without committing to anything: no
     * click is brought forward, and the click for the next cell still waits on this cell's acknowledgement exactly
     * as before. If this placement diverges, the next action never runs and the turn is simply discarded.
     *
     * <p>Only handed a next action that is also a placement, so the flags passed to {@code updateTarget} — which are
     * derived from the CURRENT action — stay correct for the rotation being issued. AIM re-issues with the next
     * action's own flags a moment later in any case; this is a head start, not a substitute.
     */
    private Rotation confirmRotation(BuildAction action) {
        if (isBreak(action)) {
            return rotationOf(action);
        }
        BuildAction next = this.actionIndex + 1 < this.plan.size() ? this.plan.action(this.actionIndex + 1) : null;
        if (next == null || isBreak(next) || ActionRunner.owns(next)) {
            return rotationOf(action);
        }
        Rotation ahead = rotationOf(next);
        return ahead != null ? ahead : rotationOf(action);
    }

    /**
     * Retire or condemn the placement that is under its revert watch. Returns null when there is nothing to answer
     * for and an outcome when the watch turned bad.
     *
     * <p>Runs at the top of every tick, ahead of the plan cursor, so a late revert is answered before the next cell
     * gets any further -- including before a gate could let a click out. The expectation is polled with the arm stamp
     * it was armed with, so this can only ever read its OWN verdict; a later placement's expectation would carry a
     * different stamp and answer CANCELLED rather than lie.
     *
     * <p>CANCELLED is retirement, not failure: it means the expectation was deliberately cleared, by a pause or a
     * reset, and there is no longer anything to be condemned for.
     */
    private Outcome resolveWatch() {
        BuildAction watched = this.watchedAction;
        if (watched == null) {
            return null;
        }
        Acknowledgements.Ack ack = this.acks.poll(watched.cell(), this.watchedArmedTick);
        if (ack == Acknowledgements.Ack.UNKNOWN) {
            return null;
        }
        this.watchedAction = null;
        this.watchedArmedTick = NEVER;
        if (ack == Acknowledgements.Ack.CONFIRMED || ack == Acknowledgements.Ack.CANCELLED) {
            return null;
        }
        this.listener.onLandedWrong(watched, liveState(watched.cell()));
        return diverge(watched, "the placement at " + BuildAction.describePos(watched.cell())
                + " was taken back after the server had acknowledged it: " + ack);
    }

    private Outcome confirm(BuildAction action) {
        // The one state a screen does not stop, so it is also the one state that has to notice a screen itself. While
        // the sign editor the server just opened is up, holding a key types it into the sign and turning the head
        // achieves nothing; polling the acknowledgement is the whole job here and it needs neither.
        // The feet, resolved before the posture because it governs the posture: a body forced into a crouch walks at
        // a third of its speed, so a walk-ahead that crouches the whole way would not be worth starting.
        PathingCommand feet = isBreak(action) ? null : walkAheadCommand();
        if (ctx.minecraft().screen == null) {
            if (feet == null) {
                holdPosture(action);
                Rotation rotation = confirmRotation(action);
                if (rotation != null) {
                    princeps.getLookBehavior().updateTarget(rotation, true, isBreak(action), !isBreak(action), true);
                }
            }
            // Else the path owns the head as well as the feet, and nothing is issued here. The steering turns the
            // body BY turning the head, so forcing a look at the next cell while walking would aim the body at the
            // cell instead of along the path. The two lookaheads are alternatives rather than partners, and they
            // cover different cells: the head one pays for cells that need no walk at all, which is the median cell,
            // and this one pays for the tail of long walks, which is what dominates the total.
        }
        if (isBreak(action)) {
            return confirmBreak(action);
        }
        BlockPos cell = action.cell();
        BlockState expected = placedStateOf(action);
        BlockState live = liveState(cell);
        Acknowledgements.Ack ack = this.acks.poll(cell, this.armedTick);
        String ackFailure = acknowledgementFailure(ack);
        if (ackFailure != null) {
            this.listener.onLandedWrong(action, live);
            return diverge(action, ackFailure + " at " + BuildAction.describePos(cell));
        }
        boolean liveMatches = matchesPlacedState(action, live, expected);
        if (acknowledgementAllowsCompletion(ack, liveMatches)) {
            this.listener.onCellConfirmed(action, this.idealSoFar);
            return advance(action);
        }
        // Server evidence is in and only the revert watch is still running. Those ticks used to be spent standing
        // still, and they were the largest fixed per-block cost left in the engine: a median of five, against a
        // vanilla floor of four for the whole placement. Hand the cell over to the watch and let the next one start
        // selecting, equipping, walking and aiming underneath it.
        //
        // Nothing about the proof is weakened. The watch keeps running -- ack.tick() is driven every tick from the
        // process, not from this state -- resolveWatch() diverges the moment it turns bad, and no click can go out
        // while it is unresolved, which is the only act that could not be taken back. The next cell's preparation
        // takes longer than the watch does in every measured case, so the guard costs nothing at all now.
        if (ack == Acknowledgements.Ack.UNKNOWN && liveMatches && this.acks.watching()) {
            this.watchedAction = action;
            this.watchedArmedTick = this.armedTick;
            this.listener.onCellConfirmed(action, this.idealSoFar);
            return advance(action);
        }
        if (ack == Acknowledgements.Ack.CONFIRMED) {
            // The ack completed its hold, but the world changed again before this poll. Never retire stale evidence.
            this.listener.onLandedWrong(action, live);
            return diverge(action, "the confirmed placement changed before retirement at "
                    + BuildAction.describePos(cell) + ": " + BuildAction.describeState(live)
                    + " instead of " + BuildAction.describeState(expected));
        }
        if (!liveMatches && !live.canBeReplaced()) {
            // Something landed and it is not what was proven. Never break-and-replace here: E3 says right on the first
            // attempt, and tearing down to correct is what produced a cell broken forty-eight times.
            this.listener.onLandedWrong(action, live);
            return diverge(action, "wrong block landed at " + BuildAction.describePos(cell) + ": "
                    + BuildAction.describeState(live) + " instead of " + BuildAction.describeState(expected));
        }
        if (this.armedTick != NEVER && this.tick - this.armedTick > ACK_FAILSAFE_TIMEOUT_TICKS) {
            return diverge(action, "the acknowledgement adapter stayed UNKNOWN for "
                    + ACK_FAILSAFE_TIMEOUT_TICKS + " ticks at " + BuildAction.describePos(cell));
        }
        return running(feet != null ? feet : hold());
    }

    /**
     * Where the feet go while the server is being waited on, or {@code null} for "stand still".
     *
     * <p>The same trade the head lookahead makes, one limb further down. Waiting for an acknowledgement costs a
     * median of five ticks and the walk to the next proven stance costs seven, and the walk's destination is already
     * known before the current cell is even clicked — so the two run concurrently and the wait disappears inside the
     * walk entirely, leaving only the two ticks of walking that outlast it.
     *
     * <p>Nothing is committed early. Walking is as reversible as turning the head: the click for the next cell still
     * waits on this cell's acknowledgement, and a cell that diverges costs a walk that has to be undone rather than a
     * block that landed wrong. A BREAK is excluded by the caller — letting the body wander cancels the dig.
     *
     * <p>Returns null when the next stance is the one already occupied, which is the common case after the walking
     * optimisation: the executor exhausts every placement reachable from a stance before moving. That case must not
     * start a path, because AIM cancels any live path and would spend a tick doing it.
     */
    private PathingCommand walkAheadCommand() {
        if (this.plan == null || this.actionIndex + 1 >= this.plan.size()) {
            return null;
        }
        BuildAction next = this.plan.action(this.actionIndex + 1);
        if (next == null || isBreak(next) || ActionRunner.owns(next)) {
            return null;
        }
        BlockPos stance = stanceOf(next);
        if (stance == null || ctx.playerFeet().equals(stance)) {
            return null;
        }
        return new PathingCommandContext(new GoalBlock(stance), PathingCommandType.SET_GOAL_AND_PATH,
                this.calcContext.get());
    }

    private Outcome confirmBreak(BuildAction action) {
        BlockPos cell = action.cell();
        BlockState live = liveState(cell);
        Acknowledgements.Ack ack = this.acks.poll(cell, this.armedTick);
        String ackFailure = acknowledgementFailure(ack);
        if (ackFailure != null) {
            return diverge(action, ackFailure + " for the break at " + BuildAction.describePos(cell));
        }
        boolean removed = live.isAir();
        if (acknowledgementAllowsCompletion(ack, removed)) {
            this.listener.onBlockBroken(cell);
            return advance(action);
        }
        if (ack == Acknowledgements.Ack.CONFIRMED) {
            return diverge(action, "the server-confirmed break changed before retirement at "
                    + BuildAction.describePos(cell) + ": found " + BuildAction.describeState(live));
        }
        if (this.armedTick != NEVER && this.tick - this.armedTick > ACK_FAILSAFE_TIMEOUT_TICKS * 4L) {
            // A hard block may legitimately take far longer than a packet round trip. This is only a broken-helper
            // failsafe; once STOP_DESTROY_BLOCK is sent, ServerAck owns the much smaller adaptive server window.
            //
            // Asked BEFORE the `removed` branch below, not after it. Client-side air with an acknowledgement that
            // never answers is precisely the shape this failsafe is for, and behind that early return it could not
            // fire on the one path that needed it.
            return diverge(action, "breaking " + BuildAction.describePos(cell) + " made no progress");
        }
        if (removed) {
            // Client-side air is only optimism until the server evidence and hold finish.
            return running(hold());
        }
        String changed = unexpectedBreakState(action, live);
        if (changed != null) {
            return diverge(action, changed);
        }
        if (breakHelper().isBlacklisted(cell)) {
            return diverge(action, "the break helper blacklisted " + BuildAction.describePos(cell) + " mid-break");
        }
        BlockHitResult hit = liveBlockHit();
        // Never with a screen up: CONFIRM runs under one so the sign placement that opened it can retire, and holding
        // the attack key through a GUI is the one thing this state must not do while it is exempt.
        if (ctx.minecraft().screen == null && hit != null && hit.getBlockPos().equals(cell) && !consuming()) {
            breakHelper().requestDeterministicBreak();
            princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        }
        return running(hold());
    }

    /** The only positive completion rule, isolated so UNKNOWN/TIMED_OUT can be pinned headlessly. */
    static boolean acknowledgementAllowsCompletion(Acknowledgements.Ack ack, boolean liveMatches) {
        return ack == Acknowledgements.Ack.CONFIRMED && liveMatches;
    }

    /** A terminal acknowledgement sentence, or null while the server is still answering. */
    private static String acknowledgementFailure(Acknowledgements.Ack ack) {
        return switch (ack) {
            case REVERTED -> "the server reverted the change";
            case TIMED_OUT -> "the server acknowledgement timed out";
            case CANCELLED -> "the server acknowledgement was cancelled";
            case NOT_SENT -> "the local controller never sent the action packet";
            case UNLOADED -> "the action chunk unloaded before server confirmation";
            case UNAVAILABLE -> "server acknowledgement is unavailable";
            case UNKNOWN, CONFIRMED -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------- DONE

    private Outcome advance(BuildAction action) {
        this.actionIndex++;
        resetPerAction();
        enter(State.SELECT);
        if (this.actionIndex >= this.plan.size()) {
            logMechanic("v3: the frozen order is complete — " + this.plan.size() + " actions");
            return new Outcome(Status.FINISHED, hold(), State.DONE, null);
        }
        return running(hold());
    }

    // ------------------------------------------------------------------------------------------------- DELEGATION

    /*
     * The right-clicks and the typing. Everything below this line is translation and nothing here decides anything:
     * ActionRunner is handed a record of what the client sees and answers with a record of what to do, and this half
     * turns that answer into keys, a look target and a screen. The decisions live there because they are the ones
     * that fail SILENTLY -- a repeater at delay 1, a blank sign, a bucket emptied into the wrong cell -- and a class
     * that touches ctx cannot be tested, while these fifty lines of translation can be read.
     */

    /**
     * One tick of an action the runner owns.
     *
     * <p>The runner is built lazily and thrown away with the action, which is what makes "one instance runs exactly
     * one action" true rather than aspirational: {@link #resetPerAction} drops it, so an action can never inherit the
     * half-finished state of the one before it.
     */
    private Outcome delegate(BuildAction action) {
        if (pathAlive()) {
            // Phase one of the two-phase handoff, and it happens BEFORE the runner is asked anything.
            // CANCEL_AND_SET_GOAL runs clearAllKeys() inside the cancel, so no key forced this tick would survive it
            // — and a runner ticked here would answer CLICK_RIGHT, record that a click went out, and then wait forty
            // ticks for the effect of a click that was wiped before it left.
            return running(new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL));
        }
        if (this.runner == null || this.runner.action() != action) {
            this.runner = new ActionRunner(action);
        }
        return apply(action, this.runner.tick(observe(action)));
    }

    /** What the client says this tick. Values only — the runner is handed no live object it could read twice and get
     *  two answers from. */
    private ActionRunner.Observation observe(BuildAction action) {
        BlockHitResult hit = liveBlockHit();
        ItemStack held = ctx.player().getMainHandItem();
        return new ActionRunner.Observation(
                this.tick,
                liveState(action.cell()),
                ctx.playerRotations(),
                rotationOf(action),
                hit == null ? null : hit.getBlockPos(),
                hit == null ? null : hit.getDirection(),
                ctx.player().getInventory().getSelectedSlot(),
                // An EMPTY hand is null and never Items.AIR. The runner refuses an interaction whose hand holds a
                // BlockItem, because a mis-aimed right-click with a placeable in hand is a wrong build rather than a
                // miss — and the empty hand is the one hand that provably cannot place.
                held.isEmpty() ? null : held.getItem(),
                placeHelper().isThrottled(),
                consuming(),
                screenState());
    }

    /**
     * Which screen is open, in the runner's vocabulary.
     *
     * <p>{@code signEditor} stays TRUE when the accessor mixin is missing, and that asymmetry is deliberate. Reported
     * as "some other screen" the editor would be CLOSED, which loses the sign's text without a word; reported as an
     * editor that cannot say which sign it edits it is REFUSED by name instead. Between a silent loss and a loud stop
     * this engine takes the stop every time.
     */
    private ActionRunner.ScreenState screenState() {
        Screen screen = ctx.minecraft().screen;
        if (screen == null) {
            return ActionRunner.ScreenState.NONE;
        }
        if (!(screen instanceof AbstractSignEditScreen)) {
            return ActionRunner.ScreenState.other();
        }
        return screen instanceof ISignEditScreen editor
                ? new ActionRunner.ScreenState(true, true, editor.getEditedSignPos(), editor.isEditingFrontText())
                : new ActionRunner.ScreenState(true, true, null, false);
    }

    /** Carry out exactly one directive. One per tick, which is what structurally prevents {@code CLICK_LEFT} and
     *  {@code CLICK_RIGHT} ever sharing a tick — the runner cannot express the sentence. */
    private Outcome apply(BuildAction action, ActionRunner.Directive directive) {
        enterIfDifferent(stateFor(directive));
        // The runner's own sentence, which is always more specific than anything this class could reconstruct. Set
        // AFTER the transition, because enter() clears it.
        this.waitNote = directive;
        return switch (directive) {
            // Aim and Wait are the same instruction to the body: hold the stance, keep steering the head. The look
            // target auto-clears at PlayerUpdateEvent.POST, so re-issuing it every tick is what stops the crosshair
            // drifting off a face while the gate waits for it.
            case ActionRunner.Directive.Aim aim -> steer(action, aim.sneak());
            case ActionRunner.Directive.Wait wait -> steer(action, wait.sneak());
            case ActionRunner.Directive.ClickRight click -> fireInteraction(action, click);
            case ActionRunner.Directive.TypeSign type -> typeSign(action, type);
            case ActionRunner.Directive.CloseScreen close -> {
                logMechanic("v3: " + close.describe());
                ctx.minecraft().setScreen(null);
                yield running(hold());
            }
            case ActionRunner.Directive.Finished done -> retire(action, done);
            case ActionRunner.Directive.Blocked stop -> blocked(stop.reason());
        };
    }

    /** The executor state a directive corresponds to, so {@code act=} in the per-tick trace keeps meaning the same
     *  thing whether the executor or the runner produced the tick. */
    static State stateFor(ActionRunner.Directive directive) {
        return switch (directive) {
            case ActionRunner.Directive.Aim ignored -> State.AIM;
            case ActionRunner.Directive.Wait ignored -> State.GATE;
            case ActionRunner.Directive.CloseScreen ignored -> State.GATE;
            case ActionRunner.Directive.TypeSign ignored -> State.CLICK;
            case ActionRunner.Directive.ClickRight ignored -> State.CLICK;
            case ActionRunner.Directive.Finished ignored -> State.CONFIRM;
            case ActionRunner.Directive.Blocked ignored -> State.BLOCKED;
        };
    }

    /**
     * Hold the stance and keep the head on the proven point.
     *
     * <p>{@code sneak} comes off the DIRECTIVE and is asserted only when it is true, which is the whole of honouring
     * {@code BuildAction.sneakState}: the process clears every forced key at the top of its tick, so not forcing it is
     * releasing it. An interaction is the one action that must be standing — a crouched right-click on a repeater
     * PLACES the held block instead of stepping the delay — so it is released here even though APPROACH sneak-walked
     * to the point, and the runner then waits for the live ray to arrive from the standing eye the plan proved it at.
     */
    private Outcome steer(BuildAction action, boolean sneak) {
        if (sneak) {
            princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        Rotation rotation = rotationOf(action);
        if (rotation != null) {
            princeps.getLookBehavior().updateTarget(rotation, true, false, true, true);
        }
        // The slot is the executor's and nobody else's, so it is RE-asserted here rather than set once in EQUIP. The
        // runner answers a drifted slot with "slot 3 selected, this needs slot 0" — a WAIT, and a wait with no
        // ceiling, because nothing inside the runner can touch an inventory. Left uncorrected that is a stall that
        // prints its own reason every tick and never stops.
        if (ctx.player().getInventory().getSelectedSlot() != action.handSlot()) {
            ctx.player().getInventory().setSelectedSlot(action.handSlot());
        }
        return running(hold());
    }

    /**
     * One right-click at the world, this tick.
     *
     * <p>Nothing is armed on {@code BlockPlaceHelper}, and that is not an omission.
     * {@code expectMainHandPlacement} is a PLACEMENT guard: it demands that the clicked position, the clicked face
     * and the resulting cell line up as {@code hit.relative(face) == target}. A repeater step produces no such triple
     * — the effect cell IS the clicked cell — and a waterlogging bucket produces the same degenerate one, so arming
     * it would make {@code matches} answer false and VOID the very click it was meant to protect, in silence. The
     * stale expectation of the placement before is cleared for the same reason, one line down.
     *
     * <p>What IS required is the acknowledgement channel. The runner confirms this click against the live world and
     * holds it for three ticks, which is honest but weaker than the packet-backed evidence a placement gets, so at
     * minimum the engine does not touch the world with its evidence channel switched off.
     */
    private Outcome fireInteraction(BuildAction action, ActionRunner.Directive.ClickRight click) {
        if (click.armPlacement()) {
            return blocked("the runner asked for an armed placement at " + BuildAction.describePos(action.cell())
                    + "; every placement is the executor's and none of them comes through here");
        }
        if (!this.acks.available()) {
            return blocked("server acknowledgement is unavailable; refusing to right-click "
                    + BuildAction.describePos(action.cell()));
        }
        if (click.sneak()) {
            princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        Rotation rotation = rotationOf(action);
        if (rotation != null) {
            princeps.getLookBehavior().updateTarget(rotation, true, false, true, true);
        }
        if (ctx.player().getInventory().getSelectedSlot() != click.handSlot()) {
            // The runner refuses to click from the wrong slot and the slot is the executor's to set; between them
            // that is one rule with two halves rather than a click sent with whatever happened to be in hand.
            ctx.player().getInventory().setSelectedSlot(click.handSlot());
            return running(hold());
        }
        placeHelper().clearExpectedPlacement();
        princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        // armedTick is deliberately NOT stamped. It is the token a CONFIRM polls its acknowledgement with, and this
        // click has none to poll: the runner confirms it against the live world instead. Stamping it would leave a
        // failsafe window open around evidence nobody is collecting.
        this.listener.onClickSent(action);
        logMechanic("v3: " + action.kind() + " at " + BuildAction.describePos(action.cell()) + " — "
                + click.describe());
        return running(hold());
    }

    /**
     * Put the four lines into the open editor and confirm them.
     *
     * <p>Through the screen's own {@code charTyped} and {@code keyPressed}, which is the path a keyboard takes, and
     * then {@code setScreen(null)} — vanilla's {@code removed()} is what builds and sends
     * {@code ServerboundSignUpdatePacket}. Nothing is written into the sign's fields and no packet is constructed
     * here.
     *
     * <p>Both refusals below are {@code blocked} and neither is a divergence, which is the opposite of this class's
     * habit and is the point. A divergence re-plans, and by the time the text can be typed the sign BLOCK is already
     * standing — so the re-plan finds the cell satisfied, emits nothing for it, and the build finishes successfully
     * with a blank sign in it. Text is the one thing in this engine whose loss leaves no trace to re-plan from.
     */
    private Outcome typeSign(BuildAction action, ActionRunner.Directive.TypeSign type) {
        if (!(ctx.minecraft().screen instanceof AbstractSignEditScreen editor)) {
            return blocked("the sign editor at " + BuildAction.describePos(type.cell())
                    + " closed between the runner's decision and the keystrokes");
        }
        List<String> lines = signLines(type.lines());
        if (!this.acks.available()) {
            return blocked("server acknowledgement is unavailable; refusing to type the sign at "
                    + BuildAction.describePos(type.cell()));
        }
        if (!this.acks.expectSign(type.cell(), type.frontSide(), lines, this.tick)) {
            return blocked("another server expectation is still pending; the sign text at "
                    + BuildAction.describePos(type.cell()) + " was not typed");
        }
        this.armedTick = this.tick;
        for (int line = 0; line < lines.size(); line++) {
            // Code points and not chars: charTyped takes one code point, so splitting a surrogate pair into two
            // events types two replacement characters onto the sign instead of the one glyph the schematic carries.
            for (int codePoint : lines.get(line).codePoints().toArray()) {
                editor.charTyped(new CharacterEvent(codePoint));
            }
            if (line < lines.size() - 1) {
                // KEY_DOWN is what AbstractSignEditScreen reads as "next line" — it steps its own line index and
                // moves the shared text field with it. The last line is deliberately not followed by one: the index
                // wraps 3 -> 0, and a stray wrap would leave the cursor on a line already written.
                editor.keyPressed(new KeyEvent(GLFW.GLFW_KEY_DOWN, 0, 0));
            }
        }
        ctx.minecraft().setScreen(null);
        this.listener.onClickSent(action);
        logMechanic("v3: sign at " + BuildAction.describePos(type.cell()) + " typed " + lines);
        return running(hold());
    }

    /** Exactly {@link SignNbt#LINES} lines, blanks included. A shorter planned list means blank remainder lines and
     *  never "leave as is", so the padding is part of what the acknowledgement is asked to confirm. */
    static List<String> signLines(List<String> planned) {
        List<String> padded = new ArrayList<>(SignNbt.LINES);
        for (int line = 0; line < SignNbt.LINES; line++) {
            padded.add(line < planned.size() ? planned.get(line) : "");
        }
        return List.copyOf(padded);
    }

    /**
     * The runner says the action is done. Retire it — with one exception.
     *
     * <p>A sign's {@code Finished} means the EDITOR CLOSED, which proves the packet went out and nothing more. Every
     * other action in the engine is confirmed by a block, and a block that did not land leaves a cell the next plan
     * will try again. Text leaves nothing: the sign stands either way, so a lost line reads as a satisfied cell for
     * ever. So this is the one retirement that waits for the server's own answer, and a failure here stops the build
     * by name rather than re-planning into a cell that no longer has an action.
     */
    private Outcome retire(BuildAction action, ActionRunner.Directive.Finished done) {
        if (action instanceof BuildAction.WriteSign sign) {
            Acknowledgements.Ack ack = this.acks.poll(sign.cell(), this.armedTick);
            String failure = acknowledgementFailure(ack);
            if (failure != null) {
                return blocked(failure + " for the sign text at " + BuildAction.describePos(sign.cell())
                        + "; the sign stands and its text did not arrive");
            }
            if (ack != Acknowledgements.Ack.CONFIRMED) {
                if (this.armedTick != NEVER && this.tick - this.armedTick > ACK_FAILSAFE_TIMEOUT_TICKS) {
                    return blocked("the acknowledgement adapter stayed UNKNOWN for " + ACK_FAILSAFE_TIMEOUT_TICKS
                            + " ticks after the sign at " + BuildAction.describePos(sign.cell()) + " was typed");
                }
                return running(hold());
            }
        }
        logMechanic("v3: " + done.describe());
        this.listener.onCellConfirmed(action, this.idealSoFar);
        return advance(action);
    }

    // ------------------------------------------------------------------------------- divergence and preconditions

    /**
     * The plan's preconditions against the LIVE world. Eight state reads, before every action, and they replace every
     * watchdog the previous engine carried.
     *
     * @return the reason, or null when the plan still holds
     */
    private String checkPreconditions(BuildAction action) {
        BlockPos cell = action.cell();
        if (cell == null) {
            return null;   // a hotbar swap has no geometry to diverge from
        }
        if (!chunkLoaded(cell)) {
            return "the chunk around " + BuildAction.describePos(cell) + " is no longer loaded";
        }
        PlacementSolution solution = solutionOf(action);
        if (solution != null) {
            BlockState at = liveState(cell);
            if (!at.canBeReplaced()) {
                return "the target cell " + BuildAction.describePos(cell) + " is occupied by "
                        + BuildAction.describeState(at);
            }
            if (!chunkLoaded(solution.against())) {
                return "the click-neighbour chunk at " + BuildAction.describePos(solution.against())
                        + " is no longer loaded";
            }
            BlockState against = liveState(solution.against());
            if (against.canBeReplaced()) {
                return "the click neighbour " + BuildAction.describePos(solution.against()) + " is gone";
            }
            // "Still that state" cannot be asked directly — the solution records the neighbour's position but not its
            // state. What matters about it is asked instead: does its live OUTLINE still reach the exact point the
            // plan aims at. Vanilla's crosshair/use clip is ClipContext.OUTLINE, not collision: repeaters and torches
            // have no collision but remain clickable, while slabs and trapdoors expose only their actual outline.
            // A neighbour that changed colour may still be equivalent here; the authoritative live crosshair and
            // placement simulation below remain the final answer.
            AABB box = liveOutlineBox(solution.against(), against);
            if (!box.inflate(1.0E-3D).contains(solution.aimPoint())) {
                return "the click face at " + BuildAction.describePos(solution.against())
                        + " no longer reaches the proven aim point " + BuildAction.describePoint(solution.aimPoint());
            }
            String stance = checkStanceAndRay(solution.stance(), solution.approach(), solution.aimPoint(),
                    solution.against(), PlayerPose.CROUCHED);
            if (stance != null) {
                return stance;
            }
            return null;
        }
        BlockPos stancePos = stanceOf(action);
        if (stancePos != null) {
            if (isBreak(action)) {
                BlockState live = liveState(cell);
                if (live.isAir()) {
                    return null;   // already gone; SELECT skips it rather than diverging
                }
                String changed = unexpectedBreakState(action, live);
                if (changed != null) {
                    return changed;
                }
            }
            // Neither the target nor the eye is a constant here, and both were wrong for the two kinds this engine
            // could not run until now. A bucket's destination is EMPTY and cannot be clicked, so the proof aims at
            // the solid neighbour and the fluid lands on the far side of the hit face — re-checking the ray against
            // the destination reports it blocked by the very block it is meant to hit. And an interaction is the one
            // action vanilla forces to STAND, so OrderPlanner proves its aim from the standing eye; re-checking that
            // ray from the crouched one moves the origin 0.35 blocks and reports a divergence that is arithmetic
            // rather than a changed world.
            return checkStanceAndRay(stancePos, approachOf(action), aimPointOf(action),
                    ActionRunner.rayTargetOf(action), proofPose(action));
        }
        return null;
    }

    /**
     * The eye the action's aim was proven from.
     *
     * <p>Keyed on the KIND and not on {@code sneak}, because the two answer different questions and disagree exactly
     * once: a paired chest is placed standing so that vanilla does not force it to SINGLE, but its geometry was still
     * proven from the crouched eye like every other placement. {@code INTERACT} is the only action proven standing —
     * {@code OrderPlanner} asks {@code BreakPlanner.aim} for {@link PlayerPose#STANDING} there, and this is the one
     * other place that has to agree with it.
     */
    static PlayerPose proofPose(BuildAction action) {
        return action.kind() == BuildAction.Kind.INTERACT ? PlayerPose.STANDING : PlayerPose.CROUCHED;
    }

    /** The two halves of a stance that can rot underneath a plan: the floor it rests on and the line of sight it was
     *  proven along. */
    private String checkStanceAndRay(BlockPos stance, Vec3 approach, Vec3 aimPoint, BlockPos target,
                                     PlayerPose pose) {
        if (stance == null || approach == null || aimPoint == null) {
            return null;
        }
        if (!chunkLoaded(stance)) {
            return "the proven stance chunk at " + BuildAction.describePos(stance) + " is no longer loaded";
        }
        if (!liveStandable(stance)) {
            return "the proven stance " + BuildAction.describePos(stance) + " is no longer standable";
        }
        boolean[] crossedUnloaded = {false};
        GridRay.Hit hit = GridRay.cast((x, y, z) -> {
            if (!ctx.world().getChunkSource().hasChunk(x >> 4, z >> 4)) {
                crossedUnloaded[0] = true;
                return true;
            }
            return liveSolidFullCube(new BlockPos(x, y, z));
        }, pose.eyeAt(approach), aimPoint);
        if (crossedUnloaded[0]) {
            return "the ray to " + BuildAction.describePoint(aimPoint) + " crosses an unloaded chunk";
        }
        if (hit != null && !(hit.x() == target.getX() && hit.y() == target.getY() && hit.z() == target.getZ())) {
            return "the ray to " + BuildAction.describePoint(aimPoint) + " is blocked at "
                    + hit.x() + "," + hit.y() + "," + hit.z();
        }
        return null;
    }

    private boolean chunkLoaded(BlockPos cell) {
        return ctx.world().getChunkSource().hasChunk(cell.getX() >> 4, cell.getZ() >> 4);
    }

    private AABB liveOutlineBox(BlockPos cell, BlockState state) {
        return clickableOutlineBox(state, ctx.world(), cell);
    }

    /**
     * World-space bounds of the shape Vanilla's block crosshair actually clips.
     *
     * <p>Do not replace this with {@link BlockState#getCollisionShape}: collision is the right shape for body/entity
     * obstruction, but not for use-on-block. Redstone components and torches deliberately have an empty collision
     * shape and a non-empty outline. Package-visible so representative vanilla partial shapes are pinned headlessly.
     */
    static AABB clickableOutlineBox(BlockState state, BlockGetter world, BlockPos cell) {
        VoxelShape shape = state.getShape(world, cell);
        return shape.isEmpty()
                ? new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX(), cell.getY(), cell.getZ())
                : shape.bounds().move(cell.getX(), cell.getY(), cell.getZ());
    }

    private boolean liveSolidFullCube(BlockPos cell) {
        BlockState state = liveState(cell);
        if (state.isAir()) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private boolean liveStandable(BlockPos stance) {
        BlockState feet = liveState(stance);
        BlockState head = liveState(stance.above());
        BlockState floor = liveState(stance.below());
        return feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && floor.getFluidState().isEmpty()
                && canWalkThrough(feet, floor)
                && canWalkThrough(head, feet)
                && MovementHelper.canWalkOnBlockState(floor, this.settings) == Ternary.YES;
    }

    private boolean canWalkThrough(BlockState state, BlockState below) {
        Ternary result = MovementHelper.canWalkThroughBlockState(state, this.settings);
        if (result != Ternary.MAYBE) {
            return result == Ternary.YES;
        }
        if (state.getBlock() instanceof CarpetBlock) {
            return MovementHelper.canWalkOnBlockState(below, this.settings) == Ternary.YES;
        }
        return state.isPathfindable(PathComputationType.LAND);
    }

    /**
     * The hard layer bound, against the world rather than against the bookkeeping.
     *
     * <p>Every covered schematic cell in the layers below must be correct in the LIVE world, including AIR and cells
     * that were already correct when planning produced no action for them; every helper block placed there must also
     * be gone or have become its finished lower-layer schematic state. Checked once per boundary and cached: a layer
     * of a large schematic is thousands of cells, and asking per tick would be the most expensive thing this class
     * does for an answer that cannot change while the executor is standing still.
     *
     * @return the first offender, or null when the boundary is clear
     */
    private String layerGate(int layer) {
        String schematicViolation = schematicLayerViolation(this.view, this.settings, layer, this::liveState);
        if (schematicViolation != null) {
            return schematicViolation;
        }
        for (int index = 0; index < this.plan.size(); index++) {
            BuildAction earlier = this.plan.action(index);
            if (earlier.layer() >= layer || !(earlier instanceof BuildAction.PlaceScaffold scaffold)) {
                continue;
            }
            BlockState live = liveState(scaffold.cell());
            BlockState desired = this.view.desired(scaffold.cell());
            boolean becameFinishedSchematicCell = desired != null && !desired.isAir()
                    && this.view.layerOf(scaffold.cell()) < layer
                    && this.settings.valid(live, desired, false);
            if (!live.isAir() && !becameFinishedSchematicCell) {
                return "layer " + layer + " may not start: former helper cell "
                        + BuildAction.describePos(scaffold.cell()) + " holds " + BuildAction.describeState(live)
                        + " instead of AIR or its finished lower-layer schematic state";
            }
        }
        return null;
    }

    @FunctionalInterface
    interface LiveStates {

        BlockState at(BlockPos cell);
    }

    /**
     * The hard layer invariant over the frozen schematic, including cells that were already correct at plan time and
     * therefore have no {@link BuildPlan.CellProof}. Air cells are covered too.
     */
    static String schematicLayerViolation(SchematicView view, V3Settings settings, int newLayer, LiveStates live) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(live, "live");
        int topExclusive = Math.min(newLayer, view.max().getY() + 1);
        for (int y = view.min().getY(); y < topExclusive; y++) {
            for (int x = view.min().getX(); x <= view.max().getX(); x++) {
                for (int z = view.min().getZ(); z <= view.max().getZ(); z++) {
                    BlockPos cell = new BlockPos(x, y, z);
                    BlockState desired = view.desired(cell);
                    if (desired == null) {
                        continue;
                    }
                    BlockState observed = live.at(cell);
                    if (!settings.valid(observed, desired, false)) {
                        return "layer " + newLayer + " may not start: " + BuildAction.describePos(cell)
                                + " in layer " + y + " holds " + BuildAction.describeState(observed)
                                + " instead of " + BuildAction.describeState(desired);
                    }
                }
            }
        }
        return null;
    }

    /** Is this action's result already in the world? */
    private boolean alreadySatisfied(BuildAction action) {
        if (action instanceof BuildAction.SwapHotbar swap) {
            return hotbarStack(swap.hotbarSlot()).getItem() == swap.item();
        }
        BlockPos cell = action.cell();
        if (cell == null) {
            return false;
        }
        if (isBreak(action)) {
            return liveState(cell).isAir();
        }
        // The two kinds whose result is a state change rather than a block. Without these the executor would walk to
        // a repeater that already reads delay 3 and only discover it after the whole approach — and, worse, a re-plan
        // would re-emit the interaction for a cell that is finished, which is the loop this method exists to break.
        // Never asked while the runner is mid-action; see onTick.
        if (action instanceof BuildAction.Interact interact) {
            return PlacementGeometry.interactionClicks(liveState(cell), interact.target()) == 0;
        }
        if (action instanceof BuildAction.FillFluid fluid) {
            return FluidPlan.satisfies(liveState(cell), fluid.expected());
        }
        // Never WRITE_SIGN: the text lives in a block entity this method does not read, and typing four lines the
        // sign already carries is free, while skipping them because the BLOCK is there is the silent loss.
        BlockState desired = desiredOf(action);
        return desired != null && this.settings.valid(liveState(cell), desired, false);
    }

    // ------------------------------------------------------------------------------------------------- plumbing

    private Outcome diverge(BuildAction action, String reason) {
        this.idealSoFar = false;
        this.stopReason = reason;
        this.state = State.DIVERGED;
        this.listener.onDivergence(action, reason);
        logMechanic("v3: DIVERGENCE at action " + this.actionIndex + " — " + reason);
        return new Outcome(Status.DIVERGED, hold(), this.state, reason);
    }

    private Outcome blocked(String reason) {
        this.stopReason = reason;
        this.state = State.BLOCKED;
        return new Outcome(Status.BLOCKED, new PathingCommand(null, PathingCommandType.DEFER), this.state, reason);
    }

    private Outcome running(PathingCommand command) {
        return new Outcome(Status.RUNNING, command, this.state, null);
    }

    /** Stand still without claiming a goal. {@code CANCEL_AND_SET_GOAL} with a null goal is what the V2 builder uses
     *  to settle in a stance, and it is the only command that does not either hand control back or set a destination
     *  the executor does not want. */
    private PathingCommand hold() {
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** The engine's own sentence about why it is waiting, for the trace. Null when it is not waiting on anything. */
    public String waitNote() {
        return this.waitNote == null ? null : String.valueOf(this.waitNote);
    }

    private void enter(State next) {
        if (next != State.GATE) {
            // A jump belongs to the gate it was launched from. Leaving the gate for any reason -- advance, divergence,
            // a re-plan -- ends it, so the next gate never inherits a launch height from an action that is over.
            this.jumpLaunchY = NO_LAUNCH;
        }
        this.state = next;
        this.stateEnteredTick = this.tick;
        // The note belongs to the wait, and a new state is a new wait. Left standing it would explain the previous
        // state's stall in the next state's sentence, which is worse than no note at all.
        this.waitNote = null;
    }

    /** Enter only on a real transition. The delegated states are re-derived from the runner's directive every tick,
     *  and re-entering the state it is already in would restart every patience counter based on
     *  {@link #stateEnteredTick} once per tick — a timeout that can never fire. */
    private void enterIfDifferent(State next) {
        if (this.state != next) {
            enter(next);
        }
    }

    private void resetPerAction() {
        this.acks.clear();
        this.actionStartedTick = this.tick;
        this.screenBlockedSinceTick = NEVER;
        // Per-action, unlike lookAxisStableTicks: a fuse that inherited its start from a finished action would fire
        // on the very first evaluation of the next one.
        this.axisHoldSince = NEVER;
        princeps.getInventoryBehavior().clearBuilderHotbarLock();
        this.runner = null;
        this.settleTicks = 0;
        this.approachEntrySettled = false;
        this.swapAttempts = 0;
        this.lastSwapTick = NEVER;
        this.armedTick = NEVER;
        this.idealSoFar = true;
        this.walking = false;
        this.travelPrecheckRequested = false;
        this.travelPrecheckReady = false;
        // Per action, so one hard walk cannot spend the allowance of every later one.
        this.travelRepaths = 0;
        this.travelStuckTicks = 0;
        this.unwedgeTicks = 0;
        this.unwedgeAim = null;
        this.lastTravelPos = null;
        this.lastTravelIndex = -1;
    }

    /** The posture the action carries. Sneak is per action and not global for a reason that bites both ways: a
     *  crouched right-click on a repeater places the held block instead of stepping the delay, and an uncrouched
     *  placement of a chest is the only way to build a double chest at all. */
    private void holdPosture(BuildAction action) {
        if (action.sneak()) {
            princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
    }

    private boolean pathAlive() {
        return princeps.getPathingBehavior().getCurrent() != null;
    }

    private boolean consuming() {
        return princeps.getSurvivalBehavior() != null && princeps.getSurvivalBehavior().isConsuming();
    }

    private BlockPlaceHelper placeHelper() {
        return princeps.getInputOverrideHandler().getBlockPlaceHelper();
    }

    private BlockBreakHelper breakHelper() {
        return princeps.getInputOverrideHandler().getBlockBreakHelper();
    }

    /** The live crosshair ray, or null when it is not on a block. Cast fresh by {@code objectMouseOver} on every
     *  call, so it is asked once per tick per consumer and never in a loop. */
    private BlockHitResult liveBlockHit() {
        HitResult over = ctx.objectMouseOver();
        return over instanceof BlockHitResult block && over.getType() == HitResult.Type.BLOCK ? block : null;
    }

    /**
     * Vanilla's real placement answer for the live click.
     *
     * <p>This is K1's one guarded live-rotation mutation. The item-level helper is required for
     * {@code StandingAndWallBlockItem}; asking only {@code item.getBlock()} silently turns every wall sign, torch,
     * banner and skull into its standing variant. Rotation is restored in the same {@code finally} that releases the
     * non-reentrancy guard, including when a modded block throws.
     */
    private BlockState simulateVanillaPlacement(PlacementSolution plan, PlaceGate.Live live) {
        ItemStack stack = live.held();
        BlockHitResult hit = live.rawHit();
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || hit == null) {
            return null;
        }
        if (this.simulatingPlacement) {
            throw new IllegalStateException("recursive V3 live placement simulation at "
                    + BuildAction.describePos(plan.cell()));
        }
        this.simulatingPlacement = true;
        float originalYaw = ctx.player().getYRot();
        float originalPitch = ctx.player().getXRot();
        try {
            ctx.player().setYRot(live.rotation().getYaw());
            ctx.player().setXRot(live.rotation().getPitch());
            BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                    ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, hit) {
            });
            BlockState landed = BlockItemPlacementHelper.placementState(blockItem, context);
            return landed != null && context.canPlace() ? landed : null;
        } finally {
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            this.simulatingPlacement = false;
        }
    }

    private BlockState liveState(BlockPos pos) {
        return ctx.world().getBlockState(pos);
    }

    private ItemStack stackAt(int slot) {
        return ctx.player().getInventory().getNonEquipmentItems().get(slot);
    }

    private ItemStack hotbarStack(int slot) {
        return stackAt(slot);
    }

    /**
     * Vanilla's own obstruction test, which counts entities — a mob standing in the cell refuses the placement, and
     * it refuses it after the cooldown has already been paid.
     *
     * <p>{@code isUnobstructed(null, shape)} includes the bot itself, deliberately: the planner proved the body does
     * not overlap the cell from the planned approach point, and if the live body has drifted into it anyway then
     * vanilla will refuse this click and the gate should say so rather than spend a cooldown finding out.
     */
    private boolean entityClear(BlockPos cell, BlockState desired) {
        VoxelShape shape = desired.getCollisionShape(ctx.world(), cell);
        return shape.isEmpty()
                || ctx.world().isUnobstructed(null, shape.move(cell.getX(), cell.getY(), cell.getZ()));
    }

    // ------------------------------------------------------------------------------- uniform action accessors

    /*
     * BuildAction is a sealed interface whose records carry the same geometry under different shapes: a Place holds
     * it inside a PlacementSolution, a Break spreads it across seven components. The executor needs one vocabulary,
     * and these are it. Written as switches over the sealed type rather than as extra interface methods so that
     * adding an action kind is a compile error here rather than a null at run time.
     */

    private static boolean isBreak(BuildAction action) {
        return action.kind() == BuildAction.Kind.BREAK || action.kind() == BuildAction.Kind.REMOVE_SCAFFOLD;
    }

    private static PlacementSolution solutionOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution();
            default -> null;
        };
    }

    private static BlockState desiredOf(BuildAction action) {
        PlacementSolution solution = solutionOf(action);
        return solution == null ? null : solution.desired();
    }

    /** State the placement action itself must produce, before any separately planned interaction mutates it. */
    private static BlockState placedStateOf(BuildAction action) {
        PlacementSolution solution = solutionOf(action);
        return solution == null ? null : solution.predicted();
    }

    private boolean matchesPlacedState(BuildAction action, BlockState live, BlockState expected) {
        BlockState desired = desiredOf(action);
        return live != null && expected != null && desired != null
                && this.settings.sameBlockstate(live, expected)
                && (this.settings.valid(live, desired, true)
                        || PlacementGeometry.interactionClicks(live, desired) >= 0);
    }

    /** The exact state the frozen plan authorises this action to destroy. */
    private static BlockState expectedBreakState(BuildAction action) {
        return switch (action) {
            case BuildAction.Break broken -> broken.expected();
            case BuildAction.RemoveScaffold removed -> removed.expected();
            default -> null;
        };
    }

    /**
     * Guard the destructive half of the executor with exact state equality. Acceptance settings deliberately do not
     * apply: a colour, facing or property change means this is no longer the block the plan authorised us to destroy.
     */
    static String unexpectedBreakState(BuildAction action, BlockState live) {
        if (!isBreak(action) || live == null || live.isAir()) {
            return null;
        }
        BlockState expected = expectedBreakState(action);
        if (expected == null) {
            return "the break action carries no expected live state for "
                    + BuildAction.describePos(action.cell());
        }
        if (!live.equals(expected)) {
            return "refusing to break changed block at " + BuildAction.describePos(action.cell()) + ": found "
                    + BuildAction.describeState(live) + ", plan authorised only " + BuildAction.describeState(expected);
        }
        return null;
    }

    private static BlockPos stanceOf(BuildAction action) {
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

    private static Vec3 approachOf(BuildAction action) {
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

    /** The angle the plan stored. Not what the executor aims at — {@link #rotationOf} re-derives that from the live
     *  eye — but it is the plan's own record, and it is what a divergence sentence should name. */
    private static Rotation plannedRotationOf(BuildAction action) {
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

    private static Vec3 aimPointOf(BuildAction action) {
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
     * Plan against reality, in one line, for a gate that refused.
     *
     * <p>Everything needed to derive the cause and nothing that has to be looked up elsewhere: the block and face the
     * plan meant to click, the exact aim point it proved, and the block, face and hit location the live ray actually
     * produced — plus the distance, because a target that is fine at one block is impossible at four.
     */
    private static String describeGateMiss(PlacementSolution solution, PlaceGate.Live live) {
        Vec3 aim = solution.aimPoint();
        // The state is in here because the last investigation ended exactly where the ray stopped being the
        // question: a click landed one millimetre from its aim point, on the right block and the right face, and
        // still refused -- so what disagreed was not the geometry but the STATE the click would produce. Naming the
        // state the plan demands is the difference between "WRONG_RESULT somewhere" and "this connection shape".
        String wanted = "wanted " + BuildAction.describePos(solution.against()) + " face " + solution.face()
                + " at " + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", aim.x, aim.y, aim.z)
                + " to land " + BuildAction.describeState(solution.predicted());
        if (live.hitPos() == null || live.hitLocation() == null) {
            return wanted + ", live ray hit nothing";
        }
        Vec3 hit = live.hitLocation();
        return wanted + ", live ray hit " + BuildAction.describePos(live.hitPos()) + " face " + live.hitFace()
                + " at " + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", hit.x, hit.y, hit.z)
                + ", aim point " + String.format(java.util.Locale.ROOT, "%.3f", aim.distanceTo(hit))
                + " from the hit";
    }

    /**
     * The rotation to aim at: re-derived at click time from the LIVE eye toward the PLANNED aim point, with the
     * pitch-agnostic hole plugged.
     *
     * <h2>The aim point is the invariant, not the angle</h2>
     * <p>V1 of plan 19. What the planner proved is a POINT on a face — that the crosshair resting there produces the
     * wanted state, that the line to it is clear, that the dominant axis wins by a margin. The rotation it stored is
     * merely one way of reaching that point from one particular place, and the executor is allowed to stand anywhere
     * inside {@link BuildAction#approachTolerance()} of that place. Replaying the stored angle from a different eye
     * therefore aims somewhere the plan never proved anything about, and the further the aim point is, the more the
     * crosshair moves for a given step: the measured case had the bot 0.037 blocks off — a correct arrival — and the
     * replayed angle put the ray through the corner of {@code 82,-60,81} instead of past it by 0.019 blocks.
     * Re-deriving the angle from the actual eye pins the crosshair to the proven point instead, and it is the whole
     * of the fix for that trace.
     *
     * <p>The planned rotation is kept as the fallback for the actions that have no aim point, and it stays the plan's
     * record of what the geometry was proved with. It is not what goes out.
     *
     * <p>{@code getEyePosition()} and not a pose-derived eye: vanilla's own crosshair clip starts there, so it is the
     * origin of the ray this rotation has to land, including while the crouch height is still interpolating. Wrapping
     * against the LIVE rotation rather than a canonical reference is what keeps a yaw that has wound out to −4173°
     * comparable — {@code isCloseTo} normalises, but the look behaviour is handed the number itself.
     *
     * <p>A pitch exactly equal to the current pitch is read as "the caller does not care about pitch" and the head is
     * then dragged toward the walking pitch band at a degree a tick — which walks the crosshair off the face while
     * the gate patiently waits for a ray that is drifting away. The same epsilon defends {@code RotationUtils
     * .reachable}.
     */
    private Rotation rotationOf(BuildAction action) {
        Rotation planned = plannedRotationOf(action);
        if (planned == null) {
            return null;
        }
        Rotation aimed = liveRotationToward(aimPointOf(action), planned);
        if (aimed.getPitch() == ctx.playerRotations().getPitch()) {
            return new Rotation(aimed.getYaw(), aimed.getPitch() + PITCH_EPSILON);
        }
        return aimed;
    }

    /** The rotation that puts the live crosshair on {@code aimPoint}, or {@code planned} when the action carries no
     *  point to aim at and the stored angle is all there is. */
    private Rotation liveRotationToward(Vec3 aimPoint, Rotation planned) {
        if (aimPoint == null) {
            return planned;
        }
        Vec3 eye = ctx.player().getEyePosition();
        return eye == null ? planned
                : RotationUtils.calcRotationFromVec3d(eye, aimPoint, ctx.playerRotations());
    }
}
