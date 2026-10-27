package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.RuleUnit;
import dev.wareworks.core.stock.StockRule;

/**
 * One configured row of a warehouse stock keeper as the controller copies it: the rule and what its numbers count
 * (M31, issue #21, ADR-073, {@code docs/warehouse-system.md} §3.6).
 * <p>
 * The rule is always keyed by the row's ghost <b>item</b>, whatever the unit, so the row order and the "first rule for
 * a key governs it" meaning stay exactly what they were. A {@link RuleUnit#FLUID} row whose ghost carries a fluid is
 * enforced as a rule on that fluid, in millibuckets ({@link #fluid()}); every other row is an item rule. A fluid row
 * whose ghost no longer reads as a filled container (a mod removed its fluid) quietly counts items again, so nothing
 * here ever has to throw.
 *
 * @param rule the row's rule, keyed by its ghost item
 * @param unit what its three numbers count
 */
public record KeeperRule(StockRule<ItemKey> rule, RuleUnit unit) {
    public KeeperRule {
        Objects.requireNonNull(rule, "rule");
        if (unit == null)
            unit = RuleUnit.ITEMS;
    }

    /** An item rule: what every row was before M31, and what a row without a saved unit still is. */
    public static KeeperRule items(StockRule<ItemKey> rule) {
        return new KeeperRule(rule, RuleUnit.ITEMS);
    }

    /** The row's ghost item. */
    public ItemKey key() {
        return rule.key();
    }

    /**
     * The fluid this row governs, in millibuckets: the fluid its ghost container carries, for a {@link RuleUnit#FLUID}
     * row only. Empty for an item row, and for a fluid row whose ghost carries no fluid (which is then enforced as an
     * item rule). Server side: it reads the container's fluid capability.
     */
    public Optional<FluidKey> fluid() {
        if (unit != RuleUnit.FLUID)
            return Optional.empty();
        return FluidContainers.contents(rule.key()).map(FluidContainers.Contents::fluid);
    }
}
