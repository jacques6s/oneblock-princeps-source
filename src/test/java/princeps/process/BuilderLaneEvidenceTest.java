/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.schematic.FillSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.pathing.calc.PathProbe;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual lane-driver decisions and request/context matching, without constructing a live engine or API provider. */
public class BuilderLaneEvidenceTest {
    private static Unsafe allocator;
    private static final BetterBlockPos TARGET = new BetterBlockPos(2, 2, 2);
    private static final Class<?> QUESTION = java.util.Arrays.stream(BuilderProcess.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("LaneQuestion")).findFirst().orElseThrow();

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
    }

    @Test public void onlyTheFreshStartMayConsumeANegativeAnswerButDrivingDoesNotRevokeAnAcceptedLane() throws Exception {
        Fixture f = fixture(); assertTrue(f.current(true));
        f.feet[0] = TARGET.east();
        assertFalse(f.current(true)); assertTrue(f.current(false));
    }

    @Test public void aGoalOrChosenTargetChangeInvalidatesTheOldQuestion() throws Exception {
        Fixture f = fixture();
        assertFalse((boolean) invoke(f.builder, "laneQuestionCurrent", new Class<?>[]{QUESTION, Goal.class, boolean.class},
                f.question, new QuietGoal(TARGET.west()), true));
        set(f.builder, "electedCell", TARGET.east()); assertFalse(f.current(true));
        set(f.builder, "electedCell", TARGET); set(f.builder, "electedGoal", new QuietGoal(TARGET.west()));
        assertFalse(f.current(false));
    }

    @Test public void newModelOriginWorldAndCleanupPhaseCannotInheritAnOldAnswer() throws Exception {
        Fixture f = fixture(); set(f.builder, "schematic", new FillSchematic(8, 8, 8, Blocks.AIR.defaultBlockState()));
        assertFalse(f.current(false));
        f = fixture(); set(f.builder, "origin", new BlockPos(1, 0, 0)); assertFalse(f.current(false));
        f = fixture(); set(f.context, "world", allocate(TestLevel.class)); assertFalse(f.current(false));
        f = fixture(); set(f.builder, "scaffoldCleanupActive", true); assertFalse(f.current(false));
    }

    @Test public void changedInventoryTargetStateAndPendingWorldProgressRequireAFreshSearch() throws Exception {
        Fixture f = fixture(); set(f.builder, "approxPlaceable", Collections.nCopies(9, Blocks.STONE.defaultBlockState()));
        assertFalse(f.current(false));
        f = fixture(); f.world.states.put(TARGET.asLong(), Blocks.STONE.defaultBlockState()); assertFalse(f.current(false));
        f = fixture(); set(f.builder, "confirmedProgressRevision", 1L);
        assertFalse(f.current(true)); assertTrue(f.current(false));
    }

    @Test public void aDifferentPredictedFacingOfTheSameInventoryItemKeepsTheQuestionAndAcceptedLane() throws Exception {
        Fixture f = fixture();
        var before = new java.util.ArrayList<>(Collections.nCopies(9, Blocks.AIR.defaultBlockState()));
        before.set(0, Blocks.PISTON.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, net.minecraft.core.Direction.NORTH));
        var after = new java.util.ArrayList<>(before);
        after.set(0, Blocks.PISTON.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, net.minecraft.core.Direction.UP));
        set(f.context, "placeable", before); set(f.builder, "approxPlaceable", after);
        set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
        assertTrue(f.current(true));
        assertEquals(BuilderProcess.Lane.B_HELPERS_ALLOWED,
                invoke(f.builder, "laneForCurrentCell", new Class<?>[]{BetterBlockPos.class}, TARGET));
        after.set(0, Blocks.AIR.defaultBlockState()); assertFalse(f.current(false));
        after.set(0, Blocks.OBSERVER.defaultBlockState()); assertFalse(f.current(false));
    }

    @Test public void actualLaneADriverTreatsErrorAndUnprovedPartialAsUnknown() throws Exception {
        for (PathProbe.Outcome outcome : new PathProbe.Outcome[]{PathProbe.Outcome.ERROR, PathProbe.Outcome.NONE,
                PathProbe.Outcome.PARTIAL}) {
            Fixture f = fixture(); f.answer(outcome, false); f.drive();
            assertTrue(get(f.builder, "laneEscalatedCell") == null); assertTrue(get(f.builder, "laneAProof") == null);
            assertTrue(((Map<?, ?>) get(f.builder, "parkedCells")).isEmpty());
            assertTrue(TARGET.equals(get(f.builder, "electedCell")));
        }
    }

    @Test public void actualLaneBErrorDoesNotParkOrReleaseTheWork() throws Exception {
        Fixture f = fixture();
        set(f.builder, "laneProbeLane", BuilderProcess.Lane.B_HELPERS_ALLOWED);
        set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
        f.answer(PathProbe.Outcome.ERROR, false); f.drive();
        assertTrue(((Map<?, ?>) get(f.builder, "parkedCells")).isEmpty());
        assertTrue(TARGET.equals(get(f.builder, "electedCell")));
    }

    @Test public void staleNegativeCompletionCannotEscalateOrPark() throws Exception {
        Fixture f = fixture(); f.answer(PathProbe.Outcome.NONE, true);
        f.feet[0] = TARGET.east(); f.drive();
        assertTrue(get(f.builder, "laneEscalatedCell") == null); assertTrue(get(f.builder, "laneAProof") == null);
        assertTrue(((Map<?, ?>) get(f.builder, "parkedCells")).isEmpty());
    }

    @Test public void helperRemovalNeedsABoundedDescentProofRatherThanRecursiveHelperPermission() throws Exception {
        Fixture f = fixture(); set(f.builder, "electedBreak", true);
        BuilderScaffoldLedger ledger = (BuilderScaffoldLedger) get(f.builder, "navigationScaffolds");
        assertTrue(ledger.record(TARGET, Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), false, true, 1));
        assertTrue(ledger.serverChanged(TARGET, Blocks.DIRT.defaultBlockState()));
        f.answer(PathProbe.Outcome.NONE, true); f.drive();
        assertTrue(f.question == get(f.builder, "laneAProof"));
        assertTrue(get(f.builder, "laneEscalatedCell") == null);
        assertEquals(BuilderProcess.Lane.A_NO_PLACING,
                invoke(f.builder, "laneForCurrentCell", new Class<?>[]{BetterBlockPos.class}, TARGET));
        assertTrue(((Map<?, ?>) get(f.builder, "parkedCells")).isEmpty());
    }

    @Test public void clearingTheActualTargetAlsoDiscardsPendingAnswersAndAcceptedProof() throws Exception {
        Fixture f = fixture(); f.answer(PathProbe.Outcome.NONE, true);
        set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
        invoke(f.builder, "clearElectedTarget", new Class<?>[]{});
        assertNull(f.probe.poll()); assertTrue(get(f.builder, "laneQuestion") == null); assertTrue(get(f.builder, "laneAProof") == null);
        assertTrue(get(f.builder, "laneEscalatedCell") == null); assertTrue(get(f.builder, "electedCell") == null);
    }

    private record Fixture(BuilderProcess builder, BuilderProcess.BuilderCalculationContext context,
                           TestLevel world, BetterBlockPos[] feet, Goal goal, PathProbe probe, Object question) {
        boolean current(boolean pending) throws Exception {
            return (boolean) invoke(builder, "laneQuestionCurrent", new Class<?>[]{QUESTION, Goal.class, boolean.class},
                    question, goal, pending);
        }
        void answer(PathProbe.Outcome outcome, boolean exhausted) throws Exception {
            Constructor<PathProbe.Result> constructor = PathProbe.Result.class.getDeclaredConstructor(
                    PathProbe.Outcome.class, princeps.api.pathing.calc.IPath.class, long.class, boolean.class);
            constructor.setAccessible(true);
            ((AtomicReference<PathProbe.Result>) get(probe, "finished")).set(constructor.newInstance(outcome, null, 1L, exhausted));
        }
        void drive() throws Exception { invoke(builder, "driveLanesInShadow", new Class<?>[]{Goal.class}, goal); }
    }

    private static Fixture fixture() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        set(builder, "princeps", allocate(princeps.Princeps.class));
        TestLevel world = allocate(TestLevel.class); world.states = new HashMap<>();
        world.states.put(TARGET.asLong(), Blocks.DIRT.defaultBlockState());
        BetterBlockPos[] feet = {TARGET.above(3)};
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "world" -> world;
                    case "playerFeet" -> feet[0];
                    default -> throw new AssertionError("unexpected live dependency " + method.getName());
                });
        set(builder, "ctx", player);
        var model = new FillSchematic(8, 8, 8, Blocks.AIR.defaultBlockState());
        List<BlockState> inventory = Collections.nCopies(9, Blocks.AIR.defaultBlockState());
        set(builder, "schematic", model); set(builder, "origin", BlockPos.ZERO); set(builder, "approxPlaceable", inventory);
        set(builder, "electedCell", TARGET); Goal goal = new QuietGoal(TARGET);
        set(builder, "electedGoal", goal); set(builder, "parkedCells", new LinkedHashMap<>());
        set(builder, "activeCells", new java.util.HashSet<>(List.of(TARGET)));
        set(builder, "placementTargetLock", new princeps.process.builder.PlacementTargetLock<>(20, 20, 60));
        set(builder, "navigationScaffolds", new BuilderScaffoldLedger()); set(builder, "laneOutcomes", new long[8]);
        PathProbe probe = new PathProbe("test"); set(builder, "laneProbe", probe);
        set(builder, "laneProbeLane", BuilderProcess.Lane.A_NO_PLACING); set(builder, "laneProbeCell", TARGET);
        var context = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(context, "this$0", builder); set(context, "world", world); set(context, "schematic", model);
        set(context, "placeable", inventory);
        Constructor<?> constructor = QUESTION.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object question = constructor.newInstance(TARGET, goal, goal, feet[0], context, Blocks.DIRT.defaultBlockState(), false, 0L, null, null);
        set(builder, "laneQuestion", question);
        return new Fixture(builder, context, world, feet, goal, probe, question);
    }
    private static final class QuietGoal extends GoalBlock {
        QuietGoal(BlockPos pos) { super(pos); }
        @Override public String toString() { return "test-goal"; } // avoids the live settings accessor only in trace formatting
    }
    private static final class TestLevel extends ClientLevel {
        Map<Long, BlockState> states;
        private TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState()); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args);
    }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(allocator.allocateInstance(type)); }
    private static Object get(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
