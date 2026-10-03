package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneSoundCues.Cue;
import dev.wareworks.core.crane.CraneSoundCues.Sound;
import dev.wareworks.core.job.CraneSpeeds;

class CraneTickMotionTest {
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.5, 0.5, 0.5);
    private static final long TICK = 1000;
    /** Far from every pose in the table, so no pair ever counts as having arrived. */
    private static final CranePose FAR_TARGET = CranePose.at(2, 40.0, 7.0, Side.RIGHT, Heading.SOUTH);
    private static final CranePose ORIGIN = CranePose.at(0.0, 0.0, Side.LEFT);

    @Test
    void aStillTickIsNoMotion() {
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN, ORIGIN);
        assertFalse(motion.handedOver());
        assertFalse(motion.movedX());
        assertFalse(motion.movedY());
        assertFalse(motion.yawed());
        assertFalse(motion.moved());
        assertEquals(0.0, motion.quarterTurns());
        assertEquals(CraneTickMotion.NONE, motion);
    }

    @Test
    void drivingAlongTheRailsIsTravel() {
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN, ORIGIN.withXY(0.6, 0.0));
        assertTrue(motion.movedX());
        assertFalse(motion.movedY());
        assertFalse(motion.yawed());
        assertTrue(motion.moved());
    }

    @Test
    void liftingIsTravel() {
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN, ORIGIN.withXY(0.0, 0.6));
        assertFalse(motion.movedX());
        assertTrue(motion.movedY());
        assertTrue(motion.moved());
    }

    @Test
    void theArmAloneIsNotTravel() {
        // The reason the extend and retract phases land in CraneActivity.AT_A_STOP and not in TRAVELLING.
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN, ORIGIN.withArm(CranePose.EXTENDED));
        assertFalse(motion.moved());
    }

    @Test
    void theSideAloneIsNotTravel() {
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN, ORIGIN.withSide(Side.RIGHT));
        assertFalse(motion.moved());
    }

    @Test
    void aHandOverIsTravelAndNeverComparesTheTwoPositions() {
        // At a corner the machine is renamed onto the next branch, where x counts from that branch's own end: the
        // number jumps without the machine moving, and the tick is travel because it drove up to the corner block.
        CranePose before = CranePose.at(0, 8.0, 1.0, Side.LEFT, Heading.EAST);
        CraneTickMotion motion = CraneTickMotion.of(before, before.handedOver(1, 0.0));
        assertTrue(motion.handedOver());
        assertTrue(motion.movedX());
        assertFalse(motion.movedY());
        assertFalse(motion.yawed());
        assertTrue(motion.moved());
    }

    @Test
    void aHandOverThatDoesNotChangeTheNumberIsStillTravel() {
        CranePose before = CranePose.at(0, 4.0, 0.0, Side.LEFT, Heading.EAST);
        CraneTickMotion motion = CraneTickMotion.of(before, before.handedOver(1, 4.0));
        assertTrue(motion.handedOver());
        assertTrue(motion.movedX());
    }

    @Test
    void aSwingIsQuarterTurnsAsAMagnitude() {
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(0.5));
        assertEquals(0.5, motion.quarterTurns());
        assertTrue(motion.yawed());
        assertTrue(motion.moved());
        assertFalse(motion.movedX());
    }

    @Test
    void aSwingAnticlockwiseCountsByItsMagnitude() {
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN.withYaw(0.5), ORIGIN);
        assertEquals(0.5, motion.quarterTurns());
        assertTrue(motion.yawed());
    }

    @Test
    void aSwingPastNorthTakesTheShortArc() {
        CraneTickMotion motion = CraneTickMotion.of(ORIGIN.withYaw(3.5), ORIGIN.withYaw(0.25));
        assertEquals(0.75, motion.quarterTurns());
    }

    /**
     * What two poses alone cannot say, and why the server does not ask them (M25 review fix): the shortest arc between
     * them is the <b>net</b> rotation, so a tick that crossed more than one corner — reachable with
     * {@code crane.turnPenaltyBlocks} at or near 0, a documented instant turn — reads short, or as no turn at all.
     * <p>
     * These are the readings the two-pose form really makes, pinned so the limit is visible instead of silent. The
     * three-argument form is handed the swing {@link CraneMotion#quarterTurnsSwung} measured leg by leg along the
     * route, and is never less than the arc — which is what makes a tick whose corners cancelled out a turning tick
     * all the same.
     */
    @Test
    void theNetArcUnderstatesATickThatCrossedSeveralCorners() {
        // Two corners the same way: a half turn, which the arc still reads in full.
        assertEquals(2.0, CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(2.0)).quarterTurns());
        // Three the same way: the arc takes the other way round and reads one.
        assertEquals(1.0, CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(3.0)).quarterTurns());
        // Four, or two in opposite directions: back where it started, so the arc reads nothing at all — and so does
        // the question "did it turn?", which is how such a tick came to be booked as plain travel.
        CraneTickMotion fourCorners = CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(4.0));
        assertEquals(0.0, fourCorners.quarterTurns());
        assertFalse(fourCorners.yawed());

        // Measured along the route instead, each is the swing that was really spent.
        assertEquals(3.0, CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(3.0), 3.0).quarterTurns());
        CraneTickMotion cancelled = CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(4.0), 2.0);
        assertEquals(2.0, cancelled.quarterTurns());
        assertTrue(cancelled.yawed());
        // And never less than the arc, whatever it is handed.
        assertEquals(1.0, CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(1.0), 0.0).quarterTurns());
        assertThrows(IllegalArgumentException.class, () -> CraneTickMotion.of(ORIGIN, ORIGIN, Double.NaN));
    }

    @Test
    void aFinishedCornerSumsToOneQuarterTurn() {
        double total = 0.0;
        CranePose pose = ORIGIN;
        for (int step = 0; step < 3; step++) {
            CranePose next = pose.withYaw(pose.yaw() + 1.0 / 3.0);
            total += CraneTickMotion.of(pose, next).quarterTurns();
            pose = next;
        }
        assertEquals(1.0, total, 1e-9);
    }

    @Test
    void roundingBelowTheEpsilonIsNoMotion() {
        assertFalse(CraneTickMotion.of(ORIGIN, ORIGIN.withXY(CraneTickMotion.EPSILON, 0.0)).moved());
        assertFalse(CraneTickMotion.of(ORIGIN, ORIGIN.withXY(0.0, CraneTickMotion.EPSILON)).moved());
        assertFalse(CraneTickMotion.of(ORIGIN, ORIGIN.withYaw(CraneTickMotion.EPSILON)).yawed());
        assertTrue(CraneTickMotion.of(ORIGIN, ORIGIN.withXY(CraneTickMotion.EPSILON * 10.0, 0.0)).moved());
    }

    @Test
    void aNonFiniteSwingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CraneTickMotion(false, false, false, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new CraneTickMotion(false, false, false, Double.POSITIVE_INFINITY));
    }

    @Test
    void noneIsThePoseThatDidNothing() {
        assertFalse(CraneTickMotion.NONE.handedOver());
        assertFalse(CraneTickMotion.NONE.moved());
        assertFalse(CraneTickMotion.NONE.yawed());
        assertEquals(0.0, CraneTickMotion.NONE.quarterTurns());
    }

    /**
     * The table that lets the sound cues keep their own four local booleans (D8): for every pair, what
     * {@link CraneTickMotion} reads must be what {@link CraneSoundCues#motion} read, which is observable through the
     * cues a fresh instance plays — {@link Cue#TURN} exactly for a tick that yawed, {@link Cue#TRAVEL_START} exactly
     * for a tick that moved, a {@link Cue#RAIL_CLACK} only for plain travel along one branch, and a
     * {@link Cue#LIFT_CHAIN} only for a tick that lifted.
     */
    @Test
    void agreesWithTheSoundCues() {
        for (CranePose[] pair : table()) {
            CranePose before = pair[0];
            CranePose after = pair[1];
            CraneTickMotion motion = CraneTickMotion.of(before, after);
            List<Cue> cues = cuesOf(new CraneSoundCues().motion(TICK, before, after, FAR_TARGET, SPEEDS, false));
            String pose = before + " → " + after;
            assertEquals(motion.yawed(), cues.contains(Cue.TURN), "turn cue disagrees for " + pose);
            assertEquals(motion.moved(), cues.contains(Cue.TRAVEL_START), "travel cue disagrees for " + pose);
            if (cues.contains(Cue.RAIL_CLACK))
                assertTrue(motion.movedX() && !motion.yawed() && !motion.handedOver(),
                        "rail clack without plain travel along one branch for " + pose);
            if (cues.contains(Cue.LIFT_CHAIN))
                assertTrue(motion.movedY(), "lift cue without a lift for " + pose);
        }
    }

    @Test
    void plainTravelThatCrossesAJointStillClacks() {
        // Guards the assertion above against passing because no pair in the table ever produced a clack.
        CranePose before = CranePose.at(0.0, 0.0, Side.LEFT);
        List<Cue> cues = cuesOf(
                new CraneSoundCues().motion(TICK, before, before.withXY(0.6, 0.0), FAR_TARGET, SPEEDS, false));
        assertTrue(cues.contains(Cue.RAIL_CLACK), cues.toString());
    }

    /** Pose pairs covering every combination the two readings could disagree on. */
    private static List<CranePose[]> table() {
        CranePose start = CranePose.at(0, 2.0, 1.0, Side.LEFT, Heading.NORTH);
        CranePose turned = start.withYaw(0.5);
        CranePose corner = CranePose.at(0, 8.0, 1.0, Side.LEFT, Heading.EAST);
        return List.of(new CranePose[] { start, start },
                new CranePose[] { start, start.withXY(2.6, 1.0) },
                new CranePose[] { start, start.withXY(2.4, 1.0) },
                new CranePose[] { start, start.withXY(1.4, 1.0) },
                new CranePose[] { start, start.withXY(2.0, 1.6) },
                new CranePose[] { start, start.withXY(2.0, 1.4) },
                new CranePose[] { start, start.withXY(2.6, 1.6) },
                new CranePose[] { start, start.withArm(CranePose.EXTENDED) },
                new CranePose[] { start, start.withSide(Side.RIGHT) },
                new CranePose[] { start, start.withYaw(1.0 / 3.0) },
                new CranePose[] { turned, start },
                new CranePose[] { start.withYaw(3.5), start.withYaw(0.25) },
                new CranePose[] { start, start.withXY(2.6, 1.0).withYaw(0.5) },
                new CranePose[] { corner, corner.handedOver(1, 0.0) },
                new CranePose[] { corner, corner.handedOver(1, 0.0).withYaw(0.5) },
                new CranePose[] { start, start.withXY(2.0 + CraneTickMotion.EPSILON, 1.0) });
    }

    private static List<Cue> cuesOf(List<Sound> sounds) {
        List<Cue> cues = new ArrayList<>(sounds.size());
        for (Sound sound : sounds)
            cues.add(sound.cue());
        return cues;
    }
}
