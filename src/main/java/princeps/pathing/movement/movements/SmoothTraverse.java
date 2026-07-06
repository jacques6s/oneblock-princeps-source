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

package princeps.pathing.movement.movements;

import com.google.common.collect.ImmutableSet;
import princeps.api.IPrinceps;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.Movement;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.movement.MovementState;

import java.util.HashSet;
import java.util.Set;

/**
 * ANY-ANGLE straight-line walk across a flat, obstacle-free run — the result of string-pulling a chain of flat
 * {@link MovementTraverse}/{@link MovementDiagonal} movements into one taut chord (see {@code Path.postProcess} /
 * {@link princeps.pathing.path.SmoothedPath}). It never breaks or places a block.
 *
 * <p>The reason this can exist WITHOUT weakening the executor's safety gates: {@link #calculateValidPositions()}
 * returns EVERY block cell the ~0.6-wide body sweeps along the chord, so the executor's position-containment gate
 * ({@code getValidPositions().contains(playerFeet())}) and its distance gate accept the bot anywhere on the diagonal.
 * We SATISFY the gates rather than bypass them. Steering is the existing pure-pursuit ({@code moveAlongPath} follows
 * the smoothed polyline); SUCCESS fires on {@code feet == dest}, exactly like a traverse. Cost is the summed cost of
 * the merged lattice movements, so the path total is unchanged and cost-verification never cancels.
 */
public class SmoothTraverse extends Movement {

    private final double precomputedCost;
    private final ImmutableSet<BetterBlockPos> valid;

    public SmoothTraverse(IPrinceps princeps, BetterBlockPos src, BetterBlockPos dest,
                          double cost, Set<BetterBlockPos> sweptCells) {
        super(princeps, src, dest, new BetterBlockPos[0], null);
        this.precomputedCost = cost;
        this.valid = ImmutableSet.copyOf(sweptCells);
        // Populate the cached cost field NOW. Unlike lattice movements (which get it via override() during
        // Path assembly), a SmoothTraverse is synthesized in post-processing and never re-run through runBackwards,
        // so without this the field stays null and the executor's no-arg getCost() (read before recalculateCost on
        // a movement's first tick) NPEs the moment a chord starts executing.
        override(cost);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return precomputedCost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return valid;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        // END-REGION success: the wide valid corridor (smoothPathSweepHalf) extends PAST the endpoint, so a body
        // that slid just beyond dest (sprint momentum, corner cut) stays "valid" forever while exact-cell equality
        // can never fire again — the movement would strand until its timeout (bench-caught: stuck-at-goal case).
        // The chord is factually complete once the body's projection along the chord axis reaches the final half
        // block, on the chord's own level.
        if (ctx.playerFeet().y == dest.y && this.getValidPositions().contains(ctx.playerFeet())) {
            // LATERAL rule (review-confirmed + bench-tuned): the axial projection alone is laterally unbounded — a
            // body knocked blocks BESIDE the line (cells chordSafe never verified) would "succeed" into unverified
            // ground. Promote ONLY from the chord's own verified corridor. This is also the livelock-free choice:
            // whenever the executor containment accepts the feet while the body is past the end, SUCCESS fires
            // (a tighter fixed bound left a containment-yes/success-no gap that rewind-ping-ponged into timeouts
            // at sharp junction cuts); outside the corridor SUCCESS can never fire and the pursuit steers back.
            final double dx = dest.x - src.x, dz = dest.z - src.z;
            final double len = Math.hypot(dx, dz);
            if (len > 1e-6) {
                final net.minecraft.world.phys.Vec3 p = ctx.player().position();
                final double s = ((p.x - (src.x + 0.5)) * dx + (p.z - (src.z + 0.5)) * dz) / len;
                if (s >= len - 0.5) {
                    return state.setStatus(MovementStatus.SUCCESS);
                }
            }
        }
        // Walk the smoothed line: the pure-pursuit follows the (now-sparse) smoothed polyline of the executor's path,
        // and the curvature-slow / octant steering ride on top. SPRINT is held; the curvature-slow releases it at bends.
        MovementHelper.moveAlongPath(princeps, state, dest);
        state.setInput(Input.SPRINT, true);
        return state;
    }

    /**
     * EXACT (sampling-free) supercover: every block cell (at the flat run's Y) that an AABB body of half-width {@code
     * half} overlaps while its centre travels the straight {@code src}->{@code dest} chord. Used both for {@link
     * #calculateValidPositions()} and, at merge time, for the collision-safety predicate (each of these cells must have
     * floor + body + head clearance and no hazard).
     *
     * <p>A body-square overlaps cell {@code [cx,cx+1]x[cz,cz+1]} iff its centre passes within {@code half} of the cell
     * on both axes, i.e. the chord intersects that cell-rect expanded by {@code half} on all sides. We enumerate the
     * bounding box and do an exact segment/rect test (Liang-Barsky). The previous version point-sampled the four body
     * corners along the chord, which MISSED cells the body only grazes at a corner between samples — a real collision
     * gap the random-terrain stress bench caught (finer sampling did not close it; only the exact test does).
     */
    public static Set<BetterBlockPos> sweptCells(BetterBlockPos src, BetterBlockPos dest, double half) {
        Set<BetterBlockPos> cells = new HashSet<>();
        final double ax = src.x + 0.5, az = src.z + 0.5, bx = dest.x + 0.5, bz = dest.z + 0.5;
        final int xlo = (int) Math.floor(Math.min(ax, bx) - half - 1);
        final int xhi = (int) Math.floor(Math.max(ax, bx) + half + 1);
        final int zlo = (int) Math.floor(Math.min(az, bz) - half - 1);
        final int zhi = (int) Math.floor(Math.max(az, bz) + half + 1);
        for (int cx = xlo; cx <= xhi; cx++) {
            for (int cz = zlo; cz <= zhi; cz++) {
                if (segIntersectsRect(ax, az, bx, bz, cx - half, cz - half, cx + 1 + half, cz + 1 + half)) {
                    cells.add(new BetterBlockPos(cx, src.y, cz));
                }
            }
        }
        cells.add(src);   // endpoints are always swept; add explicitly to be robust against FP corner cases
        cells.add(dest);
        return cells;
    }

    /** Liang-Barsky segment/AABB clip: does segment (ax,az)->(bx,bz) intersect axis-aligned rect [xmin,xmax]x[zmin,zmax]? */
    private static boolean segIntersectsRect(double ax, double az, double bx, double bz,
                                             double xmin, double zmin, double xmax, double zmax) {
        final double dx = bx - ax, dz = bz - az;
        double t0 = 0.0, t1 = 1.0;
        final double[] ps = {-dx, dx, -dz, dz};
        final double[] qs = {ax - xmin, xmax - ax, az - zmin, zmax - az};
        for (int i = 0; i < 4; i++) {
            final double p = ps[i], q = qs[i];
            if (Math.abs(p) < 1e-12) {
                if (q < 0) return false;              // parallel to this edge and outside the slab
            } else {
                final double r = q / p;
                if (p < 0) {
                    if (r > t1) return false;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return false;
                    if (r < t1) t1 = r;
                }
            }
        }
        return t0 <= t1;
    }
}
