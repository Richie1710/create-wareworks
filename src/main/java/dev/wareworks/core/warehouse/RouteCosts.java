package dev.wareworks.core.warehouse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.StorageAddress;

/**
 * What every way through a warehouse's rails costs, on a network that may <b>split and may contain loops</b>
 * ({@code docs/stacker-crane.md} §4, {@code docs/warehouse-system.md} §4, ADR-033).
 * <p>
 * <b>Why this exists.</b> Until M22 a warehouse was a chain, so between any two of its points there was exactly
 * <b>one</b> route and finding it was a walk, not a search. A T, a cross or a ring offers several, and they are not
 * equally good: the cost of a trip is {@code blocks travelled + turns · crane.turnPenaltyBlocks}, so which way round a
 * ring is cheaper depends on what a quarter turn costs. The planner ranks every candidate of a pass by that number, so
 * it has to be <b>exact</b> (not an estimate), <b>the same every time</b> (or the same warehouse would plan a different
 * job on every reload) and <b>cheap per candidate</b>.
 *
 * <h2>The graph</h2>
 * A crane's position is a branch and a position on it. Two perpendicular branches share one aisle block — a
 * {@link BranchLink} — and the crane hands over there by turning a quarter and taking the block's name on the other
 * branch. So the only decisions on a trip are <b>where to hand over</b>, and the search space is the set of
 * <b>junction nodes</b>: one per link <i>per branch of that link</i>, i.e. "standing on the shared block, named on this
 * branch". There are {@code 2 ·} the number of links of them, at most {@code 2 · aisle.maxJunctions}.
 * <ul>
 * <li>Handing over at a node costs {@code turnPenaltyBlocks} and one turn, and moves the machine by <b>nothing</b> —
 * it is the same world block.</li>
 * <li>Driving along a branch between two of its nodes costs the blocks between them and no turn. Two links on one
 * branch always sit at different positions, because at most one branch per axis passes through a block (ADR-033), so
 * such an edge is never free.</li>
 * </ul>
 * Both edge kinds are symmetric, so the cost matrix is symmetric and {@code M(u, v) == M(v, u)}.
 *
 * <h2>What is exact about it</h2>
 * A route's cost is one scalar in blocks, and a block of travel and a quarter turn are both <b>integers</b> between two
 * junction nodes. So the matrix holds an integer pair {@code (blocks, turns)} per node pair and the cost is
 * {@code blocks + turns · turnPenaltyBlocks}: nothing is rounded, nothing is approximated, and the only non-integer
 * parts of a trip are its two end stretches, from where the machine stands to the first hand-over and from the last one
 * to the target.
 * <p>
 * <b>A cheapest route never drives one aisle twice.</b> If it did, the stretch between the two visits could be replaced
 * by driving straight along that aisle, which travels no further (the detour's blocks are at least its own
 * displacement along that axis) and turns at least twice less. That is what makes a route a sequence of distinct
 * branches, bounds the turns of any answer by {@value StorageAddress#AISLE_COUNT}{@code - 1}, and is why a
 * {@link CraneRoute} — which refuses two legs in a row on one branch — can always be built from one.
 *
 * <h2>Determinism</h2>
 * Of two routes the cheaper wins; of two that cost the same the one with fewer turns. Two that cost the same and turn
 * as often are told apart by the <b>pair of junctions the trip was priced between</b> ({@link #pick}): the lower
 * position on the first aisle, then the lower position on the last, then the junctions' own indices. Which hand-overs
 * the route then really uses is a second, separate decision — {@link #nodePath} walks the cheapest way between that
 * pair and, of several steps that keep the cost, always takes the one onto the lower-numbered aisle, then the one at
 * the lower position.
 * <p>
 * The two are deliberately <b>not</b> the same key, and the distinction is not cosmetic: a trip priced as leaving its
 * first aisle at one junction may be driven straight past that junction and hand over at a later one, because driving
 * on along an aisle costs no turn. So a tie is broken by where the trip was <b>priced</b>, never by which aisle the
 * route hands over onto first — stating it the other way round would describe a route that is not built (M22 review
 * fix). Nothing in either decision depends on iteration order, hashing or the order rails were placed in, so <b>the
 * same build always plans and drives the same job</b>.
 *
 * <h2>Cost of asking</h2>
 * <ul>
 * <li>Two points of <b>one</b> aisle: {@code |toX - fromX|}, with no graph touched at all. That is the whole of a
 * warehouse that does not bend, and of most questions on one that does.</li>
 * <li>Two points of different aisles: one matrix cell per pair of (junction on the first aisle, junction on the
 * second), i.e. {@link #junctionsOn(int)}{@code (from) ·}{@link #junctionsOn(int)}{@code (to)} integer reads — a
 * handful on every shape a player builds, and <b>no search</b>.</li>
 * <li>The matrix itself is derived <b>one single-source pass at a time, on demand</b>: a row is computed the first time
 * a question starts at that junction and kept ({@link #rowsComputed()}). A warehouse therefore pays only for the aisles
 * its jobs really start on, never more than one pass per junction node for the whole life of this object, and
 * <b>nothing at all</b> while its rails are only asked about within one aisle.</li>
 * </ul>
 * Pure integer and double maths, no Minecraft types. Immutable apart from that cache of derived rows, so this object is
 * safe to keep and to share between the server thread and the client's; two threads may at worst compute the same row
 * twice and both get the same numbers.
 */
