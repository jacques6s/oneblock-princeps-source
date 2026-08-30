/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder.v3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A previously exhausted upper stance can become useful after a neighbour lands.
 *
 * <p>The upper-stance queue is deliberately one-shot, while the predicted world is not. In this fixture A cannot
 * place C initially because C has no click face. Q can place the north-facing piston B from the same upper work
 * surface; B then becomes C's click face, making A useful. B's own upper stance is blocked, and D keeps two cells
 * pending, so neither a newly created stance nor the final-cell rescan can hide a missing whole-surface confirmation.
 */
public class HighSurfaceLateUnlockTest {

    private static final Vec3i ORIGIN = new Vec3i(0, -60, 0);
    private static final int LAYER = -59;

    private static final BlockPos START_SUPPORT = new BlockPos(0, LAYER, 4);
    private static final BlockPos LATE_STANCE_SUPPORT = new BlockPos(1, LAYER, 4);
    private static final BlockPos PISTON_STANCE_SUPPORT = new BlockPos(4, LAYER, 1);
    private static final BlockPos UNLOCKING_PISTON = new BlockPos(4, LAYER, 3);
    private static final BlockPos LATE_TARGET = new BlockPos(4, LAYER, 4);
    private static final BlockPos LOW_FALLBACK = new BlockPos(8, LAYER, 4);

    /** Prevents the piston itself from contributing a fresh upper stance after it lands. */
    private static final BlockPos PISTON_STANCE_HEAD_BLOCK = UNLOCKING_PISTON.above().above();

    /** Blocks Q only in the below-surface fixture, forcing the unlocking piston to be placed from the low floor. */
    private static final BlockPos UPPER_PISTON_STANCE_HEAD_BLOCK = PISTON_STANCE_SUPPORT.above().above();

    /** A natural one-block stair from the low floor to A's already-built upper work surface. */
    private static final BlockPos BELOW_SURFACE_STEP = new BlockPos(2, LAYER - 1, 4);

    @BeforeClass
    public static void bootstrapMinecraft() {
        BasaltDryRun.bootstrap();
    }

    @Test
    public void neighbourUnlocksAnOldUpperStanceBeforeThePlannerDescends() {
        HotbarSchedule.InventorySnapshot inventory = inventory();
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);

        PredictedWorld proofWorld = world();
        PlacementOracle.Solve before = oracle.solveFrom(proofWorld, LATE_TARGET, stone(), inventory.slots(),
                SolveBudget.DEFAULT, LATE_STANCE_SUPPORT.above(), PlacementOracle.StanceFilter.ALL);
        assertFalse("A must initially have nothing solid to click for C", before.best().isPresent());

        PlacementOracle.Solve piston = oracle.solveFrom(proofWorld, UNLOCKING_PISTON, northPiston(),
                inventory.slots(), SolveBudget.DEFAULT, PISTON_STANCE_SUPPORT.above(),
                PlacementOracle.StanceFilter.ALL);
        assertTrue("Q must be able to place the neighbour without leaving the upper work surface",
                piston.best().isPresent());

        proofWorld.apply(UNLOCKING_PISTON, northPiston());
        assertFalse("the head blocker makes B's newly created upper stance unusable",
                proofWorld.isStandable(UNLOCKING_PISTON.getX(), UNLOCKING_PISTON.getY() + 1,
                        UNLOCKING_PISTON.getZ()));

        PlacementOracle.Solve after = oracle.solveFrom(proofWorld, LATE_TARGET, stone(), inventory.slots(),
                SolveBudget.DEFAULT, LATE_STANCE_SUPPORT.above(), PlacementOracle.StanceFilter.ALL);
        PlacementSolution unlocked = after.best().orElseThrow();
        assertEquals("B is the click face that did not exist when A was first examined",
                UNLOCKING_PISTON, unlocked.against());
        assertEquals(LATE_STANCE_SUPPORT.above(), unlocked.stance());

