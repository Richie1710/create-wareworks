package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.CraneKinematics;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TravelTimeModel;

class CraneMotionTest {
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(1.0, 0.5, 0.25);
    private static final CraneKinematics.Params DEFAULTS = new CraneKinematics.Params(1.0 / 384.0, 1.0 / 512.0,
            1.0 / 192.0, 1.0);
    private static final int MAX_STEPS = 100_000;

    private static List<CranePose> run(CranePose start, CranePose target, CraneSpeeds speeds) {
        List<CranePose> poses = new ArrayList<>();
        CranePose pose = start;
        while (!CraneMotion.isAt(pose, target)) {
            pose = CraneMotion.step(pose, target, speeds);
            poses.add(pose);
            if (poses.size() > MAX_STEPS)
                fail("target not reached: " + pose + " → " + target);
        }
        return poses;
    }

    @Test
    void armRetractsFullyBeforeAnyXYMotion() {
        List<CranePose> poses = run(new CranePose(2, 1, 1.0, Side.LEFT), CranePose.at(5, 3, Side.RIGHT), SPEEDS);
        assertEquals(List.of(new CranePose(2, 1, 0.75, Side.LEFT), new CranePose(2, 1, 0.5, Side.LEFT),
                new CranePose(2, 1, 0.25, Side.LEFT), new CranePose(2, 1, 0.0, Side.LEFT),
                new CranePose(3, 1.5, 0, Side.RIGHT), new CranePose(4, 2, 0, Side.RIGHT),
                new CranePose(5, 2.5, 0, Side.RIGHT), new CranePose(5, 3, 0, Side.RIGHT)), poses);
    }

    @Test
    void xAndYMoveSimultaneouslyUntilEachArrives() {
        List<CranePose> poses = run(CranePose.at(0, 0, Side.LEFT), CranePose.at(2, 2, Side.LEFT), SPEEDS);
        assertEquals(List.of(CranePose.at(1, 0.5, Side.LEFT), CranePose.at(2, 1, Side.LEFT),
                CranePose.at(2, 1.5, Side.LEFT), CranePose.at(2, 2, Side.LEFT)), poses);
    }

    @Test
    void armExtendsOnlyAtTheExactTargetPosition() {
        CranePose target = new CranePose(2, 0, 1.0, Side.RIGHT);
        List<CranePose> poses = run(CranePose.at(0, 0, Side.LEFT), target, new CraneSpeeds(1, 1, 0.5));
        assertEquals(List.of(CranePose.at(1, 0, Side.RIGHT), CranePose.at(2, 0, Side.RIGHT),
                new CranePose(2, 0, 0.5, Side.RIGHT), target), poses);
    }

    @Test
    void switchingSidesRetractsFirst() {
        CranePose target = new CranePose(2, 0, 1.0, Side.RIGHT);
        List<CranePose> poses = run(new CranePose(2, 0, 1.0, Side.LEFT), target, new CraneSpeeds(1, 1, 0.5));
        assertEquals(List.of(new CranePose(2, 0, 0.5, Side.LEFT), new CranePose(2, 0, 0.0, Side.LEFT),
                new CranePose(2, 0, 0.5, Side.RIGHT), target), poses);
    }

    @Test
    void aZeroSpeedAxisStaysWhereItIs() {
        CranePose target = CranePose.at(3, 2, Side.LEFT);
        CranePose pose = CranePose.at(0, 0, Side.LEFT);
        for (int i = 0; i < 20; i++)
            pose = CraneMotion.step(pose, target, new CraneSpeeds(0, 1, 1));
        assertEquals(CranePose.at(0, 2, Side.LEFT), pose);
        assertFalse(CraneMotion.isAt(pose, target));
        CranePose frozen = new CranePose(1, 1, 0.5, Side.LEFT);
        assertEquals(frozen, CraneMotion.step(frozen, CranePose.at(4, 4, Side.LEFT), CraneSpeeds.STOPPED));
    }

