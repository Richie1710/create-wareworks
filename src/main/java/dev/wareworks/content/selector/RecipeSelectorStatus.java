package dev.wareworks.content.selector;

import java.util.Locale;

/**
 * What a Recipe Selector last did or why it does nothing (ADR-085, design §12): the goggle line of the selector block.
 * Saved by {@link #name()}, so a constant is never renamed. The statuses after {@link #CONTESTED} are the far tier's
 * (the Ender Recipe Selector, design §7.6); the refusals among them are also said in the action bar when a player hands
 * a box over by hand.
 */
public enum RecipeSelectorStatus {
    /** Placed and linked; the station has not asked for anything yet. */
    IDLE,
    /** The machine's filter names the station's selection. */
    SET,
    /** A hand changed the filter and the selector set it back. */
    SET_BACK,
    /** The selector is linked to no station. */
    NO_LINK,
    /** The linked station is gone, or no longer links back to this selector. */
    STATION_MISSING,
    /** The linked station's chunk is not loaded. */
    STATION_NOT_LOADED,
    /** The station switched its selector off; the selector forgot its selection. */
    STATION_SWITCHED_OFF,
    /** No saw or basin in front of the selector. */
    NO_MACHINE,
    /** The machine's chunk is not loaded. */
    MACHINE_NOT_LOADED,
    /** A filter item sits in the machine and is never overwritten. */
    FILTER_ITEM,
    /** The machine refused the item. */
    FILTER_REFUSED,
    /** Another selector writes the same filter, or the filter was changed back too often in one tick. */
    CONTESTED,
    /** Ender: placed, no package with a slip has come yet. */
    WAITING_FOR_SLIP,
    /** Ender: the machine cannot make the slip's result from what is in the package; the box stays where it was. */
    CANNOT_MAKE,
    /** Ender: no unpacking Packager in front of the selector. */
    NO_PACKAGER,
    /** Ender: a package from another station than the one this selector serves; the box stays where it was. */
    FOREIGN_SLIP,
    /** Ender: a package from an older switch of the station; the box stays where it was. */
    STALE_SLIP,
    /** Ender: a package for another product waits until the machine and the Packager's target are empty. */
    WAITS_FOR_EMPTY_LINE,
    /** Ender: a package port touching the Packager also touches another inventory, which bypasses the selector. */
    PORT_BYPASS,
    /** Ender: the Packager's item capability is not this selector's handler (another mod overrides it). */
    NO_VETO,
    /** Ender: the check failed with an error; the box stays where it was (fail closed). */
    FAILED;

    /** The status saved as {@code name}, or {@link #IDLE}. */
    public static RecipeSelectorStatus byName(String name) {
        if (name != null) {
            String upper = name.toUpperCase(Locale.ROOT);
            for (RecipeSelectorStatus status : values()) {
                if (status.name().equals(upper))
                    return status;
            }
        }
        return IDLE;
    }

    /** Whether this is a refusal of a box by an Ender Recipe Selector (the box stays where it was). */
    public boolean refusesBox() {
        return switch (this) {
            case CANNOT_MAKE, FOREIGN_SLIP, STALE_SLIP, WAITS_FOR_EMPTY_LINE, NO_MACHINE, MACHINE_NOT_LOADED,
                    FILTER_ITEM, FILTER_REFUSED, CONTESTED, FAILED -> true;
            default -> false;
        };
    }

    /** The relative lang key of the goggle line, {@code gui.recipe_selector.status.<name>}. */
    public String langKey() {
        return "gui.recipe_selector.status." + name().toLowerCase(Locale.ROOT);
    }
}
