package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <b>Fluid inputs</b> (issue #55, ADR-089 amending ADR-076 D5) become the pattern's fluid cells, never item cells: an
 * input of the same fluid is merged, the first alternative of a tag is the one laid, and a recipe that needs more than
 * {@value FluidCell#MAX_PER_PATTERN} fluids or more than {@value FluidCell#MAX_MB} mB of one is refused.
 * <p>
 * <b>What a pattern cannot say is refused, and nothing is laid.</b> A pattern holds items in at most
 * {@value ProductionPattern#GRID_SIZE} cells and up to {@value FluidCell#MAX_PER_PATTERN} fluid cells, and must not make
 * one of its own ingredients. A result that is only
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

    /**
     * One fluid input of a recipe (issue #55).
     *
     * @param candidates the fluids it accepts as registry ids, in the recipe's order (more than one for a fluid tag);
     *                   the first is the one laid
     * @param millibuckets how much it takes per run, at least 1
     */
    public record FluidInput(List<String> candidates, int millibuckets) {
        public FluidInput {
            candidates = List.copyOf(candidates);
            if (millibuckets < 1)
                throw new IllegalArgumentException("millibuckets must be at least 1: " + millibuckets);
        }
    }

    /** Why a recipe cannot become a pattern. */
    public enum Refusal {
        /** An input is neither an item nor a fluid: a pattern holds items and fluids only. */
        NOT_ONLY_ITEMS,
        /** The inputs need more than {@value ProductionPattern#GRID_SIZE} cells. */
        TOO_MANY_INPUTS,
        /** Every candidate of an input is the result. */
        MAKES_ITS_INGREDIENT,
        /** The recipe needs more than {@value FluidCell#MAX_PER_PATTERN} different fluids (appended, issue #55). */
        TOO_MANY_FLUIDS,
        /** A fluid input needs more than {@value FluidCell#MAX_MB} mB per run (appended, issue #55). */
        FLUID_TOO_MUCH,
        /** The recipe makes a fluid, which a pattern cannot say (appended, issue #55). */
        FLUID_RESULT
    }

    /**
     * What a recipe became: either {@code refusal}, or the cells to lay.
     *
     * @param cells   exactly {@value ProductionPattern#GRID_SIZE} cells, each with its candidates; empty if refused
     * @param counts  the count of each cell, parallel to {@code cells}; empty if refused
     * @param refusal why nothing can be laid
     * @param fluids  the fluid cells to lay, at most {@value FluidCell#MAX_PER_PATTERN} with distinct fluids; empty for
     *                a recipe without a fluid and if refused
     */
    public record Layout<K>(List<List<K>> cells, List<Integer> counts, Optional<Refusal> refusal,
                            List<FluidCell> fluids) {
        public Layout {
            fluids = List.copyOf(fluids);
        }

        /** A layout without fluid cells. */
        public Layout(List<List<K>> cells, List<Integer> counts, Optional<Refusal> refusal) {
            this(cells, counts, refusal, List.of());
        }

        private static <K> Layout<K> refused(Refusal refusal) {
            return new Layout<>(List.of(), List.of(), Optional.of(refusal), List.of());
        }
    }

    private PatternLay() {
    }

    /** Lays {@code inputs} out for a pattern that makes {@code result}; a recipe without fluid inputs. */
    public static <K> Layout<K> layout(List<Input<K>> inputs, K result) {
        return layout(inputs, List.of(), result);
    }

    /**
     * Lays {@code inputs} and the fluid inputs {@code fluids} out for a pattern that makes {@code result}. A fluid
     * input with no candidate is no fluid this side can name and is refused like any input that is not an item.
     */
    public static <K> Layout<K> layout(List<Input<K>> inputs, List<FluidInput> fluids, K result) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(fluids, "fluids");
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
        Map<String, Long> wanted = new LinkedHashMap<>();
        for (FluidInput fluid : fluids) {
            if (fluid.candidates().isEmpty())
                return Layout.refused(Refusal.NOT_ONLY_ITEMS);
            wanted.merge(fluid.candidates().get(0), (long) fluid.millibuckets(), Long::sum);
        }
        if (wanted.size() > FluidCell.MAX_PER_PATTERN)
            return Layout.refused(Refusal.TOO_MANY_FLUIDS);
        List<FluidCell> laidFluids = new ArrayList<>(wanted.size());
        for (Map.Entry<String, Long> entry : wanted.entrySet()) {
            if (entry.getValue() > FluidCell.MAX_MB)
                return Layout.refused(Refusal.FLUID_TOO_MUCH);
            laidFluids.add(new FluidCell(entry.getKey(), entry.getValue().intValue()));
        }
        while (cells.size() < ProductionPattern.GRID_SIZE) {
            cells.add(List.of());
            counts.add(ProductionEntry.MIN_COUNT);
        }
        return new Layout<>(List.copyOf(cells), List.copyOf(counts), Optional.empty(), laidFluids);
    }
}
