/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.*;
import princeps.utils.ExcavationFiller;
import java.util.*;
import static org.junit.Assert.*;
import static princeps.process.PlatformTraverseFixture.*;

/** Actual repair picker and its executor's slot policy against acknowledged/refused inventory swaps. */
public class ExcavationFillerInventoryTest {
    private static List<Item> allowed;
    @BeforeClass public static void bootstrap() throws Exception {
        ExcavationApproachTest.bootstrap(); PlatformTraverseFixture.bootstrap();
        allowed = List.of(Items.STONE, Items.COBBLESTONE, Items.DEEPSLATE, Items.COBBLED_DEEPSLATE,
                Items.SAND, Items.OAK_SLAB);
    }

    @Test public void repairChoosesDeepslateInSlotSixOverEarlierStoneAndBagStock() throws Exception {
        Fixture f = fixture();
        f.world.inventory.setItem(3, new ItemStack(Items.STONE, 64));
        f.world.inventory.setItem(5, new ItemStack(Items.DEEPSLATE, 64));
        f.world.inventory.setItem(12, new ItemStack(Items.STONE, 64));
        assertEquals(Blocks.DEEPSLATE, f.fill().getBlock());
        assertTrue(f.select(true, false));
        assertEquals(5, f.world.inventory.getSelectedSlot());
        assertEquals(0, f.swaps.size());
        assertEquals(Items.GOLDEN_APPLE, f.world.inventory.getItem(7).getItem());
    }

    @Test public void depletedFillerRefillsOnlySlotSixAndPreservesBothAppleStacks() throws Exception {
        Fixture f = fixture();
        f.world.inventory.setItem(12, new ItemStack(Items.COBBLED_DEEPSLATE, 32));
        f.world.inventory.setItem(13, new ItemStack(Items.DEEPSLATE, 17));
        assertTrue(f.select(true, false));
        assertEquals(List.of("13->5"), f.swaps);
        assertEquals(Items.DEEPSLATE, f.world.inventory.getItem(5).getItem());
        assertEquals(Items.EXPERIENCE_BOTTLE, f.world.inventory.getItem(13).getItem());
        assertEquals(Items.GOLDEN_APPLE, f.world.inventory.getItem(6).getItem());
        assertEquals(Items.GOLDEN_APPLE, f.world.inventory.getItem(7).getItem());
        f.world.inventory.setItem(5, ItemStack.EMPTY);
        assertTrue(f.select(true, false));
        assertEquals(List.of("13->5", "12->5"), f.swaps);
    }

    @Test public void fallbackKeepsSlotSixOnTiesAndRejectsUnsuitableBlocks() throws Exception {
        Fixture f = fixture();
        f.world.inventory.setItem(3, new ItemStack(Items.STONE, 64));
        f.world.inventory.setItem(5, new ItemStack(Items.COBBLESTONE, 64));
        f.world.inventory.setItem(10, new ItemStack(Items.SAND, 64));
        f.world.inventory.setItem(11, new ItemStack(Items.OAK_SLAB, 64));
        assertEquals(Blocks.COBBLESTONE, f.fill().getBlock());
        f.world.inventory.setItem(3, ItemStack.EMPTY); f.world.inventory.setItem(5, ItemStack.EMPTY);
        assertNull(f.fill());
    }

    @Test public void refusedOrUnacknowledgedSwapCannotReportAReadyHand() throws Exception {
        Fixture f = fixture(); f.world.inventory.setItem(12, new ItemStack(Items.DEEPSLATE, 64));
        f.grantSwap = false;
        assertFalse(f.select(true, false)); assertEquals(0, f.world.inventory.getSelectedSlot());
        f.grantSwap = true; f.applySwap = false;
        assertFalse(f.select(true, false)); assertEquals(0, f.world.inventory.getSelectedSlot());
        assertEquals(Items.GOLDEN_APPLE, f.world.inventory.getItem(7).getItem());
    }

    @Test public void pausedHandsAndAvailabilityQueriesNeverSwapOrSelect() throws Exception {
        Fixture f = fixture(); f.world.inventory.setItem(12, new ItemStack(Items.DEEPSLATE, 64));
        assertTrue(f.select(false, false)); assertFalse(f.select(true, true));
        assertTrue(f.swaps.isEmpty()); assertEquals(0, f.world.inventory.getSelectedSlot());
    }

