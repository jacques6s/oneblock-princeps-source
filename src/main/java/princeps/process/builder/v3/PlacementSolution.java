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

import princeps.api.utils.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * One fully determined way to place one block: where to stand, exactly where inside that cell, what to click, where
 * on it, holding what, and what will land.
 *
 * <p>Everything the executor needs and nothing it may decide. V2's {@code Placement} carries seven fields with no
 * accessors and derives the rest at click time from the live player; this carries twelve and derives nothing, because
 * the whole point of the design is that the decision was made and proven at plan time.
 *
 * <p>The two components that do not exist in V2 are {@link #approach} and {@link #margin}, and they are the same
 * idea seen twice. V2 always walks to the block centre and takes whatever dominance that yields; V3 treats the exact
 * sub-block position as a free parameter and OPTIMISES it, then keeps the number it optimised so the planner can rank
 * solutions by it. Worked through for the upward-look family: the same stance gives margin 0.19 from the cell centre
 * and 0.41 from the optimised approach — the difference between a coin flip and a certainty, bought for free by
 * standing 0.3 blocks off centre.
 *
 * <p>Ordering is by {@link #margin} descending, and the planner takes the head. Nothing in this record is random,
 * jittered or time-dependent: the same predicted world and the same cell must produce the same solution, so that two
 * runs of the same build produce the same trace byte for byte.
 *
 * @param cell      the schematic cell being filled
 * @param desired   what the schematic wants there
 * @param stance    feet cell the bot places from
 * @param approach  exact sub-block position within {@link #stance} — a planner output, not the cell centre
 * @param against   the neighbour whose face is clicked; always {@code cell.relative(face.getOpposite())}
 * @param face      the face of {@link #against} that is clicked, i.e. the one pointing at {@link #cell}
 * @param aimPoint  exact world-space point on that face
 * @param rotation  derived from the crouched eye at {@link #approach} looking at {@link #aimPoint}, already through
 *                  the aim quantiser
 * @param margin    dominance slack in blocks; at or above {@link SolveBudget#MIN_MARGIN} for any accepted solution
 * @param approachTolerance how far the executor may stand from {@link #approach} and still be somewhere this click
 *                  was proven from, in blocks, measured horizontally. The occlusion counterpart of {@link #margin},
 *                  and the second half of plan 19: a proof that holds at one point is not a proof when the body may
 *                  arrive 0.08 blocks away, so {@link PlacementOracle} re-casts the CHOSEN solution's ray from the rim
 *                  of the arrival ball and records the widest radius over which the same cell and the same face are
 *                  still hit. {@link FineApproach#TOLERANCE} for an open cell, {@link FineApproach#TIGHT_TOLERANCE}
 *                  for one threading past a corner. Nothing constructs a solution that survives neither
 * @param predicted the state vanilla will produce for this click — compared against {@link #desired} by the
 *                  acceptance rules, not by equality
 * @param item      what has to be in hand. The ITEM and not a slot, and that distinction is the whole of the two-stage
 *                  slot resolution: the oracle proves a cell placeable when the item exists ANYWHERE in the 36-slot
 *                  inventory, because which hotbar slot the click will actually come out of is not knowable until
 *                  {@link HotbarSchedule#belady} has chosen a victim for it over the whole frozen order.
 *                  {@link HotbarSchedule#weave} writes that slot into {@link BuildAction#handSlot}. Carrying the item
 *                  rather than the block also matters on its own: {@code BlockPlaceHelper.matches} compares item
 *                  identity, so a hotbar swap between plan and click voids the click with no log line
 */
public record PlacementSolution(
        BlockPos cell,
        BlockState desired,
        BlockPos stance,
        Vec3 approach,
        BlockPos against,
        Direction face,
        Vec3 aimPoint,
        Rotation rotation,
        double margin,
        double approachTolerance,
        BlockState predicted,
        Item item
) {

    /**
     * The plan may only ask for what the body can do.
     *
     * <p>{@link #approachTolerance} is an instruction to the fine approach, and the fine approach is eight discrete
     * directions at a fixed sneak speed: below {@link FineApproach#ACHIEVABLE_TOLERANCE} no step improves, no key is
     * emitted, and the executor stands still for ever. A tolerance under the floor is therefore not a strict plan, it
     * is an unsatisfiable one, and it is refused where it is written rather than discovered 44 000 ticks into a run.
     *
     * <p>An {@link IllegalArgumentException} and not a clamp: clamping would silently execute a click from outside
     * the ball its sight line was proved over, which is the defect plan 19 exists to remove. The planner's answer to a
     * cell that needs tighter is {@link PlacementOracle.Rejection#OCCLUSION_BELOW_BODY_FLOOR}.
     */
    public PlacementSolution {
        if (!FineApproach.achievable(approachTolerance)) {
            throw new IllegalArgumentException("approach tolerance " + approachTolerance + " at " + cell
                    + " is below what the fine approach can hold (" + FineApproach.ACHIEVABLE_TOLERANCE
                    + "); refuse the solution instead of asking the body for it");
        }
    }

    /**
     * A solution before the occlusion proof has run — the widest tolerance, provisionally.
     *
     * <p>Every candidate is born here and only the one the planner actually takes is proved over the ball, because
     * the ray count is the planner's main cost and it is already 2.2 million. So this value is a claim the search has
     * not tested yet; {@link PlacementOracle#finish} either confirms it, replaces it with a tighter one, or drops the
     * candidate. It reaching an action unexamined would be the very defect plan 19 describes, which is why the check
     * lives at the single point where a solution becomes the answer rather than in each of the places one is built.
     */
    public PlacementSolution(BlockPos cell, BlockState desired, BlockPos stance, Vec3 approach, BlockPos against,
                             Direction face, Vec3 aimPoint, Rotation rotation, double margin, BlockState predicted,
                             Item item) {
        this(cell, desired, stance, approach, against, face, aimPoint, rotation, margin, FineApproach.TOLERANCE,
                predicted, item);
    }

    /** The same click, with the tolerance the occlusion proof actually granted it. */
    public PlacementSolution withApproachTolerance(double tolerance) {
        return new PlacementSolution(this.cell, this.desired, this.stance, this.approach, this.against, this.face,
                this.aimPoint, this.rotation, this.margin, tolerance, this.predicted, this.item);
    }

    /** Is this click's sight line so narrow that the fine approach has to be stricter than usual? Reported, because a
     *  build where this is true of many cells is a build standing in its own way. */
    public boolean isOcclusionTight() {
        return this.approachTolerance < FineApproach.TOLERANCE;
    }

    /**
     * The margin recorded for a cell whose look decides nothing at all — a full cube, a log placed on the axis the
     * face already fixes, anything whose landed state is the same from every legal aim.
     *
     * <p>Infinity rather than zero, and the distinction is not cosmetic: zero would mean "the dominant axis wins by
     * nothing", which is the coin flip {@link SolveBudget#MIN_MARGIN} exists to refuse, and every plain cobblestone in
     * the schematic would be reported TIGHT. There is no slack to measure here because there is nothing for the slack
     * to protect, so the value that reads correctly through every comparison the planner makes is the one that clears
     * any threshold and orders ahead of any measured margin.
     */
    public static final double UNCONSTRAINED_MARGIN = Double.POSITIVE_INFINITY;

    /** Eye-to-aim-point distance. Never above {@link PlayerPose#PLANNING_REACH} for an accepted solution. */
    public double reach(PlayerPose pose) {
        return pose.eyeAt(this.approach).distanceTo(this.aimPoint);
    }

    /** Is the margin below the acceptance threshold — a solution that exists but should be reported as TIGHT rather
     *  than taken silently? */
    public boolean isTight(SolveBudget budget) {
        return this.margin < budget.minMargin();
    }

    /**
     * The proof line written to {@code run/plan/<runid>-proof.txt}: cell, stance, approach, against, face, aim,
     * margin. One line per cell, so "why is cell 4 231 placed the way it is" is a grep and not a reconstruction.
     *
     * <p>No slot here, deliberately. The proof is about the GEOMETRY, and the geometry is the same whichever hotbar
     * slot the item is fetched into; the slot lives on the action, where the schedule put it, and appears on the plan
     * line. Printing it in both files would invite the two to drift into two different answers to one question.
     */
    public String toProofLine() {
        return String.format(Locale.ROOT,
                "cell %d,%d,%d %s stance %d,%d,%d approach %.2f,%.2f,%.2f against %d,%d,%d face %s "
                        + "aim %.3f,%.3f,%.3f yaw %.2f pitch %.2f margin %s%s item %s lands %s",
                this.cell.getX(), this.cell.getY(), this.cell.getZ(), this.desired,
                this.stance.getX(), this.stance.getY(), this.stance.getZ(),
                this.approach.x, this.approach.y, this.approach.z,
                this.against.getX(), this.against.getY(), this.against.getZ(), this.face,
                this.aimPoint.x, this.aimPoint.y, this.aimPoint.z,
                this.rotation.getYaw(), this.rotation.getPitch(),
                formatMargin(this.margin), formatTolerance(this.approachTolerance), this.item, this.predicted);
    }

    /** Printed only when it is not the default, so the 4 000 open cells read exactly as they did and the handful that
     *  thread past a corner are greppable by the one word that distinguishes them. */
    private static String formatTolerance(double tolerance) {
        return tolerance >= FineApproach.TOLERANCE ? ""
                : " tol " + BuildAction.describeTolerance(tolerance);
    }

    /** {@link #UNCONSTRAINED_MARGIN} spelled as a word, because a proof line reading {@code margin Infinity} invites
     *  the reader to hunt for the overflow that produced it. */
    private static String formatMargin(double margin) {
        return Double.isInfinite(margin) ? "free" : String.format(Locale.ROOT, "%.3f", margin);
    }
}
