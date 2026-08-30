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

package princeps.process.builder.replay;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The parser half runs everywhere; the oracle half needs a trace on disk and is skipped without one.
 *
 * <p>{@code run/logs/} is gitignored, so a checkout on another machine has no traces. That is deliberate — the
 * harness is a tool for the traces of the moment, not a fixture — but it means the expensive test has to be an
 * {@code assumeTrue} rather than a failure. What must NOT be conditional is the parser: it is pure text and is
 * pinned here against a trace written inline.
 */
public class TraceReplayTest {

    private static final File TRACE_DIR = new File("run/logs");

    @Test
    public void theParserReadsCellEventsAndIgnoresTickAndRouteLines() throws Exception {
        File tmp = File.createTempFile("princeps-trace", ".log");
        tmp.deleteOnExit();
        java.nio.file.Files.writeString(tmp.toPath(), String.join("\n",
                "# princeps build trace: T=tick, E=cell event, P=complete route snapshot",
                "T 190 pos=64.500,-60.000,69.201 act=travel tgt=70,-60,81",
                "P 23 len=15 exec=0 0:64,-60,64{Block{minecraft:air}}-MovementTraverse->",
                "E 48 PICK 75,-60,70 want=black_stained_glass against=75,-61,70 face=up rot=-50.0,14.8",
                "E 51 LAND 75,-60,70 want=black_stained_glass got=Block{minecraft:black_stained_glass} match=yes",
                "E 51 DONE 75,-60,70 got=black_stained_glass",
                "E 60 LAND 76,-60,70 want=repeater got=Block{minecraft:repeater}[delay=1] match=no",
                "E 99 DEFER 80,-59,70 attempt=3 why=all currently valid placement stances were exhausted",
                "E 120 DEFER 80,-59,70 attempt=4 why=all currently valid placement stances were exhausted",
                "E 130 DEFER 81,-59,70 attempt=1 why=whatever",
                "E 140 DEADGOAL 79,-60,70 goal=JankyComposite Primary: GoalComposite[]") + "\n");

        List<TraceReplay.Event> events = TraceReplay.parse(tmp);
        assertEquals("only E lines are cell events", 8, events.size());
        assertEquals(48, events.get(0).tick());
        assertEquals("PICK", events.get(0).kind());
        assertEquals(new BlockPos(75, -60, 70), events.get(0).pos());

        Map<BlockPos, Integer> landed = TraceReplay.landedByTick(events);
        assertEquals("match=no is not a landing, and LAND+DONE of one cell is one entry", 1, landed.size());
        assertEquals(Integer.valueOf(51), landed.get(new BlockPos(75, -60, 70)));

        assertEquals(140, TraceReplay.lastTickFor(events, new BlockPos(79, -60, 70)));
        assertEquals("a cell the trace never mentions has no last tick",
                -1, TraceReplay.lastTickFor(events, new BlockPos(1, 2, 3)));

        List<Map.Entry<BlockPos, Integer>> deferred = TraceReplay.deferredCells(events);
        assertEquals(2, deferred.size());
        assertEquals("most-deferred first", new BlockPos(80, -59, 70), deferred.get(0).getKey());
        assertEquals(Integer.valueOf(2), deferred.get(0).getValue());
    }

