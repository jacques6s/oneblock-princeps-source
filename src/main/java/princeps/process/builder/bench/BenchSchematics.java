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

package princeps.process.builder.bench;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import princeps.api.schematic.ISchematic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic schematics for the builder test bench — defined in code, so a scenario needs no {@code .litematic} file
 * and can encode the EXACT geometry a live failure was observed on.
 *
 * <p>Each scenario restricts {@link ISchematic#inSchematic} to precisely the target cells, so every other cell in the
 * bounding box is reported as "not in schematic" and the builder ignores it (see BuilderProcess.getSchematic). That
 * keeps a scenario's footprint exactly equal to the blocks it wants placed — the bench can then verify completion by
 * comparing that cell set against the world, independently of the builder's own bookkeeping.
 */
public final class BenchSchematics {

    private BenchSchematics() {
    }

    /** A scenario: a named cell->state map, materialised as an ISchematic over its own bounding box. */
    public static final class Scenario implements ISchematic {
        public final String name;
        /** Local cell (packed x,y,z relative to origin) -> desired state. Iteration order is stable for reporting. */
        public final Map<Long, BlockState> cells;
        private final int width;
        private final int height;
        private final int length;

        Scenario(String name, Map<Long, BlockState> cells, int width, int height, int length) {
            this.name = name;
            this.cells = cells;
            this.width = width;
            this.height = height;
            this.length = length;
        }

        static long key(int x, int y, int z) {
            return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (long) (z & 0xFFFF);
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            return cells.containsKey(key(x, y, z));
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            return cells.get(key(x, y, z));
        }

        @Override
        public int widthX() {
            return width;
        }

        @Override
        public int heightY() {
            return height;
        }

        @Override
        public int lengthZ() {
            return length;
        }

        public int cellCount() {
            return cells.size();
        }
    }

    /**
     * The live failure geometry: a one-wide hollow perimeter ring, {@code height} blocks tall. Building this while
     * standing inside walls the bot in — exactly the self-box that stalled layer y=112 in the 0.3.79 runs.
     */
    public static Scenario ring(int size, int height) {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        BlockState block = Blocks.BLACK_STAINED_GLASS.defaultBlockState(); // the block the live stall was seen on
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    if (x == 0 || x == size - 1 || z == 0 || z == size - 1) {
                        cells.put(Scenario.key(x, y, z), block);
                    }
                }
            }
        }
        return new Scenario("ring" + size + "x" + height, cells, size, height, size);
    }

    /**
     * Ringbig resumed after its lower two layers were already completed.
     *
     * <p>The schematic remains the complete 120-cell ring; only the bench world is pre-filled. That distinction makes
     * the final server audit prove both that every saved cell survived and that the remaining top layer was built.
     * {@link BuilderBench#setupCommands} supplies the saved world and deliberately starts the body inside it.
     */
    public static Scenario ringResume() {
        Scenario base = ring(11, 3);
        return new Scenario("ringresume", new LinkedHashMap<>(base.cells),
                base.widthX(), base.heightY(), base.lengthZ());
    }

    /** A straight wall — the simplest "walk along and place" throughput case. */
    public static Scenario wall(int length, int height) {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        BlockState block = Blocks.STONE.defaultBlockState();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < length; x++) {
                cells.put(Scenario.key(x, y, 0), block);
            }
        }
        return new Scenario("wall" + length + "x" + height, cells, length, height, 1);
    }

    /** A solid floor slab — pure placement throughput with no enclosure risk (baseline for blocks/min). */
    /**
     * A solid box that has to be taken away again -- the DIG case, and the one the bench never covered.
     *
     * <p>Every cell wants air. That is not a trick: {@code BuilderProcess.clearArea} is literally a build of a
     * FillSchematic full of air, so a scenario shaped this way runs the exact code path the client's AutoDig
     * runs, and every existing verdict, census and stall check applies to it unchanged.
     *
     * <p>Worth having because clearing fails differently from building. A build always has a face to click
     * against -- the block it just placed; a clear removes its own footholds as it goes, and the last cell of a
     * layer can leave the bot standing in a hole with nothing in reach. Nothing tested that until now.
     */
    public static Scenario dig(int width, int height, int length) {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < length; z++) {
                    cells.put(Scenario.key(x, y, z), air);
                }
            }
        }
        return new Scenario("dig" + width + "x" + height + "x" + length, cells, width, height, length);
    }

    public static Scenario floor(int size) {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        BlockState block = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                cells.put(Scenario.key(x, 0, z), block);
            }
        }
        return new Scenario("floor" + size, cells, size, 1, size);
    }

    /**
     * Orientation-sensitive blocks in one row: slabs (both halves), stairs (all 4 facings) and a door. These are the
     * placements that need a specific stance + hit point, so this scenario is the regression test for
     * "first-try correct orientation".
     */
    public static Scenario oriented() {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        int x = 0;
        // Bottom and top slabs.
        cells.put(Scenario.key(x++, 0, 0), Blocks.STONE_SLAB.defaultBlockState());
        cells.put(Scenario.key(x++, 0, 0), Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.TOP));
        // Stairs facing each cardinal direction.
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            cells.put(Scenario.key(x++, 0, 0), Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.StairBlock.FACING, dir));
        }
        // A door (lower half only — the upper half fills itself).
        cells.put(Scenario.key(x++, 0, 0), Blocks.OAK_DOOR.defaultBlockState());
        return new Scenario("oriented", cells, x, 2, 1);
    }

    /**
     * Every door there is: four facings, each with the hinge on the left and on the right.
     *
     * <p>Eight doors, and the point is that they are the WHOLE matrix rather than a sample. A door carries two
     * separately derived quantities and they come from different places: {@code facing} decides which of the block's
     * four upper edges the door stands on and comes from the look direction, while {@code hinge} decides which side
     * it swings from and comes from the HALF of the clicked face that was hit. A scenario that exercises one facing
     * with one hinge proves nothing about the other seven, and this project has twice been convinced by a sample that
     * looked right — {@code requiredLookDirections} was narrowed on the strength of one example and took every
     * clickable face away from 624 of 672 sticky pistons.
     *
     * <p>Three cells of spacing between doors, and that is load-bearing rather than tidy: vanilla consults
     * NEIGHBOURING doors before it looks at the hit position, so two doors within one block of each other would have
     * their hinges decided by each other and the hit-half rule would never be exercised at all. The neighbour case is
     * real and worth its own scenario; mixing it in here would silently replace the test with a different one.
     *
     * <p>Lower halves only — vanilla fills the upper half itself, and the bench grades what the schematic declares.
     */
    public static Scenario doorMatrix() {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        for (int row = 0; row < 3; row++) {
            int slot = 0;
            for (net.minecraft.core.Direction facing : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (net.minecraft.world.level.block.state.properties.DoorHingeSide hinge
                        : net.minecraft.world.level.block.state.properties.DoorHingeSide.values()) {
                    // IRON, not oak, and that is the test speaking rather than taste: a wooden door can be
                    // opened, and a run that opens one of its own doors reports the cell as wrong for a reason
                    // that has nothing to do with placing it. An iron door cannot be opened by hand at all, so
                    // the confound is removed by physics instead of by a rule somebody has to remember. Facing
                    // and hinge derive identically, so this tests exactly what the oak version tested.
                    cells.put(Scenario.key(slot * 2, 0, row * 5), Blocks.IRON_DOOR.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing)
                            .setValue(net.minecraft.world.level.block.DoorBlock.HINGE, hinge));
                    slot++;
                }
            }
        }
        return new Scenario("doors", cells, 8 * 2, 2, 2 * 5 + 1);
    }

    /**
     * The other half of the door matrix: pairs that touch, where the NEIGHBOUR decides the hinge.
     *
     * <p>{@code doors} deliberately spaces its eight apart so the hit-half rule is what gets exercised. That leaves
     * vanilla's first stage untested, and it is the stage with precedence: an adjacent lower door half overrides the
     * hit outright, which is what makes a double door mirror instead of both halves hinging the same way. A build
     * that got this backwards would place two doors that open into each other and audit as wrong.
     *
     * <p>The FIRST version of this scenario asked for hinges in the MIDDLE and the planner refused every
     * stance for the first door of each pair, naming the cell that would unblock it. It was right: vanilla
     * forces a door flanked on its right to hinge LEFT, so hinges-in-the-middle is a state the neighbour
     * rule actively opposes and no aim can produce. The schematic was wrong, not the engine — the same
     * defect class as the four chests in etz-basalt that face each other and both claim left.
     *
     * <p>Four facings, and for each a pair whose two halves must come out mirrored — eight doors, four pairs, each
     * pair adjacent along the axis across its own doorway. Six cells between pairs so one pair cannot reach the next.
     */
    public static Scenario doorPairs() {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        int slot = 0;
        for (net.minecraft.core.Direction facing : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            // The pair must sit side by side ACROSS the doorway, so it is laid out along the placer's left-right
            // axis and not always along X. Laid out along X regardless, an east- or west-facing pair ends up in
            // front of and behind itself, the neighbour rule never fires, and the scenario silently tests the
            // hit-half rule a second time instead of the thing it was written for.
            net.minecraft.core.Direction left = facing.getCounterClockWise();
            // Each door is flanked by the other, and vanilla gives a door flanked on its LEFT the RIGHT hinge. So
            // the base door, whose partner lies to its left, takes RIGHT; the partner takes LEFT. Derived from the
            // facing rather than written out, because "left" is west for a north-facing door and east for a
            // south-facing one — a hardcoded pair is correct for exactly half the compass.
            // Shifted by one on both axes so the partner can never land on a NEGATIVE local coordinate:
            // Scenario.key packs with (x & 0xFFFF), so a -1 wraps to 65535 and the cell silently becomes a
            // coordinate nobody asked for. The bench then reports an unsatisfied cell at x=65602 and audits
            // a manifest two rows short, which reads as a builder failure and is a packing overflow.
            int baseX = slot * 6 + 1;
            int baseZ = 1;
            int partnerX = baseX + left.getStepX();
            int partnerZ = baseZ + left.getStepZ();
            cells.put(Scenario.key(baseX, 0, baseZ), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing)
                    .setValue(net.minecraft.world.level.block.DoorBlock.HINGE,
                            net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT));
            cells.put(Scenario.key(partnerX, 0, partnerZ), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing)
                    .setValue(net.minecraft.world.level.block.DoorBlock.HINGE,
                            net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT));
            slot++;
        }
        return new Scenario("doorpairs", cells, 4 * 6 + 2, 2, 3);
    }

    /**
     * One of every door the game has — enumerated from the registry, not listed by hand.
     *
     * <p>A hand-written list is a list that goes stale: 26.1.2 has the twelve wood doors, iron, and the copper family
     * with its four oxidation stages each in a waxed and unwaxed form, and a version bump adds more without telling
     * anyone. Scanning {@code BuiltInRegistries.BLOCK} for {@link net.minecraft.world.level.block.DoorBlock} means the
     * scenario grows with the game and cannot quietly stop covering something.
     *
     * <p>Facing and hinge are cycled across the types rather than held constant, so the pass exercises variety
     * instead of testing one geometry twenty times. Three cells of spacing keeps each door out of its neighbour's
     * hinge decision — see {@link #doorPairs} for the coupled case, which is a different rule.
     *
     * <p>Iron doors are included deliberately. Their {@code open} cannot be reached by clicking at all, which is an
     * honest exception the plan reports up front; placing them is still ordinary and must work.
     */
    public static Scenario allDoorTypes() {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        net.minecraft.core.Direction[] facings =
                net.minecraft.core.Direction.Plane.HORIZONTAL.stream().toArray(net.minecraft.core.Direction[]::new);
        int index = 0;
        for (net.minecraft.world.level.block.Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof net.minecraft.world.level.block.DoorBlock)) {
                continue;
            }
            // Laid out as a GRID, not a row. Twenty-one doors three apart is sixty blocks long, which
            // overruns the box the bench clears and floors — the tail of the row then has nothing to stand
            // on and nothing to click, and twelve perfectly ordinary doors report as unbuildable for a
            // reason that has nothing to do with doors. Five per row keeps the whole scenario inside it.
            cells.put(Scenario.key((index % 5) * 3, 0, (index / 5) * 3), block.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DoorBlock.FACING, facings[index % facings.length])
                    .setValue(net.minecraft.world.level.block.DoorBlock.HINGE, (index / facings.length) % 2 == 0
                            ? net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT
                            : net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT));
            index++;
        }
        return new Scenario("alldoors", cells, 5 * 3, 2, ((index + 4) / 5) * 3);
    }

    /**
     * Every block whose facing vanilla reads off the LOOK direction, in all the directions it can face.
     *
     * <p>This family is the hardest thing the builder does, and it had no test. Vanilla picks the facing by asking
     * which axis the view vector points along most -- a boundary with zero tolerance, so a fraction of a degree decides
     * between "up" and "sideways". etz-basalt is 1264 such cells and a world audit found roughly one percent of them
     * placed the wrong way round; on a 15,000-cell schematic that is dozens of blocks silently wrong, and each one
     * costs a break-and-retry loop before it is given up on.
     *
     * <p>Geometry matters here and the first attempt got it wrong, so it is worth writing down. A single row on the
     * ground CANNOT test {@code facing=down}: that facing requires looking UP, which requires something above the cell
     * to click, and in a bottom-up build everything above the current layer is air. Three of the four down-facing
     * cells failed for that reason alone -- a fair-looking test that was asking for the impossible.
     *
     * <p>So the test blocks sit in the MIDDLE row of a three-high wall: a course below them, and a course above. The
     * course above does not exist while their own layer is being built, which is exactly the situation etz-basalt's
     * 464 down-facing pistons are in. They are therefore expected to be deferred, retired, and then placed by the retry
     * sweep once the course above them stands -- which makes this a test of retirement and the sweep as well as of
     * orientation. Adjacent columns, so every cell always has a horizontal neighbour to click against.
     */
    public static Scenario facings() {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        List<BlockState> tests = new java.util.ArrayList<>();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            tests.add(Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, dir));
            tests.add(Blocks.PISTON.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, dir));
            tests.add(Blocks.OBSERVER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, dir));
            tests.add(Blocks.DROPPER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, dir));
        }
        // Repeaters and comparators are horizontal only; their delay/mode belongs to the interaction pass, not here.
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            tests.add(Blocks.REPEATER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, dir));
            tests.add(Blocks.COMPARATOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, dir));
        }
        BlockState course = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < tests.size(); x++) {
            cells.put(Scenario.key(x, 0, 0), course);
            cells.put(Scenario.key(x, 1, 0), tests.get(x));
            cells.put(Scenario.key(x, 2, 0), course);
        }
        return new Scenario("facings", cells, tests.size(), 3, 1);
    }

    /**
     * A wall with a doorway in it, and cells the bot can only reach by going through that doorway.
     *
     * <p>The regression test for getting trapped. A door is the one block the pathfinder will happily walk INTO while
     * modelling only doors at the destination of a step, never the one at the source -- so a bot that stops inside a
     * closed doorway pushes against it and re-plans the same impossible step indefinitely. In the basalt run that cost
     * the final 4,400 ticks, at one of the farm's four entrance doors, and only ended because the owner opened it by
     * hand.
     *
     * <p>Geometry: a closed 7x7 perimeter two blocks high, with a single door as its only opening, and a floor to lay
     * INSIDE it. A first attempt used a straight wall with a door in the middle and the bot simply walked round the
     * end -- a test the bug can dodge is not a test. The ring removes the way round: the interior can only be reached
     * through the doorway, so once the door exists every remaining cell is on the far side of it.
     */
    public static Scenario doorway() {
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        BlockState wall = Blocks.STONE.defaultBlockState();
        final int size = 7;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    if (x != 0 && x != size - 1 && z != 0 && z != size - 1) {
                        continue;   // interior, not perimeter
                    }
                    if (x == 3 && z == 0) {
                        continue;   // the gap the door goes in
                    }
                    cells.put(Scenario.key(x, y, z), wall);
                }
            }
        }
        // The door itself: lower half only, the upper half fills itself when the lower is placed.
        cells.put(Scenario.key(3, 0, 0), Blocks.OAK_DOOR.defaultBlockState());
        // The floor inside the room. Every one of these is behind the door.
        for (int x = 1; x < size - 1; x++) {
            for (int z = 1; z < size - 1; z++) {
                cells.put(Scenario.key(x, 0, z), wall);
            }
        }
        return new Scenario("doorway", cells, size, 2, size);
    }

    /**
     * Materialise a real schematic file as a scenario, so the bench can grade an actual build the same way it
     * grades a synthetic one.
     *
     * <p>Synthetic geometry tests what someone thought to write down. A real schematic brings block types, mixed
     * orientations, overhangs and interior volumes nobody would think to encode by hand -- which is where a builder
     * actually breaks. Every non-air cell the schematic claims becomes a graded cell, so the verdict, the
     * unsatisfied list and the server audit all work unchanged.
     */
    public static Scenario fromFile(java.io.File file) {
        java.util.Optional<princeps.api.schematic.format.ISchematicFormat> format =
                princeps.utils.schematic.SchematicSystem.INSTANCE.getByFile(file);
        if (!format.isPresent()) {
            return null;
        }
        princeps.api.schematic.IStaticSchematic parsed;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            parsed = format.get().parse(in);
        } catch (Exception e) {
            return null;
        }
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        Map<String, Integer> unplaceable = new java.util.LinkedHashMap<>();
        for (int y = 0; y < parsed.heightY(); y++) {
            for (int z = 0; z < parsed.lengthZ(); z++) {
                for (int x = 0; x < parsed.widthX(); x++) {
                    BlockState desired = parsed.getDirect(x, y, z);
                    // Air is not a placement. Grading it would make an untouched world look like a finished build.
                    if (desired == null || desired.isAir()) {
                        continue;
                    }
                    // No item, no placement -- by ANY means, for anyone, bot or human. Water and lava are the
                    // familiar case (OneBlock's adapter omits them from the structural pass and fills them with a
                    // bucket afterwards; leaving them in is what stopped the owner's basalt farm at 418 of 16342).
                    // But the rule is not about fluids, it is about obtainability, and the narrower fluid-only test
                    // let a subtler family through: a litematic captures a RUNNING farm, so it contains
                    // piston_head -- eight cells of a block that exists only while a piston is extended. Nothing can
                    // place it, so those cells could never be satisfied and etz-basalt was capped at 99.947% before
                    // the builder even started. A cell no one can place measures nothing about the builder.
                    if (desired.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
                        unplaceable.merge(
                                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(desired.getBlock())
                                        .toString(), 1, Integer::sum);
                        continue;
                    }
                    cells.put(Scenario.key(x, y, z), desired);
                }
            }
        }
        if (!unplaceable.isEmpty()) {
            StringBuilder sb = new StringBuilder("[BENCH] " + file.getName() + ": excluded cells with no placeable"
                    + " item --");
            unplaceable.forEach((block, count) -> sb.append(' ').append(block).append(" x").append(count));
            System.out.println(sb);
        }
        if (cells.isEmpty()) {
            return null;
        }
        return new Scenario(file.getName(), cells, parsed.widthX(), parsed.heightY(), parsed.lengthZ());
    }

    /** Look up a scenario by name, or null when unknown. {@code file:<name>} loads from the schematics folder. */
    public static Scenario byName(String name) {
        if (name.regionMatches(true, 0, "file:", 0, 5)) {
            String fileName = name.substring(5);
            java.io.File dir = new java.io.File(
                    net.minecraft.client.Minecraft.getInstance().gameDirectory, "schematics");
            java.io.File file = new java.io.File(dir, fileName);
            return file.exists() ? fromFile(file) : null;
        }
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "ring":
            case "ring7":
                return ring(7, 2);   // the live self-box geometry
            case "ringbig":
                return ring(11, 3);
            case "ringresume":
                return ringResume();
            case "wall":
                return wall(11, 2);
            case "floor":
                return floor(9);
            case "dig":
                return dig(7, 3, 7);
            case "digbig":
                return dig(11, 4, 11);
            case "dig15":
                return dig(15, 15, 15);
            case "dig20":
                return dig(20, 20, 20);
            case "dig30":
                return dig(30, 30, 30);
            case "oriented":
                return oriented();
            case "doors":
            case "doormatrix":
                return doorMatrix();
            case "doorpairs":
                return doorPairs();
            case "alldoors":
                return allDoorTypes();
            case "doorway":
                return doorway();
            case "facings":
                return facings();
            default:
                return null;
        }
    }

    public static String scenarioNames() {
        return "ring7, ringbig, ringresume, wall, floor, dig, digbig, dig15, dig20, dig30, oriented, doors, doorpairs, alldoors, doorway, facings";
    }
}
