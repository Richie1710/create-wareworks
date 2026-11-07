package dev.wareworks.content.requester;

import java.util.Objects;

import dev.wareworks.content.remote.WarehouseRemoteItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The one slot of a Schematicannon Requester (issue #45, ADR-080): the linked Warehouse Remote that is its
 * configuration item and stays there.
 * <p>
 * <b>It is never a capability</b>, on any face. Create's {@code SchematicannonBlockEntity#findInventories} adds every
 * neighbour that answers {@code Capabilities.ItemHandler.BLOCK} to the cannon's inventories, so a slot offered there
 * would have the cannon pull the remote out and count it on its material list. Only the block entity's own insert and
 * remove clicks reach it. It holds one {@link WarehouseRemoteItem} and nothing else, and every change is saved and sent
 * to the clients at once, because the renderer draws the remote in the block's cradle.
 */
public final class RemoteSlot extends ItemStackHandler {
    private final SchematicannonRequesterBlockEntity owner;

    public RemoteSlot(SchematicannonRequesterBlockEntity owner) {
        super(1);
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** The remote in the slot, or an empty stack (the stack itself, not a copy: do not change it). */
    public ItemStack remote() {
        return getStackInSlot(0);
    }

    /** Whether a remote is in the slot. */
    public boolean isFilled() {
        return !remote().isEmpty();
    }

    /** Sets the slot without telling anybody: what a save or a client packet held. Never throws. */
    void load(ItemStack stack) {
        stacks.set(0, stack == null || !isItemValid(0, stack) ? ItemStack.EMPTY : stack.copyWithCount(1));
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return stack.getItem() instanceof WarehouseRemoteItem;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    @Override
    protected void onContentsChanged(int slot) {
        owner.notifyUpdate();
    }
}
