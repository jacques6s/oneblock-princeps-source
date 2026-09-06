/*
 * This file is part of Princeps.
 */
package princeps.process;

import org.junit.Test;
import org.junit.BeforeClass;
import princeps.api.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import princeps.behavior.LookBehavior;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ensures AutoDig owns every look switch it relies on, while preserving its selected speed dial. */
public class AutoDigLookProfileTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void profilePinsAndReassertsHumanizedPlacementPolicyWithoutOverwritingTurnSpeed() {
        Settings settings = hostileSettings();
        settings.humanizedLookAimCurveTurnTicks.value = 8.0D;

        AutoDigLookProfile profile = AutoDigLookProfile.apply(settings);
        assertPinned(settings);
        assertEquals(8.0D, settings.humanizedLookAimCurveTurnTicks.value, 0.0D);

        settings.humanizedLook.value = false;
        settings.humanizedLookCapPlaceTurn.value = false;
        settings.remainWithExistingLookDirection.value = true;
        settings.humanizedWalkPitchMin.value = 88.0D;
        settings.humanizedWalkPitchMax.value = 89.0D;
        profile.enforce();
        assertPinned(settings);
    }

    @Test
    public void constructionAreaToolsKeepTheirPriorWalkingBandAndRestoreItAfterwards() throws Exception {
        Settings settings = hostileSettings();
        AutoDigLookProfile profile = AutoDigLookProfile.apply(settings, false);
        Method nudge = LookBehavior.class.getDeclaredMethod("nudgePitchToBand",
                float.class, float.class, float.class, float.class);
        nudge.setAccessible(true);
        for (int reassert = 0; reassert < 2; reassert++) {
            assertEquals(6.0D, settings.humanizedWalkPitchMin.value, 0.0D);
            assertEquals(12.0D, settings.humanizedWalkPitchMax.value, 0.0D);
            assertEquals(6.0F, ((Float) nudge.invoke(null, 1.65F,
                    settings.humanizedWalkPitchMin.value.floatValue(),
                    settings.humanizedWalkPitchMax.value.floatValue(),
                    settings.humanizedWalkPitchNudgeStep.value.floatValue())).floatValue(), 0.0F);
            settings.humanizedWalkPitchMin.value = -90.0D;
            settings.humanizedWalkPitchMax.value = 90.0D;
            profile.enforce();
        }
        profile.restore();
        assertEquals(44.0D, settings.humanizedWalkPitchMin.value, 0.0D);
        assertEquals(65.0D, settings.humanizedWalkPitchMax.value, 0.0D);
    }

    @Test
    public void actualWalkingNudgePreservesEveryValidPitchIncludingTheRecordedSolidRunAngles() throws Exception {
        Settings settings = hostileSettings();
        AutoDigLookProfile profile = AutoDigLookProfile.apply(settings);
        try {
            // Exercise the production band rule with this profile's real values, not a test copy of its formula.
            // Its caller normally reads global settings through the running Minecraft client. The shared pure
            // rule lets this headless fixture use isolated settings without mocking the method under test.
            Method nudge = LookBehavior.class.getDeclaredMethod("nudgePitchToBand",
                    float.class, float.class, float.class, float.class);
            nudge.setAccessible(true);
            for (float pitch : new float[] {1.65F, 1.95F, 0.0F, 6.0F, 12.0F, -0.1F,
                    -30.0F, 30.0F, -89.999F, 89.999F, -90.0F, 90.0F}) {
                assertEquals("a flat walk must preserve the incoming pitch " + pitch,
                        pitch, ((Float) nudge.invoke(null, pitch,
                                settings.humanizedWalkPitchMin.value.floatValue(),
                                settings.humanizedWalkPitchMax.value.floatValue(),
                                settings.humanizedWalkPitchNudgeStep.value.floatValue())).floatValue(), 0.0F);
            }
            assertEquals("a fall still has its own deliberate pitch", 12.0D,
                    settings.humanizedFallPitchMin.value, 0.0D);
            assertEquals(22.0D, settings.humanizedFallPitchMax.value, 0.0D);
        } finally {
            profile.restore();
        }
    }

    @Test
    public void teardownRestoresTheOwnersPriorSettingsExactlyOnce() {
        Settings settings = hostileSettings();
        AutoDigLookProfile profile = AutoDigLookProfile.apply(settings);

        profile.restore();
        profile.restore();

        assertFalse(settings.humanizedLook.value);
        assertFalse(settings.humanizedLookAimCurve.value);
        assertFalse(settings.humanizedLookCapBreakTurn.value);
        assertFalse(settings.humanizedLookCapPlaceTurn.value);
        assertFalse(settings.humanizedSteering.value);
        assertFalse(settings.microJitter.value);
        assertTrue(settings.remainWithExistingLookDirection.value);
        assertTrue(settings.smoothLook.value);
        assertEquals(2.0D, settings.humanizedLookDriftDegrees.value, 0.0D);
        assertEquals(3.0D, settings.humanizedLookTremorDegrees.value, 0.0D);
        assertEquals(0.2D, settings.humanizedLookAimCurveYawScale.value, 0.0D);
        assertEquals(0.3D, settings.humanizedLookAimCurvePitchScale.value, 0.0D);
        assertEquals(3.0D, settings.humanizedWalkPitchNudgeStep.value, 0.0D);
        assertEquals(44.0D, settings.humanizedWalkPitchMin.value, 0.0D);
        assertEquals(65.0D, settings.humanizedWalkPitchMax.value, 0.0D);
        assertEquals(35.0D, settings.humanizedFallPitchMin.value, 0.0D);
        assertEquals(70.0D, settings.humanizedFallPitchMax.value, 0.0D);
    }

    private static Settings hostileSettings() {
        Settings settings;
        try {
            java.lang.reflect.Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            settings = constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not create isolated settings for the profile test", exception);
        }
        settings.freeLook.value = true;
        settings.blockFreeLook.value = true;
        settings.elytraFreeLook.value = true;
        settings.smoothLook.value = true;
        settings.remainWithExistingLookDirection.value = true;
        settings.humanizedLook.value = false;
        settings.humanizedLookDriftDegrees.value = 2.0D;
        settings.humanizedLookTremorDegrees.value = 3.0D;
        settings.microJitter.value = false;
        settings.microJitterMinDegrees.value = 1.0D;
        settings.microJitterMaxDegrees.value = 2.0D;
        settings.humanizedLookMaxTurnHardCap.value = 5.0D;
        settings.humanizedLookCapBreakTurn.value = false;
        settings.humanizedLookCapPlaceTurn.value = false;
        settings.humanizedLookAimCurve.value = false;
        settings.humanizedLookAimCurveMode.value = 2;
        settings.humanizedLookAimCurvePeakScale.value = 1.7D;
        settings.humanizedLookAimCurveYawScale.value = 0.2D;
        settings.humanizedLookAimCurvePitchScale.value = 0.3D;
        settings.humanizedWalkPitchNudgeStep.value = 3.0D;
        settings.humanizedWalkPitchMin.value = 44.0D;
        settings.humanizedWalkPitchMax.value = 65.0D;
        settings.humanizedFallPitchMin.value = 35.0D;
        settings.humanizedFallPitchMax.value = 70.0D;
        settings.humanizedBreakSightDelay.value = false;
        settings.humanizedSteering.value = false;
        return settings;
    }

    private static void assertPinned(Settings settings) {
        assertTrue(settings.humanizedLook.value);
        assertTrue(settings.humanizedLookAimCurve.value);
        assertTrue(settings.humanizedLookCapBreakTurn.value);
        assertTrue(settings.humanizedLookCapPlaceTurn.value);
        assertTrue(settings.humanizedSteering.value);
        assertTrue(settings.microJitter.value);
        assertFalse(settings.remainWithExistingLookDirection.value);
        assertFalse(settings.smoothLook.value);
        assertEquals(0.0D, settings.humanizedLookDriftDegrees.value, 0.0D);
        assertEquals(0.0D, settings.humanizedLookTremorDegrees.value, 0.0D);
        assertEquals(AutoDigLookProfile.MICRO_JITTER_MIN, settings.microJitterMinDegrees.value, 0.0D);
        assertEquals(AutoDigLookProfile.MICRO_JITTER_MAX, settings.microJitterMaxDegrees.value, 0.0D);
        assertEquals(1.0D, settings.humanizedLookAimCurveYawScale.value, 0.0D);
        assertEquals(1.0D, settings.humanizedLookAimCurvePitchScale.value, 0.0D);
        assertEquals(180.0D, settings.humanizedWalkPitchNudgeStep.value, 0.0D);
        assertEquals(-90.0D, settings.humanizedWalkPitchMin.value, 0.0D);
        assertEquals(90.0D, settings.humanizedWalkPitchMax.value, 0.0D);
        assertEquals(12.0D, settings.humanizedFallPitchMin.value, 0.0D);
        assertEquals(22.0D, settings.humanizedFallPitchMax.value, 0.0D);
    }
}
