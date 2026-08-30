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
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.pathing.movement.ActionCosts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * How close can this body actually be asked to stand?
 *
 * <h2>The measurement this file exists for</h2>
 * <p>The etz-basalt run deadlocked at 7%. 306 cells placed cleanly, then the fine approach froze at
 * {@code 92.476,-60,93.531} for 44 000 ticks: velocity zero, no sneak, state APPROACH. The action carried
 * {@code TIGHT_TOLERANCE = 0.02}, and 0.02 was a number chosen against geometry rather than against the controller
 * that has to reach it. {@link FineApproach#chooseKeys} does not steer — it SELECTS one of the eight key combinations
 * {@link princeps.pathing.movement.MovementOption#getOptions} offers, each moving the body a fixed distance along a
 * fixed direction. Inside 0.02 every one of the eight overshoots, none improves, so none is emitted and the bot
 * stands there for ever.
 *
 * <p>So the tolerance floor is not a preference. It is a property of the movement system, it is measurable, and this
 * file measures it against the real {@link FineApproach} rather than against a second copy of its arithmetic — which
 * is why {@link FineApproach#stepVector} exists. Every number below is produced by the code under test.
 *
 * <h2>What the answer is</h2>
 * <ul>
 *   <li>a cardinal sneak step is {@code 1.3 / 20 = 0.0650} blocks, and a DIAGONAL one is {@code sqrt(2)} times that
 *       — 0.0919 — because vanilla normalises the input vector only above unit length and a sneaked diagonal is
 *       {@code (0.3, 0.3)};</li>
 *   <li>the worst angular error between the direction wanted and the direction chosen is 36.87 degrees, not the 22.5
 *       an eight-way quantiser suggests, because the selection ranks by Manhattan distance against a unit vector
 *       while the table's diagonals have length {@code sqrt(2)};</li>
 *   <li>a step of length {@code L} at angle {@code θ} improves only while {@code d > L / (2·cos θ)}, and the supremum
 *       of that over every yaw and every wanted direction is <b>0.0487</b> blocks — from a diagonal step at 19.45
 *       degrees, not from the worst angle;</li>
 *   <li>{@link FineApproach#ACHIEVABLE_TOLERANCE} is 0.06, that supremum plus the coast sneak friction adds between
 *       the last key press and {@link FineApproach#settled}.</li>
 * </ul>
 */
public class ApproachFloorTest {

    /** {@link FineApproach#stepVector} calls {@code Mth.sin}, whose lookup table is built during bootstrap. */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Yaws swept. One tenth of a degree over the full circle: the option boundaries are 45 degrees apart, so this
     *  samples each of them 450 times. */
    private static final int YAW_SAMPLES = 3600;

    /** Wanted directions swept per yaw. */
    private static final int DIRECTION_SAMPLES = 720;

    /** Yaws the closed-loop simulations are run at. The last one is the trace's own accumulated value. */
    private static final float[] YAWS = {0.0F, 17.0F, 45.0F, 88.5F, 133.0F, 199.0F, 271.25F, -10836.0F};

    /** Starting distances, in blocks. {@code GoalBlock} is satisfied anywhere in the cell, so the fine approach can
     *  legitimately be handed a body up to about 0.7 blocks out; these are the ordinary cases. */
    private static final double[] OFFSETS = {0.30D, 0.21D, 0.13D, 0.07D};

    // --------------------------------------------------------------------------- the step the body actually takes

    /** The sneak speed the floor is built on is the pathfinder's own, not a second opinion. */
    @Test
    public void theSneakStepIsThePathfindersOwnNumber() {
        assertEquals("SNEAK_ONE_BLOCK_COST is ticks per block; the step is its reciprocal",
                1.0D / ActionCosts.SNEAK_ONE_BLOCK_COST, FineApproach.SNEAK_STEP, 1.0E-12D);
        assertEquals(0.065D, FineApproach.SNEAK_STEP, 1.0E-12D);
    }

    /**
     * A sneaked diagonal covers {@code sqrt(2)} times a sneaked cardinal, and that is not a rounding detail — it is
     * the sample that sets the floor.
     *
     * <p>{@code PlayerMovementInput} scales both impulses by 0.3 while sneaking, so a diagonal input vector is
     * {@code (0.3, 0.3)} of length 0.424, and vanilla's {@code getInputVector} normalises only above 1.0. Walking is
     * the opposite case — {@code (1, 1)} is length 1.414 and IS normalised — which is exactly why this is easy to
     * assume away.
     */
    @Test
    public void aSneakedDiagonalIsTheLongestStepTheControllerCanTake() {
        double longestSeen = 0.0D;
        for (int y = 0; y < YAW_SAMPLES; y++) {
            float yaw = y * 360.0F / YAW_SAMPLES;
            for (int d = 0; d < DIRECTION_SAMPLES; d++) {
                Vec3 step = FineApproach.stepVector(yaw, keysToward(yaw, d));
                longestSeen = Math.max(longestSeen, step.length());
                assertTrue("every step is one of exactly two lengths",
                        close(step.length(), FineApproach.SNEAK_STEP) || close(step.length(),
                                FineApproach.LONGEST_STEP));
            }
        }
        assertEquals(FineApproach.SNEAK_STEP * Math.sqrt(2.0D), FineApproach.LONGEST_STEP, 1.0E-12D);
        assertEquals("the sweep must actually reach a diagonal, or it proves nothing",
                FineApproach.LONGEST_STEP, longestSeen, 1.0E-6D);
    }

    // ------------------------------------------------------------------------------------- the quantisation

    /**
     * The controller's direction error is up to 36.87 degrees, not 22.5.
     *
     * <p>An eight-way quantiser that picked the NEAREST direction would never be worse than half of 45. This one does
     * not pick the nearest: {@code MovementOption.distanceToSq} is Manhattan distance and it compares a unit wanted
     * vector against table entries whose diagonals have length {@code sqrt(2)}, so the diagonals are systematically
     * under-selected and a cardinal key is held well past the point where a diagonal would be better. 36.87 degrees is
     * {@code arctan(3/4)}, which is where the Manhattan tie between a cardinal and its neighbouring diagonal falls.
     *
     * <p>Not a bug being pinned — V2 selects with the same comparator and matching it deliberately is documented on
     * {@link FineApproach#chooseKeys}. It is an input to the floor, and it is 64% larger than the number anyone would
     * assume.
     */
    @Test
    public void theWorstDirectionErrorIsThirtySevenDegreesAndNotTwentyTwoAndAHalf() {
        double worst = 0.0D;
        for (int y = 0; y < YAW_SAMPLES; y++) {
            float yaw = y * 360.0F / YAW_SAMPLES;
            for (int d = 0; d < DIRECTION_SAMPLES; d++) {
                worst = Math.max(worst, Math.toDegrees(angleError(yaw, d)));
            }
        }
        assertTrue("the sweep must exceed a nearest-direction quantiser's 22.5, got " + worst, worst > 22.5D);
        // The sweep steps half a degree, so it approaches the boundary rather than landing on it; the delta is that
        // step and not slack in the claim.
        assertEquals("arctan(3/4), where the Manhattan tie between a cardinal and its diagonal falls",
                Math.toDegrees(Math.atan2(3.0D, 4.0D)), worst, 360.0D / DIRECTION_SAMPLES);
    }

    /**
     * The number the floor is actually built on: the largest distance from which no available step improves.
     *
     * <p>A step of length {@code L} taken at {@code θ} from the direction the body wants lands at
     * {@code sqrt(d² + L² − 2·d·L·cos θ)}, which is below {@code d} only while {@code d > L / (2·cos θ)}. Below that
     * radius every option overshoots, none improves, {@link FineApproach#chooseKeys} is never even consulted because
     * {@link CellExecutor} has already stopped pressing — and the body stands still until something else ends the run.
     * Nothing did, for 44 000 ticks.
     *
     * <p>The supremum comes from a DIAGONAL step at 19.45 degrees rather than from the worst angle at 36.87, because
     * the extra {@code sqrt(2)} of length costs more than the extra 17 degrees do. Measuring the two worst cases
     * separately and multiplying them would give 0.0575 — a defensible envelope, and 18% pessimistic.
     */
    @Test
    public void theStallRadiusIsWhereNoAvailableStepImproves() {
        double worst = 0.0D;
        double worstAngle = 0.0D;
        double worstLength = 0.0D;
        for (int y = 0; y < YAW_SAMPLES; y++) {
            float yaw = y * 360.0F / YAW_SAMPLES;
            for (int d = 0; d < DIRECTION_SAMPLES; d++) {
                double theta = angleError(yaw, d);
                double length = FineApproach.stepVector(yaw, keysToward(yaw, d)).length();
                assertTrue("a chosen step must at least point vaguely at the target, or nothing converges",
                        Math.cos(theta) > 0.0D);
                double stall = length / (2.0D * Math.cos(theta));
                if (stall > worst) {
                    worst = stall;
                    worstAngle = Math.toDegrees(theta);
                    worstLength = length;
                }
            }
        }
        assertEquals("measured stall radius", 0.0487D, worst, 5.0E-4D);
        assertEquals("and it is a diagonal step that sets it", FineApproach.LONGEST_STEP, worstLength, 1.0E-6D);
        assertTrue("at a middling angle, not the worst one: " + worstAngle, worstAngle > 15.0D && worstAngle < 25.0D);

        assertTrue("the published constant must not be under what was measured",
                FineApproach.CONTROLLER_STALL_RADIUS >= worst);
        assertTrue("nor invented far above it", FineApproach.CONTROLLER_STALL_RADIUS < worst + 0.001D);
    }

    /** The floor sits above the stall radius, and the gap is the coast the executor absorbs between the last key
     *  press and {@link FineApproach#settled}. */
    @Test
    public void theAchievableToleranceClearsTheStallRadius() {
        assertTrue("a tolerance at the stall radius is entered and immediately left again",
                FineApproach.ACHIEVABLE_TOLERANCE > FineApproach.CONTROLLER_STALL_RADIUS);
        assertTrue("and it must stay under the open-cell tolerance, or the ladder has one rung",
                FineApproach.ACHIEVABLE_TOLERANCE < FineApproach.TOLERANCE);
        assertFalse("0.02 is what the last run was asked for and it is under the floor",
                FineApproach.achievable(0.02D));
        assertFalse("NaN is not achievable either — every comparison against it is false, which is the same freeze",
                FineApproach.achievable(Double.NaN));
        assertTrue(FineApproach.achievable(FineApproach.ACHIEVABLE_TOLERANCE));
        assertTrue(FineApproach.achievable(FineApproach.TOLERANCE));
    }

    // ------------------------------------------------------------------------------- the loop, closed

    /**
     * The freeze, reproduced: driven at 0.02, the loop never terminates.
     *
     * <p>The simulation is the executor's own rule — while {@link FineApproach#arrived} is false, hold the keys
     * {@link FineApproach#chooseKeys} names and move by {@link FineApproach#stepVector} — with the friction left out,
     * which makes it OPTIMISTIC. It still fails from most starts.
     *
     * <p>Most, not all, and the distinction is the honest one. The stall radius is a SUPREMUM over yaws and bearings:
     * a lucky pair walks the body straight onto the point and lands inside 0.02 by arithmetic. That is exactly why a
     * tolerance is not allowed to be a coin flip. A number the approach reaches from some starts and never from
     * others is not a tolerance, it is a distribution — and the executor's patience runs out on the wrong half of it
     * with no way to tell which half it was in.
     */
    @Test
    public void theLoopCannotSettleInsideTheOldTightTolerance() {
        int stuck = 0;
        int tried = 0;
        for (float yaw : YAWS) {
            for (double offset : OFFSETS) {
                for (int bearing = 0; bearing < 16; bearing++) {
                    tried++;
                    if (ticksToSettle(yaw, start(offset, bearing), 0.02D) < 0) {
                        stuck++;
                    }
                }
            }
        }
        assertTrue("0.02 must fail from a large share of legal starts, not from an unlucky one: " + stuck + "/"
                + tried, stuck > tried / 4);
    }

    /**
     * And driven at the floor, the same loop always terminates — quickly.
     *
     * <p>Same simulation, same starts, same yaws, one number changed. {@code APPROACH_PATIENCE_TICKS} is 60, so the
     * bound asserted here is the executor's real one and not a generous stand-in.
     */
    @Test
    public void theLoopAlwaysSettlesInsideTheAchievableTolerance() {
        int worst = 0;
        for (float yaw : YAWS) {
            for (double offset : OFFSETS) {
                for (int bearing = 0; bearing < 16; bearing++) {
                    Vec3 from = start(offset, bearing);
                    int ticks = ticksToSettle(yaw, from, FineApproach.ACHIEVABLE_TOLERANCE);
                    assertTrue("yaw " + yaw + " from " + from + " never settled", ticks >= 0);
                    worst = Math.max(worst, ticks);
                }
            }
        }
        assertTrue("the worst approach took " + worst + " ticks and the executor allows 60", worst < 60);
    }

    /**
     * A yaw wound out to −10 836 degrees behaves exactly like its residue.
     *
     * <p>The trace of the frozen run has that number in it. It is correct modulo 360 and everything downstream
     * normalises — but a comparison that forgot to would fail silently and for ever, so the equivalence is asserted
     * rather than assumed. {@link FineApproach#chooseKeys} is safe by construction, because it consumes the yaw only
     * through {@code Mth.sin}/{@code Mth.cos}, which are periodic.
     */
    @Test
    public void anAccumulatedYawChoosesTheSameKeysAsItsResidue() {
        float wound = -10836.0F;
        float residue = princeps.api.utils.Rotation.normalizeYaw(wound);
        assertEquals(-36.0F, residue, 1.0E-3F);
        for (int d = 0; d < DIRECTION_SAMPLES; d++) {
            Vec3 target = start(0.25D, d);
            assertEquals("direction " + d, FineApproach.chooseKeys(residue, Vec3.ZERO, target),
                    FineApproach.chooseKeys(wound, Vec3.ZERO, target));
        }
    }

    /**
     * The controller may briefly move one coordinate away from the target, but never outside the one-step reserve the
     * support proof grants around the source-to-arrival rectangle.
     */
    @Test
    public void everyClosedLoopApproachStaysInsideItsProvedSupportReserve() {
        double worstExcursion = 0.0D;
        String worstCase = "";
        for (int yawSample = 0; yawSample < 360; yawSample++) {
            float yaw = yawSample;
            for (double offset : new double[] {0.95D, 0.70D, 0.30D, 0.13D, 0.07D}) {
                for (int bearing = 0; bearing < 16; bearing++) {
                    Vec3 start = start(offset, bearing);
                    double minX = Math.min(start.x, -FineApproach.ACHIEVABLE_TOLERANCE);
                    double maxX = Math.max(start.x, FineApproach.ACHIEVABLE_TOLERANCE);
                    double minZ = Math.min(start.z, -FineApproach.ACHIEVABLE_TOLERANCE);
                    double maxZ = Math.max(start.z, FineApproach.ACHIEVABLE_TOLERANCE);
                    Vec3 at = start;
                    boolean arrived = false;
                    for (int tick = 0; tick < 60; tick++) {
                        if (FineApproach.arrived(at, Vec3.ZERO, FineApproach.ACHIEVABLE_TOLERANCE)) {
                            arrived = true;
                            break;
                        }
                        FineApproach.Keys keys = FineApproach.chooseKeys(yaw, at, Vec3.ZERO);
                        assertTrue("controller stalled outside the tolerance", keys.any());
                        at = at.add(FineApproach.stepVector(yaw, keys));
                        double excursion = Math.max(
                                Math.max(minX - at.x, at.x - maxX),
                                Math.max(minZ - at.z, at.z - maxZ));
                        if (excursion > worstExcursion) {
                            worstExcursion = excursion;
                            worstCase = "yaw " + yaw + " from " + start + " at " + at;
                        }
                    }
                    assertTrue("yaw " + yaw + " from " + start + " did not arrive inside the executor bound", arrived);
                }
            }
        }
        assertTrue("worst controller excursion " + worstExcursion + " exceeds the support reserve "
                        + FineApproach.SUPPORT_ENVELOPE_RESERVE + " (" + worstCase + ")",
                worstExcursion <= FineApproach.SUPPORT_ENVELOPE_RESERVE + 1.0E-7D);
    }

    // ------------------------------------------------------------------------------------------ the simulation

    /**
     * Run the executor's approach loop and return how many ticks it needed, or −1 if it never arrived.
     *
     * <p>Deliberately friction-free: the real body accelerates into the step and coasts out of it, and both of those
     * make arrival HARDER, not easier. A tolerance this loop cannot reach is one the bot certainly cannot.
     */
    private static int ticksToSettle(float yaw, Vec3 from, double tolerance) {
        Vec3 at = from;
        for (int tick = 1; tick <= 4000; tick++) {
            if (FineApproach.arrived(at, Vec3.ZERO, tolerance)) {
                return tick;
            }
            FineApproach.Keys keys = FineApproach.chooseKeys(yaw, at, Vec3.ZERO);
            if (!keys.any()) {
                return -1;   // the controller has nothing left to press and is not there
            }
            at = at.add(FineApproach.stepVector(yaw, keys));
        }
        return -1;
    }

    /** A start position {@code offset} blocks from the origin on the given bearing of sixteen. */
    private static Vec3 start(double offset, int bearing) {
        double angle = 2.0D * Math.PI * bearing / 16.0D;
        return new Vec3(Math.cos(angle) * offset, 0.0D, Math.sin(angle) * offset);
    }

    /** The keys the real controller picks for direction {@code d} of the sweep, from the origin. */
    private static FineApproach.Keys keysToward(float yaw, int d) {
        return FineApproach.chooseKeys(yaw, Vec3.ZERO, wanted(d));
    }

    /** The angle between the direction wanted and the direction the chosen keys actually move the body. */
    private static double angleError(float yaw, int d) {
        Vec3 want = wanted(d);
        Vec3 step = FineApproach.stepVector(yaw, FineApproach.chooseKeys(yaw, Vec3.ZERO, want));
        double cos = (want.x * step.x + want.z * step.z) / (want.length() * step.length());
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, cos)));
    }

    private static Vec3 wanted(int d) {
        double angle = 2.0D * Math.PI * d / DIRECTION_SAMPLES;
        return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1.0E-4D;
    }
}
