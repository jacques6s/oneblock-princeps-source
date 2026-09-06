/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.schematic.MaskSchematic;
import princeps.process.builder.BuilderProgressWatch;

import java.util.List;

import static org.junit.Assert.*;
import static princeps.api.pathing.movement.ActionCosts.COST_INF;
import static princeps.process.BuilderModelRestorationTest.*;

/** Actual full-model policy/BCC and final Input/BBH paths. No coordinates from the live fixture. */
public class BuilderFutureSupportTest {
    private static final BlockPos DOOR = ORIGIN.offset(1, 0, 1), FLOOR = DOOR.below();

    @BeforeClass public static void bootstrap() throws Exception { BuilderModelRestorationTest.bootstrap(); }

    @Test public void missingLowestModelDoorProtectsItsExistingOutsideFoundationAtNavigationCost() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR);
        assertTrue(f.world.getBlockState(DOOR).isAir());
        assertFalse(f.active.inSchematic(1, 0, 1, Blocks.AIR.defaultBlockState()));
        assertEquals(COST_INF, f.cost(FLOOR), 0);
        assertEquals(DOOR, policy(f).removal(FLOOR).dependent());
        assertEquals(0, f.world.writes);
    }

    @Test public void missingDoorProtectsItsFoundationAtTheActualCrosshairDamageBoundary() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR);
        f.tickMining(); assertEquals(0, f.damage.get()); assertEquals(0, f.world.writes);
    }

    @Test public void absentWallSignRetainsItsExternalHorizontalAnchor() throws Exception {
        BlockPos sign = ORIGIN.offset(0, 1, 1), anchor = sign.west();
        BlockState wanted = Blocks.PALE_OAK_WALL_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        Fixture f = future(wanted, sign, anchor);
        assertEquals(COST_INF, f.cost(anchor), 0); f.tickMining(); assertEquals(0, f.damage.get());
    }

    @Test public void absentFloorTorchUsesTheSameVanillaSurvivalContract() throws Exception {
        Fixture f = future(Blocks.TORCH.defaultBlockState(), DOOR, FLOOR);
        assertEquals(COST_INF, f.cost(FLOOR), 0); f.tickMining(); assertEquals(0, f.damage.get());
    }

    @Test public void absentCeilingAttachmentProtectsTheTopBoundaryWithoutAFloorSpecialCase() throws Exception {
        BlockPos lever = ORIGIN.offset(1, 2, 1), anchor = lever.above();
        Fixture f = future(Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING), lever, anchor);
        assertEquals(COST_INF, f.cost(anchor), 0); f.tickMining(); assertEquals(0, f.damage.get());
    }

    @Test public void wrongIdentityNeighbourStillNeedsItsDesiredExternalSupportForLaterRepair() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR);
        f.world.states.put(DOOR.asLong(), Blocks.COBBLESTONE.defaultBlockState());
        assertEquals(COST_INF, f.cost(FLOOR), 0); f.tickMining(); assertEquals(0, f.damage.get());
        f.hit = DOOR; assertEquals(1, f.cost(DOOR), 0); f.tickMining(); assertEquals(1, f.damage.get());
    }

    @Test public void actualWrongFacingAndFutureWantedFacingBothRetainTheirRespectiveSupports() throws Exception {
        BlockPos torch = ORIGIN;
        BlockState wanted = Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        Fixture f = future(wanted, torch, torch.west());
        f.world.states.put(torch.asLong(), wanted.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        f.world.states.put(torch.north().asLong(), Blocks.STONE.defaultBlockState());
        assertEquals(COST_INF, f.cost(torch.west()), 0);
        assertEquals(COST_INF, f.cost(torch.north()), 0);
    }

    @Test public void unsafeRouteRetainsFutureSupportAcrossPauseAndNullOrForeignOwner() throws Exception {
        for (int handover = 0; handover < 3; handover++) {
            Fixture f = future(door(), DOOR, FLOOR);
            var route = BuilderModelRouteProtectionTest.route(f);
            if (handover == 0) {
                set(f.owner, "progressWatch", new BuilderProgressWatch(5, 60, 2)); f.owner.pause();
            } else set(route.control(), "inControlThisTick", handover == 1 ? null : BuilderModelRouteProtectionTest.foreign());
            assertFalse(route.pathing().cancelSegmentIfSafe());
            assertSame(route.executor(), route.pathing().getCurrent());
            f.tickMining(); assertEquals(0, f.damage.get()); assertEquals(0, f.world.writes);
        }
    }

    @Test public void changedExternalSupportIsRecheckedAndStopsAnAlreadyDamagingRetainedRoute() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR);
        // AIR is an unambiguous unsupported-before input.
        f.world.states.remove(FLOOR.asLong()); assertEquals(1, f.cost(FLOOR), 0);
        var route = BuilderModelRouteProtectionTest.route(f);
        set(route.control(), "inControlThisTick", null);
        f.world.states.put(FLOOR.asLong(), Blocks.STONE.defaultBlockState());
        set(f.mining, "wasHitting", true); f.tickMining();
        assertEquals(0, f.damage.get()); assertEquals(1, f.resets.get());
        assertEquals(false, get(f.mining, "wasHitting"));
    }

    @Test public void modelAirHelperCleanupDoesNotAcquireAFutureOutsideTerrainRestriction() throws Exception {
        BlockPos target = DOOR.above(), helper = DOOR; // Both positions are INSIDE the model.
        Fixture f = future(door(), target, helper);
        f.world.states.put(helper.asLong(), Blocks.DIRT.defaultBlockState());
        assertEquals(1, f.cost(helper), 0); f.tickMining(); assertEquals(1, f.damage.get());
    }

    @Test public void unrelatedOutsideTerrainAndSupportIndependentWantedBlockRemainMineable() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR);
        BlockPos unrelated = FLOOR.east(); f.world.states.put(unrelated.asLong(), Blocks.STONE.defaultBlockState());
        f.hit = unrelated; assertEquals(1, f.cost(unrelated), 0); f.tickMining(); assertEquals(1, f.damage.get());
        Fixture independent = future(Blocks.STONE.defaultBlockState(), DOOR, FLOOR);
        assertEquals(1, independent.cost(FLOOR), 0); independent.tickMining(); assertEquals(1, independent.damage.get());
    }

    @Test public void alreadyUnsupportedDesiredStateDoesNotInventADifferentNecessarySupport() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR);
        f.world.states.remove(FLOOR.asLong());
        BlockPos side = ORIGIN.offset(-1, 0, 1); f.world.states.put(side.asLong(), Blocks.STONE.defaultBlockState());
        f.hit = side; assertEquals(1, f.cost(side), 0); f.tickMining(); assertEquals(1, f.damage.get());
    }

    @Test public void selectedWrongStateRepairAndItsPairedUpperRemainAvailable() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR);
        BlockState wrong = door().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        f.world.states.put(DOOR.asLong(), wrong);
        f.world.states.put(DOOR.above().asLong(), wrong.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        assertFalse(policy(f).removal(DOOR).allowed());
        f.owner.selectSupportRepair(DOOR, wrong, door()); f.hit = DOOR;
        f.tickMining(); assertEquals(1, f.damage.get());
        f.hit = FLOOR; f.tickMining(); assertEquals(1, f.damage.get());
    }

    @Test public void unloadedFinalWorldCannotAuthorizeARequiredFutureFoundationBreak() throws Exception {
        Fixture f = future(door(), DOOR, FLOOR); var route = BuilderModelRouteProtectionTest.route(f);
        f.world.unloaded = true;
        assertFalse(route.executor().allowsModelRemoval(FLOOR, null, () -> false));
        f.tickMining(); assertEquals(0, f.damage.get());
    }

    private static BlockState door() {
        return Blocks.PALE_OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
    }
    private static Fixture future(BlockState wanted, BlockPos target, BlockPos support) throws Exception {
        Fixture f = fixture(Blocks.AIR.defaultBlockState(), false); f.world.states.clear();
        f.world.states.put(support.asLong(), Blocks.STONE.defaultBlockState()); f.hit = support;
        f.full = new AbstractSchematic(3, 3, 3) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> stock) {
                BlockPos at = ORIGIN.offset(x, y, z);
                if (at.equals(target)) return wanted;
                if (wanted.is(Blocks.PALE_OAK_DOOR) && at.equals(target.above()))
                    return wanted.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                return Blocks.AIR.defaultBlockState();
            }
        };
        f.active = new MaskSchematic(f.full) {
            @Override protected boolean partOfMask(int x, int y, int z, BlockState current) { return false; }
        };
        set(f.owner, "schematic", f.active); set(f.owner, "realSchematic", f.full);
        set(f.cost, "schematic", f.active);
        set(f.cost, "supportDependencies", policy(f));
        return f;
    }
    private static BuilderSupportDependencies policy(Fixture f) {
        return new BuilderSupportDependencies(f.full, ORIGIN, List.of(), f.blocks, f.world);
    }
}
