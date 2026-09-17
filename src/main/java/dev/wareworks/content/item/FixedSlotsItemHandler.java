package dev.wareworks.content.item;

import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Item handler view with a <b>fixed</b> number of slots over a backing handler that may have a different one
 * ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * A menu decides how many slots it has when it is built, and both sides must agree on that number, but the handler
 * behind it can have another size: a warehouse terminal's buffer grows past the configured slot count when a save used
 * higher slots ({@link dev.wareworks.content.station.StationBuffer}), while the client's block entity always has the
 * configured count, and {@code /data merge} can even shrink a buffer while a screen is open. This view makes the size
 * the menu announced the only one that matters: slots outside the backing handler read as empty and refuse everything,
 * and no call can reach {@code ItemStackHandler}'s slot-range check, which throws.
 * <p>
 * {@link IItemHandlerModifiable} because {@code SlotItemHandler#set} casts to it; a backing handler that is not
 * modifiable simply ignores the write, so a view is never a way around a handler's own rules.
 */
public final class FixedSlotsItemHandler implements IItemHandlerModifiable {
    private final IItemHandler backing;
    private final int slots;

    /**
     * @param backing the handler the in-range slots are read from and written to
     * @param slots   the number of slots this view has (at least 1), whatever {@code backing} has
     */
    public FixedSlotsItemHandler(IItemHandler backing, int slots) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.slots = Math.max(1, slots);
    }

    @Override
    public int getSlots() {
        return slots;
    }

    /** The backing stack; callers must not modify it ({@link IItemHandler#getStackInSlot} contract). */
    @Override
    public ItemStack getStackInSlot(int slot) {
        return inBacking(slot) ? backing.getStackInSlot(slot) : ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (inBacking(slot) && backing instanceof IItemHandlerModifiable modifiable)
            modifiable.setStackInSlot(slot, stack);
    }

    /** Returns {@code stack} unchanged for a slot the backing handler does not have. */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return inBacking(slot) ? backing.insertItem(slot, stack, simulate) : stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return inBacking(slot) ? backing.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return inBacking(slot) ? backing.getSlotLimit(slot) : 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return inBacking(slot) && backing.isItemValid(slot, stack);
    }

    /** Whether {@code slot} is one of this view's slots <b>and</b> one the backing handler really has. */
    private boolean inBacking(int slot) {
        return slot >= 0 && slot < slots && slot < backing.getSlots();
    }
}
