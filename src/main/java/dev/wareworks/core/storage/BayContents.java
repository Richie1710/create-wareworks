package dev.wareworks.core.storage;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.inventory.CapacityMath;

/**
 * What a bay holds: <b>one key and a count</b>, never a list of slots (M28, issue #20).
 * <p>
 * Both bay families keep their contents here, in whatever unit the family counts in: a rack bay an item key and a
 * number of items ({@code content.storage.RackBayHandler}), a fluid bay a {@code FluidKey} and a number of
 * <b>millibuckets</b> ({@code content.storage.FluidBayHandler}, M30, issue #21). Nothing in this class knows which,
 * because nothing in it needs to.
 * <p>
 * That is the whole of a bay's contents, and it is why a bay can hold 65 536 items — or 256 buckets — without 1 024
 * stacks of save data, without a slot array and without a single {@code ItemStack} or {@code FluidStack} of its own.
 * The capacity is <b>not</b> a field here: it depends on the tier and on the configuration ({@link BayTier#capacity}
 * for an item bay, {@code FluidBayTier#capacityMillibuckets} for a fluid one), both of which may change under a
 * standing bay, so every call that needs it is given it.
 *
 * <h2>The one invariant</h2>
 * {@code stored != null} exactly when {@code count > 0}. A bay therefore <b>forgets what it held the moment it
 * empties</b>, which is what the issue asks of an unfiltered bay: put up a wall, let it fill, and a bay that has been
 * drained takes whatever comes next — the next item type, or the next fluid. There is no fourth field remembering a
 * type beside the contents, because the stored key <i>is</i> the memory.
 *
 * <h2>Why nothing here clamps silently</h2>
 * {@link #insert} and {@link #extract} return what they really accepted and really took, and the caller is expected to
 * believe that number rather than what it asked for — the item-conservation rule of this mod
 * ({@code docs/warehouse-system.md} §8). A capacity <b>below</b> the current count (a config lowered under a bay that
 * is already full) is a legal state: the bay keeps everything and accepts nothing until it drains, so a world load can
 * never destroy an item.
 * <p>
 * Pure Java, no world access: the content layer adapts this to an {@code IItemHandler}
 * ({@code content.storage.RackBayHandler}) and to an {@code IFluidHandler}
 * ({@code content.storage.FluidBayHandler}).
 *
 * <h2>Why {@link #extract} has a per-call cap</h2>
 * An {@code IItemHandler} must bound an extracted stack by its own maximum stack size, so the item side passes one
 * stack of the stored key. A fluid handler has no such rule — a drain of 64 000 mB is one {@code FluidStack} — so the
 * fluid side passes {@code Integer.MAX_VALUE} and the cap never bites. One parameter, not a second method (M30's D8).
 *
 * @param <K> the key type; must implement {@code equals}/{@code hashCode} by the identity of the thing it names
 */
public final class BayContents<K> {
    @Nullable
    private K stored;
    private int count;

    /** The stored item type, or {@code null} while the bay is empty. */
    @Nullable
    public K stored() {
        return stored;
    }

    /** How many items are stored; 0 exactly when {@link #stored()} is {@code null}. */
    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return stored == null;
    }

    /** Whether the bay currently holds {@code key} (by {@link Object#equals}); false for an empty bay. */
    public boolean holds(@Nullable K key) {
        return stored != null && stored.equals(key);
    }

    /**
     * How much of {@code key} this bay could still take, i.e. {@code capacity - count} for the stored key and 0 for any
     * other. Clamped into the non-negative {@code int} range, so an over-full bay answers 0 rather than a negative
     * number.
     *
     * @param capacity how much of {@code key} this bay holds in total, in the family's own unit
     *                 ({@link BayTier#capacity}, {@code FluidBayTier#capacityMillibuckets})
     */
    public int roomFor(K key, long capacity) {
        Objects.requireNonNull(key, "key");
        if (stored != null && !stored.equals(key))
            return 0;
        return CapacityMath.toIntClamped(capacity - count);
    }

    /**
     * Stores up to {@code amount} of {@code key} and answers how much was really accepted: 0 for anything other than
     * what is stored, and 0 for a bay with no room left. A real call that accepted anything also fixes the stored key,
     * which is how an unfiltered bay learns what it holds.
     *
     * @param amount   how much the caller offers, in the family's own unit (a number at or below 0 accepts nothing)
     * @param capacity how much of {@code key} this bay holds in total ({@link BayTier#capacity},
     *                 {@code FluidBayTier#capacityMillibuckets})
     * @param simulate whether to leave the contents untouched and only answer what a real call would accept
     * @return the accepted amount, never more than {@code amount} and never negative
     */
    public int insert(K key, int amount, long capacity, boolean simulate) {
        Objects.requireNonNull(key, "key");
        if (amount <= 0)
            return 0;
        int accepted = Math.min(amount, roomFor(key, capacity));
        if (accepted <= 0)
            return 0;
        if (!simulate) {
            stored = key;
            count += accepted;
        }
        return accepted;
    }

    /**
     * Takes up to {@code amount} of whatever is stored, at most {@code perCall} items in one call, and answers how much
     * was really taken. A real call that empties the bay also <b>forgets the item type</b>.
     *
     * @param perCall the largest amount one call may return — one stack of the stored key for an item handler, whose
     *                contract bounds an extracted stack by its own max stack size, and
     *                {@link Integer#MAX_VALUE} for a fluid handler, which has no such rule (see the class comment);
     *                a value below 1 counts as 1
     * @return the taken amount, 0 for an empty bay
     */
    public int extract(int amount, int perCall, boolean simulate) {
        if (stored == null || amount <= 0)
            return 0;
        int taken = Math.min(Math.min(amount, Math.max(1, perCall)), count);
        if (taken <= 0)
            return 0;
        if (!simulate) {
            count -= taken;
            if (count <= 0) {
                count = 0;
                stored = null; // empty: the bay forgets its type (issue #20)
            }
        }
        return taken;
    }

    /** {@link #extract(int, int, boolean)} for one named key: 0 unless the bay {@linkplain #holds holds} it. */
    public int extract(K key, int amount, int perCall, boolean simulate) {
        Objects.requireNonNull(key, "key");
        return holds(key) ? extract(amount, perCall, simulate) : 0;
    }

    /**
     * Sets the contents outright, for a load from save data: a {@code null} key or a count at or below 0 empties the
     * bay. Nothing is clamped against a capacity here — a count above what the current configuration allows is a legal
     * state the bay keeps (see the class comment), and a count the save data cannot justify at all is bounded by the
     * caller before it reaches this method.
     */
    public void restore(@Nullable K key, int count) {
        if (key == null || count <= 0) {
            clear();
            return;
        }
        this.stored = key;
        this.count = count;
    }

    /** Empties the bay without moving anything: {@code Clearable}, i.e. {@code /setblock} and structure placement. */
    public void clear() {
        stored = null;
        count = 0;
    }

    @Override
    public String toString() {
        return stored == null ? "BayContents[empty]" : "BayContents[" + stored + " x " + count + "]";
    }
}
