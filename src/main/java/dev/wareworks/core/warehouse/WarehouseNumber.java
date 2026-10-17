package dev.wareworks.core.warehouse;

import java.util.Locale;
import java.util.Optional;

/**
 * What the Display Link source "Warehouse Number" shows: <b>one</b> number about the whole warehouse, picked in the
 * Display Link screen ({@code docs/warehouse-system.md} §10, ADR-026, issue #32).
 * <p>
 * <b>The order of the values is a save format.</b> A Display Link keeps its source's settings as an NBT compound, and
 * Create's selection input writes the option a player picked as its <b>index</b> under {@code "Mode"}
 * ({@code ModularGuiLine#saveValues}). So {@link #index()} is what a world on disk says, and it is the
 * {@link #ordinal()}: a value moved, removed or put in the middle would make every link already built show a different
 * number without a word. <b>New values only ever go at the end</b>, which {@code WarehouseNumberTest} and the GameTest
 * {@code warehousenumbermodeorder} pin. Nothing else carries the index: the value travels to the target as finished
 * text, and the screen reads the saved index back from the link itself.
 * <p>
 * Every value is read from state the controller already keeps (counts, the stock index, the fluid index, the request
 * queue and the order book), never from an inventory or the world, so a pull stays a handful of field reads.
 * <p>
 * Pure Java: the lang key of a value is a plain suffix, so the JUnit lang tests can walk the values without Minecraft.
 */
public enum WarehouseNumber {
    /** Items stored over all storage locations: the "Items" line of the Warehouse Summary. */
    ITEMS,
    /** Distinct item keys in stock: the "Item types" line of the Warehouse Summary. */
    ITEM_TYPES,
    /** Fluid stored over all fluid bays, in Create's own unit ({@code "48.0 B"} / {@code "250 mB"}). */
    FLUID,
    /**
     * Counted storage locations holding something, an inventory with at least one item or a fluid bay with fluid: the
     * first number of the "Locations" line.
     */
    LOCATIONS_USED,
    /** Counted storage locations of the warehouse, fluid bays included: the second number of the "Locations" line. */
    LOCATIONS_TOTAL,
    /**
     * {@link #LOCATIONS_USED} as a share of {@link #LOCATIONS_TOTAL}, in whole percent and rounded down
     * ({@link #share}), so the display only says 100 % when every counted location really holds something.
     */
    LOCATIONS_USED_SHARE,
    /** Open retrieval requests: what the controller's goggles call "Open requests". */
    OPEN_REQUESTS,
    /** Open production orders: what the controller's goggles call "Production orders". */
    PRODUCTION_ORDERS,
    /** Stock rules whose item the warehouse is short of. */
    RULES_BELOW_MINIMUM,
    /** Stock rules that stop their item from being stored. */
    RULES_AT_MAXIMUM,
    /** Items the safety stop holds: the "Stopped products" line of the Warehouse Summary. */
    STOPPED_PRODUCTS,
    /** Warehouse ports that accept items out of the warehouse. */
    ACCEPTING_PORTS,
    /** Warehouse ports that collect items out of a machine. */
    COLLECTING_PORTS,
    /** Members at rack positions with the wrong facing: what the controller's goggles call "Misaligned blocks". */
    MISALIGNED;

    private static final WarehouseNumber[] VALUES = values();

    /** The value a link saved as {@code index}; empty for an index no value has (a later build's, or a forged one). */
    public static Optional<WarehouseNumber> byIndex(int index) {
        return index >= 0 && index < VALUES.length ? Optional.of(VALUES[index]) : Optional.empty();
    }

    /** The index a Display Link saves for this value; see the class comment for why it may never change. */
    public int index() {
        return ordinal();
    }

    /** The tail of this value's name in the Display Link screen, below {@code display_source.number.}. */
    public String langKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * {@code part} of {@code whole} in whole percent, rounded <b>down</b> and kept within 0..100; 0 when there is no
     * whole. Rounding down is what keeps a nearly full warehouse from reading full, the rule every share in the mod
     * follows ({@code CraneThroughput#share}).
     */
    public static int share(long part, long whole) {
        if (whole <= 0L || part <= 0L)
            return 0;
        if (part >= whole)
            return 100;
        return (int) (part * 100L / whole);
    }
}
