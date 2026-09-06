/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.Test;
import org.junit.BeforeClass;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ExcavationMiningLookTest {
    private static final BlockPos TARGET = new BlockPos(68, -49, 69);
    private static final double REACH = 4.5;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void aLowPitchRayAlreadyOnTheSelectedNorthFaceDoesNotRecenterDuringTheStep() {
        // The closed solid trace alternated ~1.5..1.95 degrees with the profile's exact 6-degree walking minimum.
        // Replay the real cube geometry through the same forward body travel, not a simulated game tick or packet.
        Rotation current = new Rotation(0, 1.65F);
        for (double z : new double[] {67.643, 67.770, 67.967, 68.198, 68.528}) {
            Vec3 eye = new Vec3(68.5, -48.38, z);
            HitResult hit = ray(eye, current, TARGET);
            assertTrue(BuilderProcess.snakeMiningHitMatches(hit, TARGET, Direction.NORTH));
            Rotation centered = RotationUtils.calcRotationFromVec3d(eye, new Vec3(68.5, -48.5, 69), current);
            assertTrue("face center asks for extra rotation although the actual ray is valid",
                    Math.abs(centered.getPitch() - current.getPitch()) > 1);
            assertSame(current, retain(current, Direction.NORTH, true, eye).orElseThrow());
        }
    }

    @Test
    public void allHorizontalDirectionsKeepTheirMatchingFaceAndRejectTheOtherFace() {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Vec3 eye = Vec3.atCenterOf(TARGET).add(side.getStepX() * 2.5, 0.25, side.getStepZ() * 2.5);
            Rotation current = RotationUtils.calcRotationFromVec3d(eye, Vec3.atCenterOf(TARGET), new Rotation(0, 0));
            assertSame(current, retain(current, side, true, eye).orElseThrow());
            assertTrue(retain(current, side.getOpposite(), true, eye).isEmpty());
        }
    }

    @Test
    public void aChangedTargetOrOccludingBlockRequiresANewAim() {
        Rotation current = new Rotation(0, 1.65F);
        Vec3 eye = new Vec3(68.5, -48.38, 67.77);
        assertTrue(ExcavationMiningLook.retain(true, current, TARGET.east(), Direction.NORTH, true,
                rot -> ray(eye, rot, TARGET), face -> true).isEmpty());
        assertTrue(ExcavationMiningLook.retain(true, current, TARGET, Direction.NORTH, true,
                rot -> ray(eye, rot, TARGET.north()), face -> true).isEmpty());
    }

    @Test
    public void lookingElsewhereOrBeingOutOfReachNeverKeepsTheOldAim() {
        assertTrue(retain(new Rotation(90, 0), Direction.NORTH, true, new Vec3(68.5, -48.38, 67.77)).isEmpty());
        assertTrue(retain(new Rotation(0, 0), Direction.NORTH, true, new Vec3(68.5, -48.38, 60)).isEmpty());
    }

    @Test
    public void aDifferentPredictedAngleHittingTheBlockDoesNotMakeTheActualViewUsable() {
        Vec3 eye = new Vec3(68.5, -48.38, 67.77);
        Rotation current = new Rotation(0, 30);
        Rotation predicted = new Rotation(0, 1.65F);
        assertTrue(BuilderProcess.snakeMiningHitMatches(ray(eye, predicted, TARGET), TARGET, Direction.NORTH));
        assertFalse(BuilderProcess.snakeMiningHitMatches(ray(eye, current, TARGET), TARGET, Direction.NORTH));
        assertTrue("a look processor prediction is not evidence that the current head already hits",
                retain(current, Direction.NORTH, true, eye).isEmpty());
    }

    @Test
    public void ordinaryMiningMayKeepAnyActuallyHitFaceWithoutBorrowingAreaPermission() {
        Rotation current = new Rotation(0, 1.65F);
        Vec3 eye = new Vec3(68.5, -48.38, 67.77);
        AtomicInteger areaChecks = new AtomicInteger();
        assertSame(current, ExcavationMiningLook.retain(true, current, TARGET, null, false,
                rot -> ray(eye, rot, TARGET), face -> { areaChecks.incrementAndGet(); return false; }).orElseThrow());
        assertEquals(0, areaChecks.get());
        assertTrue(ExcavationMiningLook.retain(true, current, TARGET, null, true,
                rot -> ray(eye, rot, TARGET), face -> true).isEmpty());
    }

    @Test
    public void aMatchingShardRayStillNeedsItsWholeFootprintInsideSelectionAndActiveBand() {
        Rotation current = new Rotation(0, 1.65F);
        Vec3 eye = new Vec3(68.5, -48.38, 67.77);
        assertSame(current, ExcavationMiningLook.retain(true, current, TARGET, Direction.NORTH, true,
                rot -> ray(eye, rot, TARGET), face -> BuilderProcess.snakeAreaFootprintInsideBounds(
                        TARGET, face, 67, 73, -50, -48, 67, 73)
                        && BuilderProcess.snakeAreaFootprintInsideActiveBand(TARGET, face, -50, -48)).orElseThrow());
        for (int narrowedMinX : new int[] {68, 69}) {
            assertTrue(ExcavationMiningLook.retain(true, current, TARGET, Direction.NORTH, true,
                    rot -> ray(eye, rot, TARGET), face -> BuilderProcess.snakeAreaFootprintInsideBounds(
                            TARGET, face, narrowedMinX, 73, -50, -48, 67, 73)).isEmpty());
        }
        assertTrue(ExcavationMiningLook.retain(true, current, TARGET, Direction.NORTH, true,
                rot -> ray(eye, rot, TARGET), face -> BuilderProcess.snakeAreaFootprintInsideActiveBand(
                        TARGET, face, -49, -48)).isEmpty());
        assertTrue("a fluid-plug veto must also defeat a geometrically matching face",
                ExcavationMiningLook.retain(true, current, TARGET, Direction.NORTH, true,
                        rot -> ray(eye, rot, TARGET), face -> false).isEmpty());
    }

    @Test
    public void constructionAndMalformedOrMissingCurrentRaysNeverAcquireTheExcavationHold() {
        Rotation current = new Rotation(0, 1.65F);
        Vec3 eye = new Vec3(68.5, -48.38, 67.77);
        assertTrue(ExcavationMiningLook.retain(false, current, TARGET, Direction.NORTH, true,
                rot -> { fail("construction must not even ray trace through this excavation policy"); return null; },
                face -> true).isEmpty());
        assertTrue(ExcavationMiningLook.retain(true, current, TARGET, Direction.NORTH, true,
                rot -> null, face -> true).isEmpty());
        assertTrue(ExcavationMiningLook.retain(true, null, TARGET, Direction.NORTH, true,
                rot -> { fail("missing current rotation is not a usable ray"); return null; }, face -> true).isEmpty());
    }

    private static Optional<Rotation> retain(Rotation current, Direction face, boolean area, Vec3 eye) {
        return ExcavationMiningLook.retain(true, current, TARGET, face, area,
                rotation -> ray(eye, rotation, TARGET), ignored -> true);
    }

    private static HitResult ray(Vec3 eye, Rotation rotation, BlockPos block) {
        return Shapes.block().clip(eye, eye.add(RotationUtils.calcLookDirectionFromRotation(rotation).scale(REACH)), block);
    }
}
