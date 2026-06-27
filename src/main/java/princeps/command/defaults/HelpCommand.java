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
import princeps.api.command.ICommand;
import princeps.api.command.argument.IArgConsumer;
import princeps.api.command.exception.CommandException;
import princeps.api.command.exception.CommandNotFoundException;
import princeps.api.command.helpers.Paginator;
import princeps.api.command.helpers.TabCompleteHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static princeps.api.command.IPrincepsChatControl.FORCE_COMMAND_PREFIX;

public class HelpCommand extends Command {

    public HelpCommand(IPrinceps princeps) {
        super(princeps, "help", "?");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (!args.hasAny() || args.is(Integer.class)) {
            Paginator.paginate(
                    args, new Paginator<>(
                            this.princeps.getCommandManager().getRegistry().descendingStream()
                                    .filter(command -> !command.hiddenFromHelp())
                                    .collect(Collectors.toList())
                    ),
                    () -> logDirect("All Princeps commands (clickable):"),
                    command -> {
                        String names = String.join("/", command.getNames());
                        String name = command.getNames().get(0);
                        MutableComponent shortDescComponent = Component.literal(" - " + command.getShortDesc());
                        shortDescComponent.setStyle(shortDescComponent.getStyle().withColor(ChatFormatting.DARK_GRAY));
                        MutableComponent namesComponent = Component.literal(names);
                        namesComponent.setStyle(namesComponent.getStyle().withColor(ChatFormatting.WHITE));
                        MutableComponent hoverComponent = Component.literal("");
                        hoverComponent.setStyle(hoverComponent.getStyle().withColor(ChatFormatting.GRAY));
                        hoverComponent.append(namesComponent);
                        hoverComponent.append("\n" + command.getShortDesc());
                        hoverComponent.append("\n\nClick to view full help");
                        String clickCommand = FORCE_COMMAND_PREFIX + String.format("%s %s", label, command.getNames().get(0));
                        MutableComponent component = Component.literal(name);
                        component.setStyle(component.getStyle().withColor(ChatFormatting.GRAY));
                        component.append(shortDescComponent);
                        component.setStyle(component.getStyle()
                                .withHoverEvent(new HoverEvent.ShowText(hoverComponent))
                                .withClickEvent(new ClickEvent.RunCommand(clickCommand)));
                        return component;
                    },
                    FORCE_COMMAND_PREFIX + label
            );
        } else {
            String commandName = args.getString().toLowerCase();
            ICommand command = this.princeps.getCommandManager().getCommand(commandName);
            if (command == null) {
                throw new CommandNotFoundException(commandName);
            }
            logDirect(String.format("%s - %s", String.join(" / ", command.getNames()), command.getShortDesc()));
            logDirect("");
            command.getLongDesc().forEach(this::logDirect);
            logDirect("");
            MutableComponent returnComponent = Component.literal("Click to return to the help menu");
            returnComponent.setStyle(returnComponent.getStyle().withClickEvent(new ClickEvent.RunCommand(
                    FORCE_COMMAND_PREFIX + label
            )));
            logDirect(returnComponent);
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .addCommands(this.princeps.getCommandManager())
                    .filterPrefix(args.getString())
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "View all commands or help on specific ones";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Using this command, you can view detailed help information on how to use certain commands of Princeps.",
                "",
                "Usage:",
                "> help - Lists all commands and their short descriptions.",
                "> help <command> - Displays help information on a specific command."
        );
    }
}
