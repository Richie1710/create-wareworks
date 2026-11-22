package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.StockIndex;

/**
 * {@link PlannerInput#storeExcluded()} (package mode, issue #40, owner decision E3): a storage location the content
 * layer excludes for a key is never a target for it on <b>any</b> path that stores into a location — the store plan,
 * the reroutes of every job type that stores leftovers, and the optimizer's relocation stage — and it is never asked
 * live. Each test also plans once without the exclusion, so it shows that the excluded place would have been chosen.
 */
class JobPlannerStoreExcludedTest {
    private static final String BOX = "box";
    private static final String IRON = "iron";
    private static final int STACK = 64;
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    /** The empty rack bay right beside the input: the nearest place, and the one package mode keeps boxes out of. */
    private static final RackPosition BAY = RackPosition.of(1, 0, Side.LEFT);
    /** A chest behind an interface, farther away. */
    private static final RackPosition CHEST = RackPosition.of(8, 0, Side.LEFT);
    /** Where an optimizer's box lies at priority 0. */
    private static final RackPosition OVERFLOW = RackPosition.of(12, 0, Side.LEFT);

    private static final BiPredicate<RackPosition, String> BOXES_KEPT_OUT_OF_THE_BAY =
            (location, key) -> location.equals(BAY) && key.equals(BOX);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());
    private final Map<RackPosition, Integer> priorities = new HashMap<>();
    private final Map<RackPosition, Integer> extractable = new HashMap<>();
    private final List<RackPosition> insertCalls = new ArrayList<>();

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1000};
        return () -> new UUID(0L, next[0]++);
    }

    private static InventorySnapshot<String> slots(int emptySlots, Object... keyCounts) {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(emptySlots + keyCounts.length / 2);
        for (int i = 0; i < keyCounts.length; i += 2)
            builder.add((String) keyCounts[i], (Integer) keyCounts[i + 1], STACK, STACK);
        for (int i = 0; i < emptySlots; i++)
            builder.addEmpty(STACK);
        return builder.build();
    }

    private PlannerInput.Builder<String, RackPosition> input() {
        stock.update(BAY, slots(1));
        stock.update(CHEST, slots(27));
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(0, 0)
                .speeds(SPEEDS)
                .transferTicks(10)
                .carryLimit(key -> STACK)
                .storageLocations(List.of(BAY, CHEST, OVERFLOW))
                .inputs(List.of(INPUT))
                .storePriority(location -> priorities.getOrDefault(location, 0))
                .liveExtract((location, key, max) -> Math.min(max, extractable.getOrDefault(location, 0)))
                .liveInsert((location, key, amount) -> {
                    insertCalls.add(location);
                    return location.equals(OVERFLOW) ? 0 : amount;
                });
    }

    @Test
    void nothingIsExcludedByDefault() {
        PlannerInput<String, RackPosition> built = input().build();
        assertFalse(built.storeExcluded().test(BAY, BOX), "an input built without it excludes nothing");
        assertThrows(NullPointerException.class, () -> input().storeExcluded(null).build());
    }

    @Test
    void theStorePlanPutsABoxPastTheExcludedBay() {
        PlannerInput.Builder<String, RackPosition> box = input().inputBuffers(location -> slots(0, BOX, 1));
        assertEquals(BAY, planner.plan(box.build()).job().orElseThrow().job().target(),
                "without the exclusion the nearest place takes the box");

        insertCalls.clear();
        TransportJob<String, RackPosition> job = planner.plan(box.storeExcluded(BOXES_KEPT_OUT_OF_THE_BAY).build())
                .job().orElseThrow().job();
        assertEquals(JobType.STORE, job.type());
        assertEquals(CHEST, job.target(), "with it the box goes to the chest");
        assertFalse(insertCalls.contains(BAY), "and the bay is never asked");
    }

    @Test
    void aLooseKeyStillTakesTheBay() {
        TransportJob<String, RackPosition> job = planner.plan(input().inputBuffers(location -> slots(0, IRON, 8))
                .storeExcluded(BOXES_KEPT_OUT_OF_THE_BAY).build()).job().orElseThrow().job();
        assertEquals(BAY, job.target(), "the exclusion is per key");
    }

    @Test
    void everyRerouteIntoStoragePassesTheExcludedBay() {
        for (JobType type : List.of(JobType.STORE, JobType.RETRIEVE, JobType.RELOCATE)) {
            Optional<RerouteTarget<RackPosition>> without = planner.planReroute(input().build(), BOX, 1, type, null);
            assertEquals(Optional.of(BAY), without.map(RerouteTarget::location),
                    type + ": without the exclusion the reroute takes the bay");
            insertCalls.clear();
            Optional<RerouteTarget<RackPosition>> with = planner.planReroute(
                    input().storeExcluded(BOXES_KEPT_OUT_OF_THE_BAY).build(), BOX, 1, type, null);
            assertEquals(Optional.of(CHEST), with.map(RerouteTarget::location),
                    type + ": with it the leftover goes to the chest");
            assertFalse(insertCalls.contains(BAY), type + ": and the bay is never asked");
        }
    }

    @Test
    void anOptimizerNeverRelocatesABoxIntoTheExcludedBay() {
        stock.update(OVERFLOW, slots(26, BOX, 1));
        extractable.put(OVERFLOW, 1);
        priorities.put(BAY, 5);
        priorities.put(CHEST, 3);
        PlannerInput.RelocateNeed<String> need = new PlannerInput.RelocateNeed<>(new UUID(0L, 1L), BOX, 1);

        TransportJob<String, RackPosition> without = planner.plan(input().relocations(List.of(need)).build()).job()
                .orElseThrow().job();
        assertEquals(JobType.RELOCATE, without.type());
        assertEquals(BAY, without.target(), "without the exclusion the best place is the bay");

        TransportJob<String, RackPosition> with = planner.plan(input().relocations(List.of(need))
                .storeExcluded(BOXES_KEPT_OUT_OF_THE_BAY).build()).job().orElseThrow().job();
        assertEquals(JobType.RELOCATE, with.type());
        assertEquals(OVERFLOW, with.source());
        assertEquals(CHEST, with.target(), "with it the next better place");
    }

    /**
     * Review fix (conservation MAJOR 2): when the excluded bay is the only place a box that already left the warehouse
     * can go back to, a reroute of a retrieve, a supply or a relocation ranks it last instead of dropping it, so the
     * crane never holds the box for ever. A store reroute still keeps out of it: its items go back into an input.
     */
    @Test
    void aRerouteFallsBackOnTheExcludedBayWhenNothingElseTakesTheBox() {
        for (JobType type : List.of(JobType.RETRIEVE, JobType.SUPPLY, JobType.RELOCATE)) {
            insertCalls.clear();
            Optional<RerouteTarget<RackPosition>> back = planner.planReroute(onlyTheBay()
                    .storeExcluded(BOXES_KEPT_OUT_OF_THE_BAY).build(), BOX, 1, type, null);
            assertEquals(Optional.of(BAY), back.map(RerouteTarget::location),
                    type + ": the box goes back into the bay, the only place left");
        }
        Optional<RerouteTarget<RackPosition>> store = planner.planReroute(onlyTheBay()
                .storeExcluded(BOXES_KEPT_OUT_OF_THE_BAY).build(), BOX, 1, JobType.STORE, null);
        assertEquals(Optional.of(INPUT), store.map(RerouteTarget::location),
                "a store reroute takes the box back to an input, never into the bay");
    }

    /** Review fix (tests-ux 2): a box with only an excluded bay left reads as needing a Package Filter, not as full. */
    @Test
    void theStorePlanNamesThePackageFilterWhenOnlyAnExcludedBayIsLeft() {
        PlanResult<String, RackPosition> result = planner.plan(onlyTheBay().inputBuffers(location -> slots(0, BOX, 1))
                .storeExcluded(BOXES_KEPT_OUT_OF_THE_BAY).build());
        assertTrue(result.job().isEmpty(), "no job: " + result);
        assertEquals(Optional.of(NoJobReason.PACKAGE_BAY_NEEDS_FILTER), result.primaryReason());
        PlanResult<String, RackPosition> plain = planner.plan(onlyTheBay().inputBuffers(location -> slots(0, BOX, 1))
                .build());
        assertEquals(BAY, plain.job().orElseThrow().job().target(), "without the exclusion the bay takes it");
    }

    /** {@link #input()} with the bay as the only storage location. */
    private PlannerInput.Builder<String, RackPosition> onlyTheBay() {
        return input().storageLocations(List.of(BAY));
    }
}
