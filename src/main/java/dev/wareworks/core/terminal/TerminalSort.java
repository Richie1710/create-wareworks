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
 * player can have right now. The rule is therefore the first key of every comparator rather than a side effect of the
 * amounts — under {@link #AMOUNT} an empty line would sort last anyway, but under {@link #NAME} it would not, and
 * under {@link #USED} a favourite the warehouse has just run out of would jump to the top.
 * <p>
 * <b>Under {@link #USED} that first key sorts three groups, not two</b> (M24 review fix). A terminal shows a third
 * kind of row with nothing in it: an item a <b>stock rule</b> governs keeps its row at zero stock, so that a
 * warehouse calling for something it has run out of says so where a player looks (M15). Such a row is neither stocked
 * nor producible, so {@code isProducibleOnly()} is false for it, and with a two-group head the usage key behind it
 * would lift it — a favourite the warehouse has just run out of — to the very <b>top</b> of "most used", where it is a
 * guaranteed refusal ({@link StockLine#orderable()} is 0 and the server answers {@code NOT_IN_STOCK}). The head of
 * that order is therefore <i>stocked, then empty, then an offer</i>, which is the grouping {@link #AMOUNT} produces by
 * itself through its amount keys — so "most used" without a history is still <b>exactly</b> the amount order, and the
 * other two orders are untouched.
 * <p>
 * The test is {@link StockLine#total()}, never {@link StockLine#available()}: an item the warehouse holds 320 of that
 * is all promised to open requests is <b>stock</b>, and a player who keeps asking for it wants to see it first.
 * <p>
 * <b>Saving a choice</b> uses {@link #name()} and {@link #byName(String)}, never the ordinal: the cycle order is a
 * presentation decision and may change again, while save data must not (M24, issue #17).
 */
public enum TerminalSort {
    /** Most available first, then the largest stock, then by name: what a player asking "what can I get" wants. */
    AMOUNT,
    /**
     * What this player has asked for most often first ({@link TerminalUsage}), then exactly the {@link #AMOUNT} order.
     * <p>
     * The tie-break is the amount order and not how recently something was requested, although the store knows that
     * too: the amounts are on the screen and a player can see why two rows are in this order, while a recency they
     * cannot see would make the list look shuffled. For a player with no history every count is 0, so the whole order
     * <b>is</b> {@link #AMOUNT} — the fall-back needs no case of its own.
     */
    USED,
    /** By display name (case-insensitive), then by mod id: for finding a known item. */
    NAME;

    /** The order a player who has never chosen one sees. */
    public static final TerminalSort DEFAULT = AMOUNT;

    /** The order saved as {@code name}, or {@link #DEFAULT} for anything unknown (save data is never rejected). */
    public static TerminalSort byName(String name) {
        if (name != null) {
            for (TerminalSort sort : values()) {
                if (sort.name().equalsIgnoreCase(name))
                    return sort;
            }
        }
        return DEFAULT;
    }

    /** The next order of the cycle (wraps around). */
    public TerminalSort next() {
        TerminalSort[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Relative lang key of this order's label, e.g. {@code gui.terminal.sort.amount}. */
    public String langKey() {
        return "gui.terminal.sort." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Relative lang key of the sentence that says what this order does, e.g. {@code gui.terminal.sort.amount.detail}.
     * <p>
     * The label and the sentence are two keys and not one because they are drawn in two places of one tooltip and have
     * two different jobs: the label names the order beside the button's icon and has to stay short enough for the
     * terminal's own row width, while the sentence is the only place a cycling icon button can explain itself (M24,
     * issue #17).
     */
    public String detailKey() {
        return langKey() + ".detail";
    }

    /** The comparator of this order for a player whose history is unknown ({@link TerminalUsageCounts#none()}). */
    public <K> Comparator<StockLine<K>> comparator() {
        return comparator(TerminalUsageCounts.none());
    }

    /**
     * The comparator of this order, reading {@code usage} for {@link #USED}; items that are only producible are last in
     * every order (see the class comment). {@code null} counts as no history.
     */
    public <K> Comparator<StockLine<K>> comparator(TerminalUsageCounts<K> usage) {
        TerminalUsageCounts<K> counts = usage == null ? TerminalUsageCounts.none() : usage;
        Comparator<StockLine<K>> stockedFirst = Comparator.comparingInt(line -> line.isProducibleOnly() ? 1 : 0);
        // "Most used" needs a head of three groups — stocked, empty, an offer — because the usage key would otherwise
        // lift an empty row a stock rule keeps to the top (see the class comment).
        Comparator<StockLine<K>> storedFirst =
                Comparator.comparingInt(line -> line.isProducibleOnly() ? 2 : line.total() <= 0L ? 1 : 0);
        return switch (this) {
            case AMOUNT -> byAmount(stockedFirst);
            case USED -> byAmount(storedFirst.thenComparingLong(line -> -counts.countFor(line.key())));
            case NAME -> stockedFirst.thenComparing(StockLine::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(StockLine::modId)
                    .thenComparingLong(line -> -line.available());
        };
    }

    /** {@code head}, then the largest available, the largest stock, the name and the mod id. */
    private static <K> Comparator<StockLine<K>> byAmount(Comparator<StockLine<K>> head) {
        return head.thenComparingLong(line -> -line.available())
                .thenComparingLong(line -> -line.total())
                .thenComparing(StockLine::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(StockLine::modId);
    }
}
