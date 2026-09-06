/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import princeps.api.utils.Rotation;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** A chosen mining action can keep a usable current ray without electing work from the camera direction. */
final class ExcavationMiningLook {
    static Optional<Rotation> retain(boolean excavating, Rotation current, BlockPos target,
                                     Direction requiredFace, boolean areaTool,
                                     Function<Rotation, HitResult> ray,
                                     Predicate<Direction> areaFaceAllowed) {
        if (!excavating || current == null || target == null || (areaTool && requiredFace == null)) {
            return Optional.empty();
        }
        // Re-ray from the current eye/reach. A cached crosshair from before movement is not a promise that the
        // same view still intersects this target, and matching only a block would allow the wrong Shard plane.
        HitResult result = ray.apply(current);
        if (!BuilderProcess.snakeMiningHitMatches(result, target, requiredFace)) return Optional.empty();
        if (areaTool && !areaFaceAllowed.test(((BlockHitResult) result).getDirection())) return Optional.empty();
        return Optional.of(current);
    }
}
