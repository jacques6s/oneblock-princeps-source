/*
 * This file is part of Princeps, licensed under LGPL-3.0-or-later.
 */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.utils.BlockStateInterface;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Exercises the actual floor/body/template decision, without a global Minecraft or settings replacement. */
public class BuilderScaffoldStanceTest {
    private static final BetterBlockPos FEET = new BetterBlockPos(7, 20, 11);
    private static final BlockPos FLOOR = FEET.below();
    private static Unsafe allocator;
    private static Method candidate;
    private static Method existingStance;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
        candidate = BuilderProcess.class.getDeclaredMethod("isStandableOrScaffoldable",
                int.class, int.class, int.class, BuilderProcess.BuilderCalculationContext.class);
        candidate.setAccessible(true);
        existingStance = BuilderProcess.class.getDeclaredMethod("isStandable", int.class, int.class, int.class);
        existingStance.setAccessible(true);
    }

    @Test public void anExplicitAirCellInsideTheTemplateCanProvideATemporaryStanceFloor() throws Exception {
        assertTrue(fixture(Blocks.AIR.defaultBlockState(), true, false).allowed());
    }

    @Test public void caveAirInsideTheTemplateHasTheSameFloorMeaning() throws Exception {
        assertTrue(fixture(Blocks.CAVE_AIR.defaultBlockState(), true, false).allowed());
    }

    @Test public void voidAirInsideTheTemplateHasTheSameFloorMeaning() throws Exception {
        assertTrue(fixture(Blocks.VOID_AIR.defaultBlockState(), true, false).allowed());
    }

    @Test public void anOutsideTemplateFloorRemainsAvailable() throws Exception {
        Fixture f = fixture(Blocks.STONE.defaultBlockState(), true, false);
        set(f.context, "originX", FEET.x + 100);
        assertTrue(f.allowed());
    }

    @Test public void aCellReservedForARealTemplateBlockIsNeverAThrowawayFloor() throws Exception {
        assertFalse(fixture(Blocks.STONE_BRICKS.defaultBlockState(), true, false).allowed());
    }

    @Test public void missingThrowawayStockDoesNotCreateAnAirFloor() throws Exception {
        assertFalse(fixture(Blocks.AIR.defaultBlockState(), false, false).allowed());
        Fixture outside = fixture(Blocks.AIR.defaultBlockState(), false, false);
        set(outside.context, "originX", FEET.x + 100);
        assertFalse(outside.allowed());
    }

    @Test public void aNonreplaceableNonwalkableFloorIsNotOverwritten() throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), true, false);
        f.world.states.put(FLOOR.asLong(), Blocks.OAK_FENCE.defaultBlockState());
        assertFalse(f.allowed());
    }

    @Test public void aBlockedHeadCannotBeFixedByAddingAFloor() throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), true, false);
        f.world.states.put(FEET.above().asLong(), Blocks.OAK_SLAB.defaultBlockState());
        assertFalse(f.allowed());
    }

    @Test public void aBlockedBodyCannotBeFixedByAddingAFloor() throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), true, false);
        f.world.states.put(FEET.asLong(), Blocks.OAK_SLAB.defaultBlockState());
        assertFalse(f.allowed());
    }

    @Test public void anExistingRealFloorNeedsNoThrowawayStockOrTemplatePermission() throws Exception {
        Fixture f = fixture(Blocks.STONE_BRICKS.defaultBlockState(), false, false);
        f.world.states.put(FLOOR.asLong(), Blocks.STONE.defaultBlockState());
        assertTrue(f.allowed());
    }

    @Test public void rowModeStillForbidsThrowawaysInPlannedAir() throws Exception {
        for (BlockState air : List.of(Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.VOID_AIR.defaultBlockState())) {
            assertFalse(fixture(air, true, true).allowed());
        }
    }

    @Test public void rowModeCanStillLayItsLicensedExactTemplatePixel() throws Exception {
        Fixture f = fixture(Blocks.STONE.defaultBlockState(), false, true);
        assertTrue(f.allowed());
        set(f.context, "rowFrontier", FEET.z + 1);
        assertFalse(f.allowed());
    }

    @Test public void aFluidBodyRejectsTheExistingStanceBeforeConstructingALiveBlockView() throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), true, false);
        f.world.states.put(FEET.asLong(), Blocks.WATER.defaultBlockState());
        assertFalse(f.canStand());
    }

    @Test public void aFluidHeadRejectsTheExistingStanceBeforeConstructingALiveBlockView() throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), true, false);
        f.world.states.put(FEET.above().asLong(), Blocks.WATER.defaultBlockState());
        assertFalse(f.canStand());
    }

    private static Fixture fixture(BlockState planned, boolean hasThrowaway, boolean rowMode) throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        TestLevel world = allocate(TestLevel.class); world.states = new HashMap<>();
        TestBlocks blocks = allocate(TestBlocks.class); blocks.level = world;
        IPlayerContext ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("world")) return world;
                    throw new AssertionError("unexpected live dependency " + method.getName());
                });
        set(builder, "ctx", ctx);
        set(builder, "buildInRows", rowMode);
        var context = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(context, "this$0", builder);
        set(context, "bsi", blocks);
        set(context, "hasThrowaway", hasThrowaway);
        set(context, "originX", FEET.x - 3);
        set(context, "originY", FEET.y - 1);
        set(context, "originZ", FEET.z - 3);
        set(context, "placeable", List.of(Blocks.STONE.defaultBlockState()));
        set(context, "rowMode", rowMode);
        set(context, "rowSweepAlongX", true);
        set(context, "rowBandStart", FEET.x - 3);
        set(context, "rowFrontier", FEET.z);
        set(context, "schematic", new AbstractSchematic(7, 4, 7) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> stock) {
                return planned;
            }
        });
        return new Fixture(builder, context, world);
    }

    private record Fixture(BuilderProcess builder, BuilderProcess.BuilderCalculationContext context, TestLevel world) {
        boolean canStand() throws Exception {
            // This world has no border/chunk provider; a rejected fluid stance must not construct a live BSI.
            Map<Long, BlockState> before = new HashMap<>(world.states);
            boolean result = (boolean) existingStance.invoke(builder, FEET.x, FEET.y, FEET.z);
            assertEquals("stance inspection must not change the world", before, world.states);
            return result;
        }

        boolean allowed() throws Exception {
            Map<Long, BlockState> before = new HashMap<>(world.states);
            boolean result = (boolean) candidate.invoke(builder, FEET.x, FEET.y, FEET.z, context);
            assertEquals("stance inspection must not change the world", before, world.states);
            return result;
        }
    }

    private static final class TestLevel extends ClientLevel {
        // BetterBlockPos and vanilla BlockPos have different hash functions; key both views by coordinates.
        Map<Long, BlockState> states;
        private TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }
    }

    private static final class TestBlocks extends BlockStateInterface {
        TestLevel level;
        private TestBlocks() { super(null); }
        @Override public BlockState get0(int x, int y, int z) {
            return level.getBlockState(new BlockPos(x, y, z));
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(allocator.allocateInstance(type));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
