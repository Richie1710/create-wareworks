package dev.wareworks.core.storage;

import dev.wareworks.core.inventory.CapacityMath;

/**
 * The two materials a <b>fluid bay</b> is built from, and how much one bay of each holds (M30, issue #21).
 * <p>
 * A fluid bay is a storage location that <b>is</b> a tank: it holds one fluid as a millibucket count, the way
 * {@link BayContents} holds one item key and a count. Its capacity is counted in <b>buckets</b> rather than in stacks,
 * because a fluid has no stack size to multiply by — one number stands on the block and means the same for water as
 * for lava:
 * <table>
 *   <caption>Shipped defaults</caption>
 *   <tr><th>Tier</th><th>Buckets</th><th>Measured against</th></tr>
 *   <tr><td>{@link #COPPER}</td><td>64</td><td>eight Create Fluid Tank blocks (8 buckets each)</td></tr>
 *   <tr><td>{@link #BRASS}</td><td>256</td><td>more than a 3 x 3 x 3 tank tower, which is 27 x 8 = 216</td></tr>
 * </table>
 * <b>There is no wood and no andesite tier</b>, unlike the item bays of {@link BayTier}, and the reason is Create's own
 * material language: in Create fluids are <b>copper</b> — the pipes, the pumps, the tank casing — andesite plays no
 * part in its fluid world, and a wooden barrel of lava is an explanation nobody should owe.
 * <p>
 * Both numbers are server config ({@code storage.copperFluidBayBuckets} and {@code storage.brassFluidBayBuckets}), so
 * a modpack can move the curve without a code change. This layer is pure Java and never reads them itself: content
 * code passes the configured number in, and everything here clamps it into {@link #MIN_BUCKETS}..{@link #MAX_BUCKETS}
 * rather than trusting a file a player edits, exactly as {@link BayTier} does.
 *
 * <h2>Why this is its own enum and not two more {@link BayTier} constants</h2>
 * {@link BayTier#strength()} is the ordinal and that enum's declaration order <b>is</b> the strength order its column
 * rule is built on. Inserting {@code COPPER} in the middle would silently reorder that rule; appending it would make
 * copper the strongest material in the mod; and "is a copper tank stronger than an andesite rack" is a question the
 * column rule would then have to answer and nobody can. A fluid bay carries fluid bays and an item bay carries item
 * bays — the family is compared before the strength, so a bay of the other family ends a column exactly as air does.
 *
 * <h2>Millibuckets inside, buckets outside</h2>
 * Everything stored, inserted, drained and persisted is counted in <b>millibuckets</b>, because the smallest portion
 * any container in the ecosystem carries is 250 mB (a bottle) and buckets cannot express it.
 * {@link #MILLIBUCKETS_PER_BUCKET} is this layer's mirror of {@code FluidType.BUCKET_VOLUME}, which pure Java cannot
 * see — the same split {@link CapacityMath#STACK_SIZE_CEILING} already uses against {@code Item.ABSOLUTE_MAX_STACK_SIZE},
 * and it is asserted against the real constant by a GameTest rather than remembered.
 */
public enum FluidBayTier {
    /** The fluid bay: copper is Create's fluid material, so this is the one a player builds first. */
    COPPER(64),
    /** The large fluid bay, past what a 3 x 3 x 3 tank tower holds. */
    BRASS(256);

    /** Smallest capacity any configuration may ask for: a bay of a single bucket, which is still a bay. */
    public static final int MIN_BUCKETS = 1;
    /**
     * Largest capacity any configuration may ask for. At the ceiling a bay holds
     * {@link #MAX_CAPACITY_MILLIBUCKETS} millibuckets, which is 3 % of an {@code int} — so a bay's content count fits
     * an {@code int} and the {@code int} a fluid handler reports as its tank capacity, with room to spare for the
     * arithmetic around it.
     */
    public static final int MAX_BUCKETS = 65_536;
    /**
     * Millibuckets in one bucket: the pure-Java mirror of {@code net.neoforged.neoforge.fluids.FluidType#BUCKET_VOLUME},
     * which this layer cannot import. A GameTest asserts the two are equal, the way {@code stackSizeCeiling} does for
     * {@link CapacityMath#STACK_SIZE_CEILING}.
     */
    public static final int MILLIBUCKETS_PER_BUCKET = 1_000;
    /**
     * The most fluid any bay can ever hold: {@link #MAX_BUCKETS} buckets, i.e. 65 536 000 millibuckets. Comfortably
     * inside an {@code int}, which is the whole reason {@link #MAX_BUCKETS} is where it is.
     */
    public static final int MAX_CAPACITY_MILLIBUCKETS = MAX_BUCKETS * MILLIBUCKETS_PER_BUCKET;

