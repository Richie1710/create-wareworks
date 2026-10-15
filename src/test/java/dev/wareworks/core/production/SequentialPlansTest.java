package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The lines of a craft fetch walked one after another against one snapshot (issue #24, slice 3,
 * {@code docs/warehouse-system.md} §3.4.5): the measurement behind the crafting grid's chain offer.
 * <p>
 * What it has to get right is what the craft order's portions will later meet: two chains that need the same raw
 * material share it rather than promising it twice, each line takes from the racks at its own turn in line order — so
 * a line whose items an earlier chain used has its own made too — the earlier chains' orders take the free order
 * slots, and every refusal names the item a player has to do something about.
 */
class SequentialPlansTest {
    private static final String LOG = "log";
    private static final String PLANK = "plank";
    private static final String CHARCOAL = "charcoal";
    private static final String STICK = "stick";
    private static final String CHEST = "chest";
    private static final String SLIME = "slime_ball";
    private static final String GATE = "oak_fence_gate";

    private static final String SAW = "saw";
    private static final String KILN = "kiln";
    private static final String CRAFTER = "crafter";

    /** One log makes four planks. */
    private static final ProductionPattern<String> PLANKS = ProductionPattern.of(LOG, 1, PLANK, 4);
    /** One log makes one charcoal: the second chain that wants the same logs. */
    private static final ProductionPattern<String> CHARCOALS = ProductionPattern.of(LOG, 1, CHARCOAL, 1);
    /** Two planks make four sticks. */
    private static final ProductionPattern<String> STICKS = ProductionPattern.of(PLANK, 2, STICK, 4);
    /** Eight planks make a chest. */
    private static final ProductionPattern<String> CHESTS = ProductionPattern.of(PLANK, 8, CHEST, 1);

    private static final List<StationPattern<String, String>> AISLE = List.of(StationPattern.of(SAW, PLANKS),
            StationPattern.of(KILN, CHARCOALS), StationPattern.of(CRAFTER, STICKS), StationPattern.of(CRAFTER, CHESTS));

    private final Map<String, Long> stock = new LinkedHashMap<>();

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** An idle aisle with every pattern, plenty of order slots and no bound in the way. */
    private ProductionPlanInput<String, String> input() {
        return ProductionPlanInput.of(AISLE, PlanBudget.ofMap(stock), PlanLimits.UNBOUNDED, 16);
    }

    /** An aisle whose order cap is {@code orderSlots}, all but {@code freeOrderSlots} of it already open. */
    private ProductionPlanInput<String, String> busy(int freeOrderSlots, int orderSlots) {
        return ProductionPlanInput.of(AISLE, PlanBudget.ofMap(stock), PlanLimits.UNBOUNDED, freeOrderSlots,
                orderSlots);
    }

