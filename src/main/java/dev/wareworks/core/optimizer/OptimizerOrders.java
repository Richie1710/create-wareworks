package dev.wareworks.core.optimizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Warehouse Optimizer orders of one warehouse (issue #46, ADR-079), oldest first, at most one per optimizer and
 * {@value OptimizerLimits#MAX_ORDERS} in all. The controller owns one and saves it; it plans nothing and moves nothing.
 * <p>
 * <b>What it answers.</b> {@link #openEntries()} is what the controller turns into the planner's relocation needs, one
 * per item, oldest order first. {@link #hasPending()} is the one boolean the dispatch's cheap pre-check asks, so a
 * warehouse without a running order pays nothing.
 * <p>
 * <b>What it is told.</b> A relocation handed to a crane spends one trip of the order whose entry it serves
 * ({@link #onAssigned}); what the crane puts down is counted when it reports the drop ({@link #onDelivered}); the trip
 * leaves the order's in-flight set when its crane reports it finished, given up or lost ({@link #onJobEnded}). An item
 * the planner found no better place for, or that has nothing left to move, ends in every running order that lists it
 * ({@link #endKey}): "nothing more fits" is a fact about the warehouse, not about one optimizer.
 * <p>
 * Finished orders are kept, so an optimizer's screen can still say what its last order did, until the same optimizer
 * starts another, the optimizer is removed, or a new order needs the place of the oldest finished one. Pure Java, not
 * thread-safe (server thread only).
 *
 * @param <K> item key type
 * @param <P> position type
 */
public final class OptimizerOrders<K, P> {
    /** What {@link #start} did. */
    public enum Start {
        /** A fresh order runs. */
        STARTED,
        /** The list named no item at all. */
        NO_ITEMS,
        /** Every one of the {@value OptimizerLimits#MAX_ORDERS} orders this warehouse keeps is running. */
        TOO_MANY_ORDERS
    }

    /**
     * An entry the planner should search: the entry the trip is credited to, and its item.
     *
     * @param id  the entry
     * @param key the item
     * @param <K> item key type
     */
    public record OpenEntry<K>(UUID id, K key) {
        public OpenEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(key, "key");
        }
    }

    private final Map<P, OptimizerOrder<K, P>> orders = new LinkedHashMap<>();
    private final Supplier<UUID> idFactory;

    /** Orders whose entries get random ids. */
    public OptimizerOrders() {
        this(UUID::randomUUID);
    }

    /** @param idFactory creates entry ids (deterministic in tests) */
    public OptimizerOrders(Supplier<UUID> idFactory) {
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory");
    }

    /**
     * Starts a fresh order at {@code optimizer} for {@code keys}, in their order, each item once and at most
     * {@value OptimizerLimits#MAX_ROWS} of them, with {@code trips} trips to spend (clamped,
     * {@link OptimizerLimits#clampTrips}). It replaces whatever order that optimizer had, running or finished: a trip of
     * the replaced order that is under way is finished all the same but no longer counted. When the warehouse already
     * keeps {@value OptimizerLimits#MAX_ORDERS} orders the oldest finished one makes room; when every one of them is
     * running, nothing changes.
     */
    public Start start(P optimizer, List<K> keys, int trips) {
        Objects.requireNonNull(optimizer, "optimizer");
        Objects.requireNonNull(keys, "keys");
        Set<K> distinct = new LinkedHashSet<>();
        for (K key : keys) {
            if (key != null && distinct.size() < OptimizerLimits.MAX_ROWS)
                distinct.add(key);
        }
        if (distinct.isEmpty())
            return Start.NO_ITEMS;
        int othersRunning = 0;
        for (OptimizerOrder<K, P> order : orders.values()) {
            if (order.isRunning() && !order.optimizer().equals(optimizer))
                othersRunning++;
        }
        if (othersRunning >= OptimizerLimits.MAX_ORDERS)
            return Start.TOO_MANY_ORDERS;
        orders.remove(optimizer);
        while (orders.size() >= OptimizerLimits.MAX_ORDERS) {
            P oldestFinished = null;
            for (OptimizerOrder<K, P> order : orders.values()) {
                if (!order.isRunning()) {
                    oldestFinished = order.optimizer();
                    break;
                }
            }
            orders.remove(oldestFinished);
        }
        List<OptimizerOrder.Entry<K>> entries = new ArrayList<>(distinct.size());
        for (K key : distinct) {
            entries.add(new OptimizerOrder.Entry<>(Objects.requireNonNull(idFactory.get(), "id factory returned null"),
                    key, OptimizerOrder.EntryState.OPEN, 0L));
        }
        orders.put(optimizer, new OptimizerOrder<>(optimizer, OptimizerOrder.State.RUNNING, entries,
                OptimizerLimits.clampTrips(trips), 0L, Map.of()));
        return Start.STARTED;
    }

    /**
     * Cancels the running order at {@code optimizer}: no further trip is planned for it, its open entries stop, and a
     * trip under way is finished and still counted.
     *
     * @return whether a running order was cancelled
     */
    public boolean cancel(P optimizer) {
        OptimizerOrder<K, P> order = orders.get(Objects.requireNonNull(optimizer, "optimizer"));
        if (order == null || !order.isRunning())
            return false;
        orders.put(optimizer, order.endedAs(OptimizerOrder.State.CANCELLED));
        return true;
    }

    /**
     * Forgets the order at {@code optimizer}, running or finished: the optimizer is gone. A trip under way is finished
     * all the same.
     *
     * @return whether there was one
     */
    public boolean remove(P optimizer) {
        return orders.remove(Objects.requireNonNull(optimizer, "optimizer")) != null;
    }

    /** The order at {@code optimizer}, running or finished. */
    public Optional<OptimizerOrder<K, P>> get(P optimizer) {
        return Optional.ofNullable(orders.get(Objects.requireNonNull(optimizer, "optimizer")));
    }

    /** Every order, oldest first (unmodifiable copy). */
    public List<OptimizerOrder<K, P>> all() {
        return List.copyOf(orders.values());
    }

    /** Whether some order still runs: the planner has relocations to search. */
    public boolean hasPending() {
        for (OptimizerOrder<K, P> order : orders.values()) {
            if (order.isRunning())
                return true;
        }
        return false;
    }

    /**
     * The open entries of the running orders, oldest order first and each order's rows in order, <b>one per item</b>:
     * the first entry that lists it. A second optimizer listing the same item adds nothing to search, because what may
     * move and where it fits are facts about the warehouse.
     */
    public List<OpenEntry<K>> openEntries() {
        List<OpenEntry<K>> open = new ArrayList<>();
        Set<K> seen = new HashSet<>();
        for (OptimizerOrder<K, P> order : orders.values()) {
            if (!order.isRunning())
                continue;
            for (OptimizerOrder.Entry<K> entry : order.entries()) {
                if (entry.isOpen() && seen.add(entry.key()))
                    open.add(new OpenEntry<>(entry.id(), entry.key()));
            }
        }
        return open;
    }

    /**
     * Ends {@code key} in every running order that lists it: nothing more of it fits anywhere better, or nothing of it
     * may move. An entry with a trip that set off is {@link OptimizerOrder.EntryState#DONE}, one without is
     * {@link OptimizerOrder.EntryState#NOTHING_TO_MOVE}; an order left without an open entry is finished.
     *
     * @return whether an entry ended
     */
    public boolean endKey(K key) {
        Objects.requireNonNull(key, "key");
        boolean changed = false;
        for (Map.Entry<P, OptimizerOrder<K, P>> slot : orders.entrySet()) {
            OptimizerOrder<K, P> order = slot.getValue();
            if (!order.isRunning())
                continue;
            List<OptimizerOrder.Entry<K>> next = new ArrayList<>(order.entries().size());
            boolean ended = false;
            for (OptimizerOrder.Entry<K> entry : order.entries()) {
                if (entry.isOpen() && entry.key().equals(key)) {
                    boolean tripped = entry.moved() > 0 || order.inFlight().containsValue(entry.id());
                    next.add(new OptimizerOrder.Entry<>(entry.id(), entry.key(), tripped
                            ? OptimizerOrder.EntryState.DONE : OptimizerOrder.EntryState.NOTHING_TO_MOVE,
                            entry.moved()));
                    ended = true;
                } else {
                    next.add(entry);
                }
            }
            if (ended) {
                slot.setValue(order.withEntries(next));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Ends the items of the entries the planner reported exhausted ({@code PlanResult#relocationsExhausted}), each in
     * every running order that lists it ({@link #endKey}).
     *
     * @return whether an entry ended
     */
    public boolean endExhausted(Collection<UUID> entryIds) {
        Objects.requireNonNull(entryIds, "entryIds");
        if (entryIds.isEmpty())
            return false;
        Set<K> keys = new LinkedHashSet<>();
        for (OptimizerOrder<K, P> order : orders.values()) {
            for (OptimizerOrder.Entry<K> entry : order.entries()) {
                if (entryIds.contains(entry.id()))
                    keys.add(entry.key());
            }
        }
        boolean changed = false;
        for (K key : keys)
            changed |= endKey(key);
        return changed;
    }

    /**
     * A relocation of {@code key} was handed to a crane as job {@code jobId}: the oldest running order with an open entry
     * for the item spends one trip on it and remembers the trip, and runs out when that was its last.
     *
     * @return whether an order took the trip
     */
    public boolean onAssigned(UUID jobId, K key) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(key, "key");
        for (Map.Entry<P, OptimizerOrder<K, P>> slot : orders.entrySet()) {
            OptimizerOrder<K, P> order = slot.getValue();
            if (!order.isRunning())
                continue;
            Optional<OptimizerOrder.Entry<K>> entry = order.entryFor(key).filter(OptimizerOrder.Entry::isOpen);
            if (entry.isPresent()) {
                slot.setValue(order.withTrip(jobId, entry.get().id()));
                return true;
            }
        }
        return false;
    }

    /**
     * Trip {@code jobId} put {@code amount} items down: counted for the order and the entry it serves, whatever state
     * the order is in by now.
     *
     * @return whether an order counted it
     */
    public boolean onDelivered(UUID jobId, long amount) {
        Objects.requireNonNull(jobId, "jobId");
        if (amount <= 0)
            return false;
        for (Map.Entry<P, OptimizerOrder<K, P>> slot : orders.entrySet()) {
            if (slot.getValue().inFlight().containsKey(jobId)) {
                slot.setValue(slot.getValue().withDelivered(jobId, amount));
                return true;
            }
        }
        return false;
    }

    /**
     * Trip {@code jobId} is over: finished, given up or lost.
     *
     * @return whether an order remembered it
     */
    public boolean onJobEnded(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        for (Map.Entry<P, OptimizerOrder<K, P>> slot : orders.entrySet()) {
            if (slot.getValue().inFlight().containsKey(jobId)) {
                slot.setValue(slot.getValue().withoutTrip(jobId));
                return true;
            }
        }
        return false;
    }

    /**
     * Forgets every remembered trip that is not among {@code liveJobs}, the jobs the warehouse's cranes really hold: a
     * crane that was broken or cleared while its report could not reach the controller.
     *
     * @return whether a trip was forgotten
     */
    public boolean retainTrips(Set<UUID> liveJobs) {
        Objects.requireNonNull(liveJobs, "liveJobs");
        boolean changed = false;
        for (Map.Entry<P, OptimizerOrder<K, P>> slot : orders.entrySet()) {
            OptimizerOrder<K, P> order = slot.getValue();
            for (UUID jobId : List.copyOf(order.inFlight().keySet())) {
                if (!liveJobs.contains(jobId)) {
                    order = order.withoutTrip(jobId);
                    changed = true;
                }
            }
            slot.setValue(order);
        }
        return changed;
    }

    /** Whether some order still remembers a trip on its way. */
    public boolean hasTripsInFlight() {
        for (OptimizerOrder<K, P> order : orders.values()) {
            if (!order.inFlight().isEmpty())
                return true;
        }
        return false;
    }

    /**
     * Replaces every order with saved ones, oldest first. Repeated optimizers keep the first; beyond
     * {@value OptimizerLimits#MAX_ORDERS} nothing is read; an order saved as running with nothing left to run is ended
     * the way it would have ended.
     */
    public void restore(List<OptimizerOrder<K, P>> saved) {
        Objects.requireNonNull(saved, "saved");
        orders.clear();
        for (OptimizerOrder<K, P> order : saved) {
            if (order == null || orders.containsKey(order.optimizer()))
                continue;
            if (orders.size() >= OptimizerLimits.MAX_ORDERS)
                break;
            OptimizerOrder<K, P> restored = order;
            if (restored.isRunning() && restored.openEntries() == 0)
                restored = restored.withEntries(restored.entries());
            if (restored.isRunning() && restored.tripsLeft() == 0)
                restored = restored.endedAs(OptimizerOrder.State.OUT_OF_TRIPS);
            orders.put(restored.optimizer(), restored);
        }
    }

    /** Forgets every order: the warehouse is gone. */
    public void clear() {
        orders.clear();
    }

    /** Whether there is no order at all. */
    public boolean isEmpty() {
        return orders.isEmpty();
    }
}
