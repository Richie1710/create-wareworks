package dev.wareworks.core.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Incrementally maintained stock index of one warehouse: item key → location → count
 * ({@code docs/warehouse-system.md} §5).
 * <p>
 * Every location contributes its last per-key counts. {@link #update(Object, InventorySnapshot)} replaces them with the
 * totals of a fresh snapshot and applies only the per-key difference to the aggregates, so the cost of an update is
 * proportional to the number of keys in the old and new counts, never to the size of the whole index.
 * {@link #restore(Object, Map)} does the same with counts that carry no slot information (a controller loading its
 * save). Keys and locations whose count drops to zero are pruned, so {@link #keys()} and {@link #locationsOf(Object)}
 * only report stock that exists.
 * <p>
 * <b>Order.</b> {@link #locationsOf(Object)} and {@link #locations()} are deterministic: by the comparator given to
 * {@link #StockIndex(Comparator)}, or by default in the order in which locations were first added (a location keeps its
 * place across updates, even if a key leaves and returns; {@link #remove(Object)} forgets it).
 * <p>
 * {@link #readOnlyView()} exposes the queries to code that must not modify the index.
 * <p>
 * Keys and locations must implement {@code equals}/{@code hashCode} consistently and must not be mutated while indexed.
 * Not thread-safe: the controller uses it on the server thread only.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class StockIndex<K, L> implements StockView<K, L> {
    @Nullable
    private final Comparator<? super L> locationOrder;
    private final Map<L, LocationEntry<K>> entries = new HashMap<>();
    private final Map<K, Map<L, Long>> countsByKey = new HashMap<>();
    private final Map<K, Long> totals = new HashMap<>();
    private final StockView<K, L> readOnlyView = new ReadOnlyView();
    private long totalItems;
    private int occupiedLocations;
    private long nextSequence;

    /** An index that orders locations by the time they were first added. */
    public StockIndex() {
        this.locationOrder = null;
    }

    /** An index that orders locations by {@code locationOrder} (ties by the time they were first added). */
    public StockIndex(Comparator<? super L> locationOrder) {
        this.locationOrder = Objects.requireNonNull(locationOrder, "locationOrder");
    }

    /**
     * Sets the snapshot of {@code location} (adding the location if it is new) and applies the difference to the old
     * counts. The snapshot is always stored, even if no count changed (slot layout and limits may differ).
     *
     * @return whether any count in the index changed
     */
    public boolean update(L location, InventorySnapshot<K> snapshot) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(snapshot, "snapshot");
        LocationEntry<K> entry = entryFor(location);
        boolean wasOccupied = !entry.counts.isEmpty();
        boolean changed = applyDifference(location, entry.counts, snapshot.totals());
        entry.counts = snapshot.totals();
        entry.snapshot = snapshot;
        trackOccupancy(wasOccupied, !entry.counts.isEmpty());
        return changed;
    }

    /**
     * Sets the per-key counts of {@code location} without slot information, e.g. counts restored from a save (adding
     * the location if it is new), and applies the difference to the old counts. {@link #snapshotOf} is empty for the
     * location until the next {@link #update}. Zero counts are ignored.
     *
     * @return whether any count in the index changed
     * @throws IllegalArgumentException if a count is negative (the index is left unchanged)
     */
    public boolean restore(L location, Map<K, Long> counts) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(counts, "counts");
        Map<K, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<K, Long> count : counts.entrySet()) {
            K key = Objects.requireNonNull(count.getKey(), "key");
            long value = Objects.requireNonNull(count.getValue(), "count");
            if (value < 0)
                throw new IllegalArgumentException("count of " + key + " must not be negative: " + value);
            if (value > 0)
                copy.put(key, value);
        }
        Map<K, Long> after = Collections.unmodifiableMap(copy);
        LocationEntry<K> entry = entryFor(location);
        boolean wasOccupied = !entry.counts.isEmpty();
        boolean changed = applyDifference(location, entry.counts, after);
        entry.counts = after;
        entry.snapshot = null;
        trackOccupancy(wasOccupied, !after.isEmpty());
        return changed;
    }

    /**
     * Removes a location and all its stock.
     *
     * @return whether the location was indexed
     */
    public boolean remove(L location) {
        Objects.requireNonNull(location, "location");
        LocationEntry<K> entry = entries.remove(location);
        if (entry == null)
            return false;
        for (Map.Entry<K, Long> stored : entry.counts.entrySet())
            apply(stored.getKey(), location, stored.getValue(), 0L);
        trackOccupancy(!entry.counts.isEmpty(), false);
        return true;
    }

    /** Keeps {@link #occupiedLocations} in step with one location changing between empty and holding stock. */
    private void trackOccupancy(boolean wasOccupied, boolean isOccupied) {
        if (wasOccupied == isOccupied)
            return;
        occupiedLocations += isOccupied ? 1 : -1;
    }

    private LocationEntry<K> entryFor(L location) {
        LocationEntry<K> entry = entries.get(location);
        if (entry == null) {
            entry = new LocationEntry<>(nextSequence++);
            entries.put(location, entry);
        }
        return entry;
    }

    private boolean applyDifference(L location, Map<K, Long> before, Map<K, Long> after) {
        boolean changed = false;
        for (Map.Entry<K, Long> old : before.entrySet()) {
            long now = after.getOrDefault(old.getKey(), 0L);
            if (now != old.getValue()) {
                apply(old.getKey(), location, old.getValue(), now);
                changed = true;
            }
        }
        for (Map.Entry<K, Long> added : after.entrySet()) {
            if (!before.containsKey(added.getKey())) {
                apply(added.getKey(), location, 0L, added.getValue());
                changed = true;
            }
        }
        return changed;
    }

    private void apply(K key, L location, long oldCount, long newCount) {
        long delta = newCount - oldCount;
        totalItems += delta;
        long total = totals.getOrDefault(key, 0L) + delta;
        if (total == 0)
            totals.remove(key);
        else
            totals.put(key, total);

        if (newCount == 0) {
            Map<L, Long> perLocation = countsByKey.get(key);
            if (perLocation != null) {
                perLocation.remove(location);
                if (perLocation.isEmpty())
                    countsByKey.remove(key);
            }
        } else {
            countsByKey.computeIfAbsent(key, k -> new HashMap<>()).put(location, newCount);
        }
    }

    /** A view with the queries of this index only; it reflects later changes and cannot be cast to the index. */
    public StockView<K, L> readOnlyView() {
        return readOnlyView;
    }

    @Override
    public long count(K key) {
        Objects.requireNonNull(key, "key");
        return totals.getOrDefault(key, 0L);
    }

    @Override
    public long countAt(K key, L location) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(location, "location");
        Map<L, Long> perLocation = countsByKey.get(key);
        return perLocation == null ? 0L : perLocation.getOrDefault(location, 0L);
    }

    @Override
    public Map<K, Long> countsAt(L location) {
        LocationEntry<K> entry = entries.get(Objects.requireNonNull(location, "location"));
        return entry == null ? Map.of() : entry.counts;
    }

    @Override
    public List<LocationCount<L>> locationsOf(K key) {
        Objects.requireNonNull(key, "key");
        Map<L, Long> perLocation = countsByKey.get(key);
        if (perLocation == null)
            return List.of();
        List<LocationCount<L>> result = new ArrayList<>(perLocation.size());
        perLocation.forEach((location, count) -> result.add(new LocationCount<>(location, count)));
        result.sort((a, b) -> compareLocations(a.location(), b.location()));
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<L> locations() {
        List<L> result = new ArrayList<>(entries.keySet());
        result.sort(this::compareLocations);
        return Collections.unmodifiableList(result);
    }

    private int compareLocations(L a, L b) {
        if (locationOrder != null) {
            int byOrder = locationOrder.compare(a, b);
            if (byOrder != 0)
                return byOrder;
        }
        return Long.compare(entries.get(a).sequence, entries.get(b).sequence);
    }

    @Override
    public long totalItems() {
        return totalItems;
    }

    @Override
    public int distinctKeys() {
        return totals.size();
    }

    @Override
    public Set<K> keys() {
        return Collections.unmodifiableSet(totals.keySet());
    }

    @Override
    public boolean contains(L location) {
        return entries.containsKey(Objects.requireNonNull(location, "location"));
    }

    @Override
    public int locationCount() {
        return entries.size();
    }

    @Override
    public int occupiedLocations() {
        return occupiedLocations;
    }

    @Override
    public Optional<InventorySnapshot<K>> snapshotOf(L location) {
        LocationEntry<K> entry = entries.get(Objects.requireNonNull(location, "location"));
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.snapshot);
    }

    /** Removes all locations and stock. */
    public void clear() {
        entries.clear();
        countsByKey.clear();
        totals.clear();
        totalItems = 0;
        occupiedLocations = 0;
        nextSequence = 0;
    }

    @Override
    public String toString() {
        return "StockIndex[locations=" + entries.size() + ", keys=" + totals.size() + ", items=" + totalItems + "]";
    }

    private static final class LocationEntry<K> {
        final long sequence;
        /** Positive per-key counts, unmodifiable. */
        Map<K, Long> counts = Map.of();
        /** The last snapshot, or {@code null} if the counts were restored without slot information. */
        @Nullable
        InventorySnapshot<K> snapshot;

        LocationEntry(long sequence) {
            this.sequence = sequence;
        }
    }

    /** Delegating read-only view; a separate class, so callers cannot cast it back to the index. */
    private final class ReadOnlyView implements StockView<K, L> {
        @Override
        public long count(K key) {
            return StockIndex.this.count(key);
        }

        @Override
        public long countAt(K key, L location) {
            return StockIndex.this.countAt(key, location);
        }

        @Override
        public Map<K, Long> countsAt(L location) {
            return StockIndex.this.countsAt(location);
        }

        @Override
        public List<LocationCount<L>> locationsOf(K key) {
            return StockIndex.this.locationsOf(key);
        }

        @Override
        public List<L> locations() {
            return StockIndex.this.locations();
        }

        @Override
        public long totalItems() {
            return StockIndex.this.totalItems();
        }

        @Override
        public int distinctKeys() {
            return StockIndex.this.distinctKeys();
        }

        @Override
        public Set<K> keys() {
            return StockIndex.this.keys();
        }

        @Override
        public boolean contains(L location) {
            return StockIndex.this.contains(location);
        }

        @Override
        public int locationCount() {
            return StockIndex.this.locationCount();
        }

        @Override
        public int occupiedLocations() {
            return StockIndex.this.occupiedLocations();
        }

        @Override
        public Optional<InventorySnapshot<K>> snapshotOf(L location) {
            return StockIndex.this.snapshotOf(location);
        }

        @Override
        public String toString() {
            return StockIndex.this.toString();
        }
    }
}