public final class RouteCosts {
    /** How far outside a branch a position may lie and still count as on it, for double positions. */
    private static final double POSITION_EPSILON = 1.0E-6;

    /** Bits of a packed matrix cell that hold the turns; the rest holds the blocks. */
    private static final int TURN_BITS = 6;
    private static final int TURN_MASK = (1 << TURN_BITS) - 1;

    /** A node pair the rails do not join, and a "no node" marker. */
    private static final int NONE = -1;

    private final NetworkGeometry network;
    private final List<BranchLink> links;
    private final double turnPenaltyBlocks;

    /** Branch a node is named on, its position there, and the node on the other branch of the same link. */
    private final int[] nodeBranch;
    private final int[] nodeX;
    private final int[] nodePartner;

    /** The nodes of each branch, ascending by position; the branches a position-less lookup needs. */
    private final int[][] branchNodes;

    /**
     * The junction before and after a node on the aisle it is named on, or {@link #NONE}.
     * <p>
     * These are the only along-the-aisle edges the search needs, and the reason is the geometry: driving from one
     * junction of an aisle to a further one passes <b>through</b> the ones between at no extra cost and no turn, so the
     * blocks between two junctions are the sum of the gaps between the ones in between. Linking every pair instead
     * would be the same distances at {@code junctions²} edges, and a main run with fifteen side aisles would carry 225
     * of them where 14 say the same thing.
     */
    private final int[] nodeBefore;
    private final int[] nodeAfter;

    /** Which connected part of the rails each branch belongs to: the reachability answer, free of any cost. */
    private final int[] branchComponent;

    /** Single-source rows, derived on demand and then kept ({@link #row}). */
    private final AtomicReferenceArray<int[]> rows;
    private final AtomicInteger rowsComputed = new AtomicInteger();

    private RouteCosts(NetworkGeometry network, List<BranchLink> links, double turnPenaltyBlocks) {
        this.network = Objects.requireNonNull(network, "network");
        this.turnPenaltyBlocks = turnPenaltyBlocks;
        List<BranchLink> usable = new ArrayList<>(Objects.requireNonNull(links, "links").size());
        for (BranchLink link : links) {
            if (fits(network, link))
                usable.add(link);
        }
        this.links = List.copyOf(usable);
        int count = this.links.size();
        nodeBranch = new int[2 * count];
        nodeX = new int[2 * count];
        nodePartner = new int[2 * count];
        for (int i = 0; i < count; i++) {
            BranchLink link = this.links.get(i);
            nodeBranch[2 * i] = link.branchA();
            nodeX[2 * i] = link.xA();
            nodePartner[2 * i] = 2 * i + 1;
            nodeBranch[2 * i + 1] = link.branchB();
            nodeX[2 * i + 1] = link.xB();
            nodePartner[2 * i + 1] = 2 * i;
        }
        branchNodes = nodesPerBranch(network.branchCount(), nodeBranch, nodeX);
        nodeBefore = new int[nodeBranch.length];
        nodeAfter = new int[nodeBranch.length];
        Arrays.fill(nodeBefore, NONE);
        Arrays.fill(nodeAfter, NONE);
        for (int[] nodes : branchNodes) {
            for (int i = 0; i < nodes.length; i++) {
                nodeBefore[nodes[i]] = i > 0 ? nodes[i - 1] : NONE;
                nodeAfter[nodes[i]] = i + 1 < nodes.length ? nodes[i + 1] : NONE;
            }
        }
        branchComponent = components(network.branchCount(), this.links);
        rows = new AtomicReferenceArray<>(nodeBranch.length);
    }

