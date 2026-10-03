package dev.wareworks.core.crane;

import java.util.Optional;

/**
 * What one crane got done in the window it was watched: a snapshot of {@link ThroughputWindow}, in <b>raw ticks</b>.
 * <p>
 * Ticks and not percentages, so there is one source of truth and every assertion is exact. The shares are derived
 * here, by integer division, floored, and none is ever computed as the <b>remainder</b> of the others, so nothing
 * claims a total of exactly 100.
 * <p>
 * <b>The busy headline is the sum of the three parts printed under it</b> ({@link #busyShare()}, M25 review fix), and
 * that is the one place a share is not computed from its own ticks. Flooring the whole and each of its parts
 * independently is arithmetically defensible and reads as a contradiction: {@code floor((a+b+c)/n)} can exceed
 * {@code floor(a/n) + floor(b/n) + floor(c/n)} by up to two, which is how "Busy: 85 %" came to stand above
 * "Travel 48 % · turning 7 % · at the rack 29 %". Summing the parts can only ever understate the busy share, never
 * overstate it, and it buys the one property a reader checks without being told to: the breakdown adds up to the
 * headline.
 * <p>
 * {@link #share(int)} divides by {@link #observedTicks()}, never by a fixed minute. That is not a detail: a window
 * that has only run for ten seconds holds ten seconds of ticks, and dividing those by 1200 would make every crane
 * read low for the first minute after every chunk load — teaching the player the opposite of the truth. The
 * accompanying label carries the real length instead ({@link #observedSeconds()}), and nothing is ever extrapolated.
 *
 * @param observedTicks ticks actually observed in the window; the six activity counts sum to exactly this
 * @param idleTicks     ticks with no job, the drive home included ({@link CraneActivity#IDLE})
 * @param pausedTicks   ticks the world had the machine stopped ({@link CraneActivity#PAUSED})
 * @param travelTicks   ticks driving or lifting ({@link CraneActivity#TRAVELLING})
 * @param turnTicks     ticks swinging at a corner ({@link CraneActivity#TURNING})
 * @param stopTicks     ticks standing at a source or target ({@link CraneActivity#AT_A_STOP})
 * @param blockedTicks  ticks with a job and nothing happening ({@link CraneActivity#BLOCKED})
 * @param trips         jobs completed in the window
 * @param items         items delivered in the window
 * @param corners       corners taken in the window, rounded from the quarter turns swung on the ticks counted as
 *                      {@link CraneActivity#TURNING} — the very ticks {@link #turnTicks} counts, because the two are
 *                      printed as one phrase ("turning 6 % (9 corners)"). A corner crossed on the drive home is
 *                      therefore not in here: that tick has no job and is {@link CraneActivity#IDLE}
 */
