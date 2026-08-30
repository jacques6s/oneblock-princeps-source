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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Which item sits in which hotbar slot at every point of the frozen order, computed by Belady's optimal replacement
 * over the whole demand sequence.
 *
 * <h2>Why this is a solved problem here and a heuristic everywhere else</h2>
 *
 * <p>etz-basalt has 39 block types, about 34 of them placeable, and seven usable hotbar slots. Swapping is not
 * avoidable; the only question is how often. The classical answer is that you cannot do better than a heuristic,
 * because eviction has to guess which item will be wanted again soonest and the future is unknown.
 *
 * <p>Here the future is known. The order was frozen before the bot took a step, so the complete sequence of material
 * demands is an input rather than a stream, and Belady's rule — evict the resident whose NEXT USE lies furthest in
 * the future — is provably minimal in the number of fetches. V2 could not have this at any price: it chose the next
 * cell every tick, so it never had a demand sequence to optimise over. This is the clearest case in the whole design
 * of the frozen order buying something a reactive builder cannot buy at all.
 *
 * <p>What is being minimised is FETCHES, not slot selections. Selecting an item already on the hotbar is
 * {@code Inventory.setSelectedSlot} — no throttle, no stationarity requirement, no hook anywhere in the repo, and
 * therefore free. Pulling one out of the backpack is {@code InventoryBehavior.requestSwapWithHotBar}
 * ({@code InventoryBehavior.java:182-200}), which is refused outright while
 * {@code ticksSinceLastInventoryMove < ticksBetweenInventoryMoves} and, under
 * {@code inventoryMoveOnlyIfStationary}, refused again until the bot has come to a halt — so a fetch costs a stop and
 * a wait, budgeted at {@link BuildPlan#TICKS_PER_HOTBAR_SWAP} ticks. Only the fetches emit a
 * {@link BuildAction.SwapHotbar}.
 *
 * <p>The fetch does NOT open a screen: it is one {@code ContainerInput.SWAP} click on the always-open
 * {@code inventoryMenu}. Worth stating because the opposite belief leads straight to the deadlock in
 * {@code InventoryBehavior.onTick:101-104} — a process that holds {@code CLICK_RIGHT} down every tick stops the move
 * clock advancing, so the rate limiter refuses every swap forever and the plan waits on a fetch that can never happen.
 *
 * <h2>The slot convention is not ours and must not be redefined</h2>
 *
 * <p>{@code InventoryBehavior.getTempHotbarSlot} states it at {@code InventoryBehavior.java:146} — "we're using 0 and
 * 8 for pickaxe and throwaway" — and, more to the point, {@code InventoryBehavior.onTick} ENFORCES it every single
 * tick: it swaps the best pickaxe into slot 0 and the first acceptable throwaway into slot 8 whenever they are not
 * already there. A plan that parked a material in either slot would have it evicted underneath the executor between
 * the plan and the click, and {@code BlockPlaceHelper.matches} compares item identity — so the click would go out
 * against the wrong item and be silently voided with no log line. Slots 1 through 7 are ours; 0 and 8 are not.
 *
 * <p>Slot 7 carries a second-order hazard worth stating rather than discovering: {@code throwaway(select=true, …)}
 * at {@code InventoryBehavior.java:347} swaps into slot 7 and selects it. That path only runs when the pathfinder
 * needs a throwaway and slot 8 does not have one, but when it runs it takes slot 7 without asking. Belady should
 * therefore prefer slot 7 as the eviction victim among equals, which costs nothing when the hazard does not fire.
 * Both tiebreaks below push the same way: empty slots are filled from 1 upward, occupied ties are evicted from 7
 * downward, so slot 7 is the last slot to acquire a material and the first to lose one.
 *
 * <h2>Determinism</h2>
 *
 * <p>{@code InventoryBehavior.getTempHotbarSlot} picks its victim with {@code new Random().nextInt(...)}. Nothing in
 * this class may inherit that. Belady leaves genuine ties — two residents both never used again — and every one of
 * them is broken by the fixed rule in {@link #victimSlot}: never Math.random, never hash order, never the clock.
 */
public final class HotbarSchedule {

    /** Enforced by {@code InventoryBehavior.onTick}: the best pickaxe is swapped here whenever it is not here. Never
     *  a material slot. */
    public static final int PICKAXE_SLOT = 0;

    /** Enforced by {@code InventoryBehavior.onTick}: the first acceptable throwaway, unless a totem is protecting it.
     *  This is where the scaffold block lives, which is why the scaffold's item must also be an acceptable throwaway
     *  — otherwise the two mechanisms fight over one slot every tick. */
    public static final int THROWAWAY_SLOT = 8;

    /** First slot the schedule may use. */
    public static final int FIRST_MATERIAL_SLOT = 1;

    /** Last slot the schedule may use. Also the slot {@code throwaway(select=true)} appropriates — see the class
     *  javadoc; prefer it as the eviction victim among equals. */
    public static final int LAST_MATERIAL_SLOT = 7;

    /** Seven. Nine minus the pickaxe minus the throwaway, and the whole reason this class exists. */
    public static final int MATERIAL_SLOTS = LAST_MATERIAL_SLOT - FIRST_MATERIAL_SLOT + 1;

    /** The nine hotbar slots, as {@link #belady} models them. */
    public static final int HOTBAR_SLOTS = 9;

    /** A survival inventory: nine hotbar slots plus twenty-seven main slots. Armour and offhand are excluded, as
     *  they are in {@code Inventory.getNonEquipmentItems()}, because the builder cannot place from either. */
    public static final int INVENTORY_SLOTS = 36;

    /** Slots {@link #capacity} takes off the top before counting material types: the pickaxe every {@code BREAK} uses
     *  and the throwaway the scaffold comes out of. Both are held by {@code InventoryBehavior} against our wishes, so
     *  they are a cost of doing business rather than a choice. */
    public static final int RESERVED_SLOTS = 2;

    /**
     * The layer a swap carries before {@link #weave} stamps it.
     *
     * <p>{@link #belady} works on a demand sequence, which knows action INDICES and nothing else — the layer is a
     * property of the order, and the order is not one of its arguments. Rather than have the schedule quietly claim
     * layer 0, unstamped swaps carry a value no schematic can produce, so a swap that escapes into a plan without
     * passing through {@link #weave} shows up in {@code plan.txt} as belonging to no layer at all instead of silently
     * joining the first one.
     */
    public static final int UNSTAMPED_LAYER = Integer.MIN_VALUE;

    /** {@link BuildAction.SwapHotbar#inventorySlotHint} for an item the snapshot does not hold anywhere. Not an
     *  error here — the capacity check and the material ledger are where a missing material is reported — but the
     *  executor has to be told to search rather than to trust a fabricated index. */
    public static final int NOT_IN_INVENTORY = -1;

    private HotbarSchedule() {
    }

    /**
     * The inventory as the planner sees it: a frozen list of 36 stacks, index 0-8 the hotbar, 9-35 the backpack —
     * the same layout as {@code Inventory.getNonEquipmentItems()}.
     *
     * <p>Frozen for the same reason {@link V3Settings} is: a plan proven against one inventory and executed against
     * another is not a proof. Survival consumption during a run makes this snapshot go stale, which is a known and
     * deliberately deferred gap (plan Z2) — an execution problem, not a planning one, and it belongs after this.
     */
    public record InventorySnapshot(List<ItemStack> slots, boolean instabuild) {

        public InventorySnapshot {
            slots = List.copyOf(slots);
        }

        /** Survival snapshot, retained for every existing caller. */
        public InventorySnapshot(List<ItemStack> slots) {
            this(slots, false);
        }

        /** An inventory holding nothing at all — the starting point for a test that wants to state its contents one
         *  slot at a time, and the honest answer for a bot whose inventory could not be read. */
        public static InventorySnapshot empty() {
            List<ItemStack> slots = new ArrayList<>(INVENTORY_SLOTS);
            for (int index = 0; index < INVENTORY_SLOTS; index++) {
                slots.add(ItemStack.EMPTY);
            }
            return new InventorySnapshot(slots, false);
        }

        /** The nine hotbar stacks, index equal to slot — what {@link PlacementOracle#solve} takes. Padded to nine
         *  when the snapshot is short, because every caller indexes it by slot number and a shorter list turns a
         *  missing stack into an {@link IndexOutOfBoundsException} three call frames away from the cause. */
        public List<ItemStack> hotbar() {
            List<ItemStack> hotbar = new ArrayList<>(HOTBAR_SLOTS);
            for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
                hotbar.add(slot(slot));
            }
            return List.copyOf(hotbar);
        }

        /** The stack in one slot, or {@link ItemStack#EMPTY}. */
        public ItemStack slot(int index) {
            if (index < 0 || index >= this.slots.size()) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = this.slots.get(index);
            return stack == null ? ItemStack.EMPTY : stack;
        }

        /** Total units of an item across all 36 slots — the {@code available} figure of
         *  {@link PlanReport.MaterialNeed}. */
        public int count(Item item) {
            if (item == null) {
                return 0;
            }
            int total = 0;
            for (ItemStack stack : this.slots) {
                if (stack != null && !stack.isEmpty() && stack.getItem() == item) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        /** Every distinct item held, ordered by registry id so the report prints the same list twice. */
        public List<Item> distinctItems() {
            List<Item> distinct = new ArrayList<>();
            for (ItemStack stack : this.slots) {
                if (stack != null && !stack.isEmpty() && !distinct.contains(stack.getItem())) {
                    distinct.add(stack.getItem());
                }
            }
            // Registry id and not insertion order: the same inventory read from two different slot arrangements must
            // print the same list, or two runs of the same build produce two different reports for one bot.
            distinct.sort(Comparator.comparing(HotbarSchedule::itemKey));
            return List.copyOf(distinct);
        }

        /** The lowest slot holding this item, or empty — the {@code inventorySlotHint} of
         *  {@link BuildAction.SwapHotbar}, a hint because the executor re-locates the stack before it clicks. */
        public OptionalInt firstSlotWith(Item item) {
            if (item == null) {
                return OptionalInt.empty();
            }
            for (int index = 0; index < this.slots.size(); index++) {
                ItemStack stack = this.slots.get(index);
                if (stack != null && !stack.isEmpty() && stack.getItem() == item) {
                    return OptionalInt.of(index);
                }
            }
            return OptionalInt.empty();
        }

        /** How many of the 36 slots hold something. */
        public int occupiedSlots() {
            int occupied = 0;
            for (ItemStack stack : this.slots) {
                if (stack != null && !stack.isEmpty()) {
                    occupied++;
                }
            }
            return occupied;
        }
    }

    /**
     * One request for an item at one point in the frozen order.
     *
     * @param actionIndex position in the order — strictly increasing across a demand sequence, and the key Belady
     *                    measures "furthest in the future" against
     * @param item        what has to be in hand for that action
     * @param afterUse    what remains resident in that hotbar slot after the action; normally {@code item}, but a
     *                    filled bucket becomes {@link Items#BUCKET}
     */
    public record Demand(int actionIndex, Item item, Item afterUse) {

        /** Ordinary non-transforming demand. Kept so callers constructing abstract Belady sequences stay concise. */
        public Demand(int actionIndex, Item item) {
            this(actionIndex, item, item);
        }
    }

    /**
     * What the hotbar holds when one demand is served.
     *
     * @param fetched true when this demand required pulling the item out of the backpack — the event Belady
     *                minimises, and the only kind that emits a {@link BuildAction.SwapHotbar}
     * @param evicted the item this fetch displaced, or {@code null} when the slot was empty; carried so the trace can
     *                show WHICH resident was chosen and a test can assert the Belady decision rather than the count
     */
    public record Assignment(int demandIndex, int actionIndex, Item item, int hotbarSlot, boolean fetched,
                             Item evicted) {
    }

    /**
     * The finished schedule.
     *
     * @param assignments one per demand, in order
     * @param swaps       the {@link BuildAction.SwapHotbar} actions to weave into the plan, in order
     * @param fetches     how many backpack pulls the schedule costs — provably minimal for this demand sequence, and
     *                    therefore also the ceiling on what any further material grouping could save. A later
     *                    optimisation that claims to beat this number is measuring something else
     * @param initial     the hotbar layout the schedule assumes at action 0, index equal to slot, {@code null} for
     *                    empty; the executor stages it before the first action rather than discovering it
     */
    public record Schedule(List<Assignment> assignments, List<BuildAction.SwapHotbar> swaps, int fetches,
                           List<Item> initial) {

        public Schedule {
            assignments = List.copyOf(assignments);
            swaps = List.copyOf(swaps);
            // NOT List.copyOf: initial is indexed BY SLOT and an empty slot is a null, which List.copyOf rejects
            // outright. An unmodifiable copy gives the same immutability without forcing the caller to invent a
            // sentinel item for "nothing here" -- and a sentinel is exactly how a plan ends up fetching air.
            initial = Collections.unmodifiableList(new ArrayList<>(initial));
        }
    }

    /**
     * The plan-time capacity verdict.
     *
     * @param fits            {@code distinctPlaceableTypes + reserved <= }{@link #INVENTORY_SLOTS}
     * @param overflow        the items that do not fit, named, ordered by demand descending then by registry id —
     *                        because "it does not fit" is not a decision and "these four types do not fit" is
     * @param materials       per-item need against availability, including replacements booked for lossy breaks
     */
    public record CapacityCheck(boolean fits, int distinctPlaceableTypes, int requiredMaterialSlots, int reserved,
                                int inventorySlots, List<Item> overflow, List<PlanReport.MaterialNeed> materials) {

        public CapacityCheck {
            overflow = List.copyOf(overflow);
            materials = List.copyOf(materials);
        }

        /** The report's line for this check, whichever way it went. */
        public String describe() {
            if (this.fits) {
                return this.requiredMaterialSlots + " material slots (" + this.distinctPlaceableTypes
                        + " placeable types) + " + this.reserved + " reserved fit in "
                        + this.inventorySlots + " inventory slots";
            }
            StringBuilder out = new StringBuilder(this.requiredMaterialSlots + " material slots ("
                    + this.distinctPlaceableTypes + " placeable types) + " + this.reserved
                    + " reserved do not fit in " + this.inventorySlots + " inventory slots; "
                    + this.overflow.size() + " will not fit:");
            for (Item item : this.overflow) {
                out.append(' ').append(BuildAction.describeItem(item));
            }
            return out.toString();
        }
    }

    // ------------------------------------------------------------------- the schedule

    /**
     * Extract the demand sequence from a frozen order: every action that needs a specific item in hand, in order.
     *
     * <p>Schematic placements and bucket fills demand their item. A scaffold placement does not: its one permitted
     * helper item lives in the fixed {@link #THROWAWAY_SLOT}, outside the seven slots Bélády owns. Breaks and scaffold
     * removals use the fixed {@link #PICKAXE_SLOT}; interactions and sign writing also demand a non-placing hand and
     * appear here as no demand at all. Getting either fixed-slot case into this sequence reduces the cache to six
     * material slots and makes its claimed optimum an optimum for the wrong machine.
     */
    public static List<Demand> demandSequence(List<BuildAction> order) {
        List<Demand> demands = new ArrayList<>();
        for (int index = 0; index < order.size(); index++) {
            Item item = demandedItem(order.get(index));
            if (item != null) {
                demands.add(new Demand(index, item, itemAfterUse(order.get(index), item)));
            }
        }
        return List.copyOf(demands);
    }

    /**
     * Belady / OPT over the frozen demand sequence. Minimal in {@link Schedule#fetches}, and the proof is the
     * standard one: any other policy's first divergence from OPT can be exchanged for OPT's choice without
     * increasing the fetch count.
     *
     * @param demand    from {@link #demandSequence}; {@code actionIndex} must be non-decreasing
     * @param inventory what is already on the hotbar at action 0 — a free head start OPT must be allowed to use, and
     *                  the reason the schedule is not simply "the first seven distinct items"
     */
    public static Schedule belady(List<Demand> demand, InventorySnapshot inventory) {
        List<ItemStack> hotbar = inventory.hotbar();
        List<Item> resident = new ArrayList<>(HOTBAR_SLOTS);
        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            ItemStack stack = hotbar.get(slot);
            // Slots 0 and 8 are recorded so the report can show what the bot started with, but residentSlot never
            // looks at them: a material sitting in the pickaxe or throwaway slot is a material InventoryBehavior is
            // about to evict, and a schedule that counted it as free would be one silent voided click per use.
            resident.add(stack.isEmpty() ? null : stack.getItem());
        }
        List<Item> initial = new ArrayList<>(resident);

        List<Assignment> assignments = new ArrayList<>(demand.size());
        List<BuildAction.SwapHotbar> swaps = new ArrayList<>();
        int fetches = 0;
        for (int index = 0; index < demand.size(); index++) {
            Demand request = demand.get(index);
            Item item = request.item();
            Item afterUse = inventory.instabuild() ? item : request.afterUse();
            int held = residentSlot(resident, item);
            if (held >= 0) {
                assignments.add(new Assignment(index, request.actionIndex(), item, held, false, null));
                resident.set(held, afterUse);
                continue;
            }
            // "Furthest next use" is measured from the demand AFTER this one: the demand being served right now is
            // about to be satisfied, so counting it would let the item we are fetching evict itself.
            int victim = victimSlot(resident, demand, index + 1);
            Item evicted = resident.get(victim);
            resident.set(victim, item);
            fetches++;
            assignments.add(new Assignment(index, request.actionIndex(), item, victim, true, evicted));
            swaps.add(new BuildAction.SwapHotbar(victim, item,
                    inventory.firstSlotWith(item).orElse(NOT_IN_INVENTORY), false, victim, UNSTAMPED_LAYER));
            resident.set(victim, afterUse);
        }
        return new Schedule(assignments, swaps, fetches, initial);
    }

    /**
     * Splice the schedule's swaps into the order, each immediately before the action that needs it, and write the
     * concrete hotbar slot into every action that demanded one.
     *
     * <p>Immediately before, not batched at the head of a layer, because a swap that happens early is a swap whose
     * slot can be taken again before it is used — and because {@code InventoryBehavior} contends for the hotbar every
     * tick, the window between staging and clicking is exactly the window in which a plan silently stops matching
     * the world.
     *
     * <p>This is also where each swap acquires its layer: the schedule is computed from a demand sequence that knows
     * only action indices, and the layer belongs to the action the swap serves. See {@link #UNSTAMPED_LAYER}.
     *
     * <h2>Stage two of the slot resolution</h2>
     *
     * <p>This method is the ONLY authority on {@link BuildAction#handSlot} for an action that holds a material.
     * {@link PlacementOracle} solves against the item — a cell is placeable when the bot carries the block at all,
     * wherever in its 36 slots it sits — and leaves {@link BuildAction#UNASSIGNED_SLOT} behind, because the slot a
     * placement will be made from is not knowable until {@link #belady} has chosen which resident to evict for it.
     * That choice is made here, over the whole frozen order, and written back: every {@link Assignment} carries its
     * {@link Assignment#hotbarSlot}, fetched or not, so an item that was already resident gets its slot from the same
     * place a fetched one does.
     *
     * <p>Reading the slot off the solution instead is the defect this closes. It capped a plan at the seven material
     * slots the bot happened to be holding and reported the other 10 480 of etz-basalt's 15 004 cells as
     * {@code NO_ITEM}; and had the oracle simply widened its search without this stage, a backpack index would have
     * been written into {@code handSlot}, where {@code BuildAction.checkHandSlot} refuses it — correctly, since
     * selecting slot 20 is not a thing the hotbar can do.
     *
     * <p>Refuses to return an order that still carries an {@link BuildAction#UNASSIGNED_SLOT}. Trap 9: a click made
     * with the wrong item in hand is compared by identity inside {@code BlockPlaceHelper} and voided with no log line
     * at all, so an unresolved slot must fail loudly here rather than quietly there.
     */
    public static List<BuildAction> weave(List<BuildAction> order, Schedule schedule) {
        BuildAction.SwapHotbar[] before = new BuildAction.SwapHotbar[order.size()];
        int[] handSlot = new int[order.size()];
        Arrays.fill(handSlot, BuildAction.UNASSIGNED_SLOT);
        int nextSwap = 0;
        for (Assignment assignment : schedule.assignments()) {
            int at = assignment.actionIndex();
            if (at < 0 || at >= order.size()) {
                throw new IllegalArgumentException("assignment " + assignment.demandIndex() + " points at action "
                        + at + ", outside an order of " + order.size() + " actions");
            }
            if (handSlot[at] != BuildAction.UNASSIGNED_SLOT) {
                // Two assignments for one action means the schedule believes the action needs two different items in
                // one hand. demandSequence cannot produce that, so it is a caller that built the demands by hand --
                // and the failure it would otherwise cause is one of the two slots dropped on the floor.
                throw new IllegalArgumentException("two hotbar assignments were scheduled for action " + at);
            }
            if (assignment.hotbarSlot() < FIRST_MATERIAL_SLOT
                    || assignment.hotbarSlot() > LAST_MATERIAL_SLOT) {
                throw new IllegalArgumentException("assignment " + assignment.demandIndex() + " uses reserved slot "
                        + assignment.hotbarSlot() + "; material slots are " + FIRST_MATERIAL_SLOT + ".."
                        + LAST_MATERIAL_SLOT);
            }
            Item demanded = demandedItem(order.get(at));
            if (demanded == null || demanded != assignment.item()) {
                throw new IllegalArgumentException("assignment " + assignment.demandIndex() + " carries "
                        + BuildAction.describeItem(assignment.item()) + " for action " + at + ", which demands "
                        + BuildAction.describeItem(demanded));
            }
            handSlot[at] = assignment.hotbarSlot();
            if (!assignment.fetched()) {
                continue;
            }
            if (nextSwap >= schedule.swaps().size()) {
                throw new IllegalArgumentException("the schedule claims " + schedule.assignments().size()
                        + " assignments with more fetches than its " + schedule.swaps().size() + " swaps");
            }
            BuildAction.SwapHotbar swap = schedule.swaps().get(nextSwap++);
            if (swap.hotbarSlot() != assignment.hotbarSlot() || swap.item() != assignment.item()) {
                throw new IllegalArgumentException("swap " + (nextSwap - 1) + " does not serve assignment "
                        + assignment.demandIndex() + ": " + swap.describe());
            }
            before[at] = stamp(swap, order.get(at).layer());
        }
        if (nextSwap != schedule.swaps().size()) {
            throw new IllegalArgumentException("the schedule carries " + schedule.swaps().size() + " swaps but only "
                    + nextSwap + " assignments are fetches");
        }

        List<BuildAction> woven = new ArrayList<>(order.size() + nextSwap);
        for (int index = 0; index < order.size(); index++) {
            BuildAction action = order.get(index);
            if (action.kind() == BuildAction.Kind.SWAP_HOTBAR) {
                // Weaving an already-woven order would double every swap and leave the second one selecting a slot
                // whose item the first already put there. Cheap to refuse, invisible to debug.
                throw new IllegalArgumentException("the order already carries a hotbar swap at action " + index
                        + "; weave takes the order BEFORE the schedule is spliced in");
            }
            if (before[index] != null) {
                woven.add(before[index]);
            }
            if (demandedItem(action) != null && handSlot[index] == BuildAction.UNASSIGNED_SLOT) {
                throw new IllegalArgumentException("action " + index + " demands "
                        + BuildAction.describeItem(demandedItem(action))
                        + " but the schedule assigned it no hotbar slot: "
                        + action.describe());
            }
            BuildAction fixed = inFixedSlot(action);
            BuildAction placed = handSlot[index] == BuildAction.UNASSIGNED_SLOT
                    ? fixed : inHandSlot(fixed, handSlot[index]);
            if (placed.handSlot() == BuildAction.UNASSIGNED_SLOT) {
                throw new IllegalArgumentException("action " + index + " needs an item in hand but the schedule "
                        + "assigned it no hotbar slot: " + placed.describe());
            }
            if (!(placed instanceof BuildAction.PlaceScaffold)
                    && placed.handSlot() == THROWAWAY_SLOT) {
                throw new IllegalArgumentException("action " + index + " uses reserved throwaway slot "
                        + THROWAWAY_SLOT + " but is not PLACE_SCAFFOLD: " + placed.describe());
            }
            woven.add(placed);
        }
        return List.copyOf(woven);
    }

    /**
     * The next index at or after {@code from} at which {@code item} is demanded, or {@code -1} for never again.
     *
     * <p>The OPT oracle, isolated so it can be tested on its own: Belady is one line of policy on top of this and
     * every mistake in the policy shows up here first. {@code -1} rather than {@link Integer#MAX_VALUE} so that
     * "never used again" is a case the caller has to handle rather than a magic number it can accidentally compare.
     *
     * <p>The index is into {@code demand}, not into the order. Belady only ever compares two of these against each
     * other, and demand indices are monotone in action indices, so the two rank identically — but demand indices are
     * dense, which is what makes {@link #victimSlot}'s scan linear in the demands rather than in the plan.
     */
    public static int nextUse(List<Demand> demand, int from, Item item) {
        if (item == null) {
            return -1;
        }
        for (int index = Math.max(0, from); index < demand.size(); index++) {
            // Reference equality: items are registry singletons, and it is the comparison BlockPlaceHelper.matches
            // makes when it decides whether the click that goes out is the click that was planned.
            if (demand.get(index).item() == item) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Which material slot to evict: the resident whose next use lies furthest in the future, {@code -1} beating
     * every finite index.
     *
     * <p>Ties are real and frequent — at the end of a build every remaining resident is "never again" — and they are
     * broken deterministically: highest slot first, so slot 7 goes first for the reason in the class javadoc, and so
     * the answer cannot depend on map iteration order. An empty material slot is always chosen before any occupied
     * one, since evicting nothing costs nothing; among empty slots the LOWEST is taken, which packs materials from
     * slot 1 upward and leaves slot 7 — the one {@code throwaway(select=true)} appropriates — empty for longest.
     *
     * @param resident nine entries, index equal to hotbar slot, {@code null} for empty; entries at
     *                 {@link #PICKAXE_SLOT} and {@link #THROWAWAY_SLOT} are ignored, never evicted
     */
    public static int victimSlot(List<Item> resident, List<Demand> demand, int from) {
        for (int slot = FIRST_MATERIAL_SLOT; slot <= LAST_MATERIAL_SLOT; slot++) {
            if (residentAt(resident, slot) == null) {
                return slot;
            }
        }
        int chosen = LAST_MATERIAL_SLOT;
        long furthest = Long.MIN_VALUE;
        for (int slot = FIRST_MATERIAL_SLOT; slot <= LAST_MATERIAL_SLOT; slot++) {
            int next = nextUse(demand, from, residentAt(resident, slot));
            // "Never again" is not a large number, it is the absence of one, and it must beat every finite index --
            // including the last demand in the sequence, which a naive MAX_VALUE-free encoding would tie with.
            long rank = next < 0 ? Long.MAX_VALUE : next;
            // >= and not >, walking slots upward: among equals the HIGHEST slot wins, which is slot 7 first.
            if (rank >= furthest) {
                furthest = rank;
                chosen = slot;
            }
        }
        return chosen;
    }

    // ------------------------------------------------------------------- capacity

    /**
     * Does the build's material set fit in 36 slots at all, and what does it need?
     *
     * <p>Asked before the run rather than discovered during it. The reserved count is two — the pickaxe and the
     * scaffold/throwaway — and each distinct placeable type needs at least one slot; a schematic whose material set
     * exceeds that cannot be built from one inventory load no matter how good the schedule is, and the useful moment
     * to learn it is before three hours of walking, not after two.
     *
     * <p>Stack depth is deliberately NOT modelled here. A type needing more than 64 units occupies more than one
     * slot, and that is a {@link PlanReport.MaterialNeed} shortfall rather than a capacity failure: the answers are
     * different ("get more of this") and mixing them makes both unreadable.
     *
     * <p>Types are counted by ITEM, not by state. All 24 facings of a piston are one item and one slot, so counting
     * states would report a 39-type schematic as needing a hundred and refuse a build that fits comfortably. States
     * with no item form at all — fire, piston heads, redstone wire — occupy no slot and are dropped here; they are a
     * {@link PlanReport#limitations} line, which is a different sentence in a different part of the report.
     *
     * @param scaffoldItem counted in {@code reserved}; it appears in no schematic cell by construction (5.5), so it
     *                     is never also one of the placeable types. {@code null} when the scaffold planner could not
     *                     name one, in which case only the pickaxe is reserved
     */
    public static CapacityCheck capacity(SchematicView view, V3Settings settings, InventorySnapshot inventory,
                                         Item scaffoldItem) {
        return capacity(view, settings, inventory, scaffoldItem, List.of());
    }

    /**
     * Capacity against the frozen order. Each filled bucket occupies its own slot because vanilla buckets do not
     * stack and each FillFluid turns one of them into an empty bucket.
     */
    public static CapacityCheck capacity(SchematicView view, V3Settings settings, InventorySnapshot inventory,
                                         Item scaffoldItem, List<BuildAction> order) {
        List<Item> items = new ArrayList<>();
        List<Integer> needed = new ArrayList<>();
        for (BlockState state : view.distinctStates()) {
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }
            int demand = view.demandFor(settings, state);
            int at = items.indexOf(item);
            if (at < 0) {
                items.add(item);
                needed.add(demand);
            } else {
                needed.set(at, needed.get(at) + demand);
            }
        }

        // Sorted by hand through an index list rather than by iterating a map: linear over 34 items, and it is the
        // one arrangement in this class that a HashMap would silently reorder per JVM run while every individual
        // number still looked right.
        List<Integer> byDemand = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            byDemand.add(index);
        }
        byDemand.sort(Comparator.comparingInt((Integer index) -> -needed.get(index))
                .thenComparing(index -> itemKey(items.get(index))));

        List<Item> slotItems = new ArrayList<>();
        List<Integer> slotDemand = new ArrayList<>();
        for (int index : byDemand) {
            slotItems.add(items.get(index));
            slotDemand.add(needed.get(index));
        }
        Set<Item> creativeBuckets = new HashSet<>();
        if (order != null) {
            for (BuildAction action : order) {
                if (action instanceof BuildAction.FillFluid fluid) {
                    if (inventory.instabuild() && !creativeBuckets.add(fluid.bucket())) {
                        continue;
                    }
                    slotItems.add(fluid.bucket());
                    slotDemand.add(1);
                }
            }
        }
        List<Integer> bySlotDemand = new ArrayList<>(slotItems.size());
        for (int index = 0; index < slotItems.size(); index++) {
            bySlotDemand.add(index);
        }
        bySlotDemand.sort(Comparator.comparingInt((Integer index) -> -slotDemand.get(index))
                .thenComparing(index -> itemKey(slotItems.get(index)))
                .thenComparingInt(Integer::intValue));

        int reserved = scaffoldItem == null ? 1 : RESERVED_SLOTS;
        int room = Math.max(0, INVENTORY_SLOTS - reserved);
        List<Item> overflow = new ArrayList<>();
        List<PlanReport.MaterialNeed> materials = new ArrayList<>(items.size());
        for (int rank = 0; rank < byDemand.size(); rank++) {
            int index = byDemand.get(rank);
            Item item = items.get(index);
            materials.add(new PlanReport.MaterialNeed(item, needed.get(index), 0, inventory.count(item)));
        }
        for (int rank = room; rank < bySlotDemand.size(); rank++) {
            // Repeated entries are intentional for non-stackable buckets: four water buckets are four slots, and an
            // overflow report that named water_bucket once would understate the missing capacity by three.
            overflow.add(slotItems.get(bySlotDemand.get(rank)));
        }
        return new CapacityCheck(slotItems.size() + reserved <= INVENTORY_SLOTS, items.size(), slotItems.size(),
                reserved, INVENTORY_SLOTS, overflow, materials);
    }

    /**
     * The item the plan's helper blocks are made of, or {@code null} when it places none — the {@code scaffoldItem}
     * argument of {@link #capacity}, read off the finished order rather than asked of the scaffold planner.
     *
     * <p>Read off the order because that is the only place the answer is a FACT. The scaffold planner names a
     * candidate before the run and may never use it; what reserves an inventory slot is a helper block the plan
     * actually places. The first one decides, and by rule 5.5 there is only ever one type, so "the first" and "the
     * only" are the same block — a second type would be a scaffold-planner bug, and reporting the first is then still
     * the more useful half of the truth.
     */
    public static Item scaffoldItemOf(List<BuildAction> order) {
        for (BuildAction action : order) {
            if (action instanceof BuildAction.PlaceScaffold scaffold) {
                return scaffold.solution().item();
            }
        }
        return null;
    }

    /**
     * The material ledger: what the frozen order consumes, per item, with lossy breaks booked as replacement need.
     *
     * <p>Glass drops nothing. A break of a schematic block that must be re-placed therefore costs one more unit than
     * the cell count says, and the ONLY moment that arithmetic is cheap is now — mid-run it presents as a build that
     * stops two thousand cells later for a reason nobody can connect to a break that happened at cell four.
     *
     * <p>{@code needed} is the PEAK simultaneous requirement, not the total ever placed. The two differ exactly where
     * scaffold is involved: a layer that places four hundred helper blocks and takes all four hundred back out needs
     * however many stand at once, not four hundred, and reporting the total would demand a stack of cobblestone the
     * build never simultaneously holds. Every {@link BuildAction.RemoveScaffold} therefore returns its unit, because
     * a helper block is by construction one that drops itself.
     *
     * <p>What is deliberately NOT credited is the drop from a {@link BuildAction.Break}. The item lands on the floor
     * and the plan contains no action that picks it up, so counting it would be a promise the plan does not keep.
     *
     * <h2>Two worlds, two ledgers</h2>
     *
     * <p><b>Survival</b> charges one unit per placement, and that arithmetic is the whole point: on the owner's real
     * server a 16 342-cell farm genuinely does run out, and "you are 17 short" said before the run is the difference
     * between a decision and a stall three hours in. Nothing about that path changes.
     *
     * <p><b>Creative</b> ({@link InventorySnapshot#instabuild}) consumes nothing on placement, so a stack is
     * unbounded and the only material question left is whether the bot carries the item AT ALL. Each placeable is
     * therefore charged ONCE — presence, not quantity — which is the same shape as the bucket rule below it and the
     * same reasoning {@link #belady} already applies at {@code afterUse}: a creative stack does not shrink. Without
     * this, a creative build of more than one stack of any material was refused before the bot moved, which is every
     * build the owner actually runs; the bench's floor9 asked for 81 stone against a deliberate one-stack loadout and
     * was told it was 17 short of a world where placement is free.
     *
     * <p>The check is narrowed, never disabled. An item the snapshot does not hold at all still shows a shortfall of
     * one in creative, by name, in the same limitation line.
     */
    public static List<PlanReport.MaterialNeed> materialLedger(List<BuildAction> order, InventorySnapshot inventory) {
        List<Item> items = new ArrayList<>();
        List<Integer> outstanding = new ArrayList<>();
        List<Integer> peak = new ArrayList<>();
        List<Integer> replacement = new ArrayList<>();
        // Membership only, never iterated -- so the hash order it is stored in cannot reach the output. A cell is
        // removed the moment its replacement is booked, so two placements after one lossy break book one replacement.
        Set<Long> lostToBreak = new HashSet<>();
        boolean creative = inventory.instabuild();
        // Which items have already been charged their single creative unit. Empty and unread in survival.
        Set<Item> chargedInCreative = new HashSet<>();

        for (BuildAction action : order) {
            switch (action) {
                case BuildAction.Place place -> {
                    Item item = place.solution().item();
                    int index = track(items, outstanding, peak, replacement, item);
                    chargeUnit(outstanding, peak, index, item, creative, chargedInCreative);
                    // A lossy break costs a replacement unit in SURVIVAL, where the block that dropped nothing has to
                    // come out of the stack a second time. In creative there is no second time to pay for.
                    if (lostToBreak.remove(PlacementGeometry.positionKey(place.cell())) && !creative) {
                        replacement.set(index, replacement.get(index) + 1);
                    }
                }
                // Same material demand as a PLACE. It is spelled out rather than folded into the case above
                // because the two records are distinct types and Belady has to see one unit per action either way.
                case BuildAction.JumpPlace jump -> {
                    Item item = jump.solution().item();
                    int index = track(items, outstanding, peak, replacement, item);
                    chargeUnit(outstanding, peak, index, item, creative, chargedInCreative);
                    if (lostToBreak.remove(PlacementGeometry.positionKey(jump.cell())) && !creative) {
                        replacement.set(index, replacement.get(index) + 1);
                    }
                }
                case BuildAction.PlaceScaffold scaffold -> {
                    Item item = scaffold.solution().item();
                    chargeUnit(outstanding, peak, track(items, outstanding, peak, replacement, item), item,
                            creative, chargedInCreative);
                }
                case BuildAction.FillFluid fluid ->
                        chargeUnit(outstanding, peak,
                                track(items, outstanding, peak, replacement, fluid.bucket()), fluid.bucket(),
                                creative, chargedInCreative);
                case BuildAction.RemoveScaffold removed -> {
                    Item item = removed.expected() == null ? null : removed.expected().getBlock().asItem();
                    // Survival gets its helper block back, which is what keeps `needed` the PEAK rather than the
                    // total. Creative never spent one, so there is nothing to return and crediting it would let the
                    // next PlaceScaffold charge a second unit for an item that cannot run out.
                    if (item != null && item != Items.AIR && !creative) {
                        charge(outstanding, peak, track(items, outstanding, peak, replacement, item), -1);
                    }
                }
                case BuildAction.Break broken -> {
                    if (!broken.drops()) {
                        lostToBreak.add(PlacementGeometry.positionKey(broken.cell()));
                    }
                }
                // Neither wants a placeable item in hand, and an interaction actively wants one NOT to be there.
                case BuildAction.Interact ignored -> { }
                case BuildAction.WriteSign ignored -> { }
                case BuildAction.SwapHotbar ignored -> { }
            }
        }

        List<Integer> byNeed = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            byNeed.add(index);
        }
        byNeed.sort(Comparator.comparingInt((Integer index) -> -peak.get(index))
                .thenComparing(index -> itemKey(items.get(index))));
        List<PlanReport.MaterialNeed> ledger = new ArrayList<>(items.size());
        for (int index : byNeed) {
            ledger.add(new PlanReport.MaterialNeed(items.get(index), peak.get(index), replacement.get(index),
                    inventory.count(items.get(index))));
        }
        return List.copyOf(ledger);
    }

    /** The hotbar slot holding an item that places {@code state}, or empty. The index form of V2's private
     *  {@code hotbarStackThatPlaces}, which returns a stack and therefore cannot answer the question a
     *  {@link PlacementSolution#slot} needs answered. */
    public static OptionalInt slotThatPlaces(List<ItemStack> hotbar,
                                             net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) {
            return OptionalInt.empty();
        }
        for (int slot = 0; slot < hotbar.size(); slot++) {
            ItemStack stack = hotbar.get(slot);
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            // Matched by ITEM and not by block, for the reason PlacementOracle.hotbarSlotThatPlaces records: one
            // torch item makes torch on the ground and wall_torch on a side, and a block comparison reported a
            // schematic full of wall torches as missing material while the torches sat in the bot's hand.
            if (PlacementGeometry.itemCanPlaceBlock(blockItem.getBlock().defaultBlockState(), state)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    // ------------------------------------------------------------------- internals

    /** The item one action needs in hand, or null when it needs none. Exhaustive over {@link BuildAction}'s permitted
     *  subtypes with no {@code default}, so a new action type does not compile until somebody has said whether it
     *  costs a slot. */
    private static Item demandedItem(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().item();
            case BuildAction.JumpPlace jump -> jump.solution().item();
            case BuildAction.FillFluid fluid -> fluid.bucket();
            // Scaffold placement and removal have reserved slots of their own. Neither participates in the material
            // cache: putting the helper into slots 1..7 silently turns a seven-slot optimum into a six-slot one.
            case BuildAction.PlaceScaffold ignored -> null;
            case BuildAction.Break ignored -> null;
            case BuildAction.RemoveScaffold ignored -> null;
            // An interaction must NOT hold a placeable block: a right-click with one in hand places the block instead
            // of stepping the repeater. Absence of a demand is the requirement, not the lack of one.
            case BuildAction.Interact ignored -> null;
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /** Slot content after an action consumes/transforms its demanded item. */
    private static Item itemAfterUse(BuildAction action, Item demanded) {
        return action instanceof BuildAction.FillFluid fluid ? FluidPlan.emptied(fluid.bucket()) : demanded;
    }

    /** The lowest MATERIAL slot holding this item, or -1. Slots 0 and 8 are skipped even when they hold it — see the
     *  class javadoc: {@code InventoryBehavior.onTick} takes them back every tick. */
    private static int residentSlot(List<Item> resident, Item item) {
        for (int slot = FIRST_MATERIAL_SLOT; slot <= LAST_MATERIAL_SLOT; slot++) {
            if (residentAt(resident, slot) == item) {
                return slot;
            }
        }
        return -1;
    }

    /** The resident of a slot, treating a short list as empty slots rather than as an index error. */
    private static Item residentAt(List<Item> resident, int slot) {
        return resident != null && slot >= 0 && slot < resident.size() ? resident.get(slot) : null;
    }

    /**
     * The same action, holding its material out of the slot the schedule put it in.
     *
     * <p>Exhaustive over {@link BuildAction}'s permitted subtypes with no {@code default}, and paired one-for-one with
     * {@link #demandedItem}: exactly the two cases that name an item there are the two that can be re-slotted
     * here, and a new action type does not compile until somebody has said which side of that line it falls on.
     *
     * <p>The others throw rather than passing through unchanged. {@link BuildAction.Break} and
     * {@link BuildAction.RemoveScaffold} hold the pickaxe out of a slot {@code InventoryBehavior} maintains for us,
     * and {@link BuildAction.Interact} and {@link BuildAction.WriteSign} require a hand that CANNOT place — reaching
     * any of them here means the demand sequence and the order have gone out of step, and silently re-slotting an
     * interaction to hold a block is a wrong build rather than a slow one.
     */
    private static BuildAction inHandSlot(BuildAction action, int slot) {
        return switch (action) {
            case BuildAction.Place place ->
                    new BuildAction.Place(place.solution(), place.sneak(), slot, place.layer());
            case BuildAction.JumpPlace jump -> new BuildAction.JumpPlace(jump.solution(), slot, jump.layer());
            case BuildAction.FillFluid fluid ->
                    new BuildAction.FillFluid(fluid.cell(), fluid.against(), fluid.stance(), fluid.approach(),
                            fluid.aimPoint(), fluid.rotation(), fluid.face(), fluid.bucket(), fluid.expected(),
                            fluid.sneak(), slot, fluid.layer());
            case BuildAction.PlaceScaffold ignored -> throw noDemand(action);
            case BuildAction.Break ignored -> throw noDemand(action);
            case BuildAction.RemoveScaffold ignored -> throw noDemand(action);
            case BuildAction.Interact ignored -> throw noDemand(action);
            case BuildAction.WriteSign ignored -> throw noDemand(action);
            case BuildAction.SwapHotbar ignored -> throw noDemand(action);
        };
    }

    /**
     * Apply the two slots fixed by the executor contract rather than by Bélády.
     *
     * <p>Rebuild the records even when the caller already supplied the right value. That makes this method the one
     * authority and also repairs an older pre-woven plan whose scaffold still says slot 1. A normal material action is
     * never rewritten here; it still needs an assignment and is refused below if it has none.
     */
    private static BuildAction inFixedSlot(BuildAction action) {
        return switch (action) {
            case BuildAction.PlaceScaffold scaffold ->
                    new BuildAction.PlaceScaffold(scaffold.solution(), scaffold.serves(), scaffold.sneak(),
                            THROWAWAY_SLOT, scaffold.layer());
            case BuildAction.RemoveScaffold removed ->
                    new BuildAction.RemoveScaffold(removed.cell(), removed.stance(), removed.approach(),
                            removed.aimPoint(), removed.rotation(), removed.expected(), removed.sneak(), PICKAXE_SLOT,
                            removed.layer());
            case BuildAction.Place place -> place;
            case BuildAction.JumpPlace jump -> jump;
            case BuildAction.Break broken -> broken;
            case BuildAction.Interact interact -> interact;
            case BuildAction.WriteSign sign -> sign;
            case BuildAction.FillFluid fluid -> fluid;
            case BuildAction.SwapHotbar swap -> swap;
        };
    }

    private static IllegalArgumentException noDemand(BuildAction action) {
        return new IllegalArgumentException("the schedule assigned a hotbar slot to a " + action.kind()
                + ", which demands no item: " + action.describe());
    }

    /** The same swap, carrying the layer of the action it serves. */
    private static BuildAction.SwapHotbar stamp(BuildAction.SwapHotbar swap, int layer) {
        return new BuildAction.SwapHotbar(swap.hotbarSlot(), swap.item(), swap.inventorySlotHint(), swap.sneak(),
                swap.handSlot(), layer);
    }

    /** Find or append an item's row in the four parallel ledger lists. */
    private static int track(List<Item> items, List<Integer> outstanding, List<Integer> peak,
                             List<Integer> replacement, Item item) {
        int at = items.indexOf(item);
        if (at >= 0) {
            return at;
        }
        items.add(item);
        outstanding.add(0);
        peak.add(0);
        replacement.add(0);
        return items.size() - 1;
    }

    /** Move one item's running balance and remember the high-water mark, which is what the build actually has to
     *  carry. Never lets the balance go negative: a removal without a matching placement is a planner bug, and a
     *  negative balance would hide it by subtracting from the peak of the next thing placed. */
    /** One unit of demand, booked the way the game mode makes true: every time in survival, once per item in
     *  creative. @see #materialLedger */
    private static void chargeUnit(List<Integer> outstanding, List<Integer> peak, int index, Item item,
                                   boolean creative, Set<Item> chargedInCreative) {
        if (creative && !chargedInCreative.add(item)) {
            return;
        }
        charge(outstanding, peak, index, 1);
    }

    private static void charge(List<Integer> outstanding, List<Integer> peak, int index, int delta) {
        int balance = Math.max(0, outstanding.get(index) + delta);
        outstanding.set(index, balance);
        if (balance > peak.get(index)) {
            peak.set(index, balance);
        }
    }

    /** {@code minecraft:cobblestone} — the sort key for every list this class hands out. A registry id is stable
     *  across runs and across JVMs; an identity hash and an enum ordinal are neither. */
    private static String itemKey(Item item) {
        return item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
