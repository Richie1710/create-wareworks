package dev.wareworks.core.crane;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.job.TravelTimeModel;
import dev.wareworks.core.warehouse.RouteTable;

/**
 * How long the job a crane carries out right now still takes, if nothing holds it up (issue #14, ADR-075 §8.3): the
 * number behind "about 14 s left" on the dock's goggles and beside each crane on the controller's.
 * <p>
 * The same {@link TravelTimeModel} the planner ranks its trips with, summed over what is left of the trip from the
 * phase the machine is in: the travel to the source, the stop there, the travel to the target and the stop there, each
 * only as far as it still lies ahead. It is an <b>estimate</b> and says so where it is shown:
 * <ul>
 * <li>a stop is counted with a full stroke and a whole transfer, so a stop at a station, whose arm only goes half a
 * block, and a transfer already under way make it slightly too long, never too short;</li>
 * <li>it never includes a traffic wait — nobody can know how long another crane will hold the rails — which is why the
 * goggles say "not counting the wait" while the crane is held.</li>
 * </ul>
 * Computed on demand from state both sides already have — the phase, the pose, the job's two rack positions, the
 * speeds and the route table — so the client works it out itself and nothing about it is ever synced.
 */
public final class RemainingTrip {
    /** Game ticks per second, for the seconds a player reads. */
    public static final int TICKS_PER_SECOND = 20;

    private RemainingTrip() {
    }

    /**
     * Ticks left of the job between {@code source} and {@code target}, for a crane in {@code phase} standing at
     * {@code pose}; empty in a phase that is no part of a trip (idle, done, rerouting, holding items it cannot deliver)
     * and when a leg cannot be driven at all (no rotation, no route).
     *
     * @param transferTicks how long one pick or drop takes ({@code crane.transferTicks})
     * @param travel        what driving between two rack positions costs, as the planner prices it
     */
    public static OptionalLong ticks(CranePhase phase, CranePose pose, RackPosition source, RackPosition target,
            CraneSpeeds speeds, int transferTicks, PlannerInput.TravelCost travel) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(speeds, "speeds");
        Objects.requireNonNull(travel, "travel");
        int transfer = Math.max(0, transferTicks);
        double arm = Math.clamp(pose.arm(), 0.0, TravelTimeModel.FULL_EXTENSION);
        long total = switch (phase) {
            case TRAVEL_TO_SOURCE -> sum(travel(travel, pose, source), stop(speeds, transfer),
                    between(travel, source, target), stop(speeds, transfer));
            case EXTEND_SOURCE -> sum(armTicks(speeds, TravelTimeModel.FULL_EXTENSION - arm), transfer,
                    armTicks(speeds, TravelTimeModel.FULL_EXTENSION), between(travel, source, target),
                    stop(speeds, transfer));
            case PICK -> sum(transfer, armTicks(speeds, arm), between(travel, source, target), stop(speeds, transfer));
            case RETRACT_SOURCE -> sum(armTicks(speeds, arm), between(travel, source, target), stop(speeds, transfer));
            case TRAVEL_TO_TARGET -> sum(travel(travel, pose, target), stop(speeds, transfer));
            case EXTEND_TARGET -> sum(armTicks(speeds, TravelTimeModel.FULL_EXTENSION - arm), transfer,
                    armTicks(speeds, TravelTimeModel.FULL_EXTENSION));
            case DROP -> sum(transfer, armTicks(speeds, arm));
            case RETRACT_TARGET -> armTicks(speeds, arm);
            case IDLE, COMPLETE, REROUTE, HOLDING, WAITING_FOR_TARGET -> TravelTimeModel.UNAVAILABLE;
        };
        return TravelTimeModel.isAvailable(total) ? OptionalLong.of(total) : OptionalLong.empty();
    }

    /** {@code ticks} as whole seconds, rounded up, so a trip that is nearly done reads 1 s rather than 0 s. */
    public static long seconds(long ticks) {
        if (ticks <= 0L)
            return 0L;
        return (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    /**
     * What driving costs on {@code routes} at {@code speeds}: along one aisle the straight formula, round a corner the
     * route's blocks plus {@code turnPenaltyBlocks} per quarter turn — the price the planner uses.
     */
    public static PlannerInput.TravelCost over(RouteTable routes, CraneSpeeds speeds, double turnPenaltyBlocks) {
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(speeds, "speeds");
        double penalty = Math.max(0.0, turnPenaltyBlocks);
        return (fromBranch, fromX, fromY, toBranch, toX, toY) -> {
            if (fromBranch == toBranch)
                return TravelTimeModel.travelTicks(speeds, fromX, fromY, toX, toY);
            OptionalDouble blocks = routes.routeBlocks(fromBranch, fromX, toBranch, toX, penalty);
            if (blocks.isEmpty())
                return TravelTimeModel.UNAVAILABLE;
            return TravelTimeModel.travelAlongTicks(speeds, blocks.getAsDouble(), fromY, toY);
        };
    }

    private static long travel(PlannerInput.TravelCost travel, CranePose from, RackPosition to) {
        return travel.travelTicks(from.branch(), from.x(), from.y(), to.branch(), to.x(), to.y());
    }

    private static long between(PlannerInput.TravelCost travel, RackPosition from, RackPosition to) {
        return travel.travelTicks(from.branch(), from.x(), from.y(), to.branch(), to.x(), to.y());
    }

    private static long stop(CraneSpeeds speeds, int transferTicks) {
        return TravelTimeModel.stopTicks(speeds, transferTicks);
    }

    /** Ticks to move the arm over {@code stroke}: none for no stroke at all. */
    private static long armTicks(CraneSpeeds speeds, double stroke) {
        if (!(stroke > TravelTimeModel.SNAP_EPSILON))
            return 0L;
        return TravelTimeModel.armTicks(speeds, Math.min(stroke, TravelTimeModel.FULL_EXTENSION));
    }

    private static long sum(long... parts) {
        long total = 0L;
        for (long part : parts)
            total = TravelTimeModel.add(total, part);
        return total;
    }
}
