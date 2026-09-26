package dev.wareworks.core.stock;

import java.util.List;
import java.util.Objects;

import dev.wareworks.core.production.ProductionPattern;

/**
 * Everything {@code RestockPlanner} needs to know about one stock rule to decide whether the warehouse should order
 * its item (M15 part 2, issue #3).
 * <p>
 * It is a plain snapshot of facts the content layer has already collected for its rule tick: nothing here reads a
 * world, resolves a block entity or walks a queue, which is what keeps the decision itself pure and testable. The
 * availability of the ingredients is <b>not</b> part of it — it is a function passed to the planner, because one
 * lookup snapshot is shared by every rule of a pass so that two rules cannot be promised the same items.
 *
 * @param rule       the rule as it is configured
 * @param levels     what the warehouse knows about its item right now; the minimum is judged against
 *                   {@link StockLevels#pipeline()}, so a rule that has already ordered counts that order
 * @param governs    whether the rule applies anything at all ({@code StockRules#governs}); a shadowed, inert or
 *                   unfinished rule orders nothing
 * @param paused     whether the safety stop is holding this rule ({@link StockRulePause})
 * @param openOrders automatic production orders that are already open for this item, whichever rule started them
 * @param patterns   the patterns of the aisle that make this item, in aisle order; empty when none does
 * @param <K>        item key type
 */
public record RestockInput<K>(StockRule<K> rule, StockLevels levels, boolean governs, boolean paused, int openOrders,
                              List<ProductionPattern<K>> patterns) {
    public RestockInput {
        Objects.requireNonNull(rule, "rule");
        if (levels == null)
            levels = StockLevels.NONE;
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        openOrders = Math.max(0, openOrders);
    }

    /** A governing, unpaused rule with no open order of its own. */
    public static <K> RestockInput<K> of(StockRule<K> rule, StockLevels levels,
            List<ProductionPattern<K>> patterns) {
        return new RestockInput<>(rule, levels, true, false, 0, patterns);
    }

    /** The item this rule governs. */
    public K key() {
        return rule.key();
    }
}
