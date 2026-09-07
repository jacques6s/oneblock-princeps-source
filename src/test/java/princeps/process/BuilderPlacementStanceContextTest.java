/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.IPlayerController;
import princeps.api.utils.BetterBlockPos;
import princeps.api.behavior.look.IAimProcessor;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Actual vanilla placement input and the builder's existing candidate aim/simulation methods. */
public class BuilderPlacementStanceContextTest {
    private static Unsafe unsafe;
    private static Method simulate, aims, vanillaPlacement, sampler;
    private static final BlockPos TARGET = new BlockPos(0, 20, 0);

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Item item : List.of(Items.WATER_BUCKET, Items.PALE_OAK_DOOR,Items.STONE))
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        unsafe = (Unsafe) field.get(null);
        simulate = BuilderProcess.class.getDeclaredMethod("simulatePlacement", ItemStack.class,
                BlockHitResult.class, Rotation.class, Vec3.class); simulate.setAccessible(true);
        aims = BuilderProcess.class.getDeclaredMethod("aimPointsOnFace", Vec3.class, BlockPos.class,
                AABB.class, Direction.class, BlockState.class); aims.setAccessible(true);
        vanillaPlacement = BlockItem.class.getDeclaredMethod("getPlacementState", BlockPlaceContext.class);
        vanillaPlacement.setAccessible(true);
        sampler=BuilderProcess.class.getDeclaredMethod("placementStancesFor",int.class,int.class,int.class,
                BlockState.class,BuilderProcess.BuilderCalculationContext.class,int.class,boolean.class,
                java.util.function.LongPredicate.class);sampler.setAccessible(true);
    }

    @Test public void actualVanillaDoorAndBuilderSimulationFromAValidRaisedStance() throws Exception {
        Fixture f = fixture();
        assertPlaceable(f,new Vec3(-0.5,21,0.5));
    }

    @Test public void actualVanillaDoorCanBePlacedFromItsEmptyTargetCell() throws Exception {
        Fixture f = fixture();
        f.world.states.put(TARGET.west().above(2).asLong(),Blocks.CHEST.defaultBlockState());
        set(f.player,"blocksBuilding",true);
        assertPlaceable(f,new Vec3(0.5,20,0.5));
    }

    @Test public void allHorizontalFacingsHaveAnExactlyVerifiedPanelPlacement() throws Exception {
        for(Direction facing:Direction.Plane.HORIZONTAL) {
            int exact=0;
            for(DoorHingeSide hinge:DoorHingeSide.values()) exact+=scan(fixture(facing,hinge),new Vec3(.5,20,.5)).vanillaExact;
            assertTrue("a verified hinge for "+facing,exact>0);
        }
    }

    @Test public void aFullCubeStillCannotMaterialiseInsideTheCandidateBody() throws Exception {
        Fixture door=fixture();
        Fixture cube=new Fixture(door.world,door.player,door.builder,new ItemStack(Items.STONE),Blocks.STONE.defaultBlockState(),door.processor);
        Scan scan=scan(cube,new Vec3(0.5,20,0.5));
        assertTrue(scan.rays>0);assertEquals(0,scan.vanillaExact);
    }

    @Test public void aBodyThatReallyOverlapsTheDoorPanelIsRejected() throws Exception {
        Fixture f=fixture();
        Scan scan=scan(f,new Vec3(.15,20,.5));
        assertTrue(scan.rays>0);assertEquals(0,scan.vanillaExact);
    }

    @Test public void anotherEntityInThePanelRemainsAnObstruction() throws Exception {
        Fixture f=fixture();Player other=allocate(Player.class);
        other.setPos(.05,20,.5);set(other,"blocksBuilding",true);f.world.other=other;
        Scan scan=scan(f,new Vec3(.5,20,.5));
        assertTrue(scan.rays>0);assertEquals(0,scan.vanillaExact);
    }

    @Test public void missingPermanentSupportAndOccupiedUpperHalfRemainInvalid() throws Exception {
        Fixture unsupported=fixture();unsupported.world.states.remove(TARGET.below().asLong());
        Scan missing=scan(unsupported,new Vec3(.5,20,.5));
        assertTrue(missing.rays>0);assertEquals(0,missing.vanillaExact);
        Fixture blocked=fixture();blocked.world.states.put(TARGET.above().asLong(),Blocks.STONE.defaultBlockState());
        Scan upper=scan(blocked,new Vec3(.5,20,.5));
        assertEquals(0,upper.vanillaExact);
    }

    @Test public void missingHotbarMaterialDoesNotReachCandidateGeometry() throws Exception {
        Fixture f=fixture();f.player.getInventory().clearContent();
        var context=allocate(BuilderProcess.BuilderCalculationContext.class);
        java.util.function.LongPredicate mustNotRun=key->{throw new AssertionError("candidate without material");};
        assertEquals(List.of(),sampler.invoke(f.builder,0,20,0,f.desired,context,99,false,mustNotRun));
    }

    @Test public void simulationRestoresPositionRotationAndCameraSuspensionAfterVanillaFailure() throws Exception {
        Fixture f=fixture();Vec3 remote=new Vec3(-25.5,20,-30.5);f.player.setPos(remote.x,remote.y,remote.z);
        f.player.setYRot(123);f.player.setXRot(17);f.player.setYHeadRot(101);
        Field camera=princeps.flownav.FlowCam.class.getDeclaredField("suspended");camera.setAccessible(true);
        Object before=camera.get(null);f.world.rejectHeightRead=true;
        try {
            simulate.invoke(f.builder,f.stack,new BlockHitResult(new Vec3(.9,20,.5),Direction.UP,TARGET.below(),false),
                    new Rotation(-90,70),new Vec3(.5,20,.5));
            fail("expected deliberate vanilla-input failure");
        }catch(java.lang.reflect.InvocationTargetException e){assertEquals("height input unavailable",e.getCause().getMessage());}
        assertEquals(remote,f.player.position());assertEquals(123,f.player.getYRot(),0);
        assertEquals(17,f.player.getXRot(),0);assertEquals(101,f.player.getYHeadRot(),0);assertEquals(before,camera.get(null));
    }

    @Test public void realSamplerMustConsiderTheVanillaValidTargetColumn() throws Exception {
        Fixture f=fixture();
        // Observe the actual generated/sorted candidates via its existing allowed predicate. A fluid-only input
        // rejects all movement candidates before any global client/provider lookup; no stance result is mocked.
        f.world.fluidEverywhere=true;
        Set<Long> seen=new HashSet<>();
        var context=allocate(BuilderProcess.BuilderCalculationContext.class);
        set(context,"hasThrowaway",false);
        java.util.function.LongPredicate observer=key->{seen.add(key);return false;};
        assertEquals(List.of(),sampler.invoke(f.builder,0,20,0,f.desired,context,99,false,observer));
        assertTrue("real sampler discarded an exact vanilla placement stance",seen.contains(TARGET.asLong()));
        assertTrue("ordinary side candidates remain",seen.contains(TARGET.east().asLong()));
    }

    private void assertPlaceable(Fixture f,Vec3 feet) throws Exception {
        Scan scan=scan(f,feet);
        assertTrue("real candidate rays",scan.rays>0);
        assertTrue("candidate can place exact desired state "+f.desired,scan.vanillaExact>0);
        assertTrue("builder predicts the same desired state",scan.builderExact>0);
    }

    private Scan scan(Fixture f,Vec3 feet) throws Exception {
        Vec3 remote = new Vec3(-25.5, 20, -30.5);
        f.player.setPos(remote.x, remote.y, remote.z);
        Vec3 eye = feet.add(0, 1.27, 0);
        int rays = 0, exact = 0, vanillaExact = 0;
        List<String> accepted=new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos support = TARGET.relative(side);
            BlockState state = f.world.getBlockState(support);
            if (state.isAir()) continue;
            var shape = state.getShape(f.world, support);
            if (shape.isEmpty()) continue;
            @SuppressWarnings("unchecked") List<Vec3> points = (List<Vec3>) aims.invoke(null,
                    eye, support, shape.bounds(), side, f.desired);
            for (Vec3 point : points) {
                if (eye.distanceTo(point) > 4.5) continue;
                Rotation rotation = f.processor.peekRotationExact(RotationUtils.calcRotationFromVec3d(eye, point, new Rotation(0,0)));
                Vec3 end = eye.add(RotationUtils.calcLookDirectionFromRotation(rotation).scale(4.5));
                BlockHitResult hit = f.world.clip(new ClipContext(eye,end,ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,f.player));
                if (hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(support)
                        || hit.getDirection()!=side.getOpposite()) continue;
                rays++;
                BlockState result = (BlockState) simulate.invoke(f.builder,f.stack,hit,rotation,feet);
                if (f.desired.equals(result)) exact++;
                assertEquals(remote, f.player.position());
                f.player.setPos(feet.x,feet.y,feet.z);f.player.setYRot(rotation.getYaw());
                BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(f.world,f.player,
                        InteractionHand.MAIN_HAND,f.stack,hit) {});
                BlockState realItem = (BlockState) vanillaPlacement.invoke(f.stack.getItem(), context);
                if(f.desired.equals(realItem)){
                    vanillaExact++;
                    accepted.add("support="+support+" side="+hit.getDirection()+" hit="+hit.getLocation()+" rotation="+rotation);
                }
                f.player.setPos(remote.x,remote.y,remote.z);
            }
        }
        if(feet.equals(new Vec3(.5,20,.5)) && f.desired.equals(Blocks.PALE_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.EAST)))
            System.out.println("placement evidence rays="+rays+" vanillaExact="+vanillaExact+" accepted="+accepted);
        return new Scan(rays,exact,vanillaExact);
    }

    private record Scan(int rays,int builderExact,int vanillaExact){}

    private static Fixture fixture() throws Exception {return fixture(Direction.EAST,DoorHingeSide.LEFT);}

    private static Fixture fixture(Direction facing,DoorHingeSide hinge) throws Exception {
        World world=allocate(World.class);world.states=new HashMap<>();
        world.states.put(TARGET.below().asLong(),Blocks.STONE.defaultBlockState());
        world.states.put(TARGET.relative(facing.getOpposite()).asLong(),Blocks.DIRT.defaultBlockState());
        for(Direction side:List.of(facing.getClockWise(),facing.getCounterClockWise())) {
            world.states.put(TARGET.relative(side).asLong(),Blocks.BLACK_STAINED_GLASS.defaultBlockState());
            world.states.put(TARGET.relative(side).above().asLong(),Blocks.BLACK_STAINED_GLASS.defaultBlockState());
        }
        Player player=allocate(Player.class);set(player,"level",world);world.player=player;
        set(player,"type",EntityType.PLAYER);
        set(player,"attributes",new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        set(player,"blocksBuilding",true);
        Inventory inventory=new Inventory(player,new EntityEquipment());set(player,"inventory",inventory);
        inventory.setItem(1,new ItemStack(Items.PALE_OAK_DOOR));
        player.setPos(0,20,0);
        Minecraft minecraft=allocate(Minecraft.class);Options options=allocate(Options.class);
        OptionInstance<?> sensitivity=allocate(OptionInstance.class);set(sensitivity,"value",.5D);
        set(options,"sensitivity",sensitivity);set(minecraft,"options",options);
        IPlayerController controller=(IPlayerController)Proxy.newProxyInstance(IPlayerController.class.getClassLoader(),
                new Class<?>[]{IPlayerController.class},(p,m,a)->{
                    if(m.getName().equals("getBlockReachDistance"))return 4.5D;
                    throw new AssertionError("unexpected controller action "+m.getName());
                });
        IPlayerContext ctx=(IPlayerContext)Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class},(p,m,a)->switch(m.getName()) {
                    case "world" -> world;case "player" -> player;
                    case "playerFeet" -> new BetterBlockPos(player.blockPosition());
                    case "playerController" -> controller;
                    case "playerRotations" -> new Rotation(0,0);
                    case "minecraft" -> minecraft;
                    default -> throw new AssertionError("unexpected client input "+m.getName());
                });
        BuilderProcess builder=allocate(BuilderProcess.class);set(builder,"ctx",ctx);
        var constructor=Class.forName("princeps.behavior.LookBehavior$AimProcessor").getDeclaredConstructor(IPlayerContext.class);
        constructor.setAccessible(true);IAimProcessor processor=(IAimProcessor)constructor.newInstance(ctx);
        return new Fixture(world,player,builder,new ItemStack(Items.PALE_OAK_DOOR),
                Blocks.PALE_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,facing).setValue(DoorBlock.HINGE,hinge),processor);
    }
    private record Fixture(World world,Player player,BuilderProcess builder,ItemStack stack,BlockState desired,IAimProcessor processor) {}
    private static final class World extends ClientLevel {
        Map<Long,BlockState> states;Player player,other;boolean fluidEverywhere,rejectHeightRead;
        World(){super(null,null,null,null,0,0,null,false,0L,0);}
        @Override public BlockState getBlockState(BlockPos pos){return fluidEverywhere?Blocks.WATER.defaultBlockState():states.getOrDefault(pos.asLong(),Blocks.AIR.defaultBlockState());}
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public int getMaxY(){if(rejectHeightRead)throw new IllegalStateException("height input unavailable");return 319;}
        @Override public int getMinY(){return -64;}
        @Override public boolean hasNeighborSignal(BlockPos pos){return false;}
        @Override public List<Entity> getEntities(Entity except,AABB box,Predicate<? super Entity> filter){
            List<Entity> result=new ArrayList<>();
            for(Player entity:new Player[]{player,other})if(entity!=null && entity!=except && entity.getBoundingBox().intersects(box) && filter.test(entity))result.add(entity);
            return result;
        }
        @Override public boolean setBlock(BlockPos pos,BlockState state,int flags,int limit){throw new AssertionError("no world writes in prediction");}
    }
    private static final class Player extends LocalPlayer {
        Player(){super(null,null,null,null,null,null,false,null);}
        @Override public void setPos(double x,double y,double z){
            try{set(this,"position",new Vec3(x,y,z));set(this,"blockPosition",BlockPos.containing(x,y,z));
                set(this,"bb",new AABB(x-.3,y,z-.3,x+.3,y+1.8,z+.3));}
            catch(Exception e){throw new AssertionError(e);}
        }
        @Override public boolean isDescending(){return false;}
        @Override public boolean onGround(){return true;}
        @Override public boolean isFallFlying(){return false;}
        @Override public boolean isSpectator(){return false;}
        @Override public ItemStack getMainHandItem(){return ItemStack.EMPTY;}
    }
    private static <T>T allocate(Class<T> type)throws Exception{return type.cast(unsafe.allocateInstance(type));}
    private static void set(Object target,String name,Object value)throws Exception {
        for(Class<?> t=target.getClass();t!=null;t=t.getSuperclass())try{
            Field f=t.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;
        }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
}
