package dev.wareworks.core.stock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import dev.wareworks.core.inventory.CapacityMath;
import dev.wareworks.core.job.RelocationRule;

/**
 * What a Warehouse Optimizer shows for one item (issue #46, ADR-079): how much of it the warehouse holds at each storage
 * priority, how much of it lies where nothing reads it right now, and how much of it could move to a better place now.
 * <p>
 * <b>An estimate.</b> The movable figure is computed from the stock index and the inventory snapshots with no live
 * call at all ({@link #of}), in the order the planner works in ({@code JobPlanner}'s relocation stage,
 * {@link RelocationRule}): the worst source first, each into the targets the planner ranks first for it — the better
 * filter match, then the higher priority — so it never counts a room twice for one item. The crane may then move a
 * little less (a snapshot older than the last insert, a location it cannot reach) or, now and then, a little more:
 * between the filter and the priority the planner also prefers a place that already holds the item, which this view
 * cannot see, and a different first choice can leave room for a different source.
 * <p>
 * <b>A room nobody can judge</b> ({@link #UNKNOWN_ROOM}, a location without a snapshot) is never added to the figure:
 * it is reported as {@link #movableUnknown()}, which the screen shows as "?" beside the number.
 * <p>
 * Pure Java: it knows priorities, filter ranks and counts, never a location, so it can be tested without Minecraft.
 *
 * @param counts         how many of the item the loaded storage locations hold at each priority, indexed by priority
 *                       ({@value #MIN_PRIORITY}..{@value #MAX_PRIORITY}); always {@value #PRIORITIES} entries
 * @param unloaded       how many of it lie in storage locations whose chunk is not loaded: counted by the index, but at
 *                       no priority, because a location nobody can read reports none
 * @param free           how many of the loaded ones no job has reserved, capped by what may move of the item at all
 *                       (requests, owed containers, production ingredients and a stock rule's reserve stay put)
 * @param movable        how many of {@link #free()} have a better place with room, {@code 0..free}
 * @param movableUnknown whether some better place could take more and cannot be judged from its snapshot
 */
public record PriorityBreakdown(List<Long> counts, long unloaded, long free, long movable, boolean movableUnknown) {
    /** The lowest storage priority, which every location without one has ({@code StorageFilterBehaviour}). */
    public static final int MIN_PRIORITY = 0;
    /** The highest storage priority a player can set ({@code StorageFilterBehaviour}). */
    public static final int MAX_PRIORITY = 9;
    /** How many priorities there are, and so how many entries {@link #counts()} has. */
    public static final int PRIORITIES = MAX_PRIORITY - MIN_PRIORITY + 1;
    /**
     * A room that cannot be judged from its snapshot: the planner's {@code UNKNOWN_CAPACITY}, which the controller's
     * estimate answers for a location it has no snapshot of.
     */
    public static final long UNKNOWN_ROOM = CapacityMath.UNKNOWN_CAPACITY;
    /** An item the warehouse holds none of, and so a row with nothing to show. */
    public static final PriorityBreakdown NONE = new PriorityBreakdown(List.of(), 0L, 0L, 0L, false);

    public PriorityBreakdown {
        List<Long> normalised = new ArrayList<>(PRIORITIES);
        for (int priority = 0; priority < PRIORITIES; priority++) {
            Long count = counts == null || priority >= counts.size() ? null : counts.get(priority);
            normalised.add(count == null ? 0L : Math.max(0L, count));
        }
        counts = List.copyOf(normalised);
        unloaded = Math.max(0L, unloaded);
        free = Math.max(0L, free);
        movable = Math.max(0L, Math.min(movable, free));
    }

    /**
     * What one storage location holds of the item.
     *
     * @param priority   its storage priority, clamped into {@value #MIN_PRIORITY}..{@value #MAX_PRIORITY}
     * @param filterRank what its store filter says about the item, as {@code FilterMatch#storeRank()} (lower is better)
     * @param count      how many of the item it holds
     * @param free       how many of them no job has reserved, {@code 0..count}
     */
    public record Holding(int priority, int filterRank, long count, long free) {
        public Holding {
            priority = clampPriority(priority);
            count = Math.max(0L, count);
            free = Math.max(0L, Math.min(free, count));
        }
    }

    /**
     * A storage location that could take more of the item.
     *
     * @param priority   its storage priority, clamped into {@value #MIN_PRIORITY}..{@value #MAX_PRIORITY}
     * @param filterRank what its store filter says about the item, as {@code FilterMatch#storeRank()}; the location's
     *                   filter is known to allow the item, or it would not be a room at all
     * @param room       how many it could take beyond what jobs already bring, or {@link #UNKNOWN_ROOM}
     */
    public record Room(int priority, int filterRank, long room) {
        public Room {
            priority = clampPriority(priority);
            room = room == UNKNOWN_ROOM ? UNKNOWN_ROOM : Math.max(0L, room);
        }

        /** Whether the snapshot cannot say how much fits here. */
        public boolean unknown() {
            return room == UNKNOWN_ROOM;
        }
    }

