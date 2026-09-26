package dev.wareworks.core.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

/**
 * The availability function a retrieval request is clamped with (M15, issue #3): who may reach into a reserve, and
 * what a request for an item that can also be produced may ask for.
 * <p>
 * The split is the user's decision and the inverse of the first design study: {@link StockAccess#AUTOMATION} stops at
 * the reserve, a {@link StockAccess#PLAYER} does not.
 */
class StockAvailabilityTest {
    private static final String IRON = "iron";
    private static final String GOLD = "gold";

    private final Map<String, Long> available = new HashMap<>();
    /** How often the wrapped availability was asked, so a wrapper cannot quietly cost a second pass. */
    private final List<String> asked = new ArrayList<>();

    private long availableStock(String key) {
        asked.add(key);
        return available.getOrDefault(key, 0L);
    }

    private static StockRules<String> reserving(String key, long reserve) {
        return StockRules.of(List.of(new StockRule<>(key, StockRule.UNSET, StockRule.UNSET, reserve)));
    }

    @Test
    void withoutRulesEverythingIsAvailableToEveryone() {
        available.put(IRON, 32L);
        for (StockAccess access : StockAccess.values()) {
            ToLongFunction<String> availability = StockAvailability.of(StockRules.empty(), access,
                    this::availableStock);
            assertEquals(32, availability.applyAsLong(IRON), access.name());
            assertEquals(0, availability.applyAsLong(GOLD), "an unstocked key is simply 0");
        }
    }

    @Test
    void automationStopsAtTheReserveAndAPlayerDoesNot() {
        StockRules<String> rules = reserving(IRON, 10);
        available.put(IRON, 32L);
        available.put(GOLD, 7L);

        ToLongFunction<String> automation = StockAvailability.of(rules, StockAccess.AUTOMATION, this::availableStock);
        assertEquals(22, automation.applyAsLong(IRON));
        assertEquals(7, automation.applyAsLong(GOLD), "a key no rule governs is untouched");

        ToLongFunction<String> player = StockAvailability.of(rules, StockAccess.PLAYER, this::availableStock);
        assertEquals(32, player.applyAsLong(IRON), "a player is served down to the last item");
        assertEquals(10, rules.fromReserve(IRON, 32, 32), "and the row tells them how much of it is the reserve");
    }

    @Test
    void aReserveLargerThanTheStockLeavesAutomationNothing() {
        StockRules<String> rules = reserving(IRON, 64);
        available.put(IRON, 12L);
        assertEquals(0, StockAvailability.of(rules, StockAccess.AUTOMATION, this::availableStock).applyAsLong(IRON));
        assertEquals(12, StockAvailability.of(rules, StockAccess.PLAYER, this::availableStock).applyAsLong(IRON));
        assertEquals(12, rules.heldBack(IRON, 12), "never more than what is really there");
    }

    @Test
    void negativeAvailabilityCountsAsNothing() {
        available.put(IRON, -5L);
        assertEquals(0, StockAvailability.of(reserving(IRON, 4), StockAccess.AUTOMATION, this::availableStock)
                .applyAsLong(IRON));
        assertEquals(0, StockAvailability.of(StockRules.empty(), StockAccess.PLAYER, this::availableStock)
                .applyAsLong(IRON));
    }

    /**
     * A request may ask for the stock it can have <b>and</b> for what a pattern would still make. The producible
     * amount is added for the requested key alone and the reserve is not taken off it: a reserve holds back items
     * that are lying in the racks, and items nobody has made yet are not.
     */
    @Test
    void aRequestMayAlsoAskForWhatCouldStillBeProduced() {
        StockRules<String> rules = reserving(IRON, 10);
        available.put(IRON, 32L);
        available.put(GOLD, 7L);

        ToLongFunction<String> automation = StockAvailability.forRequest(rules, StockAccess.AUTOMATION,
                this::availableStock, IRON, 100);
        assertEquals(122, automation.applyAsLong(IRON), "22 above the reserve plus what could be made");
        assertEquals(7, automation.applyAsLong(GOLD), "the bonus is for the requested key only");

        assertEquals(132, StockAvailability.forRequest(rules, StockAccess.PLAYER, this::availableStock, IRON, 100)
                .applyAsLong(IRON));
        assertEquals(22, StockAvailability.forRequest(rules, StockAccess.AUTOMATION, this::availableStock, IRON, -3)
                .applyAsLong(IRON), "a negative producible amount adds nothing");
    }

    /** Even the key nobody asked for goes through the reserve, so a merge cannot slip past it (ADR-020). */
    @Test
    void everyKeyGoesThroughTheReserve() {
        StockRules<String> rules = StockRules.of(List.of(new StockRule<>(GOLD, StockRule.UNSET, StockRule.UNSET, 5L)));
        available.put(GOLD, 8L);
        assertEquals(3, StockAvailability.forRequest(rules, StockAccess.AUTOMATION, this::availableStock, IRON, 64)
                .applyAsLong(GOLD));
    }

    @Test
    void theWrappedAvailabilityIsAskedOncePerQuestion() {
        available.put(IRON, 32L);
        ToLongFunction<String> availability = StockAvailability.forRequest(reserving(IRON, 10),
                StockAccess.AUTOMATION, this::availableStock, IRON, 8);
        availability.applyAsLong(IRON);
        assertEquals(List.of(IRON), asked);
    }

    @Test
    void saturatingRatherThanOverflowing() {
        available.put(IRON, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, StockAvailability
                .forRequest(StockRules.empty(), StockAccess.PLAYER, this::availableStock, IRON, 1_000L)
                .applyAsLong(IRON));
    }

    @Test
    void argumentsAreChecked() {
        assertThrows(NullPointerException.class,
                () -> StockAvailability.of(null, StockAccess.PLAYER, this::availableStock));
        assertThrows(NullPointerException.class, () -> StockAvailability.of(StockRules.empty(), null,
                this::availableStock));
        assertThrows(NullPointerException.class, () -> StockAvailability.of(StockRules.empty(), StockAccess.PLAYER,
                null));
        assertThrows(NullPointerException.class, () -> StockAvailability.forRequest(StockRules.empty(),
                StockAccess.PLAYER, this::availableStock, null, 1L));
    }
}
