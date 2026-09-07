/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;
import princeps.api.process.IBuilderProcess.Ending;
import princeps.api.schematic.AbstractSchematic;
import princeps.process.builder.ConfirmedBuildActions;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import static org.junit.Assert.*;
import static princeps.process.BuilderModelRestorationTest.*;

/** Actual BCC and fullRecalc work admission, with the full model above the current layer masked out. */
public class BuilderLayerCleanupTest {
    private static final BlockPos HELPER = ORIGIN.offset(-1, 0, 1);
    @BeforeClass public static void bootstrap() throws Exception { BuilderModelRestorationTest.bootstrap(); }

    @Test public void queuedLayerHelperBecomesBreakWorkWithoutOpeningTheNextLayer() throws Exception {
        Fixture f = layerFixture();
        BuilderScaffoldLedger ledger = ledger(f);
        set(f.owner, "scaffoldCleanupTargets", new HashSet<>(Set.of(HELPER.asLong())));
        set(f.owner, "layerCleanupActive", true);
        f.world.states.put(HELPER.asLong(), Blocks.COBBLESTONE.defaultBlockState());
        assertTrue(ledger.record(HELPER, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, true, 1));
        assertTrue(ledger.serverChanged(HELPER, Blocks.COBBLESTONE.defaultBlockState()));
        recalc(f);
        assertTrue("the queued helper must become ordinary BREAK work before climbing", work(f).contains(new BetterBlockPos(HELPER)));
        assertFalse("the next layer remains masked", work(f).contains(new BetterBlockPos(TARGET)));
        assertSame(f.active, get(f.owner, "schematic"));
        assertEquals(false, get(f.owner, "scaffoldCleanupActive"));
        assertEquals(0, f.world.writes);
    }

    @Test public void noCleanupRequestDoesNotInventWorkOutsideTheModel() throws Exception {
        Fixture f = layerFixture();
        f.world.states.put(HELPER.asLong(), Blocks.COBBLESTONE.defaultBlockState());
        recalc(f);
        assertFalse(work(f).contains(new BetterBlockPos(HELPER)));
        assertFalse(work(f).contains(new BetterBlockPos(TARGET)));
    }

