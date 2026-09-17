package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.CraneSpeeds;

/**
 * {@link CraneResync}: when a client snaps to a synced crane pose ({@code docs/stacker-crane.md} §5.2).
 * <p>
 * The speed term is the M3 review fix that keeps fast cranes smooth: with a flat 0.5 block threshold, a packet that
 * arrives one tick early or late snapped every crane above about 192 RPM. Only a player watching the game would notice a
 * regression, so the rule is pinned here.
 */
class CraneResyncTest {
    /** A fast crane: one tick of travel is 0.4 blocks, close to the flat snap distance. */
    private static final CraneSpeeds FAST = new CraneSpeeds(0.4, 0.3, 0.35);
    private static final CraneSpeeds SLOW = new CraneSpeeds(0.05, 0.05, 0.05);

    @Test
    void theSnapDistanceNeverFallsBelowTheFlatMinimum() {
        assertEquals(CraneResync.SNAP_DISTANCE, CraneResync.snapDistance(0.0), "a standing axis");
        assertEquals(CraneResync.SNAP_DISTANCE, CraneResync.snapDistance(0.1), "slow: the flat distance dominates");
        assertEquals(CraneResync.SNAP_DISTANCE, CraneResync.snapDistance(CraneResync.SNAP_DISTANCE
                / CraneResync.SNAP_SPEED_TICKS), "exactly at the crossover");
    }

    @Test
    void theSnapDistanceFollowsFastAxes() {
        assertEquals(0.8, CraneResync.snapDistance(0.4), 1e-9, "two ticks of motion at 0.4 blocks per tick");
        assertEquals(2.0, CraneResync.snapDistance(1.0), 1e-9, "the axis speed cap");
    }

    @Test
    void oneTickOfMotionNeverSnapsAFastCrane() {
        CranePose own = CranePose.at(10.0, 2.0, Side.LEFT);
        CranePose oneTickLate = CranePose.at(10.0 - FAST.vx(), 2.0 - FAST.vy(), Side.LEFT);
        CranePose oneTickEarly = CranePose.at(10.0 + FAST.vx(), 2.0 + FAST.vy(), Side.LEFT);
        assertFalse(CraneResync.diverges(own, oneTickLate, FAST), "a packet a tick late must not snap");
        assertFalse(CraneResync.diverges(own, oneTickEarly, FAST), "a packet a tick early must not snap");
    }

    @Test
    void realDriftSnaps() {
        CranePose own = CranePose.at(10.0, 2.0, Side.LEFT);
        assertTrue(CraneResync.diverges(own, CranePose.at(10.0 + 3 * FAST.vx(), 2.0, Side.LEFT), FAST),
                "three ticks of travel is more than the two-tick allowance");
        assertTrue(CraneResync.diverges(own, CranePose.at(10.0, 2.0 + 3 * FAST.vy(), Side.LEFT), FAST), "lift drift");
        assertTrue(CraneResync.diverges(own, CranePose.at(11.0, 2.0, Side.LEFT), SLOW),
                "a slow crane snaps beyond the flat half block");
        assertFalse(CraneResync.diverges(own, CranePose.at(10.4, 2.0, Side.LEFT), SLOW),
                "but not inside the flat half block");
    }

    @Test
    void theArmUsesItsOwnSpeed() {
        CranePose retracted = CranePose.at(1.0, 1.0, Side.LEFT);
        CranePose halfOut = new CranePose(1.0, 1.0, 0.45, Side.LEFT);
        assertFalse(CraneResync.diverges(retracted, halfOut, SLOW), "0.45 is inside the flat snap distance");
        assertTrue(CraneResync.diverges(retracted, new CranePose(1.0, 1.0, 0.6, Side.LEFT), SLOW),
                "a slow arm snaps beyond half an extension");
        assertFalse(CraneResync.diverges(retracted, new CranePose(1.0, 1.0, 0.6, Side.LEFT), FAST),
                "a fast arm covers 0.6 within two ticks (2 x 0.35), so the same packet must not snap it");
    }

    @Test
    void theRackSideOnlyMattersWhileBothArmsAreOut() {
        CranePose leftOut = new CranePose(3.0, 1.0, CranePose.EXTENDED, Side.LEFT);
        CranePose rightOut = new CranePose(3.0, 1.0, CranePose.EXTENDED, Side.RIGHT);
        assertTrue(CraneResync.diverges(leftOut, rightOut, FAST), "an extended arm on the other side must snap");

        CranePose leftRetracted = CranePose.at(3.0, 1.0, Side.LEFT);
        CranePose rightRetracted = CranePose.at(3.0, 1.0, Side.RIGHT);
        assertFalse(CraneResync.diverges(leftRetracted, rightRetracted, FAST),
                "a retracted arm has no visible side: snapping for it would be a jump with no cause");
        // One arm barely out, so the extension difference itself stays inside the snap distance and only the side
        // clause could fire. A fully extended arm against a retracted one diverges on distance alone.
        CranePose rightBarelyOut = new CranePose(3.0, 1.0, 0.2, Side.RIGHT);
        assertFalse(CraneResync.diverges(leftRetracted, rightBarelyOut, FAST), "only one arm out");
        assertFalse(CraneResync.diverges(rightBarelyOut, leftRetracted, FAST), "only one arm out, the other way round");
        assertTrue(CraneResync.diverges(leftRetracted, rightOut, FAST),
                "a fully extended arm against a retracted one snaps on the extension, whatever the side says");
    }

    @Test
    void anIdenticalPoseNeverSnaps() {
        CranePose pose = new CranePose(7.25, 3.5, 0.5, Side.RIGHT);
        assertFalse(CraneResync.diverges(pose, pose, CraneSpeeds.STOPPED));
        assertFalse(CraneResync.diverges(pose, pose, FAST));
    }
}
