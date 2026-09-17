package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RefusalMemoryTest {
    private static final long TTL = 10;
    private static final int MAX_ENTRIES = 64;
    private static final String CHEST = "chest";
    private static final String BARREL = "barrel";
    private static final String IRON = "iron";
    private static final String GOLD = "gold";

    private final RefusalMemory<String, String> memory = new RefusalMemory<>(TTL, MAX_ENTRIES);

    @Test
    void remembersUntilTheTtlEnds() {
        memory.record(CHEST, IRON, 100);
        assertTrue(memory.isRefused(CHEST, IRON, 100));
        assertTrue(memory.isRefused(CHEST, IRON, 100 + TTL - 1));
        assertEquals(1, memory.size());
        assertFalse(memory.isRefused(CHEST, IRON, 100 + TTL));
        assertEquals(0, memory.size(), "an expired refusal is forgotten when it is queried");
    }

    @Test
    void recordingAgainRestartsTheTtl() {
        memory.record(CHEST, IRON, 100);
        memory.record(CHEST, IRON, 105);
        assertEquals(1, memory.size());
        assertTrue(memory.isRefused(CHEST, IRON, 100 + TTL));
    }

    @Test
    void locationsAndKeysAreSeparate() {
        memory.record(CHEST, IRON, 0);
        assertFalse(memory.isRefused(CHEST, GOLD, 0));
        assertFalse(memory.isRefused(BARREL, IRON, 0));
    }

    @Test
    void forgetEndsEveryRefusalOfALocation() {
        memory.record(CHEST, IRON, 0);
        memory.record(CHEST, GOLD, 0);
        memory.record(BARREL, IRON, 0);
        memory.forget(CHEST);
        assertEquals(1, memory.size());
        assertFalse(memory.isRefused(CHEST, IRON, 0));
        assertFalse(memory.isRefused(CHEST, GOLD, 0));
        assertTrue(memory.isRefused(BARREL, IRON, 0));
        memory.forget(CHEST);
        assertEquals(1, memory.size(), "forgetting twice changes nothing");
    }

    @Test
    void aFullMemoryStartsOver() {
        RefusalMemory<String, String> small = new RefusalMemory<>(TTL, 2);
        small.record(CHEST, IRON, 0);
        small.record(BARREL, IRON, 0);
        small.record(CHEST, IRON, 1);
        assertEquals(2, small.size(), "an existing entry does not count twice");
        small.record(CHEST, GOLD, 1);
        assertEquals(1, small.size());
        assertFalse(small.isRefused(BARREL, IRON, 1));
        assertTrue(small.isRefused(CHEST, GOLD, 1));
        small.clear();
        assertEquals(0, small.size());
    }

    @Test
    void aClockThatGoesBackwardsExpires() {
        memory.record(CHEST, IRON, 100);
        assertFalse(memory.isRefused(CHEST, IRON, 50));
        assertEquals(0, memory.size());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RefusalMemory<String, String>(0, MAX_ENTRIES));
        assertThrows(IllegalArgumentException.class, () -> new RefusalMemory<String, String>(TTL, 0));
        assertThrows(NullPointerException.class, () -> memory.record(null, IRON, 0));
        assertThrows(NullPointerException.class, () -> memory.record(CHEST, null, 0));
    }
}
