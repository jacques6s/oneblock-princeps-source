/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** One ordinary mining action survives aim/flow changes until its block or owning slice changes. */
final class SnakeCleanupWork {
    private BetterBlockPos head;
    private int bandTop;
    private BetterBlockPos target;
    private BlockState state;
    private Vec3 hitPoint;

    BetterBlockPos retained(BetterBlockPos selectedHead, int selectedBand,
                            Function<BetterBlockPos, BlockState> current,
                            Predicate<BetterBlockPos> stillOwed) {
        if (target != null && (selectedBand != bandTop || !head.equals(selectedHead)
                || !stillOwed.test(target) || !state.equals(current.apply(target)))) {
            clear();
        }
        return target;
    }

    void choose(BetterBlockPos selectedHead, int selectedBand, BetterBlockPos selected, BlockState current) {
        if (!selected.equals(target) || !current.equals(state)) hitPoint = null;
        head = selectedHead;
        bandTop = selectedBand;
        target = selected;
        state = current;
    }

    void clear() {
        head = null;
        target = null;
        state = null;
        hitPoint = null;
    }

    Optional<Rotation> rotation(Vec3 eye, Rotation current, double reach,
                                Function<Rotation, HitResult> ray,
                                Supplier<Optional<Rotation>> findHit) {
        return rotation(eye, current, reach, ray, ray, findHit);
    }

    Optional<Rotation> rotation(Vec3 eye, Rotation current, double reach,
                                Function<Rotation, HitResult> currentRay,
                                Function<Rotation, HitResult> ray,
                                Supplier<Optional<Rotation>> findHit) {
        if (target == null) return Optional.empty();
        Optional<Rotation> held = ExcavationMiningLook.retain(true, current, target, null, false,
                currentRay, ignored -> false);
        if (held.isPresent()) return held;
        if (hitPoint != null && eye.distanceToSqr(hitPoint) <= reach * reach) {
            Rotation toward = RotationUtils.calcRotationFromVec3d(eye, hitPoint, current);
            if (hitsTarget(ray.apply(toward))) return Optional.of(toward);
        }
        // A changed eye or occluder invalidates this hit, not the chosen block. Reacquire only on that block.
        hitPoint = null;
        Optional<Rotation> fresh = findHit.get();
        if (fresh.isEmpty()) return Optional.empty();
        HitResult result = ray.apply(fresh.get());
        if (!hitsTarget(result)) return Optional.empty();
        BlockHitResult hit = (BlockHitResult) result;
        Direction face = hit.getDirection();
        // Just inside the actual outline, so ray precision at a fence/slab face cannot alternate hit/miss.
        hitPoint = hit.getLocation().add(-face.getStepX() * 0.002D,
                -face.getStepY() * 0.002D, -face.getStepZ() * 0.002D);
        return fresh;
    }

    private boolean hitsTarget(HitResult result) {
        return result instanceof BlockHitResult hit && result.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }
}
