package dev.wareworks.client.render;

/**
 * What a bay shows ({@link RackBayRenderer}, {@link FluidBayRenderer}; issues #49 and #61), decided without any
 * Minecraft class so a JUnit test can pin it ({@code BayDisplayTest}).
 * <p>
 * The stock comes first: a bay that holds something shows what it holds (a rack bay its stored item, a fluid bay its
 * fluid at its level), whatever its filter says (a List Filter that lets the stock in is not what a player wants to
 * read off a full bay). An empty bay with a store filter shows the filter <b>item</b>, so a wall that is set up but not
 * yet filled reads as "this bay is for X". A fluid bay shows the filter item too and never a ghost of the fluid,
 * because the drawn fluid is its contents readout and a ghost would read as stock. A Create filter item (List,
 * Attribute or Package Filter) is shown as that item, as Create's own filter slot draws it. An empty bay without a
 * filter shows nothing.
 */
public enum BayDisplay {
    /** The stored goods: the item type of a rack bay, the fluid of a fluid bay. */
    STORED,
    /** The store filter item, because the bay is empty. */
    FILTER,
    /** Nothing: an empty bay that accepts everything. */
    NOTHING;

    /** Stored goods first, else the filter, else nothing. */
    public static BayDisplay choose(boolean hasStored, boolean hasFilter) {
        if (hasStored)
            return STORED;
        return hasFilter ? FILTER : NOTHING;
    }
}
