/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** A complete one-high selection is an open surface, not a standing-height tunnel. */
final class ShallowExcavationPolicy {
    private ShallowExcavationPolicy() { }

    static boolean surfaceMode(boolean excavation, int completeHeight) {
        return excavation && completeHeight == 1;
    }

    static boolean keepRoofAir(ExcavationRepairPolicy.Bounds bounds, int x, int y, int z, BlockState state) {
        return bounds.minY() == bounds.maxY() && y == bounds.maxY() + 1
                && x >= bounds.minX() && x <= bounds.maxX() && z >= bounds.minZ() && z <= bounds.maxZ()
                && state.isAir();
    }

    static boolean roofBlocksStanding(BlockState roof, BlockGetter world, BlockPos at) {
        // A standing body in the excavation cell extends 0.8 blocks into this head cell. A high trapdoor whose
        // lower surface is above that envelope is not a low ceiling merely because its collision shape is nonempty.
        return Shapes.joinIsNotEmpty(roof.getCollisionShape(world, at),
                Shapes.box(0.2D, 0.0D, 0.2D, 0.8D, 0.8D, 0.8D), BooleanOp.AND);
    }

    /** A failed search is a safe blocked result, not permission to excavate a fixed roof outside the job. */
    static boolean stopAfterFailedRoute(boolean shallow, boolean failed, boolean completedThisTick,
                                         boolean coveredWorkRemains) {
        return shallow && failed && !completedThisTick && coveredWorkRemains;
    }
}