    @Test
    void axesSnapWithinTheEpsilon() {
        assertEquals(CranePose.at(2, 0, Side.LEFT),
                CraneMotion.step(CranePose.at(2 + 1e-7, 0, Side.LEFT), CranePose.at(2, 0, Side.LEFT),
                        CraneSpeeds.STOPPED), "a rounding error is removed even without speed");
        assertEquals(0.5000005, CraneMotion.step(CranePose.at(0, 0, Side.LEFT), CranePose.at(0.5000005, 0, Side.LEFT),
                new CraneSpeeds(0.5, 0, 0)).x());
        assertEquals(0.5, CraneMotion.step(CranePose.at(0, 0, Side.LEFT), CranePose.at(0.51, 0, Side.LEFT),
                new CraneSpeeds(0.5, 0, 0)).x());
        assertEquals(-0.5, CraneMotion.approach(0, -3, 0.5));
    }

    @Test
    void neverOvershootsAndFollowsTheAxisOrder() {
        Random random = new Random(42L);
        for (int run = 0; run < 400; run++) {
            CranePose pose = new CranePose(random.nextDouble() * 32, random.nextDouble() * 16, random.nextDouble(),
                    random.nextBoolean() ? Side.LEFT : Side.RIGHT);
            CranePose target = new CranePose(random.nextInt(33), random.nextInt(17), random.nextBoolean() ? 1.0 : 0.0,
                    random.nextBoolean() ? Side.LEFT : Side.RIGHT);
            CraneSpeeds speeds = new CraneSpeeds(0.01 + random.nextDouble() * 1.5, 0.01 + random.nextDouble() * 1.5,
                    0.01 + random.nextDouble());
            int steps = 0;
            while (!CraneMotion.isAt(pose, target)) {
                CranePose next = CraneMotion.step(pose, target, speeds);
                assertTowards(pose.x(), next.x(), target.x());
                assertTowards(pose.y(), next.y(), target.y());
                if (!next.sameXY(pose))
                    assertTrue(pose.arm() == 0 && next.arm() == 0, "X/Y moved with the arm out: " + pose + " → " + next);
                if (next.arm() > pose.arm())
                    assertTrue(pose.sameXY(target), "extended away from the target: " + pose + " → " + next);
                pose = next;
                if (++steps > MAX_STEPS)
                    fail("target not reached");
            }
        }
    }

    private static void assertTowards(double before, double after, double target) {
        assertTrue((target - after) * (target - before) >= 0, "overshoot: " + before + " → " + after + " / " + target);
        assertTrue(Math.abs(target - after) <= Math.abs(target - before), "moved away: " + before + " → " + after);
    }

    @Test
    void stepCountsMatchTheTravelTimeModel() {
        Random random = new Random(7L);
        for (int run = 0; run < 400; run++) {
            CraneSpeeds speeds = CraneKinematics.speeds(DEFAULTS, 8 + random.nextInt(249));
            double startX = random.nextBoolean() ? random.nextInt(33) : random.nextDouble() * 32;
            double startY = random.nextBoolean() ? random.nextInt(17) : random.nextDouble() * 16;
            CranePose start = CranePose.at(startX, startY, Side.LEFT);
            CranePose target = CranePose.at(random.nextInt(33), random.nextInt(17), Side.RIGHT);
            assertEquals(TravelTimeModel.travelTicks(speeds, startX, startY, target.x(), target.y()),
                    run(start, target, speeds).size(), () -> "travel " + start + " → " + target + " at " + speeds);
            CranePose extended = target.withArm(CranePose.EXTENDED);
            assertEquals(TravelTimeModel.armTicks(speeds), run(target, extended, speeds).size());
            assertEquals(TravelTimeModel.armTicks(speeds), run(extended, target, speeds).size());
        }
    }

    @Test
    void identicalInputsGiveIdenticalMotion() {
        assertEquals(randomTrajectory(99L), randomTrajectory(99L));
    }

    private static List<CranePose> randomTrajectory(long seed) {
        Random random = new Random(seed);
        List<CranePose> poses = new ArrayList<>();
        CranePose pose = CranePose.at(0, 0, Side.LEFT);
        for (int leg = 0; leg < 50; leg++) {
            CranePose target = new CranePose(random.nextInt(20), random.nextInt(8), random.nextBoolean() ? 1.0 : 0.0,
                    random.nextBoolean() ? Side.LEFT : Side.RIGHT);
            CraneSpeeds speeds = new CraneSpeeds(random.nextDouble(), random.nextDouble(), random.nextDouble());
            for (int i = 0; i < 30; i++) {
                pose = CraneMotion.step(pose, target, speeds);
                poses.add(pose);
            }
        }
        return poses;
    }

