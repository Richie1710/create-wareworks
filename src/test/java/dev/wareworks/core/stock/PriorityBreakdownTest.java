package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.job.FilterMatch;

/**
 * The Warehouse Optimizer's view of one item (issue #46, ADR-079): the counts per priority, the unloaded bucket and the
 * greedy movable estimate, which follows the planner's strictly-better rule and never counts a room twice.
 */
class PriorityBreakdownTest {
    private static final int OPEN = FilterMatch.UNFILTERED.storeRank();
    private static final int DEDICATED = FilterMatch.DEDICATED.storeRank();
    private static final long NO_CAP = Long.MAX_VALUE;

    private static PriorityBreakdown.Holding holding(int priority, long count) {
        return new PriorityBreakdown.Holding(priority, OPEN, count, count);
    }

    private static PriorityBreakdown.Room room(int priority, long room) {
        return new PriorityBreakdown.Room(priority, OPEN, room);
    }

    @Test
    void countsAreSortedByPriorityWithTheUnloadedApart() {
        PriorityBreakdown view = PriorityBreakdown.of(List.of(holding(0, 40), holding(5, 10), holding(5, 6),
                holding(2, 3)), 12, List.of(), NO_CAP);
        assertEquals(40, view.countAt(0));
        assertEquals(3, view.countAt(2));
        assertEquals(16, view.countAt(5));
        assertEquals(0, view.countAt(9));
        assertEquals(0, view.countAt(10), "outside the range");
        assertEquals(PriorityBreakdown.PRIORITIES, view.counts().size());
        assertEquals(12, view.unloaded(), "a location nobody reads reports no priority, so it is not at 0");
        assertEquals(59, view.loaded());
        assertEquals(71, view.total());
        assertEquals(5, view.bestPriority());
        assertEquals(16, view.best());
        assertEquals(43, view.below());
    }

    @Test
    void anEmptyItemHasNoBestPriority() {
        PriorityBreakdown view = PriorityBreakdown.of(List.of(), 7, List.of(room(9, 64)), NO_CAP);
        assertEquals(-1, view.bestPriority());
        assertEquals(0, view.best());
        assertEquals(0, view.below());
        assertEquals(0, view.movable(), "nothing loaded, nothing to move");
        assertEquals(PriorityBreakdown.NONE.counts(), PriorityBreakdown.of(List.of(), 0, List.of(), 0).counts());
    }

    @Test
    void equalPriorityMovesNothing() {
        PriorityBreakdown view = PriorityBreakdown.of(List.of(holding(3, 64)), 0, List.of(room(3, 1000)), NO_CAP);
        assertEquals(64, view.free());
        assertEquals(0, view.movable());
        PriorityBreakdown lower = PriorityBreakdown.of(List.of(holding(3, 64)), 0, List.of(room(1, 1000)), NO_CAP);
        assertEquals(0, lower.movable(), "going down is never better");
    }

    @Test
    void roomsAreFilledFromTheBestTargetAndTheWorstSourceFirst() {
        // 30 at priority 0 and 30 at priority 4; 40 room at priority 9 and 25 at priority 5.
        PriorityBreakdown view = PriorityBreakdown.of(List.of(holding(4, 30), holding(0, 30)), 0,
                List.of(room(5, 25), room(9, 40)), NO_CAP);
        // The 9 takes all 30 of priority 0 first, then 10 of priority 4; the 5 takes the remaining 20 of priority 4.
        assertEquals(60, view.movable());

        // A room is counted once: two sources compete for the same 20.
        PriorityBreakdown shared = PriorityBreakdown.of(List.of(holding(0, 30), holding(1, 30)), 0,
                List.of(room(9, 20)), NO_CAP);
        assertEquals(20, shared.movable());

        // Greedy like the planner: priority 0 goes to the 9, its best target, and priority 6 then finds no room left,
        // because the 5 cannot take from it.
        PriorityBreakdown tiers = PriorityBreakdown.of(List.of(holding(6, 10), holding(0, 10)), 0,
                List.of(room(9, 10), room(5, 10)), NO_CAP);
        assertEquals(10, tiers.movable(), "the 9 fills from priority 0 first, so priority 6 finds no room left");
    }

