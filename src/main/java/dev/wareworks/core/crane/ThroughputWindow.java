package dev.wareworks.core.crane;

import java.util.Arrays;
import java.util.Objects;

/**
 * A rolling minute of one crane's work: {@value #BUCKETS} buckets of {@value #TICKS_PER_BUCKET} ticks, one per second,
 * with running sums so a snapshot is O(1) however often a watching player asks for one.
 * <p>
 * Pure Java and <b>never saved</b>. Game time is contiguous across a restart, so saved buckets would be
 * arithmetically defensible and would still claim a minute of work for a machine that stood still — and the dock's
 * save format stays untouched, which is worth more than a number that survives a reload.
 * <p>
 * <b>Gaps contribute nothing, neither idle nor working.</b> {@link #record(long, CraneActivity)} first advances the
 * ring over every whole second that has elapsed, zeroing each bucket it moves onto and subtracting it from the running
 * sums; a chunk unload therefore shrinks {@link CraneThroughput#observedTicks()} by exactly the ticks that were in the
 * seconds it skipped, and adds nothing of its own. The advance is bounded at {@value #BUCKETS} steps: a gap of a
 * minute or more, and a game time that moves backwards (a {@code /time set}), clear the ring instead.
 * <p>
 * One instance per crane, server thread only. About 2 KB of counters.
 */
public final class ThroughputWindow {
    /** Ticks one bucket covers: one second. */
    public static final int TICKS_PER_BUCKET = 20;

    /** Buckets in the ring: one minute. */
    public static final int BUCKETS = 60;

    /** Ticks the whole window covers. */
    public static final int WINDOW_TICKS = BUCKETS * TICKS_PER_BUCKET;

    /**
     * Quarter turns are counted in thousandths of a turn, as integers: the running sum is then exact under eviction,
     * where a sum of doubles would drift away from the buckets it is supposed to add up to.
     */
    private static final int TURN_SCALE = 1000;

    /** Cap per bucket, so sixty buckets of deliveries can never overflow the running sum. */
    private static final int MAX_ITEMS_PER_BUCKET = Integer.MAX_VALUE / BUCKETS;

    /** Ticks per bucket per activity, indexed {@code bucket * CraneActivity.COUNT + activity.ordinal()}. */
    private final int[] activityTicks = new int[BUCKETS * CraneActivity.COUNT];
    private final int[] bucketTrips = new int[BUCKETS];
    private final int[] bucketItems = new int[BUCKETS];
    /** Thousandths of a quarter turn per bucket. */
    private final int[] bucketTurns = new int[BUCKETS];

    private final int[] activitySums = new int[CraneActivity.COUNT];
    private int observedTicks;
    private int trips;
    private int items;
    private int turns;

    /** The bucket being filled. */
    private int head;
    /** The second {@link #head} stands for, as {@code floorDiv(gameTime, TICKS_PER_BUCKET)}. */
    private long headSecond;
    /** Whether a tick has been recorded since the last {@link #clear()}: until then there is no second to advance from. */
    private boolean started;

    /** Counts one observed tick. */
    public void record(long gameTime, CraneActivity activity) {
        record(gameTime, activity, 0.0);
    }

    /**
     * Counts one observed tick, and the swing it contained.
     *
     * @param gameTime     the tick's game time; whole seconds elapsed since the last recorded tick are rolled off
     *                     first
     * @param activity     the one bucket the tick belongs to ({@link CraneActivity#of})
     * @param quarterTurns quarter turns swung in the tick, in either direction ({@link CraneTickMotion#quarterTurns()});
     *                     the magnitude is what counts, and a finished corner sums to exactly one. <b>Counted only on
     *                     a tick this window books as {@link CraneActivity#TURNING}</b> — see below
     */
    public void record(long gameTime, CraneActivity activity, double quarterTurns) {
        Objects.requireNonNull(activity, "activity");
        if (!Double.isFinite(quarterTurns))
            throw new IllegalArgumentException("quarterTurns must be finite: " + quarterTurns);
        advance(gameTime);
        activityTicks[head * CraneActivity.COUNT + activity.ordinal()]++;
        activitySums[activity.ordinal()]++;
        observedTicks++;
        // The corner count is measured over exactly the ticks the turning SHARE is measured over, because the two are
        // printed as one phrase — "turning 6 % (9 corners)" — and a count taken over a wider set of ticks than the
        // share beside it is a contradiction in one line (M25 review fix).
        //
        // What that leaves out is the swing of a tick the window books as something else, and with the precedence
        // PAUSED > IDLE > TURNING that is one real case: the drive home, which has no job and is therefore IDLE even
        // while it crosses a corner. Those corners are not work, so they do not belong under a breakdown of work;
        // "turning 0 % (11 corners)" was the shape of the old reading.
        if (activity != CraneActivity.TURNING)
            return;
        int scaled = (int) Math
                .round(Math.min(Math.abs(quarterTurns), CranePose.FULL_TURN) * TURN_SCALE);
        if (scaled > 0) {
            bucketTurns[head] += scaled;
            turns += scaled;
        }
    }

