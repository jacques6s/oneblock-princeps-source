/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.utils.BlockStateInterface;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

/** Actual possibleToPlace gates with controlled world/BSI observations; no claim of a positive vanilla click/ray. */
public class BuilderCleanupPlacementPredicateTest {
    private static final BetterBlockPos TARGET = new BetterBlockPos(17, 21, 11);
    private static Unsafe allocator;
    private static Method derive;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
        derive = BuilderProcess.class.getDeclaredMethod("possibleToPlace", BlockState.class,
                int.class, int.class, int.class, BuilderProcess.BuilderCalculationContext.class, int[].class);
        derive.setAccessible(true);
    }

    @Test public void actualDerivationReportsNoSolidFaceRatherThanAPlacementOrRayFailure() throws Exception {
        Fixture f = fixture(); int[] rejected = new int[6];
        assertTrue(f.derive(Blocks.DIRT.defaultBlockState(), rejected).isEmpty());
        assertArrayEquals(new int[]{6, 0, 0, 0, 0, 0}, rejected);
        assertEquals(0, f.world.obstructionQueries);
        assertTrue(f.derive(Blocks.DIRT.defaultBlockState(), null).isEmpty());
    }

    @Test public void actualSurvivalRejectionIsDistinctFromCollisionAndAim() throws Exception {
        Fixture f = fixture();
        f.world.states.put(TARGET.below().asLong(), Blocks.OAK_SLAB.defaultBlockState());
        int[] rejected = new int[6];
        assertTrue(f.derive(Blocks.TORCH.defaultBlockState(), rejected).isEmpty());
        assertEquals(1, rejected[1]); assertEquals(0, f.world.obstructionQueries);
        assertEquals(0, rejected[2] + rejected[3] + rejected[4] + rejected[5]);
    }

    @Test public void actualLiveCollisionGateRejectsSelfOverlapButTheCenteredPoseClearsThatGate() throws Exception {
        Fixture f = fixture(); f.world.states.put(TARGET.below().asLong(), Blocks.DIRT.defaultBlockState());
        f.world.avatarBody = new AABB(16.759876854 - .3, 21, 11.564538494 - .3,
                16.759876854 + .3, 22.8, 11.564538494 + .3);
        int[] rejected = new int[6];
        assertTrue(f.derive(Blocks.DIRT.defaultBlockState(), rejected).isEmpty());
        assertArrayEquals(new int[]{5, 0, 1, 0, 0, 0}, rejected);
        assertFalse(f.builder.placementPlausible(TARGET, Blocks.DIRT.defaultBlockState()));
        // This different adjacent-target counterexample establishes why a cell-level arrival is insufficient.
        // It does NOT assert that the remote helper in the real 144 run was obstructed by the player.
        f.world.avatarBody = new AABB(16.5 - .3, 21, 11.5 - .3, 16.5 + .3, 22.8, 11.5 + .3);
        assertTrue(f.builder.placementPlausible(TARGET, Blocks.DIRT.defaultBlockState()));
    }

    @Test public void centeringDoesNotConcealAnIndependentEntityObstruction() throws Exception {
        Fixture f = fixture(); f.world.states.put(TARGET.below().asLong(), Blocks.DIRT.defaultBlockState());
        f.world.otherEntityBlocks = true;
        int[] rejected = new int[6];
        assertTrue(f.derive(Blocks.DIRT.defaultBlockState(), rejected).isEmpty());
        assertArrayEquals(new int[]{5, 0, 1, 0, 0, 0}, rejected);
        assertFalse(f.builder.placementPlausible(TARGET, Blocks.DIRT.defaultBlockState(), true));
        assertTrue(f.derive(Blocks.DIRT.defaultBlockState(), null).isEmpty());
    }

    private static Fixture fixture() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        TestWorld world = allocate(TestWorld.class); world.states = new HashMap<>();
        TestBlocks blocks = allocate(TestBlocks.class); blocks.worldView = world;
        LocalPlayer avatar = allocate(LocalPlayer.class);
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "world" -> world;
                    case "player" -> avatar;
                    default -> throw new AssertionError("unexpected later live-placement dependency: " + method.getName());
                });
        set(builder, "ctx", player);
        var context = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(context, "this$0", builder); set(context, "bsi", blocks);
        return new Fixture(builder, context, world);
    }

    private record Fixture(BuilderProcess builder, BuilderProcess.BuilderCalculationContext context, TestWorld world) {
        Optional<?> derive(BlockState desired, int[] rejected) throws Exception {
            return (Optional<?>) derive.invoke(builder, desired, TARGET.x, TARGET.y, TARGET.z, context, rejected);
        }
    }
    private static final class TestWorld extends ClientLevel {
        Map<Long, BlockState> states;
        AABB avatarBody;
        boolean otherEntityBlocks;
        int obstructionQueries;
        private TestWorld() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState()); }
        @Override public boolean isUnobstructed(Entity except, VoxelShape shape) {
            obstructionQueries++;
            return !otherEntityBlocks && (except != null || avatarBody == null || !shape.bounds().intersects(avatarBody));
        }
    }
    private static final class TestBlocks extends BlockStateInterface {
        TestWorld worldView;
        private TestBlocks() { super(null); }
        @Override public BlockState get0(int x, int y, int z) { return worldView.getBlockState(new BlockPos(x, y, z)); }
    }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(allocator.allocateInstance(type)); }
    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
