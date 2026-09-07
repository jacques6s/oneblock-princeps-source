/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

/** Exercises the real profile-reset helper, not a headless simulation of build(), worlds, or game ticks. */
public class BuilderLookProfileLifecycleTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void pausingKeepsTheActiveProfileUntilTheReplacementResetRestoresItsSnapshot() throws Exception {
        Settings settings = originalSettings();
        BuilderProcess builder = headlessBuilder();
        AutoDigLookProfile active = install(builder, settings, true);

        builder.pause();
        assertTrue(builder.isPaused());
        assertSame("pause is temporary and must not release the active profile", active, profile(builder));
        assertMode(settings, true);

        builder.resetAutoDigLookProfile();
        assertNull("replacement must release the old immutable mode", profile(builder));
        assertOriginal(settings);
        assertTrue("profile reset does not itself resume the paused job", builder.isPaused());
    }

    @Test
    public void pausedConstructionAreaJobCanBeReplacedWithAnExcavationProfile() throws Exception {
        assertModeReplacement(false, true);
    }

    @Test
    public void pausedExcavationCanBeReplacedWithAConstructionAreaProfile() throws Exception {
        assertModeReplacement(true, false);
    }

    @Test
    public void ordinaryConstructionAfterPausedExcavationNeedsNoProfileAndRepeatedResetIsHarmless() throws Exception {
        Settings settings = originalSettings();
        BuilderProcess builder = headlessBuilder();
        install(builder, settings, true);
        builder.pause();

        builder.resetAutoDigLookProfile();
        assertNull(profile(builder));
        assertOriginal(settings);
        // Ordinary construction does not install a profile. Only the reset/resume lifecycle is exercised here;
        // the actual build() call site and its mode selection are reviewed separately in production source.
        builder.resume();
        assertFalse(builder.isPaused());
        assertNull(profile(builder));
        assertOriginal(settings);

        settings.humanizedWalkPitchMin.value = -14.0D;
        settings.humanizedWalkPitchMax.value = 33.0D;
        builder.resetAutoDigLookProfile();
        builder.resetAutoDigLookProfile();
        assertNull(profile(builder));
        assertEquals("a second reset must not restore a stale snapshot over new owner preferences",
                -14.0D, settings.humanizedWalkPitchMin.value, 0.0D);
        assertEquals(33.0D, settings.humanizedWalkPitchMax.value, 0.0D);
    }

    private static void assertModeReplacement(boolean oldExcavating, boolean nextExcavating) throws Exception {
        Settings settings = originalSettings();
        BuilderProcess builder = headlessBuilder();
        AutoDigLookProfile previous = install(builder, settings, oldExcavating);
        builder.pause();
        assertSame(previous, profile(builder));
        assertMode(settings, oldExcavating);

        builder.resetAutoDigLookProfile();
        assertNull(profile(builder));
        assertOriginal(settings);

        AutoDigLookProfile replacement = install(builder, settings, nextExcavating);
        assertNotSame(previous, replacement);
        assertSame(replacement, profile(builder));
        builder.resume();
        replacement.enforce();
        assertMode(settings, nextExcavating);

        builder.resetAutoDigLookProfile();
        assertNull(profile(builder));
        assertOriginal(settings);
        builder.resetAutoDigLookProfile();
        assertOriginal(settings);
    }

    private static AutoDigLookProfile install(BuilderProcess builder, Settings settings, boolean excavating)
            throws ReflectiveOperationException {
        AutoDigLookProfile active = AutoDigLookProfile.apply(settings, excavating);
        profileField().set(builder, active);
        return active;
    }

    private static AutoDigLookProfile profile(BuilderProcess builder) throws ReflectiveOperationException {
        return (AutoDigLookProfile) profileField().get(builder);
    }

    private static Field profileField() throws NoSuchFieldException {
        Field field = BuilderProcess.class.getDeclaredField("autoDigLookProfile");
        field.setAccessible(true);
        return field;
    }

    private static BuilderProcess headlessBuilder() throws ReflectiveOperationException {
        // The real constructor requires a running client. Initialize the progress watch used by pause(),
        // alongside the profile installed by each test, so the actual lifecycle reaches its assertions.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        BuilderProcess builder = (BuilderProcess) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, BuilderProcess.class);
        Field progressWatch = BuilderProcess.class.getDeclaredField("progressWatch");
        progressWatch.setAccessible(true);
        progressWatch.set(builder, new princeps.process.builder.BuilderProgressWatch(5, 60, 2));
        return builder;
    }

    private static Settings originalSettings() throws ReflectiveOperationException {
        Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Settings settings = constructor.newInstance();
        settings.humanizedWalkPitchMin.value = 44.0D;
        settings.humanizedWalkPitchMax.value = 65.0D;
        settings.humanizedWalkPitchNudgeStep.value = 3.0D;
        settings.humanizedFallPitchMin.value = 35.0D;
        settings.humanizedFallPitchMax.value = 70.0D;
        settings.humanizedLook.value = false;
        settings.freeLook.value = true;
        settings.humanizedLookCapBreakTurn.value = false;
        settings.humanizedLookAimCurveTurnTicks.value = 8.0D;
        return settings;
    }

    private static void assertMode(Settings settings, boolean excavating) {
        assertEquals(excavating ? -90.0D : 6.0D, settings.humanizedWalkPitchMin.value, 0.0D);
        assertEquals(excavating ? 90.0D : 12.0D, settings.humanizedWalkPitchMax.value, 0.0D);
        assertEquals(12.0D, settings.humanizedFallPitchMin.value, 0.0D);
        assertEquals(22.0D, settings.humanizedFallPitchMax.value, 0.0D);
        assertTrue(settings.humanizedLook.value);
        assertFalse(settings.freeLook.value);
        assertTrue(settings.humanizedLookCapBreakTurn.value);
        assertEquals(8.0D, settings.humanizedLookAimCurveTurnTicks.value, 0.0D);
    }

    private static void assertOriginal(Settings settings) {
        assertEquals(44.0D, settings.humanizedWalkPitchMin.value, 0.0D);
        assertEquals(65.0D, settings.humanizedWalkPitchMax.value, 0.0D);
        assertEquals(3.0D, settings.humanizedWalkPitchNudgeStep.value, 0.0D);
        assertEquals(35.0D, settings.humanizedFallPitchMin.value, 0.0D);
        assertEquals(70.0D, settings.humanizedFallPitchMax.value, 0.0D);
        assertFalse(settings.humanizedLook.value);
        assertTrue(settings.freeLook.value);
        assertFalse(settings.humanizedLookCapBreakTurn.value);
        assertEquals(8.0D, settings.humanizedLookAimCurveTurnTicks.value, 0.0D);
    }
}
