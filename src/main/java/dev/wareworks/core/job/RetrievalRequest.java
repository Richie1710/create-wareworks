package dev.wareworks.core.job;

import java.util.Objects;
import java.util.UUID;

/**
 * One retrieval request ({@code docs/warehouse-system.md} §7.2): deliver {@link #requested()} items of {@link #key()} to
 * {@link #destination()}. {@link #remaining()} counts down as deliveries are confirmed; a {@link RequestQueue} drops a
 * request once nothing remains.
 * <p>
 * Immutable: progress creates a new record with the same {@link #id()}. A further request for the same key and
 * destination does not create a second record either: it grows this one ({@link #withAdded}, §7.2 "merging"), so ten
 * clicks on the same item are one request the crane serves in one trip.
 *
 * @param id          stable identity, also across restarts
 * @param key         what to deliver (exact item identity)
 * @param requested   the accepted amount: everything this request ever asked for, i.e. the asked amounts after clamping
 *                    to the available stock, including what merged requests added; at least 1
 * @param remaining   amount not delivered yet, {@code 0..requested}
 * @param destination where the items go (an output station)
 * @param kind        what the request promises (M31, issue #21): items out of the racks ({@link RequestKind#STOCK}, every
 *                    request before M31) or filled containers a fluid bay makes ({@link RequestKind#FILL}, whose
 *                    {@code key} is the filled container). Merging keys on it as well ({@link RequestQueue#add})
 * @param <K>         item key type
 * @param <D>         destination type
 */
public record RetrievalRequest<K, D>(UUID id, K key, int requested, int remaining, D destination, RequestKind kind) {
    public RetrievalRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(kind, "kind");
        if (requested < 1)
            throw new IllegalArgumentException("requested must be at least 1: " + requested);
        if (remaining < 0 || remaining > requested)
            throw new IllegalArgumentException("remaining must be within 0.." + requested + ": " + remaining);
    }

    /** A {@link RequestKind#STOCK} request: what every request was before M31, and what a save without a kind reads as. */
    public RetrievalRequest(UUID id, K key, int requested, int remaining, D destination) {
        this(id, key, requested, remaining, destination, RequestKind.STOCK);
    }

    /** Whether this request is served by fill trips rather than out of the racks (M31). */
    public boolean isFill() {
        return kind == RequestKind.FILL;
    }

    /** Amount already delivered. */
    public int delivered() {
        return requested - remaining;
    }

    /** Whether items are still missing. */
    public boolean isOpen() {
        return remaining > 0;
    }

    /** The same request with another remaining amount. */
    public RetrievalRequest<K, D> withRemaining(int newRemaining) {
        return new RetrievalRequest<>(id, key, requested, newRemaining, destination, kind);
    }

    /**
     * The same request asking for {@code fewer} items less: {@link #requested()} and {@link #remaining()} both shrink
     * by it, so {@link #delivered()} is unchanged. The inverse of {@link #withAdded}, used when a promise behind a
     * request turns out to be unreachable — a production order that timed out or was cancelled gives the request the
     * amount it will never produce back ({@code docs/warehouse-system.md} §3.5).
     *
     * @param fewer items to take off, {@code 1..remaining() - 1}. Taking off everything that remains would leave a
     *              request that asks for nothing, which cannot exist; the caller removes it from the queue instead
     *              ({@link RequestQueue#reduce})
     * @throws IllegalArgumentException if {@code fewer} is outside that range
     */
    public RetrievalRequest<K, D> withRemoved(int fewer) {
        if (fewer < 1 || fewer >= remaining)
            throw new IllegalArgumentException("fewer must be within 1.." + (remaining - 1) + ": " + fewer);
        return new RetrievalRequest<>(id, key, requested - fewer, remaining - fewer, destination, kind);
    }

    /**
     * The same request asking for {@code extra} items more: {@link #requested()} and {@link #remaining()} both grow by
     * {@code extra}, so {@link #delivered()} is unchanged. Identity and destination stay, which is what lets a
     * {@link RequestQueue} merge a repeated request into this one without losing its queue position.
     *
     * @param extra the accepted amount of the repeated request, at least 1
     * @throws IllegalArgumentException if {@code extra < 1} or the total would exceed {@link Integer#MAX_VALUE}
     */
    public RetrievalRequest<K, D> withAdded(int extra) {
        if (extra < 1)
            throw new IllegalArgumentException("extra must be at least 1: " + extra);
        long grown = (long) requested + extra;
        if (grown > Integer.MAX_VALUE)
            throw new IllegalArgumentException("requested would overflow: " + requested + " + " + extra);
        return new RetrievalRequest<>(id, key, (int) grown, remaining + extra, destination, kind);
    }
}
