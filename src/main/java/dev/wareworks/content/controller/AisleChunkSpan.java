package dev.wareworks.content.controller;

/**
 * Which chunk columns one aisle occupies, as pure integer arithmetic (M19, issue #10, ADR-031).
 * <p>
 * The footprint is the aisle's own world box <b>inflated by one block horizontally</b>: aisle-local
 * {@code x ∈ [-1, length + 1]} and lateral {@code -2..+2}. That one inflation covers every position the chunk-loading
 * feature needs, and nothing is ever searched for:
 * <ul>
 * <li>the controller itself sits at aisle-local {@code x = -1} (it is {@code dock - facing}), which lies
 * <b>outside</b> {@code AisleLayout#bounds()} — and it is the ticket owner, so its own chunk must tick;</li>
 * <li>the dock at {@code x = 0} and the rails at {@code x = 1..length} are the aisle line;</li>
 * <li>rack positions (interfaces, stations, ports, keepers, terminals) are lateral {@code ±1};</li>
 * <li>the inventories behind them, and the machine behind a collecting port, are lateral {@code ±2} — no port reaches
 * further, by construction ({@code WarehouseOutputBlockEntity#attachedPos}, ADR-030);</li>
 * <li>a double chest whose other half pairs one block past either end of the aisle is covered by the {@code x} inflation.</li>
 * </ul>
 * Chunks are columns, so the mast height never costs a chunk.
 * <p>
 * Because the box is an axis-aligned rectangle in world coordinates (the aisle direction is horizontal), the footprint is
 * the rectangle of chunk columns between its corners: de-duplicated by construction and returned sorted by
 * {@code (chunkX, chunkZ)}, so trimming at a cap is reproducible across restarts.
 * <p>
 * <b>Worst case</b> (what the {@code chunkLoading.maxChunksPerAisle} default is derived from): {@code length + 3} blocks
 * along the aisle span at most {@code floor((length + 2) / 16) + 2} columns, and 5 blocks laterally span at most 2, so an
 * aisle needs at most {@code 2 · (floor((length + 2) / 16) + 2)} chunks — 8 at the default
 * {@code aisle.maxAisleLength = 32}, 12 at 64 and 20 at the configurable maximum of 128.
 * <p>
 * Pure integer maths with no Minecraft imports, so it is JUnit-testable; the content layer turns the pairs into chunk keys
 * with {@code ChunkPos#asLong} and never re-implements that packing.
 */
public final class AisleChunkSpan {
    /** How far the aisle box is inflated horizontally, in blocks: the attached inventories and the controller. */
    public static final int INFLATE = 1;
    /** Lateral half-width of a rack plane in blocks (the rack position itself). */
    private static final int RACK_LATERAL = 1;

    private AisleChunkSpan() {
    }

    /**
     * The chunk columns of the aisle, as interleaved {@code x, z} pairs sorted by {@code (chunkX, chunkZ)}.
     *
     * @param dockX  world x of the dock
     * @param dockZ  world z of the dock
     * @param stepX  x step of the aisle direction ({@code -1}, {@code 0} or {@code 1})
     * @param stepZ  z step of the aisle direction; exactly one of the two steps must be non-zero
     * @param length number of rails of the aisle ({@code 0} for an aisle without rails)
     * @return {@code 2 · chunkCount} ints: {@code [x0, z0, x1, z1, ...]}
     * @throws IllegalArgumentException if the direction is not one horizontal unit step or {@code length} is negative
     */
    public static int[] chunks(int dockX, int dockZ, int stepX, int stepZ, int length) {
        int[] box = box(dockX, dockZ, stepX, stepZ, length);
        int minChunkX = box[0] >> 4;
        int maxChunkX = box[1] >> 4;
        int minChunkZ = box[2] >> 4;
        int maxChunkZ = box[3] >> 4;
        int[] result = new int[2 * (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1)];
        int i = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                result[i++] = chunkX;
                result[i++] = chunkZ;
            }
        }
        return result;
    }

    /** How many chunk columns {@link #chunks} would return, without building the array. */
    public static int chunkCount(int dockX, int dockZ, int stepX, int stepZ, int length) {
        int[] box = box(dockX, dockZ, stepX, stepZ, length);
        return ((box[1] >> 4) - (box[0] >> 4) + 1) * ((box[3] >> 4) - (box[2] >> 4) + 1);
    }

    /**
     * The largest number of chunks any aisle of {@code length} rails can need, whatever its direction and alignment. The
     * bound the config comment of {@code chunkLoading.maxChunksPerAisle} quotes.
     */
    public static int worstCaseChunkCount(int length) {
        if (length < 0)
            throw new IllegalArgumentException("length must not be negative: " + length);
        int along = length + 1 + 2 * INFLATE;
        int lateral = 2 * (RACK_LATERAL + INFLATE) + 1;
        return spanColumns(along) * spanColumns(lateral);
    }

    /** The largest number of chunk columns a span of {@code blocks} consecutive blocks can cover. */
    private static int spanColumns(int blocks) {
        return (blocks - 1) / 16 + 2;
    }

    /** {@code [minX, maxX, minZ, maxZ]} of the inflated aisle box in world coordinates. */
    private static int[] box(int dockX, int dockZ, int stepX, int stepZ, int length) {
        if (length < 0)
            throw new IllegalArgumentException("length must not be negative: " + length);
        if (Math.abs(stepX) + Math.abs(stepZ) != 1)
            throw new IllegalArgumentException("aisle direction must be one horizontal unit step: " + stepX + "," + stepZ);
        // Right-hand (clockwise) direction of the aisle: NORTH -> EAST, EAST -> SOUTH, ...
        int rightX = -stepZ;
        int rightZ = stepX;
        int lateral = RACK_LATERAL + INFLATE;
        int minAlong = -INFLATE;
        int maxAlong = length + INFLATE;
        int[] xs = new int[4];
        int[] zs = new int[4];
        int i = 0;
        for (int along : new int[] { minAlong, maxAlong }) {
            for (int side : new int[] { -lateral, lateral }) {
                xs[i] = dockX + along * stepX + side * rightX;
                zs[i] = dockZ + along * stepZ + side * rightZ;
                i++;
            }
        }
        return new int[] { min(xs), max(xs), min(zs), max(zs) };
    }

    private static int min(int[] values) {
        int result = values[0];
        for (int value : values)
            result = Math.min(result, value);
        return result;
    }

    private static int max(int[] values) {
        int result = values[0];
        for (int value : values)
            result = Math.max(result, value);
        return result;
    }
}
