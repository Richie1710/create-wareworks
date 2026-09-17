package dev.wareworks.core.production;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a {@link ProductionOrder} stands ({@code docs/warehouse-system.md} §3.5, ADR-024). {@link #name()} is the
 * stable save name, so new constants may be added but existing ones must keep their names.
 * <p>
 * The happy path is {@link #WAITING_FOR_INGREDIENTS} → {@link #DELIVERED} → {@link #WAITING_FOR_RESULT} →
 * {@link #COMPLETE}; every open state can also end in {@link #TIMED_OUT} or {@link #CANCELLED}.
 */
public enum ProductionOrderState {
    /** The crane is still bringing ingredients to the production station. */
    WAITING_FOR_INGREDIENTS,
    /** Every ingredient is in the production station's buffer; the player's machine has not taken them yet. */
    DELIVERED,
    /** The machine took the ingredients out of the station; the result is expected through a warehouse input. */
    WAITING_FOR_RESULT,
    /** The expected amount of the result arrived in the warehouse. */
    COMPLETE,
    /** Nothing happened for {@code productionOrderTimeoutTicks}; the order gave up. */
    TIMED_OUT,
    /** A player or the controller cancelled the order. */
    CANCELLED;

    private static final String LANG_PREFIX = "gui.production.state.";

    /** Whether the order is still running, i.e. still promises ingredients and can still finish. */
    public boolean isOpen() {
        return this == WAITING_FOR_INGREDIENTS || this == DELIVERED || this == WAITING_FOR_RESULT;
    }

    /** Whether the order has ended, whatever the outcome. */
    public boolean isFinished() {
        return !isOpen();
    }

    /**
     * Whether ingredients were already handed to the player's machine in this state. Such items are <b>gone</b> from
     * the warehouse's point of view: a cancelled or timed-out order releases its reservations but can never take them
     * back out of a machine ({@code docs/warehouse-system.md} §3.5 "What cannot be undone").
     */
    public boolean handedIngredientsOver() {
        return this == DELIVERED || this == WAITING_FOR_RESULT || this == COMPLETE;
    }

    /** Relative lang key of this state's text, e.g. {@code gui.production.state.waiting_for_result}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The state with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<ProductionOrderState> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (ProductionOrderState state : values()) {
            if (state.name().equals(name))
                return Optional.of(state);
        }
        return Optional.empty();
    }
}
