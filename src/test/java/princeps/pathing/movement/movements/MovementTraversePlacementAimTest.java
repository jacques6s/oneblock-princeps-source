/*
 * This file is part of Princeps.
 */
package princeps.pathing.movement.movements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;
import princeps.api.pathing.PlacementLicence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Safety invariants that let a multi-tick bridge aim replace the old one-tick snap. */
public class MovementTraversePlacementAimTest {

    @Test
    public void backplaceNeverMovesBeforeAimAndNeverUnlatchesAfterwards() {
        assertFalse(MovementTraverse.backplaceMotionReady(false, false));
        assertTrue(MovementTraverse.backplaceMotionReady(false, true));
        assertTrue(MovementTraverse.backplaceMotionReady(true, false));
    }

    @Test
    public void onlyTheSupportFaceAdjacentToTheMissingFloorMayClick() {
        BlockPos support = new BlockPos(10, 20, 30);
        BlockPos missingFloor = support.east();

        assertTrue(MovementTraverse.backplaceFaceReachesTarget(support, Direction.EAST, missingFloor));
        assertFalse(MovementTraverse.backplaceFaceReachesTarget(support, Direction.UP, missingFloor));
        assertFalse(MovementTraverse.backplaceFaceReachesTarget(support, Direction.EAST, support.west()));
    }

    @Test
    public void excavationBridgeClickCannotSurviveCancellationOrReplayOnANewRoute() {
        BlockPos floor = new BlockPos(10, -61, 30);
        PlacementLicence original = PlacementLicence.excavationBridge(floor.asLong());
        assertTrue(MovementTraverse.excavationBridgeStillOwned(original, original, floor));
        assertFalse(MovementTraverse.excavationBridgeStillOwned(original, PlacementLicence.UNRESTRICTED, floor));
        assertFalse(MovementTraverse.excavationBridgeStillOwned(original, PlacementLicence.NONE, floor));
        assertFalse(MovementTraverse.excavationBridgeStillOwned(original,
                PlacementLicence.excavationBridge(floor.asLong()), floor));
        assertFalse(MovementTraverse.excavationBridgeStillOwned(original, original, floor.east()));
        assertFalse(MovementTraverse.excavationBridgeStillOwned(PlacementLicence.UNRESTRICTED,
                PlacementLicence.UNRESTRICTED, floor));
    }
}
