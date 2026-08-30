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

package princeps.process.builder.v3;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The three neighbour-sensitive family rules, pinned against hand-derived expectations.
 *
 * <h2>Why this compares against a transcription and not against vanilla directly</h2>
 * <p>All three vanilla decisions — {@code DoorBlock.getHinge}, {@code ChestBlock.getChestType} and
 * {@code StandingAndWallBlockItem.getPlacementState} — take a {@code BlockPlaceContext}, which requires a {@code Level}
 * and a {@code Player}, and a headless test has neither: {@code Bootstrap.bootStrap()} gives us registries and block
 * states, not a world. There is therefore no way to call vanilla's own code from here and diff the answers, and any
 * test that claimed to was really testing a mock.
 *
 * <p>What is done instead: every expectation below was derived by hand from the 26.1.2 bytecode of those three
 * methods, disassembled from the Mojmap jar this project compiles against, and the cases were chosen to discriminate
 * between the rule as written and the plausible ways of getting it wrong — the sign of the door's neighbour score, the
 * clockwise/counter-clockwise direction of the chest's LEFT/RIGHT, the sneak guard's position relative to the world
 * read, and the exact face that yields a wall variant. A test that only asserted "a chest next to a chest is double"
 * would pass against a rule with LEFT and RIGHT swapped.
 *
 * <p>The world-backed halves run against a real {@link PredictedWorld} built from a map of cells, so the neighbour
 * addressing (which cell is "left", which cell is the support) is under test too and not only the arithmetic.
 */
public class PlacementFamiliesTest {

    private static final BlockPos CELL = new BlockPos(0, 0, 0);

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ------------------------------------------------------------------------------------------- classification

    @Test
    public void theThreeFamiliesAreRecognisedAndNothingElseIs() {
        assertEquals(PlacementFamilies.Family.DOOR_HINGE,
                PlacementFamilies.classify(Blocks.OAK_DOOR.defaultBlockState()));
        assertEquals(PlacementFamilies.Family.DOOR_HINGE,
                PlacementFamilies.classify(Blocks.IRON_DOOR.defaultBlockState()));

        assertEquals(PlacementFamilies.Family.CHEST_TYPE,
                PlacementFamilies.classify(Blocks.CHEST.defaultBlockState()));
        // TrappedChestBlock extends ChestBlock, so it pairs by the same rule and must classify the same way.
        assertEquals(PlacementFamilies.Family.CHEST_TYPE,
                PlacementFamilies.classify(Blocks.TRAPPED_CHEST.defaultBlockState()));
        // EnderChestBlock does NOT extend ChestBlock and has no TYPE property -- classifying it as a chest would make
        // the resolver read a property that is not there.
        assertEquals(PlacementFamilies.Family.NONE,
                PlacementFamilies.classify(Blocks.ENDER_CHEST.defaultBlockState()));

        // Both halves of the standing/wall family answer the same item, which is what makes membership readable off
        // the item at all. If Item.BY_BLOCK were not populated by Bootstrap, the wall forms below would report NONE.
        for (BlockState state : new BlockState[] {
                Blocks.OAK_SIGN.defaultBlockState(), Blocks.OAK_WALL_SIGN.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(), Blocks.WALL_TORCH.defaultBlockState(),
                Blocks.WHITE_BANNER.defaultBlockState(), Blocks.WHITE_WALL_BANNER.defaultBlockState(),
                Blocks.SKELETON_SKULL.defaultBlockState(), Blocks.SKELETON_WALL_SKULL.defaultBlockState(),
                Blocks.OAK_HANGING_SIGN.defaultBlockState(), Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState() }) {
            assertEquals(state.toString(), PlacementFamilies.Family.STANDING_OR_WALL,
                    PlacementFamilies.classify(state));
        }

        // Wall-mounted but NOT this family: ladders, buttons and levers are one block with a placement-derived state,
        // no second variant and no neighbour read beyond canSurvive.
        for (BlockState state : new BlockState[] {
                Blocks.STONE.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(),
                Blocks.LADDER.defaultBlockState(), Blocks.LEVER.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState() }) {
            assertEquals(state.toString(), PlacementFamilies.Family.NONE, PlacementFamilies.classify(state));
            assertFalse(state.toString(), PlacementFamilies.isNeighbourSensitive(state));
        }

        assertTrue(PlacementFamilies.isNeighbourSensitive(Blocks.OAK_DOOR.defaultBlockState()));
        assertTrue(PlacementFamilies.isNeighbourSensitive(Blocks.CHEST.defaultBlockState()));
        assertTrue(PlacementFamilies.isNeighbourSensitive(Blocks.WALL_TORCH.defaultBlockState()));
    }