    private final int defaultBuckets;

    FluidBayTier(int defaultBuckets) {
        this.defaultBuckets = defaultBuckets;
    }

    /** The shipped capacity of this tier in buckets, i.e. the default of its config key. */
    public int defaultBuckets() {
        return defaultBuckets;
    }

    /**
     * {@link BayFamily#FLUID}: this is the fluid ladder, and its strengths are comparable only with each other. An
     * item bay's tier belongs to the other ladder ({@link BayTier#family()}), and the column rule compares the family
     * <b>before</b> any strength, which is what makes the two ladders safe to keep apart.
     */
    public BayFamily family() {
        return BayFamily.FLUID;
    }

    /**
     * How much weight this tier carries, rising with capacity: {@code COPPER} 0, {@code BRASS} 1. It only ever means
     * something compared with another fluid tier's — never with a {@link BayTier}'s, which is why the two enums are
     * separate.
     */
    public int strength() {
        return ordinal();
    }

    /** Whether this tier carries more weight than {@code other}, i.e. stands further up the ladder. */
    public boolean isStrongerThan(FluidBayTier other) {
        return strength() > other.strength();
    }

    /**
     * Whether a bay of this tier may stand directly under a bay of tier {@code above}: it may, unless the bay above is
     * the stronger of the two. Equal tiers carry each other, so a column of one material is any height.
     * <p>
     * This is the same, and the only, form of the column rule {@link BayTier#mayCarry} states for item bays, used in
     * both directions so the illegal column cannot be built from the top either.
     */
    public boolean mayCarry(FluidBayTier above) {
        return !above.isStrongerThan(this);
    }

    /**
     * The configured bucket count brought into range. The value comes from a config file a player edits and from a
     * per-world override, so nothing here throws: an absurd number is clamped into
     * {@link #MIN_BUCKETS}..{@link #MAX_BUCKETS} and the bay works.
     */
    public static int clampBuckets(int buckets) {
        return Math.max(MIN_BUCKETS, Math.min(buckets, MAX_BUCKETS));
    }

    /**
     * How many <b>millibuckets</b> one bay of this tier holds, given the configured bucket count:
     * {@code clampBuckets(buckets) * MILLIBUCKETS_PER_BUCKET}, which is never above
     * {@link #MAX_CAPACITY_MILLIBUCKETS} and never below {@link #MILLIBUCKETS_PER_BUCKET}.
     * <p>
     * A {@code long}, because that is what {@link BayContents#insert} takes as its capacity — and because a capacity
     * <b>below</b> what a bay already holds is a legal state there: lowering a configured number keeps everything in
     * the bay and only stops it accepting more, which is the promise the config comment makes.
     *
     * @param buckets the configured capacity of this tier in buckets, clamped here
     */
    public long capacityMillibuckets(int buckets) {
        return (long) clampBuckets(buckets) * MILLIBUCKETS_PER_BUCKET;
    }

    /**
     * How many <b>whole</b> containers of {@code containerMillibuckets} each a tank with {@code freeMillibuckets} of
     * room can take: the all-or-nothing rule of a fluid bay, in one place.
     * <p>
     * A container is drained to empty or refused, never partially, because a partially filled container is a different
     * item key per millibucket value and a warehouse that created them would grow one stock row, one terminal row and
     * one save entry per fill level. So a bay with 999 mB of room gets <b>nothing</b> from a bucket rather than 999 mB
     * — which is also the vanilla bucket's own rule, since {@code FluidBucketWrapper.fill} refuses a resource below a
     * whole bucket.
     * <p>
     * Pure integer division, so it never over-counts, and nothing here throws: a container that reports no sensible
     * size and a tank with no room both answer 0.
     *
     * @param containerMillibuckets how much one container holds; at or below 0 answers 0
     * @param freeMillibuckets      how much room the tank has; at or below 0 answers 0
     */
    public static int wholeContainers(int containerMillibuckets, long freeMillibuckets) {
        if (containerMillibuckets <= 0 || freeMillibuckets <= 0)
            return 0;
        return CapacityMath.toIntClamped(freeMillibuckets / containerMillibuckets);
    }
}