    @Test public void actualLayerTransitionWaitsForBreakWorkAndServerAckBeforeIncrementing() throws Exception {
        Fixture f = layerFixture(); own(f, HELPER);
        own(f, HELPER.above(2));
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        assertEquals(1, get(f.owner, "layer"));
        assertEquals(Set.of(HELPER.asLong()), targets(f));
        recalc(f);
        assertTrue(work(f).contains(new BetterBlockPos(HELPER)));
        assertFalse(work(f).contains(new BetterBlockPos(HELPER.above(2))));
        assertFalse(work(f).contains(new BetterBlockPos(TARGET)));
        assertEquals(Blocks.AIR.defaultBlockState(), desired(f, HELPER));
        assertEquals(1.0, f.cost(HELPER), 0.0);
        f.hit = HELPER; f.tickMining(); assertEquals(1, f.damage.get());
        f.world.states.remove(HELPER.asLong()); // Local-only result is not a receipt.
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        assertEquals(1, get(f.owner, "layer"));
        assertTrue(f.owner.scaffoldCleanupAwaitingServer());
        assertTrue((boolean) invoke(f.owner, "homeRecoveryHasAcknowledgement"));
        ledger(f).serverChanged(HELPER, Blocks.AIR.defaultBlockState());
        assertTrue(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        assertEquals(2, get(f.owner, "layer"));
        assertTrue(targets(f).isEmpty());
        assertEquals(false, get(f.owner, "layerCleanupActive"));
        assertTrue(ledger(f).contains(HELPER.above(2)));
        assertSame(f.active, get(f.owner, "schematic"));
        assertEquals(0, f.world.writes);
    }

    @Test public void directWaterServerResultReleasesOwnedHelperWithoutRequiringAnAirFrame() throws Exception {
        Fixture f = layerFixture(); own(f, HELPER);
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        f.world.states.put(HELPER.asLong(), Blocks.WATER.defaultBlockState());
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        assertTrue(f.owner.scaffoldCleanupAwaitingServer());
        assertNull(desired(f, HELPER));
        ledger(f).serverChanged(HELPER, Blocks.WATER.defaultBlockState());
        assertTrue(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        recalc(f);
        assertFalse(work(f).contains(new BetterBlockPos(HELPER)));
        assertFalse(ledger(f).contains(HELPER));
        assertEquals(0, f.damage.get());
    }

    @Test public void completionReauditsTheLoadedLayerAfterCleanup() throws Exception {
        Fixture f = layerFixture(); own(f, HELPER);
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        f.world.states.remove(HELPER.asLong());
        ledger(f).serverChanged(HELPER, Blocks.AIR.defaultBlockState());
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var counted = new java.util.HashMap<Long, BlockState>(f.world.states) {
            @Override public BlockState getOrDefault(Object key, BlockState defaultValue) {
                reads.incrementAndGet(); return super.getOrDefault(key, defaultValue);
            }
        };
        f.world.states = counted;
        var audits = new java.util.ArrayList<String>();
        assertTrue(f.owner.advanceLayerIfClean(f.cost, false, audits::add));
        assertTrue("S10 rereads all nine layer cells after the ACK", reads.get() >= 9);
        assertEquals(1, audits.size());
        assertTrue(audits.getFirst().contains("9 correct"));
        assertEquals(2, get(f.owner, "layer"));
    }

    @Test public void topDownBoundaryOnlyQueuesHelpersAtOrAboveItsFinishedBand() throws Exception {
        Fixture f = layerFixture();
        set(f.owner, "bandMinYLocal", 1); set(f.owner, "bandMaxYLocal", 1);
        own(f, HELPER); own(f, HELPER.above(2));
        assertTrue(f.owner.prepareLayerCleanup(f.cost, true));
        assertEquals(Set.of(HELPER.above(2).asLong()), targets(f));
        assertTrue(ledger(f).contains(HELPER));
    }

    @Test public void futureExternalSupportIsNamedAndCannotBecomeCleanupWork() throws Exception {
        Fixture f = layerFixture();
        BlockPos support = ORIGIN.west();
        BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        f.full = new AbstractSchematic(3, 3, 3) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> stock) {
                return x == 0 && y == 0 && z == 0 ? torch : Blocks.AIR.defaultBlockState();
            }
        };
        set(f.owner, "realSchematic", f.full);
        set(f.cost, "supportDependencies", new BuilderSupportDependencies(f.full, ORIGIN, List.of(), f.blocks, f.world));
        own(f, support);
        assertTrue(f.owner.prepareLayerCleanup(f.cost, false));
        assertEquals(Ending.LAYER_UNBUILDABLE, get(f.owner, "abortPending"));
        assertTrue(get(f.owner, "abortPendingDetail").toString().contains("REQUIRED_SUPPORT"));
        assertTrue(targets(f).isEmpty());
        assertEquals(princeps.api.pathing.movement.ActionCosts.COST_INF, f.cost(support), 0.0);
        f.hit = support; f.tickMining(); assertEquals(0, f.damage.get());
    }

    @Test public void liveCorrectModelMaterialCannotBeRemovedEvenIfAnOldLedgerEntryNamesIt() throws Exception {
        Fixture f = layerFixture();
        set(f.owner, "bandMaxYLocal", 1);
        ledger(f).record(TARGET, Blocks.AIR.defaultBlockState(), Blocks.GLASS.defaultBlockState(), false, true, 1);
        ledger(f).serverChanged(TARGET, Blocks.GLASS.defaultBlockState());
        assertTrue(f.owner.prepareLayerCleanup(f.cost, false));
        assertEquals(Ending.LAYER_UNBUILDABLE, get(f.owner, "abortPending"));
        assertTrue(get(f.owner, "abortPendingDetail").toString().contains("MODEL_BLOCK"));
        assertTrue(targets(f).isEmpty());
        f.tickMining(); assertEquals(0, f.damage.get());
    }

    @Test public void unfinishedHigherLayerClickAnchorReportsDependencyWithoutCyclingOrAdvancing() throws Exception {
        Fixture f = layerFixture(); own(f, HELPER);
        anchors(f).put(HELPER.asLong(), TARGET.asLong());
        f.world.states.remove(TARGET.asLong());
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        assertEquals(Ending.LAYER_UNBUILDABLE, get(f.owner, "abortPending"));
        assertTrue(get(f.owner, "abortPendingDetail").toString().contains(TARGET.toShortString()));
        assertEquals(1, get(f.owner, "layer"));
        assertTrue(targets(f).isEmpty());
        assertTrue(ledger(f).contains(HELPER));
    }

