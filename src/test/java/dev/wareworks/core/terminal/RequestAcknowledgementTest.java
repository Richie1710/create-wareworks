package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRule;

/**
 * What a player has agreed to pay for a request, and the one thing it has to get right: an answer authorises the
 * question it was given and <b>not</b> a more expensive one that the warehouse has produced since (M15 part 2,
 * issue #3).
 * <p>
 * This is the whole reason the answer carries numbers instead of a flag. A flag would let a "yes" given for four
 * reserved logs pay for forty, which is exactly the kind of quiet loss the confirmation exists to prevent.
 */
class RequestAcknowledgementTest {
    private static final String PLANK = "plank";
    private static final String LOG = "log";

    private static RequestConfirmation<String> question(long fromReserve, long pastMaximum, long ingredientReserve) {
        return new RequestConfirmation<>(PLANK, 64L, fromReserve, 64L, pastMaximum, 256L, 128L,
                ingredientReserve <= 0L ? List.of()
                        : List.of(new RequestConfirmation.ReservedIngredient<>(LOG, ingredientReserve,
                                ingredientReserve)));
    }

    private static RequestConfirmation<String> nothing() {
        return RequestConfirmation.none(PLANK, 64L);
    }

    // --- the two ends: nothing said, and everything accepted --------------------------------------------------------

    @Test
    void nothingAcceptedCoversOnlyAQuestionlessRequest() {
        assertTrue(RequestAcknowledgement.NONE.covers(nothing()), "an ordinary request needs no round trip");
        assertFalse(RequestAcknowledgement.NONE.covers(question(1L, 0L, 0L)));
        assertFalse(RequestAcknowledgement.NONE.covers(question(0L, 1L, 0L)));
        assertFalse(RequestAcknowledgement.NONE.covers(question(0L, 0L, 1L)));
        assertFalse(RequestAcknowledgement.NONE.given());
    }

    @Test
    void theCtrlClickCoversEverything() {
        assertTrue(RequestAcknowledgement.ANY.covers(nothing()));
        assertTrue(RequestAcknowledgement.ANY.covers(question(999L, 999L, 999L)));
        assertTrue(RequestAcknowledgement.ANY.given());
        assertTrue(RequestAcknowledgement.ANY.any());
    }

    @Test
    void aNullQuestionIsCovered() {
        assertTrue(RequestAcknowledgement.NONE.covers(null), "there is nothing to authorise");
    }

    // --- the numbers ------------------------------------------------------------------------------------------------

    @Test
    void theAnswerToAQuestionCoversThatQuestion() {
        RequestConfirmation<String> asked = question(4L, 8L, 6L);
        assertTrue(asked.acknowledgement().covers(asked));
        assertEquals(new RequestAcknowledgement(false, 4L, 8L, 6L), asked.acknowledgement());
    }

    @Test
    void anAnswerCoversACheaperQuestionToo() {
        RequestAcknowledgement answer = question(4L, 8L, 6L).acknowledgement();
        assertTrue(answer.covers(question(1L, 1L, 1L)));
        assertTrue(answer.covers(nothing()));
    }

    @Test
    void anAnswerNeverCoversAQuestionThatGrewInAnyOneOfItsNumbers() {
        RequestAcknowledgement answer = question(4L, 8L, 6L).acknowledgement();
        assertFalse(answer.covers(question(5L, 8L, 6L)), "more out of the reserve than was accepted");
        assertFalse(answer.covers(question(4L, 9L, 6L)), "more past the maximum");
        assertFalse(answer.covers(question(4L, 8L, 7L)), "more out of an ingredient's reserve");
    }

    /** A reserve somebody raised between the question and the answer: the player is asked again, not charged. */
    @Test
    void aReserveRaisedWhileThePlayerWasThinkingAsksAgain() {
        RequestAcknowledgement answer = question(4L, 0L, 0L).acknowledgement();
        assertFalse(answer.covers(question(40L, 0L, 0L)));
    }

    /** A cost that moved from one kind to another is not covered either: each kind is its own number. */
    @Test
    void acceptingOneKindOfCostDoesNotPayForAnother() {
        RequestAcknowledgement reserveOnly = question(8L, 0L, 0L).acknowledgement();
        assertFalse(reserveOnly.covers(question(0L, 8L, 0L)));
        assertFalse(reserveOnly.covers(question(0L, 0L, 8L)));
    }

    @Test
    void severalReservedIngredientsAreComparedAsOneTotal() {
        RequestConfirmation<String> two = new RequestConfirmation<>(PLANK, 64L, 0L, 0L, 0L, 0L, StockRule.UNSET,
                List.of(new RequestConfirmation.ReservedIngredient<>(LOG, 3L, 3L),
                        new RequestConfirmation.ReservedIngredient<>("nails", 5L, 5L)));
        assertEquals(8L, two.fromIngredientReserve());
        assertTrue(two.acknowledgement().covers(two));
        assertFalse(new RequestAcknowledgement(false, 0L, 0L, 7L).covers(two));
    }

    // --- construction ---------------------------------------------------------------------------------------------

    @Test
    void negativeNumbersAreClampedAndReadAsNothingSaid() {
        RequestAcknowledgement answer = new RequestAcknowledgement(false, -1L, -2L, -3L);
        assertEquals(0L, answer.fromReserve());
        assertEquals(0L, answer.pastMaximum());
        assertEquals(0L, answer.ingredientReserve());
        assertFalse(answer.given());
        assertEquals(RequestAcknowledgement.NONE, answer);
    }

    @Test
    void anyOutweighsTheNumbersItCarries() {
        RequestAcknowledgement answer = new RequestAcknowledgement(true, 1L, 1L, 1L);
        assertTrue(answer.covers(question(999L, 999L, 999L)));
        assertTrue(answer.given());
    }
}
