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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the TEST server a pickaxe that breaks a face-oriented three-by-three, so the bot can be measured against one.
 *
 * <p>Why this has to exist: several servers hand players a tool that destroys a three-by-three at once, and the
 * builder now prefers cells whose square lies wholly inside the work. That preference was reasoned about, written
 * and shipped without ever being run against a world that actually behaves that way -- the bench server breaks one
 * block per swing like any vanilla server, so every measurement of the feature was really a measurement of its
 * absence. A tool nobody can test is a guess with a version number.
 *
 * <p>The clicked FACE owns the plane: UP/DOWN spreads through X/Z, NORTH/SOUTH through X/Y, and EAST/WEST through
 * Z/Y. That is also the only rule that makes a downward entry shaft and a horizontal tunnel two orientations of the
 * same tool instead of two unrelated guesses. Capture the face at HEAD, while the target still exists; by RETURN the
 * block is air and a fresh pick ray can legitimately see through it to another face.
 *
 * <p>INERT UNLESS ASKED FOR. It does nothing at all unless {@code princeps.bench.areapick} names the tool, and
 * only the dev server's run configuration sets that. A player's client never has it, so this code cannot alter
 * anything for anyone who is not deliberately running the bench.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class MixinServerPlayerGameMode {

    @Shadow @Final protected ServerPlayer player;

    /** Guards the recursion: the neighbours are destroyed through the same method that triggered this. */
    @Unique private boolean princeps$breakingTheSquare;
    @Unique private BlockPos princeps$primaryTarget;
    @Unique private Direction princeps$primaryFace;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void princeps$capturePrimaryFace(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (princeps$breakingTheSquare || !princeps$isAreaToolHeld()) {
            return;
        }
        HitResult result = player.pick(6.0D, 1.0F, false);
        if (result != null && result.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) result).getBlockPos().equals(pos)) {
            princeps$primaryTarget = pos.immutable();
            princeps$primaryFace = ((BlockHitResult) result).getDirection();
        } else {
            princeps$primaryTarget = null;
            princeps$primaryFace = null;
        }
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void princeps$breakTheSquare(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (princeps$breakingTheSquare || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if (!princeps$isAreaToolHeld() || princeps$primaryFace == null
                || princeps$primaryTarget == null || !princeps$primaryTarget.equals(pos)) {
            return;
        }

        Direction face = princeps$primaryFace;
        princeps$primaryTarget = null;
        princeps$primaryFace = null;
        princeps$breakingTheSquare = true;
        StringBuilder removed = new StringBuilder();
        int count = 1; // the clicked block itself is already gone
        try {
            ServerPlayerGameMode self = (ServerPlayerGameMode) (Object) this;
            for (int first = -1; first <= 1; first++) {
                for (int second = -1; second <= 1; second++) {
                    if (first == 0 && second == 0) {
                        continue; // the block that was actually clicked is already gone
                    }
                    BlockPos neighbour;
                    if (face.getAxis() == Direction.Axis.Y) {
                        neighbour = pos.offset(first, 0, second);
                    } else if (face.getAxis() == Direction.Axis.Z) {
                        neighbour = pos.offset(first, second, 0);
                    } else {
                        neighbour = pos.offset(0, second, first);
                    }
                    BlockState state = player.level().getBlockState(neighbour);
                    if (state.isAir()) {
                        continue;
                    }
                    // Bedrock and the like: a player could not mine these by hand, so the tool must not either.
                    // Without this the bench would quietly dig through the world's floor.
                    if (state.getDestroySpeed(player.level(), neighbour) < 0.0F) {
                        continue;
                    }
                    if (self.destroyBlock(neighbour)) {
                        count++;
                        removed.append(';').append(neighbour.getX()).append(',')
                                .append(neighbour.getY()).append(',').append(neighbour.getZ());
                    }
                }
            }
        } finally {
            princeps$breakingTheSquare = false;
        }
        // The measurement, taken where the truth is. The client can only infer what the server destroyed, and an
        // inference is exactly what must not be trusted about a feature whose whole claim is "one swing does the
        // work of nine". Two numbers come out of these lines: the yield per swing, which says whether the tool is
        // being exploited at all, and how many of the removed cells lay OUTSIDE the requested area -- collateral
        // that no one asked for and that on a real server is someone else's ground.
        // Logger fetched here rather than held in a static field: a static initialiser inside a Mixin has to be
        // merged into the target's <clinit>, which works until it does not, and the failure would be a crash at
        // class load on every client. A map lookup a few hundred times a run is not worth that risk.
        LogManager.getLogger("PrincepsAreaPick").info("[AREAPICK] who={} hit={},{},{} face={} removed={} cells={}{}",
                player.getGameProfile().name(), pos.getX(), pos.getY(), pos.getZ(), face, count,
                pos.getX() + "," + pos.getY() + "," + pos.getZ(), removed);
    }

    @Unique
    private boolean princeps$isAreaToolHeld() {
        String wanted = System.getProperty("princeps.bench.areapick");
        if (wanted == null || wanted.isEmpty()) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        return !held.isEmpty()
                && princeps$key(held.getHoverName().getString()).contains(princeps$key(wanted));
    }

    /**
     * Letters and digits only, lowercased -- the same reduction the client's tool detection uses.
     *
     * <p>Deliberately identical, because the two have to agree on what counts as the same name. A display name
     * arrives decorated (colour codes, stars, a rarity in brackets) and comparing raw strings makes the match
     * depend on decoration nobody thinks about.
     */
    @Unique
    private static String princeps$key(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
