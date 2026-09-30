package dev.wareworks.core.address;

import java.util.Objects;
import java.util.Optional;

/**
 * One straight branch of a warehouse rail network — what a player calls an aisle and what an address names with its
 * letter ({@code docs/warehouse-system.md} §1, ADR-033).
 * <p>
 * The origin is the end of the branch nearer the dock, given as a <b>dock-relative</b> offset in world axes, so a
 * branch is described without a {@code BlockPos} and {@code core.*} stays pure Java. Position {@code x} of the branch
 * lies at {@code (originDx + heading.stepX()·x, originDz + heading.stepZ()·x)}, for {@code x ∈ [0, length]}.
 * <p>
 * Branch {@value RackPosition#FIRST_BRANCH} is the one containing the dock: its origin is {@code (0, 0)} and its
 * heading is the dock's facing, which is why a warehouse with one aisle is described by exactly the numbers it always
 * was.
 *
 * @param index    branch index, {@value RackPosition#FIRST_BRANCH}..{@value RackPosition#MAX_BRANCH}
 * @param originDx X offset of position 0 from the dock block
 * @param originDz Z offset of position 0 from the dock block
 * @param heading  direction from the origin towards the far end
 * @param length   number of rails beyond the origin, {@code 0..}{@value AisleGeometry#MAX_LENGTH}
 */
public record BranchGeometry(int index, int originDx, int originDz, Heading heading, int length) {
    public BranchGeometry {
        if (index < RackPosition.FIRST_BRANCH || index > RackPosition.MAX_BRANCH)
            throw new IllegalArgumentException("branch index must be in " + RackPosition.FIRST_BRANCH + ".."
                    + RackPosition.MAX_BRANCH + ": " + index);
        Objects.requireNonNull(heading, "heading");
        if (length < 0 || length > AisleGeometry.MAX_LENGTH)
            throw new IllegalArgumentException("length must be in 0.." + AisleGeometry.MAX_LENGTH + ": " + length);
    }

    /** The branch a warehouse with one aisle has: origin at the dock, the dock's heading. */
    public static BranchGeometry first(Heading heading, int length) {
        return new BranchGeometry(RackPosition.FIRST_BRANCH, 0, 0, heading, length);
    }

    /** Number of positions including the origin: {@code length + 1}. */
    public int positionCount() {
        return length + 1;
    }

    /** Whether {@code x} is a position of this branch. */
    public boolean contains(int x) {
        return x >= 0 && x <= length;
    }

    /** X offset of position {@code x} from the dock block (no bounds check). */
    public int cellDx(int x) {
        return originDx + heading.stepX() * x;
    }

    /** Z offset of position {@code x} from the dock block (no bounds check). */
    public int cellDz(int x) {
        return originDz + heading.stepZ() * x;
    }

    /** The position of the aisle block at the dock-relative offset {@code (dx, dz)}, if it lies on this branch. */
    public Optional<Integer> positionAt(int dx, int dz) {
        int relX = dx - originDx;
        int relZ = dz - originDz;
        if (heading.lateral(relX, relZ) != 0)
            return Optional.empty();
        int along = heading.along(relX, relZ);
        return contains(along) ? Optional.of(along) : Optional.empty();
    }

    /** The same branch truncated at its far end, which renumbers nothing because the origin is the near end. */
    public BranchGeometry withLength(int newLength) {
        return newLength == length ? this : new BranchGeometry(index, originDx, originDz, heading, newLength);
    }

    /** The same branch under another index. */
    public BranchGeometry withIndex(int newIndex) {
        return newIndex == index ? this : new BranchGeometry(newIndex, originDx, originDz, heading, length);
    }
}
