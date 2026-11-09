package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity.AislePattern;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.production.IngredientChoices;
import dev.wareworks.core.production.ProductionPattern;
import net.minecraft.world.level.Level;

/**
 * What may pay for a filter cell of this aisle's production patterns (ADR-082): the controller's
 * {@link IngredientChoices}, built once per click or pass ({@code WarehouseControllerBlockEntity#ingredientChoices}).
 * <p>
 * <b>Candidates of one filter</b> are, each once and sorted by {@link ItemKey#ORDER}:
 * <ul>
 * <li>the keys in stock it allows ({@link FilterMatchCache#inStock}) plus the matching keys a relocation carries in
 * flight (issue #46, which the availability counts), without the pattern's result and without keys this access may not
 * have any of; the {@value #MAX_STOCK_CANDIDATES} most available of them;</li>
 * <li>the results of this pass's patterns it allows, at most {@value #MAX_MADE_CANDIDATES}, in aisle order: what the
 * planner may make when stock is short.</li>
 * </ul>
 * <b>Narrowing.</b> A pattern of a station with a Crafter Feeder only takes keys the Crafter's recipe accepts in that
 * grid ({@code WarehouseProductionBlockEntity#crafterTakes}). The pattern is recognised by <b>identity</b>, exactly as
 * {@code startRestockOrder} relies on: the decision is made from these pattern objects, in this pass. An unknown pattern
 * gets un-narrowed candidates. Every station is resolved at most once per pass.
 * <p>
 * The answers are remembered per pattern object and filter for the life of this object, which is one click or pass.
 */
final class AisleChoices implements IngredientChoices<ItemKey> {
    /** Stock keys one filter may offer at most (D6). */
    static final int MAX_STOCK_CANDIDATES = 64;
    /** Producible results one filter may offer at most (D6). */
    static final int MAX_MADE_CANDIDATES = 16;

    private final Level level;
    private final FilterMatchCache cache;
    private final StockView<ItemKey, RackPosition> stock;
    private final Set<ItemKey> relocating;
    private final ToLongFunction<ItemKey> available;
    private final ToLongFunction<ItemKey> free;
    private final List<ItemKey> results;
    private final Map<ProductionPattern<ItemKey>, AislePattern> byPattern = new IdentityHashMap<>();
    private final Function<RackPosition, Optional<WarehouseProductionBlockEntity>> stations;
    private final Map<RackPosition, Optional<WarehouseProductionBlockEntity>> resolved = new HashMap<>();
    private final Map<ProductionPattern<ItemKey>, Map<ItemKey, List<ItemKey>>> answered = new IdentityHashMap<>();

    /**
     * @param patterns   this pass's patterns, the same objects the planner is given
     * @param relocating the keys a relocation carries in flight right now
     * @param available  what an item is worth to the access of this click
     * @param free       what an item is worth above every reserve (the AUTOMATION availability)
     * @param stations   resolves a production station's block entity
     */
    AisleChoices(Level level, FilterMatchCache cache, StockView<ItemKey, RackPosition> stock, Set<ItemKey> relocating,
            List<AislePattern> patterns, ToLongFunction<ItemKey> available, ToLongFunction<ItemKey> free,
            Function<RackPosition, Optional<WarehouseProductionBlockEntity>> stations) {
        this.level = level;
        this.cache = cache;
        this.stock = stock;
        this.relocating = relocating;
        this.available = available;
        this.free = free;
        this.stations = stations;
        Set<ItemKey> made = new LinkedHashSet<>();
        for (AislePattern candidate : patterns) {
            byPattern.put(candidate.pattern(), candidate);
            made.add(candidate.pattern().result().key());
        }
        this.results = List.copyOf(made);
    }

    @Override
    public List<ItemKey> candidates(ProductionPattern<ItemKey> pattern, ItemKey choice) {
        return answered.computeIfAbsent(pattern, p -> new HashMap<>()).computeIfAbsent(choice,
                c -> compute(pattern, c));
    }

    private List<ItemKey> compute(ProductionPattern<ItemKey> pattern, ItemKey choice) {
        ItemKey result = pattern.result().key();
        Set<ItemKey> all = new LinkedHashSet<>(stockMatches(choice, Optional.of(result)));
        int made = 0;
        for (ItemKey key : results) {
            if (made >= MAX_MADE_CANDIDATES)
                break;
            if (key.equals(result) || !cache.test(level, choice, key))
                continue;
            all.add(key);
            made++;
        }
        List<ItemKey> candidates = new ArrayList<>(all);
        candidates.sort(ItemKey.ORDER);
        AislePattern owner = byPattern.get(pattern);
        if (owner != null) {
            Optional<WarehouseProductionBlockEntity> station = resolved.computeIfAbsent(owner.station(), stations);
            if (station.isPresent() && station.get().attachedFeeder().isPresent())
                candidates.removeIf(key -> !station.get().crafterTakes(owner.slot(), choice, key));
        }
        return List.copyOf(candidates);
    }

    /**
     * The keys in stock {@code choice} allows, without {@code result} and without keys this access may have none of: the
     * stock part of a filter's candidates, the {@value #MAX_STOCK_CANDIDATES} most available, in no particular order.
     * Also the station screen's matches ({@code WarehouseControllerBlockEntity#choiceViews}), where a pattern may not
     * have a result yet.
     */
    List<ItemKey> stockMatches(ItemKey choice, Optional<ItemKey> result) {
        Map<ItemKey, Long> inStock = new HashMap<>();
        for (ItemKey key : cache.inStock(level, choice, stock))
            measure(inStock, key, result);
        for (ItemKey key : relocating) {
            if (!inStock.containsKey(key) && cache.test(level, choice, key))
                measure(inStock, key, result);
        }
        List<ItemKey> stocked = new ArrayList<>(inStock.keySet());
        if (stocked.size() > MAX_STOCK_CANDIDATES) {
            stocked.sort(Comparator.comparingLong((ItemKey key) -> inStock.get(key)).reversed()
                    .thenComparing(ItemKey.ORDER));
            stocked = new ArrayList<>(stocked.subList(0, MAX_STOCK_CANDIDATES));
        }
        return stocked;
    }

    /** Puts {@code key} with its availability, unless it is the result or this access may have none of it. */
    private void measure(Map<ItemKey, Long> into, ItemKey key, Optional<ItemKey> result) {
        if (result.isPresent() && key.equals(result.get()))
            return;
        long amount = available.applyAsLong(key);
        if (amount > 0L)
            into.put(key, amount);
    }

    @Override
    public long free(ItemKey key) {
        return Math.max(0L, free.applyAsLong(key));
    }

    @Override
    public boolean plain(ItemKey key) {
        return key.isPlain();
    }
}
