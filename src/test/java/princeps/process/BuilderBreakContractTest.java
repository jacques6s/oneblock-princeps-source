/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
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
import princeps.api.pathing.goals.Goal;
import princeps.api.schematic.FillSchematic;
import princeps.api.schematic.ISchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.utils.BlockStateInterface;
import princeps.pathing.calc.PathProbe;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Actual assemble/stickyPlacement calls. Row goals avoid the live singleton; target ownership is shared by both modes. */
public class BuilderBreakContractTest {
    private static Unsafe allocator;
    private static final BetterBlockPos FIRST = new BetterBlockPos(2, 2, 2);
    private static final BetterBlockPos SECOND = new BetterBlockPos(5, 2, 2);

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        if (BuiltInRegistries.ITEM.wrapAsHolder(Items.WATER_BUCKET) instanceof Holder.Reference<Item> holder
                && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
    }

    @Test public void actualAirRemovalAssemblyBindsOneTargetWithoutAnyInventory() throws Exception {
        Fixture f = fixture();
        Goal goal = f.assemble();
        assertTrue("a real removal must own navigation", FIRST.equals(get(f.builder, "electedCell")));
        assertTrue(goal == get(f.builder, "electedGoal"));
        assertFalse(goal.isInGoal(SECOND.x - 1, SECOND.y + 1, SECOND.z));
    }

    @Test public void movingTheBodyCannotSwitchToTheOtherRemoval() throws Exception {
        Fixture f = fixture(); Goal chosen = f.assemble();
        f.feet[0] = SECOND.above();
        assertTrue("one removal retains its actual goal, not a rebuilt composite", chosen == f.assemble());
        assertTrue(FIRST.equals(get(f.builder, "electedCell")));
    }

    @Test public void airRemovalNeverEntersThePlacementOrHotbarOracle() throws Exception {
        Fixture f = fixture(); f.assemble();
        Method sticky = BuilderProcess.class.getDeclaredMethod("stickyPlacement",
                BuilderProcess.BuilderCalculationContext.class, List.class);
        sticky.setAccessible(true);
        List<BlockState> demand = new ArrayList<>();
        // No player, inventory or placement lock exists in this fixture. Any such access is a regression.
        assertEquals(java.util.Optional.empty(), sticky.invoke(f.builder, f.context, demand));
        assertTrue(demand.isEmpty());
        assertTrue(FIRST.equals(get(f.builder, "electedCell")));
    }

    @Test public void realWorkSetRemovalReleasesOnlyTheMissingTarget() throws Exception {
        Fixture f = fixture(); f.assemble();
        f.work.remove(FIRST);
        Goal next = f.assemble();
        assertTrue(SECOND.equals(get(f.builder, "electedCell")));
        assertTrue(next == get(f.builder, "electedGoal"));
    }

    @Test public void aChangedLayerMaskRevokesTheRemoval() throws Exception {
        Fixture f = fixture(); f.assemble();
        set(f.context, "schematic", new FillSchematic(1, 1, 1, Blocks.AIR.defaultBlockState()));
        assertNull(f.assemble());
        assertNull(get(f.builder, "electedCell"));
        assertTrue(f.work.isEmpty());
    }

    @Test public void observingAirInTheChosenCellRevokesTheNoopRemovalImmediately() throws Exception {
        Fixture f = fixture(); f.assemble();
        ((TestBlocks) get(f.context, "bsi")).states.remove(FIRST.asLong());
        f.assemble();
        assertTrue("an already clear cell no longer owns removal", SECOND.equals(get(f.builder, "electedCell")));
        assertFalse(f.work.contains(FIRST));
    }

    @Test public void ordinaryBreakGoalStillRejectsStandingAboveItsTarget() {
        Goal goal = new BuilderProcess.GoalBreak(FIRST);
        assertFalse(goal.isInGoal(FIRST.x, FIRST.y + 1, FIRST.z));
        assertTrue(goal.isInGoal(FIRST.x + 1, FIRST.y, FIRST.z));
    }

    @Test public void realTickMaskProducerAssemblerAndPendingLaneKeepTheSameQuestionAcrossEquivalentTicks() throws Exception {
        Fixture f = fixture(); ISchematic source = (ISchematic) get(f.builder, "schematic");
        ISchematic firstMask = f.mask(source, 0, 7, false);
        set(f.context, "schematic", firstMask);
        Goal goal = f.assemble(); Object question = f.pendingQuestion(goal);
        ISchematic nextMask = f.mask(source, 0, 7, false);
        assertTrue("equivalent tick producer preserves semantic model identity", firstMask == nextMask);
        assertTrue("real assembler retains the chosen goal object", goal == f.assemble());
        f.drive(goal);
        assertTrue("the real lane driver retains the pending current question", question == get(f.builder, "laneQuestion"));
    }

