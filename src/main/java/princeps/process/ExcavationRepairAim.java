/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** Owns one excavation repair face while approaching and aiming, never a cached rotation or hotbar slot. */
final class ExcavationRepairAim {
    record Face(long target, long support, Direction side, BlockState targetState,
                BlockState supportState, AABB bounds) { }

    private Object heldWorld;
    private Face held;

    boolean mayAim(Object world, Face face, Vec3 body, double width, boolean wet, boolean reachable) {
        if (world == null || face == null || body == null || !(width > 0) || !Double.isFinite(width)
                || !reachable || face.supportState().isAir() || face.bounds() == null) {
            clear();
            return false;
        }
        boolean continuing = heldWorld == world && face.equals(held);
        if (!continuing) clear();
        if (wet && face.side().getAxis().isHorizontal()) {
            BlockPos support = BlockPos.of(face.support());
            AABB bounds = face.bounds();
            double clearance = switch (face.side()) {
                case WEST -> support.getX() + bounds.minX - body.x;
                case EAST -> body.x - (support.getX() + bounds.maxX);
                case NORTH -> support.getZ() + bounds.minZ - body.z;
                case SOUTH -> body.z - (support.getZ() + bounds.maxZ);
                default -> throw new IllegalStateException("horizontal repair face expected");
            };
            // At a grazing face a flowing corridor can carry the body back across the face plane before the
            // quarter-turn finishes. Let the current route carry the full body onto the exposed side first.
            // Once aiming, retain this exact face while it is still visible; reapplying the start margin each
            // tick would merely move the approach/aim oscillator to a different coordinate.
            if (!(clearance > 0) || (!continuing && clearance < width / 2)) {
                clear();
                return false;
            }
        }
        heldWorld = world;
        held = face;
        return true;
    }

    Optional<Face> heldFace(Object world, BlockPos target) {
        if (heldWorld != world) clear();
        return held != null && target != null && held.target() == target.asLong()
                ? Optional.of(held) : Optional.empty();
    }

    void observe(BlockPos pos, BlockState state) {
        if (held != null && ((pos.asLong() == held.target() && !held.targetState().equals(state))
                || (pos.asLong() == held.support() && !held.supportState().equals(state)))) clear();
    }

    void clear() { heldWorld = null; held = null; }
}
