/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.api.behavior;

import princeps.api.Settings;
import princeps.api.behavior.look.IAimProcessor;
import princeps.api.utils.Rotation;

/**
 * @author Brady
 * @since 9/23/2018
 */
public interface ILookBehavior extends IBehavior {

    /**
     * Updates the current {@link ILookBehavior} target to target the specified rotations on the next tick. If any sort
     * of block interaction is required, {@code blockInteract} should be {@code true}. It is not guaranteed that the
     * rotations set by the caller will be the exact rotations expressed by the client (This is due to settings like
     * {@link Settings#randomLooking}). If the rotations produced by this behavior are required, then the
     * {@link #getAimProcessor() aim processor} should be used.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     */
    void updateTarget(Rotation rotation, boolean blockInteract);

    /**
     * Like {@link #updateTarget(Rotation, boolean)}, additionally signalling that the interaction this aim serves is
     * a block BREAK (never a place/use). Break aims may be shaped by the humanized bell-curve turn — the dig itself
     * stays gated on the live crosshair raytrace at the press site, so a slower aim only ever means a later dig,
     * never a wrong-block dig. The intent cannot be derived from the CLICK_LEFT input state: break sites correctly
     * press only after the crosshair has arrived, so input-derived detection is circular and would leave the
     * first-aim snap (the "flick") in place.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     * @param breakIntent   Whether this aim targets a block that is about to be broken
     */
    default void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent) {
        this.updateTarget(rotation, blockInteract);
    }

    /**
     * The aim processor instance for this {@link ILookBehavior}, which is responsible for applying additional,
     * deterministic transformations to the target rotation set by {@link #updateTarget}.
     *
     * @return The aim processor
     * @see IAimProcessor#fork
     */
    IAimProcessor getAimProcessor();
}
