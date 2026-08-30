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

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.MovementOption;

import java.util.Comparator;

/**
 * The last half block. Sneak-walk from wherever the pathfinder dropped the bot to the exact sub-block point the plan
 * proved, tolerance 0.08.
 *
 * <h2>Why this is a regular state and not a rescue</h2>
 * <p>The pathfinder is block-granular: {@code GoalBlock} is satisfied anywhere inside the cell, which can be 0.7
 * blocks from where the proof was written. The margins that decide whether a piston lands facing down are 0.02 blocks
 * wide. So the sub-block position is not a detail of arrival, it is half the proof — the worked example in plan 4.1
 * gets margin 0.19 from the cell centre and 0.41 from the optimised approach, which is the difference between a coin
 * flip and a certainty.
 *
 * <p>V2 has the same primitive ({@code BuilderProcess.centerInPlacementStance}, private) but reaches it only after
 * something has already gone wrong, which is why it is wrapped in an 80-tick tolerance machine. Two changes here: the
 * target is the PLANNED point rather than the block centre, and it runs on every cell, so nothing has to detect a
 * failure first.
 *
 * <h2>The mirrored frame — the one thing that is easy to get backwards</h2>
 * <p>{@link MovementOption#getOptions} is built from {@code (sin(yaw), cos(yaw))}, and its MOVE_FORWARD entry is
 * exactly that pair. Minecraft's real forward vector is {@code (-sin(yaw), cos(yaw))}. Every one of the eight options
 * carries that same sign flip on X, consistently — so the option table lives in a frame mirrored about the X axis,
 * and a wanted direction expressed in world coordinates has to be mirrored the same way before it can be compared.
 * Flip one and not the other and the bot strafes away from its target with no error anywhere: it simply never
 * arrives, and the only symptom is a stance that times out.
 *
 * <p>Pure and deterministic. No client, no settings, no randomness — {@link java.util.stream.Stream#min} keeps the
 * first of equal candidates and the option order is fixed, so a tie resolves the same way in every run.
 */
public final class FineApproach {

    private FineApproach() {
    }

    /**
     * How close is close enough, in blocks, measured horizontally — the DEFAULT, for a cell whose sight line the
     * planner proved over the whole ball of this radius.
     *
     * <p>0.08 costs nothing because the planner maximised the margin it is spent against: the accepted solutions sit
     * at margins around 0.4, so eight centimetres of residual leaves the dominance decision untouched. It also clears
     * {@link #ACHIEVABLE_TOLERANCE} — the measured floor under what this controller can hold — by a third, so it is a
     * tolerance the bot reaches rather than a target it oscillates around. That last clause used to read "comfortably
     * above what sneak friction settles to within a tick or two", which was a guess about the wrong mechanism: what
     * bounds the strictness is the eight-direction quantisation, not the friction, and the number it bounds it at is
     * 0.0487 rather than the couple of centimetres the guess implied.
     *
     * <p>What it does NOT cost nothing against is occlusion, and that is the correction of plan 19. Dominance has a
     * margin the approach point is chosen to maximise; a sight line either clears the corner of the neighbouring cell
     * or does not, and on the first live basalt run it cleared by 0.019 blocks from the proven point and missed from
     * a point 0.037 away — an arrival well inside this tolerance. So this number is no longer a property of the
     * engine: it is the WIDEST tolerance, granted to an action only when {@link PlacementOracle} has proved the ray
     * from the rim of it, and {@link #TIGHT_TOLERANCE} is what the rest get.
     */
    public static final double TOLERANCE = 0.08D;

    /**
     * Horizontal distance the body covers in one sneaking tick with one movement key held, in blocks.
     *
     * <p>{@code ActionCosts.SNEAK_ONE_BLOCK_COST} is {@code 20 / 1.3}: the pathfinder prices a sneaked block at 1.3
     * blocks per second, which is {@code 1.3 / 20} per tick. The same number from the other end:
     * {@link princeps.utils.PlayerMovementInput} scales both impulses by {@code 0.3F} while {@code SNEAK} is forced,
     * and {@code 0.3 * 4.317} m/s is 1.295.
     */
    public static final double SNEAK_STEP = 1.3D / 20.0D;

