package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        orders.put(order.id(), order);
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
        orders.put(next.id(), next);
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
     * One arrival is credited <b>once</b>, to the open orders in creation order: the oldest takes what it still waits
     * for, the next only what is left.
     */
    public Observation<K, L> observeStored(K key, long amount, long now, long timeoutTicks) {
        Objects.requireNonNull(key, "key");
        if (amount <= 0L)
            return Observation.none();
        List<ProductionOrder<K, L>> changed = new ArrayList<>();
        long left = amount;
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            if (left <= 0L || !order.result().equals(key) || !order.countsArrivals())
                continue;
            ProductionOrder<K, L> next = order.withStored(left, now, timeoutTicks);
            if (next == order)
                continue;
            left -= next.produced() - order.produced();
            orders.put(next.id(), next);
            changed.add(next);
        }
        return new Observation<>(changed, amount - Math.max(0L, left));
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
            orders.put(next.id(), next);
            changed.add(next);
        }
        return List.copyOf(changed);
    }

    /**
     * Times out every open order whose deadline {@code now} has reached.
     *
     * @return the orders that timed out in this call
     */
    public List<ProductionOrder<K, L>> timeOut(long now) {
        List<ProductionOrder<K, L>> timedOut = new ArrayList<>();
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            ProductionOrder<K, L> next = order.timedOutAt(now);
            if (next == order)
                continue;
            orders.put(next.id(), next);
            timedOut.add(next);
        }
        return List.copyOf(timedOut);
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
                orders.put(order.id(), order.withoutBackingRequest());
        }
    }

    /**
     * Removes finished orders that have been finished for at least {@code retentionTicks}, using their deadline as the
     * moment they ended (a finished order's deadline is no longer pushed out).
     *
     * @return the number of removed orders
     */
    public int prune(long now, long retentionTicks) {
        int removed = 0;
        for (ProductionOrder<K, L> order : List.copyOf(orders.values())) {
            if (order.isOpen() || now < order.deadlineTick() + Math.max(0L, retentionTicks))
                continue;
            orders.remove(order.id());
            removed++;
        }
        return removed;
    }

    /** Removes every order (the aisle is gone). */
    public void clear() {
        orders.clear();
    }

    /**
     * Replaces the orders with persisted ones, in the given order. Entries with a repeated id are skipped and the cap
     * is not applied, so a lowered {@code maxProductionOrders} never deletes a saved order.
     *
     * @return the number of restored orders
     */
    public int restore(Collection<ProductionOrder<K, L>> saved) {
        Objects.requireNonNull(saved, "saved");
        orders.clear();
        for (ProductionOrder<K, L> order : saved) {
            if (order != null && !orders.containsKey(order.id()))
                orders.put(order.id(), order);
        }
        return orders.size();
    }

    // --- queries -------------------------------------------------------------------------------------------------

    public Optional<ProductionOrder<K, L>> get(UUID id) {
        return Optional.ofNullable(orders.get(Objects.requireNonNull(id, "id")));
    }

    /** The order owning the ingredient line {@code lineId}. */
    public Optional<ProductionOrder<K, L>> byLine(UUID lineId) {
        Objects.requireNonNull(lineId, "lineId");
        for (ProductionOrder<K, L> order : orders.values()) {
            if (order.line(lineId).isPresent())
                return Optional.of(order);
        }
        return Optional.empty();
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
            orders.put(order.id(), order.withDeadline(order.isOpen() ? deadline : now));
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
        orders.put(next.id(), next);
        return Optional.of(next);
    }
}
