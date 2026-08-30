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

package princeps.pathing.movement;

import princeps.Princeps;
// Imported rather than fully qualified at the call site: the parameter `princeps` in attemptToPlaceABlock shadows the
// package root, so `princeps.process.builder.BuildTrace` inside that method resolves against the VARIABLE and fails
// to compile. The simple name is unaffected.
import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.path.IPathExecutor;
import princeps.process.builder.BuildTrace;
import princeps.api.PrincepsAPI;
import princeps.api.IPrinceps;
import princeps.api.pathing.movement.ActionCosts;
import princeps.api.pathing.movement.MovementStatus;
import princeps.api.utils.*;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.MovementState.MovementTarget;
import princeps.pathing.precompute.Ternary;
import princeps.utils.BlockStateInterface;
import princeps.utils.ToolSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static princeps.api.utils.RotationUtils.DEG_TO_RAD_F;
import static princeps.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;
import static princeps.pathing.precompute.Ternary.*;

/**
 * Static helpers for cost calculation
 *
 * @author leijurv
 */
public interface MovementHelper extends ActionCosts, Helper {

    /**
     * The settings read by the state-only walkability predicates.
     *
     * <p>The live pathfinder uses {@link #LIVE_WALK_SETTINGS}; offline planners pass an immutable snapshot. Keeping the
     * decision in this class gives both callers one definition without forcing pure planning code through
     * {@code Princeps.settings()} and its client-only initialiser.
     */
    interface WalkSettings {

        boolean avoids(Block block);

        boolean allowWalkOnMagmaBlocks();

        boolean allowVines();

        boolean allowWalkOnBottomSlab();

        boolean assumeWalkOnLava();
    }

    WalkSettings LIVE_WALK_SETTINGS = new WalkSettings() {
        @Override
        public boolean avoids(Block block) {
            return Princeps.settings().blocksToAvoid.value.contains(block);
        }

        @Override
        public boolean allowWalkOnMagmaBlocks() {
            return Princeps.settings().allowWalkOnMagmaBlocks.value;
        }

        @Override
        public boolean allowVines() {
            return Princeps.settings().allowVines.value;
        }

        @Override
        public boolean allowWalkOnBottomSlab() {
            return Princeps.settings().allowWalkOnBottomSlab.value;
        }

        @Override
        public boolean assumeWalkOnLava() {
            return Princeps.settings().assumeWalkOnLava.value;
        }
    };

