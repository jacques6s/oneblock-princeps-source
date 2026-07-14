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

    /**
     * A window of the FIXED planned cruise line from {@code anchor} to {@code destination} at {@code y}.
     * Every node is a pure function of (anchor, destination, index) — one per block of travel, like the
     * pathfinder's path nodes — so the geometry NEVER shifts with the player. Only the returned window
     * slides along the line ({@code back} nodes behind the player's projection onto it, {@code ahead}
     * in front), mirroring the standard visiblePath window {@code subList(near - 30, near + 100)}.
     * The red line therefore stands still in space exactly like the planned route in every other
     * dimension; drifting sideways or forward can never rotate or drag it.
     */
    static List<BetterBlockPos> plannedWindow(BlockPos anchor, BlockPos destination, int y,
                                              BlockPos player, int back, int ahead) {
        final double dx = destination.getX() - anchor.getX();
        final double dz = destination.getZ() - anchor.getZ();
        final double length = Math.hypot(dx, dz);
        final ArrayList<BetterBlockPos> window = new ArrayList<>();
        if (length == 0.0D) {
            window.add(new BetterBlockPos(destination.getX(), y, destination.getZ()));
            return List.copyOf(window);
        }
        final double ux = dx / length;
        final double uz = dz / length;
        final double along = (player.getX() - anchor.getX()) * ux + (player.getZ() - anchor.getZ()) * uz;
        final int near = (int) Math.max(0.0D, Math.min(along, length));
        final int last = (int) length;
        final int from = Math.max(0, near - back);
        final int to = Math.min(last, near + ahead);
        for (int i = from; i <= to; i++) {
            addDistinct(window,
                    anchor.getX() + (int) Math.round(ux * i),
                    y,
                    anchor.getZ() + (int) Math.round(uz * i));
        }
        if (to == last) {
            // The window reaches the plan's end: terminate exactly on the destination column.
            addDistinct(window, destination.getX(), y, destination.getZ());
        }
        return List.copyOf(window);
    }

    /** Horizontal distance of {@code player} from the anchor→destination line (blocks, always >= 0). */
    static double lateralOffset(BlockPos anchor, BlockPos destination, BlockPos player) {
        final double dx = destination.getX() - anchor.getX();
        final double dz = destination.getZ() - anchor.getZ();
        final double length = Math.hypot(dx, dz);
        final double px = player.getX() - anchor.getX();
        final double pz = player.getZ() - anchor.getZ();
        if (length == 0.0D) {
            return Math.hypot(px, pz);
        }
        return Math.abs(px * (dz / length) - pz * (dx / length));
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
