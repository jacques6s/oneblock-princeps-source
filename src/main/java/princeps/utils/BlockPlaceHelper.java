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
import princeps.api.utils.IPlayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;

    private final IPlayerContext ctx;
    private int rightClickTimer;
    // Place timing is execution-only (never replayed in path/reach prediction), so a plain RNG is fine here.
    private final java.util.Random placeRng = new java.util.Random();

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    public void tick(boolean rightClickRequested) {
        if (rightClickTimer > 0) {
            rightClickTimer--;
            return;
        }
        HitResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || ctx.player().isHandsBusy() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            return;
        }
        // Jitter the inter-place cooldown so repeated placing (bridging, pillaring, scaffolding) is not a perfectly
        // periodic metronome — a constant place gap is a periodogram tell a human never produces. Symmetric
        // ~Gaussian jitter (sum-of-3 uniforms) preserves the mean throughput (verified 0.0% at the default), floored
        // at 1. Gated on humanizedLook (raw Princeps / rightClickSpeed<=3 keep the exact constant). Same treatment as
        // BlockBreakHelper's break cadence; does not touch the look tuning.
        final int placeBase = Princeps.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        if (Princeps.settings().humanizedLook.value && placeBase > 2) {
            final double g = this.placeRng.nextDouble() + this.placeRng.nextDouble() + this.placeRng.nextDouble() - 1.5;
            rightClickTimer = Math.max(1, placeBase + (int) Math.round(g * 1.5));
        } else {
            rightClickTimer = placeBase;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver) == InteractionResult.SUCCESS) {
                ctx.player().swing(hand);
                return;
            }
            if (!ctx.player().getItemInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) == InteractionResult.SUCCESS) {
                return;
            }
        }
    }
}
