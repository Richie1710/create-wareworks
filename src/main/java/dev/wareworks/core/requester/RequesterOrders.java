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
 * The open orders of one Schematicannon Requester (issue #45, ADR-080, amended by ADR-081), oldest first and at most
 * {@value RequesterLimits#MAX_ORDERS}: what keeps it from ordering an item twice. The block entity owns one and saves it;
 * it orders nothing and moves nothing. Pure Java, not thread-safe (server thread only).
 * <p>
 * <b>Phases</b> ({@link #advance}, on each poll that reached the terminal and its controller):
 * <ul>
 * <li>{@link RequesterOrder.Phase#RUNNING} moves on once its request is gone or its own share was delivered, first in,
 * first served ({@link OrderShare}). While it waits, its live age grows by the poll's live step, and once it reaches
 * the dead-man switch the order is given up: it leaves, and the caller gives back the part of its share that is
 * provably its own and still outstanding ({@link GivenBack}, {@link OrderShare#returnable}).</li>
 * <li><b>A made part</b> (ADR-081): an order whose share production makes in part follows the root order of the plan
 * it started ({@link RootState}). While that root is open the order stays running whatever first in, first served
 * says — a player's stock click merged in behind it can be delivered before the made items — and its live age stands,
 * as it does for any order whose request an open production order still makes items for ({@link Request#making()}):
 * the production order has a timeout of its own. A root that completed ends the made part; one that failed takes
 * exactly what it still owed off the share ({@link Shortfall}), the same amount the controller took off the request,
 * and a share with nothing left leaves. A root the controller no longer knows ({@link RootState.Kind#GONE}: it ended
 * and was pruned while the requester was not looking) may have done either: a drop of the request below what is
 * ahead and the share is taken as the made part's shortfall, and the rest of the made part is
 * {@linkplain RequesterOrder#unsure() unsure} and never given back. The same settling runs before every give-back
 * ({@link #settle}), so a give-back never counts a part the controller already took off the request.</li>
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
     * @param making    whether some <b>open</b> production order makes items for it — this requester's own root or a
     *                  merged player's (ADR-081); the live age of its orders stands meanwhile
     */
    public record Request(int requested, int delivered, boolean making) {
        /** A request no production order makes anything for. */
        public Request(int requested, int delivered) {
            this(requested, delivered, false);
        }
    }

    /**
     * Where the root production order of an order's made part stands (ADR-081).
     *
     * @param kind where it stands
     * @param owed what it promised the request and never made; {@link Kind#FAILED} only, 0 otherwise
     */
    public record RootState(Kind kind, int owed) {
        /** Where a root production order stands. */
        public enum Kind {
            /** Still running: the made part is on its way. */
            OPEN,
            /** Completed: it made everything it promised. */
            MADE,
            /** Ended without making everything; the controller took what it owed off the request. */
            FAILED,
            /**
             * No longer known: pruned after it ended while the requester was not looking. It may have made everything
             * or have failed, and then the controller took what it owed off the request.
             */
            GONE
        }

        /** A root that still runs. */
        public static final RootState OPEN = new RootState(Kind.OPEN, 0);
        /** A root that made everything it promised. */
        public static final RootState MADE = new RootState(Kind.MADE, 0);
        /** A root the controller no longer knows. */
        public static final RootState GONE = new RootState(Kind.GONE, 0);

        public RootState {
            Objects.requireNonNull(kind, "kind");
            owed = kind == Kind.FAILED ? Math.max(0, owed) : 0;
        }

        /** A root that ended owing its request {@code owed} items (clamped into {@code 0..Integer.MAX_VALUE}). */
        public static RootState failed(long owed) {
            return new RootState(Kind.FAILED, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, owed)));
        }
    }

    /**
     * An order that left before its share was through: given up by the dead-man switch or given back.
     *
     * @param order       the order as it was
     * @param outstanding what the caller reduces the request by: the part of the share that is provably its own and not
     *                    delivered ({@link OrderShare#returnable}, less the {@linkplain RequesterOrder#unsure() unsure}
     *                    made part), never a merged player's part. At least 1 from
     *                    {@link #giveBack}; a dead-man give-up may have 0, and then nothing is reduced
     * @param producing   the made part of {@code outstanding}, {@code 0..outstanding}: what the caller takes off the
     *                    promise of {@code root} and of no other production order (ADR-081)
     * @param root        the order's own root production order, present exactly when {@code producing > 0}
     * @param <K>         item key type
     */
    public record GivenBack<K>(RequesterOrder<K> order, int outstanding, int producing, Optional<UUID> root) {
        public GivenBack {
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(root, "root");
            if (outstanding < 0)
                throw new IllegalArgumentException("outstanding must not be negative: " + outstanding);
            if (producing < 0 || producing > outstanding)
                throw new IllegalArgumentException("producing must be within 0.." + outstanding + ": " + producing);
            if ((producing > 0) != root.isPresent())
                throw new IllegalArgumentException("a made part has a root and only a made part has one: " + root);
        }

        /** An order given back of which nothing is made. */
        public GivenBack(RequesterOrder<K> order, int outstanding) {
            this(order, outstanding, 0, Optional.empty());
        }

        /** {@code order} giving back {@code outstanding} items, as much of them from its made part as it has. */
        static <K> GivenBack<K> of(RequesterOrder<K> order, int outstanding) {
            int made = Math.min(outstanding, order.producing());
            return new GivenBack<>(order, outstanding, made, made > 0 ? order.root() : Optional.empty());
        }
    }

    /**
     * A made part that fell short: the order's root ended owing {@code cut} items of its share, and the share is that
     * much smaller now (ADR-081). The caller takes the same amount off what it counted as ordered.
     *
     * @param order the order as it was before the cut
     * @param cut   how many items its share lost, at least 1
     * @param <K>   item key type
     */
    public record Shortfall<K>(RequesterOrder<K> order, int cut) {
        public Shortfall {
            Objects.requireNonNull(order, "order");
            if (cut < 1)
                throw new IllegalArgumentException("cut must be at least 1: " + cut);
        }
    }

    /**
     * What one {@link #advance} did.
     *
     * @param changed    whether an order moved, aged or left
     * @param gaveUp     the orders the dead-man switch gave up
     * @param shortfalls the made parts that fell short
     * @param <K>        item key type
     */
    public record Advance<K>(boolean changed, List<GivenBack<K>> gaveUp, List<Shortfall<K>> shortfalls) {
        public Advance {
            gaveUp = List.copyOf(gaveUp);
            shortfalls = List.copyOf(shortfalls);
        }

        /** What one advance without a made part did. */
        public Advance(boolean changed, List<GivenBack<K>> gaveUp) {
            this(changed, gaveUp, List.of());
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

    /** Whether production still makes part of some running order's share (ADR-081). */
    public boolean hasMaking() {
        for (RequesterOrder<K> order : orders) {
            if (order.isMaking())
                return true;
        }
        return false;
    }

    /**
     * Why no order for {@code missing} may be placed now, or empty when it may: {@link RequesterStatus#ORDERED} (or
     * {@link RequesterStatus#MAKING} while part of it is made), {@link RequesterStatus#IN_BUFFER},
     * {@link RequesterStatus#GRACE} for a matching order in that phase (the first by
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
                found = RequesterStatus.first(found, statusOf(order));
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
        return place(key, strict, requestId, share, ahead, aheadDelivered, 0, Optional.empty());
    }

    /**
     * Adds a fresh running order for a granted share of which production makes {@code producing} items (ADR-081).
     *
     * @param ahead          what the request held before the share
     * @param aheadDelivered what of the request had been delivered when the share was granted
     * @param producing      the made part of the share, {@code 0..share}
     * @param root           the root production order of the plan the share started, present exactly when
     *                       {@code producing > 0}
     * @return whether it was added; not when {@value RequesterLimits#MAX_ORDERS} orders are open
     */
    public boolean place(K key, boolean strict, UUID requestId, int share, int ahead, int aheadDelivered,
            int producing, Optional<UUID> root) {
        if (isFull())
            return false;
        orders.add(RequesterOrder.placed(key, strict, requestId, share, Math.max(0, ahead), aheadDelivered, producing,
                root));
        return true;
    }

    /**
     * {@link #advance(long, int, Function, Function, Collection, int, int)} for orders of which nothing is made: a root
     * it is asked about anyway counts as {@link RootState#GONE}.
     */
    public Advance<K> advance(long now, int liveStep, Function<UUID, Optional<Request>> requests,
            Collection<? extends K> buffered, int graceTicks, int timeoutTicks) {
        return advance(now, liveStep, requests, id -> RootState.GONE, buffered, graceTicks, timeoutTicks);
    }

    /**
     * Moves every order on (see the class comment). Call it on a poll that reached the terminal and found its
     * controller, and only then: the live age must not grow while the warehouse could not serve the order.
     * <p>
     * A running order, in this order: a request that is gone sends it to the buffer; a made part is held against its
     * root ({@code roots}); then first in, first served. It moves to the buffer once its own share is delivered
     * <b>and</b> nothing of it is still being made; otherwise it ages by the live step, unless an open production order
     * makes items for its request.
     *
     * @param now          the game time
     * @param liveStep     the live ticks since the previous such poll ({@link RequesterLimits#liveStep})
     * @param requests     the open request of an id, empty when it is gone
     * @param roots        where a root production order stands; asked only for orders with a made part
     * @param buffered     the keys of the stacks in the terminal's buffer
     * @param graceTicks   the block's grace in ticks
     * @param timeoutTicks the dead-man switch in live ticks (clamped, {@link RequesterLimits#clampTimeout})
     */
    public Advance<K> advance(long now, int liveStep, Function<UUID, Optional<Request>> requests,
            Function<UUID, RootState> roots, Collection<? extends K> buffered, int graceTicks, int timeoutTicks) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(buffered, "buffered");
        int timeout = RequesterLimits.clampTimeout(timeoutTicks);
        int step = Math.max(0, liveStep);
        long grace = Math.max(0, graceTicks);
        boolean changed = false;
        List<GivenBack<K>> gaveUp = new ArrayList<>();
        List<Shortfall<K>> shortfalls = new ArrayList<>();
        List<RequesterOrder<K>> next = new ArrayList<>(orders.size());
        for (RequesterOrder<K> order : orders) {
            RequesterOrder<K> moved = order;
            if (moved.phase() == RequesterOrder.Phase.RUNNING) {
                Optional<Request> request = Objects.requireNonNull(requests.apply(moved.requestId()),
                        "requests returned null");
                if (request.isEmpty()) {
                    moved = moved.withMadeEnded().inBuffer();
                } else {
                    Request live = request.get();
                    moved = settled(moved, live, roots, shortfalls);
                    if (moved == null) {
                        changed = true;
                        continue;
                    }
                    moved = moved.withAhead(OrderShare.ahead(live.requested(), moved.ahead(), moved.share()));
                    int outstanding = OrderShare.outstanding(live.requested(), live.delivered(), moved.ahead(),
                            moved.share());
                    if (outstanding == 0 && moved.producing() == 0) {
                        moved = moved.inBuffer();
                    } else if (!live.making()) {
                        // With a made part the request is making, so the clock stands; the production order's own
                        // timeout ends a chain that is stuck.
                        int age = (int) Math.min(Integer.MAX_VALUE, (long) moved.liveAge() + step);
                        moved = moved.withLiveAge(age);
                        if (age >= timeout) {
                            gaveUp.add(GivenBack.of(moved, returnable(moved, live)));
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
        return new Advance<>(changed, gaveUp, shortfalls);
    }

    /**
     * Settles every running order's made part against its root, exactly as {@link #advance} does, without moving,
     * ageing or giving up anything: a failed root's shortfall is cut off the share (a share with nothing left leaves),
     * a root that is gone makes the rest of the made part {@linkplain RequesterOrder#unsure() unsure}, and a completed
     * one ends it. Call it before {@link #giveBack} whenever {@link #advance} has not just run: the controller takes a
     * failed root's shortfall off the merged request at once, and an unsettled share would count that part as its own
     * and give it back a second time, off a merged player's items.
     *
     * @param requests the open request of an id, empty when it is gone
     * @param roots    where a root production order stands; asked only for orders with a made part
     * @return the made parts that fell short, for the caller to take off what it counted as ordered
     */
    public List<Shortfall<K>> settle(Function<UUID, Optional<Request>> requests, Function<UUID, RootState> roots) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(roots, "roots");
        List<Shortfall<K>> shortfalls = new ArrayList<>();
        List<RequesterOrder<K>> next = new ArrayList<>(orders.size());
        for (RequesterOrder<K> order : orders) {
            RequesterOrder<K> moved = order;
            if (moved.isMaking()) {
                Optional<Request> request = Objects.requireNonNull(requests.apply(moved.requestId()),
                        "requests returned null");
                if (request.isPresent())
                    moved = settled(moved, request.get(), roots, shortfalls);
            }
            if (moved != null)
                next.add(moved);
        }
        orders.clear();
        orders.addAll(next);
        return shortfalls;
    }

    /**
     * Gives every order up: the remote was taken out, the block broken, or the print stopped. Returns the running
     * orders whose request is still open with part of their share provably outstanding ({@link OrderShare#returnable},
     * less the {@linkplain RequesterOrder#unsure() unsure} made part), for the caller to reduce their requests by it
     * ({@code reduceRequestShare}, never a cancel: a merged id would take players' items down with it), and forgets
     * every order. What of that is the made part is taken off the promise of the order's own root only
     * ({@link GivenBack#root()}); the production order runs on and its items land in stock (ADR-081). The made parts
     * must be {@linkplain #settle settled} first; one that is not counts as still being made.
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
                back.add(GivenBack.of(order, outstanding));
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

    /**
     * {@code order}'s made part held against its root ({@link #advance}, {@link #settle}), or {@code null} when a
     * shortfall left nothing of the share. A root that is still open leaves the order as it is.
     */
    private static <K> RequesterOrder<K> settled(RequesterOrder<K> order, Request live,
            Function<UUID, RootState> roots, List<Shortfall<K>> shortfalls) {
        if (order.producing() == 0)
            return order;
        RootState root = Objects.requireNonNull(roots.apply(order.root().orElseThrow()), "roots returned null");
        int cut;
        switch (root.kind()) {
            case OPEN -> {
                // The made part is on its way: nothing to do.
                return order;
            }
            case MADE -> {
                return order.withMadeEnded();
            }
            case FAILED -> {
                // The controller took exactly what the root owed off the merged request (onProductionOrderFinished),
                // so the share loses the same: the request asks for what is ahead, this share and what is behind
                // again, and nothing ahead looks reduced.
                cut = Math.min(root.owed(), order.producing());
                if (cut == 0)
                    return order.withMadeEnded();
                shortfalls.add(new Shortfall<>(order, cut));
                return order.share() <= cut ? null : order.withMadeEnded().withShareCut(cut);
            }
            default -> {
                // GONE: what the root owed is unknown. A request below what is ahead plus the share lost items either
                // ahead or of this share's own made part, and counting them as the share's own never gives back a
                // player's items. What is behind can hide a shortfall, so the rest of the made part is unsure and is
                // never given back.
                long drop = (long) order.ahead() + order.share() - live.requested();
                cut = (int) Math.max(0L, Math.min(order.producing(), drop));
                if (cut > 0)
                    shortfalls.add(new Shortfall<>(order, cut));
                return order.share() <= cut ? null : order.withMadeGone(cut);
            }
        }
    }

    /**
     * What of {@code order}'s share may be given back to {@code request} ({@link OrderShare#returnable}): never its
     * {@linkplain RequesterOrder#unsure() unsure} made part, which may already have been taken off the request.
     */
    private static int returnable(RequesterOrder<?> order, Request request) {
        return Math.max(0, OrderShare.returnable(request.requested(), request.delivered(), order.ahead(),
                order.aheadDelivered(), order.share()) - order.unsure());
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

    /**
     * The status {@code order} shows: {@link RequesterStatus#MAKING} while it runs with a made part,
     * {@link RequesterStatus#ORDERED} while it runs from stock, then {@link RequesterStatus#IN_BUFFER} and
     * {@link RequesterStatus#GRACE}.
     */
    public static RequesterStatus statusOf(RequesterOrder<?> order) {
        return switch (order.phase()) {
            case RUNNING -> order.producing() > 0 ? RequesterStatus.MAKING : RequesterStatus.ORDERED;
            case IN_BUFFER -> RequesterStatus.IN_BUFFER;
            case GRACE -> RequesterStatus.GRACE;
        };
    }
}
