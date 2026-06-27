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

package princeps.command.defaults;

import princeps.api.IPrinceps;
import princeps.api.command.ICommand;

import java.util.*;

public final class DefaultCommands {

    private DefaultCommands() {
    }

    public static List<ICommand> createAll(IPrinceps princeps) {
        Objects.requireNonNull(princeps);
        List<ICommand> commands = new ArrayList<>(Arrays.asList(
                new HelpCommand(princeps),
                new SetCommand(princeps),
                new CommandAlias(princeps, Arrays.asList("modified", "mod", "princeps", "modifiedsettings"), "List modified settings", "set modified"),
                new CommandAlias(princeps, "reset", "Reset all settings or just one", "set reset"),
                new GoalCommand(princeps),
                new GotoCommand(princeps),
                new PathCommand(princeps),
                new ProcCommand(princeps),
                new ETACommand(princeps),
                new VersionCommand(princeps),
                new RepackCommand(princeps),
                new BuildCommand(princeps),
                //new SchematicaCommand(princeps),
                new LitematicaCommand(princeps),
                new ComeCommand(princeps),
                new AxisCommand(princeps),
                new ForceCancelCommand(princeps),
                new GcCommand(princeps),
                new InvertCommand(princeps),
                new TunnelCommand(princeps),
                new RenderCommand(princeps),
                new FarmCommand(princeps),
                new FollowCommand(princeps),
                new PickupCommand(princeps),
                new ExploreFilterCommand(princeps),
                new ReloadAllCommand(princeps),
                new SaveAllCommand(princeps),
                new ExploreCommand(princeps),
                new BlacklistCommand(princeps),
                new FindCommand(princeps),
                new MineCommand(princeps),
                new ClickCommand(princeps),
                new SurfaceCommand(princeps),
                new ThisWayCommand(princeps),
                new WaypointsCommand(princeps),
                new CommandAlias(princeps, "sethome", "Sets your home waypoint", "waypoints save home"),
                new CommandAlias(princeps, "home", "Path to your home waypoint", "waypoints goto home"),
                new SelCommand(princeps),
                new ElytraCommand(princeps)
        ));
        ExecutionControlCommands prc = new ExecutionControlCommands(princeps);
        commands.add(prc.pauseCommand);
        commands.add(prc.resumeCommand);
        commands.add(prc.pausedCommand);
        commands.add(prc.cancelCommand);
        return Collections.unmodifiableList(commands);
    }
}
