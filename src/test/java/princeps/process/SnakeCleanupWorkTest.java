/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real vanilla block outlines reproduce the ordinary-cut ray that remains usable outside the Shard face cone. */
public class SnakeCleanupWorkTest {
    private static final BetterBlockPos HEAD = new BetterBlockPos(1, 65, 0);
    private static final BetterBlockPos TARGET = new BetterBlockPos(0, 65, 0);
    private static final int BAND_TOP = 66;
    private static final double REACH = 4.5D;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void anotherValidCurrentHitOnTheChosenBlockDoesNotReturnToTheRememberedPoint() {
        SnakeCleanupWork work = selected(Blocks.STONE.defaultBlockState());
        VoxelShape shape = net.minecraft.world.phys.shapes.Shapes.block();
        Vec3 eye = new Vec3(2.5, 65.65, 0.5);
        Rotation centered = RotationUtils.calcRotationFromVec3d(eye, new Vec3(0.5, 65.5, 0.5), new Rotation(0, 0));
        assertTrue(work.rotation(eye, new Rotation(0, 0), REACH, aimed -> ray(shape, eye, aimed),
                () -> Optional.of(centered)).isPresent());
        Rotation current = RotationUtils.calcRotationFromVec3d(eye, new Vec3(0.5, 65.8, 0.7), centered);
        assertTrue(BuilderProcess.snakeMiningHitMatches(ray(shape, eye, current), TARGET, null));
        assertSame("the chosen cell stays fixed, but a valid current ray need not recenter on its old hit point",
                current, work.rotation(eye, current, REACH, aimed -> ray(shape, eye, aimed), () -> {
                    fail("a matching current ray does not need another target search");
                    return Optional.empty();
                }).orElseThrow());
    }

    @Test
    public void lateralFlowKeepsTheActualWetSlabHitWithoutReelectingAHitEveryTick() {
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        SnakeCleanupWork work = selected(slab);
        VoxelShape shape = slab.getShape(EmptyBlockGetter.INSTANCE, TARGET);
        AtomicInteger freshHits = new AtomicInteger();
        Rotation previous = new Rotation(0.0F, 0.0F);
        for (int tick = 0; tick < 80; tick++) {
            Vec3 eye = new Vec3(2.0D + Math.sin(tick * 0.1D) * 0.15D, 65.9D, -0.5D);
            assertFalse("this body cannot hold the selected NORTH Shard face cone",
                    BuilderProcess.snakeWithinFaceAngle(eye.x - 0.5D, eye.z, Direction.NORTH));
            assertEquals(TARGET, work.retained(HEAD, BAND_TOP, at -> slab, at -> true));
            Rotation current = previous;
            boolean currentRayStillHits = BuilderProcess.snakeMiningHitMatches(ray(shape, eye, current), TARGET, null);
            Optional<Rotation> rotation = work.rotation(eye, current, REACH,
                    aimed -> ray(shape, eye, aimed), () -> {
                        freshHits.incrementAndGet();
                        return Optional.of(RotationUtils.calcRotationFromVec3d(eye, new Vec3(0.5D, 65.25D, 0.5D), current));
                    });
            assertTrue(rotation.isPresent());
            BlockHitResult hit = (BlockHitResult) ray(shape, eye, rotation.get());
            assertEquals(TARGET, hit.getBlockPos());
            // Ordinary mining owns the block, not a Shard plane. A held ray may drift from the slab's side to its
            // top while remaining correct; forcing EAST here would require the unnecessary recentering under test.
            assertTrue(hit.getDirection() == Direction.EAST || hit.getDirection() == Direction.UP);
            if (currentRayStillHits) assertSame(current, rotation.get());
            assertTrue(BuilderProcess.snakeMiningHitMatches(hit, TARGET, null));
            assertFalse(BuilderProcess.snakeMiningHitMatches(hit, TARGET, Direction.NORTH));
            previous = rotation.get();
        }
        assertEquals("a still-valid ray is held; losing it returns to the one remembered hit", 1, freshHits.get());
    }

    @Test
    public void actualWaterloggedFenceOutlineRemainsMineableFromADriftingBody() {
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        SnakeCleanupWork work = selected(fence);
        VoxelShape shape = fence.getShape(EmptyBlockGetter.INSTANCE, TARGET);
        for (int tick = 0; tick < 40; tick++) {
            Vec3 eye = new Vec3(2.1D, 65.8D, -0.6D + tick * 0.01D);
            Optional<Rotation> rotation = work.rotation(eye, new Rotation(0, 0), REACH,
                    aimed -> ray(shape, eye, aimed), () -> Optional.of(
                            RotationUtils.calcRotationFromVec3d(eye, new Vec3(0.5D, 65.5D, 0.5D), new Rotation(0, 0))));
            assertTrue(rotation.isPresent());
            assertTrue(BuilderProcess.snakeMiningHitMatches(ray(shape, eye, rotation.get()), TARGET, null));
            assertEquals(TARGET, work.retained(HEAD, BAND_TOP, at -> fence, at -> true));
        }
    }

