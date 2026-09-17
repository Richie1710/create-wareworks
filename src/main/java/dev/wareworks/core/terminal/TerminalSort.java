package dev.wareworks.core.terminal;

import java.util.Comparator;
import java.util.Locale;

/**
 * How a warehouse terminal screen orders its item list. The button on the screen cycles through the values in
 * declaration order ({@link #next()}).
 * <p>
 * Every order is a <b>total</b> order over the visible texts and amounts, so the same warehouse always produces the
 * same list; ties that remain keep the order the list already had, because sorting is stable.
 * <p>
 * <b>What is really in stock comes first, in every order</b> (M11, ADR-024). A terminal also offers items at zero
 * stock that a production station could make ({@link StockLine#isProducibleOnly()}), and those are an <i>offer</i>,
 * not inventory: mixing them into the list by name would put "Oak Planks (none, can be made)" between two items a
 * player can have right now. The rule is therefore the first key of both comparators rather than a side effect of the
 * amounts — under {@link #AMOUNT} an empty line would sort last anyway, but under {@link #NAME} it would not.
 */
public enum TerminalSort {
    /** Most available first, then the largest stock, then by name: what a player asking "what can I get" wants. */
    AMOUNT,
    /** By display name (case-insensitive), then by mod id: for finding a known item. */
    NAME;

    /** The next order of the cycle (wraps around). */
    public TerminalSort next() {
        TerminalSort[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Relative lang key of this order's label, e.g. {@code gui.terminal.sort.amount}. */
    public String langKey() {
        return "gui.terminal.sort." + name().toLowerCase(Locale.ROOT);
    }

    /** The comparator of this order; items that are only producible are last in both (see the class comment). */
    public <K> Comparator<StockLine<K>> comparator() {
        Comparator<StockLine<K>> stockedFirst = Comparator.comparingInt(line -> line.isProducibleOnly() ? 1 : 0);
        return switch (this) {
            case AMOUNT -> stockedFirst.thenComparingLong(line -> -line.available())
                    .thenComparingLong(line -> -line.total())
                    .thenComparing(StockLine::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(StockLine::modId);
            case NAME -> stockedFirst.thenComparing(StockLine::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(StockLine::modId)
                    .thenComparingLong(line -> -line.available());
        };
    }
}