    @Test public void inventoryDisabledDoesNotEvictAnotherHotbarSlot() throws Exception {
        Fixture f = fixture(); f.world.inventory.setItem(3, new ItemStack(Items.DEEPSLATE, 64));
        assertFalse("availability must not license a bridge whose slot-six swap is forbidden",
                ExcavationFiller.select(f.world.inventory.getNonEquipmentItems(),
                        stack -> stack.getItem() == Items.DEEPSLATE, false, false, false,
                        source -> false, slot -> fail("query")));
        assertFalse(ExcavationFiller.select(f.world.inventory.getNonEquipmentItems(),
                stack -> stack.getItem() == Items.DEEPSLATE, false, true, false,
                source -> { throw new AssertionError("inventory disabled"); }, slot -> fail("not ready")));
        f.world.inventory.setItem(5, new ItemStack(Items.DEEPSLATE, 64));
        assertTrue(ExcavationFiller.select(f.world.inventory.getNonEquipmentItems(),
                stack -> stack.getItem() == Items.DEEPSLATE, false, true, false,
                source -> { throw new AssertionError("already ready"); }, f.world.inventory::setSelectedSlot));
        assertEquals(5, f.world.inventory.getSelectedSlot());
    }

    @Test public void staleQueuedFetchCannotEvictFreshPreferredFiller() throws Exception {
        Fixture f = fixture(); f.world.inventory.setItem(12, new ItemStack(Items.STONE, 64));
        assertTrue(ExcavationFiller.currentFetch(f.world.inventory.getNonEquipmentItems(), 12, Items.STONE));
        f.world.inventory.setItem(5, new ItemStack(Items.DEEPSLATE, 64));
        assertFalse(ExcavationFiller.currentFetch(f.world.inventory.getNonEquipmentItems(), 12, Items.DEEPSLATE));
        f.world.inventory.setItem(12, new ItemStack(Items.DEEPSLATE, 64));
        assertFalse(ExcavationFiller.currentFetch(f.world.inventory.getNonEquipmentItems(), 12, Items.DEEPSLATE));
        f.world.inventory.setItem(5, ItemStack.EMPTY);
        assertTrue(ExcavationFiller.currentFetch(f.world.inventory.getNonEquipmentItems(), 12, Items.DEEPSLATE));
        Object job = new Object();
        assertTrue(ExcavationFiller.currentSession(job, job, false));
        assertFalse("pause revokes a queued write", ExcavationFiller.currentSession(job, job, true));
        assertFalse("cancel revokes a queued write", ExcavationFiller.currentSession(job, null, false));
        assertFalse("replacement cannot inherit old slot coordinates", ExcavationFiller.currentSession(job, new Object(), false));
    }

