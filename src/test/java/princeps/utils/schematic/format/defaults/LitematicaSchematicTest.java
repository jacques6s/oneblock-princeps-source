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

package princeps.utils.schematic.format.defaults;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Format-level regressions for data that block-state-only tests cannot see.
 *
 * <p>Litematica's signed region size describes which corner was selected first. The packed block container and its
 * block-entity positions are both indexed from the minimum corner, so reversing either stream mirrors one relative to
 * the other and puts sign text onto the wrong sign.
 */
public class LitematicaSchematicTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void negativeRegionKeepsBlockStatesAndBlockEntitiesAlignedAtTheMinimumCorner() {
        CompoundTag root = new CompoundTag();
        CompoundTag regions = new CompoundTag();
        CompoundTag region = new CompoundTag();

        CompoundTag position = new CompoundTag();
        position.putInt("x", 2);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);

        CompoundTag size = new CompoundTag();
        size.putInt("x", -2);
        size.putInt("y", 1);
        size.putInt("z", 1);
        region.put("Size", size);

        ListTag palette = new ListTag();
        palette.add(paletteEntry("minecraft:oak_sign"));
        palette.add(paletteEntry("minecraft:stone"));
        region.put("BlockStatePalette", palette);

        // Two bits per entry: local x=0 -> palette 0 (sign), local x=1 -> palette 1 (stone).
        region.putLongArray("BlockStates", new long[] { 1L << 2 });

        CompoundTag sign = new CompoundTag();
        sign.putInt("x", 0);
        sign.putInt("y", 0);
        sign.putInt("z", 0);
        CompoundTag front = new CompoundTag();
        front.putString("marker", "minimum-corner");
        sign.put("front_text", front);
        ListTag blockEntities = new ListTag();
        blockEntities.add(sign);
        region.put("TileEntities", blockEntities);

        regions.put("negative-x", region);
        root.put("Regions", regions);

        LitematicaSchematic schematic = new LitematicaSchematic(root);

        assertEquals(2, schematic.widthX());
        assertEquals(1, schematic.heightY());
        assertEquals(1, schematic.lengthZ());
        assertSame(Blocks.OAK_SIGN,
                schematic.desiredState(0, 0, 0, Blocks.AIR.defaultBlockState(), List.of()).getBlock());
        assertSame(Blocks.STONE,
                schematic.desiredState(1, 0, 0, Blocks.AIR.defaultBlockState(), List.of()).getBlock());

        CompoundTag carried = schematic.blockEntity(0, 0, 0);
        assertNotNull(carried);
        assertEquals("minimum-corner",
                carried.getCompound("front_text").orElseThrow().getString("marker").orElseThrow());
        assertNull(schematic.blockEntity(1, 0, 0));
    }

    private static CompoundTag paletteEntry(String name) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", name);
        return entry;
    }
}
