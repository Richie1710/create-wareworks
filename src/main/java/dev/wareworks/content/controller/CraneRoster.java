package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;

/**
 * The cranes of a warehouse with several, as the controller's goggles list them (issue #14, ADR-075 §15.1): how many
 * there are and what they do in a word, then one line per crane — "1: Storing to A-03-07R, about 12 s",
 * "2: waiting for crane 1", "3: idle".
 * <p>
 * <b>Absent for a warehouse of one crane</b>, so such a controller's goggle tag is byte for byte what it was up to
 * 0.17.1 ({@link ControllerGoggleSummary#cranes()}).
 * <p>
 * Bounded like every goggle record: the counts cover every crane, but at most {@value #MAX_LISTED} lines are synced —
 * the same six the controller's aisle line lists — and each is eight small numbers ({@link #pack()}). Pure Java;
 * reading never throws.
 *
 * @param counts       how many cranes there are and what they do, over all of them
 * @param entries      the first {@value #MAX_LISTED} cranes, in crane order
 * @param refusedDocks docks that face into the rails but were left out because the warehouse already has as many
 *                     cranes as the server allows
 * @param maxCranes    that maximum, as the server had it when this was built ({@code aisle.maxCranesPerNetwork})
 */
public record CraneRoster(CraneCounts counts, List<Entry> entries, int refusedDocks, int maxCranes) {
    /** How many cranes are listed by name before the rest is counted. */
    public static final int MAX_LISTED = 6;
    /** Ints per listed crane in {@link #pack()}. */
    private static final int ENTRY_LENGTH = 8;
    /** Ints before the first listed crane in {@link #pack()}: the counts, the refused docks, the maximum, the length. */
    private static final int HEAD_LENGTH = CraneCounts.PACKED_LENGTH + 3;
    private static final int NONE = -1;

    /** What one crane is doing, in a word. {@link #name()} travels by ordinal inside client packets only. */
    public enum Activity {
        IDLE,
        STORING,
        RETRIEVING,
        SUPPLYING,
        COLLECTING,
        HANDING_OVER,
        /** On a fill trip: empty containers to a fluid bay, filled ones on to the station (M31, issue #21). */
        FILLING,
        /** It has a job and stands still because another crane holds its rails. */
        WAITING,
        /** It moves aside for another crane. */
        MOVING_ASIDE,
        /** No rotation, overstressed or too slow. */
        PAUSED,
        /** Its chunk is not loaded, so nothing is known about it. */
        UNLOADED,
        /** A closed rail cut it off from its dock. */
        STRANDED;

        /** Whether this is a job of the crane's own, which a line names with its target and time. */
        public boolean isJob() {
            return switch (this) {
                case STORING, RETRIEVING, SUPPLYING, COLLECTING, HANDING_OVER, FILLING -> true;
                case IDLE, WAITING, MOVING_ASIDE, PAUSED, UNLOADED, STRANDED -> false;
            };
        }

        /** Whether a line names another crane. */
        public boolean namesCrane() {
            return this == WAITING || this == MOVING_ASIDE;
        }
    }

    /**
     * One crane of the list.
     *
     * @param number     the crane number
     * @param activity   what it is doing
     * @param otherCrane the crane it waits for or moves aside for, or 0
     * @param target     where its job goes, for a job of its own
     * @param seconds    about how many seconds its job still takes, or -1 when unknown
     */
    public record Entry(int number, Activity activity, int otherCrane, Optional<RackPosition> target, int seconds) {
        public Entry {
            number = Math.max(1, number);
            if (activity == null)
                activity = Activity.IDLE;
            otherCrane = activity.namesCrane() ? Math.max(0, otherCrane) : 0;
            target = target == null || !activity.isJob() ? Optional.empty() : target;
            seconds = activity.isJob() ? Math.max(NONE, seconds) : NONE;
        }

        /** Whether the line can say how long the job still takes. */
        public boolean hasSeconds() {
            return seconds >= 0;
        }
    }

    public CraneRoster {
        Objects.requireNonNull(counts, "counts");
        entries = entries == null ? List.of()
                : List.copyOf(entries.subList(0, Math.min(entries.size(), MAX_LISTED)));
        refusedDocks = Math.max(0, refusedDocks);
        maxCranes = Math.max(0, maxCranes);
    }

