package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * {@link WarehouseThroughput}: "Crane Throughput" read off the controller of a warehouse with several cranes (issue
 * #14, ADR-075 §15.3). Trips and items are added up, the shares are the mean, and nothing is added up until every
 * window holds a whole minute.
 */
class WarehouseThroughputTest {
    private static final int MINUTE = ThroughputWindow.WINDOW_TICKS;

    /** A whole minute with {@code travel} ticks of driving, {@code turn} of turning, and the trips and items. */
    private static CraneThroughput minute(int travel, int turn, int trips, int items) {
        return new CraneThroughput(MINUTE, MINUTE - travel - turn, 0, travel, turn, 0, 0, trips, items, 0);
    }

    @Test
    void oneCraneIsItsOwnTotal() {
        CraneThroughput one = minute(600, 120, 3, 96);
        WarehouseThroughput total = WarehouseThroughput.of(List.of(one)).orElseThrow();
        assertEquals(1, total.cranes());
        assertEquals(one.trips(), total.trips());
        assertEquals(one.items(), total.items());
        assertEquals(one.busyShare(), total.busyShare(), "exactly what the dock's own board shows");
        assertEquals(one.turnShare(), total.turnShare());
    }

    @Test
    void tripsAndItemsAddUpAndSharesAreTheMean() {
        CraneThroughput busy = minute(MINUTE, 0, 4, 128);
        CraneThroughput half = minute(MINUTE / 2, 0, 1, 16);
        CraneThroughput idle = minute(0, 0, 0, 0);
        WarehouseThroughput total = WarehouseThroughput.of(List.of(busy, half, idle)).orElseThrow();
        assertEquals(3, total.cranes());
        assertEquals(5L, total.trips());
        assertEquals(144L, total.items());
        assertEquals(50, total.busyShare(), "(100 + 50 + 0) / 3, which is never above 100 %");
        assertEquals(0, total.turnShare());
    }

    @Test
    void theMeanIsRoundedHalfUp() {
        // 100 % and 1 % make 50.5 %: rounded half up.
        WarehouseThroughput total = WarehouseThroughput.of(List.of(minute(MINUTE, 0, 0, 0),
                minute(MINUTE / 100, 0, 0, 0))).orElseThrow();
        assertEquals(51, total.busyShare());
    }

    @Test
    void nothingIsAddedUpBeforeEveryCraneHasAMinute() {
        CraneThroughput young = new CraneThroughput(MINUTE / 2, MINUTE / 2, 0, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(young.observedSeconds() < ThroughputWindow.BUCKETS);
        assertEquals(Optional.empty(), WarehouseThroughput.of(List.of(minute(10, 0, 1, 1), young)),
                "one crane still measuring keeps the whole warehouse measuring");
        assertEquals(Optional.empty(), WarehouseThroughput.of(List.of()), "no crane, no total");
    }
}
