package dev.wareworks.core.stock;

/**
 * What the three numbers of a stock keeper row count (M31, issue #21, ADR-073, {@code docs/warehouse-system.md}
 * §3.6).
 * <p>
 * A row's ghost is an item. For almost every item that is the end of it: the rule counts that item in the racks. A
 * <b>filled fluid container</b> — a lava bucket — can mean two things, though: the lava buckets lying on the shelves, or
 * the lava in the fluid bays. The unit says which. It is chosen when the row is written (a filled container starts out
 * counting its fluid) and a player can switch it on the row; a row that has no unit in its save data counts items, so a
 * lava bucket rule written before M31 keeps meaning exactly what it meant.
 * <p>
 * A fluid rule counts <b>millibuckets</b> of the container's fluid, and its three numbers act like an item rule's with
 * one difference: the minimum is only shown, because no production pattern makes fluid.
 */
public enum RuleUnit {
    /** The rule counts the row's item in the racks. Every rule before M31. */
    ITEMS,
    /** The rule counts the fluid of the row's filled container in the fluid bays, in millibuckets. */
    FLUID;

    /** The saved name of {@link #FLUID}; {@link #ITEMS} is never written, so its absence is what reads as items. */
    public static final String FLUID_NAME = "fluid";

    /** The unit a save names: {@link #FLUID} for {@value #FLUID_NAME}, {@link #ITEMS} for anything else. */
    public static RuleUnit byName(String name) {
        return FLUID_NAME.equals(name) ? FLUID : ITEMS;
    }

    /**
     * {@code value} of a rule number moved from this unit to {@code target}, for a container that holds
     * {@code capacityMb}: buckets of items become their millibuckets and back, rounded down. {@link StockRule#UNSET}
     * and every negative number stay {@link StockRule#UNSET}; a non-positive capacity changes nothing.
     */
    public long convert(long value, RuleUnit target, long capacityMb) {
        if (value < 0L)
            return StockRule.UNSET;
        if (target == this || capacityMb <= 0L)
            return value;
        if (target == FLUID)
            return value > StockRule.MAX_AMOUNT / capacityMb ? StockRule.MAX_AMOUNT : value * capacityMb;
        return value / capacityMb;
    }
}
