/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Small, world-independent state machine that makes a placement target sticky.
 *
 * <p>The builder used to select the first currently ray-traceable cell on every tick. Two adjacent cells could
 * therefore alternate forever: looking towards A made B selectable, looking towards B made A selectable, and every
 * per-cell timeout reset on the switch. This lock owns the target until the world verifies it, explicitly invalidates
 * it, or the bounded recovery path exhausts its stances. Its transient click attempt may be refreshed for that same
 * target without permitting another cell to steal ownership.</p>
 *
 * <p>The class deliberately knows nothing about Minecraft. {@code A} is the current support/face/hit/rotation/slot
 * plan owned by {@code BuilderProcess}. The target is sticky, but that plan may be refreshed as the player's
 * sub-block eye position settles; pure tests can stress the transition invariants without a running client.</p>
 */
public final class PlacementTargetLock<A> {

    public enum RouteDecision {
        KEEP_MOVING,
        ARRIVED,
        RETRY_STANCE
    }

    private final int aimTimeoutTicks;
    private final int routeNoCloserTimeoutTicks;
    private final int routeDeadlineTicks;

    private boolean active;
    private long targetKey;
    private A attempt;
    private int aimBlockedTicks;
    private double bestAimError = Double.POSITIVE_INFINITY;
    private int aimNoCloserTicks;

    private boolean recovering;
    private final Set<Long> rejectedStances = new HashSet<>();
    private boolean hasPlannedStance;
    private long plannedStanceKey;
    private int routeTicks;
    private int routeNoCloserTicks;
    private long bestDistanceSquared = Long.MAX_VALUE;

    public PlacementTargetLock(int aimTimeoutTicks, int routeNoCloserTimeoutTicks, int routeDeadlineTicks) {
        if (aimTimeoutTicks <= 0 || routeNoCloserTimeoutTicks <= 0 || routeDeadlineTicks <= 0) {
            throw new IllegalArgumentException("placement timeouts must be positive");
        }
        this.aimTimeoutTicks = aimTimeoutTicks;
        this.routeNoCloserTimeoutTicks = routeNoCloserTimeoutTicks;
        this.routeDeadlineTicks = routeDeadlineTicks;
    }

    /** Acquire an idle lock. If another target already owns it, the candidate is rejected and cannot flap it. */
    public boolean acquire(long candidateTargetKey, A candidateAttempt) {
        if (!active) {
            active = true;
            targetKey = candidateTargetKey;
            attempt = candidateAttempt;
            aimBlockedTicks = 0;
            resetAimConvergence();
            recovering = false;
            rejectedStances.clear();
            clearPlannedStance();
            return true;
        }
        return targetKey == candidateTargetKey;
    }

    /** Begin a target-bound recovery even when no click geometry was obtainable from the current side. */
    public boolean acquireForRecovery(long candidateTargetKey, long failedStanceKey) {
        if (!acquire(candidateTargetKey, null)) {
            return false;
        }
        startRecovery(failedStanceKey);
        return true;
    }

    public boolean isActive() {
        return active;
    }

    public boolean owns(long candidateTargetKey) {
        return active && targetKey == candidateTargetKey;
    }

    public long targetKey() {
        if (!active) {
            throw new IllegalStateException("no placement target is active");
        }
        return targetKey;
    }

    public Optional<A> attempt() {
        return Optional.ofNullable(attempt);
    }

    /** A new click plan is legal only as an explicit phase transition for the already-owned target. */
    public void setAttempt(long expectedTargetKey, A replacement) {
        requireOwner(expectedTargetKey);
        attempt = replacement;
        recovering = false;
        aimBlockedTicks = 0;
        resetAimConvergence();
        clearPlannedStance();
    }

    /**
     * Replace only the transient click geometry for an already-owned target and stance.
     *
     * <p>Unlike {@link #setAttempt(long, Object)}, this is not a recovery transition and deliberately preserves the
     * aim-blocked counter. A walking player can move inside one feet cell while the target remains valid; freezing
     * the old ray produces a permanent live-gate mismatch, while resetting the timeout on every fresh ray would make
     * a genuinely blocked stance unbounded.</p>
     */
    public void refreshAttempt(long expectedTargetKey, A replacement) {
        requireOwner(expectedTargetKey);
        if (recovering) {
            throw new IllegalStateException("cannot refresh a click attempt during stance recovery");
        }
        attempt = replacement;
    }

    public void clearAttempt(long expectedTargetKey) {
        requireOwner(expectedTargetKey);
        attempt = null;
        aimBlockedTicks = 0;
        resetAimConvergence();
    }

    public void markAimReady(long expectedTargetKey) {
        requireOwner(expectedTargetKey);
        aimBlockedTicks = 0;
        resetAimConvergence();
    }

