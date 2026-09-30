package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;

/**
 * Where a crane with nothing to do waits (M21, issue #1, ADR-034): the whole rule, as the pure value the content layer
 * asks.
 * <p>
 * The one property this file exists for is the <b>first</b> test: a warehouse of one straight aisle never sends its
 * crane anywhere, whatever a home point or a config says, because that is what every warehouse did before this version.
 */
class HomeReturnTest {
    private static final int DELAY = 200;
    private static final RackPosition HOME = new RackPosition(1, 3, 2, Side.RIGHT);

    // --- when a crane stays where it is --------------------------------------------------------------------------

    /** A warehouse of one straight aisle: no return, no park pose, and a home point that has no effect. */
    @Test
    void oneStraightAisleNeverSendsItsCraneAnywhere() {
        HomeReturn onAisle = HomeReturn.of(DELAY, 1, Optional.of(HOME));
        assertFalse(onAisle.enabled(), "a warehouse of one aisle does not return its crane");
        assertFalse(onAisle.homePointHasEffect(), "so a home point on it has no effect");
        assertFalse(onAisle.returnsAfter(Integer.MAX_VALUE), "not after any amount of waiting");
        assertEquals(Optional.empty(), onAisle.parkPose(Heading.EAST), "and it names no pose to park in");

        HomeReturn withoutHome = HomeReturn.of(DELAY, 1, Optional.empty());
        assertFalse(withoutHome.enabled(), "and that holds without a home point too");
        assertFalse(withoutHome.returnsAfter(DELAY));
    }

    /** A delay of 0 is the server owner's off switch and reproduces the single-aisle rule everywhere. */
    @Test
    void aDelayOfZeroSwitchesReturningHomeOff() {
        HomeReturn off = HomeReturn.of(HomeReturn.OFF, 4, Optional.of(HOME));
        assertFalse(off.enabled());
        assertFalse(off.homePointHasEffect());
        assertFalse(off.returnsAfter(0), "not even at once");
        assertFalse(off.returnsAfter(Integer.MAX_VALUE));
        assertEquals(Optional.empty(), off.parkPose(Heading.EAST));
    }

    @Test
    void theDefaultRuleNeverReturns() {
        assertFalse(HomeReturn.NEVER.enabled());
        assertEquals(Optional.empty(), HomeReturn.NEVER.home());
        assertFalse(HomeReturn.NEVER.returnsAfter(1));
    }

    // --- when it goes home ---------------------------------------------------------------------------------------

    /** The delay is a threshold, not a moment: the tick it is reached sends the crane, and so does every later one. */
    @Test
    void aBentWarehouseReturnsOnceTheDelayIsReached() {
        HomeReturn rule = HomeReturn.of(DELAY, 2, Optional.of(HOME));
        assertTrue(rule.enabled());
        assertTrue(rule.homePointHasEffect());
        assertFalse(rule.returnsAfter(0), "a crane that just finished a job stays where it is");
        assertFalse(rule.returnsAfter(DELAY - 1), "one tick before the delay it is still waiting");
        assertTrue(rule.returnsAfter(DELAY), "the tick the delay is reached it goes");
        assertTrue(rule.returnsAfter(DELAY + 1));
        assertTrue(rule.returnsAfter(HomeReturn.MAX_IDLE_TICKS), "and it never stops wanting to");
    }

    /** A delay of one tick is legal and returns on the first idle tick. */
    @Test
    void theShortestDelayReturnsOnTheFirstIdleTick() {
        HomeReturn rule = HomeReturn.of(1, 2, Optional.empty());
        assertFalse(rule.returnsAfter(0), "the tick a job ends is not yet a tick of waiting");
        assertTrue(rule.returnsAfter(1));
    }

    // --- where it parks ------------------------------------------------------------------------------------------

    /** With a home point: its aisle, its position, its level and its side, arm in, facing the way that aisle runs. */
    @Test
    void itParksInFrontOfItsHomePoint() {
        CranePose pose = HomeReturn.of(DELAY, 3, Optional.of(HOME)).parkPose(Heading.SOUTH).orElseThrow();
        assertEquals(HOME.branch(), pose.branch());
        assertEquals(HOME.x(), pose.x());
        assertEquals(HOME.y(), pose.y());
        assertEquals(HOME.side(), pose.side());
        assertEquals(CranePose.RETRACTED, pose.arm(), "the arm is in: a parked crane blocks nothing");
        assertEquals(CranePose.yawOf(Heading.SOUTH), pose.yaw(),
                "and it faces the way its aisle runs, so the next job needs no extra turn");
    }

