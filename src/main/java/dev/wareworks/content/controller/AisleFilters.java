package dev.wareworks.content.controller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.logistics.filter.FilterItemStack.AttributeFilterItemStack;
import com.simibubi.create.content.logistics.filter.FilterItemStack.ListFilterItemStack;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.job.FilterMatch;
import dev.wareworks.util.LogThrottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The <b>store settings</b> of one aisle's storage locations — the store filter (M8, ADR-021) and the storage priority
 * (M16, ADR-028) — cached by rack position ({@code docs/warehouse-system.md} §3.1). Owned by one
 * {@link WarehouseControllerBlockEntity}; server thread only.
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

    /**
     * The store settings of one storage location as its interface reports them, for the one-shot resolve of an unread
     * rack: both in one block entity lookup, so the planner can never plan with a priority this cache has not read.
     *
     * @param filter   the filter stack (a copy the cache may trim), empty for "accepts everything"
     * @param priority the storage priority, {@value #NO_PRIORITY} for "no preference"
     */
    record StoreSettings(ItemStack filter, int priority) {
        StoreSettings {
            Objects.requireNonNull(filter, "filter");
        }
    }

    /**
     * A cached store filter and storage priority.
     *
     * @param source    the untrimmed stack the filter was built from, to skip rebuilding an unchanged filter.
     *                  {@link FilterItemStack#of} trims enchantments and attribute modifiers <b>in place</b>, and a
     *                  removal is recorded in the component patch, so the wrapper's own stack never compares equal to
     *                  the stack the behaviour hands out
     * @param filter    the resolved Create filter, or {@code null} for a location that carries none (an entry with no
     *                  filter exists only because it carries a priority)
     * @param selecting whether the filter <b>selects</b> the items it accepts (an allow list) rather than excluding
     *                  others
     * @param priority  the storage priority, {@value #NO_PRIORITY} for "no preference"
     */
    private record Entry(ItemStack source, @Nullable FilterItemStack filter, boolean selecting, int priority) {
        Entry withPriority(int priority) {
            return new Entry(source, filter, selecting, priority);
        }
    }

    /**
     * Stores the store settings of the storage location at {@code rack}, and marks that location read. An empty filter
     * together with {@value #NO_PRIORITY} removes the entry, so the location accepts everything with no preference again.
     * <p>
     * {@code filter} must be a copy the caller does not keep: {@link FilterItemStack#of} trims components of a Create
     * filter item in place ({@code StorageMember#storeFilter()} therefore returns copies). An unchanged filter is not
     * rebuilt: resolving a Create list filter allocates a slot handler and a nested wrapper per entry, and this runs on
     * every refresh path while a filter only ever changes when a player clicks it — a changed <b>priority</b> alone
     * therefore keeps the resolved filter and only replaces the number.
     */
    void set(RackPosition rack, ItemStack filter, int priority) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(filter, "filter");
        unread.remove(rack);
        if (filter.isEmpty() && priority == NO_PRIORITY) {
            settings.remove(rack);
            return;
        }
        Entry cached = settings.get(rack);
        if (cached != null && ItemStack.isSameItemSameComponents(cached.source(), filter)) {
            if (cached.priority() != priority)
                settings.put(rack, cached.withPriority(priority));
            return;
        }
        if (filter.isEmpty()) {
            settings.put(rack, new Entry(ItemStack.EMPTY, null, false, priority));
            return;
        }
        ItemStack source = filter.copy(); // taken before of() trims the stack in place
        FilterItemStack resolved = FilterItemStack.of(filter);
        settings.put(rack, new Entry(source, resolved, selects(resolved), priority));
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
     *
     * @param resolver reads the store settings of a rack position straight from its interface; empty while they cannot
     *                 be read
     */
    FilterMatch match(Level level, RackPosition rack, ItemKey key,
            Function<RackPosition, Optional<StoreSettings>> resolver) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(resolver, "resolver");
        Entry entry = entryFor(rack, resolver);
        if (entry == null)
            // Resolved and it really carries nothing, or still unread because it could not be resolved at all.
            return unread.contains(rack) ? FilterMatch.REJECTED : FilterMatch.UNFILTERED;
        FilterItemStack filter = entry.filter();
        if (filter == null)
            return FilterMatch.UNFILTERED; // an entry that exists only for its priority
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
        set(rack, resolved.get().filter(), resolved.get().priority());
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