    /**
     * The measurement this harness exists for: the twenty {@code sticky_piston} cells the V1 baseline gives up on.
     *
     * <p>Asserts only that the harness ANSWERS — the answer itself is a finding, not a contract, and pinning it here
     * would turn the next real change into a red test for the wrong reason. The report is printed so a run of
     * {@code gradlew test} carries it.
     */
    @Test
    public void aDeferredStickyPistonCellGetsAnAnswerWithoutAClientOrAServer() throws Exception {
        File trace = newestTrace();
        assumeTrue("no build trace in " + TRACE_DIR + " -- nothing to replay", trace != null);

        List<TraceReplay.Event> events = TraceReplay.parse(trace);
        List<Map.Entry<BlockPos, Integer>> deferred = TraceReplay.deferredCells(events);
        assumeTrue("trace " + trace.getName() + " has no DEFER events", !deferred.isEmpty());

        long openedAt = System.nanoTime();
        TraceReplay.Session session = TraceReplay.Session.open(trace, TraceReplay.BENCH_ORIGIN);
        long setupMs = (System.nanoTime() - openedAt) / 1_000_000L;

        StringBuilder out = new StringBuilder();
        out.append("[replay] trace=").append(trace.getName())
                .append("  deferred cells=").append(deferred.size())
                .append("  setup=").append(setupMs).append(" ms\n");

        int solvable = 0;
        long slowest = 0;
        long total = 0;
        int scaffoldCandidates = 0;
        int placeableScaffold = 0;
        for (Map.Entry<BlockPos, Integer> entry : deferred) {
            long startedAt = System.nanoTime();
            TraceReplay.Report report = session.replay(entry.getKey(), -1);
            long ms = (System.nanoTime() - startedAt) / 1_000_000L;
            total += ms;
            slowest = Math.max(slowest, ms);
            if (report.solvable()) {
                solvable++;
            }
            out.append("  ").append(ms).append(" ms  x").append(entry.getValue()).append("  ")
                    .append(report.text().replace("\n", "\n      ").stripTrailing()).append('\n');
            if (!report.solvable()) {
                List<String> unblocking = session.unblockingNeighbours(entry.getKey(), -1);
                out.append("      unblocked by: ")
                        .append(unblocking.isEmpty() ? "NO SINGLE NEIGHBOUR" : String.join(", ", unblocking))
                        .append('\n');
                List<String> scaffold = session.unblockingScaffold(entry.getKey(), -1);
                out.append("      scaffold would work at: ")
                        .append(scaffold.isEmpty() ? "NOWHERE" : String.join(", ", scaffold))
                        .append('\n');
                for (String s : scaffold) {
                    scaffoldCandidates++;
                    if (!s.contains("[FLOATS")) {
                        placeableScaffold++;
                    }
                }
            }

            assertNotNull(report.desired());
            assertFalse("the oracle must have looked at candidate stances", report.candidateStances().isEmpty());
        }
        out.append("[replay] ").append(solvable).append(" of ").append(deferred.size())
                .append(" solvable; per cell max ").append(slowest)
                .append(" ms, mean ").append(total / Math.max(1, deferred.size())).append(" ms")
                .append("; scaffold candidates ").append(scaffoldCandidates)
                .append(", of them placeable ").append(placeableScaffold).append('\n');

        // Written to a file and not only to stdout: Gradle swallows System.out of a passing test unless the build
        // is told otherwise, and the whole point of this harness is that a run leaves an answer behind.
        File report = new File("run/replay");
        assertTrue("cannot create run/replay", report.isDirectory() || report.mkdirs());
        java.nio.file.Files.writeString(new File(report, "last-replay.txt").toPath(), out.toString());
        System.out.print(out);

        assertTrue("a cell must be answerable in well under a second once the session is open", slowest < 1000L);
        // THE PROBE CONTROL. A scaffold position on the build floor sits directly on the base stone one block below
        // and is the most trivially placeable case there is -- if not one single candidate comes back placeable, the
        // probe is not measuring geometry. That is exactly how the first version failed: it probed with a block the
        // inventory does not hold, PlacementOracle refused everything at NO_ITEM, and "every candidate floats" read
        // like a finding. Zero placeable candidates is a broken tool, not a result.
        if (solvable < deferred.size()) {
            // NULL candidates is the sister failure of "all candidates refused", and it has already happened once:
            // an exclusion written as view.desired(side) == null threw away every position inside the volume that
            // the schematic leaves empty -- which is exactly where scaffolding goes -- and all twenty cells flipped
            // to NOWHERE in one go. A silent zero reads as "nowhere to put it" and is indistinguishable from a
            // finding, so it is asserted, not assumed.
            assertTrue("the scaffold probe produced NO candidate at all for " + (deferred.size() - solvable)
                            + " unsolvable cells -- check the candidate filter before reading this as a result",
                    scaffoldCandidates > 0);
            assertTrue("the scaffold probe refused ALL " + scaffoldCandidates + " candidates -- that is a tool "
                            + "failure, not a finding (check that the probe block is one the inventory holds)",
                    placeableScaffold > 0);
        }
    }

