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

import princeps.api.PrincepsAPI;
import princeps.api.utils.IPlayerContext;
import princeps.utils.accessor.IPlayerControllerMP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * @author Brady
 * @since 8/25/2018
 */
public final class BlockBreakHelper {
    // base ticks between block breaks caused by tick logic
    private static final int BASE_BREAK_DELAY = 1;

    private final IPlayerContext ctx;
    private final java.util.function.Predicate<BlockPos> miningAllowed;
    private final java.util.function.BooleanSupplier allowExcavationRetries;
    private boolean wasHitting;
    private int breakDelayTimer = 0;
    // Break timing is execution-only (never replayed in path/reach prediction), so a plain RNG is fine here.
    private final java.util.Random breakRng = new java.util.Random();
    // Human sighting reaction (humanizedBreakSightDelay): the block the crosshair currently rests on, and the
    // remaining 1..3-tick wait between FIRST sighting it and the first press. Tracked every tick — including while
    // the post-break cooldown runs — so the reaction overlaps the mining rhythm instead of stacking onto it.
    private BlockPos sightedPos;
    private int sightDelayTimer;
    // Mining-rhythm window: >0 while a block broke within the last few ticks. During a held-button rhythm
    // (instamine runs: no post-break cooldown at all) a human does NOT re-react per block — without this window
    // every consecutive instamined block would pay a fresh 1..3-tick sighting stall (a 2-4x cadence regression,
    // confirmed in review). The full reaction only applies when the crosshair lands after a genuine idle/re-aim.
    private int rhythmTimer;
    /** One-shot execution intent, consumed by the next helper tick. V2 never sets it and keeps its sampled cadence. */
    private boolean deterministicThisTick;

    private static final int COLUMN_SCAN_HEIGHT = 24;
    private final BlockBreakRetry breakRetry = new BlockBreakRetry();

    BlockBreakHelper(IPlayerContext ctx, java.util.function.Predicate<BlockPos> miningAllowed) {
        this(ctx, miningAllowed, () -> false);
    }

    BlockBreakHelper(IPlayerContext ctx) {
        this(ctx, ignored -> true, () -> false);
    }

    BlockBreakHelper(IPlayerContext ctx, java.util.function.BooleanSupplier allowExcavationRetries) {
        this(ctx, ignored -> true, allowExcavationRetries);
    }

    BlockBreakHelper(IPlayerContext ctx, java.util.function.Predicate<BlockPos> miningAllowed,
                     java.util.function.BooleanSupplier allowExcavationRetries) {
        this.ctx = ctx;
        this.miningAllowed = miningAllowed;
        this.allowExcavationRetries = allowExcavationRetries;
    }

    /** Unresolved rejection for terminal planner consumers. An AutoDig retry does not imply planner completion. */
    public boolean isBlacklisted(BlockPos pos) {
        return breakRetry.rejected(ctx.world(), pos);
    }

    private boolean shouldSuppressBreak(BlockPos pos) {
        return allowExcavationRetries.getAsBoolean()
                ? breakRetry.blocked(ctx.world(), pos, monotonicMillis()) : isBlacklisted(pos);
    }

    /** True when the crosshair currently rests on a blacklisted glitch block — the caller should not force a break. */
    public boolean isAimingAtBlacklisted() {
        final HitResult trace = ctx.objectMouseOver();
        return trace != null && trace.getType() == HitResult.Type.BLOCK
                && shouldSuppressBreak(((BlockHitResult) trace).getBlockPos());
    }

    /** Forget all blacklisted blocks (e.g. on a world/dimension change). */
    public void clearBlacklist() {
        breakRetry.clear();
    }

    public void observeServerChange(BlockPos pos, BlockState state) {
        breakRetry.serverChanged(ctx.world(), pos, state);
    }

