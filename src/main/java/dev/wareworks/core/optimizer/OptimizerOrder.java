package dev.wareworks.core.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One press of a Warehouse Optimizer's Optimise (issue #46, ADR-079): the items it asked to move up to a better storage
 * location, how many trips it may still cost and what it has moved so far. Immutable; {@link OptimizerOrders} replaces
 * an order with the changed copy.
 * <p>
 * An order means "what fits <b>now</b>": an entry ends as soon as nothing more of its item fits anywhere better, and an
 * order ends when every entry has ended, when its trips run out or when the player cancels it. Nothing retries in the
 * background, and pressing again starts a fresh order. A trip already under way when an order ends is finished all the
 * same, and still counted, because a crane never gives a job back.
 *
 * @param optimizer the optimizer's position, the order's identity within its warehouse
 * @param state     whether the order still plans trips
 * @param entries   the items, in the optimizer's row order, at most {@value OptimizerLimits#MAX_ROWS}
 * @param tripsLeft how many more trips it may cost, {@code 0..}{@value OptimizerLimits#MAX_TRIPS}
 * @param moved     how many items its trips have really put down, over all entries
 * @param inFlight  the trips on their way, as job id to the entry they serve, at most
 *                  {@value OptimizerLimits#MAX_IN_FLIGHT}, oldest first
 * @param <K>       item key type
 * @param <P>       position type
 */
public record OptimizerOrder<K, P>(P optimizer, State state, List<Entry<K>> entries, int tripsLeft, long moved,
        Map<UUID, UUID> inFlight) {
    /** Where an order is. Saved by {@link #name()}. */
    public enum State {
        /** Some entry is still open and trips are left, so the warehouse plans relocations for it. */
        RUNNING,
        /** Every entry ended: nothing more of its items fits anywhere better. */
        FINISHED,
        /** It used up its trips while entries were still open. */
        OUT_OF_TRIPS,
        /** The player cancelled it. */
        CANCELLED;

        /** The state with this save name, or empty. */
        public static Optional<State> byName(String name) {
            for (State state : values()) {
                if (state.name().equals(name))
                    return Optional.of(state);
            }
            return Optional.empty();
        }
    }

    /** Where one item of an order is. Saved by {@link #name()}. */
    public enum EntryState {
        /** Still searched for trips while its order runs. */
        OPEN,
        /** Nothing more fits anywhere better, after at least one trip of it set off. */
        DONE,
        /** Nothing fitted anywhere better from the start: no trip of it ever set off. */
        NOTHING_TO_MOVE,
        /** Its order ended (out of trips, cancelled) while it was still open. */
        STOPPED;

        /** The state with this save name, or empty. */
        public static Optional<EntryState> byName(String name) {
            for (EntryState state : values()) {
                if (state.name().equals(name))
                    return Optional.of(state);
            }
            return Optional.empty();
        }
    }

    /**
     * One item of an order.
     *
     * @param id    stable identity, which the planner reports back ({@code PlanResult#relocationsExhausted})
     * @param key   the item
     * @param state where it is
     * @param moved how many of it this order's trips really put down
     * @param <K>   item key type
     */
    public record Entry<K>(UUID id, K key, EntryState state, long moved) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(state, "state");
            moved = Math.max(0L, moved);
        }

        /** Whether the entry is still searched for trips. */
        public boolean isOpen() {
            return state == EntryState.OPEN;
        }
    }

    public OptimizerOrder {
        Objects.requireNonNull(optimizer, "optimizer");
        Objects.requireNonNull(state, "state");
        entries = List.copyOf(entries.subList(0, Math.min(entries.size(), OptimizerLimits.MAX_ROWS)));
        tripsLeft = Math.max(0, Math.min(OptimizerLimits.MAX_TRIPS, tripsLeft));
        moved = Math.max(0L, moved);
        LinkedHashMap<UUID, UUID> trips = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> trip : inFlight.entrySet()) {
            if (trips.size() >= OptimizerLimits.MAX_IN_FLIGHT)
                break;
            trips.put(Objects.requireNonNull(trip.getKey(), "job id"), Objects.requireNonNull(trip.getValue(), "entry"));
        }
        inFlight = Collections.unmodifiableMap(trips);
    }

    /** Whether the warehouse still plans relocations for this order. */
    public boolean isRunning() {
        return state == State.RUNNING;
    }

    /** How many of its entries are still open. */
    public int openEntries() {
        int open = 0;
        for (Entry<K> entry : entries) {
            if (entry.isOpen())
                open++;
        }
        return open;
    }

    /** The entry for {@code key}, if the order lists it. */
    public Optional<Entry<K>> entryFor(K key) {
        for (Entry<K> entry : entries) {
            if (entry.key().equals(key))
                return Optional.of(entry);
        }
        return Optional.empty();
    }

    /** This order with {@code entries} in place of its own, ended when none of them is open any more. */
    OptimizerOrder<K, P> withEntries(List<Entry<K>> next) {
        boolean anyOpen = false;
        for (Entry<K> entry : next)
            anyOpen |= entry.isOpen();
        return new OptimizerOrder<>(optimizer, state == State.RUNNING && !anyOpen ? State.FINISHED : state, next,
                tripsLeft, moved, inFlight);
    }

    /** This order ended in {@code end}: every open entry is stopped. */
    OptimizerOrder<K, P> endedAs(State end) {
        List<Entry<K>> next = new ArrayList<>(entries.size());
        for (Entry<K> entry : entries)
            next.add(entry.isOpen() ? new Entry<>(entry.id(), entry.key(), EntryState.STOPPED, entry.moved()) : entry);
        return new OptimizerOrder<>(optimizer, end, next, tripsLeft, moved, inFlight);
    }

    /** This order with the trip {@code jobId} set off for {@code entryId}: one trip fewer, and one more on its way. */
    OptimizerOrder<K, P> withTrip(UUID jobId, UUID entryId) {
        LinkedHashMap<UUID, UUID> trips = new LinkedHashMap<>(inFlight);
        // The oldest is forgotten first should the bound ever bite: its crane never reported back.
        while (trips.size() >= OptimizerLimits.MAX_IN_FLIGHT)
            trips.remove(trips.keySet().iterator().next());
        trips.put(jobId, entryId);
        OptimizerOrder<K, P> next = new OptimizerOrder<>(optimizer, state, entries, tripsLeft - 1, moved, trips);
        return next.isRunning() && next.tripsLeft() == 0 ? next.endedAs(State.OUT_OF_TRIPS) : next;
    }

    /** This order with {@code amount} items of trip {@code jobId} put down. */
    OptimizerOrder<K, P> withDelivered(UUID jobId, long amount) {
        UUID entryId = inFlight.get(jobId);
        if (entryId == null || amount <= 0)
            return this;
        List<Entry<K>> next = new ArrayList<>(entries.size());
        for (Entry<K> entry : entries) {
            next.add(entry.id().equals(entryId)
                    ? new Entry<>(entry.id(), entry.key(), entry.state(), entry.moved() + amount) : entry);
        }
        return new OptimizerOrder<>(optimizer, state, next, tripsLeft, moved + amount, inFlight);
    }

    /** This order without the trip {@code jobId} on its way. */
    OptimizerOrder<K, P> withoutTrip(UUID jobId) {
        if (!inFlight.containsKey(jobId))
            return this;
        LinkedHashMap<UUID, UUID> trips = new LinkedHashMap<>(inFlight);
        trips.remove(jobId);
        return new OptimizerOrder<>(optimizer, state, entries, tripsLeft, moved, trips);
    }
}
