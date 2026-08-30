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

package princeps.process.builder.v3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Amanatides-Woo voxel traversal over a solid bitset — the planner's replacement for {@code level.clip}.
 *
 * <p>Two reasons it is not {@code level.clip}. It is five to ten times faster, and the dry run casts on the order of
 * 30 rays per cell across every cell of the schematic before the bot moves; and it touches no client state, so the
 * planner can run off the main thread. {@code level.clip} needs a {@code Level}, and a {@code Level} the planner
 * would have to fabricate is exactly the {@code PredictedLevel extends Level} that this design refused to build.
 *
 * <p>The trade is deliberate and must be understood by anyone reading a miss: this caster is <em>full-cube</em>. It
 * answers "which cell does the ray enter first that is marked solid, and through which face" — it does not intersect
 * the real {@code VoxelShape}, so a ray that grazes the empty half of a slab's cell reports a hit here and would miss
 * in vanilla. The bitset therefore marks only cells whose collision shape is a full cube
 * ({@link PredictedWorld#isSolidFullCube}); partial shapes are handled at the aim-point level, where the face
 * rectangle already comes from the real {@code AABB}. The regression test that keeps this honest casts 10 000 random
 * rays against a loaded chunk and demands the same position and face as {@code level.clip}.
 *
 * <p>Deterministic by construction: no epsilon that depends on iteration order, no randomness, ties on the axis
 * comparison broken in a fixed X, Y, Z order. Two runs of the same plan must produce the same trace byte for byte,
 * and a ray caster that flips a face on a tie is a nondeterminism source that would take days to find.
 */
public final class GridRay {

    private GridRay() {
    }

    /** Anything the caster can walk: one bit per cell, addressed in world coordinates. */
    @FunctionalInterface
    public interface SolidTest {

        /** Is this cell a full solid cube? Out-of-bounds must answer {@code false}, never throw. */
        boolean isSolid(int x, int y, int z);
    }

    /**
     * Where a ray stopped.
     *
     * @param x        cell hit
     * @param y        cell hit
     * @param z        cell hit
     * @param face     the face the ray entered through, i.e. the one a click would land on; {@code null} when the ray
     *                 started inside a solid cell, which the caller must treat as an obstruction, not a click target
     * @param point    the exact entry point in world coordinates
     * @param distance distance from the ray origin to {@link #point}
     */
    public record Hit(int x, int y, int z, Direction face, Vec3 point, double distance) {

        /** The vanilla shape, for handing a planned ray to code that speaks {@code BlockHitResult}. */
        public BlockHitResult toBlockHitResult() {
            // BlockHitResult's constructor will not take a null Direction, and an inside-hit has no entered face by
            // definition. The `inside` flag is what actually carries the meaning here; UP is a placeholder, and any
            // caller that reads the direction off an inside-hit is already using this result for something it must
            // not be used for (a click), which is why Hit.face is null rather than quietly some plausible face.
            boolean inside = this.face == null;
            return new BlockHitResult(this.point, inside ? Direction.UP : this.face,
                    new BlockPos(this.x, this.y, this.z), inside);
        }
    }

    /**
     * Cast from {@code from} to {@code to} and return the first solid cell entered, or {@code null} for a clean miss.
     *
     * <p>The segment is half-open at {@code to}: a solid cell whose boundary the ray only touches at the very end
     * does not count as a hit, so aiming at a point on a face does not report that face's own block as blocking the
     * path to it. That single convention is what lets one caster answer both "is the line of sight clear" and "what
     * would the crosshair be on".
     */
    public static Hit cast(SolidTest solid, Vec3 from, Vec3 to) {
        return traverse(solid, from, to);
    }

    /**
     * As {@link #cast(SolidTest, Vec3, Vec3)} but along a direction for a bounded distance, for the occlusion probes
     * that do not have a materialised end point.
     */
    public static Hit cast(SolidTest solid, Vec3 from, Vec3 direction, double maxDistance) {
        // Normalised, so maxDistance is always in blocks no matter what the caller's vector length happens to be. The
        // look vectors this is fed come out of RotationUtils already unit, for which normalize() is the identity.
        Vec3 unit = direction.normalize();
        return traverse(solid, from,
                new Vec3(from.x + unit.x * maxDistance, from.y + unit.y * maxDistance, from.z + unit.z * maxDistance));
    }

    /**
     * Is the open segment between the two points free of solid cells? The occlusion question in its own right, so the
     * common case does not allocate a {@link Hit} it throws away.
     */
    public static boolean clear(SolidTest solid, Vec3 from, Vec3 to) {
        // The Hit is only ever built on the hit branch, so the clear case — which is the common one, and the one the
        // dry run casts thousands of times per cell — allocates nothing at all.
        return traverse(solid, from, to) == null;
    }

    /**
     * The traversal itself. Standard Amanatides-Woo: keep, per axis, the ray parameter {@code t} at which the next
     * cell boundary on that axis is crossed, step whichever is smallest, repeat.
     *
     * <p>{@code t} is parametrised over the segment, so {@code t == 1} is exactly {@code to} and the half-open
     * convention is the single comparison {@code t >= 1 -> miss}. That is not an approximation: when the ray ends on a
     * cell boundary — which is precisely what aiming at a point on a face does — the numerator and the denominator of
     * that axis's {@code tMax} are the same subtraction on the same operands, so the quotient is exactly 1.0 and the
     * block behind the aim point is never reported as blocking the path to it. No epsilon is involved, and none may
     * be added.
     *
     * <p>That exactness is why each {@code tMax} is recomputed from the new cell index rather than advanced by the
     * textbook {@code += tDelta}. Accumulating 1/dx three times drifts by an ulp or two, and an aim point three blocks
     * away comes out at 0.9999999999999998 instead of 1.0 — which reads as a hit, which rejects the placement, for
     * every cell whose click face happens to be that far from the eye. One division per step buys the guarantee.
     */
    private static Hit traverse(SolidTest solid, Vec3 from, Vec3 to) {
        int x = Mth.floor(from.x);
        int y = Mth.floor(from.y);
        int z = Mth.floor(from.z);

        // Origin inside a solid cell. Reported as a hit with a null face rather than as a miss: every caller of this
        // is asking either "is my line of sight clear" or "where would my crosshair land", and for both of those an
        // eye buried in a block is an obstruction, not an unobstructed view of infinity.
        if (solid.isSolid(x, y, z)) {
            return new Hit(x, y, z, null, from, 0.0D);
        }

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        int stepX = dx > 0.0D ? 1 : dx < 0.0D ? -1 : 0;
        int stepY = dy > 0.0D ? 1 : dy < 0.0D ? -1 : 0;
        int stepZ = dz > 0.0D ? 1 : dz < 0.0D ? -1 : 0;

        // An axis the ray does not move along never produces a crossing; positive infinity keeps it out of every
        // minimum without a branch in the loop, and axis-aligned rays are the common case for UP/DOWN placements.
        double tMaxX = boundary(x, stepX, from.x, dx);
        double tMaxY = boundary(y, stepY, from.y, dy);
        double tMaxZ = boundary(z, stepZ, from.z, dz);

        // Every iteration advances exactly one cell along exactly one axis, so the Manhattan cell distance to the end
        // point is an exact upper bound on the iteration count. Using it rather than a fudged cap means a NaN
        // coordinate terminates the loop instead of hanging the planner, and it never truncates a legitimate ray.
        int budget = Math.abs(Mth.floor(to.x) - x) + Math.abs(Mth.floor(to.y) - y) + Math.abs(Mth.floor(to.z) - z) + 1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        for (int step = 0; step < budget; step++) {
            double t;
            Direction face;
            // Ties broken X, then Y, then Z, unconditionally. A ray through an exact edge or corner crosses two or
            // three boundaries at the same t; picking by anything order-dependent would make two runs of the same
            // plan disagree on a face, which is a nondeterminism source that costs days to track down.
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX;
                if (t >= 1.0D) {
                    return null;
                }
                x += stepX;
                tMaxX = boundary(x, stepX, from.x, dx);
                face = stepX > 0 ? Direction.WEST : Direction.EAST;
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY;
                if (t >= 1.0D) {
                    return null;
                }
                y += stepY;
                tMaxY = boundary(y, stepY, from.y, dy);
                face = stepY > 0 ? Direction.DOWN : Direction.UP;
            } else {
                t = tMaxZ;
                if (t >= 1.0D) {
                    return null;
                }
                z += stepZ;
                tMaxZ = boundary(z, stepZ, from.z, dz);
                face = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
            }
            if (solid.isSolid(x, y, z)) {
                Vec3 point = new Vec3(from.x + dx * t, from.y + dy * t, from.z + dz * t);
                return new Hit(x, y, z, face, point, length * t);
            }
        }
        return null;
    }

    /**
     * The ray parameter at which the cell {@code cell} is left along one axis, or positive infinity when the ray does
     * not move along it. Written as one subtraction over another so that the terminal case — {@code to} sitting
     * exactly on the boundary being tested — is bit-for-bit {@code a / a == 1.0}.
     */
    private static double boundary(int cell, int step, double origin, double delta) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return ((step > 0 ? cell + 1 : cell) - origin) / delta;
    }
}
