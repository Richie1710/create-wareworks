package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRuleStatus;

/**
 * What a warehouse terminal reports for one item, with the stock rule part of it (M15, issue #3).
 * <p>
 * The one thing these tests pin above all others: {@code ruleReserved} is a <b>part of</b> {@code available}, not a
 * deduction from it — a reserve holds items back from the warehouse's own automation and not from the player standing
 * at the terminal — so the parts of a row still add up to what is in stock.
 */
class StockCountTest {
    private static StockCount<String> ruled(long total, long available, StockRuleStatus status, long reserved) {
        return new StockCount<>("iron", total, available, false, 0L, Optional.of(status), reserved);
    }

    @Test
    void anUnruledEntryAnswersExactlyAsBeforeM15() {
        StockCount<String> count = new StockCount<>("iron", 64L, 40L);
        assertFalse(count.ruled());
        assertEquals(Optional.empty(), count.rule());
        assertEquals(0L, count.ruleReserved());
        assertEquals(40L, count.availableToAutomation(), "nothing is held back without a rule");
        assertEquals(0L, count.fromReserve(40L));
        assertEquals(24L, count.reserved(), "promised to other requests");
    }

    @Test
    void theReserveIsPartOfWhatIsAvailableToThePlayer() {
        StockCount<String> count = ruled(64L, 40L, StockRuleStatus.SATISFIED, 10L);
        assertEquals(40L, count.available(), "a player may still claim all of it");
        assertEquals(10L, count.ruleReserved());
        assertEquals(30L, count.availableToAutomation(), "a redstone request stops here");
        assertEquals(24L, count.reserved(), "total = available + promised, unchanged by the reserve");
        assertEquals(count.total(), count.available() + count.reserved(), "the parts still add up");
    }

    @Test
    void aReserveIsNeverLargerThanWhatIsThere() {
        // Everything is promised to other requests: a reserve of 10 holds back nothing that exists.
        StockCount<String> count = ruled(64L, 4L, StockRuleStatus.AT_RESERVE, 10L);
        assertEquals(4L, count.ruleReserved(), "clamped to the available amount");
        assertEquals(0L, count.availableToAutomation());
        assertEquals(4L, count.fromReserve(4L), "a player takes all four out of the reserve");
    }

    @Test
    void fromReserveMeasuresHowFarAClickGoesBelowIt() {
        StockCount<String> count = ruled(64L, 40L, StockRuleStatus.SATISFIED, 10L);
        assertEquals(0L, count.fromReserve(30L), "a request that fits above the reserve does not touch it");
        assertEquals(1L, count.fromReserve(31L), "the first item past it");
        assertEquals(10L, count.fromReserve(40L), "everything available takes the whole reserve");
        assertEquals(10L, count.fromReserve(4000L), "and never more than the reserve holds");
        assertEquals(0L, count.fromReserve(-5L), "a negative amount asks for nothing");
    }

    @Test
    void aRuledItemKeepsItsRowAtZeroStock() {
        StockCount<String> count = ruled(0L, 0L, StockRuleStatus.BELOW_MINIMUM, 0L);
        assertFalse(count.isGone(), "a warehouse calling for an item it has none of must be able to say so");
        assertTrue(count.ruled());
        // Without the rule the very same numbers are the marker that deletes the row.
        assertTrue(new StockCount<>("iron", 0L, 0L).isGone());
        assertTrue(StockCount.gone("iron").isGone());
        assertFalse(StockCount.gone("iron").ruled(), "the gone marker never carries a rule");
    }

    @Test
    void anEntryWithoutARuleHoldsBackNothingHoweverItIsBuilt() {
        StockCount<String> count = new StockCount<>("iron", 64L, 40L, false, 0L, Optional.empty(), 32L);
        assertEquals(0L, count.ruleReserved(), "a reserve without a rule is not a reserve");
        assertEquals(40L, count.availableToAutomation());
    }

    @Test
    void negativeNumbersReadAsNothing() {
        StockCount<String> count = ruled(64L, 40L, StockRuleStatus.SATISFIED, -7L);
        assertEquals(0L, count.ruleReserved());
        assertEquals(40L, count.availableToAutomation());
    }

    @Test
    void aProducibleRuledItemKeepsBothHalves() {
        StockCount<String> count = new StockCount<>("plank", 0L, 0L, true, 64L,
                Optional.of(StockRuleStatus.BELOW_MINIMUM), 0L);
        assertTrue(count.producible());
        assertEquals(64L, count.producibleAmount());
        assertEquals(64L, count.orderable(), "what a 'request everything' click may ask for");
        assertTrue(count.ruled());
        assertFalse(count.isGone());
    }
}
