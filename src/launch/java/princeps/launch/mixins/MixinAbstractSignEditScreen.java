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

import princeps.utils.accessor.ISignEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Read-only: says which sign the open editor edits and which of its two sides.
 *
 * <p>Nothing is injected and nothing is cancelled — the builder drives the screen through its own public
 * {@code charTyped} / {@code keyPressed} / {@code onClose}, which is the same path a keyboard takes, so the packet
 * that finally carries the text is vanilla's own. All this mixin does is answer "whose editor is this", which the
 * screen otherwise keeps to itself.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class MixinAbstractSignEditScreen implements ISignEditScreen {

    @Shadow
    @Final
    protected SignBlockEntity sign;

    @Shadow
    @Final
    private boolean isFrontText;

    @Override
    public BlockPos getEditedSignPos() {
        return this.sign == null ? null : this.sign.getBlockPos();
    }

    @Override
    public boolean isEditingFrontText() {
        return this.isFrontText;
    }
}
