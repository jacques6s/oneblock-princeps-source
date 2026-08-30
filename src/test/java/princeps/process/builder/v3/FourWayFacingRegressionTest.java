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
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The four-way {@code HORIZONTAL_FACING}, which the planner used to guess rather than prove.
 *
 * <p>The failure this file exists for, reproduced byte for byte from the bench's {@code oriented} run
 * {@code 7cc3a62f}: action 3 wanted {@code oak_stairs[facing=north]} at {@code 69,-60,67}, and the plan put the bot
 * at {@code 69,-60,65} — two cells NORTH of the target — with yaw {@code 0.0}, which is SOUTH. Vanilla's
 * {@code StairBlock.getStateForPlacement} is {@code context.getHorizontalDirection()} with no {@code getOpposite},
 * so that click lands {@code facing=south}. The live gate refused it, correctly, and because planning is
 * deterministic three successive re-plans produced the same wrong stance byte for byte and the build stopped.
 *
 * <p>Two things were wrong and both are pinned here. {@code PlacementOracle.simulate} never derived a four-way
 * facing at all, and {@code adoptDeferredOrientation} then copied the wanted facing straight out of the schematic —
 * so {@code predicted.facing == desired.facing} held for every stance and the proof for that property was vacuous.
 * And both rankers scored only the AXIS: a stance two cells north of a {@code facing=north} stair and one two cells
 * south of it produced the same key, and the tiebreak took the lower coordinate every time.
 *
 * <p>The gate was never the problem and is not touched. A wrong orientation is the one failure this engine may not
 * produce; where the convention is unknown the honest outcome is a refusal at plan time, which
 * {@link #aBlockWhoseYawConventionIsUnknownIsRefusedRatherThanGuessed} pins.
 */
public class FourWayFacingRegressionTest {

    /** Real vanilla block states, headless. */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    /** The cell every solve below fills, with the ground one layer under it. */
    private static final BlockPos CELL = new BlockPos(0, 0, 0);

    // --------------------------------------------------------------- the convention table

    /**
     * Every entry read out of 26.1.2's own {@code getStateForPlacement} bytecode. The two halves look alike and behave
     * oppositely — a DOOR faces where you look and a TRAPDOOR faces away — so this is a transcription check, not a
     * design opinion, and it is the cheapest place for a mapping update to announce itself.
     */
    @Test
    public void theYawConventionTableMatchesTheBytecodeItWasReadFrom() {
        for (Block towards : List.of(Blocks.OAK_STAIRS, Blocks.OAK_DOOR, Blocks.RED_BED, Blocks.OAK_FENCE_GATE,
                Blocks.CAMPFIRE, Blocks.DECORATED_POT, Blocks.CALIBRATED_SCULK_SENSOR, Blocks.LEVER,
                Blocks.STONE_BUTTON, Blocks.GRINDSTONE, Blocks.OXIDIZED_CUT_COPPER_STAIRS, Blocks.COPPER_DOOR)) {
            assertSame(BuiltInRegistries.BLOCK.getKey(towards).toString(),
                    PlacementGeometry.YawFacing.TOWARDS_LOOK, PlacementGeometry.yawFacingConvention(towards));
        }
        for (Block away : List.of(Blocks.REPEATER, Blocks.COMPARATOR, Blocks.FURNACE, Blocks.BLAST_FURNACE,
                Blocks.SMOKER, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.OAK_TRAPDOOR,
                Blocks.COPPER_TRAPDOOR, Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.CARVED_PUMPKIN, Blocks.JACK_O_LANTERN,
                Blocks.END_PORTAL_FRAME, Blocks.STONECUTTER, Blocks.LOOM, Blocks.LECTERN,
                Blocks.CHISELED_BOOKSHELF, Blocks.VAULT, Blocks.BEE_NEST, Blocks.BEEHIVE)) {
            assertSame(BuiltInRegistries.BLOCK.getKey(away).toString(),
                    PlacementGeometry.YawFacing.AWAY_FROM_LOOK, PlacementGeometry.yawFacingConvention(away));
        }
        assertSame("AnvilBlock is getHorizontalDirection().getClockWise(), and the only block that is",
                PlacementGeometry.YawFacing.CLOCKWISE_FROM_LOOK,
                PlacementGeometry.yawFacingConvention(Blocks.ANVIL));

        // Deliberately absent. Each of these reads something other than a plain yaw -- the bell switches on the
        // clicked face, cocoa and the tripwire hook walk getNearestLookingDirections against the world, the ladder
        // takes the face it hangs on. Null here means "refused at plan time", which is the safe answer; adding a
        // verified entry is how one of them becomes buildable, not relaxing the gate.
        for (Block unknown : List.of(Blocks.BELL, Blocks.COCOA, Blocks.TRIPWIRE_HOOK, Blocks.LADDER,
                Blocks.PINK_PETALS, Blocks.TUBE_CORAL_WALL_FAN)) {
            assertNull(BuiltInRegistries.BLOCK.getKey(unknown).toString(),
                    PlacementGeometry.yawFacingConvention(unknown));
        }
    }

    /**
     * {@code yawLookDirection} and {@code yawFacingFor} must be exact inverses for every block and every facing.
     *
     * <p>They are used at opposite ends of the search — one ranks the stances, the other predicts what lands — and a
     * pair that disagreed by a sign would rank exactly the stances that then fail, which reads as "no stance works"
     * rather than as a bug in either.
     */
    @Test
    public void theLookAndTheFacingAreExactInversesForEveryKnownBlock() {
        for (Block block : List.of(Blocks.OAK_STAIRS, Blocks.OAK_DOOR, Blocks.RED_BED, Blocks.OAK_FENCE_GATE,
                Blocks.REPEATER, Blocks.COMPARATOR, Blocks.FURNACE, Blocks.CHEST, Blocks.OAK_TRAPDOOR,
                Blocks.ANVIL)) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockState wanted = block.defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, facing);
                Direction look = PlacementGeometry.yawLookDirection(wanted);
                assertNotNull(block + " " + facing, look);
                assertSame(block + " " + facing, facing, PlacementGeometry.yawFacingFor(block, look));
            }
        }
    }

    /** A wall lever's facing comes from the clicked FACE, so the yaw decides nothing and the ranker must not pretend
     *  it does — the one case where the same block class answers both ways. */
    @Test
    public void aWallMountedStateHasNoYawLookEvenWhenItsBlockClassOtherwiseDoes() {
        BlockState wallLever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties
                        .AttachFace.WALL)
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST);
        assertNull(PlacementGeometry.yawLookDirection(wallLever));

        BlockState floorLever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties
                        .AttachFace.FLOOR)
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST);
        assertSame(Direction.EAST, PlacementGeometry.yawLookDirection(floorLever));

        assertNull("a wall sign hangs where it is clicked, whatever the yaw",
                PlacementGeometry.yawLookDirection(Blocks.OAK_WALL_SIGN.defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST)));
    }

    // ------------------------------------------------------- action 3 of oriented, at the lowest level

    /**
     * The failing click itself: yaw 0.0 is SOUTH, and a stair placed by it faces SOUTH. The oracle must say so.
     *
     * <p>Before the fix {@code simulate} left the facing at the item's default and {@code adoptDeferredOrientation}
     * overwrote it with the schematic's, so this click "proved" {@code facing=north} — a state vanilla would never
     * have produced from it.
     */
    @Test
    public void theStairFromRun7cc3a62fLandsFacingSouthAndIsNotProvedAsNorth() {
        PredictedWorld world = ground();
        PlacementOracle oracle = oracle();
        ItemStack stairs = new ItemStack(Items.OAK_STAIRS, 64);
        BlockPos against = CELL.below();
        // The plan's own numbers, translated to this fixture's origin: click the top face of the block below the
        // cell, aiming at the far (south) edge of it, with the yaw the trace recorded.
        Vec3 aim = new Vec3(CELL.getX() + 0.5D, CELL.getY(), CELL.getZ() + 0.9D);
        Rotation southward = new Rotation(0.0F, 27.9F);

        BlockState landed = oracle.simulate(world, stairs, against, Direction.UP, aim, southward, true);
        assertNotNull(landed);
        assertSame("yaw 0 is Direction.SOUTH and StairBlock has no getOpposite",
                Direction.SOUTH, landed.getValue(StairBlock.FACING));
        assertSame("clicking the UP face forces the bottom half", Half.BOTTOM, landed.getValue(StairBlock.HALF));

        BlockState wantedNorth = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        assertNull("this click cannot produce facing=north and must not be accepted as if it could",
                oracle.predict(world, CELL, wantedNorth, stairs, against, Direction.UP, aim, southward));

        BlockState wantedSouth = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        assertNotNull("the same click is the right one for the stair it actually makes",
                oracle.predict(world, CELL, wantedSouth, stairs, against, Direction.UP, aim, southward));
    }

    /**
     * The whole search, for all four cardinals. Each solution's own quantised yaw must produce the facing the plan
     * claims — which is exactly what the executor's live gate re-derives, so a solve that passes this cannot be
     * refused by the gate for its orientation.
     */
    @Test
    public void everyCardinalStairIsSolvedFromTheSideThatActuallyProducesItsFacing() {
        PredictedWorld world = ground();
        PlacementOracle oracle = oracle();
        List<ItemStack> inventory = List.of(new ItemStack(Items.OAK_STAIRS, 64));

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState wanted = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
            PlacementOracle.Solve solve = oracle.solve(world, CELL, wanted, inventory, SolveBudget.DEFAULT);
            assertTrue(facing + ": " + solve.explain(), solve.solved());

            PlacementSolution best = solve.best().orElseThrow();
            assertSame(facing.toString(), facing, best.predicted().getValue(StairBlock.FACING));
            // The assertion the old engine failed: the yaw the click carries, run through vanilla's own rule.
            assertSame(facing + ": the yaw of the planned click must make this facing",
                    facing, Direction.fromYRot(best.rotation().getYaw()));
            assertTrue(facing + ": a stair faces where you look, so the feet stand on the far side of the cell "
                            + "(stance " + best.stance() + ")",
                    towardsCellAlong(best.stance(), facing));
        }
    }

    /**
     * The mirror image, and the confirmation: a repeater takes {@code getHorizontalDirection().getOpposite()}, so the
     * SAME geometry has to be read the other way round. This is the {@code facings} bench's action 33 — a
     * {@code facing=west} repeater whose plan looked west and would have landed east.
     */
    @Test
    public void aRepeaterIsSolvedFromTheSideVanillaThenFacesAwayFrom() {
        PredictedWorld world = ground();
        PlacementOracle oracle = oracle();
        List<ItemStack> inventory = List.of(new ItemStack(Items.REPEATER, 64));

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState wanted = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, facing);
            PlacementOracle.Solve solve = oracle.solve(world, CELL, wanted, inventory, SolveBudget.DEFAULT);
            assertTrue(facing + ": " + solve.explain(), solve.solved());

            PlacementSolution best = solve.best().orElseThrow();
            assertSame(facing.toString(), facing, best.predicted().getValue(RepeaterBlock.FACING));
            assertSame(facing + ": a repeater faces AWAY from the look, so the click's yaw is the opposite one",
                    facing.getOpposite(), Direction.fromYRot(best.rotation().getYaw()));
            assertTrue(facing + ": the feet stand on the side the repeater ends up pointing at (stance "
                            + best.stance() + ")",
                    towardsCellAlong(best.stance(), facing.getOpposite()));
        }
    }

    /**
     * A block whose convention this file does not know is REFUSED, not guessed.
     *
     * <p>{@code BellBlock.getStateForPlacement} switches between the yaw and the clicked face depending on which face
     * is hit, so no single rule here is true for it. The old engine copied the schematic's facing in and proved every
     * such cell, which is how a wrong block gets clicked; the honest outcome is a named blocker at plan time. The way
     * to make a bell buildable is to read its bytecode and add a verified entry to
     * {@code PlacementGeometry.yawFacingConvention} — never to relax this.
     */
    @Test
    public void aBlockWhoseYawConventionIsUnknownIsRefusedRatherThanGuessed() {
        PredictedWorld world = ground();
        PlacementOracle oracle = oracle();
        List<ItemStack> inventory = List.of(new ItemStack(Items.BELL, 64));
        BlockState wanted = Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, Direction.EAST);

        PlacementOracle.Solve solve = oracle.solve(world, CELL, wanted, inventory, SolveBudget.DEFAULT);

        assertFalse("an unmodelled facing must never be proved: " + solve.explain(), solve.solved());
        assertSame("and the report has to name why", PlacementOracle.Rejection.WRONG_STATE_WOULD_LAND,
                solve.dominantRejection());
    }

    // ------------------------------------------------------------------ helpers

    /** Solid ground below y=0 and open air above it — the {@code oriented} bench's own geometry. */
    private static PredictedWorld ground() {
        return PredictedWorld.capture(
                (x, y, z) -> y < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                (x, z) -> true, new Vec3i(-8, -4, -8), new Vec3i(8, 6, 8), 0, V3Settings.defaults());
    }

    private static PlacementOracle oracle() {
        return new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
    }

    /** Does a bot standing here look along {@code look} to reach the cell? True exactly when the offset from the cell
     *  to the feet runs AGAINST the look, which is the sign the old stance key threw away. */
    private static boolean towardsCellAlong(BlockPos stance, Direction look) {
        int along = (stance.getX() - CELL.getX()) * look.getStepX() + (stance.getZ() - CELL.getZ()) * look.getStepZ();
        return along < 0;
    }
}
