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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.Rotation;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The slab half that is decided by nothing — basalt run {@code 5a29505e}, which stood down at 1317 of 15 004 cells:
 *
 * <pre>
 * WRONG_RESULT — wanted 82,-59,120 face north at 82.250,-58.500,120.000
 *                to land minecraft:polished_blackstone_slab[type=bottom,waterlogged=false],
 *                live ray hit 82,-59,120 face north at 82.250,-58.500,120.000,
 *                aim point 0.000 from the hit    (unchanged across 3 plans)
 * </pre>
 *
 * <p>Read that twice: the live ray hit the intended block, on the intended face, at the aim point, zero blocks away.
 * Every geometric thing the engine proves was right, and the gate refused the click — correctly — because the state
 * that would have landed was not the state that was proven.
 *
 * <p>Vanilla decides a side-clicked slab with one comparison, {@code clickLocation.y - clickedPos.getY() > 0.5}, and
 * {@link PlacementOracle#simulate} writes the same line. They agree, and that is the trap rather than the safeguard:
 * both call exactly {@code 0.5} BOTTOM, so a plan that aims at 0.5 proves BOTTOM and then lets the LIVE ray's last
 * few millimetres — the aim quantiser, the clip's own arithmetic — decide which half actually lands. In
 * {@code 5a29505e.r25}, 34 of the 61 side-face slab actions aim at exactly 0.5, because the sampler's vertical centre
 * IS the boundary.
 */
public class SlabHalfBoundaryRegressionTest {

    /** The cell the run died on, and its clicked neighbour. */
    private static final BlockPos CELL = new BlockPos(82, -59, 119);

    private static final BlockPos AGAINST = new BlockPos(82, -59, 120);

    /** {@code 5a29505e.r25-plan.txt} action 0: {@code aim 82.25,-58.50,120.00}. */
    private static final Vec3 BOUNDARY_AIM = new Vec3(82.25D, -58.5D, 120.0D);

    private static final Rotation LOOK = new Rotation(-74.1F, 34.9F);

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
     * The aim the plan committed to is a coin flip: one ulp decides the block.
     *
     * <p>This is the defect stated as a measurement rather than as an argument. The same click, at the aim and a
     * hair above it, lands two different blocks — and the live ray has no obligation to land on either side.
     */
    @Test
    public void oneUlpAboveTheProvenAimLandsTheOtherHalf() {
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
        PredictedWorld world = world();

        BlockState atBoundary = oracle.simulate(world, new ItemStack(Items.POLISHED_BLACKSTONE_SLAB, 64),
                AGAINST, Direction.NORTH, BOUNDARY_AIM, LOOK, true);
        BlockState justAbove = oracle.simulate(world, new ItemStack(Items.POLISHED_BLACKSTONE_SLAB, 64),
                AGAINST, Direction.NORTH, BOUNDARY_AIM.add(0.0D, 1.0E-9D, 0.0D), LOOK, true);

        assertNotNull(atBoundary);
        assertNotNull(justAbove);
        assertSame("exactly 0.5 is BOTTOM, in vanilla and here — which is what the plan proved",
                SlabType.BOTTOM, atBoundary.getValue(SlabBlock.TYPE));
        assertSame("and a nanometre higher is TOP, which is what the gate saw and refused",
                SlabType.TOP, justAbove.getValue(SlabBlock.TYPE));
    }

    /** So the planner must not offer that aim at all — the whole repair, at the seam that produced it. */
    @Test
    public void theOracleNoLongerOffersAnAimOnTheHalfBoundary() {
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
        PredictedWorld world = world();
        BlockState desired = Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);

        PlacementOracle.Solve solve = oracle.solve(world, CELL, desired,
                java.util.List.of(new ItemStack(Items.POLISHED_BLACKSTONE_SLAB, 64)), SolveBudget.DEFAULT);

        assertTrue("the cell has to stay solvable, or this refuses a defect by refusing the build",
                solve.best().isPresent());
        for (PlacementSolution solution : solve.solutions()) {
            if (solution.face().getAxis() == Direction.Axis.Y) {
                continue;
            }
            double height = solution.aimPoint().y - solution.cell().getY();
            assertTrue("a side-face slab aim at " + height + " of its cell is decided by float error, not by the plan",
                    Math.abs(height - 0.5D) > 1.0E-9D);
        }
    }

    /** Clicking the top or bottom face decides the half outright in both implementations, so nothing is refused
     *  there — and the guard must not quietly cost those placements. */
    @Test
    public void aTopFaceClickIsUntouchedBecauseItHasNoBoundaryToCross() {
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);
        BlockState landed = oracle.simulate(world(), new ItemStack(Items.POLISHED_BLACKSTONE_SLAB, 64),
                CELL.below(), Direction.UP, new Vec3(82.5D, -59.0D, 119.5D), LOOK, true);

        assertNotNull(landed);
        assertSame("clicked UP is BOTTOM with no comparison anywhere near it",
                SlabType.BOTTOM, landed.getValue(SlabBlock.TYPE));
    }

    /** Stone under the cell and its clicked neighbour, air elsewhere. */
    private static PredictedWorld world() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.equals(AGAINST)) {
                        return stone;
                    }
                    return y <= -60 ? stone : air;
                },
                (x, z) -> true, new Vec3i(74, -64, 111), new Vec3i(90, -54, 127), 0, V3Settings.defaults());
    }
}
