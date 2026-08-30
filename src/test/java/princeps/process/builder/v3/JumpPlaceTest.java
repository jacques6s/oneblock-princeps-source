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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The placement a settled body cannot make, checked on the exact shape that blocked a basalt run.
 *
 * <p>{@code 90,-59,108} is a sticky piston at {@code facing=up}. The plan report for run {@code 66b8710c} says of it:
 * "no candidate stance is standable — evaluated 145 candidate stances, 96 not standable". Under the hard layer rule
 * that one cell held back its whole layer and the 12 248 cells above it, which is why the run finished at 940 of
 * 15 004 and reported itself complete.
 *
 * <p>The cell is not hard to reach. It is hard to LOOK AT: vanilla derives an upward facing from a downward look, and
 * in the layer being built there is nothing above to look down from. The body therefore stands in the cell, jumps,
 * and clicks the block below on the way up — pillaring, which every player does without thinking about it.
 */
public class JumpPlaceTest {

    /** The cell the basalt run blocked on, moved to the origin. */
    private static final BlockPos CELL = new BlockPos(0, 1, 0);

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

    /** The case itself: solid ground under the cell, open sky above, and a sticky piston that has to end up facing
     *  up. This is the placement that had no solution at all before. */
    @Test
    public void aStickyPistonFacingUpIsSolvableByJumping() {
        Optional<PlacementSolution> solved = oracle().solveJumpPlace(
                world(true), CELL, pistonUp(), Items.STICKY_PISTON);

        assertTrue("this is the placement the basalt run had 145 candidate stances and no answer for",
                solved.isPresent());
        PlacementSolution solution = solved.get();
        assertEquals("the body stands IN the cell it is about to fill, which is what no ordinary stance may do",
                CELL, solution.stance());
        assertEquals("the click goes into the block below", CELL.below(), solution.against());
        assertEquals("and onto its top face", Direction.UP, solution.face());
    }

    /**
     * The landed state is DERIVED and has to match, not assumed to match.
     *
     * <p>This is the assertion that separates the fix from a hopeful one. The orientation comes back out of the same
     * {@code simulate} every other placement in the engine is checked against; if a downward look produced anything
     * other than {@code facing=up}, the solution would not exist rather than build the wrong block.
     */
    @Test
    public void theOrientationIsDerivedFromTheDownwardLookAndNotAssumed() {
        PlacementSolution solution = oracle().solveJumpPlace(
                world(true), CELL, pistonUp(), Items.STICKY_PISTON).orElseThrow();

        assertEquals("the simulator, not the schematic, is what says this lands facing up",
                Direction.UP, solution.predicted().getValue(BlockStateProperties.FACING));
        assertEquals(pistonUp(), solution.predicted());
        assertTrue("the look must be steep enough for vanilla's nearest-looking-direction to answer DOWN",
                solution.rotation().getPitch() > 45.0F);
    }

    /** No ground under the cell means nothing to stand on and nothing to click: refused, not attempted. This is the
     *  case that still needs the other technique — stand on a neighbour and sneak to the edge. */
    @Test
    public void withNothingToStandOnItIsRefusedRatherThanAttempted() {
        assertFalse("a body cannot jump from thin air, and the plan must say so rather than emit the action",
                oracle().solveJumpPlace(world(false), CELL, pistonUp(), Items.STICKY_PISTON).isPresent());
    }

    /** A block whose facing does not come from a downward look is not this family and must not be routed here, even
     *  though the geometry would happily produce a solution. */
    @Test
    public void aBlockThatDoesNotWantADownwardLookIsNotThisFamily() {
        BlockState pistonNorth = Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH);

        assertFalse("only a cell whose proven look is DOWN belongs to the jump family",
                oracle().solveJumpPlace(world(true), CELL, pistonNorth, Items.STICKY_PISTON).isPresent());
    }

    /** A ceiling one block above the feet leaves no room for the arc, so the jump cannot clear the cell and the
     *  solution must not exist. Checked because the alternative is a body that jumps into a block and clicks from
     *  inside the cell it is filling. */
    @Test
    public void aCeilingThatBlocksTheArcRefusesTheSolution() {
        Optional<PlacementSolution> solved = oracle().solveJumpPlace(
                worldWithCeiling(), CELL, pistonUp(), Items.STICKY_PISTON);

        assertFalse("without head room the body never leaves the cell and the click would land inside itself",
                solved.isPresent());
    }

    private static BlockState pistonUp() {
        return Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
    }

    private static PlacementOracle oracle() {
        return new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
    }

    /** Solid floor at y=0, everything else open. {@code withFloor=false} takes the floor away. */
    private static PredictedWorld world(boolean withFloor) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> withFloor && y <= 0 ? stone : air,
                (x, z) -> true, new Vec3i(-4, -3, -4), new Vec3i(4, 6, 4), 0, V3Settings.defaults());
    }

    /** Floor at y=0 and a lid at y=2, one block above the feet: standable, but with nowhere to jump to. */
    private static PredictedWorld worldWithCeiling() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> y <= 0 || y == 2 ? stone : air,
                (x, z) -> true, new Vec3i(-4, -3, -4), new Vec3i(4, 6, 4), 0, V3Settings.defaults());
    }
}
