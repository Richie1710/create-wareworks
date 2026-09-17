package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one run of a player's machine turns into what ({@code docs/warehouse-system.md} §3.5, ADR-024): the ingredients
 * of one run and exactly one result.
 * <p>
 * <b>Wareworks does not craft.</b> A pattern is not a recipe the mod executes: it only says which items have to be
 * <i>delivered</i> to a production station and which item is expected to come back into the warehouse through a normal
 * warehouse input afterwards. The crafting itself is done by the player's Create machinery, and nothing here checks
 * that the pattern matches a real recipe — a wrong pattern simply never produces its result and the order times out.
 * <p>
 * <b>Authored as a 3 x 3 grid, planned as a multiset.</b> A player fills a {@value #GRID_SIZE}-cell grid that reads
 * like a vanilla crafting recipe, because that is how recipes are written down. What the planner needs is something
 * else: <i>how many of each item</i> one run costs, because the machine arranges them itself and the crane carries one
 * item key per trip. {@link #fromGrid} is the bridge — it merges cells naming the same item into one
 * {@link ProductionEntry}, so three cells of one plank each become "3 planks" and cost <b>one</b> crane trip instead
 * of three.
 * <p>
 * Two rules make the pattern usable for planning, both enforced at construction:
 * <ul>
 * <li><b>the ingredients are distinct</b> after merging, so the amount needed for a run is one number per item;</li>
 * <li><b>the result must not be one of the ingredients.</b> Such a pattern would let {@link ProduciblePlanner} promise
 * an item out of itself, and stage 1 has no recursion that could ever resolve it (the result would be produced from a
 * result that is produced from …). Refusing it at construction keeps that loop unrepresentable instead of guarding
 * against it in every reader.</li>
 * </ul>
 * Immutable and pure: no Minecraft types, so the whole producible computation is unit tested without a game.
 *
 * @param ingredients what one run consumes, 1..{@value #MAX_INGREDIENTS} entries with distinct keys
 * @param result      what one run is expected to yield
 * @param <K>         item key type
 */
public record ProductionPattern<K>(List<ProductionEntry<K>> ingredients, ProductionEntry<K> result) {
    /** Edge length of the authoring grid: a pattern reads like a vanilla crafting recipe. */
    public static final int GRID_WIDTH = 3;
    /** Cells of the authoring grid, {@value #GRID_WIDTH} x {@value #GRID_WIDTH}. */
    public static final int GRID_SIZE = GRID_WIDTH * GRID_WIDTH;
    /**
     * Distinct ingredients one pattern may name. It equals {@link #GRID_SIZE}, because that is the most distinct items
     * the grid can hold — cells naming the same item merge into one ingredient ({@link #fromGrid}).
     */
    public static final int MAX_INGREDIENTS = GRID_SIZE;

    public ProductionPattern {
        ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
        Objects.requireNonNull(result, "result");
        if (ingredients.isEmpty() || ingredients.size() > MAX_INGREDIENTS)
            throw new IllegalArgumentException("a pattern needs 1.." + MAX_INGREDIENTS + " ingredients: "
                    + ingredients.size());
        List<K> seen = new ArrayList<>(ingredients.size());
        for (ProductionEntry<K> ingredient : ingredients) {
            if (seen.contains(ingredient.key()))
                throw new IllegalArgumentException("an ingredient may appear only once: " + ingredient.key());
            seen.add(ingredient.key());
        }
        if (seen.contains(result.key()))
            throw new IllegalArgumentException("a pattern must not produce one of its own ingredients: "
                    + result.key());
    }

    /**
     * The pattern a filled 3 x 3 grid describes: the cells that name the same item are merged into one ingredient,
     * keeping the order in which the items first appear in the grid (so the list reads the way the grid does).
     * <p>
     * This is the only place the grid layout matters. Everything downstream — the producible computation, the supply
     * lines, the crane — sees the merged multiset.
     *
     * @param cells  the filled cells in grid order, 1..{@value #GRID_SIZE} entries; keys may repeat, and the merged
     *               total of an item saturates at {@link ProductionEntry#MAX_COUNT}
     * @param result what one run is expected to yield
     * @throws IllegalArgumentException if there are no cells, more than {@value #GRID_SIZE} of them, or the result is
     *                                  one of the ingredients
     */
    public static <K> ProductionPattern<K> fromGrid(List<ProductionEntry<K>> cells, ProductionEntry<K> result) {
        Objects.requireNonNull(cells, "cells");
        if (cells.isEmpty() || cells.size() > GRID_SIZE)
            throw new IllegalArgumentException("a pattern grid holds 1.." + GRID_SIZE + " filled cells: "
                    + cells.size());
        Map<K, ProductionEntry<K>> merged = new LinkedHashMap<>();
        for (ProductionEntry<K> cell : cells) {
            Objects.requireNonNull(cell, "cell");
            merged.merge(cell.key(), cell, (existing, added) -> existing.plus(added.count()));
        }
        return new ProductionPattern<>(List.copyOf(merged.values()), result);
    }

    /** A pattern of one ingredient, e.g. one log to four planks. */
    public static <K> ProductionPattern<K> of(K ingredient, int ingredientCount, K result, int resultCount) {
        return new ProductionPattern<>(List.of(new ProductionEntry<>(ingredient, ingredientCount)),
                new ProductionEntry<>(result, resultCount));
    }

    /** Whether one run of this pattern yields {@code key}. */
    public boolean produces(K key) {
        return result.key().equals(key);
    }

    /** Whether one run of this pattern consumes {@code key}. */
    public boolean consumes(K key) {
        for (ProductionEntry<K> ingredient : ingredients) {
            if (ingredient.key().equals(key))
                return true;
        }
        return false;
    }

    /** Items one run consumes in total, over all ingredients. */
    public int ingredientItems() {
        long total = 0;
        for (ProductionEntry<K> ingredient : ingredients)
            total += ingredient.count();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * How many runs are needed for at least {@code amount} result items (at least 1). Saturates instead of
     * overflowing, so an absurd amount asks for as many runs as fit into an {@code int}.
     */
    public int runsFor(long amount) {
        if (amount <= 0L)
            return 1;
        long runs = (amount + result.count() - 1) / result.count();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, runs));
    }

    /**
     * What {@code runs} runs are expected to yield, saturating at {@link Integer#MAX_VALUE}.
     * <p>
     * There is deliberately no counterpart that returns the ingredient <b>totals</b> as {@link ProductionEntry}s: an
     * entry's count is bounded by one pattern's worth, while a total over many runs is not. Totals are computed where
     * they are used, as the {@code required} amount of a {@link SupplyLine} ({@link ProductionOrder#start}).
     */
    public int resultFor(int runs) {
        return result.totalFor(runs);
    }
}
