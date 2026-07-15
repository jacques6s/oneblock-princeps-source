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

package princeps.behavior;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.inventory.ContainerInput;
import princeps.Princeps;
import princeps.api.event.events.TickEvent;
import princeps.api.event.events.WorldEvent;
import princeps.api.event.events.type.EventState;
import princeps.api.utils.input.Input;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Classic survival helpers that run WHILE Princeps navigates (never during elytra flight): keep a Totem of
 * Undying in the offhand and hotbar slot 9, auto-eat (golden apples to heal, regular food for hunger), and
 * repair near-broken Mending gear by throwing Bottles o' Enchanting. Everything is gated on the master
 * {@code autoSurvival} setting; the totem is treated as the single most important item (offhand first).
 */
public final class SurvivalBehavior extends Behavior {

    private static final Set<net.minecraft.world.item.Item> HARMFUL_FOOD = Set.of(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT
    );

    /** Offhand button id for a SWAP container click (vanilla {@code Inventory.SLOT_OFFHAND}). */
    private static final int OFFHAND_SWAP_BUTTON = 40;
    /** Hotbar slot 9 (index 8) — the totem backup slot, which InventoryBehavior leaves alone. */
    private static final int SLOT9_INDEX = 8;

    /** While >= 0, an eat is in progress and this is the hotbar slot to re-select once it completes. */
    private int eatRestoreSlot = -1;
    private LocalPlayer eatPlayer;
    private Item eatItem;
    private InteractionHand eatHand;
    /** Wall-clock of the last golden-apple start/end, for the minor-damage throttle. */
    private long lastGappleMs;

    // Repair session: the Mending tool is parked in the offhand (so Mending targets it) while XP bottles are
    // thrown from the main hand in a slow stream. The totem system is suspended for the session's duration.
    private boolean repairing;
    private int repairThrowCd;
    /** Inventory slot the tool came from (to move it back once the session ends). */
    private int repairToolInventoryIndex = -1;
    private int repairRestoreSelectedSlot = -1;
    private LocalPlayer repairPlayer;
    private boolean repairCancelPending;

    /** A main-inventory consumable temporarily swapped into the hotbar, restored after use. */
    private int borrowedSourceIndex = -1;
    private int borrowedHotbarIndex = -1;
    private LocalPlayer borrowedPlayer;

    private final Predicate<ItemStack> isTotem = s -> s.getItem() == Items.TOTEM_OF_UNDYING;
    private final Predicate<ItemStack> isNormalGapple = s -> s.getItem() == Items.GOLDEN_APPLE;
    private final Predicate<ItemStack> isEnchantedGapple = s -> s.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
    private final Predicate<ItemStack> isGapple = s -> isNormalGapple.test(s) || isEnchantedGapple.test(s);
    private final Predicate<ItemStack> isXpBottle = s -> s.getItem() == Items.EXPERIENCE_BOTTLE;
    private final Predicate<ItemStack> isRegularFood = s ->
            s.has(DataComponents.FOOD) && !isGapple.test(s) && !HARMFUL_FOOD.contains(s.getItem());

