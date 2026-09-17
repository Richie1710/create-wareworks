package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The compact amounts drawn into the 18 pixel wide item cells of the terminal. */
class CountFormatTest {
    @Test
    void smallCountsAreExact() {
        assertEquals("0", CountFormat.compact(0));
        assertEquals("0", CountFormat.compact(-5));
        assertEquals("1", CountFormat.compact(1));
        assertEquals("999", CountFormat.compact(999));
    }

    @Test
    void largeCountsAreScaledAndTruncated() {
        assertEquals("1K", CountFormat.compact(1_000));
        assertEquals("1.2K", CountFormat.compact(1_234));
        assertEquals("1.9K", CountFormat.compact(1_999), "truncation never shows more than there is");
        assertEquals("12K", CountFormat.compact(12_345));
        assertEquals("999K", CountFormat.compact(999_999));
        assertEquals("1M", CountFormat.compact(1_000_000));
        assertEquals("1.5M", CountFormat.compact(1_500_000));
        assertEquals("2.1B", CountFormat.compact(2_147_483_647L));
        assertEquals("999B", CountFormat.compact(Long.MAX_VALUE), "saturates instead of growing the text");
    }

    @Test
    void neverLongerThanACell() {
        long[] samples = {0, 9, 64, 999, 1_000, 1_024, 9_999, 65_536, 999_999, 1_000_000, 123_456_789,
                Long.MAX_VALUE};
        for (long count : samples)
            assertTrue(CountFormat.compact(count).length() <= CountFormat.MAX_LENGTH,
                    count + " -> " + CountFormat.compact(count));
    }
}
