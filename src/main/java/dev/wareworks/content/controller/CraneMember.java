package dev.wareworks.content.controller;

import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * One crane of a warehouse with several, as its controller lists it (issue #14, ADR-075 §11): which dock, which number,
 * and where that dock stands in the warehouse's numbering.
 * <p>
 * Crane 1 is the crane at the warehouse's own dock, at position 0 of branch 0. Cranes 2, 3, ... are the member docks
 * the rail scan found, in discovery order, followed by the <b>stranded</b> ones: members whose dock a closed rail cut
 * off from the warehouse while their machine still stands on its rails. A stranded crane stays linked, may finish the
 * job it carries, gets no new one, and has no way home ({@code TrafficHealth#STRANDED}); its site is where its dock was
 * last named and no longer an aisle block of the warehouse.
 *
 * @param number     the crane number, from 1
 * @param dock       the dock block
 * @param siteBranch the branch the dock lies on
 * @param siteX      the dock's position on that branch
 * @param stranded   whether the dock is cut off from the warehouse's rails while its machine is not
 */
public record CraneMember(int number, BlockPos dock, int siteBranch, int siteX, boolean stranded) {
    public CraneMember {
        if (number < 1)
            throw new IllegalArgumentException("crane numbers start at 1: " + number);
        dock = Objects.requireNonNull(dock, "dock").immutable();
    }

    /** The crane at the warehouse's own dock. */
    public static CraneMember primary(BlockPos dock) {
        return new CraneMember(1, dock, 0, 0, false);
    }

    /** The same crane under another number. */
    public CraneMember numbered(int newNumber) {
        return new CraneMember(newNumber, dock, siteBranch, siteX, stranded);
    }
}
