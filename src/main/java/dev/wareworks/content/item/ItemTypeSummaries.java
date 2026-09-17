package dev.wareworks.content.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.InventorySummary;
import dev.wareworks.core.inventory.KeyCount;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Compact inventory summaries <b>by item type</b> for goggle tooltips, and their NBT form.
 * <p>
 * Goggle data is part of block entity update tags, which are also part of every chunk packet (2 MB client NBT quota), so
 * summaries are grouped by {@link Item} and written as registry ids and counts only: no item data components are ever
 * synced, and the tag size depends only on the number of top entries ({@code docs/architecture.md}, goggle data notes).
 * Used by the warehouse interface and the warehouse stations.
 * <p>
 * Writing and reading never throw; unknown items (e.g. of a removed mod) are dropped when reading. <b>Both</b> sides are
 * bounded by {@value #MAX_ENTRIES} entries: the writers never emit more, and {@link #read} stops there as well, so a
 * malfunctioning or modified server cannot make a client allocate and render an unbounded number of tooltip lines (the
 * same rule as {@code CraneGoggleInfo.MAX_HELD_ENTRIES} and {@code LocationReservationSummary.MAX_ENTRIES}).
 */
public final class ItemTypeSummaries {
    /**
     * Most entries written <b>and</b> read. The goggle tooltips of interface and stations show this many item types
     * ({@code WarehouseInterfaceBlockEntity.GOGGLE_TOP_ENTRIES}, {@code WarehouseStationBlockEntity.GOGGLE_TOP_ENTRIES}).
     */
    public static final int MAX_ENTRIES = 3;

    private static final String USED_SLOTS = "UsedSlots";
    private static final String TOTAL_SLOTS = "TotalSlots";
    private static final String DISTINCT_ITEMS = "DistinctItems";
    private static final String TOP = "Top";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";

    private ItemTypeSummaries() {
    }

    /** Summary of {@code snapshot} by item type, keeping the {@code maxEntries} largest item types. */
    public static InventorySummary<Item> of(InventorySnapshot<ItemKey> snapshot, int maxEntries) {
        return InventorySummary.of(snapshot, maxEntries, ItemKey::getItem);
    }

    /** Writes {@code summary} into {@code tag} (slot usage, distinct item types, top entries as ids). */
    public static void write(CompoundTag tag, InventorySummary<Item> summary) {
        tag.putInt(USED_SLOTS, summary.usedSlots());
        tag.putInt(TOTAL_SLOTS, summary.totalSlots());
        tag.putInt(DISTINCT_ITEMS, summary.distinctKeys());
        ListTag top = new ListTag();
        for (KeyCount<Item> entry : summary.topEntries()) {
            if (entry.key() == Items.AIR)
                continue; // unregistered items map to the default id (air), which would read back as a wrong item
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString(ITEM, BuiltInRegistries.ITEM.getKey(entry.key()).toString());
            entryTag.putLong(COUNT, entry.count());
            top.add(entryTag);
        }
        tag.put(TOP, top);
    }

    /**
     * Reads a summary written by {@link #write}. Never throws; invalid values are clamped or dropped, and at most
     * {@value #MAX_ENTRIES} entries are kept whatever the tag holds.
     */
    public static InventorySummary<Item> read(CompoundTag tag) {
        List<KeyCount<Item>> top = new ArrayList<>();
        ListTag list = tag.getList(TOP, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && top.size() < MAX_ENTRIES; i++) {
            CompoundTag entryTag = list.getCompound(i);
            long count = entryTag.getLong(COUNT);
            if (count < 0)
                continue;
            itemById(entryTag.getString(ITEM)).ifPresent(item -> top.add(new KeyCount<>(item, count)));
        }
        return InventorySummary.sanitized(tag.getInt(USED_SLOTS), tag.getInt(TOTAL_SLOTS), tag.getInt(DISTINCT_ITEMS),
                top);
    }

    /** The registry id of {@code item} as a string, for bounded client sync (never item components). */
    public static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** The registered item with the id written by {@link #itemId}; empty for invalid, unknown or air ids. */
    public static Optional<Item> itemById(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null)
            return Optional.empty();
        return BuiltInRegistries.ITEM.getOptional(location).filter(item -> item != Items.AIR);
    }
}