    public String breakRetryDiagnosis() {
        return ctx.objectMouseOver() instanceof BlockHitResult hit
                ? breakRetry.diagnosis(ctx.world(), hit.getBlockPos(), monotonicMillis()) : "none";
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Number of contiguous sand/gravel-like blocks directly above {@code pos}. */
    private int fallableColumnAbove(BlockPos pos) {
        int count = 0;
        for (int dy = 1; dy <= COLUMN_SCAN_HEIGHT; dy++) {
            BlockState above = ctx.world().getBlockState(pos.above(dy));
            if (!(above.getBlock() instanceof FallingBlock)) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * Records a completed break and distinguishes a server reset from a falling column refilling the cell.
     * A real refill consumes one block above; a glitch reset leaves that column unchanged.
     */
    private void noteBreak(BlockPos pos, BlockState before) {
        breakRetry.completed(ctx.world(), pos, before, fallableColumnAbove(pos), monotonicMillis());
    }

    public void stopBreakingBlock() {
        // The player controller will never be null, but the player can be
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
        }
        wasHitting = false;
    }

    /** The controller is continuing a real multi-tick break, rather than merely requesting a target. */
    public boolean isBreakingBlock() {
        if (!wasHitting || ctx.player() == null || ctx.minecraft().screen != null
                || !(ctx.objectMouseOver() instanceof BlockHitResult hit)) {
            return false;
        }
        BlockPos active = ((IPlayerControllerMP) ctx.minecraft().gameMode).getCurrentBlock();
        return active != null && active.equals(hit.getBlockPos())
                && !ctx.world().getBlockState(active).isAir();
    }

    /** Actual controller damage for this selected target; the caller must compare consecutive samples. */
    public float breakingProgressAt(BlockPos target) {
        if (target == null || !isBreakingBlock() || ctx.objectMouseOver().getType() != HitResult.Type.BLOCK
                || !ctx.world().hasChunkAt(target)) {
            return Float.NaN;
        }
        IPlayerControllerMP controller = (IPlayerControllerMP) ctx.minecraft().gameMode;
        return target.equals(controller.getCurrentBlock()) ? controller.getDestroyProgress() : Float.NaN;
    }

    /** Mark the next helper tick as a replayable V3 break tick (no sampled sight delay or cooldown variance). */
    public void requestDeterministicBreak() {
        this.deterministicThisTick = true;
    }

    public void tick(boolean isLeftClick) {
        final boolean deterministic = this.deterministicThisTick;
        this.deterministicThisTick = false;
        HitResult trace = ctx.objectMouseOver();
        boolean isBlockTrace = trace != null && trace.getType() == HitResult.Type.BLOCK;
        // Check the actual crosshair, not only the route's planned obstruction. A foreground hit or a newly
        // dependent block must not destroy a schematic support between path costing and the real mining tick.
        if (isLeftClick && isBlockTrace && !miningAllowed.test(((BlockHitResult) trace).getBlockPos())) {
            stopBreakingBlock();
            return;
        }
        if (rhythmTimer > 0) {
            rhythmTimer--;
        }

        // Sighting reaction: when the crosshair NEWLY lands on a target block, a human doesn't press the same tick —
        // arm a 1..3 tick wait from the moment of sighting. This runs BEFORE the cooldown early-return so the wait
        // counts down in parallel with the post-break cooldown (overlap, not stack: throughput ~unchanged). Leaving
        // the block resets the sighting, so re-acquiring it re-arms — "erst bei anvisieren starten".
        if (!deterministic && PrincepsAPI.getSettings().humanizedLook.value
                && PrincepsAPI.getSettings().humanizedBreakSightDelay.value) {
            if (isLeftClick && isBlockTrace) {
                BlockPos pos = ((BlockHitResult) trace).getBlockPos();
                if (!pos.equals(sightedPos)) {
                    sightedPos = pos;
                    sightDelayTimer = 1 + breakRng.nextInt(3); // fire 1..3 ticks after the crosshair lands
                } else if (sightDelayTimer > 0) {
                    sightDelayTimer--;
                }
            } else {
                sightedPos = null;
                sightDelayTimer = 0;
            }
        } else {
            sightedPos = null;
            sightDelayTimer = 0;
        }

        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            return;
        }

        if (isLeftClick && isBlockTrace) {
            final BlockPos target = ((BlockHitResult) trace).getBlockPos();
            if (shouldSuppressBreak(target)) {
                // Release the controller during a bounded retry wait. A wait never counts as completed work.
                stopBreakingBlock();
                wasHitting = false;
                return;
            }
            // still reacting to a freshly sighted block — but never delay an in-progress multi-tick break, and never
            // interrupt a live mining rhythm (held button, block broke moments ago: a human doesn't re-react per block)
            if (!wasHitting && sightDelayTimer > 0 && rhythmTimer == 0) {
                return;
            }
            ctx.playerController().setHittingBlock(wasHitting);
            if (ctx.playerController().hasBrokenBlock()) {
                rhythmTimer = 3; // a break just completed; the follow-up press below continues the rhythm
                ctx.playerController().syncHeldItem();
                ctx.playerController().clickBlock(target, ((BlockHitResult) trace).getDirection());
                ctx.player().swing(InteractionHand.MAIN_HAND);
            } else {
                BlockState before = ctx.world().getBlockState(target);
                if (ctx.playerController().onPlayerDamageBlock(target, ((BlockHitResult) trace).getDirection())) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
                if (ctx.playerController().hasBrokenBlock()) { // block broken this tick
                    noteBreak(target, before);
                    rhythmTimer = 3; // keep the held-button rhythm alive across the cooldown boundary
                    // break delay timer only applies for multi-tick block breaks like vanilla
                    final int base = PrincepsAPI.getSettings().blockBreakSpeed.value - BASE_BREAK_DELAY;
                    // Jitter the post-break cooldown so the mining cadence is not a perfectly periodic metronome — a
                    // constant inter-break gap over thousands of blocks is a periodogram tell; a human's click-to-next
                    // gap varies by a few ticks. Symmetric ~Gaussian jitter (sum-of-3 uniforms) preserves the mean
                    // throughput exactly (verified 0.0% delta); floored at 2 so two breaks never collapse into a tick.
                    if (!deterministic && PrincepsAPI.getSettings().humanizedLook.value && base > 2) {
                        final double g = this.breakRng.nextDouble() + this.breakRng.nextDouble() + this.breakRng.nextDouble() - 1.5;
                        breakDelayTimer = Math.max(2, base + (int) Math.round(g * 2.2));
                    } else {
                        breakDelayTimer = base;
                    }
                    // must reset controller's destroy delay to prevent the client from delaying itself unnecessarily
                    ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                }
            }
            // if true, we're breaking a block. if false, we broke the block this tick
            wasHitting = !ctx.playerController().hasBrokenBlock();
            // this value will be reset by the MC client handling mouse keys
            // since we're not spoofing the click keybind to the client, the client will stop the break if isDestroyingBlock is true
            // we store and restore this value on the next tick to determine if we're breaking a block
            ctx.playerController().setHittingBlock(false);
        } else {
            wasHitting = false;
        }
    }
}
