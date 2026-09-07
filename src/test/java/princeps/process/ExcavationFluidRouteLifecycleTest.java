/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.movement.IMovement;
import princeps.api.utils.BetterBlockPos;
import princeps.behavior.PathingBehavior;
import princeps.pathing.path.PathExecutor;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.*;

/** Uses real executor completion and reproduces current removal; movement physics are outside this headless test. */
public class ExcavationFluidRouteLifecycleTest {
    private static final BetterBlockPos START = new BetterBlockPos(93, -54, 96);
    private static final BetterBlockPos DESTINATION = START.north();
    private static final ExcavationFluidPlugs.Hazard HAZARD = new ExcavationFluidPlugs.Hazard(
            new BlockPos(91, -53, 93), List.of(new BlockPos(91, -53, 92), new BlockPos(90, -53, 93)));

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void completedSingleStepRemovedBeforeNextBuilderTickStillRenewsTheWait() throws Exception {
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Route route = route(START, DESTINATION, true);
        assertFalse(plugs.waitExpired(HAZARD, 100));
        plugs.observeRouteProgress(route.executor(), START);
        assertFalse(plugs.waitExpired(HAZARD, 299));

        completeAndRemove(route);
        // Behavior removes current in that same path tick; the builder never samples this executor at index one.
        plugs.observeRouteProgress(route.behavior().getCurrent(), DESTINATION);
        assertFalse("the completed licensed step must survive current=null", plugs.waitExpired(HAZARD, 300));
        for (int tick = 301; tick < 500; tick++) {
            plugs.observeRouteProgress(null, DESTINATION);
            assertFalse(plugs.waitExpired(HAZARD, tick));
        }
        assertTrue("completion grants one bounded interval", plugs.waitExpired(HAZARD, 500));
    }

    @Test
    public void failedAndExternallyCanceledExecutorsCannotBorrowCompletionCredit() throws Exception {
        for (boolean failed : new boolean[] {false, true}) {
            ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
            Route route = route(START, DESTINATION, true);
            assertFalse(plugs.waitExpired(HAZARD, 100));
            plugs.observeRouteProgress(route.executor(), START);
            if (failed) {
                install(route.executor(), "pathPosition", route.executor().getPath().length() + 3);
                install(route.executor(), "failed", true);
                assertTrue(route.executor().onTick());
                install(route.behavior(), "current", null);
                assertTrue("finished() also reports canceled executors", route.executor().finished());
            } else {
                install(route.behavior(), "current", null);
            }
            plugs.observeRouteProgress(route.behavior().getCurrent(), DESTINATION);
            assertTrue(plugs.waitExpired(HAZARD, 300));
        }
    }

    @Test
    public void replacementArrivalCannotTurnAnUnfinishedOldExecutorIntoProgress() throws Exception {
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Route canceled = route(START, DESTINATION, true);
        Route replacement = route(DESTINATION, DESTINATION.west(), true);
        assertFalse(plugs.waitExpired(HAZARD, 100));
        plugs.observeRouteProgress(canceled.executor(), START);
        plugs.observeRouteProgress(replacement.executor(), DESTINATION);
        assertTrue(plugs.waitExpired(HAZARD, 300));
    }

    @Test
    public void completionMustMatchTheObservedStartDestinationAndCardinalLicence() throws Exception {
        for (int rejected = 0; rejected < 5; rejected++) {
            ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
            BetterBlockPos destination = rejected == 3 ? DESTINATION.west()
                    : rejected == 4 ? START.above() : DESTINATION;
            Route route = route(START, destination, rejected != 2);
            assertFalse(plugs.waitExpired(HAZARD, 100));
            plugs.observeRouteProgress(route.executor(), rejected == 0 ? START.east() : START);
            completeAndRemove(route);
            plugs.observeRouteProgress(null, rejected == 1 ? destination.east() : destination);
            assertTrue("mismatched completion " + rejected, plugs.waitExpired(HAZARD, 300));
        }
    }

