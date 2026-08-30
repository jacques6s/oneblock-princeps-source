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

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * One named cell, every decision about it, one line each.
 *
 * <p>Why this exists: twice in a row a diagnosis was drawn from MISSING trace events and twice it was wrong (A28, the
 * work window; A29, the hotbar). Both failed the same way — {@code searchForPlacables} and {@code assemble} between
 * them have nine ways to walk past a cell and not one of them writes anything, so "no events" was read as "never
 * offered" when it could equally mean "offered and silently dropped nine times a tick".
 *
 * <p>The fix is not a tenth guess. It is a line per skipped cell — but a line per skipped cell over 15 004 cells is
 * gigabytes, so it is scoped to the cells actually under investigation:
 *
 * <pre>gradlew ... -Dprinceps.builder.watch=116,-59,73;116,-59,72</pre>
 *
 * <p>Off by default and free when off: {@link #WATCHED} is empty, {@link #isWatched} returns on a length check, and no
 * call site allocates. Output goes to the build trace as {@code WATCH} events, so {@code TraceReplay} parses them like
 * any other cell event and a run leaves the answer on disk.
 *
 * <p>Deduplicated by (cell, site): a verdict that has not changed is written once, then again only every
 * {@link #HEARTBEAT_TICKS} build ticks. Without that, a cell skipped every tick for 20 000 ticks produces 20 000
 * identical lines and the one line where it CHANGED is unfindable — which is the same blindness this class exists to
 * remove, just louder.
 */
public final class CellWatch {

    /**
     * Where the watch list is looked for, in order. The first one that yields a cell wins.
     *
     * <p>DECLARED BEFORE {@link #WATCHED}, and that is not a style choice: static initialisers run in source order, so
     * a {@code load()} that reads this array while it is still null throws {@code ExceptionInInitializerError} and
     * every test that touches the class fails with a {@code NoClassDefFoundError} pointing somewhere else entirely.
     */
    static final String[] SOURCES = {
            "watch-cells.txt",              // the bench client: working directory is run/
            "../autonomy/watch-cells.txt",  // ... so the shared one sits one level up
            "autonomy/watch-cells.txt",     // a unit test or a plain JVM started at the repo root
    };

    /**
     * The watch list, read once: {@link #isWatched} sits in the placement scan.
     *
     * <p>Two sources, and the FILE is the one that matters. {@code scripts/bench.ps1} is protected and builds its
     * gradle argument list from its own switches only, so there is no {@code -P} to add and no way to get a system
     * property into the bench client from outside. An environment variable is worse than useless here: the run task
     * forks from the Gradle DAEMON, so {@code System.getenv} in the build script reads the daemon's environment and
     * a long-lived daemon would serve a stale one silently.
     *
     * <p>A file has neither problem. The client's working directory is {@code run/}, a unit test's is the repo root,
     * so both are tried.
     */
    private static final long[] WATCHED = load();

    /** How long an unchanged verdict stays silent before it is restated. */
    private static final int HEARTBEAT_TICKS = 400;

    private static final Map<String, String> LAST_DETAIL = new HashMap<>();
    private static final Map<String, Long> LAST_TICK = new HashMap<>();

    private CellWatch() {
    }

    /** True when any cell is being watched at all — for guarding a call site whose ARGUMENTS are expensive to build. */
    public static boolean active() {
        return WATCHED.length > 0;
    }

    public static boolean isWatched(int x, int y, int z) {
        if (WATCHED.length == 0) {
            return false;
        }
        long key = BlockPos.asLong(x, y, z);
        for (long watched : WATCHED) {
            if (watched == key) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record what happened to this cell at one decision point.
     *
     * @param site  where the decision was made, e.g. {@code assemble} or {@code scan}
     * @param detail the verdict, e.g. {@code missing-material want=redstone_wire}
     */
    public static void note(long tick, int x, int y, int z, String site, String detail) {
        if (!isWatched(x, y, z)) {
            return;
        }
        String key = site + "@" + x + "," + y + "," + z;
        synchronized (LAST_DETAIL) {
            String previous = LAST_DETAIL.get(key);
            long since = tick - LAST_TICK.getOrDefault(key, Long.MIN_VALUE / 2);
            if (detail.equals(previous) && since < HEARTBEAT_TICKS) {
                return;
            }
            boolean repeat = detail.equals(previous);
            LAST_DETAIL.put(key, detail);
            LAST_TICK.put(key, tick);
            BuildTrace.cell(tick, "WATCH", x, y, z,
                    "site=" + site + (repeat ? " still" : "") + " " + detail);
        }
    }

    /** Forget every verdict, so a fresh build does not inherit the previous one's dedupe state. */
    public static void reset() {
        synchronized (LAST_DETAIL) {
            LAST_DETAIL.clear();
            LAST_TICK.clear();
        }
    }

    private static long[] load() {
        long[] fromProperty = parse(System.getProperty("princeps.builder.watch", ""));
        if (fromProperty.length > 0) {
            return fromProperty;
        }
        for (String source : SOURCES) {
            java.io.File file = new java.io.File(source);
            if (!file.isFile()) {
                continue;
            }
            try {
                // Newlines and semicolons both separate; `#` starts a comment, so the file can say WHY it is watching.
                String text = java.nio.file.Files.readString(file.toPath());
                StringBuilder cleaned = new StringBuilder();
                for (String line : text.split("\\R")) {
                    int hash = line.indexOf('#');
                    String body = (hash >= 0 ? line.substring(0, hash) : line).trim();
                    if (!body.isEmpty()) {
                        cleaned.append(body).append(';');
                    }
                }
                long[] parsed = parse(cleaned.toString());
                if (parsed.length > 0) {
                    return parsed;
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // An unreadable watch list must never take a build down; it is a diagnostic, not a dependency.
            }
        }
        return new long[0];
    }

    private static long[] parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new long[0];
        }
        String[] cells = raw.split(";");
        long[] out = new long[cells.length];
        int n = 0;
        for (String cell : cells) {
            String[] xyz = cell.trim().split(",");
            if (xyz.length != 3) {
                continue;   // a malformed entry must not take the build down; the missing lines say enough
            }
            try {
                out[n++] = BlockPos.asLong(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()),
                        Integer.parseInt(xyz[2].trim()));
            } catch (NumberFormatException ignored) {
                // same
            }
        }
        if (n == out.length) {
            return out;
        }
        long[] trimmed = new long[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }
}
