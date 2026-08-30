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

package princeps.pathing.movement.movements;

import princeps.Princeps;
import princeps.api.IPrinceps;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;
import princeps.api.utils.VecUtils;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.Movement;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.movement.MovementState;
// Imported, never written out in full: the inherited field `princeps` shadows the package root, so
// `princeps.process.builder.BuildTrace` does not resolve here.
import princeps.process.builder.BuildTrace;
import princeps.utils.BlockStateInterface;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public class MovementPillar extends Movement {

    public MovementPillar(IPrinceps princeps, BetterBlockPos start, BetterBlockPos end) {
        super(princeps, start, end, new BetterBlockPos[]{start.above(2)}, start);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }

    public static double cost(CalculationContext context, int x, int y, int z) {
        BlockState fromState = context.get(x, y, z);
        Block from = fromState.getBlock();
        boolean ladder = from == Blocks.LADDER || from == Blocks.VINE;
        BlockState fromDown = context.get(x, y - 1, z);
        if (!ladder) {
            if (fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE) {
                return COST_INF; // can't pillar from a ladder or vine onto something that isn't also climbable
            }
            if (fromDown.getBlock() instanceof SlabBlock && fromDown.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                return COST_INF; // can't pillar up from a bottom slab onto a non ladder
            }
        }
        if (from == Blocks.VINE && !hasAgainst(context, x, y, z)) { // TODO this vine can't be climbed, but we could place a pillar still since vines are replacable, no? perhaps the pillar jump would be impossible because of the slowdown actually.
            return COST_INF;
        }
        BlockState toBreak = context.get(x, y + 2, z);
        Block toBreakBlock = toBreak.getBlock();
        if (toBreakBlock instanceof FenceGateBlock) { // see issue #172
            return COST_INF;
        }
        BlockState srcUp = null;
        if (MovementHelper.isWater(toBreak) && MovementHelper.isWater(fromState)) { // TODO should this also be allowed if toBreakBlock is air?
            srcUp = context.get(x, y + 1, z);
            if (MovementHelper.isWater(srcUp)) {
                return LADDER_UP_ONE_COST; // allow ascending pillars of water, but only if we're already in one
            }
        }
        double placeCost = 0;
        if (!ladder) {
            // we need to place a block where we started to jump on it
            placeCost = context.costOfPlacingAt(x, y, z, fromState);
            if (placeCost >= COST_INF) {
                return COST_INF;
            }
            // The block placed here becomes the base we jump onto. It MUST be a standable full cube — in a
            // schematic build the placed block is the schematic's own block, which may be a fence/wall/thin block
            // you cannot stand on (fence tops are ~1.5 tall, non-full): refuse the pillar so it never loops
            // jumping on a fence it just placed.
            if (!context.placedBlockIsStandable(x, y, z, fromState)) {
                return COST_INF;
            }
            if (fromDown.getBlock() instanceof AirBlock) {
                placeCost += 0.1; // slightly (1/200th of a second) penalize pillaring on what's currently air
            }
        }
        if ((MovementHelper.isLiquid(fromState) && !MovementHelper.canPlaceAgainst(context.bsi, x, y - 1, z, fromDown)) || (MovementHelper.isLiquid(fromDown) && context.assumeWalkOnWater)) {
            // otherwise, if we're standing in water, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is true, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is false, we must have ascended to here, or sneak backplaced, so it is possible to pillar again
            return COST_INF;
        }
        if ((from == Blocks.LILY_PAD || from instanceof CarpetBlock) && !fromDown.getFluidState().isEmpty()) {
            // to ascend here we'd have to break the block we are standing on
            return COST_INF;
        }
        double hardness = MovementHelper.getMiningDurationTicks(context, x, y + 2, z, toBreak, true);
        if (hardness >= COST_INF) {
            return COST_INF;
        }
        if (hardness != 0) {
            if (toBreakBlock == Blocks.LADDER || toBreakBlock == Blocks.VINE) {
                hardness = 0; // we won't actually need to break the ladder / vine because we're going to use it
            } else {
                BlockState check = context.get(x, y + 3, z); // the block on top of the one we're going to break, could it fall on us?
                if (check.getBlock() instanceof FallingBlock) {
                    // see MovementAscend's identical check for breaking a falling block above our head
                    if (srcUp == null) {
                        srcUp = context.get(x, y + 1, z);
                    }
                    if (!(toBreakBlock instanceof FallingBlock) || !(srcUp.getBlock() instanceof FallingBlock)) {
                        return COST_INF;
                    }
                }
                // this is commented because it may have had a purpose, but it's very unclear what it was. it's from the minebot era.
                //if (!MovementHelper.canWalkOn(context, chkPos, check) || MovementHelper.canWalkThrough(context, chkPos, check)) {//if the block above where we want to break is not a full block, don't do it
                // TODO why does canWalkThrough mean this action is COST_INF?
                // FallingBlock makes sense, and !canWalkOn deals with weird cases like if it were lava
                // but I don't understand why canWalkThrough makes it impossible
                //    return COST_INF;
                //}
            }
        }
        if (ladder) {
            return LADDER_UP_ONE_COST + hardness * 5;
        } else {
            return JUMP_ONE_BLOCK_COST + placeCost + context.jumpPenalty + hardness;
        }
    }

    public static boolean hasAgainst(CalculationContext context, int x, int y, int z) {
        return MovementHelper.isBlockNormalCube(context.get(x + 1, y, z)) ||
                MovementHelper.isBlockNormalCube(context.get(x - 1, y, z)) ||
                MovementHelper.isBlockNormalCube(context.get(x, y, z + 1)) ||
                MovementHelper.isBlockNormalCube(context.get(x, y, z - 1));
    }

    public static BlockPos getAgainst(CalculationContext context, BetterBlockPos vine) {
        if (MovementHelper.isBlockNormalCube(context.get(vine.north()))) {
            return vine.north();
        }
        if (MovementHelper.isBlockNormalCube(context.get(vine.south()))) {
            return vine.south();
        }
        if (MovementHelper.isBlockNormalCube(context.get(vine.east()))) {
            return vine.east();
        }
        if (MovementHelper.isBlockNormalCube(context.get(vine.west()))) {
            return vine.west();
        }
        return null;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().y < src.y) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        BlockState fromDown = BlockStateInterface.get(ctx, src);
        if (MovementHelper.isWater(fromDown) && MovementHelper.isWater(ctx, dest)) {
            // stay centered while swimming up a water column
            state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()), false));
            Vec3 destCenter = VecUtils.getBlockPosCenter(dest);
            if (Math.abs(ctx.player().position().x - destCenter.x) > 0.2 || Math.abs(ctx.player().position().z - destCenter.z) > 0.2) {
                state.setInput(Input.MOVE_FORWARD, true);
            }
            if (ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            return state;
        }
        boolean ladder = fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE;
        boolean vine = fromDown.getBlock() == Blocks.VINE;
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                VecUtils.getBlockPosCenter(positionToPlace),
                ctx.playerRotations());
        if (!ladder) {
            state.setTarget(new MovementState.MovementTarget(ctx.playerRotations().withPitch(rotation.getPitch()), true));
        }

        boolean blockIsThere = MovementHelper.canWalkOn(ctx, src) || ladder;
        if (ladder) {
            BlockPos against = vine ? getAgainst(new CalculationContext(princeps), src) : src.relative(fromDown.getValue(LadderBlock.FACING).getOpposite());
            if (against == null) {
                logDirect("Unable to climb vines. Consider disabling allowVines.");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (ctx.playerFeet().equals(against.above()) || ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (MovementHelper.isBottomSlab(BlockStateInterface.get(ctx, src.below()))) {
                state.setInput(Input.JUMP, true);
            }
            /*
            if (thePlayer.getPosition0().getX() != from.getX() || thePlayer.getPosition0().getZ() != from.getZ()) {
                Princeps.moveTowardsBlock(from);
            }
             */

            MovementHelper.moveTowards(ctx, state, against);
            return state;
        } else {
            // Get ready to place a throwaway block
            if (!((Princeps) princeps).getInventoryBehavior().selectThrowawayForLocation(true, src.x, src.y, src.z)) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            state.setInput(Input.SNEAK, true);
            // since (lower down) we only right click once player.isSneaking, and that happens the tick after we request to sneak

            double diffX = ctx.player().position().x - (dest.getX() + 0.5);
            double diffZ = ctx.player().position().z - (dest.getZ() + 0.5);
            double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
            double flatMotion = Math.sqrt(ctx.player().getDeltaMovement().x * ctx.player().getDeltaMovement().x + ctx.player().getDeltaMovement().z * ctx.player().getDeltaMovement().z);
            if (dist > 0.17) {//why 0.17? because it seemed like a good number, that's why
                //[explanation added after princeps port lol] also because it needs to be less than 0.2 because of the 0.3 sneak limit
                //and 0.17 is reasonably less than 0.2

                // If it's been more than forty ticks of trying to jump and we aren't done yet, go forward, maybe we are stuck
                state.setInput(Input.MOVE_FORWARD, true);

                // revise our target to both yaw and pitch if we're going to be moving forward
                state.setTarget(new MovementState.MovementTarget(rotation, true));
            } else if (flatMotion < 0.05) {
                // If our Y coordinate is above our goal, stop jumping
                state.setInput(Input.JUMP, ctx.player().position().y < dest.getY());
            }


            if (!blockIsThere) {
                BlockState frState = BlockStateInterface.get(ctx, src);
                Block fr = frState.getBlock();
                // TODO: Evaluate usage of getMaterial().isReplaceable()
                if (!(fr instanceof AirBlock || frState.canBeReplaced())) {
                    RotationUtils.reachable(ctx, src, ctx.playerController().getBlockReachDistance())
                            .map(MovementState.MovementTarget::forBreak)
                            .ifPresent(state::setTarget);
                    state.setInput(Input.JUMP, false); // breaking is like 5x slower when you're jumping
                    // Gate the press on the crosshair actually being ON the block, like every other break site.
                    // This press used to be unconditional, which was tolerable while the precise aim snapped in a
                    // single tick — but with the bell-curve arc the aim sweeps here over several ticks, and a held
                    // CLICK_LEFT digs whatever foreground block the LIVE trace crosses mid-arc (wrong-block digs,
                    // confirmed in the adversarial review). The gate binds the dig to the intended block; the
                    // slower aim just means the dig starts once the crosshair lands.
                    if (ctx.isLookingAt(src)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    blockIsThere = false;
                } else if (ctx.player().isCrouching() && (ctx.isLookingAt(src.below()) || ctx.isLookingAt(src)) && ctx.player().position().y > dest.getY() + 0.1) {
                    // THE ONE PLACEMENT IN THE WHOLE PATHFINDER THAT USED TO WRITE NOTHING. It does not go through
                    // MovementHelper.attemptToPlaceABlock, so it produced neither a trace line nor the template guard.
                    // Measured on the facings run 762769a0: 3 of the 11 leftover cobblestone blocks had no event of ANY
                    // kind, so every census of helper blocks was short by exactly the ones placed here -- including a
                    // two-high pillar at x=97 that nothing in the log could account for. The click itself is unchanged;
                    // only its silence is.
                    // Same licence as the choke point it bypasses -- and it has to be repeated here for exactly the
                    // reason the comment above gives: this click never passes through attemptToPlaceABlock, so it gets
                    // neither the template guard nor the scaffold licence for free.
                    //
                    // The template case must be exempted here TOO, and this is not theoretical: the pillar at
                    // 73,-58,67 in run 50f40a49 put down the stone that cell wanted and was DONE one tick later. A
                    // pillar that happens to fill a template cell is free progress, exactly as at the other site.
                    boolean pillarWouldPlaceTemplateBlock = ((Princeps) princeps).getInventoryBehavior()
                            .wouldPlaceTemplateBlockAt(src.getX(), src.getY(), src.getZ());
                    if (pillarWouldPlaceTemplateBlock
                            && !princeps.getBuilderProcess().templatePlacementIsLicensedAt(src)) {
                        BuildTrace.cell(
                                BuildTrace.tickNow(), "ROW-FRONTIER-REFUSED",
                                src.getX(), src.getY(), src.getZ(),
                                "pillar template pixel belongs to a later Map-Art slice " + BuildTrace.intentNow());
                        return state.setStatus(MovementStatus.UNREACHABLE);
                    }
                    if (!pillarWouldPlaceTemplateBlock
                            && (!princeps.getBuilderProcess().scaffoldIsLicensedAt(src)
                                || !MovementHelper.currentRouteLicence(princeps).permitsPlacement(src))) {
                        BuildTrace.cell(
                                BuildTrace.tickNow(), "SCAFFOLD-REFUSED",
                                src.getX(), src.getY(), src.getZ(),
                                "no proven stance justifies a pillar block here by=pillar " + BuildTrace.intentNow());
                        // UNREACHABLE rather than a quiet skip: a pillar that neither places nor fails would crouch
                        // here forever, and the router would never learn to route otherwise.
                        return state.setStatus(MovementStatus.UNREACHABLE);
                    }
                    BuildTrace.cell(
                            BuildTrace.tickNow(), "PILLAR",
                            src.getX(), src.getY(), src.getZ(),
                            "by=pillar climbing to " + dest.getX() + "," + dest.getY() + "," + dest.getZ()
                                    + " " + BuildTrace.intentNow());
                    BuildTrace.intendWorldChange("pillar", src.getX(), src.getY(), src.getZ(),
                            "climbing to " + dest.getX() + "," + dest.getY() + "," + dest.getZ());
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        // If we are at our goal and the block below us is placed
        if (ctx.playerFeet().equals(dest) && blockIsThere) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        return state;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.below())) {
            Block block = BlockStateInterface.getBlock(ctx, src.below());
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                state.setInput(Input.SNEAK, true);
            }
        }
        if (MovementHelper.isWater(ctx, dest.above())) {
            return true;
        }
        return super.prepared(state);
    }
}
