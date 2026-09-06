/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.*;
import princeps.api.*;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.*;
import princeps.api.utils.Rotation;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.precompute.*;
import princeps.utils.BlockStateInterface;
import princeps.utils.pathing.BetterWorldBorder;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;

/** Explicit world/player/context inputs; only live constructors are bypassed, never tested placement/cost methods. */
public final class PlatformTraverseFixture {
    public static final BetterBlockPos FROM = new BetterBlockPos(10,21,10), TARGET = new BetterBlockPos(11,20,10);
    private static Unsafe unsafe;
    public BuilderProcess builder;
    public BuilderProcess.BuilderCalculationContext cost;
    public World world;
    public BlocksView blocks;
    public Player player;
    public Inventory inventory;
    public IPlayerContext ctx;
    public IPrinceps bot;
    public princeps.behavior.PathingBehavior pathing;
    public HitResult hit;
    public Rotation rotation = new Rotation(0,0);
    public double reach = 4.5;
    public final Map<Long,BlockState> wanted = new HashMap<>();

    public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Item item : List.of(Items.WATER_BUCKET,Items.OAK_PLANKS,Items.DIRT,Items.STONE,Items.STICKY_PISTON,
                Items.SAND,Items.OAK_SLAB,Items.OAK_STAIRS,Items.SOUL_SAND))
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> h && !h.areComponentsBound())
                h.bindComponents(DataComponentMap.EMPTY);
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
    }
    public PlatformTraverseFixture() throws Exception {
        world=allocate(World.class);world.states=new HashMap<>();
        blocks=allocate(BlocksView.class);blocks.world=world;blocks.loaded=true;
        player=allocate(Player.class);player.pos=Vec3.atBottomCenterOf(FROM);player.grounded=true;player.crouching=true;player.shift=true;
        inventory=new Inventory(player,new EntityEquipment());set(player,"inventory",inventory);
        inventory.setItem(1,new ItemStack(Items.OAK_PLANKS,64));
        world.states.put(FROM.below().asLong(),Blocks.OAK_PLANKS.defaultBlockState());
        for (int x=10;x<=12;x++) wanted.put(BlockPos.asLong(x,20,10),Blocks.OAK_PLANKS.defaultBlockState());
        IPlayerController controller=proxy(IPlayerController.class,(name,args)->{
            if (name.equals("getBlockReachDistance")) return reach;
            throw new AssertionError("No controller action: "+name);
        });
        ctx=proxy(IPlayerContext.class,(name,args)->switch(name) {
            case "world" -> world; case "player" -> player;
            case "playerFeet" -> new BetterBlockPos(BlockPos.containing(player.pos));
            case "playerHead" -> player.pos.add(0,1.27,0);
            case "playerRotations" -> rotation; case "playerController" -> controller;
            case "objectMouseOver" -> hit;
            default -> throw new AssertionError("Unexpected context: "+name);
        });
        bot=proxy(IPrinceps.class,(name,args)->{
            if(name.equals("getPlayerContext")) return ctx;
            throw new AssertionError("No other process: "+name);
        });
        builder=allocate(BuilderProcess.class);set(builder,"ctx",ctx);
        pathing=allocate(princeps.behavior.PathingBehavior.class);
        var engine=allocate(princeps.Princeps.class);set(engine,"pathingBehavior",pathing);set(builder,"princeps",engine);
        var model=new AbstractSchematic(3,1,1) {
            @Override public BlockState desiredState(int x,int y,int z,BlockState current,List<BlockState> stock) {
                return wanted.getOrDefault(BlockPos.asLong(x+10,y+20,z+10),Blocks.AIR.defaultBlockState());
            }
        };
        List<BlockState> stock=new ArrayList<>(Collections.nCopies(9,Blocks.AIR.defaultBlockState()));
        stock.set(1,Blocks.OAK_PLANKS.defaultBlockState());
        set(builder,"schematic",model);set(builder,"origin",new Vec3i(10,20,10));set(builder,"approxPlaceable",stock);
        set(builder,"incorrectPositions",new HashSet<>(Set.of(TARGET,TARGET.east())));
        set(builder,"cellVerdicts",new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap());
        set(builder,"orientedGoalCache",new HashMap<Long,Optional<princeps.api.pathing.goals.Goal>>());
        // The new cheap movement proof must not require another standing-cell search budget. Supplying a real
        // exhausted budget also lets the offer-off counterfactual reach Unknown instead of a missing fixture field.
        set(builder,"stanceSearchNanos",Long.MAX_VALUE);
        cost=allocate(BuilderProcess.BuilderCalculationContext.class);
        set(cost,"this$0",builder);set(cost,"world",world);set(cost,"bsi",blocks);set(cost,"placeable",stock);
        set(cost,"schematic",model);set(cost,"originX",10);set(cost,"originY",20);set(cost,"originZ",10);
        set(cost,"lane",BuilderProcess.Lane.A_NO_PLACING);set(cost,"allowBreakAnyway",List.of());
        set(cost,"worldBorder",new BetterWorldBorder(new WorldBorder()));
        set(blocks,"worldBorder",new BetterWorldBorder(new WorldBorder()));
        PrecomputedData precomputed=new PrecomputedData();byte[] cache=(byte[])get(precomputed,"data");
        MovementHelper.WalkSettings settings=new MovementHelper.WalkSettings() {
            public boolean avoids(Block b){return false;} public boolean allowWalkOnMagmaBlocks(){return false;}
            public boolean allowVines(){return false;} public boolean allowWalkOnBottomSlab(){return false;}
            public boolean assumeWalkOnLava(){return false;}
        };
        for(BlockState state:Block.BLOCK_STATE_REGISTRY) {
            int value=1;Ternary through=MovementHelper.canWalkThroughBlockState(state,settings),on=MovementHelper.canWalkOnBlockState(state,settings);
            if(through==Ternary.YES)value|=16;else if(through==Ternary.MAYBE)value|=8;
            if(on==Ternary.YES)value|=64;else if(on==Ternary.MAYBE)value|=32;
            cache[Block.BLOCK_STATE_REGISTRY.getId(state)]=(byte)value;
        }
        set(cost,"precomputedData",precomputed);
    }
    /** Mirrors the immutable input captured by the real BCC constructor, without starting a game/provider. */
    public void snapshotApproach() throws Exception {set(cost,"platformApproach",get(builder,"platformTraverseApproach"));}
    public static final class World extends ClientLevel {
        public Map<Long,BlockState> states;public int writes;
        World(){super(null,null,null,null,0,0,null,false,0L,0);}
        @Override public BlockState getBlockState(BlockPos p){return states.getOrDefault(p.asLong(),Blocks.AIR.defaultBlockState());}
        @Override public boolean setBlock(BlockPos p,BlockState state,int flags,int limit){writes++;throw new AssertionError("ClientWorld mutation");}
    }
    public static final class BlocksView extends BlockStateInterface {
        public World world;public boolean loaded;
        BlocksView(){super((IPlayerContext)null);}
        @Override public BlockState get0(int x,int y,int z){return world.getBlockState(new BlockPos(x,y,z));}
        @Override public boolean worldContainsLoadedChunk(int x,int z){return loaded;}
    }
    public static final class Player extends LocalPlayer {
        public Vec3 pos;public boolean grounded,crouching,shift;
        Player(){super(null,null,null,null,null,null,false,null);}
        @Override public Vec3 position(){return pos;}
        @Override public boolean onGround(){return grounded;}
        @Override public boolean isCrouching(){return crouching;}
        @Override public boolean isShiftKeyDown(){return shift;}
    }
    public interface Call {Object invoke(String name,Object[] args)throws Throwable;}
    public static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    public static <T>T allocate(Class<T> type)throws Exception{return type.cast(unsafe.allocateInstance(type));}
    public static Field field(Class<?> type,String name)throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    public static void set(Object owner,String name,Object value)throws Exception{field(owner.getClass(),name).set(owner,value);}
    public static Object get(Object owner,String name)throws Exception{return field(owner.getClass(),name).get(owner);}
}
