package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a recipe of any machine, as a recipe viewer shows it, into the grid of a production pattern: the viewer's "+" at
 * a production station (ADR-076). A crafting recipe keeps its own cells; this class is for everything else, a saw, a
 * press or a mixer, whose inputs have no place in a grid and which a pattern only reads as what goes in.
 * <p>
 * <b>The inputs fill the cells in order.</b> Inputs that accept the same items share a cell up to
 * {@value ProductionEntry#MAX_PER_CELL} items and spill into the next, so a recipe that needs 128 of something is two
 * cells and four slots of one ingredient are one. Nothing else is merged: the server picks one item per cell and merges
 * the rest when it plans ({@link ProductionPattern#fromGrid}).
 * <p>
 * <b>What a pattern cannot say is refused, and nothing is laid.</b> A pattern holds items only, in at most
 * {@value ProductionPattern#GRID_SIZE} cells, and must not make one of its own ingredients. A result that is only
 * <i>one of several</i> candidates of an input is dropped from that input's candidates instead, because the other
 * candidates still make a valid pattern.
 */
public final class PatternLay {
    /**
     * One input of a recipe.
     *
     * @param candidates the items it accepts, in the recipe's order; empty for an input that is no item, such as a fluid
     * @param count      how many it takes per run, at least 1
     */
    public record Input<K>(List<K> candidates, int count) {
        public Input {
            candidates = List.copyOf(candidates);
            if (count < 1)
                throw new IllegalArgumentException("count must be at least 1: " + count);
        }
    }

    /** Why a recipe cannot become a pattern. */
    public enum Refusal {
        /** An input is no item: a pattern holds items only. */
        NOT_ONLY_ITEMS,
        /** The inputs need more than {@value ProductionPattern#GRID_SIZE} cells. */
        TOO_MANY_INPUTS,
        /** Every candidate of an input is the result. */
        MAKES_ITS_INGREDIENT
    }

    /**
     * What a recipe became: either {@code refusal}, or the cells to lay.
     *
     * @param cells   exactly {@value ProductionPattern#GRID_SIZE} cells, each with its candidates; empty if refused
     * @param counts  the count of each cell, parallel to {@code cells}; empty if refused
     * @param refusal why nothing can be laid
     */
    public record Layout<K>(List<List<K>> cells, List<Integer> counts, Optional<Refusal> refusal) {
        private static <K> Layout<K> refused(Refusal refusal) {
            return new Layout<>(List.of(), List.of(), Optional.of(refusal));
        }
    }

    private PatternLay() {
    }

    /** Lays {@code inputs} out for a pattern that makes {@code result}. */
    public static <K> Layout<K> layout(List<Input<K>> inputs, K result) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(result, "result");
        List<List<K>> cells = new ArrayList<>(ProductionPattern.GRID_SIZE);
        List<Integer> counts = new ArrayList<>(ProductionPattern.GRID_SIZE);
        for (Input<K> input : inputs) {
            if (input.candidates().isEmpty())
                return Layout.refused(Refusal.NOT_ONLY_ITEMS);
            List<K> candidates = input.candidates().stream().filter(candidate -> !candidate.equals(result)).toList();
            if (candidates.isEmpty())
                return Layout.refused(Refusal.MAKES_ITS_INGREDIENT);
            int left = input.count();
            for (int cell = 0; cell < cells.size() && left > 0; cell++) {
                if (!cells.get(cell).equals(candidates))
                    continue;
                int taken = Math.min(left, ProductionEntry.MAX_PER_CELL - counts.get(cell));
                counts.set(cell, counts.get(cell) + taken);
                left -= taken;
            }
            while (left > 0) {
                int taken = Math.min(left, ProductionEntry.MAX_PER_CELL);
                cells.add(candidates);
                counts.add(taken);
                left -= taken;
            }
        }
        if (cells.size() > ProductionPattern.GRID_SIZE)
            return Layout.refused(Refusal.TOO_MANY_INPUTS);
        while (cells.size() < ProductionPattern.GRID_SIZE) {
            cells.add(List.of());
            counts.add(ProductionEntry.MIN_COUNT);
        }
        return new Layout<>(List.copyOf(cells), List.copyOf(counts), Optional.empty());
    }
}
