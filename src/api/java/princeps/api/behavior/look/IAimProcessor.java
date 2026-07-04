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

package princeps.api.behavior.look;

import princeps.api.utils.Rotation;

/**
 * @author Brady
 */
public interface IAimProcessor {

    /**
     * Returns the actual rotation that will be used when the desired rotation is requested. The returned rotation
     * always reflects what would happen in the upcoming tick. In other words, it is a pure function, and no internal
     * state changes. If simulation of the rotation states beyond the next tick is required, then a
     * {@link IAimProcessor#fork fork} should be created.
     *
     * @param desired The desired rotation to set
     * @return The actual rotation
     */
    Rotation peekRotation(Rotation desired);

    /**
     * Like {@link #peekRotation(Rotation)} but ALWAYS the exact rotation with no humanized-look wander. Reach/place
     * feasibility predictions must use this: the actual break/place is applied at a precise (exact) rotation, so a
     * prediction that included the cruising wander would disagree with reality and flip a raytrace hit/miss.
     *
     * @param desired The desired rotation to set
     * @return The exact actual rotation (mouse-quantized, no wander)
     */
    Rotation peekRotationExact(Rotation desired);

    /**
     * Returns a copy of this {@link IAimProcessor} which has its own internal state and is manually tickable.
     *
     * @return The forked processor
     * @see ITickableAimProcessor
     */
    ITickableAimProcessor fork();
}
