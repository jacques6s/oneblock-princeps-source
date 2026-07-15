package princeps.behavior;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SurvivalActivityPolicyTest {

    @Test
    public void idlePrincepsNeverOwnsSurvivalInventory() {
        assertFalse(SurvivalActivityPolicy.isActive(true, false));
    }

    @Test
    public void automationRequiresBothTheSettingAndActiveNavigation() {
        assertTrue(SurvivalActivityPolicy.isActive(true, true));
        assertFalse(SurvivalActivityPolicy.isActive(false, true));
        assertFalse(SurvivalActivityPolicy.isActive(false, false));
    }
}
