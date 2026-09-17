package dev.wareworks.core.job;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.warehouse.LocationKind;

/**
 * Amounts promised to transport jobs ({@code docs/warehouse-system.md} §7.1, §7.3): capacity at targets, stock inside
 * sources and items in transit to output stations. The controller owns one ledger per aisle; it never moves items.
 * <p>
 * <b>Entries.</b> A job holds at most one reservation per {@link Reservation.Kind}. Every {@code reserve*} call replaces
 * the job's reservation of that kind (e.g. a reroute moves the capacity reservation to the new target); an amount of 0
 * releases it. {@link #releaseJob} and {@link #release} are idempotent: releasing twice, or releasing an unknown job,
 * changes nothing.
 * <p>
 * <b>Derived from jobs.</b> {@link #reservationsFor(TransportJob)} defines what a job reserves in each stage, and
 * {@link #track} applies it. {@link #restoreFrom} rebuilds the whole ledger from persisted jobs, so the ledger itself
 * does not need to be saved (a controller adopting a crane's job rebuilds its reservations, §8):
 * <ul>
 *   <li>not picked, store: {@link Reservation.Kind#CAPACITY} of the planned amount at the target;</li>
 *   <li>not picked, retrieve or supply: {@link Reservation.Kind#STOCK} of the planned amount at the source, with the
 *       request or production ingredient line it serves ({@link JobType#reservesSourceStock()});</li>
 *   <li>picked, items held, target is a delivery station (output or production,
 *       {@link LocationKind#isDeliveryTarget()}): {@link Reservation.Kind#TRANSIT} of the held amount, with the
 *       request or ingredient line (the picked items left the source, so the stock reservation ends);</li>
 *   <li>picked, items held, any other target: {@link Reservation.Kind#CAPACITY} of the held amount at the target;</li>
 *   <li>nothing held: no reservation.</li>
 * </ul>
 * <b>Double subtraction</b> (M2 note): see {@link ReservationView} and {@link #availableStock}. Requests that disappear
 * are detached with {@link #detachRequest}, so their reservations count as not backing a request.
 * <p>
 * <b>Invariant:</b> no amount or aggregate is ever negative; aggregates always equal the sum of the stored entries.
 * Queries are O(1). Pure Java, not thread-safe (server thread only).
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class ReservationLedger<K, L> implements ReservationView<K, L> {
    private final Map<UUID, EnumMap<Reservation.Kind, Reservation<K, L>>> byJob = new LinkedHashMap<>();
    private final Map<L, Map<K, Long>> capacityAt = new HashMap<>();
    private final Map<L, Long> capacityTotalAt = new HashMap<>();
    private final Map<L, Map<K, Long>> stockAt = new HashMap<>();
    private final Map<K, Long> stockByKey = new HashMap<>();
    private final Map<K, Long> stockNotBackingByKey = new HashMap<>();
    private final Map<K, Long> transitByKey = new HashMap<>();
    private final Map<K, Long> transitBackingByKey = new HashMap<>();
    private final Map<UUID, Long> committedByRequest = new HashMap<>();
    private long totalCapacity;
    private long totalStock;
    private long totalTransit;

    // --- changes -------------------------------------------------------------------------------------------------

    /**
     * Sets job {@code jobId}'s capacity reservation to {@code amount} of {@code key} at {@code location}, replacing its
     * previous capacity reservation. An amount of 0 releases it.
     *
     * @throws IllegalArgumentException if {@code amount < 0}
     */
    public void reserveCapacity(UUID jobId, L location, K key, int amount) {
        set(jobId, Reservation.Kind.CAPACITY, location, key, amount, null);
    }

    /** Sets a stock reservation that backs no request; see {@link #reserveStock(UUID, Object, Object, int, UUID)}. */
    public void reserveStock(UUID jobId, L location, K key, int amount) {
        reserveStock(jobId, location, key, amount, null);
    }

    /**
     * Sets job {@code jobId}'s stock reservation to {@code amount} of {@code key} inside {@code location}, replacing its
     * previous stock reservation. An amount of 0 releases it.
     *
     * @param requestId the open request these items are for, or {@code null}
     * @throws IllegalArgumentException if {@code amount < 0}
     */
    public void reserveStock(UUID jobId, L location, K key, int amount, @Nullable UUID requestId) {
        set(jobId, Reservation.Kind.STOCK, location, key, amount, requestId);
    }

    /**
     * Sets job {@code jobId}'s transit entry to {@code amount} held items of {@code key} on their way to the output
     * station {@code target}, replacing its previous transit entry. An amount of 0 releases it.
     *
     * @param requestId the open request these items are for, or {@code null}
     * @throws IllegalArgumentException if {@code amount < 0}
     */
    public void reserveTransit(UUID jobId, L target, K key, int amount, @Nullable UUID requestId) {
        set(jobId, Reservation.Kind.TRANSIT, target, key, amount, requestId);
    }

    /**
     * Releases job {@code jobId}'s reservation of {@code kind}.
     *
     * @return the released amount, 0 if there was none
     */
    public int release(UUID jobId, Reservation.Kind kind) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(kind, "kind");
        EnumMap<Reservation.Kind, Reservation<K, L>> entries = byJob.get(jobId);
        if (entries == null)
            return 0;
        Reservation<K, L> old = entries.remove(kind);
        if (old == null)
            return 0;
        apply(old, -1);
        if (entries.isEmpty())
            byJob.remove(jobId);
        return old.amount();
    }

    /**
     * Releases every reservation of job {@code jobId} (completed, aborted or lost). Harmless for unknown jobs.
     *
     * @return whether the job held a reservation
     */
    public boolean releaseJob(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        EnumMap<Reservation.Kind, Reservation<K, L>> entries = byJob.remove(jobId);
        if (entries == null)
            return false;
        for (Reservation<K, L> reservation : entries.values())
            apply(reservation, -1);
        return true;
    }

    /**
     * Sets every reservation of job {@code jobId} to {@code newAmount}, e.g. to release the surplus after a partial pick
     * (§7.3). An amount of 0 releases the job. Callers normally only lower amounts: raising one promises stock or space
     * that nobody validated.
     *
     * @return whether the job held a reservation
     * @throws IllegalArgumentException if {@code newAmount < 0}
     */
    public boolean adjust(UUID jobId, int newAmount) {
        Objects.requireNonNull(jobId, "jobId");
        if (newAmount < 0)
            throw new IllegalArgumentException("newAmount must not be negative: " + newAmount);
        EnumMap<Reservation.Kind, Reservation<K, L>> entries = byJob.get(jobId);
        if (entries == null)
            return false;
        if (newAmount == 0)
            return releaseJob(jobId);
        for (Reservation<K, L> reservation : List.copyOf(entries.values())) {
            if (reservation.amount() != newAmount)
                replace(entries, reservation.withAmount(newAmount));
        }
        return true;
    }

    /**
     * Detaches every reservation from request {@code requestId} (the request was cancelled or its output removed), so
     * the reserved items count as not backing a request until the job is released or re-tracked.
     *
     * @return the number of reservations changed
     */
    public int detachRequest(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        int changed = 0;
        for (EnumMap<Reservation.Kind, Reservation<K, L>> entries : byJob.values()) {
            for (Reservation<K, L> reservation : List.copyOf(entries.values())) {
                if (reservation.requestId().filter(requestId::equals).isPresent()) {
                    replace(entries, reservation.withoutRequest());
                    changed++;
                }
            }
        }
        return changed;
    }

    /** Replaces job {@code job.id()}'s reservations with {@link #reservationsFor(TransportJob) what it reserves now}. */
    public void track(TransportJob<K, L> job) {
        Objects.requireNonNull(job, "job");
        releaseJob(job.id());
        for (Reservation<K, L> reservation : reservationsFor(job))
            put(reservation);
    }

    /**
     * The reservations a job holds in its current stage (see the class documentation). At most one per kind.
     */
    public static <K, L> List<Reservation<K, L>> reservationsFor(TransportJob<K, L> job) {
        Objects.requireNonNull(job, "job");
        if (!job.picked()) {
            // Retrieve and supply both take items out of storage, so both reserve stock at their source; only a store
            // job brings items in and reserves room at its target instead.
            if (job.type().reservesSourceStock())
                return List.of(new Reservation<>(job.id(), Reservation.Kind.STOCK, job.source(), job.key(),
                        job.plannedAmount(), job.requestId()));
            return List.of(new Reservation<>(job.id(), Reservation.Kind.CAPACITY, job.target(), job.key(),
                    job.plannedAmount(), Optional.empty()));
        }
        int held = job.heldAmount();
        if (held == 0)
            return List.of();
        if (job.targetKind().isDeliveryTarget())
            return List.of(new Reservation<>(job.id(), Reservation.Kind.TRANSIT, job.target(), job.key(), held,
                    job.requestId()));
        return List.of(new Reservation<>(job.id(), Reservation.Kind.CAPACITY, job.target(), job.key(), held,
                Optional.empty()));
    }

    /**
     * Replaces the ledger with the reservations of persisted jobs. Null entries and repeated job ids are skipped.
     *
     * @return the number of jobs that hold a reservation afterwards
     */
    public int restoreFrom(Collection<? extends TransportJob<K, L>> jobs) {
        Objects.requireNonNull(jobs, "jobs");
        clear();
        Set<UUID> seen = new HashSet<>();
        for (TransportJob<K, L> job : jobs) {
            if (job == null || !seen.add(job.id()))
                continue;
            track(job);
        }
        return byJob.size();
    }

    /** Removes every reservation. */
    public void clear() {
        byJob.clear();
        capacityAt.clear();
        capacityTotalAt.clear();
        stockAt.clear();
        stockByKey.clear();
        stockNotBackingByKey.clear();
        transitByKey.clear();
        transitBackingByKey.clear();
        committedByRequest.clear();
        totalCapacity = 0;
        totalStock = 0;
        totalTransit = 0;
    }

    /** A read-only view that cannot be cast back to the ledger. */
    public ReservationView<K, L> readOnlyView() {
        return new ReadOnlyView<>(this);
    }

    // --- queries -------------------------------------------------------------------------------------------------

    @Override
    public long reservedCapacity(L location) {
        return capacityTotalAt.getOrDefault(Objects.requireNonNull(location, "location"), 0L);
    }

    @Override
    public long reservedCapacity(L location, K key) {
        return nested(capacityAt, location, key);
    }

    @Override
    public long reservedStock(L location, K key) {
        return nested(stockAt, location, key);
    }

    @Override
    public long reservedStock(K key) {
        return stockByKey.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
    }

    @Override
    public long reservedStockNotBackingRequests(K key) {
        return stockNotBackingByKey.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
    }

    @Override
    public long inTransit(K key) {
        return transitByKey.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
    }

    @Override
    public long inTransitBackingRequests(K key) {
        return transitBackingByKey.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
    }

    @Override
    public long committedToRequest(UUID requestId) {
        return committedByRequest.getOrDefault(Objects.requireNonNull(requestId, "requestId"), 0L);
    }

    @Override
    public long totalReservedCapacity() {
        return totalCapacity;
    }

    @Override
    public long totalReservedStock() {
        return totalStock;
    }

    @Override
    public long totalInTransit() {
        return totalTransit;
    }

    @Override
    public List<Reservation<K, L>> reservationsOf(UUID jobId) {
        EnumMap<Reservation.Kind, Reservation<K, L>> entries = byJob.get(Objects.requireNonNull(jobId, "jobId"));
        return entries == null ? List.of() : List.copyOf(entries.values());
    }

    @Override
    public List<Reservation<K, L>> reservations() {
        List<Reservation<K, L>> all = new ArrayList<>();
        for (EnumMap<Reservation.Kind, Reservation<K, L>> entries : byJob.values())
            all.addAll(entries.values());
        return Collections.unmodifiableList(all);
    }

    @Override
    public List<Reservation<K, L>> reservationsAt(L location) {
        Objects.requireNonNull(location, "location");
        List<Reservation<K, L>> at = new ArrayList<>();
        for (EnumMap<Reservation.Kind, Reservation<K, L>> entries : byJob.values()) {
            for (Reservation<K, L> reservation : entries.values()) {
                if (reservation.location().equals(location))
                    at.add(reservation);
            }
        }
        return Collections.unmodifiableList(at);
    }

    @Override
    public Set<UUID> jobIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byJob.keySet()));
    }

    @Override
    public boolean isEmpty() {
        return byJob.isEmpty();
    }

    /** Number of stored reservations. */
    public int size() {
        int size = 0;
        for (EnumMap<Reservation.Kind, Reservation<K, L>> entries : byJob.values())
            size += entries.size();
        return size;
    }

    @Override
    public String toString() {
        return "ReservationLedger[jobs=" + byJob.size() + ", capacity=" + totalCapacity + ", stock=" + totalStock
                + ", transit=" + totalTransit + "]";
    }

    // --- internals -----------------------------------------------------------------------------------------------

    private void set(UUID jobId, Reservation.Kind kind, L location, K key, int amount, @Nullable UUID requestId) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(key, "key");
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        if (amount == 0) {
            release(jobId, kind);
            return;
        }
        put(new Reservation<>(jobId, kind, location, key, amount, Optional.ofNullable(requestId)));
    }

    private void put(Reservation<K, L> reservation) {
        EnumMap<Reservation.Kind, Reservation<K, L>> entries = byJob.get(reservation.jobId());
        if (entries == null) {
            entries = new EnumMap<>(Reservation.Kind.class);
            byJob.put(reservation.jobId(), entries);
        }
        replace(entries, reservation);
    }

    /** Stores {@code reservation} in its job's entry map (which must exist) and updates the aggregates. */
    private void replace(EnumMap<Reservation.Kind, Reservation<K, L>> entries, Reservation<K, L> reservation) {
        Reservation<K, L> old = entries.put(reservation.kind(), reservation);
        if (old != null)
            apply(old, -1);
        apply(reservation, 1);
    }

    private void apply(Reservation<K, L> reservation, int sign) {
        long delta = (long) sign * reservation.amount();
        K key = reservation.key();
        switch (reservation.kind()) {
            case CAPACITY -> {
                addNested(capacityAt, reservation.location(), key, delta);
                add(capacityTotalAt, reservation.location(), delta);
                totalCapacity += delta;
            }
            case STOCK -> {
                addNested(stockAt, reservation.location(), key, delta);
                add(stockByKey, key, delta);
                if (reservation.backsRequest())
                    add(committedByRequest, reservation.requestId().get(), delta);
                else
                    add(stockNotBackingByKey, key, delta);
                totalStock += delta;
            }
            case TRANSIT -> {
                add(transitByKey, key, delta);
                if (reservation.backsRequest()) {
                    add(transitBackingByKey, key, delta);
                    add(committedByRequest, reservation.requestId().get(), delta);
                }
                totalTransit += delta;
            }
        }
    }

    private static <T> void add(Map<T, Long> map, T key, long delta) {
        long value = map.getOrDefault(key, 0L) + delta;
        if (value < 0)
            throw new IllegalStateException("reservation aggregate would become negative for " + key + ": " + value);
        if (value == 0)
            map.remove(key);
        else
            map.put(key, value);
    }

    private static <A, B> void addNested(Map<A, Map<B, Long>> map, A outer, B inner, long delta) {
        Map<B, Long> values = map.computeIfAbsent(outer, ignored -> new HashMap<>());
        add(values, inner, delta);
        if (values.isEmpty())
            map.remove(outer);
    }

    private static <A, B> long nested(Map<A, Map<B, Long>> map, A outer, B inner) {
        Objects.requireNonNull(outer, "location");
        Objects.requireNonNull(inner, "key");
        Map<B, Long> values = map.get(outer);
        return values == null ? 0L : values.getOrDefault(inner, 0L);
    }

    private record ReadOnlyView<K, L>(ReservationLedger<K, L> ledger) implements ReservationView<K, L> {
        @Override
        public long reservedCapacity(L location) {
            return ledger.reservedCapacity(location);
        }

        @Override
        public long reservedCapacity(L location, K key) {
            return ledger.reservedCapacity(location, key);
        }

        @Override
        public long reservedStock(L location, K key) {
            return ledger.reservedStock(location, key);
        }

        @Override
        public long reservedStock(K key) {
            return ledger.reservedStock(key);
        }

        @Override
        public long reservedStockNotBackingRequests(K key) {
            return ledger.reservedStockNotBackingRequests(key);
        }

        @Override
        public long inTransit(K key) {
            return ledger.inTransit(key);
        }

        @Override
        public long inTransitBackingRequests(K key) {
            return ledger.inTransitBackingRequests(key);
        }

        @Override
        public long committedToRequest(UUID requestId) {
            return ledger.committedToRequest(requestId);
        }

        @Override
        public long totalReservedCapacity() {
            return ledger.totalReservedCapacity();
        }

        @Override
        public long totalReservedStock() {
            return ledger.totalReservedStock();
        }

        @Override
        public long totalInTransit() {
            return ledger.totalInTransit();
        }

        @Override
        public List<Reservation<K, L>> reservationsOf(UUID jobId) {
            return ledger.reservationsOf(jobId);
        }

        @Override
        public List<Reservation<K, L>> reservations() {
            return ledger.reservations();
        }

        @Override
        public List<Reservation<K, L>> reservationsAt(L location) {
            return ledger.reservationsAt(location);
        }

        @Override
        public Set<UUID> jobIds() {
            return ledger.jobIds();
        }

        @Override
        public boolean isEmpty() {
            return ledger.isEmpty();
        }

        @Override
        public String toString() {
            return "ReadOnly" + ledger;
        }
    }
}
