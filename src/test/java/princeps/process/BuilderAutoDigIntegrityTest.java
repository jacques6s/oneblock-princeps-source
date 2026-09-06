/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import org.junit.Test;
import org.junit.BeforeClass;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import princeps.api.utils.BetterBlockPos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure geometry for the AutoDig route and its excavation shell. */
public class BuilderAutoDigIntegrityTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void remoteGoalIsReducedToOneCardinalWaypointForThePathfinder() {
        BetterBlockPos feet = new BetterBlockPos(10, 20, 30);

        assertEquals(new BetterBlockPos(11, 20, 30),
                BuilderProcess.nextSnakeWaypoint(feet, new BetterBlockPos(14, 20, 32)));
        assertEquals(new BetterBlockPos(10, 20, 29),
                BuilderProcess.nextSnakeWaypoint(feet, new BetterBlockPos(11, 20, 26)));
    }

    @Test
    public void adjacentAndVerticalGoalsRemainOwnedByThePathfinderUnchanged() {
        BetterBlockPos feet = new BetterBlockPos(10, 20, 30);
        BetterBlockPos adjacent = new BetterBlockPos(10, 20, 31);
        BetterBlockPos descent = new BetterBlockPos(14, 19, 32);

        assertEquals(adjacent, BuilderProcess.nextSnakeWaypoint(feet, adjacent));
        assertEquals(descent, BuilderProcess.nextSnakeWaypoint(feet, descent));
    }

    @Test
    public void excavationRouteMayPlaceOnlyItsSingleSnapshottedBridgeCell() {
        long bridge = BetterBlockPos.asLong(12, 18, 30);

        assertTrue(BuilderProcess.excavationPlacementAllowed(bridge, 12, 18, 30));
        assertFalse(BuilderProcess.excavationPlacementAllowed(bridge, 12, 19, 30));
        assertFalse(BuilderProcess.excavationPlacementAllowed(bridge, 13, 18, 30));
        assertFalse(BuilderProcess.excavationPlacementAllowed(Long.MIN_VALUE, 12, 18, 30));
    }

    @Test
    public void routePreflightOwnsOnlyTheWalkingBodysCollisionEnvelope() {
        assertTrue(BuilderProcess.snakeRouteBodyIncludesY(-51, -51));
        assertTrue(BuilderProcess.snakeRouteBodyIncludesY(-51, -50));
        assertFalse(BuilderProcess.snakeRouteBodyIncludesY(-51, -52));
        assertFalse(BuilderProcess.snakeRouteBodyIncludesY(-51, -53));
        assertFalse(BuilderProcess.snakeRouteBodyIncludesY(-51, -54));
    }

    @Test
    public void verticalEntryRequiresTheExactCenteredColumnRatherThanRemoteReach() {
        BetterBlockPos stance = new BetterBlockPos(68, -51, 95);

        assertFalse(BuilderProcess.snakeEntryPoseReady(
                new BetterBlockPos(68, -51, 92), stance, true, true));
        assertFalse(BuilderProcess.snakeEntryPoseReady(stance, stance, true, false));
        assertFalse(BuilderProcess.snakeEntryPoseReady(stance, stance, false, true));
        assertTrue(BuilderProcess.snakeEntryPoseReady(stance, stance, true, true));
    }

    @Test
    public void clearedEntryCentreWaitsForLandingInsteadOfMiningItsEightNeighbours() {
        assertTrue(BuilderProcess.snakeMustAwaitEntryLanding(true, false));
        assertFalse(BuilderProcess.snakeMustAwaitEntryLanding(true, true));
        assertFalse(BuilderProcess.snakeMustAwaitEntryLanding(false, false));
    }

    @Test
    public void completedBandVerificationIsIndependentOfTheGlobalLowerWorkSet() {
        assertTrue(BuilderProcess.snakeBandNeedsVerification(
                false, true, true, -49, -49, Integer.MIN_VALUE));
        assertTrue(BuilderProcess.snakeBandNeedsVerification(
                true, true, false, Integer.MIN_VALUE, -49, Integer.MIN_VALUE));
        assertTrue(BuilderProcess.snakeBandNeedsVerification(
                false, false, false, Integer.MIN_VALUE, -49, Integer.MIN_VALUE));
    }

    @Test
    public void staleRouteCompletionCannotVerifyTheNextCommittedBand() {
        assertFalse(BuilderProcess.snakeBandNeedsVerification(
                false, true, true, -49, -52, -49));
        assertFalse(BuilderProcess.snakeBandNeedsVerification(
                false, true, true, -49, -52, Integer.MIN_VALUE));
        assertFalse(BuilderProcess.snakeBandNeedsVerification(
                false, true, true, -52, -52, -52));
    }

    @Test
    public void areaSwingAcceptsHumanDiagonalButRejectsAnOverlyObliqueApproach() {
        assertTrue(BuilderProcess.snakeWithinFaceAngle(Math.tan(Math.toRadians(34.0D)), 1.0D,
                Direction.SOUTH));
        assertFalse(BuilderProcess.snakeWithinFaceAngle(Math.tan(Math.toRadians(36.0D)), 1.0D,
                Direction.SOUTH));
        assertFalse(BuilderProcess.snakeWithinFaceAngle(0.0D, -1.0D, Direction.SOUTH));
    }

    @Test
    public void laneTurnsUseTheCentreOfTheOutermostThreeRows() {
        assertEquals(68, BuilderProcess.snakeOuterTurnTravel(67, 1));
        assertEquals(95, BuilderProcess.snakeOuterTurnTravel(96, -1));
    }

    @Test
    public void refilledSlicesAreRecognisedOnlyAfterTheirLaneCursorPassedThem() {
        assertTrue(BuilderProcess.snakeTravelAlreadyCleared(74, 75, 1));
        assertFalse(BuilderProcess.snakeTravelAlreadyCleared(75, 75, 1));
        assertTrue(BuilderProcess.snakeTravelAlreadyCleared(88, 87, -1));
        assertFalse(BuilderProcess.snakeTravelAlreadyCleared(87, 87, -1));
    }

    @Test
    public void refillRecoveryApproachesFromThePlayersAlreadyClearSide() {
        assertEquals(76, BuilderProcess.snakeRecoveryStanceTravel(77, 74, 1, 67, 96));
        assertEquals(78, BuilderProcess.snakeRecoveryStanceTravel(77, 82, 1, 67, 96));
        assertEquals(95, BuilderProcess.snakeRecoveryStanceTravel(96, 96, -1, 67, 96));
    }

    @Test
    public void currentBandOwnsFourSideWallsButNotTheirOutsideCorners() {
        int minX = 10, maxX = 14, minZ = 20, maxZ = 24;

        assertTrue(BuilderProcess.snakeShellCoordinate(9, 6, 22,
                minX, maxX, minZ, maxZ, 5, 7, 13));
        assertTrue(BuilderProcess.snakeShellCoordinate(12, 7, 25,
                minX, maxX, minZ, maxZ, 5, 7, 13));
        assertFalse(BuilderProcess.snakeShellCoordinate(9, 6, 19,
                minX, maxX, minZ, maxZ, 5, 7, 13));
        assertFalse(BuilderProcess.snakeShellCoordinate(9, 8, 22,
                minX, maxX, minZ, maxZ, 5, 7, 13));
    }

    @Test
    public void roofBelongsOnlyToTheTopBand() {
        int minX = 10, maxX = 14, minZ = 20, maxZ = 24;

        assertTrue(BuilderProcess.snakeShellCoordinate(12, 14, 22,
                minX, maxX, minZ, maxZ, 11, 13, 13));
        assertFalse(BuilderProcess.snakeShellCoordinate(12, 14, 22,
                minX, maxX, minZ, maxZ, 8, 10, 13));
        assertFalse(BuilderProcess.snakeShellCoordinate(15, 14, 22,
                minX, maxX, minZ, maxZ, 11, 13, 13));
    }

    @Test
    public void waterloggedSolidsRemainMiningWorkRatherThanUnreplaceableFluidTargets() {
        for (BlockState dry : new BlockState[] {
                Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()
        }) {
            BlockState wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
            assertTrue("fixture must carry a water source: " + wet, wet.getFluidState().isSource());
            assertFalse("excavation must mine the solid before sealing its water: " + wet,
                    BuilderProcess.snakeTreatAsFluid(wet, true));
            assertTrue("construction retains its existing fluid-pass classification: " + wet,
                    BuilderProcess.snakeTreatAsFluid(wet, false));
        }
    }

    @Test
    public void underwaterPlantsAreBreakableEvenThoughTheyCarrySourceWater() {
        for (BlockState plant : new BlockState[] {
                Blocks.SEAGRASS.defaultBlockState(), Blocks.TALL_SEAGRASS.defaultBlockState(),
                Blocks.KELP.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState()
        }) {
            assertTrue("fixture must carry source water: " + plant, plant.getFluidState().isSource());
            assertFalse("the plant must not disappear from excavation work: " + plant,
                    BuilderProcess.snakeTreatAsFluid(plant, true));
        }
    }

    @Test
    public void pureWaterLavaAndBubbleColumnsNeverBecomeMiningTargets() {
        for (BlockState fluid : new BlockState[] {
                Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState()
                        .setValue(BlockStateProperties.LEVEL, 4),
                Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 8),
                Blocks.LAVA.defaultBlockState(), Blocks.LAVA.defaultBlockState()
                        .setValue(BlockStateProperties.LEVEL, 4),
                Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, 8),
                Blocks.BUBBLE_COLUMN.defaultBlockState()
        }) {
            assertTrue("fluid-only cells must be sealed or allowed to drain: " + fluid,
                    BuilderProcess.snakeTreatAsFluid(fluid, true));
        }
        assertFalse(BuilderProcess.snakeTreatAsFluid(Blocks.STONE.defaultBlockState(), true));
        assertFalse(BuilderProcess.snakeTreatAsFluid(Blocks.AIR.defaultBlockState(), true));
    }

    @Test
    public void fallingFullHeightFlowWaitsForItsSourceInsteadOfDemandingAPlug() {
        for (BlockState liquid : new BlockState[] {
                Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState()
        }) {
            BlockState falling = liquid.setValue(BlockStateProperties.LEVEL, 8);
            assertEquals("falling flow can have source-like height", 8, falling.getFluidState().getAmount());
            assertFalse(falling.getFluidState().isSource());
            assertTrue(BuilderProcess.snakeTreatAsFluid(falling, true));
            assertFalse(BuilderProcess.snakeSourceReadyForPlug(falling, true));
            assertTrue(BuilderProcess.snakeSourceReadyForPlug(liquid, true));
        }
    }

    @Test
    public void partialSliceCentresUseTheirRealOutlineInsteadOfAnAbsentCubeFaceCentre() {
        for (BlockState state : new BlockState[] {
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.SEAGRASS.defaultBlockState(), Blocks.KELP.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState()
        }) {
            assertTrue("partial head needs an ordinary outline ray: " + state,
                    BuilderProcess.snakeHeadNeedsIndividualBreak(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        }
        for (BlockState state : new BlockState[] {
                Blocks.STONE.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), Blocks.BUBBLE_COLUMN.defaultBlockState()
        }) {
            assertFalse("full cubes and fluid-only cells keep their existing action: " + state,
                    BuilderProcess.snakeHeadNeedsIndividualBreak(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        }
    }

    @Test
    public void breakingWaterloggedSolidExposesTheSourceThatCanThenBePlugged() {
        BlockState wetSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        assertFalse("a solid cannot be replaced by a source plug before it is mined",
                BuilderProcess.snakeSourceReadyForPlug(wetSlab, true));
        assertTrue("the solid still belongs in the residual block census",
                !wetSlab.isAir() && !BuilderProcess.snakeTreatAsFluid(wetSlab, true));

        BlockState exposedWater = wetSlab.getFluidState().createLegacyBlock();
        assertTrue(BuilderProcess.snakeSourceReadyForPlug(exposedWater, true));
        assertTrue(BuilderProcess.snakeTreatAsFluid(exposedWater, true));
        assertFalse(BuilderProcess.snakeSourceReadyForPlug(Blocks.COBBLESTONE.defaultBlockState(), true));
        assertFalse(BuilderProcess.snakeTreatAsFluid(Blocks.COBBLESTONE.defaultBlockState(), true));
    }

    @Test
    public void shellAuditNeverCertifiesWaterloggedExteriorOrFlowingFluidAsDry() {
        BlockState wetWall = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        assertTrue(BuilderProcess.snakeShellRequiresSeal(wetWall, false));
        assertTrue(BuilderProcess.snakeShellRequiresSeal(Blocks.WATER.defaultBlockState()
                .setValue(BlockStateProperties.LEVEL, 4), true));
        assertTrue(BuilderProcess.snakeShellRequiresSeal(Blocks.AIR.defaultBlockState(), true));
        assertFalse(BuilderProcess.snakeShellRequiresSeal(Blocks.STONE.defaultBlockState(), false));
    }

    @Test
    public void narrowExcavationsUseOrdinaryClearingWithoutAnOutOfBoundsSnakeCorridor() {
        for (int narrow = 1; narrow <= 2; narrow++) {
            assertEquals(1, BuilderProcess.effectiveAreaBreakSize(3, true, narrow, 30));
            assertEquals(1, BuilderProcess.effectiveAreaBreakSize(3, true, 30, narrow));
            assertEquals(1, BuilderProcess.effectiveAreaBreakSize(3, true, narrow, narrow));
        }
        assertEquals(3, BuilderProcess.effectiveAreaBreakSize(3, true, 3, 3));
        assertEquals(3, BuilderProcess.effectiveAreaBreakSize(3, true, 20, 30));
        assertEquals(3, BuilderProcess.effectiveAreaBreakSize(3, false, 1, 1));
        assertEquals(1, BuilderProcess.effectiveAreaBreakSize(1, true, 30, 30));
    }

    @Test
    public void ordinaryHeadSwingsMustUseTheSameBoundaryGuardAsCleanupSwings() {
        for (Direction face : Direction.values()) {
            assertTrue(BuilderProcess.snakeAreaFootprintInsideBounds(new BlockPos(1, 1, 1), face,
                    0, 2, 0, 2, 0, 2));
            assertFalse(BuilderProcess.snakeAreaFootprintInsideBounds(new BlockPos(0, 0, 0), face,
                    0, 2, 0, 2, 0, 2));
        }
        assertFalse("two-high selection must not mine its roof", BuilderProcess.snakeAreaFootprintInsideBounds(
                new BlockPos(1, 1, 1), Direction.NORTH, 0, 2, 0, 1, 0, 2));
        assertFalse("one-high selection must not mine its floor", BuilderProcess.snakeAreaFootprintInsideBounds(
                new BlockPos(1, 0, 1), Direction.NORTH, 0, 2, 0, 0, 0, 2));
    }
}
