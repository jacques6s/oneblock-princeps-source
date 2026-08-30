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

package princeps.api.pathing;

import net.minecraft.core.BlockPos;

import java.util.function.LongPredicate;

/**
 * Where a walk may put its body into WATER instead of treating it as a wall — the "Wat-Erlaubnis".
 *
 * <p>Pathing refuses flowing liquid everywhere, and outside a build that refusal is right: a river is a place to be
 * swept out of, and the router has a whole world to go around it in. Inside an excavation it is exactly wrong. The
 * digger owns a one-cell corridor it cut itself, with solid rock under every cell of it; when a source it has just
 * exposed runs into that corridor, {@code canWalkThroughBlockState} answers NO for a fluid at level != 8,
 * {@code getMiningDurationTicks} turns that into {@code COST_INF}, and the excavation lane -- which may not break,
 * may not detour and may not scaffold -- has nothing left. Measured in run {@code a0a00867}: the bot standing in
 * water at 77,-51,68 with 77,-51,69 flooded, twenty-two movements considered, an open set of zero and a PathNode
 * map of one, for 1900 ticks.
 *
 * <p>So this is not "the digger may swim". It is the one-line permission a human takes for granted: <em>you may put
 * your feet in the ankle-deep water in the tunnel you just dug.</em> Three properties keep it that narrow:
 *
 * <ul>
 *   <li><b>Water only.</b> Lava is never licensed by any caller, and the rule below is asked about a specific cell,
 *       so a licence can never turn into "fluids are fine".</li>
 *   <li><b>Named cells only.</b> {@link #NONE} is the default everywhere; the excavation lane licenses exactly the
 *       two body cells of the single cardinal step it is asking for and nothing else.</li>
 *   <li><b>Planner and executor read the same object.</b> Same reason as {@link PlacementLicence}, same measured
 *       failure behind it: when the search allows a movement the driver refuses, the result is a route recomputed
 *       and thrown away every tick while the bot stands still.</li>
 * </ul>
 *
 * <p>Immutable and free of world access, so it is safe to read from the movement executor's hot path.
 */
public final class WadeLicence {

    /** No cell, anywhere. The default for ordinary navigation and for every lane that does not ask. */
    public static final WadeLicence NONE = new WadeLicence(null, "none");

    /** null for {@link #NONE}; otherwise the rule of one licensed route. */
    private final LongPredicate rule;
    private final String label;

    private WadeLicence(LongPredicate rule, String label) {
        this.rule = rule;
        this.label = label;
    }

    /**
     * Wading wherever {@code rule} says yes.
     *
     * <p>The caller supplies a rule that closes over immutable things only -- coordinates fixed when the route was
     * planned. A rule that reads a mutable field would let a route lose the permission its own plan was built on
     * halfway through, which is the defect {@link PlacementLicence} was created to remove.
     *
     * @param rule tested with {@link BlockPos#asLong(int, int, int)} packed coordinates
     */
    public static WadeLicence where(LongPredicate rule, String label) {
        if (rule == null) {
            return NONE;
        }
        return new WadeLicence(rule, label);
    }

    public boolean permitsWading(int x, int y, int z) {
        return rule != null && rule.test(BlockPos.asLong(x, y, z));
    }

    public boolean permitsWading(BlockPos at) {
        return at != null && permitsWading(at.getX(), at.getY(), at.getZ());
    }

    /** True for the default -- no cell may be waded, so callers can skip the packing entirely. */
    public boolean forbidsEverything() {
        return rule == null;
    }

    @Override
    public String toString() {
        return "wat-erlaubnis[" + label + "]";
    }
}
