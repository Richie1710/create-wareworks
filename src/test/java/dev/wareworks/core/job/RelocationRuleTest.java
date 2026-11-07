package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The strictly-better rule of the Warehouse Optimizer (issue #46, ADR-079): a higher priority and a filter that is no
 * worse a match, and nothing else.
 */
class RelocationRuleTest {
    @Test
    void onlyAStrictlyHigherPriorityIsBetter() {
        assertTrue(RelocationRule.isBetter(0, FilterMatch.UNFILTERED, 1, FilterMatch.UNFILTERED));
        assertFalse(RelocationRule.isBetter(3, FilterMatch.UNFILTERED, 3, FilterMatch.UNFILTERED), "equal is not");
        assertFalse(RelocationRule.isBetter(5, FilterMatch.UNFILTERED, 2, FilterMatch.UNFILTERED), "lower is not");
    }

    @Test
    void theTargetMustBeNoWorseAMatch() {
        assertFalse(RelocationRule.isBetter(0, FilterMatch.DEDICATED, 9, FilterMatch.UNFILTERED),
                "a dedicated chest is where every new item goes: emptying it into a general rack is undone");
        assertFalse(RelocationRule.isBetter(0, FilterMatch.DEDICATED, 9, FilterMatch.ALLOWED));
        assertTrue(RelocationRule.isBetter(0, FilterMatch.DEDICATED, 9, FilterMatch.DEDICATED));
        assertTrue(RelocationRule.isBetter(0, FilterMatch.UNFILTERED, 9, FilterMatch.DEDICATED));
        assertTrue(RelocationRule.isBetter(0, FilterMatch.ALLOWED, 9, FilterMatch.UNFILTERED),
                "a deny list ranks like no filter");
        assertTrue(RelocationRule.isBetter(0, FilterMatch.REJECTED, 1, FilterMatch.UNFILTERED),
                "a source re-dedicated to something else is the worst place for its own stock");
    }

    @Test
    void aRejectingTargetIsNeverBetter() {
        assertFalse(RelocationRule.isBetter(0, FilterMatch.REJECTED, 9, FilterMatch.REJECTED));
        assertFalse(RelocationRule.isBetter(0, FilterMatch.UNFILTERED, 9, FilterMatch.REJECTED));
    }

    @Test
    void theRankFormAgreesWithTheFilterForm() {
        for (FilterMatch source : FilterMatch.values()) {
            for (FilterMatch target : FilterMatch.values()) {
                if (!target.allowsStoring())
                    continue;
                for (int priority = -1; priority <= 1; priority++)
                    assertTrue(RelocationRule.isBetter(0, source, priority, target)
                            == RelocationRule.isBetter(0, source.storeRank(), priority, target.storeRank()),
                            source + " -> " + target + " at " + priority);
            }
        }
    }
}
