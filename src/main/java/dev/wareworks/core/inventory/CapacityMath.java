package dev.wareworks.core.inventory;

import java.util.Objects;

/**
 * Capacity estimates over slot views. Pure functions, no world access.
 * <p>
 * These numbers are <b>estimates</b> used to rank candidates. Before a job is created, the planner validates every
 * candidate it considers with a simulated call on the live inventory and falls through to the next one when the live
 * result is lower; transfers always trust the real result (see {@code docs/warehouse-system.md} §5).
 * <p>
 * <b>Insert estimates are upper bounds.</b> A {@link SlotView} carries no acceptance rules, so slot filters
 * ({@code IItemHandler.isItemValid}, {@code Container.canPlaceItem}) and sided insertion rules are not modelled. The
 * estimate equals a simulated insert only for unrestricted inventories (chest, barrel, a plain
 * {@code ItemStackHandler}). It overestimates for restricted ones: e.g. a shulker box rejects shulker boxes, a furnace
 * seen from a side accepts only fuel in one slot, a chiseled bookshelf accepts only books.
 *
 * <h2>Per-slot capacity rule</h2>
 * Most inventories (vanilla containers, {@code ItemStackHandler}) cap a slot at {@code min(slotLimit, maxStackSize)}.
 * Some inventories (e.g. drawers) ignore the item's stack size and only honour their slot limit. A slot that already
 * holds more than the key's max stack size proves that behaviour, so for such a slot the slot limit alone is used.
 * For every other slot the conservative {@code min(slotLimit, maxStackSize)} applies.
 */
public final class CapacityMath {
    private CapacityMath() {
    }

    /**
     * Maximum amount of one key that a slot can hold, following the per-slot capacity rule.
     *
     * @param slotLimit    slot limit reported by the inventory
     * @param maxStackSize max stack size of the key (at least 1)
     * @param currentCount amount of that key currently stored in the slot (0 for an empty slot)
     */
    public static long slotCapacity(int slotLimit, int maxStackSize, int currentCount) {
        requireMaxStackSize(maxStackSize);
        if (slotLimit <= 0)
            return 0;
        if (currentCount > maxStackSize)
            return slotLimit;
        return Math.min(slotLimit, maxStackSize);
    }

    /**
     * Estimated amount of {@code key} that could be inserted into a single slot.
     *
     * @param keyMaxStackSize max stack size of {@code key}; used for empty slots, which carry no stack size of their own
     */
    public static <K> long insertable(SlotView<K> slot, K key, int keyMaxStackSize) {
        Objects.requireNonNull(key, "key");
        requireMaxStackSize(keyMaxStackSize);
        if (slot.isEmpty())
            return slotCapacity(slot.slotLimit(), keyMaxStackSize, 0);
        if (!slot.holds(key))
            return 0;
        long capacity = slotCapacity(slot.slotLimit(), slot.maxStackSize(), slot.count());
        return Math.max(0, capacity - slot.count());
    }

    /**
     * Estimated amount of {@code key} that could be inserted into all given slots together.
     *
     * @param keyMaxStackSize max stack size of {@code key} (at least 1)
     */
    public static <K> long insertable(Iterable<SlotView<K>> slots, K key, int keyMaxStackSize) {
        Objects.requireNonNull(key, "key");
        requireMaxStackSize(keyMaxStackSize);
        long total = 0;
        for (SlotView<K> slot : slots)
            total += insertable(slot, key, keyMaxStackSize);
        return total;
    }

    /** Amount of {@code key} stored in all given slots together, i.e. what could be extracted. */
    public static <K> long extractable(Iterable<SlotView<K>> slots, K key) {
        Objects.requireNonNull(key, "key");
        long total = 0;
        for (SlotView<K> slot : slots) {
            if (slot.holds(key))
                total += slot.count();
        }
        return total;
    }

    /**
     * Maximum amount of one key a handling head carries per trip:
     * {@code min(stacks · maxStackSize, maxItems)} ({@code docs/warehouse-system.md} §7.1).
     *
     * @param maxStackSize max stack size of the key (at least 1)
     * @param stacks       number of stacks the head can hold (not negative)
     * @param maxItems     absolute item cap of the head (not negative)
     */
    public static int carryLimit(int maxStackSize, int stacks, int maxItems) {
        requireMaxStackSize(maxStackSize);
        if (stacks < 0 || maxItems < 0)
            throw new IllegalArgumentException("stacks and maxItems must not be negative: " + stacks + ", " + maxItems);
        return (int) Math.min((long) stacks * maxStackSize, maxItems);
    }

    /** Clamps a long amount into the non-negative int range, e.g. before passing it to an item handler call. */
    public static int toIntClamped(long amount) {
        if (amount <= 0)
            return 0;
        return (int) Math.min(amount, Integer.MAX_VALUE);
    }

    private static void requireMaxStackSize(int maxStackSize) {
        if (maxStackSize < 1)
            throw new IllegalArgumentException("max stack size must be at least 1: " + maxStackSize);
    }
}
