package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.optimizer.OptimizerLimits;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The item rows of one Warehouse Optimizer (issue #46, ADR-079): the items a player wants moved up to better storage
 * locations, one per row, at most {@value #MAX_ROWS}.
 * <p>
 * <b>Ghosts, not items.</b> A row stores only an {@link ItemKey}, taken from what the player carries or from a
 * clipboard's entries, so editing the list can never consume, duplicate or swallow anything — the stock keeper's rule
 * rows work the same way ({@link StockKeeperRules}). An item stands in at most one row: Optimise moves each listed item
 * once, so a second row of the same item would say nothing.
 * <p>
 * <b>Bounded loading.</b> Save data is untrusted (block entity data on items, {@code /data merge}, schematics), so a load
 * reads at most {@value #MAX_ROWS} rows, drops an entry whose item cannot be decoded and keeps the first row of an item
 * that is listed twice. Reading never throws.
 * <p>
 * Server thread only, except for the read-only accessors a menu uses to build its screen state.
 */
public final class OptimizerRows {
    /** Rows of one optimizer: as many as one order may list ({@link OptimizerLimits#MAX_ROWS}). */
    public static final int MAX_ROWS = OptimizerLimits.MAX_ROWS;
    /** The NBT key the block entity stores {@link #save} under. */
    public static final String ROWS_TAG = "Items";

    private static final String ROW = "Row";
    private static final String ITEM = "Item";

    /** One entry per row; {@code null} for an empty row. */
    private final List<ItemKey> rows = new ArrayList<>(Collections.nCopies(MAX_ROWS, null));

    /** How many rows there are; always {@value #MAX_ROWS}. */
    public int size() {
        return rows.size();
    }

    /** The item of {@code row}, empty for an empty row or one out of range. */
    public Optional<ItemKey> keyAt(int row) {
        return inRange(row) ? Optional.ofNullable(rows.get(row)) : Optional.empty();
    }

    /** How many rows hold an item. */
    public int count() {
        int count = 0;
        for (ItemKey key : rows) {
            if (key != null)
                count++;
        }
        return count;
    }

    /** Whether no row holds an item. */
    public boolean isEmpty() {
        return count() == 0;
    }

    /** The listed items in row order: what Optimise asks the warehouse to move. */
    public List<ItemKey> keys() {
        List<ItemKey> keys = new ArrayList<>();
        for (ItemKey key : rows) {
            if (key != null)
                keys.add(key);
        }
        return keys;
    }

    /** Whether some row other than {@code exceptRow} already lists {@code key}. */
    public boolean listsElsewhere(ItemKey key, int exceptRow) {
        for (int row = 0; row < rows.size(); row++) {
            if (row != exceptRow && key.equals(rows.get(row)))
                return true;
        }
        return false;
    }

    /** What an edit of one row did. */
    public enum Edit {
        /** The row changed. */
        CHANGED,
        /** Nothing changed: the row was out of range, or already held exactly this. */
        UNCHANGED,
        /** Refused: another row already lists the item. */
        ALREADY_LISTED
    }

    /** Sets the item of {@code row}, or clears the row for {@code null}. */
    public Edit set(int row, @Nullable ItemKey key) {
        if (!inRange(row) || Objects.equals(rows.get(row), key))
            return Edit.UNCHANGED;
        if (key != null && listsElsewhere(key, row))
            return Edit.ALREADY_LISTED;
        rows.set(row, key);
        return Edit.CHANGED;
    }

    /**
     * What adding a clipboard's items did.
     *
     * @param added         items written into free rows
     * @param alreadyListed items the list already held, which were left out
     * @param noRoom        new items that found no free row
     */
    public record Added(int added, int alreadyListed, int noRoom) {
    }

    /**
     * Writes {@code keys} into the free rows, top to bottom, in their order: each item once, an item the list already
     * holds is left out, and what finds no free row is counted rather than dropped silently.
     */
    public Added addAll(List<ItemKey> keys) {
        Objects.requireNonNull(keys, "keys");
        int added = 0;
        int already = 0;
        int noRoom = 0;
        int free = 0;
        for (ItemKey key : keys) {
            if (key == null)
                continue;
            if (rows.contains(key)) {
                already++;
                continue;
            }
            while (free < rows.size() && rows.get(free) != null)
                free++;
            if (free >= rows.size()) {
                noRoom++;
                continue;
            }
            rows.set(free, key);
            added++;
        }
        return new Added(added, already, noRoom);
    }

    // --- persistence -------------------------------------------------------------------------------------------

    /**
     * NBT form: {@code Items: [{Row: int, Item: <ItemKey>}]}. Empty rows are left out, so an unconfigured optimizer saves
     * an empty list. Never throws.
     */
    public ListTag save(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int row = 0; row < rows.size(); row++) {
            ItemKey key = rows.get(row);
            if (key == null)
                continue;
            try {
                Tag keyTag = key.save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                    continue; // unencodable key, already logged by ItemKey
                CompoundTag rowTag = new CompoundTag();
                rowTag.putInt(ROW, row);
                rowTag.put(ITEM, keyTag);
                list.add(rowTag);
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save optimizer row {}", row, e);
            }
        }
        return list;
    }

    /** Replaces the rows with a save written by {@link #save}. Never throws; unreadable entries are skipped. */
    public void load(ListTag list, HolderLookup.Provider registries) {
        Collections.fill(rows, null);
        if (list == null)
            return;
        for (int i = 0; i < list.size() && i < MAX_ROWS; i++) {
            CompoundTag rowTag = list.getCompound(i);
            try {
                int row = rowTag.getInt(ROW);
                if (!inRange(row))
                    continue;
                Optional<ItemKey> key = ItemKey.load(registries, rowTag.get(ITEM));
                if (key.isEmpty() || rows.contains(key.get()))
                    continue; // unreadable, or listed in an earlier row already
                rows.set(row, key.get());
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Skipping unreadable optimizer row {}", rowTag, e);
            }
        }
    }

    private boolean inRange(int row) {
        return row >= 0 && row < rows.size();
    }

    @Override
    public String toString() {
        return "OptimizerRows[listed=" + count() + "]";
    }
}
