package dev.wareworks.core.requester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * The open orders of one requester (issue #45): running, in the buffer, a grace, gone; the locks that keep an item from
 * being ordered twice; the dead-man switch that counts only live time and gives back only the requester's own share;
 * the give-back when the print stops; and what a save keeps.
 */
class RequesterOrdersTest {
    private static final String STONE = "stone";
    private static final String NAMED_STONE = "stone#named";
    private static final String RED_BANNER = "banner#red";
    private static final String BLUE_BANNER = "banner#blue";
    private static final String GLASS = "glass";
    private static final int GRACE = 200;
    private static final int TIMEOUT = 600;

    private final RequesterOrders<String, String> orders = new RequesterOrders<>(RequesterOrdersTest::itemOf);
    private final Map<UUID, RequesterOrders.Request> requests = new HashMap<>();
    private final Function<UUID, Optional<RequesterOrders.Request>> lookup = id -> Optional.ofNullable(requests.get(id));

    private static String itemOf(String key) {
        int variant = key.indexOf('#');
        return variant < 0 ? key : key.substring(0, variant);
    }

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private RequesterOrder<String> only() {
        assertEquals(1, orders.size());
        return orders.all().get(0);
    }

    private RequesterOrders.Advance<String> poll(long now, int liveStep, List<String> buffered) {
        return orders.advance(now, liveStep, lookup, buffered, GRACE, TIMEOUT);
    }

    @Test
    void anOrderRunsThenLiesInTheBufferThenWaitsItsGraceThenLeaves() {
        assertTrue(orders.place(STONE, false, id(1), 64, 0));
        requests.put(id(1), new RequesterOrders.Request(64, 30));
        assertTrue(poll(20, 20, List.of(STONE)).changed());
        assertEquals(RequesterOrder.Phase.RUNNING, only().phase());
        assertEquals(20, only().liveAge());

        requests.put(id(1), new RequesterOrders.Request(64, 64));
        poll(40, 20, List.of(STONE));
        assertEquals(RequesterOrder.Phase.IN_BUFFER, only().phase());
        assertFalse(poll(10_000, 20, List.of(STONE)).changed(), "an item in the buffer has no timeout");
        assertEquals(RequesterOrder.Phase.IN_BUFFER, only().phase());

        poll(10_020, 20, List.of());
        assertEquals(RequesterOrder.Phase.GRACE, only().phase());
        assertEquals(10_020 + GRACE, only().graceUntil());
        poll(10_020 + GRACE - 1, 20, List.of());
        assertEquals(1, orders.size());
        assertTrue(poll(10_020 + GRACE, 20, List.of()).changed());
        assertTrue(orders.isEmpty());
    }

    @Test
    void aRequestThatIsGoneCountsAsDone() {
        orders.place(STONE, false, id(1), 64, 0);
        poll(20, 20, List.of(STONE));
        assertEquals(RequesterOrder.Phase.IN_BUFFER, only().phase(), "delivered or cancelled: the buffer decides");
    }

    @Test
    void aPhaseThatIsOverMovesOnInTheSamePoll() {
        orders.place(STONE, false, id(1), 64, 0);
        poll(20, 20, List.of());
        assertEquals(RequesterOrder.Phase.GRACE, only().phase(), "the funnel emptied the buffer between two polls");
        orders.clear();
        orders.place(STONE, false, id(2), 64, 0);
        orders.advance(40, 20, lookup, List.of(), 0, TIMEOUT);
        assertTrue(orders.isEmpty(), "a grace of nothing ends at once");
    }

    @Test
    void aShareMergedBehindAPlayerIsNotDoneWhenThePlayersItemsArrive() {
        orders.place(STONE, false, id(1), 64, 10);
        requests.put(id(1), new RequesterOrders.Request(74, 10));
        poll(20, 20, List.of(STONE));
        assertEquals(RequesterOrder.Phase.RUNNING, only().phase(), "the ten that arrived were the player's");
        assertEquals(RequesterStatus.ORDERED, orders.lock(STONE, false, List.of()).orElseThrow(),
                "no second order before the requester's own share arrived");
        requests.put(id(1), new RequesterOrders.Request(64, 0));
        poll(40, 20, List.of(STONE));
        assertEquals(0, only().ahead(), "the player's ten were given back: the share moves up");
    }

    @Test
    void anOrderOfTheSameItemLocksTheMissingItem() {
        orders.place(STONE, false, id(1), 64, 0);
        assertEquals(Optional.of(RequesterStatus.ORDERED), orders.lock(STONE, false, List.of()));
        assertEquals(Optional.of(RequesterStatus.ORDERED), orders.lock(NAMED_STONE, false, List.of()),
                "a plain order holds back any variant of its item");
        assertEquals(Optional.empty(), orders.lock(GLASS, false, List.of()));
    }

    @Test
    void theBufferLocksTheMissingItemWhoeverOrderedIt() {
        assertEquals(Optional.of(RequesterStatus.IN_BUFFER), orders.lock(STONE, false, List.of(GLASS, NAMED_STONE)),
                "a player's stone goes to the cannon all the same");
        assertEquals(Optional.of(RequesterStatus.IN_BUFFER), orders.lock(RED_BANNER, true, List.of(RED_BANNER)));
        assertEquals(Optional.empty(), orders.lock(RED_BANNER, true, List.of(BLUE_BANNER, "banner")),
                "a strict item is only held back by exactly its own stack");
    }

    @Test
    void strictOrdersMatchByTheirComponents() {
        orders.place(RED_BANNER, true, id(1), 1, 0);
        assertEquals(Optional.of(RequesterStatus.ORDERED), orders.lock(RED_BANNER, true, List.of()));
        assertEquals(Optional.empty(), orders.lock(BLUE_BANNER, true, List.of()), "another pattern is another item");
        assertEquals(Optional.of(RequesterStatus.ORDERED), orders.lock("banner", false, List.of()),
                "any banner serves a plain requirement, so the strict order holds it back");
    }

    @Test
    void theLockOfTheEarliestPhaseWins() {
        orders.place(STONE, false, id(1), 64, 0);
        poll(20, 20, List.of());
        orders.place(NAMED_STONE, false, id(2), 16, 0);
        assertEquals(Optional.of(RequesterStatus.ORDERED), orders.lock(STONE, false, List.of(STONE)));
        orders.restore(List.of(orders.all().get(0)));
        assertEquals(Optional.of(RequesterStatus.IN_BUFFER), orders.lock(STONE, false, List.of(STONE)));
        assertEquals(Optional.of(RequesterStatus.GRACE), orders.lock(STONE, false, List.of()));
    }

    @Test
    void fourOrdersAtMost() {
        for (int i = 1; i <= RequesterLimits.MAX_ORDERS; i++)
            assertTrue(orders.place("item" + i, false, id(i), 1, 0));
        assertTrue(orders.isFull());
        assertFalse(orders.place(GLASS, false, id(9), 1, 0));
        assertEquals(RequesterLimits.MAX_ORDERS, orders.size());
        assertEquals(Optional.of(RequesterStatus.ORDERS_FULL), orders.lock(GLASS, false, List.of()));
        assertEquals(Optional.of(RequesterStatus.ORDERED), orders.lock("item1", false, List.of()),
                "an order of the item itself says more than the full list");
    }

    @Test
    void theLiveAgeGrowsOnlyByTheLiveSteps() {
        orders.place(STONE, false, id(1), 64, 0);
        requests.put(id(1), new RequesterOrders.Request(64, 0));
        poll(20, RequesterLimits.liveStep(20, 0, 20), List.of());
        // The terminal was unloaded for a day: the requester did not advance its orders in between, and the next poll
        // that reaches it adds two intervals at most.
        poll(1_728_020, RequesterLimits.liveStep(1_728_020, 20, 20), List.of());
        assertEquals(60, only().liveAge());
        assertEquals(RequesterOrder.Phase.RUNNING, only().phase());
    }

    @Test
    void theDeadManSwitchGivesBackTheOwnOutstandingShareAndLowersTheCounter() {
        ChecklistNeed<String> need = new ChecklistNeed<>();
        // A player's 10 ahead, already delivered when the requester's 64 merged in; then 20 of the 64 arrived.
        orders.place(STONE, false, id(1), 64, 10, 10);
        need.add(STONE, 64);
        requests.put(id(1), new RequesterOrders.Request(74, 30));
        assertTrue(poll(300, 300, List.of()).gaveUp().isEmpty());
        RequesterOrders.Advance<String> advance = poll(600, 300, List.of());
        assertEquals(1, advance.gaveUp().size());
        RequesterOrders.GivenBack<String> gaveUp = advance.gaveUp().get(0);
        assertEquals(44, gaveUp.outstanding(), "only the requester's own share, never the player's ten");
        assertEquals(id(1), gaveUp.order().requestId());
        assertTrue(orders.isEmpty(), "no grace after a give-up: the same poll may order again");
        need.subtract(itemOf(gaveUp.order().key()), gaveUp.outstanding());
        assertEquals(20, need.ordered(STONE));
    }

    @Test
    void theDeadManSwitchNeverTakesOffAPlayersClickBehindAfterAShortfallAhead() {
        ChecklistNeed<String> need = new ChecklistNeed<>();
        // Player 1 clicked 20 (10 of them from production), the requester merged 64, player 2 clicked 20 more: 104.
        orders.place(STONE, false, id(1), 64, 20);
        need.add(STONE, 64);
        // Production fell 10 short on player 1's part (104 -> 94), and the crane brought 74: player 1's 10 and all
        // of the requester's 64. Only player 2's 20 are left, and the 94 cannot tell the shortfall from one behind.
        requests.put(id(1), new RequesterOrders.Request(94, 74));
        assertTrue(poll(300, 300, List.of()).gaveUp().isEmpty());
        assertEquals(RequesterOrder.Phase.RUNNING, only().phase(), "the credit waits, which only delays it");
        RequesterOrders.Advance<String> advance = poll(600, 300, List.of());
        assertEquals(1, advance.gaveUp().size(), "the switch still fires");
        assertEquals(0, advance.gaveUp().get(0).outstanding(), "but gives nothing back: player 2 keeps all 20");
        need.subtract(STONE, advance.gaveUp().get(0).outstanding());
        assertEquals(64, need.ordered(STONE));
    }

    @Test
    void aGiveBackTakesOnlyWhatIsProvablyTheOrdersOwn() {
        orders.place(STONE, false, id(1), 64, 20);
        requests.put(id(1), new RequesterOrders.Request(94, 74));
        assertTrue(orders.giveBack(lookup).isEmpty(), "nothing given back, not 10 of player 2's");
        assertTrue(orders.isEmpty());
    }

    @Test
    void theDeadManSwitchIsClamped() {
        orders.place(STONE, false, id(1), 64, 0);
        requests.put(id(1), new RequesterOrders.Request(64, 0));
        assertTrue(orders.advance(20, 300, lookup, List.of(), GRACE, 1).gaveUp().isEmpty(),
                "a timeout under the minimum counts as the minimum");
    }

    @Test
    void aStoppedPrintGivesBackTheRunningShares() {
        orders.place(STONE, false, id(1), 64, 0);
        orders.place(GLASS, false, id(2), 8, 4);
        orders.place(RED_BANNER, true, id(3), 1, 0);
        orders.place(BLUE_BANNER, true, id(4), 1, 0);
        requests.put(id(1), new RequesterOrders.Request(64, 14));
        requests.put(id(2), new RequesterOrders.Request(12, 12));
        requests.put(id(4), new RequesterOrders.Request(1, 0));
        poll(20, 20, List.of(GLASS));
        List<RequesterOrders.GivenBack<String>> back = orders.giveBack(lookup);
        assertEquals(List.of(STONE, BLUE_BANNER), back.stream().map(given -> given.order().key()).toList());
        assertEquals(List.of(50, 1), back.stream().map(RequesterOrders.GivenBack::outstanding).toList());
        assertTrue(orders.isEmpty(), "the glass in the buffer and the gone banner request are simply forgotten");
    }

    @Test
    void theLiveSharesAreSummedPerItem() {
        orders.place(STONE, false, id(1), 64, 0);
        orders.place(NAMED_STONE, false, id(2), 16, 0);
        orders.place(GLASS, false, id(3), 4, 0);
        assertEquals(Map.of(STONE, 80, GLASS, 4), orders.liveShares());
    }

    @Test
    void theStatusPrecedenceAndTheLamps() {
        assertEquals(RequesterStatus.NO_REMOTE, RequesterStatus.first(RequesterStatus.OUT_OF_STOCK,
                RequesterStatus.NO_REMOTE));
        assertEquals(RequesterStatus.ORDERED, RequesterStatus.first(RequesterStatus.ORDERED,
                RequesterStatus.CANNON_FULL));
        assertEquals(RequesterStatus.GRACE, RequesterStatus.first(null, RequesterStatus.GRACE));
        assertEquals(RequesterStatus.GRACE, RequesterStatus.first(RequesterStatus.GRACE, null));
        Set<RequesterStatus> red = EnumSet.of(RequesterStatus.NO_CANNON, RequesterStatus.SECOND_REQUESTER,
                RequesterStatus.SKIP_MISSING, RequesterStatus.NO_INVENTORY, RequesterStatus.LINK_OTHER_DIMENSION,
                RequesterStatus.LINK_OUT_OF_RANGE, RequesterStatus.LINK_NOT_LOADED, RequesterStatus.LINK_GONE,
                RequesterStatus.LINK_NOT_ALLOWED, RequesterStatus.NO_CONTROLLER, RequesterStatus.CANNON_FULL,
                RequesterStatus.BUFFER_FULL, RequesterStatus.OUT_OF_STOCK, RequesterStatus.RESERVED);
        Set<RequesterStatus> lit = EnumSet.of(RequesterStatus.ORDERED, RequesterStatus.IN_BUFFER,
                RequesterStatus.GRACE, RequesterStatus.ORDERS_FULL);
        for (RequesterStatus status : RequesterStatus.values()) {
            assertEquals(red.contains(status), status.isRefused(), status.name());
            assertEquals(lit.contains(status), status.isLit(), status.name());
            assertEquals(red.contains(status) ? RequesterStatus.Lamp.REFUSED
                    : lit.contains(status) ? RequesterStatus.Lamp.LIT : RequesterStatus.Lamp.DARK, status.lamp());
        }
    }

    @Test
    void theStatusAndThePhaseTravelByName() {
        for (RequesterStatus status : RequesterStatus.values())
            assertEquals(Optional.of(status), RequesterStatus.byName(status.serializedName()));
        for (RequesterOrder.Phase phase : RequesterOrder.Phase.values())
            assertEquals(Optional.of(phase), RequesterOrder.Phase.byName(phase.serializedName()));
        assertEquals("out_of_stock", RequesterStatus.OUT_OF_STOCK.serializedName());
        assertEquals(Optional.empty(), RequesterStatus.byName("exploded"));
        assertEquals(Optional.empty(), RequesterStatus.byName(null));
        assertEquals(Optional.empty(), RequesterOrder.Phase.byName(""));
    }

    @Test
    void theOrdersSurviveASave() {
        orders.place(STONE, false, id(1), 64, 10);
        orders.place(RED_BANNER, true, id(2), 1, 0);
        requests.put(id(1), new RequesterOrders.Request(74, 0));
        poll(20, 20, List.of());
        poll(40, 20, List.of());
        RequesterOrders<String, String> loaded = new RequesterOrders<>(RequesterOrdersTest::itemOf);
        loaded.restore(orders.all());
        assertEquals(orders.all(), loaded.all());
        assertEquals(40, loaded.all().get(0).liveAge());
        assertEquals(RequesterOrder.Phase.GRACE, loaded.all().get(1).phase());
    }

    @Test
    void aRestoreSkipsDuplicatesAndKeepsFourAtMost() {
        RequesterOrder<String> first = RequesterOrder.placed(STONE, false, id(1), 64, 0);
        RequesterOrder<String> stale = new RequesterOrder<>(GLASS, false, id(2), 8, 0, 0, RequesterOrder.Phase.RUNNING,
                5, 99L);
        orders.restore(Arrays.asList(first, null, first, stale, RequesterOrder.placed("a", false, id(3), 1, 0),
                RequesterOrder.placed("b", false, id(4), 1, 0), RequesterOrder.placed("c", false, id(5), 1, 0)));
        assertEquals(RequesterLimits.MAX_ORDERS, orders.size());
        assertEquals(List.of(id(1), id(2), id(3), id(4)), orders.all().stream().map(RequesterOrder::requestId).toList());
        assertEquals(0L, orders.all().get(1).graceUntil(), "only a grace keeps its end");
    }

    @Test
    void anOrderIsNeverEmpty() {
        assertThrows(IllegalArgumentException.class, () -> RequesterOrder.placed(STONE, false, id(1), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RequesterOrder.placed(STONE, false, id(1), 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new RequesterOrder<>(STONE, false, id(1), 1, 2, 3,
                RequesterOrder.Phase.RUNNING, 0, 0L));
        assertEquals(2, RequesterOrder.placed(STONE, false, id(1), 1, 2, 3).aheadDelivered(), "clamped to ahead");
        assertThrows(IllegalArgumentException.class,
                () -> new RequesterOrders.GivenBack<>(RequesterOrder.placed(STONE, false, id(1), 1, 0), -1));
    }
}
