package dev.wareworks.core.requester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Schematicannon Requester's bounds (issue #45): every configurable number is clamped, the defaults lie inside
 * their ranges, and a poll adds at most two intervals of live time.
 */
class RequesterLimitsTest {
    @Test
    void theDefaultsLieInsideTheirRanges() {
        assertEquals(RequesterLimits.DEFAULT_POLL_TICKS, RequesterLimits.clampPoll(RequesterLimits.DEFAULT_POLL_TICKS));
        assertEquals(RequesterLimits.DEFAULT_TIMEOUT_TICKS,
                RequesterLimits.clampTimeout(RequesterLimits.DEFAULT_TIMEOUT_TICKS));
        assertEquals(RequesterLimits.DEFAULT_REFRESH_TICKS,
                RequesterLimits.clampRefresh(RequesterLimits.DEFAULT_REFRESH_TICKS));
        assertEquals(RequesterLimits.DEFAULT_GRACE_SECONDS,
                RequesterLimits.clampGraceSeconds(RequesterLimits.DEFAULT_GRACE_SECONDS));
        assertEquals(20, RequesterLimits.DEFAULT_POLL_TICKS, "once a second");
        assertEquals(12_000, RequesterLimits.DEFAULT_TIMEOUT_TICKS, "ten minutes");
        assertEquals(1_200, RequesterLimits.DEFAULT_REFRESH_TICKS, "a minute");
        assertEquals(10, RequesterLimits.DEFAULT_GRACE_SECONDS);
        assertEquals(4, RequesterLimits.MAX_ORDERS);
        assertEquals(512, RequesterLimits.MAX_COUNTED_ITEMS);
    }

    @Test
    void everyConfiguredNumberIsClamped() {
        assertEquals(10, RequesterLimits.clampPoll(0));
        assertEquals(200, RequesterLimits.clampPoll(Integer.MAX_VALUE));
        assertEquals(600, RequesterLimits.clampTimeout(Integer.MIN_VALUE));
        assertEquals(72_000, RequesterLimits.clampTimeout(Integer.MAX_VALUE));
        assertEquals(200, RequesterLimits.clampRefresh(-1));
        assertEquals(72_000, RequesterLimits.clampRefresh(1_000_000));
        assertEquals(0, RequesterLimits.clampGraceSeconds(-5));
        assertEquals(60, RequesterLimits.clampGraceSeconds(61));
    }

    @Test
    void theGraceIsCountedInTicks() {
        assertEquals(200, RequesterLimits.graceTicks(10));
        assertEquals(0, RequesterLimits.graceTicks(0), "none, for a funnel straight into the chest");
        assertEquals(1_200, RequesterLimits.graceTicks(500), "clamped to a minute");
    }

    @Test
    void aPollAddsAtMostTwoIntervalsOfLiveTime() {
        assertEquals(20, RequesterLimits.liveStep(120, 100, 20));
        assertEquals(40, RequesterLimits.liveStep(100_000, 100, 20), "an unload in between does not count");
        assertEquals(0, RequesterLimits.liveStep(100, -1, 20), "the first poll adds nothing");
        assertEquals(0, RequesterLimits.liveStep(100, 200, 20), "a clock that went backwards adds nothing");
        assertEquals(20, RequesterLimits.liveStep(1_000, 0, 1), "the interval is clamped to its minimum of 10");
        assertTrue(RequesterLimits.liveStep(Long.MAX_VALUE, 0, 200) <= 400);
    }

    @Test
    void aMakeRetryWaitsFivePolls() {
        assertEquals(100, RequesterLimits.makeRetryTicks(20));
        assertEquals(RequesterLimits.MAKE_RETRY_POLLS * RequesterLimits.MIN_POLL_TICKS,
                RequesterLimits.makeRetryTicks(1), "of a clamped poll");
        assertEquals(RequesterLimits.MAKE_RETRY_POLLS * RequesterLimits.MAX_POLL_TICKS,
                RequesterLimits.makeRetryTicks(10_000));
    }
}
