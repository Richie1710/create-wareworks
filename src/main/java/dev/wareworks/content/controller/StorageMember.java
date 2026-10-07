package dev.wareworks.content.controller;

import java.util.Optional;

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