    static boolean avoidBreaking(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return true;
        }
        Block b = state.getBlock();
        return Princeps.settings().blocksToDisallowBreaking.value.contains(b)
                || b == Blocks.ICE // ice becomes water, and water can mess up the path
                || b instanceof InfestedBlock // obvious reasons
                // call context.get directly with x,y,z. no need to make 5 new BlockPos for no reason
                || avoidAdjacentBreaking(bsi, x, y + 1, z, true)
                || avoidAdjacentBreaking(bsi, x + 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x - 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x, y, z + 1, false)
                || avoidAdjacentBreaking(bsi, x, y, z - 1, false);
    }

    static boolean avoidAdjacentBreaking(BlockStateInterface bsi, int x, int y, int z, boolean directlyAbove) {
        // returns true if you should avoid breaking a block that's adjacent to this one (e.g. lava that will start flowing if you give it a path)
        // this is only called for north, south, east, west, and up. this is NOT called for down.
        // we assume that it's ALWAYS okay to break the block thats ABOVE liquid
        BlockState state = bsi.get0(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove // it is fine to mine a block that has a falling block directly above, this (the cost of breaking the stacked fallings) is included in cost calculations
                // therefore if directlyAbove is true, we will actually ignore if this is falling
                && block instanceof FallingBlock // obviously, this check is only valid for falling blocks
                && Princeps.settings().avoidUpdatingFallingBlocks.value // and if the setting is enabled
                && FallingBlock.isFree(bsi.get0(x, y - 1, z))) { // and if it would fall (i.e. it's unsupported)
            return true; // dont break a block that is adjacent to unsupported gravel because it can cause really weird stuff
        }
        // only pure liquids for now
        // waterlogged blocks can have closed bottom sides and such
        if (block instanceof LiquidBlock) {
            if (directlyAbove || Princeps.settings().strictLiquidCheck.value) {
                return true;
            }
            int level = state.getValue(LiquidBlock.LEVEL);
            if (level == 0) {
                return true; // source blocks like to flow horizontally
            }
            // everything else will prefer flowing down
            return !(bsi.get0(x, y - 1, z).getBlock() instanceof LiquidBlock); // assume everything is in a static state
        }
        return !state.getFluidState().isEmpty();
    }

    public static boolean canWalkThrough(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkThrough(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkThrough(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        if (blocksPathingBarrier(context.mayUsePathingBarriers(), state)) {
            return false;
        }
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, state);
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return canWalkThrough(context, x, y, z, context.get(x, y, z));
    }

    /** Doors and gates are special because generic pathing calls them passable on the promise that a movement will
     *  operate them. Only {@code MovementTraverse} actually owns that actuator. */
    public static boolean isPathingBarrier(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof FenceGateBlock;
    }

    /** Pure half of the context policy, exposed so the no-mutation contract can be pinned without a live client. */
    static boolean blocksPathingBarrier(boolean mayUsePathingBarriers, BlockState state) {
        return !mayUsePathingBarriers && isPathingBarrier(state);
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Ternary canWalkThrough = canWalkThroughBlockState(state);
        if (canWalkThrough == YES) {
            return true;
        }
        if (canWalkThrough == NO) {
            return false;
        }
        return canWalkThroughPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkThroughBlockState(BlockState state) {
        return canWalkThroughBlockState(state, LIVE_WALK_SETTINGS);
    }

    static Ternary canWalkThroughBlockState(BlockState state, WalkSettings settings) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.COBWEB || block == Blocks.END_PORTAL || block == Blocks.COCOA || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN || block instanceof ShulkerBoxBlock || block instanceof SlabBlock || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock) {
            return NO;
        }
        if (block == Blocks.BIG_DRIPLEAF) {
            return NO;
        }
        if (block == Blocks.POWDER_SNOW) {
            return NO;
        }
        if (settings.avoids(block)) {
            return NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // TODO this assumes that all doors in all mods are openable
            if (block == Blocks.IRON_DOOR) {
                return NO;
            }
            return YES;
        }
        if (block instanceof CarpetBlock) {
            return MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // snow layers cached as the top layer of a packed chunk have no metadata, we can't make a decision based on their depth here
            // it would otherwise make long distance pathing through snowy biomes impossible
            return MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getType().getAmount(fluidState) != 8) {
                return NO;
            } else {
                return MAYBE;
            }
        }
        if (block instanceof CauldronBlock) {
            return NO;
        }
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    static boolean canWalkThroughPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            return canWalkOn(bsi, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            // the check in BlockSnow.isPassable is layers < 5
            // while actually, we want < 3 because 3 or greater makes it impassable in a 2 high ceiling
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            // ok, it's low enough we could walk through it, but is it supported?
            return canWalkOn(bsi, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(x, y, z, state, bsi)) {
                return false;
            }
            // Everything after this point has to be a special case as it relies on the water not being flowing, which means a special case is needed.
            if (Princeps.settings().assumeWalkOnWater.value) {
                return false;
            }

            BlockState up = bsi.get0(x, y + 1, z);
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof LilyPadBlock) {
                return false;
            }
            return fluidState.getType() instanceof WaterFluid;
        }

        return state.isPathfindable(PathComputationType.LAND);
    }

    static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return YES;
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return NO;
        }
        // door, fence gate, liquid, trapdoor have been accounted for, nothing else uses the world or pos parameters
        // at least in 1.12.2 vanilla, that is.....
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    /**
     * canWalkThrough but also won't impede movement at all. so not including doors or fence gates (we'd have to right click),
     * not including water, and not including ladders or vines or cobwebs (they slow us down)
     */
    static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context, x, y, z, context.get(x, y, z));
    }

    static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.fullyPassable(context.bsi, x, y, z, state);
    }

    static boolean fullyPassable(IPlayerContext ctx, BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        Ternary fullyPassable = fullyPassableBlockState(state);
        if (fullyPassable == YES) {
            return true;
        }
        if (fullyPassable == NO) {
            return false;
        }
        return state.isPathfindable(PathComputationType.LAND);
    }

    /**
     * params retained for backwards compatibility
     */
    static boolean fullyPassablePosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        return state.isPathfindable(PathComputationType.LAND);
    }

    static boolean isReplaceable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
        // the only case to deal with is snow
        /*
         *  public boolean isReplaceable(IBlockAccess worldIn, BlockPos pos)
         *     {
         *         return ((Integer)worldIn.getBlockState(pos).getValue(LAYERS)).intValue() == 1;
         *     }
         */
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            // early return for common cases hehe
            return true;
        }
        if (block instanceof SnowLayerBlock) {
            // as before, default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.canBeReplaced();
    }

    @Deprecated
    static boolean isReplacable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        return isReplaceable(x, y, z, state, bsi);
    }

    static boolean isDoorPassable(IPlayerContext ctx, BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos)) {
            return false;
        }

        BlockState state = BlockStateInterface.get(ctx, doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return true;
        }

        return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
    }

    static boolean isGatePassable(IPlayerContext ctx, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) {
            return false;
        }

        BlockState state = BlockStateInterface.get(ctx, gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }

        return state.getValue(FenceGateBlock.OPEN);
    }

    static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState, BlockPos playerPos, BooleanProperty propertyOpen) {
        if (playerPos.equals(blockPos)) {
            return false;
        }

        Direction.Axis facing = blockState.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = blockState.getValue(propertyOpen);

        Direction.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
            playerFacing = Direction.Axis.Z;
        } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
            playerFacing = Direction.Axis.X;
        } else {
            return true;
        }

        return (facing == playerFacing) == open;
    }

    static boolean avoidWalkingInto(BlockState state) {
        Block block = state.getBlock();
        return !state.getFluidState().isEmpty()
                || (block == Blocks.MAGMA_BLOCK && !Princeps.settings().allowWalkOnMagmaBlocks.value)
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof BaseFireBlock
                || block == Blocks.END_PORTAL
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    /**
     * Can I walk on this block without anything weird happening like me falling
     * through? Includes water because we know that we automatically jump on
     * water
     * <p>
     * If changing something in this function remember to also change it in precomputed data
     *
     * @param bsi   Block state provider
     * @param x     The block's x position
     * @param y     The block's y position
     * @param z     The block's z position
     * @param state The state of the block at the specified location
     * @return Whether or not the specified block can be walked on
     */
    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Ternary canWalkOn = canWalkOnBlockState(state);
        if (canWalkOn == YES) {
            return true;
        }
        if (canWalkOn == NO) {
            return false;
        }
        return canWalkOnPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkOnBlockState(BlockState state) {
        return canWalkOnBlockState(state, LIVE_WALK_SETTINGS);
    }

    static Ternary canWalkOnBlockState(BlockState state, WalkSettings settings) {
        Block block = state.getBlock();
        if (isBlockNormalCube(state) && (block != Blocks.MAGMA_BLOCK || settings.allowWalkOnMagmaBlocks()) && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return YES;
        }
        if (block instanceof AzaleaBlock) {
            return YES;
        }
        if (block == Blocks.LADDER || (block == Blocks.VINE && settings.allowVines())) { // TODO reconsider this
            return YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND) {
            return YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return YES;
        }
        if (block instanceof StairBlock) {
            return YES;
        }
        if (isWater(state)) {
            return MAYBE;
        }
        if (MovementHelper.isLava(state) && settings.assumeWalkOnLava()) {
            return MAYBE;
        }
        if (block instanceof SlabBlock) {
            if (!settings.allowWalkOnBottomSlab()) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return YES;
                }
                return NO;
            }
            return YES;
        }
        return NO;
    }

    static boolean canWalkOnPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isWater(state)) {
            // since this is called literally millions of times per second, the benefit of not allocating millions of useless "pos.up()"
            // BlockPos s that we'd just garbage collect immediately is actually noticeable. I don't even think its a decrease in readability
            BlockState upState = bsi.get0(x, y + 1, z);
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            if (MovementHelper.isFlowing(x, y, z, state, bsi) || upState.getFluidState().getType() == Fluids.FLOWING_WATER) {
                // the only scenario in which we can walk on flowing water is if it's under still water with jesus off
                return isWater(upState) && !Princeps.settings().assumeWalkOnWater.value;
            }
            // if assumeWalkOnWater is on, we can only walk on water if there isn't water above it
            // if assumeWalkOnWater is off, we can only walk on water if there is water above it
            return isWater(upState) ^ Princeps.settings().assumeWalkOnWater.value;
        }

        if (MovementHelper.isLava(state) && !MovementHelper.isFlowing(x, y, z, state, bsi) && Princeps.settings().assumeWalkOnLava.value) { // if we get here it means that assumeWalkOnLava must be true, so put it last
            return true;
        }

        return false; // If we don't recognise it then we want to just return false to be safe.
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.canWalkOn(context.bsi, x, y, z, state);
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z) {
        return canWalkOn(context, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos, BlockState state) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z, state);
    }

    static boolean canWalkOn(IPlayerContext ctx, BlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkOn(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canUseFrostWalker(CalculationContext context, BlockState state) {
        return context.frostWalker != 0
                && state == FrostedIceBlock.meltsInto()
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    static boolean canUseFrostWalker(IPlayerContext ctx, BlockPos pos) {
        boolean hasFrostWalker = false;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = ctx
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    hasFrostWalker = true;
                    break OUTER;
                }
            }
        }
        BlockState state = BlockStateInterface.get(ctx, pos);
        return hasFrostWalker
                && state == FrostedIceBlock.meltsInto()
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * If movements make us stand/walk on this block, will it have a top to walk on?
     */
    static boolean mustBeSolidToWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.LADDER || block == Blocks.VINE) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            // used for frostwalker so only includes blocks where we are still on ground when leaving them to any side
            if (block instanceof SlabBlock) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairBlock) {
                if (state.getValue(StairBlock.HALF) == Half.TOP) {
                    return true;
                }
                StairsShape shape = state.getValue(StairBlock.SHAPE);
                if (shape == StairsShape.INNER_LEFT || shape == StairsShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (context.assumeWalkOnWater) {
                return false;
            }
            Block blockAbove = context.getBlock(x, y + 1, z);
            if (blockAbove instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z) {
        return canPlaceAgainst(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, BlockPos pos) {
        return canPlaceAgainst(bsi, pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean canPlaceAgainst(IPlayerContext ctx, BlockPos pos) {
        return canPlaceAgainst(new BlockStateInterface(ctx), pos);
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return false;
        }
        // can we look at the center of a side face of this block and likely be able to place?
        // (thats how this check is used)
        // therefore dont include weird things that we technically could place against (like carpet) but practically can't
        return isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock;
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
        Block block = state.getBlock();
        // A barrier forbidden by the owning context is not a block to mine instead. In particular, Builder V3 uses
        // this to say "do not mutate this door/gate"; translating that into a break would violate the same rule more
        // severely than opening it.
        if (blocksPathingBarrier(context.mayUsePathingBarriers(), state)) {
            return COST_INF;
        }
        if (!canWalkThrough(context, x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                // A LIQUID IS NOT A MINING JOB, so the only two honest answers here are "walk into it" and "there is
                // no route through here". Which one applies is not a property of the block -- flowing water is
                // ankle-deep and harmless in a corridor with rock under it, and a sweep into a ravine in the open
                // world -- so it is the route that has to say, and it says it once, before the search starts. See
                // WadeLicence: unlicensed is the default and keeps today's refusal exactly as it was.
                //
                // A WATERLOGGED STAIR IS NOT A PUDDLE. `isWater` answers about the FLUID in the cell, and a
                // waterlogged solid carries one while still standing there as a wall -- so asking only that would
                // price a real obstruction as free walking, and the route would be planned straight through a block
                // that never moves. The block itself must BE the liquid.
                return isWater(state) && state.getBlock() instanceof LiquidBlock
                        && context.wadeLicence().permitsWading(x, y, z) ? 0 : COST_INF;
            }
            double mult = context.breakCostMultiplierAt(x, y, z, state);
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (avoidBreaking(context.bsi, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = context.toolSet.getStrVsBlock(state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            double result = 1 / strVsBlock;
            result += context.breakBlockAdditionalCost;
            result *= mult;
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += getMiningDurationTicks(context, x, y + 1, z, above, true);
                }
            }
            return result;
        }
        return 0; // we won't actually mine it, so don't check fallings above
    }

    static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /**
     * AutoTool for a specific block
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     */
    static void switchToBestToolFor(IPlayerContext ctx, BlockState b) {
        switchToBestToolFor(ctx, b, new ToolSet(ctx.player()), PrincepsAPI.getSettings().preferSilkTouch.value);
    }

    /**
     * AutoTool for a specific block with precomputed ToolSet data
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     * @param ts  previously calculated ToolSet
     */
    static void switchToBestToolFor(IPlayerContext ctx, BlockState b, ToolSet ts, boolean preferSilkTouch) {
        if (Princeps.settings().autoTool.value && !Princeps.settings().assumeExternalAutoTool.value) {
            // An auto-survival eat/repair owns the hands: a hotbar slot switch cancels the consume outright
            // (the "mines and eats at once — the gapple never finishes" glitch: movements re-select the pick
            // for the NEXT block every tick, yanking the food out of the hand mid-bite). Breaking is already
            // paused for the consume's duration, so deferring the tool switch costs nothing.
            if (PrincepsAPI.getProvider().getPrimaryPrinceps() instanceof Princeps princeps
                    && princeps.getSurvivalBehavior() != null
                    && princeps.getSurvivalBehavior().ownsInventory()) {
                return;
            }
            ctx.player().getInventory().setSelectedSlot(ts.getBestSlot(b.getBlock(), preferSilkTouch));
        }
    }

    static void moveTowards(IPlayerContext ctx, MovementState state, BlockPos pos) {
        state.setTarget(new MovementTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(pos),
                        ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                false
        )).setInput(Input.MOVE_FORWARD, true);
    }

    /** single-slot input hysteresis + cached smooth line for {@link #moveAlongPath} (one local player per client) */
    final class Steering {
        private static boolean strafing;
        private static int lastOctant;      // the strafe octant (deg offset from yaw) currently held
        private static boolean hasHeld;     // whether lastOctant is valid this run of strafing
        // Arc-rounded line of the CURRENT flat run (FlowNav's FlowLine), built once per run: rebuilding
        // per tick from a shifting window made the carrots jump at every node advance (steering jank).
        private static long lineSig = Long.MIN_VALUE;
        private static java.util.List<princeps.flownav.math.Vec3> line = java.util.Collections.emptyList();
        private static int lastIdx;         // monotonic-ish projection progress along `line`
    }

    /**
     * Pure-pursuit cruising steer ("the elytra blue line, on the ground"): instead of aiming the body at the
     * current node's center and holding W — which snaps the walk direction at every node boundary — the GAZE
     * chases a far carrot ~3.2 blocks ahead on the path polyline (the eyes lead into corners early, like a human
     * looking where they are going) while the FEET follow a near carrot 0.7 blocks ahead via the input combo
     * (W/A/D strafe) closest to the local track direction. The near lookahead is small enough that the walked
     * track still enters every node's block column (validated in simulation: 100% node coverage at 0.7; feet
     * therefore still trigger each movement's feet==dest SUCCESS), so the executor's bookkeeping is untouched —
     * the corner is cut by at most ~0.5 blocks inside the corner node's own block. Strafe engages above 25° of
     * look-vs-track error and releases below 12° (hysteresis kills A/D chatter); within the deadband it is plain
     * W and the mouse does the steering, exactly like a human. Falls back to classic moveTowards whenever there
     * is no active path, the flat window is degenerate, or the feature is off.
     */
    static void moveAlongPath(IPrinceps bot, MovementState state, BlockPos classicAim) {
        IPlayerContext ctx = bot.getPlayerContext();
        if (!Princeps.settings().humanizedLook.value || !Princeps.settings().humanizedSteering.value) {
            moveTowards(ctx, state, classicAim);
            return;
        }
        // Collision safety: the pure-pursuit line cuts a corner by up to ~0.5 blocks INSIDE the corner node, which
        // in a tight/just-dug 1-wide corridor can clip the still-solid diagonal wall block. The instant we actually
        // touch a wall, revert to an exact LOCAL aim that pulls us straight back onto the path and clears the wall.
        // Self-correcting: next unobstructed tick the smooth line resumes. NOTE: the aim must be LOCAL — classicAim
        // is the movement's dest, which for a SmoothTraverse chord can be 20+ blocks away; beelining at a distant
        // dest from an off-path position leaves the collision-verified corridor entirely. The near-carrot fallback
        // below (inside the windowed section) handles the on-path case; this early exit only remains for the
        // no-path/degenerate cases where classicAim is the adjacent lattice node anyway.
        final boolean collided = ctx.player().horizontalCollision;
        princeps.api.pathing.path.IPathExecutor exec = bot.getPathingBehavior().getCurrent();
        if (exec == null || exec.getPath() == null) {
            moveTowards(ctx, state, classicAim);
            return;
        }
        List<BetterBlockPos> nodes = exec.getPath().positions();
        int pos = exec.getPosition();
        if (pos < 0 || pos >= nodes.size()) {
            moveTowards(ctx, state, classicAim);
            return;
        }
        // FULL flat run around the executor position, bounded at any y-change so the smoothing never
        // reaches across an ascend/descend/parkour edge (those keep their exact aims). The run is the
        // STABLE span the cached arc line is built over — a shifting window would rebuild a slightly
        // different curve at every node advance, which is the historical source of steering jank.
        final int y = nodes.get(pos).y;
        int start = pos;
        while (start > 0 && nodes.get(start - 1).y == y) {
            start--;
        }
        int end = pos;
        while (end + 1 < nodes.size() && nodes.get(end + 1).y == y) {
            end++;
        }
        if (end - start < 1) {
            moveTowards(ctx, state, classicAim);
            return;
        }
        final Vec3 player = ctx.player().position();
        // The arc-rounded line of this flat run (FlowNav's FlowLine — the SAME curve the path renderer
        // draws), built ONCE per run and cached: the feet drive exactly the curve the blue line shows.
        // Dense points (<= 0.25 blocks apart) make the nearest-point projection accurate.
        final long lineSig = ((((long) (end - start) * 131 + nodes.get(start).hashCode()) * 131
                + nodes.get(end).hashCode()) * 131) + y;
        if (lineSig != Steering.lineSig) {
            final java.util.List<princeps.flownav.math.Vec3> centers = new java.util.ArrayList<>(end - start + 1);
            for (int i = start; i <= end; i++) {
                centers.add(new princeps.flownav.math.Vec3(nodes.get(i).x + 0.5, y, nodes.get(i).z + 0.5));
            }
            try {
                Steering.line = princeps.flownav.traj.FlowLine.smoothPoints(centers, 1.0);
            } catch (RuntimeException e) {
                Steering.line = centers; // degenerate geometry: fall back to the raw centers
            }
            Steering.lineSig = lineSig;
            Steering.lastIdx = 0;
        }
        final java.util.List<princeps.flownav.math.Vec3> line = Steering.line;
        if (line.size() < 2) {
            moveTowards(ctx, state, classicAim);
            return;
        }
        // Nearest line point, searched in a window around last tick's index: monotonic-ish progress, so
        // the projection never jumps between limbs of a self-approaching curve.
        final int fromIdx = Math.max(0, Steering.lastIdx - 8);
        final int toIdx = Math.min(line.size() - 1, Steering.lastIdx + 40);
        int idx = fromIdx;
        double bestD = Double.MAX_VALUE;
        for (int i = fromIdx; i <= toIdx; i++) {
            final princeps.flownav.math.Vec3 p = line.get(i);
            final double dx = p.x() - player.x, dz = p.z() - player.z;
            final double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                idx = i;
            }
        }
        Steering.lastIdx = idx;
        // Lookahead distances are settings so the feel can be tuned live (sim-validated window: near <= ~0.7 keeps
        // 100% node coverage; larger near cuts corners harder and starts skipping node columns).
        final double gazeAhead = Math.max(1.0, Princeps.settings().humanizedSteeringGazeBlocks.value);
        final double trackAhead = Mth.clamp(Princeps.settings().humanizedSteeringTrackBlocks.value.doubleValue(), 0.3, 0.7);
        final double[] far = alongLine(line, idx, gazeAhead);
        final double[] near = alongLine(line, idx, trackAhead);
        if (collided) {
            // LOCAL collision recovery: exact-aim at the near carrot's cell (<= trackAhead blocks ahead ON the
            // path), which pulls the body straight back into the verified corridor — never at a distant chord dest.
            Steering.strafing = false;
            Steering.hasHeld = false;
            moveTowards(ctx, state, new BetterBlockPos((int) Math.floor(near[0]), y, (int) Math.floor(near[1])));
            return;
        }
        // signed cross-track distance to the local smooth-line segment (for the A/D drift correction below)
        final int segEnd = Math.min(idx + 1, line.size() - 1);
        final int segBegin = Math.max(0, segEnd - 1);
        final double pax = line.get(segBegin).x(), paz = line.get(segBegin).z();
        final double pbx = line.get(segEnd).x(), pbz = line.get(segEnd).z();
        final double segLen = Math.hypot(pbx - pax, pbz - paz);
        final double crossTrack = segLen < 1e-6 ? 0.0
                : ((player.x - pax) * (pbz - paz) - (player.z - paz) * (pbx - pax)) / segLen;
        // gaze: far carrot, current pitch (nudgeToLevel + the humanized shaping own the rest)
        state.setTarget(new MovementTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        new Vec3(far[0], ctx.playerHead().y, far[1]),
                        ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                false
        ));
        // track: near carrot via the octant inputs, with engage/release hysteresis around plain W. Engagement is
        // driven by BOTH the bearing error AND the lateral cross-track drift: on a long any-angle chord a small,
        // persistent heading offset INTEGRATES into real lateral drift (nodes are no longer 1 block apart to reset
        // it), so the feet must correct with A/D before the drift grows — the user-requested "use A and D actively
        // so the body follows the plan despite the smooth slow gaze". Direction still comes from the near carrot
        // (back-to-the-line + forward), so this only engages the existing octant mechanism earlier, never replaces it.
        float idealYaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                new Vec3(near[0], ctx.playerHead().y, near[1]), ctx.playerRotations()).getYaw();
        float rel = Math.abs(Mth.degreesDifference(ctx.playerRotations().getYaw(), idealYaw));
        final double xtEngage = Math.max(0.05, Princeps.settings().humanizedSteeringXtEngage.value);
        final double xtRelease = Mth.clamp(Princeps.settings().humanizedSteeringXtRelease.value, 0.0, xtEngage);
        final boolean bearingWants = Steering.strafing ? rel >= 12f : rel >= 25f;
        final boolean xtWants = Steering.strafing ? Math.abs(crossTrack) > xtRelease
                : Math.abs(crossTrack) >= xtEngage;
        if (!bearingWants && !xtWants) {
            Steering.strafing = false;
            Steering.hasHeld = false;
            state.setInput(Input.MOVE_FORWARD, true);
        } else {
            Steering.strafing = true;
            final float relSigned = Mth.degreesDifference(ctx.playerRotations().getYaw(), idealYaw);
            // ACTUATION fix (review-confirmed): at the xt engage point the near-carrot bearing is only ~18-23 deg,
            // which Math.round(rel/45) snaps to octant 0 = plain W = no lateral input at all — the drift correction
            // was a no-op across its whole design envelope. When the LATERAL trigger demands the strafe (bearing
            // still inside the deadzone), actuate laterally: force the forward diagonal TOWARD the line from the
            // SIGN of the cross-track. crossTrack > 0 = body left of the track direction -> steer right (W+D, +45).
            if (Math.abs(relSigned) < 22.5f && Math.abs(crossTrack) > xtRelease) {
                strafeToward(state, crossTrack > 0 ? 45f : -45f);
            } else {
                strafeToward(state, relSigned);
            }
        }

        // Curvature-aware speed: release SPRINT approaching a sharp path bend (walk speed) so the tight per-tick
        // head cap can trace the corner as a smooth CURVE instead of the faster body carrying wide. The bend is
        // measured over the next ~2.4 blocks of the path, so we ease off just BEFORE the corner and accelerate out
        // of it. Bench: corner velocity-jerk ~-70% with node coverage unchanged, ~+6% time; straights keep sprinting.
        if (Princeps.settings().humanizedSteeringCurveSlow.value) {
            final double[] b0 = alongLine(line, idx, 0.2);
            final double[] b1 = alongLine(line, idx, 1.4);
            final double[] b2 = alongLine(line, idx, 2.6);
            final float h1 = (float) Math.toDegrees(Math.atan2(-(b1[0] - b0[0]), b1[1] - b0[1]));
            final float h2 = (float) Math.toDegrees(Math.atan2(-(b2[0] - b1[0]), b2[1] - b1[1]));
            if (Math.abs(Mth.degreesDifference(h1, h2))
                    >= Princeps.settings().humanizedSteeringSlowBend.value.floatValue()) {
                state.setInput(Input.SPRINT, false);
            }
        }
    }

    /**
     * Set the W/A/D(/S) inputs to steer the FEET toward {@code relYaw} (signed degrees off the current yaw), snapped
     * to the nearest 45-degree octant — with BOUNDARY HYSTERESIS: the currently-held octant is kept unless another
     * is clearly better (by humanizedSteeringHysteresis degrees). Without it the strafe input chatters — flipping
     * A<->D every couple of ticks as the bearing hovers on a 45-degree boundary — which is both robotic in the input
     * packet stream and a needless smoothness cost. Bench-measured: ~20-30% fewer octant toggles on winding paths
     * with node coverage unchanged. Positive relYaw = to the right (D), negative = left (A) (see MovementOption).
     */
    private static void strafeToward(MovementState state, float relYaw) {
        int octant = Math.round(relYaw / 45f) * 45;
        final float margin = Princeps.settings().humanizedSteeringHysteresis.value.floatValue();
        if (margin > 0 && Steering.hasHeld && octant != Steering.lastOctant) {
            float dNew = Math.abs(Mth.degreesDifference(octant, relYaw));
            float dHeld = Math.abs(Mth.degreesDifference(Steering.lastOctant, relYaw));
            if (dNew > dHeld - margin) {
                octant = Steering.lastOctant;   // the new octant isn't clearly better — keep the held one
            }
        }
        Steering.lastOctant = octant;
        Steering.hasHeld = true;
        final int a = Math.floorMod(octant, 360);
        if (a == 0) {
            state.setInput(Input.MOVE_FORWARD, true);
        } else if (a == 45) {
            state.setInput(Input.MOVE_FORWARD, true).setInput(Input.MOVE_RIGHT, true);
        } else if (a == 315) {
            state.setInput(Input.MOVE_FORWARD, true).setInput(Input.MOVE_LEFT, true);
        } else if (a == 90) {
            state.setInput(Input.MOVE_RIGHT, true);
        } else if (a == 270) {
            state.setInput(Input.MOVE_LEFT, true);
        } else if (a == 135) {
            state.setInput(Input.MOVE_BACK, true).setInput(Input.MOVE_RIGHT, true);
        } else if (a == 225) {
            state.setInput(Input.MOVE_BACK, true).setInput(Input.MOVE_LEFT, true);
        } else { // 180
            state.setInput(Input.MOVE_BACK, true);
        }
    }

    /** advance {@code ahead} blocks of horizontal arc length along the cached smooth line from point
     *  {@code idx}, clamped at the line end; returns {x, z} */
    private static double[] alongLine(java.util.List<princeps.flownav.math.Vec3> line, int idx, double ahead) {
        double cx = line.get(idx).x(), cz = line.get(idx).z();
        int j = idx;
        double rem = ahead;
        while (rem > 1e-9 && j + 1 < line.size()) {
            final double bx = line.get(j + 1).x(), bz = line.get(j + 1).z();
            final double seg = Math.hypot(bx - cx, bz - cz);
            if (seg >= rem) {
                final double f = seg == 0 ? 0 : rem / seg;
                return new double[]{cx + (bx - cx) * f, cz + (bz - cz) * f};
            }
            rem -= seg;
            cx = bx;
            cz = bz;
            j++;
        }
        return new double[]{cx, cz};
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, float idealYaw) {
        MovementOption.getOptions(
                Mth.sin(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Mth.cos(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Princeps.settings().allowSprint.value
        ).min(Comparator.comparing(option -> option.distanceToSq(
                Mth.sin(idealYaw * DEG_TO_RAD_F),
                Mth.cos(idealYaw * DEG_TO_RAD_F)
        ))).ifPresent(selection -> selection.setInputs(state));
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    static void moveTowardsWithSlightRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
        float distance = Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), idealYaw) % 45f;
        float newYaw = distance > 0f ?
                distance > 22.5f ? distance - 45f : distance :
                distance < -22.5f ? distance + 45f : distance;
        state.setTarget(new MovementTarget(new Rotation(
                ctx.playerRotations().getYaw() - newYaw,
                ctx.playerRotations().getPitch()
        ), true));
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    /**
     * Returns whether or not the specified block is
     * water, regardless of whether or not it is flowing.
     *
     * @param state The block state
     * @return Whether or not the block is water
     */
    static boolean isWater(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    /**
     * Returns whether or not the block at the specified pos is
     * water, regardless of whether or not it is flowing.
     *
     * @param ctx The player context
     * @param bp  The block pos
     * @return Whether or not the block is water
     */
    static boolean isWater(IPlayerContext ctx, BlockPos bp) {
        return isWater(BlockStateInterface.get(ctx, bp));
    }

    static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    /**
     * Returns whether or not the specified pos has a liquid
     *
     * @param ctx The player context
     * @param p   The pos
     * @return Whether or not the block is a liquid
     */
    static boolean isLiquid(IPlayerContext ctx, BlockPos p) {
        return isLiquid(BlockStateInterface.get(ctx, p));
    }

    static boolean isLiquid(BlockState blockState) {
        return !blockState.getFluidState().isEmpty();
    }

    static boolean possiblyFlowing(BlockState state) {
        FluidState fluidState = state.getFluidState();
        return fluidState.getType() instanceof FlowingFluid
                && fluidState.getType().getAmount(fluidState) != 8;
    }

    static boolean isFlowing(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluidState.getType().getAmount(fluidState) != 8) {
            return true;
        }
        return possiblyFlowing(bsi.get0(x + 1, y, z))
                || possiblyFlowing(bsi.get0(x - 1, y, z))
                || possiblyFlowing(bsi.get0(x, y, z + 1))
                || possiblyFlowing(bsi.get0(x, y, z - 1));
    }

    static boolean isBlockNormalCube(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooStalkBlock
                || block instanceof MovingPistonBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            // if we can't get the collision shape, assume it's bad and add to blocksToAvoid
        }
        return false;
    }

    /**
     * @param caller which movement is asking, verbatim into the trace. A placement line that cannot say WHO wanted the
     *               block cannot distinguish "the route stepped up here" from "the route bridged a gap here", and those
     *               are different bugs with different fixes -- see BuildTrace.context for the run that needed it.
     */
    /**
     * The Setz-Erlaubnis of the route currently being driven, or unrestricted when none is.
     *
     * <p>Public because the movements that place a block WITHOUT coming through {@link #attemptToPlaceABlock} have
     * to ask the same question — {@code MovementPillar} is the known one, and it cost three untraceable cobblestone
     * blocks in run 762769a0 to find it. There must be exactly one answer to "may this walk place here", and this
     * is where it lives.
     */
    public static PlacementLicence currentRouteLicence(IPrinceps princeps) {
        IPathExecutor current = princeps.getPathingBehavior().getCurrent();
        return current == null ? PlacementLicence.UNRESTRICTED : current.placementLicence();
    }

    /**
     * The Wat-Erlaubnis of the route currently being driven, or none when there is no route.
     *
     * <p>Same shape and same reason as {@link #currentRouteLicence}: the answer must come from the route that is
     * being driven, because it is the route's own plan that priced the step through the water.
     */
    public static WadeLicence currentRouteWadeLicence(IPrinceps princeps) {
        IPathExecutor current = princeps.getPathingBehavior().getCurrent();
        return current == null ? WadeLicence.NONE : current.wadeLicence();
    }

    static PlaceResult attemptToPlaceABlock(MovementState state, IPrinceps princeps, BlockPos placeAt, boolean preferDown, boolean wouldSneak, String caller) {
        IPlayerContext ctx = princeps.getPlayerContext();
        // NEVER a HELPER block into a cell the template names -- but filling that cell with the block it is actually
        // waiting for, while walking past it, stays allowed and is free progress. The single choke point through
        // which every movement places something to stand on, so it is the only place the rule cannot be routed around.
        //
        // The distinction is the whole fix. A first version refused every placement into a planned cell and froze the
        // bot for 8800 ticks at 104,-58,89: the route wanted to step north onto 104,-59,88, the bot was holding the
        // black_stained_glass that cell wants, and the refusal fired every tick while the router handed back the same
        // two-node path. "Routing around instead" was a promise the guard could not keep, because refusing a movement
        // does not make the router avoid the node.
        //
        // The build already prices such a cell at COST_INF, and that was not enough: a cost governs which path the
        // SEARCH picks, not what a running movement does, and it is not consulted at all by a route computed without
        // the builder's calculation context. Measured in run 20260802-221511 with the infinite cost live -- a
        // cobblestone in 124,-59,114 (wants sticky_piston), then 710 ticks of the builder trying to mine it back out
        // while the route kept re-placing it. Both of the cell's click faces, the target at 125,-59,114 and the wire
        // at 123,-59,114, had been standing since tick 11717.
        boolean templateWantsABlockHere = princeps.getBuilderProcess().templateNamesABlockAt(placeAt);
        boolean wouldPlaceTheTemplateBlock = ((Princeps) princeps).getInventoryBehavior()
                .wouldPlaceTemplateBlockAt(placeAt.getX(), placeAt.getY(), placeAt.getZ());
        if (templateWantsABlockHere && !wouldPlaceTheTemplateBlock) {
            BuildTrace.cell(BuildTrace.tickNow(), "SCAFFOLD-REFUSED", placeAt.getX(), placeAt.getY(), placeAt.getZ(),
                    "only a helper block is available and the template names this cell by=" + caller
                            + " " + BuildTrace.intentNow());
            state.setStatus(MovementStatus.UNREACHABLE);
            return PlaceResult.NO_OPTION;
        }
        if (wouldPlaceTheTemplateBlock && !princeps.getBuilderProcess().templatePlacementIsLicensedAt(placeAt)) {
            BuildTrace.cell(BuildTrace.tickNow(), "ROW-FRONTIER-REFUSED", placeAt.getX(), placeAt.getY(), placeAt.getZ(),
                    "template pixel belongs to a later Map-Art slice by=" + caller + " " + BuildTrace.intentNow());
            state.setStatus(MovementStatus.UNREACHABLE);
            return PlaceResult.NO_OPTION;
        }
        // Past the refusal there are still TWO different things this method places, and the first version of the
        // trace event ran them together. Either the template has no opinion about the cell (or wants air) and a
        // throwaway goes in -- that is scaffolding, the thing the owner is counting -- or the template names the
        // cell and the block going in is the one it is waiting for, which is free progress and not scaffolding at
        // all. Measured in 20260802-232954: all six y=-59 "scaffold" coordinates were the second case, each ending
        // in DONE got=black_stained_glass, five of them without the builder ever touching the cell. A count that
        // conflates the two cannot answer "is layer -59 built without helper blocks", which is the acceptance
        // criterion it exists to serve.
        String kind = wouldPlaceTheTemplateBlock ? "TEMPLATE-PLACE" : "SCAFFOLD";
        // NO HELPER BLOCK FOR A WALK THAT WAS NEVER PROVEN WORTH TAKING. The owner's rule, and the one leftover
        // cobblestone of the 96/96 run 50f40a49 is the whole case: see IBuilderProcess.scaffoldIsLicensedAt.
        //
        // Placed AFTER the template refusal above (that one keeps priority and its own reason string) and after `kind`
        // is computed, so TEMPLATE-PLACE -- putting in the block the cell is actually waiting for -- is exempt; and
        // BEFORE the reachable/aim work below, so a refused placement does not even turn the bot's head.
        //
        // Backfill is exempt at the call site rather than in the predicate: BackfillProcess is CLEANUP, re-filling air
        // it created itself, and it passes a throwaway MovementState and continues on NO_OPTION -- so vetoing it would
        // be both wrong in spirit and silently inert.
        if (!wouldPlaceTheTemplateBlock && !"backfill".equals(caller)
                && !princeps.getBuilderProcess().scaffoldIsLicensedAt(placeAt)) {
            BuildTrace.cell(BuildTrace.tickNow(), "SCAFFOLD-REFUSED", placeAt.getX(), placeAt.getY(), placeAt.getZ(),
                    "no proven stance justifies a helper block here by=" + caller + " " + BuildTrace.intentNow());
            state.setStatus(MovementStatus.UNREACHABLE);
            return PlaceResult.NO_OPTION;
        }
        // THE ROUTE'S OWN RULE, asked of the route rather than of a global field.
        //
        // The check above reads a predicate on the builder process, which is exactly the thing that cannot be
        // trusted while a route is in flight: its answer depends on fields that every placement resets, so a route
        // that was allowed to bridge lost the permission the moment it bridged. This one reads the licence the
        // route was CREATED with, which nothing can change afterwards.
        //
        // Filling a cell the template names is deliberately NOT governed by it: that is construction, not a helper
        // block, and the owner's rule is about helper blocks ("only where the template wants air"). Refusing it was
        // measured once and froze the bot for 8800 ticks at 104,-58,89.
        //
        // Both checks stand side by side for now, and that is on purpose: every licence is unrestricted until the
        // lane driver starts issuing real ones, so introducing this changes no behaviour and can be measured as the
        // no-op it is. The predicate above goes when the driver lands.
        if (!wouldPlaceTheTemplateBlock && !"backfill".equals(caller)
                && !currentRouteLicence(princeps).permitsPlacement(placeAt)) {
            BuildTrace.cell(BuildTrace.tickNow(), "LANE-REFUSED", placeAt.getX(), placeAt.getY(), placeAt.getZ(),
                    "the route being driven may not place here (" + currentRouteLicence(princeps) + ") by=" + caller
                            + " " + BuildTrace.intentNow());
            state.setStatus(MovementStatus.UNREACHABLE);
            return PlaceResult.NO_OPTION;
        }
        Optional<Rotation> direct = RotationUtils.reachable(ctx, placeAt, wouldSneak); // we assume that if there is a block there, it must be replacable
        boolean found = false;
        if (direct.isPresent()) {
            state.setTarget(MovementTarget.forPlacement(direct.get()));
            found = true;
        }
        for (int i = 0; i < 5; i++) {
            BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
            if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                if (!((Princeps) princeps).getInventoryBehavior().selectThrowawayForLocation(false, placeAt.getX(), placeAt.getY(), placeAt.getZ())) { // get ready to place a throwaway block
                    Helper.HELPER.logDebug("bb pls get me some blocks. dirt, netherrack, cobble");
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                Rotation place = RotationUtils.calcRotationFromVec3d(wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                Rotation actual = princeps.getLookBehavior().getAimProcessor().peekRotationExact(place);
                HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), actual, ctx.playerController().getBlockReachDistance(), wouldSneak);
                if (res != null && res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
                    state.setTarget(MovementTarget.forPlacement(place));
                    found = true;

                    if (!preferDown) {
                        // if preferDown is true, we want the last option
                        // if preferDown is false, we want the first
                        break;
                    }
                }
            }
        }
        if (ctx.getSelectedBlock().isPresent()) {
            BlockPos selectedBlock = ctx.getSelectedBlock().get();
            Direction side = ((BlockHitResult) ctx.objectMouseOver()).getDirection();
            // only way for selectedBlock.equals(placeAt) to be true is if it's replaceable
            if (selectedBlock.equals(placeAt) || (MovementHelper.canPlaceAgainst(ctx, selectedBlock) && selectedBlock.relative(side).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                // THE SELECTION HAS TO SUCCEED AT THE CLICK, not merely have succeeded when we looked earlier.
                //
                // This return value was thrown away, and it is the whole bug. The check pass above (select=false,
                // ~30 lines up) runs while a face is being searched for, and refuses the movement if no suitable
                // block can be selected. This is the act pass, ticks later, and if it fails -- because the hotbar
                // has moved underneath it, which on a map art it does constantly -- the click went out anyway with
                // whatever happened to be in the hand.
                //
                // MEASURED 2026-08-18: "Cell 54235,81,39694 wanted: clay, got: lodestone" and "wanted:
                // gray_terracotta, got: black_terracotta". Neither came from the builder's own click (its plan
                // check fired zero times all run); both came through here, where a new row's first block is laid
                // by the navigation rather than by the builder. Which block belongs in a cell is never in doubt --
                // so a click that cannot hold that block must not be a click.
                if (!((Princeps) princeps).getInventoryBehavior()
                        .selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ())) {
                    BuildTrace.cell(BuildTrace.tickNow(), kind + "-REFUSED", placeAt.getX(), placeAt.getY(),
                            placeAt.getZ(), "the block this cell wants was gone from the hand at the click by="
                                    + caller + " " + BuildTrace.intentNow());
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                // The branch that actually hands the click to the caller, and it used to write nothing at all -- so
                // the one moment a block really goes in was the one moment with no line. Everything downstream had
                // to infer landings from a later BREAK, which only ever proves the 24 of 41 that got broken again.
                BuildTrace.cell(BuildTrace.tickNow(), kind, placeAt.getX(), placeAt.getY(), placeAt.getZ(), "ready to place by=" + caller + " " + BuildTrace.intentNow());
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            ((Princeps) princeps).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
            // Helper blocks were invisible until now: the build trace records what the BUILDER does, and a block the
            // pathfinder lays to stand on is not a build action. So "cobblestone LAND: 0" could be read off a run
            // that was placing them steadily, and nobody could count what goal.md's criterion 7 forbids leaving
            // behind.
            //
            // This is the AIMING branch, not the placing one -- named accordingly, because the original name said
            // "placed" and got counted as such: 57 lines over 41 coordinates in 20260802-232954, up to four lines
            // for a single block while the bot turned towards it.
            BuildTrace.cell(BuildTrace.tickNow(), kind + "-AIM", placeAt.getX(), placeAt.getY(), placeAt.getZ(),
                    "turning towards the face to place against");
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    enum PlaceResult {
        READY_TO_PLACE, ATTEMPTING, NO_OPTION;
    }

    static boolean isTransparent(Block b) {

        return b instanceof AirBlock ||
                b == Blocks.LAVA ||
                b == Blocks.WATER;
    }

    static List<BetterBlockPos> steppingOnBlocks(IPlayerContext ctx) {
        List<BetterBlockPos> blocks = new ArrayList<>();
        for (byte x = -1; x <= 1; x++) {
            for (byte z = -1; z <= 1; z++) {
                if (ctx.player().getBoundingBox().intersects(Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x, 0, z), Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x + 1, 1, z + 1))) {
                    blocks.add(new BetterBlockPos(ctx.player().getBlockX() + x, ctx.player().getBlockY() - 1, ctx.player().getBlockZ() + z));
                }
            }
        }
        return blocks;
    }
}
