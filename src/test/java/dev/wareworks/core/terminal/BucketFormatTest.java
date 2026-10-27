package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Fluid amounts in the terminal's 18 pixel fluid cells and in its tooltips (M31, issue #21). */
class BucketFormatTest {
    @Test
    void compactKeepsFourCharactersAndNeverShowsMore() {
        assertEquals("0", BucketFormat.compact(0));
        assertEquals("0", BucketFormat.compact(-1000));
        assertEquals("<.01", BucketFormat.compact(1));
        assertEquals("0.01", BucketFormat.compact(10));
        assertEquals("0.25", BucketFormat.compact(250));
        assertEquals("0.5", BucketFormat.compact(500));
        assertEquals("0.99", BucketFormat.compact(999), "truncated, never rounded up");
        assertEquals("1", BucketFormat.compact(1000));
        assertEquals("2.5", BucketFormat.compact(2_500));
        assertEquals("9.9", BucketFormat.compact(9_999));
        assertEquals("10", BucketFormat.compact(10_000));
        assertEquals("64", BucketFormat.compact(64_000));
        assertEquals("1.2K", BucketFormat.compact(1_234_000));
        long[] samples = {1, 9, 99, 999, 1_000, 9_999, 64_000, 999_999, 2_560_000, 1_000_000_000L, Long.MAX_VALUE};
        for (long sample : samples)
            assertTrue(BucketFormat.compact(sample).length() <= BucketFormat.MAX_LENGTH,
                    sample + " -> " + BucketFormat.compact(sample));
    }

    @Test
    void bucketsAreExactWithAsFewDecimalsAsNeeded() {
        assertEquals("0", BucketFormat.buckets(0));
        assertEquals("64", BucketFormat.buckets(64_000));
        assertEquals("1.25", BucketFormat.buckets(1_250));
        assertEquals("0.001", BucketFormat.buckets(1));
        assertEquals("0.5", BucketFormat.buckets(500));
        assertEquals(64L, BucketFormat.wholeBuckets(64_999));
        assertEquals(0L, BucketFormat.wholeBuckets(-5));
    }

    @Test
    void aFluidClickReadsTheAmountAsBuckets() {
        assertEquals(10_000L, TerminalAmounts.fluidMillibucketsFor(TerminalAmounts.Click.SELECTED, 10, 0, 0, 64));
        assertEquals(64_000L, TerminalAmounts.fluidMillibucketsFor(TerminalAmounts.Click.SELECTED, 100, 0, 0, 64),
                "bounded by maxTerminalFluidRequestBuckets");
        assertEquals(16_000L, TerminalAmounts.fluidMillibucketsFor(TerminalAmounts.Click.STACK, 1, 0, 0, 64));
        assertEquals(8_000L, TerminalAmounts.fluidMillibucketsFor(TerminalAmounts.Click.STACK, 1, 0, 0, 8));
        assertEquals(7_000L, TerminalAmounts.fluidMillibucketsFor(TerminalAmounts.Click.ALL, 1, 7_000, 32_000, 64),
                "Ctrl asks for what the server says it can serve");
        assertEquals(32_000L, TerminalAmounts.fluidMillibucketsFor(TerminalAmounts.Click.ALL, 1, 0, 32_000, 64),
                "with no container in stock, everything available, so the server can answer or offer");
        assertEquals(1L, TerminalAmounts.fluidMillibucketsFor(TerminalAmounts.Click.ALL, 1, 0, 0, 64),
                "never below one millibucket");
    }
}
