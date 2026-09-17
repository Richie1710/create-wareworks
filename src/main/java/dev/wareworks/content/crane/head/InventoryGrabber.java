package dev.wareworks.content.crane.head;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.CapacityMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The MVP handling head: a grabber that carries item stacks between item handlers ({@code docs/stacker-crane.md} §6).
 * <p>
 * <b>Holding.</b> Items are kept as item keys with {@code int} amounts, not as {@code ItemStack}s, so amounts above one
 * stack (several {@code grabberStacks}) and above 99 need no special handling. Transfers split into stacks of at most the
 * key's max stack size.
 * <p>
 * <b>Transfers.</b> {@link #pick} extracts stack by stack with real calls until the amount is reached or the source gives
 * nothing, and verifies every extracted stack against the key; {@link #drop} inserts stack by stack until the target
 * refuses, and only the accepted amount leaves the head. Both loops are bounded ({@value #MAX_TRANSFER_CALLS} calls).
 * <p>
 * <b>Persistence.</b> {@code {Items: [{Item: <ItemKey>, Count: int}]}}; {@code ItemStack.save} is never called and
 * saving never throws. Loading never throws and is bounded against crafted data (block entity data on items,
 * {@code /data}, structures, schematics): at most {@value #MAX_LOADED_ENTRIES} keys and {@value #MAX_LOADED_ITEMS} items
 * in total, far above any legitimate load (the carry limit is at most 1728).
 * <p>
 * Server thread only; clients receive a bounded summary of item ids from the crane.
 */
public final class InventoryGrabber implements HandlingHead {
    /** Upper bound for the distinct keys one {@link #load} creates. */
    public static final int MAX_LOADED_ENTRIES = 16;
    /** Upper bound for the items one {@link #load} creates. */
    public static final int MAX_LOADED_ITEMS = 4096;
    /** Upper bound for inventory calls of one pick or drop. */
    private static final int MAX_TRANSFER_CALLS = 1024;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ITEMS = "Items";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";

    private final Map<ItemKey, Integer> held = new LinkedHashMap<>();
    private final Runnable onChanged;

    /** @param onChanged called after every change through a transfer, spill or clear (not on {@link #load}) */
    public InventoryGrabber(Runnable onChanged) {
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    /**
     * The carry limit from the server config: {@code min(grabberStacks · maxStackSize, grabberMaxItems)}
     * ({@code docs/warehouse-system.md} §7.1). Used by the grabber and by the controller's planner.
     */
    public static int carryLimitFor(ItemKey key) {
        return CapacityMath.carryLimit(Math.max(1, key.getMaxStackSize()), Math.max(1, WareworksConfig.grabberStacks()),
                Math.max(1, WareworksConfig.grabberMaxItems()));
    }

    @Override
    public int carryLimit(ItemKey key) {
        return carryLimitFor(Objects.requireNonNull(key, "key"));
    }

    @Override
    public int pick(TransferContext source, ItemKey key, int amount) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");
        int picked = 0;
        for (int calls = 0; picked < amount && calls < MAX_TRANSFER_CALLS; calls++) {
            int want = Math.min(amount - picked, key.getMaxStackSize());
            ItemStack taken;
            try {
                taken = source.extract(key, want, false);
            } catch (RuntimeException e) {
                // A foreign inventory threw: keep what was really taken so far, so head and job stay in step.
                LOGGER.warn("Inventory at {} failed while extracting {}", source.position(), key, e);
                break;
            }
            if (taken.isEmpty())
                break;
            if (!key.matches(taken)) {
                returnOrSpill(source, taken);
                break;
            }
            if (taken.getCount() > want)
                returnOrSpill(source, taken.split(taken.getCount() - want));
            add(key, taken.getCount());
            picked += taken.getCount();
        }
        if (picked > 0)
            onChanged.run();
        return picked;
    }

    @Override
    public int drop(TransferContext target, ItemKey key, int amount) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(key, "key");
        int available = Math.min(amount, count(key));
        int delivered = 0;
        for (int calls = 0; delivered < available && calls < MAX_TRANSFER_CALLS; calls++) {
            int chunk = Math.min(available - delivered, key.getMaxStackSize());
            ItemStack remainder;
            try {
                remainder = target.insert(key.toStack(chunk), false);
            } catch (RuntimeException e) {
                // A foreign inventory threw: count only what was really accepted before.
                LOGGER.warn("Inventory at {} failed while inserting {}", target.position(), key, e);
                break;
            }
            int accepted = chunk - Math.min(chunk, Math.max(0, remainder.getCount()));
            if (accepted <= 0)
                break;
            delivered += accepted;
            if (accepted < chunk)
                break; // the target is full
        }
        if (delivered > 0) {
            remove(key, delivered);
            onChanged.run();
        }
        return delivered;
    }

    @Override
    public HeldItems held() {
        if (held.isEmpty())
            return HeldItems.EMPTY;
        List<HeldItems.Entry> entries = new ArrayList<>(held.size());
        held.forEach((key, count) -> entries.add(new HeldItems.Entry(key, count)));
        return new HeldItems(entries);
    }

    @Override
    public int count(ItemKey key) {
        return held.getOrDefault(Objects.requireNonNull(key, "key"), 0);
    }

    @Override
    public boolean isEmpty() {
        return held.isEmpty();
    }

    @Override
    public long spill(Level level, BlockPos pos, Predicate<ItemKey> which) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(which, "which");
        long dropped = 0;
        for (Map.Entry<ItemKey, Integer> entry : List.copyOf(held.entrySet())) {
            if (!which.test(entry.getKey()))
                continue;
            held.remove(entry.getKey());
            dropped += TransferContexts.spillAt(level, pos, entry.getKey().toStack(entry.getValue()));
        }
        if (dropped > 0)
            onChanged.run();
        return dropped;
    }

    @Override
    public void clear() {
        if (held.isEmpty())
            return;
        held.clear();
        onChanged.run();
    }

    @Override
    public CompoundTag save(HolderLookup.Provider registries) {
        ListTag items = new ListTag();
        for (Map.Entry<ItemKey, Integer> entry : held.entrySet()) {
            try {
                Tag keyTag = entry.getKey().save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty()) {
                    LOGGER.warn("Could not save held items {} x{}", entry.getKey(), entry.getValue());
                    continue;
                }
                CompoundTag item = new CompoundTag();
                item.put(ITEM, keyTag);
                item.putInt(COUNT, entry.getValue());
                items.add(item);
            } catch (RuntimeException e) {
                LOGGER.warn("Could not save held items {}", entry.getKey(), e);
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.put(ITEMS, items);
        return tag;
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        held.clear();
        ListTag list = tag.getList(ITEMS, Tag.TAG_COMPOUND);
        int budget = MAX_LOADED_ITEMS;
        boolean truncated = false;
        for (int i = 0; i < list.size(); i++) {
            if (held.size() >= MAX_LOADED_ENTRIES || budget == 0) {
                truncated = true; // the rest is not even decoded
                break;
            }
            CompoundTag item = list.getCompound(i);
            try {
                int count = item.getInt(COUNT);
                if (count <= 0)
                    continue;
                Optional<ItemKey> key = ItemKey.load(registries, item.get(ITEM));
                if (key.isEmpty())
                    continue;
                int accepted = Math.min(count, budget);
                truncated |= accepted < count;
                budget -= accepted;
                held.merge(key.get(), accepted, InventoryGrabber::saturatedAdd);
            } catch (RuntimeException e) {
                LOGGER.warn("Skipping unreadable held items {}", item, e);
            }
        }
        if (truncated)
            LOGGER.warn("Handling head save holds more than {} item types or {} items; the rest was not loaded",
                    MAX_LOADED_ENTRIES, MAX_LOADED_ITEMS);
    }

    private void add(ItemKey key, int amount) {
        held.merge(key, amount, InventoryGrabber::saturatedAdd);
    }

    private void remove(ItemKey key, int amount) {
        int left = count(key) - amount;
        if (left > 0)
            held.put(key, left);
        else
            held.remove(key);
    }

    /** Gives a stack back to the source it came from; whatever does not fit is spilled there, never deleted. */
    private static void returnOrSpill(TransferContext source, ItemStack stack) {
        ItemStack rest;
        try {
            rest = source.insert(stack.copy(), false);
        } catch (RuntimeException e) {
            LOGGER.warn("Inventory at {} failed while taking back {}", source.position(), stack, e);
            rest = stack;
        }
        if (!rest.isEmpty()) {
            LOGGER.warn("Inventory at {} handed out {} that could not be given back; dropping it", source.position(),
                    rest);
            source.spill(rest);
        }
    }

    private static int saturatedAdd(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    @Override
    public String toString() {
        return "InventoryGrabber" + held;
    }
}
