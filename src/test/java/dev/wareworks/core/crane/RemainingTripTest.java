package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.PlannerInput;
import dev.wareworks.core.job.TravelTimeModel;

/**
 * {@link RemainingTrip}: what the goggles of a crane of a warehouse with several cranes say its job still takes (issue
 * #14, ADR-075 §8.3). The rule the whole file pins: <b>from the start of a trip it is exactly the planner's trip time</b>
 * ({@link TravelTimeModel#tripTicks}), and every phase after that takes away exactly what the crane has already done.
 */
class RemainingTripTest {
    /** One block, one level and a whole arm stroke in two, five and four ticks. */
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.5, 0.2, 0.25);
    private static final int TRANSFER = 3;
    private static final RackPosition SOURCE = new RackPosition(4, 1, Side.LEFT);
    private static final RackPosition TARGET = new RackPosition(10, 0, Side.RIGHT);
    private static final PlannerInput.TravelCost STRAIGHT = PlannerInput.TravelCost.straight(SPEEDS);

    private static long ticks(CranePhase phase, CranePose pose) {
        OptionalLong ticks = RemainingTrip.ticks(phase, pose, SOURCE, TARGET, SPEEDS, TRANSFER, STRAIGHT);
        assertTrue(ticks.isPresent(), phase + " is part of a trip");
        return ticks.getAsLong();
    }

    @Test
    void fromTheStartItIsThePlannersTripTime() {
        CranePose atDock = CranePose.at(0.0, 0.0, Side.LEFT);
        long planned = TravelTimeModel.tripTicks(SPEEDS, TRANSFER, 0.0, 0.0, SOURCE.x(), SOURCE.y(), TARGET.x(),
                TARGET.y());
        assertEquals(planned, ticks(CranePhase.TRAVEL_TO_SOURCE, atDock));
    }

    @Test
    void everyPhaseTakesAwayWhatIsDone() {
        long stop = TravelTimeModel.stopTicks(SPEEDS, TRANSFER);
        long arm = TravelTimeModel.armTicks(SPEEDS);
        long between = TravelTimeModel.travelTicks(SPEEDS, SOURCE.x(), SOURCE.y(), TARGET.x(), TARGET.y());
        CranePose atSource = CranePose.at(SOURCE.x(), SOURCE.y(), Side.LEFT);
        CranePose atTarget = CranePose.at(TARGET.x(), TARGET.y(), Side.RIGHT);

        assertEquals(stop + between + stop, ticks(CranePhase.TRAVEL_TO_SOURCE, atSource),
                "standing at the source the travel there is done");
        assertEquals(arm + TRANSFER + arm + between + stop, ticks(CranePhase.EXTEND_SOURCE, atSource),
                "a retracted arm still has the whole stroke out and back");
        assertEquals(TravelTimeModel.armTicks(SPEEDS, 0.5) + TRANSFER + arm + between + stop,
                ticks(CranePhase.EXTEND_SOURCE, atSource.withArm(0.5)), "half out, half of the stroke is left");
        assertEquals(TRANSFER + arm + between + stop, ticks(CranePhase.PICK, atSource.withArm(1.0)),
                "a pick counts its whole transfer, never less");
        assertEquals(TravelTimeModel.armTicks(SPEEDS, 0.25) + between + stop,
                ticks(CranePhase.RETRACT_SOURCE, atSource.withArm(0.25)), "a quarter of the stroke left to retract");
        assertEquals(stop, ticks(CranePhase.TRAVEL_TO_TARGET, atTarget), "at the target only the stop is left");
        assertEquals(TRANSFER + arm, ticks(CranePhase.DROP, atTarget.withArm(1.0)));
        assertEquals(0L, ticks(CranePhase.RETRACT_TARGET, atTarget), "a retracted arm has nothing left to do");
    }

    @Test
    void aPhaseOutsideATripHasNoTime() {
        CranePose pose = CranePose.at(2.0, 0.0, Side.LEFT);
        for (CranePhase phase : new CranePhase[] {CranePhase.IDLE, CranePhase.COMPLETE, CranePhase.REROUTE,
                CranePhase.HOLDING, CranePhase.WAITING_FOR_TARGET})
            assertEquals(OptionalLong.empty(),
                    RemainingTrip.ticks(phase, pose, SOURCE, TARGET, SPEEDS, TRANSFER, STRAIGHT), phase.name());
    }

    @Test
    void aStoppedCraneOrAMissingRouteHasNoTime() {
        CranePose pose = CranePose.at(0.0, 0.0, Side.LEFT);
        assertEquals(OptionalLong.empty(), RemainingTrip.ticks(CranePhase.TRAVEL_TO_SOURCE, pose, SOURCE, TARGET,
                CraneSpeeds.STOPPED, TRANSFER, PlannerInput.TravelCost.straight(CraneSpeeds.STOPPED)),
                "a crane without rotation never arrives");
        PlannerInput.TravelCost nowhere = (fromBranch, fromX, fromY, toBranch, toX, toY) -> TravelTimeModel.UNAVAILABLE;
        assertEquals(OptionalLong.empty(), RemainingTrip.ticks(CranePhase.TRAVEL_TO_TARGET, pose, SOURCE, TARGET,
                SPEEDS, TRANSFER, nowhere), "no route, no time");
    }

    @Test
    void secondsAreRoundedUp() {
        assertEquals(0L, RemainingTrip.seconds(0L));
        assertEquals(1L, RemainingTrip.seconds(1L), "a trip that is nearly done still reads one second");
        assertEquals(1L, RemainingTrip.seconds(20L));
        assertEquals(2L, RemainingTrip.seconds(21L));
    }
}
