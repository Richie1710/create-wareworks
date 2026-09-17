package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InventorySnapshotTest {
    private static final int STACK = 64;
    private static final int LIMIT = 99;
    private static final int CHEST_SLOTS = 27;

    private static InventorySnapshot<String> mixed() {
        return InventorySnapshot.<String>builder(6)
                .add("iron", 64, LIMIT, STACK)
                .addEmpty(LIMIT)
                .add("gold", 10, LIMIT, STACK)
                .add("iron", 36, LIMIT, STACK)
                .add("diamond", 10, LIMIT, STACK)
                .addEmpty(LIMIT)
                .build();
    }

    @Test
    void zeroSlotSnapshot() {
        InventorySnapshot<String> snapshot = InventorySnapshot.empty();
        assertEquals(0, snapshot.totalSlots());
        assertEquals(0, snapshot.usedSlots());
        assertEquals(0, snapshot.freeSlots());
        assertTrue(snapshot.isEmpty());
        assertEquals(0, snapshot.totalItems());
        assertEquals(0, snapshot.count("iron"));
        assertTrue(snapshot.totals().isEmpty());
        assertTrue(snapshot.topEntries(3).isEmpty());
        assertEquals(0, snapshot.insertable("iron", STACK));
        assertSame(InventorySnapshot.empty(), InventorySnapshot.of(List.of()));
    }

    /** The O(1) estimate (review: planner cost per candidate) equals the slot-by-slot one for any slot mix. */
    @Test
    void insertableEqualsTheSlotBySlotEstimate() {
        java.util.Random random = new java.util.Random(20260915L);
        String[] keys = {"iron", "gold", "sword"};
        int[] maxStackSizes = {1, 16, STACK};
        for (int round = 0; round < 500; round++) {
            int size = random.nextInt(12);
            boolean uniformLimits = random.nextBoolean();
            InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(size);
            for (int i = 0; i < size; i++) {
                int limit = uniformLimits ? LIMIT : random.nextInt(4) * 40;
                if (random.nextInt(3) == 0) {
                    builder.addEmpty(limit);
                } else {
                    String key = keys[random.nextInt(keys.length)];
                    builder.add(key, 1 + random.nextInt(200), limit, key.equals("sword") ? 1 : STACK);
                }
            }
            InventorySnapshot<String> snapshot = builder.build();
            for (String key : keys) {
                for (int maxStackSize : maxStackSizes)
                    assertEquals(CapacityMath.insertable(snapshot.slots(), key, maxStackSize),
                            snapshot.insertable(key, maxStackSize), () -> key + " into " + snapshot.slots());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> mixed().insertable("iron", 0));
        assertThrows(IllegalArgumentException.class,
                () -> InventorySnapshot.<String>builder(1).add("iron", 1, LIMIT, STACK).build().insertable("iron", 0));
    }

    @Test
    void allEmptySlots() {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(CHEST_SLOTS);
        for (int i = 0; i < CHEST_SLOTS; i++)
            builder.addEmpty(LIMIT);
        InventorySnapshot<String> snapshot = builder.build();
        assertTrue(snapshot.isEmpty());
        assertEquals(CHEST_SLOTS, snapshot.totalSlots());
        assertEquals(CHEST_SLOTS, snapshot.freeSlots());
        assertEquals((long) CHEST_SLOTS * STACK, snapshot.insertable("iron", STACK));
    }

    @Test
    void mixedSlotsTotalsAndCounts() {
        InventorySnapshot<String> snapshot = mixed();
        assertEquals(6, snapshot.totalSlots());
        assertEquals(4, snapshot.usedSlots());
        assertEquals(2, snapshot.freeSlots());
        assertFalse(snapshot.isEmpty());
        assertEquals(120, snapshot.totalItems());
        assertEquals(100, snapshot.count("iron"));
        assertEquals(10, snapshot.count("gold"));
        assertEquals(0, snapshot.count("emerald"));
        assertEquals(List.of("iron", "gold", "diamond"), List.copyOf(snapshot.keys()));
        assertEquals(Map.of("iron", 100L, "gold", 10L, "diamond", 10L), snapshot.totals());
    }

    @Test
    void topEntriesSortedByCountWithStableTies() {
        InventorySnapshot<String> snapshot = mixed();
        assertEquals(List.of(new KeyCount<>("iron", 100), new KeyCount<>("gold", 10), new KeyCount<>("diamond", 10)),
                snapshot.topEntries(3));
        assertEquals(List.of(new KeyCount<>("iron", 100), new KeyCount<>("gold", 10)), snapshot.topEntries(2));
        assertEquals(3, snapshot.topEntries(10).size());
        assertTrue(snapshot.topEntries(0).isEmpty());
        assertTrue(snapshot.topEntries(-1).isEmpty());
    }

    @Test
    void capacityDelegates() {
        InventorySnapshot<String> snapshot = mixed();
        // iron: 0 in the full slot, 28 in the 36-slot, 64 in each of the two empty slots
        assertEquals(28 + 2L * STACK, snapshot.insertable("iron", STACK));
        assertEquals(2L * STACK, snapshot.insertable("emerald", STACK));
        assertEquals(100, snapshot.extractable("iron"));
    }

    @Test
    void hugeCountsAboveContainerLimits() {
        int drawerLimit = 1 << 20;
        InventorySnapshot<String> snapshot = InventorySnapshot.<String>builder(3)
                .add("cobblestone", 500_000, drawerLimit, STACK)
                .add("cobblestone", 1_000_000, drawerLimit, STACK)
                .add("dirt", 150, LIMIT, STACK)
                .build();
        assertEquals(1_500_000, snapshot.count("cobblestone"));
        assertEquals(1_500_150, snapshot.totalItems());
        assertEquals((drawerLimit - 500_000L) + (drawerLimit - 1_000_000L), snapshot.insertable("cobblestone", STACK));
        assertEquals(new KeyCount<>("cobblestone", 1_500_000), snapshot.topEntries(1).getFirst());
    }

    @Test
    void totalsBeyondIntRange() {
        List<SlotView<String>> slots = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            slots.add(SlotView.of("cobblestone", Integer.MAX_VALUE, Integer.MAX_VALUE, STACK));
        InventorySnapshot<String> snapshot = InventorySnapshot.of(slots);
        assertEquals(3L * Integer.MAX_VALUE, snapshot.count("cobblestone"));
        assertEquals(3L * Integer.MAX_VALUE, snapshot.totalItems());
    }

    @Test
    void snapshotIsImmutable() {
        List<SlotView<String>> source = new ArrayList<>(List.of(SlotView.of("iron", 1, LIMIT, STACK)));
        InventorySnapshot<String> snapshot = InventorySnapshot.of(source);
        source.add(SlotView.of("gold", 1, LIMIT, STACK));
        assertEquals(1, snapshot.totalSlots());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.slots().add(SlotView.empty(LIMIT)));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.totals().put("gold", 5L));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.topEntries(1).add(new KeyCount<>("x", 1)));
    }

    @Test
    void equalityBySlots() {
        assertEquals(mixed(), mixed());
        assertEquals(mixed().hashCode(), mixed().hashCode());
        InventorySnapshot<String> changed = InventorySnapshot.<String>builder(1).add("iron", 63, LIMIT, STACK).build();
        assertNotEquals(mixed(), changed);
    }

    @Test
    void slotAccessByIndex() {
        InventorySnapshot<String> snapshot = mixed();
        assertEquals("gold", snapshot.slot(2).key());
        assertTrue(snapshot.slot(1).isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.slot(6));
    }

    @Test
    void keyCountValidation() {
        assertThrows(NullPointerException.class, () -> new KeyCount<String>(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new KeyCount<>("iron", -1));
    }
}
