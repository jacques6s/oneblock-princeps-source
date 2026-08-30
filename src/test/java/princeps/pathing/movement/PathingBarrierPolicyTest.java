/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package princeps.pathing.movement;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PathingBarrierPolicyTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void aContextThatForbidsBarriersRejectsEveryDoorAndGateState() {
        assertTrue(MovementHelper.blocksPathingBarrier(false, Blocks.PALE_OAK_DOOR.defaultBlockState()));
        assertTrue(MovementHelper.blocksPathingBarrier(false, Blocks.PALE_OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, true)));
        assertTrue(MovementHelper.blocksPathingBarrier(false, Blocks.PALE_OAK_FENCE_GATE.defaultBlockState()));
        assertTrue(MovementHelper.blocksPathingBarrier(false, Blocks.PALE_OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.OPEN, true)));
        assertTrue(MovementHelper.blocksPathingBarrier(false, Blocks.IRON_DOOR.defaultBlockState()));
    }

    @Test
    public void ordinaryNavigationKeepsItsDoorAndGateCapability() {
        assertFalse(MovementHelper.blocksPathingBarrier(true, Blocks.PALE_OAK_DOOR.defaultBlockState()));
        assertFalse(MovementHelper.blocksPathingBarrier(true, Blocks.PALE_OAK_FENCE_GATE.defaultBlockState()));
    }

    @Test
    public void thePolicyDoesNotTurnOrdinaryBlocksIntoBarriers() {
        assertFalse(MovementHelper.blocksPathingBarrier(false, Blocks.AIR.defaultBlockState()));
        assertFalse(MovementHelper.blocksPathingBarrier(false, Blocks.STONE.defaultBlockState()));
    }
}
