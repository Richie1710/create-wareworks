package dev.wareworks.content.packagemode;

import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The outer face of a storage block in package mode (issue #40): every call goes to {@code backing}, except that a
 * loose stack is refused on the way in ({@link PackageModeGate#admits}). Taking out is untouched, so loose stock from
 * before the switch stays retrievable.
 * <p>
 * Used for a rack bay's capability (design R-IN-5): the crane keeps the bay's raw handler, so its relocations and
 * leftovers of loose stock still land, and only automation outside the warehouse (a hopper, a funnel, a chute or a belt
 * behind or beside the bay) meets the gate. The bay hands this view out only while package mode is on; off, its
 * capability is the raw handler as it always was. A pure delegate without state.
 */
public final class PackageGatedItemHandler implements IItemHandler {
    private final IItemHandler backing;

    public PackageGatedItemHandler(IItemHandler backing) {
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
        return PackageModeGate.admits(stack) ? backing.insertItem(slot, stack, simulate) : stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return backing.extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return backing.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return PackageModeGate.admits(stack) && backing.isItemValid(slot, stack);
    }
}
