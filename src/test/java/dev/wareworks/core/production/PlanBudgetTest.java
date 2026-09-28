package dev.wareworks.core.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The one availability snapshot a plan is paid from (M20, {@code docs/warehouse-system.md} §3.5.6, ADR-032): spending
 * it, not spending it twice, and starting over without asking the world again.
 */
class PlanBudgetTest {
    private static final String LOG = "log";
    private static final String PLANK = "plank";

    @Test
    void whatIsTakenIsGone() {
        PlanBudget<String> budget = PlanBudget.ofMap(Map.of(LOG, 10L));
        assertEquals(10L, budget.available(LOG));
        assertEquals(4L, budget.take(LOG, 4L));
        assertEquals(6L, budget.available(LOG));
        assertEquals(4L, budget.spent(LOG));
        assertEquals(10L, budget.snapshot(LOG), "the snapshot is what the warehouse had, not what is left");
        assertEquals(4L, budget.spentItems());
    }

    /** A branch can never be promised more than is left, however much it asks for. */
    @Test
    void nothingIsPromisedTwice() {
        PlanBudget<String> budget = PlanBudget.ofMap(Map.of(LOG, 5L));
        assertEquals(5L, budget.take(LOG, 8L), "only what is there");
        assertEquals(0L, budget.available(LOG));
        assertEquals(0L, budget.take(LOG, 1L));
        assertEquals(5L, budget.spent(LOG));
    }

    @Test
    void takingNothingChangesNothing() {
        PlanBudget<String> budget = PlanBudget.ofMap(Map.of(LOG, 5L));
        assertEquals(0L, budget.take(LOG, 0L));
        assertEquals(0L, budget.take(LOG, -3L));
        assertTrue(budget.nothingSpent());
        assertTrue(budget.spentByKey().isEmpty());
    }

    @Test
    void negativeAvailabilityCountsAsNothing() {
        PlanBudget<String> budget = PlanBudget.of(key -> -7L);
        assertEquals(0L, budget.snapshot(LOG));
        assertEquals(0L, budget.available(LOG));
        assertEquals(0L, budget.take(LOG, 1L));
    }

    /** Every key is asked of the world once, so one plan cannot see two different warehouses. */
    @Test
    void theSnapshotIsTakenOncePerKey() {
        Map<String, Long> asked = new LinkedHashMap<>();
        Map<String, Long> world = new LinkedHashMap<>(Map.of(LOG, 10L));
        PlanBudget<String> budget = PlanBudget.of(key -> {
            asked.merge(key, 1L, Long::sum);
            return world.getOrDefault(key, 0L);
        });
        budget.take(LOG, 1L);
        world.put(LOG, 1000L); // the world moves on; the plan does not
        assertEquals(10L, budget.snapshot(LOG));
        assertEquals(9L, budget.available(LOG));
        assertEquals(Map.of(LOG, 1L), asked, "asked exactly once");
    }

    /** Starting over shares the snapshot and forgets the spending — how a plan is tried at a smaller size. */
    @Test
    void aFreshLedgerSharesTheSnapshot() {
        Map<String, Long> asked = new LinkedHashMap<>();
        PlanBudget<String> budget = PlanBudget.of(key -> {
            asked.merge(key, 1L, Long::sum);
            return 12L;
        });
        budget.take(LOG, 12L);
        PlanBudget<String> again = budget.fresh();
        assertTrue(again.nothingSpent());
        assertEquals(12L, again.available(LOG));
        assertEquals(Map.of(LOG, 1L), asked, "the world is not asked a second time");
        assertEquals(12L, budget.spent(LOG), "and the first ledger is untouched");
        again.take(LOG, 2L);
        assertEquals(12L, budget.spent(LOG));
    }

    @Test
    void whatWasSpentIsReportedInTheOrderItWasSpent() {
        PlanBudget<String> budget = PlanBudget.ofMap(Map.of(LOG, 10L, PLANK, 10L));
        budget.take(PLANK, 3L);
        budget.take(LOG, 2L);
        budget.take(PLANK, 1L);
        assertEquals(List.of(PLANK, LOG), List.copyOf(budget.spentByKey().keySet()));
        assertEquals(Map.of(PLANK, 4L, LOG, 2L), budget.spentByKey());
        assertEquals(6L, budget.spentItems());
        assertFalse(budget.nothingSpent());
        Map<String, Long> spent = budget.spentByKey();
        assertThrows(UnsupportedOperationException.class, () -> spent.put(LOG, 1L));
    }

    /**
     * A branch that is given up on leaves nothing behind: the planner falls back to another pattern for an item whose
     * subtree could not be made, and what that attempt spent has to be free again for the next one.
     */
    @Test
    void aLedgerCanBePutBackToAnEarlierMark() {
        PlanBudget<String> budget = PlanBudget.ofMap(Map.of(LOG, 10L, PLANK, 10L));
        budget.take(LOG, 4L);
        Map<String, Long> mark = budget.spentByKey();
        budget.take(LOG, 3L);
        budget.take(PLANK, 5L);
        assertEquals(3L, budget.available(LOG));
        budget.resetTo(mark);
        assertEquals(6L, budget.available(LOG), "what the abandoned branch took is free again");
        assertEquals(10L, budget.available(PLANK), "including a key it was the only one to touch");
        assertEquals(Map.of(LOG, 4L), budget.spentByKey());
        budget.resetTo(Map.of());
        assertTrue(budget.nothingSpent(), "and a mark from before anything was spent empties the ledger");
    }
}
