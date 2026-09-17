package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The item list a warehouse terminal screen shows: everything the server sent, narrowed by the search and the "only
 * what is in stock" filter, ordered by the chosen {@link TerminalSort} and cut into the rows of the grid
 * ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * Pure Java: the screen owns one of these and only feeds it lines and user input, so the list, search, sort and paging
 * behaviour is unit tested without a game. The visible list is recomputed lazily, at most once per change, not per
 * frame.
 *
 * @param <K> item key type
 */
public final class StockListModel<K> {
    /** Insertion order keeps the server's order as the tie-break of a stable sort. */
    private final Map<K, StockLine<K>> lines = new LinkedHashMap<>();

    private String query = "";
    private TerminalSort sort = TerminalSort.AMOUNT;
    private boolean inStockOnly;

    private List<StockLine<K>> visible = List.of();
    private boolean dirty = true;

    /** Replaces the whole list (a screen opened, or the server re-synced everything). */
    public void replaceAll(Collection<StockLine<K>> replacement) {
        Objects.requireNonNull(replacement, "replacement");
        lines.clear();
        for (StockLine<K> line : replacement) {
            if (line.isShown())
                lines.put(line.key(), line);
        }
        dirty = true;
    }

    /**
     * Applies a delta: entries worth showing are added or replaced, the others removed. An item at zero stock that the
     * aisle can <b>produce</b> is worth showing (M11, ADR-024), which is what lets a terminal offer something that is
     * not there yet.
     */
    public void apply(Collection<StockLine<K>> changed) {
        Objects.requireNonNull(changed, "changed");
        for (StockLine<K> line : changed) {
            if (line.isShown())
                lines.put(line.key(), line);
            else
                lines.remove(line.key());
        }
        dirty = true;
    }

    public void clear() {
        lines.clear();
        dirty = true;
    }

    /** @return whether the query changed (the screen resets its scrolling then) */
    public boolean setQuery(String newQuery) {
        String normalized = newQuery == null ? "" : newQuery;
        if (normalized.equals(query))
            return false;
        query = normalized;
        dirty = true;
        return true;
    }

    /** @return whether the order changed */
    public boolean setSort(TerminalSort newSort) {
        Objects.requireNonNull(newSort, "sort");
        if (newSort == sort)
            return false;
        sort = newSort;
        dirty = true;
        return true;
    }

    /** @return whether the filter changed */
    public boolean setInStockOnly(boolean only) {
        if (only == inStockOnly)
            return false;
        inStockOnly = only;
        dirty = true;
        return true;
    }

    public String query() {
        return query;
    }

    public TerminalSort sort() {
        return sort;
    }

    public boolean inStockOnly() {
        return inStockOnly;
    }

    /** Number of item types the server sent, before search and filter. */
    public int size() {
        return lines.size();
    }

    /** The line of {@code key}, whether it is visible or not. */
    public Optional<StockLine<K>> find(K key) {
        return key == null ? Optional.empty() : Optional.ofNullable(lines.get(key));
    }

    /** The lines that pass search and filter, in the chosen order. Unmodifiable. */
    public List<StockLine<K>> visible() {
        if (dirty)
            recompute();
        return visible;
    }

    // --- grid paging -----------------------------------------------------------------------------------------------

    /** Rows the visible lines need in a grid of {@code columns} columns. */
    public int rowCount(int columns) {
        int perRow = Math.max(1, columns);
        int count = visible().size();
        return (count + perRow - 1) / perRow;
    }

    /** The largest first row a grid of {@code rows} rows can scroll to (0 when everything fits). */
    public int maxScrollRow(int columns, int rows) {
        return Math.max(0, rowCount(columns) - Math.max(1, rows));
    }

    /** {@code scrollRow} clamped into {@code [0, maxScrollRow]}. */
    public int clampScrollRow(int scrollRow, int columns, int rows) {
        return Math.max(0, Math.min(scrollRow, maxScrollRow(columns, rows)));
    }

    /**
     * The lines shown in a grid of {@code columns} × {@code rows} cells whose first row is {@code scrollRow}. Shorter
     * than the grid when the list ends; never longer. Unmodifiable.
     */
    public List<StockLine<K>> window(int scrollRow, int columns, int rows) {
        List<StockLine<K>> all = visible();
        int perRow = Math.max(1, columns);
        int from = Math.min(all.size(), clampScrollRow(scrollRow, columns, rows) * perRow);
        int to = Math.min(all.size(), from + perRow * Math.max(1, rows));
        return List.copyOf(all.subList(from, to));
    }

    private void recompute() {
        List<StockLine<K>> matching = new ArrayList<>(lines.size());
        for (StockLine<K> line : lines.values()) {
            // "Only what is available" hides producible-but-absent items too: nothing of them can be handed over now.
            if (inStockOnly && line.available() <= 0L)
                continue;
            if (TerminalSearch.matches(line, query))
                matching.add(line);
        }
        matching.sort(sort.comparator());
        visible = List.copyOf(matching);
        dirty = false;
    }
}
