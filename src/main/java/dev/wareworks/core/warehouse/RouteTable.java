package dev.wareworks.core.warehouse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.NetworkGeometry;

/**
 * One rail network with its {@link BranchLink links} already derived: everything {@link RouteModel} needs to answer a
 * route question without looking at the shape again ({@code docs/stacker-crane.md} §4, ADR-033).
 * <p>
 * <b>Why this exists.</b> {@link NetworkGeometry#links()} walks every pair of branches and allocates a fresh list, and
 * a planning pass asks for a route once per candidate — twice, in fact, once to decide whether the crane can get there
 * and once for what it costs. The links depend on the <b>shape</b> alone, and a shape is immutable, so they are
 * derived once when the shape is handed over and read many times after that: the controller builds one table per
 * planning pass, and a crane keeps one for as long as its rails do not change.
 * <p>
 * Pure values, no Minecraft types; a table is as immutable as the geometry it holds, so it is safe to keep and to
 * share.
 *
 * @param network the shape
 * @param links   the aisle blocks two perpendicular branches share, i.e. {@link NetworkGeometry#links()}
 */
public record RouteTable(NetworkGeometry network, List<BranchLink> links) {
    public RouteTable {
        Objects.requireNonNull(network, "network");
        links = List.copyOf(Objects.requireNonNull(links, "links"));
    }

    /** The table of a shape: derives its links once. */
    public static RouteTable of(NetworkGeometry network) {
        Objects.requireNonNull(network, "network");
        return new RouteTable(network, network.links());
    }

    public int branchCount() {
        return network.branchCount();
    }

    /** Whether a crane on {@code fromBranch} can drive to {@code toBranch} at all ({@link RouteModel#reachable}). */
    public boolean reachable(int fromBranch, int toBranch) {
        return RouteModel.reachable(network, links, fromBranch, toBranch);
    }

    /** The route between two points of this network, or empty when the rails do not join them. */
    public Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX) {
        return RouteModel.route(network, links, fromBranch, fromX, toBranch, toX);
    }

    /**
     * Whether a machine standing at {@code (fromBranch, fromX)} can really drive to {@code (toBranch, toX)}.
     * <p>
     * <b>This is the question {@code CraneMotion} answers</b>, and it lives here so that the controller that plans a
     * job and the crane that checks its own job can never mean two different things by "it can get there" (M21 review
     * fix). {@link #reachable} compares branch indices only and is the cheaper, weaker question: a branch that a
     * broken rail made shorter than the crane's own position still exists and is still joined to its neighbours, so
     * it answers yes while every route from that crane is empty — which left the machine standing still for ever with
     * nothing reported.
     * <p>
     * A machine already named on the branch it has to reach always can: it drives straight at the target along the one
     * line it stands on, exactly as it did before there were corners, and that is also how it comes back onto rails
     * that became shorter under it.
     */
    public boolean canDrive(int fromBranch, double fromX, int toBranch, double toX) {
        if (fromBranch == toBranch)
            return fromBranch >= 0 && fromBranch < branchCount();
        return route(fromBranch, fromX, toBranch, toX).isPresent();
    }

    /**
     * What that route costs in blocks — blocks travelled plus {@code turnPenaltyBlocks} per quarter turn — without
     * building the route itself ({@link RouteModel#routeBlocks}).
     */
    public OptionalDouble routeBlocks(int fromBranch, double fromX, int toBranch, double toX,
            double turnPenaltyBlocks) {
        return RouteModel.routeBlocks(network, links, fromBranch, fromX, toBranch, toX, turnPenaltyBlocks);
    }
}
