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

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's body as four numbers — everything the oracle needs to know about the player, with no player attached.
 *
 * <p>This is class C of the seam analysis: the live code reads {@code ctx.player().getEyeHeight(Pose.CROUCHING)},
 * {@code ctx.playerController().getBlockReachDistance()} and {@code ctx.playerFeet()} in the middle of the geometry,
 * which is what ties the whole placement search to a running client. Passed as a value, the same geometry runs
 * headless and against a predicted world.
 *
 * <p>Every V3 placement is planned crouched. The crouch pose interpolates 1.62 to 1.27 over roughly six ticks after
 * the sneak key goes down, so the live eye lags the planned one right after arrival — the executor's convergence gate
 * absorbs that, but the plan is always proven against the settled crouched eye.
 *
 * @param crouchEyeHeight eye above feet while sneaking; vanilla 1.27
 * @param width           full body width; vanilla 0.6, so the centre may come within 0.3 of a cell wall
 * @param crouchHeight    full body height while sneaking; vanilla 1.5
 * @param reach           planning reach in blocks from eye to aim point
 */
public record PlayerPose(double crouchEyeHeight, double width, double crouchHeight, double reach) {

    /**
     * Planning reach, not the server's. {@code blockReachDistance} defaults to 4.5 and server-side reach checks sit at
     * 4.5 plus a tolerance; a plan that spends the last 0.3 blocks of that budget is a bet on latency and residual
     * drift going its way. 4.2 leaves the margin, and the oracle is choosing among stances anyway — the cost of
     * refusing the far ones is close to zero.
     */
    public static final double PLANNING_REACH = 4.2D;

    /** Vanilla crouched dimensions with {@link #PLANNING_REACH}. */
    public static final PlayerPose CROUCHED = new PlayerPose(1.27D, 0.6D, 1.5D, PLANNING_REACH);

    /**
     * Vanilla STANDING dimensions — for the one thing the builder does not do crouched.
     *
     * <p>An interaction must not sneak: a crouched right-click skips the clicked block's own use and places the held
     * item instead, so a repeater ends up buried rather than stepped. That makes its click the only one in the engine
     * that goes out from the standing eye, 0.35 blocks above the crouched one — and an aim proven from the wrong eye
     * is not the aim that will be cast. Over a 4-block reach that offset moves the hit point by several centimetres,
     * which is more than the margins this whole design is built on.
     *
     * <p>The component names still say "crouch" because the pose the planner uses for everything else is the
     * crouched one and renaming four fields would touch every caller for nothing. Read them as "this pose's eye
     * height" and "this pose's body height".
     */
    public static final PlayerPose STANDING = new PlayerPose(1.62D, 0.6D, 1.8D, PLANNING_REACH);

    /**
     * The eye, given the exact sub-block position the planner chose to stand at.
     *
     * <p>{@code approach} is the FEET point, matching {@code Entity#position} and the {@code (x + 0.5, y, z + 0.5)}
     * the oracle's {@code centreOf} hands out — {@code y} is the plane the body rests on, not the block's middle. So
     * the eye is a pure vertical offset, exactly as {@code RayTraceUtils.inferSneakingEyePosition} builds it from
     * {@code entity.getY() + getEyeHeight(Pose.CROUCHING)}. Getting this wrong by half a block is invisible in a
     * compile and fatal in a plan: every ray in the oracle starts here.
     */
    public Vec3 eyeAt(Vec3 approach) {
        return new Vec3(approach.x, approach.y + this.crouchEyeHeight, approach.z);
    }

    /**
     * The body box at that position. Used for check A1 — the box must not intersect the collision shape of the block
     * being placed, because vanilla's {@code isUnobstructed} counts the bot itself and will refuse the bot's own
     * placement. Rendered against the real collision shape rather than a full cube on purpose: a bottom slab occupies
     * only the lower half of its cell, so a stance whose head is in the upper half is legal and a full-cube test would
     * throw it away for nothing.
     */
    public AABB bodyAt(Vec3 approach) {
        return this.bodyAt(approach, this.crouchHeight);
    }

    /**
     * The body box with the height supplied rather than taken from the pose, so
     * {@link PlacementGeometry#playerBodyIntersects} and {@link #bodyAt(Vec3)} share ONE formula instead of writing
     * the same six coordinates twice. Two copies of this box that disagree by the sign on {@code halfWidth} would
     * still compile, still pass every test that only exercises one of them, and reject a different set of stances at
     * run time than the one the proof was written against.
     */
    public AABB bodyAt(Vec3 approach, double height) {
        double half = this.halfWidth();
        return new AABB(
                approach.x - half, approach.y, approach.z - half,
                approach.x + half, approach.y + height, approach.z + half);
    }

    /**
     * How far the body centre must stay from a cell wall: half the width, plus nothing. The oracle's approach grid
     * uses a wider inset than this ({@link SolveBudget#approachInset()}) so the body is provably inside its own
     * standing cell rather than merely not overlapping the next one.
     */
    public double halfWidth() {
        return this.width / 2.0D;
    }
}
