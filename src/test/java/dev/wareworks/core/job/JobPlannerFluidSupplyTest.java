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
 * The fluid supply stage of {@link JobPlanner} (issue #55, ADR-089): an ingredient line of a production order that is
 * flagged {@link PlannerInput.SupplyNeed#fluid()} is served by one {@code FILL} trip that ends at the order's
 * <b>production station</b>, with the line as its request id — and by no more than
 * {@value PlannerInput.FillSupply#MAX_AT_STATION} containers at a station at a time.
 */
class JobPlannerFluidSupplyTest {
    private static final String BUCKET = "bucket";
    private static final String WATER_BUCKET = "water_bucket";
    private static final String DIRT = "dirt";
    private static final int STACK = 64;
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    private static final RackPosition STATION = RackPosition.of(0, 0, Side.LEFT);
    private static final RackPosition OTHER_STATION = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition RACK = RackPosition.of(2, 0, Side.LEFT);
    private static final RackPosition BAY = RackPosition.of(3, 0, Side.RIGHT);
    private static final RackPosition DIRT_RACK = RackPosition.of(4, 0, Side.LEFT);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final Map<RackPosition, Integer> extractable = new HashMap<>();
    private final Map<RackPosition, Integer> insertable = new HashMap<>();
    private final Map<RackPosition, Integer> held = new HashMap<>();
    private final List<RackPosition> fillCalls = new ArrayList<>();
    /** Fluid lines the station has that a selector gate keeps out of the supplies (issue #55 review). */
    private final List<UUID> gatedLines = new ArrayList<>();
    private int bayContainers = 10;

    private static Supplier<UUID> sequentialIds() {
        long[] next = {5000};
        return () -> new UUID(0L, next[0]++);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());

    private static InventorySnapshot<String> slots(String key, int count) {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(2);
        builder.add(key, count, STACK, STACK);
        builder.addEmpty(STACK);
        return builder.build();
    }

    private PlannerInput.FillSupply<String, RackPosition> supply() {
        return new PlannerInput.FillSupply<>() {
            @Override
            public Optional<PlannerInput.FillRecipe<String>> recipe(String filledKey) {
                return filledKey.equals(WATER_BUCKET) ? Optional.of(new PlannerInput.FillRecipe<>(BUCKET, 1000))
                        : Optional.empty();
            }

            @Override
            public List<PlannerInput.FillBay<RackPosition>> bays(String filledKey) {
                return bayContainers > 0 ? List.of(new PlannerInput.FillBay<>(BAY, bayContainers)) : List.of();
            }

            @Override
            public int liveFill(RackPosition bay, String emptyKey, int amount) {
                fillCalls.add(bay);
                return amount;
            }

            @Override
            public int heldAtStation(RackPosition station) {
                return held.getOrDefault(station, 0);
            }

            @Override
            public java.util.Collection<UUID> fluidLinesAt(RackPosition station) {
                return station.equals(STATION) ? gatedLines : List.of();
            }
        };
    }

    private PlannerInput.Builder<String, RackPosition> input() {
        stock.update(RACK, slots(BUCKET, 20));
        stock.update(DIRT_RACK, slots(DIRT, 100));
        extractable.putIfAbsent(RACK, 20);
        extractable.putIfAbsent(DIRT_RACK, 100);
        insertable.putIfAbsent(STATION, 64);
        insertable.putIfAbsent(OTHER_STATION, 64);
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(0, 0)
                .speeds(SPEEDS)
                .transferTicks(10)
                .carryLimit(key -> WATER_BUCKET.equals(key) ? 1 : STACK)
                .liveExtract((location, key, max) -> Math.min(max, extractable.getOrDefault(location, 0)))
                .liveInsert((location, key, amount) -> Math.min(amount, insertable.getOrDefault(location, 0)))
                .fillSupply(supply());
    }

    private static PlannerInput.SupplyNeed<String, RackPosition> water(long line, int remaining,
            RackPosition station) {
        return new PlannerInput.SupplyNeed<>(id(line), WATER_BUCKET, remaining, station, true);
    }

    private static PlannerInput.SupplyNeed<String, RackPosition> dirt(long line, int remaining) {
        return new PlannerInput.SupplyNeed<>(id(line), DIRT, remaining, STATION, false);
    }

    // --- the trip ---------------------------------------------------------------------------------------------------

    /** The line is served by a fill trip: empties from the rack, the bay, the production station, the line as owner. */
    @Test
    void aFluidLineIsServedByAFillTripToTheProductionStation() {
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(water(1, 2, STATION)))
                .build());
        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(JobType.FILL, job.type());
        assertEquals(RACK, job.source());
        assertEquals(BAY, job.target());
        assertEquals(BUCKET, job.key(), "it picks the empty container first");
        assertEquals(Optional.of(id(1)), job.requestId(), "the line is the owner");
        assertEquals(1, job.plannedAmount(), "a filled bucket is carried one at a time");
        assertEquals(Set.of(), result.reasons());
    }

    /** After the fill the trip may drop at the production station: the type allows it, the kind is checked by the job. */
    @Test
    void aFillTripMayEndAtAProductionStation() {
        assertTrue(JobType.FILL.allowsTarget(LocationKind.PRODUCTION));
        assertTrue(JobType.FILL.allowsTarget(LocationKind.OUTPUT));
        assertTrue(JobType.FILL.allowsTarget(LocationKind.STORAGE));
        assertFalse(JobType.FILL.allowsTarget(LocationKind.INPUT));
        TransportJob<String, RackPosition> trip = TransportJob.fill(id(7), RACK, BAY, BUCKET, 1, id(1));
        TransportJob<String, RackPosition> filled = trip.withPicked(1).filledFor(WATER_BUCKET, STATION,
                LocationKind.PRODUCTION);
        assertEquals(LocationKind.PRODUCTION, filled.targetKind());
        assertEquals(STATION, filled.target());
        assertEquals(Optional.of(id(1)), filled.requestId(), "the request (the line) is kept");
    }

    /** A terminal's fill request (an output) and a plain ingredient are planned as ever. */
    @Test
    void anItemLineIsStillARetrieve() {
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(dirt(2, 10))).build());
        TransportJob<String, RackPosition> job = result.job().orElseThrow().job();
        assertEquals(JobType.SUPPLY, job.type());
        assertEquals(DIRT_RACK, job.source());
        assertEquals(STATION, job.target());
    }

    // --- the two containers ------------------------------------------------------------------------------------------

    /** Containers standing in the buffer count: two there, and the next one waits for the player's machine. */
    @Test
    void twoContainersInTheBufferHoldTheNextOneBack() {
        held.put(STATION, 2);
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(water(1, 3, STATION)))
                .build());
        assertTrue(result.job().isEmpty());
        assertTrue(result.reasons().contains(NoJobReason.PRODUCTION_FULL));
        held.put(STATION, 1);
        TransportJob<String, RackPosition> job = planner.plan(input().supplies(List.of(water(1, 3, STATION))).build())
                .job().orElseThrow().job();
        assertEquals(1, job.plannedAmount(), "room for exactly one more");
    }

    /** What the trips on their way carry counts too, over every fluid line of the station. */
    @Test
    void tripsOnTheirWayCountAgainstTheSameTwo() {
        TransportJob<String, RackPosition> first = TransportJob.fill(id(40), RACK, BAY, BUCKET, 1, id(1));
        ledger.track(first);
        TransportJob<String, RackPosition> second = TransportJob.fill(id(41), RACK, BAY, BUCKET, 1, id(2));
        ledger.track(second);
        // line 3 of the same station asks too; both lines of its order already have a container on the way
        PlanResult<String, RackPosition> blocked = planner.plan(input().supplies(List.of(water(1, 3, STATION),
                water(2, 3, STATION), water(3, 3, STATION))).build());
        assertTrue(blocked.job().isEmpty(), "two on their way fill the station's two");
        assertTrue(blocked.reasons().contains(NoJobReason.PRODUCTION_FULL));
        // another station is untouched
        PlanResult<String, RackPosition> other = planner.plan(input().supplies(List.of(water(1, 3, STATION),
                water(2, 3, STATION), water(4, 1, OTHER_STATION))).build());
        assertEquals(Optional.of(id(4)), other.job().orElseThrow().job().requestId());
    }

    /**
     * A line the selector's gate keeps out of the supplies still has its trip on the way, and that container counts
     * against the station's two (issue #55 review): one in flight for the gated line, one held, so a third is refused.
     */
    @Test
    void aGatedLinesTripCountsToo() {
        TransportJob<String, RackPosition> gatedTrip = TransportJob.fill(id(40), RACK, BAY, BUCKET, 1, id(7));
        ledger.track(gatedTrip);
        held.put(STATION, 1);
        PlanResult<String, RackPosition> without = planner.plan(input().supplies(List.of(water(1, 1, STATION)))
                .build());
        assertTrue(without.job().isPresent(), "blind to the gated line, a second container would be planned");
        gatedLines.add(id(7));
        PlanResult<String, RackPosition> with = planner.plan(input().supplies(List.of(water(1, 1, STATION)))
                .build());
        assertTrue(with.job().isEmpty(), "one held and one on its way for the gated line fill the station's two");
        assertTrue(with.reasons().contains(NoJobReason.PRODUCTION_FULL));
    }

    /** The bays have nothing: the line waits, naming why, and nothing is planned for the iron instead of it. */
    @Test
    void noFluidInTheBaysIsNotInStock() {
        bayContainers = 0;
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(water(1, 1, STATION)))
                .build());
        assertTrue(result.job().isEmpty());
        assertTrue(result.reasons().contains(NoJobReason.NOT_IN_STOCK));
    }

    /** A station that takes nothing more reports PRODUCTION_FULL, as an item supply does, not the output's reason. */
    @Test
    void aFullStationIsProductionFull() {
        insertable.put(STATION, 0);
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(water(1, 1, STATION)))
                .build());
        assertTrue(result.job().isEmpty());
        assertTrue(result.reasons().contains(NoJobReason.PRODUCTION_FULL));
        assertFalse(result.reasons().contains(NoJobReason.OUTPUT_FULL));
    }

    /** No empty container anywhere: the named reason, not a generic one. */
    @Test
    void noEmptyContainerIsNamed() {
        extractable.put(RACK, 0);
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(water(1, 1, STATION)))
                .build());
        assertTrue(result.job().isEmpty());
        assertTrue(result.reasons().contains(NoJobReason.NO_EMPTY_CONTAINERS));
    }

    /** A filled container the supply cannot make any more (its fluid is gone from the recipe table) is not in stock. */
    @Test
    void anUnknownContainerIsNeverPlanned() {
        PlannerInput.SupplyNeed<String, RackPosition> unknown = new PlannerInput.SupplyNeed<>(id(9), "mystery_bucket", 1,
                STATION, true);
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(unknown)).build());
        assertTrue(result.job().isEmpty());
        assertTrue(result.reasons().contains(NoJobReason.NOT_IN_STOCK), "so the order ends by its timeout");
    }

    /** The containers already on their way to a line are not planned a second time. */
    @Test
    void whatIsOnItsWayIsNotPlannedAgain() {
        TransportJob<String, RackPosition> trip = TransportJob.fill(id(40), RACK, BAY, BUCKET, 1, id(1));
        ledger.track(trip);
        PlanResult<String, RackPosition> result = planner.plan(input().supplies(List.of(water(1, 1, STATION)))
                .build());
        assertTrue(result.job().isEmpty(), "the line's one container is already in flight");
    }

    /** The cap of the production planner and of this one are one number. */
    @Test
    void theCapIsTwo() {
        assertEquals(2, PlannerInput.FillSupply.MAX_AT_STATION);
    }
}
