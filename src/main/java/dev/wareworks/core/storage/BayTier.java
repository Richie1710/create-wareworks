package dev.wareworks.core.storage;

import dev.wareworks.core.inventory.CapacityMath;

/**
 * The three materials a rack bay is built from, and how much one bay of each holds (M28, issue #20).
 * <p>
 * A bay holds <b>one item type</b> as a count rather than in slots, and its capacity is counted in <b>stacks</b>, the
 * way Create's {@code vaultCapacity} and the drawer mods count: a bay holds "64 stacks" and the item says what a stack
 * is, so one number stands on the block whatever is in it and a bay of ender pearls holds 64 x 16 while one of
 * cobblestone holds 64 x 64. The ladder rises fourfold per tier — enough that reorganising a wall upward is worth the
 * trouble, little enough that a wooden warehouse does not become worthless the day andesite arrives — and brass lands
 * level with a fully upgraded drawer rather than past it:
 * <table>
 *   <caption>Shipped defaults</caption>
 *   <tr><th>Tier</th><th>Stacks</th><th>Cobblestone</th></tr>
 *   <tr><td>{@link #WOOD}</td><td>64</td><td>4 096</td></tr>
 *   <tr><td>{@link #ANDESITE}</td><td>256</td><td>16 384</td></tr>
 *   <tr><td>{@link #BRASS}</td><td>1 024</td><td>65 536</td></tr>
 * </table>
 * The three numbers are server config ({@code storage.woodBayStacks} and its two siblings), so a modpack can move the
 * curve without a code change. This layer is pure Java and never reads them itself: content code passes the configured
 * number in, and everything here clamps it into {@link #MIN_STACKS}..{@link #MAX_STACKS} rather than trusting a file a
 * player edits, exactly as {@code core.production.PlanLimits} and {@code core.stock.RestockLimits} do.
 * <p>
 * <b>{@link #strength()} is the whole of the column rule.</b> A bay may carry nothing stronger above it — no andesite
 * on top of wood, because the rack below would give way — so upgrading a wall is a reorganisation from the bottom up,
 * and that reorganisation is what makes the tiers a progression rather than a label. The rule is monotone: the
 * strength of a column never rises going upwards, which is why it is refused in <b>both</b> directions
 * ({@link #mayCarry}) and not only upwards — refusing "stronger above" alone would let the illegal column be built
 * from the top.
 * <p>
 * The declaration order <b>is</b> the strength order and must stay that way: {@link #strength()} is the ordinal, so a
 * tier inserted in the middle rather than appended would silently reorder the column rule.
 */
public enum BayTier {
    /** The cheap early bay: a better barrel, buildable long before there is a warehouse. */
    WOOD(64),
    /** The middle bay. */
    ANDESITE(256),
    /** The strongest bay, level with a fully upgraded drawer. */
    BRASS(1024);

    /** Smallest capacity any configuration may ask for: a bay of a single stack, which is still a bay. */
    public static final int MIN_STACKS = 1;
    /**
     * Largest capacity any configuration may ask for. It is the number that keeps a bay's content count inside an
     * {@code int}: at the ceiling a bay holds {@link #MAX_CAPACITY_ITEMS} items, so a bay's count fits a
     * {@code SlotView.count} and the {@code int} slot limit an item handler reports, while the stock index counts in
     * {@code long} regardless.
     */
    public static final int MAX_STACKS = 4096;
    /**
     * The most items any bay can ever hold: {@link #MAX_STACKS} stacks of the largest stack there is
     * ({@link CapacityMath#STACK_SIZE_CEILING}), which is 405 504 and comfortably inside an {@code int}.
     */
    public static final int MAX_CAPACITY_ITEMS = MAX_STACKS * CapacityMath.STACK_SIZE_CEILING;

    private final int defaultStacks;

    BayTier(int defaultStacks) {
        this.defaultStacks = defaultStacks;
    }

    /** The shipped capacity of this tier in stacks, i.e. the default of its config key. */
    public int defaultStacks() {
        return defaultStacks;
    }

    /**
     * {@link BayFamily#ITEM}: this is the item ladder, and its strengths are comparable only with each other. A fluid
     * bay's tier belongs to the other ladder ({@link FluidBayTier#family()}), and the column rule compares the family
     * <b>before</b> any strength, so a fluid bay ends an item bay's column exactly as air does.
     */
    public BayFamily family() {
        return BayFamily.ITEM;
    }

    /**
     * How much weight this tier carries, rising with capacity: {@code WOOD} 0, {@code ANDESITE} 1, {@code BRASS} 2.
     * It only ever means something compared with another tier's.
     */
    public int strength() {
        return ordinal();
    }

    /** Whether this tier carries more weight than {@code other}, i.e. stands further up the ladder. */
    public boolean isStrongerThan(BayTier other) {
        return strength() > other.strength();
    }

    /**
     * Whether a bay of this tier may stand directly under a bay of tier {@code above}: it may, unless the bay above is
     * the stronger of the two. Equal tiers carry each other, so a column of one material is any height.
     * <p>
     * This is the <b>only</b> form of the column rule, used in both directions: a bay being placed asks it of the bay
     * above it and the bay below asks it of the bay being placed.
     */
    public boolean mayCarry(BayTier above) {
        return !above.isStrongerThan(this);
    }

    /**
     * The configured stack count brought into range. The value comes from a config file a player edits and from a
     * per-world override, so nothing here throws: an absurd number is clamped into
     * {@link #MIN_STACKS}..{@link #MAX_STACKS} and the bay works.
     */
    public static int clampStacks(int stacks) {
        return Math.max(MIN_STACKS, Math.min(stacks, MAX_STACKS));
    }

    /**
     * How many items one bay of this tier holds of an item that stacks to {@code maxStackSize}, given the configured
     * stack count: {@code clampStacks(stacks) * maxStackSize}, which for every stack size Minecraft allows is at most
     * {@link #MAX_CAPACITY_ITEMS}.
     * <p>
     * It is one multiplication because it is asked on every insert, and it never throws for the same reason
     * {@code InventoryGrabber} guards its own stack size: a key whose item reports no sensible stack size counts as 1
     * rather than turning a bay into a hole.
     *
     * @param stacks       the configured capacity of this tier in stacks, clamped here
     * @param maxStackSize how far the stored item stacks, read from the item; a value below 1 counts as 1
     */
    public long capacity(int stacks, int maxStackSize) {
        return (long) clampStacks(stacks) * Math.max(1, maxStackSize);
    }
}
