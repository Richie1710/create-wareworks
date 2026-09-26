package dev.wareworks.core.stock;

import java.util.Objects;

/**
 * One rule of an aisle, judged against the warehouse's current levels (M15, issue #3): everything a goggle line, a
 * screen row or a terminal badge needs, in the order the rules are listed.
 * <p>
 * The derived numbers are the <b>effective</b> ones, i.e. what this rule actually applies. A rule that does not govern
 * — shadowed by an earlier one for the same item, above the aisle's rule cap, or with no number switched on — caps
 * nothing ({@link #headroom()} is {@link Long#MAX_VALUE}), calls for nothing and holds nothing back, whatever numbers
 * it carries. Its {@link #levels()} are {@link StockLevels#NONE}, because the levels of a rule that applies nothing are
 * never looked up.
 *
 * @param index   position in {@link StockRules#rules()}, i.e. the aisle-wide order the controller keeps
 * @param rule    the rule as it is configured
 * @param status  what its three numbers say right now, <b>unrefined</b>: this is what the rule counts as, and what the
 *                keeper's comparator and the controller's counters are taken from
 * @param levels  the levels it was judged against, {@link StockLevels#NONE} when it governs nothing
 * @param restock what automatic restocking last did about it (M15 part 2), {@link RestockOutcome#NOT_GOVERNING} when
 *                restocking has nothing to say — which refines nothing, so an aisle that never restocks reads exactly
 *                as it did before
 * @param <K>     item key type
 */
public record StockRuleEvaluation<K>(int index, StockRule<K> rule, StockRuleStatus status, StockLevels levels,
                                     RestockOutcome restock) {
    public StockRuleEvaluation {
        if (index < 0)
            throw new IllegalArgumentException("index must not be negative: " + index);
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(levels, "levels");
        if (restock == null)
            restock = RestockOutcome.NOT_GOVERNING;
    }

    /** An evaluation automatic restocking has nothing to say about. */
    public StockRuleEvaluation(int index, StockRule<K> rule, StockRuleStatus status, StockLevels levels) {
        this(index, rule, status, levels, RestockOutcome.NOT_GOVERNING);
    }

    /**
     * The status every <b>surface</b> shows: {@link #status()} refined by what restocking is doing about it
     * ({@link RestockOutcome#refine}). Never used for counting, which is what {@link #status()} is for.
     */
    public StockRuleStatus displayStatus() {
        return restock.refine(status);
    }

    /** This evaluation with the restock outcome restocking arrived at for its item. */
    public StockRuleEvaluation<K> withRestock(RestockOutcome outcome) {
        return outcome == restock ? this : new StockRuleEvaluation<>(index, rule, status, levels, outcome);
    }

    /** The item this rule is about. */
    public K key() {
        return rule.key();
    }

    /** Whether the rule applies anything at all ({@link StockRuleStatus#governs()}). */
    public boolean governs() {
        return status.governs();
    }

    /** Whether one of the three numbers bites right now ({@link StockRuleStatus#bites()}); the keeper's lamp. */
    public boolean bites() {
        return status.bites();
    }

    /** How many more items of the key may still be stored; {@link Long#MAX_VALUE} without an effective maximum. */
    public long headroom() {
        return governs() ? rule.headroom(levels) : Long.MAX_VALUE;
    }

    /** How many items the warehouse is short of the minimum this rule effectively calls for; 0 when it calls for none. */
    public long shortfall() {
        return governs() ? rule.shortfall(levels) : 0L;
    }

    /** How many of the available items this rule effectively holds back from automation. */
    public long heldBack() {
        return governs() ? rule.heldBack(levels.available()) : 0L;
    }
}
