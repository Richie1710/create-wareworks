package dev.wareworks.content.item;

import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Item handler view that lets automation insert into a backing handler but never extract from it (the external
 * capability of the warehouse input, {@code docs/warehouse-system.md} §3.2).
 * <p>
 * A pure delegate without state: slot count and contents are always those of the backing handler, so the view stays
 * valid when the backing handler changes size. Owners use the backing handler for their own extractions.
 */
public final class InsertOnlyItemHandler implements IItemHandler {
    private final IItemHandler backing;

    public InsertOnlyItemHandler(IItemHandler backing) {
        this.backing = Objects.requireNonNull(backing, "backing");
    }

    @Override
    public int getSlots() {
        return backing.getSlots();
    }

    /** The backing stack; callers must not modify it ({@link IItemHandler#getStackInSlot} contract). */
    @Override
    public ItemStack getStackInSlot(int slot) {
        return backing.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return backing.insertItem(slot, stack, simulate);
    }

    /** Always empty: nothing can be taken out through this view. */
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return backing.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return backing.isItemValid(slot, stack);
    }
}
