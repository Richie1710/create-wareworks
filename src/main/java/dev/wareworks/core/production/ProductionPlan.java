package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A complete, bounded chain of production steps, worked out at the moment of the click
 * ({@code docs/warehouse-system.md} §3.5.6, ADR-032): every step that has to run so that the ordered item can be made,
 * with the machine each one runs at.
 * <p>
 * <b>Children before parents.</b> {@link #nodes()} is in dependency order and the <b>last</b> node is the root, so a
 * caller that creates the orders in list order always creates a step before the step that waits for it. That order is
 * also what acceptance needs: every node becomes an ordinary production order in the same tick, children first, each
 * naming the parent supply line it feeds.
 * <p>
 * <b>A plan has no lifetime.</b> It is a value computed from one availability snapshot and either turned into orders
 * immediately or thrown away. Nothing re-plans, nothing schedules: once the orders exist, the claim on every ingredient
 * <i>and</i> on every intermediate is an ordinary supply line, which is why the plan never has to stay valid (ADR-032).
 * <p>
 * <b>A plan is a tree, not a graph.</b> Two branches that need the same intermediate get a step each — one order per
 * parent line — while the {@link PlanBudget} makes sure the stock they both draw on is spent only once
 * ({@link #leafDemand()}).
 * <p>
 * That shape is deliberate, and it costs something: a pattern makes whole runs, so the first branch's step regularly
 * makes more of the intermediate than that branch needs, and the second branch still gets a step of its own instead of
 * paying out of the first one's surplus. One plan therefore commits more raw material — and more of
 * {@link #ingredientItems()} — than the chain strictly needs. The alternative is worse: a step paying out of another
 * branch's surplus would be an order whose ingredients <b>nobody is making</b> as far as the bookkeeping can tell. It
 * would have no open child, so the two rules that keep a chain safe would not cover it — it would fetch nothing it could
 * find and its deadline would run while the step it was silently waiting for was still working
 * ({@code ProductionOrders#hasOpenChildren}, {@code #timeOut}). Nothing is lost to the surplus either way: it lands in a
 * rack as items nobody promised.
 *
 * @param nodes           every step in dependency order, children before parents, the root last; at least one node
 * @param depth           levels the chain has below the root: 0 for a plain single-level order, 1 for planks from logs
 *                        under a chest
 * @param ingredientItems ingredient items the whole plan hands to machines, summed over every step — the number
 *                        {@link PlanLimits#maxIngredientItems()} bounds and the size of what one click can lose
 *                        ({@code §3.5.4})
 * @param leafDemand      items the plan takes <b>out of the racks</b>, per key: what the budget really spent, which is
 *                        what a reserve warning is measured over. An intermediate a step makes is not in here — the
 *                        step makes it, the racks do not hold it yet
 * @param requested       result items the click asked for
 * @param rootPromise     result items the plan promises the request, i.e. {@code min(requested, what the root's runs
 *                        yield)}. Less than {@link #requested()} when the plan had to be made smaller to fit the
 *                        ingredients or a bound
 * @param <K>             item key type
 * @param <L>             location type
 */
public record ProductionPlan<K, L>(List<PlanNode<K, L>> nodes, int depth, long ingredientItems,
                                   Map<K, Long> leafDemand, long requested, long rootPromise) {
    public ProductionPlan {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        leafDemand = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(leafDemand,
                "leafDemand")));
        if (nodes.isEmpty())
            throw new IllegalArgumentException("a plan needs at least one step");
        // The structure is checked here rather than trusted, because everything downstream reads it as a tree: the
        // order the orders are created in, the depth a screen indents by and the parent a blocked step waits for.
        int roots = 0;
        for (int i = 0; i < nodes.size(); i++) {
            PlanNode<K, L> node = nodes.get(i);
            if (node.index() != i)
                throw new IllegalArgumentException("node " + i + " carries index " + node.index());
            if (node.isRoot()) {
                roots++;
                if (i != nodes.size() - 1)
                    throw new IllegalArgumentException("the root must be the last node, not " + i);
                continue;
            }
            if (node.parent() >= nodes.size())
                throw new IllegalArgumentException("node " + i + " names a parent that does not exist");
            if (node.parent() <= i)
                throw new IllegalArgumentException("node " + i + " must come before its parent " + node.parent());
            if (node.depth() != nodes.get(node.parent()).depth() + 1)
                throw new IllegalArgumentException("node " + i + " is not one level below its parent");
        }
        if (roots != 1)
            throw new IllegalArgumentException("a plan has exactly one root, not " + roots);
        if (depth < 0)
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        if (ingredientItems < 0L)
            throw new IllegalArgumentException("ingredientItems must not be negative: " + ingredientItems);
        if (requested < 1L)
            throw new IllegalArgumentException("requested must be at least 1: " + requested);
        if (rootPromise < 1L || rootPromise > requested)
            throw new IllegalArgumentException("rootPromise must be within 1.." + requested + ": " + rootPromise);
    }

    /** Production orders this plan creates. */
    public int steps() {
        return nodes.size();
    }

    /** The ordered item's own step — the node a request waits for, and the last one in {@link #nodes()}. */
    public PlanNode<K, L> root() {
        return nodes.get(nodes.size() - 1);
    }

    /** The item that was ordered. */
    public K result() {
        return root().result();
    }

    /** Whether this is a plain single-level order: one step, nothing waiting for anything. */
    public boolean isSingleLevel() {
        return nodes.size() == 1;
    }

    /**
     * Whether the plan promises the request less than it asked for, because the ingredients or a bound did not allow
     * more. The granted amount is {@link #rootPromise()}; a pattern makes whole runs, so the root may still yield more
     * than that and the surplus is promised to nobody.
     */
    public boolean isClamped() {
        return rootPromise < requested;
    }

    /** The steps that feed {@code index}, in creation order. */
    public List<PlanNode<K, L>> childrenOf(int index) {
        List<PlanNode<K, L>> children = new ArrayList<>(2);
        for (PlanNode<K, L> node : nodes) {
            if (node.parent() == index)
                children.add(node);
        }
        return List.copyOf(children);
    }

    /** The step at {@code index}. */
    public PlanNode<K, L> node(int index) {
        return nodes.get(index);
    }
}
