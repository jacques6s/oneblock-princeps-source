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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 */

package princeps.process.builder.v3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Physical cells Vanilla creates from one placement click.
 *
 * <p>This is deliberately independent of the schematic mask. A sparse schematic may mention only the lower door
 * half, while the server still creates the upper half; both forward simulation and speculative safety checks must see
 * the same consequence or they prove different worlds.
 */
final class PlacementConsequences {

    private PlacementConsequences() {
    }

    /** One secondary cell created together with the primary. */
    record Secondary(BlockPos cell, BlockState state) {
    }

    /**
     * The physical second cell created by a placement, or {@code null} when this state is not a primary half.
     */
    static Secondary secondaryOf(BlockPos primary, BlockState landed) {
        if (primary == null || landed == null) {
            return null;
        }
        if (landed.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && landed.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return new Secondary(primary.above(),
                    landed.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        }
        if (landed.hasProperty(BlockStateProperties.BED_PART)
                && landed.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && landed.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = landed.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return new Secondary(primary.relative(facing),
                    landed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        }
        return null;
    }
}
