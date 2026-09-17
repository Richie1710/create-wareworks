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
 * The store filters of one aisle's storage locations, cached by rack position ({@code docs/warehouse-system.md} §3.1,
 * ADR-021). Owned by one {@link WarehouseControllerBlockEntity}; server thread only.
 * <p>
 * <b>Why a cache.</b> {@link dev.wareworks.core.job.JobPlanner} asks about every storage candidate of a planning run —
 * up to 2 · (L+1) · H of them, every {@code dispatchIntervalTicks} — and resolving a block entity per candidate would be
 * a world lookup per candidate per run. The filters are therefore read where the controller already touches a location
 * ({@code refreshLocation}: joins, content hints, the round robin, after every transfer and the load verification) and
 * when an interface reports that a player changed its filter ({@link WarehouseRegistry#filterChanged}). A lookup during
 * planning is then one hash map read, and an unfiltered location costs nothing beyond that.
 * <p>
 * <b>"Not read yet" is not "no filter"</b> (M8 review fix, the reason this class has {@link #markUnread}). Nothing here
 * is persisted — the filter itself lives in the interface's own block entity data (Create's {@code FilteringBehaviour})
 * — so after a world or chunk load the map starts empty while the locations are still queued for their background
 * snapshots, which are drained at only {@code maxSnapshotsPerTick} per tick. Planning already runs in that window. A
 * plain cache miss would answer {@link FilterMatch#UNFILTERED}, i.e. "accepts everything", and the planner would store
 * into chests a player dedicated to something else — permanently, because ADR-021 never re-shuffles. Restored and
 * joining locations are therefore marked <b>unread</b>, and the first {@link #match} for such a rack resolves its
 * interface once through the caller's resolver and caches the answer. That is one block entity lookup per location
 * ever, not one per candidate per run, so the performance rule still holds.
 * <p>
 * <b>Selecting and excluding filters.</b> Create's list and attribute filters have a deny mode, where {@code test}
 * answers true for everything the filter does <i>not</i> list. Such a location accepts the item but was not dedicated
 * to it, so it is {@link FilterMatch#ALLOWED} rather than {@link FilterMatch#DEDICATED} and gets no ranking bonus (M8
 * review fix; before, one deny-list chest outranked consolidation and item-type grouping for every item in the
 * warehouse).
 * <p>
 * <b>Shared inventories.</b> Entries are keyed by rack position, and the planner only ever asks about the location that
 * counts an inventory ({@code sharedInventoryOf}), so for a double chest or an item vault read by several interfaces the
 * filter of that counting location is the one that applies (§3.1.1, known limitation). The goggle accessors therefore
 * take a predicate that excludes aliases, so the controller never counts a filter that cannot do anything.
 */
final class AisleFilters {
    private final Map<RackPosition, Entry> filters = new HashMap<>();
    /**
     * Storage locations whose filter has never been read into this cache (restored from a save, or freshly joined).
     * A {@link #match} for one of them resolves it instead of assuming "no filter".
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
     * A cached filter.
     *
     * @param source    the untrimmed stack it was built from, to skip rebuilding an unchanged filter.
     *                  {@link FilterItemStack#of} trims enchantments and attribute modifiers <b>in place</b>, and a
     *                  removal is recorded in the component patch, so the wrapper's own stack never compares equal to
     *                  the stack the behaviour hands out
     * @param filter    the resolved Create filter
     * @param selecting whether it <b>selects</b> the items it accepts (an allow list) rather than excluding others
     */
    private record Entry(ItemStack source, FilterItemStack filter, boolean selecting) {
    }

    /**
     * Stores the filter of the storage location at {@code rack}, and marks that location read. An empty stack removes
     * the entry, so the location accepts everything again.
     * <p>
     * {@code filter} must be a copy the caller does not keep: {@link FilterItemStack#of} trims components of a Create
     * filter item in place ({@code StorageMember#storeFilter()} therefore returns copies). An unchanged filter is not
     * rebuilt: resolving a Create list filter allocates a slot handler and a nested wrapper per entry, and this runs on
     * every refresh path while a filter only ever changes when a player clicks it.
     */
    void set(RackPosition rack, ItemStack filter) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(filter, "filter");
        unread.remove(rack);
        if (filter.isEmpty()) {
            filters.remove(rack);
            return;
        }
        Entry cached = filters.get(rack);
        if (cached != null && ItemStack.isSameItemSameComponents(cached.source(), filter))
            return;
        ItemStack source = filter.copy(); // taken before of() trims the stack in place
        FilterItemStack resolved = FilterItemStack.of(filter);
        filters.put(rack, new Entry(source, resolved, selects(resolved)));
    }

    /**
     * The storage location at {@code rack} exists but its filter is unknown here (restored from a save, or it just
     * joined the aisle): the next {@link #match} resolves it instead of treating it as unfiltered.
     */
    void markUnread(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (!filters.containsKey(rack))
            unread.add(rack);
    }

    /** The storage location at {@code rack} left the aisle (or is no longer a storage location). */
    void remove(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        filters.remove(rack);
        unread.remove(rack);
    }

    /** The aisle is gone or was replaced. */
    void clear() {
        filters.clear();
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
        Objects.requireNonNull(counted, "counted");
        int count = 0;
        for (RackPosition rack : filters.keySet()) {
            if (counted.test(rack))
                count++;
        }
        return count;
    }

    /** Whether a filter is cached for the storage location at {@code rack} (alias-blind; see {@link #filteredCount}). */
    boolean isFiltered(RackPosition rack) {
        return filters.containsKey(Objects.requireNonNull(rack, "rack"));
    }

    /**
     * What the filter of the storage location at {@code rack} says about {@code key}, for
     * {@code PlannerInput#storeFilter}. A location known to carry no filter is {@link FilterMatch#UNFILTERED}.
     * <p>
     * A location whose filter was never read here ({@link #markUnread}) is resolved through {@code resolver} once and
     * then cached; while it cannot be resolved (its block entity is gone or its chunk is not loaded) the answer is
     * {@link FilterMatch#REJECTED}, because storing into a location whose rule cannot be checked is exactly the
     * misplacement this cache exists to prevent, and the planner has other candidates.
     * <p>
     * A filter that throws while being evaluated (a modded item attribute) counts as {@link FilterMatch#REJECTED}
     * rather than as "accepts everything": a broken filter must not silently deliver items into a location the player
     * set aside for something else. The failure is logged at most once per {@link LogThrottle} interval.
     *
     * @param resolver reads the filter stack of a rack position straight from its interface; empty while it cannot be
     *                 read
     */
    FilterMatch match(Level level, RackPosition rack, ItemKey key,
            Function<RackPosition, Optional<ItemStack>> resolver) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(resolver, "resolver");
        Entry entry = filters.get(rack);
        if (entry == null) {
            if (!unread.contains(rack))
                return FilterMatch.UNFILTERED;
            Optional<ItemStack> resolved = resolver.apply(rack);
            if (resolved.isEmpty())
                return FilterMatch.REJECTED;
            set(rack, resolved.get()); // also clears the unread mark, so this costs one lookup per location ever
            entry = filters.get(rack);
            if (entry == null)
                return FilterMatch.UNFILTERED; // read, and it really carries no filter
        }
        try {
            if (!entry.filter().test(level, probe(key)))
                return FilterMatch.REJECTED;
            return entry.selecting() ? FilterMatch.DEDICATED : FilterMatch.ALLOWED;
        } catch (RuntimeException e) {
            if (failures.tryLog(level.getGameTime()))
                Wareworks.LOGGER.warn("Storage filter at {} could not be evaluated for {}", rack, key, e);
            return FilterMatch.REJECTED;
        }
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