    /**
     * The LONGEST step the controller can take in one tick, in blocks — a diagonal one, and it is not
     * {@link #SNEAK_STEP}.
     *
     * <p>{@code PlayerMovementInput} emits {@code moveVector = (0.3, 0.3)} for a diagonal sneak, whose length is
     * 0.424. Vanilla's {@code Entity.getInputVector} normalises the input vector only when {@code lengthSqr() > 1.0},
     * so a sneaked diagonal is NOT normalised and moves {@code sqrt(2)} times as far as a sneaked cardinal — 0.0919
     * blocks against 0.0650. Walking is the opposite case and that is why the quirk is easy to miss: at full impulse
     * the diagonal is {@code (1, 1)}, length 1.414, which vanilla does normalise.
     */
    public static final double LONGEST_STEP = SNEAK_STEP * Math.sqrt(2.0D);

    /**
     * Extra floor required around the axis-aligned source-to-target envelope.
     *
     * <p>The eight-way controller always reduces total distance, but its closest direction can briefly carry one
     * coordinate away from the target (the measured worst single component is about 0.019 blocks). One complete
     * longest diagonal step is a conservative bound around that quantisation excursion. The planner proves this rim
     * and the executor starts only from settled velocity, so uncontrolled path-travel momentum is not hidden inside
     * the number. A closed-loop sweep over every whole-degree yaw, sixteen bearings and starts up to 0.95 blocks
     * measured a maximum excursion of 0.111 blocks; 0.13 keeps nearly two centimetres of reserve over that result.
     */
    public static final double SUPPORT_ENVELOPE_RESERVE = 0.13D;

    /**
     * The radius inside which this controller cannot improve its position, in blocks. MEASURED, not chosen — see
     * {@code ApproachFloorTest#theStallRadiusIsWhereNoAvailableStepImproves}.
     *
     * <p>The derivation, because a number like this is worthless without one. {@link #chooseKeys} does not steer: it
     * SELECTS one of the eight key combinations {@link MovementOption#getOptions} offers, each of which moves the body
     * along a fixed direction 45 degrees apart, at a fixed length ({@link #SNEAK_STEP} cardinal,
     * {@link #LONGEST_STEP} diagonal). So a step of length {@code L} taken at angle {@code θ} from the direction the
     * body actually wants lands at
     *
     * <pre>d' = sqrt(d² + L² − 2·d·L·cos θ)</pre>
     *
     * <p>and {@code d' < d} holds only while {@code d > L / (2·cos θ)}. Below that radius EVERY option overshoots,
     * none improves, and the loop emits no key at all — which is not a slow approach, it is a permanent one.
     *
     * <p>Sweeping every yaw and every wanted direction through the real {@link #chooseKeys} gives two worst cases.
     * The largest angular error is 36.87 degrees and not the 22.5 an eight-way quantiser suggests, because
     * {@code MovementOption.distanceToSq} is Manhattan distance against a UNIT wanted vector while the table's
     * diagonal entries have length {@code sqrt(2)} — the diagonals are systematically under-selected. The largest
     * value of {@code L / (2·cos θ)} is 0.0487, and it comes from a different sample: a DIAGONAL step (L = 0.0919) at
     * θ = 19.45 degrees, because the long step costs more than the bad angle does.
     *
     * <p>0.0488 is that supremum rounded up. Nothing below it is reachable by this body, whatever the geometry asks.
     */
    public static final double CONTROLLER_STALL_RADIUS = 0.0488D;

    /**
     * The FLOOR under every per-action tolerance in the engine, in blocks: the tightest arrival the body can be asked
     * to hold.
     *
     * <p>{@link #CONTROLLER_STALL_RADIUS} times 1.23, rounded to the hundredth. The factor pays for the one thing the
     * step model above leaves out: the executor releases the keys the moment {@link #arrived} is true and then waits
     * for {@link #settled}, and sneak friction ({@code 0.6 * 0.91 = 0.546} a tick) coasts the body a little further
     * while it does. A tolerance equal to the stall radius would therefore be entered and immediately left again.
     *
     * <p>This is a property of the MOVEMENT SYSTEM and not of any schematic, which is why it lives here and why the
     * planner reads it rather than owning it. The previous value of {@link #TIGHT_TOLERANCE} was 0.02 — below the
     * stall radius by a factor of two and a half, i.e. specified against a controller that could never satisfy it.
     * The etz-basalt run paid for that: the fine approach froze at {@code 92.476,-60,93.531} for 44 000 ticks with
     * velocity zero, because no key improved and none was emitted.
     *
     * <p>The engine's answer to a cell that needs tighter than this is not a tighter number. It is
     * {@link PlacementOracle.Rejection#OCCLUSION_BELOW_BODY_FLOOR} — a named blocker at plan time. Prove it or refuse
     * it; never emit an action the executor cannot satisfy.
     */
    public static final double ACHIEVABLE_TOLERANCE = 0.06D;

