package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.TransportJob;

class CraneActivityTest {
    private static final CranePose HERE = CranePose.at(3.0, 1.0, Side.LEFT);
    private static final CraneTickMotion STILL = CraneTickMotion.NONE;
    private static final CraneTickMotion DRIVING = CraneTickMotion.of(HERE, HERE.withXY(3.5, 1.0));
    private static final CraneTickMotion LIFTING = CraneTickMotion.of(HERE, HERE.withXY(3.0, 1.5));
    private static final CraneTickMotion SWINGING = CraneTickMotion.of(HERE, HERE.withYaw(0.5));
    private static final CraneTickMotion SWINGING_AND_DRIVING = CraneTickMotion.of(HERE,
            HERE.withXY(3.5, 1.0).withYaw(0.5));
    private static final CraneTickMotion EXTENDING = CraneTickMotion.of(HERE, HERE.withArm(CranePose.EXTENDED));

    /** The phases in which the machine stands at a source or target. */
    private static final EnumSet<CranePhase> STOPS = EnumSet.of(CranePhase.EXTEND_SOURCE, CranePhase.PICK,
            CranePhase.RETRACT_SOURCE, CranePhase.EXTEND_TARGET, CranePhase.DROP, CranePhase.RETRACT_TARGET,
            CranePhase.COMPLETE);

    @Test
    void aPausedTickIsPausedWhateverElseItWas() {
        for (CranePhase phase : CranePhase.values()) {
            assertEquals(CraneActivity.PAUSED, CraneActivity.of(true, true, phase, SWINGING_AND_DRIVING));
            assertEquals(CraneActivity.PAUSED, CraneActivity.of(true, false, phase, STILL));
        }
    }

    @Test
    void aCraneWithoutAJobIsIdleEvenWhileDrivingHome() {
        // The drive home has no job, and the goggle status line already reads "Idle" for it: counting it as travel
        // would make a warehouse that parks its crane look busier than one that does not.
        assertEquals(CraneActivity.IDLE, CraneActivity.of(false, false, CranePhase.IDLE, DRIVING));
        assertEquals(CraneActivity.IDLE, CraneActivity.of(false, false, CranePhase.IDLE, STILL));
        assertEquals(CraneActivity.IDLE, CraneActivity.of(false, false, CranePhase.IDLE, SWINGING));
    }

    @Test
    void turningBeatsTravelling() {
        // A tick that both turned and drove counts as turning, the rule the shipped sound code already applies.
        assertEquals(CraneActivity.TURNING,
                CraneActivity.of(false, true, CranePhase.TRAVEL_TO_TARGET, SWINGING_AND_DRIVING));
        assertEquals(CraneActivity.TURNING, CraneActivity.of(false, true, CranePhase.TRAVEL_TO_SOURCE, SWINGING));
    }

    @Test
    void drivingAndLiftingAreTravel() {
        assertEquals(CraneActivity.TRAVELLING, CraneActivity.of(false, true, CranePhase.TRAVEL_TO_SOURCE, DRIVING));
        assertEquals(CraneActivity.TRAVELLING, CraneActivity.of(false, true, CranePhase.TRAVEL_TO_TARGET, LIFTING));
    }

    @Test
    void aHandOverIsTravel() {
        CranePose corner = CranePose.at(0, 8.0, 1.0, Side.LEFT, Heading.EAST);
        CraneTickMotion handOver = CraneTickMotion.of(corner, corner.handedOver(1, 0.0));
        assertEquals(CraneActivity.TRAVELLING, CraneActivity.of(false, true, CranePhase.TRAVEL_TO_TARGET, handOver));
    }

    @Test
    void everyStopPhaseIsAStop() {
        for (CranePhase phase : STOPS) {
            assertEquals(CraneActivity.AT_A_STOP, CraneActivity.of(false, true, phase, STILL), phase.name());
            assertEquals(CraneActivity.AT_A_STOP, CraneActivity.of(false, true, phase, EXTENDING), phase.name());
        }
    }

    @Test
    void aTravelTickThatCoveredNoGroundIsBlocked() {
        // The stranded case: a crane standing still in TRAVEL_TO_TARGET because a rail was broken is not paused, and
        // must not read as travelling.
        assertEquals(CraneActivity.BLOCKED, CraneActivity.of(false, true, CranePhase.TRAVEL_TO_TARGET, STILL));
        assertEquals(CraneActivity.BLOCKED, CraneActivity.of(false, true, CranePhase.TRAVEL_TO_SOURCE, STILL));
        assertEquals(CraneActivity.BLOCKED, CraneActivity.of(false, true, CranePhase.TRAVEL_TO_TARGET, EXTENDING));
    }

