/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
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
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.process.IBuilderProcess.Ending;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.behavior.InventoryBehavior;
import princeps.utils.BlockStateInterface;
import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.junit.Assert.*;

/** Actual scaffold selection and preparation methods, with controlled inventory callbacks; no placement oracle mock. */
public class BuilderScaffoldMaterialPreparationTest {
    private static final BetterBlockPos ORIGIN=new BetterBlockPos(0,20,0), TARGET=new BetterBlockPos(1,21,1);
    private static Unsafe allocator;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for(Item item:List.of(Items.WATER_BUCKET,Items.GLASS,Items.DIRT))
            if(BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder && !holder.areComponentsBound())
                holder.bindComponents(DataComponentMap.EMPTY);
        Field f=Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); allocator=(Unsafe)f.get(null);
    }

    @Test public void notHotbarNeverOpensAHypotheticalHelperAndRequestsNormalInventoryPreparation() throws Exception {
        Fixture f=fixture(); AtomicInteger swaps=new AtomicInteger();
        assertTrue(f.open(slot->{assertEquals(9,slot);swaps.incrementAndGet();return false;}));
        assertEquals(1,swaps.get()); assertTrue("same demanded cell",TARGET.equals(get(f.owner,"scaffoldMaterialTarget")));
        assertNoScaffold(f); assertEquals(1,f.parked().size());
        assertTrue("same tick reentry does not multiply requests",f.open(slot->{swaps.incrementAndGet();return false;}));
        assertEquals(1,swaps.get()); assertEquals(0L,get(f.owner,"scaffoldMaterialWaitStarted"));
        set(f.owner,"incorrectPositions",new HashSet<>(Set.of(TARGET)));
        var cancel=new princeps.api.process.PathingCommand(null,princeps.api.process.PathingCommandType.CANCEL_AND_SET_GOAL);
        Method wrapper=BuilderProcess.class.getDeclaredMethod("holdStillWithoutTearingUpTheRoute",princeps.api.process.PathingCommand.class);
        wrapper.setAccessible(true);
        assertSame("material preparation must not preserve a previous route",cancel,wrapper.invoke(f.owner,cancel));
    }

    @Test public void realInventoryLockRefusalKeepsOneDemandAndStopsAt120WithoutRenewingTheDeadline() throws Exception {
        Fixture f=fixture(); InventoryBehavior inventory=allocate(InventoryBehavior.class);
        set(inventory,"ctx",f.ctx); set(inventory,"hotbarFetchedAt",new long[9]);
        set(inventory,"builderLockedHotbarSlot",1);
        AtomicInteger calls=new AtomicInteger();
        IntPredicate request=slot->{calls.incrementAndGet();return inventory.attemptToPutOnHotbar(slot,candidate->false);};
        for(long tick=0;tick<120;tick++) {
            set(f.owner,"buildTick",tick); assertTrue(f.open(request));
            assertTrue("same demanded cell",TARGET.equals(get(f.owner,"scaffoldMaterialTarget")));
            assertEquals(0L,get(f.owner,"scaffoldMaterialWaitStarted"));
            assertEquals(Ending.RUNNING,get(f.owner,"abortPending")); assertNoScaffold(f);
        }
        assertTrue(String.valueOf(get(inventory,"lastSwapRefusal")).contains("owned by Builder V3"));
        set(f.owner,"buildTick",120L); assertTrue(f.open(request));
        assertEquals(120,calls.get()); assertEquals(Ending.MATERIALS_MISSING,get(f.owner,"abortPending"));
        assertTrue(String.valueOf(get(f.owner,"abortPendingHeadline")).contains("hotbar"));
        assertNoScaffold(f);
    }

    @Test public void availableMaterialIsReprobedAndANegativeGeometryVerdictStillCannotOpenAHelper() throws Exception {
        Fixture f=fixture(); assertTrue(f.open(slot->false));
        // These are existing negative oracle entries, not invented positive evidence. The old NOT_HOTBAR
        // fallback would already have opened a helper before this actual-material re-evaluation.
        Map<Long,Boolean> cache=(Map<Long,Boolean>)get(f.owner,"scaffoldProbeCache");
        for(Direction d:Direction.values()) cache.put(TARGET.relative(d).asLong(),false);
        f.inventory.getNonEquipmentItems().set(1,new ItemStack(Items.GLASS));
        set(f.owner,"buildTick",1L);
        assertFalse(f.open(slot->{throw new AssertionError("material already present");}));
        assertTrue("demand cleared",get(f.owner,"scaffoldMaterialTarget")==null); assertNoScaffold(f);
    }

    @Test public void existingPositiveOracleEntryStillOpensExactlyItsOrdinaryHelper() throws Exception {
        Fixture f=fixture();f.inventory.getNonEquipmentItems().set(1,new ItemStack(Items.GLASS));
        Map<Long,Boolean> cache=(Map<Long,Boolean>)get(f.owner,"scaffoldProbeCache");
        for(Direction d:Direction.values())cache.put(TARGET.relative(d).asLong(),false);
        BlockPos approved=TARGET.west();cache.put(approved.asLong(),true);
        assertTrue(f.open(slot->{throw new AssertionError("material already present");}));
        assertTrue(approved.equals(get(f.owner,"scaffoldCell")));assertTrue(TARGET.equals(get(f.owner,"scaffoldServes")));
        assertEquals(Blocks.DIRT.defaultBlockState(),get(f.owner,"scaffoldWanted"));
        assertEquals(1,get(f.owner,"scaffoldsThisLayer"));assertEquals(0,f.world.writes);
    }

    @Test public void missingExternalDoorFoundationStopsBeforeUnverifiedMaterialOrHelperActions() throws Exception {
        Fixture f=fixture();
        var model=new AbstractSchematic(1,2,1) {
            @Override public BlockState desiredState(int x,int y,int z,BlockState now,List<BlockState> stock) {
                return Blocks.PALE_OAK_DOOR.defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                        y==0?net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                                :net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
            }
        };
        set(f.owner,"origin",TARGET);set(f.owner,"schematic",model);set(f.owner,"realSchematic",model);
        set(f.bcc,"originX",TARGET.x);set(f.bcc,"originY",TARGET.y);set(f.bcc,"originZ",TARGET.z);set(f.bcc,"schematic",model);
        assertTrue(f.open(slot->{throw new AssertionError("material is not the missing permanent foundation");}));
        assertEquals(Ending.LAYER_UNBUILDABLE,get(f.owner,"abortPending"));
        assertTrue(String.valueOf(get(f.owner,"abortPendingHeadline")).contains(TARGET.below().toShortString()));
        assertNoScaffold(f);
    }

    @Test public void missingInventoryAndDisabledInventoryHaveBoundedExplicitFailures() throws Exception {
        Fixture f=fixture(); f.stock.set(9,Blocks.AIR.defaultBlockState());
        assertTrue(f.open(slot->{throw new AssertionError("no matching inventory slot");}));
        set(f.owner,"buildTick",120L);
        assertTrue(f.open(slot->{throw new AssertionError("deadline expired");}));
        assertEquals(Ending.MATERIALS_MISSING,get(f.owner,"abortPending")); assertNoScaffold(f);
        Fixture disabled=fixture();
        assertTrue(disabled.owner.prepareScaffoldMaterial(TARGET,Blocks.GLASS.defaultBlockState(),false,
                slot->{throw new AssertionError("inventory disabled");}));
        assertEquals(Ending.MATERIALS_MISSING,get(disabled.owner,"abortPending")); assertNoScaffold(disabled);
    }

    @Test public void pausedBuildTicksDoNotSpendOrRenewTheRemainingMaterialBudget() throws Exception {
        Fixture f=fixture();assertTrue(f.open(slot->false));
        set(f.owner,"buildTick",119L);assertTrue(f.open(slot->false));
        f.owner.pause();set(f.owner,"buildTick",1000L);f.owner.pause();
        set(f.owner,"buildTick",3119L);f.owner.resume();f.owner.resume();
        assertEquals(3000L,get(f.owner,"scaffoldMaterialWaitStarted"));
        assertNull(get(f.owner,"scaffoldMaterialPausedAt"));
        assertTrue(f.open(slot->false));assertEquals(Ending.RUNNING,get(f.owner,"abortPending"));
        set(f.owner,"buildTick",3120L);assertTrue(f.open(slot->{throw new AssertionError("active budget exhausted");}));
        assertEquals(Ending.MATERIALS_MISSING,get(f.owner,"abortPending"));assertNoScaffold(f);
    }

    @Test public void pauseWithoutTicksAndRepeatedResumeDoNotGrantANewMaterialBudget() throws Exception {
        Fixture f=fixture();assertTrue(f.open(slot->false));
        set(f.owner,"buildTick",119L);f.owner.pause();f.owner.pause();f.owner.resume();f.owner.resume();
        assertEquals(0L,get(f.owner,"scaffoldMaterialWaitStarted"));
        set(f.owner,"buildTick",120L);assertTrue(f.open(slot->{throw new AssertionError("not renewed");}));
        assertEquals(Ending.MATERIALS_MISSING,get(f.owner,"abortPending"));
    }

    @Test public void unknownViewIsNotDescribedAsAProvenMissingPermanentFoundation() throws Exception {
        Fixture f=fixture(); f.blocks.loaded=false;
        String diagnostic=f.owner.scaffoldSupportDiagnostic(f.bcc,TARGET,Blocks.TORCH.defaultBlockState(),Blocks.DIRT.defaultBlockState());
        assertTrue(diagnostic.contains("unknown")); assertFalse(diagnostic.contains("missing permanent"));
        assertNoScaffold(f);
    }

    private static void assertNoScaffold(Fixture f) throws Exception {
        // BetterBlockPos.toString consults global coordinate-censor settings. Failed headless assertions must
        // report the invariant itself, not initialize a game API solely to format an unexpected coordinate.
        assertTrue("no unverified scaffold",get(f.owner,"scaffoldCell")==null); assertNull(get(f.owner,"scaffoldWanted"));
        assertEquals(0,get(f.owner,"scaffoldsThisLayer"));
        assertTrue(((Map<?,?>)get(f.owner,"temporarySupportTargets")).isEmpty()); assertEquals(0,f.world.writes);
    }

    private static Fixture fixture() throws Exception {
        BuilderProcess owner=allocate(BuilderProcess.class);
        for(Field field:BuilderProcess.class.getDeclaredFields())
            if(!Modifier.isStatic(field.getModifiers()) && !field.getType().isInterface()
                    && (Map.class.isAssignableFrom(field.getType()) || Set.class.isAssignableFrom(field.getType()))) {
                field.setAccessible(true);field.set(owner,field.getType().getConstructor().newInstance());
            }
        World world=allocate(World.class);world.states=new HashMap<>();
        world.states.put(new BlockPos(0,20,1).asLong(),Blocks.STONE.defaultBlockState());
        View blocks=allocate(View.class);blocks.world=world;blocks.loaded=true;
        LocalPlayer player=allocate(LocalPlayer.class);Inventory inventory=new Inventory(player,new EntityEquipment());
        set(player,"inventory",inventory); inventory.getNonEquipmentItems().set(8,new ItemStack(Items.DIRT));
        inventory.getNonEquipmentItems().set(9,new ItemStack(Items.GLASS));
        IPlayerContext ctx=(IPlayerContext)Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),new Class<?>[]{IPlayerContext.class},
                (p,m,a)->switch(m.getName()) {
                    case "world" -> world;
                    case "player" -> player;
                    case "playerFeet" -> new BetterBlockPos(0,21,0);
                    default -> throw new AssertionError("unexpected interaction: "+m.getName());
                });
        var model=new AbstractSchematic(3,3,3) {
            @Override public BlockState desiredState(int x,int y,int z,BlockState now,List<BlockState> stock) {
                return x==1 && y==1 && z==1 ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
        };
        ArrayList<BlockState> stock=new ArrayList<>(Collections.nCopies(36,Blocks.AIR.defaultBlockState()));
        stock.set(8,Blocks.DIRT.defaultBlockState());stock.set(9,Blocks.GLASS.defaultBlockState());
        set(owner,"ctx",ctx);set(owner,"origin",ORIGIN);set(owner,"schematic",model);set(owner,"realSchematic",model);
        set(owner,"approxPlaceable",stock);set(owner,"ending",Ending.RUNNING);set(owner,"abortPending",Ending.RUNNING);
        set(owner,"progressWatch",new princeps.process.builder.BuilderProgressWatch(5_000_000_000L,60_000_000_000L,2));
        Class<?> reason=Class.forName("princeps.process.BuilderProcess$ParkReason");
        Class<?> parked=Class.forName("princeps.process.BuilderProcess$ParkedCell");
        Constructor<?> ctor=parked.getDeclaredConstructor(BetterBlockPos.class,reason,long.class,long.class);ctor.setAccessible(true);
        ((Map<Long,Object>)get(owner,"parkedCells")).put(TARGET.asLong(),ctor.newInstance(TARGET,reason.getEnumConstants()[0],0L,0L));
        var bcc=allocate(BuilderProcess.BuilderCalculationContext.class);set(bcc,"this$0",owner);
        set(bcc,"originX",ORIGIN.x);set(bcc,"originY",ORIGIN.y);set(bcc,"originZ",ORIGIN.z);set(bcc,"schematic",model);
        set(bcc,"placeable",stock);set(bcc,"bsi",blocks);set(bcc,"hasThrowaway",true);
        return new Fixture(owner,bcc,world,blocks,ctx,inventory,stock);
    }
    private record Fixture(BuilderProcess owner,BuilderProcess.BuilderCalculationContext bcc,World world,View blocks,
                           IPlayerContext ctx,Inventory inventory,ArrayList<BlockState> stock) {
        boolean open(IntPredicate request) {
            return owner.openScaffoldPhase(bcc,Blocks.DIRT.defaultBlockState(),
                    (target,wanted)->owner.prepareScaffoldMaterial(target,wanted,true,request),message->{});
        }
        Map<?,?> parked() throws Exception {return (Map<?,?>)get(owner,"parkedCells");}
    }
    private static class World extends ClientLevel {
        Map<Long,BlockState> states;int writes;
        World(){super(null,null,null,null,0,0,null,false,0L,0);}
        @Override public BlockState getBlockState(BlockPos p){return states.getOrDefault(p.asLong(),Blocks.AIR.defaultBlockState());}
        @Override public int getMinY(){return -64;}
        @Override public int getHeight(){return 384;}
        @Override public boolean setBlock(BlockPos p,BlockState s,int flags,int limit){writes++;throw new AssertionError("world write");}
    }
    private static class View extends BlockStateInterface {
        World world;boolean loaded;
        View(){super((IPlayerContext)null);}
        @Override public BlockState get0(int x,int y,int z){return world.getBlockState(new BlockPos(x,y,z));}
        @Override public boolean worldContainsLoadedChunk(int x,int z){return loaded;}
    }
    private static <T>T allocate(Class<T> type)throws Exception{return type.cast(allocator.allocateInstance(type));}
    private static Field field(Class<?> type,String name)throws Exception{
        for(Class<?> t=type;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static void set(Object owner,String name,Object value)throws Exception{field(owner.getClass(),name).set(owner,value);}
    private static Object get(Object owner,String name)throws Exception{return field(owner.getClass(),name).get(owner);}
}
