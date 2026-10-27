package dev.wareworks.content.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;

/**
 * The recipe of every filled container key a fill request has asked for, measured once and remembered (M31, issue #21,
 * ADR-073): which empty container it is filled from, with which fluid, and how much of it.
 * <p>
 * Nothing is looked up in a table. A filled key is <b>emptied</b> on a probe ({@code FluidContainers#drained}), and
 * the empty container that comes out is <b>filled</b> again with the same fluid and drained back
 * ({@code FluidContainers#fillsBack}): only a key that is exactly what its empty container fills to is refillable. A
 * filled key is deterministic per key, so the answer can be remembered; a bounded map is cleared when it fills (the
 * empty-container test's is sized to the item index, {@link KeyMemo}) and whenever the controller's aisle state is cleared, so nothing here outlives a reload. Never saved.
 * <p>
 * Server thread only.
 */
final class FillRecipes {
    /** Bound on the remembered keys; far above the handful of filled containers a warehouse is asked for. */
    static final int MAX_REMEMBERED = 256;

    private final Map<ItemKey, Optional<FillLedger.Recipe<ItemKey, FluidKey>>> known = new HashMap<>();
    /**
     * Whether an item key is an empty fluid container, per key: the terminal's fluid cells ask it of every item type in
     * stock, so it is sized to the item index ({@link #expectItemTypes}), not to {@link #MAX_REMEMBERED}.
     */
    private final KeyMemo<ItemKey> empties = new KeyMemo<>(FluidContainers::isEmptyContainer, MAX_REMEMBERED);
    /** What an empty container becomes once filled with a fluid, per pair; empty when it may not be filled whole. */
    private final Map<FillPair, Optional<FluidContainers.Filled>> fills = new HashMap<>();

    /** An empty container and a fluid it might be filled with. */
    private record FillPair(ItemKey empty, FluidKey fluid) {
    }

    /** The recipe of {@code filledKey}, or empty when it cannot be filled from an empty container of its own. */
    Optional<FillLedger.Recipe<ItemKey, FluidKey>> of(ItemKey filledKey) {
        Objects.requireNonNull(filledKey, "filledKey");
        Optional<FillLedger.Recipe<ItemKey, FluidKey>> recipe = known.get(filledKey);
        if (recipe != null)
            return recipe;
        recipe = measure(filledKey);
        if (known.size() >= MAX_REMEMBERED)
            known.clear();
        known.put(filledKey, recipe);
        return recipe;
    }

    /**
     * Whether {@code key} is an empty fluid container ({@code FluidContainers#isEmptyContainer}), remembered per key: the
     * terminal asks it of every item type in stock on every push, and the answer of a key never changes. The memo holds
     * every item type of the index once {@link #expectItemTypes} has been told how many there are, so a push without new
     * item types probes nothing.
     */
    boolean isEmptyContainer(ItemKey key) {
        return empties.test(Objects.requireNonNull(key, "key"));
    }

    /** Sizes the empty-container memo for a pass over an item index of {@code itemTypes} distinct keys. */
    void expectItemTypes(int itemTypes) {
        empties.expect(itemTypes);
    }

    /**
     * What the empty container {@code empty} becomes once filled with {@code fluid}, and how much it takes, or empty
     * when it may not be filled whole with it or would not drain back to exactly that ({@code FluidContainers
     * #fillsBack}). Measured on probes once per pair and remembered; the filled key's own recipe is remembered with it,
     * so a fill request for it never measures again.
     */
    Optional<FluidContainers.Filled> fillOf(ItemKey empty, FluidKey fluid) {
        Objects.requireNonNull(empty, "empty");
        Objects.requireNonNull(fluid, "fluid");
        FillPair pair = new FillPair(empty, fluid);
        Optional<FluidContainers.Filled> filled = fills.get(pair);
        if (filled != null)
            return filled;
        filled = isEmptyContainer(empty) ? FluidContainers.fillsBack(empty, fluid) : Optional.empty();
        if (fills.size() >= MAX_REMEMBERED)
            fills.clear();
        fills.put(pair, filled);
        return filled;
    }

    /** Forgets every answer. */
    void clear() {
        known.clear();
        empties.clear();
        fills.clear();
    }

    private static Optional<FillLedger.Recipe<ItemKey, FluidKey>> measure(ItemKey filledKey) {
        return FluidContainers.drained(filledKey).flatMap(drained -> FluidContainers
                .fillsBack(drained.emptied(), drained.fluid())
                .filter(filled -> filled.filled().equals(filledKey) && filled.millibuckets() == drained.millibuckets())
                .map(filled -> new FillLedger.Recipe<>(drained.emptied(), drained.fluid(), drained.millibuckets())));
    }
}
