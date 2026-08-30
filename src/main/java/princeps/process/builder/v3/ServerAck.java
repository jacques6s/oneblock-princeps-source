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

import princeps.api.IPrinceps;
import princeps.api.event.events.PacketEvent;
import princeps.api.event.events.WorldEvent;
import princeps.api.event.events.ChunkEvent;
import princeps.api.event.events.type.EventState;
import princeps.api.event.listener.AbstractGameEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The server's answer, as opposed to the client's guess.
 *
 * <p>Before this class there was no server-side acknowledgement anywhere in Princeps. {@code BlockPlaceHelper}
 * counts {@code successfulBlockInteractions}, but that counter increments when {@code processRightClickBlock} returns
 * {@code SUCCESS} — it is the CLIENT PREDICTION and nothing more. The client draws the block the instant the click
 * goes out. On a server with a protection plugin the block appears, is reverted two to six ticks later, and a builder
 * that trusted its own prediction booked the cell as done before the revert even arrived. That is precisely the
 * failure mode behind "it must work on servers", and it is invisible: a rollback produces no change to the counter
 * and no event on the bus.
 *
 * <h2>What an acknowledgement is here</h2>
 * Server confirmation received AND the state held for {@link #MIN_HOLD_TICKS} ticks afterwards. Both halves are
 * necessary and neither is sufficient:
 * <ul>
 *   <li><b>Confirmation</b> is either an explicit {@code ClientboundBlockUpdatePacket} at the cell — which carries
 *       the server's own state and is therefore authoritative — or a {@code ClientboundBlockChangedAckPacket} whose
 *       sequence has caught up with the one our click carried. The second case matters more than it looks: when the
 *       server silently refuses a placement it sends no block update at all, the client rolls the prediction back
 *       itself on the ack, and the only trace left is that the live state stopped matching. Watching the ack is what
 *       turns that from an invisible non-event into an observation.</li>
 *   <li><b>The hold</b> is what catches the plugin that lets the placement through and sweeps it two ticks later.
 *       During the hold the live state is re-read every tick, and the first tick it stops satisfying the desired
 *       state settles the expectation as {@link Outcome#REVERTED} or {@link Outcome#WRONG_STATE}.</li>
 * </ul>
 *
 * <h2>Latency</h2>
 * The hold and the timeout are both derived from an EWMA of observed round trips, so that a laggy server delays a
 * confirmation instead of manufacturing a rejection. A repair triggered by lag is worse than no repair at all: it
 * breaks a block that was correctly placed and then places it again, which is the build/break/rebuild cycle that
 * makes {@code BlockBreakHelper} blacklist the position permanently after two breaks in four seconds.
 *
 * <p>The EWMA is measurement, not sampling — there is no RNG anywhere in this class, and on the bench (singleplayer,
 * round trips of zero to one tick) it settles immediately on the floor, so a bench run stays reproducible.
 *
 * <h2>Wiring</h2>
 * No new mixin is needed: {@code MixinNetworkManager} already forwards every clientbound packet to the bus and every
 * serverbound one. This class implements {@link AbstractGameEventListener} and is registered once via
 * {@link #registerWith(IPrinceps)}. Two consequences of the bus's shape are load-bearing here:
 * <ul>
 *   <li>Every packet fires twice, {@code PRE} and {@code POST}. Both handlers filter on
 *       {@code getState() == EventState.PRE} or every observation would be counted twice.</li>
 *   <li>Handlers run on the netty event-loop thread. Every one of them does nothing but read final packet fields and
 *       hand a runnable to {@link MainThread}, so all mutable state in this class is touched on the client thread
 *       only and needs no locking. {@link #active} is the sole exception and is volatile because the netty thread
 *       reads it to decide whether to bother.</li>
 *   <li>{@code IEventBus} declares no deregistration. Registration is therefore permanent and per-instance, and the
 *       instance self-gates on {@link #active}: between builds it is registered, inert, and costs three
 *       {@code instanceof} tests per packet.</li>
 * </ul>
 */
public final class ServerAck implements AbstractGameEventListener {

    /**
     * Ticks the state must hold after the server confirmed it. Three, because a protection-plugin revert lands two to
     * six ticks after the placement and the observed distribution's mass is at the front of that range; the tail is
     * covered by the latency term below rather than by a larger constant, so a fast server is not made slow to pay
     * for a slow one.
     */
    public static final int MIN_HOLD_TICKS = 3;

    /** Ticks without an answer after a matching outbound packet before the expectation settles
     *  {@link Outcome#TIMED_OUT}. When no matching packet was ever emitted, the same local window settles
     *  {@link Outcome#NOT_SENT} instead. Floor, not the value: the real timeout scales with measured round trip. */
    public static final int MIN_TIMEOUT_TICKS = 20;

    /** How many measured round trips the timeout allows for. Four, so that a server whose round trip has quadrupled
     *  since the last measurement still gets answered rather than declared unresponsive. */
    public static final int TIMEOUT_ROUND_TRIPS = 4;

    /** EWMA smoothing factor. 0.2 — roughly a five-sample memory, long enough that one stalled tick does not move the
     *  window and short enough that a server that genuinely got slower is tracked within a few placements. */
    public static final double LATENCY_ALPHA = 0.2D;

    /** The round trip assumed before anything has been measured, in ticks. Two ticks is 100 ms, which is a normal
     *  ping to a normal server; seeding at zero would make the first placement of every build time out on any
     *  connection at all. */
    public static final double SEED_ROUND_TRIP_TICKS = 2.0D;

    /** Round trips above this are ignored as measurements. A single 200-tick sample from a server hitch would
     *  otherwise stretch the window for the next dozen placements and hide a real rejection behind it. */
    public static final double MAX_ROUND_TRIP_SAMPLE_TICKS = 40.0D;

    /** No outbound sequence has been seen for the pending expectation. */
    public static final int NO_SEQUENCE = Integer.MIN_VALUE;

    /** What the executor is waiting for. Each kind names both its matching outbound packet and its authoritative
     *  inbound evidence. */
    public enum Kind {

        /** A block was clicked into place. */
        PLACE,

        /** A block was mined away. Desired state is air. */
        BREAK,

        /** A use-on-block action changes state at an effect cell, which need not be the clicked cell. */
        USE_ON,

        /** A sign update changes block-entity metadata rather than block state. */
        SIGN
    }

    /** How the pending expectation ended. */
    public enum Outcome {

        /** Nothing outstanding. */
        IDLE,

        /** Clicked, no verdict yet. */
        PENDING,

        /** The server confirmed it and it held. The only outcome that may retire a cell. */
        CONFIRMED,

        /** The server never had it, or took it away again: the cell is empty, or holds a different block entirely.
         *  The honest reading of a client prediction that was rolled back. */
        REVERTED,

        /** The right block landed in a state the acceptance rules refuse — the piston facing the other way. Split
         *  from {@link #REVERTED} because the repair differs: this one has to be broken first. */
        WRONG_STATE,

        /** The local helper/controller never emitted the matching action packet. This is local divergence, never
         *  evidence that the server refused a placement. */
        NOT_SENT,

        /** The cell's chunk unloaded while the expectation was pending, so no live confirmation is possible. */
        UNLOADED,

        /** The server said nothing at all within the latency-adaptive window. Not a rejection by itself; it is
         *  evidence, and {@link EvidenceJournal} is what decides when enough of it has accumulated. */
        TIMED_OUT
    }

    /** Hands a runnable to the client thread. {@code ctx.minecraft()::execute} in production, {@code Runnable::run}
     *  in a test. Exists because every packet handler in this class runs on the netty event loop. */
    @FunctionalInterface
    public interface MainThread {

        void execute(Runnable task);
    }

    /** Reads the live client world. Not the {@link PredictedWorld} snapshot: the whole point of this class is to
     *  compare against what actually exists, and the snapshot is by construction what the planner believed. */
    @FunctionalInterface
    public interface WorldView {

        BlockState stateAt(BlockPos pos);
    }

    /** Does an observed state satisfy a desired one? Wired to {@code V3Settings.valid(observed, desired, true)} —
     *  {@code itemVerify = true}, the same question {@code placementResultAccepted} asks, which disables the
     *  {@code buildIgnoreExisting} and {@code buildValidSubstitutes} escapes. "Did my click land" and "is this cell
     *  done" are deliberately different questions in V2 and stay different here. */
    @FunctionalInterface
    public interface StateMatcher {

        boolean satisfies(BlockState observed, BlockState desired);
    }

    /**
     * Every block change the server reported, whether or not anything was expecting it.
     *
     * <p>This is the second product of the listener and it is not a by-product. {@link EvidenceJournal} clears a
     * cell's marker when the world around it changes, and a cell already retired can be swept away an hour later by a
     * plugin — neither is observable from the expectation channel, because by then nothing is pending. Everything the
     * server says about the world goes through here and the owner decides what it means.
     */
    @FunctionalInterface
    public interface ChangeSink {

        void onServerBlockChange(BlockPos pos, BlockState state);

        /** A chunk containing pending or completed proof became unverifiable. */
        default void onChunkUnload(int chunkX, int chunkZ) {
        }

        /** Discards everything. */
        ChangeSink NONE = (pos, state) -> {
        };
    }

    @FunctionalInterface
    public interface LogSink {

        void log(String message);

        LogSink NONE = message -> {
        };
    }

    private final MainThread thread;
    private final WorldView world;
    private final StateMatcher matcher;
    private final ChangeSink sink;
    private final LogSink log;

    /** Read from the netty thread, written from the client thread. The only cross-thread field in the class. */
    private volatile boolean active;

    /** True once {@link #registerWith} has run. Registration is permanent — the bus cannot forget a listener — so a
     *  second registration would double every observation for the rest of the session. */
    private boolean registered;

    private Expectation pending;
    private Outcome outcome = Outcome.IDLE;

    /** The settled expectation, kept for {@link #describe()} after {@link #pending} is cleared. */
    private Expectation last;

    private double roundTripTicks = SEED_ROUND_TRIP_TICKS;
    private long roundTripSamples;

    /** The tick number the last {@link #tick(long)} carried. The packet handlers need a clock and the bus gives them
     *  none; bouncing to the client thread means they land within a tick of this value, which is the resolution the
     *  measurement is stated in anyway. */
    private long clientTick;

    /** The highest sequence the server has acknowledged. Compared by subtraction, never by {@code <}, because the
     *  sequence is a 32-bit counter that wraps. */
    private int lastAckSequence = NO_SEQUENCE;

    private long confirmations;
    private long reverts;
    private long wrongStates;
    private long timeouts;

    public ServerAck(MainThread thread, WorldView world, StateMatcher matcher, ChangeSink sink) {
        this(thread, world, matcher, sink, LogSink.NONE);
    }

    public ServerAck(MainThread thread, WorldView world, StateMatcher matcher, ChangeSink sink, LogSink log) {
        this.thread = thread;
        this.world = world;
        this.matcher = matcher;
        this.sink = sink == null ? ChangeSink.NONE : sink;
        this.log = log == null ? LogSink.NONE : log;
    }

    /** One mutable expectation. Not a record: the confirmation arrives in pieces and each piece stamps its own tick,
     *  which is the raw material of the latency measurement. */
    private static final class Expectation {

        private final Kind kind;
        /** The state/metadata effect cell. For placement this is the placed cell, not the clicked neighbour. */
        private final BlockPos cell;
        private final BlockState desired;
        private final StateMatcher stateMatcher;

        /** The block whose face was clicked. It may equal {@link #cell} for interactions and waterlogging. */
        private final BlockPos clicked;
        private final Direction face;
        private final boolean frontSide;
        private final List<String> signLines;

        private final long sentTick;

        private int sequence = NO_SEQUENCE;
        private boolean outboundSeen;
        /** Tick the matching outbound packet was observed. A break may be armed many ticks before its STOP packet. */
        private long outboundTick = -1L;
        private boolean serverProcessed;

        /** Tick the first piece of server evidence arrived — the one the round trip is measured against. */
        private long answeredTick = -1L;

        /** The last state the server reported for {@link #cell}, or null if it never mentioned it. */
        private BlockState observed;
        private boolean signMetadataSeen;
        private boolean signMetadataMatches;

        /** Tick the hold ends. Negative until the server has answered at all. */
        private long holdUntilTick = -1L;

        private Expectation(Kind kind, BlockPos cell, BlockState desired, StateMatcher stateMatcher,
                            BlockPos clicked, Direction face, boolean frontSide, List<String> signLines, long sentTick) {
            this.kind = kind;
            this.cell = cell;
            this.desired = desired;
            this.stateMatcher = stateMatcher;
            this.clicked = clicked;
            this.face = face;
            this.frontSide = frontSide;
            this.signLines = signLines;
            this.sentTick = sentTick;
        }
    }

    // ------------------------------------------------------------------------------------------------ registration

    /**
     * Join the event bus. Idempotent, and it has to be: the bus has no deregistration, so registering per build
     * instead of per instance would leave one listener per build running forever, each one counting every packet.
     */
    public void registerWith(IPrinceps princeps) {
        if (!this.registered) {
            princeps.getGameEventHandler().registerEventListener(this);
            this.registered = true;
        }
    }

    /** Start listening. Called when a build starts. */
    public void activate() {
        this.reset();
        this.active = true;
    }

    /**
     * Stop listening and forget everything.
     *
     * <p>Must be called from {@code onLostControl()} and from world-leave. A listener that stays armed across a world
     * change holds a {@link BlockPos} for a cell in a world that no longer exists, and the next build's first tick
     * settles against it.
     */
    public void deactivate() {
        this.active = false;
        this.reset();
    }

    public boolean isActive() {
        return this.active;
    }

    private void reset() {
        this.pending = null;
        this.last = null;
        this.outcome = Outcome.IDLE;
        this.lastAckSequence = NO_SEQUENCE;
        this.roundTripTicks = SEED_ROUND_TRIP_TICKS;
        this.roundTripSamples = 0L;
        this.confirmations = 0L;
        this.reverts = 0L;
        this.wrongStates = 0L;
        this.timeouts = 0L;
    }

    // ---------------------------------------------------------------------------------------------- the executor

    /**
     * Arm an expectation for a placement. Called in the same tick the click goes out.
     *
     * @param cell    the cell the block should occupy
     * @param against the neighbour whose face was clicked
     * @param face    the clicked face, i.e. the one pointing at {@code cell}
     * @param desired what the plan says will land there
     * @param tick    the build tick of the click
     */
    public boolean expectPlacement(BlockPos cell, BlockPos against, Direction face, BlockState desired, long tick) {
        return this.arm(new Expectation(Kind.PLACE, cell.immutable(), desired, this.matcher,
                against == null ? null : against.immutable(), face, false, null, tick));
    }

    /** Arm an expectation for a break: the cell must become, and stay, empty. */
    public boolean expectBreak(BlockPos cell, BlockState desired, long tick) {
        return this.arm(new Expectation(Kind.BREAK, cell.immutable(), desired, null,
                null, null, false, null, tick));
    }

    /**
     * Arm an exact state-changing use-on-block expectation.
     *
     * <p>{@code effectCell} is where the state must change. {@code clickedPos} is what the outbound
     * {@link ServerboundUseItemOnPacket} must name. They differ for a bucket placed against a neighbour and are equal
     * for repeater interactions and waterlogging.
     */
    public boolean expectUseOn(BlockPos effectCell, BlockPos clickedPos, Direction face, BlockState expectedAfter,
                               long tick) {
        return this.expectUseOn(effectCell, clickedPos, face, expectedAfter, BlockState::equals, tick);
    }

    /**
     * Arm a state-changing use-on-block expectation with an action-specific matcher.
     *
     * <p>The matcher belongs to this expectation and is not the schematic-wide matcher passed to the constructor.
     * Fluid actions can therefore prove their precise fluid predicate without weakening placement acknowledgement,
     * while interaction steps normally use the exact overload above.
     */
    public boolean expectUseOn(BlockPos effectCell, BlockPos clickedPos, Direction face, BlockState expectedAfter,
                               StateMatcher stateMatcher, long tick) {
        Objects.requireNonNull(effectCell, "effectCell");
        Objects.requireNonNull(clickedPos, "clickedPos");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(expectedAfter, "expectedAfter");
        Objects.requireNonNull(stateMatcher, "stateMatcher");
        return this.arm(new Expectation(Kind.USE_ON, effectCell.immutable(), expectedAfter, stateMatcher,
                clickedPos.immutable(), face, false, null, tick));
    }

    /**
     * Arm an exact four-line sign metadata expectation.
     *
     * <p>The matching outbound packet must name the same cell, side and four lines. Positive inbound evidence is a
     * sign {@link ClientboundBlockEntityDataPacket} at the cell whose requested side decodes to those same lines.
     */
    public boolean expectSign(BlockPos cell, boolean frontSide, List<String> exactLines, long tick) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(exactLines, "exactLines");
        if (exactLines.size() != SignNbt.LINES) {
            throw new IllegalArgumentException("a sign expectation requires exactly " + SignNbt.LINES + " lines");
        }
        List<String> copied = List.copyOf(exactLines);
        return this.arm(new Expectation(Kind.SIGN, cell.immutable(), null, null,
                null, null, frontSide, copied, tick));
    }

    private boolean arm(Expectation expectation) {
        if (this.pending != null) {
            this.log.log("v3 ack: refusing to overwrite pending " + this.pending.kind + " at "
                    + this.pending.cell.getX() + "," + this.pending.cell.getY() + "," + this.pending.cell.getZ());
            return false;
        }
        this.pending = expectation;
        this.last = expectation;
        this.outcome = Outcome.PENDING;
        return true;
    }

    /** Drop the pending expectation without a verdict — a divergence, a re-plan, an abandoned cell. */
    public void clearExpectation() {
        this.pending = null;
        this.outcome = Outcome.IDLE;
    }

    /**
     * Advance the expectation by one client tick and report where it stands. Called from the executor's {@code onTick}
     * on the client thread, which is also the only thread that ever reads {@link WorldView}.
     *
     * <p>The live state is re-read every tick rather than only at the end of the hold. A block that appears, is
     * reverted, and is placed again by something else inside three ticks would pass an end-of-window check and fail
     * this one, and the second of those is the honest answer.
     */
    public Outcome tick(long tick) {
        if (!this.active) {
            return this.outcome;
        }
        this.clientTick = tick;
        if (this.pending == null) {
            return this.outcome;
        }
        Expectation e = this.pending;
        BlockState live = this.world.stateAt(e.cell);
        boolean satisfied = this.satisfied(e, live);

        if (e.holdUntilTick < 0L) {
            if (!this.hasInboundEvidence(e, live)) {
                // Nothing from the server yet. Not evidence of anything until the window has actually elapsed.
                // A BREAK is armed before mining starts, but its authoritative outbound packet is STOP_DESTROY_BLOCK
                // at completion. Do not time out while a hard block is still being mined; CellExecutor owns that
                // progress bound. Once the packet exists, this class owns the adaptive server window.
                if (e.kind == Kind.BREAK && e.outboundTick < 0L) {
                    return this.outcome;
                }
                long waitingSince = e.outboundTick >= 0L ? e.outboundTick : e.sentTick;
                if (tick - waitingSince > this.timeoutTicks()) {
                    return this.settle(e.outboundSeen ? Outcome.TIMED_OUT : Outcome.NOT_SENT);
                }
                return this.outcome;
            }
            if (!satisfied) {
                // The server answered and the answer is no. There is nothing to hold.
                return this.settle(this.classify(live, e));
            }
            e.holdUntilTick = tick + this.holdTicks();
            return this.outcome;
        }
        if (!satisfied) {
            return this.settle(this.classify(live, e));
        }
        return tick >= e.holdUntilTick ? this.settle(Outcome.CONFIRMED) : this.outcome;
    }

    private boolean satisfied(Expectation e, BlockState live) {
        return switch (e.kind) {
            // BREAK/REMOVE has one exact post-state: AIR. User acceptance settings such as okIfWater and
            // buildIgnoreBlocks are schematic policy, not authority to call fluid or vegetation removed.
            case BREAK -> live.isAir();
            case SIGN -> live.getBlock() instanceof SignBlock && e.signMetadataMatches;
            case PLACE, USE_ON -> e.stateMatcher.satisfies(live, e.desired);
        };
    }

    private boolean hasInboundEvidence(Expectation e, BlockState live) {
        if (e.kind == Kind.SIGN) {
            // A sign-state block update commonly precedes its block-entity packet. It proves only that a sign exists,
            // not what either side says, so settling on it would race the metadata packet by one client tick.
            return e.signMetadataSeen || e.serverProcessed
                    || e.observed != null && !(live.getBlock() instanceof SignBlock);
        }
        return e.serverProcessed || e.observed != null;
    }

    private Outcome settle(Outcome result) {
        Expectation e = this.pending;
        this.pending = null;
        this.outcome = result;
        switch (result) {
            case CONFIRMED -> this.confirmations++;
            case REVERTED -> this.reverts++;
            case WRONG_STATE -> this.wrongStates++;
            case TIMED_OUT -> this.timeouts++;
            default -> {
            }
        }
        if (result != Outcome.CONFIRMED && e != null) {
            this.log.log("v3 ack: " + result + " at " + e.cell.getX() + "," + e.cell.getY() + "," + e.cell.getZ()
                    + " kind=" + e.kind + " wanted=" + e.desired + " server=" + (e.observed == null ? "silent"
                    : e.observed.toString()) + " rtt=" + String.format(Locale.ROOT, "%.1f", this.roundTripTicks));
        }
        return result;
    }

    /**
     * Why the expectation failed, from the state that is actually there.
     *
     * <p>The split is not cosmetic. A missing block is placed again; a wrong-facing block has to be broken first, and
     * breaking and placing may not share a tick because {@code CLICK_LEFT} clears the pending {@code CLICK_RIGHT}
     * commit before the helpers tick. A repair that guesses wrong here spends a click on nothing.
     */
    private Outcome classify(BlockState live, Expectation e) {
        if (e.kind == Kind.BREAK) {
            return Outcome.REVERTED;
        }
        if (e.kind == Kind.SIGN) {
            return live.getBlock() instanceof SignBlock ? Outcome.WRONG_STATE : Outcome.REVERTED;
        }
        return live.isAir() || live.getBlock() != e.desired.getBlock() ? Outcome.REVERTED : Outcome.WRONG_STATE;
    }

    // ------------------------------------------------------------------------------------------------- the window

    /**
     * Ticks a confirmed state must hold. The floor plus one measured round trip, because a revert that the server
     * decided on at the same moment it confirmed is exactly one round trip behind the confirmation — waiting less
     * than that is waiting for a message that has not been sent yet.
     */
    public int holdTicks() {
        return holdFloor() + (int) Math.ceil(this.roundTripTicks);
    }

    /**
     * The revert-watch floor, from the owner's dial rather than the constant.
     *
     * <p>{@link #MIN_HOLD_TICKS} stays the default and the documented reasoning behind it is unchanged. The dial
     * exists because the number is a judgement about OTHER PEOPLE'S SERVERS — three ticks buys protection against a
     * plugin that reverts after confirming, and on a server that has no such plugin it buys nothing. Whoever knows
     * which of the two they are on should be able to say so.
     *
     * <p>It is deliberately not the throughput lever it looks like. Since the watch stopped blocking the next cell's
     * preparation, these ticks run underneath work that has to happen anyway and cost close to nothing; lowering the
     * dial mostly trades protection for no gain. Kept because it is the owner's call, not because it is fast.
     */
    private int holdFloor() {
        return this.holdFloorTicks;
    }

    /**
     * The floor, pushed in by the owner rather than read from settings here.
     *
     * <p>This class reads no settings on purpose: it is unit-tested headless, and reaching for the settings singleton
     * pulls the whole game API into a test that has none. Eight ServerAck tests died on exactly that the moment this
     * dial was first wired the other way round. The process sets it; the default is the constant, so anything that
     * never sets it behaves exactly as before.
     */
    private int holdFloorTicks = MIN_HOLD_TICKS;

    /** Set the revert-watch floor in ticks. Negative values are clamped to zero. */
    public void setHoldFloorTicks(int ticks) {
        this.holdFloorTicks = Math.max(0, ticks);
    }

    /**
     * True once the server has answered YES and only the revert watch is still running.
     *
     * <p>The distinction the executor needs to stop standing still: at this point the placement HAS authoritative
     * server evidence, and what remains is the window in which a protection plugin could still take it back. That
     * window is a reason not to CLICK again yet — it is not a reason to refuse to walk, turn, or pick the next block,
     * which is what the executor used to do with it.
     */
    public boolean holding() {
        Expectation e = this.pending;
        return this.active && e != null && e.holdUntilTick >= 0L && this.outcome == Outcome.PENDING;
    }

    /** Ticks of silence before {@link Outcome#TIMED_OUT}. */
    public int timeoutTicks() {
        return Math.max(MIN_TIMEOUT_TICKS,
                (int) Math.ceil(this.roundTripTicks * TIMEOUT_ROUND_TRIPS) + MIN_HOLD_TICKS);
    }

    /** The current round-trip estimate in ticks. */
    public double roundTripTicks() {
        return this.roundTripTicks;
    }

    public long roundTripSamples() {
        return this.roundTripSamples;
    }

    private void sample(long sentTick, long answeredTick) {
        double observedTicks = answeredTick - sentTick;
        if (observedTicks < 0.0D || observedTicks > MAX_ROUND_TRIP_SAMPLE_TICKS) {
            return;
        }
        this.roundTripTicks = LATENCY_ALPHA * observedTicks + (1.0D - LATENCY_ALPHA) * this.roundTripTicks;
        this.roundTripSamples++;
    }

    // -------------------------------------------------------------------------------------------------- the bus

    /**
     * Clientbound packets. {@code PRE} only, netty thread only, so every branch does the same thing: read the final
     * fields off the packet and hand the work to the client thread.
     */
    @Override
    public void onReceivePacket(PacketEvent event) {
        if (!this.active || event.getState() != EventState.PRE) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            BlockPos pos = update.getPos().immutable();
            BlockState state = update.getBlockState();
            this.thread.execute(() -> this.observeChange(pos, state));
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            // A protection plugin restoring a region sends one of these, not sixteen block updates. Reading it on the
            // client thread is safe: the packet is immutable and runUpdates only walks its own arrays. The position
            // it hands out is reused between callbacks, hence immutable().
            this.thread.execute(() -> section.runUpdates((pos, state) -> this.observeChange(pos.immutable(), state)));
        } else if (packet instanceof ClientboundBlockChangedAckPacket ack) {
            int sequence = ack.sequence();
            this.thread.execute(() -> this.observeAck(sequence));
        } else if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
            BlockPos pos = blockEntity.getPos().immutable();
            BlockEntityType<?> type = blockEntity.getType();
            CompoundTag tag = blockEntity.getTag().copy();
            this.thread.execute(() -> this.observeSignMetadata(pos, type, tag));
        }
    }

    /**
     * Serverbound packets, for the one thing they carry that nothing else exposes: the sequence number of our own
     * click. {@code MultiPlayerGameMode.useItemOn} allocates it internally and {@code BlockPlaceHelper} only ever
     * sees the {@code InteractionResult}, so the packet on its way out is the sole place it is visible.
     *
     * <p>Matching on the clicked block and face rather than on "the next packet" matters: the bot interacts with
     * other things — an interaction step, a door, an inventory — and a sequence borrowed from one of those would
     * acknowledge a placement the server has not reached yet.
     */
    @Override
    public void onSendPacket(PacketEvent event) {
        if (!this.active || event.getState() != EventState.PRE) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundUseItemOnPacket use) {
            int sequence = use.getSequence();
            BlockPos clicked = use.getHitResult().getBlockPos().immutable();
            Direction direction = use.getHitResult().getDirection();
            this.thread.execute(() -> this.observeUseOutbound(clicked, direction, sequence));
        } else if (packet instanceof ServerboundPlayerActionPacket action
                && (action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                || action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)) {
            int sequence = action.getSequence();
            BlockPos broken = action.getPos().immutable();
            ServerboundPlayerActionPacket.Action destroyAction = action.getAction();
            this.thread.execute(() -> this.observeBreakOutbound(broken, sequence, destroyAction));
        } else if (packet instanceof ServerboundSignUpdatePacket sign) {
            BlockPos cell = sign.getPos().immutable();
            boolean frontSide = sign.isFrontText();
            List<String> lines = List.copyOf(Arrays.asList(sign.getLines().clone()));
            this.thread.execute(() -> this.observeSignOutbound(cell, frontSide, lines));
        }
    }

    /** Leaving or entering a world settles nothing and invalidates everything: the pending cell is a position in a
     *  world that is no longer the one being read. Trap: {@code onLostControl()} must reset whatever {@code isActive}
     *  reads, and this is the same discipline one layer down. */
    @Override
    public void onWorldEvent(WorldEvent event) {
        this.clearExpectation();
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (!this.active || event.getState() != EventState.PRE || event.getType() != ChunkEvent.Type.UNLOAD) {
            return;
        }
        int chunkX = event.getX();
        int chunkZ = event.getZ();
        this.thread.execute(() -> {
            this.sink.onChunkUnload(chunkX, chunkZ);
            Expectation e = this.pending;
            if (e != null && (e.cell.getX() >> 4) == chunkX && (e.cell.getZ() >> 4) == chunkZ) {
                this.settle(Outcome.UNLOADED);
            }
        });
    }

    /** A death moves the bot, cancels the build's assumptions, and leaves any outstanding click unanswerable. */
    @Override
    public void onPlayerDeath() {
        this.clearExpectation();
    }

    // ------------------------------------------------------------------------------- client-thread observation

    private void observeUseOutbound(BlockPos clicked, Direction face, int sequence) {
        Expectation e = this.pending;
        if (e == null || e.outboundSeen || e.kind != Kind.PLACE && e.kind != Kind.USE_ON) {
            return;
        }
        if (e.clicked != null && clicked.equals(e.clicked) && face == e.face) {
            e.sequence = sequence;
            e.outboundSeen = true;
            e.outboundTick = this.clientTick;
        }
    }

    private void observeBreakOutbound(BlockPos broken, int sequence,
                                      ServerboundPlayerActionPacket.Action destroyAction) {
        Expectation e = this.pending;
        if (e == null || e.outboundSeen || e.kind != Kind.BREAK || !broken.equals(e.cell)) {
            return;
        }
        // Creative and every survival block that breaks in one hit have no STOP packet: vanilla destroys the client
        // block inside the prediction and then sends START as that prediction's complete action. A slow survival
        // break sends the same START while the block is still present and a later STOP when mining completes, so AIR
        // is the exact discriminator. It promotes the outbound packet only; server ack/update evidence is still
        // required below before the expectation can confirm.
        if (destroyAction == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && !this.world.stateAt(broken).isAir()) {
            return;
        }
        e.sequence = sequence;
        e.outboundSeen = true;
        e.outboundTick = this.clientTick;
    }

    private void observeSignOutbound(BlockPos cell, boolean frontSide, List<String> lines) {
        Expectation e = this.pending;
        if (e == null || e.outboundSeen || e.kind != Kind.SIGN) {
            return;
        }
        if (cell.equals(e.cell) && frontSide == e.frontSide && lines.equals(e.signLines)) {
            e.outboundSeen = true;
            e.outboundTick = this.clientTick;
        }
    }

    private void observeChange(BlockPos pos, BlockState state) {
        this.sink.onServerBlockChange(pos, state);
        Expectation e = this.pending;
        if (e != null && pos.equals(e.cell) && e.outboundSeen) {
            e.observed = state;
            if (e.kind == Kind.SIGN && state.getBlock() instanceof SignBlock) {
                // A sign block-state packet contains no text. Wait for ClientboundBlockEntityDataPacket so both the
                // verdict and the RTT sample are based on the metadata the expectation is actually about.
                return;
            }
            // A packet at the right coordinates is authoritative world state, but it is evidence for THIS action
            // only after the matching outbound packet exists. Otherwise an unrelated player changing the same cell
            // between arming and our helper actually sending would be allowed to retire our action.
            this.answered(e);
        }
    }

    private void observeSignMetadata(BlockPos pos, BlockEntityType<?> type, CompoundTag tag) {
        Expectation e = this.pending;
        if (e == null || e.kind != Kind.SIGN || !e.outboundSeen || !pos.equals(e.cell)
                || type != BlockEntityType.SIGN && type != BlockEntityType.HANGING_SIGN) {
            return;
        }
        List<String> observedLines = signLines(tag, e.frontSide);
        e.signMetadataSeen = true;
        e.signMetadataMatches = observedLines != null && observedLines.equals(e.signLines);
        e.serverProcessed = true;
        this.answered(e);
    }

    /**
     * Decode one side exactly. {@link SignNbt#lines} intentionally collapses four blank lines to an empty list for
     * planning; acknowledgement must distinguish a present blank side from absent metadata, so it expands only the
     * former back to four blanks.
     */
    private static List<String> signLines(CompoundTag tag, boolean frontSide) {
        boolean present = tag.getCompound(frontSide ? "front_text" : "back_text").isPresent()
                || frontSide && tag.getString("Text1").isPresent();
        if (!present) {
            return null;
        }
        List<String> lines = SignNbt.lines(tag, frontSide);
        if (SignNbt.unreadable(lines)) {
            return null;
        }
        return lines.isEmpty() ? List.of("", "", "", "") : lines;
    }

    private void observeAck(int sequence) {
        this.lastAckSequence = sequence;
        Expectation e = this.pending;
        if (e == null || e.serverProcessed) {
            return;
        }
        // Wrap-safe: the sequence is a plain 32-bit counter and a session long enough to wrap it is not impossible.
        //
        // An unrelated acknowledgement is not evidence for an expectation whose outbound packet never existed.
        // Placements then time out by name; breaks remain pending until STOP_DESTROY_BLOCK is actually sent.
        boolean caughtUp = e.sequence != NO_SEQUENCE && sequence - e.sequence >= 0;
        if (caughtUp) {
            e.serverProcessed = true;
            this.answered(e);
        }
    }

    /** The first piece of server evidence for this expectation — the moment the round trip is measurable. */
    private void answered(Expectation e) {
        if (e.answeredTick < 0L) {
            e.answeredTick = this.clientTick;
            this.sample(e.outboundTick >= 0L ? e.outboundTick : e.sentTick, e.answeredTick);
        }
    }

    // ------------------------------------------------------------------------------------------------ reporting

    public Outcome outcome() {
        return this.outcome;
    }

    public boolean isPending() {
        return this.pending != null;
    }

    /** The cell of the pending expectation, or null. */
    public BlockPos pendingCell() {
        return this.pending == null ? null : this.pending.cell;
    }

    /** The cell of the most recent expectation, pending or settled, or null. Survives {@link #settle} on purpose:
     *  {@link EvidenceJournal} is told about a rejection AFTER the verdict exists, and by then the pending slot is
     *  already empty. */
    public BlockPos lastCell() {
        return this.last == null ? null : this.last.cell;
    }

    /** What the server last said stood at {@link #lastCell()}, or null if it never mentioned it — the difference
     *  between "the server refused and said so" and "the server said nothing and the client rolled the prediction
     *  back by itself". */
    public BlockState lastObserved() {
        return this.last == null ? null : this.last.observed;
    }

    public long confirmations() {
        return this.confirmations;
    }

    public long reverts() {
        return this.reverts;
    }

    public long wrongStates() {
        return this.wrongStates;
    }

    public long timeouts() {
        return this.timeouts;
    }

    /** The highest sequence the server has acknowledged, or {@link #NO_SEQUENCE}. */
    public int lastAckSequence() {
        return this.lastAckSequence;
    }

    /** One line for the trace: where the acknowledgement machinery stands, in the vocabulary of its own decisions. */
    public String describe() {
        return String.format(Locale.ROOT,
                "ack %s cell=%s rtt=%.1f hold=%d timeout=%d ok=%d reverted=%d wrong=%d timedout=%d",
                this.outcome,
                this.pending == null ? "-" : this.pending.cell.getX() + "," + this.pending.cell.getY() + ","
                        + this.pending.cell.getZ(),
                this.roundTripTicks, this.holdTicks(), this.timeoutTicks(),
                this.confirmations, this.reverts, this.wrongStates, this.timeouts);
    }
}
