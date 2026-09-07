/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.Princeps;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.calc.IPath;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.schematic.FillSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;
import princeps.behavior.PathingBehavior;
import princeps.pathing.calc.PathProbe;
import princeps.pathing.path.PathExecutor;
import net.minecraft.world.level.block.Blocks;
import princeps.utils.BlockPlaceHelper;
import princeps.utils.InputOverrideHandler;
import princeps.utils.PathingCommandContext;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Calls the actual actor approach phase. These are input/state contracts, not Minecraft physics assertions. */
public class BuilderCleanupApproachTest {
    private static final BetterBlockPos STANCE = new BetterBlockPos(16, 21, 11);
    private static Unsafe allocator;
    private static Class<?> episodeType, stageType;
    private static Method approach;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
        episodeType = Class.forName("princeps.process.BuilderProcess$CleanupEscape");
        stageType = Class.forName("princeps.process.BuilderProcess$CleanupStage");
        approach = BuilderProcess.class.getDeclaredMethod("driveCleanupPlacementApproach", episodeType);
        approach.setAccessible(true);
    }

    @Test public void actualActorCentersAnInCellArrivalBeforeEnteringPlace() throws Exception {
        Fixture f = fixture();
        // Same sub-block offset as the real 144-stop, translated away from the benchmark coordinates.
        f.pose(new Vec3(STANCE.x + .759876854, STANCE.y, STANCE.z + .564538494), true);
        PathingCommand command = f.approach();
        assertNotNull("cell arrival must not skip the proved placement pose", command);
        assertEquals(PathingCommandType.CANCEL_AND_SET_GOAL, command.commandType);
        assertEquals("WALK_PREFIX", f.stage());
        assertEquals(Boolean.TRUE, f.inputs.get(Input.SNEAK));
        assertTrue("real existing centering primitive must issue a body movement",
                f.inputs.keySet().stream().anyMatch(k -> k == Input.MOVE_FORWARD || k == Input.MOVE_BACK
                        || k == Input.MOVE_LEFT || k == Input.MOVE_RIGHT));
        assertFalse(f.inputs.containsKey(Input.CLICK_RIGHT));
    }

    @Test public void actualActorSettlesAtTheCenterBeforeEnteringPlace() throws Exception {
        Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE), true);
        assertNotNull("first centered tick must settle the actual body", f.approach());
        assertEquals("WALK_PREFIX", f.stage());
        assertEquals(Boolean.TRUE, f.inputs.get(Input.SNEAK));
        f.inputs.clear();
        assertNull("next unchanged centered grounded tick may derive a live click", f.approach());
        assertEquals("PLACE", f.stage());
        assertTrue(f.inputs.isEmpty());
    }

    @Test public void anUnreachedStanceRetainsTheExistingStrictRouteContext() throws Exception {
        Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE.east()), true);
        PathingCommand command = f.approach();
        assertTrue(command instanceof PathingCommandContext);
        assertSame(f.route, ((PathingCommandContext) command).desiredCalcContext);
        assertTrue(command.goal.isInGoal(STANCE)); assertEquals("WALK_PREFIX", f.stage());
        assertTrue(f.inputs.isEmpty());
    }

    @Test public void anAirborneCellArrivalDoesNotStartPlacementOrCentering() throws Exception {
        Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE), false);
        assertSame(f.route, ((PathingCommandContext) f.approach()).desiredCalcContext);
        assertEquals("WALK_PREFIX", f.stage()); assertTrue(f.inputs.isEmpty());
    }

    @Test public void anObservedWorldChangeStillBlocksBeforeAnyMovementInput() throws Exception {
        Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE), true);
        set(f.episode, "worldInvalidated", true);
        assertEquals(PathingCommandType.CANCEL_AND_SET_GOAL, f.approach().commandType);
        assertEquals("BLOCKED", f.stage()); assertTrue(f.inputs.isEmpty());
    }

    @Test public void theActualFullEscapeDriverUsesThePoseStepAfterAProvedPrefix() throws Exception {
        Fixture f = fixture(); f.pose(new Vec3(STANCE.x + .76, STANCE.y, STANCE.z + .565), true);
        assertNotNull(f.drive());
        assertEquals("WALK_PREFIX", f.stage());
        assertEquals(Boolean.TRUE, f.inputs.get(Input.SNEAK));
        assertTrue(f.inputs.size() >= 2); assertFalse(f.inputs.containsKey(Input.CLICK_RIGHT));
    }

    @Test public void aStillPresentPathIsCancelledBeforeCenteringTakesMovementOwnership() throws Exception {
        Fixture f = fixture(); f.pose(new Vec3(STANCE.x + .76, STANCE.y, STANCE.z + .565), true);
        set(f.pathing, "current", allocate(PathExecutor.class));
        assertNotNull(f.approach()); assertTrue(f.inputs.isEmpty());
        set(f.pathing, "current", null);
        assertNotNull(f.approach()); assertTrue(f.inputs.size() >= 2);
        assertEquals("WALK_PREFIX", f.stage());
    }

    @Test public void driftingOutOfTheCenterRequiresAnotherSettlingTick() throws Exception {
        Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE), true); assertNotNull(f.approach());
        f.pose(new Vec3(STANCE.x + .76, STANCE.y, STANCE.z + .565), true); assertNotNull(f.approach());
        f.pose(Vec3.atBottomCenterOf(STANCE), true); assertNotNull(f.approach());
        assertEquals("WALK_PREFIX", f.stage()); assertNull(f.approach()); assertEquals("PLACE", f.stage());
    }

    @Test public void anUnconvergedActorConsumesOneStanceWithoutRenewingItsOwnerOrDeadline() throws Exception {
        Fixture f = fixture(); f.pose(new Vec3(STANCE.x + .76, STANCE.y, STANCE.z + .565), true);
        Object owner = read(f.episode, "owner"); long deadline = (long) read(f.episode, "deadline");
        ((ArrayDeque<BetterBlockPos>) read(f.episode, "stances")).add(STANCE.north());
        for (int i = 0; i < 80; i++) { f.inputs.clear(); assertNotNull(f.approach()); }
        assertEquals("CANDIDATE", f.stage()); assertSame(owner, read(f.episode, "owner"));
        assertEquals(deadline, read(f.episode, "deadline")); assertEquals(STANCE, read(f.episode, "proofStart"));
        assertEquals(List.of(STANCE.north()), List.copyOf((ArrayDeque<?>) read(f.episode, "stances")));
        assertNull(read(f.episode, "realRouteContext")); assertNull(read(f.builder, "cleanupEscapeDebt"));
        assertFalse((boolean) read(f.episode, "prefixProved")); assertFalse((boolean) read(f.episode, "presentProved"));
        assertFalse((boolean) read(f.episode, "removedProved"));
    }

    @Test public void rejectionCannotRestartAnEpisodeAfterAHelperRequestWasIssued() throws Exception {
        Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE), true);
        set(f.episode, "placed", true);
        Method reject = BuilderProcess.class.getDeclaredMethod("rejectCleanupPlacementStance", episodeType, String.class);
        reject.setAccessible(true); reject.invoke(f.builder, f.episode, "test rejection");
        assertEquals("BLOCKED", f.stage());
        assertEquals(STANCE.east(2), read(f.episode, "proofStart"));
    }

    @Test public void theActualProofConsumerUsesTheRebasedPoseAfterARejectedApproach() throws Exception {
        Fixture f = fixture(); f.pose(new Vec3(STANCE.x + .76, STANCE.y, STANCE.z + .565), true);
        for (int i = 0; i < 80; i++) f.approach();
        f.answer(PathProbe.Outcome.COMPLETE);
        assertNotNull(f.drive()); assertEquals("WALK_PREFIX", f.stage());
        assertTrue((boolean) read(f.episode, "removedProved"));
        assertTrue(f.inputs.keySet().stream().noneMatch(k -> k == Input.CLICK_RIGHT || k == Input.CLICK_LEFT));
    }

    @Test public void aLaterRealPoseChangeInvalidatesEvenACompleteProofBeforeAnyAction() throws Exception {
        Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE), true);
        set(f.episode, "proofStart", STANCE); f.answer(PathProbe.Outcome.COMPLETE);
        f.pose(Vec3.atBottomCenterOf(STANCE.east()), true);
        assertNotNull(f.drive()); assertEquals("BLOCKED", f.stage()); assertTrue(f.inputs.isEmpty());
    }

    @Test public void noncompleteAnswersNeverAdvanceTheActualActorToItsApproach() throws Exception {
        for (PathProbe.Outcome outcome : List.of(PathProbe.Outcome.ERROR, PathProbe.Outcome.NONE, PathProbe.Outcome.PARTIAL)) {
            Fixture f = fixture(); f.pose(Vec3.atBottomCenterOf(STANCE), true);
            set(f.episode, "proofStart", STANCE); f.answer(outcome);
            assertNotNull(f.drive()); assertEquals("CANDIDATE", f.stage()); assertTrue(f.inputs.isEmpty());
        }
    }

    private static Fixture fixture() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class); Princeps owner = allocate(Princeps.class);
        LocalPlayer avatar = allocate(LocalPlayer.class); ClientLevel world = allocate(ClientLevel.class);
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "world" -> world;
                    case "player" -> avatar;
                    case "playerFeet" -> new BetterBlockPos(avatar.position().x, avatar.position().y, avatar.position().z);
                    case "playerRotations" -> new Rotation(120.15F, 13.50F);
                    default -> throw new AssertionError("unexpected live dependency: " + method.getName());
                });
        set(builder, "princeps", owner); set(builder, "ctx", player); set(owner, "playerContext", player);
        PathingBehavior pathing = allocate(PathingBehavior.class); set(owner, "pathingBehavior", pathing);
        InputOverrideHandler input = allocate(InputOverrideHandler.class);
        Map<Input, Boolean> inputs = new HashMap<>(); set(input, "inputForceStateMap", inputs);
        set(input, "blockPlaceHelper", allocate(BlockPlaceHelper.class)); set(owner, "inputOverrideHandler", input);
        Object episode = allocator.allocateInstance(episodeType);
        set(episode, "this$0", builder); set(episode, "world", world);
        set(episode, "stage", stage("WALK_PREFIX")); set(episode, "stance", STANCE);
        set(episode, "helper", STANCE.west(3).below()); set(episode, "owner", STANCE.west(3).below(2));
        set(episode, "snapshot", Map.of()); set(episode, "probe", new PathProbe("cleanup-approach-test"));
        set(episode, "stances", new ArrayDeque<BetterBlockPos>());
        var route = allocate(BuilderProcess.CleanupEscapeContext.class);
        set(route, "allowBreakAnyway", List.of()); set(route, "maxFallHeightNoWater", 3);
        var model = new FillSchematic(24, 11, 15, Blocks.AIR.defaultBlockState());
        set(builder, "schematic", model); set(builder, "origin", BlockPos.ZERO);
        set(builder, "electedCell", read(episode, "owner"));
        var ownerGoal = new GoalBlock((BetterBlockPos) read(episode, "owner"));
        set(builder, "electedGoal", ownerGoal); set(episode, "ownerGoal", ownerGoal);
        set(episode, "model", model); set(episode, "buildOrigin", BlockPos.ZERO);
        set(episode, "start", STANCE.east(2)); set(episode, "proofStart", STANCE.east(2));
        set(episode, "deadline", System.nanoTime() + 60_000_000_000L); set(episode, "initialRules", route);
        set(episode, "prefixProved", true); set(episode, "presentProved", true); set(episode, "removedProved", true);
        set(episode, "realRouteContext", route); set(episode, "realRouteGoal", new GoalBlock(STANCE));
        set(episode, "realRouteDestination", STANCE); set(episode, "realRouteMayMine", false);
        set(builder, "cleanupEscape", episode);
        return new Fixture(builder, episode, avatar, inputs, route, pathing);
    }

    private record Fixture(BuilderProcess builder, Object episode, LocalPlayer avatar, Map<Input, Boolean> inputs,
                           BuilderProcess.CleanupEscapeContext route, PathingBehavior pathing) {
        void pose(Vec3 feet, boolean ground) throws Exception { set(avatar, "position", feet); set(avatar, "onGround", ground); }
        PathingCommand approach() throws Exception { return (PathingCommand) approach.invoke(builder, episode); }
        PathingCommand drive() throws Exception {
            Method method = BuilderProcess.class.getDeclaredMethod("driveCleanupEscape", boolean.class,
                    BuilderProcess.BuilderCalculationContext.class); method.setAccessible(true);
            return (PathingCommand) method.invoke(builder, true, route);
        }
        void answer(PathProbe.Outcome outcome) throws Exception {
            BetterBlockPos from = STANCE.west(3).below(), to = from.below();
            IPath path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getSrc" -> from;
                        case "getDest" -> to;
                        case "positions" -> List.of(from, to);
                        case "length" -> 2;
                        default -> throw new AssertionError("unexpected path method " + method.getName());
                    });
            var constructor = PathProbe.Result.class.getDeclaredConstructor(PathProbe.Outcome.class, IPath.class, long.class);
            constructor.setAccessible(true);
            PathProbe probe = (PathProbe) read(episode, "probe");
            ((AtomicReference<PathProbe.Result>) read(probe, "finished")).set(constructor.newInstance(outcome, path, 1L));
            set(episode, "queryStart", from); set(episode, "queryGoal", new GoalBlock(to));
            set(episode, "queryContext", route); set(episode, "stage", BuilderCleanupApproachTest.stage("REMOVED_PROOF"));
        }
        String stage() throws Exception { return read(episode, "stage").toString(); }
    }
    private static Object stage(String name) { return java.util.Arrays.stream(stageType.getEnumConstants()).filter(v -> v.toString().equals(name)).findFirst().orElseThrow(); }
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