    @Test public void deepslateTextureAxesAreAcceptedOnlyForExcavationFiller() {
        BlockState desired = Blocks.DEEPSLATE.defaultBlockState();
        for (var axis : net.minecraft.core.Direction.Axis.values()) {
            BlockState actual = desired.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, axis);
            assertTrue(BuilderProcess.axisIndependentExcavationFiller(true, actual, desired));
            assertFalse("ordinary blueprints still use exact-state comparison",
                    BuilderProcess.axisIndependentExcavationFiller(false, actual, desired));
        }
        assertFalse(BuilderProcess.axisIndependentExcavationFiller(true, Blocks.STONE.defaultBlockState(), desired));
        assertFalse(BuilderProcess.axisIndependentExcavationFiller(true, null, desired));
    }

    @Test public void nativePlacementAndExternalSupplyHaveDistinctFiniteOwnership() throws Exception {
        Fixture f = fixture();
        var builder = f.world.builder;
        set(builder, "excavating", true);
        set(builder, "abortPending", princeps.api.process.IBuilderProcess.Ending.RUNNING);
        set(builder, "navigationScaffolds", new BuilderScaffoldLedger());
        var engine = (princeps.Princeps) get(builder, "princeps");
        var manager = allocate(princeps.utils.PathingControlManager.class);
        set(manager, "inControlThisTick", builder); set(engine, "pathingControlManager", manager);
        var input = allocate(princeps.utils.InputOverrideHandler.class);
        var helper = allocate(princeps.utils.BlockPlaceHelper.class);
        set(input, "inputForceStateMap", new HashMap<>());
        set(input, "blockPlaceHelper", helper); set(engine, "inputOverrideHandler", input);
        assertFalse(builder.isExcavationPlacementPending());
        helper.expectExcavationIntegrityPlacement(TARGET.below(), net.minecraft.core.Direction.UP, TARGET, 5,
                Items.DEEPSLATE, () -> true);
        assertTrue(builder.isExcavationPlacementPending());
        helper.clearExpectedPlacement();
        assertFalse("executed/canceled expectation does not create sticky ownership", builder.isExcavationPlacementPending());
        input.setInputForceState(princeps.api.utils.input.Input.CLICK_RIGHT, true);
        assertTrue(builder.isExcavationPlacementPending());
        set(manager, "inControlThisTick", null);
        assertFalse("foreign owner never inherits this action", builder.isExcavationPlacementPending());
        set(builder, "paused", true);
        builder.setExcavationExternalInventoryOwned(true);
        assertTrue(builder.isExcavationExternalInventoryOwned());
        assertFalse(builder.isExcavationPlacementPending());
        builder.setExcavationExternalInventoryOwned(false);
        assertFalse(builder.isExcavationExternalInventoryOwned());
        set(builder, "excavating", false);
        builder.setExcavationExternalInventoryOwned(true);
        assertFalse("ordinary modules cannot claim AutoDig's supply handoff", builder.isExcavationExternalInventoryOwned());
    }

    @Test public void actualQueuedFetchAdmissionRunsBeforeDisabledUpkeepAndCannotResumeOldJobs() throws Exception {
        Fixture f = fixture();
        var builder = f.world.builder;
        set(builder, "excavating", true);
        set(builder, "abortPending", princeps.api.process.IBuilderProcess.Ending.RUNNING);
        var engine = (princeps.Princeps) get(builder, "princeps");
        set(engine, "builderProcess", builder);
        var inventory = allocate(princeps.behavior.InventoryBehavior.class);
        set(inventory, "princeps", engine);
        var admission = princeps.behavior.InventoryBehavior.class.getDeclaredMethod("discardInactiveExcavationFetch");
        admission.setAccessible(true);
        Object job = builder.excavationInventorySession();
        for (int mode = 0; mode < 3; mode++) {
            set(inventory, "lastTickRequestedMove", new int[]{12, 5});
            set(inventory, "excavationFetchSession", job);
            set(builder, "paused", mode == 0);
            if (mode == 1) set(builder, "excavating", false);
            if (mode == 2) {
                set(builder, "excavating", true);
                set(builder, "schematic", new princeps.api.schematic.FillSchematic(3, 1, 1, Blocks.AIR.defaultBlockState()));
            }
            admission.invoke(inventory);
            assertNull("pause, cancellation and replacement discard the queued packet", get(inventory, "lastTickRequestedMove"));
            assertNull(get(inventory, "excavationFetchSession"));
        }
        set(inventory, "lastTickRequestedMove", new int[]{12, 5});
        admission.invoke(inventory);
        assertNull("an untagged older ordinary-builder write cannot take AutoDig's slot", get(inventory, "lastTickRequestedMove"));
    }

    private static Fixture fixture() throws Exception {
        Fixture f = new Fixture(); f.world = new PlatformTraverseFixture();
        for (int i = 0; i < 36; i++) f.world.inventory.setItem(i, ItemStack.EMPTY);
        f.world.inventory.setItem(5, new ItemStack(Items.EXPERIENCE_BOTTLE, 64));
        f.world.inventory.setItem(6, new ItemStack(Items.GOLDEN_APPLE, 64));
        f.world.inventory.setItem(7, new ItemStack(Items.GOLDEN_APPLE, 64));
        return f;
    }
    private static final class Fixture {
        PlatformTraverseFixture world; List<String> swaps = new ArrayList<>();
        boolean grantSwap = true, applySwap = true;
        BlockState fill() { return world.builder.excavationFillerState(TARGET, allowed, true); }
        boolean select(boolean select, boolean busy) {
            BlockState fill = fill();
            return fill != null && ExcavationFiller.select(world.inventory.getNonEquipmentItems(),
                    stack -> stack.getItem() == fill.getBlock().asItem(), true, select, busy, source -> {
                        swaps.add(source + "->5");
                        if (!grantSwap) return false;
                        if (applySwap) {
                            ItemStack out = world.inventory.getItem(5);
                            world.inventory.setItem(5, world.inventory.getItem(source));
                            world.inventory.setItem(source, out);
                        }
                        return true;
                    }, world.inventory::setSelectedSlot);
        }
    }
}
