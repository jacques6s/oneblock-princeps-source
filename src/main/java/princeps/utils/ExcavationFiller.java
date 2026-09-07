/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.utils;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.IntPredicate;
import java.util.function.IntConsumer;

/** AutoDig's shared material order and single reserved hotbar destination. */
public final class ExcavationFiller {
    public static final int SLOT = 5;
    private ExcavationFiller() {}

    public static int preference(Item item) {
        return item == Items.DEEPSLATE ? 0 : item == Items.COBBLED_DEEPSLATE ? 1 : 2;
    }

    public static boolean currentFetch(List<ItemStack> stock, int source, Item preferred) {
        return source >= 0 && source < Math.min(stock.size(), 36) && source != SLOT
                && !stock.get(source).isEmpty() && stock.get(source).getItem() == preferred
                && (stock.get(SLOT).isEmpty() || stock.get(SLOT).getItem() != preferred);
    }

    public static boolean currentSession(Object requested, Object current, boolean paused) {
        return requested != null && requested == current && !paused;
    }

    public static int source(List<ItemStack> stock, Predicate<? super ItemStack> eligible, boolean allowInventory) {
        int best = -1;
        int limit = Math.min(stock.size(), allowInventory ? 36 : 9);
        for (int offset = -1; offset < limit; offset++) {
            int slot = offset < 0 ? SLOT : offset;
            if (slot >= limit || offset == SLOT) continue;
            if (!allowInventory && slot != SLOT) continue;
            ItemStack stack = stock.get(slot);
            if (stack.isEmpty() || !eligible.test(stack)) continue;
            if (best < 0 || preference(stack.getItem()) < preference(stock.get(best).getItem())) best = slot;
        }
        return best;
    }

    /** The real executor supplies its existing rate-limited swap; no success is inferred from requesting it. */
    public static boolean select(List<ItemStack> stock, Predicate<? super ItemStack> desired,
                                 boolean allowInventory, boolean select, boolean handsBusy,
                                 IntPredicate fetch, IntConsumer selectSlot) {
        int source = source(stock, desired, allowInventory);
        if (source < 0) return false;
        if (!select) return true;
        if (handsBusy) return false;
        if (source != SLOT && (!allowInventory || !fetch.test(source))) return false;
        if (stock.get(SLOT).isEmpty() || !desired.test(stock.get(SLOT))) return false;
        selectSlot.accept(SLOT);
        return true;
    }
}
