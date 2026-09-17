package dev.wareworks.gametest;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.core.inventory.InventorySnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Counts every item of a GameTest by exact identity ({@link ItemKey}: item and components), for the item conservation
 * invariant ({@code docs/warehouse-system.md} §8): all inventories with an item capability (chests, hoppers), all station
 * buffers, all crane handling heads and all item entities inside the test bounds. Nothing is counted twice: stations and
 * docks are read through their own API, everything else through {@code Capabilities.ItemHandler.BLOCK} (single chests
 * only; the tests never merge chests).
 * <p>
 * A census reads every block position of the test bounds once, which is fine for tests (a few thousand lookups).
 */
final class ItemCensus {
    private ItemCensus() {
    }

    /** Positive counts per item key inside the test bounds. */
    static Map<ItemKey, Long> take(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AABB bounds = helper.getBounds();
        Map<ItemKey, Long> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(bounds.minX), Mth.floor(bounds.minY), Mth.floor(bounds.minZ),
                Mth.ceil(bounds.maxX) - 1, Mth.ceil(bounds.maxY) - 1, Mth.ceil(bounds.maxZ) - 1)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null)
                continue;
            if (be instanceof WarehouseStationBlockEntity station) {
                InventorySnapshot<ItemKey> buffer = station.bufferedItems();
                for (ItemKey key : buffer.keys())
                    add(counts, key, buffer.count(key));
            } else if (be instanceof StackerCraneBlockEntity crane) {
                for (HeldItems.Entry entry : crane.heldItems().entries())
                    add(counts, entry.key(), entry.count());
            } else {
                IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos.immutable(), null);
                if (handler == null)
                    continue;
                for (int slot = 0; slot < handler.getSlots(); slot++)
                    add(counts, handler.getStackInSlot(slot));
            }
        }
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM))
            add(counts, entity.getItem());
        return counts;
    }

    /** Fails the test unless the census equals {@code expected} exactly (no missing, extra or changed keys). */
    static void assertEquals(GameTestHelper helper, Map<ItemKey, Long> expected, String context) {
        Map<ItemKey, Long> actual = take(helper);
        if (!actual.equals(expected))
            helper.fail("item conservation violated (" + context + "): expected " + describe(expected) + " but found "
                    + describe(actual));
    }

    /** A mutable expectation map from key/count pairs ({@code ItemKey, Number, ItemKey, Number, ...}). */
    static Map<ItemKey, Long> of(Object... keyCounts) {
        Map<ItemKey, Long> expected = new HashMap<>();
        for (int i = 0; i + 1 < keyCounts.length; i += 2)
            add(expected, (ItemKey) keyCounts[i], ((Number) keyCounts[i + 1]).longValue());
        return expected;
    }

    /** Adds {@code delta} (may be negative) to {@code key} in an expectation map, dropping zero counts. */
    static void change(Map<ItemKey, Long> expected, ItemKey key, long delta) {
        add(expected, key, delta);
    }

    private static void add(Map<ItemKey, Long> counts, ItemStack stack) {
        if (!stack.isEmpty())
            add(counts, ItemKey.of(stack), stack.getCount());
    }

    private static void add(Map<ItemKey, Long> counts, ItemKey key, long amount) {
        long value = counts.getOrDefault(key, 0L) + amount;
        if (value == 0)
            counts.remove(key);
        else
            counts.put(key, value);
    }

    private static String describe(Map<ItemKey, Long> counts) {
        Map<String, Long> sorted = new TreeMap<>();
        counts.forEach((key, count) -> sorted.put(key.toString(), count));
        return sorted.toString();
    }
}
