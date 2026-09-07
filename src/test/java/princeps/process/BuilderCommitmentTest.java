/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;
import princeps.Princeps;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.goals.Goal;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.schematic.FillSchematic;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.IPlayerContext;
import princeps.behavior.PathingBehavior;
import princeps.pathing.path.PathExecutor;
import princeps.pathing.movement.CalculationContext;
import princeps.process.builder.BuilderProgressWatch;
import princeps.process.builder.ConfirmedBuildActions;
import princeps.utils.InputOverrideHandler;
import princeps.utils.BlockStateInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

/** Calls the actual completion callback, including the pre-fix callback in the controlled RED run. */
public class BuilderCommitmentTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : new Item[]{Items.WATER_BUCKET, Items.PALE_OAK_SIGN}) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        }
    }

    @Test public void completingAnotherCellDoesNotReleaseTheChosenBuildTarget() throws Exception {
        BuilderProcess process = fixture();
        BetterBlockPos chosen = new BetterBlockPos(10, 65, 20);
        field("electedCell").set(process, chosen);
        completed(process, new BetterBlockPos(11, 64, 20));
        assertTrue("an unrelated completion must retain the elected target", chosen.equals(field("electedCell").get(process)));
        assertEquals(77L, field("lastCellCompletedTick").getLong(process));
    }

    @Test public void completingTheChosenCellReleasesIt() throws Exception {
        BuilderProcess process = fixture();
        BetterBlockPos chosen = new BetterBlockPos(10, 65, 20);
        field("electedCell").set(process, chosen);
        completed(process, chosen);
        assertNull(field("electedCell").get(process));
    }

    @Test public void actualPauseAndResumeCallbacksExcludeATimeGapWithoutTicks() throws Exception {
        BuilderProcess process = fixture();
        BuilderProgressWatch watch = new BuilderProgressWatch(5, 60, 2);
        field("progressWatch").set(process, watch);
        var sample = new BuilderProgressWatch.Sample(BuilderProgressWatch.Phase.WORK, 7L, 10, null, -1, 0, null, 0);
        watch.observe(0, sample); watch.observe(4, sample);
        process.pause(); assertTrue(process.isPaused());
        process.resume(); assertFalse(process.isPaused());
        assertNotEquals(BuilderProgressWatch.Decision.STOP, watch.observe(10000, sample));
        assertEquals(4L, watch.quietNanos());
        assertEquals(BuilderProgressWatch.Decision.STOP, watch.observe(10056, sample));
    }

    @Test public void realHoldWrapperCannotReviveTheRouteAfterAWatchWaitOrStop() throws Exception {
        BuilderProcess process = fixture();
        Princeps engine = allocate(Princeps.class);
        PathingBehavior pathing = allocate(PathingBehavior.class);
        InputOverrideHandler inputs = allocate(InputOverrideHandler.class);
        set(inputs, "inputForceStateMap", new HashMap<>());
        GoalBlock goal = new GoalBlock(10, 65, 20);
        set(pathing, "goal", goal);
        set(engine, "pathingBehavior", pathing);
        set(engine, "inputOverrideHandler", inputs);
        set(process, "princeps", engine);
        field("incorrectPositions").set(process, new java.util.HashSet<>(Set.of(new BetterBlockPos(10, 65, 20))));
        Method wrapper = BuilderProcess.class.getDeclaredMethod("holdStillWithoutTearingUpTheRoute", PathingCommand.class);
        wrapper.setAccessible(true);
        PathingCommand cancel = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        PathingCommand ordinary = (PathingCommand) wrapper.invoke(process, cancel);
        assertEquals(PathingCommandType.REVALIDATE_GOAL_AND_PATH, ordinary.commandType);
        assertSame(goal, ordinary.goal);
        field("progressHold").setBoolean(process, true);
        assertSame(cancel, wrapper.invoke(process, cancel));
    }

    @Test public void actualPathExecutorCancelAndFinishedSentinelsAreNeverProgressNodes() throws Exception {
        List<BetterBlockPos> positions = List.of(new BetterBlockPos(0, 64, 0), new BetterBlockPos(1, 64, 0), new BetterBlockPos(2, 64, 0));
        IPath path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "positions" -> positions;
                    case "length" -> positions.size();
                    default -> throw new AssertionError("unexpected path call " + method.getName());
                });
        PathExecutor executor = allocate(PathExecutor.class);
        set(executor, "path", path);
        set(executor, "pathPosition", 1);
        assertEquals(1, BuilderProcess.progressPathPosition(executor));
        set(executor, "pathPosition", positions.size() + 3); // actual PathExecutor.cancel() sentinel
        set(executor, "failed", true);
        assertEquals(-1, BuilderProcess.progressPathPosition(executor));
        set(executor, "failed", false);
        assertEquals(-1, BuilderProcess.progressPathPosition(executor));
        set(executor, "pathPosition", -1);
        assertEquals(-1, BuilderProcess.progressPathPosition(executor));
        set(executor, "pathPosition", 1); set(executor, "failed", true);
        assertEquals(-1, BuilderProcess.progressPathPosition(executor));
    }

    @Test public void actualServerCallbackCreditsAnOutstandingPlacementOnceButNotOldPowerCycles() throws Exception {
        BuilderProcess process = fixture();
        field("schematic").set(process, new FillSchematic(1, 1, 1, Blocks.STONE.defaultBlockState()));
        field("navigationScaffolds").set(process, new BuilderScaffoldLedger());
        var actions = new ConfirmedBuildActions<BlockState>(BlockState::equals);
        field("progressActions").set(process, actions);
        BlockPos pos = new BlockPos(10, 65, 20);
        BlockState off = Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.POWERED, false);
        BlockState on = off.setValue(BlockStateProperties.POWERED, true);
        actions.arm(pos.asLong(), Blocks.AIR.defaultBlockState(), off);
        process.observeScaffoldServerChange(pos, off);
        assertEquals(1L, field("confirmedProgressRevision").getLong(process));
        for (int i = 0; i < 100; i++) process.observeScaffoldServerChange(pos, i % 2 == 0 ? on : off);
        assertEquals(1L, field("confirmedProgressRevision").getLong(process));
    }

    @Test public void readonlyMiningAccessorNamesTheActualVanillaFloatField() throws Exception {
        assertEquals(float.class, net.minecraft.client.multiplayer.MultiPlayerGameMode.class.getDeclaredField("destroyProgress").getType());
    }

    @Test public void anActualSupportRemovalInvalidatesTheGoalButAnExhaustedSearchBudgetDoesNot() throws Exception {
        BuilderProcess process = fixture();
        BetterBlockPos cell = new BetterBlockPos(7, 20, 11);
        BlockPos support = cell.east();
        TestLevel world = allocate(TestLevel.class); world.states = new HashMap<>();
        world.states.put(support, Blocks.STONE.defaultBlockState());
        TestBlocks blocks = allocate(TestBlocks.class); blocks.level = world;
        LocalPlayer avatar = allocate(LocalPlayer.class);
        Inventory inventory = new Inventory(avatar, new EntityEquipment());
        inventory.getNonEquipmentItems().set(0, new ItemStack(Items.PALE_OAK_SIGN));
        set(avatar, "inventory", inventory);
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                (proxy, method, args) -> { if (method.getName().equals("world")) return world;
                    if (method.getName().equals("player")) return avatar;
                    throw new AssertionError("unexpected live dependency " + method.getName()); });
        set(process, "ctx", player);
        set(process, "parkedCells", new java.util.LinkedHashMap<>());
        set(process, "activeCells", new java.util.HashSet<>(Set.of(cell)));
        set(process, "incorrectPositions", new java.util.HashSet<>(Set.of(cell)));
        set(process, "cellVerdicts", new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap());
        set(process, "orientedGoalCache", new HashMap<Long, Optional<Goal>>());
        set(process, "stanceSearchNanos", Long.MAX_VALUE);
        BlockState desired = Blocks.PALE_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.WEST);
        var context = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(context, "this$0", process); set(context, "bsi", blocks);
        set(context, "originX", cell.x); set(context, "originY", cell.y); set(context, "originZ", cell.z);
        set(context, "schematic", new AbstractSchematic(1, 1, 1) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> available) { return desired; }
        });
        GoalBlock oldGoal = new GoalBlock(6, 20, 11);
        field("electedCell").set(process, cell); field("electedGoal").set(process, oldGoal);
        assertTrue(desired.canSurvive(world, cell));
        var unknown = process.urteileUeber(cell, context);
        assertTrue(unknown instanceof BuilderProcess.CellUrteil.Unbekannt);
        assertSame(oldGoal, process.acceptElectedVerdict(unknown));
        assertSame(cell, field("electedCell").get(process));
        world.states.remove(support);
        assertFalse(desired.canSurvive(world, cell));
        var invalid = process.urteileUeber(cell, context);
        assertTrue(invalid instanceof BuilderProcess.CellUrteil.Parken);
        assertNull(process.acceptElectedVerdict(invalid));
        assertNull(field("electedCell").get(process));
    }

    @Test public void unknownWithoutAPreviousRouteStillKeepsTheCellAndNewProofReplacesOnlyItsGoal() throws Exception {
        BuilderProcess process = fixture();
        BetterBlockPos cell = new BetterBlockPos(7, 20, 11);
        field("electedCell").set(process, cell);
        assertNull(process.acceptElectedVerdict(new BuilderProcess.CellUrteil.Unbekannt("chosen material not on hotbar")));
        assertSame(cell, field("electedCell").get(process));
        GoalBlock first = new GoalBlock(6, 20, 11), revised = new GoalBlock(7, 20, 10);
        assertSame(first, process.acceptElectedVerdict(new BuilderProcess.CellUrteil.Setzen(first)));
        assertSame(revised, process.acceptElectedVerdict(new BuilderProcess.CellUrteil.Setzen(revised)));
        assertSame(cell, field("electedCell").get(process));
    }

    private static final class TestLevel extends ClientLevel {
        Map<BlockPos, BlockState> states;
        private TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }
    private static final class TestBlocks extends BlockStateInterface {
        TestLevel level;
        private TestBlocks() { super(null); }
        @Override public BlockState get0(int x, int y, int z) { return level.getBlockState(new BlockPos(x, y, z)); }
    }

    private static void completed(BuilderProcess process, BetterBlockPos pos) throws Exception {
        Method callback = Arrays.stream(BuilderProcess.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("noteCellCompleted")).findFirst().orElseThrow();
        callback.setAccessible(true);
        if (callback.getParameterCount() == 0) callback.invoke(process);
        else callback.invoke(process, pos);
    }

    private static BuilderProcess fixture() throws Exception {
        BuilderProcess process = allocate(BuilderProcess.class);
        set(process, "breakBranchProgress", new BreakBranchProgress(120, 100));
        set(process, "excavationFluidPlugs", new ExcavationFluidPlugs());
        set(process, "excavationRepairAim", new ExcavationRepairAim());
        field("buildTick").setLong(process, 77L);
        field("stanceFailures").set(process, new Long2ObjectOpenHashMap<>());
        field("stanceEndorsed").set(process, new Long2ObjectOpenHashMap<>());
        return process;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field accessor = Unsafe.class.getDeclaredField("theUnsafe");
        accessor.setAccessible(true);
        return type.cast(((Unsafe) accessor.get(null)).allocateInstance(type));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Field field(String name) throws Exception {
        Field field = BuilderProcess.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
