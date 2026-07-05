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
        // blockInteract == "this movement needs an EXACT facing" (break/place/bridge) → carry it as the precise
        // flag so the humanized-look filter hard-bypasses its wander for this target.
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), blockInteract);
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
                // Optionally arc the head through a mining corner instead of snapping: cap the turn even while a
                // BREAK is forced (CLICK_LEFT), on the ground, and not launching a jump (a jump must fly its exact
                // heading). Off by default (normal precise stays a 1-tick exact aim); the Base Hunter turns it on.
                final boolean breaking = princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT);
                this.processor.setCapPreciseTurn(
                        Princeps.settings().humanizedLook.value
                                && Princeps.settings().humanizedLookCapBreakTurn.value
                                && breaking && !jumpLaunch);
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
                    ctx.player().setYRot(curYaw + Mth.degreesDifference(curYaw, actual.getYaw()) * a);
                    ctx.player().setXRot(curPitch + (actual.getPitch() - curPitch) * a);
                } else {
                    ctx.player().setYRot(actual.getYaw());
                    ctx.player().setXRot(actual.getPitch());
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
                    } else if (!ctx.player().isFallFlying() && Princeps.settings().smoothLook.value) {
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
                    //ctx.player().xRotO = prevRotation.getPitch();
                    //ctx.player().yRotO = prevRotation.getYaw();
                    this.prevRotation = null;
                }
                // The target is done being used for this game tick, so it can be invalidated
                this.target = null;
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
        this.smoothYawBuffer.clear();
        this.smoothPitchBuffer.clear();
    }

    public void pig() {
        if (this.target != null) {
            final Rotation actual = this.processor.peekRotation(this.target.rotation);
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
        final Rotation actual = this.processor.peekRotation(this.target.rotation);
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
        private static final double SACCADE_EASE = 0.28; // fraction toward the fixation per tick (~4-tick flick)
        private static final double TREMOR_THETA = 0.35; // fast reversion → high-freq hand micro-jitter
        private static final double TREMOR_YAW = 0.14;   // yaw micro-tremor scale (deg); clamped to 3x
        private static final double TREMOR_PITCH = 0.10;

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
        }

        final void setPrecise(final boolean precise) {
            this.precise = precise;
        }

        final void setCapPreciseTurn(final boolean capPreciseTurn) {
            this.capPreciseTurn = capPreciseTurn;
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
                desiredYaw += (float) this.tremorYaw;
                desiredPitch += (float) this.tremorPitch;
                if (!this.precise && !airborne) {
                    // Cruising: add the fixation/saccade wander, then SPEED-LIMIT the per-tick turn so a corner is a
                    // short human arc rather than a one-tick superhuman flick. Fed through calculateMouseMove below so
                    // the emitted delta is still an integer number of mouse counts (never an impossible float angle).
                    desiredYaw += (float) this.ouYaw;
                    desiredPitch += (float) this.ouPitch;
                    final float maxTurn = (float) Math.max(1.0, Princeps.settings().humanizedLookTurnSpeed.value);
                    final float cappedYaw = prev.getYaw()
                            + Mth.clamp(Mth.degreesDifference(prev.getYaw(), desiredYaw), -maxTurn, maxTurn);
                    final float cappedPitch = prev.getPitch()
                            + Mth.clamp(desiredPitch - prev.getPitch(), -maxTurn, maxTurn);
                    return new Rotation(
                            this.calculateMouseMove(prev.getYaw(), cappedYaw),
                            this.calculateMouseMove(prev.getPitch(), cappedPitch)
                    ).clamp();
                }
                // Precise APPLY (break/place, airborne jump/parkour, elytra): EXACT target + tremor only, no wander.
                // Normally NO turn cap — the crosshair must sit on the target block THIS tick for BlockBreakHelper.
                // Exception (capPreciseTurn, base-hunt only, breaking-on-ground): speed-limit the approach too. The
                // head arcs to the new block over a few ticks and the crosshair breaks the blocks it sweeps across —
                // a natural human mining arc through a corner instead of a 1-tick 90° snap. Safe here because the dig
                // simply lands a few ticks later once the crosshair arrives (isLookingAt gates the actual break); the
                // reach/place PREDICTIONS still use peekRotationExact (uncapped) so pathing plans correctly.
                if (this.capPreciseTurn && !airborne) {
                    final float maxTurn = (float) Math.max(1.0, Princeps.settings().humanizedLookTurnSpeed.value);
                    final float cappedYaw = prev.getYaw()
                            + Mth.clamp(Mth.degreesDifference(prev.getYaw(), desiredYaw), -maxTurn, maxTurn);
                    final float cappedPitch = prev.getPitch()
                            + Mth.clamp(desiredPitch - prev.getPitch(), -maxTurn, maxTurn);
                    return new Rotation(
                            this.calculateMouseMove(prev.getYaw(), cappedYaw),
                            this.calculateMouseMove(prev.getPitch(), cappedPitch)
                    ).clamp();
                }
                return new Rotation(
                        this.calculateMouseMove(prev.getYaw(), desiredYaw),
                        this.calculateMouseMove(prev.getPitch(), desiredPitch)
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

        @Override
        public final void tick() {
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
                    this.ouYawTarget = (this.rand.nextDouble() * 2.0 - 1.0) * maxDrift;
                    this.ouPitchTarget = (this.rand.nextDouble() * 2.0 - 1.0) * maxDrift * 0.5;
                    this.dwellTicks = 6 + (int) (this.rand.nextDouble() * 26.0); // hold 6..31 ticks (~0.3–1.6s)
                }
                this.dwellTicks--;
                this.ouYaw += (this.ouYawTarget - this.ouYaw) * SACCADE_EASE;
                this.ouPitch += (this.ouPitchTarget - this.ouPitch) * SACCADE_EASE;
                // Always-on high-frequency micro-tremor (hand jitter) — never zeroed on the ground, so the emitted
                // rotation is never bit-exactly constant even mid-break. Fast reversion + small scale, clamped to 3x.
                this.tremorYaw = clampDrift(this.tremorYaw * (1.0 - TREMOR_THETA) + TREMOR_YAW * gaussian(), TREMOR_YAW * 3.0);
                this.tremorPitch = clampDrift(this.tremorPitch * (1.0 - TREMOR_THETA) + TREMOR_PITCH * gaussian(), TREMOR_PITCH * 3.0);
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
         * Nudges the player's pitch to a regular level. (Between {@code -20} and {@code 10}, increments are by {@code 1})
         */
        private float nudgeToLevel(float pitch) {
            if (pitch < -20) {
                return pitch + 1;
            } else if (pitch > 10) {
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

        private float calculateMouseMove(float current, float target) {
            final float delta = target - current;
            final double deltaPx = angleToMouse(delta); // yes, even the mouse movements use double
            return current + mouseToAngle(deltaPx);
        }

        private double angleToMouse(float angleDelta) {
            final float minAngleChange = mouseToAngle(1);
            return Math.round(angleDelta / minAngleChange);
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

        public Target(Rotation rotation, Mode mode, boolean precise) {
            this.rotation = rotation;
            this.mode = mode;
            this.precise = precise;
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
}
