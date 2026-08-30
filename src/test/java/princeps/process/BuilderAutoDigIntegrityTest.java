/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import org.junit.Test;
import org.junit.BeforeClass;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
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
}
