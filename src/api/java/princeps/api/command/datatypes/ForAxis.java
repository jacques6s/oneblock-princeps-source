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

package princeps.api.command.datatypes;

import princeps.api.command.exception.CommandException;
import princeps.api.command.helpers.TabCompleteHelper;
import net.minecraft.core.Direction;

import java.util.Locale;
import java.util.stream.Stream;

public enum ForAxis implements IDatatypeFor<Direction.Axis> {
    INSTANCE;

    @Override
    public Direction.Axis get(IDatatypeContext ctx) throws CommandException {
        return Direction.Axis.valueOf(ctx.getConsumer().getString().toUpperCase(Locale.US));
    }

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
        return new TabCompleteHelper()
                .append(Stream.of(Direction.Axis.values())
                        .map(Direction.Axis::getName).map(String::toLowerCase))
                .filterPrefix(ctx.getConsumer().getString())
                .stream();
    }
}
