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

import princeps.Princeps;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.movement.ActionCosts;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.SettingsUtil;
import princeps.pathing.calc.openset.BinaryHeapOpenSet;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.Moves;
import princeps.utils.pathing.BetterWorldBorder;
import princeps.utils.pathing.Favoring;
import princeps.utils.pathing.MutableMoveResult;

import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final CalculationContext calcContext;
    private final SearchBudget budget;
    private boolean exhaustedSearch;
    private boolean encounteredUnknownChunk;

    /** True only after frontier exhaustion or the explicit node budget, never timeout/chunk limit/cancellation. */
    boolean exhaustedSearch() {
        return exhaustedSearch;
    }

    /** A single unknown chunk makes negative geometry evidence incomplete, even below the chunk-fetch limit. */
    void recordSearchEnd(boolean outOfBudget, BinaryHeapOpenSet frontier, int unknownChunks) {
        exhaustedSearch = !cancelRequested && !encounteredUnknownChunk && unknownChunks == 0
                && (outOfBudget || frontier.isEmpty());
    }

    /** Separate from the fetch-limit counter, which deliberately omits dynamic-XZ movements. */
    void recordUnknownChunk() {
        encounteredUnknownChunk = true;
    }

    /**
     * @param budget how many nodes this search may expand. MANDATORY rather than optional on purpose — see
     *               {@link SearchBudget}. Pass {@link SearchBudget#UNLIMITED} for today's behaviour.
     */
    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring,
                           CalculationContext context, SearchBudget budget) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
        if (budget == null) {
            throw new IllegalArgumentException("a search without a budget is the thing this parameter exists to"
                    + " prevent; pass SearchBudget.UNLIMITED to keep the old behaviour");
        }
        this.budget = budget;
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        int minY = calcContext.world.dimensionType().minY();
        int height = calcContext.world.dimensionType().height();
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = new BetterWorldBorder(calcContext.world.getWorldBorder());
        long startTime = System.currentTimeMillis();
        boolean slowPath = Princeps.settings().slowPath.value;
        if (slowPath) {
            logDebug("slowPath is on, path timeout will be " + Princeps.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
        }
        long primaryTimeoutTime = startTime + (slowPath ? Princeps.settings().slowPathTimeoutMS.value : primaryTimeout);
        long failureTimeoutTime = startTime + (slowPath ? Princeps.settings().slowPathTimeoutMS.value : failureTimeout);
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        int pathingMaxChunkBorderFetch = Princeps.settings().pathingMaxChunkBorderFetch.value; // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
        double minimumImprovement = Princeps.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
        Moves[] allMoves = Moves.values();
        boolean outOfBudget = false;
        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            // Checked before the clock, and every node rather than every 64: a budget is a promise about work done,
            // and one that can be overrun by up to 63 nodes is a weaker promise than it looks. The comparison is an
            // int compare against a field, so it costs nothing next to the movement expansion below it.
            if (numNodes >= budget.maxNodes()) {
                outOfBudget = true;
                break;
            }
            if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    break;
                }
            }
            if (slowPath) {
                try {
                    Thread.sleep(Princeps.settings().slowPathTimeDelayMS.value);
                } catch (InterruptedException ignored) {}
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                // THE NUMBER NOBODY HAD, and its absence made every estimate of search cost wrong by an order of
                // magnitude. The three println lines after the loop -- movements considered, open set size, PathNode
                // map size, nodes per second -- are on the FAILURE path: a search that reaches its goal returns HERE
                // and prints none of them. So every node count in every log of this project belongs to a search that
                // did NOT arrive, which is why "a 47-block path costs the same as a 7-block path" came out of the
                // data: both numbers were really the wall clock (500 ms primary / 2000 ms failure) in disguise, at
                // the measured ~115000 nodes per second.
                //
                // A budget for lane A has to be derived from the searches that SUCCEED, and until this line existed
                // there was no way to see one. Printed at debug level, so it costs nothing unless someone is looking.
                System.out.println("reached goal after " + numNodes + " nodes, " + numMovementsConsidered
                        + " movements, " + (System.currentTimeMillis() - startTime) + "ms");
                logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
                return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
            }
            for (Moves moves : allMoves) {
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    recordUnknownChunk();
                    // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
                    if (!moves.dynamicXZ) { // only increment the counter if the movement would have gone out of bounds guaranteed
                        numEmptyChunk++;
                    }
                    continue;
                }
                if (!moves.dynamicXZ && !worldBorder.entirelyContains(newX, newZ)) {
                    continue;
                }
                if (currentNode.y + moves.yOffset > height || currentNode.y + moves.yOffset < minY) {
                    continue;
                }
                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                numMovementsConsidered++;
                double actionCost = res.cost;
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s calculated implausible cost %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            actionCost));
                }
                if (!calcContext.isPathPositionAllowed(res.x, res.y, res.z)) {
                    continue;
                }
                // check destination after verifying it's not COST_INF -- some movements return COST_INF without adjusting the destination
                if (moves.dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) { // see issue #218
                    continue;
                }
                // Base Hunter Y ceiling: block only NET-UPWARD movement that would rise above the cap. A level or
                // downward move is always allowed — even from above the cap — so a player who ends up above it
                // (knockback, rising water, or a stale latch after a teleport/reconnect) can always descend back
                // out instead of stranding the pathfinder with an empty open set. Off by default (short-circuits).
                if (calcContext.baseHuntYCeiling && res.y > calcContext.baseHuntMaxY && res.y > currentNode.y) {
                    continue;
                }
                if (!moves.dynamicXZ && (res.x != newX || res.z != newZ)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at x z %s %s instead of %s %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            SettingsUtil.maybeCensor(res.x),
                            SettingsUtil.maybeCensor(res.z),
                            SettingsUtil.maybeCensor(newX),
                            SettingsUtil.maybeCensor(newZ)));
                }
                if (!moves.dynamicY && res.y != currentNode.y + moves.yOffset) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at y %s instead of %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            SettingsUtil.maybeCensor(res.y),
                            SettingsUtil.maybeCensor(currentNode.y + moves.yOffset)));
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                if (isFavoring) {
                    // see issue #18
                    actionCost *= favoring.calculate(hashCode);
                }
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double tentativeCost = currentNode.cost + actionCost;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);//dont double count, dont insert into open set if it's already there
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }
        if (cancelRequested) {
            return Optional.empty();
        }
        recordSearchEnd(outOfBudget, openSet, numEmptyChunk);
        System.out.println(numMovementsConsidered + " movements considered");
        System.out.println("Open set size: " + openSet.size());
        System.out.println("PathNode map size: " + mapSize());
        System.out.println((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
        if (outOfBudget) {
            // Said out loud, because "the search stopped early" and "the search found nothing" look identical from
            // the outside and mean opposite things: the first is a budget that may be too small, the second is a
            // world that has no route in it. Every past investigation into a stalled build had to guess which.
            logDebug("Search ended on its node budget (" + budget + ") after " + numNodes + " nodes; whatever it"
                    + " returns is the best it had, not the best there is");
        }
        Optional<IPath> result = bestSoFar(true, numNodes);
        if (result.isPresent()) {
            logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        }
        return result;
    }
}