    /** A craft fetch's lines, in the order its portions are requested: per key, what its line asks for. */
    @SafeVarargs
    private static LinkedHashMap<String, Long> lines(Map.Entry<String, Long>... entries) {
        LinkedHashMap<String, Long> lines = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : entries)
            lines.put(entry.getKey(), entry.getValue());
        return lines;
    }

    /** The chains a preview of {@code lines} plans, against {@code base}. */
    private static Map<String, ChainPreview<String>> chains(ProductionPlanInput<String, String> base,
            SequencedMap<String, Long> lines) {
        return SequentialPlans.preview(base, lines).chains();
    }

    /**
     * What the craft order's portions would have made, requested one after another in line order with nothing in
     * between — the controller's request path in miniature, written out without the ledger: each portion claims what
     * the racks still hold of its item, the planner plans the rest against what is left, and the chain's ingredients
     * are then spoken for. Per line that had something made, its chain's whole-run output.
     */
    private Map<String, Long> portions(SequencedMap<String, Long> lines) {
        Map<String, Long> left = new HashMap<>(stock);
        Map<String, Long> made = new LinkedHashMap<>();
        lines.forEach((key, need) -> {
            long claimed = Math.min(need, left.getOrDefault(key, 0L));
            left.merge(key, -claimed, Long::sum);
            if (need - claimed < 1L)
                return;
            ProductionPlan<String, String> plan = ProductionPlanner.plan(ProductionPlanInput.of(AISLE,
                    PlanBudget.ofMap(new HashMap<>(left)), PlanLimits.UNBOUNDED, 16), key, need - claimed)
                    .orElseThrow();
            plan.leafDemand().forEach((leaf, items) -> left.merge(leaf, -items, Long::sum));
            made.put(key, (long) plan.root().output());
        });
        return made;
    }

    private static Map.Entry<String, Long> of(String key, long amount) {
        return Map.entry(key, amount);
    }

    private static void accepted(ChainPreview<String> chain, long promised, long made) {
        assertTrue(chain.accepted(), () -> "refused with " + chain.refusal().orElse(null) + " about "
                + chain.about().orElse(null));
        assertEquals(promised, chain.promised(), "promised");
        assertEquals(made, chain.made(), "made in whole runs");
    }

    /** Asserts the refusal and, which is the point of a refusal, the item it names. */
    private static void refused(ChainPreview<String> chain, PlanRefusal reason, String about) {
        assertFalse(chain.accepted(), "expected a refusal");
        assertEquals(Optional.of(reason), chain.refusal());
        assertEquals(Optional.of(about), chain.about(), "a refusal has to name the item it is about");
        assertEquals(0L, chain.promised());
        assertEquals(0L, chain.made());
    }

    // --- one shared budget -------------------------------------------------------------------------------------------

    /**
     * Planks and charcoal are both made from logs. Three logs: the planks take two, the charcoal gets the one that is
     * left — planned on their own, each would have been promised every log in the racks.
     */
    @Test
    void twoShortKeysShareOneLogBudget() {
        stock.put(LOG, 3L);
        FetchPreview<String> fetch = SequentialPlans.preview(input(), lines(of(PLANK, 8L), of(CHARCOAL, 2L)));
        Map<String, ChainPreview<String>> previews = fetch.chains();
        assertEquals(List.of(PLANK, CHARCOAL), List.copyOf(previews.keySet()), "one preview per key, in grid order");
        assertEquals(Map.of(PLANK, 0L, CHARCOAL, 0L), fetch.taken(), "the racks hold neither");
        accepted(previews.get(PLANK), 8L, 8L);
        accepted(previews.get(CHARCOAL), 1L, 1L);

        // On its own, the charcoal chain would have been promised both logs it needs.
        ProductionPlan<String, String> alone = ProductionPlanner.plan(input(), CHARCOAL, 2L).orElseThrow();
        assertEquals(2L, alone.rootPromise(), "the budget is what the earlier chain left, not the racks");

        // The order is the grid's: whoever comes first is paid first.
        Map<String, ChainPreview<String>> reversed = chains(input(), lines(of(CHARCOAL, 2L), of(PLANK, 8L)));
        accepted(reversed.get(CHARCOAL), 2L, 2L);
        accepted(reversed.get(PLANK), 4L, 4L);
    }

    @Test
    void theLedgerAddsUpEveryChainsLeavesAndSteps() {
        stock.put(LOG, 3L);
        SequentialPlans<String, String> plans = SequentialPlans.over(input());
        accepted(plans.preview(PLANK, 8L), 8L, 8L);
        assertEquals(2L, plans.spent(LOG));
        assertEquals(1L, plans.available(LOG));
        assertEquals(1, plans.usedSlots());
        accepted(plans.preview(CHARCOAL, 2L), 1L, 1L);
        assertEquals(Map.of(LOG, 3L), plans.spentByKey());
        assertEquals(0L, plans.available(LOG));
        assertEquals(2, plans.usedSlots());
        assertEquals(14, plans.freeOrderSlots());
        // A third chain finds nothing left and names the raw material.
        refused(plans.preview(STICK, 4L), PlanRefusal.MISSING_INGREDIENT, LOG);
    }

    /**
     * A chest needs planks, the planks need logs: two orders and two logs. Sticks afterwards need planks again, which
     * are a step of their own (a plan is a tree), paid with what the chest left.
     */
    @Test
    void aDeeperChainSpendsItsLeavesAndAllItsSteps() {
        stock.put(LOG, 3L);
        SequentialPlans<String, String> plans = SequentialPlans.over(input());
        ProductionPlan<String, String> chest = plans.plan(CHEST, 1L).orElseThrow();
        assertEquals(List.of(PLANK, CHEST), chest.nodes().stream().map(PlanNode::result).toList());
        assertEquals(2, plans.usedSlots(), "the chest's chain opens two orders");
        assertEquals(2L, plans.spent(LOG));
        accepted(plans.preview(STICK, 4L), 4L, 4L);
        assertEquals(3L, plans.spent(LOG), "the sticks' planks are made from the last log");
        assertEquals(4, plans.usedSlots());
    }

    // --- each line takes from the racks at its own turn ------------------------------------------------------------

    /**
     * The grid takes two logs as logs, in a cell before the planks, and wants eight planks made: the logs' line takes
     * its two first, so the planks' chain gets the one log that is left and promises four planks, not eight.
     */
    @Test
    void anEarlierLinesTakeIsSpentBeforeALaterChain() {
        stock.put(LOG, 3L);
        FetchPreview<String> fetch = SequentialPlans.preview(input(), lines(of(LOG, 2L), of(PLANK, 8L)));
        assertEquals(Map.of(LOG, 2L, PLANK, 0L), fetch.taken());
        assertEquals(List.of(PLANK), List.copyOf(fetch.chains().keySet()), "the logs' line needs no chain");
        accepted(fetch.chains().get(PLANK), 4L, 4L);
        accepted(chains(input(), lines(of(PLANK, 8L))).get(PLANK), 8L, 8L);

        SequentialPlans<String, String> plans = SequentialPlans.over(input());
        assertEquals(2L, plans.take(LOG, 2L), "a line takes what it needs while the racks hold it");
        assertEquals(0L, plans.take(PLANK, 0L), "a line of nothing takes nothing");
        assertEquals(2L, plans.spent(LOG));
        assertEquals(0L, plans.spent(PLANK));
        assertEquals(1L, plans.available(LOG));
        assertEquals(1L, plans.take(LOG, 5L), "and never more than is left");
        assertEquals(0L, plans.take(LOG, 1L));
        assertEquals(0L, plans.take(LOG, -3L));

        // A line that takes every log leaves the chain none, and the refusal says which item that is.
        refused(chains(input(), lines(of(LOG, 3L), of(PLANK, 8L))).get(PLANK), PlanRefusal.MISSING_INGREDIENT, LOG);
    }

    /**
     * The same two lines the other way round: the planks come first, so their chain is paid with two of the three logs,
     * and the logs' line then finds one. Nothing here makes logs, so the rest is simply missing — the order is the
     * grid's, and it is the order the portions are requested in.
     */
    @Test
    void aLaterLineFindsWhatAnEarlierChainLeft() {
        stock.put(LOG, 3L);
        FetchPreview<String> fetch = SequentialPlans.preview(input(), lines(of(PLANK, 8L), of(LOG, 2L)));
        accepted(fetch.chains().get(PLANK), 8L, 8L);
        assertEquals(1L, fetch.taken().get(LOG), "the planks' chain used two of the three logs");
        refused(fetch.chains().get(LOG), PlanRefusal.NO_PATTERN, LOG);
    }

    /**
     * A fence gate is stick, plank, stick in two rows: four sticks and two planks, with the sticks' cell first. With
     * two planks and a log in the racks, the sticks' portion has its sticks made of the two planks, so the planks'
     * portion finds none and has its own made of the log. The preview walks the lines the same way and adds up both
     * chains — taking every line's stock first would have counted the two planks twice, and the order's second portion
     * would then ask for production its Yes did not cover.
     */
    @Test
    void theLinesAreWalkedTheWayThePortionsAreRequested() {
        stock.put(PLANK, 2L);
        stock.put(LOG, 1L);
        SequencedMap<String, Long> gate = lines(of(STICK, 4L), of(PLANK, 2L));
        FetchPreview<String> fetch = SequentialPlans.preview(input(), gate);
        assertEquals(Map.of(STICK, 0L, PLANK, 0L), fetch.taken(),
                "the planks' line finds the planks the sticks' chain already uses");
        accepted(fetch.chains().get(STICK), 4L, 4L);
        accepted(fetch.chains().get(PLANK), 2L, 4L);
        long made = fetch.chains().values().stream().mapToLong(ChainPreview::made).sum();
        assertEquals(Map.of(STICK, 4L, PLANK, 4L), portions(gate));
        assertEquals(portions(gate).values().stream().mapToLong(Long::longValue).sum(), made,
                "what a Yes agrees to have made is what the portions will make");

        // With the planks' cell first (an oak fence), the planks' line takes the two and the sticks' chain makes its
        // planks of the log: the same 1 log, but other steps — and again exactly what the portions meet.
        SequencedMap<String, Long> fence = lines(of(PLANK, 2L), of(STICK, 4L));
        FetchPreview<String> planksFirst = SequentialPlans.preview(input(), fence);
        assertEquals(2L, planksFirst.taken().get(PLANK));
        assertEquals(List.of(STICK), List.copyOf(planksFirst.chains().keySet()));
        accepted(planksFirst.chains().get(STICK), 4L, 4L);
        assertEquals(portions(fence), Map.of(STICK, 4L));
    }

    @Test
    void aTakeOfAKeyTheChainsDoNotUseChangesNothing() {
        stock.put(LOG, 3L);
        stock.put(CHARCOAL, 5L);
        FetchPreview<String> fetch = SequentialPlans.preview(input(), lines(of(CHARCOAL, 5L), of(PLANK, 8L)));
        assertEquals(5L, fetch.taken().get(CHARCOAL));
        assertFalse(fetch.chains().containsKey(CHARCOAL), "the racks serve the charcoal's line");
        accepted(fetch.chains().get(PLANK), 8L, 8L);
    }

    // --- the order slots ---------------------------------------------------------------------------------------------

    /**
     * One free order slot in a busy aisle: the planks chain takes it, and the charcoal chain is told the orders are
     * busy — waiting for one to finish would let it through.
     */
    @Test
    void theEarlierChainsUseUpTheOrderSlots() {
        stock.put(LOG, 10L);
        Map<String, ChainPreview<String>> previews = chains(busy(1, 12), lines(of(PLANK, 8L), of(CHARCOAL, 2L)));
        accepted(previews.get(PLANK), 8L, 8L);
        refused(previews.get(CHARCOAL), PlanRefusal.ORDERS_BUSY, CHARCOAL);
    }

    /**
     * An idle aisle that may run one order at all: it is the fetch's own first chain that takes the slot, so the second
     * one is busy as well, not too long for the configuration.
     */
    @Test
    void aSlotTakenByAnEarlierChainOfTheSameFetchIsBusyNotTooMany() {
        stock.put(LOG, 10L);
        Map<String, ChainPreview<String>> previews = chains(busy(1, 1), lines(of(PLANK, 8L), of(CHARCOAL, 2L)));
        accepted(previews.get(PLANK), 8L, 8L);
        refused(previews.get(CHARCOAL), PlanRefusal.ORDERS_BUSY, CHARCOAL);
    }

    @Test
    void aTwoStepChainNeedsTwoFreeSlots() {
        stock.put(LOG, 10L);
        Map<String, ChainPreview<String>> previews = chains(busy(2, 12), lines(of(CHARCOAL, 1L), of(CHEST, 1L)));
        accepted(previews.get(CHARCOAL), 1L, 1L);
        refused(previews.get(CHEST), PlanRefusal.ORDERS_BUSY, PLANK);
    }

    // --- every refusal names its item, and spends nothing ------------------------------------------------------------

    @Test
    void aRefusalNamesItsItem() {
        // No logs at all: the planks are not refused for planks, but for what is really missing.
        refused(chains(input(), lines(of(PLANK, 8L))).get(PLANK), PlanRefusal.MISSING_INGREDIENT, LOG);
        // Nothing in the aisle makes slime balls.
        ChainPreview<String> slime = chains(input(), lines(of(SLIME, 1L))).get(SLIME);
        refused(slime, PlanRefusal.NO_PATTERN, SLIME);
        assertFalse(slime.chainRefused(), "no chain was tried for an item nothing makes");
        // The warehouse has stopped making charcoal.
        stock.put(LOG, 10L);
        ChainPreview<String> stopped = chains(input().withPaused(Set.of(CHARCOAL)), lines(of(CHARCOAL, 2L)))
                .get(CHARCOAL);
        refused(stopped, PlanRefusal.PAUSED, CHARCOAL);
        assertTrue(stopped.chainRefused());
    }

    /** A refused chain leaves no trace in the ledger, so the next one is planned as if it had never been tried. */
    @Test
    void aRefusedChainSpendsNothing() {
        stock.put(LOG, 2L);
        SequentialPlans<String, String> plans = SequentialPlans.over(input().withPaused(Set.of(CHARCOAL)));
        refused(plans.preview(SLIME, 1L), PlanRefusal.NO_PATTERN, SLIME);
        refused(plans.preview(CHARCOAL, 2L), PlanRefusal.PAUSED, CHARCOAL);
        assertTrue(plans.spentByKey().isEmpty());
        assertEquals(0, plans.usedSlots());
        accepted(plans.preview(PLANK, 8L), 8L, 8L);
    }

    // --- one snapshot, never changed ---------------------------------------------------------------------------------

    @Test
    void theRacksAreAskedOncePerKeyHoweverManyChainsArePlanned() {
        Map<String, Integer> asked = new HashMap<>();
        PlanBudget<String> counting = PlanBudget.of(key -> {
            asked.merge(key, 1, Integer::sum);
            return LOG.equals(key) ? 10L : 0L;
        });
        ProductionPlanInput<String, String> base = ProductionPlanInput.of(AISLE, counting, PlanLimits.UNBOUNDED, 16);
        Map<String, ChainPreview<String>> previews = chains(base,
                lines(of(PLANK, 8L), of(CHARCOAL, 2L), of(STICK, 8L), of(CHEST, 1L), of(LOG, 1L)));
        assertEquals(4, previews.size(), "the logs' line is served by the racks");
        previews.values().forEach(chain -> assertTrue(chain.accepted()));
        assertEquals(1, (int) asked.get(LOG), "one snapshot for the whole fetch");
        assertEquals(1, (int) asked.get(PLANK));
    }

    @Test
    void theCallersInputIsNeverChanged() {
        stock.put(LOG, 3L);
        ProductionPlanInput<String, String> base = input();
        SequentialPlans.preview(base, lines(of(LOG, 1L), of(PLANK, 8L), of(CHARCOAL, 2L)));
        assertTrue(base.budget().nothingSpent());
        assertEquals(3L, ProductionPlanner.plan(base, CHARCOAL, 3L).orElseThrow().rootPromise(),
                "the same input plans against every log afterwards");
    }

    @Test
    void aLineTheRacksServeHasNoChainAndAnEmptyLineIsSkipped() {
        stock.put(LOG, 3L);
        FetchPreview<String> fetch = SequentialPlans.preview(input(),
                lines(of(PLANK, 0L), of(CHARCOAL, -4L), of(STICK, 4L), of(LOG, 1L)));
        assertEquals(List.of(STICK, LOG), List.copyOf(fetch.taken().keySet()), "every line asking for something");
        assertEquals(List.of(STICK), List.copyOf(fetch.chains().keySet()), "a chain only for what the racks lack");
        assertEquals(1L, fetch.taken().get(LOG));
        assertThrows(UnsupportedOperationException.class,
                () -> fetch.chains().put(PLANK, ChainPreview.accepted(1L, 1L)));
        assertThrows(UnsupportedOperationException.class, () -> fetch.taken().put(PLANK, 1L));
        assertThrows(IllegalArgumentException.class, () -> SequentialPlans.over(input()).plan(PLANK, 0L));
        assertEquals(FetchPreview.<String>none(), SequentialPlans.preview(input(), lines()),
                "no line, nothing previewed");
    }

    // --- the preview record ------------------------------------------------------------------------------------------

    @Test
    void aPreviewIsEitherAChainOrARefusal() {
        ChainPreview<String> chain = ChainPreview.accepted(5L, 8L);
        assertTrue(chain.accepted());
        assertFalse(chain.chainRefused());
        assertTrue(chain.refusal().isEmpty());
        assertTrue(chain.about().isEmpty());

        ChainPreview<String> missing = ChainPreview.refused(PlanRefusal.MISSING_INGREDIENT, LOG);
        assertTrue(missing.chainRefused());
        assertFalse(ChainPreview.refused(PlanRefusal.NO_PATTERN, SLIME).chainRefused());

        assertThrows(IllegalArgumentException.class, () -> ChainPreview.accepted(0L, 0L), "a chain promises something");
        assertThrows(IllegalArgumentException.class, () -> ChainPreview.accepted(8L, 5L),
                "whole runs make at least what is promised");
        assertThrows(IllegalArgumentException.class,
                () -> new ChainPreview<>(1L, 1L, Optional.of(PlanRefusal.PAUSED), Optional.of(PLANK)),
                "a refusal makes nothing");
        assertThrows(IllegalArgumentException.class,
                () -> new ChainPreview<>(1L, 1L, Optional.empty(), Optional.of(PLANK)),
                "an accepted chain is about no refused item");
    }

    @Test
    void aFetchPreviewKeepsItsLinesInOrderAndCannotBeChanged() {
        Map<String, Long> taken = new LinkedHashMap<>();
        taken.put(STICK, 0L);
        taken.put(PLANK, 2L);
        Map<String, ChainPreview<String>> chains = new LinkedHashMap<>();
        chains.put(STICK, ChainPreview.accepted(4L, 4L));
        FetchPreview<String> fetch = new FetchPreview<>(taken, chains);
        taken.put(LOG, 1L);
        chains.clear();
        assertEquals(List.of(STICK, PLANK), List.copyOf(fetch.taken().keySet()), "a copy, in line order");
        assertEquals(1, fetch.chains().size());
        assertTrue(new FetchPreview<String>(null, null).taken().isEmpty());
        assertEquals(FetchPreview.none(), new FetchPreview<>(Map.of(), Map.of()));
        Map<String, Long> withoutAmount = new HashMap<>();
        withoutAmount.put(GATE, null);
        assertThrows(NullPointerException.class, () -> new FetchPreview<>(withoutAmount, Map.of()));
    }

    @Test
    void aPreviewOfAPlanIsItsRootsPromiseAndWholeRuns() {
        stock.put(LOG, 10L);
        ProductionPlan<String, String> plan = ProductionPlanner.plan(input(), PLANK, 5L).orElseThrow();
        assertEquals(ChainPreview.accepted(5L, 8L), ChainPreview.of(plan),
                "two runs of four planks for five promised");
    }
}
