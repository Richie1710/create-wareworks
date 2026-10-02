package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRule;

/**
 * What pressing Fetch on a clipboard really means (M23, issue #19): "the list wants 2000 cobblestone and the warehouse
 * holds 1300 — fetch what there is?" and "4 entries are not in stock but can be produced — start production?", both
 * measured over the whole list before anything at all is requested.
 * <p>
 * The arithmetic has one job beyond adding up: it must never name an amount no order could reach. That is why the stock
 * and the production of an item are <b>spent</b> as the lines are classified — two entries of 1300 cobblestone cannot
 * both be served — and why {@code wanted == serveable + producing + missing} is checked on every list here.
 */
class ListOrderConfirmationTest {
    private static final String COBBLE = "cobblestone";
    private static final String PLANK = "oak_plank";
    private static final String DIAMOND = "diamond";

    // --- the four kinds of entry -------------------------------------------------------------------------------------

    @Test
    void aListTheRacksCoverAsksNothing() {
        ListOrderConfirmation<String> question = question(List.of(line(COBBLE, 2000), line(PLANK, 64)),
                stock(COBBLE, 2000, PLANK, 64), Map.of());
        assertFalse(question.required(), "a stocked warehouse fetches a list without a dialog");
        assertEquals(2064L, question.serveable());
        assertEquals(0L, question.missing());
        assertEquals(0L, question.producing());
        assertEquals(2, question.entriesServed());
        assertEquals(2, question.entries());
        assertTrue(question.named().isEmpty(), "nothing needs a sentence");
        assertTrue(question.covers(0L, 0L, 0L));
    }

    @Test
    void partialStockIsWhatTheDialogIsAbout() {
        ListOrderConfirmation<String> question = question(List.of(line(COBBLE, 2000)), stock(COBBLE, 1300), Map.of());
        assertTrue(question.required());
        assertEquals(2000L, question.wanted());
        assertEquals(1300L, question.serveable(), "the warehouse can give 1300");
        assertEquals(700L, question.missing());
        assertEquals(1, question.entriesShort());
        assertEquals(0, question.entriesImpossible());
        ListOrderConfirmation.Line<String> named = question.named().get(0);
        assertEquals(COBBLE, named.key());
        assertEquals(2000L, named.wanted());
        assertEquals(1300L, named.serveable());
        assertEquals(700L, named.missing());
        assertTrue(named.isShort());
        assertFalse(named.isImpossible());
    }

    @Test
    void producibleEntriesAreAskedAboutAsWell() {
        ListOrderConfirmation<String> question = question(List.of(line(PLANK, 384)), Map.of(), stock(PLANK, 1000));
        assertTrue(question.required(), "producible items ask too");
        assertEquals(0L, question.serveable());
        assertEquals(384L, question.producing());
        assertEquals(0L, question.missing());
        assertEquals(1, question.entriesProducing());
        assertEquals(0, question.entriesShort());
        assertEquals(384L, question.named().get(0).producing());
        assertFalse(question.named().get(0).isShort());
    }

    @Test
    void anEntryTheWarehouseCanDoNothingForIsNamedAsSuch() {
        ListOrderConfirmation<String> question = question(List.of(line(DIAMOND, 7)), Map.of(), Map.of());
        assertEquals(7L, question.missing());
        assertEquals(1, question.entriesImpossible());
        assertEquals(0, question.entriesShort());
        assertTrue(question.named().get(0).isImpossible());
    }

    @Test
    void anEntryPartlyInStockAndPartlyProducibleIsCoveredInFull() {
        ListOrderConfirmation<String> question = question(List.of(line(PLANK, 100)), stock(PLANK, 40),
                stock(PLANK, 60));
        assertEquals(40L, question.serveable());
        assertEquals(60L, question.producing());
        assertEquals(0L, question.missing());
        assertEquals(1, question.entriesProducing(), "it needs production, so that is what it is counted as");
        assertEquals(0, question.entriesShort());
    }

    @Test
    void anEntryTheProductionOnlyPartlyCoversIsShort() {
        ListOrderConfirmation<String> question = question(List.of(line(PLANK, 100)), stock(PLANK, 10),
                stock(PLANK, 20));
        assertEquals(10L, question.serveable());
        assertEquals(20L, question.producing());
        assertEquals(70L, question.missing());
        assertEquals(1, question.entriesShort());
        assertEquals(0, question.entriesProducing(), "an entry is exactly one of the four kinds");
        assertEquals(1, question.entries());
    }

    // --- shared stock ------------------------------------------------------------------------------------------------

