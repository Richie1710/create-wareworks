package dev.wareworks.core.terminal;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;

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
 * @param rule      what the stock rule governing this item is doing, empty when none does ({@link StockCount#rule()})
 * @param ruleReserved how many of the available items the rule holds back from automation ({@link StockCount})
 * @param ruleMaximum the most of this item the rule lets the warehouse store ({@link StockCount#ruleMaximum()})
 * @param name      display name, e.g. "Iron Ingot"
 * @param modId     namespace of the item's id, e.g. "minecraft"
 * @param <K>       item key type
 */
public record StockLine<K>(K key, long total, long available, boolean producible, long producibleAmount,
                           Optional<StockRuleStatus> rule, long ruleReserved, long ruleMaximum, String name,
                           String modId) {
    public StockLine {
        Objects.requireNonNull(key, "key");
        total = Math.max(0L, total);
        available = Math.max(0L, Math.min(available, total));
        producibleAmount = producible ? Math.max(0L, producibleAmount) : 0L;
        if (rule == null)
            rule = Optional.empty();
        ruleReserved = rule.isPresent() ? Math.max(0L, Math.min(ruleReserved, available)) : 0L;
        ruleMaximum = rule.isPresent() && ruleMaximum >= 0L ? Math.min(ruleMaximum, StockRule.MAX_AMOUNT)
                : StockRule.UNSET;
        name = name == null ? "" : name;
        modId = modId == null ? "" : modId;
    }

    /** A line of an item that is simply in stock, i.e. one no production pattern makes and no rule governs. */
    public StockLine(K key, long total, long available, String name, String modId) {
        this(key, total, available, false, 0L, Optional.empty(), 0L, StockRule.UNSET, name, modId);
    }

    /** A line of an item the aisle can make, without saying how many of it it could make right now. */
    public StockLine(K key, long total, long available, boolean producible, String name, String modId) {
        this(key, total, available, producible, 0L, Optional.empty(), 0L, StockRule.UNSET, name, modId);
    }

    /** A line no stock rule governs. */
    public StockLine(K key, long total, long available, boolean producible, long producibleAmount, String name,
            String modId) {
        this(key, total, available, producible, producibleAmount, Optional.empty(), 0L, StockRule.UNSET, name, modId);
    }

    /** A line for {@code counts} with the texts the client resolved for it. */
    public static <K> StockLine<K> of(StockCount<K> counts, String name, String modId) {
        Objects.requireNonNull(counts, "counts");
        return new StockLine<>(counts.key(), counts.total(), counts.available(), counts.producible(),
                counts.producibleAmount(), counts.rule(), counts.ruleReserved(), counts.ruleMaximum(), name, modId);
    }

    /** The amounts without the texts. */
    public StockCount<K> counts() {
        return new StockCount<>(key, total, available, producible, producibleAmount, rule, ruleReserved, ruleMaximum);
    }

    /**
     * Whether the line is worth showing: something is stored, the aisle can produce it (M11, ADR-024), or a stock rule
     * governs it (M15) — a ruled item keeps its row at zero stock, or a reserve on something the warehouse has just
     * run out of would delete the row it is meant to explain.
     */
    public boolean isShown() {
        return total > 0L || producible || rule.isPresent();
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

    /** Whether a stock rule governs this item ({@link StockCount#ruled()}). */
    public boolean ruled() {
        return rule.isPresent();
    }

    /** What the warehouse's own automation could still be promised ({@link StockCount#availableToAutomation()}). */
    public long availableToAutomation() {
        return Math.max(0L, available - ruleReserved);
    }

    /** How many items of a request for {@code amount} come out of the reserve ({@link StockCount#fromReserve}). */
    public long fromReserve(long amount) {
        return Math.min(Math.max(0L, Math.max(0L, amount) - availableToAutomation()), ruleReserved);
    }

    /** The largest amount one request may ask for right now ({@link StockCount#orderable()}). */
    public long orderable() {
        return available + producibleAmount;
    }
}
