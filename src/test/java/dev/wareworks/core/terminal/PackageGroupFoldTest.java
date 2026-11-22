package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.inventory.KeyCount;

/**
 * Package mode's terminal rows (issue #40, owner decision E5): every stored box folded into one row per address, with
 * what is inside summed over the boxes, before the window is cut.
 */
class PackageGroupFoldTest {
    private static List<KeyCount<String>> holds(Object... keysAndCounts) {
        List<KeyCount<String>> contents = new ArrayList<>();
        for (int i = 0; i < keysAndCounts.length; i += 2)
            contents.add(new KeyCount<>((String) keysAndCounts[i], ((Number) keysAndCounts[i + 1]).longValue()));
        return contents;
    }

    private static Optional<PackageGroupFold.Row<String>> row(List<PackageGroupFold.Row<String>> rows,
            String address) {
        return rows.stream().filter(row -> row.address().equals(address)).findFirst();
    }

    @Test
    void boxesOfOneAddressBecomeOneRowWithTheirContentsSummed() {
        PackageGroupFold<String> fold = new PackageGroupFold<>(Comparator.naturalOrder());
        // Two different boxes for Base North (each its own key) and three identical ones for the Mine.
        fold.add("Base North", "box-a", 1, 1, holds("iron", 64, "coal", 32), false);
        fold.add("Base North", "box-b", 1, 0, holds("iron", 16), false);
        fold.add("Mine", "box-c", 3, 2, holds("torch", 10), false);
        List<PackageGroupFold.Row<String>> rows = fold.rows();

        assertEquals(2, rows.size(), "one row per address, not per box");
        PackageGroupFold.Row<String> north = row(rows, "Base North").orElseThrow();
        assertEquals(2L, north.boxes());
        assertEquals(1L, north.available(), "only what a player may still be promised");
        assertEquals(List.of(new KeyCount<>("iron", 80L), new KeyCount<>("coal", 32L)), north.summary().contents(),
                "summed over the boxes, largest first");
        assertEquals(2, north.summary().kinds());
        PackageGroupFold.Row<String> mine = row(rows, "Mine").orElseThrow();
        assertEquals(3L, mine.boxes());
        assertEquals(List.of(new KeyCount<>("torch", 30L)), mine.summary().contents(),
                "three identical boxes hold three times what one holds");
        assertEquals(mine.boxes(), mine.summary().boxes());
    }

    @Test
    void aSummaryListsTheEightLargestContentsAndCountsTheRest() {
        PackageGroupFold<String> fold = new PackageGroupFold<>(Comparator.naturalOrder());
        List<KeyCount<String>> many = new ArrayList<>();
        for (int i = 0; i < 11; i++)
            many.add(new KeyCount<>("item" + (char) ('a' + i), 100 - i));
        fold.add("Depot", "box", 1, 1, many, false);
        PackageGroupSummary<String> summary = fold.rows().getFirst().summary();

        assertEquals(PackageGroupSummary.MAX_CONTENTS, summary.contents().size());
        assertEquals("itema", summary.contents().getFirst().key(), "the largest first");
        assertEquals(11, summary.kinds());
        assertEquals(3, summary.moreKinds(), "and 3 more");
    }

    @Test
    void equalContentCountsAreOrderedByKeyNotByHashOrder() {
        PackageGroupFold<String> fold = new PackageGroupFold<>(Comparator.naturalOrder());
        fold.add("", "box", 1, 1, holds("zinc", 5, "andesite", 5, "brass", 5), false);
        assertEquals(List.of("andesite", "brass", "zinc"),
                fold.rows().getFirst().summary().contents().stream().map(KeyCount::key).toList());
    }

    @Test
    void boxesWithoutAnAddressAreARowOfTheirOwn() {
        PackageGroupFold<String> fold = new PackageGroupFold<>(Comparator.naturalOrder());
        fold.add("", "blank", 2, 2, holds("dirt", 1), false);
        fold.add(null, "blank-too", 1, 1, holds("dirt", 1), false);
        fold.add("Farm", "addressed", 1, 1, holds("wheat", 1), false);
        List<PackageGroupFold.Row<String>> rows = fold.rows();
        assertEquals(2, rows.size());
        assertEquals(3L, row(rows, "").orElseThrow().boxes(), "a null address is the blank one");
    }

    @Test
    void theNetworkOrderFlagIsSetByAnyOneBox() {
        PackageGroupFold<String> fold = new PackageGroupFold<>(Comparator.naturalOrder());
        fold.add("Base", "plain", 1, 1, holds("iron", 1), false);
        assertFalse(fold.rows().getFirst().summary().networkOrder());
        fold.add("Base", "ordered", 1, 1, holds("iron", 1), true);
        assertTrue(fold.rows().getFirst().summary().networkOrder());
    }

    @Test
    void nothingStoredIsNoRowAndAvailableNeverExceedsTheBoxes() {
        PackageGroupFold<String> fold = new PackageGroupFold<>(Comparator.naturalOrder());
        fold.add("Gone", "box", 0, 0, holds("iron", 1), false);
        assertTrue(fold.isEmpty(), "a key with no box stored adds no row");
        fold.add("Base", "box", 2, 5, holds(), false);
        PackageGroupFold.Row<String> base = fold.rows().getFirst();
        assertEquals(2L, base.available());
        assertTrue(base.summary().contents().isEmpty(), "an empty box is a box with nothing inside");
        assertEquals(0, base.summary().kinds());
    }

    @Test
    void hugeCountsSaturateInsteadOfOverflowing() {
        PackageGroupFold<String> fold = new PackageGroupFold<>(Comparator.naturalOrder());
        fold.add("Big", "box", Long.MAX_VALUE / 2, 0, holds("iron", 64), false);
        fold.add("Big", "box2", 1, 0, holds("iron", 64), false);
        assertEquals(Long.MAX_VALUE, fold.rows().getFirst().summary().contents().getFirst().count());
    }
}
