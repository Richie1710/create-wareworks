package dev.wareworks.core.warehouse;

import java.util.List;
import java.util.Objects;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.traffic.DockSite;

/**
 * What one discovery run found ({@link RailGraph#scan}): the network it could take, and where and why it ended
 * ({@code docs/warehouse-system.md} §4, ADR-033).
 * <p>
 * The geometry is always valid — a shorter warehouse, never none — so a player who builds something this version
 * cannot follow keeps the part of it that works and is told where it stops.
 *
 * <b>Two unloaded flags, because they answer two different questions.</b> Since M21 the walk reads the blocks beside an
 * aisle as well, because a rail there is a turn — but a block beside an aisle can never make that aisle <i>longer</i>.
 * So {@code firstBranchIncomplete} is what the dock's own length rule reads, and the broad
 * {@code reachedUnloadedChunk} is what the controller reads before it lets a scan reshape a warehouse. Reading the
 * broad one for the length would freeze a straight warehouse as soon as its rack plane fell outside the loaded area,
 * and it could then never shrink again (M21 review fix).
 *
 * @param geometry              the branches the scan took, with the dock's branch first
 * @param stop                  why the walk ended
 * @param stopDx                X offset of the block the walk stopped at, relative to the dock
 * @param stopDz                Z offset of that block
 * @param rails                 aisle blocks beyond the dock in {@code geometry}
 * @param firstBranchIncomplete whether a position that could have carried the branch <b>at the dock</b> further was in
 *                              a chunk that is not loaded, so that one aisle may be longer than counted
 *                              ({@link AisleGeometry#scannedLength})
 * @param reachedUnloadedChunk  whether any position the scan looked at was in a chunk that is not loaded, so the
 *                              network may really be larger
 * @param memberDocks           the docks without a controller of their own that joined this warehouse as aisle blocks,
 *                              in discovery order: the docks of crane 2, 3, ... (issue #14, ADR-075). Empty for every
 *                              warehouse of one crane
 * @param refusedDocks          docks that faced into the rails but were left out because the warehouse already had
 *                              {@code aisle.maxCranesPerNetwork} cranes
 */
public record RailNetwork(NetworkGeometry geometry, NetworkStop stop, int stopDx, int stopDz, int rails,
                          boolean firstBranchIncomplete, boolean reachedUnloadedChunk, List<DockSite> memberDocks,
                          int refusedDocks) {
    public RailNetwork {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(stop, "stop");
        memberDocks = List.copyOf(Objects.requireNonNull(memberDocks, "memberDocks"));
        if (rails < 0)
            throw new IllegalArgumentException("rails must not be negative: " + rails);
        if (refusedDocks < 0)
            throw new IllegalArgumentException("refusedDocks must not be negative: " + refusedDocks);
    }

    /** A network of one crane: no member dock joined and none was refused, which is every network up to 0.17.1. */
    public RailNetwork(NetworkGeometry geometry, NetworkStop stop, int stopDx, int stopDz, int rails,
            boolean firstBranchIncomplete, boolean reachedUnloadedChunk) {
        this(geometry, stop, stopDx, stopDz, rails, firstBranchIncomplete, reachedUnloadedChunk, List.of(), 0);
    }

    /** Stacker cranes this warehouse has: the one at its own dock plus one per member dock. */
    public int craneCount() {
        return 1 + memberDocks.size();
    }

    /** Number of straight branches, at least one. */
    public int branchCount() {
        return geometry.branchCount();
    }

    /** Rails of the branch at the dock, i.e. the aisle length a warehouse had before M21. */
    public int firstBranchLength() {
        return geometry.firstBranch().length();
    }

    /**
     * The length of the dock's own branch after a scan, under the rule that an incomplete scan never shrinks a
     * warehouse ({@link AisleGeometry#scannedLength}).
     */
    public int resolveFirstBranchLength(int previousLength, int maxLength) {
        return AisleGeometry.scannedLength(firstBranchLength(), firstBranchIncomplete, Math.max(0, previousLength),
                Math.clamp(maxLength, 0, AisleGeometry.MAX_LENGTH));
    }

    /** Whether the scan saw the whole network: it ended at real block ends and no chunk was missing. */
    public boolean isComplete() {
        return !reachedUnloadedChunk && stop.isComplete();
    }
}
