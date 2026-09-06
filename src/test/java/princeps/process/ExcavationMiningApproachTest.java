package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.movement.IMovement;
import princeps.api.pathing.path.IPathExecutor;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.utils.BetterBlockPos;

import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.*;

public class ExcavationMiningApproachTest {
    private static BlockState AIR;
    private static BlockState WATER;
    private static VanillaWaterTags waterTags;

    @BeforeClass
    public static void bootstrapMinecraft() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        waterTags = new VanillaWaterTags();
        AIR = Blocks.AIR.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        assertTrue(WATER.getFluidState().is(FluidTags.WATER));
    }

    @AfterClass
    public static void restoreFluidTags() throws ReflectiveOperationException {
        if (waterTags != null) waterTags.close();
    }

    @Test
    public void observedReachBoundaryMiningCannotCancelTheUnfinishedSouthStep() {
        BetterBlockPos start = new BetterBlockPos(96, -54, 69);
        BetterBlockPos step = start.south();
        BlockPos target = new BlockPos(95, -53, 74);
        IPathExecutor route = route(start, step);
        Function<BlockPos, BlockState> world = pos -> pos.getY() == -54
                ? WATER.setValue(BlockStateProperties.LEVEL, 3) : AIR;
        int geometricallyReachable = 0;
        // Closed b713 trace: brief mining decisions recur near z=69.67 while the route destination is z=70.
        // This replays the measured geometry, not Minecraft fluid dynamics or a claim of server completion.
        for (double z : new double[] {69.633, 69.670, 69.664, 69.674, 69.680, 69.667}) {
            Vec3 body = new Vec3(96.700, -54, z);
            Vec3 northFace = new Vec3(95.5, -52.5, 74);
            if (body.add(0, 1.62, 0).distanceTo(northFace) <= 4.5) geometricallyReachable++;
            assertRetained(route, ExcavationMiningApproach.finishWetStep(true, route,
                    BlockPos.containing(body), target, world));
        }
        assertTrue("the old reachable-head gate can interrupt this very step", geometricallyReachable > 0);
        assertNull("entry into the destination cell is sufficient; exact centering is not required",
                ExcavationMiningApproach.finishWetStep(true, route, step, target, world));
        assertNull("a cancelled/completed executor cannot leave a sticky mining prohibition",
                ExcavationMiningApproach.finishWetStep(true, null, start, target, world));
    }

    @Test
    public void allDirectionsAndWaterLevelsKeepTheSameLicensedStepForOrdinaryAndAreaTargets() {
        BetterBlockPos start = new BetterBlockPos(10, 22, 30);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BetterBlockPos step = new BetterBlockPos(start.relative(direction));
            BlockPos target = step.relative(direction, 3).above();
            IPathExecutor route = route(start, step);
            for (BlockPos wet : new BlockPos[] {start, start.above(), step, step.above()}) {
                for (int level = 0; level <= 15; level++) {
                    BlockState water = WATER.setValue(BlockStateProperties.LEVEL, level);
                    assertTrue(water.getFluidState().is(FluidTags.WATER));
                    assertRetained(route, ExcavationMiningApproach.finishWetStep(true, route, start, target,
                            pos -> pos.equals(wet) ? water : AIR));
                }
            }
        }
    }

    @Test
    public void dryTravelAndOrdinaryConstructionRetainTheirMiningPreemption() {
        BetterBlockPos start = new BetterBlockPos(10, 22, 30);
        IPathExecutor route = route(start, start.south());
        BlockPos target = start.south(4).above();
        assertNull(ExcavationMiningApproach.finishWetStep(true, route, start, target, pos -> AIR));
        assertNull(ExcavationMiningApproach.finishWetStep(false, route, start, target, pos -> {
            fail("construction does not enter the excavation corridor policy");
            return WATER;
        }));
        assertNull(ExcavationMiningApproach.finishWetStep(true, route, start, null, pos -> WATER));
    }

    @Test
    public void lateBodyObstructionsAndEveryLocalBodyTargetCanPreemptMovement() {
        BetterBlockPos start = new BetterBlockPos(10, 22, 30);
        BetterBlockPos step = start.south();
        IPathExecutor route = route(start, step);
        BlockPos remote = start.south(4).above();
        for (BlockPos body : new BlockPos[] {start, start.above(), step, step.above()}) {
            assertNull("route obstruction election still reaches the ordinary mining branch",
                    ExcavationMiningApproach.finishWetStep(true, route, start, body, pos -> WATER));
            for (BlockState obstacle : new BlockState[] {
                    Blocks.STONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
                    Blocks.LAVA.defaultBlockState(), Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, 8),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                    Blocks.SEAGRASS.defaultBlockState(), Blocks.BUBBLE_COLUMN.defaultBlockState()
            }) {
                assertNull("a now-blocked body corridor must not be protected from a corrective action",
                        ExcavationMiningApproach.finishWetStep(true, route, start, remote,
                                pos -> pos.equals(body) ? obstacle : WATER));
            }
        }
    }

    @Test
    public void defaultOrPartialWaterLicencesCannotBorrowTheMiningTick() {
        BetterBlockPos start = new BetterBlockPos(10, 22, 30);
        BetterBlockPos step = start.south();
        IPath path = route(start, step).getPath();
        BlockPos target = start.south(4).above();
        for (BlockPos missing : new BlockPos[] {start, start.above(), step, step.above()}) {
            WadeLicence partial = WadeLicence.where(at -> at != missing.asLong(), "test partial");
            assertNull(ExcavationMiningApproach.finishWetStep(true, executor(path, 0, partial),
                    start, target, pos -> WATER));
        }
        IPathExecutor ordinaryRoute = new IPathExecutor() {
            @Override public IPath getPath() { return path; }
            @Override public int getPosition() { return 0; }
        };
        assertNull(ExcavationMiningApproach.finishWetStep(true, ordinaryRoute, start, target, pos -> WATER));
    }

    @Test
    public void noInferredDiagonalVerticalLongOrStaleRouteReceivesTheHold() {
        BetterBlockPos start = new BetterBlockPos(10, 22, 30);
        BlockPos target = start.south(4).above();
        for (BetterBlockPos end : new BetterBlockPos[] {
                start, start.above(), start.below(), start.south().east(), start.south(2)
        }) {
            assertNull(ExcavationMiningApproach.finishWetStep(true, route(start, end), start, target, pos -> WATER));
        }
        IPathExecutor route = route(start, start.south());
        assertNull(ExcavationMiningApproach.finishWetStep(true,
                executor(route.getPath(), 1, route.wadeLicence()), start, target, pos -> WATER));
        assertNull(ExcavationMiningApproach.finishWetStep(true, route, start.west(), target, pos -> WATER));
        assertNull(ExcavationMiningApproach.finishWetStep(true,
                executor(path(List.of(start, start.south(), start.south(2))), 0, route.wadeLicence()),
                start, target, pos -> WATER));
    }

    private static void assertRetained(IPathExecutor route, PathingCommand command) {
        assertNotNull("the clear water step must retain movement", command);
        assertEquals(PathingCommandType.REVALIDATE_GOAL_AND_PATH, command.commandType);
        assertSame("reuse the existing goal; do not calculate a route to the distant head", route.getPath().getGoal(), command.goal);
    }

    private static IPathExecutor route(BetterBlockPos start, BetterBlockPos step) {
        WadeLicence licence = WadeLicence.where(packed -> {
            BlockPos at = BlockPos.of(packed);
            return (at.getY() == start.y || at.getY() == start.y + 1)
                    && ((at.getX() == start.x && at.getZ() == start.z)
                    || (at.getX() == step.x && at.getZ() == step.z));
        }, "test immutable excavation step");
        return executor(path(List.of(start, step)), 0, licence);
    }

    private static IPath path(List<BetterBlockPos> positions) {
        Goal goal = new GoalBlock(positions.getLast());
        return new IPath() {
            @Override public List<IMovement> movements() { throw new UnsupportedOperationException("policy reads route positions only"); }
            @Override public List<BetterBlockPos> positions() { return positions; }
            @Override public Goal getGoal() { return goal; }
            @Override public int getNumNodesConsidered() { return 2; }
        };
    }

    private static IPathExecutor executor(IPath path, int position, WadeLicence licence) {
        return new IPathExecutor() {
            @Override public IPath getPath() { return path; }
            @Override public int getPosition() { return position; }
            @Override public WadeLicence wadeLicence() { return licence; }
        };
    }
}
