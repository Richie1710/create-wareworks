package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.warehouse.LocationKind;

class TransportJobTest {
    private static final UUID JOB = new UUID(0L, 1L);
    private static final UUID REQUEST = new UUID(0L, 2L);
    private static final String ORE = "ore";

    @Test
    void factoriesCreateUnpickedJobs() {
        TransportJob<String, String> store = TransportJob.store(JOB, "in", "chest", ORE, 16);
        assertEquals(JobType.STORE, store.type());
        assertEquals(LocationKind.INPUT, store.sourceKind());
        assertEquals(LocationKind.STORAGE, store.targetKind());
        assertEquals(Optional.empty(), store.requestId());
        assertNull(store.requestIdOrNull());
        assertFalse(store.picked());
        assertEquals(0, store.heldAmount());
        assertFalse(store.isFinished());

        TransportJob<String, String> retrieve = TransportJob.retrieve(JOB, "chest", "out", ORE, 8, REQUEST);
        assertEquals(LocationKind.STORAGE, retrieve.sourceKind());
        assertEquals(LocationKind.OUTPUT, retrieve.targetKind());
        assertEquals(REQUEST, retrieve.requestIdOrNull());
        assertEquals(Optional.empty(), TransportJob.retrieve(JOB, "chest", "out", ORE, 8, null).requestId());
    }

    /**
     * A warehouse rebuilt under a working crane renames the two places its job names, and nothing else: the same job,
     * the same items, the same request, the same progress (M21 review fix, ADR-033).
     */
    @Test
    void relabellingMovesOnlyTheTwoNames() {
        TransportJob<String, String> job = TransportJob.retrieve(JOB, "chest", "out", ORE, 32, REQUEST)
                .withPicked(20).plusDelivered(5);
        TransportJob<String, String> moved = job.relabelled("chest-b", "out-b");
        assertEquals("chest-b", moved.source());
        assertEquals("out-b", moved.target());
        assertEquals(job.id(), moved.id());
        assertEquals(job.type(), moved.type());
        assertEquals(job.targetKind(), moved.targetKind());
        assertEquals(job.key(), moved.key());
        assertEquals(job.plannedAmount(), moved.plannedAmount());
        assertEquals(job.requestId(), moved.requestId());
        assertTrue(moved.picked());
        assertEquals(job.pickedAmount(), moved.pickedAmount());
        assertEquals(job.deliveredAmount(), moved.deliveredAmount());
        assertEquals(job.heldAmount(), moved.heldAmount());
        assertSame(job, job.relabelled("chest", "out"), "a rebuild that moved neither place changes nothing");
        assertThrows(NullPointerException.class, () -> job.relabelled(null, "out"));
        assertThrows(NullPointerException.class, () -> job.relabelled("chest", null));
    }

    @Test
    void progressIsTrackedByCopies() {
        TransportJob<String, String> job = TransportJob.retrieve(JOB, "chest", "out", ORE, 32, REQUEST);
        TransportJob<String, String> picked = job.withPicked(20);
        assertTrue(picked.picked());
        assertEquals(20, picked.heldAmount());
        assertEquals(JOB, picked.id());
        assertThrows(IllegalStateException.class, () -> picked.withPicked(1), "a job is picked once");

        TransportJob<String, String> partly = picked.plusDelivered(5);
        assertEquals(15, partly.heldAmount());
        assertEquals(5, partly.deliveredAmount());
        assertThrows(IllegalArgumentException.class, () -> partly.plusDelivered(16));
        assertThrows(IllegalArgumentException.class, () -> partly.plusDelivered(-1));
        assertTrue(partly.plusDelivered(15).isFinished());
        assertTrue(job.withPicked(0).isFinished(), "a zero pick holds nothing");
        assertThrows(IllegalStateException.class, () -> job.plusDelivered(0));
    }

