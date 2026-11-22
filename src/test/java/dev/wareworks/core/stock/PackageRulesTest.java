package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Package mode's stock rules (issue #40, slice S5, design §7): which boxes a rule counts, the strictest maximum where
 * several rules match one box, what is inbound and what waits at the inputs, the reserve, the signal-only minimum, and
 * the match cache.
 * <p>
 * The matcher here follows Create's {@code PackageItem#matchAddress} (blank only matches blank, {@code *} on either side
 * matches everything, exact, then a glob in either direction), with {@code *} as the only wildcard.
 */
class PackageRulesTest {
    private static final long UNSET = StockRule.UNSET;

    /** Create's address match, with {@code *} as the only glob wildcard. */
    static boolean createLike(String box, String rule) {
        if (rule.isBlank())
            return box.isBlank();
        if (rule.equals("*") || box.equals("*"))
            return true;
        if (rule.equals(box))
            return true;
        return glob(box).matcher(rule).matches() || glob(rule).matcher(box).matches();
    }

    private static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (!regex.isEmpty())
                regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    private static PackageRules.Rule rule(int index, String address, long min, long max, long reserve) {
        return new PackageRules.Rule(index, address, new StockRule<>(address, min, max, reserve));
    }

    private static PackageRules rules(PackageRules.Rule... rules) {
        return PackageRules.of(List.of(rules), PackageRulesTest::createLike);
    }

    @Test
    void aRuleCountsEveryAddressItsGlobMatches() {
        PackageRules rules = rules(rule(0, "Base*", UNSET, UNSET, UNSET), rule(1, "Base North", UNSET, UNSET, UNSET));
        assertArrayEquals(new int[] {0, 1}, rules.matching("Base North"));
        assertArrayEquals(new int[] {0}, rules.matching("Base South"));
        assertArrayEquals(new int[] {}, rules.matching("Outpost"));
        assertArrayEquals(new int[] {0, 1}, rules.matching("*"), "a box addressed * counts towards every rule");
        assertArrayEquals(new int[] {}, rules.matching(""), "a box without an address counts towards none");

        long[] stocked = rules.perRule(Map.of("Base North", 3L, "Base South", 2L, "Outpost", 7L, "*", 1L));
        assertArrayEquals(new long[] {6L, 4L}, stocked, "each rule sums its own group");
    }

    @Test
    void aBlankAddressMakesNoRule() {
        PackageRules rules = rules(rule(0, "", 1, 1, 1), rule(1, "  ", 1, 1, 1));
        assertTrue(rules.isEmpty(), "a row without an address would only count unaddressed boxes");
        assertEquals(Long.MAX_VALUE, rules.headroom("", new long[0], new long[0], null));
        assertTrue(PackageRules.empty().isEmpty());
    }

    @Test
    void theStrictestMaximumOfEveryMatchingRuleWins() {
        PackageRules rules = rules(rule(0, "Base*", UNSET, 10, UNSET), rule(1, "Base North", UNSET, 2, UNSET),
                rule(2, "Outpost", UNSET, UNSET, UNSET));
        long[] stocked = rules.perRule(Map.of("Base North", 2L, "Base South", 7L));
        long[] none = new long[rules.size()];
        assertEquals(0L, rules.headroom("Base North", stocked, none, null),
                "Base North: 2 - 2 = 0 under its own rule, although 10 - 9 = 1 under Base*");
        assertEquals(1L, rules.headroom("Base South", stocked, none, null), "Base South only under Base*");
        assertEquals(0L, rules.headroom("*", stocked, none, null), "a box addressed * never slips past a cap");
        assertEquals(Long.MAX_VALUE, rules.headroom("Outpost", stocked, none, null), "a rule without a maximum");
        assertEquals(Long.MAX_VALUE, rules.headroom("Elsewhere", stocked, none, null), "no matching rule");
        assertTrue(rules.capsAny("Base South"));
        assertFalse(rules.capsAny("Outpost"));
    }

    @Test
    void inboundAndWaitingBoxesCount() {
        PackageRules rules = rules(rule(0, "Base*", UNSET, 4, UNSET));
        long[] stocked = {1L};
        long[] inbound = {1L};
        assertEquals(2L, rules.headroom("Base North", stocked, inbound, null),
                "a box a crane carries in counts (design critique m4)");
        assertEquals(1L, rules.headroom("Base North", stocked, inbound, new long[] {1L}),
                "at an input, what already waits in the inputs counts too");
        assertEquals(0L, rules.headroom("Base North", stocked, inbound, new long[] {5L}),
                "never negative");
        assertEquals(0L, rules(rule(0, "Base*", UNSET, 0, UNSET)).headroom("Base", new long[1], new long[1], null),
                "a maximum of 0 takes nothing");
    }

