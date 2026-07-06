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

package princeps.command;

import princeps.Princeps;
import princeps.api.PrincepsAPI;
import princeps.api.Settings;
import princeps.api.command.argument.ICommandArgument;
import princeps.api.command.exception.CommandNotEnoughArgumentsException;
import princeps.api.command.exception.CommandNotFoundException;
import princeps.api.command.helpers.TabCompleteHelper;
import princeps.api.command.manager.ICommandManager;
import princeps.api.event.events.ChatEvent;
import princeps.api.event.events.TabCompleteEvent;
import princeps.api.utils.Helper;
import princeps.api.utils.SettingsUtil;
import princeps.behavior.Behavior;
import princeps.command.argument.ArgConsumer;
import princeps.command.argument.CommandArguments;
import princeps.command.manager.CommandManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Tuple;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static princeps.api.command.IPrincepsChatControl.FORCE_COMMAND_PREFIX;

public class ExamplePrincepsControl extends Behavior implements Helper {

    private static final Settings settings = PrincepsAPI.getSettings();
    private final ICommandManager manager;

    public ExamplePrincepsControl(Princeps princeps) {
        super(princeps);
        this.manager = princeps.getCommandManager();
    }

    @Override
    public void onSendChatMessage(ChatEvent event) {
        String msg = event.getMessage();
        String prefix = settings.prefix.value;
        String secondary = settings.secondaryPrefix.value;
        boolean forceRun = msg.startsWith(FORCE_COMMAND_PREFIX);
        boolean primaryHit = settings.prefixControl.value && msg.startsWith(prefix);
        // secondary always-available prefix: rescue hatch when the host client's own command system swallows the
        // configured prefix before it reaches this hook (e.g. a "."-prefixed client cancelling ".princeps ...").
        boolean secondaryHit = !primaryHit && settings.prefixControl.value
                && !secondary.isEmpty() && msg.startsWith(secondary);
        if (primaryHit || secondaryHit || forceRun) {
            event.cancel();
            String commandStr = msg.substring(forceRun ? FORCE_COMMAND_PREFIX.length()
                    : primaryHit ? prefix.length() : secondary.length());
            if (!runCommand(commandStr) && !commandStr.trim().isEmpty()) {
                new CommandNotFoundException(CommandManager.expand(commandStr).getA()).handle(null, null);
            }
        } else if ((settings.chatControl.value || settings.chatControlAnyway.value) && runCommand(msg)) {
            event.cancel();
        }
    }

    private void logRanCommand(String command, String rest) {
        if (settings.echoCommands.value) {
            String msg = command + rest;
            String toDisplay = settings.censorRanCommands.value ? command + " ..." : msg;
            MutableComponent component = Component.literal(String.format("> %s", toDisplay));
            component.setStyle(component.getStyle()
                    .withColor(ChatFormatting.WHITE)
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("Click to rerun command")
                    ))
                    .withClickEvent(new ClickEvent.RunCommand(
                            FORCE_COMMAND_PREFIX + msg
                    )));
            logDirect(component);
        }
    }

    public boolean runCommand(String msg) {
        if (msg.trim().equalsIgnoreCase("damn")) {
            logDirect("daniel");
            return false;
        } else if (msg.trim().equalsIgnoreCase("orderpizza")) {
            try {
                Util.getPlatform().openUri("https://www.dominos.com/en/pages/order/");
            } catch (Exception ignored) {}
            return false;
        }
        if (msg.isEmpty()) {
            return this.runCommand("help");
        }
        Tuple<String, List<ICommandArgument>> pair = CommandManager.expand(msg);
        String command = pair.getA();
        String rest = msg.substring(pair.getA().length());
        ArgConsumer argc = new ArgConsumer(this.manager, pair.getB());
        if (!argc.hasAny()) {
            Settings.Setting setting = settings.byLowerName.get(command.toLowerCase(Locale.US));
            if (setting != null) {
                logRanCommand(command, rest);
                if (setting.getValueClass() == Boolean.class) {
                    this.manager.execute(String.format("set toggle %s", setting.getName()));
                } else {
                    this.manager.execute(String.format("set %s", setting.getName()));
                }
                return true;
            }
        } else if (argc.hasExactlyOne()) {
            for (Settings.Setting setting : settings.allSettings) {
                if (setting.isJavaOnly()) {
                    continue;
                }
                if (setting.getName().equalsIgnoreCase(pair.getA())) {
                    logRanCommand(command, rest);
                    try {
                        this.manager.execute(String.format("set %s %s", setting.getName(), argc.getString()));
                    } catch (CommandNotEnoughArgumentsException ignored) {} // The operation is safe
                    return true;
                }
            }
        }

        // If the command exists, then handle echoing the input
        if (this.manager.getCommand(pair.getA()) != null) {
            logRanCommand(command, rest);
        }

        return this.manager.execute(pair);
    }

    @Override
    public void onPreTabComplete(TabCompleteEvent event) {
        if (!settings.prefixControl.value) {
            return;
        }
        String prefix = event.prefix;
        String commandPrefix = settings.prefix.value;
        if (!prefix.startsWith(commandPrefix)) {
            return;
        }
        String msg = prefix.substring(commandPrefix.length());
        List<ICommandArgument> args = CommandArguments.from(msg, true);
        Stream<String> stream = tabComplete(msg);
        if (args.size() == 1) {
            stream = stream.map(x -> commandPrefix + x);
        }
        event.completions = stream.toArray(String[]::new);
    }

    public Stream<String> tabComplete(String msg) {
        try {
            List<ICommandArgument> args = CommandArguments.from(msg, true);
            ArgConsumer argc = new ArgConsumer(this.manager, args);
            if (argc.hasAtMost(2)) {
                if (argc.hasExactly(1)) {
                    return new TabCompleteHelper()
                            .addCommands(this.manager)
                            .addSettings()
                            .filterPrefix(argc.getString())
                            .stream();
                }
                Settings.Setting setting = settings.byLowerName.get(argc.getString().toLowerCase(Locale.US));
                if (setting != null && !setting.isJavaOnly()) {
                    if (setting.getValueClass() == Boolean.class) {
                        TabCompleteHelper helper = new TabCompleteHelper();
                        if ((Boolean) setting.value) {
                            helper.append("true", "false");
                        } else {
                            helper.append("false", "true");
                        }
                        return helper.filterPrefix(argc.getString()).stream();
                    } else {
                        return Stream.of(SettingsUtil.settingValueToString(setting));
                    }
                }
            }
            return this.manager.tabComplete(msg);
        } catch (CommandNotEnoughArgumentsException ignored) { // Shouldn't happen, the operation is safe
            return Stream.empty();
        }
    }
}
