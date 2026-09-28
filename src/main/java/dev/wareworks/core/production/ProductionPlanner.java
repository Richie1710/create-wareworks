package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Works out the <b>whole chain</b> an order needs, at the moment of the click ({@code docs/warehouse-system.md} §3.5.6,
 * ADR-032, M20, issue #4): order a chest with logs but no planks in the racks, and this is what answers "planks from
 * logs at the saw, then the chest from those planks at the crafter" — or refuses the click and <b>names the item</b>
 * that is really missing.
 * <p>
 * <b>Wareworks still crafts nothing.</b> Every step of a plan is an ordinary production order at one of the player's own
 * machines (ADR-024): the crane delivers the ingredients, the machine works, the product comes back through a warehouse
 * input into a rack, and the crane fetches it again for the next step. There is deliberately no machine-to-machine
 * shortcut and no intermediate the controller holds — every intermediate is a real crane trip through a real rack,
 * which is what keeps the stock index and every surface honest.
 * <p>
 * <b>Decided once, and then it is over.</b> The plan is computed from one availability snapshot ({@link PlanBudget}) and
 * either turned into orders in the same tick or thrown away. That is what makes a refusal actionable: the answer is
 * "Oak Log is missing" <i>before</i> the crane starts, instead of an accepted order whose logs are already in a sawmill
 * when the same sentence finally appears on a status line.
 *
 * <h2>The walk</h2>
 * Depth first, in pattern order, post-order (children before parents):
 * <ol>
 * <li>An item's patterns are tried in the order "the one that could make the most of it right now first, ties by aisle
 * order" — the first of them is exactly what {@code ProduciblePlanner#bestPatternFor} picks, so a rule, a player and a
 * step all start from the same pattern. When no pattern can make any of it — the normal case for an item the chain has
 * yet to make — that order is plain aisle order.</li>
 * <li><b>A pattern that leads nowhere is not the answer.</b> When the subtree under the preferred pattern cannot be
 * made, the next pattern for the same item is tried, and only when <i>every</i> pattern for it fails is the refusal
 * reported ({@link #MAX_EXTRA_PATTERN_ATTEMPTS} bounds how many such retries one walk may spend). Without that fallback a
 * chain the aisle really can make was refused because one pattern out of two was a dead end, and the refusal named an
 * ingredient of the pattern nobody would have used — which is the one sentence this feature is judged by (M20 review
 * fix). Two branches that need the same item therefore normally use the same pattern, and differ only when one of them
 * could not be made with it.</li>
 * <li>Every ingredient is paid for out of the racks as far as the budget reaches, and <b>only the rest</b> becomes a
 * step of its own: three planks in stock of the eight a chest needs are used, and a step makes the other five. An
 * ingredient the budget pays for in full is a leaf and is never expanded.</li>
 * <li>A step that cannot be made is not a stall but a refusal, and the refusal travels up with the item it is about
 * ({@link PlanRefusal}).</li>
 * </ol>
 * <b>A plan is a tree, not a graph</b> ({@link ProductionPlan}), and that is deliberate: two branches that need the
 * same intermediate get a step each, one per parent ingredient line, and each of them makes whole runs, so a plan
 * regularly commits more raw material than the chain strictly needs. Pooling one step's surplus into the other branch
 * would mean an order whose ingredients <i>nobody is making</i> — it would have no open child, so the rule that a
 * blocked parent fetches nothing and does not time out would not cover it, and it could time out while the step it was
 * silently waiting for was still running. The surplus is not lost either way: it lands in a rack as items nobody
 * promised.
 *
 * <h2>What bounds it</h2>
 * <b>There is no depth limit.</b> A chain may be as deep as the player's machines make it. Three things bound it
 * instead, and each of them names the item it bit on:
 * <ul>
 * <li><b>Cycles, always.</b> The result keys on the current path are carried down; a step for an item already on the
 * path, or a pattern that would <i>consume</i> what a step further up makes, is {@link PlanRefusal#LOOP} — refused
 * before anything is converted. Iron ingot to iron block and back therefore terminates by construction, and it is what
 * makes a plan finite without counting levels. A loop only rules out <b>that pattern</b>: another pattern for the same
 * item is tried before the walk gives up.</li>
 * <li><b>The step count</b> ({@link PlanLimits#maxSteps()}, and the aisle's free order slots), which also bounds how
 * deep a chain can get: every level costs at least one step.</li>
 * <li><b>The ingredient items</b> ({@link PlanLimits#maxIngredientItems()}) — what one click may hand to machines over
 * every step, which is the number that really bounds what a click can lose ({@code §3.5.4}).</li>
 * </ul>
 * <b>A bound clamps before it refuses.</b> A plan that does not fit is tried again with fewer runs of the ordered item —
 * cost is monotone in those runs, so the largest plan that fits is found by a binary search of at most 31 walks — and the
 * click is refused only when not even <b>one</b> run fits. That is the same clamp a single-level order has always had:
 * {@code startProductionOrder} bounded its runs by the ingredients that were really there rather than refusing, and the
 * request was told the amount it was granted. A refusal is therefore always about something a smaller order cannot
 * escape.
 * <p>
 * <b>At a step limit of 1 this is the pre-M20 planner</b>, answer for answer: one order for the ordered item, its runs
 * clamped by the ingredients in the racks, and {@link PlanRefusal#MISSING_INGREDIENT} naming the first ingredient one
 * run cannot be paid for.
 *
 * <h2>Cost</h2>
 * One bounded pass at a click, touching no inventory and no world. One walk visits at most {@code stepLimit} nodes plus
 * the one that refused, each scanning the aisle's patterns once for one item (the ranked candidates are cached per item
 * for the whole plan), each node looking at up to {@value ProductionPattern#MAX_INGREDIENTS} ingredients. A walk may
 * additionally spend a bounded number of attempts on alternative patterns ({@link #MAX_EXTRA_PATTERN_ATTEMPTS}), so its
 * work stays bounded by {@code (extra attempts + 1) × (stepLimit + 1)} node attempts however the patterns are written. One
 * plan takes at most the 31 walks of the clamp's binary search plus two per pattern of the <b>ordered</b> item that turns
 * out to be a dead end, because a root pattern that cannot make even one run is given up on after those two. The
 * availability function is asked at most once per item for the whole plan, however many walks it takes, and so is the
 * ranking of an item's patterns.
 * <p>
 * Pure Java with no Minecraft types, so every branch of it is a unit test.
 */
public final class ProductionPlanner {
    /**
     * Attempts at an <b>alternative</b> pattern one walk may spend before it gives up and reports the refusal
     * ({@code min(64, max(8, stepLimit))}).
     * <p>
     * The first pattern of every item is always tried, so this bounds only the fallback and never the walk itself: a
     * chain that works with the preferred patterns costs exactly what it cost before the fallback existed. It exists
     * because trying every pattern of every item is exponential in the depth of the chain, and a click has to answer in
     * one tick whatever a player has written into their aisle.
     */
    private static final int MAX_EXTRA_PATTERN_ATTEMPTS = 64;
    /** The smallest fallback budget, so a shallow chain can always try every pattern it has. */
    private static final int MIN_EXTRA_PATTERN_ATTEMPTS = 8;

    private ProductionPlanner() {
    }

    /**
     * The chain that would make {@code amount} items of {@code key}, or the refusal that names what is in the way.
     *
     * @param input  the aisle's patterns, the budget, the bounds, the paused items and the free order slots — one
     *               consistent snapshot, which the planner never mutates
     * @param key    the ordered item
     * @param amount result items the click asked for, at least 1. The plan may promise less
     *               ({@link ProductionPlan#rootPromise()}) when the ingredients or a bound did not allow more
     * @throws IllegalArgumentException if {@code amount < 1}
     */
    public static <K, L> ProductionPlanResult<K, L> plan(ProductionPlanInput<K, L> input, K key, long amount) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(key, "key");
        if (amount < 1L)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        // The ordered item's own two structural answers do not depend on how much was asked for, so they are given
        // without a walk: the warehouse has stopped making it, or nothing here makes it at all.
        if (input.isPaused(key))
            return ProductionPlanResult.refused(PlanRefusal.PAUSED, key);
        Map<K, List<StationPattern<K, L>>> ranked = new LinkedHashMap<>();
        List<StationPattern<K, L>> roots = candidatesFor(input, ranked, key);
        if (roots.isEmpty())
            return ProductionPlanResult.refused(PlanRefusal.NO_PATTERN, key);
        // The ordered item's own patterns are tried like every other item's: a chest the aisle can build two ways is
        // not refused because the first way happens to be a dead end.
        Reported<K> reported = new Reported<>();
        for (StationPattern<K, L> root : roots) {
            int wanted = root.pattern().runsFor(amount);
            Attempt<K, L> best = attempt(input, ranked, root, key, amount, wanted);
            if (best.failed() && wanted > 1) {
                // The whole order did not fit. One run of the ordered item is the smallest thing a pattern can make, so
                // it decides whether anything at all is possible — and its refusal is the one the player is told about,
                // because it is the one no smaller order can escape.
                Attempt<K, L> smallest = attempt(input, ranked, root, key, amount, 1);
                best = smallest.failed() ? smallest
                        : largestThatFits(input, ranked, root, key, amount, smallest, wanted - 1);
            }
            if (!best.failed())
                return ProductionPlanResult.accepted(best.plan());
            reported.record(best.refusal(), best.about());
        }
        return ProductionPlanResult.refused(reported.reason(), reported.about());
    }

    /**
     * The largest plan that fits, between one run of the ordered item (which is known to fit) and {@code hi} runs
     * (which is known not to). Cost, step count and room use are all monotone in the ordered item's runs — fewer runs
     * need fewer ingredients, and an ingredient the racks can pay for is not expanded at all — so a binary search finds
     * the largest one in at most 31 walks instead of walking down run by run.
     */
    private static <K, L> Attempt<K, L> largestThatFits(ProductionPlanInput<K, L> input,
            Map<K, List<StationPattern<K, L>>> ranked, StationPattern<K, L> root, K key, long amount,
            Attempt<K, L> fits, int hi) {
        Attempt<K, L> best = fits;
        int lo = fits.rootRuns();
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            Attempt<K, L> tried = attempt(input, ranked, root, key, amount, mid);
            if (tried.failed()) {
                hi = mid - 1;
            } else {
                best = tried;
                lo = mid;
            }
        }
        return best;
    }

    private static <K, L> Attempt<K, L> attempt(ProductionPlanInput<K, L> input,
            Map<K, List<StationPattern<K, L>>> ranked, StationPattern<K, L> root, K key, long amount, int rootRuns) {
        return new Walk<>(input, ranked).run(root, key, amount, rootRuns);
    }

    /**
     * The patterns of this aisle that make {@code key}, <b>best first</b>: the one that could make the most of it
     * against the plan's starting availability leads, ties keep aisle order, and when none of them can make any right
     * now — the normal case for an item the chain has yet to produce — the order is plain aisle order. Remembered for
     * the whole plan, so the availability is asked once per item however many walks a plan takes.
     * <p>
     * Measuring against the starting snapshot rather than against what the walk has already spent is deliberate: the
     * ranking is then the same for every branch of one plan and does not depend on the order the branches are walked in,
     * which is what makes a plan reproducible and its cost monotone in the ordered item's runs. At the head of the list
     * stands the very pattern {@code startProductionOrder} picks today.
     */
    private static <K, L> List<StationPattern<K, L>> candidatesFor(ProductionPlanInput<K, L> input,
            Map<K, List<StationPattern<K, L>>> cache, K key) {
        List<StationPattern<K, L>> found = cache.get(key);
        if (found == null) {
            found = rank(input, key);
            cache.put(key, found);
        }
        return found;
    }

    private static <K, L> List<StationPattern<K, L>> rank(ProductionPlanInput<K, L> input, K key) {
        ToLongFunction<K> snapshot = input.budget()::snapshot;
        List<StationPattern<K, L>> candidates = new ArrayList<>(2);
        Map<StationPattern<K, L>, Long> producible = new IdentityHashMap<>();
        for (StationPattern<K, L> candidate : input.patterns()) {
            if (!candidate.produces(key))
                continue;
            candidates.add(candidate);
            producible.put(candidate, ProduciblePlanner.producibleAmount(candidate.pattern(), snapshot));
        }
        if (candidates.size() > 1) {
            // A stable sort, so the head is "the best, ties by aisle order" and the tail keeps aisle order too.
            candidates.sort(Comparator.comparingLong((StationPattern<K, L> candidate) -> producible.get(candidate))
                    .reversed());
        }
        return List.copyOf(candidates);
    }

    /**
     * The refusal a caller is told about when several patterns were tried and all of them failed.
     * <p>
     * The <b>first</b> pattern's refusal is the one reported, because that is the pattern the aisle would have used —
     * with one exception: a {@link PlanRefusal#LOOP} says that <i>this pattern</i> cannot be used here (it would convert
     * an item into itself), not that the item cannot be made, so a later pattern's answer is the more actionable one and
     * takes precedence over it (M20 review fix).
     */
    private static final class Reported<K> {
        private PlanRefusal reason;
        private K about;

        private void record(PlanRefusal next, K item) {
            if (next == null)
                return;
            if (reason == null || (reason == PlanRefusal.LOOP && next != PlanRefusal.LOOP)) {
                reason = next;
                about = item;
            }
        }

        private PlanRefusal reason() {
            return reason;
        }

        private K about() {
            return about;
        }
    }

    /** One walk: either a plan for a given number of runs of the ordered item, or the refusal that stopped it. */
    private record Attempt<K, L>(ProductionPlan<K, L> plan, PlanRefusal refusal, K about, int rootRuns) {
        static <K, L> Attempt<K, L> of(ProductionPlan<K, L> plan, int rootRuns) {
            return new Attempt<>(plan, null, null, rootRuns);
        }

        static <K, L> Attempt<K, L> refused(PlanRefusal refusal, K about, int rootRuns) {
            return new Attempt<>(null, refusal, about, rootRuns);
        }

        boolean failed() {
            return plan == null;
        }
    }

    /** A node while it is being built: the links are references, because its index is only known once it is flattened. */
    private static final class Draft<K, L> {
        private final StationPattern<K, L> station;
        private final int runs;
        private final long needed;
        private final int depth;
        private final Draft<K, L> parent;
        private final List<Draft<K, L>> children = new ArrayList<>(2);

        private Draft(StationPattern<K, L> station, int runs, long needed, int depth, Draft<K, L> parent) {
            this.station = station;
            this.runs = runs;
            this.needed = needed;
            this.depth = depth;
            this.parent = parent;
        }
    }

    /**
     * What one pattern attempt of a {@link Walk} may change, so that giving up on that pattern and trying the next one
     * leaves no trace of it: the steps created so far, the ingredient items committed, what the plan means to make of
     * every item, and the budget's ledger.
     */
    private record Mark<K>(int created, long ingredientItems, Map<K, Long> plannedOutput, Map<K, Long> spent) {
    }

    /**
     * One attempt's state: the fork of the budget it spends, the steps it has created, the result keys on the current
     * path, the room every intermediate has left, the ingredient items it has committed and what is left of its
     * fallback budget.
     */
    private static final class Walk<K, L> {
        private final ProductionPlanInput<K, L> input;
        private final Map<K, List<StationPattern<K, L>>> ranked;
        private final PlanBudget<K> budget;
        private final int stepLimit;
        private final List<Draft<K, L>> created = new ArrayList<>();
        private final List<K> path = new ArrayList<>();
        /**
         * Result items the plan already means to make of a key. A second step for the same intermediate has to fit
         * under the same maximum as the first one, because both of them are the plan asking to store that item.
         */
        private final Map<K, Long> plannedOutput = new LinkedHashMap<>();
        private long ingredientItems;
        /**
         * Attempts at an alternative pattern this walk may still spend
         * ({@link ProductionPlanner#MAX_EXTRA_PATTERN_ATTEMPTS}).
         */
        private int extraAttempts;
        private PlanRefusal refusal;
        private K about;

        private Walk(ProductionPlanInput<K, L> input, Map<K, List<StationPattern<K, L>>> ranked) {
            this.input = input;
            this.ranked = ranked;
            this.budget = input.budget().fresh();
            this.stepLimit = input.stepLimit();
            this.extraAttempts = Math.min(MAX_EXTRA_PATTERN_ATTEMPTS,
                    Math.max(MIN_EXTRA_PATTERN_ATTEMPTS, this.stepLimit));
        }

        private Attempt<K, L> run(StationPattern<K, L> root, K key, long requested, int rootRuns) {
            // A pattern makes whole runs, so the root regularly yields more than was asked for; what it is created for
            // — and what it promises the request — is never more than the request wanted, and the surplus was asked for
            // by nobody. Asking for that number rather than for the whole run still needs the same runs, because the
            // runs are what it was computed from.
            long promise = Math.min(requested, root.pattern().resultFor(rootRuns));
            Draft<K, L> top = expand(key, promise, null, 0, root);
            if (top == null)
                return Attempt.refused(refusal, about, rootRuns);
            List<PlanNode<K, L>> nodes = flatten(top);
            int depth = 0;
            for (PlanNode<K, L> node : nodes)
                depth = Math.max(depth, node.depth());
            return Attempt.of(new ProductionPlan<>(nodes, depth, ingredientItems, budget.spentByKey(), requested,
                    promise), rootRuns);
        }

        /**
         * The step that makes {@code needed} items of {@code key} for {@code parent}, with the steps it needs itself, or
         * {@code null} with {@link #refusal} set.
         * <p>
         * Every pattern for {@code key} is tried in ranking order until one of them yields a subtree, and everything a
         * failed attempt did is undone before the next one starts ({@link #tryPattern}).
         *
         * @param preset the pattern to use, for the ordered item, whose pattern is chosen before the walk so that the
         *               attempt can be parameterised by its runs; {@code null} everywhere else
         */
        private Draft<K, L> expand(K key, long needed, Draft<K, L> parent, int depth, StationPattern<K, L> preset) {
            // A stopped item is not planned at all (ADR-027, extended by ADR-032): otherwise this very click would
            // rebuild the same chain into the same machine that swallowed the last batch.
            if (input.isPaused(key))
                return refuse(PlanRefusal.PAUSED, key);
            // The chain has already promised to make this item further up, so making it out of itself is nonsense
            // whatever the racks hold. In practice the scan over the pattern's own ingredients below refuses such a
            // pattern one level earlier, and a pattern may not produce one of its own ingredients (ProductionPattern),
            // so this is the guard that keeps the walk finite if either of those two rules ever moves.
            if (path.contains(key))
                return refuse(PlanRefusal.LOOP, key);
            List<StationPattern<K, L>> candidates = preset != null ? List.of(preset)
                    : candidatesFor(input, ranked, key);
            if (candidates.isEmpty())
                // Nothing makes the ordered item at all, or the chain has reached an item the player has to supply.
                // The difference is the whole point of naming the item.
                return refuse(depth == 0 ? PlanRefusal.NO_PATTERN : PlanRefusal.MISSING_INGREDIENT, key);
            Reported<K> reported = new Reported<>();
            for (int candidate = 0; candidate < candidates.size(); candidate++) {
                // The preferred pattern is always tried; a fallback costs one of the walk's bounded extra attempts.
                if (candidate > 0 && extraAttempts-- <= 0)
                    break;
                Mark<K> mark = candidates.size() > 1 ? mark() : null;
                Draft<K, L> node = tryPattern(key, needed, parent, depth, candidates.get(candidate));
                if (node != null)
                    return node;
                reported.record(refusal, about);
                if (mark != null)
                    restore(mark);
            }
            return refuse(reported.reason(), reported.about());
        }

        /**
         * One attempt at making {@code needed} items of {@code key} with one given pattern: the node and its whole
         * subtree, or {@code null} with {@link #refusal} set. The caller undoes what a failed attempt spent.
         */
        private Draft<K, L> tryPattern(K key, long needed, Draft<K, L> parent, int depth,
                StationPattern<K, L> station) {
            ProductionPattern<K> pattern = station.pattern();
            // A pattern that would consume what a step further up makes closes the loop the other way round — two
            // patterns that are inverses of each other. Refused even when that item is in the racks: converting it
            // into itself is never what the click meant. It rules out this pattern, not the item.
            for (ProductionEntry<K> ingredient : pattern.ingredients()) {
                if (path.contains(ingredient.key()))
                    return refuse(PlanRefusal.LOOP, ingredient.key());
            }
            int runs = pattern.runsFor(needed);
            // An intermediate that does not fit under its own maximum never comes back: the input would back up, the
            // step above it would starve and the chain would time out with the batch gone. The ordered item itself is
            // not checked here — a player may order past their own maximum, and the request panel says so.
            if (depth > 0 && pattern.runsWithin(roomLeftFor(key)) < runs)
                return refuse(PlanRefusal.NO_ROOM, key);
            if (created.size() >= stepLimit)
                return refuse(input.slotsBind() ? PlanRefusal.ORDERS_BUSY : PlanRefusal.TOO_MANY_STEPS, key);
            long cost = (long) pattern.ingredientItems() * runs;
            if (ingredientItems + cost > input.limits().maxIngredientItems())
                return refuse(PlanRefusal.TOO_MANY_INGREDIENT_ITEMS, key);
            Draft<K, L> node = new Draft<>(station, runs, needed, depth, parent);
            created.add(node);
            ingredientItems += cost;
            plannedOutput.merge(key, (long) pattern.resultFor(runs), Long::sum);
            path.add(key);
            try {
                for (ProductionEntry<K> ingredient : pattern.ingredients()) {
                    long need = (long) ingredient.count() * runs;
                    // What the racks can pay for is paid for and is a leaf; only what is left over becomes a step, so
                    // three planks in stock of the eight a chest needs are used rather than made a second time.
                    long remainder = need - budget.take(ingredient.key(), need);
                    if (remainder <= 0L)
                        continue;
                    Draft<K, L> child = expand(ingredient.key(), remainder, node, depth + 1, null);
                    if (child == null)
                        return null; // the refusal of the deepest question is the one that is reported
                    node.children.add(child);
                }
            } finally {
                path.remove(path.size() - 1);
            }
            return node;
        }

        /** Room left under {@code key}'s own maximum, minus what this plan already means to make of it. */
        private long roomLeftFor(K key) {
            return Math.max(0L, input.roomFor(key) - plannedOutput.getOrDefault(key, 0L));
        }

        private Draft<K, L> refuse(PlanRefusal reason, K item) {
            refusal = reason;
            about = item;
            return null;
        }

        /** Everything a pattern attempt may change, so that giving up on it leaves no trace. */
        private Mark<K> mark() {
            return new Mark<>(created.size(), ingredientItems, new LinkedHashMap<>(plannedOutput),
                    budget.spentByKey());
        }

        /** Puts this walk back to what {@link #mark()} recorded, after a pattern attempt led nowhere. */
        private void restore(Mark<K> mark) {
            while (created.size() > mark.created())
                created.remove(created.size() - 1);
            ingredientItems = mark.ingredientItems();
            plannedOutput.clear();
            plannedOutput.putAll(mark.plannedOutput());
            budget.resetTo(mark.spent());
        }

        /** The drafts as nodes in dependency order: children before parents, the root last. */
        private List<PlanNode<K, L>> flatten(Draft<K, L> root) {
            List<Draft<K, L>> ordered = new ArrayList<>(created.size());
            postOrder(root, ordered);
            Map<Draft<K, L>, Integer> indices = new IdentityHashMap<>();
            for (int i = 0; i < ordered.size(); i++)
                indices.put(ordered.get(i), i);
            List<PlanNode<K, L>> nodes = new ArrayList<>(ordered.size());
            for (int i = 0; i < ordered.size(); i++) {
                Draft<K, L> draft = ordered.get(i);
                int parent = draft.parent == null ? PlanNode.NO_PARENT : indices.get(draft.parent);
                nodes.add(new PlanNode<>(i, parent, draft.depth, draft.station.station(), draft.station.pattern(),
                        draft.runs, draft.needed));
            }
            return nodes;
        }

        private void postOrder(Draft<K, L> draft, List<Draft<K, L>> out) {
            for (Draft<K, L> child : draft.children)
                postOrder(child, out);
            out.add(draft);
        }
    }
}
