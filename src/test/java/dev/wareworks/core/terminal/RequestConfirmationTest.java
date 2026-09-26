package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.stock.StockLevels;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRules;

/**
 * What a player's click at a warehouse terminal would cost across the boundaries a stock keeper set (M15 part 2,
 * issue #3), i.e. the question the terminal asks before it makes the request.
 * <p>
 * The three costs are told apart on purpose, because they come from three different directions: the reserve of the
 * item itself, the reserve of an <b>ingredient</b> a production order would spend, and the storage maximum the result
 * would go past. Only the second is impossible for a screen to work out, which is why the whole decision lives on the
 * server; these tests are the decision without a game around it.
 */
class RequestConfirmationTest {
    private static final String PLANK = "plank";
    private static final String LOG = "log";
    private static final String NAILS = "nails";

    /** One log and two nails make four planks: a pattern whose runs overshoot what is asked for. */
    private static final ProductionPattern<String> PATTERN = new ProductionPattern<>(
            List.of(new ProductionEntry<>(LOG, 1), new ProductionEntry<>(NAILS, 2)),
            new ProductionEntry<>(PLANK, 4));

    @SafeVarargs
    private static StockRules<String> rules(StockRule<String>... rules) {
        return StockRules.of(List.of(rules));
    }

    private static StockRule<String> reserve(String key, long reserve) {
        return new StockRule<>(key, StockRule.UNSET, StockRule.UNSET, reserve);
    }

    private static ToLongFunction<String> available(Map<String, Long> stock) {
        return key -> stock.getOrDefault(key, 0L);
    }

    /** A request that is served out of the racks alone, i.e. nothing would be produced. */
    private static RequestConfirmation<String> fromStock(StockRules<String> rules, long wanted, long available) {
        return RequestConfirmation.of(rules, PLANK, wanted, StockLevels.stored(available), Optional.empty(), 0L,
                key -> 0L);
    }

    // --- nothing to ask about ---------------------------------------------------------------------------------------

    @Test
    void aWarehouseWithoutRulesAsksNothing() {
        RequestConfirmation<String> question = fromStock(StockRules.empty(), 64L, 64L);
        assertFalse(question.required());
        assertEquals(0L, question.fromReserve());
        assertEquals(0L, question.pastMaximum());
        assertEquals(StockRule.UNSET, question.maximum());
        assertTrue(question.ingredients().isEmpty());
        assertEquals(64L, question.amount());
    }

    @Test
    void aRequestThatStaysAboveTheReserveAsksNothing() {
        StockRules<String> rules = rules(reserve(PLANK, 16L));
        RequestConfirmation<String> question = fromStock(rules, 48L, 64L);
        assertFalse(question.required());
        assertEquals(0L, question.fromReserve());
        assertEquals(16L, question.reserved(), "the reserve is still reported, so a row can name it");
    }

    @Test
    void theEmptyQuestionCrossesNothingWhateverTheAmount() {
        RequestConfirmation<String> question = RequestConfirmation.none(PLANK, 99L);
        assertFalse(question.required());
        assertEquals(99L, question.amount());
        assertEquals(RequestAcknowledgement.NONE, question.acknowledgement());
    }

    // --- the reserve of the requested item --------------------------------------------------------------------------

    @Test
    void aRequestThatReachesIntoTheReserveIsAskedAboutWithBothNumbers() {
        StockRules<String> rules = rules(reserve(PLANK, 16L));
        RequestConfirmation<String> question = fromStock(rules, 52L, 64L);
        assertTrue(question.required());
        assertEquals(4L, question.fromReserve(), "48 are free, so four come out of the reserve");
        assertEquals(16L, question.reserved(), "and the panel says out of how many");
    }

    @Test
    void aRequestForEverythingTakesTheWholeReserveAndNoMore() {
        StockRules<String> rules = rules(reserve(PLANK, 16L));
        RequestConfirmation<String> question = fromStock(rules, 64L, 64L);
        assertEquals(16L, question.fromReserve());
        assertEquals(16L, question.reserved());
    }

    /** What is not in the racks is not "taken from the reserve", it is simply missing. */
    @Test
    void askingForMoreThanIsThereNeverInventsReservedItems() {
        StockRules<String> rules = rules(reserve(PLANK, 16L));
        RequestConfirmation<String> question = fromStock(rules, 500L, 64L);
        assertEquals(16L, question.fromReserve());
        assertEquals(64L, question.amount(), "and the question is about what the request would really take");
    }

