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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;
import princeps.api.utils.Rotation;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A placement can mutate more than the schematic cell it names.
 *
 * <p>The live doorway fixture deliberately masks only the lower door cell. Vanilla nevertheless creates its upper
 * half, while the old forward simulation asked {@code SchematicView} whether that upper cell existed before staging
 * it. It therefore froze later actions and rays through air that would be a door by execution time. These tests step
 * the real {@link OrderPlanner.PlannerState#commit} seam and keep the physical side effect independent of the mask.
 */
public class SecondaryPlacementSideEffectTest {

    private static final BlockPos PRIMARY = new BlockPos(0, 64, 0);

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * The exact doorway regression: after the lower-only action, an OUTLINE ray at head height must hit the physical
     * upper door instead of treating that unmasked cell as air.
     */
    @Test
    public void aSparseLowerDoorStagesItsUpperHalfBeforeTheNextRay() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        Fixture fixture = fixture(lower);
        BlockPos upper = PRIMARY.above();
        Vec3 beforeDoor = new Vec3(PRIMARY.getX() + 0.5D, upper.getY() + 0.5D, PRIMARY.getZ() - 1.0D);
        Vec3 behindDoor = new Vec3(PRIMARY.getX() + 0.5D, upper.getY() + 0.5D, PRIMARY.getZ() + 2.0D);

        assertFalse("the regression requires the upper cell to be absent from the sparse schematic",
                fixture.view().covers(upper));
        assertNull("the same head-height ray is open before the lower door placement",
                OutlineGeometry.clip(fixture.world(), beforeDoor, behindDoor).hit());

        commitPlacement(fixture, lower, Items.OAK_DOOR);

        BlockState expectedUpper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertEquals("Vanilla creates this state even though the schematic did not mention its cell",
                expectedUpper, fixture.world().get(upper));
        OutlineGeometry.Ray ray = OutlineGeometry.clip(fixture.world(), beforeDoor, behindDoor);
        assertTrue("the post-door ray must be evaluable", ray.evaluable());
        assertNotNull("the upper door must obstruct the post-placement ray", ray.hit());
        assertEquals("the next action now sees the upper door instead of proving a ray through it",
                upper, ray.hit().getBlockPos());
    }

    /** Beds use FOOT/HEAD rather than DOUBLE_BLOCK_HALF, but obey the same two-cell placement contract. */
    @Test
    public void aSparseBedFootStagesItsHeadAlongFacing() {
        BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.BED_PART, BedPart.FOOT);
        Fixture fixture = fixture(foot);
        BlockPos head = PRIMARY.east();

        assertFalse(fixture.view().covers(head));
        commitPlacement(fixture, foot, Items.RED_BED);

        assertEquals(foot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), fixture.world().get(head));
    }

    /** The shared lower/upper property also covers Vanilla's double-height plants without a block-name table. */
    @Test
    public void aSparseDoublePlantStagesItsUpperHalf() {
        BlockState lower = Blocks.SUNFLOWER.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        Fixture fixture = fixture(lower);

        commitPlacement(fixture, lower, Items.SUNFLOWER);

        assertEquals(lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                fixture.world().get(PRIMARY.above()));
    }

    private static void commitPlacement(Fixture fixture, BlockState landed, Item item) {
        BlockPos stance = PRIMARY.south();
        PlacementSolution solution = new PlacementSolution(
                PRIMARY,
                landed,
                stance,
                Vec3.atBottomCenterOf(stance),
                PRIMARY.below(),
                Direction.UP,
                new Vec3(PRIMARY.getX() + 0.5D, PRIMARY.getY(), PRIMARY.getZ() + 0.5D),
                new Rotation(0.0F, 0.0F),
                PlacementSolution.UNCONSTRAINED_MARGIN,
                landed,
                item);
        fixture.state().commit(fixture.world(), List.of(
                new BuildAction.Place(solution, false, BuildAction.UNASSIGNED_SLOT, PRIMARY.getY())));
    }

    private static Fixture fixture(BlockState desired) {
        ISchematic sparse = new ISchematic() {
            @Override
            public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                return x == 0 && y == 0 && z == 0;
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           List<BlockState> approxPlaceable) {
                return desired;
            }

            @Override
            public int widthX() {
                return 1;
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
        PredictedWorld world = PredictedWorld.capture(
                (x, y, z) -> Blocks.AIR.defaultBlockState(),
                (x, z) -> true,
                new Vec3i(PRIMARY.getX(), PRIMARY.getY(), PRIMARY.getZ()),
                new Vec3i(PRIMARY.getX(), PRIMARY.getY(), PRIMARY.getZ()),
                PredictedWorld.DEFAULT_MARGIN);
        SchematicView view = SchematicView.capture("sparse-secondary", sparse, PRIMARY, world, List.of());
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        OrderPlanner.PlannerState state = new OrderPlanner.PlannerState(
                oracle, view, settings, SolveBudget.DEFAULT, HotbarSchedule.InventorySnapshot.empty(),
                PRIMARY.offset(-2, 0, -2));
        return new Fixture(world, view, state);
    }

    private record Fixture(PredictedWorld world, SchematicView view, OrderPlanner.PlannerState state) {
    }
}
