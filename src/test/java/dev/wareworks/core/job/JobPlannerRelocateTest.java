package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.StockIndex;
import dev.wareworks.core.warehouse.LocationKind;

/**
 * The relocation stage of {@link JobPlanner} (issue #46, ADR-079): a Warehouse Optimizer's items move from a low storage
 * priority to a strictly better location, only when nothing else is to be done, and a warehouse without an optimizer
 * order plans exactly as it did before.
 */
class JobPlannerRelocateTest {
    private static final String IRON = "iron";
    private static final String GOLD = "gold";
    private static final String LAVA_BUCKET = "lava_bucket";
    private static final int STACK = 64;
    private static final int TRANSFER_TICKS = 10;
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = RackPosition.of(0, 0, Side.LEFT);
    private static final RackPosition PRODUCTION = RackPosition.of(0, 1, Side.RIGHT);
    /** The overflow chest a player filled first: far away, priority 0. */
    private static final RackPosition OVERFLOW = RackPosition.of(9, 0, Side.LEFT);
    private static final RackPosition NEAR_OVERFLOW = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition RACK = RackPosition.of(3, 0, Side.LEFT);
    private static final RackPosition FAR_RACK = RackPosition.of(7, 0, Side.LEFT);
    private static final RackPosition BAY = RackPosition.of(2, 0, Side.LEFT);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());
    private final Map<RackPosition, Integer> extractable = new HashMap<>();
    private final Map<RackPosition, Integer> insertable = new HashMap<>();
    private final List<RackPosition> extractCalls = new ArrayList<>();
    private final List<RackPosition> insertCalls = new ArrayList<>();
    private final Map<RackPosition, Integer> priorities = new HashMap<>();
    /** Locations whose store filter selects exactly the listed keys. */
    private final Map<RackPosition, Set<String>> dedicated = new HashMap<>();
    private final Set<RackPosition> bays = new HashSet<>();

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1000};
        return () -> new UUID(0L, next[0]++);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static InventorySnapshot<String> slots(int emptySlots, Object... keyCounts) {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(emptySlots + keyCounts.length / 2);
        for (int i = 0; i < keyCounts.length; i += 2)
            builder.add((String) keyCounts[i], (Integer) keyCounts[i + 1], STACK, STACK);
        for (int i = 0; i < emptySlots; i++)
            builder.addEmpty(STACK);
        return builder.build();
    }

    private FilterMatch filterMatch(RackPosition location, String key) {
        Set<String> listed = dedicated.get(location);
        if (listed == null)
            return FilterMatch.UNFILTERED;
        return listed.contains(key) ? FilterMatch.DEDICATED : FilterMatch.REJECTED;
    }

    /** Puts {@code count} of {@code key} into {@code location}, one stack per slot, per index and live. */
    private void holds(RackPosition location, String key, int count) {
        List<Object> stacks = new ArrayList<>();
        for (int left = count; left > 0; left -= STACK) {
            stacks.add(key);
            stacks.add(Math.min(STACK, left));
        }
        stock.update(location, slots(27 - stacks.size() / 2, stacks.toArray()));
        extractable.put(location, count);
    }

    @SafeVarargs
    private PlannerInput.Builder<String, RackPosition> input(PlannerInput.RelocateNeed<String>... needs) {
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(0, 0)
                .speeds(SPEEDS)
                .transferTicks(TRANSFER_TICKS)
                .carryLimit(key -> key.equals(LAVA_BUCKET) ? 1 : STACK)
                .storageLocations(List.of(NEAR_OVERFLOW, BAY, RACK, FAR_RACK, OVERFLOW))
                .storePriority(location -> priorities.getOrDefault(location, 0))
                .storeFilter(this::filterMatch)
                .relocateTarget((location, key) -> !bays.contains(location))
                .liveExtract((location, key, max) -> {
                    extractCalls.add(location);
                    return Math.min(max, extractable.getOrDefault(location, 0));
                })
                .liveInsert((location, key, amount) -> {
                    insertCalls.add(location);
                    return Math.min(amount, insertable.getOrDefault(location, 0));
                })
                .relocations(List.of(needs));
    }

    private static PlannerInput.RelocateNeed<String> need(long id, String key, long movable) {
        return new PlannerInput.RelocateNeed<>(id(id), key, movable);
    }

    // --- the stage ---------------------------------------------------------------------------------------------------

    @Test
    void anOverflowIsMovedUpToABetterPlace() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30)).build());

        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(JobType.RELOCATE, job.type());
        assertEquals(OVERFLOW, job.source());
        assertEquals(RACK, job.target());
        assertEquals(LocationKind.STORAGE, job.targetKind());
        assertEquals(30, job.plannedAmount());
        assertEquals(Optional.empty(), job.requestId(), "a relocation serves nobody");
        assertEquals(Set.of(), result.reasons(), "a relocation adds no reason, not even NO_WORK");
        assertEquals(Set.of(), result.relocationsExhausted());
    }

    @Test
    void nothingMovesAtEqualPriority() {
        holds(OVERFLOW, IRON, 30);
        insertable.put(RACK, STACK);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30)).build());

        assertFalse(result.hasJob(), "a location of the same priority is no better place");
        assertEquals(Set.of(id(1)), result.relocationsExhausted(), "nothing fits anywhere better: the entry ends");
        assertEquals(Set.of(NoJobReason.NO_WORK), result.reasons(), "and the goggles say what they always said");
        assertEquals(List.of(), extractCalls, "no source had anything better to go to, so none was asked");
    }

    @Test
    void lowerPrioritiesAreNeverTargets() {
        holds(RACK, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(OVERFLOW, STACK);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30)).build());

        assertFalse(result.hasJob());
        assertEquals(List.of(), insertCalls, "the overflow below is never offered the rack's stock");
    }

    @Test
    void aDedicatedChestIsNotEmptiedIntoAGeneralRack() {
        holds(OVERFLOW, IRON, 30);
        dedicated.put(OVERFLOW, Set.of(IRON));
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        PlanResult<String, RackPosition> general = planner.plan(input(need(1, IRON, 30)).build());
        assertFalse(general.hasJob(), "every new iron would be stored back into its dedicated chest");
        assertEquals(Set.of(id(1)), general.relocationsExhausted());

        dedicated.put(RACK, Set.of(IRON));
        PlanResult<String, RackPosition> alsoDedicated = planner.plan(input(need(1, IRON, 30)).build());
        assertEquals(RACK, alsoDedicated.job().orElseThrow().job().target(),
                "a higher location dedicated to iron as well is a better place");
    }

    @Test
    void aSourceWhoseFilterRejectsItsOwnStockMayGoAnywhereBetter() {
        holds(OVERFLOW, IRON, 30);
        dedicated.put(OVERFLOW, Set.of(GOLD)); // re-dedicated while the iron was inside
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        assertEquals(RACK, planner.plan(input(need(1, IRON, 30)).build()).job().orElseThrow().job().target());
    }

    @Test
    void aRejectingTargetIsNeverChosen() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 9);
        dedicated.put(RACK, Set.of(GOLD));
        priorities.put(FAR_RACK, 5);
        insertable.put(RACK, STACK);
        insertable.put(FAR_RACK, STACK);

        assertEquals(FAR_RACK, planner.plan(input(need(1, IRON, 30)).build()).job().orElseThrow().job().target());
        assertFalse(insertCalls.contains(RACK), "a rack whose filter rejects iron costs no live call");
    }

    @Test
    void aFluidBayIsNeverARelocationTarget() {
        holds(OVERFLOW, LAVA_BUCKET, 4);
        bays.add(BAY);
        priorities.put(BAY, 9);
        priorities.put(RACK, 5);
        insertable.put(BAY, 1);
        insertable.put(RACK, STACK);

        TransportJob<String, RackPosition> job = planner.plan(input(need(1, LAVA_BUCKET, 4)).build()).job()
                .orElseThrow().job();
        assertEquals(RACK, job.target(), "the bay would drain the bucket: the shelf above the overflow takes it");
        assertEquals(1, job.plannedAmount(), "a lava bucket travels one per trip");
        assertFalse(insertCalls.contains(BAY));
    }

    @Test
    void theHighestPriorityAmongTheBetterPlacesWins() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 3);
        priorities.put(FAR_RACK, 6);
        insertable.put(RACK, STACK);
        insertable.put(FAR_RACK, STACK);

        assertEquals(FAR_RACK, planner.plan(input(need(1, IRON, 30)).build()).job().orElseThrow().job().target(),
                "the store ranking decides among the better places");
    }

    @Test
    void theMovableAmountAndTheCarryLimitBoundTheTrip() {
        holds(OVERFLOW, IRON, 200);
        priorities.put(RACK, 5);
        insertable.put(RACK, 1000);

        assertEquals(10, planner.plan(input(need(1, IRON, 10)).build()).job().orElseThrow().job().plannedAmount(),
                "only the free surplus moves");
        assertEquals(STACK, planner.plan(input(need(1, IRON, 150)).build()).job().orElseThrow().job()
                .plannedAmount(), "and one trip carries one stack");
    }

    @Test
    void reservedStockStaysWhereItIs() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);
        ledger.reserveStock(id(77), OVERFLOW, IRON, 25, id(78));

        assertEquals(5, planner.plan(input(need(1, IRON, 30)).build()).job().orElseThrow().job().plannedAmount(),
                "the 25 a retrieve reserved there are not taken");

        ledger.reserveStock(id(77), OVERFLOW, IRON, 30, id(78));
        PlanResult<String, RackPosition> all = planner.plan(input(need(1, IRON, 30)).build());
        assertFalse(all.hasJob(), "a source whose stock is all reserved is no source");
    }

    @Test
    void heldBackItemsStayInTheWorstPlaces() {
        holds(OVERFLOW, IRON, 32);
        holds(NEAR_OVERFLOW, IRON, 32);
        priorities.put(NEAR_OVERFLOW, 2);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        TransportJob<String, RackPosition> job = planner.plan(input(new PlannerInput.RelocateNeed<>(id(1), IRON, 40,
                40)).build()).job().orElseThrow().job();
        assertEquals(NEAR_OVERFLOW, job.source(), "the 40 spoken for are the priority-0 chest's 32 and 8 more");
        assertEquals(24, job.plannedAmount(), "so the priority-2 chest gives what is left beyond them");
        assertEquals(List.of(NEAR_OVERFLOW), extractCalls, "and the held-back chest is never asked");

        // The trip landed: the moved items still count, so the free surplus is as large as before, but the items
        // spoken for are now exactly what lies below, and nothing more moves.
        holds(NEAR_OVERFLOW, IRON, 8);
        holds(RACK, IRON, 24);
        PlanResult<String, RackPosition> after = planner.plan(input(new PlannerInput.RelocateNeed<>(id(1), IRON, 24,
                40)).build());
        assertFalse(after.hasJob(), "the spoken-for items are not carried up trip by trip");
        assertEquals(Set.of(id(1)), after.relocationsExhausted());
    }

    @Test
    void theLowestSourceIsEmptiedFirst() {
        holds(NEAR_OVERFLOW, IRON, 30);
        holds(OVERFLOW, IRON, 30);
        priorities.put(NEAR_OVERFLOW, 2);
        priorities.put(FAR_RACK, 5);
        insertable.put(FAR_RACK, STACK);

        TransportJob<String, RackPosition> job = planner.plan(input(need(1, IRON, 60)).build()).job().orElseThrow()
                .job();
        assertEquals(OVERFLOW, job.source(), "the priority-0 chest is emptied before the nearer priority-2 one");
        assertEquals(List.of(OVERFLOW), extractCalls);
    }

    @Test
    void aSourceThatGivesNothingFallsThroughToTheNext() {
        holds(OVERFLOW, IRON, 30);
        holds(NEAR_OVERFLOW, IRON, 30);
        extractable.put(OVERFLOW, 0); // the index is stale
        priorities.put(NEAR_OVERFLOW, 1);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        TransportJob<String, RackPosition> job = planner.plan(input(need(1, IRON, 60)).build()).job().orElseThrow()
                .job();
        assertEquals(NEAR_OVERFLOW, job.source());
        assertEquals(List.of(OVERFLOW, NEAR_OVERFLOW), extractCalls);
    }

    @Test
    void aFullTargetLeavesTheNeedExhausted() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, 0);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30)).build());
        assertFalse(result.hasJob());
        assertEquals(Set.of(id(1)), result.relocationsExhausted());
    }

    @Test
    void theNextNeedIsSearchedAndAnEarlierExhaustedOneIsReported() {
        holds(OVERFLOW, IRON, 30);
        holds(NEAR_OVERFLOW, GOLD, 10);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);
        dedicated.put(RACK, Set.of(GOLD));

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30), need(2, GOLD, 10)).build());
        assertEquals(GOLD, result.job().orElseThrow().job().key(), "iron fits nowhere better, gold does");
        assertEquals(Set.of(id(1)), result.relocationsExhausted());
    }

    @Test
    void aNeedWithNothingInStockIsExhausted() {
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30)).build());
        assertEquals(Set.of(id(1)), result.relocationsExhausted());
    }

    @Test
    void theBudgetIsRespectedAndACutShortSearchIsNotExhausted() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        PlanResult<String, RackPosition> one = planner.plan(input(need(1, IRON, 30)).liveSimulationBudget(1).build());
        assertFalse(one.hasJob(), "the extract spent the only call, the insert had none left");
        assertEquals(Set.of(), one.relocationsExhausted(), "a search the budget cut short is searched again");
        assertEquals(List.of(OVERFLOW), extractCalls);
        assertEquals(List.of(), insertCalls);

        PlanResult<String, RackPosition> none = planner.plan(input(need(1, IRON, 30)).liveSimulationBudget(0).build());
        assertFalse(none.hasJob());
        assertEquals(Set.of(), none.relocationsExhausted());
    }

    // --- every other stage first ---------------------------------------------------------------------------------

    @Test
    void aRequestWinsOverARelocation() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);
        insertable.put(OUTPUT, STACK);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30))
                .requests(List.of(new PlannerInput.OpenRequest<>(id(9), IRON, 5, OUTPUT))).build());
        assertEquals(JobType.RETRIEVE, result.job().orElseThrow().job().type());
        assertEquals(Set.of(), result.relocationsExhausted(), "the relocation stage never ran");
    }

    @Test
    void aSupplyWinsOverARelocation() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);
        insertable.put(PRODUCTION, STACK);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30))
                .supplies(List.of(new PlannerInput.SupplyNeed<>(id(9), IRON, 5, PRODUCTION))).build());
        assertEquals(JobType.SUPPLY, result.job().orElseThrow().job().type());
    }

    @Test
    void anArrivalWinsOverARelocation() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);
        extractable.put(INPUT, 8);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30)).inputs(List.of(INPUT))
                .inputBuffers(station -> slots(0, GOLD, 8)).build());
        assertEquals(JobType.STORE, result.job().orElseThrow().job().type());
    }

    @Test
    void aRelocationBesideAFullInputKeepsItsReasonAndTheCursor() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);
        dedicated.put(RACK, Set.of(IRON));
        dedicated.put(OVERFLOW, Set.of(IRON));
        dedicated.put(NEAR_OVERFLOW, Set.of(IRON));
        dedicated.put(FAR_RACK, Set.of(IRON));
        dedicated.put(BAY, Set.of(IRON));
        RackPosition second = RackPosition.of(0, 1, Side.RIGHT);

        PlanResult<String, RackPosition> result = planner.plan(input(need(1, IRON, 30))
                .inputs(List.of(INPUT, second)).inputCursor(1)
                .inputBuffers(station -> station.equals(INPUT) ? slots(0, GOLD, 8) : InventorySnapshot.empty())
                .build());
        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(JobType.RELOCATE, job.type(), "gold fits nowhere, so the crane tidies iron instead");
        assertTrue(result.reasons().contains(NoJobReason.NO_MATCHING_FILTER), "and the input's reason stays");
        assertEquals(1, result.nextArrivalCursor(), "a relocation never moves the arrival round robin");
    }

    @Test
    void withoutANeedNothingChanges() {
        holds(OVERFLOW, IRON, 30);
        priorities.put(RACK, 5);
        insertable.put(RACK, STACK);

        PlanResult<String, RackPosition> result = planner.plan(input().build());
        assertFalse(result.hasJob());
        assertEquals(Set.of(NoJobReason.NO_WORK), result.reasons());
        assertEquals(List.of(), extractCalls);
        assertEquals(List.of(), insertCalls);
    }

    // --- reroute -------------------------------------------------------------------------------------------------

    @Test
    void aRelocationRerouteStaysInStorageAndAvoidsBays() {
        bays.add(BAY);
        insertable.put(BAY, STACK);
        insertable.put(RACK, 0);
        insertable.put(OVERFLOW, STACK);
        insertable.put(OUTPUT, STACK);
        priorities.put(RACK, 5);
        priorities.put(BAY, 9);
        for (RackPosition location : List.of(NEAR_OVERFLOW, BAY, RACK, FAR_RACK, OVERFLOW))
            stock.update(location, slots(27));
        PlannerInput<String, RackPosition> in = input().outputs(List.of(OUTPUT)).crane(3, 0).build();

        RerouteTarget<RackPosition> target = planner.planReroute(in, LAVA_BUCKET, 1, JobType.RELOCATE, RACK)
                .orElseThrow();
        assertEquals(LocationKind.STORAGE, target.kind());
        assertFalse(target.location().equals(BAY), "a bay would drain the bucket");
        assertFalse(insertCalls.contains(BAY));
        assertFalse(insertCalls.contains(OUTPUT), "a relocated item never leaves the warehouse");

        insertable.put(OVERFLOW, 0);
        insertCalls.clear();
        assertEquals(Optional.empty(), planner.planReroute(in, LAVA_BUCKET, 1, JobType.RELOCATE, RACK),
                "nowhere in storage: the crane holds rather than deliver to an output");
        assertFalse(insertCalls.contains(OUTPUT));
    }

    @Test
    void aRelocationRerouteMayGoBackDownAndIntoARejectingLocation() {
        insertable.put(OVERFLOW, STACK);
        priorities.put(RACK, 5);
        dedicated.put(OVERFLOW, Set.of(GOLD));
        for (RackPosition location : List.of(NEAR_OVERFLOW, BAY, RACK, FAR_RACK, OVERFLOW))
            stock.update(location, slots(27));

        assertEquals(OVERFLOW, planner.planReroute(input().build(), IRON, 20, JobType.RELOCATE, RACK).orElseThrow()
                .location(), "back where it came from beats holding, even below and even re-dedicated");
    }
}
