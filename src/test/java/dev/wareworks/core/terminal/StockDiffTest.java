package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRuleStatus;

/** What a terminal actually sends to an open screen after the first full list. */
class StockDiffTest {
    private static StockCount<String> count(String key, long total, long available) {
        return new StockCount<>(key, total, available);
    }

    /** An item the aisle holds none of but can make, with what it could make right now (M11, ADR-024). */
    private static StockCount<String> producible(String key, long producibleAmount) {
        return new StockCount<>(key, 0L, 0L, true, producibleAmount);
    }

    @Test
    void aProducibleItemAtZeroStockIsAnEntry() {
        StockDiff<String> diff = new StockDiff<>();
        StockCount<String> offer = producible("plank", 64);
        assertEquals(List.of(offer), diff.commit(List.of(offer)));
        assertEquals(1, diff.size(), "an offer at zero stock keeps its row");
        assertTrue(!offer.isGone(), "a producible item at zero stock has not left the index");
        assertEquals(64L, offer.orderable(), "and is what a click may ask for");
    }

    @Test
    void aChangedProducibleAmountAloneIsSent() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(producible("plank", 64)));
        // Ingredients were used elsewhere: the offer is still there, but smaller, and the screen has to be told.
        assertEquals(List.of(producible("plank", 32)), diff.commit(List.of(producible("plank", 32))));
        assertTrue(diff.commit(List.of(producible("plank", 32))).isEmpty(), "and only once");
    }

    @Test
    void anOfferWhosePatternDisappearedIsGone() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(producible("plank", 64)));
        assertEquals(List.of(StockCount.gone("plank")), diff.commit(List.of()));
        assertEquals(0, diff.size());
    }

    @Test
    void theFirstCommitReportsEverything() {
        StockDiff<String> diff = new StockDiff<>();
        List<StockCount<String>> current = List.of(count("iron", 64, 64), count("gold", 10, 4));
        assertEquals(current, diff.commit(current));
        assertEquals(2, diff.size());
    }

    @Test
    void anUnchangedWarehouseSendsNothing() {
        StockDiff<String> diff = new StockDiff<>();
        List<StockCount<String>> current = List.of(count("iron", 64, 64));
        diff.commit(current);
        assertTrue(diff.changes(current).isEmpty());
        assertTrue(diff.commit(current).isEmpty());
    }

    @Test
    void onlyChangedEntriesAreReported() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(count("iron", 64, 64), count("gold", 10, 10)));
        List<StockCount<String>> changes = diff.commit(List.of(count("iron", 64, 32), count("gold", 10, 10)));
        assertEquals(List.of(count("iron", 64, 32)), changes, "a changed availability alone is a change");
    }

    @Test
    void newAndRemovedEntriesAreReported() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(count("iron", 64, 64), count("gold", 10, 10)));
        List<StockCount<String>> changes = diff.commit(List.of(count("iron", 64, 64), count("diamond", 3, 3)));
        assertEquals(2, changes.size());
        assertTrue(changes.contains(count("diamond", 3, 3)));
        assertTrue(changes.contains(StockCount.gone("gold")), "a vanished item type is sent with total 0");
        assertTrue(StockCount.gone("gold").isGone());
    }

    @Test
    void anEntryThatOnlyLeftTheReportedWindowIsNotReportedAsGone() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(count("iron", 64, 64), count("gold", 10, 10)));

        // "gold" is still in stock, it only did not fit into the cut-off list this time (maxTerminalStockEntries).
        List<StockCount<String>> changes = diff.commit(List.of(count("iron", 64, 32)), "gold"::equals);
        assertEquals(List.of(count("iron", 64, 32)), changes, "only the entry that really changed is sent");
        assertEquals(2, diff.size(), "the screen keeps the row it already has for the cut-off item");
        assertTrue(diff.commit(List.of(count("iron", 64, 32)), "gold"::equals).isEmpty(), "and is not sent again");

        // Once it really leaves the index it is reported as gone after all.
        assertEquals(List.of(StockCount.gone("gold")), diff.commit(List.of(count("iron", 64, 32))));
        assertEquals(1, diff.size());
    }

    @Test
    void aCutOffEntryComesBackWithItsCurrentAmounts() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(count("iron", 64, 64), count("gold", 10, 10)));
        diff.commit(List.of(count("iron", 64, 64)), "gold"::equals);
        assertEquals(List.of(count("gold", 7, 7)), diff.commit(List.of(count("iron", 64, 64), count("gold", 7, 7))),
                "the amounts changed while it was outside the window, so they are sent");
    }

    @Test
    void changesWithAWindowDoesNotChangeTheRememberedState() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(count("iron", 64, 64), count("gold", 10, 10)));
        assertTrue(diff.changes(List.of(count("iron", 64, 64)), "gold"::equals).isEmpty());
        assertEquals(2, diff.size());
        assertEquals(List.of(StockCount.gone("gold")), diff.changes(List.of(count("iron", 64, 64))),
                "without the window predicate the same list means gold left the index");
    }

    @Test
    void changesDoesNotChangeTheRememberedState() {
        StockDiff<String> diff = new StockDiff<>();
        List<StockCount<String>> first = List.of(count("iron", 64, 64));
        diff.commit(first);
        List<StockCount<String>> next = List.of(count("iron", 32, 32));
        assertEquals(next, diff.changes(next));
        assertEquals(next, diff.changes(next), "asking twice gives the same answer");
        assertEquals(next, diff.commit(next));
        assertTrue(diff.changes(next).isEmpty());
    }

    @Test
    void resetMakesTheNextCommitReportEverythingAgain() {
        StockDiff<String> diff = new StockDiff<>();
        List<StockCount<String>> current = List.of(count("iron", 64, 64));
        diff.commit(current);
        diff.reset();
        assertTrue(diff.isEmpty());
        assertEquals(current, diff.commit(current));
    }

    @Test
    void countsAreClampedToSaneValues() {
        StockCount<String> clamped = count("iron", -5, 20);
        assertEquals(0L, clamped.total());
        assertEquals(0L, clamped.available());
        StockCount<String> promised = count("iron", 10, 40);
        assertEquals(10L, promised.available(), "available is never above the total");
        assertEquals(0L, promised.reserved());
    }

    /** A rule keeps a row alive at zero stock, exactly as a production pattern does (M15, issue #3). */
    @Test
    void aRuledItemAtZeroStockIsAnEntry() {
        StockDiff<String> diff = new StockDiff<>();
        StockCount<String> ruled = new StockCount<>("iron", 0L, 0L, false, 0L,
                Optional.of(StockRuleStatus.BELOW_MINIMUM), 0L);
        assertEquals(List.of(ruled), diff.commit(List.of(ruled)));
        assertEquals(1, diff.size(), "the warehouse is calling for it: the row stays");
        // The same key without a rule is the marker that deletes the row.
        assertEquals(List.of(StockCount.gone("iron")), diff.commit(List.of(count("iron", 0, 0))));
        assertEquals(0, diff.size());
    }

    /** Only the reserve moved: the amounts are the same, and the screen still has to be told (M15). */
    @Test
    void aChangedReserveAloneIsSent() {
        StockDiff<String> diff = new StockDiff<>();
        StockCount<String> before = new StockCount<>("iron", 64L, 64L, false, 0L,
                Optional.of(StockRuleStatus.SATISFIED), 10L);
        StockCount<String> after = new StockCount<>("iron", 64L, 64L, false, 0L,
                Optional.of(StockRuleStatus.AT_RESERVE), 32L);
        diff.commit(List.of(before));
        assertEquals(List.of(after), diff.commit(List.of(after)));
        assertTrue(diff.commit(List.of(after)).isEmpty(), "and only once");
    }

    /** A rule a player deleted leaves the row alive while the item is in stock, and only drops the badge. */
    @Test
    void aRemovedRuleIsSentAsAPlainEntry() {
        StockDiff<String> diff = new StockDiff<>();
        diff.commit(List.of(new StockCount<>("iron", 64L, 64L, false, 0L, Optional.of(StockRuleStatus.SATISFIED), 8L)));
        assertEquals(List.of(count("iron", 64, 64)), diff.commit(List.of(count("iron", 64, 64))));
        assertEquals(1, diff.size(), "the item is still in stock, so the row is not gone");
    }
}
