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

import java.util.HashMap;
import java.util.Map;

/**
 * The flight recorder: it notices when the builder starts repeating itself, and preserves the run-up.
 *
 * <p><b>Why not simply log everything.</b> That was the first answer and it is the wrong one. A 39000-tick run already
 * writes a 22 MB trace; a line per decision per cell in the work set per tick is several gigabytes, and writing it
 * slows the run enough to change what is being measured. Worse, it does not actually help: on 02.08. the owner spotted
 * a place-walk-break loop on his screen, and the evidence was ALREADY in the trace -- 710 BREAK events on that cell
 * and 1842 failed routes on its neighbour. Nobody had counted them. More raw data would have made that harder, not
 * easier.
 *
 * <p><b>So the recorder is a detector plus a ring buffer.</b> It watches for the shapes that repetition takes, and
 * when one fires it says so BY NAME and dumps the preceding {@link #RING} ticks in full. The anomaly arrives with its
 * own context attached, which is what makes a later reconstruction possible: what happened, in what order, holding
 * what, aiming where. Between anomalies it costs one array write per tick.
 *
 * <p>The four shapes, each taken from a failure this project actually had:
 * <ul>
 *   <li>{@code FROZEN} — same position and same activity for {@link #FROZEN_TICKS} ticks. The 8839-tick stand at
 *       104,-58,89 and the 11069-tick one at 70,-60,122.</li>
 *   <li>{@code HAND-FLIPFLOP} — the held item alternating between two stacks. Seen at 124,-59,114, where the bot
 *       swapped sticky_piston / cobblestone every tick without moving.</li>
 *   <li>{@code TARGET-FLIPFLOP} — the committed cell alternating. The oscillation {@code stickyPlacement} was built
 *       for; this catches the cases it does not cover.</li>
 *   <li>{@code PLACE-BREAK-LOOP} — the same cell built and torn out repeatedly. The report found a dozen cells with
 *       ten breaks against one placement.</li>
 * </ul>
 *
 * <p>Dumps are capped ({@link #MAX_DUMPS}) and each shape re-arms only after it has stopped, so a pathological run
 * produces a readable file rather than a bigger version of the problem it is describing.
 */
public final class ActionJournal {

    /** Ticks of run-up preserved with each anomaly. 400 at 20 t/s is twenty seconds of context. */
    private static final int RING = 400;
    /** How long one position plus one activity has to hold before it counts as frozen rather than busy. */
    private static final int FROZEN_TICKS = 120;
    /** Window and threshold for an alternating hand or target. Six changes over two seconds is not indecision. */
    private static final int FLIPFLOP_WINDOW = 40;
    private static final int FLIPFLOP_CHANGES = 6;
    /** Breaks of one cell, against at least one placement of it, before the pair is called a loop. */
    private static final int PLACE_BREAK_LOOP = 4;
    /** A cap, because a recorder that floods is a recorder nobody reads. */
    private static final int MAX_DUMPS = 25;

    private static final String[] ring = new String[RING];
    private static int ringHead;
    private static int dumps;

    private static String frozenKey = "";
    private static long frozenSince = -1;
    private static boolean frozenReported;

    /**
     * Steht der Bau gerade fest? Genau das, was die FROZEN-Zeile meldet -- gleiche Stelle, gleiche Taetigkeit,
     * {@link #FROZEN_TICKS} Ticks lang.
     *
     * <p>Abfragbar gemacht, weil die Geruest-Phase einen Ausgang braucht und ihn nicht aus einer eigenen Uhr
     * bauen soll. Eine Geruestzelle kann auf zwei Arten scheitern: sie bekommt ein negatives Urteil -- dann ist
     * sie geparkt und die Phase gibt sie auf --, oder sie bekommt ueberhaupt keines und bleibt unentschieden.
     * Der zweite Fall ist gemessen: Lauf 2a3a8436 endete mit OPEN=1, DONE=0, GIVEUP=0 und einem Haenger, weil
     * genau diese Lage von keiner Bedingung erfasst war. Der Stillstandsmelder sieht sie bereits; er wurde nur
     * nie gefragt.
     */
    public static boolean isFrozen() {
        return frozenReported;
    }

    /**
     * Wie viele Stillstands-EPISODEN es bisher gab. Zaehlt genau dann hoch, wenn eine neue gemeldet wird.
     *
     * <p><b>Warum eine Zaehlung und nicht der Schalter darueber.</b> {@link #isFrozen()} bleibt stehen, solange
     * der Bot an derselben Stelle dieselbe Taetigkeit macht -- es beschreibt einen ZUSTAND, nicht ein Ereignis.
     * Wer ihn als "mein Vorgang ist gescheitert" liest, bekommt fuer jeden neuen Vorgang sofort dasselbe Ja,
     * obwohl der gerade erst begonnen hat.
     *
     * <p>GEMESSEN, Lauf 78901334: die Geruest-Phase gab sieben Versuche hintereinander auf, jeweils EINEN Tick
     * nach dem Eroeffnen -- 32726 OPEN, 32727 GIVEUP, 32728 OPEN, 32729 GIVEUP, und so fort. Das Budget von acht
     * war in zehn Ticks verbraucht, alles an derselben Zelle, und keiner der Versuche hatte je eine Chance.
     *
     * <p>Mit der Episodenzahl fragt der Aufrufer das Richtige: ist seit MEINEM Beginn eine NEUE Episode
     * dazugekommen? Das ist ein Ereignis, keine Uhr.
     */
    public static int frozenEpisodes() {
        return frozenEpisodes;
    }

    private static int frozenEpisodes;

