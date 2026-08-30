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
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;
import princeps.api.utils.Rotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The hotbar schedule of 5.8, pinned where it is worth pinning: at the optimality claim and at determinism.
 *
 * <p>Belady is called "provably minimal" in the plan document, in the class javadoc and in the report's own wording,
 * and a claim of that shape is worth nothing unless something checks it. {@link #beladyMatchesBruteForceOptimum} does
 * exactly that — it enumerates every eviction policy over a fixed sequence by dynamic programming and asserts the
 * schedule pays the same number of fetches as the best of them. {@link #cyclicWorstCaseCostsOneExtraFetch} is the
 * hand-computed companion: eight items through seven slots, worked out on paper in the comment, so that a failure says
 * which decision went wrong rather than only that the total moved.
 *
 * <p>The determinism tests exist because the fallback this class must not inherit is two call frames away:
 * {@code InventoryBehavior.getTempHotbarSlot} picks its eviction victim with {@code new Random().nextInt(...)}. A
 * schedule that did the same would still produce a working build and a different plan file every run, which is the one
 * failure mode the whole design is built to exclude.
 */
public class HotbarScheduleTest {

    /**
     * Real vanilla items and block states, headless — and one step beyond what the rest of this package's tests need.
     *
     * <p>{@code Bootstrap.bootStrap()} is enough for {@code Blocks.*}, {@code Items.*}, properties and collision
     * shapes, and it is NOT enough to construct an {@link ItemStack}: since the data-component rework every stack
     * reads {@code Holder.Reference.components()} in its constructor, that binding happens during datapack load rather
     * than during bootstrap, and the failure is a bare {@code NullPointerException: Components not bound yet} three
     * frames inside vanilla. {@code Bootstrap.validate()} does not help and neither does {@code Item.components()} —
     * it is the very accessor that throws.
     *
     * <p>So the binding is done here, with {@link net.minecraft.core.component.DataComponentMap#EMPTY}, which is the
     * smallest thing that satisfies the constructor. The consequence to know about: {@code stack.getMaxStackSize()}
     * then answers 1 for everything. Nothing in {@link HotbarSchedule} reads it — the schedule counts SLOTS and the
     * ledger counts units the caller put in the snapshot — so the fixture is honest for what it is used for and would
     * be a lie for anything that asked how many fit in a stack.
     */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    /**
     * Eight distinct materials — one more than the seven slots the schedule may use, which is the smallest set that
     * forces an eviction at all.
     *
     * <p>A method and not a {@code static final} field, and that is not style: a static initialiser runs at class
     * load, which is BEFORE {@code @BeforeClass}, so touching {@code Items} from one throws out of the registry
     * before {@code Bootstrap.bootStrap()} has ever been called. The failure arrives as an
     * {@code ExceptionInInitializerError} with no test name attached to it.
     */
    private static List<Item> eight() {
        return List.of(Items.STONE, Items.DIRT, Items.OAK_PLANKS, Items.COBBLESTONE,
                Items.SAND, Items.GRAVEL, Items.GLASS, Items.BRICKS);
    }

    // ------------------------------------------------------------------ the optimality claim

    /**
     * Eight items cycled twice through seven slots, worked out by hand.
     *
     * <pre>
     * demand   A B C D E F G H | A B C D E F G H
     * slot     1 2 3 4 5 6 7 7 |             6
     * fetch    * * * * * * * * |             *      = 9
     * </pre>
     *
     * <p>The first seven fill empty slots and cost a fetch each. H at index 7 must evict somebody, and every resident
     * is used again — A at 8 through G at 14 — so Belady takes the one used LAST, which is G, and G is therefore the
     * only item that has to come back. It does, at index 14, and by then A..F are never used again while H is used at
     * 15, so the victim is one of the six dead residents rather than H.
     *
     * <p>Nine is also the floor: eight distinct items cost eight fetches on their own, and the eviction at index 7 is
     * forced to hit an item that is demanded again. No policy can pay less.
     */
    @Test
    public void cyclicWorstCaseCostsOneExtraFetch() {
        List<HotbarSchedule.Demand> demand = demandOf(cycle(eight(), 2));
        HotbarSchedule.Schedule schedule = HotbarSchedule.belady(demand, HotbarSchedule.InventorySnapshot.empty());

        assertEquals("eight distinct items, one of them fetched twice", 9, schedule.fetches());
        assertEquals(9, schedule.swaps().size());

        // The seven initial fetches fill slots 1..7 in order, which is what packs materials from the bottom and
        // leaves slot 7 -- the one throwaway(select=true) appropriates -- empty for longest.
        for (int slot = HotbarSchedule.FIRST_MATERIAL_SLOT; slot <= HotbarSchedule.LAST_MATERIAL_SLOT; slot++) {
            assertEquals(slot, schedule.swaps().get(slot - 1).hotbarSlot());
            assertNull("filling an empty slot evicts nobody",
                    schedule.assignments().get(slot - 1).evicted());
        }
        // H evicts G: the resident whose next use is furthest away, not the oldest and not the least recently used.
        assertSame(Items.BRICKS, schedule.assignments().get(7).item());
        assertSame(Items.GLASS, schedule.assignments().get(7).evicted());
        assertEquals(HotbarSchedule.LAST_MATERIAL_SLOT, schedule.assignments().get(7).hotbarSlot());
        // G comes back at index 14, and by then everything except H is dead, so H survives.
        assertSame(Items.GLASS, schedule.assignments().get(14).item());
        assertNotEquals("the item used at the very end must not be the victim",
                Items.BRICKS, schedule.assignments().get(14).evicted());
    }

    /**
     * The optimality claim itself, against every alternative rather than against an argument.
     *
     * <p>The dynamic programme below is the definition of OPT: at each demand, either the item is resident and the
     * step is free, or it is fetched and — when the bar is full — SOME resident is evicted, all choices explored. The
     * state is the resident SET (slot assignment cannot affect cost, since selecting a slot is a number key and free),
     * which for nine candidate items is at most C(9,7) = 36 sets, so the whole search is exact and instant.
     *
     * <p>The sequence is generated by a fixed linear congruential step rather than by {@code Random}: a test of a
     * determinism guarantee has no business being seeded by anything the JVM chooses.
     */
    @Test
    public void beladyMatchesBruteForceOptimum() {
        List<Item> alphabet = new ArrayList<>(eight());
        alphabet.add(Items.NETHERRACK);      // nine items, two more than the bar holds

        List<Item> sequence = new ArrayList<>();
        long lcg = 12345L;
        for (int step = 0; step < 60; step++) {
            lcg = (lcg * 6364136223846793005L + 1442695040888963407L);
            sequence.add(alphabet.get((int) Math.floorMod(lcg >>> 33, alphabet.size())));
        }

        HotbarSchedule.Schedule schedule =
                HotbarSchedule.belady(demandOf(sequence), HotbarSchedule.InventorySnapshot.empty());
        assertEquals("Belady must pay exactly what an exhaustive search of every eviction policy pays",
                bruteForceOptimum(sequence, alphabet), schedule.fetches());
    }

    /** {@link HotbarSchedule#nextUse} is the oracle the whole policy rests on, and "never again" has to be a case the
     *  caller handles rather than a large number it can accidentally compare. */
    @Test
    public void nextUseReportsNeverAgainAsMinusOne() {
        List<HotbarSchedule.Demand> demand = demandOf(List.of(Items.STONE, Items.DIRT, Items.STONE));
        assertEquals(0, HotbarSchedule.nextUse(demand, 0, Items.STONE));
        assertEquals(2, HotbarSchedule.nextUse(demand, 1, Items.STONE));
        assertEquals(-1, HotbarSchedule.nextUse(demand, 3, Items.STONE));
        assertEquals(-1, HotbarSchedule.nextUse(demand, 0, Items.GLASS));
        assertEquals("a null resident is 'no next use', not an exception",
                -1, HotbarSchedule.nextUse(demand, 0, null));
    }

    /**
     * Ties are the normal case at the end of a build — every remaining resident is "never again" — and they are broken
     * highest slot first, so slot 7 goes before slot 6.
     *
     * <p>Not cosmetic: {@code InventoryBehavior.java:347} has {@code throwaway(select=true, …)} swap into slot 7 and
     * select it, unconditionally and ignoring its own rate limiter's answer. Slot 7 is therefore the slot most likely
     * to be taken from underneath the plan, and evicting it first costs nothing when that never happens.
     */
    @Test
    public void tiesAreBrokenFromTheHighestSlotDown() {
        List<Item> resident = new ArrayList<>();
        for (int slot = 0; slot < HotbarSchedule.HOTBAR_SLOTS; slot++) {
            resident.add(slot == HotbarSchedule.PICKAXE_SLOT ? Items.STONE_PICKAXE
                    : slot == HotbarSchedule.THROWAWAY_SLOT ? Items.COBBLESTONE : eight().get(slot - 1));
        }
        // Nothing is demanded again: every material slot ties at "never".
        assertEquals(HotbarSchedule.LAST_MATERIAL_SLOT,
                HotbarSchedule.victimSlot(resident, List.of(), 0));

        // Now slot 7's resident is the only one with a future. It must survive, and the tie among the other six is
        // again broken from the top: slot 6.
        List<HotbarSchedule.Demand> demand = demandOf(List.of(resident.get(HotbarSchedule.LAST_MATERIAL_SLOT)));
        assertEquals(HotbarSchedule.LAST_MATERIAL_SLOT - 1,
                HotbarSchedule.victimSlot(resident, demand, 0));
    }

    // ------------------------------------------------------------------ the slot convention

    /**
     * Slots 0 and 8 are not ours. {@code InventoryBehavior.onTick:113-122} swaps the best pickaxe into 0 and the first
     * acceptable throwaway into 8 EVERY tick, so a material parked there is a material that will be gone between the
     * plan and the click — and {@code BlockPlaceHelper.matches} compares item identity, so that click is voided with
     * no log line at all.
     */
    @Test
    public void materialsInSlotZeroAndEightDoNotCount() {
        List<ItemStack> slots = emptyInventory();
        slots.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.STONE, 64));
        slots.set(HotbarSchedule.THROWAWAY_SLOT, new ItemStack(Items.DIRT, 64));
        HotbarSchedule.InventorySnapshot inventory = new HotbarSchedule.InventorySnapshot(slots);

        HotbarSchedule.Schedule schedule =
                HotbarSchedule.belady(demandOf(List.of(Items.STONE, Items.DIRT)), inventory);

        assertEquals("both have to be fetched into a slot we own", 2, schedule.fetches());
        for (BuildAction.SwapHotbar swap : schedule.swaps()) {
            assertTrue("slot " + swap.hotbarSlot() + " is not a material slot",
                    swap.hotbarSlot() >= HotbarSchedule.FIRST_MATERIAL_SLOT
                            && swap.hotbarSlot() <= HotbarSchedule.LAST_MATERIAL_SLOT);
        }
        assertEquals("the report still shows what the bot started with",
                Items.STONE, schedule.initial().get(HotbarSchedule.PICKAXE_SLOT));
    }

    /** An item already on a material slot is free — {@code Inventory.setSelectedSlot}, a number key, no throttle and
     *  no stationarity requirement. Only the backpack pull costs, and only the backpack pull emits an action. */
    @Test
    public void anItemAlreadyOnTheBarIsFree() {
        List<ItemStack> slots = emptyInventory();
        slots.set(3, new ItemStack(Items.STONE, 64));
        HotbarSchedule.Schedule schedule = HotbarSchedule.belady(
                demandOf(List.of(Items.STONE, Items.STONE, Items.STONE)),
                new HotbarSchedule.InventorySnapshot(slots));

        assertEquals(0, schedule.fetches());
        assertTrue(schedule.swaps().isEmpty());
        for (HotbarSchedule.Assignment assignment : schedule.assignments()) {
            assertEquals(3, assignment.hotbarSlot());
            assertFalse(assignment.fetched());
        }
    }

    // ------------------------------------------------------------------ determinism

    /**
     * The same demand sequence twice must produce the identical swap list — identical slots, identical items,
     * identical order.
     *
     * <p>This is the regression test section 13 of the plan promises, at the one place a hash order or a
     * {@code Math.random} would be most tempting and least visible: two runs would still build the same farm, and only
     * the plan file would differ.
     */
    @Test
    public void twoRunsProduceTheIdenticalSwapList() {
        List<Item> sequence = cycle(eight(), 3);
        HotbarSchedule.Schedule first = HotbarSchedule.belady(demandOf(sequence), inventoryOf(eight()));
        HotbarSchedule.Schedule second = HotbarSchedule.belady(demandOf(sequence), inventoryOf(eight()));

        assertEquals(first.fetches(), second.fetches());
        assertEquals(first.swaps(), second.swaps());
        assertEquals(first.assignments(), second.assignments());
        assertEquals(first.initial(), second.initial());
    }

    /** Two inventories holding the same items in the same slots, built independently, must schedule identically —
     *  the snapshot is a value and nothing in the schedule may depend on the identity of the list it came in. */
    @Test
    public void anEqualInventorySchedulesEqually() {
        List<Item> sequence = cycle(eight(), 2);
        assertEquals(HotbarSchedule.belady(demandOf(sequence), inventoryOf(eight())).swaps(),
                HotbarSchedule.belady(demandOf(sequence), inventoryOf(new ArrayList<>(eight()))).swaps());
    }

    // ------------------------------------------------------------------ weaving into the order

    /**
     * Each swap lands immediately before the action that needs it, and nothing else moves.
     *
     * <p>Immediately before rather than batched at the head of a layer, because {@code InventoryBehavior} contends for
     * the hotbar every tick: the window between staging an item and clicking with it is exactly the window in which
     * the plan silently stops matching the world.
     */
    @Test
    public void swapsAreWovenImmediatelyBeforeTheActionTheyServe() {
        List<BuildAction> order = List.of(
                placeOf(new BlockPos(0, 0, 0), Blocks.STONE, Items.STONE, 1),
                placeOf(new BlockPos(1, 0, 0), Blocks.DIRT, Items.DIRT, 2),
                placeOf(new BlockPos(2, 0, 0), Blocks.STONE, Items.STONE, 1));

        List<HotbarSchedule.Demand> demand = HotbarSchedule.demandSequence(order);
        assertEquals(3, demand.size());
        HotbarSchedule.Schedule schedule =
                HotbarSchedule.belady(demand, HotbarSchedule.InventorySnapshot.empty());
        List<BuildAction> woven = HotbarSchedule.weave(order, schedule);

        // Two fetches, so five actions: SWAP PLACE SWAP PLACE PLACE. The third placement re-selects stone, which is
        // free, so it gets no swap of its own.
        assertEquals(5, woven.size());
        assertEquals(BuildAction.Kind.SWAP_HOTBAR, woven.get(0).kind());
        assertEquals(BuildAction.Kind.PLACE, woven.get(1).kind());
        assertEquals(BuildAction.Kind.SWAP_HOTBAR, woven.get(2).kind());
        assertEquals(BuildAction.Kind.PLACE, woven.get(3).kind());
        assertEquals(BuildAction.Kind.PLACE, woven.get(4).kind());
        assertEquals("a woven swap carries the layer of the action it serves", 2, woven.get(2).layer());
        assertEquals("and never the sentinel the schedule left it with",
                1, woven.get(0).layer());
    }

    /** Weaving an already-woven order would double every swap, and the second copy would select a slot the first one
     *  had already filled. Cheap to refuse; invisible to debug. */
    @Test(expected = IllegalArgumentException.class)
    public void weavingTwiceIsRefused() {
        List<BuildAction> order = List.of(placeOf(new BlockPos(0, 0, 0), Blocks.STONE, Items.STONE, 1));
        HotbarSchedule.Schedule schedule =
                HotbarSchedule.belady(HotbarSchedule.demandSequence(order), HotbarSchedule.InventorySnapshot.empty());
        HotbarSchedule.weave(HotbarSchedule.weave(order, schedule), schedule);
    }

    /** A break demands nothing: it goes out holding the pickaxe, and the pickaxe lives in slot 0 under
     *  {@code InventoryBehavior}'s own upkeep. Scheduling it would be scheduling a slot we do not own. */
    @Test
    public void breaksAndInteractionsDemandNoMaterialSlot() {
        List<BuildAction> order = List.of(
                breakOf(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState(), 1),
                placeOf(new BlockPos(1, 0, 0), Blocks.STONE, Items.STONE, 1));
        List<HotbarSchedule.Demand> demand = HotbarSchedule.demandSequence(order);

        assertEquals(1, demand.size());
        assertEquals("the demand belongs to the placement, at its own index in the order", 1, demand.get(0).actionIndex());
        assertSame(Items.STONE, demand.get(0).item());
    }

    /**
     * Scaffold uses the two executor-owned slots and therefore consumes none of the seven material-cache slots.
     * Both ordinary materials begin in the backpack to exercise real fetches; repeating the complete calculation
     * must produce the same slots, swaps and woven order.
     */
    @Test
    public void scaffoldSlotsStayOutsideBeladyAndBackpackWeavingIsDeterministic() {
        BlockPos helper = new BlockPos(0, 1, 0);
        List<BuildAction> order = List.of(
                scaffoldOf(helper, Blocks.COBBLESTONE, Items.COBBLESTONE),
                placeOf(new BlockPos(0, 0, 0), Blocks.STONE, Items.STONE, 1),
                removeScaffoldOf(helper, Blocks.COBBLESTONE.defaultBlockState()),
                placeOf(new BlockPos(1, 0, 0), Blocks.DIRT, Items.DIRT, 1));

        List<HotbarSchedule.Demand> demands = HotbarSchedule.demandSequence(order);
        assertEquals("only the two schematic materials belong to Belady", 2, demands.size());
        assertEquals(1, demands.get(0).actionIndex());
        assertSame(Items.STONE, demands.get(0).item());
        assertEquals(3, demands.get(1).actionIndex());
        assertSame(Items.DIRT, demands.get(1).item());

        List<ItemStack> slots = emptyInventory();
        slots.set(HotbarSchedule.THROWAWAY_SLOT, new ItemStack(Items.COBBLESTONE, 64));
        slots.set(HotbarSchedule.HOTBAR_SLOTS, new ItemStack(Items.STONE, 64));
        slots.set(HotbarSchedule.HOTBAR_SLOTS + 1, new ItemStack(Items.DIRT, 64));
        HotbarSchedule.InventorySnapshot snapshot = new HotbarSchedule.InventorySnapshot(slots);

        HotbarSchedule.Schedule first = HotbarSchedule.belady(demands, snapshot);
        HotbarSchedule.Schedule second = HotbarSchedule.belady(demands, snapshot);
        List<BuildAction> firstWoven = HotbarSchedule.weave(order, first);
        List<BuildAction> secondWoven = HotbarSchedule.weave(order, second);
        assertEquals(first, second);
        assertEquals(firstWoven, secondWoven);
        assertEquals("both backpack materials are fetched; scaffold is not", 2, first.fetches());

        boolean sawScaffold = false;
        boolean sawRemoval = false;
        for (BuildAction action : firstWoven) {
            if (action instanceof BuildAction.PlaceScaffold scaffold) {
                sawScaffold = true;
                assertEquals(HotbarSchedule.THROWAWAY_SLOT, scaffold.handSlot());
            } else {
                assertNotEquals("slot 8 is reserved for PLACE_SCAFFOLD: " + action.describe(),
                        HotbarSchedule.THROWAWAY_SLOT, action.handSlot());
            }
            if (action instanceof BuildAction.RemoveScaffold removed) {
                sawRemoval = true;
                assertEquals(HotbarSchedule.PICKAXE_SLOT, removed.handSlot());
            }
        }
        assertTrue(sawScaffold);
        assertTrue(sawRemoval);
    }

    /** Even a malformed pre-woven normal action cannot carry the throwaway slot past the schedule boundary. */
    @Test(expected = IllegalArgumentException.class)
    public void theThrowawaySlotCannotEscapeOntoANormalAction() {
        BuildAction.Break malformed = new BuildAction.Break(
                new BlockPos(0, 0, 0),
                new BlockPos(0, 1, 0),
                Vec3.ZERO,
                Vec3.ZERO,
                new Rotation(0.0F, 0.0F),
                Direction.UP,
                Blocks.STONE.defaultBlockState(),
                BreakPlanner.drops(Blocks.STONE.defaultBlockState()),
                true,
                HotbarSchedule.THROWAWAY_SLOT,
                0);
        HotbarSchedule.weave(List.of(malformed),
                HotbarSchedule.belady(List.of(), HotbarSchedule.InventorySnapshot.empty()));
    }

    // ------------------------------------------------------------------ capacity and the ledger

    /**
     * The plan-time capacity check: 36 inventory slots, two of them reserved, one per distinct placeable type. A
     * schematic whose material set does not fit cannot be built from one inventory load however good the schedule is,
     * and the useful moment to learn that is before three hours of walking rather than after two.
     */
    @Test
    public void capacityNamesWhatWillNotFit() {
        List<Block> many = firstPlaceableBlocks(40);
        Map<BlockPos, BlockState> cells = new HashMap<>();
        for (int index = 0; index < many.size(); index++) {
            cells.put(new BlockPos(index, 0, 0), many.get(index).defaultBlockState());
        }
        SchematicView view = viewOf(cells, new BlockPos(0, 0, 0), new BlockPos(many.size() - 1, 0, 0));

        HotbarSchedule.CapacityCheck check = HotbarSchedule.capacity(view, V3Settings.defaults(),
                HotbarSchedule.InventorySnapshot.empty(), Items.COBBLESTONE);

        assertEquals(40, check.distinctPlaceableTypes());
        assertEquals(HotbarSchedule.RESERVED_SLOTS, check.reserved());
        assertFalse(check.fits());
        assertEquals("36 slots minus the pickaxe and the throwaway leaves room for 34",
                40 - (HotbarSchedule.INVENTORY_SLOTS - HotbarSchedule.RESERVED_SLOTS), check.overflow().size());
        assertTrue(check.describe().contains("do not fit"));
    }

    /** The same check on a schematic that fits says so without naming anything, and reserves only the pickaxe when no
     *  helper block type was named. */
    @Test
    public void capacityFitsForAnOrdinaryBuild() {
        Map<BlockPos, BlockState> cells = new HashMap<>();
        cells.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        cells.put(new BlockPos(1, 0, 0), Blocks.GLASS.defaultBlockState());
        SchematicView view = viewOf(cells, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));

        HotbarSchedule.CapacityCheck check = HotbarSchedule.capacity(view, V3Settings.defaults(),
                HotbarSchedule.InventorySnapshot.empty(), null);

        assertTrue(check.fits());
        assertEquals(2, check.distinctPlaceableTypes());
        assertEquals("no scaffold item named, so only the pickaxe is reserved", 1, check.reserved());
        assertTrue(check.overflow().isEmpty());
    }

    /**
     * The ledger reports the PEAK simultaneous requirement, not the total ever placed, and the two differ exactly
     * where scaffold is involved: a layer that puts four hundred helper blocks in and takes all four hundred back out
     * needs however many stand at once, and reporting four hundred would demand a stack the build never holds.
     */
    @Test
    public void theLedgerCountsThePeakAndNotTheTotal() {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);
        List<BuildAction> order = List.of(
                scaffoldOf(first, Blocks.COBBLESTONE, Items.COBBLESTONE),
                removeScaffoldOf(first, Blocks.COBBLESTONE.defaultBlockState()),
                scaffoldOf(second, Blocks.COBBLESTONE, Items.COBBLESTONE),
                removeScaffoldOf(second, Blocks.COBBLESTONE.defaultBlockState()));

        List<PlanReport.MaterialNeed> ledger =
                HotbarSchedule.materialLedger(order, HotbarSchedule.InventorySnapshot.empty());

        assertEquals(1, ledger.size());
        assertSame(Items.COBBLESTONE, ledger.get(0).item());
        assertEquals("two placed, never at the same time", 1, ledger.get(0).needed());
    }

    /**
     * Glass drops nothing, so a schematic cell that has to be broken and put back costs one MORE unit than the cell
     * count says. Booked here, at plan time, because mid-run it presents as a build that stops two thousand cells
     * later for a reason nobody can connect to a break at cell four.
     */
    @Test
    public void alossyBreakBooksAReplacement() {
        BlockPos cell = new BlockPos(0, 0, 0);
        List<BuildAction> order = List.of(
                breakOf(cell, Blocks.GLASS.defaultBlockState(), 0),
                placeOf(cell, Blocks.GLASS, Items.GLASS, 0));

        List<PlanReport.MaterialNeed> ledger =
                HotbarSchedule.materialLedger(order, HotbarSchedule.InventorySnapshot.empty());

        assertEquals(1, ledger.size());
        assertSame(Items.GLASS, ledger.get(0).item());
        assertEquals(1, ledger.get(0).forReplacement());
        assertEquals("nothing in the snapshot covers it", 1, ledger.get(0).shortfall());
    }

    // ------------------------------------------------------- the creative ledger

    /**
     * The bench's floor9, exactly: 81 stone cells against the ONE stack the bench deliberately loads, in a creative
     * world. Survival is right to call that 17 short; creative is not, because instabuild consumes nothing and the
     * stack cannot run down. The planner used to refuse the build before the bot moved — {@code 81 / 81 cells proven},
     * {@code blockers=0}, {@code placed=0}, twenty ticks and a stop — and the same shape refused doorway (71 stone)
     * and ringbig (120 glass).
     *
     * <p>Both branches are asserted from one order, because the number that matters is the DIFFERENCE between them
     * and a test that only pinned the creative side would pass just as happily if survival had been broken too.
     */
    @Test
    public void aCreativePlacementChargesPresenceAndSurvivalStillChargesQuantity() {
        List<BuildAction> order = new ArrayList<>();
        for (int x = 0; x < 81; x++) {
            order.add(placeOf(new BlockPos(x, 0, 0), Blocks.STONE, Items.STONE, 0));
        }

        List<PlanReport.MaterialNeed> survival =
                HotbarSchedule.materialLedger(order, oneStackOf(Items.STONE, false));
        assertEquals(1, survival.size());
        assertEquals("one unit leaves the stack per placement", 81, survival.get(0).needed());
        assertEquals("64 held against 81 placements", 17, survival.get(0).shortfall());

        List<PlanReport.MaterialNeed> creative =
                HotbarSchedule.materialLedger(order, oneStackOf(Items.STONE, true));
        assertEquals(1, creative.size());
        assertSame(Items.STONE, creative.get(0).item());
        assertEquals("instabuild consumes nothing, so the question is presence and not quantity",
                1, creative.get(0).needed());
        assertEquals("a creative stack is unbounded", 0, creative.get(0).shortfall());
    }

    /** Narrowed, never disabled: a material the snapshot does not hold at all is still named, in creative too. A
     *  build cannot place a block the bot is not carrying however free the placement is. */
    @Test
    public void aCreativeLedgerStillNamesAMaterialTheBotDoesNotCarry() {
        List<BuildAction> order = List.of(
                placeOf(new BlockPos(0, 0, 0), Blocks.STONE, Items.STONE, 0),
                placeOf(new BlockPos(1, 0, 0), Blocks.GLASS, Items.GLASS, 0));

        List<PlanReport.MaterialNeed> ledger =
                HotbarSchedule.materialLedger(order, oneStackOf(Items.STONE, true));

        PlanReport.MaterialNeed glass = ledger.stream()
                .filter(need -> need.item() == Items.GLASS).findFirst().orElseThrow();
        assertEquals(1, glass.needed());
        assertEquals("carried by nothing in the snapshot", 1, glass.shortfall());
        assertEquals("the stone is held, so it is not a shortfall", 0,
                ledger.stream().filter(need -> need.item() == Items.STONE).findFirst().orElseThrow().shortfall());
    }

    /** A break that drops nothing costs a second unit out of the stack in survival. In creative there is no stack to
     *  take it out of, so the replacement column is zero rather than a warning about a world that does not apply. */
    @Test
    public void aCreativeLossyBreakBooksNoReplacement() {
        BlockPos cell = new BlockPos(0, 0, 0);
        List<BuildAction> order = List.of(
                breakOf(cell, Blocks.GLASS.defaultBlockState(), 0),
                placeOf(cell, Blocks.GLASS, Items.GLASS, 0));

        List<PlanReport.MaterialNeed> ledger =
                HotbarSchedule.materialLedger(order, oneStackOf(Items.GLASS, true));

        assertEquals(1, ledger.size());
        assertEquals(0, ledger.get(0).forReplacement());
        assertEquals(1, ledger.get(0).needed());
        assertEquals(0, ledger.get(0).shortfall());
    }

    /** The peak rule is a survival rule about a stack going down and coming back up. Creative has neither, and the
     *  answer is one either way — which is what stops a repeated place/remove cycle from charging a second unit. */
    @Test
    public void aCreativeScaffoldCycleStillCostsOneUnit() {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);
        List<BuildAction> order = List.of(
                scaffoldOf(first, Blocks.COBBLESTONE, Items.COBBLESTONE),
                removeScaffoldOf(first, Blocks.COBBLESTONE.defaultBlockState()),
                scaffoldOf(second, Blocks.COBBLESTONE, Items.COBBLESTONE),
                removeScaffoldOf(second, Blocks.COBBLESTONE.defaultBlockState()));

        List<PlanReport.MaterialNeed> ledger =
                HotbarSchedule.materialLedger(order, oneStackOf(Items.COBBLESTONE, true));

        assertEquals(1, ledger.size());
        assertEquals(1, ledger.get(0).needed());
    }

    // ------------------------------------------------------------------ helpers

    /** One full stack in the backpack and nothing else — the bench's deliberate one-stack-per-type loadout, in the
     *  game mode the caller names. */
    private static HotbarSchedule.InventorySnapshot oneStackOf(Item item, boolean instabuild) {
        List<ItemStack> slots = emptyInventory();
        slots.set(HotbarSchedule.HOTBAR_SLOTS, new ItemStack(item, 64));
        return new HotbarSchedule.InventorySnapshot(slots, instabuild);
    }

    /** The exhaustive optimum: a dynamic programme over resident SETS, which is the definition of OPT rather than an
     *  approximation of it. Slot assignment is irrelevant to cost, so a set is a complete state. */
    private static int bruteForceOptimum(List<Item> sequence, List<Item> alphabet) {
        Map<Integer, Integer> states = new HashMap<>();
        states.put(0, 0);
        for (Item wanted : sequence) {
            int bit = 1 << alphabet.indexOf(wanted);
            Map<Integer, Integer> next = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : states.entrySet()) {
                int resident = entry.getKey();
                int cost = entry.getValue();
                if ((resident & bit) != 0) {
                    merge(next, resident, cost);
                    continue;
                }
                if (Integer.bitCount(resident) < HotbarSchedule.MATERIAL_SLOTS) {
                    merge(next, resident | bit, cost + 1);
                    continue;
                }
                for (int victim = 0; victim < alphabet.size(); victim++) {
                    int victimBit = 1 << victim;
                    if ((resident & victimBit) != 0) {
                        merge(next, (resident & ~victimBit) | bit, cost + 1);
                    }
                }
            }
            states = next;
        }
        // Iterating a HashMap is fine HERE and only here: this is a test taking a minimum over values, which no order
        // can change. Nothing in the production path may do the same.
        int best = Integer.MAX_VALUE;
        for (int cost : states.values()) {
            best = Math.min(best, cost);
        }
        return best;
    }

    private static void merge(Map<Integer, Integer> states, int resident, int cost) {
        states.merge(resident, cost, Math::min);
    }

    /** The alphabet repeated {@code times} over, which is the pattern that makes eviction unavoidable. */
    private static List<Item> cycle(List<Item> alphabet, int times) {
        List<Item> sequence = new ArrayList<>(alphabet.size() * times);
        for (int round = 0; round < times; round++) {
            sequence.addAll(alphabet);
        }
        return sequence;
    }

    /** A demand sequence whose action indices are the sequence indices — enough for everything but {@link
     *  HotbarSchedule#weave}, which needs a real order. */
    private static List<HotbarSchedule.Demand> demandOf(List<Item> items) {
        List<HotbarSchedule.Demand> demand = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            demand.add(new HotbarSchedule.Demand(index, items.get(index)));
        }
        return demand;
    }

    private static List<ItemStack> emptyInventory() {
        List<ItemStack> slots = new ArrayList<>(HotbarSchedule.INVENTORY_SLOTS);
        for (int index = 0; index < HotbarSchedule.INVENTORY_SLOTS; index++) {
            slots.add(ItemStack.EMPTY);
        }
        return slots;
    }

    /** Everything in the backpack, nothing on the bar — the honest starting point for a build whose materials were
     *  just collected. */
    private static HotbarSchedule.InventorySnapshot inventoryOf(List<Item> items) {
        List<ItemStack> slots = emptyInventory();
        for (int index = 0; index < items.size(); index++) {
            slots.set(HotbarSchedule.HOTBAR_SLOTS + index, new ItemStack(items.get(index), 64));
        }
        return new HotbarSchedule.InventorySnapshot(slots);
    }

    /** The first {@code count} registry blocks that have an item form. Registry iteration is insertion-ordered and
     *  therefore stable, which is what makes this a fixture rather than a lottery. */
    private static List<Block> firstPlaceableBlocks(int count) {
        List<Block> blocks = new ArrayList<>(count);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.asItem() == Items.AIR || block.defaultBlockState().isAir()) {
                continue;
            }
            blocks.add(block);
            if (blocks.size() == count) {
                break;
            }
        }
        return blocks;
    }

    /** A schematic view over a hand-drawn map of cells, against an all-air world. */
    private static SchematicView viewOf(Map<BlockPos, BlockState> cells, BlockPos min, BlockPos max) {
        PredictedWorld world = PredictedWorld.capture(
                (x, y, z) -> Blocks.AIR.defaultBlockState(), (x, z) -> true, min, max, 1);
        Map<Long, BlockState> byLocal = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> cell : cells.entrySet()) {
            BlockPos local = cell.getKey().subtract(min);
            byLocal.put(PlacementGeometry.positionKey(local.getX(), local.getY(), local.getZ()),
                    cell.getValue());
        }
        ISchematic schematic = new ISchematic() {

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                BlockState desired = byLocal.get(PlacementGeometry.positionKey(x, y, z));
                return desired == null ? Blocks.AIR.defaultBlockState() : desired;
            }

            @Override
            public int widthX() {
                return max.getX() - min.getX() + 1;
            }

            @Override
            public int heightY() {
                return max.getY() - min.getY() + 1;
            }

            @Override
            public int lengthZ() {
                return max.getZ() - min.getZ() + 1;
            }
        };
        return SchematicView.capture("test", schematic, new Vec3i(min.getX(), min.getY(), min.getZ()), world,
                List.of());
    }

    private static BuildAction.Place placeOf(BlockPos cell, Block block, Item item, int layer) {
        return new BuildAction.Place(solutionOf(cell, block, item), true, 1, layer);
    }

    private static BuildAction.PlaceScaffold scaffoldOf(BlockPos cell, Block block, Item item) {
        return new BuildAction.PlaceScaffold(solutionOf(cell, block, item), cell.above(), true, 1, 0);
    }

    private static BuildAction.Break breakOf(BlockPos cell, BlockState expected, int layer) {
        return new BuildAction.Break(cell, cell.above(), Vec3.ZERO, Vec3.ZERO, new Rotation(0.0F, 0.0F),
                Direction.UP, expected, BreakPlanner.drops(expected), true, HotbarSchedule.PICKAXE_SLOT, layer);
    }

    private static BuildAction.RemoveScaffold removeScaffoldOf(BlockPos cell, BlockState expected) {
        return new BuildAction.RemoveScaffold(cell, cell.above(), Vec3.ZERO, Vec3.ZERO, new Rotation(0.0F, 0.0F),
                expected, true, HotbarSchedule.PICKAXE_SLOT, 0);
    }

    /** A solution with only the fields the schedule and the ledger read filled in honestly; the geometry is not what
     *  this test is about and a fabricated aim point would only make the assertions harder to read. */
    private static PlacementSolution solutionOf(BlockPos cell, Block block, Item item) {
        BlockState state = block.defaultBlockState();
        return new PlacementSolution(cell, state, cell.above(), Vec3.ZERO, cell.below(), Direction.UP, Vec3.ZERO,
                new Rotation(0.0F, 0.0F), PlacementSolution.UNCONSTRAINED_MARGIN, state, item);
    }
}
