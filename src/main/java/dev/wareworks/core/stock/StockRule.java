package dev.wareworks.core.stock;

import java.util.Objects;

/**
 * One line of a warehouse stock keeper: an item and the three numbers the warehouse obeys for it (M15, issue #3).
 * <p>
 * <b>The one sentence a player learns: the three numbers govern three different directions.</b>
 * <table border="1">
 * <caption>What each number governs</caption>
 * <tr><th>Value</th><th>Governs</th><th>Never touches</th></tr>
 * <tr><td>{@link #minimum()}</td><td>what comes <b>in</b>: the keeper's comparator calls for the item, and from part 2
 *     the warehouse restocks it back up to this number</td><td>storing, requests</td></tr>
 * <tr><td>{@link #maximum()}</td><td>what may be <b>stored</b>: above it the crane stops accepting the item and a
 *     warehouse input backs up on purpose</td><td>requests, reroutes, what is already stored</td></tr>
 * <tr><td>{@link #reserve()}</td><td>what may go <b>out to automation</b>: a redstone request stops at it, a player at
 *     a terminal may take it and is told so</td><td>storing, a player's own requests</td></tr>
 * </table>
 * <p>
 * <b>Off is a value.</b> Each number is either a real amount or {@link #UNSET}, which a screen shows as "–". The
 * distinction matters for the maximum, where {@code 0} is a meaningful setting ("accept none of this any more") and
 * {@link #UNSET} means "no cap at all"; for the minimum and the reserve, {@code 0} and {@link #UNSET} behave the same
 * and only read differently.
 * <p>
 * <b>Nothing here ever throws on a number.</b> The canonical constructor clamps whatever it is given into range and
 * resolves the two contradictions a player can express ({@code minimum > maximum} raises the maximum,
 * {@code reserve > maximum} lowers the reserve), so a crafted save file, a schematic or a hostile payload can only ever
 * produce a sane rule. {@link #checked} does the same and reports what it had to change, which is what the screen tells
 * the player.
 * <p>
 * Pure Java with no Minecraft types. The identity of the item is one opaque key — the content layer uses
 * {@code ItemKey}, so a rule means exactly what the terminal row it was copied from counts.
 *
 * @param key     the item this rule governs, never {@code null}
 * @param minimum the amount the warehouse tries to keep, or {@link #UNSET}
 * @param maximum the largest amount it will store, or {@link #UNSET} for no cap
 * @param reserve how many of the last items are protected from automation, or {@link #UNSET}
 * @param <K>     item key type
 */
public record StockRule<K>(K key, long minimum, long maximum, long reserve) {
    /** A number that is switched off. A screen shows it as "–"; every query reads it as "no limit". */
    public static final long UNSET = -1L;

    /** The largest amount a number may be set to. Far beyond any warehouse, and small enough that sums cannot hurt. */
    public static final long MAX_AMOUNT = 1_000_000L;

    public StockRule {
        Objects.requireNonNull(key, "key");
        minimum = normalize(minimum);
        maximum = normalize(maximum);
        reserve = normalize(reserve);
        // "Keep at least 64 but store at most 32" cannot be obeyed in either direction; the cap gives way, so the item
        // keeps flowing. The reserve is then clamped against the raised maximum, never the original one.
        if (maximum != UNSET && minimum > maximum)
            maximum = minimum;
        if (maximum != UNSET && reserve > maximum)
            reserve = maximum;
    }

    /** A rule that governs nothing yet: an item with all three numbers off. */
    public static <K> StockRule<K> of(K key) {
        return new StockRule<>(key, UNSET, UNSET, UNSET);
    }

    /**
     * The rule the three numbers produce, together with the one correction that had to be made to them
     * ({@link StockRuleAdjustment}). This is what a validated configuration payload answers with, so a player is told
     * when the value they scrolled to is not the value that was stored.
     */
    public static <K> Adjusted<K> checked(K key, long minimum, long maximum, long reserve) {
        StockRule<K> rule = new StockRule<>(key, minimum, maximum, reserve);
        return new Adjusted<>(rule, rule.adjustmentFrom(minimum, maximum, reserve));
    }

