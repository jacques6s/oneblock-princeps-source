/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.elytra;

import princeps.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Pure local-plan geometry used to feed void flight into the regular Elytra render pipeline. */
final class VoidFlightRenderGeometry {

    private VoidFlightRenderGeometry() {}

    static List<BetterBlockPos> directCorridor(BlockPos player,
                                                BlockPos destination,
                                                int cruiseY,
                                                double lookahead) {
        ArrayList<BetterBlockPos> corridor = new ArrayList<>(2);
        addDistinct(corridor, player.getX(), cruiseY, player.getZ());

        final double dx = destination.getX() - player.getX();
        final double dz = destination.getZ() - player.getZ();
        final double distance = Math.hypot(dx, dz);
        if (distance == 0.0D || lookahead <= 0.0D) {
            return List.copyOf(corridor);
        }

        // VOID_CRUISE really steers straight toward the destination column. Show that real plan, but only over
        // the same local horizon as the normal Elytra path. Extending to a far destination creates the rejected
        // screen-sized diagonal/V and is not how the standard visiblePath window behaves.
        final double scale = Math.min(1.0D, lookahead / distance);
        int endX = player.getX() + (int) (dx * scale);
        int endZ = player.getZ() + (int) (dz * scale);
        if (endX == player.getX() && endZ == player.getZ()) {
            // Keep a very small positive lookahead renderable without ever jumping to the far destination.
            if (Math.abs(dx) >= Math.abs(dz)) {
                endX += Integer.signum((int) dx);
            } else {
                endZ += Integer.signum((int) dz);
            }
        }
        addDistinct(corridor, endX, cruiseY, endZ);
        return List.copyOf(corridor);
    }

    private static void addDistinct(List<BetterBlockPos> points, int x, int y, int z) {
        if (!points.isEmpty()) {
            BetterBlockPos last = points.get(points.size() - 1);
            if (last.getX() == x && last.getY() == y && last.getZ() == z) {
                return;
            }
        }
        points.add(new BetterBlockPos(x, y, z));
    }
}