    /**
     * Observe a not-yet-aligned aim without confusing normal turning with a blocked live placement gate.
     *
     * @return true only when the angular error has failed to improve for {@code noCloserTimeoutTicks}
     */
    public boolean markAimConverging(long expectedTargetKey, double angularError, int noCloserTimeoutTicks) {
        requireOwner(expectedTargetKey);
        if (!Double.isFinite(angularError) || angularError < 0.0D || noCloserTimeoutTicks <= 0) {
            throw new IllegalArgumentException("invalid aim convergence observation");
        }
        if (angularError + 0.01D < bestAimError) {
            bestAimError = angularError;
            aimNoCloserTicks = 0;
            return false;
        }
        if (++aimNoCloserTicks < noCloserTimeoutTicks) {
            return false;
        }
        resetAimConvergence();
        return true;
    }

    /**
     * Counts a failed alignment against the committed target. On timeout, the exact failed feet cell is blacklisted
     * and the state transitions to recovery. Returning true means the caller must stop aiming and plan another side.
     */
    public boolean markAimBlocked(long expectedTargetKey, long failedStanceKey) {
        requireOwner(expectedTargetKey);
        resetAimConvergence();
        if (++aimBlockedTicks < aimTimeoutTicks) {
            return false;
        }
        startRecovery(failedStanceKey);
        return true;
    }

    public void startRecovery(long failedStanceKey) {
        startRecovery();
        rejectedStances.add(failedStanceKey);
    }

    /**
     * Re-plan the owned target without blaming a stance. This transition is used for stale inventory, support,
     * pose, or route state where no placement attempt actually proved the current feet cell invalid.
     */
    public void startRecovery() {
        if (!active) {
            throw new IllegalStateException("cannot recover without a target");
        }
        attempt = null;
        aimBlockedTicks = 0;
        resetAimConvergence();
        recovering = true;
        clearPlannedStance();
    }

    public boolean isRecovering() {
        return active && recovering;
    }

    public boolean mayTryStance(long stanceKey) {
        return active && !rejectedStances.contains(stanceKey);
    }

    public int rejectedStanceCount() {
        return rejectedStances.size();
    }

    public boolean hasPlannedStance() {
        return active && recovering && hasPlannedStance;
    }

    public long plannedStanceKey() {
        if (!hasPlannedStance()) {
            throw new IllegalStateException("no recovery stance is planned");
        }
        return plannedStanceKey;
    }

    public void planStance(long stanceKey, long initialDistanceSquared) {
        if (!active || !recovering) {
            throw new IllegalStateException("a stance can only be planned during recovery");
        }
        if (rejectedStances.contains(stanceKey)) {
            throw new IllegalArgumentException("cannot reuse a rejected placement stance");
        }
        hasPlannedStance = true;
        plannedStanceKey = stanceKey;
        routeTicks = 0;
        routeNoCloserTicks = 0;
        bestDistanceSquared = initialDistanceSquared;
    }

    /** Progress is distance improvement, not merely another path calculation or movement input. */
    public RouteDecision routeTick(long currentDistanceSquared, boolean arrived) {
        return routeTick(currentDistanceSquared, arrived, false);
    }

    /** A proven path can initially lead away from its target; advancing its nodes is real progress too. */
    public RouteDecision routeTick(long currentDistanceSquared, boolean arrived, boolean routeAdvanced) {
        if (!hasPlannedStance()) {
            throw new IllegalStateException("no recovery stance is planned");
        }
        routeTicks++;
        if (arrived) {
            return RouteDecision.ARRIVED;
        }
        if (currentDistanceSquared < bestDistanceSquared || routeAdvanced) {
            bestDistanceSquared = Math.min(bestDistanceSquared, currentDistanceSquared);
            routeNoCloserTicks = 0;
            // The deadline bounds a stalled/detouring segment, not a long route that is still measurably advancing.
            routeTicks = 0;
        } else {
            routeNoCloserTicks++;
        }
        if (routeNoCloserTicks >= routeNoCloserTimeoutTicks || routeTicks >= routeDeadlineTicks) {
            rejectPlannedStance();
            return RouteDecision.RETRY_STANCE;
        }
        return RouteDecision.KEEP_MOVING;
    }

    public void rejectPlannedStance() {
        if (!hasPlannedStance()) {
            return;
        }
        rejectedStances.add(plannedStanceKey);
        clearPlannedStance();
    }

    /** Terminal transition: verified, externally completed, invalid, or explicitly exhausted. */
    public void release() {
        active = false;
        targetKey = 0L;
        attempt = null;
        aimBlockedTicks = 0;
        resetAimConvergence();
        recovering = false;
        rejectedStances.clear();
        clearPlannedStance();
    }

    private void requireOwner(long expectedTargetKey) {
        if (!owns(expectedTargetKey)) {
            throw new IllegalStateException("placement target changed without a terminal transition");
        }
    }

    private void clearPlannedStance() {
        hasPlannedStance = false;
        plannedStanceKey = 0L;
        routeTicks = 0;
        routeNoCloserTicks = 0;
        bestDistanceSquared = Long.MAX_VALUE;
    }

    private void resetAimConvergence() {
        bestAimError = Double.POSITIVE_INFINITY;
        aimNoCloserTicks = 0;
    }
}