    private static final String[] handWindow = new String[FLIPFLOP_WINDOW];
    private static final String[] targetWindow = new String[FLIPFLOP_WINDOW];
    private static int windowHead;
    private static long lastFlipFlopTick = Long.MIN_VALUE;

    private static final Map<String, int[]> cellPlaceBreak = new HashMap<>();

    private ActionJournal() {
    }

    /** Forget everything. A new build must not inherit the previous one's anomalies or its dump budget. */
    public static synchronized void reset() {
        java.util.Arrays.fill(ring, null);
        java.util.Arrays.fill(handWindow, null);
        java.util.Arrays.fill(targetWindow, null);
        ringHead = 0;
        windowHead = 0;
        dumps = 0;
        frozenKey = "";
        frozenSince = -1;
        frozenReported = false;
        lastFlipFlopTick = Long.MIN_VALUE;
        cellPlaceBreak.clear();
    }

    /**
     * One build tick, as the builder saw it. Called from the same place the per-tick trace line is written, so the
     * recorder can never disagree with the trace about what happened.
     */
    public static synchronized void tick(long tick, String position, String act, String target, String hand,
                                         String goal) {
        if (!BuildTrace.isActive()) {
            return;
        }
        ring[ringHead] = tick + " " + position + " act=" + act + " tgt=" + target + " hand=" + hand + " goal=" + goal;
        ringHead = (ringHead + 1) % RING;

        // FROZEN. Position AND activity, because a bot turning on the spot to aim is working and a bot repeating the
        // same activity while walking is travelling; only the pair standing still is a stall.
        String key = position + "|" + act;
        if (key.equals(frozenKey)) {
            if (!frozenReported && frozenSince >= 0 && tick - frozenSince >= FROZEN_TICKS) {
                frozenReported = true;
                frozenEpisodes++;
                fire(tick, "FROZEN", position,
                        "same position and activity for " + (tick - frozenSince) + " ticks: " + act);
            }
        } else {
            frozenKey = key;
            frozenSince = tick;
            frozenReported = false;
        }

        handWindow[windowHead] = hand;
        targetWindow[windowHead] = target;
        windowHead = (windowHead + 1) % FLIPFLOP_WINDOW;
        if (tick - lastFlipFlopTick > FLIPFLOP_WINDOW) {
            String hands = alternatingPair(handWindow);
            if (hands != null) {
                lastFlipFlopTick = tick;
                fire(tick, "HAND-FLIPFLOP", position, "the held item alternates between " + hands
                        + " without the position changing anything");
            } else {
                String targets = alternatingPair(targetWindow);
                if (targets != null) {
                    lastFlipFlopTick = tick;
                    fire(tick, "TARGET-FLIPFLOP", position, "the committed cell alternates between " + targets);
                }
            }
        }
    }

    /**
     * A cell event, tapped straight off {@link BuildTrace#cell} so no call site has to remember to report.
     *
     * @param kind the trace verb; only landings and breaks are counted here
     */
    public static synchronized void cellEvent(long tick, String kind, int x, int y, int z) {
        if (!BuildTrace.isActive()) {
            return;
        }
        boolean placed = "LAND".equals(kind) || "DONE".equals(kind) || "SCAFFOLD".equals(kind);
        boolean broke = "BREAK".equals(kind);
        if (!placed && !broke) {
            return;
        }
        String cell = x + "," + y + "," + z;
        int[] counts = cellPlaceBreak.computeIfAbsent(cell, ignored -> new int[3]);
        if (placed) {
            counts[0]++;
        } else {
            counts[1]++;
        }
        // BREAK is written once per tick of mining, so a single removal is a burst of them. Counting distinct
        // placements against breaks would call one honest clear-and-rebuild a loop; requiring BOTH a placement and
        // repeated breaking is what separates "tore it out again" from "took a while to mine".
        if (counts[0] > 0 && counts[1] >= PLACE_BREAK_LOOP && counts[2] == 0) {
            counts[2] = 1;
            fire(tick, "PLACE-BREAK-LOOP", cell,
                    counts[0] + " placement(s) against " + counts[1] + " breaks of the same cell");
        }
    }

    /** The two-value alternation test: exactly two distinct values, changing often, inside one window. */
    private static String alternatingPair(String[] window) {
        String first = null;
        String second = null;
        int changes = 0;
        String previous = null;
        for (int i = 0; i < window.length; i++) {
            String value = window[(windowHead + i) % window.length];
            if (value == null) {
                return null;   // window not full yet; a partial window invents alternations that are not there
            }
            if (first == null) {
                first = value;
            } else if (!value.equals(first) && second == null) {
                second = value;
            } else if (!value.equals(first) && !value.equals(second)) {
                return null;   // three or more values is variety, not oscillation
            }
            if (previous != null && !previous.equals(value)) {
                changes++;
            }
            previous = value;
        }
        return second != null && changes >= FLIPFLOP_CHANGES ? first + " <-> " + second : null;
    }

    /** Name the anomaly, then hand over the run-up that produced it. */
    private static void fire(long tick, String pattern, String where, String detail) {
        BuildTrace.cell(tick, "REPEAT", 0, 0, 0, "pattern=" + pattern + " at=" + where + " -- " + detail);
        if (dumps >= MAX_DUMPS) {
            return;
        }
        dumps++;
        BuildTrace.journal("J ---- " + pattern + " at " + where + ", tick " + tick + ", last " + RING + " ticks ----");
        for (int i = 0; i < RING; i++) {
            String line = ring[(ringHead + i) % RING];
            if (line != null) {
                BuildTrace.journal("J " + line);
            }
        }
        BuildTrace.journal("J ---- end " + pattern + " ----");
    }
}
