package dev.wareworks.content.station;

import java.util.Optional;

/**
 * What a click in a Warehouse Optimizer's screen asks the server to do (issue #46, ADR-079). Travels by {@link #name()}
 * ({@code network.OptimizerActionPayload}), so an unknown action from another build reads as none and does nothing.
 */
public enum OptimizerAction {
    /** Put an item into a row: a ghost, nothing leaves the player's hand. */
    SET_ITEM,
    /** Empty a row. */
    CLEAR_ROW,
    /** Add the items of the clipboard the player carries on the cursor to the free rows. */
    IMPORT_CLIPBOARD,
    /** Start a fresh order for every listed item. */
    OPTIMISE,
    /** Start a fresh order for one row's item alone. */
    OPTIMISE_ROW,
    /** Stop the running order; a trip under way is finished. */
    CANCEL;

    /** The action with this name, or empty for {@code null} or an unknown name. */
    public static Optional<OptimizerAction> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (OptimizerAction action : values()) {
            if (action.name().equals(name))
                return Optional.of(action);
        }
        return Optional.empty();
    }
}
