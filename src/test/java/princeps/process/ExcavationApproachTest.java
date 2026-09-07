/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.IPrinceps;
import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.movement.IMovement;
import princeps.api.utils.BetterBlockPos;
import princeps.behavior.PathingBehavior;
import princeps.pathing.movement.movements.MovementTraverse;
import princeps.pathing.path.PathExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.*;

/** Uses actual MovementTraverse required cells and actual PathExecutor token carriage, without a game loop. */
public class ExcavationApproachTest {
    private static final BetterBlockPos START = new BetterBlockPos(8, 24, 30);
    private static final BetterBlockPos ENTRY = START.east(3);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // CalculationContext's real class initializer creates a water-bucket stack. Headless bootstrap does not
        // load the item-component datapack; the same empty components used by the tool-policy fixtures suffice.
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(item)
                    instanceof net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item> holder
                    && !holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        }
    }

    @Test
    public void onlyCurrentMovementsFeetAndHeadCanBeAccessCuts() throws Exception {
        IPath path = path(START, START.east(), START.east(2));
        assertTrue(ExcavationApproach.requiresBreak(path, 0, START.east()));
        assertTrue(ExcavationApproach.requiresBreak(path, 0, START.east().above()));
        for (BlockPos unrelated : new BlockPos[] {START, START.east().below(), START.east().above(2),
                START.east().north(), START.east(2), START.east(2).above()}) {
            assertFalse("not a cut of the active movement: " + unrelated.asLong(),
                    ExcavationApproach.requiresBreak(path, 0, unrelated));
        }
        assertTrue(ExcavationApproach.requiresBreak(path, 1, START.east(2)));
        assertFalse("previous movement's cut must not survive advancement",
                ExcavationApproach.requiresBreak(path, 1, START.east()));
        assertFalse(ExcavationApproach.requiresBreak(path, -1, START.east()));
        assertFalse(ExcavationApproach.requiresBreak(path, 2, START.east()));
        assertFalse(ExcavationApproach.requiresBreak(null, 0, START.east()));
    }

    @Test
    public void planningAndActualExecutorMustCarryTheSameJobIdentity() throws Exception {
        ExcavationApproach approach = new ExcavationApproach();
        approach.start();
        Goal entry = new GoalBlock(ENTRY);
        Object token = approach.commit(entry, START);
        PathExecutor route = executor(path(START, START.east()), token);
        assertSame(token, route.excavationApproachToken());
        assertTrue(approach.owns(token, route.excavationApproachToken()));
        assertFalse("a manually forced or foreign command never inherits route permission", approach.owns(null, token));
        assertFalse(approach.owns(new Object(), token));
        assertFalse(approach.owns(token, new Object()));
        PathExecutor ordinaryRoute = new PathExecutor(allocate(PathingBehavior.class), path(START, START.east()));
        assertNull(ordinaryRoute.excavationApproachToken());
        assertFalse(approach.owns(token, ordinaryRoute.excavationApproachToken()));
        assertSame("changing a work candidate must not move the committed access destination", token,
                approach.commit(new GoalBlock(START.north(20)), START));
        assertSame(entry, approach.entry());
        approach.observeCommand(token);
        assertTrue(approach.owns(token, token));
        approach.observeCommand(new Object());
        assertFalse("an intervening process/command retires the previous route session", approach.owns(token, token));
        Object renewed = approach.commit(entry, START);
        assertNotSame(token, renewed);
        assertFalse(approach.owns(renewed, token));
    }

    @Test
    public void pauseResumeReplacementAndArrivalInvalidateStaleRoutes() throws Exception {
        ExcavationApproach approach = new ExcavationApproach();
        approach.start();
        Object original = approach.commit(new GoalBlock(ENTRY), START);
        approach.suspend();
        assertFalse(approach.owns(original, original));
        Object resumed = approach.commit(new GoalBlock(ENTRY), START);
        assertNotSame(original, resumed);
        assertFalse(approach.owns(resumed, original));
        assertTrue(approach.owns(resumed, resumed));
        approach.observe(ENTRY);
        assertFalse(approach.pending());
        assertFalse(approach.owns(resumed, resumed));
        assertNull("a subsequent band cannot turn into another access tunnel",
                approach.commit(new GoalBlock(ENTRY.below(3)), ENTRY));
        approach.start();
        Object replacement = approach.commit(new GoalBlock(ENTRY), START);
        assertNotSame(resumed, replacement);
        assertFalse(approach.owns(replacement, resumed));
        approach.clear();
        assertFalse(approach.owns(replacement, replacement));
        assertFalse(approach.pending());
    }

    @Test
    public void actualBuilderPauseAndResumeRevokeBeforeAnotherTick() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        ExcavationApproach approach = new ExcavationApproach();
        put(builder, "excavationApproach", approach);
        approach.start();
        Object original = approach.commit(new GoalBlock(ENTRY), START);
        builder.pause();
        assertTrue(builder.isPaused());
        assertFalse(approach.owns(original, original));
        builder.resume();
        assertFalse(builder.isPaused());
        Object resumed = approach.commit(new GoalBlock(ENTRY), START);
        assertNotSame(original, resumed);
        assertFalse(approach.owns(resumed, original));
    }

    @Test
    public void splicingCannotTransferAnApproachTokenToAForeignContinuation() throws Exception {
        Object token = new Object();
        PathExecutor route = executor(path(START, START.east()), token);
        PathExecutor foreign = executor(path(START.east(), START.east(2)), new Object());
        assertSame(route, route.trySplice(foreign));
        assertSame(token, route.excavationApproachToken());
        assertEquals(START.east(), route.getPath().getDest());
    }

    @Test
    public void ordinaryWorkingCutWithoutPriorTravelCannotBecomeALaterBandAccessTunnel() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        ExcavationApproach approach = new ExcavationApproach();
        put(builder, "excavationApproach", approach);
        put(builder, "excavating", true);
        put(builder, "origin", ENTRY);
        put(builder, "schematic", new princeps.api.schematic.FillSchematic(4, 6, 4,
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        approach.start();
        builder.noteExcavationWorkCut(START.east());
        assertTrue("outside access cuts do not complete entry", approach.pending());
        builder.noteExcavationWorkCut(ENTRY);
        assertFalse(approach.pending());
        assertNull("ordinary excavation may not enable initial travel at a later band",
                approach.commit(new GoalBlock(ENTRY.below(3)), START));
    }

    @Test
    public void harmlessBuilderHoldRetainsExactApproachContextButForeignRouteCannotBorrowIt() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        ExcavationApproach approach = new ExcavationApproach();
        approach.start();
        Object token = approach.commit(new GoalBlock(ENTRY), START);
        var context = allocate(BuilderProcess.BuilderCalculationContext.class);
        put(context, "approachToken", token);
        var engine = allocate(princeps.Princeps.class);
        var pathing = allocate(PathingBehavior.class);
        var input = allocate(princeps.utils.InputOverrideHandler.class);
        put(input, "inputForceStateMap", new java.util.HashMap<>());
        put(engine, "pathingBehavior", pathing);
        put(engine, "inputOverrideHandler", input);
        put(pathing, "goal", approach.entry());
        put(pathing, "context", context);
        put(pathing, "current", executor(path(START, START.east()), token));
        put(builder, "princeps", engine);
        put(builder, "excavating", true);
        put(builder, "excavationApproach", approach);
        put(builder, "incorrectPositions", new java.util.HashSet<>(List.of(ENTRY)));
        var hold = new princeps.api.process.PathingCommand(null,
                princeps.api.process.PathingCommandType.CANCEL_AND_SET_GOAL);
        var retained = builder.holdStillWithoutTearingUpTheRoute(hold);
        assertTrue(retained instanceof princeps.utils.PathingCommandContext);
        var retainedContext = ((princeps.utils.PathingCommandContext) retained).desiredCalcContext;
        assertSame(context, retainedContext);
        assertSame(approach.entry(), retained.goal);
        approach.observeCommand(retainedContext.excavationApproachToken());
        assertSame("a harmless hold must not create a cancel/replan cycle", token,
                approach.commit(new GoalBlock(ENTRY), START));
        put(pathing, "current", executor(path(START, START.east()), new Object()));
        assertFalse(builder.holdStillWithoutTearingUpTheRoute(hold) instanceof princeps.utils.PathingCommandContext);
        input.setInputForceState(princeps.api.utils.input.Input.CLICK_LEFT, true);
        assertSame("an actual click still cancels and holds the body", hold,
                builder.holdStillWithoutTearingUpTheRoute(hold));
    }

    static PathExecutor executor(IPath path, Object token) throws Exception {
        return new PathExecutor(allocate(PathingBehavior.class), path, PlacementLicence.NONE, WadeLicence.NONE, token);
    }

    private static IPath path(BetterBlockPos... positions) {
        IPrinceps princeps = (IPrinceps) Proxy.newProxyInstance(IPrinceps.class.getClassLoader(),
                new Class<?>[] {IPrinceps.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPlayerContext")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
        var movements = new java.util.ArrayList<IMovement>();
        for (int i = 1; i < positions.length; i++) movements.add(new MovementTraverse(princeps, positions[i - 1], positions[i]));
        return new IPath() {
            @Override public List<IMovement> movements() { return movements; }
            @Override public List<BetterBlockPos> positions() { return List.of(positions); }
            @Override public Goal getGoal() { return new GoalBlock(positions[positions.length - 1]); }
            @Override public int getNumNodesConsidered() { return positions.length; }
        };
    }

    static void put(Object instance, String name, Object value) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(instance, value);
                return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
}
