package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The Missing section of the list table (issue #36, ADR-069): every material the list is short of, summed over the
 * entries that need it, the largest first, with the entries named in list order.
 */
class ListShortageTest {
    private static final String INGOT = "iron_ingot";
    private static final String COAL = "coal";
    private static final String DIAMOND = "diamond";

    @Test
    void nothingShortMeansNoSection() {
        assertTrue(ListShortage.of(List.of(served(0), served(1))).isEmpty());
        assertTrue(ListShortage.<String>of(List.of()).isEmpty());
    }

    @Test
    void theMaterialsOfEveryEntryAreSummedAndTheEntriesNamedInListOrder() {
        List<ListShortage<String>> found = ListShortage.of(List.of(
                shortOf(4, material(INGOT, 2), material(COAL, 1)),
                shortOf(1, material(DIAMOND, 3)),
                shortOf(2, material(INGOT, 5))));
        assertEquals(List.of(INGOT, DIAMOND, COAL), found.stream().map(ListShortage::key).toList(),
                "the largest total first");
        assertEquals(7L, found.get(0).amount());
        assertEquals(List.of(2, 4), found.get(0).entries(), "in list order, whatever order the lines came in");
        assertEquals(List.of(1), found.get(1).entries());
    }

    @Test
    void equalTotalsKeepTheirFirstAppearanceInListOrder() {
        List<ListShortage<String>> found = ListShortage.of(List.of(shortOf(3, material(COAL, 2)),
                shortOf(0, material(DIAMOND, 2))));
        assertEquals(List.of(DIAMOND, COAL), found.stream().map(ListShortage::key).toList());
    }

    @Test
    void totalsSaturateInsteadOfOverflowing() {
        List<ListShortage<String>> found = ListShortage.of(List.of(shortOf(0, material(INGOT, Long.MAX_VALUE)),
                shortOf(1, material(INGOT, Long.MAX_VALUE))));
        assertEquals(Long.MAX_VALUE, found.getFirst().amount());
    }

    private static ListOrderConfirmation.Line<String> served(int order) {
        return new ListOrderConfirmation.Line<>(order, DIAMOND, 4L, 4L, 0L, 0L, 0L, Optional.empty(),
                Optional.empty(), List.of());
    }

    @SafeVarargs
    private static ListOrderConfirmation.Line<String> shortOf(int order,
            ListOrderConfirmation.Material<String>... materials) {
        return new ListOrderConfirmation.Line<>(order, "entry" + order, Long.MAX_VALUE, 0L, 0L, 0L, 0L,
                Optional.empty(), Optional.empty(), List.of(materials));
    }

    private static ListOrderConfirmation.Material<String> material(String key, long amount) {
        return new ListOrderConfirmation.Material<>(key, amount);
    }
}
