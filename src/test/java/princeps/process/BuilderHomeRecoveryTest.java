/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.Princeps;
import princeps.api.event.events.TickEvent;
import princeps.api.event.events.type.EventState;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.process.PathingCommandType;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.input.Input;
import princeps.behavior.InventoryBehavior;
import princeps.behavior.SurvivalBehavior;
import princeps.pathing.calc.PathProbe;
import princeps.process.builder.BuilderProgressWatch;
import princeps.process.builder.ConfirmedBuildActions;
import princeps.process.builder.PlacementTargetLock;
import princeps.utils.BlockPlaceHelper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.*;
import static princeps.process.BuilderModelRestorationTest.*;

/** Real Builder entry/hold/resume, safe-cancel, delayed expectation, Survival teardown and server revision.
 * Stored movement safety and a loaded collision-free landing are explicit fixture inputs, not a physics run. */
public class BuilderHomeRecoveryTest {
    @BeforeClass public static void bootstrap() throws Exception { BuilderModelRestorationTest.bootstrap(); }

    @Test public void disabledDefaultAndNonRouteQuietNeverRequestHome() throws Exception {
        F f = fixture();
        assertFalse(f.begin()); assertEquals("DISABLED", f.b().homeRecoveryState());
        f.b().setHomeRecoveryEnabled(true); set(f.b(), "homeFailedRoute", null);
        assertFalse(f.begin()); assertEquals(0, f.b().homeRecoveryRequestId());
    }

    @Test public void materialChunkAndConfirmationWaitsAreNotTeleportEligibility() throws Exception {
        F f = fixture(); f.enable();
        for (var phase : List.of(BuilderProgressWatch.Phase.WAIT_MATERIAL, BuilderProgressWatch.Phase.WAIT_CHUNK,
                BuilderProgressWatch.Phase.WAIT_CONFIRMATION, BuilderProgressWatch.Phase.MINING,
                BuilderProgressWatch.Phase.PAUSED)) assertFalse(f.b().beginHomeRecovery(phase, 100));
        set(f.b(), "scaffoldMaterialTarget", new BetterBlockPos(TARGET)); assertFalse(f.begin());
        set(f.b(), "scaffoldMaterialTarget", null);
        f.world.unloaded = true; assertFalse(f.begin());
    }

    @Test public void changedTargetWorldRevisionRowsOrExcavationRefuseOldFailure() throws Exception {
        F f = fixture(); f.enable();
        set(f.b(), "confirmedProgressRevision", 1L); assertFalse(f.begin());
        set(f.b(), "confirmedProgressRevision", 0L); set(f.b(), "electedCell", new BetterBlockPos(TARGET.above())); assertFalse(f.begin());
        set(f.b(), "electedCell", new BetterBlockPos(TARGET)); set(f.b(), "buildInRows", true); assertFalse(f.begin());
        set(f.b(), "buildInRows", false); set(f.b(), "excavating", true); assertFalse(f.begin());
    }

