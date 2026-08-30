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

package princeps.process.builder.v3;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where a body's feet actually come to rest, as opposed to where the integer coordinate says they do.
 *
 * <p>Every approach point the oracle proves used to be built at {@code stance.getY()}. That is correct only for a
 * body standing on a full block, where the surface it rests on is the floor of the stance cell — and wrong for every
 * block a body sinks INTO. The basalt farm is full of one: soul sand is 0.875 high, so a body standing on it occupies
 * the soul sand's own cell and rests an eighth of a block below the integer.
 *
 * <p>The consequence is not cosmetic. The eye is proven an eighth of a block above where it will be, the ray it casts
 * is a different ray, and the live gate refuses to recognise a click as the one that was proven. Measured on the
 * basalt run before the fix: three stalls of 65, 66 and 69 ticks, every one of them at {@code y=-59.125} with a
 * repeater in hand, which is exactly where this farm puts its repeaters.
 *
 * <p>What these tests pin is the METHOD rather than the block. Nothing here names soul sand as a special case: the
 * height comes off the collision shape, so slabs, snow layers, farmland and dirt paths are covered by the same three
 * lines and no list has to be kept up to date. The slab case below is in the suite for exactly that reason — it is a
 * block nobody thought about while fixing the one that hurt.
 */
public class FootHeightTest {

    private static final BlockPos STANCE = new BlockPos(0, 0, 0);

    private static final BlockPos SUPPORT = STANCE.below();

    /** Real vanilla shapes and states, headless -- the collision shapes these assertions read are vanilla's own. */
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

    /** Air underfoot in the stance cell: the body rests on the floor of the cell, which IS the integer. */
    @Test
    public void aBodyInAnEmptyCellRestsOnTheIntegerCoordinate() {
        assertEquals("nothing to sink into, so the old assumption was right here and stays right",
                0.0D, PlacementOracle.footY(worldOf(Blocks.AIR.defaultBlockState()), STANCE), 1.0E-9D);
    }

    /**
     * Soul sand: 0.875 high, so the body stands INSIDE the block's own cell and rests 0.125 below the integer.
     *
     * <p>This is the case that cost the run, and the number is asserted exactly rather than as "less than one" —
     * 0.125 is what has to come out for the proven eye and the live eye to be the same eye.
     */
    @Test
    public void aBodyOnSoulSandRestsAnEighthOfABlockLow() {
        double foot = PlacementOracle.footY(worldOf(Blocks.SOUL_SAND.defaultBlockState()), STANCE);

        assertEquals("soul sand is 0.875 high inside the support cell, an eighth below canonical stance zero",
                -0.125D, foot, 1.0E-9D);
    }

    /** A bottom slab, which nobody was thinking about: same three lines, no new case, half a block. */
    @Test
    public void theSameDerivationCoversABlockNobodyFixedItFor() {
        BlockState bottomSlab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);

        assertEquals("a bottom slab is half a block and the collision shape says so without being asked about slabs",
                -0.5D, PlacementOracle.footY(worldOf(bottomSlab), STANCE), 1.0E-9D);
    }

    /**
     * A full block in the stance cell reports the cell's own ceiling.
     *
     * <p>Not a stance the planner would ever choose — {@code isStandable} rejects it long before this is asked — but
     * the derivation must not answer something below the integer for it, because an approach point under the floor is
     * a body inside the ground.
     */
    @Test
    public void aFullBlockNeverReportsBelowTheFloor() {
        assertTrue("a foot height below the stance floor would put the body inside the ground",
                PlacementOracle.footY(worldOf(Blocks.STONE.defaultBlockState()), STANCE) >= 0.0D);
    }

    /** One block beneath the canonical stance, air everywhere else. */
    private static PredictedWorld worldOf(BlockState underStance) {
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> new BlockPos(x, y, z).equals(SUPPORT) ? underStance : air,
                (x, z) -> true, new Vec3i(-3, -3, -3), new Vec3i(3, 3, 3), 0, V3Settings.defaults());
    }
}