    /**
     * The calibration, and the harness is worthless without it.
     *
     * <p>Every "NO STANCE" verdict rests on a world this class RECONSTRUCTS: stone below the build floor, air above,
     * plus the cells the trace reports as landed. If that model is missing support the real world had, the oracle
     * would report "nothing solid to click" for cells the bot placed without trouble — and the whole report would be
     * an artefact of the reconstruction rather than a finding about the build.
     *
     * <p>So: take cells the trace says V1 DID place, rebuild the world as of the tick before each landed, and require
     * the oracle to find them solvable. A failure here does not mean the builder is broken; it means this file is.
     */
    @Test
    public void cellsTheBuilderActuallyPlacedComeBackSolvable() throws Exception {
        File trace = newestTrace();
        assumeTrue("no build trace in " + TRACE_DIR + " -- nothing to replay", trace != null);

        List<TraceReplay.Event> events = TraceReplay.parse(trace);
        Map<BlockPos, Integer> landed = TraceReplay.landedByTick(events);
        assumeTrue("trace " + trace.getName() + " reports no landed cells", landed.size() >= 40);

        TraceReplay.Session session = TraceReplay.Session.open(trace, TraceReplay.BENCH_ORIGIN);

        // PER LAYER, and that is the whole point. A first version took every 47th landed cell and got twenty samples
        // that all sat on layer -60 -- where the stone below floorY guarantees support from the BASE assumption. That
        // control cannot fail even if the overlay of landed cells contributes nothing, and the overlay is exactly what
        // carries every verdict one layer higher. Sampling per layer forces the thin upper layers into the control.
        Map<Integer, List<BlockPos>> byLayer = new TreeMap<>();
        for (BlockPos cell : landed.keySet()) {
            if (session.expectedAt(cell) == null) {
                continue;   // neither the schematic nor the trace says what was supposed to be here -- no question
            }
            byLayer.computeIfAbsent(cell.getY(), y -> new ArrayList<>()).add(cell);
        }
        List<BlockPos> sample = new ArrayList<>();
        for (List<BlockPos> layer : byLayer.values()) {
            int step = Math.max(1, layer.size() / 12);
            for (int i = 0; i < layer.size(); i += step) {
                sample.add(layer.get(i));
            }
        }

        int checked = 0;
        int solvable = 0;
        StringBuilder failures = new StringBuilder();
        for (BlockPos cell : sample) {
            int landedAt = landed.get(cell);
            // The world one tick BEFORE it landed: the cell itself must still be open, or the question is empty.
            // The expected block explicitly, so a cell the schematic has no opinion about -- a click anchor, say --
            // is ASKED about rather than dropped from the control.
            TraceReplay.Report report =
                    session.replay(cell, landedAt - 1, true, Map.of(), session.expectedAt(cell));
            checked++;
            if (report.solvable()) {
                solvable++;
            } else {
                failures.append("    ").append(report.text().replace("\n", "\n    ").stripTrailing()).append('\n');
            }
        }

        // THE NEGATIVE CONTROL. Without it the line above says only "the base assumption is sound". Take the cells
        // that are NOT on the build floor -- the ones whose support can only come from the overlay -- and ask them
        // again with the overlay switched off. Every one that flips to unsolvable is a cell whose answer the
        // reconstruction actually produced.
        int aboveFloor = 0;
        int flipped = 0;
        for (BlockPos cell : sample) {
            if (cell.getY() <= TraceReplay.BENCH_ORIGIN.getY()) {
                continue;   // the floor layer has stone below it whether the overlay exists or not
            }
            aboveFloor++;
            int landedAt = landed.get(cell);
            BlockState want = session.expectedAt(cell);
            boolean withOverlay = session.replay(cell, landedAt - 1, true, Map.of(), want).solvable();
            boolean without = session.replay(cell, landedAt - 1, false, Map.of(), want).solvable();
            if (withOverlay && !without) {
                flipped++;
            }
        }

        String headline = "[replay-calibration] trace=" + trace.getName() + "  " + solvable + " of " + checked
                + " cells the builder ACTUALLY PLACED come back solvable"
                + "  |  negative control: " + flipped + " of " + aboveFloor
                + " control cells above the floor flip to unsolvable without the overlay\n";
        File dir = new File("run/replay");
        assertTrue("cannot create run/replay", dir.isDirectory() || dir.mkdirs());
        java.nio.file.Files.writeString(new File(dir, "last-calibration.txt").toPath(), headline + failures);
        System.out.print(headline);
        if (solvable < checked) {
            System.out.println(failures);
        }
        assertTrue("the reconstructed world must be able to explain the cells that WERE built -- "
                        + solvable + " of " + checked + " solvable:\n" + failures,
                solvable * 100 >= checked * 80);
        assertTrue("the control must reach above the build floor, or it only proves the base assumption",
                aboveFloor > 0);
        assertTrue("the overlay of landed cells must be load-bearing: " + flipped + " of " + aboveFloor
                        + " control cells above the floor flip to unsolvable without it",
                flipped > 0);
    }

