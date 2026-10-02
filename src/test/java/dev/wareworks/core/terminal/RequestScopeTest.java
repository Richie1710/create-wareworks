package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRule;

/**
 * The one thing M23 adds to the M15 question: a <b>list order</b> asks before it has items made, because it starts
 * production while nobody is at the terminal (issue #19, "producible items ask too"). A click does not, because the
 * player is standing there and the result is what they asked for.
 * <p>
 * Everything here is also a <b>regression pin</b>. The scope-less {@link RequestConfirmation#required()} and
 * {@link RequestAcknowledgement#covers(RequestConfirmation)} must keep meaning exactly what they meant before M23, and
 * the answer a click sends must stay the same four numbers — a redstone port, a GameTest and the terminal screen all
 * go through them.
 */
class RequestScopeTest {
    private static final String PLANK = "oak_plank";
    private static final String LOG = "oak_log";

    /** A request that crosses no boundary a stock keeper set, but has items made for it. */
    private static RequestConfirmation<String> producing(long made) {
        return new RequestConfirmation<>(PLANK, made, 0L, 0L, 0L, made, StockRule.UNSET, List.of());
    }

    /** A request that takes items out of a reserve, and makes nothing. */
    private static RequestConfirmation<String> fromReserve(long items) {
        return new RequestConfirmation<>(PLANK, 64L, items, 64L, 0L, 0L, StockRule.UNSET, List.of());
    }

    // --- what each scope asks about ----------------------------------------------------------------------------------

    @Test
    void productionIsWorthAQuestionForAListAndNotForAClick() {
        RequestConfirmation<String> made = producing(256L);
        assertFalse(made.required(RequestScope.CLICK), "a click starts production under the player's own eyes");
        assertTrue(made.required(RequestScope.LIST), "a list order starts it while nobody is there");
        assertEquals(256L, made.made());
    }

    @Test
    void aWarehouseWithoutStockKeepersAndWithoutProductionNeverAsksEitherWay() {
        RequestConfirmation<String> plain = RequestConfirmation.none(PLANK, 64L);
        assertFalse(plain.required(RequestScope.CLICK));
        assertFalse(plain.required(RequestScope.LIST), "an aisle with no patterns sees no extra dialog");
        assertTrue(RequestAcknowledgement.NONE.covers(plain, RequestScope.LIST));
    }

    @Test
    void everythingTheClickAsksAboutAListAsksAboutToo() {
        for (RequestConfirmation<String> question : List.of(fromReserve(4L),
                new RequestConfirmation<>(PLANK, 64L, 0L, 0L, 8L, 256L, 128L, List.of()),
                new RequestConfirmation<>(PLANK, 64L, 0L, 0L, 0L, 0L, StockRule.UNSET,
                        List.of(new RequestConfirmation.ReservedIngredient<>(LOG, 6L, 32L))))) {
            assertTrue(question.required(RequestScope.CLICK));
            assertTrue(question.required(RequestScope.LIST));
        }
    }

    // --- the answer --------------------------------------------------------------------------------------------------

    @Test
    void anAnswerForAListHasToNameWhatWouldBeMade() {
        RequestConfirmation<String> made = producing(256L);
        assertFalse(RequestAcknowledgement.NONE.covers(made, RequestScope.LIST));
        assertFalse(new RequestAcknowledgement(false, 999L, 999L, 999L, 255L).covers(made, RequestScope.LIST),
                "every other number is accepted and it still is not covered");
        assertTrue(new RequestAcknowledgement(false, 0L, 0L, 0L, 256L).covers(made, RequestScope.LIST));
        assertTrue(new RequestAcknowledgement(false, 0L, 0L, 0L, 300L).covers(made, RequestScope.LIST),
                "a budget with room to spare covers a cheaper portion");
        assertTrue(RequestAcknowledgement.ANY.covers(made, RequestScope.LIST), "the ctrl-click still covers it all");
    }

    @Test
    void theAnswerToAListQuestionIsTheQuestionsOwnNumbers() {
        RequestConfirmation<String> question = new RequestConfirmation<>(PLANK, 64L, 4L, 64L, 8L, 256L, 128L,
                List.of(new RequestConfirmation.ReservedIngredient<>(LOG, 6L, 32L)));
        RequestAcknowledgement answer = question.acknowledgement(RequestScope.LIST);
        assertEquals(new RequestAcknowledgement(false, 4L, 8L, 6L, 256L), answer);
        assertTrue(answer.covers(question, RequestScope.LIST));
        assertTrue(answer.covers(question, RequestScope.CLICK));
        assertTrue(answer.given());
    }

