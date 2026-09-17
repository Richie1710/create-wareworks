package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.warehouse.LocationKind;

class ReservationLedgerTest {
    private static final String DIAMOND = "diamond";
    private static final String IRON = "iron";
    private static final String A = "chest-a";
    private static final String B = "chest-b";
    private static final String IN = "input";
    private static final String OUT = "output";
    private static final UUID JOB1 = id(1);
    private static final UUID JOB2 = id(2);
    private static final UUID JOB3 = id(3);
    private static final UUID REQ1 = id(101);
    private static final UUID REQ2 = id(102);

    private final ReservationLedger<String, String> ledger = new ReservationLedger<>();

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static Reservation<String, String> reservation(UUID job, Reservation.Kind kind, String location, String key,
            int amount, UUID request) {
        return new Reservation<>(job, kind, location, key, amount, Optional.ofNullable(request));
    }

    @Test
    void emptyLedger() {
        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.size());
        assertEquals(0, ledger.reservedCapacity(A));
        assertEquals(0, ledger.reservedCapacity(A, DIAMOND));
        assertEquals(0, ledger.reservedStock(A, DIAMOND));
        assertEquals(0, ledger.reservedStock(DIAMOND));
        assertEquals(0, ledger.reservedStockNotBackingRequests(DIAMOND));
        assertEquals(0, ledger.inTransit(DIAMOND));
        assertEquals(0, ledger.committedToRequest(REQ1));
        assertEquals(List.of(), ledger.reservations());
        assertEquals(List.of(), ledger.reservationsOf(JOB1));
        assertEquals(Set.of(), ledger.jobIds());
    }

    @Test
    void capacityAggregatesPerLocationAndKey() {
        ledger.reserveCapacity(JOB1, A, DIAMOND, 10);
        ledger.reserveCapacity(JOB2, A, IRON, 5);
        ledger.reserveCapacity(JOB3, B, DIAMOND, 7);
        assertEquals(15, ledger.reservedCapacity(A));
        assertEquals(10, ledger.reservedCapacity(A, DIAMOND));
        assertEquals(5, ledger.reservedCapacity(A, IRON));
        assertEquals(7, ledger.reservedCapacity(B));
        assertEquals(22, ledger.totalReservedCapacity());
        assertEquals(0, ledger.totalReservedStock());
        assertEquals(List.of(JOB1, JOB2, JOB3), List.copyOf(ledger.jobIds()));
    }

    @Test
    void reservationsAtALocation() {
        ledger.reserveCapacity(JOB1, A, DIAMOND, 10);
        ledger.reserveStock(JOB2, A, IRON, 4, REQ1);
        ledger.reserveCapacity(JOB3, B, IRON, 7);
        ledger.reserveTransit(JOB3, OUT, IRON, 3, REQ2);
        assertEquals(List.of(reservation(JOB1, Reservation.Kind.CAPACITY, A, DIAMOND, 10, null),
                reservation(JOB2, Reservation.Kind.STOCK, A, IRON, 4, REQ1)), ledger.reservationsAt(A));
        assertEquals(List.of(reservation(JOB3, Reservation.Kind.CAPACITY, B, IRON, 7, null)), ledger.reservationsAt(B));
        assertEquals(List.of(reservation(JOB3, Reservation.Kind.TRANSIT, OUT, IRON, 3, REQ2)),
                ledger.readOnlyView().reservationsAt(OUT), "the read-only view delegates");
        assertEquals(List.of(), ledger.reservationsAt(IN));
        ledger.releaseJob(JOB1);
        assertEquals(List.of(reservation(JOB2, Reservation.Kind.STOCK, A, IRON, 4, REQ1)), ledger.reservationsAt(A));
        assertThrows(UnsupportedOperationException.class, () -> ledger.reservationsAt(A).clear());
    }

    @Test
    void reservingReplacesTheReservationOfTheSameKind() {
        ledger.reserveCapacity(JOB1, A, DIAMOND, 10);
        ledger.reserveCapacity(JOB1, B, DIAMOND, 4);
        assertEquals(0, ledger.reservedCapacity(A), "the old target is released");
        assertEquals(4, ledger.reservedCapacity(B));
        ledger.reserveStock(JOB1, A, DIAMOND, 3);
        assertEquals(2, ledger.size());
        assertEquals(List.of(reservation(JOB1, Reservation.Kind.CAPACITY, B, DIAMOND, 4, null),
                reservation(JOB1, Reservation.Kind.STOCK, A, DIAMOND, 3, null)), ledger.reservationsOf(JOB1));
        ledger.reserveCapacity(JOB1, B, DIAMOND, 0);
        assertEquals(0, ledger.reservedCapacity(B), "an amount of 0 releases");
        assertEquals(3, ledger.reservedStock(DIAMOND));
    }

    @Test
    void releasingTwiceIsHarmless() {
        ledger.reserveStock(JOB1, A, DIAMOND, 8, REQ1);
        ledger.reserveCapacity(JOB1, B, DIAMOND, 8);
        ledger.reserveCapacity(JOB2, B, IRON, 2);
        assertTrue(ledger.releaseJob(JOB1));
        assertFalse(ledger.releaseJob(JOB1));
        assertFalse(ledger.releaseJob(id(999)));
        assertEquals(0, ledger.release(JOB1, Reservation.Kind.STOCK));
        assertEquals(2, ledger.release(JOB2, Reservation.Kind.CAPACITY));
        assertEquals(0, ledger.release(JOB2, Reservation.Kind.CAPACITY));
        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.totalReservedCapacity());
        assertEquals(0, ledger.totalReservedStock());
        assertEquals(0, ledger.committedToRequest(REQ1));
    }

    @Test
    void adjustSetsEveryReservationOfTheJob() {
        ledger.reserveCapacity(JOB1, A, DIAMOND, 32);
        ledger.reserveStock(JOB1, B, DIAMOND, 32, REQ1);
        assertTrue(ledger.adjust(JOB1, 20));
        assertEquals(20, ledger.reservedCapacity(A));
        assertEquals(20, ledger.reservedStock(B, DIAMOND));
        assertEquals(20, ledger.committedToRequest(REQ1));
        assertTrue(ledger.adjust(JOB1, 0));
        assertTrue(ledger.isEmpty());
        assertFalse(ledger.adjust(JOB1, 5), "unknown jobs are not created");
        assertThrows(IllegalArgumentException.class, () -> ledger.adjust(JOB1, -1));
    }

    @Test
    void invalidReservationsAreRejectedWithoutChanges() {
        assertThrows(IllegalArgumentException.class, () -> ledger.reserveCapacity(JOB1, A, DIAMOND, -1));
        assertThrows(NullPointerException.class, () -> ledger.reserveStock(JOB1, null, DIAMOND, 1));
        assertTrue(ledger.isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> reservation(JOB1, Reservation.Kind.CAPACITY, A, DIAMOND, 1, REQ1));
        assertThrows(IllegalArgumentException.class, () -> reservation(JOB1, Reservation.Kind.STOCK, A, DIAMOND, 0, null));
    }

    @Test
    void stockViewsSeparateRequestBackedReservations() {
        ledger.reserveStock(JOB1, A, DIAMOND, 10, REQ1);
        ledger.reserveStock(JOB2, B, DIAMOND, 4);
        ledger.reserveTransit(JOB3, OUT, DIAMOND, 6, REQ2);
        assertEquals(14, ledger.reservedStock(DIAMOND));
        assertEquals(10, ledger.reservedStock(A, DIAMOND));
        assertEquals(4, ledger.reservedStockNotBackingRequests(DIAMOND));
        assertEquals(6, ledger.inTransit(DIAMOND));
        assertEquals(6, ledger.inTransitBackingRequests(DIAMOND));
        assertEquals(10, ledger.committedToRequest(REQ1));
        assertEquals(6, ledger.committedToRequest(REQ2));
        assertEquals(6, ledger.totalInTransit());
    }

    /** M2 note: a reservation backing an open request is inside the request's remaining amount already. */
    @Test
    void availableStockNeverSubtractsARequestTwice() {
        long indexed = 64;
        assertEquals(32, ledger.availableStock(DIAMOND, indexed, 32), "the request alone promises 32");

        TransportJob<String, String> job = TransportJob.retrieve(JOB1, A, OUT, DIAMOND, 32, REQ1);
        ledger.track(job);
        assertEquals(32, ledger.reservedStock(DIAMOND));
        assertEquals(32, ledger.availableStock(DIAMOND, indexed, 32),
                "a naive index - reserved - remaining would report 0 here");

        job = job.withPicked(32);
        ledger.track(job);
        indexed = 32; // the source was re-snapshotted after the pick
        assertEquals(32, ledger.availableStock(DIAMOND, indexed, 32), "items in the head are not owed from storage");

        job = job.plusDelivered(32);
        ledger.track(job);
        assertEquals(32, ledger.availableStock(DIAMOND, indexed, 0), "request finished");

        ledger.reserveStock(JOB2, A, DIAMOND, 10);
        assertEquals(22, ledger.availableStock(DIAMOND, indexed, 0), "reservations without request are subtracted");
        assertEquals(0, ledger.availableStock(DIAMOND, 5, 100), "never negative");
    }

    @Test
    void partialPickKeepsAvailabilityCorrect() {
        TransportJob<String, String> job = TransportJob.retrieve(JOB1, A, OUT, DIAMOND, 32, REQ1);
        ledger.track(job);
        assertEquals(32, ledger.availableStock(DIAMOND, 64, 32));
        job = job.withPicked(20);
        ledger.track(job);
        assertEquals(20, ledger.committedToRequest(REQ1));
        assertEquals(0, ledger.reservedStock(DIAMOND));
        assertEquals(32, ledger.availableStock(DIAMOND, 44, 32));
        job = job.plusDelivered(20);
        ledger.track(job);
        assertEquals(32, ledger.availableStock(DIAMOND, 44, 12));
        assertTrue(ledger.isEmpty());
    }

    @Test
    void trackFollowsTheStoreLifecycleAndReroutes() {
        TransportJob<String, String> job = TransportJob.store(JOB2, IN, A, IRON, 64);
        ledger.track(job);
        assertEquals(List.of(reservation(JOB2, Reservation.Kind.CAPACITY, A, IRON, 64, null)), ledger.reservationsOf(JOB2));
        job = job.withPicked(40);
        ledger.track(job);
        assertEquals(40, ledger.reservedCapacity(A));
        job = job.plusDelivered(30);
        ledger.track(job);
        assertEquals(10, ledger.reservedCapacity(A));
        job = job.withTarget(B, LocationKind.STORAGE);
        ledger.track(job);
        assertEquals(0, ledger.reservedCapacity(A));
        assertEquals(10, ledger.reservedCapacity(B));
        job = job.withTarget(IN, LocationKind.INPUT);
        ledger.track(job);
        assertEquals(10, ledger.reservedCapacity(IN));
        ledger.track(job.plusDelivered(10));
        assertTrue(ledger.isEmpty());
    }

    @Test
    void retrieveLeftoversRoutedToStorageReserveCapacity() {
        TransportJob<String, String> job = TransportJob.retrieve(JOB1, A, OUT, DIAMOND, 16, REQ1).withPicked(16)
                .withTarget(B, LocationKind.STORAGE).withoutRequest();
        ledger.track(job);
        assertEquals(List.of(reservation(JOB1, Reservation.Kind.CAPACITY, B, DIAMOND, 16, null)),
                ledger.reservationsOf(JOB1));
        assertEquals(0, ledger.inTransit(DIAMOND));
        assertEquals(0, ledger.committedToRequest(REQ1));
    }

    @Test
    void detachRequestTurnsReservationsIntoUnbackedOnes() {
        ledger.reserveStock(JOB1, A, DIAMOND, 10, REQ1);
        ledger.reserveTransit(JOB2, OUT, DIAMOND, 5, REQ1);
        ledger.reserveStock(JOB3, B, DIAMOND, 3, REQ2);
        assertEquals(2, ledger.detachRequest(REQ1));
        assertEquals(0, ledger.committedToRequest(REQ1));
        assertEquals(3, ledger.committedToRequest(REQ2));
        assertEquals(10, ledger.reservedStockNotBackingRequests(DIAMOND));
        assertEquals(0, ledger.inTransitBackingRequests(DIAMOND));
        assertEquals(5, ledger.inTransit(DIAMOND));
        assertEquals(90, ledger.availableStock(DIAMOND, 100, 0));
        assertEquals(0, ledger.detachRequest(REQ1));
        assertTrue(ledger.releaseJob(JOB1), "an aborted job releases its detached reservation");
        assertEquals(0, ledger.reservedStockNotBackingRequests(DIAMOND));
    }

    @Test
    void restoreFromJobsRebuildsEverything() {
        ledger.reserveCapacity(id(99), B, IRON, 1);
        TransportJob<String, String> retrieve = TransportJob.retrieve(JOB1, A, OUT, DIAMOND, 12, REQ1);
        TransportJob<String, String> store = TransportJob.store(JOB2, IN, B, IRON, 30).withPicked(10);
        TransportJob<String, String> finished = TransportJob.store(JOB3, IN, B, IRON, 5).withPicked(5).plusDelivered(5);
        TransportJob<String, String> duplicate = TransportJob.retrieve(JOB1, B, OUT, DIAMOND, 1, null);
        assertEquals(2, ledger.restoreFrom(Arrays.asList(retrieve, null, store, finished, duplicate)));
        assertEquals(List.of(reservation(JOB1, Reservation.Kind.STOCK, A, DIAMOND, 12, REQ1)),
                ledger.reservationsOf(JOB1), "the first entry of a repeated id wins");
        assertEquals(10, ledger.reservedCapacity(B));
        assertEquals(List.of(), ledger.reservationsOf(JOB3));
        assertEquals(List.of(), ledger.reservationsOf(id(99)), "restore replaces the old content");
    }

    @Test
    void readOnlyViewReflectsTheLedger() {
        ReservationView<String, String> view = ledger.readOnlyView();
        assertFalse(view instanceof ReservationLedger);
        ledger.reserveStock(JOB1, A, DIAMOND, 3, REQ1);
        assertEquals(3, view.reservedStock(A, DIAMOND));
        assertEquals(3, view.committedToRequest(REQ1));
        assertEquals(ledger.reservations(), view.reservations());
        assertEquals(32, view.availableStock(DIAMOND, 35, 3) + 0, "the default method works through the view");
    }

    @Test
    void randomOperationsKeepAggregatesConsistent() {
        Random random = new Random(20260915L);
        List<String> locations = List.of(A, B, IN, OUT);
        List<String> keys = List.of(DIAMOND, IRON);
        List<UUID> requests = List.of(REQ1, REQ2);
        for (int step = 0; step < 20_000; step++) {
            UUID job = id(random.nextInt(12));
            String location = locations.get(random.nextInt(locations.size()));
            String key = keys.get(random.nextInt(keys.size()));
            UUID request = random.nextBoolean() ? requests.get(random.nextInt(requests.size())) : null;
            int amount = random.nextInt(40);
            switch (random.nextInt(8)) {
                case 0 -> ledger.reserveCapacity(job, location, key, amount);
                case 1 -> ledger.reserveStock(job, location, key, amount, request);
                case 2 -> ledger.reserveTransit(job, location, key, amount, request);
                case 3 -> ledger.releaseJob(job);
                case 4 -> ledger.release(job, Reservation.Kind.values()[random.nextInt(Reservation.Kind.values().length)]);
                case 5 -> ledger.adjust(job, amount);
                case 6 -> ledger.detachRequest(requests.get(random.nextInt(requests.size())));
                default -> {
                    if (random.nextInt(50) == 0)
                        ledger.clear();
                }
            }
            if (step % 97 == 0)
                assertMatchesRecomputation(locations, keys, requests);
        }
        assertMatchesRecomputation(locations, keys, requests);
    }

    private void assertMatchesRecomputation(List<String> locations, List<String> keys, List<UUID> requests) {
        Map<String, Long> expected = new HashMap<>();
        long capacity = 0;
        long stock = 0;
        long transit = 0;
        for (Reservation<String, String> r : ledger.reservations()) {
            assertTrue(r.amount() > 0);
            switch (r.kind()) {
                case CAPACITY -> {
                    capacity += r.amount();
                    expected.merge("cap|" + r.location(), (long) r.amount(), Long::sum);
                    expected.merge("cap|" + r.location() + "|" + r.key(), (long) r.amount(), Long::sum);
                }
                case STOCK -> {
                    stock += r.amount();
                    expected.merge("stock|" + r.location() + "|" + r.key(), (long) r.amount(), Long::sum);
                    expected.merge("stock|" + r.key(), (long) r.amount(), Long::sum);
                    if (r.backsRequest())
                        expected.merge("req|" + r.requestId().get(), (long) r.amount(), Long::sum);
                    else
                        expected.merge("free|" + r.key(), (long) r.amount(), Long::sum);
                }
                case TRANSIT -> {
                    transit += r.amount();
                    expected.merge("transit|" + r.key(), (long) r.amount(), Long::sum);
                    if (r.backsRequest()) {
                        expected.merge("transitReq|" + r.key(), (long) r.amount(), Long::sum);
                        expected.merge("req|" + r.requestId().get(), (long) r.amount(), Long::sum);
                    }
                }
            }
        }
        assertEquals(capacity, ledger.totalReservedCapacity());
        assertEquals(stock, ledger.totalReservedStock());
        assertEquals(transit, ledger.totalInTransit());
        for (String location : locations) {
            assertEquals(expected.getOrDefault("cap|" + location, 0L), ledger.reservedCapacity(location));
            for (String key : keys) {
                assertEquals(expected.getOrDefault("cap|" + location + "|" + key, 0L),
                        ledger.reservedCapacity(location, key));
                assertEquals(expected.getOrDefault("stock|" + location + "|" + key, 0L),
                        ledger.reservedStock(location, key));
            }
        }
        for (String key : keys) {
            assertEquals(expected.getOrDefault("stock|" + key, 0L), ledger.reservedStock(key));
            assertEquals(expected.getOrDefault("free|" + key, 0L), ledger.reservedStockNotBackingRequests(key));
            assertEquals(expected.getOrDefault("transit|" + key, 0L), ledger.inTransit(key));
            assertEquals(expected.getOrDefault("transitReq|" + key, 0L), ledger.inTransitBackingRequests(key));
        }
        for (UUID request : requests)
            assertEquals(expected.getOrDefault("req|" + request, 0L), ledger.committedToRequest(request));
        assertEquals(ledger.reservations().size(), ledger.size());
        assertEquals(ledger.isEmpty(), ledger.jobIds().isEmpty());
    }
}
