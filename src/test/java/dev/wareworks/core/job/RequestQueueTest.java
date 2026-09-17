package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

class RequestQueueTest {
    private static final String DIAMOND = "diamond";
    private static final String IRON = "iron";
    private static final String GOLD = "gold";
    private static final String OUT_A = "output-a";
    private static final String OUT_B = "output-b";
    private static final ToLongFunction<String> PLENTY = key -> Long.MAX_VALUE;

    /** Ids 0, 1, 2, ... so that tests can name them. */
    private static Supplier<UUID> sequentialIds() {
        long[] next = {0};
        return () -> new UUID(0L, next[0]++);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static RequestQueue<String, String> queue(int max) {
        return new RequestQueue<>(max, sequentialIds());
    }

    private static RetrievalRequest<String, String> accepted(RequestQueue.AddResult<String, String> result) {
        assertTrue(result.isAccepted(), "expected an accepted request, got " + result);
        return result.request().orElseThrow();
    }

    /** The ids of the open requests in queue order. */
    private static List<UUID> ids(RequestQueue<String, String> queue) {
        return queue.requests().stream().map(RetrievalRequest::id).toList();
    }

    @Test
    void addClampsToAvailableStock() {
        RequestQueue<String, String> queue = queue(4);
        RetrievalRequest<String, String> request = accepted(queue.add(DIAMOND, 32, OUT_A, key -> 20));
        assertEquals(new RetrievalRequest<>(id(0), DIAMOND, 20, 20, OUT_A), request);
        assertEquals(0, request.delivered());
        assertEquals(1, queue.openCount());

        RetrievalRequest<String, String> small = accepted(queue.add(IRON, 5, OUT_A, PLENTY));
        assertEquals(5, small.requested(), "an amount below the stock is not changed");
        assertEquals(Optional.of(request), queue.oldestOpen());
    }

    @Test
    void addRejectsWhenNothingIsAvailable() {
        RequestQueue<String, String> queue = queue(4);
        assertEquals(RequestQueue.AddResult.rejected(RequestQueue.Rejection.NOTHING_AVAILABLE),
                queue.add(DIAMOND, 8, OUT_A, key -> 0));
        assertEquals(Optional.of(RequestQueue.Rejection.NOTHING_AVAILABLE),
                queue.add(DIAMOND, 8, OUT_A, key -> -5).rejection(), "negative availability counts as 0");
        assertTrue(queue.isEmpty());
    }

    @Test
    void addRejectsWhenFull() {
        RequestQueue<String, String> queue = queue(2);
        accepted(queue.add(DIAMOND, 1, OUT_A, PLENTY));
        accepted(queue.add(DIAMOND, 1, OUT_B, PLENTY));
        assertTrue(queue.isFull());
        assertEquals(Optional.of(RequestQueue.Rejection.QUEUE_FULL), queue.add(IRON, 1, OUT_A, PLENTY).rejection());
        assertEquals(Optional.of(RequestQueue.Rejection.NOTHING_AVAILABLE), queue.add(IRON, 1, OUT_A, key -> 0).rejection(),
                "availability is checked before the cap");
        assertEquals(2, queue.openCount());
    }

    @Test
    void addRejectsWhenTheDestinationIsFull() {
        RequestQueue<String, String> queue = queue(4);
        assertEquals(Integer.MAX_VALUE, queue.maxOpenRequestsPerDestination(), "no per-destination cap by default");
        queue.setMaxOpenRequestsPerDestination(2);
        accepted(queue.add(DIAMOND, 1, OUT_A, PLENTY));
        accepted(queue.add(IRON, 1, OUT_A, PLENTY));
        assertTrue(queue.isFullFor(OUT_A));
        assertFalse(queue.isFullFor(OUT_B));
        assertEquals(2, queue.openCountFor(OUT_A));
        // A key that destination has no open request for needs a slot of its own; a repeated key would merge (§7.2).
        assertEquals(Optional.of(RequestQueue.Rejection.DESTINATION_FULL), queue.add(GOLD, 1, OUT_A, PLENTY).rejection(),
                "a busy destination cannot take more slots");
        assertEquals(Optional.of(RequestQueue.Rejection.NOTHING_AVAILABLE), queue.add(GOLD, 1, OUT_A, key -> 0).rejection(),
                "availability is checked before the destination cap");
        accepted(queue.add(DIAMOND, 1, OUT_B, PLENTY));
        accepted(queue.add(IRON, 1, OUT_B, PLENTY));
        assertTrue(queue.isFull());
        assertEquals(Optional.of(RequestQueue.Rejection.DESTINATION_FULL), queue.add(GOLD, 1, OUT_A, PLENTY).rejection(),
                "the destination cap is reported before the queue cap");
        assertEquals(Optional.of(RequestQueue.Rejection.QUEUE_FULL), queue.add(DIAMOND, 1, "output-c", PLENTY).rejection());

        queue.cancel(queue.requestsFor(OUT_A).getFirst().id());
        assertFalse(queue.isFullFor(OUT_A), "a finished request frees the destination");
        assertThrows(IllegalArgumentException.class, () -> queue.setMaxOpenRequestsPerDestination(0));

        RetrievalRequest<String, String> saved1 = new RetrievalRequest<>(id(20), IRON, 1, 1, OUT_B);
        RetrievalRequest<String, String> saved2 = new RetrievalRequest<>(id(21), IRON, 1, 1, OUT_B);
        RetrievalRequest<String, String> saved3 = new RetrievalRequest<>(id(22), IRON, 1, 1, OUT_B);
        assertEquals(3, queue.restore(List.of(saved1, saved2, saved3)), "restore ignores the destination cap");
    }

    @Test
    void addValidatesArguments() {
        RequestQueue<String, String> queue = queue(2);
        assertThrows(IllegalArgumentException.class, () -> queue.add(DIAMOND, 0, OUT_A, PLENTY));
        assertThrows(IllegalArgumentException.class, () -> queue.add(DIAMOND, -3, OUT_A, PLENTY));
        assertThrows(NullPointerException.class, () -> queue.add(null, 1, OUT_A, PLENTY));
        assertThrows(NullPointerException.class, () -> queue.add(DIAMOND, 1, null, PLENTY));
        assertThrows(NullPointerException.class, () -> queue.add(DIAMOND, 1, OUT_A, null));
        assertThrows(IllegalArgumentException.class, () -> new RequestQueue<String, String>(0));

        RequestQueue<String, String> constantIds = new RequestQueue<>(4, () -> id(7));
        accepted(constantIds.add(DIAMOND, 1, OUT_A, PLENTY));
        // Another key needs a new request, and therefore a new id (a repeated key would merge into the open one).
        assertThrows(IllegalStateException.class, () -> constantIds.add(IRON, 1, OUT_A, PLENTY));
        assertEquals(1, constantIds.openCount(), "a failed add changes nothing");
    }

    @Test
    void requestRecordValidates() {
        assertThrows(IllegalArgumentException.class, () -> new RetrievalRequest<>(id(0), DIAMOND, 0, 0, OUT_A));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalRequest<>(id(0), DIAMOND, 4, 5, OUT_A));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalRequest<>(id(0), DIAMOND, 4, -1, OUT_A));
        assertThrows(NullPointerException.class, () -> new RetrievalRequest<>(null, DIAMOND, 4, 4, OUT_A));
        RetrievalRequest<String, String> done = new RetrievalRequest<>(id(0), DIAMOND, 4, 0, OUT_A);
        assertFalse(done.isOpen());
        assertEquals(4, done.delivered());
    }

    @Test
    void deliverCountsDownAndRemovesDoneRequests() {
        RequestQueue<String, String> queue = queue(4);
        UUID first = accepted(queue.add(DIAMOND, 10, OUT_A, PLENTY)).id();
        UUID second = accepted(queue.add(IRON, 3, OUT_A, PLENTY)).id();

        assertEquals(4, queue.deliver(first, 4));
        assertEquals(6, queue.get(first).orElseThrow().remaining());
        assertEquals(first, queue.oldestOpen().orElseThrow().id(), "a partial delivery keeps the queue position");

        assertEquals(6, queue.deliver(first, 50), "only the remaining amount counts");
        assertTrue(queue.get(first).isEmpty(), "a done request leaves the queue");
        assertEquals(second, queue.oldestOpen().orElseThrow().id());

        assertEquals(0, queue.deliver(first, 1), "unknown id");
        assertEquals(0, queue.deliver(second, 0), "nothing delivered");
        assertThrows(IllegalArgumentException.class, () -> queue.deliver(second, -1));
        assertEquals(3, queue.get(second).orElseThrow().remaining());
    }

    @Test
    void cancelAndCancelFor() {
        RequestQueue<String, String> queue = queue(8);
        UUID a1 = accepted(queue.add(DIAMOND, 2, OUT_A, PLENTY)).id();
        UUID b1 = accepted(queue.add(DIAMOND, 3, OUT_B, PLENTY)).id();
        UUID a2 = accepted(queue.add(IRON, 4, OUT_A, PLENTY)).id();

        assertEquals(b1, queue.cancel(b1).orElseThrow().id());
        assertTrue(queue.cancel(b1).isEmpty(), "already cancelled");

        UUID b2 = accepted(queue.add(IRON, 5, OUT_B, PLENTY)).id();
        List<RetrievalRequest<String, String>> removed = queue.cancelFor(OUT_A);
        assertEquals(List.of(a1, a2), removed.stream().map(RetrievalRequest::id).toList(), "in queue order");
        assertEquals(List.of(b2), queue.requests().stream().map(RetrievalRequest::id).toList());
        assertTrue(queue.cancelFor(OUT_A).isEmpty());

        queue.clear();
        assertTrue(queue.isEmpty());
        assertTrue(queue.oldestOpen().isEmpty());
    }

    @Test
    void perDestinationAndPerKeyViews() {
        RequestQueue<String, String> queue = queue(8);
        accepted(queue.add(DIAMOND, 2, OUT_A, PLENTY));
        accepted(queue.add(DIAMOND, 3, OUT_B, PLENTY));
        UUID iron = accepted(queue.add(IRON, 4, OUT_A, PLENTY)).id();
        queue.deliver(iron, 1);

        assertEquals(List.of(DIAMOND, IRON), queue.requestsFor(OUT_A).stream().map(RetrievalRequest::key).toList());
        assertEquals(5L, queue.remainingFor(OUT_A));
        assertEquals(3L, queue.remainingFor(OUT_B));
        assertEquals(0L, queue.remainingFor("nowhere"));
        assertEquals(5L, queue.remainingOf(DIAMOND));
        assertEquals(3L, queue.remainingOf(IRON));
        assertEquals(0L, queue.remainingOf("gold"));
        assertEquals(Map.of(DIAMOND, 5L, IRON, 3L), queue.remainingByKey(),
                "one pass over the queue instead of one scan per key");
        assertThrows(UnsupportedOperationException.class, () -> queue.remainingByKey().clear());
        assertThrows(UnsupportedOperationException.class, () -> queue.requests().clear(), "views are read-only");
        assertThrows(UnsupportedOperationException.class, () -> queue.requestsFor(OUT_A).clear());
    }

    @Test
    void fifoOrderSurvivesMixedChanges() {
        RequestQueue<String, String> queue = queue(16);
        List<UUID> expected = new ArrayList<>();
        // One key per request: requests for a key the destination already waits for would merge instead (§7.2).
        for (int i = 0; i < 6; i++)
            expected.add(accepted(queue.add("key-" + i, 10, OUT_A, PLENTY)).id());
        queue.deliver(expected.get(2), 3);
        queue.cancel(expected.get(1));
        expected.remove(1);
        queue.deliver(expected.get(0), 10);
        expected.remove(0);
        assertEquals(expected, queue.requests().stream().map(RetrievalRequest::id).toList());
        assertEquals(expected.getFirst(), queue.oldestOpen().orElseThrow().id());
    }

    @Test
    void restoreSkipsInvalidEntriesAndIgnoresTheCap() {
        RequestQueue<String, String> queue = queue(2);
        accepted(queue.add(DIAMOND, 1, OUT_A, PLENTY));
        RetrievalRequest<String, String> first = new RetrievalRequest<>(id(10), DIAMOND, 8, 5, OUT_A);
        RetrievalRequest<String, String> done = new RetrievalRequest<>(id(11), IRON, 4, 0, OUT_B);
        RetrievalRequest<String, String> duplicate = new RetrievalRequest<>(id(10), IRON, 2, 2, OUT_B);
        RetrievalRequest<String, String> second = new RetrievalRequest<>(id(12), IRON, 3, 3, OUT_B);
        RetrievalRequest<String, String> third = new RetrievalRequest<>(id(13), IRON, 1, 1, OUT_B);

        int restored = queue.restore(Arrays.asList(first, done, null, duplicate, second, third));
        assertEquals(3, restored);
        assertEquals(List.of(first, second, third), queue.requests(), "order kept, first duplicate wins");
        assertTrue(queue.isFull(), "above the cap after restoring");
        // A key no destination waits for: a repeated key would merge and never see the cap (§7.2).
        assertEquals(Optional.of(RequestQueue.Rejection.QUEUE_FULL), queue.add(GOLD, 1, OUT_A, PLENTY).rejection());

        queue.setMaxOpenRequests(4);
        assertTrue(queue.add(GOLD, 1, OUT_A, PLENTY).isAccepted(), "a raised cap accepts again");
        assertThrows(IllegalArgumentException.class, () -> queue.setMaxOpenRequests(0));

        queue.restore(List.of());
        assertTrue(queue.isEmpty(), "restore replaces the whole queue");
    }

    // --- merging (§7.2, ADR-020) ------------------------------------------------------------------------------------

    @Test
    void repeatedRequestsMergeIntoTheOpenOne() {
        RequestQueue<String, String> queue = queue(8);
        RetrievalRequest<String, String> first = accepted(queue.add(DIAMOND, 1, OUT_A, PLENTY));
        RequestQueue.AddResult<String, String> second = queue.add(DIAMOND, 1, OUT_A, PLENTY);
        assertTrue(second.merged(), "the same key and destination merge");
        assertEquals(1, second.accepted(), "this call added one item");
        RetrievalRequest<String, String> merged = accepted(second);
        assertEquals(first.id(), merged.id(), "the open request keeps its identity");
        assertEquals(2, merged.requested());
        assertEquals(2, merged.remaining());
        assertEquals(1, queue.openCount(), "no second request");

        for (int click = 3; click <= 10; click++)
            assertTrue(queue.add(DIAMOND, 1, OUT_A, PLENTY).merged(), "click " + click);
        assertEquals(1, queue.openCount(), "ten clicks are one request");
        assertEquals(10L, queue.remainingOf(DIAMOND));
        assertEquals(Optional.of(first.id()), queue.openFor(OUT_A, DIAMOND).map(RetrievalRequest::id));
        assertTrue(queue.openFor(OUT_B, DIAMOND).isEmpty(), "another destination has no open request");
        assertTrue(queue.openFor(OUT_A, IRON).isEmpty(), "another key has no open request");
    }

    @Test
    void anotherKeyOrDestinationDoesNotMergeAndFifoIsKept() {
        RequestQueue<String, String> queue = queue(8);
        UUID diamondsA = accepted(queue.add(DIAMOND, 2, OUT_A, PLENTY)).id();
        UUID ironA = accepted(queue.add(IRON, 2, OUT_A, PLENTY)).id();
        UUID diamondsB = accepted(queue.add(DIAMOND, 2, OUT_B, PLENTY)).id();
        assertEquals(3, queue.openCount(), "a different key and a different destination queue their own requests");

        RequestQueue.AddResult<String, String> grown = queue.add(DIAMOND, 3, OUT_A, PLENTY);
        assertTrue(grown.merged());
        assertEquals(List.of(diamondsA, ironA, diamondsB),
                queue.requests().stream().map(RetrievalRequest::id).toList(), "a merge never moves a request");
        assertEquals(Optional.of(diamondsA), queue.oldestOpen().map(RetrievalRequest::id), "still first in, first out");
        assertEquals(5, queue.get(diamondsA).orElseThrow().remaining());
        assertEquals(2, queue.get(diamondsB).orElseThrow().remaining(), "the other destination is untouched");
    }

    @Test
    void mergingTakesNoQueueSlotSoNoCapRefusesIt() {
        RequestQueue<String, String> queue = queue(2);
        queue.setMaxOpenRequestsPerDestination(1);
        UUID id = accepted(queue.add(DIAMOND, 1, OUT_A, PLENTY)).id();
        accepted(queue.add(DIAMOND, 1, OUT_B, PLENTY));
        assertTrue(queue.isFull());
        assertTrue(queue.isFullFor(OUT_A));

        RequestQueue.AddResult<String, String> merged = queue.add(DIAMOND, 4, OUT_A, PLENTY);
        assertTrue(merged.merged(), "a full queue still accepts a merge: it takes no slot");
        assertEquals(5, queue.get(id).orElseThrow().remaining());
        assertEquals(Optional.of(RequestQueue.Rejection.DESTINATION_FULL),
                queue.add(IRON, 1, OUT_A, PLENTY).rejection(), "a new key at that destination still needs a slot");
        assertEquals(2, queue.openCount());
    }

    @Test
    void theStockClampAppliesToTheMergedTotal() {
        RequestQueue<String, String> queue = queue(4);
        // What the controller passes: the stock minus what the open requests already promise.
        ToLongFunction<String> stock = key -> 12 - queue.remainingOf(key);
        RetrievalRequest<String, String> first = accepted(queue.add(DIAMOND, 8, OUT_A, stock));
        assertEquals(8, first.remaining());

        RequestQueue.AddResult<String, String> second = queue.add(DIAMOND, 8, OUT_A, stock);
        assertEquals(4, second.accepted(), "only what the stock still allows is added");
        assertEquals(12, accepted(second).remaining(), "the merged total is the whole stock, never more");
        assertEquals(Optional.of(RequestQueue.Rejection.NOTHING_AVAILABLE),
                queue.add(DIAMOND, 1, OUT_A, stock).rejection(), "clicking again promises nothing twice");
        assertEquals(1, queue.openCount());
    }

    @Test
    void thePerRequestCapAppliesToTheMergedTotal() {
        RequestQueue<String, String> queue = queue(4);
        RetrievalRequest<String, String> first = accepted(queue.add(DIAMOND, 6, OUT_A, PLENTY, 10));
        assertEquals(6, first.remaining());

        RequestQueue.AddResult<String, String> capped = queue.add(DIAMOND, 6, OUT_A, PLENTY, 10);
        assertEquals(4, capped.accepted(), "the cap bounds the merged total, not the increment");
        assertEquals(10, accepted(capped).remaining());
        assertEquals(Optional.of(RequestQueue.Rejection.REQUEST_FULL),
                queue.add(DIAMOND, 1, OUT_A, PLENTY, 10).rejection(), "clicking cannot exceed the cap");
        assertEquals(1, queue.openCount());

        queue.deliver(first.id(), 3);
        assertEquals(3, queue.add(DIAMOND, 9, OUT_A, PLENTY, 10).accepted(), "a delivery frees room again");
        assertEquals(10, queue.get(first.id()).orElseThrow().remaining());
        assertTrue(queue.add(DIAMOND, 1, OUT_A, PLENTY).isAccepted(), "a caller without a cap of its own is unaffected");
        assertThrows(IllegalArgumentException.class, () -> queue.add(DIAMOND, 1, OUT_A, PLENTY, 0));
    }

    @Test
    void mergingFollowsDeliveriesAndCancellations() {
        RequestQueue<String, String> queue = queue(4);
        UUID id = accepted(queue.add(DIAMOND, 4, OUT_A, PLENTY)).id();
        assertEquals(3, queue.deliver(id, 3));

        RetrievalRequest<String, String> merged = accepted(queue.add(DIAMOND, 6, OUT_A, PLENTY));
        assertEquals(id, merged.id());
        assertEquals(10, merged.requested());
        assertEquals(7, merged.remaining());
        assertEquals(3, merged.delivered(), "what was delivered before stays delivered");

        assertEquals(7, queue.deliver(id, 50), "only the remaining amount counts");
        assertTrue(queue.isEmpty(), "a finished merged request leaves the queue");
        RetrievalRequest<String, String> fresh = accepted(queue.add(DIAMOND, 2, OUT_A, PLENTY));
        assertFalse(fresh.id().equals(id), "a finished request is not merged into any more");
        assertEquals(Optional.of(fresh), queue.cancel(fresh.id()));
        assertTrue(queue.openFor(OUT_A, DIAMOND).isEmpty(), "a cancelled request is gone");
    }

    @Test
    void aServedRequestYieldsItsQueuePositionWhenItIsToppedUp() {
        RequestQueue<String, String> queue = queue(8);
        UUID clock = accepted(queue.add(DIAMOND, 4, OUT_A, PLENTY)).id();
        UUID waiting = accepted(queue.add(IRON, 4, OUT_B, PLENTY)).id();

        // Nothing delivered yet: a burst of requests belongs where the first one queued it.
        assertTrue(queue.add(DIAMOND, 4, OUT_A, PLENTY).merged());
        assertEquals(List.of(clock, waiting), ids(queue), "an unserved request keeps its turn");

        // Once it has been served, a top-up goes behind everything that is waiting now, so a destination that keeps
        // adding to its request (a redstone pulse clock) cannot hold the head of the queue and starve the others.
        assertEquals(3, queue.deliver(clock, 3));
        assertTrue(queue.add(DIAMOND, 4, OUT_A, PLENTY).merged());
        assertEquals(List.of(waiting, clock), ids(queue), "a served request yields to the one that waited");
        assertEquals(Optional.of(waiting), queue.oldestOpen().map(RetrievalRequest::id));
        assertEquals(9, queue.get(clock).orElseThrow().remaining(), "the items were still added");
        assertEquals(12, queue.get(clock).orElseThrow().requested());
        assertEquals(3, queue.get(clock).orElseThrow().delivered());

        assertTrue(queue.add(DIAMOND, 4, OUT_A, PLENTY).merged());
        assertEquals(List.of(waiting, clock), ids(queue), "and cannot fall behind the end of the queue");
        queue.deliver(waiting, 1);
        assertEquals(List.of(waiting, clock), ids(queue), "a delivery never moves a request");
    }

    @Test
    void aRestoredQueueMergesIntoTheOldestDuplicate() {
        RequestQueue<String, String> queue = queue(8);
        // A world saved before merging existed holds several requests for one key and destination: restore keeps every
        // one of them and never merges, and the next request grows the oldest (§7.2 "persistence").
        RetrievalRequest<String, String> older = new RetrievalRequest<>(id(30), DIAMOND, 4, 4, OUT_A);
        RetrievalRequest<String, String> younger = new RetrievalRequest<>(id(31), DIAMOND, 6, 6, OUT_A);
        assertEquals(2, queue.restore(List.of(older, younger)), "restore keeps duplicates");
        assertEquals(Optional.of(older), queue.openFor(OUT_A, DIAMOND), "the oldest matching request is grown");

        RequestQueue.AddResult<String, String> merged = queue.add(DIAMOND, 5, OUT_A, PLENTY);
        assertTrue(merged.merged());
        assertEquals(id(30), accepted(merged).id());
        assertEquals(9, queue.get(id(30)).orElseThrow().remaining());
        assertEquals(younger, queue.get(id(31)).orElseThrow(), "the other saved request is untouched");
        assertEquals(List.of(id(30), id(31)), ids(queue), "both keep their order");
        assertEquals(2, queue.openCount(), "and both are served one after another");
    }

    @Test
    void mergingNeverOverflows() {
        RequestQueue<String, String> queue = queue(2);
        UUID id = accepted(queue.add(DIAMOND, Integer.MAX_VALUE, OUT_A, PLENTY)).id();
        assertEquals(Integer.MAX_VALUE, queue.get(id).orElseThrow().requested());
        assertEquals(Optional.of(RequestQueue.Rejection.REQUEST_FULL), queue.add(DIAMOND, 1, OUT_A, PLENTY).rejection(),
                "nothing fits into a request that already asks for the largest amount");

        RetrievalRequest<String, String> request = new RetrievalRequest<>(id(0), DIAMOND, 4, 2, OUT_A);
        RetrievalRequest<String, String> grown = request.withAdded(5);
        assertEquals(9, grown.requested());
        assertEquals(7, grown.remaining());
        assertEquals(2, grown.delivered());
        assertEquals(request.id(), grown.id());
        assertThrows(IllegalArgumentException.class, () -> request.withAdded(0));
        assertThrows(IllegalArgumentException.class, () -> request.withAdded(Integer.MAX_VALUE));
    }

    @Test
    void randomIdsAreUnique() {
        RequestQueue<String, String> queue = new RequestQueue<>(64);
        Map<UUID, Boolean> seen = new java.util.HashMap<>();
        // One key per request: a repeated key would merge into the open request instead of creating an id (§7.2).
        for (int i = 0; i < 64; i++)
            assertTrue(seen.put(accepted(queue.add("key-" + i, 1, OUT_A, PLENTY)).id(), Boolean.TRUE) == null);
        assertEquals(64, queue.openCount());
    }
}
