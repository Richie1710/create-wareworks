package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.StockIndex;

/**
 * The merge need of {@link JobPlanner} (issue #57, ADR-088): an item spread over several places is carried into its
 * home, smallest holder first, never up or sideways into a worse place, and never into a place that cannot take the
 * whole of the source.
 */
class JobPlannerMergeTest {
    private static final String IRON = "iron";
    private static final int STACK = 64;
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    /** Index order is the list order: A, B, C, D, E. */
    private static final RackPosition A = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition B = RackPosition.of(2, 0, Side.LEFT);
    private static final RackPosition C = RackPosition.of(3, 0, Side.LEFT);
    private static final RackPosition D = RackPosition.of(7, 0, Side.LEFT);
    private static final RackPosition E = RackPosition.of(9, 0, Side.LEFT);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());
    private final Map<RackPosition, Integer> extractable = new HashMap<>();
    private final Map<RackPosition, Integer> insertable = new HashMap<>();
    private final Map<RackPosition, Long> estimates = new HashMap<>();
    private final List<RackPosition> extractCalls = new ArrayList<>();
    private final Map<RackPosition, Integer> priorities = new HashMap<>();
    private final Map<RackPosition, Set<String>> dedicated = new HashMap<>();
    private final Set<RackPosition> bays = new HashSet<>();
    private final Set<RackPosition> unavailable = new HashSet<>();
    private int carryLimit = STACK;

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1000};
        return () -> new UUID(0L, next[0]++);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private FilterMatch filterMatch(RackPosition location, String key) {
        Set<String> listed = dedicated.get(location);
        if (listed == null)
            return FilterMatch.UNFILTERED;
        return listed.contains(key) ? FilterMatch.DEDICATED : FilterMatch.REJECTED;
    }

    private void holds(RackPosition location, String key, int count) {
        List<Integer> stacks = new ArrayList<>();
        for (int left = count; left > 0; left -= STACK)
            stacks.add(Math.min(STACK, left));
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(27);
        for (int size : stacks)
            builder.add(key, size, STACK, STACK);
        for (int i = 0; i < 27 - stacks.size(); i++)
            builder.addEmpty(STACK);
        stock.update(location, builder.build());
        extractable.put(location, count);
    }

    private PlannerInput.Builder<String, RackPosition> input(List<PlannerInput.RelocateNeed<String>> needs) {
        for (RackPosition place : List.of(A, B, C, D, E))
            insertable.putIfAbsent(place, 1000);
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(0, 0)
                .speeds(SPEEDS)
                .transferTicks(10)
                .carryLimit(key -> carryLimit)
                .storageLocations(List.of(A, B, C, D, E))
                .storePriority(location -> priorities.getOrDefault(location, 0))
                .storeFilter(this::filterMatch)
                .available(location -> !unavailable.contains(location))
                .relocateTarget((location, key) -> !bays.contains(location))
                .insertEstimate((location, key) -> estimates.getOrDefault(location, JobPlanner.UNKNOWN_CAPACITY))
                .liveExtract((location, key, max) -> {
                    extractCalls.add(location);
                    return Math.min(max, extractable.getOrDefault(location, 0));
                })
                .liveInsert((location, key, amount) -> Math.min(amount, insertable.getOrDefault(location, 0)))
                .relocations(needs);
    }

    private static PlannerInput.RelocateNeed<String> merge(long id, String key, long movable, long heldBack) {
        return new PlannerInput.RelocateNeed<>(id(id), key, movable, heldBack, true);
    }

    private PlanResult<String, RackPosition> plan(PlannerInput.RelocateNeed<String> need) {
        return planner.plan(input(List.of(need)).build());
    }

    @Test
    void theSmallestHolderIsEmptiedFirstIntoTheFullestPlaceAtOnePriority() {
        holds(A, IRON, 20);
        holds(B, IRON, 10);
        holds(C, IRON, 50);

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 80, 0)).job().orElseThrow().job();

        assertEquals(JobType.RELOCATE, job.type());
        assertEquals(B, job.source(), "the smallest holder goes first");
        assertEquals(C, job.target(), "the fullest place is the home");
        assertEquals(10, job.plannedAmount());
    }

    @Test
    void aTieOfTheCountsGoesToTheLowerIndex() {
        holds(B, IRON, 30);
        holds(D, IRON, 30);

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 60, 0)).job().orElseThrow().job();

        assertEquals(D, job.source());
        assertEquals(B, job.target(), "equal counts: the lower index is home, not the nearer or the farther place");
    }

    @Test
    void theHighestPriorityHoldingItIsHomeWhateverTheCounts() {
        holds(A, IRON, 5);
        holds(E, IRON, 500);
        priorities.put(A, 2);

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 505, 0)).job().orElseThrow().job();

        assertEquals(E, job.source());
        assertEquals(A, job.target());
    }

    @Test
    void aPlaceWhoseFilterNamesTheItemIsItsHomeEvenWhenEmpty() {
        holds(B, IRON, 40);
        holds(C, IRON, 90);
        dedicated.put(D, Set.of(IRON));

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 130, 0)).job().orElseThrow().job();

        assertEquals(B, job.source());
        assertEquals(D, job.target(), "the dedicated place is home before any holder");
    }

    @Test
    void aSourceAboveTheHomeIsNeverMovedDown() {
        dedicated.put(D, Set.of(IRON));
        holds(C, IRON, 30);
        holds(D, IRON, 10);
        priorities.put(C, 4);

        PlanResult<String, RackPosition> result = plan(merge(1, IRON, 40, 0));

        assertFalse(result.hasJob(), "the source has a higher priority than its filter home");
        assertEquals(Map.of(id(1), RelocationReason.HOME_WORSE), result.relocationReasons());
    }

    @Test
    void stockARejectingPlaceStillHoldsIsEmptiedIntoThePlainHome() {
        holds(B, IRON, 10);
        holds(C, IRON, 40);
        dedicated.put(B, Set.of("gold"));

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 50, 0)).job().orElseThrow().job();

        assertEquals(B, job.source(), "the rejected stock is emptied into the plain home");
        assertEquals(C, job.target());
    }

    @Test
    void roomAnotherCraneReservedAtTheHomeCountsSoTheHomeDoesNotFlip() {
        holds(A, IRON, 10);
        holds(C, IRON, 12);
        // Another crane is carrying 8 iron (already out of the index) towards A: A will hold 18, more than C's 12.
        ledger.reserveCapacity(id(500), A, IRON, 8);

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 22, 0)).job().orElseThrow().job();

        assertEquals(A, job.target(), "the home stays A while the trip towards it is in the air");
        assertEquals(C, job.source());
    }

    @Test
    void stockAnotherCraneIsCarryingAwayDoesNotCountTowardsTheHome() {
        holds(A, IRON, 14);
        holds(C, IRON, 12);
        ledger.reserveStock(id(501), A, IRON, 8);

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 26, 0)).job().orElseThrow().job();

        assertEquals(C, job.target(), "A will hold 6 once the trip has picked, so C is the home");
    }

    @Test
    void aHomeWithoutRoomForTheWholeSourceTakesNothing() {
        holds(A, IRON, 10);
        holds(C, IRON, 50);
        estimates.put(C, 5L);

        PlanResult<String, RackPosition> result = plan(merge(1, IRON, 60, 0));

        assertFalse(result.hasJob());
        assertEquals(Map.of(id(1), RelocationReason.HOME_FULL), result.relocationReasons());
        assertEquals(List.of(), extractCalls, "no live call for a source that cannot fit");
    }

    @Test
    void anItemInOnePlaceIsAlreadyMerged() {
        holds(C, IRON, 50);

        PlanResult<String, RackPosition> result = plan(merge(1, IRON, 50, 0));

        assertFalse(result.hasJob());
        assertEquals(Map.of(id(1), RelocationReason.ALREADY_ONE_PLACE), result.relocationReasons());
    }

    @Test
    void nothingInStockEndsAsNoneInStock() {
        PlanResult<String, RackPosition> result = plan(merge(1, IRON, 1, 0));

        assertEquals(Map.of(id(1), RelocationReason.NONE_IN_STOCK), result.relocationReasons());
    }

    @Test
    void anUnreachableHomeEndsTheItemInsteadOfMovingTheHome() {
        holds(A, IRON, 10);
        holds(C, IRON, 50);
        unavailable.add(C);

        PlanResult<String, RackPosition> result = plan(merge(1, IRON, 60, 0));

        assertFalse(result.hasJob(), "A is not made home because C cannot be reached now");
        assertEquals(Map.of(id(1), RelocationReason.NOT_REACHABLE), result.relocationReasons());
    }

    @Test
    void aFluidBayIsNeitherSourceNorHome() {
        holds(A, IRON, 10);
        holds(C, IRON, 50);
        holds(D, IRON, 500);
        bays.add(D);

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 560, 0)).job().orElseThrow().job();

        assertEquals(A, job.source());
        assertEquals(C, job.target(), "the bay with 500 is no home");
    }

    @Test
    void aPlacePackageModeKeepsTheItemOutOfIsNeitherSourceNorHome() {
        holds(A, IRON, 10);
        holds(C, IRON, 50);
        holds(D, IRON, 500);

        PlannerInput<String, RackPosition> input = input(List.of(merge(1, IRON, 560, 0)))
                .storeExcluded((location, key) -> location.equals(D)).build();
        TransportJob<String, RackPosition> job = planner.plan(input).job().orElseThrow().job();

        assertEquals(A, job.source());
        assertEquals(C, job.target(), "the excluded place with 500 is no home");
        assertTrue(!job.source().equals(D) && !job.target().equals(D), "and never a source");
    }

    @Test
    void whenEveryHolderButOneIsExcludedTheItemIsAlreadyInOnePlace() {
        holds(A, IRON, 10);
        holds(D, IRON, 500);

        PlanResult<String, RackPosition> result = planner.plan(input(List.of(merge(1, IRON, 510, 0)))
                .storeExcluded((location, key) -> location.equals(D)).build());

        assertFalse(result.hasJob());
        assertEquals(Map.of(id(1), RelocationReason.ALREADY_ONE_PLACE), result.relocationReasons());
    }

    @Test
    void spokenForStockKeepsAWorstPlaceFromBeingEmptied() {
        holds(A, IRON, 10);
        holds(B, IRON, 20);
        holds(C, IRON, 50);

        // 10 are spoken for: they stay in the worst place, the first by index at one priority.
        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 70, 10)).job().orElseThrow().job();

        assertEquals(B, job.source(), "A holds the spoken-for items, so it cannot be emptied");
        assertEquals(C, job.target());
    }

    @Test
    void aTripCarriesAtMostTheCarryLimitAndTheSourceStaysTheSmallest() {
        holds(A, IRON, 100);
        holds(C, IRON, 400);
        carryLimit = 64;

        TransportJob<String, RackPosition> job = plan(merge(1, IRON, 500, 0)).job().orElseThrow().job();

        assertEquals(A, job.source());
        assertEquals(64, job.plannedAmount());
    }

    @Test
    void anOptimiseNeedStillNeverMovesSideways() {
        holds(A, IRON, 10);
        holds(C, IRON, 50);

        PlanResult<String, RackPosition> result = plan(new PlannerInput.RelocateNeed<>(id(1), IRON, 60));

        assertFalse(result.hasJob());
        assertTrue(result.relocationReasons().containsKey(id(1)));
    }
}
