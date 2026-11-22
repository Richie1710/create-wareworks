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
    void packagesHaveTheirOwnSavedNameAndAbsentMeansItems() {
        assertEquals(RuleUnit.PACKAGES, RuleUnit.byName(RuleUnit.PACKAGES_NAME));
        assertEquals("packages", RuleUnit.PACKAGES.savedName(), "the name a save writes (issue #40)");
        assertEquals("fluid", RuleUnit.FLUID.savedName());
        assertEquals("", RuleUnit.ITEMS.savedName(), "an item rule writes no unit at all");
        assertEquals(RuleUnit.ITEMS, RuleUnit.byName(""), "an absent unit is still an item rule");
        assertEquals(RuleUnit.ITEMS, RuleUnit.byName("PACKAGES"), "the saved name is exact");
        for (RuleUnit unit : RuleUnit.values())
            assertEquals(unit, RuleUnit.byName(unit.savedName()), "every unit reads back from its saved name");
    }

    @Test
    void theWireOrdinalIsBounded() {
        assertEquals(RuleUnit.ITEMS, RuleUnit.byOrdinal(0));
        assertEquals(RuleUnit.FLUID, RuleUnit.byOrdinal(1));
        assertEquals(RuleUnit.PACKAGES, RuleUnit.byOrdinal(2), "appended, so the older ordinals keep their meaning");
        assertEquals(RuleUnit.ITEMS, RuleUnit.byOrdinal(3), "an unknown ordinal counts items");
        assertEquals(RuleUnit.ITEMS, RuleUnit.byOrdinal(-1));
    }

    @Test
    void itemsAndPackagesAreBothCountedOneByOne() {
        assertEquals(10L, RuleUnit.ITEMS.convert(10L, RuleUnit.PACKAGES, 1_000L),
                "a filled container's item row turned into a package row keeps its numbers");
        assertEquals(10L, RuleUnit.PACKAGES.convert(10L, RuleUnit.ITEMS, 1_000L));
        assertEquals(10L, RuleUnit.FLUID.convert(10_000L, RuleUnit.PACKAGES, 1_000L), "millibuckets back to a count");
        assertEquals(10_000L, RuleUnit.PACKAGES.convert(10L, RuleUnit.FLUID, 1_000L));
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
