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

package princeps.process.elytra;

import princeps.Princeps;
import princeps.api.pathing.goals.Goal;
import princeps.api.process.IElytraProcess;
import princeps.api.process.PathingCommand;
import princeps.utils.PrincepsProcessHelper;
import net.minecraft.core.BlockPos;

/**
 * @author Brady
 */
public final class NullElytraProcess extends PrincepsProcessHelper implements IElytraProcess {

    public NullElytraProcess(Princeps princeps) {
        super(princeps);
    }

    @Override
    public void repackChunks() {
        throw new UnsupportedOperationException("Called repackChunks() on NullElytraBehavior");
    }

    @Override
    public BlockPos currentDestination() {
        return null;
    }

    @Override
    public void pathTo(BlockPos destination) {
        throw new UnsupportedOperationException("Called pathTo() on NullElytraBehavior");
    }

    @Override
    public void pathTo(Goal destination) {
        throw new UnsupportedOperationException("Called pathTo() on NullElytraBehavior");
    }

    @Override
    public void resetState() {

    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        throw new UnsupportedOperationException("Called onTick on NullElytraProcess");
    }

    @Override
    public void onLostControl() {

    }

    @Override
    public String displayName0() {
        return "NullElytraProcess";
    }

    @Override
    public boolean isLoaded() {
        return false;
    }

    @Override
    public boolean isSafeToCancel() {
        return true;
    }

    @Override
    public boolean isLanding() {
        return false;
    }
}
