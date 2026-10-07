package dev.wareworks.gametest;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;

import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.registry.WareworksEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Counts every item of a GameTest by exact identity ({@link ItemKey}: item and components), for the item conservation
 * invariant ({@code docs/warehouse-system.md} §8): all inventories with an item capability (chests, hoppers, depots), all
 * station buffers, all crane handling heads, the claws of Create mechanical arms and all item entities inside the test
 * bounds. Nothing is counted twice: stations and docks are read through their own API, arms through their save data
 * ({@link MechanicalArmFixture#heldItem}), everything else through {@code Capabilities.ItemHandler.BLOCK} — where the
 * second half of a <b>double</b> chest is skipped, because both halves answer the same handler — but only while the half
 * that is counted lies inside the bounds itself ({@link #isSecondChestHalf}).
 * <p>
 * <b>A Create package counts as its contents and the box itself counts as nothing</b> (M26), wherever it turns up — in
 * an inventory, in a station buffer, on a handling head, in an arm's claw, or lying in the world as a
 * {@link PackageEntity}, which is a {@code LivingEntity} and therefore invisible to the item-entity sweep. That is the
 * only rule under which packing and unpacking conserve items: a Packager builds the box out of nothing and destroys it
 * again on the way in, so counting the box would report 64 iron vanishing into one {@code create:package}. The
 * expectation builders ({@link #of}, {@link #change}) apply the same rule, so a test states what it fed in the terms it
 * fed it and the census agrees. See {@link #add(Map, ItemStack, long)}.
 * <p>
 * <b>A pallet counts as its contents and the pallet itself as nothing</b> (M28): the load of a broken rack bay lies in
 * the world as one {@link PalletEntity}, which is neither an {@code ItemEntity} nor a block with an item capability,
 * so without this branch a broken brass bay could delete 65 536 items while every conservation test reported PASS. A
 * pallet is a new <b>carrier</b>, exactly as a package is, and not a fifth place an item can be
 * ({@code docs/warehouse-system.md} §8).
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
        BoundingBox box = new BoundingBox(Mth.floor(bounds.minX), Mth.floor(bounds.minY), Mth.floor(bounds.minZ),
                Mth.ceil(bounds.maxX) - 1, Mth.ceil(bounds.maxY) - 1, Mth.ceil(bounds.maxZ) - 1);
        Map<ItemKey, Long> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
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
            } else if (be instanceof ArmBlockEntity arm) {
                add(counts, MechanicalArmFixture.heldItem(arm, level.registryAccess()));
            } else {
                // Both halves of a double chest answer the <b>same</b> 54-slot handler, so only one of them may be counted
                // (M18, issue #13, which needs a real double chest to prove that a collecting port never reads an
                // inventory its own aisle already indexes). The right half is the one skipped; a single chest is counted
                // as it always was.
                if (isSecondChestHalf(level.getBlockState(pos), pos, box))
                    continue;
                IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos.immutable(), null);
                if (handler == null)
                    continue;
                for (int slot = 0; slot < handler.getSlots(); slot++)
                    add(counts, handler.getStackInSlot(slot));
            }
        }
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM))
            add(counts, entity.getItem());
        // A package lying in the world is never an ItemEntity: NeoForge swaps it for a PackageEntity, a LivingEntity
        // the sweep above cannot see (Item#hasCustomEntity, NeoForgeEventHandler#onEntityJoinWorld). That swap is
        // deferred by one server task, so a test that drops a package must let a tick pass before it counts again.
        for (PackageEntity entity : helper.getEntities(AllEntityTypes.PACKAGE.get()))
            add(counts, entity.getBox());
        // The load of a broken rack bay: one entity carrying one item type and a count, which no sweep above can see
        // (M28). It is read through the entity's own API rather than its item capability, so that a census never
        // depends on a capability registration being present.
        for (PalletEntity pallet : helper.getEntities(WareworksEntityTypes.PALLET.get()))
            pallet.carriedKey().ifPresent(key -> add(counts, key, pallet.carriedCount()));
        return counts;
    }

    /**
     * Whether {@code state} at {@code pos} is the half of a double chest whose contents the other half already reports —
     * which is only true when that other half is inside {@code box} and is really visited.
     * <p>
     * A double chest straddling the census boundary with its counted half outside would otherwise vanish from the census
     * entirely, and an item lost or duplicated inside it would stop failing the run (M18 review). That is exactly the
     * boundary a scenario puts a player's machine on.
     */
    private static boolean isSecondChestHalf(BlockState state, BlockPos pos, BoundingBox box) {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) != ChestType.RIGHT)
            return false;
        return box.isInside(pos.relative(ChestBlock.getConnectedDirection(state)));
    }

    /** Fails the test unless the census equals {@code expected} exactly (no missing, extra or changed keys). */
    static void assertEquals(GameTestHelper helper, Map<ItemKey, Long> expected, String context) {
        Map<ItemKey, Long> actual = take(helper);
        if (!actual.equals(expected))
            helper.fail("item conservation violated (" + context + "): expected " + describe(expected) + " but found "
                    + describe(actual));
    }

    /**
     * A mutable expectation map from key/count pairs ({@code ItemKey, Number, ItemKey, Number, ...}). A package key
     * contributes its contents, exactly as the census counts it.
     */
    static Map<ItemKey, Long> of(Object... keyCounts) {
        Map<ItemKey, Long> expected = new HashMap<>();
        for (int i = 0; i + 1 < keyCounts.length; i += 2)
            add(expected, (ItemKey) keyCounts[i], ((Number) keyCounts[i + 1]).longValue());
        return expected;
    }

    /**
     * Adds {@code delta} (may be negative) to {@code key} in an expectation map, dropping zero counts. A package key
     * again contributes its contents, so feeding one box of 64 iron changes the expectation by 64 iron.
     */
    static void change(Map<ItemKey, Long> expected, ItemKey key, long delta) {
        add(expected, key, delta);
    }

    private static void add(Map<ItemKey, Long> counts, ItemStack stack) {
        add(counts, stack, stack.getCount());
    }

    /**
     * Counts {@code copies} items of {@code stack}'s identity — or, when it is a Create package, the contents of
     * {@code copies} such boxes, because the box counts as nothing. {@code copies} may be negative (an expectation
     * being reduced).
     * <p>
     * The contents are read from the {@code create:package_contents} component, <b>never</b> through
     * {@link PackageItem#getContents}: that fills a fixed nine-slot {@code ItemStackHandler} from every slot the
     * component declares ({@code ItemHelper.fillItemStackHandler}), the component permits 256
     * ({@code ItemContainerContents.MAX_SIZE}), and {@code ItemStackHandler.setStackInSlot} throws out of range. A
     * crafted package with ten slots would make the census crash the run instead of reporting on it.
     * <p>
     * A package inside a package is counted the same way. The recursion always terminates: a data component is an
     * immutable value and cannot contain itself, and its depth is bounded by what NBT would accept.
     */
    private static void add(Map<ItemKey, Long> counts, ItemStack stack, long copies) {
        if (stack.isEmpty() || copies == 0)
            return;
        if (!PackageItem.isPackage(stack)) {
            put(counts, ItemKey.of(stack), copies);
            return;
        }
        stack.getOrDefault(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.EMPTY)
                .nonEmptyStream()
                .forEach(held -> add(counts, held, copies * held.getCount()));
    }

    /** Counts {@code amount} items of {@code key}, a package again as its contents. */
    private static void add(Map<ItemKey, Long> counts, ItemKey key, long amount) {
        if (key.getItem() instanceof PackageItem) {
            add(counts, key.toStack(), amount);
            return;
        }
        put(counts, key, amount);
    }

    private static void put(Map<ItemKey, Long> counts, ItemKey key, long amount) {
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