    /** With a pattern the bound is what the racks hold plus what those runs would yield, and not a byte more. */
    @Test
    void aQuestionNeverNamesMoreThanTheAisleCouldServe() {
        StockRules<String> rules = rules(reserve(PLANK, 16L));
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 500L, StockLevels.stored(64L),
                Optional.of(PATTERN), 2L, available(Map.of(LOG, 2L, NAILS, 4L)));
        assertEquals(72L, question.amount(), "64 in the racks and two runs of four");
    }

    @Test
    void aReserveOnAnUnrelatedItemIsNotAskedAbout() {
        StockRules<String> rules = rules(reserve(LOG, 16L));
        assertFalse(fromStock(rules, 64L, 64L).required());
    }

    /** A shadowed rule applies nothing, so it is nothing to ask about either. */
    @Test
    void onlyAGoverningRuleRaisesAQuestion() {
        StockRules<String> rules = rules(new StockRule<>(PLANK, 10L, StockRule.UNSET, StockRule.UNSET),
                reserve(PLANK, 16L));
        assertFalse(fromStock(rules, 64L, 64L).required(),
                "the first rule governs the item and reserves nothing; the second one applies nothing at all");
    }

    // --- the maximum the result would be left above -------------------------------------------------------------------

    /**
     * What is asked about is what <b>stays</b>: a pattern makes whole runs, so an order for one plank makes four and the
     * three nobody asked for land in the racks. Those three are the part a player can do something about.
     */
    @Test
    void aWholeRunSurplusThatCannotBeStoredIsAskedAbout() {
        StockRules<String> rules = rules(new StockRule<>(PLANK, StockRule.UNSET, 2L, StockRule.UNSET));
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 1L, StockLevels.stored(0L),
                Optional.of(PATTERN), 1L, available(Map.of(LOG, 6L, NAILS, 12L)));
        assertTrue(question.required());
        assertEquals(4L, question.made(), "one run makes four");
        assertEquals(1L, question.amount(), "one of them was asked for");
        assertEquals(1L, question.pastMaximum(), "three stay, and the cap of two leaves room for two of them");
        assertEquals(2L, question.maximum());
        assertTrue(question.overflows());
    }

    /**
     * <b>The level the racks pass through while the order runs is not asked about.</b> Those items are promised to this
     * very request and leave again, so a question about them names a state the same request undoes — and spends the
     * credibility of the dialog the reserve depends on (M15 review fix).
     */
    @Test
    void theLevelTheOrderPassesThroughIsNotAskedAbout() {
        StockRules<String> rules = rules(new StockRule<>(PLANK, StockRule.UNSET, 2L, StockRule.UNSET));
        // A cap of two, nothing in stock, four asked for and one run that makes exactly four: while the order runs the
        // racks really do hold four, and every one of them is on its way to the player.
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 4L, StockLevels.stored(0L),
                Optional.of(PATTERN), 1L, available(Map.of(LOG, 6L, NAILS, 12L)));
        assertEquals(0L, question.pastMaximum());
        assertFalse(question.required());
        assertFalse(question.overflows());
    }

    @Test
    void whatTheWarehouseAlreadyHoldsCountsAgainstTheMaximum() {
        StockRules<String> rules = rules(new StockRule<>(PLANK, StockRule.UNSET, 52L, StockRule.UNSET));
        // 50 in the racks and all of them promised elsewhere, so the one plank asked for has to be made: the run makes
        // four, three of them stay, and the cap of 52 leaves room for two.
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 1L,
                new StockLevels(50L, 0L, 0L, 0L), Optional.of(PATTERN), 1L, available(Map.of(LOG, 5L, NAILS, 10L)));
        assertEquals(1L, question.pastMaximum());
    }

    /** What another order will bring back is promised to that order's own request, not left in the racks by this one. */
    @Test
    void whatAnotherOrderWillBringIsNotCountedAgainstThisRequest() {
        StockRules<String> rules = rules(new StockRule<>(PLANK, StockRule.UNSET, 16L, StockRule.UNSET));
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 24L,
                new StockLevels(0L, 0L, 8L, 0L), Optional.of(PATTERN), 6L, available(Map.of(LOG, 6L, NAILS, 12L)));
        assertEquals(0L, question.pastMaximum(), "six runs make exactly the 24 that were asked for");
    }

    @Test
    void aRequestServedOutOfTheRacksNeverGoesPastTheMaximum() {
        StockRules<String> rules = rules(new StockRule<>(PLANK, StockRule.UNSET, 1L, StockRule.UNSET));
        RequestConfirmation<String> question = fromStock(rules, 64L, 64L);
        assertEquals(0L, question.pastMaximum(), "taking items out never stores any");
        assertEquals(0L, question.made(), "and nothing is made for it");
        assertFalse(question.required());
    }

    @Test
    void withoutAMaximumNothingIsEverPastIt() {
        StockRules<String> rules = rules(reserve(PLANK, 0L));
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 24L, StockLevels.stored(0L),
                Optional.of(PATTERN), 6L, available(Map.of(LOG, 6L, NAILS, 12L)));
        assertEquals(0L, question.pastMaximum());
        assertEquals(StockRule.UNSET, question.maximum());
    }

    // --- the ingredients a production order would spend -------------------------------------------------------------

    @Test
    void anIngredientAReserveProtectsIsNamedWithItsItem() {
        StockRules<String> rules = rules(reserve(LOG, 4L));
        // Six runs need six logs; only two are free above the reserve, so four come out of it.
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 24L, StockLevels.stored(0L),
                Optional.of(PATTERN), 6L, available(Map.of(LOG, 6L, NAILS, 12L)));
        assertTrue(question.required());
        assertEquals(1, question.ingredients().size());
        RequestConfirmation.ReservedIngredient<String> ingredient = question.ingredients().getFirst();
        assertEquals(LOG, ingredient.key());
        assertEquals(4L, ingredient.fromReserve());
        assertEquals(4L, ingredient.reserved());
        assertEquals(4L, question.fromIngredientReserve());
    }

    @Test
    void everyReservedIngredientOfAPatternIsNamed() {
        StockRules<String> rules = rules(reserve(LOG, 4L), reserve(NAILS, 6L));
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 24L, StockLevels.stored(0L),
                Optional.of(PATTERN), 6L, available(Map.of(LOG, 6L, NAILS, 12L)));
        assertEquals(List.of(LOG, NAILS), question.ingredients().stream()
                .map(RequestConfirmation.ReservedIngredient::key).toList(), "in pattern order");
        assertEquals(4L, question.ingredients().getFirst().fromReserve());
        assertEquals(6L, question.ingredients().get(1).fromReserve(), "twelve needed, six free above the reserve");
        assertEquals(10L, question.fromIngredientReserve());
    }

    @Test
    void anIngredientThatStaysAboveItsReserveIsNotNamed() {
        StockRules<String> rules = rules(reserve(LOG, 4L));
        // Two runs need two logs and ten are free above the reserve.
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 8L, StockLevels.stored(0L),
                Optional.of(PATTERN), 2L, available(Map.of(LOG, 14L, NAILS, 20L)));
        assertFalse(question.required());
        assertTrue(question.ingredients().isEmpty());
    }

    @Test
    void nothingIsAskedAboutTheIngredientsOfAnOrderThatIsNotStarted() {
        StockRules<String> rules = rules(reserve(LOG, 4L));
        // Everything asked for is in the racks, so no order would run whatever the pattern could do.
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 8L, StockLevels.stored(64L),
                Optional.of(PATTERN), 6L, available(Map.of(LOG, 1L, NAILS, 1L)));
        assertTrue(question.ingredients().isEmpty());
        assertFalse(question.required());
    }

    @Test
    void withoutAPatternThereAreNoIngredientsToAskAbout() {
        StockRules<String> rules = rules(reserve(LOG, 4L));
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 24L, StockLevels.stored(0L),
                Optional.empty(), 6L, available(Map.of(LOG, 1L)));
        assertFalse(question.required());
    }

    @Test
    void theRunCountDecidesHowManyIngredientsAreCounted() {
        StockRules<String> rules = rules(reserve(LOG, 8L));
        ToLongFunction<String> stock = available(Map.of(LOG, 8L, NAILS, 40L));
        assertEquals(2L, RequestConfirmation.of(rules, PLANK, 8L, StockLevels.stored(0L), Optional.of(PATTERN), 2L,
                stock).fromIngredientReserve(), "two runs, two logs, all of them reserved");
        assertEquals(5L, RequestConfirmation.of(rules, PLANK, 20L, StockLevels.stored(0L), Optional.of(PATTERN), 5L,
                stock).fromIngredientReserve(), "five runs, five logs");
    }

    /** All three at once: the reserve of the item, an ingredient's reserve and the cap the surplus would be left above. */
    @Test
    void oneQuestionCanNameAllThreeCosts() {
        StockRules<String> rules = rules(new StockRule<>(PLANK, StockRule.UNSET, 2L, 1L), reserve(LOG, 4L));
        RequestConfirmation<String> question = RequestConfirmation.of(rules, PLANK, 2L,
                new StockLevels(1L, 0L, 0L, 1L), Optional.of(PATTERN), 1L, available(Map.of(LOG, 4L, NAILS, 8L)));
        assertTrue(question.required());
        assertEquals(1L, question.fromReserve(), "the one in the racks is reserved");
        assertEquals(1L, question.fromIngredientReserve(), "and the log the run needs is too");
        assertEquals(4L, question.made(), "the run makes four for the one plank it owes");
        assertEquals(1L, question.pastMaximum(), "three stay, and a cap of two leaves room for two of them");
        assertEquals(new RequestAcknowledgement(false, 1L, 1L, 1L), question.acknowledgement());
    }

    // --- construction ---------------------------------------------------------------------------------------------

    @Test
    void aQuestionAlwaysHasAnItem() {
        assertThrows(NullPointerException.class, () -> RequestConfirmation.none(null, 1L));
    }

    @Test
    void negativeAndImpossibleNumbersAreClamped() {
        RequestConfirmation<String> question = new RequestConfirmation<>(PLANK, -5L, -1L, -1L, -1L, -1L, -7L, null);
        assertEquals(0L, question.amount());
        assertEquals(0L, question.fromReserve());
        assertEquals(0L, question.reserved());
        assertEquals(0L, question.pastMaximum());
        assertEquals(0L, question.made());
        assertEquals(StockRule.UNSET, question.maximum());
        assertTrue(question.ingredients().isEmpty());
        assertFalse(question.required());
    }

    @Test
    void theReserveTakenIsNeverMoreThanTheReserveOrTheRequest() {
        assertEquals(4L,
                new RequestConfirmation<>(PLANK, 10L, 99L, 4L, 0L, 0L, StockRule.UNSET, List.of()).fromReserve());
        assertEquals(3L,
                new RequestConfirmation<>(PLANK, 3L, 99L, 64L, 0L, 0L, StockRule.UNSET, List.of()).fromReserve());
    }

    @Test
    void whatCannotBeStoredIsNeverMoreThanTheOrderMakesAndNeedsACap() {
        // Bounded by what the order really makes, not by what the player asked for: the asked-for part is exactly the
        // part that leaves the warehouse again (M15 review fix).
        assertEquals(12L, new RequestConfirmation<>(PLANK, 10L, 0L, 0L, 99L, 12L, 64L, List.of()).pastMaximum());
        assertEquals(0L,
                new RequestConfirmation<>(PLANK, 10L, 0L, 0L, 99L, 12L, StockRule.UNSET, List.of()).pastMaximum(),
                "without a cap nothing can be past it");
        assertEquals(0L, new RequestConfirmation<>(PLANK, 10L, 0L, 0L, 99L, 0L, 64L, List.of()).pastMaximum(),
                "and nothing that is never made can fail to be stored");
    }

    @Test
    void anIngredientListIsBoundedByWhatAPatternCanHold() {
        List<RequestConfirmation.ReservedIngredient<String>> many = new java.util.ArrayList<>();
        for (int i = 0; i < ProductionPattern.MAX_INGREDIENTS + 4; i++)
            many.add(new RequestConfirmation.ReservedIngredient<>("item" + i, 1L, 1L));
        assertEquals(ProductionPattern.MAX_INGREDIENTS,
                new RequestConfirmation<>(PLANK, 1L, 0L, 0L, 0L, 0L, StockRule.UNSET, many).ingredients().size());
    }

    @Test
    void anIngredientsNumbersAreClampedToo() {
        RequestConfirmation.ReservedIngredient<String> ingredient =
                new RequestConfirmation.ReservedIngredient<>(LOG, 99L, 4L);
        assertEquals(4L, ingredient.fromReserve());
        assertThrows(NullPointerException.class, () -> new RequestConfirmation.ReservedIngredient<>(null, 1L, 1L));
    }

    @Test
    void aQuestionThatCrossesNothingIsCoveredByEveryAnswer() {
        RequestConfirmation<String> nothing = RequestConfirmation.none(PLANK, 10L);
        assertTrue(RequestAcknowledgement.NONE.covers(nothing));
        assertSame(RequestAcknowledgement.NONE, RequestAcknowledgement.NONE);
    }
}
