/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.api.utils;

import princeps.api.PrincepsAPI;
import princeps.api.IPrinceps;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;

/**
 * @author Brady
 * @since 9/25/2018
 */
public final class RotationUtils {

    /**
     * Constant that a degree value is multiplied by to get the equivalent radian value
     */
    public static final double DEG_TO_RAD = Math.PI / 180.0;
    public static final float DEG_TO_RAD_F = (float) DEG_TO_RAD;

    /**
     * Constant that a radian value is multiplied by to get the equivalent degree value
     */
    public static final double RAD_TO_DEG = 180.0 / Math.PI;
    public static final float RAD_TO_DEG_F = (float) RAD_TO_DEG;

    /**
     * Offsets from the root block position to the center of each side.
     */
    private static final Vec3[] BLOCK_SIDE_MULTIPLIERS = new Vec3[]{
            new Vec3(0.5, 0, 0.5), // Down
            new Vec3(0.5, 1, 0.5), // Up
            new Vec3(0.5, 0.5, 0), // North
            new Vec3(0.5, 0.5, 1), // South
            new Vec3(0, 0.5, 0.5), // West
            new Vec3(1, 0.5, 0.5)  // East
    };

    private RotationUtils() {}

    /**
     * Calculates the rotation from BlockPos<sub>dest</sub> to BlockPos<sub>orig</sub>
     *
     * @param orig The origin position
     * @param dest The destination position
     * @return The rotation from the origin to the destination
     */
    public static Rotation calcRotationFromCoords(BlockPos orig, BlockPos dest) {
        return calcRotationFromVec3d(new Vec3(orig.getX(), orig.getY(), orig.getZ()), new Vec3(dest.getX(), dest.getY(), dest.getZ()));
    }

    /**
     * Wraps the target angles to a relative value from the current angles. This is done by
     * subtracting the current from the target, normalizing it, and then adding the current
     * angles back to it.
     *
     * @param current The current angles
     * @param target  The target angles
     * @return The wrapped angles
     */
    public static Rotation wrapAnglesToRelative(Rotation current, Rotation target) {
        if (current.yawIsReallyClose(target)) {
            return new Rotation(current.getYaw(), target.getPitch());
        }
        return target.subtract(current).normalize().add(current);
    }

