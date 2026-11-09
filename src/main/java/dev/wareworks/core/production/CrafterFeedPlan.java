package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The arithmetic of a Warehouse Crafter Feeder (issue #34, ADR-067): which pattern slot an order belongs to, how many
 * runs it is, and how many of them are laid into the Crafter at once.
 * <p>
 * <b>An order does not remember its pattern slot.</b> It carries its result, the amount it waits for and one supply line
 * per distinct ingredient — the merged multiset, never the grid. The feeder therefore finds the slot again by
 * comparing those numbers ({@link #runsFor}): same result, a whole number of runs, and every line exactly
 * {@code count x runs}. Two slots that pass are both the same crafting recipe in another arrangement (or the same one
 * twice), so either is correct, and the first one in slot order wins ({@link #match}).
 * <p>
 * <b>Filter cells (ADR-082).</b> A choice ingredient is paid by one line per chosen key, each a whole number of runs and
 * each naming its filter ({@link SupplyLine#choice()}). {@link #runsFor} matches those lines by filter, and
 * {@link #segment} lays the order in segments: one key per filter group per batch, so a Crafter slot never holds two
 * kinds at once.
 * <p>
 * Pure: no Minecraft types.
 */
public final class CrafterFeedPlan {
    /** At most this many items go into one Crafter slot at once: a stack, whatever the item says. */
    public static final int MAX_BATCH = 64;

    /**
     * The slot an order is fed from, and how many runs it is.
     *
     * @param slot the pattern slot
     * @param runs runs of that pattern the order is, at least 1
     */
    public record Match(int slot, int runs) {
        public Match {
            if (slot < 0)
                throw new IllegalArgumentException("slot must not be negative: " + slot);
            if (runs < 1)
                throw new IllegalArgumentException("runs must be at least 1: " + runs);
        }
    }

    private CrafterFeedPlan() {
    }

    /**
     * How many runs of {@code pattern} {@code order} is, or nothing when the order was not planned from this pattern:
     * another result, an amount that is no whole number of runs, another set of ingredients or another amount of one.
     */
    public static <K> OptionalInt runsFor(ProductionOrder<K, ?> order, ProductionPattern<K> pattern) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(pattern, "pattern");
        if (!pattern.result().key().equals(order.result()))
            return OptionalInt.empty();
        int perRun = pattern.result().count();
        if (order.resultAmount() < perRun || order.resultAmount() % perRun != 0)
            return OptionalInt.empty();
        int runs = order.resultAmount() / perRun;
        List<SupplyLine<K>> lines = order.lines();
        int exactLines = 0;
        for (SupplyLine<K> line : lines) {
            if (line.choice().isEmpty())
                exactLines++;
        }
        List<ProductionEntry<K>> exact = pattern.exactIngredients();
        if (exactLines != exact.size())
            return OptionalInt.empty();
        for (ProductionEntry<K> ingredient : exact) {
            boolean found = false;
            for (SupplyLine<K> line : lines) {
                if (line.choice().isPresent() || !line.key().equals(ingredient.key()))
                    continue;
                if ((long) ingredient.count() * runs != line.required())
                    return OptionalInt.empty();
                found = true;
                break;
            }
            if (!found)
                return OptionalInt.empty();
        }
        // Every choice of the pattern is paid by its own group of lines, each a whole number of runs, together exactly
        // the order's runs; and no line may be left over (ADR-082).
        int matched = exactLines;
        for (ProductionEntry<K> ingredient : pattern.choiceIngredients()) {
            long sum = 0L;
            int group = 0;
            for (SupplyLine<K> line : lines) {
                if (line.choice().filter(ingredient.key()::equals).isEmpty())
                    continue;
                if (line.required() % ingredient.count() != 0)
                    return OptionalInt.empty();
                sum += line.required();
                group++;
            }
            if (group == 0 || sum != (long) ingredient.count() * runs)
                return OptionalInt.empty();
            matched += group;
        }
        return matched == lines.size() ? OptionalInt.of(runs) : OptionalInt.empty();
    }

    /**
     * One segment of an order with filter cells (ADR-082): the concrete layout to lay, and how many runs it lasts.
     *
     * @param layout the grid with every filter cell set to the key that pays this run
     * @param runs   runs this layout lasts from the run the segment was asked for; {@link Integer#MAX_VALUE} when the
     *               grid has no filter cell (one layout for every run), 0 when the lines hold no further run
     */
    public record Segment<K>(CrafterGrid<K> layout, int runs) {
        public Segment {
            Objects.requireNonNull(layout, "layout");
            if (runs < 0)
                throw new IllegalArgumentException("runs must not be negative: " + runs);
        }
    }

    /**
     * The segment that starts at run {@code runsDone} of an order (ADR-082): every cell of a filter {@code c} holds the
     * key of the line that pays run {@code runsDone} of {@code c} — lines in line order, {@code required / cells} runs
     * each — and the segment lasts until the next line boundary of any filter. A Crafter slot therefore never holds two
     * kinds in one batch, and the cells that are filled are the same in every segment.
     *
     * @param cells    the slot's grid as authored, with the filter keys in the filter cells ({@code layoutOf})
     * @param choices  the pattern's choice keys
     * @param lines    the order's supply lines
     * @param runsDone runs of the order already laid
     */
    public static <K> Segment<K> segment(CrafterGrid<K> cells, Set<K> choices, List<SupplyLine<K>> lines,
            int runsDone) {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(lines, "lines");
        Map<K, Integer> perKey = cells.cellsPerKey();
        List<Optional<K>> layout = new ArrayList<>(cells.cells());
        long end = Long.MAX_VALUE;
        int done = Math.max(0, runsDone);
        for (Map.Entry<K, Integer> used : perKey.entrySet()) {
            K choice = used.getKey();
            if (!choices.contains(choice))
                continue;
            int count = Math.max(1, used.getValue());
            long covered = 0L;
            K key = null;
            for (SupplyLine<K> line : lines) {
                if (line.choice().filter(choice::equals).isEmpty())
                    continue;
                long next = covered + line.required() / count;
                if (done < next) {
                    key = line.key();
                    end = Math.min(end, next);
                    break;
                }
                covered = next;
            }
            if (key == null)
                return new Segment<>(cells, 0);
            for (int cell = 0; cell < CrafterGrid.SLOTS; cell++) {
                if (layout.get(cell).filter(choice::equals).isPresent())
                    layout.set(cell, Optional.of(key));
            }
        }
        int runs = end == Long.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, end - done);
        return new Segment<>(new CrafterGrid<>(layout), runs);
    }

    /**
     * The first slot in slot order whose pattern {@code order} was planned from and that {@code valid} accepts (for the
     * feeder: whose crafting recipe check passed), with the number of runs.
     *
     * @param slots one entry per pattern slot, empty for a slot that holds no complete pattern
     */
    public static <K> Optional<Match> match(ProductionOrder<K, ?> order, List<Optional<ProductionPattern<K>>> slots,
            IntPredicate valid) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(valid, "valid");
        for (int slot = 0; slot < slots.size(); slot++) {
            Optional<ProductionPattern<K>> pattern = slots.get(slot);
            if (pattern == null || pattern.isEmpty() || !valid.test(slot))
                continue;
            OptionalInt runs = runsFor(order, pattern.get());
            if (runs.isPresent())
                return Optional.of(new Match(slot, runs.getAsInt()));
        }
        return Optional.empty();
    }

    /**
     * How many runs of an order a feeder still has to lay when it loads it: the runs the order is, less those a feeder
     * already laid into the Crafter (kept by the station, so a feeder that is broken and placed again does not lay them a
     * second time), and never more than the result the order still waits for needs. A feeder lays no more than this, so
     * it can never take a later order's ingredients out of the buffer as its own.
     *
     * @param runs              runs the order is ({@link #runsFor})
     * @param laid              runs of it already laid into the Crafter
     * @param outstandingResult result items the order still waits for
     * @param perRun            result items one run makes, at least 1
     * @return 0 when nothing is left to lay
     */
    public static int remainingRuns(int runs, int laid, long outstandingResult, int perRun) {
        long left = (long) runs - Math.max(0, laid);
        long byResult = (Math.max(0L, outstandingResult) + Math.max(1, perRun) - 1) / Math.max(1, perRun);
        return (int) Math.max(0L, Math.min(left, byResult));
    }

    /**
     * How many runs are laid at once: as many as are left, but never more than a stack of the item with the smallest
     * stack size among the used cells and never more than {@value #MAX_BATCH}. Each run puts one item into every used
     * slot, so this is also the count of every slot after laying.
     *
     * @param runsLeft            runs still to lay
     * @param maxStackPerUsedCell the smallest maximum stack size of the items in the used cells
     * @return 0 when nothing is left to lay, otherwise at least 1
     */
    public static int batch(int runsLeft, int maxStackPerUsedCell) {
        if (runsLeft <= 0)
            return 0;
        return Math.max(1, Math.min(runsLeft, Math.min(Math.max(1, maxStackPerUsedCell), MAX_BATCH)));
    }
}
