package dev.wareworks.core.stock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * The stock rules of one aisle, in the order the controller keeps them (M15, issue #3).
 * <p>
 * A warehouse stock keeper holds a <b>list</b> of rules, and an aisle may have several keepers, so the controller
 * concatenates them into one ordered list — keeper by keeper, row by row — and keeps a copy of it. Everything that
 * gates item movement asks this copy and never a block entity, so nothing can be stored past a maximum, and nothing
 * reserved handed out, while a keeper's chunk happens to be unloaded (the M8 cold-cache lesson).
 * <p>
 * <b>Two ways a rule applies nothing</b>, and each says something different to the player:
 * <ul>
 *   <li><b>Shadowed</b> — an earlier rule already governs the same item. The first one wins; the later one is a
 *       duplicate a player has to remove. Merging them ("strictest wins") is deliberately not done: it can produce a
 *       minimum above a maximum, and a player cannot see which row produced the number.</li>
 *   <li><b>Inert</b> — the rule is beyond {@link #cap()} ({@code maxStockRules}). A lowered cap is reversible: nothing
 *       is written back into a keeper, so raising it again makes the rules govern again.</li>
 * </ul>
 * An <b>empty</b> rule ({@link StockRule#isEmpty()}, an item with no number switched on) governs nothing and also
 * shadows nothing — a row a player started and abandoned must not disable the rule they then wrote further down.
 * <p>
 * Immutable, pure Java, O(1) per query after an O(n) construction. The item key must have value equality (the content
 * layer uses {@code ItemKey}).
 *
 * @param <K> item key type
 */
public final class StockRules<K> {
    /**
     * The largest number of rules one aisle holds. A list longer than this is truncated rather than rejected: crafted
     * save data must not be able to make a rule set unbounded, and it must not throw either.
     */
    public static final int MAX_RULES = 256;

    /** A cap that lets every rule govern, for tests and for a warehouse whose configuration imposes none. */
    public static final int NO_CAP = MAX_RULES;

    private static final StockRules<?> EMPTY = new StockRules<>(List.of(), NO_CAP);

    private final List<StockRule<K>> rules;
    private final int cap;
    private final Map<K, Integer> governing;
    private final boolean[] governs;

    private StockRules(List<? extends StockRule<K>> rules, int cap) {
        Objects.requireNonNull(rules, "rules");
        List<StockRule<K>> copy = new ArrayList<>(Math.min(rules.size(), MAX_RULES));
        for (StockRule<K> rule : rules) {
            if (copy.size() == MAX_RULES)
                break;
            copy.add(Objects.requireNonNull(rule, "rule"));
        }
        this.rules = List.copyOf(copy);
        this.cap = Math.max(0, Math.min(cap, MAX_RULES));
        this.governs = new boolean[this.rules.size()];
        Map<K, Integer> owners = new LinkedHashMap<>();
        for (int index = 0; index < this.rules.size() && index < this.cap; index++) {
            StockRule<K> rule = this.rules.get(index);
            if (rule.isEmpty())
                continue; // an unfinished row neither governs nor shadows
            if (owners.putIfAbsent(rule.key(), index) == null)
                this.governs[index] = true;
        }
        this.governing = Collections.unmodifiableMap(owners);
    }

    /** An aisle with no rules: every query answers "no rule", so every caller behaves exactly as before M15. */
    @SuppressWarnings("unchecked")
    public static <K> StockRules<K> empty() {
        return (StockRules<K>) EMPTY;
    }

    /** The rules in the given order, all of them allowed to govern ({@link #NO_CAP}). */
    public static <K> StockRules<K> of(List<? extends StockRule<K>> rules) {
        return of(rules, NO_CAP);
    }

    /**
     * The rules in the given order, with the rules from index {@code cap} on inert.
     *
     * @param rules the aisle's rules, keeper by keeper and row by row; entries beyond {@link #MAX_RULES} are dropped
     * @param cap   {@code maxStockRules}, clamped to {@code 0 … } {@link #MAX_RULES}
     */
    public static <K> StockRules<K> of(List<? extends StockRule<K>> rules, int cap) {
        return new StockRules<>(rules, cap);
    }

    /** The same rules under another cap, e.g. after a config change. */
    public StockRules<K> withCap(int cap) {
        return cap == this.cap ? this : new StockRules<>(rules, cap);
    }

    // --- the list ------------------------------------------------------------------------------------------------

    /** Every rule, inert and shadowed ones included, in aisle order. Unmodifiable. */
    public List<StockRule<K>> rules() {
        return rules;
    }

    /** The rule at {@code index}, whether or not it governs. */
    public StockRule<K> rule(int index) {
        return rules.get(index);
    }

    /** How many rules the aisle holds, inert and shadowed ones included. */
    public int size() {
        return rules.size();
    }

    /** Whether the aisle has no rule at all. */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** How many rules actually apply something. */
    public int governingCount() {
        return governing.size();
    }

    /** The rule cap of the aisle ({@code maxStockRules}); rules from this index on are {@link #isInert inert}. */
    public int cap() {
        return cap;
    }

    // --- what a single rule is -----------------------------------------------------------------------------------

    /** Whether the rule at {@code index} applies its numbers. */
    public boolean governs(int index) {
        Objects.checkIndex(index, rules.size());
        return governs[index];
    }

    /** Whether the rule at {@code index} is beyond the aisle's rule cap and therefore applies nothing. */
    public boolean isInert(int index) {
        Objects.checkIndex(index, rules.size());
        return index >= cap;
    }

    /** Whether an earlier rule already governs the same item, so this one applies nothing. */
    public boolean isShadowed(int index) {
        Objects.checkIndex(index, rules.size());
        return !governs[index] && index < cap && !rules.get(index).isEmpty();
    }

    /**
     * The status of the rule at {@code index}: {@link StockRuleStatus#INERT}, {@link StockRuleStatus#SHADOWED} or
     * {@link StockRuleStatus#NO_LIMITS} for a rule that applies nothing, otherwise what
     * {@link StockRule#statusFor(StockLevels)} makes of the levels. {@code levels} is only asked for a rule that
     * really governs, so reading the warehouse's counters costs nothing for a duplicate or an inert row.
     */
    public StockRuleStatus statusOf(int index, Function<? super K, StockLevels> levels) {
        Objects.requireNonNull(levels, "levels");
        Objects.checkIndex(index, rules.size());
        if (index >= cap)
            return StockRuleStatus.INERT;
        StockRule<K> rule = rules.get(index);
        if (rule.isEmpty())
            return StockRuleStatus.NO_LIMITS;
        if (!governs[index])
            return StockRuleStatus.SHADOWED;
        return rule.statusFor(levelsOf(levels, rule.key()));
    }

    /**
     * Every rule with its status and the levels it was judged against, in aisle order — one pass for the goggles, the
     * screen and the controller's cached counts. {@code levels} is asked once per governing rule and never for an
     * inert, shadowed or empty one.
     */
    public List<StockRuleEvaluation<K>> evaluate(Function<? super K, StockLevels> levels) {
        Objects.requireNonNull(levels, "levels");
        List<StockRuleEvaluation<K>> evaluations = new ArrayList<>(rules.size());
        for (int index = 0; index < rules.size(); index++) {
            StockRule<K> rule = rules.get(index);
            if (index >= cap) {
                evaluations.add(new StockRuleEvaluation<>(index, rule, StockRuleStatus.INERT, StockLevels.NONE));
            } else if (rule.isEmpty()) {
                evaluations.add(new StockRuleEvaluation<>(index, rule, StockRuleStatus.NO_LIMITS, StockLevels.NONE));
            } else if (!governs[index]) {
                evaluations.add(new StockRuleEvaluation<>(index, rule, StockRuleStatus.SHADOWED, StockLevels.NONE));
            } else {
                StockLevels current = levelsOf(levels, rule.key());
                evaluations.add(new StockRuleEvaluation<>(index, rule, rule.statusFor(current), current));
            }
        }
        return List.copyOf(evaluations);
    }

    // --- what governs an item ------------------------------------------------------------------------------------

    /**
     * What the rule that governs {@code key} is doing right now, or empty when no rule governs it — the one answer a
     * terminal row needs, because a row is about an <b>item</b> and not about a position in the rule list.
     * <p>
     * Only a governing rule can be reported here: a shadowed or inert rule for the same item applies nothing, and
     * saying so in a terminal row would tell a player their stock is capped when it is not
     * ({@link #statusOf(int, Function)} is what a keeper's own row asks, where those two answers are the point).
     * {@code levels} is asked exactly once, and never at all for an item nothing governs.
     */
    public Optional<StockRuleStatus> governingStatusOf(K key, Function<? super K, StockLevels> levels) {
        Objects.requireNonNull(levels, "levels");
        Integer index = governing.get(Objects.requireNonNull(key, "key"));
        if (index == null)
            return Optional.empty();
        StockRule<K> rule = rules.get(index);
        return Optional.of(rule.statusFor(levelsOf(levels, rule.key())));
    }

    /** The rule that governs {@code key}, or empty when none does. */
    public Optional<StockRule<K>> ruleFor(K key) {
        Integer index = governing.get(Objects.requireNonNull(key, "key"));
        return index == null ? Optional.empty() : Optional.of(rules.get(index));
    }

    /** Where the rule that governs {@code key} stands, for the "the rule above already governs this" hint. */
    public OptionalInt governingIndexOf(K key) {
        Integer index = governing.get(Objects.requireNonNull(key, "key"));
        return index == null ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /** Whether any rule governs {@code key}. A terminal keeps such an item in its list even at zero stock. */
    public boolean governsKey(K key) {
        return governing.containsKey(Objects.requireNonNull(key, "key"));
    }

    /** The items the aisle has a governing rule for, in aisle order. Unmodifiable. */
    public Set<K> governedKeys() {
        return governing.keySet();
    }

    // --- the three numbers, per item -----------------------------------------------------------------------------

    /**
     * How many more items of {@code key} may be stored; {@link Long#MAX_VALUE} without a governing maximum, which is
     * what the job planner's default is, so an aisle without rules plans exactly as it did before M15.
     */
    public long headroom(K key, long stocked, long inbound, long expected) {
        return ruleFor(key).map(rule -> rule.headroom(stocked, inbound, expected)).orElse(Long.MAX_VALUE);
    }

    /** {@link #headroom(Object, long, long, long)} for known levels. */
    public long headroom(K key, StockLevels levels) {
        Objects.requireNonNull(levels, "levels");
        return headroom(key, levels.stocked(), levels.inbound(), levels.expected());
    }

    /** The reserve that governs {@code key}, 0 when no rule reserves it. */
    public long reserved(K key) {
        return ruleFor(key).map(rule -> Math.max(0L, rule.reserve())).orElse(0L);
    }

    /** How many of {@code available} items of {@code key} are held back from automation; 0 without a rule. */
    public long heldBack(K key, long available) {
        return ruleFor(key).map(rule -> rule.heldBack(available)).orElse(0L);
    }

    /**
     * How many of the {@code available} items of {@code key} the given taker may claim. Without a rule, and for a
     * {@link StockAccess#PLAYER}, this is simply what is available.
     */
    public long availableTo(StockAccess access, K key, long available) {
        Objects.requireNonNull(access, "access");
        return ruleFor(key).map(rule -> rule.availableTo(access, available)).orElse(Math.max(0L, available));
    }

    /** How many items of a request for {@code amount} would come out of the reserve; 0 without a rule. */
    public long fromReserve(K key, long available, long amount) {
        return ruleFor(key).map(rule -> rule.fromReserve(available, amount)).orElse(0L);
    }

    /** Whether a request for {@code amount} reaches into the reserve — the hint a terminal row shows. */
    public boolean takesFromReserve(K key, long available, long amount) {
        return fromReserve(key, available, amount) > 0L;
    }

    /** How many items of {@code key} the warehouse is short of its minimum; 0 without a rule or while it is met. */
    public long shortfall(K key, long pipeline) {
        return ruleFor(key).map(rule -> rule.shortfall(pipeline)).orElse(0L);
    }

    /** Whether a governing rule calls for {@code key} right now. */
    public boolean isBelowMinimum(K key, long pipeline) {
        return ruleFor(key).map(rule -> rule.isBelowMinimum(pipeline)).orElse(false);
    }

    // --- aggregates ----------------------------------------------------------------------------------------------

    /**
     * How many governing rules are below their minimum — the controller's comparator value, and the number its
     * goggles show. {@code pipeline} is asked once per governing rule with a minimum and never for any other.
     */
    public int belowMinimumCount(ToLongFunction<? super K> pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        int count = 0;
        for (Map.Entry<K, Integer> entry : governing.entrySet()) {
            StockRule<K> rule = rules.get(entry.getValue());
            if (rule.hasMinimum() && rule.isBelowMinimum(pipeline.applyAsLong(entry.getKey())))
                count++;
        }
        return count;
    }

    /** Whether any governing rule is below its minimum. Stops at the first one it finds. */
    public boolean anyBelowMinimum(ToLongFunction<? super K> pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        for (Map.Entry<K, Integer> entry : governing.entrySet()) {
            StockRule<K> rule = rules.get(entry.getValue());
            if (rule.hasMinimum() && rule.isBelowMinimum(pipeline.applyAsLong(entry.getKey())))
                return true;
        }
        return false;
    }

    // --- identity ------------------------------------------------------------------------------------------------

    /**
     * Two rule sets are equal when they hold the same rules in the same order under the same cap. The controller
     * compares its copy with a freshly read one this way, so a refresh that changed nothing writes nothing.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof StockRules<?> that))
            return false;
        return cap == that.cap && rules.equals(that.rules);
    }

    @Override
    public int hashCode() {
        return 31 * rules.hashCode() + cap;
    }

    @Override
    public String toString() {
        return "StockRules[" + rules.size() + " rules, " + governing.size() + " governing, cap " + cap + "]";
    }

    private StockLevels levelsOf(Function<? super K, StockLevels> levels, K key) {
        StockLevels current = levels.apply(key);
        return current == null ? StockLevels.NONE : current;
    }
}
