package dev.wareworks.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SyncThrottle}, the shared goggle sync throttle of interface, stations and controller
 * ({@code docs/warehouse-system.md} §3.1.1): a change marks a sync pending, and a pending sync is sent at most once per
 * interval. The rule that matters is that a <b>throttled change stays pending</b> and is sent on a later observation,
 * instead of being dropped (which would leave goggle summaries stale until the next unrelated change).
 */
class SyncThrottleTest {
    private static final int INTERVAL = 20;

    @Test
    void nothingIsSentWithoutAChange() {
        SyncThrottle throttle = new SyncThrottle(INTERVAL);
        assertFalse(throttle.isPending());
        assertFalse(throttle.tryConsume(0), "an unchanged summary is never sent");
        assertFalse(throttle.tryConsume(10_000));
    }

    @Test
    void theFirstChangeIsSentAtOnceAndClearsThePendingFlag() {
        SyncThrottle throttle = new SyncThrottle(INTERVAL);
        throttle.markPending();
        assertTrue(throttle.isPending());
        assertTrue(throttle.tryConsume(0));
        assertFalse(throttle.isPending(), "a sent sync is no longer pending");
        assertFalse(throttle.tryConsume(0), "and is not sent twice");
    }

    @Test
    void aThrottledChangeStaysPendingAndIsSentLater() {
        SyncThrottle throttle = new SyncThrottle(INTERVAL);
        throttle.markPending();
        assertTrue(throttle.tryConsume(100));

        throttle.markPending();
        assertFalse(throttle.tryConsume(100 + INTERVAL - 1), "throttled");
        assertTrue(throttle.isPending(), "a throttled change must not be lost");
        assertFalse(throttle.tryConsume(100 + INTERVAL - 1));
        assertTrue(throttle.isPending());

        assertTrue(throttle.tryConsume(100 + INTERVAL), "sent on the next observation past the interval");
        assertFalse(throttle.isPending());
    }

    @Test
    void severalChangesInsideOneIntervalSendOnce() {
        SyncThrottle throttle = new SyncThrottle(INTERVAL);
        throttle.markPending();
        assertTrue(throttle.tryConsume(0));
        int sent = 0;
        for (int now = 1; now <= INTERVAL; now++) {
            throttle.markPending();
            if (throttle.tryConsume(now))
                sent++;
        }
        assertTrue(sent == 1, "exactly one sync per interval, but " + sent);
    }

    @Test
    void repeatedMarkingIsIdempotent() {
        SyncThrottle throttle = new SyncThrottle(INTERVAL);
        throttle.markPending();
        throttle.markPending();
        assertTrue(throttle.tryConsume(0));
        assertFalse(throttle.tryConsume(INTERVAL), "two marks are still one pending sync");
    }

    @Test
    void zeroIntervalSendsEveryChange() {
        SyncThrottle throttle = new SyncThrottle(0);
        throttle.markPending();
        assertTrue(throttle.tryConsume(5));
        throttle.markPending();
        assertTrue(throttle.tryConsume(5));
    }

    @Test
    void negativeIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SyncThrottle(-1));
    }
}
