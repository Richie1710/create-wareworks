package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.content.item.ItemHandlerSnapshots;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.InventorySnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The item buffer of a warehouse station ({@code docs/warehouse-system.md} §3.2). It is never exposed as a capability
 * itself: automation sees an insert-only or extract-only view, the crane uses the station's API.
 * <p>
 * <b>No aliasing.</b> {@link ItemStackHandler#insertItem} stores the caller's stack instance when it fits into an empty
 * slot; this buffer stores copies ({@link #insertItem}, {@link #setStackInSlot}), and {@link #extract} and {@link #insert}
 * never hand out or keep a caller's instance, so a caller that changes its stack later cannot duplicate or delete
 * buffered items.
 * <p>
 * <b>Persistence.</b> {@link #save} writes each non-empty slot as a count-less {@link ItemKey} plus an {@code int} count,
 * so {@code ItemStack.save} is never called and saving never throws. {@link #load} never throws and never drops a
 * readable item of a save this class wrote: the buffer gets at least the configured number of slots, more if the save
 * used higher slots (e.g. the config was lowered), and entries with invalid, duplicate or out-of-range slots or oversized
 * counts are split into stacks and placed into free slots, growing the buffer if needed. Only items that cannot be decoded
 * at all (e.g. of a removed mod) are lost, as in vanilla containers.
 * <p>
 * <b>Bounded loading.</b> Save data is untrusted: block entity data on items, {@code /data merge}, structures and uploaded
 * schematics all reach {@link #load}. A load therefore creates at most {@link #MAX_LOADED_STACKS} stacks and slots in
 * total, whatever the saved counts are; the excess of such a crafted entry is skipped with a warning. A legitimate save
 * holds at most one stack per slot and far fewer slots, so it is never affected.
 * <p>
 * Server thread only.
 */
public class StationBuffer extends ItemStackHandler {
    /** A buffer has at least one slot. */
    public static final int MIN_SLOTS = 1;
    /** Saved slot indices at or above this value are treated as invalid; their items go into free slots. */
    public static final int MAX_SAVED_SLOT_INDEX = 1024;
    /** Upper bound for the stacks (and slots) one {@link #load} creates, so crafted counts cannot exhaust memory. */
    public static final int MAX_LOADED_STACKS = MAX_SAVED_SLOT_INDEX;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SIZE = "Size";
    private static final String ITEMS = "Items";
    private static final String SLOT = "Slot";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";
    private static final int NO_SLOT = -1;

    private final Runnable onChanged;

    /**
     * @param slots     number of slots (at least {@value #MIN_SLOTS})
     * @param onChanged called after every content change through this handler (not on {@link #load})
     */
    public StationBuffer(int slots, Runnable onChanged) {
        super(Math.max(MIN_SLOTS, slots));
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    // --- item handler --------------------------------------------------------------------------------------------

    /** Inserts a copy: the caller's stack is never stored. Returns the remainder like {@link ItemStackHandler}. */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return super.insertItem(slot, simulate ? stack : stack.copy(), simulate);
    }

    /** Stores a copy of {@code stack}. */
    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        super.setStackInSlot(slot, stack.copy());
    }

    @Override
    protected void onContentsChanged(int slot) {
        onChanged.run();
    }

    // --- station API ---------------------------------------------------------------------------------------------

    /**
     * Inserts {@code stack} into matching stacks first, then into empty slots. The caller's stack is not modified.
     *
     * @return what did not fit (a new stack, or empty)
     */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        if (stack.isEmpty())
            return ItemStack.EMPTY;
        return ItemHandlerHelper.insertItemStacked(this, stack.copy(), simulate);
    }

    /**
     * Takes up to {@code amount} items of {@code key} from all slots, at most one stack ({@link ItemKey#getMaxStackSize()})
     * per call; call it again for more. With {@code simulate} the buffer is unchanged and the result is what a real call
     * would return in the same tick.
     *
     * @return a new stack of {@code key} with the taken amount, or empty
     */
    public ItemStack extract(ItemKey key, int amount, boolean simulate) {
        Objects.requireNonNull(key, "key");
        int limit = Math.min(amount, key.getMaxStackSize());
        if (limit <= 0)
            return ItemStack.EMPTY;
        int taken = 0;
        for (int slot = 0; slot < stacks.size() && taken < limit; slot++) {
            ItemStack inSlot = stacks.get(slot);
            if (!key.matches(inSlot))
                continue;
            int take = Math.min(limit - taken, inSlot.getCount());
            if (!simulate) {
                stacks.set(slot, take == inSlot.getCount() ? ItemStack.EMPTY : inSlot.copyWithCount(inSlot.getCount() - take));
                onContentsChanged(slot);
            }
            taken += take;
        }
        return key.toStack(taken);
    }

    /** Number of items of {@code key} in all slots (the amount {@link #extract} can take over several calls). */
    public long countOf(ItemKey key) {
        Objects.requireNonNull(key, "key");
        long total = 0;
        for (ItemStack stack : stacks) {
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }

    /** Snapshot of all slots (reads each slot once). */
    public InventorySnapshot<ItemKey> snapshot() {
        return ItemHandlerSnapshots.capture(this);
    }

    public boolean isEmpty() {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty())
                return false;
        }
        return true;
    }

    /** Sum of the counts of all slots. */
    public long totalItems() {
        long total = 0;
        for (ItemStack stack : stacks)
            total += stack.getCount();
        return total;
    }

    /**
     * Drops every buffered stack at {@code pos} and empties the slots (block broken). Uses
     * {@link Containers#dropItemStack}, which ignores the {@code doTileDrops} game rule, so no item is ever voided.
     *
     * @return the number of dropped items
     */
    public long dropAll(Level level, BlockPos pos) {
        long dropped = 0;
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty())
                continue;
            stacks.set(slot, ItemStack.EMPTY);
            dropped += stack.getCount();
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack.copy());
        }
        return dropped;
    }

    /** Empties every slot without dropping anything ({@code Clearable}: commands and structures replace the block). */
    public void clear() {
        boolean changed = false;
        for (int slot = 0; slot < stacks.size(); slot++) {
            if (stacks.get(slot).isEmpty())
                continue;
            stacks.set(slot, ItemStack.EMPTY);
            changed = true;
        }
        if (changed)
            onChanged.run();
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    /**
     * NBT form: {@code {Size: int, Items: [{Slot: int, Item: <ItemKey>, Count: int}]}}. Never throws; a stack whose key
     * cannot be encoded is skipped with a warning.
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        ListTag items = new ListTag();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty())
                continue;
            try {
                Tag keyTag = ItemKey.of(stack).save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty()) {
                    LOGGER.warn("Could not save buffered stack {} in slot {}", stack, slot);
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putInt(SLOT, slot);
                entry.put(ITEM, keyTag);
                entry.putInt(COUNT, stack.getCount());
                items.add(entry);
            } catch (RuntimeException e) {
                LOGGER.warn("Could not save buffered stack in slot {}", slot, e);
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt(SIZE, stacks.size());
        tag.put(ITEMS, items);
        return tag;
    }

    /**
     * Replaces the contents with a save written by {@link #save} (see the class comment for the rules). Does not call
     * the change callback. Never throws.
     *
     * @param configuredSlots the slot count for this buffer from the config
     */
    public void load(CompoundTag tag, HolderLookup.Provider registries, int configuredSlots) {
        List<Entry> entries = new ArrayList<>();
        int highestSlot = NO_SLOT;
        int stackBudget = MAX_LOADED_STACKS;
        int truncatedEntries = 0;
        ListTag list = tag.getList(ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            if (stackBudget == 0) {
                truncatedEntries += list.size() - i; // not even decoded
                break;
            }
            CompoundTag entryTag = list.getCompound(i);
            try {
                int count = entryTag.getInt(COUNT);
                Optional<ItemKey> key = count > 0 ? ItemKey.load(registries, entryTag.get(ITEM)) : Optional.empty();
                if (key.isEmpty())
                    continue;
                int slot = entryTag.contains(SLOT, Tag.TAG_INT) ? entryTag.getInt(SLOT) : NO_SLOT;
                if (slot < 0 || slot >= MAX_SAVED_SLOT_INDEX)
                    slot = NO_SLOT;
                highestSlot = Math.max(highestSlot, slot);
                int perStack = Math.max(1, Math.min(getSlotLimit(0), key.get().getMaxStackSize()));
                long stacksNeeded = (count + (long) perStack - 1) / perStack;
                int stacks = (int) Math.min(stacksNeeded, stackBudget);
                if (stacks < stacksNeeded)
                    truncatedEntries++;
                stackBudget -= stacks;
                int left = count;
                for (int part = 0; part < stacks; part++, left -= perStack) {
                    // Only the first part of an oversized entry keeps its slot; the rest goes into free slots.
                    entries.add(new Entry(part == 0 ? slot : NO_SLOT, key.get().toStack(Math.min(perStack, left))));
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Skipping unreadable buffered stack {}", entryTag, e);
            }
        }
        if (truncatedEntries > 0)
            LOGGER.warn("Station buffer save holds more than {} stacks; {} entries were not fully loaded",
                    MAX_LOADED_STACKS, truncatedEntries);

        int size = Math.max(Math.min(Math.max(MIN_SLOTS, configuredSlots), MAX_LOADED_STACKS), highestSlot + 1);
        List<ItemStack> loaded = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++)
            loaded.add(ItemStack.EMPTY);
        List<ItemStack> unplaced = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.slot() != NO_SLOT && loaded.get(entry.slot()).isEmpty())
                loaded.set(entry.slot(), entry.stack());
            else
                unplaced.add(entry.stack());
        }
        int searchFrom = 0;
        for (ItemStack stack : unplaced) {
            while (searchFrom < loaded.size() && !loaded.get(searchFrom).isEmpty())
                searchFrom++;
            if (searchFrom < loaded.size())
                loaded.set(searchFrom, stack);
            else
                loaded.add(stack); // grow rather than lose an item
        }
        stacks = NonNullList.of(ItemStack.EMPTY, loaded.toArray(new ItemStack[0]));
        onLoad();
    }

    private record Entry(int slot, ItemStack stack) {
    }
}
