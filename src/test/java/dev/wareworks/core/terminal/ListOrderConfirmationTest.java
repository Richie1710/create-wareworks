package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.production.PlanBudget;
import dev.wareworks.core.production.PlanLimits;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.ProductionPlanInput;
import dev.wareworks.core.production.StationPattern;
import dev.wareworks.core.stock.StockRule;

/**
 * What pressing Fetch on a clipboard really means (M23, issue #19; measured with the click's planner since issue #36,
 * ADR-069): one line per entry, its split into stock, production and missing, the planner's reason with the item it
 * names, and the materials it is short of.
 * <p>
 * The arithmetic has one job beyond adding up: it must never name an amount no order could reach, and it must never
 * call an entry impossible that a click would make. That is why the stock and the chains' ingredients are <b>spent</b>
 * as the lines are measured, why every entry is planned with the click's own planner — a lantern whose nuggets have to
 * be made of ingots first is producible (the bug of issue #36) — and why {@code wanted == serveable + producing +
 * missing} is checked on every list here.
 */
class ListOrderConfirmationTest {
    private static final String COBBLE = "cobblestone";
    private static final String DIAMOND = "diamond";
    private static final String LOG = "oak_log";
    private static final String PLANK = "oak_plank";
    private static final String INGOT = "iron_ingot";
    private static final String NUGGET = "iron_nugget";
    private static final String COAL = "coal";
    private static final String STICK = "stick";
    private static final String TORCH = "torch";
    private static final String LANTERN = "lantern";

    private static final String STATION = "station";

    /** One log makes four planks. */
    private static final ProductionPattern<String> PLANKS = ProductionPattern.of(LOG, 1, PLANK, 4);
    /** One ingot makes nine nuggets. */
    private static final ProductionPattern<String> NUGGETS = ProductionPattern.of(INGOT, 1, NUGGET, 9);
    /** Coal and a stick make four torches. */
    private static final ProductionPattern<String> TORCHES = new ProductionPattern<>(
            List.of(new ProductionEntry<>(COAL, 1), new ProductionEntry<>(STICK, 1)), new ProductionEntry<>(TORCH, 4));
    /** Eight nuggets around a torch make a lantern: the owner's pattern of issue #36. */
    private static final ProductionPattern<String> LANTERNS = new ProductionPattern<>(
            List.of(new ProductionEntry<>(NUGGET, 8), new ProductionEntry<>(TORCH, 1)),
            new ProductionEntry<>(LANTERN, 1));

    private static final List<StationPattern<String, String>> AISLE = StationPattern.at(STATION,
            List.of(PLANKS, NUGGETS, TORCHES, LANTERNS));

    private final Map<String, Long> stock = new LinkedHashMap<>();

    // --- the four kinds of entry -------------------------------------------------------------------------------------

    @Test
    void aListTheRacksCoverAsksNothing() {
        stock(COBBLE, 2000);
        stock(PLANK, 64);
        ListOrderConfirmation<String> question = measure(input(), line(COBBLE, 2000), line(PLANK, 64));
        assertFalse(question.required(), "a stocked warehouse fetches a list without a dialog");
        assertEquals(2064L, question.serveable());
        assertEquals(0L, question.missing());
        assertEquals(0L, question.producing());
        assertEquals(2, question.entriesServed());
        assertEquals(2, question.entries());
        assertTrue(question.lines().stream().allMatch(line -> line.status() == ListEntryStatus.AVAILABLE));
        assertTrue(question.shortages().isEmpty(), "nothing is missing");
        assertTrue(question.covers(0L, 0L, 0L));
    }

    @Test
    void partialStockOfARawItemIsPartlyAndNamesNoPattern() {
        stock(COBBLE, 1300);
        ListOrderConfirmation<String> question = measure(input(), line(COBBLE, 2000));
        assertTrue(question.required());
        ListOrderConfirmation.Line<String> line = question.lines().getFirst();
        assertEquals(ListEntryStatus.PARTLY, line.status());
        assertEquals(2000L, line.wanted());
        assertEquals(1300L, line.fromStock());
        assertEquals(700L, line.missing());
        assertEquals(Optional.of(PlanRefusal.NO_PATTERN), line.refusal(), "nothing here makes cobblestone");
        assertEquals(Optional.of(COBBLE), line.about());
        assertEquals(List.of(new ListOrderConfirmation.Material<>(COBBLE, 700L)), line.materials(),
                "the item itself is what is missing");
        assertEquals(1300L, question.obtainable());
        addsUp(question);
    }

