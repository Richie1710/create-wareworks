package dev.wareworks.core.terminal;

import java.util.Objects;

/**
 * One line of a warehouse terminal's item list as the screen holds it: the amounts of a {@link StockCount} plus the two
 * texts the search matches, the item's display name and its mod id.
 * <p>
 * The texts are supplied by the client (the display name is language dependent, so the server never sends it); the pure
 * model only reads them. Matching lowercases on demand ({@link TerminalSearch}), which is cheap for the bounded number
 * of entries a terminal may report ({@code maxTerminalStockEntries}).
 *
 * @param key       the item identity (the content layer uses {@code ItemKey})
 * @param total     what the aisle stores
 * @param available what a new request may still claim
 * @param producible whether a production station of the aisle can make this item (M11, ADR-024)
 * @param producibleAmount how many of it the aisle could make right now, as the <b>server</b> computed it from the
 *                  ingredients its patterns need ({@link StockCount#producibleAmount()})
 * @param name      display name, e.g. "Iron Ingot"
 * @param modId     namespace of the item's id, e.g. "minecraft"
 * @param <K>       item key type
 */
public record StockLine<K>(K key, long total, long available, boolean producible, long producibleAmount, String name,
                           String modId) {
    public StockLine {
        Objects.requireNonNull(key, "key");
        total = Math.max(0L, total);
        available = Math.max(0L, Math.min(available, total));
        producibleAmount = producible ? Math.max(0L, producibleAmount) : 0L;
        name = name == null ? "" : name;
        modId = modId == null ? "" : modId;
    }

    /** A line of an item that is simply in stock, i.e. one no production pattern makes. */
    public StockLine(K key, long total, long available, String name, String modId) {
        this(key, total, available, false, 0L, name, modId);
    }

    /** A line of an item the aisle can make, without saying how many of it it could make right now. */
    public StockLine(K key, long total, long available, boolean producible, String name, String modId) {
        this(key, total, available, producible, 0L, name, modId);
    }

    /** A line for {@code counts} with the texts the client resolved for it. */
    public static <K> StockLine<K> of(StockCount<K> counts, String name, String modId) {
        Objects.requireNonNull(counts, "counts");
        return new StockLine<>(counts.key(), counts.total(), counts.available(), counts.producible(),
                counts.producibleAmount(), name, modId);
    }

    /** The amounts without the texts. */
    public StockCount<K> counts() {
        return new StockCount<>(key, total, available, producible, producibleAmount);
    }

    /** Whether the line is worth showing: something is stored, or the aisle can produce it (M11, ADR-024). */
    public boolean isShown() {
        return total > 0L || producible;
    }

    /**
     * Whether this line is only an <b>offer</b>: the aisle holds none of the item but can make it (M11, ADR-024).
     * Such lines are marked in the grid and sorted behind everything that is really in stock ({@link TerminalSort}).
     */
    public boolean isProducibleOnly() {
        return total <= 0L && producible;
    }

    /** What is stored but already promised to a running job or an open request. */
    public long reserved() {
        return total - available;
    }

    /** The largest amount one request may ask for right now ({@link StockCount#orderable()}). */
    public long orderable() {
        return available + producibleAmount;
    }
}
