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

package princeps.utils;

import princeps.Princeps;
import princeps.api.PrincepsAPI;
import princeps.api.event.events.TickEvent;
import princeps.api.utils.IInputOverrideHandler;
import princeps.api.utils.input.Input;
import princeps.behavior.Behavior;
import princeps.behavior.SurvivalBehavior;
import net.minecraft.client.player.KeyboardInput;

import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    public InputOverrideHandler(Princeps princeps) {
        super(princeps);
        this.blockBreakHelper = new BlockBreakHelper(princeps.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(princeps.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        // Pause block-breaking while auto-survival is consuming (eating / mending-repair): switching the main
        // hand to food or XP while the forced attack keeps hitting a block is the "mine and eat at once" glitch.
        // The two are made mutually exclusive here so it can never happen, regardless of tick ordering.
        final SurvivalBehavior survival = princeps.getSurvivalBehavior();
        final boolean consuming = survival != null && survival.isConsuming();
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT) && !consuming);
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT) && !consuming);

        // Keep the character on the bot-owned input (which reads only forced inputs, never the keyboard)
        // when Princeps is controlling OR when suppressPlayerKeyboard is set. The latter is used by the
        // freecam: while flying the detached camera the character must never respond to WASD — only to the
        // bot's own forced inputs (basehunt). With an empty forced map it simply stands still.
        if (inControl() || Princeps.settings().suppressPlayerKeyboard.value) {
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return princeps.getPathingBehavior().isPathing() || princeps != PrincepsAPI.getProvider().getPrimaryPrinceps();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }
}