    @Test
    void stateStepKeepsThePreviousPose() {
        CranePose start = CranePose.at(1, 1, Side.LEFT);
        CraneState<String, String> state = CraneState.<String, String>idle(start).withTarget(CranePose.at(3, 1, Side.LEFT));
        CraneState<String, String> moved = CraneMotion.step(state, SPEEDS);
        assertEquals(start, moved.previousPose());
        assertEquals(CranePose.at(2, 1, Side.LEFT), moved.pose());
        assertTrue(moved.isMoving());
        assertEquals(CranePose.at(1.5, 1, Side.LEFT), moved.interpolatedPose(0.5));
    }

    /** A displayed client pose (Ponder) moves towards its own target with the shared motion; without one it stands still. */
    @Test
    void displayedStateMovesTowardsItsTarget() {
        CranePose start = new CranePose(0, 0, 1.0, Side.LEFT);
        CranePose target = new CranePose(3, 2, 1.0, Side.RIGHT);
        CraneState<String, String> state = CraneState.displayed(start, target, CranePhase.EXTEND_TARGET);
        assertEquals(start, state.pose());
        assertEquals(start, state.previousPose());
        assertEquals(target, state.target());
        assertEquals(CranePhase.EXTEND_TARGET, state.phase());
        assertTrue(state.job().isEmpty());
        assertFalse(state.paused());
        List<CranePose> expected = run(start, target, SPEEDS);
        List<CranePose> shown = new ArrayList<>();
        while (!CraneMotion.isAt(state.pose(), state.target())) {
            state = CraneMotion.step(state, SPEEDS);
            shown.add(state.pose());
            if (shown.size() > MAX_STEPS)
                fail("displayed state never reached its target: " + state.pose());
        }
        assertEquals(expected, shown, "the same poses as any crane moving to that target");
        assertEquals(CranePhase.EXTEND_TARGET, state.phase(), "motion never changes the phase");

        CraneState<String, String> fixed = CraneState.displayed(target, target, CranePhase.DROP);
        assertEquals(target, CraneMotion.step(fixed, SPEEDS).pose(), "a pose that is its own target stands still");
    }

    @Test
    void atTargetIgnoresTheSideOfARetractedArm() {
        assertTrue(CraneMotion.isAt(CranePose.at(1, 2, Side.LEFT), CranePose.at(1, 2, Side.RIGHT)));
        assertFalse(CraneMotion.isAt(new CranePose(1, 2, 1, Side.LEFT), new CranePose(1, 2, 1, Side.RIGHT)));
        assertFalse(CraneMotion.isAt(new CranePose(1, 2, 0.5, Side.LEFT), new CranePose(1, 2, 1, Side.LEFT)));
        assertFalse(CraneMotion.isAt(CranePose.at(1, 2.5, Side.LEFT), CranePose.at(1, 2, Side.LEFT)));
    }

    @Test
    void poseInterpolationAndValidation() {
        CranePose previous = new CranePose(0, 0, 0, Side.LEFT);
        CranePose current = new CranePose(1, 2, 1, Side.RIGHT);
        assertEquals(new CranePose(0.25, 0.5, 0.25, Side.RIGHT), CranePose.lerp(previous, current, 0.25));
        assertEquals(current, CranePose.lerp(previous, current, 7));
        assertEquals(previous.withSide(Side.RIGHT), CranePose.lerp(previous, current, -1));
        assertThrows(IllegalArgumentException.class, () -> new CranePose(0, 0, 1.5, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new CranePose(Double.NaN, 0, 0, Side.LEFT));
        assertEquals(new CranePose(0, 3, 1, CranePose.DEFAULT_SIDE),
                CranePose.sanitized(Double.POSITIVE_INFINITY, 3, 5, null));
        assertEquals(new CranePose(2, 0, 0, Side.RIGHT), CranePose.sanitized(2, Double.NaN, Double.NaN, Side.RIGHT));
    }
}
