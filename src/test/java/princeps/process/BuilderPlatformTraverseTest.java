/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
import org.junit.*;
import princeps.api.pathing.goals.Goal;
import princeps.pathing.movement.movements.MovementTraverse;
import static org.junit.Assert.*;
import static princeps.api.pathing.movement.ActionCosts.*;
import static princeps.process.PlatformTraverseFixture.*;

public class BuilderPlatformTraverseTest {
    @BeforeClass public static void bootstrap()throws Exception{PlatformTraverseFixture.bootstrap();}
    @Test public void realVerdictOffersNormalFiniteTraverseOntoExactPermanentTarget()throws Exception {
        var f=new PlatformTraverseFixture();
        var verdict=f.builder.urteileUeber(TARGET,f.cost);
        assertTrue(verdict instanceof BuilderProcess.CellUrteil.Setzen);
        Goal goal=((BuilderProcess.CellUrteil.Setzen)verdict).ziel();assertTrue(goal.isInGoal(TARGET.above()));
        assertFalse(goal.isInGoal(FROM));assertEquals(SNEAK_ONE_BLOCK_COST,MovementTraverse.cost(f.cost,10,21,10,11,10),0);
        assertEquals(0,f.world.writes);
    }
    @Test public void oneStepSnapshotForbidsDetourHelperAndEveryBreak()throws Exception {
        var f=new PlatformTraverseFixture();assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));f.snapshotApproach();
        assertTrue(f.cost.isPathPositionAllowed(10,21,10));assertTrue(f.cost.isPathPositionAllowed(11,21,10));
        for(BlockPos p:new BlockPos[]{FROM.below(),FROM.above(),FROM.north(),TARGET.east().above()})
            assertFalse(f.cost.isPathPositionAllowed(p.getX(),p.getY(),p.getZ()));
        assertEquals(0,f.cost.costOfPlacingAt(11,20,10,Blocks.AIR.defaultBlockState()),0);
        assertEquals(COST_INF,f.cost.costOfPlacingAt(12,20,10,Blocks.AIR.defaultBlockState()),0);
        assertEquals(COST_INF,f.cost.costOfPlacingAt(10,19,10,Blocks.AIR.defaultBlockState()),0);
        assertEquals(COST_INF,f.cost.breakCostMultiplierAt(10,20,10,Blocks.OAK_PLANKS.defaultBlockState()),0);
        assertFalse(f.builder.allowsSupportRemoval(FROM.below(),f.blocks));
    }
    @Test public void crossingIntoEmptyDestinationKeepsSameGoalAndOriginalSupport()throws Exception {
        var f=new PlatformTraverseFixture();Goal first=f.builder.platformTraverseGoal(TARGET,f.cost,true);assertNotNull(first);
        set(f.builder,"electedCell",TARGET);f.player.pos=new Vec3(11.2,21,10.5);
        assertSame(first,f.builder.platformTraverseGoal(TARGET,f.cost,true));
        assertNull(f.builder.platformTraverseGoal(TARGET.east(),f.cost,true));
        assertSame(first,f.builder.platformTraverseGoal(TARGET,f.cost,true));
    }
    @Test public void ordinaryExecutingRouteMustEndBeforeNewOfferWhileExactMarkedStepContinues()throws Exception {
        var f=new PlatformTraverseFixture();
        var route=allocate(princeps.pathing.path.PathExecutor.class);
        set(route,"path",goalPath(new princeps.api.pathing.goals.GoalBlock(TARGET.above())));
        set(f.pathing,"current",route);
        assertTrue("no new action may be spliced onto an ordinary executor",f.builder.platformTraverseGoal(TARGET,f.cost,true)==null);
        assertNull(get(f.builder,"platformTraverseApproach"));
        set(f.pathing,"current",null);
        Goal marked=f.builder.platformTraverseGoal(TARGET,f.cost,true);assertNotNull(marked);
        set(route,"path",goalPath(marked));set(f.pathing,"current",route);
        assertSame(marked,f.builder.platformTraverseGoal(TARGET,f.cost,true));
        set(route,"path",goalPath(new princeps.api.pathing.goals.GoalBlock(TARGET.above())));
        assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        assertNull(get(f.builder,"platformTraverseApproach"));
    }
    @Test public void actualMiningEntryRetainsNoBreakRuleWhileInvalidatedRouteIsStillBeingCancelled()throws Exception {
        var f=new PlatformTraverseFixture();Goal original=f.builder.platformTraverseGoal(TARGET,f.cost,true);assertNotNull(original);f.snapshotApproach();
        f.world.states.remove(FROM.below().asLong());assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        assertNull(get(f.builder,"platformTraverseApproach"));
        var pathing=allocate(princeps.behavior.PathingBehavior.class);
        var route=allocate(princeps.pathing.path.PathExecutor.class);
        set(route,"path",proxy(princeps.api.pathing.calc.IPath.class,(name,args)->{
            if(name.equals("getGoal")) return original;
            throw new AssertionError("Unexpected path access: "+name);
        }));
        set(pathing,"current",route);
        var bot=allocate(princeps.Princeps.class);set(bot,"pathingBehavior",pathing);set(f.builder,"princeps",bot);
        assertFalse(f.builder.allowsSupportRemoval(TARGET.above()));
    }
    @Test public void nextPermanentTargetUsesFirstOnlyAfterObservedLanding()throws Exception {
        var f=new PlatformTraverseFixture();assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.player.pos=Vec3.atBottomCenterOf(TARGET.above());
        assertNull(f.builder.platformTraverseGoal(TARGET.east(),f.cost,false));
        f.world.states.put(TARGET.asLong(),Blocks.OAK_PLANKS.defaultBlockState());
        var clear=BuilderProcess.class.getDeclaredMethod("clearElectedTarget");clear.setAccessible(true);clear.invoke(f.builder);
        Goal next=f.builder.platformTraverseGoal(TARGET.east(),f.cost,true);assertNotNull(next);
        assertTrue(next.isInGoal(TARGET.east().above()));assertEquals(0,f.world.writes);
    }
    @Test public void materialWaitWithdrawsEdgeProofWithoutChangingChosenCell()throws Exception {
        var f=new PlatformTraverseFixture();Goal goal=f.builder.platformTraverseGoal(TARGET,f.cost,true);assertNotNull(goal);
        set(f.builder,"electedCell",TARGET);set(f.builder,"electedGoal",goal);f.snapshotApproach();
        f.inventory.setItem(1,ItemStack.EMPTY);
        assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));assertNull(get(f.builder,"platformTraverseApproach"));
        assertNull(f.builder.acceptElectedVerdict(new BuilderProcess.CellUrteil.Unbekannt("material wait")));
        assertSame(TARGET,get(f.builder,"electedCell"));
        f.snapshotApproach();assertTrue("new ordinary route is not restricted to two cells",f.cost.isPathPositionAllowed(15,19,9));
        f.inventory.setItem(1,new ItemStack(Items.OAK_PLANKS,64));
        assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
    }
    @Test public void actualPauseAndTargetCompletionReleaseProofAndFutureRouteBounds()throws Exception {
        var f=new PlatformTraverseFixture();
        set(f.builder,"progressWatch",new princeps.process.builder.BuilderProgressWatch(5,60,2));
        assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));f.snapshotApproach();
        f.builder.pause();assertNull(get(f.builder,"platformTraverseApproach"));
        f.snapshotApproach();assertTrue(f.cost.isPathPositionAllowed(15,19,9));
        f.builder.resume();assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        set(f.builder,"electedCell",TARGET);
        set(f.builder,"stanceFailures",new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
        set(f.builder,"stanceEndorsed",new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
        var completed=BuilderProcess.class.getDeclaredMethod("noteCellCompleted",princeps.api.utils.BetterBlockPos.class);
        completed.setAccessible(true);completed.invoke(f.builder,TARGET);
        assertNull(get(f.builder,"platformTraverseApproach"));assertNull(get(f.builder,"electedCell"));
        f.snapshotApproach();assertTrue(f.cost.isPathPositionAllowed(15,19,9));
    }
    @Test public void activeOverlayCannotTurnRealModelAirIntoPermanentPlatformTarget()throws Exception {
        var f=new PlatformTraverseFixture();
        set(f.builder,"realSchematic",new princeps.api.schematic.AbstractSchematic(3,1,1){
            @Override public net.minecraft.world.level.block.state.BlockState desiredState(int x,int y,int z,
                    net.minecraft.world.level.block.state.BlockState current,java.util.List<net.minecraft.world.level.block.state.BlockState> stock){
                return x==0?Blocks.OAK_PLANKS.defaultBlockState():Blocks.AIR.defaultBlockState();
            }
        });
        assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
    }
    @Test public void unknownChunkMissingItemAndUnsupportedOrObstructedPlatformOfferNothing()throws Exception {
        var f=new PlatformTraverseFixture();f.blocks.loaded=false;assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.blocks.loaded=true;f.inventory.setItem(1,ItemStack.EMPTY);assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.inventory.setItem(1,new ItemStack(Items.DIRT,64));assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.inventory.setItem(1,new ItemStack(Items.OAK_PLANKS,64));f.world.states.remove(FROM.below().asLong());assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.world.states.put(FROM.below().asLong(),Blocks.OAK_PLANKS.defaultBlockState());
        for(BlockPos p:new BlockPos[]{FROM,FROM.above(),TARGET.above(),TARGET.above(2)}){
            f.world.states.put(p.asLong(),Blocks.STONE.defaultBlockState());assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));f.world.states.remove(p.asLong());
        }
    }
    @Test public void airTargetFallingBlockPartialShapeAndFacingAreNeverGenericTemplateSteps()throws Exception {
        var f=new PlatformTraverseFixture();
        for(var state:new net.minecraft.world.level.block.state.BlockState[]{Blocks.AIR.defaultBlockState(),Blocks.SAND.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(),Blocks.OAK_STAIRS.defaultBlockState(),Blocks.STICKY_PISTON.defaultBlockState()}) {
            f.wanted.put(TARGET.asLong(),state);
            if (!state.isAir()) f.inventory.setItem(1,new ItemStack(state.getBlock().asItem(),64));
            var stock=new java.util.ArrayList<>(java.util.Collections.nCopies(9,Blocks.AIR.defaultBlockState()));stock.set(1,state);
            set(f.cost,"placeable",stock);set(f.builder,"approxPlaceable",stock);
            assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        }
    }
    @Test public void slabStairAndSoulSandCannotServeAsAssumedFullPlatform()throws Exception {
        var f=new PlatformTraverseFixture();
        for(var state:new net.minecraft.world.level.block.state.BlockState[]{Blocks.OAK_SLAB.defaultBlockState(),Blocks.OAK_STAIRS.defaultBlockState(),Blocks.SOUL_SAND.defaultBlockState()}){
            f.world.states.put(FROM.below().asLong(),state);f.wanted.put(FROM.below().asLong(),state);
            assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        }
    }
    @Test public void pauseAirborneWorldPlayerAndTargetChangesCannotReuseOldProof()throws Exception {
        var f=new PlatformTraverseFixture();assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        set(f.builder,"paused",true);assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,false));set(f.builder,"paused",false);
        f.player.grounded=false;assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));f.player.grounded=true;
        assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.wanted.put(TARGET.asLong(),Blocks.STONE.defaultBlockState());assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.wanted.put(TARGET.asLong(),Blocks.OAK_PLANKS.defaultBlockState());assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.world=allocate(World.class);f.world.states=new java.util.HashMap<>();assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
    }
    @Test public void outsidePlanWrongExistingIdentityAndReadOnlyQueryDoNotCreateApproach()throws Exception {
        var f=new PlatformTraverseFixture();assertNotNull(f.builder.platformTraverseGoal(TARGET,f.cost,false));
        assertNull(get(f.builder,"platformTraverseApproach"));
        f.world.states.put(TARGET.asLong(),Blocks.STONE.defaultBlockState());assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        f.world.states.remove(TARGET.asLong());set(f.builder,"buildInRows",true);assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
        set(f.builder,"buildInRows",false);set(f.builder,"excavating",true);assertNull(f.builder.platformTraverseGoal(TARGET,f.cost,true));
    }
    private static princeps.api.pathing.calc.IPath goalPath(Goal goal){
        return proxy(princeps.api.pathing.calc.IPath.class,(name,args)->{
            if(name.equals("getGoal")) return goal;
            throw new AssertionError("unexpected path query: "+name);
        });
    }
}
