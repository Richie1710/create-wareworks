package dev.wareworks.core.terminal;

/**
 * How much a click on a warehouse terminal's item asks for ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * The screen carries one <b>selected amount</b> (a Create scroll input, 1 … {@code maxTerminalRequestAmount}); a click
 * on an item requests it, and the modifier keys are the shortcuts a storage mod is expected to have:
 * <ul>
 * <li>plain click → the selected amount;</li>
 * <li>shift-click → one stack of that item;</li>
 * <li>control-click → everything that is available.</li>
 * </ul>
 * Every result is clamped to {@code [1, max]} and, while something can be ordered at all, to that amount, so the screen
 * never asks for more than the server could grant. With nothing orderable the raw amount is kept on purpose: the server
 * then answers with the real reason ({@code NOT_IN_STOCK}) instead of the screen inventing one.
 * <p>
 * <b>"Everything possible" includes what can be made</b> (M11, ADR-024). A terminal offers items a production station
 * can produce even at zero stock, so the bound is {@code available + producible}, where the producible half is the
 * number the <b>server</b> computed from the ingredients its patterns need ({@code StockCount#producibleAmount()}).
 * The screen knows neither the patterns nor what the ingredients are already promised to, so it must never derive that
 * number itself — it only uses the one it was last told.
 * <p>
 * <b>That number is one step deep, so it does not bound a chosen amount of a makeable item</b> (issue #42). It counts
 * what the ingredients in the racks make right now, while the server plans whole chains (M20, ADR-032): stairs out of
 * tiles it first has to make out of bricks. Clamping a click to it cut 274 stairs down to the 40 the tiles in stock
 * gave, before the server could plan the rest. A plain or Shift click on an item a pattern makes therefore keeps its
 * amount, and the server clamps it to what its plan can really make and says why it stopped there. Only Ctrl, which
 * asks for "everything", still takes the number it was told.
 */
public final class TerminalAmounts {
    /** The smallest amount a request can ask for. */
    public static final int MIN_AMOUNT = 1;

    /** Which shortcut a click used. */
    public enum Click {
        /** Plain click: the amount the screen's scroll input shows. */
        SELECTED,
        /** Shift: one stack of the clicked item. */
        STACK,
        /** Control: everything the aisle can still promise. */
        ALL
    }

    private TerminalAmounts() {
    }

    /** {@code amount} clamped to {@code [1, max]}; a {@code max} below 1 is treated as 1. */
    public static int clamp(int amount, int max) {
        int upper = Math.max(MIN_AMOUNT, max);
        return Math.min(Math.max(amount, MIN_AMOUNT), upper);
    }

    /**
     * The selected amount after a scroll or a button press: {@code amount + delta * step}, clamped to {@code [1, max]}.
     * Saturates instead of overflowing.
     */
    public static int stepped(int amount, int delta, int step, int max) {
        long moved = (long) amount + (long) delta * Math.max(1, step);
        return clamp((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, moved)), max);
    }

    /** {@link #amountFor(Click, int, int, long, long, int)} for an item no production pattern makes. */
    public static int amountFor(Click click, int selected, int stackSize, long available, int max) {
        return amountFor(click, selected, stackSize, available, 0L, max);
    }

    /**
     * {@link #amountFor(Click, int, int, long, long, int)} that knows whether a pattern of the aisle makes the item at
     * all: then a plain or Shift click keeps its amount (bounded by {@code max} only), because the server may make more
     * through a chain than {@code producible} says (issue #42).
     *
     * @param makeable whether a production pattern of the aisle makes the item, whatever its ingredients
     */
    public static int amountFor(Click click, int selected, int stackSize, long available, long producible,
            boolean makeable, int max) {
        if (makeable && click != Click.ALL)
            return amountFor(click, selected, stackSize, 0L, 0L, max);
        return amountFor(click, selected, stackSize, available, producible, max);
    }

    /**
     * The amount a click asks for.
     *
     * @param click      which modifier the player held
     * @param selected   the screen's selected amount
     * @param stackSize  the clicked item's max stack size (at least 1)
     * @param available  what the terminal last reported as available for that item
     * @param producible what the terminal last reported as producible right now for that item, computed on the server
     *                   (M11, ADR-024); 0 for an item nothing can make
     * @param max        {@code maxTerminalRequestAmount}
     */
    public static int amountFor(Click click, int selected, int stackSize, long available, long producible, int max) {
        long orderable = Math.max(0L, available) + Math.max(0L, producible);
        int wanted = switch (click) {
            case SELECTED -> clamp(selected, max);
            case STACK -> clamp(Math.max(MIN_AMOUNT, stackSize), max);
            case ALL -> clamp((int) Math.min(orderable, max), max);
        };
        if (orderable <= 0L)
            return wanted; // let the server answer with the real reason
        return clamp((int) Math.min(wanted, orderable), max);
    }

    /** Buckets a Shift-click on a fluid cell asks for (M31): a stack's worth of crane trips, like an item's Shift. */
    public static final int FLUID_STACK_BUCKETS = 16;

    /**
     * Millibuckets a click on a fluid cell asks for (M31, issue #21, {@code docs/warehouse-system.md} §3.9.6): the
     * selected amount read as <b>buckets</b>, {@value #FLUID_STACK_BUCKETS} buckets with Shift, and with Ctrl what the
     * server says a request for everything would serve ({@code servableMb}), which may be any number of millibuckets —
     * or, while no container is in stock, everything available, so the server can answer with the real reason or offer
     * to make containers. Every amount is bounded by {@code maxBuckets} and is at least 1 mB.
     */
    public static long fluidMillibucketsFor(Click click, int selectedBuckets, long servableMb, long availableMb,
            int maxBuckets) {
        long max = (long) Math.max(MIN_AMOUNT, maxBuckets) * BucketFormat.BUCKET;
        long wanted = switch (click) {
            case SELECTED -> (long) Math.max(MIN_AMOUNT, selectedBuckets) * BucketFormat.BUCKET;
            case STACK -> (long) FLUID_STACK_BUCKETS * BucketFormat.BUCKET;
            case ALL -> servableMb > 0L ? servableMb : availableMb;
        };
        return Math.max(1L, Math.min(wanted, max));
    }
}
