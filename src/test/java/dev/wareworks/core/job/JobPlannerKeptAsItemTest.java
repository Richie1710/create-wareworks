package dev.wareworks.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * {@link PlannerInput#keptAsItem()} (ADR-089 amendment): a fluid bay is not offered a filled container something waits
 * for as an item, on any path that stores into a location, an advisory reroute included, and the input says why when no
 * shelf takes it.
 */
class JobPlannerKeptAsItemTest {
    private static final String BUCKET = "water bucket";
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.25, 0.25, 0.5);

    private static final RackPosition INPUT = RackPosition.of(0, 0, Side.RIGHT);
    /** The fluid bay right beside the input: the place a filled container is poured into. */
    private static final RackPosition BAY = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition CHEST = RackPosition.of(8, 0, Side.LEFT);
    private static final RackPosition PORT = RackPosition.of(12, 0, Side.LEFT);

    private static final RackPosition OUTPUT = RackPosition.of(14, 0, Side.LEFT);
    private static final RackPosition DIVERSION = RackPosition.of(16, 0, Side.LEFT);

    private static final BiPredicate<RackPosition, String> AWAITED =
            (location, key) -> location.equals(BAY) && key.equals(BUCKET);

    private final StockIndex<String, RackPosition> stock = new StockIndex<>();
    private final ReservationLedger<String, RackPosition> ledger = new ReservationLedger<>();
    private final JobPlanner<String, RackPosition> planner = new JobPlanner<>(Function.identity(), sequentialIds());
    private final List<RackPosition> insertCalls = new ArrayList<>();

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1000};
        return () -> new UUID(0L, next[0]++);
    }

    private static InventorySnapshot<String> oneBucket() {
        InventorySnapshot.Builder<String> builder = InventorySnapshot.builder(1);
        builder.add(BUCKET, 1, 1, 1);
        return builder.build();
    }

    private PlannerInput.Builder<String, RackPosition> input() {
        return PlannerInput.builder(stock.readOnlyView(), ledger.readOnlyView())
                .crane(0, 0)
                .speeds(SPEEDS)
                .transferTicks(10)
                .carryLimit(key -> 1)
                .storageLocations(List.of(BAY, CHEST))
                .inputs(List.of(INPUT))
                .storeFilter((location, key) -> location.equals(BAY) ? FilterMatch.DEDICATED : FilterMatch.UNFILTERED)
                .liveExtract((location, key, max) -> max)
                .liveInsert((location, key, amount) -> {
                    insertCalls.add(location);
                    return amount;
                });
    }

    private PlannerInput.Builder<String, RackPosition> arriving() {
        return input().inputBuffers(location -> oneBucket());
    }

    @Test
    void nothingIsKeptOutByDefault() {
        PlannerInput<String, RackPosition> built = arriving().build();
        assertFalse(built.keptAsItem().test(BAY, BUCKET));
        assertEquals(BAY, planner.plan(built).job().orElseThrow().job().target());
    }

    @Test
    void theStorePlanTakesAnAwaitedContainerToAShelf() {
        TransportJob<String, RackPosition> job = planner.plan(arriving().keptAsItem(AWAITED).build())
                .job().orElseThrow().job();
        assertEquals(CHEST, job.target());
        assertFalse(insertCalls.contains(BAY), "the bay is never asked, so it is never remembered as refusing");
    }

    @Test
    void theCollectPlanTakesAnAwaitedContainerToAShelf() {
        TransportJob<String, RackPosition> job = planner.plan(input().inputs(List.of())
                .collectSources(List.of(PORT)).collectBuffers(location -> oneBucket())
                .keptAsItem(AWAITED).build()).job().orElseThrow().job();
        assertEquals(JobType.COLLECT, job.type());
        assertEquals(CHEST, job.target());
    }

    @Test
    void aRerouteTakesAnAwaitedContainerToAPlaceThatKeepsIt() {
        for (JobType type : JobType.values()) {
            Optional<RerouteTarget<RackPosition>> with = planner.planReroute(input().keptAsItem(AWAITED).build(),
                    BUCKET, 1, type, null);
            assertEquals(Optional.of(CHEST), with.map(RerouteTarget::location), type + " goes to the shelf");
        }
    }

    @Test
    void aRerouteWouldRatherPourThanLeaveTheCraneHolding() {
        for (JobType type : JobType.values()) {
            PlannerInput<String, RackPosition> only = input().storageLocations(List.of(BAY)).inputs(List.of())
                    .keptAsItem(AWAITED).build();
            assertEquals(Optional.empty(), planner.planRerouteKeepingItem(only, BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type + " has no place that keeps it");
            // A relocation never targets a bay at all, so it has no such last resort.
            assertEquals(type == JobType.RELOCATE ? Optional.empty() : Optional.of(BAY),
                    planner.planReroute(only, BUCKET, 1, type, null).map(RerouteTarget::location), type.name());
            assertEquals(Optional.empty(), planner.planReroute(only, BUCKET, 1, type, BAY)
                    .map(RerouteTarget::location), type + ": the failed bay is not offered again");
        }
    }

    /** An input the rule names keeps no item properly (a Recipe Selector's return input): the bay comes first. */
    @Test
    void anInputTheRuleNamesComesAfterTheBayAndBeforeAnExport() {
        BiPredicate<RackPosition, String> named = AWAITED.or((location, key) -> location.equals(INPUT));
        for (JobType type : new JobType[] {JobType.STORE, JobType.COLLECT}) {
            PlannerInput.Builder<String, RackPosition> built = withPort(input().storageLocations(List.of(BAY))
                    .keptAsItem(named), -1);
            assertEquals(Optional.empty(), planner.planRerouteKeepingItem(built.build(), BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type + ": no reason to refuse the bay at the drop");
            assertEquals(Optional.of(BAY), planner.planReroute(built.build(), BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type.name());
            // The refused bay still takes it: one hold retry, not the named input and not the port.
            built.allOrNothing(BAY::equals);
            assertEquals(Optional.empty(), planner.planReroute(built.build(), BUCKET, 1, type, BAY)
                    .map(RerouteTarget::location), type + ": held for one retry");
            // No bay takes it: the input, as without the rule.
            built.liveInsert((location, key, amount) -> location.equals(BAY) ? 0 : amount);
            assertEquals(Optional.of(INPUT), planner.planReroute(built.build(), BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type + ": the input when no bay can take it");
        }
    }

    @Test
    void anInputKeepsABucketAStoreRerouteCanReturn() {
        PlannerInput<String, RackPosition> built = input().storageLocations(List.of(BAY)).keptAsItem(AWAITED).build();
        assertEquals(Optional.of(INPUT), planner.planReroute(built, BUCKET, 1, JobType.STORE, null)
                .map(RerouteTarget::location));
    }

    /** An accepting port: an overflow for a negative rank, a diversion for a positive one. */
    private static PlannerInput.Builder<String, RackPosition> withPort(PlannerInput.Builder<String, RackPosition> input,
            int rank) {
        return input.ports(List.of(PORT)).portRank(location -> location.equals(PORT) ? rank : 0);
    }

    @Test
    void anAwaitedContainerDoesNotLeaveThroughAnOverflow() {
        PlanResult<String, RackPosition> result = planner.plan(withPort(arriving()
                .storageLocations(List.of(BAY)).keptAsItem(AWAITED), -1).build());
        assertTrue(result.job().isEmpty());
        assertEquals(Optional.of(NoJobReason.AWAITED_AS_ITEM), result.primaryReason());
    }

    @Test
    void aStoreReroutePrefersABayThatTakesTheContainerToAnyPort() {
        for (int rank : new int[] {-1, 1}) {
            PlannerInput<String, RackPosition> built = withPort(input().storageLocations(List.of(BAY))
                    .inputs(List.of()).keptAsItem(AWAITED), rank).build();
            // A port exports it, so it is no place that keeps the item.
            assertEquals(Optional.empty(), planner.planRerouteKeepingItem(built, BUCKET, 1, JobType.STORE, null));
            assertEquals(Optional.of(BAY), planner.planReroute(built, BUCKET, 1, JobType.STORE, null)
                    .map(RerouteTarget::location), "port rank " + rank);
        }
    }

    @Test
    void aStoreRerouteUsesTheOverflowWhenNoBayCanTakeTheContainer() {
        // The bay is full, or capped by a fluid rule: it is passed over for the wait but could not take it anyway.
        PlannerInput<String, RackPosition> full = withPort(input().inputs(List.of())
                .liveInsert((location, key, amount) -> location.equals(PORT) ? amount : 0).keptAsItem(AWAITED), -1)
                .build();
        for (RackPosition failed : new RackPosition[] {null, CHEST, BAY}) {
            assertEquals(Optional.of(PORT), planner.planReroute(full, BUCKET, 1, JobType.STORE, failed)
                    .map(RerouteTarget::location), "failed target " + failed);
        }
    }

    @Test
    void aRefusedBayThatStillTakesTheCarryIsRetriedBeforeAnExport() {
        // The place the bay was refused for is gone again: the answer is one hold retry, which offers the bay. The
        // failed target itself is never answered, and nothing is exported meanwhile.
        for (JobType type : new JobType[] {JobType.STORE, JobType.RETRIEVE, JobType.FILL}) {
            PlannerInput<String, RackPosition> built = withPort(input().storageLocations(List.of(BAY))
                    .inputs(List.of()).outputs(List.of(OUTPUT)).allOrNothing(BAY::equals).keptAsItem(AWAITED), -1)
                    .build();
            assertEquals(Optional.empty(), planner.planReroute(built, BUCKET, 1, type, BAY), type.name());
            assertEquals(Optional.of(BAY), planner.planReroute(built, BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type.name());
        }
    }

    @Test
    void aRetrieveOrFillReroutePrefersABayToAnOutputThatNeverAsked() {
        for (JobType type : new JobType[] {JobType.RETRIEVE, JobType.FILL}) {
            PlannerInput.Builder<String, RackPosition> built = input().outputs(List.of(OUTPUT)).keptAsItem(AWAITED);
            assertEquals(Optional.of(CHEST), planner.planReroute(built.build(), BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type + ": a shelf comes first");
            built.storageLocations(List.of(BAY));
            assertEquals(Optional.empty(), planner.planRerouteKeepingItem(built.build(), BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type + ": the output is no reason to refuse the bay at the drop");
            assertEquals(Optional.of(BAY), planner.planReroute(built.build(), BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type.name());
            built.liveInsert((location, key, amount) -> location.equals(BAY) ? 0 : amount);
            assertEquals(Optional.of(OUTPUT), planner.planReroute(built.build(), BUCKET, 1, type, null)
                    .map(RerouteTarget::location), type + ": the output when the bay cannot take it");
        }
    }

    /**
     * Every job type against every combination of targets: keeping the bay out never leaves a reroute without a target
     * where the ladder without that rule (0.28.0's) has one, and never answers anything but that ladder's target or a
     * shelf or an input, which keep the item in the warehouse.
     */
    @Test
    void noRerouteIsWorseThanWithoutTheRule() {
        List<RackPosition> places = List.of(CHEST, BAY, INPUT, OUTPUT, PORT, DIVERSION);
        for (int taking = 0; taking < 1 << places.size(); taking++) {
            Set<RackPosition> takes = new HashSet<>();
            for (int bit = 0; bit < places.size(); bit++) {
                if ((taking & 1 << bit) != 0)
                    takes.add(places.get(bit));
            }
            for (JobType type : JobType.values()) {
                for (RackPosition failed : new RackPosition[] {null, CHEST, BAY, INPUT, OUTPUT, PORT}) {
                    Supplier<PlannerInput.Builder<String, RackPosition>> base = () -> input()
                            .outputs(List.of(OUTPUT))
                            .ports(List.of(PORT, DIVERSION))
                            .portRank(location -> location.equals(PORT) ? -1 : location.equals(DIVERSION) ? 1 : 0)
                            .relocateTarget((location, key) -> !location.equals(BAY))
                            .liveInsert((location, key, amount) -> takes.contains(location) ? amount : 0);
                    String name = type + ", takes " + takes + ", failed " + failed;
                    Optional<RackPosition> before = planner.planReroute(base.get().build(), BUCKET, 1, type, failed)
                            .map(RerouteTarget::location);
                    PlannerInput<String, RackPosition> kept = base.get().keptAsItem(AWAITED).build();
                    Optional<RackPosition> after = planner.planReroute(kept, BUCKET, 1, type, failed)
                            .map(RerouteTarget::location);
                    if (before.isPresent() && after.isEmpty()) {
                        // The one allowed empty answer: the refused bay takes the carry, so the next retry gets a
                        // target.
                        assertTrue(BAY.equals(failed) && takes.contains(BAY), name + " held instead of " + before);
                        assertTrue(planner.planReroute(kept, BUCKET, 1, type, null).isPresent(),
                                name + " holds for good");
                        continue;
                    }
                    assertEquals(before.isPresent(), after.isPresent(), name);
                    if (before.isPresent()) {
                        RackPosition is = after.get();
                        assertTrue(is.equals(before.get()) || is.equals(CHEST) || is.equals(INPUT),
                                name + ": " + is + " instead of " + before.get());
                    }
                }
            }
        }
    }

    @Test
    void anOverflowTakesWhatNoBayIsPassedOverFor() {
        // Not awaited, and the bay takes nothing: the overflow, as before.
        assertEquals(PORT, planner.plan(withPort(arriving().storageLocations(List.of(BAY))
                .liveInsert((location, key, amount) -> location.equals(BAY) ? 0 : amount), -1).build())
                .job().orElseThrow().job().target());
        // Awaited, but no bay would take this container: nothing is poured instead of exported.
        assertEquals(PORT, planner.plan(withPort(arriving().storageLocations(List.of(BAY))
                .storeFilter((location, key) -> location.equals(BAY) ? FilterMatch.REJECTED : FilterMatch.UNFILTERED)
                .keptAsItem(AWAITED), -1).build()).job().orElseThrow().job().target());
        // At a stock rule's maximum no bay was ever a candidate.
        assertEquals(PORT, planner.plan(withPort(arriving().storeHeadroom(key -> 0L).keptAsItem(AWAITED), -1)
                .build()).job().orElseThrow().job().target());
    }

    @Test
    void aDiversionStillTakesAnAwaitedContainer() {
        assertEquals(PORT, planner.plan(withPort(arriving().keptAsItem(AWAITED), 1).build())
                .job().orElseThrow().job().target());
    }

    @Test
    void anInputSaysThatItsContainerIsAwaitedWhenNoShelfTakesIt() {
        PlanResult<String, RackPosition> only = planner.plan(arriving().storageLocations(List.of(BAY))
                .keptAsItem(AWAITED).build());
        assertTrue(only.job().isEmpty());
        assertEquals(Optional.of(NoJobReason.AWAITED_AS_ITEM), only.primaryReason());
        // A full shelf as well: still the reason that says why the bay with room is not used.
        PlanResult<String, RackPosition> full = planner.plan(arriving()
                .liveInsert((location, key, amount) -> 0).keptAsItem(AWAITED).build());
        assertEquals(Optional.of(NoJobReason.AWAITED_AS_ITEM), full.primaryReason());
        assertTrue(NoJobReason.AWAITED_AS_ITEM.refusesStoring());
        assertTrue(NoJobReason.AWAITED_AS_ITEM.refusesCollecting());
    }

    @Test
    void aBayThatWouldNotTakeTheContainerIsNoReason() {
        PlanResult<String, RackPosition> result = planner.plan(arriving().storageLocations(List.of(BAY))
                .storeFilter((location, key) -> FilterMatch.REJECTED).keptAsItem(AWAITED).build());
        assertEquals(Optional.of(NoJobReason.NO_MATCHING_FILTER), result.primaryReason());
    }
}
