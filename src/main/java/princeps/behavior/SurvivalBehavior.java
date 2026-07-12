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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.inventory.ContainerInput;
import princeps.Princeps;
import princeps.api.event.events.TickEvent;

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
    private static final int SLOT9_INDEX = 8;

    /** While >= 0, an eat is in progress and this is the hotbar slot to re-select once it completes. */
    private int eatRestoreSlot = -1;
    /** Wall-clock of the last golden-apple heal, for the minor-damage throttle. */
    private long lastGappleMs;

    // Repair session: the Mending tool is parked in the offhand (so Mending targets it) while XP bottles are
    // thrown from the main hand in a slow stream. The totem system is suspended for the session's duration.
    private boolean repairing;
    private int repairThrowCd;
    /** Hotbar slot the tool came from (to move it back and re-select once the session ends). */
    private int repairToolHotbar = -1;

    private final Predicate<ItemStack> isTotem = s -> s.getItem() == Items.TOTEM_OF_UNDYING;
    private final Predicate<ItemStack> isGapple = s ->
            s.getItem() == Items.GOLDEN_APPLE || s.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
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
        if (!Princeps.settings().autoSurvival.value) {
            if (repairing) {
                cancelRepair(ctx.player()); // setting turned off mid-session — restore hands
            }
            return;
        }
        final LocalPlayer p = ctx.player();
        if (p == null || !p.isAlive() || p.isFallFlying()) {
            return; // never fight the elytra flight or act at the menu
        }
        // An external container (chest/etc.) is open: leave the player's inventory alone.
        if (p.containerMenu != p.inventoryMenu) {
            return;
        }

        // A currently-running eat must finish untouched (re-selecting or swapping mid-eat aborts it).
        if (this.eatRestoreSlot >= 0) {
            if (p.isUsingItem()) {
                return;
            }
            p.getInventory().setSelectedSlot(this.eatRestoreSlot);
            this.eatRestoreSlot = -1;
            return;
        }

        // A repair session owns the hands (tool in offhand, XP in main hand) until it finishes.
        if (this.repairing) {
            tickRepair(p);
            return;
        }
        if (p.isUsingItem()) {
            return; // some other use (e.g. the user) is in progress
        }

        // 1) Totem — the single most important item; managed always while enabled.
        if (Princeps.settings().survivalAutoTotem.value && manageTotems(p)) {
            return;
        }

        // Eat + repair are tied to the navigation actually running (don't devour the user's gapples while idle).
        final boolean navActive = princeps.getPathingBehavior().isPathing()
                || princeps.getPathingControlManager().mostRecentInControl()
                        .map(pr -> pr.isActive()).orElse(false);
        if (!navActive) {
            return;
        }

        if (Princeps.settings().survivalAutoEat.value && tryEat(p)) {
            return;
        }
        if (Princeps.settings().survivalAutoRepair.value) {
            maybeStartRepair(p);
        }
    }

    // ─────────────────────────────────────── TOTEM ───────────────────────────────────────
    /** Ensures a totem in the offhand (priority) and, if a spare exists, in hotbar slot 9. Returns whether it acted. */
    private boolean manageTotems(LocalPlayer p) {
        final boolean offHasTotem = isTotem.test(p.getItemBySlot(EquipmentSlot.OFFHAND));
        if (!offHasTotem) {
            final int src = findInventoryTotem(p, -1);
            if (src >= 0) {
                swap(p, menuSlot(src), OFFHAND_SWAP_BUTTON);
                return true;
            }
            return false; // no totem anywhere — nothing to do
        }
        // Offhand is covered; fill slot 9 only from a DIFFERENT totem (one totem => offhand only).
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
        final long now = System.currentTimeMillis();
        final boolean haveGapple = hasItem(p, isGapple);

        // Golden apple = healing. Emergency (low HP) bypasses the throttle; minor damage is throttled.
        if (haveGapple && hp < maxHp) {
            final boolean emergency = hp < Princeps.settings().survivalGappleEmergencyHp.value;
            final boolean throttleOk = now - this.lastGappleMs >= Princeps.settings().survivalGappleThrottleMs.value;
            if (emergency || throttleOk) {
                if (startEat(p, isGapple)) {
                    this.lastGappleMs = now;
                    return true;
                }
            }
        }

        // Hunger = regular food (golden apples are reserved for healing; only used if nothing else feeds).
        if (food <= Princeps.settings().survivalEatFoodLevel.value) {
            if (startEat(p, isRegularFood)) {
                return true;
            }
            if (haveGapple && startEat(p, isGapple)) {
                return true;
            }
        }
        return false;
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
            return true;
        }
        // Didn't start (shouldn't happen for food) — put the held slot back.
        p.getInventory().setSelectedSlot(prevSlot);
        return false;
    }

    // ─────────────────────────────────────── REPAIR ───────────────────────────────────────
    // XP-bottle Mending repair, per spec: park the held Mending tool in the OFFHAND (so Mending targets it),
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
        // The tool to repair must be the held main-hand item (that's what we can park in the offhand).
        if (!isMendingLow(p.getItemBySlot(EquipmentSlot.MAINHAND))) {
            return;
        }
        // Park the held tool in the offhand (held slot <-> offhand); the offhand's old item (a totem) lands
        // in the held slot and is displaced to the inventory when we grab a bottle next tick.
        this.repairToolHotbar = p.getInventory().getSelectedSlot();
        swap(p, menuSlot(this.repairToolHotbar), OFFHAND_SWAP_BUTTON);
        this.repairing = true;
        this.repairThrowCd = 0;
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

    /** Ends a repair session: move the tool out of the offhand back to its hotbar slot, re-select it. */
    private void endRepair(LocalPlayer p) {
        if (this.repairToolHotbar >= 0) {
            swap(p, menuSlot(this.repairToolHotbar), OFFHAND_SWAP_BUTTON); // offhand tool <-> hotbar slot
            p.getInventory().setSelectedSlot(this.repairToolHotbar);
        }
        this.repairToolHotbar = -1;
        this.repairing = false;
        // The totem system re-runs next tick and restores a totem to the (now free) offhand.
    }

    /** Hard cancel (setting turned off / world change): restore the hands without other checks. */
    private void cancelRepair(LocalPlayer p) {
        if (p != null && this.repairToolHotbar >= 0 && p.containerMenu == p.inventoryMenu) {
            swap(p, menuSlot(this.repairToolHotbar), OFFHAND_SWAP_BUTTON);
            p.getInventory().setSelectedSlot(this.repairToolHotbar);
        }
        this.repairToolHotbar = -1;
        this.repairing = false;
    }

    // ─────────────────────────────────────── helpers ───────────────────────────────────────
    /** Brings a matching item into the main hand (holding it, swapping from the inventory if needed). */
    private boolean selectIntoHand(LocalPlayer p, Predicate<ItemStack> want) {
        final Inventory inv = p.getInventory();
        if (want.test(inv.getItem(inv.getSelectedSlot()))) {
            return true;
        }
        for (int i = 0; i <= 8; i++) { // hotbar first — no swap needed
            if (want.test(inv.getItem(i))) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i <= 35; i++) { // main inventory — swap onto a spare hotbar slot
            if (want.test(inv.getItem(i))) {
                final int hb = spareHotbarSlot(p);
                swap(p, menuSlot(i), hb);
                inv.setSelectedSlot(hb);
                return true;
            }
        }
        return false;
    }

    /** A hotbar slot safe to borrow for a swap: never 0 (typically the pickaxe) or 8 (the totem slot). */
    private int spareHotbarSlot(LocalPlayer p) {
        final Inventory inv = p.getInventory();
        for (int i = 1; i <= 7; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return 4; // none free — displace the middle slot (restored via the saved selected slot)
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
