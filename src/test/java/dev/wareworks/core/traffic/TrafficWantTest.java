package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;

/** Which stop a crane asks the traffic ledger for in each phase (ADR-075 §4.2). */
class TrafficWantTest {
    private static final CranePose HERE = CranePose.at(0, 3, 1, Side.LEFT, Heading.EAST);
    private static final CranePose SOURCE = CranePose.at(1, 2, 0, Side.RIGHT, Heading.SOUTH);
    private static final Optional<RackPosition> TARGET = Optional.of(RackPosition.of(0, 7, 2, Side.LEFT));

    private static Optional<TrafficWant> job(int branch, int x) {
        return Optional.of(new TrafficWant(TrafficWant.Kind.JOB, branch, x));
    }

    @Test
    void travellingToAStopWantsThatStop() {
        assertEquals(job(1, 2), TrafficWant.of(CranePhase.TRAVEL_TO_SOURCE, HERE, SOURCE, TARGET));
        assertEquals(job(1, 2), TrafficWant.of(CranePhase.EXTEND_SOURCE, HERE, SOURCE, TARGET));
        assertEquals(job(1, 2), TrafficWant.of(CranePhase.TRAVEL_TO_TARGET, HERE, SOURCE, TARGET));
    }

    @Test
    void thePickPostsTheTargetAhead() {
        assertEquals(job(0, 7), TrafficWant.of(CranePhase.PICK, HERE, HERE, TARGET),
                "so the grant is ready when the arm is in");
        assertEquals(job(0, 7), TrafficWant.of(CranePhase.RETRACT_SOURCE, HERE, HERE, TARGET));
        assertEquals(Optional.empty(), TrafficWant.of(CranePhase.PICK, HERE, HERE, Optional.empty()));
    }

    @Test
    void anIdleCraneWantsItsRestingTargetAsARestWant() {
        assertEquals(Optional.of(new TrafficWant(TrafficWant.Kind.REST, 1, 2)),
                TrafficWant.of(CranePhase.IDLE, HERE, SOURCE, Optional.empty()));
        assertEquals(Optional.empty(), TrafficWant.of(CranePhase.IDLE, HERE, HERE.withArm(0), Optional.empty()),
                "resting where it stands wants nothing");
    }

    @Test
    void aStopWhereTheCraneStandsWantsNothing() {
        for (CranePhase phase : CranePhase.values()) {
            if (phase != CranePhase.PICK && phase != CranePhase.RETRACT_SOURCE)
                assertEquals(Optional.empty(), TrafficWant.of(phase, HERE, HERE, Optional.empty()), phase.name());
        }
        assertEquals(job(0, 7), TrafficWant.of(CranePhase.HOLDING, HERE, CranePose.at(0, 7, 0, Side.LEFT,
                Heading.EAST), Optional.empty()), "a reroute target elsewhere is a job want");
        assertThrows(IllegalArgumentException.class, () -> new TrafficWant(TrafficWant.Kind.JOB, 0, -1));
    }
}