    // ------------------------------------------------------------------------------------------------- doors

    /**
     * Stage 1 of {@code getHinge}: a signed count of the solid cells flanking the door, negative counter-clockwise of
     * facing and positive clockwise, both halves of each column weighed equally.
     */
    @Test
    public void doorHingeCountsSolidNeighboursWithTheSignVanillaUses() {
        // A solid block on the counter-clockwise ("left") side pushes the hinge to the LEFT -- the door swings away
        // from the wall it is against. One cell is enough; the score only has to be non-zero.
        assertEquals(DoorHingeSide.LEFT, hinge(Direction.NORTH, true, false, false, false, 0.5D, 0.5D));
        assertEquals(DoorHingeSide.LEFT, hinge(Direction.NORTH, false, true, false, false, 0.5D, 0.5D));
        assertEquals(DoorHingeSide.RIGHT, hinge(Direction.NORTH, false, false, true, false, 0.5D, 0.5D));
        assertEquals(DoorHingeSide.RIGHT, hinge(Direction.NORTH, false, false, false, true, 0.5D, 0.5D));

        // Balanced walls cancel exactly and hand the decision to the hit position, which is the whole reason the aim
        // point is an input to this rule.
        assertEquals(DoorHingeSide.LEFT, hinge(Direction.NORTH, true, true, true, true, 0.3D, 0.5D));
        assertEquals(DoorHingeSide.RIGHT, hinge(Direction.NORTH, true, true, true, true, 0.7D, 0.5D));
    }

    /**
     * Stage 2: an adjacent LOWER door half beats the score outright, in both directions. This is what makes a pair of
     * doors mirror into a double door instead of both hinging the same way.
     */
    @Test
    public void anAdjacentLowerDoorOverridesTheNeighbourScore() {
        // Left neighbour is a door, right is not: RIGHT, even though two solid cells on the left would otherwise have
        // forced LEFT. The override is disjoined with the score, so it fires against a score of -2.
        assertEquals(DoorHingeSide.RIGHT,
                PlacementFamilies.doorHinge(Direction.NORTH, true, true, false, false, true, false, 0.5D, 0.5D));
        // Mirror image, and it has to be scored differently: vanilla tests the RIGHT clause first, so a right-hand
        // door with a POSITIVE score would still answer RIGHT. What the LEFT override beats is the hit tiebreak --
        // a hit at x=0.9 facing north is RIGHT on its own, and the door flag turns it LEFT.
        assertEquals(DoorHingeSide.RIGHT,
                PlacementFamilies.doorHinge(Direction.NORTH, false, false, false, false, false, false, 0.9D, 0.5D));
        assertEquals(DoorHingeSide.LEFT,
                PlacementFamilies.doorHinge(Direction.NORTH, false, false, false, false, false, true, 0.9D, 0.5D));
        // Doors on BOTH sides cancel each other and the score decides again -- neither override clause fires when both
        // flags are set, which is the case a rule written as two independent ifs gets wrong.
        assertEquals(DoorHingeSide.RIGHT,
                PlacementFamilies.doorHinge(Direction.NORTH, false, false, true, false, true, true, 0.5D, 0.5D));
        assertEquals(DoorHingeSide.LEFT,
                PlacementFamilies.doorHinge(Direction.NORTH, true, false, false, false, true, true, 0.5D, 0.5D));
    }

