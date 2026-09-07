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
import princeps.api.pathing.goals.GoalComposite;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.schematic.FillSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.pathing.calc.PathProbe;
import princeps.process.builder.BuilderProgressWatch;
import princeps.process.builder.PlacementTargetLock;
import princeps.utils.PathingCommandContext;
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
    private static final BetterBlockPos STANCE = TARGET.east();
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

    @Test public void actualPlannedRecoveryReturnConsumesFreshNegativeAInsteadOfStarvingTheLaneDriver() throws Exception {
        Fixture f = recoveryFixture(BuilderProcess.Lane.B_HELPERS_ALLOWED);
        f.answer(PathProbe.Outcome.NONE, true);
        PathingCommandContext command = f.recover();
        assertTrue("the actual early recovery return must consume the completed A question",
                f.question == get(f.builder, "laneAProof"));
        assertEquals(BuilderProcess.Lane.B_HELPERS_ALLOWED, get(command.desiredCalcContext, "lane"));
        assertTrue(new GoalBlock(STANCE).equals(command.goal));
        assertEquals(1, get(get(f.builder, "placementTargetLock"), "routeTicks"));
    }

    @Test public void actualPlannedRecoveryReturnConsumesCompleteBAndRetainsTheAcceptedLaneAfterOwnLanding() throws Exception {
        Fixture f = recoveryFixture(BuilderProcess.Lane.B_HELPERS_ALLOWED);
        set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
        set(f.builder, "laneProbeLane", BuilderProcess.Lane.B_HELPERS_ALLOWED);
        f.answer(PathProbe.Outcome.COMPLETE, false);
        PathingCommandContext command = f.recover();
        assertEquals(true, get(f.builder, "laneBAnswered"));
        set(f.builder, "confirmedProgressRevision", 1L);
        f.world.states.put(TARGET.west(2).asLong(), Blocks.DIRT.defaultBlockState());
        command = f.recover();
        assertTrue(f.question == get(f.builder, "laneAProof"));
        assertEquals(BuilderProcess.Lane.B_HELPERS_ALLOWED, get(command.desiredCalcContext, "lane"));
    }

    @Test public void actualPlannedRecoveryReturnRejectsProofForADifferentStance() throws Exception {
        Fixture f = recoveryFixture(BuilderProcess.Lane.A_NO_PLACING);
        set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
        planRecovery(f, STANCE.east());
        PathingCommandContext command = f.recover();
        assertTrue(get(f.builder, "laneAProof") == null);
        assertTrue(get(f.builder, "laneEscalatedCell") == null);
        assertTrue(new GoalBlock(STANCE.east()).equals(command.goal));
        assertEquals(BuilderProcess.Lane.A_NO_PLACING, get(command.desiredCalcContext, "lane"));
    }

    @Test public void actualPlannedRecoveryReturnRejectsStaleWorldNegativeAndPreservesUnknown() throws Exception {
        for (boolean changedWorld : new boolean[]{false, true}) {
            Fixture f = recoveryFixture(BuilderProcess.Lane.A_NO_PLACING);
            f.answer(changedWorld ? PathProbe.Outcome.NONE : PathProbe.Outcome.ERROR, changedWorld);
            if (changedWorld) set(f.builder, "confirmedProgressRevision", 1L);
            PathingCommandContext command = f.recover();
            assertTrue("the recovery route must service or discard the pending completion", get(f.builder, "laneQuestion") == null);
            assertTrue(get(f.builder, "laneAProof") == null);
            assertTrue(get(f.builder, "laneEscalatedCell") == null);
            assertEquals(BuilderProcess.Lane.A_NO_PLACING, get(command.desiredCalcContext, "lane"));
            assertTrue(((Map<?, ?>) get(f.builder, "parkedCells")).isEmpty());
        }
    }

    @Test public void negativeACompleteBOwnHelperLandingAndExpandedCleanupStillReachTheActualRecoveryReturn() throws Exception {
        Fixture f = recoveryFixture(BuilderProcess.Lane.B_HELPERS_ALLOWED);
        Goal initialRoute = new BuilderProcess.JankyGoalComposite(f.goal, new GoalComposite());
        f.answer(PathProbe.Outcome.NONE, true);
        finishRetainingWorkProof(f, initialRoute);
        assertTrue(f.question == get(f.builder, "laneAProof"));

        // The real driver starts its worker in the client. Deliver that worker's completion at the same request
        // boundary here; do not replace the driver, target lock, goal equality or scaffold ledger with test logic.
        set(f.builder, "laneQuestion", f.question); set(f.builder, "laneProbeLane", BuilderProcess.Lane.B_HELPERS_ALLOWED);
        f.answer(PathProbe.Outcome.COMPLETE, false);
        finishRetainingWorkProof(f, initialRoute);
        assertEquals(true, get(f.builder, "laneBAnswered"));

        BetterBlockPos helper = TARGET.west(2).below();
        BuilderScaffoldLedger ledger = (BuilderScaffoldLedger) get(f.builder, "navigationScaffolds");
        assertTrue(ledger.record(helper, Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), false, true, 1));
        f.world.states.put(helper.asLong(), Blocks.DIRT.defaultBlockState());
        assertTrue(ledger.serverChanged(helper, Blocks.DIRT.defaultBlockState()));
        set(f.builder, "confirmedProgressRevision", 1L);
        Goal expandedRoute = new BuilderProcess.JankyGoalComposite(f.goal,
                new GoalComposite(new BuilderProcess.GoalBreak(helper)));
        assertFalse(initialRoute.equals(expandedRoute));
        PathingCommandContext routed = finishRetainingWorkProof(f, expandedRoute);
        assertTrue(expandedRoute == routed.goal);
        assertTrue(f.question == get(f.builder, "laneAProof"));
        assertEquals(BuilderProcess.Lane.B_HELPERS_ALLOWED, get(routed.desiredCalcContext, "lane"));
        PathingCommandContext recovery = f.recover();
        assertTrue(new GoalBlock(STANCE).equals(recovery.goal));
        assertTrue(f.question == get(f.builder, "laneAProof"));
        assertEquals(BuilderProcess.Lane.B_HELPERS_ALLOWED, get(recovery.desiredCalcContext, "lane"));
        assertEquals(1, get(get(f.builder, "placementTargetLock"), "routeTicks"));
    }

    @Test public void sameFallbackCannotTransferAnAcceptedLaneToDifferentWorkOrModel() throws Exception {
        for (boolean changeModel : new boolean[]{false, true}) {
            Fixture f = recoveryFixture(BuilderProcess.Lane.A_NO_PLACING);
            set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
            if (changeModel) set(f.builder, "schematic", new FillSchematic(8, 8, 8, Blocks.STONE.defaultBlockState()));
            else set(f.builder, "electedCell", TARGET.north());
            f.recover();
            assertTrue(get(f.builder, "laneAProof") == null);
            assertTrue(get(f.builder, "laneEscalatedCell") == null);
        }
    }

    @Test public void actualPlatformFinisherCancelsWhenConsumedNegativeBWithdrawsTheOffer() throws Exception {
        Fixture f = platformFixture();
        set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
        set(f.builder, "laneProbeLane", BuilderProcess.Lane.B_HELPERS_ALLOWED);
        assertTrue(f.current(true));
        f.answer(PathProbe.Outcome.NONE, true);
        PathingCommand command = f.finishCommand(new QuietGoal(STANCE), f.goal);
        assertEquals(PathingCommandType.CANCEL_AND_SET_GOAL, command.commandType);
        assertNull("a withdrawn platform offer cannot escape with ordinary routing rules", command.goal);
        assertNull(get(f.builder, "platformTraverseApproach"));
        assertNull(get(f.builder, "electedCell")); assertNull(get(f.builder, "laneAProof"));
        assertTrue(((Map<?, ?>) get(f.builder, "parkedCells")).containsKey(TARGET.asLong()));
        assertEquals(1L, get(f.builder, "cellsParked"));
    }

    @Test public void actualPlatformFinisherKeepsTheMatchingOfferAndItsExactAContext() throws Exception {
        Fixture f = platformFixture();
        Object approach = get(f.builder, "platformTraverseApproach");
        set(f.builder, "laneAProof", f.question); set(f.builder, "laneEscalatedCell", TARGET);
        set(f.builder, "laneProbeLane", BuilderProcess.Lane.B_HELPERS_ALLOWED);
        f.answer(PathProbe.Outcome.COMPLETE, false);
        PathingCommandContext command = f.finish(new QuietGoal(STANCE), f.goal);
        assertEquals(true, get(f.builder, "laneBAnswered"));
        assertSame(f.goal, command.goal); assertSame(f.context, command.desiredCalcContext);
        assertSame(approach, get(command.desiredCalcContext, "platformApproach"));
        assertEquals(BuilderProcess.Lane.A_NO_PLACING, get(command.desiredCalcContext, "lane"));
        assertEquals(false, get(f.builder, "scaffoldPassAllowed"));
        assertTrue(((Map<?, ?>) get(f.builder, "parkedCells")).isEmpty());
    }

    @Test public void clearingOrReplacingThePlatformSnapshotInvalidatesPendingAndAcceptedLaneEvidence() throws Exception {
        Fixture f = platformFixture();
        Object original = get(f.builder, "platformTraverseApproach");
        assertTrue(f.current(true)); assertTrue(f.current(false));
        set(f.builder, "platformTraverseApproach", null);
        assertFalse(f.current(true)); assertFalse(f.current(false));
        set(f.builder, "platformTraverseApproach", original);
        assertTrue(f.current(true)); assertTrue(f.current(false));
        Object replacement = suppliedPlatformApproach(f);
        assertNotSame(original, replacement);
        assertTrue("equal coordinates and goal still describe a different offer", original.equals(replacement));
        set(f.builder, "platformTraverseApproach", replacement);
        assertFalse(f.current(true)); assertFalse(f.current(false));
    }

    /** Supplies a retained approach/context snapshot, not a successful platform election or a path search.
     * BuilderPlatformTraverseTest covers actual election. These tests execute the real Lane result consumer,
     * question-identity check, target teardown and final route dispatch without creating another API provider. */
    private static Fixture platformFixture() throws Exception {
        Class<?> goalType = java.util.Arrays.stream(BuilderProcess.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("PlatformTraverseGoal")).findFirst().orElseThrow();
        Constructor<?> goalConstructor = goalType.getDeclaredConstructor(BlockPos.class); goalConstructor.setAccessible(true);
        Goal goal = (Goal) goalConstructor.newInstance(TARGET.above());
        Fixture base = fixture(Blocks.AIR.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), goal);
        base.feet[0] = TARGET.west().above();
        base.world.states.put(base.feet[0].below().asLong(), Blocks.SMOOTH_STONE.defaultBlockState());
        set(base.context, "lane", BuilderProcess.Lane.A_NO_PLACING);
        Object approach = suppliedPlatformApproach(base);
        set(base.builder, "platformTraverseApproach", approach); set(base.context, "platformApproach", approach);
        Constructor<?> questionConstructor = QUESTION.getDeclaredConstructors()[0]; questionConstructor.setAccessible(true);
        Object question = questionConstructor.newInstance(TARGET, goal, goal, base.feet[0], base.context,
                Blocks.AIR.defaultBlockState(), false, 0L, null, null);
        set(base.builder, "laneQuestion", question);
        return new Fixture(base.builder, base.context, base.world, base.feet, goal, base.probe, question);
    }

    private static Object suppliedPlatformApproach(Fixture f) throws Exception {
        Class<?> type = java.util.Arrays.stream(BuilderProcess.class.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals("PlatformTraverseApproach")).findFirst().orElseThrow();
        Constructor<?> constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        return constructor.newInstance(f.world, f.builder, get(f.builder, "schematic"), BlockPos.ZERO,
                f.feet[0], TARGET, Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), f.goal);
    }

    private static Fixture recoveryFixture(BuilderProcess.Lane lane) throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), new GoalBlock(STANCE));
        set(f.context, "lane", lane);
        set(f.builder, "committedPlaceTarget", TARGET);
        set(f.builder, "progressWatch", new BuilderProgressWatch(1L, 2L, 0.1));
        PlacementTargetLock<Object> lock = (PlacementTargetLock<Object>) get(f.builder, "placementTargetLock");
        assertTrue(lock.acquire(TARGET.asLong(), new Object())); lock.startRecovery();
        planRecovery(f, STANCE);
        return f;
    }

    private static PathingCommandContext finishRetainingWorkProof(Fixture f, Goal routeGoal) throws Exception {
        try {
            return f.finish(routeGoal, f.goal);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            // A wrongly revoked B proof tries to construct a fresh live A context. Assert the causal contract
            // before allowing that headless constructor boundary to obscure a failure; unrelated exceptions rethrow.
            assertTrue("the selected work proof must survive a route-only fallback change",
                    f.question == get(f.builder, "laneAProof"));
            throw failure;
        }
    }

    private static void planRecovery(Fixture f, BetterBlockPos stance) throws Exception {
        PlacementTargetLock<Object> lock = (PlacementTargetLock<Object>) get(f.builder, "placementTargetLock");
        set(f.builder, "plannedPlacementStance", stance);
        long dx = f.feet[0].x - stance.x, dy = f.feet[0].y - stance.y, dz = f.feet[0].z - stance.z;
        lock.planStance(stance.asLong(), dx * dx + dy * dy + dz * dz);
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
        PathingCommandContext recover() throws Exception {
            return (PathingCommandContext) invoke(builder, "placementRecoveryForPlannedStance",
                    new Class<?>[]{BuilderProcess.BuilderCalculationContext.class, BetterBlockPos.class, boolean.class},
                    context, feet[0], false);
        }
        PathingCommandContext finish(Goal routeGoal, Goal workGoal) throws Exception {
            return (PathingCommandContext) finishCommand(routeGoal, workGoal);
        }
        PathingCommand finishCommand(Goal routeGoal, Goal workGoal) throws Exception {
            return (PathingCommand) invoke(builder, "finishBuilderRoute",
                    new Class<?>[]{Goal.class, Goal.class, PathingCommandType.class, BuilderProcess.BuilderCalculationContext.class},
                    routeGoal, workGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, context);
        }
    }

    private static Fixture fixture() throws Exception {
        return fixture(Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState(), new QuietGoal(TARGET));
    }
    private static Fixture fixture(BlockState current, BlockState desired, Goal goal) throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        set(builder, "princeps", allocate(princeps.Princeps.class));
        TestLevel world = allocate(TestLevel.class); world.states = new HashMap<>();
        world.states.put(TARGET.asLong(), current);
        BetterBlockPos[] feet = {TARGET.above(3)};
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "world" -> world;
                    case "playerFeet" -> feet[0];
                    default -> throw new AssertionError("unexpected live dependency " + method.getName());
                });
        set(builder, "ctx", player);
        var model = new FillSchematic(8, 8, 8, desired);
        List<BlockState> inventory = Collections.nCopies(9, desired);
        set(builder, "schematic", model); set(builder, "origin", BlockPos.ZERO); set(builder, "approxPlaceable", inventory);
        set(builder, "electedCell", TARGET);
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
        Object question = constructor.newInstance(TARGET, goal, goal, feet[0], context, current, false, 0L, null, null);
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