    @Test
    void waitingHoldingAndReroutingAreBlocked() {
        for (CranePhase phase : List.of(CranePhase.WAITING_FOR_TARGET, CranePhase.HOLDING, CranePhase.REROUTE))
            assertEquals(CraneActivity.BLOCKED, CraneActivity.of(false, true, phase, STILL), phase.name());
    }

    @Test
    void everyPhaseAndEveryMotionLandsInExactlyOneBucket() {
        List<CraneTickMotion> motions = List.of(STILL, DRIVING, LIFTING, SWINGING, SWINGING_AND_DRIVING, EXTENDING);
        for (CranePhase phase : CranePhase.values()) {
            for (CraneTickMotion motion : motions) {
                for (boolean paused : new boolean[] { false, true }) {
                    for (boolean hasJob : new boolean[] { false, true }) {
                        CraneActivity activity = CraneActivity.of(paused, hasJob, phase, motion);
                        String where = phase + " " + motion + " paused=" + paused + " job=" + hasJob;
                        assertNotNull(activity, where);
                        // Classification is a single return, so "exactly one bucket" is structural. What this pins is
                        // that every combination keeps the definitions of the six buckets, read the other way round.
                        if (paused)
                            assertEquals(CraneActivity.PAUSED, activity, where);
                        else if (!hasJob)
                            assertEquals(CraneActivity.IDLE, activity, where);
                        else
                            assertTrue(activity != CraneActivity.PAUSED && activity != CraneActivity.IDLE, where);
                        switch (activity) {
                            case PAUSED -> assertTrue(paused, where);
                            case IDLE -> assertTrue(!paused && !hasJob, where);
                            case TURNING -> assertTrue(motion.yawed(), where);
                            case TRAVELLING -> assertTrue(motion.moved() && !motion.yawed(), where);
                            case AT_A_STOP -> assertTrue(!motion.moved() && STOPS.contains(phase), where);
                            case BLOCKED -> assertTrue(!motion.moved() && !STOPS.contains(phase), where);
                        }
                    }
                }
            }
        }
    }

    @Test
    void workNamesTravellingTurningAndTheStop() {
        assertTrue(CraneActivity.TRAVELLING.isWorking());
        assertTrue(CraneActivity.TURNING.isWorking());
        assertTrue(CraneActivity.AT_A_STOP.isWorking());
        assertFalse(CraneActivity.IDLE.isWorking());
        assertFalse(CraneActivity.PAUSED.isWorking());
        assertFalse(CraneActivity.BLOCKED.isWorking());
    }

    @Test
    void thereAreExactlySixBuckets() {
        assertEquals(6, CraneActivity.COUNT);
        assertEquals(CraneActivity.COUNT, CraneActivity.values().length);
    }

    @Test
    void theStateOverloadReadsThePauseFlagAndTheJob() {
        CraneState<String, RackPosition> idle = CraneState.idle(HERE);
        assertEquals(CraneActivity.IDLE, CraneActivity.of(idle, DRIVING));
        assertEquals(CraneActivity.PAUSED, CraneActivity.of(idle.withPaused(true), DRIVING));

        TransportJob<String, RackPosition> job = TransportJob.store(new UUID(0L, 1L),
                RackPosition.of(0, 0, Side.RIGHT), RackPosition.of(3, 1, Side.LEFT), "ore", 16);
        CraneState<String, RackPosition> travelling = new CraneState<>(HERE, HERE, HERE, CranePhase.TRAVEL_TO_SOURCE, 0,
                0, false, false, Optional.of(job), Optional.empty());
        assertEquals(CraneActivity.TRAVELLING, CraneActivity.of(travelling, DRIVING));
        assertEquals(CraneActivity.BLOCKED, CraneActivity.of(travelling, STILL));
        assertEquals(CraneActivity.PAUSED, CraneActivity.of(travelling.withPaused(true), STILL));
        assertEquals(CraneActivity.AT_A_STOP, CraneActivity.of(travelling.withPhase(CranePhase.PICK), STILL));
    }
}
