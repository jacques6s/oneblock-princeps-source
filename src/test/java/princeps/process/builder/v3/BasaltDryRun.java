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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import princeps.api.schematic.ISchematic;
import princeps.process.builder.bench.BenchSchematics;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The dry run against the owner's real basalt farm, with no client, no server and no world — the measurement that
 * answers Q1, Q2 and Q4 of plan section 16 before an executor exists to be wrong about them.
 *
 * <h2>Why a {@code main} and not only a JUnit test</h2>
 *
 * <p>Two thirds of this file DO run under JUnit and do so in {@link BasaltDryRunTest}: parsing the litematic,
 * capturing the world and the view, and the whole Q1 foot-column census, which {@link UpwardLook#classifyFootColumns}
 * deliberately keeps settings-free for exactly this reason. What does not run there is the planner itself.
 * {@link PredictedWorld#isStandable} decides a candidate stance with
 * {@code MovementHelper.canWalkThroughBlockState}, which falls through to
 * {@code Princeps.settings().blocksToAvoid} at {@code MovementHelper.java:155} for every block that is neither air
 * nor one of the seventeen types short-circuited above that line. Against a synthetic world of azalea and shulker
 * boxes that line is never reached, which is how {@code OrderPlannerTest} runs the oracle at all. Against basalt,
 * pistons and observers it is reached on the first candidate stance that lands beside a placed block, and
 * {@code Princeps.settings()} dies in a static initialiser under JUnit (trap 1.7, plan correction K2) — verified
 * again here, not assumed.
 *
 * <p>So this class is launched by {@code tools/dryrun/run-dryrun.ps1}, which prepends a compiled shim for
 * {@code princeps.api.PrincepsAPI} — a real {@code Settings} at stock defaults, no {@code Minecraft} — to the
 * classpath. The shim's own javadoc states what it changes and what it does not. The alternative would have been to
 * put that shim in the test source set, where it would shadow the real class for every test in the suite and quietly
 * invalidate the premise the azalea/shulker fixtures are built on; a script that nothing else invokes keeps the
 * exception where a reader will find it.
 *
 * <h2>The world this plans against</h2>
 *
 * <p>Superflat: stone everywhere below the schematic's bottom layer, air everywhere else, every column loaded. That
 * is the bench's world, and it is the right premise for these three questions — a real terrain snapshot would mix
 * "the geometry does not work" with "there was a hill in the way", and only the first is being measured. Every cell
 * therefore comes out PROVEN; the PROVEN/PROVISIONAL split of 5.10 has nothing to bite on here and that is a property
 * of the fixture, not a result.
 *
 * <p>{@link #ORIGIN} puts the bottom layer at y=-60, so the printed layer numbers are the ones plan 5.2, 5.5 and 16
 * quote (the 448 {@code facing=down} pistons in y=-57). {@link #verifyAnchor} re-derives that count from the file and
 * the run says so loudly when it does not match, so a schematic that was replaced or re-anchored cannot have its
 * numbers read as answers to the old question. {@code BasaltDryRunTest} turns the same check into an assertion.
 */
public final class BasaltDryRun {

    private BasaltDryRun() {
    }

    /** Where the owner's farm lives, relative to the client's working directory ({@code run/}) and to the repository
     *  root — both are tried, because the harness runs from {@code run/} and the JUnit test from the project root. */
    public static final String SCHEMATIC_NAME = "etz-basalt.litematic";

    /**
     * Bottom layer at y=-60, so that the 448 {@code piston[facing=down]} land in y=-57.
     *
     * <p>Pinned by measurement rather than assumed. The litematic carries no absolute anchor the loader keeps
     * (trap 1.51: {@code Metadata} is never read), so the origin is a choice, and the only choice that makes the
     * layer numbers in the plan document mean anything is the one that reproduces its census. y=-60 does, exactly:
     * layer -57 then holds 448 {@code piston[extended=false,facing=down]}, and the whole family across the build
     * comes to 464 pistons + 112 {@code observer[facing=up]} + 16 {@code sticky_piston[facing=down]} = 592, which is
     * the count in 5.7 to the block. y=-59 was tried first and put the piston row in -56.
     */
    public static final Vec3i ORIGIN = new Vec3i(0, -60, 0);

    /** The layer plan 5.2 and question Q1 are about. */
    public static final int PISTON_LAYER = -57;

    /** What Q1 counts: {@code facing=down} pistons in {@link #PISTON_LAYER}, per {@code UPWARD_LOOK.md}. */
    public static final int EXPECTED_PISTONS = 448;

    /** Off the build, on the floor, outside the schematic's footprint but inside the captured box. */
    public static BlockPos botStart() {
        return new BlockPos(-4, ORIGIN.getY(), -4);
    }

    /**
     * Superflat fill below the build. Stone is a normal cube, so {@code canWalkOnBlockState} answers YES at
     * {@code MovementHelper.java:415} before any setting is read — the floor is not what forces the shim.
     *
     * <p>A method and not a constant, and the same goes for {@link #throwawayCandidates()}: touching {@code Blocks}
     * from a static initialiser loads {@code BuiltInRegistries} before {@link #bootstrap} has run, and vanilla
     * answers that with {@code IllegalArgumentException: Not bootstrapped} wrapped in an
     * {@code ExceptionInInitializerError} that names a sound event. Nothing in this class may reference a registry
     * object outside a method body.
     */
    public static BlockState floor() {
        return Blocks.STONE.defaultBlockState();
    }

    /**
     * {@code Settings.acceptableThrowawayItems} at its stock default, written out rather than read.
     *
     * <p>Duplicating the list is the point: {@link BasaltDryRunTest} shares this file and must not touch
     * {@code Princeps.settings()} at all, and the value is a default the checkout never overrides —
     * {@code run/princeps/settings.txt} does not exist, so {@code SettingsUtil.readAndApply} applies nothing. If that
     * ever stops being true this list is where the divergence shows up, which is better than a shim silently
     * answering a different list than the client would.
     */
    public static List<Item> throwawayCandidates() {
        return List.of(Blocks.DIRT.asItem(), Blocks.COBBLESTONE.asItem(), Blocks.NETHERRACK.asItem(),
                Blocks.STONE.asItem());
    }

    /**
     * How many of each material the bot is given.
     *
     * <p>Large enough that no cell is ever refused for {@code NO_ITEM} and the material ledger reports no shortfall:
     * Q2 and Q4 are questions about geometry and about the Belady optimum, and a plan that stopped early because a
     * stack ran out would answer neither. Survival consumption is Z1/Z2 territory and explicitly out of scope here.
     */
    public static final int STACK_COUNT = 4096;

    // ------------------------------------------------------------------------------------------------- bootstrap

    /**
     * Registries, plus the component binding that makes {@link ItemStack} constructible.
     *
     * <p>{@code new ItemStack(item)} throws {@code NullPointerException: Components not bound yet} after
     * {@code Bootstrap.bootStrap()} alone, because item components are bound by a data-pack load no headless process
     * performs. Bound by hand here exactly as {@code HotbarScheduleTest} and {@code OrderPlannerTest} do. Side effect:
     * {@code getMaxStackSize()} answers 1 afterwards, since {@link DataComponentMap#EMPTY} carries no
     * {@code MAX_STACK_SIZE} — nothing on the planning path reads it, and {@link #STACK_COUNT} is carried in the
     * stack's count field regardless.
     */
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- the fixture

    /** The litematic, wherever it is relative to the process's working directory. Null when it is nowhere. */
    public static File schematicFile() {
        File fromRoot = new File("run/schematics/" + SCHEMATIC_NAME);
        if (fromRoot.isFile()) {
            return fromRoot;
        }
        File fromRun = new File("schematics/" + SCHEMATIC_NAME);
        return fromRun.isFile() ? fromRun : null;
    }

    /**
     * The farm, parsed.
     *
     * <p>{@code BenchSchematics.fromFile} and not the raw {@code IStaticSchematic}: it drops the cells whose block
     * has no item — eight {@code piston_head}s in this file, a block that exists only while a piston is extended and
     * that therefore no builder and no human can place. Grading or planning them would answer a question about
     * vanilla rather than about this engine, and the bench already excludes them, so the two agree by construction.
     */
    public static BenchSchematics.Scenario load(File file) {
        return BenchSchematics.fromFile(file);
    }

    /** Superflat stone under the build, air above, every column loaded. */
    public static PredictedWorld world(ISchematic schematic) {
        Vec3i max = new Vec3i(
                ORIGIN.getX() + Math.max(0, schematic.widthX() - 1),
                ORIGIN.getY() + Math.max(0, schematic.heightY() - 1),
                ORIGIN.getZ() + Math.max(0, schematic.lengthZ() - 1));
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture((x, y, z) -> y < ORIGIN.getY() ? floor() : air,
                (x, z) -> true, ORIGIN, max, PredictedWorld.DEFAULT_MARGIN);
    }

    /**
     * The view, resolved against the untouched world exactly as {@code PlannedBuilderProcess.runDryRun} does.
     *
     * <p>Two passes over the same schematic: the ledger's placeable list is derived from the inventory, and the
     * inventory is derived from what the schematic asks for. The first pass is handed an empty list, which for a
     * {@code BenchSchematics.Scenario} changes nothing — it answers from a fixed map and never reads the argument —
     * but doing it in one pass would bake that into the harness and mislead whoever next points this at a schematic
     * that does read it.
     */
    public static SchematicView view(ISchematic schematic, PredictedWorld world,
                                     HotbarSchedule.InventorySnapshot inventory) {
        // Mirrors the live default (Settings.builderV3Waterlogging = false) on purpose: a harness that
        // planned a bucket pass the shipped client never runs would report numbers for a build nobody
        // is going to get.
        return SchematicView.capture("etz-basalt", schematic, ORIGIN, world, ledgerPlaceable(inventory),
                V3Settings.defaults().waterlogging());
    }

    /** {@code PlannedBuilderProcess.ledgerPlaceable}, verbatim in effect: the inventory's block items as default
     *  states, in {@code distinctItems()}' registry order. */
    public static List<BlockState> ledgerPlaceable(HotbarSchedule.InventorySnapshot inventory) {
        List<BlockState> placeable = new ArrayList<>();
        for (Item item : inventory.distinctItems()) {
            if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
                placeable.add(blockItem.getBlock().defaultBlockState());
            }
        }
        return placeable;
    }

    /**
     * An inventory that can build the whole schematic: a pickaxe in slot 0, the throwaway in slot 8, one material per
     * remaining slot in registry order.
     *
     * <p>Registry order and not encounter order, for the reason
     * {@code OrderPlannerTest.theOrderIsInvariantToWhereTheMaterialSitsInTheInventory} exists to pin: the plan must be
     * a property of the schematic and the world, and an inventory laid out in the order the parser happened to walk
     * the palette would make it a property of the file's byte layout too.
     *
     * @param materials the distinct placeable items the schematic asks for
     * @param throwaway the reserved helper block, or null when none of {@link #THROWAWAY_CANDIDATES} is free
     */
    public static HotbarSchedule.InventorySnapshot inventory(List<Item> materials, Item throwaway) {
        List<ItemStack> slots = new ArrayList<>(HotbarSchedule.INVENTORY_SLOTS);
        for (int index = 0; index < HotbarSchedule.INVENTORY_SLOTS; index++) {
            slots.add(ItemStack.EMPTY);
        }
        slots.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.DIAMOND_PICKAXE));
        if (throwaway != null) {
            slots.set(HotbarSchedule.THROWAWAY_SLOT, new ItemStack(throwaway, STACK_COUNT));
        }
        List<Item> sorted = new ArrayList<>(materials);
        sorted.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
        int slot = HotbarSchedule.FIRST_MATERIAL_SLOT;
        for (Item item : sorted) {
            while (slot < HotbarSchedule.INVENTORY_SLOTS
                    && (slot == HotbarSchedule.PICKAXE_SLOT || slot == HotbarSchedule.THROWAWAY_SLOT
                        || !slots.get(slot).isEmpty())) {
                slot++;
            }
            if (slot >= HotbarSchedule.INVENTORY_SLOTS) {
                // Reported by the capacity check rather than thrown: "34 types do not fit in 36 slots" is one of the
                // answers this run is allowed to produce (5.8), and dying here would hide it.
                break;
            }
            slots.set(slot, new ItemStack(item, STACK_COUNT));
        }
        return new HotbarSchedule.InventorySnapshot(slots);
    }

    /** Every distinct placeable item the schematic asks for, in first-seen order. */
    public static List<Item> materialsOf(SchematicView view) {
        List<Item> items = new ArrayList<>();
        for (BlockState state : view.distinctStates()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR && !items.contains(item)) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * {@code ScaffoldPlanner.chooseScaffoldItem} over {@link #throwawayCandidates()}.
     *
     * <p>The inventory it is shown is a PROBE holding one of each candidate and nothing else, not the real one.
     * {@code chooseScaffoldItem} requires {@code inventory.count(candidate) > 0} — it will not name a helper block the
     * bot is not carrying — and the real inventory cannot hold the throwaway until the throwaway has been chosen. The
     * probe breaks that circle without touching what the choice depends on, which is only "is this candidate also a
     * schematic material".
     */
    public static Optional<Item> throwaway(SchematicView view) {
        List<Item> candidates = throwawayCandidates();
        List<ItemStack> slots = new ArrayList<>(HotbarSchedule.INVENTORY_SLOTS);
        for (int index = 0; index < HotbarSchedule.INVENTORY_SLOTS; index++) {
            slots.add(index < candidates.size() ? new ItemStack(candidates.get(index), STACK_COUNT)
                    : ItemStack.EMPTY);
        }
        return ScaffoldPlanner.chooseScaffoldItem(view, new HotbarSchedule.InventorySnapshot(slots), candidates);
    }

    // --------------------------------------------------------------------------------------------------- the census

    /** One layer's worth of Q1, and the sub-question about what would have to be excavated. */
    public record Census(
            int layer,
            int cells,
            int upwardCells,
            int withUsableColumn,
            int withUsableLateral,
            int needingSteppingStoneOnly,
            int allColumnsSchematic,
            List<BlockPos> blockedCells,
            int blockedWhoseExcavationIsUpward,
            Map<String, Integer> excavationBlockNames
    ) {

        /** Compatibility accessor for reports captured before the stance search expanded to three depths. */
        @Deprecated
        public int allEightSchematic() {
            return this.allColumnsSchematic;
        }
    }

    /** The operational Q1 measurement, using the pathfinder's authoritative standability predicate. */
    public static Census census(PredictedWorld world, SchematicView view, int layer,
                                 java.util.function.Predicate<BlockState> family) {
        return census(world, view, layer, family, true);
    }

    /** Explicit operational name retained for report code that wants to make the distinction visible. */
    public static Census operationalCensus(PredictedWorld world, SchematicView view, int layer,
                                            java.util.function.Predicate<BlockState> family) {
        return census(world, view, layer, family, true);
    }

    /** Settings-free approximation for parser-only diagnostics; never a planner or Q1 verdict. */
    public static Census structuralCensus(PredictedWorld world, SchematicView view, int layer,
                                           java.util.function.Predicate<BlockState> family) {
        return census(world, view, layer, family, false);
    }

    private static Census census(PredictedWorld world, SchematicView view, int layer,
                                 java.util.function.Predicate<BlockState> family, boolean authoritative) {
        int upward = 0;
        int usable = 0;
        int usableLateral = 0;
        int steppingStoneOnly = 0;
        int allSchematic = 0;
        int recursive = 0;
        List<BlockPos> blocked = new ArrayList<>();
        Map<String, Integer> excavation = new TreeMap<>();

        for (BlockPos cell : view.cellsInLayer(layer)) {
            BlockState desired = view.desired(cell);
            if (!family.test(desired)) {
                continue;
            }
            upward++;
            List<UpwardLook.FootColumn> columns = authoritative
                    ? UpwardLook.classifyFootColumns(world, view, cell)
                    : UpwardLook.classifyFootColumnsStructurally(world, view, cell);
            boolean anyUsable = false;
            boolean anyUsableLateral = false;
            boolean anyStepping = false;
            for (UpwardLook.FootColumn column : columns) {
                if (column.status() == UpwardLook.Status.USABLE) {
                    anyUsable = true;
                    anyUsableLateral |= !column.corner();
                } else if (column.status() == UpwardLook.Status.NEEDS_STEPPING_STONE) {
                    anyStepping = true;
                }
            }
            if (anyUsable) {
                usable++;
                if (anyUsableLateral) {
                    usableLateral++;
                }
                continue;
            }
            if (anyStepping) {
                steppingStoneOnly++;
                continue;
            }
            blocked.add(cell);
            if (UpwardLook.allColumnsAreSchematic(columns)) {
                allSchematic++;
            }
            // The sub-question: for a cell with no column, the fix on the table is planned excavation of one foot
            // cell. Whether that cell is itself an upward-look block decides whether the excavation recurses into the
            // same family, which is what 5.7.4 flags as the bounded-but-real worry.
            boolean recurses = false;
            for (UpwardLook.FootColumn column : columns) {
                BlockState foot = view.desired(column.feet());
                if (foot == null || foot.isAir()) {
                    continue;
                }
                excavation.merge(PlacementGeometry.blockName(foot), 1, Integer::sum);
                recurses |= UpwardLook.applies(foot);
            }
            if (recurses) {
                recursive++;
            }
        }
        return new Census(layer, view.cellsInLayer(layer).size(), upward, usable, usableLateral, steppingStoneOnly,
                allSchematic, List.copyOf(blocked), recursive, Map.copyOf(excavation));
    }

    /**
     * Does this state genuinely require a look whose dominant axis is UP — the exact test, derived from the same
     * convention {@link PlacementOracle#simulate} places by?
     *
     * <p>{@link UpwardLook#applies} deliberately does not answer this. It delegates to
     * {@link PlacementGeometry#needsUpwardLook}, which asks {@link PlacementGeometry#requiredLookDirections}, which
     * returns <b>both signs</b> — {@code {facing.getOpposite(), facing}} — with a documented reason: vanilla is not
     * consistent about which way round it reads the look (a piston faces away from you, an observer faces where you
     * look), and returning both and letting the simulation decide is safer than a per-block list to get wrong. The
     * price is that {@code needsUpwardLook} is an over-approximation for every vertical facing in those three block
     * families, and on etz-basalt the over-approximation is not small: it adds 624 {@code sticky_piston[facing=up]},
     * 32 {@code dropper[facing=up]} and 16 {@code observer[facing=down]} — 672 cells, all of which need a look
     * pointing DOWN — to a genuine family of 592.
     *
     * <p>For the stance window ({@link PlacementGeometry#lowestStanceOffset}) that over-approximation is harmless and
     * costs three extra rows of candidates. For a CENSUS it is not harmless, because Q1 is a question about "the 448
     * {@code facing=down} pistons in y=-57" and an answer averaged over 1 264 cells would not be an answer to it. So
     * the census uses this test, and the wide one is reported beside it rather than instead of it.
     *
     * <p>The rule, read straight off {@code simulate}: {@code state = observer ? look : look.getOpposite()}, hence
     * {@code look = observer ? facing : facing.getOpposite()}, hence the look is UP exactly when an observer faces UP
     * or a piston/dispenser family block faces DOWN.
     */
    public static boolean needsGenuineUpwardLook(BlockState desired) {
        if (desired == null || !PlacementGeometry.needsUpwardLook(desired)) {
            return false;
        }
        net.minecraft.core.Direction facing =
                desired.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        boolean observer = desired.getBlock() instanceof net.minecraft.world.level.block.ObserverBlock;
        return observer ? facing == net.minecraft.core.Direction.UP : facing == net.minecraft.core.Direction.DOWN;
    }

    /** Cells in one layer whose desired state satisfies a family test. */
    public static int countFamily(SchematicView view, int layer, java.util.function.Predicate<BlockState> family) {
        int found = 0;
        for (BlockPos cell : view.cellsInLayer(layer)) {
            if (family.test(view.desired(cell))) {
                found++;
            }
        }
        return found;
    }

    /**
     * Refuse to report Q1's numbers against a schematic that is not the one Q1 was asked about.
     *
     * @return the number of {@code piston[facing=down]} cells found in {@link #PISTON_LAYER}
     */
    public static int verifyAnchor(SchematicView view) {
        return countFamily(view, PISTON_LAYER, state -> state != null
                && state.getBlock() == Blocks.PISTON
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                        == net.minecraft.core.Direction.DOWN);
    }

    /**
     * The upward-look family, tallied by state, layer by layer.
     *
     * <p>Exists because the plan's own census and this one disagree, and a disagreement between two counts of the
     * same thing is only useful if the composition is visible. {@code UPWARD_LOOK.md} counted the family as
     * "464 pistons, 112 observers, 16 sticky pistons"; {@link UpwardLook#applies} is a state test — any state whose
     * {@link PlacementGeometry#requiredLookDirections} contains UP — so it also catches droppers, dispensers, hoppers
     * and observers that the older count did not think to look for. Which of the two is the family that matters is a
     * question about vanilla, and it is answered by looking at the names.
     */
    public static Map<Integer, Map<String, Integer>> upwardComposition(SchematicView view) {
        Map<Integer, Map<String, Integer>> byLayer = new LinkedHashMap<>();
        for (int layer : view.layers()) {
            Map<String, Integer> tally = new TreeMap<>();
            for (BlockPos cell : view.cellsInLayer(layer)) {
                BlockState desired = view.desired(cell);
                if (UpwardLook.applies(desired)) {
                    tally.merge(BuildAction.describeState(desired), 1, Integer::sum);
                }
            }
            if (!tally.isEmpty()) {
                byLayer.put(layer, tally);
            }
        }
        return byLayer;
    }

    // ---------------------------------------------------------------------------------------------------- the run

    public static void main(String[] args) {
        bootstrap();
        for (String arg : args) {
            if (arg.startsWith("--scenario=")) {
                // The fallback the task sanctions and the control the etz-basalt run needs: a synthetic scenario is
                // single-material, so it cannot hit the "only nine slots reach the oracle" gap that stops the real
                // file, and a complete plan over one of them is the proof that the planner completes at all.
                BenchSchematics.Scenario scenario = BenchSchematics.byName(arg.substring("--scenario=".length()));
                if (scenario == null) {
                    System.out.println("unknown scenario; one of: " + BenchSchematics.scenarioNames());
                    System.exit(2);
                    return;
                }
                runScenario(scenario);
                return;
            }
        }
        File file = schematicFile();
        if (file == null) {
            System.out.println("NO SCHEMATIC: " + SCHEMATIC_NAME + " not found from "
                    + new File(".").getAbsolutePath());
            System.exit(2);
            return;
        }
        System.out.println("schematic: " + file.getAbsolutePath() + "  (" + file.length() + " bytes)");

        BenchSchematics.Scenario parsed = load(file);
        if (parsed == null) {
            System.out.println("PARSE FAILED");
            System.exit(2);
            return;
        }
        System.out.printf(Locale.ROOT, "parsed:    %dx%dx%d, %d placeable cells%n",
                parsed.widthX(), parsed.heightY(), parsed.lengthZ(), parsed.cellCount());

        PredictedWorld world = world(parsed);
        SchematicView prelim = view(parsed, world, HotbarSchedule.InventorySnapshot.empty());
        List<Item> materials = materialsOf(prelim);
        Optional<Item> helper = throwaway(prelim);
        HotbarSchedule.InventorySnapshot inventory = inventory(materials, helper.orElse(null));
        SchematicView view = view(parsed, world, inventory);

        V3Settings settings = V3Settings.defaults();
        System.out.printf(Locale.ROOT, "cells:     %d over %d layers, %d distinct states, %d distinct items%n",
                view.cellCount(), view.layers().size(), view.distinctStates().size(), materials.size());
        System.out.println("throwaway: " + helper.map(item -> BuildAction.describeItem(item)).orElse("NONE"));
        StringBuilder names = new StringBuilder("items:    ");
        for (Item item : inventory.distinctItems()) {
            names.append(' ').append(BuildAction.describeItem(item));
        }
        System.out.println(names);
        HotbarSchedule.CapacityCheck capacity =
                HotbarSchedule.capacity(view, settings, inventory, helper.orElse(null));
        System.out.println("capacity:  " + capacity.describe());

        printLayerCensus(view);

        int pistons = verifyAnchor(view);
        System.out.printf(Locale.ROOT, "%nANCHOR: layer %d holds %d piston[facing=down] cells (expected %d)%n",
                PISTON_LAYER, pistons, EXPECTED_PISTONS);
        if (pistons != EXPECTED_PISTONS) {
            System.out.println("  the anchor does not match; Q1's numbers below are about a DIFFERENT schematic "
                    + "or a different origin than plan section 16 asked about");
        }

        System.out.println();
        System.out.println("=== upward-look family, by state ===");
        for (Map.Entry<Integer, Map<String, Integer>> layer : upwardComposition(view).entrySet()) {
            for (Map.Entry<String, Integer> state : layer.getValue().entrySet()) {
                System.out.printf(Locale.ROOT, "  layer %5d  %-46s %5d%n",
                        layer.getKey(), state.getKey(), state.getValue());
            }
        }

        printQ1(world, view, BasaltDryRun::needsGenuineUpwardLook, "genuine upward-look family");
        printQ1(world, view, UpwardLook::applies, "planner's wider upward-look test");

        if (args.length > 0 && args[0].equals("--census-only")) {
            return;
        }

        System.out.println();
        System.out.println("=== THE DRY RUN ===");
        long started = System.nanoTime();
        PlanReport report = plan(view, world, settings, inventory, helper.orElse(null));
        long millis = (System.nanoTime() - started) / 1_000_000L;
        System.out.println(report.render());
        System.out.printf(Locale.ROOT, "%nplanned in %d ms%n", millis);
        printPlanFacts(report);

        List<java.nio.file.Path> written = PlanTraceWriter.writeAll("etz-basalt", report.plan(), report);
        for (java.nio.file.Path path : written) {
            System.out.println("wrote " + path.toAbsolutePath());
        }

        System.out.println();
        System.out.println("=== DETERMINISM (plan 13: two runs, byte-identical) ===");
        PredictedWorld secondWorld = world(parsed);
        SchematicView secondView = view(parsed, secondWorld, inventory);
        PlanReport second = plan(secondView, secondWorld, settings, inventory, helper.orElse(null));
        System.out.println("plan lines identical:   " + report.plan().toPlanLines().equals(second.plan().toPlanLines())
                + "  (" + report.plan().toPlanLines().size() + " lines)");
        System.out.println("proof lines identical:  "
                + report.plan().toProofLines().equals(second.plan().toProofLines())
                + "  (" + report.plan().toProofLines().size() + " lines)");
        System.out.println("report identical:       " + report.render().equals(second.render()));
    }

    /**
     * One synthetic scenario, end to end, on the same superflat world — the control for the etz-basalt run.
     *
     * <p>{@code ringbig} is the geometry that stalled the old engine at y=112 (a one-wide hollow perimeter that boxes
     * the bot in), and it is single-material, so nothing here is limited by which items reach the oracle. If the
     * planner produces a complete plan for this and an incomplete one for etz-basalt, the difference is the material
     * gap and not the geometry — which is the whole reason this mode exists rather than only the real file.
     */
    public static void runScenario(BenchSchematics.Scenario scenario) {
        System.out.printf(Locale.ROOT, "scenario:  %s  %dx%dx%d, %d cells%n", scenario.name, scenario.widthX(),
                scenario.heightY(), scenario.lengthZ(), scenario.cellCount());
        PredictedWorld world = world(scenario);
        SchematicView bare = view(scenario, world, HotbarSchedule.InventorySnapshot.empty());
        Optional<Item> helper = throwaway(bare);
        HotbarSchedule.InventorySnapshot inventory = inventory(materialsOf(bare), helper.orElse(null));
        SchematicView view = view(scenario, world, inventory);
        V3Settings settings = V3Settings.defaults();
        System.out.println("throwaway: " + helper.map(BuildAction::describeItem).orElse("NONE"));

        long started = System.nanoTime();
        PlanReport report = plan(view, world, settings, inventory, helper.orElse(null));
        System.out.println(report.render());
        System.out.printf(Locale.ROOT, "%nplanned in %d ms%n", (System.nanoTime() - started) / 1_000_000L);
        printPlanFacts(report);

        PredictedWorld again = world(scenario);
        PlanReport second = plan(view(scenario, again, inventory), again, settings, inventory, helper.orElse(null));
        System.out.println();
        System.out.println("=== DETERMINISM (plan 13: two runs, byte-identical) ===");
        System.out.println("plan lines identical:   "
                + report.plan().toPlanLines().equals(second.plan().toPlanLines()));
        System.out.println("proof lines identical:  "
                + report.plan().toProofLines().equals(second.plan().toProofLines()));
        System.out.println("report identical:       " + report.render().equals(second.render()));
    }

    /** The planner, wired the way {@code PlannedBuilderProcess.runDryRun} wires it. */
    public static PlanReport plan(SchematicView view, PredictedWorld world, V3Settings settings,
                                  HotbarSchedule.InventorySnapshot inventory, Item helper) {
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, helper,
                HotbarSchedule.THROWAWAY_SLOT);
        return new OrderPlanner(oracle, scaffold)
                .onProgress(System.out::println)
                .plan(view, world, settings, SolveBudget.DEFAULT, inventory, botStart());
    }

    private static void printLayerCensus(SchematicView view) {
        System.out.println();
        System.out.println("layer      cells   upward-look   (planner's wider test)");
        for (int layer : view.layers()) {
            System.out.printf(Locale.ROOT, "%5d  %9d  %12d  %22d%n", layer, view.cellsInLayer(layer).size(),
                    countFamily(view, layer, BasaltDryRun::needsGenuineUpwardLook),
                    countFamily(view, layer, UpwardLook::applies));
        }
    }

    private static void printQ1(PredictedWorld world, SchematicView view,
                                java.util.function.Predicate<BlockState> family, String familyName) {
        System.out.println();
        System.out.println("=== Q1: foot columns of the " + familyName + ", under the hard layer rule ===");
        System.out.println("layer   upward   >=1 usable   (of those, >=1 lateral)   stepping-stone only   "
                + "no column   all 24 schematic");
        Map<Integer, Census> all = new LinkedHashMap<>();
        for (int layer : view.layers()) {
            Census census = operationalCensus(world, view, layer, family);
            if (census.upwardCells() == 0) {
                continue;
            }
            all.put(layer, census);
            System.out.printf(Locale.ROOT, "%5d %8d %12d %27d %21d %11d %16d%n",
                    layer, census.upwardCells(), census.withUsableColumn(), census.withUsableLateral(),
                    census.needingSteppingStoneOnly(), census.blockedCells().size(), census.allColumnsSchematic());
        }
        for (Census census : all.values()) {
            if (census.blockedCells().isEmpty()) {
                continue;
            }
            System.out.printf(Locale.ROOT, "%nlayer %d has %d cells with no foot column:%n",
                    census.layer(), census.blockedCells().size());
            for (BlockPos cell : census.blockedCells()) {
                System.out.println("  " + BuildAction.describePos(cell) + "  "
                        + BuildAction.describeState(view.desired(cell)));
            }
            System.out.printf(Locale.ROOT, "  %d of them would have to excavate a block that is itself "
                    + "upward-look%n", census.blockedWhoseExcavationIsUpward());
            for (Map.Entry<String, Integer> entry : census.excavationBlockNames().entrySet()) {
                System.out.println("    candidate excavation block: " + entry.getKey() + " x" + entry.getValue());
            }
        }
    }

    private static void printPlanFacts(PlanReport report) {
        BuildPlan.Counts counts = report.plan().counts();
        System.out.println();
        System.out.println("=== Q2: scaffold per layer ===");
        if (counts.scaffoldByLayer().isEmpty()) {
            System.out.println("  none, in any layer");
        } else {
            List<Integer> layers = new ArrayList<>(counts.scaffoldByLayer().keySet());
            layers.sort(Comparator.naturalOrder());
            for (int layer : layers) {
                System.out.printf(Locale.ROOT, "  layer %5d: %d%n", layer, counts.scaffoldByLayer().get(layer));
            }
        }
        System.out.println("  total scaffold blocks: " + counts.scaffoldBlocks());
        System.out.println();
        System.out.println("=== Q4: hotbar swaps under Belady ===");
        System.out.println("  swaps: " + counts.hotbarSwaps());
        System.out.println();
        System.out.printf(Locale.ROOT, "cells %d   proven %d   provisional %d%n",
                counts.cells(), counts.proven(), counts.provisional());
        System.out.printf(Locale.ROOT, "actions %d = %d place + %d break + %d scaffold+/- + %d swap + %d interact%n",
                report.plan().size(), counts.placements(), counts.breaks(), counts.scaffoldBlocks() * 2,
                counts.hotbarSwaps(), counts.interactions());
        System.out.printf(Locale.ROOT, "ETA %s (%d ticks)   rays %d%n",
                report.plan().eta(), counts.estimatedTicks(), counts.raysCast());
        System.out.println("blockers: " + report.blockers().size());
    }
}
