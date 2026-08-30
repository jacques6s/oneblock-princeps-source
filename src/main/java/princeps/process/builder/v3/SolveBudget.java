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

/**
 * What one call to {@link PlacementOracle#solve} is allowed to spend, and the thresholds it judges by.
 *
 * <p>Rays are the cost centre of the dry run: 15 004 cells at six faces times up to 24 stances times several aim
 * points is arithmetic that does not finish. The budget makes the tiering explicit — the fast path solves the ~90 %
 * of cells that are orientation-free full cubes with a single central ray, and only what fails it pays for the full
 * search. The cap exists so an unsolvable cell is answered in bounded time and handed to the scaffold planner rather
 * than searched harder.
 *
 * @param maxStances          candidate feet cells evaluated per cell; V2's measured working figure is 24
 * @param maxRays             hard ray cap per cell — exhausting it is an answer, not a failure to try
 * @param minMargin           dominance slack below which a solution is refused (see {@link #MIN_MARGIN})
 * @param maxReach            eye-to-aim-point limit; normally {@link PlayerPose#PLANNING_REACH}
 * @param approachStep        grid resolution when optimising the approach point, in blocks
 * @param approachInset       how far the body centre must stay from the standing cell's wall
 * @param approachRefineTopN  how many of the best stances get the approach grid rather than the cell centre
 * @param fastPath            allow the single-central-ray shortcut for orientation-free full cubes
 */
public record SolveBudget(
        int maxStances,
        int maxRays,
        double minMargin,
        double maxReach,
        double approachStep,
        double approachInset,
        int approachRefineTopN,
        boolean fastPath
) {

    /**
     * Dominance slack a solution must clear, in blocks.
     *
     * <p>{@code mostAlignedPointOnFace} compares strictly, so V2 accepts a margin of 0.02 — measured, real, and a coin
     * flip once floating point, the crouch-pose interpolation and the aim curve's residual have each had their say. A
     * placement that lands the wrong facing is not a retry, it is a break and a re-place, so the threshold is set
     * where the outcome stops depending on rounding. Cells solvable only below it are reported as TIGHT with the
     * number, never taken silently.
     */
    public static final double MIN_MARGIN = 0.15D;

    /** Candidate stances per cell. Matches V2's {@code MAX_STANCES_EVALUATED_PER_CALL}. */
    public static final int MAX_STANCES = 24;

    /** Ray cap per cell. Exhaustion routes the cell to the scaffold planner. */
    public static final int MAX_RAYS = 512;

    /** Approach grid resolution. 0.1 blocks: fine enough that the margin surface is sampled near its optimum, coarse
     *  enough that a 0.6-wide window is a handful of samples per axis. */
    public static final double APPROACH_STEP = 0.1D;

    /** Minimum distance from the standing cell's wall to the body centre. 0.3 is exactly half the body width, which is
     *  what makes check A2 — "the body fits in the standing cell" — hold by construction instead of by test. */
    public static final double APPROACH_INSET = 0.3D;

    /** Stances that get the approach grid. The grid is the expensive part and the ranking is already sound. */
    public static final int APPROACH_REFINE_TOP_N = 3;

    /** The budget the planner and the dry run use. */
    public static final SolveBudget DEFAULT = new SolveBudget(MAX_STANCES, MAX_RAYS, MIN_MARGIN,
            PlayerPose.PLANNING_REACH, APPROACH_STEP, APPROACH_INSET, APPROACH_REFINE_TOP_N, true);

    /** The full search with the fast path disabled — for the diagnostic pass that explains why a cell has no
     *  solution, where the shortcut would hide which stage rejected it. */
    public SolveBudget exhaustive() {
        return new SolveBudget(this.maxStances, this.maxRays, this.minMargin, this.maxReach, this.approachStep,
                this.approachInset, this.approachRefineTopN, false);
    }
}
