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

package princeps.launch.mixins;

import princeps.api.PrincepsAPI;
import princeps.api.IPrinceps;
import princeps.api.event.events.ChatEvent;
import princeps.utils.accessor.IGuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static princeps.api.command.IPrincepsChatControl.FORCE_COMMAND_PREFIX;

@Mixin(Screen.class)
public abstract class MixinScreen implements IGuiScreen {

    //TODO: switch to enum extention with mixin 9.0 or whenever Mumfrey gets around to it
    @Inject(method = "defaultHandleGameClickEvent", at = @At(value = "HEAD"), cancellable = true)
    private static void handleCustomClickEvent(final ClickEvent clickEvent, final Minecraft minecraft, final Screen screen, final CallbackInfo ci) {
        if (clickEvent == null) {
            return;
        }
        if (!(clickEvent instanceof ClickEvent.RunCommand(String command))) return;
        if (!command.startsWith(FORCE_COMMAND_PREFIX)) {
            return;
        }
        IPrinceps princeps = PrincepsAPI.getProvider().getPrimaryPrinceps();
        if (princeps != null) {
            princeps.getGameEventHandler().onSendChatMessage(new ChatEvent(command));
        }
        ci.cancel();
    }
}