    @Test
    void rerouteAndRequestDetaching() {
        TransportJob<String, String> retrieve = TransportJob.retrieve(JOB, "chest", "out", ORE, 32, REQUEST)
                .withPicked(10);
        TransportJob<String, String> back = retrieve.withTarget("other chest", LocationKind.STORAGE).withoutRequest();
        assertEquals("other chest", back.target());
        assertEquals(LocationKind.STORAGE, back.targetKind());
        assertEquals(Optional.empty(), back.requestId());
        assertSame(back, back.withoutRequest());
        assertThrows(IllegalArgumentException.class, () -> retrieve.withTarget("in", LocationKind.INPUT));

        TransportJob<String, String> store = TransportJob.store(JOB, "in", "chest", ORE, 16).withPicked(16);
        assertEquals(LocationKind.INPUT, store.withTarget("in", LocationKind.INPUT).targetKind());
        // Since M17 store leftovers may also go to an accepting warehouse port, which is an OUTPUT; a production station
        // stays forbidden, because nobody ordered those items (ADR-024).
        assertEquals(LocationKind.OUTPUT, store.withTarget("port", LocationKind.OUTPUT).targetKind());
        assertThrows(IllegalArgumentException.class, () -> store.withTarget("machine", LocationKind.PRODUCTION));
    }

