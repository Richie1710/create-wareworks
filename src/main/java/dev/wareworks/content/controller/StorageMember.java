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
}
