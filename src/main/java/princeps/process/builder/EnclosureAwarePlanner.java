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

package princeps.process.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Builder V2 — enclosure-safe build ordering (pure geometry, no Minecraft world).
 *
 * <p>The reactive 0.3.x builder's worst live failure was <em>self-boxing</em>: building the perimeter of a layer
 * while standing inside it walls the bot in two blocks high, after which every remaining wall cell is unreachable and
 * the build stalls in a deferral avalanche (observed live on layer y=112). The structural cure is to never let a
 * placement disconnect the bot's standing region from the exterior: order the cells so the enclosing geometry is
 * built <em>from the outside, retreating toward an exit</em>, and only ever seal the last gap from the exit-connected
 * side.
 *
 * <p>This planner is deliberately world-free: it reasons about a voxel set (the schematic's solid target cells) plus
 * whatever is already solid in the world, and a predicate for which cells a foot can stand in. That makes it fully
 * unit-testable without a Minecraft {@code Level} — the exact geometry that stalled live (a one-wide wall ring) is a
 * regression fixture. The runtime executor consumes the returned order; deriving the concrete click for each cell is a
 * separate concern (the placement oracle).
 *
 * <p>Model. Space is the integer lattice. A cell is packed into a long via {@link #pack}. A cell is <em>solid</em>
 * when it is pre-existing world solid, or a target cell that has already been placed in the running order. A
 * <em>stance</em> for a target cell is a standable free cell within {@code reach} Chebyshev distance of it whose foot
 * component is connected — through free space — to the exterior (any standable free cell outside the schematic's
 * bounding shell). The planner emits an order in which every cell, at the moment it is placed, still has such a
 * stance, and placing it does not sever any not-yet-built cell's only exterior-connected stance. Cells that cannot be
 * ordered this way (genuinely only reachable from a sealed pocket) are returned in {@link Result#unreachable} rather
 * than silently dropped — the caller surfaces them honestly or plans a break-out, never skips them.
 */
public final class EnclosureAwarePlanner {

    private EnclosureAwarePlanner() {
    }

    /** 21-bit signed packing per axis (range ~[-1,048,576, 1,048,575]) — ample for any schematic near the anchor. */
    private static final int BITS = 21;
    private static final long MASK = (1L << BITS) - 1L;
    private static final int HALF = 1 << (BITS - 1);

    public static long pack(int x, int y, int z) {
        return ((long) (x + HALF) & MASK) << (2 * BITS)
                | ((long) (y + HALF) & MASK) << BITS
                | ((long) (z + HALF) & MASK);
    }

    public static int unpackX(long key) {
        return (int) ((key >>> (2 * BITS)) & MASK) - HALF;
    }

    public static int unpackY(long key) {
        return (int) ((key >>> BITS) & MASK) - HALF;
    }

    public static int unpackZ(long key) {
        return (int) (key & MASK) - HALF;
    }

    /** The environment the planner reasons over. All coordinates are absolute (world) block positions. */
    public interface World {
        /** True if this cell is solid in the world independent of the build (terrain, pre-existing blocks). */
        boolean isPreexistingSolid(int x, int y, int z);

        /**
         * True if a foot could occupy this cell and stand there, GIVEN the running solid predicate — i.e. the cell
         * itself is free, the cell above is free (headroom), and there is solid footing directly below. The planner
         * passes the current running-solid test so "solid" reflects the state after the cells placed so far.
         */
        boolean isStandable(int x, int y, int z, LongPredicate solid);
    }

    public static final class Result {
        /** Cells in a safe placement order — placing them front-to-back never encloses the bot away from the exit. */
        public final List<Long> order;
        /** Target cells that could not be ordered safely (only reachable from a sealed pocket). Never skipped. */
        public final List<Long> unreachable;

        Result(List<Long> order, List<Long> unreachable) {
            this.order = order;
            this.unreachable = unreachable;
        }
    }

    /**
     * @param targets   the schematic's solid target cells (packed), that the builder must place
     * @param world     terrain + standability oracle
     * @param exit      a standable free cell known to be exterior (the safe side the bot retreats toward)
     * @param reach     max Chebyshev distance from a stance foot to a target cell (typically 4)
     */
    public static Result plan(Set<Long> targets, World world, long exit, int reach) {
        // Running solid = pre-existing terrain OR an already-placed target.
        Set<Long> placed = new HashSet<>();
        LongPredicate solid = key -> placed.contains(key)
                || world.isPreexistingSolid(unpackX(key), unpackY(key), unpackZ(key));

        // The navigable region around an open build is unbounded (a ground plane is standable everywhere), so the
        // stance flood must be confined to a finite working box: the schematic bounds expanded by reach + headroom.
        // A standable cell on the box boundary is exterior by construction — the bot can always walk further out.
        Box box = Box.around(targets, exit, reach + 2);

        List<Long> order = new ArrayList<>(targets.size());
        Set<Long> remaining = new HashSet<>(targets);

        while (!remaining.isEmpty()) {
            // Exterior-connected standable component in the CURRENT solid state.
            Set<Long> exterior = floodStandable(exit, world, solid, box);

            // Buildable now = a remaining cell with a stance in that exterior component whose placement does not
            // strand any other remaining cell. We prefer the cell FARTHEST from the exit (retreat ordering): build
            // the deep geometry first, walk back out.
            long best = Long.MIN_VALUE;
            int bestDist = -1;
            for (long cell : remaining) {
                if (!hasStanceIn(cell, world, solid, exterior, reach)) {
                    continue;
                }
                if (wouldStrand(cell, remaining, world, solid, exit, reach, box)) {
                    continue;
                }
                int d = exitDistance(cell, exterior, exit);
                if (d > bestDist) {
                    bestDist = d;
                    best = cell;
                }
            }

            if (best == Long.MIN_VALUE) {
                // No safe placement remains: everything left is only reachable from a sealed pocket. Surface it.
                break;
            }
            placed.add(best);
            remaining.remove(best);
            order.add(best);
        }

        List<Long> unreachable = new ArrayList<>(remaining);
        Collections.sort(unreachable);
        return new Result(order, unreachable);
    }

    /** Finite working region: schematic bounds unioned with the exit, expanded by a margin, so a ground-plane flood
     *  terminates. Standability on the boundary means the bot can keep walking outward = exterior. */
    private static final class Box {
        final int minX, minY, minZ, maxX, maxY, maxZ;

        private Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }

        static Box around(Set<Long> targets, long exit, int margin) {
            int minX = unpackX(exit), minY = unpackY(exit), minZ = unpackZ(exit);
            int maxX = minX, maxY = minY, maxZ = minZ;
            for (long k : targets) {
                int x = unpackX(k), y = unpackY(k), z = unpackZ(k);
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
            // Extra headroom on +Y so a foot standing on top of the tallest cell (and the free cell above it) is in.
            return new Box(minX - margin, minY - margin, minZ - margin,
                    maxX + margin, maxY + margin + 1, maxZ + margin);
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** BFS over standable free cells reachable from {@code start}, through 26-neighbour + step-up/down moves,
     *  confined to {@code box}. */
    private static Set<Long> floodStandable(long start, World world, LongPredicate solid, Box box) {
        Set<Long> seen = new HashSet<>();
        if (!world.isStandable(unpackX(start), unpackY(start), unpackZ(start), solid)) {
            return seen;
        }
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            int cx = unpackX(cur), cy = unpackY(cur), cz = unpackZ(cur);
            // Horizontal steps with a +/-1 vertical tolerance (walk, step up one, drop one) — the movement subset the
            // compiler is allowed to assume without a planned scaffold. Diagonals included (the bot can corner).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = cx + dx, ny = cy + dy, nz = cz + dz;
                        if (!box.contains(nx, ny, nz)) {
                            continue;
                        }
                        long nk = pack(nx, ny, nz);
                        if (seen.contains(nk)) {
                            continue;
                        }
                        if (world.isStandable(nx, ny, nz, solid)) {
                            seen.add(nk);
                            queue.add(nk);
                        }
                    }
                }
            }
        }
        return seen;
    }

    /** Does {@code cell} have a standable stance within {@code reach} that lies in the exterior-connected component? */
    private static boolean hasStanceIn(long cell, World world, LongPredicate solid, Set<Long> exterior, int reach) {
        int tx = unpackX(cell), ty = unpackY(cell), tz = unpackZ(cell);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    long stance = pack(tx + dx, ty + dy, tz + dz);
                    if (exterior.contains(stance)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Would placing {@code cell} strand any OTHER remaining cell — i.e. leave it with no exterior-connected stance in
     * the resulting solid state? This is the enclosure invariant: a placement may never remove the last exit-connected
     * stance of a not-yet-built cell.
     */
    private static boolean wouldStrand(long cell, Set<Long> remaining, World world, LongPredicate baseSolid,
                                       long exit, int reach, Box box) {
        // Hypothetical solid state with `cell` added.
        LongPredicate solidAfter = key -> key == cell || baseSolid.test(key);
        Set<Long> exteriorAfter = floodStandable(exit, world, solidAfter, box);
        for (long other : remaining) {
            if (other == cell) {
                continue;
            }
            if (!hasStanceIn(other, world, solidAfter, exteriorAfter, reach)) {
                return true;
            }
        }
        return false;
    }

    /** Free-region step distance from the exit to the nearest stance of {@code cell}; larger = deeper = build first. */
    private static int exitDistance(long cell, Set<Long> exterior, long exit) {
        // Manhattan proxy over the exterior set is enough to bias retreat ordering; exact BFS distance is not needed
        // for correctness (the invariant guarantees safety), only for a sensible deep-first tie-break.
        int tx = unpackX(cell), ty = unpackY(cell), tz = unpackZ(cell);
        int ex = unpackX(exit), ey = unpackY(exit), ez = unpackZ(exit);
        return Math.abs(tx - ex) + Math.abs(ty - ey) + Math.abs(tz - ez);
    }
}
