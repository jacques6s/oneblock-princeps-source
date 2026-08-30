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

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A block entity must travel through every schematic transform with the block state it belongs to. A coordinate bug
 * here does not lose all sign text; it writes valid text onto a different sign, which is harder to notice and worse.
 */
public class BlockEntityTransformTest {

    private static Block[][] blocks;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Blocks.* must not be read during class initialisation: doing so precedes Bootstrap and poisons the shared
        // registry for every test class in this worker.
        blocks = new Block[][] {
                { Blocks.STONE, Blocks.DIRT, Blocks.GRANITE },
                { Blocks.DIORITE, Blocks.ANDESITE, Blocks.COBBLESTONE }
        };
    }

    @Test
    public void mirroredCoordinatesKeepNbtAttachedToTheirState() {
        for (Mirror mirror : Mirror.values()) {
            assertAligned(new MirroredSchematic(source(), mirror));
        }
    }

    @Test
    public void rotatedCoordinatesKeepNbtAttachedToTheirState() {
        for (Rotation rotation : Rotation.values()) {
            assertAligned(new RotatedSchematic(source(), rotation));
        }
    }

    @Test
    public void compositeOffsetsBlockEntityCoordinatesWithTheSubSchematic() {
        CompositeSchematic composite = new CompositeSchematic(0, 0, 0);
        composite.put(source(), 3, 2, 4);

        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 3; z++) {
                BlockState desired = composite.desiredState(3 + x, 2, 4 + z,
                        Blocks.AIR.defaultBlockState(), List.of());
                assertMarker(desired, composite.blockEntity(3 + x, 2, 4 + z));
            }
        }
    }

    private static void assertAligned(ISchematic transformed) {
        for (int x = 0; x < transformed.widthX(); x++) {
            for (int z = 0; z < transformed.lengthZ(); z++) {
                BlockState desired = transformed.desiredState(x, 0, z, Blocks.AIR.defaultBlockState(), List.of());
                assertMarker(desired, transformed.blockEntity(x, 0, z));
            }
        }
    }

    private static void assertMarker(BlockState desired, CompoundTag nbt) {
        assertNotNull(nbt);
        assertEquals(BuiltInRegistries.BLOCK.getKey(desired.getBlock()).toString(),
                nbt.getString("marker").orElseThrow());
    }

    private static ISchematic source() {
        return new ISchematic() {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           List<BlockState> approxPlaceable) {
                return blocks[x][z].defaultBlockState();
            }

            @Override
            public CompoundTag blockEntity(int x, int y, int z) {
                CompoundTag tag = new CompoundTag();
                tag.putString("marker", BuiltInRegistries.BLOCK.getKey(blocks[x][z]).toString());
                return tag;
            }

            @Override
            public int widthX() {
                return 2;
            }

            @Override
            public int heightY() {
                return 1;
            }

            @Override
            public int lengthZ() {
                return 3;
            }
        };
    }
}
