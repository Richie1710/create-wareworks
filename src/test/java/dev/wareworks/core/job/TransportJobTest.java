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
        assertThrows(IllegalArgumentException.class, () -> store.withTarget("out", LocationKind.OUTPUT));
    }

    @Test
    void invalidJobsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TransportJob.store(JOB, "in", "chest", ORE, 0));
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.STORE, "in", "chest",
                LocationKind.STORAGE, ORE, 4, Optional.of(REQUEST), false, 0, 0), "store jobs serve no request");
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.STORE, "in", "out",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.RETRIEVE, "chest", "out",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), false, 2, 0), "unpicked jobs hold nothing");
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.RETRIEVE, "chest", "out",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), true, 5, 0), "picked more than planned");
        assertThrows(IllegalArgumentException.class, () -> new TransportJob<>(JOB, JobType.RETRIEVE, "chest", "out",
                LocationKind.OUTPUT, ORE, 4, Optional.empty(), true, 3, 4), "delivered more than picked");
        assertThrows(NullPointerException.class, () -> TransportJob.store(JOB, null, "chest", ORE, 1));
    }

    @Test
    void jobTypeEndpoints() {
        assertTrue(JobType.STORE.allowsTarget(LocationKind.STORAGE));
        assertTrue(JobType.STORE.allowsTarget(LocationKind.INPUT));
        assertFalse(JobType.STORE.allowsTarget(LocationKind.OUTPUT));
        assertTrue(JobType.RETRIEVE.allowsTarget(LocationKind.OUTPUT));
        assertTrue(JobType.RETRIEVE.allowsTarget(LocationKind.STORAGE));
        assertFalse(JobType.RETRIEVE.allowsTarget(LocationKind.INPUT));
        assertEquals(LocationKind.INPUT, JobType.STORE.fallbackTargetKind());
        assertEquals(LocationKind.STORAGE, JobType.RETRIEVE.fallbackTargetKind());
        assertEquals(Optional.of(JobType.RETRIEVE), JobType.byName("RETRIEVE"));
        assertEquals(Optional.empty(), JobType.byName("retrieve"));
        assertEquals(Optional.empty(), JobType.byName(null));
    }
}
