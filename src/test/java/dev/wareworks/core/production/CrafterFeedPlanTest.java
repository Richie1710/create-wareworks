package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * Which pattern slot a Crafter Feeder serves an order from, and how much it lays at once (issue #34, ADR-067).
 */
class CrafterFeedPlanTest {
    private static final String COAL = "coal";
    private static final String STICK = "stick";
    private static final String TORCH = "torch";
    private static final String PLANK = "plank";
    private static final String STATION = "station";

    private final Supplier<UUID> lineIds = sequentialIds();

    private static Supplier<UUID> sequentialIds() {
        long[] next = {1};
        return () -> new UUID(0L, next[0]++);
    }

    private static ProductionPattern<String> torch() {
        return new ProductionPattern<>(List.of(new ProductionEntry<>(COAL, 1), new ProductionEntry<>(STICK, 1)),
                new ProductionEntry<>(TORCH, 4));
    }

    /** Two planks in one column make four sticks: two cells of one item, merged into one line of 2. */
    private static ProductionPattern<String> sticks() {
        return ProductionPattern.fromGrid(List.of(new ProductionEntry<>(PLANK, 1), new ProductionEntry<>(PLANK, 1)),
                new ProductionEntry<>(STICK, 4));
    }

    private ProductionOrder<String, String> order(ProductionPattern<String> pattern, int runs) {
        return ProductionOrder.start(UUID.randomUUID(), STATION, pattern, runs, lineIds, 0L, 100L, 0L, null);
    }

    @Test
    void anOrderPlannedFromAPatternMatchesItWithItsRuns() {
        assertEquals(OptionalInt.of(3), CrafterFeedPlan.runsFor(order(torch(), 3), torch()));
        assertEquals(OptionalInt.of(5), CrafterFeedPlan.runsFor(order(sticks(), 5), sticks()),
                "merged cells: the line is 2 planks per run");
    }

    @Test
    void anotherResultDoesNotMatch() {
        assertTrue(CrafterFeedPlan.runsFor(order(sticks(), 2), torch()).isEmpty());
    }

    @Test
    void anAmountThatIsNoWholeNumberOfRunsDoesNotMatch() {
        ProductionPattern<String> twoTorches = new ProductionPattern<>(torch().ingredients(),
                new ProductionEntry<>(TORCH, 3));
        // 3 runs of 4 torches = 12, which is no whole number of runs of 3 torches with these lines (4 runs would need
        // 4 coal, the order has 3).
        assertTrue(CrafterFeedPlan.runsFor(order(torch(), 3), twoTorches).isEmpty());
        ProductionPattern<String> fiveTorches = new ProductionPattern<>(torch().ingredients(),
                new ProductionEntry<>(TORCH, 5));
        assertTrue(CrafterFeedPlan.runsFor(order(torch(), 3), fiveTorches).isEmpty(), "12 is no multiple of 5");
    }

    @Test
    void anExtraOrMissingLineDoesNotMatch() {
        ProductionPattern<String> coalOnly = ProductionPattern.of(COAL, 1, TORCH, 4);
        assertTrue(CrafterFeedPlan.runsFor(order(torch(), 2), coalOnly).isEmpty(), "the order has a stick line more");
        assertTrue(CrafterFeedPlan.runsFor(order(coalOnly, 2), torch()).isEmpty(), "the order has a line less");
    }

    @Test
    void anotherAmountOfAnIngredientDoesNotMatch() {
        ProductionPattern<String> doubleCoal = new ProductionPattern<>(
                List.of(new ProductionEntry<>(COAL, 2), new ProductionEntry<>(STICK, 1)), new ProductionEntry<>(TORCH, 4));
        assertTrue(CrafterFeedPlan.runsFor(order(torch(), 2), doubleCoal).isEmpty());
    }

    @Test
    void theFirstValidSlotInSlotOrderWins() {
        ProductionOrder<String, String> torches = order(torch(), 2);
        List<Optional<ProductionPattern<String>>> slots = List.of(Optional.of(sticks()), Optional.empty(),
                Optional.of(torch()), Optional.of(torch()));
        assertEquals(Optional.of(new CrafterFeedPlan.Match(2, 2)), CrafterFeedPlan.match(torches, slots, slot -> true));
        assertEquals(Optional.of(new CrafterFeedPlan.Match(3, 2)),
                CrafterFeedPlan.match(torches, slots, slot -> slot != 2), "a slot whose check failed is skipped");
        assertEquals(Optional.empty(), CrafterFeedPlan.match(torches, slots, slot -> false));
    }

    @Test
    void aBatchIsAtMostAStackOfTheSmallestItem() {
        assertEquals(64, CrafterFeedPlan.batch(200, 64), "the 64 cap");
        assertEquals(64, CrafterFeedPlan.batch(200, 99), "never more than a stack, whatever an item claims");
        assertEquals(16, CrafterFeedPlan.batch(200, 16), "eggs");
        assertEquals(1, CrafterFeedPlan.batch(5, 1), "buckets: one run at a time");
        assertEquals(3, CrafterFeedPlan.batch(3, 64), "runs below the cap");
        assertEquals(0, CrafterFeedPlan.batch(0, 64), "nothing left to lay");
        assertEquals(1, CrafterFeedPlan.batch(5, 0), "a broken stack size still lays one");
    }

    @Test
    void aReloadedOrderLaysOnlyWhatIsLeft() {
        assertEquals(100, CrafterFeedPlan.remainingRuns(100, 0, 400, 4), "a fresh order lays all its runs");
        assertEquals(36, CrafterFeedPlan.remainingRuns(100, 64, 400, 4), "the station remembers 64 runs laid");
        assertEquals(36, CrafterFeedPlan.remainingRuns(100, 0, 144, 4), "256 torches already credited");
        assertEquals(1, CrafterFeedPlan.remainingRuns(100, 0, 1, 4), "a part of a run still needs the whole run");
        assertEquals(0, CrafterFeedPlan.remainingRuns(100, 100, 400, 4), "everything laid");
        assertEquals(0, CrafterFeedPlan.remainingRuns(100, 0, 0, 4), "nothing awaited any more");
        assertEquals(0, CrafterFeedPlan.remainingRuns(2, 5, 8, 4), "more laid than the order is never goes negative");
        assertEquals(2, CrafterFeedPlan.remainingRuns(2, -3, 8, 0), "broken inputs are bounded");
    }
}
