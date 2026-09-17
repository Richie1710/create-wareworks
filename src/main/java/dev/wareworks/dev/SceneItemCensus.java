package dev.wareworks.dev;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.core.inventory.InventorySnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Counts every item of a scene by exact identity ({@link ItemKey}: item and data components), for the item conservation
 * invariant of {@code docs/warehouse-system.md} §8. The dev-harness counterpart of {@code gametest.ItemCensus}, which
 * needs a {@code GameTestHelper}.
 * <p>
 * Counted once each: all inventories reachable through {@code Capabilities.ItemHandler.BLOCK} (chests, hoppers), all
 * warehouse station buffers, all stacker crane handling heads and all item entities inside the box. Server thread only.
 * <p>
 * A census reads every block position of the box once, so scenarios keep the box to their own aisle. The box is
 * inflated around that aisle and therefore normally spans several chunks: {@link #take} <b>refuses</b> to count while
 * any of them is missing instead of silently reading an unloaded chest as empty, which would report a conservation
 * violation for a chunk-loading race. A scenario waits for {@link #isFullyLoaded} before every census.
 */
final class SceneItemCensus {
    private SceneItemCensus() {
    }

    /**
     * Whether every chunk the census box touches is loaded. The aisle's own chunk is not enough: a box of an aisle at
     * a chunk border reaches the neighbouring chunks, and the rack wall and its inventories can sit in those.
     */
    static boolean isFullyLoaded(ServerLevel level, AABB box) {
        int y = Mth.floor(box.minY);
        int firstChunkX = SectionPos.blockToSectionCoord(Mth.floor(box.minX));
        int lastChunkX = SectionPos.blockToSectionCoord(Mth.ceil(box.maxX) - 1);
        int firstChunkZ = SectionPos.blockToSectionCoord(Mth.floor(box.minZ));
        int lastChunkZ = SectionPos.blockToSectionCoord(Mth.ceil(box.maxZ) - 1);
        for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++)
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++)
                if (!level.isLoaded(new BlockPos(SectionPos.sectionToBlockCoord(chunkX), y,
                        SectionPos.sectionToBlockCoord(chunkZ))))
                    return false;
        return true;
    }

    /**
     * Positive counts per item key inside {@code box}.
     *
     * @throws VisualTestException when a position of the box is not loaded ({@link #isFullyLoaded})
     */
    static Map<ItemKey, Long> take(ServerLevel level, AABB box) {
        Map<ItemKey, Long> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.ceil(box.maxX) - 1, Mth.ceil(box.maxY) - 1, Mth.ceil(box.maxZ) - 1)) {
            if (!level.isLoaded(pos))
                throw new VisualTestException("the census box " + box + " reaches the unloaded position " + pos
                        + "; a census must wait until the whole box is loaded");
            BlockEntity be = level.getBlockEntity(pos);
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
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box))
            add(counts, entity.getItem());
        return counts;
    }

    /**
     * Fails the run unless the census of {@code box} equals {@code expected} exactly, and logs one PASS or FAIL line
     * naming {@code step}. This is the evidence of the robustness run: the logs, not the screenshots.
     *
     * @throws VisualTestException when an item was lost, duplicated or changed identity
     */
    static void assertEquals(ServerLevel level, AABB box, Map<ItemKey, Long> expected, String step) {
        Map<ItemKey, Long> actual = take(level, box);
        if (actual.equals(expected)) {
            VisualTestHarness.LOGGER.info(VisualTestHarness.PREFIX + "robustness PASS: {} (items {})", step,
                    describe(actual));
            return;
        }
        String reason = step + ": expected " + describe(expected) + " but found " + describe(actual);
        VisualTestHarness.LOGGER.error(VisualTestHarness.PREFIX + "robustness FAIL: {}", reason);
        throw new VisualTestException("item conservation violated, " + reason);
    }

    /** A stable, readable rendering of a census, sorted by item key. */
    static String describe(Map<ItemKey, Long> counts) {
        Map<String, Long> sorted = new TreeMap<>();
        counts.forEach((key, count) -> sorted.put(key.toString(), count));
        return sorted.toString();
    }

    /** A copy of {@code counts} with {@code delta} added to {@code key} (zero counts are dropped). */
    static Map<ItemKey, Long> plus(Map<ItemKey, Long> counts, ItemKey key, long delta) {
        Map<ItemKey, Long> result = new HashMap<>(counts);
        add(result, key, delta);
        return result;
    }

    /** A copy of {@code counts} with every entry of {@code added} applied. */
    static Map<ItemKey, Long> plusAll(Map<ItemKey, Long> counts, List<ItemStack> added) {
        Map<ItemKey, Long> result = new HashMap<>(counts);
        for (ItemStack stack : added)
            add(result, stack);
        return result;
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
}
