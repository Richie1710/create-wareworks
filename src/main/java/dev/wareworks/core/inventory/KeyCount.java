package dev.wareworks.core.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

/**
 * An item key together with a total amount, e.g. one line of a "top entries" listing.
 *
 * @param key   the item key, never {@code null}
 * @param count the total amount, never negative
 * @param <K>   the item key type
 */
public record KeyCount<K>(K key, long count) {
    public KeyCount {
        Objects.requireNonNull(key, "key");
        if (count < 0)
            throw new IllegalArgumentException("count must not be negative: " + count);
    }

    /**
     * The {@code n} entries of {@code totals} with the largest counts, largest first (unmodifiable). Ties keep the
     * iteration order of {@code totals}, which for a {@link java.util.HashMap} is <b>not</b> reproducible; use
     * {@link #largestFirst(Map, int, Comparator)} where the order must be stable. Returns an empty list for
     * {@code n <= 0}.
     */
    public static <K> List<KeyCount<K>> largestFirst(Map<K, Long> totals, int n) {
        return sorted(totals, n, null);
    }

    /**
     * The {@code n} entries of {@code totals} with the largest counts, largest first (unmodifiable), with equal counts
     * ordered by {@code tieBreak}. The result then depends only on the contents of {@code totals}, never on its
     * iteration order, which is what a display must have to stop flickering between two equally stocked items.
     * Returns an empty list for {@code n <= 0}.
     */
    public static <K> List<KeyCount<K>> largestFirst(Map<K, Long> totals, int n, Comparator<? super K> tieBreak) {
        return sorted(totals, n, Objects.requireNonNull(tieBreak, "tieBreak"));
    }

    private static <K> List<KeyCount<K>> sorted(Map<K, Long> totals, int n, @Nullable Comparator<? super K> tieBreak) {
        if (n <= 0 || totals.isEmpty())
            return List.of();
        List<KeyCount<K>> entries = new ArrayList<>(totals.size());
        totals.forEach((key, count) -> entries.add(new KeyCount<>(key, count)));
        // List.sort is stable, so equal counts keep their iteration order unless a tie-break decides them.
        Comparator<KeyCount<K>> order = Comparator.comparingLong((KeyCount<K> entry) -> entry.count()).reversed();
        if (tieBreak != null)
            order = order.thenComparing(KeyCount::key, tieBreak);
        entries.sort(order);
        return List.copyOf(entries.subList(0, Math.min(n, entries.size())));
    }
}
