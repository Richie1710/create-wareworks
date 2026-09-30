package dev.wareworks.content.controller;

/**
 * Which chunk columns one aisle occupies, as pure integer arithmetic (M19, issue #10, ADR-031).
 * <p>
 * The footprint is the aisle's own world box <b>inflated by one block horizontally</b>: aisle-local
 * {@code x ∈ [-1, length + 1]} and lateral {@code -2..+2}. That one inflation covers every position the chunk-loading
 * feature needs, and nothing is ever searched for:
 * <ul>
 * <li>the controller itself sits at aisle-local {@code x = -1} (it is {@code dock - facing}), which lies
 * <b>outside</b> {@code BranchLayout#bounds()} — and it is the ticket owner, so its own chunk must tick;</li>
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
 * <b>Worst case of one aisle</b>: {@code length + 3} blocks along the aisle span at most
 * {@code floor((length + 2) / 16) + 2} columns, and 5 blocks laterally span at most 2, so an aisle needs at most
 * {@code 2 · (floor((length + 2) / 16) + 2)} chunks — 8 at the default {@code aisle.maxAisleLength = 32}, 12 at 64 and
 * 20 at the configurable maximum of 128.
 * <p>
 * <b>Worst case of a warehouse</b> ({@link #networkChunks}) has no closed form, because the union depends on how the
 * aisles fold: {@code Σ_b worstCaseChunkCount(L_b)} is an upper bound and a loose one, since consecutive branches
 * always share the chunk of their corner. What matters for the cap is that a warehouse which <b>bends</b> needs more
 * columns than a single aisle of the same length cap ever could — a corner turns one long rectangle into two shorter
 * ones at right angles — so {@code chunkLoading.maxChunksPerAisle} cannot be derived from
 * {@code aisle.maxAisleLength} alone any more. The numbers the config comment quotes for the default length cap are
 * pinned in {@code NetworkChunkSpanTest}: 8 for one straight aisle of 32, 10 for an L of 32 + 16, 12 for an L of two
 * 32s, 16 for a U of three, and 36 for the widest chain {@code aisle.maxNetworkRails = 256} allows.
 * <p>
 * Pure integer maths with no Minecraft imports, so it is JUnit-testable; the content layer turns the pairs into chunk keys
 * with {@code ChunkPos#asLong} and never re-implements that packing.
 */
public final class AisleChunkSpan {
    /** How far the aisle box is inflated horizontally, in blocks: the attached inventories and the controller. */
    public static final int INFLATE = 1;
    /** Ints one branch contributes to {@link #networkChunks}: originX, originZ, stepX, stepZ, length. */
    public static final int INTS_PER_BRANCH = 5;
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

    /**
     * The chunk columns of a whole warehouse: the <b>de-duplicated union</b> of its branches' own footprints, sorted by
     * {@code (chunkX, chunkZ)} so trimming at a cap is reproducible across restarts (ADR-033).
     * <p>
     * The one-block inflation stays <b>per branch</b> rather than being applied once to a bounding box: it is what
     * covers the inventory behind a rack position, the machine behind a collecting port and a double chest pairing one
     * block past either end of an aisle, and every branch has its own two ends. A warehouse of one aisle therefore
     * gets literally what {@link #chunks} gives that aisle.
     *
     * @param branches one {@code {originX, originZ, stepX, stepZ, length}} group per branch
     * @return {@code 2 · chunkCount} ints: {@code [x0, z0, x1, z1, ...]}
     */
    public static int[] networkChunks(int[] branches) {
        if (branches == null || branches.length == 0 || branches.length % INTS_PER_BRANCH != 0)
            throw new IllegalArgumentException("a branch is " + INTS_PER_BRANCH + " ints: "
                    + (branches == null ? "null" : branches.length));
        if (branches.length == INTS_PER_BRANCH)
            return chunks(branches[0], branches[1], branches[2], branches[3], branches[4]);
        java.util.TreeSet<Long> columns = new java.util.TreeSet<>(
                java.util.Comparator.comparingInt((Long column) -> (int) (column >> 32))
                        .thenComparingInt(column -> (int) (long) column));
        for (int i = 0; i < branches.length; i += INTS_PER_BRANCH) {
            int[] own = chunks(branches[i], branches[i + 1], branches[i + 2], branches[i + 3], branches[i + 4]);
            for (int j = 0; j < own.length; j += 2)
                columns.add(((long) own[j] << 32) | (own[j + 1] & 0xFFFFFFFFL));
        }
        int[] result = new int[columns.size() * 2];
        int i = 0;
        for (long column : columns) {
            result[i++] = (int) (column >> 32);
            result[i++] = (int) column;
        }
        return result;
    }

    /** How many chunk columns {@link #chunks} would return, without building the array. */
    public static int chunkCount(int dockX, int dockZ, int stepX, int stepZ, int length) {
        int[] box = box(dockX, dockZ, stepX, stepZ, length);
        return ((box[1] >> 4) - (box[0] >> 4) + 1) * ((box[3] >> 4) - (box[2] >> 4) + 1);
    }

    /**
     * The largest number of chunks any <b>single</b> aisle of {@code length} rails can need, whatever its direction and
     * alignment. A warehouse of several aisles is not bounded by this — see the class comment.
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
