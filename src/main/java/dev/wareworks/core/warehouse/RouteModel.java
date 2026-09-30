package dev.wareworks.core.warehouse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.NetworkGeometry;

/**
 * How a crane gets from one point of a rail network to another ({@code docs/stacker-crane.md} §4, ADR-033), as pure
 * integer and double maths without a {@code Level}.
 * <p>
 * <b>Step one of M21 discovers a chain</b> — every aisle block has at most two connections and there are no loops
 * ({@code RailGraph}) — so between any two points there is <b>exactly one</b> route, and finding it is a walk over at
 * most {@value dev.wareworks.core.address.StorageAddress#AISLE_COUNT} branches rather than a search. The breadth-first
 * walk below therefore does not need to compare costs to be right; it is written so that it still answers
 * deterministically (fewest hand-overs, then the lowest branch indices) on a geometry that is not a chain, which is
 * what step two replaces with a real cost search (design §10).
 * <p>
 * <b>Nothing here is stored.</b> A route is recomputed from the live geometry whenever it is needed, so a crane cannot
 * hold a route to rails a player has taken away: it simply has none, and the content layer turns that into the
 * existing "source or target missing" ladder.
 * <p>
 * <b>Cost.</b> Every question needs the network's {@link BranchLink links}, and deriving them walks every pair of
 * branches. A caller that asks many questions about one shape — the controller ranks every candidate of a planning
 * pass — therefore holds a {@link RouteTable}, which derives them once; the {@code NetworkGeometry} overloads below
 * build a throw-away table and are for the callers that ask once.
 */
public final class RouteModel {
    /** How far outside a branch a position may lie and still count as on it, for double positions. */
    private static final double POSITION_EPSILON = 1.0E-6;

    private RouteModel() {
    }

    /**
     * The route from {@code (fromBranch, fromX)} to {@code (toBranch, toX)}, or empty when the network does not join
     * the two — an unknown branch, a position outside its branch, or two parts of a network that no longer touch.
     */
    public static Optional<CraneRoute> route(NetworkGeometry network, int fromBranch, double fromX, int toBranch,
            double toX) {
        Objects.requireNonNull(network, "network");
        return route(network, network.links(), fromBranch, fromX, toBranch, toX);
    }

    /** {@link #route(NetworkGeometry, int, double, int, double)} with the network's links already derived. */
    static Optional<CraneRoute> route(NetworkGeometry network, List<BranchLink> links, int fromBranch, double fromX,
            int toBranch, double toX) {
        Objects.requireNonNull(network, "network");
        if (!isOn(network, fromBranch, fromX) || !isOn(network, toBranch, toX))
            return Optional.empty();
        if (fromBranch == toBranch)
            return Optional.of(CraneRoute.straight(fromBranch, clamp(network, fromBranch, fromX),
                    clamp(network, toBranch, toX), network.branch(fromBranch).heading()));
        List<BranchLink> path = linkPath(network, links, fromBranch, toBranch);
        if (path == null)
            return Optional.empty();
        List<CraneRoute.Leg> legs = new ArrayList<>(path.size() + 1);
        int branch = fromBranch;
        double entry = clamp(network, fromBranch, fromX);
        for (BranchLink link : path) {
            double exit = link.positionOn(branch);
            legs.add(new CraneRoute.Leg(branch, entry, exit, network.branch(branch).heading()));
            int next = link.other(branch);
            // The hand-over: the same world block, named on the branch the crane drives on next.
            entry = link.positionOn(next);
            branch = next;
        }
        legs.add(new CraneRoute.Leg(branch, entry, clamp(network, toBranch, toX), network.branch(branch).heading()));
        return Optional.of(new CraneRoute(legs));
    }

    /**
     * What the route between the two points costs in blocks — blocks travelled plus {@code turnPenaltyBlocks} per
     * quarter turn — or empty when there is no route.
     * <p>
     * On a warehouse that does not bend this is literally {@code |toX - fromX|}, which is what makes a one-branch
     * network plan exactly as it did before M21.
     */
    public static OptionalDouble routeBlocks(NetworkGeometry network, int fromBranch, double fromX, int toBranch,
            double toX, double turnPenaltyBlocks) {
        Objects.requireNonNull(network, "network");
        return routeBlocks(network, network.links(), fromBranch, fromX, toBranch, toX, turnPenaltyBlocks);
    }