    // --- the regression pins: a click means what it always meant ----------------------------------------------------

    @Test
    void theScopelessFormsAreTheClick() {
        for (RequestConfirmation<String> question : List.of(RequestConfirmation.none(PLANK, 64L), fromReserve(4L),
                producing(256L), new RequestConfirmation<>(PLANK, 64L, 0L, 0L, 8L, 256L, 128L, List.of()))) {
            assertEquals(question.required(RequestScope.CLICK), question.required(),
                    "required() is required(CLICK), byte for byte");
            for (RequestAcknowledgement answer : List.of(RequestAcknowledgement.NONE, RequestAcknowledgement.ANY,
                    new RequestAcknowledgement(false, 4L, 8L, 6L), question.acknowledgement()))
                assertEquals(answer.covers(question, RequestScope.CLICK), answer.covers(question),
                        "covers(question) is covers(question, CLICK)");
        }
    }

    @Test
    void theAnswerToAClickIsStillTheSameFourNumbers() {
        RequestConfirmation<String> question = new RequestConfirmation<>(PLANK, 64L, 4L, 64L, 8L, 256L, 128L,
                List.of(new RequestConfirmation.ReservedIngredient<>(LOG, 6L, 32L)));
        assertEquals(new RequestAcknowledgement(false, 4L, 8L, 6L), question.acknowledgement());
        assertEquals(0L, question.acknowledgement().produced(), "a click accepts nothing of what only a list is asked");
        assertEquals(question.acknowledgement(RequestScope.CLICK), question.acknowledgement());
        assertEquals(new RequestAcknowledgement(false, 1L, 2L, 3L, 0L), new RequestAcknowledgement(false, 1L, 2L, 3L));
        assertFalse(new RequestAcknowledgement(false, 0L, 0L, 0L).given());
        assertEquals(RequestAcknowledgement.NONE, new RequestAcknowledgement(false, 0L, 0L, 0L));
    }

    @Test
    void aProducedNumberAloneIsStillAnAnswer() {
        RequestAcknowledgement budget = new RequestAcknowledgement(false, 0L, 0L, 0L, 40L);
        assertTrue(budget.given(), "a Fetch that agreed to production said something");
        assertTrue(budget.covers(producing(40L), RequestScope.LIST));
        assertTrue(budget.covers(producing(40L)), "and for a click it was never a cost at all");
    }

    // --- a budget: topped up, spent down ----------------------------------------------------------------------------

    @Test
    void addingTwoAnswersAddsEveryNumber() {
        RequestAcknowledgement first = new RequestAcknowledgement(false, 1L, 2L, 3L, 4L);
        assertEquals(new RequestAcknowledgement(false, 2L, 4L, 6L, 8L), first.plus(first));
        assertEquals(first, first.plus(RequestAcknowledgement.NONE));
        assertSame(first, first.plus(null));
        assertEquals(RequestAcknowledgement.ANY, first.plus(RequestAcknowledgement.ANY),
                "whatever it costs cannot be narrowed by a number");
        assertEquals(RequestAcknowledgement.ANY, RequestAcknowledgement.ANY.plus(first));
    }

    @Test
    void spendingTakesTheRealCostOutAndNeverGoesBelowNothing() {
        RequestAcknowledgement budget = new RequestAcknowledgement(false, 10L, 10L, 10L, 100L);
        RequestConfirmation<String> cost = new RequestConfirmation<>(PLANK, 64L, 4L, 64L, 8L, 60L, 128L,
                List.of(new RequestConfirmation.ReservedIngredient<>(LOG, 6L, 32L)));
        assertEquals(new RequestAcknowledgement(false, 6L, 2L, 4L, 40L), budget.minus(cost));
        assertEquals(new RequestAcknowledgement(false, 2L, 0L, 0L, 0L), budget.minus(cost).minus(cost),
                "what is spent out stays out, and no number ever goes below nothing");
        assertFalse(budget.minus(cost).covers(cost, RequestScope.LIST));
        assertSame(budget, budget.minus(null));
        assertSame(RequestAcknowledgement.ANY, RequestAcknowledgement.ANY.minus(cost), "a ctrl-click spends nothing");
    }

    @Test
    void aNullQuestionIsCoveredInEveryScope() {
        assertTrue(RequestAcknowledgement.NONE.covers(null, RequestScope.LIST));
        assertTrue(RequestAcknowledgement.NONE.covers(null, RequestScope.CLICK));
    }
}
