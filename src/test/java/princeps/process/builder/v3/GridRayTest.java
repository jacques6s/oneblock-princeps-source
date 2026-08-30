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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Hand-computed expectations for the DDA.
 *
 * <p>A real {@code Level} cannot be built headless — {@code Bootstrap.bootStrap()} gives registries and nothing else,
 * no chunks, no player — so the regression test the plan asks for (10 000 random rays against {@code level.clip} on a
 * loaded chunk) has to live in the game, not here. What lives here is the part that can be checked exactly: the grid
 * traversal itself, against a synthetic bitset and expectations worked out on paper. Every distance below is written
 * as the arithmetic that produced it rather than as a decimal literal, so a reader can re-derive it without trusting
 * the test.
 *
 * <p>The half-open-at-{@code to} convention gets its own pair of cases. It is the one property the rest of the planner
 * silently depends on: an aim point sits exactly on the face plane of the block being clicked, and if that block
 * counted as blocking the ray to its own face, every single placement would be rejected as occluded.
 */
public class GridRayTest {

    private static final double EPS = 1.0E-9D;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A grid whose solid cells are exactly the ones listed, and which records what the caster asked about. */
    private static final class Grid implements GridRay.SolidTest {

        private final Set<Long> cells = new HashSet<>();
        private final List<BlockPos> visited = new ArrayList<>();

        Grid solid(int x, int y, int z) {
            this.cells.add(BlockPos.asLong(x, y, z));
            return this;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            this.visited.add(new BlockPos(x, y, z));
            return this.cells.contains(BlockPos.asLong(x, y, z));
        }
    }

    private static void assertHit(GridRay.Hit hit, int x, int y, int z, Direction face, Vec3 point, double distance) {
        assertNotNull("expected a hit", hit);
        assertEquals("cell x", x, hit.x());
        assertEquals("cell y", y, hit.y());
        assertEquals("cell z", z, hit.z());
        assertEquals("entered face", face, hit.face());
        assertEquals("point x", point.x, hit.point().x, EPS);
        assertEquals("point y", point.y, hit.point().y, EPS);
        assertEquals("point z", point.z, hit.point().z, EPS);
        assertEquals("distance", distance, hit.distance(), EPS);
    }

    // ------------------------------------------------------------------------------------------ axis aligned

