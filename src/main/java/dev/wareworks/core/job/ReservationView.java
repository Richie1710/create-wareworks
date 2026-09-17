package dev.wareworks.core.job;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only queries of a {@link ReservationLedger}, e.g. for the {@link JobPlanner} and goggles.
 * <p>
 * <b>Two stock views</b> ({@code docs/warehouse-system.md} §7.2, M2 note): the controller's available stock already
 * subtracts the remaining amounts of open requests. Reservations that back such a request are part of that remaining
 * amount, so they must not be subtracted a second time:
 * <ul>
 *   <li>{@link #reservedStock(Object, Object)} / {@link #reservedStock(Object)}: every stock reservation. Planning
 *       subtracts these from what a location can give, because the items are promised to a job.</li>
 *   <li>{@link #reservedStockNotBackingRequests(Object)}: only reservations without an open request. Availability
 *       subtracts these; {@link #availableStock} combines both views correctly.</li>
 * </ul>
 *
 * @param <K> item key type
 * @param <L> location type
 */
public interface ReservationView<K, L> {
    /** Capacity reserved at {@code location} over all keys (conservative: space for one key may use shared slots). */
    long reservedCapacity(L location);

    /** Capacity reserved at {@code location} for {@code key}. */
    long reservedCapacity(L location, K key);

    /** Stock of {@code key} reserved inside {@code location}. */
    long reservedStock(L location, K key);

    /** Stock of {@code key} reserved over all locations. */
    long reservedStock(K key);

    /** Stock of {@code key} reserved by jobs that do not back an open request. */
    long reservedStockNotBackingRequests(K key);

    /** Items of {@code key} in handling heads on their way to output stations. */
    long inTransit(K key);

    /** Items of {@code key} in handling heads on their way to output stations for an open request. */
    long inTransitBackingRequests(K key);

    /** Amount already covered for request {@code requestId}: reserved stock plus items in transit for it. */
    long committedToRequest(UUID requestId);

    long totalReservedCapacity();

    long totalReservedStock();

    long totalInTransit();

    /** The reservations of one job (unmodifiable, in {@link Reservation.Kind} order); empty if it has none. */
    List<Reservation<K, L>> reservationsOf(UUID jobId);

    /** Every reservation, grouped by job in insertion order (unmodifiable copy). */
    List<Reservation<K, L>> reservations();

    /**
     * The reservations at {@code location}, grouped by job in insertion order (unmodifiable copy): capacity reserved at a
     * target, stock reserved inside a source, items in transit to an output station. O(number of reservations), for
     * goggles and diagnostics; the planner uses the O(1) aggregates.
     */
    List<Reservation<K, L>> reservationsAt(L location);

    /** The jobs holding at least one reservation (unmodifiable copy, insertion order). */
    Set<UUID> jobIds();

    /** Whether no reservation exists. */
    boolean isEmpty();

    /**
     * Stock of {@code key} that a new request may still claim, combining both views without double subtraction:
     * <pre>
     * indexed − reservedStockNotBackingRequests − max(0, openRequestRemaining − inTransitBackingRequests)
     * </pre>
     * Stock reservations that back a request are already inside {@code openRequestRemaining}. Items of a request that
     * are in a handling head are inside the remaining amount but no longer in the indexed count, so they are not owed
     * from storage any more. Never negative.
     *
     * @param indexedCount         the stock index count of {@code key}
     * @param openRequestRemaining sum of the remaining amounts of the open requests for {@code key}
     */
    default long availableStock(K key, long indexedCount, long openRequestRemaining) {
        Objects.requireNonNull(key, "key");
        long owedFromStorage = Math.max(0L, openRequestRemaining - inTransitBackingRequests(key));
        return Math.max(0L, indexedCount - reservedStockNotBackingRequests(key) - owedFromStorage);
    }
}
