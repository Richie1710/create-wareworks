package dev.wareworks.content.item;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.inventory.InventorySnapshot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Adapts a live {@link IItemHandler} to a core {@link InventorySnapshot} keyed by {@link ItemKey}.
 * <p>
 * Reads every slot once, so call it on demand (interaction, job planning, throttled reconciliation), never per tick.
 * Stacks returned by the handler are only read, never modified.
 */
public final class ItemHandlerSnapshots {
    private ItemHandlerSnapshots() {
    }

    /** Snapshot of all slots of {@code handler}; a {@code null} handler yields a snapshot with zero slots. */
    public static InventorySnapshot<ItemKey> capture(@Nullable IItemHandler handler) {
        if (handler == null)
            return InventorySnapshot.empty();
        int slots = Math.max(0, handler.getSlots());
        InventorySnapshot.Builder<ItemKey> builder = InventorySnapshot.builder(slots);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            int limit = Math.max(0, handler.getSlotLimit(slot));
            if (stack.isEmpty())
                builder.addEmpty(limit);
            else
                builder.add(ItemKey.of(stack), stack.getCount(), limit, stack.getMaxStackSize());
        }
        return builder.build();
    }
}
