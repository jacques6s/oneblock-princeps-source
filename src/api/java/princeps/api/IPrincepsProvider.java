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

package princeps.api;

import princeps.api.cache.IWorldScanner;
import princeps.api.command.ICommand;
import princeps.api.command.ICommandSystem;
import princeps.api.schematic.ISchematicSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;
import java.util.Objects;

/**
 * Provides the present {@link IPrinceps} instances, as well as non-princeps instance related APIs.
 *
 * @author leijurv
 */
public interface IPrincepsProvider {

    /**
     * Returns the primary {@link IPrinceps} instance. This instance is persistent, and
     * is represented by the local player that is created by the game itself, not a "bot"
     * player through Princeps.
     *
     * @return The primary {@link IPrinceps} instance.
     */
    IPrinceps getPrimaryPrinceps();

    /**
     * Returns all of the active {@link IPrinceps} instances. This includes the local one
     * returned by {@link #getPrimaryPrinceps()}.
     *
     * @return All active {@link IPrinceps} instances.
     * @see #getPrincepsForPlayer(LocalPlayer)
     */
    List<IPrinceps> getAllPrincepss();

    /**
     * Provides the {@link IPrinceps} instance for a given {@link LocalPlayer}.
     *
     * @param player The player
     * @return The {@link IPrinceps} instance.
     */
    default IPrinceps getPrincepsForPlayer(LocalPlayer player) {
        for (IPrinceps princeps : this.getAllPrincepss()) {
            if (Objects.equals(player, princeps.getPlayerContext().player())) {
                return princeps;
            }
        }
        return null;
    }

    /**
     * Provides the {@link IPrinceps} instance for a given {@link Minecraft}.
     *
     * @param minecraft The minecraft
     * @return The {@link IPrinceps} instance.
     */
    default IPrinceps getPrincepsForMinecraft(Minecraft minecraft) {
        for (IPrinceps princeps : this.getAllPrincepss()) {
            if (Objects.equals(minecraft, princeps.getPlayerContext().minecraft())) {
                return princeps;
            }
        }
        return null;
    }

    /**
     * Provides the {@link IPrinceps} instance for the player with the specified connection.
     *
     * @param connection The connection
     * @return The {@link IPrinceps} instance.
     */
    default IPrinceps getPrincepsForConnection(ClientPacketListener connection) {
        for (IPrinceps princeps : this.getAllPrincepss()) {
            final LocalPlayer player = princeps.getPlayerContext().player();
            if (player != null && player.connection == connection) {
                return princeps;
            }
        }
        return null;
    }

    /**
     * Creates and registers a new {@link IPrinceps} instance using the specified {@link Minecraft}. The existing
     * instance is returned if already registered.
     *
     * @param minecraft The minecraft
     * @return The {@link IPrinceps} instance
     */
    IPrinceps createPrinceps(Minecraft minecraft);

    /**
     * Destroys and removes the specified {@link IPrinceps} instance. If the specified instance is the
     * {@link #getPrimaryPrinceps() primary princeps}, this operation has no effect and will return {@code false}.
     *
     * @param princeps The princeps instance to remove
     * @return Whether the princeps instance was removed
     */
    boolean destroyPrinceps(IPrinceps princeps);

    /**
     * Returns the {@link IWorldScanner} instance. This is not a type returned by
     * {@link IPrinceps} implementation, because it is not linked with {@link IPrinceps}.
     *
     * @return The {@link IWorldScanner} instance.
     */
    IWorldScanner getWorldScanner();

    /**
     * Returns the {@link ICommandSystem} instance. This is not bound to a specific {@link IPrinceps}
     * instance because {@link ICommandSystem} itself controls global behavior for {@link ICommand}s.
     *
     * @return The {@link ICommandSystem} instance.
     */
    ICommandSystem getCommandSystem();

    /**
     * @return The {@link ISchematicSystem} instance.
     */
    ISchematicSystem getSchematicSystem();
}
