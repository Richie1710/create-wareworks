package dev.wareworks.core.address;

import java.util.Comparator;
import java.util.Objects;

/**
 * A rack position in aisle-local coordinates: the source of truth for a storage location or station
 * ({@code docs/warehouse-system.md} §1-2). Addresses are derived from it, never the other way round.
 *
 * @param x    aisle position: 0 is the dock column, 1 the first rail
 * @param y    level index: 0 is the dock's height
 * @param side rack side relative to the aisle direction
 */
public record RackPosition(int x, int y, Side side) implements Comparable<RackPosition> {
    /** Stable order: by position, then level, then side ({@link Side#LEFT} first). */
    public static final Comparator<RackPosition> ORDER = Comparator.comparingInt(RackPosition::x)
            .thenComparingInt(RackPosition::y)
            .thenComparing(RackPosition::side);

    public RackPosition {
        if (x < 0)
            throw new IllegalArgumentException("x must not be negative: " + x);
        if (y < 0)
            throw new IllegalArgumentException("y must not be negative: " + y);
        Objects.requireNonNull(side, "side");
    }

    public static RackPosition of(int x, int y, Side side) {
        return new RackPosition(x, y, side);
    }

    @Override
    public int compareTo(RackPosition other) {
        return ORDER.compare(this, other);
    }
}
