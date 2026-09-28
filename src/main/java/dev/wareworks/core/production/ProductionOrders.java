package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The production orders of one warehouse controller ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * <b>Open orders promise ingredients.</b> {@link #outstandingIngredient} is what the controller subtracts from its
 * available stock, so ingredients an order still has to fetch cannot be handed to a terminal request in the meantime,
 * and a second production order sees them as taken and refuses instead of promising the same items twice.
 * <p>
 * <b>Finished orders are kept for a while</b> ({@link #prune}). A player who comes back to a station has to be able to
 * read that their order timed out or was cancelled; an order that vanished the moment it failed would look exactly like
 * one that was never placed. A finished order promises nothing ({@link ProductionOrder#outstanding}), so keeping it
 * costs only the line in the tooltip.
 * <p>
 * <b>Orders form plans</b> (M20, issue #4, ADR-032). An order that is a <b>step</b> names the ingredient line of
 * another order it makes its product for ({@link ProductionOrder#parentLine()}), and that single link is the whole
 * structure: a plan is the set of orders reachable through those links, its <b>root</b> is the one order without a
 * parent, and everything a screen or the dispatch wants to know — the children of a step, whether a parent still waits
 * for one, how deep a step sits, which step is actually working — is <i>derived</i> by walking the links
 * ({@link #childrenOf}, {@link #hasOpenChildren}, {@link #depthOf}, {@link #planOf}, {@link #frontierOf}). Nothing about
 * a plan is stored twice, so nothing can disagree with anything else.
 * <p>
 * Two indices make those walks cheap: line id → owning order, and parent line id → the orders feeding it. Both are
 * maintained by {@link #add}, {@link #addAll}, {@link #detach}, {@link #prune}, {@link #clear} and {@link #restore},
 * and every walk carries a visited set, so even hand-edited save data that names itself as its own ancestor terminates
 * ({@link #validatePlans}).
 * <p>
 * <b>A chain that cannot be finished ends, and ends bounded.</b> Three rules do all of it, and none of them needs a
 * state of its own:
 * <ul>
 * <li>an order with an open child <b>fetches nothing</b> ({@link #hasOpenChildren}, asked by the caller's supply
 * needs) — a machine cannot run on a partial set, so a failing chain must not leave half-sets of ingredients in
 * several machines;</li>
 * <li>and therefore its <b>deadline does not run</b> either, and starts over when its last step ends
 * ({@link #timeOut});</li>
 * <li>an order that ends badly ends the rest of its plan with it ({@link #failPlan}): every order above it is
 * cancelled, and every other one is cancelled if it has handed nothing over or left running if its ingredients are
 * already in a machine. What that cost is one number, {@link #unrecoveredOf}.</li>
 * </ul>
 * <p>
 * Pure Java, not thread-safe (server thread only). The controller persists the orders and restores them with
 * {@link #restore}.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public final class ProductionOrders<K, L> {
    /** Smallest allowed value of {@link #maxOpenOrders()}. */
    public static final int MIN_OPEN_ORDERS = 1;

    private final Map<UUID, ProductionOrder<K, L>> orders = new LinkedHashMap<>();
    /** Ingredient line id → the order that owns it, so {@link #byLine} is a lookup instead of a scan. */
    private final Map<UUID, UUID> lineOwners = new HashMap<>();
    /** Parent line id → the orders making that ingredient, in creation order: the plan structure, read downwards. */
    private final Map<UUID, List<UUID>> childrenByParentLine = new HashMap<>();
    /**
     * Whether two orders ever claimed the same ingredient line id. Only crafted or corrupted save data can do that
     * (line ids are random), and {@link #byLine} then answers with the first claimant exactly as the old scan did — but
     * removing that claimant has to hand the line back to the other one, and this flag is what keeps that rescan off
     * the normal path.
     */
    private boolean sharedLineIds;
    /**
     * The orders the last {@link #timeOut} pass left alone because they were waiting for a step of their own plan. An
     * order that is in here and is not blocked any more starts its timeout over, once. Nothing about it is saved:
     * deadlines are not persisted either, and a restore gives every open order a fresh one anyway.
     */
    private final Set<UUID> blockedLastPass = new HashSet<>();
    private int maxOpenOrders;

    public ProductionOrders(int maxOpenOrders) {
        setMaxOpenOrders(maxOpenOrders);
    }

    public int maxOpenOrders() {
        return maxOpenOrders;
    }

    /** Changes the cap (e.g. after a config change). Orders above a lowered cap are kept until they finish. */
    public void setMaxOpenOrders(int max) {
        if (max < MIN_OPEN_ORDERS)
            throw new IllegalArgumentException("maxOpenOrders must be at least " + MIN_OPEN_ORDERS + ": " + max);
        this.maxOpenOrders = max;
    }

    // --- changes -------------------------------------------------------------------------------------------------

    /**
     * Adds {@code order}.
     *
     * @return whether it was added; false when {@link #maxOpenOrders()} orders are already open or the id is in use
     */
    public boolean add(ProductionOrder<K, L> order) {
        Objects.requireNonNull(order, "order");
        if (orders.containsKey(order.id()) || (order.isOpen() && isFull()))
            return false;
        store(order);
        return true;
    }

    /**
     * Adds a whole production plan <b>all or nothing</b> (M20, ADR-032): either every order of {@code batch} exists
     * afterwards or none does.
     * <p>
     * This is what makes accepting a chain atomic. The moment the orders exist, every ingredient <i>and</i> every
     * intermediate of the plan is promised by an ordinary supply line, which is the plan's entire reservation — so the
     * plan never has to stay valid, but it must not be able to half-exist: a parent without its children would fetch
     * ingredients for a run that nothing is going to complete.
     * <p>
     * {@code batch} is added in list order, so the caller passes <b>children before parents</b>
     * ({@link ProductionPlan#nodes()} is already in that order). The batch is refused when
     * <ul>
     * <li>it repeats an id, or names one this collection already has;</li>
     * <li>its open orders would take the open count past {@link #maxOpenOrders()} — the plan has to fit the free slots
     * as a whole, which is what the planner's own step bound is measured against;</li>
     * <li>a step's parent line is not a line of another order of the batch or of an order already here, or the parent
     * links form a cycle. A step waiting for a parent that does not exist is exactly what must never come into being
     * ({@link #validatePlans} is the same rule for save data).</li>
     * </ul>
     *
     * @return whether the batch was added; an empty batch changes nothing and is accepted
     */
    public boolean addAll(List<ProductionOrder<K, L>> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty())
            return true;
        Map<UUID, ProductionOrder<K, L>> byId = new LinkedHashMap<>();
        Map<UUID, UUID> lineOwnersInBatch = new HashMap<>();
        int open = 0;
        for (ProductionOrder<K, L> order : batch) {
            Objects.requireNonNull(order, "order");
            if (orders.containsKey(order.id()) || byId.put(order.id(), order) != null)
                return false;
            if (order.isOpen())
                open++;
            for (SupplyLine<K> line : order.lines())
                lineOwnersInBatch.putIfAbsent(line.id(), order.id());
        }
        if (openCount() + open > maxOpenOrders)
            return false;
        for (ProductionOrder<K, L> order : batch) {
            if (!parentChainResolves(order, byId, lineOwnersInBatch))
                return false;
        }
        for (ProductionOrder<K, L> order : batch)
            store(order);
        return true;
    }

    /**
     * Whether {@code order}'s parent links lead to a root through orders that exist, without repeating one. Ancestors
     * are looked up in {@code batch} first and in this collection afterwards, so a plan may be checked before any of it
     * is stored.
     */
    private boolean parentChainResolves(ProductionOrder<K, L> order, Map<UUID, ProductionOrder<K, L>> batch,
            Map<UUID, UUID> batchLineOwners) {
        Set<UUID> seen = new HashSet<>();
        ProductionOrder<K, L> current = order;
        while (current.isStep()) {
            if (!seen.add(current.id()))
                return false; // a cycle: nothing in it could ever be unblocked
            UUID parentLine = current.parentLine().orElseThrow();
            UUID ownerId = batchLineOwners.get(parentLine);
            if (ownerId == null)
                ownerId = lineOwners.get(parentLine);
            if (ownerId == null || ownerId.equals(current.id()))
                return false; // no such line, or an order waiting for itself
            ProductionOrder<K, L> parent = batch.get(ownerId);
            current = parent != null ? parent : orders.get(ownerId);
            if (current == null)
                return false;
        }
        return true;
    }

    /**
     * Counts a crane delivery of {@code amount} items on the ingredient line {@code lineId}.
     *
     * @return the order after the delivery, or empty when no open order has that line
     */
    public Optional<ProductionOrder<K, L>> deliver(UUID lineId, int amount, long now, long timeoutTicks) {
        return replace(byLine(lineId), order -> order.withDelivered(lineId, amount, now, timeoutTicks));
    }

    /**
     * The player's machine took the ingredients of order {@code orderId} out of its station's buffer: a
     * {@link ProductionOrderState#DELIVERED} order moves on to {@link ProductionOrderState#WAITING_FOR_RESULT}.
     * <p>
     * This is scoped to <b>one order</b>, not to its station. A station runs as many orders as it has patterns, and
     * the caller's observation ("the buffer no longer holds <i>these</i> ingredients") only ever answers for the
     * order it was asked about: advancing every order of that station would move one whose own ingredients are still
     * visibly lying in the buffer, and would push its timeout out by a step that never happened to it.
     *
     * @return the order after the change, or empty when it is unknown or was not waiting to be taken
     */
    public Optional<ProductionOrder<K, L>> ingredientsTaken(UUID orderId, long now, long timeoutTicks) {
        Objects.requireNonNull(orderId, "orderId");
        ProductionOrder<K, L> order = orders.get(orderId);
        if (order == null)
            return Optional.empty();
        ProductionOrder<K, L> next = order.withIngredientsTaken(now, timeoutTicks);
        if (next == order)
            return Optional.empty();
        store(next);
        return Optional.of(next);
    }

    /**
     * What one observation did: the orders it changed, and how many result items it credited in total.
     *
     * @param changed  the orders whose state or counters changed (filter for {@link ProductionOrderState#COMPLETE} to
     *                 find the finished ones)
     * @param credited result items this observation counted towards those orders, over all of them
     */
    public record Observation<K, L>(List<ProductionOrder<K, L>> changed, long credited) {
        public Observation {
            changed = List.copyOf(Objects.requireNonNull(changed, "changed"));
            credited = Math.max(0L, credited);
        }

        /** Nothing changed and nothing was credited. */
        public static <K, L> Observation<K, L> none() {
            return new Observation<>(List.of(), 0L);
        }

        public boolean isEmpty() {
            return changed.isEmpty();
        }
    }

    /**
     * Credits {@code amount} result items of {@code key} that really arrived in the warehouse — the crane stored them
     * out of one of the aisle's own warehouse inputs ({@link ProductionOrder#withStored}).
     * <p>
     * This is the channel an <b>automatic</b> order is completed by, and the only one, which is what makes a rule's
     * safety stop mean something (M15 part 2). Ordinary orders are credited here too, and the caller then keeps the
     * credited amount off the stock-level channel, so the same physical batch is never counted twice
     * ({@link #observeResult}).
     * <p>
     * One arrival is credited <b>once</b>, and the order that needs it most takes it first: the orders that have
     * <b>no other channel at all</b> come before the ones that can still be completed by a stock level
     * ({@link ProductionOrder#countsStockLevels()}), and among those the ones whose machine has really taken the
     * ingredients come first. Creation order decides the rest, as it always did.
     * <p>
     * <b>Why not plain creation order</b> (M20 review fix). A step of a plan and an automatic order can only ever be
     * completed here — a level rise says nothing about which machine made the items, which is the whole of the safety
     * stop (M15 part 2, ADR-026, ADR-032). An older ordinary order for the same item counts arrivals from the moment it
     * is created, i.e. while its own crane is still fetching, so in plain creation order it took the batch a step's
     * machine had just made and gave nothing back: the step counted nothing, its deadline was not pushed out, and it
     * timed out minutes later — arming a safety stop for an item whose machines were all working, and taking the rest of
     * its plan with it. An order that has a second channel loses nothing by being served second: the items are in the
     * stock index either way.
     */
    public Observation<K, L> observeStored(K key, long amount, long now, long timeoutTicks) {
        Objects.requireNonNull(key, "key");
        if (amount <= 0L)
            return Observation.none();
        List<ProductionOrder<K, L>> changed = new ArrayList<>();
        long left = amount;
        for (ProductionOrder<K, L> order : arrivalOrder(key)) {
            if (left <= 0L)
                break;
            ProductionOrder<K, L> next = order.withStored(left, now, timeoutTicks);
            if (next == order)
                continue;
            left -= next.produced() - order.produced();
            store(next);
            changed.add(next);
        }
        return new Observation<>(changed, amount - Math.max(0L, left));
    }

    /**
     * The orders an arrival of {@code key} may be credited to, in the order it is offered to them
     * ({@link #observeStored}): those without a second channel first, the ones whose machine has taken the ingredients
     * before the ones it has not, and creation order within each group (the sort is stable).
     */
    private List<ProductionOrder<K, L>> arrivalOrder(K key) {
        List<ProductionOrder<K, L>> candidates = new ArrayList<>();
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.result().equals(key) && order.countsArrivals())
                candidates.add(order);
        }
        if (candidates.size() > 1)
            candidates.sort(Comparator.comparingInt(ProductionOrders::arrivalRank));
        return candidates;
    }

    /** 0 for an order this arrival is the only hope of, 1 for the rest of those, 2 for one with a second channel. */
    private static int arrivalRank(ProductionOrder<?, ?> order) {
        if (order.countsStockLevels())
            return 2;
        return order.state() == ProductionOrderState.WAITING_FOR_RESULT ? 0 : 1;
    }

    /**
     * Lets every open order waiting for {@code key} observe its current stock level; orders that have seen enough
     * become {@link ProductionOrderState#COMPLETE}.
     * <p>
     * It returns every order this call <b>changed</b>, not only the completed ones, because the caller has to know
     * whether anything was written at all: observing an unchanged stock level must not mark the controller dirty on
     * every dispatch interval for as long as an order is open.
     * <p>
     * <b>Automatic orders are not counted here</b> ({@link ProductionOrder#countsStockLevels()}): a level rise says
     * nothing about where the items came from, and for an automatic order that is the difference between "the machine
     * works" and "the machine ate the batch" (M15 part 2).
     *
     * @return the orders whose state or counters changed in this call (filter for
     * {@link ProductionOrderState#COMPLETE} to find the finished ones)
     */
    public List<ProductionOrder<K, L>> observeResult(K key, long stockNow, long now, long timeoutTicks) {
        return observeResult(key, stockNow, now, timeoutTicks, 0L);
    }

    /**
     * {@link #observeResult(Object, long, long, long)} with the part of the rise that {@link #observeStored} has
     * already credited since the last observation of {@code key}. Those items are in the level too, so counting them
     * again would complete an order nothing was made for.
     *
     * @param alreadyCredited result items of {@code key} already counted through the arrival channel; negative counts
     *                        as 0
     */
    public List<ProductionOrder<K, L>> observeResult(K key, long stockNow, long now, long timeoutTicks,
            long alreadyCredited) {
        Objects.requireNonNull(key, "key");
        List<ProductionOrder<K, L>> changed = new ArrayList<>();
        long credited = Math.max(0L, alreadyCredited);
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            if (!order.isOpen() || !order.result().equals(key))
                continue;
            // One arrival is credited once, to the open orders in creation order: the oldest takes what it still
            // waits for, the next only what is left of that same increase. Crediting every order with the full
            // increase would complete an order nothing was made for, and a phantom-complete order never gives its
            // backing request the unproduced amount back and can never time out either (§3.5.3).
            long counted = Math.min(Math.max(0L, order.observableGain(stockNow) - credited), order.outstandingResult());
            ProductionOrder<K, L> next = order.withResultStock(stockNow, counted, now, timeoutTicks);
            if (next == order)
                continue;
            credited += counted;
            store(next);
            changed.add(next);
        }
        return List.copyOf(changed);
    }

    /**
     * Times out every open order whose deadline {@code now} has reached — <b>except one that is waiting for a step of
     * its own plan</b> (M20, ADR-032).
     * <p>
     * <b>A blocked order's deadline does not run.</b> An order with an open child fetches nothing
     * ({@link #hasOpenChildren}), so there is nothing it could be making progress on and nothing wrong with it: what
     * takes time is the step below it, and that step has a running deadline of its own. Without this a deep chain could
     * never finish, because the root's timeout would expire while the machines under it were working perfectly. The
     * invariant this leaves is the one that makes a plan collapse instead of hanging: <i>the deepest open order of a
     * plan always has a running deadline</i>.
     * <p>
     * <b>An order that stops being blocked starts its timeout over</b>, in the first call that sees it unblocked. Its
     * own deadline was set when the plan was created and is long past by then, so without the restart it would time out
     * in the very tick its last step ended — and the chain would die at the moment it was ready to run. Deadlines are
     * not persisted, so a restart needs no save ({@link #restartDeadlines} gives every restored order a fresh one).
     * <p>
     * Which orders are blocked is decided <b>once</b>, before anything is timed out, so an order that is unblocked by a
     * child timing out in this very call is not timed out in the same pass: it is the caller's
     * {@code failPlan(child)} that ends it, as a cancellation, with the child's loss reported against the plan.
     *
     * @param timeoutTicks ticks without progress an order gets when it stops being blocked
     * @return the orders that timed out in this call
     */
    public List<ProductionOrder<K, L>> timeOut(long now, long timeoutTicks) {
        Set<UUID> blocked = blockedOrderIds();
        if (!blockedLastPass.isEmpty()) {
            for (UUID id : blockedLastPass) {
                ProductionOrder<K, L> unblocked = blocked.contains(id) ? null : orders.get(id);
                if (unblocked != null && unblocked.isOpen())
                    store(unblocked.withDeadline(saturatedAdd(now, Math.max(1L, timeoutTicks))));
            }
            blockedLastPass.clear();
        }
        blockedLastPass.addAll(blocked);
        List<ProductionOrder<K, L>> timedOut = new ArrayList<>();
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            if (blocked.contains(order.id()))
                continue;
            ProductionOrder<K, L> next = order.timedOutAt(now);
            if (next == order)
                continue;
            store(next);
            timedOut.add(next);
        }
        return List.copyOf(timedOut);
    }

    /** The open orders that are waiting for a step of their own plan and therefore fetch nothing and cannot time out. */
    private Set<UUID> blockedOrderIds() {
        if (childrenByParentLine.isEmpty())
            return Set.of();
        Set<UUID> blocked = new HashSet<>();
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.isOpen() && hasOpenChildren(order.id()))
                blocked.add(order.id());
        }
        return blocked;
    }

    /**
     * Ends the rest of the plan that {@code nodeId} belongs to, because that order ended <b>badly</b> — it timed out,
     * was cancelled, or lost its station (M20, ADR-032). The failing order itself is not touched: the caller has already
     * ended it.
     * <p>
     * <b>Fail upward.</b> Every open <b>ancestor</b> is cancelled, in this call. An ancestor is waiting for an
     * ingredient that is now never going to be made, so leaving it open would mean an order fetching the rest of its
     * ingredients for a run that can never happen. Because a blocked parent fetches nothing ({@link #hasOpenChildren}),
     * an ancestor has handed <i>nothing</i> to a machine, so cancelling it costs nothing at all — the guard pays off
     * twice.
     * <p>
     * <b>Cancel downward only what has cost nothing.</b> Every other open order of the plan — the descendants of the
     * failing order and the other branches of its plan — is judged on its own:
     * <ul>
     * <li>nothing delivered → <b>cancelled</b>. The crane aborts before the pick or reroutes what it already holds back
     * into storage, and the ingredients it still promised are free again;</li>
     * <li>ingredients already at a machine → <b>detached</b> ({@link ProductionOrder#withoutParentLine()}) and left
     * running. Those items are in the machine and nothing takes them back out, so letting the order finish turns them
     * into a product the player keeps instead of a loss ({@code docs/warehouse-system.md} §3.5.4). It keeps the arrival
     * channel, so a machine that swallowed <i>its</i> batch is still caught.</li>
     * </ul>
     * Afterwards no open order of the plan is a step of anything, so nothing is left waiting for an order that has
     * ended, and running this again finds nothing to do.
     */
    public PlanFailure<K, L> failPlan(UUID nodeId, long now) {
        Objects.requireNonNull(nodeId, "nodeId");
        ProductionOrder<K, L> node = orders.get(nodeId);
        if (node == null)
            return PlanFailure.none();
        List<ProductionOrder<K, L>> plan = planOf(nodeId);
        if (plan.size() <= 1)
            return PlanFailure.none(); // an order in no plan: its own ending is the whole story
        Set<UUID> ancestors = ancestorIdsOf(node);
        List<ProductionOrder<K, L>> cancelled = new ArrayList<>();
        List<ProductionOrder<K, L>> detached = new ArrayList<>();
        for (ProductionOrder<K, L> member : plan) {
            if (member.id().equals(nodeId) || !member.isOpen())
                continue;
            if (ancestors.contains(member.id()) || member.deliveredIngredients() == 0)
                cancel(member.id(), now).ifPresent(cancelled::add);
            else
                detach(member.id()).ifPresent(detached::add);
        }
        return new PlanFailure<>(cancelled, detached, unrecoveredIn(plan));
    }

    /**
     * What ending one order did to the rest of its plan ({@link #failPlan}).
     *
     * @param cancelled   orders this call ended. Each is finished, promises nothing any more and needs the caller's
     *                    ordinary cleanup for an ended order (crane jobs, a backing request, the safety stop)
     * @param detached    orders this call took out of the plan and left <b>running</b>: their ingredients are already in
     *                    a machine, so their product still comes back and lands in stock as items nobody promised
     * @param unrecovered ingredient items the whole plan handed to machines and will never see a result for — the one
     *                    number a player has to be told ({@code docs/warehouse-system.md} §3.5.4)
     */
    public record PlanFailure<K, L>(List<ProductionOrder<K, L>> cancelled, List<ProductionOrder<K, L>> detached,
                                    long unrecovered) {
        public PlanFailure {
            cancelled = List.copyOf(Objects.requireNonNull(cancelled, "cancelled"));
            detached = List.copyOf(Objects.requireNonNull(detached, "detached"));
            unrecovered = Math.max(0L, unrecovered);
        }

        /** Nothing else was in the plan, so nothing else changed. */
        public static <K, L> PlanFailure<K, L> none() {
            return new PlanFailure<>(List.of(), List.of(), 0L);
        }

        /** Whether this failure changed no other order. */
        public boolean isEmpty() {
            return cancelled.isEmpty() && detached.isEmpty();
        }
    }

    /**
     * Ingredient items the plan {@code orderId} belongs to handed to machines and never got a result for: what this
     * chain cost ({@code docs/warehouse-system.md} §3.5.4). 0 while nothing of it has ended badly.
     */
    public long unrecoveredOf(UUID orderId) {
        return unrecoveredIn(planOf(orderId));
    }

    /** {@link #unrecoveredOf} over the orders of a plan, read in their <b>current</b> state. */
    private long unrecoveredIn(List<ProductionOrder<K, L>> plan) {
        long total = 0L;
        for (ProductionOrder<K, L> member : plan) {
            ProductionOrder<K, L> current = orders.get(member.id());
            if (current != null && current.endedWithLostIngredients())
                total += current.deliveredIngredients();
        }
        return total;
    }

    /** The ids of the orders above {@code order} in its plan; a cycle in hand-edited data stops the walk. */
    private Set<UUID> ancestorIdsOf(ProductionOrder<K, L> order) {
        Set<UUID> ancestors = new HashSet<>();
        ProductionOrder<K, L> current = order;
        while (current.isStep()) {
            ProductionOrder<K, L> parent = parentOf(current).orElse(null);
            if (parent == null || parent.id().equals(current.id()) || !ancestors.add(parent.id()))
                break;
            current = parent;
        }
        return ancestors;
    }

    /** Cancels order {@code id}; returns the cancelled order, or empty when it is unknown or already finished. */
    public Optional<ProductionOrder<K, L>> cancel(UUID id, long now) {
        return replace(get(id).filter(ProductionOrder::isOpen), order -> order.cancelled(now));
    }

    /** Cancels every open order for the production station at {@code station} (it was removed or turned away). */
    public List<ProductionOrder<K, L>> cancelFor(L station, long now) {
        Objects.requireNonNull(station, "station");
        List<ProductionOrder<K, L>> cancelled = new ArrayList<>();
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            if (order.isOpen() && order.station().equals(station))
                cancel(order.id(), now).ifPresent(cancelled::add);
        }
        return List.copyOf(cancelled);
    }

    /** Forgets the backing request of every order that names {@code requestId} (the request is gone). */
    public void detachRequest(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            if (order.backingRequest().filter(requestId::equals).isPresent())
                store(order.withoutBackingRequest());
        }
    }

    /**
     * Removes finished orders that have been finished for at least {@code retentionTicks}, using their deadline as the
     * moment they ended (a finished order's deadline is no longer pushed out).
     * <p>
     * <b>A whole plan ages out together</b> (M20, ADR-032): a finished step is kept for as long as <i>any</i> order of
     * its plan is still open, and once none is, every order of that plan is forgotten together, counted from the last
     * one that ended. Three things depend on it — a player who comes back can read the whole chain and not a torn-off
     * piece of it, the step count on a parent's line is stable while the plan runs, and no state is ever read off a
     * node that has been pruned while its plan was still going. An order that is in no plan is a plan of one, which is
     * exactly the rule as it always was.
     *
     * @return the number of removed orders
     */
    public int prune(long now, long retentionTicks) {
        long retention = Math.max(0L, retentionTicks);
        if (childrenByParentLine.isEmpty()) {
            // No order is a step of anything, so every plan is a plan of one: the pre-M20 rule, at the pre-M20 cost.
            int removed = 0;
            for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
                if (order.isOpen() || now < saturatedAdd(order.deadlineTick(), retention))
                    continue;
                forget(order.id());
                removed++;
            }
            return removed;
        }
        Map<UUID, List<UUID>> plans = new LinkedHashMap<>();
        for (ProductionOrder<K, L> order : orders.values())
            plans.computeIfAbsent(rootIdOf(order), key -> new ArrayList<>()).add(order.id());
        int removed = 0;
        for (List<UUID> plan : plans.values()) {
            boolean anyOpen = false;
            long lastEnd = Long.MIN_VALUE;
            for (UUID id : plan) {
                ProductionOrder<K, L> order = orders.get(id);
                if (order == null)
                    continue;
                if (order.isOpen()) {
                    anyOpen = true;
                    break;
                }
                lastEnd = Math.max(lastEnd, order.deadlineTick());
            }
            if (anyOpen || lastEnd == Long.MIN_VALUE || now < saturatedAdd(lastEnd, retention))
                continue;
            for (UUID id : plan) {
                if (orders.containsKey(id)) {
                    forget(id);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Removes every order (the aisle is gone). */
    public void clear() {
        orders.clear();
        lineOwners.clear();
        childrenByParentLine.clear();
        blockedLastPass.clear();
        sharedLineIds = false;
    }

    /**
     * Replaces the orders with persisted ones, in the given order. Entries with a repeated id are skipped and the cap
     * is not applied, so a lowered {@code maxProductionOrders} never deletes a saved order.
     * <p>
     * Saved plans are <b>not</b> checked here: the parent links are restored exactly as they were written, and
     * {@link #validatePlans} is what the caller runs once afterwards to end the ones a truncated or edited save broke.
     *
     * @return the number of restored orders
     */
    public int restore(Collection<ProductionOrder<K, L>> saved) {
        Objects.requireNonNull(saved, "saved");
        clear();
        for (ProductionOrder<K, L> order : saved) {
            if (order != null && !orders.containsKey(order.id()))
                store(order);
        }
        return orders.size();
    }

    /**
     * This order without its parent line: it stops being a step and runs on as an ordinary order
     * ({@link ProductionOrder#withoutParentLine()}).
     *
     * @return the detached order, or empty when {@code orderId} is unknown or was no step
     */
    public Optional<ProductionOrder<K, L>> detach(UUID orderId) {
        return replace(get(orderId).filter(ProductionOrder::isStep), ProductionOrder::withoutParentLine);
    }

    /**
     * Ends every step whose plan the save data does not describe any more — run <b>once after {@link #restore}</b>
     * (M20, ADR-032).
     * <p>
     * A save can be truncated ({@code MAX_SAVED_ORDERS}), edited by hand or written by a broken tool, and the one thing
     * that must never come out of it is <b>a step waiting for a parent that does not exist</b>: its product is for
     * nobody, and the whole point of a plan is that the crane fetches nothing for a run that cannot happen. A step is
     * therefore ended when its chain of parents does not lead to a root through orders that are still here and still
     * open — which also covers a chain that is cyclic or names the step itself, because the walk carries a visited set
     * and any repetition is a break.
     * <p>
     * What "ended" means follows the same rule as a failing plan, so nothing is lost that has not been lost already
     * ({@code docs/warehouse-system.md} §3.5.4):
     * <ul>
     * <li>nothing delivered yet → <b>cancelled</b>. It cost nothing, and cancelling frees the ingredients it still
     * promised;</li>
     * <li>ingredients already at the machine → <b>detached</b> and left running. Those items are in the machine and
     * nothing takes them back out, so the product comes back and lands in stock as items the player keeps.</li>
     * </ul>
     * Only <b>open</b> steps are looked at: a finished one fetches nothing and is only read, so its link is left as the
     * save wrote it — which also keeps it grouped with the rest of its plan while that plan ages out ({@link #prune}).
     * Running this twice therefore finds nothing the second time.
     * <b>No safety stop can be armed by this</b> (ADR-027): a step is never an automatic order
     * ({@link ProductionOrder} keeps the two exclusive), and a save-integrity failure is no evidence about anybody's
     * machine — it must not wedge the aisle's production behind a pause that only a player can lift.
     *
     * @return the orders this call changed, in creation order. A cancelled one is finished ({@code !isOpen()}) and its
     * caller-side cleanup is the ordinary one for an ended order; a detached one is still open and needs nothing but a
     * save
     */
    public List<ProductionOrder<K, L>> validatePlans(long now) {
        if (childrenByParentLine.isEmpty())
            return List.of();
        List<ProductionOrder<K, L>> changed = new ArrayList<>();
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            if (!order.isOpen() || !order.isStep() || planOfIsIntact(order))
                continue;
            Optional<ProductionOrder<K, L>> ended = order.deliveredIngredients() == 0
                    ? cancel(order.id(), now)
                    : detach(order.id());
            ended.ifPresent(changed::add);
        }
        return List.copyOf(changed);
    }

    /**
     * Whether {@code order}'s parent links lead to a root, hop by hop, through orders that are here and still open. A
     * finished ancestor can never use what this step is making, so a step under one is as broken as one whose parent was
     * truncated away; the walk carries a visited set, so a cycle answers false instead of spinning.
     */
    private boolean planOfIsIntact(ProductionOrder<K, L> order) {
        Set<UUID> seen = new HashSet<>();
        ProductionOrder<K, L> current = order;
        while (current.isStep()) {
            if (!seen.add(current.id()))
                return false;
            ProductionOrder<K, L> parent = parentOf(current).orElse(null);
            if (parent == null || parent.id().equals(current.id()) || !parent.isOpen())
                return false;
            current = parent;
        }
        return true;
    }

    // --- queries -------------------------------------------------------------------------------------------------

    public Optional<ProductionOrder<K, L>> get(UUID id) {
        return Optional.ofNullable(orders.get(Objects.requireNonNull(id, "id")));
    }

    /** The order owning the ingredient line {@code lineId}. */
    public Optional<ProductionOrder<K, L>> byLine(UUID lineId) {
        Objects.requireNonNull(lineId, "lineId");
        return Optional.ofNullable(lineOwners.get(lineId)).map(orders::get);
    }

    // --- plans ---------------------------------------------------------------------------------------------------

    /**
     * The order whose ingredient line {@code order} is a step of (M20): the order waiting for what this one makes.
     * Empty for a root, and empty for a step whose parent is gone — an <b>orphan</b>, which is what
     * {@link #validatePlans} ends after a restore. It answers literally, so hand-edited data that points an order at
     * its own line answers with that order; every walk here is written for that.
     */
    public Optional<ProductionOrder<K, L>> parentOf(ProductionOrder<K, L> order) {
        Objects.requireNonNull(order, "order");
        return order.parentLine().flatMap(this::byLine);
    }

    /** The orders making an ingredient for {@code orderId}, in creation order (empty for an order nothing feeds). */
    public List<ProductionOrder<K, L>> childrenOf(UUID orderId) {
        Set<UUID> ids = childIdsOf(orderId);
        if (ids.isEmpty())
            return List.of();
        List<ProductionOrder<K, L>> children = new ArrayList<>(ids.size());
        for (ProductionOrder<K, L> order : orders.values()) {
            if (ids.contains(order.id()))
                children.add(order);
        }
        return List.copyOf(children);
    }

    /**
     * Whether any order is still making an ingredient for {@code orderId}.
     * <p>
     * This is the question the dispatch asks of every open order: a parent with an open child fetches <b>nothing</b>,
     * not even the ingredients it could pay for, because a machine cannot run on a partial set and a funnel would
     * happily push half a run into it — which is exactly what turns a deep chain into a deep loss (ADR-032). It is a
     * lookup per ingredient line, not a scan.
     */
    public boolean hasOpenChildren(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        ProductionOrder<K, L> order = orders.get(orderId);
        if (order == null || childrenByParentLine.isEmpty())
            return false;
        for (SupplyLine<K> line : order.lines()) {
            for (UUID childId : childrenByParentLine.getOrDefault(line.id(), List.of())) {
                ProductionOrder<K, L> child = orders.get(childId);
                if (child != null && child.isOpen() && !childId.equals(orderId))
                    return true;
            }
        }
        return false;
    }

    /**
     * Every order below {@code orderId} in its plan, nearest first: its children, then theirs, in creation order within
     * each level. {@link #planOf} is the same set in dependency order, which is what creating or reading a whole plan
     * wants.
     */
    public List<ProductionOrder<K, L>> descendantsOf(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        if (!orders.containsKey(orderId) || childrenByParentLine.isEmpty())
            return List.of();
        List<ProductionOrder<K, L>> found = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        seen.add(orderId);
        List<UUID> level = new ArrayList<>(List.of(orderId));
        while (!level.isEmpty()) {
            List<UUID> next = new ArrayList<>();
            for (UUID id : level) {
                for (ProductionOrder<K, L> child : childrenOf(id)) {
                    if (!seen.add(child.id()))
                        continue; // hand-edited data cannot make this walk loop
                    found.add(child);
                    next.add(child.id());
                }
            }
            level = next;
        }
        return List.copyOf(found);
    }

    /**
     * The root of the plan {@code orderId} belongs to: the order at the top of its parent chain, or the order itself
     * when it is a root, an orphan or unknown to this collection.
     */
    public Optional<ProductionOrder<K, L>> rootOf(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        ProductionOrder<K, L> order = orders.get(orderId);
        if (order == null)
            return Optional.empty();
        Set<UUID> seen = new HashSet<>();
        ProductionOrder<K, L> current = order;
        while (current.isStep() && seen.add(current.id())) {
            ProductionOrder<K, L> parent = parentOf(current).orElse(null);
            if (parent == null || seen.contains(parent.id()))
                break; // an orphan, or a cycle: the walk stops where it stands
            current = parent;
        }
        return Optional.of(current);
    }

    /**
     * How many parent links {@code orderId} is below its root: 0 for a root, an orphan or an unknown order, 1 for the
     * step that makes an ingredient of a root. A cycle stops the walk, so this always answers.
     */
    public int depthOf(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        ProductionOrder<K, L> order = orders.get(orderId);
        if (order == null)
            return 0;
        int depth = 0;
        Set<UUID> seen = new HashSet<>();
        ProductionOrder<K, L> current = order;
        while (current.isStep() && seen.add(current.id())) {
            ProductionOrder<K, L> parent = parentOf(current).orElse(null);
            if (parent == null || seen.contains(parent.id()))
                break;
            current = parent;
            depth++;
        }
        return depth;
    }

    /**
     * The whole plan {@code orderId} belongs to, <b>in dependency order</b>: children before parents, the root last —
     * the same order {@link ProductionPlan#nodes()} uses, so a caller can read a running plan exactly as it created it.
     * Empty for an unknown order; a single order for one that is in no plan.
     */
    public List<ProductionOrder<K, L>> planOf(UUID orderId) {
        Optional<ProductionOrder<K, L>> root = rootOf(orderId);
        if (root.isEmpty())
            return List.of();
        List<ProductionOrder<K, L>> nodes = new ArrayList<>(descendantsOf(root.get().id()));
        Map<UUID, Integer> depths = new HashMap<>();
        for (ProductionOrder<K, L> node : nodes)
            depths.put(node.id(), depthOf(node.id()));
        // Deepest first, so every step comes before the step it feeds; ties keep creation order (the sort is stable).
        nodes.sort(Comparator.comparingInt((ProductionOrder<K, L> node) -> depths.get(node.id())).reversed());
        nodes.add(root.get());
        return List.copyOf(nodes);
    }

    /**
     * The step of the plan under {@code rootId} that is really working: the <b>deepest open</b> order of the plan, ties
     * by creation order. That is the one a screen names as the plan's current step, because every shallower open order
     * is waiting for it ({@link #hasOpenChildren}).
     */
    public Optional<ProductionOrder<K, L>> frontierOf(UUID rootId) {
        Set<UUID> plan = new HashSet<>();
        for (ProductionOrder<K, L> node : planOf(rootId))
            plan.add(node.id());
        ProductionOrder<K, L> best = null;
        int bestDepth = -1;
        for (ProductionOrder<K, L> order : orders.values()) {
            if (!order.isOpen() || !plan.contains(order.id()))
                continue;
            int depth = depthOf(order.id());
            if (depth > bestDepth) {
                best = order;
                bestDepth = depth;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Open orders that are a step of a plan ({@link ProductionOrder#isStep()}). */
    public int openStepCount() {
        int count = 0;
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.isOpen() && order.isStep())
                count++;
        }
        return count;
    }

    /**
     * Whether {@code lineId} is an ingredient line of an <b>open</b> order that still needs items (the ids
     * {@code SUPPLY} jobs carry). A line whose items have all arrived answers false, so the crane detaches the id from
     * its job exactly as it does for a request that is finished.
     */
    public boolean hasOpenLine(UUID lineId) {
        return byLine(lineId).filter(ProductionOrder::isOpen).flatMap(order -> order.line(lineId))
                .filter(line -> !line.isComplete()).isPresent();
    }

    /**
     * Gives every order a fresh deadline, for a world reload: deadlines are not persisted, so a restored <b>open</b>
     * order starts its timeout over instead of timing out immediately because the world was closed for a while
     * ({@code docs/warehouse-system.md} §3.5).
     * <p>
     * A restored <b>finished</b> order gets {@code now} instead, because its deadline is the moment it ended and
     * {@link #prune} measures the retention from there: giving it a future deadline would keep a dead order visible
     * for a timeout plus a retention after every world load.
     */
    public void restartDeadlines(long now, long timeoutTicks) {
        long deadline = now + Math.max(1L, timeoutTicks);
        for (ProductionOrder<K, L> order : List.copyOf(orders.values()))
            store(order.withDeadline(order.isOpen() ? deadline : now));
    }

    /** All orders in creation order, finished ones included (unmodifiable copy). */
    public List<ProductionOrder<K, L>> all() {
        return List.copyOf(orders.values());
    }

    /** The open orders in creation order (unmodifiable copy). */
    public List<ProductionOrder<K, L>> open() {
        List<ProductionOrder<K, L>> result = new ArrayList<>();
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.isOpen())
                result.add(order);
        }
        return Collections.unmodifiableList(result);
    }

    /** The orders for the production station at {@code station}, in creation order (unmodifiable copy). */
    public List<ProductionOrder<K, L>> ordersFor(L station) {
        Objects.requireNonNull(station, "station");
        List<ProductionOrder<K, L>> result = new ArrayList<>();
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.station().equals(station))
                result.add(order);
        }
        return Collections.unmodifiableList(result);
    }

    /** Items of {@code key} the open orders still have to take out of storage. */
    public long outstandingIngredient(K key) {
        Objects.requireNonNull(key, "key");
        long total = 0L;
        for (ProductionOrder<K, L> order : orders.values())
            total += order.outstanding(key);
        return total;
    }

    /**
     * Items the open orders still have to take out of storage, <b>per key</b>, in one pass over the orders.
     * <p>
     * For callers that need {@link #outstandingIngredient} for many keys at once (the controller's producible
     * computation walks every ingredient of every pattern), which would otherwise scan every order and every line
     * again for each key.
     *
     * @return an unmodifiable map; a key no open order owes is absent, never mapped to 0
     */
    public Map<K, Long> outstandingIngredientsByKey() {
        Map<K, Long> byKey = new HashMap<>();
        for (ProductionOrder<K, L> order : orders.values()) {
            if (!order.isOpen())
                continue;
            for (SupplyLine<K> line : order.lines()) {
                if (line.remaining() > 0)
                    byKey.merge(line.key(), (long) line.remaining(), Long::sum);
            }
        }
        return Collections.unmodifiableMap(byKey);
    }

    /** Result items of {@code key} the open orders still expect to be produced. */
    public long outstandingResult(K key) {
        Objects.requireNonNull(key, "key");
        long total = 0L;
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.result().equals(key))
                total += order.outstandingResult();
        }
        return total;
    }

    public int openCount() {
        return open().size();
    }

    /**
     * Open orders the warehouse started by itself to refill a stock rule (M15 part 2,
     * {@link ProductionOrder#isRestock()}) — what {@code maxRestockOrders} bounds.
     */
    public int openRestockCount() {
        int count = 0;
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.isOpen() && order.isRestock())
                count++;
        }
        return count;
    }

    /**
     * Open automatic orders for {@code key} — what {@code maxRestockOrdersPerRule} bounds, and what stops one rule
     * from ordering the same thing twice while the first run is still in a machine.
     */
    public int openRestockCountFor(K key) {
        Objects.requireNonNull(key, "key");
        int count = 0;
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.isOpen() && order.isRestock() && order.result().equals(key))
                count++;
        }
        return count;
    }

    public int size() {
        return orders.size();
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    /** Whether {@link #add} would refuse a new open order because too many are open. */
    public boolean isFull() {
        return openCount() >= maxOpenOrders;
    }

    @Override
    public String toString() {
        return "ProductionOrders[open=" + openCount() + "/" + maxOpenOrders + ", total=" + orders.size() + "]";
    }

    /** Applies {@code change} to {@code order} and stores the result if it differs. */
    private Optional<ProductionOrder<K, L>> replace(Optional<ProductionOrder<K, L>> order,
            java.util.function.UnaryOperator<ProductionOrder<K, L>> change) {
        if (order.isEmpty())
            return Optional.empty();
        ProductionOrder<K, L> next = change.apply(order.get());
        store(next);
        return Optional.of(next);
    }

    /**
     * Stores {@code order}, adding it to the indices or following the one thing that can change about an order's place
     * in a plan: losing its parent line ({@link ProductionOrder#withoutParentLine()}). Ingredient lines never change
     * identity, so a replacement never touches the line index.
     */
    private void store(ProductionOrder<K, L> order) {
        ProductionOrder<K, L> previous = orders.put(order.id(), order);
        if (previous == null) {
            for (SupplyLine<K> line : order.lines()) {
                UUID owner = lineOwners.putIfAbsent(line.id(), order.id());
                sharedLineIds |= owner != null && !owner.equals(order.id());
            }
            order.parentLine().ifPresent(parent -> childrenOfLine(parent).add(order.id()));
            return;
        }
        if (previous.parentLine().equals(order.parentLine()))
            return;
        previous.parentLine().ifPresent(parent -> forgetChild(parent, order.id()));
        order.parentLine().ifPresent(parent -> childrenOfLine(parent).add(order.id()));
    }

    /** Removes {@code id} and everything the indices know about it. */
    private void forget(UUID id) {
        ProductionOrder<K, L> removed = orders.remove(id);
        if (removed == null)
            return;
        for (SupplyLine<K> line : removed.lines()) {
            if (lineOwners.remove(line.id(), id) && sharedLineIds)
                rebindLineOwner(line.id());
        }
        removed.parentLine().ifPresent(parent -> forgetChild(parent, id));
    }

    /**
     * Hands the line {@code lineId} to whichever order still holds it, after its owner was removed. Only reachable when
     * two orders ever claimed one line id, which only crafted save data does ({@link #sharedLineIds}).
     */
    private void rebindLineOwner(UUID lineId) {
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.line(lineId).isPresent()) {
                lineOwners.put(lineId, order.id());
                return;
            }
        }
    }

    private List<UUID> childrenOfLine(UUID parentLine) {
        return childrenByParentLine.computeIfAbsent(parentLine, key -> new ArrayList<>(2));
    }

    private void forgetChild(UUID parentLine, UUID childId) {
        List<UUID> children = childrenByParentLine.get(parentLine);
        if (children == null)
            return;
        children.remove(childId);
        if (children.isEmpty())
            childrenByParentLine.remove(parentLine);
    }

    /** The ids of the orders making an ingredient for {@code orderId} (a set, so one order is named once). */
    private Set<UUID> childIdsOf(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        ProductionOrder<K, L> order = orders.get(orderId);
        if (order == null || childrenByParentLine.isEmpty())
            return Set.of();
        Set<UUID> ids = new HashSet<>();
        for (SupplyLine<K> line : order.lines()) {
            for (UUID childId : childrenByParentLine.getOrDefault(line.id(), List.of())) {
                if (!childId.equals(orderId) && orders.containsKey(childId))
                    ids.add(childId);
            }
        }
        return ids;
    }

    /** The id of the root of {@code order}'s plan — the order itself when it is a root, an orphan or in a cycle. */
    private UUID rootIdOf(ProductionOrder<K, L> order) {
        return rootOf(order.id()).map(ProductionOrder::id).orElse(order.id());
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 && a > 0 && b > 0 ? Long.MAX_VALUE : sum;
    }
}