    @Test
    void twoEntriesOfOneItemShareWhatTheWarehouseHas() {
        ListOrderConfirmation<String> question = question(List.of(line(COBBLE, 1300), line(COBBLE, 1300)),
                stock(COBBLE, 1300), Map.of());
        assertEquals(2600L, question.wanted());
        assertEquals(1300L, question.serveable(), "1300 cobblestone cannot serve two entries of 1300");
        assertEquals(1300L, question.missing());
        assertEquals(1, question.entriesServed(), "the first entry is covered");
        assertEquals(1, question.entriesImpossible(), "and the second one gets nothing at all");
        addsUp(question);
    }

    @Test
    void twoEntriesOfOneItemShareWhatTheWarehouseCouldMake() {
        ListOrderConfirmation<String> question = question(List.of(line(PLANK, 64), line(PLANK, 64)), Map.of(),
                stock(PLANK, 96));
        assertEquals(96L, question.producing(), "a pattern's ingredients are not counted twice either");
        assertEquals(32L, question.missing());
        assertEquals(1, question.entriesProducing());
        assertEquals(1, question.entriesShort());
        addsUp(question);
    }

    @Test
    void everyTotalOfAMixedListAddsUp() {
        ListOrderConfirmation<String> question = question(
                List.of(line(COBBLE, 2000), line(PLANK, 384), line(DIAMOND, 7), line(COBBLE, 500)),
                stock(COBBLE, 1300), stock(PLANK, 1000));
        assertEquals(2891L, question.wanted());
        assertEquals(1300L, question.serveable());
        assertEquals(384L, question.producing());
        assertEquals(1207L, question.missing());
        assertEquals(0, question.entriesServed());
        assertEquals(1, question.entriesShort(), "the 2000 cobblestone");
        assertEquals(1, question.entriesProducing(), "the planks");
        assertEquals(2, question.entriesImpossible(), "the diamonds, and the second cobblestone entry");
        assertEquals(4, question.entries());
        addsUp(question);
    }

    // --- what the panel says -----------------------------------------------------------------------------------------

    @Test
    void atMostFiveEntriesAreNamedAndOnlyTheOnesThatNeedASentence() {
        List<ListLine<String>> lines = new ArrayList<>();
        lines.add(line(COBBLE, 64));
        for (int i = 0; i < 8; i++)
            lines.add(line(DIAMOND + i, 10));
        ListOrderConfirmation<String> question = question(lines, stock(COBBLE, 64), Map.of());
        assertEquals(ListOrderConfirmation.MAX_NAMED, question.named().size());
        assertEquals(DIAMOND + "0", question.named().get(0).key(), "the served entry needs no sentence");
        assertEquals(DIAMOND + "4", question.named().get(4).key(), "and the panel counts the rest");
        assertEquals(8, question.entriesImpossible());
        assertEquals(1, question.entriesServed());
    }

    @Test
    void aQuestionBuiltFromAPayloadNeverCarriesNegativeNumbersOrTooManyNames() {
        List<ListOrderConfirmation.Line<String>> named = new ArrayList<>();
        for (int i = 0; i < 9; i++)
            named.add(new ListOrderConfirmation.Line<>(DIAMOND, -1L, -1L, -1L, -1L));
        ListOrderConfirmation<String> question = new ListOrderConfirmation<>(-5L, -5L, -5L, -5L, -1, -1, -1, -1, -1,
                named);
        assertEquals(0L, question.wanted());
        assertEquals(0, question.entries());
        assertEquals(ListOrderConfirmation.MAX_NAMED, question.named().size());
        assertEquals(0L, question.named().get(0).wanted());
        assertFalse(question.required());
    }

    @Test
    void anEmptyListAndAFinishedOneAskNothing() {
        assertFalse(ListOrderConfirmation.<String>none().required());
        assertEquals(0, question(List.of(), stock(COBBLE, 64), Map.of()).entries());
        ListLine<String> done = ListLine.of(0, 0, COBBLE, 64).withPortion(UUID.randomUUID(), 64).withCredited(64);
        assertTrue(done.isComplete());
        ListOrderConfirmation<String> question = question(List.of(done, line(COBBLE, 10)), stock(COBBLE, 10),
                Map.of());
        assertEquals(10L, question.wanted(), "resuming a half-finished order asks only about what is left");
        assertEquals(1, question.entries());
    }

    @Test
    void aWarehouseThatAnswersWithNonsenseIsReadAsHavingNothing() {
        ListOrderConfirmation<String> question = ListOrderConfirmation.of(List.of(line(COBBLE, 10)), 0, key -> -500L,
                key -> Long.MIN_VALUE);
        assertEquals(10L, question.missing());
        assertEquals(0L, question.serveable());
        assertEquals(0L, question.producing());
        addsUp(question);
    }

