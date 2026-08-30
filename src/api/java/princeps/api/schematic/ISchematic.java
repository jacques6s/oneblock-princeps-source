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

package princeps.api.schematic;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Basic representation of a schematic. Provides the dimensions and the desired state for a given position relative to
 * the origin.
 *
 * @author leijurv
 */
public interface ISchematic {

    /**
     * Does the block at this coordinate matter to the schematic?
     * <p>
     * Normally just a check for if the coordinate is in the cube.
     * <p>
     * However, in the case of something like a map art, anything that's below the level of the map art doesn't matter,
     * so this function should return false in that case. (i.e. it doesn't really have to be air below the art blocks)
     *
     * @param x            The x position of the block, relative to the origin
     * @param y            The y position of the block, relative to the origin
     * @param z            The z position of the block, relative to the origin
     * @param currentState The current state of that block in the world, or null
     * @return Whether or not the specified position is within the bounds of this schematic
     */
    default boolean inSchematic(int x, int y, int z, BlockState currentState) {
        return x >= 0 && x < widthX() && y >= 0 && y < heightY() && z >= 0 && z < lengthZ();
    }

    default int size(Direction.Axis axis) {
        switch (axis) {
            case X:
                return widthX();
            case Y:
                return heightY();
            case Z:
                return lengthZ();
            default:
                throw new UnsupportedOperationException(axis + "");
        }
    }

    /**
     * Returns the desired block state at a given (X, Y, Z) position relative to the origin (0, 0, 0).
     *
     * @param x               The x position of the block, relative to the origin
     * @param y               The y position of the block, relative to the origin
     * @param z               The z position of the block, relative to the origin
     * @param current         The current state of that block in the world, or null
     * @param approxPlaceable The list of blockstates estimated to be placeable
     * @return The desired block state at the specified position
     */
    BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable);

    /**
     * The block-entity NBT the schematic carries for a position, or {@code null} when it carries none.
     *
     * <p>A block state cannot express a sign's text, a repeater has no NBT at all, and a chest's contents are not a
     * property — everything a block entity holds is invisible to {@link #desiredState}. Most schematic sources drop
     * that data at parse time and most consumers never wanted it, so the default here is honest: no NBT.
     *
     * <p>Implementations that DO carry it must return the compound in vanilla's own on-disk shape, i.e. what
     * {@code BlockEntity.saveWithId} would produce, so a consumer can read {@code front_text} / {@code back_text}
     * off a sign without knowing which file format it came from. Wrapper schematics must translate the coordinate
     * exactly as they translate {@link #desiredState}, or the text of one sign lands on another.
     *
     * @param x The x position of the block, relative to the origin
     * @param y The y position of the block, relative to the origin
     * @param z The z position of the block, relative to the origin
     * @return the block-entity compound for that cell, or {@code null}
     */
    default CompoundTag blockEntity(int x, int y, int z) {
        return null;
    }

    /**
     * Resets possible caches to avoid wrong behavior when moving the schematic around
     */
    default void reset() {}

    /**
     * @return The width (X axis length) of this schematic
     */
    int widthX();

    /**
     * @return The height (Y axis length) of this schematic
     */
    int heightY();

    /**
     * @return The length (Z axis length) of this schematic
     */
    int lengthZ();
}
