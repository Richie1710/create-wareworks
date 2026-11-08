package dev.wareworks.core.requester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * What the schematic still needs as one requester can tell (issue #45): the material list minus its own orders, a
 * rebase onto the live shares whenever Create remakes the list, and a rate-limited refresh when the count went stale,
 * so the requester never stalls; and what of it may be made (ADR-081): the list's remainder in full, otherwise no more
 * than the cannon reports missing.
 */
class ChecklistNeedTest {
    private static final String STONE = "stone";
    private static final String GLASS = "glass";
    private static final int STACK = 64;
    private static final int REFRESH = 1_200;

    private final ChecklistNeed<String> need = new ChecklistNeed<>();

    /** A material list: what is required and gathered per item, and the fingerprint those sums give. */
    private record Material(Map<String, Long> required, Map<String, Long> gathered, long blocksToPlace)
            implements ChecklistNeed.Checklist<String> {
        static Material of(long stoneRequired, long stoneGathered) {
            return new Material(Map.of(STONE, stoneRequired), Map.of(STONE, stoneGathered), stoneRequired);
        }

        @Override
        public long required(String item) {
            return required.getOrDefault(item, 0L);
        }

        @Override
        public long gathered(String item) {
            return gathered.getOrDefault(item, 0L);
        }

        @Override
        public ChecklistNeed.Fingerprint fingerprint() {
            long r = required.values().stream().mapToLong(Long::longValue).sum();
            long g = gathered.values().stream().mapToLong(Long::longValue).sum();
            return new ChecklistNeed.Fingerprint(r, g, blocksToPlace);
        }
    }

    /** A refresh that hands out {@code fresh} and counts its calls. */
    private static final class Refresh implements Supplier<Material> {
        private final Material fresh;
        private int calls;

        Refresh(Material fresh) {
            this.fresh = fresh;
        }

        @Override
        public Material get() {
            calls++;
            return fresh;
        }
    }

    private static final Supplier<Material> NO_REFRESH = () -> {
        throw new AssertionError("the list must not be refreshed here");
    };

    @Test
    void theCounterAddsAndSubtracts() {
        need.add(STONE, 64);
        need.add(STONE, 16);
        assertEquals(80, need.ordered(STONE));
        need.subtract(STONE, 30);
        assertEquals(50, need.ordered(STONE));
        need.subtract(STONE, 500);
        assertEquals(0, need.ordered(STONE), "floored at nothing");
        need.add(STONE, 0);
        need.subtract(GLASS, 5);
        assertEquals(Map.of(), need.counts());
    }

    @Test
    void theRemainderCountsWhatWasOrdered() {
        Material list = Material.of(80, 0);
        assertEquals(80, need.need(STONE, STACK, 1, false, list, Map.of(), 0, REFRESH, NO_REFRESH));
        need.add(STONE, 64);
        assertEquals(16, need.need(STONE, STACK, 1, false, list, Map.of(STONE, 64), 20, REFRESH, NO_REFRESH),
                "the same list: no rebase, the counter stands");
    }

    @Test
    void aFreshListRebasesOntoTheLiveShares() {
        assertFalse(need.fingerprint().isPresent());
        assertTrue(need.observe(Material.of(80, 0).fingerprint(), Map.of()));
        need.add(STONE, 64);
        need.add(GLASS, 4);
        // Create remade the list: 48 reached the chest, 16 of this requester's shares are still on their way.
        assertEquals(16, need.need(STONE, STACK, 1, false, Material.of(80, 48), Map.of(STONE, 16), 40, REFRESH,
                NO_REFRESH), "80 - 48 gathered - 16 still on the way");
        assertEquals(16, need.ordered(STONE));
        assertEquals(0, need.ordered(GLASS), "an item without a live order starts afresh");
        assertFalse(need.observe(Material.of(80, 48).fingerprint(), Map.of()), "the same list rebases once");
    }

    @Test
    void theFallbackRefreshesOnlyWhenTheCounterIsStale() {
        need.observe(Material.of(80, 0).fingerprint(), Map.of());
        need.add(STONE, 80);
        // The player took 30 out of the chest: the counter says nothing is left, the cannon still misses stone.
        Refresh refresh = new Refresh(Material.of(80, 50));
        assertEquals(30, need.need(STONE, STACK, 1, false, Material.of(80, 0), Map.of(), 1_000, REFRESH, refresh));
        assertEquals(1, refresh.calls);
        assertEquals(1_000, need.lastRefresh());
        assertEquals(0, need.ordered(STONE), "the counter restarts from the live shares");
    }

    @Test
    void theRefreshIsRateLimitedAndOrdersWhatIsMissingInBetween() {
        need.observe(Material.of(80, 0).fingerprint(), Map.of());
        need.add(STONE, 80);
        Refresh stillNothing = new Refresh(Material.of(80, 0));
        assertEquals(2, need.need(STONE, STACK, 2, false, Material.of(80, 0), Map.of(STONE, 80), 1_000, REFRESH,
                stillNothing), "still nothing after the refresh: what the cannon reports missing");
        assertEquals(1, stillNothing.calls);
        need.add(STONE, 80);
        assertEquals(2, need.need(STONE, STACK, 2, false, Material.of(80, 0), Map.of(STONE, 80), 1_000 + REFRESH - 1,
                REFRESH, stillNothing), "within the pause: no second refresh");
        assertEquals(1, stillNothing.calls);
        assertTrue(need.mayRefresh(1_000 + REFRESH, REFRESH));
        assertTrue(need.mayRefresh(999, REFRESH), "a clock that went backwards may refresh");
        assertEquals(1, need.need(STONE, STACK, 0, false, Material.of(80, 0), Map.of(STONE, 160), 1_000 + REFRESH,
                REFRESH, stillNothing), "at least one");
        assertEquals(2, stillNothing.calls);
    }

    @Test
    void anItemOffTheListAndADamagingRequirementNeedNoRefresh() {
        assertEquals(STACK, need.need(GLASS, STACK, 1, false, Material.of(80, 0), Map.of(), 0, REFRESH, NO_REFRESH));
        assertEquals(1, need.need(STONE, 1, 1, true, Material.of(80, 0), Map.of(), 0, REFRESH, NO_REFRESH));
    }

    @Test
    void anItemPastTheCounterCapIsUnknownAndOneStack() {
        Map<String, Long> required = new HashMap<>();
        for (int i = 0; i < RequesterLimits.MAX_COUNTED_ITEMS; i++) {
            need.add("item" + i, 1);
            required.put("item" + i, 1L);
        }
        required.put(STONE, 3L);
        need.add(STONE, 3);
        assertFalse(need.counts(STONE));
        assertEquals(0, need.ordered(STONE), "not counted past the cap");
        Material list = new Material(required, Map.of(), 0);
        need.observe(list.fingerprint(), need.counts());
        assertEquals(STACK, need.need(STONE, STACK, 1, false, list, need.counts(), 0, REFRESH, NO_REFRESH));
        assertEquals(RequesterLimits.MAX_COUNTED_ITEMS, need.counts().size());
    }

    // --- what may be made (ADR-081) --------------------------------------------------------------------------------

    @Test
    void theListsRemainderMayBeMadeInFull() {
        Material list = Material.of(80, 5);
        assertEquals(new ChecklistNeed.Need(75, 75),
                need.needOf(STONE, STACK, 1, false, list, Map.of(), 0, REFRESH, NO_REFRESH));
        need.add(STONE, 64);
        assertEquals(new ChecklistNeed.Need(11, 11),
                need.needOf(STONE, STACK, 1, false, list, Map.of(STONE, 64), 20, REFRESH, NO_REFRESH));
        // A fresh list of 66 rebases onto the 64 still on their way: 2 left, but the cannon misses 3.
        assertEquals(new ChecklistNeed.Need(3, 3), need.needOf(STONE, STACK, 3, false, Material.of(66, 0),
                Map.of(STONE, 64), 40, REFRESH, NO_REFRESH), "the cannon's floor above the remainder");
    }

    @Test
    void anItemOffTheListIsOneStackButOnlyWhatIsMissingIsMade() {
        assertEquals(new ChecklistNeed.Need(STACK, 2),
                need.needOf(GLASS, STACK, 2, false, Material.of(80, 0), Map.of(), 0, REFRESH, NO_REFRESH));
        assertEquals(new ChecklistNeed.Need(STACK, 1),
                need.needOf(GLASS, STACK, 0, false, Material.of(80, 0), Map.of(), 0, REFRESH, NO_REFRESH));
    }

    @Test
    void anUncountedItemIsOneStackButOnlyWhatIsMissingIsMade() {
        Map<String, Long> required = new HashMap<>();
        for (int i = 0; i < RequesterLimits.MAX_COUNTED_ITEMS; i++) {
            need.add("item" + i, 1);
            required.put("item" + i, 1L);
        }
        required.put(STONE, 30L);
        Material list = new Material(required, Map.of(), 0);
        need.observe(list.fingerprint(), need.counts());
        assertEquals(new ChecklistNeed.Need(STACK, 4),
                need.needOf(STONE, STACK, 4, false, list, need.counts(), 0, REFRESH, NO_REFRESH));
    }

    @Test
    void aDamagingRequirementMakesOne() {
        assertEquals(new ChecklistNeed.Need(1, 1),
                need.needOf(STONE, 1, 3, true, Material.of(80, 0), Map.of(), 0, REFRESH, NO_REFRESH));
    }

    @Test
    void theStaleFallbackMakesOnlyWhatIsMissing() {
        need.observe(Material.of(80, 0).fingerprint(), Map.of());
        need.add(STONE, 80);
        Refresh stillNothing = new Refresh(Material.of(80, 0));
        assertEquals(new ChecklistNeed.Need(2, 2), need.needOf(STONE, STACK, 2, false, Material.of(80, 0),
                Map.of(STONE, 80), 1_000, REFRESH, stillNothing), "refreshed, and still nothing: what is missing");
        need.add(STONE, 80);
        assertEquals(new ChecklistNeed.Need(2, 2), need.needOf(STONE, STACK, 2, false, Material.of(80, 0),
                Map.of(STONE, 80), 1_001, REFRESH, stillNothing), "within the pause: what is missing");
        assertEquals(1, stillNothing.calls);
    }

    @Test
    void aRefreshedRemainderMayBeMadeInFull() {
        need.observe(Material.of(80, 0).fingerprint(), Map.of());
        need.add(STONE, 80);
        Refresh refresh = new Refresh(Material.of(80, 50));
        assertEquals(new ChecklistNeed.Need(30, 30), need.needOf(STONE, STACK, 1, false, Material.of(80, 0),
                Map.of(), 1_000, REFRESH, refresh), "the fresh list's remainder");
    }

    @Test
    void needIsTheAmountOfNeedOf() {
        Material list = Material.of(80, 5);
        ChecklistNeed<String> other = new ChecklistNeed<>();
        assertEquals(other.needOf(GLASS, STACK, 2, false, list, Map.of(), 0, REFRESH, NO_REFRESH).amount(),
                need.need(GLASS, STACK, 2, false, list, Map.of(), 0, REFRESH, NO_REFRESH));
        assertEquals(other.needOf(STONE, STACK, 1, false, list, Map.of(), 0, REFRESH, NO_REFRESH).amount(),
                need.need(STONE, STACK, 1, false, list, Map.of(), 0, REFRESH, NO_REFRESH));
    }

    @Test
    void aNeedMakesAtLeastOneAndNeverMoreThanItOrders() {
        assertThrows(IllegalArgumentException.class, () -> new ChecklistNeed.Need(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChecklistNeed.Need(4, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChecklistNeed.Need(4, 5));
    }

    @Test
    void theStateSurvivesASave() {
        need.observe(Material.of(80, 0).fingerprint(), Map.of());
        need.add(STONE, 64);
        need.need(STONE, STACK, 1, false, Material.of(80, 0), Map.of(STONE, 64), 300, REFRESH, NO_REFRESH);
        ChecklistNeed<String> loaded = new ChecklistNeed<>();
        loaded.restore(need.counts(), need.fingerprint(), need.lastRefresh());
        assertEquals(need.counts(), loaded.counts());
        assertEquals(need.fingerprint(), loaded.fingerprint());
        assertEquals(need.lastRefresh(), loaded.lastRefresh());
        assertEquals(16, loaded.need(STONE, STACK, 1, false, Material.of(80, 0), Map.of(STONE, 64), 320, REFRESH,
                NO_REFRESH));
        loaded.clear();
        assertEquals(Map.of(), loaded.counts());
        assertEquals(Optional.empty(), loaded.fingerprint());
        assertEquals(ChecklistNeed.NEVER, loaded.lastRefresh());
    }
}
