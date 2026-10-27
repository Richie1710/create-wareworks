package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.job.RequestKind;
import dev.wareworks.core.job.ReservationLedger;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.LocationKind;

/**
 * {@link FillLedger} (M31, issue #21, ADR-073, design D5): what open fill requests have promised, and the property the
 * availability of the empty container rests on — <b>each empty container is owed exactly once in every phase of its
 * trip</b>. The availability formula is the ledger's own ({@code ReservationView#availableStock}), applied here exactly
 * as the controller applies it.
 */
class FillLedgerTest {
    private static final String BUCKET = "bucket";
    private static final String LAVA_BUCKET = "lava_bucket";
    private static final String LAVA = "lava";
    private static final String RACK = "rack";
    private static final String BAY = "bay";
    private static final String TERMINAL = "terminal";
    private static final int MB = 1000;
    private static final UUID REQUEST = new UUID(0L, 1L);
    private static final UUID JOB = new UUID(0L, 2L);
    private static final FillLedger.Recipe<String, String> RECIPE = new FillLedger.Recipe<>(BUCKET, LAVA, MB);

    private static Optional<FillLedger.Recipe<String, String>> recipe(String filled) {
        return filled.equals(LAVA_BUCKET) ? Optional.of(RECIPE) : Optional.empty();
    }

    private static RetrievalRequest<String, String> fillRequest(int requested, int remaining) {
        return new RetrievalRequest<>(REQUEST, LAVA_BUCKET, requested, remaining, TERMINAL, RequestKind.FILL);
    }

    private static FillLedger<String, String, String> ledgerOf(RetrievalRequest<String, String> request,
            List<TransportJob<String, String>> jobs) {
        return FillLedger.of(List.of(request), jobs, FillLedgerTest::recipe);
    }

    /** What a new request may still claim of the empty container, by the controller's formula. */
    private static long availableEmpties(long indexed, ReservationLedger<String, String> reservations,
            FillLedger<String, String, String> fills, long itemRequests) {
        return reservations.availableStock(BUCKET, indexed, itemRequests + fills.owedEmpties(BUCKET));
    }

    /**
     * Five empty buckets in the racks and a fill request for two. Whatever phase the one trip of one bucket is in, an
     * observer sees three buckets available and the fluid of the request owed exactly once.
     */
    @Test
    void eachEmptyIsOwedOnceInEveryPhase() {
        RetrievalRequest<String, String> request = fillRequest(2, 2);
        ReservationLedger<String, String> reservations = new ReservationLedger<>();

        // No trip yet.
        FillLedger<String, String, String> open = ledgerOf(request, List.of());
        assertEquals(2, open.owedEmpties(BUCKET));
        assertEquals(2L * MB, open.owedFluid(LAVA));
        assertEquals(3, availableEmpties(5, reservations, open, 0));

        // Planned: its stock reservation backs the request, and the request still owes the bucket.
        TransportJob<String, String> planned = TransportJob.fill(JOB, RACK, BAY, BUCKET, 1, REQUEST);
        reservations.track(planned);
        FillLedger<String, String, String> plannedLedger = ledgerOf(request, List.of(planned));
        assertEquals(2, plannedLedger.owedEmpties(BUCKET));
        assertEquals(MB, plannedLedger.reservedAtBay(BAY), "the bay's fluid for the planned trip is taken");
        assertEquals(3, availableEmpties(5, reservations, plannedLedger, 0));

        // Picked: the bucket left the index; the request must stop owing it.
        TransportJob<String, String> picked = planned.withPicked(1);
        reservations.track(picked);
        FillLedger<String, String, String> pickedLedger = ledgerOf(request, List.of(picked));
        assertEquals(1, pickedLedger.heldEmpty(REQUEST));
        assertEquals(1, pickedLedger.owedEmpties(BUCKET));
        assertEquals(2L * MB, pickedLedger.owedFluid(LAVA), "nothing is filled yet");
        assertEquals(3, availableEmpties(4, reservations, pickedLedger, 0));

        // Filled: no empty container any more, and its fluid left the bay.
        TransportJob<String, String> filled = picked.filledFor(LAVA_BUCKET, TERMINAL, LocationKind.OUTPUT);
        reservations.track(filled);
        FillLedger<String, String, String> filledLedger = ledgerOf(request, List.of(filled));
        assertEquals(1, filledLedger.heldFilled(REQUEST));
        assertEquals(0, filledLedger.heldEmpty(REQUEST));
        assertEquals(1, filledLedger.owedEmpties(BUCKET));
        assertEquals(MB, filledLedger.owedFluid(LAVA));
        assertEquals(0, filledLedger.reservedAtBay(BAY), "a filled trip takes nothing more out of the bay");
        assertEquals(3, availableEmpties(4, reservations, filledLedger, 0));

        // Delivered: the request's remaining amount went down.
        reservations.releaseJob(JOB);
        FillLedger<String, String, String> delivered = ledgerOf(fillRequest(2, 1), List.of());
        assertEquals(1, delivered.owedEmpties(BUCKET));
        assertEquals(MB, delivered.owedFluid(LAVA));
        assertEquals(3, availableEmpties(4, reservations, delivered, 0));
    }

    /** An item request for the empty or the filled key is unaffected by the fill request's own key. */
    @Test
    void itemRequestsOfEitherKeyAreUnaffected() {
        FillLedger<String, String, String> ledger = ledgerOf(fillRequest(3, 3), List.of());
        assertEquals(0, ledger.owedEmpties(LAVA_BUCKET), "a filled container is never owed out of the racks");
        assertEquals(Map.of(BUCKET, 3L), ledger.owedEmptiesByKey());
        ReservationLedger<String, String> reservations = new ReservationLedger<>();
        assertEquals(10 - 3 - 4, availableEmpties(10, reservations, ledger, 4),
                "an item request for four buckets and the fill request's three are both owed");
    }

    /** A stock request, an unknown key, a job of another request and a detached trip owe nothing here. */
    @Test
    void onlyOpenFillRequestsAndTheirTripsCount() {
        RetrievalRequest<String, String> stock = new RetrievalRequest<>(REQUEST, LAVA_BUCKET, 3, 3, TERMINAL);
        assertTrue(FillLedger.of(List.of(stock), List.<TransportJob<String, String>>of(), FillLedgerTest::recipe)
                .isEmpty());
        RetrievalRequest<String, String> unknown = new RetrievalRequest<>(REQUEST, "water_tank", 3, 3, TERMINAL,
                RequestKind.FILL);
        assertTrue(FillLedger.of(List.of(unknown), List.<TransportJob<String, String>>of(), FillLedgerTest::recipe)
                .isEmpty(), "a key that cannot be filled owes nothing");
        TransportJob<String, String> detached = TransportJob.fill(JOB, RACK, BAY, BUCKET, 1, null);
        TransportJob<String, String> other = TransportJob.fill(JOB, RACK, BAY, BUCKET, 1, new UUID(0L, 9L));
        FillLedger<String, String, String> ledger = ledgerOf(fillRequest(2, 2), List.of(detached, other));
        assertEquals(0, ledger.reservedAtBay(BAY), "trips nobody of these requests waits for take nothing");
        assertEquals(2, ledger.owedEmpties(BUCKET));
        assertTrue(FillLedger.<String, String, String>empty().isEmpty());
    }
}
