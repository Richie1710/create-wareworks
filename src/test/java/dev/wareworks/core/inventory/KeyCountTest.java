package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KeyCountTest {
    private static Map<String, Long> totals(Object... keyCountPairs) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < keyCountPairs.length; i += 2)
            result.put((String) keyCountPairs[i], ((Number) keyCountPairs[i + 1]).longValue());
        return result;
    }

    private static List<String> keys(List<KeyCount<String>> entries) {
        List<String> result = new ArrayList<>(entries.size());
        for (KeyCount<String> entry : entries)
            result.add(entry.key());
        return result;
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> new KeyCount<>("iron", -1));
        assertThrows(NullPointerException.class, () -> new KeyCount<>(null, 1));
    }

    @Test
    void largestFirstOrdersByCount() {
        List<KeyCount<String>> top = KeyCount.largestFirst(totals("iron", 5, "gold", 30, "clay", 12), 3);
        assertEquals(List.of("gold", "clay", "iron"), keys(top));
        assertEquals(30L, top.get(0).count());
    }

    @Test
    void largestFirstCutsToN() {
        assertEquals(List.of("gold", "clay"), keys(KeyCount.largestFirst(totals("iron", 5, "gold", 30, "clay", 12), 2)));
    }

    @Test
    void largestFirstClampsNToTheSize() {
        assertEquals(2, KeyCount.largestFirst(totals("iron", 5, "gold", 30), 10).size());
    }

    @Test
    void largestFirstIsEmptyWithoutRoomOrEntries() {
        assertTrue(KeyCount.largestFirst(totals("iron", 5), 0).isEmpty());
        assertTrue(KeyCount.largestFirst(totals("iron", 5), -1).isEmpty());
        assertTrue(KeyCount.largestFirst(Map.of(), 3).isEmpty());
    }

    @Test
    void largestFirstResultIsUnmodifiable() {
        List<KeyCount<String>> top = KeyCount.largestFirst(totals("iron", 5), 1);
        assertThrows(UnsupportedOperationException.class, () -> top.add(new KeyCount<>("gold", 1)));
    }

    /** Without a tie-break, equal counts keep the iteration order of the map they came from. */
    @Test
    void equalCountsKeepTheMapOrderWithoutATieBreak() {
        assertEquals(List.of("iron", "gold"), keys(KeyCount.largestFirst(totals("iron", 7, "gold", 7), 2)));
        assertEquals(List.of("gold", "iron"), keys(KeyCount.largestFirst(totals("gold", 7, "iron", 7), 2)));
    }

    /** With a tie-break, the same contents give the same order however the map was built (the stock list depends on it). */
    @Test
    void tieBreakMakesEqualCountsReproducible() {
        Comparator<String> byName = Comparator.naturalOrder();
        assertEquals(List.of("gold", "iron"), keys(KeyCount.largestFirst(totals("iron", 7, "gold", 7), 2, byName)));
        assertEquals(List.of("gold", "iron"), keys(KeyCount.largestFirst(totals("gold", 7, "iron", 7), 2, byName)));
    }

    /** The tie-break never outranks the count: a smaller amount stays below, whatever its key. */
    @Test
    void tieBreakNeverOutranksTheCount() {
        List<KeyCount<String>> top =
                KeyCount.largestFirst(totals("apple", 1, "zinc", 9, "brass", 9), 3, Comparator.naturalOrder());
        assertEquals(List.of("brass", "zinc", "apple"), keys(top));
    }

    /** The tie-break decides which equal entry still fits into a cut-off list. */
    @Test
    void tieBreakDecidesTheCut() {
        List<KeyCount<String>> top =
                KeyCount.largestFirst(totals("zinc", 4, "brass", 4, "iron", 4), 2, Comparator.naturalOrder());
        assertEquals(List.of("brass", "iron"), keys(top));
    }

    @Test
    void tieBreakIsRequired() {
        assertThrows(NullPointerException.class, () -> KeyCount.largestFirst(totals("iron", 1), 1, null));
    }
}
