/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process;

import princeps.api.Settings;

/**
 * Scoped, deterministic look policy for the area-tool excavation snake.
 *
 * <p>The caller still owns the one deliberate user dial, {@code humanizedLookAimCurveTurnTicks}. Everything else
 * that decides whether a turn curves, snaps, wanders, or inherits a prior module's steering is fixed for the life of
 * the excavation and restored afterwards. That makes a bench run and a real AutoDig session exercise the same aim
 * state machine without leaking those values into ordinary navigation.
 */
final class AutoDigLookProfile {

    static final double MICRO_JITTER_MIN = 0.05D;
    static final double MICRO_JITTER_MAX = 0.40D;

    private final Settings settings;
    private final boolean freeLook;
    private final boolean blockFreeLook;
    private final boolean elytraFreeLook;
    private final boolean smoothLook;
    private final boolean remainWithExistingLookDirection;
    private final boolean humanizedLook;
    private final double humanizedLookDriftDegrees;
    private final double humanizedLookTremorDegrees;
    private final boolean microJitter;
    private final double microJitterMinDegrees;
    private final double microJitterMaxDegrees;
    private final double humanizedLookMaxTurnHardCap;
    private final boolean humanizedLookCapBreakTurn;
    private final boolean humanizedLookCapPlaceTurn;
    private final boolean humanizedLookAimCurve;
    private final int humanizedLookAimCurveMode;
    private final double humanizedLookAimCurvePeakScale;
    private final double humanizedLookAimCurveYawScale;
    private final double humanizedLookAimCurvePitchScale;
    private final double humanizedWalkPitchNudgeStep;
    private final double humanizedWalkPitchMin;
    private final double humanizedWalkPitchMax;
    private final double humanizedFallPitchMin;
    private final double humanizedFallPitchMax;
    private final boolean humanizedBreakSightDelay;
    private final boolean humanizedSteering;
    private final double humanizedSteeringXtEngage;
    private final double humanizedSteeringXtRelease;
    private final double humanizedSteeringGazeBlocks;
    private final double humanizedSteeringTrackBlocks;
    private final boolean humanizedSteeringCurveSlow;
    private final double humanizedSteeringSlowBend;
    private final double humanizedSteeringHysteresis;
    private boolean restored;

    private AutoDigLookProfile(Settings settings) {
        this.settings = settings;
        freeLook = settings.freeLook.value;
        blockFreeLook = settings.blockFreeLook.value;
        elytraFreeLook = settings.elytraFreeLook.value;
        smoothLook = settings.smoothLook.value;
        remainWithExistingLookDirection = settings.remainWithExistingLookDirection.value;
        humanizedLook = settings.humanizedLook.value;
        humanizedLookDriftDegrees = settings.humanizedLookDriftDegrees.value;
        humanizedLookTremorDegrees = settings.humanizedLookTremorDegrees.value;
        microJitter = settings.microJitter.value;
        microJitterMinDegrees = settings.microJitterMinDegrees.value;
        microJitterMaxDegrees = settings.microJitterMaxDegrees.value;
        humanizedLookMaxTurnHardCap = settings.humanizedLookMaxTurnHardCap.value;
        humanizedLookCapBreakTurn = settings.humanizedLookCapBreakTurn.value;
        humanizedLookCapPlaceTurn = settings.humanizedLookCapPlaceTurn.value;
        humanizedLookAimCurve = settings.humanizedLookAimCurve.value;
        humanizedLookAimCurveMode = settings.humanizedLookAimCurveMode.value;
        humanizedLookAimCurvePeakScale = settings.humanizedLookAimCurvePeakScale.value;
        humanizedLookAimCurveYawScale = settings.humanizedLookAimCurveYawScale.value;
        humanizedLookAimCurvePitchScale = settings.humanizedLookAimCurvePitchScale.value;
        humanizedWalkPitchNudgeStep = settings.humanizedWalkPitchNudgeStep.value;
        humanizedWalkPitchMin = settings.humanizedWalkPitchMin.value;
        humanizedWalkPitchMax = settings.humanizedWalkPitchMax.value;
        humanizedFallPitchMin = settings.humanizedFallPitchMin.value;
        humanizedFallPitchMax = settings.humanizedFallPitchMax.value;
        humanizedBreakSightDelay = settings.humanizedBreakSightDelay.value;
        humanizedSteering = settings.humanizedSteering.value;
        humanizedSteeringXtEngage = settings.humanizedSteeringXtEngage.value;
        humanizedSteeringXtRelease = settings.humanizedSteeringXtRelease.value;
        humanizedSteeringGazeBlocks = settings.humanizedSteeringGazeBlocks.value;
        humanizedSteeringTrackBlocks = settings.humanizedSteeringTrackBlocks.value;
        humanizedSteeringCurveSlow = settings.humanizedSteeringCurveSlow.value;
        humanizedSteeringSlowBend = settings.humanizedSteeringSlowBend.value;
        humanizedSteeringHysteresis = settings.humanizedSteeringHysteresis.value;
    }

