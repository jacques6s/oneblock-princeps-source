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

package princeps.utils;

import princeps.Princeps;
import princeps.api.process.IPrincepsProcess;
import princeps.api.utils.Helper;
import princeps.api.utils.IPlayerContext;

public abstract class PrincepsProcessHelper implements IPrincepsProcess, Helper {

    protected final Princeps princeps;
    protected final IPlayerContext ctx;

    public PrincepsProcessHelper(Princeps princeps) {
        this.princeps = princeps;
        this.ctx = princeps.getPlayerContext();
    }

    @Override
    public boolean isTemporary() {
        return false;
    }
}
