package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class ExcavationFluidPlugsTest {
    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState COBBLESTONE;
    private static BlockState WATER;
    private static BlockState LAVA;
    private static final Predicate<Fluid> VANILLA = ExcavationFluidPlugs::vanillaSourceConversion;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
        COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        LAVA = Blocks.LAVA.defaultBlockState();
    }

    @Test
    public void unguardedSourcePlugCyclesButGuardedRemovalWaitsForAFeederToBeSealed() {
        BlockPos p = new BlockPos(10, 22, 30);
        BlockPos q = p.west();
        BlockPos r = p.north();
        CellWorld oldWorld = sourceCorner(p, q, r);
        Map<Long, BlockState> original = Map.copyOf(oldWorld.cells);

        // Replay the old placement/mining order, then an independent fluid step. The production policy is not
        // consulted in this control: every cycle must restore exactly the original three-source world.
        for (int cycle = 0; cycle < 3; cycle++) {
            oldWorld.put(p, COBBLESTONE);
            oldWorld.put(p, AIR);
            waterTick(oldWorld, p);
            assertTrue(oldWorld.getFluidState(p).isSource());
            assertEquals("place -> break -> fluid update is a repeatable cycle", original, oldWorld.cells);
        }

        CellWorld guarded = sourceCorner(p, q, r);
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        seal(plugs, guarded, p);
        for (int tick = 0; tick < 4; tick++) {
            if (plugs.firstHazard(List.of(p), guarded, VANILLA).isEmpty()) {
                guarded.put(p, AIR);
                plugs.observe(p, AIR);
            }
            waterTick(guarded, p);
            assertEquals("the owned plug must survive while both feeders remain", COBBLESTONE,
                    guarded.getBlockState(p));
        }
        assertHazard(plugs, guarded, List.of(p), p, q, r);

        seal(plugs, guarded, q);
        assertTrue("one surviving feeder can flow, but cannot restore a source",
                plugs.firstHazard(List.of(p), guarded, VANILLA).isEmpty());
        guarded.put(p, AIR);
        plugs.observe(p, AIR);
        waterTick(guarded, p);
        assertFalse(guarded.getFluidState(p).isEmpty());
        assertFalse(guarded.getFluidState(p).isSource());

        seal(plugs, guarded, r);
        waterTick(guarded, p);
        assertTrue("sealing the last feeder lets the remaining flow drain", guarded.getBlockState(p).isAir());
        for (BlockPos plug : List.of(q, r)) {
            assertTrue(plugs.firstHazard(List.of(plug), guarded, VANILLA).isEmpty());
            guarded.put(plug, AIR);
            plugs.observe(plug, AIR);
        }
        for (BlockPos cleared : List.of(p, q, r)) assertTrue(guarded.getBlockState(cleared).isAir());
        assertEquals(0, plugs.size());
    }

    @Test
    public void isolatedSourceAndASingleFeederDoNotHoldAnInternalPlug() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        world.put(p.below(), STONE);
        assertSafe(plugs, world, p);
        world.put(p.west(), WATER);
        assertSafe(plugs, world, p);
    }

    @Test
    public void flowingNeighborsNeverCountAsRenewableSources() {
        BlockPos p = new BlockPos(10, 22, 30);
        for (int level = 1; level <= 15; level++) {
            CellWorld world = new CellWorld();
            ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
            world.put(p.below(), STONE);
            BlockState flow = WATER.setValue(BlockStateProperties.LEVEL, level);
            for (Direction direction : Direction.Plane.HORIZONTAL) world.put(p.relative(direction), flow);
            assertSafe(plugs, world, p);
            world.put(p.west(), WATER);
            assertSafe(plugs, world, p);
        }
    }

    @Test
    public void diagonalAndVerticalSourcesDoNotBecomeTwoHorizontalFeeders() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        world.put(p.below(), WATER);
        world.put(p.above(), WATER);
        world.put(p.north().west(), WATER);
        world.put(p.south().east(), WATER);
        assertSafe(plugs, world, p);
        world.put(p.west(), WATER);
        assertSafe(plugs, world, p);
    }

    @Test
    public void waterAndLavaSourcesAreNotAddedTogether() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        world.put(p.below(), STONE);
        world.put(p.west(), WATER);
        world.put(p.east(), LAVA);
        assertTrue(plugs.firstHazard(List.of(p), world, ignored -> true).isEmpty());
    }

    @Test
    public void vanillaWaterAndLavaConversionDefaultsAreDistinct() {
        assertTrue(ExcavationFluidPlugs.vanillaSourceConversion(Fluids.WATER));
        assertTrue(ExcavationFluidPlugs.vanillaSourceConversion(Fluids.FLOWING_WATER));
        assertFalse(ExcavationFluidPlugs.vanillaSourceConversion(Fluids.LAVA));
        assertFalse(ExcavationFluidPlugs.vanillaSourceConversion(Fluids.FLOWING_LAVA));
        assertFalse(ExcavationFluidPlugs.vanillaSourceConversion(Fluids.EMPTY));
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, LAVA);
        world.put(p.below(), STONE);
        world.put(p.west(), LAVA);
        world.put(p.east(), LAVA);
        assertSafe(plugs, world, p);
        assertTrue("a caller that knows lava conversion is enabled must get the source hazard",
                plugs.firstHazard(List.of(p), world, fluid -> fluid.isSame(Fluids.LAVA)).isPresent());
        assertTrue("conversion disabled explicitly also makes the water geometry nonrenewable",
                plugs.firstHazard(List.of(p), world, ignored -> false).isEmpty());
    }

    @Test
    public void twoWaterSourcesNeedSolidOrMatchingSourceSupportBelow() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        world.put(p.west(), WATER);
        world.put(p.east(), WATER);
        for (BlockState support : List.of(AIR, LAVA, WATER.setValue(BlockStateProperties.LEVEL, 2))) {
            world.put(p.below(), support);
            assertSafe(plugs, world, p);
        }
        for (BlockState support : List.of(STONE, WATER)) {
            world.put(p.below(), support);
            assertHazard(plugs, world, List.of(p), p, p.west(), p.east());
        }
    }

    @Test
    public void lowerSourceBandsDoNotNeedToBeDrainedBeforeAPlugCanBeRemoved() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        for (int y = -20; y < p.getY(); y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) world.put(new BlockPos(p.getX() + x, y, p.getZ() + z), WATER);
            }
        }
        assertSafe(plugs, world, p);
        world.put(p.west(), WATER);
        assertSafe(plugs, world, p);
    }

    @Test
    public void aShardCutThatAlsoRemovesTheSupportCannotRegenerateTheUpperPlug() {
        BlockPos target = new BlockPos(10, 22, 30);
        BlockPos p = target.above();
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        world.put(target, STONE);
        world.put(p.west(), WATER);
        world.put(p.east(), WATER);
        assertHazard(plugs, world, List.of(p), p, p.west(), p.east());
        List<BlockPos> cut = ExcavationFluidPlugs.footprint(target, Direction.EAST, true);
        assertTrue(coordinates(cut).contains(p.below().asLong()));
        assertTrue("regeneration is evaluated after the whole simultaneous cut, including its removed support",
                plugs.firstHazard(cut, world, VANILLA).isEmpty());
    }

    @Test
    public void hazardousOffCentrePlugsAreCheckedInAllThreeShardPlanes() {
        BlockPos target = new BlockPos(10, 22, 30);
        for (Direction face : List.of(Direction.UP, Direction.EAST, Direction.NORTH)) {
            BlockPos p;
            BlockPos q;
            BlockPos r;
            if (face.getAxis() == Direction.Axis.Y) {
                p = target.east().south();
                q = p.east();
                r = p.south();
            } else if (face.getAxis() == Direction.Axis.X) {
                p = target.below().south();
                q = p.west();
                r = p.east();
            } else {
                p = target.below().east();
                q = p.north();
                r = p.south();
            }
            CellWorld world = new CellWorld();
            ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
            world.put(target, STONE);
            world.put(p.below(), STONE);
            world.put(q, WATER);
            world.put(r, WATER);
            assertSafe(plugs, world, target);
            List<BlockPos> cut = ExcavationFluidPlugs.footprint(target, face, true);
            assertFalse(coordinates(cut).contains(q.asLong()));
            assertFalse(coordinates(cut).contains(r.asLong()));
            assertHazard(plugs, world, cut, p, q, r);
        }
    }

    @Test
    public void aShardCannotUseAnUnbreakableSupportAsIfItHadRemovedIt() {
        BlockPos target = new BlockPos(10, 22, 30);
        BlockPos p = target.above().south();
        List<BlockPos> cut = ExcavationFluidPlugs.footprint(target, Direction.EAST, true);
        assertTrue(BuilderProcess.snakeAreaFootprintInsideActiveBand(target, Direction.EAST, 21, 23));
        for (BlockState support : List.of(Blocks.BEDROCK.defaultBlockState(), Blocks.BARRIER.defaultBlockState())) {
            CellWorld world = sourceCorner(p, p.west(), p.east());
            ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
            world.put(p.below(), support);
            assertTrue(support.getDestroySpeed(world, p.below()) < 0);
            assertTrue(coordinates(cut).contains(p.below().asLong()));
            assertHazard(plugs, world, cut, p, p.west(), p.east());
            world.put(p.below(), STONE);
            assertTrue("breakable support in the identical footprint is actually removed",
                    plugs.firstHazard(cut, world, VANILLA).isEmpty());
        }
    }

    @Test
    public void anApparentlySafeShardCutCannotRemoveTheNextBandsSupportingFloor() {
        BlockPos p = new BlockPos(10, 20, 30);
        CellWorld world = sourceCorner(p, p.west(), p.east());
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        assertHazard(plugs, world, List.of(p), p, p.west(), p.east());
        List<BlockPos> cut = ExcavationFluidPlugs.footprint(p, Direction.EAST, true);
        assertTrue("the fluid calculation alone sees the supporting block being removed",
                plugs.firstHazard(cut, world, VANILLA).isEmpty());
        assertTrue("the whole cut is still inside the larger user selection",
                BuilderProcess.snakeAreaFootprintInsideBounds(p, Direction.EAST, 0, 40, 10, 30, 0, 40));
        assertFalse("the next band's floor is not available as a source-regeneration workaround",
                BuilderProcess.snakeAreaFootprintInsideActiveBand(p, Direction.EAST, 20, 22));
        assertTrue("the identical cut is permitted when all its support belongs to the active band",
                BuilderProcess.snakeAreaFootprintInsideActiveBand(p, Direction.EAST, 19, 21));
    }

    @Test
    public void fullBandsAdvanceNormallyAndShortBandsKeepTheirHorizontalCleanupPlanes() {
        for (int top : new int[] {22, 19, 16}) {
            for (Direction face : Direction.values()) {
                assertTrue(BuilderProcess.snakeAreaFootprintInsideActiveBand(new BlockPos(10, top - 1, 30),
                        face, top - 2, top));
            }
        }
        for (int height : new int[] {1, 2}) {
            for (int y = 10; y < 10 + height; y++) {
                for (Direction face : Direction.values()) {
                    assertEquals(face.getAxis() == Direction.Axis.Y,
                            BuilderProcess.snakeAreaFootprintInsideActiveBand(new BlockPos(10, y, 30),
                                    face, 10, 10 + height - 1));
                }
            }
        }
        assertFalse(BuilderProcess.snakeAreaFootprintInsideActiveBand(BlockPos.ZERO, Direction.UP,
                Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertFalse(BuilderProcess.snakeAreaFootprintInsideActiveBand(BlockPos.ZERO, null, -1, 1));
        assertFalse(BuilderProcess.snakeAreaFootprintInsideActiveBand(null, Direction.UP, -1, 1));
    }

    @Test
    public void footprintUsesTheClickedFaceAndSingleBlockToolsKeepOneCell() {
        BlockPos target = new BlockPos(10, 22, 30);
        for (Direction face : Direction.values()) {
            List<BlockPos> cut = ExcavationFluidPlugs.footprint(target, face, true);
            assertEquals(9, cut.size());
            Set<Long> expected = new HashSet<>();
            for (int first = -1; first <= 1; first++) {
                for (int second = -1; second <= 1; second++) {
                    BlockPos cell = switch (face.getAxis()) {
                        case X -> target.offset(0, first, second);
                        case Y -> target.offset(first, 0, second);
                        case Z -> target.offset(first, second, 0);
                    };
                    expected.add(cell.asLong());
                }
            }
            assertEquals(expected, coordinates(cut));
            assertEquals(Set.of(target.asLong()), coordinates(ExcavationFluidPlugs.footprint(target, face, false)));
        }
    }

    @Test
    public void onlyAnIssuedSourceToDryFullBlockTransitionStartsOwnership() {
        BlockPos p = new BlockPos(10, 22, 30);
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        for (BlockState before : List.of(AIR, STONE, WATER.setValue(BlockStateProperties.LEVEL, 2))) {
            plugs.record(p, before, COBBLESTONE);
            assertEquals(0, plugs.size());
        }
        for (BlockState after : List.of(AIR, WATER, WATER.setValue(BlockStateProperties.LEVEL, 2))) {
            plugs.record(p, WATER, after);
            assertEquals(0, plugs.size());
        }
        plugs.record(p, WATER, COBBLESTONE);
        assertEquals(1, plugs.size());
        plugs.record(new BetterBlockPos(p), WATER, COBBLESTONE);
        assertEquals("coordinate identity must not depend on the concrete BlockPos hash", 1, plugs.size());
    }

    @Test
    public void observedPlacementRejectionAndExternalReplacementRevokeOwnership() {
        BlockPos p = new BlockPos(10, 22, 30);
        for (BlockState replacement : List.of(AIR, WATER, STONE)) {
            CellWorld world = new CellWorld();
            ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
            world.put(p.below(), STONE);
            world.put(p.west(), WATER);
            world.put(p.east(), WATER);
            assertHazard(plugs, world, List.of(p), p, p.west(), p.east());
            world.put(p, replacement);
            plugs.observe(new BetterBlockPos(p), replacement);
            assertEquals(0, plugs.size());
            assertSafe(plugs, world, p);
        }
    }

    @Test
    public void liveWorldReplacementCannotKeepAStalePlugProtected() {
        BlockPos p = new BlockPos(10, 22, 30);
        for (BlockState replacement : List.of(AIR, WATER, STONE)) {
            CellWorld world = new CellWorld();
            ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
            world.put(p.below(), STONE);
            world.put(p.west(), WATER);
            world.put(p.east(), WATER);
            world.put(p, replacement);
            assertSafe(plugs, world, p);
        }
    }

    @Test
    public void unchangedObservationPreservesOwnershipAndLifecycleResetForgetsIt() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = new CellWorld();
        ExcavationFluidPlugs plugs = ownedPlug(world, p, WATER);
        world.put(p.below(), STONE);
        world.put(p.west(), WATER);
        world.put(p.east(), WATER);
        plugs.observe(new BetterBlockPos(p), COBBLESTONE);
        assertEquals(1, plugs.size());
        assertHazard(plugs, world, List.of(new BetterBlockPos(p)), p, p.west(), p.east());
        plugs.clear();
        assertEquals(0, plugs.size());
        assertSafe(plugs, world, p);
    }

    @Test
    public void oldSourceObservationsRemainPendingUntilTheFirstPlacedBlockAcknowledgement() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = sourceCorner(p, p.west(), p.north());
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        plugs.record(p, WATER, COBBLESTONE);
        for (int duplicate = 0; duplicate < 10; duplicate++) {
            plugs.observe(new BetterBlockPos(p), WATER);
            assertEquals("the old source packet must not erase an outstanding placement", 1, plugs.size());
            assertSafe(plugs, world, p);
        }
        world.put(p, COBBLESTONE);
        plugs.observe(p, COBBLESTONE);
        assertHazard(plugs, world, List.of(p), p, p.west(), p.north());
        world.put(p, WATER);
        plugs.observe(p, WATER);
        assertEquals("the same WATER after acknowledgement is a real replacement, not an old-state grace", 0,
                plugs.size());
    }

    @Test
    public void aPendingPlacementDoesNotIgnoreAirRejectionOrAnUnrelatedReplacement() {
        BlockPos p = new BlockPos(10, 22, 30);
        for (BlockState replacement : List.of(AIR, STONE, LAVA,
                WATER.setValue(BlockStateProperties.LEVEL, 2))) {
            ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
            plugs.record(p, WATER, COBBLESTONE);
            plugs.observe(p, replacement);
            assertEquals("only the exact old source state receives the pending grace", 0, plugs.size());
            plugs.observe(p, COBBLESTONE);
            assertEquals("a later unrelated solid cannot resurrect discarded ownership", 0, plugs.size());
        }
    }

    @Test
    public void aSnapshotKeepsItsLedgerWhenTheLiveLedgerRecordsObservesOrClears() {
        BlockPos p = new BlockPos(10, 22, 30);
        BlockPos other = p.offset(10, 0, 0);
        CellWorld world = sourceCorner(p, p.west(), p.north());
        ExcavationFluidPlugs live = new ExcavationFluidPlugs();
        seal(live, world, p);
        ExcavationFluidPlugs snapshot = live.snapshot();
        live.observe(p, AIR);
        world.put(other, WATER);
        seal(live, world, other);
        assertEquals(1, live.size());
        assertEquals(1, snapshot.size());
        assertHazard(snapshot, world, List.of(p), p, p.west(), p.north());
        assertSafe(live, world, p);
        world.put(other.below(), STONE);
        world.put(other.west(), WATER);
        world.put(other.east(), WATER);
        assertHazard(live, world, List.of(other), other, other.west(), other.east());
        assertSafe(snapshot, world, other);
        live.clear();
        assertEquals(0, live.size());
        assertEquals(1, snapshot.size());
        assertHazard(snapshot, world, List.of(p), p, p.west(), p.north());
    }

    @Test
    public void aSnapshotsPendingConfirmationStateIsNotSharedWithTheLiveLedger() {
        BlockPos p = new BlockPos(10, 22, 30);
        CellWorld world = sourceCorner(p, p.west(), p.north());
        ExcavationFluidPlugs live = new ExcavationFluidPlugs();
        live.record(p, WATER, COBBLESTONE);
        ExcavationFluidPlugs snapshot = live.snapshot();
        live.observe(p, COBBLESTONE);
        snapshot.observe(p, WATER);
        assertEquals("confirming the live entry must not mutate the snapshot's pending entry", 1, snapshot.size());
        live.observe(p, WATER);
        assertEquals(0, live.size());
        world.put(p, COBBLESTONE);
        snapshot.observe(p, COBBLESTONE);
        assertHazard(snapshot, world, List.of(p), p, p.west(), p.north());
        assertEquals(0, live.size());
    }

    @Test
    public void confirmationTimeoutUsesAStrictFirstRequestTickBoundary() {
        BlockPos p = new BlockPos(10, 22, 30);
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        plugs.record(p, WATER, COBBLESTONE, 123);
        assertTrue(plugs.unconfirmedBefore(122).isEmpty());
        assertTrue("a request exactly at the cutoff is still within its confirmation window",
                plugs.unconfirmedBefore(123).isEmpty());
        assertEquals(p.asLong(), plugs.unconfirmedBefore(124).orElseThrow().asLong());
        plugs.observe(p, COBBLESTONE);
        assertTrue("confirmed ownership no longer owes a server acknowledgement",
                plugs.unconfirmedBefore(Long.MAX_VALUE).isEmpty());
    }

    @Test
    public void duplicateRequestsAndOldSourcePacketsCannotPostponeTheConfirmationTimeout() {
        BlockPos p = new BlockPos(10, 22, 30);
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        plugs.record(p, WATER, COBBLESTONE, 123);
        for (int tick = 124; tick <= 523; tick++) {
            plugs.record(new BetterBlockPos(p), WATER, COBBLESTONE, tick);
            plugs.observe(p, WATER);
            assertTrue(plugs.unconfirmedBefore(tick - 400).isEmpty());
        }
        plugs.record(p, WATER, COBBLESTONE, 524);
        plugs.observe(p, WATER);
        assertEquals("the 401st elapsed tick must still refer to the original request",
                p.asLong(), plugs.unconfirmedBefore(524 - 400).orElseThrow().asLong());
    }

    @Test
    public void rejectedClearedAndConfirmedRequestsAreExcludedFromPendingTimeouts() {
        BlockPos first = new BlockPos(10, 22, 30);
        BlockPos second = first.east();
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        plugs.record(first, WATER, COBBLESTONE, 100);
        plugs.record(second, WATER, COBBLESTONE, 200);
        plugs.observe(first, COBBLESTONE);
        assertTrue(plugs.unconfirmedBefore(150).isEmpty());
        assertEquals(second.asLong(), plugs.unconfirmedBefore(201).orElseThrow().asLong());
        plugs.observe(second, AIR);
        assertTrue(plugs.unconfirmedBefore(Long.MAX_VALUE).isEmpty());
        plugs.record(second, WATER, COBBLESTONE);
        assertTrue("the compatibility overload starts its request at tick zero",
                plugs.unconfirmedBefore(0).isEmpty());
        assertEquals(second.asLong(), plugs.unconfirmedBefore(1).orElseThrow().asLong());
        plugs.clear();
        assertTrue(plugs.unconfirmedBefore(Long.MAX_VALUE).isEmpty());
    }

    @Test
    public void aSnapshotPreservesTheOriginalRequestTimestampAndConfirmationStatus() {
        BlockPos pending = new BlockPos(10, 22, 30);
        BlockPos confirmed = pending.east();
        ExcavationFluidPlugs live = new ExcavationFluidPlugs();
        live.record(pending, WATER, COBBLESTONE, 123);
        live.record(confirmed, WATER, COBBLESTONE, 50);
        live.observe(confirmed, COBBLESTONE);
        ExcavationFluidPlugs snapshot = live.snapshot();
        live.observe(pending, COBBLESTONE);
        live.clear();
        snapshot.record(pending, WATER, COBBLESTONE, 500);
        snapshot.observe(pending, WATER);
        assertTrue(snapshot.unconfirmedBefore(123).isEmpty());
        assertEquals(pending.asLong(), snapshot.unconfirmedBefore(124).orElseThrow().asLong());
        snapshot.observe(pending, COBBLESTONE);
        assertTrue(snapshot.unconfirmedBefore(Long.MAX_VALUE).isEmpty());
    }

    @Test
    public void anUnchangedHazardExpiresAfterTwoHundredBuilderTicksWithoutCountingRepeatedPolls() {
        BlockPos p = new BlockPos(10, 22, 30);
        var hazard = new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.east()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        for (int poll = 0; poll < 500; poll++) assertFalse(plugs.waitExpired(hazard, 100));
        assertFalse(plugs.waitExpired(hazard, 299));
        assertTrue("an inaccessible feeder must lead to a bounded failure, not an infinite mining hold",
                plugs.waitExpired(hazard, 300));
    }

    @Test
    public void anAbsentHazardAndALifecycleResetBothRestartTheWaitBudget() {
        BlockPos p = new BlockPos(10, 22, 30);
        var hazard = new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.east()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        assertFalse(plugs.waitExpired(hazard, 100));
        assertFalse(plugs.waitExpired(null, 299));
        assertFalse(plugs.waitExpired(hazard, 300));
        assertFalse(plugs.waitExpired(hazard, 499));
        plugs.clear();
        assertFalse(plugs.waitExpired(hazard, 500));
        assertFalse(plugs.waitExpired(hazard, 699));
        assertTrue(plugs.waitExpired(hazard, 700));
    }

    @Test
    public void changingTheBlockedPlugOrItsSourceSetDoesNotRestartTheWaitBudget() {
        BlockPos p = new BlockPos(10, 22, 30);
        var original = new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.east()));
        for (var changed : List.of(
                new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.north())),
                new ExcavationFluidPlugs.Hazard(p.above(), List.of(p.west(), p.east())))) {
            ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
            assertFalse(plugs.waitExpired(original, 100));
            assertFalse(plugs.waitExpired(changed, 299));
            assertTrue("a different blocked target is diagnostic information, not observed improvement",
                    plugs.waitExpired(changed, 300));
        }
    }

    @Test
    public void pendingRequestsAndRepeatedOldSourcePacketsDoNotResetAnExistingHazardDeadline() {
        BlockPos p = new BlockPos(10, 22, 30);
        BlockPos pending = p.offset(10, 0, 0);
        var hazard = new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.east()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        assertFalse(plugs.waitExpired(hazard, 100));
        for (int tick = 101; tick < 300; tick++) {
            plugs.record(pending, WATER, COBBLESTONE);
            plugs.observe(pending, WATER);
            assertFalse(plugs.waitExpired(hazard, tick));
        }
        assertEquals(1, plugs.size());
        assertTrue("requests and old-state packets are not proof that the world improved",
                plugs.waitExpired(hazard, 300));
    }

    @Test
    public void onlyTheFirstPlacementAcknowledgementResetsTheHazardDeadline() {
        BlockPos p = new BlockPos(10, 22, 30);
        BlockPos pending = p.offset(10, 0, 0);
        var hazard = new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.east()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        plugs.record(pending, WATER, COBBLESTONE);
        assertFalse(plugs.waitExpired(hazard, 100));
        assertFalse(plugs.waitExpired(hazard, 299));
        plugs.observe(pending, COBBLESTONE);
        assertFalse("the first observed placement is real progress", plugs.waitExpired(hazard, 300));
        for (int tick = 301; tick < 500; tick++) {
            plugs.observe(pending, COBBLESTONE);
            assertFalse(plugs.waitExpired(hazard, tick));
        }
        assertTrue("duplicate acknowledgements cannot renew the grace forever", plugs.waitExpired(hazard, 500));
    }

    @Test
    public void repeatingARequestForAnAlreadyConfirmedPlacementCannotManufactureNewProgress() {
        BlockPos p = new BlockPos(10, 22, 30);
        BlockPos confirmed = p.offset(10, 0, 0);
        var hazard = new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.east()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        plugs.record(confirmed, WATER, COBBLESTONE);
        plugs.observe(confirmed, COBBLESTONE);
        assertFalse(plugs.waitExpired(hazard, 100));
        for (int tick = 101; tick < 300; tick++) {
            plugs.record(confirmed, WATER, COBBLESTONE);
            plugs.observe(confirmed, COBBLESTONE);
            assertFalse(plugs.waitExpired(hazard, tick));
        }
        assertTrue("reissuing the same request must preserve the fact that its acknowledgement was already counted",
                plugs.waitExpired(hazard, 300));
    }

    @Test
    public void explicitRouteProgressRestartsTheWaitBudgetButElapsedTicksAloneDoNot() {
        BlockPos p = new BlockPos(10, 22, 30);
        var hazard = new ExcavationFluidPlugs.Hazard(p, List.of(p.west(), p.east()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        assertFalse(plugs.waitExpired(hazard, 100));
        assertFalse(plugs.waitExpired(hazard, 299));
        plugs.progress();
        assertFalse(plugs.waitExpired(hazard, 300));
        assertFalse(plugs.waitExpired(hazard, 499));
        assertTrue(plugs.waitExpired(hazard, 500));
    }

    @Test
    public void theFirstRouteObservationAndANewExecutorDoNotCountAsMovementProgress() {
        BlockPos feet = new BlockPos(10, 22, 30);
        var hazard = new ExcavationFluidPlugs.Hazard(feet.east(), List.of(feet.north(), feet.south()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        assertFalse(plugs.waitExpired(hazard, 100));
        plugs.routeProgress(new Object(), 7, feet);
        for (int tick = 101; tick < 300; tick++) {
            plugs.routeProgress(new Object(), tick, feet.offset(tick, 0, 0));
            assertFalse(plugs.waitExpired(hazard, tick));
        }
        assertTrue("replanning alone cannot keep a blocked excavation alive", plugs.waitExpired(hazard, 300));
    }

    @Test
    public void theSameOrABackwardRouteIndexCannotRenewTheWaitEvenAtANewCell() {
        BlockPos feet = new BlockPos(10, 22, 30);
        var hazard = new ExcavationFluidPlugs.Hazard(feet.east(), List.of(feet.north(), feet.south()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Object route = new Object();
        assertFalse(plugs.waitExpired(hazard, 100));
        plugs.routeProgress(route, 5, feet);
        plugs.routeProgress(route, 5, feet.east());
        assertFalse(plugs.waitExpired(hazard, 199));
        plugs.routeProgress(route, 4, feet.north());
        assertFalse(plugs.waitExpired(hazard, 250));
        plugs.routeProgress(route, 5, feet.south());
        plugs.routeProgress(null, 100, feet.above());
        assertTrue("moving backward and recovering the old index is not new route progress",
                plugs.waitExpired(hazard, 300));
    }

    @Test
    public void anAdvancedRouteIndexAtANewCellRestartsTheWaitBudget() {
        BlockPos feet = new BlockPos(10, 22, 30);
        var hazard = new ExcavationFluidPlugs.Hazard(feet.east(), List.of(feet.north(), feet.south()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Object route = new Object();
        assertFalse(plugs.waitExpired(hazard, 100));
        plugs.routeProgress(route, 0, feet);
        assertFalse(plugs.waitExpired(hazard, 299));
        plugs.routeProgress(route, 1, feet.east());
        assertFalse("a completed route step into a new cell is observable progress",
                plugs.waitExpired(hazard, 300));
        assertFalse(plugs.waitExpired(hazard, 499));
        assertTrue(plugs.waitExpired(hazard, 500));
    }

    @Test
    public void advancingIndicesThroughPreviouslyVisitedCellsCannotCreatePerpetualProgress() {
        BlockPos start = new BlockPos(10, 22, 30);
        BlockPos next = start.east();
        var hazard = new ExcavationFluidPlugs.Hazard(start.north(), List.of(start.west(), start.south()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Object route = new Object();
        assertFalse(plugs.waitExpired(hazard, 100));
        plugs.routeProgress(route, 0, start);
        plugs.routeProgress(route, 1, next);
        assertFalse(plugs.waitExpired(hazard, 200));
        for (int tick = 201; tick < 400; tick++) {
            plugs.routeProgress(route, tick, tick % 2 == 0 ? new BetterBlockPos(start) : next);
            assertFalse(plugs.waitExpired(hazard, tick));
        }
        assertTrue("returning to either credited cell is a loop, even with increasing node numbers",
                plugs.waitExpired(hazard, 400));
    }

    @Test
    public void aReplannedRouteKeepsTheEpisodesAlreadyCreditedCells() {
        BlockPos start = new BlockPos(10, 22, 30);
        BlockPos next = start.east();
        var hazard = new ExcavationFluidPlugs.Hazard(start.north(), List.of(start.west(), start.south()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Object firstRoute = new Object();
        Object replanned = new Object();
        assertFalse(plugs.waitExpired(hazard, 100));
        plugs.routeProgress(firstRoute, 0, start);
        plugs.routeProgress(firstRoute, 1, next);
        assertFalse(plugs.waitExpired(hazard, 200));
        plugs.routeProgress(replanned, 0, next);
        plugs.routeProgress(replanned, 1, start);
        assertFalse(plugs.waitExpired(hazard, 399));
        assertTrue("replanning must not make the old start cell new again", plugs.waitExpired(hazard, 400));
    }

    @Test
    public void aChangedHazardKeepsTheRouteEpisodeButAnAbsentHazardStartsANewOne() {
        BlockPos start = new BlockPos(10, 22, 30);
        BlockPos next = start.east();
        var original = new ExcavationFluidPlugs.Hazard(start.north(), List.of(start.west(), start.south()));
        var changed = new ExcavationFluidPlugs.Hazard(start.south(), List.of(start.west(), start.north()));
        for (boolean absentBetween : new boolean[] {false, true}) {
            ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
            Object route = new Object();
            assertFalse(plugs.waitExpired(original, 100));
            plugs.routeProgress(route, 0, start);
            plugs.routeProgress(route, 1, next);
            assertFalse(plugs.waitExpired(original, 150));
            if (absentBetween) assertFalse(plugs.waitExpired(null, 199));
            var active = absentBetween ? original : changed;
            assertFalse(plugs.waitExpired(active, 200));
            plugs.routeProgress(route, 2, next);
            if (absentBetween) {
                assertFalse(plugs.waitExpired(active, 399));
                plugs.routeProgress(route, 3, start);
                assertFalse("a newly reached cell after the old blockage ended may count again",
                        plugs.waitExpired(active, 399));
                assertFalse(plugs.waitExpired(active, 598));
                assertTrue(plugs.waitExpired(active, 599));
            } else {
                assertFalse(plugs.waitExpired(active, 349));
                plugs.routeProgress(route, 3, start);
                assertTrue("a hazard change must not make this episode's old start cell new again",
                        plugs.waitExpired(active, 350));
            }
        }
    }

    @Test
    public void oscillatingHazardsCannotRenewTheDeadlineOrReuseAlreadyCreditedRouteCells() {
        BlockPos start = new BlockPos(10, 22, 30);
        BlockPos next = start.east();
        var first = new ExcavationFluidPlugs.Hazard(start.north(), List.of(start.west(), start.south()));
        var second = new ExcavationFluidPlugs.Hazard(start.south(), List.of(start.west(), start.north()));
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        Object route = new Object();
        assertFalse(plugs.waitExpired(first, 100));
        plugs.routeProgress(route, 0, start);
        plugs.routeProgress(route, 1, next);
        assertFalse(plugs.waitExpired(first, 150));
        for (int tick = 151; tick < 350; tick++) {
            var active = tick % 2 == 0 ? first : second;
            // A reach-local scan can alternate its first blocked plug as the body drifts across a cell edge.
            // Neither the diagnostic target change nor a return to these two cells improves the excavation.
            assertFalse(plugs.waitExpired(active, tick));
            plugs.routeProgress(route, tick, tick % 2 == 0 ? start : new BetterBlockPos(next));
            assertFalse(plugs.waitExpired(active, tick));
        }
        assertTrue("the deadline remains 200 ticks after the last genuinely new route cell",
                plugs.waitExpired(first, 350));
    }

    private static CellWorld sourceCorner(BlockPos p, BlockPos q, BlockPos r) {
        CellWorld world = new CellWorld();
        for (BlockPos cell : List.of(p, q, r)) {
            world.put(cell, WATER);
            world.put(cell.below(), STONE);
        }
        return world;
    }

    private static ExcavationFluidPlugs ownedPlug(CellWorld world, BlockPos pos, BlockState source) {
        ExcavationFluidPlugs plugs = new ExcavationFluidPlugs();
        world.put(pos, source);
        seal(plugs, world, pos);
        return plugs;
    }

    private static void seal(ExcavationFluidPlugs plugs, CellWorld world, BlockPos pos) {
        BlockState before = world.getBlockState(pos);
        world.put(pos, COBBLESTONE);
        plugs.record(pos, before, COBBLESTONE);
        plugs.observe(pos, world.getBlockState(pos));
    }

    private static void assertSafe(ExcavationFluidPlugs plugs, CellWorld world, BlockPos pos) {
        assertTrue("this cut must not wait for a source-regeneration hazard that does not exist",
                plugs.firstHazard(List.of(pos), world, VANILLA).isEmpty());
    }

    private static void assertHazard(ExcavationFluidPlugs plugs, CellWorld world, Collection<BlockPos> cut,
                                     BlockPos plug, BlockPos... sources) {
        var hazard = plugs.firstHazard(cut, world, VANILLA);
        assertTrue("removing the plug would restore a renewable source", hazard.isPresent());
        assertEquals(plug.asLong(), hazard.orElseThrow().plug().asLong());
        assertEquals(coordinates(List.of(sources)), coordinates(hazard.orElseThrow().sources()));
    }

    private static Set<Long> coordinates(Collection<BlockPos> positions) {
        Set<Long> result = new HashSet<>();
        for (BlockPos position : positions) result.add(position.asLong());
        return result;
    }

    /**
     * Small fluid-update model for the three transparent water cells used by the cycle regression, not a full
     * server simulation. It is deliberately independent of firstHazard: two horizontal water sources over solid
     * support restore a source; otherwise the highest neighboring amount loses one level, eventually reaching AIR.
     * These are the 26.1.2 FlowingFluid.getNewLiquid/WaterFluid.getDropOff rules for this exact fixture geometry.
     */
    private static void waterTick(CellWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isAir() && !state.getFluidState().getType().isSame(Fluids.WATER)) return;
        if (state.getFluidState().isSource()) return;
        int sources = 0;
        int greatestAmount = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            FluidState neighbor = world.getFluidState(pos.relative(direction));
            if (!neighbor.getType().isSame(Fluids.WATER)) continue;
            if (neighbor.isSource()) sources++;
            greatestAmount = Math.max(greatestAmount, neighbor.getAmount());
        }
        BlockState below = world.getBlockState(pos.below());
        if (sources >= 2 && (below.isSolid()
                || (below.getFluidState().isSource() && below.getFluidState().getType().isSame(Fluids.WATER)))) {
            world.put(pos, WATER);
        } else {
            int amount = greatestAmount - 1;
            world.put(pos, amount <= 0 ? AIR : WATER.setValue(BlockStateProperties.LEVEL, 8 - amount));
        }
    }

    /** World identity is a packed coordinate, never BlockPos versus BetterBlockPos's differing hash functions. */
    private static final class CellWorld implements BlockGetter {
        private final Map<Long, BlockState> cells = new HashMap<>();

        private void put(BlockPos pos, BlockState state) {
            if (state.isAir()) cells.remove(pos.asLong());
            else cells.put(pos.asLong(), state);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) { return cells.getOrDefault(pos.asLong(), AIR); }

        @Override
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }

        @Override
        public int getHeight() { return 384; }

        @Override
        public int getMinY() { return -64; }
    }
}
