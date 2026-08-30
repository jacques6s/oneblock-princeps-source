/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package princeps.pathing.movement.movements;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementDiagonalBarrierTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void everyCellOfTheDiagonalPassageRejectsTheBasaltDoor() {
        for (int slot = 0; slot < 6; slot++) {
            BlockState[] passage = airPassage();
            passage[slot] = Blocks.PALE_OAK_DOOR.defaultBlockState();
            assertTrue("door slot " + slot, contains(passage));
        }
    }

    @Test
    public void anOpenBarrierIsStillNotAValidDiagonal() {
        BlockState[] passage = airPassage();
        passage[0] = Blocks.PALE_OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        assertTrue(contains(passage));

        passage = airPassage();
        passage[5] = Blocks.PALE_OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true);
        assertTrue(contains(passage));
    }

    @Test
    public void aBarrierFreePassageIsLeftToTheNormalGeometryChecks() {
        assertFalse(contains(airPassage()));
        BlockState[] passage = airPassage();
        passage[2] = Blocks.STONE.defaultBlockState();
        assertFalse("solid geometry is handled by the existing cost calculation", contains(passage));
    }

    private static BlockState[] airPassage() {
        BlockState[] states = new BlockState[6];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        return states;
    }

    private static boolean contains(BlockState[] states) {
        return MovementDiagonal.passageContainsPathingBarrier(
                states[0], states[1], states[2], states[3], states[4], states[5]);
    }
}
