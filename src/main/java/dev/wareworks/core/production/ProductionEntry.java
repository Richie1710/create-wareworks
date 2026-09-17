package dev.wareworks.core.production;

import java.util.Objects;

/**
 * One line of a production pattern ({@code docs/warehouse-system.md} §3.5): an item and how many of it one run of the
 * pattern consumes (an ingredient) or yields (the result).
 * <p>
 * <b>Two bounds, on purpose.</b> A pattern is authored in a 3 x 3 grid, and one <b>cell</b> (like the result) holds at
 * most {@value #MAX_PER_CELL} items — one vanilla stack, the same thing a crafting grid slot holds. An
 * <b>ingredient</b> of the finished pattern is the <i>sum</i> of every cell that names that item
 * ({@link ProductionPattern#fromGrid}), so it can be as large as {@value #MAX_COUNT}: nine cells of a stack each. The
 * record itself therefore bounds at {@value #MAX_COUNT}, while {@link #clampCellCount} — what the editor and untrusted
 * save data go through — bounds at {@value #MAX_PER_CELL}.
 *
 * @param key   the item identity (the content layer uses {@code ItemKey})
 * @param count items per run, {@value #MIN_COUNT}..{@value #MAX_COUNT}
 * @param <K>   item key type
 */
public record ProductionEntry<K>(K key, int count) {
    /** Smallest count a pattern line may have; a line of 0 is no line, it is an empty cell. */
    public static final int MIN_COUNT = 1;
    /** Largest count one grid cell (or the result) may hold: one vanilla stack. */
    public static final int MAX_PER_CELL = 64;
    /** Largest count an ingredient of a whole pattern may have: {@link ProductionPattern#GRID_SIZE} full cells. */
    public static final int MAX_COUNT = ProductionPattern.GRID_SIZE * MAX_PER_CELL;

    public ProductionEntry {
        Objects.requireNonNull(key, "key");
        if (count < MIN_COUNT || count > MAX_COUNT)
            throw new IllegalArgumentException("count must be within " + MIN_COUNT + ".." + MAX_COUNT + ": " + count);
    }

    /** {@code count} clamped into what one grid cell may hold, for values from a client or from save data. */
    public static int clampCellCount(int count) {
        return Math.min(Math.max(count, MIN_COUNT), MAX_PER_CELL);
    }

    /** A cell entry with a clamped count, for untrusted input. */
    public static <K> ProductionEntry<K> sanitized(K key, int count) {
        return new ProductionEntry<>(key, clampCellCount(count));
    }

    /**
     * This line plus {@code more} items of the same key, saturating at {@value #MAX_COUNT}. This is how the cells of a
     * grid that name the same item become one ingredient ({@link ProductionPattern#fromGrid}).
     *
     * @throws IllegalArgumentException if {@code more < 1}
     */
    public ProductionEntry<K> plus(int more) {
        if (more < MIN_COUNT)
            throw new IllegalArgumentException("more must be at least " + MIN_COUNT + ": " + more);
        return new ProductionEntry<>(key, (int) Math.min(MAX_COUNT, (long) count + more));
    }

    /**
     * The total for {@code runs} runs, saturating at {@link Integer#MAX_VALUE} instead of overflowing.
     *
     * @throws IllegalArgumentException if {@code runs < 1}
     */
    public int totalFor(int runs) {
        if (runs < 1)
            throw new IllegalArgumentException("runs must be at least 1: " + runs);
        return (int) Math.min(Integer.MAX_VALUE, (long) count * runs);
    }
}
