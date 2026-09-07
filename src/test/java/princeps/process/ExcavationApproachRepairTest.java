/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.Rotation;

import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static princeps.process.ExcavationApproachTest.*;

/** Replays actual E217/E223 repair-face admission from the frozen approach runs, without a client loop. */
public class ExcavationApproachRepairTest {
    private static final BetterBlockPos FEET = new BetterBlockPos(70, -54, 66);
    private static final BetterBlockPos HOLE = new BetterBlockPos(68, -54, 68);
    private static final BetterBlockPos ENTRY = HOLE.below(3);
    private static final ExcavationRepairPolicy.Bounds BOUNDS = new ExcavationRepairPolicy.Bounds(
            67, 73, -60, -55, 67, 73, -57, -55, false);

    @BeforeClass public static void bootstrap() throws Exception {
        ExcavationApproachTest.bootstrap();
        PlatformTraverseFixture.bootstrap();
    }

    @Test public void reachableEntryShellFaceMustNotTakeAimFromItsCommittedApproach() throws Exception {
        Fixture f = fixture();
        assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP, ExcavationRepairPolicy.repair(BOUNDS,
                HOLE.x, HOLE.y, HOLE.z, Blocks.AIR.defaultBlockState(), true, true));
        assertFalse("E217: a visible shell face cannot seal the future body cell before entry", f.mayAim(HOLE));
        assertTrue("deferral must happen before a repair face takes ownership",
                f.aim.heldFace(f.world.world, HOLE).isEmpty());
    }

    @Test public void passedRoofGapCannotStealControlsAtTheE223Transition() throws Exception {
        Fixture f = fixture();
        BetterBlockPos passedGap = new BetterBlockPos(70, -54, 67);
        f.world.player.pos = new Vec3(69.558, -54, 67.607);
        put(f.world.pathing, "current", executor(path(new BetterBlockPos(69, -54, 67),
                new BetterBlockPos(68, -54, 67), HOLE, HOLE.below(), ENTRY), f.approach.token()));
        assertFalse("E223: a passed dry roof gap cannot take aim/item control during initial travel",
                f.mayAim(passedGap, passedGap.below(), Direction.UP));
        assertTrue(f.aim.heldFace(f.world.world, passedGap).isEmpty());
    }

    @Test public void allDryShellMaintenanceYieldsButFloorRepairsRemainEligible() throws Exception {
        Fixture f = fixture();
        Object token = f.approach.token();
        put(f.world.pathing, "current", executor(path(FEET, FEET.south(), HOLE.below(), ENTRY), token));
        assertFalse("the hole is future HEAD clearance even when it is not a waypoint", f.mayAim(HOLE));
        assertFalse("an unrelated roof gap must also yield shared controls", f.mayAim(HOLE.east().south()));
        assertFalse("a missing support must still be bridgeable", f.world.builder.deferApproachShellRepair(
                ExcavationRepairPolicy.Kind.BRIDGE, Blocks.AIR.defaultBlockState()));
    }

    @Test public void sourceAndFlowingFluidRepairsAreNeverPostponedByTheDryGapRule() throws Exception {
        Fixture f = fixture();
        for (var kind : new ExcavationRepairPolicy.Kind[]{ExcavationRepairPolicy.Kind.INTERNAL_SOURCE,
                ExcavationRepairPolicy.Kind.SHELL_SOURCE}) {
            assertFalse(f.world.builder.deferApproachShellRepair(kind, Blocks.WATER.defaultBlockState()));
        }
        var flowing = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3);
        assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP, ExcavationRepairPolicy.repair(BOUNDS,
                HOLE.x, HOLE.y, HOLE.z, flowing, true, true));
        assertFalse("SHELL_GAP includes flow: kind alone is not sufficient", f.world.builder.deferApproachShellRepair(
                ExcavationRepairPolicy.Kind.SHELL_GAP, flowing));
    }

    @Test public void onlyArrivalAndWorkingCutsEndTheJourneyDeferral() throws Exception {
        Fixture f = fixture();
        assertFalse(f.mayAim(HOLE));
        put(f.world.pathing.getCurrent(), "pathPosition", 6);
        assertFalse("passed cells still yield until the whole initial journey ends", f.mayAim(HOLE));
        f = fixture();
        f.approach.observe(ENTRY);
        assertTrue("normal excavation must restore the entry shell", f.mayAim(HOLE));
        f = fixture();
        put(f.world.builder, "origin", new BetterBlockPos(67, -60, 67));
        put(f.world.builder, "schematic", new princeps.api.schematic.FillSchematic(7, 6, 7,
                Blocks.AIR.defaultBlockState()));
        f.world.builder.noteExcavationWorkCut(ENTRY);
        assertTrue("real in-selection work restores ordinary shell maintenance", f.mayAim(HOLE));
    }

    @Test public void recalculationRetainsPriorityOnlyForTheCommittedContext() throws Exception {
        Fixture f = fixture();
        put(f.world.pathing, "current", null);
        assertFalse("dry repair must not seize control between route segments", f.mayAim(HOLE));
        put(f.world.pathing, "context", null);
        assertTrue("no committed context means no approach control reservation", f.mayAim(HOLE));
        f = fixture();
        put(f.world.pathing, "current", executor(path(FEET, FEET.south()), new Object()));
        assertTrue("a foreign executor cannot borrow approach priority", f.mayAim(HOLE));
        f = fixture();
        f.approach.start();
        assertTrue("an uncommitted pending job cannot suppress shell maintenance", f.mayAim(HOLE));
    }

    @Test public void pauseForeignContextAndJobReplacementCannotBorrowTheOldRoute() throws Exception {
        Fixture f = fixture();
        put(f.world.builder, "paused", true);
        assertTrue(f.mayAim(HOLE));
        put(f.world.builder, "paused", false);
        f.approach.suspend();
        f.approach.commit(new GoalBlock(ENTRY), FEET);
        assertTrue("resume needs a freshly committed executor", f.mayAim(HOLE));
        f = fixture();
        put(f.world.cost, "approachToken", new Object());
        assertTrue("foreign context is not an access passage", f.mayAim(HOLE));
        f = fixture();
        f.approach.observeCommand(null);
        assertTrue("an owner/command replacement retires the reservation", f.mayAim(HOLE));
        f = fixture();
        f.approach.start();
        f.approach.commit(new GoalBlock(ENTRY), FEET);
        assertTrue("replacement does not inherit another job's path", f.mayAim(HOLE));
    }

    private static Fixture fixture() throws Exception {
        PlatformTraverseFixture f = new PlatformTraverseFixture();
        f.player.pos = new Vec3(70.458, -54, 66.7);
        put(f.player, "dimensions", EntityDimensions.scalable(0.6F, 1.8F));
        f.world.states.put(HOLE.west().asLong(), Blocks.STONE.defaultBlockState());
        put(f.builder, "excavating", true);
        var abortField = PlatformTraverseFixture.field(BuilderProcess.class, "abortPending");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object running = Enum.valueOf((Class) abortField.getType(), "RUNNING");
        abortField.set(f.builder, running);
        ExcavationApproach approach = new ExcavationApproach();
        approach.start();
        Object token = approach.commit(new GoalBlock(ENTRY), FEET);
        put(f.builder, "excavationApproach", approach);
        var aim = new ExcavationRepairAim();
        put(f.builder, "excavationRepairAim", aim);
        put(f.cost, "approachToken", token);
        put(f.pathing, "context", f.cost);
        put(f.pathing, "goal", approach.entry());
        put(f.pathing, "current", executor(path(FEET, FEET.south(), FEET.south(2),
                HOLE.east(), HOLE, HOLE.below(), HOLE.below(2), ENTRY), token));
        return new Fixture(f, approach, aim);
    }

    private record Fixture(PlatformTraverseFixture world, ExcavationApproach approach, ExcavationRepairAim aim) {
        boolean mayAim(BetterBlockPos cell) throws Exception {
            return mayAim(cell, cell.west(), Direction.EAST);
        }
        boolean mayAim(BetterBlockPos cell, BlockPos support, Direction side) throws Exception {
            world.world.states.put(support.asLong(), Blocks.STONE.defaultBlockState());
            var placement = new BuilderProcess.Placement(3, support, side,
                    new Rotation(0, 0), FEET.asLong(), cell, Blocks.COBBLESTONE.defaultBlockState());
            Class<?> repairClass = Class.forName("princeps.process.BuilderProcess$SnakeRepair");
            var constructor = repairClass.getDeclaredConstructor(BetterBlockPos.class, ExcavationRepairPolicy.Kind.class, int.class);
            constructor.setAccessible(true);
            Object repair = constructor.newInstance(cell, ExcavationRepairPolicy.Kind.SHELL_GAP, 0);
            Method method = BuilderProcess.class.getDeclaredMethod("excavationRepairFaceReady",
                    BuilderProcess.Placement.class, repairClass, ExcavationRepairPolicy.Bounds.class,
                    BuilderProcess.BuilderCalculationContext.class);
            method.setAccessible(true);
            return (boolean) method.invoke(world.builder, placement, repair, BOUNDS, world.cost);
        }
    }
}
