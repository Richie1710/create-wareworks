package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What one player has asked a warehouse terminal for, counted per item type, so that {@link TerminalSort#USED} can put
 * the things they really use first (M24, issue #17).
 * <p>
 * One store belongs to one player and lives on the <b>server</b> (every terminal of the world therefore answers the
 * same way, and a reconnect does not forget). Pure Java and mutable: the content layer records a request, saves
 * {@link #entries()} and restores them with {@link #replaceAll(Collection)}; nothing here knows a {@code Level}, a tick
 * or a clock.
 *
 * <h2>What counts</h2>
 * <b>One request counts once</b>, whatever amount it asked for. The order answers "what does this player keep fetching",
 * not "what moved the most items" — otherwise a single ctrl-click on a thousand cobblestone would outrank a hundred
 * deliberate requests for a gearbox. The caller counts a request the terminal <b>accepted</b> (granted, or waiting for
 * stock): a refusal is not a habit, and counting refusals would let mis-clicks reorder the list. A clipboard list
 * counts once per item type on it, at the moment the player starts the order
 * ({@code content.station.TerminalPreferences#countListOrder}), because that is when and what the player asked for —
 * the order then runs on for minutes with nobody at the terminal.
 * <p>
 * <b>One action that names many item types is counted as one action</b> ({@link #recordAll}), never as a run of
 * separate requests. That is not a convenience: a clipboard list may be longer than this store is large
 * ({@code maxTerminalListEntries} is 128 by default against 64 entries here, and 1024 against 256 at the extremes), and
 * a run of plain {@link #record} calls would then make the later keys of the list evict its earlier ones — a brand new
 * entry is the weakest entry there is, so it is the next call's own victim. A repeated identical list would be a fixed
 * point at count 1 and could never teach the terminal anything, which is precisely the action a building player repeats
 * most. {@link #recordAll} therefore raises what the store already knows <b>first</b> and never evicts what the same
 * action counted; see its own comment.
 *
 * <h2>Four bounds, so nothing can grow without end</h2>
 * <ul>
 * <li><b>How many item types</b> — {@link #capacity()}, at most {@link #MAX_CAPACITY}. A player who has worked through
 * a large modpack would otherwise accumulate a count per item type that exists, in save data that is written for every
 * player of the world.</li>
 * <li><b>How large a count</b> — {@link #MAX_COUNT}, enforced by the fade below, so every number this class stores
 * fits in a {@code short}.</li>
 * <li><b>How large a recency stamp</b> — {@link #MAX_STAMP}; the stamps are renumbered from 1 whenever they would run
 * past it, which keeps the numbers small even for a player who only ever requests item types they never requested
 * before (that case never triggers the fade, because no count ever grows).</li>
 * <li><b>How large one item type may be</b> — not here, because this class cannot see an item: a key is whatever the
 * content layer makes it, and an {@code ItemKey} carries the item's whole data-component patch, so a shulker box with
 * a container component or a written book is a key of kilobytes. The three bounds above are all counts of
 * <i>things</i> and would happily bound 64 such keys, so the size bound lives where the bytes are
 * ({@code content.station.TerminalPreferences#MAX_KEY_SIZE} refuses to remember an oversized key at all, and
 * {@code MAX_USED_SIZE} bounds the whole saved list). Without it this store is bounded in entries and unbounded in
 * save data (M24 review fix).</li>
 * </ul>
 *
 * <h2>What is dropped when the cap is reached</h2>
 * The <b>weakest</b> entry: the lowest count, and among equal counts the one that was requested longest ago. A new
 * item type is therefore always learned — it displaces the weakest entry rather than being refused — because a player
 * whose build has moved on to another set of items has to be able to re-teach the terminal. The price is that a brand
 * new entry (count 1) is itself the next candidate, so a passing one-off click does not push a real favourite out;
 * only something asked for repeatedly climbs.
 * <p>
 * The one exception is <b>within a single action</b> ({@link #recordAll}): what that action has just counted is not a
 * candidate for the rest of it, so a list cannot spend its own capacity on itself. A list with more new item types
 * than the store has room for therefore keeps the ones it named <b>first</b> and reports the rest as not counted,
 * rather than ending with the last few and nothing else.
 * <p>
 * The stamp is a counter of recorded requests, not a world time: the model needs an order of events, not a date, and a
 * logical counter is the only one a pure class can have.
 *
 * <h2>Whether counts fade, and why they fade by use and not by time</h2>
 * They fade. When a count reaches {@link #MAX_COUNT} <b>every</b> count is halved, and whatever falls to zero is
 * forgotten. So a habit that stopped keeps shrinking as a new one grows, and the list follows what a player does now
 * instead of ossifying around the first week of a world.
 * <p>
 * The fade is driven by <b>use</b>, never by elapsed time, and that is a decision:
 * <ul>
 * <li>A time-based decay needs a clock, which this class deliberately does not have — it would have to be passed into
 * every call, saved, and made to survive a world whose time jumps.</li>
 * <li>Time-based decay punishes a break from the world: a player who returns after a month would find their terminal
 * had forgotten them, although they did nothing. Use-based decay forgets only in proportion to what has happened
 * since.</li>
 * <li>It is exactly testable without a clock: record requests and the fade is reproducible.</li>
 * </ul>
 * Halving (rather than subtracting one) keeps the shape of a history while compressing it: a favourite at 256 stays
 * ahead at 128, while everything asked for once drops to zero and leaves the store — which also hands the freed
 * capacity to whatever the player is doing now.
 *
 * @param <K> item key type; needs value equality (the content layer uses {@code ItemKey})
 */
public final class TerminalUsage<K> implements TerminalUsageCounts<K> {
    /** The most item types one store may ever remember, whatever a configuration asks for. */
    public static final int MAX_CAPACITY = 256;

    /** How many item types a store remembers unless it is told another number. */
    public static final int DEFAULT_CAPACITY = 64;

    /** The count at which every count is halved (the fade). Also the largest count a store ever holds. */
    public static final int MAX_COUNT = 256;

    /** The largest recency stamp; the stamps are renumbered from 1 before they could pass it. */
    public static final int MAX_STAMP = 1 << 16;

    /**
     * A {@link LinkedHashMap} so that no iteration here can depend on hash order; the recency stamps are unique, so
     * eviction and {@link #entries()} are total orders and never need that order as a tie-break. Keys are never
     * {@code null}.
     */
    private final Map<K, Slot> slots = new LinkedHashMap<>();

    private final int capacity;

    /** How many requests have been recorded since the last renumbering; the recency stamp of the next one. */
    private int sequence;

    /** A store of {@link #DEFAULT_CAPACITY} item types. */
    public TerminalUsage() {
        this(DEFAULT_CAPACITY);
    }

    /** A store of {@code capacity} item types, clamped into {@code [1, }{@link #MAX_CAPACITY}{@code ]}. */
    public TerminalUsage(int capacity) {
        this.capacity = Math.max(1, Math.min(capacity, MAX_CAPACITY));
    }

    /**
     * The store {@code entries} describe, large enough to hold all of them ({@link #MAX_CAPACITY}).
     * <p>
     * This is the <b>client</b> side of the model: it reads what the server sent and never counts anything, so it must
     * not drop entries just because its own default capacity is smaller than the server's configured one.
     */
    public static <K> TerminalUsage<K> ofEntries(Collection<? extends Entry<K>> entries) {
        TerminalUsage<K> usage = new TerminalUsage<>(MAX_CAPACITY);
        usage.replaceAll(entries);
        return usage;
    }

    /** How many item types this store remembers at most. */
    public int capacity() {
        return capacity;
    }

    /** How many item types it remembers right now. */
    public int size() {
        return slots.size();
    }

    /** Whether this player has no history at all (then "most used" is the amount order, see {@link TerminalSort}). */
    public boolean isEmpty() {
        return slots.isEmpty();
    }

    @Override
    public long countFor(K key) {
        if (key == null)
            return 0L;
        Slot slot = slots.get(key);
        return slot == null ? 0L : slot.count;
    }

    /**
     * Counts one accepted request for {@code key} (see the class comment for what counts as one).
     *
     * @return the count this item has afterwards, which is lower than before when this call triggered the fade
     */
    public int record(K key) {
        Objects.requireNonNull(key, "key");
        Slot slot = slots.get(key);
        if (slot == null) {
            if (slots.size() >= capacity)
                evictWeakest(Set.of());
            slot = new Slot();
            slots.put(key, slot);
        }
        slot.count = Math.min(MAX_COUNT, slot.count + 1);
        slot.stamp = ++sequence;
        if (slot.count >= MAX_COUNT)
            fade();
        else if (sequence >= MAX_STAMP)
            renumber();
        return slot.count;
    }

    /**
     * Counts <b>one</b> player action that named several item types — a clipboard order — once per item type, in a way
     * that cannot make the action fight itself (M24 review fix; see the class comment).
     * <p>
     * Two rules, and both are needed:
     * <ul>
     * <li><b>Item types the store already knows are raised first.</b> Otherwise the new keys of the same list could
     * evict them before they were ever counted, and a list ordered a second time would raise nothing at all: every one
     * of its keys would have been displaced by a later key of its own first run, so the whole list would sit at count 1
     * for ever. Ordering the same list again is the most repeated action a building player has, so a counter that
     * cannot see it is not a counter of what a player uses.</li>
     * <li><b>Nothing this call counted is evicted by this call.</b> A new entry is the weakest entry there is, so
     * without this the keys a list named first would be dropped by the keys it named last — and a list with more new
     * types than the store is large would end up holding only its tail. With it, a list that does not fit keeps its
     * head and the remainder is simply not counted (the return value says how many were).</li>
     * </ul>
     * Nothing else changes: the bounds, the eviction order among <i>older</i> entries and the fade are exactly
     * {@link #record}'s, and a one-element collection does exactly what {@code record} does. A {@code null} key is
     * skipped rather than rejected, because the caller reads its keys out of an item a player wrote.
     *
     * @return how many item types were counted, which is the number of distinct non-null keys unless the store had no
     *         room left for the last of them
     */
    public int recordAll(Collection<? extends K> keys) {
        Objects.requireNonNull(keys, "keys");
        // Once per item type: a list that names cobblestone on three pages is one thing the player asked for. The
        // insertion order is the list's own, which is what makes the cut below keep what the player wrote first.
        Set<K> action = new LinkedHashSet<>();
        for (K key : keys) {
            if (key != null)
                action.add(key);
        }
        List<K> fresh = new ArrayList<>(action.size());
        int counted = 0;
        for (K key : action) {
            if (slots.containsKey(key)) {
                record(key);
                counted++;
            } else {
                fresh.add(key);
            }
        }
        for (K key : fresh) {
            // A full store gives up its weakest *older* entry; when every entry is one this very action counted, the
            // rest of the list is not counted instead of undoing the start of it.
            if (slots.size() >= capacity && !evictWeakest(action))
                break;
            record(key);
            counted++;
        }
        return counted;
    }

    /** Forgets everything (a player asked for it, or the world's data was reset). */
    public void clear() {
        slots.clear();
        sequence = 0;
    }

    /**
     * What this store remembers, <b>strongest first</b>: the highest count, and among equal counts the most recently
     * requested. Unmodifiable.
     * <p>
     * The order is the one to save in: save data cut short by anything keeps the entries that matter most, and the
     * list a player would recognise is written first.
     */
    public List<Entry<K>> entries() {
        List<Entry<K>> list = new ArrayList<>(slots.size());
        for (Map.Entry<K, Slot> entry : slots.entrySet())
            list.add(new Entry<>(entry.getKey(), entry.getValue().count, entry.getValue().stamp));
        list.sort(strongestFirst());
        return List.copyOf(list);
    }

    /**
     * Replaces everything with {@code saved} (a world was loaded, or the server sent a client its counts).
     * <p>
     * Every bound is re-applied here, because save data can be crafted or can come from a build with a larger cap:
     * entries without a count are dropped, a key that appears twice keeps its strongest entry, counts are clamped to
     * {@link #MAX_COUNT}, the strongest {@link #capacity()} entries are kept and the stamps are renumbered from 1 so
     * that no two entries can share one. Nothing is rejected and nothing throws.
     */
    public void replaceAll(Collection<? extends Entry<K>> saved) {
        Objects.requireNonNull(saved, "saved");
        slots.clear();
        sequence = 0;
        Map<K, Entry<K>> unique = new LinkedHashMap<>();
        for (Entry<K> entry : saved) {
            if (entry == null || entry.count() <= 0)
                continue;
            unique.merge(entry.key(), entry, (first, second) -> new Entry<>(first.key(),
                    Math.max(first.count(), second.count()), Math.max(first.stamp(), second.stamp())));
        }
        List<Entry<K>> kept = new ArrayList<>(unique.values());
        // Strongest first, then cut to the cap: a lowered cap must keep what the player uses, not what comes first.
        kept.sort(strongestFirst());
        if (kept.size() > capacity)
            kept = new ArrayList<>(kept.subList(0, capacity));
        // Insert in stamp order, so a restore starts from the recency order it was saved in; a stable sort keeps
        // entries whose saved stamps are equal (a client payload has none) in the strongest-first order above.
        kept.sort(Comparator.comparingInt((Entry<K> entry) -> entry.stamp()));
        for (Entry<K> entry : kept) {
            Slot slot = new Slot();
            slot.count = entry.count();
            slot.stamp = ++sequence;
            slots.put(entry.key(), slot);
        }
    }

    /**
     * Drops the lowest count, among equal counts the one requested longest ago (see the class comment).
     *
     * @param keep keys this eviction may not touch, i.e. the item types of the action that is running
     *             ({@link #recordAll}); empty for a single {@link #record}
     * @return whether an entry was dropped, i.e. whether there is room now
     */
    private boolean evictWeakest(Set<K> keep) {
        K weakest = null;
        int count = Integer.MAX_VALUE;
        int stamp = Integer.MAX_VALUE;
        for (Map.Entry<K, Slot> entry : slots.entrySet()) {
            if (keep.contains(entry.getKey()))
                continue;
            Slot slot = entry.getValue();
            if (slot.count < count || (slot.count == count && slot.stamp < stamp)) {
                weakest = entry.getKey();
                count = slot.count;
                stamp = slot.stamp;
            }
        }
        if (weakest == null)
            return false;
        slots.remove(weakest);
        return true;
    }

    /** Halves every count and forgets what falls to zero (the fade; see the class comment). */
    private void fade() {
        Iterator<Map.Entry<K, Slot>> iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Slot slot = iterator.next().getValue();
            slot.count /= 2;
            if (slot.count <= 0)
                iterator.remove();
        }
        renumber();
    }

    /** Renumbers the stamps to {@code 1..size}, keeping their order, so every stamp stays small and unique. */
    private void renumber() {
        List<Slot> ordered = new ArrayList<>(slots.values());
        ordered.sort(Comparator.comparingInt(slot -> slot.stamp));
        int stamp = 0;
        for (Slot slot : ordered)
            slot.stamp = ++stamp;
        sequence = stamp;
    }

    private static <K> Comparator<Entry<K>> strongestFirst() {
        return Comparator.<Entry<K>>comparingInt(entry -> -entry.count())
                .thenComparingInt(entry -> -entry.stamp());
    }

    /** The mutable half of one remembered item type; never handed out. */
    private static final class Slot {
        private int count;
        private int stamp;
    }

    /**
     * One remembered item type, as it is saved and sent.
     * <p>
     * A client that only reads counts can use {@link #Entry(Object, int)} and ignore the stamp; a <b>save</b> keeps it,
     * or every stamp would be equal after a world load and eviction would have nothing left to break ties with.
     *
     * @param count how often the player asked for it, in {@code [0, }{@link TerminalUsage#MAX_COUNT}{@code ]}; 0 means
     *              "nothing remembered" and is dropped by {@link TerminalUsage#replaceAll(Collection)}
     * @param stamp when it was last asked for, as a count of recorded requests
     *              ({@code [0, }{@link TerminalUsage#MAX_STAMP}{@code ]})
     */
    public record Entry<K>(K key, int count, int stamp) {
        public Entry {
            Objects.requireNonNull(key, "key");
            count = Math.max(0, Math.min(count, MAX_COUNT));
            stamp = Math.max(0, Math.min(stamp, MAX_STAMP));
        }

        /** An entry whose recency is unknown, which is all a client needs to sort. */
        public Entry(K key, int count) {
            this(key, count, 0);
        }
    }
}
