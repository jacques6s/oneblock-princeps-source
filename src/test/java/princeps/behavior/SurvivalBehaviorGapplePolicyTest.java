/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.behavior;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SurvivalBehaviorGapplePolicyTest {

    @Test
    public void fiveHeartsIsAlreadyAnEmergency() {
        assertTrue(GapplePolicy.isEmergency(10.0F, 10));
        assertTrue(GapplePolicy.isEmergency(9.5F, 10));
        assertFalse(GapplePolicy.isEmergency(10.5F, 10));
    }

    @Test
    public void normalGappleWaitsForTheFullTenSeconds() {
        long last = 25_000L;
        assertFalse(GapplePolicy.isCooldownReady(last + 9_999L, last, 10_000));
        assertTrue(GapplePolicy.isCooldownReady(last + 10_000L, last, 10_000));
    }
}
