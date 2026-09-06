package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.utils.ToolSet;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class OrdinaryMiningToolTest {
    private static final Map<Holder.Reference<Item>, List<TagKey<Item>>> ORIGINAL_TAGS = new HashMap<>();
    private static Method bindTags;

    @BeforeClass
    public static void bootstrapMinecraft() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Headless bootstrap does not load item datapacks. Bind the four vanilla tag memberships and supply
        // their tested Tool component rules below. Selection and material speed still use real ItemStacks,
        // ItemStack.is(tag), vanilla Tool rules, block hardness/drops and ToolSet.calculateSpeedVsBlock.
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        }
        bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
        bindTags.setAccessible(true);
        bind(Items.DIAMOND_PICKAXE, ItemTags.PICKAXES);
        bind(Items.DIAMOND_AXE, ItemTags.AXES);
        bind(Items.DIAMOND_SHOVEL, ItemTags.SHOVELS);
        bind(Items.DIAMOND_HOE, ItemTags.HOES);
    }

    private static void bind(Item item, TagKey<Item> tag) throws ReflectiveOperationException {
        Holder.Reference<Item> holder = (Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(item);
        List<TagKey<Item>> original = holder.tags().toList();
        ORIGINAL_TAGS.put(holder, original);
        List<TagKey<Item>> tagged = new ArrayList<>(original);
        if (!tagged.contains(tag)) tagged.add(tag);
        bindTags.invoke(holder, tagged);
    }

    @AfterClass
    public static void restoreTags() throws ReflectiveOperationException {
        if (bindTags != null) {
            for (var entry : ORIGINAL_TAGS.entrySet()) bindTags.invoke(entry.getKey(), entry.getValue());
        }
    }

    private static ItemStack tool(Item item, String name, float speed, Block effectiveBlock) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.MAX_DAMAGE, 1561);
        stack.set(DataComponents.DAMAGE, 0);
        stack.set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        stack.set(DataComponents.TOOL, new Tool(List.of(Tool.Rule.minesAndDrops(
                HolderSet.direct(BuiltInRegistries.BLOCK.wrapAsHolder(effectiveBlock)), speed)), 1.0F, 1, true));
        return stack;
    }

    private static List<ItemStack> hotbar() {
        return List.of(
                tool(Items.DIAMOND_PICKAXE, "Shard Pickaxe", 4096.0F, Blocks.STONE),
                tool(Items.DIAMOND_PICKAXE, "Diamond Pickaxe", 8.0F, Blocks.STONE),
                tool(Items.DIAMOND_AXE, "Diamond Axe", 8.0F, Blocks.OAK_FENCE),
                tool(Items.DIAMOND_SHOVEL, "Diamond Shovel", 8.0F, Blocks.GRAVEL),
                tool(Items.DIAMOND_HOE, "Diamond Hoe", 8.0F, Blocks.OAK_LEAVES));
    }

    @Test
    public void woodStoneGravelAndLeavesChooseTheirOrdinaryMaterialTool() {
        List<ItemStack> slots = hotbar();
        Block[] materials = {Blocks.STONE, Blocks.OAK_FENCE, Blocks.GRAVEL, Blocks.OAK_LEAVES};
        for (int i = 0; i < materials.length; i++) {
            var state = materials[i].defaultBlockState();
            int expected = i + 1;
            assertTrue("the fixture must model the material speed advantage",
                    ToolSet.calculateSpeedVsBlock(slots.get(expected), state)
                            > ToolSet.calculateSpeedVsBlock(slots.get(expected == 1 ? 2 : 1), state));
            assertEquals(materials[i].toString(), expected,
                    BuilderProcess.ordinaryMiningToolSlot(slots, state, true, 10));
        }
    }

    @Test
    public void evenAFasterLowerSlotShardCannotWinAnIndividualCut() {
        List<ItemStack> slots = hotbar();
        assertTrue(ToolSet.calculateSpeedVsBlock(slots.get(0), Blocks.STONE.defaultBlockState())
                > ToolSet.calculateSpeedVsBlock(slots.get(1), Blocks.STONE.defaultBlockState()));
        assertEquals(1, BuilderProcess.ordinaryMiningToolSlot(slots, Blocks.STONE.defaultBlockState(), false, 10));
        assertEquals(-1, BuilderProcess.ordinaryMiningToolSlot(List.of(slots.getFirst()),
                Blocks.STONE.defaultBlockState(), false, 10));
    }

    @Test
    public void wearBoundaryMatchesNavigationAndDisablingItemSaverRestoresTheFastTool() {
        List<ItemStack> slots = hotbar();
        ItemStack axe = slots.get(2);
        axe.setDamageValue(axe.getMaxDamage() - 11);
        assertEquals(2, BuilderProcess.ordinaryMiningToolSlot(slots, Blocks.OAK_FENCE.defaultBlockState(), true, 10));
        axe.setDamageValue(axe.getMaxDamage() - 10);
        assertEquals(1, BuilderProcess.ordinaryMiningToolSlot(slots, Blocks.OAK_FENCE.defaultBlockState(), true, 10));
        assertEquals(2, BuilderProcess.ordinaryMiningToolSlot(slots, Blocks.OAK_FENCE.defaultBlockState(), false, 10));
        for (ItemStack stack : slots) stack.setDamageValue(stack.getMaxDamage() - 10);
        assertEquals(-1, BuilderProcess.ordinaryMiningToolSlot(slots, Blocks.STONE.defaultBlockState(), true, 10));
    }

    @Test
    public void hotbarOwnershipAndMiningTagsExcludeBackpackToolsAndFastNonTools() {
        ItemStack pick = hotbar().get(1);
        ItemStack axe = hotbar().get(2);
        List<ItemStack> inventory = new ArrayList<>();
        inventory.add(pick);
        for (int i = 1; i < 9; i++) inventory.add(ItemStack.EMPTY);
        inventory.add(axe);
        assertEquals(0, BuilderProcess.ordinaryMiningToolSlot(inventory, Blocks.OAK_FENCE.defaultBlockState(), true, 10));
        ItemStack fastSword = tool(Items.DIAMOND_SWORD, "Diamond Sword", 4096.0F, Blocks.OAK_FENCE);
        assertEquals(1, BuilderProcess.ordinaryMiningToolSlot(List.of(fastSword, axe),
                Blocks.OAK_FENCE.defaultBlockState(), true, 10));
        assertEquals(-1, BuilderProcess.ordinaryMiningToolSlot(List.of(fastSword, ItemStack.EMPTY),
                Blocks.OAK_FENCE.defaultBlockState(), true, 10));
    }
}