    /**
     * A stored rule and what had to be corrected to get it.
     *
     * @param rule       the rule as it was stored
     * @param adjustment what was corrected, {@link StockRuleAdjustment#NONE} when nothing was
     * @param <K>        item key type
     */
    public record Adjusted<K>(StockRule<K> rule, StockRuleAdjustment adjustment) {
        public Adjusted {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(adjustment, "adjustment");
        }
    }

    // --- the three numbers ---------------------------------------------------------------------------------------

    /** Whether the minimum calls for anything. {@link #UNSET} and 0 are both "off". */
    public boolean hasMinimum() {
        return minimum > 0L;
    }

    /** Whether a storage cap is set. Unlike the other two, {@code 0} is a real setting: "accept none of this". */
    public boolean hasMaximum() {
        return maximum != UNSET;
    }

    /** Whether anything is held back from automation. {@link #UNSET} and 0 are both "off". */
    public boolean hasReserve() {
        return reserve > 0L;
    }

    /** Whether the rule says nothing at all: an item with all three numbers off. It governs and shadows nothing. */
    public boolean isEmpty() {
        return !hasMinimum() && !hasMaximum() && !hasReserve();
    }

    // --- storing (the maximum) -----------------------------------------------------------------------------------

    /**
     * How many more items of the key the warehouse may still store, {@link Long#MAX_VALUE} without a maximum.
     * <p>
     * {@code max(0, maximum + expected − stocked − inbound)}. The {@code + expected} allowance is load-bearing: a
     * production pattern makes whole runs, so an order for 32 regularly comes back as 36, and without the allowance
     * the surplus would be refused at the input, never reach the stock index and strand the order. The rule is:
     * <i>the warehouse always takes back what it sent out for.</i>
     * <p>
     * Partial storing is normal and exact — stock 1990, maximum 2048, a buffer of 64 gives a headroom of 58, and the
     * remaining 6 stay in the input on purpose.
     */
    public long headroom(long stocked, long inbound, long expected) {
        if (!hasMaximum())
            return Long.MAX_VALUE;
        long taken = StockLevels.sum(Math.max(0L, stocked), Math.max(0L, inbound));
        long room = StockLevels.difference(StockLevels.sum(maximum, Math.max(0L, expected)), taken);
        return Math.max(0L, room);
    }

    /** {@link #headroom(long, long, long)} for the levels of one key. */
    public long headroom(StockLevels levels) {
        Objects.requireNonNull(levels, "levels");
        return headroom(levels.stocked(), levels.inbound(), levels.expected());
    }

    /** Whether the maximum leaves no room at all, i.e. nothing of this item may be stored right now. */
    public boolean isAtMaximum(StockLevels levels) {
        return hasMaximum() && headroom(levels) <= 0L;
    }

    // --- calling for items (the minimum) -------------------------------------------------------------------------

    /**
     * How many items the warehouse is short of its minimum, given what it has or has already sent for
     * ({@link StockLevels#pipeline()}); 0 while the minimum is off or met.
     * <p>
     * The pipeline is the whole hysteresis: a rule that has just ordered counts that order and is satisfied until the
     * items arrive or the order ends, so it cannot order the same thing twice.
     */
    public long shortfall(long pipeline) {
        return hasMinimum() ? Math.max(0L, StockLevels.difference(minimum, Math.max(0L, pipeline))) : 0L;
    }

    /** {@link #shortfall(long)} for the levels of one key. */
    public long shortfall(StockLevels levels) {
        Objects.requireNonNull(levels, "levels");
        return shortfall(levels.pipeline());
    }

    /** Whether the warehouse has less of the item than the minimum calls for. */
    public boolean isBelowMinimum(long pipeline) {
        return hasMinimum() && Math.max(0L, pipeline) < minimum;
    }

    /** {@link #isBelowMinimum(long)} for the levels of one key. */
    public boolean isBelowMinimum(StockLevels levels) {
        Objects.requireNonNull(levels, "levels");
        return isBelowMinimum(levels.pipeline());
    }

    // --- handing items out (the reserve) -------------------------------------------------------------------------

    /**
     * How much of {@code available} the given taker may claim: everything for a {@link StockAccess#PLAYER}, everything
     * above the reserve for {@link StockAccess#AUTOMATION}. Never negative.
     */
    public long availableTo(StockAccess access, long available) {
        Objects.requireNonNull(access, "access");
        long free = Math.max(0L, available);
        return access == StockAccess.PLAYER ? free : Math.max(0L, free - Math.max(0L, reserve));
    }

