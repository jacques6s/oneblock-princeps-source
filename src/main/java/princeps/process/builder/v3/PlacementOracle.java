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
import princeps.api.utils.RotationUtils;
import princeps.process.builder.BuildTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Given a predicted world and a cell, every way to place it — ranked by how much the aim wins by.
 *
 * <p>Layer 1. Pure: no {@code IPlayerContext}, no {@code Level}, no {@code LocalPlayer}, no {@code Princeps.settings()}.
 * The world arrives as a {@link PredictedWorld}, the body as a {@link PlayerPose}, the inventory as a list of
 * stacks, the acceptance rules as a {@link V3Settings}, and the aim quantiser as a {@link UnaryOperator} — the five
 * cuts that turn V2's {@code simulatePlacement}, {@code orientationAchievableFrom} and {@code placementStancesFor}
 * from client-bound methods into functions.
 *
 * <p>V2's equivalent mutates the live player's rotation and restores it in a {@code finally} — twenty-four stances by
 * six faces by five aim points per cell per tick, not re-entrant and not thread-safe. That mutation is the single
 * thing this class exists to remove. It also means the oracle can run off the main thread, which is what makes a
 * whole-schematic dry run cost seconds instead of minutes.
 *
 * <h2>Ranking, not first-match</h2>
 * <p>{@link #solve} returns solutions ordered by {@link PlacementSolution#margin} descending, and the planner takes
 * the head. Taking the first workable answer is what V2 does and it is why a piston can land facing the wrong way
 * after a search that "succeeded": the first stance frequently wins the dominance comparison by 0.02 blocks, which is
 * inside the noise of the crouch-pose interpolation. Ranking costs nothing — the candidates were enumerated anyway.
 *
 * <h2>The bot's own body</h2>
 * <p>Vanilla's {@code isUnobstructed} tests the placed block's collision shape against every entity INCLUDING the bot,
 * so a stance whose body overlaps the target refuses its own placement. Worse, {@code BlockItem.getPlacementState}
 * folds {@code canSurvive} and {@code isUnobstructed} into one null, and V2 reports both as "canPlace false" — which
 * reads as "the target cell is obstructed" and sends the search hunting for an obstruction that is the bot. A soul
 * sand cell in open air did exactly that. The oracle therefore checks the body explicitly, four ways, at the approach
 * point: A1 the body box misses the target's collision shape, A2 the body fits inside the standing cell (free by
 * construction from {@link SolveBudget#approachInset()}), A3 the eye is not inside a solid block, A4 the eye-to-aim
 * ray is clear.
 *
 * <p>A1 is compared against the real collision shape and not a full cube, on purpose: a bottom slab occupies only the
 * lower half of its cell, so a body standing in the upper half is legal, and a full-cube test would discard those
 * stances for nothing.
 *
 * <p>Foreign entities are not planned around — they move. They are a condition of the executor's click gate instead:
 * an entity standing in the target cell means the gate does not fire this tick, which is the same mechanism as the
 * place-cooldown and needs no new state, no queue and no watchdog.
 *
 * <h2>What is PROVEN here and what is deferred</h2>
 * <p>Vanilla has no player-free {@code getStateForPlacement}: every {@code UseOnContext} constructor takes a
 * {@code Player} and {@code BlockItem.canPlace} wants {@code CollisionContext.placementContext(player)}. So this class
 * cannot ask vanilla what would land and does not try. It proves the GEOMETRIC half exactly — which face is clicked,
 * which look axis dominates and by how much, that the line of sight is clear, that the body is not in the way — and
 * hands the exact state comparison to the executor's live gate, which re-runs the real simulation against the real
 * crosshair before a click goes out. {@link #simulate} lists property by property which side of that line each
 * placement-relevant property falls on. The three families whose landed state depends on their NEIGHBOURS rather than
 * on the aim are finished by {@link PlacementFamilies}.
 */
public final class PlacementOracle {

    /** Why a candidate was refused, in the order the search asks. Every failure the dry run reports names one of
     *  these, because "no stance yields the required look" and "every stance was out of reach" are different problems
     *  with different fixes, and a report that cannot tell them apart costs a day per occurrence. */
    public enum Rejection {

        /** The cell lies outside the captured snapshot box. */
        OUT_OF_BOUNDS,

        /** The snapshot never saw this cell's footprint — the answer would be a guess, so it is not given. */
        UNLOADED_CHUNK,

        /** The cell already holds something that is not {@code desired} and was not planned to be broken. */
        CELL_OCCUPIED,

        /** Nothing the bot is carrying, in any of its 36 slots, places this block. Not "nothing on the hotbar": which
         *  hotbar slot serves a placement is decided by {@link HotbarSchedule#belady} long after this search, so the
         *  only question the oracle can honestly ask is whether the material exists at all. */
        NO_ITEM,

        /** The candidate stance is not standable in the predicted world. */
        STANCE_NOT_STANDABLE,

        /**
         * The stance cell is standable in general, but this exact sub-block approach is not supported at its proven
         * feet height.
         *
         * <p>This is deliberately separate from {@link #STANCE_NOT_STANDABLE}. A stair is a perfectly valid stance
         * while only part of its cell reaches the upper step; calling the whole stance bad hides the actionable fact
         * that the approach raster selected the cut-out part of that same stair.
         */
        APPROACH_NOT_SUPPORTED,

        /**
         * The approach is supported before the click, but the physical result of this very placement would remove
         * that support while the body is still standing there.
         *
         * <p>Neighbour-shaped stairs are the measured case: placing an adjacent stair can turn the straight stair
         * under the bot into an outer corner in the same server tick. A pre-click proof accepts the click and the
         * body immediately falls; this reason names the missing half of that proof.
         */
        POST_ACTION_NOT_SUPPORTED,

        /** A journal entry or the caller's filter forbade this (cell, stance, face) triple. */
        STANCE_FILTERED,

        /** Nothing solid on any allowed face of the cell to click against. */
        NOTHING_SOLID_TO_CLICK,

        /** The body box at the approach point intersects the block being placed — check A1. */
        BODY_INTERSECTS_TARGET,

        /** The crouched eye sits inside a solid cell — check A3. */
        EYE_INSIDE_BLOCK,

        /** Eye to aim point exceeds the planning reach. */
        OUT_OF_REACH,

        /** The ray reached a different block or a different face than intended. */
        RAY_MISSED_FACE,

        /** Something solid stands between the eye and the aim point — check A4. */
        RAY_OBSTRUCTED,

        /** No point on this face can make the required look direction the dominant axis. */
        LOOK_NOT_DOMINANT,

        /** A solution exists but its dominance slack is under {@link SolveBudget#minMargin()}. Reported as TIGHT with
         *  the number rather than taken silently. */
        MARGIN_TOO_TIGHT,

        /** The simulation lands a state the acceptance rules refuse. */
        WRONG_STATE_WOULD_LAND,

        /** Placement would land, but the required bucket follow-up cannot be aimed at the placed block. */
        FOLLOWUP_UNREACHABLE,

        /** {@code canSurvive} is false for the desired state at the cell in the predicted world. */
        CANNOT_SURVIVE,

        /** The target cell's own volume is obstructed by something other than the bot. */
        SPACE_OBSTRUCTED,

        /** The ray cap was reached first. An answer, not a failure to try — the cell goes to the scaffold planner. */
        RAY_BUDGET_EXHAUSTED,

        /**
         * The STANCE cap was reached first: {@link SolveBudget#maxStances()} stances had already been evaluated, so
         * this standable, clickable candidate was never tried.
         *
         * <p>Split out from {@link #RAY_BUDGET_EXHAUSTED} because for a long time it wore that name, and the two are
         * different findings with different fixes. Every one of the 56 budget lines in the etz-basalt report was this
         * one — in all of them {@code stancesTried − notStandable − exhausted} was exactly 24, the stance cap — while
         * the text said "ray budget spent after 11" and the highest ray count on any cell was 298 against a cap of
         * 512. The ray cap has never fired anywhere. Raising it would not have been a mask, it would have been a
         * no-op, and the report was the thing that made it look otherwise.
         */
        STANCE_BUDGET_EXHAUSTED,

        /**
         * The sight line holds from the approach POINT but not from the whole ball the executor may arrive in — not
         * even at {@link FineApproach#TIGHT_TOLERANCE}. Plan 19's rejection.
         *
         * <p>Last in the enum although the search asks it last of all, in {@link #finish}, and both facts are
         * deliberate. It is asked last because it is the only check that runs on the CHOSEN solution rather than on
         * every candidate — eight rays are affordable once per cell and unaffordable 2.2 million times — and it is
         * ordered last because {@link Solve#dominantRejection} keeps the earlier reason on a tie, and a cell whose
         * candidates mostly failed for a reason of their own should still be explained by that reason.
         *
         * <p>The measured case: cell {@code 81,-60,81}, stance {@code 82,-60,82}, clicking the south face of
         * {@code 81,-60,80}. From the proven point the ray cleared the corner of {@code 82,-60,81} — blue ice the bot
         * had placed itself as action 101 — by 0.019 blocks. The bot arrived 0.037 away, which is a CORRECT arrival
         * inside a 0.08 tolerance, and from there the ray hit that corner. Everything downstream then did exactly the
         * right thing: the gate refused, the engine re-planned three times, reproduced the identical solution, and
         * stopped by name. The plan underneath it was what was wrong.
         */
        OCCLUSION_NOT_ROBUST,

        /**
         * The sight line holds from the approach POINT, and the widest arrival ball it survives is narrower than
         * {@link FineApproach#ACHIEVABLE_TOLERANCE} — so the geometry works and the BODY cannot deliver it.
         *
         * <p>Split out from {@link #OCCLUSION_NOT_ROBUST} because it is a different finding with a different fix, and
         * because for one release the engine's answer to it was to emit the action anyway with a 0.02 tolerance the
         * fine approach could not reach. That is the freeze at {@code 92.476,-60,93.531}: 44 000 ticks, velocity zero,
         * no divergence, no log line. The tolerance was not merely optimistic, it was below
         * {@link FineApproach#CONTROLLER_STALL_RADIUS}, where no key improves and none is emitted.
         *
         * <p>What it tells a human is precise and actionable in a way the generic reason is not: the stance is
         * geometrically correct and something the bot itself placed is grazing the ray. Clearing that neighbour, or
         * building the cell a layer earlier, makes it placeable. {@link #OCCLUSION_NOT_ROBUST} says the opposite —
         * that no arrival at all works from there, so the stance is the thing to change.
         *
         * <p>Last in the enum for the same reason its sibling is: {@link Solve#dominantRejection} keeps the earlier
         * reason on a tie, and both of these are asked once, in {@link #finish}, on the chosen solution only.
         */
        OCCLUSION_BELOW_BODY_FLOOR
    }

    /**
     * A veto on (cell, stance, face) triples, supplied by the planner.
     *
     * <p>This is what makes the two re-entries of the design safe. When the server refuses a placement, the triple
     * goes into a journal, the journal is an INPUT to the next solve, and the re-plan therefore cannot hand back the
     * same solution. Without it, "re-plan on rejection" is an infinite loop at click cadence — which is exactly the
     * risk V2's design notes were right to be afraid of.
     */
    @FunctionalInterface
    public interface StanceFilter {

        boolean allows(BlockPos cell, BlockPos stance, Direction face);

        /** Allows everything — the dry run's default before any rejection has been observed. */
        StanceFilter ALL = (cell, stance, face) -> true;
    }

    /**
     * Everything one solve produced, including why the failures failed.
     *
     * @param solutions      ordered by {@link PlacementSolution#margin} descending; empty means no solution. The HEAD
     *                       — and only the head — has been proved over the whole approach ball and carries the
     *                       {@link PlacementSolution#approachTolerance} that proof granted it; the tail is ranked but
     *                       unproved, because eight rays apiece to decorate a number the report prints is not what
     *                       this planner's ray budget is for
     * @param tight          solutions that cleared every check except the margin threshold, same ordering; the input
     *                       to a TIGHT report line, never used silently
     * @param rejections     one counter per {@link Rejection}, indexed by {@code ordinal()} — the report's
     *                       "243 candidate stances -> 194 not standable, 49 nothing solid to click, 0 workable"
     * @param stancesTried   how many candidate stances were actually evaluated
     * @param raysCast       rays spent, against {@link SolveBudget#maxRays()}
     * @param rayBudgetExhausted    the RAY cap stopped the search — {@link SolveBudget#maxRays()} rays were spent
     * @param stanceBudgetExhausted the STANCE cap stopped it — {@link SolveBudget#maxStances()} stances were evaluated
     *                              and the rest were never tried. Separate from the ray cap because they are different
     *                              findings: one says the cell is expensive, the other says the window is crowded, and
     *                              for a long time both printed the ray one's sentence.
     */
    public record Solve(
            List<PlacementSolution> solutions,
            List<PlacementSolution> tight,
            int[] rejections,
            int stancesTried,
            int raysCast,
            boolean rayBudgetExhausted,
            boolean stanceBudgetExhausted
    ) {

        /** The highest-margin solution, or empty. */
        public Optional<PlacementSolution> best() {
            return this.solutions.isEmpty() ? Optional.empty() : Optional.of(this.solutions.get(0));
        }

        public boolean solved() {
            return !this.solutions.isEmpty();
        }

        /** The rejection that accounts for most of the refused candidates — the one the report leads with. */
        public Rejection dominantRejection() {
            Rejection dominant = null;
            int most = 0;
            for (Rejection reason : REJECTIONS) {
                int count = this.rejections[reason.ordinal()];
                // Strictly greater, so a tie keeps the EARLIER reason: the enum is ordered by the sequence the search
                // asks its questions in, and the earliest question a candidate fails is the one that explains it.
                if (count > most) {
                    most = count;
                    dominant = reason;
                }
            }
            return dominant;
        }

        /** "194 not standable, 49 nothing solid to click, 0 workable", built from {@link #rejections}. */
        public String explain() {
            List<Rejection> raised = new ArrayList<>();
            for (Rejection reason : REJECTIONS) {
                if (this.rejections[reason.ordinal()] > 0) {
                    raised.add(reason);
                }
            }
            raised.sort(Comparator.comparingInt((Rejection reason) -> -this.rejections[reason.ordinal()])
                    .thenComparingInt(Enum::ordinal));
            StringBuilder line = new StringBuilder("of ").append(this.stancesTried).append(" candidate stances: ");
            for (int i = 0; i < raised.size(); i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(this.rejections[raised.get(i).ordinal()]).append(' ').append(phrase(raised.get(i)));
            }
            if (raised.isEmpty()) {
                line.append("nothing refused");
            }
            line.append(", ").append(this.solutions.size()).append(" would work");
            if (!this.tight.isEmpty()) {
                line.append(" (").append(this.tight.size()).append(" solvable only below the margin threshold)");
            }
            // Name the cap that stopped it, and say how many candidates it left untried. The old form said "ray budget
            // spent after N" for BOTH caps and put the ray count after it, so a stance-cap stop -- which is what every
            // recorded one has been -- read as a ray problem and invited raising a limit that has never been reached.
            if (this.rayBudgetExhausted) {
                line.append(" -- RAY cap hit: all ").append(this.raysCast).append(" rays spent");
            }
            if (this.stanceBudgetExhausted) {
                line.append(" -- STANCE cap hit: ")
                        .append(this.rejections[Rejection.STANCE_BUDGET_EXHAUSTED.ordinal()])
                        .append(" further candidate stance(s) never evaluated (")
                        .append(this.raysCast).append(" rays spent, cap not reached)");
            }
            return line.toString();
        }

        private static String phrase(Rejection reason) {
            return reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /** {@code values()} clones its backing array on every call and both the search and the report walk it per cell. */
    private static final Rejection[] REJECTIONS = Rejection.values();

    /** Horizontal half-width of the stance window, in cells. V2's {@code placementStancesFor} uses the same window and
     *  its size is what makes the dry run's "of 243 candidate stances" line comparable across the two builders.
     *
     *  <p>Public because {@link OrderPlanner} bounds a scan by it: a stance can only serve cells inside this window,
     *  so a world change further away than this plus the invalidation radius cannot give a stance a new solution. */
    public static final int STANCE_WINDOW_RADIUS = 3;

    /** Highest stance worth considering, relative to the cell. One above: any higher and the crouched eye looks down
     *  a slope too shallow to reach the cell's own faces within {@link PlayerPose#PLANNING_REACH}. */
    private static final int HIGHEST_STANCE_OFFSET = 1;

    /** Sort penalty for standing on the blind side of the face the state forces us to click — a west-facing sign hangs
     *  on the east neighbour's west face, which simply is not visible from the east. Ported from V2 (:4551), where
     *  nearest-first ordering without it spent the whole evaluation cap on geometrically impossible stances. */
    private static final long WRONG_SIDE_PENALTY = 10_000L;

    /** Beside the facing axis rather than on it. */
    private static final long NEAR_AXIS_PENALTY = 100_000L;

    /** Off the facing axis, or on the wrong side of a vertical look. Ordered last, never dropped. */
    private static final long OFF_AXIS_PENALTY = 1_000_000L;

    /**
     * The yaw the raw rotation is wrapped relative to.
     *
     * <p>{@code RotationUtils.calcRotationFromVec3d} exposes only its three-argument form; the two-argument one is
     * private. The third argument exists to keep a turn short by expressing the target near the CURRENT yaw, and the
     * planner has no current yaw — a plan is proven hours before the bot stands there. A fixed reference is therefore
     * not a stand-in for the live rotation, it is the correct choice: it makes the stored yaw canonical, so two runs of
     * the same plan record the same number instead of two representatives of the same angle. The executor wraps it
     * against the live rotation when it hands the aim to {@code ILookBehavior}.
     */
    private static final Rotation PLANNING_REFERENCE = new Rotation(0.0F, 0.0F);

    /**
     * The arrival tolerances the final arrival proof will try, widest first. The first whose supported approach
     * corridor, body geometry, orientation and rim rays all survive is written into the solution; a solution
     * surviving none of them is refused.
     *
     * <p>Rungs and not a bisection, on purpose. A continuous answer would invite the fine approach to chase
     * numbers like 0.043 — below {@link FineApproach#CONTROLLER_STALL_RADIUS}, where the eight-direction controller
     * has no step that improves and therefore emits none. The strictness has a floor set by the BODY, not by the
     * geometry, and the LAST rung of this ladder is exactly that floor: the planner asks for nothing tighter, ever.
     *
     * <p>The ladder opens UPWARD as far as 0.30 because the old top rung was the engine's most common live stopper.
     * A cell in the open has a proven standing region most of a block wide — the owner's "hundreds of positions you
     * could place this from" — and demanding the body stop within 8cm of one arbitrary point inside it made an easy
     * placement fail for a reason that has nothing to do with the placement. The widest rung that survives the rim is
     * taken, so an open cell now hands the body a region it cannot miss, and a cramped one still narrows to the floor.
     *
     * <p>Cost stays where it was for the common cell: the scan is widest-first and stops at the first survivor, so an
     * open cell pays one rung's worth of rim samples exactly as before. Only a cell that has to narrow pays for the
     * rungs it discards.
     */
    static final double[] APPROACH_TOLERANCE_LADDER = {0.30D, 0.18D, 0.12D,
            FineApproach.TOLERANCE, FineApproach.ACHIEVABLE_TOLERANCE};

    static {
        for (double rung : APPROACH_TOLERANCE_LADDER) {
            if (!FineApproach.achievable(rung)) {
                throw new ExceptionInInitializerError("approach tolerance ladder rung " + rung
                        + " is below what the fine approach can hold (" + FineApproach.ACHIEVABLE_TOLERANCE + ")");
            }
        }
    }

    /**
     * Points sampled on the rim of the arrival ball. Eight — the plan's upper bound — because the obstruction that
     * motivates this is the CORNER of a neighbouring cell, and a corner is a feature the four axis-aligned samples
     * can straddle: the measured miss at {@code 82,-60,81} lies on the diagonal from the stance, which is exactly
     * where a four-point rim has no sample.
     *
     * <p>Stored as exact literals rather than computed from {@code Math.cos}. Determinism is measured here and
     * {@code Math.cos} is only required to be within one ulp of the true value, so it is free to differ between JVM
     * implementations; {@code StrictMath} would fix that and still cost a transcendental per sample per cell for
     * eight numbers that never change. {@code 0.7071067811865476} is {@code Math.sqrt(0.5)} rounded to double, so the
     * eight offsets are unit length to within an ulp and the rim is a circle rather than an octagon of two radii.
     */
    private static final double SQRT_HALF = 0.7071067811865476D;

    /** The eight rim directions, in a fixed order, x then z. */
    private static final double[][] RIM_UNIT_OFFSETS = {
            {1.0D, 0.0D},
            {SQRT_HALF, SQRT_HALF},
            {0.0D, 1.0D},
            {-SQRT_HALF, SQRT_HALF},
            {-1.0D, 0.0D},
            {-SQRT_HALF, -SQRT_HALF},
            {0.0D, -1.0D},
            {SQRT_HALF, -SQRT_HALF}
    };

    /** How many rim samples (and therefore at most how many rays) one arrival-proof rung costs. */
    public static final int OCCLUSION_RIM_SAMPLES = RIM_UNIT_OFFSETS.length;

    /** Same epsilon vanilla removes from the horizontal body box when it asks whether sneaking may leave an edge. */
    private static final double SUPPORT_EPSILON = 1.0E-7D;

    /**
     * How many ranked candidates the final arrival proof will examine before it gives up on the cell.
     *
     * <p>This is the proof's ENTIRE budget, and it is deliberately not {@link SolveBudget#maxRays()}. The ray cap
     * bounds the SEARCH — how long the planner may spend looking for candidates — and the proof is not searching: it
     * is deciding whether the candidate already found may be handed to the executor. Charging it against the same cap
     * produced the exact inversion this bound exists to stop: a crowded cell spent its 512 rays enumerating stances,
     * and the proof then found nothing left to spend and refused a solution it had not looked at, turning a placed
     * cell into a scaffolded one for no reason anybody could read off the report.
     *
     * <p>Four, times two rungs, times {@link #OCCLUSION_RIM_SAMPLES}, is at most 64 rays — one eighth of the cap, and
     * only for a cell whose first candidates keep clipping. If all four clip, the cell is refused by name. That is
     * the trade this engine always makes: a named blocker at plan time beats a wrong block in the world.
     */
    public static final int OCCLUSION_PROOF_MAX_CANDIDATES = 4;

    private final V3Settings settings;
    private final PlayerPose pose;

    /**
     * The aim quantiser: raw rotation in, the rotation the client would actually apply out.
     *
     * <p>Class F of the seam analysis. At runtime this is
     * {@code princeps.getLookBehavior().getAimProcessor()::peekRotationExact} — {@code peekRotationExact}, never
     * {@code peekRotation}, because the non-exact form mutates the aim curve's velocity state on its first call each
     * game tick, so a feasibility raytrace that used it would consume the tick's curve advance and desync the aim it
     * was predicting. In the dry run and in tests it is the identity: mouse quantisation moves a rotation by
     * hundredths of a degree, far inside the margin the planner is selecting on.
     */
    private final UnaryOperator<Rotation> quantise;

    public PlacementOracle(V3Settings settings, PlayerPose pose, UnaryOperator<Rotation> quantise) {
        this.settings = settings;
        this.pose = pose;
        this.quantise = quantise;
    }

    /** The identity-quantiser form, for the dry run and for tests. */
    public PlacementOracle(V3Settings settings, PlayerPose pose) {
        this(settings, pose, UnaryOperator.identity());
    }

    public V3Settings settings() {
        return this.settings;
    }

    public PlayerPose pose() {
        return this.pose;
    }

    /**
     * Every way to place {@code desired} at {@code cell} against the predicted world, best first.
     *
     * @param world     the world as it will be at this point in the plan
     * @param cell      absolute coordinates of the schematic cell
     * @param desired   the state the schematic asks for
     * @param inventory every stack the bot carries — all 36 of {@code Inventory.getNonEquipmentItems()}, not the nine
     *                  hotbar slots. The oracle answers "is this cell placeable", and a material in the backpack is a
     *                  material the bot has; which hotbar slot it will be in when the click goes out is
     *                  {@link HotbarSchedule#belady}'s answer over the FROZEN order, and cannot be known here. A
     *                  shorter list is legal and simply narrows the search — that is how {@link ScaffoldPlanner}
     *                  restricts a helper-block solve to the helper item
     * @param budget    what this call may spend and the thresholds it judges by
     */
    public Solve solve(PredictedWorld world, BlockPos cell, BlockState desired, List<ItemStack> inventory,
                       SolveBudget budget) {
        return this.solve(world, cell, desired, inventory, budget, StanceFilter.ALL);
    }

    /** As {@link #solve}, with the planner's journal veto applied to every candidate triple. */
    public Solve solve(PredictedWorld world, BlockPos cell, BlockState desired, List<ItemStack> inventory,
                       SolveBudget budget, StanceFilter filter) {
        return this.solveOver(world, cell, desired, inventory, budget, filter, null);
    }

    /**
     * The same question asked of ONE stance: can this cell be placed from exactly here?
     *
     * <p>The planner's stance-anchored selection needs this and {@link #solve} cannot answer it. {@code solve}
     * returns the cell's BEST solution over the whole window, so "is this cell placeable from where I am standing"
     * could only be read off it by luck — the answer is yes whenever the best solution happens to name this stance,
     * and unknown otherwise. Stance-anchored selection asks the question tens of times per placement, and it has to
     * be the real question.
     *
     * <p>Narrowed by handing the search a one-element stance list rather than by filtering: {@link StanceFilter}
     * already expresses the restriction — {@link UpwardLook#fromColumn} pins a stance and a face that way, which is
     * why nothing new is invented here — but it is consulted per FACE, inside the stance loop and after the
     * standability test, so a filtered solve still walks all 243 candidates of the window to refuse 242 of them. Once
     * per cell that is the right trade; forty-nine times per placement it is the whole cost of the search.
     *
     * <p>Otherwise identical to {@link #solve} in every respect that decides acceptance: same precheck, same fast
     * path, same full search, same margin, same arrival proof. A solution that comes back from here has cleared
     * exactly what a solution from {@code solve} has cleared — which is what lets the planner prefer a stance without
     * lowering the bar for the cells it takes from it.
     */
    public Solve solveFrom(PredictedWorld world, BlockPos cell, BlockState desired, List<ItemStack> inventory,
                           SolveBudget budget, BlockPos stance, StanceFilter filter) {
        return this.solveOver(world, cell, desired, inventory, budget, filter, stance);
    }

    /**
     * {@link #solve} and {@link #solveFrom} in one body.
     *
     * @param only the single stance to consider, or {@code null} for the whole window
     */
    private Solve solveOver(PredictedWorld world, BlockPos cell, BlockState desired, List<ItemStack> inventory,
                            SolveBudget budget, StanceFilter filter, BlockPos only) {
        int carried = 0;
        if (budget.fastPath() && fastPathApplies(desired)) {
            Solve quick = this.fastSolve(world, cell, desired, inventory, budget, filter, only);
            if (quick.solved()) {
                return quick;
            }
            // Only the rays carry over. Carrying the REJECTION counters too would double-count every stance the full
            // search is about to walk again, and the counters are the report's whole diagnostic value.
            carried = quick.raysCast();
        }
        Search search = new Search(world, budget);
        search.subject = cell;
        search.raysCast = carried;
        ItemStack stack = this.precheck(search, world, cell, desired, inventory);
        if (stack != null) {
            this.fullSearch(search, world, cell, desired, stack, budget, filter, only);
        }
        return this.finish(search);
    }

    /**
     * Could this stance possibly serve this cell at all — the cheap integer screen in front of every
     * {@link #solveFrom}.
     *
     * <p>The inverse of {@link #candidateStances}'s window, and it lives here because that window is the thing it has
     * to stay true to: {@code dx, dz} within {@link #STANCE_WINDOW_RADIUS}, the stance no higher than
     * {@link #HIGHEST_STANCE_OFFSET} above the cell and no lower than the deepest offset any family asks for, and
     * never the cell's own column. A caller that guessed at this arithmetic instead would either miss cells the window
     * covers or pay for solves the window has already ruled out, and both are silent.
     */
    public static boolean withinStanceWindow(BlockPos stance, BlockPos cell) {
        int dx = cell.getX() - stance.getX();
        int dz = cell.getZ() - stance.getZ();
        if (Math.abs(dx) > STANCE_WINDOW_RADIUS || Math.abs(dz) > STANCE_WINDOW_RADIUS) {
            return false;
        }
        int dy = cell.getY() - stance.getY();
        if (dy > -PlacementGeometry.DEEPEST_STANCE_OFFSET || dy < -HIGHEST_STANCE_OFFSET) {
            return false;
        }
        // The target column, which candidateStances excludes for the reason given there: the body would be inside the
        // cell it is trying to fill.
        return dx != 0 || dz != 0 || (dy != 0 && dy != -1);
    }

    /**
     * The single-central-ray shortcut for orientation-free full cubes — around 90 % of cells. One ray from the nearest
     * standable stance; if it lands, the cell is solved and nothing else is evaluated. Returns empty when the fast
     * path does not apply or did not land, and the caller falls through to the full search.
     */
    public Solve solveFast(PredictedWorld world, BlockPos cell, BlockState desired, List<ItemStack> inventory,
                           SolveBudget budget) {
        return this.fastSolve(world, cell, desired, inventory, budget, StanceFilter.ALL, null);
    }

    /** The stances one search walks: the whole window, or the single one {@link #solveFrom} narrowed it to. */
    private List<BlockPos> stancesFor(PredictedWorld world, BlockPos cell, BlockState desired, SolveBudget budget,
                                      BlockPos only) {
        return only == null ? this.candidateStances(world, cell, desired, budget) : List.of(only);
    }

    /**
     * The candidate feet cells for this state, already ordered, before any of them is tested.
     *
     * <p>Window is {@code dx,dz in [-3,3]}, {@code dy in [lowestStanceOffset(desired), 1]}, excluding the cell itself
     * and the cell above it. The upward-look family reaches down to -3 because every upward stance stands at least one
     * layer below the target — see {@link PlacementGeometry#needsUpwardLook} for the arithmetic.
     *
     * <p>Deliberately UNFILTERED and uncapped, which is why {@code budget} is not consulted here. The whole window is
     * what makes "of 243 candidate stances: 194 not standable, 49 nothing solid to click, 0 would work" possible, and
     * that line is the one that proved the piston row was an ordering problem rather than a geometry problem.
     * {@link SolveBudget#maxStances()} bounds the stances that get to spend RAYS, not the ones that get counted.
     */
    public List<BlockPos> candidateStances(PredictedWorld world, BlockPos cell, BlockState desired,
                                           SolveBudget budget) {
        int lowest = PlacementGeometry.lowestStanceOffset(desired);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -STANCE_WINDOW_RADIUS; dx <= STANCE_WINDOW_RADIUS; dx++) {
            for (int dz = -STANCE_WINDOW_RADIUS; dz <= STANCE_WINDOW_RADIUS; dz++) {
                for (int dy = lowest; dy <= HIGHEST_STANCE_OFFSET; dy++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) {
                        continue;   // the target column: the body would be inside the cell it is trying to fill
                    }
                    candidates.add(cell.offset(dx, dy, dz));
                }
            }
        }
        Direction support = PlacementGeometry.requiredSupportDirection(desired);
        int sx = support == null ? 0 : support.getStepX();
        int sy = support == null ? 0 : support.getStepY();
        int sz = support == null ? 0 : support.getStepZ();
        // For a block whose facing is read off the LOOK, the yaw is a CONSEQUENCE of where the bot stands, so only
        // stances lying on the axis the block faces can produce it at all -- and only the ones on ONE END of that
        // axis, because looking north and looking south are different yaws. Perpendicular stances make it look
        // sideways and place the block turned ninety degrees; stances at the far end make it look backwards and place
        // the block turned a hundred and eighty. Which end is right differs by block (a door faces where you look, a
        // repeater faces away), and PlacementGeometry.yawLookDirection is where that per-block fact is named, from
        // 26.1.2's own bytecode.
        //
        // Ordered by the LOOK and not by isOrientationSensitive: that predicate answers which blocks the pathfinder's
        // default goal gets wrong, which is a different question and excludes stairs. Ordering stairs by distance
        // alone is how the oriented bench came to stand two cells NORTH of a facing=north stair looking SOUTH.
        Direction yawLook = PlacementGeometry.yawLookDirection(desired);
        Direction verticalLook = verticalLookFor(desired);
        candidates.sort(Comparator
                .comparingLong((BlockPos stance) -> stanceKey(stance, cell, sx, sy, sz, yawLook, verticalLook))
                // Two stances at the same distance on the same side are genuinely interchangeable, and a sort that
                // leaves them in whatever order the enumeration produced is a nondeterminism nobody would look for.
                .thenComparingLong(PlacementGeometry::positionKey));
        return List.copyOf(candidates);
    }

    /**
     * The exact sub-block position inside {@code stance} that maximises the dominance margin for this face and aim
     * point, or empty when no position in the cell yields a legal placement at all.
     *
     * <p>The optimisation V2 does not have. It samples the interior of the standing cell on a
     * {@link SolveBudget#approachStep()} grid, no closer than {@link SolveBudget#approachInset()} to any wall, and
     * keeps the best. Two effects, both measured: stances that fail from the centre succeed 0.3 blocks off it, and the
     * accepted solutions are the robust ones rather than the first ones.
     */
    public Optional<Vec3> bestApproach(PredictedWorld world, BlockPos stance, BlockPos cell, BlockState desired,
                                       BlockPos against, Direction face, SolveBudget budget) {
        Vec3 best = null;
        double bestMargin = Double.NEGATIVE_INFINITY;
        for (AABB local : clickableParts(world, against)) {
            List<Vec3> ranked = this.rankedApproaches(world, cell, stance, desired, against, local,
                    face.getOpposite(), budget, null);
            if (ranked.isEmpty()) {
                continue;
            }
            Vec3 candidate = ranked.get(0);
            List<Aim> aims = this.aimsFor(world, cell, this.pose.eyeAt(candidate), against, local,
                    face.getOpposite(), desired);
            double margin = aims.isEmpty() ? Double.NEGATIVE_INFINITY : aims.get(0).margin();
            if (best == null || margin > bestMargin) {
                best = candidate;
                bestMargin = margin;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Checks A0 through A4 at one approach point: feet supported at this exact sub-block coordinate, body clear of
     * the target's collision shape, body inside its own cell, eye not inside a solid block, and line of sight clear.
     *
     * @return the rejection that fired, or null when all four hold
     */
    public Rejection checkBody(PredictedWorld world, BlockPos stance, Vec3 approach, BlockPos cell, BlockState desired,
                               Vec3 aimPoint) {
        Rejection blocked = this.checkBodyAndEye(world, stance, approach, cell, desired);
        if (blocked != null) {
            return blocked;
        }
        // A4 asks only about the segment strictly before the clicked surface. The solve's authoritative full-reach
        // ray separately proves which outline face the quantised look actually lands on.
        return OutlineGeometry.clearBeforeSurface(world, this.pose.eyeAt(approach), aimPoint)
                ? null : Rejection.RAY_OBSTRUCTED;
    }

    /**
     * What vanilla would place, given this exact click, without touching a player or a level.
     *
     * <p>The pure half of V2's {@code simulatePlacement}. Two traps it must not fall into, both verified in 26.1.2:
     * {@code BlockPlaceContext.at(...)} returns a PLAIN {@code BlockPlaceContext}, discarding any subclass — so any
     * block routed through it or through {@code BlockItem.updatePlacementContext} silently reverts to reading the live
     * player, which is the most likely way a "pure" oracle is quietly wrong for a subset of blocks. And
     * {@code BlockPlaceContext}'s protected constructor lets {@code this} escape: it calls
     * {@code canBeReplaced(this)} during construction, so a subclass reading its own fields there sees them null.
     * Initialise from constructor arguments only.
     *
     * <p>Both traps are avoided by not going near either class. There is no player-free {@code getStateForPlacement}
     * in vanilla to call, so this derives the state directly and only for the properties whose derivation is the SAME
     * for every block that carries them — which is the only kind that can be got right without a per-block table:
     *
     * <table><caption>Coverage</caption>
     * <tr><th>Property</th><th>Derived from</th></tr>
     * <tr><td>{@code axis}</td><td>the clicked face's axis; every pillar in the game agrees</td></tr>
     * <tr><td>{@code type} (slab)</td><td>the face, then the hit height in the cell</td></tr>
     * <tr><td>{@code half} (stairs, trapdoor)</td><td>the same rule; both vanilla classes spell it identically</td></tr>
     * <tr><td>{@code facing} (piston, dispenser, dropper, observer)</td><td>the QUANTISED look, opposite for all but
     * the observer, whose double {@code getOpposite} cancels — verified in 26.1.2 bytecode</td></tr>
     * </table>
     *
     * <p>Everything else that is placement-controllable — a four-way {@code facing}, a wall-versus-standing variant —
     * takes a convention that differs block by block (a door faces where you look, a repeater faces away) and is left
     * at the default here. The caller adopts those from the schematic's own state after the geometry has proven the
     * look AXIS, and the executor's live gate is the authority on the sign. That split is deliberate: a wrong guess
     * here would be silent and total for a whole family, where a deferred one is a named divergence on its first cell.
     *
     * <p>Neighbour-sensitive families are finished by {@link PlacementFamilies#resolve}; this method answers the
     * geometry. {@code sneaking} is carried for that hand-off and for callers that log the click; no property in the
     * table above reads it.
     *
     * @return the state that would land, or null when this stack cannot place here
     */
    public BlockState simulate(PredictedWorld world, ItemStack stack, BlockPos against, Direction face, Vec3 aimPoint,
                               Rotation rotation, boolean sneaking) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockPos cell = against.relative(face);
        if (!world.get(cell).canBeReplaced()) {
            return null;   // vanilla's canPlace, in the only form available without a Level
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            state = state.setValue(BlockStateProperties.AXIS, face.getAxis());
        }
        // Vanilla measures the hit height against the CLICKED block, which shares its Y with the cell for every side
        // face and is short-circuited by the UP/DOWN branches otherwise, so the cell is the same number and reads
        // as what it means.
        double heightInCell = aimPoint.y - cell.getY();
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            state = state.setValue(BlockStateProperties.SLAB_TYPE, face == Direction.UP ? SlabType.BOTTOM
                    : face == Direction.DOWN ? SlabType.TOP
                    : heightInCell > 0.5D ? SlabType.TOP : SlabType.BOTTOM);
        }
        if (state.hasProperty(BlockStateProperties.HALF)) {
            state = state.setValue(BlockStateProperties.HALF, face == Direction.UP ? Half.BOTTOM
                    : face == Direction.DOWN ? Half.TOP
                    : heightInCell > 0.5D ? Half.TOP : Half.BOTTOM);
        }
        // FaceAttachedHorizontalDirectionalBlock.getStateForPlacement (verified in 26.1.2 bytecode) moves
        // getClickedFace().getOpposite() to the head of getNearestLookingDirections when the clicked block is not
        // replaceable. Its first viable state is therefore completely determined by the face we click:
        //
        //   clicked UP   -> nearest DOWN -> FLOOR
        //   clicked DOWN -> nearest UP   -> CEILING
        //   clicked side -> nearest opposite(side) -> WALL, facing=side
        //
        // The old oracle left all three properties at the item's default. A wall lever therefore remained
        // wall+north no matter which support face had actually been proven; every east/west/south lever in the
        // Basalt centre failed as WRONG_RESULT_STATE despite having exact support and ray proofs.
        if (blockItem.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock
                && state.hasProperty(BlockStateProperties.ATTACH_FACE)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            if (face == Direction.UP) {
                state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.fromYRot(rotation.getYaw()));
            } else if (face == Direction.DOWN) {
                state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.fromYRot(rotation.getYaw()));
            } else {
                state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, face);
            }
        } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            // The four-way facing, DERIVED rather than deferred. Vanilla reads it off getHorizontalDirection(), which
            // is Direction.fromYRot(yaw) and nothing else -- the pitch is free, so unlike the six-way family there is
            // no dominance contest here, only a quadrant. Read off the QUANTISED yaw for the same reason the six-way
            // branch below does: the quantiser is the last thing to touch the aim before the click, so it is the yaw
            // the click actually carries.
            //
            // Which of the three transforms vanilla applies is a per-block fact (a stair faces where you look, a
            // repeater faces away, an anvil a quarter turn clockwise) and PlacementGeometry.yawFacingConvention names
            // it from 26.1.2's own bytecode. A block it does not name keeps the item's DEFAULT facing, which the
            // acceptance test in predict() will then refuse for every stance -- a named blocker at plan time instead of
            // a guessed click. That is the whole correction: this property used to be copied out of the schematic in
            // adoptDeferredOrientation, which made the prediction agree with the plan by construction and could never
            // disagree, however wrong the stance was.
            Direction landed = PlacementGeometry.yawFacingFor(blockItem.getBlock(),
                    Direction.fromYRot(rotation.getYaw()));
            if (landed != null) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, landed);
            }
        }
        // A HOPPER's facing comes from the CLICKED FACE and nothing else: HopperBlock.getStateForPlacement is
        // getClickedFace().getOpposite(), collapsed to DOWN when that is vertical (26.1.2 bytecode, verbatim). `face`
        // is getClickedFace() by this method's own contract -- simulate's caller computes `cell = against.relative(
        // face)` -- so the landed facing is exactly face.getOpposite().
        //
        // Keyed on HopperBlock.FACING and not BlockStateProperties.FACING: they are DIFFERENT Property objects
        // (FACING_HOPPER, five values) and StateHolder.valueIndex compares with ==, so EVERY BlockStateProperties.
        // FACING branch in this file silently skips hoppers. That left the item default facing=down on all 140 of
        // etz-basalt's, and predict() then refused every stance of every one of them as WRONG_STATE_WOULD_LAND -- not
        // one hopper appears in 4250 plan lines. Mutually exclusive with both branches around it: a hopper carries
        // neither HORIZONTAL_FACING nor BlockStateProperties.FACING.
        if (state.hasProperty(HopperBlock.FACING)) {
            Direction landed = face.getOpposite();
            state = state.setValue(HopperBlock.FACING,
                    landed.getAxis() == Direction.Axis.Y ? Direction.DOWN : landed);
        }
        if (state.hasProperty(BlockStateProperties.FACING) && isLookDerivedFacing(blockItem.getBlock())) {
            // Read off the QUANTISED rotation rather than off the aim vector: the two agree in direction but the
            // quantiser is the last thing to touch the aim before the click, so a quantisation that flips the dominant
            // axis has to be able to fail this. That is what the margin is buying, and measuring it anywhere earlier
            // would measure a number the click never sees.
            Direction look = dominantDirection(RotationUtils.calcLookDirectionFromRotation(rotation));
            if (look == null) {
                return null;   // a tie is a coin flip; vanilla would break it by enum order and this must not
            }
            state = state.setValue(BlockStateProperties.FACING,
                    blockItem.getBlock() instanceof ObserverBlock ? look : look.getOpposite());
        }
        // Last, because it REPLACES the whole state rather than setting one property: a wire's connections are the
        // only thing a wire has, and vanilla's getStateForPlacement is that derivation and nothing else.
        //
        // It is here at all because the connective properties are AUTO_RESOLVED and the acceptance rules skip them,
        // which made it look as though nothing depended on them. The OUTLINE does, and the outline is what aim points
        // are measured on. Left at the item default, every wire the planner placed was a one-box dot while the live
        // block was a three-box cross -- 19 WRONG_FACE divergences in the first thirteen minutes of basalt run
        // 47f7142e, one of them two actions after the wire itself was placed. See RedstoneConnections.
        return RedstoneConnections.stateForPlacement(world, cell, state);
    }

    // ------------------------------------------------------------------------------------------------ the search

    /**
     * The cell-level questions, asked once before any stance is enumerated. Every one of them refuses the CELL rather
     * than a candidate, so asking them per stance would multiply a single fact by 243 and bury the reason it failed.
     *
     * @return the stack to place from — the item, never an index, for the reason on {@link Rejection#NO_ITEM} — or
     *         null with the reason already counted
     */
    private ItemStack precheck(Search search, PredictedWorld world, BlockPos cell, BlockState desired,
                               List<ItemStack> inventory) {
        if (!world.inBounds(cell.getX(), cell.getY(), cell.getZ())) {
            search.reject(Rejection.OUT_OF_BOUNDS);
            return null;
        }
        if (!world.isLoaded(cell.getX(), cell.getY(), cell.getZ())) {
            // get0 answers AIR for an unloaded chunk, so a search that ran here would prove a placement into terrain
            // nobody has seen. PROVEN would be a lie and the whole design is that it is not.
            search.reject(Rejection.UNLOADED_CHUNK);
            return null;
        }
        if (!world.get(cell).canBeReplaced()) {
            search.reject(Rejection.CELL_OCCUPIED);
            return null;
        }
        // The pure stand-in for canSurvive. Vanilla's needs a Level; what it decides for everything that hangs, stands
        // or sticks is whether the ONE neighbour the state names is solid, and that is readable here. Blocks with no
        // named support fall through and are covered by NOTHING_SOLID_TO_CLICK, so nothing is counted twice.
        Direction support = PlacementGeometry.requiredSupportDirection(desired);
        if (support != null && clickableParts(world, cell.relative(support)).isEmpty()) {
            search.reject(Rejection.CANNOT_SURVIVE);
            return null;
        }
        BlockPos second = secondCellOf(desired, cell);
        if (second != null && !world.get(second).canBeReplaced()) {
            // A door's upper half and a bed's head are placed by vanilla, not by us, and vanilla refuses the whole
            // placement when their cell is taken. The bot's own body cannot be there -- it is A1 that owns that -- so
            // this genuinely is the space being obstructed by the world.
            search.reject(Rejection.SPACE_OBSTRUCTED);
            return null;
        }
        ItemStack stack = stackThatPlaces(inventory, desired);
        if (stack == null) {
            search.reject(Rejection.NO_ITEM);
            return null;
        }
        Item followup = FluidPlan.waterloggingBucketFor(desired);
        if (followup != null && !containsItem(inventory, followup)) {
            // The block item alone is not enough: accepting the dry placement here would leave a cell the layer gate
            // can never satisfy. Bucket quantity is checked over the frozen order by the material ledger; this check
            // owns only the zero-versus-some feasibility question.
            search.reject(Rejection.NO_ITEM);
            return null;
        }
        return stack;
    }

    /** Twenty-four stances by up to six faces by their derived aim points, every one of them ranked and none of them
     *  taken on sight.
     *
     *  @param only the single stance {@link #solveFrom} narrowed the search to, or {@code null} for the whole window */
    private void fullSearch(Search search, PredictedWorld world, BlockPos cell, BlockState desired, ItemStack stack,
                            SolveBudget budget, StanceFilter filter, BlockPos only) {
        Direction[] supports = PlacementGeometry.supportDirectionsFor(desired);
        // Read once. Which faces of the cell's neighbours are clickable does not depend on where the bot stands, and
        // the stance loop asks the same question up to 243 times -- the only work in this search that genuinely
        // repeats, and therefore the only thing worth caching. A memo keyed on the neighbourhood would cost more state
        // reads than the rays it saved, and would have to be invalidated on every PredictedWorld.apply to stay true.
        BlockPos[] againstPos = new BlockPos[supports.length];
        List<List<AABB>> faceParts = new ArrayList<>(supports.length);
        boolean anyClickable = false;
        for (int i = 0; i < supports.length; i++) {
            againstPos[i] = cell.relative(supports[i]);
            List<AABB> parts = clickableParts(world, againstPos[i]);
            faceParts.add(parts);
            anyClickable |= !parts.isEmpty();
        }
        List<BlockPos> stances = this.stancesFor(world, cell, desired, budget, only);
        int evaluated = 0;
        for (BlockPos stance : stances) {
            search.stancesTried++;
            if (!world.isLoaded(stance.getX(), stance.getY(), stance.getZ())) {
                // The snapshot reads AIR for a column it never saw, and air under the feet is "not standable" by
                // accident rather than by knowledge. A stance three cells out can easily fall outside the loaded
                // radius, and a proof resting on terrain nobody looked at is not a proof.
                search.reject(Rejection.UNLOADED_CHUNK);
                continue;
            }
            if (!world.isStandable(stance.getX(), stance.getY(), stance.getZ())) {
                search.reject(Rejection.STANCE_NOT_STANDABLE);
                continue;
            }
            if (!anyClickable) {
                // Raised per standable stance and not once per face: a wall torch has one solid neighbour and five of
                // air, and counting the airy directions reported "144 nothing solid to click" for a cell whose wall
                // was standing right there -- a number that reads like the cause and is pure arithmetic.
                search.reject(Rejection.NOTHING_SOLID_TO_CLICK);
                continue;
            }
            // Two caps, two names. They used to share RAY_BUDGET_EXHAUSTED and one flag, which made every stance-cap
            // stop read as "ray budget spent after N" -- a sentence that named the wrong limit and then printed a ray
            // count against it. The counter raised here IS the number of candidates left unevaluated, one per turn of
            // this loop, which is what the report prints.
            if (search.raysCast >= budget.maxRays()) {
                search.reject(Rejection.RAY_BUDGET_EXHAUSTED);
                search.rayBudgetExhausted = true;
                continue;   // keep walking: the standability tally is free and it is what the report is made of
            }
            if (evaluated >= budget.maxStances()) {
                search.reject(Rejection.STANCE_BUDGET_EXHAUSTED);
                search.stanceBudgetExhausted = true;
                continue;
            }
            evaluated++;
            // The approach grid is the expensive part and the stance ranking is already sound, so only the best few
            // stances get it; the rest are judged from the cell centre exactly as V2 judges all of them.
            boolean refine = evaluated <= budget.approachRefineTopN();
            for (int i = 0; i < supports.length && search.raysCast < budget.maxRays(); i++) {
                if (faceParts.get(i).isEmpty()) {
                    continue;
                }
                Direction face = supports[i].getOpposite();
                if (!filter.allows(cell, stance, face)) {
                    search.reject(Rejection.STANCE_FILTERED);
                    continue;
                }
                for (AABB local : faceParts.get(i)) {
                    List<Vec3> approaches = refine
                            ? this.rankedApproaches(world, cell, stance, desired, againstPos[i], local,
                                    supports[i], budget, search)
                            : List.of(centreOf(world, stance));
                    for (Vec3 approach : approaches) {
                        if (this.evaluateApproach(search, world, cell, desired, stance, approach, againstPos[i],
                                local, supports[i], stack, budget)
                                || search.raysCast >= budget.maxRays()) {
                            break;
                        }
                    }
                    if (search.raysCast >= budget.maxRays()) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Every aim point this face offers from this approach, best margin first, stopping at the first that survives all
     * of A1 to A4 and lands an acceptable state.
     *
     * @return true when this (stance, face, approach) produced a solution — a further aim point on the same face can
     *         only have a smaller margin, so there is nothing left to buy here
     */
    private boolean evaluateApproach(Search search, PredictedWorld world, BlockPos cell, BlockState desired,
                                     BlockPos stance, Vec3 approach, BlockPos against, AABB local, Direction support,
                                     ItemStack stack, SolveBudget budget) {
        Vec3 eye = this.pose.eyeAt(approach);
        List<Aim> aims = this.aimsFor(world, cell, eye, against, local, support, desired);
        if (aims.isEmpty()) {
            search.reject(Rejection.LOOK_NOT_DOMINANT);
            return false;
        }
        // A0 to A3 depend on the body and not on where it is aiming, so they are asked once per approach. Asking them
        // per aim point would multiply one fact by five and make the report read as though five different things
        // went wrong.
        Rejection body = this.checkBodyAndEye(world, stance, approach, cell, desired);
        if (body != null) {
            search.reject(body);
            return false;
        }
        Direction face = support.getOpposite();
        for (Aim aim : aims) {
            if (search.raysCast >= budget.maxRays()) {
                search.reject(Rejection.RAY_BUDGET_EXHAUSTED);
                search.rayBudgetExhausted = true;
                return false;
            }
            if (eye.distanceTo(aim.point()) > budget.maxReach()) {
                search.reject(Rejection.OUT_OF_REACH);
                continue;
            }
            Rotation rotation = this.quantise.apply(
                    RotationUtils.calcRotationFromVec3d(eye, aim.point(), PLANNING_REFERENCE));
            // One authoritative OUTLINE ray answers both A4 and the crosshair question. It must be cast after
            // quantisation: an unquantised segment to the intended point is not evidence for the look that goes out.
            Rejection crosshair = this.confirmCrosshair(search, world, against, face, eye, rotation, budget);
            if (crosshair != null) {
                search.reject(crosshair);
                continue;
            }
            BlockState landed = this.predict(world, cell, desired, stack, against, face, aim.point(), rotation);
            if (landed == null) {
                search.reject(Rejection.WRONG_STATE_WOULD_LAND);
                continue;
            }
            if (FluidPlan.wantsWaterlogging(desired)
                    && BreakPlanner.aim(world, this.pose, budget, cell, stance, landed).isEmpty()) {
                search.reject(Rejection.FOLLOWUP_UNREACHABLE);
                continue;
            }
            PlacementSolution solution = new PlacementSolution(cell, desired, stance, approach, against, face,
                    aim.point(), rotation, aim.margin(), landed, stack.getItem());
            if (aim.margin() < budget.minMargin()) {
                // Kept, with its number, and never taken silently. A cell solvable only at 0.04 is a cell the report
                // has to name -- the run that placed it right and the run that placed it wrong look identical from
                // here, and that is precisely the thing the dry run exists to stop being a surprise.
                search.tight.add(solution);
                search.reject(Rejection.MARGIN_TOO_TIGHT);
            } else {
                search.solutions.add(solution);
            }
            return true;
        }
        return false;
    }

    /**
     * Would the crosshair actually rest on the face we intend to click?
     *
     * <p>Cast along the QUANTISED look for the full planning reach, not to the aim point: a segment that stops on the
     * face cannot report which block it stopped against, because {@link GridRay} is half-open at its end point. Run
     * after A4, so a hit on any other cell means the ray went somewhere else rather than that something stands in the
     * way — A4 has already proven the segment up to the aim point is clear.
     *
     * <p>Skipped when the neighbour is not a full cube. Those are absent from the solid bitset by design (partial
     * shapes enter through the face {@code AABB} the aim points come from, not through the DDA), so the caster has
     * nothing to answer with and a wrong answer is worse than a deferred one. The executor re-runs the real crosshair
     * against the real {@code VoxelShape} before it clicks.
     */
    private Rejection confirmCrosshair(Search search, PredictedWorld world, BlockPos against, Direction face, Vec3 eye,
                                       Rotation rotation, SolveBudget budget) {
        Vec3 look = RotationUtils.calcLookDirectionFromRotation(rotation);
        Vec3 end = eye.add(look.x * budget.maxReach(), look.y * budget.maxReach(), look.z * budget.maxReach());
        search.raysCast++;
        OutlineGeometry.Ray ray = OutlineGeometry.clip(world, eye, end);
        if (!ray.evaluable() || ray.hit() == null) {
            return Rejection.RAY_MISSED_FACE;
        }
        if (!ray.hit().getBlockPos().equals(against)) {
            return Rejection.RAY_OBSTRUCTED;
        }
        return ray.hit().getDirection() == face ? null : Rejection.RAY_MISSED_FACE;
    }

    /** The one-ray shortcut of plan 4.5: the nearest standable stance, the face of its nearest clickable neighbour,
     *  the centre of that face, one ray. Applies only where the landed state cannot depend on the aim at all, so there
     *  is nothing for a search over aim points to discover. */
    private Solve fastSolve(PredictedWorld world, BlockPos cell, BlockState desired, List<ItemStack> inventory,
                            SolveBudget budget, StanceFilter filter, BlockPos only) {
        Search search = new Search(world, budget);
        search.subject = cell;
        ItemStack stack = this.precheck(search, world, cell, desired, inventory);
        if (stack == null) {
            return this.finish(search);
        }
        for (BlockPos stance : this.stancesFor(world, cell, desired, budget, only)) {
            search.stancesTried++;
            if (!world.isLoaded(stance.getX(), stance.getY(), stance.getZ())) {
                // The snapshot reads AIR for a column it never saw, and air under the feet is "not standable" by
                // accident rather than by knowledge. A stance three cells out can easily fall outside the loaded
                // radius, and a proof resting on terrain nobody looked at is not a proof.
                search.reject(Rejection.UNLOADED_CHUNK);
                continue;
            }
            if (!world.isStandable(stance.getX(), stance.getY(), stance.getZ())) {
                search.reject(Rejection.STANCE_NOT_STANDABLE);
                continue;
            }
            Vec3 approach = centreOf(world, stance);
            Vec3 eye = this.pose.eyeAt(approach);
            ClickTarget target = nearestClickable(world, cell, eye, desired, stance, filter);
            if (target == null) {
                search.reject(Rejection.NOTHING_SOLID_TO_CLICK);
                return this.finish(search);
            }
            Direction support = target.support();
            BlockPos against = target.against();
            Direction face = support.getOpposite();
            Vec3 aim = target.point();
            if (eye.distanceTo(aim) > budget.maxReach()) {
                search.reject(Rejection.OUT_OF_REACH);
                return this.finish(search);
            }
            Rejection body = this.checkBodyAndEye(world, stance, approach, cell, desired);
            if (body != null) {
                search.reject(body);
                return this.finish(search);
            }
            Rotation rotation = this.quantise.apply(
                    RotationUtils.calcRotationFromVec3d(eye, aim, PLANNING_REFERENCE));
            // The one ray, and it answers both questions: it stops at the first solid cell, so landing on the intended
            // face proves the line of sight as well. A4 needs no separate cast here.
            Rejection crosshair = this.confirmCrosshair(search, world, against, face, eye, rotation, budget);
            if (crosshair != null) {
                search.reject(crosshair);
                return this.finish(search);
            }
            BlockState landed = this.predict(world, cell, desired, stack, against, face, aim, rotation);
            if (landed == null) {
                search.reject(Rejection.WRONG_STATE_WOULD_LAND);
                return this.finish(search);
            }
            search.solutions.add(new PlacementSolution(cell, desired, stance, approach, against, face, aim, rotation,
                    PlacementSolution.UNCONSTRAINED_MARGIN, landed, stack.getItem()));
            return this.finish(search);
        }
        return this.finish(search);
    }

    /** Is this a cell whose landed state cannot depend on where the bot stands — no orientation, no neighbour rule,
     *  no named support? Those are the ~90 % the single-ray path exists for. */
    private static boolean fastPathApplies(BlockState desired) {
        return !FluidPlan.wantsWaterlogging(desired)
                && !PlacementGeometry.placementStateIsGeometrySensitive(desired)
                && !PlacementFamilies.isNeighbourSensitive(desired)
                // The fifth condition, and its absence is what kept the basalt run stuck. The four above ask whether
                // the landed state depends on the CELL, the NEIGHBOURS, the HIT POINT or the SUPPORT. None of them
                // asks whether it depends on the LOOK -- and a piston's does: vanilla reads the direction the placer
                // faces, not where on the face the click lands. So a sticky_piston passed every test here, took the
                // fast path, and was written into the plan with UNCONSTRAINED_MARGIN -- "this cell has no look
                // constraint at all" -- while demanding facing=up from a look that could never produce it.
                //
                // That is also why re-planning never escaped it. The fast path is deterministic: same world, same
                // cell, same wrong answer, three times over, with the live gate refusing each one a millimetre from
                // a perfect aim. The defect was never in the world, so re-reading the world could not help.
                && provenLook(desired) == null
                && PlacementGeometry.yawLookDirection(desired) == null
                && PlacementGeometry.requiredSupportDirection(desired) == null;
    }

    // ------------------------------------------------------------------------------------------- aim and approach

    /** An aim point and what its look wins by. */
    private record Aim(Vec3 point, double margin) {
    }

    /** One actual outline component of a neighbour selected for the fast path. */
    private record ClickTarget(Direction support, BlockPos against, AABB local, Vec3 point) {
    }

    /**
     * The points on this face worth a ray, best margin first.
     *
     * <p>Three cases, and which one applies is a property of the desired state alone. A state whose facing vanilla
     * reads off the six-way look gets exactly ONE point — the closed-form optimum for the one look that produces it,
     * so the two-sign ambiguity {@link PlacementGeometry#requiredLookDirections} deliberately keeps open is closed
     * here by the block's own convention rather than by trying both and hoping. A state with a four-way facing gets
     * the sampled points, scored by how far the yaw's own axis wins by, which is the same question one dimension
     * down. Everything else is orientation-free and every legal point is equally correct.
     *
     * <p>The sampled points are then passed through {@link #selectHingeHalf}, which is where a door's hinge stops
     * being predicted and starts being chosen.
     */
    private List<Aim> aimsFor(PredictedWorld world, BlockPos cell, Vec3 eye, BlockPos against, AABB local,
                              Direction support, BlockState desired) {
        Direction proven = provenLook(desired);
        if (proven != null) {
            PlacementGeometry.AlignedPoint aligned = PlacementGeometry.mostAlignedPointOnFaceWithMargin(
                    eye, against, local, support.getOpposite(), proven);
            // The margin is REQUIRED to be positive here, exactly as the yaw branch below requires it, and its
            // absence is what let this engine prove a 36 degree look for a block that only lands facing up past 45.
            // Vanilla resolves a six-way facing by which component of the look vector dominates, so a click at or
            // below that boundary produces a DIFFERENT block from the same geometry -- the clicked face has no say
            // in it at all. A non-positive margin is therefore not a worse candidate, it is not a candidate.
            //
            // It cost the basalt run twice over: the plan demanded sticky_piston[facing=up] while committing to a
            // 36 degree look, the live gate refused the contradiction one millimetre from a perfect aim, and three
            // re-plans reproduced it identically because the proof itself was what allowed it.
            if (aligned == null) {
                return List.of();
            }
            // Vanilla's rule, written as vanilla writes it, because the previous attempt checked a PROXY and got the
            // answer it deserved: it demanded "margin > 0" of a helper that scores alignment on its own scale, the
            // helper reported a hair above zero at a look of 44.81 degrees, the condition passed, and the game --
            // which does not care about that scale at all -- placed the piston sideways.
            //
            // getNearestLookingDirection takes the look vector and picks the axis with the LARGEST ABSOLUTE
            // COMPONENT. Nothing else. So the requirement is three numbers compared, and it is stated here in those
            // terms rather than in degrees: the component along the proven look must exceed both others, by a margin
            // rather than by a hair, because the body arrives somewhere inside a ball and not on a point.
            double dominance = axisDominance(eye, aligned.point(), proven);
            if (dominance <= DOMINANCE_MARGIN) {
                return List.of();
            }
            return List.of(new Aim(aligned.point(), Math.min(aligned.margin(), dominance)));
        }
        Direction yawLook = PlacementGeometry.yawLookDirection(desired);
        List<Vec3> sampled = selectHingeHalf(world, cell, desired, local, support.getOpposite(),
                PlacementGeometry.aimPointsOnFace(eye, against, local, support, desired));
        List<Aim> aims = new ArrayList<>(sampled.size());
        for (Vec3 point : sampled) {
            if (!halfClearsItsBoundary(cell, point, support.getOpposite(), desired)) {
                continue;
            }
            double margin = yawLook == null
                    ? PlacementSolution.UNCONSTRAINED_MARGIN : yawMargin(eye, point, yawLook);
            if (margin > 0.0D) {
                // A point whose yaw lands in the wrong quadrant places the block turned ninety or a hundred and eighty
                // degrees. It is not a worse candidate, it is not a candidate, so it is absent rather than present and
                // negative.
                aims.add(new Aim(point, margin));
            }
        }
        aims.sort(Comparator.comparingDouble((Aim aim) -> -aim.margin())
                .thenComparingDouble(aim -> aim.point().distanceToSqr(eye))
                .thenComparingDouble(aim -> aim.point().x)
                .thenComparingDouble(aim -> aim.point().y)
                .thenComparingDouble(aim -> aim.point().z));
        return aims;
    }

    /**
     * Does this aim keep the slab or stair half it decides away from the boundary that decides it?
     *
     * <p>Vanilla's rule is one comparison — {@code clickLocation.y - clickedPos.getY() > 0.5} picks TOP, everything
     * else BOTTOM (26.1.2 {@code SlabBlock.getStateForPlacement}, {@code dcmpl}/{@code ifle}, verbatim) — and
     * {@link #simulate} writes the identical line. Identical rules are exactly why an aim ON the boundary is not
     * safe: both sides agree that 0.5 is BOTTOM, so the planner proves BOTTOM and is then decided by whichever side
     * of 0.5 the LIVE ray's hit lands, which the aim quantiser and the clip's own arithmetic settle to a few
     * millimetres. Nothing in the proof gets a say.
     *
     * <p>Measured: basalt run {@code 5a29505e} stood down at 1317 of 15 004 cells on
     *
     * <pre>
     * WRONG_RESULT — wanted 82,-59,120 face north at 82.250,-58.500,120.000
     *                to land polished_blackstone_slab[type=bottom],
     *                live ray hit 82,-59,120 face north at 82.250,-58.500,120.000,
     *                aim point 0.000 from the hit
     * </pre>
     *
     * <p>The ray hit the aim point exactly, on the intended block and the intended face, and the gate refused it
     * anyway — because the half that landed was not the half that was proven. 34 of the 61 side-face slab actions in
     * that plan aim at exactly {@code 0.5}: the sampler's vertical centre IS the boundary.
     *
     * <p>This is the same failure the six-way family already refuses, and it is refused the same way. {@link
     * #DOMINANCE_MARGIN} is reused rather than a second number invented: the error it has to clear here is smaller
     * (the aim point is fixed in world space, so unlike a facing this does not move with the arrival ball), and it
     * costs nothing, because {@link PlacementGeometry#aabbSideMultipliers} offers the quarter heights as well and
     * those sit 0.25 from the boundary. A cell whose only aim is the centre is refused rather than gambled on — a
     * plan that cannot say which half will land is not a plan.
     *
     * <p>Only the SIDE faces are asked. Clicking UP or DOWN decides the half outright in both implementations, with
     * no comparison and therefore no boundary.
     */
    private static boolean halfClearsItsBoundary(BlockPos cell, Vec3 point, Direction face, BlockState desired) {
        if (face.getAxis() == Direction.Axis.Y) {
            return true;
        }
        if (!desired.hasProperty(BlockStateProperties.SLAB_TYPE)
                && !desired.hasProperty(BlockStateProperties.HALF)) {
            return true;
        }
        return Math.abs((point.y - cell.getY()) - 0.5D) > DOMINANCE_MARGIN;
    }

    /**
     * Slide each sampled aim point onto the half of the face that SELECTS the wanted door hinge.
     *
     * <p>The hinge is the one property in the engine that the plan can pick outright rather than predict: vanilla
     * decides it from where inside the cell the hit lands, and the plan owns the aim point. Before this existed the
     * sampler offered a face CENTRE first — exactly 0.5 on both tangential axes, which is vanilla's decision boundary
     * with no width — and the planner proved the hinge an exact 0.5 produces while the live ray, arriving from a body
     * anywhere inside {@link FineApproach#TOLERANCE}, produced the other one. Three deterministic re-plans, three
     * identical boundary aims, one cell blocked forever.
     *
     * <p>Only the sampled points move, and only along one horizontal axis, so everything the rest of the search
     * proves about them — reach, occlusion, the yaw quadrant — is re-proved afterwards from the moved point rather
     * than inherited. Movement is a clamp towards the nearest legal offset, so a point that already sits in the
     * window is returned unchanged and the ordering the sampler intended survives.
     *
     * <p>Three cases pass through untouched, each for its own reason:
     * <ul>
     *   <li>not a door, or a door whose flanking blocks settle the hinge themselves — nothing about the aim can
     *       change the answer, and {@link PlacementFamilies#resolve} checks WHAT they settled;</li>
     *   <li>the deciding axis is the clicked face's own normal — the hit is pinned to the face plane at an offset of
     *       0 or 1, a full half block from the boundary, which is as decided as an aim ever gets;</li>
     *   <li>a face whose {@code AABB} does not reach into the window at all — the point is dropped instead, and an
     *       empty list is a named blocker rather than a guess.</li>
     * </ul>
     */
    private static List<Vec3> selectHingeHalf(PredictedWorld world, BlockPos cell, BlockState desired, AABB local,
                                              Direction face, List<Vec3> sampled) {
        if (!(desired.getBlock() instanceof DoorBlock)
                || !desired.hasProperty(DoorBlock.FACING) || !desired.hasProperty(DoorBlock.HINGE)) {
            return sampled;
        }
        PlacementFamilies.HingeWindow window = PlacementFamilies.doorHingeWindow(
                world, cell, desired.getValue(DoorBlock.FACING), desired.getValue(DoorBlock.HINGE));
        if (window == null || window.axis() == face.getAxis()) {
            return sampled;
        }
        // The deciding axis is tangential to the clicked face, so `against` and `cell` share that coordinate and the
        // face's own span converts to cell-relative offsets by reading the local AABB directly.
        double lo = Math.max(window.min(), local.min(window.axis()));
        double hi = Math.min(window.max(), local.max(window.axis()));
        if (lo > hi) {
            return List.of();
        }
        List<Vec3> moved = new ArrayList<>(sampled.size());
        for (Vec3 point : sampled) {
            double offset = point.get(window.axis()) - cell.get(window.axis());
            Vec3 selected = window.contains(offset)
                    ? point
                    : point.with(window.axis(), cell.get(window.axis()) + Mth.clamp(offset, lo, hi));
            // Two samples can clamp onto the same point. Casting the same ray twice costs a ray from the budget and
            // buys nothing, and a duplicate in the report reads as though the search found two solutions.
            if (!moved.contains(selected)) {
                moved.add(selected);
            }
        }
        return moved;
    }

    /**
     * The interior of the standing cell, sampled and ranked by the margin it buys, best first.
     *
     * <p>Worked through for the upward-look family, which is where the whole idea earns its keep: feet at T+East+Down,
     * clicking the underside of a scaffold block above T. From the cell centre the aim is
     * {@code d = (-0.5, +0.73, 0)} and the margin 0.19 — a coin flip. Stepping 0.2 towards the cell wall makes it
     * {@code d = (-0.32, +0.73, 0)} and the margin 0.41, which survives every rounding error, the crouch
     * interpolation and the aim curve's residual with room left over. Same stance, same face, same block; the only
     * thing that changed is a number V2 never had a name for.
     *
     * <p>Stepped by an integer counter rather than by accumulating the step onto a double: 0.1 is not representable,
     * and a grid that drifts by an ulp per row is a plan that differs between two runs of the same build.
     */
    /**
     * The outer approach raster: the body centre exactly on the stance cell's boundary.
     *
     * <p>0.0 rather than a negative number, and the reason is what holds a body up. At 0.0 half the body's footprint
     * still rests on the stance cell, which is ample; past 0.0 it is standing on a sliver, and a body that slipped off
     * would take with it the settled stance every other part of this proof assumes.
     */
    static final double EDGE_INSET = 0.0D;

    /** One square raster of body positions at the given inset, keeping only those that yield an aim. */
    private List<Aim> rasterise(PredictedWorld world, BlockPos cell, BlockPos stance, BlockState desired,
                                BlockPos against, AABB local, Direction support, double inset, double step,
                                Search search) {
        List<Aim> scored = new ArrayList<>();
        int steps = (int) Math.floor((1.0D - 2.0D * inset) / step + 1.0E-9D);
        double foot = footY(world, stance);
        for (int ix = 0; ix <= steps; ix++) {
            for (int iz = 0; iz <= steps; iz++) {
                Vec3 approach = new Vec3(stance.getX() + inset + ix * step, foot,
                        stance.getZ() + inset + iz * step);
                // A standable CELL is not proof for every point in it. Bottom stairs are the measured counterexample:
                // their lower half makes MovementHelper accept the stance, while only the upper half (or one corner
                // after a neighbour turns it into an outer stair) reaches this approach's feet plane. Filtering here
                // matters in addition to the final proof: otherwise the top-N ranking can fill entirely with
                // impossible edge points and hide a lower-margin point whose feet are actually supported.
                if (!this.approachSupported(world, approach)) {
                    if (search != null) {
                        search.reject(Rejection.APPROACH_NOT_SUPPORTED);
                    }
                    continue;
                }
                List<Aim> aims = this.aimsFor(world, cell, this.pose.eyeAt(approach), against, local, support,
                        desired);
                if (!aims.isEmpty()) {
                    scored.add(new Aim(approach, aims.get(0).margin()));
                }
            }
        }
        return scored;
    }

    private List<Vec3> rankedApproaches(PredictedWorld world, BlockPos cell, BlockPos stance, BlockState desired,
                                        BlockPos against, AABB local, Direction support, SolveBudget budget,
                                        Search search) {
        double step = budget.approachStep();
        // Two rasters, not one. The inner one is the old grid: the body wholly inside its stance cell. The outer one
        // lets the body stand ON the cell boundary, which is what a crouching player does when they lean over a ledge
        // to see the SIDE of the block they are standing next to -- the placement V2 could make and V3 could not.
        //
        // Nothing about the proof is relaxed to allow it. A2 never asked "is the body inside its cell", it asked "if
        // it is not, does it overlap anything SOLID" -- so an overhang over air was always admissible and simply was
        // never generated, because the grid stopped at APPROACH_INSET. Over a solid neighbour the same check refuses
        // it exactly as before.
        //
        // EDGE_INSET is 0.0 and not negative: at 0.0 half the body's footprint still rests on the stance cell, which
        // is what holds a body up. Past that it is standing on a sliver, and a body that slid off would take the
        // proof's assumption of a settled stance with it.
        // The outer raster is a FALLBACK and not a second pass. Running both every time would multiply the ray
        // count of the whole plan by about six, and the plan already costs 47 seconds; a cell the ordinary grid can
        // serve has no use for a ledge. So the edge is only swept for a cell that nothing else could reach, which is
        // exactly the population it was built for.
        List<Aim> scored = rasterise(world, cell, stance, desired, against, local, support,
                budget.approachInset(), step, search);
        if (scored.isEmpty()) {
            scored = rasterise(world, cell, stance, desired, against, local, support, EDGE_INSET, step, search);
        }
        Vec3 eye = this.pose.eyeAt(centreOf(world, stance));
        scored.sort(Comparator.comparingDouble((Aim aim) -> -aim.margin())
                // Every approach in an orientation-free cell scores the same infinity, so the tiebreak is what
                // actually chooses: nearest to the face, which is the shortest reach and therefore the most robust
                // click. It is also a total order, which the plan's determinism requirement needs it to be.
                .thenComparingDouble(aim -> aim.point().distanceToSqr(eye))
                .thenComparingDouble(aim -> aim.point().x)
                .thenComparingDouble(aim -> aim.point().z));
        List<Vec3> best = new ArrayList<>(Math.min(scored.size(), budget.approachRefineTopN()));
        for (int i = 0; i < scored.size() && i < budget.approachRefineTopN(); i++) {
            best.add(scored.get(i).point());
        }
        return List.copyOf(best);
    }

    /**
     * The one placement a settled body cannot make: a block whose landed facing is UP.
     *
     * <p>Vanilla derives that facing from the placer's look, so the only way to produce it is a click that looks DOWN
     * — and looking down at a cell means being above it. In the layer currently being built there is nothing above to
     * stand on, which is why the basalt farm's sticky pistons reported "no candidate stance is standable" for all 145
     * candidates and blocked 12 248 cells behind them under the hard layer rule.
     *
     * <p>So the body stands IN the cell, jumps, and clicks the block below while airborne. The block lands in the
     * space just vacated, facing up because the eye was looking down as it did. This is pillaring, the most ordinary
     * movement in the game; there is nothing here a server could tell apart from a person.
     *
     * <p>Everything is derived rather than assumed. The family test is {@link #provenLook} equalling DOWN, which is
     * the engine's existing rule for which look produces which state and covers pistons, dispensers and droppers at
     * {@code facing=up} together with observers at {@code facing=down}. The landed state comes back out of
     * {@link #simulate} and has to EQUAL the desired one — the same simulator every other placement is checked
     * against — so a block whose orientation would come out differently is refused here rather than discovered in the
     * world.
     *
     * <p>What is NOT proven here is the tick the click goes out on. {@link JumpArc} says a window exists and how wide
     * it is; the executor re-reads the live body and clicks inside it. That is the same division every gate in this
     * engine already uses, and a disagreement between the two costs a jump, never a block.
     */
    public Optional<PlacementSolution> solveJumpPlace(PredictedWorld world, BlockPos cell, BlockState desired,
                                                      Item item) {
        if (item == null || provenLook(desired) != Direction.DOWN) {
            return Optional.empty();
        }
        int[] window = JumpArc.clearanceWindow(1.0D);
        if (window == null) {
            return Optional.empty();   // stated rather than assumed; a jump that cannot clear a block has no window
        }
        if (!world.get(cell).canBeReplaced()) {
            return Optional.empty();
        }
        // The stance IS the cell. That is the whole trick and the reason no ordinary stance search can find it: every
        // other placement forbids standing in the cell it is filling, and this one requires it.
        if (!world.isStandable(cell.getX(), cell.getY(), cell.getZ())) {
            return Optional.empty();
        }
        // Head room for the arc: the apex plus the standing body. Three cells above the feet, checked against the
        // predicted world so a block this plan places later cannot invalidate the jump after the fact.
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos above = cell.above(dy);
            if (collisionBoxOf(world.get(above), above) != null) {
                return Optional.empty();
            }
        }
        BlockPos support = cell.below();
        Vec3 aim = OutlineGeometry.faceCentre(support, new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), Direction.UP);
        Vec3 approach = centreOf(world, cell);
        if (!approachEnvelopeSupported(world, this.pose, cell, approach, JUMP_PLACE_TOLERANCE)) {
            return Optional.empty();
        }
        // Reach is demanded at the APEX rather than at the window edges: the eye is directly above the aim, so the
        // farthest the click ever has to travel is the highest the feet ever get. Use the physical feet plane rather
        // than the canonical path-node Y; soul sand and bottom slabs make those different.
        Vec3 eye = new Vec3(approach.x, approach.y + JumpArc.apex() + this.pose.crouchEyeHeight(), approach.z);
        if (eye.distanceTo(aim) > PlayerPose.PLANNING_REACH) {
            return Optional.empty();
        }
        Rotation rotation = this.quantise.apply(
                RotationUtils.calcRotationFromVec3d(eye, aim, PLANNING_REFERENCE));
        BlockState landed = this.simulate(world, new ItemStack(item), support, Direction.UP, aim, rotation, false);
        if (landed == null || !landed.equals(desired)) {
            return Optional.empty();
        }
        // A generous arrival tolerance, and it is generous for a reason rather than by oversight: vanilla decides
        // WHICH cell a placement lands in from the block and face that were hit, not from where the body stands, so
        // any position inside the cell produces the identical placement. The body only has to be in the cell it is
        // jumping out of.
        return Optional.of(new PlacementSolution(cell, desired, cell, approach, support, Direction.UP, aim, rotation,
                PlacementSolution.UNCONSTRAINED_MARGIN, JUMP_PLACE_TOLERANCE, landed, item));
    }

    /** How far off the cell centre the body may be when it jumps. Vanilla resolves the target cell from the hit block
     *  and face alone, so anywhere inside the cell places the same block; this is bounded by the body half-width so
     *  the jump starts from a position the cell actually contains. */
    static final double JUMP_PLACE_TOLERANCE = 0.2D;

    /** The dead centre of a face of a neighbour's collision box, in world coordinates. The right aim for a cell whose
     *  landed state does not depend on where on the face the click lands, and the wrong one for every other cell. */
    private static Vec3 faceCentre(BlockPos against, AABB local, Direction face) {
        return OutlineGeometry.faceCentre(against, local, face);
    }

    /** The nearest real outline component, in support/component order so exact-distance ties stay deterministic. */
    private static ClickTarget nearestClickable(PredictedWorld world, BlockPos cell, Vec3 eye, BlockState desired,
                                                BlockPos stance, StanceFilter filter) {
        ClickTarget nearest = null;
        double best = Double.MAX_VALUE;
        for (Direction support : PlacementGeometry.supportDirectionsFor(desired)) {
            BlockPos against = cell.relative(support);
            Direction face = support.getOpposite();
            if (!filter.allows(cell, stance, face)) {
                continue;
            }
            for (AABB local : clickableParts(world, against)) {
                Vec3 point = OutlineGeometry.faceCentre(against, local, face);
                double distance = eye.distanceToSqr(point);
                if (distance < best) {
                    best = distance;
                    nearest = new ClickTarget(support, against, local, point);
                }
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------------------------------------ body checks

    /** A0 to A3. Split out of {@link #checkBody} so the search can pay for A4's ray only once the cheap checks have
     *  passed, and so the ray counter is incremented by the code that actually casts. */
    private Rejection checkBodyAndEye(PredictedWorld world, BlockPos stance, Vec3 approach, BlockPos cell,
                                      BlockState desired) {
        if (!this.approachSupported(world, approach)) {
            return Rejection.APPROACH_NOT_SUPPORTED;
        }
        AABB body = this.pose.bodyAt(approach);
        AABB target = collisionBoxOf(desired, cell);
        if (target != null && body.intersects(target)) {
            return Rejection.BODY_INTERSECTS_TARGET;   // A1
        }
        // A2. Unreachable while the approach grid keeps the body centre APPROACH_INSET from the wall and the inset is
        // half the body width -- which is the reason that inset exists rather than a rounder number. Kept because the
        // method is public and a caller may hand in an approach point the grid never produced.
        if (!containedHorizontally(body, stance) && overlapsSolid(world, body)) {
            return Rejection.STANCE_NOT_STANDABLE;
        }
        Vec3 eye = this.pose.eyeAt(approach);
        int ex = Mth.floor(eye.x);
        int ey = Mth.floor(eye.y);
        int ez = Mth.floor(eye.z);
        if (world.isSolidFullCube(ex, ey, ez)) {
            return Rejection.EYE_INSIDE_BLOCK;   // A3
        }
        AABB occupied = world.collisionBox(ex, ey, ez);
        return occupied != null && occupied.contains(eye) ? Rejection.EYE_INSIDE_BLOCK : null;
    }

    /**
     * The collision box the desired state will have once it stands, in world coordinates, or null when it has none.
     *
     * <p>{@code getCollisionShape(null, null)} is the repo's established way of asking a state for its shape without a
     * world ({@code MovementHelper.isBlockNormalCube}, :1024) and it swallows the same exceptions for the same reason:
     * a handful of blocks genuinely need a level and a position to answer. The fallback differs though — that method
     * assumes "not a normal cube" because it is deciding whether to path over something, and this one assumes the FULL
     * cube, because it is deciding whether the bot's own body is in the way and the safe error is to refuse a stance
     * rather than to invent one.
     */
    private static AABB collisionBoxOf(BlockState state, BlockPos at) {
        try {
            VoxelShape shape = state.getCollisionShape(null, null);
            if (shape.isEmpty()) {
                return null;
            }
            return shape.bounds().move(at.getX(), at.getY(), at.getZ());
        } catch (Exception ignored) {
            return new AABB(at.getX(), at.getY(), at.getZ(),
                    at.getX() + 1.0D, at.getY() + 1.0D, at.getZ() + 1.0D);
        }
    }

    private static boolean containedHorizontally(AABB body, BlockPos stance) {
        return body.minX >= stance.getX() - 1.0E-9D && body.maxX <= stance.getX() + 1.0D + 1.0E-9D
                && body.minZ >= stance.getZ() - 1.0E-9D && body.maxZ <= stance.getZ() + 1.0D + 1.0E-9D;
    }

    private static boolean overlapsSolid(PredictedWorld world, AABB body) {
        for (int x = Mth.floor(body.minX); x <= Mth.floor(body.maxX - 1.0E-9D); x++) {
            for (int y = Mth.floor(body.minY); y <= Mth.floor(body.maxY - 1.0E-9D); y++) {
                for (int z = Mth.floor(body.minZ); z <= Mth.floor(body.maxZ - 1.0E-9D); z++) {
                    if (world.isSolidFullCube(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Is some real collision surface directly under the body's horizontal footprint at this exact feet height?
     *
     * <p>There are two details here that a block-level {@link PredictedWorld#isStandable} cannot answer:
     *
     * <ul>
     *   <li>An edge point may straddle two cells, and a corner point may straddle four. Looking only at
     *       {@code stance.below()} misses three quarters of the possible support.</li>
     *   <li>{@link VoxelShape#bounds()} is not a support surface. A bottom stair's bounds fill the whole cell and
     *       reach its top, even though the top step covers only a half, an L, or one corner. The individual shape
     *       boxes are therefore tested at their own {@code maxY}.</li>
     * </ul>
     *
     * <p>Any positive horizontal overlap is sufficient, matching vanilla's sneaking edge check: it asks whether the
     * horizontally shifted body box has any collision beneath it. The footprint is shrunk by vanilla's own
     * {@code 1e-7} epsilon so merely touching a face is not invented into support by floating-point equality.
     *
     * <p>Package-private for the exact geometry regression; this is not builder API.
     */
    boolean approachSupported(BlockGetter world, Vec3 approach) {
        return approachSupported(world, this.pose, approach);
    }

    /**
     * Exact support predicate shared with the live fine-approach gate and the break/removal planners.
     *
     * <p>The {@link BlockGetter} form is intentional: a proof reads {@link PredictedWorld}, while the last guard before
     * a movement key reads the live level. Both walk the same component boxes and the same body footprint. Predicted
     * stairs additionally derive their neighbour-updated shape; a live level already carries that shape but deriving it
     * again is harmless and keeps the two paths identical.
     */
    static boolean approachSupported(BlockGetter world, PlayerPose pose, Vec3 approach) {
        return supportRegions(world, pose, approach.y, approach.x, approach.x, approach.z, approach.z)
                .stream().anyMatch(region -> region.contains(approach.x, approach.z));
    }

    /** One rectangle of body-centre positions supported at one exact feet plane. */
    private record SupportRegion(double minX, double maxX, double minZ, double maxZ) {

        private boolean contains(double x, double z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }

        private boolean intersects(double minX, double maxX, double minZ, double maxZ) {
            return this.maxX >= minX && this.minX <= maxX && this.maxZ >= minZ && this.minZ <= maxZ;
        }
    }

    /**
     * Collision-top components expressed as the region in which a body centre retains positive support.
     *
     * <p>A top rectangle supports a body whenever it overlaps the body's horizontal footprint. Minkowski-expanding
     * that rectangle by the body's half width is therefore the exact centre region; shrinking by
     * {@link #SUPPORT_EPSILON} turns a face-touch into the same conservative "not supported" answer vanilla uses.
     */
    private static List<SupportRegion> supportRegions(BlockGetter world, PlayerPose pose, double feetY,
                                                      double wantedMinX, double wantedMaxX,
                                                      double wantedMinZ, double wantedMaxZ) {
        AABB bodyAtOrigin = pose.bodyAt(new Vec3(0.0D, feetY, 0.0D));
        double halfX = (bodyAtOrigin.maxX - bodyAtOrigin.minX) * 0.5D;
        double halfZ = (bodyAtOrigin.maxZ - bodyAtOrigin.minZ) * 0.5D;
        int lowX = Mth.floor(wantedMinX - halfX);
        int highX = Mth.floor(wantedMaxX + halfX);
        int lowZ = Mth.floor(wantedMinZ - halfZ);
        int highZ = Mth.floor(wantedMaxZ + halfZ);
        int highestSupportCell = Mth.floor(feetY - SUPPORT_EPSILON);
        List<SupportRegion> regions = new ArrayList<>();

        for (int x = lowX; x <= highX; x++) {
            for (int z = lowZ; z <= highZ; z++) {
                // Ordinary blocks and stairs are in highestSupportCell. One extra cell covers collision shapes that
                // rise above their own block (fences and walls) without turning this into an unbounded vertical scan.
                for (int y = highestSupportCell; y >= highestSupportCell - 1; y--) {
                    BlockPos support = new BlockPos(x, y, z);
                    BlockState state = supportCollisionState(world, support);
                    if (state.isAir()) {
                        continue;
                    }
                    VoxelShape shape;
                    try {
                        shape = state.getCollisionShape(world, support);
                    } catch (Exception ignored) {
                        // Unknown geometry cannot prove support. Other cells under the same footprint may still do so.
                        continue;
                    }
                    for (AABB local : shape.toAabbs()) {
                        double top = y + local.maxY;
                        if (Math.abs(top - feetY) > SUPPORT_EPSILON) {
                            continue;
                        }
                        double minX = x + local.minX - halfX + SUPPORT_EPSILON;
                        double maxX = x + local.maxX + halfX - SUPPORT_EPSILON;
                        double minZ = z + local.minZ - halfZ + SUPPORT_EPSILON;
                        double maxZ = z + local.maxZ + halfZ - SUPPORT_EPSILON;
                        if (maxX >= wantedMinX && minX <= wantedMaxX
                                && maxZ >= wantedMinZ && minZ <= wantedMaxZ) {
                            regions.add(new SupportRegion(minX, maxX, minZ, maxZ));
                        }
                    }
                }
            }
        }
        return regions;
    }

    /**
     * Prove FineApproach from every physically possible {@code GoalBlock} arrival in the stance, not from an invented
     * cell centre.
     *
     * <p>The source set is the union of all supported body-centre regions inside the goal cell at the planned feet
     * plane. Its bounding rectangle is required to be fully covered, then joined with the whole square around the
     * allowed arrival ball and required to remain fully covered as one rectangle. That is deliberately stronger than
     * path existence: it proves a convex support envelope, so the controller's monotone eight-way steps cannot cross a
     * stair cut-out. Straight stairs and the surviving quarter of an outer stair remain legal when their expanded
     * centre region is rectangular; an inner-L or two disconnected slivers fail closed.
     */
    static boolean approachEnvelopeSupported(BlockGetter world, PlayerPose pose, BlockPos stance, Vec3 approach,
                                             double tolerance) {
        double cellMinX = stance.getX();
        double cellMaxX = stance.getX() + 1.0D;
        double cellMinZ = stance.getZ();
        double cellMaxZ = stance.getZ() + 1.0D;
        double reserve = FineApproach.SUPPORT_ENVELOPE_RESERVE;
        double wantedMinX = Math.min(cellMinX, approach.x - tolerance) - reserve;
        double wantedMaxX = Math.max(cellMaxX, approach.x + tolerance) + reserve;
        double wantedMinZ = Math.min(cellMinZ, approach.z - tolerance) - reserve;
        double wantedMaxZ = Math.max(cellMaxZ, approach.z + tolerance) + reserve;
        List<SupportRegion> regions = supportRegions(world, pose, approach.y,
                wantedMinX, wantedMaxX, wantedMinZ, wantedMaxZ);

        double sourceMinX = Double.POSITIVE_INFINITY;
        double sourceMaxX = Double.NEGATIVE_INFINITY;
        double sourceMinZ = Double.POSITIVE_INFINITY;
        double sourceMaxZ = Double.NEGATIVE_INFINITY;
        for (SupportRegion region : regions) {
            if (!region.intersects(cellMinX, cellMaxX, cellMinZ, cellMaxZ)) {
                continue;
            }
            sourceMinX = Math.min(sourceMinX, Math.max(cellMinX, region.minX));
            sourceMaxX = Math.max(sourceMaxX, Math.min(cellMaxX, region.maxX));
            sourceMinZ = Math.min(sourceMinZ, Math.max(cellMinZ, region.minZ));
            sourceMaxZ = Math.max(sourceMaxZ, Math.min(cellMaxZ, region.maxZ));
        }
        if (!Double.isFinite(sourceMinX) || sourceMinX > sourceMaxX || sourceMinZ > sourceMaxZ) {
            return false;
        }

        double envelopeMinX = Math.min(sourceMinX, approach.x - tolerance) - reserve;
        double envelopeMaxX = Math.max(sourceMaxX, approach.x + tolerance) + reserve;
        double envelopeMinZ = Math.min(sourceMinZ, approach.z - tolerance) - reserve;
        double envelopeMaxZ = Math.max(sourceMaxZ, approach.z + tolerance) + reserve;
        return rectangleCovered(regions, envelopeMinX, envelopeMaxX, envelopeMinZ, envelopeMaxZ);
    }

    /** The post-action form: only the stationary arrival square matters; there is no second travel hand-off. */
    static boolean arrivalDiskSupported(BlockGetter world, PlayerPose pose, Vec3 approach, double tolerance) {
        List<SupportRegion> regions = supportRegions(world, pose, approach.y,
                approach.x - tolerance, approach.x + tolerance,
                approach.z - tolerance, approach.z + tolerance);
        return rectangleCovered(regions, approach.x - tolerance, approach.x + tolerance,
                approach.z - tolerance, approach.z + tolerance);
    }

    /** Exact coverage of an axis-aligned rectangle by a union of axis-aligned support regions. */
    private static boolean rectangleCovered(List<SupportRegion> regions, double minX, double maxX,
                                            double minZ, double maxZ) {
        if (regions.isEmpty() || minX > maxX || minZ > maxZ) {
            return false;
        }
        List<Double> cuts = new ArrayList<>();
        cuts.add(minX);
        cuts.add(maxX);
        for (SupportRegion region : regions) {
            if (region.maxX < minX || region.minX > maxX) {
                continue;
            }
            cuts.add(Mth.clamp(region.minX, minX, maxX));
            cuts.add(Mth.clamp(region.maxX, minX, maxX));
        }
        cuts.sort(Double::compare);
        List<Double> probes = new ArrayList<>(cuts.size() * 2);
        Double previous = null;
        for (double cut : cuts) {
            if (previous == null || Math.abs(cut - previous) > SUPPORT_EPSILON) {
                probes.add(cut);
                if (previous != null) {
                    probes.add((previous + cut) * 0.5D);
                }
                previous = cut;
            }
        }
        for (double x : probes) {
            if (!verticalSliceCovered(regions, x, minZ, maxZ)) {
                return false;
            }
        }
        return true;
    }

    /** Coverage of one vertical slice of {@link #rectangleCovered}. */
    private static boolean verticalSliceCovered(List<SupportRegion> regions, double x, double minZ, double maxZ) {
        List<double[]> intervals = new ArrayList<>();
        for (SupportRegion region : regions) {
            if (x < region.minX || x > region.maxX || region.maxZ < minZ || region.minZ > maxZ) {
                continue;
            }
            intervals.add(new double[] {Math.max(minZ, region.minZ), Math.min(maxZ, region.maxZ)});
        }
        intervals.sort(Comparator.comparingDouble(interval -> interval[0]));
        double covered = minZ;
        for (double[] interval : intervals) {
            if (interval[0] > covered + SUPPORT_EPSILON) {
                return false;
            }
            covered = Math.max(covered, interval[1]);
            if (covered >= maxZ - SUPPORT_EPSILON) {
                return true;
            }
        }
        return covered >= maxZ - SUPPORT_EPSILON;
    }

    /**
     * The state whose collision surface will exist after ordinary neighbour updates.
     *
     * <p>{@link PredictedWorld#apply} is intentionally a raw overlay write. That is useful for deterministic forward
     * simulation, but a stair stores its neighbour-derived {@link StairBlock#SHAPE} property and vanilla rewrites an
     * already placed stair the instant an adjacent stair lands. Run {@code 5c2dd1c5} hit exactly that seam: the
     * overlay still held {@code straight}, while the live block under the body had become {@code outer_right}.
     *
     * <p>Only the collision read is normalised here; no predicted state is mutated and no OrderPlanner ownership is
     * bypassed. The derivation below is vanilla's {@code StairBlock.getStairsShape} expressed against
     * {@link PredictedWorld}, including its same-half and side-neighbour guards.
     */
    private static BlockState supportCollisionState(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof StairBlock
                ? state.setValue(StairBlock.SHAPE, effectiveStairsShape(world, pos, state))
                : state;
    }

    /**
     * Vanilla's neighbour-derived stair shape against the predicted overlay.
     *
     * <p>Package-private so the exact post-neighbour regression can assert that a raw {@code straight} state is read
     * as the {@code outer_right} collision form the live world creates.
     */
    static StairsShape effectiveStairsShape(PredictedWorld world, BlockPos pos, BlockState state) {
        return effectiveStairsShape((BlockGetter) world, pos, state);
    }

    private static StairsShape effectiveStairsShape(BlockGetter world, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(StairBlock.FACING);
        Half half = state.getValue(StairBlock.HALF);

        BlockState front = world.getBlockState(pos.relative(facing));
        if (StairBlock.isStairs(front)
                && half == front.getValue(StairBlock.HALF)) {
            Direction frontFacing = front.getValue(StairBlock.FACING);
            if (frontFacing.getAxis() != facing.getAxis()
                    && stairCanTakeShape(state, world, pos, frontFacing.getOpposite())) {
                return frontFacing == facing.getCounterClockWise()
                        ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
            }
        }

        BlockState back = world.getBlockState(pos.relative(facing.getOpposite()));
        if (StairBlock.isStairs(back)
                && half == back.getValue(StairBlock.HALF)) {
            Direction backFacing = back.getValue(StairBlock.FACING);
            if (backFacing.getAxis() != facing.getAxis()
                    && stairCanTakeShape(state, world, pos, backFacing)) {
                return backFacing == facing.getCounterClockWise()
                        ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
            }
        }
        return StairsShape.STRAIGHT;
    }

    /** Vanilla's {@code StairBlock.canTakeShape}: a matching stair on this side keeps the run straight. */
    private static boolean stairCanTakeShape(BlockState state, BlockGetter world, BlockPos pos,
                                             Direction side) {
        BlockState neighbour = world.getBlockState(pos.relative(side));
        return !StairBlock.isStairs(neighbour)
                || neighbour.getValue(StairBlock.FACING) != state.getValue(StairBlock.FACING)
                || neighbour.getValue(StairBlock.HALF) != state.getValue(StairBlock.HALF);
    }

    // ------------------------------------------------------------------------------------------------- prediction

    /**
     * The geometry, then the family rule, then the acceptance test — the three steps V2 folds into one call into
     * vanilla, kept apart here because only the first of them can be answered without a Level.
     *
     * <p>Public because {@link PlaceGate} runs exactly this composition against the LIVE ray, the LIVE rotation and a
     * one-tick capture of the LIVE world, and the whole value of the gate is that it asks the same question the
     * planner answered. A second copy of the sneak rule, the deferred-orientation adoption and the {@code itemVerify}
     * choice would drift, and the way it would show is a cell the planner proved and the executor refuses forever.
     *
     * @return the state that would land and is accepted, or null when the click must not go out
     */
    public BlockState predict(PredictedWorld world, BlockPos cell, BlockState desired, ItemStack stack,
                              BlockPos against, Direction face, Vec3 aimPoint, Rotation rotation) {
        // Crouched for everything except a chest, because sneak forces a chest to SINGLE and a crouched placement
        // therefore destroys a planned double chest silently. The sneak state is an INPUT the action carries, not an
        // assumption about the executor's posture -- which is exactly why it can differ per cell. The residual is
        // named rather than hidden: a chest is the one family whose click comes from the standing eye while its reach
        // and occlusion were proven from the crouched one, so it is the family the executor's live gate is
        // load-bearing for.
        boolean sneaking = PlacementFamilies.classify(desired) != PlacementFamilies.Family.CHEST_TYPE;
        BlockState geometric = this.simulate(world, stack, against, face, aimPoint, rotation, sneaking);
        if (geometric == null) {
            return null;
        }
        BlockState candidate = adoptDeferredOrientation(geometric, desired);
        BlockState landed = PlacementFamilies.resolve(world, cell, candidate, desired, face, aimPoint, sneaking);
        if (landed == null) {
            return null;
        }
        // itemVerify = TRUE, exactly as V2's placementResultAccepted (:2110). "Would this click be accepted" is a
        // stricter question than "is this cell done": buildIgnoreExisting and buildValidSubstitutes must not be able
        // to answer the first one, or the planner proves a placement the acceptance pass will then reject.
        return this.settings.valid(landed, desired, true)
                || PlacementGeometry.interactionClicks(landed, desired) >= 0
                || FluidPlan.canFinishWaterlogging(landed, desired, this.settings) ? landed : null;
    }

    /**
     * Copy the properties whose vanilla derivation this file does not claim to know, from the state the schematic
     * asked for.
     *
     * <p>Deliberately narrow, and one property narrower than it used to be. The four-way {@code HORIZONTAL_FACING}
     * was copied here, and that was the bug this method is named after: copying it made
     * {@code predicted.facing == desired.facing} hold for EVERY stance, so the property could never disagree, the
     * proof for it was vacuous, and the sign of the facing was never decided by anything. The live gate then refused
     * the click — correctly — and, because planning is deterministic, three successive re-plans produced the same
     * wrong stance byte for byte. {@link #simulate} now DERIVES that property from the quantised yaw wherever
     * {@link PlacementGeometry#yawFacingConvention} names the block's convention, and leaves it at the item default
     * where it does not, which the acceptance test in {@link #predict} turns into a named blocker rather than a click.
     *
     * <p>What remains is the six-way {@code FACING} of the blocks whose derivation genuinely is per-block and
     * unlisted. Everything {@link #simulate} DID derive stays derived, which is what lets a genuine contradiction
     * surface: a stair clicked on a face that forces {@code half=bottom} while the schematic wants {@code half=top} is
     * refused here rather than placed and broken.
     */
    private static BlockState adoptDeferredOrientation(BlockState geometric, BlockState desired) {
        BlockState result = geometric;
        if (result.hasProperty(BlockStateProperties.FACING) && desired.hasProperty(BlockStateProperties.FACING)
                && !isLookDerivedFacing(result.getBlock())) {
            // End rods, barrels, shulker boxes: six-way facings whose vanilla derivation is per-block (clicked face,
            // its opposite, or the look) and which no rule in this file claims to know.
            //
            // NOT hoppers, however much it looks like it should be. A hopper carries FACING_HOPPER, a different
            // Property object, so this branch cannot see one and never could -- and a hopper's facing IS known, and
            // is derived in simulate() from the clicked face. Re-adding the word here would invite someone to key
            // this on HopperBlock.FACING too, which would copy the schematic's facing into the prediction and make
            // the proof vacuous for every stance: exactly the bug this method's javadoc describes above.
            result = result.setValue(BlockStateProperties.FACING,
                    desired.getValue(BlockStateProperties.FACING));
        }
        return result;
    }

    /**
     * The one look direction that produces this state's facing, or null when the look does not decide it.
     *
     * <p>{@link PlacementGeometry#requiredLookDirections} returns BOTH signs on purpose — vanilla is not consistent
     * about which way round it reads the look, and V2 survives that by trying both and letting its live simulation
     * pick. This oracle has no live simulation to defer to, so the convention has to be named: verified in 26.1.2
     * bytecode, a piston is {@code getNearestLookingDirection().getOpposite()} and an observer is that opposite taken
     * twice, which cancels. V2 encodes the same split in its stance ordering ({@code verticalLook}, :4543) and that
     * ordering is what made all 112 of etz-basalt's vertical observers reachable.
     *
     * <p>Getting this wrong is not subtle: it is a whole family placed backwards, and it shows up as a named
     * divergence on that family's first cell rather than as a slow drift.
     */
    private static Direction provenLook(BlockState desired) {
        if (PlacementGeometry.requiredLookDirections(desired) == null
                || !desired.hasProperty(BlockStateProperties.FACING)) {
            return null;
        }
        Direction facing = desired.getValue(BlockStateProperties.FACING);
        return desired.getBlock() instanceof ObserverBlock ? facing : facing.getOpposite();
    }

    /** The membership test behind {@link PlacementGeometry#requiredLookDirections}, asked of a BLOCK because
     *  {@link #simulate} knows the item it is holding and not the state the schematic wants. */
    private static boolean isLookDerivedFacing(Block block) {
        return block instanceof PistonBaseBlock || block instanceof DispenserBlock || block instanceof ObserverBlock;
    }

    /**
     * Dominance one dimension down, and SIGNED.
     *
     * <p>{@code Direction.fromYRot} reads only the two horizontal components, so the pitch is irrelevant and the
     * contest is the horizontal one — but it is a contest between four quadrants, not two axes. The previous form
     * took {@code Math.abs} of both components and returned {@code |dx| - |dz|}, which is the same number for a point
     * two cells north of the eye and a point two cells south of it. Both scored positively for a {@code facing=north}
     * stair, the tiebreak took the lower coordinate, and the bot ended up looking SOUTH while the plan claimed NORTH.
     *
     * <p>The signed form is the exact horizontal analogue of
     * {@link PlacementGeometry#mostAlignedPointOnFaceWithMargin}: project onto the required look, subtract the
     * strongest competitor, and let a point aiming the wrong way down that axis come out negative so the caller drops
     * it. A margin of zero is a 45-degree aim, which {@code fromYRot}'s flooring would decide by rounding.
     */
    /**
     * How far the look from {@code eye} to {@code point} is from flipping to a different nearest direction.
     *
     * <p>{@code Direction.getNearest} picks the axis with the largest absolute component of the look vector, so the
     * component ALONG the wanted direction has to beat the largest of the other two. Positive means it does; the
     * number is how much room there is before it stops doing so, in the same units as the vector itself.
     *
     * <p>Works for all six directions rather than only the vertical ones, and deliberately so: the horizontal
     * families have {@link #yawMargin}, which answers the same question for the four-way convention, and having one
     * shape for both makes it obvious that neither axis is left unguarded again.
     */
    private static double axisDominance(Vec3 eye, Vec3 point, Direction look) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double along = look.getStepX() * dx + look.getStepY() * dy + look.getStepZ() * dz;
        double across = switch (look.getAxis()) {
            case X -> Math.max(Math.abs(dy), Math.abs(dz));
            case Y -> Math.max(Math.abs(dx), Math.abs(dz));
            case Z -> Math.max(Math.abs(dx), Math.abs(dy));
        };
        return along - across;
    }

    /** How much room the dominance must have to spare. The body arrives anywhere inside its arrival ball, so a look
     *  that only just wins at the proven point can lose at the point the body actually reaches. */
    private static final double DOMINANCE_MARGIN = 0.05D;

    private static double yawMargin(Vec3 eye, Vec3 point, Direction look) {
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        double along = look.getStepX() * dx + look.getStepZ() * dz;
        double across = look.getAxis() == Direction.Axis.X ? Math.abs(dz) : Math.abs(dx);
        return along - across;
    }

    /** Which axis of this vector wins outright, or null for a tie. Strict on purpose: vanilla's {@code getNearest}
     *  accepts a tie and breaks it by enum order, and a placement decided by enum order is a coin flip. */
    private static Direction dominantDirection(Vec3 direction) {
        double x = Math.abs(direction.x);
        double y = Math.abs(direction.y);
        double z = Math.abs(direction.z);
        if (x > y && x > z) {
            return direction.x > 0.0D ? Direction.EAST : Direction.WEST;
        }
        if (y > x && y > z) {
            return direction.y > 0.0D ? Direction.UP : Direction.DOWN;
        }
        if (z > x && z > y) {
            return direction.z > 0.0D ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------------- world reads

    /**
     * The neighbour's collision box in CELL-LOCAL coordinates, or null when there is nothing there to click.
     * {@link PredictedWorld#collisionBox} answers in world coordinates and {@link PlacementGeometry} takes the local
     * box plus the position — the two conventions meet here and nowhere else.
     *
     * <p>These boxes are only as true as the STATE they come off, and for a block whose outline follows a connective
     * property that state is not the item default. {@link #simulate} does not derive {@code north/east/south/west} —
     * they are {@link PlacementGeometry#AUTO_RESOLVED_PROP_NAMES}, the game's to resolve — which made it look as
     * though nothing depended on them. Aim points are measured on the outline, and the outline does depend on them:
     * a wire the plan placed was stored as the one-box dot while the live block was a three-box cross, so the aim on
     * its side face was proven against a shape that never exists. Measured on run {@code 9561f19f}, the west face at
     * {@code 126.188,-58.969,80.344} against a live hit on the west ARM at {@code 126.119,-58.938,80.272}; and again
     * on {@code 47f7142e}, nineteen divergences in thirteen minutes. {@link RedstoneConnections} now derives it where
     * vanilla does, and {@code ConnectiveOutlineDriftTest} pins both the failure and its closure.
     *
     * <p>What remains open, and the shape any future report of this will have: the derivation answers "what does a
     * wire placed HERE, NOW, become". Fences, walls, panes and bars have the same kind of neighbour-derived outline
     * and are NOT derived — no run has produced one, so nothing was written for them on spec. And a wire the plan
     * places and later places a neighbour of drifts again, because vanilla's {@code updateShape} keeps the dot/cross
     * distinction sticky and re-deriving it as a fresh placement would be a different kind of wrong.
     *
     * <p>Two other repairs were worked out before the derivation and are recorded so nobody re-derives them.
     * Returning {@code List.of()} for such a state turns the affected actions into blockers instead of stalls — 351
     * of layer -59's 1817 in {@code 9561f19f.r22} click against a wire the same plan places. Proving the aim over the
     * block's minimal and maximal variants is airtight, since hit distance is monotone in the shape and agreeing
     * extremes sandwich every variant, and it comes to the same thing for a wire: the side silhouette is exactly what
     * the connections change, so no side-face aim survives.
     */
    private static List<AABB> clickableParts(PredictedWorld world, BlockPos pos) {
        if (world.get(pos).canBeReplaced()) {
            return List.of();   // air, water, grass: the placement replaces them instead of clicking against them
        }
        return OutlineGeometry.localParts(world, pos);
    }

    /** The cell vanilla fills in by itself when this state is placed — a door's upper half, a bed's head — or null.
     *  Read off the state's own properties rather than off a block list, so a modded door is covered too. */
    private static BlockPos secondCellOf(BlockState desired, BlockPos cell) {
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return cell.above();
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART)
                && desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return cell.relative(desired.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return null;
    }

    /**
     * The first stack in the snapshot that can place this state, or null.
     *
     * <p>Matched by ITEM and not by block, because one torch item makes {@code torch} on the ground and
     * {@code wall_torch} on a side, and the block comparison alone reported a schematic full of wall torches as
     * missing material while the torches sat in the bot's hand.
     *
     * <p>Walks the WHOLE list — 36 slots when the planner calls it — and returns the STACK rather than its index. The
     * index would be a lie in both directions: a backpack index is not a hotbar slot and
     * {@code BuildAction.checkHandSlot} rightly refuses it, and even a hotbar index is only where the item happens to
     * sit BEFORE the schedule runs. Scanning nine slots instead of thirty-six is what reported 10 480 of etz-basalt's
     * 15 004 cells as {@code NO_ITEM} while the bot carried every one of the 34 materials.
     *
     * <p>Deterministic by construction: the first matching slot wins, and the snapshot is a frozen list. Two stacks of
     * the same item therefore always resolve to the same one, and the resolution never depends on iteration order.
     */
    private static ItemStack stackThatPlaces(List<ItemStack> inventory, BlockState desired) {
        for (int index = 0; index < inventory.size(); index++) {
            ItemStack stack = inventory.get(index);
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (PlacementGeometry.itemCanPlaceBlock(blockItem.getBlock().defaultBlockState(), desired)) {
                return stack;
            }
        }
        return null;
    }

    private static boolean containsItem(List<ItemStack> inventory, Item item) {
        if (inventory == null || item == null) {
            return false;
        }
        for (ItemStack stack : inventory) {
            if (stack != null && !stack.isEmpty() && stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    /** @see #footY(PredictedWorld, BlockPos) */
    private static Vec3 centreOf(PredictedWorld world, BlockPos stance) {
        return new Vec3(stance.getX() + 0.5D, footY(world, stance), stance.getZ() + 0.5D);
    }

    /**
     * The physical feet plane represented by this canonical pathing stance.
     *
     * <p>{@code IPlayerContext.playerFeet()} does not simply floor the physical Y. It adds {@code 0.1251} for soul
     * sand, then promotes a coordinate that falls inside a slab by one cell. Consequently a body at {@code y=0.875}
     * on soul sand and one at {@code y=0.5} on a bottom slab are both reported at canonical stance {@code y=1}.
     * Looking at {@code world.get(stance)} therefore examines air and invents the integer plane; the support block is
     * below the canonical stance and its collision components carry the real answer.
     *
     * <p>Every collision top is tested through the same canonicalisation rule and only a top that maps back to this
     * stance may win. This also separates a bottom stair correctly: its lower half belongs to the lower path node,
     * while its upper step belongs to the stance above it.
     */
    static double footY(PredictedWorld world, BlockPos stance) {
        BlockPos support = stance.below();
        BlockState state = supportCollisionState(world, support);
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(world, support);
        } catch (Exception ignored) {
            return stance.getY();
        }
        double best = Double.NEGATIVE_INFINITY;
        for (AABB local : shape.toAabbs()) {
            double top = support.getY() + local.maxY;
            if (canonicalFeetY(world, stance.getX(), stance.getZ(), top) == stance.getY()) {
                best = Math.max(best, top);
            }
        }
        return Double.isFinite(best) ? best : stance.getY();
    }

    /** The Y half of {@code IPlayerContext.playerFeet()}, including its slab promotion. */
    private static int canonicalFeetY(PredictedWorld world, int x, int z, double physicalFeetY) {
        int raw = Mth.floor(physicalFeetY + 0.1251D);
        return world.get(x, raw, z).getBlock() instanceof SlabBlock ? raw + 1 : raw;
    }

    // ------------------------------------------------------------------------------------------- stance ordering

    /** Which way the bot has to be looking for a vertical facing, or null when this state has none. A piston faces
     *  AWAY from the look and an observer faces exactly WHERE it looks, so ordering by the FACING instead of by the
     *  LOOK put every vertical observer's workable stances on the wrong side of the penalty (V2, :4543). */
    private static Direction verticalLookFor(BlockState desired) {
        if (!desired.hasProperty(BlockStateProperties.FACING)) {
            return null;
        }
        Direction facing = desired.getValue(BlockStateProperties.FACING);
        if (facing.getAxis() != Direction.Axis.Y) {
            return null;
        }
        return desired.getBlock() instanceof ObserverBlock ? facing : facing.getOpposite();
    }

    /** V2's stance sort key ({@code placementStancesFor}, :4551), with the origin fixed to the cell: the planner has
     *  no player position, because it is proving an order hours before anybody stands anywhere. */
    private static long stanceKey(BlockPos stance, BlockPos cell, int sx, int sy, int sz,
                                  Direction yawLook, Direction verticalLook) {
        long dx = stance.getX() - cell.getX();
        long dy = stance.getY() - cell.getY();
        long dz = stance.getZ() - cell.getZ();
        long key = dx * dx + dy * dy + dz * dz;
        long side = dx * sx + dy * sy + dz * sz;
        if (side > 0) {
            key += WRONG_SIDE_PENALTY;
        }
        if (yawLook != null) {
            // The bot looks FROM the stance TOWARDS the cell, so the offset from cell to stance runs against the look
            // and `along` has to be NEGATIVE. Zero is the perpendicular case and positive is the far end of the axis,
            // where the same block lands rotated a hundred and eighty degrees -- which used to score identically to
            // the right end and win the tiebreak by having the lower coordinate.
            long along = yawLook.getStepX() * dx + yawLook.getStepZ() * dz;
            long across = yawLook.getAxis() == Direction.Axis.X ? dz : dx;
            if (across != 0 || along >= 0) {
                key += Math.abs(across) == 1 && along < 0 ? NEAR_AXIS_PENALTY : OFF_AXIS_PENALTY;
            }
        }
        if (verticalLook != null) {
            // To look DOWN at the cell, be level with it or above; to look UP, level or below. And be adjacent: the
            // steepness of the look is what decides the axis, and steepness falls off with distance.
            boolean rightSide = verticalLook == Direction.DOWN ? dy >= 0 : dy <= 0;
            long horizontal = Math.max(Math.abs(dx), Math.abs(dz));
            if (!rightSide) {
                key += OFF_AXIS_PENALTY;
            } else if (horizontal > 1) {
                key += NEAR_AXIS_PENALTY * horizontal;
            }
        }
        return key;
    }

    // -------------------------------------------------------------------------------------------- accumulation

    /**
     * The mutable half of a {@link Solve}, alive for exactly one call. Not shared, not reused, never handed out.
     *
     * <p>It carries the world and the budget so that {@link #finish} can cast rays. That is the one place where a
     * check runs on the chosen solution rather than on a candidate, and giving it the world through the accumulator
     * is what keeps its two callers — the fast path and the full search — from each having to remember to run it.
     */
    private static final class Search {

        private final PredictedWorld world;
        private final SolveBudget budget;
        private final int[] rejections = new int[REJECTIONS.length];
        private final List<PlacementSolution> solutions = new ArrayList<>();
        private final List<PlacementSolution> tight = new ArrayList<>();
        private int stancesTried;
        private int raysCast;

        /** The cell and stance a rejection is about, so the verbose journal can name them. Plain mutable fields and
         *  not parameters on {@link #reject}: every one of the thirty-odd rejection sites would otherwise have to be
         *  edited to carry two arguments it already has in scope, and a refactor that size on the way to a log line
         *  is how a working proof gets broken. */
        private BlockPos subject;
        private BlockPos from;
        private boolean rayBudgetExhausted;
        private boolean stanceBudgetExhausted;

        private Search(PredictedWorld world, SolveBudget budget) {
            this.world = world;
            this.budget = budget;
        }

        private void reject(Rejection reason) {
            this.rejections[reason.ordinal()]++;
            // Every discarded thought, with the cell it was about and the stance it was tried from. Off unless the
            // full journal is switched on, because a basalt plan discards on the order of a million candidates and
            // that is gigabytes -- which is exactly what it is for. The owner's rule: never again a place where the
            // evidence simply is not there.
            if (BuildTrace.isVerbose()) {
                BuildTrace.rejected(reason.name(), this.subject, this.from);
            }
        }
    }

    /** Freeze the accumulator into the ranked, immutable answer — after the head has been proved over the whole ball
     *  the executor may arrive in. */
    private Solve finish(Search search) {
        Comparator<PlacementSolution> ranking = this.ranking();
        search.solutions.sort(ranking);
        search.tight.sort(ranking);
        List<PlacementSolution> ranked = this.proveHeadOverApproachBall(search);
        // The counter array is cloned out: a record component is only as immutable as what it points at, and a Solve
        // that shares its array with a live accumulator is a report that changes while it is being read.
        return new Solve(ranked, List.copyOf(search.tight), search.rejections.clone(),
                search.stancesTried, search.raysCast, search.rayBudgetExhausted, search.stanceBudgetExhausted);
    }

    /**
     * V2 of plan 19: prove the sight line over the BALL, not over the point, and only for the solution that will
     * actually be taken.
     *
     * <p>The ranked list is walked from the head. The first candidate whose ray lands on the same cell and the same
     * face from every rim point of some tolerance rung becomes the head, carrying that rung; the candidates ahead of
     * it are dropped and counted as {@link Rejection#OCCLUSION_NOT_ROBUST}, because a solution that cannot survive
     * the tightest arrival slack the body can hold is not a solution — it is the 0.019-block clip of action 102
     * waiting to be re-discovered live.
     *
     * <p>The TAIL is left unproved and unchanged, deliberately. Only {@link Solve#best} is ever executed, so proving
     * the rest would spend eight rays apiece to decorate a number the report prints; and the rays are this planner's
     * main cost, already 2.2 million for etz-basalt. The tail's tolerances therefore remain the provisional widest
     * value, which is why {@link PlacementSolution#approachTolerance} is documented as a claim until the solution is
     * chosen.
     *
     * <p>Cost when nothing is wrong: eight rays for the cell, whatever the search spent getting there. The walk is
     * bounded by {@link #OCCLUSION_PROOF_MAX_CANDIDATES} and NOT by the search's ray cap — see that constant for why
     * charging the proof against the search's budget refused solutions nobody had looked at.
     */
    private List<PlacementSolution> proveHeadOverApproachBall(Search search) {
        if (search.solutions.isEmpty() || search.world == null) {
            return List.copyOf(search.solutions);
        }
        int examined = Math.min(search.solutions.size(), OCCLUSION_PROOF_MAX_CANDIDATES);
        for (int i = 0; i < examined; i++) {
            PlacementSolution candidate = search.solutions.get(i);
            double tolerance = this.provenApproachTolerance(search, candidate);
            if (Double.isNaN(tolerance)) {
                // Name the failed contract precisely. Support is checked first because "the route to this approach
                // leaves the upper surface" needs a different repair from a dirty sight line; only the latter spends
                // the diagnostic ray that separates a bad stance from a too-tight arrival ball.
                search.reject(this.arrivalRejection(search, candidate));
                continue;
            }
            List<PlacementSolution> ranked = new ArrayList<>(search.solutions.size() - i);
            ranked.add(candidate.withApproachTolerance(tolerance));
            // The tail keeps its order and its provisional tolerance; it exists for the report's "N would work".
            ranked.addAll(search.solutions.subList(i + 1, search.solutions.size()));
            return List.copyOf(ranked);
        }
        // Every candidate the proof was allowed to look at clips somewhere inside its own arrival ball. The tail may
        // hold one that does not, and it is still refused: handing out an unproved head is the defect, and a cell
        // this crowded is one a human should see named rather than watch the executor discover.
        return List.of();
    }

    /**
     * The widest arrival tolerance whose support corridor, body geometry, orientation and click all remain valid, or
     * {@code NaN} when even {@link FineApproach#TIGHT_TOLERANCE} is too wide. This is the final arrival proof on its
     * own, for a caller that has a solution in hand rather than a search.
     *
     * <p>The rays it spends are not counted against anything, which is correct here and would not be inside a solve:
     * this form exists for tests and for a diagnostic pass over a finished plan, neither of which is competing for
     * {@link SolveBudget#maxRays()}.
     */
    public double provenApproachTolerance(PredictedWorld world, PlacementSolution solution, SolveBudget budget) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(budget, "budget");
        return this.provenApproachTolerance(new Search(world, budget), solution);
    }

    /**
     * Why the final arrival proof would refuse this solution, or {@code null} when it would accept it.
     *
     * <p>The diagnostic form of the same support/body/orientation/ray proof used by the solve, for a caller holding a
     * finished plan rather than a search. Same answer, with any diagnostic rays counted against nothing.
     */
    public Rejection refusalFor(PredictedWorld world, PlacementSolution solution, SolveBudget budget) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(budget, "budget");
        Search search = new Search(world, budget);
        return Double.isNaN(this.provenApproachTolerance(search, solution))
                ? this.arrivalRejection(search, solution) : null;
    }

    /**
     * Name a support failure before asking which sight-line failure a solution earned.
     *
     * <p>The narrowest rung is the least floor area the executor may use. If even its eight endpoints cannot be
     * reached from the stance centre without losing the proven feet height, this is an approach-support defect and
     * not an occlusion.
     */
    private Rejection arrivalRejection(Search search, PlacementSolution solution) {
        double floor = slipperyFloorTolerance(search.world, solution.stance());
        double narrowest = Double.NaN;
        for (int i = APPROACH_TOLERANCE_LADDER.length - 1; i >= 0; i--) {
            if (APPROACH_TOLERANCE_LADDER[i] >= floor) {
                narrowest = APPROACH_TOLERANCE_LADDER[i];
                break;
            }
        }
        if (Double.isNaN(narrowest)
                || !this.approachSupportHoldsOverRim(search.world, solution.stance(),
                        solution.approach(), narrowest)) {
            return Rejection.APPROACH_NOT_SUPPORTED;
        }
        if (!this.postPlacementSupportHoldsOverRim(search.world, solution, narrowest)) {
            return Rejection.POST_ACTION_NOT_SUPPORTED;
        }
        return this.occlusionRejection(search, solution);
    }

    /**
     * Which of the two occlusion refusals a solution that survived no rung has earned.
     *
     * <p>The distinction is worth one ray because the two ask a human for different things. A ray that is clean from
     * the exact proven point and dirty over a ball the body can actually hold means the STANCE is right and something
     * is grazing the line — {@link Rejection#OCCLUSION_BELOW_BODY_FLOOR}, and the thing the engine used to do here
     * was emit a 0.02 tolerance and stand in it for 44 000 ticks. A ray that is dirty even at radius zero means the
     * stance is wrong — {@link Rejection#OCCLUSION_NOT_ROBUST}.
     */
    private Rejection occlusionRejection(Search search, PlacementSolution solution) {
        return this.sightLineHoldsAtPoint(search, solution)
                ? Rejection.OCCLUSION_BELOW_BODY_FLOOR
                : Rejection.OCCLUSION_NOT_ROBUST;
    }

    /**
     * The widest arrival tolerance from which this click is still the same click, or {@code NaN} when even
     * {@link FineApproach#TIGHT_TOLERANCE} is too wide.
     */
    private double provenApproachTolerance(Search search, PlacementSolution solution) {
        // The floor is a property of the GROUND, not a constant. A body brakes against friction, and blue ice has
        // 0.989 of it against a normal block's 0.6 -- one per cent of speed shed per tick instead of forty. The fine
        // approach's eight fixed directions cannot stop a drifting body inside a small ball at all: the basalt run
        // spent 61 ticks failing to settle within 0.18 on a blue ice stance at 116,-59,83 and threw a 4239-cell plan
        // away over it. So a slippery stance is not asked for precision it cannot deliver; it is either given a
        // region wide enough that drifting inside it does not matter, or refused.
        //
        // The same lesson as the soul sand foot height, in the other direction: derive the number from the world
        // instead of assuming one the world does not supply.
        double floor = slipperyFloorTolerance(search.world, solution.stance());
        for (double tolerance : APPROACH_TOLERANCE_LADDER) {
            if (tolerance >= floor && this.placementHoldsOverRim(search, solution, tolerance)) {
                return tolerance;
            }
        }
        return Double.NaN;
    }

    /** The narrowest arrival ball worth asking for on this ground. {@link FineApproach#ACHIEVABLE_TOLERANCE} on
     *  anything a body can brake on, and the ladder's widest rung on anything it slides across. */
    private static double slipperyFloorTolerance(PredictedWorld world, BlockPos stance) {
        BlockPos under = stance.below();
        float friction = world.get(under).getBlock().getFriction();
        return friction > NORMAL_FRICTION + 1.0E-4F
                ? APPROACH_TOLERANCE_LADDER[0] : FineApproach.ACHIEVABLE_TOLERANCE;
    }

    /** Vanilla's default block friction. Anything above it is ice of some kind, or slime. */
    static final float NORMAL_FRICTION = 0.6F;

    /**
     * Does the click work from the exact proven point, ignoring the arrival ball entirely?
     *
     * <p>Only ever asked of a candidate that has already failed every rung, and only to name WHY. A yes means the
     * geometry is sound and the arrival slack it would need is under {@link FineApproach#ACHIEVABLE_TOLERANCE} — the
     * body's floor — which is a blocker the engine reports rather than an action it emits.
     *
     * <p>One ray, not eight: at radius zero every rim sample is the same point, and casting the identical ray eight
     * times to say so would be the kind of cost this proof is careful about everywhere else.
     */
    private boolean sightLineHoldsAtPoint(Search search, PlacementSolution solution) {
        Vec3 eye = this.pose.eyeAt(solution.approach());
        if (eye.distanceTo(solution.aimPoint()) > search.budget.maxReach()) {
            return false;
        }
        Rotation rotation = this.quantise.apply(
                RotationUtils.calcRotationFromVec3d(eye, solution.aimPoint(), PLANNING_REFERENCE));
        return this.confirmCrosshair(search, search.world, solution.against(), solution.face(), eye, rotation,
                search.budget) == null;
    }

    /**
     * Does every rim point remain reachable on supported ground and preserve the same legal placement?
     *
     * <p>The ray is re-derived at each rim point exactly as the executor now derives it — the rotation from THAT eye
     * toward the proven aim point, quantised, cast for the full reach. That is V1 and V2 asking the same question
     * from the two ends: the executor's invariant is the aim POINT, so the planner's proof has to be that the point
     * is reachable as a crosshair from anywhere the body may legitimately stand, rather than that one angle works
     * from one place.
     *
     * <p>Reach is re-checked here because a rim point can be up to one tolerance further from the aim than the centre
     * was, and a solution that only fits inside {@link SolveBudget#maxReach()} from the exact proven point is a bet
     * on the arrival going its way.
     *
     * <p>The body, eye, exact feet support, yaw quadrant and six-way axis dominance are all re-checked at the rim.
     * FineApproach may legitimately settle anywhere in this ball, so handing out the tolerance is a contract about
     * every allowed endpoint rather than only the planned centre.
     *
     * <p>MEASURED AND DISCARDED: widening this rim, or adding a face-edge margin to the aim, does NOT fix the
     * {@code WRONG_FACE} stalls. The stall of run {@code 9561f19f} action 74 was diagnosed as a grazed face edge
     * inside the arrival ball; it is not. The body stood 0.0117 blocks from the proven point — inside the ladder's
     * narrowest rung, {@link FineApproach#ACHIEVABLE_TOLERANCE} — and the ray still went to a different face,
     * because the block it was aimed at had a different OUTLINE live than in the plan. Every rim point here casts
     * against the same predicted world and returns the same answer the centre did, so no amount of rim proves
     * anything about it. See {@code ConnectiveOutlineDriftTest}, which reproduces both rays to the millimetre.
     */
    private boolean placementHoldsOverRim(Search search, PlacementSolution solution, double tolerance) {
        Vec3 approach = solution.approach();
        Vec3 aim = solution.aimPoint();
        double reach = search.budget.maxReach();
        if (!this.approachSupportHoldsOverRim(search.world, solution.stance(), approach, tolerance)) {
            return false;
        }
        if (!this.postPlacementSupportHoldsOverRim(search.world, solution, tolerance)) {
            return false;
        }
        // The two independent orientation conventions. A four-way block reads a yaw quadrant; a piston, dispenser or
        // observer reads the dominant axis of the full look vector. Both were proved at the planned point, so both
        // have to remain true over the same arrival ball the body receives.
        Direction yawLook = PlacementGeometry.yawLookDirection(solution.desired());
        Direction axisLook = provenLook(solution.desired());
        for (double[] offset : RIM_UNIT_OFFSETS) {
            Vec3 rim = new Vec3(approach.x + offset[0] * tolerance, approach.y, approach.z + offset[1] * tolerance);
            Vec3 eye = this.pose.eyeAt(rim);
            if (eye.distanceTo(aim) > reach) {
                return false;
            }
            // A1 to A4 over the whole arrival region rather than at its centre. Without this the ball may not widen at
            // all: the approach grid keeps the body centre APPROACH_INSET from the wall and APPROACH_INSET is exactly
            // half the body width, so a rim point in a walled stance is a body inside the wall. This is also the
            // erosion A1 to A3 never had — the planned point was proven and the 8cm around it were not.
            if (this.checkBodyAndEye(search.world, solution.stance(), rim, solution.cell(), solution.desired())
                    != null) {
                return false;
            }
            // The one that makes widening safe instead of reckless. yawMargin is the distance from the quadrant
            // boundary that decides a yaw-derived facing, and until now it was proven at the planned point ONLY: the
            // body was free to stand anywhere in the ball and place the block turned. A wider ball would have made
            // that hole wider, so the ball and the rotation proof widen together or not at all. The hinge rides along:
            // the aim point is fixed in world space, so a stable facing means a stable placer frame and therefore the
            // same half of the same face.
            if (yawLook != null && yawMargin(eye, aim, yawLook) <= 0.0D) {
                return false;
            }
            // fd3eac58: action 43's north-facing sticky piston was safe at the exact planned point (SOUTH beat WEST
            // by 0.36), but the granted 0.30 arrival ball let the body stop 0.276 away, where WEST won by 0.029.
            // The ray still hit the exact intended face, so only the landed FACING changed. yawLook is null for this
            // family because it carries six-way FACING rather than HORIZONTAL_FACING; without this separate check the
            // four-way proof above can never see it. Keep the same quantified dominance reserve aimsFor required at
            // the centre, now at every rim point from which the executor is allowed to click.
            if (axisLook != null && axisDominance(eye, aim, axisLook) <= DOMINANCE_MARGIN) {
                return false;
            }
            Rotation rotation = this.quantise.apply(
                    RotationUtils.calcRotationFromVec3d(eye, aim, PLANNING_REFERENCE));
            if (this.confirmCrosshair(search, search.world, solution.against(), solution.face(), eye, rotation,
                    search.budget) != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Would the body still have the complete granted arrival disk under it after this click's physical consequences?
     *
     * <p>The speculative edit includes the primary and Vanilla's automatic secondary half. Existing stairs are read
     * through {@link #effectiveStairsShape}, so an adjacent stair changing from straight to an outer corner is visible
     * even though {@link PredictedWorld#apply} deliberately stores raw states without neighbour mutation. The scratch
     * restores the exact prior delta, not merely the original snapshot.
     */
    private boolean postPlacementSupportHoldsOverRim(PredictedWorld world, PlacementSolution solution,
                                                     double tolerance) {
        ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(world);
        try {
            scratch.apply(solution.cell(), solution.predicted());
            PlacementConsequences.Secondary secondary =
                    PlacementConsequences.secondaryOf(solution.cell(), solution.predicted());
            if (secondary != null) {
                if (!world.inBounds(secondary.cell().getX(), secondary.cell().getY(), secondary.cell().getZ())) {
                    return false;
                }
                scratch.apply(secondary.cell(), secondary.state());
            }
            return arrivalDiskSupported(world, this.pose, solution.approach(), tolerance);
        } finally {
            scratch.restore();
        }
    }

    /**
     * Does the complete arbitrary-entry/fine-approach envelope remain supported?
     *
     * <p>Kept separate from the ray/orientation loop so a failed proof can be named
     * {@link Rejection#APPROACH_NOT_SUPPORTED} rather than disguised as an occlusion.
     */
    private boolean approachSupportHoldsOverRim(PredictedWorld world, BlockPos stance, Vec3 approach,
                                                double tolerance) {
        return approachEnvelopeSupported(world, this.pose, stance, approach, tolerance);
    }

    /**
     * Margin descending, then a total order over everything else.
     *
     * <p>The tail of this comparator is not decoration. Two solutions with the same margin are equally good and the
     * planner has to pick one; if that pick depends on the order the candidates happened to be enumerated in, the same
     * schematic produces two different plans and the whole "same input, same plan, byte for byte" property is gone.
     * Reach comes first among the tiebreaks because a shorter click is the one that survives latency.
     */
    private Comparator<PlacementSolution> ranking() {
        return Comparator.comparingDouble((PlacementSolution solution) -> -solution.margin())
                .thenComparingDouble(solution -> solution.reach(this.pose))
                .thenComparingLong(solution -> PlacementGeometry.positionKey(solution.stance()))
                .thenComparingInt(solution -> solution.face().ordinal())
                // No slot tiebreak: one solve resolves ONE stack, so every candidate in this list carries the same
                // item, and the hotbar slot does not exist yet in any case. Stance, face and aim point already give a
                // total order over what a single solve can produce.
                .thenComparingDouble(solution -> solution.aimPoint().x)
                .thenComparingDouble(solution -> solution.aimPoint().y)
                .thenComparingDouble(solution -> solution.aimPoint().z);
    }
}
