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
import princeps.api.process.IGetToBlockProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class BlacklistCommand extends Command {

    public BlacklistCommand(IPrinceps princeps) {
        super(princeps, "blacklist");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        IGetToBlockProcess proc = princeps.getGetToBlockProcess();
        if (!proc.isActive()) {
            throw new CommandInvalidStateException("GetToBlockProcess is not currently active");
        }
        if (proc.blacklistClosest()) {
            logDirect("Blacklisted closest instances");
        } else {
            throw new CommandInvalidStateException("No known locations, unable to blacklist");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Blacklist closest block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "While going to a block this command blacklists the closest block so that Princeps won't attempt to get to it.",
                "",
                "Usage:",
                "> blacklist"
        );
    }
}
