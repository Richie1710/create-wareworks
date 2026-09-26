package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

/**
 * One production order ({@code docs/warehouse-system.md} §3.5, ADR-024): bring the ingredients of a pattern to a
 * production station, wait for the player's machine to turn them into the result, and count the result as it arrives
 * back in the warehouse through a normal warehouse input.
 * <p>
 * <b>Wareworks never crafts and never invents items.</b> This record only tracks what was promised, what was delivered
 * and what came back; every item movement is a real crane job, and the result is only ever <i>observed</i> in the stock
 * index ({@link #withResultStock}).
 * <p>
 * <b>The state machine</b> is pure and complete ({@link ProductionOrderState}), which is why it is unit tested without a
 * game:
 * <ul>
 * <li>{@link #withDelivered} counts a crane drop at the station; once every line is complete the order is
 * {@link ProductionOrderState#DELIVERED};</li>
 * <li>{@link #withIngredientsTaken} moves a delivered order to {@link ProductionOrderState#WAITING_FOR_RESULT} when the
 * station's buffer no longer holds the ingredients, i.e. the machine really took them;</li>
 * <li>{@link #withResultStock} accumulates the <b>increases</b> of the result's stock (never the level itself, which
 * also falls when the crane serves a request) and completes the order once {@link #resultAmount()} of them were
 * seen. An <b>automatic</b> order is deliberately not counted this way ({@link #countsStockLevels()}): see
 * {@link #withStored};</li>
 * <li>{@link #withStored} counts result items the warehouse really <b>stored out of one of its own warehouse
 * inputs</b> — the route the product of a pattern takes back into the racks. This is the only channel an automatic
 * order is completed by, because its completion decides whether a rule's safety stop fires;</li>
 * <li>{@link #timedOutAt} ends an order that made no progress for the configured timeout, {@link #cancelled} ends one a
 * player gave up on. Both release what is still promised and neither takes anything back: ingredients a machine has
 * already swallowed are gone ({@link ProductionOrderState#handedIngredientsOver()}).</li>
 * </ul>
 * Every progress step pushes the deadline out, so a slow machine is not killed mid-run while a stuck order still ends.
 *
 * @param id             stable identity, also across restarts
 * @param station        the production station the ingredients go to
 * @param result         the item the machine is expected to make
 * @param resultAmount   how many of it this order waits for
 * @param lines          one {@link SupplyLine} per ingredient, each with its own id
 * @param state          where the order stands
 * @param deadlineTick   game time at which an order without progress times out
 * @param produced       result items observed since the order started
 * @param resultStockSeen the result's stock level at the last observation, the baseline of {@link #produced}
 * @param backingRequest the retrieval request this order was created for, so a lost order can give that request its
 *                       unproducible amount back; empty for an order nobody is waiting on
 * @param promisedToRequest result items {@link #backingRequest()} waits for from this order, {@code 0..resultAmount}
 *                       (0 without a backing request). A pattern makes <b>whole runs</b>, so an order regularly yields
 *                       more than was asked for; that surplus simply lands in stock and was promised to nobody.
 *                       Keeping the promise apart from {@link #resultAmount()} is what stops a failed order from
 *                       taking items off its request that production was never going to deliver for it
 *                       ({@link #unfulfilledPromise()}, {@code docs/warehouse-system.md} §3.5.3)
 * @param restock        whether the <b>warehouse itself</b> started this order to refill a stock rule's minimum
 *                       (M15 part 2, issue #3) rather than a player or a redstone request asking for the result. It
 *                       is an explicit flag and not "has no backing request", because an ordinary order loses its
 *                       request when that request is served or cancelled ({@link #withoutBackingRequest()}) and would
 *                       otherwise turn into an automatic one — which would let a player's own cancellation trip the
 *                       safety stop of a rule that never ordered anything
 * @param <K>            item key type
 * @param <L>            location type
 */
