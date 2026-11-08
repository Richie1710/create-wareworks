package dev.wareworks.core.requester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * How much one requester order asks for (issue #45, ADR-081): a stack at most, the schematic's remainder when less,
 * never below what the cannon reports missing, and never more than the cannon or the buffer can take; the stock first,
 * then at most what the schematic still needs made.
 */
class OrderAmountTest {
    private static final int STACK = 64;
    private static final long PLENTY = 10_000;

    private static OrderAmount plenty(int need) {
        return OrderAmount.of(need, STACK, PLENTY, PLENTY, PLENTY, PLENTY, 256);
    }

    @Test
    void anOrderIsOneStackAtMost() {
        int need = OrderAmount.need(STACK, 1, 80, 0, 0, false);
        assertEquals(80, need);
        assertEquals(OrderAmount.order(64), plenty(need));
    }

    @Test
    void theRemainderFollowsTheListAndWhatWasOrdered() {
        assertEquals(16, OrderAmount.need(STACK, 1, 80, 0, 64, false), "80 = 64 + 16");
        assertEquals(OrderAmount.order(16), plenty(16));
        assertEquals(75, OrderAmount.need(STACK, 1, 80, 5, 0, false), "5 already in the chest");
    }

    @Test
    void theNeedNeverDropsBelowWhatTheCannonMisses() {
        assertEquals(2, OrderAmount.need(STACK, 2, 10, 9, 0, false), "a double slab misses two");
        assertEquals(3, OrderAmount.need(STACK, 2, 10, 7, 0, false));
    }

    @Test
    void anItemUnknownToTheListIsOneStack() {
        assertEquals(64, OrderAmount.need(STACK, 1, 0, 0, 0, false));
        assertEquals(16, OrderAmount.need(16, 1, 0, 0, 0, false), "a banner stacks to 16");
        assertEquals(1, OrderAmount.need(0, 1, 0, 0, 0, false), "a stack is at least one");
    }

    @Test
    void aDamagingRequirementNeedsOne() {
        assertEquals(1, OrderAmount.need(1, 1, 200, 0, 0, true), "super glue is only worn down");
        assertEquals(1, OrderAmount.need(1, 1, 0, 0, 0, true));
    }

    @Test
    void aStaleCounterNeedsNothingByItself() {
        assertEquals(0, OrderAmount.need(STACK, 1, 80, 0, 80, false));
        assertEquals(0, OrderAmount.need(STACK, 4, 80, 40, 64, false));
    }

    @Test
    void theRoomTheBufferAndTheStockCapTheOrder() {
        assertEquals(10, OrderAmount.of(64, STACK, 10, PLENTY, PLENTY, PLENTY, 256).amount(), "the cannon's chest");
        assertEquals(5, OrderAmount.of(64, STACK, PLENTY, 5, PLENTY, PLENTY, 256).amount(), "the buffer");
        assertEquals(3, OrderAmount.of(64, STACK, PLENTY, PLENTY, 3, PLENTY, 256).amount(), "the stock");
        assertEquals(2, OrderAmount.of(64, STACK, PLENTY, PLENTY, PLENTY, PLENTY, 2).amount(), "the terminal's cap");
        assertEquals(16, OrderAmount.of(64, 16, PLENTY, PLENTY, PLENTY, PLENTY, 256).amount(), "the stack");
        assertEquals(7, OrderAmount.of(7, STACK, PLENTY, PLENTY, PLENTY, PLENTY, 256).amount(), "the need");
    }

    @Test
    void theFirstFactorAtZeroDecidesTheRefusal() {
        assertEquals(Optional.of(RequesterStatus.CANNON_FULL), OrderAmount.of(64, STACK, 0, 0, 0, 0, 256).refusal(),
                "nothing is ordered while the cannon's inventories are full");
        assertEquals(Optional.of(RequesterStatus.BUFFER_FULL),
                OrderAmount.of(64, STACK, PLENTY, 0, 0, 0, 256).refusal());
        assertEquals(Optional.of(RequesterStatus.RESERVED), OrderAmount.of(64, STACK, PLENTY, PLENTY, 0, 5, 256)
                .refusal(), "a reserve holds it back from automation, a player could still take it");
        assertEquals(Optional.of(RequesterStatus.OUT_OF_STOCK),
                OrderAmount.of(64, STACK, PLENTY, PLENTY, 0, 0, 256).refusal());
        assertEquals(Optional.of(RequesterStatus.OUT_OF_STOCK),
                OrderAmount.of(64, STACK, PLENTY, PLENTY, -3, 0, 256).refusal());
        OrderAmount refused = OrderAmount.of(64, STACK, 0, PLENTY, PLENTY, PLENTY, 256);
        assertFalse(refused.isOrder());
        assertEquals(0, refused.amount());
        assertTrue(plenty(1).isOrder());
    }

    @Test
    void anOrderAndARefusalAreNeverBoth() {
        assertThrows(IllegalArgumentException.class, () -> OrderAmount.of(0, STACK, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> OrderAmount.order(0));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderAmount(3, 0, Optional.of(RequesterStatus.CANNON_FULL)));
        assertThrows(IllegalArgumentException.class, () -> OrderAmount.order(3, 4), "more made than ordered");
        assertThrows(IllegalArgumentException.class, () -> OrderAmount.order(3, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderAmount(0, 1, Optional.of(RequesterStatus.OUT_OF_STOCK)), "a refusal makes nothing");
    }

    // --- the made part (ADR-081) ----------------------------------------------------------------------------------

    @Test
    void nothingToMakeIsExactlyTheOrderFromStock() {
        long[][] cases = { { 64, 10, PLENTY, PLENTY, 5 }, { 7, PLENTY, 5, 3, 0 }, { 64, PLENTY, PLENTY, 0, 5 },
                { 64, PLENTY, PLENTY, 0, 0 }, { 64, 0, PLENTY, PLENTY, PLENTY }, { 64, PLENTY, 0, PLENTY, 0 } };
        for (long[] c : cases) {
            OrderAmount stockOnly = OrderAmount.of((int) c[0], STACK, c[1], c[2], c[3], c[4], 256);
            assertEquals(stockOnly, OrderAmount.of((int) c[0], STACK, c[1], c[2], c[3], c[4], 256, 0));
            assertEquals(0, stockOnly.maxMade());
        }
        assertEquals(OrderAmount.order(9, 0), OrderAmount.order(9));
    }

    @Test
    void withNothingInStockTheNeedIsMade() {
        assertEquals(OrderAmount.order(16, 16), OrderAmount.of(16, STACK, PLENTY, PLENTY, 0, 0, 256, 16));
        assertEquals(OrderAmount.order(6, 6), OrderAmount.of(16, STACK, PLENTY, PLENTY, 0, 0, 256, 6),
                "no more made than the cap");
    }

    @Test
    void theStockComesFirstAndTheRestIsMade() {
        OrderAmount amount = OrderAmount.of(16, STACK, PLENTY, PLENTY, 5, 5, 256, 16);
        assertEquals(16, amount.amount(), "one order of sixteen");
        assertEquals(11, amount.maxMade(), "the five in stock first, eleven made");
        assertEquals(OrderAmount.order(16, 0), OrderAmount.of(16, STACK, PLENTY, PLENTY, 40, 40, 256, 16),
                "enough in stock: nothing made");
        assertEquals(OrderAmount.order(8, 3), OrderAmount.of(16, STACK, PLENTY, PLENTY, 5, 5, 256, 3),
                "the made part capped, the stock still ordered");
    }

    @Test
    void theMadePartIsCappedByTheNeedTheRoomsAndTheTerminal() {
        assertEquals(OrderAmount.order(7, 7), OrderAmount.of(7, STACK, PLENTY, PLENTY, 0, 0, 256, 64), "the need");
        assertEquals(OrderAmount.order(10, 8), OrderAmount.of(64, STACK, 10, PLENTY, 2, 2, 256, 64),
                "the cannon's chest");
        assertEquals(OrderAmount.order(5, 5), OrderAmount.of(64, STACK, PLENTY, 5, 0, 0, 256, 64), "the buffer");
        assertEquals(OrderAmount.order(2, 1), OrderAmount.of(64, STACK, PLENTY, PLENTY, 1, 1, 2, 64),
                "the terminal's cap");
        assertEquals(OrderAmount.order(16, 16), OrderAmount.of(64, 16, PLENTY, PLENTY, 0, 0, 256, 64), "the stack");
    }

    @Test
    void theRefusalsKeepTheirPrecedenceWithAMadePart() {
        assertEquals(Optional.of(RequesterStatus.CANNON_FULL),
                OrderAmount.of(64, STACK, 0, 0, 0, 0, 256, 64).refusal());
        assertEquals(Optional.of(RequesterStatus.BUFFER_FULL),
                OrderAmount.of(64, STACK, PLENTY, 0, PLENTY, 0, 256, 64).refusal());
        assertEquals(Optional.of(RequesterStatus.RESERVED), OrderAmount.of(64, STACK, PLENTY, PLENTY, 0, 5, 256, 0)
                .refusal(), "nothing to make and the stock reserved");
        assertEquals(Optional.of(RequesterStatus.OUT_OF_STOCK),
                OrderAmount.of(64, STACK, PLENTY, PLENTY, 0, 0, 256, -2).refusal());
        assertTrue(OrderAmount.of(64, STACK, PLENTY, PLENTY, 0, 5, 256, 1).isOrder(),
                "a reserve on the stock does not stop the made part");
    }

    @Test
    void theMadePartIsAlwaysWithinTheAmount() {
        for (int need = 1; need <= 70; need += 3)
            for (long available = -1; available <= 70; available += 7)
                for (int makeCap = -1; makeCap <= 70; makeCap += 9) {
                    OrderAmount amount = OrderAmount.of(need, STACK, 40, 50, available, available, 30, makeCap);
                    assertTrue(amount.maxMade() >= 0 && amount.maxMade() <= amount.amount(), amount.toString());
                    if (amount.isOrder())
                        assertEquals(Math.min(Math.min(need, 30), Math.max(0, available) + Math.max(0, makeCap)),
                                amount.amount(), amount.toString());
                }
    }
}