    @Test
    void theReserveHoldsBoxesBackFromAutomationOnly() {
        PackageRules rules = rules(rule(0, "Base*", UNSET, UNSET, 2), rule(1, "Base North", UNSET, UNSET, 4));
        long[] available = rules.perRule(Map.of("Base North", 5L, "Base South", 3L));
        assertArrayEquals(new long[] {8L, 5L}, available);
        assertEquals(1L, rules.availableTo(StockAccess.AUTOMATION, "Base North", available),
                "the strictest: 5 - 4 = 1 under Base North, 8 - 2 = 6 under Base*");
        assertEquals(6L, rules.availableTo(StockAccess.AUTOMATION, "Base South", available));
        assertEquals(Long.MAX_VALUE, rules.availableTo(StockAccess.PLAYER, "Base North", available),
                "a player is never held back");
        assertEquals(Long.MAX_VALUE, rules.availableTo(StockAccess.AUTOMATION, "Outpost", available));
        assertEquals(0L, rules.availableTo(StockAccess.AUTOMATION, "Base North", new long[] {8L, 3L}),
                "down to the reserve, automation gets nothing");
        assertTrue(rules.reservesAny("Base South"));
        assertFalse(rules.reservesAny("Outpost"));
    }

    @Test
    void theMinimumOnlySignals() {
        PackageRules rules = rules(rule(7, "Base*", 5, 10, 1));
        long[] stocked = {3L};
        long[] inbound = {1L};
        long[] available = {3L};
        StockLevels levels = rules.levelsOf(0, stocked, inbound, available);
        assertEquals(new StockLevels(3L, 1L, 0L, 3L), levels, "nothing is ever expected: the warehouse never packs");
        assertEquals(StockRuleStatus.BELOW_MINIMUM, rules.statusOf(0, levels));
        assertEquals(7, rules.rule(0).index(), "the row keeps its place in the aisle's rule list");
        assertEquals(StockRuleStatus.SATISFIED, rules.statusOf(0, rules.levelsOf(0, new long[] {5L}, new long[1],
                new long[] {5L})));
        assertEquals(StockRuleStatus.AT_MAXIMUM, rules.statusOf(0, rules.levelsOf(0, new long[] {9L},
                new long[] {1L}, new long[] {9L})));
        assertEquals(StockRuleStatus.AT_RESERVE, rules.statusOf(0, rules.levelsOf(0, new long[] {5L}, new long[1],
                new long[] {1L})));
    }

    @Test
    void theGlobIsAskedOncePerBoxAddress() {
        AtomicInteger calls = new AtomicInteger();
        BiPredicate<String, String> counting = (box, rule) -> {
            calls.incrementAndGet();
            return createLike(box, rule);
        };
        PackageRules rules = PackageRules.of(List.of(rule(0, "Base*", UNSET, 1, UNSET), rule(1, "Out*", 1, 1, 1)),
                counting);
        rules.matching("Base North");
        rules.matching("Base North");
        rules.perRule(Map.of("Base North", 1L));
        rules.headroom("Base North", new long[2], new long[2], null);
        assertEquals(2, calls.get(), "one match per rule, for the first question only");
        PackageRules rebuilt = PackageRules.of(rules.rules(), counting);
        rebuilt.matching("Base North");
        assertEquals(4, calls.get(), "a rebuilt rule set (an edited rule) starts with an empty cache");
    }

    @Test
    void theCacheIsBounded() {
        PackageRules rules = rules(rule(0, "Base*", UNSET, 1, UNSET));
        for (int i = 0; i < PackageRules.MATCH_CACHE_BOUND * 2 + 5; i++)
            rules.matching("Box " + i);
        assertArrayEquals(new int[] {0}, rules.matching("Base North"), "still answers after starting over");
    }

    @Test
    void aMatcherThatThrowsMatchesNothing() {
        PackageRules rules = PackageRules.of(List.of(rule(0, "[", UNSET, 1, UNSET)), (box, rule) -> {
            throw new IllegalArgumentException("malformed glob");
        });
        assertArrayEquals(new int[] {}, rules.matching("Base North"));
        assertEquals(Long.MAX_VALUE, rules.headroom("Base North", new long[1], new long[1], null));
    }

    @Test
    void equalRuleSetsAreEqual() {
        assertEquals(rules(rule(0, "Base*", 1, 2, 0)), rules(rule(0, "Base*", 1, 2, 0)));
        assertFalse(rules(rule(0, "Base*", 1, 2, 0)).equals(rules(rule(0, "Base*", 1, 3, 0))),
                "a changed number is a changed rule set");
    }
}
