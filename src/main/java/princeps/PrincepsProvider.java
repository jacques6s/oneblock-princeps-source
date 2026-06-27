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

package princeps;

import princeps.api.IPrinceps;
import princeps.api.IPrincepsProvider;
import princeps.api.cache.IWorldScanner;
import princeps.api.command.ICommandSystem;
import princeps.api.schematic.ISchematicSystem;
import princeps.cache.FasterWorldScanner;
import princeps.command.CommandSystem;
import princeps.command.ExamplePrincepsControl;
import princeps.utils.schematic.SchematicSystem;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Brady
 * @since 9/29/2018
 */
public final class PrincepsProvider implements IPrincepsProvider {

    private final List<IPrinceps> all;
    private final List<IPrinceps> allView;

    public PrincepsProvider() {
        this.all = new CopyOnWriteArrayList<>();
        this.allView = Collections.unmodifiableList(this.all);

        // Setup chat control, just for the primary instance
        final Princeps primary = (Princeps) this.createPrinceps(Minecraft.getInstance());
        primary.registerBehavior(ExamplePrincepsControl::new);
    }

    @Override
    public IPrinceps getPrimaryPrinceps() {
        return this.all.get(0);
    }

    @Override
    public List<IPrinceps> getAllPrincepss() {
        return this.allView;
    }

    @Override
    public synchronized IPrinceps createPrinceps(Minecraft minecraft) {
        IPrinceps princeps = this.getPrincepsForMinecraft(minecraft);
        if (princeps == null) {
            this.all.add(princeps = new Princeps(minecraft));
        }
        return princeps;
    }

    @Override
    public synchronized boolean destroyPrinceps(IPrinceps princeps) {
        return princeps != this.getPrimaryPrinceps() && this.all.remove(princeps);
    }

    @Override
    public IWorldScanner getWorldScanner() {
        return FasterWorldScanner.INSTANCE;
    }

    @Override
    public ICommandSystem getCommandSystem() {
        return CommandSystem.INSTANCE;
    }

    @Override
    public ISchematicSystem getSchematicSystem() {
        return SchematicSystem.INSTANCE;
    }
}
