package dev.wareworks.core.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The recipe a player lays in a warehouse terminal's crafting panel (issue #24, M32): nine cells, each empty or naming
 * one exact item key.
 * <p>
 * <b>A grid of ghosts, not of items.</b> A cell is never an item and never a menu slot. The ingredients stay in the
 * terminal's buffer ("Delivered here") until the moment a player crafts, and the grid only says which of them one craft
 * takes, in which arrangement. The arrangement matters exactly once, when the server matches the grid against a vanilla
 * crafting recipe; everything else — what a craft takes from the buffer ({@link SlotPlan}), how many crafts the buffer
 * holds ({@link #craftsFrom}), what Fetch has to bring ({@link #toFetch}) — sees the merged multiset
 * {@link #perCraft()}.
 * <p>
 * <b>Exact keys.</b> A cell names an item <i>with</i> its components, never a tag and never an alternative. The
 * recipe's own ingredient test decides on the server whether the grid matches, and a craft consumes exactly the keys
 * the grid shows, so renamed planks are a different ingredient from plain planks here exactly as they are in the stock
 * list.
 * <p>
 * <b>Deliberately no "the result must not be an ingredient" rule.</b> {@code core.production.ProductionPattern} refuses
 * such a pattern because its planner could promise an item out of itself. A crafting grid has no such planner, and
 * vanilla has real recipes of that shape: cloning a map or a written book gives back a key the craft consumes. The grid
 * knows nothing of results at all, so it cannot copy the rule by accident.
 * <p>
 * Immutable and pure: no Minecraft types, so the grid's arithmetic is unit tested without a game. Two grids with the
 * same cells are equal, which is what lets the server answer a preview only when the cells really changed.
 *
 * @param cells exactly {@value #SIZE} cells in row-major order, an empty {@link Optional} for an empty cell
 * @param <K>   item key type
 */
public record CraftGrid<K>(List<Optional<K>> cells) {
    /** Edge length of the grid: a vanilla crafting table's 3 x 3. */
    public static final int WIDTH = 3;
    /** Cells of the grid, {@value #WIDTH} x {@value #WIDTH}. */
    public static final int SIZE = WIDTH * WIDTH;

    public CraftGrid {
        Objects.requireNonNull(cells, "cells");
        if (cells.size() != SIZE)
            throw new IllegalArgumentException("a crafting grid has exactly " + SIZE + " cells: " + cells.size());
        for (Optional<K> cell : cells)
            Objects.requireNonNull(cell, "cell");
        cells = List.copyOf(cells);
    }

    /** A grid with every cell empty. */
    public static <K> CraftGrid<K> empty() {
        return new CraftGrid<>(Collections.nCopies(SIZE, Optional.empty()));
    }

    /**
     * A grid from up to {@value #SIZE} keys in row-major order; a {@code null} entry is an empty cell, and the cells
     * past the end of the list stay empty. Convenient where a recipe is written down row by row.
     *
     * @throws IllegalArgumentException if {@code keys} has more than {@value #SIZE} entries
     */
    @SafeVarargs
    public static <K> CraftGrid<K> of(K... keys) {
        if (keys.length > SIZE)
            throw new IllegalArgumentException("a crafting grid has at most " + SIZE + " cells: " + keys.length);
        List<Optional<K>> cells = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++)
            cells.add(i < keys.length ? Optional.ofNullable(keys[i]) : Optional.empty());
        return new CraftGrid<>(cells);
    }

    /**
     * The key in cell {@code index}, empty for an empty cell.
     *
     * @throws IndexOutOfBoundsException if {@code index} is not a cell of the grid
     */
    public Optional<K> cell(int index) {
        return cells.get(index);
    }

    /**
     * This grid with cell {@code index} set to {@code key}, or emptied for an empty {@link Optional}: what a click with
     * a real item on the cursor does, which clears the cell when the hand is empty.
     *
     * @throws IndexOutOfBoundsException if {@code index} is not a cell of the grid
     */
    public CraftGrid<K> withCell(int index, Optional<K> key) {
        Objects.checkIndex(index, SIZE);
        Objects.requireNonNull(key, "key");
        List<Optional<K>> changed = new ArrayList<>(cells);
        changed.set(index, key);
        return new CraftGrid<>(changed);
    }

    /** This grid with cell {@code index} set to {@code key}. */
    public CraftGrid<K> with(int index, K key) {
        return withCell(index, Optional.of(Objects.requireNonNull(key, "key")));
    }

    /** This grid with cell {@code index} emptied. */
    public CraftGrid<K> without(int index) {
        return withCell(index, Optional.empty());
    }

    /** Whether no cell names an item: such a grid matches no recipe and has nothing to fetch. */
    public boolean isEmpty() {
        for (Optional<K> cell : cells) {
            if (cell.isPresent())
                return false;
        }
        return true;
    }

    /** Cells that name an item, i.e. how many items one craft takes. */
    public int occupiedCells() {
        int occupied = 0;
        for (Optional<K> cell : cells) {
            if (cell.isPresent())
                occupied++;
        }
        return occupied;
    }

    /**
     * The first empty cell in row-major order, where a Shift + right-click on a stock cell writes its item; empty when
     * every cell is taken.
     */
    public OptionalInt nextFreeCell() {
        for (int i = 0; i < SIZE; i++) {
            if (cells.get(i).isEmpty())
                return OptionalInt.of(i);
        }
        return OptionalInt.empty();
    }

    /** The first cell in row-major order that names {@code key}; empty when no cell does. */
    public OptionalInt firstCell(K key) {
        Objects.requireNonNull(key, "key");
        for (int i = 0; i < SIZE; i++) {
            if (cells.get(i).filter(key::equals).isPresent())
                return OptionalInt.of(i);
        }
        return OptionalInt.empty();
    }

    /**
     * What one craft takes: every key the grid names, with the number of cells that name it, in the order in which the
     * keys <b>first</b> appear in the grid (so the list reads the way the grid does, the same order
     * {@code ProductionPattern#fromGrid} keeps). Unmodifiable; empty for an empty grid.
     */
    public Map<K, Integer> perCraft() {
        Map<K, Integer> merged = new LinkedHashMap<>();
        for (Optional<K> cell : cells)
            cell.ifPresent(key -> merged.merge(key, 1, Integer::sum));
        return Collections.unmodifiableMap(merged);
    }

    /**
     * How many whole crafts {@code counts} hold: the smallest {@code count / perCraft} over the grid's keys, rounded
     * down, because a craft cannot be cut in half. A key {@code counts} does not name counts as 0, as does a negative
     * count. An empty grid crafts nothing and answers 0.
     *
     * @param counts how many of each key there are, e.g. the terminal's buffer
     */
    public int craftsFrom(Map<K, Long> counts) {
        Objects.requireNonNull(counts, "counts");
        Map<K, Integer> needs = perCraft();
        if (needs.isEmpty())
            return 0;
        long crafts = Long.MAX_VALUE;
        for (Map.Entry<K, Integer> need : needs.entrySet())
            crafts = Math.min(crafts, Math.max(0L, counts.getOrDefault(need.getKey(), 0L)) / need.getValue());
        return (int) Math.min(Integer.MAX_VALUE, crafts);
    }

    /**
     * What has to be fetched so that the buffer holds {@code crafts} crafts: per key {@code max(0, perCraft × crafts −
     * inBuffer)}, in first-cell order. Only keys with something to fetch are listed, so an empty map means the buffer
     * already holds enough. Unmodifiable.
     * <p>
     * It tops up against the <b>buffer</b> and nothing else: items already on their way to this terminal for an
     * unrelated request are not subtracted, because they belong to that request and a craft cannot count on them.
     * The arithmetic is done in {@code long} and saturates at {@link Integer#MAX_VALUE}, so even an absurd number of
     * crafts never overflows into a negative amount.
     *
     * @param crafts   how many crafts the player wants; 0 or less fetches nothing
     * @param inBuffer how many of each key the terminal's buffer already holds; a key it does not name counts as 0
     */
    public Map<K, Integer> toFetch(int crafts, Map<K, Long> inBuffer) {
        Objects.requireNonNull(inBuffer, "inBuffer");
        if (crafts <= 0)
            return Map.of();
        Map<K, Integer> fetch = new LinkedHashMap<>();
        for (Map.Entry<K, Integer> need : perCraft().entrySet()) {
            long missing = (long) need.getValue() * crafts - Math.max(0L, inBuffer.getOrDefault(need.getKey(), 0L));
            if (missing > 0L)
                fetch.put(need.getKey(), (int) Math.min(Integer.MAX_VALUE, missing));
        }
        return Collections.unmodifiableMap(fetch);
    }
}
