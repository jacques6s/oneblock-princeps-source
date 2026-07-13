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

    // ── glitch-block blacklist ──────────────────────────────────────────────────────────────────────────
    // A block that keeps re-appearing after we break it (the server re-sets it / it isn't really breakable and
    // just "glitches") is abandoned after a few rapid re-breaks so the bot never hammers it forever. Once
    // blacklisted it is never mined again; the caller's stuck detection then re-routes / RTPs away.
    private static final int REGROW_LIMIT = 2;          // MORE than this many rapid re-breaks of the SAME block → blacklist
    private static final long REGROW_WINDOW_MS = 4000L; // re-breaks farther apart than this are treated as unrelated
    private final java.util.Set<Long> blacklist = new java.util.HashSet<>();
    private long lastBrokenPosPacked = Long.MIN_VALUE;
    private int regrowCount;
    private long lastBrokenAtMs;

    BlockBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** True if this block was abandoned as an un-breakable glitch block (re-set itself too many times). */
    public boolean isBlacklisted(BlockPos pos) {
        return pos != null && blacklist.contains(pos.asLong());
    }

    /** True when the crosshair currently rests on a blacklisted glitch block — the caller should not force a break. */
    public boolean isAimingAtBlacklisted() {
        final HitResult trace = ctx.objectMouseOver();
        return trace != null && trace.getType() == HitResult.Type.BLOCK
                && blacklist.contains(((BlockHitResult) trace).getBlockPos().asLong());
    }

    /** Forget all blacklisted blocks (e.g. on a world/dimension change). */
    public void clearBlacklist() {
        blacklist.clear();
        lastBrokenPosPacked = Long.MIN_VALUE;
        regrowCount = 0;
    }

    /** Records a completed break of {@code pos} and blacklists it once it has regrown+been re-broken too often. */
    private void noteBreak(BlockPos pos) {
        final long packed = pos.asLong();
        final long now = System.currentTimeMillis();
        if (packed == this.lastBrokenPosPacked && (now - this.lastBrokenAtMs) < REGROW_WINDOW_MS) {
            this.regrowCount++;
        } else {
            this.regrowCount = 1;
        }
        this.lastBrokenPosPacked = packed;
        this.lastBrokenAtMs = now;
        if (this.regrowCount > REGROW_LIMIT) {
            this.blacklist.add(packed);
        }
    }

    public void stopBreakingBlock() {
        // The player controller will never be null, but the player can be
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
            wasHitting = false;
        }
    }

    public void tick(boolean isLeftClick) {
        HitResult trace = ctx.objectMouseOver();
        boolean isBlockTrace = trace != null && trace.getType() == HitResult.Type.BLOCK;
        if (rhythmTimer > 0) {
            rhythmTimer--;
        }

        // Sighting reaction: when the crosshair NEWLY lands on a target block, a human doesn't press the same tick —
        // arm a 1..3 tick wait from the moment of sighting. This runs BEFORE the cooldown early-return so the wait
        // counts down in parallel with the post-break cooldown (overlap, not stack: throughput ~unchanged). Leaving
        // the block resets the sighting, so re-acquiring it re-arms — "erst bei anvisieren starten".
        if (PrincepsAPI.getSettings().humanizedLook.value && PrincepsAPI.getSettings().humanizedBreakSightDelay.value) {
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
            if (blacklist.contains(target.asLong())) {
                // Abandoned glitch block: never mine it again. Stop any in-progress break and report not-hitting
                // so the caller's stuck detection takes over (re-route / RTP) instead of hammering it forever.
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
                if (ctx.playerController().onPlayerDamageBlock(target, ((BlockHitResult) trace).getDirection())) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
                if (ctx.playerController().hasBrokenBlock()) { // block broken this tick
                    noteBreak(target); // count regrows of the SAME block → blacklist a glitching one
                    rhythmTimer = 3; // keep the held-button rhythm alive across the cooldown boundary
                    // break delay timer only applies for multi-tick block breaks like vanilla
                    final int base = PrincepsAPI.getSettings().blockBreakSpeed.value - BASE_BREAK_DELAY;
                    // Jitter the post-break cooldown so the mining cadence is not a perfectly periodic metronome — a
                    // constant inter-break gap over thousands of blocks is a periodogram tell; a human's click-to-next
                    // gap varies by a few ticks. Symmetric ~Gaussian jitter (sum-of-3 uniforms) preserves the mean
                    // throughput exactly (verified 0.0% delta); floored at 2 so two breaks never collapse into a tick.
                    if (PrincepsAPI.getSettings().humanizedLook.value && base > 2) {
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
