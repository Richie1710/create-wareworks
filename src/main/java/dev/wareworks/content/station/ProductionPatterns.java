package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The editable pattern slots of one warehouse production station ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * <b>A pattern is authored as a 3 x 3 grid plus a result</b>, because that is how a player already reads a recipe:
 * {@value ProductionPattern#GRID_SIZE} cells with indices {@code 0..8} (row-major, like a crafting grid) and the
 * result at {@value #RESULT_ENTRY}. What the planner gets is something else — {@link #patternAt} merges the cells that
 * name the same item into one ingredient ({@link ProductionPattern#fromGrid}), so a row of three planks is "3 planks"
 * and costs one crane trip, not three. The grid is for reading; the multiset is for planning.
 * <p>
 * A slot is a <b>draft</b>: a player fills it cell by cell, so it is regularly incomplete (an ingredient but no result
 * yet). {@link #patterns()} therefore returns only the slots that are complete and valid, and the strict, pure
 * {@link ProductionPattern} never has to represent a half-written pattern.
 * <p>
 * <b>Nothing here consumes items.</b> The entries are ghost items: only the {@link ItemKey} and a count are stored, set
 * from what a player carries or clicks. Editing a pattern never takes an item out of anyone's inventory.
 * <p>
 * <b>Validation happens on the way in</b> ({@link #setEntry}), not on the way out. Only one rule can be broken by a
 * single click: a result that is also one of its own ingredients, which {@link ProductionPattern} refuses because
 * stage 1 could never resolve it. Repeating an item across grid cells is <i>allowed</i> — it is the whole point of a
 * grid — and is what the merge above turns into one larger ingredient.
 * <p>
 * <b>Bounded loading.</b> Save data is untrusted (block entity data on items, {@code /data merge}, schematics), so a
 * load reads at most {@value #MAX_SLOTS} slots with at most {@value #ENTRIES_PER_PATTERN} entries each and clamps every
 * count; reading never throws, and an entry whose item cannot be decoded is dropped like any other unreadable item.
 * <p>
 * Server thread only.
 */
public final class ProductionPatterns {
    /** Hard cap on pattern slots, whatever {@code maxProductionPatterns} says. */
    public static final int MAX_SLOTS = 8;
    /** Cells of the authoring grid: indices {@code 0..}{@value ProductionPattern#GRID_SIZE}{@code -1}. */
    public static final int GRID_CELLS = ProductionPattern.GRID_SIZE;
    /** Entries of one pattern: its {@value #GRID_CELLS} grid cells plus the result. */
    public static final int ENTRIES_PER_PATTERN = GRID_CELLS + 1;
    /** The entry index of the result; {@code 0..}{@value #GRID_CELLS}{@code -1} are the grid cells. */
    public static final int RESULT_ENTRY = GRID_CELLS;

    /** The NBT key the block entity stores {@link #save} under. */
    public static final String PATTERNS_TAG = "Patterns";

    private static final String SLOT = "Slot";
    private static final String ENTRIES = "Entries";
    private static final String ENTRY = "Entry";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";

    /** One pattern slot: {@value #GRID_CELLS} grid cells and one result, each optional. */
    private static final class Draft {
        private final ItemKey[] keys = new ItemKey[ENTRIES_PER_PATTERN];
        private final int[] counts = new int[ENTRIES_PER_PATTERN];

        private Draft() {
            Arrays.fill(counts, ProductionEntry.MIN_COUNT);
        }

        private boolean isEmpty() {
            for (ItemKey key : keys) {
                if (key != null)
                    return false;
            }
            return true;
        }

        private void clear() {
            Arrays.fill(keys, null);
            Arrays.fill(counts, ProductionEntry.MIN_COUNT);
        }
    }

    private final List<Draft> slots;

    /** @param slotCount number of pattern slots ({@code maxProductionPatterns}), clamped to 1..{@value #MAX_SLOTS} */
    public ProductionPatterns(int slotCount) {
        int count = Math.min(Math.max(1, slotCount), MAX_SLOTS);
        List<Draft> drafts = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            drafts.add(new Draft());
        this.slots = drafts;
    }

    /** Number of pattern slots this station offers. */
    public int size() {
        return slots.size();
    }

    /** Whether no slot holds anything at all. */
    public boolean isEmpty() {
        for (Draft draft : slots) {
            if (!draft.isEmpty())
                return false;
        }
        return true;
    }

    /** The item of one entry, or empty when that entry is not set. */
    public Optional<ItemKey> keyAt(int pattern, int entry) {
        if (!inRange(pattern, entry))
            return Optional.empty();
        return Optional.ofNullable(slots.get(pattern).keys[entry]);
    }

    /** The count of one entry (at least 1, also for an unset entry). */
    public int countAt(int pattern, int entry) {
        return inRange(pattern, entry) ? slots.get(pattern).counts[entry] : ProductionEntry.MIN_COUNT;
    }

    /** How many grid cells of {@code pattern} are filled. */
    public int filledCells(int pattern) {
        if (pattern < 0 || pattern >= slots.size())
            return 0;
        Draft draft = slots.get(pattern);
        int filled = 0;
        for (int cell = 0; cell < GRID_CELLS; cell++) {
            if (draft.keys[cell] != null)
                filled++;
        }
        return filled;
    }

    /**
     * Sets or clears one entry.
     *
     * @param pattern the pattern slot
     * @param entry   {@code 0..}{@value #GRID_CELLS}{@code -1} for a grid cell, {@value #RESULT_ENTRY} for the result
     * @param key     the item, or {@code null} to clear the entry
     * @param count   items per run, clamped to {@link ProductionEntry#clampCellCount}
     * @return whether the entry changed; false for an out-of-range index or a refused item (a result that is one of
     * its own ingredients, in either direction)
     */
    public boolean setEntry(int pattern, int entry, @Nullable ItemKey key, int count) {
        if (!inRange(pattern, entry))
            return false;
        Draft draft = slots.get(pattern);
        int clamped = ProductionEntry.clampCellCount(count);
        if (key == null) {
            if (draft.keys[entry] == null && draft.counts[entry] == clamped)
                return false;
            draft.keys[entry] = null;
            draft.counts[entry] = ProductionEntry.MIN_COUNT;
            return true;
        }
        if (!accepts(draft, entry, key))
            return false;
        if (key.equals(draft.keys[entry]) && draft.counts[entry] == clamped)
            return false;
        draft.keys[entry] = key;
        draft.counts[entry] = clamped;
        return true;
    }

    /**
     * Whether {@code key} may go into {@code entry}. The only rule a single click can break is
     * {@link ProductionPattern}'s "a pattern must not produce one of its own ingredients": a grid cell may not hold the
     * result, and the result may not hold what a grid cell holds. Repeats <b>between grid cells</b> are allowed and
     * merge into one larger ingredient.
     */
    private static boolean accepts(Draft draft, int entry, ItemKey key) {
        if (entry == RESULT_ENTRY) {
            for (int cell = 0; cell < GRID_CELLS; cell++) {
                if (key.equals(draft.keys[cell]))
                    return false;
            }
            return true;
        }
        return !key.equals(draft.keys[RESULT_ENTRY]);
    }

    /** Clears every entry of one pattern slot. */
    public boolean clearPattern(int pattern) {
        if (pattern < 0 || pattern >= slots.size())
            return false;
        Draft draft = slots.get(pattern);
        if (draft.isEmpty())
            return false;
        draft.clear();
        return true;
    }

    /** Clears every slot. */
    public void clear() {
        for (Draft draft : slots)
            draft.clear();
    }

    /**
     * The pattern of one slot, if it is complete (at least one filled grid cell and a result). The filled cells are
     * merged into one ingredient per item ({@link ProductionPattern#fromGrid}).
     */
    public Optional<ProductionPattern<ItemKey>> patternAt(int pattern) {
        if (pattern < 0 || pattern >= slots.size())
            return Optional.empty();
        Draft draft = slots.get(pattern);
        if (draft.keys[RESULT_ENTRY] == null)
            return Optional.empty();
        List<ProductionEntry<ItemKey>> cells = new ArrayList<>(GRID_CELLS);
        for (int cell = 0; cell < GRID_CELLS; cell++) {
            if (draft.keys[cell] != null)
                cells.add(new ProductionEntry<>(draft.keys[cell], draft.counts[cell]));
        }
        if (cells.isEmpty())
            return Optional.empty();
        try {
            return Optional.of(ProductionPattern.fromGrid(cells,
                    new ProductionEntry<>(draft.keys[RESULT_ENTRY], draft.counts[RESULT_ENTRY])));
        } catch (IllegalArgumentException e) {
            // setEntry refuses everything that could get here; a crafted save could still contain it.
            return Optional.empty();
        }
    }

    /** Every complete pattern of this station, in slot order. */
    public List<ProductionPattern<ItemKey>> patterns() {
        List<ProductionPattern<ItemKey>> complete = new ArrayList<>(slots.size());
        for (int pattern = 0; pattern < slots.size(); pattern++)
            patternAt(pattern).ifPresent(complete::add);
        return List.copyOf(complete);
    }

    /** Number of complete patterns (the goggle line). */
    public int patternCount() {
        return patterns().size();
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    /**
     * NBT form: {@code Patterns: [{Slot: int, Entries: [{Entry: int, Item: <ItemKey>, Count: int}]}]}. Empty slots and
     * unset entries are left out, so an untouched station saves almost nothing. Never throws.
     */
    public ListTag save(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int pattern = 0; pattern < slots.size(); pattern++) {
            Draft draft = slots.get(pattern);
            if (draft.isEmpty())
                continue;
            ListTag entries = new ListTag();
            for (int entry = 0; entry < ENTRIES_PER_PATTERN; entry++) {
                ItemKey key = draft.keys[entry];
                if (key == null)
                    continue;
                try {
                    Tag keyTag = key.save(registries);
                    if (keyTag instanceof CompoundTag compound && compound.isEmpty())
                        continue; // unencodable key, already logged by ItemKey
                    CompoundTag entryTag = new CompoundTag();
                    entryTag.putInt(ENTRY, entry);
                    entryTag.put(ITEM, keyTag);
                    entryTag.putInt(COUNT, draft.counts[entry]);
                    entries.add(entryTag);
                } catch (RuntimeException e) {
                    Wareworks.LOGGER.warn("Could not save production pattern entry {} of slot {}", entry, pattern, e);
                }
            }
            if (entries.isEmpty())
                continue;
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt(SLOT, pattern);
            slotTag.put(ENTRIES, entries);
            list.add(slotTag);
        }
        return list;
    }

    /**
     * Replaces the patterns with a save written by {@link #save}. Never throws; invalid entries are skipped.
     * <p>
     * <b>A lowered {@code maxProductionPatterns} hides patterns, it never deletes them.</b> The slot list first grows
     * to hold every saved slot (bounded by {@value #MAX_SLOTS}), the way {@code StationBuffer} grows for buffer slots:
     * without that, a station loaded under a lowered config dropped the patterns in the higher slots on read and the
     * next save wrote the truncated set back, so what a player authored was gone for good and raising the value again
     * did not bring it back.
     */
    public void load(ListTag list, HolderLookup.Provider registries) {
        clear();
        if (list == null)
            return;
        int highestSaved = -1;
        for (int i = 0; i < list.size() && i < MAX_SLOTS; i++)
            highestSaved = Math.max(highestSaved, list.getCompound(i).getInt(SLOT));
        growTo(highestSaved + 1);
        for (int i = 0; i < list.size() && i < MAX_SLOTS; i++) {
            CompoundTag slotTag = list.getCompound(i);
            int pattern = slotTag.getInt(SLOT);
            if (pattern < 0 || pattern >= slots.size())
                continue;
            ListTag entries = slotTag.getList(ENTRIES, Tag.TAG_COMPOUND);
            for (int e = 0; e < entries.size() && e < ENTRIES_PER_PATTERN; e++) {
                CompoundTag entryTag = entries.getCompound(e);
                try {
                    int entry = entryTag.getInt(ENTRY);
                    ItemKey.load(registries, entryTag.get(ITEM))
                            .ifPresent(key -> setEntry(pattern, entry, key, entryTag.getInt(COUNT)));
                } catch (RuntimeException ex) {
                    Wareworks.LOGGER.warn("Skipping unreadable production pattern entry {}", entryTag, ex);
                }
            }
        }
    }

    /** Grows the slot list to {@code count} slots, bounded by {@value #MAX_SLOTS}; never shrinks it ({@link #load}). */
    private void growTo(int count) {
        for (int slot = slots.size(); slot < Math.min(count, MAX_SLOTS); slot++)
            slots.add(new Draft());
    }

    private boolean inRange(int pattern, int entry) {
        return pattern >= 0 && pattern < slots.size() && entry >= 0 && entry < ENTRIES_PER_PATTERN;
    }

    @Override
    public String toString() {
        return "ProductionPatterns[slots=" + slots.size() + ", complete=" + patternCount() + "]";
    }
}
