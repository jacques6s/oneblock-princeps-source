/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.schematic.ISchematic;
import princeps.api.schematic.MaskSchematic;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.IPlayerController;
import princeps.utils.BlockBreakHelper;
import princeps.utils.BlockStateInterface;
import princeps.utils.ToolSet;
import princeps.utils.accessor.IPlayerControllerMP;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;
import static princeps.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Real BCC and BBH boundaries with an explicit active-layer snapshot. No implementation
 * method is replaced. The full model retains the block hidden by the active mask.
 * The inventory contains one unenchanted pick, no replacement material or promised drops.
 * This is a regression contract, not a complete A* route, server-drop or physics simulation.
 */
public class BuilderModelRestorationTest {
    static final BlockPos ORIGIN = new BlockPos(10, 12, 20);
    static final BlockPos TARGET = ORIGIN.offset(1, 1, 1);
    private static Unsafe unsafe;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Item item : List.of(Items.WATER_BUCKET, Items.DIAMOND_PICKAXE)) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        }
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        unsafe = (Unsafe) field.get(null);
    }

    @Test public void maskedCorrectMaterialWithoutReplacementCannotBeANavigationBreak() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        assertFalse(f.active.inSchematic(1, 1, 1, f.world.getBlockState(TARGET)));
        assertTrue(f.full.inSchematic(1, 1, 1, f.world.getBlockState(TARGET)));
        assertEquals(f.world.getBlockState(TARGET), f.full.desiredState(1, 1, 1, null, List.of()));
        assertEquals("the full model must retain restoration requirements outside the working layer",
                COST_INF, f.cost(TARGET), 0);
    }

    @Test public void anotherMaterialHasTheSameCostContractWithoutAGlassSpecialCase() throws Exception {
        Fixture f = fixture(Blocks.BOOKSHELF.defaultBlockState(), true);
        assertEquals(COST_INF, f.cost(TARGET), 0);
    }

    @Test public void maskedCorrectHopperHasTheSamePlanningAndDamageContract() throws Exception {
        Fixture f = fixture(Blocks.HOPPER.defaultBlockState(), true);
        assertEquals(COST_INF, f.cost(TARGET), 0);
        f.tickMining();
        assertEquals(0, f.damage.get());
    }

    @Test public void actualCrosshairAlsoProtectsTheMaskedHopper() throws Exception {
        Fixture f = fixture(Blocks.HOPPER.defaultBlockState(), true);
        f.tickMining();
        assertEquals(0, f.damage.get());
    }

    @Test public void actualCrosshairDamageCannotConsumeCorrectGlassWithoutRestoration() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        f.tickMining();
        assertEquals("actual BBH must stop before the real controller damage entry", 0, f.damage.get());
        assertEquals(0, f.world.writes);
    }

    @Test public void actualCrosshairDamageAppliesTheSameRuleToOtherCorrectMaterial() throws Exception {
        Fixture f = fixture(Blocks.BOOKSHELF.defaultBlockState(), true);
        f.tickMining();
        assertEquals(0, f.damage.get());
    }

    @Test public void unmaskedCorrectMaterialHasNoExecutorLoophole() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), false);
        f.tickMining();
        assertEquals(0, f.damage.get());
    }

    @Test public void newlyCorrectTargetAfterPlanningIsRecheckedBeforeDamage() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        f.world.states.put(TARGET.asLong(), Blocks.COBBLESTONE.defaultBlockState());
        assertEquals(1, f.cost(TARGET), 0);
        f.world.states.put(TARGET.asLong(), Blocks.BLACK_STAINED_GLASS.defaultBlockState());
        f.tickMining();
        assertEquals(0, f.damage.get());
    }

    @Test public void previouslyStartedMiningIsResetWhenTheCurrentTargetMustBePreserved() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        set(f.mining, "wasHitting", true);
        f.tickMining();
        assertEquals(0, f.damage.get());
        assertEquals(1, f.resets.get());
        assertEquals(false, get(f.mining, "wasHitting"));
    }

    @Test public void wrongIdentityStillAllowsItsOrdinaryRemoval() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        f.world.states.put(TARGET.asLong(), Blocks.COBBLESTONE.defaultBlockState());
        assertEquals(1, f.cost(TARGET), 0);
        f.tickMining();
        assertEquals(1, f.damage.get());
    }

    @Test public void modelAirCleanupKeepsItsOrdinaryMiningPath() throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), false);
        f.world.states.put(TARGET.asLong(), Blocks.DIRT.defaultBlockState());
        assertEquals(1, f.cost(TARGET), 0);
        f.tickMining();
        assertEquals(1, f.damage.get());
    }

    @Test public void unrelatedOutsideTerrainRemainsMineable() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        f.hit = TARGET.offset(20, 0, 0);
        f.world.states.put(f.hit.asLong(), Blocks.STONE.defaultBlockState());
        assertEquals(1, f.cost(f.hit), 0);
        f.tickMining();
        assertEquals(1, f.damage.get());
    }

    @Test public void excavationKeepsItsExistingExecutorOwnershipBoundary() throws Exception {
        Fixture f = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        set(f.owner, "excavating", true);
        f.tickMining();
        assertEquals(1, f.damage.get());
    }

    static Fixture fixture(BlockState wanted, boolean masked) throws Exception {
        Fixture f = new Fixture();
        f.world = allocate(World.class); f.world.states = new HashMap<>();
        f.world.states.put(TARGET.asLong(), wanted);
        f.blocks = allocate(BlocksView.class); f.blocks.world = f.world;
        f.full = new AbstractSchematic(3, 3, 3) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> stock) {
                return x == 1 && y == 1 && z == 1 ? wanted : Blocks.AIR.defaultBlockState();
            }
        };
        // Input to the real BCC: cells above the active band are masked, not removed from the full model.
        f.active = masked ? new MaskSchematic(f.full) {
            @Override protected boolean partOfMask(int x, int y, int z, BlockState current) { return y == 0; }
        } : f.full;
        f.owner = allocate(BuilderProcess.class);
        set(f.owner, "scaffoldCleanupTargets", new java.util.HashSet<>());
        set(f.owner, "excavationFluidPlugs", new ExcavationFluidPlugs());
        set(f.owner, "excavationRepairAim", new ExcavationRepairAim());
        LocalPlayer player = allocate(LocalPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        set(player, "inventory", inventory);
        inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        assertEquals(1, inventory.getItem(0).getCount());
        assertFalse(allocate(ToolSet.class).hasSilkTouch(inventory.getItem(0)));
        for (int i = 1; i < 36; i++) assertTrue(inventory.getItem(i).isEmpty());
        Minecraft minecraft = allocate(Minecraft.class);
        GameMode gameMode = allocate(GameMode.class); gameMode.target = TARGET; gameMode.damage = 0.25F;
        set(minecraft, "gameMode", gameMode);
        IPlayerController controller = (IPlayerController) Proxy.newProxyInstance(IPlayerController.class.getClassLoader(),
                new Class<?>[]{IPlayerController.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasBrokenBlock" -> false;
                    case "onPlayerDamageBlock" -> { assertEquals(f.hit, args[0]); f.damage.incrementAndGet(); yield false; }
                    case "setHittingBlock" -> null;
                    case "resetBlockRemoving" -> { f.resets.incrementAndGet(); yield null; }
                    default -> throw new AssertionError("unexpected controller operation: " + method.getName());
                });
        IPlayerContext ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "world" -> f.world;
                    case "player" -> player;
                    case "minecraft" -> minecraft;
                    case "playerController" -> controller;
                    case "objectMouseOver" -> new BlockHitResult(Vec3.atCenterOf(f.hit), Direction.UP, f.hit, false);
                    default -> throw new AssertionError("unexpected context read: " + method.getName());
                });
        set(f.owner, "ctx", ctx); set(f.owner, "origin", ORIGIN);
        f.ctx = ctx; f.player = player;
        set(f.owner, "schematic", f.active); set(f.owner, "realSchematic", f.full);
        set(f.owner, "approxPlaceable", List.of());
        f.cost = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(f.cost, "fluidPlugSnapshot", new ExcavationFluidPlugs());
        set(f.cost, "this$0", f.owner); set(f.cost, "originX", ORIGIN.getX());
        set(f.cost, "originY", ORIGIN.getY()); set(f.cost, "originZ", ORIGIN.getZ());
        set(f.cost, "schematic", f.active); set(f.cost, "placeable", List.of());
        set(f.cost, "allowBreak", true); set(f.cost, "allowBreakAnyway", List.of());
        set(f.cost, "bsi", f.blocks); set(f.cost, "lane", BuilderProcess.Lane.A_NO_PLACING);
        set(f.cost, "supportDependencies", new BuilderSupportDependencies(f.full, ORIGIN, List.of(), f.blocks, f.world));
        Constructor<BlockBreakHelper> constructor = BlockBreakHelper.class.getDeclaredConstructor(IPlayerContext.class, Predicate.class);
        constructor.setAccessible(true);
        f.mining = constructor.newInstance(ctx, (Predicate<BlockPos>) pos -> f.owner.allowsSupportRemoval(pos, f.blocks));
        return f;
    }

    static final class Fixture {
        BuilderProcess owner; BuilderProcess.BuilderCalculationContext cost; World world; BlocksView blocks;
        ISchematic full, active; BlockBreakHelper mining; BlockPos hit = TARGET;
        IPlayerContext ctx; LocalPlayer player;
        final AtomicInteger damage = new AtomicInteger(), resets = new AtomicInteger();
        double cost(BlockPos pos) { return cost.breakCostMultiplierAt(pos.getX(), pos.getY(), pos.getZ(), world.getBlockState(pos)); }
        void tickMining() { mining.requestDeterministicBreak(); mining.tick(true); }
    }
    static class World extends ClientLevel {
        Map<Long,BlockState> states; int writes; boolean unloaded;
        World() { super(null,null,null,null,0,0,null,false,0L,0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState()); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int limit) { writes++; throw new AssertionError("ClientWorld mutation"); }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public boolean hasChunk(int x, int z) { return !unloaded; }
    }
    static class BlocksView extends BlockStateInterface {
        World world;
        BlocksView() { super((IPlayerContext)null); }
        @Override public BlockState get0(int x, int y, int z) { return world.getBlockState(new BlockPos(x,y,z)); }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return true; }
    }
    private static class GameMode extends MultiPlayerGameMode implements IPlayerControllerMP {
        BlockPos target;
        float damage;
        GameMode() { super(null,null); }
        @Override public BlockPos getCurrentBlock() { return target; }
        @Override public boolean isHittingBlock() { return true; }
        @Override public float getDestroyProgress() { return damage; }
        @Override public void setIsHittingBlock(boolean value) { throw new AssertionError("accessor mutation"); }
        @Override public void callSyncCurrentPlayItem() { throw new AssertionError("accessor mutation"); }
        @Override public void setDestroyDelay(int delay) { throw new AssertionError("accessor mutation"); }
    }
    static <T> T allocate(Class<T> type) throws Exception { return type.cast(unsafe.allocateInstance(type)); }
    static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> next = type; next != null; next = next.getSuperclass()) {
            try { Field field = next.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static void set(Object owner, String name, Object value) throws Exception { field(owner.getClass(),name).set(owner,value); }
    static Object get(Object owner, String name) throws Exception { return field(owner.getClass(),name).get(owner); }
}
