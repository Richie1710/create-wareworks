package dev.wareworks.core.stock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * The stock rules of an aisle that count <b>sealed packages by address</b> (issue #40, package mode, design §7): the
 * keeper rows of unit {@link RuleUnit#PACKAGES}, each one a Package Filter's address and the three numbers.
 * <p>
 * <b>What a rule counts.</b> Every box whose address the rule's address matches, by the matcher the content layer
 * hands in (Create's own {@code PackageItem#matchAddress}: blank only matches blank, {@code *} on either side matches
 * everything, otherwise an exact match or a glob in either direction). So a box addressed {@code *} counts towards
 * every rule, and a rule {@code Base*} counts {@code Base North} and {@code Base South} together.
 * <p>
 * <b>Several rules for one box.</b> Package rules are not "first rule governs" like item rules: each rule governs its
 * own group of addresses, and where several match one box, the <b>strictest</b> of them applies — the least headroom
 * under a maximum, the least left above a reserve. A box addressed {@code *} therefore never slips past any cap.
 * <p>
 * <b>The numbers.</b> The maximum caps what may be stored (with what is inbound, and at a warehouse input with what
 * already waits there); the reserve holds boxes back from {@link StockAccess#AUTOMATION}; the minimum is only
 * <b>reported</b> ({@link StockRuleStatus#BELOW_MINIMUM}): the warehouse never packs anything.
 * <p>
 * <b>Cost.</b> The glob is compiled by Create on every match, so the answer per box address is cached here, bounded;
 * a changed rule set is a new instance, so the cache can never answer for rules that are gone. Pure Java: the address
 * of a box and the counts per address are the caller's.
 */
public final class PackageRules {
    /** Box addresses whose matches are remembered before the cache starts over. */
    static final int MATCH_CACHE_BOUND = 4096;

    private static final PackageRules EMPTY = new PackageRules(List.of(), (box, rule) -> false);
    private static final int[] NONE = new int[0];

    /**
     * One package rule.
     *
     * @param index   where the row stands in the aisle's rule list ({@code StockRules} index), for the keeper's screen
     * @param address the Package Filter's address, never blank
     * @param rule    the three numbers, keyed by the address
     */
    public record Rule(int index, String address, StockRule<String> rule) {
        public Rule {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(rule, "rule");
        }
    }

    private final List<Rule> rules;
    private final BiPredicate<String, String> matcher;
    private final Map<String, int[]> matchCache = new HashMap<>();

    private PackageRules(List<Rule> rules, BiPredicate<String, String> matcher) {
        this.rules = rules;
        this.matcher = matcher;
    }

    /** No package rule at all: every question answers neutrally. */
    public static PackageRules empty() {
        return EMPTY;
    }

    /**
     * @param rules   the rules in aisle order; a rule with a blank address is left out (it would match only boxes without
     *                an address, which no row can mean)
     * @param matcher {@code (boxAddress, ruleAddress) -> matches}
     */
    public static PackageRules of(List<Rule> rules, BiPredicate<String, String> matcher) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(matcher, "matcher");
        List<Rule> kept = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            if (!rule.address().isBlank())
                kept.add(rule);
        }
        return kept.isEmpty() ? EMPTY : new PackageRules(List.copyOf(kept), matcher);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }

    /** The rules in aisle order. */
    public List<Rule> rules() {
        return rules;
    }

    public Rule rule(int position) {
        return rules.get(position);
    }

    /**
     * The positions (in {@link #rules()}) of the rules a box addressed {@code boxAddress} counts towards. Cached per
     * address; the returned array must not be changed.
     */
    public int[] matching(String boxAddress) {
        Objects.requireNonNull(boxAddress, "boxAddress");
        if (rules.isEmpty())
            return NONE;
        int[] known = matchCache.get(boxAddress);
        if (known != null)
            return known;
        int[] found = new int[rules.size()];
        int count = 0;
        for (int position = 0; position < rules.size(); position++) {
            if (matches(boxAddress, rules.get(position).address()))
                found[count++] = position;
        }
        int[] result = count == 0 ? NONE : Arrays.copyOf(found, count);
        if (matchCache.size() >= MATCH_CACHE_BOUND)
            matchCache.clear();
        matchCache.put(boxAddress, result);
        return result;
    }

    private boolean matches(String boxAddress, String ruleAddress) {
        try {
            return matcher.test(boxAddress, ruleAddress);
        } catch (RuntimeException e) {
            return false; // a malformed glob matches nothing rather than taking the caller down
        }
    }

    /** Whether some rule with a maximum counts a box addressed {@code boxAddress}. */
    public boolean capsAny(String boxAddress) {
        for (int position : matching(boxAddress)) {
            if (rules.get(position).rule().hasMaximum())
                return true;
        }
        return false;
    }

    /** Whether some rule with a reserve counts a box addressed {@code boxAddress}. */
    public boolean reservesAny(String boxAddress) {
        for (int position : matching(boxAddress)) {
            if (rules.get(position).rule().hasReserve())
                return true;
        }
        return false;
    }

    /**
     * Per rule, the sum of {@code byAddress} over the addresses it counts: boxes stored, inbound, waiting or available,
     * whatever the map holds. One entry per rule, in the order of {@link #rules()}; saturating.
     */
    public long[] perRule(Map<String, Long> byAddress) {
        Objects.requireNonNull(byAddress, "byAddress");
        long[] sums = new long[rules.size()];
        if (rules.isEmpty())
            return sums;
        for (Map.Entry<String, Long> entry : byAddress.entrySet()) {
            long count = entry.getValue() == null ? 0L : Math.max(0L, entry.getValue());
            if (count == 0L)
                continue;
            for (int position : matching(entry.getKey()))
                sums[position] = StockLevels.sum(sums[position], count);
        }
        return sums;
    }

    /**
     * How many more boxes addressed {@code boxAddress} may come in: the least headroom of every matching rule with a
     * maximum, each measured as {@code maximum − stocked − inbound − waiting} of its own group; {@link Long#MAX_VALUE}
     * when no such rule counts the box.
     *
     * @param stocked per rule ({@link #perRule}), what the racks hold
     * @param inbound per rule, what a transport job is bringing in
     * @param waiting per rule, what already waits in the warehouse's inputs; {@code null} for none (the planner's own
     *                question, which plans exactly those boxes)
     */
    public long headroom(String boxAddress, long[] stocked, long[] inbound, long[] waiting) {
        long least = Long.MAX_VALUE;
        for (int position : matching(boxAddress)) {
            StockRule<String> rule = rules.get(position).rule();
            if (!rule.hasMaximum())
                continue;
            long taken = StockLevels.sum(at(stocked, position), waiting == null ? 0L : at(waiting, position));
            least = Math.min(least, rule.headroom(taken, at(inbound, position), 0L));
        }
        return least;
    }

    /**
     * How many boxes addressed {@code boxAddress} the given taker may have: {@link Long#MAX_VALUE} for a
     * {@link StockAccess#PLAYER} and without a matching reserve; otherwise the least of what each matching rule with a
     * reserve leaves above it of its own group's {@code available} boxes.
     */
    public long availableTo(StockAccess access, String boxAddress, long[] available) {
        Objects.requireNonNull(access, "access");
        if (access == StockAccess.PLAYER)
            return Long.MAX_VALUE;
        long least = Long.MAX_VALUE;
        for (int position : matching(boxAddress)) {
            StockRule<String> rule = rules.get(position).rule();
            if (rule.hasReserve())
                least = Math.min(least, rule.availableTo(StockAccess.AUTOMATION, at(available, position)));
        }
        return least;
    }

    /** The levels of the rule at {@code position}: stocked, inbound, nothing expected (nothing is packed), available. */
    public StockLevels levelsOf(int position, long[] stocked, long[] inbound, long[] available) {
        return new StockLevels(at(stocked, position), at(inbound, position), 0L, at(available, position));
    }

    /** What the rule at {@code position} says at its levels ({@link StockRule#statusFor}). */
    public StockRuleStatus statusOf(int position, StockLevels levels) {
        return rules.get(position).rule().statusFor(levels);
    }

    private static long at(long[] values, int position) {
        return values == null || position >= values.length ? 0L : Math.max(0L, values[position]);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PackageRules that && rules.equals(that.rules);
    }

    @Override
    public int hashCode() {
        return rules.hashCode();
    }

    @Override
    public String toString() {
        return "PackageRules" + rules;
    }
}
