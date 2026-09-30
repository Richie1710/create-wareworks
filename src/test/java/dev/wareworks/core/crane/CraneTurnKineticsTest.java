package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.CraneKinematics;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TravelTimeModel;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * A quarter turn runs off the same shaft as the drive, and costs time accordingly (M21, ADR-033).
 * <p>
 * The whole kinetic claim of the turn is one line — {@code turnPerTick = vx / turnPenaltyBlocks} — and this pins the
 * consequences a player can feel: the turn gets slower exactly as the crane gets slower, it stops when the crane stops,
 * it needs no speed factor of its own, and doubling the RPM halves it. Nothing here is a new mechanism: the turn spends
 * the same per-tick budget of {@code vx} blocks the travel does, which is what lets one formula price a whole route.
 */
class CraneTurnKineticsTest {
    /** The shipped defaults: travel 1/384 blocks per tick per RPM, capped at 1 block per tick. */
    private static final CraneKinematics.Params PARAMS =
            new CraneKinematics.Params(1.0 / 384.0, 1.0 / 512.0, 1.0 / 192.0, 1.0);
    /** The default {@code crane.turnPenaltyBlocks}: a quarter turn costs as much as one block of travel. */
    private static final double PENALTY = TravelTimeModel.DEFAULT_TURN_PENALTY_BLOCKS;
    private static final int FAST_RPM = 128;
    private static final int SLOW_RPM = 32;

    /** A quarter turn at the default penalty takes three ticks at 128 RPM and twelve at 32 RPM. */
    @Test
    void aTurnTakesTheTicksTheTravelSpeedSaysItDoes() {
        assertEquals(3, turnTicks(FAST_RPM), "128 RPM: vx = 1/3, so one block of turn is three ticks");
        assertEquals(12, turnTicks(SLOW_RPM), "32 RPM: vx = 1/12, so the same turn is twelve ticks");
    }

    /** Four times the rotation, a quarter of the turn: the turn scales with RPM exactly as the drive does. */
    @Test
    void aFasterShaftTurnsTheMachineProportionallyFaster() {
        assertEquals(4 * turnTicks(FAST_RPM), turnTicks(SLOW_RPM), "a quarter of the RPM is four times the turn");
        assertEquals(CraneKinematics.speeds(PARAMS, FAST_RPM).vx() / PENALTY,
                CraneMotion.turnSpeed(CraneKinematics.speeds(PARAMS, FAST_RPM), PENALTY), 1.0E-12,
                "the turn speed is the travel speed divided by the penalty, and nothing else");
        assertEquals(4.0, CraneMotion.turnSpeed(CraneKinematics.speeds(PARAMS, FAST_RPM), PENALTY)
                / CraneMotion.turnSpeed(CraneKinematics.speeds(PARAMS, SLOW_RPM), PENALTY), 1.0E-9,
                "and so it scales with the shaft");
    }

    /**
     * No rotation, no turn — and no new pause reason for it either. A crane that cannot drive cannot turn, which is
     * why {@code crane.turnPenaltyBlocks} needed no entry in {@code CraneKinematics.Params#zeroFactorNames}.
     */
    @Test
    void aStoppedShaftTurnsNothing() {
        CraneSpeeds stopped = CraneKinematics.speeds(PARAMS, 0);
        assertTrue(stopped.isStopped(), "no rotation is no speed at all");
        assertEquals(0.0, CraneMotion.turnSpeed(stopped, PENALTY), "so the machine does not turn either");
        CranePose pose = CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.NORTH);
        assertEquals(pose, step(pose, PENALTY, stopped), "and a stopped crane stands exactly where it stood");
    }

    /** A penalty of 0 is the documented instant turn: one tick, whatever the shaft does, as long as it turns at all. */
    @Test
    void afreeTurnIsOneTickAtEveryShaftSpeed() {
        assertEquals(1, turnTicks(FAST_RPM, 0.0));
        assertEquals(1, turnTicks(SLOW_RPM, 0.0));
        assertEquals(CraneMotion.INSTANT_TURN, CraneMotion.turnSpeed(CraneKinematics.speeds(PARAMS, SLOW_RPM), 0.0));
    }

    /** Ticks a standing machine needs for one quarter turn at {@code rpm} and the default penalty. */
    private static int turnTicks(int rpm) {
        return turnTicks(rpm, PENALTY);
    }

    /**
     * Ticks a machine standing on one block needs to swing from its aisle's heading to the next one's: a route of two
     * empty legs meeting on that block, so nothing but the turn can cost anything.
     */
    private static int turnTicks(int rpm, double penalty) {
        CraneSpeeds speeds = CraneKinematics.speeds(PARAMS, rpm);
        CraneRoute route = CraneRoute.of(new CraneRoute.Leg(0, 2.0, 2.0, Heading.EAST),
                new CraneRoute.Leg(1, 0.0, 0.0, Heading.SOUTH));
        CranePose pose = CranePose.at(0, 2.0, 0.0, Side.LEFT, Heading.EAST);
        CranePose target = CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.SOUTH);
        for (int ticks = 1; ticks <= 1000; ticks++) {
            pose = CraneMotion.step(pose, target, speeds, route, penalty);
            if (CraneMotion.isAt(pose, target))
                return ticks;
        }
        throw new AssertionError("the machine never finished its turn at " + rpm + " RPM");
    }

    private static CranePose step(CranePose pose, double penalty, CraneSpeeds speeds) {
        CraneRoute route = CraneRoute.straight(pose.branch(), pose.x(), pose.x(), Heading.NORTH);
        return CraneMotion.step(pose, pose.withYaw(CranePose.yawOf(Heading.EAST)), speeds, route, penalty);
    }

    /** Keeps the imports honest: the pose types this test builds are the ones the crane really uses. */
    @Test
    void aPoseOnAFurtherAisleIsAnOrdinaryPose() {
        CranePose pose = CranePose.at(1, 2.0, 0.0, Side.RIGHT, Heading.SOUTH);
        assertEquals(1, pose.branch());
        assertEquals(Optional.of(Heading.SOUTH), pose.heading());
        assertTrue(pose.branch() <= RackPosition.MAX_BRANCH);
    }
}
