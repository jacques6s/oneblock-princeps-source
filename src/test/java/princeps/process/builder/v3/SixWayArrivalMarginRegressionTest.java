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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The exact six-way-facing arrival failure from facings run {@code fd3eac58}.
 *
 * <p>Action 43 proved a north-facing sticky piston from {@code 78.300,65.300} with a 0.30-block
 * arrival tolerance. The body legitimately stopped at {@code 78.479,65.510}, 0.276 blocks away.
 * Both rays hit the intended top face, but that offset moved the look through the 45-degree
 * SOUTH/WEST boundary: the planned look made a north-facing piston, the live look made an
 * east-facing one, and three deterministic re-plans stopped after 42 of 96 cells.
 *
 * <p>The arrival proof already checks the corresponding boundary for four-way
 * {@code HORIZONTAL_FACING}. A piston carries six-way {@code FACING}, so this test pins the
 * missing half: the look axis must remain dominant everywhere inside the tolerance the body
 * receives, not only at the exact planned point.
 */
public class SixWayArrivalMarginRegressionTest {

    private static final BlockPos CELL = new BlockPos(75, -59, 67);
    private static final BlockPos AGAINST = new BlockPos(75, -60, 67);
    private static final BlockPos STANCE = new BlockPos(78, -60, 65);
    private static final Vec3 APPROACH = new Vec3(78.30D, -60.0D, 65.30D);
    private static final Vec3 AIM = new Vec3(75.98D, -59.0D, 67.98D);
    private static final Vec3 MEASURED_LIVE = new Vec3(78.479D, -60.0D, 65.510D);
    private static final Rotation REFERENCE = new Rotation(0.0F, 0.0F);

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

    @Test
    public void fd3eac58CannotReceiveTheUnsafeThirtyCentimeterArrivalBall() {
        PlacementOracle oracle = oracle();
        PlacementSolution plan = solution();
        double granted = oracle.provenApproachTolerance(world(), plan, SolveBudget.DEFAULT);

        assertTrue("the measured body position was a legal 0.30 arrival",
                FineApproach.arrived(MEASURED_LIVE, APPROACH, 0.30D));
        assertEquals("the six-way facing boundary makes 0.30 unsafe; the next proven rung is 0.18",
                0.18D, granted, 0.0D);
        assertFalse("the executor must keep approaching instead of entering the gate at the measured wrong-facing point",
                FineApproach.arrived(MEASURED_LIVE, APPROACH, granted));

        BlockState landed = landedAt(oracle, world(), MEASURED_LIVE);
        assertNotNull(landed);
        assertSame("yaw 45.35 looks WEST, so a piston faces EAST instead of the planned NORTH",
                Direction.EAST, landed.getValue(BlockStateProperties.FACING));
    }

    @Test
    public void everyPointInsideTheGrantedBallKeepsTheProvenSixWayFacing() {
        PlacementOracle oracle = oracle();
        PredictedWorld world = world();
        double granted = oracle.provenApproachTolerance(world, solution(), SolveBudget.DEFAULT);
        assertEquals(0.18D, granted, 0.0D);

        for (int step = 0; step < 32; step++) {
            double angle = 2.0D * Math.PI * step / 32.0D;
            for (double fraction : new double[]{1.0D, 0.75D, 0.5D, 0.25D, 0.0D}) {
                double radius = granted * fraction;
                Vec3 feet = APPROACH.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
                BlockState landed = landedAt(oracle, world, feet);
                String where = String.format("angle %.1f radius %.3f",
                        Math.toDegrees(angle), radius);
                assertNotNull(where, landed);
                assertEquals(where, desired(), landed);
            }
        }
    }

    private static BlockState landedAt(PlacementOracle oracle, PredictedWorld world, Vec3 feet) {
        Vec3 eye = PlayerPose.CROUCHED.eyeAt(feet);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(eye, AIM, REFERENCE);
        return oracle.simulate(world, new ItemStack(Items.STICKY_PISTON, 64),
                AGAINST, Direction.UP, AIM, rotation, true);
    }

    private static PlacementSolution solution() {
        BlockState desired = desired();
        Rotation rotation = RotationUtils.calcRotationFromVec3d(
                PlayerPose.CROUCHED.eyeAt(APPROACH), AIM, REFERENCE);
        return new PlacementSolution(CELL, desired, STANCE, APPROACH, AGAINST, Direction.UP, AIM,
                rotation, 0.36D, desired, Items.STICKY_PISTON);
    }

    private static BlockState desired() {
        return Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH);
    }

    private static PredictedWorld world() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> y < -60 || new BlockPos(x, y, z).equals(AGAINST) ? stone : air,
                (x, z) -> true, new Vec3i(70, -63, 61), new Vec3i(83, -55, 73), 0,
                V3Settings.defaults());
    }

    private static PlacementOracle oracle() {
        return new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
    }
}
