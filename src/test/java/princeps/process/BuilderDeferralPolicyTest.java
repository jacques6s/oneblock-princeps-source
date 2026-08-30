/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderDeferralPolicyTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void watchdogCannotRefreshABackoffBeforeItsDeadline() {
        assertFalse(BuilderProcess.deferralMayBeScheduled(640, 120));
        assertFalse(BuilderProcess.deferralMayBeScheduled(640, 639));
    }

    @Test
    public void cellCanBeDeferredAgainOnceItsBackoffExpires() {
        assertTrue(BuilderProcess.deferralMayBeScheduled(640, 640));
        assertTrue(BuilderProcess.deferralMayBeScheduled(640, 641));
    }
}
