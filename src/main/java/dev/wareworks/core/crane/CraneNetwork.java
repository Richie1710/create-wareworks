package dev.wareworks.core.crane;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.RouteModel;
import dev.wareworks.core.warehouse.RouteTable;

/**
 * What a crane knows about the rails under it while it moves, and what a turn on them costs ({@code
 * docs/stacker-crane.md} §4, ADR-033).
 * <p>
 * The state machine asks this and nothing else about the world: which way a branch runs, and whether there is a route
 * from where the machine stands to where it has to be. Both answers are <b>derived from the live geometry</b> and
 * never saved, so a crane follows a network a player has just changed, and a crane whose rails were taken away simply
 * has no route — which the dock turns into the existing source- or target-missing ladder.
 */
public interface CraneNetwork {
    /**
     * The warehouse of exactly one straight aisle every warehouse was before M21: no branch has a known heading and no
     * route is ever needed, so a crane driving on it behaves precisely as it did then.
     */
    CraneNetwork SINGLE_BRANCH = new CraneNetwork() {
        @Override
        public Optional<Heading> headingOf(int branch) {
            return Optional.empty();
        }

        @Override
        public Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX) {
            return Optional.empty();
        }

        @Override
        public double turnPenaltyBlocks() {
            return 0.0;
        }

        @Override
        public String toString() {
            return "CraneNetwork.SINGLE_BRANCH";
        }
    };

    /** The way a branch runs, or empty when this network does not know that branch. */
    Optional<Heading> headingOf(int branch);

    /** The way from one point of the network to another, or empty when the rails do not join them. */
    Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX);

    /** Blocks of travel one quarter turn costs ({@code crane.turnPenaltyBlocks}); 0 turns in one tick. */
    double turnPenaltyBlocks();

    /** Whether a crane on {@code fromBranch} can drive to {@code toBranch} at all. */
    default boolean reachable(int fromBranch, int toBranch) {
        return fromBranch == toBranch || route(fromBranch, 0.0, toBranch, 0.0).isPresent();
    }

    /** The yaw a crane on {@code branch} faces at rest, or {@code fallback} when the branch is unknown. */
    default double restingYaw(int branch, double fallback) {
        return headingOf(branch).map(CranePose::yawOf).orElse(fallback);
    }

    /**
     * The network a discovered {@link NetworkGeometry} describes, with the configured turn penalty, costing the shape
     * on the spot. A caller that already holds the shape's {@link RouteTable} — the warehouse's own — hands that over
     * instead ({@link #of(RouteTable, double)}), so the controller and the crane read one set of derived rows.
     */
    static CraneNetwork of(NetworkGeometry geometry, double turnPenaltyBlocks) {
        return of(RouteTable.of(geometry), turnPenaltyBlocks);
    }

    /** The network a {@link RouteTable} its owner already holds describes, at {@code turnPenaltyBlocks} per turn. */
    static CraneNetwork of(RouteTable routes, double turnPenaltyBlocks) {
        Objects.requireNonNull(routes, "routes");
        routes.costs(turnPenaltyBlocks);
        return new Discovered(routes, turnPenaltyBlocks);
    }

    /**
     * A network backed by a discovered geometry. Routes are recomputed on every call, because the rails may have
     * changed under the machine since the last one; what they <b>cost</b> is derived once per shape and turn price and
     * then read ({@link RouteTable}, {@link RouteModel}), because a machine asks for its route on every tick it moves
     * and the costs cannot change without the shape changing.
     * <p>
     * The table carries <b>this</b> network's turn price, so the route the machine drives is the one the controller
     * costed when it planned the job: on rails that split, which way round is cheapest depends on what a quarter turn
     * is worth, and the two must never disagree about it.
     *
     * @param routes            the branches the dock discovered, with their links
     * @param turnPenaltyBlocks {@code crane.turnPenaltyBlocks}
     */
    record Discovered(RouteTable routes, double turnPenaltyBlocks) implements CraneNetwork {
        public Discovered {
            Objects.requireNonNull(routes, "routes");
            if (!Double.isFinite(turnPenaltyBlocks) || turnPenaltyBlocks < 0.0)
                throw new IllegalArgumentException(
                        "turnPenaltyBlocks must be finite and not negative: " + turnPenaltyBlocks);
        }

        /** The shape this network describes. */
        public NetworkGeometry geometry() {
            return routes.network();
        }

        @Override
        public Optional<Heading> headingOf(int branch) {
            NetworkGeometry geometry = geometry();
            if (branch < 0 || branch >= geometry.branchCount())
                return Optional.empty();
            return Optional.of(geometry.branch(branch).heading());
        }

        /**
         * The cheapest route at <b>this</b> network's turn price, so the route the machine drives is the one the
         * controller costed when it planned the job: where the rails split, which way round is cheapest depends on
         * what a quarter turn is worth, and the two must never disagree about it.
         */
        @Override
        public Optional<CraneRoute> route(int fromBranch, double fromX, int toBranch, double toX) {
            return routes.route(fromBranch, fromX, toBranch, toX, turnPenaltyBlocks);
        }

        @Override
        public boolean reachable(int fromBranch, int toBranch) {
            return routes.reachable(fromBranch, toBranch);
        }
    }
}
