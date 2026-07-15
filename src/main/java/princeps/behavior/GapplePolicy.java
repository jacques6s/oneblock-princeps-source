/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.behavior;

/** Pure golden-apple policy kept separate from Minecraft state so its boundary conditions are testable. */
final class GapplePolicy {

    private GapplePolicy() {}

    /** Health and the threshold are Minecraft health points (half-hearts). */
    static boolean isEmergency(float health, int emergencyHp) {
        return health <= emergencyHp;
    }

    static boolean isCooldownReady(long now, long lastGappleMs, int cooldownMs) {
        return now - lastGappleMs >= Math.max(0, cooldownMs);
    }
}
