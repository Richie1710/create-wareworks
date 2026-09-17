package dev.wareworks.core.inventory;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable view of one inventory slot at the moment a snapshot was taken.
 * <p>
 * An empty slot has no key, a count of 0 and a max stack size of 0. An occupied slot has a non-null key, a count of at
 * least 1 and a max stack size of at least 1. The slot limit is whatever the inventory reports for the slot; it may be
 * smaller or larger than the key's max stack size (e.g. drawers report huge limits).
 *
 * @param key          the stored key, or {@code null} for an empty slot
 * @param count        the stored amount; may exceed the max stack size for inventories that ignore stack sizes
 * @param slotLimit    the slot limit reported by the inventory
 * @param maxStackSize the max stack size of the stored key, or 0 for an empty slot
 * @param <K>          the item key type
 */
public record SlotView<K>(@Nullable K key, int count, int slotLimit, int maxStackSize) {
    public SlotView {
        if (slotLimit < 0)
            throw new IllegalArgumentException("slotLimit must not be negative: " + slotLimit);
        if (count < 0)
            throw new IllegalArgumentException("count must not be negative: " + count);
        if (key == null || count == 0) {
            key = null;
            count = 0;
            maxStackSize = 0;
        } else if (maxStackSize < 1) {
            throw new IllegalArgumentException("an occupied slot needs a max stack size of at least 1: " + maxStackSize);
        }
    }

    /** Creates an empty slot with the given limit. */
    public static <K> SlotView<K> empty(int slotLimit) {
        return new SlotView<>(null, 0, slotLimit, 0);
    }

    /** Creates a slot holding {@code count} of {@code key}; a count of 0 yields an empty slot. */
    public static <K> SlotView<K> of(K key, int count, int slotLimit, int maxStackSize) {
        return new SlotView<>(Objects.requireNonNull(key, "key"), count, slotLimit, maxStackSize);
    }

    public boolean isEmpty() {
        return key == null;
    }

    /** Whether this slot currently holds {@code other} (by {@link Object#equals}). */
    public boolean holds(@Nullable K other) {
        return key != null && key.equals(other);
    }
}
