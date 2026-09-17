package dev.wareworks.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link LogThrottle}: the first line is always logged, further ones only after the interval. Guards the fix of the
 * one-shot latches that silenced every later storage-interop failure of a block entity.
 */
class LogThrottleTest {
    private static final long INTERVAL = 1200;

    @Test
    void theFirstLineIsAlwaysLogged() {
        assertTrue(new LogThrottle(INTERVAL).tryLog(0));
        assertTrue(new LogThrottle(INTERVAL).tryLog(-500));
        assertTrue(new LogThrottle(INTERVAL).tryLog(Long.MAX_VALUE));
    }

    @Test
    void furtherLinesWaitForTheInterval() {
        LogThrottle throttle = new LogThrottle(INTERVAL);
        assertTrue(throttle.tryLog(100));
        assertFalse(throttle.tryLog(100), "same tick");
        assertFalse(throttle.tryLog(101));
        assertFalse(throttle.tryLog(100 + INTERVAL - 1), "one tick before the interval is over");
        assertTrue(throttle.tryLog(100 + INTERVAL), "exactly at the interval");
    }

    @Test
    void everyLoggedLineRestartsTheInterval() {
        LogThrottle throttle = new LogThrottle(INTERVAL);
        long now = 0;
        int logged = 0;
        for (int i = 0; i < 5 * INTERVAL; i++) {
            if (throttle.tryLog(now++))
                logged++;
        }
        assertEquals(5, logged, "one line per interval, not one per call and not one for ever");
    }

    @Test
    void aClockThatMovedBackwardsLogsAgain() {
        LogThrottle throttle = new LogThrottle(INTERVAL);
        assertTrue(throttle.tryLog(10_000));
        assertTrue(throttle.tryLog(5), "a level whose time was set back must not silence the line for ever");
        assertFalse(throttle.tryLog(6), "and the interval starts again from there");
    }

    @Test
    void zeroIntervalLogsEveryCall() {
        LogThrottle throttle = new LogThrottle(0);
        assertTrue(throttle.tryLog(1));
        assertTrue(throttle.tryLog(1));
        assertTrue(throttle.tryLog(2));
    }

    @Test
    void theDefaultIntervalIsUsable() {
        LogThrottle throttle = new LogThrottle();
        assertTrue(throttle.tryLog(0));
        assertFalse(throttle.tryLog(LogThrottle.DEFAULT_MIN_INTERVAL_TICKS - 1));
        assertTrue(throttle.tryLog(LogThrottle.DEFAULT_MIN_INTERVAL_TICKS));
    }

    @Test
    void negativeIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LogThrottle(-1));
    }
}
