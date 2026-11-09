package dev.wareworks.content.controller;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.simibubi.create.content.logistics.filter.FilterItemStack;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.CrafterRecipeCheck;
import dev.wareworks.content.station.PatternFilterCells;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.util.LogThrottle;
import net.minecraft.world.level.Level;

/**
 * What the filter cells of an aisle's production patterns allow (ADR-082): one cache per controller, used on the server
 * thread and never ticked.
 * <p>
 * <b>Verdicts.</b> Per filter key, Create's matcher is built once ({@link PatternFilterCells#resolve}) and every key it was
 * asked about is remembered.
 * <p>
 * <b>The aisle's filters, never evicted.</b> The cache holds at most {@value #MAX_FILTERS} filters, and it never drops
 * one that is still in use to make room for another: a least-recently-used cache would miss on every access once a pass
 * cycles through more filters than it holds, and every pass would test the whole stock again. Instead the controller
 * tells it which filters its stations hold ({@link #retain}, with every station pattern read), in aisle order; filters
 * no station holds any more are dropped, and the first {@value #MAX_FILTERS} in aisle order are the ones it answers for.
 * A filter beyond them allows nothing (no Create test runs for it, its cell has no candidates and is refused with
 * {@code NO_MATCHING_ITEM}), and a warning is logged now and then. The bound is a deliberate scale cap: a real aisle
 * holds a handful of distinct filters.
 * <p>
 * <b>Stock matches.</b> The keys in stock a filter allows are kept with the stock index's
 * {@link StockView#keyGeneration()} they were built at, and rebuilt lazily when a key came or went: one pass over the
 * stock keys with the cached verdicts, so only a key new to the cache runs a Create test (also with more filters in the
 * aisle than the cap: a filter beyond it runs none). Verdicts for keys that left the stock are pruned during that
 * rebuild.
 * <p>
 * <b>Invalidation.</b> Everything is dropped when the level's recipe manager changes identity
 * ({@link CrafterRecipeCheck#recipeSet}): a {@code /reload} replaces recipes and tags together, and a tag is what an
 * Attribute Filter's "is tagged" tests.
 * <p>
 * <b>Cost.</b> Nothing per tick. It is asked only inside a request, a terminal's producible pass, a restock evaluation,
 * a station screen push and a station edit; per filter and change of the key set it costs one hash lookup per distinct
 * stock key plus one Create test per key it has not seen.
 */
public final class FilterMatchCache {
    /** Distinct filters one aisle plans with at most; the cache never holds more. */
    public static final int MAX_FILTERS = 128;

    private final LogThrottle failures = new LogThrottle();
    private final LogThrottle overCap = new LogThrottle();
    private final Map<ItemKey, Entry> entries = new HashMap<>();
    /**
     * The first {@value #MAX_FILTERS} filters of the aisle's last {@link #retain}, in aisle order, or {@code null}
     * before the first one.
     */
    private Set<ItemKey> admitted;
    private WeakReference<Object> recipes = new WeakReference<>(null);
    /** Create tests run so far; for the scale GameTest. */
    private long createTests;

    /** One filter: its matcher, its verdicts and its stock matches. */
    private static final class Entry {
        private final FilterItemStack filter;
        private final Map<ItemKey, Boolean> verdicts = new HashMap<>();
        private List<ItemKey> inStock = List.of();
        private long generation = Long.MIN_VALUE;

        private Entry(FilterItemStack filter) {
            this.filter = filter;
        }
    }

    /**
     * The filters the aisle's stations hold now, in aisle order: every filter no longer among them is dropped, and the
     * first {@value #MAX_FILTERS} are the ones this cache answers for from now on. Cheap (one set over the filters); the
     * controller calls it whenever it reads its station patterns.
     */
    public void retain(Collection<ItemKey> inAisleOrder) {
        Set<ItemKey> first = new LinkedHashSet<>();
        for (ItemKey filter : inAisleOrder) {
            if (first.size() >= MAX_FILTERS)
                break;
            first.add(filter);
        }
        entries.keySet().retainAll(first);
        admitted = first;
    }

    /**
     * Whether {@code filter} allows {@code key}. A filter that throws allows nothing, and the failure is logged; so does
     * a filter beyond the cap ({@link #MAX_FILTERS}).
     */
    public boolean test(Level level, ItemKey filter, ItemKey key) {
        Entry entry = entryFor(level, filter);
        return entry != null && verdict(level, entry, filter, key);
    }

    /**
     * Every key in {@code stock} that {@code filter} allows, in no particular order; rebuilt only when a key entered or
     * left the stock since the last call. Empty for a filter beyond the cap.
     */
    public List<ItemKey> inStock(Level level, ItemKey filter, StockView<ItemKey, RackPosition> stock) {
        Entry entry = entryFor(level, filter);
        if (entry == null)
            return List.of();
        long generation = stock.keyGeneration();
        if (entry.generation == generation)
            return entry.inStock;
        Set<ItemKey> keys = stock.keys();
        List<ItemKey> matches = new ArrayList<>();
        for (ItemKey key : keys) {
            if (verdict(level, entry, filter, key))
                matches.add(key);
        }
        // Verdicts of keys that left the stock are dropped, so the cache never grows past the stock it answers for.
        for (Iterator<ItemKey> it = entry.verdicts.keySet().iterator(); it.hasNext();) {
            if (!keys.contains(it.next()))
                it.remove();
        }
        entry.inStock = List.copyOf(matches);
        entry.generation = generation;
        return entry.inStock;
    }

    /** Create tests this cache has run so far (a filter's test of one key); for the scale GameTest. */
    public long createTests() {
        return createTests;
    }

    /** The filters the cache holds now. */
    public int size() {
        return entries.size();
    }

    /** The entry of {@code filter}, made on first use; {@code null} for a filter beyond the cap. */
    private Entry entryFor(Level level, ItemKey filter) {
        Object current = CrafterRecipeCheck.recipeSet(level);
        if (recipes.get() != current) {
            entries.clear();
            recipes = new WeakReference<>(current);
        }
        Entry entry = entries.get(filter);
        if (entry != null)
            return entry;
        // Once the aisle's own filters fill the cap, only they get a place; before that, any filter asked about may.
        boolean outside = admitted != null && admitted.size() >= MAX_FILTERS && !admitted.contains(filter);
        if (outside || entries.size() >= MAX_FILTERS) {
            if (overCap.tryLog(level.getGameTime()))
                Wareworks.LOGGER.warn("An aisle plans with more than {} distinct filters in its production patterns; "
                        + "filter {} allows nothing", MAX_FILTERS, filter);
            return null;
        }
        entry = new Entry(PatternFilterCells.resolve(filter));
        entries.put(filter, entry);
        return entry;
    }

    private boolean verdict(Level level, Entry entry, ItemKey filter, ItemKey key) {
        Boolean known = entry.verdicts.get(key);
        if (known != null)
            return known;
        boolean allows;
        createTests++;
        try {
            allows = entry.filter.test(level, key.toStack(1));
        } catch (RuntimeException e) {
            allows = false;
            if (failures.tryLog(level.getGameTime()))
                Wareworks.LOGGER.warn("Filter {} of a production pattern failed to test {}", filter, key, e);
        }
        entry.verdicts.put(key, allows);
        return allows;
    }
}
