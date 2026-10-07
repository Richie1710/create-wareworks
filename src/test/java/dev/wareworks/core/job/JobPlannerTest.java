package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.StockIndex;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.warehouse.LocationKind;

class JobPlannerTest {
    private static final String IRON = "iron";
    private static final String DIAMOND = "diamond";
    private static final String SHULKER = "shulker";
    /** Keys of one item type with different components, written "type#components" (see {@code itemType}). */
    private static final String ITEM_TYPE_SEPARATOR = "#";
    private static final String WORN_SWORD = "sword#worn";
    private static final String NEW_SWORD = "sword#new";
    private static final String PEARL = "pearl";
    private static final int SMALL_STACK = 16;
    /** What every vanilla container and every plain {@code ItemStackHandler} reports per slot. */
    private static final int CONTAINER_LIMIT = 99;
    /** A slot limit no stack-size rule can explain: a drawer. */
    private static final int BULK_LIMIT = 1 << 16;
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
    /** Accepting warehouse ports (M17): output stations of the aisle that take items instead of requesting them. */
    private static final RackPosition PORT_A = RackPosition.of(4, 0, Side.RIGHT);
    private static final RackPosition PORT_B = RackPosition.of(10, 0, Side.RIGHT);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());
    private final Live live = new Live();
    /** Store filters: the keys a filtered location lists ({@link PlannerInput#storeFilter()}). */
    private final Map<RackPosition, Set<String>> storeFilters = new HashMap<>();
    /** Locations whose list is a <b>deny</b> list: they accept everything they do not list, but select nothing. */
    private final Set<RackPosition> denyLists = new HashSet<>();
    /** Storage priorities per location ({@link PlannerInput#storePriority()}, M16); absent means 0. */
    private final Map<RackPosition, Integer> priorities = new HashMap<>();
    /** Signed port ranks ({@link PlannerInput#portRank()}, M17); absent means 0, i.e. "no accepting port". */
    private final Map<RackPosition, Integer> portRanks = new HashMap<>();

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

    /** A snapshot of one drawer-like slot: a limit far above any stack size, so stack sizes may not apply. */
    private static InventorySnapshot<String> bulkSlot(String key, int count) {
        return InventorySnapshot.<String>builder(1).add(key, count, BULK_LIMIT, STACK).build();
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

    /** Gives {@code location} a storage priority (M16); higher fills first when storing. */
    private void priority(RackPosition location, int priority) {
        priorities.put(location, priority);
    }

    private int priorityOf(RackPosition location) {
        return priorities.getOrDefault(location, 0);
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
        assertEquals(0, result.nextArrivalCursor());
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
        assertEquals(1, result.nextArrivalCursor(), "the cursor stays when nothing was planned");
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
        assertEquals(1, first.nextArrivalCursor());
        PlanResult<String, RackPosition> second = planner.plan(base.inputCursor(first.nextArrivalCursor()).build());
        assertEquals(IN_C, second.job().orElseThrow().job().source(), "the empty input is skipped");
        assertEquals(0, second.nextArrivalCursor());
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
        assertEquals(0, nextInput.nextArrivalCursor());
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
        assertEquals(1, result.nextArrivalCursor(), "the next run starts at the next input");
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

    /**
     * The store gate is a pre-filter, not a verdict: a location whose snapshot cannot be judged reaches the live
     * simulate. One drawer-like slot (a limit far above any stack size) holding <b>exactly</b> one full stack is the
     * case the three numbers cannot decide — full for an ordinary slot, half empty for a drawer — so the estimate
     * answers {@link JobPlanner#UNKNOWN_CAPACITY} and the drawer keeps being filled instead of dying at one stack.
     */
    @Test
    void aDrawerHoldingExactlyOneStackIsStillOffered() {
        RackPosition drawer = rack(2, 0, Side.LEFT);
        stock.update(drawer, bulkSlot(IRON, STACK));
        live.insertable.put(drawer, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(drawer))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        PlanResult<String, RackPosition> result = planner.plan(base.inputBuffers(l -> slots(0, IRON, 8)).build());
        assertTrue(result.hasJob(), "a drawer holding exactly one stack must not be gated out as full");
        assertEquals(drawer, result.job().orElseThrow().job().target(), "the drawer is still a store candidate");
        assertEquals(List.of(drawer), live.insertCalls, "and exactly one live simulate decided it");
        assertEquals(JobPlanner.UNKNOWN_CAPACITY, JobPlanner.InsertEstimate
                .fromSnapshots(stock.readOnlyView(), key -> STACK).estimateInsertable(drawer, IRON),
                "the snapshot cannot decide this slot, so it must not claim it is full");
    }

    /**
     * The other half of the same question, and the reason the estimate may not simply trust the slot limit: an
     * <b>ordinary</b> container slot reports a limit of {@value #CONTAINER_LIMIT} whatever it holds, so a slot holding
     * a full stack of a 16-stacking item (an ender pearl) is genuinely full. It must stay skipped, and it must cost no
     * live call at all — that is the cost bound of the fix above.
     */
    @Test
    void anOrdinaryFullSlotOfASmallStackingItemCostsNoLiveCall() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, InventorySnapshot.<String>builder(1)
                .add(PEARL, SMALL_STACK, CONTAINER_LIMIT, SMALL_STACK).build());
        live.insertable.put(chest, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> SMALL_STACK))
                .inputBuffers(location -> slots(0, PEARL, 8))
                .build());
        assertFalse(result.hasJob(), "a full ordinary slot is full");
        assertEquals(List.of(), live.insertCalls, "and is skipped before any live call");
    }

    /**
     * A drawer that already holds more than one stack proves its own behaviour, so the estimate answers a number
     * again — the unknown answer above is needed for exactly one count, not forever.
     */
    @Test
    void aDrawerPastOneStackEstimatesItsRealRoom() {
        RackPosition drawer = rack(2, 0, Side.LEFT);
        stock.update(drawer, bulkSlot(IRON, STACK + 1));
        assertEquals(BULK_LIMIT - (STACK + 1), JobPlanner.InsertEstimate
                .fromSnapshots(stock.readOnlyView(), key -> STACK).estimateInsertable(drawer, IRON));
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

    // --- stock rules: the maximum (M15, issue #3) -----------------------------------------------------------------

    /** A warehouse without stock rules answers "unlimited", so every other test in this class plans as before M15. */
    @Test
    void storeHeadroomIsUnlimitedByDefault() {
        assertEquals(Long.MAX_VALUE, input().build().storeHeadroom().applyAsLong(IRON));
        assertThrows(NullPointerException.class, () -> input().storeHeadroom(null).build());
    }

    /**
     * Partial storing is the normal case of a maximum, and it is exact: 64 buffered items with 58 of headroom left
     * store 58, and the remaining 6 stay in the input on purpose.
     */
    @Test
    void storeHeadroomBoundsThePlannedAmount() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, STACK));

        TransportJob<String, RackPosition> job = planner.plan(base.storeHeadroom(key -> 58).build()).job()
                .orElseThrow().job();
        assertEquals(IRON, job.key());
        assertEquals(58, job.plannedAmount());
        assertEquals(STACK, planner.plan(base.storeHeadroom(key -> 4096).build()).job().orElseThrow().job()
                .plannedAmount(), "headroom above the buffered amount changes nothing");
    }

    /**
     * An item with no headroom left is skipped before the candidate walk — no ranking, no estimate, no live call —
     * and the input is told it is at a maximum rather than that the warehouse is full.
     */
    @Test
    void anItemAtItsMaximumIsSkippedBeforeAnyCandidateWork() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 5))
                .storeHeadroom(key -> 0).build());
        assertFalse(result.hasJob());
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), result.reasons());
        assertEquals(NoJobReason.AT_MAXIMUM, result.primaryReason().orElseThrow());
        assertEquals(List.of(), live.insertCalls, "nothing was even simulated");
        assertEquals(0, result.nextArrivalCursor(), "the cursor stays when nothing was planned");
    }

    /** One capped item blocks neither the buffer's other item types nor the next input. */
    @Test
    void aCappedItemDoesNotBlockTheOthers() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        Map<String, Long> headroom = Map.of(IRON, 0L);

        PlanResult<String, RackPosition> sameInput = planner.plan(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 1, DIAMOND, 5))
                .storeHeadroom(key -> headroom.getOrDefault(key, Long.MAX_VALUE)).build());
        assertEquals(DIAMOND, sameInput.job().orElseThrow().job().key(), "the next item type of the same input");
        assertEquals(Set.of(), sameInput.reasons());

        Map<RackPosition, InventorySnapshot<String>> buffers = Map.of(IN_A, slots(0, IRON, 1), IN_B,
                slots(0, DIAMOND, 5));
        PlanResult<String, RackPosition> nextInput = planner.plan(input().inputs(List.of(IN_A, IN_B))
                .storageLocations(List.of(chest)).inputBuffers(buffers::get)
                .storeHeadroom(key -> headroom.getOrDefault(key, Long.MAX_VALUE)).build());
        assertEquals(IN_B, nextInput.job().orElseThrow().job().source());
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), nextInput.reasons());
    }

    /**
     * A maximum is the more specific answer than a filter mismatch, so it wins over one; but a skip that really is a
     * lack of room wins over both, or a player would be told "at maximum" while their warehouse is genuinely full.
     */
    @Test
    void atMaximumBeatsAFilterMismatchButNotAFullWarehouse() {
        RackPosition dedicated = rack(1, 0, Side.LEFT);
        stock.update(dedicated, slots(27));
        live.insertable.put(dedicated, STACK);
        filter(dedicated, SHULKER);
        Map<String, Long> headroom = Map.of(IRON, 0L);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .inputBuffers(location -> slots(0, IRON, 3, DIAMOND, 3))
                .storeHeadroom(key -> headroom.getOrDefault(key, Long.MAX_VALUE));

        PlanResult<String, RackPosition> capped = planner.plan(base.storageLocations(List.of(dedicated)).build());
        assertFalse(capped.hasJob());
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), capped.reasons());

        // A location that may take the diamonds but gives nothing is a genuinely full warehouse again.
        RackPosition full = rack(2, 0, Side.LEFT);
        stock.update(full, slots(27));
        PlanResult<String, RackPosition> outOfRoom = planner.plan(base.storageLocations(List.of(dedicated, full))
                .build());
        assertFalse(outOfRoom.hasJob());
        assertEquals(Set.of(NoJobReason.WAREHOUSE_FULL), outOfRoom.reasons());
    }

    /**
     * The ledger's per-key capacity aggregate is what stops two trips planned one after the other from both seeing
     * the same headroom and together storing past the maximum.
     */
    @Test
    void reservedCapacityBoundsTheNextTripAtAMaximum() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, 256);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, STACK))
                .storeHeadroom(key -> 100 - stock.count(key) - ledger.reservedCapacityFor(key));

        TransportJob<String, RackPosition> first = planner.plan(base.build()).job().orElseThrow().job();
        assertEquals(STACK, first.plannedAmount());
        ledger.track(first);
        assertEquals(STACK, ledger.reservedCapacityFor(IRON));

        TransportJob<String, RackPosition> second = planner.plan(base.build()).job().orElseThrow().job();
        assertEquals(36, second.plannedAmount(), "the first trip's items are already promised against the maximum");
        ledger.track(second);
        PlanResult<String, RackPosition> third = planner.plan(base.build());
        assertFalse(third.hasJob());
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), third.reasons());
    }

    /**
     * A reroute never consults the headroom (§8): the items are already in the handling head, so they must find a
     * target or the crane holds for ever.
     */
    @Test
    void reroutesIgnoreStoreHeadroom() {
        RackPosition failed = rack(2, 0, Side.LEFT);
        RackPosition chest = rack(4, 0, Side.LEFT);
        stock.update(failed, slots(27));
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        PlannerInput<String, RackPosition> in = input().crane(2, 0).storageLocations(List.of(failed, chest))
                .inputs(List.of(IN_A)).outputs(List.of(OUT_A)).storeHeadroom(key -> 0).build();
        for (JobType type : List.of(JobType.STORE, JobType.RETRIEVE, JobType.SUPPLY)) {
            assertEquals(chest, planner.planReroute(in, IRON, 10, type, failed).orElseThrow().location(),
                    "a " + type + " reroute is not bound by a maximum");
        }
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

    // --- storage priorities (M16, issue #11, ADR-028) --------------------------------------------------------------

    /** A warehouse in which nothing was prioritised answers 0, so every other test in this class plans as before M16. */
    @Test
    void storePriorityIsNeutralByDefault() {
        assertEquals(0, input().build().storePriority().applyAsInt(IN_A));
        assertSame(PlannerInput.NO_PRIORITY, input().build().storePriority());
        assertThrows(NullPointerException.class, () -> input().storePriority(null).build());
    }

    /**
     * The headline case: two locations the rules above the priority leave equal (both unfiltered, both empty), so today
     * only travel time decides. A priority overrides it, which is what "the rack by the door fills before the far end of
     * the aisle" means: travel time is measured crane -> input -> location and has nothing to do with where a player
     * stands.
     */
    @Test
    void storePrefersAHigherPriorityLocationOverANearerOne() {
        RackPosition near = rack(1, 0, Side.LEFT);
        RackPosition farPreferred = rack(9, 0, Side.LEFT);
        stock.update(near, slots(27));
        stock.update(farPreferred, slots(27));
        live.insertable.put(near, STACK);
        live.insertable.put(farPreferred, STACK);
        priority(farPreferred, 3);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(near, farPreferred)).storePriority(this::priorityOf)
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        assertEquals(farPreferred, storeTarget(base, IRON), "the preferred location wins over the nearer one");
        assertEquals(List.of(farPreferred), live.insertCalls, "and it is the first candidate simulated");

        // Without the priority the same scene stores into the nearer location: travel time is the next key.
        priorities.clear();
        live.insertCalls.clear();
        assertEquals(near, storeTarget(base, IRON));
    }

    /**
     * A filter is a <b>hard</b> rule ("may this item live here at all"), a priority a <b>soft</b> preference ("which of
     * the permitted locations first"), so hard comes first: a prioritised unfiltered vault must never outrank a
     * dedicated shelf, or dedicated locations would never fill while a general vault has room, the failure ADR-021
     * forbids.
     */
    @Test
    void priorityDoesNotOutrankTheStoreFilter() {
        RackPosition nearPrioritised = rack(1, 0, Side.LEFT);
        RackPosition farDedicated = rack(9, 0, Side.LEFT);
        stock.update(nearPrioritised, slots(27));
        stock.update(farDedicated, slots(27));
        live.insertable.put(nearPrioritised, STACK);
        live.insertable.put(farDedicated, STACK);
        priority(nearPrioritised, 9);
        filter(farDedicated, IRON);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(nearPrioritised, farDedicated)).storePriority(this::priorityOf)
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        assertEquals(farDedicated, storeTarget(base, IRON), "priority 9 does not beat a dedication");
        assertEquals(List.of(farDedicated), live.insertCalls);

        // And a rejecting filter still drops the location however high its priority is.
        priority(farDedicated, 9);
        live.insertCalls.clear();
        assertEquals(nearPrioritised, storeTarget(base, DIAMOND), "the dedicated location rejects diamonds");
        assertFalse(live.insertCalls.contains(farDedicated), "a rejected location is never simulated");
    }

    /**
     * The load-bearing decision, with a precedent in this repository: the M8 review found that a deny-list chest ranked
     * above consolidation and grouping "filled with a mix of everything" and demoted it. A priority is per
     * <b>location</b>, not per item, so a prioritised unfiltered location attracts <i>every</i> item type; above those
     * keys it would reproduce that bug by design. Below them it cannot.
     */
    @Test
    void priorityDoesNotOutrankConsolidationOrItemTypeGrouping() {
        RackPosition preferredHoldingAnotherType = rack(1, 0, Side.LEFT);
        RackPosition emptyPlain = rack(4, 0, Side.LEFT);
        RackPosition holdingTheKey = rack(8, 0, Side.LEFT);
        stock.update(preferredHoldingAnotherType, slots(26, DIAMOND, 10));
        stock.update(emptyPlain, slots(27));
        stock.update(holdingTheKey, slots(26, IRON, 10));
        for (RackPosition location : List.of(preferredHoldingAnotherType, emptyPlain, holdingTheKey))
            live.insertable.put(location, STACK);
        priority(preferredHoldingAnotherType, 5);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storePriority(this::priorityOf)
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        assertEquals(emptyPlain,
                storeTarget(base.storageLocations(List.of(preferredHoldingAnotherType, emptyPlain)), IRON),
                "item-type grouping decides before the priority, so a preferred location is not mixed");

        live.insertCalls.clear();
        priorities.clear();
        priority(emptyPlain, 5);
        assertEquals(holdingTheKey, storeTarget(base.storageLocations(List.of(emptyPlain, holdingTheKey)), IRON),
                "consolidation decides before the priority");
    }

    /** Within one filter class the priority is what orders several dedicated locations among themselves. */
    @Test
    void priorityRanksSeveralDedicatedLocations() {
        RackPosition nearDedicated = rack(1, 0, Side.LEFT);
        RackPosition farDedicated = rack(9, 0, Side.LEFT);
        stock.update(nearDedicated, slots(27));
        stock.update(farDedicated, slots(27));
        live.insertable.put(nearDedicated, STACK);
        live.insertable.put(farDedicated, STACK);
        filter(nearDedicated, IRON);
        filter(farDedicated, IRON);
        priority(nearDedicated, 1);
        priority(farDedicated, 4);
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A))
                .storageLocations(List.of(nearDedicated, farDedicated)).storePriority(this::priorityOf)
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));
        assertEquals(farDedicated, storeTarget(base, IRON), "both dedicated to iron: the higher priority fills first");
    }

    /** Equal priorities leave the old order intact: travel time, then list order for an exact tie. */
    @Test
    void equalPrioritiesFallBackToTravelTime() {
        RackPosition near = rack(2, 0, Side.LEFT);
        RackPosition far = rack(7, 0, Side.LEFT);
        RackPosition nearOtherSide = rack(2, 0, Side.RIGHT);
        for (RackPosition location : List.of(near, far, nearOtherSide)) {
            stock.update(location, slots(27));
            live.insertable.put(location, STACK);
            priority(location, 3);
        }
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A)).storePriority(this::priorityOf)
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK));

        assertEquals(near, storeTarget(base.storageLocations(List.of(far, near)), IRON),
                "equal priority: the nearer location wins");
        live.insertCalls.clear();
        assertEquals(near, storeTarget(base.storageLocations(List.of(near, nearOtherSide)), IRON),
                "equal priority and equal travel time: list order decides");
        live.insertCalls.clear();
        priority(far, 4);
        assertEquals(far, storeTarget(base.storageLocations(List.of(near, far)), IRON),
                "and one step higher wins again");
    }

    /**
     * The identity proof asked for by the milestone: with every priority 0 the planner produces the <b>same</b> job as
     * one that was never told about priorities at all. Structurally this holds because the new key compares
     * {@code Integer.compare(0, 0) == 0} for every pair, so the comparator evaluates the same chain as before, and
     * because the builder default is literally {@link PlannerInput#NO_PRIORITY}; this walks several seeded layouts
     * (filters, pre-existing contents, distances and item types mixed) to show it.
     */
    @Test
    void priorityZeroEverywhereReproducesTheOldOrder() {
        List<String> keys = List.of(IRON, DIAMOND, WORN_SWORD, NEW_SWORD, SHULKER);
        for (long seed = 1; seed <= 12; seed++) {
            Random random = new Random(seed);
            stock.clear();
            storeFilters.clear();
            denyLists.clear();
            priorities.clear();
            live.insertable.clear();
            List<RackPosition> storage = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                RackPosition location = rack(1 + random.nextInt(12), random.nextInt(3),
                        random.nextBoolean() ? Side.LEFT : Side.RIGHT);
                if (storage.contains(location))
                    continue;
                storage.add(location);
                String held = keys.get(random.nextInt(keys.size()));
                stock.update(location, random.nextBoolean() ? slots(27) : slots(26, held, 1 + random.nextInt(20)));
                live.insertable.put(location, random.nextInt(4) == 0 ? 0 : STACK);
                if (random.nextInt(3) == 0)
                    filter(location, keys.get(random.nextInt(keys.size())));
                else if (random.nextInt(5) == 0)
                    denyFilter(location, keys.get(random.nextInt(keys.size())));
            }
            String buffered = keys.get(random.nextInt(keys.size()));
            PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A, IN_B))
                    .inputCursor(random.nextInt(2)).storageLocations(storage)
                    .itemType(key -> key.split(ITEM_TYPE_SEPARATOR, 2)[0])
                    .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                    .inputBuffers(location -> slots(0, buffered, 7));

            live.insertCalls.clear();
            PlanResult<String, RackPosition> before = planner.plan(base.build());
            List<RackPosition> callsBefore = List.copyOf(live.insertCalls);
            live.insertCalls.clear();
            PlanResult<String, RackPosition> zeroed = planner.plan(base.storePriority(location -> 0).build());

            String scene = "seed " + seed + ", storage " + storage;
            assertEquals(describe(before), describe(zeroed), scene);
            assertEquals(before.reasons(), zeroed.reasons(), scene);
            assertEquals(before.nextArrivalCursor(), zeroed.nextArrivalCursor(), scene);
            assertEquals(callsBefore, live.insertCalls, "the same candidates in the same order: " + scene);
        }
    }

    /** A planned job without its (random) id, for comparing two planning runs of the same scene. */
    private static String describe(PlanResult<String, RackPosition> result) {
        return result.job()
                .map(planned -> planned.job().type() + " " + planned.job().source() + " to " + planned.job().target()
                        + " " + planned.job().key() + " x" + planned.job().plannedAmount() + " in "
                        + planned.estimatedTicks() + " ticks")
                .orElse("no job");
    }

    /**
     * Retrieval keeps the shortest path, always: the planner passes the neutral priority literally on that path, so a
     * high priority can never send the crane past a nearer location holding the same item. The same for a SUPPLY.
     */
    @Test
    void retrievalIgnoresStorePriorities() {
        RackPosition near = rack(1, 0, Side.LEFT);
        RackPosition farPrioritised = rack(9, 0, Side.LEFT);
        stock.update(near, slots(0, DIAMOND, 10));
        stock.update(farPrioritised, slots(0, DIAMOND, 10));
        live.extractable.put(near, 10);
        live.extractable.put(farPrioritised, 10);
        live.insertable.put(OUT_A, STACK);
        live.insertable.put(IN_C, STACK); // the production station of the supply below
        priority(farPrioritised, 9);
        PlannerInput.Builder<String, RackPosition> base = input().storePriority(this::priorityOf)
                .storageLocations(List.of(near, farPrioritised));

        PlanResult<String, RackPosition> retrieve = planner.plan(base
                .requests(List.of(request(id(1), DIAMOND, 5, OUT_A))).build());
        assertEquals(near, retrieve.job().orElseThrow().job().source(),
                "a retrieve takes the nearest source, priority or not");
        assertEquals(List.of(near), live.extractCalls, "the far, prioritised source is never asked");

        live.extractCalls.clear();
        PlanResult<String, RackPosition> supply = planner.plan(base.requests(List.of())
                .supplies(List.of(new PlannerInput.SupplyNeed<>(id(2), DIAMOND, 5, IN_C))).build());
        assertEquals(near, supply.job().orElseThrow().job().source(), "a supply takes the nearest source too");
        assertEquals(List.of(near), live.extractCalls);
    }

    /** A store reroute is still storing, so it honours the priority like the store plan does. */
    @Test
    void storeRerouteHonoursPriorities() {
        RackPosition failed = rack(2, 0, Side.LEFT);
        RackPosition near = rack(3, 0, Side.LEFT);
        RackPosition farPreferred = rack(9, 0, Side.LEFT);
        for (RackPosition location : List.of(failed, near, farPreferred)) {
            stock.update(location, slots(27));
            live.insertable.put(location, STACK);
        }
        priority(farPreferred, 2);
        PlannerInput<String, RackPosition> in = input().crane(2, 0).storePriority(this::priorityOf)
                .storageLocations(List.of(failed, near, farPreferred)).inputs(List.of(IN_A)).build();
        assertEquals(farPreferred, planner.planReroute(in, IRON, 10, JobType.STORE, failed).orElseThrow().location());
        assertEquals(List.of(farPreferred), live.insertCalls, "the preferred location is tried first");
    }

    /**
     * On a retrieve reroute the filter class is still the first key, so a priority can never lift a rejecting location
     * above an accepting one: the items would then go back into a location the player forbade for new items.
     */
    @Test
    void retrieveRerouteKeepsRejectingLocationsLastDespitePriority() {
        RackPosition nearRejectingPrioritised = rack(1, 0, Side.LEFT);
        RackPosition farGeneral = rack(8, 0, Side.LEFT);
        stock.update(nearRejectingPrioritised, slots(27));
        stock.update(farGeneral, slots(27));
        live.insertable.put(nearRejectingPrioritised, STACK);
        live.insertable.put(farGeneral, STACK);
        filter(nearRejectingPrioritised, DIAMOND);
        priority(nearRejectingPrioritised, 9);
        PlannerInput<String, RackPosition> in = input().crane(1, 0).storePriority(this::priorityOf)
                .storageLocations(List.of(nearRejectingPrioritised, farGeneral)).outputs(List.of(OUT_A)).build();
        assertEquals(farGeneral, planner.planReroute(in, IRON, 5, JobType.RETRIEVE, OUT_A).orElseThrow().location(),
                "rejecting stays last although it is nearer and prioritised 9");
        assertEquals(List.of(farGeneral), live.insertCalls);
    }

    /** A priority only reorders the candidates: it costs no extra live call and no extra budget. */
    @Test
    void priorityCostsNoLiveCallOrBudget() {
        List<RackPosition> chests = new ArrayList<>();
        for (int x = 1; x <= 6; x++) {
            RackPosition chest = rack(x, 0, Side.LEFT);
            stock.update(chest, slots(27));
            chests.add(chest);
        }
        RackPosition accepting = chests.get(5);
        live.insertable.put(accepting, STACK);
        priority(chests.get(4), 7); // prioritised, but its live inventory refuses
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A)).storageLocations(chests)
                .inputBuffers(location -> slots(0, IRON, 5));

        live.insertCalls.clear();
        planner.plan(base.build());
        int callsWithout = live.insertCalls.size();
        live.insertCalls.clear();
        PlanResult<String, RackPosition> withPriorities = planner.plan(base.storePriority(this::priorityOf).build());
        assertEquals(accepting, withPriorities.job().orElseThrow().job().target());
        assertEquals(callsWithout, live.insertCalls.size(), "the same number of live calls, only in another order");
        assertFalse(withPriorities.reasons().contains(NoJobReason.BUDGET_EXHAUSTED));
    }

    /**
     * A stock rule's maximum is consulted per key <b>before</b> any candidate work, so no priority can resurrect a
     * capped item, and the reason stays the specific {@code AT_MAXIMUM} rather than {@code WAREHOUSE_FULL}.
     */
    @Test
    void storeHeadroomStillDecidesBeforeAnyPriority() {
        RackPosition preferred = rack(1, 0, Side.LEFT);
        stock.update(preferred, slots(27));
        live.insertable.put(preferred, STACK);
        priority(preferred, 9);
        PlanResult<String, RackPosition> result = planner.plan(input().inputs(List.of(IN_A))
                .storageLocations(List.of(preferred)).storePriority(this::priorityOf)
                .inputBuffers(location -> slots(0, IRON, 5)).storeHeadroom(key -> 0).build());
        assertFalse(result.hasJob());
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), result.reasons());
        assertEquals(List.of(), live.insertCalls, "nothing was even simulated");
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
    // --- accepting warehouse ports (M17, issue #12, ADR-029) -------------------------------------------------------

    /**
     * A warehouse whose ports all request has no accepting port at all, which is the builder's default, so every other
     * test in this class plans as before M17.
     */
    @Test
    void acceptingPortsAreAbsentByDefault() {
        assertEquals(List.of(), input().build().ports());
        assertSame(PlannerInput.NO_PORT_RANK, input().build().portRank());
        assertEquals(0, input().build().portRank().applyAsInt(PORT_A));
        assertThrows(NullPointerException.class, () -> input().portRank(null).build());
        assertThrows(NullPointerException.class, () -> input().ports(null).build());
    }

    /**
     * The identity proof asked for by the milestone, in the shape M16 used: with no accepting port the planner produces
     * the <b>same</b> job, the same reasons, the same cursor and the same live-call sequence as one that was never told
     * about ports at all. Structurally it holds because every non-port candidate carries the same rank class, so the new
     * first key compares equal for every pair and {@code thenComparing} evaluates the chain that was there before; this
     * walks several seeded layouts (filters, priorities, pre-existing contents, distances and item types mixed) to show
     * it.
     */
    @Test
    void noAcceptingPortReproducesTheOldOrder() {
        List<String> keys = List.of(IRON, DIAMOND, WORN_SWORD, NEW_SWORD, SHULKER);
        for (long seed = 1; seed <= 12; seed++) {
            Random random = new Random(seed);
            stock.clear();
            storeFilters.clear();
            denyLists.clear();
            priorities.clear();
            portRanks.clear();
            live.insertable.clear();
            List<RackPosition> storage = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                RackPosition location = rack(1 + random.nextInt(12), random.nextInt(3),
                        random.nextBoolean() ? Side.LEFT : Side.RIGHT);
                if (storage.contains(location))
                    continue;
                storage.add(location);
                String held = keys.get(random.nextInt(keys.size()));
                stock.update(location, random.nextBoolean() ? slots(27) : slots(26, held, 1 + random.nextInt(20)));
                live.insertable.put(location, random.nextInt(4) == 0 ? 0 : STACK);
                if (random.nextInt(3) == 0)
                    filter(location, keys.get(random.nextInt(keys.size())));
                else if (random.nextInt(5) == 0)
                    denyFilter(location, keys.get(random.nextInt(keys.size())));
                if (random.nextInt(3) == 0)
                    priority(location, random.nextInt(10));
            }
            String buffered = keys.get(random.nextInt(keys.size()));
            PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A, IN_B))
                    .inputCursor(random.nextInt(2)).storageLocations(storage).storePriority(this::priorityOf)
                    .itemType(key -> key.split(ITEM_TYPE_SEPARATOR, 2)[0])
                    .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                    .inputBuffers(location -> slots(0, buffered, 7));

            live.insertCalls.clear();
            PlanResult<String, RackPosition> before = planner.plan(base.build());
            List<RackPosition> callsBefore = List.copyOf(live.insertCalls);
            live.insertCalls.clear();
            PlanResult<String, RackPosition> withEmptyPorts = planner.plan(base.ports(List.of())
                    .portRank(this::portRankOf).build());

            String scene = "seed " + seed + ", storage " + storage;
            assertEquals(describe(before), describe(withEmptyPorts), scene);
            assertEquals(before.reasons(), withEmptyPorts.reasons(), scene);
            assertEquals(before.nextArrivalCursor(), withEmptyPorts.nextArrivalCursor(), scene);
            assertEquals(callsBefore, live.insertCalls, "the same candidates in the same order: " + scene);
        }
    }

    /**
     * The user's row "everything incoming is diverted out": a diversion port outranks <b>every</b> storage location,
     * including a near one that is dedicated to the very item — "before they are stored" has to beat even the strongest
     * storing rule, or a diversion would divert nothing in a tidy warehouse.
     */
    @Test
    void aDiversionPortBeatsEvenADedicatedLocation() {
        RackPosition nearDedicated = rack(1, 0, Side.LEFT);
        stock.update(nearDedicated, slots(27));
        live.insertable.put(nearDedicated, STACK);
        filter(nearDedicated, IRON);
        priority(nearDedicated, PlannerInput.NO_PRIORITY.applyAsInt(nearDedicated) + 9);
        live.insertable.put(PORT_B, STACK);
        port(PORT_B, 1); // the weakest diversion there is

        TransportJob<String, RackPosition> job = storeJob(withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(nearDedicated)).storePriority(this::priorityOf)
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK)), PORT_B),
                IRON, 8);
        assertEquals(PORT_B, job.target());
        assertEquals(LocationKind.OUTPUT, job.targetKind());
        assertEquals(JobType.STORE, job.type(), "it is still a store job: nobody asked for these items");
        assertEquals(Optional.empty(), job.requestId());
        assertEquals(8, job.plannedAmount());
        assertEquals(List.of(PORT_B), live.insertCalls, "and the port is the first candidate simulated");
    }

    /**
     * The other half of the user's table: <b>a storage location always wins over an overflow</b>, however strong the
     * overflow is and however far away the location — the whole point of a negative rank is "only what the warehouse
     * could not keep". The port is reached only once nothing can store the items.
     */
    @Test
    void storageAlwaysWinsOverAnOverflowPort() {
        RackPosition farGeneral = rack(12, 2, Side.LEFT);
        stock.update(farGeneral, slots(27));
        live.insertable.put(farGeneral, STACK);
        live.insertable.put(PORT_A, STACK);
        port(PORT_A, PortSettings.MIN_RANK); // the strongest overflow there is, and it stands next to the input

        PlannerInput.Builder<String, RackPosition> base = withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(farGeneral))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK)), PORT_A);
        assertEquals(farGeneral, storeJob(base, IRON, 8).target(), "the far chest beats the overflow at the door");
        assertEquals(List.of(farGeneral), live.insertCalls, "and the port is not even simulated");

        // Only when nothing can store them does the overflow get anything.
        live.insertable.put(farGeneral, 0);
        live.insertCalls.clear();
        assertEquals(PORT_A, storeJob(base, IRON, 8).target());
        assertEquals(List.of(farGeneral, PORT_A), live.insertCalls, "storage first, always");
    }

    /**
     * The interaction M15 and M17 were designed around, and the reason an overflow exists at all: an item at its stock
     * rule's <b>maximum</b> contributes no storage candidate — no ranking, no estimate, no live call and no remembered
     * refusal, exactly as before M17 — and still reaches the accepting ports, with the full trip amount, because a
     * maximum is a rule about <b>storing</b> and a port does not store.
     */
    @Test
    void anItemAtItsMaximumStillReachesAnOverflowPort() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(PORT_A, STACK);
        port(PORT_A, -1);

        PlanResult<String, RackPosition> result = planner.plan(withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 30))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                .storeHeadroom(key -> 0), PORT_A).build());
        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(PORT_A, job.target());
        assertEquals(30, job.plannedAmount(), "the port's amount is the buffer, not the headroom");
        assertEquals(Set.of(), result.reasons());
        assertEquals(List.of(PORT_A), live.insertCalls, "the capped chest was never a candidate");
        assertEquals(0, result.nextArrivalCursor(), "the round robin moved on as for any planned job (one input, so it wraps)");
    }

    /** A headroom below the buffered amount bounds what may be <b>stored</b> and leaves a port's amount alone. */
    @Test
    void aHeadroomSmallerThanTheBufferBoundsStorageButNotThePort() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(PORT_A, STACK);
        PlannerInput.Builder<String, RackPosition> base = withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, STACK))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                .storeHeadroom(key -> 10), PORT_A);

        port(PORT_A, 2); // a diversion takes the whole trip
        TransportJob<String, RackPosition> diverted = planner.plan(base.build()).job().orElseThrow().job();
        assertEquals(PORT_A, diverted.target());
        assertEquals(STACK, diverted.plannedAmount());

        port(PORT_A, -2); // an overflow loses to the chest, which may take exactly the headroom
        live.insertCalls.clear();
        TransportJob<String, RackPosition> stored = planner.plan(base.build()).job().orElseThrow().job();
        assertEquals(chest, stored.target());
        assertEquals(10, stored.plannedAmount(), "the maximum still bounds storing, exactly as in M15");
    }

    /**
     * The user's row "only surplus cobblestone leaves, everything else backs up": a port's filter is a <b>hard</b> rule,
     * so a port that does not name the item is dropped before the capacity estimate and before any live call — a rack
     * wall of filtered ports can no more eat the live-simulation budget than a partitioned warehouse can (ADR-021).
     */
    @Test
    void aPortWhoseFilterRejectsCostsNoLiveCallAndNoBudget() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(PORT_A, STACK);
        port(PORT_A, -1);
        filter(PORT_A, DIAMOND);
        PlannerInput.Builder<String, RackPosition> base = withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                .storeHeadroom(key -> 0), PORT_A);

        // Iron at its maximum: the chest is no candidate and the cobblestone port refuses the iron, so it backs up.
        PlanResult<String, RackPosition> iron = planner.plan(base.inputBuffers(location -> slots(0, IRON, 8)).build());
        assertFalse(iron.hasJob());
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), iron.reasons(),
                "a rejecting port is no reason to report a full port");
        assertEquals(List.of(), live.insertCalls, "nothing was simulated at all");

        // Its own item leaves, and only its own item.
        TransportJob<String, RackPosition> diamonds = storeJob(base, DIAMOND, 8);
        assertEquals(PORT_A, diamonds.target());
        assertEquals(List.of(PORT_A), live.insertCalls);
    }

    /** Two ports compete by filter first, then strength, then travel time, then index order — never by anything else. */
    @Test
    void severalPortsRankByFilterThenStrengthThenTravelThenIndexOrder() {
        RackPosition nearPort = rack(2, 0, Side.RIGHT);
        RackPosition farPort = rack(9, 0, Side.RIGHT);
        RackPosition twinOfNear = rack(2, 0, Side.LEFT); // same x and y, so the same travel time
        for (RackPosition port : List.of(nearPort, farPort, twinOfNear)) {
            live.insertable.put(port, STACK);
            port(port, -1);
        }
        PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A)).storeHeadroom(key -> 0);

        // (1) A dedicated port before an unfiltered one, however much further away it is.
        filter(farPort, IRON);
        assertEquals(farPort, storeJob(withPorts(base, nearPort, farPort), IRON, 8).target());
        assertEquals(List.of(farPort), live.insertCalls);

        // (2) Within one filter class the stronger overflow first.
        storeFilters.clear();
        live.insertCalls.clear();
        port(farPort, -5);
        assertEquals(farPort, storeJob(withPorts(base, nearPort, farPort), IRON, 8).target(),
                "strength 4 beats strength 0");

        // (3) Equal strength: the nearer port.
        live.insertCalls.clear();
        port(farPort, -1);
        assertEquals(nearPort, storeJob(withPorts(base, farPort, nearPort), IRON, 8).target());

        // (4) Equal strength and equal travel time: list order, which makes planning deterministic.
        live.insertCalls.clear();
        assertEquals(twinOfNear, storeJob(withPorts(base, twinOfNear, nearPort), IRON, 8).target());
        live.insertCalls.clear();
        assertEquals(nearPort, storeJob(withPorts(base, nearPort, twinOfNear), IRON, 8).target());
    }

    /**
     * A full <b>diversion</b> port falls through to storage: one full chest behind a diversion port must not be able to
     * stop the whole warehouse from storing, which is the "a diversion swallows everything" risk in its worst form.
     */
    @Test
    void aFullDiversionPortFallsThroughToStorage() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(PORT_A, 0);
        port(PORT_A, 3);

        PlanResult<String, RackPosition> result = planner.plan(withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 8))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK)),
                PORT_A).build());
        assertEquals(chest, result.job().orElseThrow().job().target());
        assertEquals(LocationKind.STORAGE, result.job().orElseThrow().job().targetKind());
        assertEquals(List.of(PORT_A, chest), live.insertCalls, "the port was tried first and simply gave nothing");
    }

    /**
     * A full <b>overflow</b> port is the last candidate, so nothing takes the items and <b>the input backs up</b> —
     * exactly as an input does when a warehouse is full, with nothing destroyed and nothing dropped. It is reported as
     * {@link NoJobReason#PORT_FULL}, which is the thing a player can go and fix.
     */
    @Test
    void aFullOverflowPortBacksTheInputUpAndReportsPortFull() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(PORT_A, 0);
        port(PORT_A, -1);

        PlanResult<String, RackPosition> result = planner.plan(withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 8))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                .storeHeadroom(key -> 0), PORT_A).build());
        assertFalse(result.hasJob(), "the items stay in the input");
        assertEquals(Set.of(NoJobReason.PORT_FULL), result.reasons());
        assertEquals(NoJobReason.PORT_FULL, result.primaryReason().orElseThrow());
        assertEquals(List.of(PORT_A), live.insertCalls);
    }

    /**
     * The reason ladder: a full port is the more specific answer than a maximum (a maximum is not a fault, a backed-up
     * overflow is), and a storage location that was ranked and gave nothing is a genuinely full warehouse again.
     */
    @Test
    void portFullBeatsAtMaximumAndLosesToAFullWarehouse() {
        assertTrue(NoJobReason.PORT_FULL.ordinal() < NoJobReason.AT_MAXIMUM.ordinal(),
                "declared in priority order, so primaryReason() picks it over a maximum");
        assertTrue(NoJobReason.WAREHOUSE_FULL.ordinal() < NoJobReason.PORT_FULL.ordinal());

        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, 0);
        live.insertable.put(PORT_A, 0);
        port(PORT_A, -1);
        PlannerInput.Builder<String, RackPosition> base = withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest)).inputBuffers(location -> slots(0, IRON, 8)), PORT_A);

        PlanResult<String, RackPosition> capped = planner.plan(base.storeHeadroom(key -> 0).build());
        assertEquals(Set.of(NoJobReason.PORT_FULL), capped.reasons(), "the maximum is not what the player must fix");

        PlanResult<String, RackPosition> full = planner.plan(base.storeHeadroom(PlannerInput.UNLIMITED_HEADROOM).build());
        assertEquals(Set.of(NoJobReason.WAREHOUSE_FULL), full.reasons(),
                "a chest that was ranked and gave nothing is a full warehouse, not a full port");
    }

    /**
     * A port that is listed but ranks 0 is no accepting port: that is what a content layer answers for a port whose
     * policy it could not read, and exporting is irreversible, so the candidate is dropped rather than guessed at.
     */
    @Test
    void aPortRankOfZeroIsNoAcceptingPort() {
        live.insertable.put(PORT_A, STACK);
        PlanResult<String, RackPosition> result = planner.plan(withPorts(input().inputs(List.of(IN_A))
                .inputBuffers(location -> slots(0, IRON, 8)).storeHeadroom(key -> 0), PORT_A).build());
        assertFalse(result.hasJob());
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), result.reasons());
        assertEquals(List.of(), live.insertCalls, "an unresolved port is never simulated");
    }

    /**
     * Store leftovers reach a port only after every storage location and every input buffer (§8, M17): putting items
     * back into an input is reversible and exporting them is not, and it converges to the same outcome anyway because the
     * next store plan offers them to the port. The port's own rank does not lift it above the input.
     */
    @Test
    void storeLeftoversReachAPortAfterStorageAndInputs() {
        RackPosition failed = rack(2, 0, Side.LEFT);
        RackPosition chest = rack(4, 0, Side.LEFT);
        stock.update(failed, slots(27));
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(IN_A, STACK);
        live.insertable.put(PORT_A, STACK);
        port(PORT_A, PortSettings.MAX_RANK); // the strongest diversion: still last on a reroute
        PlannerInput.Builder<String, RackPosition> base = withPorts(input().crane(2, 0)
                .storageLocations(List.of(failed, chest)).inputs(List.of(IN_A)).outputs(List.of(OUT_A)), PORT_A);

        assertEquals(chest, planner.planReroute(base.build(), IRON, 10, JobType.STORE, failed).orElseThrow().location(),
                "another storage location first");

        live.insertable.put(chest, 0);
        RerouteTarget<RackPosition> toInput = planner.planReroute(base.build(), IRON, 10, JobType.STORE, failed)
                .orElseThrow();
        assertEquals(IN_A, toInput.location(), "then back into an input buffer");
        assertEquals(LocationKind.INPUT, toInput.kind());

        live.insertable.put(IN_A, 0);
        RerouteTarget<RackPosition> toPort = planner.planReroute(base.build(), IRON, 10, JobType.STORE, failed)
                .orElseThrow();
        assertEquals(PORT_A, toPort.location(), "and only then out through the port");
        assertEquals(LocationKind.OUTPUT, toPort.kind());
        assertEquals(10, toPort.amount());

        live.insertable.put(PORT_A, 0);
        assertTrue(planner.planReroute(base.build(), IRON, 10, JobType.STORE, failed).isEmpty(),
                "with nothing left the crane holds, as it always did");
    }

    /**
     * Retrieve and supply leftovers are <b>never</b> exported (M17): only items the warehouse chose not to store may
     * leave through a port, so a player can reason that what comes out of one is surplus and the mod never quietly feeds
     * a shredder with items somebody requested. An accepting port is therefore not even an "output station that did not
     * ask for these items", which is the last resort of a retrieve reroute.
     */
    @Test
    void retrieveAndSupplyLeftoversNeverReachAPort() {
        live.insertable.put(PORT_A, STACK);
        port(PORT_A, -1);
        // The port is an output station of the aisle as well, which is exactly the trap: it must be filtered out.
        PlannerInput<String, RackPosition> in = withPorts(input().crane(2, 0).storageLocations(List.of())
                .outputs(List.of(OUT_A, PORT_A)).inputs(List.of(IN_A)), PORT_A).build();

        assertTrue(planner.planReroute(in, IRON, 10, JobType.RETRIEVE, OUT_A).isEmpty(),
                "a retrieve reroute holds rather than exporting requested items");
        assertTrue(planner.planReroute(in, IRON, 10, JobType.SUPPLY, IN_C).isEmpty(),
                "and a supply reroute goes back into storage or nowhere");
        assertEquals(List.of(), live.insertCalls, "the port was not even simulated");

        // A store reroute does reach it, from the same input: the difference is the job, not the port.
        assertEquals(PORT_A, planner.planReroute(in, IRON, 10, JobType.STORE, null).orElseThrow().location());
    }

    /**
     * A port consults neither the capacity estimate nor the refusal memory, so every gated-open port costs one live
     * simulation per key per run for as long as it is open — the two mechanisms that make a large restricted warehouse
     * cheap (ADR-021) are both off for ports. The cost was argued from "the handful of ports an aisle has", which nothing
     * enforced: an aisle has up to {@code 32 × 16 × 2} rack positions against a budget of 64, so a rack wall of accepting
     * ports could spend the whole budget on ports every run and never reach a storage location at all.
     * <p>
     * {@link JobPlanner#MAX_PORT_CANDIDATES} enforces it instead, and the cap is applied <b>by rank</b>: the weakest
     * ports are dropped, never the one the ranking wanted.
     */
    @Test
    void aWallOfPortsCannotSpendTheWholeLiveBudget() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);

        // Twice the cap in full diversion ports, which rank ahead of every storage location, all of them near the input.
        List<RackPosition> ports = new ArrayList<>();
        for (int i = 0; i < 2 * JobPlanner.MAX_PORT_CANDIDATES; i++) {
            RackPosition port = rack(1 + i % 15, i / 15, Side.RIGHT); // never IN_A at (0, 0)
            port(port, 1);
            live.insertable.put(port, 0);
            ports.add(port);
        }
        PlannerInput.Builder<String, RackPosition> base = withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of(chest))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK)),
                ports.toArray(RackPosition[]::new));

        assertEquals(chest, storeJob(base, IRON, 8).target(), "the items are still stored");
        assertEquals(JobPlanner.MAX_PORT_CANDIDATES + 1, live.insertCalls.size(),
                "at most the cap in port simulations, then the storage location");
        assertEquals(chest, live.insertCalls.get(live.insertCalls.size() - 1));
        assertTrue(live.insertCalls.subList(0, JobPlanner.MAX_PORT_CANDIDATES).stream().allMatch(ports::contains));

        // The cap drops the weakest ports, not an arbitrary window: the last port of the list is the only one dedicated
        // to the item, which outranks every unfiltered one however far away it stands, so it is tried first of all.
        RackPosition dedicated = ports.get(ports.size() - 1);
        filter(dedicated, IRON);
        live.insertable.put(dedicated, STACK);
        live.insertCalls.clear();
        assertEquals(dedicated, storeJob(base, IRON, 8).target(), "the best port survives the cap");
        assertEquals(List.of(dedicated), live.insertCalls, "and is the first candidate simulated");
    }

    /**
     * Exactly the cap in ports changes nothing at all: every one of them is offered, in the same order the merged ranking
     * puts them in. That is the case every aisle anybody builds is in.
     */
    @Test
    void asManyPortsAsTheCapAreAllOffered() {
        List<RackPosition> ports = new ArrayList<>();
        for (int i = 0; i < JobPlanner.MAX_PORT_CANDIDATES; i++) {
            RackPosition port = rack(1 + i, 0, Side.RIGHT); // never IN_A at (0, 0); further from it with every step
            port(port, -1);
            live.insertable.put(port, 0);
            ports.add(port);
        }
        PlannerInput.Builder<String, RackPosition> base = withPorts(input().inputs(List.of(IN_A))
                .storageLocations(List.of()), ports.toArray(RackPosition[]::new));

        assertEquals(Optional.empty(), planner.plan(base.inputBuffers(location -> slots(0, IRON, 8)).build()).job());
        assertEquals(ports, live.insertCalls, "all of them, nearest first");
    }

    /** Helpers of this section. */
    private PlannerInput.Builder<String, RackPosition> withPorts(PlannerInput.Builder<String, RackPosition> base,
            RackPosition... ports) {
        return base.ports(List.of(ports)).portRank(this::portRankOf);
    }

    /** Makes {@code location} an accepting port with a signed rank (negative overflow, positive diversion). */
    private void port(RackPosition location, int rank) {
        portRanks.put(location, rank);
    }

    private int portRankOf(RackPosition location) {
        return portRanks.getOrDefault(location, 0);
    }

    /** The job the store plan produces for {@code buffered} items of {@code key} at every input. */
    private TransportJob<String, RackPosition> storeJob(PlannerInput.Builder<String, RackPosition> base, String key,
            int buffered) {
        return planner.plan(base.inputBuffers(location -> slots(0, key, buffered)).build()).job().orElseThrow().job();
    }

    // --- collecting warehouse ports (M18, issue #13) -----------------------------------------------------------------

    /**
     * An aisle without a collecting port is the builder's default, so every other test in this class plans as before M18.
     */
    @Test
    void collectSourcesAreAbsentByDefault() {
        assertEquals(List.of(), input().build().collectSources());
        assertTrue(input().build().collectBuffers().apply(PORT_A).isEmpty());
        assertThrows(NullPointerException.class, () -> input().collectSources(null).build());
        assertThrows(NullPointerException.class, () -> input().collectBuffers(null).build());
    }

    /**
     * The identity proof of M18, in the shape M16 and M17 used: with no collect source the planner produces the
     * <b>same</b> job, the same reasons, the same cursor and the same live-call sequence as an input that was never told
     * about collecting at all. Structurally it holds because the arrival walk is {@code inputs ++ collectSources} and the
     * second half is empty, so the loop is the loop it was; this walks the same seeded layout matrix the M17 proof walks.
     */
    @Test
    void noCollectSourceReproducesTheOldOrder() {
        List<String> keys = List.of(IRON, DIAMOND, WORN_SWORD, NEW_SWORD, SHULKER);
        for (long seed = 1; seed <= 12; seed++) {
            Random random = new Random(seed);
            stock.clear();
            storeFilters.clear();
            denyLists.clear();
            priorities.clear();
            portRanks.clear();
            live.insertable.clear();
            live.extractable.clear();
            List<RackPosition> storage = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                RackPosition location = rack(1 + random.nextInt(12), random.nextInt(3),
                        random.nextBoolean() ? Side.LEFT : Side.RIGHT);
                if (storage.contains(location))
                    continue;
                storage.add(location);
                String held = keys.get(random.nextInt(keys.size()));
                stock.update(location, random.nextBoolean() ? slots(27) : slots(26, held, 1 + random.nextInt(20)));
                live.insertable.put(location, random.nextInt(4) == 0 ? 0 : STACK);
                if (random.nextInt(3) == 0)
                    filter(location, keys.get(random.nextInt(keys.size())));
                else if (random.nextInt(5) == 0)
                    denyFilter(location, keys.get(random.nextInt(keys.size())));
                if (random.nextInt(3) == 0)
                    priority(location, random.nextInt(10));
            }
            String buffered = keys.get(random.nextInt(keys.size()));
            PlannerInput.Builder<String, RackPosition> base = input().inputs(List.of(IN_A, IN_B))
                    .inputCursor(random.nextInt(2)).storageLocations(storage).storePriority(this::priorityOf)
                    .itemType(key -> key.split(ITEM_TYPE_SEPARATOR, 2)[0])
                    .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK))
                    .inputBuffers(location -> slots(0, buffered, 7));

            live.insertCalls.clear();
            PlanResult<String, RackPosition> before = planner.plan(base.build());
            List<RackPosition> callsBefore = List.copyOf(live.insertCalls);
            live.insertCalls.clear();
            PlanResult<String, RackPosition> withEmptyCollect = planner.plan(base.collectSources(List.of())
                    .collectBuffers(location -> InventorySnapshot.empty()).build());

            String scene = "seed " + seed + ", storage " + storage;
            assertEquals(describe(before), describe(withEmptyCollect), scene);
            assertEquals(before.reasons(), withEmptyCollect.reasons(), scene);
            assertEquals(before.nextArrivalCursor(), withEmptyCollect.nextArrivalCursor(), scene);
            assertEquals(callsBefore, live.insertCalls, "the same candidates in the same order: " + scene);
        }
    }

    /** The happy path: what the machine behind the port holds is fetched and stored, and the job says so. */
    @Test
    void aCollectSourceIsStoredLikeAnythingElse() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.extractable.put(PORT_A, 20);

        TransportJob<String, RackPosition> job = collectJob(collecting(input().storageLocations(List.of(chest)), PORT_A),
                IRON, 20);
        assertEquals(JobType.COLLECT, job.type());
        assertEquals(PORT_A, job.source());
        assertEquals(chest, job.target());
        assertEquals(LocationKind.STORAGE, job.targetKind());
        assertEquals(20, job.plannedAmount());
        assertEquals(Optional.empty(), job.requestId());
    }

    /** A player's request and a production order's ingredients are always planned first: collecting never makes one wait. */
    @Test
    void requestsAndSuppliesBeatACollect() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(26, DIAMOND, 30));
        live.insertable.put(chest, STACK);
        live.insertable.put(OUT_A, STACK);
        live.insertable.put(IN_C, STACK);
        live.extractable.put(chest, 30);
        live.extractable.put(PORT_A, 20);
        PlannerInput.Builder<String, RackPosition> base = collecting(input()
                .storageLocations(List.of(chest)).outputs(List.of(OUT_A)), PORT_A);

        TransportJob<String, RackPosition> retrieve = planner.plan(base
                .requests(List.of(request(id(1), DIAMOND, 8, OUT_A))).build()).job().orElseThrow().job();
        assertEquals(JobType.RETRIEVE, retrieve.type(), "a waiting player first");

        TransportJob<String, RackPosition> supply = planner.plan(base.requests(List.of())
                .supplies(List.of(new PlannerInput.SupplyNeed<>(id(2), DIAMOND, 8, IN_C))).build())
                .job().orElseThrow().job();
        assertEquals(JobType.SUPPLY, supply.type(), "then a production order's ingredients");

        assertEquals(JobType.COLLECT, planner.plan(base.supplies(List.of()).build()).job().orElseThrow().job().type(),
                "and only then the arrivals");
    }

    /**
     * Fairness (risk 4): the input stations and the collecting ports are <b>one</b> round robin from one cursor, so within
     * one full walk every input and every collect source is served exactly once — and an always-full input can therefore
     * not starve a collecting port, which is exactly the production loop collecting exists for.
     */
    @Test
    void oneArrivalWalkServesEveryInputAndEveryCollectSource() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.extractable.put(PORT_A, STACK);
        live.extractable.put(PORT_B, STACK);
        PlannerInput.Builder<String, RackPosition> base = input().storageLocations(List.of(chest))
                .inputs(List.of(IN_A, IN_B)).collectSources(List.of(PORT_A, PORT_B))
                .collectBuffers(location -> slots(0, IRON, 8))
                .inputBuffers(location -> slots(0, IRON, 8));

        List<String> served = new ArrayList<>();
        int cursor = 0;
        for (int run = 0; run < 4; run++) {
            PlanResult<String, RackPosition> result = planner.plan(base.inputCursor(cursor).build());
            TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
            served.add(job.type() + "@" + job.source());
            cursor = result.nextArrivalCursor();
        }
        assertEquals(List.of("STORE@" + IN_A, "STORE@" + IN_B, "COLLECT@" + PORT_A, "COLLECT@" + PORT_B), served,
                "every arrival exactly once per walk, inputs first because the cursor started there");
        assertEquals(0, cursor, "and the walk wraps");
    }

    /**
     * Loop guard 1 (§5), the cheap one: a key at its M15 maximum yields <b>no collect job at all</b>, and a headroom
     * smaller than what the machine holds bounds the amount. The moment an overflow can fire, collecting of that key has
     * already stopped, so the churn cycle cannot start.
     */
    @Test
    void aCollectStopsAtTheMaximum() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.extractable.put(PORT_A, 40);
        PlannerInput.Builder<String, RackPosition> base = collecting(input().storageLocations(List.of(chest)), PORT_A);

        PlanResult<String, RackPosition> capped = planner.plan(base.storeHeadroom(key -> 0L)
                .collectBuffers(location -> slots(0, IRON, 40)).build());
        assertTrue(capped.job().isEmpty(), "nothing is fetched out of the machine");
        assertTrue(capped.reasons().contains(NoJobReason.AT_MAXIMUM), "and the aisle says why");
        assertEquals(List.of(), live.extractCalls, "the maximum decides before any live call");

        assertEquals(6, collectJob(base.storeHeadroom(key -> 6L), IRON, 40).plannedAmount(),
                "a headroom smaller than the offer bounds the amount; the rest stays in the machine");
    }

    /**
     * Loop guard 2 (§5): a collect is never offered a <b>port</b>, not even the strongest diversion standing right next to
     * it — the one candidate that ignores the headroom guard 1 relies on. It is structural (JobType.COLLECT allows no
     * OUTPUT target), and the planner never even simulates the port.
     */
    @Test
    void aCollectIsNeverOfferedAPort() {
        RackPosition chest = rack(8, 0, Side.LEFT); // far away, so only the ranking could prefer it
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(PORT_B, STACK);
        live.extractable.put(PORT_A, 20);
        port(PORT_B, PortSettings.MAX_RANK); // the strongest diversion there is, right at the aisle mouth

        TransportJob<String, RackPosition> job = collectJob(withPorts(collecting(input()
                .storageLocations(List.of(chest)), PORT_A), PORT_B), IRON, 20);
        assertEquals(chest, job.target(), "the far storage location, never the near diversion");
        assertFalse(live.insertCalls.contains(PORT_B), "the port was not even simulated");
    }

    /** And the same on the reroute path: storage, then the input stations, and nothing else — never a port. */
    @Test
    void aCollectRerouteOffersStorageThenInputsAndNothingElse() {
        RackPosition failed = rack(2, 0, Side.LEFT);
        RackPosition chest = rack(4, 0, Side.LEFT);
        stock.update(failed, slots(27));
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.insertable.put(IN_A, STACK);
        live.insertable.put(PORT_B, STACK);
        port(PORT_B, PortSettings.MAX_RANK);
        PlannerInput.Builder<String, RackPosition> base = withPorts(input().crane(2, 0)
                .storageLocations(List.of(failed, chest)).inputs(List.of(IN_A)).outputs(List.of(OUT_A)), PORT_B);

        assertEquals(chest, planner.planReroute(base.build(), IRON, 10, JobType.COLLECT, failed).orElseThrow()
                .location(), "another storage location first");

        live.insertable.put(chest, 0);
        RerouteTarget<RackPosition> toInput = planner.planReroute(base.build(), IRON, 10, JobType.COLLECT, failed)
                .orElseThrow();
        assertEquals(IN_A, toInput.location(), "then an input buffer, from where they are stored normally");
        assertEquals(LocationKind.INPUT, toInput.kind());

        live.insertable.put(IN_A, 0);
        assertTrue(planner.planReroute(base.build(), IRON, 10, JobType.COLLECT, failed).isEmpty(),
                "and then the crane holds rather than pushing them anywhere");
        assertFalse(live.insertCalls.contains(PORT_B), "the strong diversion was never even simulated");
    }

    /**
     * The port's own filter is a <b>hard</b> rule and is answered before any live call, exactly as an accepting port's is:
     * an unfiltered port collects anything the machine hands out, a filtered one only its item.
     */
    @Test
    void aPortFilterDecidesWhatIsCollectedAtAll() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.extractable.put(PORT_A, STACK);
        filter(PORT_A, DIAMOND);
        PlannerInput.Builder<String, RackPosition> base = collecting(input().storageLocations(List.of(chest)), PORT_A);

        PlanResult<String, RackPosition> rejected = planner.plan(base.collectBuffers(location -> slots(0, IRON, 8))
                .build());
        assertTrue(rejected.job().isEmpty(), "the iron the port does not name stays in the machine");
        assertEquals(List.of(), live.extractCalls, "and costs no live call");
        assertEquals(Set.of(NoJobReason.COLLECT_SOURCE_EMPTY), rejected.reasons(),
                "and says the machine has nothing this port may fetch, never that the warehouse is full: that reason "
                        + "would arm the aisle's back-off against every input station");

        assertEquals(DIAMOND, collectJob(base, DIAMOND, 8).key(), "its own item is collected");
    }

    /**
     * A stale snapshot is normal: the machine may have consumed the items since. The live extract bounds the amount, a key
     * the machine no longer hands out falls through to the next key, and a source that hands out nothing at all falls
     * through to the next source.
     */
    @Test
    void theLiveExtractBoundsTheAmountAndFallsThrough() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.extractable.put(PORT_A, 5);
        PlannerInput.Builder<String, RackPosition> base = collecting(input().storageLocations(List.of(chest)), PORT_A);
        assertEquals(5, collectJob(base, IRON, 40).plannedAmount(), "what the machine really hands out");

        live.extractable.put(PORT_A, 0);
        live.extractable.put(PORT_B, 7);
        PlanResult<String, RackPosition> next = planner.plan(base.collectSources(List.of(PORT_A, PORT_B)).build());
        TransportJob<String, RackPosition> job = next.job().orElseThrow().job();
        assertEquals(PORT_B, job.source(), "an empty source falls through to the next one");
        assertEquals(7, job.plannedAmount());
    }

    /** A key that fits nowhere falls through to the next key of the same machine, then to the next source. */
    @Test
    void oneUnstorableItemNeverBlocksACollectingPort() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.extractable.put(PORT_A, STACK);
        filter(chest, DIAMOND); // the chest takes diamonds only
        PlannerInput.Builder<String, RackPosition> base = collecting(input().storageLocations(List.of(chest)), PORT_A)
                .collectBuffers(location -> slots(0, IRON, 8, DIAMOND, 4));

        TransportJob<String, RackPosition> job = planner.plan(base.build()).job().orElseThrow().job();
        assertEquals(DIAMOND, job.key(), "the iron nothing takes is skipped, the diamond is collected");
        assertEquals(4, job.plannedAmount());
    }

    /** The target ranking is {@code selectStorage}'s: a store filter, then consolidation, then the priority a player set. */
    @Test
    void collectTargetsAreRankedLikeAnyStore() {
        RackPosition near = rack(1, 0, Side.LEFT);
        RackPosition far = rack(9, 0, Side.LEFT);
        RackPosition dedicated = rack(12, 0, Side.LEFT);
        for (RackPosition location : List.of(near, far, dedicated)) {
            stock.update(location, slots(27));
            live.insertable.put(location, STACK);
        }
        priority(far, 9);
        PlannerInput.Builder<String, RackPosition> base = collecting(input()
                .storageLocations(List.of(near, far, dedicated)).storePriority(this::priorityOf), PORT_A);
        live.extractable.put(PORT_A, 8);

        assertEquals(far, collectJob(base, IRON, 8).target(), "the priority a player set beats the nearer location");
        filter(dedicated, IRON);
        assertEquals(dedicated, collectJob(base, IRON, 8).target(), "and a dedicated filter beats the priority");
    }

    /** The reason ladder of a collect-only aisle: no room, no filter that takes it, or a maximum. */
    @Test
    void theReasonLadderOfACollectOnlyAisle() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.extractable.put(PORT_A, 8);
        PlannerInput.Builder<String, RackPosition> base = collecting(input().storageLocations(List.of(chest))
                .insertEstimate(JobPlanner.InsertEstimate.fromSnapshots(stock.readOnlyView(), key -> STACK)), PORT_A);

        // The chest has room per snapshot but refuses live: a genuinely full warehouse.
        live.insertable.put(chest, 0);
        assertEquals(Set.of(NoJobReason.WAREHOUSE_FULL),
                planner.plan(base.build()).reasons());

        // No storage location's filter accepts it at all: another problem, another answer.
        filter(chest, DIAMOND);
        assertEquals(Set.of(NoJobReason.NO_MATCHING_FILTER), planner.plan(base.build()).reasons());

        // And a maximum, which is not a fault at all.
        storeFilters.clear();
        live.insertable.put(chest, STACK);
        assertEquals(Set.of(NoJobReason.AT_MAXIMUM), planner.plan(base.storeHeadroom(key -> 0L).build()).reasons());

        // Nothing in the machine at all is simply no work.
        assertEquals(Set.of(NoJobReason.NO_WORK),
                planner.plan(base.collectBuffers(location -> InventorySnapshot.empty()).build()).reasons());

        // And the two cheap fall-throughs of the collect branch, which reach no candidate at all: the port's own filter
        // rejecting what is in the machine, and the machine handing nothing out. Neither is a full warehouse, and neither
        // may arm the back-off (M18 review). The builder is mutable, so the machine and the headroom are restored first.
        base.collectBuffers(location -> slots(0, IRON, 8)).storeHeadroom(key -> Long.MAX_VALUE);
        live.insertable.put(chest, STACK);
        filter(PORT_A, DIAMOND);
        assertEquals(Set.of(NoJobReason.COLLECT_SOURCE_EMPTY), planner.plan(base.build()).reasons(),
                "the port's filter names none of it");
        storeFilters.clear();
        live.extractable.put(PORT_A, 0);
        assertEquals(Set.of(NoJobReason.COLLECT_SOURCE_EMPTY), planner.plan(base.build()).reasons(),
                "the machine hands nothing out");
    }

    /**
     * A collect source that yields nothing must not make an <b>input station</b> of the same run report a full warehouse
     * either: the reasons of one run are shared, {@code WAREHOUSE_FULL} is the strongest of them, and it is the one that
     * arms {@code fullBackoffTicks} in {@code CraneDispatch} (M18 review).
     */
    @Test
    void anEmptyCollectSourceNeverReportsAFullWarehouseNextToAnInput() {
        RackPosition chest = rack(2, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        live.extractable.put(PORT_A, 0); // the machine hands nothing out
        filter(chest, DIAMOND); // and the only storage location takes no iron, which is the input's honest answer
        PlanResult<String, RackPosition> result = planner.plan(collecting(input()
                .storageLocations(List.of(chest)).inputs(List.of(IN_A))
                .inputBuffers(location -> slots(0, IRON, 8)), PORT_A).build());

        assertTrue(result.job().isEmpty());
        assertEquals(Set.of(NoJobReason.NO_MATCHING_FILTER, NoJobReason.COLLECT_SOURCE_EMPTY), result.reasons());
        assertFalse(result.reasons().contains(NoJobReason.WAREHOUSE_FULL),
                "the collect source contributes no full warehouse, which would arm the aisle's back-off");
        assertEquals(NoJobReason.NO_MATCHING_FILTER, result.primaryReason().orElseThrow(),
                "and never masks the input station's own answer, which is what the goggles show");
    }

    /**
     * A rack wall of collecting ports cannot spend the live budget a storage location needs
     * ({@link JobPlanner#MAX_COLLECT_CANDIDATES}): beyond the cap the sources are simply not examined this run, and the
     * cursor still moves on, so the next runs reach them.
     */
    @Test
    void aWallOfCollectingPortsIsBounded() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        List<RackPosition> sources = new ArrayList<>();
        for (int i = 0; i < 3 * JobPlanner.MAX_COLLECT_CANDIDATES; i++) {
            RackPosition source = rack(1 + i % 15, i / 15, Side.RIGHT);
            sources.add(source);
            live.extractable.put(source, 0); // every one of them answers "nothing", so the run walks them all
        }
        PlanResult<String, RackPosition> result = planner.plan(input().storageLocations(List.of(chest))
                .collectSources(sources).collectBuffers(location -> slots(0, IRON, 8)).build());
        assertTrue(result.job().isEmpty());
        assertEquals(JobPlanner.MAX_COLLECT_CANDIDATES, live.extractCalls.size(),
                "at most the cap in live extractions per run");
        assertEquals(JobPlanner.MAX_COLLECT_CANDIDATES, result.nextArrivalCursor(),
                "the next run starts at the first source this one skipped, so nothing beyond the cap starves");
        // And it really continues there: the second run examines the next window rather than the same one again.
        live.extractCalls.clear();
        planner.plan(input().storageLocations(List.of(chest)).collectSources(sources)
                .collectBuffers(location -> slots(0, IRON, 8)).inputCursor(result.nextArrivalCursor()).build());
        assertEquals(sources.subList(JobPlanner.MAX_COLLECT_CANDIDATES, 2 * JobPlanner.MAX_COLLECT_CANDIDATES),
                live.extractCalls, "the next window of sources");
        assertEquals(Set.of(NoJobReason.COLLECT_SOURCE_EMPTY), result.reasons(),
                "a wall of machines that hand nothing out is not a full warehouse");
    }

    /**
     * The cap's cursor survives a job (M18 review): a run that skipped sources and then wrapped round to an input station
     * that had work must still leave the cursor at the first skipped source. Otherwise an input station that plans a job
     * on every run keeps the cursor inside the input region for ever and the sources beyond the cap are never examined
     * again.
     */
    @Test
    void theCapsCursorSurvivesAJobFromAnInput() {
        RackPosition chest = rack(1, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        List<RackPosition> sources = new ArrayList<>();
        for (int i = 0; i < 2 * JobPlanner.MAX_COLLECT_CANDIDATES; i++) {
            RackPosition source = rack(1 + i % 15, 1 + i / 15, Side.RIGHT);
            sources.add(source);
            live.extractable.put(source, 0); // none of them yields anything, so the run walks into the cap
        }
        // The walk starts at the first collect source (index 1, right after the single input station), hits the cap and
        // wraps round to the input, which always has items.
        PlanResult<String, RackPosition> result = planner.plan(input().storageLocations(List.of(chest))
                .inputs(List.of(IN_A)).inputBuffers(location -> slots(0, IRON, 8)).collectSources(sources)
                .collectBuffers(location -> slots(0, IRON, 8)).inputCursor(1).build());

        assertEquals(IN_A, result.job().orElseThrow().job().source(), "the input station got the job");
        assertEquals(1 + JobPlanner.MAX_COLLECT_CANDIDATES, result.nextArrivalCursor(),
                "and the cursor stays at the first source the cap dropped, not at the input's successor");
    }

    // --- helpers of this section -------------------------------------------------------------------------------------

    /** Makes {@code sources} collecting ports whose machines hold 8 iron each unless a test says otherwise. */
    private PlannerInput.Builder<String, RackPosition> collecting(PlannerInput.Builder<String, RackPosition> base,
            RackPosition... sources) {
        return base.collectSources(List.of(sources)).collectBuffers(location -> slots(0, IRON, 8));
    }

    /** The job the collect branch produces for {@code held} items of {@code key} behind every collecting port. */
    private TransportJob<String, RackPosition> collectJob(PlannerInput.Builder<String, RackPosition> base, String key,
            int held) {
        return planner.plan(base.collectBuffers(location -> slots(0, key, held)).build()).job().orElseThrow().job();
    }

    // --- the cost of one pass (M22, issue #2) -------------------------------------------------------------------------

    /**
     * Whether a location can be used is the most expensive question the planner asks about one — the content layer
     * answers it with a route search from the crane's own point plus a chunk lookup — and it is asked over every
     * storage location per <b>(input station x item type)</b>. Nothing can change it while one pass ranks, so it is
     * asked of the world <b>once per location per pass</b> ({@link LocationAvailability}).
     * <p>
     * The assertion is the promise itself rather than a number: no location is ever asked about twice in one pass.
     * Before this it was asked six times about every chest in exactly this setup.
     */
    @Test
    void onePassAsksTheWorldAboutEveryLocationAtMostOnce() {
        List<RackPosition> chests = new ArrayList<>();
        for (int x = 2; x <= 9; x++) {
            RackPosition chest = rack(x, 0, Side.LEFT);
            chests.add(chest);
            stock.update(chest, slots(27));
            live.insertable.put(chest, STACK);
        }
        List<RackPosition> asked = new ArrayList<>();
        // Two input stations, three item types each: the shape that made the old walk pay six times per chest.
        PlanResult<String, RackPosition> result = planner.plan(input()
                .storageLocations(chests)
                .inputs(List.of(IN_A, IN_B))
                .inputBuffers(location -> slots(0, IRON, 4, DIAMOND, 4, SHULKER, 4))
                .available(location -> {
                    asked.add(location);
                    return true;
                })
                .build());

        assertTrue(result.hasJob(), "the pass really did plan a job over those chests");
        assertEquals(new HashSet<>(asked).size(), asked.size(),
                "no location was asked about twice: " + asked);
        assertTrue(asked.containsAll(chests), "and every chest was asked about once");
    }

    /**
     * Filtering the storage list by availability must not reorder what is left: the planner's last ranking key is a
     * location's index in {@link PlannerInput#storageLocations()}, so two candidates that are equal in every other key
     * still go to the earlier one in that list — with or without an unavailable location in front of it.
     */
    @Test
    void anUnavailableLocationDoesNotReorderTheOnesBehindIt() {
        RackPosition unreachable = rack(2, 0, Side.LEFT);
        RackPosition first = rack(3, 0, Side.LEFT);
        RackPosition second = rack(3, 0, Side.RIGHT);
        for (RackPosition chest : List.of(unreachable, first, second)) {
            stock.update(chest, slots(27));
            live.insertable.put(chest, STACK);
        }
        // first and second are the same distance from the crane and equal in every other key, so only the order of
        // the list can decide - and the unavailable location in front of them must not change it.
        PlanResult<String, RackPosition> result = planner.plan(input()
                .storageLocations(List.of(unreachable, first, second))
                .available(location -> !location.equals(unreachable))
                .inputs(List.of(IN_A)).inputBuffers(location -> slots(0, IRON, 8)).build());
        assertEquals(first, result.job().orElseThrow().job().target());

        PlanResult<String, RackPosition> withoutIt = planner.plan(input()
                .storageLocations(List.of(first, second))
                .inputs(List.of(IN_A)).inputBuffers(location -> slots(0, IRON, 8)).build());
        assertEquals(first, withoutIt.job().orElseThrow().job().target(), "and the same one wins without it");
    }

    /**
     * <b>The measurement</b> of the second M22 scaling item, in the shape that pays for it: a warehouse of 64 storage
     * locations whose chests are all dedicated to something else, with four input stations holding three item types
     * each. Every one of the twelve (station x item type) combinations ranks the whole warehouse, so this is the
     * dispatch run the candidate work is really about — and the one {@code fullBackoffTicks} exists to protect.
     * <p>
     * Both numbers are asserted exactly, because a measurement nobody can read is a claim:
     * <ul>
     * <li><b>before</b> {@link LocationAvailability#questions()} = 4 + 12 · 64 = <b>772</b> questions put to the
     * world — four about the stations and the whole list once per combination;</li>
     * <li><b>after</b> {@link LocationAvailability#probes()} = 64 + 4 = <b>68</b>, one per location, and
     * {@link LocationAvailability#listWalks()} = <b>1</b>: the candidate list is derived once for the run however many
     * item types it ranks.</li>
     * </ul>
     * That is 11.4 times less of the most expensive question in the planner, and — the part that matters for a
     * warehouse that splits — a number that no longer grows with the item types an input holds.
     */
    @Test
    void theWorkOfOneDispatchRunDoesNotGrowWithTheItemTypesItRanks() {
        List<RackPosition> chests = new ArrayList<>();
        for (int level = 0; level < 4; level++) {
            for (int x = 2; x <= 17; x++) {
                RackPosition chest = rack(x, level, Side.LEFT);
                chests.add(chest);
                stock.update(chest, slots(27));
                live.insertable.put(chest, STACK);
                // Dedicated to an item no station holds, so every combination ranks the whole list and none succeeds:
                // a REJECTED filter costs no live call, so the live budget cannot cut the walk short (ADR-021).
                filter(chest, "something else");
            }
        }
        List<RackPosition> stations = List.of(IN_A, IN_B, IN_C, rack(0, 3, Side.RIGHT));
        PlannerInput<String, RackPosition> pass = input()
                .storageLocations(chests)
                .inputs(stations)
                .inputBuffers(location -> slots(0, IRON, 4, DIAMOND, 4, SHULKER, 4))
                .build();

        PlanResult<String, RackPosition> result = planner.plan(pass);

        assertFalse(result.hasJob(), "nothing fits anywhere, which is why every combination ranks the whole warehouse");
        assertEquals(Optional.of(NoJobReason.NO_MATCHING_FILTER), result.primaryReason());
        LocationAvailability<RackPosition> asked = pass.available();
        assertEquals(1, asked.listWalks(), "the candidate list is derived once per dispatch run");
        assertEquals(chests.size() + stations.size(), asked.probes(),
                "and the world is asked once per location: " + asked);
        assertEquals(stations.size() + stations.size() * 3 * chests.size(), asked.questions(),
                "what the same run asked the world before M22: " + asked);
        assertEquals(0, live.insertCalls.size(), "no live call was spent on a rejected candidate");
    }

    /**
     * The same measurement for the ordinary run — one input station, one item type, a job planned: the list is still
     * derived once and every location asked about once, so the pass that <b>succeeds</b> pays nothing for the cache
     * either.
     */
    @Test
    void aPassThatPlansAJobAsksAboutEachLocationOnce() {
        List<RackPosition> chests = new ArrayList<>();
        for (int x = 2; x <= 9; x++) {
            RackPosition chest = rack(x, 0, Side.LEFT);
            chests.add(chest);
            stock.update(chest, slots(27));
            live.insertable.put(chest, STACK);
        }
        PlannerInput<String, RackPosition> pass = input()
                .storageLocations(chests)
                .inputs(List.of(IN_A))
                .inputBuffers(location -> slots(0, IRON, 8))
                .build();

        assertTrue(planner.plan(pass).hasJob());

        LocationAvailability<RackPosition> asked = pass.available();
        assertEquals(1, asked.listWalks(), "one list for the run");
        assertEquals(chests.size() + 1, asked.probes(), "one question per chest and one about the station: " + asked);
    }

    /**
     * The other half of the cache, and the one the list walk alone does not cover: a <b>retrieve</b> stage walks the
     * locations that hold the item (a different list, one question per candidate) and the <b>store</b> stage that runs
     * after it in the same pass then ranks the same chests again. The world is asked about each of them once for the
     * whole pass, not once per stage.
     * <p>
     * Measured: eight chests, one open request nothing can be extracted for and one input station whose items fit
     * nowhere — <b>18</b> questions, <b>10</b> probes.
     */
    @Test
    void aRetrieveAndAStoreInOnePassShareWhatTheWorldSaid() {
        List<RackPosition> chests = new ArrayList<>();
        for (int x = 2; x <= 9; x++) {
            RackPosition chest = rack(x, 0, Side.LEFT);
            chests.add(chest);
            stock.update(chest, slots(27, IRON, 8));
        }
        live.insertable.put(OUT_A, STACK); // the output takes items; nothing can be extracted for the request
        PlannerInput<String, RackPosition> pass = input()
                .storageLocations(chests)
                .requests(List.of(request(id(1), IRON, 8, OUT_A)))
                .inputs(List.of(IN_A))
                .inputBuffers(location -> slots(0, DIAMOND, 8))
                .build();

        assertFalse(planner.plan(pass).hasJob(), "both stages ran and neither could plan");

        LocationAvailability<RackPosition> asked = pass.available();
        assertEquals(chests.size() + 2, asked.probes(),
                "each chest once, plus the output and the input station: " + asked);
        assertEquals(2 * chests.size() + 2, asked.questions(),
                "both stages needed an answer about every chest: " + asked);
    }

    /**
     * <b>One pass, one cache.</b> Availability is kept for the length of a planning pass because nothing can change it
     * while the planner ranks — which stops being true the moment the pass is over. A builder that is kept and built a
     * second time must therefore ask the world again, or a controller planning twice would plan the second job on what
     * the world looked like before the first one.
     */
    @Test
    void aSecondPassBuiltFromTheSameBuilderAsksTheWorldAgain() {
        RackPosition chest = rack(3, 0, Side.LEFT);
        stock.update(chest, slots(27));
        live.insertable.put(chest, STACK);
        boolean[] reachable = {false};
        PlannerInput.Builder<String, RackPosition> builder = input()
                .storageLocations(List.of(chest))
                .inputs(List.of(IN_A))
                .inputBuffers(location -> slots(0, IRON, 8))
                .available(location -> location.equals(IN_A) || reachable[0]);

        assertFalse(planner.plan(builder.build()).hasJob(), "the chest cannot be driven to yet");
        reachable[0] = true;

        assertTrue(planner.plan(builder.build()).hasJob(), "the next pass sees the rails that were laid in between");
    }
}
