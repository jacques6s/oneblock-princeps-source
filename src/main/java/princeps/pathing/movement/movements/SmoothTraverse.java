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
        // Walk the smoothed line: the pure-pursuit follows the (now-sparse) smoothed polyline of the executor's path,
        // and the curvature-slow / octant steering ride on top. SPRINT is held; the curvature-slow releases it at bends.
        MovementHelper.moveAlongPath(princeps, state, dest);
        state.setInput(Input.SPRINT, true);
        return state;
    }

    /**
     * Every block cell (at the flat run's Y) that a body of half-width {@code half} sweeps along the {@code src}->{@code
     * dest} chord — the supercover corners. Used both for {@link #calculateValidPositions()} and, at merge time, for
     * the collision-safety predicate (each of these cells must have floor + body + head clearance and no hazard).
     */
    public static Set<BetterBlockPos> sweptCells(BetterBlockPos src, BetterBlockPos dest, double half) {
        Set<BetterBlockPos> cells = new HashSet<>();
        final double ax = src.x + 0.5, az = src.z + 0.5, bx = dest.x + 0.5, bz = dest.z + 0.5;
        final double dist = Math.hypot(bx - ax, bz - az);
        final int steps = Math.max(1, (int) (dist / 0.1));
        for (int s = 0; s <= steps; s++) {
            final double t = (double) s / steps;
            final double x = ax + (bx - ax) * t, z = az + (bz - az) * t;
            cells.add(new BetterBlockPos((int) Math.floor(x - half), src.y, (int) Math.floor(z - half)));
            cells.add(new BetterBlockPos((int) Math.floor(x + half), src.y, (int) Math.floor(z - half)));
            cells.add(new BetterBlockPos((int) Math.floor(x - half), src.y, (int) Math.floor(z + half)));
            cells.add(new BetterBlockPos((int) Math.floor(x + half), src.y, (int) Math.floor(z + half)));
        }
        cells.add(src);
        cells.add(dest);
        return cells;
    }
}
