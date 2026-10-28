package dev.wareworks.core.traffic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * The rails of a warehouse as the traffic of several cranes sees them: one <b>cell</b> per aisle block, and an edge
 * between every two blocks a crane drives from one to the other ({@code docs/stacker-crane.md} §4.8, issue #14,
 * ADR-075).
 * <p>
 * <b>A cell is the unit of reservation.</b> A crane is one block long, so one aisle block is the finest stretch there
 * is, and the coarser stretch a railway signals — from one junction to the next — would make a straight aisle with a
 * dock at each end a single stretch on which two cranes could never both work. Goggles speak of stretches (a run of
 * reserved cells named by its first and last address); this class speaks of cells.
 * <p>
 * <b>Derived from the shape, never saved.</b> The cells are numbered densely, branch by branch and position by
 * position, and the second name a link block has on the higher branch is merged into the cell of its first, so a
 * corner or a junction is one cell. Edges are consecutive positions of one branch, which is exactly the connectivity
 * {@link dev.wareworks.core.warehouse.RailGraph} discovered and {@link dev.wareworks.core.warehouse.RouteCosts} routes
 * on: two blocks of different, parallel branches that happen to lie side by side are not joined, and a dock — the
 * warehouse's own and every member — lies at one end of one branch and so has <b>one</b> neighbour. That every dock is
 * a dead end is the property the whole deadlock argument of ADR-075 rests on ({@link #isDeadEnd}).
 * <p>
 * Pure integers, deterministic everywhere: neighbours are kept in ascending cell order and every search breaks ties by
 * the lower cell index, so the same shape always yields the same paths whatever order the cranes are listed in.
 * Owned by the warehouse layout next to its {@link dev.wareworks.core.warehouse.RouteTable}, derived once per shape.
 * At most {@code aisle.maxNetworkRails + 1} plus the member docks cells, so a search is a few thousand integer steps at
 * worst.
 */
public final class TrackGraph {
    /** No cell: a position outside the network, or a search that found nothing. */
    public static final int NONE = -1;

    private final NetworkGeometry network;
    /** Per branch, per position: the cell. */
    private final int[][] cellOf;
    /** Per cell: the one or two branches it is named on, ascending. */
    private final int[][] namesBranch;
    /** Per cell: its position on each of {@link #namesBranch}. */
    private final int[][] namesX;
    /** Per cell: the neighbouring cells, ascending. */
    private final int[][] neighbours;
    /** Per cell, parallel to {@link #neighbours}: the branch the edge to that neighbour runs along. */
    private final int[][] edgeBranches;
    /** Per cell: the connected part of the rails it belongs to. */
    private final int[] components;
    /** The dock cells, the warehouse's own first and then every member in discovery order. */
    private final int[] docks;
    private final BitSet dockSet = new BitSet();
    /** Cells a search has expanded since this graph was built: the measure the traffic cost bound is pinned in. */
    private long visits;

    private TrackGraph(NetworkGeometry network, int[][] cellOf, int[][] namesBranch, int[][] namesX,
            int[][] neighbours, int[][] edgeBranches, int[] docks) {
        this.network = network;
        this.cellOf = cellOf;
        this.namesBranch = namesBranch;
        this.namesX = namesX;
        this.neighbours = neighbours;
        this.edgeBranches = edgeBranches;
        this.docks = docks;
        for (int dock : docks) {
            if (dock != NONE)
                dockSet.set(dock);
        }
        this.components = label(neighbours);
    }

    /** The track graph of a warehouse of one crane: its own dock and nothing else marked. */
    public static TrackGraph of(NetworkGeometry network) {
        return of(network, List.of());
    }

    /**
     * The track graph of {@code network}, with the warehouse's own dock and every member dock in {@code memberDocks}
     * marked. A member whose position is not part of the network is listed as {@link #NONE}.
     */
    public static TrackGraph of(NetworkGeometry network, List<DockSite> memberDocks) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(memberDocks, "memberDocks");
        int branches = network.branchCount();
        // The B side of every link is the A side's cell: the A branch is the lower index and so numbered first.
        Map<Long, long[]> secondNames = new HashMap<>();
        for (BranchLink link : network.links())
            secondNames.put(key(link.branchB(), link.xB()), new long[] {link.branchA(), link.xA()});
        int[][] cellOf = new int[branches][];
        List<int[]> branchNames = new ArrayList<>();
        List<int[]> xNames = new ArrayList<>();
        for (int b = 0; b < branches; b++) {
            BranchGeometry branch = network.branch(b);
            cellOf[b] = new int[branch.positionCount()];
            for (int x = 0; x <= branch.length(); x++) {
                long[] first = secondNames.get(key(b, x));
                if (first != null) {
                    int cell = cellOf[(int) first[0]][(int) first[1]];
                    cellOf[b][x] = cell;
                    branchNames.set(cell, new int[] {branchNames.get(cell)[0], b});
                    xNames.set(cell, new int[] {xNames.get(cell)[0], x});
                    continue;
                }
                cellOf[b][x] = branchNames.size();
                branchNames.add(new int[] {b});
                xNames.add(new int[] {x});
            }
        }
        int cells = branchNames.size();
        List<List<int[]>> adjacency = new ArrayList<>(cells);
        for (int i = 0; i < cells; i++)
            adjacency.add(new ArrayList<>(2));
        for (int b = 0; b < branches; b++) {
            for (int x = 0; x < cellOf[b].length - 1; x++) {
                int a = cellOf[b][x];
                int c = cellOf[b][x + 1];
                adjacency.get(a).add(new int[] {c, b});
                adjacency.get(c).add(new int[] {a, b});
            }
        }
        int[][] neighbours = new int[cells][];
        int[][] edgeBranches = new int[cells][];
        for (int i = 0; i < cells; i++) {
            List<int[]> edges = adjacency.get(i);
            edges.sort((p, q) -> Integer.compare(p[0], q[0]));
            neighbours[i] = new int[edges.size()];
            edgeBranches[i] = new int[edges.size()];
            for (int e = 0; e < edges.size(); e++) {
                neighbours[i][e] = edges.get(e)[0];
                edgeBranches[i][e] = edges.get(e)[1];
            }
        }
        int[] docks = new int[1 + memberDocks.size()];
        docks[0] = cellOf[RackPosition.FIRST_BRANCH][0];
        for (int i = 0; i < memberDocks.size(); i++) {
            DockSite site = memberDocks.get(i);
            docks[i + 1] = site.branch() < branches && site.x() < cellOf[site.branch()].length
                    ? cellOf[site.branch()][site.x()] : NONE;
        }
        return new TrackGraph(network, cellOf, branchNames.toArray(int[][]::new), xNames.toArray(int[][]::new),
                neighbours, edgeBranches, docks);
    }

    private static long key(int branch, int x) {
        return ((long) branch << 32) | (x & 0xFFFFFFFFL);
    }

    // --- cells ---------------------------------------------------------------------------------------------------

    /** The shape this graph describes. */
    public NetworkGeometry network() {
        return network;
    }

    public int cellCount() {
        return namesBranch.length;
    }

    /** The cell of position {@code x} of {@code branch}, or {@link #NONE} if the network has no such position. */
    public int cellAt(int branch, int x) {
        if (branch < 0 || branch >= cellOf.length || x < 0 || x >= cellOf[branch].length)
            return NONE;
        return cellOf[branch][x];
    }

    /** How many names a cell has: two for a link block, one for every other. */
    public int nameCount(int cell) {
        return namesBranch[cell].length;
    }

    /** The branch of the {@code name}-th name of {@code cell}, ascending. */
    public int branchOf(int cell, int name) {
        return namesBranch[cell][name];
    }

    /** The position of the {@code name}-th name of {@code cell}. */
    public int xOf(int cell, int name) {
        return namesX[cell][name];
    }

    /** The position {@code cell} has on {@code branch}, or {@link #NONE} if it does not lie on that branch. */
    public int xOn(int cell, int branch) {
        for (int name = 0; name < namesBranch[cell].length; name++) {
            if (namesBranch[cell][name] == branch)
                return namesX[cell][name];
        }
        return NONE;
    }

    /** The neighbours of {@code cell}, ascending (a copy). */
    public int[] neighbours(int cell) {
        return neighbours[cell].clone();
    }

    public int degree(int cell) {
        return neighbours[cell].length;
    }

    /** The branch the edge between two neighbouring cells runs along, or {@link #NONE} if they are no neighbours. */
    public int edgeBranch(int a, int b) {
        int[] around = neighbours[a];
        for (int e = 0; e < around.length; e++) {
            if (around[e] == b)
                return edgeBranches[a][e];
        }
        return NONE;
    }

    /** Whether {@code cell} has at most one neighbour: what every dock is (ADR-075, Lemma 2). */
    public boolean isDeadEnd(int cell) {
        return neighbours[cell].length <= 1;
    }

    /** Whether {@code cell} is a dock of this warehouse, its own or a member's. */
    public boolean isDock(int cell) {
        return cell >= 0 && dockSet.get(cell);
    }

    /** The cell of the warehouse's own dock. */
    public int primaryDock() {
        return docks[0];
    }

    /**
     * The dock cells in crane order: index 0 is crane 1 at the warehouse's own dock, then one per member dock in
     * discovery order ({@link #NONE} for a member that is no position of the network).
     */
    public int[] docks() {
        return docks.clone();
    }

    /** Whether two cells lie on the same connected part of the rails, i.e. a crane could drive from one to the other. */
    public boolean connected(int a, int b) {
        return a >= 0 && b >= 0 && components[a] == components[b];
    }

    /**
     * The one or two cells a chassis at {@code x} on {@code branch} covers: {@code floor(x)} and {@code ceil(x)}, one
     * cell when the crane stands exactly on a block. Positions outside the branch are left out, so a crane the rails
     * were taken away under covers nothing here.
     */
    public int[] standingCells(int branch, double x) {
        int low = cellAt(branch, (int) Math.floor(x));
        int high = cellAt(branch, (int) Math.ceil(x));
        if (low == NONE)
            return high == NONE ? new int[0] : new int[] {high};
        if (high == NONE || high == low)
            return new int[] {low};
        return new int[] {low, high};
    }

    /**
     * Every cell {@code route} passes, in driving order, each once: a link block the route hands over at is listed
     * once, and a leg that starts or ends between two blocks lists both. Positions the network does not have are left
     * out.
     */
    public int[] cellsOf(CraneRoute route) {
        Objects.requireNonNull(route, "route");
        int[] out = new int[16];
        int size = 0;
        int last = NONE;
        BitSet seen = new BitSet();
        for (CraneRoute.Leg leg : route.legs()) {
            boolean forward = leg.isForward();
            int from = forward ? (int) Math.floor(leg.fromX()) : (int) Math.ceil(leg.fromX());
            int to = forward ? (int) Math.ceil(leg.toX()) : (int) Math.floor(leg.toX());
            int step = forward ? 1 : -1;
            for (int x = from; forward ? x <= to : x >= to; x += step) {
                int cell = cellAt(leg.branch(), x);
                if (cell == NONE || cell == last || seen.get(cell))
                    continue;
                if (size == out.length)
                    out = Arrays.copyOf(out, size * 2);
                out[size++] = cell;
                seen.set(cell);
                last = cell;
            }
        }
        return Arrays.copyOf(out, size);
    }

    // --- searches ------------------------------------------------------------------------------------------------

    /**
     * Every cell reachable from {@code from} without entering a {@code blocked} cell. A source that is itself blocked
     * still counts as reached, because a crane can always leave the block it stands on.
     */
    public BitSet reachable(int[] from, @Nullable BitSet blocked) {
        BitSet reached = new BitSet(cellCount());
        int[] queue = new int[cellCount()];
        int head = 0;
        int tail = 0;
        for (int cell : from) {
            if (cell >= 0 && !reached.get(cell)) {
                reached.set(cell);
                queue[tail++] = cell;
            }
        }
        while (head < tail) {
            int cell = queue[head++];
            visits++;
            for (int next : neighbours[cell]) {
                if (reached.get(next) || (blocked != null && blocked.get(next)))
                    continue;
                reached.set(next);
                queue[tail++] = next;
            }
        }
        return reached;
    }

    /** Whether {@code to} is reachable from {@code from} without entering a {@code blocked} cell. */
    public boolean canReach(int[] from, int to, @Nullable BitSet blocked) {
        if (to < 0)
            return false;
        return reachable(from, blocked).get(to);
    }

    /**
     * The cheapest path from any cell of {@code from} to {@code to}, as cells in driving order with the start first:
     * every step costs one, a step onto a {@code discouraged} cell one more, a {@code blocked} cell is never entered
     * (unless it is a start or the goal itself is a start). Ties break by the lower cell index, so the answer is the
     * same for the same question every time. Empty when {@code to} cannot be reached.
     */
    public Optional<int[]> path(int[] from, int to, @Nullable BitSet blocked, @Nullable BitSet discouraged) {
        int cells = cellCount();
        if (to < 0 || to >= cells)
            return Optional.empty();
        int[] cost = new int[cells];
        int[] parent = new int[cells];
        Arrays.fill(cost, Integer.MAX_VALUE);
        Arrays.fill(parent, NONE);
        PriorityQueue<Long> open = new PriorityQueue<>();
        for (int cell : from) {
            if (cell < 0 || cell >= cells)
                continue;
            if (cell == to)
                return Optional.of(new int[] {to});
            if (cost[cell] != 0) {
                cost[cell] = 0;
                open.add(entry(0, cell));
            }
        }
        boolean found = false;
        while (!open.isEmpty()) {
            long top = open.poll();
            int at = (int) (top & 0xFFFFFFFFL);
            int atCost = (int) (top >>> 32);
            if (atCost > cost[at])
                continue;
            visits++;
            if (at == to) {
                found = true;
                break;
            }
            for (int next : neighbours[at]) {
                if (blocked != null && blocked.get(next))
                    continue;
                int nextCost = atCost + 1 + (discouraged != null && discouraged.get(next) ? 1 : 0);
                if (nextCost < cost[next] || (nextCost == cost[next] && at < parent[next])) {
                    cost[next] = nextCost;
                    parent[next] = at;
                    open.add(entry(nextCost, next));
                }
            }
        }
        if (!found)
            return Optional.empty();
        int length = 0;
        for (int at = to; at != NONE; at = parent[at])
            length++;
        int[] path = new int[length];
        int index = length;
        for (int at = to; at != NONE; at = parent[at])
            path[--index] = at;
        return Optional.of(path);
    }

    private static long entry(int cost, int cell) {
        return ((long) cost << 32) | cell;
    }

    /**
     * The route a crane at {@code (startBranch, startX)} drives along {@code path}, ending named on {@code endBranch}:
     * consecutive cells along one branch become one leg, and a change of branch is a hand-over at the link block the
     * two branches share, which is exactly the shape {@link CraneRoute} requires. Empty if the path does not start at
     * the crane, is not a path of this graph, or does not end on a cell named on {@code endBranch}.
     */
    public Optional<CraneRoute> routeAlong(int[] path, int startBranch, double startX, int endBranch) {
        Objects.requireNonNull(path, "path");
        if (path.length == 0 || startBranch < 0 || startBranch >= cellOf.length)
            return Optional.empty();
        int branch = startBranch;
        int first = xOn(path[0], branch);
        if (first == NONE || Math.abs(first - startX) >= 1.0)
            return Optional.empty();
        List<CraneRoute.Leg> legs = new ArrayList<>();
        double from = startX;
        double to = first;
        for (int i = 0; i + 1 < path.length; i++) {
            int along = edgeBranch(path[i], path[i + 1]);
            if (along == NONE)
                return Optional.empty();
            if (along != branch) {
                int handOver = xOn(path[i], along);
                if (handOver == NONE)
                    return Optional.empty();
                legs.add(leg(branch, from, to));
                branch = along;
                from = handOver;
            }
            to = xOn(path[i + 1], branch);
        }
        if (endBranch != branch) {
            int end = xOn(path[path.length - 1], endBranch);
            if (end == NONE)
                return Optional.empty();
            legs.add(leg(branch, from, to));
            branch = endBranch;
            from = end;
            to = end;
        }
        legs.add(leg(branch, from, to));
        try {
            return Optional.of(new CraneRoute(legs));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // never seen: every hand-over above is at a link of two perpendicular branches
        }
    }

    private CraneRoute.Leg leg(int branch, double from, double to) {
        return new CraneRoute.Leg(branch, from, to, network.branch(branch).heading());
    }

    /** Cells expanded by every search on this graph so far: what {@code TrafficCostTest} bounds. */
    public long visits() {
        return visits;
    }

    private static int[] label(int[][] neighbours) {
        int[] components = new int[neighbours.length];
        Arrays.fill(components, NONE);
        int[] queue = new int[neighbours.length];
        int next = 0;
        for (int start = 0; start < neighbours.length; start++) {
            if (components[start] != NONE)
                continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            components[start] = next;
            while (head < tail) {
                int cell = queue[head++];
                for (int neighbour : neighbours[cell]) {
                    if (components[neighbour] == NONE) {
                        components[neighbour] = next;
                        queue[tail++] = neighbour;
                    }
                }
            }
            next++;
        }
        return components;
    }

    @Override
    public String toString() {
        return "TrackGraph[" + cellCount() + " cells, " + docks.length + " docks]";
    }
}