    /** Without a home point the dock is home: position 0 of the aisle at the dock, at dock level. */
    @Test
    void withoutAHomePointTheDockIsHome() {
        HomeReturn rule = HomeReturn.of(DELAY, 3, Optional.empty());
        assertTrue(rule.enabled(), "a bent warehouse without a home point still returns its crane");
        assertFalse(rule.homePointHasEffect(), "there is simply no block to report anything about");
        CranePose pose = rule.parkPose(Heading.WEST).orElseThrow();
        assertEquals(RackPosition.FIRST_BRANCH, pose.branch());
        assertEquals(0.0, pose.x());
        assertEquals(0.0, pose.y());
        assertEquals(CranePose.RETRACTED, pose.arm());
        assertEquals(CranePose.yawOf(Heading.WEST), pose.yaw());
        assertEquals(RackPosition.FIRST_BRANCH, rule.homeBranch());
        assertEquals(0.0, rule.homeX());
    }

    /** Breaking the home point falls back to the dock without any further rule. */
    @Test
    void breakingTheHomePointFallsBackToTheDock() {
        HomeReturn rule = HomeReturn.of(DELAY, 3, Optional.of(HOME));
        assertEquals(HOME.branch(), rule.homeBranch());
        HomeReturn broken = rule.withHome(Optional.empty());
        assertEquals(RackPosition.FIRST_BRANCH, broken.homeBranch());
        assertEquals(0.0, broken.homeX());
        assertEquals(RackPosition.FIRST_BRANCH, broken.parkPose(Heading.NORTH).orElseThrow().branch());
        assertTrue(broken.enabled(), "and it still goes home, to the dock");
    }

    // --- counting the wait ---------------------------------------------------------------------------------------

    /** A crane is waiting exactly while it is idle, has no job and is not paused. */
    @Test
    void onlyAnIdleUnpausedCraneWithoutAJobIsWaiting() {
        assertTrue(HomeReturn.isWaiting(CranePhase.IDLE, false, false));
        assertFalse(HomeReturn.isWaiting(CranePhase.IDLE, true, false), "a job means work, whatever the phase says");
        assertFalse(HomeReturn.isWaiting(CranePhase.IDLE, false, true), "a paused crane is stopped, not waiting");
        for (CranePhase phase : CranePhase.values()) {
            if (phase != CranePhase.IDLE)
                assertFalse(HomeReturn.isWaiting(phase, false, false), phase + " is not waiting for work");
        }
    }

    /** The counter runs while the crane waits, resets the moment it does not, and never overflows. */
    @Test
    void theIdleCounterRunsOnlyWhileTheCraneWaits() {
        int ticks = 0;
        for (int tick = 0; tick < 5; tick++)
            ticks = HomeReturn.countIdle(ticks, true);
        assertEquals(5, ticks);
        assertEquals(0, HomeReturn.countIdle(ticks, false), "a job resets it at once");
        assertEquals(0, HomeReturn.countIdle(0, false));
        assertEquals(HomeReturn.MAX_IDLE_TICKS, HomeReturn.countIdle(HomeReturn.MAX_IDLE_TICKS, true),
                "and a crane that waited for a week counts no further");
        assertEquals(1, HomeReturn.countIdle(-7, true), "an impossible counter is repaired rather than trusted");
    }

    /**
     * A crane that pauses mid-wait keeps its counter: it is stopped, not busy, so it goes home as soon as it has
     * rotation again instead of starting to wait all over.
     */
    @Test
    void aPauseNeitherRunsNorResetsTheCounter() {
        int ticks = 100;
        boolean waiting = HomeReturn.isWaiting(CranePhase.IDLE, false, true);
        assertFalse(waiting);
        // The content layer only calls countIdle while the crane is waiting, so a paused tick changes nothing at all.
        assertEquals(ticks + 1, HomeReturn.countIdle(ticks, true), "and it carries on where it left off");
    }

    // --- bounds --------------------------------------------------------------------------------------------------

    @Test
    void anImpossibleRuleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> HomeReturn.of(-1, 2, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> HomeReturn.of(DELAY, 0, Optional.empty()));
        assertThrows(NullPointerException.class, () -> HomeReturn.of(DELAY, 2, null));
        assertThrows(NullPointerException.class, () -> HomeReturn.of(DELAY, 2, Optional.of(HOME)).parkPose(null));
    }
}
