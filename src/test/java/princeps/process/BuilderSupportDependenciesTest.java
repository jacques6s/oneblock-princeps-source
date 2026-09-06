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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.IPlayerController;
import princeps.utils.BlockBreakHelper;
import princeps.utils.BlockStateInterface;
import princeps.utils.InputOverrideHandler;
import princeps.utils.accessor.IPlayerControllerMP;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;
import static princeps.api.pathing.movement.ActionCosts.COST_INF;

public class BuilderSupportDependenciesTest {
    private static final BlockPos TARGET = new BlockPos(5, 20, 5);
    private static final BlockPos FLOOR = TARGET.below();
    private static Unsafe allocator;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); allocator = (Unsafe) f.get(null);
    }

    @Test public void existingDoorProtectsItsOutsideModelFoundationAtActualNavigationCost() throws Exception {
        Fixture f = fixture(door(false));
        assertEquals(COST_INF, f.cost(FLOOR), 0);
        assertEquals(BuilderSupportDependencies.Reason.REQUIRED_SUPPORT, f.policy().removal(FLOOR).reason());
        assertEquals(TARGET, f.policy().removal(FLOOR).dependent());
        assertEquals(Blocks.STONE.defaultBlockState(), f.world.getBlockState(FLOOR));
        assertEquals(0, f.world.writes);
    }

    @Test public void openDoorWithWrongToggleStillProtectsTheSameSupport() throws Exception {
        Fixture f = fixture(door(false)); f.world.states.put(TARGET.asLong(), door(true));
        assertEquals(COST_INF, f.cost(FLOOR), 0);
        assertFalse(f.owner.allowsSupportRemoval(FLOOR, f.blocks));
    }

    @Test public void sameIdentityWrongOrientationRetainsItsRealWallAnchor() throws Exception {
        BlockState wanted = Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        Fixture f = fixture(wanted);
        f.world.states.put(TARGET.asLong(), wanted.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        BlockPos actualAnchor = TARGET.south(); f.world.states.put(actualAnchor.asLong(), Blocks.STONE.defaultBlockState());
        assertEquals(COST_INF, f.cost(actualAnchor), 0);
    }

    @Test public void floorAndWallAttachedBlocksUseRealVanillaPredicates() throws Exception {
        Fixture torch = fixture(Blocks.TORCH.defaultBlockState());
        assertEquals(COST_INF, torch.cost(FLOOR), 0);
        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        Fixture wall = fixture(lever); BlockPos anchor = TARGET.west();
        wall.world.states.put(anchor.asLong(), Blocks.STONE.defaultBlockState());
        assertEquals(COST_INF, wall.cost(anchor), 0);
        Fixture sign = fixture(Blocks.PALE_OAK_WALL_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        sign.world.states.put(anchor.asLong(), Blocks.STONE.defaultBlockState());
        assertEquals(COST_INF, sign.cost(anchor), 0);
    }

    @Test public void unrelatedStoneAndWrongNeighbourIdentityRetainNormalMiningCost() throws Exception {
        Fixture f = fixture(door(false)); BlockPos unrelated = TARGET.east();
        f.world.states.put(unrelated.asLong(), Blocks.STONE.defaultBlockState());
        assertEquals(1, f.cost(unrelated), 0);
        f.world.states.put(TARGET.asLong(), Blocks.OAK_DOOR.defaultBlockState());
        assertEquals(1, f.cost(FLOOR), 0);
    }

    @Test public void alreadyUnsupportedTargetDoesNotInventANewDependency() throws Exception {
        Fixture f = fixture(door(false)); f.world.states.remove(FLOOR.asLong());
        BlockPos side = TARGET.east(); f.world.states.put(side.asLong(), Blocks.STONE.defaultBlockState());
        assertTrue(f.policy().removal(side).allowed());
    }

    @Test public void unmaskedFutureLayerProtectsItsFoundation() throws Exception {
        Fixture f = fixture(door(false));
        set(f.context, "schematic", new Model(Blocks.AIR.defaultBlockState()));
        assertEquals(COST_INF, f.cost(FLOOR), 0);
    }

    @Test public void nonLayeredBuildUsesItsFullSchematicForTheSamePlanningPolicy() throws Exception {
        Fixture f = fixture(door(false)); set(f.owner,"realSchematic",null);
        assertSame(f.model, f.owner.supportModelForDependencies());
        set(f.context,"supportDependencies", new BuilderSupportDependencies(
                f.owner.supportModelForDependencies(), TARGET, List.of(), f.blocks, f.world));
        assertEquals(COST_INF,f.cost(FLOOR),0);
    }

    @Test public void unknownLoadedAreaCannotAuthorizeMiningNearAModelTarget() throws Exception {
        Fixture f = fixture(door(false)); f.blocks.loaded = false;
        assertEquals(BuilderSupportDependencies.Reason.UNKNOWN_WORLD, f.policy().removal(FLOOR).reason());
        assertEquals(COST_INF, f.cost(FLOOR), 0);
        assertTrue("far unrelated terrain does not require a model-neighbour query",
                f.policy().removal(TARGET.offset(100, 0, 0)).allowed());
    }

    @Test public void airHypothesisIsIndependentAndDoesNotLeakIntoTheRealOrOtherViews() throws Exception {
        Fixture f = fixture(door(false));
        var original = new BuilderSupportDependencies.View(f.blocks, f.world, null, null);
        var removed = original.withBlock(FLOOR, Blocks.AIR.defaultBlockState());
        var water = original.withBlock(FLOOR, Blocks.WATER.defaultBlockState());
        assertTrue(removed.getBlockState(FLOOR).isAir());
        assertTrue(removed.getFluidState(FLOOR).isEmpty());
        assertFalse(water.getFluidState(FLOOR).isEmpty());
        assertEquals(Blocks.STONE.defaultBlockState(), original.getBlockState(FLOOR));
        assertEquals(0, f.world.writes);
    }

    @Test public void externalMissingSupportIsNamedWithoutPermissionToPlaceIt() throws Exception {
        Fixture f = fixture(door(false)); f.world.states.remove(FLOOR.asLong());
        assertEquals(FLOOR, f.policy().missingExternalSupport(TARGET, door(false), Blocks.DIRT.defaultBlockState()));
        String diagnostic = f.owner.scaffoldSupportDiagnostic(f.context, TARGET, door(false), Blocks.DIRT.defaultBlockState());
        assertTrue(diagnostic.contains("missing permanent support outside the schematic"));
        assertTrue(diagnostic.contains(FLOOR.toShortString()));
        assertTrue(f.world.getBlockState(FLOOR).isAir()); assertEquals(0, f.world.writes);
    }

    @Test public void existingSupportAndUnrelatedSideDoNotProduceMissingTerrainDiagnostic() throws Exception {
        Fixture f = fixture(door(false));
        assertNull(f.policy().missingExternalSupport(TARGET, door(false), Blocks.DIRT.defaultBlockState()));
        f.world.states.remove(FLOOR.asLong());
        assertTrue(f.policy().removal(TARGET.east()).allowed());
    }

    @Test public void pauseInactiveAndExcavationLeaveOtherMiningUnchanged() throws Exception {
        Fixture f = fixture(door(false));
        set(f.owner, "paused", true); assertTrue(f.owner.allowsSupportRemoval(FLOOR, f.blocks));
        set(f.owner, "paused", false); set(f.owner, "excavating", true);
        assertTrue(f.owner.allowsSupportRemoval(FLOOR, f.blocks));
        set(f.owner, "excavating", false); set(f.owner, "schematic", null);
        assertTrue(f.owner.allowsSupportRemoval(FLOOR, f.blocks));
    }

    @Test public void actualCrosshairGuardStopsExistingMiningBeforeBothControllerEntryPoints() throws Exception {
        Fixture f = fixture(door(false)); AtomicInteger forbiddenCalls = new AtomicInteger(), stopCalls = new AtomicInteger();
        LocalPlayer player = allocate(LocalPlayer.class);
        IPlayerController controller = (IPlayerController) Proxy.newProxyInstance(IPlayerController.class.getClassLoader(),
                new Class<?>[]{IPlayerController.class}, (p, m, a) -> {
                    if (m.getName().equals("setHittingBlock") || m.getName().equals("resetBlockRemoving")) { stopCalls.incrementAndGet(); return null; }
                    forbiddenCalls.incrementAndGet(); throw new AssertionError("unapproved controller call: " + m.getName());
                });
        IPlayerContext ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (p, m, a) -> switch (m.getName()) {
                    case "objectMouseOver" -> new BlockHitResult(Vec3.atCenterOf(FLOOR), Direction.UP, FLOOR, false);
                    case "player" -> player;
                    case "playerController" -> controller;
                    default -> throw new AssertionError(m.getName());
                });
        Constructor<BlockBreakHelper> ctor = BlockBreakHelper.class.getDeclaredConstructor(IPlayerContext.class, Predicate.class);
        ctor.setAccessible(true);
        BlockBreakHelper helper = ctor.newInstance(ctx, (Predicate<BlockPos>) p -> f.owner.allowsSupportRemoval(p, f.blocks));
        set(helper, "wasHitting", true);
        helper.requestDeterministicBreak();
        helper.tick(true);
        assertEquals(0, forbiddenCalls.get()); assertEquals(2, stopCalls.get());
        assertEquals(false, get(helper, "wasHitting")); assertEquals(0, f.world.writes);
    }

    @Test public void newDependentAfterPlanningIsRejectedByTheFreshExecutionCheck() throws Exception {
        Fixture f = fixture(door(false)); f.world.states.remove(TARGET.asLong());
        assertEquals(1, f.cost(FLOOR), 0);
        f.world.states.put(TARGET.asLong(), door(true));
        assertFalse(f.owner.allowsSupportRemoval(FLOOR, f.blocks));
    }

    @Test public void selectedWrongFacingAndHingeDoorRepairsMayRemoveTheirVanillaUpperPartner() throws Exception {
        for (BlockState wrong : List.of(door(false).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
                door(false).setValue(BlockStateProperties.DOOR_HINGE,
                        net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT))) {
            Fixture f = fixture(door(false), true);
            f.world.states.put(TARGET.asLong(), wrong);
            f.world.states.put(TARGET.above().asLong(), wrong.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
            assertFalse("navigation has no paired-repair permission", f.policy().removal(TARGET).allowed());
            assertFalse(f.owner.allowsSupportRemoval(TARGET, f.blocks));
            f.owner.selectSupportRepair(TARGET, wrong, door(false));
            assertTrue("the actually selected lower repair includes its upper", f.owner.allowsSupportRemoval(TARGET, f.blocks));
            assertFalse("the same action never authorizes its outside foundation", f.owner.allowsSupportRemoval(FLOOR, f.blocks));
            assertEquals(0, f.world.writes);
        }
    }

    @Test public void correctOrMerelyOpenDoorCannotAcquirePairedRepairPermission() throws Exception {
        Fixture f = fixture(door(false), true);
        f.owner.selectSupportRepair(TARGET, door(false), door(false));
        assertFalse(f.owner.allowsSupportRemoval(TARGET, f.blocks));
        f.world.states.put(TARGET.asLong(), door(true));
        f.owner.selectSupportRepair(TARGET, door(true), door(false));
        assertFalse("normal right-click state repair retains both halves", f.owner.allowsSupportRemoval(TARGET, f.blocks));
    }

    @Test public void selectedRepairExpiresWithTickWorldModelOrChangedPrimaryState() throws Exception {
        Fixture f = fixture(door(false), true);
        BlockState wrong = door(false).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        f.world.states.put(TARGET.asLong(), wrong);
        f.owner.selectSupportRepair(TARGET, wrong, door(false));
        set(f.owner,"buildTick",1L); assertFalse(f.owner.allowsSupportRemoval(TARGET,f.blocks));
        f.owner.selectSupportRepair(TARGET,wrong,door(false));
        f.world.states.put(TARGET.asLong(),door(false)); assertFalse(f.owner.allowsSupportRemoval(TARGET,f.blocks));
        f.world.states.put(TARGET.asLong(),wrong);
        set(f.owner,"realSchematic",new Model(door(false),true));
        assertFalse(f.owner.allowsSupportRemoval(TARGET,f.blocks));
        set(f.owner,"realSchematic",f.model);
        f.owner.selectSupportRepair(TARGET,wrong,door(false));
        World newWorld = allocate(World.class); newWorld.states = new HashMap<>(f.world.states);
        set(f.owner,"ctx",context(newWorld,allocate(LocalPlayer.class)));
        assertFalse(f.owner.allowsSupportRemoval(TARGET,f.blocks));
    }

    @Test public void confirmedMultiTickRepairContinuesButAnInterruptedOrRedirectedActionDoesNot() throws Exception {
        Fixture f = fixture(door(false),true);
        BlockState wrong=door(false).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        f.world.states.put(TARGET.asLong(),wrong);
        f.owner.selectSupportRepair(TARGET,wrong,door(false));
        assertTrue(f.owner.allowsSupportRemoval(TARGET,f.blocks));
        set(f.owner,"buildTick",1L);
        assertTrue("real same-block controller continuation",f.owner.allowsSupportRemoval(TARGET,f.blocks,true));
        set(f.owner,"buildTick",2L);
        assertTrue(f.owner.allowsSupportRemoval(TARGET,f.blocks,true));
        set(f.owner,"buildTick",3L);
        assertFalse("a fresh click is not a continuation",f.owner.allowsSupportRemoval(TARGET,f.blocks,false));
        assertFalse("a later held click cannot resurrect the old action",f.owner.allowsSupportRemoval(TARGET,f.blocks,true));
        f.owner.selectSupportRepair(TARGET,wrong,door(false));
        assertFalse(f.owner.allowsSupportRemoval(FLOOR,f.blocks,true));
        assertFalse(f.owner.allowsSupportRemoval(TARGET,f.blocks,true));
    }

    @Test public void actualBreakHelperContinuesDamageOnlyForTheSameConfirmedRepairTarget() throws Exception {
        Fixture f=fixture(door(false),true);
        BlockState wrong=door(false).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        f.world.states.put(TARGET.asLong(),wrong);
        LocalPlayer player=allocate(LocalPlayer.class);Minecraft minecraft=allocate(Minecraft.class);
        GameMode mode=allocate(GameMode.class);mode.target=TARGET;set(minecraft,"gameMode",mode);
        AtomicInteger damage=new AtomicInteger(),resets=new AtomicInteger();
        BlockPos[] hit={TARGET};
        IPlayerController controller=(IPlayerController)Proxy.newProxyInstance(IPlayerController.class.getClassLoader(),
                new Class<?>[]{IPlayerController.class},(p,m,a)->switch(m.getName()) {
                    case "hasBrokenBlock" -> false;
                    case "onPlayerDamageBlock" -> {assertEquals(TARGET,a[0]);damage.incrementAndGet();yield false;}
                    case "setHittingBlock" -> null;
                    case "resetBlockRemoving" -> {resets.incrementAndGet();yield null;}
                    default -> throw new AssertionError("unexpected controller action: "+m.getName());
                });
        IPlayerContext ctx=(IPlayerContext)Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class},(p,m,a)->switch(m.getName()) {
                    case "world" -> f.world;
                    case "player" -> player;
                    case "minecraft" -> minecraft;
                    case "playerController" -> controller;
                    case "objectMouseOver" -> new BlockHitResult(Vec3.atCenterOf(hit[0]),Direction.UP,hit[0],false);
                    default -> throw new AssertionError(m.getName());
                });
        set(f.owner,"ctx",ctx);
        Constructor<BlockBreakHelper> ctor=BlockBreakHelper.class.getDeclaredConstructor(IPlayerContext.class,Predicate.class);
        ctor.setAccessible(true);BlockBreakHelper[] holder=new BlockBreakHelper[1];
        holder[0]=ctor.newInstance(ctx,(Predicate<BlockPos>)p->f.owner.allowsSupportRemoval(p,f.blocks,holder[0].isBreakingBlock()));
        f.owner.selectSupportRepair(TARGET,wrong,door(false));
        for(long tick=0;tick<3;tick++) {
            set(f.owner,"buildTick",tick);holder[0].requestDeterministicBreak();holder[0].tick(true);
            assertTrue(holder[0].isBreakingBlock());
        }
        assertEquals(3,damage.get());
        hit[0]=FLOOR;set(f.owner,"buildTick",3L);
        holder[0].requestDeterministicBreak();holder[0].tick(true);
        assertEquals(3,damage.get());assertEquals(1,resets.get());assertFalse(holder[0].isBreakingBlock());
        hit[0]=TARGET;holder[0].requestDeterministicBreak();holder[0].tick(true);
        assertEquals("old action is not resurrected after crosshair redirection",3,damage.get());
    }

    @Test public void temporaryForeignMiningOwnerRevokesTheActionEvenWhenBuilderTicksDidNotAdvance() throws Exception {
        Fixture f=fixture(door(false),true);
        BlockState wrong=door(false).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        f.world.states.put(TARGET.asLong(),wrong);
        InputOverrideHandler input=allocate(InputOverrideHandler.class);
        java.lang.reflect.Method ownership=InputOverrideHandler.class.getDeclaredMethod("observeMiningOwner",
                princeps.api.process.IPrincepsProcess.class);ownership.setAccessible(true);
        ownership.invoke(input,f.owner);f.owner.selectSupportRepair(TARGET,wrong,door(false));
        assertTrue(f.owner.allowsSupportRemoval(TARGET,f.blocks));
        Object temporary=Proxy.newProxyInstance(princeps.api.process.IPrincepsProcess.class.getClassLoader(),
                new Class<?>[]{princeps.api.process.IPrincepsProcess.class},(p,m,a)->{
                    throw new AssertionError("no foreign process actions or builder selection");
                });
        ownership.invoke(input,temporary);assertNull(get(input,"supportMiningOwner"));
        ownership.invoke(input,f.owner);
        set(f.owner,"buildTick",1L);
        assertFalse("foreign controller state cannot renew the old action",f.owner.allowsSupportRemoval(TARGET,f.blocks,true));
    }

    @Test public void unmodelledOrDifferentWantedUpperIsNotAnAuthorizedRepairPartner() throws Exception {
        Fixture f = fixture(door(false),true);
        BlockState wrong = door(false).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        f.world.states.put(TARGET.asLong(),wrong);
        f.model.upperWanted = door(false).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER);
        f.owner.selectSupportRepair(TARGET,wrong,door(false));
        assertFalse(f.owner.allowsSupportRemoval(TARGET,f.blocks));
    }

    @Test public void allowedMiningReleasesTheRefusalDiagnosticLatch() throws Exception {
        Fixture f = fixture(door(false));
        assertFalse(f.owner.allowsSupportRemoval(FLOOR,f.blocks));
        assertEquals(TARGET,get(f.owner,"lastSupportDependent"));
        f.world.states.remove(TARGET.asLong()); assertTrue(f.owner.allowsSupportRemoval(FLOOR,f.blocks));
        assertNull(get(f.owner,"lastSupportRefusal")); assertNull(get(f.owner,"lastSupportDependent"));
        f.world.states.put(TARGET.asLong(),door(false)); assertFalse(f.owner.allowsSupportRemoval(FLOOR,f.blocks));
        assertEquals(FLOOR,get(f.owner,"lastSupportRefusal"));
    }

    private static BlockState door(boolean open) {
        return Blocks.PALE_OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.OPEN, open);
    }

    private static Fixture fixture(BlockState wanted) throws Exception {
        return fixture(wanted,false);
    }

    private static Fixture fixture(BlockState wanted,boolean paired) throws Exception {
        World world = allocate(World.class); world.states = new HashMap<>();
        world.states.put(TARGET.asLong(), wanted); world.states.put(FLOOR.asLong(), Blocks.STONE.defaultBlockState());
        BlocksView blocks = allocate(BlocksView.class); blocks.world = world; blocks.loaded = true;
        Model model = new Model(wanted,paired); BuilderProcess owner = allocate(BuilderProcess.class);
        if(paired) world.states.put(TARGET.above().asLong(),model.upperWanted);
        IPlayerContext ctx = context(world,allocate(LocalPlayer.class));
        set(owner,"ctx",ctx); set(owner,"origin",TARGET); set(owner,"schematic",model); set(owner,"realSchematic",model);
        set(owner,"approxPlaceable",Collections.emptyList());
        var cost = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(cost,"this$0",owner); set(cost,"originX",TARGET.getX()); set(cost,"originY",TARGET.getY()); set(cost,"originZ",TARGET.getZ());
        set(cost,"schematic",model);
        set(cost,"supportDependencies",new BuilderSupportDependencies(model,TARGET,List.of(),blocks,world));
        set(cost,"placeable",Collections.emptyList()); set(cost,"allowBreak",true); set(cost,"allowBreakAnyway",List.of());
        set(cost,"bsi",blocks); set(cost,"lane",BuilderProcess.Lane.A_NO_PLACING);
        return new Fixture(owner,cost,world,blocks,model);
    }

    private static IPlayerContext context(World world,LocalPlayer player) {
        return (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (p,m,a) -> switch(m.getName()) {
                    case "world" -> world;
                    case "player" -> player;
                    default -> throw new AssertionError(m.getName());
                });
    }

    private record Fixture(BuilderProcess owner, BuilderProcess.BuilderCalculationContext context,
                           World world, BlocksView blocks, Model model) {
        BuilderSupportDependencies policy() { return new BuilderSupportDependencies(model,TARGET,List.of(),blocks,world); }
        double cost(BlockPos pos) { return context.breakCostMultiplierAt(pos.getX(),pos.getY(),pos.getZ(),world.getBlockState(pos)); }
    }
    private static final class Model extends AbstractSchematic {
        private final BlockState wanted;
        private BlockState upperWanted;
        Model(BlockState wanted) { this(wanted,false); }
        Model(BlockState wanted,boolean paired) {
            super(1,paired?2:1,1); this.wanted=wanted;
            if(paired) upperWanted=wanted.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER);
        }
        @Override public BlockState desiredState(int x,int y,int z,BlockState now,List<BlockState> stock) { return y==0?wanted:upperWanted; }
    }
    private static class World extends ClientLevel {
        Map<Long,BlockState> states; int writes;
        World() { super(null,null,null,null,0,0,null,false,0L,0); }
        @Override public BlockState getBlockState(BlockPos p) { return states.getOrDefault(p.asLong(),Blocks.AIR.defaultBlockState()); }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public boolean setBlock(BlockPos p,BlockState state,int flags,int limit) { writes++; throw new AssertionError("ClientWorld mutation"); }
    }
    private static class BlocksView extends BlockStateInterface {
        World world; boolean loaded;
        BlocksView() { super((IPlayerContext)null); }
        @Override public BlockState get0(int x,int y,int z) { return world.getBlockState(new BlockPos(x,y,z)); }
        @Override public boolean worldContainsLoadedChunk(int x,int z) { return loaded; }
    }
    private static class GameMode extends MultiPlayerGameMode implements IPlayerControllerMP {
        BlockPos target;
        GameMode(){super(null,null);}
        @Override public BlockPos getCurrentBlock(){return target;}
        @Override public boolean isHittingBlock(){return true;}
        @Override public float getDestroyProgress(){return 0.25F;}
        @Override public void setIsHittingBlock(boolean b){throw new AssertionError("accessor write");}
        @Override public void callSyncCurrentPlayItem(){throw new AssertionError("accessor write");}
        @Override public void setDestroyDelay(int delay){throw new AssertionError("accessor write");}
    }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(allocator.allocateInstance(type)); }
    private static Field field(Class<?> type,String name) throws Exception {
        for(Class<?> t=type;t!=null;t=t.getSuperclass()) try { Field f=t.getDeclaredField(name);f.setAccessible(true);return f; } catch(NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(name);
    }
    private static void set(Object owner,String name,Object value) throws Exception { field(owner.getClass(),name).set(owner,value); }
    private static Object get(Object owner,String name) throws Exception { return field(owner.getClass(),name).get(owner); }
}
