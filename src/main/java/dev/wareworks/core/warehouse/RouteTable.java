package dev.wareworks.core.warehouse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.NetworkGeometry;

/**
 * One rail network with everything a route question needs already derived: its {@link BranchLink links} and the
 * {@link RouteCosts costs} of driving between them ({@code docs/stacker-crane.md} §4, ADR-033).
 * <p>
 * <b>Why this exists.</b> {@link NetworkGeometry#links()} walks every pair of branches and allocates a fresh list, and
 * on a network that may split there is more than one way from A to B, so the cheapest one has to be searched for
 * rather than walked ({@link RouteCosts}). A planning pass asks a route question once per candidate — twice, in fact,
 * once for whether the crane can get there and once for what it costs — so none of that may happen per question. All
 * of it depends on the <b>shape</b> alone, and a shape is an immutable value, so all of it is derived once when the
 * shape is handed over and read many times after that.
 * <p>
 * <b>Derived only when the rails change — because a table has an owner.</b> A table is not cached globally and
 * {@link #of(NetworkGeometry)} always derives a fresh one. Instead the two things that ask many route questions about
 * one shape <b>hold</b> their table for as long as they hold the shape: a {@code WarehouseLayout} keeps the table of
 * the network it maps, and the dock keeps the {@code CraneNetwork} built from that very table. The shape and the table
 * then have one lifetime, so a warehouse pays for its links and its route costs when its rails change and never once
 * per planning pass, and the controller and its crane read the same derived rows.
 * <p>
 * This replaces a fixed-size static cache of recent shapes (M22 review fix). That cache evicted by a plain round-robin
 * counter, so from the ninth live shape on a server its hit rate was <b>zero</b> and every planning pass re-derived
 * what the class comment promised was derived once — and a shape that lost its slot silently split the controller and
 * its own dock onto two tables. An owner cannot miss, has no hit rate to lose, and leaves no shared mutable state in
 * {@code core.*}.
 * <p>
 * <b>The turn price is not part of a table.</b> Whether the rails join two points does not depend on what a quarter
 * turn costs, and the two questions that do — what a route costs and which route it is — are asked by the controller
 * and the crane with the <b>same</b> value out of the same config key. A table therefore keeps one set of costs per
 * price it is asked about ({@link #costs(double)}) rather than being one, which is what lets a single table serve a
 * whole warehouse.
 * <p>
 * Pure values, no Minecraft types; a table is as immutable as the geometry it holds (apart from the derived costs it
 * caches), so it is safe to keep and to share.
 */
public final class RouteTable {
    /**
     * The turn price of the questions that do not weigh blocks against turns: <b>a free, instant turn</b>.
     * {@link #reachable} and {@link #canDrive} answer the same whatever a turn costs, and so does every route on a
     * warehouse that bends without splitting, because there is only one of them.
     */
    public static final double TURNS_FREE = 0.0;

    private final NetworkGeometry network;
    private final List<BranchLink> links;

    /** Costs with a free turn, and costs at the one price a caller asked about; both derived when first asked. */
    private volatile RouteCosts free;
    private volatile RouteCosts priced;

    private RouteTable(NetworkGeometry network, List<BranchLink> links) {
        this.network = Objects.requireNonNull(network, "network");
        this.links = List.copyOf(Objects.requireNonNull(links, "links"));
    }

    /**
     * The table of a shape: its links derived once, its route costs derived on demand and then kept. A caller that
     * will ask more than one route question about the shape <b>keeps the table</b> rather than calling this again — a
     * fresh table shares nothing with an earlier one, so asking twice derives twice (see the class comment).
     */
    public static RouteTable of(NetworkGeometry network) {
        Objects.requireNonNull(network, "network");
        return new RouteTable(network, network.links());
    }

    /**
     * The table of a shape, told up front which turn price its costs will be asked about — the one a crane drives and
     * a controller plans with ({@code crane.turnPenaltyBlocks}). The same kind of table as {@link #of(NetworkGeometry)}
     * builds; this only saves the first caller from deriving the costs itself.
     */
    public static RouteTable of(NetworkGeometry network, double turnPenaltyBlocks) {
        RouteTable table = of(network);
        table.costs(turnPenaltyBlocks);
        return table;
    }

    /** The shape this table describes. */
    public NetworkGeometry network() {
        return network;
    }

    /** The aisle blocks two perpendicular branches share, i.e. {@link NetworkGeometry#links()}. */
    public List<BranchLink> links() {
        return links;
    }

