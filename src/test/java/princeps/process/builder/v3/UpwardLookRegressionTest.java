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
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regressions for the complete and authoritative upward-look stance search. */
public class UpwardLookRegressionTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    @Test
    public void footColumnsCoverAllEightHorizontalsAtEachDepthInStableOrder() {
        assertEquals(24, UpwardLook.FOOT_COLUMNS);
        assertEquals(24, UpwardLook.FOOT_OFFSETS.size());
        for (int index = 0; index < UpwardLook.FOOT_OFFSETS.size(); index++) {
            int expectedDepth = -1 - index / 8;
            assertEquals(expectedDepth, UpwardLook.FOOT_OFFSETS.get(index).getY());
        }
        assertEquals(new Vec3i(-1, -1, 0), UpwardLook.FOOT_OFFSETS.get(0));
        assertEquals(new Vec3i(-1, -2, 0), UpwardLook.FOOT_OFFSETS.get(8));
        assertEquals(new Vec3i(-1, -3, 0), UpwardLook.FOOT_OFFSETS.get(16));
    }

    @Test
    public void focusedColumnFilterAcceptsADeepStanceAndRejectsTheOldShallowOne() {
        BlockPos target = new BlockPos(4, 70, 9);
        BlockPos deep = target.offset(-1, -3, 0);
        PlacementOracle.StanceFilter filter = UpwardLook.fromColumn(target, deep);

        assertTrue(filter.allows(target, deep, Direction.DOWN));
        assertFalse(filter.allows(target, target.offset(-1, -1, 0), Direction.DOWN));
        assertFalse(filter.allows(target, deep, Direction.UP));
    }

    @Test
    public void passableFinishedSchematicStateAtTheFeetIsAuthoritativelyUsable() {
        BlockPos target = new BlockPos(0, 67, 0);
        BlockPos feet = target.offset(-1, -1, 0);
        Vec3i origin = new Vec3i(-2, 63, -2);
        ISchematic schematic = sparseSchematic(origin, target, feet);
        PredictedWorld world = world((x, y, z) -> {
            if (x == feet.getX() && y == feet.getY() && z == feet.getZ()) {
                return Blocks.REDSTONE_WIRE.defaultBlockState();
            }
            return y == feet.getY() - 1 ? Blocks.AZALEA.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        });
        SchematicView view = SchematicView.capture("passable-feet", schematic, origin, world, List.of());

        UpwardLook.FootColumn column = UpwardLook.classifyFootColumns(world, view, target).stream()
                .filter(candidate -> candidate.feet().equals(feet))
                .findFirst().orElseThrow();

        assertTrue(view.wantsBlock(feet));
        assertTrue(world.isStandable(feet.getX(), feet.getY(), feet.getZ()));
        assertEquals(UpwardLook.Status.USABLE, column.status());
    }

    @Test
    public void anUnwalkableFloorIsNotStructurallyPromotedToUsable() {
        BlockPos target = new BlockPos(0, 67, 0);
        BlockPos feet = target.offset(-1, -1, 0);
        BlockPos floor = feet.below();
        PredictedWorld world = world((x, y, z) -> {
            if (x == floor.getX() && y == floor.getY() && z == floor.getZ()) {
                return Blocks.CACTUS.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        });

        UpwardLook.FootColumn column = UpwardLook.classifyFootColumns(world, null, target).stream()
                .filter(candidate -> candidate.feet().equals(feet))
                .findFirst().orElseThrow();

        assertFalse(world.isStandable(feet.getX(), feet.getY(), feet.getZ()));
        assertEquals(UpwardLook.Status.BLOCKED_BY_TERRAIN, column.status());
    }

    private static PredictedWorld world(PredictedWorld.StateSource states) {
        return PredictedWorld.capture(states, (x, z) -> true, new Vec3i(-5, 60, -5),
                new Vec3i(5, 72, 5), 0, V3Settings.defaults());
    }

    /**
     * The order helper blocks go down in is the order each one is proven against, so it is asserted rather than
     * assumed: the stepping stone makes the foot column standable, every support is what the NEXT support is placed
     * against, and the click surface is last because it is placed against the last support.
     *
     * <p>This is what the bridge shape could not express. A single {@code bridge} field can hold one helper block on a
     * standing lateral neighbour; every {@code observer[facing=up]} in etz-basalt's y=-58 has no standing lateral
     * neighbour at all and needs TWO, so a chain is the shape and its ORDER is the whole contract.
     */
    @Test
    public void helpersGoDownStoneFirstThenTheChainInOrderThenTheClickSurface() {
        BlockPos target = new BlockPos(3, -58, 23);
        BlockPos scaffold = UpwardLook.clickSurface(target);
        BlockPos stone = new BlockPos(2, -60, 23);
        List<BlockPos> supports = List.of(new BlockPos(3, -58, 22), new BlockPos(3, -57, 22));
        UpwardLook.Solved solved = new UpwardLook.Solved(target, scaffold, supports, stone, null, null, null,
                List.of());

        assertEquals(List.of(stone, supports.get(0), supports.get(1), scaffold),
                solved.helpersInPlacementOrder());
        assertEquals(scaffold, target.above());
    }

    /** With nothing to climb, the chain is empty and the click surface is the only helper block — the shape plan 5.7.2
     *  describes, and the one the 448 pistons of y=-57 mostly get. */
    @Test
    public void anEmptyChainLeavesTheClickSurfaceAsTheOnlyHelperBlock() {
        BlockPos target = new BlockPos(3, -57, 23);
        UpwardLook.Solved solved = new UpwardLook.Solved(target, UpwardLook.clickSurface(target), List.of(), null,
                null, null, null, List.of());

        assertEquals(List.of(target.above()), solved.helpersInPlacementOrder());
        assertTrue(UpwardLook.MAX_SUPPORT_HELPERS >= 2);
    }

    private static ISchematic sparseSchematic(Vec3i origin, BlockPos target, BlockPos feet) {
        BlockState upward = Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN);
        return new ISchematic() {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                BlockPos absolute = new BlockPos(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                if (absolute.equals(target)) {
                    return upward;
                }
                if (absolute.equals(feet)) {
                    return Blocks.REDSTONE_WIRE.defaultBlockState();
                }
                return Blocks.AIR.defaultBlockState();
            }

            @Override
            public int widthX() {
                return 5;
            }

            @Override
            public int heightY() {
                return 5;
            }

            @Override
            public int lengthZ() {
                return 5;
            }
        };
    }
}