    @Test
    public void occlusionInvalidatesOnlyTheHitWhileObservedWaterInvalidatesTheMiningTarget() {
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        SnakeCleanupWork work = selected(slab);
        Vec3 eye = new Vec3(2.0D, 65.9D, -0.5D);
        VoxelShape shape = slab.getShape(EmptyBlockGetter.INSTANCE, TARGET);
        Rotation raw = RotationUtils.calcRotationFromVec3d(eye, new Vec3(0.5D, 65.25D, 0.5D), new Rotation(0, 0));
        assertTrue(work.rotation(eye, raw, REACH, aimed -> ray(shape, eye, aimed), () -> Optional.of(raw)).isPresent());
        assertFalse(work.rotation(eye, raw, REACH, aimed -> null, Optional::empty).isPresent());
        assertEquals("a temporary obstacle cannot elect a different leftover", TARGET,
                work.retained(HEAD, BAND_TOP, at -> slab, at -> true));
        assertTrue(work.rotation(eye, raw, REACH, aimed -> ray(shape, eye, aimed), () -> Optional.of(raw)).isPresent());
        assertTrue(BuilderProcess.snakeSourceBlockNeedsBreak(slab));
        assertFalse(BuilderProcess.snakeSourceReadyForPlug(slab, true));
        BlockState water = Blocks.WATER.defaultBlockState();
        assertNull(work.retained(HEAD, BAND_TOP, at -> water, at -> false));
        assertFalse(BuilderProcess.snakeSourceBlockNeedsBreak(water));
        assertTrue("the existing source-plug pass now owns the exposed water", BuilderProcess.snakeSourceReadyForPlug(water, true));
    }

    @Test
    public void changedBlockSliceOrBandExplicitlyReleasesAnOrdinaryAction() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertNull(selected(stone).retained(HEAD, BAND_TOP, at -> Blocks.GRAVEL.defaultBlockState(), at -> true));
        assertNull(selected(stone).retained(new BetterBlockPos(2, 65, 0), BAND_TOP, at -> stone, at -> true));
        assertNull(selected(stone).retained(HEAD, BAND_TOP - 3, at -> stone, at -> true));
        assertNull(selected(stone).retained(HEAD, BAND_TOP, at -> stone, at -> false));
    }

    @Test
    public void ordinaryWaterWorkCanSwingButFallingEntryAndWrongCellStillCannot() {
        assertTrue(BuilderProcess.snakeMiningPostureReady(false, true, true, false));
        assertTrue(BuilderProcess.snakeMiningPostureReady(true, false, true, false));
        assertFalse(BuilderProcess.snakeMiningPostureReady(false, false, true, false));
        assertFalse(BuilderProcess.snakeMiningPostureReady(false, true, true, true));
        assertFalse(BuilderProcess.snakeMiningPostureReady(false, true, false, false));
        BlockHitResult other = new BlockHitResult(new Vec3(2, 65.5, 0.5), Direction.WEST,
                new BetterBlockPos(2, 65, 0), false);
        assertFalse(BuilderProcess.snakeMiningHitMatches(other, TARGET, null));
        assertFalse(BuilderProcess.snakeMiningHitMatches(null, TARGET, null));
    }

    @Test
    public void dryFullNineRetainsWideToolGeometryAndItsSelectedPlane() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertFalse(BuilderProcess.snakeHeadNeedsIndividualBreak(stone, EmptyBlockGetter.INSTANCE, HEAD));
        assertFalse(BuilderProcess.snakeSourceBlockNeedsBreak(stone));
        assertTrue(BuilderProcess.snakeAreaFootprintInsideBounds(HEAD, Direction.NORTH, 0, 2, 64, 66, 0, 2));
        assertTrue(BuilderProcess.snakeWithinFaceAngle(0.0D, -1.0D, Direction.NORTH));
        assertTrue(BuilderProcess.snakeMiningPostureReady(true, false, false, false));
        BlockHitResult matching = new BlockHitResult(new Vec3(1.5, 65.5, 0), Direction.NORTH, HEAD, false);
        assertTrue(BuilderProcess.snakeMiningHitMatches(matching, HEAD, Direction.NORTH));
        assertFalse(BuilderProcess.snakeMiningHitMatches(matching, HEAD, Direction.EAST));
        assertFalse("a wet body's ordinary permission must not reach the wide tool",
                BuilderProcess.snakeMiningPostureReady(false, true, false, false));
    }

    private static SnakeCleanupWork selected(BlockState state) {
        SnakeCleanupWork work = new SnakeCleanupWork();
        work.choose(HEAD, BAND_TOP, TARGET, state);
        return work;
    }

    private static HitResult ray(VoxelShape shape, Vec3 eye, Rotation rotation) {
        Vec3 end = eye.add(RotationUtils.calcLookDirectionFromRotation(rotation).scale(REACH));
        return shape.clip(eye, end, TARGET);
    }
}
