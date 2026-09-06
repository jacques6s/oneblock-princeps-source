/*
 * This file is part of Princeps.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package princeps.process;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.IPlayerController;
import princeps.pathing.movement.CalculationContext;
import princeps.utils.BlockStateInterface;
import princeps.utils.PrincepsProcessHelper;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

/** Calls the production verdict methods; only the world and player inputs are fixtures.
 * The recovery caller additionally requires a real client integration check. */
public class BuilderMaterialVerdictTest {
    private static final BetterBlockPos TARGET = new BetterBlockPos(120, -59, 207);
    private static Unsafe allocator;

    @BeforeClass
    public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : new Item[]{Items.WATER_BUCKET, Items.GLASS, Items.STONE, Items.TORCH, Items.STICK}) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        allocator = (Unsafe) singleton.get(null);
    }

    @Test
    public void absentHotbarMaterialIsUnknownAndLeavesWorkActive() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState());
        assertUnknown(f.owner.urteileUeber(TARGET, f.context));
        f.assertNoNegativeEvidence();
        assertEquals(0, get(f.owner, "orientedSearchesThisTick"));
        assertEquals(0L, get(f.owner, "stanceSearchNanos"));
    }

    @Test
    public void materialElsewhereInInventoryDoesNotBecomeGeometryEvidence() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState());
        f.inventory.getNonEquipmentItems().set(9, new ItemStack(Items.GLASS));
        for (int i = 0; i < 3; i++) {
            assertUnknown(f.owner.urteileUeber(TARGET, f.context));
        }
        f.assertNoNegativeEvidence();
    }

    @Test
    public void readOnlyJudgmentDoesNotParkOrSpendBudgetForMissingMaterial() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState());
        assertUnknown(f.owner.urteileUeber(TARGET, f.context, false));
        f.assertNoNegativeEvidence();
        assertEquals(0, get(f.owner, "orientedSearchesThisTick"));
    }

    @Test
    public void refillReevaluatesTheSameUnchangedWorldAndRealNegativeRemainsCached() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState());
        assertUnknown(f.owner.urteileUeber(TARGET, f.context));
        f.inventory.getNonEquipmentItems().set(0, new ItemStack(Items.GLASS));
        // Every candidate stance is water. The only solid neighbour is in the excluded target column.
        assertParkReason(f.owner.urteileUeber(TARGET, f.context), "NO_STANCE");
        assertEquals(1, get(f.owner, "orientedSearchesThisTick"));
        assertEquals(1, f.verdicts().size());
        assertEquals(1, f.parked().size());
        f.inventory.getNonEquipmentItems().set(0, ItemStack.EMPTY);
        assertParkReason(f.owner.urteileUeber(TARGET, f.context), "NO_STANCE");
        assertEquals("a proven negative is reused", 1, get(f.owner, "orientedSearchesThisTick"));
    }

    @Test
    public void anExistingPositiveGoalSurvivesAHotbarGap() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState());
        Goal proven = new GoalBlock(TARGET.offset(2, 0, 0));
        f.goals().put(TARGET.asLong(), Optional.of(proven));
        BuilderProcess.CellUrteil result = f.owner.urteileUeber(TARGET, f.context);
        assertTrue(result instanceof BuilderProcess.CellUrteil.Setzen);
        assertSame(proven, ((BuilderProcess.CellUrteil.Setzen) result).ziel());
        assertTrue(f.verdicts().isEmpty());
        assertEquals(0, get(f.owner, "orientedSearchesThisTick"));
    }

    @Test
    public void absentFaceIsStillAWorldFactWithoutMaterial() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState());
        f.world.blocks.clear();
        assertParkReason(f.owner.urteileUeber(TARGET, f.context), "NO_FACE");
        assertEquals(1, f.verdicts().size());
        assertEquals(0, get(f.owner, "orientedSearchesThisTick"));
    }

    @Test
    public void wrongLandedBlockCanStillBeRoutedForBreakingWithoutMaterial() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState());
        f.world.blocks.put(TARGET.asLong(), Blocks.STONE.defaultBlockState());
        assertTrue(f.owner.urteileUeber(TARGET, f.context) instanceof BuilderProcess.CellUrteil.Setzen);
        f.assertNoNegativeEvidence();
    }

    @Test
    public void hotbarMatchingPreservesItemAliasesAndRejectsNonPlacementItems() throws Exception {
        Fixture f = fixture(Blocks.WALL_TORCH.defaultBlockState());
        ItemStack torch = new ItemStack(Items.TORCH);
        f.inventory.getNonEquipmentItems().set(8, torch);
        assertSame(torch, invoke(f.owner, "hotbarStackThatPlaces", new Class<?>[]{BlockState.class},
                Blocks.WALL_TORCH.defaultBlockState()));
        f.inventory.getNonEquipmentItems().set(8, new ItemStack(Items.STICK));
        assertNull(invoke(f.owner, "hotbarStackThatPlaces", new Class<?>[]{BlockState.class},
                Blocks.GLASS.defaultBlockState()));
    }

    private static void assertUnknown(BuilderProcess.CellUrteil verdict) {
        assertTrue("missing probe material is unknown, not " + verdict,
                verdict instanceof BuilderProcess.CellUrteil.Unbekannt);
    }

    private static void assertParkReason(BuilderProcess.CellUrteil verdict, String reason) {
        assertTrue("expected geometric park, got " + verdict, verdict instanceof BuilderProcess.CellUrteil.Parken);
        assertEquals(reason, String.valueOf((Object) ((BuilderProcess.CellUrteil.Parken) verdict).grund()));
    }

    private static Fixture fixture(BlockState desired) throws Exception {
        BuilderProcess owner = (BuilderProcess) allocator.allocateInstance(BuilderProcess.class);
        // Real collections, fresh per test. No BuilderProcess decision is overridden.
        for (Field field : BuilderProcess.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && (Map.class.isAssignableFrom(field.getType())
                    || Set.class.isAssignableFrom(field.getType())) && !field.getType().isInterface()) {
                field.setAccessible(true);
                field.set(owner, field.getType().getConstructor().newInstance());
            }
        }
        FixtureWorld world = (FixtureWorld) allocator.allocateInstance(FixtureWorld.class);
        world.blocks = new HashMap<>();
        world.blocks.put(TARGET.asLong(), Blocks.AIR.defaultBlockState());
        world.blocks.put(TARGET.below().asLong(), Blocks.STONE.defaultBlockState());
        LocalPlayer player = (LocalPlayer) allocator.allocateInstance(LocalPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        set(Player.class, player, "inventory", inventory);
        set(Entity.class, player, "position", new Vec3(130.5, -59, 217.5));
        set(Entity.class, player, "onGround", true);
        IPlayerController controller = (IPlayerController) Proxy.newProxyInstance(
                IPlayerController.class.getClassLoader(), new Class<?>[]{IPlayerController.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBlockReachDistance")) return 3.5D;
                    throw new AssertionError("unexpected controller dependency: " + method);
                });
        IPlayerContext playerContext = (IPlayerContext) Proxy.newProxyInstance(
                IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "player" -> player;
                    case "world" -> world;
                    case "playerController" -> controller;
                    case "playerFeet" -> new BetterBlockPos(130, -59, 217);
                    default -> throw new AssertionError("unexpected player context dependency: " + method);
                });
        set(PrincepsProcessHelper.class, owner, "ctx", playerContext);
        set(BuilderProcess.class, owner, "approxPlaceable", Collections.nCopies(36, Blocks.AIR.defaultBlockState()));
        FixtureBlocks bsi = (FixtureBlocks) allocator.allocateInstance(FixtureBlocks.class);
        bsi.fixture = world;
        BuilderProcess.BuilderCalculationContext context = (BuilderProcess.BuilderCalculationContext)
                allocator.allocateInstance(BuilderProcess.BuilderCalculationContext.class);
        Class<?> type = BuilderProcess.BuilderCalculationContext.class;
        set(type, context, "this$0", owner);
        set(type, context, "originX", TARGET.x);
        set(type, context, "originY", TARGET.y);
        set(type, context, "originZ", TARGET.z);
        set(type, context, "schematic", new AbstractSchematic(1, 1, 1) {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           java.util.List<BlockState> available) {
                return desired;
            }
        });
        set(CalculationContext.class, context, "bsi", bsi);
        Set<BetterBlockPos> active = (Set<BetterBlockPos>) get(owner, "activeCells");
        active.add(TARGET);
        ((Set<BetterBlockPos>) get(owner, "incorrectPositions")).add(TARGET);
        return new Fixture(owner, context, world, inventory);
    }

    private record Fixture(BuilderProcess owner, BuilderProcess.BuilderCalculationContext context,
                           FixtureWorld world, Inventory inventory) {
        private Long2LongOpenHashMap verdicts() throws Exception { return (Long2LongOpenHashMap) get(owner, "cellVerdicts"); }
        private Map<?, ?> parked() throws Exception { return (Map<?, ?>) get(owner, "parkedCells"); }
        private Map<Long, Optional<Goal>> goals() throws Exception {
            return (Map<Long, Optional<Goal>>) get(owner, "orientedGoalCache");
        }
        private void assertNoNegativeEvidence() throws Exception {
            assertTrue(verdicts().isEmpty());
            assertTrue(parked().isEmpty());
            assertTrue(goals().isEmpty());
            assertTrue(((Set<?>) get(owner, "activeCells")).contains(TARGET));
            assertTrue(((Set<?>) get(owner, "incorrectPositions")).contains(TARGET));
        }
    }

    private static class FixtureWorld extends ClientLevel {
        private Map<Long, BlockState> blocks;
        private FixtureWorld() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos.asLong(), Blocks.WATER.defaultBlockState());
        }
    }

    private static class FixtureBlocks extends BlockStateInterface {
        private FixtureWorld fixture;
        private FixtureBlocks() { super(null); }
        @Override public BlockState get0(int x, int y, int z) { return fixture.getBlockState(new BlockPos(x, y, z)); }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = BuilderProcess.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }
}
