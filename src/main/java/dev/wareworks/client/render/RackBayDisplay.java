package dev.wareworks.client.render;

/**
 * What a rack bay shows at the mouth of the bay ({@link RackBayRenderer}, issue #49), decided without any Minecraft
 * class so a JUnit test can pin it ({@code RackBayDisplayTest}).
 * <p>
 * The stock comes first: a bay that holds something shows what it holds, whatever its filter says (a List Filter that
 * lets the stock in is not what a player wants to read off a full bay). An empty bay with a store filter shows the
 * filter, so a rack wall that is set up but not yet filled reads as "this bay is for X" — before issue #49 such a bay
 * drew nothing at all, and the filter only became visible with the first item stored. A Create filter item (List,
 * Attribute or Package Filter) is shown as that item, as Create's own filter slot draws it. An empty bay without a
 * filter shows nothing.
 */
public enum RackBayDisplay {
    /** The stored item type. */
    STORED,
    /** The store filter item, because the bay is empty. */
    FILTER,
    /** Nothing: an empty bay that accepts everything. */
    NOTHING;

    /** Stored key first, else the filter, else nothing. */
    public static RackBayDisplay choose(boolean hasStored, boolean hasFilter) {
        if (hasStored)
            return STORED;
        return hasFilter ? FILTER : NOTHING;
    }
}
