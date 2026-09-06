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
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IInputOverrideHandler;
import princeps.api.utils.input.Input;
import princeps.behavior.Behavior;
import princeps.behavior.SurvivalBehavior;
import princeps.pathing.movement.MovementHelper;
import princeps.process.BuilderProcess;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;

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

    /** The keys through which something is actively steering the body, as opposed to merely clicking or looking. */
    private static final Input[] BODY_INPUTS = {
            Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.JUMP, Input.SNEAK,
    };

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;
    private princeps.process.BuilderProcess supportMiningOwner;

    public InputOverrideHandler(Princeps princeps) {
        super(princeps);
        this.blockBreakHelper = new BlockBreakHelper(princeps.getPlayerContext(), this::allowsMining);
        this.blockPlaceHelper = new BlockPlaceHelper(princeps);
    }

    boolean allowsMining(net.minecraft.core.BlockPos target) {
        var controlling = princeps.getPathingControlManager().mostRecentInControl().orElse(null);
        observeMiningOwner(controlling);
        var executing = princeps.getPathingBehavior().getCurrent();
        if (executing != null && !executing.allowsModelRemoval(target, controlling, blockBreakHelper::isBreakingBlock)) return false;
        return supportMiningOwner == null || supportMiningOwner.allowsSupportRemoval(target);
    }

    void observeMiningOwner(princeps.api.process.IPrincepsProcess controlling) {
        princeps.process.BuilderProcess next = controlling instanceof princeps.process.BuilderProcess builder ? builder : null;
        if (supportMiningOwner != null && supportMiningOwner != next) supportMiningOwner.revokeSupportRepair();
        supportMiningOwner = next;
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        if (input == null) {
            return false;
        }
        // THE LEDGE GUARD. It has to sit on the READ, not on any of the writes -- see onlyTheCrouchIsHoldingUs.
        if (input == Input.SNEAK && onlyTheCrouchIsHoldingUs()) {
            return true;
        }
        return this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * True while the body is standing over a hole and only the crouch edge-clamp is keeping it out of it.
     *
     * <p>MEASURED, AutoMapArt run of 2026-08-18, 09:50:09, on a picture suspended at y=128. The bot bridges its own
     * platform outward: {@link princeps.pathing.movement.movements.MovementTraverse} walks to the lip of the gap,
     * holds {@link Input#SNEAK} so the clamp keeps the body on the block behind it, and asks
     * {@code MovementHelper.attemptToPlaceABlock} for the bridge block. The last aim line of that run puts the body
     * at z=46985.298 -- a 0.6-wide hitbox overlapping its support by 0.002 blocks, which is exactly where vanilla
     * parks a CROUCHING player and nowhere a walking one can come to rest.
     *
     * <p>Then the crouch was taken away from underneath it, and three separate callers do that, none of them wrong
     * on its own terms. {@link princeps.pathing.movement.Movement#update()} clears every forced key when a movement
     * reports a complete status, and {@code UNREACHABLE} is complete -- which is what {@code attemptToPlaceABlock}
     * returns the moment the cell under the bridge is a template pixel the bot has just run out of.
     * {@link princeps.process.BuilderProcess} clears all keys and cancels the path on the tick it is paused, which
     * is how the auction-house restock announces itself. Stopping a near-path does the same at the hand-over from
     * approach to build. In that run the first two fired together, because both are triggered by the same event --
     * the last block of a colour leaving the hotbar. The bot fell 61 blocks and the picture was over at 4635 of
     * 16512 cells.
     *
     * <p>So the guard cannot live at any of those writes; a fourth one would reopen the hole. It lives on the read
     * every consumer already goes through -- {@link PlayerMovementInput} asks this very method for the crouch -- and
     * it speaks only when NOTHING else is driving the body. A movement that steps off a ledge on purpose always
     * holds a movement key while it does so, so descending, falling and parkour never see this; a body with an empty
     * input map standing over air is never doing that deliberately.
     */
    private boolean onlyTheCrouchIsHoldingUs() {
        if (!nothingIsDrivingTheBody()) {
            return false; // a movement is in charge, and it is allowed to step off
        }
        if (princeps.getPathingControlManager().mostRecentInControl().isEmpty()) {
            return false; // nothing of ours is running this tick: the body belongs to the operator
        }
        LocalPlayer player = ctx.player();
        if (player == null || !player.onGround()) {
            return false; // already in the air, where a crouch catches nothing
        }
        BetterBlockPos feet = ctx.playerFeet();
        // A real floor under the block the body occupies is an ordinary stand, and the guard stays quiet for it.
        return !MovementHelper.canWalkOn(ctx, feet.below());
    }

    /** Whether every movement key has been let go, i.e. whatever was steering the body no longer is. */
    private boolean nothingIsDrivingTheBody() {
        for (Input input : BODY_INPUTS) {
            // The raw map on purpose: asking isInputForcedDown here would consult the guard that calls this.
            if (this.inputForceStateMap.getOrDefault(input, false)) {
                return false;
            }
        }
        return true;
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
        observeMiningOwner(event.getType() == TickEvent.Type.OUT ? null
                : princeps.getPathingControlManager().mostRecentInControl().orElse(null));
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        // A blacklisted glitch block (kept re-appearing after breaking): stop forcing the attack so we never
        // hammer it, AND so the bot's stuck detection (forcing forward but NOT attacking) can fire and route /
        // RTP away instead of pinning against an unbreakable block forever.
        if (isInputForcedDown(Input.CLICK_LEFT) && blockBreakHelper.isAimingAtBlacklisted()) {
            setInputForceState(Input.CLICK_LEFT, false);
        }
        // Pause block-breaking while auto-survival is consuming (eating / mending-repair): switching the main
        // hand to food or XP while the forced attack keeps hitting a block is the "mine and eat at once" glitch.
        // The two are made mutually exclusive here so it can never happen, regardless of tick ordering.
        final SurvivalBehavior survival = princeps.getSurvivalBehavior();
        final boolean consuming = survival != null && survival.isConsuming();
        boolean requestedBreak = isInputForcedDown(Input.CLICK_LEFT) && !consuming;
        // Direct builder clicks and navigation's obstacle/stuck clicks share this actuator. Recheck the held
        // item and current AIR mask here so a later hotbar change or stale route cannot widen a one-block cut.
        if (requestedBreak && princeps.getBuilderProcess() instanceof BuilderProcess builder
                && ctx.objectMouseOver() instanceof BlockHitResult hit
                && !builder.ordinaryExcavationBreakAllowed(hit.getBlockPos())) {
            requestedBreak = false;
            setInputForceState(Input.CLICK_LEFT, false);
            blockBreakHelper.stopBreakingBlock();
        }
        blockBreakHelper.tick(requestedBreak);
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

    public BlockPlaceHelper getBlockPlaceHelper() {
        return blockPlaceHelper;
    }
}
