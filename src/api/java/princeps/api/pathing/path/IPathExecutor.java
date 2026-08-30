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

package princeps.api.pathing.path;

import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.calc.IPath;

/**
 * @author Brady
 * @since 10/8/2018
 */
public interface IPathExecutor {

    IPath getPath();

    int getPosition();

    /**
     * What this route may do to the world while it is being driven — see {@link PlacementLicence}.
     *
     * <p>Asked by the one choke point through which every movement places a block, so that the answer comes from
     * the route being driven rather than from a global field that can change under it. The default is deliberately
     * permissive: an executor created outside a build behaves exactly as it always has.
     */
    default PlacementLicence placementLicence() {
        return PlacementLicence.UNRESTRICTED;
    }

    /**
     * Where this route may stand in water rather than try to mine it — see {@link WadeLicence}.
     *
     * <p>Asked by {@code Movement.prepared}, the one place a running movement decides that something is in the way.
     * The default is deliberately restrictive: an executor created outside an excavation behaves exactly as it
     * always has, which for a liquid means it does not walk into one the search did not plan for.
     */
    default WadeLicence wadeLicence() {
        return WadeLicence.NONE;
    }
}
