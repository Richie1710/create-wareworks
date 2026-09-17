package dev.wareworks.core.job;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One entry of a {@link ReservationLedger}: an amount of a key promised to a job ({@code docs/warehouse-system.md} §7).
 *
 * @param jobId     the job holding the reservation
 * @param kind      what is promised
 * @param location  for {@link Kind#CAPACITY} the target, for {@link Kind#STOCK} the source, for {@link Kind#TRANSIT} the
 *                  output station the held items travel to
 * @param key       item key
 * @param amount    promised amount, at least 1 (the ledger never stores empty reservations)
 * @param requestId who the promised items are for: a retrieval request, or the ingredient line of a production order
 *                  ({@code core.production.SupplyLine}). Never present for {@link Kind#CAPACITY}
 * @param <K>       item key type
 * @param <L>       location type
 */
public record Reservation<K, L>(UUID jobId, Kind kind, L location, K key, int amount, Optional<UUID> requestId) {
    /** What a reservation promises. {@link #name()} is a stable save name. */
    public enum Kind {
        /** Free space at a target location, so no other job plans into the same space. */
        CAPACITY,
        /** Items still inside a source location, so no other job or request claims them. */
        STOCK,
        /**
         * Items already in a handling head on their way to a delivery station (an output or a production station);
         * they are no longer indexed stock.
         */
        TRANSIT
    }

    public Reservation {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestId, "requestId");
        if (amount < 1)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        if (requestId.isPresent() && kind == Kind.CAPACITY)
            throw new IllegalArgumentException("capacity reservations do not back requests");
    }

    /** Whether the reserved items back an open retrieval request's or production ingredient line's remaining amount. */
    public boolean backsRequest() {
        return requestId.isPresent();
    }

    /** The same reservation with another amount (at least 1). */
    public Reservation<K, L> withAmount(int newAmount) {
        return new Reservation<>(jobId, kind, location, key, newAmount, requestId);
    }

    /** The same reservation without a request. */
    public Reservation<K, L> withoutRequest() {
        return new Reservation<>(jobId, kind, location, key, amount, Optional.empty());
    }
}
