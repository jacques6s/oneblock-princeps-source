/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.movement.IMovement;
import princeps.api.utils.BetterBlockPos;
import princeps.pathing.calc.PathProbe;
import princeps.pathing.movement.movements.MovementDownward;
import princeps.utils.BlockStateInterface;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.*;

/** Request/server ordering and actual BSI overlays; deliberately not a headless Minecraft physics certificate. */
public class BuilderCleanupContractTest {
    private static final BlockPos HELPER = new BlockPos(11, 23, 17);
    private static final Object WORLD = new Object(), EPISODE = new Object();
    private static Unsafe allocator;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
    }

    private static BuilderCleanupDebt debt() {
        return new BuilderCleanupDebt(WORLD, EPISODE, HELPER, Blocks.DIRT.defaultBlockState(), 7, 40, 100);
    }

    @Test public void airBeforeOwnershipNeverDischargesARequest() {
        var debt = debt();
        debt.serverChanged(WORLD, HELPER, Blocks.AIR.defaultBlockState(), 41, 101);
        assertEquals(BuilderCleanupDebt.State.REQUESTED, debt.state());
        assertFalse(debt.discharged()); assertTrue(debt.mayRequest());
    }

    @Test public void localSuccessAloneDoesNotPermitEnteringOrMiningTheHelper() {
        var debt = debt(); debt.interactionObserved(WORLD, EPISODE, 8, 101);
        assertEquals(BuilderCleanupDebt.State.REQUESTED, debt.state());
        assertFalse(debt.mayRequest());
        assertFalse(debt.mayMine(WORLD, HELPER, Blocks.DIRT.defaultBlockState()));
        debt.serverChanged(WORLD, HELPER, Blocks.AIR.defaultBlockState(), 41, 102);
        assertFalse(debt.discharged());
    }

    @Test public void currentServerAckCanArriveBeforeTheClientReceiptIsPolled() {
        var debt = debt();
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 41, 101);
        assertEquals(BuilderCleanupDebt.State.REQUESTED, debt.state());
        assertFalse(debt.mayMine(WORLD, HELPER, Blocks.DIRT.defaultBlockState()));
        debt.interactionObserved(WORLD, EPISODE, 8, 102);
        assertEquals(BuilderCleanupDebt.State.OWNED, debt.state());
        assertTrue(debt.mayMine(WORLD, HELPER, Blocks.DIRT.defaultBlockState()));
    }

    @Test public void currentClientReceiptThenServerAckAlsoGrantsOnlyTheExactBlock() {
        var debt = debt(); debt.interactionObserved(WORLD, EPISODE, 8, 101);
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 41, 102);
        assertTrue(debt.mayMine(WORLD, HELPER, Blocks.DIRT.defaultBlockState()));
        assertFalse(debt.mayMine(new Object(), HELPER, Blocks.DIRT.defaultBlockState()));
        assertFalse(debt.mayMine(WORLD, HELPER.above(), Blocks.DIRT.defaultBlockState()));
        assertFalse(debt.mayMine(WORLD, HELPER, Blocks.STONE.defaultBlockState()));
    }

    @Test public void preRequestUpdatesOrOlderSequencesCannotCombineWithAFutureInteraction() {
        var debt = debt();
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 40, 101);
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 41, 99);
        debt.interactionObserved(WORLD, EPISODE, 8, 102);
        assertEquals(BuilderCleanupDebt.State.REQUESTED, debt.state());
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 42, 103);
        debt.serverChanged(WORLD, HELPER, Blocks.AIR.defaultBlockState(), 41, 104);
        assertEquals(BuilderCleanupDebt.State.OWNED, debt.state());
    }

    @Test public void wrongEpisodeWorldOrPreRequestClientReceiptCannotCompleteOwnership() {
        var debt = debt(); debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 41, 101);
        debt.interactionObserved(WORLD, new Object(), 8, 102);
        debt.interactionObserved(new Object(), EPISODE, 8, 102);
        debt.interactionObserved(WORLD, EPISODE, 7, 102);
        debt.interactionObserved(WORLD, EPISODE, 8, 99);
        assertEquals(BuilderCleanupDebt.State.REQUESTED, debt.state());
    }

    @Test public void aLaterAirUpdateDischargesOnlyPreviouslyOwnedDebt() {
        var debt = debt(); debt.interactionObserved(WORLD, EPISODE, 8, 101);
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 41, 102);
        debt.serverChanged(WORLD, HELPER, Blocks.AIR.defaultBlockState(), 42, 103);
        assertTrue(debt.discharged());
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 43, 104);
        assertFalse(debt.mayMine(WORLD, HELPER, Blocks.DIRT.defaultBlockState()));
    }

    @Test public void aForeignReplacementCannotBeReacquiredOrReportedAsOurRemoval() {
        var debt = debt(); debt.interactionObserved(WORLD, EPISODE, 8, 101);
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 41, 102);
        debt.serverChanged(WORLD, HELPER, Blocks.STONE.defaultBlockState(), 42, 103);
        debt.serverChanged(WORLD, HELPER, Blocks.AIR.defaultBlockState(), 43, 104);
        debt.serverChanged(WORLD, HELPER, Blocks.DIRT.defaultBlockState(), 44, 105);
        assertEquals(BuilderCleanupDebt.State.REPLACED, debt.state());
        assertFalse(debt.discharged()); assertFalse(debt.mayMine(WORLD, HELPER, Blocks.DIRT.defaultBlockState()));
    }

    @Test public void unrelatedPositionAndWorldPacketsCannotAcknowledgeTheHelper() {
        var debt = debt(); debt.interactionObserved(WORLD, EPISODE, 8, 101);
        debt.serverChanged(new Object(), HELPER, Blocks.DIRT.defaultBlockState(), 41, 102);
        debt.serverChanged(WORLD, HELPER.above(), Blocks.DIRT.defaultBlockState(), 42, 103);
        assertEquals(BuilderCleanupDebt.State.REQUESTED, debt.state());
    }

    @Test public void actualPresentAndRemovedBsiReadsUseSeparatePersistentOverlays() throws Exception {
        BlockStateInterface present = (BlockStateInterface) allocator.allocateInstance(BlockStateInterface.class);
        BlockStateInterface removed = (BlockStateInterface) allocator.allocateInstance(BlockStateInterface.class);
        present.nimmBlockAn(HELPER, Blocks.DIRT.defaultBlockState());
        removed.nimmBlockAn(HELPER, Blocks.AIR.defaultBlockState());
        // The real get0 implementation, including the AIR overlay branch; no get0 override bypasses the hypothesis.
        assertTrue(present.get0(HELPER).is(Blocks.DIRT));
        assertTrue(removed.get0(HELPER).isAir());
        for (int i = 0; i < 100; i++) {
            assertTrue(removed.get0(HELPER.getX(), HELPER.getY(), HELPER.getZ()).isAir());
            assertTrue(present.get0(HELPER.getX(), HELPER.getY(), HELPER.getZ()).is(Blocks.DIRT));
        }
    }

    @Test public void strictCleanupContextsNeverLicenseAnotherPlacementOrAnUnrelatedBreak() throws Exception {
        var context = (BuilderProcess.CleanupEscapeContext) allocator.allocateInstance(BuilderProcess.CleanupEscapeContext.class);
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF,
                context.costOfPlacingAt(11, 23, 17, Blocks.AIR.defaultBlockState()), 0.0);
        assertFalse(context.placementLicence().permitsPlacement(HELPER));
        assertFalse(context.mayUsePathingBarriers());
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF,
                context.breakCostMultiplierAt(11, 23, 17, Blocks.DIRT.defaultBlockState()), 0.0);
        field(context, "mineOnly", HELPER); field(context, "mineState", Blocks.DIRT.defaultBlockState());
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF,
                context.breakCostMultiplierAt(12, 23, 17, Blocks.DIRT.defaultBlockState()), 0.0);
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF,
                context.breakCostMultiplierAt(11, 23, 17, Blocks.STONE.defaultBlockState()), 0.0);
        field(context, "allowBreakAnyway", List.of());
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF,
                context.breakCostMultiplierAt(11, 23, 17, Blocks.DIRT.defaultBlockState()), 0.0);
    }

    @Test public void theRealDownwardCostRejectsADisabledDownwardPermissionBeforeAnyHypothesis() throws Exception {
        var context = (BuilderProcess.CleanupEscapeContext) allocator.allocateInstance(BuilderProcess.CleanupEscapeContext.class);
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF,
                MovementDownward.cost(context, HELPER.getX(), HELPER.getY() + 1, HELPER.getZ()), 0.0);
    }

    @Test public void aFourBlockFallCannotMasqueradeAsADryThreeBlockLeg() throws Exception {
        BetterBlockPos start = new BetterBlockPos(11, 24, 17), end = start.below(4);
        Goal goal = new GoalBlock(end);
        assertFalse(BuilderProcess.cleanupCompleteDryPath(answer(PathProbe.Outcome.COMPLETE, List.of(start, end), goal), start, goal, 3));
    }

    @Test public void distinctOneAndThreeBlockLegsRespectTheOriginalThreeBlockLimit() throws Exception {
        BetterBlockPos top = new BetterBlockPos(11, 24, 17), lower = top.below(), floor = lower.east().below(3);
        Goal down = new GoalBlock(lower), landing = new GoalBlock(floor);
        assertTrue(BuilderProcess.cleanupCompleteDryPath(answer(PathProbe.Outcome.COMPLETE, List.of(top, lower), down), top, down, 3));
        assertTrue(BuilderProcess.cleanupCompleteDryPath(answer(PathProbe.Outcome.COMPLETE, List.of(lower, floor), landing), lower, landing, 3));
    }

    @Test public void partialErrorWrongStartAndWrongEndpointAreNeverCompleteProofs() throws Exception {
        BetterBlockPos start = new BetterBlockPos(11, 24, 17), end = start.east(); Goal goal = new GoalBlock(end);
        for (PathProbe.Outcome outcome : new PathProbe.Outcome[]{PathProbe.Outcome.NONE, PathProbe.Outcome.PARTIAL, PathProbe.Outcome.ERROR}) {
            assertFalse(BuilderProcess.cleanupCompleteDryPath(answer(outcome, List.of(start, end), goal), start, goal, 3));
        }
        var complete = answer(PathProbe.Outcome.COMPLETE, List.of(start, end), goal);
        assertFalse(BuilderProcess.cleanupCompleteDryPath(complete, start.west(), goal, 3));
        assertFalse(BuilderProcess.cleanupCompleteDryPath(complete, start, new GoalBlock(end.east()), 3));
    }

    private static PathProbe.Result answer(PathProbe.Outcome outcome, List<BetterBlockPos> positions, Goal goal) throws Exception {
        IPath path = new IPath() {
            public List<IMovement> movements() { throw new AssertionError("a proof path must never execute in this contract test"); }
            public List<BetterBlockPos> positions() { return positions; }
            public Goal getGoal() { return goal; }
            public int getNumNodesConsidered() { return 1; }
        };
        Constructor<PathProbe.Result> constructor = PathProbe.Result.class.getDeclaredConstructor(
                PathProbe.Outcome.class, IPath.class, long.class, boolean.class);
        constructor.setAccessible(true); return constructor.newInstance(outcome, path, 1L, false);
    }

    private static void field(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
