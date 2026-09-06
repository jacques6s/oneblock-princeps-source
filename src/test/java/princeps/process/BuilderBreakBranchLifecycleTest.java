/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/** Exercises the actual shared reset helper; it does not simulate build(), a world, or the game loop. */
public class BuilderBreakBranchLifecycleTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void replacingAPausedJobDropsItsYieldAndUnobservedRemoval() throws Exception {
        BuilderProcess builder = headlessBuilder();
        BreakBranchProgress progress = new BreakBranchProgress(120, 100);
        BreakTargetObservation observation = new BreakTargetObservation();
        install(builder, "breakBranchProgress", progress);
        install(builder, "breakTargetObservation", observation);
        Object world = new Object();
        observation.claim(world, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        for (int tick = 0; tick < 121; tick++) {
            progress.beginTick(false, false, false);
            progress.shouldYield(true, BlockPos.ZERO, Float.NaN);
        }
        assertEquals(100, progress.yieldRemaining());
        builder.pause();
        assertTrue(builder.isPaused());
        builder.resetBreakBranchTracking();
        assertTrue("reset does not itself resume the old job", builder.isPaused());
        assertEquals(0, progress.activeTick());
        assertEquals(0, progress.yieldRemaining());
        assertFalse(observation.observe(world, pos -> true, pos -> Blocks.AIR.defaultBlockState()));
        builder.resume();
        progress.beginTick(false, false, false);
        assertFalse(progress.shouldYield(true, BlockPos.ZERO, Float.NaN));
        assertEquals(1, progress.nonProgressTicks());
    }

    @Test
    public void repeatedTeardownResetCannotCarryMiningDamageIntoConstruction() throws Exception {
        BuilderProcess builder = headlessBuilder();
        BreakBranchProgress progress = new BreakBranchProgress(120, 100);
        install(builder, "breakBranchProgress", progress);
        install(builder, "breakTargetObservation", new BreakTargetObservation());
        progress.beginTick(false, false, false);
        assertFalse(progress.shouldYield(true, BlockPos.ZERO, 0.4F));
        progress.beginTick(false, false, false);
        assertFalse(progress.shouldYield(true, BlockPos.ZERO, 0.5F));
        builder.resetBreakBranchTracking();
        builder.resetBreakBranchTracking();
        progress.beginTick(false, false, false);
        assertFalse(progress.shouldYield(true, BlockPos.ZERO, 0.6F));
        assertEquals("the next job must acquire its own consecutive damage history", 1,
                progress.nonProgressTicks());
    }

    private static void install(BuilderProcess builder, String name, Object value) throws Exception {
        Field field = BuilderProcess.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(builder, value);
    }

    private static BuilderProcess headlessBuilder() throws Exception {
        // The real constructor needs a running client. Initialize only the fields consumed by the reset helper.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        return (BuilderProcess) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, BuilderProcess.class);
    }
}
