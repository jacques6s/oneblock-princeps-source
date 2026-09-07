/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.Princeps;
import princeps.api.behavior.look.IAimProcessor;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.process.PathingCommand;
import princeps.api.schematic.FillSchematic;
import princeps.api.utils.*;
import princeps.api.utils.input.Input;
import princeps.behavior.LookBehavior;
import princeps.behavior.PathingBehavior;
import princeps.pathing.calc.PathProbe;
import princeps.utils.BlockPlaceHelper;
import princeps.utils.BlockStateInterface;
import princeps.utils.InputOverrideHandler;
import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.Assert.*;

/** Actual Minecraft clip + runtime face/eye/quantization and the production derivation/actor branch.
 * Offline poses/world/options are inputs; clip and aim are not stubs. No physics, use packet or ACK claim. */
public class BuilderCleanupClickableStanceTest {
    private static final BetterBlockPos STANCE = new BetterBlockPos(16, 21, 11);
    private static final BetterBlockPos HELPER = STANCE.west(3).below();
    private static final Rotation LOOK = new Rotation(857.40F, 15.15F);
    private static Unsafe allocator;
    private static Class<?> episodeType, stageType;
    private static Method derive, actor, approach, points;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
        episodeType = Class.forName("princeps.process.BuilderProcess$CleanupEscape");
        stageType = Class.forName("princeps.process.BuilderProcess$CleanupStage");
        derive = method("possibleToPlace", BlockState.class, int.class, int.class, int.class,
                BuilderProcess.BuilderCalculationContext.class, int[].class);
        actor = method("deriveCleanupPlacement", episodeType, BuilderProcess.BuilderCalculationContext.class);
        approach = method("driveCleanupPlacementApproach", episodeType);
        points = method("aimPointsOnFace", Vec3.class, BlockPos.class, AABB.class, Direction.class, BlockState.class);
    }

    @Test public void actualVanillaAndQuantizedRaysReproduceTheWorkingReachBoundary() throws Exception {
        Fixture f = fixture(); f.failedPose();
        assertEquals(1.27, f.avatar.getEyeHeight(Pose.CROUCHING), .000001);
        assertEquals(0, f.hits(3.5)); assertEquals(9, f.hits(4.5));
        int[] rejected = new int[6];
        assertTrue(f.derive(rejected).isEmpty());
        assertArrayEquals(new int[]{5, 0, 0, 0, 9, 0}, rejected);
        f.pose(.5, .5);
        assertEquals("the same real MC world permits one quantized support click at the center", 1, f.hits(3.5));
        assertEquals(9, f.hits(4.5));
    }

    @Test public void actualRayMissInsideOrdinaryToleranceKeepsTheProofAndMovesTheActor() throws Exception {
        Fixture f = fixture(); f.failedPose();
        Object owner = read(f.episode, "owner"); long deadline = (long) read(f.episode, "deadline");
        assertTrue((boolean) method("centeredInPlacementStance", BetterBlockPos.class).invoke(f.builder, STANCE));
        assertNotNull(f.act());
        assertEquals("WALK_PREFIX", f.stage()); assertTrue(f.moves());
        assertEquals(Boolean.TRUE, f.inputs.get(Input.SNEAK));
        assertFalse(f.inputs.containsKey(Input.CLICK_RIGHT));
        assertSame(owner, read(f.episode, "owner")); assertEquals(deadline, read(f.episode, "deadline"));
        assertTrue((boolean) read(f.episode, "prefixProved"));
        assertTrue((boolean) read(f.episode, "presentProved")); assertTrue((boolean) read(f.episode, "removedProved"));
        assertNull(read(f.builder, "cleanupEscapeDebt"));
    }

    @Test public void thePulseMustSettleThenReenterTheRealPlacementPhaseAtAClickablePose() throws Exception {
        Fixture f = fixture(); f.failedPose(); f.act();
        assertEquals("WALK_PREFIX", f.stage());
        // This supplies an observed next pose, not a claim that the isolated test runs movement physics.
        f.pose(.5, .5); f.inputs.clear();
        assertNotNull(f.approach()); assertEquals("WALK_PREFIX", f.stage()); assertFalse(f.moves());
        f.inputs.clear(); assertNull(f.approach()); assertEquals("PLACE", f.stage());
        assertEquals(1, f.hits(3.5)); assertTrue(f.inputs.isEmpty());
    }

    @Test public void aMotionlessRayMissConsumesTheStanceInsteadOfOscillatingOrRenewingTheEpisode() throws Exception {
        Fixture f = fixture(); f.failedPose(); long deadline = (long) read(f.episode, "deadline");
        f.act(); f.inputs.clear(); set(f.episode, "stage", stage("PLACE")); f.act();
        assertEquals("CANDIDATE", f.stage()); assertFalse(f.moves());
        assertEquals(deadline, read(f.episode, "deadline")); assertEquals(STANCE, read(f.episode, "proofStart"));
        assertFalse((boolean) read(f.episode, "prefixProved"));
    }

    @Test public void aFurtherCorrectionRequiresMeasuredApproachAndConsumesTheSameBudget() throws Exception {
        Fixture f = fixture(); f.pose(.61, .56); assertEquals(0, f.hits(3.5)); f.act();
        f.pose(.60, .555); assertEquals(0, f.hits(3.5)); f.inputs.clear();
        set(f.episode, "stage", stage("PLACE")); f.act();
        assertEquals("WALK_PREFIX", f.stage()); assertTrue(f.moves());
        assertEquals(2, read(f.episode, "placementCenteringTicks"));
    }

    @Test public void movingFurtherFromTheCenterDoesNotStartABackAndForthController() throws Exception {
        Fixture f = fixture(); f.failedPose(); f.act();
        f.pose(.62, .56); f.inputs.clear(); set(f.episode, "stage", stage("PLACE")); f.act();
        assertEquals("CANDIDATE", f.stage()); assertFalse(f.moves());
    }

    @Test public void aRayThatStillMissesAtTheExactCenterDoesNotGainReach() throws Exception {
        Fixture f = fixture(); f.world.states.clear();
        BetterBlockPos remote = HELPER.west(); set(f.episode, "helper", remote);
        f.world.states.put(remote.below().asLong(), Blocks.DIRT.defaultBlockState()); f.pose(.5, .5);
        f.act(); assertEquals("CANDIDATE", f.stage()); assertFalse(f.moves());
    }

    @Test public void theExistingCenteringBudgetStillBoundsClickCorrections() throws Exception {
        Fixture f = fixture(); f.failedPose(); set(f.episode, "placementCenteringTicks", 79);
        f.act(); assertEquals("CANDIDATE", f.stage()); assertFalse(f.moves());
    }

    @Test public void aRealEntityObstructionDoesNotBecomeARayRecenteringRequest() throws Exception {
        Fixture f = fixture(); f.failedPose(); f.world.obstructed = true;
        int[] rejected = new int[6]; assertTrue(f.derive(rejected).isEmpty());
        assertArrayEquals(new int[]{5, 0, 1, 0, 0, 0}, rejected);
        f.act(); assertEquals("CANDIDATE", f.stage()); assertFalse(f.moves());
    }

    @Test public void observedWorldInvalidationBlocksTheCorrectionBeforeInput() throws Exception {
        Fixture f = fixture(); f.failedPose(); set(f.episode, "worldInvalidated", true);
        f.act(); assertEquals("BLOCKED", f.stage()); assertFalse(f.moves());
    }

    @Test public void actualOuterDriverStillRejectsAChangedOwnerBeforeTheClickBranch() throws Exception {
        Fixture f = fixture(); f.failedPose(); set(f.builder, "electedCell", HELPER.east());
        method("driveCleanupEscape", boolean.class, BuilderProcess.BuilderCalculationContext.class)
                .invoke(f.builder, true, f.context);
        assertEquals("BLOCKED", f.stage()); assertTrue(f.inputs.isEmpty());
    }

    private static Fixture fixture() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class); Princeps owner = allocate(Princeps.class);
        TestWorld world = allocate(TestWorld.class); world.states = new HashMap<>();
        world.states.put(HELPER.below().asLong(), Blocks.DIRT.defaultBlockState());
        world.states.put(STANCE.below().asLong(), Blocks.OAK_PLANKS.defaultBlockState());
        TestPlayer avatar = allocate(TestPlayer.class); set(avatar, "level", world); set(avatar, "onGround", true);
        Minecraft mc = allocate(Minecraft.class); Options options = allocate(Options.class);
        OptionInstance<?> sensitivity = allocate(OptionInstance.class); set(sensitivity, "value", .5);
        set(options, "sensitivity", sensitivity); set(mc, "options", options);
        IPlayerController controller = (IPlayerController) Proxy.newProxyInstance(IPlayerController.class.getClassLoader(),
                new Class<?>[]{IPlayerController.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockReachDistance")) return 3.5;
                    throw new AssertionError("unexpected controller use " + method.getName());
                });
        IPlayerContext ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "player" -> avatar; case "world" -> world; case "minecraft" -> mc;
                    case "playerController" -> controller; case "playerRotations" -> LOOK;
                    case "playerFeet" -> new BetterBlockPos(avatar.position().x, avatar.position().y, avatar.position().z);
                    default -> throw new AssertionError("unexpected live dependency " + method.getName());
                });
        set(builder, "ctx", ctx); set(builder, "princeps", owner); set(owner, "playerContext", ctx);
        Class<?> aimType = Class.forName("princeps.behavior.LookBehavior$AimProcessor");
        Constructor<?> constructor = aimType.getDeclaredConstructor(IPlayerContext.class); constructor.setAccessible(true);
        IAimProcessor processor = (IAimProcessor) constructor.newInstance(ctx);
        LookBehavior look = allocate(LookBehavior.class); set(look, "processor", processor); set(owner, "lookBehavior", look);
        set(owner, "pathingBehavior", allocate(PathingBehavior.class));
        InputOverrideHandler input = allocate(InputOverrideHandler.class); Map<Input, Boolean> inputs = new HashMap<>();
        set(input, "inputForceStateMap", inputs); set(input, "blockPlaceHelper", allocate(BlockPlaceHelper.class));
        set(owner, "inputOverrideHandler", input);
        TestBlocks blocks = allocate(TestBlocks.class); blocks.world = world;
        var context = allocate(BuilderProcess.BuilderCalculationContext.class); set(context, "bsi", blocks);
        set(context, "allowBreakAnyway", List.of()); set(context, "maxFallHeightNoWater", 3);
        Object episode = allocate(episodeType); set(episode, "this$0", builder); set(episode, "world", world);
        set(episode, "stage", stage("PLACE")); set(episode, "stance", STANCE); set(episode, "helper", HELPER);
        set(episode, "material", Blocks.DIRT.defaultBlockState()); set(episode, "owner", HELPER.below());
        set(episode, "snapshot", Map.of()); set(episode, "probe", new PathProbe("cleanup-clickable-stance-test"));
        set(episode, "stances", new ArrayDeque<BetterBlockPos>());
        set(episode, "placementFailedCenterDistanceSq", Double.POSITIVE_INFINITY);
        set(episode, "deadline", System.nanoTime() + 60_000_000_000L); set(episode, "initialRules", context);
        set(episode, "prefixProved", true); set(episode, "presentProved", true); set(episode, "removedProved", true);
        var model = new FillSchematic(24, 30, 15, Blocks.AIR.defaultBlockState());
        var goal = new GoalBlock(HELPER.below()); set(builder, "schematic", model); set(builder, "origin", BlockPos.ZERO);
        set(builder, "electedCell", HELPER.below()); set(builder, "electedGoal", goal); set(builder, "cleanupEscape", episode);
        set(episode, "model", model); set(episode, "buildOrigin", BlockPos.ZERO); set(episode, "ownerGoal", goal);
        return new Fixture(builder, episode, context, avatar, world, processor, inputs);
    }

    private record Fixture(BuilderProcess builder, Object episode, BuilderProcess.BuilderCalculationContext context,
                           TestPlayer avatar, TestWorld world, IAimProcessor processor, Map<Input, Boolean> inputs) {
        void pose(double x, double z) throws Exception {
            Vec3 feet = new Vec3(STANCE.x + x, STANCE.y, STANCE.z + z); set(avatar, "position", feet);
            set(avatar, "bb", new AABB(feet.x - .3, feet.y, feet.z - .3, feet.x + .3, feet.y + 1.5, feet.z + .3));
        }
        void failedPose() throws Exception { pose(.60083494592281, .55920385669317); }
        Optional<?> derive(int[] rejected) throws Exception {
            return (Optional<?>) derive.invoke(builder, Blocks.DIRT.defaultBlockState(), HELPER.x, HELPER.y, HELPER.z, context, rejected);
        }
        PathingCommand act() throws Exception { return (PathingCommand) actor.invoke(builder, episode, context); }
        PathingCommand approach() throws Exception { return (PathingCommand) approach.invoke(builder, episode); }
        String stage() throws Exception { return read(episode, "stage").toString(); }
        boolean moves() { return inputs.keySet().stream().anyMatch(k -> k == Input.MOVE_FORWARD || k == Input.MOVE_BACK
                || k == Input.MOVE_LEFT || k == Input.MOVE_RIGHT); }
        int hits(double reach) throws Exception {
            BlockPos support = HELPER.below(); BlockState actual = world.getBlockState(support);
            Vec3 eye = RayTraceUtils.inferSneakingEyePosition(avatar);
            List<Vec3> samples = (List<Vec3>) points.invoke(null, eye, support, actual.getShape(world, support).bounds(),
                    Direction.DOWN, Blocks.DIRT.defaultBlockState());
            int hits = 0;
            for (Vec3 point : samples) {
                Rotation rotation = processor.peekRotationExact(RotationUtils.calcRotationFromVec3d(eye, point, LOOK));
                HitResult hit = RayTraceUtils.rayTraceTowards(avatar, rotation, reach, true);
                if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                        && block.getBlockPos().equals(support) && block.getDirection() == Direction.UP) hits++;
            }
            return hits;
        }
    }

    private static final class TestWorld extends ClientLevel {
        Map<Long, BlockState> states; boolean obstructed;
        private TestWorld() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public boolean isUnobstructed(Entity except, VoxelShape shape) { return !obstructed; }
    }
    private static final class TestPlayer extends LocalPlayer {
        private TestPlayer() { super(null, null, null, null, null, null, false, null); }
        @Override public boolean isShiftKeyDown() { return true; }
        @Override public boolean isFallFlying() { return false; }
        @Override public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
    }
    private static final class TestBlocks extends BlockStateInterface {
        TestWorld world;
        private TestBlocks() { super(null); }
        @Override public BlockState get0(int x, int y, int z) { return world.getBlockState(new BlockPos(x, y, z)); }
    }
    private static Object stage(String name) { return Arrays.stream(stageType.getEnumConstants()).filter(v -> v.toString().equals(name)).findFirst().orElseThrow(); }
    private static Method method(String name, Class<?>... args) throws Exception {
        Method method = BuilderProcess.class.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
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
