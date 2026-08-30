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

package princeps.utils.accessor;

import net.minecraft.core.BlockPos;

/**
 * Which sign, and which of its two sides, the open sign editor is editing.
 *
 * <p>{@code AbstractSignEditScreen} keeps its {@code sign} field {@code protected} and its {@code isFrontText} field
 * {@code private}, so a screen that has opened is otherwise anonymous: the builder can see that A sign editor is up
 * and cannot see WHICH sign it belongs to. Typing into it blind is how a schematic's text ends up on the neighbour's
 * sign — silently, because both signs are signs and both end up with four lines on them.
 *
 * <p>Two accessors close that. The builder writes text only into the editor for the cell it just placed, and refuses
 * (loudly, by name) when the editor belongs to anything else.
 */
public interface ISignEditScreen {

    /** The position of the sign this editor is editing. */
    BlockPos getEditedSignPos();

    /**
     * Is this editor editing the FRONT side?
     *
     * <p>Decided by the game, never by the caller: {@code SignItem} opens a freshly placed sign's editor with
     * {@code frontText = true} unconditionally, and a later right-click opens whichever side
     * {@code SignBlockEntity.isFacingFrontText} says the player is standing on. So the side is an observation, and a
     * plan that wants the back side has to prove a stance behind the sign rather than ask for one.
     */
    boolean isEditingFrontText();
}