    /**
     * The breakdown of one item: {@code holdings} are the loaded storage locations that hold it, {@code unloaded} what
     * the unloaded ones hold, {@code rooms} the loaded locations that could take more of it and {@code movableCap} what
     * may move of it at all ({@code WarehouseControllerBlockEntity#optimizerMovable}).
     * <p>
     * Rooms are handed out greedily, the way the planner's relocation stage takes them: the sources with the lowest
     * priority first, each one into the targets with the best filter match and then the highest priority first, and
     * only where {@link RelocationRule#isBetter(int, int, int, int)} holds. So a room is never counted twice for this
     * item, and an item at the best priority it can reach counts as movable nowhere.
     */
    public static PriorityBreakdown of(List<Holding> holdings, long unloaded, List<Room> rooms, long movableCap) {
        Objects.requireNonNull(holdings, "holdings");
        Objects.requireNonNull(rooms, "rooms");
        long[] counts = new long[PRIORITIES];
        long totalFree = 0;
        for (Holding holding : holdings) {
            counts[holding.priority() - MIN_PRIORITY] += holding.count();
            totalFree += holding.free();
        }
        long budget = Math.max(0L, Math.min(totalFree, movableCap));
        long free = budget;

        // The worst sources first: the lowest priority, and among equal priorities the worse filter match.
        List<Holding> sources = new ArrayList<>(holdings);
        sources.sort(Comparator.comparingInt(Holding::priority).thenComparing(Holding::filterRank,
                Comparator.reverseOrder()));
        long[] left = new long[sources.size()];
        for (int i = 0; i < left.length; i++)
            left[i] = sources.get(i).free();
        // The targets in the planner's ranking (JobPlanner#RANKING): the better filter match first, then the higher
        // priority. Ranking the priority first would fill a high open rack from the worst source and leave nothing for
        // a better source that only that rack could take, where the planner sends the worst source to a dedicated
        // place below it first.
        List<Room> targets = new ArrayList<>(rooms);
        targets.sort(Comparator.comparingInt(Room::filterRank)
                .thenComparing(Comparator.comparingInt(Room::priority).reversed()));
        long[] room = new long[targets.size()];
        for (int t = 0; t < room.length; t++)
            room[t] = targets.get(t).room();

        long movable = 0;
        for (int i = 0; i < sources.size() && budget > 0; i++) {
            Holding source = sources.get(i);
            for (int t = 0; t < targets.size() && left[i] > 0 && budget > 0; t++) {
                Room target = targets.get(t);
                if (target.unknown() || room[t] <= 0 || !RelocationRule.isBetter(source.priority(),
                        source.filterRank(), target.priority(), target.filterRank()))
                    continue;
                long taken = Math.min(Math.min(room[t], left[i]), budget);
                left[i] -= taken;
                room[t] -= taken;
                budget -= taken;
                movable += taken;
            }
        }
        // A room nobody can judge is never added: it is only reported, and only when something could still go there.
        boolean unknown = false;
        if (budget > 0) {
            for (Room target : targets) {
                if (target.unknown())
                    unknown |= anySourceFor(target, sources, left);
            }
        }
        List<Long> byPriority = new ArrayList<>(PRIORITIES);
        for (long count : counts)
            byPriority.add(count);
        return new PriorityBreakdown(byPriority, unloaded, free, movable, unknown);
    }

    private static boolean anySourceFor(Room target, List<Holding> sources, long[] left) {
        for (int i = 0; i < sources.size(); i++) {
            Holding source = sources.get(i);
            if (left[i] > 0 && RelocationRule.isBetter(source.priority(), source.filterRank(), target.priority(),
                    target.filterRank()))
                return true;
        }
        return false;
    }

    private static int clampPriority(int priority) {
        return Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, priority));
    }

    /** How many of the item the loaded storage locations hold at {@code priority}; 0 outside the range. */
    public long countAt(int priority) {
        return priority < MIN_PRIORITY || priority > MAX_PRIORITY ? 0L : counts.get(priority - MIN_PRIORITY);
    }

    /** How many the loaded storage locations hold at all priorities together. */
    public long loaded() {
        long total = 0;
        for (long count : counts)
            total += count;
        return total;
    }

    /** How many the warehouse holds in all, loaded or not. */
    public long total() {
        return loaded() + unloaded;
    }

    /** The highest priority a loaded location holding the item has, or -1 when none holds any. */
    public int bestPriority() {
        for (int priority = MAX_PRIORITY; priority >= MIN_PRIORITY; priority--) {
            if (countAt(priority) > 0)
                return priority;
        }
        return -1;
    }

    /** How many lie at {@link #bestPriority()}: the "Best" column. */
    public long best() {
        int best = bestPriority();
        return best < 0 ? 0L : countAt(best);
    }

    /** How many lie at the loaded priorities below {@link #bestPriority()}: the "Below" column. */
    public long below() {
        return loaded() - best();
    }
}
