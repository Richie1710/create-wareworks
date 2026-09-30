package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chunk footprint of a warehouse with more than one aisle ({@link AisleChunkSpan#networkChunks}, M19 + ADR-033):
 * the de-duplicated union of the aisles' own footprints, in a reproducible order.
 */
class NetworkChunkSpanTest {
    /** One straight aisle of 32 rails at the chunk corner: the shape the old default of 8 was derived from. */
    private static final int[] STRAIGHT_32 = { 0, 0, 1, 0, 32 };
    /** An L: 32 rails east, then 16 south from the corner. */
    private static final int[] L_32_16 = { 0, 0, 1, 0, 32, 32, 0, 0, 1, 16 };
    /** An L of two aisles at the default length cap. */
    private static final int[] L_32_32 = { 0, 0, 1, 0, 32, 32, 0, 0, 1, 32 };
    /** A U: east, south, west, all 32. */
    private static final int[] U_32_32_32 = { 0, 0, 1, 0, 32, 32, 0, 0, 1, 32, 32, 32, -1, 0, 32 };
    /** Rails of one aisle of {@link #snake()}; 16 of them are {@code aisle.maxNetworkRails = 256}. */
    private static final int SNAKE_RAILS = 16;
    /** Aisles of {@link #snake()}, which is {@code aisle.maxBranches = 16}. */
    private static final int SNAKE_AISLES = 16;

    @Test
    @DisplayName("a warehouse of one aisle gets literally the footprint that aisle always had")
    void oneAisleIsUnchanged() {
        for (int length : new int[] { 0, 1, 7, 32, 64 }) {
            int[] single = AisleChunkSpan.chunks(100, -40, 1, 0, length);
            assertArrayEquals(single, AisleChunkSpan.networkChunks(new int[] { 100, -40, 1, 0, length }),
                    "length " + length);
        }
    }

    @Test
    @DisplayName("two aisles that share chunks count them once")
    void sharedChunksAreCountedOnce() {
        // An L inside one chunk: both legs cover the same columns.
        int[] network = AisleChunkSpan.networkChunks(new int[] { 8, 8, 1, 0, 4, 12, 8, 0, 1, 4 });
        assertEquals(distinct(network), network.length / 2, "no column appears twice");
        int[] first = AisleChunkSpan.chunks(8, 8, 1, 0, 4);
        int[] second = AisleChunkSpan.chunks(12, 8, 0, 1, 4);
        Set<Long> union = new HashSet<>();
        addAll(union, first);
        addAll(union, second);
        assertEquals(union.size(), network.length / 2, "exactly the union of the two");
    }

    @Test
    @DisplayName("the union really is a union: every chunk of every aisle is in it")
    void everyAislesChunksAreIncluded() {
        int[] branches = { 0, 0, 1, 0, 20, 20, 0, 0, 1, 20, 20, 20, -1, 0, 20 };
        Set<Long> network = new HashSet<>();
        addAll(network, AisleChunkSpan.networkChunks(branches));
        for (int i = 0; i < branches.length; i += AisleChunkSpan.INTS_PER_BRANCH) {
            int[] own = AisleChunkSpan.chunks(branches[i], branches[i + 1], branches[i + 2], branches[i + 3],
                    branches[i + 4]);
            Set<Long> columns = new HashSet<>();
            addAll(columns, own);
            assertTrue(network.containsAll(columns), "aisle at index " + i);
        }
    }

    @Test
    @DisplayName("the order is by (chunkX, chunkZ), so trimming at a cap is the same after a restart")
    void theOrderIsReproducible() {
        int[] chunks = AisleChunkSpan.networkChunks(new int[] { 0, 0, 1, 0, 40, 40, 0, 0, -1, 40 });
        for (int i = 2; i < chunks.length; i += 2) {
            int previousX = chunks[i - 2];
            int previousZ = chunks[i - 1];
            assertTrue(chunks[i] > previousX || (chunks[i] == previousX && chunks[i + 1] > previousZ),
                    "ascending at " + i + ": (" + previousX + "," + previousZ + ") then ("
                            + chunks[i] + "," + chunks[i + 1] + ")");
        }
    }

    @Test
    @DisplayName("negative coordinates sort before positive ones, not after")
    void negativeColumnsSortFirst() {
        int[] chunks = AisleChunkSpan.networkChunks(new int[] { 0, 0, 1, 0, 2, 0, 0, 0, -1, 40 });
        assertTrue(chunks[1] < 0, "the first column is the most negative z: " + chunks[1]);
    }

    // --- the arithmetic behind chunkLoading.maxChunksPerAisle (M21) -------------------------------------------------

    /**
     * The table the config comment of {@code chunkLoading.maxChunksPerAisle} quotes, pinned so the comment cannot go
     * stale. Every shape here uses the default {@code aisle.maxAisleLength = 32} for its longest aisle, which is the
     * point: the old default of 8 was <b>exactly</b> one straight aisle's worst case at that length cap, and a
     * warehouse of several aisles is simply not bounded by it.
     */
    @Test
    @DisplayName("the shapes the config comment quotes need exactly the chunks it says they do")
    void theConfigCommentsTableIsTrue() {
        assertEquals(8, count(STRAIGHT_32), "one straight aisle of 32 rails - the old default");
        assertEquals(10, count(L_32_16), "an L of 32 + 16 rails - the new default");
        assertEquals(12, count(L_32_32), "an L of two full 32-rail aisles");
        assertEquals(16, count(U_32_32_32), "a U of three");
        assertEquals(36, count(snake()), "the widest chain aisle.maxNetworkRails = 256 allows");
    }

    /**
     * Why the cap had to be raised at all: at one and the same {@code aisle.maxAisleLength}, a warehouse that bends
     * covers more chunk columns than any single aisle can. A corner replaces one long rectangle with two shorter ones
     * at right angles, and two rectangles at right angles cover more columns than one of them.
     */
    @Test
    @DisplayName("a warehouse that bends needs more chunks than a single aisle of the same length cap ever can")
    void bendingNeedsMoreThanTheLongestSingleAisle() {
        int longestSingleAisle = AisleChunkSpan.worstCaseChunkCount(32);
        assertEquals(8, longestSingleAisle, "the bound the old default was derived from");
        assertTrue(count(L_32_16) > longestSingleAisle, "an L of 32 + 16 already exceeds it: " + count(L_32_16));
        assertTrue(count(L_32_32) > count(L_32_16), "and a longer second aisle needs more again");
        assertTrue(count(U_32_32_32) > count(L_32_32), "and a third aisle more again");
    }

    /** No alignment of these shapes exceeds the pinned numbers: the table is a worst case, not one lucky offset. */
    @Test
    @DisplayName("no alignment of a quoted shape needs more chunks than the table says")
    void noAlignmentExceedsTheTable() {
        for (int[] shape : new int[][] { STRAIGHT_32, L_32_16, L_32_32, U_32_32_32, snake() }) {
            int pinned = count(shape);
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++)
                    assertTrue(count(shifted(shape, dx, dz)) <= pinned,
                            "offset " + dx + "," + dz + " of a " + (shape.length / AisleChunkSpan.INTS_PER_BRANCH)
                                    + " aisle warehouse needs " + count(shifted(shape, dx, dz)) + " > " + pinned);
            }
        }
    }

    /**
     * The default of {@code chunkLoading.maxChunksPerAisle} is 10 and covers exactly what the config comment claims:
     * every straight aisle up to the default length cap, and a first corner — not an L of two full-length aisles.
     * <p>
     * The number lives in {@code WareworksConfig}, which needs Minecraft to load; this pins the arithmetic the choice
     * rests on, so a later change to the default meets a test that says what it is giving up.
     */
    @Test
    @DisplayName("the default of 10 covers every straight aisle and a first corner, and not more")
    void theDefaultCoversAStraightAisleAndAFirstCorner() {
        int shipped = 10;
        for (int length = 0; length <= 32; length++)
            assertTrue(AisleChunkSpan.worstCaseChunkCount(length) <= shipped,
                    "a straight aisle of " + length + " rails needs " + AisleChunkSpan.worstCaseChunkCount(length));
        assertTrue(count(L_32_16) <= shipped, "and an L of 32 + 16 rails fits");
        assertTrue(count(L_32_32) > shipped, "while an L of two full 32-rail aisles does not, which the comment says");
    }

    @Test
    @DisplayName("a branch list that is not a whole number of aisles is refused rather than read past its end")
    void aMalformedBranchListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.networkChunks(new int[] { 0, 0, 1, 0 }));
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.networkChunks(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> AisleChunkSpan.networkChunks(null));
    }

    /**
     * The worst chain the default caps allow: {@value #SNAKE_AISLES} aisles of {@value #SNAKE_RAILS} rails turning
     * alternately right and left, which walks the diagonal and so spreads over more chunk columns than any spiral or
     * comb of the same rail count (checked exhaustively over all 2^15 turn patterns of this shape).
     */
    private static int[] snake() {
        int[] branches = new int[SNAKE_AISLES * AisleChunkSpan.INTS_PER_BRANCH];
        int x = 0;
        int z = 0;
        int stepX = 1;
        int stepZ = 0;
        for (int i = 0; i < SNAKE_AISLES; i++) {
            int at = i * AisleChunkSpan.INTS_PER_BRANCH;
            branches[at] = x;
            branches[at + 1] = z;
            branches[at + 2] = stepX;
            branches[at + 3] = stepZ;
            branches[at + 4] = SNAKE_RAILS;
            x += stepX * SNAKE_RAILS;
            z += stepZ * SNAKE_RAILS;
            // Right, then left, then right again: the staircase.
            int turnedX = i % 2 == 0 ? -stepZ : stepZ;
            int turnedZ = i % 2 == 0 ? stepX : -stepX;
            stepX = turnedX;
            stepZ = turnedZ;
        }
        return branches;
    }

    /** How many chunk columns this warehouse needs. */
    private static int count(int[] branches) {
        return AisleChunkSpan.networkChunks(branches).length / 2;
    }

    /** The same warehouse moved by {@code (dx, dz)} blocks, which changes only how it sits inside its chunks. */
    private static int[] shifted(int[] branches, int dx, int dz) {
        int[] moved = branches.clone();
        for (int i = 0; i < moved.length; i += AisleChunkSpan.INTS_PER_BRANCH) {
            moved[i] += dx;
            moved[i + 1] += dz;
        }
        return moved;
    }

    private static int distinct(int[] chunks) {
        Set<Long> columns = new HashSet<>();
        addAll(columns, chunks);
        return columns.size();
    }

    private static void addAll(Set<Long> columns, int[] chunks) {
        for (int i = 0; i < chunks.length; i += 2)
            columns.add(((long) chunks[i] << 32) | (chunks[i + 1] & 0xFFFFFFFFL));
    }
}
