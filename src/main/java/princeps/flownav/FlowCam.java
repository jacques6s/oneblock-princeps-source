package princeps.flownav;

import net.minecraft.util.Mth;

/**
 * Frame-rate camera interpolation. Princeps applies the view rotation once per game tick (20 TPS); left
 * alone the first-person camera then STEPS 20 times a second instead of gliding at the render frame rate.
 * This holds the previous- and current-tick APPLIED rotation so the camera getters ({@code getViewYRot /
 * getViewXRot}, mixed in on {@link net.minecraft.world.entity.Entity}) can lerp between them every frame —
 * exactly how vanilla interpolates entity rotation with {@code partialTick}.
 *
 * <p>Purely visual and client-only: the value sent to the server is still the per-tick rotation. Single
 * local player, so the state is static.
 */
public final class FlowCam {

    /** The player whose view is being interpolated, or {@code null} when inactive. Volatile: written on
     *  the game thread, read on the render thread. A single reference compare is the whole hot-path guard —
     *  the camera getters run many times per FRAME, so no provider lookup / iteration may happen there. */
    private static volatile Object owner;
    private static float prevYaw;
    private static float curYaw;
    private static float prevPitch;
    private static float curPitch;
    /** Calls without a look target tolerated before interpolation really stops (the update event fires
     *  PRE and POST per tick, so this is ~3 game ticks). Movement handoffs (walk -> ascend etc.) can
     *  leave a target-less tick; a hard stop there reseeds prev=cur and renders one un-interpolated
     *  frame-run — a micro-stutter at every segment boundary. */
    private static final int STOP_GRACE_TICKS = 6;
    private static int graceLeft;

    private FlowCam() {
    }

    /**
     * Suspends the view takeover while a caller needs vanilla to read the player's ACTUAL yRot/xRot.
     *
     * <p>THE BUG THIS EXISTS FOR, and it was silent and total. {@code BuilderProcess.simulatePlacement} answers "what
     * block would land if I stood there and looked that way" by temporarily writing {@code setYRot}/{@code setXRot} and
     * asking vanilla. But vanilla reads the look ONLY through {@code getViewYRot(1.0F)}, and
     * {@code MixinClientPlayerEntity} injects at its HEAD and returns {@link #viewYaw} instead whenever
     * {@link #ownsView}. FlowCam knows nothing about a hypothetical rotation, so it handed back the rotation actually
     * applied this tick -- and every stance the builder ever "verified" was evaluated against wherever the camera
     * happened to point, not against the candidate being tested.
     *
     * <p>The mixin's own comment says "At partialTicks == 1 this returns the exact applied rotation, so gameplay
     * raycasts are byte-identical". True for gameplay, and precisely wrong for a simulation whose whole purpose is to
     * substitute a different rotation.
     *
     * <p>How it was caught, from the full trace of run 526a0201 on cell 85,-59,67: of 219 distinct (yaw, pitch) pairs,
     * <b>195 produced more than one outcome</b> -- (yaw=90, pitch=41) gave east 6x, south 34x, north 8x, and yaw=-90 and
     * yaw=+90 both gave south. Vanilla's placement is a pure function of look and hit point, so one input cannot give
     * three outputs: the logged rotation was not the rotation being used. That is also why "0 of 21924 attempts on the
     * owner's own hand-derived face produced facing=west" said nothing about geometry -- the question was never asked.
     *
     * <p>Nested deliberately (a counter, not a flag): the builder can ask about a stance from inside another such
     * question, and a boolean would be reset by the inner scope while the outer one still needed it.
     */
    private static int suspended;

    /** Take vanilla's own rotation for the duration of a simulation. ALWAYS pair with {@link #resumeView} in a finally. */
    public static void suspendView() {
        suspended++;
    }

    public static void resumeView() {
        if (suspended > 0) {
            suspended--;
        }
    }

    /** Allocation-free per-frame guard: is this the player whose view we interpolate? */
    public static boolean ownsView(Object player) {
        return suspended == 0 && player == owner;
    }

    /** Called once per tick with the rotation actually applied this tick; shifts current -> previous. */
    public static void push(Object player, float yaw, float pitch) {
        graceLeft = STOP_GRACE_TICKS;
        if (owner != player) {
            // Fresh start: no previous tick yet — seed both so the first rendered frame does not snap.
            prevYaw = curYaw = yaw;
            prevPitch = curPitch = pitch;
            owner = player;
            return;
        }
        prevYaw = curYaw;
        prevPitch = curPitch;
        curYaw = yaw;
        curPitch = pitch;
    }

    /**
     * No look target this tick (movement handoff). Tolerated for a short grace: the pair keeps
     * shifting with the LIVE rotation, so the camera stays continuous (without the shift the lerp
     * would replay the previous tick's delta and snap back at the tick boundary). Only a sustained
     * absence really stops the interpolation and returns the camera to vanilla.
     */
    public static void stopSoon(float liveYaw, float livePitch) {
        if (owner == null) {
            return;
        }
        if (--graceLeft <= 0) {
            owner = null;
            return;
        }
        prevYaw = curYaw;
        prevPitch = curPitch;
        curYaw = liveYaw;
        curPitch = livePitch;
    }

    /** Stop interpolating immediately (control released): the camera getters fall through to vanilla. */
    public static void stop() {
        owner = null;
    }

    public static float viewYaw(float partial) {
        return Mth.rotLerp(partial, prevYaw, curYaw);
    }

    public static float viewPitch(float partial) {
        return Mth.lerp(partial, prevPitch, curPitch);
    }
}