    public int branchCount() {
        return network.branchCount();
    }

    /** Junctions of this network: aisle blocks two aisles share, i.e. what {@code aisle.maxJunctions} bounds. */
    public int junctionCount() {
        return links.size();
    }

    /**
     * The costs of this shape when a quarter turn is worth {@code turnPenaltyBlocks} of travel, derived once and kept.
     * <p>
     * One set is kept for {@link #TURNS_FREE} and one for the price a caller asks about. A warehouse reads that price
     * from one config key everywhere, so in a running game the second slot is written once and read for ever; a caller
     * that changed the price mid-game would simply have the costs derived again.
     */
    public RouteCosts costs(double turnPenaltyBlocks) {
        if (turnPenaltyBlocks == TURNS_FREE) {
            RouteCosts known = free;
            if (known == null)
                free = known = RouteCosts.of(network, links, TURNS_FREE);
            return known;
        }
        RouteCosts known = priced;
        if (known == null || known.turnPenaltyBlocks() != turnPenaltyBlocks)
            priced = known = RouteCosts.of(network, links, turnPenaltyBlocks);
        return known;
    }

    /** The costs of this shape with a free turn ({@link #TURNS_FREE}). */
    public RouteCosts costs() {
        return costs(TURNS_FREE);
    }

    /**
     * Whether a crane on {@code fromBranch} can drive to {@code toBranch} at all ({@link RouteCosts#reachable}).
     * <p>
     * This is the <b>weaker</b> of the two reachability questions and is about branches only. Everything that decides
     * whether a job may be planned or kept asks {@link #canDrive} instead, from the crane's own point.
     */
    public boolean reachable(int fromBranch, int toBranch) {
        return costs().reachable(fromBranch, toBranch);
    }

    /**
     * The cheapest route between two points of this network with a <b>free turn</b>, or empty when the rails do not
     * join them. On a warehouse that does not split there is exactly one route, so the price cannot change the answer;
     * where the rails do split it can, so a caller that plans or drives a job uses
     * {@link #route(int, double, int, double, double)}.
     */
    public Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX) {
        return costs().route(fromBranch, fromX, toBranch, toX);
    }

    /** The cheapest route when a quarter turn is worth {@code turnPenaltyBlocks} ({@link RouteCosts#route}). */
    public Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX,
            double turnPenaltyBlocks) {
        return costs(turnPenaltyBlocks).route(fromBranch, fromX, toBranch, toX);
    }

    /**
     * Whether a machine standing at {@code (fromBranch, fromX)} can really drive to {@code (toBranch, toX)}
     * ({@link RouteCosts#canDrive}).
     * <p>
     * <b>This is the question {@code CraneMotion} answers</b>, and it lives here so that the controller that plans a
     * job and the crane that checks its own job can never mean two different things by "it can get there" (M21 review
     * fix). {@link #reachable} compares branch indices only and is the cheaper, weaker question: a branch that a
     * broken rail made shorter than the crane's own position still exists and is still joined to its neighbours, so it
     * answers yes while every route from that crane is empty — which left the machine standing still for ever with
     * nothing reported.
     * <p>
     * A machine already named on the branch it has to reach always can: it drives straight at the target along the one
     * line it stands on, exactly as it did before there were corners, and that is also how it comes back onto rails
     * that became shorter under it. It needs no turn price, because whether a way exists does not depend on what a
     * turn costs.
     */
    public boolean canDrive(int fromBranch, double fromX, int toBranch, double toX) {
        return costs().canDrive(fromBranch, fromX, toBranch, toX);
    }

    /**
     * What the cheapest route costs in blocks — blocks travelled plus {@code turnPenaltyBlocks} per quarter turn —
     * without building the route itself ({@link RouteCosts#costBlocks}). This is the number the planner reads, once
     * per candidate.
     */
    public OptionalDouble routeBlocks(int fromBranch, double fromX, int toBranch, double toX,
            double turnPenaltyBlocks) {
        return costs(turnPenaltyBlocks).costBlocks(fromBranch, fromX, toBranch, toX);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        return o instanceof RouteTable other && network.equals(other.network) && links.equals(other.links);
    }

    @Override
    public int hashCode() {
        return Objects.hash(network, links);
    }

    @Override
    public String toString() {
        return "RouteTable[" + network.branchCount() + " aisles, " + junctionCount() + " junctions]";
    }
}
