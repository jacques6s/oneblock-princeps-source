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
import princeps.api.command.Command;
import princeps.api.command.argument.IArgConsumer;
import princeps.api.command.exception.CommandException;
import princeps.api.command.exception.CommandInvalidStateException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class VersionCommand extends Command {

    public VersionCommand(IPrinceps princeps) {
        super(princeps, "version");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) {
            throw new CommandInvalidStateException("Null version (this is normal in a dev environment)");
        } else {
            logDirect(String.format("You are running Princeps v%s", version));
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "View the Princeps version";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The version command prints the version of Princeps you're currently running.",
                "",
                "Usage:",
                "> version - View version information, if present"
        );
    }
}
