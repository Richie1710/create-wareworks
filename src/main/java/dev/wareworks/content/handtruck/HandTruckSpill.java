package dev.wareworks.content.handtruck;

import java.util.ArrayList;
import java.util.List;

import dev.wareworks.Wareworks;
import dev.wareworks.registry.WareworksDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a loaded hand truck into plain stacks (issue #37, ADR-070): what it holds, the container's block item and the
 * empty truck. This is how a loaded truck that left a player anyway — thrown out of a screen with a full inventory, a
 * death, an item entity loaded from an old save — ends: tipped over, and nothing in it is lost or doubled.
 * <p>
 * The contents are read the way chunk loading would read them: the carried data is loaded into a block entity of the
 * carried type ({@code BlockEntity#loadStatic}) that is never added to a level, and its {@link Container} slots are
 * copied. A container with an unrolled loot table holds no items yet, and its block item carries vanilla's own
 * {@code container_loot} component instead, which rolls the loot the first time it is opened after being placed — a
 * table and a seed, never stored items. The block item gets the custom name back as well, and never vanilla's
 * {@code container} or {@code block_entity_data}, which would make it a placeable full chest.
 */
public final class HandTruckSpill {
    private HandTruckSpill() {
    }

    /**
     * Every stack a loaded truck turns into: contents, block item, empty truck. For anything that is not a loaded truck,
     * just a copy of it.
     */
    public static List<ItemStack> stacks(ItemStack truck, HolderLookup.Provider registries) {
        HandTruckLoad load = HandTruckItem.load(truck);
        List<ItemStack> stacks = new ArrayList<>();
        if (load == null) {
            stacks.add(truck.copy());
            return stacks;
        }
        BlockEntity be = carried(load, registries);
        if (be == null) {
            Wareworks.LOGGER.error("A hand truck carried {} with a block entity type that no longer exists ({}); "
                    + "its contents are lost", load.state(), load.blockEntityType());
        } else if (be instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty())
                    stacks.add(stack.copy());
            }
        }
        ItemStack block = new ItemStack(load.state().getBlock());
        if (!block.isEmpty()) {
            if (be instanceof Nameable nameable && nameable.getCustomName() != null)
                block.set(DataComponents.CUSTOM_NAME, nameable.getCustomName());
            if (be instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null)
                block.set(DataComponents.CONTAINER_LOOT, new SeededContainerLoot(randomizable.getLootTable(),
                        randomizable.getLootTableSeed()));
            stacks.add(block);
        }
        ItemStack empty = truck.copy();
        empty.remove(WareworksDataComponents.HAND_TRUCK_LOAD.get());
        stacks.add(empty);
        return stacks;
    }

    /** What the carried container holds, item by item; empty when its type no longer exists. */
    public static List<ItemStack> contents(HandTruckLoad load, HolderLookup.Provider registries) {
        List<ItemStack> contents = new ArrayList<>();
        if (carried(load, registries) instanceof Container container)
            for (int slot = 0; slot < container.getContainerSize(); slot++)
                if (!container.getItem(slot).isEmpty())
                    contents.add(container.getItem(slot).copy());
        return contents;
    }

    /** Tips {@code truck} over at {@code position}: every stack of {@link #stacks} as an item entity there. */
    public static void spillInto(Level level, Vec3 position, ItemStack truck) {
        for (ItemStack stack : stacks(truck, level.registryAccess())) {
            ItemEntity entity = new ItemEntity(level, position.x, position.y, position.z, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
    }

    /**
     * The carried block entity, loaded from the truck's data and never added to a level. A randomizable container's
     * loot stays unrolled: rolling needs a level, and this one has none.
     */
    private static BlockEntity carried(HandTruckLoad load, HolderLookup.Provider registries) {
        CompoundTag tag = load.dataCopy();
        tag.putString("id", load.blockEntityType().toString());
        return BlockEntity.loadStatic(BlockPos.ZERO, load.state(), tag, registries);
    }
}
