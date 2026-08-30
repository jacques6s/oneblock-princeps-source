/*
 * This file is part of Princeps.
 */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderTemporarySupportPolicyTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void pristineSchematicAirMayCarryOneTemporaryClickSupport() {
        assertTrue(BuilderProcess.temporarySupportMayBeRouted(
                true, true, false, false, true, true));
    }

    @Test
    public void helperIsRejectedWhenItWouldChangeOrRemainPartOfTheBuild() {
        assertFalse(BuilderProcess.temporarySupportMayBeRouted(
                false, true, false, false, true, true));
        assertFalse(BuilderProcess.temporarySupportMayBeRouted(
                true, false, false, false, true, true));
        assertFalse(BuilderProcess.temporarySupportMayBeRouted(
                true, true, true, false, true, true));
        assertFalse(BuilderProcess.temporarySupportMayBeRouted(
                true, true, false, true, true, true));
        assertFalse(BuilderProcess.temporarySupportMayBeRouted(
                true, true, false, false, false, true));
        assertFalse(BuilderProcess.temporarySupportMayBeRouted(
                true, true, false, false, true, false));
    }
}
