package dev.wareworks.core.production;

/**
 * What the crafting recipe check says about one production pattern while a Warehouse Crafter Feeder is attached to its
 * station (issue #34, ADR-067).
 * <p>
 * A vanilla Crafter crafts nothing from a grid that matches no crafting recipe and consumes nothing either, so a pattern
 * that is no recipe would have its ingredients fetched into the station and then lie there until the order times out.
 * The station therefore hides every pattern this check does not call {@link #VALID} from the planner while a feeder is
 * attached — nothing is ever fetched for it — and its screen shows it red with the reason.
 * <p>
 * <b>The ordinal is the wire format</b> ({@code ProductionScreenState}): new constants are appended, never inserted, and
 * an unknown ordinal reads as {@link #NOT_CHECKED} ({@link #byOrdinal}).
 */
public enum CrafterPatternCheck {
    /** No feeder is attached, so nothing was checked: every complete pattern is used as it always was. */
    NOT_CHECKED,
    /** The slot holds no complete pattern (no result, or no cell): not a pattern yet, and nothing to show in red. */
    INCOMPLETE,
    /** The grid is a crafting recipe that makes exactly the pattern's result. */
    VALID,
    /** A filled cell holds more than one item; a Crafter takes one item per slot and craft. */
    CELL_NOT_ONE,
    /** The grid matches no crafting recipe. */
    NOT_A_RECIPE,
    /** The grid is a crafting recipe, but it makes another item or another amount than the pattern says. */
    RESULT_DIFFERS;

    /** The check with this ordinal, or {@link #NOT_CHECKED} for an ordinal a newer version wrote. */
    public static CrafterPatternCheck byOrdinal(int ordinal) {
        CrafterPatternCheck[] values = values();
        return ordinal < 0 || ordinal >= values.length ? NOT_CHECKED : values[ordinal];
    }

    /** Whether the pattern is complete but the feeder refuses it, which the screen draws in red with the reason. */
    public boolean refused() {
        return this == CELL_NOT_ONE || this == NOT_A_RECIPE || this == RESULT_DIFFERS;
    }

    /** Whether the planner may use the pattern: always without a feeder, and with one only after a passed check. */
    public boolean usable() {
        return this == NOT_CHECKED || this == VALID;
    }
}