    @Test public void unsafeCurrentCannotBecomeReadyAndItsActualContextIsRetained() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin());
        var old = f.route.executor();
        var command = f.b().advanceHomeRecovery(101);
        assertEquals(PathingCommandType.REVALIDATE_GOAL_AND_PATH, command.commandType);
        assertSame(old, f.route.pathing().getCurrent());
        assertTrue(command instanceof princeps.utils.PathingCommandContext);
        assertSame(f.route.base().cost, ((princeps.utils.PathingCommandContext) command).desiredCalcContext);
        assertEquals("QUIESCING", f.b().homeRecoveryState());
        assertFalse(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
    }

    @Test public void actualSafeCancelClearsDamageAndThrottledPlacementBeforeReady() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin());
        set(f.route.base().mining, "wasHitting", true);
        f.route.input().setInputForceState(Input.CLICK_RIGHT, true);
        f.place.expectMainHandPlacement(TARGET.below(), net.minecraft.core.Direction.UP, TARGET, 0, Items.DIRT);
        set(f.place, "rightClickTimer", 5);
        f.ready();
        assertNull(f.route.pathing().getCurrent()); assertFalse(f.route.base().mining.isBreakingBlock());
        assertEquals(1, f.route.base().resets.get());
        assertNull(get(f.place, "expectedPlacement"));
        assertFalse(f.route.input().isInputForcedDown(Input.CLICK_RIGHT));
        assertEquals("READY", f.b().homeRecoveryState());
    }

    @Test public void actualSurvivalTickReleasesOwnEatAndCannotRestartUseDuringHold() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin());
        set(f.survival, "eatRestoreSlot", 2); set(f.survival, "eatPlayer", f.player);
        set(f.survival, "eatItem", Items.APPLE); f.mc.options.keyUse.setDown(true);
        f.survival.onTick(new TickEvent(EventState.PRE, TickEvent.Type.IN, 1));
        assertFalse(f.survival.isConsuming()); assertFalse(f.mc.options.keyUse.isDown());
        assertEquals(2, f.player.getInventory().getSelectedSlot());
        f.survival.onTick(new TickEvent(EventState.PRE, TickEvent.Type.IN, 2));
        assertFalse(f.mc.options.keyUse.isDown());
    }

    @Test public void foreignOwnerDoesNotLoseItsSurvivalUseAndCannotResumeToken() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin()); f.ready();
        set(f.route.control(), "inControlThisTick", BuilderModelRouteProtectionTest.foreign());
        f.mc.options.keyUse.setDown(true);
        Method gate = SurvivalBehavior.class.getDeclaredMethod("suspendForBuilderHome", LocalPlayer.class); gate.setAccessible(true);
        assertEquals(false, gate.invoke(f.survival, f.player));
        assertTrue(f.mc.options.keyUse.isDown());
        assertFalse(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
        assertEquals("FAILED", f.b().homeRecoveryState());
    }

    @Test public void ownerInterludeRevokesReadyEvenAfterOriginalOwnerReturns() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin()); f.ready();
        // Actual Input callback observes OUT and revokes its prior owner before any attack/use work.
        set(f.route.input(), "supportMiningOwner", f.b());
        f.route.input().onTick(new TickEvent(EventState.PRE, TickEvent.Type.OUT, 3));
        assertEquals("FAILED", f.b().homeRecoveryState());
        assertFalse(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
    }

    @Test public void stillPublishedNextSegmentCannotBeReportedReady() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin());
        // The ordinary cancellation operation is tested above. A still-published next segment is not quiescence.
        set(f.route.pathing(), "current", null); set(f.route.pathing(), "next", f.route.executor());
        f.b().advanceHomeRecovery(101); f.b().advanceHomeRecovery(102);
        assertEquals("QUIESCING", f.b().homeRecoveryState());
        assertFalse(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
    }

    @Test public void actualPreTickThenInputThenSurvivalReachesReadyWithoutTransientOwnerFailure() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin());
        set(get(f.b(), "homeRecovery"), "deadline", Long.MAX_VALUE);
        set(f.route.pathing(), "safeToCancel", true);
        set(f.survival, "eatRestoreSlot", 2); set(f.survival, "eatPlayer", f.player);
        set(f.survival, "eatItem", Items.APPLE); f.mc.options.keyUse.setDown(true);
        for (int tick = 0; tick < 3; tick++) {
            // Real preTick clears the selected owner while Builder.onTick executes, then republishes it.
            f.route.control().preTick();
            assertSame(f.b(), f.route.control().mostRecentInControl().orElseThrow());
            assertNotEquals("FAILED", f.b().homeRecoveryState());
            // Existing deterministic rhythm seam avoids global humanization settings; no attack is requested.
            f.route.base().mining.requestDeterministicBreak();
            f.route.input().onTick(new TickEvent(EventState.PRE, TickEvent.Type.IN, tick));
            f.survival.onTick(new TickEvent(EventState.PRE, TickEvent.Type.IN, tick));
        }
        assertEquals("READY", f.b().homeRecoveryState());
        assertTrue(f.player.input instanceof princeps.utils.PlayerMovementInput);
        assertFalse(f.mc.options.keyUse.isDown());
        assertFalse(f.route.base().mining.isBreakingBlock());
    }

    @Test public void lateUnsafeLegAcknowledgementCannotBecomeReadyOrRearmTheNextAttempt() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin());
        BlockPos late = TARGET.above(4);
        f.ledger.record(late, Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), false, true, 1);
        f.ready(); assertEquals("QUIESCING", f.b().homeRecoveryState());
        // Use the real ledger ACK seam (its enclosing callback also performs runtime chat logging).
        assertTrue(f.ledger.serverChanged(late, Blocks.DIRT.defaultBlockState()));
        f.actions.arm(TARGET.asLong(), Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        f.b().observeScaffoldServerChange(TARGET, Blocks.AIR.defaultBlockState());
        assertEquals(1L, get(f.b(), "confirmedProgressRevision"));
        f.ready(); assertTrue(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
        f.failed(); assertFalse(f.begin());
    }

    @Test public void confirmedRelocationReleasesOnlyDepartureRouteParks() throws Exception {
        F f = fixture();
        BetterBlockPos unreachable = new BetterBlockPos(TARGET.above(6));
        BetterBlockPos missingFace = new BetterBlockPos(TARGET.above(7));
        BetterBlockPos noStance = new BetterBlockPos(TARGET.above(8));
        Class<?> reason = Class.forName("princeps.process.BuilderProcess$ParkReason");
        Method park = BuilderProcess.class.getDeclaredMethod("parkCell", BetterBlockPos.class, reason); park.setAccessible(true);
        for (var entry : java.util.Map.of(unreachable, "UNREACHABLE", missingFace, "NO_FACE", noStance, "NO_STANCE").entrySet()) {
            Object value = java.util.Arrays.stream(reason.getEnumConstants()).filter(x -> x.toString().equals(entry.getValue())).findFirst().orElseThrow();
            park.invoke(f.b(), entry.getKey(), value);
        }
        var memos = (it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap)get(f.b(), "cellVerdicts");
        memos.put(unreachable.asLong(), 1); memos.put(missingFace.asLong(), 2); memos.put(noStance.asLong(), 3);
        f.enable(); assertTrue(f.begin()); f.ready();
        set(f.player, "position", new Vec3(14.5, 12, 24.5));
        assertTrue(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
        var parks = (java.util.Map<?, ?>)get(f.b(), "parkedCells");
        assertFalse(parks.containsKey(unreachable.asLong())); assertFalse(memos.containsKey(unreachable.asLong()));
        assertTrue(parks.containsKey(missingFace.asLong())); assertEquals(2, memos.get(missingFace.asLong()));
        assertTrue(parks.containsKey(noStance.asLong())); assertEquals(3, memos.get(noStance.asLong()));
        assertTrue(((java.util.Set<?>)get(f.b(), "incorrectPositions")).contains(unreachable));
        assertTrue(((java.util.Set<?>)get(f.b(), "activeCells")).contains(unreachable));
        assertSame(f.ledger, get(f.b(), "navigationScaffolds"));
    }

    @Test public void timeoutAndDisableRetainPausedBuildAndCannotResetAttempt() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin()); long id = f.b().homeRecoveryRequestId();
        f.b().advanceHomeRecovery(10_000_000_101L);
        assertEquals("FAILED", f.b().homeRecoveryState());
        f.b().setHomeRecoveryEnabled(false); f.b().setHomeRecoveryEnabled(true); f.b().resume();
        assertEquals(id, f.b().homeRecoveryRequestId()); assertEquals("FAILED", f.b().homeRecoveryState());
        assertTrue(f.b().isPaused()); assertSame(f.route.base().full, get(f.b(), "realSchematic"));
    }

    @Test public void exhaustedUnplacedCleanupCanSuspendWithoutDiscardingLedgerOrAttemptedOwners() throws Exception {
        F f = fixture(); f.enable(); Object episode = f.episode("BLOCKED", false);
        set(episode, "blockedReason", "finite helper candidates exhausted");
        set(f.b(), "homeFailedRoute", null);
        var owners = new HashSet<Long>(); owners.add(TARGET.asLong()); set(f.b(), "cleanupEscapeAttemptedOwners", owners);
        assertTrue(f.begin()); assertNull(get(f.b(), "cleanupEscape"));
        assertSame(owners, get(f.b(), "cleanupEscapeAttemptedOwners")); assertTrue(f.ledger.owns(TARGET.above(3), Blocks.DIRT.defaultBlockState()));
    }

    @Test public void activePlacedOrAcknowledgementBoundCleanupNeverSuspends() throws Exception {
        for (String stage : List.of("CANDIDATE", "PLACE", "WAIT_PLACE", "DOWNWARD")) {
            F f = fixture(); f.enable(); f.episode(stage, false); assertFalse(f.begin());
        }
        F f = fixture(); f.enable(); Object episode = f.episode("BLOCKED", true);
        set(episode, "blockedReason", "finite helper candidates exhausted"); assertFalse(f.begin());
        set(f.b(), "cleanupEscape", null);
        set(f.b(), "cleanupEscapeDebt", new BuilderCleanupDebt(f.world, episode, TARGET, Blocks.DIRT.defaultBlockState(), 0, 0, 0));
        assertFalse(f.begin());
    }

    @Test public void pendingScaffoldAckAndLastPlacementPreventRequest() throws Exception {
        F f = fixture(); f.enable(); set(f.b(), "lastPlacedCell", TARGET); assertFalse(f.begin());
        set(f.b(), "lastPlacedCell", null);
        f.ledger.record(TARGET.above(4), Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), false, true, 1);
        assertFalse(f.begin());
    }

    @Test public void resumeRebasesPermissionsPreservesBuildAndOnlyServerActionRearmsBudget() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin()); f.ready(); long first = f.b().homeRecoveryRequestId();
        assertFalse(f.b().resumeAfterHomeRecovery(first + 1));
        Object model = get(f.b(), "realSchematic"), origin = get(f.b(), "origin"), work = get(f.b(), "incorrectPositions");
        Object debt = new BuilderCleanupDebt(f.world, new Object(), TARGET.above(5), Blocks.DIRT.defaultBlockState(), 0, 0, 0);
        ((BuilderCleanupDebt) debt).interactionObserved(f.world, ((BuilderCleanupDebt) debt).episode, 1, 1);
        ((BuilderCleanupDebt) debt).serverChanged(f.world, TARGET.above(5), Blocks.DIRT.defaultBlockState(), 1, 1);
        ((BuilderCleanupDebt) debt).serverChanged(f.world, TARGET.above(5), Blocks.AIR.defaultBlockState(), 2, 2);
        set(f.b(), "cleanupEscapeDebt", debt);
        assertTrue(f.b().resumeAfterHomeRecovery(first));
        assertSame(model, get(f.b(), "realSchematic")); assertSame(origin, get(f.b(), "origin")); assertSame(work, get(f.b(), "incorrectPositions"));
        assertSame(debt, get(f.b(), "cleanupEscapeDebt")); assertSame(f.ledger, get(f.b(), "navigationScaffolds"));
        assertTrue(f.ledger.owns(TARGET.above(3), Blocks.DIRT.defaultBlockState()));
        assertEquals(new BetterBlockPos(TARGET), get(f.b(), "electedCell")); assertNull(get(f.b(), "electedGoal"));
        assertEquals(0L, get(f.b(), "confirmedProgressRevision")); f.failed(); assertFalse(f.begin());
        f.b().observeScaffoldServerChange(TARGET, Blocks.AIR.defaultBlockState()); assertFalse(f.begin());
        f.actions.arm(TARGET.asLong(), Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        f.b().observeScaffoldServerChange(TARGET, Blocks.AIR.defaultBlockState()); f.failed();
        assertEquals(1L, get(f.b(), "confirmedProgressRevision")); assertTrue(f.begin());
        assertTrue(f.b().homeRecoveryRequestId() > first);
    }

    @Test public void movementUseCollisionAndUnloadPreventReadyOrResume() throws Exception {
        F f = fixture(); f.enable(); assertTrue(f.begin()); set(f.route.pathing(), "safeToCancel", true);
        f.world.collision = true; f.b().advanceHomeRecovery(101); assertEquals("QUIESCING", f.b().homeRecoveryState());
        f.world.collision = false; set(f.player, "deltaMovement", new Vec3(.05, 0, 0));
        f.b().advanceHomeRecovery(102); assertEquals("QUIESCING", f.b().homeRecoveryState());
        set(f.player, "deltaMovement", new Vec3(0, -.0784, 0));
        f.player.using = true; f.b().advanceHomeRecovery(103); assertEquals("QUIESCING", f.b().homeRecoveryState());
        f.player.using = false; f.ready();
        f.mc.options.keyAttack.setDown(true); assertFalse(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
        f.b().advanceHomeRecovery(104); assertEquals("QUIESCING", f.b().homeRecoveryState());
        f.mc.options.keyAttack.setDown(false); f.ready(); f.world.unloaded = true;
        assertFalse(f.b().resumeAfterHomeRecovery(f.b().homeRecoveryRequestId()));
    }

    private static F fixture() throws Exception {
        var base = BuilderModelRestorationTest.fixture(Blocks.GLASS.defaultBlockState(), false);
        var route = BuilderModelRouteProtectionTest.route(base);
        BuilderProcess b = base.owner; Princeps bot = (Princeps)get(b,"princeps");
        HomeWorld world = allocate(HomeWorld.class); world.states = new HashMap<>(); base.world = world;
        HomePlayer player = allocate(HomePlayer.class); set(player,"position",new Vec3(11.5,12,21.5)); set(player,"deltaMovement",Vec3.ZERO);
        set(player,"inventory",new Inventory(player,new EntityEquipment()));
        Minecraft mc = allocate(Minecraft.class); Options options = allocate(Options.class);
        set(options,"keyUse",allocate(KeyMapping.class)); set(options,"keyAttack",allocate(KeyMapping.class));
        set(mc,"options",options); set(mc,"mouseHandler",allocate(MouseHandler.class));
        IPlayerContext ctx=(IPlayerContext)Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),new Class<?>[]{IPlayerContext.class},
                (proxy,m,args)->switch(m.getName()){
                    case "world"->world;case "player"->player;case "minecraft"->mc;case "objectMouseOver"->null;
                    case "playerFeet"->new BetterBlockPos(player.position().x,player.position().y,player.position().z);
                    default->throw new AssertionError("unexpected context operation "+m.getName());});
        set(b,"ctx",ctx); set(route.pathing(),"ctx",ctx);set(route.pathing(),"princeps",bot);set(bot,"playerContext",ctx);set(bot,"builderProcess",b);
        set(route.pathing(),"context",base.cost);
        set(route.pathing(),"pathPlanLock",new Object());set(route.pathing(),"pathCalcLock",new Object());set(route.pathing(),"toDispatch",new LinkedBlockingQueue<>());
        set(route.input(),"ctx",ctx);set(route.input(),"inputForceStateMap",new HashMap<Input,Boolean>());
        set(player,"input",allocate(net.minecraft.client.player.ClientInput.class));
        set(route.control(),"princeps",bot);set(route.control(),"processes",new HashSet<>(List.of(b)));
        set(route.control(),"active",new ArrayList<>(List.of(b)));
        BlockPlaceHelper place=allocate(BlockPlaceHelper.class);set(place,"ctx",ctx);set(place,"princeps",bot);set(route.input(),"blockPlaceHelper",place);
        SurvivalBehavior survival=allocate(SurvivalBehavior.class);set(survival,"ctx",ctx);set(survival,"princeps",bot);
        set(survival,"eatRestoreSlot",-1);set(survival,"borrowedSourceIndex",-1);set(survival,"repairToolInventoryIndex",-1);set(bot,"survivalBehavior",survival);
        set(bot,"inventoryBehavior",allocate(InventoryBehavior.class));
        set(bot,"elytraProcess",Proxy.newProxyInstance(princeps.api.process.IElytraProcess.class.getClassLoader(),
                new Class<?>[]{princeps.api.process.IElytraProcess.class},(proxy,m,args)->{
                    if(m.getName().equals("isActive")) return false;
                    throw new AssertionError("unexpected Elytra operation "+m.getName());}));
        set(b,"ending",princeps.api.process.IBuilderProcess.Ending.RUNNING);
        set(b,"abortPending",princeps.api.process.IBuilderProcess.Ending.RUNNING);
        set(b,"progressWatch",new BuilderProgressWatch(5_000_000_000L,60_000_000_000L,2));
        ConfirmedBuildActions<net.minecraft.world.level.block.state.BlockState> actions=new ConfirmedBuildActions<>((a,c)->a.equals(c));set(b,"progressActions",actions);
        set(b,"homeRecoveryAttemptRevision",Long.MIN_VALUE);set(b,"placementTargetLock",new PlacementTargetLock<>(10,10,10));
        set(b,"laneProbe",new PathProbe("home-test"));set(b,"navigationVisitedCells",new LongOpenHashSet());set(b,"orientedGoalCache",new HashMap<>());
        set(b,"parkedCells",new LinkedHashMap<>());set(b,"activeCells",new HashSet<>());
        set(b,"cellVerdicts",new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap());
        BuilderScaffoldLedger ledger=new BuilderScaffoldLedger();ledger.record(TARGET.above(3),Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState(),false,true,0);
        ledger.serverChanged(TARGET.above(3),Blocks.DIRT.defaultBlockState());set(b,"navigationScaffolds",ledger);
        set(b,"incorrectPositions",new HashSet<>(List.of(new BetterBlockPos(TARGET))));
        F f=new F(route,world,player,mc,survival,place,ledger,actions);f.failed();return f;
    }

    private record F(BuilderModelRouteProtectionTest.Route route, HomeWorld world, HomePlayer player, Minecraft mc,
                     SurvivalBehavior survival, BlockPlaceHelper place, BuilderScaffoldLedger ledger,
                     ConfirmedBuildActions<net.minecraft.world.level.block.state.BlockState> actions){
        BuilderProcess b(){return route.base().owner;}
        void enable(){b().setHomeRecoveryEnabled(true);}
        boolean begin(){return b().beginHomeRecovery(BuilderProgressWatch.Phase.WORK,100);}
        void failed()throws Exception{GoalBlock goal=new GoalBlock(TARGET);set(route.pathing(),"goal",goal);set(b(),"electedCell",new BetterBlockPos(TARGET));set(b(),"electedGoal",goal);
            set(b(),"homeFailedRoute",goal);set(b(),"homeFailedTarget",new BetterBlockPos(TARGET));set(b(),"homeFailedRevision",get(b(),"confirmedProgressRevision"));}
        void ready()throws Exception{set(route.pathing(),"safeToCancel",true);b().advanceHomeRecovery(101);b().advanceHomeRecovery(102);}
        Object episode(String stage,boolean placed)throws Exception{Class<?> t=Class.forName("princeps.process.BuilderProcess$CleanupEscape");Object e=allocate(t);
            Class<?> st=Class.forName("princeps.process.BuilderProcess$CleanupStage");Object value=java.util.Arrays.stream(st.getEnumConstants()).filter(x->x.toString().equals(stage)).findFirst().orElseThrow();
            set(e,"this$0",b());set(e,"stage",value);set(e,"placed",placed);set(e,"probe",new PathProbe("home-exhaustion-test"));set(b(),"cleanupEscape",e);return e;}
    }
    private static class HomeWorld extends BuilderModelRestorationTest.World {
        boolean collision;
        @Override public boolean noCollision(Entity entity){return !collision;}
    }
    private static class HomePlayer extends LocalPlayer {
        boolean using;
        HomePlayer(){super(null,null,null,null,null,null,false,null);}
        @Override public boolean onGround(){return true;}
        @Override public boolean isUsingItem(){return using;}
        @Override public boolean isInWater(){return false;}
        @Override public boolean isInLava(){return false;}
        @Override public boolean isFallFlying(){return false;}
    }
}
