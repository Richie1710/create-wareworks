package dev.wareworks.core.warehouse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.traffic.DockSite;

/**
 * Discovery of a warehouse rail network, as pure integer maths ({@code docs/warehouse-system.md} §4, ADR-033,
 * ADR-035).
 * <p>
 * The world part of discovery is one function: {@link CellProbe}, which says what stands at a dock-relative
 * {@code (dx, dz)}. Everything else — which blocks connect, where the scan stops and why, and how the result is cut
 * into straight {@link BranchGeometry branches} — happens here, so it is JUnit-testable without a {@code Level} and the
 * content layer ({@code content.crane.RailNetworkScan}) only has to read block states and never load a chunk.
 * <p>
 * <b>Connectivity is plain orthogonal adjacency</b> of aisle blocks. A rail offers a connection in all four horizontal
 * directions; the network's own dock offers one <b>only towards its facing</b> (so a rail beside the dock is a rack
 * position, not a branch); another dock offers none — it is a wall, which is what keeps two warehouses apart. A rail
 * closed with a wrench is not an aisle block at all. <b>No other block state is read</b>: the rail's axis and its
 * connection flags are cosmetic, so a stale state in an untouched chunk is a wrong picture and never a wrong warehouse.
 * If that is ever "optimised" into reading the connection flags, every world saved before M21 breaks silently.
 *
 * <h2>The rails may split and may close on themselves (M22, issue #2)</h2>
 * The scan is a <b>breadth-first flood</b> from the dock: every aisle block it can reach belongs to the warehouse, a
 * block three rails meet at is entered like any other, and rails that lead back onto a block already taken simply
 * close a ring. The two reasons that used to say "this version cannot follow that shape" are gone with the
 * restriction.
 * <p>
 * <b>What a branch is.</b> A branch is a <b>maximal straight run</b> of connected aisle blocks along one axis, at
 * least two blocks long — plus branch {@value RackPosition#FIRST_BRANCH}, the run that contains the dock along the
 * dock's facing, which may be the dock alone (the rail-less warehouse). Maximal is what makes the decomposition
 * unambiguous: two collinear touching rails are always the same branch, so at most one branch per axis passes through
 * any block, a comb's main run keeps one letter, and a block two perpendicular branches share is a
 * {@link dev.wareworks.core.address.BranchLink} with a legal name on both.
 * <p>
 * <b>Every block the flood reached belongs to at least one branch</b>, because a reached rail has a connected
 * neighbour and the run through the two of them is two blocks long. And every branch is joined to branch
 * {@value RackPosition#FIRST_BRANCH} through links, because the block a branch was first reached <i>from</i> lies on a
 * perpendicular run through the block it was reached <i>at</i>. So a discovered network is connected by construction;
 * only {@code aisle.maxAisleLength} can cut a part of it loose, and such an aisle is then reported as one the crane
 * cannot reach rather than silently dropped with the player's records in it.
 * <p>
 * <b>Deterministic.</b> Branch {@value RackPosition#FIRST_BRANCH} comes first; the rest are ordered by the flood
 * distance from the dock to the branch's nearest block, then by that block's dock-relative {@code (along, lateral)},
 * then axis X before axis Z. At most one run per (block, axis) exists, so the order is total: the same build always
 * yields the same aisle numbers, whatever order the chunks loaded in. A branch's origin — its position 0 — is the end
 * nearer the dock, so every outbound leg drives forward and extending an aisle at its far end renumbers nothing.
 * <p>
 * <b>Several cranes (issue #14, ADR-075).</b> A dock without a controller of its own ({@link Cell#FREE_DOCK}) that faces
 * the aisle block the flood reaches it from joins the warehouse as a <b>member dock</b>: it is taken as an aisle block,
 * never expanded, and like the warehouse's own dock it connects only towards its facing — so every dock is a dead end
 * of the rails, which is what the traffic of several cranes rests on. Collinear at the far end of an aisle it extends
 * that aisle by one block; beside an aisle it forms a stub aisle of one block. {@link Limits#maxCranes()} caps how many
 * join, and a cap of one reproduces 0.17.1 exactly: every other dock is then a {@link NetworkStop#SECOND_DOCK} wall.
 * <p>
 * <b>Bounded, and every maximum names a number to raise.</b> {@code aisle.maxNetworkRails} stops the flood itself,
 * {@code aisle.maxAisleLength} truncates a branch at its far end, and {@code aisle.maxBranches} and
 * {@code aisle.maxJunctions} keep a <b>prefix</b> of the branch order — which is still a connected warehouse, because
 * a branch's connector always comes before it. A build over a cap therefore yields a smaller <i>working</i> warehouse
 * that says where it stopped, never nothing.
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
        /**
         * A stacker crane dock with a warehouse controller of its own behind it: the primary of another warehouse, and
         * a wall, so both warehouses keep working.
         */
        OTHER_DOCK,
        /** Anything else, including air. */
        NONE,
        /** Not loaded — the scan must never load a chunk, so it treats this as a wall and says the scan is partial. */
        UNLOADED,
        /**
         * A stacker crane dock <b>without</b> a controller of its own (issue #14, ADR-075). If it faces the aisle block
         * the flood reaches it from and {@link Limits#maxCranes()} leaves room, it is taken as an aisle block of this
         * warehouse — a <b>member dock</b>, whose crane becomes another crane of it — and like the warehouse's own dock
         * it connects only towards its facing. Otherwise it is a wall exactly like {@link #OTHER_DOCK}.
         */
        FREE_DOCK
    }

    /** Reads one dock-relative position. {@code (0, 0)} is the dock itself. */
    @FunctionalInterface
    public interface CellProbe {
        Cell at(int dx, int dz);

        /**
         * The facing of the {@link Cell#FREE_DOCK} at {@code (dx, dz)}, asked only for such a cell. {@code null} reads as
         * "faces nowhere useful", which keeps the dock a wall.
         */
        default Heading dockFacing(int dx, int dz) {
            return null;
        }

        /**
         * Whether the {@link Cell#FREE_DOCK} at {@code (dx, dz)} is a crane the warehouse already has (issue #14,
         * ADR-075 §2.5). Such a dock keeps its place: it is counted against {@link Limits#maxCranes()} before any dock
         * the warehouse does not have yet, whatever order the flood meets them in, and it is taken even beyond the cap,
         * so that a crane a lowered cap leaves out can still drive home to its dock before it leaves. A scan that has no
         * warehouse behind it lists none.
         */
        default boolean listedDock(int dx, int dz) {
            return false;
        }

        /**
         * How many places of {@link Limits#maxCranes()} cranes take whose docks the walk cannot reach: stranded members,
         * whose machines still stand on the rails while a closed rail cuts their docks off (issue #14, ADR-075 §2.5).
         * A dock the warehouse does not have yet only gets a place that is left after them.
         */
        default int reservedPlaces() {
            return 0;
        }
    }

    /**
     * The bounds discovery works inside; all of them come from the server config.
     *
     * @param maxRails        aisle blocks beyond the dock the flood may take ({@code aisle.maxNetworkRails})
     * @param maxBranches     branches the network may have ({@code aisle.maxBranches}); <b>1 is the off-switch</b> and
     *                        takes a separate path that reads nothing but the dock's own line, so it reproduces 0.5.0
     *                        discovery exactly rather than approximately ({@code Walk#runStraight})
     * @param maxJunctions    aisle blocks two aisles may share ({@code aisle.maxJunctions}, M22) — what a route search
     *                        is priced in ({@link RouteCosts}); a branch that would push the count over it is left out
     * @param maxBranchLength rails of one branch ({@code aisle.maxAisleLength}); a longer branch is truncated at its
     *                        <b>far</b> end, which renumbers nothing because its origin is the near end
     * @param height          mast height of the crane
     * @param maxCranes       stacker cranes the warehouse may have, the one at its own dock included
     *                        ({@code aisle.maxCranesPerNetwork}, issue #14, ADR-075), clamped to
     *                        {@value #MIN_CRANES}..{@value #MAX_CRANES}. <b>1 is the off switch</b>: every
     *                        {@link Cell#FREE_DOCK} is then a wall reported as {@link NetworkStop#SECOND_DOCK}, which is
     *                        the discovery of 0.17.1 exactly
     */
    public record Limits(int maxRails, int maxBranches, int maxJunctions, int maxBranchLength, int height,
                         int maxCranes) {
        /** The fewest cranes a warehouse may be limited to: its own. */
        public static final int MIN_CRANES = 1;
        /** The most cranes the config can allow on one warehouse. */
        public static final int MAX_CRANES = 16;

        public Limits {
            if (maxRails < 0)
                throw new IllegalArgumentException("maxRails must not be negative: " + maxRails);
            maxBranches = Math.clamp(maxBranches, 1, StorageAddress.AISLE_COUNT);
            maxJunctions = Math.max(0, maxJunctions);
            maxBranchLength = Math.clamp(maxBranchLength, 0, AisleGeometry.MAX_LENGTH);
            height = Math.clamp(height, AisleGeometry.MIN_HEIGHT, AisleGeometry.MAX_HEIGHT);
            maxCranes = Math.clamp(maxCranes, MIN_CRANES, MAX_CRANES);
        }

        /**
         * The bounds of a warehouse of <b>one</b> crane: every free dock is a wall, as it was up to 0.17.1. Every caller
         * from before issue #14 keeps exactly its meaning through this constructor.
         */
        public Limits(int maxRails, int maxBranches, int maxJunctions, int maxBranchLength, int height) {
            this(maxRails, maxBranches, maxJunctions, maxBranchLength, height, MIN_CRANES);
        }
    }

    /**
     * Floods the network at {@code probe} from its dock and cuts it into branches.
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
        return walk.result();
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
     * A branch can never be longer than the address format allows. {@code aisle.maxAisleLength} caps it far below that
     * ({@code 128}), so the clamp here is unreachable with any config and only keeps a hand-built field of rails from
     * throwing.
     */
    private static int clampBranchLength(int length) {
        return Math.min(length, AisleGeometry.MAX_LENGTH);
    }

    /** One maximal straight run of aisle blocks, before it is given an index, an origin and a heading. */
    private record Run(Heading.Axis axis, int fixed, int from, int to, int nearestDistance, int nearestAlong,
                       int nearestLateral, long originCell, boolean fromLowEnd) {
        /** X offset of the run's block at {@code along}, where {@code along} counts along the axis. */
        int dx(int along) {
            return axis == Heading.Axis.X ? along : fixed;
        }

        int dz(int along) {
            return axis == Heading.Axis.X ? fixed : along;
        }

        int blocks() {
            return to - from + 1;
        }

        /** Whether this run is the one branch {@value RackPosition#FIRST_BRANCH} already describes. */
        boolean isSameAs(Heading.Axis otherAxis, int otherFixed, int otherFrom, int otherTo) {
            return axis == otherAxis && fixed == otherFixed && from == otherFrom && to == otherTo;
        }

        BranchGeometry at(int index) {
            Heading heading = axis == Heading.Axis.X ? (fromLowEnd ? Heading.EAST : Heading.WEST)
                    : (fromLowEnd ? Heading.SOUTH : Heading.NORTH);
            return new BranchGeometry(index, cellDx(originCell), cellDz(originCell), heading,
                    clampBranchLength(blocks() - 1));
        }
    }

    /** Branch order: nearest to the dock first, then the lower dock-relative key, then axis X before axis Z. */
    private static final Comparator<Run> ORDER = Comparator.comparingInt(Run::nearestDistance)
            .thenComparingInt(Run::nearestAlong)
            .thenComparingInt(Run::nearestLateral)
            .thenComparingInt(run -> run.axis() == Heading.Axis.X ? 0 : 1);

    /**
     * The flood itself, and the decomposition of what it took.
     * <p>
     * Every bound is checked <b>before</b> a block is taken, so a warehouse whose caps are reached costs no more to
     * discover than the warehouse the caps allow. That is what makes {@code aisle.maxBranches = 1} not only behave
     * like every version before M21 but cost the same: it never looks beside the dock's own line.
     */
    private static final class Walk {
        /** No aisle was left out by {@code aisle.maxBranches}: a {@code dx} of {@link Integer#MIN_VALUE}, which is
         * {@code aisle.maxNetworkRails} orders of magnitude outside anything a scan can reach. */
        private static final long NO_OVERFLOW = Long.MIN_VALUE;

        private final CellProbe probe;
        private final Heading dockHeading;
        private final Limits limits;
        /** The aisle blocks taken, in flood order, so every derived list is built in one deterministic order. */
        private final List<Long> taken = new ArrayList<>();
        /** Flood distance in blocks from the dock, which is what orders the branches and picks their origins. */
        private final Map<Long, Integer> distances = new HashMap<>();
        /** Positions read as {@link Cell#UNLOADED}, so "could the dock's own aisle be longer" needs no second read. */
        private final Set<Long> unloaded = new HashSet<>();
        private NetworkStop stop = NetworkStop.END;
        private long stopCell;
        /** Any position anywhere the scan looked at was unloaded, so the <b>network</b> may be larger than this. */
        private boolean networkIncomplete;
        /** A configured maximum has already been named; the first one found is the one reported. */
        private boolean limitNamed;
        /** The first rail of the first aisle {@code aisle.maxBranches} left out, or {@code -1} if none was. */
        private long overflowRail = NO_OVERFLOW;
        private int rails;
        /** Member docks taken as aisle blocks, in flood order: crane 2, 3, ... (issue #14). */
        private final List<Long> memberDocks = new ArrayList<>();
        /** The facing of every member dock taken, which is the one way it connects. */
        private final Map<Long, Heading> memberFacings = new HashMap<>();
        /** Free docks that faced into the warehouse but found {@link Limits#maxCranes()} reached. */
        private final Set<Long> refusedDocks = new HashSet<>();
        /**
         * Every wall the flood ran into, in the order it met them. The reported stop is resolved from this list at the
         * end, so a free dock first seen from its side and later taken from the block it faces is never named as the
         * place the warehouse stops.
         */
        private final List<Wall> walls = new ArrayList<>();

        Walk(CellProbe probe, Heading dockHeading, Limits limits) {
            this.probe = probe;
            this.dockHeading = dockHeading;
            this.limits = limits;
        }

        void run() {
            long dock = cell(0, 0);
            take(dock, 0);
            stopCell = dock;
            if (limits.maxBranches() == 1) {
                runStraight(dock);
                return;
            }
            flood(dock);
        }

        /**
         * Breadth-first from the dock: a block three rails meet at is taken like any other, and a rail that leads back
         * onto a block already taken closes a ring and is simply not taken twice.
         * <p>
         * Neighbours are visited in dock-relative order (forward, right, left, back), so a rotated copy of a build is
         * flooded in the same order and gets the same aisle numbers.
         */
        private void flood(long dock) {
            Deque<Long> queue = new ArrayDeque<>();
            queue.addLast(dock);
            while (!queue.isEmpty()) {
                long current = queue.removeFirst();
                int distance = distances.get(current) + 1;
                for (Heading step : order()) {
                    // The dock offers a connection only towards its facing, so a rail beside it is a rack position.
                    if (current == dock && step != dockHeading)
                        continue;
                    int dx = cellDx(current) + step.stepX();
                    int dz = cellDz(current) + step.stepZ();
                    long next = cell(dx, dz);
                    if (distances.containsKey(next))
                        continue; // already part of the warehouse: a ring closes here, which is ordinary since M22
                    Cell what = read(dx, dz);
                    if (what == Cell.FREE_DOCK && facesBack(dx, dz, step) && joinsAsMember(next, distance))
                        continue; // a member dock: taken, and never expanded, because it connects only back here
                    if (what != Cell.RAIL) {
                        walls.add(new Wall(reasonFor(what), next));
                        continue;
                    }
                    if (rails >= limits.maxRails()) {
                        nameLimit(NetworkStop.MAX_RAILS, next);
                        return;
                    }
                    take(next, distance);
                    rails++;
                    queue.addLast(next);
                }
            }
            stopAtWall();
        }

        /**
         * Whether the free dock at {@code (dx, dz)}, entered by {@code step}, faces the block it was entered from - the
         * only way a dock offers a connection.
         */
        private boolean facesBack(int dx, int dz, Heading step) {
            return probe.dockFacing(dx, dz) == step.opposite();
        }

        /**
         * Takes a free dock that faces into the warehouse as a member, if {@link Limits#maxCranes()} leaves room. At the
         * cap it is a wall: with a cap of one exactly the {@link NetworkStop#SECOND_DOCK} wall every dock was up to
         * 0.17.1 (so the caller falls through to the wall branch), above one the maximum is named with its key.
         *
         * @return whether the caller is done with this block
         */
        private boolean joinsAsMember(long next, int distance) {
            if (limits.maxCranes() == Limits.MIN_CRANES)
                return false;
            boolean listed = probe.listedDock(cellDx(next), cellDz(next));
            int room = limits.maxCranes() - 1 - Math.max(0, probe.reservedPlaces());
            if (listed && memberDocks.size() >= room)
                leaveOutNewest();
            if (listed || memberDocks.size() < room) {
                take(next, distance);
                memberDocks.add(next);
                memberFacings.put(next, probe.dockFacing(cellDx(next), cellDz(next)));
                return true;
            }
            refusedDocks.add(next);
            nameLimit(NetworkStop.MAX_CRANES, next);
            return true;
        }

        /**
         * Makes room for a dock the warehouse already has: the member taken last that the warehouse does <b>not</b> have
         * yet is left out again and becomes the refused dock. A member dock is never expanded, so nothing else was
         * taken through it. Nothing happens when every member taken so far is listed.
         */
        private void leaveOutNewest() {
            for (int i = memberDocks.size() - 1; i >= 0; i--) {
                long dock = memberDocks.get(i);
                if (probe.listedDock(cellDx(dock), cellDz(dock)))
                    continue;
                memberDocks.remove(i);
                memberFacings.remove(dock);
                taken.remove(Long.valueOf(dock));
                distances.remove(dock);
                refusedDocks.add(dock);
                nameLimit(NetworkStop.MAX_CRANES, dock);
                return;
            }
        }

        /**
         * The most worth naming of the walls the flood ran into ({@link #rank}), the first of them on a tie, leaving out
         * a wall that was taken after all. A plain end at the dock when none is worth naming.
         */
        private void stopAtWall() {
            NetworkStop blocked = NetworkStop.END;
            long blockedCell = cell(0, 0);
            for (Wall wall : walls) {
                if (distances.containsKey(wall.cell()))
                    continue; // a free dock seen from its side first and then taken as a member
                if (rank(wall.reason()) > rank(blocked)) {
                    blocked = wall.reason();
                    blockedCell = wall.cell();
                }
            }
            stopAt(blocked, blockedCell);
        }

        /**
         * Discovery as every version before M21 did it, which is what {@code aisle.maxBranches = 1} promises: follow
         * the dock's facing, count the rails, stop at the first block that is not one. <b>Nothing beside the line is
         * read at all</b>, so a decorative rail next to an aisle can neither shorten that warehouse nor make its scan
         * partial, and an operator who sets the key gets the 0.5.0 warehouse back rather than something that resembles
         * it (M21 review fix). The rail's axis is still ignored — that is the one stated behaviour change of M21 and
         * the closed rail is its cure.
         */
        private void runStraight(long dock) {
            long current = dock;
            int placed = 0;
            while (true) {
                int dx = cellDx(current) + dockHeading.stepX();
                int dz = cellDz(current) + dockHeading.stepZ();
                long next = cell(dx, dz);
                Cell what = read(dx, dz);
                if (what == Cell.FREE_DOCK && limits.maxCranes() > Limits.MIN_CRANES
                        && facesBack(dx, dz, dockHeading)) {
                    // A second dock at the far end of the line, facing back: the second crane of a straight aisle,
                    // which works with branching switched off too (issue #14). Nothing beyond it is read.
                    if (placed >= limits.maxBranchLength()) {
                        nameLimit(NetworkStop.MAX_LENGTH, next);
                        return;
                    }
                    take(next, ++placed);
                    memberDocks.add(next);
                    memberFacings.put(next, dockHeading.opposite());
                    stopAt(NetworkStop.END, cell(dx + dockHeading.stepX(), dz + dockHeading.stepZ()));
                    return;
                }
                if (what != Cell.RAIL) {
                    stopAt(reasonFor(what), next);
                    return;
                }
                if (placed >= limits.maxRails()) {
                    nameLimit(NetworkStop.MAX_RAILS, next);
                    return;
                }
                if (placed >= limits.maxBranchLength()) {
                    nameLimit(NetworkStop.MAX_LENGTH, next);
                    return;
                }
                take(next, ++placed);
                rails++;
                current = next;
            }
        }

        // --- decomposition -----------------------------------------------------------------------------------------

        RailNetwork result() {
            List<BranchGeometry> kept = applyLimits(branches());
            NetworkGeometry geometry = new NetworkGeometry(kept, limits.height());
            BranchGeometry first = kept.get(RackPosition.FIRST_BRANCH);
            int ahead = first.length() + 1;
            // The dock's own aisle may be longer than counted only if the block it could have continued into is
            // unloaded. Deliberately narrower than networkIncomplete: a block beside an aisle may well be a junction,
            // but it can never make that aisle longer, and reading one flag for both questions froze a straight
            // warehouse's length for ever as soon as its rack plane fell outside the loaded area (M21 review fix).
            boolean firstBranchIncomplete = unloaded.contains(cell(first.cellDx(ahead), first.cellDz(ahead)));
            return new RailNetwork(geometry, stop, cellDx(stopCell), cellDz(stopCell), railsOf(kept),
                    firstBranchIncomplete, networkIncomplete, dockSites(geometry), refusedDocks.size());
        }

        /**
         * Every member dock the kept network still contains, in flood order, with the name it has in that network. A
         * member a configured maximum cut away afterwards ({@code aisle.maxAisleLength} at the far end of its branch,
         * {@code aisle.maxBranches} on its stub) is no aisle block any more and so no member either.
         */
        private List<DockSite> dockSites(NetworkGeometry geometry) {
            List<DockSite> sites = new ArrayList<>(memberDocks.size());
            for (long dock : memberDocks) {
                int dx = cellDx(dock);
                int dz = cellDz(dock);
                for (BranchGeometry branch : geometry.branches()) {
                    Optional<Integer> x = branch.positionAt(dx, dz);
                    if (x.isPresent()) {
                        sites.add(new DockSite(dx, dz, memberFacings.get(dock), branch.index(), x.get()));
                        break;
                    }
                }
            }
            return sites;
        }

        /** Branch {@value RackPosition#FIRST_BRANCH} and then every other maximal run, in branch order. */
        private List<BranchGeometry> branches() {
            List<BranchGeometry> ordered = new ArrayList<>();
            BranchGeometry first = firstBranch();
            ordered.add(first);
            if (limits.maxBranches() == 1)
                return ordered;
            Heading.Axis firstAxis = dockHeading.axis();
            int firstFrom = Math.min(0, first.length() * step(dockHeading, firstAxis));
            int firstTo = Math.max(0, first.length() * step(dockHeading, firstAxis));
            List<Run> runs = new ArrayList<>();
            collectRuns(Heading.Axis.X, runs);
            collectRuns(Heading.Axis.Z, runs);
            runs.removeIf(run -> run.isSameAs(firstAxis, 0, firstFrom, firstTo));
            runs.sort(ORDER);
            // aisle.maxBranches counts the aisle at the dock, so this many further ones fit. The rest are dropped
            // here, before the (quadratic) junction count ever sees them, and the first of them is remembered so the
            // cap can be reported with the number to raise.
            int room = limits.maxBranches() - 1;
            for (int i = 0; i < Math.min(runs.size(), room); i++)
                ordered.add(runs.get(i).at(ordered.size()));
            if (runs.size() > room)
                overflowRail = firstRailOf(runs.get(room));
            return ordered;
        }

        /** The first rail of a run beyond its origin — the block a dropped aisle is named by. */
        private long firstRailOf(Run run) {
            int origin = run.fromLowEnd() ? run.from() : run.to();
            int next = run.fromLowEnd() ? origin + 1 : origin - 1;
            return cell(run.dx(next), run.dz(next));
        }

        /** The run that contains the dock along its facing, which may be the dock alone. */
        private BranchGeometry firstBranch() {
            int length = 0;
            while (true) {
                long here = cell(dockHeading.stepX() * length, dockHeading.stepZ() * length);
                long next = cell(dockHeading.stepX() * (length + 1), dockHeading.stepZ() * (length + 1));
                // A member dock at the far end of the line ends it: it connects only back the way it faces.
                if (!distances.containsKey(next) || !connects(here, next))
                    break;
                length++;
            }
            return BranchGeometry.first(dockHeading, clampBranchLength(length));
        }

        /**
         * Every maximal straight run of at least two blocks along {@code axis}. A block is the start of one when the
         * block before it along the axis is not an aisle block of this network or does not connect to it, which is the
         * one definition that makes two collinear touching rails the same branch everywhere.
         */
        private void collectRuns(Heading.Axis axis, List<Run> out) {
            for (long block : taken) {
                int dx = cellDx(block);
                int dz = cellDz(block);
                long before = axis == Heading.Axis.X ? cell(dx - 1, dz) : cell(dx, dz - 1);
                if (distances.containsKey(before) && connects(before, block))
                    continue;
                long cursor = block;
                while (true) {
                    int cx = cellDx(cursor);
                    int cz = cellDz(cursor);
                    long next = axis == Heading.Axis.X ? cell(cx + 1, cz) : cell(cx, cz + 1);
                    if (!distances.containsKey(next) || !connects(cursor, next))
                        break;
                    cursor = next;
                }
                int from = axis == Heading.Axis.X ? dx : dz;
                int to = axis == Heading.Axis.X ? cellDx(cursor) : cellDz(cursor);
                if (to <= from)
                    continue; // a single block is no aisle of its own; it belongs to the run of the other axis
                out.add(runOf(axis, axis == Heading.Axis.X ? dz : dx, from, to));
            }
        }

        /** One run with its order key and its origin end resolved: nearest to the dock, then the lower key. */
        private Run runOf(Heading.Axis axis, int fixed, int from, int to) {
            int nearestAlong = 0;
            int nearestLateral = 0;
            int nearestDistance = Integer.MAX_VALUE;
            for (int at = from; at <= to; at++) {
                int dx = axis == Heading.Axis.X ? at : fixed;
                int dz = axis == Heading.Axis.X ? fixed : at;
                int distance = distances.get(cell(dx, dz));
                int along = dockHeading.along(dx, dz);
                int lateral = dockHeading.lateral(dx, dz);
                if (distance < nearestDistance || (distance == nearestDistance
                        && (along < nearestAlong || (along == nearestAlong && lateral < nearestLateral)))) {
                    nearestDistance = distance;
                    nearestAlong = along;
                    nearestLateral = lateral;
                }
            }
            int lowDx = axis == Heading.Axis.X ? from : fixed;
            int lowDz = axis == Heading.Axis.X ? fixed : from;
            int highDx = axis == Heading.Axis.X ? to : fixed;
            int highDz = axis == Heading.Axis.X ? fixed : to;
            boolean fromLowEnd = isNearer(lowDx, lowDz, highDx, highDz);
            long origin = fromLowEnd ? cell(lowDx, lowDz) : cell(highDx, highDz);
            return new Run(axis, fixed, from, to, nearestDistance, nearestAlong, nearestLateral, origin, fromLowEnd);
        }

        /** Whether the first end is the one a branch is numbered from: nearer the dock, then the lower key. */
        private boolean isNearer(int dxA, int dzA, int dxB, int dzB) {
            int distanceA = distances.get(cell(dxA, dzA));
            int distanceB = distances.get(cell(dxB, dzB));
            if (distanceA != distanceB)
                return distanceA < distanceB;
            int alongA = dockHeading.along(dxA, dzA);
            int alongB = dockHeading.along(dxB, dzB);
            if (alongA != alongB)
                return alongA < alongB;
            return dockHeading.lateral(dxA, dzA) <= dockHeading.lateral(dxB, dzB);
        }

        /**
         * The configured maxima, applied to the branch order:
         * <ol>
         * <li>{@code aisle.maxAisleLength} truncates a branch at its <b>far</b> end, which renumbers nothing. An aisle
         * that only touched the cut-off part is <b>kept</b> rather than deleted: it is then an aisle the crane cannot
         * reach, which the warehouse says on its goggles and the planner refuses jobs towards — far better than
         * dropping a player's chests out of the address space because a number in a config file is too small.</li>
         * <li>{@code aisle.maxBranches} and {@code aisle.maxJunctions} keep a <b>prefix</b> of the order. A prefix is
         * still a connected warehouse: a branch is reached through a perpendicular run whose nearest block is one step
         * closer to the dock, so its connector always sorts before it.</li>
         * </ol>
         */
        private List<BranchGeometry> applyLimits(List<BranchGeometry> ordered) {
            List<BranchGeometry> sized = new ArrayList<>(ordered.size());
            for (BranchGeometry branch : ordered) {
                if (branch.length() <= limits.maxBranchLength()) {
                    sized.add(branch);
                    continue;
                }
                int cut = limits.maxBranchLength();
                nameLimit(NetworkStop.MAX_LENGTH, cell(branch.cellDx(cut + 1), branch.cellDz(cut + 1)));
                sized.add(branch.withLength(cut));
            }
            List<BranchGeometry> kept = new ArrayList<>(sized.size());
            for (BranchGeometry branch : sized) {
                List<BranchGeometry> with = new ArrayList<>(kept);
                with.add(branch.withIndex(kept.size()));
                if (junctionsOf(with) > limits.maxJunctions()) {
                    nameLimit(NetworkStop.MAX_JUNCTIONS, cell(branch.cellDx(1), branch.cellDz(1)));
                    return kept;
                }
                kept.add(with.getLast());
            }
            if (overflowRail != NO_OVERFLOW)
                nameLimit(NetworkStop.MAX_BRANCHES, overflowRail);
            return kept;
        }

        private int junctionsOf(List<BranchGeometry> branches) {
            return new NetworkGeometry(branches, limits.height()).links().size();
        }

        /**
         * Aisle blocks beyond the dock the warehouse really contains. A block two aisles share is counted once, which
         * for a chain is literally the sum of the branch lengths the previous version reported. A member dock is an
         * aisle block but no rail, so it is not counted (issue #14).
         */
        private int railsOf(List<BranchGeometry> branches) {
            Set<Long> blocks = new HashSet<>();
            for (BranchGeometry branch : branches) {
                for (int x = 0; x <= branch.length(); x++)
                    blocks.add(cell(branch.cellDx(x), branch.cellDz(x)));
            }
            memberDocks.forEach(blocks::remove);
            return Math.max(0, blocks.size() - 1);
        }

        // --- reading ------------------------------------------------------------------------------------------------

        /**
         * Whether two orthogonally adjacent aisle blocks of this network offer each other a connection. Only a dock
         * ever refuses: it offers one towards its facing and nowhere else, which is what keeps a rail beside it a rack
         * position and a run that passes the dock from behind a separate aisle. Since issue #14 that holds for every
         * dock of the warehouse, its members included, so every dock is a dead end of the rails (ADR-075).
         */
        private boolean connects(long a, long b) {
            Heading facingA = dockFacingOf(a);
            if (facingA != null && stepBetween(a, b) != facingA)
                return false;
            Heading facingB = dockFacingOf(b);
            return facingB == null || stepBetween(b, a) == facingB;
        }

        /** The facing of the dock at {@code block}, the warehouse's own or a member, or {@code null} for a rail. */
        private Heading dockFacingOf(long block) {
            if (block == cell(0, 0))
                return dockHeading;
            return memberFacings.get(block);
        }

        private void take(long block, int distance) {
            taken.add(block);
            distances.put(block, distance);
        }

        private Cell read(int dx, int dz) {
            Cell what = probe.at(dx, dz);
            if (what == null)
                return Cell.NONE;
            if (what == Cell.UNLOADED) {
                networkIncomplete = true;
                unloaded.add(cell(dx, dz));
            }
            return what;
        }

        private static NetworkStop reasonFor(Cell what) {
            return switch (what) {
                case UNLOADED -> NetworkStop.UNLOADED;
                case OTHER_DOCK, FREE_DOCK -> NetworkStop.SECOND_DOCK;
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
            if (limitNamed)
                return; // a maximum with a number to raise is the more useful answer than the block beyond it
            stop = reason;
            stopCell = where;
        }

        /** A configured maximum: it outranks every other reason, and the first one found is the one reported. */
        private void nameLimit(NetworkStop reason, long where) {
            if (limitNamed)
                return;
            limitNamed = true;
            stop = reason;
            stopCell = where;
        }

        private static int step(Heading heading, Heading.Axis axis) {
            return axis == Heading.Axis.X ? heading.stepX() : heading.stepZ();
        }

        /** A block the flood could not take, and why. */
        private record Wall(NetworkStop reason, long cell) {
        }

        private static Heading stepBetween(long from, long to) {
            Heading step = Heading.of(cellDx(to) - cellDx(from), cellDz(to) - cellDz(from));
            if (step == null)
                throw new IllegalStateException("aisle blocks connect over one horizontal block");
            return step;
        }
    }
}