    static AutoDigLookProfile apply(Settings settings) {
        AutoDigLookProfile snapshot = new AutoDigLookProfile(settings);
        snapshot.enforce();
        return snapshot;
    }

    /** Reassert settings another module or a persisted-config reload must not change mid-excavation. */
    void enforce() {
        settings.freeLook.value = false;
        settings.blockFreeLook.value = false;
        settings.elytraFreeLook.value = false;
        settings.smoothLook.value = false;
        settings.remainWithExistingLookDirection.value = false;
        settings.humanizedLook.value = true;
        settings.humanizedLookDriftDegrees.value = 0.0D;
        settings.humanizedLookTremorDegrees.value = 0.0D;
        settings.microJitter.value = true;
        settings.microJitterMinDegrees.value = MICRO_JITTER_MIN;
        settings.microJitterMaxDegrees.value = MICRO_JITTER_MAX;
        settings.humanizedLookMaxTurnHardCap.value = 70.0D;
        settings.humanizedLookCapBreakTurn.value = true;
        settings.humanizedLookCapPlaceTurn.value = true;
        settings.humanizedLookAimCurve.value = true;
        settings.humanizedLookAimCurveMode.value = 1;
        settings.humanizedLookAimCurvePeakScale.value = 1.0D;
        settings.humanizedLookAimCurveYawScale.value = 1.0D;
        settings.humanizedLookAimCurvePitchScale.value = 1.0D;
        settings.humanizedWalkPitchNudgeStep.value = 180.0D;
        settings.humanizedWalkPitchMin.value = 6.0D;
        settings.humanizedWalkPitchMax.value = 12.0D;
        settings.humanizedFallPitchMin.value = 12.0D;
        settings.humanizedFallPitchMax.value = 22.0D;
        settings.humanizedBreakSightDelay.value = true;
        settings.humanizedSteering.value = true;
        settings.humanizedSteeringXtEngage.value = 0.30D;
        settings.humanizedSteeringXtRelease.value = 0.12D;
        settings.humanizedSteeringGazeBlocks.value = 1.5D;
        settings.humanizedSteeringTrackBlocks.value = 0.7D;
        settings.humanizedSteeringCurveSlow.value = true;
        settings.humanizedSteeringSlowBend.value = 22.0D;
        settings.humanizedSteeringHysteresis.value = 20.0D;
    }

    void restore() {
        if (restored) {
            return;
        }
        restored = true;
        settings.freeLook.value = freeLook;
        settings.blockFreeLook.value = blockFreeLook;
        settings.elytraFreeLook.value = elytraFreeLook;
        settings.smoothLook.value = smoothLook;
        settings.remainWithExistingLookDirection.value = remainWithExistingLookDirection;
        settings.humanizedLook.value = humanizedLook;
        settings.humanizedLookDriftDegrees.value = humanizedLookDriftDegrees;
        settings.humanizedLookTremorDegrees.value = humanizedLookTremorDegrees;
        settings.microJitter.value = microJitter;
        settings.microJitterMinDegrees.value = microJitterMinDegrees;
        settings.microJitterMaxDegrees.value = microJitterMaxDegrees;
        settings.humanizedLookMaxTurnHardCap.value = humanizedLookMaxTurnHardCap;
        settings.humanizedLookCapBreakTurn.value = humanizedLookCapBreakTurn;
        settings.humanizedLookCapPlaceTurn.value = humanizedLookCapPlaceTurn;
        settings.humanizedLookAimCurve.value = humanizedLookAimCurve;
        settings.humanizedLookAimCurveMode.value = humanizedLookAimCurveMode;
        settings.humanizedLookAimCurvePeakScale.value = humanizedLookAimCurvePeakScale;
        settings.humanizedLookAimCurveYawScale.value = humanizedLookAimCurveYawScale;
        settings.humanizedLookAimCurvePitchScale.value = humanizedLookAimCurvePitchScale;
        settings.humanizedWalkPitchNudgeStep.value = humanizedWalkPitchNudgeStep;
        settings.humanizedWalkPitchMin.value = humanizedWalkPitchMin;
        settings.humanizedWalkPitchMax.value = humanizedWalkPitchMax;
        settings.humanizedFallPitchMin.value = humanizedFallPitchMin;
        settings.humanizedFallPitchMax.value = humanizedFallPitchMax;
        settings.humanizedBreakSightDelay.value = humanizedBreakSightDelay;
        settings.humanizedSteering.value = humanizedSteering;
        settings.humanizedSteeringXtEngage.value = humanizedSteeringXtEngage;
        settings.humanizedSteeringXtRelease.value = humanizedSteeringXtRelease;
        settings.humanizedSteeringGazeBlocks.value = humanizedSteeringGazeBlocks;
        settings.humanizedSteeringTrackBlocks.value = humanizedSteeringTrackBlocks;
        settings.humanizedSteeringCurveSlow.value = humanizedSteeringCurveSlow;
        settings.humanizedSteeringSlowBend.value = humanizedSteeringSlowBend;
        settings.humanizedSteeringHysteresis.value = humanizedSteeringHysteresis;
    }
}