        PredictedWorld planWorld = world();
        SchematicView view = SchematicView.capture("late-upper-stance", schematic(), ORIGIN, planWorld,
                BasaltDryRun.ledgerPlaceable(inventory));
        ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, Blocks.DIRT.asItem(),
                HotbarSchedule.THROWAWAY_SLOT);
        PlanReport report = new OrderPlanner(oracle, scaffold).plan(view, planWorld, settings, SolveBudget.DEFAULT,
                inventory, START_SUPPORT.above());

        assertEquals(report.render(), PlanReport.Status.READY, report.status());
        assertEquals(6, report.plan().counts().cells());
        assertEquals(3, report.plan().counts().placements());
        assertEquals(0, report.plan().counts().scaffoldBlocks());

        List<BuildAction.Place> placements = new ArrayList<>();
        for (BuildAction action : report.plan().actions()) {
            if (action instanceof BuildAction.Place place) {
                placements.add(place);
            }
        }
        assertEquals(3, placements.size());

        assertEquals(UNLOCKING_PISTON, placements.get(0).cell());
        assertEquals(PISTON_STANCE_SUPPORT.above(), placements.get(0).solution().stance());

        assertEquals("the old A stance must be reconsidered before any low solution is accepted",
                LATE_TARGET, placements.get(1).cell());
        assertEquals(LATE_STANCE_SUPPORT.above(), placements.get(1).solution().stance());

        assertEquals(LOW_FALLBACK, placements.get(2).cell());
        assertTrue("descending is allowed only after the upper surface has no remaining work",
                placements.get(2).solution().stance().getY() < LAYER + 1);
    }

    @Test
    public void lowPlacementReopensAnOldUpperStanceBeforeAnySecondLowPlacement() {
        HotbarSchedule.InventorySnapshot inventory = inventory();
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        PredictedWorld planWorld = worldBelowSurface();
        SchematicView view = SchematicView.capture("late-promotion-from-below", schematic(), ORIGIN, planWorld,
                BasaltDryRun.ledgerPlaceable(inventory));
        ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, Blocks.DIRT.asItem(),
                HotbarSchedule.THROWAWAY_SLOT);
        BlockPos lowStart = new BlockPos(4, LAYER - 1, 2);

        PlanReport report = new OrderPlanner(oracle, scaffold).plan(view, planWorld, settings, SolveBudget.DEFAULT,
                inventory, lowStart);

        assertEquals(report.render(), PlanReport.Status.READY, report.status());
        List<BuildAction.Place> placements = new ArrayList<>();
        for (BuildAction action : report.plan().actions()) {
            if (action instanceof BuildAction.Place place) {
                placements.add(place);
            }
        }
        assertEquals(3, placements.size());

        int unlocking = placementIndex(placements, UNLOCKING_PISTON);
        int promoted = placementIndex(placements, LATE_TARGET);
        assertTrue("the fixture must contain the low placement that creates the missing click face", unlocking >= 0);
        assertTrue("Q is blocked, so the unlocking placement really must be made from below",
                placements.get(unlocking).solution().stance().getY() < LAYER + 1);
        assertEquals("once that low placement unlocks A, promotion must win before any further low placement",
                unlocking + 1, promoted);
        assertEquals("that low placement unlocks A; promotion must win before another low candidate",
                LATE_STANCE_SUPPORT.above(), placements.get(promoted).solution().stance());
    }

    private static int placementIndex(List<BuildAction.Place> placements, BlockPos cell) {
        for (int index = 0; index < placements.size(); index++) {
            if (placements.get(index).cell().equals(cell)) {
                return index;
            }
        }
        return -1;
    }

    private static ISchematic schematic() {
        return new ISchematic() {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                if (y != 1) {
                    return Blocks.AIR.defaultBlockState();
                }
                if (x == 4 && z == 3) {
                    return northPiston();
                }
                if ((x == 0 && z == 4) || (x == 1 && z == 4) || (x == 4 && z == 1)
                        || (x == 4 && z == 4) || (x == 8 && z == 4)) {
                    return stone();
                }
                return Blocks.AIR.defaultBlockState();
            }

            @Override
            public int widthX() {
                return 9;
            }

            @Override
            public int heightY() {
                return 2;
            }

            @Override
            public int lengthZ() {
                return 5;
            }
        };
    }

    private static PredictedWorld world() {
        Vec3i max = new Vec3i(8, LAYER, 4);
        return PredictedWorld.capture((x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            if (y == ORIGIN.getY() - 1
                    || pos.equals(UNLOCKING_PISTON.below())
                    || pos.equals(LOW_FALLBACK.below())
                    || pos.equals(START_SUPPORT)
                    || pos.equals(LATE_STANCE_SUPPORT)
                    || pos.equals(PISTON_STANCE_SUPPORT)
                    || upperWalkway(pos)
                    || pos.equals(BELOW_SURFACE_STEP)
                    || pos.equals(PISTON_STANCE_HEAD_BLOCK)) {
                return stone();
            }
            return Blocks.AIR.defaultBlockState();
        }, (x, z) -> true, ORIGIN, max, PredictedWorld.DEFAULT_MARGIN);
    }

    private static PredictedWorld worldBelowSurface() {
        Vec3i max = new Vec3i(8, LAYER, 4);
        return PredictedWorld.capture((x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            if (y == ORIGIN.getY() - 1
                    || pos.equals(UNLOCKING_PISTON.below())
                    || pos.equals(LOW_FALLBACK.below())
                    || pos.equals(START_SUPPORT)
                    || pos.equals(LATE_STANCE_SUPPORT)
                    || pos.equals(PISTON_STANCE_SUPPORT)
                    || upperWalkway(pos)
                    || pos.equals(PISTON_STANCE_HEAD_BLOCK)
                    || pos.equals(UPPER_PISTON_STANCE_HEAD_BLOCK)
                    || pos.equals(BELOW_SURFACE_STEP)) {
                return stone();
            }
            return Blocks.AIR.defaultBlockState();
        }, (x, z) -> true, ORIGIN, max, PredictedWorld.DEFAULT_MARGIN);
    }

    /**
     * A real connected upper work surface. Without these blocks the old fixture consisted of isolated stance islands
     * and silently relied on the planner teleporting between them; the route proof correctly refuses that fiction.
     */
    private static boolean upperWalkway(BlockPos pos) {
        if (pos.getY() != LAYER) {
            return false;
        }
        return pos.getX() == 1 && pos.getZ() >= 1 && pos.getZ() <= 4
                || pos.getZ() == 1 && pos.getX() >= 1 && pos.getX() <= 4;
    }

    private static HotbarSchedule.InventorySnapshot inventory() {
        List<Item> materials = List.of(Blocks.STONE.asItem(), Blocks.PISTON.asItem());
        return BasaltDryRun.inventory(materials, Blocks.DIRT.asItem());
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState northPiston() {
        return Blocks.PISTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.NORTH);
    }
}
