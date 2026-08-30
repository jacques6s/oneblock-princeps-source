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

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockItemPlacementHelperTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void standingAndWallItemsSupplyBothRegisteredBlocks() {
        assertTrue(BlockItemPlacementHelper.canSupply(
                (BlockItem) Items.PALE_OAK_SIGN, Blocks.PALE_OAK_SIGN.defaultBlockState()));
        assertTrue(BlockItemPlacementHelper.canSupply(
                (BlockItem) Items.PALE_OAK_SIGN, Blocks.PALE_OAK_WALL_SIGN.defaultBlockState()));
        assertTrue(BlockItemPlacementHelper.canSupply(
                (BlockItem) Items.PALE_OAK_HANGING_SIGN, Blocks.PALE_OAK_WALL_HANGING_SIGN.defaultBlockState()));
        assertTrue(BlockItemPlacementHelper.canSupply(
                (BlockItem) Items.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH.defaultBlockState()));
    }

    @Test
    public void rejectsUnrelatedAndItemlessBlocks() {
        assertFalse(BlockItemPlacementHelper.canSupply(
                (BlockItem) Items.PALE_OAK_SIGN, Blocks.OAK_WALL_SIGN.defaultBlockState()));
        assertFalse(BlockItemPlacementHelper.canSupply(
                (BlockItem) Items.PISTON, Blocks.PISTON_HEAD.defaultBlockState()));
        assertFalse(BlockItemPlacementHelper.canSupply(
                (BlockItem) Items.STONE, Blocks.BUBBLE_COLUMN.defaultBlockState()));
    }
}
