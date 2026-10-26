package dev.wareworks.core.production;

import java.util.Locale;
import java.util.Optional;

/**
 * What a Warehouse Crafter Feeder is doing, or why it is not (issue #34, ADR-067): the one line its goggles show.
 * <p>
 * {@link #name()} is the sync name ({@code CrafterFeederGoggleSummary}), so constants may be added but existing ones keep
 * their names. Nothing about it is saved: the status is derived again on the first server tick after a load.
 */
public enum FeederStatus {
    /** No delivered order of the station waits for the Crafter. */
    IDLE,
    /** An order is loaded and runs. */
    FEEDING,
    /** The feeder touches no production station with that station's back. */
    NO_STATION,
    /** The feeder touches the backs of two production stations and cannot tell which one it serves. */
    TWO_STATIONS,
    /** The station belongs to no loaded warehouse. */
    NO_WAREHOUSE,
    /** No vanilla Crafter stands in front of the feeder. */
    NO_CRAFTER,
    /** Nothing in front of the Crafter's output takes items; the Crafter would drop its result. */
    NO_OUTPUT,
    /** What stands in front of the Crafter's output cannot take the result and its remainders right now. */
    OUTPUT_FULL,
    /** The feeder needs rotation and has none (or is overstressed). */
    NO_ROTATION,
    /** A delivered order waits, but the Crafter's grid is not empty, so nothing can be laid into it. */
    GRID_NOT_EMPTY,
    /** The grid no longer holds the loaded layout: a player took or added items. */
    GRID_DISTURBED,
    /** Something other than the feeder powers the Crafter, so a pulse of the feeder would give no rising edge. */
    EXTERNAL_SIGNAL,
    /** The station's buffer does not hold the next batch. */
    MISSING_INGREDIENTS,
    /** The Crafter was pulsed and crafted nothing. */
    CRAFT_FAILED,
    /**
     * The loaded order's grid is no longer the crafting recipe it was checked against (a {@code /reload} or a datapack
     * removed or changed it): nothing more is laid and the grid is not pulsed, and the order times out as usual.
     */
    RECIPE_CHANGED;

    private static final String LANG_PREFIX = "gui.crafter_feeder.status.";

    /** Whether this status stops the feeder; the goggles show it in gold. */
    public boolean blocked() {
        return this != IDLE && this != FEEDING;
    }

    /** Relative lang key of this status, e.g. {@code gui.crafter_feeder.status.no_crafter}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The status with the given sync name, or empty for {@code null} or unknown names. */
    public static Optional<FeederStatus> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (FeederStatus status : values()) {
            if (status.name().equals(name))
                return Optional.of(status);
        }
        return Optional.empty();
    }
}
