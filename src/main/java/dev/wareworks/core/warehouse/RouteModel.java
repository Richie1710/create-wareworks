package dev.wareworks.core.warehouse;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import dev.wareworks.core.address.NetworkGeometry;

/**
 * How a crane gets from one point of a rail network to another ({@code docs/stacker-crane.md} §4, ADR-033), as
 * pure integer and double maths without a {@code Level}.
 * <p>
 * <b>A network may bend, split and close on itself</b> (M22): corners, tees, crosses and rings are ordinary shapes. So
 * between two points there is in general <b>more than one</b> way, the ways are not equally good — a trip costs
 * {@code blocks travelled + turns · crane.turnPenaltyBlocks} — and the cheapest one is searched for rather than
 * walked. The search, what it costs to ask and why its answer is always the same lives in {@link RouteCosts}; this
 * class is the one-question front door to it.
 * <p>
 * <b>A warehouse of one aisle, and one that bends without splitting, answer exactly what they always answered.</b> In a
 * chain there is only one route, so "the cheapest" is that route whatever a turn costs, and two points of one aisle
 * cost {@code |toX - fromX|} without the rails being looked at at all.
 * <p>
 * <b>Nothing here is stored.</b> A route is recomputed from the live geometry whenever it is needed, so a crane cannot
 * hold a route to rails a player has taken away: it simply has none, and the content layer turns that into the
 * existing "source or target missing" ladder.
 * <p>
 * <b>Cost.</b> Every question needs the network's links and its route costs, and deriving those walks the branch list.
 * Each method here derives a {@link RouteTable} of its own and throws it away again, so <b>this is the front door for
 * one question about a shape nobody holds</b> — a shape built on the spot in a test or a scene. A caller that asks many
 * questions about one shape holds the table instead, which is what {@code WarehouseLayout} and
 * {@link dev.wareworks.core.crane.CraneNetwork.Discovered} do for the whole life of a warehouse's rails (M22 review
 * fix: these methods used to lean on a static cache of recent shapes, which quietly stopped hitting at all once a
 * server held nine of them).
 */
public final class RouteModel {
    private RouteModel() {
    }

    /**
     * The cheapest route from {@code (fromBranch, fromX)} to {@code (toBranch, toX)} with a <b>free turn</b>
     * ({@link RouteTable#TURNS_FREE}), or empty when the network does not join the two — an unknown branch, a position
     * outside its branch, or two parts of a network that no longer touch.
     * <p>
     * On a warehouse that does not split there is exactly one route, so the turn price cannot change the answer. Where
     * the rails do split it can, so a caller that plans or drives a job uses
     * {@link #route(NetworkGeometry, int, double, int, double, double)} with the server's own
     * {@code crane.turnPenaltyBlocks}.
     */
    public static Optional<CraneRoute> route(NetworkGeometry network, int fromBranch, double fromX, int toBranch,
            double toX) {
        return route(network, fromBranch, fromX, toBranch, toX, RouteTable.TURNS_FREE);
    }

    /** The cheapest route when a quarter turn is worth {@code turnPenaltyBlocks} of travel. */
    public static Optional<CraneRoute> route(NetworkGeometry network, int fromBranch, double fromX, int toBranch,
            double toX, double turnPenaltyBlocks) {
        Objects.requireNonNull(network, "network");
        return RouteTable.of(network).route(fromBranch, fromX, toBranch, toX, turnPenaltyBlocks);
    }

    /**
     * What the cheapest route between the two points costs in blocks — blocks travelled plus
     * {@code turnPenaltyBlocks} per quarter turn — or empty when there is no route.
     * <p>
     * On two points of one aisle this is literally {@code |toX - fromX|}, which is what makes a one-branch network plan
     * exactly as it did before M21.
     */
    public static OptionalDouble routeBlocks(NetworkGeometry network, int fromBranch, double fromX, int toBranch,
            double toX, double turnPenaltyBlocks) {
        Objects.requireNonNull(network, "network");
        return RouteTable.of(network).routeBlocks(fromBranch, fromX, toBranch, toX, turnPenaltyBlocks);
    }

    /** Whether a crane standing on {@code fromBranch} can drive to {@code toBranch} at all. */
    public static boolean reachable(NetworkGeometry network, int fromBranch, int toBranch) {
        Objects.requireNonNull(network, "network");
        return RouteTable.of(network).reachable(fromBranch, toBranch);
    }
}
