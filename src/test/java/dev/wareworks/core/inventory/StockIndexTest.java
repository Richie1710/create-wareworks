package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StockIndexTest {
    private static final int STACK = 64;
    private static final int LIMIT = 99;

    /** Builds a snapshot from key/count pairs, one slot each. */
    private static InventorySnapshot<String> snapshot(Object... keyCountPairs) {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(keyCountPairs.length / 2);
        for (int i = 0; i < keyCountPairs.length; i += 2)
            builder.add((String) keyCountPairs[i], (Integer) keyCountPairs[i + 1], LIMIT, STACK);
        return builder.build();
    }

    private static <L> List<LocationCount<L>> counts(Object... locationCountPairs) {
        List<LocationCount<L>> result = new ArrayList<>();
        for (int i = 0; i < locationCountPairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            L location = (L) locationCountPairs[i];
            result.add(new LocationCount<>(location, ((Number) locationCountPairs[i + 1]).longValue()));
        }
        return result;
    }

    @Test
    void emptyIndex() {
        StockIndex<String, String> index = new StockIndex<>();
        assertEquals(0, index.totalItems());
        assertEquals(0, index.distinctKeys());
        assertEquals(0, index.locationCount());
        assertEquals(0, index.occupiedLocations());
        assertEquals(0, index.count("iron"));
        assertEquals(0, index.countAt("iron", "A"));
        assertTrue(index.locationsOf("iron").isEmpty());
        assertTrue(index.keys().isEmpty());
        assertTrue(index.locations().isEmpty());
        assertEquals(Optional.empty(), index.snapshotOf("A"));
        assertFalse(index.contains("A"));
        assertFalse(index.remove("A"), "removing an unknown location");
    }

    @Test
    void firstUpdateAddsStock() {
        StockIndex<String, String> index = new StockIndex<>();
        InventorySnapshot<String> chest = snapshot("iron", 64, "gold", 10, "iron", 36);
        assertTrue(index.update("A", chest));
        assertEquals(100, index.count("iron"));
        assertEquals(10, index.count("gold"));
        assertEquals(110, index.totalItems());
        assertEquals(2, index.distinctKeys());
        assertEquals(100, index.countAt("iron", "A"));
        assertEquals(counts("A", 100), index.locationsOf("iron"));
        assertSame(chest, index.snapshotOf("A").orElseThrow());
        assertTrue(index.contains("A"));
        assertEquals(List.of("A"), index.locations());
    }

    @Test
    void updatesApplyOnlyTheDifference() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("A", snapshot("iron", 50, "gold", 10));
        index.update("B", snapshot("iron", 5));

        assertTrue(index.update("A", snapshot("iron", 20, "diamond", 3)), "iron down, gold gone, diamond new");
        assertEquals(25, index.count("iron"));
        assertEquals(0, index.count("gold"));
        assertEquals(3, index.count("diamond"));
        assertEquals(28, index.totalItems());
        assertFalse(index.keys().contains("gold"), "zero totals are pruned");
        assertTrue(index.locationsOf("gold").isEmpty(), "zero locations are pruned");
        assertEquals(counts("A", 20, "B", 5), index.locationsOf("iron"));
        assertEquals(0, index.countAt("gold", "A"));
    }

    @Test
    void unchangedCountsReportNoChangeButStoreTheSnapshot() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("A", snapshot("iron", 64, "iron", 36));
        InventorySnapshot<String> rearranged = InventorySnapshot.<String>builder(3)
                .addEmpty(LIMIT)
                .add("iron", 100, LIMIT * 2, STACK)
                .addEmpty(LIMIT)
                .build();
        assertFalse(index.update("A", rearranged), "same totals, different slot layout");
        assertSame(rearranged, index.snapshotOf("A").orElseThrow(), "the newer snapshot is kept");
        assertFalse(index.update("A", rearranged), "identical update");
        assertEquals(100, index.count("iron"));
    }

    @Test
    void emptySnapshotsKeepTheLocation() {
        StockIndex<String, String> index = new StockIndex<>();
        assertFalse(index.update("A", InventorySnapshot.empty()), "a new empty location changes no count");
        assertTrue(index.contains("A"));
        assertEquals(1, index.locationCount());
        assertEquals(0, index.distinctKeys());

        index.update("A", snapshot("iron", 7));
        assertTrue(index.update("A", InventorySnapshot.empty()), "stock removed by an empty snapshot");
        assertTrue(index.contains("A"), "still a location, just empty");
        assertEquals(0, index.totalItems());
        assertTrue(index.keys().isEmpty());
    }

    /**
     * {@code occupiedLocations} counts the locations that hold something, for the "used / total" line of a display:
     * it must follow a location filling up and emptying again, a removal and a restore from a save, and it must not
     * count a location that reads an inventory another one counts (a shared inventory gets an empty snapshot).
     */
    @Test
    void occupiedLocationsFollowStock() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("A", InventorySnapshot.empty());
        assertEquals(1, index.locationCount());
        assertEquals(0, index.occupiedLocations(), "an empty location is indexed but not occupied");

        index.update("A", snapshot("iron", 7));
        assertEquals(1, index.occupiedLocations());
        index.update("A", snapshot("iron", 3, "gold", 1));
        assertEquals(1, index.occupiedLocations(), "a second key does not count the location twice");

        index.update("B", snapshot("gold", 2));
        index.update("C", InventorySnapshot.empty());
        assertEquals(3, index.locationCount());
        assertEquals(2, index.occupiedLocations(), "the alias of a shared inventory holds nothing of its own");

        index.update("A", InventorySnapshot.empty());
        assertEquals(1, index.occupiedLocations(), "an emptied location stops counting");
        assertTrue(index.remove("B"));
        assertEquals(0, index.occupiedLocations());
        assertFalse(index.remove("B"), "a second removal changes nothing");
        assertEquals(0, index.occupiedLocations());

        assertTrue(index.restore("A", Map.of("clay", 4L)), "counts from a save");
        assertEquals(1, index.occupiedLocations());
        assertTrue(index.restore("A", Map.of()), "restoring nothing empties the location");
        assertEquals(0, index.occupiedLocations());
        assertEquals(2, index.locationCount(), "A and C are still indexed");
    }

    @Test
    void removeDropsAllStockOfALocation() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("A", snapshot("iron", 10, "gold", 4));
        index.update("B", snapshot("iron", 6));
        assertTrue(index.remove("A"));
        assertFalse(index.contains("A"));
        assertEquals(6, index.count("iron"));
        assertEquals(0, index.count("gold"));
        assertEquals(6, index.totalItems());
        assertEquals(1, index.distinctKeys());
        assertEquals(counts("B", 6), index.locationsOf("iron"));
        assertEquals(Optional.empty(), index.snapshotOf("A"));
        assertFalse(index.remove("A"), "second removal");
    }

    @Test
    void locationsKeepRegistrationOrderAcrossKeyChurn() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("C", snapshot("iron", 1));
        index.update("A", snapshot("iron", 2));
        index.update("B", snapshot("iron", 3));
        assertEquals(counts("C", 1, "A", 2, "B", 3), index.locationsOf("iron"));

        index.update("A", snapshot("gold", 1));
        index.update("A", snapshot("iron", 9));
        assertEquals(counts("C", 1, "A", 9, "B", 3), index.locationsOf("iron"),
                "a location keeps its place when a key leaves and returns");
        assertEquals(List.of("C", "A", "B"), index.locations());

        index.remove("C");
        index.update("C", snapshot("iron", 1));
        assertEquals(counts("A", 9, "B", 3, "C", 1), index.locationsOf("iron"), "re-added locations go last");
    }

    @Test
    void comparatorOrdersLocations() {
        StockIndex<String, Integer> index = new StockIndex<>(Comparator.reverseOrder());
        index.update(2, snapshot("iron", 2));
        index.update(7, snapshot("iron", 7));
        index.update(4, snapshot("iron", 4));
        assertEquals(counts(7, 7, 4, 4, 2, 2), index.locationsOf("iron"));
        assertEquals(List.of(7, 4, 2), index.locations());

        StockIndex<String, String> byLength = new StockIndex<>(Comparator.comparingInt(String::length));
        byLength.update("bb", snapshot("iron", 1));
        byLength.update("aa", snapshot("iron", 1));
        byLength.update("c", snapshot("iron", 1));
        assertEquals(List.of("c", "bb", "aa"), byLength.locations(), "comparator ties keep registration order");
    }

    @Test
    void countsExceedIntRange() {
        StockIndex<String, Integer> index = new StockIndex<>();
        int locations = 3;
        for (int i = 0; i < locations; i++)
            index.update(i, InventorySnapshot.<String>builder(1)
                    .add("cobblestone", Integer.MAX_VALUE, Integer.MAX_VALUE, STACK).build());
        assertEquals((long) Integer.MAX_VALUE * locations, index.count("cobblestone"));
        assertEquals((long) Integer.MAX_VALUE * locations, index.totalItems());
    }

    @Test
    void manyLocationsStayConsistentWithRecomputation() {
        int locations = 400;
        int keys = 12;
        int updates = 5000;
        Random random = new Random(20260915L);
        StockIndex<String, Integer> index = new StockIndex<>();
        Map<Integer, InventorySnapshot<String>> truth = new HashMap<>();

        for (int step = 0; step < updates; step++) {
            int location = random.nextInt(locations);
            if (random.nextInt(10) == 0) {
                assertEquals(truth.remove(location) != null, index.remove(location), "remove result");
                continue;
            }
            int slots = random.nextInt(6);
            InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(slots);
            for (int s = 0; s < slots; s++) {
                if (random.nextInt(4) == 0)
                    builder.addEmpty(LIMIT);
                else
                    builder.add("key" + random.nextInt(keys), 1 + random.nextInt(STACK), LIMIT, STACK);
            }
            InventorySnapshot<String> next = builder.build();
            InventorySnapshot<String> previous = truth.put(location, next);
            boolean expectedChange = !(previous == null ? Map.of() : previous.totals()).equals(next.totals());
            assertEquals(expectedChange, index.update(location, next), "update result at step " + step);
        }

        Map<String, Map<Integer, Long>> expected = new HashMap<>();
        long expectedTotal = 0;
        for (Map.Entry<Integer, InventorySnapshot<String>> entry : truth.entrySet()) {
            for (Map.Entry<String, Long> stored : entry.getValue().totals().entrySet()) {
                expected.computeIfAbsent(stored.getKey(), k -> new HashMap<>()).put(entry.getKey(), stored.getValue());
                expectedTotal += stored.getValue();
            }
        }
        assertEquals(expectedTotal, index.totalItems());
        assertEquals(expected.keySet(), index.keys());
        assertEquals(expected.size(), index.distinctKeys());
        assertEquals(truth.size(), index.locationCount());
        for (int k = 0; k < keys; k++) {
            String key = "key" + k;
            Map<Integer, Long> perLocation = expected.getOrDefault(key, Map.of());
            assertEquals(perLocation.values().stream().mapToLong(Long::longValue).sum(), index.count(key), key);
            List<LocationCount<Integer>> listed = index.locationsOf(key);
            assertEquals(perLocation.size(), listed.size(), key);
            for (LocationCount<Integer> entry : listed) {
                assertEquals(perLocation.get(entry.location()), entry.count(), key + " at " + entry.location());
                assertEquals(entry.count(), index.countAt(key, entry.location()));
            }
        }
        for (Map.Entry<Integer, InventorySnapshot<String>> entry : truth.entrySet())
            assertSame(entry.getValue(), index.snapshotOf(entry.getKey()).orElseThrow());
    }

    @Test
    void clearResetsEverything() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("A", snapshot("iron", 3));
        index.update("B", snapshot("gold", 3));
        index.clear();
        assertEquals(0, index.totalItems());
        assertEquals(0, index.distinctKeys());
        assertEquals(0, index.locationCount());
        assertEquals(0, index.occupiedLocations());
        assertTrue(index.locationsOf("iron").isEmpty());
        index.update("B", snapshot("iron", 1));
        index.update("A", snapshot("iron", 1));
        assertEquals(List.of("B", "A"), index.locations(), "order restarts after clear");
    }

    @Test
    void viewsAreUnmodifiableAndNullsAreRejected() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("A", snapshot("iron", 3));
        assertThrows(UnsupportedOperationException.class, () -> index.keys().remove("iron"));
        assertThrows(UnsupportedOperationException.class, () -> index.locations().clear());
        assertThrows(UnsupportedOperationException.class, () -> index.locationsOf("iron").clear());
        assertThrows(NullPointerException.class, () -> index.update(null, snapshot()));
        assertThrows(NullPointerException.class, () -> index.update("A", null));
        assertThrows(NullPointerException.class, () -> index.remove(null));
        assertThrows(NullPointerException.class, () -> index.count(null));
        assertThrows(NullPointerException.class, () -> index.locationsOf(null));
        assertThrows(NullPointerException.class, () -> new StockIndex<String, String>(null));
        assertThrows(IllegalArgumentException.class, () -> new LocationCount<>("A", 0));
        assertThrows(NullPointerException.class, () -> new LocationCount<String>(null, 1));
    }

    @Test
    void restoreSetsCountsWithoutASnapshot() {
        StockIndex<String, String> index = new StockIndex<>();
        index.update("A", snapshot("iron", 10));
        assertTrue(index.restore("B", Map.of("iron", 5_000_000_000L, "gold", 3L)), "restored counts are new stock");
        assertEquals(5_000_000_010L, index.count("iron"));
        assertEquals(Optional.empty(), index.snapshotOf("B"), "restored counts carry no slot information");
        assertEquals(Map.of("iron", 5_000_000_000L, "gold", 3L), index.countsAt("B"));
        assertTrue(index.contains("B"));
        assertEquals(List.of("A", "B"), index.locations());

        assertTrue(index.update("B", snapshot("gold", 4)), "a snapshot replaces restored counts by difference");
        assertEquals(10L, index.count("iron"));
        assertEquals(4L, index.count("gold"));
        assertTrue(index.snapshotOf("B").isPresent());

        assertFalse(index.restore("A", Map.of("iron", 10L, "dirt", 0L)), "same counts, zero entries ignored");
        assertEquals(Optional.empty(), index.snapshotOf("A"));
        assertEquals(Map.of("iron", 10L), index.countsAt("A"));
        assertEquals(Map.of(), index.countsAt("missing"));

        assertThrows(IllegalArgumentException.class, () -> index.restore("C", Map.of("iron", -1L)));
        assertFalse(index.contains("C"), "a rejected restore adds no location");
        assertTrue(index.restore("B", Map.of()), "restoring nothing empties the location");
        assertEquals(0L, index.count("gold"));
        assertTrue(index.contains("B"), "an emptied location stays indexed");
        assertTrue(index.remove("B"));
        assertEquals(10L, index.totalItems());
    }

    @Test
    void readOnlyViewReflectsTheIndex() {
        StockIndex<String, String> index = new StockIndex<>(Comparator.reverseOrder());
        StockView<String, String> view = index.readOnlyView();
        assertFalse(view instanceof StockIndex, "the view cannot be cast back to the index");
        assertSame(view, index.readOnlyView(), "one view per index");

        index.update("A", snapshot("iron", 3));
        index.update("B", snapshot("iron", 2, "gold", 1));
        assertEquals(5L, view.count("iron"));
        assertEquals(2L, view.countAt("iron", "B"));
        assertEquals(counts("B", 2, "A", 3), view.locationsOf("iron"));
        assertEquals(List.of("B", "A"), view.locations());
        assertEquals(6L, view.totalItems());
        assertEquals(2, view.distinctKeys());
        assertEquals(Set.of("iron", "gold"), view.keys());
        assertTrue(view.contains("A"));
        assertEquals(2, view.locationCount());
        assertEquals(2, view.occupiedLocations());
        assertEquals(index.snapshotOf("A"), view.snapshotOf("A"));
        assertEquals(Map.of("iron", 2L, "gold", 1L), view.countsAt("B"));
        assertThrows(UnsupportedOperationException.class, () -> view.countsAt("B").clear());

        index.remove("A");
        assertEquals(2L, view.count("iron"), "later changes are visible");
        assertFalse(view.contains("A"));
    }
}
