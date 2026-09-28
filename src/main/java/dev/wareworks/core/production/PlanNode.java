package dev.wareworks.core.production;

import java.util.Objects;

/**
 * One step of a production plan ({@code docs/warehouse-system.md} §3.5.6, ADR-032): make {@link #needed()} items of
 * {@link #result()} with {@link #runs()} runs of {@link #pattern()} at {@link #station()}.
 * <p>
 * A node is a <b>description of an order that does not exist yet</b>. When the plan is accepted, each node becomes one
 * ordinary {@link ProductionOrder} — nothing about a plan survives acceptance, which is why a plan never has to stay
 * valid (ADR-032).
 * <p>
 * <b>The links are indices into the plan's own node list</b>, not ids: the plan is built and read in one pass and the
 * nodes are in dependency order, so an index is the cheapest link that cannot dangle. {@link #parent()} is {@code -1}
 * for the root, and every other node's parent sits <b>after</b> it in the list
 * ({@link ProductionPlan#nodes()} checks it).
 *
 * @param index   this node's position in {@link ProductionPlan#nodes()}
 * @param parent  the position of the node this one makes its product for, {@code -1} for the root
 * @param depth   0 for the root, one more than the parent's depth for every other node
 * @param station the production station the ingredients go to
 * @param pattern the pattern that is run there
 * @param runs    runs of the pattern, at least 1. A pattern cannot be cut, so a node regularly makes more than
 *                {@link #needed()} and the surplus simply lands in stock
 * @param needed  result items the node was created for: for the root what the click is promised
 *                ({@link ProductionPlan#rootPromise()}, i.e. the ordered amount or less when the plan had to be made
 *                smaller), and for every other node the part of a parent's ingredient line that the racks could not pay
 *                for otherwise. Never the whole run — {@link #output()} is that, and it is regularly larger
 * @param <K>     item key type
 * @param <L>     location type
 */
public record PlanNode<K, L>(int index, int parent, int depth, L station, ProductionPattern<K> pattern, int runs,
                             long needed) {
    /** What {@link #parent()} is for a node nobody waits for: the plan's root. */
    public static final int NO_PARENT = -1;

    public PlanNode {
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(pattern, "pattern");
        if (index < 0)
            throw new IllegalArgumentException("index must not be negative: " + index);
        if (parent < NO_PARENT || parent == index)
            throw new IllegalArgumentException("parent must be another node or " + NO_PARENT + ": " + parent);
        if (depth < 0)
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        if ((parent == NO_PARENT) != (depth == 0))
            throw new IllegalArgumentException("only the root has no parent, and only the root has depth 0");
        if (runs < 1)
            throw new IllegalArgumentException("runs must be at least 1: " + runs);
        if (needed < 1L)
            throw new IllegalArgumentException("needed must be at least 1: " + needed);
    }

    /** Whether this is the ordered item's own step, the one a request waits for. */
    public boolean isRoot() {
        return parent == NO_PARENT;
    }

    /** The item this step makes. */
    public K result() {
        return pattern.result().key();
    }

    /** Result items {@link #runs()} runs are expected to yield, which is at least {@link #needed()}. */
    public int output() {
        return pattern.resultFor(runs);
    }

    /** Ingredient items this step alone hands to the machine, over all its ingredients. */
    public long ingredientItems() {
        return (long) pattern.ingredientItems() * runs;
    }
}
