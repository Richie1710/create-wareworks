package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * The rule set of one aisle (M15, issue #3): the order the controller keeps, what happens to a duplicate, what happens
 * above the rule cap, and the neutral answers an item without a rule gets — which are what keeps a warehouse without
 * rules behaving exactly as it did before M15.
 */
class StockRulesTest {
    private static final String IRON = "iron";
    private static final String GOLD = "gold";
    private static final String COPPER = "copper";

    private static StockRule<String> rule(String key, long minimum, long maximum, long reserve) {
        return new StockRule<>(key, minimum, maximum, reserve);
    }

    private static StockRule<String> keeps(String key, long minimum) {
        return rule(key, minimum, StockRule.UNSET, StockRule.UNSET);
    }

    private static StockRule<String> caps(String key, long maximum) {
        return rule(key, StockRule.UNSET, maximum, StockRule.UNSET);
    }

    private static StockRule<String> reserves(String key, long reserve) {
        return rule(key, StockRule.UNSET, StockRule.UNSET, reserve);
    }

    // --- the empty set is the world before M15 ---------------------------------------------------------------------

    @Test
    void anAisleWithoutRulesAnswersNeutrallyToEverything() {
        StockRules<String> none = StockRules.empty();
        assertTrue(none.isEmpty());
        assertEquals(0, none.size());
        assertEquals(0, none.governingCount());
        assertEquals(Optional.empty(), none.ruleFor(IRON));
        assertEquals(OptionalInt.empty(), none.governingIndexOf(IRON));
        assertFalse(none.governsKey(IRON));
        assertTrue(none.governedKeys().isEmpty());
        assertEquals(Long.MAX_VALUE, none.headroom(IRON, 5_000L, 5_000L, 0L), "the job planner's default");
        assertEquals(0L, none.reserved(IRON));
        assertEquals(0L, none.heldBack(IRON, 32L));
        assertEquals(32L, none.availableTo(StockAccess.AUTOMATION, IRON, 32L));
        assertEquals(32L, none.availableTo(StockAccess.PLAYER, IRON, 32L));
        assertEquals(0L, none.fromReserve(IRON, 32L, 32L));
        assertFalse(none.takesFromReserve(IRON, 32L, 32L));
        assertEquals(0L, none.shortfall(IRON, 0L));
        assertFalse(none.isBelowMinimum(IRON, 0L));
        assertEquals(0, none.belowMinimumCount(key -> 0L));
        assertFalse(none.anyBelowMinimum(key -> 0L));
        assertTrue(none.evaluate(key -> StockLevels.NONE).isEmpty());
        assertSame(StockRules.empty(), StockRules.<String>empty(), "one shared instance");
        assertEquals(StockRules.empty(), StockRules.of(List.of()));
    }

    @Test
    void anItemWithoutARuleIsUntouchedByTheOthers() {
        StockRules<String> rules = StockRules.of(List.of(rule(IRON, 64L, 512L, 32L)));
        assertEquals(Long.MAX_VALUE, rules.headroom(GOLD, 10_000L, 0L, 0L));
        assertEquals(0L, rules.reserved(GOLD));
        assertEquals(7L, rules.availableTo(StockAccess.AUTOMATION, GOLD, 7L));
        assertEquals(0L, rules.shortfall(GOLD, 0L));
        assertFalse(rules.governsKey(GOLD));
    }

    // --- order, duplicates and the cap -----------------------------------------------------------------------------

    @Test
    void theRulesKeepTheOrderTheControllerReadThemIn() {
        List<StockRule<String>> list = List.of(keeps(IRON, 64L), caps(GOLD, 32L), reserves(COPPER, 8L));
        StockRules<String> rules = StockRules.of(list);
        assertEquals(list, rules.rules());
        assertEquals(3, rules.size());
        assertEquals(3, rules.governingCount());
        assertEquals(List.of(IRON, GOLD, COPPER), List.copyOf(rules.governedKeys()));
        assertEquals(keeps(IRON, 64L), rules.rule(0));
        assertThrows(UnsupportedOperationException.class, () -> rules.rules().add(keeps(GOLD, 1L)));
        assertThrows(IndexOutOfBoundsException.class, () -> rules.rule(3));
        assertThrows(IndexOutOfBoundsException.class, () -> rules.governs(3));
        assertThrows(IndexOutOfBoundsException.class, () -> rules.isShadowed(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> rules.isInert(7));
    }

    /** The first rule for an item wins; a second one is a duplicate the player has to remove, not a merge. */
    @Test
    void aDuplicateIsShadowedAndAppliesNothing() {
        StockRules<String> rules = StockRules.of(List.of(rule(IRON, 64L, 512L, 32L), rule(IRON, 1_000L, 16L, 900L),
                keeps(GOLD, 8L)));
        assertTrue(rules.governs(0));
        assertFalse(rules.governs(1));
        assertTrue(rules.isShadowed(1));
        assertFalse(rules.isInert(1));
        assertTrue(rules.governs(2), "another item is not affected by the duplicate");
        assertEquals(2, rules.governingCount());
        assertEquals(Optional.of(rule(IRON, 64L, 512L, 32L)), rules.ruleFor(IRON));
        assertEquals(OptionalInt.of(0), rules.governingIndexOf(IRON), "the hint names the rule that does govern");
        // None of the shadowed rule's much stricter numbers applies.
        assertEquals(512L - 100L, rules.headroom(IRON, 100L, 0L, 0L));
        assertEquals(32L, rules.reserved(IRON));
        assertFalse(rules.isBelowMinimum(IRON, 100L), "the shadowed minimum of 1000 says nothing");
    }

    /** A row a player started and abandoned must not disable the rule they then wrote further down. */
    @Test
    void anEmptyRowNeitherGovernsNorShadows() {
        StockRule<String> unfinished = StockRule.of(IRON);
        StockRules<String> rules = StockRules.of(List.of(unfinished, rule(IRON, 64L, 512L, 32L)));
        assertFalse(rules.governs(0));
        assertFalse(rules.isShadowed(0), "it shadows nothing, so it is not reported as a duplicate");
        assertFalse(rules.isInert(0));
        assertEquals(StockRuleStatus.NO_LIMITS, rules.statusOf(0, key -> StockLevels.NONE));
        assertTrue(rules.governs(1));
        assertEquals(OptionalInt.of(1), rules.governingIndexOf(IRON));
        assertEquals(32L, rules.reserved(IRON));
    }

    @Test
    void rulesBeyondTheCapAreInertAndTheCapIsReversible() {
        List<StockRule<String>> list = List.of(keeps(IRON, 64L), keeps(GOLD, 8L), keeps(COPPER, 16L));
        StockRules<String> capped = StockRules.of(list, 2);
        assertEquals(3, capped.size(), "nothing is thrown away, it only stops applying");
        assertEquals(2, capped.governingCount());
        assertTrue(capped.governs(1));
        assertFalse(capped.governs(2));
        assertTrue(capped.isInert(2));
        assertFalse(capped.isShadowed(2), "it is beyond the cap, not a duplicate");
        assertFalse(capped.governsKey(COPPER));
        assertEquals(StockRuleStatus.INERT, capped.statusOf(2, key -> StockLevels.NONE));
        StockRules<String> raised = capped.withCap(StockRules.NO_CAP);
        assertTrue(raised.governs(2), "raising the cap makes it govern again: nothing was written back");
        assertEquals(3, raised.governingCount());
        assertSame(capped, capped.withCap(2));
        assertEquals(0, StockRules.of(list, 0).governingCount(), "a cap of zero switches every rule off");
        assertEquals(0, StockRules.of(list, -5).cap(), "a negative cap reads as zero");
        assertEquals(StockRules.MAX_RULES, StockRules.of(list, Integer.MAX_VALUE).cap());
    }

    /** A duplicate beyond the cap is inert; the cap is structural and is reported before the duplicate. */
    @Test
    void theCapIsReportedBeforeTheDuplicate() {
        StockRules<String> rules = StockRules.of(List.of(keeps(IRON, 64L), keeps(IRON, 8L)), 1);
        assertTrue(rules.isInert(1));
        assertFalse(rules.isShadowed(1));
        assertEquals(StockRuleStatus.INERT, rules.statusOf(1, key -> StockLevels.NONE));
    }

    @Test
    void anAbsurdlyLongListIsTruncatedRatherThanRefused() {
        List<StockRule<String>> many = new ArrayList<>();
        for (int i = 0; i < StockRules.MAX_RULES + 50; i++)
            many.add(keeps("item" + i, 1L));
        StockRules<String> rules = StockRules.of(many);
        assertEquals(StockRules.MAX_RULES, rules.size());
        assertEquals(StockRules.MAX_RULES, rules.governingCount());
        assertThrows(NullPointerException.class, () -> StockRules.of(Arrays.asList(keeps(IRON, 1L), null)));
    }

    // --- the numbers, per item -------------------------------------------------------------------------------------

    @Test
    void theGoverningRuleAnswersForItsItem() {
        StockRules<String> rules = StockRules.of(List.of(rule(IRON, 64L, 512L, 32L)));
        assertEquals(412L, rules.headroom(IRON, 100L, 0L, 0L));
        assertEquals(412L, rules.headroom(IRON, new StockLevels(100L, 0L, 0L, 100L)));
        assertEquals(376L, rules.headroom(IRON, 100L, 36L, 0L));
        assertEquals(32L, rules.reserved(IRON));
        assertEquals(32L, rules.heldBack(IRON, 100L));
        assertEquals(10L, rules.heldBack(IRON, 10L));
        assertEquals(68L, rules.availableTo(StockAccess.AUTOMATION, IRON, 100L));
        assertEquals(100L, rules.availableTo(StockAccess.PLAYER, IRON, 100L));
        assertEquals(12L, rules.fromReserve(IRON, 100L, 80L));
        assertTrue(rules.takesFromReserve(IRON, 100L, 80L));
        assertFalse(rules.takesFromReserve(IRON, 100L, 68L));
        assertEquals(24L, rules.shortfall(IRON, 40L));
        assertTrue(rules.isBelowMinimum(IRON, 63L));
        assertFalse(rules.isBelowMinimum(IRON, 64L));
    }

    // --- status and evaluation -------------------------------------------------------------------------------------

    @Test
    void everyRuleIsEvaluatedInOrderWithItsOwnLevels() {
        Map<String, StockLevels> levels = new HashMap<>();
        levels.put(IRON, StockLevels.stored(10L));
        levels.put(GOLD, StockLevels.stored(32L));
        levels.put(COPPER, StockLevels.stored(200L));
        StockRules<String> rules = StockRules.of(List.of(keeps(IRON, 64L), caps(GOLD, 32L), reserves(COPPER, 8L),
                keeps(IRON, 10L), StockRule.of("tin")), 4);
        List<StockRuleEvaluation<String>> evaluations = rules.evaluate(key -> levels.getOrDefault(key,
                StockLevels.NONE));
        assertEquals(5, evaluations.size());
        assertEquals(StockRuleStatus.BELOW_MINIMUM, evaluations.get(0).status());
        assertEquals(54L, evaluations.get(0).shortfall());
        assertEquals(Long.MAX_VALUE, evaluations.get(0).headroom(), "this rule caps nothing");
        assertEquals(StockRuleStatus.AT_MAXIMUM, evaluations.get(1).status());
        assertEquals(0L, evaluations.get(1).headroom());
        assertEquals(StockRuleStatus.SATISFIED, evaluations.get(2).status());
        assertEquals(8L, evaluations.get(2).heldBack(), "8 of the 200 are held back, which is not yet a bite");
        assertEquals(StockRuleStatus.SHADOWED, evaluations.get(3).status());
        assertEquals(StockRuleStatus.INERT, evaluations.get(4).status());
        for (int index = 0; index < evaluations.size(); index++) {
            StockRuleEvaluation<String> evaluation = evaluations.get(index);
            assertEquals(index, evaluation.index());
            assertEquals(rules.rule(index), evaluation.rule());
            assertEquals(rules.statusOf(index, key -> levels.getOrDefault(key, StockLevels.NONE)),
                    evaluation.status());
            assertEquals(rules.governs(index), evaluation.governs());
        }
    }

    @Test
    void aRuleThatAppliesNothingCapsNothingAndReservesNothing() {
        StockRules<String> rules = StockRules.of(List.of(rule(IRON, 64L, 512L, 32L), caps(IRON, 0L)));
        StockRuleEvaluation<String> shadowed = rules.evaluate(key -> StockLevels.stored(100L)).get(1);
        assertEquals(StockRuleStatus.SHADOWED, shadowed.status());
        assertFalse(shadowed.governs());
        assertFalse(shadowed.bites());
        assertEquals(Long.MAX_VALUE, shadowed.headroom(), "its maximum of 0 would stop every delivery");
        assertEquals(0L, shadowed.shortfall());
        assertEquals(0L, shadowed.heldBack());
        assertEquals(StockLevels.NONE, shadowed.levels());
        assertEquals(IRON, shadowed.key());
    }

    /** Reading the warehouse's counters costs something, so a rule that applies nothing must not ask for them. */
    @Test
    void theLevelsAreOnlyLookedUpForRulesThatGovern() {
        List<String> asked = new ArrayList<>();
        Function<String, StockLevels> counting = key -> {
            asked.add(key);
            return StockLevels.stored(100L);
        };
        StockRules<String> rules = StockRules.of(List.of(keeps(IRON, 64L), keeps(IRON, 8L), StockRule.of(GOLD),
                keeps(COPPER, 16L)), 3);
        rules.evaluate(counting);
        assertEquals(List.of(IRON), asked, "not for the duplicate, the empty row or the rule beyond the cap");
        asked.clear();
        rules.statusOf(1, counting);
        rules.statusOf(2, counting);
        rules.statusOf(3, counting);
        assertTrue(asked.isEmpty());
        assertEquals(StockRuleStatus.SATISFIED, rules.statusOf(0, counting));
        assertEquals(List.of(IRON), asked);
    }

    /**
     * What a terminal row asks: a row is about an <b>item</b>, so only the rule that really governs it may answer.
     */
    @Test
    void theStatusOfAnItemIsTheStatusOfTheRuleThatGovernsIt() {
        Map<String, StockLevels> levels = new HashMap<>(Map.of(IRON, StockLevels.stored(10L),
                GOLD, StockLevels.stored(100L)));
        Function<String, StockLevels> lookup = key -> levels.getOrDefault(key, StockLevels.NONE);
        StockRules<String> rules = StockRules.of(List.of(keeps(IRON, 64L), caps(IRON, 4L), caps(GOLD, 100L)));
        assertEquals(Optional.of(StockRuleStatus.BELOW_MINIMUM), rules.governingStatusOf(IRON, lookup),
                "the first rule governs iron; the shadowed cap below it says nothing about the item");
        assertEquals(Optional.of(StockRuleStatus.AT_MAXIMUM), rules.governingStatusOf(GOLD, lookup));
        assertEquals(Optional.empty(), rules.governingStatusOf(COPPER, lookup), "no rule, no badge");
        assertEquals(StockRuleStatus.SHADOWED, rules.statusOf(1, lookup),
                "the keeper's own row still reports why it does nothing");
    }

    @Test
    void anItemWhoseOnlyRuleIsInertOrEmptyHasNoStatus() {
        Function<String, StockLevels> lookup = key -> StockLevels.stored(10L);
        StockRules<String> capped = StockRules.of(List.of(keeps(IRON, 64L), keeps(GOLD, 64L)), 1);
        assertEquals(Optional.empty(), capped.governingStatusOf(GOLD, lookup), "beyond the rule cap");
        assertEquals(Optional.empty(), StockRules.of(List.of(StockRule.of(IRON))).governingStatusOf(IRON, lookup),
                "a row with an item and no number governs nothing");
    }

    @Test
    void theStatusOfAnUnruledItemCostsNoLevelLookup() {
        List<String> asked = new ArrayList<>();
        Function<String, StockLevels> counting = key -> {
            asked.add(key);
            return StockLevels.stored(100L);
        };
        StockRules<String> rules = StockRules.of(List.of(keeps(IRON, 64L)));
        assertEquals(Optional.empty(), rules.governingStatusOf(GOLD, counting));
        assertTrue(asked.isEmpty(), "a terminal full of unruled items reads no counter at all");
        assertEquals(Optional.of(StockRuleStatus.SATISFIED), rules.governingStatusOf(IRON, counting));
        assertEquals(List.of(IRON), asked, "and exactly once for a ruled one");
    }

    @Test
    void levelsThatAreNotKnownReadAsNothing() {
        StockRules<String> rules = StockRules.of(List.of(keeps(IRON, 64L)));
        assertEquals(StockRuleStatus.BELOW_MINIMUM, rules.statusOf(0, key -> null));
        assertEquals(StockRuleStatus.BELOW_MINIMUM, rules.evaluate(key -> null).get(0).status());
    }

    // --- the aggregates a comparator reads -------------------------------------------------------------------------

    @Test
    void theBelowMinimumCountIsWhatTheComparatorShows() {
        Map<String, Long> pipeline = new HashMap<>(Map.of(IRON, 10L, GOLD, 100L, COPPER, 0L));
        StockRules<String> rules = StockRules.of(List.of(keeps(IRON, 64L), keeps(GOLD, 64L), caps(COPPER, 16L),
                keeps(IRON, 1_000L)));
        assertEquals(1, rules.belowMinimumCount(key -> pipeline.getOrDefault(key, 0L)),
                "gold is met, copper has no minimum and the duplicate iron rule applies nothing");
        assertTrue(rules.anyBelowMinimum(key -> pipeline.getOrDefault(key, 0L)));
        pipeline.put(IRON, 64L);
        assertEquals(0, rules.belowMinimumCount(key -> pipeline.getOrDefault(key, 0L)), "exactly met is met");
        assertFalse(rules.anyBelowMinimum(key -> pipeline.getOrDefault(key, 0L)));
        pipeline.put(IRON, 63L);
        pipeline.put(GOLD, 0L);
        assertEquals(2, rules.belowMinimumCount(key -> pipeline.getOrDefault(key, 0L)));
    }

    @Test
    void aRuleWithoutAMinimumIsNeverAskedForItsPipeline() {
        List<String> asked = new ArrayList<>();
        StockRules<String> rules = StockRules.of(List.of(caps(IRON, 16L), reserves(GOLD, 8L), keeps(COPPER, 4L)));
        rules.belowMinimumCount(key -> {
            asked.add(key);
            return 0L;
        });
        assertEquals(List.of(COPPER), asked);
    }

    // --- identity --------------------------------------------------------------------------------------------------

    @Test
    void ruleSetsAreEqualWhenTheyHoldTheSameRulesInTheSameOrder() {
        List<StockRule<String>> list = List.of(keeps(IRON, 64L), caps(GOLD, 32L));
        assertEquals(StockRules.of(list), StockRules.of(new ArrayList<>(list)));
        assertEquals(StockRules.of(list).hashCode(), StockRules.of(new ArrayList<>(list)).hashCode());
        assertNotEquals(StockRules.of(list), StockRules.of(List.of(caps(GOLD, 32L), keeps(IRON, 64L))));
        assertNotEquals(StockRules.of(list), StockRules.of(list, 1));
        assertNotEquals(StockRules.of(list), StockRules.of(List.of(keeps(IRON, 65L), caps(GOLD, 32L))));
        assertNotEquals(StockRules.of(list), "not a rule set");
        assertTrue(StockRules.of(list).toString().contains("2 rules"));
    }

    // --- the property the whole cache rests on ---------------------------------------------------------------------

    /**
     * Whatever list, cap and numbers a warehouse produces, every answer is the one a plain linear scan of the list
     * gives, and every index is in exactly one of the four states. In the house style of {@code StockIndexTest} and
     * {@code ReservationLedgerTest}.
     */
    @Test
    void randomRuleSetsAnswerLikeAFullRecomputation() {
        Random random = new Random(20260925L);
        List<String> keys = List.of(IRON, GOLD, COPPER, "tin");
        long[] numbers = { StockRule.UNSET, 0L, 1L, 16L, 64L, 512L, StockRule.MAX_AMOUNT };
        for (int round = 0; round < 2_000; round++) {
            List<StockRule<String>> list = new ArrayList<>();
            int size = random.nextInt(8);
            for (int i = 0; i < size; i++)
                list.add(new StockRule<>(keys.get(random.nextInt(keys.size())),
                        numbers[random.nextInt(numbers.length)], numbers[random.nextInt(numbers.length)],
                        numbers[random.nextInt(numbers.length)]));
            int cap = random.nextInt(10) - 1;
            StockRules<String> rules = StockRules.of(list, cap);
            int effectiveCap = Math.max(0, Math.min(cap, StockRules.MAX_RULES));
            assertEquals(effectiveCap, rules.cap());

            Map<String, Integer> expectedOwner = new HashMap<>();
            for (int index = 0; index < list.size() && index < effectiveCap; index++) {
                StockRule<String> rule = list.get(index);
                if (!rule.isEmpty())
                    expectedOwner.putIfAbsent(rule.key(), index);
            }
            assertEquals(expectedOwner.size(), rules.governingCount());
            for (int index = 0; index < list.size(); index++) {
                boolean inert = index >= effectiveCap;
                boolean empty = list.get(index).isEmpty();
                boolean governs = !inert && !empty && expectedOwner.get(list.get(index).key()) == index;
                boolean shadowed = !inert && !empty && !governs;
                assertEquals(governs, rules.governs(index), "governs at " + index);
                assertEquals(inert, rules.isInert(index), "inert at " + index);
                assertEquals(shadowed, rules.isShadowed(index), "shadowed at " + index);
                assertEquals(1, (governs ? 1 : 0) + (inert ? 1 : 0) + (shadowed ? 1 : 0) + (empty && !inert ? 1 : 0),
                        "exactly one state at " + index);
            }
            long stocked = random.nextInt(2_000);
            long inbound = random.nextInt(100);
            long expected = random.nextInt(100);
            long available = random.nextInt((int) stocked + 1);
            long amount = random.nextInt(2_000);
            for (String key : keys) {
                Integer owner = expectedOwner.get(key);
                StockRule<String> rule = owner == null ? null : list.get(owner);
                assertEquals(Optional.ofNullable(rule), rules.ruleFor(key));
                assertEquals(owner == null ? OptionalInt.empty() : OptionalInt.of(owner),
                        rules.governingIndexOf(key));
                assertEquals(rule != null, rules.governsKey(key));
                assertEquals(rule == null ? Long.MAX_VALUE : rule.headroom(stocked, inbound, expected),
                        rules.headroom(key, stocked, inbound, expected));
                assertEquals(rule == null ? 0L : Math.max(0L, rule.reserve()), rules.reserved(key));
                assertEquals(rule == null ? available : rule.availableTo(StockAccess.AUTOMATION, available),
                        rules.availableTo(StockAccess.AUTOMATION, key, available));
                assertEquals(available, rules.availableTo(StockAccess.PLAYER, key, available),
                        "a player is never held back");
                assertEquals(rule == null ? 0L : rule.fromReserve(available, amount),
                        rules.fromReserve(key, available, amount));
                assertEquals(rule == null ? 0L : rule.shortfall(stocked), rules.shortfall(key, stocked));
                assertEquals(rule != null && rule.isBelowMinimum(stocked), rules.isBelowMinimum(key, stocked));
            }
            int expectedShort = 0;
            for (Integer owner : expectedOwner.values()) {
                if (list.get(owner).isBelowMinimum(stocked))
                    expectedShort++;
            }
            assertEquals(expectedShort, rules.belowMinimumCount(key -> stocked));
            assertEquals(expectedShort > 0, rules.anyBelowMinimum(key -> stocked));
            assertEquals(list.size(), rules.evaluate(key -> new StockLevels(stocked, inbound, expected, available))
                    .size());
        }
    }
}
