/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.Princeps;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.pathing.calc.PathProbe;
import princeps.process.builder.ConfirmedBuildActions;
import princeps.utils.BlockPlaceHelper;
import princeps.utils.BlockStateInterface;
import princeps.utils.InputOverrideHandler;
import princeps.utils.PathingCommandContext;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static princeps.api.pathing.movement.ActionCosts.COST_INF;

/** Actual Downward phase, actual execution cost and central controller callback; no physics/provider shadow. */
public class BuilderCleanupOwnershipExecutionTest {
    private static final BetterBlockPos HELPER = new BetterBlockPos(13, 22, 19);
    private static Unsafe allocator;
    private static Class<?> episodeType, stageType;
    private static Method drive;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
        episodeType = Class.forName("princeps.process.BuilderProcess$CleanupEscape");
        stageType = Class.forName("princeps.process.BuilderProcess$CleanupStage");
        drive = BuilderProcess.class.getDeclaredMethod("driveCleanupDownward", episodeType,
                BuilderProcess.BuilderCalculationContext.class);
        drive.setAccessible(true);
    }

    @Test public void actualExecutionCostLosesOwnershipAfterAirEvenIfIdenticalDirtReappears() throws Exception {
        Fixture f = fixture(); f.own();
        assertEquals(1.0, f.cost(), 0.0);
        f.remove();
        f.debt.serverChanged(f.world, HELPER, Blocks.DIRT.defaultBlockState(), 43, 104);
        f.blocks.nimmBlockAn(HELPER, Blocks.DIRT.defaultBlockState());
        assertEquals(BuilderCleanupDebt.State.REMOVED, f.debt.state());
        assertEquals(COST_INF, f.cost(), 0.0);
    }

    @Test public void endingTheEpisodeRevokesAnExistingExecutionContextWithoutErasingDebt() throws Exception {
        Fixture f = fixture(); f.own(); assertEquals(1.0, f.cost(), 0.0);
        set(f.builder, "cleanupEscape", null);
        assertEquals(COST_INF, f.cost(), 0.0);
        assertEquals(BuilderCleanupDebt.State.OWNED, f.debt.state());
    }

    @Test public void presentHypothesisKeepsItsSeparateSingleBlockCostWithoutClaimingOwnership() throws Exception {
        Fixture f = fixture();
        set(f.context, "permittedDebt", null); set(f.context, "hypotheticalMine", true);
        assertEquals(1.0, f.cost(), 0.0);
        assertEquals(BuilderCleanupDebt.State.REQUESTED, f.debt.state());
    }

    @Test public void actualDownwardPhaseContinuesItsOwnedBlockAndThenItsRemovedAirLanding() throws Exception {
        Fixture f = fixture(); f.own();
        assertSame(f.context, ((PathingCommandContext) f.drive()).desiredCalcContext);
        f.remove(); f.blocks.nimmBlockAn(HELPER, Blocks.AIR.defaultBlockState());
        PathingCommand landing = f.drive();
        assertEquals(PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, landing.commandType);
        assertTrue(landing.goal.isInGoal(HELPER));
        assertSame(f.context, ((PathingCommandContext) landing).desiredCalcContext);
        assertEquals("DOWNWARD", read(f.episode, "stage").toString());
    }

    @Test public void actualDownwardPhaseCancelsInsteadOfMiningIdenticalForeignReplacement() throws Exception {
        Fixture f = fixture(); f.own(); f.remove();
        f.debt.serverChanged(f.world, HELPER, Blocks.DIRT.defaultBlockState(), 43, 104);
        f.blocks.nimmBlockAn(HELPER, Blocks.DIRT.defaultBlockState());
        PathingCommand stopped = f.drive();
        assertEquals(PathingCommandType.CANCEL_AND_SET_GOAL, stopped.commandType);
        assertNull(stopped.goal);
        assertEquals("BLOCKED", read(f.episode, "stage").toString());
    }

    @Test public void actualControllerCallbackCannotRegisterAnEscapeBeforeMatchingLocalReceipt() throws Exception {
        Fixture f = fixture();
        // This is the real entry point that BlockPlaceHelper invokes before publishing SuccessfulBlockInteraction.
        f.builder.recordNavigationScaffold(HELPER, Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        assertFalse(f.ledger.awaitingServer());
        f.builder.observeScaffoldServerChange(HELPER, Blocks.DIRT.defaultBlockState());
        assertFalse(f.ledger.owns(HELPER, Blocks.DIRT.defaultBlockState()));
        assertEquals(BuilderCleanupDebt.State.REQUESTED, f.debt.state());
    }

    private static Fixture fixture() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        Princeps owner = allocate(Princeps.class);
        ClientLevel world = allocate(ClientLevel.class);
        Object episode = allocator.allocateInstance(episodeType);
        set(episode, "this$0", builder); set(episode, "helper", HELPER); set(episode, "owner", HELPER.below());
        set(episode, "world", world); set(episode, "placed", true);
        set(episode, "snapshot", Map.of(HELPER.asLong(), Blocks.AIR.defaultBlockState()));
        set(episode, "stage", enumValue("DOWNWARD")); set(episode, "probe", new PathProbe("cleanup-ownership-test"));
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("world")) return world;
                    if (method.getName().equals("playerFeet")) return HELPER.above();
                    throw new AssertionError("unexpected live dependency: " + method.getName());
                });
        set(builder, "princeps", owner); set(builder, "ctx", player);
        set(owner, "playerContext", player);
        InputOverrideHandler input = allocate(InputOverrideHandler.class);
        set(input, "blockPlaceHelper", allocate(BlockPlaceHelper.class)); set(owner, "inputOverrideHandler", input);
        BuilderCleanupDebt debt = new BuilderCleanupDebt(world, episode, HELPER, Blocks.DIRT.defaultBlockState(), 7, 40, 100);
        set(builder, "cleanupEscape", episode); set(builder, "cleanupEscapeDebt", debt);
        set(builder, "cleanupServerUpdateSequence", 40L);
        BuilderScaffoldLedger ledger = new BuilderScaffoldLedger(); set(builder, "navigationScaffolds", ledger);
        set(builder, "progressActions", new ConfirmedBuildActions<BlockState>(BlockState::equals));
        var model = new AbstractSchematic(1, 1, 1) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> stock) {
                return Blocks.AIR.defaultBlockState();
            }
        };
        set(builder, "schematic", model);
        var context = allocate(BuilderProcess.CleanupEscapeContext.class);
        set(context, "this$0", builder); set(context, "mineOnly", HELPER); set(context, "mineState", Blocks.DIRT.defaultBlockState());
        Field superclassOwner = BuilderProcess.BuilderCalculationContext.class.getDeclaredField("this$0");
        superclassOwner.setAccessible(true); superclassOwner.set(context, builder);
        set(context, "permittedDebt", debt); set(context, "allowBreak", true); set(context, "allowBreakAnyway", List.of());
        set(context, "originX", HELPER.x); set(context, "originY", HELPER.y); set(context, "originZ", HELPER.z);
        set(context, "schematic", model);
        BlockStateInterface blocks = allocate(BlockStateInterface.class);
        blocks.nimmBlockAn(HELPER, Blocks.DIRT.defaultBlockState()); set(context, "bsi", blocks);
        set(episode, "realRouteContext", context); set(episode, "realRouteGoal", new GoalBlock(HELPER));
        set(episode, "realRouteDestination", HELPER); set(episode, "realRouteMayMine", true);
        return new Fixture(builder, context, episode, world, debt, blocks, ledger);
    }

    private record Fixture(BuilderProcess builder, BuilderProcess.CleanupEscapeContext context, Object episode,
                           ClientLevel world, BuilderCleanupDebt debt, BlockStateInterface blocks, BuilderScaffoldLedger ledger) {
        void own() { debt.interactionObserved(world, episode, 8, 101); debt.serverChanged(world, HELPER, Blocks.DIRT.defaultBlockState(), 41, 102); }
        void remove() { debt.serverChanged(world, HELPER, Blocks.AIR.defaultBlockState(), 42, 103); }
        double cost() { return context.breakCostMultiplierAt(HELPER.x, HELPER.y, HELPER.z, blocks.get0(HELPER)); }
        PathingCommand drive() throws Exception { return (PathingCommand) BuilderCleanupOwnershipExecutionTest.drive.invoke(builder, episode, context); }
    }
    private static Object enumValue(String name) { return java.util.Arrays.stream(stageType.getEnumConstants()).filter(v -> v.toString().equals(name)).findFirst().orElseThrow(); }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(allocator.allocateInstance(type)); }
    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static Object read(Object target, String name) throws Exception { return field(target, name).get(target); }
}
