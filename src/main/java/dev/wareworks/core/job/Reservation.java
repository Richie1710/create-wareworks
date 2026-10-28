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
 *                  output station the held items travel to, for {@link Kind#FILLING} the current target of the fill
 *                  trip (the bay before the fill, the station after it), for
 *                  {@link Kind#BUFFER} the input station or collecting port the items will be picked from
 * @param key       item key
 * @param amount    promised amount, at least 1 (the ledger never stores empty reservations)
 * @param requestId who the promised items are for: a retrieval request, or the ingredient line of a production order
 *                  ({@code core.production.SupplyLine}). Never present for {@link Kind#CAPACITY} and
 *                  {@link Kind#BUFFER}
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
        TRANSIT,
        /**
         * Containers of a <b>fill trip</b> already in a handling head, empty on their way to the fluid bay or filled on
         * their way to the station (M31, issue #21, ADR-073), with the fill request they are for.
         * <p>
         * It counts towards that request ({@code committedToRequest}, so the planner does not plan the same containers a
         * second time) and towards <b>no</b> per-key total: the empties have left the racks, and the filled containers
         * are not stock anybody's item request is owed from. Counting them as {@link #TRANSIT} of their key would make
         * an item click on "Bucket" or "Lava Bucket" more or less available by the trip in flight.
         */
        FILLING,
        /**
         * Items in a station's buffer - an input station's or a collecting port's - that a job will pick (issue #14,
         * ADR-075 section 9). One crane could only ever carry one trip out of a buffer at a time, so nothing was
         * reserved there; with several cranes two of them must not plan the same buffered items. It is its own
         * aggregate and <b>never</b> counted as stock: buffered items are no indexed stock, so the stock index
         * arithmetic is untouched. Released at the pick like a stock reservation.
         */
        BUFFER
    }

    public Reservation {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestId, "requestId");
        if (amount < 1)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        if (requestId.isPresent() && (kind == Kind.CAPACITY || kind == Kind.BUFFER))
            throw new IllegalArgumentException(kind + " reservations do not back requests");
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
