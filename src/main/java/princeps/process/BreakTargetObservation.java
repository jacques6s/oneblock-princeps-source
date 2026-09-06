/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Function;
import java.util.function.Predicate;

/** One previously claimed solid cell, observed directly even when it lies outside the nearby scan radius. */
final class BreakTargetObservation {
    private Object world;
    private BlockPos target;

    void claim(Object world, BlockPos target, BlockState state) {
        clear();
        if (world != null && target != null && state != null && !state.isAir()) {
            this.world = world;
            this.target = target.immutable();
        }
    }

    boolean observe(Object currentWorld, Predicate<BlockPos> loaded, Function<BlockPos, BlockState> stateAt) {
        if (target == null) return false;
        if (world != currentWorld) {
            clear();
            return false;
        }
        if (!loaded.test(target)) return false;
        BlockState current = stateAt.apply(target);
        if (current == null || !current.isAir()) return false;
        // A loaded AIR observation ends this claim exactly once. A look/click request is never completion.
        clear();
        return true;
    }

    void clear() {
        world = null;
        target = null;
    }
}