    /**
     * The approach tolerance for a cell whose sight line survives {@link #ACHIEVABLE_TOLERANCE} of drift but not
     * {@link #TOLERANCE}.
     *
     * <p>The other half of the answer to open question F2 — "how strict may the fine approach be?". Too strict costs
     * waiting before every click, too loose does not buy the margin, and the measurement says there is no single
     * right answer: the strictness is derived per action from that action's own proof, and the planner is the only
     * place that can know it.
     *
     * <p>It is exactly the floor and never anything below it. A solution needing more precision than this is not
     * tightened further, it is refused — see {@link PlacementOracle#APPROACH_TOLERANCE_LADDER}.
     */
    public static final double TIGHT_TOLERANCE = ACHIEVABLE_TOLERANCE;

    /** Slack for comparing a stored tolerance against the floor. Tolerances are written by the planner from the
     *  ladder's own literals, so this only absorbs a round trip through a text plan file. */
    private static final double FLOOR_EPSILON = 1.0E-9D;

    /**
     * May the body be asked to stand this close? The one predicate every producer of a tolerance is checked against,
     * so "the plan asks only for what the body can do" is enforced at construction rather than discovered live.
     *
     * <p>{@code NaN} answers false, which is the answer that matters: {@code NaN >= x} is false and a tolerance that
     * arrived as NaN would otherwise flow into {@link #arrived}, where every comparison against it is false and the
     * approach never ends.
     */
    public static boolean achievable(double tolerance) {
        return tolerance >= ACHIEVABLE_TOLERANCE - FLOOR_EPSILON;
    }

    /** Horizontal speed under which residual momentum is dead, in blocks per tick. Sneak friction kills a walking
     *  step in one to three ticks; below this the body will not drift out of {@link #TOLERANCE} while the head turns. */
    public static final double SETTLED_SPEED = 0.01D;