    /**
     * The costs of one shape at one turn price. Deriving them reads the branch list and nothing else; every route a
     * caller then asks for is answered from it.
     *
     * @param turnPenaltyBlocks blocks of travel a quarter turn is worth ({@code crane.turnPenaltyBlocks}); {@code 0}
     *                          is a free, instant turn, and the cheapest route is then simply the shortest one
     */
    public static RouteCosts of(NetworkGeometry network, double turnPenaltyBlocks) {
        Objects.requireNonNull(network, "network");
        return of(network, network.links(), turnPenaltyBlocks);
    }

    /** {@link #of(NetworkGeometry, double)} with the shape's {@link NetworkGeometry#links() links} already derived. */
    public static RouteCosts of(NetworkGeometry network, List<BranchLink> links, double turnPenaltyBlocks) {
        requirePenalty(turnPenaltyBlocks);
        return new RouteCosts(network, links, turnPenaltyBlocks);
    }

    public NetworkGeometry network() {
        return network;
    }

    /** The aisle blocks two perpendicular branches share, i.e. this network's corners, tees and crosses. */
    public List<BranchLink> links() {
        return links;
    }

    /** Blocks of travel one quarter turn is worth here ({@code crane.turnPenaltyBlocks}). */
    public double turnPenaltyBlocks() {
        return turnPenaltyBlocks;
    }

    /** Junctions of this network: shared aisle blocks, i.e. what {@code aisle.maxJunctions} bounds. */
    public int junctionCount() {
        return links.size();
    }

    /** Places a route may hand over at: two per junction, one for each of its branches. */
    public int nodeCount() {
        return nodeBranch.length;
    }

    /** Junctions on one aisle — the factor that bounds what one route question costs. */
    public int junctionsOn(int branch) {
        return branch < 0 || branch >= branchNodes.length ? 0 : branchNodes[branch].length;
    }

    /**
     * Which connected part of the rails an aisle belongs to, or {@code -1} for an aisle this network does not have.
     * Two aisles with the same answer are joined by rails; two with different answers are not, however close they
     * stand.
     */
    public int componentOf(int branch) {
        return branch < 0 || branch >= branchComponent.length ? NONE : branchComponent[branch];
    }

    /** Whether a crane named on {@code fromBranch} can drive to {@code toBranch} at all. */
    public boolean reachable(int fromBranch, int toBranch) {
        int from = componentOf(fromBranch);
        return from != NONE && from == componentOf(toBranch);
    }

    /**
     * Whether a machine standing at {@code (fromBranch, fromX)} can really drive to {@code (toBranch, toX)} — the
     * question {@code CraneMotion} answers, asked from the machine's own <b>point</b> and not from its branch index
     * (M21 review fix). A branch that a broken rail made shorter than the crane's position still exists and still
     * meets its neighbours, so the branch-only question says yes while every route from that machine is empty.
     * <p>
     * A machine already named on the branch it has to reach always can: it drives straight at the target along the one
     * line it stands on, which is also how it comes back onto rails that became shorter under it.
     */
    public boolean canDrive(int fromBranch, double fromX, int toBranch, double toX) {
        if (fromBranch == toBranch)
            return fromBranch >= 0 && fromBranch < network.branchCount();
        return isOn(fromBranch, fromX) && isOn(toBranch, toX) && reachable(fromBranch, toBranch);
    }

