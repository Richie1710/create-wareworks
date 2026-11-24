package dev.wareworks.core.production;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The fluid half of the Recipe Selector's pattern check (issue #55, ADR-089 amending ADR-085 D14): does a recipe's list
 * of fluid ingredients fit the fluid cells of a pattern exactly?
 * <p>
 * <b>Exactly</b> means a one-to-one pairing: every fluid ingredient of the recipe is matched by exactly one fluid cell
 * that names a fluid the ingredient accepts, with <b>the same millibuckets</b>, and no cell is left over. A recipe with
 * no fluid ingredient fits only a pattern without fluid cells. The amount matters because the machine's basin takes the
 * recipe's amount per run, and the warehouse delivers what the pattern says: a pattern of 500 mB for a 250 mB recipe
 * would make the line wait for fluid the recipe never consumes.
 * <p>
 * Pure Java: the fluid is a registry id as in {@link FluidCell}, and what an ingredient accepts is a predicate on ids,
 * so a Create fluid tag or a list of fluids can both stand behind it.
 */
public final class FluidNeeds {
    /** The most fluid ingredients a recipe may have for the check to pair them: a Basin takes two. */
    public static final int MAX_NEEDS = FluidCell.MAX_PER_PATTERN;

    /**
     * One fluid ingredient of a recipe.
     *
     * @param accepts     whether a fluid, by its registry id, satisfies the ingredient
     * @param millibuckets the amount one run takes, at least 1
     */
    public record Need(Predicate<String> accepts, int millibuckets) {
        public Need {
            Objects.requireNonNull(accepts, "accepts");
            if (millibuckets < 1)
                throw new IllegalArgumentException("millibuckets must be at least 1: " + millibuckets);
        }
    }

    private FluidNeeds() {
    }

    /**
     * Whether {@code needs} and {@code cells} pair up one to one, each pair accepting the cell's fluid with equal
     * millibuckets. More needs than {@value #MAX_NEEDS} never fit (a pattern has no more cells), and both lists empty
     * fit.
     */
    public static boolean fit(List<Need> needs, List<FluidCell> cells) {
        Objects.requireNonNull(needs, "needs");
        Objects.requireNonNull(cells, "cells");
        if (needs.size() != cells.size() || needs.size() > MAX_NEEDS)
            return false;
        return pair(needs, cells, new boolean[cells.size()], 0);
    }

    private static boolean pair(List<Need> needs, List<FluidCell> cells, boolean[] used, int need) {
        if (need == needs.size())
            return true;
        Need current = needs.get(need);
        for (int cell = 0; cell < cells.size(); cell++) {
            if (used[cell] || cells.get(cell).millibuckets() != current.millibuckets()
                    || !current.accepts().test(cells.get(cell).fluid()))
                continue;
            used[cell] = true;
            if (pair(needs, cells, used, need + 1))
                return true;
            used[cell] = false;
        }
        return false;
    }
}