    @Test
    public void axisAlignedPositiveXEntersThroughTheWestFace() {
        Grid grid = new Grid().solid(3, 0, 0);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(5.5D, 0.5D, 0.5D));
        // Cell 3 spans [3,4); travelling +X the ray enters it at x == 3, which is 2.5 blocks from the origin.
        assertHit(hit, 3, 0, 0, Direction.WEST, new Vec3(3.0D, 0.5D, 0.5D), 2.5D);
    }

    @Test
    public void axisAlignedNegativeZEntersThroughTheSouthFace() {
        Grid grid = new Grid().solid(0, 0, -3);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(0.5D, 0.5D, -4.5D));
        // Cell -3 spans [-3,-2); travelling -Z the ray enters at z == -2, i.e. through the cell's +Z (SOUTH) face.
        assertHit(hit, 0, 0, -3, Direction.SOUTH, new Vec3(0.5D, 0.5D, -2.0D), 2.5D);
    }

    @Test
    public void negativeCoordinatesFloorTowardsMinusInfinity() {
        // The whole ray lives at negative x, which is where a truncating cast instead of a floor would put the origin
        // in the wrong cell and shift every subsequent boundary by one.
        Grid grid = new Grid().solid(-4, 0, -1);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(-0.5D, 0.5D, -0.5D), new Vec3(-5.5D, 0.5D, -0.5D));
        assertHit(hit, -4, 0, -1, Direction.EAST, new Vec3(-3.0D, 0.5D, -0.5D), 2.5D);
    }

    @Test
    public void downwardRayEntersThroughTheUpFace() {
        Grid grid = new Grid().solid(0, -5, 0);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, -0.5D, 0.5D), new Vec3(0.5D, -6.5D, 0.5D));
        assertHit(hit, 0, -5, 0, Direction.UP, new Vec3(0.5D, -4.0D, 0.5D), 3.5D);
    }

    // ------------------------------------------------------------------------------------------ diagonal

    @Test
    public void diagonalRayInterleavesTheAxisCrossings() {
        // from (0.5,0.5,0.5) to (4.5,0.5,2.5): dx=4, dz=2. X boundaries fall at t = .125 .375 .625 .875, Z boundaries
        // at t = .25 .75, so the cell walk is (0,0,0) (1,0,0) (1,0,1) (2,0,1) (3,0,1) (3,0,2) (4,0,2).
        Grid grid = new Grid().solid(3, 0, 1);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(4.5D, 0.5D, 2.5D));
        assertHit(hit, 3, 0, 1, Direction.WEST, new Vec3(3.0D, 0.5D, 1.75D), Math.sqrt(2.5D * 2.5D + 1.25D * 1.25D));
    }

    @Test
    public void diagonalRayVisitsEveryCellItPassesThroughInOrder() {
        Grid grid = new Grid();
        assertNull(GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(4.5D, 0.5D, 2.5D)));
        assertEquals(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(1, 0, 1),
                new BlockPos(2, 0, 1),
                new BlockPos(3, 0, 1),
                new BlockPos(3, 0, 2),
                new BlockPos(4, 0, 2)), grid.visited);
    }

    // ------------------------------------------------------------------------------------------ corner graze

    @Test
    public void exactCornerBreaksTheTieTowardsX() {
        // A 45 degree ray through the lattice corners: at every crossing tMaxX == tMaxZ exactly. X is taken first,
        // unconditionally, so the walk staircases X-then-Z and the trace is reproducible byte for byte.
        Grid grid = new Grid().solid(1, 0, 0);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(3.5D, 0.5D, 3.5D));
        assertHit(hit, 1, 0, 0, Direction.WEST, new Vec3(1.0D, 0.5D, 1.0D), Math.sqrt(0.5D * 0.5D + 0.5D * 0.5D));
    }

    @Test
    public void theCellOnTheOtherSideOfTheCornerIsNeverEntered() {
        // Same ray. (0,0,1) is the Z-first alternative at the very first corner and (1,0,2) the one after it; a caster
        // that resolved ties by anything order-dependent would hit one of them on some runs and not others.
        Grid grid = new Grid().solid(0, 0, 1).solid(1, 0, 2).solid(2, 0, 3);
        assertNull(GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(3.5D, 0.5D, 3.5D)));
        assertEquals(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(1, 0, 1),
                new BlockPos(2, 0, 1),
                new BlockPos(2, 0, 2),
                new BlockPos(3, 0, 2),
                new BlockPos(3, 0, 3)), grid.visited);
    }

    // ------------------------------------------------------------------------------------------ starting inside

    @Test
    public void startingInsideASolidReportsANullFaceAtZeroDistance() {
        Grid grid = new Grid().solid(0, 0, 0);
        Vec3 from = new Vec3(0.5D, 0.5D, 0.5D);
        GridRay.Hit hit = GridRay.cast(grid, from, new Vec3(5.5D, 0.5D, 0.5D));
        assertHit(hit, 0, 0, 0, null, from, 0.0D);
        assertFalse("an eye buried in a block has no line of sight", GridRay.clear(grid, from, new Vec3(5.5D, 0.5D, 0.5D)));
    }

    @Test
    public void anInsideHitCarriesTheFlagRatherThanAFace() {
        Grid grid = new Grid().solid(0, 0, 0);
        BlockHitResult result = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(5.5D, 0.5D, 0.5D))
                .toBlockHitResult();
        assertEquals(new BlockPos(0, 0, 0), result.getBlockPos());
        assertTrue("inside must be set; the direction is a placeholder", result.isInside());
    }

    // ------------------------------------------------------------------------------------------ misses

    @Test
    public void anEmptyGridIsAClearMiss() {
        Grid grid = new Grid();
        assertNull(GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(5.5D, 0.5D, 0.5D)));
        assertTrue(GridRay.clear(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(5.5D, 0.5D, 0.5D)));
    }

    @Test
    public void solidCellsOffTheLineAreNotHit() {
        Grid grid = new Grid().solid(3, 1, 0).solid(3, 0, 1).solid(3, 0, -1);
        assertNull(GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(5.5D, 0.5D, 0.5D)));
    }

    // ------------------------------------------------------------------------------------------ the half-open end

    @Test
    public void aimingAtAFaceDoesNotReportThatFacesOwnBlockAsBlocking() {
        // THE convention. The aim point of a placement against the WEST face of (3,0,0) is exactly x == 3, and the
        // planner asks this caster whether the line to it is clear. If the segment were closed at `to`, the answer
        // would be no for every placement ever planned.
        Grid grid = new Grid().solid(3, 0, 0);
        Vec3 eye = new Vec3(0.5D, 0.5D, 0.5D);
        Vec3 aim = new Vec3(3.0D, 0.5D, 0.5D);
        assertNull(GridRay.cast(grid, eye, aim));
        assertTrue(GridRay.clear(grid, eye, aim));
    }

    @Test
    public void oneUlpPastTheFaceIsAHit() {
        Grid grid = new Grid().solid(3, 0, 0);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(3.0001D, 0.5D, 0.5D));
        assertHit(hit, 3, 0, 0, Direction.WEST, new Vec3(3.0D, 0.5D, 0.5D), 2.5D);
    }

    @Test
    public void aRayThatEndsInsideTheBlockItEnteredStillHits() {
        Grid grid = new Grid().solid(3, 0, 0);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(3.5D, 0.5D, 0.5D));
        assertHit(hit, 3, 0, 0, Direction.WEST, new Vec3(3.0D, 0.5D, 0.5D), 2.5D);
    }

    // ------------------------------------------------------------------------------------------ degenerate rays

    @Test
    public void aZeroLengthRayInEmptySpaceMisses() {
        Grid grid = new Grid().solid(3, 0, 0);
        Vec3 point = new Vec3(0.5D, 0.5D, 0.5D);
        assertNull(GridRay.cast(grid, point, point));
        assertEquals(List.of(new BlockPos(0, 0, 0)), grid.visited);
    }

    @Test
    public void aRayAlongACellBoundaryStaysInThePositiveCell() {
        // from.x is exactly 3.0, so floor puts the ray in cell x=3 and it runs down that column rather than the one
        // at x=2. Documented rather than defended: it is the same convention BlockPos.containing uses.
        Grid grid = new Grid().solid(3, 0, 3).solid(2, 0, 3);
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(3.0D, 0.5D, 0.5D), new Vec3(3.0D, 0.5D, 5.5D));
        assertHit(hit, 3, 0, 3, Direction.NORTH, new Vec3(3.0D, 0.5D, 3.0D), 2.5D);
    }

    // ------------------------------------------------------------------------------------------ direction overload

    @Test
    public void theDirectionOverloadNormalisesSoMaxDistanceIsInBlocks() {
        Grid grid = new Grid().solid(3, 0, 0);
        // Length 7 direction vector, 5 blocks of reach: the caster must travel 5 blocks, not 35.
        GridRay.Hit hit = GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(7.0D, 0.0D, 0.0D), 5.0D);
        assertHit(hit, 3, 0, 0, Direction.WEST, new Vec3(3.0D, 0.5D, 0.5D), 2.5D);
    }

    @Test
    public void theDirectionOverloadStopsAtMaxDistance() {
        Grid grid = new Grid().solid(3, 0, 0);
        // 2 blocks of reach ends at x == 2.5, short of the block. This is how OUT_OF_REACH is meant to look: a miss,
        // not a hit the caller then has to re-measure.
        assertNull(GridRay.cast(grid, new Vec3(0.5D, 0.5D, 0.5D), new Vec3(1.0D, 0.0D, 0.0D), 2.0D));
    }

    // ------------------------------------------------------------------------------------------ determinism

    @Test
    public void twoIdenticalCastsProduceIdenticalTraces() {
        Grid first = new Grid().solid(4, 2, 3);
        Grid second = new Grid().solid(4, 2, 3);
        Vec3 from = new Vec3(0.37D, 0.61D, 0.29D);
        Vec3 to = new Vec3(6.13D, 3.47D, 4.91D);
        GridRay.Hit a = GridRay.cast(first, from, to);
        GridRay.Hit b = GridRay.cast(second, from, to);
        assertEquals(a, b);
        assertEquals(first.visited, second.visited);
    }
}
