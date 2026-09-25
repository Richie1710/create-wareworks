package dev.wareworks.core.inventory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only queries of a {@link StockIndex}. {@link StockIndex#readOnlyView()} returns an implementation that cannot be
 * cast back to the index, so callers outside the owner (e.g. job planning in later milestones) can only read.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public interface StockView<K, L> {
    /** Total stored amount of {@code key} over all locations, 0 if absent. */
    long count(K key);

    /** Stored amount of {@code key} at {@code location}, 0 if absent or the location is not indexed. */
    long countAt(K key, L location);

    /** The per-key amounts stored at {@code location} (unmodifiable, positive counts only); empty if not indexed. */
    Map<K, Long> countsAt(L location);

    /** The locations holding {@code key}, with their amounts, in index order. Unmodifiable. */
    List<LocationCount<L>> locationsOf(K key);

    /** All indexed locations, including empty ones, in index order. Unmodifiable copy. */
    List<L> locations();

    /** Sum of all stored items over all keys and locations. */
    long totalItems();

    /** Number of distinct keys with a positive total. */
    int distinctKeys();

    /** The keys with a positive total (unmodifiable, no defined order). */
    Set<K> keys();

    /** Whether {@code location} is indexed (possibly without stock). */
    boolean contains(L location);

    int locationCount();

    /**
     * Number of indexed locations that hold at least one item, i.e. {@link #locationCount()} minus the empty ones.
     * Maintained while the index changes, so asking is a field read, not a walk over the locations.
     */
    int occupiedLocations();

    /**
     * The last snapshot of {@code location}; empty if the location is not indexed or its counts were restored without
     * slot information ({@link StockIndex#restore}) and no snapshot arrived since.
     */
    Optional<InventorySnapshot<K>> snapshotOf(L location);
}
