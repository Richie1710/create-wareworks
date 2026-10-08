package dev.wareworks.content.crane.head;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.crane.head.ExchangeDecision.Action;
import dev.wareworks.content.crane.head.ExchangeDecision.Outcome;

/**
 * {@link ExchangeDecision}: the table a handling head follows at a container exchange (M30, issue #21).
 * <p>
 * This is the conservation argument of the exchange, and it is the only part of that operation a unit test can reach —
 * everything around it needs an item capability, a fluid capability and a block entity, i.e. a world. So the cases
 * that matter most here are the ones no correct fluid bay can produce: a location that promises one swap and performs
 * another, one that claims more containers than the head holds, and one that reports a swap of a key for itself.
 */
class ExchangeDecisionTest {
    private static final int ONE = 1;
    private static final int THREE = 3;
    private static final int SIXTEEN = 16;

    // --- gate 1: may the head even ask --------------------------------------------------------------------------

    @Test
    void aHeadMayAskWhileItHoldsWhatItOffers() {
        assertTrue(ExchangeDecision.mayAsk(ONE, ONE, true));
        assertTrue(ExchangeDecision.mayAsk(ONE, SIXTEEN, true), "holding more than it offers is fine");
        assertTrue(ExchangeDecision.mayAsk(SIXTEEN, SIXTEEN, true));
    }

    @Test
    void aHeadNeverOffersContainersItDoesNotHold() {
        assertFalse(ExchangeDecision.mayAsk(THREE, 2, true),
                "offering containers the head does not hold is how a bay would take fluid out of nothing");
        assertFalse(ExchangeDecision.mayAsk(ONE, 0, true));
    }

    @Test
    void anExchangeOfAtMostZeroContainersIsNotAnExchange() {
        assertFalse(ExchangeDecision.mayAsk(0, SIXTEEN, true));
        assertFalse(ExchangeDecision.mayAsk(-1, SIXTEEN, true));
    }

    @Test
    void aKeyIsNeverExchangedForItself() {
        assertFalse(ExchangeDecision.mayAsk(ONE, ONE, false),
                "the head would remove and re-add the same items while the bay kept the fluid: fluid from nothing");
    }

    // --- gate 2: all or nothing, while nothing has moved -------------------------------------------------------

    @Test
    void aPlanForExactlyWhatWasAskedAgrees() {
        assertTrue(ExchangeDecision.planAgrees(THREE, THREE, true));
    }

    @Test
    void aPlanForFewerContainersIsRefusedRatherThanPartiallyApplied() {
        assertFalse(ExchangeDecision.planAgrees(THREE, 2, true),
                "a partial exchange would leave both a filled and an empty container in one head");
        assertFalse(ExchangeDecision.planAgrees(THREE, 0, true));
    }

    @Test
    void aPlanForMoreContainersThanAskedIsRefusedToo() {
        assertFalse(ExchangeDecision.planAgrees(ONE, 2, true));
    }

    @Test
    void aPlanNamingAnotherResultingItemIsRefused() {
        assertFalse(ExchangeDecision.planAgrees(THREE, THREE, false),
                "the caller has already decided what it expects back; a different item is a different operation");
    }

    @Test
    void nothingIsEverPlannedForZeroContainers() {
        assertFalse(ExchangeDecision.planAgrees(0, 0, true));
    }

    // --- gate 3: after the fluid has moved ---------------------------------------------------------------------

    @Test
    void theExchangeThatHappenedAsPlannedIsCompleted() {
        assertEquals(new Outcome(Action.EXCHANGE, THREE), ExchangeDecision.judge(THREE, THREE, THREE, true));
        assertEquals(new Outcome(Action.EXCHANGE, ONE), ExchangeDecision.judge(ONE, SIXTEEN, ONE, true),
                "a head holding more of the key than it gave up is still an ordinary exchange");
    }

    @Test
    void aLocationThatReportsNoContainerIsTheOnlyRefusalLeft() {
        assertEquals(Outcome.REFUSED, ExchangeDecision.judge(THREE, THREE, 0, true));
        assertEquals(Outcome.REFUSED, ExchangeDecision.judge(THREE, THREE, -5, true),
                "a negative count is nonsense, and nonsense is nothing");
        assertEquals(0, Outcome.REFUSED.containers());
    }

    @Test
    void anEmptyHeadCanGiveUpNothingWhateverTheLocationClaims() {
        assertEquals(Outcome.REFUSED, ExchangeDecision.judge(THREE, 0, THREE, true));
    }

    @Test
    void fewerContainersThanPlannedAreSalvagedRatherThanHeld() {
        assertEquals(new Outcome(Action.SALVAGE, 2), ExchangeDecision.judge(THREE, THREE, 2, true),
                "the two containers are gone, so they leave the head; what came back is dropped, not held");
    }

    @Test
    void anotherResultingItemThanPlannedIsSalvaged() {
        assertEquals(new Outcome(Action.SALVAGE, THREE), ExchangeDecision.judge(THREE, THREE, THREE, false),
                "the fluid has already moved, so the containers may not stay on the head");
    }

    @Test
    void moreContainersThanTheHeadHoldsAreClampedAndSalvaged() {
        assertEquals(new Outcome(Action.SALVAGE, THREE), ExchangeDecision.judge(THREE, THREE, SIXTEEN, true),
                "the head gives up all it has; the surplus is fluid the location took for containers that never "
                        + "existed, and only the joint census can report that");
    }

    // --- the outcome type itself -------------------------------------------------------------------------------

    @Test
    void exactlyARefusalMovesNoContainer() {
        assertThrows(IllegalArgumentException.class, () -> new Outcome(Action.REFUSE, ONE));
        assertThrows(IllegalArgumentException.class, () -> new Outcome(Action.EXCHANGE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Outcome(Action.SALVAGE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Outcome(Action.EXCHANGE, -1));
    }

    @Test
    void everyJudgementIsOneOfTheThreeActions() {
        for (int reported = -1; reported <= 4; reported++) {
            for (int held = 0; held <= 4; held++) {
                for (boolean expected : new boolean[] { false, true }) {
                    Outcome outcome = ExchangeDecision.judge(THREE, held, reported, expected);
                    assertTrue(outcome.containers() <= held, "never more than the head holds");
                    assertTrue(outcome.containers() <= Math.max(0, reported), "never more than was reported");
                    assertEquals(outcome.action() == Action.EXCHANGE,
                            reported == THREE && held >= THREE && expected,
                            "only the exact, expected swap completes: reported " + reported + ", held " + held
                                    + ", expected " + expected);
                }
            }
        }
    }
}
