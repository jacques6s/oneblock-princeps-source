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
import princeps.api.pathing.PlacementLicence;
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
// Imported rather than written out: Movement carries a field named `princeps`, which shadows the package root, so
// `princeps.process.builder.BuildTrace` does not resolve inside this class.
import princeps.process.builder.BuildTrace;
import princeps.utils.BlockStateInterface;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;
import java.util.Set;

public class MovementTraverse extends Movement {

    /**
     * Did we have to place a bridge block or was it always there
     */
    private boolean wasTheBridgeBlockAlwaysThere = true;
    /** Once the back-place look has landed, keep edging out while the exact live-ray gate waits for placement range. */
    private boolean backplaceAimReady;

    public MovementTraverse(IPrinceps princeps, BetterBlockPos from, BetterBlockPos to) {
        super(princeps, from, to, new BetterBlockPos[]{to.above(), to}, to.below());
    }

    @Override
    public void reset() {
        super.reset();
        wasTheBridgeBlockAlwaysThere = true;
        backplaceAimReady = false;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest); // src.above means that we don't get caught in an infinite loop in water
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState pb0 = context.get(destX, y + 1, destZ);
        BlockState pb1 = context.get(destX, y, destZ);
        BlockState destOn = context.get(destX, y - 1, destZ);
        BlockState srcDown = context.get(x, y - 1, z);
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !context.assumeWalkOnWater && MovementHelper.canUseFrostWalker(context, destOn);
        if (frostWalker || MovementHelper.canWalkOn(context, destX, y - 1, destZ, destOn)) { //this is a walk, not a bridge
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            boolean sneaking = false;
            if (MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1)) {
                WC = context.waterWalkSpeed;
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // with frostwalker we can walk on water without the penalty, if we are sure we won't be using jesus
                } else if (destOn.getBlock() == Blocks.WATER) {
                    WC += context.walkOnWaterOnePenalty;
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (context.allowWalkOnMagmaBlocks && srcDownBlock.equals(Blocks.MAGMA_BLOCK)) {
                    sneaking = true;
                    WC += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && !sneaking && context.canSprint) {
                    // If there's nothing in the way, and this isn't water, and we aren't sneak placing
                    // We can sprint =D
                    // Don't check for soul sand, since we can sprint on that too
                    WC *= SPRINT_MULTIPLIER;
                }
                return WC;
            }
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                hardness1 *= 5;
                hardness2 *= 5;
            }
            return WC + hardness1 + hardness2;
        } else {//this is a bridge, so we need to place a block
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                return COST_INF;
            }
            if (MovementHelper.isReplaceable(destX, y - 1, destZ, destOn, context.bsi)) {
                boolean throughWater = MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1);
                if (MovementHelper.isWater(destOn) && throughWater) {
                    // this happens when assume walk on water is true and this is a traverse in water, which isn't allowed
                    return COST_INF;
                }
                double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, destOn);
                if (placeCost >= COST_INF) {
                    return COST_INF;
                }
                // We walk ONTO the bridged block — it must be a standable full cube (never a schematic fence/wall).
                if (!context.placedBlockIsStandable(destX, y - 1, destZ, destOn)) {
                    return COST_INF;
                }
                double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
                if (hardness1 >= COST_INF) {
                    return COST_INF;
                }
                double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
                double WC = throughWater ? context.waterWalkSpeed : WALK_ONE_BLOCK_COST;
                for (int i = 0; i < 5; i++) {
                    int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepX();
                    int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepY();
                    int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepZ();
                    if (againstX == x && againstZ == z) { // this would be a backplace
                        continue;
                    }
                    if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) { // found a side place option
                        return WC + placeCost + hardness1 + hardness2;
                    }
                }
                // now that we've checked all possible directions to side place, we actually need to backplace
                if (srcDownBlock == Blocks.SOUL_SAND || (srcDownBlock instanceof SlabBlock && srcDown.getValue(SlabBlock.TYPE) != SlabType.DOUBLE)) {
                    return COST_INF; // can't sneak and backplace against soul sand or half slabs (regardless of whether it's top half or bottom half) =/
                }
                if (!standingOnABlock) { // standing on water / swimming
                    return COST_INF; // this is obviously impossible
                }
                Block blockSrc = context.getBlock(x, y, z);
                if ((blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock) && !srcDown.getFluidState().isEmpty()) {
                    return COST_INF; // we can stand on these but can't place against them
                }
                WC = WC * (SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST);//since we are sneak backplacing, we are sneaking lol
                return WC + placeCost + hardness1 + hardness2;
            }
            return COST_INF;
        }
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        BlockState pb0 = BlockStateInterface.get(ctx, positionsToBreak[0]);
        BlockState pb1 = BlockStateInterface.get(ctx, positionsToBreak[1]);
        if (state.getStatus() != MovementStatus.RUNNING) {
            // if the setting is enabled
            if (!Princeps.settings().walkWhileBreaking.value) {
                return state;
            }
            // and if we're prepping (aka mining the block in front)
            if (state.getStatus() != MovementStatus.PREPPING) {
                return state;
            }
            // and if it's fine to walk into the blocks in front
            if (MovementHelper.avoidWalkingInto(pb0)) {
                return state;
            }
            if (MovementHelper.avoidWalkingInto(pb1)) {
                return state;
            }
            // and we aren't already pressed up against the block
            double dist = Math.max(Math.abs(ctx.player().position().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().position().z - (dest.getZ() + 0.5D)));
            if (dist < 0.83) {
                return state;
            }
            if (!state.getTarget().getRotation().isPresent()) {
                // this can happen rarely when the server lags and doesn't send the falling sand entity until you've already walked through the block and are now mining the next one
                return state;
            }

            // combine the yaw to the center of the destination, and the pitch to the specific block we're trying to break
            // it's safe to do this since the two blocks we break (in a traverse) are right on top of each other and so will have the same yaw
            float yawToDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), dest), ctx.playerRotations()).getYaw();
            float pitchToBreak = state.getTarget().getRotation().get().getPitch();
            if ((MovementHelper.isBlockNormalCube(pb0) || pb0.getBlock() instanceof AirBlock && (MovementHelper.isBlockNormalCube(pb1) || pb1.getBlock() instanceof AirBlock))) {
                // in the meantime, before we're right up against the block, we can break efficiently at this angle.
                // Jitter the old hardcoded 26 by a stable per-target amount so the pitch histogram doesn't spike at a
                // single constant (the efficient-break geometry tolerates several degrees); the look tremor rides on
                // top for per-tick continuity.
                pitchToBreak = 26f + ((Long.hashCode(dest.asLong()) & 7) - 3) * 1.1f; // ~23..30, stable per block
            }

            return state.setTarget(MovementState.MovementTarget.forBreak(
                            new Rotation(yawToDest, pitchToBreak)))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.SPRINT, true);
        }

        Block fd = BlockStateInterface.get(ctx, src.below()).getBlock();
        boolean ladder = fd == Blocks.LADDER || fd == Blocks.VINE;

        //sneak may have been set to true in the PREPPING state while mining an adjacent block, but we still want it to be true if the player is about to go on magma
        state.setInput(Input.SNEAK, Princeps.settings().allowWalkOnMagmaBlocks.value && MovementHelper.steppingOnBlocks(ctx).stream().anyMatch(block -> ctx.world().getBlockState(block).is(Blocks.MAGMA_BLOCK)));

        if (pb0.getBlock() instanceof DoorBlock || pb1.getBlock() instanceof DoorBlock) {
            boolean notPassable = pb0.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, src, dest) || pb1.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, dest, src);
            boolean canOpen = !(Blocks.IRON_DOOR.equals(pb0.getBlock()) || Blocks.IRON_DOOR.equals(pb1.getBlock()));

            if (notPassable && canOpen) {
                // Declared as its own kind: opening a door is a world change the census must be able to tell apart
                // from placing a block, because the two answer different questions about a block-free lane.
                BuildTrace.intendWorldChange("traverse-door", positionsToBreak[0].getX(),
                        positionsToBreak[0].getY(), positionsToBreak[0].getZ(), "opening to walk through");
                return state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), positionsToBreak[0]), ctx.playerRotations()), true))
                        .setInput(Input.CLICK_RIGHT, true);
            }
        }

        if (pb0.getBlock() instanceof FenceGateBlock || pb1.getBlock() instanceof FenceGateBlock) {
            BlockPos blocked = !MovementHelper.isGatePassable(ctx, positionsToBreak[0], src.above()) ? positionsToBreak[0]
                    : !MovementHelper.isGatePassable(ctx, positionsToBreak[1], src) ? positionsToBreak[1]
                    : null;
            if (blocked != null) {
                Optional<Rotation> rotation = RotationUtils.reachable(ctx, blocked);
                if (rotation.isPresent()) {
                    BuildTrace.intendWorldChange("traverse-gate", blocked.getX(), blocked.getY(), blocked.getZ(),
                            "opening to walk through");
                    return state.setTarget(new MovementState.MovementTarget(rotation.get(), true)).setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        boolean isTheBridgeBlockThere = MovementHelper.canWalkOn(ctx, positionToPlace) || ladder || MovementHelper.canUseFrostWalker(ctx, positionToPlace);
        BlockPos feet = ctx.playerFeet();
        if (feet.getY() != dest.getY() && !ladder) {
            logDebug("Wrong Y coordinate");
            if (feet.getY() < dest.getY()) {
                System.out.println("In movement traverse");
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }

        if (isTheBridgeBlockThere) {
            if (feet.equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (Princeps.settings().overshootTraverse.value && (feet.equals(dest.offset(getDirection())) || feet.equals(dest.offset(getDirection()).offset(getDirection())))) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            Block low = BlockStateInterface.get(ctx, src).getBlock();
            Block high = BlockStateInterface.get(ctx, src.above()).getBlock();
            if (ctx.player().position().y > src.y + 0.1D && !ctx.player().onGround() && (low == Blocks.VINE || low == Blocks.LADDER || high == Blocks.VINE || high == Blocks.LADDER)) {
                // hitting W could cause us to climb the ladder instead of going forward
                // wait until we're on the ground
                return state;
            }
            BlockPos into = dest.subtract(src).offset(dest);
            BlockState intoBelow = BlockStateInterface.get(ctx, into);
            BlockState intoAbove = BlockStateInterface.get(ctx, into.above());
            // SPRINT IS THE INPUT THE FLOODED CORRIDOR WAS MISSING. Wading under vanilla physics moves at a
            // ~0.02 base factor with 0.8/tick friction; sprint-swimming lifts that friction to ~0.9 and is the
            // only lever a walker has in water. This gate refused it whenever the feet were wet -- so the digger,
            // licensed to stand in its own flooded corridor, could ask for forward every tick and still never
            // exceed creep speed. Measured in run d05f21fd: 0% of ticks at walking speed in the flooded band
            // against 20% in the healthy phase, and the run died of it. A licensed wading cell is the one place
            // sprint in water is wanted; everywhere else the old refusal stands.
            final boolean licensedWade = MovementHelper.currentRouteWadeLicence(princeps).permitsWading(feet);
            if (wasTheBridgeBlockAlwaysThere && (!MovementHelper.isLiquid(ctx, feet) || Princeps.settings().sprintInWater.value || licensedWade) && (!MovementHelper.avoidWalkingInto(intoBelow) || MovementHelper.isWater(intoBelow)) && !MovementHelper.avoidWalkingInto(intoAbove)) {
                state.setInput(Input.SPRINT, true);
            }

            BlockState destDown = BlockStateInterface.get(ctx, dest.below());
            BlockPos against = positionsToBreak[0];
            if (feet.getY() != dest.getY() && ladder && (destDown.getBlock() == Blocks.VINE || destDown.getBlock() == Blocks.LADDER)) {
                against = destDown.getBlock() == Blocks.VINE ? MovementPillar.getAgainst(new CalculationContext(princeps), dest.below()) : dest.relative(destDown.getValue(LadderBlock.FACING).getOpposite());
                if (against == null) {
                    logDirect("Unable to climb vines. Consider disabling allowVines.");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
            }
            if (ladder) {
                MovementHelper.moveTowards(ctx, state, against);
            } else {
                // plain flat walk: pure-pursuit steer along the path (falls back to moveTowards when off/no path)
                MovementHelper.moveAlongPath(princeps, state, against);
            }
            return state;
        } else {
            wasTheBridgeBlockAlwaysThere = false;
            Block standingOn = BlockStateInterface.get(ctx, feet.below()).getBlock();
            if (standingOn.equals(Blocks.SOUL_SAND) || standingOn instanceof SlabBlock) { // see issue #118
                double dist = Math.max(Math.abs(dest.getX() + 0.5 - ctx.player().position().x), Math.abs(dest.getZ() + 0.5 - ctx.player().position().z));
                if (dist < 0.85) { // 0.5 + 0.3 + epsilon
                    MovementHelper.moveTowards(ctx, state, dest);
                    return state.setInput(Input.MOVE_FORWARD, false)
                            .setInput(Input.MOVE_BACK, true);
                }
            }
            double dist1 = Math.max(Math.abs(ctx.player().position().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().position().z - (dest.getZ() + 0.5D)));
            PlaceResult p = MovementHelper.attemptToPlaceABlock(state, princeps, dest.below(), false, !Princeps.settings().assumeSafeWalk.value, "traverse");
            if ((p == PlaceResult.READY_TO_PLACE || dist1 < 0.6) && !Princeps.settings().assumeSafeWalk.value) {
                state.setInput(Input.SNEAK, true);
            }
            switch (p) {
                case READY_TO_PLACE: {
                    if (ctx.player().isCrouching() || Princeps.settings().assumeSafeWalk.value) {
                        if (!armExcavationBridgeClick()) return state;
                        BuildTrace.intendWorldChange("traverse-bridge", dest.below().getX(), dest.below().getY(),
                                dest.below().getZ(), "bridging toward " + dest.getX() + "," + dest.getY() + "," + dest.getZ());
                        state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state;
                }
                case ATTEMPTING: {
                    if (dist1 > 0.83) {
                        // might need to go forward a bit
                        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getYaw();
                        if (Math.abs(state.getTarget().rotation.getYaw() - yaw) < 0.1) {
                            // but only if our attempted place is straight ahead
                            return state.setInput(Input.MOVE_FORWARD, true);
                        }
                    } else if (ctx.playerRotations().isCloseTo(state.getTarget().rotation, 0.75f, 0.55f)) {
                        // well i guess theres something in the way
                        // (tolerance covers humanizedLook's bounded micro-tremor — a 0.01° check would never pass
                        // again with tremor on and this gate would go dead, stalling the bridge against obstructions)
                        return state.setInput(Input.CLICK_LEFT, true);
                    }
                    return state;
                }
                default:
                    break;
            }
            if (feet.equals(dest)) {
                // If we are in the block that we are trying to get to, we are sneaking over air and we need to place a block beneath us against the one we just walked off of
                // Out.log(from + " " + to + " " + faceX + "," + faceY + "," + faceZ + " " + whereAmI);
                double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
                double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
                double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
                // faceX, faceY, faceZ is the middle of the face between from and to
                BlockPos goalLook = src.below(); // this is the block we were just standing on, and the one we want to place against

                Rotation backToFace = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                float pitch = backToFace.getPitch();
                double dist2 = Math.max(Math.abs(ctx.player().position().x - faceX), Math.abs(ctx.player().position().z - faceZ));
                if (dist2 < 0.29) { // see issue #208
                    float yaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
                    state.setTarget(MovementState.MovementTarget.forPlacement(new Rotation(yaw, pitch)));
                } else {
                    state.setTarget(MovementState.MovementTarget.forPlacement(backToFace));
                }
                // The old exact aim could edge backwards and acquire the support face in the same tick. A curved
                // placement aim first faces backwards while fully supported; otherwise its multi-tick turn and edge
                // motion race. Once acquired, latch the motion so tiny geometry changes cannot chatter MOVE_BACK.
                backplaceAimReady = backplaceMotionReady(backplaceAimReady,
                        ctx.playerRotations().isCloseTo(state.getTarget().rotation, 0.75f, 0.55f));
                if (backplaceAimReady) {
                    state.setInput(Input.MOVE_BACK, true);
                }
                HitResult liveHit = ctx.objectMouseOver();
                boolean exactBackplaceFace = liveHit != null && liveHit.getType() == HitResult.Type.BLOCK
                        && ((BlockHitResult) liveHit).getBlockPos().equals(goalLook)
                        && backplaceFaceReachesTarget(goalLook, ((BlockHitResult) liveHit).getDirection(), dest.below());
                if (exactBackplaceFace) {
                    if (!armExcavationBridgeClick()) return state;
                    // The sneak backplace. It never passes attemptToPlaceABlock, so it carries neither the template
                    // guard nor the scaffold licence -- the same shape of leak MovementPillar had, and the reason
                    // the census is declared at the button rather than at the decision.
                    BuildTrace.intendWorldChange("traverse-backplace", dest.below().getX(), dest.below().getY(),
                            dest.below().getZ(), "sneak backplace from " + goalLook.getX() + "," + goalLook.getY()
                                    + "," + goalLook.getZ());
                    return state.setInput(Input.CLICK_RIGHT, true); // wait to right click until we are able to place
                }
                // Out.log("Trying to look at " + goalLook + ", actually looking at" + Princeps.whatAreYouLookingAt());
                // Tremor-tolerant (see above): CLICK_LEFT just breaks the crosshair block, so sub-degree closeness
                // to the intended aim is functionally identical to the old exact check.
                if (ctx.playerRotations().isCloseTo(state.getTarget().rotation, 0.75f, 0.55f)
                        && liveHit != null && liveHit.getType() == HitResult.Type.BLOCK
                        && !((BlockHitResult) liveHit).getBlockPos().equals(goalLook)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            MovementHelper.moveTowardsWithSlightRotation(ctx, state, dest);
            return state;
        }
    }

    private boolean armExcavationBridgeClick() {
        BlockPos floor = dest.below();
        PlacementLicence licence = MovementHelper.currentRouteLicence(princeps);
        if (!licence.isExcavationBridge(floor)) return true; // ordinary builder movement keeps its existing lifecycle
        var helper = ((Princeps) princeps).getInputOverrideHandler().getBlockPlaceHelper();
        HitResult live = ctx.objectMouseOver();
        if (helper.isThrottled() || !(ctx.player().getMainHandItem().getItem() instanceof BlockItem)
                || live == null || live.getType() != HitResult.Type.BLOCK || !(live instanceof BlockHitResult hit)
                || !backplaceFaceReachesTarget(hit.getBlockPos(), hit.getDirection(), floor)) return false;
        helper.expectExcavationIntegrityPlacement(hit.getBlockPos(), hit.getDirection(), floor,
                ctx.player().getInventory().getSelectedSlot(), ctx.player().getMainHandItem().getItem(),
                () -> excavationBridgeStillOwned(licence, MovementHelper.currentRouteLicence(princeps), floor)
                        && !MovementHelper.canWalkOn(ctx, floor));
        return true;
    }

    static boolean excavationBridgeStillOwned(PlacementLicence planned, PlacementLicence current, BlockPos target) {
        // A matching coordinate on a later route is not the original step's permission to replay its click.
        return planned != null && planned == current && planned.isExcavationBridge(target);
    }

    static boolean backplaceMotionReady(boolean alreadyReady, boolean aimAligned) {
        return alreadyReady || aimAligned;
    }

    static boolean backplaceFaceReachesTarget(BlockPos support, Direction face, BlockPos target) {
        return support != null && face != null && target != null && support.relative(face).equals(target);
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we're in the process of breaking blocks before walking forwards
        // or if this isn't a sneak place (the block is already there)
        // then it's safe to cancel this
        return state.getStatus() != MovementStatus.RUNNING || MovementHelper.canWalkOn(ctx, dest.below());
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.below())) {
            Block block = BlockStateInterface.getBlock(ctx, src.below());
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                state.setInput(Input.SNEAK, true);
            }
        }
        return super.prepared(state);
    }
}