    @Test public void realMaskProducerInvalidatesActualLaneOnBandRuleAndUnderlyingModelChanges() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Fixture f = fixture(); ISchematic source = (ISchematic) get(f.builder, "schematic");
            ISchematic mask = f.mask(source, 0, 7, false); set(f.context, "schematic", mask);
            Goal goal = f.assemble(); f.pendingQuestion(goal);
            ISchematic changed = kind == 0 ? f.mask(source, 1, 6, false)
                    : kind == 1 ? f.mask(source, 0, 7, true)
                    : f.mask(new FillSchematic(8, 8, 8, Blocks.AIR.defaultBlockState()), 0, 7, false);
            assertTrue(mask != changed);
            f.drive(goal);
            assertTrue(get(f.builder, "laneQuestion") == null);
            assertTrue(get(f.builder, "laneAProof") == null);
        }
    }

    @Test public void tickMethodUsesTheSameTestedMaskProducer() throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var stream = BuilderProcess.class.getResourceAsStream("BuilderProcess.class")) {
            new org.objectweb.asm.ClassReader(stream).accept(node, 0);
        }
        int calls = 0;
        for (var method : node.methods) for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals("princeps/process/BuilderProcess") && call.name.equals("layerMask")) {
                assertEquals("onTick", method.name); calls++;
            }
        }
        assertEquals(1, calls);
    }

    private record Fixture(BuilderProcess builder, BuilderProcess.BuilderCalculationContext context,
                           BetterBlockPos[] feet, HashSet<BetterBlockPos> work) {
        Goal assemble() throws Exception {
            Method method = BuilderProcess.class.getDeclaredMethod("assemble",
                    BuilderProcess.BuilderCalculationContext.class, List.class, boolean.class, boolean.class);
            method.setAccessible(true);
            return (Goal) method.invoke(builder, context, List.of(), false, false);
        }
        ISchematic mask(ISchematic source, int min, int max, boolean topDown) throws Exception {
            Method method = BuilderProcess.class.getDeclaredMethod("layerMask", ISchematic.class, int.class, int.class, boolean.class);
            method.setAccessible(true); ISchematic mask = (ISchematic) method.invoke(builder, source, min, max, topDown);
            set(builder, "schematic", mask); return mask;
        }
        Object pendingQuestion(Goal goal) throws Exception {
            Class<?> type = java.util.Arrays.stream(BuilderProcess.class.getDeclaredClasses())
                    .filter(candidate -> candidate.getSimpleName().equals("LaneQuestion")).findFirst().orElseThrow();
            var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            Object question = constructor.newInstance(FIRST, goal, goal, feet[0], context,
                    Blocks.DIRT.defaultBlockState(), false, 0L, null, null);
            set(builder, "laneQuestion", question); set(builder, "laneProbeLane", BuilderProcess.Lane.A_NO_PLACING);
            PathProbe probe = (PathProbe) get(builder, "laneProbe");
            set(probe, "running", allocate(princeps.pathing.calc.AStarPathFinder.class));
            return question;
        }
        void drive(Goal goal) throws Exception {
            Method method = BuilderProcess.class.getDeclaredMethod("driveLanesInShadow", Goal.class);
            method.setAccessible(true); method.invoke(builder, goal);
        }
    }

    private static Fixture fixture() throws Exception {
        BuilderProcess builder = allocate(BuilderProcess.class);
        // The real assembler's target-selection code is shared. Only breakGoal's row branch avoids consulting
        // the live Princeps singleton; no provider or global setting is replaced for these tests.
        set(builder, "buildInRows", true); set(builder, "rowActiveBandStart", Integer.MIN_VALUE);
        set(builder, "parkedCells", new LinkedHashMap<>());
        HashSet<BetterBlockPos> work = new HashSet<>(List.of(FIRST, SECOND));
        set(builder, "incorrectPositions", work);
        List<BlockState> inventory = java.util.Collections.nCopies(9, Blocks.AIR.defaultBlockState());
        set(builder, "approxPlaceable", inventory);
        set(builder, "origin", BlockPos.ZERO);
        set(builder, "laneProbe", new PathProbe("test")); set(builder, "laneOutcomes", new long[8]);
        set(builder, "navigationScaffolds", new BuilderScaffoldLedger());
        BetterBlockPos[] feet = { FIRST.above() };
        TestLevel world = allocate(TestLevel.class);
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{ IPlayerContext.class }, (proxy, method, args) -> {
                    if (method.getName().equals("playerFeet")) return feet[0];
                    if (method.getName().equals("world")) return world;
                    throw new AssertionError("unexpected live/material access: " + method.getName());
                });
        set(builder, "ctx", player);
        TestBlocks blocks = allocate(TestBlocks.class);
        blocks.states = new HashMap<>();
        blocks.states.put(FIRST.asLong(), Blocks.DIRT.defaultBlockState());
        blocks.states.put(SECOND.asLong(), Blocks.DIRT.defaultBlockState());
        world.states = blocks.states;
        assertTrue(blocks.get0(FIRST) == Blocks.DIRT.defaultBlockState());
        assertTrue(blocks.get0(SECOND) == Blocks.DIRT.defaultBlockState());
        var context = allocate(BuilderProcess.BuilderCalculationContext.class);
        set(context, "this$0", builder); set(context, "bsi", blocks);
        set(context, "world", world); set(context, "placeable", inventory);
        set(context, "rowMode", true); set(context, "rowBandStart", Integer.MIN_VALUE);
        ISchematic source = new FillSchematic(8, 8, 8, Blocks.AIR.defaultBlockState());
        set(context, "schematic", source); set(builder, "schematic", source);
        return new Fixture(builder, context, feet, work);
    }

    private static final class TestLevel extends ClientLevel {
        Map<Long, BlockState> states;
        private TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState()); }
    }

    private static final class TestBlocks extends BlockStateInterface {
        Map<Long, BlockState> states;
        private TestBlocks() { super(null); }
        @Override public BlockState get0(int x, int y, int z) {
            return states.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }
    }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(allocator.allocateInstance(type)); }
    private static Object get(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
