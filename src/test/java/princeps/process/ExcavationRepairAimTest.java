package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class ExcavationRepairAimTest {
    private static final AABB FULL = new AABB(0, 0, 0, 1, 1, 1);
    private static BlockState AIR;
    private static BlockState WATER;
    private static BlockState STONE;
    private static BlockState COBBLESTONE;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
        COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    }

    @Test
    public void theObservedSouthFaceCanBeRayVisibleWhileTheBodyStillStraddlesItsPlane() {
        Object world = new Object();
        BlockPos support = new BlockPos(93, -54, 69);
        var face = face(support, Direction.SOUTH, FULL);
        ExcavationRepairAim aim = new ExcavationRepairAim();
        int oldStarts = 0;
        boolean previousVisible = false;
        // Actual large-water end-cycle z values. The old gate could select a repair as soon as the south face
        // became visible, then lose that face again below z=70. This is a geometry replay, not a fluid simulation.
        for (double z : new double[] {70.045, 70.039, 70.022, 69.997, 69.967, 70.019}) {
            Vec3 body = new Vec3(96.6, -54, z);
            Vec3 eye = body.add(0, 1.62, 0);
            Vec3 justInsideSouthFace = new Vec3(93.5, -53.5, 70 - 0.000001);
            BlockHitResult hit = Shapes.block().clip(eye, justInsideSouthFace, support);
            assertNotNull("the full support block remains ray reachable", hit);
            boolean visible = hit.getDirection() == Direction.SOUTH;
            assertEquals("the exposed south face changes exactly at the z=70 plane", z > 70, visible);
            assertTrue(eye.distanceTo(hit.getLocation()) < 4.5);
            if (visible && !previousVisible) oldStarts++;
            previousVisible = visible;
            assertFalse("a grazing repair must not preempt the walk before the body clears the face plane",
                    aim.mayAim(world, face, body, 0.6, true, visible));
        }
        assertEquals("the old visibility-only gate starts the same repair twice in this short loop", 2, oldStarts);
        assertTrue(aim.heldFace(world, BlockPos.of(face.target())).isEmpty());
    }

    @Test
    public void aStartedRepairKeepsAimingWhileCurrentDriftReducesTheMarginButNotFaceVisibility() {
        Object world = new Object();
        var face = face(new BlockPos(93, -54, 69), Direction.SOUTH, FULL);
        ExcavationRepairAim aim = new ExcavationRepairAim();
        assertTrue(aim.mayAim(world, face, new Vec3(96.6, -54, 70.31), 0.6, true, true));
        for (double z : new double[] {70.25, 70.18, 70.10, 70.02, 70.001}) {
            assertTrue("the same live face retains the repair while the body is still on its exposed side",
                    aim.mayAim(world, face, new Vec3(96.6, -54, z), 0.6, true, true));
            assertEquals(face, aim.heldFace(world, BlockPos.of(face.target())).orElseThrow());
        }
    }

    @Test
    public void allFourHorizontalFacesUseTheActualBodyWidthForTheirStartMargin() {
        Object world = new Object();
        BlockPos support = new BlockPos(93, -54, 69);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var face = face(support, side, FULL);
            for (double width : new double[] {0.6, 1.0, 0.2}) {
                ExcavationRepairAim aim = new ExcavationRepairAim();
                assertFalse(aim.mayAim(world, face, body(face, width / 2 - 0.0001), width, true, true));
                assertTrue(aim.mayAim(world, face, body(face, width / 2 + 0.0001), width, true, true));
                assertTrue(aim.mayAim(world, face, body(face, 0.01), width, true, true));
            }
        }
    }

    @Test
    public void zeroOrNegativeClearanceEndsTheHoldAndRequiresAFreshFullMargin() {
        Object world = new Object();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var face = face(new BlockPos(93, -54, 69), side, FULL);
            for (double lostClearance : new double[] {0, -0.01}) {
                ExcavationRepairAim aim = new ExcavationRepairAim();
                assertTrue(aim.mayAim(world, face, body(face, 0.31), 0.6, true, true));
                assertFalse(aim.mayAim(world, face, body(face, lostClearance), 0.6, true, true));
                assertTrue(aim.heldFace(world, BlockPos.of(face.target())).isEmpty());
                assertFalse("crossing back by a millimetre must not immediately preempt navigation again",
                        aim.mayAim(world, face, body(face, 0.001), 0.6, true, true));
            }
        }
    }

    @Test
    public void anEquivalentRecomputedCandidatePreservesTheHeldFace() {
        Object world = new Object();
        BlockPos support = new BlockPos(93, -54, 69);
        var original = face(support, Direction.SOUTH, FULL);
        ExcavationRepairAim aim = new ExcavationRepairAim();
        assertTrue(aim.mayAim(world, original, body(original, 0.31), 0.6, true, true));
        var recomputed = face(new BlockPos(93, -54, 69), Direction.SOUTH, new AABB(0, 0, 0, 1, 1, 1));
        assertTrue("fresh geometry and eye-height calculations must not turn an identical face into a new request",
                aim.mayAim(world, recomputed, body(recomputed, 0.1), 0.6, true, true));
    }

    @Test
    public void changingCandidateTargetOrSupportStateCannotBorrowTheOldHold() {
        Object world = new Object();
        var original = face(new BlockPos(93, -54, 69), Direction.SOUTH, FULL);
        for (var changed : new ExcavationRepairAim.Face[] {
                new ExcavationRepairAim.Face(original.target(), original.support(), original.side(),
                        WATER.setValue(BlockStateProperties.LEVEL, 2), STONE, FULL),
                new ExcavationRepairAim.Face(original.target(), original.support(), original.side(),
                        WATER, COBBLESTONE, FULL)
        }) {
            ExcavationRepairAim aim = new ExcavationRepairAim();
            assertTrue(aim.mayAim(world, original, body(original, 0.31), 0.6, true, true));
            assertFalse(aim.mayAim(world, changed, body(changed, 0.1), 0.6, true, true));
            assertTrue(aim.heldFace(world, BlockPos.of(original.target())).isEmpty());
        }
    }

    @Test
    public void aDifferentTargetSupportOrSideNeedsItsOwnStartMargin() {
        Object world = new Object();
        BlockPos support = new BlockPos(93, -54, 69);
        var original = face(support, Direction.SOUTH, FULL);
        for (var changed : new ExcavationRepairAim.Face[] {
                face(support.east(), Direction.SOUTH, FULL),
                face(support, Direction.NORTH, FULL),
                new ExcavationRepairAim.Face(original.target(), support.east().asLong(), Direction.SOUTH,
                        WATER, STONE, FULL)
        }) {
            ExcavationRepairAim aim = new ExcavationRepairAim();
            assertTrue(aim.mayAim(world, original, body(original, 0.31), 0.6, true, true));
            assertFalse("the margin credit belongs only to the precise held target/support/face",
                    aim.mayAim(world, changed, body(changed, 0.1), 0.6, true, true));
        }
    }

    @Test
    public void matchingAndUnrelatedObservationsPreserveTheHoldButChangedOwnedCellsRevokeIt() {
        Object world = new Object();
        var face = face(new BlockPos(93, -54, 69), Direction.SOUTH, FULL);
        for (boolean targetChanges : new boolean[] {false, true}) {
            ExcavationRepairAim aim = new ExcavationRepairAim();
            assertTrue(aim.mayAim(world, face, body(face, 0.31), 0.6, true, true));
            aim.observe(BlockPos.of(face.target()), WATER);
            aim.observe(BlockPos.of(face.support()), STONE);
            aim.observe(new BlockPos(100, -54, 100), AIR);
            assertTrue(aim.mayAim(world, face, body(face, 0.1), 0.6, true, true));
            aim.observe(BlockPos.of(targetChanges ? face.target() : face.support()), COBBLESTONE);
            assertTrue(aim.heldFace(world, BlockPos.of(face.target())).isEmpty());
        }
    }

    @Test
    public void changingWorldIdentityInvalidatesTheHeldFaceEvenAtTheSameCoordinates() {
        Object firstWorld = new Object();
        Object secondWorld = new Object();
        var face = face(new BlockPos(93, -54, 69), Direction.SOUTH, FULL);
        ExcavationRepairAim aim = new ExcavationRepairAim();
        assertTrue(aim.mayAim(firstWorld, face, body(face, 0.31), 0.6, true, true));
        assertFalse(aim.mayAim(secondWorld, face, body(face, 0.1), 0.6, true, true));
        assertTrue(aim.heldFace(secondWorld, BlockPos.of(face.target())).isEmpty());
    }

    @Test
    public void losingReachOrTheSupportDoesNotKeepAnInvalidRepairAlive() {
        Object world = new Object();
        var face = face(new BlockPos(93, -54, 69), Direction.SOUTH, FULL);
        ExcavationRepairAim aim = new ExcavationRepairAim();
        assertTrue(aim.mayAim(world, face, body(face, 0.31), 0.6, true, true));
        assertFalse(aim.mayAim(world, face, body(face, 0.1), 0.6, true, false));
        assertTrue(aim.heldFace(world, BlockPos.of(face.target())).isEmpty());
        var noSupport = new ExcavationRepairAim.Face(face.target(), face.support(), face.side(), WATER, AIR, FULL);
        assertFalse(aim.mayAim(world, noSupport, body(face, 0.31), 0.6, true, true));
        assertFalse(aim.mayAim(null, face, body(face, 0.31), 0.6, true, true));
        assertFalse(aim.mayAim(world, null, body(face, 0.31), 0.6, true, true));
    }

    @Test
    public void dryAndVerticalRepairsKeepTheirExistingReachabilityRule() {
        Object world = new Object();
        BlockPos support = new BlockPos(93, -54, 69);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var face = face(support, side, FULL);
            ExcavationRepairAim aim = new ExcavationRepairAim();
            assertTrue(aim.mayAim(world, face, body(face, 0.01), 0.6, false, true));
            assertFalse(aim.mayAim(world, face, body(face, 0.01), 0.6, false, false));
        }
        for (Direction side : new Direction[] {Direction.UP, Direction.DOWN}) {
            var face = face(support, side, FULL);
            ExcavationRepairAim aim = new ExcavationRepairAim();
            Vec3 body = new Vec3(96.6, -54, 69.9);
            assertTrue(aim.mayAim(world, face, body, 0.6, true, true));
            assertFalse(aim.mayAim(world, face, body, 0.6, true, false));
        }
    }

    @Test
    public void horizontalFacePlanesComeFromTheLocalSupportShapeRatherThanTheWholeBlockCube() {
        Object world = new Object();
        AABB partial = new AABB(0.125, 0, 0.25, 0.875, 1, 0.75);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var face = face(new BlockPos(93, -54, 69), side, partial);
            ExcavationRepairAim aim = new ExcavationRepairAim();
            assertFalse(aim.mayAim(world, face, body(face, 0.2999), 0.6, true, true));
            assertTrue(aim.mayAim(world, face, body(face, 0.3001), 0.6, true, true));
            assertTrue(aim.mayAim(world, face, body(face, 0.01), 0.6, true, true));
        }
    }

    @Test
    public void heldFaceIsScopedToItsWorldAndTargetAndClearRequiresANewStart() {
        Object world = new Object();
        var face = face(new BlockPos(93, -54, 69), Direction.SOUTH, FULL);
        ExcavationRepairAim aim = new ExcavationRepairAim();
        assertTrue(aim.mayAim(world, face, body(face, 0.31), 0.6, true, true));
        assertTrue(aim.heldFace(world, new BlockPos(100, -54, 100)).isEmpty());
        assertEquals(face, aim.heldFace(world, BlockPos.of(face.target())).orElseThrow());
        aim.clear();
        assertTrue(aim.heldFace(world, BlockPos.of(face.target())).isEmpty());
        assertFalse(aim.mayAim(world, face, body(face, 0.1), 0.6, true, true));
        assertTrue(aim.mayAim(world, face, body(face, 0.31), 0.6, true, true));
        assertTrue(aim.heldFace(new Object(), BlockPos.of(face.target())).isEmpty());
    }

    private static ExcavationRepairAim.Face face(BlockPos support, Direction side, AABB bounds) {
        return new ExcavationRepairAim.Face(support.relative(side).asLong(), support.asLong(), side,
                WATER, STONE, bounds);
    }

    private static Vec3 body(ExcavationRepairAim.Face face, double clearance) {
        BlockPos support = BlockPos.of(face.support());
        AABB bounds = face.bounds();
        return switch (face.side()) {
            case WEST -> new Vec3(support.getX() + bounds.minX - clearance, support.getY(), support.getZ() + 0.5);
            case EAST -> new Vec3(support.getX() + bounds.maxX + clearance, support.getY(), support.getZ() + 0.5);
            case NORTH -> new Vec3(support.getX() + 0.5, support.getY(), support.getZ() + bounds.minZ - clearance);
            case SOUTH -> new Vec3(support.getX() + 0.5, support.getY(), support.getZ() + bounds.maxZ + clearance);
            default -> throw new IllegalArgumentException("this fixture helper describes horizontal face clearance");
        };
    }
}