    @Test public void alreadyCompletedAnchorTargetHiddenByLayerMaskDoesNotCreateACycle() throws Exception {
        Fixture f = layerFixture(); own(f, HELPER);
        anchors(f).put(HELPER.asLong(), TARGET.asLong());
        assertTrue(f.owner.prepareLayerCleanup(f.cost, false));
        assertTrue(anchors(f).isEmpty());
        assertEquals(Set.of(HELPER.asLong()), targets(f));
        assertEquals(Ending.RUNNING, get(f.owner, "abortPending"));
    }

    @Test public void currentPlatformTraversalRetainsItsNoMiningContractAtTheBoundary() throws Exception {
        Fixture f = layerFixture(); own(f, HELPER);
        Object platform = allocate(Class.forName("princeps.process.BuilderProcess$PlatformTraverseApproach"));
        set(f.owner, "platformTraverseApproach", platform);
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        assertTrue(targets(f).isEmpty());
        assertEquals(1, get(f.owner, "layer"));
        f.hit = HELPER; f.tickMining(); assertEquals(0, f.damage.get());
    }

    @Test public void foreignReplacementLosesOwnershipAndNeverBecomesAQueuedBreak() throws Exception {
        Fixture f = layerFixture(); own(f, HELPER);
        assertFalse(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        f.world.states.put(HELPER.asLong(), Blocks.STONE.defaultBlockState());
        ledger(f).serverChanged(HELPER, Blocks.STONE.defaultBlockState());
        assertTrue(f.owner.advanceLayerIfClean(f.cost, false, message -> { }));
        recalc(f); assertFalse(work(f).contains(new BetterBlockPos(HELPER)));
        assertNull(desired(f, HELPER));
    }

    private static Fixture layerFixture() throws Exception {
        Fixture f = fixture(Blocks.GLASS.defaultBlockState(), true);
        set(f.owner, "navigationScaffolds", new BuilderScaffoldLedger());
        set(f.owner, "scaffoldCleanupTargets", new HashSet<>());
        set(f.owner, "observedCompleted", new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        set(f.owner, "parkedCells", new java.util.LinkedHashMap<>());
        set(f.owner, "temporarySupportTargets", new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap());
        set(f.owner, "scaffoldFailed", new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        set(f.owner, "scaffoldProbeCache", new java.util.HashMap<>());
        set(f.owner, "progressActions", new ConfirmedBuildActions<BlockState>(Object::equals));
        set(f.owner, "ending", Ending.RUNNING); set(f.owner, "abortPending", Ending.RUNNING);
        set(f.owner, "layer", 1); set(f.owner, "bandMinYLocal", 0); set(f.owner, "bandMaxYLocal", 0);
        return f;
    }

    private static void own(Fixture f, BlockPos p) throws Exception {
        f.world.states.put(p.asLong(), Blocks.COBBLESTONE.defaultBlockState());
        assertTrue(ledger(f).record(p, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, true, 1));
        assertTrue(ledger(f).serverChanged(p, Blocks.COBBLESTONE.defaultBlockState()));
    }

    private static BuilderScaffoldLedger ledger(Fixture f) throws Exception { return (BuilderScaffoldLedger) get(f.owner, "navigationScaffolds"); }
    @SuppressWarnings("unchecked") private static Set<Long> targets(Fixture f) throws Exception { return (Set<Long>) get(f.owner, "scaffoldCleanupTargets"); }
    private static it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap anchors(Fixture f) throws Exception {
        return (it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap) get(f.owner, "temporarySupportTargets");
    }
    private static Object invoke(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target);
    }
    private static BlockState desired(Fixture f, BlockPos p) throws Exception {
        Method m = f.cost.getClass().getDeclaredMethod("getSchematic", int.class, int.class, int.class, BlockState.class);
        m.setAccessible(true); return (BlockState) m.invoke(f.cost, p.getX(), p.getY(), p.getZ(), f.world.getBlockState(p));
    }

    private static void recalc(Fixture f) throws Exception {
        Method m = BuilderProcess.class.getDeclaredMethod("fullRecalc", BuilderProcess.BuilderCalculationContext.class);
        m.setAccessible(true); m.invoke(f.owner, f.cost);
    }

    @SuppressWarnings("unchecked") private static Set<BetterBlockPos> work(Fixture f) throws Exception {
        return (Set<BetterBlockPos>) get(f.owner, "incorrectPositions");
    }
}
