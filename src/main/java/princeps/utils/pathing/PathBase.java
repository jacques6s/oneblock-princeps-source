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

package princeps.utils.pathing;

import princeps.Princeps;
import princeps.api.PrincepsAPI;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.pathing.path.CutoffPath;
import princeps.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;

public abstract class PathBase implements IPath {

    @Override
    public PathBase cutoffAtLoadedChunks(Object bsi0) { // <-- cursed cursed cursed
        if (!Princeps.settings().cutoffAtLoadBoundary.value) {
            return this;
        }
        BlockStateInterface bsi = (BlockStateInterface) bsi0;
        for (int i = 0; i < positions().size(); i++) {
            BlockPos pos = positions().get(i);
            if (!bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                return new CutoffPath(this, i);
            }
        }
        return this;
    }

    @Override
    public PathBase staticCutoff(Goal destination) {
        int min = PrincepsAPI.getSettings().pathCutoffMinimumLength.value;
        if (length() < min) {
            return this;
        }
        if (destination == null || destination.isInGoal(getDest())) {
            return this;
        }
        double factor = PrincepsAPI.getSettings().pathCutoffFactor.value;
        int newLength = (int) ((length() - min) * factor) + min - 1;
        return new CutoffPath(this, newLength);
    }
}
