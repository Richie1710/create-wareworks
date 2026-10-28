package dev.wareworks.core.traffic;

import java.util.Objects;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;

/**
 * Where a <b>member dock</b> stands in a warehouse: a stacker crane dock without a controller of its own that faces
 * into the warehouse's rails and so became an aisle block of it, its crane another crane of the warehouse (issue #14,
 * ADR-075, {@code docs/stacker-crane.md} §4.8).
 * <p>
 * Found by {@link dev.wareworks.core.warehouse.RailGraph} and carried in
 * {@link dev.wareworks.core.warehouse.RailNetwork#memberDocks()}. The offset is relative to the warehouse's own
 * (primary) dock, in world axes, like every other offset of the discovery; the name is the one the dock block has in
 * the discovered network. Like every dock it connects only towards its facing, so its block is a dead end of the rails
 * ({@link TrackGraph#isDeadEnd}).
 *
 * @param dx     X offset of the member dock from the primary dock
 * @param dz     Z offset of the member dock from the primary dock
 * @param facing the way the member dock faces: towards the one aisle block it connects to
 * @param branch the branch the dock block lies on
 * @param x      the dock block's position on that branch
 */
public record DockSite(int dx, int dz, Heading facing, int branch, int x) {
    public DockSite {
        Objects.requireNonNull(facing, "facing");
        if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH)
            throw new IllegalArgumentException("branch must be in " + RackPosition.FIRST_BRANCH + ".."
                    + RackPosition.MAX_BRANCH + ": " + branch);
        if (x < 0 || x > AisleGeometry.MAX_LENGTH)
            throw new IllegalArgumentException("x must be in 0.." + AisleGeometry.MAX_LENGTH + ": " + x);
    }

    /** The site of the warehouse's own dock: position 0 of the first branch, facing along it. */
    public static DockSite primary(Heading facing) {
        return new DockSite(0, 0, facing, RackPosition.FIRST_BRANCH, 0);
    }
}
