package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link AisleChunkSpan}: the chunk footprint of an aisle (M19, issue #10, ADR-031).
 * <p>
 * The two things that would be silent bugs in the world are pinned here: that the <b>controller</b> at aisle-local
 * {@code x = -1} is inside the set — it is the ticket owner, and it lies outside {@code AisleLayout#bounds()} — and that
 * lateral {@code ±2} is, which is where the inventories behind the rack positions and the machine behind a collecting port
 * live. The worst-case counts are pinned too, because the default of {@code chunkLoading.maxChunksPerAisle} is derived
 * from them.
 */
class AisleChunkSpanTest {
    /** The four horizontal directions as {@code stepX, stepZ}: north, east, south, west. */
    private static final int[][] FACINGS = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };

    @Test
    void oneAisleInsideOneChunkNeedsOneChunk() {
        // Dock at 8,8 with two rails: everything stays inside the chunk 0,0.
        assertEquals(1, AisleChunkSpan.chunkCount(8, 8, 1, 0, 2));
        assertEquals(List.of("0,0"), keys(AisleChunkSpan.chunks(8, 8, 1, 0, 2)));
    }

    @Test
    void theControllerPositionIsAlwaysCovered() {
        for (int[] facing : FACINGS) {
            for (int offset = 0; offset < 16; offset++) {
                int dockX = offset;
                int dockZ = offset;
                Set<String> chunks = new LinkedHashSet<>(keys(AisleChunkSpan.chunks(dockX, dockZ, facing[0], facing[1], 6)));
                // The controller is dock - facing, which is aisle-local x = -1.
                int controllerX = dockX - facing[0];
                int controllerZ = dockZ - facing[1];
                assertTrue(chunks.contains(key(controllerX >> 4, controllerZ >> 4)),
                        "the ticket owner's own chunk must be in its footprint (facing " + facing[0] + "," + facing[1]
                                + ", dock " + dockX + "," + dockZ + ")");
            }
        }
    }

    @Test
    void everyBlockOfTheInflatedAisleBoxIsCovered() {
        for (int[] facing : FACINGS) {
            for (int dockX : new int[] { 0, 15, 16, -1, -16, 100 }) {
                for (int dockZ : new int[] { 0, 15, 16, -1, -16, 100 }) {
                    int length = 20;
                    Set<String> chunks = new LinkedHashSet<>(
                            keys(AisleChunkSpan.chunks(dockX, dockZ, facing[0], facing[1], length)));
                    int rightX = -facing[1];
                    int rightZ = facing[0];
                    for (int along = -1; along <= length + 1; along++) {
                        for (int lateral = -2; lateral <= 2; lateral++) {
                            int x = dockX + along * facing[0] + lateral * rightX;
                            int z = dockZ + along * facing[1] + lateral * rightZ;
                            assertTrue(chunks.contains(key(x >> 4, z >> 4)),
                                    "block " + x + "," + z + " (along " + along + ", lateral " + lateral + ") missing");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theSetIsDeduplicatedAndSortedByChunkXThenZ() {
        int[] chunks = AisleChunkSpan.chunks(0, 0, 1, 0, 40);
        List<String> keys = keys(chunks);
        assertEquals(new LinkedHashSet<>(keys).size(), keys.size(), "no chunk twice");
        for (int i = 2; i < chunks.length; i += 2) {
            int previousX = chunks[i - 2];
            int previousZ = chunks[i - 1];
            assertTrue(chunks[i] > previousX || (chunks[i] == previousX && chunks[i + 1] > previousZ),
                    "not sorted at index " + i + ": " + keys);
        }
    }

    @Test
    void theOrderIsTheSameForTheSameAisle() {
        // Trimming at a cap has to pick the same chunks after a restart, so the order is part of the contract.
        assertEquals(keys(AisleChunkSpan.chunks(37, -83, 0, 1, 17)), keys(AisleChunkSpan.chunks(37, -83, 0, 1, 17)));
    }

    @Test
    void chunkCountAgreesWithTheChunkList() {
        for (int[] facing : FACINGS) {
            for (int length : new int[] { 0, 1, 16, 32, 64, 128 }) {
                for (int dock : new int[] { 0, 7, 15, -8 }) {
                    assertEquals(AisleChunkSpan.chunks(dock, dock, facing[0], facing[1], length).length / 2,
                            AisleChunkSpan.chunkCount(dock, dock, facing[0], facing[1], length));
                }
            }
        }
    }

    @Test
    void anAisleWithoutRailsStillCoversItsControllerAndDock() {
        assertEquals(4, AisleChunkSpan.chunkCount(0, 0, 1, 0, 0),
                "a dock on the corner of four chunks reaches into all of them through the inflation");
        assertEquals(1, AisleChunkSpan.chunkCount(8, 8, 1, 0, 0), "and stays in one chunk in the middle of one");
    }

    @Test
    void theWorstCaseCountsAreTheOnesTheConfigDefaultIsDerivedFrom() {
        assertEquals(8, AisleChunkSpan.worstCaseChunkCount(32), "the default aisle.maxAisleLength");
        assertEquals(12, AisleChunkSpan.worstCaseChunkCount(64));
        assertEquals(20, AisleChunkSpan.worstCaseChunkCount(128), "the configurable maximum");
    }

    @Test
    void noAlignmentEverExceedsTheWorstCase() {
        for (int length : new int[] { 0, 1, 15, 16, 17, 31, 32, 63, 64, 127, 128 }) {
            int worst = AisleChunkSpan.worstCaseChunkCount(length);
            int seen = 0;
            for (int[] facing : FACINGS) {
                for (int dockX = -20; dockX <= 20; dockX++) {
                    for (int dockZ = -20; dockZ <= 20; dockZ++)
                        seen = Math.max(seen, AisleChunkSpan.chunkCount(dockX, dockZ, facing[0], facing[1], length));
                }
            }
            assertTrue(seen <= worst, "length " + length + ": saw " + seen + " chunks, bound is " + worst);
        }
    }

    @Test
    void anImpossibleDirectionOrLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.chunks(0, 0, 0, 0, 4), "no direction");
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.chunks(0, 0, 1, 1, 4), "diagonal");
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.chunks(0, 0, 2, 0, 4), "not a unit step");
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.chunks(0, 0, 1, 0, -1), "negative length");
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.worstCaseChunkCount(-1));
    }

    private static List<String> keys(int[] chunks) {
        List<String> keys = new java.util.ArrayList<>(chunks.length / 2);
        for (int i = 0; i < chunks.length; i += 2)
            keys.add(key(chunks[i], chunks[i + 1]));
        return keys;
    }

    private static String key(int chunkX, int chunkZ) {
        return chunkX + "," + chunkZ;
    }
}