    /**
     * How many of the currently available items the reserve is holding back from automation:
     * {@code min(reserve, available)}. This is the number a terminal row shows next to what is available, so the parts
     * a player is shown still add up to what is in stock.
     */
    public long heldBack(long available) {
        return Math.min(Math.max(0L, reserve), Math.max(0L, available));
    }

    /**
     * How many items of a request for {@code amount} would come out of the reserve. 0 while the request fits above it;
     * never more than {@link #heldBack(long)}, because what is not there at all is not "taken from the reserve", it is
     * simply missing.
     */
    public long fromReserve(long available, long amount) {
        long wanted = Math.max(0L, amount);
        long free = availableTo(StockAccess.AUTOMATION, available);
        return Math.min(Math.max(0L, StockLevels.difference(wanted, free)), heldBack(available));
    }

    /**
     * Whether a request for {@code amount} reaches into the reserve — the hint a terminal row shows a player before
     * they click, and the reason automation stops here.
     */
    public boolean takesFromReserve(long available, long amount) {
        return fromReserve(available, amount) > 0L;
    }

    /** Whether the reserve is holding back everything that is left, so automation gets nothing more. */
    public boolean isAtReserve(long available) {
        return heldBack(available) > 0L && availableTo(StockAccess.AUTOMATION, available) == 0L;
    }

    // --- status --------------------------------------------------------------------------------------------------

    /**
     * What this rule is doing at the given levels, as one value for the lamp, the goggles, the screen and the
     * terminal badge. Only the four non-structural values can be returned; shadowing and the rule cap are decided by
     * {@link StockRules}, an empty filter slot by the content layer.
     */
    public StockRuleStatus statusFor(StockLevels levels) {
        Objects.requireNonNull(levels, "levels");
        if (isEmpty())
            return StockRuleStatus.NO_LIMITS;
        if (isBelowMinimum(levels))
            return StockRuleStatus.BELOW_MINIMUM;
        if (isAtMaximum(levels))
            return StockRuleStatus.AT_MAXIMUM;
        if (isAtReserve(levels.available()))
            return StockRuleStatus.AT_RESERVE;
        return StockRuleStatus.SATISFIED;
    }

    // --- editing -------------------------------------------------------------------------------------------------

    /** The same numbers on another item. */
    public <T> StockRule<T> withKey(T other) {
        return new StockRule<>(other, minimum, maximum, reserve);
    }

    /** The same item with another minimum (clamped and cross-clamped like any other rule). */
    public StockRule<K> withMinimum(long value) {
        return new StockRule<>(key, value, maximum, reserve);
    }

    /** The same item with another maximum. */
    public StockRule<K> withMaximum(long value) {
        return new StockRule<>(key, minimum, value, reserve);
    }

    /** The same item with another reserve. */
    public StockRule<K> withReserve(long value) {
        return new StockRule<>(key, minimum, maximum, value);
    }

    // --- internals -----------------------------------------------------------------------------------------------

    private static long normalize(long value) {
        if (value < 0L)
            return UNSET;
        return Math.min(value, MAX_AMOUNT);
    }

    private static boolean outOfRange(long value) {
        return value < UNSET || value > MAX_AMOUNT;
    }

    private StockRuleAdjustment adjustmentFrom(long rawMinimum, long rawMaximum, long rawReserve) {
        // Only the minimum can have raised the maximum, and only the (possibly raised) maximum can have lowered the
        // reserve, so comparing against the plain range normalization identifies which cross-clamp ran.
        if (maximum != normalize(rawMaximum))
            return StockRuleAdjustment.MAXIMUM_RAISED_TO_MINIMUM;
        if (reserve != normalize(rawReserve))
            return StockRuleAdjustment.RESERVE_CLAMPED_TO_MAXIMUM;
        if (outOfRange(rawMinimum) || outOfRange(rawMaximum) || outOfRange(rawReserve))
            return StockRuleAdjustment.VALUE_CLAMPED;
        return StockRuleAdjustment.NONE;
    }
}
