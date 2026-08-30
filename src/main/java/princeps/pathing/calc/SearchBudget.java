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

/**
 * How much work one path search may spend before it gives up.
 *
 * <p>Until this existed there was no node limit anywhere in this project — a search ended only when a wall clock ran
 * out, checked every 64 nodes in {@link AStarPathFinder}. That is affordable while a search happens now and then,
 * and it stops being affordable the moment something asks a question PER CELL. Measured on the etz-basalt run
 * {@code 20260806-122329-full}: single searches expanding ~240 000 nodes and considering ~5 000 000 movements,
 * repeated for every one of the last eighteen cells of a layer, each one ending in "no observable progress" — the
 * bot spent the last eleven thousand ticks of that run standing at 114,-60,128 while its pathfinder churned.
 *
 * <p>A budget is therefore a MANDATORY constructor parameter of the searcher rather than an optional setting. The
 * reason is the same one that keeps being learned the hard way here: an optional limit is a limit the next call site
 * forgets, and forgetting it is silent. With a required parameter the compiler asks every future caller the
 * question, and {@link #UNLIMITED} is a visible answer rather than an accident.
 *
 * <p>{@link #UNLIMITED} reproduces today's behaviour exactly: {@code Integer.MAX_VALUE} nodes is never reached
 * before one of the two timeouts. Every existing call site passes it, so introducing this type changes nothing —
 * which is deliberate. It is groundwork for the two-lane search, where lane A must be able to say "look briefly,
 * and if there is no complete route within that, hand over to lane B" without burning the full failure timeout of
 * two seconds on every cell.
 */
public final class SearchBudget {

    /** Today's behaviour: bounded only by the primary and failure timeouts. */
    public static final SearchBudget UNLIMITED = new SearchBudget(Integer.MAX_VALUE, "unlimited");

    private final int maxNodes;
    private final String name;

    private SearchBudget(int maxNodes, String name) {
        this.maxNodes = maxNodes;
        this.name = name;
    }

    /**
     * @param maxNodes how many nodes may be expanded; the search stops after this many and returns whatever it has
     * @param name     shown in the search's own log line, so a run says WHICH budget ended a search
     */
    public static SearchBudget ofNodes(int maxNodes, String name) {
        if (maxNodes < 1) {
            throw new IllegalArgumentException("a search budget of " + maxNodes + " nodes cannot find anything");
        }
        return new SearchBudget(maxNodes, name);
    }

    public int maxNodes() {
        return maxNodes;
    }

    public boolean isUnlimited() {
        return maxNodes == Integer.MAX_VALUE;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return isUnlimited() ? "budget[unlimited]" : "budget[" + name + ", " + maxNodes + " nodes]";
    }
}
