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
 * What a walk is allowed to do to the world — the "Setz-Erlaubnis".
 *
 * <p>One value, attached to one route, answering one question: <em>may this walk put a block down, and where?</em>
 * Three forms, and they are exactly the owner's two lanes plus ordinary navigation:
 *
 * <ul>
 *   <li>{@link #NONE} — lane A. Not one block, not after twenty ticks, not ever.</li>
 *   <li>{@link #where} — lane B. Only where the rule of the build allows a helper block, which by the owner's
 *       standing rule means: only where the template wants air.</li>
 *   <li>{@link #UNRESTRICTED} — ordinary navigation outside a build, unchanged from today.</li>
 * </ul>
 *
 * <p><b>Why this is a value and not a predicate on a process.</b> Today the question is answered by
 * {@code BuilderProcess.scaffoldIsLicensedAt}, which reads live process fields — so the answer can change while a
 * route is being driven, and it does: every placement resets the fields the answer depends on. A lane B route was
 * therefore killed by the very helper block its own plan called for. What matters is not "set versus function" but
 * that nothing inside it can change once the route exists: {@link #where} closes over the SCHEMATIC, which is
 * fixed for the whole build.
 *
 * <p><b>Why the same object must serve planner and executor.</b> Measured, run {@code f159bc1d}: when the planner
 * thought a bridging move was allowed and the executor refused it, the result was 921 two-node paths against 922
 * "no path" ticks, 882 refusal gaps of exactly two ticks, the bot motionless for 1920 ticks and not one block
 * placed. The two ends must read the same answer from the same source, or the router plans forever what the mover
 * declines forever.
 *
 * <p>Immutable and free of world access, so it is safe to read from the movement executor's hot path.
 */
public final class PlacementLicence {

    /** Ordinary navigation: anything, anywhere. The default everywhere outside a build. */
    public static final PlacementLicence UNRESTRICTED = new PlacementLicence(null, true, "any");

    /** Lane A: nothing, anywhere. */
    public static final PlacementLicence NONE = new PlacementLicence(null, false, "none");

    /** null for the two constants; otherwise the rule of a lane B route. */
    private final LongPredicate rule;
    /** Only meaningful when {@link #rule} is null: true = everything, false = nothing. */
    private final boolean blanket;
    private final String label;

    private PlacementLicence(LongPredicate rule, boolean blanket, String label) {
        this.rule = rule;
        this.blanket = blanket;
        this.label = label;
    }

    /**
     * Lane B: a helper block wherever {@code rule} says yes.
     *
     * <p>The caller supplies a rule that closes over immutable things only — the schematic and the build origin.
     * A rule that reads a mutable field would reintroduce exactly the defect this type exists to remove, and the
     * reviewer's note on the old code is the warning: "a cost governs which path the SEARCH picks, not what a
     * running movement does".
     *
     * @param rule tested with {@link BlockPos#asLong(int, int, int)} packed coordinates
     */
    public static PlacementLicence where(LongPredicate rule, String label) {
        if (rule == null) {
            return NONE;
        }
        return new PlacementLicence(rule, false, label);
    }

    public boolean permitsPlacement(int x, int y, int z) {
        return rule == null ? blanket : rule.test(BlockPos.asLong(x, y, z));
    }

    public boolean permitsPlacement(BlockPos at) {
        return at != null && permitsPlacement(at.getX(), at.getY(), at.getZ());
    }

    /** True only for {@link #UNRESTRICTED}; used where a caller wants to skip the check entirely. */
    public boolean isUnrestricted() {
        return rule == null && blanket;
    }

    /** True for lane A — no placement anywhere, under any circumstances. */
    public boolean forbidsEverything() {
        return rule == null && !blanket;
    }

    @Override
    public String toString() {
        return "setz-erlaubnis[" + label + "]";
    }
}
