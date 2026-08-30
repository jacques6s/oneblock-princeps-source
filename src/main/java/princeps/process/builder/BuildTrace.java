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

package princeps.process.builder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * A complete, per-tick record of what the builder did — not only what went wrong.
 *
 * <p>Written because the ordinary log is a FAULT log and that hid the largest defect in this project for eight
 * sessions. A 96000-tick run produced 8758 lines, of which 4800 were fixed-interval bench samples; a successful
 * placement produced NO line at all, and the layer give-up recorded "setting aside its remaining 105 cell(s)" without
 * naming one of them. So 175 of 245 missing cells appeared nowhere: the world was audited against the schematic and
 * simply had holes that nothing in the log could explain.
 *
 * <p>This file answers one question and is shaped entirely around it: <em>name a coordinate, and see everything that
 * ever happened to that cell.</em> Every line therefore carries the tick, and every cell event carries the coordinate
 * in a fixed {@code x,y,z} form, so the whole life of one block is {@code grep "112,-58,93"}.
 *
 * <p>Three line kinds:
 * <pre>
 *   T &lt;tick&gt; pos=x,y,z look=yaw,pitch/NEAREST layer=N work=N retired=N act=&lt;what&gt; tgt=x,y,z
 *   E &lt;tick&gt; &lt;EVENT&gt; x,y,z &lt;detail&gt;
 *   P &lt;tick&gt; &lt;complete path nodes and movement classes, once per new route&gt;
 * </pre>
 *
 * <p>Cost: one buffered line per tick plus events, flushed every {@link #FLUSH_EVERY} lines rather than per write.
 * At 300000 ticks a run this is tens of megabytes, which the owner has explicitly accepted in exchange for being able
 * to explain any single missing block.
 */
public final class BuildTrace {

    /** Lines buffered before hitting the disk. Per-line flushing on the render thread is what makes tracing cost. */
    private static final int FLUSH_EVERY = 512;

    private static BuildTrace active;

    private final Writer out;
    private final Path path;
    private int sinceFlush;
    private long lines;

    private BuildTrace(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        this.out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8), 1 << 16);
    }

    /**
     * Begin a trace for this build, replacing any previous one.
     *
     * @param tag identifies the run in the file name; a bench run id, or anything unique
     * @return the file being written, or null if tracing could not be started (which is never fatal)
     */
    public static synchronized Path start(String tag) {
        stop();
        try {
            String safe = tag == null || tag.isEmpty() ? "build" : tag.replaceAll("[^A-Za-z0-9._-]", "-");
            // "logs", not "run/logs": the client's working directory IS run/, which is why latest.log lives at
            // run/logs/latest.log. Getting this wrong put the first trace in run/run/logs and it took a
            // directory listing to find it.
            active = new BuildTrace(Paths.get("logs", "build-trace-" + safe + ".log"));
            active.raw("# princeps build trace: T=tick, E=cell event, P=complete route snapshot");
            active.raw("# T <tick> pos=x,y,z look=yaw,pitch/NEAREST layer=N work=N retired=N act=<what> tgt=x,y,z");
            active.raw("# E <tick> <EVENT> x,y,z <detail>");
            active.raw("# P <tick> complete route, emitted once whenever the path object changes");
            return active.path;
        } catch (IOException e) {
            active = null;
            return null;
        }
    }

    public static synchronized void stop() {
        if (active != null) {
            try {
                active.out.close();
            } catch (IOException ignored) {
                // a trace that cannot be closed is still a trace that was written
            }
            active = null;
        }
    }

    public static boolean isActive() {
        return active != null;
    }

    public static Path path() {
        return active == null ? null : active.path;
    }

    public static long lineCount() {
        return active == null ? 0L : active.lines;
    }

    /**
     * The per-tick line. Called once per build tick, before anything decides what to do.
     *
     * <p>Every field here exists because some past investigation needed it and did not have it:
     * <ul>
     *   <li>{@code eye} and {@code sneak} — the eye is 1.62 above the feet standing and 1.27 crouching, and EVERY
     *       dominance calculation in the aim derivation turns on which one it was;
     *   <li>{@code ground} — a click taken while falling has neither of those eye heights;
     *   <li>{@code vel} — whether the bot was still moving when it clicked;
     *   <li>{@code aimd} — degrees the visible aim moved since the previous tick, i.e. whether the look had ARRIVED.
     *       A click fired mid-swing reaches the server at an angle the builder never computed, which is the leading
     *       explanation for a piston that wanted facing=up and holds facing=south;
     *   <li>{@code hand} — the item actually selected, because the wrong item in hand is the wrong block placed;
     *   <li>{@code path} — distinguishes walking from standing still with no idea what to do.
     * </ul>
     */
    /** Whether the full journal is on: every discarded candidate, not only the ones that survived. Gigabytes on a
     *  real schematic, and meant to be. */
    public static boolean isVerbose() {
        return active != null && VERBOSE;
    }

    /** {@code -PfullTrace} / {@code -Dprinceps.buildtrace.full=true}. Read once: a per-candidate branch is on the
     *  hottest path the planner has, and a system property lookup there would cost more than the write. */
    private static final boolean VERBOSE = Boolean.getBoolean("princeps.buildtrace.full");

    /** One discarded candidate: why, for which cell, from which stance. */
    public static void rejected(String reason, Object subject, Object from) {
        BuildTrace t = active;
        if (t == null) {
            return;
        }
        t.raw("R " + reason + " cell=" + (subject == null ? "-" : subject) + " from=" + (from == null ? "-" : from));
    }

    public static void tick(long tick, double x, double y, double z, double eye, boolean sneaking, boolean onGround,
                            double vx, double vy, double vz, float yaw, float pitch, String nearest, float aimDelta,
                            String hand, int slot, int layer, int work, int retired, String path,
                            String action, String target, String detail) {
        BuildTrace t = active;
        if (t == null) {
            return;
        }
        t.raw(String.format(Locale.ROOT,
                "T %d pos=%.3f,%.3f,%.3f eye=%.3f sneak=%d ground=%d vel=%.3f,%.3f,%.3f "
                        + "look=%.2f,%.2f/%s aimd=%.2f hand=%s@%d layer=%d work=%d retired=%d path=%s act=%s tgt=%s %s",
                tick, x, y, z, eye, sneaking ? 1 : 0, onGround ? 1 : 0, vx, vy, vz,
                yaw, pitch, nearest, aimDelta, hand == null ? "-" : hand, slot,
                layer, work, retired, path == null ? "-" : path,
                action == null ? "-" : action, target == null ? "-" : target,
                detail == null ? "-" : detail));
    }

    /**
     * The builder's tick and its current intent, so code OUTSIDE the builder can write a line that says WHEN and WHY.
     *
     * <p>This exists because the trace could not answer the one question that matters about a helper block: when was it
     * placed, and what was the bot trying to do at the time. Three concrete gaps, all measured on the facings run
     * 762769a0 and its 11 leftover cobblestone blocks:
     *
     * <ul>
     *   <li>{@code MovementHelper.attemptToPlaceABlock} writes its SCAFFOLD line with a literal {@code 0} for the tick,
     *       because a movement has no access to {@code buildTick}. Every scaffold line in every run so far is therefore
     *       untimed, and a filter of "tick &lt;= N" silently lets them all through -- which looked like a valid
     *       tick-matched comparison and was not.</li>
     *   <li>{@code MovementPillar} places its block directly via {@code setInput(CLICK_RIGHT)} and writes NOTHING. 3 of
     *       those 11 blocks had no event of any kind, so they were invisible to every census.</li>
     *   <li>No placement line says which CELL the block was being placed for, so "he put a block here" could never be
     *       connected to "because he was trying to reach that".</li>
     * </ul>
     *
     * <p>Set once per build tick; read by any writer that has no builder reference. Volatile rather than synchronized:
     * a stale tick in a diagnostic line is harmless, a lock on the render thread is not.
     */
    private static volatile long contextTick;
    private static volatile String contextIntent = "-";

    public static void context(long tick, String intent) {
        contextTick = tick;
        contextIntent = intent == null ? "-" : intent;
    }

    /** The builder's current tick, for writers that have no builder reference. */
    public static long tickNow() {
        return contextTick;
    }

    /** What the builder was trying to do at {@link #tickNow()}, for the same writers. */
    public static String intentNow() {
        return contextIntent;
    }

    // ==============================================================================================================
    // WORLD-CHANGE CENSUS
    //
    // Every block this bot puts into the world must be attributable to whatever wanted it there. Until now it was
    // not, and the gap is not theoretical: attemptToPlaceABlock writes a line when it is READY to place, but the
    // click itself is forced by the CALLER -- MovementTraverse:322 and :365, MovementAscend:187, MovementParkour:296,
    // MovementFall:116, and MovementPillar:306, which does not pass the choke point at all. "Ready" is not a
    // placement. On the facings run 762769a0 three of eleven leftover cobblestone blocks carried no event of any
    // kind, so every census of helper blocks was short by exactly the ones nobody wrote down.
    //
    // Two halves, deliberately kept apart so a mismatch between them is itself a finding:
    //   INTENT-PLACE  written by the call site immediately before it forces the right-click
    //   WORLD         written at the ONE place where a right-click actually becomes a world interaction
    //                 (BlockPlaceHelper, processRightClickBlock == SUCCESS), carrying the pending intent
    //
    // A WORLD line reading src=UNATTRIBUTED is a world change nobody declared. That number has to be zero before
    // any claim of the form "this lane never places a block" can be believed -- which is the whole reason this
    // exists ahead of the rule it is meant to police.
    //
    // Counted whether or not a trace FILE is open: the counters are the acceptance criterion, the file is the
    // detail. worldChangeCensus() is printed periodically into the ordinary game log, which every gate stage
    // archives, so the census survives stages that run without -BuildTrace.

    private static volatile String pendingIntentSource;
    private static volatile String pendingIntentDetail;

    private static final java.util.concurrent.atomic.AtomicLong worldChanges =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong unattributedWorldChanges =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> worldChangesBySource =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Declare who is about to change the world, immediately before forcing the right-click that does it.
     *
     * <p>Call this at the site that presses the button, not where the decision was taken: the two are several
     * frames apart in every movement, and it is the press that reaches the server.
     */
    public static void intendWorldChange(String source, int x, int y, int z, String detail) {
        pendingIntentSource = source == null ? "?" : source;
        pendingIntentDetail = detail;
        cell(contextTick, "INTENT-PLACE", x, y, z,
                "src=" + pendingIntentSource + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /**
     * A right-click just became a real world interaction. Consumes the pending declaration, so a second interaction
     * without its own declaration is reported as unattributed rather than borrowing the previous one's credit.
     */
    public static void worldChanged(int x, int y, int z, String detail) {
        String source = pendingIntentSource;
        String intentDetail = pendingIntentDetail;
        pendingIntentSource = null;
        pendingIntentDetail = null;
        boolean attributed = source != null;
        if (!attributed) {
            source = "UNATTRIBUTED";
            unattributedWorldChanges.incrementAndGet();
        }
        worldChanges.incrementAndGet();
        worldChangesBySource
                .computeIfAbsent(source, ignored -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
        cell(contextTick, "WORLD", x, y, z, "src=" + source
                + (detail == null || detail.isEmpty() ? "" : " " + detail)
                + (intentDetail == null || intentDetail.isEmpty() ? "" : " " + intentDetail));
    }

    /** World interactions nobody declared. The acceptance criterion of the census: this has to be zero. */
    public static long unattributedWorldChanges() {
        return unattributedWorldChanges.get();
    }

    public static long worldChanges() {
        return worldChanges.get();
    }

    /** One line, sources ordered by count, for the ordinary game log. */
    public static String worldChangeCensus() {
        StringBuilder sb = new StringBuilder();
        worldChangesBySource.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(e.getValue().get()).append("x ").append(e.getKey());
                });
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** Cleared with the build, so one run's numbers never leak into the next one's census. */
    public static void resetWorldChangeCensus() {
        pendingIntentSource = null;
        pendingIntentDetail = null;
        worldChanges.set(0L);
        unattributedWorldChanges.set(0L);
        worldChangesBySource.clear();
    }
    // ==============================================================================================================

    /**
     * A cell event. {@code event} is a short uppercase verb so the file greps cleanly by kind as well as by
     * coordinate: ENTER, PICK, STANCE, AIM, CLICK, LAND, REJECT, DEFER, RETIRE, RECALL, DROP, DONE.
     */
    public static void cell(long tick, String event, int x, int y, int z, String detail) {
        BuildTrace t = active;
        if (t == null) {
            return;
        }
        t.raw("E " + tick + " " + event + " " + x + "," + y + "," + z + (detail == null ? "" : " " + detail));
        // Tapped here rather than at each call site, so a verb added later is watched for repetition automatically
        // and nobody has to remember. ActionJournal ignores everything but landings and breaks, which is also what
        // keeps its own REPEAT events from feeding back into it.
        ActionJournal.cellEvent(tick, event, x, y, z);
    }

    /** A journal line from {@link ActionJournal}: same file, own {@code J} prefix, so the recorder needs no sink. */
    public static void journal(String line) {
        BuildTrace t = active;
        if (t == null) {
            return;
        }
        t.raw(line);
    }

    /** A complete route snapshot. Kept out of every T line so long paths cost once, not once per movement tick. */
    public static void path(long tick, String detail) {
        BuildTrace t = active;
        if (t == null) {
            return;
        }
        t.raw("P " + tick + " " + (detail == null ? "-" : detail));
    }

    private void raw(String line) {
        try {
            out.write(line);
            out.write('\n');
            lines++;
            if (++sinceFlush >= FLUSH_EVERY) {
                sinceFlush = 0;
                out.flush();
            }
        } catch (IOException ignored) {
            // Tracing must never take a build down. A trace that stops is a diagnostic loss, not a failure.
        }
    }
}
