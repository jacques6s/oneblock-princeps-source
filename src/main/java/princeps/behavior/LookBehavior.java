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

package princeps.behavior;

import princeps.Princeps;
import princeps.api.Settings;
import princeps.api.behavior.ILookBehavior;
import princeps.api.behavior.look.AimIntent;
import princeps.api.behavior.look.IAimProcessor;
import princeps.api.behavior.look.ITickableAimProcessor;
import princeps.api.process.IElytraProcess;
import princeps.api.event.events.*;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.movement.IMovement;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;
import princeps.behavior.look.ForkableRandom;
import princeps.flownav.FlowCam;
import princeps.pathing.movement.movements.MovementDiagonal;
import princeps.pathing.movement.movements.MovementTraverse;
import princeps.pathing.movement.movements.SmoothTraverse;
import princeps.pathing.path.PathExecutor;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public final class LookBehavior extends Behavior implements ILookBehavior {

    /**
     * The current look target, may be {@code null}.
     */
    private Target target;

    /**
     * The rotation known to the server. Returned by {@link #getEffectiveRotation()} for use in {@link IPlayerContext}.
     */
    private Rotation serverRotation;

    /**
     * The last player rotation. Used to restore the player's angle when using free look.
     *
     * @see Settings#freeLook
     */
    private Rotation prevRotation;

    /**
     * The rotation PRE actually applied to the player this tick. RotationMoveEvent (moveRelative) fires later in
     * the same tick and used to RE-peek with prev = the already-advanced live rotation — taking a SECOND turn step
     * that the mixin then wrote back to the entity. That doubled the effective turn rate erratically (1-2x) and made
     * the walk direction lead the visible look during turns (the observed weaving). Consumers of "the rotation this
     * tick" must reuse this value instead of re-peeking.
     */
    private Rotation appliedRotation;

    /** Sub-mouse-count remainder carried between elytra flight ticks so the quantized flight rotation tracks the
     *  low-pass with zero drift (error diffusion). Reset whenever a flight ends. */
    private float elytraYawResidual;
    private float elytraPitchResidual;

    /** Solver demand (deg between applied and requested rotation) below which flight smoothing stays fully smooth. */
    private static final double ELYTRA_AGILE_BLEND_START_DEG = 6.0;
    /** Solver demand at/above which flight smoothing is fully agile (and the agile hold re-arms). */
    private static final double ELYTRA_AGILE_ENTER_DEG = 20.0;
    /** Nether-interior flight yields earlier: tunnel obstacles need steering rather than open-sky glide lag. */
    private static final double ELYTRA_NETHER_INTERIOR_BLEND_START_DEG = 3.0;
    private static final double ELYTRA_NETHER_INTERIOR_AGILE_ENTER_DEG = 10.0;
    /** Maximum smoothing during a hard sub-roof maneuver (0.10 means 91% response per tick). */
    private static final double ELYTRA_NETHER_INTERIOR_AGILE_SMOOTHNESS_MAX = 0.10;
    /** Ticks the agile level is held after a hard demand, so S-curve sequences don't flip-flop mid-maneuver. */
    private static final int ELYTRA_AGILE_HOLD_TICKS = 10;
    /** Remaining ticks of the agile hold (see above). Decays naturally; stale values are harmless. */
    private int elytraAgileHold;

    // ── cruise micro-jitter (see updateMicroJitter) ──────────────────────────────────────────────────────
    /** Commanded yaw turn (deg/tick) below which a tick counts as walking a straight line (curves are above). */
    private static final float JITTER_STRAIGHT_MAX_TURN = 2.5f;
    /** Consecutive straight ticks required before the jitter may fire (rules out curve exits/entries). */
    private static final int JITTER_MIN_STRAIGHT_TICKS = 6;
    /** Ticks between excursions: min + rand(0..span-1) → roughly one per second. */
    private static final int JITTER_COOLDOWN_MIN = 14;
    private static final int JITTER_COOLDOWN_SPAN = 13;
    /**
     * Apply-path-only RNG (like curveRng): the jitter never touches predictions or the forked solver replay
     * of {@link AimProcessor}'s ForkableRandom, so untracked randomness is safe here.
     */
    private final Random jitterRng = new Random();
    /** Last commanded (pre-jitter) yaw, for the straightness turn-rate estimate. NaN = no previous sample. */
    private float jitterLastYaw = Float.NaN;
    private int jitterStraightTicks;
    private int jitterCooldown = JITTER_COOLDOWN_MIN;
    /** Remaining ticks of the active excursion (0 = idle). */
    private int jitterTicksLeft;
    /** Ticks per envelope side (1..3): ramp out over this many ticks, then back over the same. */
    private int jitterRampTicks = 1;
    private float jitterYawAmp;
    private float jitterPitchAmp;
    /** The offset applied to the SENT rotation this tick (mouse-count-grid aligned; 0 while idle). */
    private float jitterYawOffset;
    private float jitterPitchOffset;

    private final AimProcessor processor;

    private final Deque<Float> smoothYawBuffer;
    private final Deque<Float> smoothPitchBuffer;

    public LookBehavior(Princeps princeps) {
        super(princeps);
        this.processor = new AimProcessor(princeps.getPlayerContext());
        this.smoothYawBuffer = new ArrayDeque<>();
        this.smoothPitchBuffer = new ArrayDeque<>();
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        this.updateTarget(rotation, blockInteract, AimIntent.NONE);
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent) {
        this.updateTarget(rotation, blockInteract, AimIntent.fromLegacy(breakIntent, false));
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent, boolean placeIntent) {
        this.updateTarget(rotation, blockInteract, AimIntent.fromLegacy(breakIntent, placeIntent), false);
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent, boolean placeIntent,
                             boolean deterministicIntent) {
        this.updateTarget(rotation, blockInteract, AimIntent.fromLegacy(breakIntent, placeIntent),
                deterministicIntent);
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract, AimIntent intent) {
        this.updateTarget(rotation, blockInteract, intent, false);
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract, AimIntent intent,
                             boolean deterministicIntent) {
        AimIntent safeIntent = intent == null ? AimIntent.NONE : intent;
        // blockInteract == "this movement needs an EXACT facing" (break/place/bridge) → carry it as the precise
        // flag so the humanized-look filter hard-bypasses its wander for this target. The explicit intent engages
        // the appropriate interaction curve from the FIRST aim tick — the mouse button cannot signal that because
        // correct break/place sites press only after the crosshair has arrived.
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), blockInteract, safeIntent,
                deterministicIntent);
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return this.processor;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            this.processor.tick();
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {

        if (this.target == null) {
            // Not steering the view this tick: keep the camera continuous over short movement
            // handoffs (grace), fall back to vanilla only on sustained absence.
            FlowCam.stopSoon(ctx.player().getYRot(), ctx.player().getXRot());
            // Micro-jitter is a while-steering feature: drop any active excursion and restart the
            // straightness observation from scratch when steering resumes.
            this.jitterTicksLeft = 0;
            this.jitterYawOffset = 0.0f;
            this.jitterPitchOffset = 0.0f;
            this.jitterStraightTicks = 0;
            this.jitterLastYaw = Float.NaN;
            return;
        }

        switch (event.getState()) {
            case PRE: {
                if (this.target.mode == Target.Mode.NONE) {
                    // Just return for PRE, we still want to set target to null on POST
                    return;
                }

                this.prevRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                // A jump commanded THIS tick (parkour/ascend launch) must fly its EXACT heading: the airborne
                // bypass only engages next tick once physics leave the ground, so a forced-jump tick counts precise.
                final boolean jumpLaunch = princeps.getInputOverrideHandler().isInputForcedDown(Input.JUMP);
                this.processor.setPrecise(this.target.precise || jumpLaunch);
                // Arc the head through a mining aim instead of snapping: cap the turn while a BREAK is forced OR
                // while the target itself is a declared break aim, on the ground, and not launching a jump (a jump
                // must fly its exact heading). The breakIntent flag is essential: every break site correctly gates
                // its CLICK_LEFT press on the crosshair having ARRIVED, so on the acquisition tick the input alone
                // is always false — deriving the arc from the input is circular (curve waits for click, click waits
                // for arrival) and the first-aim snap ("flick") would survive. Placement is handled independently
                // below; ordinary right-click interactions remain exact because they declare no interaction intent.
                final boolean breaking = princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                        || this.target.intent == AimIntent.BREAK;
                // A declared placement asks for the same arc under its own setting. It is deliberately NOT derived
                // from the CLICK_RIGHT input the way `breaking` is derived from CLICK_LEFT: the builder's gate
                // forces the right-click only on the tick the aim has already arrived, so an input-derived term
                // would be circular exactly as it is on the break side — and, worse, CLICK_RIGHT is forced by the
                // pathfinder's bridging too. The placement pipeline declares intent before the click and keeps body
                // motion gated until the aim is safe, so both builder and bridge targets can use this path.
                final boolean placing = this.target.intent == AimIntent.PLACE;
                this.processor.setDeterministicPrecise(this.target.deterministicIntent);
                this.processor.setCapPreciseTurn(
                        Princeps.settings().humanizedLook.value && !jumpLaunch
                                && ((breaking && Princeps.settings().humanizedLookCapBreakTurn.value)
                                        || (placing && Princeps.settings().humanizedLookCapPlaceTurn.value)));
                // A plain FALL/descent (airborne, sinking, not a jump launch, not gliding, not a precise action):
                // route the look through the smooth rate-limited turn so it eases toward the (now stabilized) travel
                // heading + a downward pitch instead of snapping/spinning. Jump ascents (rising) and precise airborne
                // aims keep the exact heading.
                this.processor.setSmoothAirborne(
                        !ctx.player().onGround() && !ctx.player().isFallFlying() && !jumpLaunch
                                && !this.target.precise && ctx.player().getDeltaMovement().y < -0.08);
                final Rotation actual = this.processor.peekRotation(this.target.rotation);
                if (ctx.player().isFallFlying()) {
                    // Low-pass the *applied* look while gliding: ease toward the steering target instead of
                    // snapping to it each tick. This is the rotation the physics step AND the outgoing movement
                    // packet both read, so the server sees one smooth, self-consistent line — no silent packets,
                    // nothing that looks robotic. elytraSmoothness in [0,1] (higher = smoother) maps to the
                    // per-tick lerp factor `a` (lower = smoother); clamped so even max smoothness still tracks
                    // the target each tick, and 0 disables it (snap straight to the steering target). While
                    // landing we use the snappier elytraLandingSmoothness so the look tracks the spot precisely
                    // and sets down cleanly instead of overshooting until it gets stuck.
                    // MANEUVER-ADAPTIVE: open-space flight (including the Nether roof) keeps the long, smooth line.
                    // Below the Nether roof obstacle turns yield earlier and become nearly direct. Tiny corrections
                    // remain fully smooth so straight tunnel flight never starts twitching. A short agile hold keeps
                    // S-curve sequences from flip-flopping mid-maneuver.
                    final IElytraProcess elytraProc = princeps.getElytraProcess();
                    final double smoothness;
                    if (elytraProc != null && elytraProc.isLanding()) {
                        smoothness = Princeps.settings().elytraLandingSmoothness.value;
                    } else {
                        final float yawErr = Math.abs(Mth.degreesDifference(this.prevRotation.getYaw(), this.target.rotation.getYaw()));
                        final float pitchErr = Math.abs(this.target.rotation.getPitch() - this.prevRotation.getPitch());
                        final double err = Math.max(yawErr, pitchErr);
                        final boolean netherInterior = this.ctx.world().dimensionType().hasCeiling()
                                && this.ctx.player().getY() < 120.0;
                        final double blendStart = netherInterior
                                ? ELYTRA_NETHER_INTERIOR_BLEND_START_DEG : ELYTRA_AGILE_BLEND_START_DEG;
                        final double agileEnter = netherInterior
                                ? ELYTRA_NETHER_INTERIOR_AGILE_ENTER_DEG : ELYTRA_AGILE_ENTER_DEG;
                        if (err >= agileEnter) {
                            this.elytraAgileHold = ELYTRA_AGILE_HOLD_TICKS;
                        } else if (this.elytraAgileHold > 0) {
                            this.elytraAgileHold--;
                        }
                        final double smooth = Princeps.settings().elytraSmoothness.value;
                        final double configuredAgile = Princeps.settings().elytraSmoothnessAgile.value;
                        final double agile = netherInterior
                                ? Math.min(configuredAgile, ELYTRA_NETHER_INTERIOR_AGILE_SMOOTHNESS_MAX)
                                : configuredAgile;
                        final double blend = this.elytraAgileHold > 0
                                ? 1.0
                                : Math.max(0.0, Math.min(1.0,
                                        (err - blendStart) / (agileEnter - blendStart)));
                        smoothness = smooth + (agile - smooth) * blend;
                    }
                    final float a = (float) Math.max(0.1, Math.min(1.0, 1.0 - smoothness * 0.9));
                    final float curYaw = this.prevRotation.getYaw();
                    final float curPitch = this.prevRotation.getPitch();
                    // Low-pass toward the steering target, then ERROR-DIFFUSION quantize onto the integer mouse-count
                    // grid: the raw fractional lerp would emit a continuous, non-mouse-achievable yaw/pitch every
                    // flight tick (a real elytra flyer still steers with a quantized mouse — a rotation-quantization
                    // check could flag the continuous stream). Carrying the sub-count residual makes a slow turn a
                    // real 0/1-count-per-tick pattern instead of stalling, and keeps the emitted line within one
                    // mouse count of the raw low-pass (sim-verified: 0 non-quantized ticks, <0.15° drift, no stall
                    // across all smoothness values) — so the flight path and landing are unchanged.
                    final float minCount = this.processor.minAngleChange();
                    final float desiredYawDelta = Mth.degreesDifference(curYaw, actual.getYaw()) * a + this.elytraYawResidual;
                    final float qYawDelta = Math.round(desiredYawDelta / minCount) * minCount;
                    this.elytraYawResidual = desiredYawDelta - qYawDelta;
                    final float desiredPitchDelta = (actual.getPitch() - curPitch) * a + this.elytraPitchResidual;
                    final float qPitchDelta = Math.round(desiredPitchDelta / minCount) * minCount;
                    this.elytraPitchResidual = desiredPitchDelta - qPitchDelta;
                    ctx.player().setYRot(curYaw + qYawDelta);
                    ctx.player().setXRot(curPitch + qPitchDelta);
                } else {
                    // Cruise micro-jitter: nudge only the SENT rotation (player fields feed sendPosition's
                    // movement packet right after PRE). appliedRotation and FlowCam below deliberately keep the
                    // CLEAN commanded rotation, so physics, steering, predictions and the visible camera never
                    // see the jitter — the server alone does.
                    updateMicroJitter(actual);
                    ctx.player().setYRot(actual.getYaw() + this.jitterYawOffset);
                    ctx.player().setXRot(Mth.clamp(actual.getPitch() + this.jitterPitchOffset, -90.0f, 90.0f));
                    // not flying: drop any carried elytra residual so the next flight starts fresh
                    this.elytraYawResidual = 0.0f;
                    this.elytraPitchResidual = 0.0f;
                }
                this.appliedRotation = ctx.player().isFallFlying()
                        ? new Rotation(ctx.player().getYRot(), ctx.player().getXRot())
                        : new Rotation(actual.getYaw(), actual.getPitch());
                // FlowNav: record this tick's applied view so the camera interpolates it across frames.
                // Only in CLIENT mode, where the applied rotation persists visually — a SERVER-mode
                // rotation is restored in POST, and interpolating toward it would drag the free camera.
                // Uses appliedRotation (jitter-free on the ground) so the rendered camera stays untouched.
                if (this.target.mode == Target.Mode.CLIENT) {
                    FlowCam.push(ctx.player(), this.appliedRotation.getYaw(), this.appliedRotation.getPitch());
                } else {
                    FlowCam.stop();
                }
                break;
            }
            case POST: {
                // Reset the player's rotations back to their original values
                if (this.prevRotation != null) {
                    this.smoothYawBuffer.addLast(this.target.rotation.getYaw());
                    while (this.smoothYawBuffer.size() > Princeps.settings().smoothLookTicks.value) {
                        this.smoothYawBuffer.removeFirst();
                    }
                    this.smoothPitchBuffer.addLast(this.target.rotation.getPitch());
                    while (this.smoothPitchBuffer.size() > Princeps.settings().smoothLookTicks.value) {
                        this.smoothPitchBuffer.removeFirst();
                    }
                    if (this.target.mode == Target.Mode.SERVER) {
                        ctx.player().setYRot(this.prevRotation.getYaw());
                        ctx.player().setXRot(this.prevRotation.getPitch());
                    } else if (!ctx.player().isFallFlying() && Princeps.settings().smoothLook.value
                            && !Princeps.settings().humanizedLook.value) {
                        // smoothLook only when the humanizer is off: humanizedLook owns the turn shaping now, and
                        // this raw-target camera average would flatten the wander back out (and its naive degree
                        // mean corrupts headings near ±180).
                        // Camera averaging only off the elytra. While fall-flying the PRE low-pass is the single
                        // source of truth — overwriting yRot here with the raw-target buffer average would leak a
                        // stale mean into the next tick's packet and re-desync the physics from the sent rotation.
                        ctx.player().setYRot((float) this.smoothYawBuffer.stream().mapToDouble(d -> d).average().orElse(this.prevRotation.getYaw()));
                    }
                    // During elytra flight, point the body + head at the final look yaw so the model faces the
                    // flight direction. We deliberately leave yBodyRotO / yHeadRotO at their previous-tick values
                    // so the renderer interpolates the body smoothly at frame-rate, instead of snapping once per
                    // tick — that per-tick snap is what made the model look like it was stuttering / twitching.
                    if (ctx.player().isFallFlying()) {
                        final float yaw = ctx.player().getYRot();
                        ctx.player().yBodyRot = yaw;
                        ctx.player().setYHeadRot(yaw);
                    }
                    // Frame-rate camera smoothness (client-only): set the render-interpolation ORIGIN to the
                    // previous tick's rotation, so vanilla's getViewYRot(partialTick) lerps prev -> current
                    // across every frame between the 20 TPS ticks. Without it the local camera steps once per
                    // tick; with it the eased <=9 deg/tick turn is drawn smoothly at full FPS. Purely visual —
                    // the sent packet still carries the per-tick rotation, so the server sees no difference.
                    ctx.player().xRotO = prevRotation.getPitch();
                    ctx.player().yRotO = prevRotation.getYaw();
                    this.prevRotation = null;
                }
                // The target is done being used for this game tick, so it can be invalidated -- and the head-speed
                // override rides on exactly this lifetime, so whoever wants it has to ask again next tick.
                turnTicksOverride = 0.0;
                this.target = null;
                this.appliedRotation = null;
                break;
            }
            default:
                break;
        }
    }

    /**
     * Cruise micro-jitter (humanization): a real hand never holds a heading perfectly still while walking, so
     * roughly once a second on a calm STRAIGHT stretch the SENT view makes a tiny excursion — yaw and pitch each
     * pick an independent amplitude in ±[microJitterMin..Max] degrees, ramp out over 1–3 ticks and back over the
     * same, ending at exactly 0 (net-zero by construction: the offset is an envelope around the commanded
     * rotation, never accumulated state, so it cannot drift the heading or steer the feet). Diagonals count as
     * straight; curves, jump launches, break/place aims, water, elytra and precise targets are all excluded via
     * {@link #jitterEligible()}, and any gate failing mid-excursion zeroes it immediately. Offsets snap to the
     * integer mouse-count grid ({@link AbstractAimProcessor#minAngleChange()}) — a physical mouse can only emit
     * whole counts, so the packet stream stays mouse-achievable instead of showing impossible fractional turns.
     */
    private void updateMicroJitter(Rotation commanded) {
        // Straightness estimate from the commanded (pre-jitter) turn rate — the far-carrot direction change.
        final float yaw = commanded.getYaw();
        final float turn = Float.isNaN(this.jitterLastYaw)
                ? Float.MAX_VALUE
                : Math.abs(Mth.degreesDifference(this.jitterLastYaw, yaw));
        this.jitterLastYaw = yaw;
        if (turn < JITTER_STRAIGHT_MAX_TURN) {
            this.jitterStraightTicks++;
        } else {
            this.jitterStraightTicks = 0;
        }

        if (!jitterEligible()) {
            this.jitterTicksLeft = 0;
            this.jitterYawOffset = 0.0f;
            this.jitterPitchOffset = 0.0f;
            return;
        }

        if (this.jitterTicksLeft <= 0) {
            this.jitterYawOffset = 0.0f;
            this.jitterPitchOffset = 0.0f;
            if (--this.jitterCooldown > 0) {
                return;
            }
            // Schedule the next excursion: independent signed amplitudes per axis, 1–3 ticks per envelope side.
            final double min = Math.max(0.0, Princeps.settings().microJitterMinDegrees.value);
            final double max = Math.max(min, Princeps.settings().microJitterMaxDegrees.value);
            this.jitterRampTicks = 1 + this.jitterRng.nextInt(3);
            this.jitterTicksLeft = this.jitterRampTicks * 2;
            this.jitterYawAmp = (float) ((min + this.jitterRng.nextDouble() * (max - min))
                    * (this.jitterRng.nextBoolean() ? 1.0 : -1.0));
            this.jitterPitchAmp = (float) ((min + this.jitterRng.nextDouble() * (max - min))
                    * (this.jitterRng.nextBoolean() ? 1.0 : -1.0));
            this.jitterCooldown = JITTER_COOLDOWN_MIN + this.jitterRng.nextInt(JITTER_COOLDOWN_SPAN);
        }

        // Advance the envelope: t rises 1..2r; out-leg reaches the full amplitude at t == r, the back-leg
        // returns to exactly 0 at t == 2r (the last tick of every excursion sends the clean rotation again).
        this.jitterTicksLeft--;
        final int t = this.jitterRampTicks * 2 - this.jitterTicksLeft;
        final float env = t <= this.jitterRampTicks
                ? (float) t / this.jitterRampTicks
                : (float) (this.jitterRampTicks * 2 - t) / this.jitterRampTicks;
        final float grid = this.processor.minAngleChange();
        this.jitterYawOffset = Math.round(this.jitterYawAmp * env / grid) * grid;
        this.jitterPitchOffset = Math.round(this.jitterPitchAmp * env / grid) * grid;
    }

    /** All conditions under which the micro-jitter may run: calm straight flat-walking cruise, nothing else. */
    private boolean jitterEligible() {
        if (!Princeps.settings().microJitter.value || !Princeps.settings().humanizedLook.value) {
            return false;
        }
        // Only plain CLIENT-mode cruise targets; precise/break aims keep their exact facing.
        if (this.target == null || this.target.mode != Target.Mode.CLIENT
                || this.target.precise || this.target.intent == AimIntent.BREAK) {
            return false;
        }
        if (this.jitterStraightTicks < JITTER_MIN_STRAIGHT_TICKS) {
            return false;
        }
        // Grounded walking only — never airborne, swimming or gliding (elytra has its own smoothing, and a
        // rotation the physics don't see is exactly the desync flight anticheats look for).
        if (!ctx.player().onGround() || ctx.player().isFallFlying() || ctx.player().isInWater()) {
            return false;
        }
        final IElytraProcess elytra = princeps.getElytraProcess();
        if (elytra != null && elytra.isActive()) {
            return false;
        }
        // No jump/break/place commanded this tick (also catches bridging and pillar launches).
        if (princeps.getInputOverrideHandler().isInputForcedDown(Input.JUMP)
                || princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                || princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) {
            return false;
        }
        // The current path movement must be a flat walk (straight or diagonal) — every maneuver type
        // (ascend, descend, fall, parkour, pillar, ...) is excluded, including the ticks leading into it.
        final PathExecutor exec = princeps.getPathingBehavior().getCurrent();
        if (exec == null || exec.getPath() == null) {
            return false;
        }
        final IPath path = exec.getPath();
        final int pos = exec.getPosition();
        final List<IMovement> movements = path.movements();
        if (pos < 0 || pos >= movements.size()) {
            return false;
        }
        final IMovement movement = movements.get(pos);
        return movement instanceof MovementTraverse
                || movement instanceof MovementDiagonal
                || movement instanceof SmoothTraverse;
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        final ServerboundMovePlayerPacket packet = (ServerboundMovePlayerPacket) event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket.Rot || packet instanceof ServerboundMovePlayerPacket.PosRot) {
            this.rotationBeforeThat = this.rotationBeforeLast;
            this.rotationBeforeLast = this.previousServerRotation;
            this.previousServerRotation = this.serverRotation;
            this.serverRotation = new Rotation(packet.getYRot(0.0f), packet.getXRot(0.0f));
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        this.serverRotation = null;
        this.previousServerRotation = null;
        this.rotationBeforeLast = null;
        this.rotationBeforeThat = null;
        this.target = null;
        this.appliedRotation = null;
        this.elytraYawResidual = 0.0f;
        this.elytraPitchResidual = 0.0f;
        this.smoothYawBuffer.clear();
        this.smoothPitchBuffer.clear();
    }

    public void pig() {
        if (this.target != null) {
            final Rotation actual = this.appliedRotation != null
                    ? this.appliedRotation
                    : this.processor.peekRotation(this.target.rotation);
            ctx.player().setYRot(actual.getYaw());
        }
    }

    @Override
    public Optional<Rotation> getRotationTheServerHas() {
        return Optional.ofNullable(this.serverRotation);
    }

    @Override
    public Optional<Rotation> getRotationTheServerWillUse() {
        final Rotation now = this.serverRotation;
        final Rotation before = this.previousServerRotation;
        if (now == null || before == null) {
            return Optional.empty();
        }
        // Kopf-Yaw (eine Sendung alt) plus aktueller Pitch -- genau die Mischung, die getViewVector auf dem
        // Server liefert. Siehe ILookBehavior fuer die Messzeile, aus der das abgelesen ist.
        return Optional.of(new Rotation(before.getYaw(), now.getPitch()));
    }

    @Override
    public java.util.List<Rotation> getRotationsTheServerMightUse() {
        final Rotation now = this.serverRotation;
        if (now == null) {
            return java.util.Collections.emptyList();
        }
        // ALLE JUENGSTEN KOPF-KANDIDATEN, nicht nur einer -- weil der Verzug nicht fest bei eins liegt.
        //
        // GEMESSEN, basalt 20260807-2308, Zelle 114,-59,74: der Client hatte denselben Yaw zweimal gesendet, die
        // Ein-Schritt-Mischung war also gleich der aktuellen Rotation und die Pruefung fand keinen Widerspruch.
        // Mit dieser Rotation (129,268 / 41,694) waere das Ergebnis auch richtig gewesen -- |y| = 0,665 gegen
        // |x| = 0,578, also down, also Kolben nach up. Der Server hat trotzdem east gesetzt, also einen Yaw
        // benutzt, der noch weiter zurueckliegt.
        //
        // Statt die Verzugstiefe zu erraten oder auf Ruhe zu warten (das kostete 84 -> 31 Bloecke pro Minute),
        // werden einfach alle jungen Kandidaten geprueft: der Klick geht nur raus, wenn die Platzierung unter
        // JEDEM von ihnen den gewollten Block ergibt. Bei ruhigem Zielen sind sie ohnehin fast gleich und die
        // Pruefung kostet nichts; nur wenn die jüngste Blickgeschichte eine Richtungsgrenze ueberquert, wartet
        // der Klick einen Tick -- und genau dann ist er auch nicht vorhersagbar.
        final java.util.List<Rotation> out = new java.util.ArrayList<>(HEAD_LAG_CANDIDATES);
        for (Rotation yawSource : new Rotation[]{now, this.previousServerRotation, this.rotationBeforeLast,
                this.rotationBeforeThat}) {
            if (yawSource != null) {
                out.add(new Rotation(yawSource.getYaw(), now.getPitch()));
            }
        }
        return out;
    }

    /** Wie weit zurueck ein Kopf-Yaw stammen kann. Vier Sendungen, also drei Ticks Verzug -- gemessen wurde
     *  einer, beobachtet wurde mehr als einer, und jeder weitere Kandidat kostet nur eine Simulation. */
    private static final int HEAD_LAG_CANDIDATES = 4;

    @Override
    public boolean rotationHasSettled() {
        final Rotation now = this.serverRotation;
        final Rotation before = this.previousServerRotation;
        final Rotation earlier = this.rotationBeforeLast;
        if (now == null || before == null || earlier == null) {
            return false;   // noch keine drei gesendeten Rotationen: nichts, worueber man Ruhe behaupten koennte
        }
        // DREI, NICHT ZWEI -- und der dritte kostet einen Tick und rettet die Orientierung.
        //
        // Zwei genuegen fuer den KOERPER: dann ist yRot bei der Ausfuehrung dieselbe wie beim Eintreffen des
        // Klicks. Die Platzierungsrichtung liest vanilla aber nicht aus yRot, sondern ueber
        // LivingEntity.getViewYRot aus yHeadRot -- und der Kopf hinkt dem Koerper einen Tick hinterher, weil er
        // im Entity-Tick nachgezogen wird und nicht beim Verarbeiten des Bewegungspakets.
        //
        // GEMESSEN, basalt 20260807-214131, Zelle 111,-59,121, gewollt sticky_piston[facing=up]. Die
        // Server-Thread-Zeile im Moment der Ausfuehrung:
        //     yaw=127,616  pitch=43,717   headYaw=85,819  viewYRot=85,819  viewXRot=43,717
        // Der Koerper stand also schon auf 127,6 -- genau dem Wert, den der Client geprueft hatte --, der Kopf
        // aber noch auf 85,8. Aus der Mischung (Kopf-Yaw, neuer Pitch) folgt |x|=0,7208 gegen |y|=0,6912, also
        // west, also Kolben nach east. Mit dem Koerper-Yaw waere es |y|=0,6912 gegen |x|=0,5724 gewesen: down,
        // also up, also richtig. Ein einziger Tick Kopfverzug, und der Block steht quer.
        //
        // Drei gleiche gesendete Rotationen heissen: der Kopf hatte einen ganzen Tick Zeit, den Koerper
        // einzuholen. Danach sind yRot, yHeadRot und die gepruefte Rotation dasselbe.
        return sameAngles(now, before) && sameAngles(before, earlier);
    }

    /**
     * Aendert sich der Blick noch SO STARK, dass es die dominante Achse kippen koennte?
     *
     * <p>DIE ERSTE FASSUNG VERLANGTE BIT-GLEICHHEIT, und das war ein Denkfehler mit Messbeleg. Der Blick kommt
     * nie exakt zur Ruhe: der Look-Prozessor faehrt die letzte Annaeherung aus, gemessen im basalt-Lauf
     * 20260807-2154 als {@code look=319.96,17.95} gefolgt von {@code 17.98} -- drei Hundertstel Grad Restdrift
     * pro Tick. Die Bedingung traf damit NIE zu, der Bauer meldete den Klick gar nicht erst an, und der Bau stand
     * ab Zelle 184 endlos still. Nicht einmal der Rueckhalte-Zaehler sah es, weil zum Zurueckhalten nie ein Klick
     * angemeldet wurde: Stillstand ohne Spur, die schlechteste Sorte.
     *
     * <p>Die richtige Frage ist nicht "unveraendert", sondern "veraendert sich um weniger, als kippen kann". Der
     * Schaden, gegen den diese Bedingung existiert, war ein Kopfverzug von 85,8 auf 127,6 Grad -- zweiundvierzig
     * Grad. Drei Hundertstel sind es nicht. Ein halbes Grad Toleranz verschiebt die waagerechten Komponenten des
     * Blickvektors um rund ein Prozent und liegt damit weit unter der Dominanz-Marge von fuenfzehn Prozent, die
     * ein angenommener Zielpunkt ohnehin einhalten muss.
     *
     * <p>Yaw wird ueber die kuerzeste Distanz verglichen, nicht roh: der Client wickelt ihn nie zurueck und
     * sammelt ueber einen Lauf bis -630 Grad, ein roher Vergleich meldete also Bewegung, wo keine ist.
     */
    private static boolean sameAngles(Rotation a, Rotation b) {
        return Math.abs(Rotation.normalizeYaw(a.getYaw() - b.getYaw())) <= SETTLE_TOLERANCE_DEGREES
                && Math.abs(a.getPitch() - b.getPitch()) <= SETTLE_TOLERANCE_DEGREES;
    }

    /**
     * Wie viel Restbewegung eine "ruhige" Rotation haben darf, in Grad.
     *
     * <p>Ein halbes Grad. Die Restdrift des Look-Prozessors betraegt gemessen drei Hundertstel je Tick, der
     * Schadensfall betrug zweiundvierzig Grad -- dazwischen ist viel Platz, und diese Zahl liegt naeher an der
     * Drift als am Schaden.
     */
    private static final float SETTLE_TOLERANCE_DEGREES = 0.5F;

    private Rotation previousServerRotation;
    private Rotation rotationBeforeLast;
    private Rotation rotationBeforeThat;

    public Optional<Rotation> getEffectiveRotation() {
        if (Princeps.settings().freeLook.value) {
            return Optional.ofNullable(this.serverRotation);
        }
        // If freeLook isn't on, just defer to the player's actual rotations
        return Optional.empty();
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        if (this.target == null) {
            return;
        }
        // During elytra flight the movement/thrust MUST use the same rotation PRE already applied to the
        // player — the low-passed value the movement packet also sends. Overriding it here with the raw
        // steering target would make the physics glide along the un-smoothed angle while the packet reports
        // the smoothed one: a move-direction-vs-look-direction desync that elytra anticheats flag. So while
        // fall-flying we leave the event at its default (the applied, low-passed rotation) and only steer the
        // move for non-elytra targets.
        if (ctx.player().isFallFlying()) {
            return;
        }
        // Reuse the exact rotation PRE applied this tick — movement == look, ONE turn step per tick. Re-peeking
        // here read the already-advanced live rotation as prev and took a second step (see appliedRotation).
        final Rotation actual = this.appliedRotation != null
                ? this.appliedRotation
                : this.processor.peekRotation(this.target.rotation);
        event.setYaw(actual.getYaw());
        event.setPitch(actual.getPitch());
    }

    private static final class AimProcessor extends AbstractAimProcessor {

        public AimProcessor(final IPlayerContext ctx) {
            super(ctx);
        }

        @Override
        protected Rotation getPrevRotation() {
            // Implementation will use LookBehavior.serverRotation
            return ctx.playerRotations();
        }
    }

    private static abstract class AbstractAimProcessor implements ITickableAimProcessor {

        protected final IPlayerContext ctx;
        private final ForkableRandom rand;
        private double randomYawOffset;
        private double randomPitchOffset;
        // Humanized-look state: mean-reverting yaw/pitch wander + the current precise-phase gate.
        private double ouYaw;          // current cruising fixation offset (yaw)
        private double ouPitch;        // current cruising fixation offset (pitch)
        private double ouYawTarget;    // fixation the current saccade is easing toward
        private double ouPitchTarget;
        private int dwellTicks;        // ticks left holding this fixation before the next saccade
        private double tremorYaw;      // always-on micro-tremor (BOTH cruising and precise apply — never predictions)
        private double tremorPitch;
        private boolean precise;
        private boolean capPreciseTurn; // when set, speed-limit the turn even during a precise BREAK (mining arc)
        private boolean deterministicPrecise; // explicit V3 target: no sampled tremor or curve variance reaches it
        private boolean smoothAirborne; // a plain fall/descent — use the smooth rate-limited turn, not the exact aim
        // Bell-curve mining aim state (humanizedLookAimCurve): current angular speed of the arc + the tick stamps
        // that detect "a fresh arc started" (reset speed to 0 = ease-in) and guard the once-per-tick advance.
        private double curveVel;       // current head-turn speed of the running arc (deg/tick)
        private long tickCount;        // advanced once per tick() — forks replay it deterministically via advance()
        private long curveTickStamp = Long.MIN_VALUE; // last tickCount the curve advanced (gap > 1 tick = fresh arc)
        // The desired rotation the curve last steered toward: a significant CHANGE while precise (a new aim point
        // on the block, or a new block) restarts the ease-in, so a re-aim is never ridden at full plateau speed.
        private float lastCurveDesiredYaw = Float.NaN;
        private float lastCurveDesiredPitch;
        // Execution-only variance RNG: NEVER drawn from this.rand — the forked solver replays this.rand via tick()
        // and consuming it here would desync its place-predictions from reality. The curve only shapes the APPLY
        // path (predictions use peekRotationExact), so untracked randomness is safe here, like BlockBreakHelper's.
        private final java.util.Random curveRng = new java.util.Random();
        private static final double SACCADE_EASE = 0.28; // fraction toward the fixation per tick (~4-tick flick)
        // A precise-aim TARGET change beyond this (deg, yaw/pitch hypot) restarts the curve's ease-in. Above the
        // per-tick drift of wander/tremor, below any real point-to-point re-aim on a block face.
        private static final double REAIM_FRESH_DEGREES = 2.5;
        private static final double TREMOR_THETA = 0.35; // fast reversion → high-freq hand micro-jitter
        private static final double TREMOR_PITCH_RATIO = 0.70; // pitch tremor as a fraction of yaw tremor

        public AbstractAimProcessor(IPlayerContext ctx) {
            this.ctx = ctx;
            this.rand = new ForkableRandom();
        }

        private AbstractAimProcessor(final AbstractAimProcessor source) {
            this.ctx = source.ctx;
            this.rand = source.rand.fork();
            this.randomYawOffset = source.randomYawOffset;
            this.randomPitchOffset = source.randomPitchOffset;
            this.ouYaw = source.ouYaw;
            this.ouPitch = source.ouPitch;
            this.ouYawTarget = source.ouYawTarget;
            this.ouPitchTarget = source.ouPitchTarget;
            this.dwellTicks = source.dwellTicks;
            this.tremorYaw = source.tremorYaw;
            this.tremorPitch = source.tremorPitch;
            this.precise = source.precise;
            this.capPreciseTurn = source.capPreciseTurn;
            this.deterministicPrecise = source.deterministicPrecise;
            this.smoothAirborne = source.smoothAirborne;
            this.curveVel = source.curveVel;
            this.tickCount = source.tickCount;
            this.curveTickStamp = source.curveTickStamp;
            this.lastCurveDesiredYaw = source.lastCurveDesiredYaw;
            this.lastCurveDesiredPitch = source.lastCurveDesiredPitch;
        }

        final void setPrecise(final boolean precise) {
            this.precise = precise;
        }

        final void setCapPreciseTurn(final boolean capPreciseTurn) {
            this.capPreciseTurn = capPreciseTurn;
        }

        final void setDeterministicPrecise(final boolean deterministicPrecise) {
            this.deterministicPrecise = deterministicPrecise;
        }

        final void setSmoothAirborne(final boolean smoothAirborne) {
            this.smoothAirborne = smoothAirborne;
        }

        @Override
        public final Rotation peekRotation(final Rotation rotation) {
            return this.peekRotationInternal(rotation, false);
        }

        @Override
        public final Rotation peekRotationExact(final Rotation rotation) {
            // Reach/place PREDICTIONS must model the EXACT rotation the (always-precise) break/place is applied at,
            // never the cosmetic cruising wander — otherwise the prediction disagrees with reality and a reach/place
            // raytrace flips hit<->miss around the ~3° wander.
            return this.peekRotationInternal(rotation, true);
        }

        private Rotation peekRotationInternal(final Rotation rotation, final boolean forceExact) {
            final Rotation prev = this.getPrevRotation();

            float desiredYaw = rotation.getYaw();
            float desiredPitch = rotation.getPitch();

            // "The caller doesn't care about the pitch, it just passed playerRotations().getPitch() through" is a
            // GUESS, and equal pitches are the only evidence for it. That evidence is worthless for an aim that means
            // its pitch, because an arrived aim ALWAYS has desiredPitch == prev: the guess is therefore wrong exactly
            // when the aim is right. It cost a whole excavation. The entry aim looks straight down; the moment the
            // head reached 90 degrees this rewrote the PREDICTED pitch into the walking band (and the profile's
            // 180-degree nudge step makes that one hop, not a drift), so every predicted reach ray flew out over the
            // roof and missed. The break site only publishes its target once that prediction hits, so the target was
            // never published, the head never turned, and the bot stood on a block it was correctly aiming at for the
            // rest of the run. See mayNudgePitchToLevel: the nudge belongs to pitch-agnostic CRUISING only.
            if (mayNudgePitchToLevel(forceExact, this.precise, desiredPitch, prev.getPitch())) {
                desiredPitch = nudgeToLevel(desiredPitch);
            }

            final boolean airborne = this.ctx.player() != null
                    && (!this.ctx.player().onGround() || this.ctx.player().isFallFlying());
            if (!forceExact && Princeps.settings().humanizedLook.value) {
                // Always-on micro-tremor, applied on BOTH the cruising and the precise APPLY path (never on the
                // peekRotationExact predictions). A real hand is never bit-exactly still: without this the yaw/pitch
                // freeze to a constant the instant a break/place starts and the noise floor collapses to exactly 0 —
                // a bimodal "silence keyed to interactions" tell. Bounded < ~0.5° so at reach distance the aim moves
                // < 0.04 blocks and never leaves the target face → reach/place hit<->miss is unchanged (predictions
                // stay exact) and the break's crosshair stays on the target block.
                // While a precise block interaction holds, the hand STEADIES: scale the tremor down (default 1/10,
                // user spec — the full cruising tremor reads as nervous first-person drift while mining). Kept
                // nonzero so the mid-interaction noise floor never collapses to exactly 0.
                final double tremorScale = this.precise
                        ? Math.max(0.0, Princeps.settings().humanizedBreakTremorScale.value) : 1.0;
                if (!this.deterministicPrecise) {
                    desiredYaw += (float) (this.tremorYaw * tremorScale);
                    desiredPitch += (float) (this.tremorPitch * tremorScale);
                }

                final boolean cruising = !this.precise && !airborne;
                // A SMOOTH, tightly rate-limited turn is used for cruising, declared break/place interaction arcs
                // (capPreciseTurn), and a plain fall/descent (smoothAirborne). In all three the SENT head
                // movement is hard-capped at humanizedLookMaxCruiseYaw deg horizontally / MaxCruisePitch deg
                // vertically per tick, so following the (blocky) path and easing DOWN into a drop look super smooth
                // instead of snapping. The pursuit octant feet-steer keeps 100% node coverage under the slow head
                // turn (bench-verified). The EXACT instant aim below is kept only where the heading must be exact
                // THIS tick: an un-capped precise break (crosshair on the block for BlockBreakHelper), a jump launch
                // (ballistic heading), or elytra.
                if (cruising || (this.capPreciseTurn && !airborne) || this.smoothAirborne) {
                    if (cruising) {
                        // saccadic wander, faded out during an active turn (>20°) so it never fights the corner
                        final float rawYawErr = Math.abs(Mth.degreesDifference(prev.getYaw(), desiredYaw));
                        final double wanderScale = rawYawErr <= 20.0f ? 1.0 : Math.max(0.0, 1.0 - (rawYawErr - 20.0) / 25.0);
                        desiredYaw += (float) (this.ouYaw * wanderScale);
                        desiredPitch += (float) (this.ouPitch * wanderScale);
                    }
                    // BELL-CURVE interaction aim: this.precise inside this branch implies a declared break/place path
                    // (cruising and smoothAirborne both require !precise). Instead of jumping straight to the flat
                    // rate limit (a 0->9 velocity kick in one tick — the "flick"), the head eases IN, rides the
                    // mode's peak, and eases OUT into the block. The dig itself still only fires once the live
                    // crosshair raytrace lands on the target (BlockBreakHelper), so hit/miss is untouched.
                    // Bell-curve turn for EVERY smooth head movement — cruising and fall/descent too, not just
                    // the mining break-cap. The head eases IN (peak/3 accel), rides the mode peak (9 deg/tick),
                    // and eases OUT onto the target, hard-capped at the peak: the user's acceleration profile
                    // applied to ALL camera motion, so a turn never snaps and never exceeds 9 deg/tick. The
                    // proportional ease-out lands ON the target without overshoot, so a correction after a drop
                    // no longer orbits the line.
                    if (Princeps.settings().humanizedLookAimCurve.value) {
                        return this.aimCurveTurn(prev, desiredYaw, desiredPitch);
                    }
                    // proportional ease-out toward the target, then the tight per-tick smoothness cap
                    final double maxStep = Princeps.settings().humanizedLookTurnMaxSpeed.value;
                    final float capY = (float) Math.max(1.0, Princeps.settings().humanizedLookMaxCruiseYaw.value);
                    final float capP = (float) Math.max(1.0, Princeps.settings().humanizedLookMaxCruisePitch.value);
                    final float stepY = Mth.clamp(
                            proportionalStep(Mth.degreesDifference(prev.getYaw(), desiredYaw), maxStep), -capY, capY);
                    final float stepP = Mth.clamp(
                            proportionalStep(desiredPitch - prev.getPitch(), maxStep * 0.6), -capP, capP);
                    return new Rotation(
                            this.calculateMouseMove(prev.getYaw(), prev.getYaw() + stepY),
                            this.calculateMouseMove(prev.getPitch(), prev.getPitch() + stepP)
                    ).clamp();
                }
                // Exact instant aim (an interaction whose curve is disabled, jump launch, elytra): the heading must be
                // exact THIS tick. Only a lone superhuman snap is clamped by the 70°/tick hard cap — a re-aim 90°+
                // to the side or a mid-air retarget flip — so it spreads over a few ticks (the dig fires once
                // isLookingAt catches up), while normal exact aims (<= the ballistic max) pass through unchanged.
                return new Rotation(
                        this.calculateMouseMove(prev.getYaw(), hardCap(prev.getYaw(), desiredYaw, true)),
                        this.calculateMouseMove(prev.getPitch(), hardCap(prev.getPitch(), desiredPitch, false))
                ).clamp();
            }

            // forceExact prediction, or humanized-look off: face the EXACT target with NO tremor so reach/place
            // predictions are byte-identical to what the precise apply lands on. (legacy offsets are 0 while humanized.)
            if (!this.deterministicPrecise) {
                desiredYaw += this.randomYawOffset;
                desiredPitch += this.randomPitchOffset;
            }

            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), desiredYaw),
                    this.calculateMouseMove(prev.getPitch(), desiredPitch)
            ).clamp();
        }

        /**
         * One tick of the bell-curve mining arc: the head's TOTAL angular speed follows ease-in (accelerate by
         * ~peak/3 per tick) → the mode's peak → proportional ease-out into the target, and the step is taken along
         * the straight (yaw,pitch) error vector so the arc is one clean sweep. The velocity advances exactly once
         * per game tick (guarded by {@code tickCount}), so a same-tick re-peek can never double-accelerate; a gap
         * in curve ticks (the arc ended or was interrupted) resets the speed to 0 = the next aim eases in fresh.
         * A per-tick ABSOLUTE jitter (0.01..0.1 deg, user spec) makes the plateau breathe around the mode value
         * (9 -> 8.90..9.10) and keeps the ease-out's mini steps from ever repeating the same number; the soft
         * ceiling is mode + 0.1. Mouse-quantization happens in calculateMouseMove as usual.
         */
        private Rotation aimCurveTurn(final Rotation prev, final float desiredYaw, final float desiredPitch) {
            final float yawErr = Mth.degreesDifference(prev.getYaw(), desiredYaw);
            final float pitchErr = desiredPitch - prev.getPitch();
            final double errMag = Math.hypot(yawErr, pitchErr);
            if (this.curveTickStamp != this.tickCount) {
                final boolean fresh = this.curveTickStamp != this.tickCount - 1;
                this.curveTickStamp = this.tickCount;
                if (fresh) {
                    this.curveVel = 0.0;
                } else if (this.precise && !Float.isNaN(this.lastCurveDesiredYaw)) {
                    // Mid-arc RE-AIM (a new point on the block, or the next block) while the arc velocity rides the
                    // plateau: without a reset the whole jump lands in ONE tick (step = min(err, vel) with vel at
                    // peak — the "hard flick" seen while digging down). Treat a significant target change as a fresh
                    // arc: ease in again (~peak/3 accel), so every re-aim spreads over >= 3 ticks like a human's.
                    final double switchMag = Math.hypot(
                            Mth.degreesDifference(this.lastCurveDesiredYaw, desiredYaw),
                            desiredPitch - this.lastCurveDesiredPitch);
                    if (switchMag > REAIM_FRESH_DEGREES) {
                        this.curveVel = 0.0;
                    }
                }
                this.lastCurveDesiredYaw = desiredYaw;
                this.lastCurveDesiredPitch = desiredPitch;
                final double peak = aimCurvePeak();
                double v = aimCurveNextVel(this.curveVel, errMag, peak, aimCurveAccel());
                // Per-tick ABSOLUTE jitter, magnitude 0.01..0.1 deg (user spec): the plateau BREATHES around the
                // mode value (9 -> 8.90..9.10) instead of stagnating on the identical number tick after tick, and
                // the ease-out's mini steps are never numerically the same twice. Soft ceiling = mode + 0.1 (the
                // user's own example: 9.09 is fine at mode 9). Also spreads the first arc step (~peak/3 +- jitter),
                // removing the constant-first-step histogram spike flagged in the mining-session audit.
                if (!this.deterministicPrecise) {
                    final double jitterMag = 0.01 + this.curveRng.nextDouble() * 0.09;
                    v += this.curveRng.nextBoolean() ? jitterMag : -jitterMag;
                }
                this.curveVel = Math.max(0.3, Math.min(v, peak + 0.1));
            }
            final double step = Math.min(errMag, this.curveVel);
            final double s = errMag > 1e-9 ? step / errMag : 0.0;
            // Per-axis ceilings (live-tunable): the yaw/pitch SHARE of this tick's step never exceeds
            // peak * axisScale. At 1.0/1.0 this changes nothing (a step's component can't exceed the total,
            // which is already capped at the peak); lower values calm one axis — e.g. the wide sideways
            // sweeps while digging straight down — while the other axis keeps its pace.
            final double peakNow = aimCurvePeak();
            final double yawCap = peakNow * Math.max(0.05, Princeps.settings().humanizedLookAimCurveYawScale.value);
            final double pitchCap = peakNow * Math.max(0.05, Princeps.settings().humanizedLookAimCurvePitchScale.value);
            final double stepYaw = Mth.clamp(yawErr * s, -yawCap, yawCap);
            final double stepPitch = Mth.clamp(pitchErr * s, -pitchCap, pitchCap);
            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), prev.getYaw() + (float) stepYaw),
                    this.calculateMouseMove(prev.getPitch(), prev.getPitch() + (float) stepPitch)
            ).clamp();
        }

        @Override
        public final void tick() {
            // Advance the tick stamp FIRST (before any early return): the bell-curve arc keys its once-per-tick
            // velocity update and its fresh-arc detection to this counter, and forks replay it via advance().
            this.tickCount++;
            // No anti-aim wobble while gliding: a real elytra flyer holds a smooth line, so the randomLooking
            // jitter both looks robotic to the server AND is the dominant source of the visible per-tick twitch.
            // Skipping it here (and in the forked solver processor, which shares this method) keeps the simulated
            // and the actually-applied rotation identical.
            if (this.ctx.player() != null && this.ctx.player().isFallFlying()) {
                this.randomYawOffset = 0.0;
                this.randomPitchOffset = 0.0;
                this.ouYaw = 0.0;
                this.ouPitch = 0.0;
                this.tremorYaw = 0.0;
                this.tremorPitch = 0.0;
                return;
            }
            if (Princeps.settings().humanizedLook.value) {
                // Advance the wander + tremor every tick — in tick(), so the forked solver's advance() replays it
                // deterministically and its place-predictions match reality.
                final double maxDrift = Math.max(0.0, Princeps.settings().humanizedLookDriftDegrees.value);
                // Fixation + saccade (replaces the symmetric mean-zero OU, whose smooth mean-reverting spectrum has
                // no saccades and averages to exactly the true heading — itself a tell). A human head HOLDS a small
                // offset for a randomized dwell, then FLICKS to a new one: hold near ouYawTarget for dwellTicks, then
                // ease quickly toward a fresh uniform offset in [-maxDrift, maxDrift]. Bounded by maxDrift so the walk
                // stays inside the path corridor; it is only APPLIED while cruising (see peekRotation).
                if (this.dwellTicks <= 0) {
                    // Minimal-saccade floor (user spec: straight walking should only ever adjust 0.5..~2*drift deg):
                    // redraw until the new fixation differs visibly from the current offset, so a saccade is never a
                    // sub-visible micro twitch. Guarded redraw keeps the rand stream deterministic under fork replay.
                    final double minSaccade = Math.min(0.5, maxDrift);
                    double target = (this.rand.nextDouble() * 2.0 - 1.0) * maxDrift;
                    for (int guard = 0; guard < 8 && Math.abs(target - this.ouYaw) < minSaccade; guard++) {
                        target = (this.rand.nextDouble() * 2.0 - 1.0) * maxDrift;
                    }
                    this.ouYawTarget = target;
                    this.ouPitchTarget = (this.rand.nextDouble() * 2.0 - 1.0) * maxDrift * 0.5;
                    this.dwellTicks = 6 + (int) (this.rand.nextDouble() * 26.0); // hold 6..31 ticks (~0.3–1.6s)
                }
                this.dwellTicks--;
                this.ouYaw += (this.ouYawTarget - this.ouYaw) * SACCADE_EASE;
                this.ouPitch += (this.ouPitchTarget - this.ouPitch) * SACCADE_EASE;
                // Always-on high-frequency micro-tremor (hand jitter) — never zeroed on the ground by default, so
                // emitted rotation is not bit-exactly constant mid-break. BaseHunter can set this to 0 for a fully
                // stable first-person camera while keeping the humanized acceleration curve.
                final double tremorYawScale = Math.max(0.0, Princeps.settings().humanizedLookTremorDegrees.value);
                final double tremorPitchScale = tremorYawScale * TREMOR_PITCH_RATIO;
                this.tremorYaw = clampDrift(
                        this.tremorYaw * (1.0 - TREMOR_THETA) + tremorYawScale * gaussian(),
                        tremorYawScale * 3.0);
                this.tremorPitch = clampDrift(
                        this.tremorPitch * (1.0 - TREMOR_THETA) + tremorPitchScale * gaussian(),
                        tremorPitchScale * 3.0);
                this.randomYawOffset = 0.0;
                this.randomPitchOffset = 0.0;
                return;
            }

            // legacy randomLooking (only when humanizedLook is off)
            this.randomYawOffset = (this.rand.nextDouble() - 0.5) * Princeps.settings().randomLooking.value;
            this.randomPitchOffset = (this.rand.nextDouble() - 0.5) * Princeps.settings().randomLooking.value;

            // legacy randomLooking113
            double random = this.rand.nextDouble() - 0.5;
            if (Math.abs(random) < 0.1) {
                random *= 4;
            }
            this.randomYawOffset += random * Princeps.settings().randomLooking113.value;
        }

        @Override
        public final void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                this.tick();
            }
        }

        @Override
        public Rotation nextRotation(final Rotation rotation) {
            final Rotation actual = this.peekRotation(rotation);
            this.tick();
            return actual;
        }

        @Override
        public final ITickableAimProcessor fork() {
            return new AbstractAimProcessor(this) {

                private Rotation prev = AbstractAimProcessor.this.getPrevRotation();

                @Override
                public Rotation nextRotation(final Rotation rotation) {
                    return (this.prev = super.nextRotation(rotation));
                }

                @Override
                protected Rotation getPrevRotation() {
                    return this.prev;
                }
            };
        }

        protected abstract Rotation getPrevRotation();

        /**
         * Nudges the player's pitch to a regular level, 1 degree per tick. Legacy band [-20, 10]; with humanizedLook
         * the natural WALK-GAZE band (default [6, 12] degrees downward — user spec) applies instead. Only ever runs
         * for pitch-agnostic targets (cruising), so break/place/pearl/elytra/drop aims are never fought.
         */
