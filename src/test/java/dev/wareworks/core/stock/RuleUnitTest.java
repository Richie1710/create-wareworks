package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a stock keeper row counts (M31, issue #21, ADR-073): the saved name, and how the three numbers move when a
 * player switches a filled container's row between counting items and counting fluid.
 */
class RuleUnitTest {
    @Test
    void onlyFluidHasASavedName() {
        assertEquals(RuleUnit.FLUID, RuleUnit.byName(RuleUnit.FLUID_NAME));
        assertEquals(RuleUnit.ITEMS, RuleUnit.byName(""), "no name, as every row before M31 has, counts items");
        assertEquals(RuleUnit.ITEMS, RuleUnit.byName("FLUID"), "the saved name is exact");
        assertEquals(RuleUnit.ITEMS, RuleUnit.byName("garbage"));
    }

    @Test
    void itemsBecomeTheirMillibucketsAndBack() {
        assertEquals(10_000L, RuleUnit.ITEMS.convert(10L, RuleUnit.FLUID, 1_000L), "ten lava buckets");
        assertEquals(10L, RuleUnit.FLUID.convert(10_000L, RuleUnit.ITEMS, 1_000L));
        assertEquals(2L, RuleUnit.FLUID.convert(2_999L, RuleUnit.ITEMS, 1_000L), "rounded down, never more");
        assertEquals(0L, RuleUnit.ITEMS.convert(0L, RuleUnit.FLUID, 1_000L), "a maximum of 0 stays 0");
    }

    @Test
    void offStaysOffAndNothingOverflows() {
        assertEquals(StockRule.UNSET, RuleUnit.ITEMS.convert(StockRule.UNSET, RuleUnit.FLUID, 1_000L));
        assertEquals(StockRule.UNSET, RuleUnit.FLUID.convert(-7L, RuleUnit.ITEMS, 1_000L));
        assertEquals(StockRule.MAX_AMOUNT, RuleUnit.ITEMS.convert(StockRule.MAX_AMOUNT, RuleUnit.FLUID, 1_000L),
                "a capped item number is the cap in millibuckets too");
        assertEquals(StockRule.MAX_AMOUNT, RuleUnit.ITEMS.convert(Long.MAX_VALUE / 2, RuleUnit.FLUID, 1_000L));
    }

    @Test
    void theSameUnitOrNoCapacityChangesNothing() {
        assertEquals(64L, RuleUnit.ITEMS.convert(64L, RuleUnit.ITEMS, 1_000L));
        assertEquals(64L, RuleUnit.ITEMS.convert(64L, RuleUnit.FLUID, 0L), "an item that carries no fluid");
    }
}
