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
 * The vanilla jump, as a pure function of the tick.
 *
 * <p>Exists for one placement the engine could not make at all: a block whose landed facing is UP. Vanilla derives
 * that facing from the placer's look — {@code getNearestLookingDirection().getOpposite()} — so an upward-facing block
 * can only be produced by a click that looks DOWN, and looking down at a cell means being above it. For a cell in the
 * layer currently being built there is usually nothing above to stand on, which is why the basalt farm's sticky
 * pistons reported "no candidate stance is standable" for all 145 candidates and blocked 12 248 cells behind them.
 *
 * <p>The answer is the most ordinary movement a player has: stand in the cell, jump, and click the block below while
 * airborne. The block lands in the space just vacated, and because the eye is looking down as it does, it lands
 * facing up. Every Minecraft player has pillared this way; nothing here is exotic, and nothing here is a technique a
 * server could tell apart from a human.
 *
 * <h2>Why this can be proven rather than tried</h2>
 * Every other action in this engine is proven for a body that is settled and still. This one is proven for a body in
 * flight, which is only honest because the flight is deterministic: no randomness enters a vanilla jump. The three
 * constants below were read out of the game's own bytecode rather than remembered, and the recurrence is the game's:
 *
 * <pre>
 *   on the jump tick:   vy  = BASE_JUMP_POWER
 *   every tick after:   y  += vy
 *                       vy  = (vy - GRAVITY) * VERTICAL_DRAG
 * </pre>
 *
 * <p>What the planner gets from this class is the existence and the width of a window. What actually authorises the
 * click is the executor re-reading the live body, exactly as every other gate in the engine does — the arc says a
 * window is there, the live check says the body is in it. A mismatch between the two costs a jump, never a block.
 */
public final class JumpArc {

    /** {@code LivingEntity.BASE_JUMP_POWER}, read from the game jar. Blocks that modify it — honey, slime — change
     *  this, and a stance on one of them is not a jump-place candidate for that reason. */
    public static final double BASE_JUMP_POWER = 0.42D;

    /** The {@code gravity} attribute's default, read from {@code Attributes}. */
    public static final double GRAVITY = 0.08D;

    /** Vertical drag per tick, read from {@code LivingEntity}. */
    public static final double VERTICAL_DRAG = 0.98D;

    /** Ticks of arc worth computing. The feet are back below the launch point long before this; it exists so the
     *  loops below terminate on a constant rather than on a float comparison. */
    public static final int MAX_TICKS = 16;

    private JumpArc() {
    }

    /**
     * How far above the launch point the feet are, tick by tick. Index {@code i} is {@code i + 1} ticks after the
     * jump input, because the first entry already includes the jump tick's own movement.
     */
    public static double[] rise() {
        double[] rise = new double[MAX_TICKS];
        double velocity = BASE_JUMP_POWER;
        double height = 0.0D;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            height += velocity;
            rise[tick] = height;
            velocity = (velocity - GRAVITY) * VERTICAL_DRAG;
        }
        return rise;
    }

    /** The highest the feet ever get above the launch point. */
    public static double apex() {
        double best = 0.0D;
        for (double height : rise()) {
            best = Math.max(best, height);
        }
        return best;
    }

    /**
     * The ticks during which the feet are at least {@code clearance} above the launch point, as
     * {@code {first, last}} inclusive, or {@code null} when the jump never gets that high.
     *
     * <p>{@code clearance} is 1.0 for the case this class exists for: the body has to be entirely out of the cell it
     * is about to fill, and the cell is one block tall.
     *
     * <p>Returned as a window and not as a single tick on purpose. A single tick would have to be hit exactly, and a
     * client that drops one — which the bench does under a raised tick rate — would miss the placement and have to
     * jump again. A window of several ticks is hit by any tick inside it, so a dropped tick costs nothing.
     */
    public static int[] clearanceWindow(double clearance) {
        double[] rise = rise();
        int first = -1;
        int last = -1;
        for (int tick = 0; tick < rise.length; tick++) {
            if (rise[tick] >= clearance) {
                if (first < 0) {
                    first = tick;
                }
                last = tick;
            }
        }
        return first < 0 ? null : new int[] {first, last};
    }

    /**
     * Whether a jump from this stance can clear a full block at all.
     *
     * <p>One call rather than a comparison at each site, so that "can a body jump-place from here" has a single
     * answer in the codebase.
     */
    public static boolean clearsAFullBlock() {
        return clearanceWindow(1.0D) != null;
    }
}