    /**
     * Counts one completed job. Credited to the second the window currently stands on, which is the tick's own second
     * whenever it is recorded in the same tick as {@link #record}.
     */
    public void addTrip() {
        bucketTrips[head]++;
        trips++;
    }

    /** Counts items delivered, credited like {@link #addTrip()}. */
    public void addItems(int amount) {
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        int added = Math.min(amount, MAX_ITEMS_PER_BUCKET - bucketItems[head]);
        if (added <= 0)
            return;
        bucketItems[head] += added;
        items += added;
    }

    /** Forgets everything, in O({@value #BUCKETS}). */
    public void clear() {
        Arrays.fill(activityTicks, 0);
        Arrays.fill(bucketTrips, 0);
        Arrays.fill(bucketItems, 0);
        Arrays.fill(bucketTurns, 0);
        Arrays.fill(activitySums, 0);
        observedTicks = 0;
        trips = 0;
        items = 0;
        turns = 0;
        head = 0;
        headSecond = 0;
        started = false;
    }

    /** Ticks currently in the window, at most {@value #WINDOW_TICKS}. */
    public int observedTicks() {
        return observedTicks;
    }

    /** What the window holds right now. O(1). */
    public CraneThroughput snapshot() {
        return new CraneThroughput(observedTicks, activitySums[CraneActivity.IDLE.ordinal()],
                activitySums[CraneActivity.PAUSED.ordinal()], activitySums[CraneActivity.TRAVELLING.ordinal()],
                activitySums[CraneActivity.TURNING.ordinal()], activitySums[CraneActivity.AT_A_STOP.ordinal()],
                activitySums[CraneActivity.BLOCKED.ordinal()], trips, items,
                (turns + TURN_SCALE / 2) / TURN_SCALE);
    }

    /** Rolls the ring forward to {@code gameTime}'s second, forgetting every bucket it moves onto. */
    private void advance(long gameTime) {
        long second = Math.floorDiv(gameTime, TICKS_PER_BUCKET);
        if (!started) {
            started = true;
            headSecond = second;
            return;
        }
        if (second == headSecond)
            return;
        long elapsed = second - headSecond;
        if (elapsed < 0 || elapsed >= BUCKETS) {
            // A whole minute of nothing, or a clock that jumped backwards: every bucket would be rolled off anyway.
            clear();
            started = true;
            headSecond = second;
            return;
        }
        for (long step = 0; step < elapsed; step++) {
            head = head + 1 == BUCKETS ? 0 : head + 1;
            forget(head);
        }
        headSecond = second;
    }

    /** Subtracts one bucket from the running sums and empties it. */
    private void forget(int bucket) {
        int base = bucket * CraneActivity.COUNT;
        for (int activity = 0; activity < CraneActivity.COUNT; activity++) {
            int ticks = activityTicks[base + activity];
            if (ticks == 0)
                continue;
            activitySums[activity] -= ticks;
            observedTicks -= ticks;
            activityTicks[base + activity] = 0;
        }
        trips -= bucketTrips[bucket];
        bucketTrips[bucket] = 0;
        items -= bucketItems[bucket];
        bucketItems[bucket] = 0;
        turns -= bucketTurns[bucket];
        bucketTurns[bucket] = 0;
    }
}
