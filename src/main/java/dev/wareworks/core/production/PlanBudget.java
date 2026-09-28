package dev.wareworks.core.production;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * The one availability snapshot a production plan is paid from, and the ledger of what it has already spent
 * ({@code docs/warehouse-system.md} §3.5.6, ADR-032).
 * <p>
 * <b>Why a budget and not a function.</b> A plan may need the same item in two branches — a chest wants planks, and the
 * sticks it also wants want planks too. Asked twice, an availability function answers the same number twice, and the
 * plan would promise the same logs to two machines. The budget answers {@code snapshot − spent}, so the second branch
 * sees exactly what the first one left it and either pays from what is left or gets a step of its own.
 * <p>
 * <b>What "available" means is not this class's decision.</b> It wraps whatever the caller passes — for a player at a
 * terminal {@code StockAvailability.of(rules, PLAYER, availabilityLookup())}, for a warehouse port the same with
 * {@code AUTOMATION} — so a plan spends exactly what a request of the same taker may spend, reserves and all. Negative
 * answers count as 0, as everywhere else in the production path.
 * <p>
 * <b>Really a snapshot.</b> Every key is asked of the wrapped function <b>once</b> and remembered, so a walk that
 * revisits a key cannot see two different worlds, and the several attempts one plan may take
 * ({@link ProductionPlanner} shrinks a plan that does not fit) all measure against the same numbers. {@link #fresh()}
 * is how an attempt starts over: a new ledger over the <b>same</b> remembered snapshot.
 * <p>
 * <b>Nothing here spends anything in the world.</b> Taking from the budget is bookkeeping inside one planning pass; the
 * real claim on an item is the production order's own supply line, created when the plan is accepted (ADR-032).
 * Mutable by design and single-threaded, like everything on a server tick; pure Java with no Minecraft types.
 *
 * @param <K> item key type
 */
public final class PlanBudget<K> {
    private final ToLongFunction<? super K> availability;
    /** The remembered answers of {@link #availability}, shared with every {@link #fresh()} ledger over it. */
    private final Map<K, Long> snapshot;
    private final Map<K, Long> spent = new LinkedHashMap<>();

    private PlanBudget(ToLongFunction<? super K> availability, Map<K, Long> snapshot) {
        this.availability = availability;
        this.snapshot = snapshot;
    }

    /**
     * A budget over {@code availability}, with nothing spent yet.
     *
     * @param availability how many items of a key a new promise may still claim (the controller's
     *                     {@code availableStock} behind the taker's own reserve); negative answers count as 0 and every
     *                     key is asked at most once
     */
    public static <K> PlanBudget<K> of(ToLongFunction<? super K> availability) {
        return new PlanBudget<>(Objects.requireNonNull(availability, "availability"), new LinkedHashMap<>());
    }

    /** A budget of fixed numbers, for tests and for a caller that has the availability as a map already. */
    public static <K> PlanBudget<K> ofMap(Map<K, Long> available) {
        Objects.requireNonNull(available, "available");
        Map<K, Long> copy = new LinkedHashMap<>(available);
        return of(key -> copy.getOrDefault(key, 0L));
    }

    /** What the warehouse had of {@code key} when this plan started, whatever the plan has spent since. */
    public long snapshot(K key) {
        Objects.requireNonNull(key, "key");
        return snapshot.computeIfAbsent(key, k -> Math.max(0L, availability.applyAsLong(k)));
    }

    /** What is left of {@code key} for the rest of the plan: {@link #snapshot} minus what it has taken. */
    public long available(K key) {
        return Math.max(0L, snapshot(key) - spent(key));
    }

    /** Items of {@code key} this plan has assigned to a machine out of real stock. */
    public long spent(K key) {
        Objects.requireNonNull(key, "key");
        return spent.getOrDefault(key, 0L);
    }

    /**
     * Takes up to {@code amount} items of {@code key} out of the budget and returns how many it really got — never
     * more than {@link #available}, so a plan can never promise items it has not got. An amount of 0 or less takes
     * nothing.
     */
    public long take(K key, long amount) {
        if (amount <= 0L)
            return 0L;
        long taken = Math.min(available(key), amount);
        if (taken > 0L)
            spent.merge(key, taken, Long::sum);
        return taken;
    }

    /** Items over all keys this plan has assigned out of real stock. */
    public long spentItems() {
        long total = 0L;
        for (long items : spent.values())
            total += items;
        return total;
    }

    /**
     * What this plan takes out of the racks, per key, in the order the keys were first spent — the plan's
     * {@code leafDemand} ({@link ProductionPlan#leafDemand()}). Unmodifiable.
     */
    public Map<K, Long> spentByKey() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(spent));
    }

    /** Whether nothing has been taken yet. */
    public boolean nothingSpent() {
        return spent.isEmpty();
    }

    /**
     * Puts the ledger back to an earlier {@link #spentByKey()} — how a caller <b>undoes</b> a branch it tried and gave
     * up on ({@link ProductionPlanner} falls back to another pattern for an item whose subtree could not be made).
     * <p>
     * The snapshot is untouched, so this is bookkeeping and nothing else: it restores what the plan <i>means</i> to take
     * out of the racks, which is what the abandoned branch had added to.
     *
     * @param mark a map taken from {@link #spentByKey()} before the branch started
     */
    public void resetTo(Map<K, Long> mark) {
        Objects.requireNonNull(mark, "mark");
        spent.clear();
        spent.putAll(mark);
    }

    /**
     * A budget over the <b>same</b> snapshot with nothing spent — how one planning attempt starts over after another
     * did not fit. The remembered availability is shared, so starting over costs no further lookups and every attempt
     * measures against the same world.
     */
    public PlanBudget<K> fresh() {
        return new PlanBudget<>(availability, snapshot);
    }
}
