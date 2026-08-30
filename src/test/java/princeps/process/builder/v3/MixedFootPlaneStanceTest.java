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
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One stance cell, two foot planes — the geometry that ended basalt run {@code 701fed58} at 464 of 15 004 cells.
 *
 * <p>The run stopped on a sentence that reads like a contradiction between a green unit test and a live trace:
 *
 * <pre>
 * DIVERGENCE at action 0 — the path reached stance 91,-59,105 on physical feet plane -59.000,
 *                          but the proof requires -59.125
 * </pre>
 *
 * <p>{@link FootHeightTest} asserts that a body on soul sand rests an eighth of a block low, and it is right; the
 * trace says the body rested on the integer, and it is right too. Both, because a stance CELL does not have one foot
 * plane. Soul sand at {@code 91,-60,105} tops out at {@code -59.125}, blue ice next door at {@code 90,-60,105} tops
 * out at {@code -59.000}, and a body is 0.6 wide: while any part of the footprint still overlaps the ice the body
 * rests on the ice, and only once the whole footprint clears {@code x = 91.3} does it sink onto the soul sand.
 *
 * <p>Every one of the twelve ticks in the whole 699-file trace corpus in which the body's feet cell sat over soul
 * sand is one of those overlapping positions, and every one of them measured {@code y = -59.000}. The last of them,
 * {@code T 9387}, is the one the executor diverged on: {@code x = 91.298}, a footprint reaching {@code 90.998} —
 * two millimetres of blue ice, holding the body an eighth of a block up.
 *
 * <p>So neither side of the apparent contradiction is the defect. {@link PlacementOracle#footY} answers for the
 * planned point and answers correctly; the planner's arrival proof (below) never hands out a ball that straddles the
 * two planes; and the body's entry position simply is not in that ball yet. The defect is WHERE the executor asks:
 * {@code CellExecutor.approach} demanded the proven plane on the tick the body entered the cell, before the fine
 * approach — which exists precisely to walk the last fraction of a block — had taken a single step.
 *
 * <p>The measuring instrument here is vanilla's own {@link Shapes#collide}, the same call {@code Entity.collide}
 * makes, rather than a second implementation of it in the test.
 */
public class MixedFootPlaneStanceTest {

    /** {@code build-trace-701fed58.log}, {@code T 9380..9387}: the cell the body walked into. */
    private static final BlockPos STANCE = new BlockPos(91, -59, 105);

    /** {@code T 9380}: {@code feet=91,-59,105 on=Block{minecraft:soul_sand}}. */
    private static final BlockPos SOUL_SAND = new BlockPos(91, -60, 105);

    /** {@code T 9379}: {@code feet=90,-59,105 on=Block{minecraft:blue_ice}} — the neighbour that holds the body up. */
    private static final BlockPos BLUE_ICE = new BlockPos(90, -60, 105);

    /** {@code 701fed58.r4-plan.txt} action 0: {@code from 91,-59,105 @91.50,-59.13,105.50}. */
    private static final Vec3 PLANNED_APPROACH = new Vec3(91.5D, -59.125D, 105.5D);

    /** {@code T 9387}: the exact body position the executor called a divergence. */
    private static final Vec3 MEASURED_ENTRY = new Vec3(91.298D, -59.000D, 105.490D);

    /** {@code T 9387}: {@code look=1751.19} — the yaw is unbounded in the trace and in the client, and the movement
     *  frame is built from {@code Mth.sin}/{@code Mth.cos}, which do not care. */
    private static final float LIVE_YAW = 1751.19F;

    /** A cell the plan could be about; the click itself is not what this test is measuring. */
    private static final BlockPos CELL = new BlockPos(93, -60, 105);

    private static final BlockPos AGAINST = new BlockPos(93, -61, 105);

    private static final Vec3 AIM = new Vec3(93.5D, -60.0D, 105.5D);

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

    /**
     * The live trace's position, reproduced from the two blocks it names — and it lands on the integer, not an eighth
     * below it.
     */
    @Test
    public void theMeasuredEntryPositionRestsOnTheNeighbouringIceAndNotOnTheSoulSand() {
        assertEquals("two millimetres of the footprint still overlap the ice at x=91, which tops out at -59.000",
                -59.000D, restsAt(MEASURED_ENTRY.x, MEASURED_ENTRY.z), 1.0E-9D);
        assertTrue("and that is only true because the body is 0.6 wide: its west edge is west of x=91",
                MEASURED_ENTRY.x - PlayerPose.CROUCHED.halfWidth() < BLUE_ICE.getX() + 1.0D);
    }

    /** The point the plan actually chose, in the same world — an eighth of a block low, exactly as proven. */
    @Test
    public void thePlannedApproachPointRestsWhereTheProofSaysItDoes() {
        assertEquals("the whole footprint clears x=91.3, so nothing but soul sand is under it",
                -59.125D, restsAt(PLANNED_APPROACH.x, PLANNED_APPROACH.z), 1.0E-9D);
        assertEquals("and the oracle's per-stance derivation agrees, for that point",
                -59.125D, PlacementOracle.footY(world(), STANCE), 1.0E-9D);
    }

    /**
     * The two planes coexist inside one cell, and {@code footY} cannot see it — which is the whole defect in one
     * assertion.
     *
     * <p>{@link PlacementOracle#footY} takes a {@code BlockPos}. The question it is asked has an answer only for a
     * POINT, and here the two answers are 0.125 apart. Nothing about that makes the function wrong at the point the
     * planner uses it on; it makes the executor wrong for asking it about a body that is somewhere else in the cell.
     */
    @Test
    public void theStanceCellCarriesBothPlanesAtOnce() {
        double atEntry = restsAt(MEASURED_ENTRY.x, MEASURED_ENTRY.z);
        double atPlan = restsAt(PLANNED_APPROACH.x, PLANNED_APPROACH.z);

        assertEquals("one eighth of a block apart, in the same stance cell", 0.125D, atEntry - atPlan, 1.0E-9D);
        assertEquals("both positions are the same canonical pathing stance, which is why the executor accepted one"
                        + " and diverged on the other",
                Mth.floor(atEntry + 0.1251D), Mth.floor(atPlan + 0.1251D));
    }

    /**
     * The planner is not the defect: the ball it grants never straddles the two planes.
     *
     * <p>Sampled rather than argued — every point of the granted disk, rim and interior, is dropped through vanilla's
     * collider and has to land on the planned plane. If a future change to the tolerance ladder or to the rim proof
     * ever widened the ball past {@code x = 91.3}, this fails here instead of five hours into a basalt run.
     */
    @Test
    public void everyPointInTheGrantedArrivalBallRestsOnThePlannedPlane() {
        PredictedWorld world = world();
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
        double granted = oracle.provenApproachTolerance(world, solution(), SolveBudget.DEFAULT);

        assertFalse("the arrival proof has to grant this click SOME ball, or this test is measuring nothing",
                Double.isNaN(granted));
        for (int step = 0; step < 32; step++) {
            double angle = 2.0D * Math.PI * step / 32.0D;
            for (double fraction : new double[]{1.0D, 0.75D, 0.5D, 0.25D, 0.0D}) {
                double radius = granted * fraction;
                double x = PLANNED_APPROACH.x + Math.cos(angle) * radius;
                double z = PLANNED_APPROACH.z + Math.sin(angle) * radius;
                assertEquals(String.format("granted %.2f, angle %.1f, radius %.3f", granted,
                                Math.toDegrees(angle), radius),
                        PLANNED_APPROACH.y, restsAt(x, z), 1.0E-9D);
            }
        }
    }

    /**
     * And the position the executor diverged on is OUTSIDE that ball — so the plane it demanded there was never a
     * claim the plan had made.
     */
    @Test
    public void theDivergingEntryPositionWasNeverInsideTheBallTheProofIsAbout() {
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
        double granted = oracle.provenApproachTolerance(world(), solution(), SolveBudget.DEFAULT);

        assertFalse("T 9387 is 0.202 blocks from the planned point — outside the granted ball, which is why the fine"
                        + " approach still had walking to do when the plane check fired",
                FineApproach.arrived(MEASURED_ENTRY, PLANNED_APPROACH, granted));
    }

    /**
     * The same premise a second time, one guard further down: the step the fine approach wants to take is refused
     * because it is judged at the plane the body is LEAVING.
     *
     * <p>Taken through the real controller — {@link FineApproach#chooseKeys} at the trace's live yaw, then
     * {@link FineApproach#stepVector} — rather than a hand-picked point, because the claim is about the step the
     * executor would actually emit. That step is supported, lands exactly on the planned plane, and would have been
     * refused as "would leave its live support envelope".
     */
    @Test
    public void theStepTheFineApproachWantsIsRefusedByTheSameOnePlaneAssumption() {
        PredictedWorld world = world();
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
        FineApproach.Keys keys = FineApproach.chooseKeys(LIVE_YAW, MEASURED_ENTRY, PLANNED_APPROACH);
        Vec3 next = MEASURED_ENTRY.add(FineApproach.stepVector(LIVE_YAW, keys));

        assertTrue("the controller does want to move, so there is a step to judge", keys.any());
        assertEquals("and the body it moves lands on the planned plane, which is the whole point of the walk",
                PLANNED_APPROACH.y, restsAt(next.x, next.z), 1.0E-9D);
        assertFalse("but judged at the plane the body is standing on now, nothing holds it there",
                oracle.approachSupported(world, next));
        assertTrue("judged at the plane the plan proved, it is held — by the soul sand the walk is heading for",
                oracle.approachSupported(world, new Vec3(next.x, PLANNED_APPROACH.y, next.z)));
    }

    // ------------------------------------------------------------------------------------------------ instrument

    /**
     * Where a body of this pose comes to rest above the given horizontal position, by vanilla's own collision solver.
     *
     * <p>Dropped from one block above the stance floor and stopped by {@link Shapes#collide} — the identical call
     * {@code Entity.collide} makes for the Y axis — over every collision shape the swept box can touch. Writing the
     * "highest top under the footprint" rule out by hand instead would be a second implementation of the thing being
     * measured, and this codebase has already had two measurements that were themselves the error.
     */
    private static double restsAt(double x, double z) {
        PredictedWorld world = world();
        double from = STANCE.getY() + 1.0D;
        AABB body = PlayerPose.CROUCHED.bodyAt(new Vec3(x, from, z));
        double drop = -3.0D;
        AABB swept = body.expandTowards(0.0D, drop, 0.0D);
        List<VoxelShape> shapes = new ArrayList<>();
        for (int bx = Mth.floor(swept.minX); bx <= Mth.floor(swept.maxX); bx++) {
            for (int by = Mth.floor(swept.minY); by <= Mth.floor(swept.maxY); by++) {
                for (int bz = Mth.floor(swept.minZ); bz <= Mth.floor(swept.maxZ); bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
                    if (!shape.isEmpty()) {
                        shapes.add(shape.move(bx, by, bz));
                    }
                }
            }
        }
        return from + Shapes.collide(Direction.Axis.Y, body, shapes, drop);
    }

    private static PlacementSolution solution() {
        BlockState desired = Blocks.BLACK_STAINED_GLASS.defaultBlockState();
        Rotation rotation = RotationUtils.calcRotationFromVec3d(
                PlayerPose.CROUCHED.eyeAt(PLANNED_APPROACH), AIM, REFERENCE);
        return new PlacementSolution(CELL, desired, STANCE, PLANNED_APPROACH, AGAINST, Direction.UP, AIM,
                rotation, Double.POSITIVE_INFINITY, desired, Items.BLACK_STAINED_GLASS);
    }

    /** The two blocks the trace names, on the bench's superflat floor. */
    private static PredictedWorld world() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState soulSand = Blocks.SOUL_SAND.defaultBlockState();
        BlockState blueIce = Blocks.BLUE_ICE.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.equals(SOUL_SAND)) {
                        return soulSand;
                    }
                    if (pos.equals(BLUE_ICE)) {
                        return blueIce;
                    }
                    return y <= -61 ? stone : air;
                },
                (x, z) -> true, new Vec3i(85, -64, 99), new Vec3i(99, -54, 112), 0, V3Settings.defaults());
    }
}
