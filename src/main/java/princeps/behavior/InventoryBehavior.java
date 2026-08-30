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

import princeps.Princeps;
import princeps.api.event.events.TickEvent;
import princeps.api.utils.Helper;
import princeps.api.utils.input.Input;
import princeps.utils.AreaTool;
import princeps.utils.ToolSet;
import princeps.process.BuilderProcess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    int ticksSinceLastInventoryMove;
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt

    /**
     * Why the last swap request was refused, in words, for whoever has to explain the stall.
     *
     * <p>Every refusal path here used to return a bare {@code false}. The caller in BuilderProcess turns that into
     * {@code REQUEST_PAUSE} and tries again next tick, forever, and because the pause short-circuits the rest of
     * onTick nothing further down ever reports anything. A lighthouse run logged "Hotbar fetch needed" 1944 times in
     * 3300 ticks without one word about WHY the fetch never happened. A boolean was never enough information.
     */
    private String lastSwapRefusal = "no request yet";
    /** Why this behavior's tick did nothing, if it bailed out early. Same reasoning as above. */
    private String lastTickSkipReason = "";
    /** Hotbar slot currently owned by Builder V3. Upkeep may continue elsewhere but may not overwrite this slot. */
    private int builderLockedHotbarSlot = -1;

    /**
     * The slot a MOVEMENT fetches into when the block it must stand on is in the backpack.
     *
     * <p>Named rather than written as a bare 7 in one method and implied by a loop bound in another, because the
     * two disagreed and that disagreement is what let two fetchers write the same slot in the same tick.
     */
    private static final int MOVEMENT_FETCH_SLOT = 7;

    /** When each hotbar slot was last filled by a fetch, so the eviction can take the oldest rather than the first. */
    private final long[] hotbarFetchedAt = new long[9];

    /** Monotonic counter for {@link #hotbarFetchedAt}; only the ORDER matters, never the value. */
    private long hotbarFetchSequence;

    /** Human-readable reason the most recent hotbar swap request did not go through. */
    public String lastSwapRefusal() {
        return lastTickSkipReason.isEmpty()
                ? lastSwapRefusal
                : lastSwapRefusal + "; inventory upkeep is also skipping its tick: " + lastTickSkipReason;
    }

    /** Give Builder V3 exclusive ownership of one hotbar slot until the current action retires. */
    public void lockBuilderHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) {
            throw new IllegalArgumentException("hotbar slot must be 0..8, got " + slot);
        }
        this.builderLockedHotbarSlot = slot;
        if (this.lastTickRequestedMove != null && this.lastTickRequestedMove[1] == slot) {
            this.lastTickRequestedMove = null;
        }
    }

    /** Release Builder V3's per-action hotbar ownership. */
    public void clearBuilderHotbarLock() {
        this.builderLockedHotbarSlot = -1;
    }

    public InventoryBehavior(Princeps princeps) {
        super(princeps);
    }

    @Override
    public void onTick(TickEvent event) {
        if (!Princeps.settings().allowInventory.value) {
            lastTickSkipReason = "allowInventory is off";
            return;
        }
        if (princeps.getSurvivalBehavior().ownsInventory()) {
            lastTickSkipReason = "the survival behavior owns the inventory";
            return;
        }
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            // we have a crafting table or a chest or something open
            lastTickSkipReason = "a container screen is open";
            return;
        }
        // A forced block-use executes later in this same behavior pass. Keep the selected slot and its contents
        // immutable until BlockPlaceHelper has acknowledged the exact support/face/item request.
        //
        // NOTE this returns before ticksSinceLastInventoryMove++, and that is load-bearing in the wrong direction: if
        // CLICK_RIGHT is held down every tick, the move clock never advances, so the rate limiter below refuses every
        // swap forever. Named rather than silent, because that is indistinguishable from "the item isn't there".
        if (princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) {
            lastTickSkipReason = "a right-click is being forced, so the move clock is frozen";
            return;
        }
        lastTickSkipReason = "";
        ticksSinceLastInventoryMove++;
        final boolean slot9TotemProtected = Princeps.settings().autoSurvival.value
                && Princeps.settings().survivalAutoTotem.value
                && ctx.player().getInventory().getItem(8).getItem() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING;
        if (slot9TotemProtected && lastTickRequestedMove != null && lastTickRequestedMove[1] == 8) {
            lastTickRequestedMove = null;
        }
        if (!slot9TotemProtected) {
            final int firstThrowaway = firstValidThrowaway();
            if (firstThrowaway >= 9) {
                requestSwapWithHotBar(firstThrowaway, 8);
            }
        }
        // THE TOOL FOR THE WORK, not a pickaxe on principle. This slot used to be stocked against STONE always, so
        // a builder tearing several hundred mismatched WOOL pixels back out of a map art did it with a diamond
        // pickaxe -- which is no faster on wool than a bare hand, while shears are five times faster than either.
        // The builder reports what it is actually breaking; everything else still gets the stone answer.
        int pick = bestToolAgainst(princeps.getBuilderProcess() instanceof princeps.process.BuilderProcess builder
                ? builder.toolWorkInFront()
                : Blocks.STONE);
        if (pick >= 9) {
            requestSwapWithHotBar(pick, 0);
        }
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1]);
        }
    }

    /**
     * @return true once the item is on its way to the hotbar; false while the caller must wait and retry.
     *
     * <p>No destination used to mean "return true" -- reporting success for a swap that never happened. The caller
     * then moved on believing the material was in hand, the item stayed in the backpack, and the cell was deferred
     * on the same grounds next tick, and the next, forever. Silence about doing nothing is worse than failing.
     */
    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (!destination.isPresent()) {
            lastSwapRefusal = "every hotbar slot 1-7 is protected as still-needed, so there is nowhere to put it";
            return false;
        }
        return requestSwapWithHotBar(inMainInvy, destination.getAsInt());
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (i == MOVEMENT_FETCH_SLOT) {
                // NOT THIS ONE. Slot 7 is where throwaway() fetches the block a MOVEMENT is about to stand on, and
                // the two fetchers ran on the same slot without either knowing about the other: the builder pulled
                // its next colour into 7 in the same tick a bridge placement had just pulled its floor block there,
                // so the movement clicked holding the builder's colour. Two owners, one hand -- the collision is
                // removed by giving each fetcher its own slot rather than by timing them against each other.
                continue;
            }
            if (ctx.player().getInventory().getNonEquipmentItems().get(i).isEmpty() && !disallowedHotbar.test(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 1; i < 8; i++) {
                if (i != MOVEMENT_FETCH_SLOT && !disallowedHotbar.test(i)) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            // Last resort: evict a slot that IS currently useful. With more materials than hotbar slots -- any real
            // schematic -- every slot is useful, both loops above come up empty, and refusing to swap means the item
            // in the backpack can never be reached. The build then defers that cell forever with "X is in inventory
            // but not ready on the hotbar", which is exactly the deadlock the lighthouse sat in at 124/426. Evicting
            // a useful stack costs one extra swap later; refusing costs the whole build.
            for (int i = 1; i < 8; i++) {
                if (i != MOVEMENT_FETCH_SLOT) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        // OLDEST FIRST, NOT ALWAYS THE SAME ONE.
        //
        // The rule here used to be "take candidates.get(0)", reasoned as: a fixed victim keeps the rest of the
        // hotbar stable so the working set converges. It does the opposite once there are more materials than
        // slots, because candidates.get(0) is always slot 1. The bar then stops being a working set of seven and
        // becomes six frozen slots plus one revolving door, and every colour that is not among the frozen six has
        // to come through that door -- evicting the colour that came through it a moment earlier, which the next
        // cell then needs again.
        //
        // Measured on a live map art with seventeen colours: across consecutive census lines sixty ticks apart,
        // slots 4, 5, 6 and 8 never changed once while slot 1 read light_blue_terracotta, then deepslate_tiles,
        // then clay, then lodestone. The customer described the same thing from the other side of the screen --
        // "the blocks keep switching In the 2 slot really fast". Slot index 1 is the second slot on the bar.
        //
        // Evicting the LEAST RECENTLY FETCHED slot spreads the churn across all of them, which is what actually
        // converges: a colour that has just been fetched is the one most likely to be wanted again in the next
        // few cells, and it is now the last one to be thrown out rather than the first.
        int oldest = -1;
        long oldestStamp = Long.MAX_VALUE;
        for (int slot : candidates) {
            if (ctx.player().getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                return OptionalInt.of(slot);   // an empty slot costs nothing at all
            }
            long stamp = hotbarFetchedAt[slot];
            if (stamp < oldestStamp) {
                oldestStamp = stamp;
                oldest = slot;
            }
        }
        return oldest < 0 ? OptionalInt.of(candidates.get(0)) : OptionalInt.of(oldest);
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        if (inHotbar == this.builderLockedHotbarSlot) {
            lastSwapRefusal = "hotbar slot " + inHotbar + " is owned by Builder V3's current action";
            if (lastTickRequestedMove != null && lastTickRequestedMove[1] == inHotbar) {
                lastTickRequestedMove = null;
            }
            return false;
        }
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        if (ticksSinceLastInventoryMove < Princeps.settings().ticksBetweenInventoryMoves.value) {
            lastSwapRefusal = "rate limited: " + ticksSinceLastInventoryMove + " tick(s) since the last move, "
                    + Princeps.settings().ticksBetweenInventoryMoves.value + " required";
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Princeps.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Princeps.settings().inventoryMoveOnlyIfStationary.value && !princeps.getInventoryPauserProcess().stationaryForInventoryMove()) {
            lastSwapRefusal = "waiting to come to a halt (inventoryMoveOnlyIfStationary)";
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        lastSwapRefusal = "none: slot " + inInventory + " -> hotbar " + inHotbar;
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ContainerInput.SWAP, ctx.player());
        if (inHotbar >= 0 && inHotbar < hotbarFetchedAt.length) {
            // Freshly served, so it is now the LAST slot that should be thrown out again. See getTempHotbarSlot.
            hotbarFetchedAt[inHotbar] = ++hotbarFetchSequence;
        }
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return true;
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            if (Princeps.settings().acceptableThrowawayItems.value.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Princeps.settings().itemSaver.value && (stack.getDamageValue() + Princeps.settings().itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                continue;
            }
            // THE AREA TOOL IS NEVER PICKED UP BY AN AUTOMATIC CHOICE -- see AreaTool for the afternoon that cost.
            //
            // ToolSet.pickBestSlot already refuses it and BuilderProcess only ever takes it deliberately, for a
            // full 3x3 slice. This scan was the hole left in that rule: it ranks anything with a TOOL component
            // across all 36 slots and swaps the winner into hotbar slot 0 every tick, so a SPARE area pickaxe
            // lying in the backpack walks itself into the working hand -- and then every block broken to open a
            // path takes nine with it, including the one the bot had just decided to stand on.
            if (AreaTool.is(stack)) {
                continue;
            }
            if (stack.getItem().components().has(DataComponents.TOOL)) {
                double speed = ToolSet.calculateSpeedVsBlock(stack, against.defaultBlockState()); // takes into account enchants
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
        }
        return bestInd;
    }

    /**
     * Equips a usable elytra from the main inventory into the chest armor slot (three PICKUP clicks:
     * lift the elytra, drop it into the chest slot, put the replaced chestplate — if any — back into
     * the elytra's old slot). Used by the auto-elytra dispatcher right before a bot-chosen flight, so
     * no move-throttling applies (a player swaps armor in one motion too). Returns whether an elytra
     * with enough durability was found and equipped.
     */
    public boolean equipElytraFromInventory() {
        final NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int found = -1;
        for (int i = 0; i < invy.size(); i++) {
            final ItemStack stack = invy.get(i);
            if (stack.getItem() == net.minecraft.world.item.Items.ELYTRA
                    && stack.getMaxDamage() - stack.getDamageValue() >= Princeps.settings().elytraMinimumDurability.value) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            return false;
        }
        final int containerId = ctx.player().inventoryMenu.containerId;
        final int slotId = found < 9 ? found + 36 : found;
        final int chestArmorSlot = 6; // player inventory menu: 5=head, 6=chest, 7=legs, 8=feet
        ctx.playerController().windowClick(containerId, slotId, 0, ContainerInput.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, chestArmorSlot, 0, ContainerInput.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, slotId, 0, ContainerInput.PICKUP, ctx.player());
        return true;
    }

    public boolean hasGenericThrowaway() {
        for (Item item : Princeps.settings().acceptableThrowawayItems.value) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item that would fill this cell with the TEMPLATE's own block, or null when only a throwaway is left.
     *
     * <p>Split out of {@link #selectThrowawayForLocation} so the caller can tell the two cases apart WITHOUT
     * committing to a hotbar switch. They are not the same act: filling a planned cell with the block it is supposed
     * to hold, while walking past it, is free progress; dropping a cobblestone into that same cell is the thing that
     * makes it unbuildable. Until this split existed there was no way to ask which one was about to happen, and a
     * guard written against "placing into a planned cell" therefore blocked both -- freezing the bot for 8800 ticks
     * at 104,-58,89 while it held the black_stained_glass that 104,-59,88 was waiting for.
     */
    private Predicate<? super ItemStack> templateBlockChooserAt(int x, int y, int z) {
        BlockState maybe = princeps.getBuilderProcess().placeAt(x, y, z, princeps.bsi.get0(x, y, z));
        if (maybe == null) {
            return null;
        }
        Predicate<? super ItemStack> exactState = stack -> stack.getItem() instanceof BlockItem && maybe.equals(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {})));
        if (throwaway(false, exactState)) {
            return exactState;
        }
        // NOT for a state the placement geometry decides. This predicate compares the BLOCK and ignores the state,
        // so it happily hands the movement placer a sticky piston to use as a stepping stone -- and the placer sets
        // whatever facing falls out of wherever it happens to be looking. The builder then has to break it again.
        // Measured on run ce0b0946: 32 cells were broken, 25 of them never clicked by the builder at all and 30 never
        // even chosen as a target, including one cell broken FORTY-EIGHT times. costOfPlacingAt returns 0 -- free --
        // for any schematic block on the hotbar, so the pathfinder routes straight through the blueprint. This is also
        // where wrongly-oriented pistons in the finished world come from: they were never placed by the builder.
        if (BuilderProcess.placementStateIsGeometrySensitive(maybe)) {
            return null;
        }
        Predicate<? super ItemStack> anyState = stack -> stack.getItem() instanceof BlockItem
                && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock());
        return throwaway(false, anyState) ? anyState : null;
    }

    /** True when a placement here would use the template's own block rather than a helper block. */
    public boolean wouldPlaceTemplateBlockAt(int x, int y, int z) {
        return templateBlockChooserAt(x, y, z) != null;
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        Predicate<? super ItemStack> template = templateBlockChooserAt(x, y, z);
        if (template != null) {
            return throwaway(select, template);
        }
        for (Item item : Princeps.settings().acceptableThrowawayItems.value) {
            if (throwaway(select, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        return throwaway(select, desired, Princeps.settings().allowInventory.value);
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        LocalPlayer p = ctx.player();
        // An auto-survival eat/repair owns the hands: selecting a throwaway slot (or swapping one in) would
        // cancel the consume outright — same class of bug as the mid-eat auto-tool switch. Report the item as
        // available WITHOUT touching the hotbar; placing is paused for the consume's duration anyway, and the
        // real slot select happens on the first tick after the consume ends.
        final SurvivalBehavior survival = princeps.getSurvivalBehavior();
        final boolean handsOwned = survival != null && survival.ownsInventory();
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item)) {
                if (select && !handsOwned) {
                    p.getInventory().setSelectedSlot(i);
                }
                return true;
            }
        }
        if (desired.test(p.getItemBySlot(EquipmentSlot.OFFHAND))) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.getItem().components().has(DataComponents.TOOL)) {
                    if (select && !handsOwned) {
                        p.getInventory().setSelectedSlot(i);
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (!select || handsOwned) {
                        // A question, not an act. The planner asks whether the material exists at all, and it does.
                        return true;
                    }
                    // THE ONE LIE THAT PUT THE WRONG BLOCK IN THE WORLD AND THE BOT IN THE VOID.
                    //
                    // This branch used to read:
                    //
                    //     requestSwapWithHotBar(i, MOVEMENT_FETCH_SLOT);   // return value thrown away
                    //     p.getInventory().setSelectedSlot(MOVEMENT_FETCH_SLOT);
                    //     return true;
                    //
                    // requestSwapWithHotBar refuses far more often than it grants. It refuses while the move rate
                    // limiter is cooling down, it refuses while the bot is not standing still if
                    // inventoryMoveOnlyIfStationary is set, and it refuses outright for a slot the builder has
                    // locked. During a Map Art it is refused almost continuously, because the builder's own hotbar
                    // fetch is asking for a swap on nearly every tick -- measured in a live run as "rate limited:
                    // 0 tick(s) since the last move, 1 required", over and over.
                    //
                    // On every one of those refusals the old code still selected the fetch slot and still answered
                    // "yes, you are holding it". The two callers of this method are the two that matter most:
                    //
                    //   * MovementHelper.attemptToPlaceABlock -- the single choke point through which EVERY
                    //     movement lays the block it is about to stand on. A map art is one block thick with open
                    //     air underneath, so that block IS the floor. Told "yes" and handed a slot holding some
                    //     other colour, the movement clicks and one of two things happens. Either a block goes down
                    //     that the picture never asked for -- "the click produced orange_wool where light_gray_wool
                    //     was wanted", the misplacement the owner reported -- or the slot holds something that does
                    //     not place at all, nothing appears, and the bot walks into the gap it was told was about to
                    //     be floor. That is the fall. Both reported failures are this line.
                    //
                    //   * MovementPillar -- the climb back out. Same lie, same result: a jump, a click that places
                    //     nothing, and a bot that never gets up.
                    //
                    // So the answer now comes from the world instead of from hope: the swap must be granted AND the
                    // slot must actually hold the material before this reports success. A refusal costs a few ticks
                    // -- the request has been filed and the next tick is very likely to be granted -- and the caller
                    // simply does not click yet, which is the correct thing to do when you are not holding the block
                    // you meant to place.
                    if (!requestSwapWithHotBar(i, MOVEMENT_FETCH_SLOT)) {
                        return false;
                    }
                    if (!desired.test(inv.get(MOVEMENT_FETCH_SLOT))) {
                        // The click is applied to the local inventory immediately, so the slot should already hold
                        // it. If it does not, something else owns that slot this tick and clicking would place
                        // whatever it is holding.
                        lastSwapRefusal = "slot " + MOVEMENT_FETCH_SLOT + " did not take the material after the swap";
                        return false;
                    }
                    p.getInventory().setSelectedSlot(MOVEMENT_FETCH_SLOT);
                    return true;
                }
            }
        }

        return false;
    }
}