    @Test
    void anEntryProductionCoversIsCrafted() {
        stock(LOG, 100);
        ListOrderConfirmation<String> question = measure(input(), line(PLANK, 384));
        ListOrderConfirmation.Line<String> line = question.lines().getFirst();
        assertEquals(ListEntryStatus.CRAFTED, line.status());
        assertEquals(384L, line.produced());
        assertEquals(384L, question.producing());
        assertEquals(0L, question.missing());
        assertTrue(line.refusal().isEmpty());
        assertTrue(line.materials().isEmpty());
        assertEquals(1, question.entriesProducing());
    }

    @Test
    void wholeRunsAreReportedBesideThePromise() {
        stock(LOG, 1);
        ListOrderConfirmation.Line<String> line = measure(input(), line(PLANK, 3)).lines().getFirst();
        assertEquals(3L, line.produced(), "the promise is what was asked for");
        assertEquals(4L, line.made(), "a log makes four planks");
    }

    @Test
    void anEntryTheWarehouseCanDoNothingForIsImpossible() {
        ListOrderConfirmation<String> question = measure(input(), line(DIAMOND, 7));
        ListOrderConfirmation.Line<String> line = question.lines().getFirst();
        assertEquals(ListEntryStatus.IMPOSSIBLE, line.status());
        assertEquals(Optional.of(PlanRefusal.NO_PATTERN), line.refusal());
        assertEquals(7L, question.missing());
        assertEquals(1, question.entriesImpossible());
    }

    @Test
    void anAisleWithoutPatternsAnswersNoPatternForEveryShortfall() {
        stock(PLANK, 10);
        ProductionPlanInput<String, String> none = ProductionPlanInput.of(List.of(), PlanBudget.ofMap(stock),
                PlanLimits.DEFAULT, 16);
        ListOrderConfirmation.Line<String> line = measure(none, line(PLANK, 30)).lines().getFirst();
        assertEquals(ListEntryStatus.PARTLY, line.status());
        assertEquals(Optional.of(PlanRefusal.NO_PATTERN), line.refusal());
        assertEquals(20L, line.missing());
    }

    // --- the lantern of issue #36 ------------------------------------------------------------------------------------

    @Test
    void aLanternWithItsIngredientsInStockIsCrafted() {
        stock(NUGGET, 16);
        stock(TORCH, 2);
        ListOrderConfirmation.Line<String> line = measure(input(), line(LANTERN, 2)).lines().getFirst();
        assertEquals(ListEntryStatus.CRAFTED, line.status());
        assertEquals(2L, line.produced());
    }

    @Test
    void aLanternWhoseIngredientsHaveToBeMadeFirstIsCrafted() {
        // The bug of issue #36: the old measurement looked one level deep and called this "0 of 2", while a click
        // plans ingots -> nuggets and coal + stick -> torches and makes both lanterns.
        stock(INGOT, 2);
        stock(COAL, 1);
        stock(STICK, 1);
        ListOrderConfirmation<String> question = measure(input(), line(LANTERN, 2));
        ListOrderConfirmation.Line<String> line = question.lines().getFirst();
        assertEquals(ListEntryStatus.CRAFTED, line.status(), "the chain makes it, as a click would");
        assertEquals(2L, line.produced());
        assertEquals(0L, line.missing());
        assertEquals(2L, question.producing(), "and that is the number the Yes agrees to");
    }

