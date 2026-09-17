package dev.wareworks.core.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Compact, immutable digest of an {@link InventorySnapshot}: slot usage, the number of distinct keys and the largest
 * entries. It is cheap to compare, so a sender can skip syncing when nothing visible changed. Its size is bounded only if
 * the key type is: to sync it to clients (e.g. for goggle tooltips), summarise by a small group key such as the item
 * type ({@link #of(InventorySnapshot, int, Function)}), never by keys that carry unbounded data.
 *
 * @param usedSlots    number of occupied slots, {@code 0 <= usedSlots <= totalSlots}
 * @param totalSlots   number of slots of the inventory, not negative
 * @param distinctKeys number of distinct keys stored, at least {@code topEntries.size()}
 * @param topEntries   the largest entries, largest first (copied, unmodifiable)
 * @param <K>          the item key type
 */
public record InventorySummary<K>(int usedSlots, int totalSlots, int distinctKeys, List<KeyCount<K>> topEntries) {
    private static final InventorySummary<?> EMPTY = new InventorySummary<>(0, 0, 0, List.of());

    public InventorySummary {
        Objects.requireNonNull(topEntries, "topEntries");
        topEntries = List.copyOf(topEntries);
        if (totalSlots < 0)
            throw new IllegalArgumentException("totalSlots must not be negative: " + totalSlots);
        if (usedSlots < 0 || usedSlots > totalSlots)
            throw new IllegalArgumentException("usedSlots must be within 0.." + totalSlots + ": " + usedSlots);
        if (distinctKeys < topEntries.size())
            throw new IllegalArgumentException(
                    "distinctKeys (" + distinctKeys + ") must cover the top entries (" + topEntries.size() + ")");
    }

    /** The summary of an inventory with zero slots. */
    @SuppressWarnings("unchecked")
    public static <K> InventorySummary<K> empty() {
        return (InventorySummary<K>) EMPTY;
    }

    /**
     * Summarises {@code snapshot}, keeping at most {@code maxEntries} top entries (see
     * {@link InventorySnapshot#topEntries(int)} for ordering). A negative {@code maxEntries} keeps none.
     */
    public static <K> InventorySummary<K> of(InventorySnapshot<K> snapshot, int maxEntries) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.totalSlots() == 0)
            return empty();
        return new InventorySummary<>(snapshot.usedSlots(), snapshot.totalSlots(), snapshot.keys().size(),
                snapshot.topEntries(maxEntries));
    }

    /**
     * Summarises {@code snapshot} with its keys merged into coarser groups, e.g. item keys that differ only in
     * components merged by item type. Counts of keys in the same group are added up; {@code distinctKeys} is the number
     * of distinct groups. The top entries are the {@code maxEntries} largest groups, largest first, with ties in the
     * order in which groups first appear. A negative {@code maxEntries} keeps none.
     *
     * @param grouping maps every key of the snapshot to its group; must not return {@code null}
     * @param <G>      the group type; must implement {@code equals}/{@code hashCode}
     */
    public static <K, G> InventorySummary<G> of(InventorySnapshot<K> snapshot, int maxEntries,
                                                Function<? super K, ? extends G> grouping) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(grouping, "grouping");
        if (snapshot.totalSlots() == 0)
            return empty();
        Map<G, Long> groups = new LinkedHashMap<>();
        snapshot.totals().forEach((key, count) -> groups.merge(
                Objects.requireNonNull(grouping.apply(key), "grouping returned null"), count, Long::sum));
        return new InventorySummary<>(snapshot.usedSlots(), snapshot.totalSlots(), groups.size(),
                KeyCount.largestFirst(groups, maxEntries));
    }

    /**
     * Builds a summary from untrusted values (e.g. a network or NBT tag) by clamping them into the valid ranges
     * instead of throwing. Null entries are dropped.
     */
    public static <K> InventorySummary<K> sanitized(int usedSlots, int totalSlots, int distinctKeys,
                                                    List<KeyCount<K>> topEntries) {
        List<KeyCount<K>> entries = topEntries == null ? List.of()
                : topEntries.stream().filter(Objects::nonNull).toList();
        int total = Math.max(0, totalSlots);
        int used = Math.clamp(usedSlots, 0, total);
        int distinct = Math.max(Math.max(0, distinctKeys), entries.size());
        if (total == 0 && entries.isEmpty() && distinct == 0)
            return empty();
        return new InventorySummary<>(used, total, distinct, entries);
    }

    /** Whether no slot holds an item. */
    public boolean isEmpty() {
        return usedSlots == 0;
    }

    /** Number of distinct keys not listed in {@link #topEntries()}. */
    public int hiddenKeys() {
        return distinctKeys - topEntries.size();
    }
}