    /**
     * Stage 3: the tiebreak reads the hit against {@code facing}'s step vector, and vanilla's comparisons are
     * asymmetric — an exact 0.5 goes LEFT on all four axes. Aim points land on exact halves constantly, so this is the
     * common case and not a corner case.
     */
    @Test
    public void theHitTiebreakFollowsFacingAndSendsExactHalvesLeft() {
        // facing NORTH: stepZ = -1, so the X fraction decides and >0.5 is RIGHT.
        assertEquals(DoorHingeSide.RIGHT, hinge(Direction.NORTH, false, false, false, false, 0.9D, 0.5D));
        assertEquals(DoorHingeSide.LEFT, hinge(Direction.NORTH, false, false, false, false, 0.1D, 0.5D));
        // facing SOUTH: stepZ = +1, the sense inverts.
        assertEquals(DoorHingeSide.LEFT, hinge(Direction.SOUTH, false, false, false, false, 0.9D, 0.5D));
        assertEquals(DoorHingeSide.RIGHT, hinge(Direction.SOUTH, false, false, false, false, 0.1D, 0.5D));
        // facing EAST: stepX = +1, so the Z fraction decides and >0.5 is RIGHT.
        assertEquals(DoorHingeSide.RIGHT, hinge(Direction.EAST, false, false, false, false, 0.5D, 0.9D));
        assertEquals(DoorHingeSide.LEFT, hinge(Direction.EAST, false, false, false, false, 0.5D, 0.1D));
        // facing WEST: stepX = -1.
        assertEquals(DoorHingeSide.LEFT, hinge(Direction.WEST, false, false, false, false, 0.5D, 0.9D));
        assertEquals(DoorHingeSide.RIGHT, hinge(Direction.WEST, false, false, false, false, 0.5D, 0.1D));

        // Exactly 0.5 on every facing: vanilla's four comparisons are <0.5 / >0.5 / >0.5 / <0.5, none of which a 0.5
        // satisfies, so the fallthrough is LEFT. A rule written with >= or <= anywhere flips one of these.
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            assertEquals(facing.toString(), DoorHingeSide.LEFT,
                    hinge(facing, false, false, false, false, 0.5D, 0.5D));
        }
    }

    /** The same rule over a real {@link PredictedWorld}, so the neighbour ADDRESSING is under test and not only the
     *  arithmetic: counter-clockwise of NORTH is WEST, and both halves of that column are read. */
    @Test
    public void doorHingeReadsTheCellsVanillaReads() {
        Vec3 centre = new Vec3(0.5D, 0.5D, 0.0D);

        // Wall to the WEST (counter-clockwise of NORTH) -> hinge LEFT.
        assertEquals(DoorHingeSide.LEFT, PlacementFamilies.predictDoorHinge(
                world(cells().set(-1, 0, 0, Blocks.STONE)), CELL, Direction.NORTH, centre));
        // Only the UPPER left cell solid: still LEFT, because vanilla weighs the head-height neighbour too. A rule
        // that read only the feet cell would return the 0.5-tiebreak answer here instead.
        assertEquals(DoorHingeSide.LEFT, PlacementFamilies.predictDoorHinge(
                world(cells().set(-1, 1, 0, Blocks.STONE)), CELL, Direction.NORTH, centre));
        // Wall to the EAST (clockwise) -> RIGHT.
        assertEquals(DoorHingeSide.RIGHT, PlacementFamilies.predictDoorHinge(
                world(cells().set(1, 0, 0, Blocks.STONE)), CELL, Direction.NORTH, centre));

        // An existing lower door half to the WEST wins over everything: this is the second leaf of a double door.
        // The door itself is not a full cube, so it contributes nothing to the score -- only the override fires.
        BlockState lowerDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH);
        assertEquals(DoorHingeSide.RIGHT, PlacementFamilies.predictDoorHinge(
                world(cells().set(-1, 0, 0, lowerDoor)), CELL, Direction.NORTH, centre));
        // The UPPER half is not a partner -- vanilla checks HALF == LOWER explicitly, and an upper half beside the
        // cell means the door's own lower half is one cell further down and does not flank this one.
        assertEquals(DoorHingeSide.LEFT, PlacementFamilies.predictDoorHinge(
                world(cells().set(-1, 0, 0, lowerDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
                        .set(-1, 1, 0, Blocks.STONE)),
                CELL, Direction.NORTH, centre));
    }

    @Test
    public void resolveWritesTheHingeOntoTheCandidateAndLeavesTheRestAlone() {
        BlockState candidate = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);

        BlockState landed = PlacementFamilies.resolve(world(cells().set(1, 0, 0, Blocks.STONE)), CELL, candidate,
                Direction.UP, new Vec3(0.5D, 0.0D, 0.5D), false);

        assertNotNull(landed);
        assertEquals(DoorHingeSide.RIGHT, landed.getValue(DoorBlock.HINGE));
        assertEquals(Direction.NORTH, landed.getValue(DoorBlock.FACING));
        assertEquals(DoubleBlockHalf.LOWER, landed.getValue(DoorBlock.HALF));
    }

    // ------------------------------------------------------------------------------------------------ chests

    /**
     * {@code candidatePartnerFacing}: only a same-block, still-SINGLE chest is a candidate. The SINGLE check is what
     * stops a third chest joining an existing double.
     */
    @Test
    public void onlyAnUnpairedChestOfTheSameKindIsAPartner() {
        BlockState single = chest(Blocks.CHEST, Direction.NORTH, ChestType.SINGLE);

        assertEquals(Direction.NORTH, PlacementFamilies.candidatePartnerFacing(single, Blocks.CHEST));
        assertNull(PlacementFamilies.candidatePartnerFacing(
                chest(Blocks.CHEST, Direction.NORTH, ChestType.LEFT), Blocks.CHEST));
        assertNull(PlacementFamilies.candidatePartnerFacing(Blocks.STONE.defaultBlockState(), Blocks.CHEST));
        assertNull(PlacementFamilies.candidatePartnerFacing(null, Blocks.CHEST));
        // chestCanConnectTo is state.is(this): a trapped chest is a different block and never pairs with a plain one.
        assertNull(PlacementFamilies.candidatePartnerFacing(
                chest(Blocks.TRAPPED_CHEST, Direction.NORTH, ChestType.SINGLE), Blocks.CHEST));
    }

    /**
     * The direction of LEFT and RIGHT, which is the one thing in this family that is easy to write backwards and
     * impossible to notice: two chests that both claim the same half stay two single chests.
     */
    @Test
    public void aPartnerClockwiseOfFacingMakesThisChestTheLeftHalf() {
        BlockState partner = chest(Blocks.CHEST, Direction.NORTH, ChestType.SINGLE);
        BlockState air = Blocks.AIR.defaultBlockState();

        assertEquals(ChestType.LEFT, PlacementFamilies.chestType(Direction.NORTH, partner, air, Blocks.CHEST));
        assertEquals(ChestType.RIGHT, PlacementFamilies.chestType(Direction.NORTH, air, partner, Blocks.CHEST));
        assertEquals(ChestType.SINGLE, PlacementFamilies.chestType(Direction.NORTH, air, air, Blocks.CHEST));

        // A neighbour facing a different way is not a partner: both halves of a double chest share one facing.
        BlockState crosswise = chest(Blocks.CHEST, Direction.EAST, ChestType.SINGLE);
        assertEquals(ChestType.SINGLE, PlacementFamilies.chestType(Direction.NORTH, crosswise, crosswise,
                Blocks.CHEST));
    }

    /** Neighbour addressing over a real world: clockwise of NORTH is EAST. */
    @Test
    public void predictChestTypeReadsTheClockwiseCellFirst() {
        BlockState partner = chest(Blocks.CHEST, Direction.NORTH, ChestType.SINGLE);

        assertEquals(ChestType.LEFT, PlacementFamilies.predictChestType(
                world(cells().set(1, 0, 0, partner)), CELL, Direction.NORTH, false));
        assertEquals(ChestType.RIGHT, PlacementFamilies.predictChestType(
                world(cells().set(-1, 0, 0, partner)), CELL, Direction.NORTH, false));
        assertEquals(ChestType.SINGLE, PlacementFamilies.predictChestType(
                world(cells()), CELL, Direction.NORTH, false));
    }

    /**
     * The rule that matters more than the prediction. Sneak forces SINGLE, and vanilla answers that guard BEFORE the
     * world read — {@code getChestType} is only reached under {@code !isSecondaryUseActive()}.
     *
     * <p>The null world is deliberate and is the assertion: if an implementer ever moves the neighbour read ahead of
     * the sneak guard, this test throws instead of quietly costing every planned double chest in the schematic.
     */
    @Test
    public void sneakForcesSingleBeforeAnyWorldReadHappens() {
        assertEquals(ChestType.SINGLE, PlacementFamilies.predictChestType(null, CELL, Direction.NORTH, true));

        // And with a genuine partner present, so the answer is not SINGLE by accident.
        PredictedWorld withPartner = world(cells().set(1, 0, 0, chest(Blocks.CHEST, Direction.NORTH,
                ChestType.SINGLE)));
        assertEquals(ChestType.LEFT, PlacementFamilies.predictChestType(withPartner, CELL, Direction.NORTH, false));
        assertEquals(ChestType.SINGLE, PlacementFamilies.predictChestType(withPartner, CELL, Direction.NORTH, true));
    }

    /**
     * Vanilla's one branch in which a sneaking placement still pairs: sneak-clicking the horizontal face of an
     * existing single chest adopts that chest's facing. It rewrites FACING as well as TYPE, which is why it lives in
     * {@code resolve} and cannot be expressed by {@code predictChestType}.
     */
    @Test
    public void sneakClickingTheSideOfAChestStillPairsAndAdoptsItsFacing() {
        // against = cell + WEST, so the clicked face (against -> cell) is EAST.
        PredictedWorld world = world(cells().set(-1, 0, 0, chest(Blocks.CHEST, Direction.NORTH, ChestType.SINGLE)));
        BlockState candidate = chest(Blocks.CHEST, Direction.SOUTH, ChestType.SINGLE);

        BlockState landed = PlacementFamilies.resolve(world, CELL, candidate, Direction.EAST,
                new Vec3(0.0D, 0.5D, 0.5D), true);

        assertNotNull(landed);
        // The candidate wanted SOUTH; the partner's NORTH wins, because vanilla assigns facing = partner.
        assertEquals(Direction.NORTH, landed.getValue(ChestBlock.FACING));
        // counterClockWise(NORTH) == WEST == clickedFace.getOpposite() -> RIGHT.
        assertEquals(ChestType.RIGHT, landed.getValue(ChestBlock.TYPE));

        // The same click NOT sneaking takes the ordinary path: the candidate keeps its own SOUTH facing, and the
        // neighbour to the west is then counter-clockwise of SOUTH, i.e. the partner search looks east and west of
        // SOUTH instead. Pinned so the two paths cannot be collapsed into one.
        BlockState standing = PlacementFamilies.resolve(world, CELL, candidate, Direction.EAST,
                new Vec3(0.0D, 0.5D, 0.5D), false);
        assertNotNull(standing);
        assertEquals(Direction.SOUTH, standing.getValue(ChestBlock.FACING));
        assertEquals(ChestType.SINGLE, standing.getValue(ChestBlock.TYPE));
    }

    // -------------------------------------------------------------------------------------- standing or wall

    /**
     * The variant is decided by the clicked face, because {@code getNearestLookingDirections} moves
     * {@code clickedFace.getOpposite()} to the front of the array whenever the clicked block is not replaceable —
     * which is always, for a builder that clicks solid neighbours.
     */
    @Test
    public void theClickedFaceDecidesStandingVersusWall() {
        BlockState standing = Blocks.OAK_SIGN.defaultBlockState();
        BlockState wall = Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);

        // Standing form: click the TOP face of the block below.
        assertSame(standing, PlacementFamilies.standingOrWall(standing, Direction.UP, true));
        assertNull(PlacementFamilies.standingOrWall(standing, Direction.UP, false));
        // Clicking the bottom face of the block above is skipped by vanilla before anything is tested: a sign does not
        // hang from a ceiling. No solid neighbour rescues it.
        assertNull(PlacementFamilies.standingOrWall(standing, Direction.DOWN, true));
        // A horizontal click yields the WALL block, so it cannot produce the standing form.
        assertNull(PlacementFamilies.standingOrWall(standing, Direction.NORTH, true));

        // Wall form facing NORTH hangs on the block to its SOUTH, so the click is the SOUTH neighbour's NORTH face.
        assertSame(wall, PlacementFamilies.standingOrWall(wall, Direction.NORTH, true));
        assertNull(PlacementFamilies.standingOrWall(wall, Direction.NORTH, false));
        // Every other horizontal face produces a wall sign with a DIFFERENT facing, which is a wrong cell, not a
        // near miss.
        assertNull(PlacementFamilies.standingOrWall(wall, Direction.SOUTH, true));
        assertNull(PlacementFamilies.standingOrWall(wall, Direction.EAST, true));
        // And the floor click produces the standing form, not this.
        assertNull(PlacementFamilies.standingOrWall(wall, Direction.UP, true));
    }

    @Test
    public void torchesBannersAndSkullsFollowTheSameRule() {
        BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        assertSame(wallTorch, PlacementFamilies.standingOrWall(wallTorch, Direction.EAST, true));
        assertNull(PlacementFamilies.standingOrWall(wallTorch, Direction.WEST, true));

        BlockState wallBanner = Blocks.WHITE_WALL_BANNER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        assertSame(wallBanner, PlacementFamilies.standingOrWall(wallBanner, Direction.SOUTH, true));

        BlockState wallSkull = Blocks.SKELETON_WALL_SKULL.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        assertSame(wallSkull, PlacementFamilies.standingOrWall(wallSkull, Direction.WEST, true));

        assertSame(Blocks.TORCH.defaultBlockState(),
                PlacementFamilies.standingOrWall(Blocks.TORCH.defaultBlockState(), Direction.UP, true));
    }

    /** Hanging signs are the same item family with the attachment inverted: {@code HangingSignItem} passes
     *  {@code Direction.UP} where {@code SignItem} passes {@code DOWN}. */
    @Test
    public void hangingSignsAttachUpwardsAndTheWallVariantIsRefused() {
        BlockState ceiling = Blocks.OAK_HANGING_SIGN.defaultBlockState();
        assertEquals(Direction.UP, PlacementFamilies.attachmentDirection(ceiling));
        assertSame(ceiling, PlacementFamilies.standingOrWall(ceiling, Direction.DOWN, true));
        assertNull(PlacementFamilies.standingOrWall(ceiling, Direction.DOWN, false));
        // The floor click is the one vanilla skips for this item.
        assertNull(PlacementFamilies.standingOrWall(ceiling, Direction.UP, true));

        // The wall hanging sign spans BETWEEN two blocks perpendicular to its facing, so the clicked face does not
        // determine it. Refused outright rather than guessed -- a refusal is a named blocker, a guess is a wrong cell.
        BlockState wallHanging = Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        for (Direction face : Direction.values()) {
            assertNull(face.toString(), PlacementFamilies.standingOrWall(wallHanging, face, true));
        }

        assertEquals(Direction.DOWN, PlacementFamilies.attachmentDirection(Blocks.OAK_SIGN.defaultBlockState()));
        assertEquals(Direction.DOWN, PlacementFamilies.attachmentDirection(Blocks.WALL_TORCH.defaultBlockState()));
    }

    @Test
    public void wallFormIsDerivedFromTheItemNotAListOfClasses() {
        assertTrue(PlacementFamilies.isWallForm(Blocks.OAK_WALL_SIGN.defaultBlockState()));
        assertTrue(PlacementFamilies.isWallForm(Blocks.WALL_TORCH.defaultBlockState()));
        assertTrue(PlacementFamilies.isWallForm(Blocks.WHITE_WALL_BANNER.defaultBlockState()));
        assertTrue(PlacementFamilies.isWallForm(Blocks.SKELETON_WALL_SKULL.defaultBlockState()));
        assertTrue(PlacementFamilies.isWallForm(Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState()));

        assertFalse(PlacementFamilies.isWallForm(Blocks.OAK_SIGN.defaultBlockState()));
        assertFalse(PlacementFamilies.isWallForm(Blocks.TORCH.defaultBlockState()));
        assertFalse(PlacementFamilies.isWallForm(Blocks.OAK_HANGING_SIGN.defaultBlockState()));
        assertFalse(PlacementFamilies.isWallForm(Blocks.STONE.defaultBlockState()));
    }

    /**
     * The confirmation the plan asked for: {@code PlacementGeometry.requiredSupportDirection} already names the
     * neighbour to click for every wall member of this family, and it is exactly the face this rule accepts. Two
     * modules deriving the same direction two ways is only safe while they agree, so the agreement is asserted.
     */
    @Test
    public void requiredSupportDirectionNamesTheOneFaceThisRuleAccepts() {
        for (BlockState wall : new BlockState[] {
                Blocks.OAK_WALL_SIGN.defaultBlockState(), Blocks.WALL_TORCH.defaultBlockState(),
                Blocks.WHITE_WALL_BANNER.defaultBlockState(), Blocks.SKELETON_WALL_SKULL.defaultBlockState() }) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockState state = wall.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
                Direction support = PlacementGeometry.requiredSupportDirection(state);
                assertNotNull(state.toString(), support);
                assertSame(state.toString(), state,
                        PlacementFamilies.standingOrWall(state, support.getOpposite(), true));
            }
        }
    }

    /** The world-backed form: the support cell is the block that was clicked, i.e. {@code cell + face.getOpposite()}. */
    @Test
    public void predictStandingOrWallReadsTheClickedBlockAsTheSupport() {
        BlockState standing = Blocks.OAK_SIGN.defaultBlockState();
        assertSame(standing, PlacementFamilies.predictStandingOrWall(
                world(cells().set(0, -1, 0, Blocks.STONE)), CELL, standing, Direction.UP));
        assertNull(PlacementFamilies.predictStandingOrWall(world(cells()), CELL, standing, Direction.UP));

        BlockState wall = Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        assertSame(wall, PlacementFamilies.predictStandingOrWall(
                world(cells().set(0, 0, 1, Blocks.STONE)), CELL, wall, Direction.NORTH));
        // Solid somewhere else is not solid where it is needed.
        assertNull(PlacementFamilies.predictStandingOrWall(
                world(cells().set(0, 0, -1, Blocks.STONE)), CELL, wall, Direction.NORTH));

        // Outside the family the method is a no-op, so resolve's dispatch stays the only place classification happens.
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, PlacementFamilies.predictStandingOrWall(world(cells()), CELL, stone, Direction.UP));
    }

    @Test
    public void familyResolutionCanTurnTheItemsStandingCandidateIntoTheDesiredWallVariant() {
        BlockState candidate = Blocks.PALE_OAK_SIGN.defaultBlockState();
        BlockState desired = Blocks.PALE_OAK_WALL_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        PredictedWorld world = world(cells().set(1, 0, 0, Blocks.STONE));

        assertSame(desired, PlacementFamilies.resolve(world, CELL, candidate, desired, Direction.WEST,
                new Vec3(1.0D, 0.5D, 0.5D), true));
    }

    @Test
    public void resolveLeavesOrdinaryBlocksExactlyAsTheyCame() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, PlacementFamilies.resolve(world(cells()), CELL, stone, Direction.UP,
                new Vec3(0.5D, 0.0D, 0.5D), false));

        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState();
        assertSame(stairs, PlacementFamilies.resolve(world(cells()), CELL, stairs, Direction.UP,
                new Vec3(0.5D, 0.0D, 0.5D), true));
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private static DoorHingeSide hinge(Direction facing, boolean leftLower, boolean leftUpper, boolean rightLower,
                                       boolean rightUpper, double hitX, double hitZ) {
        return PlacementFamilies.doorHinge(facing, leftLower, leftUpper, rightLower, rightUpper, false, false,
                hitX, hitZ);
    }

    private static BlockState chest(net.minecraft.world.level.block.Block block, Direction facing, ChestType type) {
        return block.defaultBlockState()
                .setValue(ChestBlock.FACING, facing)
                .setValue(ChestBlock.TYPE, type);
    }

    private static Cells cells() {
        return new Cells();
    }

    private static PredictedWorld world(Cells cells) {
        return PredictedWorld.capture(cells::at, (x, z) -> true, new Vec3i(-3, -3, -3), new Vec3i(3, 3, 3), 0);
    }

    /** A hand-built neighbourhood. Everything not set is air, which is what an empty schematic cell looks like. */
    private static final class Cells {

        private final Map<Long, BlockState> states = new HashMap<>();

        Cells set(int x, int y, int z, net.minecraft.world.level.block.Block block) {
            return this.set(x, y, z, block.defaultBlockState());
        }

        Cells set(int x, int y, int z, BlockState state) {
            this.states.put(BlockPos.asLong(x, y, z), state);
            return this;
        }

        BlockState at(int x, int y, int z) {
            return this.states.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }
    }
}
