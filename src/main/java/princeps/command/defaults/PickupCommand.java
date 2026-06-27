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
import princeps.api.command.datatypes.ItemById;
import princeps.api.command.exception.CommandException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class PickupCommand extends Command {

    public PickupCommand(IPrinceps princeps) {
        super(princeps, "pickup");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Set<Item> collecting = new HashSet<>();
        while (args.hasAny()) {
            Item item = args.getDatatypeFor(ItemById.INSTANCE);
            collecting.add(item);
        }
        if (collecting.isEmpty()) {
            princeps.getFollowProcess().pickup(stack -> true);
            logDirect("Picking up all items");
        } else {
            princeps.getFollowProcess().pickup(stack -> collecting.contains(stack.getItem()));
            logDirect("Picking up these items:");
            collecting.stream().map(BuiltInRegistries.ITEM::getKey).map(Identifier::toString).forEach(this::logDirect);
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        while (args.has(2)) {
            if (args.peekDatatypeOrNull(ItemById.INSTANCE) == null) {
                return Stream.empty();
            }
            args.get();
        }
        return args.tabCompleteDatatype(ItemById.INSTANCE);
    }

    @Override
    public String getShortDesc() {
        return "Pickup items";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Usage:",
                "> pickup - Pickup anything",
                "> pickup <item1> <item2> <...> - Pickup certain items"
        );
    }
}
