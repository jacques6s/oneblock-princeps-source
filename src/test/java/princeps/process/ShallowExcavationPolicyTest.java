/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.*;

public class ShallowExcavationPolicyTest {
    private static final BlockPos ROOF = new BlockPos(12, 21, 32);

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ExcavationRepairPolicy.Bounds surface(int height, int floor, int top) {
        return new ExcavationRepairPolicy.Bounds(10, 16, 20, 19 + height, 30, 36, floor, top, true);
    }

    @Test
    public void completeOneHighSurfaceUsesOrdinaryMiningButTwoHighAndShortLastBandsKeepTheirMode() {
        assertEquals(1, BuilderProcess.effectiveAreaBreakSize(3, true, 7, 1, 7));
        assertEquals(1, BuilderProcess.effectiveAreaBreakSize(3, true, 1, 1, 1));
        assertEquals(3, BuilderProcess.effectiveAreaBreakSize(3, true, 7, 2, 7));
        assertEquals("a final one-high band still belongs to a four-high selection", 3,
                BuilderProcess.effectiveAreaBreakSize(3, true, 7, 4, 7));
        assertEquals("construction is not an excavation surface", 3,
                BuilderProcess.effectiveAreaBreakSize(3, false, 7, 1, 7));
    }

    @Test
    public void openSurfaceKeepsHeadroomWhileSidesAndFloorStillOweRepair() {
        var bounds = surface(1, 20, 20);
        BlockState air = Blocks.AIR.defaultBlockState();
        assertNull(ExcavationRepairPolicy.repair(bounds, 12, 21, 32, air, true, true));
        assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP,
                ExcavationRepairPolicy.repair(bounds, 9, 20, 32, air, true, true));
        assertEquals(ExcavationRepairPolicy.Kind.BRIDGE,
                ExcavationRepairPolicy.repair(bounds, 12, 19, 32, air, true, true));
        var census = ExcavationRepairPolicy.inspect(bounds, pos ->
                        bounds.inside(pos.getX(), pos.getY(), pos.getZ()) || pos.getY() == 21
                                ? air : Blocks.STONE.defaultBlockState(),
                pos -> bounds.inside(pos.getX(), pos.getY(), pos.getZ()) || pos.getY() == 21);
        assertTrue("an empty open one-high pit is complete without creating a ceiling", census.clean());
    }

    @Test
    public void existingSolidRoofIsPreservedAndWaterOrLavaInputStillRequiresASeal() {
        var bounds = surface(1, 20, 20);
        assertNull(ExcavationRepairPolicy.repair(bounds, 12, 21, 32,
                Blocks.STONE.defaultBlockState(), false, true));
        for (BlockState source : new BlockState[] {Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState()}) {
            assertEquals(ExcavationRepairPolicy.Kind.SHELL_SOURCE,
                    ExcavationRepairPolicy.repair(bounds, 12, 21, 32, source, true, true));
            assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP,
                    ExcavationRepairPolicy.repair(bounds, 12, 21, 32,
                            source.setValue(BlockStateProperties.LEVEL, 2), true, true));
        }
    }

    @Test
    public void oneHighTopBandOfATallerSelectionStillSealsItsRoof() {
        var bounds = surface(4, 23, 23);
        assertFalse(ShallowExcavationPolicy.keepRoofAir(bounds, 12, 24, 32, Blocks.AIR.defaultBlockState()));
        assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP,
                ExcavationRepairPolicy.repair(bounds, 12, 24, 32, Blocks.AIR.defaultBlockState(), true, true));
        var twoHigh = surface(2, 20, 21);
        assertEquals(ExcavationRepairPolicy.Kind.SHELL_GAP,
                ExcavationRepairPolicy.repair(twoHigh, 12, 22, 32, Blocks.AIR.defaultBlockState(), true, true));
    }

    @Test
    public void lowCeilingUsesRealStandingCollisionRatherThanAListOfBlockNames() {
        assertTrue(ShallowExcavationPolicy.roofBlocksStanding(Blocks.STONE.defaultBlockState(),
                EmptyBlockGetter.INSTANCE, ROOF));
        assertTrue(ShallowExcavationPolicy.roofBlocksStanding(Blocks.OAK_SLAB.defaultBlockState(),
                EmptyBlockGetter.INSTANCE, ROOF));
        assertFalse(ShallowExcavationPolicy.roofBlocksStanding(Blocks.AIR.defaultBlockState(),
                EmptyBlockGetter.INSTANCE, ROOF));
        BlockState highTrapdoor = Blocks.IRON_TRAPDOOR.defaultBlockState()
                .setValue(BlockStateProperties.HALF, Half.TOP);
        assertFalse("1.8125 blocks of clearance still fits the standing body",
                ShallowExcavationPolicy.roofBlocksStanding(highTrapdoor, EmptyBlockGetter.INSTANCE, ROOF));
    }

    @Test
    public void nearbyCoveredRemainderIsActuallyVisibleFromAnOpenAccessHole() {
        BlockPos target = new BlockPos(1, 20, 0);
        Vec3 eye = new Vec3(0.5D, 21.62D, 0.5D);
        Vec3 aim = new Vec3(1.01D, 20.75D, 0.5D);
        BlockHitResult hit = Blocks.STONE.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, target)
                .clip(eye, aim, target);
        assertNotNull(hit);
        assertEquals(target, hit.getBlockPos());
        assertEquals(Direction.WEST, hit.getDirection());
        assertNull("the ray enters the selected block below its intact roof",
                Blocks.STONE.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, target.above())
                        .clip(eye, aim, target.above()));
        assertFalse("covered alone is not a reason to reject reachable ordinary work",
                ShallowExcavationPolicy.stopAfterFailedRoute(true, false, false, true, false));
    }

    @Test
    public void theSameAccessHoleCannotAimThroughTheFixedRoofAtAFartherCell() {
        BlockPos target = new BlockPos(3, 20, 0);
        BlockPos interveningRoof = new BlockPos(1, 21, 0);
        Vec3 eye = new Vec3(0.5D, 21.62D, 0.5D);
        Vec3 aim = new Vec3(3.01D, 20.75D, 0.5D);
        var shape = Blocks.STONE.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, target);
        BlockHitResult targetHit = shape.clip(eye, aim, target);
        BlockHitResult roofHit = shape.clip(eye, aim, interveningRoof);
        assertNotNull(targetHit);
        assertNotNull(roofHit);
        assertTrue("the fixed roof intercepts the ray before the selected work cell",
                roofHit.getLocation().distanceToSqr(eye) < targetHit.getLocation().distanceToSqr(eye));
        assertTrue(ShallowExcavationPolicy.roofBlocksStanding(Blocks.STONE.defaultBlockState(),
                EmptyBlockGetter.INSTANCE, interveningRoof));
    }

    @Test
    public void failedRouteUnderFixedRoofStopsWithoutForgivingUnfinishedCells() {
        assertTrue(ShallowExcavationPolicy.stopAfterFailedRoute(true, true, false, true, false));
        assertFalse("new world work invalidates a previous failed path verdict",
                ShallowExcavationPolicy.stopAfterFailedRoute(true, true, true, true, false));
        assertFalse(ShallowExcavationPolicy.stopAfterFailedRoute(true, true, false, false, false));
        assertFalse("two-high or taller work retains its existing route handling",
                ShallowExcavationPolicy.stopAfterFailedRoute(false, true, false, true, false));
        var bounds = surface(1, 20, 20);
        var census = ExcavationRepairPolicy.inspect(bounds, pos -> Blocks.STONE.defaultBlockState(), pos -> false);
        assertFalse("a blocked route cannot convert remaining blocks into success", census.clean());
        assertEquals(49, census.solid());
    }

    @Test
    public void failedGenericRouteKeepsARealWetApproachToPartlyCoveredOneHighWork() throws ReflectiveOperationException {
        try (VanillaWaterTags ignored = new VanillaWaterTags()) {
            var bounds = surface(1, 20, 20);
            BetterBlockPos feet = new BetterBlockPos(10, 20, 30);
            BetterBlockPos work = new BetterBlockPos(16, 20, 30);
            BetterBlockPos next = feet.east();
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState water = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 2);
            Map<Long, BlockState> cells = new HashMap<>();
            cells.put(feet.asLong(), water);
            cells.put(next.asLong(), water);
            cells.put(work.asLong(), stone);
            cells.put(work.above().asLong(), stone);
            Function<BlockPos, BlockState> read = pos -> cells.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
            boolean covered = ShallowExcavationPolicy.roofBlocksStanding(read.apply(work.above()),
                    EmptyBlockGetter.INSTANCE, work.above());
            assertTrue(covered);
            BetterBlockPos actualStep = ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, read);
            assertNotNull("the next body's headroom remains open despite the distant fixed roof", actualStep);
            assertEquals(next.asLong(), actualStep.asLong());
            assertFalse("a generic route failure cannot reject an available licensed water step",
                    ShallowExcavationPolicy.stopAfterFailedRoute(true, true, false, covered,
                            ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, read) != null));

            for (BlockState obstruction : new BlockState[] {stone,
                    Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)}) {
                cells.put(next.above().asLong(), obstruction);
                assertNull(ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, read));
                assertTrue("a blocked body step grants no retry exemption or outside excavation",
                        ShallowExcavationPolicy.stopAfterFailedRoute(true, true, false, covered,
                                ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, read) != null));
            }
            cells.remove(next.above().asLong());
            assertFalse("removing the obstacle makes the existing step usable again",
                    ShallowExcavationPolicy.stopAfterFailedRoute(true, true, false, covered,
                            ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, read) != null));
            cells.remove(feet.asLong());
            cells.remove(next.asLong());
            assertTrue("ordinary dry navigation has no water-step exemption",
                    ShallowExcavationPolicy.stopAfterFailedRoute(true, true, false, covered,
                            ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work, read) != null));
        }
    }
}
