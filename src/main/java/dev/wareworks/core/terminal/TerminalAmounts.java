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
}
