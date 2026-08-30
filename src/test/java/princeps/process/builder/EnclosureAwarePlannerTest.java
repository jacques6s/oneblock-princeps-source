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

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static princeps.process.builder.EnclosureAwarePlanner.pack;
import static princeps.process.builder.EnclosureAwarePlanner.unpackX;
import static princeps.process.builder.EnclosureAwarePlanner.unpackY;
import static princeps.process.builder.EnclosureAwarePlanner.unpackZ;

public class EnclosureAwarePlannerTest {

    /** A test world: a solid ground plane at {@code groundY}, plus an arbitrary set of pre-existing solids. */
    private static final class TestWorld implements EnclosureAwarePlanner.World {
        final int groundY;
        final Set<Long> preexisting;

        TestWorld(int groundY, Set<Long> preexisting) {
            this.groundY = groundY;
            this.preexisting = preexisting;
        }

        @Override
        public boolean isPreexistingSolid(int x, int y, int z) {
            return y <= groundY || preexisting.contains(pack(x, y, z));
        }

        @Override
        public boolean isStandable(int x, int y, int z, LongPredicate solid) {
            long here = pack(x, y, z);
            long below = pack(x, y - 1, z);
            long above = pack(x, y + 1, z);
            return !solid.test(here) && !solid.test(above) && solid.test(below);
        }
    }

    private LongPredicate runningSolid(TestWorld w, Set<Long> placed) {
        return key -> placed.contains(key)
                || w.isPreexistingSolid(unpackX(key), unpackY(key), unpackZ(key));
    }

    /**
     * Replays a produced order and asserts the enclosure invariant at every step: before each placement, every
     * not-yet-built cell (including the one about to be placed) must have a standable stance connected — through free
     * space — to the exit. If this holds for the whole order, the bot is never sealed away from its work. This is the
     * property whose violation was the live y=112 self-box.
     */
    private void assertNeverSelfBoxed(List<Long> order, TestWorld w, long exit, int reach) {
        Set<Long> placed = new HashSet<>();
        Set<Long> remaining = new HashSet<>(order);
        int[] box = boxAround(order, exit, reach + 2);
        for (long cell : order) {
            LongPredicate solid = runningSolid(w, placed);
            Set<Long> exterior = flood(exit, w, solid, box);
            for (long r : remaining) {
                assertTrue("cell " + str(r) + " lost its exterior stance before step "
                        + str(cell), hasStance(r, exterior, reach));
            }
            placed.add(cell);
            remaining.remove(cell);
        }
    }

    private int[] boxAround(List<Long> cells, long exit, int margin) {
        int minX = unpackX(exit), minY = unpackY(exit), minZ = unpackZ(exit);
        int maxX = minX, maxY = minY, maxZ = minZ;
        for (long k : cells) {
            minX = Math.min(minX, unpackX(k)); minY = Math.min(minY, unpackY(k)); minZ = Math.min(minZ, unpackZ(k));
            maxX = Math.max(maxX, unpackX(k)); maxY = Math.max(maxY, unpackY(k)); maxZ = Math.max(maxZ, unpackZ(k));
        }
        return new int[]{minX - margin, minY - margin, minZ - margin,
                maxX + margin, maxY + margin + 1, maxZ + margin};
    }

