package dev.wareworks.content.item;

import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Item handler view that lets automation extract from a backing handler but never insert into it (the external
 * capability of the warehouse output, {@code docs/warehouse-system.md} §3.2): funnels, chutes and hoppers can pull
 * delivered items, but nothing can be pushed in.
 * <p>
 * A pure delegate without state; owners insert through the backing handler.
 */
public final class ExtractOnlyItemHandler implements IItemHandler {
    private final IItemHandler backing;

    public ExtractOnlyItemHandler(IItemHandler backing) {
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

    /** Always rejects: returns {@code stack} unchanged. */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return backing.extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return backing.getSlotLimit(slot);
    }

    /** Always false: no item can be inserted through this view. */
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }
}