    public SurvivalBehavior(Princeps princeps) {
        super(princeps);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (repairThrowCd > 0) {
            repairThrowCd--;
        }
        final LocalPlayer p = ctx.player();

        if (p == null) {
            holdUseKey(false);
            return;
        }

        final boolean navigationActive = princeps.getPathingBehavior().isPathing()
                || princeps.getPathingControlManager().mostRecentInControl()
                        .map(process -> process.isActive()).orElse(false);
        final boolean automationActive = SurvivalActivityPolicy.isActive(
                Princeps.settings().autoSurvival.value,
                navigationActive
        );

        // An eat in progress is managed FIRST and unconditionally: we run before Minecraft.handleKeybinds
        // this tick, so we must hold the USE key down every tick of the eat — otherwise handleKeybinds sees
        // keyUse released and cancels the use before any food is ever consumed. Release it (and restore the
        // held slot) the moment the eat completes or is interrupted, on every exit path.
        if (this.eatRestoreSlot >= 0) {
            final boolean samePlayer = p == this.eatPlayer;
            final boolean canContinue = samePlayer
                    && automationActive
                    && Princeps.settings().survivalAutoEat.value
                    && p.isAlive() && !p.isFallFlying()
                    && !princeps.getElytraProcess().isActive()
                    && p.containerMenu == p.inventoryMenu;
            if (canContinue && p.isUsingItem() && p.getUseItem().getItem() == this.eatItem
                    && p.getUsedItemHand() == this.eatHand) {
                holdUseKey(true);
                return;
            }
            finishEat(p, samePlayer);
            return;
        }

        if (!automationActive) {
            if (repairing) {
                cancelRepair(p); // setting disabled or navigation ended mid-session — restore hands
            }
            return;
        }
        if (this.repairing) {
            if (p != this.repairPlayer) {
                clearRepairState();
                return;
            }
            if (p.containerMenu != p.inventoryMenu) {
                this.repairCancelPending = true;
                return; // wait until the external container closes; its slot ids differ from inventoryMenu
            }
            if (this.repairCancelPending || !Princeps.settings().survivalAutoRepair.value
                    || !p.isAlive() || p.isFallFlying() || princeps.getElytraProcess().isActive()) {
                cancelRepair(p);
                return;
            }
            tickRepair(p);
            return;
        }
        if (!p.isAlive() || p.isFallFlying() || princeps.getElytraProcess().isActive()) {
            return;
        }
        // An external container (chest/etc.) is open: leave the player's inventory alone.
        if (p.containerMenu != p.inventoryMenu) {
            return;
        }

        // A repair session owns the hands (tool in offhand, XP in main hand) until it finishes.
        if (p.isUsingItem()) {
            return; // some other use (e.g. the user) is in progress
        }

        // 1) Totem — the single most important item; managed only while Princeps navigation owns the player.
        if (Princeps.settings().survivalAutoTotem.value && manageTotems(p)) {
            return;
        }

        // Never START an eat or repair while the bot is actively breaking a block: switching the main hand to
        // food/XP mid-break loses the block's progress and fights the forced attack every tick — the reported
        // "wants to mine and eat" glitch. Defer to a walking gap between blocks. (And once a consume DOES begin,
        // InputOverrideHandler pauses mining for its whole duration, so the two never overlap.) The totem above
        // still runs, so survival isn't compromised while a dig is briefly in progress.
        if (princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                || princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) {
            return;
        }

        if (Princeps.settings().survivalAutoEat.value && tryEat(p)) {
            return;
        }
        if (Princeps.settings().survivalAutoRepair.value) {
            maybeStartRepair(p);
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() != EventState.PRE) {
            return;
        }
        final LocalPlayer p = ctx.player();
        if (this.eatRestoreSlot >= 0 && p != null) {
            finishEat(p, p == this.eatPlayer);
        } else {
            holdUseKey(false);
            this.eatRestoreSlot = -1;
            this.eatPlayer = null;
            this.eatItem = null;
            this.eatHand = null;
        }
        if (this.repairing && p != null && p == this.repairPlayer && p.containerMenu == p.inventoryMenu) {
            cancelRepair(p);
        } else {
            clearRepairState();
        }
        clearBorrowedSlot();
        this.lastGappleMs = 0L;
    }

    /**
     * True while an eat/repair/borrow session owns the hotbar. Pathing-side slot switches
     * ({@code MovementHelper.switchToBestToolFor}, {@code InventoryBehavior.throwaway}) MUST hold off while
     * this is set: a mid-consume {@code setSelectedSlot} cancels the item use outright — the reported
     * "mines and eats at once, the gapple never finishes" glitch. Breaking/placing is paused for the same
     * duration by {@link InputOverrideHandler}, so deferring the slot work costs nothing.
     */
    public boolean ownsInventory() {
        return this.eatRestoreSlot >= 0 || this.repairing || this.borrowedSourceIndex >= 0;
    }

    /**
     * True while an eat or repair session owns the hands. {@link InputOverrideHandler} reads this to pause
     * block-breaking for the duration, so the pickaxe (forced attack) and the food/XP in hand never fight.
     */
    public boolean isConsuming() {
        return this.eatRestoreSlot >= 0 || this.repairing;
    }

