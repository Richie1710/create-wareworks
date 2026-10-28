package dev.wareworks.content.controller;

/**
 * How many cranes a warehouse has and what they are doing in a word (issue #14, ADR-075 §15.2): the head of the
 * controller's crane list ("Cranes: 3 — 2 working, 1 waiting") and the terminal's crane line ("3 cranes: 2 working,
 * 1 waiting").
 * <p>
 * Pure Java and bounded: four small numbers, whatever the warehouse does. The three states never add up to more than
 * the cranes there are; whatever is left is idle (or, for a crane whose chunk is not loaded, simply unknown).
 *
 * @param total   the cranes of the warehouse; 0 without one, 1 for every warehouse built up to 0.17.1
 * @param working cranes carrying out a job, or moving aside for another crane's
 * @param waiting cranes with a job that stand still because another crane holds their rails
 * @param paused  cranes that cannot move: no rotation, overstressed, too slow
 */
public record CraneCounts(int total, int working, int waiting, int paused) {
    /** No warehouse, and therefore no crane. */
    public static final CraneCounts NONE = new CraneCounts(0, 0, 0, 0);
    /** Number of ints {@link #pack()} writes. */
    public static final int PACKED_LENGTH = 4;

    public CraneCounts {
        total = Math.max(0, total);
        working = Math.clamp(working, 0, total);
        waiting = Math.clamp(waiting, 0, total - working);
        paused = Math.clamp(paused, 0, total - working - waiting);
    }

    /** Whether the warehouse has more than one crane, which is the only time any surface names the counts. */
    public boolean several() {
        return total > 1;
    }

    /** The cranes that are neither working, waiting nor paused. */
    public int idle() {
        return total - working - waiting - paused;
    }

    /** The numbers in a fixed order: total, working, waiting, paused. */
    public int[] pack() {
        return new int[] {total, working, waiting, paused};
    }

    /** Reads what {@link #pack()} wrote; anything else reads as {@link #NONE}. Never throws. */
    public static CraneCounts unpack(int[] packed) {
        if (packed == null || packed.length != PACKED_LENGTH)
            return NONE;
        return new CraneCounts(packed[0], packed[1], packed[2], packed[3]);
    }
}
