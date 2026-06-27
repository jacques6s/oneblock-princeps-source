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

import princeps.api.IPrinceps;
import princeps.api.cache.IWaypoint;
import princeps.api.cache.IWaypointCollection;
import princeps.api.command.exception.CommandException;
import princeps.api.command.helpers.TabCompleteHelper;

import java.util.Comparator;
import java.util.stream.Stream;

public enum ForWaypoints implements IDatatypeFor<IWaypoint[]> {
    INSTANCE;

    @Override
    public IWaypoint[] get(IDatatypeContext ctx) throws CommandException {
        final String input = ctx.getConsumer().getString();
        final IWaypoint.Tag tag = IWaypoint.Tag.getByName(input);

        // If the input doesn't resolve to a valid tag, resolve by name
        return tag == null
                ? getWaypointsByName(ctx.getPrinceps(), input)
                : getWaypointsByTag(ctx.getPrinceps(), tag);
    }

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
        return new TabCompleteHelper()
                .append(getWaypointNames(ctx.getPrinceps()))
                .sortAlphabetically()
                .prepend(IWaypoint.Tag.getAllNames())
                .filterPrefix(ctx.getConsumer().getString())
                .stream();
    }

    public static IWaypointCollection waypoints(IPrinceps princeps) {
        return princeps.getWorldProvider().getCurrentWorld().getWaypoints();
    }

    public static IWaypoint[] getWaypoints(IPrinceps princeps) {
        return waypoints(princeps).getAllWaypoints().stream()
                .sorted(Comparator.comparingLong(IWaypoint::getCreationTimestamp).reversed())
                .toArray(IWaypoint[]::new);
    }

    public static String[] getWaypointNames(IPrinceps princeps) {
        return Stream.of(getWaypoints(princeps))
                .map(IWaypoint::getName)
                .filter(name -> !name.isEmpty())
                .toArray(String[]::new);
    }

    public static IWaypoint[] getWaypointsByTag(IPrinceps princeps, IWaypoint.Tag tag) {
        return waypoints(princeps).getByTag(tag).stream()
                .sorted(Comparator.comparingLong(IWaypoint::getCreationTimestamp).reversed())
                .toArray(IWaypoint[]::new);
    }

    public static IWaypoint[] getWaypointsByName(IPrinceps princeps, String name) {
        return Stream.of(getWaypoints(princeps))
                .filter(waypoint -> waypoint.getName().equalsIgnoreCase(name))
                .toArray(IWaypoint[]::new);
    }
}
