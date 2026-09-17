package dev.wareworks.core.job;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * The open retrieval requests of one warehouse controller, first in, first out ({@code docs/warehouse-system.md} §7.2).
 * <p>
 * <b>Adding.</b> {@link #add} clamps the asked amount with an availability function (the controller passes the stock that
 * is not promised yet) and rejects a request when nothing is available ({@link Rejection#NOTHING_AVAILABLE}), when the
 * open request it would merge into already asks for the largest allowed amount ({@link Rejection#REQUEST_FULL}), when its
 * destination already has {@link #maxOpenRequestsPerDestination()} open requests ({@link Rejection#DESTINATION_FULL}, so
 * one destination cannot take every slot) or when {@link #maxOpenRequests()} requests are open
 * ({@link Rejection#QUEUE_FULL}), checked in this order. The accepted amount becomes the request's
 * {@link RetrievalRequest#requested()} and {@link RetrievalRequest#remaining()}.
 * <p>
 * <b>Merging</b> ({@code docs/warehouse-system.md} §7.2, ADR-020). A request for a key a destination already has an open
 * request for does <b>not</b> queue a second request: it grows that one ({@link RetrievalRequest#withAdded}), so clicking
 * the same item ten times is one request the crane serves in one trip. Only the oldest matching request is grown
 * ({@link #openFor}); a merge takes no new queue slot, so neither cap can refuse it, while the amount is clamped like
 * any other: to what is available and to {@code maxRemainingPerRequest}, applied to the <b>merged</b> remaining amount.
 * <p>
 * <b>Fairness</b> (§7.2). What a merge does to the queue position depends on whether the request was served already:
 * a request that has received nothing yet keeps its position, so a burst of clicks stays where the first click queued
 * it; a request that already received items moves <b>behind</b> every request that is open now, so a destination that
 * keeps topping its request up (a redstone pulse clock) takes its turn again instead of holding the head of the queue
 * for ever and starving the other destinations. Requests for another key or destination are never moved, and
 * {@link #deliver} never moves one.
 * <p>
 * <b>Progress.</b> {@link #deliver} counts confirmed deliveries down and drops a request when nothing remains;
 * {@link #cancel} and {@link #cancelFor} remove requests. Every stored request is open ({@code remaining > 0}).
 * <p>
 * <b>Persistence.</b> {@link #requests()} lists the requests in queue order and {@link #restore} rebuilds the queue from
 * such a list (invalid and duplicate entries are skipped; a restored queue may exceed a lowered maximum, it then only
 * rejects new requests until enough are done).
 * <p>
 * The queue never moves items and holds no reservations; it only records what is owed. Pure Java, not thread-safe
 * (server thread only).
 *
 * @param <K> item key type
 * @param <D> destination type
 */
public final class RequestQueue<K, D> {
    /** Why {@link #add} refused a request. */
    public enum Rejection {
        /** {@link #maxOpenRequests()} requests are open already. */
        QUEUE_FULL,
        /** The destination has {@link #maxOpenRequestsPerDestination()} open requests already. */
        DESTINATION_FULL,
        /** The availability function allowed nothing. */
        NOTHING_AVAILABLE,
        /**
         * The open request for this key and destination already waits for the largest amount one request may ask for
         * ({@code maxRemainingPerRequest}), so merging into it could not add anything.
         */
        REQUEST_FULL
    }

    /**
     * Result of {@link #add}: exactly one of the accepted request and the rejection is present.
     *
     * @param request   the accepted request, i.e. the new one or the merged one with its grown amounts
     * @param rejection why the request was refused
     * @param accepted  how many items this call added (0 for a refusal); below the asked amount when it was clamped
     * @param merged    whether the items were added to a request that was already open for this key and destination
     * @param <K>       item key type
     * @param <D>       destination type
     */
    public record AddResult<K, D>(Optional<RetrievalRequest<K, D>> request, Optional<Rejection> rejection, int accepted,
                                  boolean merged) {
        public AddResult {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(rejection, "rejection");
            if (request.isPresent() == rejection.isPresent())
                throw new IllegalArgumentException("exactly one of request and rejection must be present");
            if (request.isPresent() ? accepted < 1 : accepted != 0)
                throw new IllegalArgumentException("an accepted request adds at least 1 item, a refusal none: "
                        + accepted);
            if (merged && request.isEmpty())
                throw new IllegalArgumentException("a refusal never merges");
        }

        public static <K, D> AddResult<K, D> accepted(RetrievalRequest<K, D> request, int accepted, boolean merged) {
            return new AddResult<>(Optional.of(request), Optional.empty(), accepted, merged);
        }

        public static <K, D> AddResult<K, D> rejected(Rejection rejection) {
            return new AddResult<>(Optional.empty(), Optional.of(rejection), 0, false);
        }

        public boolean isAccepted() {
            return request.isPresent();
        }
    }

    /** Smallest allowed value of {@link #maxOpenRequests()}. */
    public static final int MIN_OPEN_REQUESTS = 1;
    /** {@code maxRemainingPerRequest} of a caller that caps a single request only by the available stock. */
    public static final int NO_AMOUNT_LIMIT = Integer.MAX_VALUE;

    private final Map<UUID, RetrievalRequest<K, D>> open = new LinkedHashMap<>();
    private final Supplier<UUID> idFactory;
    private int maxOpenRequests;
    private int maxOpenRequestsPerDestination = Integer.MAX_VALUE;

    /** A queue with random request ids and no per-destination cap. */
    public RequestQueue(int maxOpenRequests) {
        this(maxOpenRequests, UUID::randomUUID);
    }

    /**
     * A queue without per-destination cap ({@link #setMaxOpenRequestsPerDestination} sets one).
     *
     * @param maxOpenRequests maximum number of open requests (at least {@value #MIN_OPEN_REQUESTS})
     * @param idFactory       creates the id of every accepted request (e.g. deterministic ids in tests)
     */
    public RequestQueue(int maxOpenRequests, Supplier<UUID> idFactory) {
        setMaxOpenRequests(maxOpenRequests);
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory");
    }

    public int maxOpenRequests() {
        return maxOpenRequests;
    }

    /** Changes the cap (e.g. after a config change). Open requests above a lowered cap are kept. */
    public void setMaxOpenRequests(int maxOpenRequests) {
        if (maxOpenRequests < MIN_OPEN_REQUESTS)
            throw new IllegalArgumentException("maxOpenRequests must be at least " + MIN_OPEN_REQUESTS + ": "
                    + maxOpenRequests);
        this.maxOpenRequests = maxOpenRequests;
    }

    /** Maximum number of open requests per destination ({@link Integer#MAX_VALUE} without a cap). */
    public int maxOpenRequestsPerDestination() {
        return maxOpenRequestsPerDestination;
    }

    /** Changes the per-destination cap. Open requests above a lowered cap are kept. */
    public void setMaxOpenRequestsPerDestination(int maxOpenRequestsPerDestination) {
        if (maxOpenRequestsPerDestination < MIN_OPEN_REQUESTS)
            throw new IllegalArgumentException("maxOpenRequestsPerDestination must be at least " + MIN_OPEN_REQUESTS
                    + ": " + maxOpenRequestsPerDestination);
        this.maxOpenRequestsPerDestination = maxOpenRequestsPerDestination;
    }

    // --- changes -------------------------------------------------------------------------------------------------

    /**
     * Adds a request for up to {@code requested} items of {@code key} with no cap of its own, i.e. clamped only to
     * {@code available.applyAsLong(key)}; see {@link #add(Object, int, Object, ToLongFunction, int)}.
     */
    public AddResult<K, D> add(K key, int requested, D destination, ToLongFunction<? super K> available) {
        return add(key, requested, destination, available, NO_AMOUNT_LIMIT);
    }

    /**
     * Adds a request for up to {@code requested} items of {@code key}, clamped to {@code available.applyAsLong(key)} and
     * to {@code maxRemainingPerRequest}.
     * <p>
     * <b>Merging.</b> When {@code destination} already has an open request for {@code key}, the accepted amount is added
     * to that request instead of queueing a second one: it keeps its id, and its
     * {@link RetrievalRequest#requested()} and {@link RetrievalRequest#remaining()} grow. A merge takes no queue slot, so
     * neither cap is consulted; what is clamped is the <b>merged</b> remaining amount, never the increment alone. The
     * grown request keeps its queue position while nothing has been delivered to it and moves to the back once something
     * has ("Fairness" above).
     * <p>
     * Checked in this order: the availability ({@link Rejection#NOTHING_AVAILABLE}, so a request for something that is
     * not available reports that even when the queue is full), the room left in the request this one would merge into
     * ({@link Rejection#REQUEST_FULL}), the destination's cap ({@link Rejection#DESTINATION_FULL}) and the queue's cap
     * ({@link Rejection#QUEUE_FULL}).
     *
     * @param requested              asked amount, at least 1
     * @param available              how many items of a key can still be promised; negative values count as 0. It must
     *                               already account for what the open requests promise (the controller's
     *                               {@code availableStock}), which is what makes the clamp apply to the merged total
     * @param maxRemainingPerRequest largest amount one request may wait for, at least 1
     *                               ({@link #NO_AMOUNT_LIMIT} for no cap of its own)
     * @throws IllegalArgumentException if {@code requested < 1} or {@code maxRemainingPerRequest < 1}
     * @throws IllegalStateException    if the id factory returns an id that is already in use
     */
    public AddResult<K, D> add(K key, int requested, D destination, ToLongFunction<? super K> available,
            int maxRemainingPerRequest) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(available, "available");
        if (requested < 1)
            throw new IllegalArgumentException("requested must be at least 1: " + requested);
        if (maxRemainingPerRequest < 1)
            throw new IllegalArgumentException("maxRemainingPerRequest must be at least 1: " + maxRemainingPerRequest);
        long grantable = Math.min(requested, Math.max(0L, available.applyAsLong(key)));
        if (grantable == 0)
            return AddResult.rejected(Rejection.NOTHING_AVAILABLE);
        Optional<RetrievalRequest<K, D>> mergeInto = openFor(destination, key);
        long headroom = mergeInto.map(request -> Math.min((long) maxRemainingPerRequest - request.remaining(),
                (long) Integer.MAX_VALUE - request.requested())).orElse((long) maxRemainingPerRequest);
        if (headroom <= 0)
            return AddResult.rejected(Rejection.REQUEST_FULL);
        int amount = (int) Math.min(grantable, headroom);
        if (mergeInto.isPresent()) {
            RetrievalRequest<K, D> merged = mergeInto.get().withAdded(amount);
            // Replacing a key keeps its queue position, removing it first moves the request to the back. A request that
            // was never served keeps its turn (a burst of clicks belongs where the first click queued it); one that was
            // already served yields, so topping a request up cannot hold the head of the queue for ever (§7.2).
            if (merged.delivered() > 0)
                open.remove(merged.id());
            open.put(merged.id(), merged);
            return AddResult.accepted(merged, amount, true);
        }
        if (isFullFor(destination))
            return AddResult.rejected(Rejection.DESTINATION_FULL);
        if (isFull())
            return AddResult.rejected(Rejection.QUEUE_FULL);
        UUID id = Objects.requireNonNull(idFactory.get(), "id factory returned null");
        if (open.containsKey(id))
            throw new IllegalStateException("duplicate request id " + id);
        RetrievalRequest<K, D> request = new RetrievalRequest<>(id, key, amount, amount, destination);
        open.put(id, request);
        return AddResult.accepted(request, amount, false);
    }

    /**
     * Confirms that {@code amount} items of request {@code id} were delivered. The request is removed when nothing
     * remains; deliveries beyond the remaining amount are not counted.
     *
     * @return the amount counted towards the request (0 for an unknown id)
     * @throws IllegalArgumentException if {@code amount < 0}
     */
    public int deliver(UUID id, int amount) {
        Objects.requireNonNull(id, "id");
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        RetrievalRequest<K, D> request = open.get(id);
        if (request == null || amount == 0)
            return 0;
        int counted = Math.min(amount, request.remaining());
        int remaining = request.remaining() - counted;
        if (remaining == 0)
            open.remove(id);
        else
            open.put(id, request.withRemaining(remaining)); // replacing a key keeps its queue position
        return counted;
    }

    /**
     * Takes {@code amount} items off request {@code id} without counting them as delivered: the promise behind them
     * turned out to be unreachable ({@code docs/warehouse-system.md} §3.5 — a production order that timed out or was
     * cancelled). The request keeps its queue position; if nothing remains it is removed.
     * <p>
     * This is deliberately <b>not</b> {@link #deliver}: nothing arrived, so {@link RetrievalRequest#delivered()} must
     * not grow, or the station's "delivered so far" would count items that never existed.
     *
     * @return the request as it stands now, or empty when it was removed or the id is unknown
     * @throws IllegalArgumentException if {@code amount < 0}
     */
    public Optional<RetrievalRequest<K, D>> reduce(UUID id, int amount) {
        Objects.requireNonNull(id, "id");
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        RetrievalRequest<K, D> request = open.get(id);
        if (request == null)
            return Optional.empty();
        if (amount == 0)
            return Optional.of(request);
        if (amount >= request.remaining()) {
            open.remove(id);
            return Optional.empty();
        }
        RetrievalRequest<K, D> reduced = request.withRemoved(amount);
        open.put(id, reduced); // replacing a key keeps its queue position
        return Optional.of(reduced);
    }

    /** Removes request {@code id}; returns it, or empty if it was not open. */
    public Optional<RetrievalRequest<K, D>> cancel(UUID id) {
        return Optional.ofNullable(open.remove(Objects.requireNonNull(id, "id")));
    }

    /** Removes every request for {@code destination} (e.g. the output station is gone); returns them in queue order. */
    public List<RetrievalRequest<K, D>> cancelFor(D destination) {
        Objects.requireNonNull(destination, "destination");
        List<RetrievalRequest<K, D>> removed = new ArrayList<>();
        Iterator<RetrievalRequest<K, D>> iterator = open.values().iterator();
        while (iterator.hasNext()) {
            RetrievalRequest<K, D> request = iterator.next();
            if (request.destination().equals(destination)) {
                removed.add(request);
                iterator.remove();
            }
        }
        return Collections.unmodifiableList(removed);
    }

    /** Removes every request. */
    public void clear() {
        open.clear();
    }

    /**
     * Replaces the queue with persisted requests, in the given order. Requests without remaining items and repeated ids
     * are skipped. Neither cap is applied.
     *
     * @return the number of restored requests
     */
    public int restore(Collection<RetrievalRequest<K, D>> saved) {
        Objects.requireNonNull(saved, "saved");
        open.clear();
        for (RetrievalRequest<K, D> request : saved) {
            if (request == null || !request.isOpen() || open.containsKey(request.id()))
                continue;
            open.put(request.id(), request);
        }
        return open.size();
    }

    // --- queries -------------------------------------------------------------------------------------------------

    /** All open requests in queue order (unmodifiable copy). */
    public List<RetrievalRequest<K, D>> requests() {
        return List.copyOf(open.values());
    }

    public Optional<RetrievalRequest<K, D>> get(UUID id) {
        return Optional.ofNullable(open.get(Objects.requireNonNull(id, "id")));
    }

    /** The oldest open request, which is served first. */
    public Optional<RetrievalRequest<K, D>> oldestOpen() {
        Iterator<RetrievalRequest<K, D>> iterator = open.values().iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    /** The open requests for {@code destination}, in queue order (unmodifiable copy). */
    public List<RetrievalRequest<K, D>> requestsFor(D destination) {
        Objects.requireNonNull(destination, "destination");
        List<RetrievalRequest<K, D>> result = new ArrayList<>();
        for (RetrievalRequest<K, D> request : open.values()) {
            if (request.destination().equals(destination))
                result.add(request);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * The open request a further request for {@code key} at {@code destination} would be merged into: the oldest one
     * with that key and destination, or empty if there is none.
     */
    public Optional<RetrievalRequest<K, D>> openFor(D destination, K key) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(key, "key");
        for (RetrievalRequest<K, D> request : open.values()) {
            if (request.destination().equals(destination) && request.key().equals(key))
                return Optional.of(request);
        }
        return Optional.empty();
    }

    /** Number of open requests for {@code destination}. */
    public int openCountFor(D destination) {
        Objects.requireNonNull(destination, "destination");
        int count = 0;
        for (RetrievalRequest<K, D> request : open.values()) {
            if (request.destination().equals(destination))
                count++;
        }
        return count;
    }

    /** Whether {@link #add} would reject a request for {@code destination} because it has too many open requests. */
    public boolean isFullFor(D destination) {
        return openCountFor(destination) >= maxOpenRequestsPerDestination;
    }

    /** Sum of the remaining amounts of the requests for {@code destination}. */
    public long remainingFor(D destination) {
        Objects.requireNonNull(destination, "destination");
        long sum = 0;
        for (RetrievalRequest<K, D> request : open.values()) {
            if (request.destination().equals(destination))
                sum += request.remaining();
        }
        return sum;
    }

    /**
     * The remaining amounts of every key that has an open request, in <b>one</b> pass over the queue: what each key is
     * already promised to.
     * <p>
     * For callers that need the promised amount of many keys at once (a warehouse terminal's stock snapshot,
     * {@code docs/warehouse-system.md} §3.4.1), which would otherwise scan the whole queue once per key.
     *
     * @return an unmodifiable map; a key without an open request is absent, never mapped to 0
     */
    public Map<K, Long> remainingByKey() {
        Map<K, Long> byKey = new HashMap<>();
        for (RetrievalRequest<K, D> request : open.values())
            byKey.merge(request.key(), (long) request.remaining(), Long::sum);
        return Collections.unmodifiableMap(byKey);
    }

    /** Sum of the remaining amounts of the requests for {@code key} (the amount of that key already promised). */
    public long remainingOf(K key) {
        Objects.requireNonNull(key, "key");
        long sum = 0;
        for (RetrievalRequest<K, D> request : open.values()) {
            if (request.key().equals(key))
                sum += request.remaining();
        }
        return sum;
    }

    public int openCount() {
        return open.size();
    }

    public boolean isEmpty() {
        return open.isEmpty();
    }

    /** Whether {@link #add} would reject a request because too many are open. */
    public boolean isFull() {
        return open.size() >= maxOpenRequests;
    }

    @Override
    public String toString() {
        return "RequestQueue[open=" + open.size() + "/" + maxOpenRequests + ", perDestination="
                + maxOpenRequestsPerDestination + "]";
    }
}
