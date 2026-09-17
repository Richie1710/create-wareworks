package dev.wareworks.core.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * iteration order of {@code totals}. Returns an empty list for {@code n <= 0}.
     */
    static <K> List<KeyCount<K>> largestFirst(Map<K, Long> totals, int n) {
        if (n <= 0 || totals.isEmpty())
            return List.of();
        List<KeyCount<K>> entries = new ArrayList<>(totals.size());
        totals.forEach((key, count) -> entries.add(new KeyCount<>(key, count)));
        // List.sort is stable, so equal counts keep their iteration order.
        entries.sort(Comparator.comparingLong((KeyCount<K> entry) -> entry.count()).reversed());
        return List.copyOf(entries.subList(0, Math.min(n, entries.size())));
    }
}
