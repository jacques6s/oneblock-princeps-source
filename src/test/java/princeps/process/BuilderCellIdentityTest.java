/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * S0b of the owner's build algorithm v0.4: <em>what is a cell at all?</em>
 *
 * <p>The macro loop puts the cells of layer E into the ACTIVE list and the micro loop runs until ACTIVE is empty;
 * P6a then aborts the build if anything is left in PARK. So a position that can never be placed by hand must never
 * enter ACTIVE in the first place — otherwise it is parked, nothing can ever wake it, and P6a kills a layer that
 * was in fact finished. The spec names five such families: piston heads, moving pistons, the UPPER half of a door,
 * the HEAD of a bed, and fluids. A piston, a pane of glass and the LOWER half of a door are ordinary cells.
 *
 * <h2>What this test mirrors</h2>
 *
 * <p>Two of the three rules below already exist in {@link BuilderProcess}, but as {@code private static} members, so
 * they cannot be called from here. They are reproduced verbatim as {@code mirror*} helpers — the same convention
 * {@code BuilderWaterloggingPolicyTest}, {@code BuilderDeferralPolicyTest} and {@code BuilderTemporarySupportPolicyTest}
 * use for the same reason. If the originals change, these copies must change with them.
 *
 * <ul>
 *   <li>{@link #mirrorIsSecondaryHalf} mirrors {@code BuilderProcess.isSecondaryHalf(BlockState)} —
 *       {@code BuilderProcess.java:6209}, private static.</li>
 *   <li>{@link #mirrorItemCanPlaceBlock} mirrors {@code BuilderProcess.itemCanPlaceBlock(BlockState, BlockState)} —
 *       {@code BuilderProcess.java:3593}, private static.</li>
 *   <li>{@link #mirrorNotACellOfItsOwn} is the S0b rule itself. It does <em>not</em> exist in {@code BuilderProcess}
 *       yet; it is the {@code notACellOfItsOwn} / {@code isNoItemCell} method the conversion map schedules for
 *       block B4, to be wired into {@code fullRecalc}, {@code recalcNearby} and {@code firstMissingMaterial}. This
 *       test states the contract that method has to satisfy before it is written.</li>
 * </ul>
 */
public class BuilderCellIdentityTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ------------------------------------------------------------------------------------------------------------
    // S0b: the five families that are not cells
    // ------------------------------------------------------------------------------------------------------------

    @Test
    public void pistonHeadAndMovingPistonAreNotCells() {
        assertTrue(mirrorNotACellOfItsOwn(Blocks.PISTON_HEAD.defaultBlockState()));
        assertTrue(mirrorNotACellOfItsOwn(Blocks.MOVING_PISTON.defaultBlockState()));
    }

    @Test
    public void theUpperHalfOfADoorAndTheHeadOfABedAreNotCells() {
        assertTrue(mirrorNotACellOfItsOwn(doorHalf(DoubleBlockHalf.UPPER)));
        assertTrue(mirrorNotACellOfItsOwn(bedPart(BedPart.HEAD)));
    }

    @Test
    public void fluidsAreNotCellsBecauseTheyBelongToTheLaterFluidPass() {
        assertTrue(mirrorNotACellOfItsOwn(Blocks.WATER.defaultBlockState()));
        assertTrue(mirrorNotACellOfItsOwn(Blocks.LAVA.defaultBlockState()));
    }

    @Test
    public void nothingAtAllIsNotACellEither() {
        assertTrue(mirrorNotACellOfItsOwn(null));
        assertTrue(mirrorNotACellOfItsOwn(Blocks.AIR.defaultBlockState()));
        assertTrue(mirrorNotACellOfItsOwn(Blocks.CAVE_AIR.defaultBlockState()));
    }

    @Test
    public void pistonsGlassAndTheLowerHalfOfADoorAreOrdinaryCells() {
        assertFalse(mirrorNotACellOfItsOwn(Blocks.PISTON.defaultBlockState()));
        assertFalse(mirrorNotACellOfItsOwn(Blocks.STICKY_PISTON.defaultBlockState()));
        assertFalse(mirrorNotACellOfItsOwn(Blocks.GLASS.defaultBlockState()));
        assertFalse(mirrorNotACellOfItsOwn(Blocks.BLACK_STAINED_GLASS.defaultBlockState()));
        assertFalse(mirrorNotACellOfItsOwn(doorHalf(DoubleBlockHalf.LOWER)));
        assertFalse(mirrorNotACellOfItsOwn(bedPart(BedPart.FOOT)));
        assertFalse(mirrorNotACellOfItsOwn(Blocks.STONE.defaultBlockState()));
    }

    /**
     * The piston is the whole point of the distinction: {@code piston_head} and {@code piston} share a name and a
     * schematic row, and exactly one of them is work. Getting this backwards costs a piston-heavy schematic every
     * layer it contains one.
     */
    @Test
    public void aPistonHeadIsNotItsPiston() {
        assertTrue(mirrorNotACellOfItsOwn(Blocks.PISTON_HEAD.defaultBlockState()));
        assertFalse(mirrorNotACellOfItsOwn(Blocks.PISTON.defaultBlockState()));
        assertFalse(mirrorNotACellOfItsOwn(Blocks.STICKY_PISTON.defaultBlockState()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // isSecondaryHalf: which half of a two-block block is the one you cannot click
    // ------------------------------------------------------------------------------------------------------------

    @Test
    public void secondaryHalfIsTheUpperDoorAndTheBedHead() {
        assertTrue(mirrorIsSecondaryHalf(doorHalf(DoubleBlockHalf.UPPER)));
        assertTrue(mirrorIsSecondaryHalf(bedPart(BedPart.HEAD)));
    }

    @Test
    public void secondaryHalfIsNotTheLowerDoorTheBedFootOrAnyOrdinaryBlock() {
        assertFalse(mirrorIsSecondaryHalf(doorHalf(DoubleBlockHalf.LOWER)));
        assertFalse(mirrorIsSecondaryHalf(bedPart(BedPart.FOOT)));
        assertFalse(mirrorIsSecondaryHalf(Blocks.GLASS.defaultBlockState()));
        assertFalse(mirrorIsSecondaryHalf(Blocks.PISTON.defaultBlockState()));
        assertFalse(mirrorIsSecondaryHalf(null));
    }

    /**
     * {@code isSecondaryHalf} alone is not the S0b rule, and this is the assertion that says why the wider
     * {@code notACellOfItsOwn} has to exist: it lets a piston head, a moving piston and a bucket of water straight
     * through into the ACTIVE list.
     */
    @Test
    public void secondaryHalfAloneDoesNotCoverTheS0bFamilies() {
        assertFalse(mirrorIsSecondaryHalf(Blocks.PISTON_HEAD.defaultBlockState()));
        assertFalse(mirrorIsSecondaryHalf(Blocks.MOVING_PISTON.defaultBlockState()));
        assertFalse(mirrorIsSecondaryHalf(Blocks.WATER.defaultBlockState()));

        assertTrue(mirrorNotACellOfItsOwn(Blocks.PISTON_HEAD.defaultBlockState()));
        assertTrue(mirrorNotACellOfItsOwn(Blocks.MOVING_PISTON.defaultBlockState()));
        assertTrue(mirrorNotACellOfItsOwn(Blocks.WATER.defaultBlockState()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // itemCanPlaceBlock: the material test, and why it cannot stand in for S0b
    // ------------------------------------------------------------------------------------------------------------

    /** No item exists that puts these down; that is the structural reason they are not cells. */
    @Test
    public void theNoItemFamiliesReallyHaveNoItem() {
        assertSame(Items.AIR, Blocks.PISTON_HEAD.asItem());
        assertSame(Items.AIR, Blocks.MOVING_PISTON.asItem());
        assertSame(Items.AIR, Blocks.WATER.asItem());
        assertSame(Items.AIR, Blocks.LAVA.asItem());
    }

    @Test
    public void noOrdinaryMaterialCanSupplyAPistonHead() {
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState();
        assertFalse(mirrorItemCanPlaceBlock(Blocks.PISTON.defaultBlockState(), head));
        assertFalse(mirrorItemCanPlaceBlock(Blocks.STICKY_PISTON.defaultBlockState(), head));
        assertFalse(mirrorItemCanPlaceBlock(Blocks.STONE.defaultBlockState(), head));
    }

    /**
     * The {@code != Items.AIR} guard inside {@code itemCanPlaceBlock} carries real weight: without it every pair of
     * item-less blocks would match on "both place nothing", and water would be reported as a valid source of a
     * piston head.
     */
    @Test
    public void twoItemlessBlocksAreNotInterchangeable() {
        assertFalse(mirrorItemCanPlaceBlock(Blocks.WATER.defaultBlockState(),
                Blocks.PISTON_HEAD.defaultBlockState()));
        assertFalse(mirrorItemCanPlaceBlock(Blocks.MOVING_PISTON.defaultBlockState(),
                Blocks.PISTON_HEAD.defaultBlockState()));
    }

    /**
     * And this is why a material test can never replace S0b: asked about a piston head against a piston head,
     * {@code itemCanPlaceBlock} says yes on block identity alone. A scan that only consulted the material would
     * therefore admit the head into ACTIVE, fail to place it forever, and park it — the exact shape P6a turns into
     * an aborted layer.
     */
    @Test
    public void identityMatchWouldSmuggleAPistonHeadPastTheMaterialTest() {
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState();
        assertTrue(mirrorItemCanPlaceBlock(head, head));
        assertTrue(mirrorNotACellOfItsOwn(head));
    }

    @Test
    public void oneItemCoveringTwoBlocksStillCounts() {
        // torch / wall_torch, the family the original comment on itemCanPlaceBlock was written for
        assertFalse(Blocks.TORCH.asItem() == Items.AIR);
        assertTrue(mirrorItemCanPlaceBlock(Blocks.TORCH.defaultBlockState(),
                Blocks.WALL_TORCH.defaultBlockState()));
        assertTrue(mirrorItemCanPlaceBlock(Blocks.WALL_TORCH.defaultBlockState(),
                Blocks.TORCH.defaultBlockState()));
        // and unrelated materials still do not match
        assertFalse(mirrorItemCanPlaceBlock(Blocks.STONE.defaultBlockState(),
                Blocks.GLASS.defaultBlockState()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    private static BlockState doorHalf(DoubleBlockHalf half) {
        return Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, half);
    }

    private static BlockState bedPart(BedPart part) {
        return Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, part);
    }

    // ------------------------------------------------------------------------------------------------------------
    // mirrors of BuilderProcess internals — keep byte-for-byte in step with the originals
    // ------------------------------------------------------------------------------------------------------------

    /** Mirror of {@code BuilderProcess.isSecondaryHalf(BlockState)} (private static, BuilderProcess.java:6209). */
    private static boolean mirrorIsSecondaryHalf(BlockState desired) {
        if (desired == null) {
            return false;
        }
        Block b = desired.getBlock();
        if (b instanceof DoorBlock) {
            return desired.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        }
        if (b instanceof BedBlock) {
            return desired.getValue(BedBlock.PART) == BedPart.HEAD;
        }
        return false;
    }

    /**
     * Mirror of {@code BuilderProcess.itemCanPlaceBlock(BlockState, BlockState)} (private static,
     * BuilderProcess.java:3593).
     */
    private static boolean mirrorItemCanPlaceBlock(BlockState available, BlockState desired) {
        if (available.getBlock() == desired.getBlock()) {
            return true;
        }
        Item availableItem = available.getBlock().asItem();
        return availableItem != Items.AIR && availableItem == desired.getBlock().asItem();
    }

    /**
     * The S0b rule. Not yet present in {@code BuilderProcess} — this is the shape {@code notACellOfItsOwn}
     * (a.k.a. {@code isNoItemCell}) must have when block B4 introduces it and hangs it off {@code fullRecalc},
     * {@code recalcNearby} and {@code firstMissingMaterial}.
     */
    private static boolean mirrorNotACellOfItsOwn(BlockState desired) {
        if (desired == null || desired.isAir()) {
            return true;
        }
        if (mirrorIsSecondaryHalf(desired)) {
            return true;                                   // door UPPER, bed HEAD
        }
        Block b = desired.getBlock();
        if (b instanceof PistonHeadBlock || b instanceof MovingPistonBlock) {
            return true;                                   // no BlockItem exists for either
        }
        return b instanceof LiquidBlock;                   // water/lava belong to the fluid pass
    }
}