    /**
     * The newest trace of a BASALT run — not simply the newest trace.
     *
     * <p>The harness loads {@code etz-basalt.litematic} and anchors it at {@link TraceReplay#BENCH_ORIGIN}, so a
     * trace from any other scenario describes a different building in a different place. Taking the newest file
     * outright broke the moment a {@code ringbig} run happened to be the last thing measured: the origin guard threw
     * with "93 of 121 landed cells fall outside the schematic", which is the guard working and the caller asking the
     * wrong question.
     *
     * <p>The scenario is not in the trace's own name, but the bench archives its client log as
     * {@code file-etz-basalt.litematic-<stamp>-<runid>.log} — so the run ids of basalt runs can be read off there and
     * matched against the trace files.
     */
    /**
     * Der Abhaengigkeitsgraph des blockierten Clusters — die Frage des Besitzers vom 02.08.2026.
     *
     * <p>{@code N = 0} aus Iteration 19 sagt nur: kein Nachbar ist JETZT setzbar. Der Besitzer weist darauf hin,
     * dass das zu eng gemessen ist. Kein Kolben dieser Farm haengt im fertigen Bauwerk in der Luft — sie sitzen an
     * Target-Bloecken. Der Kolben schwebt also nicht, er schwebt nur JETZT. Die Frage, die zaehlt, lautet deshalb:
     *
     * <p><b>Existiert eine Reihenfolge, in der dieses Cluster baubar ist?</b>
     *
     * <p>Das ist eine Erreichbarkeitsfrage im Graphen: jede blockierte Zelle zeigt auf ihre Schematic-Nachbarn.
     * Haengt irgendein Element an einem bereits STEHENDEN Block, ist es ein Reihenfolgeproblem — dann dieses
     * Element zuerst und die Kette entlang. Haengt keines, ist es ein echter Deadlock, und dann steht der Graph
     * als Beleg dafuer da.
     *
     * <p>Gerechnet wird als Fixpunkt: eine Zelle gilt als erreichbar, wenn sie jetzt setzbar ist ODER wenn sie es
     * wird, sobald alle bereits als erreichbar erkannten Zellen stehen. Wiederholen, bis nichts mehr dazukommt.
     */
    @Test
    public void derAbhaengigkeitsgraphDesBlockiertenClusters() throws Exception {
        File trace = newestTrace();
        assumeTrue("kein Basalt-Trace vorhanden", trace != null);

        List<TraceReplay.Event> events = TraceReplay.parse(trace);
        List<Map.Entry<BlockPos, Integer>> deferred = TraceReplay.deferredCells(events);
        assumeTrue("Trace ohne DEFER-Ereignisse", !deferred.isEmpty());

        TraceReplay.Session session = TraceReplay.Session.open(trace, TraceReplay.BENCH_ORIGIN);
        // Der Knotensatz sind die blockierten Zellen PLUS alles, was die Schematic um sie herum noch will --
        // zwei Hops weit. Nur die blockierten Zellen zu nehmen beantwortet die Frage nicht: gerade die
        // target-Bloecke, an denen die Kolben haengen sollen, tragen selbst kein DEFER, weil der Builder sie
        // nie versucht hat. Sie sind aber genau die Kandidaten fuer "haengt irgendetwas an etwas Stehendem".
        java.util.LinkedHashSet<BlockPos> nodes = new java.util.LinkedHashSet<>();
        for (Map.Entry<BlockPos, Integer> e : deferred) {
            nodes.add(e.getKey());
        }
        for (int hop = 0; hop < 2; hop++) {
            for (BlockPos cell : new ArrayList<>(nodes)) {
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos side = cell.relative(d);
                    BlockState wants = session.expectedAt(side);
                    if (wants != null && !wants.isAir() && !session.isBuilt(side)) {
                        nodes.add(side);
                    }
                }
            }
        }
        List<BlockPos> blocked = new ArrayList<>(nodes);

