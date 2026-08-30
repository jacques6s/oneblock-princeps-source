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
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import princeps.api.schematic.ISchematic;
import princeps.process.builder.bench.BenchSchematics;
import princeps.process.builder.v3.BasaltDryRun;
import princeps.process.builder.v3.HotbarSchedule;
import princeps.process.builder.v3.PlacementOracle;
import princeps.process.builder.v3.PredictedWorld;
import princeps.process.builder.v3.SchematicView;
import princeps.process.builder.v3.SolveBudget;
import princeps.process.builder.v3.V3Settings;
import princeps.process.builder.v3.PlayerPose;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replays one failed cell out of a build trace, with no client, no server and no world.
 *
 * <h2>Why</h2>
 *
 * <p>Answering "why could this cell not be placed" costs a four-minute bench run today, and the answer arrives as one
 * suppressed line in a 16 MB log. The V1 baseline (02.08.2026) ends in three of three runs on the same wall — twenty
 * distinct {@code sticky_piston} cells, all at y=-59, all reported as {@code "no standable, occlusion-safe stance can
 * currently place sticky_piston"} — and iteration 13 showed the bot visiting 258 stances without finding one. That is
 * a question about geometry, and geometry does not need a running game to answer.
 *
 * <h2>What this DOES answer</h2>
 *
 * <p>Given the world as it stood at tick T and the cell V1 gave up on: is there ANY standable, occlusion-safe stance
 * in the search window from which a click places the desired block, and if not, what refused every candidate. It uses
 * {@link PlacementOracle} — V3's headless stance/aim/ray search, already exercised by the existing test suite — so
 * the answer is the same kind of answer V3's planner acts on.
 *
 * <h2>What this does NOT answer</h2>
 *
 * <p>It is <b>not</b> a re-execution of {@code BuilderProcess.possibleToPlace}. That method reads {@code ctx.player()},
 * {@code ctx.world()} and the live aim processor and cannot run headless at all. So a cell this harness calls solvable
 * may still be one V1 fails on for a reason of its own, and the difference between the two answers is itself a finding
 * rather than a defect in the harness. What it rules out is the opposite and more useful direction: a cell no stance
 * can serve is not going to be fixed in the executor.
 *
 * <h2>The world it reconstructs</h2>
 *
 * <p>Base terrain follows {@link BasaltDryRun#world}: stone below the build floor, air above — the same assumption the
 * dry run has always made. On top of it go every cell the trace reports as landed by tick T, in the state the schematic
 * asks for. A cell the trace never reports as landed is air here, whatever happened in the real world.
 *
 * <p>Two signals count as landed, and they are not equally strong. {@code LAND ... match=yes} is exact — the executor
 * saw the desired block. {@code DONE} is the weaker one: it fires when the builder considers the cell satisfied
 * ({@code BuilderProcess:3728}), which includes a block whose {@code LAND} said {@code match=no} because only a toggle
 * (delay, open, mode, note) still differs and the interaction pass will fix it. So a {@code DONE}-only cell is entered
 * in the state the SCHEMATIC asks for, not the state it was in at tick T. For geometry — is there something solid
 * here to stand on and click against — the difference does not matter. It would matter for a question about that
 * block's own properties, and this harness does not ask one. Measured on {@code e278902a}: 935 of 940 overlay cells
 * carry {@code match=yes}; the five that do not are repeaters.
 */
public final class TraceReplay {

    /**
     * Where the bench puts this schematic. Taken from a V3 trace's own BUILD line
     * ({@code E 0 BUILD 67,-60,67 ... dims=63x18x63}), because V1 traces carry no such line.
     *
     * <p>Not trusted blindly: {@link #replay} checks that every cell the trace reports as landed actually falls inside
     * the schematic, and fails loudly otherwise. A wrong origin would otherwise produce a confident answer about the
     * wrong cells.
     */
    public static final BlockPos BENCH_ORIGIN = new BlockPos(67, -60, 67);

    private TraceReplay() {
    }

    /** One {@code E <tick> <KIND> x,y,z <detail>} line. */
    public record Event(int tick, String kind, BlockPos pos, String detail) {
    }

    public record Report(
            BlockPos cell,
            BlockState desired,
            int atTick,
            int landedByThen,
            List<BlockPos> candidateStances,
            PlacementOracle.Solve solve,
            /** The six neighbours as the reconstruction sees them, and what the schematic wants there. */
            String neighbourhood
    ) {
        public boolean solvable() {
            return this.solve.solved();
        }

        public String text() {
            StringBuilder out = new StringBuilder();
            out.append("cell ").append(this.cell.getX()).append(',').append(this.cell.getY()).append(',')
                    .append(this.cell.getZ())
                    .append("  want=").append(this.desired.getBlock().getDescriptionId())
                    .append("  at tick ").append(this.atTick)
                    .append("  (").append(this.landedByThen).append(" cells landed by then)\n");
            out.append("  candidate stances in window: ").append(this.candidateStances.size()).append('\n');
            out.append("  neighbours: ").append(this.neighbourhood).append('\n');
            out.append("  oracle: ").append(this.solve.explain()).append('\n');
            out.append("  verdict: ").append(this.solvable() ? "SOLVABLE" : "NO STANCE").append('\n');
            this.solve.best().ifPresent(best -> out.append("  best: ").append(best).append('\n'));
            return out.toString();
        }
    }

    public static List<Event> parse(File trace) throws IOException {
        List<Event> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(trace))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 2 || line.charAt(0) != 'E' || line.charAt(1) != ' ') {
                    continue;   // T (per-tick) and P (route) lines are not cell events
                }
                String[] parts = line.split(" ", 5);
                if (parts.length < 4) {
                    continue;
                }
                BlockPos pos = parsePos(parts[3]);
                if (pos == null) {
                    continue;
                }
                int tick;
                try {
                    tick = Integer.parseInt(parts[1]);
                } catch (NumberFormatException bad) {
                    continue;
                }
                events.add(new Event(tick, parts[2], pos, parts.length >= 5 ? parts[4] : ""));
            }
        }
        return events;
    }

    private static BlockPos parsePos(String field) {
        String[] xyz = field.split(",");
        if (xyz.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /**
     * Every cell the trace reports as correctly landed, mapped to the tick it landed on.
     *
     * <p>{@code LAND ... match=yes} is the strong signal and {@code DONE} the weaker one — the executor emits DONE
     * after observing the cell satisfied, which is the same claim without the block name. Both are taken; the earliest
     * tick wins, because a cell can be re-observed later without having moved.
     */
    /**
     * The block that landed, for cells the schematic has no opinion about.
     *
     * <p>Click anchors (since iteration 17) are placed OUTSIDE the schematic on purpose, so
     * {@code view.desired()} has nothing to say about them and the overlay would leave them out — which would make
     * the reconstruction describe a world without the very blocks the builder put there to click against. The
     * {@code LAND} line carries the name, so it is read from there.
     */
    public static Map<BlockPos, BlockState> landedStates(List<Event> events) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (Event event : events) {
            if (!"LAND".equals(event.kind)) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("want=([a-z_]+)").matcher(event.detail);
            if (!m.find()) {
                continue;
            }
            BlockState state = byPath(m.group(1));
            if (state != null) {
                states.putIfAbsent(event.pos, state);
            }
        }
        return states;
    }

    /**
     * A block by its registry path, e.g. {@code "cobblestone"}.
     *
     * <p>Built by walking the registry rather than through {@code ResourceLocation}: nothing else in this repository
     * imports that class, and a test harness is the wrong place to be the first. One pass over ~1100 blocks, cached.
     */
    private static Map<String, BlockState> byPath;

    private static synchronized BlockState byPath(String path) {
        if (byPath == null) {
            byPath = new LinkedHashMap<>();
            for (net.minecraft.world.level.block.Block block
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                byPath.putIfAbsent(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath(),
                        block.defaultBlockState());
            }
        }
        BlockState state = byPath.get(path);
        return state == null || state.isAir() ? null : state;
    }

    public static Map<BlockPos, Integer> landedByTick(List<Event> events) {
        Map<BlockPos, Integer> landed = new LinkedHashMap<>();
        for (Event event : events) {
            boolean good = ("LAND".equals(event.kind) && event.detail.contains("match=yes"))
                    || "DONE".equals(event.kind);
            if (good) {
                landed.merge(event.pos, event.tick, Math::min);
            }
        }
        return landed;
    }

    /** The last tick at which the trace still says something about this cell — where the giving-up happened. */
    public static int lastTickFor(List<Event> events, BlockPos cell) {
        int last = -1;
        for (Event event : events) {
            if (event.pos.equals(cell)) {
                last = Math.max(last, event.tick);
            }
        }
        return last;
    }

    /** Cells the trace reports a DEFER on, most-deferred first — the harness's own work list. */
    public static List<Map.Entry<BlockPos, Integer>> deferredCells(List<Event> events) {
        Map<BlockPos, Integer> counts = new LinkedHashMap<>();
        for (Event event : events) {
            if ("DEFER".equals(event.kind)) {
                counts.merge(event.pos, 1, Integer::sum);
            }
        }
        List<Map.Entry<BlockPos, Integer>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return ordered;
    }

    private static String shortName(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** {@link BasaltDryRun#view}, but anchored where the trace says the build is rather than at the dry run's origin. */
    private static SchematicView view(ISchematic schematic, PredictedWorld world,
                                      HotbarSchedule.InventorySnapshot inventory, Vec3i origin) {
        return SchematicView.capture("etz-basalt", schematic, origin, world,
                BasaltDryRun.ledgerPlaceable(inventory), V3Settings.defaults().waterlogging());
    }

    public static Report replay(File trace, BlockPos cell) throws IOException {
        return Session.open(trace, BENCH_ORIGIN).replay(cell, -1);
    }

    /**
     * The expensive half, paid once: Bootstrap, the litematic, the inventory and the view.
     *
     * <p>The first version of this harness rebuilt all of it per cell and took 6.6 s to answer ONE question — a tool
     * that was supposed to turn two experiments an hour into two hundred. Everything here is independent of which
     * cell is asked about and of the tick; only the world is not, and that is rebuilt per query.
     */
    public static final class Session {

        private final List<Event> events;
        private final Map<BlockPos, Integer> landed;
        /** What landed where, for the cells the schematic does not speak about -- click anchors above all. */
        private final Map<BlockPos, BlockState> landedStates;
        private final SchematicView view;
        private final HotbarSchedule.InventorySnapshot inventory;
        /**
         * The block the bot would actually use as a helper — {@code BasaltDryRun.throwaway}, here dirt.
         *
         * <p>NOT an arbitrary full cube. The first version of the scaffold self-check probed with
         * {@code Blocks.STONE}, which sits in none of the 36 inventory slots, so {@code PlacementOracle} refused
         * every candidate at {@code NO_ITEM} before asking a single geometry question — and the resulting
         * "every candidate floats" read like a finding while it was a constant. Found by the critic.
         */
        private final BlockState helper;
        private final PlacementOracle oracle;
        private final SolveBudget budget;
        private final Vec3i min;
        private final Vec3i max;
        private final BlockState floor;
        private final BlockState air;
        private final int floorY;

        private Session(List<Event> events, Map<BlockPos, Integer> landed,
                        Map<BlockPos, BlockState> landedStates, SchematicView view,
                        HotbarSchedule.InventorySnapshot inventory, Vec3i min, Vec3i max, BlockState floor,
                        BlockState air, int floorY, BlockState helper) {
            this.events = events;
            this.landed = landed;
            this.landedStates = landedStates;
            this.view = view;
            this.inventory = inventory;
            this.helper = helper;
            this.min = min;
            this.max = max;
            this.floor = floor;
            this.air = air;
            this.floorY = floorY;
            this.oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
            // exhaustive() schaltet nur den Schnellpfad ab, NICHT die Stancegrenze. Gemessen am 02.08.2026: drei
            // Kontrollzellen kamen mit "STANCE cap hit: 25 further candidate stance(s) never evaluated" zurueck --
            // der Pruefstand hatte also nicht genug hingesehen, und "kein Standplatz" war seine eigene Schranke.
            // Fuer eine Diagnose ohne Wanduhr ist ein Suchbudget das falsche Instrument.
            this.budget = new SolveBudget(SolveBudget.MAX_STANCES * 16, SolveBudget.MAX_RAYS * 16,
                    SolveBudget.MIN_MARGIN, PlayerPose.PLANNING_REACH, SolveBudget.APPROACH_STEP,
                    SolveBudget.APPROACH_INSET, SolveBudget.APPROACH_REFINE_TOP_N, false);
        }

        public static Session open(File trace, BlockPos origin) throws IOException {
            BasaltDryRun.bootstrap();
            List<Event> events = parse(trace);

            ISchematic schematic = BasaltDryRun.load(BasaltDryRun.schematicFile());
            Vec3i min = new Vec3i(origin.getX(), origin.getY(), origin.getZ());
            Vec3i max = new Vec3i(
                    origin.getX() + Math.max(0, schematic.widthX() - 1),
                    origin.getY() + Math.max(0, schematic.heightY() - 1),
                    origin.getZ() + Math.max(0, schematic.lengthZ() - 1));

            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState floor = BasaltDryRun.floor();
            int floorY = origin.getY();
            PredictedWorld untouched = PredictedWorld.capture(
                    (x, y, z) -> y < floorY ? floor : air, (x, z) -> true, min, max, PredictedWorld.DEFAULT_MARGIN);

            // NOT BasaltDryRun.view: that one anchors the schematic at its own ORIGIN (0,-60,0), while a live trace's
            // coordinates are wherever the bench put the build. Mixing the two put every landed cell outside the
            // schematic, which is what the origin check below caught on the first run of this harness.
            List<Item> materials = BasaltDryRun.materialsOf(
                    view(schematic, untouched, BasaltDryRun.inventory(List.of(), null), min));
            Optional<Item> throwaway = BasaltDryRun.throwaway(
                    view(schematic, untouched, BasaltDryRun.inventory(materials, null), min));
            // Whatever the trace shows landing has to be IN the probe inventory, or the oracle refuses it at NO_ITEM
            // and the answer reads like geometry. Found by the positive control: a cobblestone the builder really
            // placed came back unsolvable with "of 0 candidate stances: 1 no item" -- the harness had assumed the
            // schematic's own throwaway (dirt) and nothing else outside the material list.
            List<Item> withLanded = new ArrayList<>(materials);
            for (BlockState landedState : landedStates(events).values()) {
                Item item = landedState.getBlock().asItem();
                if (item != net.minecraft.world.item.Items.AIR && !withLanded.contains(item)) {
                    withLanded.add(item);
                }
            }
            HotbarSchedule.InventorySnapshot inventory =
                    BasaltDryRun.inventory(withLanded, throwaway.orElse(null));
            SchematicView view = view(schematic, untouched, inventory, min);

            Map<BlockPos, Integer> landed = landedByTick(events);
            Map<BlockPos, BlockState> landedStates = landedStates(events);
            int outside = 0;
            for (BlockPos pos : landed.keySet()) {
                // A cell the schematic does not speak about is only evidence of a wrong origin if it is also OUTSIDE
                // the volume. Click anchors sit inside it on purpose, and counting them here made the guard fire on a
                // perfectly good trace as soon as the builder started using them.
                boolean inVolume = pos.getX() >= min.getX() && pos.getY() >= min.getY() && pos.getZ() >= min.getZ()
                        && pos.getX() <= max.getX() && pos.getY() <= max.getY() && pos.getZ() <= max.getZ();
                if (view.desired(pos) == null && !inVolume) {
                    outside++;
                }
            }
            if (!landed.isEmpty() && outside > landed.size() / 4) {
                // A wrong origin puts most landed cells outside the schematic, and every report would then describe a
                // different building than the one the trace is about. Caught on this harness's very first run.
                throw new IllegalStateException("origin " + origin + " looks wrong: " + outside + " of "
                        + landed.size() + " landed cells fall outside the schematic");
            }
            BlockState helper = throwaway.map(item -> item instanceof net.minecraft.world.item.BlockItem b
                    ? b.getBlock().defaultBlockState() : null).orElse(null);
            if (helper == null) {
                throw new IllegalStateException("no throwaway block for this schematic -- the scaffold question "
                        + "cannot be asked with a block the bot does not carry");
            }
            return new Session(events, landed, landedStates(events), view, inventory, min, max, floor, air,
                    floorY, helper);
        }

        public List<Event> events() {
            return this.events;
        }

        public Map<BlockPos, Integer> landed() {
            return this.landed;
        }

        /**
         * What a landed cell was supposed to become: the schematic's wish, or — for a cell the schematic has no
         * opinion about — the block the trace says actually landed there.
         *
         * <p>The second case exists for click anchors and blocks like them. Skipping such cells in the positive
         * control was tried and rejected in review: it takes cells OUT of the control instead of asking the question,
         * and the question is perfectly askable once the expected block is known. So it is asked.
         */
        /** Whether the trace already reports this cell as built. */
        public boolean isBuilt(BlockPos cell) {
            return this.landed.containsKey(cell);
        }

        public BlockState expectedAt(BlockPos cell) {
            BlockState wanted = this.view.desired(cell);
            return wanted != null ? wanted : this.landedStates.get(cell);
        }

        /**
         * Which single neighbour, if it were already there, would make this cell placeable.
         *
         * <p>The question the report raises and cannot answer on its own. A cell whose six neighbours are all air
         * cannot be clicked against from anywhere, and that is a statement about the ORDER the cells are built in,
         * not about aiming. So: for each neighbour the schematic wants filled, put it there and ask again. Every
         * neighbour that flips the answer to solvable is a cell that should have come first.
         *
         * <p>Costs one solve per neighbour — a few dozen milliseconds for the whole question. The same question in
         * the bench costs a run.
         *
         * @return the neighbours that would unblock the cell, each with the block the schematic wants there
         */
        public List<String> unblockingNeighbours(BlockPos cell, int atTick) {
            List<String> unblocking = new ArrayList<>();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos side = cell.relative(dir);
                BlockState wanted = this.view.desired(side);
                if (wanted == null || wanted.isAir()) {
                    continue;   // the schematic wants nothing there; it is never going to become support
                }
                Report withIt = this.replay(cell, atTick, true, Map.of(side, wanted));
                if (withIt.solvable()) {
                    // And is the unblocker itself buildable right now? Without this the finding is only "these two
                    // cells are in the wrong order" -- with it, it is either "so build that one first" or "the stall
                    // just moves one cell along", and those call for opposite work. Asked at the SAME tick, so the
                    // answer is about the same world the blocked cell was refused in.
                    boolean itself = this.replay(side, atTick >= 0 ? atTick : lastTickFor(this.events, cell), true)
                            .solvable();
                    unblocking.add(dir.getName() + "=" + shortName(wanted)
                            + (itself ? "[buildable now]" : "[ITSELF BLOCKED]"));
                }
            }
            return unblocking;
        }

        /**
         * Would a single helper block — one the schematic does NOT ask for — unblock this cell?
         *
         * <p>The question the neighbour scan cannot reach. {@link #unblockingNeighbours} only tries what the schematic
         * wants there, and when every one of those is itself blocked (measured: 11 of 11), the schematic cannot supply
         * the support at all. A builder's other way out is to put a throwaway block somewhere and click against that.
         * V3 plans 137 of them; V1 knows the concept only well enough to CLEAN UP after the pathfinder
         * ({@code isScaffoldLeftBehind}) and never places one as a click anchor.
         *
         * <p>So: for each of the six sides the schematic leaves empty, put a plain full cube there and ask again.
         * A side that flips the answer is a scaffold position that would work.
         *
         * @return the sides where a helper block would unblock the cell
         */
        public List<String> unblockingScaffold(BlockPos cell, int atTick) {
            List<String> works = new ArrayList<>();
            BlockState helper = this.helper;
            int tick = atTick >= 0 ? atTick : lastTickFor(this.events, cell);
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos side = cell.relative(dir);
                // OUTSIDE the volume is the exclusion, not "the schematic has nothing to say here". Those two look
                // identical through view.desired() -- both give null -- and conflating them threw away every `down`
                // candidate in one go: the cell under a y=-59 piston is inside the volume and empty, which is exactly
                // where scaffolding belongs. Measured the moment it happened: all twenty flipped to NOWHERE.
                // Below the floor the probe would be a no-op on base stone and would read as a success, so the bounds
                // check has to be its own test.
                if (side.getX() < this.min.getX() || side.getY() < this.min.getY() || side.getZ() < this.min.getZ()
                        || side.getX() > this.max.getX() || side.getY() > this.max.getY()
                        || side.getZ() > this.max.getZ()) {
                    continue;
                }
                BlockState wanted = this.view.desired(side);
                if (wanted != null && !wanted.isAir()) {
                    continue;   // the schematic speaks about this cell; a helper block there is not scaffolding
                }
                if (!this.replay(cell, tick, true, Map.of(side, helper)).solvable()) {
                    continue;
                }
                // AND: is the helper block itself placeable right now? Without this the answer is worth exactly as
                // much as "east=target would unblock it" was before the same check killed all eleven of those --
                // a scaffold stone that floats is not scaffolding, it is the same question one cell along.
                // Found by the critic in the closing review of iteration 16, after a first version that asked it
                // of schematic neighbours and not of scaffold.
                Report own = this.replay(side, tick, true, Map.of(), helper);
                // The reason comes along. A self-solve that refuses every candidate WITHOUT evaluating a stance is a
                // broken tool, not a finding -- that is exactly how the stone-probe version failed, and printing the
                // oracle's own words makes it impossible to read NO_ITEM as geometry a second time.
                works.add(dir.getName() + (own.solvable() ? "" : "[FLOATS: " + own.solve().explain() + "]"));
            }
            return works;
        }

        /** @param atTick the world as of this tick, or -1 for "the last tick this cell was mentioned" */
        public Report replay(BlockPos cell, int atTick) {
            return this.replay(cell, atTick, true);
        }

        /**
         * @param overlay whether the cells the trace reports as landed are put into the world at all
         *
         * <p>The {@code false} case is the negative control, and it exists because the positive one alone cannot
         * carry the result. Every cell on the build floor has its support from the BASE assumption — stone below
         * {@code floorY} — so a calibration drawn only from that layer comes back solvable whether the overlay
         * contributes anything or not. The cells this harness is actually asked about sit one layer higher, where
         * there is nothing but air without the overlay. So the overlay has to be shown to do work: a cell that is
         * solvable with it and unsolvable without it proves the reconstruction is load-bearing exactly where the
         * verdicts fall. Found by the critic in the closing review of iteration 14, after a first version whose
         * twenty control cells all sat on layer -60.
         */
        public Report replay(BlockPos cell, int atTick, boolean overlay) {
            return this.replay(cell, atTick, overlay, Map.of());
        }

        /** @param alsoPlaced cells to treat as already built on top of the overlay — the "what if" half */
        public Report replay(BlockPos cell, int atTick, boolean overlay, Map<BlockPos, BlockState> alsoPlaced) {
            return this.replay(cell, atTick, overlay, alsoPlaced, null);
        }

        /**
         * @param want the block to ask about, or null for "whatever the schematic wants here"
         *
         * <p>Needed for scaffolding: the schematic wants nothing at a scaffold position, so there is no desired state
         * to look up, and the question "could a helper block be PUT there" cannot be asked without naming the block.
         */
        public Report replay(BlockPos cell, int atTick, boolean overlay, Map<BlockPos, BlockState> alsoPlaced,
                             BlockState want) {
            int tick = atTick >= 0 ? atTick : lastTickFor(this.events, cell);

            Map<Long, BlockState> placed = new HashMap<>();
            for (Map.Entry<BlockPos, BlockState> extra : alsoPlaced.entrySet()) {
                placed.put(extra.getKey().asLong(), extra.getValue());
            }
            for (Map.Entry<BlockPos, Integer> entry : (overlay ? this.landed : Map.<BlockPos, Integer>of()).entrySet()) {
                if (entry.getValue() > tick) {
                    continue;
                }
                BlockState desired = this.view.desired(entry.getKey());
                if (desired == null) {
                    desired = this.landedStates.get(entry.getKey());   // a click anchor, or other non-schematic block
                }
                if (desired != null) {
                    placed.put(entry.getKey().asLong(), desired);
                }
            }
            PredictedWorld world = PredictedWorld.capture(
                    (x, y, z) -> {
                        BlockState set = placed.get(BlockPos.asLong(x, y, z));
                        if (set != null) {
                            return set;
                        }
                        return y < this.floorY ? this.floor : this.air;
                    },
                    (x, z) -> true, this.min, this.max, PredictedWorld.DEFAULT_MARGIN);

            BlockState desired = want != null ? want : this.view.desired(cell);
            if (desired == null) {
                throw new IllegalStateException("cell " + cell + " is not in the schematic");
            }
            List<BlockPos> candidates = this.oracle.candidateStances(world, cell, desired, this.budget);
            PlacementOracle.Solve solve =
                    this.oracle.solve(world, cell, desired, this.inventory.slots(), this.budget);

            // "0 would work" names the wall but not the brick. The six neighbours do: a cell with nothing solid
            // beside it cannot be clicked against from ANY stance, and that is a statement about the build ORDER,
            // not about aiming. Both what stands there now and what the schematic wants there, because the
            // difference between the two is exactly what the order has not yet delivered.
            StringBuilder around = new StringBuilder();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos side = cell.relative(dir);
                BlockState now = world.get(side);
                BlockState wanted = this.view.desired(side);
                if (around.length() > 0) {
                    around.append(", ");
                }
                around.append(dir.getName()).append('=').append(shortName(now));
                if (wanted != null && !wanted.equals(now)) {
                    around.append("(wants ").append(shortName(wanted)).append(')');
                }
            }
            return new Report(cell, desired, tick, placed.size(), candidates, solve, around.toString());
        }
    }
}
