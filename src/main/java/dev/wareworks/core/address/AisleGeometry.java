package dev.wareworks.core.address;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Size of one aisle in aisle-local coordinates ({@code docs/warehouse-system.md} §1): positions {@code x ∈ [0, length]}
 * (0 is the dock column, one position per rail) and levels {@code y ∈ [0, height - 1]}, with a rack on both sides.
 * <p>
 * This is the pure part of the design's {@code AisleGeometry { dock; facing; length; height }}
 * ({@code docs/stacker-crane.md} §3). The world mapping (dock position and facing) lives in the content layer
 * ({@code content.controller.AisleLayout}), so this record stays free of Minecraft types.
 * <p>
 * Limits follow the address format: every rack position of a valid geometry has a {@link StorageAddress}.
 *
 * @param length number of rails, {@code 0..}{@value #MAX_LENGTH}
 * @param height mast height (number of levels), {@value #MIN_HEIGHT}..{@value #MAX_HEIGHT}
 */
public record AisleGeometry(int length, int height) {
    public static final int MAX_LENGTH = StorageAddress.MAX_POSITION;
    public static final int MIN_HEIGHT = 1;
    public static final int MAX_HEIGHT = StorageAddress.MAX_LEVEL;

    private static final Side[] SIDES = Side.values();

    public AisleGeometry {
        if (length < 0 || length > MAX_LENGTH)
            throw new IllegalArgumentException("length must be in 0.." + MAX_LENGTH + ": " + length);
        if (height < MIN_HEIGHT || height > MAX_HEIGHT)
            throw new IllegalArgumentException("height must be in " + MIN_HEIGHT + ".." + MAX_HEIGHT + ": " + height);
    }

    public static AisleGeometry of(int length, int height) {
        return new AisleGeometry(length, height);
    }

    public AisleGeometry withLength(int newLength) {
        return new AisleGeometry(newLength, height);
    }

    public AisleGeometry withHeight(int newHeight) {
        return new AisleGeometry(length, newHeight);
    }

    /** Number of aisle positions including the dock column: {@code length + 1}. */
    public int positionCount() {
        return length + 1;
    }

    /** Number of rack positions on both sides: {@code 2 · (length + 1) · height}. */
    public int rackPositionCount() {
        return SIDES.length * positionCount() * height;
    }

    /** Whether {@code (x, y)} lies inside the aisle: {@code 0 <= x <= length} and {@code 0 <= y < height}. */
    public boolean contains(int x, int y) {
        return x >= 0 && x <= length && y >= 0 && y < height;
    }

    public boolean contains(RackPosition rack) {
        return contains(rack.x(), rack.y());
    }

    /**
     * All rack positions in {@link RackPosition#ORDER} (position, then level, then {@link Side#LEFT} before
     * {@link Side#RIGHT}). The list is an unmodifiable, random-access view computed on access; it allocates no backing
     * storage.
     */
    public List<RackPosition> rackPositions() {
        return new RackPositionList(this);
    }

    /** Index of {@code rack} in {@link #rackPositions()}, or {@code -1} if it lies outside this geometry. */
    public int indexOf(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (!contains(rack))
            return -1;
        return (rack.x() * height + rack.y()) * SIDES.length + rack.side().ordinal();
    }

    /** The rack position at {@code index} of {@link #rackPositions()}. */
    public RackPosition rackPosition(int index) {
        Objects.checkIndex(index, rackPositionCount());
        int side = index % SIDES.length;
        int column = index / SIDES.length;
        return new RackPosition(column / height, column % height, SIDES[side]);
    }

    /**
     * The aisle length after a rail scan ({@code docs/warehouse-system.md} §4).
     * <p>
     * A complete scan (it stopped at a non-rail block or at the cap) is authoritative. An incomplete scan stopped at a
     * position whose chunk is not loaded, so the rails beyond are unknown: the previously known length is kept if it is
     * larger, so that unloading part of an aisle never shrinks it. The result never exceeds {@code maxLength}.
     *
     * @param countedRails   consecutive rails found by the scan
     * @param scanIncomplete whether the scan stopped at an unloaded position
     * @param previousLength the last known length
     * @param maxLength      the configured cap
     */
    public static int scannedLength(int countedRails, boolean scanIncomplete, int previousLength, int maxLength) {
        if (countedRails < 0 || previousLength < 0 || maxLength < 0)
            throw new IllegalArgumentException("lengths must not be negative: counted=" + countedRails
                    + ", previous=" + previousLength + ", max=" + maxLength);
        int counted = Math.min(countedRails, maxLength);
        if (!scanIncomplete)
            return counted;
        return Math.max(counted, Math.min(previousLength, maxLength));
    }

    private static final class RackPositionList extends AbstractList<RackPosition> implements RandomAccess {
        private final AisleGeometry geometry;

        RackPositionList(AisleGeometry geometry) {
            this.geometry = geometry;
        }

        @Override
        public RackPosition get(int index) {
            return geometry.rackPosition(index);
        }

        @Override
        public int size() {
            return geometry.rackPositionCount();
        }

        @Override
        public int indexOf(Object o) {
            return o instanceof RackPosition rack ? geometry.indexOf(rack) : -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            return indexOf(o);
        }

        @Override
        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }
    }
}
