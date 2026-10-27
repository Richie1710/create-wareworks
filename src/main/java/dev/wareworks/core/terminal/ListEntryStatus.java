package dev.wareworks.core.terminal;

import java.util.Locale;

/**
 * What the warehouse can do for one entry of a clipboard list, as the list question's table shows it
 * ({@code docs/warehouse-system.md} §3.4.4, issue #36, ADR-069).
 * <p>
 * <b>The declaration order is the table's sort order</b>: the entries a player has to do something about come first
 * (red, then gold), then the ones production covers (blue), then the ones the racks cover (green). Derived from a
 * line's split on both sides of the wire and never sent or saved, so the order is free to say exactly that.
 */
public enum ListEntryStatus {
    /** Nothing at all can be had for the entry: not from the racks, not from production. Red. */
    IMPOSSIBLE,
    /** Only part of the entry can be had. Gold. */
    PARTLY,
    /** The entry can be had in full, but only with production for part or all of it. Blue. */
    CRAFTED,
    /** The racks cover the entry in full. Green. */
    AVAILABLE;

    private static final String LANG_PREFIX = "gui.terminal.list.table.status.";

    /**
     * The status of an entry with this split.
     *
     * @param fromStock what the racks give of it
     * @param produced  what production would make for it
     * @param missing   what cannot be had at all
     */
    public static ListEntryStatus of(long fromStock, long produced, long missing) {
        if (missing <= 0L)
            return produced <= 0L ? AVAILABLE : CRAFTED;
        return fromStock + produced > 0L ? PARTLY : IMPOSSIBLE;
    }

    /** The word the table's status column shows, without the mod prefix. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }
}
