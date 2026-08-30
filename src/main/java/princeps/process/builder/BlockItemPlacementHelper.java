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

package princeps.process.builder;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import princeps.utils.accessor.IBlockItem;

/** Shared, side-effect-free helpers for predicting and matching block-item placement. */
public final class BlockItemPlacementHelper {

    private BlockItemPlacementHelper() {}

    /**
     * Calls the virtual item-level placement method used by vanilla. In a launched client every BlockItem implements
     * {@link IBlockItem} through MixinBlockItem. The fallback keeps isolated unit tests and non-Mixin tooling usable;
     * it is intentionally only an approximation because it cannot select a StandingAndWallBlockItem's wall variant.
     */
    public static BlockState placementState(BlockItem item, BlockPlaceContext context) {
        if (item instanceof IBlockItem accessor) {
            return accessor.princeps$getPlacementState(context);
        }
        return item.getBlock().getStateForPlacement(context);
    }

    /**
     * Whether the Mixin that makes {@link #placementState} exact is actually applied.
     *
     * <p>Worth a method of its own because the failure is otherwise invisible: MixinBlockItem was written, compiled
     * and left out of {@code mixins.princeps.json}, so every BlockItem silently took the approximate branch above and
     * every wall-mounted block in every schematic simulated as its standing variant. Nothing threw, nothing logged,
     * and the builder simply declared those cells unbuildable forever. A one-line check at build start turns that
     * back into something a log can say out loud.
     */
    public static boolean wired(BlockItem sample) {
        return sample instanceof IBlockItem;
    }

    /**
     * True when the item can represent the desired block. The direct block comparison handles ordinary BlockItems;
     * the registered item comparison handles aliases such as pale-oak-sign -> pale-oak-wall-sign. Blocks with no
     * obtainable item map to AIR and are deliberately rejected.
     */
    public static boolean canSupply(BlockItem item, BlockState desired) {
        if (item.getBlock() == desired.getBlock()) {
            return true;
        }
        return desired.getBlock().asItem() != Items.AIR && desired.getBlock().asItem() == item;
    }
}
