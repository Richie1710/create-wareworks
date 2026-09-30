package dev.wareworks.core.warehouse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;

/**
 * Discovery of a warehouse rail network, as pure integer maths ({@code docs/warehouse-system.md} §4, ADR-033).
 * <p>
 * The world part of discovery is one function: {@link CellProbe}, which says what stands at a dock-relative
 * {@code (dx, dz)}. Everything else — which blocks connect, where the chain stops and why, and how the result is cut
 * into straight {@link BranchGeometry branches} — happens here, so it is JUnit-testable without a {@code Level} and the
 * content layer ({@code content.crane.RailNetworkScan}) only has to read block states and never load a chunk.
 * <p>
 * <b>Connectivity is plain orthogonal adjacency</b> of aisle blocks. A rail offers a connection in all four horizontal
 * directions; the network's own dock offers one <b>only towards its facing</b> (so a rail beside the dock is a rack
 * position, not a branch); another dock offers none — it is a wall, which is what keeps two warehouses apart. A rail
 * closed with a wrench is not an aisle block at all. <b>No other block state is read</b>: the rail's axis and its
 * connection flags are cosmetic, so a stale state in an untouched chunk is a wrong picture and never a wrong warehouse.
 * If that is ever "optimised" into reading the connection flags, every world saved before M21 breaks silently.
 * <p>
 * <b>Step one follows a chain.</b> The walk refuses to enter an aisle block with three or more connections
 * ({@link NetworkStop#BRANCHED}) and one it has already taken ({@link NetworkStop#LOOPED}), and reports where it
 * stopped. The result is therefore always a valid network — a shorter warehouse, never none.
 */
public final class RailGraph {
    private RailGraph() {
    }

    /** What stands at one dock-relative position, as far as discovery cares. */
    public enum Cell {
        /** An open warehouse rail: connects in all four horizontal directions. */
        RAIL,
        /** A warehouse rail closed with a wrench: not an aisle block. */
        CLOSED_RAIL,
        /** This network's own dock: connects only towards its facing, i.e. only to position 1 of the first branch. */
        DOCK,
        /** Another stacker crane dock: a wall, so both warehouses keep working. */
        OTHER_DOCK,
        /** Anything else, including air. */
        NONE,
        /** Not loaded — the scan must never load a chunk, so it treats this as a wall and says the scan is partial. */
        UNLOADED
    }

    /** Reads one dock-relative position. {@code (0, 0)} is the dock itself. */
    @FunctionalInterface
    public interface CellProbe {
        Cell at(int dx, int dz);
    }

    /**
     * The bounds discovery works inside; all of them come from the server config.
     *
     * @param maxRails        aisle blocks beyond the dock the walk may take ({@code aisle.maxNetworkRails})
     * @param maxBranches     branches the network may have ({@code aisle.maxBranches}); <b>1 is the M21 off-switch</b>
     *                        and takes a separate path that reads nothing but the dock's own line, so it reproduces
     *                        0.5.0 discovery exactly rather than approximately ({@code Walk#runStraight})
     * @param maxBranchLength rails of one branch ({@code aisle.maxAisleLength}); a longer branch is truncated at its
     *                        <b>far</b> end, which renumbers nothing because its origin is the near end
     * @param height          mast height of the crane
     */
    public record Limits(int maxRails, int maxBranches, int maxBranchLength, int height) {
        public Limits {
            if (maxRails < 0)
                throw new IllegalArgumentException("maxRails must not be negative: " + maxRails);
            maxBranches = Math.clamp(maxBranches, 1, StorageAddress.AISLE_COUNT);
            maxBranchLength = Math.clamp(maxBranchLength, 0, AisleGeometry.MAX_LENGTH);
            height = Math.clamp(height, AisleGeometry.MIN_HEIGHT, AisleGeometry.MAX_HEIGHT);
        }
    }

