package dev.wareworks.core.requester;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Which stored variant a requester orders for a plain requirement (issue #45): the plain one first, then the one with
 * the most stock, ties by the stable order, and never one nothing is left of.
 */
class VariantPickTest {
    private static Optional<String> pick(Map<String, Long> stock) {
        return VariantPick.pick(stock.keySet(), key -> key.startsWith("stone"), key -> !key.contains("#"),
                key -> stock.getOrDefault(key, 0L), Comparator.naturalOrder());
    }

    @Test
    void thePlainVariantComesFirst() {
        assertEquals(Optional.of("stone"), pick(Map.of("stone#named", 500L, "stone", 3L, "dirt", 900L)));
    }

    @Test
    void otherwiseTheVariantWithTheMostStock() {
        assertEquals(Optional.of("stone#b"), pick(Map.of("stone", 0L, "stone#a", 10L, "stone#b", 40L)));
    }

    @Test
    void aTieGoesToTheStableOrder() {
        assertEquals(Optional.of("stone#a"), pick(Map.of("stone#c", 10L, "stone#a", 10L, "stone#b", 10L)));
    }

    @Test
    void nothingIsPickedWhenNothingIsLeft() {
        assertEquals(Optional.empty(), pick(Map.of("stone", 0L, "stone#a", -1L, "dirt", 64L)));
        assertEquals(Optional.empty(), VariantPick.pick(List.<String>of(), key -> true, key -> true, key -> 1L,
                Comparator.naturalOrder()));
    }
}
