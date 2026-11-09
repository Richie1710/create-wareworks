package dev.wareworks.gametest;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
import com.simibubi.create.content.logistics.item.filter.attribute.attributes.InTagAttribute;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PatternFilterCells;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Create filters as GameTests build them for filter cells of production patterns (ADR-082): configured exactly as
 * Create's own filter screens store them, so a test exercises the same data components a player's filter carries.
 */
final class FilterCells {
    /** The crafting table: four cells of one plank in the top-left 2 x 2. */
    static final int[] TABLE_CELLS = {0, 1, 3, 4};
    static final ItemKey TABLE = ItemKey.of(Items.CRAFTING_TABLE);

    private FilterCells() {
    }

    /** "Is tagged #minecraft:planks", the filter a recipe viewer lays for a tag ingredient. */
    static ItemKey anyPlank() {
        return PatternFilterCells.inTag(ItemTags.PLANKS);
    }

    /** A List Filter in allow mode with {@code items}, one per slot. */
    static ItemKey list(Item... items) {
        ItemStack filter = AllItems.FILTER.asStack();
        List<ItemStack> stacks = new ArrayList<>(items.length);
        for (Item item : items)
            stacks.add(new ItemStack(item));
        filter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(stacks));
        return ItemKey.of(filter);
    }

    /** An Attribute Filter in {@code mode} with {@code entries}. */
    static ItemKey attribute(AttributeFilterWhitelistMode mode, ItemAttribute.ItemAttributeEntry... entries) {
        ItemStack filter = AllItems.ATTRIBUTE_FILTER.asStack();
        filter.set(AllDataComponents.ATTRIBUTE_FILTER_WHITELIST_MODE, mode);
        filter.set(AllDataComponents.ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES, List.of(entries));
        return ItemKey.of(filter);
    }

    /** One "is tagged" entry of an Attribute Filter. */
    static ItemAttribute.ItemAttributeEntry tagged(TagKey<Item> tag, boolean inverted) {
        return new ItemAttribute.ItemAttributeEntry(new InTagAttribute(tag), inverted);
    }

    /**
     * A List Filter too large for a pattern cell ({@link PatternFilterCells#MAX_FILTER_BYTES}): eighteen oak planks, each
     * renamed with a long name, as a player can make with an anvil.
     */
    static ItemKey oversized() {
        ItemStack filter = AllItems.FILTER.asStack();
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < 18; slot++) {
            ItemStack named = new ItemStack(Items.OAK_PLANKS);
            named.set(DataComponents.CUSTOM_NAME, Component.literal(("Plank " + slot + " ").repeat(40)));
            stacks.add(named);
        }
        filter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(stacks));
        return ItemKey.of(filter);
    }

    /** An Attribute Filter in deny mode: anything that is not tagged {@code tag}. */
    static ItemKey denying(TagKey<Item> tag) {
        return attribute(AttributeFilterWhitelistMode.BLACKLIST, tagged(tag, false));
    }

    /** An Attribute Filter nobody configured: no attribute, so it is no filter cell. */
    static ItemKey blankAttribute() {
        return ItemKey.of(AllItems.ATTRIBUTE_FILTER.asStack());
    }

    /**
     * The crafting table in pattern slot {@code slot}: {@code filter} (or an exact item) in its four cells, one table per
     * run.
     */
    static void tablePattern(GameTestHelper helper, WarehouseProductionBlockEntity station, int slot, ItemKey filter) {
        for (int cell : TABLE_CELLS)
            helper.assertTrue(station.setPatternEntry(slot, cell, filter, 1), "the filter in cell " + cell);
        helper.assertTrue(station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, TABLE, 1), "a table");
        helper.assertValueEqual(station.patterns().isFilterAt(slot, TABLE_CELLS[0]), PatternFilterCells.isFilter(filter),
                "a configured filter is a filter cell, an item is exact");
    }
}