    /**
     * {@link #routeBlocks(NetworkGeometry, int, double, int, double, double)} with the network's links already
     * derived, and <b>without building the route</b>: the cost is the one number the planner reads, and it asks for it
     * once per candidate, so the legs are summed as the walk goes rather than materialised and validated first. The
     * answer is the same as {@link CraneRoute#costBlocks} to the last bit — one hand-over is one leg boundary and one
     * quarter turn.
     */
    static OptionalDouble routeBlocks(NetworkGeometry network, List<BranchLink> links, int fromBranch, double fromX,
            int toBranch, double toX, double turnPenaltyBlocks) {
        Objects.requireNonNull(network, "network");
        if (!Double.isFinite(turnPenaltyBlocks) || turnPenaltyBlocks < 0.0)
            throw new IllegalArgumentException(
                    "turnPenaltyBlocks must be finite and not negative: " + turnPenaltyBlocks);
        if (!isOn(network, fromBranch, fromX) || !isOn(network, toBranch, toX))
            return OptionalDouble.empty();
        double from = clamp(network, fromBranch, fromX);
        double to = clamp(network, toBranch, toX);
        if (fromBranch == toBranch)
            return OptionalDouble.of(Math.abs(to - from));
        List<BranchLink> path = linkPath(network, links, fromBranch, toBranch);
        if (path == null)
            return OptionalDouble.empty();
        double blocks = 0.0;
        int branch = fromBranch;
        double at = from;
        for (BranchLink link : path) {
            blocks += Math.abs(link.positionOn(branch) - at);
            int next = link.other(branch);
            at = link.positionOn(next);
            branch = next;
        }
        blocks += Math.abs(to - at);
        return OptionalDouble.of(blocks + path.size() * turnPenaltyBlocks);
    }

    /** Whether a crane standing on {@code fromBranch} can drive to {@code toBranch} at all. */
    public static boolean reachable(NetworkGeometry network, int fromBranch, int toBranch) {
        Objects.requireNonNull(network, "network");
        return reachable(network, network.links(), fromBranch, toBranch);
    }

    /** {@link #reachable(NetworkGeometry, int, int)} with the network's links already derived. */
    static boolean reachable(NetworkGeometry network, List<BranchLink> links, int fromBranch, int toBranch) {
        Objects.requireNonNull(network, "network");
        if (fromBranch < 0 || fromBranch >= network.branchCount() || toBranch < 0
                || toBranch >= network.branchCount())
            return false;
        return fromBranch == toBranch || linkPath(network, links, fromBranch, toBranch) != null;
    }

    /**
     * The links to cross, in order, to get from one branch to another, or {@code null} when none do. Breadth-first over
     * the branches, so the answer has the fewest hand-overs; ties are broken by the link order of
     * {@link NetworkGeometry#links()}, which is ascending by branch index, so the same warehouse always answers the
     * same route.
     */
    private static List<BranchLink> linkPath(NetworkGeometry network, List<BranchLink> links, int fromBranch,
            int toBranch) {
        int count = network.branchCount();
        BranchLink[] takenTo = new BranchLink[count];
        int[] cameFrom = new int[count];
        boolean[] seen = new boolean[count];
        seen[fromBranch] = true;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(fromBranch);
        while (!queue.isEmpty()) {
            int branch = queue.poll();
            if (branch == toBranch)
                return pathTo(toBranch, fromBranch, takenTo, cameFrom);
            for (BranchLink link : links) {
                int next = link.other(branch);
                if (next < 0 || next >= count || seen[next])
                    continue;
                seen[next] = true;
                takenTo[next] = link;
                cameFrom[next] = branch;
                queue.add(next);
            }
        }
        return null;
    }

    private static List<BranchLink> pathTo(int toBranch, int fromBranch, BranchLink[] takenTo, int[] cameFrom) {
        List<BranchLink> path = new ArrayList<>();
        for (int branch = toBranch; branch != fromBranch; branch = cameFrom[branch])
            path.add(takenTo[branch]);
        List<BranchLink> ordered = new ArrayList<>(path.size());
        for (int i = path.size() - 1; i >= 0; i--)
            ordered.add(path.get(i));
        return ordered;
    }

    private static boolean isOn(NetworkGeometry network, int branch, double x) {
        if (branch < 0 || branch >= network.branchCount() || !Double.isFinite(x))
            return false;
        return x >= -POSITION_EPSILON && x <= network.branch(branch).length() + POSITION_EPSILON;
    }

    /** A position that {@link #isOn} accepted, pulled onto the branch so a leg never names one outside it. */
    private static double clamp(NetworkGeometry network, int branch, double x) {
        BranchGeometry geometry = network.branch(branch);
        return Math.min(Math.max(x, 0.0), geometry.length());
    }
}