    /**
     * The roster of every crane in {@code cranes}, crane order: the counts over all of them and the first
     * {@value #MAX_LISTED} listed.
     */
    public static CraneRoster of(List<Entry> cranes, int refusedDocks, int maxCranes) {
        Objects.requireNonNull(cranes, "cranes");
        int working = 0;
        int waiting = 0;
        int paused = 0;
        for (Entry entry : cranes) {
            if (entry.activity().isJob() || entry.activity() == Activity.MOVING_ASIDE)
                working++;
            else if (entry.activity() == Activity.WAITING)
                waiting++;
            else if (entry.activity() == Activity.PAUSED)
                paused++;
        }
        return new CraneRoster(new CraneCounts(cranes.size(), working, waiting, paused), cranes, refusedDocks,
                maxCranes);
    }

    /** Cranes that are counted but not listed by name. */
    public int unlisted() {
        return Math.max(0, counts.total() - entries.size());
    }

    /** The numbers in a fixed order: counts, refused docks, maximum, the number of lines, then every line. */
    public int[] pack() {
        int[] packed = new int[HEAD_LENGTH + entries.size() * ENTRY_LENGTH];
        System.arraycopy(counts.pack(), 0, packed, 0, CraneCounts.PACKED_LENGTH);
        packed[CraneCounts.PACKED_LENGTH] = refusedDocks;
        packed[CraneCounts.PACKED_LENGTH + 1] = maxCranes;
        packed[CraneCounts.PACKED_LENGTH + 2] = entries.size();
        int at = HEAD_LENGTH;
        for (Entry entry : entries) {
            RackPosition target = entry.target().orElse(null);
            packed[at] = entry.number();
            packed[at + 1] = entry.activity().ordinal();
            packed[at + 2] = entry.otherCrane();
            packed[at + 3] = target == null ? NONE : target.branch();
            packed[at + 4] = target == null ? NONE : target.x();
            packed[at + 5] = target == null ? NONE : target.y();
            packed[at + 6] = target == null ? NONE : target.side().ordinal();
            packed[at + 7] = entry.seconds();
            at += ENTRY_LENGTH;
        }
        return packed;
    }

    /**
     * Reads what {@link #pack()} wrote. Untrusted packet data: a length that does not match the line count, or more
     * lines than are ever synced, is no roster at all; a line naming no valid target simply has none. Never throws.
     */
    public static Optional<CraneRoster> unpack(int[] packed) {
        if (packed == null || packed.length < HEAD_LENGTH)
            return Optional.empty();
        int listed = packed[CraneCounts.PACKED_LENGTH + 2];
        if (listed < 0 || listed > MAX_LISTED || packed.length != HEAD_LENGTH + listed * ENTRY_LENGTH)
            return Optional.empty();
        int[] counts = new int[CraneCounts.PACKED_LENGTH];
        System.arraycopy(packed, 0, counts, 0, CraneCounts.PACKED_LENGTH);
        List<Entry> entries = new ArrayList<>(listed);
        Activity[] activities = Activity.values();
        for (int i = 0; i < listed; i++) {
            int at = HEAD_LENGTH + i * ENTRY_LENGTH;
            int ordinal = packed[at + 1];
            Activity activity = ordinal >= 0 && ordinal < activities.length ? activities[ordinal] : Activity.IDLE;
            entries.add(new Entry(packed[at], activity, packed[at + 2],
                    target(packed[at + 3], packed[at + 4], packed[at + 5], packed[at + 6]), packed[at + 7]));
        }
        return Optional.of(new CraneRoster(CraneCounts.unpack(counts), entries,
                packed[CraneCounts.PACKED_LENGTH], packed[CraneCounts.PACKED_LENGTH + 1]));
    }

    private static Optional<RackPosition> target(int branch, int x, int y, int side) {
        Side[] sides = Side.values();
        if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH || x < 0 || y < 0 || side < 0
                || side >= sides.length)
            return Optional.empty();
        return Optional.of(new RackPosition(branch, x, y, sides[side]));
    }
}
