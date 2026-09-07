/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.process;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.AbstractSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.IPlayerContext;
import princeps.pathing.movement.CalculationContext;
import princeps.utils.BlockStateInterface;
import princeps.utils.PrincepsProcessHelper;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.Assert.*;

/** Real verdict/park/watcher methods against an isolated in-memory vanilla block world. */
public class BuilderParkVerdictLifecycleTest {
    private static final BetterBlockPos CELL = new BetterBlockPos(7, 20, 11);
    private static Unsafe allocator;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Item item : new Item[]{Items.WATER_BUCKET, Items.PALE_OAK_SIGN}) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        }
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        allocator = (Unsafe) f.get(null);
    }

    @Test public void unsupportedWallStatesActuallyLeaveActiveWorkAndAcquireOneParkAndMemo() throws Exception {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Fixture f = fixture(facing);
            assertFalse(f.desired.canSurvive(f.world, CELL));
            assertTrue(f.builder.urteileUeber(CELL, f.context) instanceof BuilderProcess.CellUrteil.Parken);
            assertEquals(1, f.parks.size()); assertTrue(f.memos.containsKey(CELL.asLong()));
            assertFalse(f.active.contains(CELL)); assertFalse(f.incorrect.contains(CELL));
            f.builder.urteileUeber(CELL, f.context);
            assertEquals("unchanged obstruction is recorded once", 1, f.parks.size());
            assertEquals(1L, field(f.builder, "cellsParked"));
        }
    }

    @Test public void readOnlyJudgmentDoesNotParkRemoveOrMemoizeUnsupportedCells() throws Exception {
        Fixture f = fixture(Direction.WEST);
        assertTrue(f.builder.urteileUeber(CELL, f.context, false) instanceof BuilderProcess.CellUrteil.Parken);
        assertTrue(f.parks.isEmpty()); assertTrue(f.memos.isEmpty());
        assertTrue(f.active.contains(CELL)); assertTrue(f.incorrect.contains(CELL));
    }

    @Test public void supportArrivalWakesBothPairedStatesWithoutClaimingPlacementSuccess() throws Exception {
        Fixture f = fixture(Direction.WEST); f.builder.urteileUeber(CELL, f.context);
        assertFalse(f.parks.isEmpty());
        f.world.states.put(f.support, Blocks.BLACK_STAINED_GLASS.defaultBlockState());
        assertTrue(f.desired.canSurvive(f.world, CELL));
        f.wake(f.support);
        assertTrue(f.parks.isEmpty()); assertTrue(f.memos.isEmpty());
        assertTrue(f.active.contains(CELL));
        // No placement is simulated: exhausting this test's stance budget must remain unknown, not success.
        set(BuilderProcess.class, f.builder, "stanceSearchNanos", Long.MAX_VALUE);
        assertTrue(f.builder.urteileUeber(CELL, f.context) instanceof BuilderProcess.CellUrteil.Unbekannt);
    }

    @Test public void supportChangeWithTheSameFaceMaskStillInvalidatesMissingSupportVerdict() throws Exception {
        Fixture f = fixture(Direction.NORTH);
        f.world.states.put(f.support, Blocks.TORCH.defaultBlockState());
        assertFalse(f.desired.canSurvive(f.world, CELL));
        f.builder.urteileUeber(CELL, f.context);
        assertFalse(f.parks.isEmpty());
        f.world.states.put(f.support, Blocks.STONE.defaultBlockState());
        assertTrue(f.desired.canSurvive(f.world, CELL));
        f.wake(f.support);
        assertTrue(f.parks.isEmpty()); assertTrue(f.memos.isEmpty());
    }

    @Test public void unrelatedWorldChangeDoesNotReleaseAValidMissingSupportPark() throws Exception {
        Fixture f = fixture(Direction.EAST); f.record(256L);
        f.wake(CELL.offset(30, 0, 30));
        assertEquals(1, f.parks.size()); assertEquals(1, f.memos.size());
        assertFalse(f.active.contains(CELL));
    }

    @Test public void dryWindowReleaseClearsEachReleasedMemoAndCachedGoalButKeepsUnrelatedEntries() throws Exception {
        Fixture f = fixture(Direction.SOUTH); f.record(512L);
        long other = CELL.offset(40, 0, 0).asLong();
        f.memos.put(other, 123L); f.goals.put(CELL.asLong(), Optional.empty()); f.goals.put(other, Optional.empty());
        call(f.builder, "releaseParkedCellsForRetry", new Class<?>[0]);
        assertTrue(f.parks.isEmpty()); assertFalse(f.memos.containsKey(CELL.asLong()));
        assertFalse(f.goals.containsKey(CELL.asLong()));
        assertEquals(123L, f.memos.get(other)); assertTrue(f.goals.containsKey(other));
    }

    @Test public void unchangedCellCanParkAgainAfterDryReleaseInsteadOfBecomingAnOrphanMemo() throws Exception {
        Fixture f = fixture(Direction.WEST); f.record(256L);
        call(f.builder, "releaseParkedCellsForRetry", new Class<?>[0]);
        f.active.add(CELL); f.incorrect.add(CELL);
        f.record(256L);
        assertEquals(1, f.parks.size()); assertEquals(1, f.memos.size());
        assertFalse(f.active.contains(CELL)); assertFalse(f.incorrect.contains(CELL));
        assertEquals(2L, field(f.builder, "cellsParked"));
    }

    @Test public void emptyDryReleasePreservesUnrelatedVerdicts() throws Exception {
        Fixture f = fixture(Direction.WEST); f.memos.put(123L, 456L);
        call(f.builder, "releaseParkedCellsForRetry", new Class<?>[0]);
        assertEquals(456L, f.memos.get(123L)); assertTrue(f.active.contains(CELL));
    }

    // Constructors require a running client. Only the storage shells are allocated;
    // production verdict, mutation and watcher methods are never overridden or mirrored.
    private static final class TestLevel extends ClientLevel {
        Map<BlockPos, BlockState> states;
        private TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
    }
    private static final class TestBlocks extends BlockStateInterface {
        TestLevel level;
        private TestBlocks() { super(null); }
        @Override public BlockState get0(int x, int y, int z) { return level.getBlockState(new BlockPos(x, y, z)); }
    }
    private record Fixture(BuilderProcess builder, BuilderProcess.BuilderCalculationContext context,
                           TestLevel world, BlockState desired, BlockPos support,
                           Map<Long, ?> parks, Long2LongOpenHashMap memos, Map<Long, Optional<?>> goals,
                           Set<BetterBlockPos> active, Set<BetterBlockPos> incorrect) {
        void record(long reason) throws Exception {
            call(builder, "recordCellVerdict", new Class<?>[]{int.class, int.class, int.class, long.class}, CELL.x, CELL.y, CELL.z, reason);
        }
        void wake(BlockPos pos) throws Exception {
            call(builder, "watchmanAfterChangeAt", new Class<?>[]{int.class, int.class, int.class}, pos.getX(), pos.getY(), pos.getZ());
        }
    }
    private static Fixture fixture(Direction facing) throws Exception {
        TestLevel world = (TestLevel) allocator.allocateInstance(TestLevel.class); world.states = new HashMap<>();
        TestBlocks blocks = (TestBlocks) allocator.allocateInstance(TestBlocks.class); blocks.level = world;
        LocalPlayer avatar = (LocalPlayer) allocator.allocateInstance(LocalPlayer.class);
        Inventory inventory = new Inventory(avatar, new EntityEquipment());
        inventory.getNonEquipmentItems().set(0, new ItemStack(Items.PALE_OAK_SIGN));
        set(Player.class, avatar, "inventory", inventory);
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("world")) return world;
                    if (method.getName().equals("player")) return avatar;
                    throw new AssertionError("unexpected live dependency: " + method.getName());
                });
        BuilderProcess builder = (BuilderProcess) allocator.allocateInstance(BuilderProcess.class);
        var parks = new LinkedHashMap<Long, Object>(); var memos = new Long2LongOpenHashMap();
        var goals = new HashMap<Long, Optional<?>>(); var active = new HashSet<>(Set.of(CELL));
        var incorrect = new HashSet<>(Set.of(CELL));
        set(PrincepsProcessHelper.class, builder, "ctx", player);
        set(BuilderProcess.class, builder, "parkedCells", parks); set(BuilderProcess.class, builder, "cellVerdicts", memos);
        set(BuilderProcess.class, builder, "orientedGoalCache", goals); set(BuilderProcess.class, builder, "activeCells", active);
        set(BuilderProcess.class, builder, "incorrectPositions", incorrect);
        var context = (BuilderProcess.BuilderCalculationContext) allocator.allocateInstance(BuilderProcess.BuilderCalculationContext.class);
        set(BuilderProcess.BuilderCalculationContext.class, context, "this$0", builder);
        set(CalculationContext.class, context, "bsi", blocks);
        BlockState desired = Blocks.PALE_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing);
        set(BuilderProcess.BuilderCalculationContext.class, context, "originX", CELL.x);
        set(BuilderProcess.BuilderCalculationContext.class, context, "originY", CELL.y);
        set(BuilderProcess.BuilderCalculationContext.class, context, "originZ", CELL.z);
        set(BuilderProcess.BuilderCalculationContext.class, context, "schematic", new AbstractSchematic(1, 1, 1) {
            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> available) { return desired; }
        });
        return new Fixture(builder, context, world, desired, CELL.relative(facing.getOpposite()), parks, memos, goals, active, incorrect);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args);
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
}
