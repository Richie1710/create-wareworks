package dev.wareworks.content.crane.head;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * The inventory a handling head reaches at one location of the aisle, for the current tick only
 * ({@code docs/stacker-crane.md} §6). Three kinds exist ({@link TransferContexts#resolve}): a storage location (the item
 * handler of the inventory behind a warehouse interface), an input station (its internal extract/insert API) and an
 * output station (its internal insert API; nothing can be extracted).
 * <p>
 * The same context serves the crane's real transfers and the controller's live simulations
 * ({@code docs/warehouse-system.md} §5): a simulated call equals the real one in the same tick for well-behaved
 * inventories. Callers never keep a context across ticks. Stacks passed in are never modified or stored.
 */
public interface TransferContext {
    /** What kind of location this is. */
    LocationKind kind();

    /** World position of the location (where the arm reaches in; stray items are spilled here). */
    BlockPos position();

    /**
     * Removes up to {@code maxAmount} items of exactly {@code key}, at most one stack of the key. Something else a
     * misbehaving inventory hands out is given back or spilled, never returned or lost.
     *
     * @return a new stack of {@code key} with the removed amount, or empty
     */
    ItemStack extract(ItemKey key, int maxAmount, boolean simulate);

    /**
     * Inserts {@code stack} (matching stacks first where applicable).
     *
     * @return what did not fit (a new stack, or empty)
     */
    ItemStack insert(ItemStack stack, boolean simulate);

    /** How many of up to {@code maxAmount} items of {@code key} could be extracted now (no change). */
    int simulateExtract(ItemKey key, int maxAmount);

    /** How many of {@code amount} items of {@code key} would be accepted now (no change). */
    int simulateInsert(ItemKey key, int amount);

    /** Drops {@code stack} into the world at {@link #position()} (never voids items). */
    void spill(ItemStack stack);
}
