package dev.wareworks.core.terminal;

/**
 * Compact amounts for the item cells of a warehouse terminal screen ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * A cell is 18 pixels wide, so a count is rendered with at most <b>four</b> characters: plain below 1000, then scaled
 * with one decimal while the whole part is a single digit ("1.2K"), and without a decimal above that ("12K", "999K",
 * "1.2M", "12M", "1.2B"). Truncation is towards zero, so a shown amount is never larger than the real one. The exact
 * amount is always in the cell's tooltip.
 */
public final class CountFormat {
    /** Longest string {@link #compact} can return, e.g. "999K". */
    public static final int MAX_LENGTH = 4;

    private static final long THOUSAND = 1_000L;
    private static final long MILLION = 1_000_000L;
    private static final long BILLION = 1_000_000_000L;
    /** Above this whole part the decimal is dropped, so the text stays within {@value #MAX_LENGTH} characters. */
    private static final long DECIMALS_BELOW = 10L;
    /** Largest whole part shown; a larger amount saturates instead of growing the text. */
    private static final long MAX_WHOLE = 999L;

    private CountFormat() {
    }

    /** {@code count} as at most {@value #MAX_LENGTH} characters; negative counts read as "0". */
    public static String compact(long count) {
        if (count <= 0L)
            return "0";
        if (count < THOUSAND)
            return Long.toString(count);
        if (count < MILLION)
            return scaled(count, THOUSAND, "K");
        if (count < BILLION)
            return scaled(count, MILLION, "M");
        return scaled(count, BILLION, "B");
    }

    private static String scaled(long count, long unit, String suffix) {
        // Saturates at three digits: an amount beyond 999 billion cannot exist in a warehouse, and the cell has no
        // room for it either. A saturated text still never claims more than there is.
        long whole = Math.min(count / unit, MAX_WHOLE);
        if (whole >= DECIMALS_BELOW)
            return whole + suffix;
        long tenths = (count % unit) * 10L / unit;
        return tenths == 0L ? whole + suffix : whole + "." + tenths + suffix;
    }
}
