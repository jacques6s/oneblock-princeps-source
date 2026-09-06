/*
 * This file is part of Princeps, licensed under LGPL-3.0-or-later.
 */
package princeps.process.builder;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Measures an active build against observations, never input requests or newly allocated goals. */
public final class BuilderProgressWatch {
    public enum Phase { WORK, MINING, WAIT_CONFIRMATION, WAIT_MATERIAL, WAIT_CHUNK, PAUSED }
    public enum Decision { CONTINUE, DIAGNOSE, STOP }

    public record Sample(Phase phase, Long target, double distance, Object route, int pathPosition,
                         long worldRevision, Long miningTarget, double miningProgress) { }

    private final long diagnoseNanos;
    private final long stopNanos;
    private final double approachDistance;
    private final Map<Long, Double> bestDistances = new HashMap<>();
    private final Map<Object, Integer> routeHighWater = new HashMap<>();
    private final Map<Long, Double> miningHighWater = new HashMap<>();
    private boolean started;
    private long lastNanos;
    private long quietNanos;
    private long approachQuietNanos;
    private long lastWorldRevision;
    private Phase previousPhase;
    private Long approachTarget;
    private double approachBaseline;
    private boolean diagnosed;
    private boolean advancedLastSample;

    public BuilderProgressWatch(long diagnoseNanos, long stopNanos, double approachDistance) {
        if (diagnoseNanos <= 0 || stopNanos <= diagnoseNanos
                || !Double.isFinite(approachDistance) || approachDistance <= 0) {
            throw new IllegalArgumentException("invalid build progress limits");
        }
        this.diagnoseNanos = diagnoseNanos;
        this.stopNanos = stopNanos;
        this.approachDistance = approachDistance;
    }

    public Decision observe(long nowNanos, Sample sample) {
        advancedLastSample = false;
        Objects.requireNonNull(sample.phase());
        if (!Double.isFinite(sample.distance()) || sample.distance() < 0
                || !Double.isFinite(sample.miningProgress()) || sample.miningProgress() < 0) {
            throw new IllegalArgumentException("invalid build progress observation");
        }
        long elapsed = started ? Math.max(0, nowNanos - lastNanos) : 0;
        lastNanos = nowNanos;
        if (!started) {
            started = true;
            lastWorldRevision = sample.worldRevision();
        }
        // An explicit owner/supply pause suspends the clock, including the first resumed sample.
        if (sample.phase() == Phase.PAUSED || previousPhase == Phase.PAUSED) {
            elapsed = 0;
        }
        previousPhase = sample.phase();
        if (sample.phase() == Phase.PAUSED) {
            return Decision.CONTINUE;
        }
        quietNanos += elapsed;
        approachQuietNanos += elapsed;
        boolean worldChanged = sample.worldRevision() != lastWorldRevision;
        lastWorldRevision = sample.worldRevision();
        if (worldChanged) {
            bestDistances.clear();
            routeHighWater.clear();
            miningHighWater.clear();
        }
        boolean waiting = sample.phase() == Phase.WAIT_CHUNK || sample.phase() == Phase.WAIT_MATERIAL
                || sample.phase() == Phase.WAIT_CONFIRMATION;
        boolean advanced = worldChanged;
        boolean approached = worldChanged;
        if (!waiting && sample.target() != null) {
            Double best = bestDistances.get(sample.target());
            if (best != null && sample.distance() + 0.01 < best) {
                advanced = true;
            }
            bestDistances.put(sample.target(), best == null ? sample.distance() : Math.min(best, sample.distance()));
            if (!Objects.equals(approachTarget, sample.target())) {
                approachTarget = sample.target();
                approachBaseline = sample.distance();
                // Changing a target is no evidence of progress and does not reset either clock.
            } else if (approachBaseline - sample.distance() >= approachDistance) {
                approachBaseline = sample.distance();
                approached = true;
            }
        }
        if (!waiting && sample.route() != null && sample.pathPosition() >= 0) {
            Integer highest = routeHighWater.get(sample.route());
            if (highest != null && sample.pathPosition() > highest) {
                advanced = true;
                approached = true; // a valid detour need not reduce straight-line distance
            }
            routeHighWater.put(sample.route(), highest == null ? sample.pathPosition()
                    : Math.max(highest, sample.pathPosition()));
        }
        if (sample.phase() == Phase.MINING && sample.miningTarget() != null) {
            Double highest = miningHighWater.get(sample.miningTarget());
            if (highest != null && sample.miningProgress() > highest + 0.0000001) {
                advanced = true;
                approached = true;
            }
            miningHighWater.put(sample.miningTarget(), highest == null ? sample.miningProgress()
                    : Math.max(highest, sample.miningProgress()));
        }
        if (advanced) {
            quietNanos = 0;
        }
        advancedLastSample = advanced;
        if (approached) {
            approachQuietNanos = 0;
            diagnosed = false;
        }
        if (quietNanos >= stopNanos) {
            return Decision.STOP;
        }
        if (!diagnosed && approachQuietNanos >= diagnoseNanos) {
            diagnosed = true;
            return Decision.DIAGNOSE;
        }
        return Decision.CONTINUE;
    }

    public long quietNanos() { return quietNanos; }
    public boolean advancedLastSample() { return advancedLastSample; }

    /** Register a replacement route's starting point without crediting its construction as movement. */
    public void beginRoute(Object route, int position) {
        if (route != null && position >= 0) routeHighWater.putIfAbsent(route, position);
    }

    /** Called by the real pause callback even when no builder ticks are delivered during the pause. */
    public void pause() { previousPhase = Phase.PAUSED; }

    public void reset() {
        started = false;
        lastNanos = quietNanos = approachQuietNanos = lastWorldRevision = 0;
        previousPhase = null;
        approachTarget = null;
        diagnosed = false;
        advancedLastSample = false;
        bestDistances.clear();
        routeHighWater.clear();
        miningHighWater.clear();
    }
}