    @Test
    void theFilterMatchOutranksThePriorityAsInThePlanner() {
        // 10 at priority 0 and 10 at priority 6, both in open chests; room for 10 in an open chest at priority 9 and
        // for 10 in a chest at priority 5 dedicated to the item. The planner sends priority 0 into the dedicated chest
        // (the filter ranks above the priority) and priority 6 into the 9: all 20 move, and the screen says so.
        PriorityBreakdown view = PriorityBreakdown.of(List.of(holding(0, 10), holding(6, 10)), 0,
                List.of(room(9, 10), new PriorityBreakdown.Room(5, DEDICATED, 10)), NO_CAP);
        assertEquals(20, view.movable());
    }

    @Test
    void reservedItemsAndTheMovableCapStayPut() {
        PriorityBreakdown reserved = PriorityBreakdown.of(List.of(new PriorityBreakdown.Holding(0, OPEN, 64, 20)), 0,
                List.of(room(9, 1000)), NO_CAP);
        assertEquals(20, reserved.free(), "a job's reservation at the location is left alone");
        assertEquals(20, reserved.movable());

        PriorityBreakdown capped = PriorityBreakdown.of(List.of(holding(0, 64), holding(1, 64)), 0,
                List.of(room(9, 1000)), 50);
        assertEquals(50, capped.free(), "requests and reserves keep the rest");
        assertEquals(50, capped.movable());

        assertEquals(0, PriorityBreakdown.of(List.of(holding(0, 64)), 0, List.of(room(9, 64)), 0).movable());
        assertEquals(0, PriorityBreakdown.of(List.of(holding(0, 64)), 0, List.of(room(9, 64)), -5).free());
    }

    @Test
    void theTargetMustBeNoWorseAFilterMatch() {
        PriorityBreakdown dedicated = PriorityBreakdown.of(
                List.of(new PriorityBreakdown.Holding(0, DEDICATED, 64, 64)), 0, List.of(room(9, 1000)), NO_CAP);
        assertEquals(0, dedicated.movable(), "a dedicated chest at priority 0 is where every new item goes");
        PriorityBreakdown toDedicated = PriorityBreakdown.of(
                List.of(new PriorityBreakdown.Holding(0, DEDICATED, 64, 64)), 0,
                List.of(new PriorityBreakdown.Room(9, DEDICATED, 1000)), NO_CAP);
        assertEquals(64, toDedicated.movable());
    }

    @Test
    void anUnknownRoomIsShownButNeverAdded() {
        PriorityBreakdown view = PriorityBreakdown.of(List.of(holding(0, 64)), 0,
                List.of(room(9, PriorityBreakdown.UNKNOWN_ROOM), room(5, 10)), NO_CAP);
        assertEquals(10, view.movable());
        assertTrue(view.movableUnknown(), "there is more that might fit");

        PriorityBreakdown full = PriorityBreakdown.of(List.of(holding(0, 10)), 0,
                List.of(room(9, 10), room(5, PriorityBreakdown.UNKNOWN_ROOM)), NO_CAP);
        assertEquals(10, full.movable());
        assertFalse(full.movableUnknown(), "everything already found a known room");

        PriorityBreakdown below = PriorityBreakdown.of(List.of(holding(5, 10)), 0,
                List.of(room(2, PriorityBreakdown.UNKNOWN_ROOM)), NO_CAP);
        assertFalse(below.movableUnknown(), "an unknown room below every source is no candidate at all");
    }

    @Test
    void valuesAreClamped() {
        PriorityBreakdown.Holding holding = new PriorityBreakdown.Holding(42, OPEN, 10, 20);
        assertEquals(PriorityBreakdown.MAX_PRIORITY, holding.priority());
        assertEquals(10, holding.free(), "free never exceeds the count");
        assertEquals(PriorityBreakdown.MIN_PRIORITY, new PriorityBreakdown.Room(-3, OPEN, -1).priority());
        assertEquals(0, new PriorityBreakdown.Room(-3, OPEN, -1).room());
        PriorityBreakdown odd = new PriorityBreakdown(List.of(-4L, 5L), -2, 3, 9, false);
        assertEquals(PriorityBreakdown.PRIORITIES, odd.counts().size());
        assertEquals(0, odd.countAt(0));
        assertEquals(5, odd.countAt(1));
        assertEquals(0, odd.unloaded());
        assertEquals(3, odd.movable(), "never more than is free");
    }
}
