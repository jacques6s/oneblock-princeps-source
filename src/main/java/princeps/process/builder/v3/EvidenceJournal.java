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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the server actually did, remembered, and fed back into the planner.
 *
 * <p>This is the class that makes decision E-D terminate. E-D has no emergency path: a divergence re-runs the
 * planner, and a rejection re-runs the same planner. Both are the same mechanism, which is the point — but a
 * deterministic planner re-run over an unchanged world produces, by construction, the same plan. Without a record of
 * what was already refused, "re-plan on rejection" is an infinite loop at click cadence: click, refused, re-plan,
 * identical plan, click, refused, forever, at twenty ticks a second. The V2 design notes were right to fear exactly
 * this. The fix is not a retry counter or a backoff — both of those are the emergency path E-D refuses to have — it
 * is to make the refusal an INPUT.
 *
 * <p>So this journal is a {@link PlacementOracle.StanceFilter}. Every rejected {@code (cell, stance, face)} triple is
 * banned, the planner is handed the ban list, and a re-plan therefore cannot hand back the same solution. It must
 * find a structurally different one or report the cell as unsolvable. Either answer is progress; the loop is closed
 * structurally rather than by a limit.
 *
 * <h2>What goes in</h2>
 * VERIFIED executor facts only, and the distinction is the whole value of the file. Not "the click did not seem to
 * work", not "the cell still looks wrong", not a prediction — the three things {@link ServerAck} can actually
 * establish about a click that was sent: the server never confirmed it, it was placed and then taken away, or what
 * landed was not what was asked for. A journal that accepted guesses would ban good geometry on the strength of a
 * chunk that had not loaded yet, and the ban is permanent within a build.
 *
 * <h2>What comes out</h2>
 * <ul>
 *   <li>The veto, as {@link #allows}, straight into the planner.</li>
 *   <li>{@link CellStatus#EXTERNALLY_BLOCKED} after {@link #REJECTIONS_BEFORE_BLOCKED} structurally different
 *       rejections at one cell. Three different stances, three different faces, three different plans, and the server
 *       refused all of them: the obstacle is not geometry, it is somebody else's rule. The cell is named, the build
 *       stops, and it stops LOUDLY — {@link #blockedReport()} prints coordinates, not a count.</li>
 *   <li>{@link #netProgressStalled}: placed minus reverted over {@link #NET_PROGRESS_WINDOW_TICKS} at or below zero.
 *       This is the case no per-cell rule can see, because every individual cell is fine — the world is simply being
 *       changed against the build faster than the build changes it. Answering that by continuing to place is how a
 *       bot spends three hours achieving nothing.</li>
 * </ul>
 *
 * <h2>The marker is not permanent</h2>
 * A blocked cell clears when the world around it changes ({@link #observeWorldChange}). The evidence was gathered
 * against a specific world; a neighbour appearing or disappearing invalidates it, and refusing to reconsider would
 * turn one moment's protection region into a permanent hole in the build. This is also what makes the escalation
 * safe to set as low as three: the cost of being wrong is bounded by the next change in the neighbourhood.
 *
 * <h2>Two things this class deliberately is not</h2>
 * <ul>
 *   <li><b>Not a pause.</b> The bench scores {@code isPaused() == true} as FAILURE, so the most correct reaction the
 *       system has — "I am blocked at cell X and I am telling you" — must not be signalled by pausing. This class
 *       returns statuses and strings; turning them into a halt is {@link PlannedBuilderProcess}'s job and it does it
 *       through the named-stop channel, not through the pause flag.</li>
 *   <li><b>Not client-aware.</b> No {@code Princeps.settings()}, no {@code ctx}, no {@code Minecraft}, no
 *       {@code BlockState}. Pure bookkeeping over positions, faces and ticks, which is what makes it the one piece of
 *       the acknowledgement story that can be tested headlessly — and it is the piece whose termination argument
 *       needs testing.</li>
 * </ul>
 *
 * <p>Deterministic throughout: every iterated collection is insertion-ordered, so two runs over the same evidence
 * report the same cells in the same order. The one {@link LinkedHashSet} that is only ever asked {@code contains}
 * could have been a {@code HashSet}; it is not, because it is also printed.
 */
public final class EvidenceJournal implements PlacementOracle.StanceFilter {

    /**
     * Structurally different rejections at one cell before it is declared {@link CellStatus#EXTERNALLY_BLOCKED}.
     *
     * <p>Three, and the number is a statement about what is being distinguished rather than a tolerance. One
     * rejection is an accident — a mob in the way, a chunk boundary, a block that moved between plan and click. Two
     * is a coincidence. Three different stances and three different clicked faces, every one refused by the server,
     * is not a geometry problem the planner can solve by trying harder, and every further plan is a click spent
     * confirming what is already known.
     */
    public static final int REJECTIONS_BEFORE_BLOCKED = 3;

    /**
     * Chebyshev radius around a cell whose changes clear its record, in blocks.
     *
     * <p>One: the cell and its twenty-six neighbours. That is the footprint a placement's proof actually rests on —
     * the block clicked against, the cell itself, what holds the bot up — so a change inside it invalidates the
     * evidence and a change outside it does not. A larger radius would clear markers on unrelated activity elsewhere
     * on a busy server and re-open the loop the journal exists to close.
     */
    public static final int NEIGHBOURHOOD_RADIUS = 1;

    /** The net-progress window, in ticks. 600 = 30 seconds: long enough to contain a walk between distant cells,
     *  short enough that half a minute of achieving nothing is reported as half a minute rather than as an hour. */
    public static final long NET_PROGRESS_WINDOW_TICKS = 600L;

    /** What the executor verified about a click that was sent. Nothing here is inferred. */
    public enum Reason {

        /** The server said nothing within the latency-adaptive window — {@link ServerAck.Outcome#TIMED_OUT}, or an
         *  ack that arrived with the cell already empty because the client rolled its own prediction back. */
        NO_CONFIRMATION,

        /** The block was there and then it was not: {@link ServerAck.Outcome#REVERTED}. The signature of a protection
         *  plugin, and the case the whole acknowledgement layer was built to see. */
        REVERTED,

        /** The right block landed in a state the acceptance rules refuse — {@link ServerAck.Outcome#WRONG_STATE}. A
         *  geometry fact rather than a permission fact, and banning the triple is exactly the right response: some
         *  other stance and face will land it correctly. */
        WRONG_RESULT
    }

    /** How much the world is arguing about one cell. */
    public enum CellStatus {

        /** No evidence against any way of placing it. */
        CLEAR,

        /** At least one refused triple, fewer than {@link #REJECTIONS_BEFORE_BLOCKED}. The planner is still expected
         *  to find another way and usually does. */
        CONTESTED,

        /** {@link #REJECTIONS_BEFORE_BLOCKED} structurally different solutions, all refused. Named, reported, and
         *  terminal until the neighbourhood changes. */
        EXTERNALLY_BLOCKED
    }

    /**
     * One refused way of placing one cell.
     *
     * @param cell   the schematic cell
     * @param stance the feet cell the click was made from
     * @param face   the clicked face of the neighbour, i.e. the one pointing at {@code cell}. Together with the cell
     *               this fixes the block clicked against, because a solution's {@code against} is always
     *               {@code cell.relative(face.getOpposite())} — which is why the triple, and not a quadruple, is the
     *               unit of structural difference
     * @param reason what the executor verified
     * @param tick   the build tick it was verified at
     */
    public record Rejection(BlockPos cell, BlockPos stance, Direction face, Reason reason, long tick) {

        public Rejection {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(reason, "reason");
            cell = cell.immutable();
            stance = stance == null ? null : stance.immutable();
        }

        /** One line for the trace and the blocked report. */
        public String describe() {
            return String.format(Locale.ROOT, "cell %d,%d,%d stance %s face %s %s @%d",
                    this.cell.getX(), this.cell.getY(), this.cell.getZ(),
                    this.stance == null ? "-" : this.stance.getX() + "," + this.stance.getY() + ","
                            + this.stance.getZ(),
                    this.face == null ? "-" : this.face.toString(), this.reason, this.tick);
        }
    }

    /** A (stance, face) pair. Coordinates as a long and the face as an ordinal so that equality is value equality and
     *  cannot be broken by a {@code BetterBlockPos} whose {@code hashCode} is {@code longHash} rather than the
     *  vanilla {@code Vec3i} hash — the same reason {@code OrderPlanner.PlannerState.Veto} is shaped this way. */
    private record StanceFace(long stance, int face) {

        private static StanceFace of(BlockPos stance, Direction face) {
            return new StanceFace(stance == null ? Long.MIN_VALUE : PlacementGeometry.positionKey(stance),
                    face == null ? -1 : face.ordinal());
        }
    }

    /** Everything known about one contested cell. */
    private static final class CellRecord {

        private final BlockPos cell;

        /** The refused (stance, face) pairs. Insertion-ordered because it is printed; its size IS the count of
         *  structurally different solutions, which is why a repeated triple cannot escalate anything. */
        private final LinkedHashSet<StanceFace> banned = new LinkedHashSet<>();

        private final List<Rejection> rejections = new ArrayList<>();

        private CellStatus status = CellStatus.CLEAR;

        private CellRecord(BlockPos cell) {
            this.cell = cell.immutable();
        }
    }

    /** One progress event. {@code +1} for a cell the server confirmed, {@code -1} for one it took away. */
    private record ProgressEvent(long tick, int delta) {
    }

    /** Contested cells, keyed by {@link PlacementGeometry#positionKey}, in the order they first went wrong. */
    private final Map<Long, CellRecord> cells = new LinkedHashMap<>();

    private final Deque<ProgressEvent> progress = new ArrayDeque<>();

    /** The tick the journal started counting from. The net-progress verdict is suppressed until a full window has
     *  elapsed since this, because "nothing placed yet" and "everything placed is being removed" are the same
     *  arithmetic and only one of them is a problem. */
    private long startTick;

    private long placed;
    private long reverted;

    public EvidenceJournal() {
        this(0L);
    }

    public EvidenceJournal(long startTick) {
        this.startTick = startTick;
    }

    /** Forget everything. Called at build start, alongside the per-run counters. */
    public void reset(long tick) {
        this.cells.clear();
        this.progress.clear();
        this.startTick = tick;
        this.placed = 0L;
        this.reverted = 0L;
    }

    // ------------------------------------------------------------------------------------------ the planner input

    /**
     * The veto, as the planner sees it. Every candidate triple in every solve passes through here.
     *
     * <p>An {@link CellStatus#EXTERNALLY_BLOCKED} cell allows NOTHING, not merely its three refused triples. That is
     * deliberate and it is what stops the fourth plan from existing: once the verdict is "somebody else's rule", a
     * fourth stance is another click spent learning the same thing. The planner then reports the cell as unsolvable
     * through its ordinary blocker channel — the same path as a cell with no geometry at all — so no separate
     * failure mode has to be invented for it.
     */
    @Override
    public boolean allows(BlockPos cell, BlockPos stance, Direction face) {
        CellRecord record = this.cells.get(PlacementGeometry.positionKey(cell));
        if (record == null) {
            return true;
        }
        if (record.status == CellStatus.EXTERNALLY_BLOCKED) {
            return false;
        }
        return !record.banned.contains(StanceFace.of(stance, face));
    }

    /**
     * This journal and another filter, both of which must allow a triple.
     *
     * <p>The planner keeps its own within-plan vetoes; the executor's journal survives across plans. They are the two
     * re-entries of E-D and they compose rather than replace each other.
     */
    public PlacementOracle.StanceFilter combinedWith(PlacementOracle.StanceFilter other) {
        if (other == null) {
            return this;
        }
        return (cell, stance, face) -> this.allows(cell, stance, face) && other.allows(cell, stance, face);
    }

    // ---------------------------------------------------------------------------------------------- what goes in

    /**
     * Record one verified rejection and return the cell's status afterwards.
     *
     * <p>Idempotent per triple. Re-rejecting a triple the journal already knows adds the evidence line but does NOT
     * advance the escalation, because the escalation counts structurally different solutions and the same triple
     * twice is one solution refused twice. Without that, an executor that retried a click before consulting the
     * planner could escalate a cell to terminal on a single stance.
     */
    public CellStatus reject(BlockPos cell, BlockPos stance, Direction face, Reason reason, long tick) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(reason, "reason");
        CellRecord record = this.cells.computeIfAbsent(PlacementGeometry.positionKey(cell),
                key -> new CellRecord(cell));
        record.rejections.add(new Rejection(cell, stance, face, reason, tick));
        record.banned.add(StanceFace.of(stance, face));
        record.status = record.banned.size() >= REJECTIONS_BEFORE_BLOCKED
                ? CellStatus.EXTERNALLY_BLOCKED
                : CellStatus.CONTESTED;
        return record.status;
    }

    /** {@link ServerAck}'s vocabulary translated into this one, so the executor does not have to hold two mappings.
     *  Returns null for outcomes that are not rejections — a confirmed placement is not evidence against anything. */
    public static Reason reasonFor(ServerAck.Outcome outcome) {
        return switch (outcome) {
            case REVERTED -> Reason.REVERTED;
            case WRONG_STATE -> Reason.WRONG_RESULT;
            case TIMED_OUT -> Reason.NO_CONFIRMATION;
            default -> null;
        };
    }

    /**
     * A block change the server reported. Clears the record of every cell within {@link #NEIGHBOURHOOD_RADIUS}.
     *
     * <p>Fed from {@link ServerAck.ChangeSink}, i.e. from what the SERVER said, never from the client's own
     * prediction. A prediction clearing the marker would let the builder's own refused click clear the evidence
     * against that click, which is the loop again with an extra step in it.
     *
     * @return how many cell records were cleared
     */
    public int observeWorldChange(BlockPos changed) {
        Objects.requireNonNull(changed, "changed");
        if (this.cells.isEmpty()) {
            return 0;
        }
        List<Long> clear = new ArrayList<>();
        for (Map.Entry<Long, CellRecord> entry : this.cells.entrySet()) {
            if (withinNeighbourhood(entry.getValue().cell, changed)) {
                clear.add(entry.getKey());
            }
        }
        for (long key : clear) {
            this.cells.remove(key);
        }
        return clear.size();
    }

    /**
     * A server packet invalidated a cell that had already completed its acknowledgement hold.
     *
     * <p>The two effects belong together: the changed neighbourhood must forget geometric refusals that may no
     * longer apply, and the net-progress window must count the completed work the server took back. Keeping the pair
     * here prevents a packet consumer from doing one without the other.
     *
     * @return how many nearby rejection records were cleared
     */
    public int recordServerInvalidation(BlockPos changed, long tick) {
        int cleared = this.observeWorldChange(changed);
        this.recordReverted(tick);
        return cleared;
    }

    /** Chebyshev distance at or below {@link #NEIGHBOURHOOD_RADIUS} on every axis, the cell itself included. */
    public static boolean withinNeighbourhood(BlockPos cell, BlockPos changed) {
        return Math.abs(cell.getX() - changed.getX()) <= NEIGHBOURHOOD_RADIUS
                && Math.abs(cell.getY() - changed.getY()) <= NEIGHBOURHOOD_RADIUS
                && Math.abs(cell.getZ() - changed.getZ()) <= NEIGHBOURHOOD_RADIUS;
    }

    // ------------------------------------------------------------------------------------------- net progress

    /** A cell the server confirmed and that held. */
    public void recordPlaced(long tick) {
        this.placed++;
        this.progress.addLast(new ProgressEvent(tick, 1));
        this.prune(tick);
    }

    /** A placement the server took back, whether it was still pending or already retired. */
    public void recordReverted(long tick) {
        this.reverted++;
        this.progress.addLast(new ProgressEvent(tick, -1));
        this.prune(tick);
    }

    private void prune(long now) {
        while (!this.progress.isEmpty() && now - this.progress.peekFirst().tick() >= NET_PROGRESS_WINDOW_TICKS) {
            this.progress.removeFirst();
        }
    }

    /** Placed minus reverted inside the window ending at {@code now}. */
    public int netProgress(long now) {
        this.prune(now);
        int net = 0;
        for (ProgressEvent event : this.progress) {
            net += event.delta();
        }
        return net;
    }

    /**
     * Is the world being changed against the build?
     *
     * <p>Three conditions, and the two guards matter as much as the arithmetic. A full window must have elapsed since
     * the journal started, or a build that spends its first thirty seconds walking to the origin reports a stall. And
     * at least one revert must have happened inside the window, or the same thing happens on any long walk between
     * distant cells — net zero out of nothing placed and nothing lost is not the world fighting back, it is the bot
     * not having got there yet.
     */
    public boolean netProgressStalled(long now) {
        if (now - this.startTick < NET_PROGRESS_WINDOW_TICKS) {
            return false;
        }
        this.prune(now);
        boolean anyRevert = false;
        int net = 0;
        for (ProgressEvent event : this.progress) {
            net += event.delta();
            anyRevert |= event.delta() < 0;
        }
        return anyRevert && net <= 0;
    }

    /** The sentence a human reads when {@link #netProgressStalled} is true. */
    public String stallReport(long now) {
        int placedInWindow = 0;
        int revertedInWindow = 0;
        for (ProgressEvent event : this.progress) {
            if (event.delta() > 0) {
                placedInWindow++;
            } else {
                revertedInWindow++;
            }
        }
        return String.format(Locale.ROOT,
                "the world is being changed against the build: %d placed and %d reverted in the last %d ticks "
                        + "(net %d)",
                placedInWindow, revertedInWindow, NET_PROGRESS_WINDOW_TICKS, placedInWindow - revertedInWindow);
    }

    // ------------------------------------------------------------------------------------------------ reporting

    public CellStatus status(BlockPos cell) {
        CellRecord record = this.cells.get(PlacementGeometry.positionKey(cell));
        return record == null ? CellStatus.CLEAR : record.status;
    }

    public boolean isExternallyBlocked(BlockPos cell) {
        return this.status(cell) == CellStatus.EXTERNALLY_BLOCKED;
    }

    /** Has this exact triple been refused? The question {@link #allows} answers, without the cell-wide ban. */
    public boolean isBanned(BlockPos cell, BlockPos stance, Direction face) {
        CellRecord record = this.cells.get(PlacementGeometry.positionKey(cell));
        return record != null && record.banned.contains(StanceFace.of(stance, face));
    }

    /** How many structurally different solutions this cell has had refused — the escalation counter itself. */
    public int structurallyDifferentRejections(BlockPos cell) {
        CellRecord record = this.cells.get(PlacementGeometry.positionKey(cell));
        return record == null ? 0 : record.banned.size();
    }

    /** Every rejection recorded at one cell, in the order they happened. */
    public List<Rejection> rejections(BlockPos cell) {
        CellRecord record = this.cells.get(PlacementGeometry.positionKey(cell));
        return record == null ? List.of() : Collections.unmodifiableList(record.rejections);
    }

    /** Cells the server has refused every way of building, in the order they went terminal. */
    public List<BlockPos> externallyBlockedCells() {
        List<BlockPos> blocked = new ArrayList<>();
        for (CellRecord record : this.cells.values()) {
            if (record.status == CellStatus.EXTERNALLY_BLOCKED) {
                blocked.add(record.cell);
            }
        }
        return blocked;
    }

    /** Cells with evidence against them but still solvable. */
    public List<BlockPos> contestedCells() {
        List<BlockPos> contested = new ArrayList<>();
        for (CellRecord record : this.cells.values()) {
            if (record.status == CellStatus.CONTESTED) {
                contested.add(record.cell);
            }
        }
        return contested;
    }

    public long placedTotal() {
        return this.placed;
    }

    public long revertedTotal() {
        return this.reverted;
    }

    /**
     * The named halt, in full. Coordinates and the refused triples that produced them, because "1 cell blocked" tells
     * a reader nothing they can act on and "cell 118,71,-243: three stances, three faces, all refused" tells them
     * whether to move a claim boundary or to fix the schematic.
     */
    public String blockedReport() {
        List<BlockPos> blocked = this.externallyBlockedCells();
        if (blocked.isEmpty()) {
            return "no externally blocked cells";
        }
        StringBuilder out = new StringBuilder();
        out.append(blocked.size()).append(blocked.size() == 1 ? " cell is" : " cells are")
                .append(" externally blocked — the server refused every way of building ")
                .append(blocked.size() == 1 ? "it" : "them").append(":");
        for (BlockPos cell : blocked) {
            out.append('\n').append("  ").append(cell.getX()).append(',').append(cell.getY()).append(',')
                    .append(cell.getZ());
            for (Rejection rejection : this.rejections(cell)) {
                out.append('\n').append("    ").append(rejection.describe());
            }
        }
        return out.toString();
    }

    /** One line for the trace. */
    public String describe() {
        return String.format(Locale.ROOT, "journal contested=%d blocked=%d placed=%d reverted=%d",
                this.contestedCells().size(), this.externallyBlockedCells().size(), this.placed, this.reverted);
    }
}
