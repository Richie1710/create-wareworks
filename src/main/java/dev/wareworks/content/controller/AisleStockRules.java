package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.KeeperRule;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRules;

/**
 * The controller's own copy of the stock rules its aisle's warehouse stock keepers hold
 * ({@code docs/warehouse-system.md} §3.6, M15, issue #3). Owned by one {@link WarehouseControllerBlockEntity}; server
 * thread only.
 * <p>
 * <b>Why a copy, and why a saved one.</b> A rule gates item movement in <i>both</i> directions: a maximum stops the
 * planner from making a {@code STORE} job, a reserve stops automation from being promised the last items. Both effects
 * are irreversible — nothing is ever re-shuffled and a handed-out reserve cannot be taken back — so a miss must never
 * be read as "no rule". A keeper sits at a rack position and its chunk can be unloaded while the controller plans, so
 * unlike {@code AisleFilters}, which re-reads an interface on demand, this cache is <b>persisted with the
 * controller</b>: after a world load the rules are there before the first tick, and a keeper whose chunk is not loaded
 * simply keeps the rules it was last read with. That is the M8 cold-cache lesson applied where it would have done
 * permanent damage.
 * <p>
 * For the same reason this copy is <b>never thrown away wholesale</b> — not when the aisle is lost, not when its dock
 * moves and not when the layout is replaced. Only the two operations that name one rack drop anything: {@link #set} on a
 * keeper that was read again, and {@link #remove} for a rack a <i>loaded</i> block proved to be no aligned keeper any
 * more. Everything else about an aisle is re-read within a few ticks; a maximum that was forgotten for one tick has
 * already stored items that are never moved back, and a forgotten reserve has already been handed out.
 * <p>
 * <b>Order is meaning.</b> The rules of the aisle are the keepers' rows concatenated in {@link RackPosition#ORDER} —
 * a fixed, coordinate-derived order, so the same build always produces the same rule set and the first rule for an
 * item is always the same one. {@link StockRules} then decides which rule governs an item, which one is shadowed by an
 * earlier one, and which ones are beyond the aisle's cap ({@code maxStockRules}) and therefore inert.
 * <p>
 * Rebuilding is O(total rules) and happens only when a keeper joins, changes or leaves, or when the cap changes —
 * never per tick and never per planning run. A lookup during planning is what {@link StockRules} costs: one hash map
 * read.
 * <p>
 * <b>Fluid rules</b> (M31, issue #21, ADR-073). A row may count the fluid of its filled container instead of the item
 * ({@link KeeperRule#fluid()}). Such a row keeps its place in {@link #rules()} — the aisle-wide index a keeper's screen
 * and goggles read by — but as an <b>empty</b> item rule, which by {@link StockRules}' own rule neither governs nor
 * shadows anything, so a lava bucket row that counts lava never caps the lava buckets on the shelves. The same row is
 * enforced in the parallel {@link #fluidRules()}, keyed by the fluid and counted in millibuckets, with the same "the
 * first rule governs" meaning among the fluid rows and the same cap: a fluid row at or beyond the aisle's rule cap is
 * left out of it, exactly as it is inert in {@link #rules()}.
 */
final class AisleStockRules {
    /** Rules per keeper, in the aisle-wide order the flattened set inherits. */
    private final Map<RackPosition, List<KeeperRule>> perKeeper = new TreeMap<>(RackPosition.ORDER);
    /** Where each keeper's rules start in {@link #rules()}, for "what is my row doing" on a keeper's own screen. */
    private final Map<RackPosition, Integer> offsets = new HashMap<>();

    private StockRules<ItemKey> flattened = StockRules.empty();
    private int cap = StockRules.NO_CAP;
    /** Every row in aisle order as it was configured, fluid rows included; parallel to {@link #flattened}. */
    private List<KeeperRule> configured = List.of();
    /** The fluid rows below the cap, keyed by fluid and in aisle order (M31). */
    private StockRules<FluidKey> fluidFlattened = StockRules.empty();
    /** Where each fluid row of {@link #fluidFlattened} stands in {@link #rules()}: aisle index → fluid index. */
    private Map<Integer, Integer> fluidIndexOf = Map.of();

    /** The aisle's rules, in keeper order and row order. Never {@code null}; empty without keepers. */
    StockRules<ItemKey> rules() {
        return flattened;
    }

    /** Whether no keeper of this aisle holds a rule. */
    boolean isEmpty() {
        return flattened.isEmpty();
    }

    /**
     * The aisle's fluid rules (M31): one per {@link KeeperRule#fluid() fluid row} below the rule cap, in aisle order,
     * keyed by the fluid and counted in millibuckets. Never {@code null}; empty without fluid rows, which is what every
     * aisle before M31 has.
     */
    StockRules<FluidKey> fluidRules() {
        return fluidFlattened;
    }

    /**
     * Where the fluid rule of the row at {@code index} of {@link #rules()} stands in {@link #fluidRules()}, or empty for
     * an item row, a fluid row beyond the cap, or an index out of range.
     */
    OptionalInt fluidIndexAt(int index) {
        Integer fluidIndex = fluidIndexOf.get(index);
        return fluidIndex == null ? OptionalInt.empty() : OptionalInt.of(fluidIndex);
    }

