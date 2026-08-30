/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.api.behavior.look;

/**
 * The world interaction a precise look target is preparing.
 *
 * <p>This is carried separately from the mouse button. Correct callers press only after the live ray has reached
 * its target, so deriving intent from a currently held button is circular: the aim would snap before the button can
 * advertise what it is for. A single enum also makes BREAK and PLACE mutually exclusive by construction.
 */
public enum AimIntent {
    NONE,
    BREAK,
    PLACE;

    /** Compatibility bridge for older call sites which supplied two booleans. */
    public static AimIntent fromLegacy(boolean breakIntent, boolean placeIntent) {
        if (breakIntent && placeIntent) {
            throw new IllegalArgumentException("An aim cannot prepare a break and a placement at the same time");
        }
        return breakIntent ? BREAK : placeIntent ? PLACE : NONE;
    }
}
