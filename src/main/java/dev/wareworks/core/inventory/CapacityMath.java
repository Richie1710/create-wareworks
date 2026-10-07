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
 *
 * <h2>The one count the numbers cannot decide</h2>
 * At <b>exactly</b> one full stack the two rules disagree and nothing in the three numbers settles it: an ordinary slot
 * is full, a drawer has barely started. Guessing either way is wrong — answering "full" is what killed drawers behind
 * a warehouse interface at one stack, and answering "room" would plan jobs into an ordinary slot that cannot take
 * them. So this class does not guess: {@link #capacityUnknown} marks such a slot, and an estimate over a whole
 * inventory whose known room is <b>zero</b> while at least one such slot is present answers {@link #UNKNOWN_CAPACITY}
 * instead of 0. The planner's capacity gate is a pre-filter, so an unknown answer costs one live simulate at the only
 * place that can tell a drawer from a chest, and the live result stays authoritative as always.
 * <p>
 * "Such a slot" is deliberately narrow: only a slot limit <b>above</b> {@link #STACK_SIZE_CEILING} can mean anything
 * other than a stack-size rule, so every vanilla container and every plain {@code ItemStackHandler} keeps its cheap,
 * exact zero and reaches no live call at all. A bulk slot whose limit is at or below that ceiling stays conservative
 * and loses at most {@code slotLimit - maxStackSize} items of headroom in that one slot — bounded and small, unlike
 * the 65 536-item drawer this rule exists for.
 */
public final class CapacityMath {
    /**
     * Insert estimate meaning "unknown, ask the live inventory". {@link Long#MAX_VALUE}, so a caller that only asks
     * "is there room?" lets the location through; the same value as {@code core.job.JobPlanner.UNKNOWN_CAPACITY},
     * which an estimate for a location without a snapshot has always answered.
     */
    public static final long UNKNOWN_CAPACITY = Long.MAX_VALUE;
    /**
     * The largest count any single item stack can reach, mirroring {@code Item.ABSOLUTE_MAX_STACK_SIZE} = 99 (this
     * layer is pure Java and cannot see it; GameTest {@code stacksizeceiling} pins the mirror). Every ordinary slot
     * limit is at or below it: {@code ItemStackHandler.getSlotLimit} returns exactly that constant and
     * {@code InvWrapper.getSlotLimit} returns {@code Container.getMaxStackSize()}, which vanilla defaults to 99 — and
     * both then clamp an insert by the item's own stack size. A slot limit <b>above</b> it can therefore not be a
     * stack-size rule at all, which is the only evidence of a drawer available before a live call.
     */
    public static final int STACK_SIZE_CEILING = 99;

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
     * Whether this slot's remaining capacity for {@code key} cannot be decided from the snapshot: it holds
     * <b>exactly</b> one full stack of {@code key} and its slot limit is above {@link #STACK_SIZE_CEILING}, so it is
     * full under stack-size rules and has room under its slot limit, and nothing here says which applies.
     */
    public static <K> boolean capacityUnknown(SlotView<K> slot, K key) {
        Objects.requireNonNull(key, "key");
        return slot.holds(key) && slot.count() == slot.maxStackSize() && slot.slotLimit() > STACK_SIZE_CEILING;
    }

    /**
     * Estimated amount of {@code key} that could be inserted into all given slots together, or
     * {@link #UNKNOWN_CAPACITY} when no slot has known room while at least one is {@link #capacityUnknown}.
     *
     * @param keyMaxStackSize max stack size of {@code key} (at least 1)
     */
    public static <K> long insertable(Iterable<SlotView<K>> slots, K key, int keyMaxStackSize) {
        Objects.requireNonNull(key, "key");
        requireMaxStackSize(keyMaxStackSize);
        long total = 0;
        boolean unknown = false;
        for (SlotView<K> slot : slots) {
            total += insertable(slot, key, keyMaxStackSize);
            unknown |= capacityUnknown(slot, key);
        }
        return total == 0 && unknown ? UNKNOWN_CAPACITY : total;
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