        StringBuilder out = new StringBuilder();
        out.append("[graph] trace=").append(trace.getName())
                .append("  Knoten (blockiert + offene Schematic-Nachbarn, 2 Hops)=").append(blocked.size()).append('\n');

        // Runde 0: welche sind ohne Hilfe setzbar?
        java.util.Map<BlockPos, BlockState> reachable = new java.util.LinkedHashMap<>();
        for (BlockPos cell : blocked) {
            if (session.replay(cell, -1).solvable()) {
                reachable.put(cell, session.expectedAt(cell));
            }
        }
        out.append("  Runde 0 (ohne Hilfe setzbar): ").append(reachable.size()).append('\n');

        // Fixpunkt: mit allem, was bisher erreichbar ist, noch einmal fragen.
        int round = 0;
        while (true) {
            round++;
            java.util.Map<BlockPos, BlockState> assume = new java.util.LinkedHashMap<>(reachable);
            int before = reachable.size();
            for (BlockPos cell : blocked) {
                if (reachable.containsKey(cell)) {
                    continue;
                }
                if (session.replay(cell, -1, true, assume).solvable()) {
                    reachable.put(cell, session.expectedAt(cell));
                }
            }
            out.append("  Runde ").append(round).append(": ").append(reachable.size())
                    .append(" von ").append(blocked.size()).append(" erreichbar\n");
            if (reachable.size() == before) {
                break;
            }
        }

        List<BlockPos> stuck = new ArrayList<>();
        for (BlockPos cell : blocked) {
            if (!reachable.containsKey(cell)) {
                stuck.add(cell);
            }
        }
        out.append("[graph] ERGEBNIS: ").append(reachable.size()).append(" von ").append(blocked.size())
                .append(" ueber eine Reihenfolge baubar, ").append(stuck.size()).append(" bleiben\n");
        if (!stuck.isEmpty()) {
            out.append("  bleibende Zellen und was die Schematic um sie herum will:\n");
            for (BlockPos cell : stuck.subList(0, Math.min(6, stuck.size()))) {
                out.append("    ").append(session.replay(cell, -1).text()
                        .replace("\n", "\n      ").stripTrailing()).append('\n');
            }
        }

