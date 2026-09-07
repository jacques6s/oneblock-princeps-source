/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.Princeps;
import princeps.api.process.PathingCommand;
import princeps.api.schematic.FillSchematic;
import princeps.api.utils.BetterBlockPos;
import princeps.api.utils.input.Input;
import princeps.behavior.InventoryBehavior;
import java.util.List;
import static org.junit.Assert.*;
import static princeps.process.BuilderCleanupClickableStanceTest.*;

/** Actual repair scan, vanilla geometry and native slot adapter; no game, inventory packet or ACK claim. */
public class ExcavationRepairSelectionTest {
    @BeforeClass public static void bootstrapRepair() throws Exception {
        ExcavationApproachTest.bootstrap();
        BuilderCleanupClickableStanceTest.bootstrap();
        var singleton = net.minecraft.client.Minecraft.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        var minecraft = allocate(net.minecraft.client.Minecraft.class);
        var directory = java.nio.file.Files.createTempDirectory("princeps-repair-selection-test-");
        java.nio.file.Files.createDirectories(directory.resolve("princeps"));
        java.nio.file.Files.writeString(directory.resolve("princeps/settings.txt"), "");
        set(minecraft, "gameDirectory", directory.toFile());
        singleton.set(null, minecraft);
        try { Princeps.settings(); } finally { singleton.set(null, previous); }
    }

    @Test public void unplaceableRepairCannotSelectFillerBeforeTheSameTicksMiningAction() throws Exception {
        var old = Princeps.settings().acceptableThrowawayItems.value;
        int area = Princeps.settings().areaBreakSize.value;
        boolean allow = Princeps.settings().allowInventory.value;
        try {
            Princeps.settings().acceptableThrowawayItems.value = List.of(Items.DEEPSLATE);
            Princeps.settings().areaBreakSize.value = 3;
            Princeps.settings().allowInventory.value = true;
            var f = repairFixture(false);
            f.world().obstructed = true;
            var inventory = f.avatar().getInventory();
            for (int tick = 1; tick <= 22; tick++) {
                set(f.builder(), "buildTick", (long) tick);
                assertNull(scan(f));
                assertEquals("13fa's failed candidate must not take the mining slot", 0, inventory.getSelectedSlot());
                assertTrue((boolean) method("snakeToolReady", net.minecraft.world.level.block.state.BlockState.class,
                        boolean.class).invoke(f.builder(), Blocks.STONE.defaultBlockState(), false));
            }
            assertFalse(f.inputs().getOrDefault(Input.CLICK_RIGHT, false));
            assertTrue(((ExcavationRepairAim) read(f.builder(), "excavationRepairAim"))
                    .heldFace(f.world(), new BetterBlockPos(13, 20, 11)).isEmpty());
        } finally { Princeps.settings().acceptableThrowawayItems.value = old; Princeps.settings().areaBreakSize.value = area; Princeps.settings().allowInventory.value = allow; }
    }

    @Test public void unplaceableBagOnlyCandidateCannotEvenQueueAnInventoryMove() throws Exception {
        var old = Princeps.settings().acceptableThrowawayItems.value;
        int area = Princeps.settings().areaBreakSize.value;
        boolean allow = Princeps.settings().allowInventory.value;
        try {
            Princeps.settings().acceptableThrowawayItems.value = List.of(Items.DEEPSLATE);
            Princeps.settings().areaBreakSize.value = 3;
            Princeps.settings().allowInventory.value = true;
            var f = repairFixture(true); f.world().obstructed = true;
            assertNull(scan(f));
            var nativeInventory = ((Princeps) read(f.builder(), "princeps")).getInventoryBehavior();
            assertNull("candidate discovery cannot start a queued fetch", read(nativeInventory, "lastTickRequestedMove"));
            assertEquals(0, f.avatar().getInventory().getSelectedSlot());
        } finally { Princeps.settings().acceptableThrowawayItems.value = old; Princeps.settings().areaBreakSize.value = area; Princeps.settings().allowInventory.value = allow; }
    }

    @Test public void admittedRepairOwnsItsTickAndBagRefillWaitDoesNotFallThroughToMining() throws Exception {
        var old = Princeps.settings().acceptableThrowawayItems.value;
        int area = Princeps.settings().areaBreakSize.value;
        boolean allow = Princeps.settings().allowInventory.value;
        try {
            Princeps.settings().acceptableThrowawayItems.value = List.of(Items.DEEPSLATE);
            Princeps.settings().areaBreakSize.value = 3;
            Princeps.settings().allowInventory.value = true;
            var ready = repairFixture(false);
            assertNotNull("a geometrically admitted repair takes the tick", scan(ready));
            assertEquals(5, ready.avatar().getInventory().getSelectedSlot());
            assertTrue(ready.inputs().getOrDefault(Input.SNEAK, false));
            var refill = repairFixture(true);
            var inventory = ((Princeps) read(refill.builder(), "princeps")).getInventoryBehavior();
            set(inventory, "ticksSinceLastInventoryMove", -1);
            assertNotNull("waiting for an admitted repair's refill cannot return to mining", scan(refill));
            assertArrayEquals(new int[]{12, 5}, (int[]) read(inventory, "lastTickRequestedMove"));
            assertEquals("no slot selection before the inventory adapter acknowledges its swap", 0,
                    refill.avatar().getInventory().getSelectedSlot());
        } finally { Princeps.settings().acceptableThrowawayItems.value = old; Princeps.settings().areaBreakSize.value = area; Princeps.settings().allowInventory.value = allow; }
    }

    private static Fixture repairFixture(boolean bagOnly) throws Exception {
        Fixture f = fixture(); f.pose(.5, .5);
        var builder = f.builder();
        set(f.avatar(), "dimensions", EntityDimensions.scalable(.6F, 1.8F));
        Inventory stock = new Inventory(f.avatar(), new EntityEquipment());
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal("Shard Pickaxe"));
        stock.setItem(0, pick); stock.setItem(bagOnly ? 12 : 5, new ItemStack(Items.DEEPSLATE, 64));
        set(f.avatar(), "inventory", stock);
        set(builder, "excavating", true); set(builder, "cleanupEscape", null);
        set(builder, "abortPending", princeps.api.process.IBuilderProcess.Ending.RUNNING);
        set(builder, "origin", new BlockPos(14, 19, 8));
        set(builder, "schematic", new FillSchematic(7, 4, 7, Blocks.AIR.defaultBlockState()));
        set(builder, "snakeHead", new BetterBlockPos(18, 20, 11));
        set(builder, "snakeBandFloor", 19); set(builder, "snakeBandTop", 21);
        set(builder, "excavationApproach", new ExcavationApproach());
        set(builder, "excavationRepairAim", new ExcavationRepairAim());
        var engine = (Princeps) read(builder, "princeps");
        set(engine.getLookBehavior(), "ctx", read(builder, "ctx"));
        set(engine, "builderProcess", builder);
        var inventory = allocate(InventoryBehavior.class);
        set(inventory, "ctx", read(builder, "ctx")); set(inventory, "princeps", engine);
        set(inventory, "builderLockedHotbarSlot", -1);
        set(engine, "inventoryBehavior", inventory);
        return f;
    }

    private static PathingCommand scan(Fixture f) throws Exception {
        return (PathingCommand) method("excavationIntegrityPlacementCommand",
                BuilderProcess.BuilderCalculationContext.class, boolean.class).invoke(f.builder(), f.context(), true);
    }
}
