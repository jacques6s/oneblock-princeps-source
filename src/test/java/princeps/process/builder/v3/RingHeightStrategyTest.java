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
import net.minecraft.world.item.Item;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.process.builder.bench.BenchSchematics;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The live RingBig failure as a deterministic planner regression.
 *
 * <p>A two-high completed perimeter is a locked room for a body still on the floor. The primary strategy is to climb
 * while the current layer still has openings; resume from an already sealed world exercises the planned-scaffold
 * fallback instead. Both are required: ordering prevents the trap, scaffold recovers a legitimate saved world.
 */
public class RingHeightStrategyTest {

    private static final int BOTTOM = BasaltDryRun.ORIGIN.getY();
    private static final int MIDDLE = BOTTOM + 1;
    private static final int TOP = BOTTOM + 2;

    @BeforeClass
    public static void bootstrapMinecraft() {
        BasaltDryRun.bootstrap();
    }

    @Test
    public void freshRingPromotesToTheUpperWorkSurfaceBeforeTheMiddleLayerCloses() {
        Fixture fixture = fixture(false);
        PlanReport report = plan(fixture, BasaltDryRun.botStart());

        assertEquals(PlanReport.Status.READY, report.status());
        assertEquals(120, report.plan().counts().cells());
        assertEquals(0, report.plan().counts().scaffoldBlocks());

        int firstElevated = Integer.MAX_VALUE;
        int lastMiddle = -1;
        BlockPos lastMiddleStance = null;
        for (int index = 0; index < report.plan().size(); index++) {
            BuildAction action = report.plan().action(index);
            if (action instanceof BuildAction.Place place && action.layer() == MIDDLE) {
                lastMiddle = index;
                lastMiddleStance = place.solution().stance();
                if (place.solution().stance().getY() == MIDDLE + 1) {
                    firstElevated = Math.min(firstElevated, index);
                }
            }
        }
        assertTrue("the planner must climb during the layer, not after sealing it",
                firstElevated < lastMiddle);
        assertNotNull(lastMiddleStance);
        assertEquals("the layer gate must leave the body on top of the completed middle ring",
                MIDDLE + 1, lastMiddleStance.getY());
        assertNeverDropsAfterPromotion(report.plan(), BOTTOM);
        assertNeverDropsAfterPromotion(report.plan(), MIDDLE);
        assertNeverDropsAfterPromotion(report.plan(), TOP);
    }

