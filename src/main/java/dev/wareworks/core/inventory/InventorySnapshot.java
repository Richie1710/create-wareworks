package dev.wareworks.core.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable copy of an inventory's contents at one moment, generic over the item key type.
 * <p>
 * Snapshots are taken on demand (never per tick) and are the input for the stock index and for capacity estimates.
 * Per-key totals use {@code long}, because inventories with large slot limits can hold far more than a stack per slot.
 * Iteration order of {@link #totals()} is the order in which keys first appear in the slot list.
 *
 * @param <K> the item key type; must implement {@code equals}/{@code hashCode} by item identity
 */
public final class InventorySnapshot<K> {
    private static final InventorySnapshot<?> EMPTY = new InventorySnapshot<>(List.of());
    /** {@link #emptySlotLimit}: the snapshot has no empty slot. */
    private static final int NO_EMPTY_SLOT = -1;
    /** {@link #emptySlotLimit}: the empty slots have different limits. */
    private static final int MIXED_SLOT_LIMITS = -2;

    private final List<SlotView<K>> slots;
    private final Map<K, Long> totals;
    /** Free space per key in the slots that hold it ({@link CapacityMath#insertable(SlotView, Object, int)}). */
    private final Map<K, Long> roomByKey;
    /** The common limit of all empty slots, {@link #NO_EMPTY_SLOT} or {@link #MIXED_SLOT_LIMITS}. */
    private final int emptySlotLimit;
    private final int usedSlots;
    private final long totalItems;

    private InventorySnapshot(List<SlotView<K>> slots) {
        this.slots = List.copyOf(slots);
        Map<K, Long> sums = new LinkedHashMap<>();
        Map<K, Long> room = new HashMap<>();
        int emptyLimit = NO_EMPTY_SLOT;
        int used = 0;
        long items = 0;
        for (SlotView<K> slot : this.slots) {
            if (slot.isEmpty()) {
                if (emptyLimit == NO_EMPTY_SLOT)
                    emptyLimit = slot.slotLimit();
                else if (emptyLimit != slot.slotLimit())
                    emptyLimit = MIXED_SLOT_LIMITS;
                continue;
            }
            used++;
            items += slot.count();
            sums.merge(slot.key(), (long) slot.count(), Long::sum);
            room.merge(slot.key(), CapacityMath.insertable(slot, slot.key(), slot.maxStackSize()), Long::sum);
        }
        this.totals = Collections.unmodifiableMap(sums);
        this.roomByKey = room;
        this.emptySlotLimit = emptyLimit;
        this.usedSlots = used;
        this.totalItems = items;
    }

    /** A snapshot of an inventory with zero slots, e.g. when no inventory is attached. */
    @SuppressWarnings("unchecked")
    public static <K> InventorySnapshot<K> empty() {
        return (InventorySnapshot<K>) EMPTY;
    }

    /** Creates a snapshot from the given slots, in slot order. The list is copied. */
    public static <K> InventorySnapshot<K> of(List<SlotView<K>> slots) {
        Objects.requireNonNull(slots, "slots");
        return slots.isEmpty() ? empty() : new InventorySnapshot<>(slots);
    }

    public static <K> Builder<K> builder(int expectedSlots) {
        return new Builder<>(expectedSlots);
    }

    /** All slots in slot order (unmodifiable). */
    public List<SlotView<K>> slots() {
        return slots;
    }

    public SlotView<K> slot(int index) {
        return slots.get(index);
    }

    public int totalSlots() {
        return slots.size();
    }

    /** Number of slots holding at least one item. */
    public int usedSlots() {
        return usedSlots;
    }

    public int freeSlots() {
        return slots.size() - usedSlots;
    }

    /** Whether no slot holds an item. A snapshot with zero slots is empty. */
    public boolean isEmpty() {
        return usedSlots == 0;
    }

    /** Sum of all stored items over all keys. */
    public long totalItems() {
        return totalItems;
    }

    /** Stored amount of {@code key}, 0 if absent. */
    public long count(K key) {
        return totals.getOrDefault(key, 0L);
    }

    /** Per-key totals in order of first appearance (unmodifiable). */
    public Map<K, Long> totals() {
        return totals;
    }

    /** Distinct keys in order of first appearance (unmodifiable). */
    public Set<K> keys() {
        return totals.keySet();
    }

    /**
     * The {@code n} keys with the largest totals, largest first. Ties keep the order of first appearance.
     * Returns an empty list for {@code n <= 0}.
     */
    public List<KeyCount<K>> topEntries(int n) {
        return KeyCount.largestFirst(totals, n);
    }

    /**
     * Estimated insertable amount of {@code key}, equal to {@link CapacityMath#insertable(Iterable, Object, int)} over
     * the slots. O(1) when all empty slots share one limit (the usual case; the planner calls this for every candidate
     * location), otherwise one pass over the slots.
     */
    public long insertable(K key, int keyMaxStackSize) {
        Objects.requireNonNull(key, "key");
        long total = roomByKey.getOrDefault(key, 0L);
        if (emptySlotLimit != MIXED_SLOT_LIMITS)
            return total + freeSlots() * CapacityMath.slotCapacity(Math.max(0, emptySlotLimit), keyMaxStackSize, 0);
        for (SlotView<K> slot : slots) {
            if (slot.isEmpty())
                total += CapacityMath.slotCapacity(slot.slotLimit(), keyMaxStackSize, 0);
        }
        return total;
    }

    /** Stored amount of {@code key}, see {@link CapacityMath#extractable(Iterable, Object)}. */
    public long extractable(K key) {
        return count(key);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof InventorySnapshot<?> other && slots.equals(other.slots);
    }

    @Override
    public int hashCode() {
        return slots.hashCode();
    }

    @Override
    public String toString() {
        return "InventorySnapshot[slots=" + slots.size() + ", used=" + usedSlots + ", totals=" + totals + "]";
    }

    /** Collects slots in slot order. Not thread-safe; build once. */
    public static final class Builder<K> {
        private final List<SlotView<K>> slots;

        private Builder(int expectedSlots) {
            this.slots = new ArrayList<>(Math.max(0, expectedSlots));
        }

        public Builder<K> add(SlotView<K> slot) {
            slots.add(Objects.requireNonNull(slot, "slot"));
            return this;
        }

        public Builder<K> addEmpty(int slotLimit) {
            return add(SlotView.empty(slotLimit));
        }

        public Builder<K> add(K key, int count, int slotLimit, int maxStackSize) {
            return add(SlotView.of(key, count, slotLimit, maxStackSize));
        }

        public InventorySnapshot<K> build() {
            return InventorySnapshot.of(slots);
        }
    }
}
