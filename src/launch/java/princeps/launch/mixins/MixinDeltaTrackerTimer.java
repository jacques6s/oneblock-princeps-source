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

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the CLIENT's game clock faster, for the test bench only.
 *
 * <p>Minecraft's simulation is tick-quantised: movement per tick, placement cooldowns, redstone, entity AI. Running
 * the clock at N x 20 Hz therefore does not change what happens, only how long it takes to watch — which is exactly
 * what a bench wants and exactly what {@code /tick rate} does NOT give, because that command speeds the server while
 * the client, and with it the bot's own logic, keeps running at 20 Hz. Measured on this setup: server at 60/s, client
 * still 20 ticks per 1.00 second. Accelerating one side alone is worse than not accelerating at all, since it warps
 * the world relative to the thing under test. This is the client half; the bench sets the server half over RCON.
 *
 * <p>Gated on {@code -Dprinceps.bench.timescale}. Absent or 1, this mixin does nothing at all, so the shipped client
 * is untouched.
 *
 * <p>What does NOT scale: network round trips, and any wall-clock timer on the server (autosave, timeouts). Princeps'
 * build and pathing logic is tick-based throughout, so neither affects what the bench measures — but that is a claim
 * to verify per scenario, not to assume, which is what the bench's 1x-versus-Nx equivalence check is for.
 */
@Mixin(DeltaTracker.Timer.class)
public class MixinDeltaTrackerTimer {

    private static final float TIMESCALE = resolveTimescale();

    private static float resolveTimescale() {
        String raw = System.getProperty("princeps.bench.timescale");
        if (raw == null || raw.isEmpty()) {
            return 1.0F;
        }
        try {
            float scale = Float.parseFloat(raw);
            // A bench that runs slower than real time is a mistake, not a feature; so is one that outruns any
            // machine. Clamp rather than let a typo produce a run nobody can interpret.
            return Math.max(1.0F, Math.min(20.0F, scale));
        } catch (NumberFormatException e) {
            return 1.0F;
        }
    }

    /**
     * The timer is constructed with the tick rate it will run at. Scaling it here means every consumer -- the game
     * loop, partial ticks, interpolation -- sees one consistent clock, rather than a tick count patched after the
     * fact that the renderer would then disagree with.
     */
    // Static, and it has to be: HEAD of a constructor is before the super() call, where `this` does not yet exist,
    // and mixin rejects an instance handler there outright.
    @ModifyVariable(method = "<init>", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static float princeps$scaleTickRate(float tickRate) {
        return TIMESCALE == 1.0F ? tickRate : tickRate * TIMESCALE;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void princeps$announceTimescale(CallbackInfo ci) {
        if (TIMESCALE != 1.0F) {
            System.out.println("[BENCH] client timescale " + TIMESCALE + "x -- game clock at "
                    + (20.0F * TIMESCALE) + " ticks per second");
        }
    }
}
