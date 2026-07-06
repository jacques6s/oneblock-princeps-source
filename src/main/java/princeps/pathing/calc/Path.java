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

package princeps.pathing.calc;

import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.movement.IMovement;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.Helper;
import princeps.Princeps;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.Movement;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.movement.Moves;
import princeps.pathing.movement.movements.MovementDiagonal;
import princeps.pathing.movement.movements.MovementTraverse;
import princeps.pathing.movement.movements.SmoothTraverse;
import princeps.pathing.path.CutoffPath;
import princeps.pathing.path.SmoothedPath;
import princeps.utils.pathing.PathBase;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Set;
import java.util.List;

/**
 * A node based implementation of IPath
 *
 * @author leijurv
 */
class Path extends PathBase {

    /**
     * The start position of this path
     */
    private final BetterBlockPos start;

    /**
     * The end position of this path
     */
    private final BetterBlockPos end;

    /**
     * The blocks on the path. Guaranteed that path.get(0) equals start and
     * path.get(path.size()-1) equals end
     */
    private final List<BetterBlockPos> path;

    private final List<Movement> movements;

    private final List<PathNode> nodes;

    private final Goal goal;

    private final int numNodes;

    private final CalculationContext context;

    private volatile boolean verified;

    Path(BetterBlockPos realStart, PathNode start, PathNode end, int numNodes, Goal goal, CalculationContext context) {
        this.end = new BetterBlockPos(end.x, end.y, end.z);
        this.numNodes = numNodes;
        this.movements = new ArrayList<>();
        this.goal = goal;
        this.context = context;

        PathNode current = end;
        List<BetterBlockPos> tempPath = new ArrayList<>();
        List<PathNode> tempNodes = new ArrayList<>();
        while (current != null) {
            tempNodes.add(current);
            tempPath.add(new BetterBlockPos(current.x, current.y, current.z));
            current = current.previous;
        }

        // If the position the player is at is different from the position we told A* to start from,
        // and A* gave us no movements, then add a fake node that will allow a movement to be created
        // that gets us to the single position in the path.
        // See PathingBehavior#createPathfinder and https://github.com/cabaletta/princeps/pull/4519
        var startNodePos = new BetterBlockPos(start.x, start.y, start.z);
        if (!realStart.equals(startNodePos) && start.equals(end)) {
            this.start = realStart;
            PathNode fakeNode = new PathNode(realStart.x, realStart.y, realStart.z, goal);
            fakeNode.cost = 0;
            tempNodes.add(fakeNode);
            tempPath.add(realStart);
        } else {
            this.start = startNodePos;
        }

        // Nodes are traversed last to first so we need to reverse the list
        this.path = Lists.reverse(tempPath);
        this.nodes = Lists.reverse(tempNodes);
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    private boolean assembleMovements() {
        if (path.isEmpty() || !movements.isEmpty()) {
            throw new IllegalStateException("Path must not be empty");
        }
        for (int i = 0; i < path.size() - 1; i++) {
            double cost = nodes.get(i + 1).cost - nodes.get(i).cost;
            Movement move = runBackwards(path.get(i), path.get(i + 1), cost);
            if (move == null) {
                return true;
            } else {
                movements.add(move);
            }
        }
        return false;
    }

    private Movement runBackwards(BetterBlockPos src, BetterBlockPos dest, double cost) {
        for (Moves moves : Moves.values()) {
            Movement move = moves.apply0(context, src);
            if (move.getDest().equals(dest)) {
                // have to calculate the cost at calculation time so we can accurately judge whether a cost increase happened between cached calculation and real execution
                // however, taking into account possible favoring that could skew the node cost, we really want the stricter limit of the two
                // so we take the minimum of the path node cost difference, and the calculated cost
                move.override(Math.min(move.calculateCost(context), cost));
                return move;
            }
        }
        // this is no longer called from bestPathSoFar, now it's in postprocessing
        Helper.HELPER.logDebug("Movement became impossible during calculation " + src + " " + dest + " " + dest.subtract(src));
        return null;
    }

    @Override
    public IPath postProcess() {
        if (verified) {
            throw new IllegalStateException("Path must not be verified twice");
        }
        verified = true;
        boolean failed = assembleMovements();
        movements.forEach(m -> m.checkLoadedChunk(context));

        if (failed) { // at least one movement became impossible during calculation
            CutoffPath res = new CutoffPath(this, movements().size());
            if (res.movements().size() != movements.size()) {
                throw new IllegalStateException("Path has wrong size after cutoff");
            }
            return res;
        }
        // more post processing here
        sanityCheck();
        if (Princeps.settings().smoothPath.value) {
            IPath smoothed = smoothFlatRuns();
            if (smoothed != null) {
                return smoothed;
            }
        }
        return this;
    }

    /**
     * ANY-ANGLE smoothing: string-pull maximal flat, obstacle-free runs of MovementTraverse/MovementDiagonal into
     * single straight {@link SmoothTraverse} chords. Greedy line-of-sight: from each kept node, jump to the furthest
     * node whose direct chord is collision-safe. Returns {@code null} (keep the safe lattice path) on any problem.
     */
    private IPath smoothFlatRuns() {
        try {
            List<BetterBlockPos> newPos = new ArrayList<>();
            List<princeps.api.pathing.movement.IMovement> newMov = new ArrayList<>();
            newPos.add(path.get(0));
            final int n = movements.size();
            // Cap a single chord's block-length. Without it the greedy scan is O(L^3) per long DIAGONAL open-field run
            // (chordSafe recomputes an O(chord^2) swept box for every candidate k), which could stall path
            // post-processing on a big obstacle-free field. The cap bounds it to O(L*cap^2); it costs ZERO on real
            // terrain (merges are far shorter) and only splits a huge straight run into COLLINEAR chords -> same line,
            // a few more nodes, no smoothness loss.
            final int maxChord = Math.max(1, Princeps.settings().smoothMaxChord.value);
            int i = 0;
            while (i < n) {
                final Movement start = movements.get(i);
                int best = i;
                if (isFlatMergeable(start)) {
                    final BetterBlockPos src = start.getSrc();
                    int k = i;
                    while (k < n && isFlatMergeable(movements.get(k))
                            && movements.get(k).getSrc().y == src.y && movements.get(k).getDest().y == src.y) {
                        final BetterBlockPos dk = movements.get(k).getDest();
                        if (Math.max(Math.abs(dk.x - src.x), Math.abs(dk.z - src.z)) > maxChord) {
                            break; // chord would exceed the cap; longer runs continue as a fresh (collinear) chord
                        }
                        if (chordSafe(src, dk)) {
                            best = k;
                        }
                        k++;
                    }
                }
                if (best > i) { // merge i..best into one chord
                    final BetterBlockPos src = start.getSrc();
                    final BetterBlockPos dest = movements.get(best).getDest();
                    double summed = 0;
                    for (int k = i; k <= best; k++) {
                        summed += movements.get(k).getCost(context);
                    }
                    // getCost = min(euclidean chord walked at sprint, summed lattice cost): the chord is the actual
                    // (shorter) distance the bot walks, so this reflects true cost without ever EXCEEDING the summed
                    // lattice cost -> the executor's cost-verification (recalc vs self) can never cancel.
                    final double chord = Math.sqrt((double) (dest.x - src.x) * (dest.x - src.x)
                            + (double) (dest.z - src.z) * (dest.z - src.z));
                    final double cost = Math.min(chord * princeps.api.pathing.movement.ActionCosts.SPRINT_ONE_BLOCK_COST, summed);
                    Set<BetterBlockPos> swept = SmoothTraverse.sweptCells(src, dest, 0.35);
                    newMov.add(new SmoothTraverse(context.princeps, src, dest, cost, swept));
                    newPos.add(dest);
                    i = best + 1;
                } else {
                    newMov.add(start);
                    newPos.add(start.getDest());
                    i++;
                }
            }
            return new SmoothedPath(newPos, newMov, numNodes, goal);
        } catch (Exception e) {
            return null; // any failure -> fall back to the proven lattice path
        }
    }

    private boolean isFlatMergeable(Movement m) {
        if (!(m instanceof MovementTraverse) && !(m instanceof MovementDiagonal)) {
            return false;
        }
        if (m.getSrc().y != m.getDest().y) {
            return false; // only flat runs; ascend/descend/parkour/fall/pillar terminate a run
        }
        return m.toBreak(context.bsi).isEmpty() && m.toPlace(context.bsi).isEmpty(); // no dig/place waypoints
    }

    /** Every cell a 0.7-wide body sweeps along src->dest must have solid floor + clear body + clear head + no hazard. */
    private boolean chordSafe(BetterBlockPos src, BetterBlockPos dest) {
        for (BetterBlockPos c : SmoothTraverse.sweptCells(src, dest, 0.35)) {
            final int x = c.x, y = c.y, z = c.z;
            if (!MovementHelper.canWalkOn(context, x, y - 1, z, context.get(x, y - 1, z))) return false;
            if (!MovementHelper.canWalkThrough(context, x, y, z)) return false;
            if (!MovementHelper.canWalkThrough(context, x, y + 1, z)) return false;
            if (MovementHelper.avoidWalkingInto(context.get(x, y, z))) return false;
        }
        return true;
    }

    @Override
    public List<IMovement> movements() {
        if (!verified) {
            // edge case note: this is called during verification
            throw new IllegalStateException("Path not yet verified");
        }
        return Collections.unmodifiableList(movements);
    }

    @Override
    public List<BetterBlockPos> positions() {
        return Collections.unmodifiableList(path);
    }

    @Override
    public int getNumNodesConsidered() {
        return numNodes;
    }

    @Override
    public BetterBlockPos getSrc() {
        return start;
    }

    @Override
    public BetterBlockPos getDest() {
        return end;
    }
}
