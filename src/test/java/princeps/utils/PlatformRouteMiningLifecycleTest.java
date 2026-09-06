/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.utils;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.*;
import princeps.Princeps;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.*;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.process.IPrincepsProcess;
import princeps.api.utils.*;
import princeps.api.utils.input.Input;
import princeps.behavior.PathingBehavior;
import princeps.pathing.movement.MovementState;
import princeps.pathing.movement.movements.MovementTraverse;
import princeps.pathing.path.PathExecutor;
import princeps.process.BuilderProcess;
import princeps.process.PlatformTraverseFixture;
import princeps.process.builder.BuilderProgressWatch;

import static org.junit.Assert.*;
import static princeps.process.PlatformTraverseFixture.*;

/** Real continuing actor -> real input predicate -> real BBH tick, with recorded controller calls. */
public class PlatformRouteMiningLifecycleTest {
    @BeforeClass public static void bootstrap() throws Exception { PlatformTraverseFixture.bootstrap(); }

    @Test public void pauseCannotReleaseMiningWhileUnsafePlatformSegmentRemainsCurrent() throws Exception {
        Fixture f = fixture();
        f.platform.builder.pause();
        assertNull(get(f.platform.builder, "platformTraverseApproach"));
        assertFalse(f.pathing.cancelSegmentIfSafe());
        assertSame(f.route, f.pathing.getCurrent());
        assertFalse("public builder entry must honour the surviving path before paused", f.platform.builder.allowsSupportRemoval(TARGET.above()));
        assertObstructedActorCannotMine(f);
    }

    @Test public void cancelledOwnerNullCannotReleaseTheStillExecutingSegment() throws Exception {
        Fixture f = fixture();
        f.platform.builder.pause();
        set(f.platform.builder, "schematic", null);
        set(f.manager, "inControlThisTick", null);
        assertFalse(f.pathing.cancelSegmentIfSafe());
        assertSame(f.route, f.pathing.getCurrent());
        assertObstructedActorCannotMine(f);
        assertNull(get(f.input, "supportMiningOwner"));
    }

    @Test public void anotherOwnerCannotLendMiningPermissionToTheOldPlatformSegment() throws Exception {
        Fixture f = fixture();
        f.platform.builder.pause();
        set(f.manager, "inControlThisTick", proxy(IPrincepsProcess.class, (name, args) -> {
            throw new AssertionError("foreign process must not be consulted: " + name);
        }));
        assertFalse(f.pathing.cancelSegmentIfSafe());
        assertObstructedActorCannotMine(f);
        assertNull(get(f.input, "supportMiningOwner"));
    }

    @Test public void absentOrOrdinaryCurrentRouteRetainsNormalNonBuilderMining() throws Exception {
        Fixture f = fixture(); f.platform.builder.pause();
        set(f.manager, "inControlThisTick", null);
        set(f.pathing, "current", null);
        assertTrue(f.input.allowsMining(TARGET.above()));
        f.breaker.requestDeterministicBreak(); f.breaker.tick(true);
        assertEquals(1, f.damage);
        set(f.route, "path", path(new GoalBlock(TARGET.above())));
        set(f.pathing, "current", f.route);
        assertTrue(f.input.allowsMining(TARGET.above()));
        f.breaker.requestDeterministicBreak(); f.breaker.tick(true);
        assertEquals(2, f.damage); assertEquals(0, f.platform.world.writes);
    }

