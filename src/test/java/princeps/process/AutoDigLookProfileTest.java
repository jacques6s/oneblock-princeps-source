/*
 * This file is part of Princeps.
 */
package princeps.process;

import org.junit.Test;
import org.junit.BeforeClass;
import princeps.api.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

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
        profile.enforce();
        assertPinned(settings);
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
    }
}
