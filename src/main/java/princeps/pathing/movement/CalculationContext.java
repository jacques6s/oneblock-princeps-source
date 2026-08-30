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
import princeps.api.IPrinceps;
// Imported, never qualified: this class has a public field named `princeps`, which shadows the package root.
import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.movement.ActionCosts;
import princeps.cache.WorldData;
import princeps.pathing.precompute.PrecomputedData;
import princeps.utils.BlockStateInterface;
import princeps.utils.ToolSet;
import princeps.utils.pathing.BetterWorldBorder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static princeps.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    public final boolean safeForThreadedUse;
    public final IPrinceps princeps;
    public final Level world;
    public final WorldData worldData;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    // NOT final, unlike its neighbours: BuilderCalculationContext raises it. The builder has a reason the ordinary
    // pathfinder does not -- it must REACH cells, and a course that is only partly built is full of one-block gaps in
    // the floor. Forbidding the jump does not stop the bot, it makes it fill the gap with a helper block instead, and
    // a helper block is the thing that gets built in and cannot be taken out again. Measured on run bc6db984: 171 of
    // 174 helper blocks went in at y=-60, i.e. into the FLOOR, 123 of them 20-34 blocks out at the rim. Those are
    // jumps, priced as construction. The user's setting stays untouched; only the builder's own context differs.
    public boolean allowParkour;
    // Stays as the user set it, deliberately, and the builder does NOT raise it: allowParkourPlace is the jump that
    // places a block in mid-air, which is precisely the unreachable, unremovable helper block the owner's rule exists
    // to prevent. Pure jumping adds no blocks at all; that is why only the field above is opened.
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    // Base Hunter Y ceiling: when enabled, no movement may reach a destination Y above baseHuntMaxY. Read
    // once here (like every other tunable) so all movements in a single path-calc are consistent.
    public final boolean baseHuntYCeiling;
    public final int baseHuntMaxY;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;

    public final PrecomputedData precomputedData;

    public CalculationContext(IPrinceps princeps) {
        this(princeps, false);
    }

    public CalculationContext(IPrinceps princeps, boolean forUseOnAnotherThread) {
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.princeps = princeps;
        LocalPlayer player = princeps.getPlayerContext().player();
        this.world = princeps.getPlayerContext().world();
        this.worldData = (WorldData) princeps.getPlayerContext().worldData();
        this.bsi = new BlockStateInterface(princeps.getPlayerContext(), forUseOnAnotherThread);
        this.toolSet = new ToolSet(player);
        this.hasThrowaway = Princeps.settings().allowPlace.value && ((Princeps) princeps).getInventoryBehavior().hasGenericThrowaway();
        this.hasWaterBucket = Princeps.settings().allowWaterBucketFall.value && Inventory.isHotbarSlot(player.getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) && world.dimension() != Level.NETHER;
        this.canSprint = Princeps.settings().allowSprint.value && player.getFoodData().getFoodLevel() > 6;
        this.placeBlockCost = Princeps.settings().blockPlacementPenalty.value;
        this.allowBreak = Princeps.settings().allowBreak.value;
        this.allowBreakAnyway = new ArrayList<>(Princeps.settings().allowBreakAnyway.value);
        this.allowParkour = Princeps.settings().allowParkour.value;
        this.allowParkourPlace = Princeps.settings().allowParkourPlace.value;
        this.allowJumpAtBuildLimit = Princeps.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = Princeps.settings().allowParkourAscend.value;
        this.assumeWalkOnWater = Princeps.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        // todo: technically there can now be datapack enchants that replace blocks with any other at any range
        int frostWalkerLevel = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = princeps.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    frostWalkerLevel = itemEnchantments.getLevel(enchant);
                }
            }
        }
        this.frostWalker = frostWalkerLevel;
        this.allowDiagonalDescend = Princeps.settings().allowDiagonalDescend.value;
        this.allowDiagonalAscend = Princeps.settings().allowDiagonalAscend.value;
        this.allowDownward = Princeps.settings().allowDownward.value;
        // Base-hunt Y ceiling is OVERWORLD-ONLY by construction: the standard base hunt is an
        // overworld mode (other dimensions get their own mechanisms later). Gating here — at the
        // single point every path calculation reads the clamp — means a stale/stuck setting can
        // never strangle nether/end pathing no matter what the client-side lifecycle does.
        this.baseHuntYCeiling = Princeps.settings().baseHuntYCeiling.value
                && !this.world.dimensionType().hasCeiling()
                && !this.world.dimensionType().hasEnderDragonFight();
        this.baseHuntMaxY = Princeps.settings().baseHuntMaxY.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = Princeps.settings().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = Princeps.settings().maxFallHeightBucket.value;
        float waterSpeedMultiplier = 1.0f;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = princeps.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value()
                    .getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        waterSpeedMultiplier = effect.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.breakBlockAdditionalCost = Princeps.settings().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = Princeps.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Princeps.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Princeps.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Princeps.settings().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    }

    public final IPrinceps getPrinceps() {
        return princeps;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!Princeps.settings().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!Princeps.settings().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    /**
     * Route-local node filter. Ordinary navigation accepts every geometrically valid destination; specialised
     * callers may narrow one calculation without replacing the pathfinder or its human movement controller.
     */
    public boolean isPathPositionAllowed(int x, int y, int z) {
        return true;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        return 1;
    }

    /**
     * Whether a route owned by this context may use doors and fence gates.
     *
     * <p>The ordinary navigator may: {@code MovementTraverse} knows how to open them. A builder that has frozen and
     * proved an exact world-state plan may not, because toggling one is an unplanned world mutation and invalidates
     * that proof. Kept on the calculation context so the rule follows the path that requested it instead of changing
     * navigation globally.
     */
    public boolean mayUsePathingBarriers() {
        return true;
    }

    /**
     * Would the block this context PLACES at (x,y,z) be a standable full cube? Pillar / ascend / bridge moves stand
     * on the block they place, so a placement that yields a fence/wall/thin non-cube top is not a valid step-up
     * target. The base context places a generic throwaway full cube, so this is always true; the schematic builder
     * overrides it to check the schematic's own block (a schematic fence must never be pillared onto).
     */
    public boolean placedBlockIsStandable(int x, int y, int z, BlockState current) {
        return true;
    }

    /**
     * What a route planned with THIS context may do to the world while it is driven — the "Setz-Erlaubnis".
     *
     * <p>Read at exactly two moments and never in between: once by the search, so a lane that may not place blocks
     * cannot return a route that does; and once when the route's executor is created, so the driving obeys the rules
     * the planning used. That is the whole of "no mixed operation", and it is why this lives on the context rather
     * than on a process: a context belongs to one search, a process outlives every route it starts.
     *
     * <p>The base context is unrestricted, so ordinary navigation is untouched.
     */
    public PlacementLicence placementLicence() {
        return PlacementLicence.UNRESTRICTED;
    }

    /**
     * Where a route planned with THIS context may stand in water instead of treating it as a wall — the
     * "Wat-Erlaubnis". Read at the same two moments as {@link #placementLicence()} and for the same reason.
     *
     * <p>The base context licenses nothing, so ordinary navigation is untouched: it goes on refusing flowing liquid
     * outright, which in an open world is the correct caution. Only a caller that owns the cell it is asking about
     * — the excavation lane owns the corridor it cut — may say otherwise.
     */
    public WadeLicence wadeLicence() {
        return WadeLicence.NONE;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        // TODO more protection logic here; see #220
        return false;
    }
}
