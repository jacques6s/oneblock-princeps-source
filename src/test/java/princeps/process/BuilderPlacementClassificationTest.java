/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderPlacementClassificationTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void ordinaryPropertylessCubesUseTheThroughputPath() {
        assertFalse(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.BLACK_STAINED_GLASS.defaultBlockState()));
        assertFalse(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.BLUE_ICE.defaultBlockState()));
        assertFalse(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.STONE.defaultBlockState()));
        assertFalse(BuilderProcess.shouldUseStanceRecovery(
                Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true));
        assertFalse(BuilderProcess.shouldUseStanceRecovery(
                Blocks.BLUE_ICE.defaultBlockState(), true));
        assertFalse(BuilderProcess.shouldUseStanceRecovery(
                Blocks.STONE.defaultBlockState(), true));
    }

    @Test
    public void placementControlledBlocksRemainStrict() {
        assertTrue(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.OAK_LOG.defaultBlockState()));
        assertTrue(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.PISTON.defaultBlockState()));
        assertTrue(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.OBSERVER.defaultBlockState()));
        assertTrue(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.STONE_SLAB.defaultBlockState()));
        assertTrue(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.OAK_STAIRS.defaultBlockState()));
        assertTrue(BuilderProcess.placementStateIsGeometrySensitive(
                Blocks.PALE_OAK_DOOR.defaultBlockState()));
        assertTrue(BuilderProcess.shouldUseStanceRecovery(
                Blocks.OAK_LOG.defaultBlockState(), true));
        assertTrue(BuilderProcess.shouldUseStanceRecovery(
                Blocks.PISTON.defaultBlockState(), true));
        assertTrue(BuilderProcess.shouldUseStanceRecovery(
                Blocks.OBSERVER.defaultBlockState(), true));
        assertTrue(BuilderProcess.shouldUseStanceRecovery(
                Blocks.STONE_SLAB.defaultBlockState(), false));
        assertTrue(BuilderProcess.shouldUseStanceRecovery(
                Blocks.OAK_STAIRS.defaultBlockState(), false));
        assertTrue(BuilderProcess.shouldUseStanceRecovery(
                Blocks.PALE_OAK_DOOR.defaultBlockState(), false));
        assertTrue(BuilderProcess.shouldUseStanceRecovery(
                Blocks.REDSTONE_WIRE.defaultBlockState(), false));
        // Wire's connection/power properties are environment-resolved, but it can never enter the ordinary CUBE
        // path because its collision shape is not a full block.
        assertFalse(Block.isShapeFullBlock(
                Blocks.REDSTONE_WIRE.defaultBlockState().getCollisionShape(null, null)));
    }
}