    /**
     * What the cheapest route between the two points costs in blocks — blocks travelled plus
     * {@link #turnPenaltyBlocks} per quarter turn — or empty when the rails do not join them.
     * <p>
     * On two points of one aisle this is literally {@code |toX - fromX|}, which is what makes a warehouse that does not
     * bend plan exactly as it did before there were corners. It is the number the planner reads, so it is answered
     * without building the route.
     */
    public OptionalDouble costBlocks(int fromBranch, double fromX, int toBranch, double toX) {
        if (!isOn(fromBranch, fromX) || !isOn(toBranch, toX))
            return OptionalDouble.empty();
        double from = clamp(fromBranch, fromX);
        double to = clamp(toBranch, toX);
        if (fromBranch == toBranch)
            return OptionalDouble.of(Math.abs(to - from));
        Pick pick = pick(fromBranch, from, toBranch, to);
        return pick == null ? OptionalDouble.empty() : OptionalDouble.of(pick.cost);
    }

    /**
     * The cheapest route itself, as the legs a crane drives, or empty when the rails do not join the two points. The
     * route a machine drives and the cost a planner ranked it by are therefore the same decision, taken once here, so
     * the two can never mean different things by "the way there".
     */
    public Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX) {
        if (!isOn(fromBranch, fromX) || !isOn(toBranch, toX))
            return Optional.empty();
        double from = clamp(fromBranch, fromX);
        double to = clamp(toBranch, toX);
        if (fromBranch == toBranch)
            return Optional.of(CraneRoute.straight(fromBranch, from, to, headingOf(fromBranch)));
        Pick pick = pick(fromBranch, from, toBranch, to);
        if (pick == null)
            return Optional.empty();
        int[] path = nodePath(pick.fromNode, pick.toNode);
        if (path == null)
            return Optional.empty(); // a route whose cost is known always has a first step; never seen, never trusted
        List<CraneRoute.Leg> legs = new ArrayList<>(path.length);
        int branch = fromBranch;
        double entry = from;
        for (int i = 0; i + 1 < path.length; i++) {
            int at = path[i];
            int next = path[i + 1];
            if (nodeBranch[at] == nodeBranch[next])
                continue; // driving on along the same branch: the leg only gets longer
            legs.add(new CraneRoute.Leg(branch, entry, nodeX[at], headingOf(branch)));
            branch = nodeBranch[next];
            entry = nodeX[next];
        }
        legs.add(new CraneRoute.Leg(branch, entry, to, headingOf(branch)));
        return Optional.of(new CraneRoute(legs));
    }

    /**
     * Single-source passes derived so far — the whole of the work this object has done beyond reading the branch list.
     * It never exceeds {@link #nodeCount()} for the life of the object, so a shape that is kept while its rails do not
     * change is costed once and no more, however many questions are asked about it.
     */
    int rowsComputed() {
        return rowsComputed.get();
    }

    /** Matrix cells one route question between these two aisles reads: the bound this class promises per candidate. */
    int cellsPerQuestion(int fromBranch, int toBranch) {
        return fromBranch == toBranch ? 0 : junctionsOn(fromBranch) * junctionsOn(toBranch);
    }

    @Override
    public String toString() {
        return "RouteCosts[" + network.branchCount() + " aisles, " + junctionCount() + " junctions, turn "
                + turnPenaltyBlocks + " blocks]";
    }

    // --- the choice: which junctions a cheapest route leaves and joins on -----------------------------------------

    /**
     * The pair of junctions a cheapest trip from {@code (fromBranch, fromX)} to {@code (toBranch, toX)} is
     * <b>priced</b> between, with what the whole trip costs — or {@code null} when the rails do not join them.
     * <p>
     * Every route between two different branches leaves the first one at one of its junctions and arrives on the last
     * one at one of its junctions, so minimising over those pairs is exact: the matrix cell between them is already the
     * cheapest of everything in between. <b>Both</b> {@link #costBlocks} and {@link #route} go through here, which is
     * what makes the cost a planner ranks and the way a machine drives one and the same answer.
     * <p>
     * A priced junction is not necessarily one the route turns at. The cheapest way out of {@code fromNode} may run
     * <b>along</b> the first aisle past it and hand over further on at no extra turn, which costs exactly the same and
     * is therefore the same answer; {@link #nodePath} picks that way, and {@link Pick#beats} is careful to rank only
     * what is priced here (M22 review fix).
     */
    private Pick pick(int fromBranch, double fromX, int toBranch, double toX) {
        if (!reachable(fromBranch, toBranch))
            return null;
        Pick best = null;
        for (int from : branchNodes[fromBranch]) {
            int[] row = row(from);
            double lead = Math.abs(nodeX[from] - fromX);
            for (int to : branchNodes[toBranch]) {
                int cell = row[to];
                if (cell == NONE)
                    continue;
                int blocks = cell >>> TURN_BITS;
                int turns = cell & TURN_MASK;
                double cost = lead + blocks + Math.abs(toX - nodeX[to]) + turns * turnPenaltyBlocks;
                Pick candidate = new Pick(from, to, cost, turns);
                if (best == null || candidate.beats(best, nodeX))
                    best = candidate;
            }
        }
        return best;
    }

    /**
     * Which hand-overs a cheapest route uses, in order, from {@code fromNode} to {@code toNode}.
     * <p>
     * Walked forward one node at a time, taking only a step that keeps the rest of the trip exactly as cheap as the
     * matrix says it is — in integers, so "exactly" means exactly. Of several such steps the one onto the lower aisle
     * is taken, and on one aisle the one at the lower position, which is what makes a ring with two equal ways round
     * always drive the same way. Returns {@code null} if no step keeps the cost, which cannot happen for a pair the
     * matrix joins.
     */
    private int[] nodePath(int fromNode, int toNode) {
        int[] toTarget = row(toNode); // symmetric: the cost from any node to the target is its cell in this one row
        int[] path = new int[nodeCount() + 1];
        int length = 0;
        int at = fromNode;
        path[length++] = at;
        while (at != toNode) {
            if (length >= path.length)
                return null;
            int remaining = toTarget[at];
            if (remaining == NONE)
                return null;
            int blocksLeft = remaining >>> TURN_BITS;
            int turnsLeft = remaining & TURN_MASK;
            // The hand-over at this very block, and the next junction either way along the aisle it is named on.
            int step = NONE;
            if (keepsTheCost(toTarget, nodePartner[at], 0, 1, blocksLeft, turnsLeft))
                step = nodePartner[at];
            step = alongOrBetter(toTarget, at, nodeBefore[at], blocksLeft, turnsLeft, step);
            step = alongOrBetter(toTarget, at, nodeAfter[at], blocksLeft, turnsLeft, step);
            if (step == NONE)
                return null;
            at = step;
            path[length++] = at;
        }
        return Arrays.copyOf(path, length);
    }

    /** {@code along} if driving on to it keeps the cost and it is preferred over {@code step}, else {@code step}. */
    private int alongOrBetter(int[] toTarget, int at, int along, int blocksLeft, int turnsLeft, int step) {
        if (along == NONE)
            return step;
        int blocks = Math.abs(nodeX[at] - nodeX[along]);
        if (!keepsTheCost(toTarget, along, blocks, 0, blocksLeft, turnsLeft))
            return step;
        return step == NONE || prefers(along, step) ? along : step;
    }

    /** Whether stepping to {@code next} over an edge of {@code (blocks, turns)} leaves exactly the cost promised. */
    private boolean keepsTheCost(int[] toTarget, int next, int blocks, int turns, int blocksLeft, int turnsLeft) {
        if (next == NONE)
            return false;
        int remaining = toTarget[next];
        if (remaining == NONE)
            return false;
        return blocks + (remaining >>> TURN_BITS) == blocksLeft && turns + (remaining & TURN_MASK) == turnsLeft;
    }

    /** Of two equally good next blocks, the one on the lower aisle, then the one at the lower position. */
    private boolean prefers(int candidate, int current) {
        if (nodeBranch[candidate] != nodeBranch[current])
            return nodeBranch[candidate] < nodeBranch[current];
        if (nodeX[candidate] != nodeX[current])
            return nodeX[candidate] < nodeX[current];
        return candidate < current;
    }

    /** A chosen pair of hand-overs with what the whole trip costs; see {@link #pick}. */
    private record Pick(int fromNode, int toNode, double cost, int turns) {
        /**
         * The deterministic order of two priced trips: cheaper, then fewer turns, then the pair of junctions itself —
         * the lower position on the first aisle, then on the last, then the node indices, which are unique.
         * <p>
         * Two junctions of one aisle always sit at different positions (at most one aisle per axis runs through a
         * block, ADR-033), so the positions alone already decide; the indices are the belt and braces that make the
         * order total whatever shape a save hands over.
         */
        boolean beats(Pick other, int[] nodeX) {
            if (cost != other.cost)
                return cost < other.cost;
            if (turns != other.turns)
                return turns < other.turns;
            if (nodeX[fromNode] != nodeX[other.fromNode])
                return nodeX[fromNode] < nodeX[other.fromNode];
            if (nodeX[toNode] != nodeX[other.toNode])
                return nodeX[toNode] < nodeX[other.toNode];
            return fromNode != other.fromNode ? fromNode < other.fromNode : toNode < other.toNode;
        }
    }

    // --- the matrix, one row at a time ----------------------------------------------------------------------------

    /**
     * The costs from one junction node to every other, derived the first time they are asked for and kept afterwards.
     * <p>
     * Two threads that ask at once may both derive the row; they derive the same numbers from the same immutable shape,
     * so the loser's copy is simply dropped.
     */
    private int[] row(int source) {
        int[] known = rows.get(source);
        if (known != null)
            return known;
        int[] derived = singleSource(source);
        rowsComputed.incrementAndGet();
        if (rows.compareAndSet(source, null, derived))
            return derived;
        return rows.get(source);
    }

    /**
     * One single-source pass over the junction nodes, cheapest first, ordered by cost and then by turns — a key that
     * cannot fall along an edge (blocks never decrease, and a hand-over always adds a turn), so the pass is exact even
     * when a turn is free.
     */
    private int[] singleSource(int source) {
        int count = nodeCount();
        int[] blocks = new int[count];
        int[] turns = new int[count];
        boolean[] settled = new boolean[count];
        Arrays.fill(blocks, NONE);
        blocks[source] = 0;
        turns[source] = 0;
        for (int step = 0; step < count; step++) {
            int at = NONE;
            double bestCost = 0.0;
            int bestTurns = 0;
            for (int node = 0; node < count; node++) {
                if (settled[node] || blocks[node] == NONE)
                    continue;
                double cost = blocks[node] + turns[node] * turnPenaltyBlocks;
                if (at == NONE || cost < bestCost || (cost == bestCost && turns[node] < bestTurns)) {
                    at = node;
                    bestCost = cost;
                    bestTurns = turns[node];
                }
            }
            if (at == NONE)
                break;
            settled[at] = true;
            relax(blocks, turns, settled, nodePartner[at], blocks[at], turns[at] + 1);
            relaxAlong(blocks, turns, settled, at, nodeBefore[at]);
            relaxAlong(blocks, turns, settled, at, nodeAfter[at]);
        }
        int[] row = new int[count];
        for (int node = 0; node < count; node++)
            row[node] = blocks[node] == NONE ? NONE : (blocks[node] << TURN_BITS) | turns[node];
        return row;
    }

    /** Driving on along the aisle to the next junction: the blocks between them, and no turn. */
    private void relaxAlong(int[] blocks, int[] turns, boolean[] settled, int at, int next) {
        if (next == NONE)
            return;
        relax(blocks, turns, settled, next, blocks[at] + Math.abs(nodeX[at] - nodeX[next]), turns[at]);
    }

    private void relax(int[] blocks, int[] turns, boolean[] settled, int node, int viaBlocks, int viaTurns) {
        if (node == NONE || settled[node])
            return;
        if (blocks[node] != NONE) {
            double known = blocks[node] + turns[node] * turnPenaltyBlocks;
            double candidate = viaBlocks + viaTurns * turnPenaltyBlocks;
            if (candidate > known || (candidate == known && viaTurns >= turns[node]))
                return;
        }
        blocks[node] = viaBlocks;
        turns[node] = viaTurns;
    }

    // --- shape ----------------------------------------------------------------------------------------------------

    private Heading headingOf(int branch) {
        return network.branch(branch).heading();
    }

    private boolean isOn(int branch, double x) {
        if (branch < 0 || branch >= network.branchCount() || !Double.isFinite(x))
            return false;
        return x >= -POSITION_EPSILON && x <= network.branch(branch).length() + POSITION_EPSILON;
    }

    /** A position {@link #isOn} accepted, pulled onto the branch so a leg never names one outside it. */
    private double clamp(int branch, double x) {
        BranchGeometry geometry = network.branch(branch);
        return Math.min(Math.max(x, 0.0), geometry.length());
    }

    /**
     * Whether a link really names two <b>perpendicular</b> branches of this shape at positions they have. Links are
     * derived from the branch list and always do; one that does not is <b>ignored</b> rather than thrown over, because
     * a route that misses a corner is a longer trip while an exception in the middle of a planning pass is a warehouse
     * that stops. The perpendicularity is what makes every hand-over exactly a quarter turn, which {@link CraneRoute}
     * refuses to be built without.
     */
    private static boolean fits(NetworkGeometry network, BranchLink link) {
        int count = network.branchCount();
        if (link.branchA() >= count || link.branchB() >= count)
            return false;
        BranchGeometry a = network.branch(link.branchA());
        BranchGeometry b = network.branch(link.branchB());
        return a.heading().isPerpendicularTo(b.heading()) && a.contains(link.xA()) && b.contains(link.xB());
    }

    private static int[][] nodesPerBranch(int branchCount, int[] nodeBranch, int[] nodeX) {
        int[] counts = new int[branchCount];
        for (int branch : nodeBranch)
            counts[branch]++;
        int[][] perBranch = new int[branchCount][];
        for (int branch = 0; branch < branchCount; branch++)
            perBranch[branch] = new int[counts[branch]];
        int[] filled = new int[branchCount];
        for (int node = 0; node < nodeBranch.length; node++) {
            int branch = nodeBranch[node];
            perBranch[branch][filled[branch]++] = node;
        }
        // Ascending by position, so every walk over one aisle's junctions runs the way the aisle does.
        for (int[] nodes : perBranch) {
            for (int i = 1; i < nodes.length; i++) {
                int node = nodes[i];
                int j = i - 1;
                while (j >= 0 && nodeX[nodes[j]] > nodeX[node]) {
                    nodes[j + 1] = nodes[j];
                    j--;
                }
                nodes[j + 1] = node;
            }
        }
        return perBranch;
    }

    /**
     * Which branches the rails join, as one number per branch. Breadth-first over the branch list in link order, so
     * the ids themselves are reproducible; only their equality is ever read.
     */
    private static int[] components(int branchCount, List<BranchLink> links) {
        int[] component = new int[branchCount];
        Arrays.fill(component, NONE);
        int next = 0;
        for (int start = 0; start < branchCount; start++) {
            if (component[start] != NONE)
                continue;
            int id = next++;
            component[start] = id;
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                int branch = queue.poll();
                for (BranchLink link : links) {
                    int other = link.other(branch);
                    if (other < 0 || other >= branchCount || component[other] != NONE)
                        continue;
                    component[other] = id;
                    queue.add(other);
                }
            }
        }
        return component;
    }

    private static void requirePenalty(double turnPenaltyBlocks) {
        if (!Double.isFinite(turnPenaltyBlocks) || turnPenaltyBlocks < 0.0)
            throw new IllegalArgumentException(
                    "turnPenaltyBlocks must be finite and not negative: " + turnPenaltyBlocks);
    }
}
