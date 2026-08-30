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
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The exact upper-surface fall from oriented run {@code 5c2dd1c5}.
 *
 * <p>Action 3 placed the south-facing stair at {@code 71,-60,67}. Vanilla's neighbour update immediately changed
 * the east-facing stair under the body at {@code 70,-60,67} from {@code straight} to {@code outer_right}. Action 4
 * had already been proved from stance {@code 70,-59,67}, at the exact double edge {@code 70.00,-59.00,68.00}.
 * FineApproach walked toward that point, left the remaining upper quarter of the stair, dropped onto the lower
 * half-step, and the pathfinder routed it back up forever.
 *
 * <p>The defect had two independent hiding places. {@link PredictedWorld#isStandable} is cell-granular, so a stair
 * counts even where its upper step is absent; and {@code VoxelShape.bounds()} spans the entire stair, so a bounds-only
 * test invents a top surface over the cut-out. These tests pin the actual component boxes, the four-cell footprint
 * of a body on a corner, the dynamic outer shape, and the exact rejected plan coordinate.
 */
public class PartialStairApproachRegressionTest {

    private static final BlockPos TARGET = new BlockPos(69, -60, 67);
    private static final BlockPos SAFE_TARGET = new BlockPos(70, -59, 66);
    private static final BlockPos AGAINST = TARGET.below();
    private static final BlockPos STANCE = new BlockPos(70, -59, 67);
    private static final BlockPos STAIR_UNDER_STANCE = STANCE.below();
    private static final BlockPos SHAPING_NEIGHBOUR = new BlockPos(71, -60, 67);

    /** The point written into 5c2dd1c5-proof.txt. */
    private static final Vec3 PLANNED_CORNER = new Vec3(70.00D, -59.00D, 68.00D);

    /** The last supported location before action 3's click changed the stair shape. */
    private static final Vec3 MEASURED_ON_STRAIGHT = new Vec3(70.944D, -59.00D, 67.076D);

    /** A genuine point on the one upper quarter left by facing=east, shape=outer_right. */
    private static final Vec3 SAFE_OUTER_QUARTER = new Vec3(70.80D, -59.00D, 67.80D);

    private static final Vec3 AIM = new Vec3(69.90D, -60.00D, 67.50D);
    private static final Vec3 RESHAPING_APPROACH = new Vec3(71.00D, -59.00D, 67.00D);
    private static final Vec3 RESHAPING_AIM = new Vec3(71.10D, -60.00D, 67.50D);

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
    public void theNeighbourShapeChangeRemovesTheMeasuredUpperSurfaceButKeepsItsSafeQuarter() {
        PlacementOracle oracle = oracle();
        PredictedWorld beforeNeighbour = world(eastStair(StairsShape.STRAIGHT), false);
        PredictedWorld rawPostNeighbour = world(eastStair(StairsShape.STRAIGHT), true);
        PredictedWorld livePostNeighbour = world(eastStair(StairsShape.OUTER_RIGHT), true);

        assertTrue("the straight east stair really supported the body before the adjacent click",
                oracle.approachSupported(beforeNeighbour, MEASURED_ON_STRAIGHT));
        assertEquals("the predicted overlay stores straight, but its collision read must derive vanilla's live shape",
                StairsShape.OUTER_RIGHT,
                PlacementOracle.effectiveStairsShape(rawPostNeighbour, STAIR_UNDER_STANCE,
                        rawPostNeighbour.get(STAIR_UNDER_STANCE)));
        assertFalse("the raw predicted straight state must already behave like the post-neighbour outer_right",
                oracle.approachSupported(rawPostNeighbour, MEASURED_ON_STRAIGHT));
        assertFalse("after the south stair lands, outer_right removes exactly the quarter the body occupied",
                oracle.approachSupported(livePostNeighbour, MEASURED_ON_STRAIGHT));
        assertTrue("partial support is not a blanket ban on upper stances: the surviving upper quarter stays valid",
                oracle.approachSupported(rawPostNeighbour, SAFE_OUTER_QUARTER));
    }

    @Test
    public void theExactDoubleEdgeFrom5c2dd1c5IsNeverAProvenApproach() {
        PlacementOracle oracle = oracle();
        PredictedWorld plannerShape = world(eastStair(StairsShape.STRAIGHT), true);
        PredictedWorld liveShape = world(eastStair(StairsShape.OUTER_RIGHT), true);

        // The stale predicted shape and the dynamically updated live shape must agree on the safety decision. That
        // makes this fix robust without relying on neighbour propagation in the planner's raw state overlay.
        assertFalse("the raw straight overlay, resolved with its new neighbour, has no top surface at this edge",
                oracle.approachSupported(plannerShape, PLANNED_CORNER));
        assertFalse("and the live outer_right shape is equally unsafe there",
                oracle.approachSupported(liveShape, PLANNED_CORNER));

        PlacementSolution oldPlan = measuredPlan();
        assertTrue("the old action must survive no legal arrival rung",
                Double.isNaN(oracle.provenApproachTolerance(plannerShape, oldPlan, SolveBudget.DEFAULT)));
        assertSame("the report names the floor defect instead of disguising it as an occlusion",
                PlacementOracle.Rejection.APPROACH_NOT_SUPPORTED,
                oracle.refusalFor(plannerShape, oldPlan, SolveBudget.DEFAULT));
    }

    @Test
    public void theClickThatReshapesItsOwnFloorIsRejectedBeforeItCanDropTheBody() {
        PlacementOracle oracle = oracle();
        PredictedWorld beforeClick = world(eastStair(StairsShape.STRAIGHT), false, false, true);
        PlacementSolution reshapingClick = reshapingPlan();

        assertTrue("before its own click the straight east stair carries the complete fine-approach envelope",
                PlacementOracle.approachEnvelopeSupported(beforeClick, PlayerPose.CROUCHED, STANCE,
                        RESHAPING_APPROACH, FineApproach.TIGHT_TOLERANCE));
        assertSame("the refusal must name the click that removes its own floor, not blame the following action",
                PlacementOracle.Rejection.POST_ACTION_NOT_SUPPORTED,
                oracle.refusalFor(beforeClick, reshapingClick, SolveBudget.DEFAULT));
        assertTrue("no arrival tolerance may turn an immediately falling click into a proved action",
                Double.isNaN(oracle.provenApproachTolerance(beforeClick, reshapingClick, SolveBudget.DEFAULT)));
    }

    @Test
    public void theRasterDropsUnsupportedPointsBeforeTheyCanOccupyItsTopN() {
        PlacementOracle oracle = oracle();
        PlacementOracle.Solve solve = oracle.solveFrom(
                world(eastStair(StairsShape.STRAIGHT)),
                TARGET,
                northStair(),
                List.of(new ItemStack(Items.OAK_STAIRS, 64)),
                SolveBudget.DEFAULT,
                STANCE,
                PlacementOracle.StanceFilter.ALL);

        assertTrue("the exact fixture must exercise the named raster rejection: " + solve.explain(),
                solve.rejections()[PlacementOracle.Rejection.APPROACH_NOT_SUPPORTED.ordinal()] > 0);
        assertTrue("an unsafe corner may never hide a lower-ranked safe point or escape as a solution",
                solve.solutions().stream().allMatch(solution ->
                        oracle.approachSupported(world(eastStair(StairsShape.STRAIGHT), true),
                                solution.approach())
                                && solution.approach().distanceToSqr(PLANNED_CORNER) > 1.0E-12D));
    }

    @Test
    public void aConnectedUpperSurfaceStillKeepsRealSolutionsInsteadOfBanningPartialBlocksByName() {
        PlacementOracle oracle = oracle();
        PlacementOracle.Solve solve = oracle.solveFrom(
                world(eastStair(StairsShape.STRAIGHT), true, true),
                SAFE_TARGET,
                Blocks.STONE.defaultBlockState(),
                List.of(new ItemStack(Items.STONE, 64)),
                SolveBudget.DEFAULT.exhaustive(),
                STANCE,
                PlacementOracle.StanceFilter.ALL);

        assertFalse("safe partial-surface approaches must survive when their complete envelope is carried",
                solve.solutions().isEmpty());
        assertTrue(solve.solutions().stream().allMatch(solution ->
                oracle.approachSupported(world(eastStair(StairsShape.STRAIGHT), true, true),
                        solution.approach())));
    }

    private static PlacementSolution measuredPlan() {
        BlockState desired = northStair();
        Rotation rotation = RotationUtils.calcRotationFromVec3d(
                PlayerPose.CROUCHED.eyeAt(PLANNED_CORNER), AIM, new Rotation(0.0F, 0.0F));
        return new PlacementSolution(TARGET, desired, STANCE, PLANNED_CORNER, AGAINST, Direction.UP, AIM,
                rotation, 0.40D, desired, Items.OAK_STAIRS);
    }

    /** Action 000004 from the oriented proof: its own south stair turns the east stair under its body into an outer. */
    private static PlacementSolution reshapingPlan() {
        BlockState desired = southStair();
        Rotation rotation = RotationUtils.calcRotationFromVec3d(
                PlayerPose.CROUCHED.eyeAt(RESHAPING_APPROACH), RESHAPING_AIM, new Rotation(0.0F, 0.0F));
        return new PlacementSolution(SHAPING_NEIGHBOUR, desired, STANCE, RESHAPING_APPROACH,
                SHAPING_NEIGHBOUR.below(), Direction.UP, RESHAPING_AIM, rotation, 0.40D,
                desired, Items.OAK_STAIRS);
    }

    private static BlockState northStair() {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    private static BlockState eastStair(StairsShape shape) {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, shape);
    }

    private static BlockState southStair() {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    /**
     * The exact built neighbourhood at action 4: a full foundation, the east stair whose state differs between the
     * planner and live world, and the south-facing neighbour that caused the outer-right update.
     */
    private static PredictedWorld world(BlockState stanceFloor) {
        return world(stanceFloor, true);
    }

    private static PredictedWorld world(BlockState stanceFloor, boolean withShapingNeighbour) {
        return world(stanceFloor, withShapingNeighbour, false);
    }

    private static PredictedWorld world(BlockState stanceFloor, boolean withShapingNeighbour,
                                        boolean withConnectedUpperSurface) {
        return world(stanceFloor, withShapingNeighbour, withConnectedUpperSurface, withConnectedUpperSurface);
    }

    private static PredictedWorld world(BlockState stanceFloor, boolean withShapingNeighbour,
                                        boolean withConnectedUpperSurface, boolean withWestSupport) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> {
                    if (y <= -61) {
                        return stone;
                    }
                    BlockPos here = new BlockPos(x, y, z);
                    if (here.equals(STAIR_UNDER_STANCE)) {
                        return stanceFloor;
                    }
                    if (withShapingNeighbour && here.equals(SHAPING_NEIGHBOUR)) {
                        return southStair();
                    }
                    if (withWestSupport && here.equals(TARGET)) {
                        return stone;
                    }
                    if (withConnectedUpperSurface && y == -60
                            && x >= 69 && x <= 71 && z >= 66 && z <= 68) {
                        return stone;
                    }
                    return air;
                },
                (x, z) -> true,
                new Vec3i(64, -63, 62),
                new Vec3i(76, -55, 73),
                0,
                V3Settings.defaults());
    }

    private static PlacementOracle oracle() {
        return new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
    }
}
