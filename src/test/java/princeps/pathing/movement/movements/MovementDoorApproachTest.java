/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.pathing.movement.movements;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.IPrinceps;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.IPlayerController;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;
import princeps.behavior.PathingBehavior;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.movement.MovementState;
import princeps.pathing.precompute.PrecomputedData;
import princeps.pathing.precompute.Ternary;
import princeps.process.builder.BuildTrace;
import princeps.utils.BlockStateInterface;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static princeps.api.pathing.movement.ActionCosts.*;

public class MovementDoorApproachTest {
    private static final BetterBlockPos FROM = new BetterBlockPos(10, 21, 10);
    private static final BetterBlockPos DEST = new BetterBlockPos(11, 20, 10);
    private static Unsafe unsafe;
    private Object oldIntentSource, oldIntentDetail;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); unsafe = (Unsafe) f.get(null);
    }
    @Before public void saveTraceIntent() throws Exception {
        oldIntentSource = field(BuildTrace.class,"pendingIntentSource").get(null);
        oldIntentDetail = field(BuildTrace.class,"pendingIntentDetail").get(null);
    }
    @After public void restoreTraceIntent() throws Exception {
        field(BuildTrace.class,"pendingIntentSource").set(null,oldIntentSource);
        field(BuildTrace.class,"pendingIntentDetail").set(null,oldIntentDetail);
    }

    @Test public void actualDescendRunningActorHonoursItsFiniteDoorCost() throws Exception {
        Fixture f = fixture();
        assertEquals(WALK_OFF_BLOCK_COST + Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST),
                f.descend.calculateCost(f.cost), 0);
        MovementState state = f.descend.updateState(running());
        assertTrue("finite wooden-door descend must actually request normal use", input(state,Input.CLICK_RIGHT));
        assertFalse(input(state,Input.MOVE_FORWARD)); assertFalse(input(state,Input.CLICK_LEFT));
        assertEquals(MovementStatus.RUNNING,state.getStatus()); assertEquals(0,f.world.writes);
    }

    @Test public void onlyDoorwayInNarrowDescendingCorridorRemainsUsable() throws Exception {
        Fixture f = fixture();
        // Three-high permanent side walls: the direct step through the door is the only outgoing descent.
        for (Direction side : List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST))
            for (int dy=-1;dy<=1;dy++) f.world.states.put(FROM.relative(side).offset(0,dy,0).asLong(),Blocks.STONE.defaultBlockState());
        assertTrue(f.descend.calculateCost(f.cost) < COST_INF);
        for (Direction side : List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST)) {
            var other = new ReadyDescend(f.bot,FROM,new BetterBlockPos(FROM.relative(side).below()));
            assertEquals("no mining and no side detour",COST_INF,other.calculateCost(f.cost),0);
        }
        assertTrue(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
    }

    @Test public void actualTraverseUsesTheSameRayGatedActor() throws Exception {
        Fixture f = fixture();
        ReadyTraverse flat = new ReadyTraverse(f.bot,FROM.below(),DEST);
        f.hit = BlockHitResult.miss(new Vec3(12,21,10),Direction.WEST,new BlockPos(12,21,10));
        assertFalse(input(flat.updateState(running()),Input.CLICK_RIGHT));
        f.hit = hit(DEST);
        assertTrue(input(flat.updateState(running()),Input.CLICK_RIGHT));
        assertEquals(0,f.world.writes);
    }

    @Test public void aimingAtMissOrForeignBlockDoesNotUseHeldBuildingItem() throws Exception {
        Fixture f = fixture();
        for (HitResult ray : new HitResult[]{null,BlockHitResult.miss(Vec3.ZERO,Direction.UP,BlockPos.ZERO),hit(DEST.north())}) {
            f.hit=ray; MovementState state=f.descend.updateState(running());
            assertFalse(input(state,Input.CLICK_RIGHT)); assertFalse(input(state,Input.MOVE_FORWARD));
            assertTrue("keep aiming at the door",state.getTarget().getRotation().isPresent());
        }
    }

    @Test public void matchingOtherDoorHalfIsAcceptedButMalformedPairIsNot() throws Exception {
        Fixture f=fixture(); f.hit=hit(DEST);
        assertTrue(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
        f.hit=hit(DEST.above());
        assertTrue(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
        // Force the ray to a neighbour outside the passage so it cannot be independently selected.
        BlockPos top=DEST.above(2);
        f.world.states.put(top.asLong(),door(false).setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER));
        f.hit=hit(top);
        MovementState state=running();
        assertTrue(MovementHelper.openDoorOnRoute(f.bot,state,FROM,new BlockPos[]{DEST.above()}));
        assertFalse("an upper half's partner cannot be above it",input(state,Input.CLICK_RIGHT));
        f.world.states.put(DEST.asLong(),door(false).setValue(DoorBlock.FACING,Direction.NORTH));
        f.hit=hit(DEST);state=running();
        MovementHelper.openDoorOnRoute(f.bot,state,FROM,new BlockPos[]{DEST.above()});
        assertFalse("different orientation is not the same paired door",input(state,Input.CLICK_RIGHT));
    }

    @Test public void reachIsCheckedAgainstCurrentHitLocation() throws Exception {
        Fixture f=fixture(); f.hit=new BlockHitResult(new Vec3(100,100,100),Direction.WEST,DEST,false);
        assertFalse(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
    }

    @Test public void actualSneakingMustEndBeforeUse() throws Exception {
        Fixture f=fixture(); f.player.crouching=true;
        MovementState state=f.descend.updateState(running().setInput(Input.SNEAK,true));
        assertFalse(input(state,Input.SNEAK)); assertFalse(input(state,Input.CLICK_RIGHT));
        f.player.crouching=false;
        assertTrue(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
    }

    @Test public void secondaryUseCannotPlaceABlockEvenWhenPoseIsNotCrouching() throws Exception {
        Fixture f=fixture();f.player.shift=true;
        assertFalse(f.player.isCrouching());assertTrue(f.player.isSecondaryUseActive());
        MovementState state=f.descend.updateState(running());
        assertFalse(input(state,Input.SNEAK));assertFalse(input(state,Input.CLICK_RIGHT));
    }

    @Test public void airborneDescendKeepsItsNormalSteeringWithoutClick() throws Exception {
        Fixture f=fixture(); f.player.grounded=false;
        MovementState state=f.descend.updateState(running());
        assertFalse(input(state,Input.CLICK_RIGHT)); assertTrue(input(state,Input.MOVE_FORWARD));
    }

    @Test public void passableDoorDoesNotToggleAgainAndDescendContinues() throws Exception {
        Fixture f=fixture();
        assertTrue(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
        f.setDoor(door(true));
        MovementState next=f.descend.updateState(running());
        assertFalse(input(next,Input.CLICK_RIGHT)); assertTrue(input(next,Input.MOVE_FORWARD));
        assertEquals(0,f.world.writes);
    }

    @Test public void sidewaysClosedDoorNeedsNoInteractionButOpenPanelCanBlock() throws Exception {
        Fixture f=fixture(); f.setDoor(door(false).setValue(DoorBlock.FACING,Direction.NORTH));
        assertFalse(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
        f.setDoor(door(true).setValue(DoorBlock.FACING,Direction.NORTH));
        assertTrue(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
    }

    @Test public void nonHandDoorAndForbiddenOrAbsentRouteContextNeverReceiveUse() throws Exception {
        Fixture f=fixture(); f.setDoor(Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.EAST));
        MovementState state=f.descend.updateState(running());
        assertEquals(MovementStatus.UNREACHABLE,state.getStatus()); assertFalse(input(state,Input.CLICK_RIGHT));
        f.setDoor(door(false)); f.cost.barriers=false;
        assertEquals(COST_INF,f.descend.calculateCost(f.cost),0);
        state=f.descend.updateState(running());
        assertEquals(MovementStatus.UNREACHABLE,state.getStatus()); assertFalse(input(state,Input.CLICK_RIGHT));
        set(f.pathing,"context",null);
        assertEquals(MovementStatus.UNREACHABLE,f.descend.updateState(running()).getStatus());
    }

    @Test public void doorwayAtUpperAndLowerDescendHeightUsesHorizontalProjection() throws Exception {
        for (int dy : new int[]{0,1}) {
            Fixture f=fixture(); f.world.states.remove(DEST.asLong()); f.world.states.remove(DEST.above().asLong());
            BlockPos lower=DEST.above(dy);
            f.world.states.put(lower.asLong(),door(false));
            f.world.states.put(lower.above().asLong(),door(false).setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
            f.hit=hit(lower);
            assertTrue(input(f.descend.updateState(running()),Input.CLICK_RIGHT));
        }
    }

    @Test public void barrierFreeDescendCostAndSteeringAreUnchanged() throws Exception {
        Fixture f=fixture(); double doorCost=f.descend.calculateCost(f.cost);
        f.world.states.remove(DEST.asLong());f.world.states.remove(DEST.above().asLong());
        assertEquals(doorCost,f.descend.calculateCost(f.cost),0);
        MovementState state=f.descend.updateState(running());
        assertTrue(input(state,Input.MOVE_FORWARD));assertFalse(input(state,Input.CLICK_RIGHT));
    }

    private static Fixture fixture() throws Exception {
        Fixture f=new Fixture(); f.world=allocate(World.class);f.world.states=new HashMap<>();
        f.world.states.put(FROM.below().asLong(),Blocks.STONE.defaultBlockState());
        f.world.states.put(DEST.below().asLong(),Blocks.STONE.defaultBlockState());f.setDoor(door(false));
        f.player=allocate(Player.class);f.player.grounded=true;f.player.pos=Vec3.atBottomCenterOf(FROM);
        f.hit=hit(DEST.above());
        IPlayerController controller=proxy(IPlayerController.class,(method,args)->switch(method) {
            case "getBlockReachDistance" -> 4.5D;
            default -> throw new AssertionError("no controller action: "+method);
        });
        IPlayerContext ctx=proxy(IPlayerContext.class,(method,args)->switch(method) {
            case "world" -> f.world;
            case "player" -> f.player;
            case "playerHead" -> f.player.pos.add(0,1.62,0);
            case "playerFeet" -> FROM;
            case "playerRotations" -> new Rotation(0,0);
            case "playerController" -> controller;
            case "objectMouseOver" -> f.hit;
            default -> throw new AssertionError("unexpected context query: "+method);
        });
        f.cost=allocate(Context.class);f.cost.barriers=true;
        BlocksView bsi=allocate(BlocksView.class);bsi.world=f.world;
        set(f.cost,"world",f.world);set(f.cost,"bsi",bsi);set(f.cost,"allowBreakAnyway",List.of());
        PrecomputedData precomputed=new PrecomputedData();
        // Cache the genuine state-only predicates with an explicit immutable settings input. Do not initialise
        // the global client/provider merely to construct a pathfinder input snapshot in a headless test.
        byte[] cache=(byte[])field(PrecomputedData.class,"data").get(precomputed);
        MovementHelper.WalkSettings settings=new MovementHelper.WalkSettings() {
            public boolean avoids(Block block){return false;} public boolean allowWalkOnMagmaBlocks(){return false;}
            public boolean allowVines(){return false;} public boolean allowWalkOnBottomSlab(){return false;}
            public boolean assumeWalkOnLava(){return false;}
        };
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int data=1;
            Ternary through=MovementHelper.canWalkThroughBlockState(state,settings);
            Ternary on=MovementHelper.canWalkOnBlockState(state,settings);
            if(through==Ternary.YES)data|=16;else if(through==Ternary.MAYBE)data|=8;
            if(on==Ternary.YES)data|=64;else if(on==Ternary.MAYBE)data|=32;
            cache[Block.BLOCK_STATE_REGISTRY.getId(state)]=(byte)data;
        }
        set(f.cost,"precomputedData",precomputed);
        f.pathing=allocate(PathingBehavior.class);set(f.pathing,"context",f.cost);
        f.bot=proxy(IPrinceps.class,(method,args)->switch(method) {
            case "getPlayerContext" -> ctx;
            case "getPathingBehavior" -> f.pathing;
            default -> throw new AssertionError("no other process or actions: "+method);
        });
        f.descend=new ReadyDescend(f.bot,FROM,DEST);f.descend.forceSafeMode=true;
        return f;
    }

    private static BlockState door(boolean open){return Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.EAST).setValue(DoorBlock.OPEN,open);}
    private static BlockHitResult hit(BlockPos pos){return new BlockHitResult(Vec3.atCenterOf(pos),Direction.WEST,pos,false);}
    private static MovementState running(){return new MovementState().setStatus(MovementStatus.RUNNING);}
    private static boolean input(MovementState state,Input input){return Boolean.TRUE.equals(state.getInputStates().get(input));}
    private static final class Fixture {
        World world;Player player;Context cost;PathingBehavior pathing;IPrinceps bot;ReadyDescend descend;HitResult hit;
        void setDoor(BlockState lower){world.states.put(DEST.asLong(),lower);world.states.put(DEST.above().asLong(),lower.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));}
    }
    // Only the already-completed preparation stage is supplied here. The actual RUNNING updateState and normal
    // Descend cost are executed, including their route actor. Mining/preparation has independent support tests.
    private static class ReadyDescend extends MovementDescend {
        ReadyDescend(IPrinceps bot,BetterBlockPos from,BetterBlockPos dest){super(bot,from,dest);}
        @Override protected boolean prepared(MovementState state){return true;}
    }
    private static class ReadyTraverse extends MovementTraverse {
        ReadyTraverse(IPrinceps bot,BetterBlockPos from,BetterBlockPos dest){super(bot,from,dest);}
        @Override protected boolean prepared(MovementState state){return true;}
    }
    private static class Context extends CalculationContext {
        boolean barriers;
        Context(){super(null);}
        @Override public boolean mayUsePathingBarriers(){return barriers;}
    }
    private static class Player extends LocalPlayer {
        boolean grounded,crouching,shift;Vec3 pos;
        Player(){super(null,null,null,null,null,null,false,null);}
        @Override public boolean onGround(){return grounded;}
        @Override public boolean isCrouching(){return crouching;}
        @Override public boolean isShiftKeyDown(){return shift;}
        @Override public Vec3 position(){return pos;}
    }
    private static class World extends ClientLevel {
        Map<Long,BlockState> states;int writes;
        World(){super(null,null,null,null,0,0,null,false,0L,0);}
        @Override public BlockState getBlockState(BlockPos pos){return states.getOrDefault(pos.asLong(),Blocks.AIR.defaultBlockState());}
        @Override public boolean setBlock(BlockPos pos,BlockState state,int flags,int limit){writes++;throw new AssertionError("ClientWorld mutation");}
    }
    private static class BlocksView extends BlockStateInterface {
        World world;
        BlocksView(){super((IPlayerContext)null);}
        @Override public BlockState get0(int x,int y,int z){return world.getBlockState(new BlockPos(x,y,z));}
    }
    private interface Call {Object invoke(String method,Object[] args) throws Throwable;}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    private static <T>T allocate(Class<T> type)throws Exception{return type.cast(unsafe.allocateInstance(type));}
    private static Field field(Class<?> type,String name)throws Exception {
        for(Class<?> t=type;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static void set(Object owner,String name,Object value)throws Exception{field(owner.getClass(),name).set(owner,value);}
}
