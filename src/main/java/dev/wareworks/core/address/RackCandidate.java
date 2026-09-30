package dev.wareworks.core.address;

import java.util.Objects;

/**
 * One way a world column could be a rack position of a network: the branch it would belong to, the position on it, the
 * rack side and the direction that points <b>away</b> from that branch ({@code docs/warehouse-system.md} §1, ADR-033).
 * <p>
 * A column laterally beside a corner is beside a straight rail of two perpendicular branches at once, so
 * {@link NetworkGeometry#candidates} answers with up to four of these — one per horizontal neighbour that is an aisle
 * block. Which one owns the block is decided by the member's own facing: a storage interface faces
 * {@link #awayFromBranch()}, everything else faces its opposite. Every candidate of one column has a <b>distinct</b>
 * {@link #awayFromBranch()} (two candidates with the same one would share their aisle block and their axis, hence be
 * the same branch), so exactly zero or one of them can be satisfied — the ownership rule is total.
 *
 * @param branch          index of the branch this candidate belongs to
 * @param x               position along that branch
 * @param side            rack side relative to that branch's heading
 * @param awayFromBranch  direction from the branch's aisle block to the column, i.e. where a storage interface looks
 */
public record RackCandidate(int branch, int x, Side side, Heading awayFromBranch) {
    public RackCandidate {
        if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH)
            throw new IllegalArgumentException("branch must be in " + RackPosition.FIRST_BRANCH + ".."
                    + RackPosition.MAX_BRANCH + ": " + branch);
        if (x < 0)
            throw new IllegalArgumentException("x must not be negative: " + x);
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(awayFromBranch, "awayFromBranch");
    }

    /** The rack position this candidate names at level {@code y}. */
    public RackPosition at(int y) {
        return new RackPosition(branch, x, y, side);
    }
}
