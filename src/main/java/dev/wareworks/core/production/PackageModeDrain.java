package dev.wareworks.core.production;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The drain rule of package mode (issue #40, owner decision E2, 2026-11-22): which <b>loose</b> items a warehouse in
 * package mode still lets in and stores, and how many.
 * <p>
 * Package mode is one server switch read at world load, so it can be on while production orders opened before the
 * switch are still running. Their machines give their results back loose, through an ordinary warehouse input; refusing
 * them would strand the player's line (belt, saw, arm) until the mode is switched off again. So a loose stack is let in
 * and stored while it is what such an order's machine can still give back, and never more:
 * <ul>
 * <li><b>results</b>: what the ingredients the crane really dropped at the machine pay for ({@link #makeable}, 0
 * before anything was delivered), minus what the order already counted ({@link ProductionOrder#produced()}) and minus
 * what this ledger already let through for it ({@link #consumed}). It answers the same for an open order and for one
 * that ended with ingredients at its machine (cancelled or timed out), so an order that is no longer credited still
 * stops letting items in once the machine's share came back (review fix, 2026-11-22);</li>
 * <li><b>empty containers</b>: one per filled container the crane dropped at the machine (a water bucket comes back as
 * a bucket), whatever the order's state, minus what was let through for it;</li>
 * <li>an order the controller forgets while it still allows something keeps its rest here ({@link #forget}) until it is
 * used up, so a slow line (a saw, Robo Bee legs) is not refused a late result once the order's retention ran out.</li>
 * </ul>
 * Every loose item that left an input in package mode is booked against these allowances ({@link #consume}), first to
 * the orders that make it, then to the container returns, then to the forgotten rests. The ledger is <b>not saved</b>:
 * after a restart an order the controller still keeps allows {@code makeable - produced} again, at most once per load,
 * and a forgotten rest is gone (its late results wait outside for a hand).
 * <p>
 * Pure, so the rule is unit tested without a game. Not thread-safe; the server thread owns it.
 *
 * @param <K> item key type
 */
public final class PackageModeDrain<K> {
    /** Per order: the result items booked against it, never below what it counted itself when booked. */
    private final Map<UUID, Long> consumed = new HashMap<>();
    /** Per order and empty container key: the containers booked against it. */
    private final Map<UUID, Map<K, Long>> containersConsumed = new HashMap<>();
    /** Orders the controller forgot while they still allowed something: their rest per key, in the order forgotten. */
    private final Map<UUID, Map<K, Long>> leftovers = new LinkedHashMap<>();

    /**
     * The result items the ingredients delivered to {@code order}'s machine pay for: for each ingredient (a choice paid
     * with several keys counts as one), {@code floor(resultAmount * delivered / required)}, and the least of them. 0
     * while nothing was delivered: a machine cannot have made anything before the crane dropped an ingredient at it.
     * A pattern's yield is linear in its runs, so this is exact for whole runs and never more than one partial run
     * could make.
     */
    public static <K, L> long makeable(ProductionOrder<K, L> order) {
        Objects.requireNonNull(order, "order");
        if (order.deliveredIngredients() <= 0)
            return 0L;
        Map<Object, long[]> ingredients = new HashMap<>();
        for (SupplyLine<K> line : order.lines()) {
            Object ingredient = line.choice().<Object>map(ChoiceGroup::new).orElse(line.key());
            long[] counts = ingredients.computeIfAbsent(ingredient, unused -> new long[2]);
            counts[0] += line.required();
            counts[1] += line.delivered();
        }
        long makeable = order.resultAmount();
        for (long[] counts : ingredients.values())
            makeable = Math.min(makeable, order.resultAmount() * counts[1] / Math.max(1L, counts[0]));
        return Math.max(0L, makeable);
    }

    /** Groups the lines that pay one choice, apart from an exact ingredient of the same key. */
    private record ChoiceGroup(Object choice) {
    }

    /**
     * The result items {@code order}'s machine may still give back: {@link #makeable} minus the larger of what the order
     * counted and what this ledger booked against it. 0 for a completed order (it counted all it was made for).
     */
    public <L> long owedResult(ProductionOrder<K, L> order) {
        Objects.requireNonNull(order, "order");
        long makeable = makeable(order);
        if (makeable <= 0L)
            return 0L;
        long used = Math.max(order.produced(), consumed.getOrDefault(order.id(), 0L));
        return Math.max(0L, makeable - used);
    }

    /**
     * The empty containers of {@code container} {@code order}'s machine may still give back: one per filled container
     * of an ingredient line whose empty form ({@code emptyOf}) is {@code container} that the crane dropped there, minus
     * what this ledger booked against it.
     */
    public <L> long owedContainers(ProductionOrder<K, L> order, K container, Function<K, Optional<K>> emptyOf) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(container, "container");
        long delivered = 0L;
        for (SupplyLine<K> line : order.lines()) {
            if (line.delivered() > 0 && emptyOf.apply(line.key()).filter(container::equals).isPresent())
                delivered += line.delivered();
        }
        if (delivered <= 0L)
            return 0L;
        long used = containersConsumed.getOrDefault(order.id(), Map.of()).getOrDefault(container, 0L);
        return Math.max(0L, delivered - used);
    }

    /** What {@code orders} and the forgotten rests still let in of {@code key}, over every rule above. */
    public <L> long remaining(Collection<ProductionOrder<K, L>> orders, K key, Function<K, Optional<K>> emptyOf) {
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(emptyOf, "emptyOf");
        long total = 0L;
        for (ProductionOrder<K, L> order : orders) {
            if (order.result().equals(key))
                total = saturatedAdd(total, owedResult(order));
            total = saturatedAdd(total, owedContainers(order, key, emptyOf));
        }
        for (Map<K, Long> rest : leftovers.values())
            total = saturatedAdd(total, rest.getOrDefault(key, 0L));
        return total;
    }

    /** Every key {@code orders} and the forgotten rests still let in, with its amount (only positive). */
    public <L> Map<K, Long> allowance(Collection<ProductionOrder<K, L>> orders, Function<K, Optional<K>> emptyOf) {
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(emptyOf, "emptyOf");
        Map<K, Long> allowance = new HashMap<>();
        for (ProductionOrder<K, L> order : orders) {
            long owed = owedResult(order);
            if (owed > 0L)
                allowance.merge(order.result(), owed, PackageModeDrain::saturatedAdd);
            for (K container : containersOf(order, emptyOf)) {
                long containers = owedContainers(order, container, emptyOf);
                if (containers > 0L)
                    allowance.merge(container, containers, PackageModeDrain::saturatedAdd);
            }
        }
        for (Map<K, Long> rest : leftovers.values()) {
            for (Map.Entry<K, Long> entry : rest.entrySet()) {
                if (entry.getValue() > 0L)
                    allowance.merge(entry.getKey(), entry.getValue(), PackageModeDrain::saturatedAdd);
            }
        }
        return allowance;
    }

    /** The distinct empty containers {@code order}'s delivered filled containers give back. */
    private static <K, L> Set<K> containersOf(ProductionOrder<K, L> order, Function<K, Optional<K>> emptyOf) {
        Set<K> containers = new java.util.LinkedHashSet<>();
        for (SupplyLine<K> line : order.lines()) {
            if (line.delivered() > 0)
                emptyOf.apply(line.key()).ifPresent(containers::add);
        }
        return containers;
    }

    /**
     * Books {@code amount} loose items of {@code key} that left an input in package mode (the crane stored them, or
     * an accepting port took them) against the allowances: first the orders that make {@code key}, in their order, then
     * their container returns, then the forgotten rests. Call it <b>before</b> the orders are credited with the same
     * arrival, so the booking starts from what they had counted until then.
     *
     * @return what could not be booked (items let in beyond every allowance), never negative
     */
    public <L> long consume(Collection<ProductionOrder<K, L>> orders, K key, long amount,
            Function<K, Optional<K>> emptyOf) {
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(emptyOf, "emptyOf");
        long left = Math.max(0L, amount);
        for (ProductionOrder<K, L> order : orders) {
            if (left <= 0L)
                return 0L;
            if (!order.result().equals(key))
                continue;
            long taken = Math.min(left, owedResult(order));
            if (taken <= 0L)
                continue;
            long base = Math.max(order.produced(), consumed.getOrDefault(order.id(), 0L));
            consumed.put(order.id(), base + taken);
            left -= taken;
        }
        for (ProductionOrder<K, L> order : orders) {
            if (left <= 0L)
                return 0L;
            long taken = Math.min(left, owedContainers(order, key, emptyOf));
            if (taken <= 0L)
                continue;
            containersConsumed.computeIfAbsent(order.id(), unused -> new HashMap<>()).merge(key, taken, Long::sum);
            left -= taken;
        }
        for (Iterator<Map<K, Long>> rests = leftovers.values().iterator(); rests.hasNext() && left > 0L;) {
            Map<K, Long> rest = rests.next();
            long owed = rest.getOrDefault(key, 0L);
            long taken = Math.min(left, owed);
            if (taken <= 0L)
                continue;
            if (owed - taken <= 0L)
                rest.remove(key);
            else
                rest.put(key, owed - taken);
            if (rest.isEmpty())
                rests.remove();
            left -= taken;
        }
        return left;
    }

    /**
     * The controller is about to forget {@code gone} (pruned after its retention): whatever they still let in stays
     * here as a rest until it is used up. {@code live} names every order the controller still keeps afterwards; the
     * bookings of every other order are dropped.
     */
    public <L> void forget(Collection<ProductionOrder<K, L>> gone, Set<UUID> live, Function<K, Optional<K>> emptyOf) {
        Objects.requireNonNull(gone, "gone");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(emptyOf, "emptyOf");
        for (ProductionOrder<K, L> order : gone) {
            Map<K, Long> rest = new HashMap<>();
            long owed = owedResult(order);
            if (owed > 0L)
                rest.merge(order.result(), owed, PackageModeDrain::saturatedAdd);
            for (K container : containersOf(order, emptyOf)) {
                long containers = owedContainers(order, container, emptyOf);
                if (containers > 0L)
                    rest.merge(container, containers, PackageModeDrain::saturatedAdd);
            }
            if (!rest.isEmpty())
                leftovers.put(order.id(), rest);
        }
        consumed.keySet().retainAll(live);
        containersConsumed.keySet().retainAll(live);
    }

    /** Whether nothing is booked and no rest is kept (the state of a fresh load). */
    public boolean isEmpty() {
        return consumed.isEmpty() && containersConsumed.isEmpty() && leftovers.isEmpty();
    }

    /** Drops every booking and rest (package mode is off, so nothing may be let in for them). */
    public void clear() {
        consumed.clear();
        containersConsumed.clear();
        leftovers.clear();
    }

    /**
     * How many more loose items of {@code key} one input may take in: the allowance minus what that input already
     * {@code waiting} holds of it, never below 0.
     */
    public static <K> long room(Map<K, Long> allowance, K key, long waiting) {
        Objects.requireNonNull(allowance, "allowance");
        long allowed = allowance.getOrDefault(key, 0L);
        return Math.max(0L, allowed - Math.max(0L, waiting));
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
