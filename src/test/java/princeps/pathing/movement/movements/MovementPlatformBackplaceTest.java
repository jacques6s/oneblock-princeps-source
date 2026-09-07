/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.pathing.movement.movements;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
import org.junit.*;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.MovementState;
import princeps.process.PlatformTraverseFixture;
import princeps.process.builder.BuildTrace;
import static org.junit.Assert.*;
import static princeps.process.PlatformTraverseFixture.*;

/** Actual method called by RUNNING Traverse after its normal inventory/licence checks; no controller actions. */
public class MovementPlatformBackplaceTest {
    private Object intent,detail;
    @BeforeClass public static void bootstrap()throws Exception{PlatformTraverseFixture.bootstrap();}
    @Before public void saveIntent()throws Exception{
        intent=field(BuildTrace.class,"pendingIntentSource").get(null);detail=field(BuildTrace.class,"pendingIntentDetail").get(null);
    }
    @After public void restoreIntent()throws Exception{
        field(BuildTrace.class,"pendingIntentSource").set(null,intent);field(BuildTrace.class,"pendingIntentDetail").set(null,detail);
    }
    @Test public void runningActorTurnsThenSneakBackplacesAgainstExactLiveFace()throws Exception{
        var f=edge();var move=movement(f);MovementState first=move.updateBackplace(running());
        assertTrue(input(first,Input.SNEAK));assertFalse(input(first,Input.MOVE_BACK));assertFalse(input(first,Input.CLICK_RIGHT));
        f.rotation=first.getTarget().rotation;f.hit=hit();MovementState next=move.updateBackplace(running());
        assertTrue(input(next,Input.MOVE_BACK));assertTrue(input(next,Input.CLICK_RIGHT));assertFalse(input(next,Input.JUMP));
        assertFalse(input(next,Input.CLICK_LEFT));assertEquals(MovementStatus.RUNNING,next.getStatus());assertEquals(0,f.world.writes);
    }
    @Test public void actualCrouchMustBeEstablishedBeforeEdgeMotionOrClick()throws Exception{
        var f=edge();var move=movement(f);f.hit=hit();f.player.crouching=false;
        MovementState state=move.updateBackplace(running());assertTrue(input(state,Input.SNEAK));
        assertFalse(input(state,Input.MOVE_BACK));assertFalse(input(state,Input.CLICK_RIGHT));
        f.player.crouching=true;assertTrue(input(move.updateBackplace(running()),Input.CLICK_RIGHT));
    }
    @Test public void airborneBodyDoesNotStartTheSneakBackplace()throws Exception{
        var f=edge();f.player.grounded=false;f.hit=hit();var state=movement(f).updateBackplace(running());
        assertFalse(input(state,Input.MOVE_BACK));assertFalse(input(state,Input.CLICK_RIGHT));assertFalse(input(state,Input.JUMP));
    }
    @Test public void crouchingPoseWithoutSecondaryUseCannotClickAnInteractiveSupport()throws Exception{
        var f=edge();var move=movement(f);f.hit=hit();f.player.shift=false;
        assertTrue(f.player.isCrouching());assertFalse(f.player.isSecondaryUseActive());
        var state=move.updateBackplace(running());assertTrue(input(state,Input.SNEAK));
        assertFalse(input(state,Input.CLICK_RIGHT));assertFalse(input(state,Input.MOVE_BACK));
        f.player.shift=true;assertTrue(f.player.isSecondaryUseActive());
        assertTrue(input(move.updateBackplace(running()),Input.CLICK_RIGHT));
    }
    @Test public void missForeignFaceWrongSideAndBeyondReachNeverSendPlacement()throws Exception{
        var f=edge();var move=movement(f);
        for(HitResult ray:new HitResult[]{null,BlockHitResult.miss(Vec3.ZERO,Direction.UP,FROM),
                new BlockHitResult(new Vec3(11,20.5,10.5),Direction.UP,FROM.below(),false),
                new BlockHitResult(new Vec3(11,20.5,10.5),Direction.EAST,FROM.below().north(),false),
                new BlockHitResult(new Vec3(100,20.5,10.5),Direction.EAST,FROM.below(),false)}){
            f.hit=ray;assertFalse(input(move.updateBackplace(running()),Input.CLICK_RIGHT));
        }
        f.hit=hit();f.reach=.5;assertFalse(input(move.updateBackplace(running()),Input.CLICK_RIGHT));
    }
    @Test public void waitAndFreshRayDoNotCarryOldClickOrBackwardsInput()throws Exception{
        var f=edge();var move=movement(f);f.player.crouching=false;
        var state=running().setInput(Input.CLICK_LEFT,true).setInput(Input.CLICK_RIGHT,true).setInput(Input.MOVE_BACK,true);
        move.updateBackplace(state);
        assertFalse(input(state,Input.CLICK_LEFT));assertFalse(input(state,Input.CLICK_RIGHT));assertFalse(input(state,Input.MOVE_BACK));
        f.player.crouching=true;f.hit=hit();move.updateBackplace(state);assertTrue(input(state,Input.CLICK_RIGHT));
        f.hit=null;move.updateBackplace(state);assertFalse(input(state,Input.CLICK_RIGHT));assertFalse(input(state,Input.CLICK_LEFT));
    }
    @Test public void sameNormalActorBuildsTwoConsecutiveEdgesOnlyAgainstEachCurrentSupport()throws Exception{
        var f=edge();f.hit=hit();assertTrue(input(movement(f).updateBackplace(running()),Input.CLICK_RIGHT));
        f.world.states.put(TARGET.asLong(),net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
        f.player.pos=new Vec3(12.2,21,10.5);
        var next=new MovementTraverse(f.bot,TARGET.above(),TARGET.east().above());
        assertFalse("the first support face cannot place the second target",input(next.updateBackplace(running()),Input.CLICK_RIGHT));
        f.hit=new BlockHitResult(new Vec3(12,20.5,10.5),Direction.EAST,TARGET,false);
        assertTrue(input(next.updateBackplace(running()),Input.CLICK_RIGHT));assertEquals(0,f.world.writes);
    }
    @Test public void extractedActorStillArms222IntegrityAndRejectsItsReplacedRoute()throws Exception{
        var f=edge(); f.hit=hit();
        var engine=allocate(princeps.Princeps.class);set(engine,"playerContext",f.ctx);set(engine,"pathingBehavior",f.pathing);f.bot=engine;
        var inputs=allocate(princeps.utils.InputOverrideHandler.class);set(engine,"inputOverrideHandler",inputs);
        var helper=allocate(princeps.utils.BlockPlaceHelper.class);set(helper,"ctx",f.ctx);set(helper,"princeps",engine);set(inputs,"blockPlaceHelper",helper);
        var executor=allocate(princeps.pathing.path.PathExecutor.class);
        var licence=princeps.api.pathing.PlacementLicence.excavationBridge(TARGET.asLong());
        set(executor,"placementLicence",licence);set(f.pathing,"current",executor);
        f.inventory.setSelectedSlot(1);
        var equipment=new net.minecraft.world.entity.EntityEquipment();set(f.player,"equipment",equipment);
        equipment.set(net.minecraft.world.entity.EquipmentSlot.MAINHAND,f.inventory.getItem(1));
        assertTrue(input(movement(f).updateBackplace(running()),Input.CLICK_RIGHT));
        Object expectation=get(helper,"expectedPlacement");assertNotNull(expectation);
        assertEquals(true,get(expectation,"excavationIntegrity"));assertEquals(TARGET,get(expectation,"target"));
        var stillCorrect=(java.util.function.BooleanSupplier)get(expectation,"stillCorrect");
        set(executor,"placementLicence",princeps.api.pathing.PlacementLicence.excavationBridge(TARGET.asLong()));
        assertFalse("same coordinate on a new licence is not the armed route",stillCorrect.getAsBoolean());
        helper.clearExpectedPlacement();set(helper,"rightClickTimer",1);
        assertFalse(input(movement(f).updateBackplace(running()),Input.CLICK_RIGHT));assertNull(get(helper,"expectedPlacement"));
        set(helper,"rightClickTimer",0);f.inventory.setSelectedSlot(0);
        equipment.set(net.minecraft.world.entity.EquipmentSlot.MAINHAND,net.minecraft.world.item.ItemStack.EMPTY);
        assertFalse(input(movement(f).updateBackplace(running()),Input.CLICK_RIGHT));assertNull(get(helper,"expectedPlacement"));
    }

    @Test public void bothRealTraversePlacementBranchesRetain222IntegrityGate()throws Exception{
        var calls=new java.util.HashMap<String,Integer>();
        try(var bytes=MovementTraverse.class.getResourceAsStream("MovementTraverse.class")){
            new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9){
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){
                            if(owner.equals("princeps/pathing/movement/movements/MovementTraverse")&&method.equals("armExcavationBridgeClick"))calls.merge(name,1,Integer::sum);
                        }
                    };
                }
            },org.objectweb.asm.ClassReader.SKIP_DEBUG);
        }
        assertEquals(java.util.Map.of("updateState",1,"updateBackplace",1),calls);
    }
    private static PlatformTraverseFixture edge()throws Exception{
        var f=new PlatformTraverseFixture();f.player.pos=new Vec3(11.2,21,10.5);return f;
    }
    private static MovementTraverse movement(PlatformTraverseFixture f){return new MovementTraverse(f.bot,FROM,TARGET.above());}
    private static BlockHitResult hit(){return new BlockHitResult(new Vec3(11,20.5,10.5),Direction.EAST,FROM.below(),false);}
    private static MovementState running(){return new MovementState().setStatus(MovementStatus.RUNNING);}
    private static boolean input(MovementState s,Input i){return Boolean.TRUE.equals(s.getInputStates().get(i));}
}
