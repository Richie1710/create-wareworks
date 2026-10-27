package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
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
 * The fill stage of {@link JobPlanner} (M31, issue #21, ADR-073): a {@link RequestKind#FILL} request is served by one
 * {@code FILL} job — empty containers out of a rack, filled at a fluid bay, delivered to the request's station — and
 * a warehouse without fill requests plans exactly as it did before.
 */
class JobPlannerFillTest {
    private static final String BUCKET = "bucket";
    private static final String LAVA_BUCKET = "lava_bucket";
    private static final String IRON = "iron";
    private static final int BUCKET_MB = 1000;
    private static final int STACK = 64;
    private static final int TRANSFER_TICKS = 10;
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    private static final RackPosition TERMINAL = RackPosition.of(0, 0, Side.LEFT);
    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    private static final RackPosition NEAR_RACK = RackPosition.of(2, 0, Side.LEFT);
    private static final RackPosition FAR_RACK = RackPosition.of(9, 0, Side.LEFT);
    private static final RackPosition NEAR_BAY = RackPosition.of(3, 0, Side.RIGHT);
    private static final RackPosition FAR_BAY = RackPosition.of(10, 0, Side.RIGHT);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final Map<RackPosition, Integer> extractable = new HashMap<>();
    private final Map<RackPosition, Integer> insertable = new HashMap<>();
    /** Whole containers each bay holds, as {@link PlannerInput.FillSupply#bays} reports them. */
    private final Map<RackPosition, Integer> bayContainers = new HashMap<>();
    /** What a bay's live fill answers; absent means "everything it is asked for". */
    private final Map<RackPosition, Integer> liveFill = new HashMap<>();
    private final List<RackPosition> fillCalls = new ArrayList<>();
    private final Map<String, Integer> carryLimits = new HashMap<>(Map.of(BUCKET, 16, LAVA_BUCKET, 1, IRON, STACK));

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1000};
        return () -> new UUID(0L, next[0]++);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());

    private static InventorySnapshot<String> slots(int emptySlots, Object... keyCounts) {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(emptySlots + keyCounts.length / 2);
        for (int i = 0; i < keyCounts.length; i += 2)
            builder.add((String) keyCounts[i], (Integer) keyCounts[i + 1], STACK, STACK);
        for (int i = 0; i < emptySlots; i++)
            builder.addEmpty(STACK);
        return builder.build();
    }

    private PlannerInput.FillSupply<String, RackPosition> supply() {
        return new PlannerInput.FillSupply<>() {
            @Override
            public Optional<PlannerInput.FillRecipe<String>> recipe(String filledKey) {
                return filledKey.equals(LAVA_BUCKET) ? Optional.of(new PlannerInput.FillRecipe<>(BUCKET, BUCKET_MB))
                        : Optional.empty();
            }

            @Override
            public List<PlannerInput.FillBay<RackPosition>> bays(String filledKey) {
                List<PlannerInput.FillBay<RackPosition>> bays = new ArrayList<>();
                for (RackPosition bay : List.of(NEAR_BAY, FAR_BAY)) {
                    int whole = bayContainers.getOrDefault(bay, 0);
                    if (whole > 0)
                        bays.add(new PlannerInput.FillBay<>(bay, whole));
                }
                return bays;
            }

            @Override
            public int liveFill(RackPosition bay, String emptyKey, int amount) {
                fillCalls.add(bay);
                return Math.min(amount, liveFill.getOrDefault(bay, amount));
            }
        };
    }

    private PlannerInput.Builder<String, RackPosition> input() {
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(0, 0)
                .speeds(SPEEDS)
                .transferTicks(TRANSFER_TICKS)
                .carryLimit(key -> carryLimits.getOrDefault(key, STACK))
                .liveExtract((location, key, max) -> Math.min(max, extractable.getOrDefault(location, 0)))
                .liveInsert((location, key, amount) -> Math.min(amount, insertable.getOrDefault(location, 0)))
                .fillSupply(supply());
    }

    private static PlannerInput.OpenRequest<String, RackPosition> fill(UUID id, int remaining) {
        return new PlannerInput.OpenRequest<>(id, LAVA_BUCKET, remaining, TERMINAL, RequestKind.FILL);
    }

    private void emptiesAt(RackPosition rack, int count) {
        stock.update(rack, slots(1, BUCKET, count));
        extractable.put(rack, count);
    }

    // --- planning ------------------------------------------------------------------------------------------------

    /**
     * One trip carries one filled bucket however many empties a grabber stack could hold: the trip is bounded by the
     * carry limit of what comes <b>out</b> of the bay as well (the owner's decision 1).
     */
    @Test
    void aFillRequestIsPlannedAsOneFillTripBoundedByBothCarryLimits() {
        emptiesAt(NEAR_RACK, 10);
        bayContainers.put(NEAR_BAY, 10);
        insertable.put(TERMINAL, 9);
        PlanResult<String, RackPosition> result = planner.plan(input().requests(List.of(fill(id(1), 3))).build());
        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(TransportJob.fill(job.id(), NEAR_RACK, NEAR_BAY, BUCKET, 1, id(1)), job);
        assertEquals(JobType.FILL, job.type());
        assertEquals(LocationKind.STORAGE, job.targetKind(), "the bay is the planned target");
        assertEquals(Set.of(), result.reasons());

        carryLimits.put(LAVA_BUCKET, 16);
        assertEquals(3, planner.plan(input().requests(List.of(fill(id(1), 3))).build()).job().orElseThrow().job()
                .plannedAmount(), "with a stackable filled container the need is the bound");
    }

    /** Pairs are ranked by crane → rack → bay → station, so the near rack goes with the near bay. */
    @Test
    void pairsAreRankedByTheThreeLegs() {
        emptiesAt(FAR_RACK, 4);
        emptiesAt(NEAR_RACK, 4);
        bayContainers.put(NEAR_BAY, 5);
        bayContainers.put(FAR_BAY, 5);
        insertable.put(TERMINAL, 9);
        PlannedJob<String, RackPosition> planned = planner.plan(input().requests(List.of(fill(id(1), 1))).build())
                .job().orElseThrow();
        assertEquals(NEAR_RACK, planned.job().source());
        assertEquals(NEAR_BAY, planned.job().target());
        long expected = TravelTimeModel.add(TravelTimeModel.add(TravelTimeModel.add(TravelTimeModel.add(
                TravelTimeModel.add(TravelTimeModel.travelTicks(SPEEDS, 0, 0, 2, 0),
                        TravelTimeModel.stopTicks(SPEEDS, TRANSFER_TICKS, LocationKind.STORAGE.armStroke())),
                TravelTimeModel.travelTicks(SPEEDS, 2, 0, 3, 0)),
                TravelTimeModel.stopTicks(SPEEDS, TRANSFER_TICKS, LocationKind.STORAGE.armStroke())),
                TravelTimeModel.travelTicks(SPEEDS, 3, 0, 0, 0)),
                TravelTimeModel.stopTicks(SPEEDS, TRANSFER_TICKS, LocationKind.OUTPUT.armStroke()));
        assertEquals(expected, planned.estimatedTicks(), "three legs and three stops");
    }

    @Test
    void noEmptiesIsItsOwnReasonAndNoFluidIsNotInStock() {
        bayContainers.put(NEAR_BAY, 10);
        insertable.put(TERMINAL, 9);
        PlanResult<String, RackPosition> noEmpties = planner.plan(input().requests(List.of(fill(id(1), 2))).build());
        assertFalse(noEmpties.hasJob());
        assertEquals(Set.of(NoJobReason.NO_EMPTY_CONTAINERS), noEmpties.reasons());

        bayContainers.clear();
        emptiesAt(NEAR_RACK, 3);
        PlanResult<String, RackPosition> noFluid = planner.plan(input().requests(List.of(fill(id(1), 2))).build());
        assertEquals(Set.of(NoJobReason.NOT_IN_STOCK), noFluid.reasons(), "the fluid is not in the warehouse");
    }

    @Test
    void emptiesTheLiveRackDoesNotGiveAreNoEmptiesAndABayThatFillsNothingIsNotInStock() {
        stock.update(NEAR_RACK, slots(1, BUCKET, 3));
        extractable.put(NEAR_RACK, 0);
        bayContainers.put(NEAR_BAY, 10);
        insertable.put(TERMINAL, 9);
        assertEquals(Set.of(NoJobReason.NO_EMPTY_CONTAINERS),
                planner.plan(input().requests(List.of(fill(id(1), 2))).build()).reasons());

        extractable.put(NEAR_RACK, 3);
        liveFill.put(NEAR_BAY, 0);
        assertEquals(Set.of(NoJobReason.NOT_IN_STOCK),
                planner.plan(input().requests(List.of(fill(id(1), 2))).build()).reasons(),
                "the bay was emptied by a pipe since its index entry was read");
    }

    /** The amount is the smallest of the need, the carry limits, the live empties, the bay's containers and its fill. */
    @Test
    void theAmountIsBoundedByTheBayAndItsLiveFill() {
        carryLimits.put(LAVA_BUCKET, 16);
        emptiesAt(NEAR_RACK, 10);
        bayContainers.put(NEAR_BAY, 4);
        insertable.put(TERMINAL, 64);
        assertEquals(4, planner.plan(input().requests(List.of(fill(id(1), 9))).build()).job().orElseThrow().job()
                .plannedAmount(), "the bay holds four whole containers that are not promised");
        liveFill.put(NEAR_BAY, 2);
        assertEquals(2, planner.plan(input().requests(List.of(fill(id(1), 9))).build()).job().orElseThrow().job()
                .plannedAmount(), "and its live fill says two");
        ledger.reserveStock(id(77), NEAR_RACK, BUCKET, 9);
        liveFill.remove(NEAR_BAY);
        assertEquals(1, planner.plan(input().requests(List.of(fill(id(1), 9))).build()).job().orElseThrow().job()
                .plannedAmount(), "and the rack's reserved empties are not counted");
    }

    /** A full station is the retrieve's answer: nothing planned, OUTPUT_FULL, and no empties are even looked at. */
    @Test
    void aFullStationIsOutputFull() {
        emptiesAt(NEAR_RACK, 4);
        bayContainers.put(NEAR_BAY, 4);
        PlanResult<String, RackPosition> result = planner.plan(input().requests(List.of(fill(id(1), 2))).build());
        assertEquals(Set.of(NoJobReason.OUTPUT_FULL), result.reasons());
        assertEquals(List.of(), fillCalls);
    }

    /** The runaway guard of every request: a fill already covered by its jobs plans no second trip. */
    @Test
    void aFillRequestCoveredByItsJobsIsSkipped() {
        emptiesAt(NEAR_RACK, 10);
        bayContainers.put(NEAR_BAY, 10);
        insertable.put(TERMINAL, 9);
        TransportJob<String, RackPosition> picked = TransportJob.fill(id(50), NEAR_RACK, NEAR_BAY, BUCKET, 1, id(1))
                .withPicked(1);
        ledger.track(picked);
        PlanResult<String, RackPosition> result = planner.plan(input().requests(List.of(fill(id(1), 1))).build());
        assertFalse(result.hasJob(), "the one container asked for is on its way");
        assertEquals(Set.of(NoJobReason.NO_WORK), result.reasons());
    }

    /**
     * A warehouse without fill requests plans <b>identically</b> with and without a fill supply: the same job, the same
     * reasons, the same cursor and the same live calls — the supply is never asked.
     */
    @Test
    void withoutFillRequestsTheFillSupplyChangesNothing() {
        stock.update(NEAR_RACK, slots(2, IRON, 30, BUCKET, 5));
        extractable.put(NEAR_RACK, 35);
        insertable.put(TERMINAL, 64);
        insertable.put(FAR_RACK, 64);
        stock.update(FAR_RACK, slots(4));
        List<PlannerInput.OpenRequest<String, RackPosition>> requests = List.of(
                new PlannerInput.OpenRequest<>(id(1), IRON, 7, TERMINAL));
        int[] supplyCalls = new int[1];
        PlannerInput.FillSupply<String, RackPosition> counting = new PlannerInput.FillSupply<>() {
            @Override
            public Optional<PlannerInput.FillRecipe<String>> recipe(String filledKey) {
                supplyCalls[0]++;
                return Optional.empty();
            }

            @Override
            public List<PlannerInput.FillBay<RackPosition>> bays(String filledKey) {
                supplyCalls[0]++;
                return List.of();
            }

            @Override
            public int liveFill(RackPosition bay, String emptyKey, int amount) {
                supplyCalls[0]++;
                return 0;
            }
        };
        for (int budget : new int[] {1, 2, 3, JobPlanner.DEFAULT_LIVE_SIMULATION_BUDGET}) {
            JobPlanner<String, RackPosition> before = new JobPlanner<>(Function.identity(), sequentialIds());
            JobPlanner<String, RackPosition> after = new JobPlanner<>(Function.identity(), sequentialIds());
            PlannerInput.Builder<String, RackPosition> plain = PlannerInput.builder(stock.readOnlyView(),
                            ledger.readOnlyView()).crane(0, 0).speeds(SPEEDS).transferTicks(TRANSFER_TICKS)
                    .carryLimit(key -> STACK).requests(requests).inputs(List.of(INPUT))
                    .storageLocations(List.of(NEAR_RACK, FAR_RACK))
                    .inputBuffers(location -> slots(0, IRON, 3))
                    .liveExtract((location, key, max) -> Math.min(max, extractable.getOrDefault(location, 0)))
                    .liveInsert((location, key, amount) -> Math.min(amount, insertable.getOrDefault(location, 0)))
                    .liveSimulationBudget(budget);
            PlanResult<String, RackPosition> without = before.plan(plain.build());
            PlanResult<String, RackPosition> with = after.plan(plain.fillSupply(counting).build());
            assertEquals(without, with, "budget " + budget);
        }
        assertEquals(0, supplyCalls[0], "the fill supply is never asked without a fill request");
    }

    // --- reroute -------------------------------------------------------------------------------------------------

    /**
     * A fill trip's leftovers are a retrieve's: storage first, then an output that did not ask for them. The
     * planner is handed the same arguments for both types and answers the same target.
     */
    @Test
    void aFillRerouteIsARetrieveReroute() {
        stock.update(NEAR_RACK, slots(3));
        stock.update(FAR_RACK, slots(3));
        insertable.put(NEAR_RACK, 64);
        insertable.put(FAR_RACK, 64);
        PlannerInput<String, RackPosition> in = input().storageLocations(List.of(FAR_RACK, NEAR_RACK))
                .outputs(List.of(TERMINAL)).build();
        Optional<RerouteTarget<RackPosition>> retrieve = planner.planReroute(in, BUCKET, 1, JobType.RETRIEVE,
                NEAR_BAY);
        Optional<RerouteTarget<RackPosition>> fill = planner.planReroute(in, BUCKET, 1, JobType.FILL, NEAR_BAY);
        assertTrue(fill.isPresent());
        assertEquals(retrieve, fill);
        assertEquals(LocationKind.STORAGE, fill.get().kind());

        insertable.clear();
        insertable.put(TERMINAL, 64);
        Optional<RerouteTarget<RackPosition>> toOutput = planner.planReroute(in, BUCKET, 1, JobType.FILL, NEAR_BAY);
        assertEquals(TERMINAL, toOutput.orElseThrow().location(), "an output only as the last resort");
    }
}