public record CraneThroughput(int observedTicks, int idleTicks, int pausedTicks, int travelTicks, int turnTicks,
        int stopTicks, int blockedTicks, int trips, int items, int corners) {
    /** A window that has seen nothing — what a freshly loaded dock reports. */
    public static final CraneThroughput EMPTY = new CraneThroughput(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** A whole share in percent. */
    public static final int FULL_SHARE = 100;

    /** Number of ints {@link #pack()} writes, i.e. the number of components of this record. */
    public static final int PACKED_LENGTH = 10;

    public CraneThroughput {
        requireNotNegative(observedTicks, "observedTicks");
        requireNotNegative(idleTicks, "idleTicks");
        requireNotNegative(pausedTicks, "pausedTicks");
        requireNotNegative(travelTicks, "travelTicks");
        requireNotNegative(turnTicks, "turnTicks");
        requireNotNegative(stopTicks, "stopTicks");
        requireNotNegative(blockedTicks, "blockedTicks");
        requireNotNegative(trips, "trips");
        requireNotNegative(items, "items");
        requireNotNegative(corners, "corners");
    }

    private static void requireNotNegative(int value, String name) {
        if (value < 0)
            throw new IllegalArgumentException(name + " must not be negative: " + value);
    }

    /**
     * The ten numbers in component order, for a save or a packet: one {@code int[]} is about 44 accounting bytes,
     * against 10 × 64 for a compound of named ints.
     */
    public int[] pack() {
        return new int[] { observedTicks, idleTicks, pausedTicks, travelTicks, turnTicks, stopTicks, blockedTicks,
                trips, items, corners };
    }

    /**
     * Reads what {@link #pack()} wrote. Never throws: an array of the wrong length is no measurement at all, and a
     * negative number — which the canonical constructor refuses, deliberately, so that nothing inside the mod can
     * produce one — is clamped to zero rather than rejected, because the value arrives from a client packet or a
     * hand-edited save and a tooltip is not worth an exception.
     */
    public static Optional<CraneThroughput> unpack(int[] packed) {
        if (packed == null || packed.length != PACKED_LENGTH)
            return Optional.empty();
        return Optional.of(new CraneThroughput(atLeastZero(packed[0]), atLeastZero(packed[1]), atLeastZero(packed[2]),
                atLeastZero(packed[3]), atLeastZero(packed[4]), atLeastZero(packed[5]), atLeastZero(packed[6]),
                atLeastZero(packed[7]), atLeastZero(packed[8]), atLeastZero(packed[9])));
    }

    private static int atLeastZero(int value) {
        return Math.max(0, value);
    }

    /** The ticks of one bucket. */
    public int ticks(CraneActivity activity) {
        return switch (activity) {
            case PAUSED -> pausedTicks;
            case IDLE -> idleTicks;
            case TURNING -> turnTicks;
            case TRAVELLING -> travelTicks;
            case AT_A_STOP -> stopTicks;
            case BLOCKED -> blockedTicks;
        };
    }

    /** Ticks the machine was getting work done: travelling, turning and standing at a rack. */
    public int workingTicks() {
        return travelTicks + turnTicks + stopTicks;
    }

    /**
     * {@code ticks} as a percentage of the observed ticks, floored — and {@code 0} for a window that has observed
     * nothing, so no surface can divide by zero.
     */
    public int share(int ticks) {
        if (observedTicks <= 0 || ticks <= 0)
            return 0;
        return (int) ((long) ticks * FULL_SHARE / observedTicks);
    }

    /**
     * Share of the window the machine was working: <b>the sum of {@link #travelShare()}, {@link #turnShare()} and
     * {@link #stopShare()}</b>, so the headline a player reads is exactly what the breakdown under it adds up to.
     * <p>
     * Not {@code share(workingTicks())}: that floors the whole where the three lines floor their parts, and the two
     * differ by up to two percentage points — a visible contradiction between two lines of one tooltip. The three
     * parts stay the primary numbers, each from its own ticks; this is their total.
     * <p>
     * <b>The one state in which the printed breakdown is still short of this number.</b> The turning term is printed
     * only on a warehouse that bends right now, because a straight aisle structurally never yaws and a standing
     * {@code 0 %} teaches nothing — but the window remembers a minute, and the geometry does not. So for up to the
     * {@value ThroughputWindow#BUCKETS} seconds after a player <b>breaks the last junction</b> of a bending warehouse,
     * this total still contains {@link #turnShare()} while the line under it names travel and the rack only. It
     * resolves itself as the ring rolls, it needs the player to have just torn down their own rails, and the
     * alternative — printing the turning term whenever the window holds one — would show a straight warehouse a term
     * its own shape makes impossible. Left as it is, deliberately, and recorded here and in
     * {@code docs/stacker-crane.md} §9 rather than in neither.
     */
    public int busyShare() {
        return travelShare() + turnShare() + stopShare();
    }

    /** Share of the window the machine had a job and was stuck. */
    public int blockedShare() {
        return share(blockedTicks);
    }

    /** Share of the window the machine was driving or lifting. */
    public int travelShare() {
        return share(travelTicks);
    }

    /** Share of the window the machine was swinging at a corner. */
    public int turnShare() {
        return share(turnTicks);
    }

    /** Share of the window the machine was standing at a source or target. */
    public int stopShare() {
        return share(stopTicks);
    }

    /** Share of the window the machine had no job. */
    public int idleShare() {
        return share(idleTicks);
    }

    /** Share of the window the world had the machine stopped. */
    public int pausedShare() {
        return share(pausedTicks);
    }

    /**
     * Whole seconds of observation, the second in progress rounded up — the length a surface must name beside every
     * share while the window is not yet full ("of the last 23 s").
     */
    public int observedSeconds() {
        return (observedTicks + ThroughputWindow.TICKS_PER_BUCKET - 1) / ThroughputWindow.TICKS_PER_BUCKET;
    }

    /**
     * Whether the window holds a whole minute. The youngest second is always still filling, so this is a question
     * about {@link #observedSeconds()} and not about reaching {@link ThroughputWindow#WINDOW_TICKS} exactly.
     */
    public boolean isFullMinute() {
        return observedSeconds() >= ThroughputWindow.BUCKETS;
    }

    /**
     * Whether there is nothing worth showing: nothing worked, nothing blocked, nothing delivered. A window that holds
     * only idle or only paused ticks is empty, because the status and pause lines already say exactly that and the
     * mod's rule is to leave a zero out entirely rather than print it.
     */
    public boolean isEmpty() {
        return workingTicks() == 0 && blockedTicks == 0 && trips == 0 && items == 0;
    }
}
