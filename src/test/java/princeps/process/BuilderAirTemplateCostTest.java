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

package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.pathing.PlacementLicence;
import princeps.api.schematic.AbstractSchematic;
import princeps.pathing.movement.CalculationContext;
import princeps.utils.pathing.BetterWorldBorder;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static princeps.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Calls the real search-cost and licence methods, not a mirror of their conditions.
 * The trace219 refused Ascend requested support at (64,-60,65), where both the
 * template and server contained AIR. Empty/non-block inventory slots are represented
 * by AIR in approxPlaceable, but that sentinel cannot be a template-placement licence.
 */
public class BuilderAirTemplateCostTest {
    private static final int X = 64;
    private static final int Y = -60;
    private static final int Z = 65;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // CalculationContext's static water-bucket stack needs this holder even
        // though these world-free cost tests never inspect bucket components.
        // Match the existing headless fixture convention, limited to that item.
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.EMPTY);
        }
    }

    @Test
    public void laneARejectsAirSupportWithEmptyInventorySlotsAndRealThrowaway() throws Exception {
        BuilderProcess.BuilderCalculationContext context = context(
                BuilderProcess.Lane.A_NO_PLACING, Blocks.AIR.defaultBlockState(), inventory(Blocks.STONE.defaultBlockState()));
        assertSame(PlacementLicence.NONE, context.placementLicence());
        assertFalse(context.placementLicence().permitsPlacement(X, Y, Z));
        assertEquals(COST_INF, context.costOfPlacingAt(X, Y, Z, Blocks.AIR.defaultBlockState()), 0.0D);
    }

    @Test
    public void laneAAlsoRejectsAirSupportWhenEverySlotIsAnAirSentinel() throws Exception {
        assertForbidden(Blocks.AIR.defaultBlockState(), inventory(Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void everyAirVariantIsExcludedFromTheTemplateExemption() throws Exception {
        for (BlockState air : List.of(Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState())) {
            assertTrue(air.isAir());
            assertForbidden(air, inventory(air));
        }
    }

    @Test
    public void removingEmptySlotsDoesNotChangeTheNoHelperDecision() throws Exception {
        assertForbidden(Blocks.AIR.defaultBlockState(), Collections.nCopies(9, Blocks.STONE.defaultBlockState()));
    }

    @Test
    public void laneBRetainsItsLicensedAirHelperCost() throws Exception {
        BuilderProcess.BuilderCalculationContext context = context(
                BuilderProcess.Lane.B_HELPERS_ALLOWED, Blocks.AIR.defaultBlockState(), inventory(Blocks.STONE.defaultBlockState()));
        assertTrue(context.placementLicence().permitsPlacement(X, Y, Z));
        double cost = context.costOfPlacingAt(X, Y, Z, Blocks.AIR.defaultBlockState());
        assertTrue(cost > 0.0D && cost < COST_INF);
    }

    @Test
    public void laneAKeepsFreePlacementOfAnAvailableOrdinaryTemplateBlock() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BuilderProcess.BuilderCalculationContext context = context(BuilderProcess.Lane.A_NO_PLACING, stone, inventory(stone));
        assertSame(PlacementLicence.NONE, context.placementLicence());
        assertEquals(0.0D, context.costOfPlacingAt(X, Y, Z, Blocks.AIR.defaultBlockState()), 0.0D);
    }

    @Test
    public void missingAndGeometrySensitiveTemplateBlocksStayForbidden() throws Exception {
        assertForbidden(Blocks.GLASS.defaultBlockState(), inventory(Blocks.STONE.defaultBlockState()));
        BlockState piston = Blocks.PISTON.defaultBlockState();
        assertTrue(BuilderProcess.placementStateIsGeometrySensitive(piston));
        assertForbidden(piston, inventory(piston));
    }

    @Test
    public void laneAStillForbidsAHelperOutsideTheTemplate() throws Exception {
        BuilderProcess.BuilderCalculationContext context = context(
                BuilderProcess.Lane.A_NO_PLACING, Blocks.AIR.defaultBlockState(), inventory(Blocks.STONE.defaultBlockState()));
        assertEquals(COST_INF, context.costOfPlacingAt(X + 1, Y, Z, Blocks.AIR.defaultBlockState()), 0.0D);
    }

    private static void assertForbidden(BlockState desired, List<BlockState> stock) throws Exception {
        BuilderProcess.BuilderCalculationContext context = context(BuilderProcess.Lane.A_NO_PLACING, desired, stock);
        assertEquals(COST_INF, context.costOfPlacingAt(X, Y, Z, Blocks.AIR.defaultBlockState()), 0.0D);
    }

    private static List<BlockState> inventory(BlockState firstSlot) {
        List<BlockState> stock = new ArrayList<>(Collections.nCopies(9, Blocks.AIR.defaultBlockState()));
        stock.set(0, firstSlot);
        return stock;
    }

    /**
     * A thread-local, world-free snapshot for the actual BCC methods under test.
     * Their normal constructors require a live LocalPlayer, world, and inventory.
     * Allocation bypasses only those constructors; no cost/licence method is mocked
     * or overridden, and no global Princeps/player state is changed. A new fixture is
     * created per assertion. Unused context fields deliberately stay unset so an
     * unexpected new dependency fails the test instead of consulting a live world.
     */
    private static BuilderProcess.BuilderCalculationContext context(
            BuilderProcess.Lane lane, BlockState desired, List<BlockState> stock) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe allocator = (Unsafe) singleton.get(null);
        BuilderProcess owner = (BuilderProcess) allocator.allocateInstance(BuilderProcess.class);
        BuilderProcess.BuilderCalculationContext context = (BuilderProcess.BuilderCalculationContext)
                allocator.allocateInstance(BuilderProcess.BuilderCalculationContext.class);
        List<BlockState> snapshot = List.copyOf(stock);
        set(BuilderProcess.class, owner, "approxPlaceable", snapshot);
        Class<?> type = BuilderProcess.BuilderCalculationContext.class;
        set(type, context, "this$0", owner);
        set(type, context, "placeable", snapshot);
        set(type, context, "lane", lane);
        set(type, context, "scaffoldLicensed", lane == BuilderProcess.Lane.B_HELPERS_ALLOWED);
        set(type, context, "originX", X);
        set(type, context, "originY", Y);
        set(type, context, "originZ", Z);
        set(type, context, "schematic", new AbstractSchematic(1, 1, 1) {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                return desired;
            }
        });
        set(CalculationContext.class, context, "hasThrowaway", true);
        set(CalculationContext.class, context, "worldBorder", new BetterWorldBorder(new WorldBorder()));
        return context;
    }

    private static void set(Class<?> declaringClass, Object target, String name, Object value) throws Exception {
        Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
