package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One concrete ingredient line of a plan step ({@link PlanNode#lines()}, ADR-082): deliver {@link #amount()} items of
 * {@link #key()} to the step's station.
 * <p>
 * An exact ingredient gives one line of its own key. A choice ingredient gives one line per key that pays it, each a
 * whole number of runs, and every such line remembers the filter that chose it ({@link #choice()}). The production
 * order is created from these lines (the lines overloads of {@link ProductionOrder}) and never from its pattern, so
 * an order never holds a filter key.
 *
 * @param key    the concrete item
 * @param amount items of it the step needs, at least 1
 * @param choice the filter key of the choice this line pays, empty for an exact ingredient
 * @param fluid  the fluid of a <b>fluid line</b> (issue #55, ADR-089): then {@code key} is the filled container, {@code
 *               amount} counts containers and the line pays one {@link FluidCell} of the pattern; empty for every item
 *               line
 * @param <K>    item key type
 */
public record NodeLine<K>(K key, long amount, Optional<K> choice, Optional<String> fluid) {
    public NodeLine {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(fluid, "fluid");
        if (amount < 1L)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        if (fluid.isPresent() && choice.isPresent())
            throw new IllegalArgumentException("a fluid line pays no choice");
    }

    /** A line of an item ingredient: what every line was before ADR-089. */
    public NodeLine(K key, long amount, Optional<K> choice) {
        this(key, amount, choice, Optional.empty());
    }

    /** A fluid line (issue #55): {@code containers} filled containers {@code container} that deliver {@code fluid}. */
    public static <K> NodeLine<K> fluid(K container, long containers, String fluid) {
        return new NodeLine<>(container, containers, Optional.empty(),
                Optional.of(Objects.requireNonNull(fluid, "fluid")));
    }

    /** Whether this is a fluid line (issue #55). */
    public boolean isFluid() {
        return fluid.isPresent();
    }

    /** A line of an exact ingredient. */
    public static <K> NodeLine<K> exact(K key, long amount) {
        return new NodeLine<>(key, amount, Optional.empty());
    }

    /** A line that pays the choice {@code choice}. */
    public static <K> NodeLine<K> chosen(K key, long amount, K choice) {
        return new NodeLine<>(key, amount, Optional.of(Objects.requireNonNull(choice, "choice")));
    }

    /** The exact lines of a pattern without choices for {@code runs} runs, in pattern order. */
    public static <K> List<NodeLine<K>> exactLinesOf(ProductionPattern<K> pattern, int runs) {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.hasChoices())
            throw new IllegalArgumentException("a pattern with choices has no lines of its own; plan them");
        if (pattern.hasFluids())
            throw new IllegalArgumentException("a pattern with fluid cells has no lines of its own; plan them");
        List<NodeLine<K>> lines = new ArrayList<>(pattern.ingredients().size());
        for (ProductionEntry<K> ingredient : pattern.ingredients())
            lines.add(exact(ingredient.key(), (long) ingredient.count() * runs));
        return List.copyOf(lines);
    }
}