    private boolean inBox(int x, int y, int z, int[] b) {
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    // Mirror of the planner's internal flood/stance, used only to verify the emitted order independently.
    private Set<Long> flood(long start, TestWorld w, LongPredicate solid, int[] box) {
        Set<Long> seen = new HashSet<>();
        if (!w.isStandable(unpackX(start), unpackY(start), unpackZ(start), solid)) {
            return seen;
        }
        java.util.Deque<Long> q = new java.util.ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty()) {
            long c = q.poll();
            int cx = unpackX(c), cy = unpackY(c), cz = unpackZ(c);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = cx + dx, ny = cy + dy, nz = cz + dz;
                        if (!inBox(nx, ny, nz, box)) {
                            continue;
                        }
                        long nk = pack(nx, ny, nz);
                        if (!seen.contains(nk) && w.isStandable(nx, ny, nz, solid)) {
                            seen.add(nk);
                            q.add(nk);
                        }
                    }
                }
            }
        }
        return seen;
    }

    private boolean hasStance(long cell, Set<Long> exterior, int reach) {
        int tx = unpackX(cell), ty = unpackY(cell), tz = unpackZ(cell);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (exterior.contains(pack(tx + dx, ty + dy, tz + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String str(long k) {
        return unpackX(k) + "," + unpackY(k) + "," + unpackZ(k);
    }

    @Test
    public void packRoundTrips() {
        int[][] samples = {{0, 0, 0}, {1, 2, 3}, {-4, 5, -6}, {100, -111, 122}, {-1048576, 1048575, 0}};
        for (int[] s : samples) {
            long k = pack(s[0], s[1], s[2]);
            assertEquals(s[0], unpackX(k));
            assertEquals(s[1], unpackY(k));
            assertEquals(s[2], unpackZ(k));
        }
    }

    /**
     * The live failure geometry: a one-wide wall ring the bot stood inside on layer y=112. Built from an exterior
     * exit, the whole ring must order cleanly and the invariant must hold at every step (no self-box).
     */
    @Test
    public void oneWideWallRingBuildsFromOutsideWithoutSelfBoxing() {
        int y = 112;
        Set<Long> ring = new HashSet<>();
        // 7x7 perimeter ring at y=112: x,z in [0..6], only the border.
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                if (x == 0 || x == 6 || z == 0 || z == 6) {
                    ring.add(pack(x, y, z));
                }
            }
        }
        TestWorld w = new TestWorld(y - 1, new HashSet<>()); // ground plane just below the ring
        long exit = pack(-3, y, 3); // a standable free cell well outside the ring, on the ground plane
        EnclosureAwarePlanner.Result r = EnclosureAwarePlanner.plan(ring, w, exit, 4);

        assertEquals("every ring cell must be scheduled", ring.size(), r.order.size());
        assertTrue("nothing may be left unreachable for an open ring", r.unreachable.isEmpty());
        assertNeverSelfBoxed(r.order, w, exit, 4);
    }

    /**
     * A one-wide corridor of wall cells deeper than reach. Retreat ordering must build the far end first and walk
     * back toward the exit — and never strand the near cells.
     */
    @Test
    public void deepCorridorRetreatsTowardExit() {
        int y = 64;
        Set<Long> wall = new HashSet<>();
        for (int x = 0; x <= 10; x++) {
            wall.add(pack(x, y, 2)); // a straight wall line
        }
        TestWorld w = new TestWorld(y - 1, new HashSet<>());
        long exit = pack(0, y, 0); // near the x=0 end, in front of the wall
        EnclosureAwarePlanner.Result r = EnclosureAwarePlanner.plan(wall, w, exit, 4);

        assertEquals(wall.size(), r.order.size());
        assertTrue(r.unreachable.isEmpty());
        assertNeverSelfBoxed(r.order, w, exit, 4);
        // Deep-first: the far end (x=10) should be scheduled before the near end (x=0).
        int idxFar = r.order.indexOf(pack(10, y, 2));
        int idxNear = r.order.indexOf(pack(0, y, 2));
        assertTrue("far end must be built before the near end (retreat ordering)", idxFar < idxNear);
    }

    /**
     * A genuinely sealed pocket: a target cell fully surrounded by pre-existing solids on all sides with no possible
     * stance. It must be surfaced in {@code unreachable}, never silently dropped or claimed built.
     */
    @Test
    public void trulyUnreachableCellIsSurfacedNotSkipped() {
        int y = 64;
        int cx = 40, cz = 40; // put the buried cell far from the exit so no stance can reach it within the box
        Set<Long> pre = new HashSet<>();
        // Bury (cx,y,cz) inside a solid cube of radius 5 (except the target cell itself): every cell within reach 4
        // is solid, so NO standable free stance exists anywhere near it — genuinely stance-unreachable.
        int radius = 5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue; // leave the target cell free — it's what we're trying (and failing) to reach
                    }
                    pre.add(pack(cx + dx, y + dy, cz + dz));
                }
            }
        }
        TestWorld w = new TestWorld(y - 20, pre); // ground far below the buried cube
        Set<Long> targets = new HashSet<>();
        targets.add(pack(cx, y, cz));
        long exit = pack(0, y - 19, 0);
        EnclosureAwarePlanner.Result r = EnclosureAwarePlanner.plan(targets, w, exit, 4);

        assertTrue("buried cell must not be in the build order", r.order.isEmpty());
        assertEquals(1, r.unreachable.size());
        assertEquals(pack(cx, y, cz), (long) r.unreachable.get(0));
    }
}
