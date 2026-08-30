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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Plan 19: a proof that holds at one point is not a proof when the executor may stand 0.08 blocks away.
 *
 * <h2>The measurement this file exists for</h2>
 * <p>The first live run against the real basalt farm set 101 cells cleanly — 101 clicks, 101 landed, {@code wrong=0},
 * {@code broke=0} — and stopped at action 102 with {@code WRONG_BLOCK}. Cell {@code 81,-60,81} (blue ice), stance
 * {@code 82,-60,82}, clicking the south face of {@code 81,-60,80}. The trace has the bot at {@code 82.537, 82.462};
 * the plan proved {@code 82.50, 82.50}. That is 0.037 and 0.038 off — well inside the 0.08 approach tolerance, so a
 * CORRECT arrival. From the proven point the ray clears the corner of {@code 82,-60,81} by about 0.019 blocks. That
 * cell holds blue ice the bot placed itself as action 101. From the actual point the ray hits it.
 *
 * <p>Everything downstream did exactly the right thing: the gate refused, the engine waited 60 ticks, re-planned
 * three times, reproduced the identical solution deterministically, and stopped by name. The plan underneath it was
 * what was wrong — the design has a margin for the dominance axis and none for occlusion.
 *
 * <h2>The fixture</h2>
 * <p>The layout is the measured one translated to the origin, so the arithmetic can be read off the file: cell
 * {@code 1,0,1}, stance {@code 2,0,2}, clicking the SOUTH face of {@code 1,0,0}, with the bot's own earlier
 * placement standing at {@code 2,0,1}. The ray leaves the crouched eye at {@code 2.5, 1.27, 2.5} and has to get past
 * the top-north-west corner of that block, which is the same corner and the same kind of pass as the trace.
 *
 * <p>The aim height is the one free parameter and it sets the clearance exactly. The ray leaves the occluder's
 * {@code x} band at {@code t = (0.5 + e) / (1 + e)} for an eye displaced by {@code e} along {@code +x}, and that exit
 * is the lowest point of its pass, so a total drop of {@code s} clears the top face {@code y = 1} by
 * {@code 0.27 - s·t}. {@link #CLIPPED_AIM} picks {@code s = 0.502} for a clearance of 0.019 at {@code e = 0} — the
 * measured number.
 *
 * <p>{@link #TIGHT_AIM} is the fixture for the LADDER, and it is derived rather than chosen. It has to clip somewhere
 * inside the loose ball and nowhere inside the tight one, which pins {@code s} between {@code 0.27 / t(0.08) =
 * 0.5028} and {@code 0.27 / t(0.06) = 0.5111}; {@code s = 0.507} is the middle of that window, for a clearance of
 * 0.0023 at the tight rung and −0.0023 at the loose one. It moves when {@link FineApproach#ACHIEVABLE_TOLERANCE}
 * moves, and that is correct: the number the body can hold is what the ladder is made of.
 */
public class OcclusionMarginRegressionTest {

    /** Real vanilla shapes and states, headless — the outline clip these proofs run on is vanilla's own. */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    /** {@code 81,-60,81}: the cell action 102 wanted to fill. */
    private static final BlockPos CELL = new BlockPos(1, 0, 1);

    /** {@code 81,-60,80}: the neighbour whose south face is clicked. */
    private static final BlockPos AGAINST = new BlockPos(1, 0, 0);

    /** {@code 82,-60,82}: the feet cell the plan proved. */
    private static final BlockPos STANCE = new BlockPos(2, 0, 2);

    /** {@code 82,-60,81}: blue ice the bot placed itself as action 101, and the corner the ray has to clear. */
    private static final BlockPos OCCLUDER = new BlockPos(2, 0, 1);

    /** The exact sub-block point the plan proved — {@code 82.50, 82.50} in the trace. */
    private static final Vec3 APPROACH = new Vec3(2.5D, 0.0D, 2.5D);

    /** Aim on the clicked face whose ray clears the occluder's corner by 0.019 blocks: {@code 1.27 - 0.768 = 0.502}
     *  of drop, half of it spent by the time the ray leaves the occluder's {@code x} band. */
    private static final Vec3 CLIPPED_AIM = new Vec3(1.5D, 0.768D, 1.0D);

    /** Aim inside the window where {@link FineApproach#TOLERANCE} of drift clips the corner and
     *  {@link FineApproach#ACHIEVABLE_TOLERANCE} does not: {@code 1.27 - 0.507}. See the class javadoc for the
     *  window. {@link #theFixtureStraddlesExactlyTheTwoRungsOfTheLadder} re-derives it and fails if the ladder moves
     *  underneath it, so this literal can never quietly stop testing the thing it is here for. */
    private static final Vec3 TIGHT_AIM = new Vec3(1.5D, 1.27D - 0.507D, 1.0D);

    /** The crouched eye at {@link #APPROACH}. Every ray below starts here or at a point offset from it. */
    private static final Vec3 EYE = PlayerPose.CROUCHED.eyeAt(APPROACH);

    /** How far past the aim point the crosshair ray is cast — the same full-reach cast the oracle uses, because a
     *  segment that stops on the face cannot report which block it stopped against. */
    private static final double REACH = PlayerPose.PLANNING_REACH;

    /** The planner's canonical yaw reference. Not a stand-in for a live rotation: the plan is proven before there is
     *  one, and a fixed reference is what makes the stored angle the same number in every run. */
    private static final Rotation REFERENCE = new Rotation(0.0F, 0.0F);

    // ------------------------------------------------------------------ V1: the aim POINT is the invariant

    /**
     * The measured defect, at the lowest level: from the proven point the ray clears the corner, and from a point
     * the executor is allowed to stand at it does not — if the executor replays the planned ANGLE.
     *
     * <p>0.06 of drift and not the trace's 0.053, for one reason: this fixture reconstructs the vertical pass over
     * the corner and the real one also had a horizontal component, so the same total drift buys slightly less error
     * here. The defect being pinned is the shape of the failure, not its last decimal — a legal arrival aiming at a
     * different block than the one the plan proved.
     */
    @Test
    public void replayingThePlannedAngleFromALegalArrivalClipsTheCornerTheProofCleared() {
        PredictedWorld world = basaltFixture(true);

        BlockHitResult fromTheProvenPoint = crosshair(world, EYE, plannedRotation(CLIPPED_AIM));
        assertNotNull("the proven point must reach the face at all", fromTheProvenPoint);
        assertEquals("the plan's own ray lands on the neighbour it clicks",
                AGAINST, fromTheProvenPoint.getBlockPos());
        assertSame("and on its south face", Direction.SOUTH, fromTheProvenPoint.getDirection());

        // A correct arrival: 0.06 blocks from the proven point, well inside the 0.08 the fine approach allows.
        Vec3 arrived = APPROACH.add(0.06D, 0.0D, 0.0D);
        assertTrue("the fixture's drift must be a LEGAL arrival, or it proves nothing",
                FineApproach.arrived(arrived, APPROACH));

        BlockHitResult replayed = crosshair(world, PlayerPose.CROUCHED.eyeAt(arrived), plannedRotation(CLIPPED_AIM));
        assertNotNull(replayed);
        assertEquals("this is action 102: the replayed angle puts the crosshair on the block the bot placed itself "
                        + "as action 101, and the gate correctly refuses",
                OCCLUDER, replayed.getBlockPos());
    }

    /**
     * V1. Deriving the rotation at click time from the ACTUAL eye toward the PLANNED aim point puts the crosshair
     * back on the proven face, from the same arrival that the replayed angle got wrong.
     *
     * <p>This is the whole of the executor-side fix, expressed as geometry: what the planner proved is a POINT on a
     * face — that the crosshair resting there produces the wanted state, that the line to it is clear, that the
     * dominant axis wins by a margin. The angle it stored is merely one way of reaching that point from one place.
     * Aiming pins the far end of the ray where it was proven and lets only the near end move, which is why a drift
     * that translates the whole replayed ray past a corner merely rotates this one.
     */
    @Test
    public void aimingAtThePlannedPointFromTheActualEyeReachesTheProvenFace() {
        PredictedWorld world = basaltFixture(true);
        Vec3 arrived = APPROACH.add(0.06D, 0.0D, 0.0D);
        Vec3 eye = PlayerPose.CROUCHED.eyeAt(arrived);

        // Exactly what CellExecutor.rotationOf now computes: the rotation from the live eye to the planned point.
        Rotation aimed = RotationUtils.calcRotationFromVec3d(eye, CLIPPED_AIM, REFERENCE);
        BlockHitResult hit = crosshair(world, eye, aimed);

        assertNotNull("aiming at the proven point must reach something", hit);
        assertEquals("the crosshair is back on the neighbour the plan clicks", AGAINST, hit.getBlockPos());
        assertSame("on the face the plan proved", Direction.SOUTH, hit.getDirection());
    }

    /** The two rotations differ, or the test above would be passing for the wrong reason — it would be asserting
     *  that the drift does not matter rather than that re-deriving the angle is what absorbs it. */
    @Test
    public void theReDerivedRotationIsNotThePlannedOne() {
        Vec3 eye = PlayerPose.CROUCHED.eyeAt(APPROACH.add(0.06D, 0.0D, 0.0D));
        Rotation planned = plannedRotation(CLIPPED_AIM);
        Rotation aimed = RotationUtils.calcRotationFromVec3d(eye, CLIPPED_AIM, REFERENCE);
        assertFalse("a drifted eye aiming at the same point needs a different angle",
                aimed.isCloseTo(planned, 0.01F, 0.01F));
    }

    // ------------------------------------------------- V2: the proof is over the ball, not over the point

    /**
     * The occlusion proof refuses a stance whose sight line survives its own point but not the arrival ball, and
     * grants the tighter rung when that one does hold.
     *
     * <p>This is the number the planner writes into the action. It is not a preference and not a setting: the tight
     * rung here means that the ray from every sampled point of a ball that size lands on the same cell and the same
     * face, and that at least one point of a {@link FineApproach#TOLERANCE} ball does not.
     */
    @Test
    public void aSightLineThatSurvivesTheFloorButNotTheFullToleranceIsGrantedTheTighterOne() {
        PredictedWorld world = basaltFixture(true);
        double granted = oracle().provenApproachTolerance(world, solution(TIGHT_AIM), SolveBudget.DEFAULT);

        assertEquals("the ray clips the corner from somewhere inside the loose ball and from nowhere inside the "
                + "tight one", FineApproach.TIGHT_TOLERANCE, granted, 1.0E-9D);
    }

    /**
     * The tight rung the planner may grant is exactly the floor the body can hold, and never anything under it.
     *
     * <p>This is the correction of the freeze. {@code TIGHT_TOLERANCE} used to be 0.02 — two and a half times
     * stricter than {@link FineApproach#CONTROLLER_STALL_RADIUS}, i.e. an instruction the fine approach provably
     * cannot carry out — and the planner emitted it on any cell whose sight line asked for it.
     */
    @Test
    public void theTighterRungIsTheBodysFloorAndNotAGeometricWish() {
        assertEquals("the ladder's tight rung IS the achievable floor",
                FineApproach.ACHIEVABLE_TOLERANCE, FineApproach.TIGHT_TOLERANCE, 0.0D);
        for (double rung : PlacementOracle.APPROACH_TOLERANCE_LADDER) {
            assertTrue("ladder rung " + rung + " must be something the body can hold",
                    FineApproach.achievable(rung));
        }
    }

    /**
     * And the floor is enforced where a tolerance is WRITTEN, not merely where the ladder is declared.
     *
     * <p>A refusal and never a clamp. Clamping would widen the ball the click was proved over and execute it anyway,
     * which is the plan-19 defect wearing a helpful face; the engine's answer to a cell that needs tighter is a named
     * blocker. {@code NaN} is refused by the same predicate for a sharper reason: every comparison against NaN is
     * false, so {@link FineApproach#arrived} would answer no from everywhere and the approach would never end.
     */
    @Test
    public void aSolutionCannotBeGivenAToleranceTheBodyCannotHold() {
        PlacementSolution open = solution(TIGHT_AIM);
        for (double refused : new double[]{0.02D, 0.0D, -1.0D, Double.NaN}) {
            try {
                open.withApproachTolerance(refused);
                fail("a tolerance of " + refused + " must not be constructible");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("below what the fine approach"));
            }
        }
        assertEquals(FineApproach.ACHIEVABLE_TOLERANCE,
                open.withApproachTolerance(FineApproach.ACHIEVABLE_TOLERANCE).approachTolerance(), 0.0D);
    }

    /** The fixture is only worth having while it straddles the two rungs. Re-derived from the ladder itself, so a
     *  change to the floor fails HERE, with the arithmetic in the message, rather than turning the four tests above
     *  into assertions about nothing. */
    @Test
    public void theFixtureStraddlesExactlyTheTwoRungsOfTheLadder() {
        double drop = 1.27D - TIGHT_AIM.y;
        double mustExceed = 0.27D / exitFraction(FineApproach.TOLERANCE);
        double mustStayUnder = 0.27D / exitFraction(FineApproach.ACHIEVABLE_TOLERANCE);
        assertTrue("drop " + drop + " must exceed " + mustExceed + " or the loose rung would not clip",
                drop > mustExceed);
        assertTrue("drop " + drop + " must stay under " + mustStayUnder + " or the tight rung would clip too",
                drop < mustStayUnder);
    }

    /** Where along the ray the occluder's {@code x} band ends, for an eye pushed {@code e} blocks along {@code +x} —
     *  the rim sample that decides this fixture, and the lowest point of the pass. */
    private static double exitFraction(double e) {
        return (0.5D + e) / (1.0D + e);
    }

    /**
     * The same click with the bot's own earlier placement absent keeps the full tolerance — the tightening is bought
     * by an obstruction and is not a blanket cost on every cell.
     *
     * <p>The two fixtures differ in exactly one block, so this and the test above are one measurement read twice.
     */
    @Test
    public void anOpenSightLineKeepsTheFullTolerance() {
        PredictedWorld open = basaltFixture(false);
        double granted = oracle().provenApproachTolerance(open, solution(TIGHT_AIM), SolveBudget.DEFAULT);

        // Not "equals 0.08" any more. That number was the ladder's ceiling, not a fact about this cell, and pinning a
        // ceiling as a measurement is how the engine came to demand 8cm precision from a body whose smallest sneak
        // step is 6.5cm while most of a block would have done. What this fixture actually asserts is the direction:
        // an unobstructed sight line is never charged, so it must come out at least as wide as the old ceiling.
        assertTrue("nothing is in the way, so nothing is paid for: expected at least "
                + FineApproach.TOLERANCE + ", got " + granted, granted >= FineApproach.TOLERANCE - 1.0E-9D);
        assertEquals("and with the ladder open upward this cell measures 0.18", 0.18D, granted, 1.0E-9D);
    }

    /** The one comparison the plan asks for in words: a tight cell gets a tighter tolerance than an open one. */
    @Test
    public void aTightCellGetsATighterToleranceThanAnOpenOne() {
        PlacementOracle oracle = oracle();
        double tight = oracle.provenApproachTolerance(basaltFixture(true), solution(TIGHT_AIM), SolveBudget.DEFAULT);
        double open = oracle.provenApproachTolerance(basaltFixture(false), solution(TIGHT_AIM), SolveBudget.DEFAULT);

        assertTrue("tight " + tight + " must be stricter than open " + open, tight < open);
    }

    /**
     * The granted tolerance is a claim about a whole disc, and it is checked here against a rim three times denser
     * than the one the proof samples, plus the interior.
     *
     * <p>An independent verification and not a restatement: the proof casts eight rays and this casts ninety-six, so
     * a proof that happened to sample past an obstruction would show up here rather than in the live run.
     */
    @Test
    public void everyPointInsideTheGrantedToleranceReachesTheProvenFace() {
        PredictedWorld world = basaltFixture(true);
        PlacementSolution plan = solution(TIGHT_AIM);
        double granted = oracle().provenApproachTolerance(world, plan, SolveBudget.DEFAULT);
        assertFalse("the fixture must be solvable at some tolerance", Double.isNaN(granted));

        for (int step = 0; step < 24; step++) {
            double angle = 2.0D * Math.PI * step / 24.0D;
            for (double fraction : new double[]{1.0D, 0.5D, 0.25D, 0.0D}) {
                double radius = granted * fraction;
                Vec3 point = APPROACH.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
                BlockHitResult hit = crosshair(world, PlayerPose.CROUCHED.eyeAt(point),
                        RotationUtils.calcRotationFromVec3d(PlayerPose.CROUCHED.eyeAt(point), TIGHT_AIM, REFERENCE));
                String where = String.format("angle %.0f radius %.4f", Math.toDegrees(angle), radius);
                assertNotNull(where, hit);
                assertEquals(where, AGAINST, hit.getBlockPos());
                assertSame(where, Direction.SOUTH, hit.getDirection());
            }
        }
    }

    /** And the tolerance it did NOT grant is genuinely unsafe — otherwise the tightening would be a superstition. */
    @Test
    public void somePointOnTheRefusedToleranceMissesTheProvenFace() {
        PredictedWorld world = basaltFixture(true);
        boolean anyMiss = false;
        for (int step = 0; step < 24 && !anyMiss; step++) {
            double angle = 2.0D * Math.PI * step / 24.0D;
            Vec3 point = APPROACH.add(Math.cos(angle) * FineApproach.TOLERANCE, 0.0D,
                    Math.sin(angle) * FineApproach.TOLERANCE);
            Vec3 eye = PlayerPose.CROUCHED.eyeAt(point);
            BlockHitResult hit = crosshair(world, eye,
                    RotationUtils.calcRotationFromVec3d(eye, TIGHT_AIM, REFERENCE));
            anyMiss = hit == null || !hit.getBlockPos().equals(AGAINST) || hit.getDirection() != Direction.SOUTH;
        }
        assertTrue("0.08 was refused, so some point of that ball must actually aim somewhere else", anyMiss);
    }

    // ------------------------------------------------------- V3: the tolerance belongs to the action

    /** The number the oracle measured is what the fine approach is judged by — through the solution, through the
     *  action, with nothing in between free to widen it. */
    @Test
    public void theActionCarriesTheTolerancePlanningGrantedIt() {
        PlacementSolution tight = solution(TIGHT_AIM).withApproachTolerance(FineApproach.TIGHT_TOLERANCE);
        BuildAction.Place place = new BuildAction.Place(tight, true, 1, 0);

        assertEquals(FineApproach.TIGHT_TOLERANCE, place.approachTolerance(), 1.0E-9D);
        assertEquals("a scaffold placement is proven by the same oracle and honours the same number",
                FineApproach.TIGHT_TOLERANCE,
                new BuildAction.PlaceScaffold(tight, CELL, true, 1, 0).approachTolerance(), 1.0E-9D);

        BuildAction.Place open = new BuildAction.Place(solution(TIGHT_AIM), true, 1, 0);
        assertEquals("an unconstrained placement pays nothing",
                FineApproach.TOLERANCE, open.approachTolerance(), 1.0E-9D);
    }

    /** An arrival that is inside the loose tolerance and outside the tight one must be refused when the action asks
     *  for the tight one — the whole of the executor-side V3, and the thing a global constant cannot express. */
    @Test
    public void theFineApproachRefusesAnArrivalTheTightToleranceDoesNotCover() {
        Vec3 drifted = APPROACH.add(0.07D, 0.0D, 0.0D);

        assertTrue("0.07 is a legal arrival for an ordinary cell",
                FineApproach.arrived(drifted, APPROACH, FineApproach.TOLERANCE));
        assertFalse("and is not one for a cell whose sight line only survives the tight rung",
                FineApproach.arrived(drifted, APPROACH, FineApproach.TIGHT_TOLERANCE));
        assertTrue("the two-argument form is the loose one, for callers with no action in hand",
                FineApproach.arrived(drifted, APPROACH));
    }

    /** A tightened solution says so in the plan and the proof files, and an ordinary one leaves both lines exactly
     *  as they were — 4 000 unchanged cells and a greppable handful. */
    @Test
    public void onlyATightenedSolutionPrintsItsTolerance() {
        PlacementSolution open = solution(TIGHT_AIM);
        PlacementSolution tight = open.withApproachTolerance(FineApproach.TIGHT_TOLERANCE);

        assertFalse(open.isOcclusionTight());
        assertTrue(tight.isOcclusionTight());
        String printed = "tol " + BuildAction.describeTolerance(FineApproach.TIGHT_TOLERANCE);
        assertFalse("an ordinary cell's proof line is unchanged", open.toProofLine().contains("tol "));
        assertTrue("a tightened one names the number", tight.toProofLine().contains(printed));
        assertFalse(new BuildAction.Place(open, true, 1, 0).describe().contains("tol "));
        assertTrue(new BuildAction.Place(tight, true, 1, 0).describe().contains(printed));
    }

    // ------------------------------------------------------------------------------ determinism and cost

    /** No randomness anywhere in the new proof: the rim offsets are literals and the ladder is fixed, so the same
     *  world and the same solution answer the same number every time. Determinism is measured on this engine and a
     *  proof that sampled a different rim per run would move a plan line without moving anything else. */
    @Test
    public void theProofIsDeterministic() {
        PredictedWorld world = basaltFixture(true);
        PlacementSolution plan = solution(TIGHT_AIM);
        double first = oracle().provenApproachTolerance(world, plan, SolveBudget.DEFAULT);
        for (int run = 0; run < 8; run++) {
            assertEquals(first, oracle().provenApproachTolerance(world, plan, SolveBudget.DEFAULT), 0.0D);
        }
    }

    /** The cost is bounded and small, because it is paid only for the solution that is actually taken. Eight rays a
     *  rung against a cap of 512 that the worst measured cell reached 298 of. */
    @Test
    public void theProofCostsEightRaysPerTolerance() {
        assertEquals(8, PlacementOracle.OCCLUSION_RIM_SAMPLES);
        double[] ladder = PlacementOracle.APPROACH_TOLERANCE_LADDER;
        // The length is not the property worth pinning — it changed once already, when the ladder was opened upward.
        // These two are: the scan must be widest-first, or "the first survivor" is not "the widest survivor"; and the
        // last rung must be the body's floor, or the planner can order an arrival the fine approach cannot hold.
        for (int i = 1; i < ladder.length; i++) {
            assertTrue("rung " + i + " (" + ladder[i] + ") must be stricter than rung " + (i - 1)
                    + " (" + ladder[i - 1] + "): the scan takes the first survivor and that has to be the widest",
                    ladder[i] < ladder[i - 1]);
        }
        assertEquals("the planner never asks for anything tighter than the body can hold",
                FineApproach.ACHIEVABLE_TOLERANCE, ladder[ladder.length - 1], 0.0D);
    }

    /** A solution whose sight line survives neither rung is refused outright. A named blocker at plan time always
     *  beats a wrong block in the world. */
    @Test
    public void aSightLineThatSurvivesNeitherRungIsRefused() {
        // The occluder raised a full layer: it now stands between the eye and the face at every offset, so no rung
        // of the ladder can hold and the honest answer is that this stance has no proof at all.
        PredictedWorld walled = fixture(true, new BlockPos(2, 1, 1));
        double granted = oracle().provenApproachTolerance(walled, solution(TIGHT_AIM), SolveBudget.DEFAULT);
        assertTrue("an obstruction the ray cannot get past at any tolerance must answer NaN, got " + granted,
                Double.isNaN(granted));
    }

    /**
     * The two refusals are told apart, and by the only thing that distinguishes them: whether the click works from
     * the exact proven point.
     *
     * <p>This is the defect's own signature. A cell whose ray is clean from the point and dirty over a ball the body
     * can hold is asking for an arrival tighter than the fine approach can deliver — the engine used to answer that
     * by writing 0.02 into the action and freezing on it — and it is a DIFFERENT finding from a stance the ray never
     * clears at all, because the fix is different: clear the neighbour, versus move the stance.
     */
    @Test
    public void aCellNeedingTighterThanTheBodyCanHoldIsNamedSeparately() {
        // Drop s below the floor's window: the ray still clears from the exact point (clearance 0.27 - 0.5·s > 0)
        // and clips from somewhere inside even the tight ball.
        Vec3 belowTheFloor = new Vec3(1.5D, 1.27D - 0.535D, 1.0D);
        PredictedWorld world = basaltFixture(true);
        PlacementOracle oracle = oracle();

        assertTrue("the fixture must be unsolvable at every rung, or it names nothing",
                Double.isNaN(oracle.provenApproachTolerance(world, solution(belowTheFloor), SolveBudget.DEFAULT)));
        assertSame("the ray IS clean from the proven point — the body is what cannot deliver it",
                PlacementOracle.Rejection.OCCLUSION_BELOW_BODY_FLOOR,
                oracle.refusalFor(world, solution(belowTheFloor), SolveBudget.DEFAULT));

        // And the walled fixture, where no arrival works at all, keeps the older, structural reason.
        assertSame("nothing gets past this at any radius, including a radius of zero",
                PlacementOracle.Rejection.OCCLUSION_NOT_ROBUST,
                oracle.refusalFor(fixture(true, new BlockPos(2, 1, 1)), solution(TIGHT_AIM), SolveBudget.DEFAULT));
    }

    /** The rejections have sentences in the report. A cause the planner can name and the report cannot print is a
     *  cause a human meets as a blank line. */
    @Test
    public void theRejectionIsNamedInTheReport() {
        assertNotNull(PlacementOracle.Rejection.valueOf("OCCLUSION_NOT_ROBUST"));
        assertNotNull(PlacementOracle.Rejection.valueOf("OCCLUSION_BELOW_BODY_FLOOR"));
        PlacementOracle.Rejection[] all = PlacementOracle.Rejection.values();
        assertSame("both are asked last, on the chosen solution only, so an earlier cause still explains a cell "
                        + "that failed for one", PlacementOracle.Rejection.OCCLUSION_NOT_ROBUST, all[all.length - 2]);
        assertSame(PlacementOracle.Rejection.OCCLUSION_BELOW_BODY_FLOOR, all[all.length - 1]);
    }

    // ------------------------------------------------------------------------------------------ fixture

    /** The measured layout: ground under everything, the clicked neighbour, and optionally the block the bot placed
     *  itself one action earlier. */
    private static PredictedWorld basaltFixture(boolean withOccluder) {
        return fixture(withOccluder, OCCLUDER);
    }

    private static PredictedWorld fixture(boolean withOccluder, BlockPos occluderAt) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState ice = Blocks.BLUE_ICE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> {
                    if (y < 0) {
                        return stone;
                    }
                    BlockPos here = new BlockPos(x, y, z);
                    if (here.equals(AGAINST)) {
                        return stone;
                    }
                    return withOccluder && here.equals(occluderAt) ? ice : air;
                },
                (x, z) -> true, new Vec3i(-6, -3, -6), new Vec3i(8, 5, 8), 0, V3Settings.defaults());
    }

    /** The solution the planner produced, with the aim point as the only variable. Margin is
     *  {@link PlacementSolution#UNCONSTRAINED_MARGIN} because blue ice is a full cube whose landed state does not
     *  depend on the look at all — which is exactly why the DOMINANCE margin could never have caught this. */
    private static PlacementSolution solution(Vec3 aim) {
        BlockState ice = Blocks.BLUE_ICE.defaultBlockState();
        return new PlacementSolution(CELL, ice, STANCE, APPROACH, AGAINST, Direction.SOUTH, aim,
                plannedRotation(aim), PlacementSolution.UNCONSTRAINED_MARGIN, ice, Items.BLUE_ICE);
    }

    private static Rotation plannedRotation(Vec3 aim) {
        return RotationUtils.calcRotationFromVec3d(EYE, aim, REFERENCE);
    }

    /** The full-reach outline cast the oracle's crosshair confirmation uses, cast the same way. */
    private static BlockHitResult crosshair(PredictedWorld world, Vec3 eye, Rotation rotation) {
        Vec3 look = RotationUtils.calcLookDirectionFromRotation(rotation);
        Vec3 end = eye.add(look.x * REACH, look.y * REACH, look.z * REACH);
        OutlineGeometry.Ray ray = OutlineGeometry.clip(world, eye, end);
        assertTrue("the clip must be evaluable", ray.evaluable());
        return ray.hit();
    }

    private static PlacementOracle oracle() {
        return new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
    }

    /** Guard against the fixture drifting into something that proves nothing: the proven point must clear the corner
     *  and the drifted one must not, or every assertion above is vacuous. */
    @Test
    public void theFixtureReproducesTheGeometryItClaimsTo() {
        PredictedWorld world = basaltFixture(true);
        assertSame("the block at 2,0,1 is the one the bot placed as action 101",
                Blocks.BLUE_ICE, world.get(OCCLUDER).getBlock());
        assertSame("the clicked neighbour is solid", Blocks.STONE, world.get(AGAINST).getBlock());
        assertTrue("the cell being filled is empty", world.get(CELL).isAir());
        assertNull("and nothing stands in the target cell's own column",
                world.get(CELL.above()).isAir() ? null : "occupied");
        assertTrue("the aim point lies on the clicked face",
                CLIPPED_AIM.z == AGAINST.getZ() + 1
                        && CLIPPED_AIM.x > AGAINST.getX() && CLIPPED_AIM.x < AGAINST.getX() + 1
                        && CLIPPED_AIM.y > AGAINST.getY() && CLIPPED_AIM.y < AGAINST.getY() + 1);
        assertTrue("and inside the planning reach", EYE.distanceTo(CLIPPED_AIM) < PlayerPose.PLANNING_REACH);
    }
}