    @Test
    public void unobservedCompletionAndReplayedCompletedExecutorReceiveNoCredit() throws Exception {
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Route route = route(START, DESTINATION, true);
        assertFalse(plugs.waitExpired(HAZARD, 100));
        completeAndRemove(route);
        plugs.observeRouteProgress(route.executor(), DESTINATION);
        plugs.observeRouteProgress(null, DESTINATION);
        assertTrue(plugs.waitExpired(HAZARD, 300));
    }

    @Test
    public void aPauseOrBorrowedHandsDiscardPendingCompletionWithoutResettingTheDeadline() throws Exception {
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Route route = route(START, DESTINATION, true);
        assertFalse(plugs.waitExpired(HAZARD, 100));
        plugs.observeRouteProgress(route.executor(), START);
        plugs.suspendRouteProgress();
        completeAndRemove(route);
        plugs.observeRouteProgress(null, DESTINATION);
        assertTrue(plugs.waitExpired(HAZARD, 300));
    }

    @Test
    public void returningOverCompletedCellsAfterAPauseDoesNotRenewTheEpisode() throws Exception {
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Route outbound = route(START, DESTINATION, true);
        assertFalse(plugs.waitExpired(HAZARD, 100));
        plugs.observeRouteProgress(outbound.executor(), START);
        completeAndRemove(outbound);
        plugs.observeRouteProgress(null, DESTINATION);
        assertFalse(plugs.waitExpired(HAZARD, 200));
        plugs.suspendRouteProgress();
        Route back = route(DESTINATION, START, true);
        plugs.observeRouteProgress(back.executor(), DESTINATION);
        completeAndRemove(back);
        plugs.observeRouteProgress(null, START);
        assertFalse(plugs.waitExpired(HAZARD, 399));
        assertTrue(plugs.waitExpired(HAZARD, 400));
    }

    private record Route(PathingBehavior behavior, PathExecutor executor) { }

    private static Route route(BetterBlockPos start, BetterBlockPos destination, boolean licensed) throws Exception {
        PathingBehavior behavior = headlessBehavior();
        IPath path = new IPath() {
            @Override public List<IMovement> movements() { return List.of(); }
            @Override public List<BetterBlockPos> positions() { return List.of(start, destination); }
            @Override public Goal getGoal() { return new GoalBlock(destination); }
            @Override public int getNumNodesConsidered() { return 2; }
        };
        WadeLicence licence = licensed ? WadeLicence.where(packed -> {
            BlockPos pos = BlockPos.of(packed);
            return pos.equals(start) || pos.equals(start.above())
                    || pos.equals(destination) || pos.equals(destination.above());
        }, "test cardinal excavation route") : WadeLicence.NONE;
        PathExecutor executor = new PathExecutor(behavior, path, PlacementLicence.UNRESTRICTED, licence);
        install(behavior, "current", executor);
        return new Route(behavior, executor);
    }

    private static void completeAndRemove(Route route) throws Exception {
        // Seed the SUCCESS boundary immediately before PathExecutor's real recursive terminal tick. This avoids
        // simulating Minecraft movement while exercising the index-one -> finished -> current-null lifecycle.
        install(route.executor(), "pathPosition", route.executor().getPath().length() - 1);
        assertTrue(route.executor().onTick());
        assertTrue(route.executor().finished());
        assertFalse(route.executor().failed());
        // PathingBehavior.tickPath retires the executor immediately after onTick returns finished. Reproduce
        // that removal without invoking the unrelated settings/chat initialization in a headless JVM.
        install(route.behavior(), "current", null);
        assertNull(route.behavior().getCurrent());
    }

    private static void install(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static PathingBehavior headlessBehavior() throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        PathingBehavior behavior = (PathingBehavior) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, PathingBehavior.class);
        return behavior;
    }
}
