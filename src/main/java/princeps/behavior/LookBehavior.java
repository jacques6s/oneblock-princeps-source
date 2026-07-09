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
import princeps.api.behavior.look.IAimProcessor;
import princeps.api.behavior.look.ITickableAimProcessor;
import princeps.api.process.IElytraProcess;
import princeps.api.event.events.*;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;
import princeps.behavior.look.ForkableRandom;
import princeps.flownav.FlowCam;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

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
        this.updateTarget(rotation, blockInteract, false);
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent) {
        // blockInteract == "this movement needs an EXACT facing" (break/place/bridge) → carry it as the precise
        // flag so the humanized-look filter hard-bypasses its wander for this target. breakIntent additionally
        // marks BREAK aims (never place/use) so the bell-curve mining arc can engage from the FIRST aim tick —
        // the CLICK_LEFT input can't signal that, because break sites press only after the crosshair arrived.
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), blockInteract, breakIntent);
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
            FlowCam.stop(); // not steering the view: let the camera fall through to vanilla
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
                // for arrival) and the first-aim snap ("flick") would survive. Place/use aims never set breakIntent
                // and never force CLICK_LEFT, so they keep the exact 1-tick aim (bridging timing untouched).
                final boolean breaking = princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                        || this.target.breakIntent;
                this.processor.setCapPreciseTurn(
                        Princeps.settings().humanizedLook.value
                                && Princeps.settings().humanizedLookCapBreakTurn.value
                                && breaking && !jumpLaunch);
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
                    final IElytraProcess elytraProc = princeps.getElytraProcess();
                    final double smoothness = (elytraProc != null && elytraProc.isLanding())
                            ? Princeps.settings().elytraLandingSmoothness.value
                            : Princeps.settings().elytraSmoothness.value;
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
                    ctx.player().setYRot(actual.getYaw());
                    ctx.player().setXRot(actual.getPitch());
                    // not flying: drop any carried elytra residual so the next flight starts fresh
                    this.elytraYawResidual = 0.0f;
                    this.elytraPitchResidual = 0.0f;
                }
                this.appliedRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                // FlowNav: record this tick's applied view so the camera interpolates it across frames.
                // Only in CLIENT mode, where the applied rotation persists visually — a SERVER-mode
                // rotation is restored in POST, and interpolating toward it would drag the free camera.
                if (this.target.mode == Target.Mode.CLIENT) {
                    FlowCam.push(ctx.player(), ctx.player().getYRot(), ctx.player().getXRot());
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
                // The target is done being used for this game tick, so it can be invalidated
                this.target = null;
                this.appliedRotation = null;
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        final ServerboundMovePlayerPacket packet = (ServerboundMovePlayerPacket) event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket.Rot || packet instanceof ServerboundMovePlayerPacket.PosRot) {
            this.serverRotation = new Rotation(packet.getYRot(0.0f), packet.getXRot(0.0f));
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        this.serverRotation = null;
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
        private boolean smoothAirborne; // a plain fall/descent — use the smooth rate-limited turn, not the exact aim
        // Bell-curve mining aim state (humanizedLookAimCurve): current angular speed of the arc + the tick stamps
        // that detect "a fresh arc started" (reset speed to 0 = ease-in) and guard the once-per-tick advance.
        private double curveVel;       // current head-turn speed of the running arc (deg/tick)
        private long tickCount;        // advanced once per tick() — forks replay it deterministically via advance()
        private long curveTickStamp = Long.MIN_VALUE; // last tickCount the curve advanced (gap > 1 tick = fresh arc)
        // Execution-only variance RNG: NEVER drawn from this.rand — the forked solver replays this.rand via tick()
        // and consuming it here would desync its place-predictions from reality. The curve only shapes the APPLY
        // path (predictions use peekRotationExact), so untracked randomness is safe here, like BlockBreakHelper's.
        private final java.util.Random curveRng = new java.util.Random();
        private static final double SACCADE_EASE = 0.28; // fraction toward the fixation per tick (~4-tick flick)
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
            this.smoothAirborne = source.smoothAirborne;
            this.curveVel = source.curveVel;
            this.tickCount = source.tickCount;
            this.curveTickStamp = source.curveTickStamp;
        }

        final void setPrecise(final boolean precise) {
            this.precise = precise;
        }

        final void setCapPreciseTurn(final boolean capPreciseTurn) {
            this.capPreciseTurn = capPreciseTurn;
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

            // In other words, the target doesn't care about the pitch, so it used playerRotations().getPitch()
            // and it's safe to adjust it to a normal level
            if (desiredPitch == prev.getPitch()) {
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
                desiredYaw += (float) (this.tremorYaw * tremorScale);
                desiredPitch += (float) (this.tremorPitch * tremorScale);

                final boolean cruising = !this.precise && !airborne;
                // A SMOOTH, tightly rate-limited turn is used for cruising, for the base-hunt break-corner arc
                // (capPreciseTurn), and for a plain fall/descent (smoothAirborne). In all three the SENT head
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
                    // BELL-CURVE mining aim: this.precise inside this branch implies the capPreciseTurn break path
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
                // Exact instant aim (un-capped precise break, jump launch, elytra): the crosshair/heading must be
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
            desiredYaw += this.randomYawOffset;
            desiredPitch += this.randomPitchOffset;

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
                }
                final double peak = aimCurvePeak();
                double v = aimCurveNextVel(this.curveVel, errMag, peak);
                // Per-tick ABSOLUTE jitter, magnitude 0.01..0.1 deg (user spec): the plateau BREATHES around the
                // mode value (9 -> 8.90..9.10) instead of stagnating on the identical number tick after tick, and
                // the ease-out's mini steps are never numerically the same twice. Soft ceiling = mode + 0.1 (the
                // user's own example: 9.09 is fine at mode 9). Also spreads the first arc step (~peak/3 +- jitter),
                // removing the constant-first-step histogram spike flagged in the mining-session audit.
                final double jitterMag = 0.01 + this.curveRng.nextDouble() * 0.09;
                v += this.curveRng.nextBoolean() ? jitterMag : -jitterMag;
                this.curveVel = Math.max(0.3, Math.min(v, peak + 0.1));
            }
            final double step = Math.min(errMag, this.curveVel);
            final double s = errMag > 1e-9 ? step / errMag : 0.0;
            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), prev.getYaw() + (float) (yawErr * s)),
                    this.calculateMouseMove(prev.getPitch(), prev.getPitch() + (float) (pitchErr * s))
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
            if (pitch < lo) {
                return pitch + 1;
            } else if (pitch > hi) {
                return pitch - 1;
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
        /** True when this precise aim targets a block about to be BROKEN — eligible for the bell-curve arc. */
        public final boolean breakIntent;

        public Target(Rotation rotation, Mode mode, boolean precise, boolean breakIntent) {
            this.rotation = rotation;
            this.mode = mode;
            this.precise = precise;
            this.breakIntent = breakIntent;
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

    /** The bell-curve mode's peak head-turn speed (deg/tick): 0 = superSmooth (5), 1 = standard (9), 2 = fast (20). */
    static double aimCurvePeak() {
        final int mode = Princeps.settings().humanizedLookAimCurveMode.value;
        if (mode <= 0) {
            return 5.0;
        }
        if (mode >= 2) {
            return 20.0;
        }
        return 9.0;
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
    static double aimCurveNextVel(final double vPrev, final double errMag, final double peakDegPerTick) {
        final double peak = Math.max(0.5, peakDegPerTick);
        final double accel = Math.max(0.5, peak / 3.0);
        final double gain = 0.45;
        final double tailMin = Math.max(0.9, peak / 8.0);
        final double rise = Math.min(vPrev + accel, peak);
        final double tail = Math.max(tailMin, errMag * gain);
        return Math.min(rise, tail);
    }
}
