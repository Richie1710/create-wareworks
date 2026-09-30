package dev.wareworks.core.address;

import java.util.List;

/**
 * The set of rack positions a warehouse has, in {@link RackPosition#ORDER} ({@code docs/warehouse-system.md} §1).
 * <p>
 * One aisle has {@link AisleGeometry}; a rail network has {@link NetworkGeometry} (ADR-033). Everything that only walks
 * or validates positions — the membership list above all — speaks this interface, so it does not care how many branches
 * there are. A warehouse with a single branch answers exactly what {@link AisleGeometry} answered before M21, index for
 * index.
 */
public interface RackSpace {
    /** Number of rack positions, i.e. the size of {@link #rackPositions()}. */
    int rackPositionCount();

    /** Whether {@code rack} is one of them. */
    boolean contains(RackPosition rack);

    /**
     * All rack positions in {@link RackPosition#ORDER}. The list is an unmodifiable, random-access view computed on
     * access.
     */
    List<RackPosition> rackPositions();

    /** Index of {@code rack} in {@link #rackPositions()}, or {@code -1} if it lies outside. */
    int indexOf(RackPosition rack);

    /** The rack position at {@code index} of {@link #rackPositions()}. */
    RackPosition rackPosition(int index);
}
