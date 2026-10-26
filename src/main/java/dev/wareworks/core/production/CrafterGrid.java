package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The 3 x 3 layout a Warehouse Crafter Feeder lays into a vanilla Crafter (issue #34, ADR-067): one optional item per
 * cell, row-major, exactly as a production pattern is authored ({@code ProductionPatterns}, cells {@code 0..8}).
 * <p>
 * <b>Cell i is Crafter slot i.</b> The vanilla Crafter's container is row-major with a width of three
 * ({@code CrafterBlockEntity.CONTAINER_WIDTH}), and NeoForge exposes it as an {@code InvWrapper} in that same index
 * order, so the mapping is the identity ({@link #slotOf}). It is spelled out as a method anyway, so the one place that
 * would have to change if it ever stopped being one is named, and a JUnit test pins it.
 * <p>
 * What a pattern's merged multiset ({@link ProductionPattern#fromGrid}) has thrown away — which cell holds what — is
 * exactly what a crafting recipe depends on, so the feeder keeps this layout and not the multiset.
 * <p>
 * Immutable and pure: no Minecraft types.
 *
 * @param cells the {@value #SLOTS} cells in grid order; an empty optional is an empty cell
 * @param <K>   item key type
 */
public record CrafterGrid<K>(List<Optional<K>> cells) {
    /** Edge length of the Crafter's grid. */
    public static final int WIDTH = ProductionPattern.GRID_WIDTH;
    /** Slots of a vanilla Crafter, which are the cells of a pattern grid. */
    public static final int SLOTS = ProductionPattern.GRID_SIZE;
    /** A mask with one bit per slot. */
    public static final int ALL_SLOTS_MASK = (1 << SLOTS) - 1;

    public CrafterGrid {
        Objects.requireNonNull(cells, "cells");
        if (cells.size() != SLOTS)
            throw new IllegalArgumentException("a crafter grid has " + SLOTS + " cells: " + cells.size());
        for (Optional<K> cell : cells)
            Objects.requireNonNull(cell, "cell");
        cells = List.copyOf(cells);
    }

    /** A grid of {@value #SLOTS} empty cells. */
    public static <K> CrafterGrid<K> empty() {
        return new CrafterGrid<>(Collections.nCopies(SLOTS, Optional.empty()));
    }

    /** A grid from nullable cells, {@code null} being an empty cell. */
    public static <K> CrafterGrid<K> ofNullable(List<K> cells) {
        Objects.requireNonNull(cells, "cells");
        List<Optional<K>> wrapped = new ArrayList<>(cells.size());
        for (K cell : cells)
            wrapped.add(Optional.ofNullable(cell));
        return new CrafterGrid<>(wrapped);
    }

    /**
     * The Crafter slot that pattern cell {@code cell} goes into: the same index, because both are row-major with a width
     * of {@value #WIDTH}.
     *
     * @throws IllegalArgumentException for an index outside {@code 0..}{@value #SLOTS}{@code -1}
     */
    public static int slotOf(int cell) {
        if (cell < 0 || cell >= SLOTS)
            throw new IllegalArgumentException("cell must be within 0.." + (SLOTS - 1) + ": " + cell);
        return cell;
    }

    /** The item of {@code cell}, empty for an empty cell or an index out of range. */
    public Optional<K> at(int cell) {
        return cell < 0 || cell >= SLOTS ? Optional.empty() : cells.get(cell);
    }

    /** Whether no cell holds anything. */
    public boolean isEmpty() {
        return usedMask() == 0;
    }

    /** One bit per filled cell, bit {@code i} for cell {@code i}. */
    public int usedMask() {
        int mask = 0;
        for (int cell = 0; cell < SLOTS; cell++) {
            if (cells.get(cell).isPresent())
                mask |= 1 << cell;
        }
        return mask;
    }

    /** How many cells are filled. */
    public int usedCells() {
        return Integer.bitCount(usedMask());
    }

    /** How many cells hold each item, in the order the items first appear in the grid. */
    public Map<K, Integer> cellsPerKey() {
        Map<K, Integer> counts = new LinkedHashMap<>();
        for (Optional<K> cell : cells)
            cell.ifPresent(key -> counts.merge(key, 1, Integer::sum));
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Whether every filled cell of a pattern holds exactly one item ({@code CrafterPatternCheck#CELL_NOT_ONE}). A vanilla
     * Crafter takes one item out of every filled slot per craft, so a cell of two would leave the second item behind
     * after every run and the next run of the same order would find a grid it cannot tell from a disturbed one.
     *
     * @param counts the count of each cell, read only where this grid holds an item
     */
    public boolean allCellsSingle(int[] counts) {
        Objects.requireNonNull(counts, "counts");
        for (int cell = 0; cell < SLOTS; cell++) {
            if (cells.get(cell).isPresent() && (cell >= counts.length || counts[cell] != 1))
                return false;
        }
        return true;
    }

    /**
     * How many runs of this layout a Crafter grid holds right now: {@code 0} when every slot is empty, {@code n} when
     * every cell of this layout holds its own item {@code n} times and every other slot is empty, and nothing at all
     * when the grid is neither — a player took or added something, or another order's items are lying there. The feeder
     * never pulses into such a grid.
     *
     * @param slotKeys   the item in each Crafter slot, empty for an empty slot
     * @param slotCounts the count in each Crafter slot
     */
    public OptionalInt runsIn(List<Optional<K>> slotKeys, int[] slotCounts) {
        Objects.requireNonNull(slotKeys, "slotKeys");
        Objects.requireNonNull(slotCounts, "slotCounts");
        if (slotKeys.size() != SLOTS || slotCounts.length != SLOTS)
            return OptionalInt.empty();
        boolean allEmpty = true;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slotKeys.get(slot).isPresent() && slotCounts[slot] > 0)
                allEmpty = false;
        }
        if (allEmpty)
            return OptionalInt.of(0);
        int runs = -1;
        for (int slot = 0; slot < SLOTS; slot++) {
            Optional<K> expected = cells.get(slot);
            Optional<K> found = slotCounts[slot] > 0 ? slotKeys.get(slot) : Optional.empty();
            if (expected.isEmpty()) {
                if (found.isPresent())
                    return OptionalInt.empty();
                continue;
            }
            if (found.isEmpty() || !found.equals(expected))
                return OptionalInt.empty();
            if (runs == -1)
                runs = slotCounts[slot];
            else if (runs != slotCounts[slot])
                return OptionalInt.empty();
        }
        return runs < 1 ? OptionalInt.empty() : OptionalInt.of(runs);
    }
}
