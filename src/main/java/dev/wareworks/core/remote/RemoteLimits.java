package dev.wareworks.core.remote;

/**
 * How far a Warehouse Remote reaches (issue #44, ADR-077): the range arithmetic only, so it can be tested without
 * Minecraft.
 * <p>
 * The range is measured from the player's eyes to the centre of the linked terminal's block, in blocks, and only
 * within one dimension: coordinates of two dimensions are not comparable (the Nether's are scaled 1:8), so a remote
 * used in another dimension is either refused or, where the server allows it, not measured at all. The configured
 * range is clamped here as well, rather than trusting a file a player edits.
 */
public final class RemoteLimits {
    /** The shortest range the configuration may set, in blocks: a little beyond the reach of a hand at the block. */
    public static final int MIN_RANGE = 16;
    /** The longest range the configuration may set, in blocks: the world border's furthest corner, so no limit. */
    public static final int MAX_RANGE = 30_000_000;
    /** The shipped range, in blocks: the far side of a large base, not the next village. */
    public static final int DEFAULT_RANGE = 256;

    private RemoteLimits() {
    }

    /** {@code range} clamped into {@value #MIN_RANGE}..{@value #MAX_RANGE}. */
    public static int clampRange(int range) {
        return Math.max(MIN_RANGE, Math.min(MAX_RANGE, range));
    }

    /**
     * Whether {@code distance} blocks are within a range of {@code range} blocks (clamped): exactly the range still
     * reaches. A distance that is not a number reaches nothing.
     */
    public static boolean withinRange(double distance, int range) {
        return distance >= 0 && distance <= clampRange(range);
    }

    /** {@code distance} rounded to whole blocks for a message, so a refusal never reads "256 blocks away, at most 256". */
    public static long shownBlocks(double distance) {
        if (!(distance > 0))
            return 0;
        return (long) Math.ceil(distance);
    }
}
