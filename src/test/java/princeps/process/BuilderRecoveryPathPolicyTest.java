/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderRecoveryPathPolicyTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void ordinaryPlacementStillYieldsWhenItWouldCloseTheActiveRoute() {
        assertTrue(BuilderProcess.placementTargetMustYieldToActivePath(false, true));
    }

    @Test
    public void recoveryKeepsOwnershipWhileItsRoutePassesThroughTheEmptyTarget() {
        assertFalse(BuilderProcess.placementTargetMustYieldToActivePath(true, true));
    }

    @Test
    public void aRouteThatDoesNotUseTheTargetNeverForcesAYield() {
        assertFalse(BuilderProcess.placementTargetMustYieldToActivePath(false, false));
        assertFalse(BuilderProcess.placementTargetMustYieldToActivePath(true, false));
    }

    @Test
    public void recoveryMayPlanPastThePlayersCurrentTinyTargetOverlap() {
        // Exact live deadlock from run 729cabf1: feet=(89.465,-59,114.277), player half-width=0.3.
        // The player clips 0.023 blocks into z=113, so a current-pose placement check is false even though moving to
        // the centre of the same adjacent stance makes the target perfectly placeable.
        AABB playerAtLivePose = new AABB(
                89.465D - 0.3D, -59.0D, 114.277D - 0.3D,
                89.465D + 0.3D, -57.2D, 114.277D + 0.3D);
        AABB pistonTarget = new AABB(89.0D, -59.0D, 113.0D, 90.0D, -58.0D, 114.0D);
        assertTrue(playerAtLivePose.intersects(pistonTarget));
        assertTrue(BuilderProcess.recoveryCandidateMayBePlanned(true));
    }

    @Test
    public void recoveryStillRejectsAnObstructionThatRemainsAfterThePlayerMoves() {
        assertFalse(BuilderProcess.recoveryCandidateMayBePlanned(false));
    }

    @Test
    public void adjacentOffCenterSelfOverlapBootstrapsCenteringBeforeStanceSimulation() {
        assertTrue(BuilderProcess.recoveryNeedsSelfClearanceBootstrap(
                true, false, true, false, true));
    }

    @Test
    public void centeringBootstrapDoesNotHideRealObstructionsOrInventUnsafeStances() {
        assertFalse(BuilderProcess.recoveryNeedsSelfClearanceBootstrap(
                true, false, true, false, false));  // obstruction remains after the player moves
        assertFalse(BuilderProcess.recoveryNeedsSelfClearanceBootstrap(
                true, true, true, false, true));   // already centred: use the ordinary stance search
        assertFalse(BuilderProcess.recoveryNeedsSelfClearanceBootstrap(
                false, false, true, false, true)); // diagonal/distant pose must be routed normally
        assertFalse(BuilderProcess.recoveryNeedsSelfClearanceBootstrap(
                true, false, false, false, true)); // never centre on unsafe footing
        assertFalse(BuilderProcess.recoveryNeedsSelfClearanceBootstrap(
                true, false, true, true, true));   // no self obstruction exists
    }
}
