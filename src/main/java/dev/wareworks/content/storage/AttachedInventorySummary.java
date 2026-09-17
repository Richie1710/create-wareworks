package dev.wareworks.content.storage;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.InventorySummary;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * What the goggle tooltip of a warehouse interface shows about its attached inventory: whether one is attached, the
 * inventory block and a compact {@link InventorySummary} of its contents <b>by item type</b>.
 * <p>
 * Built on the server from a live snapshot and synced to clients in the block entity's update tag, which is also part
 * of every chunk packet. The contents are therefore grouped by {@link Item} and synced as registry ids only
 * ({@link ItemTypeSummaries}): item data components (container contents, book pages, custom names, ...) are never sent,
 * so the tag stays a few hundred bytes whatever the inventory holds, and items that differ only in components (damaged
 * or enchanted tools) form one line.
 * <p>
 * Records compare by value, so the server only syncs when something visible changed. Reading never throws: invalid or
 * unknown data (e.g. an item or block of a removed mod) is dropped.
 *
 * @param hasInventory  whether an item handler was found at the attached position
 * @param attachedBlock registry id of the attached inventory block, or {@code null} without inventory
 * @param contents      summary of the inventory contents by item type ({@link InventorySummary#empty()} without
 *                      inventory)
 */
public record AttachedInventorySummary(boolean hasInventory, @Nullable ResourceLocation attachedBlock,
                                       InventorySummary<Item> contents) {
    /** No inventory attached. */
    public static final AttachedInventorySummary NONE =
            new AttachedInventorySummary(false, null, InventorySummary.empty());

    private static final String HAS_INVENTORY = "HasInventory";
    private static final String BLOCK = "Block";

    public AttachedInventorySummary {
        if (contents == null)
            contents = InventorySummary.empty();
        if (!hasInventory) {
            attachedBlock = null;
            contents = InventorySummary.empty();
        }
    }

    /** Summary of an attached inventory with the given contents. */
    public static AttachedInventorySummary attached(ResourceLocation block, InventorySummary<Item> contents) {
        return new AttachedInventorySummary(true, block, contents);
    }

    /** Summary of an attached inventory from a snapshot, keeping the {@code maxEntries} largest item types. */
    public static AttachedInventorySummary attached(ResourceLocation block, InventorySnapshot<ItemKey> snapshot,
                                                    int maxEntries) {
        return attached(block, ItemTypeSummaries.of(snapshot, maxEntries));
    }

    /** Display name of the attached block, if it is known in this game instance. */
    public Optional<Component> attachedBlockName() {
        if (attachedBlock == null)
            return Optional.empty();
        return BuiltInRegistries.BLOCK.getOptional(attachedBlock).map(Block::getName);
    }

    /** Writes this summary into {@code tag}. Never throws; entries without a registry id are skipped. */
    public void write(CompoundTag tag) {
        tag.putBoolean(HAS_INVENTORY, hasInventory);
        if (!hasInventory)
            return;
        if (attachedBlock != null)
            tag.putString(BLOCK, attachedBlock.toString());
        ItemTypeSummaries.write(tag, contents);
    }

    /** Reads a summary written by {@link #write}. Never throws; returns {@link #NONE} for missing data. */
    public static AttachedInventorySummary read(CompoundTag tag) {
        if (!tag.getBoolean(HAS_INVENTORY))
            return NONE;
        ResourceLocation block = tag.contains(BLOCK, Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString(BLOCK))
                : null;
        return new AttachedInventorySummary(true, block, ItemTypeSummaries.read(tag));
    }
}
