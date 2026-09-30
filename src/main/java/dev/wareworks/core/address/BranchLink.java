package dev.wareworks.core.address;

/**
 * One aisle block that two perpendicular branches share: the corner or junction a crane hands over at (ADR-033).
 * <p>
 * The block has a legal, in-range name on <b>both</b> branches — {@code (branchA, xA)} and {@code (branchB, xB)} — so a
 * hand-over is a rename of one world block and never needs a coordinate outside a branch. Links are <b>derived</b> from
 * the branch list, never saved and never synced, so no save and no packet can contradict the world.
 *
 * @param branchA the lower branch index
 * @param xA      the shared block's position on {@code branchA}
 * @param branchB the higher branch index
 * @param xB      the shared block's position on {@code branchB}
 */
public record BranchLink(int branchA, int xA, int branchB, int xB) {
    public BranchLink {
        if (branchA == branchB)
            throw new IllegalArgumentException("a link joins two different branches: " + branchA);
        if (branchA > branchB)
            throw new IllegalArgumentException("branchA must be the lower index: " + branchA + " > " + branchB);
        if (xA < 0 || xB < 0)
            throw new IllegalArgumentException("link positions must not be negative: " + xA + ", " + xB);
    }

    /** The other branch of this link, or {@code -1} if {@code branch} is not one of its two. */
    public int other(int branch) {
        if (branch == branchA)
            return branchB;
        return branch == branchB ? branchA : -1;
    }

    /** This link's position on {@code branch}, or {@code -1} if {@code branch} is not one of its two. */
    public int positionOn(int branch) {
        if (branch == branchA)
            return xA;
        return branch == branchB ? xB : -1;
    }
}
