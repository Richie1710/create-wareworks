package dev.wareworks.core.requester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A requester's share of a merged request (issue #45): first in, first served, so a player's earlier click is never
 * taken for the requester's own items.
 */
class OrderShareTest {
    @Test
    void aShareAloneIsCreditedAsItArrives() {
        assertEquals(30, OrderShare.ownDelivered(64, 30, 0, 64));
        assertEquals(34, OrderShare.outstanding(64, 30, 0, 64));
        assertFalse(OrderShare.done(64, 30, 0, 64));
        assertTrue(OrderShare.done(64, 64, 0, 64));
    }

    @Test
    void aShareMergedBehindAPlayersClickWaitsForIt() {
        // A player clicked 10, then the requester's 64 merged in: requested 74, ten ahead.
        assertEquals(0, OrderShare.ownDelivered(74, 10, 10, 64), "the player's ten arrived, not the requester's");
        assertFalse(OrderShare.done(74, 10, 10, 64));
        assertEquals(30, OrderShare.ownDelivered(74, 40, 10, 64));
        assertTrue(OrderShare.done(74, 74, 10, 64));
    }

    @Test
    void twoRequestersOnOneRequestAreCreditedApart() {
        // Requester A's 64 first, requester B's 16 merged behind it.
        assertTrue(OrderShare.done(80, 64, 0, 64));
        assertEquals(0, OrderShare.ownDelivered(80, 64, 64, 16));
        assertEquals(16, OrderShare.outstanding(80, 64, 64, 16));
        assertEquals(8, OrderShare.ownDelivered(80, 72, 64, 16));
    }

    @Test
    void somebodyAheadWhoGaveTheirPartBackMovesTheShareForward() {
        // The player's ten were given back: the request asks for 64 now, all of it the requester's.
        assertEquals(0, OrderShare.ahead(64, 10, 64));
        assertEquals(5, OrderShare.ownDelivered(64, 5, 10, 64));
        assertEquals(4, OrderShare.ahead(68, 10, 64), "six of the ten given back");
    }

    @Test
    void somebodyBehindWhoGaveTheirPartBackChangesNothing() {
        // 10 ahead, the requester's 64, then 20 more behind it; the 20 are given back again.
        assertEquals(10, OrderShare.ahead(94, 10, 64));
        assertEquals(10, OrderShare.ahead(74, 10, 64));
        assertEquals(0, OrderShare.ownDelivered(74, 10, 10, 64));
    }

    @Test
    void onlyWhatIsProvablyTheSharesOwnIsGivenBack() {
        // Nothing ahead, or nothing of the request delivered: the whole outstanding part.
        assertEquals(34, OrderShare.returnable(64, 30, 0, 0, 64));
        assertEquals(64, OrderShare.returnable(74, 0, 10, 0, 64));
        // The player's ten ahead were delivered before the share was granted: they stay ahead for good.
        assertEquals(44, OrderShare.returnable(74, 30, 10, 10, 64));
        // The same ten arrived only after the grant: they might have been given back and the 30 all the share's.
        assertEquals(34, OrderShare.returnable(74, 30, 10, 0, 64));
        assertEquals(44, OrderShare.outstanding(74, 30, 10, 64), "while the credit still counts them ahead");
    }

    @Test
    void aShortfallAheadMaskedByAClickBehindGivesBackNothingOfThatClick() {
        // 20 ahead (10 from production), the share's 64, a click of 20 behind: 104. Production falls 10 short ahead
        // (94), and 74 arrive: the ten ahead and the whole share. 94 >= 20 + 64, so the credit cannot see the
        // shortfall and still counts 10 outstanding; giving those back would take them off the click behind.
        assertEquals(20, OrderShare.ahead(94, 20, 64));
        assertEquals(10, OrderShare.outstanding(94, 74, 20, 64));
        assertEquals(0, OrderShare.returnable(94, 74, 20, 0, 64));
    }

    @Test
    void theOutstandingPartIsTheShareMinusWhatArrived() {
        assertEquals(64, OrderShare.outstanding(74, 0, 10, 64));
        assertEquals(0, OrderShare.outstanding(74, 200, 10, 64), "never below nothing");
        assertEquals(0, OrderShare.outstanding(0, 0, 0, 0), "an empty share has nothing outstanding");
    }

    @Test
    void beingMadeGoesDownAsMadeItemsAreDelivered() {
        // x16 ordered, 5 from stock and 11 made in three runs of four; the five in stock arrive first.
        assertEquals(11, OrderShare.beingMade(16, 0, 11, 11));
        assertEquals(11, OrderShare.beingMade(16, 5, 11, 11), "the stock part arrived, nothing made yet");
        assertEquals(7, OrderShare.beingMade(16, 5, 11, 7), "one run produced, still in stock");
        assertEquals(7, OrderShare.beingMade(16, 9, 11, 7), "and delivered");
        assertEquals(3, OrderShare.beingMade(16, 13, 11, 3), "two runs produced and delivered");
        assertEquals(3, OrderShare.beingMade(16, 13, 11, Long.MAX_VALUE), "without the root's promise as well");
        assertEquals(0, OrderShare.beingMade(16, 16, 11, 0), "all of it arrived");
        assertEquals(0, OrderShare.beingMade(16, 0, 0, Long.MAX_VALUE), "nothing made, nothing shown");
        for (int delivered = 0; delivered <= 16; delivered++) {
            for (long unmade : new long[] { 0L, 3L, 11L, Long.MAX_VALUE }) {
                int made = OrderShare.beingMade(16, delivered, 11, unmade);
                assertTrue(made >= 0 && delivered + made <= 16,
                        "ordered 16 >= being made " + made + " + delivered " + delivered);
            }
        }
        assertEquals(0, OrderShare.beingMade(16, 40, 11, 11), "a delivery count above the share is clamped");
    }
}
