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

package princeps.behavior;

import princeps.Princeps;
import princeps.api.behavior.IBehavior;
import princeps.api.utils.IPlayerContext;

/**
 * A type of game event listener that is given {@link Princeps} instance context.
 *
 * @author Brady
 * @since 8/1/2018
 */
public class Behavior implements IBehavior {

    public final Princeps princeps;
    public final IPlayerContext ctx;

    protected Behavior(Princeps princeps) {
        this.princeps = princeps;
        this.ctx = princeps.getPlayerContext();
    }
}