    /** The row at {@code index} of {@link #rules()} as it was configured, with its unit; empty out of range. */
    Optional<KeeperRule> configuredAt(int index) {
        return index >= 0 && index < configured.size() ? Optional.of(configured.get(index)) : Optional.empty();
    }

    /** The rack positions this copy holds rules for, in {@link RackPosition#ORDER}. */
    Set<RackPosition> keepers() {
        return perKeeper.keySet();
    }

    /** Whether this copy holds rules for the keeper at {@code rack}. */
    boolean contains(RackPosition rack) {
        return perKeeper.containsKey(Objects.requireNonNull(rack, "rack"));
    }

    /**
     * Where the rules of the keeper at {@code rack} start in {@link #rules()}, so its screen and its goggles can ask
     * the aisle-wide set what each of its own rows is doing. Empty when that keeper holds no rule at all.
     */
    OptionalInt offsetOf(RackPosition rack) {
        Integer offset = offsets.get(Objects.requireNonNull(rack, "rack"));
        return offset == null ? OptionalInt.empty() : OptionalInt.of(offset);
    }

    /** How many rules the keeper at {@code rack} contributes to {@link #rules()}; 0 when it holds none. */
    int ruleCountAt(RackPosition rack) {
        List<KeeperRule> rules = perKeeper.get(Objects.requireNonNull(rack, "rack"));
        return rules == null ? 0 : rules.size();
    }

    /**
     * Stores the rules of the keeper at {@code rack}. An empty list keeps the keeper listed with no rules, which is
     * different from {@link #remove}: a keeper whose last rule a player deleted is still a member of the aisle.
     *
     * @return whether the aisle's rule set really changed (a refresh that changed nothing writes nothing)
     */
    boolean set(RackPosition rack, List<KeeperRule> rules) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(rules, "rules");
        List<KeeperRule> copy = List.copyOf(rules);
        List<KeeperRule> before = perKeeper.put(rack, copy);
        if (copy.equals(before))
            return false;
        return rebuild();
    }

    /** The keeper at {@code rack} left the aisle (or is no longer a keeper). */
    boolean remove(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (perKeeper.remove(rack) == null)
            return false;
        return rebuild();
    }

    /**
     * Applies the aisle's rule cap ({@code maxStockRules}). Lowering it is reversible: nothing is written back into a
     * keeper, so raising it again makes the same rules govern again.
     *
     * @return whether the rule set changed
     */
    boolean setCap(int cap) {
        int clamped = Math.max(0, Math.min(cap, StockRules.MAX_RULES));
        if (clamped == this.cap)
            return false;
        this.cap = clamped;
        // A rebuild rather than withCap: the fluid rules leave out every fluid row at or beyond the cap, so a changed
        // cap changes which fluid rows exist at all.
        return rebuild();
    }

    /** The saved form: one entry per keeper, in aisle order. */
    Map<RackPosition, List<KeeperRule>> saved() {
        return perKeeper;
    }

    /**
     * Replaces the whole copy with what was saved with the controller. The rules are trusted no further than any other
     * save data: {@link StockRule} clamps every number on construction, and {@link StockRules} bounds the list.
     */
    void restore(Map<RackPosition, List<KeeperRule>> saved) {
        Objects.requireNonNull(saved, "saved");
        perKeeper.clear();
        for (Map.Entry<RackPosition, List<KeeperRule>> entry : saved.entrySet())
            perKeeper.put(entry.getKey(), List.copyOf(entry.getValue()));
        rebuild();
    }

    private boolean rebuild() {
        StockRules<ItemKey> before = flattened;
        StockRules<FluidKey> fluidBefore = fluidFlattened;
        List<KeeperRule> rows = new ArrayList<>();
        List<StockRule<ItemKey>> all = new ArrayList<>();
        List<StockRule<FluidKey>> fluids = new ArrayList<>();
        Map<Integer, Integer> fluidIndices = new HashMap<>();
        offsets.clear();
        for (Map.Entry<RackPosition, List<KeeperRule>> entry : perKeeper.entrySet()) {
            List<KeeperRule> rules = entry.getValue();
            if (rules.isEmpty())
                continue;
            offsets.put(entry.getKey(), all.size());
            for (KeeperRule row : rules) {
                int index = all.size();
                if (index >= StockRules.MAX_RULES)
                    break; // what StockRules would drop anyway; kept parallel to it
                rows.add(row);
                Optional<FluidKey> fluid = row.fluid();
                if (fluid.isEmpty()) {
                    all.add(row.rule());
                    continue;
                }
                // The row keeps its place as an empty item rule, which governs and shadows nothing (StockRules), and is
                // enforced as a rule on its fluid instead - unless it is beyond the cap, where it is inert either way.
                all.add(StockRule.of(row.key()));
                if (index < cap) {
                    fluidIndices.put(index, fluids.size());
                    fluids.add(row.rule().withKey(fluid.get()));
                }
            }
        }
        configured = List.copyOf(rows);
        flattened = StockRules.of(all, cap);
        fluidFlattened = fluids.isEmpty() ? StockRules.empty() : StockRules.of(fluids);
        fluidIndexOf = Map.copyOf(fluidIndices);
        return !flattened.equals(before) || !fluidFlattened.equals(fluidBefore);
    }

    @Override
    public String toString() {
        return "AisleStockRules[keepers=" + perKeeper.size() + ", " + flattened + "]";
    }
}
