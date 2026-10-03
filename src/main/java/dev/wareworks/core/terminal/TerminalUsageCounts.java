package dev.wareworks.core.terminal;

/**
 * How often a player has asked a warehouse terminal for an item — the one thing {@link TerminalSort#USED} needs to
 * order a list (M24, issue #17).
 * <p>
 * It is an interface and not the store itself because the two sides need different halves of the same model: the
 * <b>server</b> owns the counting ({@link TerminalUsage}, which implements this) and the <b>client</b> only ever reads
 * the numbers it was sent. A screen that holds one of these can sort without knowing anything about caps, eviction or
 * the fade.
 * <p>
 * {@link #countFor(Object)} is called once per comparison of a sort, so it must be a lookup and never a computation:
 * the terminal sorts a bounded list ({@code maxTerminalStockEntries}) when the screen rebuilds, not per frame
 * ({@link StockListModel}).
 *
 * @param <K> item key type (the content layer uses {@code ItemKey})
 */
@FunctionalInterface
public interface TerminalUsageCounts<K> {
    /**
     * How often this player has asked for {@code key}; 0 for an item they never requested, for an item the store has
     * forgotten, and for {@code null}. Never negative.
     */
    long countFor(K key);

    /**
     * The counts of a player with no history: everything 0.
     * <p>
     * This is what makes "most used" degrade into {@link TerminalSort#AMOUNT} instead of into an empty or random list
     * — with every count 0 the usage key of that comparator is constant and the amount keys behind it decide
     * ({@link TerminalSort#comparator(TerminalUsageCounts)}).
     */
    static <K> TerminalUsageCounts<K> none() {
        return key -> 0L;
    }
}
