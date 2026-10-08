package dev.wareworks.content.controller;

import java.util.Map;
import java.util.Optional;

import dev.wareworks.content.fluid.FluidDedication;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * A warehouse member that is a storage location: it reads an attached inventory for the controller's stock index
 * ({@code docs/warehouse-system.md} §5). Implemented by the warehouse interface.
 */
public interface StorageMember extends WarehouseMember {
    @Override
    default LocationKind locationKind() {
        return LocationKind.STORAGE;
    }

    /** Position of the attached inventory; the controller skips snapshots while it is not loaded. */
    BlockPos attachedPos();

    /**
     * Reads the attached inventory (server, on demand only). A snapshot with zero slots means no inventory is attached.
     */
    InventorySnapshot<ItemKey> snapshot();

    /**
     * What this location holds as <b>fluid</b>, in millibuckets per fluid, for the controller's parallel fluid stock
     * index ({@code docs/warehouse-system.md} §3.9, M30 step 10, D10). A fluid bay answers one entry while it holds
     * anything; every other storage member leaves the default, which is the empty map.
     * <p>
     * It is a <b>map</b> and not a fluid plus an amount because that is what {@code StockIndex#restore} takes, and
     * that index is the whole reason this method exists: the controller never learns an amount it cannot put straight
     * into it. A bay holds exactly one fluid, so the map is empty or a single entry — and the entry disappears the
     * moment the bay empties, which is what makes an unfiltered bay take whatever arrives next ({@code BayContents}).
     * <p>
     * It rides beside {@link #snapshot()} rather than inside it for the reason D10 refuses a union key: the item index
     * feeds {@code StockView#totalItems()} and {@code distinctKeys()}, which the controller's goggles, its display
     * board, its save and every plan read as <b>items</b>, and summing millibuckets into that number would corrupt all
     * of them at once. Two indexes are also the issue's own answer to "what does the warehouse hold": {@code lava: 64
     * buckets} <b>and</b> {@code bucket: 17}, with nothing pretending one is the other.
     * <p>
     * Read on the same paths as {@link #snapshot()} and never cached by the caller, so it has to be cheap: a bay
     * answers it from two fields.
     */
    default Map<FluidKey, Long> fluidStock() {
        return Map.of();
    }

    /**
     * The live item handler of the attached inventory (server: cached), empty without inventory or while its position is
     * not loaded. The crane transfers through it and the controller simulates through it (M3); never keep it across
     * ticks.
     */
    Optional<IItemHandler> attachedHandler();

    /**
     * The filter stack that decides which items may be <b>stored</b> here ({@code docs/warehouse-system.md} §3.1,
     * ADR-021). An empty stack means "accepts everything", the Create convention for an empty filter slot; retrieval is
     * never restricted by it.
     * <p>
     * Must return a <b>copy</b>: the controller hands it to Create's {@code FilterItemStack.of}, which trims components
     * of a filter item in place. The default is "no filter", so a storage member without a filter slot needs no change.
     */
    default ItemStack storeFilter() {
        return ItemStack.EMPTY;
    }

    /**
     * Which fluid this location takes <b>filled containers</b> of, or empty for a location that stores items
     * ({@code docs/warehouse-system.md} §3.9, M30, issue #21, D6). A fluid bay answers it; every other storage member
     * leaves the default.
     * <p>
     * It rides beside {@link #storeFilter()} rather than inside it because the two are read in opposite ways: a store
     * filter is a Create {@code FilterItemStack} evaluated against an arriving item, while a fluid bay's filter slot
     * holds a <b>container</b> whose only meaning is the fluid inside it. Evaluating that slot as an item filter would
     * match the <i>item</i> {@code lava_bucket} and route containers where fluid was meant, which is the dangerous kind
     * of free — so a location that answers this is never asked the item question at all.
     * <p>
     * A present answer is a <b>hard</b> rule in both directions: such a location takes a container of its fluid
     * ({@code FilterMatch#DEDICATED}, so it outranks a shelf) and nothing else at all, not even an item an unfiltered
     * chest beside it would take. An empty container carries no fluid and is therefore refused, which is what keeps the
     * warehouse from carrying the empties it just produced straight back to the bay.
     */
    default Optional<FluidDedication> storeFluidFilter() {
        return Optional.empty();
    }

    /**
     * The storage priority a player gave this location ({@code docs/warehouse-system.md} §3.1, ADR-028, M16): 0..9,
     * higher fills first. It orders only the locations the store filter, consolidation and item-type grouping left
     * equal, it is read only when items are <b>stored</b>, and it is a property of the location and of no item — which
     * is what keeps it out of the retrieval path entirely.
     * <p>
     * The default is 0, "no preference", so a storage member without a priority slot needs no change and a warehouse
     * nobody prioritised plans exactly as it did before M16.
     */
    default int storePriority() {
        return 0;
    }

    /**
     * Whether this location holds at most <b>one item type at a time</b>: everything already inside it, and everything
     * a planned job is about to bring, has to be the same item as the next delivery. A rack bay says yes; an inventory
     * behind a warehouse interface says no, because a chest may hold whatever a player put into it.
     * <p>
     * It is <b>stricter than the planner's item-type grouping</b> and a different kind of rule. {@code JobPlanner}
     * prefers a location that holds nothing of another type ({@code holdsOnlyTypeOf}, a ranking key over the item), but
     * it will still store into a mixed one when nothing better is free. This is a gate: a location that answers true is
     * never offered a second type at all, not even as the last candidate, because ADR-021 never re-shuffles and a
     * location that was mixed once stays mixed.
     * <p>
     * The default is false, so a storage member that takes anything needs no change and a warehouse of interfaces is
     * planned exactly as it was before.
     */
    default boolean holdsOneTypeOnly() {
        return false;
    }

    /**
     * Whether this location may be <b>stored into</b> at all. Retrieval is never restricted by it: items that are
     * already inside can always be fetched, exactly as with the store filter.
     * <p>
     * It answers for the location itself rather than for an item, which is what distinguishes it from
     * {@link #storeFilter()}: a rack bay that may not be filled because the column it stands in forbids it refuses
     * every item for the same reason, and no filter could express that.
     * <p>
     * The default is true, so a storage member that is always fillable needs no change.
     */
    default boolean acceptsStoring() {
        return true;
    }
}
