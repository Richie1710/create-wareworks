package dev.wareworks.core.address;

import java.util.Comparator;
import java.util.Objects;

/**
 * A rack position in aisle-local coordinates: the source of truth for a storage location or station
 * ({@code docs/warehouse-system.md} §1-2). Addresses are derived from it, never the other way round.
 * <p>
 * A warehouse is one connected set of rails, decomposed into straight <b>branches</b> — what a player calls an aisle
 * and what an address names with its letter (ADR-033, M21). {@code branch} says which of them the position belongs to;
 * {@code x}, {@code y} and {@code side} are local to that branch exactly as they were local to the single aisle before.
 * <p>
 * <b>Branch 0 is the default everywhere.</b> {@link #RackPosition(int, int, Side)} and {@link #of(int, int, Side)} both
 * mean branch 0, so a one-branch warehouse — every warehouse up to 0.5.0 — is described by exactly the values it always
 * was, and persistence omits the branch when it is 0.
 *
 * @param branch branch index within the warehouse, {@value #FIRST_BRANCH}..{@value #MAX_BRANCH}; the bound is the
 *               address format's ({@link StorageAddress#AISLE_COUNT}), because every branch needs a letter
 * @param x      position along the branch: 0 is the branch origin, 1 the first rail beyond it
 * @param y      level index: 0 is the dock's height
 * @param side   rack side relative to the branch direction
 */
public record RackPosition(int branch, int x, int y, Side side) implements Comparable<RackPosition> {
    /** Branch of every position of a warehouse that has only one, and the value an absent branch reads as. */
    public static final int FIRST_BRANCH = 0;
    /** Largest branch index: one per address letter ({@link StorageAddress#AISLE_COUNT}). */
    public static final int MAX_BRANCH = StorageAddress.AISLE_COUNT - 1;

    /**
     * Stable order: by branch, then position, then level, then side ({@link Side#LEFT} first).
     * <p>
     * Branch is the <b>first</b> key on purpose: on a one-branch warehouse the order is then literally the order it was
     * before M21, so no round-robin cursor and no planner tie-break moves in an existing world.
     */
    public static final Comparator<RackPosition> ORDER = Comparator.comparingInt(RackPosition::branch)
            .thenComparingInt(RackPosition::x)
            .thenComparingInt(RackPosition::y)
            .thenComparing(RackPosition::side);

    public RackPosition {
        if (branch < FIRST_BRANCH || branch > MAX_BRANCH)
            throw new IllegalArgumentException("branch must be in " + FIRST_BRANCH + ".." + MAX_BRANCH + ": " + branch);
        if (x < 0)
            throw new IllegalArgumentException("x must not be negative: " + x);
        if (y < 0)
            throw new IllegalArgumentException("y must not be negative: " + y);
        Objects.requireNonNull(side, "side");
    }

    /** A position on {@link #FIRST_BRANCH}, the only branch a warehouse with one aisle has. */
    public RackPosition(int x, int y, Side side) {
        this(FIRST_BRANCH, x, y, side);
    }

    /** A position on {@link #FIRST_BRANCH}, the only branch a warehouse with one aisle has. */
    public static RackPosition of(int x, int y, Side side) {
        return new RackPosition(FIRST_BRANCH, x, y, side);
    }

    public static RackPosition of(int branch, int x, int y, Side side) {
        return new RackPosition(branch, x, y, side);
    }

    /** Whether this position lies on {@link #FIRST_BRANCH}. */
    public boolean isOnFirstBranch() {
        return branch == FIRST_BRANCH;
    }

    /** The same local position on another branch. */
    public RackPosition withBranch(int newBranch) {
        return newBranch == branch ? this : new RackPosition(newBranch, x, y, side);
    }

    @Override
    public int compareTo(RackPosition other) {
        return ORDER.compare(this, other);
    }
}