    // ─────────────────────────────────────── TOTEM ───────────────────────────────────────
    /**
     * Ensures a Totem of Undying in the offhand and a distinct backup in hotbar slot 9 when available.
     * InventoryBehavior protects that backup instead of treating slot 9 as its throwaway slot.
     */
    private boolean manageTotems(LocalPlayer p) {
        final boolean offHasTotem = isTotem.test(p.getItemBySlot(EquipmentSlot.OFFHAND));
        if (!offHasTotem) {
            final int src = findInventoryTotem(p, -1);
            if (src >= 0) {
                swap(p, menuSlot(src), OFFHAND_SWAP_BUTTON);
                return true;
            }
            return false;
        }
        final Inventory inv = p.getInventory();
        if (!isTotem.test(inv.getItem(SLOT9_INDEX))) {
            final int src = findInventoryTotem(p, SLOT9_INDEX);
            if (src >= 0) {
                swap(p, menuSlot(src), SLOT9_INDEX);
                return true;
            }
        }
        return false;
    }

    /** First inventory index (0-35) holding a totem, excluding {@code excludeIndex}; -1 if none. */
    private int findInventoryTotem(LocalPlayer p, int excludeIndex) {
        final var items = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) {
            if (i != excludeIndex && isTotem.test(items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // ─────────────────────────────────────── EAT ───────────────────────────────────────
    private boolean tryEat(LocalPlayer p) {
        final float hp = p.getHealth();
        final float maxHp = p.getMaxHealth();
        final int food = p.getFoodData().getFoodLevel();
        final boolean needsHealing = hp < maxHp;
        final boolean needsFood = food <= Princeps.settings().survivalEatFoodLevel.value;
        if (!needsHealing && !needsFood) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final boolean haveGapple = hasItem(p, isGapple);

        // Golden apple = healing. Emergency (<= configured HP) bypasses the throttle; minor damage is throttled.
        if (haveGapple && needsHealing) {
            final boolean emergency = GapplePolicy.isEmergency(
                    hp,
                    Princeps.settings().survivalGappleEmergencyHp.value
            );
            if (tryStartGapple(p, emergency, now)) {
                return true;
            }
        }

        // Hunger = regular food. A normal golden apple remains the last-resort food, but it MUST use the same
        // cooldown as healing. The old fallback called startEat directly, so low hunger silently bypassed the
        // 10-second guard and could chain gapples every time the previous one finished.
        if (needsFood) {
            if (startEat(p, isRegularFood)) {
                return true;
            }
            if (haveGapple && tryStartGapple(p, false, now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts a normal gapple when the cooldown permits, or immediately in an emergency. Enchanted gapples stay
     * emergency-only. Every successful gapple start records the same timestamp, including the hunger fallback.
     */
    private boolean tryStartGapple(LocalPlayer p, boolean emergency, long now) {
        if (!emergency && !GapplePolicy.isCooldownReady(
                now,
                this.lastGappleMs,
                Princeps.settings().survivalGappleThrottleMs.value
        )) {
            return false;
        }
        final boolean started = startEat(p, isNormalGapple)
                || emergency && startEat(p, isEnchantedGapple);
        if (started) {
            this.lastGappleMs = now;
        }
        return started;
    }

    /** Selects a matching consumable into the main hand and starts eating it; returns whether it began. */
    private boolean startEat(LocalPlayer p, Predicate<ItemStack> want) {
        final int prevSlot = p.getInventory().getSelectedSlot();
        if (!selectIntoHand(p, want)) {
            return false;
        }
        ctx.playerController().processRightClick(p, ctx.world(), InteractionHand.MAIN_HAND);
        if (p.isUsingItem()) {
            this.eatRestoreSlot = prevSlot;
            this.eatPlayer = p;
            this.eatItem = p.getUseItem().getItem();
            this.eatHand = p.getUsedItemHand();
            holdUseKey(true); // hold USE so handleKeybinds (later this tick) doesn't cancel the eat
            return true;
        }
        // Didn't start (shouldn't happen for food) — put the held slot back.
        restoreBorrowedSlot(p);
        p.getInventory().setSelectedSlot(prevSlot);
        return false;
    }

    /** Force the vanilla USE key state so a bot-started eat survives handleKeybinds' release check. */
    private void holdUseKey(boolean down) {
        ctx.minecraft().options.keyUse.setDown(down || ctx.minecraft().mouseHandler.isRightPressed());
    }

    private void finishEat(LocalPlayer p, boolean restoreSlot) {
        holdUseKey(false);
        restoreBorrowedSlot(p);
        if (restoreSlot && p.containerMenu == p.inventoryMenu) {
            p.getInventory().setSelectedSlot(this.eatRestoreSlot);
        }
        // Start-time gating prevents retry spam if use gets interrupted; refreshing at session end makes the user-
        // visible timeout a full configured interval AFTER the apple has finished instead of including its ~1.6s
        // eating animation. The <=5-heart emergency path still bypasses this timestamp immediately.
        if (this.eatItem == Items.GOLDEN_APPLE || this.eatItem == Items.ENCHANTED_GOLDEN_APPLE) {
            this.lastGappleMs = System.currentTimeMillis();
        }
        this.eatRestoreSlot = -1;
        this.eatPlayer = null;
        this.eatItem = null;
        this.eatHand = null;
    }

    // ─────────────────────────────────────── REPAIR ───────────────────────────────────────
    // XP-bottle Mending repair: park the most damaged eligible Mending item in the OFFHAND,
    // hold XP bottles in the MAIN hand, and throw a slow stream at our feet until the tool is healthy again.
    // Only Mending items are ever repaired; worn Mending armor is topped up incidentally by the same orbs.

    private boolean isMendingLow(ItemStack st) {
        return st.isDamageableItem() && hasMending(st)
                && remainingFraction(st) < Princeps.settings().survivalRepairBelowFraction.value;
    }

    private void maybeStartRepair(LocalPlayer p) {
        if (!hasItem(p, isXpBottle)) {
            return;
        }
        // Survival first: don't pull the totem out of the offhand while in danger.
        if (p.getHealth() < p.getMaxHealth() * Princeps.settings().survivalRepairMinHealthFraction.value) {
            return;
        }
        final int toolIndex = findLowestMendingTool(p);
        if (toolIndex < 0) {
            return;
        }
        this.repairToolInventoryIndex = toolIndex;
        this.repairRestoreSelectedSlot = p.getInventory().getSelectedSlot();
        this.repairPlayer = p;
        this.repairCancelPending = false;
        swap(p, menuSlot(toolIndex), OFFHAND_SWAP_BUTTON);
        this.repairing = true;
        this.repairThrowCd = 0;
    }

    /** Finds the most damaged low-durability Mending item in the hotbar or main inventory. */
    private int findLowestMendingTool(LocalPlayer p) {
        final Inventory inv = p.getInventory();
        int found = -1;
        float lowest = Float.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            final ItemStack stack = inv.getItem(i);
            if (isMendingLow(stack)) {
                final float remaining = remainingFraction(stack);
                if (remaining < lowest) {
                    found = i;
                    lowest = remaining;
                }
            }
        }
        return found;
    }

    private void tickRepair(LocalPlayer p) {
        final ItemStack tool = p.getItemBySlot(EquipmentSlot.OFFHAND);
        final boolean toolHealthy = !(tool.isDamageableItem() && hasMending(tool))
                || remainingFraction(tool) >= Princeps.settings().survivalRepairStopFraction.value;
        final boolean unsafe = p.getHealth() < p.getMaxHealth() * Princeps.settings().survivalRepairMinHealthFraction.value;
        if (toolHealthy || unsafe || !hasItem(p, isXpBottle)) {
            endRepair(p);
            return;
        }
        if (this.repairThrowCd > 0) {
            return;
        }
        if (!selectIntoHand(p, isXpBottle)) { // grab an XP bottle into the main hand (tool stays in offhand)
            endRepair(p);
            return;
        }
        // Throw straight down so the orb lands at our feet and is collected immediately (instantaneous use).
        final float prevPitch = p.getXRot();
        p.setXRot(85.0F);
        ctx.playerController().processRightClick(p, ctx.world(), InteractionHand.MAIN_HAND);
        p.setXRot(prevPitch);
        this.repairThrowCd = Math.max(1, Princeps.settings().survivalRepairThrowIntervalTicks.value);
    }

    /** Ends a repair session: move the tool back to its exact inventory slot and restore the selected slot. */
    private void endRepair(LocalPlayer p) {
        restoreBorrowedSlot(p);
        if (this.repairToolInventoryIndex >= 0 && p == this.repairPlayer) {
            swap(p, menuSlot(this.repairToolInventoryIndex), OFFHAND_SWAP_BUTTON);
            p.getInventory().setSelectedSlot(this.repairRestoreSelectedSlot);
        }
        clearRepairState();
        // The totem system re-runs next tick and restores a totem to the (now free) offhand.
    }

    /** Hard cancel (setting turned off / world change): restore the hands without other checks. */
    private void cancelRepair(LocalPlayer p) {
        if (p != null && p == this.repairPlayer && p.containerMenu != p.inventoryMenu) {
            this.repairCancelPending = true;
            return;
        }
        if (p != null && this.repairToolInventoryIndex >= 0
                && p == this.repairPlayer) {
            restoreBorrowedSlot(p);
            swap(p, menuSlot(this.repairToolInventoryIndex), OFFHAND_SWAP_BUTTON);
            p.getInventory().setSelectedSlot(this.repairRestoreSelectedSlot);
        }
        clearRepairState();
    }

    private void clearRepairState() {
        this.repairToolInventoryIndex = -1;
        this.repairRestoreSelectedSlot = -1;
        this.repairPlayer = null;
        this.repairCancelPending = false;
        this.repairThrowCd = 0;
        this.repairing = false;
        clearBorrowedSlot();
    }

    // ─────────────────────────────────────── helpers ───────────────────────────────────────
    /** Brings a matching item into the main hand (holding it, swapping from the inventory if needed). */
    private boolean selectIntoHand(LocalPlayer p, Predicate<ItemStack> want) {
        final Inventory inv = p.getInventory();
        if (want.test(inv.getItem(inv.getSelectedSlot()))) {
            return true;
        }
        restoreBorrowedSlot(p);
        for (int i = 0; i <= 8; i++) { // hotbar first — no swap needed
            if (want.test(inv.getItem(i))) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i <= 35; i++) { // borrow a safe hotbar slot and restore it after use
            if (want.test(inv.getItem(i))) {
                final int hb = borrowableHotbarSlot(p);
                swap(p, menuSlot(i), hb);
                this.borrowedSourceIndex = i;
                this.borrowedHotbarIndex = hb;
                this.borrowedPlayer = p;
                inv.setSelectedSlot(hb);
                return true;
            }
        }
        return false;
    }

    /** Prefer an empty slot; on a full hotbar borrow 1..7 and restore it after the session. */
    private int borrowableHotbarSlot(LocalPlayer p) {
        final Inventory inv = p.getInventory();
        for (int i = 1; i <= 7; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return inv.getSelectedSlot() == 7 ? 6 : 7;
    }

    private void restoreBorrowedSlot(LocalPlayer p) {
        if (this.borrowedSourceIndex < 0) {
            return;
        }
        if (p == this.borrowedPlayer && p.containerMenu == p.inventoryMenu) {
            swap(p, menuSlot(this.borrowedSourceIndex), this.borrowedHotbarIndex);
        }
        clearBorrowedSlot();
    }

    private void clearBorrowedSlot() {
        this.borrowedSourceIndex = -1;
        this.borrowedHotbarIndex = -1;
        this.borrowedPlayer = null;
    }

    private boolean hasItem(LocalPlayer p, Predicate<ItemStack> want) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (want.test(s)) {
                return true;
            }
        }
        return want.test(p.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    private static boolean hasMending(ItemStack stack) {
        final ItemEnchantments ench = stack.getEnchantments();
        for (Holder<Enchantment> e : ench.keySet()) {
            if (e.is(Enchantments.MENDING)) {
                return true;
            }
        }
        return false;
    }

    private static float remainingFraction(ItemStack stack) {
        final int max = stack.getMaxDamage();
        return max <= 0 ? 1.0F : (max - stack.getDamageValue()) / (float) max;
    }

    /** Inventory index (0-35) → player-menu slot id (hotbar 0-8 → 36-44, main inv 9-35 → 9-35). */
    private static int menuSlot(int invIndex) {
        return invIndex < 9 ? invIndex + 36 : invIndex;
    }

    /** Un-throttled SWAP container click (source menu slot ↔ hotbar {@code button} 0-8, or offhand 40). */
    private void swap(LocalPlayer p, int srcMenuSlot, int button) {
        ctx.playerController().windowClick(p.inventoryMenu.containerId, srcMenuSlot, button, ContainerInput.SWAP, p);
    }
}
