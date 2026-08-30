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

import princeps.api.schematic.ISchematic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A double chest cannot be placed in one click, and the planner used to ask for one 88 times.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code ChestBlock.getStateForPlacement} can only return {@code type=left} or {@code type=right} when a chest is
 * ALREADY standing beside the cell — {@code candidatePartnerFacing} requires the neighbour to be a chest of the same
 * block with {@code TYPE == SINGLE}. With an empty partner cell, every stance, every face, every aim, sneaking or not,
 * lands {@code type=single}. There is no click anywhere in the game that produces a lone {@code type=left} chest.
 *
 * <p>The planner nevertheless solved each half against the schematic's {@code left}/{@code right} directly, so the
 * simulation correctly predicted SINGLE, the acceptance test correctly refused it, and all 88 chest halves of
 * etz-basalt reported {@code WRONG_STATE_WOULD_LAND} forever. Both halves of that sentence are the engine working:
 * the family rule was right and the gate was right. What was wrong was the QUESTION.
 *
 * <h2>What changed, and what deliberately did not</h2>
 *
 * <p>The click gate is untouched. {@code predict}, {@code valid}, {@code sameBlockstate} and
 * {@code AUTO_RESOLVED_PROP_NAMES} are all exactly as they were — in particular {@code "type"} was NOT added to the
 * auto-resolved list, because that list is matched by property NAME and {@code "type"} is shared by {@code CHEST_TYPE},
 * {@code PISTON_TYPE} and {@code SLAB_TYPE}. Adding it would have un-enforced top/bottom on every slab in every
 * schematic, silently, forever. What changed is that the planner now asks for the state vanilla can actually produce
 * at that moment ({@link SchematicView#placementTarget}) and then simulates the pairing vanilla does for free
 * ({@code OrderPlanner.completeChestPair}).
 *
 * <p>{@link SchematicView#desired} keeps its meaning as the frozen schematic, so {@code satisfied},
 * {@code CellExecutor.schematicLayerViolation} and {@code PlannedBuilderProcess.observeServerBlockChange} all still
 * hold the build to the true {@code left}/{@code right}. A pair whose second half never lands still stops the next
 * layer with a named cell.
 */
public class ChestPairRegressionTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Vec3i ORIGIN = new Vec3i(0, 64, 0);

    /** The west half of the pair, and the one the schematic wants RIGHT. */
    private static final BlockPos WEST_HALF = new BlockPos(0, 64, 0);

    /** The east half, wanted LEFT. */
    private static final BlockPos EAST_HALF = new BlockPos(1, 64, 0);

    // ------------------------------------------------------------------------------------- the pure helpers

    /**
     * {@code ChestBlock.getConnectedDirection} verbatim: LEFT connects clockwise of its facing, RIGHT
     * counter-clockwise. The asymmetry is vanilla's and is the thing that is easy to get backwards — reversing it
     * produces two chests that both claim the same half, which the server resolves by leaving them both single: a
     * double chest that never opens as one, and nothing anywhere reports it.
     */
    @Test
    public void theConnectedDirectionIsClockwiseForLeftAndCounterClockwiseForRight() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos left = PlacementFamilies.chestPairPartner(chest(facing, ChestType.LEFT), WEST_HALF);
            BlockPos right = PlacementFamilies.chestPairPartner(chest(facing, ChestType.RIGHT), WEST_HALF);
            assertEquals(facing.toString(), WEST_HALF.relative(facing.getClockWise()), left);
            assertEquals(facing.toString(), WEST_HALF.relative(facing.getCounterClockWise()), right);
        }
        assertNull("a single chest has no partner cell",
                PlacementFamilies.chestPairPartner(chest(Direction.SOUTH, ChestType.SINGLE), WEST_HALF));
        assertNull("nor does a block that is not a chest at all",
                PlacementFamilies.chestPairPartner(Blocks.STONE.defaultBlockState(), WEST_HALF));
    }

    /**
     * The consistency rule, and the four etz-basalt corner cells it exists to catch.
     *
     * <p>Those four "pairs" point at each other, both claim the same half, and disagree on facing. Vanilla can never
     * produce them — {@code ChestBlock.updateShape} requires the two facings to be equal before it will pair anything.
     * They must stay blocked, and this is the rule that keeps them blocked.
     */
    @Test
    public void aPairIsConsistentOnlyWithMatchingFacingAndOppositeHalves() {
        assertTrue(PlacementFamilies.chestPairConsistent(
                chest(Direction.SOUTH, ChestType.RIGHT), chest(Direction.SOUTH, ChestType.LEFT)));
        assertTrue("and it is symmetric", PlacementFamilies.chestPairConsistent(
                chest(Direction.SOUTH, ChestType.LEFT), chest(Direction.SOUTH, ChestType.RIGHT)));

        assertFalse("both halves claiming the same side is the double chest that never opens as one",
                PlacementFamilies.chestPairConsistent(
                        chest(Direction.SOUTH, ChestType.LEFT), chest(Direction.SOUTH, ChestType.LEFT)));
        assertFalse("the etz-basalt corner shape: matching types, opposing facings",
                PlacementFamilies.chestPairConsistent(
                        chest(Direction.NORTH, ChestType.LEFT), chest(Direction.SOUTH, ChestType.LEFT)));
        assertFalse("facings must be equal, not opposite -- updateShape compares them with ==",
                PlacementFamilies.chestPairConsistent(
                        chest(Direction.NORTH, ChestType.RIGHT), chest(Direction.SOUTH, ChestType.LEFT)));
        assertFalse("a single is never half of a pair", PlacementFamilies.chestPairConsistent(
                chest(Direction.SOUTH, ChestType.SINGLE), chest(Direction.SOUTH, ChestType.LEFT)));
        assertFalse("chestCanConnectTo is state.is(this): a trapped chest never pairs with a plain one",
                PlacementFamilies.chestPairConsistent(
                        chest(Blocks.CHEST, Direction.SOUTH, ChestType.RIGHT),
                        chest(Blocks.TRAPPED_CHEST, Direction.SOUTH, ChestType.LEFT)));
    }

    /**
     * {@code ChestBlock.updateShape} as values — the conversion the GAME performs for free, which the forward
     * simulation has to reproduce because {@code PredictedWorld.apply} does no neighbour propagation.
     */
    @Test
    public void placingTheSecondHalfConvertsTheSingleChestBesideIt() {
        BlockState single = chest(Direction.SOUTH, ChestType.SINGLE);
        assertEquals("a LEFT landing beside a single makes that single the RIGHT half",
                chest(Direction.SOUTH, ChestType.RIGHT),
                PlacementFamilies.chestPairCompletion(chest(Direction.SOUTH, ChestType.LEFT), single));
        assertEquals(chest(Direction.SOUTH, ChestType.LEFT),
                PlacementFamilies.chestPairCompletion(chest(Direction.SOUTH, ChestType.RIGHT), single));

        assertNull("a neighbour that is already paired is not converted again -- that is what stops a third chest "
                        + "joining a double", PlacementFamilies.chestPairCompletion(
                                chest(Direction.SOUTH, ChestType.LEFT), chest(Direction.SOUTH, ChestType.RIGHT)));
        assertNull("facings must agree", PlacementFamilies.chestPairCompletion(
                chest(Direction.SOUTH, ChestType.LEFT), chest(Direction.NORTH, ChestType.SINGLE)));
        assertNull("a single landing converts nothing", PlacementFamilies.chestPairCompletion(single, single));
        assertNull("nor does a different block", PlacementFamilies.chestPairCompletion(
                chest(Blocks.TRAPPED_CHEST, Direction.SOUTH, ChestType.LEFT), single));
    }

    // ------------------------------------------------------------------------------- the planner's question

    /**
     * THE regression. Against an empty partner cell the target is {@code type=single}, which is what vanilla can
     * land; once the partner is standing it is the schematic's own half again.
     *
     * <p>Before the fix this method did not exist and the planner asked for {@code left} in both situations, so the
     * first half was unplaceable and the whole field deadlocked: the report's "cells that would unblock it" lines for
     * the etz-basalt chests were all OTHER chests.
     */
    @Test
    public void theFirstHalfIsSolvedForAsSingleAndTheSecondForItsRealHalf() {
        PredictedWorld world = emptyWorld();
        SchematicView view = pairView(world);

        assertEquals("the frozen schematic is untouched -- satisfied() still holds the build to this",
                chest(Direction.SOUTH, ChestType.RIGHT), view.desired(WEST_HALF));
        assertEquals(chest(Direction.SOUTH, ChestType.LEFT), view.desired(EAST_HALF));

        assertEquals("with nothing beside it, the only state vanilla can land is single",
                chest(Direction.SOUTH, ChestType.SINGLE), view.placementTarget(world, WEST_HALF));
        assertEquals(chest(Direction.SOUTH, ChestType.SINGLE), view.placementTarget(world, EAST_HALF));

        // Put the west half down as SINGLE, exactly as the plan would.
        world.apply(WEST_HALF, chest(Direction.SOUTH, ChestType.SINGLE));

        assertEquals("now that a chest stands beside it, the east half's real type IS producible",
                chest(Direction.SOUTH, ChestType.LEFT), view.placementTarget(world, EAST_HALF));
    }

    /**
     * The four corner cells stay blocked, by design, and this is the assertion that keeps them that way.
     *
     * <p>A named blocker always beats a wrong block. Downgrading these to {@code single} would make them placeable and
     * permanently unsatisfiable at the same time — the layer gate would never open and nothing would say why.
     */
    @Test
    public void anInconsistentSchematicPairIsNeverDowngradedAndStaysBlocked() {
        PredictedWorld world = emptyWorld();
        // The etz-basalt corner shape: two halves pointing at each other, both LEFT, facings opposed.
        SchematicView view = viewOf(world,
                Map.of(WEST_HALF, chest(Direction.NORTH, ChestType.LEFT),
                        EAST_HALF, chest(Direction.SOUTH, ChestType.LEFT)));

        assertEquals("no downgrade: vanilla cannot build this, so the planner must not pretend it can",
                view.desired(WEST_HALF), view.placementTarget(world, WEST_HALF));
        assertEquals(view.desired(EAST_HALF), view.placementTarget(world, EAST_HALF));
    }

    /**
     * The algebra closes in BOTH orders — either half may go down first.
     *
     * <p>Traced through {@code PlacementFamilies.chestType}, which is the transcription of
     * {@code ChestBlock.getChestType} the oracle actually consults. This is the proof that the two-step process
     * terminates in exactly the schematic's own states rather than in a mirrored pair, and it is checked for all four
     * facings because the clockwise/counter-clockwise asymmetry is where a sign error would hide.
     */
    @Test
    public void eitherHalfMayGoFirstAndBothOrdersLandTheSchematicsOwnStates() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState single = chest(facing, ChestType.SINGLE);

            // The two cells really are each other's partners -- the premise the rest of this loop rests on.
            assertEquals(facing.toString(),
                    WEST_HALF,
                    PlacementFamilies.chestPairPartner(chest(facing, ChestType.LEFT),
                            WEST_HALF.relative(facing.getCounterClockWise())));

            // Order A: the RIGHT half at WEST_HALF goes down first, as single. Then the LEFT half at `second` is
            // placed: its clockwise neighbour is WEST_HALF, which holds a single of the same facing -> LEFT.
            assertEquals("order A, " + facing, ChestType.LEFT,
                    PlacementFamilies.chestType(facing, single, air, Blocks.CHEST));

            // Order B: the LEFT half goes down first, as single. Then the RIGHT half at WEST_HALF sees it
            // counter-clockwise -> RIGHT.
            assertEquals("order B, " + facing, ChestType.RIGHT,
                    PlacementFamilies.chestType(facing, air, single, Blocks.CHEST));

            // And whichever landed second converts the first, so the pair ends in the schematic's own two states.
            assertEquals("order A completion, " + facing, chest(facing, ChestType.RIGHT),
                    PlacementFamilies.chestPairCompletion(chest(facing, ChestType.LEFT), single));
            assertEquals("order B completion, " + facing, chest(facing, ChestType.LEFT),
                    PlacementFamilies.chestPairCompletion(chest(facing, ChestType.RIGHT), single));
        }
    }

    /**
     * The acceptance rules were not relaxed to make any of this work, and this is the assertion that proves it.
     *
     * <p>If a future change adds {@code "type"} to {@code AUTO_RESOLVED_PROP_NAMES} to "fix chests", these fail — and
     * so does every slab in every schematic, silently, which is why the guard is here rather than left to a comment.
     */
    @Test
    public void theGateStillRefusesASingleWhereADoubleHalfIsWanted() {
        V3Settings settings = V3Settings.defaults();
        assertFalse("a lone single is NOT an acceptable stand-in for the finished left half",
                settings.valid(chest(Direction.SOUTH, ChestType.SINGLE),
                        chest(Direction.SOUTH, ChestType.LEFT), false));
        assertFalse("nor is the mirrored half",
                settings.valid(chest(Direction.SOUTH, ChestType.RIGHT),
                        chest(Direction.SOUTH, ChestType.LEFT), false));
        assertTrue("but the state the planner asks for on the first click matches itself exactly",
                settings.valid(chest(Direction.SOUTH, ChestType.SINGLE),
                        chest(Direction.SOUTH, ChestType.SINGLE), true));

        assertFalse("\"type\" must never join AUTO_RESOLVED_PROP_NAMES: it is shared with SLAB_TYPE, and adding it "
                        + "would un-enforce top/bottom on every slab ever placed",
                PlacementGeometry.AUTO_RESOLVED_PROP_NAMES.contains("type"));
        assertFalse("the same guard, stated where a slab would feel it: etz-basalt alone has 133 planned "
                        + "polished_blackstone_slab[type=bottom] lines and thousands more unplanned",
                settings.valid(
                        Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                        Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
                        false));
    }

    // ---------------------------------------------------------------------------------------------- fixture

    private static BlockState chest(Direction facing, ChestType type) {
        return chest(Blocks.CHEST, facing, type);
    }

    private static BlockState chest(Block block, Direction facing, ChestType type) {
        return block.defaultBlockState()
                .setValue(ChestBlock.FACING, facing)
                .setValue(ChestBlock.TYPE, type);
    }

    /** Air everywhere, every column loaded — a chest needs no support, so nothing here influences the answer. */
    private static PredictedWorld emptyWorld() {
        return PredictedWorld.capture((x, y, z) -> Blocks.AIR.defaultBlockState(), (x, z) -> true,
                ORIGIN, new Vec3i(ORIGIN.getX() + 1, ORIGIN.getY(), ORIGIN.getZ()), PredictedWorld.DEFAULT_MARGIN);
    }

    /** The consistent pair: {@code right} at the west cell, {@code left} at the east cell, both facing south. */
    private static SchematicView pairView(PredictedWorld world) {
        return viewOf(world, Map.of(WEST_HALF, chest(Direction.SOUTH, ChestType.RIGHT),
                EAST_HALF, chest(Direction.SOUTH, ChestType.LEFT)));
    }

    /** A 2x1x1 schematic whose two cells are whatever the caller says. */
    private static SchematicView viewOf(PredictedWorld world, Map<BlockPos, BlockState> cells) {
        Map<Long, BlockState> byKey = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : cells.entrySet()) {
            byKey.put(entry.getKey().asLong(), entry.getValue());
        }
        ISchematic schematic = new ISchematic() {

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                return byKey.getOrDefault(
                        BlockPos.asLong(ORIGIN.getX() + x, ORIGIN.getY() + y, ORIGIN.getZ() + z),
                        Blocks.AIR.defaultBlockState());
            }

            @Override
            public int widthX() {
                return 2;
            }

            @Override
            public int heightY() {
                return 1;
            }

            @Override
            public int lengthZ() {
                return 1;
            }
        };
        return SchematicView.capture("chest-pair", schematic, ORIGIN, world, List.of());
    }
}
