package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class JobPlannerTest {
    private static final String IRON = "iron";
    private static final String DIAMOND = "diamond";
    private static final String SHULKER = "shulker";
    /** Keys of one item type with different components, written "type#components" (see {@code itemType}). */
    private static final String ITEM_TYPE_SEPARATOR = "#";
    private static final String WORN_SWORD = "sword#worn";
    private static final String NEW_SWORD = "sword#new";
    private static final int STACK = 64;
    private static final int CARRY = 64;
    private static final int TRANSFER_TICKS = 10;
    /** Four ticks per block on both axes. */
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    private static final RackPosition IN_A = RackPosition.of(0, 0, Side.RIGHT);
    private static final RackPosition IN_B = RackPosition.of(0, 1, Side.RIGHT);
    private static final RackPosition IN_C = RackPosition.of(0, 2, Side.RIGHT);
    private static final RackPosition OUT_A = RackPosition.of(0, 0, Side.LEFT);
    private static final RackPosition OUT_B = RackPosition.of(0, 1, Side.LEFT);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());
    private final Live live = new Live();
    /** Store filters: the keys a filtered location lists ({@link PlannerInput#storeFilter()}). */
    private final Map<RackPosition, Set<String>> storeFilters = new HashMap<>();
    /** Locations whose list is a <b>deny</b> list: they accept everything they do not list, but select nothing. */
    private final Set<RackPosition> denyLists = new HashSet<>();

    /** Simulated live inventories that record every call. */
    private static final class Live {
        final Map<RackPosition, Integer> extractable = new HashMap<>();
        final Map<RackPosition, Integer> insertable = new HashMap<>();
        final Set<String> rejectedEverywhere = new HashSet<>();
        final List<RackPosition> extractCalls = new ArrayList<>();
        final List<RackPosition> insertCalls = new ArrayList<>();

        int simulateExtract(RackPosition location, String key, int maxAmount) {
            extractCalls.add(location);
            return Math.min(maxAmount, extractable.getOrDefault(location, 0));
        }

        int simulateInsert(RackPosition location, String key, int amount) {
            insertCalls.add(location);
            if (rejectedEverywhere.contains(key))
                return 0;
            return Math.min(amount, insertable.getOrDefault(location, 0));
        }
    }

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1000};
        return () -> new UUID(0L, next[0]++);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static RackPosition rack(int x, int y, Side side) {
        return RackPosition.of(x, y, side);
    }

    /** A snapshot with the given key/count slots (one stack limit each) followed by empty slots. */
    private static InventorySnapshot<String> slots(int emptySlots, Object... keyCounts) {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(emptySlots + keyCounts.length / 2);
        for (int i = 0; i < keyCounts.length; i += 2)
            builder.add((String) keyCounts[i], (Integer) keyCounts[i + 1], STACK, STACK);
        for (int i = 0; i < emptySlots; i++)
            builder.addEmpty(STACK);
        return builder.build();
    }

    private static PlannerInput.OpenRequest<String, RackPosition> request(UUID id, String key, int remaining,
            RackPosition output) {
        return new PlannerInput.OpenRequest<>(id, key, remaining, output);
    }

    private PlannerInput.Builder<String, RackPosition> input() {
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(0, 0)
                .speeds(SPEEDS)
                .transferTicks(TRANSFER_TICKS)
                .carryLimit(key -> CARRY)
                .liveExtract(live::simulateExtract)
                .liveInsert(live::simulateInsert)
                .storeFilter(this::filterMatch);
    }

    /** Gives {@code location} an allow-list store filter that selects exactly {@code accepted} for storing. */
    private void filter(RackPosition location, String... accepted) {
        storeFilters.put(location, Set.of(accepted));
    }

    /**
     * Gives {@code location} a <b>deny</b>-list store filter: it accepts everything except {@code denied}, which is
     * what Create's list and attribute filters answer in deny mode, and is not a dedication to anything.
     */
    private void denyFilter(RackPosition location, String... denied) {
        storeFilters.put(location, Set.of(denied));
        denyLists.add(location);
    }

    private FilterMatch filterMatch(RackPosition location, String key) {
        Set<String> listed = storeFilters.get(location);
        if (listed == null)
            return FilterMatch.UNFILTERED;
        if (denyLists.contains(location))
            return listed.contains(key) ? FilterMatch.REJECTED : FilterMatch.ALLOWED;
        return listed.contains(key) ? FilterMatch.DEDICATED : FilterMatch.REJECTED;
    }

    // --- general -------------------------------------------------------------------------------------------------

    @Test
    void noWorkWithoutRequestsOrBufferedItems() {
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A)).build());
        assertFalse(result.hasJob());
        assertEquals(Set.of(NoJobReason.NO_WORK), result.reasons());
        assertEquals(Optional.of(NoJobReason.NO_WORK), result.primaryReason());
        assertEquals(0, result.nextInputCursor());
        assertEquals(List.of(), live.insertCalls);
    }

    @Test
    void locationsCanBeAnyType() {
        Map<String, RackPosition> where = Map.of("input", rack(0, 0, Side.RIGHT), "chest", rack(3, 0, Side.LEFT));
        StockIndex<String, String> index = new StockIndex<>();
        index.update("chest", slots(27));
        JobPlanner<String, String> stringPlanner = new JobPlanner<>(where::get, sequentialIds());
        PlannerInput<String, String> in = PlannerInput.builder(index.readOnlyView(),
                        new ReservationLedger<String, String>().readOnlyView())
                .speeds(SPEEDS).carryLimit(key -> CARRY)
                .inputs(List.of("input")).storageLocations(List.of("chest"))
                .inputBuffers(location -> slots(0, IRON, 3))
                .liveInsert((location, key, amount) -> amount)
                .build();
        TransportJob<String, String> job = stringPlanner.plan(in).job().orElseThrow().job();
        assertEquals(TransportJob.store(job.id(), "input", "chest", IRON, 3), job);
    }

    // --- retrieve ------------------------------------------------------------------------------------------------

    @Test
    void retrieveServesTheOldestRequestFromTheFastestSource() {
        RackPosition far = rack(8, 0, Side.LEFT);
        RackPosition near = rack(2, 0, Side.LEFT);
        stock.update(far, slots(0, DIAMOND, 10, IRON, 30));
        stock.update(near, slots(0, DIAMOND, 10, IRON, 30));
        live.extractable.put(far, 40);
        live.extractable.put(near, 40);
        live.insertable.put(OUT_A, STACK);
        UUID older = id(1);
        PlanResult<String, RackPosition> result = planner.plan(input()
                .requests(List.of(request(older, DIAMOND, 5, OUT_A), request(id(2), IRON, 7, OUT_A)))
                .build());
        PlannedJob<String, RackPosition> planned = result.job().orElseThrow();
        assertEquals(TransportJob.retrieve(planned.job().id(), near, OUT_A, DIAMOND, 5, older), planned.job());
        assertEquals(TravelTimeModel.tripTicks(SPEEDS, TRANSFER_TICKS, 0, 0, 2, 0, 0, 0), planned.estimatedTicks());
        assertEquals(Set.of(), result.reasons());
        assertEquals(List.of(OUT_A), live.insertCalls, "the output is checked for at least one item");
        assertEquals(List.of(near), live.extractCalls, "the far source is never asked");
    }

    @Test
    void retrieveRankingIncludesTheWayToTheOutput() {
        RackPosition first = rack(2, 0, Side.LEFT);
        RackPosition second = rack(8, 0, Side.LEFT);
        RackPosition output = rack(10, 0, Side.RIGHT);
        stock.update(first, slots(0, DIAMOND, 10));
        stock.update(second, slots(0, DIAMOND, 10));
        live.extractable.put(first, 10);
        live.extractable.put(second, 10);
        live.insertable.put(output, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input().crane(10, 0)
                .requests(List.of(request(id(1), DIAMOND, 3, output))).build());
        assertEquals(second, result.job().orElseThrow().job().source(), "crane → 8 → 10 beats crane → 2 → 10");
    }

    @Test
    void retrieveAmountIsLimitedByCarryLimitAndReservedStock() {
        RackPosition source = rack(1, 0, Side.LEFT);
        stock.update(source, slots(0, IRON, 64, IRON, 36));
        live.extractable.put(source, 100);
        live.insertable.put(OUT_A, STACK);
        List<PlannerInput.OpenRequest<String, RackPosition>> requests = List.of(request(id(1), IRON, 90, OUT_A));
        assertEquals(CARRY, planner.plan(input().requests(requests).build()).job().orElseThrow().job().plannedAmount());

        ledger.reserveStock(id(50), source, IRON, 40);
        live.extractCalls.clear();
        assertEquals(60, planner.plan(input().requests(requests).build()).job().orElseThrow().job().plannedAmount(),
                "min(90, 64, 100 - 40)");
        assertEquals(List.of(source), live.extractCalls);

        ledger.reserveStock(id(50), source, IRON, 100);
        PlanResult<String, RackPosition> fullyReserved = planner.plan(input().requests(requests).build());
        assertFalse(fullyReserved.hasJob());
        assertEquals(Set.of(NoJobReason.NOT_IN_STOCK), fullyReserved.reasons());
    }

    @Test
    void retrieveFallsThroughWhenTheLiveInventoryHasLess() {
        RackPosition near = rack(1, 0, Side.LEFT);
        RackPosition far = rack(5, 0, Side.LEFT);
        stock.update(near, slots(0, DIAMOND, 10));
        stock.update(far, slots(0, DIAMOND, 10));
        live.extractable.put(near, 0); // taken by a player since the last snapshot
        live.extractable.put(far, 4);
        live.insertable.put(OUT_A, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input()
                .requests(List.of(request(id(1), DIAMOND, 8, OUT_A))).build());
        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(far, job.source());
        assertEquals(4, job.plannedAmount());
        assertEquals(List.of(near, far), live.extractCalls);
    }

    @Test
    void retrieveSkipsUnavailableSources() {
        RackPosition unloaded = rack(1, 0, Side.LEFT);
        RackPosition loaded = rack(6, 0, Side.LEFT);
        stock.update(unloaded, slots(0, DIAMOND, 10));
        stock.update(loaded, slots(0, DIAMOND, 10));
        live.extractable.put(unloaded, 10);
        live.extractable.put(loaded, 10);
        live.insertable.put(OUT_A, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input().available(location -> !location.equals(unloaded))
                .requests(List.of(request(id(1), DIAMOND, 2, OUT_A))).build());
        assertEquals(loaded, result.job().orElseThrow().job().source());
        assertFalse(live.extractCalls.contains(unloaded));
    }

    @Test
    void coveredRequestsAreSkipped() {
        RackPosition source = rack(1, 0, Side.LEFT);
        stock.update(source, slots(0, DIAMOND, 50));
        live.extractable.put(source, 50);
        live.insertable.put(OUT_A, STACK);
        UUID request = id(1);
        ledger.reserveStock(id(60), source, DIAMOND, 12, request);
        PlanResult<String, RackPosition> partly = planner.plan(input()
                .requests(List.of(request(request, DIAMOND, 20, OUT_A))).build());
        assertEquals(8, partly.job().orElseThrow().job().plannedAmount(), "20 remaining, 12 covered by a job");

        ledger.reserveTransit(id(61), OUT_A, DIAMOND, 8, request);
        PlanResult<String, RackPosition> covered = planner.plan(input()
                .requests(List.of(request(request, DIAMOND, 20, OUT_A))).build());
        assertFalse(covered.hasJob());
        assertEquals(Set.of(NoJobReason.NO_WORK), covered.reasons());
    }

    @Test
    void aFullOutputDoesNotBlockYoungerRequests() {
        RackPosition source = rack(1, 0, Side.LEFT);
        stock.update(source, slots(0, DIAMOND, 50));
        live.extractable.put(source, 50);
        live.insertable.put(OUT_A, 0);
        live.insertable.put(OUT_B, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input().requests(List.of(
                request(id(1), DIAMOND, 5, OUT_A), request(id(2), DIAMOND, 6, OUT_B))).build());
        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(OUT_B, job.target());
        assertEquals(Optional.of(id(2)), job.requestId());
        assertEquals(Set.of(NoJobReason.OUTPUT_FULL), result.reasons());
    }

    @Test
    void unavailableOutputsAreReported() {
        PlanResult<String, RackPosition> result = planner.plan(input().available(location -> !location.equals(OUT_A))
                .requests(List.of(request(id(1), DIAMOND, 5, OUT_A))).build());
        assertEquals(Set.of(NoJobReason.LOCATION_UNAVAILABLE), result.reasons());
        assertEquals(List.of(), live.insertCalls);
    }

    @Test
    void storeRunsWhenNoRequestCanBeServed() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(OUT_A, STACK);
        live.insertable.put(chest, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input()
                .requests(List.of(request(id(1), DIAMOND, 5, OUT_A)))
                .storageLocations(List.of(chest)).inputs(List.of(IN_A))
                .inputBuffers(location -> slots(0, IRON, 12)).build());
        assertEquals(TransportJob.store(result.job().orElseThrow().job().id(), IN_A, chest, IRON, 12),
                result.job().get().job());
        assertEquals(Set.of(NoJobReason.NOT_IN_STOCK), result.reasons());
        assertEquals(NoJobReason.NOT_IN_STOCK, result.primaryReason().orElseThrow());
    }

    // --- store ---------------------------------------------------------------------------------------------------

    @Test
    void storePrefersConsolidationThenTravelTime() {
        RackPosition near = rack(1, 0, Side.LEFT);
        RackPosition nearOtherSide = rack(1, 0, Side.RIGHT);
        RackPosition far = rack(6, 0, Side.LEFT);
        stock.update(near, slots(27));
        stock.update(nearOtherSide, slots(27));
        stock.update(far, slots(26, IRON, 10));
        for (RackPosition location : List.of(near, nearOtherSide, far))
            live.insertable.put(location, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(nearOtherSide, near, far))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        PlannedJob<String, RackPosition> iron = planner.plan(base.inputBuffers(location -> slots(0, IRON, 20)).build())
                .job().orElseThrow();
        assertEquals(far, iron.job().target(), "the location already holding iron wins over nearer empty ones");
        assertEquals(20, iron.job().plannedAmount());
        assertEquals(TravelTimeModel.tripTicks(SPEEDS, TRANSFER_TICKS, 0, 0, 0, 0, 6, 0), iron.estimatedTicks());

        PlannedJob<String, RackPosition> diamond = planner.plan(base.inputBuffers(location -> slots(0, DIAMOND, 5))
                .build()).job().orElseThrow();
        assertEquals(nearOtherSide, diamond.job().target(), "equal travel time: list order decides");
    }

    @Test
    void storePrefersLocationsWithoutOtherItemTypes() {
        RackPosition nearMixed = rack(1, 0, Side.LEFT);
        RackPosition middleSameType = rack(3, 0, Side.LEFT);
        RackPosition farEmpty = rack(6, 0, Side.LEFT);
        stock.update(nearMixed, slots(26, DIAMOND, 10));
        stock.update(middleSameType, slots(26, WORN_SWORD, 1));
        stock.update(farEmpty, slots(27));
        for (RackPosition location : List.of(nearMixed, middleSameType, farEmpty))
            live.insertable.put(location, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(nearMixed, middleSameType, farEmpty))
                .itemType(key -> key.split(ITEM_TYPE_SEPARATOR, 2)[0])
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        assertEquals(farEmpty, storeTarget(base, IRON), "a new item type gets an empty location of its own");
        assertEquals(middleSameType, storeTarget(base, NEW_SWORD),
                "the same item type with other components joins its location (nearer than the empty one)");
        assertEquals(nearMixed, storeTarget(base, DIAMOND), "consolidation still comes first");

        live.insertable.put(farEmpty, 0);
        assertEquals(nearMixed, storeTarget(base, IRON), "a location with other item types when no other one accepts");

        live.insertable.put(farEmpty, STACK);
        assertEquals(farEmpty, storeTarget(base.itemType(key -> key), NEW_SWORD),
                "without an item type function every key is its own type");
    }

    /** The target of the store job planned for 8 buffered items of {@code key}. */
    private RackPosition storeTarget(PlannerInput.Builder<String, RackPosition> base, String key) {
        return planner.plan(base.inputBuffers(location -> slots(0, key, 8)).build()).job().orElseThrow().job().target();
    }

    @Test
    void storeRankingIncludesTheWayFromTheCraneToTheInput() {
        RackPosition left = rack(2, 0, Side.LEFT);
        RackPosition right = rack(8, 0, Side.LEFT);
        stock.update(left, slots(27));
        stock.update(right, slots(27));
        live.insertable.put(left, STACK);
        live.insertable.put(right, STACK);
        RackPosition input = rack(9, 0, Side.RIGHT);
        PlanResult<String, RackPosition> result = planner.plan(input().crane(0, 0).inputs(List.of(input))
                .storageLocations(List.of(left, right)).inputBuffers(location -> slots(0, IRON, 1)).build());
        assertEquals(right, result.job().orElseThrow().job().target(), "ranked from the input, not from the crane");
    }

    @Test
    void storeAmountIsLimitedByBufferCarryAndReservedCapacity() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, 50);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 64, IRON, 36));
        assertEquals(50, planner.plan(base.build()).job().orElseThrow().job().plannedAmount(), "min(100, 64, 50)");

        ledger.reserveCapacity(id(70), chest, DIAMOND, 10);
        assertEquals(40, planner.plan(base.build()).job().orElseThrow().job().plannedAmount(),
                "reserved capacity of any key is subtracted");

        assertEquals(20, planner.plan(base.carryLimit(key -> 20).build()).job().orElseThrow().job().plannedAmount());
    }

    @Test
    void storeFallsThroughWhenTheLiveInventoryRejects() {
        RackPosition shulkerBox = rack(1, 0, Side.LEFT);
        RackPosition chest = rack(4, 0, Side.LEFT);
        stock.update(shulkerBox, slots(26, SHULKER, 1));
        stock.update(chest, slots(27));
        live.insertable.put(shulkerBox, 0); // a shulker box rejects shulker boxes, the estimate does not know
        live.insertable.put(chest, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A))
                .storageLocations(List.of(shulkerBox, chest)).inputBuffers(location -> slots(0, SHULKER, 1)).build());
        assertEquals(chest, result.job().orElseThrow().job().target());
        assertEquals(List.of(shulkerBox, chest), live.insertCalls);
    }

    @Test
    void storeSkipsLocationsWhoseEstimateIsUsedUp() {
        RackPosition full = rack(1, 0, Side.LEFT);
        RackPosition reserved = rack(2, 0, Side.LEFT);
        RackPosition free = rack(5, 0, Side.LEFT);
        stock.update(full, slots(0, IRON, 64));
        stock.update(reserved, slots(0, IRON, 54));
        stock.update(free, slots(1));
        for (RackPosition location : List.of(full, reserved, free))
            live.insertable.put(location, STACK);
        ledger.reserveCapacity(id(80), reserved, IRON, 10);
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A))
                .storageLocations(List.of(full, reserved, free))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                .inputBuffers(location -> slots(0, IRON, 3)).build());
        assertEquals(free, result.job().orElseThrow().job().target());
        assertEquals(List.of(free), live.insertCalls, "estimates filter before any live call");
    }

    @Test
    void warehouseFullWhenNothingAccepts() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A, IN_B)).inputCursor(1)
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 3)).build());
        assertFalse(result.hasJob());
        assertEquals(Set.of(NoJobReason.WAREHOUSE_FULL), result.reasons());
        assertEquals(1, result.nextInputCursor(), "the cursor stays when nothing was planned");
    }

    @Test
    void storeRoundRobinsOverInputsWithItems() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        Map<RackPosition, InventorySnapshot<String>> buffers = Map.of(IN_A, slots(0, IRON, 1), IN_B, slots(9),
                IN_C, slots(0, DIAMOND, 2));
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A, IN_B, IN_C))
                .storageLocations(List.of(chest)).inputBuffers(buffers::get);

        PlanResult<String, RackPosition> first = planner.plan(base.inputCursor(0).build());
        assertEquals(IN_A, first.job().orElseThrow().job().source());
        assertEquals(1, first.nextInputCursor());
        PlanResult<String, RackPosition> second = planner.plan(base.inputCursor(first.nextInputCursor()).build());
        assertEquals(IN_C, second.job().orElseThrow().job().source(), "the empty input is skipped");
        assertEquals(0, second.nextInputCursor());
        assertEquals(IN_C, planner.plan(base.inputCursor(-1).build()).job().orElseThrow().job().source(),
                "cursors are taken modulo the input count");
        assertEquals(IN_A, planner.plan(base.inputCursor(6).build()).job().orElseThrow().job().source());
    }

    @Test
    void anUnstorableItemDoesNotBlockTheOthers() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.rejectedEverywhere.add(SHULKER);
        PlanResult<String, RackPosition> sameInput = planner.plan(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, SHULKER, 1, IRON, 5)).build());
        assertEquals(IRON, sameInput.job().orElseThrow().job().key(), "the next item type of the same input");
        assertEquals(Set.of(), sameInput.reasons());

        Map<RackPosition, InventorySnapshot<String>> buffers = Map.of(IN_A, slots(0, SHULKER, 1), IN_B,
                slots(0, IRON, 5));
        PlanResult<String, RackPosition> nextInput = planner.plan(input().inputs(List.of(IN_A, IN_B))
                .storageLocations(List.of(chest)).inputBuffers(buffers::get).build());
        assertEquals(IN_B, nextInput.job().orElseThrow().job().source());
        assertEquals(Set.of(NoJobReason.WAREHOUSE_FULL), nextInput.reasons());
        assertEquals(0, nextInput.nextInputCursor());
    }

    @Test
    void liveSimulationsAreBudgeted() {
        List<RackPosition> chests = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            RackPosition chest = rack(x, 0, Side.LEFT);
            stock.update(chest, slots(27));
            chests.add(chest);
        }
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A, IN_B))
                .storageLocations(chests).inputBuffers(location -> slots(0, IRON, 5)).liveSimulationBudget(3).build());
        assertFalse(result.hasJob());
        assertEquals(3, live.insertCalls.size());
        assertTrue(result.reasons().contains(NoJobReason.BUDGET_EXHAUSTED));
        assertEquals(1, result.nextInputCursor(), "the next run starts at the next input");
        assertThrows(IllegalArgumentException.class, () -> input().liveSimulationBudget(-1).build());
    }

    /**
     * Review fix: more refusing locations than the budget, ranked ahead of the one that accepts, stalled a single input
     * forever (every run spent its budget on the same refusals). Remembered refusals are skipped without a live call.
     */
    @Test
    void rememberedRefusalsLetExhaustedRunsMakeProgress() {
        int budget = 8;
        List<RackPosition> shulkerBoxes = new ArrayList<>();
        for (int x = 1; x <= budget + 3; x++) {
            RackPosition box = rack(x, 0, Side.LEFT);
            stock.update(box, slots(27));
            shulkerBoxes.add(box);
        }
        RackPosition chest = rack(20, 0, Side.LEFT);
        stock.update(chest, slots(26, DIAMOND, 5));
        List<RackPosition> storage = new ArrayList<>(shulkerBoxes);
        storage.add(chest);
        RefusalMemory<String, RackPosition> refusals = new RefusalMemory<>(100, 1000);
        long now = 0;
        JobPlanner.LiveInsert<String, RackPosition> boxesRejectShulkers = (location, key, amount) -> {
            live.insertCalls.add(location);
            int accepted = key.equals(SHULKER) && !location.equals(chest) ? 0 : amount;
            if (accepted == 0)
                refusals.record(location, key, now);
            return accepted;
        };
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A)).storageLocations(storage)
                .inputBuffers(location -> slots(0, SHULKER, 1, IRON, 5)).liveInsert(boxesRejectShulkers)
                .liveSimulationBudget(budget);

        for (int run = 0; run < 2; run++) {
            live.insertCalls.clear();
            PlanResult<String, RackPosition> stalled = planner.plan(base.build());
            assertFalse(stalled.hasJob(), "without the memory every run spends its budget on the same refusals");
            assertTrue(stalled.reasons().contains(NoJobReason.BUDGET_EXHAUSTED));
            assertEquals(shulkerBoxes.subList(0, budget), live.insertCalls);
        }

        live.insertCalls.clear();
        PlanResult<String, RackPosition> remembered = planner.plan(
                base.insertRefused((location, key) -> refusals.isRefused(location, key, now)).build());
        TransportJob<String, RackPosition> job = remembered.job().orElseThrow().job();
        assertEquals(SHULKER, job.key());
        assertEquals(chest, job.target(), "past the refusing boxes");
        assertEquals(List.of(shulkerBoxes.get(budget), shulkerBoxes.get(budget + 1), shulkerBoxes.get(budget + 2), chest),
                live.insertCalls, "remembered refusals cost no live call");
    }

    @Test
    void rememberedExtractRefusalsAreSkipped() {
        RackPosition locked = rack(1, 0, Side.LEFT);
        RackPosition open = rack(6, 0, Side.LEFT);
        stock.update(locked, slots(0, DIAMOND, 10));
        stock.update(open, slots(0, DIAMOND, 10));
        live.extractable.put(locked, 0);
        live.extractable.put(open, 10);
        live.insertable.put(OUT_A, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input()
                .requests(List.of(request(id(1), DIAMOND, 5, OUT_A)))
                .extractRefused((location, key) -> location.equals(locked) && key.equals(DIAMOND)).build());
        assertEquals(open, result.job().orElseThrow().job().source());
        assertEquals(List.of(open), live.extractCalls, "the remembered refusal costs no live call");
    }

    @Test
    void insertEstimateFromSnapshots() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        RackPosition restored = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(1, IRON, 60));
        stock.restore(restored, Map.of(IRON, 5L));
        JobPlanner.InsertEstimate<String, RackPosition> estimate = JobPlanner.InsertEstimate
                .fromSnapshots(stock.readOnlyView(), key -> STACK);
        assertEquals(68, estimate.estimateInsertable(chest, IRON));
        assertEquals(64, estimate.estimateInsertable(chest, DIAMOND));
        assertEquals(JobPlanner.UNKNOWN_CAPACITY, estimate.estimateInsertable(restored, IRON));
    }

    // --- store filters (M8, ADR-021) -----------------------------------------------------------------------------

    /**
     * A location whose filter explicitly accepts the item ranks above every unfiltered one, so a dedicated chest fills
     * before general storage — even before a nearer location that already holds the item. For another item the same
     * location is skipped without a live call.
     */
    @Test
    void storePrefersADedicatedLocationOverUnfilteredOnes() {
        RackPosition nearGeneral = rack(1, 0, Side.LEFT);
        RackPosition nearHoldingIron = rack(2, 0, Side.LEFT);
        RackPosition farDedicated = rack(9, 0, Side.LEFT);
        stock.update(nearGeneral, slots(27));
        stock.update(nearHoldingIron, slots(26, IRON, 10));
        stock.update(farDedicated, slots(27));
        for (RackPosition location : List.of(nearGeneral, nearHoldingIron, farDedicated))
            live.insertable.put(location, STACK);
        filter(farDedicated, IRON);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(nearGeneral, nearHoldingIron, farDedicated))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        assertEquals(farDedicated, storeTarget(base, IRON),
                "the dedicated chest wins over a nearer one that already holds the iron");
        assertEquals(List.of(farDedicated), live.insertCalls, "and it is the first candidate simulated");

        live.insertCalls.clear();
        assertEquals(nearGeneral, storeTarget(base, DIAMOND), "another item goes to an unfiltered location");
        assertFalse(live.insertCalls.contains(farDedicated), "a rejected location is never simulated");
    }

    /**
     * Rejected locations are skipped <b>before</b> the capacity estimate and before any live call, so they consume
     * neither the live simulation budget nor (in the content layer) the refusal memory: far more rejecting locations
     * than the budget allows still leave the one accepting location reachable in the same run.
     */
    @Test
    void filteredOutLocationsCostNoLiveCallAndNoBudget() {
        List<RackPosition> dedicatedToDiamonds = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            RackPosition chest = rack(x, 0, Side.LEFT);
            stock.update(chest, slots(27));
            live.insertable.put(chest, STACK);
            filter(chest, DIAMOND);
            dedicatedToDiamonds.add(chest);
        }
        RackPosition general = rack(12, 0, Side.LEFT);
        stock.update(general, slots(27));
        live.insertable.put(general, STACK);
        List<RackPosition> storage = new ArrayList<>(dedicatedToDiamonds);
        storage.add(general);

        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A)).storageLocations(storage)
                .inputBuffers(location -> slots(0, IRON, 5)).liveSimulationBudget(2).build());
        assertEquals(general, result.job().orElseThrow().job().target());
        assertEquals(List.of(general), live.insertCalls, "only the location that may take the item is simulated");
        assertFalse(result.reasons().contains(NoJobReason.BUDGET_EXHAUSTED),
                "ten rejecting locations must not exhaust a budget of two");
    }

    /** Filters restrict storing only: what is already inside stays retrievable when the filter no longer matches it. */
    @Test
    void retrievalIgnoresStoreFilters() {
        RackPosition source = rack(1, 0, Side.LEFT);
        stock.update(source, slots(0, DIAMOND, 10));
        live.extractable.put(source, 10);
        live.insertable.put(OUT_A, STACK);
        filter(source, IRON); // the filter was changed after the diamonds were stored

        PlanResult<String, RackPosition> result = planner.plan(input()
                .requests(List.of(request(id(1), DIAMOND, 5, OUT_A))).build());
        assertEquals(source, result.job().orElseThrow().job().source(), "stored items are always retrievable");
        assertEquals(5, result.job().orElseThrow().job().plannedAmount());
    }

    /**
     * A deny list ("this chest takes anything but iron") accepts an item without selecting it, so it must not outrank
     * consolidation, item-type grouping and travel time the way a real dedication does — one such chest would
     * otherwise win for <b>every</b> item in the warehouse and fill with a mix of everything.
     */
    @Test
    void aDenyListAcceptsWithoutOutrankingConsolidation() {
        RackPosition nearDenying = rack(1, 0, Side.LEFT);
        RackPosition farHoldingIron = rack(8, 0, Side.LEFT);
        stock.update(nearDenying, slots(27));
        stock.update(farHoldingIron, slots(26, IRON, 10));
        for (RackPosition location : List.of(nearDenying, farHoldingIron))
            live.insertable.put(location, STACK);
        denyFilter(nearDenying, DIAMOND); // "anything but diamonds"
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(nearDenying, farHoldingIron))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        assertEquals(farHoldingIron, storeTarget(base, IRON),
                "a deny list that merely does not exclude the item is no dedication: consolidation still wins");
        live.insertCalls.clear();
        assertEquals(farHoldingIron, storeTarget(base, DIAMOND),
                "and what the deny list does exclude never enters it, although it is the nearest location");
        assertFalse(live.insertCalls.contains(nearDenying), "a denied item costs no live call");

        // The same chest with an allow list for iron does outrank the location that already holds iron.
        filter(nearDenying, IRON);
        denyLists.remove(nearDenying);
        assertEquals(nearDenying, storeTarget(base, IRON), "an allow list is a dedication and ranks first");
    }

    /**
     * Review fix: one input whose items no filter accepts is not the same problem as a full warehouse — a retrieval
     * frees space but never makes a filter match — so it gets its own reason.
     */
    @Test
    void anItemNoFilterAcceptsIsReportedSeparatelyFromAFullWarehouse() {
        RackPosition dedicated = rack(1, 0, Side.LEFT);
        stock.update(dedicated, slots(27));
        live.insertable.put(dedicated, STACK);
        filter(dedicated, DIAMOND);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                .inputBuffers(location -> slots(0, IRON, 3));

        PlanResult<String, RackPosition> filtered = planner.plan(base.storageLocations(List.of(dedicated)).build());
        assertFalse(filtered.hasJob());
        assertEquals(Set.of(NoJobReason.NO_MATCHING_FILTER), filtered.reasons());
        assertEquals(List.of(), live.insertCalls, "nothing was even simulated");

        // A location that may take the item but has no room is a genuinely full warehouse again.
        RackPosition full = rack(2, 0, Side.LEFT);
        stock.update(full, slots(0, IRON, STACK));
        PlanResult<String, RackPosition> outOfRoom = planner.plan(base.storageLocations(List.of(dedicated, full))
                .build());
        assertFalse(outOfRoom.hasJob());
        assertEquals(Set.of(NoJobReason.WAREHOUSE_FULL), outOfRoom.reasons());
    }

    /** A reroute stores the leftovers, so it obeys the same filters as the store plan. */
    @Test
    void storeReroutesRespectFilters() {
        RackPosition failed = rack(2, 0, Side.LEFT);
        RackPosition dedicatedToDiamonds = rack(3, 0, Side.LEFT);
        RackPosition general = rack(6, 0, Side.LEFT);
        for (RackPosition location : List.of(failed, dedicatedToDiamonds, general))
            stock.update(location, slots(27));
        live.insertable.put(dedicatedToDiamonds, STACK);
        live.insertable.put(general, 7);
        filter(dedicatedToDiamonds, DIAMOND);
        PlannerInput<String, RackPosition> in = input().crane(2, 0)
                .storageLocations(List.of(failed, dedicatedToDiamonds, general)).inputs(List.of(IN_A)).build();

        assertEquals(Optional.of(new RerouteTarget<>(general, LocationKind.STORAGE, 7, 16)),
                planner.planReroute(in, IRON, 10, JobType.STORE, failed),
                "the nearer chest is dedicated to another item");
        assertFalse(live.insertCalls.contains(dedicatedToDiamonds), "and is never simulated");
    }

    // --- reroute -------------------------------------------------------------------------------------------------

    @Test
    void storeLeftoversGoToOtherStorageThenInputsThenNowhere() {
        RackPosition failed = rack(2, 0, Side.LEFT);
        RackPosition rejecting = rack(3, 0, Side.LEFT);
        RackPosition accepting = rack(5, 0, Side.LEFT);
        for (RackPosition location : List.of(failed, rejecting, accepting))
            stock.update(location, slots(27));
        live.insertable.put(failed, STACK);
        live.insertable.put(accepting, 7);
        live.insertable.put(IN_A, 5);
        live.insertable.put(IN_B, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().crane(2, 0)
                .storageLocations(List.of(failed, rejecting, accepting)).inputs(List.of(IN_A, IN_B));

        assertEquals(Optional.of(new RerouteTarget<>(accepting, LocationKind.STORAGE, 7, 12)),
                planner.planReroute(base.build(), IRON, 10, JobType.STORE, failed));

        live.insertable.put(accepting, 0);
        assertEquals(Optional.of(new RerouteTarget<>(IN_A, LocationKind.INPUT, 5, 8)),
                planner.planReroute(base.build(), IRON, 10, JobType.STORE, failed),
                "both inputs are 8 ticks away: list order");

        live.insertable.put(IN_A, 0);
        live.insertable.put(IN_B, 0);
        assertEquals(Optional.empty(), planner.planReroute(base.build(), IRON, 10, JobType.STORE, failed));
        assertFalse(live.insertCalls.contains(failed), "the failed target is never asked again");
    }

    /** Review fix: a hold retry passes no failed target, so the former target is a candidate again. */
    @Test
    void aHoldRetryMayChooseTheFormerTarget() {
        RackPosition former = rack(2, 0, Side.LEFT);
        stock.update(former, slots(27));
        live.insertable.put(former, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().crane(2, 0).storageLocations(List.of(former));
        assertEquals(Optional.empty(), planner.planReroute(base.build(), IRON, 10, JobType.STORE, former),
                "excluded right after it failed");
        assertEquals(former, planner.planReroute(base.build(), IRON, 10, JobType.STORE, null).orElseThrow().location());
    }

    /** Review fix: storage candidates that use up the budget never hide a station that accepts the items. */
    @Test
    void theRerouteStationFallbackHasItsOwnBudget() {
        List<RackPosition> fullChests = new ArrayList<>();
        for (int x = 1; x <= 4; x++) {
            RackPosition chest = rack(x, 0, Side.LEFT);
            stock.update(chest, slots(27)); // the live inventories refuse (default 0)
            fullChests.add(chest);
        }
        live.insertable.put(IN_A, STACK);
        Optional<RerouteTarget<RackPosition>> target = planner.planReroute(input().storageLocations(fullChests)
                .inputs(List.of(IN_A)).liveSimulationBudget(2).build(), IRON, 10, JobType.STORE, null);
        assertEquals(IN_A, target.orElseThrow().location());
        assertEquals(3, live.insertCalls.size(), "two storage calls, then one for the input");
    }

    @Test
    void retrieveLeftoversGoBackToStorageThenOtherOutputsThenNowhere() {
        RackPosition empty = rack(1, 0, Side.LEFT);
        RackPosition consolidating = rack(6, 0, Side.LEFT);
        stock.update(empty, slots(27));
        stock.update(consolidating, slots(26, DIAMOND, 3));
        live.insertable.put(OUT_A, STACK);
        live.insertable.put(OUT_B, 4);
        live.insertable.put(empty, STACK);
        live.insertable.put(consolidating, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().crane(3, 0).outputs(List.of(OUT_A, OUT_B))
                .storageLocations(List.of(empty, consolidating));

        RerouteTarget<RackPosition> storage = planner.planReroute(base.build(), DIAMOND, 9, JobType.RETRIEVE, OUT_A)
                .orElseThrow();
        assertEquals(consolidating, storage.location(), "back into storage, not into an output that did not ask");
        assertEquals(LocationKind.STORAGE, storage.kind());
        assertEquals(9, storage.amount());

        live.insertable.put(empty, 0);
        live.insertable.put(consolidating, 0);
        assertEquals(Optional.of(new RerouteTarget<>(OUT_B, LocationKind.OUTPUT, 4, 12)),
                planner.planReroute(base.build(), DIAMOND, 9, JobType.RETRIEVE, OUT_A),
                "another output only when no storage location accepts the items");

        live.insertable.put(OUT_B, 0);
        assertEquals(Optional.empty(), planner.planReroute(base.build(), DIAMOND, 9, JobType.RETRIEVE, OUT_A));
        assertThrows(IllegalArgumentException.class,
                () -> planner.planReroute(base.build(), DIAMOND, 0, JobType.RETRIEVE, OUT_A));
    }

    /**
     * Review fix: retrieve leftovers already came <b>out</b> of the warehouse, so a store filter must not stop them
     * from going back. A location re-dedicated while its stock was inside can always take that stock back, and in a
     * fully partitioned aisle with a failed output that is the only thing between the crane and a permanent
     * {@code HOLDING} loop.
     */
    @Test
    void aRetrieveRerouteReturnsLeftoversToTheLocationTheyCameFrom() {
        RackPosition source = rack(2, 0, Side.LEFT);
        stock.update(source, slots(26, IRON, 4));
        live.insertable.put(source, STACK);
        live.insertable.put(OUT_A, 0); // the output failed mid-job
        filter(source, DIAMOND); // re-dedicated after the iron was stored there
        PlannerInput<String, RackPosition> in = input().crane(2, 0).storageLocations(List.of(source))
                .outputs(List.of(OUT_A)).inputs(List.of(IN_A)).build();

        RerouteTarget<RackPosition> target = planner.planReroute(in, IRON, 4, JobType.RETRIEVE, OUT_A).orElseThrow();
        assertEquals(source, target.location(), "back into the location the items were picked from");
        assertEquals(LocationKind.STORAGE, target.kind());
        assertEquals(4, target.amount());

        assertEquals(Optional.empty(), planner.planReroute(in, IRON, 4, JobType.STORE, null),
                "a store reroute still honours the filter: new items never enter a location that rejects them");
    }

    /** On a retrieve reroute a rejecting location is the last resort, not a peer of the ones that accept the item. */
    @Test
    void aRetrieveRerouteRanksRejectingLocationsLast() {
        RackPosition nearRejecting = rack(1, 0, Side.LEFT);
        RackPosition farGeneral = rack(6, 0, Side.LEFT);
        stock.update(nearRejecting, slots(27));
        stock.update(farGeneral, slots(27));
        live.insertable.put(nearRejecting, STACK);
        live.insertable.put(farGeneral, STACK);
        filter(nearRejecting, DIAMOND);
        PlannerInput<String, RackPosition> in = input().crane(1, 0)
                .storageLocations(List.of(nearRejecting, farGeneral)).outputs(List.of(OUT_A)).build();

        assertEquals(farGeneral, planner.planReroute(in, IRON, 5, JobType.RETRIEVE, OUT_A).orElseThrow().location(),
                "the unfiltered location wins although it is five blocks further away");
        assertEquals(List.of(farGeneral), live.insertCalls, "the rejecting one is not even simulated first");
    }
}
