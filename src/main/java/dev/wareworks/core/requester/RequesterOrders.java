package dev.wareworks.core.requester;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The open orders of one Schematicannon Requester (issue #45, ADR-080), oldest first and at most
 * {@value RequesterLimits#MAX_ORDERS}: what keeps it from ordering an item twice. The block entity owns one and saves it;
 * it orders nothing and moves nothing. Pure Java, not thread-safe (server thread only).
 * <p>
 * <b>Phases</b> ({@link #advance}, on each poll that reached the terminal and its controller):
 * <ul>
 * <li>{@link RequesterOrder.Phase#RUNNING} moves on once its request is gone or its own share was delivered, first in,
 * first served ({@link OrderShare}). While it waits, its live age grows by the poll's live step, and once it reaches
 * the dead-man switch the order is given up: it leaves, and the caller gives back the part of its share that is
 * provably its own and still outstanding ({@link GivenBack}, {@link OrderShare#returnable}).</li>
 * <li>{@link RequesterOrder.Phase#IN_BUFFER} stays while the terminal's buffer holds a matching stack. It has <b>no
 * timeout</b>: an item in the buffer is never ordered again, and the goggles point at the broken belt.</li>
 * <li>{@link RequesterOrder.Phase#GRACE} waits the block's grace after the items left the buffer, then the order is
 * removed.</li>
 * </ul>
 * A phase that is over moves on in the same poll, so a funnel that empties the buffer between two polls goes straight
 * from running to the grace.
 * <p>
 * <b>Locks</b> ({@link #lock}): no order for the missing item while a matching order exists in any phase, while the
 * buffer holds a matching stack (also one a player or another requester ordered: it goes to the cannon all the same),
 * or while {@value RequesterLimits#MAX_ORDERS} orders are open.
 * <p>
 * <b>Matching.</b> Orders and buffered stacks are compared by item. Two strict requirements (exact components) match
 * only when their keys are equal; a strict and a plain one of the same item match, because any variant satisfies the
 * plain one and the strict order holds back a second order for the same item until it is through. A buffered stack
 * matches a strict missing item only when its key is equal, so an unrelated variant lying in the buffer does not hold a
 * strict order back for ever.
 *
 * @param <K> item key type (a variant: item and components)
 * @param <I> item type
 */
public final class RequesterOrders<K, I> {
    /**
     * A request as the controller has it now.
     *
     * @param requested what it asks for in all, merged amounts included
     * @param delivered what it has delivered so far
     */
    public record Request(int requested, int delivered) {
    }

    /**
     * An order that left before its share was through: given up by the dead-man switch or given back.
     *
     * @param order       the order as it was
     * @param outstanding what the caller reduces the request by: the part of the share that is provably its own and not
     *                    delivered ({@link OrderShare#returnable}), never a merged player's part. At least 1 from
     *                    {@link #giveBack}; a dead-man give-up may have 0, and then nothing is reduced
     * @param <K>         item key type
     */
    public record GivenBack<K>(RequesterOrder<K> order, int outstanding) {
        public GivenBack {
            Objects.requireNonNull(order, "order");
            if (outstanding < 0)
                throw new IllegalArgumentException("outstanding must not be negative: " + outstanding);
        }
    }

    /**
     * What one {@link #advance} did.
     *
     * @param changed whether an order moved, aged or left
     * @param gaveUp  the orders the dead-man switch gave up
     * @param <K>     item key type
     */
    public record Advance<K>(boolean changed, List<GivenBack<K>> gaveUp) {
        public Advance {
            gaveUp = List.copyOf(gaveUp);
        }
    }

    private final List<RequesterOrder<K>> orders = new ArrayList<>();
    private final Function<? super K, ? extends I> itemOf;

    /** @param itemOf the item of a key */
    public RequesterOrders(Function<? super K, ? extends I> itemOf) {
        this.itemOf = Objects.requireNonNull(itemOf, "itemOf");
    }

    /** Every order, oldest first (unmodifiable copy). */
    public List<RequesterOrder<K>> all() {
        return List.copyOf(orders);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public int size() {
        return orders.size();
    }

    /** Whether {@value RequesterLimits#MAX_ORDERS} orders are open. */
    public boolean isFull() {
        return orders.size() >= RequesterLimits.MAX_ORDERS;
    }

    /** Whether some request still waits for an order's share. */
    public boolean hasRunning() {
        for (RequesterOrder<K> order : orders) {
            if (order.isRunning())
                return true;
        }
        return false;
    }

    /**
     * Why no order for {@code missing} may be placed now, or empty when it may: {@link RequesterStatus#ORDERED},
     * {@link RequesterStatus#IN_BUFFER}, {@link RequesterStatus#GRACE} for a matching order in that phase (the first by
     * precedence when several match), {@link RequesterStatus#IN_BUFFER} too for a matching stack in the buffer, and
     * {@link RequesterStatus#ORDERS_FULL} when no order matches but none can be added.
     *
     * @param missing  the key of the stack the cannon reports missing
     * @param strict   whether the requirement wants exactly that key
     * @param buffered the keys of the stacks in the terminal's buffer, one snapshot per decision
     */
    public Optional<RequesterStatus> lock(K missing, boolean strict, Collection<? extends K> buffered) {
        Objects.requireNonNull(missing, "missing");
        Objects.requireNonNull(buffered, "buffered");
        RequesterStatus found = null;
        for (RequesterOrder<K> order : orders) {
            if (matchesMissing(order, missing, strict))
                found = RequesterStatus.first(found, statusOf(order.phase()));
        }
        for (K stack : buffered) {
            if (stack != null && (strict ? stack.equals(missing) : sameItem(stack, missing))) {
                found = RequesterStatus.first(found, RequesterStatus.IN_BUFFER);
                break;
            }
        }
        if (found == null && isFull())
            found = RequesterStatus.ORDERS_FULL;
        return Optional.ofNullable(found);
    }

    /**
     * Adds a fresh running order for a granted share, into a request of which nothing had been delivered.
     *
     * @return whether it was added; not when {@value RequesterLimits#MAX_ORDERS} orders are open
     */
    public boolean place(K key, boolean strict, UUID requestId, int share, int ahead) {
        return place(key, strict, requestId, share, ahead, 0);
    }

    /**
     * Adds a fresh running order for a granted share.
     *
     * @param ahead          what the request held before the share
     * @param aheadDelivered what of the request had been delivered when the share was granted
     * @return whether it was added; not when {@value RequesterLimits#MAX_ORDERS} orders are open
     */
    public boolean place(K key, boolean strict, UUID requestId, int share, int ahead, int aheadDelivered) {
        if (isFull())
            return false;
        orders.add(RequesterOrder.placed(key, strict, requestId, share, Math.max(0, ahead), aheadDelivered));
        return true;
    }

    /**
     * Moves every order on (see the class comment). Call it on a poll that reached the terminal and found its
     * controller, and only then: the live age must not grow while the warehouse could not serve the order.
     *
     * @param now          the game time
     * @param liveStep     the live ticks since the previous such poll ({@link RequesterLimits#liveStep})
     * @param requests     the open request of an id, empty when it is gone
     * @param buffered     the keys of the stacks in the terminal's buffer
     * @param graceTicks   the block's grace in ticks
     * @param timeoutTicks the dead-man switch in live ticks (clamped, {@link RequesterLimits#clampTimeout})
     */
    public Advance<K> advance(long now, int liveStep, Function<UUID, Optional<Request>> requests,
            Collection<? extends K> buffered, int graceTicks, int timeoutTicks) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(buffered, "buffered");
        int timeout = RequesterLimits.clampTimeout(timeoutTicks);
        int step = Math.max(0, liveStep);
        long grace = Math.max(0, graceTicks);
        boolean changed = false;
        List<GivenBack<K>> gaveUp = new ArrayList<>();
        List<RequesterOrder<K>> next = new ArrayList<>(orders.size());
        for (RequesterOrder<K> order : orders) {
            RequesterOrder<K> moved = order;
            if (moved.phase() == RequesterOrder.Phase.RUNNING) {
                Optional<Request> request = Objects.requireNonNull(requests.apply(moved.requestId()),
                        "requests returned null");
                if (request.isEmpty()) {
                    moved = moved.inBuffer();
                } else {
                    Request live = request.get();
                    moved = moved.withAhead(OrderShare.ahead(live.requested(), moved.ahead(), moved.share()));
                    int outstanding = OrderShare.outstanding(live.requested(), live.delivered(), moved.ahead(),
                            moved.share());
                    if (outstanding == 0) {
                        moved = moved.inBuffer();
                    } else {
                        int age = (int) Math.min(Integer.MAX_VALUE, (long) moved.liveAge() + step);
                        moved = moved.withLiveAge(age);
                        if (age >= timeout) {
                            gaveUp.add(new GivenBack<>(moved, returnable(moved, live)));
                            changed = true;
                            continue;
                        }
                    }
                }
            }
            if (moved.phase() == RequesterOrder.Phase.IN_BUFFER && !buffers(moved, buffered))
                moved = moved.inGrace(now + grace);
            if (moved.phase() == RequesterOrder.Phase.GRACE && now >= moved.graceUntil()) {
                changed = true;
                continue;
            }
            if (!moved.equals(order))
                changed = true;
            next.add(moved);
        }
        orders.clear();
        orders.addAll(next);
        return new Advance<>(changed, gaveUp);
    }

    /**
     * Gives every order up: the remote was taken out, the block broken, or the print stopped. Returns the running
     * orders whose request is still open with part of their share provably outstanding ({@link OrderShare#returnable}),
     * for the caller to reduce their requests by it ({@code reduceRequestShare}, never a cancel: a merged id would take
     * players' items down with it), and forgets every order.
     *
     * @param requests the open request of an id, empty when it is gone
     */
    public List<GivenBack<K>> giveBack(Function<UUID, Optional<Request>> requests) {
        Objects.requireNonNull(requests, "requests");
        List<GivenBack<K>> back = new ArrayList<>();
        for (RequesterOrder<K> order : orders) {
            if (!order.isRunning())
                continue;
            Optional<Request> request = Objects.requireNonNull(requests.apply(order.requestId()),
                    "requests returned null");
            if (request.isEmpty())
                continue;
            int outstanding = returnable(order, request.get());
            if (outstanding > 0)
                back.add(new GivenBack<>(order, outstanding));
        }
        orders.clear();
        return back;
    }

    /** Forgets every order without giving anything back: the terminal could not be reached to do so. */
    public void clear() {
        orders.clear();
    }

    /** The sum of the orders' shares per item, in every phase: what a fresh material list does not count yet. */
    public Map<I, Integer> liveShares() {
        Map<I, Integer> shares = new LinkedHashMap<>();
        for (RequesterOrder<K> order : orders)
            shares.merge(itemOf.apply(order.key()), order.share(), (a, b) -> (int) Math.min(Integer.MAX_VALUE,
                    (long) a + b));
        return shares;
    }

    /**
     * Restores saved orders in their saved order: {@code null}s and a second order of the same request are skipped, and
     * at most {@value RequesterLimits#MAX_ORDERS} are kept.
     */
    public void restore(List<RequesterOrder<K>> saved) {
        Objects.requireNonNull(saved, "saved");
        orders.clear();
        Set<UUID> seen = new HashSet<>();
        for (RequesterOrder<K> order : saved) {
            if (orders.size() >= RequesterLimits.MAX_ORDERS)
                break;
            if (order != null && seen.add(order.requestId()))
                orders.add(order.phase() == RequesterOrder.Phase.GRACE ? order : order.withGraceUntilCleared());
        }
    }

    /** What of {@code order}'s share may be given back to {@code request} ({@link OrderShare#returnable}). */
    private static int returnable(RequesterOrder<?> order, Request request) {
        return OrderShare.returnable(request.requested(), request.delivered(), order.ahead(), order.aheadDelivered(),
                order.share());
    }

    /** Whether {@code order} holds back an order for {@code missing} (see the class comment). */
    public boolean matchesMissing(RequesterOrder<K> order, K missing, boolean strict) {
        if (!sameItem(order.key(), missing))
            return false;
        return !(order.strict() && strict) || order.key().equals(missing);
    }

    private boolean buffers(RequesterOrder<K> order, Collection<? extends K> buffered) {
        for (K stack : buffered) {
            if (stack != null && (order.strict() ? stack.equals(order.key()) : sameItem(stack, order.key())))
                return true;
        }
        return false;
    }

    private boolean sameItem(K a, K b) {
        return Objects.equals(itemOf.apply(a), itemOf.apply(b));
    }

    private static RequesterStatus statusOf(RequesterOrder.Phase phase) {
        return switch (phase) {
            case RUNNING -> RequesterStatus.ORDERED;
            case IN_BUFFER -> RequesterStatus.IN_BUFFER;
            case GRACE -> RequesterStatus.GRACE;
        };
    }
}
