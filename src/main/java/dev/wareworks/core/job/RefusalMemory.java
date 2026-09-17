package dev.wareworks.core.job;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers which locations refused a key in a live simulation although the index promised room or stock
 * ({@code docs/warehouse-system.md} §7.4), so the planner skips them instead of spending its live simulation budget on
 * the same refusals every run. Without it, more restricted locations than the budget, ranked ahead of one that accepts
 * (e.g. many empty shulker boxes offered a shulker box), would exhaust every run on the same candidates forever.
 * <p>
 * An entry ends when its location is read again ({@link #forget}, the owner calls it after a snapshot), when it is
 * older than {@code ttlTicks} (inventories that change silently), or when the memory is full: recording a new entry at
 * {@code maxEntries} clears everything, which only costs some repeated simulations.
 * <p>
 * Pure Java, not thread-safe (server thread only). Queries and changes are O(1).
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class RefusalMemory<K, L> {
    private final long ttlTicks;
    private final int maxEntries;
    private final Map<L, Map<K, Long>> refusedAt = new HashMap<>();
    private int size;

    /**
     * @param ttlTicks   how long a refusal is remembered, at least 1
     * @param maxEntries bound for remembered refusals, at least 1
     */
    public RefusalMemory(long ttlTicks, int maxEntries) {
        if (ttlTicks < 1)
            throw new IllegalArgumentException("ttlTicks must be at least 1: " + ttlTicks);
        if (maxEntries < 1)
            throw new IllegalArgumentException("maxEntries must be at least 1: " + maxEntries);
        this.ttlTicks = ttlTicks;
        this.maxEntries = maxEntries;
    }

    /** {@code location} refused {@code key} at game time {@code now}. */
    public void record(L location, K key, long now) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(key, "key");
        Map<K, Long> keys = refusedAt.get(location);
        if (keys == null || !keys.containsKey(key)) {
            if (size >= maxEntries) {
                clear();
                keys = null;
            }
            size++;
        }
        if (keys == null) {
            keys = new HashMap<>();
            refusedAt.put(location, keys);
        }
        keys.put(key, now);
    }

    /** Whether {@code location} refused {@code key} less than {@code ttlTicks} before {@code now}. */
    public boolean isRefused(L location, K key, long now) {
        Map<K, Long> keys = refusedAt.get(location);
        if (keys == null)
            return false;
        Long at = keys.get(key);
        if (at == null)
            return false;
        if (now >= at && now - at < ttlTicks)
            return true;
        // Expired (or the clock went backwards): forget it.
        keys.remove(key);
        size--;
        if (keys.isEmpty())
            refusedAt.remove(location);
        return false;
    }

    /** Forgets every refusal of {@code location} (it was read again). */
    public void forget(L location) {
        Map<K, Long> keys = refusedAt.remove(location);
        if (keys != null)
            size -= keys.size();
    }

    public void clear() {
        refusedAt.clear();
        size = 0;
    }

    /** Number of remembered refusals, expired ones included until they are queried. */
    public int size() {
        return size;
    }
}