    @Test
    void invalidJobsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TransportJob.store(JOB, "in", "chest", ORE, 0));
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.STORE, "in", "chest",
                LocationKind.STORAGE, ORE, 4, Optional.of(REQUEST), false, 0, 0), "store jobs serve no request");
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.STORE, "in", "machine",
                LocationKind.PRODUCTION, ORE, 4, Optional.empty(), false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.RETRIEVE, "chest", "out",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), false, 2, 0), "unpicked jobs hold nothing");
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.RETRIEVE, "chest", "out",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), true, 5, 0), "picked more than planned");
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.RETRIEVE, "chest", "out",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), true, 3, 4), "delivered more than picked");
        assertThrows(NullPointerException.class, () -> TransportJob.store(JOB, null, "chest", ORE, 1));
    }

    /**
     * A collect job (M18, issue #13): it picks at a <b>collecting</b> warehouse port, stores what it fetched, and can
     * never drop at a port — which is the churn loop of §5 answered by the constructor rather than by a rule.
     */
    @Test
    void aCollectJobPicksAtAPortAndStores() {
        TransportJob<String, String> collect = TransportJob.collect(JOB, "port", "chest", ORE, 12);
        assertEquals(JobType.COLLECT, collect.type());
        assertEquals(LocationKind.OUTPUT, collect.sourceKind(), "the source is the port itself");
        assertEquals(LocationKind.STORAGE, collect.targetKind());
        assertEquals(Optional.empty(), collect.requestId(), "nobody asked for collected items");
        assertFalse(collect.picked());
        assertFalse(JobType.COLLECT.reservesSourceStock(), "a machine's inventory is not indexed stock");
    }

    /** The loop answer, structural: a collect job may store or go back into an input, and nowhere else. */
    @Test
    void aCollectJobCannotDropAtAnOutput() {
        assertTrue(JobType.COLLECT.allowsTarget(LocationKind.STORAGE));
        assertTrue(JobType.COLLECT.allowsTarget(LocationKind.INPUT));
        assertFalse(JobType.COLLECT.allowsTarget(LocationKind.OUTPUT), "a collected item is never exported");
        assertFalse(JobType.COLLECT.allowsTarget(LocationKind.PRODUCTION));
        assertFalse(JobType.COLLECT.allowsTarget(LocationKind.KEEPER));
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.COLLECT, "port", "other port",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), false, 0, 0));

        TransportJob<String, String> held = TransportJob.collect(JOB, "port", "chest", ORE, 12).withPicked(12);
        assertEquals(LocationKind.INPUT, held.withTarget("in", LocationKind.INPUT).targetKind(),
                "leftovers may go back into an input buffer");
        assertThrows(IllegalArgumentException.class, () -> held.withTarget("port", LocationKind.OUTPUT));
        assertThrows(IllegalArgumentException.class, () -> held.withTarget("machine", LocationKind.PRODUCTION));
    }

    /** Nobody asked for collected items, so a collect job can carry no request id at all. */
    @Test
    void aCollectJobServesNobody() {
        assertFalse(JobType.COLLECT.mayCarryRequestId());
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.COLLECT, "port", "chest",
                LocationKind.STORAGE, ORE, 4, Optional.of(REQUEST), false, 0, 0));
    }

    /**
     * The two questions the pre-M18 code asked as {@code type != STORE}, now positive: which types bring items <b>in</b>
     * (so a real drop into storage may complete a production order) and which ones <b>wait</b> at a full delivery target.
     */
    @Test
    void theTwoPositiveTypeQuestions() {
        assertTrue(JobType.STORE.bringsItemsIn());
        assertTrue(JobType.COLLECT.bringsItemsIn());
        assertFalse(JobType.RETRIEVE.bringsItemsIn());
        assertFalse(JobType.SUPPLY.bringsItemsIn());

        assertTrue(JobType.RETRIEVE.waitsAtAFullTarget());
        assertTrue(JobType.SUPPLY.waitsAtAFullTarget());
        assertFalse(JobType.STORE.waitsAtAFullTarget(), "a store into a full port must not park the crane");
        assertFalse(JobType.COLLECT.waitsAtAFullTarget(), "and a collect has no delivery target at all");

        // Every type answers exactly one of the two: there is no type that both brings items in and waits for somebody.
        for (JobType type : JobType.values())
            assertFalse(type.bringsItemsIn() && type.waitsAtAFullTarget(), type + " cannot be both");
    }

    @Test
    void jobTypeEndpoints() {
        assertTrue(JobType.STORE.allowsTarget(LocationKind.STORAGE));
        assertTrue(JobType.STORE.allowsTarget(LocationKind.INPUT));
        // An accepting warehouse port is an OUTPUT, and a store job may drop there since M17 (issue #12).
        assertTrue(JobType.STORE.allowsTarget(LocationKind.OUTPUT));
        assertFalse(JobType.STORE.allowsTarget(LocationKind.PRODUCTION));
        assertFalse(JobType.STORE.allowsTarget(LocationKind.KEEPER));
        assertTrue(JobType.RETRIEVE.allowsTarget(LocationKind.OUTPUT));
        assertTrue(JobType.RETRIEVE.allowsTarget(LocationKind.STORAGE));
        assertFalse(JobType.RETRIEVE.allowsTarget(LocationKind.INPUT));
        assertFalse(JobType.RETRIEVE.allowsTarget(LocationKind.PRODUCTION));
        assertTrue(JobType.SUPPLY.allowsTarget(LocationKind.PRODUCTION));
        assertTrue(JobType.SUPPLY.allowsTarget(LocationKind.STORAGE));
        assertFalse(JobType.SUPPLY.allowsTarget(LocationKind.OUTPUT));
        assertEquals(LocationKind.INPUT, JobType.STORE.fallbackTargetKind());
        assertEquals(LocationKind.STORAGE, JobType.RETRIEVE.fallbackTargetKind());
        assertEquals(LocationKind.OUTPUT, JobType.COLLECT.sourceKind());
        assertEquals(LocationKind.STORAGE, JobType.COLLECT.plannedTargetKind());
        assertEquals(LocationKind.INPUT, JobType.COLLECT.fallbackTargetKind());
        assertEquals(Optional.of(JobType.COLLECT), JobType.byName("COLLECT"), "the stable save name");
        assertEquals(Optional.of(JobType.RETRIEVE), JobType.byName("RETRIEVE"));
        assertEquals(Optional.empty(), JobType.byName("retrieve"));
        assertEquals(Optional.empty(), JobType.byName(null));
    }
}
