/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockPlaceHelperTest {

    @Test
    public void acknowledgementMatchesOnlyTheExactMainHandPlacement() {
        BlockPos support = new BlockPos(10, 64, 20);
        BlockPos target = support.relative(Direction.UP);
        BlockPlaceHelper.SuccessfulBlockInteraction ack =
                new BlockPlaceHelper.SuccessfulBlockInteraction(
                        7L, support, Direction.UP, InteractionHand.MAIN_HAND, 3, Items.STONE);

        assertTrue(ack.matchesMainHandPlacement(support, Direction.UP, target, 3, Items.STONE));
        assertFalse(ack.matchesMainHandPlacement(support, Direction.NORTH, target, 3, Items.STONE));
        assertFalse(ack.matchesMainHandPlacement(support, Direction.UP, target.east(), 3, Items.STONE));
        assertFalse(ack.matchesMainHandPlacement(support, Direction.UP, target, 4, Items.STONE));
        assertFalse(ack.matchesMainHandPlacement(support, Direction.UP, target, 3, Items.DIRT));
    }

    @Test
    public void offhandSuccessCannotAcknowledgeAHotbarPlacement() {
        BlockPos support = new BlockPos(-1, -1, -1);
        BlockPlaceHelper.SuccessfulBlockInteraction ack =
                new BlockPlaceHelper.SuccessfulBlockInteraction(
                        8L, support, Direction.EAST, InteractionHand.OFF_HAND, -1, Items.STONE);

        assertFalse(ack.matchesMainHandPlacement(
                support, Direction.EAST, support.east(), 0, Items.STONE));
    }
}
