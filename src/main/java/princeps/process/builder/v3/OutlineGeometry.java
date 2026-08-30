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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The one definition of a block's clickable geometry used by the V3 planner.
 *
 * <p>Vanilla's block crosshair uses {@link BlockState#getShape}, commonly called the outline or selection shape. It
 * does <em>not</em> use the collision shape. That distinction is visible on the blocks follow-up actions care about:
 * a repeater and a torch have no collision at all but both have a small outline that can be right-clicked; a slab and
 * an open trapdoor have outlines that occupy only part of their cell. Treating any of those as a full cube proves an
 * aim point Vanilla will not hit.
 *
 * <p>This class therefore keeps three rules in one place:
 *
 * <ol>
 *   <li>Ask the real state for {@code getShape(world, pos, CollisionContext.empty())}.</li>
 *   <li>Keep every component box returned by {@link VoxelShape#toAabbs()}, rather than replacing a compound shape by
 *       its bounding box and aiming through a hole.</li>
 *   <li>Accept an aim only when Vanilla's own {@link BlockGetter#clip} with {@link ClipContext.Block#OUTLINE} returns
 *       the intended cell and face.</li>
 * </ol>
 *
 * <p>An empty shape and a shape that cannot be evaluated both produce no candidates. There is deliberately no
 * full-cube fallback: "unproven" is safe, while a fabricated clickable face is an irreversible wrong click.
 */
public final class OutlineGeometry {

    /** Deterministic component-box order. {@code VoxelShape} is stable today; the plan must stay stable if that
     * implementation detail changes. */
    private static final Comparator<AABB> BOX_ORDER = Comparator
            .comparingDouble((AABB box) -> box.minY)
            .thenComparingDouble(box -> box.minX)
            .thenComparingDouble(box -> box.minZ)
            .thenComparingDouble(box -> box.maxY)
            .thenComparingDouble(box -> box.maxX)
            .thenComparingDouble(box -> box.maxZ);

    private OutlineGeometry() {
    }

    /**
     * Result of one outline ray.
     *
     * @param evaluable false when some outline on the traversed ray threw while being evaluated
     * @param hit       the first block hit, or {@code null} for a genuine miss or an unevaluable ray
     */
    public record Ray(boolean evaluable, BlockHitResult hit) {

        /** Did this ray end on exactly the intended block face? */
        public boolean hits(BlockPos cell, Direction face) {
            return this.evaluable && this.hit != null
                    && this.hit.getBlockPos().equals(cell)
                    && this.hit.getDirection() == face;
        }
    }

    /** The real outline's component boxes in cell-local coordinates, deterministically ordered. */
    public static List<AABB> localParts(BlockGetter world, BlockPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        return localParts(world, pos, world.getBlockState(pos));
    }

    /**
     * The outline of {@code state} as though it occupied {@code pos}.
     *
     * <p>The read-only overlay matters for PLACE -> INTERACT: while that action group is being compiled the repeater
     * is not yet in the predicted world, but both its neighbour-sensitive shape query and the final ray must see it.
     */
    public static List<AABB> localParts(BlockGetter world, BlockPos pos, BlockState state) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        BlockGetter shapedWorld = withState(world, pos, state);
        final VoxelShape shape;
        try {
            shape = state.getShape(shapedWorld, pos, CollisionContext.empty());
        } catch (RuntimeException ignored) {
            return List.of();
        }
        if (shape == null || shape.isEmpty()) {
            return List.of();
        }
        List<AABB> parts = new ArrayList<>(shape.toAabbs());
        parts.removeIf(OutlineGeometry::degenerate);
        parts.sort(BOX_ORDER);
        return List.copyOf(parts);
    }

    /** The outline's component boxes moved into world coordinates. */
    public static List<AABB> worldParts(BlockGetter world, BlockPos pos) {
        return moveToWorld(localParts(world, pos), pos);
    }

    /** The outline of a not-yet-committed state, moved into world coordinates. */
    public static List<AABB> worldParts(BlockGetter world, BlockPos pos, BlockState state) {
        return moveToWorld(localParts(world, pos, state), pos);
    }

    /** Centre of one actual component-box face, in world coordinates. */
    public static Vec3 faceCentre(BlockPos pos, AABB local, Direction face) {
        double x = pos.getX() + (local.minX + local.maxX) * 0.5D;
        double y = pos.getY() + (local.minY + local.maxY) * 0.5D;
        double z = pos.getZ() + (local.minZ + local.maxZ) * 0.5D;
        switch (face.getAxis()) {
            case X -> x = pos.getX() + (face == Direction.EAST ? local.maxX : local.minX);
            case Y -> y = pos.getY() + (face == Direction.UP ? local.maxY : local.minY);
            case Z -> z = pos.getZ() + (face == Direction.SOUTH ? local.maxZ : local.minZ);
        }
        return new Vec3(x, y, z);
    }

    /** Vanilla OUTLINE ray over the supplied world. A failure is not a miss and is marked unevaluable. */
    public static Ray clip(BlockGetter world, Vec3 from, Vec3 to) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        try {
            BlockHitResult hit = world.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            return hit == null || hit.getType() != HitResult.Type.BLOCK
                    ? new Ray(true, null) : new Ray(true, hit);
        } catch (RuntimeException ignored) {
            return new Ray(false, null);
        }
    }

    /** Vanilla OUTLINE ray with one not-yet-committed state overlaid at its future cell. */
    public static Ray clip(BlockGetter world, BlockPos pos, BlockState state, Vec3 from, Vec3 to) {
        return clip(withState(world, pos, state), from, to);
    }

    /** Does the quantised look hit exactly {@code target}/{@code face} within {@code reach}? */
    public static boolean hits(BlockGetter world, Vec3 eye, Rotation rotation, double reach,
                               BlockPos target, Direction face) {
        Vec3 look = RotationUtils.calcLookDirectionFromRotation(rotation);
        Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        return clip(world, eye, end).hits(target, face);
    }

    /** As {@link #hits(BlockGetter, Vec3, Rotation, double, BlockPos, Direction)}, with a future state overlaid. */
    public static boolean hits(BlockGetter world, BlockPos pos, BlockState state, Vec3 eye, Rotation rotation,
                               double reach, Direction face) {
        Vec3 look = RotationUtils.calcLookDirectionFromRotation(rotation);
        Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        return clip(world, pos, state, eye, end).hits(pos, face);
    }

    /**
     * Is the segment clear strictly before a point on a target surface?
     *
     * <p>The end is pulled one ulp toward the eye. This preserves the old half-open "path to the face" question while
     * using outline shapes for intervening partial blocks. The separate full-reach ray remains the authority on which
     * cell and face the crosshair actually hits.
     */
    public static boolean clearBeforeSurface(BlockGetter world, Vec3 eye, Vec3 surface) {
        Vec3 end = new Vec3(
                Math.nextAfter(surface.x, eye.x),
                Math.nextAfter(surface.y, eye.y),
                Math.nextAfter(surface.z, eye.z));
        Ray ray = clip(world, eye, end);
        return ray.evaluable() && ray.hit() == null;
    }

    /**
     * A read-only block getter that changes one state and delegates every other read.
     *
     * <p>Package-private so tests can assert the exact same view used by the ray without making another overlay type.
     */
    static BlockGetter withState(BlockGetter delegate, BlockPos pos, BlockState state) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        BlockPos fixed = pos.immutable();
        return new BlockGetter() {

            @Override
            public BlockEntity getBlockEntity(BlockPos query) {
                return query.equals(fixed) ? null : delegate.getBlockEntity(query);
            }

            @Override
            public BlockState getBlockState(BlockPos query) {
                return query.equals(fixed) ? state : delegate.getBlockState(query);
            }

            @Override
            public FluidState getFluidState(BlockPos query) {
                return query.equals(fixed) ? state.getFluidState() : delegate.getFluidState(query);
            }

            @Override
            public int getHeight() {
                return delegate.getHeight();
            }

            @Override
            public int getMinY() {
                return delegate.getMinY();
            }
        };
    }

    private static List<AABB> moveToWorld(List<AABB> local, BlockPos pos) {
        if (local.isEmpty()) {
            return List.of();
        }
        List<AABB> moved = new ArrayList<>(local.size());
        for (AABB box : local) {
            moved.add(box.move(pos.getX(), pos.getY(), pos.getZ()));
        }
        return List.copyOf(moved);
    }

    private static boolean degenerate(AABB box) {
        return box == null || box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ;
    }
}