    /**
     * Calculates the rotation from Vec<sub>dest</sub> to Vec<sub>orig</sub> and makes the
     * return value relative to the specified current rotations.
     *
     * @param orig    The origin position
     * @param dest    The destination position
     * @param current The current rotations
     * @return The rotation from the origin to the destination
     * @see #wrapAnglesToRelative(Rotation, Rotation)
     */
    public static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest, Rotation current) {
        return wrapAnglesToRelative(current, calcRotationFromVec3d(orig, dest));
    }

    /**
     * Calculates the rotation from Vec<sub>dest</sub> to Vec<sub>orig</sub>
     *
     * @param orig The origin position
     * @param dest The destination position
     * @return The rotation from the origin to the destination
     */
    private static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest) {
        double[] delta = {orig.x - dest.x, orig.y - dest.y, orig.z - dest.z};
        double yaw = Mth.atan2(delta[0], -delta[2]);
        double dist = Math.sqrt(delta[0] * delta[0] + delta[2] * delta[2]);
        double pitch = Mth.atan2(delta[1], dist);
        return new Rotation(
                (float) (yaw * RAD_TO_DEG),
                (float) (pitch * RAD_TO_DEG)
        );
    }

    /**
     * Calculates the look vector for the specified yaw/pitch rotations.
     *
     * @param rotation The input rotation
     * @return Look vector for the rotation
     */
    public static Vec3 calcLookDirectionFromRotation(Rotation rotation) {
        float flatZ = Mth.cos((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
        float flatX = Mth.sin((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
        float pitchBase = -Mth.cos(-rotation.getPitch() * DEG_TO_RAD_F);
        float pitchHeight = Mth.sin(-rotation.getPitch() * DEG_TO_RAD_F);
        return new Vec3(flatX * pitchBase, pitchHeight, flatZ * pitchBase);
    }

    @Deprecated
    public static Vec3 calcVec3dFromRotation(Rotation rotation) {
        return calcLookDirectionFromRotation(rotation);
    }

    /**
     * @param ctx Context for the viewing entity
     * @param pos The target block position
     * @return The optional rotation
     * @see #reachable(IPlayerContext, BlockPos, double)
     */
    public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos) {
        return reachable(ctx, pos, false);
    }

    public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos, boolean wouldSneak) {
        return reachable(ctx, pos, ctx.playerController().getBlockReachDistance(), wouldSneak);
    }

    /**
     * Determines if the specified entity is able to reach the center of any of the sides
     * of the specified block. It first checks if the block center is reachable, and if so,
     * that rotation will be returned. If not, it will return the first center of a given
     * side that is reachable. The return type will be {@link Optional#empty()} if the entity is
     * unable to reach any of the sides of the block.
     *
     * @param ctx                Context for the viewing entity
     * @param pos                The target block position
     * @param blockReachDistance The block reach distance of the entity
     * @return The optional rotation
     */
    public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos, double blockReachDistance) {
        return reachable(ctx, pos, blockReachDistance, false);
    }

    public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos, double blockReachDistance, boolean wouldSneak) {
        if (PrincepsAPI.getSettings().remainWithExistingLookDirection.value && ctx.isLookingAt(pos)) {
            /*
             * why add 0.0001?
             * to indicate that we actually have a desired pitch
             * the way we indicate that the pitch can be whatever and we only care about the yaw
             * is by setting the desired pitch to the current pitch
             * setting the desired pitch to the current pitch + 0.0001 means that we do have a desired pitch, it's
             * just what it currently is
             *
             * or if you're a normal person literally all this does it ensure that we don't nudge the pitch to a normal level
             */
            Rotation hypothetical = ctx.playerRotations().add(new Rotation(0, 0.0001F));
            if (wouldSneak) {
                // the concern here is: what if we're looking at it now, but as soon as we start sneaking we no longer are
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), hypothetical, blockReachDistance, true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(pos)) {
                    return Optional.of(hypothetical); // yes, if we sneaked we would still be looking at the block
                }
            } else {
                return Optional.of(hypothetical);
            }
        }
        // Humanized aim: before falling back to the exact geometric centroid (a superhuman dead-center tell that an
        // anticheat can catch by replaying the dig raytrace and seeing every hit land on the shape centroid), aim at
        // the point on the block NEAREST our current look — a human pulls to the near edge, not across to dead center
        // (minimal mouse travel) — plus a small deterministic per-block scatter. Guarded by the same reachableOffset
        // raytrace, so it is only used when the ray still lands on `pos`; otherwise we fall through to the exact
        // centroid / face centers below, unchanged. Predictions go through peekRotationExact (in reachableOffset), so
        // reach/place stays byte-consistent. Only breaking/interacting use this; placement-against has its own aim.
        if (PrincepsAPI.getSettings().humanizedLook.value && !wouldSneak) {
            Optional<Rotation> humanized = reachableHumanized(ctx, pos, blockReachDistance);
            if (humanized.isPresent()) {
                return humanized;
            }
        }

        Optional<Rotation> possibleRotation = reachableCenter(ctx, pos, blockReachDistance, wouldSneak);
        //System.out.println("center: " + possibleRotation);
        if (possibleRotation.isPresent()) {
            return possibleRotation;
        }

        BlockState state = ctx.world().getBlockState(pos);
        VoxelShape shape = state.getShape(ctx.world(), pos);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        for (Vec3 sideOffset : BLOCK_SIDE_MULTIPLIERS) {
            double xDiff = shape.min(Direction.Axis.X) * sideOffset.x + shape.max(Direction.Axis.X) * (1 - sideOffset.x);
            double yDiff = shape.min(Direction.Axis.Y) * sideOffset.y + shape.max(Direction.Axis.Y) * (1 - sideOffset.y);
            double zDiff = shape.min(Direction.Axis.Z) * sideOffset.z + shape.max(Direction.Axis.Z) * (1 - sideOffset.z);
            possibleRotation = reachableOffset(ctx, pos, new Vec3(pos.getX(), pos.getY(), pos.getZ()).add(xDiff, yDiff, zDiff), blockReachDistance, wouldSneak);
            if (possibleRotation.isPresent()) {
                return possibleRotation;
            }
        }
        return Optional.empty();
    }

    /**
     * Determines if the specified entity is able to reach the specified block with
     * the given offsetted position. The return type will be {@link Optional#empty()} if
     * the entity is unable to reach the block with the offset applied.
     *
     * @param ctx                Context for the viewing entity
     * @param pos                The target block position
     * @param offsetPos          The position of the block with the offset applied.
     * @param blockReachDistance The block reach distance of the entity
     * @return The optional rotation
     */
    public static Optional<Rotation> reachableOffset(IPlayerContext ctx, BlockPos pos, Vec3 offsetPos, double blockReachDistance, boolean wouldSneak) {
        Vec3 eyes = wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.player().getEyePosition(1.0F);
        Rotation rotation = calcRotationFromVec3d(eyes, offsetPos, ctx.playerRotations());
        Rotation actualRotation = PrincepsAPI.getProvider().getPrincepsForPlayer(ctx.player()).getLookBehavior().getAimProcessor().peekRotationExact(rotation);
        HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRotation, blockReachDistance, wouldSneak);
        //System.out.println(result);
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            if (((BlockHitResult) result).getBlockPos().equals(pos)) {
                return Optional.of(rotation);
            }
            if (ctx.world().getBlockState(pos).getBlock() instanceof BaseFireBlock && ((BlockHitResult) result).getBlockPos().equals(pos.below())) {
                return Optional.of(rotation);
            }
        }
        return Optional.empty();
    }

    /**
     * Determines if the specified entity is able to reach the specified block where it is
     * looking at the direct center of it's hitbox.
     *
     * @param ctx                Context for the viewing entity
     * @param pos                The target block position
     * @param blockReachDistance The block reach distance of the entity
     * @return The optional rotation
     */
    public static Optional<Rotation> reachableCenter(IPlayerContext ctx, BlockPos pos, double blockReachDistance, boolean wouldSneak) {
        return reachableOffset(ctx, pos, VecUtils.calculateBlockCenter(ctx.world(), pos), blockReachDistance, wouldSneak);
    }

    /**
     * Human-like reach: aim at the point on {@code pos} nearest to where the player is ALREADY looking (least mouse
     * travel — a person pulls to the near edge of a block, not across it to the dead center), with a small
     * deterministic per-block scatter so repeated breaks don't all reconstruct to the exact shape centroid. Returns
     * empty (→ caller falls back to the exact center) if the resulting ray would not land on {@code pos}, so
     * correctness is never traded for realism. Deterministic per block (stable across ticks) so the reach raytrace
     * never flips hit&lt;-&gt;miss from tick jitter.
     */
    private static Optional<Rotation> reachableHumanized(IPlayerContext ctx, BlockPos pos, double blockReachDistance) {
        final Vec3 eyes = ctx.player().getEyePosition(1.0F);
        final Vec3 center = VecUtils.calculateBlockCenter(ctx.world(), pos);

        // Closest point on our current look ray to the block center → where the block sits relative to our aim.
        final Vec3 look = calcLookDirectionFromRotation(ctx.playerRotations());
        final double t = Mth.clamp(center.subtract(eyes).dot(look), 0.0, blockReachDistance);
        final Vec3 rayPt = eyes.add(look.scale(t));

        // Small deterministic per-block scatter (splitmix on the packed pos) so different blocks land on different
        // in-face points, but a given block is stable tick-to-tick.
        final long h = mix(pos.asLong());
        final double nx = ((h & 0xFFFFL) / 65535.0) - 0.5;         // [-0.5, 0.5]
        final double ny = (((h >>> 16) & 0xFFFFL) / 65535.0) - 0.5;
        final double nz = (((h >>> 32) & 0xFFFFL) / 65535.0) - 0.5;

        // Clamp the aim well inside the block (inset from every edge) so the guard raytrace still lands on pos.
        final double margin = 0.18;
        final double x = Mth.clamp(rayPt.x + nx * 0.22, pos.getX() + margin, pos.getX() + 1.0 - margin);
        final double y = Mth.clamp(rayPt.y + ny * 0.22, pos.getY() + margin, pos.getY() + 1.0 - margin);
        final double z = Mth.clamp(rayPt.z + nz * 0.22, pos.getZ() + margin, pos.getZ() + 1.0 - margin);

        return reachableOffset(ctx, pos, new Vec3(x, y, z), blockReachDistance, false);
    }

    /** SplitMix64 finalizer — cheap, well-distributed hash of the packed block position (deterministic, no state). */
    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    @Deprecated
    public static Optional<Rotation> reachable(LocalPlayer entity, BlockPos pos, double blockReachDistance) {
        return reachable(entity, pos, blockReachDistance, false);
    }

    @Deprecated
    public static Optional<Rotation> reachable(LocalPlayer entity, BlockPos pos, double blockReachDistance, boolean wouldSneak) {
        IPrinceps princeps = PrincepsAPI.getProvider().getPrincepsForPlayer(entity);
        IPlayerContext ctx = princeps.getPlayerContext();
        return reachable(ctx, pos, blockReachDistance, wouldSneak);
    }

    @Deprecated
    public static Optional<Rotation> reachableOffset(Entity entity, BlockPos pos, Vec3 offsetPos, double blockReachDistance, boolean wouldSneak) {
        Vec3 eyes = wouldSneak ? RayTraceUtils.inferSneakingEyePosition(entity) : entity.getEyePosition(1.0F);
        Rotation rotation = calcRotationFromVec3d(eyes, offsetPos, new Rotation(entity.getYRot(), entity.getXRot()));
        HitResult result = RayTraceUtils.rayTraceTowards(entity, rotation, blockReachDistance, wouldSneak);
        //System.out.println(result);
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            if (((BlockHitResult) result).getBlockPos().equals(pos)) {
                return Optional.of(rotation);
            }
            if (entity.level().getBlockState(pos).getBlock() instanceof BaseFireBlock && ((BlockHitResult) result).getBlockPos().equals(pos.below())) {
                return Optional.of(rotation);
            }
        }
        return Optional.empty();
    }

    @Deprecated
    public static Optional<Rotation> reachableCenter(Entity entity, BlockPos pos, double blockReachDistance, boolean wouldSneak) {
        return reachableOffset(entity, pos, VecUtils.calculateBlockCenter(entity.level(), pos), blockReachDistance, wouldSneak);
    }
}