private float nudgeToLevel(float pitch) {
            float lo = -20.0f, hi = 10.0f;
            if (Princeps.settings().humanizedLook.value) {
                lo = Princeps.settings().humanizedWalkPitchMin.value.floatValue();
                hi = Math.max(lo, Princeps.settings().humanizedWalkPitchMax.value.floatValue());
            }
            final float step = (float) Math.max(0.1, Princeps.settings().humanizedWalkPitchNudgeStep.value);
            if (pitch < lo) {
                return Math.min(lo, pitch + step); // clamp so a big step never overshoots past the band
            } else if (pitch > hi) {
                return Math.max(hi, pitch - step);
            }
            return pitch;
        }

        /** Cheap ~normal noise (mean 0, std ~0.5) from three uniforms — stateless, deterministic under fork(). */
        private double gaussian() {
            return this.rand.nextDouble() + this.rand.nextDouble() + this.rand.nextDouble() - 1.5;
        }

        private static double clampDrift(final double v, final double max) {
            return v < -max ? -max : (v > max ? max : v);
        }

        /**
         * Ballistic ease-out turn step: a fraction (gain) of the remaining error per tick, floored at minStep so the
         * settle completes, ceilinged at maxStep. The outer {@code min(|error|, ...)} overshoot clamp is load-bearing:
         * without it the minStep floor would oscillate ±minStep around the target forever (a self-made weave).
         * Pure function of (error, settings) — no rand — so it is side-effect-free across the multiple peek calls per
         * tick and identical in forked solver predictions.
         */
        /**
         * Absolute per-tick delta ceiling — a packet-validity backstop applied to the SENT rotation only (never to
         * peekRotationExact predictions, which must stay exact for reach/place planning). Normal movement never
         * exceeds the ballistic max, so this only clamps pathological one-tick snaps (un-capped break re-aim, mid-air
         * retarget flip) that would otherwise emit a physically impossible rotation packet.
         */
        private float hardCap(final float prev, final float target, final boolean yaw) {
            final float cap = (float) Math.max(1.0, Princeps.settings().humanizedLookMaxTurnHardCap.value);
            final float delta = yaw ? Mth.degreesDifference(prev, target) : target - prev;
            return prev + Mth.clamp(delta, -cap, cap);
        }

        private static float proportionalStep(final float error, final double maxStep) {
            final double gain = Math.max(0.05, Princeps.settings().humanizedLookTurnGain.value);
            final double minStep = Math.max(0.5, Princeps.settings().humanizedLookTurnMinSpeed.value);
            final double abs = Math.abs(error);
            final double clamped = Math.max(minStep, Math.min(abs * gain, Math.max(minStep, maxStep)));
            return (float) Math.copySign(Math.min(abs, clamped), error);
        }

        private float calculateMouseMove(float current, float target) {
            final float delta = target - current;
            final double deltaPx = angleToMouse(delta); // yes, even the mouse movements use double
            return current + mouseToAngle(deltaPx);
        }

        private double angleToMouse(float angleDelta) {
            final float minAngleChange = mouseToAngle(1);
            return Math.round(angleDelta / minAngleChange);
        }

        /** The smallest rotation change one mouse count produces at the current sensitivity (the quantization step). */
        public final float minAngleChange() {
            return mouseToAngle(1);
        }

        private float mouseToAngle(double mouseDelta) {
            // casting float literals to double gets us the precise values used by mc
            final double f = ctx.minecraft().options.sensitivity().get() * (double) 0.6f + (double) 0.2f;
            return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f; // yes, one double and one float scaling factor
        }
    }

    private static class Target {

        public final Rotation rotation;
        public final Mode mode;
        /** True when the movement needs an EXACT facing (break/place/bridge) — humanized wander is bypassed. */
        public final boolean precise;
        /** The mutually-exclusive interaction this precise aim prepares. */
        public final AimIntent intent;
        /** True only for V3 execution: sampled look variance must not affect this target. */
        public final boolean deterministicIntent;

        public Target(Rotation rotation, Mode mode, boolean precise, AimIntent intent,
                      boolean deterministicIntent) {
            this.rotation = rotation;
            this.mode = mode;
            this.precise = precise;
            this.intent = intent;
            this.deterministicIntent = deterministicIntent;
        }

        enum Mode {
            /**
             * Rotation will be set client-side and is visual to the player
             */
            CLIENT,

            /**
             * Rotation will be set server-side and is silent to the player
             */
            SERVER,

            /**
             * Rotation will remain unaffected on both the client and server
             */
            NONE;

            static Mode resolve(IPlayerContext ctx, boolean blockInteract) {
                final Settings settings = Princeps.settings();
                final boolean antiCheat = settings.antiCheatCompatibility.value;
                final boolean blockFreeLook = settings.blockFreeLook.value;

                if (ctx.player().isFallFlying()) {
                    // always need to set angles while flying
                    return settings.elytraFreeLook.value ? SERVER : CLIENT;
                } else if (settings.freeLook.value) {
                    // Regardless of if antiCheatCompatibility is enabled, if a blockInteract is requested then the player
                    // rotation needs to be set somehow, otherwise Princeps will halt since objectMouseOver() will just be
                    // whatever the player is mousing over visually. Let's just settle for setting it silently.
                    if (blockInteract) {
                        return blockFreeLook ? SERVER : CLIENT;
                    }
                    return antiCheat ? SERVER : NONE;
                }

                // all freeLook settings are disabled so set the angles
                return CLIENT;
            }
        }
    }

    /**
     * Sim-calibrated bell-curve rows: {turnTicks, peakDegPerTick, accelDegPerTick2}. Each completes a 90-degree
     * turn in EXACTLY {@code turnTicks} game ticks with a monotonic accelerate->plateau->decelerate bell (verified
     * in navbench/aim_curve_sim.py). The tick count is KNIFE-EDGE (peak 33 -> 4 ticks, 34 -> 3) — do not replace
     * this table with a smooth formula. The 5 canonical modes: 2 Superfast, 3 Fast, 4 Balanced, 8 Smooth, 12 SuperSmooth.
     */
    private static final double[][] TURN_CAL = {
            {2.0, 45.0, 45.0},
            {3.0, 34.0, 22.7},
            {4.0, 26.0, 13.0},
            {8.0, 15.5, 3.9},
            {12.0, 11.5, 1.9},
    };

    /** Interpolate column {@code col} (1 = peak, 2 = accel) of {@link #TURN_CAL} at {@code turnTicks}, clamped to
     *  the table ends. Exact rows (2/3/4/8/12) return their calibrated value verbatim. */
    private static double turnCal(final double turnTicks, final int col) {
        if (turnTicks <= TURN_CAL[0][0]) return TURN_CAL[0][col];
        final int last = TURN_CAL.length - 1;
        if (turnTicks >= TURN_CAL[last][0]) return TURN_CAL[last][col];
        for (int i = 1; i < TURN_CAL.length; i++) {
            if (turnTicks <= TURN_CAL[i][0]) {
                final double t0 = TURN_CAL[i - 1][0], t1 = TURN_CAL[i][0];
                final double f = (turnTicks - t0) / (t1 - t0);
                return TURN_CAL[i - 1][col] + f * (TURN_CAL[i][col] - TURN_CAL[i - 1][col]);
            }
        }
        return TURN_CAL[last][col];
    }

    /** Peak head-turn speed (deg/tick) for the current {@code humanizedLookAimCurveTurnTicks} mode, times the fine
     *  peakScale (1.0 keeps the exact per-mode tick count). */
    static double aimCurvePeak() {
        final double ticks = aimCurveTurnTicks();
        return turnCal(ticks, 1) * Math.max(0.1, Princeps.settings().humanizedLookAimCurvePeakScale.value);
    }

    /**
     * Ticks-to-90-degrees for this tick's turn: whatever asked for a specific head speed this tick, else the global
     * setting.
     *
     * <p>Set by {@link #requestAimCurveTurnTicks(double)} and cleared in the same POST phase that invalidates the
     * look target, so an override lives exactly one game tick. That is deliberate: the alternative — writing the
     * global setting on build start and restoring it on stop — leaves the owner walking at build speed for ever if
     * any exit path is missed, and this engine has already been bitten once by a value that outlived its owner.
     * Nothing to restore means nothing to leak.
     */
    private static double aimCurveTurnTicks() {
        double override = turnTicksOverride;
        return Math.max(1.0, override > 0.0 ? override : Princeps.settings().humanizedLookAimCurveTurnTicks.value);
    }

    /** Ask for a specific head-turn speed for THIS tick only. Re-assert it every tick for as long as it should
     *  apply; stop asserting it and the global setting takes over on the next one. */
    public static void requestAimCurveTurnTicks(double ticksPerNinetyDegrees) {
        turnTicksOverride = ticksPerNinetyDegrees;
    }

    /** Zero means "nobody asked this tick". Static because {@link #aimCurvePeak()} and {@link #aimCurveAccel()} are,
     *  and they already read global mutable settings the same way. */
    private static double turnTicksOverride;

    /** Ease-in/out acceleration (deg/tick^2) paired with {@link #aimCurvePeak()} so the bell shape (and the exact
     *  ticks-per-90deg) hold; scaled by the same peakScale so the profile stays self-consistent. */
    static double aimCurveAccel() {
        final double ticks = aimCurveTurnTicks();
        return turnCal(ticks, 2) * Math.max(0.1, Princeps.settings().humanizedLookAimCurvePeakScale.value);
    }

    /**
     * Whether the pitch-agnostic walking nudge may touch this aim at all.
     *
     * <p>Static and free of game state so the rule itself can be pinned by a test: the two ways an aim declares
     * that its pitch is load-bearing are an exact prediction ({@code forceExact}, which reach and placement
     * planning ray-trace against) and a precise interaction ({@code precise}, a break/place/bridge facing). In
     * both, moving the pitch towards the walking band changes where the aim points, and it does so only after
     * the aim has arrived, which makes the damage invisible until something downstream stalls forever.
     */
    static boolean mayNudgePitchToLevel(boolean forceExact, boolean precise, float desiredPitch,
                                        float prevPitch) {
        return !forceExact && !precise && desiredPitch == prevPitch;
    }

    /**
     * PURE core of the bell-curve mining aim (unit-tested; no settings, no MC): the next angular speed of the arc.
     * <pre>
     *   rise = min(vPrev + peak/3, peak)          ease-IN: 0 -> peak in ~3 ticks (the fast rise of the bell)
     *   tail = max(max(0.9, peak/8), err * 0.45)  ease-OUT: proportional deceleration with a floor (no end-crawl)
     *   v    = min(rise, tail)
     * </pre>
     * Yields the user's right-skewed bell: fast rise, plateau at the mode peak while the error is large, then a
     * longer proportional tail into the target. Sim-validated in navbench/aim_curve_sim.py: peak acceleration is
     * peak/3 per tick^2 (3x below the old flat rate-limit's 0->peak kick, 30x below the exact-aim snap), the mode
     * ceiling is never exceeded, and tremor-sized (<=0.5 deg) corrections step sub-degree so the crosshair never
     * leaves the block face mid-break.
     */
    static double aimCurveNextVel(final double vPrev, final double errMag, final double peakDegPerTick,
                                  final double accelDegPerTick2) {
        final double peak = Math.max(0.5, peakDegPerTick);
        final double accel = Math.max(0.1, accelDegPerTick2);
        // Ease-IN: accelerate by `accel`/tick up to the mode peak (the fast rise of the bell).
        final double rise = Math.min(vPrev + accel, peak);
        // Ease-OUT: a CONSTANT-deceleration envelope v <= sqrt(2*accel*err). Unlike the old proportional tail
        // (which dragged the last ~18deg over 5-6 ticks and made every mode feel washed-out), this decel scales
        // WITH the tick budget, so a 90deg turn lands in exactly the mode's turnTicks. The caller clamps to
        // [0.3, peak+0.1], so the ~0.3 floor prevents an end-crawl.
        final double stop = Math.sqrt(2.0 * accel * Math.max(0.0, errMag));
        return Math.min(rise, stop);
    }
}
