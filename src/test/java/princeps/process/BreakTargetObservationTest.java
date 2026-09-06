/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class BreakTargetObservationTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void removedClaimIsObservedOnceIndependentlyOfNearbyScanRadius() {
        var observation = new BreakTargetObservation();
        Object world = new Object();
        BlockPos reachableOutsideRadiusOne = new BlockPos(4, 20, 0);
        observation.claim(world, reachableOutsideRadiusOne, Blocks.STONE.defaultBlockState());
        AtomicInteger reads = new AtomicInteger();
        assertTrue(observation.observe(world, pos -> true, pos -> {
            assertEquals(reachableOutsideRadiusOne, pos);
            reads.incrementAndGet();
            return Blocks.AIR.defaultBlockState();
        }));
        assertFalse(observation.observe(world, pos -> true, pos -> {
            fail("completed claims are not repeatedly credited");
            return Blocks.AIR.defaultBlockState();
        }));
        assertEquals(1, reads.get());
    }

    @Test
    public void unchangedRejectedReplacedAndUnloadedTargetsGiveNoRemovalCredit() {
        var observation = new BreakTargetObservation();
        Object world = new Object();
        observation.claim(world, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.STONE.defaultBlockState()));
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.WATER.defaultBlockState()));
        assertFalse(observation.observe(world, pos -> false, pos -> {
            fail("an unloaded world's fallback AIR must never be queried");
            return Blocks.AIR.defaultBlockState();
        }));
        assertTrue(observation.observe(world, pos -> true, pos -> Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void anotherWorldOrFreshJobCannotCompleteThePreviousClaim() {
        var observation = new BreakTargetObservation();
        Object world = new Object();
        observation.claim(world, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        assertFalse(observation.observe(new Object(), pos -> true, pos -> Blocks.AIR.defaultBlockState()));
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.AIR.defaultBlockState()));
        observation.claim(world, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        observation.clear();
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void existingAirAndChangingLookOrClaimAloneAreNotObservedWork() {
        var observation = new BreakTargetObservation();
        Object world = new Object();
        observation.claim(world, BlockPos.ZERO, Blocks.AIR.defaultBlockState());
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.AIR.defaultBlockState()));
        observation.claim(world, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        observation.claim(world, new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.STONE.defaultBlockState()));
    }

    @Test
    public void constructionWrongBlockRemovalIsWorkBeforeTheReplacementIsPlaced() {
        var observation = new BreakTargetObservation();
        Object world = new Object();
        // Replacing dirt with stone first requires a real removal. This is progress, not completed construction.
        observation.claim(world, BlockPos.ZERO, Blocks.DIRT.defaultBlockState());
        assertTrue(observation.observe(world, pos -> true, pos -> Blocks.AIR.defaultBlockState()));
    }
}