public record ProductionOrder<K, L>(UUID id, L station, K result, int resultAmount, List<SupplyLine<K>> lines,
                                    ProductionOrderState state, long deadlineTick, long produced,
                                    long resultStockSeen, Optional<UUID> backingRequest, long promisedToRequest,
                                    boolean restock) {
    public ProductionOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(backingRequest, "backingRequest");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty())
            throw new IllegalArgumentException("a production order needs at least one ingredient line");
        if (resultAmount < 1)
            throw new IllegalArgumentException("resultAmount must be at least 1: " + resultAmount);
        produced = Math.max(0L, produced);
        resultStockSeen = Math.max(0L, resultStockSeen);
        // Nobody to give anything back to means nothing is promised; and an order can never owe a request more than
        // the whole run yields.
        promisedToRequest = backingRequest.isEmpty() ? 0L
                : Math.max(0L, Math.min(promisedToRequest, resultAmount));
        // An automatic order is one nobody asked for: the two can never both be true, so save data cannot describe an
        // order that would both refund a request and trip a rule's safety stop.
        if (restock && backingRequest.isPresent())
            restock = false;
    }

    /**
     * A new order for {@code runs} runs of {@code pattern} at {@code station}.
     *
     * @param lineIds        creates the id of every ingredient line (the id a {@code SUPPLY} job then carries)
     * @param now            current game time
     * @param timeoutTicks   ticks without progress after which the order times out
     * @param resultStockNow the result's stock right now, the baseline the produced amount is counted from
     * @param backingRequest the retrieval request waiting for this result, or {@code null}
     * @throws IllegalArgumentException if {@code runs < 1}
     */
    public static <K, L> ProductionOrder<K, L> start(UUID id, L station, ProductionPattern<K> pattern, int runs,
            Supplier<UUID> lineIds, long now, long timeoutTicks, long resultStockNow, @Nullable UUID backingRequest) {
        return start(id, station, pattern, runs, lineIds, now, timeoutTicks, resultStockNow, backingRequest,
                pattern == null ? 0L : pattern.resultFor(Math.max(1, runs)));
    }

    /**
     * A new order that promises {@code promisedToRequest} of its result to {@code backingRequest}; see
     * {@link #start(UUID, Object, ProductionPattern, int, Supplier, long, long, long, UUID)} for the rest.
     *
     * @param promisedToRequest result items the backing request is waiting for from this order. It is normally
     *                          <b>less</b> than the whole run yields, because a pattern makes whole runs and the
     *                          surplus was asked for by nobody ({@link #promisedToRequest()})
     */
    public static <K, L> ProductionOrder<K, L> start(UUID id, L station, ProductionPattern<K> pattern, int runs,
            Supplier<UUID> lineIds, long now, long timeoutTicks, long resultStockNow, @Nullable UUID backingRequest,
            long promisedToRequest) {
        return start(id, station, pattern, runs, lineIds, now, timeoutTicks, resultStockNow, backingRequest,
                promisedToRequest, false);
    }

    /**
     * An <b>automatic restock order</b> (M15 part 2, issue #3): the warehouse itself refilling a stock rule's minimum,
     * so there is no request behind it and nothing to give back if it fails. Everything else about it — the supply
     * lines, the crane jobs, the timeout — is an ordinary production order.
     * <p>
     * The one difference is <b>how its result is counted</b>: an automatic order is completed only by items the
     * warehouse really stored out of one of its inputs ({@link #withStored}) and never by a rise of the result's stock
     * level, because its completion is what decides whether the safety stop fires ({@link #countsStockLevels()}). It
     * therefore keeps no stock baseline at all.
     */
    public static <K, L> ProductionOrder<K, L> restock(UUID id, L station, ProductionPattern<K> pattern, int runs,
            Supplier<UUID> lineIds, long now, long timeoutTicks) {
        return start(id, station, pattern, runs, lineIds, now, timeoutTicks, 0L, null, 0L, true);
    }

    private static <K, L> ProductionOrder<K, L> start(UUID id, L station, ProductionPattern<K> pattern, int runs,
            Supplier<UUID> lineIds, long now, long timeoutTicks, long resultStockNow, @Nullable UUID backingRequest,
            long promisedToRequest, boolean restock) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(lineIds, "lineIds");
        if (runs < 1)
            throw new IllegalArgumentException("runs must be at least 1: " + runs);
        List<SupplyLine<K>> lines = new ArrayList<>(pattern.ingredients().size());
        for (ProductionEntry<K> ingredient : pattern.ingredients())
            lines.add(SupplyLine.of(Objects.requireNonNull(lineIds.get(), "line id"), ingredient.key(),
                    ingredient.totalFor(runs)));
        return new ProductionOrder<>(id, station, pattern.result().key(), pattern.resultFor(runs), lines,
                ProductionOrderState.WAITING_FOR_INGREDIENTS, deadline(now, timeoutTicks), 0L,
                Math.max(0L, resultStockNow), Optional.ofNullable(backingRequest), promisedToRequest, restock);
    }

    /** Whether the order still promises ingredients and can still finish. */
    public boolean isOpen() {
        return state.isOpen();
    }

    /**
     * Whether the warehouse started this order by itself to refill a stock rule ({@link #restock()}). It is the one
     * kind of order that can trip a rule's safety stop, and the one nobody is waiting for.
     */
    public boolean isRestock() {
        return restock;
    }

    /**
     * Whether a rise of the result's <b>stock level</b> may complete this order ({@link #withResultStock}).
     * <p>
     * It may for an order a player or a redstone request asked for: somebody is waiting, and an item that turns up
     * from anywhere satisfies them just as well (§3.5.3). It may <b>not</b> for an automatic order (M15 part 2), and
     * that is the whole of the safety stop: completion is what decides whether a rule keeps ordering, and a level
     * rise says nothing about <i>where</i> the items came from. An unrelated farm, a barrel tipped into a rack, or a
     * player taking the product out and putting it back would otherwise complete an order whose ingredients a machine
     * had swallowed — and the rule would go on feeding that machine for ever. An automatic order is therefore counted
     * only by {@link #withStored}.
     */
    public boolean countsStockLevels() {
        return !restock;
    }

    /**
     * Whether an arrival may be counted towards this order at all ({@link #withStored}): it must be open, and an
     * automatic order must have been given something first.
     * <p>
     * The gate is the mirror image of {@link #endedWithLostIngredients()}. A machine cannot have made anything before
     * the crane dropped an ingredient at it, so an automatic order with nothing delivered can only be completed by
     * somebody else's items — and it is exactly the order that has delivered nothing which must be allowed to time out
     * without pausing anything.
     */
    public boolean countsArrivals() {
        return isOpen() && (!restock || deliveredIngredients() > 0);
    }

    /**
     * Whether this order ended <b>badly</b>: it is finished, it did not complete, and the crane had already dropped
     * ingredients at the production station. Those items are gone ({@code docs/warehouse-system.md} §3.5.4), and for
     * an automatic order this is exactly the safety stop's condition (M15 part 2).
     * <p>
     * An order that gave up while the crane was still fetching answers {@code false}: nothing left the warehouse, so
     * there is nothing to protect a player from.
     * <p>
     * For an automatic order, {@link ProductionOrderState#COMPLETE} really is evidence that the machine gave something
     * back, because such an order is only ever completed by items the warehouse stored out of one of its inputs
     * ({@link #countsStockLevels()}). The one thing that remains indistinguishable is a <i>second</i> source of the
     * same product feeding the same warehouse through an input: the warehouse did receive the items, and no
     * bookkeeping can say which machine made them ({@code docs/warehouse-system.md} §3.6.3, ADR-026).
     */
    public boolean endedWithLostIngredients() {
        return state.isFinished() && state != ProductionOrderState.COMPLETE && deliveredIngredients() > 0;
    }

    /** The ingredient line with {@code lineId}, if this order has it. */
    public Optional<SupplyLine<K>> line(UUID lineId) {
        Objects.requireNonNull(lineId, "lineId");
        for (SupplyLine<K> line : lines) {
            if (line.id().equals(lineId))
                return Optional.of(line);
        }
        return Optional.empty();
    }

    /**
     * Items of {@code key} this order still has to take out of storage. 0 for a finished order, because a finished
     * order promises nothing any more — which is exactly what releases its reservations
     * ({@code WarehouseControllerBlockEntity#availableStock}).
     */
    public int outstanding(K key) {
        Objects.requireNonNull(key, "key");
        if (!isOpen())
            return 0;
        int total = 0;
        for (SupplyLine<K> line : lines) {
            if (line.key().equals(key))
                total += line.remaining();
        }
        return total;
    }

    /** Items over all ingredients this order still has to fetch. */
    public int outstandingIngredients() {
        if (!isOpen())
            return 0;
        int total = 0;
        for (SupplyLine<K> line : lines)
            total += line.remaining();
        return total;
    }

    /**
     * Items the crane already dropped into the production station, over all ingredients.
     * <p>
     * This is the number the "cannot be undone" boundary is about ({@code docs/warehouse-system.md} §3.5): a cancelled
     * or timed-out order with a positive count left those items at the machine, and nothing takes them back out of it.
     * It answers for a finished order too, which is exactly when it is asked.
     */
    public int deliveredIngredients() {
        int total = 0;
        for (SupplyLine<K> line : lines)
            total += line.delivered();
        return total;
    }

    /** Result items this order still waits for; 0 once it has seen enough or ended. */
    public long outstandingResult() {
        return isOpen() ? unproducedAmount() : 0L;
    }

    /**
     * Result items this order never saw arrive, <b>whatever its state</b>. Unlike {@link #outstandingResult()} this
     * also answers for a finished order, which is what a cancelled or timed-out order's backing request has to be
     * given back ({@code WarehouseControllerBlockEntity}): those items are never coming.
     */
    public long unproducedAmount() {
        return Math.max(0L, resultAmount - produced);
    }

    /**
     * Result items this order <b>promised its backing request</b> and never delivered: what that request has to be
     * given back when the order ends without producing everything ({@code WarehouseControllerBlockEntity}).
     * <p>
     * This is deliberately not {@link #unproducedAmount()}. A pattern makes whole runs, so {@link #resultAmount()} is
     * regularly larger than what the request asked production for; giving the request the whole shortfall back would
     * take items off it that production never owed it — items the aisle may well have in stock
     * ({@code docs/warehouse-system.md} §3.5.3, §3.5.4). 0 for an order nobody is waiting on.
     */
    public long unfulfilledPromise() {
        return Math.max(0L, promisedToRequest - produced);
    }

    /** This order with another deadline (a world reload gives every restored order its full timeout again). */
    public ProductionOrder<K, L> withDeadline(long newDeadline) {
        return new ProductionOrder<>(id, station, result, resultAmount, lines, state, newDeadline, produced,
                resultStockSeen, backingRequest, promisedToRequest, restock);
    }

    /** Whether every ingredient line has been served completely. */
    public boolean allIngredientsDelivered() {
        for (SupplyLine<K> line : lines) {
            if (!line.isComplete())
                return false;
        }
        return true;
    }

    /**
     * This order with {@code amount} more items delivered on line {@code lineId}. An unknown line or a delivery of 0
     * changes nothing. Once every line is complete the state becomes {@link ProductionOrderState#DELIVERED}; any
     * delivery pushes the deadline out.
     * <p>
     * A <b>finished</b> order still counts the delivery, but neither its state nor its deadline moves. A crane that
     * was already carrying ingredients when the order was cancelled really does drop them at the station, and
     * {@link #deliveredIngredients()} is the number the screen prints as "not recovered" — leaving such a drop
     * uncounted would understate the loss by exactly the amount that was in flight
     * ({@code docs/warehouse-system.md} §3.5.4).
     */
    public ProductionOrder<K, L> withDelivered(UUID lineId, int amount, long now, long timeoutTicks) {
        Objects.requireNonNull(lineId, "lineId");
        if (amount <= 0)
            return this;
        List<SupplyLine<K>> updated = new ArrayList<>(lines.size());
        boolean changed = false;
        for (SupplyLine<K> line : lines) {
            SupplyLine<K> next = line.id().equals(lineId) ? line.withDelivered(amount) : line;
            changed |= next != line;
            updated.add(next);
        }
        if (!changed)
            return this;
        if (!isOpen())
            return new ProductionOrder<>(id, station, result, resultAmount, updated, state, deadlineTick, produced,
                    resultStockSeen, backingRequest, promisedToRequest, restock);
        boolean complete = true;
        for (SupplyLine<K> line : updated)
            complete &= line.isComplete();
        return new ProductionOrder<>(id, station, result, resultAmount, updated,
                complete ? ProductionOrderState.DELIVERED : state, deadline(now, timeoutTicks), produced,
                resultStockSeen, backingRequest, promisedToRequest, restock);
    }

    /**
     * The production station's buffer no longer holds this order's ingredients, so the player's machine took them:
     * {@link ProductionOrderState#DELIVERED} becomes {@link ProductionOrderState#WAITING_FOR_RESULT}. Any other state
     * is unchanged.
     */
    public ProductionOrder<K, L> withIngredientsTaken(long now, long timeoutTicks) {
        if (state != ProductionOrderState.DELIVERED)
            return this;
        return new ProductionOrder<>(id, station, result, resultAmount, lines, ProductionOrderState.WAITING_FOR_RESULT,
                deadline(now, timeoutTicks), produced, resultStockSeen, backingRequest, promisedToRequest, restock);
    }

    /**
     * This order after observing the result's current stock level.
     * <p>
     * Only <b>increases</b> count: the level also falls when the crane serves a request out of the same stock, and a
     * falling level says nothing about production. The order completes once it has seen {@link #resultAmount()} items
     * arrive — from any source, which is deliberate for an order somebody is waiting for: a player who puts the product
     * in by hand has satisfied the request just as well.
     * <p>
     * An <b>automatic</b> order is not counted here at all ({@link #countsStockLevels()}); {@link #withStored} is its
     * only channel.
     */
    public ProductionOrder<K, L> withResultStock(long stockNow, long now, long timeoutTicks) {
        return withResultStock(stockNow, observableGain(stockNow), now, timeoutTicks);
    }

    /**
     * This order after {@code amount} result items really arrived in the warehouse: the crane stored them out of one of
     * the aisle's own warehouse inputs, which is the route the product of a pattern takes back into the racks
     * ({@code docs/warehouse-system.md} §3.5).
     * <p>
     * Unlike {@link #withResultStock} this counts an <b>event</b> and not a level, so it needs no baseline and cannot be
     * fooled by the level falling and rising again. It is what an automatic order is completed by, and for such an
     * order nothing is counted before the crane has dropped an ingredient at the machine ({@link #countsArrivals()}).
     * An arrival of 0, a closed order and an order that already has everything it waits for all change nothing.
     */
    public ProductionOrder<K, L> withStored(long amount, long now, long timeoutTicks) {
        if (amount <= 0L || !countsArrivals())
            return this;
        long counted = Math.min(amount, outstandingResult());
        if (counted <= 0L)
            return this;
        long total = saturatedAdd(produced, counted);
        boolean complete = total >= resultAmount;
        return new ProductionOrder<>(id, station, result, resultAmount, lines,
                complete ? ProductionOrderState.COMPLETE : state, complete ? now : deadline(now, timeoutTicks), total,
                resultStockSeen, backingRequest, promisedToRequest, restock);
    }

    /**
     * Result items this order could count from a stock level of {@code stockNow}: the increase over the level it last
     * saw. 0 for a finished order and for a level that did not rise.
     * <p>
     * It is separate from {@link #withResultStock(long, long, long, long)} because one arrival must be credited
     * <b>once</b>: with two open orders for the same result, each would otherwise count the same items in full and
     * both would complete although only one batch was made ({@link ProductionOrders#observeResult}). An automatic order
     * answers 0 whatever the level is ({@link #countsStockLevels()}).
     */
    public long observableGain(long stockNow) {
        return isOpen() && countsStockLevels() ? Math.max(0L, Math.max(0L, stockNow) - resultStockSeen) : 0L;
    }

    /**
     * {@link #withResultStock(long, long, long)} with the part of the increase that belongs to <b>this</b> order:
     * {@code gained} is counted as produced (never more than it still waits for), and the level is remembered as the
     * new baseline either way, so the same arrival is not seen again on the next observation.
     */
    public ProductionOrder<K, L> withResultStock(long stockNow, long gained, long now, long timeoutTicks) {
        if (!isOpen() || !countsStockLevels())
            return this;
        long current = Math.max(0L, stockNow);
        long counted = Math.max(0L, Math.min(gained, outstandingResult()));
        if (counted == 0L && current == resultStockSeen)
            return this;
        long total = saturatedAdd(produced, counted);
        boolean complete = total >= resultAmount;
        // A finished order's deadline is the moment it ended, so the controller can forget it a fixed time later
        // (ProductionOrders#prune); an open one's deadline is pushed out by every arrival it really counted — an
        // arrival that went to another order is no progress of this one and must not extend its timeout.
        long nextDeadline = complete ? now : counted > 0L ? deadline(now, timeoutTicks) : deadlineTick;
        return new ProductionOrder<>(id, station, result, resultAmount, lines,
                complete ? ProductionOrderState.COMPLETE : state, nextDeadline, total, current, backingRequest,
                promisedToRequest, restock);
    }

    /**
     * This order as timed out, if it is open and {@code now} has reached its deadline; unchanged otherwise. A timed-out
     * order stops promising ingredients, and the ones a machine already took are not recovered
     * ({@link ProductionOrderState#handedIngredientsOver()}).
     */
    public ProductionOrder<K, L> timedOutAt(long now) {
        if (!isOpen() || now < deadlineTick)
            return this;
        return endedAt(ProductionOrderState.TIMED_OUT, now);
    }

    /** This order as cancelled at {@code now}; a finished order is unchanged. */
    public ProductionOrder<K, L> cancelled(long now) {
        return isOpen() ? endedAt(ProductionOrderState.CANCELLED, now) : this;
    }

    /**
     * This order without its backing request (the request was served, cancelled or lost). The promise goes with it:
     * there is nobody left to give anything back to, so the order simply runs on and its result lands in stock.
     */
    public ProductionOrder<K, L> withoutBackingRequest() {
        if (backingRequest.isEmpty())
            return this;
        return new ProductionOrder<>(id, station, result, resultAmount, lines, state, deadlineTick, produced,
                resultStockSeen, Optional.empty(), 0L, restock);
    }

    /**
     * This order in a finished state, with its deadline set to the moment it ended. A finished order's deadline is no
     * longer a timeout but the timestamp {@link ProductionOrders#prune} forgets it from.
     */
    private ProductionOrder<K, L> endedAt(ProductionOrderState next, long now) {
        return new ProductionOrder<>(id, station, result, resultAmount, lines, next, now, produced, resultStockSeen,
                backingRequest, promisedToRequest, restock);
    }

    /** The deadline {@code timeoutTicks} after {@code now}, saturating instead of overflowing. */
    private static long deadline(long now, long timeoutTicks) {
        return saturatedAdd(now, Math.max(1L, timeoutTicks));
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
