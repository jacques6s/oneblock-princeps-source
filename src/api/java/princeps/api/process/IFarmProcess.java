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

package princeps.api.process;

import net.minecraft.core.BlockPos;

public interface IFarmProcess extends IPrincepsProcess {

    /**
     * Begin to search for crops to farm with in specified aria
     * from specified location.
     *
     * @param range The distance from center to farm from
     * @param pos   The center position to base the range from
     */
    void farm(int range, BlockPos pos);

    /**
     * Begin to search for nearby crops to farm.
     */
    default void farm() {farm(0, null);}

    /**
     * Begin to search for crops to farm with in specified aria
     * from the position the command was executed.
     *
     * @param range The distance to search for crops to farm
     */
    default void farm(int range) {farm(range, null);}
}