    /** Degrees to radians as float, matching what {@code Mth.sin}/{@code Mth.cos} are fed everywhere else in the
     *  movement code — the lookup table they use is why the constant is not {@code Math.toRadians}. */
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0D);

    /** The key combination to hold this tick, or {@link #NONE} when the bot is already there. Two fields because the
     *  four diagonal options are two keys and {@code MovementOption.input2()} is null for the other four. */
    public record Keys(Input first, Input second) {

        public static final Keys NONE = new Keys(null, null);

        public boolean any() {
            return this.first != null;
        }
    }

    /** Squared horizontal distance. Y is ignored throughout: the pathfinder put the feet on the stance's floor and
     *  the approach point's Y is that floor by construction, so a vertical term would only add the sub-block bob of
     *  a body that has not finished landing. */
    public static double horizontalDistanceSq(Vec3 position, Vec3 target) {
        double dx = position.x - target.x;
        double dz = position.z - target.z;
        return dx * dx + dz * dz;
    }

    /** Is the body at the planned point, judged by the widest tolerance? Only for callers that have no action in
     *  hand — the executor asks {@link #arrived(Vec3, Vec3, double)} with the tolerance the action carries. */
    public static boolean arrived(Vec3 position, Vec3 target) {
        return arrived(position, target, TOLERANCE);
    }

    /**
     * Is the body at the planned point, judged by THIS action's tolerance?
     *
     * <p>The tolerance is an input and not a constant because it is part of the proof. {@link PlacementOracle} casts
     * the chosen solution's ray from the rim of the arrival ball and writes into the action the widest radius over
     * which the ray still lands on the same cell and the same face; standing anywhere inside that radius is therefore
     * standing somewhere the click was proven from, and standing outside it is not.
     */
    public static boolean arrived(Vec3 position, Vec3 target, double tolerance) {
        return horizontalDistanceSq(position, target) <= tolerance * tolerance;
    }

    /** Has the residual momentum died? Asked after {@link #arrived}, because arriving at speed means leaving again
     *  on the next tick and aiming from a point the plan did not prove. */
    public static boolean settled(Vec3 velocity) {
        return velocity.x * velocity.x + velocity.z * velocity.z <= SETTLED_SPEED * SETTLED_SPEED;
    }

    /**
     * Is this body moving fast enough that pressing against it removes more speed than it adds?
     *
     * <p>Waiting for sneak friction to eat the momentum handed over by path travel was, measured over a live basalt
     * run, {@code 20.7 %} of every tick the builder spent — a fifth of its life standing still while its own velocity
     * decayed. The body can push back instead, and it already owns the eight-direction solver to do it.
     *
     * <p>The floor is one {@link #SNEAK_STEP}, and it is the whole safety argument: the counter-impulse is itself
     * about one step long, so below that speed a press does not brake, it reverses — and a body that reverses every
     * tick oscillates around the target instead of arriving at it. Above it, friction and the press pull the same
     * way and the speed falls monotonically. The last sliver of speed is still left to friction, which is what
     * friction is good at.
     */
    public static boolean worthBraking(Vec3 velocity) {
        return velocity.x * velocity.x + velocity.z * velocity.z > SNEAK_STEP * SNEAK_STEP;
    }

    /**
     * The key combination whose motion vector points most nearly at the target.
     *
     * <p>Selection, not steering: the bot walks in whichever of eight directions its current facing affords, and the
     * head is busy aiming at the click point, so turning the body toward the target is not an option. Over the
     * fraction of a block this state covers, at sneak speed, the best of eight is well inside the tolerance.
     *
     * @param yawDegrees the LIVE yaw, because the motion frame is the one the body has right now, not the one the aim
     *                   is turning toward
     * @return the keys to force, or {@link Keys#NONE} when the target is already underfoot
     */
    public static Keys chooseKeys(float yawDegrees, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6D) {
            return Keys.NONE;
        }
        // Into the option table's mirrored frame: negate X, keep Z, and normalise so the comparison is against unit
        // vectors on both sides (every option's motion pair is unit length, since (sin, cos) is).
        float wantedX = (float) (-dx / length);
        float wantedZ = (float) (dz / length);
        float radians = yawDegrees * DEG_TO_RAD;
        // canSprint = false: sprinting is not merely faster, it multiplies the forward component by 1.3 and so tilts
        // the diagonal options away from 45 degrees, which changes WHICH option wins. And a sprint over 0.3 blocks
        // would overshoot the tolerance it is aiming for anyway.
        MovementOption best = MovementOption
                .getOptions(Mth.sin(radians), Mth.cos(radians), false)
                // distanceToSq is Manhattan despite its name (MovementOption.java:41). It is a valid ranking over
                // unit vectors and it is what V2 ranks with; the point here is to pick the same option V2 would,
                // not to be more correct than it in a way that changes behaviour nobody measured.
                .min(Comparator.comparingDouble(option -> option.distanceToSq(wantedX, wantedZ)))
                .orElse(null);
        return best == null ? Keys.NONE : new Keys(best.input1(), best.input2());
    }

    /**
     * Where the body actually goes when these keys are held for one sneaking tick, as a WORLD-space vector whose
     * length is the distance covered.
     *
     * <p>The other end of {@link #chooseKeys}, and it exists so the floor above can be measured against the real
     * controller instead of against a second copy of its arithmetic. It reproduces {@code PlayerMovementInput.tick}
     * followed by vanilla's {@code Entity.getInputVector}: forward is {@code +1} and back {@code -1} on the forward
     * impulse, left is {@code +1} and right {@code -1} on the left impulse, both scaled by {@code 0.3} for sneak, and
     * the pair is rotated into world space as {@code (l·cos − f·sin, f·cos + l·sin)}. It is NOT normalised, because
     * vanilla does not normalise it either below unit length — which is the whole reason a diagonal sneak covers
     * {@link #LONGEST_STEP} rather than {@link #SNEAK_STEP}.
     *
     * @return the per-tick displacement, or {@link Vec3#ZERO} for {@link Keys#NONE}
     */
    public static Vec3 stepVector(float yawDegrees, Keys keys) {
        double forward = impulse(keys, Input.MOVE_FORWARD) - impulse(keys, Input.MOVE_BACK);
        double left = impulse(keys, Input.MOVE_LEFT) - impulse(keys, Input.MOVE_RIGHT);
        if (forward == 0.0D && left == 0.0D) {
            return Vec3.ZERO;
        }
        float radians = yawDegrees * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);
        return new Vec3(left * cos - forward * sin, 0.0D, forward * cos + left * sin).scale(SNEAK_STEP);
    }

    private static double impulse(Keys keys, Input wanted) {
        return keys.first() == wanted || keys.second() == wanted ? 1.0D : 0.0D;
    }
}
