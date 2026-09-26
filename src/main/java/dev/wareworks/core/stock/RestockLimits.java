package dev.wareworks.core.stock;

/**
 * The hard bounds automatic restocking obeys (M15 part 2, issue #3), as the configuration sets them.
 * <p>
 * <b>Why bounds at all.</b> An automatic order spends real items and hands them to a machine that may never give them
 * back ({@code docs/warehouse-system.md} §3.5.4). Two numbers keep that exposure finite whatever a player writes into a
 * rule:
 * <ul>
 *   <li>{@link #ordersPerRule()} — how many automatic orders <b>one rule</b> may have open at a time. At the default
 *       of 1 a rule waits for its own order before it asks for more, which is what keeps a slow machine from
 *       collecting a queue of identical runs;</li>
 *   <li>{@link #amountPerOrder()} — the largest amount of the <b>product</b> one automatic order may ask for, so a
 *       minimum of 100 000 turns into a sequence of bounded runs instead of one order that fills a station buffer with
 *       a hundred crane trips;</li>
 *   <li>{@link #ingredientItemsPerOrder()} — the largest number of <b>ingredient</b> items one automatic order may
 *       spend, which is what actually bounds the loss. The product cap alone does not: a pattern of nine ingots to one
 *       block turns "at most 512 blocks" into 4608 ingots, and the whole point of the bounds is that the first loss the
 *       safety stop allows is a known size (M15 review fix).</li>
 * </ul>
 * {@link #ordersPerAisle()} bounds the whole aisle on top of that and is also the <b>off switch</b>: 0 in either
 * order count stops the warehouse ordering by itself, while every rule keeps enforcing its maximum and its reserve.
 * <p>
 * <b>One run is always allowed.</b> A run is the smallest thing a pattern can make, so an order for a single run of an
 * expensive pattern is started even when that one run costs more than {@link #ingredientItemsPerOrder()}. The bound is
 * about <i>repeats</i>: it is what stops one order from carrying a hundred runs, and {@link #ordersPerRule()} then
 * sequences the rest of a large shortfall, one order at a time, with the safety stop in between.
 * <p>
 * Every value is clamped into range on construction and nothing here ever throws: these numbers come from a config
 * file a player edits.
 *
 * @param ordersPerRule          automatic orders one rule may have open, {@code 0..}{@value #MAX_ORDERS}
 * @param ordersPerAisle         automatic orders one aisle may have open, {@code 0..}{@value #MAX_ORDERS}
 * @param amountPerOrder         largest amount of the product one automatic order may ask for,
 *                               {@code 1..}{@value #MAX_AMOUNT}
 * @param ingredientItemsPerOrder largest number of ingredient items one automatic order may spend beyond its first
 *                               run, {@code 1..}{@value #MAX_AMOUNT}
 */
public record RestockLimits(int ordersPerRule, int ordersPerAisle, long amountPerOrder,
                            long ingredientItemsPerOrder) {
    /** Largest number of automatic orders either bound may allow. */
    public static final int MAX_ORDERS = 256;
    /** Largest amount one automatic order may ask for. */
    public static final long MAX_AMOUNT = StockRule.MAX_AMOUNT;

    /** Automatic restocking switched off: rules still cap and still reserve, the warehouse just never orders. */
    public static final RestockLimits OFF = new RestockLimits(0, 0, 1L, 1L);

    /** The shipped defaults, for tests and for a caller without a configuration. */
    public static final RestockLimits DEFAULT = new RestockLimits(1, 4, 512L, 64L);

    public RestockLimits {
        ordersPerRule = Math.max(0, Math.min(ordersPerRule, MAX_ORDERS));
        ordersPerAisle = Math.max(0, Math.min(ordersPerAisle, MAX_ORDERS));
        amountPerOrder = Math.max(1L, Math.min(amountPerOrder, MAX_AMOUNT));
        ingredientItemsPerOrder = Math.max(1L, Math.min(ingredientItemsPerOrder, MAX_AMOUNT));
    }

    /** Whether the warehouse may order anything by itself at all. */
    public boolean enabled() {
        return ordersPerRule > 0 && ordersPerAisle > 0;
    }

    /** How much one order may ask for, given what a rule is short of; never more than {@link #amountPerOrder()}. */
    public long boundAmount(long shortfall) {
        return Math.max(0L, Math.min(shortfall, amountPerOrder));
    }

    /**
     * How many runs of a pattern one order may make, given what one run of it costs in ingredient items: at least 1,
     * and otherwise as many as fit into {@link #ingredientItemsPerOrder()}.
     *
     * @param ingredientItemsPerRun items one run of the pattern consumes, over all its ingredients; a value below 1 is
     *                              read as 1
     */
    public int boundRuns(int ingredientItemsPerRun) {
        long perRun = Math.max(1L, ingredientItemsPerRun);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, ingredientItemsPerOrder / perRun));
    }
}