        File dir = new File("run/replay");
        assertTrue("run/replay nicht anlegbar", dir.isDirectory() || dir.mkdirs());
        java.nio.file.Files.writeString(new File(dir, "last-graph.txt").toPath(), out.toString());
        System.out.print(out);

        assertFalse("der Graph muss Zellen enthalten", blocked.isEmpty());
    }

    /**
     * Die benannte Zelle des Besitzers und ihre KLICKFLAECHE — eine Frage, ueber ein Tick-Fenster.
     *
     * <p>Stand nach Iteration 29: {@code 116,-59,72} (sticky_piston) scheitert 49x an "nothing solid to click", und
     * die Flaeche, gegen die geklickt wuerde, ist {@code 116,-59,73} (redstone_wire). Diese Drahtzelle hat im ganzen
     * 108000-Tick-Lauf NULL Ereignisse — kein DEFER, kein PICK. Ausgeschlossen sind bereits Ausrichtung (0x "wrong
     * block would land"), Arbeitsfenster (A28) und Hotbar (A29). Was offen ist: <b>war sie ueberhaupt setzbar?</b>
     *
     * <p>Ist sie setzbar und wurde trotzdem nie angefasst, liegt der Fehler in der Platzierungssuche, nicht in der
     * Welt — dann sagt das hier, dass die naechste Suche in {@code searchForPlacables} stattzufinden hat und nicht
     * in der Geometrie. Ist sie nicht setzbar, nennt das Orakel den Grund, und der ist die naechste Baustelle.
     *
     * <p><b>Die Falle, um die dieser Test herumbaut:</b> {@code replay(cell, -1)} loest ueber
     * {@link TraceReplay#lastTickFor} auf, und das ist fuer eine ereignislose Zelle {@code -1}. Der Overlay-Filter
     * {@code landedTick > tick} wirft dann JEDE gelandete Zelle weg und die Frage wird gegen eine LEERE Welt
     * gestellt — garantiert "NO STANCE", und zwar als Artefakt. Genau daran war der Abhaengigkeitsgraph aus
     * Iteration 20 kontaminiert. Hier wird der Tick deshalb immer explizit genannt.
     *
     * <p>Berichtet, nicht gepinnt: die Antwort ist ein Befund, kein Vertrag.
     */
    @Test
    public void dieKlickflaecheDerBenanntenZelle() throws Exception {
        File trace = chosenTrace();
        assumeTrue("kein Basalt-Trace vorhanden", trace != null);

        BlockPos target = cellProperty("princeps.replay.cell", new BlockPos(116, -59, 72));
        BlockPos surface = cellProperty("princeps.replay.surface", new BlockPos(116, -59, 73));

        List<TraceReplay.Event> events = TraceReplay.parse(trace);
        int maxTick = 0;
        for (TraceReplay.Event event : events) {
            maxTick = Math.max(maxTick, event.tick());
        }
        assumeTrue("Trace ohne Ereignisse", maxTick > 0);

        TraceReplay.Session session = TraceReplay.Session.open(trace, TraceReplay.BENCH_ORIGIN);
        assumeTrue("Zielzelle liegt nicht in dieser Schematic", session.expectedAt(target) != null);
        assumeTrue("Klickflaeche liegt nicht in dieser Schematic", session.expectedAt(surface) != null);

        StringBuilder out = new StringBuilder();
        out.append("[cell] trace=").append(trace.getName()).append("  maxTick=").append(maxTick).append('\n');
        out.append("[cell] Ziel     ").append(describe(session, target, events)).append('\n');
        out.append("[cell] Flaeche  ").append(describe(session, surface, events)).append('\n');

        // Ein Tick-Fenster, keine Punktmessung: eine Zelle kann frueh unmoeglich und spaet setzbar sein, und genau
        // dieser Unterschied trennt "Reihenfolge" von "unbaubar". Fuenftel des Laufs, damit die Leiter auch auf
        // einem anderen Trace noch etwas bedeutet.
        int[] ladder = {maxTick / 5, 2 * maxTick / 5, 3 * maxTick / 5, 4 * maxTick / 5, maxTick};
        int surfaceSolvableAt = -1;
        out.append("[cell] Klickflaeche ").append(pos(surface)).append(" ueber die Laufzeit:\n");
        for (int tick : ladder) {
            TraceReplay.Report report = session.replay(surface, tick);
            if (report.solvable() && surfaceSolvableAt < 0) {
                surfaceSolvableAt = tick;
            }
            out.append("   t=").append(tick).append("  ").append(report.solvable() ? "SETZBAR " : "NO STANCE")
                    .append("  ").append(report.solve().explain()).append('\n');
            assertNotNull(report.desired());
        }

        // Und die eigentliche Kette: traegt die Flaeche den Kolben, wenn sie steht?
        out.append("[cell] Zielzelle ").append(pos(target)).append(" bei t=").append(maxTick).append(":\n");
        TraceReplay.Report plain = session.replay(target, maxTick);
        out.append("   ohne Flaeche  ").append(plain.solvable() ? "SETZBAR " : "NO STANCE")
                .append("  ").append(plain.solve().explain()).append('\n');
        TraceReplay.Report withSurface = session.replay(target, maxTick, true,
                Map.of(surface, session.expectedAt(surface)));
        out.append("   mit Flaeche   ").append(withSurface.solvable() ? "SETZBAR " : "NO STANCE")
                .append("  ").append(withSurface.solve().explain()).append('\n');

        out.append("[cell] Nachbarschaft der Zielzelle: ").append(plain.neighbourhood()).append('\n');
        out.append("[cell] entsperrt durch: ")
                .append(joinOrNone(session.unblockingNeighbours(target, maxTick), "KEIN EINZELNER NACHBAR"))
                .append('\n');
        out.append("[cell] Geruest wuerde wirken an: ")
                .append(joinOrNone(session.unblockingScaffold(target, maxTick), "NIRGENDS")).append('\n');

        out.append("[cell] BEFUND: Klickflaeche ")
                .append(surfaceSolvableAt >= 0 ? "ab t=" + surfaceSolvableAt + " SETZBAR" : "im ganzen Lauf NIE setzbar")
                .append("; Zielzelle mit stehender Flaeche ")
                .append(withSurface.solvable() ? "SETZBAR" : "weiterhin blockiert").append('\n');

        File dir = new File("run/replay");
        assertTrue("run/replay nicht anlegbar", dir.isDirectory() || dir.mkdirs());
        java.nio.file.Files.writeString(new File(dir, "last-cell.txt").toPath(), out.toString());
        System.out.print(out);

        // Nur das Werkzeug wird gepinnt, nicht sein Urteil: eine Antwort ohne einen einzigen gepruefen Standplatz
        // waere ein kaputtes Instrument, das sich als Befund liest — dieselbe Falle wie die Geruest-Sonde oben.
        assertFalse("das Orakel hat fuer die Zielzelle keinen einzigen Standplatz geprueft",
                plain.candidateStances().isEmpty());
    }

    /** Position, Wunschzustand, ob sie im Lauf stand und wie oft der Trace sie ueberhaupt erwaehnt. */
    private static String describe(TraceReplay.Session session, BlockPos cell, List<TraceReplay.Event> events) {
        int mentions = 0;
        for (TraceReplay.Event event : events) {
            if (event.pos().equals(cell)) {
                mentions++;
            }
        }
        BlockState wanted = session.expectedAt(cell);
        return pos(cell) + "  want=" + (wanted == null ? "-" : wanted.getBlock().getDescriptionId())
                + "  gebaut=" + (session.isBuilt(cell) ? "ja" : "nein")
                + "  Trace-Ereignisse=" + mentions;
    }

    private static String pos(BlockPos cell) {
        return cell.getX() + "," + cell.getY() + "," + cell.getZ();
    }

    private static String joinOrNone(List<String> values, String none) {
        return values.isEmpty() ? none : String.join(", ", values);
    }

    private static BlockPos cellProperty(String key, BlockPos fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String[] xyz = raw.split(",");
        if (xyz.length != 3) {
            throw new IllegalArgumentException(key + " erwartet x,y,z, bekam: " + raw);
        }
        return new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()),
                Integer.parseInt(xyz[2].trim()));
    }

    /** {@link #newestTrace()}, aber per {@code -Dprinceps.replay.trace=<dateiname>} auf einen bestimmten Lauf setzbar. */
    private static File chosenTrace() {
        String named = System.getProperty("princeps.replay.trace");
        if (named != null && !named.isBlank()) {
            File explicit = new File(TRACE_DIR, named.endsWith(".log") ? named : "build-trace-" + named + ".log");
            assertTrue("angeforderter Trace fehlt: " + explicit, explicit.isFile());
            return explicit;
        }
        return newestTrace();
    }

    private static File newestTrace() {
        File[] archived = new File("run/bench-out").listFiles(
                (dir, name) -> name.startsWith("file-etz-basalt.litematic-") && name.endsWith(".log"));
        if (archived == null || archived.length == 0) {
            return null;
        }
        java.util.Set<String> basaltRuns = new java.util.HashSet<>();
        for (File log : archived) {
            String name = log.getName();
            int dot = name.lastIndexOf('.');
            int dash = name.lastIndexOf('-', dot);
            if (dash > 0 && dot > dash) {
                basaltRuns.add(name.substring(dash + 1, dot));
            }
        }
        File[] files = TRACE_DIR.listFiles((dir, name) -> name.startsWith("build-trace-") && name.endsWith(".log"));
        if (files == null) {
            return null;
        }
        // NICHT der neueste, sondern der GROESSTE Basalt-Trace. Der neueste kann ein Lauf sein, der bei 940
        // Zellen gestorben ist -- und dann liegt keine einzige gelandete Zelle oberhalb des Baubodens, die
        // Negativkontrolle hat nichts zu pruefen und die Rekonstruktion ist genau dort unbelegt, wo jede
        // interessante Frage sitzt. Gemessen am 02.08.2026: auf build-trace-b9405fbc lief der Graph ueber
        // 168 Knoten in Ebene -59, waehrend die Kontrolle dort null Belege hatte.
        // Dateigroesse als Mass: ein Lauf, der weiter kam, hat mehr Ticks und damit mehr Zeilen.
        // Der GROESSTE Basalt-Trace, der auch DEFER-Ereignisse traegt.
        //
        // Zwei Fallen stecken hier, beide am 02.08.2026 hineingelaufen. Die erste: der NEUESTE Trace kann ein Lauf
        // sein, der bei 940 Zellen starb -- dann liegt keine gelandete Zelle oberhalb des Baubodens, die
        // Negativkontrolle hat nichts zu pruefen, und die Rekonstruktion ist genau dort unbelegt, wo jede
        // interessante Frage sitzt. Die zweite: die groessten Traces ueberhaupt sind V3-Laeufe (46 MB), und die
        // kennen die Ereignisart DEFER gar nicht -- der Pruefstand uebersprang daraufhin stillschweigend alles.
        List<File> candidates = new ArrayList<>();
        for (File file : files) {
            String id = file.getName().substring("build-trace-".length(), file.getName().length() - ".log".length());
            if (basaltRuns.contains(id)) {
                candidates.add(file);
            }
        }
        candidates.sort((a, b) -> Long.compare(b.length(), a.length()));
        for (File candidate : candidates) {
            try {
                if (hasDeferEvents(candidate)) {
                    return candidate;
                }
            } catch (java.io.IOException ignored) {
                // unlesbar: naechster Kandidat
            }
        }
        return null;
    }

    private static boolean hasDeferEvents(File trace) throws java.io.IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(trace))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("E ") && line.contains(" DEFER ")) {
                    return true;
                }
            }
        }
        return false;
    }
}
