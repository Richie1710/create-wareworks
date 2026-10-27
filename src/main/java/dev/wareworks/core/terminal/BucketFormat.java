package dev.wareworks.core.terminal;

/**
 * Fluid amounts in buckets for the fluid cells of a warehouse terminal screen (M31, issue #21,
 * {@code docs/warehouse-system.md} §3.9.6).
 * <p>
 * The amount is stored in millibuckets (1 000 mB = one bucket); a player thinks in buckets. A cell keeps
 * {@link CountFormat}'s four characters: whole buckets from ten on ({@code "64"}, {@code "1.2K"}), one decimal below
 * ten ({@code "2.5"}), and two below one bucket ({@code "0.25"}). Truncation is towards zero, so a cell never claims
 * more than there is; an amount too small to show as {@code "0.01"} reads {@code "<.01"}. The exact amount is in the
 * tooltip, as {@link #buckets(long)} next to the millibuckets.
 */
public final class BucketFormat {
    /** Millibuckets in one bucket (NeoForge's {@code FluidType.BUCKET_VOLUME}). */
    public static final int BUCKET = 1_000;
    /** Longest string {@link #compact} can return. */
    public static final int MAX_LENGTH = CountFormat.MAX_LENGTH;

    private static final long TEN_BUCKETS = 10L * BUCKET;

    private BucketFormat() {
    }

    /** {@code millibuckets} as at most {@value #MAX_LENGTH} characters of buckets; negative amounts read as "0". */
    public static String compact(long millibuckets) {
        if (millibuckets <= 0L)
            return "0";
        if (millibuckets >= TEN_BUCKETS)
            return CountFormat.compact(millibuckets / BUCKET);
        if (millibuckets >= BUCKET) {
            long whole = millibuckets / BUCKET;
            long tenths = millibuckets % BUCKET / 100L;
            return tenths == 0L ? Long.toString(whole) : whole + "." + tenths;
        }
        long hundredths = millibuckets / 10L;
        if (hundredths == 0L)
            return "<.01";
        String digits = hundredths < 10L ? "0" + hundredths : Long.toString(hundredths);
        return "0." + (digits.endsWith("0") ? digits.substring(0, 1) : digits);
    }

    /**
     * {@code millibuckets} as an exact number of buckets, with as many decimals as it needs and no more:
     * {@code "64"}, {@code "1.25"}, {@code "0.001"}. Negative amounts read as "0".
     */
    public static String buckets(long millibuckets) {
        if (millibuckets <= 0L)
            return "0";
        long whole = millibuckets / BUCKET;
        long rest = millibuckets % BUCKET;
        if (rest == 0L)
            return Long.toString(whole);
        String decimals = String.format("%03d", rest);
        while (decimals.endsWith("0"))
            decimals = decimals.substring(0, decimals.length() - 1);
        return whole + "." + decimals;
    }

    /** Whole buckets in {@code millibuckets}, rounded down; never negative. */
    public static long wholeBuckets(long millibuckets) {
        return Math.max(0L, millibuckets) / BUCKET;
    }
}