    @Test
    void aClampedChainIsPartlyAndTheNextPlanNamesTheMissingRawMaterial() {
        stock(INGOT, 2);
        stock(COAL, 2);
        stock(STICK, 2);
        ListOrderConfirmation.Line<String> line = measure(input(), line(LANTERN, 3)).lines().getFirst();
        assertEquals(ListEntryStatus.PARTLY, line.status());
        assertEquals(2L, line.produced(), "two ingots make the nuggets of two lanterns");
        assertEquals(1L, line.missing());
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), line.refusal());
        assertEquals(Optional.of(INGOT), line.about());
        assertEquals(List.of(new ListOrderConfirmation.Material<>(INGOT, 1L)), line.materials(),
                "one more ingot would make the third");
    }

    @Test
    void aStoppedIngredientIsRefusedAsPaused() {
        stock(NUGGET, 16);
        stock(COAL, 1);
        stock(STICK, 1);
        ListOrderConfirmation.Line<String> line = measure(input().withPaused(Set.of(TORCH)), line(LANTERN, 2))
                .lines().getFirst();
        assertEquals(ListEntryStatus.IMPOSSIBLE, line.status(),
                "the old measurement called this producible, and the portion was then refused");
        assertEquals(Optional.of(PlanRefusal.PAUSED), line.refusal());
        assertEquals(Optional.of(TORCH), line.about());
        assertEquals(List.of(new ListOrderConfirmation.Material<>(LANTERN, 2L)), line.materials(),
                "a stop is no material, so the entry's own item is what is missing");
    }

    @Test
    void aChainLongerThanTheStepLimitIsRefusedAsTooLong() {
        stock(INGOT, 2);
        stock(COAL, 1);
        stock(STICK, 1);
        ProductionPlanInput<String, String> short_ = ProductionPlanInput.of(AISLE, PlanBudget.ofMap(stock),
                new PlanLimits(2, PlanLimits.MAX_INGREDIENT_ITEMS), 16);
        ListOrderConfirmation.Line<String> line = measure(short_, line(LANTERN, 2)).lines().getFirst();
        assertEquals(ListEntryStatus.IMPOSSIBLE, line.status());
        assertEquals(Optional.of(PlanRefusal.TOO_MANY_STEPS), line.refusal());
    }

    @Test
    void anEntryWithNothingInStockNamesEveryRawMaterialItIsShortOf() {
        ListOrderConfirmation.Line<String> line = measure(input(), line(LANTERN, 2)).lines().getFirst();
        assertEquals(ListEntryStatus.IMPOSSIBLE, line.status());
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), line.refusal());
        Map<String, Long> materials = line.materials().stream()
                .collect(Collectors.toMap(ListOrderConfirmation.Material::key, ListOrderConfirmation.Material::amount));
        assertEquals(Map.of(INGOT, 2L, COAL, 1L, STICK, 1L), materials,
                "two ingots for sixteen nuggets, one coal and one stick for the torches");
    }

    // --- one ledger for the whole list --------------------------------------------------------------------------------

    @Test
    void twoEntriesOfOneItemShareWhatTheWarehouseHas() {
        stock(COBBLE, 1300);
        ListOrderConfirmation<String> question = measure(input(), line(COBBLE, 1300), line(COBBLE, 1300));
        assertEquals(2600L, question.wanted());
        assertEquals(1300L, question.serveable(), "1300 cobblestone cannot serve two entries of 1300");
        assertEquals(1, question.entriesServed(), "the first entry is covered");
        assertEquals(1, question.entriesImpossible(), "and the second one gets nothing at all");
        addsUp(question);
    }

    @Test
    void aChainsIngredientsAreSpentBeforeTheNextEntry() {
        stock(NUGGET, 16);
        stock(TORCH, 2);
        ListOrderConfirmation<String> question = measure(input(), line(NUGGET, 16), line(LANTERN, 2));
        assertEquals(ListEntryStatus.AVAILABLE, question.lines().get(0).status(), "the nuggets take the nuggets");
        ListOrderConfirmation.Line<String> lantern = question.lines().get(1);
        assertEquals(ListEntryStatus.IMPOSSIBLE, lantern.status(), "so the lanterns cannot have them as well");
        assertEquals(Optional.of(INGOT), lantern.about());
        assertEquals(List.of(new ListOrderConfirmation.Material<>(INGOT, 2L)), lantern.materials());
        addsUp(question);
    }

    @Test
    void anAcceptedChainSpendsItsRawMaterialForTheLaterEntries() {
        stock(LOG, 2);
        ListOrderConfirmation<String> question = measure(input(), line(PLANK, 8), line(PLANK, 8));
        assertEquals(ListEntryStatus.CRAFTED, question.lines().get(0).status());
        assertEquals(ListEntryStatus.IMPOSSIBLE, question.lines().get(1).status(), "the two logs are gone");
        assertEquals(Optional.of(PlanRefusal.MISSING_INGREDIENT), question.lines().get(1).refusal());
        assertEquals(Optional.of(LOG), question.lines().get(1).about());
    }

    @Test
    void orderSlotsAreNotSharedAcrossTheEntries() {
        stock(LOG, 2);
        ProductionPlanInput<String, String> oneSlot = ProductionPlanInput.of(AISLE, PlanBudget.ofMap(stock),
                PlanLimits.UNBOUNDED, 1, 4);
        ListOrderConfirmation<String> question = measure(oneSlot, line(PLANK, 4), line(PLANK, 4));
        assertEquals(ListEntryStatus.CRAFTED, question.lines().get(0).status());
        assertEquals(ListEntryStatus.CRAFTED, question.lines().get(1).status(),
                "a list's portions run one after another, so the slot is free again");
    }

    @Test
    void aFullOrderCapIsRefusedAsBusy() {
        stock(LOG, 2);
        ProductionPlanInput<String, String> full = ProductionPlanInput.of(AISLE, PlanBudget.ofMap(stock),
                PlanLimits.UNBOUNDED, 0, 4);
        ListOrderConfirmation.Line<String> line = measure(full, line(PLANK, 4)).lines().getFirst();
        assertEquals(ListEntryStatus.IMPOSSIBLE, line.status());
        assertEquals(Optional.of(PlanRefusal.ORDERS_BUSY), line.refusal());
    }

    @Test
    void aChainTheIngredientBoundClampsIsPlannedAgainForTheRest() {
        stock(LOG, 100);
        ProductionPlanInput<String, String> bounded = ProductionPlanInput.of(AISLE, PlanBudget.ofMap(stock),
                new PlanLimits(32, 2L), 16);
        ListOrderConfirmation.Line<String> line = measure(bounded, line(PLANK, 16)).lines().getFirst();
        assertEquals(ListEntryStatus.CRAFTED, line.status(), "two chains of two logs each");
        assertEquals(16L, line.produced());

        ListOrderConfirmation.Line<String> tooMuch = measure(bounded, line(PLANK, 40)).lines().getFirst();
        assertEquals(ListEntryStatus.PARTLY, tooMuch.status(), "at most four chains per entry");
        assertEquals(32L, tooMuch.produced());
        assertTrue(tooMuch.refusal().isEmpty(), "every plan succeeded, so there is no reason to name");
        assertEquals(List.of(new ListOrderConfirmation.Material<>(PLANK, 8L)), tooMuch.materials());
    }

    @Test
    void everyTotalOfAMixedListAddsUp() {
        stock(COBBLE, 1300);
        stock(LOG, 100);
        ListOrderConfirmation<String> question = measure(input(), line(COBBLE, 2000), line(PLANK, 384),
                line(DIAMOND, 7), line(COBBLE, 500));
        assertEquals(2891L, question.wanted());
        assertEquals(1300L, question.serveable());
        assertEquals(384L, question.producing());
        assertEquals(1207L, question.missing());
        assertEquals(1, question.entriesShort(), "the 2000 cobblestone");
        assertEquals(1, question.entriesProducing(), "the planks");
        assertEquals(2, question.entriesImpossible(), "the diamonds, and the second cobblestone entry");
        assertEquals(4, question.entries());
        assertEquals(1300L + 384L, question.obtainable());
        addsUp(question);
    }

    // --- the table ---------------------------------------------------------------------------------------------------

    @Test
    void theTableShowsTheProblemsFirstAndKeepsListOrderWithinAStatus() {
        stock(COBBLE, 100);
        stock(LOG, 10);
        ListOrderConfirmation<String> question = measure(input(), line(COBBLE, 10), line(PLANK, 4),
                line(DIAMOND, 1), line(COBBLE, 200), line(INGOT, 1), line(LOG, 1));
        List<String> order = question.tableOrder().stream()
                .map(line -> line.status() + ":" + line.order()).toList();
        assertEquals(List.of("IMPOSSIBLE:2", "IMPOSSIBLE:4", "PARTLY:3", "CRAFTED:1", "AVAILABLE:0",
                "AVAILABLE:5"), order);
        assertEquals(List.of(0, 1, 2, 3, 4, 5), question.lines().stream().map(ListOrderConfirmation.Line::order)
                .toList(), "the lines themselves stay in list order");
    }

    @Test
    void theMissingSectionSumsTheMaterialsOfEveryEntry() {
        ListOrderConfirmation<String> question = measure(input(), line(LANTERN, 2), line(DIAMOND, 3),
                line(NUGGET, 9));
        List<ListShortage<String>> shortages = question.shortages();
        Map<String, ListShortage<String>> byKey = shortages.stream()
                .collect(Collectors.toMap(ListShortage::key, shortage -> shortage));
        assertEquals(3L, byKey.get(INGOT).amount(), "two for the lanterns, one for the nuggets");
        assertEquals(List.of(0, 2), byKey.get(INGOT).entries());
        assertEquals(3L, byKey.get(DIAMOND).amount());
        assertEquals(List.of(1), byKey.get(DIAMOND).entries());
        assertEquals(INGOT, shortages.getFirst().key(), "ties by first appearance in list order");
    }

    @Test
    void completeLinesAreNotPartOfTheQuestion() {
        assertFalse(ListOrderConfirmation.<String>none().required());
        stock(COBBLE, 10);
        ListLine<String> done = ListLine.of(0, 0, COBBLE, 64).withPortion(UUID.randomUUID(), 64).withCredited(64);
        assertTrue(done.isComplete());
        ListOrderConfirmation<String> question = measure(input(), done, line(COBBLE, 10));
        assertEquals(10L, question.wanted(), "resuming a half-finished order asks only about what is left");
        assertEquals(1, question.entries());
        assertEquals(1, question.lines().getFirst().order(), "and the line keeps its place in the list");
    }

    @Test
    void aWarehouseThatAnswersWithNonsenseIsReadAsHavingNothing() {
        ProductionPlanInput<String, String> nonsense = ProductionPlanInput.of(AISLE, PlanBudget.of(key -> -500L),
                PlanLimits.DEFAULT, 16);
        ListOrderConfirmation<String> question = measure(nonsense, line(COBBLE, 10));
        assertEquals(10L, question.missing());
        assertEquals(0L, question.serveable());
        addsUp(question);
    }

    // --- the record keeps itself consistent ---------------------------------------------------------------------------

    @Test
    void aLineBuiltFromAPayloadDerivesWhatIsMissingAndClampsEverything() {
        ListOrderConfirmation.Line<String> line = new ListOrderConfirmation.Line<>(-3, COBBLE, 10L, 20L, 5L, -1L,
                999L, Optional.of(PlanRefusal.NO_PATTERN), Optional.of(COBBLE),
                List.of(new ListOrderConfirmation.Material<>(COBBLE, 1L)));
        assertEquals(0, line.order());
        assertEquals(10L, line.fromStock(), "the racks never give more than is wanted");
        assertEquals(0L, line.produced());
        assertEquals(0L, line.missing(), "missing is derived, never taken");
        assertTrue(line.refusal().isEmpty(), "a reason without anything missing is dropped");
        assertTrue(line.about().isEmpty());
        assertTrue(line.materials().isEmpty());

        ListOrderConfirmation.Line<String> aboutAlone = new ListOrderConfirmation.Line<>(0, COBBLE, 10L, 0L, 0L, 0L,
                0L, Optional.empty(), Optional.of(DIAMOND), List.of());
        assertEquals(10L, aboutAlone.missing());
        assertTrue(aboutAlone.about().isEmpty(), "an item without a reason says nothing");

        ListOrderConfirmation.Line<String> many = new ListOrderConfirmation.Line<>(0, COBBLE, 10L, 0L, 0L, 0L, 0L,
                Optional.empty(), Optional.empty(), List.of(new ListOrderConfirmation.Material<>(LOG, 1L),
                        new ListOrderConfirmation.Material<>(LOG, 2L), new ListOrderConfirmation.Material<>(COAL, -4L),
                        new ListOrderConfirmation.Material<>(STICK, 1L), new ListOrderConfirmation.Material<>(INGOT,
                                1L)));
        assertEquals(List.of(LOG, COAL, STICK), many.materials().stream().map(ListOrderConfirmation.Material::key)
                .toList(), "distinct, at most three");
        assertEquals(1L, many.materials().get(1).amount(), "and at least one each");
    }

    @Test
    void aQuestionNeverHoldsMoreLinesThanTheCap() {
        List<ListOrderConfirmation.Line<String>> lines = new ArrayList<>();
        for (int i = 0; i < ListOrderConfirmation.MAX_LINES + 5; i++)
            lines.add(new ListOrderConfirmation.Line<>(i, COBBLE, 1L, 1L, 0L, 0L, 0L, Optional.empty(),
                    Optional.empty(), List.of()));
        ListOrderConfirmation<String> question = new ListOrderConfirmation<>(lines, -1);
        assertEquals(ListOrderConfirmation.MAX_LINES, question.entries());
        assertEquals(0, question.entriesDropped());
        assertFalse(question.required());
    }

    // --- the round trip ---------------------------------------------------------------------------------------------

    @Test
    void theAnswerIsMeasuredAgainAndCoversOnlyWhatItWasGiven() {
        stock(COBBLE, 1300);
        stock(LOG, 1000);
        ListOrderConfirmation<String> asked = measure(input(), line(COBBLE, 2000), line(PLANK, 384));
        assertTrue(answers(asked, asked), "the Yes to this question authorises it");

        stock(COBBLE, 1900);
        assertTrue(answers(measure(input(), line(COBBLE, 2000), line(PLANK, 384)), asked),
                "a warehouse that gained stock in between costs less, so the Yes still holds");

        stock(COBBLE, 900);
        assertFalse(answers(measure(input(), line(COBBLE, 2000), line(PLANK, 384)), asked),
                "one that lost stock is asked again");

        stock(COBBLE, 1300);
        assertFalse(answers(measure(input(), line(COBBLE, 2000), line(PLANK, 500)), asked),
                "and so is more production than agreed");
    }

    @Test
    void aClipboardLongerThanTheCapIsAlwaysAskedAbout() {
        stock(COBBLE, 64);
        ListOrderConfirmation<String> question = ListOrderConfirmation.measure(List.of(line(COBBLE, 64)), 3,
                input());
        assertTrue(question.truncated(), "three entries stayed on the clipboard");
        assertEquals(3, question.entriesDropped());
        assertTrue(question.required(),
                "a list the cap cut short asks even when everything it took is in stock (M23 review fix)");
        assertEquals(0L, question.missing(), "the untaken entries are reported, not measured");
        assertEquals(1, question.entries(), "and they are no part of the entry counts");
        assertFalse(question.covers(0L, 0L, 0L), "a Fetch that says nothing about them is asked again");
        assertFalse(question.covers(0L, 0L, 2L), "and so is one that accepts fewer than were left behind");
        assertTrue(question.covers(0L, 0L, 3L), "the Yes names them");
    }

    @Test
    void aYesCreatesAConsentBudgetOfExactlyTheProductionItAgreedTo() {
        stock(LOG, 1000);
        RequestAcknowledgement budget = measure(input(), line(PLANK, 384)).budget();
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
    @Test
    void theYesAgreesToWholeRunsSoTheFirstPortionIsCovered() {
        // "Torch x2" from a four-torch pattern: the promise is two, but the portion is charged the four one run makes.
        stock(COAL, 1);
        stock(STICK, 1);
        ListOrderConfirmation<String> question = measure(input(), line(TORCH, 2));
        assertEquals(2L, question.producing(), "the promise is what the entry asks for");
        assertEquals(4L, question.madeTotal(), "one run makes four");
        assertFalse(question.covers(0L, 2L, 0L), "a Yes to the promise alone does not cover the run");
        assertTrue(question.covers(0L, 4L, 0L), "a Yes to the whole run does");

        RequestAcknowledgement budget = question.budget();
        assertEquals(4L, budget.produced());
        RequestConfirmation<String> portion = new RequestConfirmation<>(TORCH, 2L, 0L, 0L, 0L, 4L, StockRule.UNSET,
                List.of());
        assertTrue(budget.covers(portion, RequestScope.LIST), "so the first portion starts without a second question");
    }


    // --- helpers -----------------------------------------------------------------------------------------------------

    private void stock(String key, long amount) {
        stock.put(key, amount);
    }

    /** An idle aisle with every pattern, plenty of order slots and the default bounds. */
    private ProductionPlanInput<String, String> input() {
        return ProductionPlanInput.of(AISLE, PlanBudget.ofMap(stock), PlanLimits.DEFAULT, 16);
    }

    private static ListLine<String> line(String key, int wanted) {
        return ListLine.of(0, 0, key, wanted);
    }

    @SafeVarargs
    private static ListOrderConfirmation<String> measure(ProductionPlanInput<String, String> base,
            ListLine<String>... lines) {
        return ListOrderConfirmation.measure(List.of(lines), 0, base);
    }

    /** Whether {@code question} is authorised by the Yes a player gave to {@code asked}. */
    private static boolean answers(ListOrderConfirmation<String> question, ListOrderConfirmation<String> asked) {
        return question.covers(asked.missing(), asked.madeTotal(), asked.entriesDropped());
    }

    /** The one invariant: a question can never name an amount no order could reach. */
    private static void addsUp(ListOrderConfirmation<String> question) {
        assertEquals(question.wanted(), question.serveable() + question.producing() + question.missing(),
                "wanted == serveable + producing + missing");
    }
}
