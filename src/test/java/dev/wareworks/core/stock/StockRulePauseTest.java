package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRulePause.Cause;

/**
 * The safety stop's own value ({@code docs/warehouse-system.md} §3.5.4, ADR-027, widened by ADR-032): which kind of order
 * armed it, and whether it may ever be forgotten without a player.
 */
class StockRulePauseTest {
    @Test
    void aCauseSaysWhichKindOfOrderArmedTheStop() {
        assertEquals(Cause.TIMED_OUT, Cause.of(true, false), "an automatic order that timed out");
        assertEquals(Cause.CANCELLED, Cause.of(true, true), "an automatic order that was given up");
        assertEquals(Cause.ORDER_TIMED_OUT, Cause.of(false, false), "an order somebody asked for, timed out");
        assertEquals(Cause.ORDER_CANCELLED, Cause.of(false, true), "an order somebody asked for, given up");
    }

    /**
     * Only a rule-born pause may be forgotten with the rule it belonged to. One armed by anybody else has no rule at all
     * — an intermediate of a chain is normally governed by none — so forgetting it would let the next click rebuild the
     * same order into the same broken machine (M20, ADR-032).
     */
    @Test
    void onlyAnAutomaticOrdersPauseBelongsToARule() {
        assertTrue(Cause.TIMED_OUT.isRuleBorn());
        assertTrue(Cause.CANCELLED.isRuleBorn());
        assertFalse(Cause.ORDER_TIMED_OUT.isRuleBorn());
        assertFalse(Cause.ORDER_CANCELLED.isRuleBorn());
        assertTrue(StockRulePause.timedOut(3L).isRuleBorn());
        assertFalse(new StockRulePause(Cause.ORDER_CANCELLED, 3L).isRuleBorn());
    }

    /** Every cause has its own text, and the save name round-trips — the two things appending a value must not break. */
    @Test
    void everyCauseHasItsOwnTextAndSurvivesASave() {
        Set<String> keys = new HashSet<>();
        for (Cause cause : Cause.values()) {
            assertTrue(cause.langKey().startsWith("gui.keeper.paused."), cause + " has a keeper text");
            assertTrue(keys.add(cause.langKey()), cause + " has a text of its own");
            assertEquals(Optional.of(cause), Cause.byName(cause.name()), "the save name reads back");
        }
        assertEquals(4, keys.size(), "four kinds of loss, no more");
        assertEquals(Optional.empty(), Cause.byName("PLAN_TIMED_OUT"), "an unknown name reads as nothing");
        assertEquals(Optional.empty(), Cause.byName(null));
    }

    @Test
    void whatWasNeverFetchedWasNeverSpent() {
        assertEquals(0L, new StockRulePause(Cause.ORDER_TIMED_OUT, -5L).unrecovered());
        assertEquals(7L, StockRulePause.cancelled(7L).unrecovered());
        assertEquals(Cause.CANCELLED, StockRulePause.cancelled(7L).cause());
    }
}
