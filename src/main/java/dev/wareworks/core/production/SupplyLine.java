package dev.wareworks.core.production;

import java.util.Objects;
import java.util.UUID;

/**
 * One ingredient a {@link ProductionOrder} still owes its production station ({@code docs/warehouse-system.md} §3.5):
 * deliver {@link #required()} items of {@link #key()} there, of which {@link #delivered()} have arrived.
 * <p>
 * A line is the production side's counterpart of a {@code RetrievalRequest}, and deliberately shaped like one: it has
 * its <b>own id</b>, which the {@code SUPPLY} job carries, so the reservation ledger tracks each ingredient separately
 * ({@code ReservationView#committedToRequest}) and the planner never plans a second trip for items that are already on
 * their way. One id per order would aggregate the three ingredients into one number and make that impossible.
 * <p>
 * Immutable; progress creates a copy with the same id.
 *
 * @param key       the ingredient
 * @param required  items one whole order needs (pattern count times the number of runs)
 * @param delivered items the crane already dropped into the production station, {@code 0..required}
 * @param <K>       item key type
 */
public record SupplyLine<K>(UUID id, K key, int required, int delivered) {
    public SupplyLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        if (required < 1)
            throw new IllegalArgumentException("required must be at least 1: " + required);
        if (delivered < 0 || delivered > required)
            throw new IllegalArgumentException("delivered must be within 0.." + required + ": " + delivered);
    }

    /** A line that has not been served yet. */
    public static <K> SupplyLine<K> of(UUID id, K key, int required) {
        return new SupplyLine<>(id, key, required, 0);
    }

    /** Items still to deliver. */
    public int remaining() {
        return required - delivered;
    }

    /** Whether everything this line asks for has arrived at the production station. */
    public boolean isComplete() {
        return remaining() == 0;
    }

    /**
     * This line with {@code amount} more items delivered; more than the remaining amount counts only up to it, so a
     * crane that dropped more than planned can never push the line past its own total.
     *
     * @throws IllegalArgumentException if {@code amount < 0}
     */
    public SupplyLine<K> withDelivered(int amount) {
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        int counted = Math.min(amount, remaining());
        return counted == 0 ? this : new SupplyLine<>(id, key, required, delivered + counted);
    }
}