    private static void assertObstructedActorCannotMine(Fixture f) throws Exception {
        var actor = MovementTraverse.class.getDeclaredMethod("updateBackplace", MovementState.class);
        actor.setAccessible(true);
        var movement = new MovementTraverse(f.platform.bot, FROM, TARGET.above());
        MovementState first = (MovementState) actor.invoke(movement, running());
        f.platform.rotation = first.getTarget().rotation;
        MovementState next = (MovementState) actor.invoke(movement, running());
        assertTrue("the continuing actor really requests obstruction mining", Boolean.TRUE.equals(next.getInputStates().get(Input.CLICK_LEFT)));
        assertFalse(Boolean.TRUE.equals(next.getInputStates().get(Input.CLICK_RIGHT)));
        set(f.breaker, "wasHitting", true);
        f.breaker.requestDeterministicBreak();
        f.breaker.tick(Boolean.TRUE.equals(next.getInputStates().get(Input.CLICK_LEFT)));
        assertEquals("no damage call may escape", 0, f.damage);
        assertEquals("no initial click may escape", 0, f.clicks);
        assertEquals(1, f.resets);
        assertFalse((boolean) get(f.breaker, "wasHitting"));
        assertSame(f.route, f.pathing.getCurrent());
        assertEquals(0, f.platform.world.writes);
    }

    private static Fixture fixture() throws Exception {
        Fixture f = new Fixture(); f.platform = new PlatformTraverseFixture();
        var offer = BuilderProcess.class.getDeclaredMethod("platformTraverseGoal", net.minecraft.core.BlockPos.class,
                BuilderProcess.BuilderCalculationContext.class, boolean.class);
        offer.setAccessible(true);
        Goal goal = (Goal) offer.invoke(f.platform.builder, TARGET, f.platform.cost, true);
        assertNotNull(goal);
        // A new obstruction appears after the route proof. The ray names an actual solid block, not phantom AIR.
        f.platform.world.states.put(TARGET.above().asLong(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        f.platform.player.pos = new Vec3(11.2, 21, 10.5);
        f.platform.hit = new BlockHitResult(new Vec3(11,21.5,10.5), Direction.EAST, TARGET.above(), false);
        f.route = allocate(PathExecutor.class); set(f.route, "path", path(goal));
        f.pathing = allocate(PathingBehavior.class); set(f.pathing,"current",f.route);
        // This is the executor's stored cancellation result while its destination floor is still AIR.
        // The real cancelSegmentIfSafe below must retain that exact current executor; no physics is simulated.
        assertTrue(f.platform.world.getBlockState(TARGET).isAir());
        set(f.pathing,"safeToCancel",false);
        f.manager = allocate(PathingControlManager.class); set(f.manager,"inControlThisTick",f.platform.builder);
        Princeps bot = allocate(Princeps.class);set(bot,"pathingBehavior",f.pathing);set(bot,"pathingControlManager",f.manager);
        set(f.platform.builder,"princeps",bot);
        set(f.platform.builder,"progressWatch",new BuilderProgressWatch(5,60,2));
        f.input = allocate(InputOverrideHandler.class);set(f.input,"princeps",bot);
        IPlayerController controller = proxy(IPlayerController.class, (name,args) -> switch(name) {
            case "setHittingBlock" -> null;
            case "resetBlockRemoving" -> { f.resets++; yield null; }
            case "hasBrokenBlock" -> false;
            case "onPlayerDamageBlock" -> { f.damage++; yield false; }
            case "clickBlock" -> { f.clicks++; yield false; }
            default -> throw new AssertionError("unexpected controller operation: " + name);
        });
        IPlayerContext ctx = proxy(IPlayerContext.class, (name,args) -> switch(name) {
            case "world" -> f.platform.world;
            case "player" -> f.platform.player;
            case "objectMouseOver" -> f.platform.hit;
            case "playerController" -> controller;
            default -> throw new AssertionError("unexpected break context: " + name);
        });
        f.breaker = new BlockBreakHelper(ctx, f.input::allowsMining);
        set(f.input, "blockBreakHelper", f.breaker);
        return f;
    }
    private static IPath path(Goal goal) { return proxy(IPath.class,(name,args)-> {
        if (name.equals("getGoal")) return goal;
        throw new AssertionError("unexpected path query: " + name);
    }); }
    private static MovementState running() { return new MovementState().setStatus(MovementStatus.RUNNING); }
    private static final class Fixture {
        PlatformTraverseFixture platform; PathExecutor route; PathingBehavior pathing; PathingControlManager manager;
        InputOverrideHandler input; BlockBreakHelper breaker; int damage,clicks,resets;
    }
}
