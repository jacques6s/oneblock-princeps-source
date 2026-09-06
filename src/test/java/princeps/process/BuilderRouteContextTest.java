/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import princeps.Princeps;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.input.Input;
import princeps.behavior.PathingBehavior;
import princeps.pathing.movement.CalculationContext;
import princeps.utils.InputOverrideHandler;
import princeps.utils.PathingCommandContext;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Actual builder hold command -> actual PathingBehavior context selection; no global provider or settings shadow. */
public class BuilderRouteContextTest {
    private static final BetterBlockPos FEET = new BetterBlockPos(7, 20, 11);
    private static Unsafe allocator;
    private static Method hold;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
        hold = BuilderProcess.class.getDeclaredMethod("holdStillWithoutTearingUpTheRoute", PathingCommand.class);
        hold.setAccessible(true);
    }

    @Test public void anIdleHoldKeepsTheActualLaneAContextInThePathingBehavior() throws Exception {
        assertHoldPreservesLane(BuilderProcess.Lane.A_NO_PLACING, false);
    }

    @Test public void anIdleHoldKeepsTheActualLaneBContextAndItsAirOnlyLicence() throws Exception {
        assertHoldPreservesLane(BuilderProcess.Lane.B_HELPERS_ALLOWED, true);
    }

    private void assertHoldPreservesLane(BuilderProcess.Lane lane, boolean mayPlaceAir) throws Exception {
        Fixture f = fixture(lane);
        PathingCommand command = f.hold();
        assertEquals(PathingCommandType.REVALIDATE_GOAL_AND_PATH, command.commandType);
        assertSame(f.goal, command.goal);
        // The goal is already at these synthetic feet so this REAL method selects the context, then returns
        // without starting an A* worker. If the hold loses its context, the generic constructor trips the sentinel.
        try {
            assertFalse(f.pathing.secretInternalSetGoalAndPath(command));
        } catch (NewGenericContext unexpected) {
            fail("A continuation constructed a generic context instead of retaining the builder lane");
        }
        assertSame(f.context, f.pathing.secretInternalGetCalculationContext());
        assertEquals(mayPlaceAir, f.pathing.secretInternalGetCalculationContext()
                .placementLicence().permitsPlacement(FEET));
        assertFalse(f.pathing.secretInternalGetCalculationContext()
                .placementLicence().permitsPlacement(FEET.east()));
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF,
                f.pathing.secretInternalGetCalculationContext().breakCostMultiplierAt(
                        FEET.x, FEET.y, FEET.z, Blocks.STONE_BRICKS.defaultBlockState()), 0.0D);
    }

    @Test public void aRealClickStillCancelsRatherThanKeepingTheBodyMoving() throws Exception {
        Fixture f = fixture(BuilderProcess.Lane.B_HELPERS_ALLOWED);
        f.inputs.put(Input.CLICK_LEFT, true);
        assertSame(f.cancel, f.hold());
        f.inputs.clear(); f.inputs.put(Input.CLICK_RIGHT, true);
        assertSame(f.cancel, f.hold());
    }

    @Test public void explicitPauseProgressStopAndCompletedWorkStillCancel() throws Exception {
        Fixture f = fixture(BuilderProcess.Lane.A_NO_PLACING);
        set(f.builder, "paused", true); assertSame(f.cancel, f.hold());
        set(f.builder, "paused", false); set(f.builder, "progressHold", true);
        assertSame(f.cancel, f.hold());
        set(f.builder, "progressHold", false); set(f.builder, "incorrectPositions", new HashSet<>());
        assertSame(f.cancel, f.hold());
    }

    @Test public void aGenuineNewGenericCommandStillConstructsItsOwnContext() throws Exception {
        Fixture f = fixture(BuilderProcess.Lane.B_HELPERS_ALLOWED);
        PathingCommand nextProcess = new PathingCommand(new GoalBlock(30, 20, 11),
                PathingCommandType.SET_GOAL_AND_PATH);
        try {
            f.pathing.secretInternalSetGoalAndPath(nextProcess);
            fail("A genuine generic request must not inherit the builder context");
        } catch (NewGenericContext expected) {
            // The ordinary constructor really reached its player dependency. Stop before reading global settings
            // or building a live world; this proves the real generic branch stayed distinct from continuation.
        }
    }

    @Test public void anExplicitNewContextStillReplacesThePreviousLane() throws Exception {
        Fixture f = fixture(BuilderProcess.Lane.B_HELPERS_ALLOWED);
        CalculationContext next = allocate(CalculationContext.class);
        assertFalse(f.pathing.secretInternalSetGoalAndPath(new PathingCommandContext(f.goal,
                PathingCommandType.SET_GOAL_AND_PATH, next)));
        assertSame(next, f.pathing.secretInternalGetCalculationContext());
    }

    @Test public void anExistingGenericApproachAlsoKeepsItsOwnRulesDuringAHold() throws Exception {
        Fixture f = fixture(BuilderProcess.Lane.B_HELPERS_ALLOWED);
        CalculationContext approach = allocate(CalculationContext.class);
        set(f.pathing, "context", approach);
        assertFalse(f.pathing.secretInternalSetGoalAndPath(f.hold()));
        assertSame(approach, f.pathing.secretInternalGetCalculationContext());
        assertTrue(approach.placementLicence().permitsPlacement(FEET.east()));
    }

    @Test public void noInFlightGoalMeansThereIsNoRouteToPreserve() throws Exception {
        Fixture f = fixture(BuilderProcess.Lane.A_NO_PLACING);
        set(f.pathing, "goal", null);
        assertSame(f.cancel, f.hold());
    }

    @Test public void aHoldWithoutAnExistingSnapshotRetainsTheOrdinaryInitializationPath() throws Exception {
        Fixture f = fixture(BuilderProcess.Lane.A_NO_PLACING);
        set(f.pathing, "context", null);
        assertFalse(f.hold() instanceof PathingCommandContext);
        try {
            f.pathing.secretInternalSetGoalAndPath(f.hold());
            fail("No existing snapshot means normal context initialization is still required");
        } catch (NewGenericContext expected) { }
    }

    @Test public void diagnosisIdleAndAirborneContinuationsAllUseTheTestedContextPreservingCommand() throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = BuilderProcess.class.getResourceAsStream("BuilderProcess.class")) {
            new ClassReader(stream).accept(node, 0);
        }
        Set<String> callers = new HashSet<>();
        int calls = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && call.name.equals("continueCurrentRoute")) {
                    callers.add(method.name); calls++;
                }
            }
        }
        assertEquals(Set.of("observeBuilderProgress", "holdStillWithoutTearingUpTheRoute", "onTick",
                "placementRecoveryCommand"), callers);
        assertEquals(4, calls);
    }

    private static Fixture fixture(BuilderProcess.Lane lane) throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        Princeps owner = allocate(Princeps.class);
        PathingBehavior pathing = allocate(PathingBehavior.class);
        InputOverrideHandler input = allocate(InputOverrideHandler.class);
        Map<Input, Boolean> inputs = new HashMap<>();
        set(input, "inputForceStateMap", inputs);
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("playerFeet")) return FEET;
                    if (method.getName().equals("player")) throw new NewGenericContext();
                    throw new AssertionError("Unexpected live dependency " + method.getName());
                });
        set(owner, "playerContext", player); set(owner, "pathingBehavior", pathing);
        set(owner, "inputOverrideHandler", input);
        set(builder, "princeps", owner);
        set(builder, "incorrectPositions", new HashSet<>(List.of(FEET.below())));
        set(pathing, "princeps", owner); set(pathing, "ctx", player);
        GoalBlock goal = new GoalBlock(FEET);
        set(pathing, "goal", goal);
        var context = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(context, "this$0", builder); set(context, "lane", lane);
        set(context, "allowBreak", false); set(context, "allowBreakAnyway", List.of());
        set(context, "originX", FEET.x); set(context, "originY", FEET.y); set(context, "originZ", FEET.z);
        set(context, "schematic", new AbstractSchematic(2, 1, 1) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> stock) {
                return (x == 0 ? Blocks.AIR : Blocks.STONE_BRICKS).defaultBlockState();
            }
        });
        set(pathing, "context", context);
        return new Fixture(builder, pathing, context, goal, inputs,
                new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL));
    }

    private record Fixture(BuilderProcess builder, PathingBehavior pathing,
                           BuilderProcess.BuilderCalculationContext context, GoalBlock goal,
                           Map<Input, Boolean> inputs, PathingCommand cancel) {
        PathingCommand hold() throws Exception { return (PathingCommand) hold.invoke(builder, cancel); }
    }
    private static final class NewGenericContext extends RuntimeException { }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(allocator.allocateInstance(type)); }
    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
