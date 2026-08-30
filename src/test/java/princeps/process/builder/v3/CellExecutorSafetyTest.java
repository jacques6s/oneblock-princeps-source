/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder.v3;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.FillSchematic;
import princeps.api.utils.Rotation;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Headless pins for the irreversible completion and destruction rules in {@link CellExecutor}. */
public class CellExecutorSafetyTest {

    private static final BlockPos CELL = new BlockPos(12, 70, -8);
    private static final BlockPos STANCE = CELL.south();
    private static final Vec3 APPROACH = Vec3.atBottomCenterOf(STANCE);
    private static final Vec3 AIM = Vec3.atCenterOf(CELL);
    private static final Rotation ROTATION = new Rotation(180.0F, 0.0F);

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void onlyConfirmedServerEvidenceCanCompleteAWorldChange() {
        for (CellExecutor.Acknowledgements.Ack ack : CellExecutor.Acknowledgements.Ack.values()) {
            assertTrue(ack.name(), CellExecutor.acknowledgementAllowsCompletion(ack, true)
                    == (ack == CellExecutor.Acknowledgements.Ack.CONFIRMED));
        }
        assertFalse(CellExecutor.acknowledgementAllowsCompletion(
                CellExecutor.Acknowledgements.Ack.CONFIRMED, false));
    }

    @Test
    public void breakRefusesAnyStateOtherThanTheExactPlannedState() {
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
        BlockState south = north.setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
        BuildAction.Break action = new BuildAction.Break(CELL, STANCE, APPROACH, AIM, ROTATION,
                Direction.UP, north, true, false, HotbarSchedule.PICKAXE_SLOT, CELL.getY());

        assertNull(CellExecutor.unexpectedBreakState(action, north));
        assertNotNull("a property-only change must invalidate destructive authority",
                CellExecutor.unexpectedBreakState(action, south));
        assertNull("an already-empty target needs no destructive action",
                CellExecutor.unexpectedBreakState(action, Blocks.AIR.defaultBlockState()));
        assertNotNull("replaceable is not removed: water still occupies the authorised break cell",
                CellExecutor.unexpectedBreakState(action, Blocks.WATER.defaultBlockState()));
    }

    @Test
    public void scaffoldRemovalUsesTheSameExactStateGuard() {
        BlockState expected = Blocks.COBBLESTONE.defaultBlockState();
        BuildAction.RemoveScaffold action = new BuildAction.RemoveScaffold(CELL, STANCE, APPROACH, AIM, ROTATION,
                expected, false, HotbarSchedule.PICKAXE_SLOT, CELL.getY());

        assertNull(CellExecutor.unexpectedBreakState(action, expected));
        assertNotNull(CellExecutor.unexpectedBreakState(action, Blocks.STONE.defaultBlockState()));
    }

    @Test
    public void clickGateUsesVanillaOutlineForNonCollidingAndPartialBlocks() {
        AABB repeater = CellExecutor.clickableOutlineBox(
                Blocks.REPEATER.defaultBlockState(), null, BlockPos.ZERO);
        AABB torch = CellExecutor.clickableOutlineBox(
                Blocks.TORCH.defaultBlockState(), null, BlockPos.ZERO);
        AABB slab = CellExecutor.clickableOutlineBox(
                Blocks.STONE_SLAB.defaultBlockState(), null, BlockPos.ZERO);
        AABB openTrapdoor = CellExecutor.clickableOutlineBox(
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true),
                null, BlockPos.ZERO);

        assertTrue("a repeater is clickable despite having no collision", repeater.getYsize() > 0.0D);
        assertTrue("a torch is clickable despite having no collision", torch.getYsize() > 0.0D);
        assertEquals("a bottom slab exposes only its lower half", 0.5D, slab.maxY, 1.0E-9D);
        assertEquals("an open trapdoor exposes a full-height vertical outline", 1.0D,
                openTrapdoor.getYsize(), 1.0E-9D);
        assertTrue("an open trapdoor stays thin on one horizontal axis",
                openTrapdoor.getXsize() < 0.25D || openTrapdoor.getZsize() < 0.25D);
    }

    @Test
    public void layerGateChecksCellsThatWereAlreadyCorrectWhenThePlanWasMade() {
        BlockPos origin = new BlockPos(4, 70, 9);
        PredictedWorld snapshot = PredictedWorld.capture(
                (x, y, z) -> y == origin.getY()
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                (x, z) -> true, origin, origin.above(), 0);
        SchematicView view = SchematicView.capture("two layers",
                new FillSchematic(1, 2, 1, Blocks.STONE.defaultBlockState()),
                new Vec3i(origin.getX(), origin.getY(), origin.getZ()), snapshot,
                List.of(Blocks.STONE.defaultBlockState()));

        assertNull(CellExecutor.schematicLayerViolation(view, V3Settings.defaults(), origin.getY() + 1,
                ignored -> Blocks.STONE.defaultBlockState()));
        assertNotNull("the lower cell had no placement proof, but still belongs to the hard layer invariant",
                CellExecutor.schematicLayerViolation(view, V3Settings.defaults(), origin.getY() + 1,
                        ignored -> Blocks.DIRT.defaultBlockState()));
    }

    @Test
    public void layerGateIncludesCoveredAirCells() {
        BlockPos origin = new BlockPos(4, 70, 9);
        PredictedWorld snapshot = PredictedWorld.capture(
                (x, y, z) -> Blocks.AIR.defaultBlockState(), (x, z) -> true, origin, origin.above(), 0);
        SchematicView view = SchematicView.capture("air",
                new FillSchematic(1, 2, 1, Blocks.AIR.defaultBlockState()),
                new Vec3i(origin.getX(), origin.getY(), origin.getZ()), snapshot, List.of());

        assertNotNull(CellExecutor.schematicLayerViolation(view, V3Settings.defaults(), origin.getY() + 1,
                ignored -> Blocks.STONE.defaultBlockState()));
    }

    @Test
    public void pathfinderCannotMutateInsideTheBoxOrOnAProtectedFutureStance() {
        Vec3i origin = new Vec3i(10, 64, 20);
        BlockPos protectedOutside = new BlockPos(9, 64, 20);
        long protectedKey = BlockPos.asLong(protectedOutside.getX(), protectedOutside.getY(),
                protectedOutside.getZ());

        assertFalse("the minimum corner is immutable to navigation",
                PlannedBuilderProcess.pathMutationAllowed(origin, 3, 3, 3, ignored -> false,
                        10, 64, 20));
        assertFalse("the maximum corner is immutable to navigation",
                PlannedBuilderProcess.pathMutationAllowed(origin, 3, 3, 3, ignored -> false,
                        12, 66, 22));
        assertFalse("future plan cells remain protected even outside the schematic",
                PlannedBuilderProcess.pathMutationAllowed(origin, 3, 3, 3, key -> key == protectedKey,
                        protectedOutside.getX(), protectedOutside.getY(), protectedOutside.getZ()));
        assertTrue("unrelated terrain outside the plan remains available to ordinary navigation",
                PlannedBuilderProcess.pathMutationAllowed(origin, 3, 3, 3, key -> key == protectedKey,
                        30, 64, 30));
    }
}