    /**
     * Walks the network at {@code probe} from its dock and cuts it into branches.
     *
     * @param probe       reads the world (or a test fixture); {@code (0, 0)} must be the dock
     * @param dockHeading the dock's facing, i.e. the heading of branch {@value RackPosition#FIRST_BRANCH}
     * @param limits      the configured bounds
     */
    public static RailNetwork scan(CellProbe probe, Heading dockHeading, Limits limits) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(dockHeading, "dockHeading");
        Objects.requireNonNull(limits, "limits");
        Walk walk = new Walk(probe, dockHeading, limits);
        walk.run();
        return decompose(walk, dockHeading, limits);
    }

    /** Packs a dock-relative offset into one long, so the visited set needs no allocation per cell. */
    public static long cell(int dx, int dz) {
        return ((long) dx << 32) | (dz & 0xFFFFFFFFL);
    }

    public static int cellDx(long packed) {
        return (int) (packed >> 32);
    }

    public static int cellDz(long packed) {
        return (int) packed;
    }

    /**
     * Cuts what the walk took into branches and applies the caps again. The walk already stops at every one of them, so
     * this is the safety net that keeps the result valid for a hand-built chain rather than the usual path.
     */
    private static RailNetwork decompose(Walk walk, Heading dockHeading, Limits limits) {
        List<BranchGeometry> straight = cut(walk.path, dockHeading);
        List<BranchGeometry> kept = new ArrayList<>(straight.size());
        NetworkStop stop = walk.stop;
        long stopCell = walk.stopCell;
        for (BranchGeometry branch : straight) {
            if (kept.size() == limits.maxBranches()) {
                stop = NetworkStop.MAX_BRANCHES;
                stopCell = cell(branch.cellDx(1), branch.cellDz(1));
                break;
            }
            if (branch.length() > limits.maxBranchLength()) {
                int cut = limits.maxBranchLength();
                kept.add(branch.withLength(cut).withIndex(kept.size()));
                stop = NetworkStop.MAX_LENGTH;
                stopCell = cell(branch.cellDx(cut + 1), branch.cellDz(cut + 1));
                break;
            }
            kept.add(branch.withIndex(kept.size()));
        }
        NetworkGeometry geometry = new NetworkGeometry(kept, limits.height());
        int rails = 0;
        for (BranchGeometry branch : kept)
            rails += branch.length();
        return new RailNetwork(geometry, stop, cellDx(stopCell), cellDz(stopCell), rails,
                walk.firstBranchIncomplete, walk.networkIncomplete);
    }

    /**
     * Cuts an ordered chain of aisle blocks into maximal straight runs. The block where the chain turns belongs to
     * <b>both</b> runs — it is their {@code BranchLink} — and two collinear touching rails are therefore always the
     * same branch.
     */
    private static List<BranchGeometry> cut(List<Long> path, Heading dockHeading) {
        List<BranchGeometry> branches = new ArrayList<>();
        int start = 0;
        Heading heading = dockHeading;
        for (int i = 1; i < path.size(); i++) {
            Heading step = stepBetween(path.get(i - 1), path.get(i));
            if (step == heading)
                continue;
            branches.add(branchOf(branches.size(), path.get(start), heading, i - 1 - start));
            start = i - 1;
            heading = step;
        }
        branches.add(branchOf(branches.size(), path.get(start), heading, path.size() - 1 - start));
        return branches;
    }

    /**
     * A branch can never be longer than the address format allows. {@code aisle.maxAisleLength} caps it far below that
     * ({@code 128}), so the clamp here is unreachable with any config and only keeps a hand-built chain from throwing.
     */
    private static int clampBranchLength(int length) {
        return Math.min(length, AisleGeometry.MAX_LENGTH);
    }

    private static BranchGeometry branchOf(int index, long origin, Heading heading, int length) {
        return new BranchGeometry(index, cellDx(origin), cellDz(origin), heading, clampBranchLength(length));
    }

    private static Heading stepBetween(long from, long to) {
        Heading step = Heading.of(cellDx(to) - cellDx(from), cellDz(to) - cellDz(from));
        if (step == null)
            throw new IllegalStateException("chain steps must be one horizontal block");
        return step;
    }

    /**
     * The chain walk itself: breadth is never needed, because a chain has exactly one way on.
     * <p>
     * Every bound is checked <b>before</b> the block is taken, so a warehouse whose caps are reached costs no more to
     * discover than the warehouse the caps allow. That is what makes {@code aisle.maxBranches = 1} not only behave like
     * every version before M21 but cost the same: the walk stops at the first turn.
     */
    private static final class Walk {
        private final CellProbe probe;
        private final Heading dockHeading;
        private final Limits limits;
        private final List<Long> path = new ArrayList<>();
        private final Set<Long> visited = new HashSet<>();
        private NetworkStop stop = NetworkStop.END;
        private long stopCell;
        /** Any position anywhere the walk looked at was unloaded, so the <b>network</b> may be larger than this. */
        private boolean networkIncomplete;
        /**
         * A position that could have carried the branch <b>at the dock</b> further was unloaded, so that one aisle may
         * be longer than counted. Deliberately narrower than {@link #networkIncomplete}: the walk reads the blocks
         * beside the aisle as well now (they may be a turn), and an unloaded block beside an aisle cannot make that
         * aisle longer. Reading the broad flag here would freeze a straight warehouse's length for ever as soon as its
         * rack plane fell outside the loaded area — a warehouse that could then never shrink again (M21 review fix).
         */
        private boolean firstBranchIncomplete;

        Walk(CellProbe probe, Heading dockHeading, Limits limits) {
            this.probe = probe;
            this.dockHeading = dockHeading;
            this.limits = limits;
        }

        void run() {
            long dock = cell(0, 0);
            path.add(dock);
            visited.add(dock);
            stopCell = dock;
            if (limits.maxBranches() == 1) {
                runStraight(dock);
                return;
            }
            long current = dock;
            long previous = dock; // the dock offers only forward, so nothing behind it is ever a continuation
            Heading runHeading = dockHeading;
            int runLength = 0;
            int branches = 1;
            while (true) {
                Heading step = continuationFrom(current, previous, current == dock, runHeading, branches == 1);
                if (step == null)
                    return;
                long next = cell(cellDx(current) + step.stepX(), cellDz(current) + step.stepZ());
                if (visited.contains(next)) {
                    stopAt(NetworkStop.LOOPED, next);
                    return;
                }
                if (path.size() - 1 >= limits.maxRails()) {
                    stopAt(NetworkStop.MAX_RAILS, next);
                    return;
                }
                if (connectionsOf(next) >= 3) {
                    stopAt(NetworkStop.BRANCHED, next);
                    return;
                }
                boolean turn = step != runHeading;
                if (turn && branches >= limits.maxBranches()) {
                    stopAt(NetworkStop.MAX_BRANCHES, next);
                    return;
                }
                if (!turn && runLength >= limits.maxBranchLength()) {
                    stopAt(NetworkStop.MAX_LENGTH, next);
                    return;
                }
                path.add(next);
                visited.add(next);
                previous = current;
                current = next;
                if (turn) {
                    branches++;
                    runHeading = step;
                    runLength = 1; // the block the chain turned on is position 0 of the new branch
                } else {
                    runLength++;
                }
            }
        }

        /**
         * Discovery as every version before M21 did it, which is what {@code aisle.maxBranches = 1} promises: follow
         * the dock's facing, count the rails, stop at the first block that is not one. <b>Nothing beside the line is
         * read at all</b>, so a decorative rail next to an aisle can neither shorten that warehouse nor make its scan
         * partial, and an operator who sets the key gets the 0.5.0 warehouse back rather than something that resembles
         * it (M21 review fix). The rail's axis is still ignored — that is the one stated behaviour change of this
         * milestone and the closed rail is its cure.
         */
        private void runStraight(long dock) {
            long current = dock;
            int rails = 0;
            while (true) {
                int dx = cellDx(current) + dockHeading.stepX();
                int dz = cellDz(current) + dockHeading.stepZ();
                long next = cell(dx, dz);
                Cell what = read(dx, dz);
                if (what != Cell.RAIL) {
                    if (what == Cell.UNLOADED)
                        firstBranchIncomplete = true; // the rails beyond are unknown, exactly as in 0.5.0
                    stopAt(reasonFor(what), next);
                    return;
                }
                if (rails >= limits.maxRails()) {
                    stopAt(NetworkStop.MAX_RAILS, next);
                    return;
                }
                if (rails >= limits.maxBranchLength()) {
                    stopAt(NetworkStop.MAX_LENGTH, next);
                    return;
                }
                path.add(next);
                visited.add(next);
                current = next;
                rails++;
            }
        }

        /**
         * The one direction the chain continues in, or {@code null} with {@link #stop} set. Two continuations mean
         * {@code current} itself has three connections, which the walk would not have entered — except at the dock,
         * whose forward block is the only one it can reach.
         *
         * @param runHeading     the heading of the straight run {@code current} belongs to, i.e. what "straight on" is
         * @param onFirstBranch  whether that run is still the branch at the dock, the only one whose length an
         *                       unloaded block ahead can make longer
         */
        private Heading continuationFrom(long current, long previous, boolean atDock, Heading runHeading,
                                         boolean onFirstBranch) {
            Heading open = null;
            NetworkStop blocked = NetworkStop.END;
            long blockedCell = current;
            for (Heading step : order()) {
                if (atDock && step != dockHeading)
                    continue;
                int dx = cellDx(current) + step.stepX();
                int dz = cellDz(current) + step.stepZ();
                long neighbour = cell(dx, dz);
                if (neighbour == previous)
                    continue;
                Cell what = read(dx, dz);
                if (what == Cell.UNLOADED && onFirstBranch && step == runHeading)
                    firstBranchIncomplete = true;
                if (what == Cell.RAIL) {
                    if (open != null) {
                        stopAt(NetworkStop.BRANCHED, current);
                        return null;
                    }
                    open = step;
                    continue;
                }
                NetworkStop reason = reasonFor(what);
                if (rank(reason) > rank(blocked)) {
                    blocked = reason;
                    blockedCell = neighbour;
                }
            }
            if (open != null)
                return open;
            stopAt(blocked, blockedCell);
            return null;
        }

        /** Connections of an aisle block: rails offer all four sides, this network's dock only towards its facing. */
        private int connectionsOf(long block) {
            int connections = 0;
            for (Heading step : order()) {
                int dx = cellDx(block) + step.stepX();
                int dz = cellDz(block) + step.stepZ();
                Cell what = read(dx, dz);
                if (what == Cell.RAIL)
                    connections++;
                else if (what == Cell.DOCK && dx == 0 && dz == 0
                        && cell(dockHeading.stepX(), dockHeading.stepZ()) == block)
                    connections++;
            }
            return connections;
        }

        private Cell read(int dx, int dz) {
            Cell what = probe.at(dx, dz);
            if (what == null)
                return Cell.NONE;
            if (what == Cell.UNLOADED)
                networkIncomplete = true;
            return what;
        }

        private static NetworkStop reasonFor(Cell what) {
            return switch (what) {
                case UNLOADED -> NetworkStop.UNLOADED;
                case OTHER_DOCK -> NetworkStop.SECOND_DOCK;
                case CLOSED_RAIL -> NetworkStop.CLOSED;
                default -> NetworkStop.END;
            };
        }

        /**
         * Which of several blocked sides is worth naming. An unloaded neighbour wins, because it is the only one that
         * means the network may really be larger; then another dock, which is a fault; then a closed rail, which is
         * what the player asked for; a plain end says nothing.
         */
        private static int rank(NetworkStop reason) {
            return switch (reason) {
                case UNLOADED -> 3;
                case SECOND_DOCK -> 2;
                case CLOSED -> 1;
                default -> 0;
            };
        }

        /** Dock-relative order, so a rotated copy of a build is discovered in the same order. */
        private List<Heading> order() {
            return List.of(dockHeading, dockHeading.right(), dockHeading.left(), dockHeading.opposite());
        }

        private void stopAt(NetworkStop reason, long where) {
            stop = reason;
            stopCell = where;
        }
    }
}
