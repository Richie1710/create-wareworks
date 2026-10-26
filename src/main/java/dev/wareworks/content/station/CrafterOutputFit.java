package dev.wareworks.content.station;

import java.util.List;
import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Whether what a vanilla Crafter is about to output fits into the item handler in front of it (issue #34, ADR-067 D5).
 * <p>
 * The Crafter hands its result and then each remainder to that handler one stack at a time
 * ({@code VanillaInventoryCodeHooks#insertCrafterOutput}), and a stack goes into a slot only if the <b>whole</b> stack
 * fits that slot; whatever finds no such slot is dropped as an item entity in front of the Crafter. This check models
 * exactly that against a copy of the handler's slots, so the result and its remainders are judged together — two
 * independent simulations would both claim the same last free slot. A slot nothing has been modelled into yet is asked
 * the handler itself ({@code insertItem} simulated), which carries every rule the handler has; a slot the model already
 * filled is judged by the count and the slot limit.
 * <p>
 * A vanilla container in front is fed by the hopper code instead, which spreads a stack over several slots, so for it
 * this check is on the safe side: it may wait where the Crafter would have managed, never the other way round.
 */
final class CrafterOutputFit {
    private CrafterOutputFit() {
    }

    /** Whether every stack of {@code leaving}, in order, finds a slot of {@code target} that takes it whole. */
    static boolean fits(IItemHandler target, List<ItemStack> leaving) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(leaving, "leaving");
        int slots = target.getSlots();
        ItemStack[] modelled = new ItemStack[slots];
        for (ItemStack stack : leaving) {
            if (stack == null || stack.isEmpty())
                continue;
            boolean placed = false;
            for (int slot = 0; slot < slots && !placed; slot++) {
                if (modelled[slot] == null) {
                    if (target.insertItem(slot, stack, true).isEmpty()) {
                        ItemStack present = target.getStackInSlot(slot);
                        modelled[slot] = present.isEmpty() ? stack.copy()
                                : present.copyWithCount(present.getCount() + stack.getCount());
                        placed = true;
                    }
                    continue;
                }
                ItemStack present = modelled[slot];
                int limit = Math.min(target.getSlotLimit(slot), present.getMaxStackSize());
                if (ItemStack.isSameItemSameComponents(present, stack)
                        && present.getCount() + stack.getCount() <= limit) {
                    present.grow(stack.getCount());
                    placed = true;
                }
            }
            if (!placed)
                return false;
        }
        return true;
    }
}