    @Test
    public void resumedSealedRingPlansOneOwnedAccessStepAndItsRemoval() {
        Fixture fixture = fixture(true);
        BlockPos trappedInside = new BlockPos(BasaltDryRun.ORIGIN.getX() + 9, BOTTOM,
                BasaltDryRun.ORIGIN.getZ() + 2);
        BlockPos firstTopStance = new BlockPos(BasaltDryRun.ORIGIN.getX() + 10, TOP,
                BasaltDryRun.ORIGIN.getZ() + 4);
        assertFalse("the fixture must really be a sealed two-high ring",
                ScaffoldPlanner.stanceReachableWithoutMutation(fixture.world(), trappedInside, firstTopStance));

        PlanReport report = plan(fixture, trappedInside);

        assertEquals(report.render(), PlanReport.Status.READY, report.status());
        assertEquals("the report still covers the whole schematic", 120, report.plan().counts().cells());
        assertEquals("only the top perimeter remains to place", 40, report.plan().counts().placements());
        assertEquals("one temporary stair is sufficient for a two-layer rise",
                1, report.plan().counts().scaffoldBlocks());

        int placed = -1;
        int served = -1;
        int removed = -1;
        BlockPos helper = null;
        BlockPos servedCell = null;
        BuildAction.RemoveScaffold cleanup = null;
        BuildAction.Place servedPlacement = null;
        int navigationHelpers = 0;
        int removals = 0;
        for (int index = 0; index < report.plan().size(); index++) {
            BuildAction action = report.plan().action(index);
            if (action instanceof BuildAction.PlaceScaffold scaffold) {
                navigationHelpers++;
                placed = index;
                helper = scaffold.cell();
                servedCell = scaffold.serves();
            } else if (action instanceof BuildAction.Place place
                    && servedCell != null && place.cell().equals(servedCell)) {
                served = index;
                servedPlacement = place;
            } else if (action instanceof BuildAction.RemoveScaffold removal) {
                removals++;
                removed = index;
                cleanup = removal;
                assertEquals("only the helper the planner owns may be removed", helper, removal.cell());
            } else if (action instanceof BuildAction.Break broken) {
                throw new AssertionError("resume must never break a schematic block: "
                        + BuildAction.describePos(broken.cell()));
            }
        }
        assertEquals("exactly one navigation helper is placed", 1, navigationHelpers);
        assertEquals("exactly that one helper is taken back", 1, removals);
        assertNotNull(servedCell);
        assertFalse("the temporary stair must not occupy a schematic cell",
                fixture.view().covers(helper));
        assertNotNull(cleanup);
        assertNotNull(servedPlacement);
        assertTrue("the owned helper must stand before the upper cleanup stance is visited",
                placed >= 0 && placed < removed);
        assertTrue("the temporary stair must be gone before the target click reuses its original world proof",
                removed < served);
        assertEquals("cleanup must leave the body on the target's upper walk component",
                servedPlacement.solution().stance().getY(), cleanup.stance().getY());
        for (int index = placed + 1; index < removed; index++) {
            assertFalse("no schematic placement may postpone navigation-helper cleanup",
                    report.plan().action(index) instanceof BuildAction.Place);
        }
    }

    private static Fixture fixture(boolean completeLowerLayers) {
        BenchSchematics.Scenario scenario = BenchSchematics.byName("ringbig");
        assertNotNull(scenario);

        PredictedWorld inventoryWorld = BasaltDryRun.world(scenario);
        SchematicView bare = BasaltDryRun.view(scenario, inventoryWorld,
                HotbarSchedule.InventorySnapshot.empty());
        Item helper = BasaltDryRun.throwaway(bare).orElseThrow();
        HotbarSchedule.InventorySnapshot inventory =
                BasaltDryRun.inventory(BasaltDryRun.materialsOf(bare), helper);

        PredictedWorld world = BasaltDryRun.world(scenario);
        SchematicView view = BasaltDryRun.view(scenario, world, inventory);
        if (completeLowerLayers) {
            for (int layer : List.of(BOTTOM, MIDDLE)) {
                for (BlockPos cell : view.primaryCellsInLayer(layer)) {
                    world.apply(cell, view.desired(cell));
                }
            }
        }
        return new Fixture(world, view, inventory, helper);
    }

    private static PlanReport plan(Fixture fixture, BlockPos start) {
        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT, fixture.helper(),
                HotbarSchedule.THROWAWAY_SLOT);
        return new OrderPlanner(oracle, scaffold).plan(fixture.view(), fixture.world(), settings,
                SolveBudget.DEFAULT, fixture.inventory(), start);
    }

    private static void assertNeverDropsAfterPromotion(BuildPlan plan, int layer) {
        boolean promoted = false;
        for (BuildAction action : plan.actions()) {
            if (!(action instanceof BuildAction.Place place) || action.layer() != layer) {
                continue;
            }
            int stanceY = place.solution().stance().getY();
            if (stanceY == layer + 1) {
                promoted = true;
            } else if (promoted) {
                throw new AssertionError("layer " + layer + " descended after promotion at "
                        + BuildAction.describePos(place.cell()) + ": stance y=" + stanceY);
            }
        }
        assertTrue("layer " + layer + " must acquire its upper work surface", promoted);
    }

    private record Fixture(PredictedWorld world, SchematicView view,
                           HotbarSchedule.InventorySnapshot inventory, Item helper) {
    }
}
