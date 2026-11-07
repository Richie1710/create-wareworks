package dev.wareworks.core.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.job.ReservationLedger;
import dev.wareworks.core.job.TransportJob;

/**
 * The availability add-back of the Warehouse Optimizer (issue #46, ADR-079), measured with the real ledger: a request
 * sees what it would see without the relocation, before the pick and after it, what may still move leaves the trip
 * on its way out, and a trip is inbound only once it left the index. The arithmetic is the controller's own
 * ({@code WarehouseControllerBlockEntity#availableStock}, {@code #optimizerMovable}, {@code #storeHeadroom}).
 */
class RelocationStockTest {
    private static final String IRON = "iron";
    private static final String GOLD = "gold";
    private static final UUID RELOCATION = new UUID(0L, 1L);
    private static final UUID RETRIEVE = new UUID(0L, 2L);
    private static final UUID REQUEST = new UUID(0L, 3L);
    private static final UUID STORE = new UUID(0L, 4L);

    private final ReservationLedger<String, String> ledger = new ReservationLedger<>();

    /** What the controller answers: the ledger's figure measured against the count with the relocation counted in. */
    private long available(long indexed, long openRequests, List<TransportJob<String, String>> jobs) {
        long moving = RelocationStock.inFlight(jobs, IRON);
        return ledger.availableStock(IRON, RelocationStock.counted(indexed, moving), openRequests);
    }

    @Test
    void aRequestSeesTheWholeStockBeforeAndAfterThePick() {
        TransportJob<String, String> relocate = TransportJob.relocate(RELOCATION, "overflow", "rack", IRON, 64);
        assertEquals(100, available(100, 0, List.of()), "nothing moving");

        ledger.track(relocate);
        assertEquals(64, RelocationStock.inFlight(List.of(relocate), IRON));
        assertEquals(36, ledger.availableStock(IRON, 100, 0), "without the add-back a request would lose a trip");
        assertEquals(100, available(100, 0, List.of(relocate)), "with it, before the pick");

        TransportJob<String, String> picked = relocate.withPicked(64);
        ledger.track(picked);
        assertEquals(100, available(36, 0, List.of(picked)), "after the pick, with 64 out of the index");

        TransportJob<String, String> landed = picked.plusDelivered(64);
        ledger.track(landed);
        assertEquals(0, RelocationStock.inFlight(List.of(landed), IRON));
        assertEquals(100, available(100, 0, List.of(landed)), "and once it is put down again");
    }

    @Test
    void anOpenRequestStillOwnsItsShareWhileItemsMove() {
        TransportJob<String, String> relocate = TransportJob.relocate(RELOCATION, "overflow", "rack", IRON, 64);
        ledger.track(relocate);
        assertEquals(54, available(64, 10, List.of(relocate)),
                "the add-back goes into the count, so a clamp never hands a request what another one owns");
        assertEquals(0, RelocationStock.free(available(64, 10, List.of(relocate)), 64),
                "and nothing more may move: the 54 free are the trip under way");
    }

    @Test
    void onlyTheFreeSurplusMoves() {
        TransportJob<String, String> relocate = TransportJob.relocate(RELOCATION, "overflow", "rack", IRON, 20);
        ledger.track(relocate);
        ledger.track(TransportJob.retrieve(RETRIEVE, "rack", "output", IRON, 5, REQUEST));
        long available = available(100, 5, List.of(relocate));
        assertEquals(95, available, "the request's five are spoken for, the relocation's twenty are not");
        assertEquals(75, RelocationStock.free(available, RelocationStock.inFlight(List.of(relocate), IRON)));
    }

    @Test
    void otherKeysAndOtherJobsAreNotCounted() {
        List<TransportJob<String, String>> jobs = List.of(
                TransportJob.relocate(RELOCATION, "overflow", "rack", GOLD, 64),
                TransportJob.retrieve(RETRIEVE, "rack", "output", IRON, 5, REQUEST),
                TransportJob.relocate(new UUID(0L, 9L), "overflow", "rack", IRON, 12).withPicked(10).plusDelivered(4));
        assertEquals(6, RelocationStock.inFlight(jobs, IRON), "only what the iron relocation still holds");
        assertEquals(0, RelocationStock.free(5, 9));
        assertEquals(0, RelocationStock.counted(-4, -1));
    }

    @Test
    void aTripIsInboundOnlyOnceItLeftTheIndex() {
        // 100 iron indexed, a store of 16 on its way in, and a relocation of 64 out of the overflow.
        TransportJob<String, String> store = TransportJob.store(STORE, "input", "rack", IRON, 16);
        TransportJob<String, String> relocate = TransportJob.relocate(RELOCATION, "overflow", "rack", IRON, 64);
        ledger.track(store);
        ledger.track(relocate);
        List<TransportJob<String, String>> jobs = List.of(store, relocate);
        assertEquals(80, ledger.reservedCapacityFor(IRON), "the ledger holds the trip's room as well as the store's");
        assertEquals(64, RelocationStock.planned(jobs, IRON));
        assertEquals(16, RelocationStock.inbound(ledger.reservedCapacityFor(IRON), RelocationStock.planned(jobs, IRON)),
                "before the pick the trip's items are still indexed: only the store comes in");

        TransportJob<String, String> picked = relocate.withPicked(64);
        ledger.track(picked);
        jobs = List.of(store, picked);
        assertEquals(0, RelocationStock.planned(jobs, IRON));
        assertEquals(80, RelocationStock.inbound(ledger.reservedCapacityFor(IRON), RelocationStock.planned(jobs, IRON)),
                "after it the 64 are out of the index and on their way: 36 indexed + 80 inbound = 116, as before");
        assertEquals(0, RelocationStock.inbound(10, 64), "never below 0");
    }

    @Test
    void movingItemsAreCollectedPerKey() {
        List<TransportJob<String, String>> jobs = List.of(
                TransportJob.relocate(RELOCATION, "overflow", "rack", IRON, 64),
                TransportJob.relocate(new UUID(0L, 9L), "overflow", "rack", IRON, 12).withPicked(10).plusDelivered(4),
                TransportJob.relocate(new UUID(0L, 10L), "overflow", "rack", GOLD, 5).withPicked(5),
                TransportJob.retrieve(RETRIEVE, "rack", "output", IRON, 5, REQUEST));
        Map<String, RelocationStock.Moving> moving = RelocationStock.byKey(jobs);
        assertEquals(new RelocationStock.Moving(70, 6), moving.get(IRON), "64 planned and 6 still carried");
        assertEquals(new RelocationStock.Moving(5, 5), moving.get(GOLD));
        assertEquals(2, moving.size(), "a retrieve is no relocation");
        assertEquals(RelocationStock.inFlight(jobs, IRON), moving.get(IRON).inFlight(), "the same figure");
        assertEquals(Map.of(), RelocationStock.byKey(List.<TransportJob<String, String>>of()));
    }
}
