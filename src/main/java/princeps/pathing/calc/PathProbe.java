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
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.PathCalculationResult;
import princeps.pathing.movement.CalculationContext;
import princeps.utils.pathing.Favoring;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Ask "is there a route to this goal under these rules?" without the bot setting off.
 *
 * <p>This is the missing half of the two-lane build. Lane A and lane B are only distinguishable if somebody can run
 * a search, look at its answer, and THEN decide — and until now the only way to start a search was to name a goal,
 * at which point {@code PathingBehavior} began walking toward it. A question and a commitment were the same act.
 *
 * <p>Deliberately built out of pieces that were already public — the {@link AStarPathFinder} constructor and
 * {@link AbstractNodeCostSearch#calculate} — so this class touches nothing in {@code PathingBehavior}: not
 * {@code current}, not {@code next}, not {@code inProgress}, not {@code goal}. A probe therefore cannot disturb a
 * walk in progress, which is the property that makes it safe to ask the question at all.
 *
 * <p><b>One probe object per caller.</b> A single shared probe with one in-flight slot was the flaw an independent
 * reviewer found in the design this is built from: the builder's per-cell question and the mover's own planning
 * would cancel each other's searches and consume each other's results, and each theft costs a full search. Hold
 * your own instance and nobody can take your answer.
 *
 * <p><b>Threading.</b> {@link #start} must be called from the main thread, because building a
 * {@link CalculationContext} builds a {@code BlockStateInterface}, which refuses to be constructed anywhere else.
 * The search itself then runs on {@link Princeps#getExecutor()}. Poll from the main thread.
 */
public final class PathProbe {

    public enum Outcome {
        /** A route that actually reaches the goal. The only answer lane A is allowed to accept. */
        COMPLETE,
        /**
         * A route that gets closer but does not arrive. A* produces these routinely rather than admitting defeat —
         * it keeps the best node found under coefficients up to 10, which its own source describes as a path that is
         * "pretty terrible" but taken anyway. Treating this as success is how a lane that must not place blocks ends
         * up walking somewhere arbitrary and never escalating.
         */
        PARTIAL,
        /** No route at all. */
        NONE,
        /** Cancelled or threw. Never interpreted as an answer about the world. */
        ERROR,
    }

    public static final class Result {

        public final Outcome outcome;
        public final IPath path;
        /** Positions in the returned route; the number of STEPS is one less. Zero when there is no route. */
        public final int positions;
        public final long millis;

        Result(Outcome outcome, IPath path, long millis) {
            this.outcome = outcome;
            this.path = path;
            this.positions = path == null ? 0 : path.length();
            this.millis = millis;
        }

        public boolean reachedGoal() {
            return outcome == Outcome.COMPLETE;
        }

        @Override
        public String toString() {
            return outcome + (path == null ? "" : " " + positions + " positions") + " in " + millis + "ms";
        }
    }

    private final String name;
    private final AtomicReference<Result> finished = new AtomicReference<>();
    private volatile AbstractNodeCostSearch running;

    /**
     * @param name identifies this probe in the log; use something that says WHO is asking, not what it asks about
     */
    public PathProbe(String name) {
        this.name = name;
    }

    public boolean isRunning() {
        return running != null;
    }

    public String name() {
        return name;
    }

    /**
     * Begin a search. Returns false if this probe is already busy — the caller decides whether to wait or cancel,
     * because only the caller knows whether the old question is still worth answering.
     *
     * @param context MUST be built for threaded use ({@code new CalculationContext(princeps, true)}), and MUST be
     *                built on the main thread. Its rules are what makes this probe lane A or lane B: a context whose
     *                {@code costOfPlacingAt} is COST_INF cannot return a route that places a block, so "lane A found
     *                nothing" is a statement about the world and not about a filter applied afterwards.
     * @param budget  how many nodes this question is worth. See {@link SearchBudget} for why this is not optional:
     *                a search that fails runs to its timeout, and at the measured ~115000 nodes per second the
     *                default failure timeout of 2000 ms is about 240000 nodes — against a median of FOUR nodes for
     *                a search that succeeds.
     */
    public boolean start(IPlayerContext ctx, BetterBlockPos from, Goal goal, CalculationContext context,
                         SearchBudget budget, long primaryTimeoutMs, long failureTimeoutMs) {
        if (running != null) {
            return false;
        }
        if (goal == null) {
            throw new IllegalArgumentException("a probe without a goal has nothing to answer");
        }
        if (!context.safeForThreadedUse) {
            throw new IllegalStateException("a probe runs off the main thread; build its context with"
                    + " new CalculationContext(princeps, true)");
        }
        // The same favouring the driving search uses (PathingBehavior.createPathfinder), including mob avoidance.
        // A probe judged under different favouring than the walk would be answering a slightly different question,
        // and the whole point of the probe is that its answer predicts the walk.
        Favoring favoring = new Favoring(ctx, null, context);
        AStarPathFinder pathfinder = new AStarPathFinder(
                from, from.getX(), from.getY(), from.getZ(), goal, favoring, context, budget);
        finished.set(null);
        running = pathfinder;
        final long startedAt = System.currentTimeMillis();
        Princeps.getExecutor().execute(() -> {
            Result result;
            try {
                PathCalculationResult calc = pathfinder.calculate(primaryTimeoutMs, failureTimeoutMs);
                IPath path = calc.getPath().orElse(null);
                Outcome outcome;
                switch (calc.getType()) {
                    case SUCCESS_TO_GOAL:
                        outcome = Outcome.COMPLETE;
                        break;
                    case SUCCESS_SEGMENT:
                        outcome = Outcome.PARTIAL;
                        break;
                    case FAILURE:
                        outcome = Outcome.NONE;
                        break;
                    default:
                        outcome = Outcome.ERROR;
                        break;
                }
                result = new Result(outcome, outcome == Outcome.COMPLETE || outcome == Outcome.PARTIAL ? path : null,
                        System.currentTimeMillis() - startedAt);
            } catch (RuntimeException e) {
                // A probe that throws must not take the build with it: an unanswered question is a reason to try
                // something else, never a reason to stop.
                result = new Result(Outcome.ERROR, null, System.currentTimeMillis() - startedAt);
            } finally {
                running = null;
            }
            finished.set(result);
        });
        return true;
    }

    /** The answer, once, or null while the search is still running. Call from the main thread. */
    public Result poll() {
        return finished.getAndSet(null);
    }

    /** Stop caring about the answer. Safe to call at any time, including when nothing is running. */
    public void cancel() {
        AbstractNodeCostSearch search = running;
        if (search != null) {
            search.cancel();
        }
        finished.set(null);
    }
}
