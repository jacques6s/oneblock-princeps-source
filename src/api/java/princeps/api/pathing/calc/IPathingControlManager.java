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

package princeps.api.pathing.calc;

import princeps.api.process.IPrincepsProcess;
import princeps.api.process.PathingCommand;

import java.util.Optional;

/**
 * @author leijurv
 */
public interface IPathingControlManager {

    /**
     * Registers a process with this pathing control manager. See {@link IPrincepsProcess} for more details.
     *
     * @param process The process
     * @see IPrincepsProcess
     */
    void registerProcess(IPrincepsProcess process);

    /**
     * @return The most recent {@link IPrincepsProcess} that had control
     */
    Optional<IPrincepsProcess> mostRecentInControl();

    /**
     * @return The most recent pathing command executed
     */
    Optional<PathingCommand> mostRecentCommand();
}
