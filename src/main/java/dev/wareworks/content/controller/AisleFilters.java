package dev.wareworks.content.controller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.logistics.filter.FilterItemStack.AttributeFilterItemStack;
import com.simibubi.create.content.logistics.filter.FilterItemStack.ListFilterItemStack;

import dev.wareworks.Wareworks;
import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.content.fluid.FluidDedication;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.job.FilterMatch;
import dev.wareworks.util.LogThrottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The <b>store settings</b> of one aisle's storage locations — the store filter (M8, ADR-021), the storage priority
 * (M16, ADR-028) and the two store rules a location itself reports (M28) — cached by rack position
 * ({@code docs/warehouse-system.md} §3.1). Owned by one {@link WarehouseControllerBlockEntity}; server thread only.
 * <p>
 * <b>Why a cache.</b> {@link dev.wareworks.core.job.JobPlanner} asks about every storage candidate of a planning run —
 * up to 2 · (L+1) · H of them, every {@code dispatchIntervalTicks} — and resolving a block entity per candidate would be
 * a world lookup per candidate per run. The settings are therefore read where the controller already touches a location
 * ({@code refreshLocation}: joins, content hints, the round robin, after every transfer and the load verification) and
 * when an interface reports that a player changed one of them ({@link WarehouseRegistry#filterChanged}). A lookup during
 * planning is then one hash map read, and a location with neither a filter nor a priority costs nothing beyond that.
 * <p>
 * <b>"Not read yet" is not "no filter"</b> (M8 review fix, the reason this class has {@link #markUnread}). Nothing here
 * is persisted — filter and priority live in the interface's own block entity data (Create's {@code FilteringBehaviour})
 * — so after a world or chunk load the map starts empty while the locations are still queued for their background
 * snapshots, which are drained at only {@code maxSnapshotsPerTick} per tick. Planning already runs in that window. A
 * plain cache miss would answer {@link FilterMatch#UNFILTERED}, i.e. "accepts everything", and the planner would store
 * into chests a player dedicated to something else — permanently, because ADR-021 never re-shuffles. Restored and
 * joining locations are therefore marked <b>unread</b>, and the first {@link #match} or {@link #priorityOf} for such a
 * rack resolves its interface once through the caller's resolver — reading filter <b>and</b> priority in that one
 * lookup, so the answer is right whichever of the two the planner asks for first. That is one block entity lookup per
 * location ever, not one per candidate per run, so the performance rule still holds.
 * <p>
 * <b>An unread priority is not dangerous, and the asymmetry is the point.</b> An unread filter defaulting to "no filter"
 * was permanently wrong: items entered a chest the player had forbidden and nothing is ever re-shuffled, which is why an
 * unresolvable filter counts as {@link FilterMatch#REJECTED}. A priority has no forbidding answer — an unread priority
 * defaulting to 0 only means the warehouse stores in the old travel-time order into a <b>permitted</b> location, just
 * not the preferred one. The dangerous default would be "assume high", which is never taken.
 * <p>
 * <b>Selecting and excluding filters.</b> Create's list and attribute filters have a deny mode, where {@code test}
 * answers true for everything the filter does <i>not</i> list. Such a location accepts the item but was not dedicated
 * to it, so it is {@link FilterMatch#ALLOWED} rather than {@link FilterMatch#DEDICATED} and gets no ranking bonus (M8
 * review fix; before, one deny-list chest outranked consolidation and item-type grouping for every item in the
 * warehouse).
 * <p>
 * <b>Two questions that are not filters.</b> Besides the filter and the priority a player sets, a storage location is
 * asked whether it holds {@linkplain StorageMember#holdsOneTypeOnly() one item type only} and whether it
 * {@linkplain StorageMember#acceptsStoring() accepts storing} at all (M28). They are cached here because they ride the
 * same refresh paths and have to be known in the same one lookup, and they are answered in {@link #match} <b>before</b>
 * the Create filter is evaluated: both are cheaper than walking a filter's rules and both are stricter, so a location
 * neither of them allows costs no filter evaluation, no capacity estimate and no live simulation.
 * <p>
 * <b>A third question that is a filter, of a different kind</b> (M30 step 9, issue #21, D6). A fluid bay answers
 * {@linkplain StorageMember#storeFluidFilter() which fluid it takes filled containers of}, and that answer replaces the
 * item filter rather than joining it: such a location's filter slot holds a <b>container</b> whose only meaning is the
 * fluid inside it, so the slot is never resolved into a Create {@code FilterItemStack} at all — a filter built from a
 * water bucket matches the <i>item</i> {@code water_bucket} and would route containers where fluid was meant. It is
 * decided in {@link #match} directly after the two M28 rules and returns there, and it is the one answer that can make
 * a location {@link FilterMatch#DEDICATED} for an item nobody filtered for.
 * <p>
 * <b>A location that answers any of them is never neutral, and that is the one cost worth naming.</b> An entry is
 * dropped only for a location that carries no filter, no priority, takes any item type, accepts storing and stores
 * items rather than fluid, which is
 * what makes a wall of plain warehouse interfaces cost nothing here beyond the map itself. A rack bay holds one item type by construction, so it always keeps an entry: a
 * warehouse of 1 056 bays holds 1 056 small entries where a warehouse of 1 056 interfaces holds none. That is tens of
 * kilobytes beside a stock index that already keeps a snapshot per location, and it is bounded by the number of rack
 * positions, so it is paid for knowingly rather than discovered later.
 * <p>
 * <b>Shared inventories.</b> Entries are keyed by rack position, and the planner only ever asks about the location that
 * counts an inventory ({@code sharedInventoryOf}), so for a double chest or an item vault read by several interfaces the
 * settings of that counting location are the ones that apply (§3.1.1, known limitation). The goggle accessors therefore
 * take a predicate that excludes aliases, so the controller never counts a filter or a priority that cannot do anything.
 */
final class AisleFilters {
    /** The priority of a location nobody prioritised, and the answer for a location that is not cached here. */
    static final int NO_PRIORITY = 0;

    private final Map<RackPosition, Entry> settings = new HashMap<>();
    /**
     * Storage locations whose store settings have never been read into this cache (restored from a save, or freshly
     * joined). A {@link #match} for one of them resolves it instead of assuming "no filter".
     */
    private final Set<RackPosition> unread = new HashSet<>();
    /** Rate limit for a filter that throws while being evaluated (a modded item attribute); a second one stays loggable. */
    private final LogThrottle failures = new LogThrottle();

    /** The key the cached probe stack belongs to; {@code null} before the first {@link #match}. */
    @Nullable
    private ItemKey probeKey;
    /** One probe stack per key instead of one per candidate: {@code FilterItemStack#test} never mutates it. */
    private ItemStack probeStack = ItemStack.EMPTY;
    /** The key {@link #carried} was read from; {@code null} before the first fluid question (M30 step 9). */
    @Nullable
    private ItemKey carriedKey;
    /** What one item of {@link #carriedKey} carries, memoised exactly as {@link #probeStack} is; see {@link #carried}. */
    private Optional<FluidKey> carried = Optional.empty();

    /**
     * The store settings of one storage location as its member reports them, for the one-shot resolve of an unread
     * rack: all of them in one block entity lookup, so the planner can never plan with a setting this cache has not
     * read.
     *
     * @param filter           the filter stack (a copy the cache may trim), empty for "accepts everything"
     * @param priority         the storage priority, {@value #NO_PRIORITY} for "no preference"
     * @param holdsOneTypeOnly whether the location takes at most one item type at a time
     *                         ({@link StorageMember#holdsOneTypeOnly()})
     * @param acceptsStoring   whether the location may be stored into at all
     *                         ({@link StorageMember#acceptsStoring()})
     * @param acceptsFluid     which fluid the location takes filled containers of, empty for a location that stores
     *                         items ({@link StorageMember#storeFluidFilter()}, M30 step 9)
     */
    record StoreSettings(ItemStack filter, int priority, boolean holdsOneTypeOnly, boolean acceptsStoring,
            Optional<FluidDedication> acceptsFluid) {
        StoreSettings {
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(acceptsFluid, "acceptsFluid");
        }
    }

    /**
     * Everything a storage member says about storing, read in <b>one</b> block entity lookup — the shape every caller
     * of {@link #set} and the one-shot resolver both use, so a setting added later cannot be read on one path and
     * forgotten on another.
     */
    static StoreSettings read(StorageMember member) {
        Objects.requireNonNull(member, "member");
        return new StoreSettings(member.storeFilter(), member.storePriority(), member.holdsOneTypeOnly(),
                member.acceptsStoring(), member.storeFluidFilter());
    }

    /**
     * A cached store filter and storage priority.
     *
     * @param source    the untrimmed stack the filter was built from, to skip rebuilding an unchanged filter.
     *                  {@link FilterItemStack#of} trims enchantments and attribute modifiers <b>in place</b>, and a
     *                  removal is recorded in the component patch, so the wrapper's own stack never compares equal to
     *                  the stack the behaviour hands out. For a <b>fluid</b> location it is the slot's stack as it
     *                  stands, because nothing is built from it (see {@link #acceptsFluid})
     * @param filter    the resolved Create filter, or {@code null} for a location that carries none (an entry with no
     *                  filter exists only because it carries a priority, a store rule or a fluid dedication)
     * @param selecting whether the filter <b>selects</b> the items it accepts (an allow list) rather than excluding
     *                  others
     * @param priority  the storage priority, {@value #NO_PRIORITY} for "no preference"
     * @param holdsOneTypeOnly whether the location takes at most one item type at a time (M28); an entry exists for a
     *                         location that carries nothing but this
     * @param acceptsStoring whether the location may be stored into at all (M28)
     * @param acceptsFluid which fluid this location takes filled containers of, or {@code null} for a location that
     *                     stores items (M30 step 9). A location that answers it never has a resolved {@link #filter}:
     *                     its slot holds a container and is read as the fluid inside, so nothing here may ever evaluate
     *                     it as a Create item filter
     */
    private record Entry(ItemStack source, @Nullable FilterItemStack filter, boolean selecting, int priority,
            boolean holdsOneTypeOnly, boolean acceptsStoring, @Nullable FluidDedication acceptsFluid) {
        /** The same resolved filter with new store rules: a changed priority or flag never rebuilds the filter. */
        Entry withStoreRules(int priority, boolean holdsOneTypeOnly, boolean acceptsStoring,
                @Nullable FluidDedication acceptsFluid) {
            return new Entry(source, filter, selecting, priority, holdsOneTypeOnly, acceptsStoring, acceptsFluid);
        }
    }

    /**
     * Stores the store settings of the storage location at {@code rack}, and marks that location read. A location that
     * says <b>nothing</b> — no filter, {@value #NO_PRIORITY}, takes any item type and accepts storing — keeps no entry,
     * so it accepts everything with no preference again and costs nothing during planning. Any one of the four keeps
     * one, which is why a rack bay always has an entry (see the class comment).
     * <p>
     * {@code filter} must be a copy the caller does not keep: {@link FilterItemStack#of} trims components of a Create
     * filter item in place ({@link StorageMember#storeFilter()} therefore returns copies). An unchanged filter is not
     * rebuilt: resolving a Create list filter allocates a slot handler and a nested wrapper per entry, and this runs on
     * every refresh path while a filter only ever changes when a player clicks it — a changed <b>priority</b> or a
     * changed store rule therefore keeps the resolved filter and only replaces the numbers beside it. A location that
     * changed from a fluid location into an item one, or back, is always rebuilt: the two keep the stack in that slot
     * for different purposes, so an unchanged stack is not an unchanged rule there.
     * <p>
     * A <b>fluid</b> location's filter is never resolved at all (M30 step 9, D6): its slot holds a container whose only
     * meaning is the fluid inside it, so building a Create {@code FilterItemStack} from it would be both wasted work
     * and a trap — the one thing that must never happen is that lava buckets get routed where lava was meant. That
     * also keeps such a location out of {@link #filteredCount}, which counts item partitions.
     */
    void set(RackPosition rack, StoreSettings read) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(read, "read");
        ItemStack filter = read.filter();
        int priority = read.priority();
        boolean holdsOneTypeOnly = read.holdsOneTypeOnly();
        boolean acceptsStoring = read.acceptsStoring();
        FluidDedication acceptsFluid = read.acceptsFluid().orElse(null);
        unread.remove(rack);
        if (filter.isEmpty() && priority == NO_PRIORITY && !holdsOneTypeOnly && acceptsStoring
                && acceptsFluid == null) {
            settings.remove(rack);
            return;
        }
        Entry cached = settings.get(rack);
        // The fast path keeps the resolved filter, so it may only be taken while the location is still the same KIND
        // of location. A fluid bay broken and replaced by a warehouse interface carrying the very same filter item
        // compares equal here, and its entry carries no resolved filter at all — so without this test that interface's
        // item filter would read as "accepts everything" and the warehouse would store anything into a chest the
        // player dedicated to lava buckets. The opposite replacement would leave a resolved item filter on a bay and
        // count it as an item partition in the goggle summary.
        if (cached != null && (cached.acceptsFluid() != null) == (acceptsFluid != null)
                && ItemStack.isSameItemSameComponents(cached.source(), filter)) {
            if (cached.priority() != priority || cached.holdsOneTypeOnly() != holdsOneTypeOnly
                    || cached.acceptsStoring() != acceptsStoring
                    || !Objects.equals(cached.acceptsFluid(), acceptsFluid))
                settings.put(rack,
                        cached.withStoreRules(priority, holdsOneTypeOnly, acceptsStoring, acceptsFluid));
            return;
        }
        if (filter.isEmpty() || acceptsFluid != null) {
            settings.put(rack, new Entry(filter.isEmpty() ? ItemStack.EMPTY : filter.copy(), null, false, priority,
                    holdsOneTypeOnly, acceptsStoring, acceptsFluid));
            return;
        }
        ItemStack source = filter.copy(); // taken before of() trims the stack in place
        FilterItemStack resolved = FilterItemStack.of(filter);
        settings.put(rack,
                new Entry(source, resolved, selects(resolved), priority, holdsOneTypeOnly, acceptsStoring, null));
    }

    /**
     * The storage location at {@code rack} exists but its store settings are unknown here (restored from a save, or it
     * just joined the aisle): the next {@link #match} or {@link #priorityOf} resolves them instead of treating the
     * location as unfiltered.
     */
    void markUnread(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (!settings.containsKey(rack))
            unread.add(rack);
    }

    /** The storage location at {@code rack} left the aisle (or is no longer a storage location). */
    void remove(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        settings.remove(rack);
        unread.remove(rack);
    }

    /** The aisle is gone or was replaced. */
    void clear() {
        settings.clear();
        unread.clear();
        probeKey = null;
        probeStack = ItemStack.EMPTY;
        carriedKey = null;
        carried = Optional.empty();
    }

    /**
     * Storage locations of this aisle that carry a filter <b>that applies</b>, for the controller's goggle summary.
     *
     * @param counted excludes locations the planner never asks about (shared-inventory aliases), so the count never
     *                promises a partition that cannot happen
     */
    int filteredCount(Predicate<RackPosition> counted) {
        return count(counted, entry -> entry.filter() != null);
    }

    /** Storage locations of this aisle that carry a storage priority <b>that applies</b> (M16); see {@link #filteredCount}. */
    int prioritisedCount(Predicate<RackPosition> counted) {
        return count(counted, entry -> entry.priority() != NO_PRIORITY);
    }

    private int count(Predicate<RackPosition> counted, Predicate<Entry> matching) {
        Objects.requireNonNull(counted, "counted");
        int count = 0;
        for (Map.Entry<RackPosition, Entry> cached : settings.entrySet()) {
            if (matching.test(cached.getValue()) && counted.test(cached.getKey()))
                count++;
        }
        return count;
    }

    /** Whether a filter is cached for the storage location at {@code rack} (alias-blind; see {@link #filteredCount}). */
    boolean isFiltered(RackPosition rack) {
        Entry entry = settings.get(Objects.requireNonNull(rack, "rack"));
        return entry != null && entry.filter() != null;
    }

    /** Whether a storage priority is cached for the storage location at {@code rack} (alias-blind, M16). */
    boolean isPrioritised(RackPosition rack) {
        Entry entry = settings.get(Objects.requireNonNull(rack, "rack"));
        return entry != null && entry.priority() != NO_PRIORITY;
    }

    /**
     * What the filter of the storage location at {@code rack} says about {@code key}, for
     * {@code PlannerInput#storeFilter}. A location known to carry no filter is {@link FilterMatch#UNFILTERED}.
     * <p>
     * A location whose settings were never read here ({@link #markUnread}) is resolved through {@code resolver} once and
     * then cached; while it cannot be resolved (its block entity is gone or its chunk is not loaded) the answer is
     * {@link FilterMatch#REJECTED}, because storing into a location whose rule cannot be checked is exactly the
     * misplacement this cache exists to prevent, and the planner has other candidates.
     * <p>
     * A filter that throws while being evaluated (a modded item attribute) counts as {@link FilterMatch#REJECTED}
     * rather than as "accepts everything": a broken filter must not silently deliver items into a location the player
     * set aside for something else. The failure is logged at most once per {@link LogThrottle} interval.
     * <p>
     * The two store rules of M28 decide <b>first</b>, before the filter is evaluated: a location that
     * {@linkplain StorageMember#acceptsStoring() accepts no storing} refuses every key, and one that
     * {@linkplain StorageMember#holdsOneTypeOnly() holds one item type only} refuses every key it is not already
     * committed to. Both are cheaper than walking a filter's rules and both are stricter, so the order costs nothing and
     * saves the filter evaluation. For every location that answers neither — every warehouse interface — this is two
     * field reads on an entry that was looked up anyway.
     * <p>
     * A location that takes <b>filled containers</b> (a fluid bay, M30 step 9, D6) decides directly after them and
     * <b>returns</b>: a container of its fluid is {@link FilterMatch#DEDICATED}, so a bay outranks every shelf for it,
     * and everything else is {@link FilterMatch#REJECTED} — an empty container (which closes the churn loop: the
     * warehouse never carries the empties it just produced back to the bay), a container of another fluid, and every
     * ordinary item. The Create filter is <b>never</b> reached for such a location, which is the whole point: the slot
     * holds a container and its only meaning is the fluid inside it.
     *
     * @param resolver    reads the store settings of a rack position straight from its interface; empty while they
     *                    cannot be read
     * @param committedTo whether a one-type location holds, and awaits, nothing but {@code key}; consulted only for a
     *                    location that holds one item type only, so a warehouse without one never calls it
     */
    FilterMatch match(Level level, RackPosition rack, ItemKey key,
            Function<RackPosition, Optional<StoreSettings>> resolver,
            BiPredicate<RackPosition, ItemKey> committedTo) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(committedTo, "committedTo");
        Entry entry = entryFor(rack, resolver);
        if (entry == null)
            // Resolved and it really carries nothing, or still unread because it could not be resolved at all.
            return unread.contains(rack) ? FilterMatch.REJECTED : FilterMatch.UNFILTERED;
        if (!entry.acceptsStoring())
            return FilterMatch.REJECTED;
        if (entry.holdsOneTypeOnly() && !committedTo.test(rack, key))
            return FilterMatch.REJECTED;
        FluidDedication acceptsFluid = entry.acceptsFluid();
        if (acceptsFluid != null) {
            FluidKey arriving = carried(key).orElse(null);
            return arriving != null && acceptsFluid.accepts(arriving) ? FilterMatch.DEDICATED : FilterMatch.REJECTED;
        }
        FilterItemStack filter = entry.filter();
        if (filter == null)
            return FilterMatch.UNFILTERED; // an entry that exists only for its priority or a store rule
        try {
            if (!filter.test(level, probe(key)))
                return FilterMatch.REJECTED;
            return entry.selecting() ? FilterMatch.DEDICATED : FilterMatch.ALLOWED;
        } catch (RuntimeException e) {
            if (failures.tryLog(level.getGameTime()))
                Wareworks.LOGGER.warn("Storage filter at {} could not be evaluated for {}", rack, key, e);
            return FilterMatch.REJECTED;
        }
    }

    /**
     * Whether the storage location at {@code rack} takes <b>filled containers</b> rather than items — a fluid bay
     * (M30 step 9, D6) — resolving an unread location once, exactly as {@link #match} and {@link #priorityOf} do.
     * <p>
     * It is asked by the controller's insert estimate and by its reroute rule
     * ({@code PlannerInput#allOrNothing}) and by nothing else. A fluid bay's inventory snapshot has
     * <b>zero slots</b>, so the snapshot estimate answers 0 for it and {@code JobPlanner}'s capacity gate would drop
     * it before any live call — which is exactly what must keep happening for a warehouse interface whose chest was
     * taken away, and exactly what must <b>not</b> happen for a bay that holds fluid and no items by design. The two
     * report the same empty snapshot for opposite reasons, so the estimate asks this positive question instead of
     * reading anything into an empty snapshot.
     * <p>
     * "Unknown, ask the live inventory" is the honest answer for such a location: how many containers it takes is
     * {@code TransferContexts}' {@code simulateInsert}, which measures the container and the room in one call.
     */
    boolean takesFluidContainers(RackPosition rack, Function<RackPosition, Optional<StoreSettings>> resolver) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(resolver, "resolver");
        Entry entry = entryFor(rack, resolver);
        return entry != null && entry.acceptsFluid() != null;
    }

    /**
     * The storage priority of the storage location at {@code rack}, for {@code PlannerInput#storePriority} (M16).
     * {@value #NO_PRIORITY} for a location that carries none — and for one that cannot be resolved at all, which only
     * costs the old travel-time order for a few hundred ticks after a load and can never store into a forbidden place
     * (see the class comment).
     *
     * @param resolver as in {@link #match}: the same one-shot resolve, so the first plan after a load already knows the
     *                 priorities of the locations it ranks
     */
    int priorityOf(RackPosition rack, Function<RackPosition, Optional<StoreSettings>> resolver) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(resolver, "resolver");
        Entry entry = entryFor(rack, resolver);
        return entry == null ? NO_PRIORITY : entry.priority();
    }

    /**
     * The cached entry of {@code rack}, resolving an unread location once (filter and priority together) and caching the
     * answer. {@code null} means "carries nothing" or "still unread"; {@link #unread} tells the two apart.
     */
    @Nullable
    private Entry entryFor(RackPosition rack, Function<RackPosition, Optional<StoreSettings>> resolver) {
        Entry entry = settings.get(rack);
        if (entry != null || !unread.contains(rack))
            return entry;
        Optional<StoreSettings> resolved = resolver.apply(rack);
        if (resolved.isEmpty())
            return null;
        // Also clears the unread mark, so this costs one block entity lookup per location ever.
        set(rack, resolved.get());
        return settings.get(rack);
    }

    /**
     * The stack handed to {@link FilterItemStack#test}, built once per key instead of once per candidate.
     * {@code test} treats it as read-only in every Create wrapper (the same assumption Create itself makes wherever it
     * passes a caller's stack), so one instance can serve every location of a planning run.
     */
    private ItemStack probe(ItemKey key) {
        if (!key.equals(probeKey)) {
            probeKey = key;
            probeStack = key.toStack();
        }
        return probeStack;
    }

    /**
     * Which fluid <b>one item</b> of {@code key} carries, empty for everything that is not a filled container — read
     * once per key instead of once per candidate, exactly as {@link #probe} is and for the same reason (M30 step 9).
     * <p>
     * It is {@code FluidContainers.drained} and not the cheaper {@code contents} read, because this is a <b>store
     * gate</b>: what it lets through is driven to a bay and exchanged there, and the exchange asks {@code drained}
     * (M30 review fix, issue #21). A container the lenient read accepts and a drain cannot honour — a per-call cap, a
     * multi-tank item, a consumable, a handler answering with two items — would otherwise be ranked
     * {@link FilterMatch#DEDICATED}, carried to the bay and refused on arrival, against the settled rule that a
     * container which does not fit is never sent in the first place.
     * <p>
     * The reading itself is not free either way: it builds a throw-away probe stack, resolves
     * {@code Capabilities.FluidHandler.ITEM} on it and drains it. A planning run asks about one key across every
     * storage candidate of an aisle, so memoising turns "a capability lookup per rack position" into one per key per
     * run — and a warehouse with no fluid bay never reaches this method at all, because only an entry with a fluid
     * dedication asks.
     */
    private Optional<FluidKey> carried(ItemKey key) {
        if (!key.equals(carriedKey)) {
            carriedKey = key;
            carried = FluidContainers.drained(key).map(FluidContainers.Drained::fluid);
        }
        return carried;
    }

    /**
     * Whether {@code filter} selects what it accepts. Create's deny modes accept everything they do <b>not</b> list
     * ({@code ListFilterItemStack#test} returns {@code isBlacklist} when nothing matched, and an attribute filter in
     * {@code BLACKLIST} mode does the same), which is not a dedication.
     */
    private static boolean selects(FilterItemStack filter) {
        if (filter instanceof ListFilterItemStack list)
            return !list.isBlacklist;
        if (filter instanceof AttributeFilterItemStack attributes)
            return attributes.whitelistMode != AttributeFilterWhitelistMode.BLACKLIST;
        return true; // plain item, package address, unconfigured filter item
    }
}