    // --- the round trip ---------------------------------------------------------------------------------------------

    @Test
    void theAnswerIsMeasuredAgainAndCoversOnlyWhatItWasGiven() {
        ListOrderConfirmation<String> asked = question(List.of(line(COBBLE, 2000), line(PLANK, 384)),
                stock(COBBLE, 1300), stock(PLANK, 1000));
        assertTrue(answers(asked, asked), "the Yes to this question authorises it");

        ListOrderConfirmation<String> richer = question(List.of(line(COBBLE, 2000), line(PLANK, 384)),
                stock(COBBLE, 1900), stock(PLANK, 1000));
        assertTrue(answers(richer, asked),
                "a warehouse that gained stock in between costs less, so the Yes still holds");

        ListOrderConfirmation<String> poorer = question(List.of(line(COBBLE, 2000), line(PLANK, 384)),
                stock(COBBLE, 900), stock(PLANK, 1000));
        assertFalse(answers(poorer, asked), "one that lost stock is asked again");

        ListOrderConfirmation<String> producingMore = question(List.of(line(COBBLE, 2000), line(PLANK, 500)),
                stock(COBBLE, 1300), stock(PLANK, 1000));
        assertFalse(answers(producingMore, asked), "and so is more production than agreed");
    }

    @Test
    void aClipboardLongerThanTheCapIsAlwaysAskedAbout() {
        ListOrderConfirmation<String> question = ListOrderConfirmation.of(List.of(line(COBBLE, 64)), 3,
                key -> 64L, key -> 0L);
        assertTrue(question.truncated(), "three entries stayed on the clipboard");
        assertEquals(3, question.entriesDropped());
        assertTrue(question.required(),
                "a list the cap cut short asks even when everything it took is in stock (M23 review fix)");
        assertEquals(0L, question.missing(), "the untaken entries are reported, not measured");
        assertEquals(1, question.entries(), "and they are no part of the entry counts");
        addsUp(question);
        assertFalse(question.covers(0L, 0L, 0L), "a Fetch that says nothing about them is asked again");
        assertFalse(question.covers(0L, 0L, 2L), "and so is one that accepts fewer than were left behind");
        assertTrue(question.covers(0L, 0L, 3L), "the Yes names them");
    }

    @Test
    void aYesCreatesAConsentBudgetOfExactlyTheProductionItAgreedTo() {
        ListOrderConfirmation<String> asked = question(List.of(line(PLANK, 384)), Map.of(), stock(PLANK, 1000));
        RequestAcknowledgement budget = asked.budget();
        assertEquals(384L, budget.produced());
        assertEquals(0L, budget.fromReserve(), "a reserve cannot be measured over a list and is asked per portion");
        assertEquals(0L, budget.pastMaximum());
        assertFalse(budget.any(), "a list order never signs a blank cheque");

        RequestConfirmation<String> portion = new RequestConfirmation<>(PLANK, 64L, 0L, 0L, 0L, 64L, StockRule.UNSET,
                List.of());
        assertTrue(budget.covers(portion, RequestScope.LIST), "and it pays for the portions that production needs");
        RequestConfirmation<String> wholeList = new RequestConfirmation<>(PLANK, 500L, 0L, 0L, 0L, 500L,
                StockRule.UNSET, List.of());
        assertFalse(budget.covers(wholeList, RequestScope.LIST), "but never for more than was agreed to");
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static ListLine<String> line(String key, int wanted) {
        return ListLine.of(0, 0, key, wanted);
    }

    private static Map<String, Long> stock(String key, long amount) {
        Map<String, Long> levels = new HashMap<>();
        levels.put(key, amount);
        return levels;
    }

    private static Map<String, Long> stock(String first, long firstAmount, String second, long secondAmount) {
        Map<String, Long> levels = stock(first, firstAmount);
        levels.put(second, secondAmount);
        return levels;
    }

    private static ListOrderConfirmation<String> question(List<ListLine<String>> lines, Map<String, Long> available,
            Map<String, Long> producible) {
        return ListOrderConfirmation.of(lines, 0, key -> available.getOrDefault(key, 0L),
                key -> producible.getOrDefault(key, 0L));
    }

    /** Whether {@code question} is authorised by the Yes a player gave to {@code asked}. */
    private static boolean answers(ListOrderConfirmation<String> question, ListOrderConfirmation<String> asked) {
        return question.covers(asked.missing(), asked.producing(), asked.entriesDropped());
    }

    /** The one invariant: a question can never name an amount no order could reach. */
    private static void addsUp(ListOrderConfirmation<String> question) {
        assertEquals(question.wanted(), question.serveable() + question.producing() + question.missing(),
                "wanted == serveable + producing + missing");
    }
}
