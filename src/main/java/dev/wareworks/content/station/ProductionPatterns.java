package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.FluidCell;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

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
 * <b>Filter cells (ADR-082).</b> A grid cell may hold a configured Create List or Attribute Filter instead of an exact
 * item ({@link PatternFilterCells#isFilter}); it is flagged as a filter cell and stores the filter's ghost
 * ({@link PatternFilterCells#ghostOf}). {@link #patternAt} hands the keys of flagged cells to the planner as
 * {@link ProductionPattern#choices()}. The result entry is never a filter cell, and an entry saved without the flag is an
 * exact cell — also a configured filter in an old save, which therefore loads exactly as it did.
 * <p>
 * <b>Fluid cells (issue #55, ADR-089).</b> Beside the grid a slot holds up to {@value #FLUID_CELLS} {@link FluidCell}s, a
 * fluid and the millibuckets one run takes. They are no entries: they have their own list in the save
 * ({@code FluidCells}, written only for a slot that has one, so every save without fluid cells is byte for byte what it
 * was), their own setter ({@link #setFluid}) and never a filter flag. The same fluid may not sit in both cells.
 * <p>
 * <b>Bounded loading.</b> Save data is untrusted (block entity data on items, {@code /data merge}, schematics), so a
 * load reads at most {@value #MAX_SLOTS} slots with at most {@value #ENTRIES_PER_PATTERN} entries each and clamps every
 * count; reading never throws, and an entry whose item cannot be decoded is dropped like any other unreadable item.
 * <p>
 * Server thread only.
 */
public final class ProductionPatterns {
    /** Hard cap on pattern slots, whatever {@code maxProductionPatterns} says. */
    public static final int MAX_SLOTS = 36;
    /** Cells of the authoring grid: indices {@code 0..}{@value ProductionPattern#GRID_SIZE}{@code -1}. */
    public static final int GRID_CELLS = ProductionPattern.GRID_SIZE;
    /** Entries of one pattern: its {@value #GRID_CELLS} grid cells plus the result. */
    public static final int ENTRIES_PER_PATTERN = GRID_CELLS + 1;
    /** The entry index of the result; {@code 0..}{@value #GRID_CELLS}{@code -1} are the grid cells. */
    public static final int RESULT_ENTRY = GRID_CELLS;

    /** Fluid cells of one pattern slot (issue #55). */
    public static final int FLUID_CELLS = FluidCell.MAX_PER_PATTERN;
    /**
     * The entry index of the first fluid cell in a menu or payload: the cells of a pattern are {@code 0..9}, the fluid
     * cells {@code FLUID_ENTRY_BASE + 0..1}. A fluid cell is no {@link #keyAt} entry; the index only names it on the wire.
     */
    public static final int FLUID_ENTRY_BASE = ENTRIES_PER_PATTERN;

    /** The NBT key the block entity stores {@link #save} under. */
    public static final String PATTERNS_TAG = "Patterns";

    private static final String SLOT = "Slot";
    private static final String ENTRIES = "Entries";
    private static final String ENTRY = "Entry";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";
    /** Present ({@code 1b}) only on a filter cell (ADR-082); a missing flag is an exact cell. */
    private static final String FILTER = "Filter";
    /** The optional per-slot list of fluid cells (issue #55); absent means none, which is every save before ADR-089. */
    private static final String FLUID_CELLS_TAG = "FluidCells";
    private static final String CELL = "Cell";
    private static final String FLUID = "Fluid";
    private static final String MILLIBUCKETS = "Mb";

    /** One pattern slot: {@value #GRID_CELLS} grid cells and one result, each optional. */
    private static final class Draft {
        private final ItemKey[] keys = new ItemKey[ENTRIES_PER_PATTERN];
        private final int[] counts = new int[ENTRIES_PER_PATTERN];
        /** Whether a grid cell is a filter cell (ADR-082); never true for the result. */
        private final boolean[] filters = new boolean[ENTRIES_PER_PATTERN];
        /** The fluid cells (issue #55); a cell is {@code null} while unset. */
        private final FluidCell[] fluids = new FluidCell[FLUID_CELLS];

        private Draft() {
            Arrays.fill(counts, ProductionEntry.MIN_COUNT);
        }

        private boolean isEmpty() {
            for (ItemKey key : keys) {
                if (key != null)
                    return false;
            }
            return !hasFluids();
        }

        private boolean hasFluids() {
            for (FluidCell fluid : fluids) {
                if (fluid != null)
                    return true;
            }
            return false;
        }

        private void clear() {
            Arrays.fill(keys, null);
            Arrays.fill(counts, ProductionEntry.MIN_COUNT);
            Arrays.fill(filters, false);
            Arrays.fill(fluids, null);
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

    /** Whether one entry is a filter cell (ADR-082): a grid cell holding a configured List or Attribute Filter. */
    public boolean isFilterAt(int pattern, int entry) {
        return inRange(pattern, entry) && slots.get(pattern).keys[entry] != null && slots.get(pattern).filters[entry];
    }

    /**
     * The filters of pattern slot {@code pattern} (ADR-082), each once, in the order of their first filter cell, with
     * the items of it one run takes: every cell holding that filter together, as {@link #patternAt} merges them. Empty
     * for a slot without a filter cell; also answered for a slot that has no result yet.
     */
    public Map<ItemKey, Integer> filterGroups(int pattern) {
        if (pattern < 0 || pattern >= slots.size())
            return Map.of();
        Draft draft = slots.get(pattern);
        Map<ItemKey, Integer> groups = new LinkedHashMap<>();
        for (int cell = 0; cell < GRID_CELLS; cell++) {
            if (draft.keys[cell] != null && draft.filters[cell])
                groups.putIfAbsent(draft.keys[cell], 0);
        }
        for (int cell = 0; cell < GRID_CELLS && !groups.isEmpty(); cell++) {
            if (draft.keys[cell] != null && groups.containsKey(draft.keys[cell]))
                groups.merge(draft.keys[cell], draft.counts[cell], Integer::sum);
        }
        return groups;
    }

    /** The count of one entry (at least 1, also for an unset entry). */
    public int countAt(int pattern, int entry) {
        return inRange(pattern, entry) ? slots.get(pattern).counts[entry] : ProductionEntry.MIN_COUNT;
    }

    /** The fluid cell {@code cell} (0 or 1) of pattern slot {@code pattern}, or empty when it is not set (issue #55). */
    public Optional<FluidCell> fluidAt(int pattern, int cell) {
        if (pattern < 0 || pattern >= slots.size() || cell < 0 || cell >= FLUID_CELLS)
            return Optional.empty();
        return Optional.ofNullable(slots.get(pattern).fluids[cell]);
    }

    /** The set fluid cells of pattern slot {@code pattern} in cell order (issue #55); empty for a slot without any. */
    public List<FluidCell> fluidCells(int pattern) {
        if (pattern < 0 || pattern >= slots.size())
            return List.of();
        List<FluidCell> set = new ArrayList<>(FLUID_CELLS);
        for (FluidCell fluid : slots.get(pattern).fluids) {
            if (fluid != null)
                set.add(fluid);
        }
        return List.copyOf(set);
    }

    /**
     * Sets or clears one fluid cell (issue #55). The amount is clamped to {@code 1..}{@value FluidCell#MAX_MB}. Nothing
     * changes for an out-of-range index, a blank or malformed fluid id, or a fluid the other cell of the slot already
     * names (a Basin recipe never asks for the same fluid twice). The set cells stay at the front: clearing cell 0 moves
     * cell 1 into it, and setting cell 1 while cell 0 is empty fills cell 0.
     *
     * @param fluid the fluid's registry id, or {@code null} to clear the cell
     * @return whether the cell changed
     */
    public boolean setFluid(int pattern, int cell, @Nullable String fluid, int millibuckets) {
        if (pattern < 0 || pattern >= slots.size() || cell < 0 || cell >= FLUID_CELLS)
            return false;
        Draft draft = slots.get(pattern);
        if (fluid == null) {
            if (draft.fluids[cell] == null)
                return false;
            draft.fluids[cell] = null;
            compactFluids(draft);
            return true;
        }
        // Bounded like every payload that carries it: a longer id could never be synced to a client (an untrusted save
        // or a /data merge must not make the screen state unwritable).
        if (fluid.isBlank() || fluid.length() > FluidCell.MAX_ID_LENGTH || ResourceLocation.tryParse(fluid) == null)
            return false;
        FluidCell other = draft.fluids[1 - cell];
        if (other != null && other.fluid().equals(fluid))
            return false;
        FluidCell next = FluidCell.sanitized(fluid, millibuckets);
        if (next.equals(draft.fluids[cell]))
            return false;
        draft.fluids[cell] = next;
        compactFluids(draft);
        return true;
    }

    /**
     * Keeps the set fluid cells at the front: a lone cell is always cell 0. The screen draws a cell where its position in
     * the list is, so a gap would put an edit on a cell other than the one a player clicked.
     */
    private static void compactFluids(Draft draft) {
        if (draft.fluids[0] == null && draft.fluids[1] != null) {
            draft.fluids[0] = draft.fluids[1];
            draft.fluids[1] = null;
        }
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
        return setEntry(pattern, entry, key, count,
                key != null && entry >= 0 && entry < GRID_CELLS && PatternFilterCells.isFilter(key));
    }

    /**
     * {@link #setEntry(int, int, ItemKey, int)} with the filter flag given (ADR-082): a save's flag, or what the server
     * classified. The flag is forced off for the result and for a key that is no configured filter, and a filter cell
     * stores the filter's ghost ({@link PatternFilterCells#ghostOf}).
     */
    public boolean setEntry(int pattern, int entry, @Nullable ItemKey key, int count, boolean filter) {
        if (!inRange(pattern, entry))
            return false;
        Draft draft = slots.get(pattern);
        int clamped = ProductionEntry.clampCellCount(count);
        if (key == null) {
            if (draft.keys[entry] == null && draft.counts[entry] == clamped)
                return false;
            draft.keys[entry] = null;
            draft.counts[entry] = ProductionEntry.MIN_COUNT;
            draft.filters[entry] = false;
            return true;
        }
        boolean isFilter = filter && entry != RESULT_ENTRY && PatternFilterCells.isFilter(key);
        ItemKey stored = isFilter ? PatternFilterCells.ghostOf(key) : key;
        if (!accepts(draft, entry, stored))
            return false;
        if (stored.equals(draft.keys[entry]) && draft.counts[entry] == clamped && draft.filters[entry] == isFilter)
            return false;
        draft.keys[entry] = stored;
        draft.counts[entry] = clamped;
        draft.filters[entry] = isFilter;
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
        Set<ItemKey> choices = new LinkedHashSet<>();
        for (int cell = 0; cell < GRID_CELLS; cell++) {
            if (draft.keys[cell] != null) {
                cells.add(new ProductionEntry<>(draft.keys[cell], draft.counts[cell]));
                // A key is a choice when any cell holding it is a filter cell (ADR-082).
                if (draft.filters[cell])
                    choices.add(draft.keys[cell]);
            }
        }
        List<FluidCell> fluids = fluidCells(pattern);
        if (cells.isEmpty() && fluids.isEmpty())
            return Optional.empty();
        try {
            return Optional.of(ProductionPattern.fromGrid(cells,
                    new ProductionEntry<>(draft.keys[RESULT_ENTRY], draft.counts[RESULT_ENTRY]), choices, fluids));
        } catch (IllegalArgumentException e) {
            // setEntry refuses everything that could get here; a crafted save could still contain it.
            return Optional.empty();
        }
    }

    /**
     * Whether slot {@code pattern} holds an ingredient (a grid cell or a fluid cell) but no result (issue #62). Such a
     * pattern is incomplete: it is never offered or ordered, and the station says so.
     */
    public boolean lacksResult(int pattern) {
        if (pattern < 0 || pattern >= slots.size() || slots.get(pattern).keys[RESULT_ENTRY] != null)
            return false;
        return filledCells(pattern) > 0 || !fluidCells(pattern).isEmpty();
    }

    /** Every slot that {@linkplain #lacksResult lacks its result}, in slot order. */
    public List<Integer> slotsWithoutResult() {
        List<Integer> lacking = new ArrayList<>(0);
        for (int pattern = 0; pattern < slots.size(); pattern++) {
            if (lacksResult(pattern))
                lacking.add(pattern);
        }
        return lacking;
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
     * NBT form: {@code Patterns: [{Slot: int, Entries: [{Entry: int, Item: <ItemKey>, Count: int, Filter: 1b}],
     * FluidCells: [{Cell: int, Fluid: string, Mb: int}]}]}, the {@code Filter} flag only on a filter cell (ADR-082) and
     * {@code FluidCells} only on a slot with a fluid cell (issue #55). Empty slots and unset entries are left out, so an
     * untouched station saves almost nothing. Never throws.
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
                    if (draft.filters[entry])
                        entryTag.putBoolean(FILTER, true);
                    entries.add(entryTag);
                } catch (RuntimeException e) {
                    Wareworks.LOGGER.warn("Could not save production pattern entry {} of slot {}", entry, pattern, e);
                }
            }
            ListTag fluidList = new ListTag();
            for (int cell = 0; cell < FLUID_CELLS; cell++) {
                FluidCell fluid = draft.fluids[cell];
                if (fluid == null)
                    continue;
                CompoundTag fluidTag = new CompoundTag();
                fluidTag.putInt(CELL, cell);
                fluidTag.putString(FLUID, fluid.fluid());
                fluidTag.putInt(MILLIBUCKETS, fluid.millibuckets());
                fluidList.add(fluidTag);
            }
            if (entries.isEmpty() && fluidList.isEmpty())
                continue;
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt(SLOT, pattern);
            slotTag.put(ENTRIES, entries);
            // Only a slot with a fluid cell carries the list, so a save without any is what it always was.
            if (!fluidList.isEmpty())
                slotTag.put(FLUID_CELLS_TAG, fluidList);
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
                    // No flag means exact, also for a configured filter an old save holds (ADR-082).
                    boolean filter = entryTag.getBoolean(FILTER);
                    ItemKey.load(registries, entryTag.get(ITEM))
                            .ifPresent(key -> setEntry(pattern, entry, key, entryTag.getInt(COUNT), filter));
                } catch (RuntimeException ex) {
                    Wareworks.LOGGER.warn("Skipping unreadable production pattern entry {}", entryTag, ex);
                }
            }
            // Absent in every save before ADR-089; read bounded like the entries and never throwing.
            ListTag fluidList = slotTag.getList(FLUID_CELLS_TAG, Tag.TAG_COMPOUND);
            for (int f = 0; f < fluidList.size() && f < FLUID_CELLS; f++) {
                CompoundTag fluidTag = fluidList.getCompound(f);
                setFluid(pattern, fluidTag.getInt(CELL), fluidTag.getString(FLUID), fluidTag.getInt(MILLIBUCKETS));
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
