package dev.wareworks.content.crane.head;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.StorageMember;
import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseDeliveryStationBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Resolves aisle locations to {@link TransferContext}s and implements the three kinds.
 * <p>
 * {@link #resolve} never loads a chunk: a rack position (or, for storage, the attached inventory position) that is not
 * loaded gives {@link Status#UNLOADED}, so the crane waits and retries; a position outside the aisle geometry, without
 * an aligned member of the expected kind or without an attached inventory gives {@link Status#MISSING}, so the crane
 * reroutes or aborts ({@code docs/warehouse-system.md} §8). Cost: at most two {@code isLoaded} checks, one block entity
 * lookup and a capability cache read.
 */
public final class TransferContexts {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Outcome of {@link #resolve}. */
    public enum Status {
        /** The location can be used now. */
        AVAILABLE,
        /** The location or its inventory is in a chunk that is not loaded; wait. */
        UNLOADED,
        /** The location is gone, misaligned, of another kind or has no inventory. */
        MISSING
    }

    /**
     * A resolved location: exactly {@link Status#AVAILABLE} carries a context.
     *
     * @param status  outcome
     * @param context the context of an available location
     */
    public record Resolution(Status status, Optional<TransferContext> context) {
        public static final Resolution UNLOADED = new Resolution(Status.UNLOADED, Optional.empty());
        public static final Resolution MISSING = new Resolution(Status.MISSING, Optional.empty());

        public Resolution {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(context, "context");
            if ((status == Status.AVAILABLE) != context.isPresent())
                throw new IllegalArgumentException("exactly an available location has a context");
        }

        public static Resolution available(TransferContext context) {
            return new Resolution(Status.AVAILABLE, Optional.of(context));
        }

        public boolean isAvailable() {
            return status == Status.AVAILABLE;
        }
    }

    private TransferContexts() {
    }

    /**
     * The context of the member of {@code kind} at {@code rack} of {@code layout} (server). The member must stand at the
     * rack position with the facing rule of its kind ({@link WarehouseMember#isAlignedWith}).
     */
    public static Resolution resolve(Level level, AisleLayout layout, RackPosition rack, LocationKind kind) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(kind, "kind");
        if (!layout.geometry().contains(rack))
            return Resolution.MISSING;
        BlockPos pos = layout.rackPos(rack);
        if (!level.isLoaded(pos))
            return Resolution.UNLOADED;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof WarehouseMember member) || blockEntity.isRemoved() || member.locationKind() != kind
                || !member.isAlignedWith(layout, rack.side()))
            return Resolution.MISSING;
        return switch (kind) {
            case STORAGE -> {
                if (!(member instanceof StorageMember storage))
                    yield Resolution.MISSING;
                if (!level.isLoaded(storage.attachedPos()))
                    yield Resolution.UNLOADED;
                Optional<IItemHandler> handler = storage.attachedHandler();
                yield handler.isPresent() ? Resolution.available(ofHandler(level, pos, handler.get()))
                        : Resolution.MISSING;
            }
            case INPUT -> blockEntity instanceof WarehouseInputBlockEntity input
                    ? Resolution.available(ofInput(level, input)) : Resolution.MISSING;
            // Warehouse output, terminal (ADR-018) and production station (ADR-024) are all stations the crane
            // delivers into, so one context serves all three; only the kind it reports differs.
            case OUTPUT, PRODUCTION -> blockEntity instanceof WarehouseDeliveryStationBlockEntity delivery
                    ? Resolution.available(ofDelivery(level, delivery, kind)) : Resolution.MISSING;
            // A warehouse stock keeper holds no items at all, so no job can ever name it as a source or a target
            // (M15): a crane that somehow asked for one is told the member is not there, which is exactly true.
            case KEEPER -> Resolution.MISSING;
        };
    }

    /** A storage context over a live item handler; {@code position} is where stray items are spilled. */
    public static TransferContext ofHandler(Level level, BlockPos position, IItemHandler handler) {
        return new HandlerContext(level, position.immutable(), handler);
    }

    /** An input station context (extract and put back). */
    public static TransferContext ofInput(Level level, WarehouseInputBlockEntity station) {
        return new InputContext(level, station.getBlockPos(), station);
    }

    /** An output-style station context (insert only): warehouse output or warehouse terminal. */
    public static TransferContext ofOutput(Level level, WarehouseDeliveryStationBlockEntity station) {
        return ofDelivery(level, station, LocationKind.OUTPUT);
    }

    /**
     * A delivery station context (insert only): warehouse output, warehouse terminal (both {@link LocationKind#OUTPUT})
     * or warehouse production station ({@link LocationKind#PRODUCTION}).
     *
     * @param kind the kind the context reports, which decides how the crane treats a full station: an output is waited
     *             at, a production station's leftovers are rerouted back into storage (ADR-024)
     */
    public static TransferContext ofDelivery(Level level, WarehouseDeliveryStationBlockEntity station,
            LocationKind kind) {
        return new DeliveryContext(level, station.getBlockPos(), station, kind);
    }

    /**
     * Drops {@code stack} at {@code pos}, split into stacks of at most the item's max stack size first, so no oversized
     * item entity is created. Uses {@link Containers#dropItemStack}, which ignores {@code doTileDrops}.
     *
     * @return the number of dropped items
     */
    public static int spillAt(Level level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty())
            return 0;
        int left = stack.getCount();
        int perStack = Math.max(1, stack.getMaxStackSize());
        while (left > 0) {
            int part = Math.min(left, perStack);
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack.copyWithCount(part));
            left -= part;
        }
        return stack.getCount();
    }

    private static int accepted(int offered, ItemStack remainder) {
        return offered - Math.min(offered, Math.max(0, remainder.getCount()));
    }

    /** The item handler of the inventory behind a warehouse interface. */
    private record HandlerContext(Level level, BlockPos position, IItemHandler handler) implements TransferContext {
        @Override
        public LocationKind kind() {
            return LocationKind.STORAGE;
        }

        /**
         * Extracts slot by slot. A real extraction that fails part way (a foreign inventory throws) returns what the
         * earlier slot calls really handed out, so those items are never lost; simulations rethrow.
         */
        @Override
        public ItemStack extract(ItemKey key, int maxAmount, boolean simulate) {
            int limit = Math.min(maxAmount, key.getMaxStackSize());
            if (limit <= 0)
                return ItemStack.EMPTY;
            int taken = 0;
            try {
                int slots = Math.max(0, handler.getSlots());
                for (int slot = 0; slot < slots && taken < limit; slot++) {
                    if (!key.matches(handler.getStackInSlot(slot)))
                        continue;
                    ItemStack got = handler.extractItem(slot, limit - taken, simulate);
                    if (got.isEmpty())
                        continue;
                    if (!key.matches(got)) {
                        if (!simulate)
                            giveBack(slot, got); // a misbehaving inventory handed out something else
                        continue;
                    }
                    int accepted = Math.min(got.getCount(), limit - taken);
                    taken += accepted; // counted before anything is given back, so a later failure keeps them
                    if (!simulate && got.getCount() > accepted)
                        giveBack(slot, got.copyWithCount(got.getCount() - accepted)); // handed out more than asked
                }
            } catch (RuntimeException e) {
                if (simulate)
                    throw e;
                LOGGER.warn("Inventory at {} failed while extracting {}; keeping the {} items it handed out before",
                        position, key, taken, e);
            }
            return key.toStack(taken);
        }

        /** Gives a stack back; whatever the inventory does not take (also after a failing call) is spilled, never lost. */
        private void giveBack(int slot, ItemStack stack) {
            ItemStack rest = stack;
            try {
                rest = handler.insertItem(slot, stack.copy(), false);
            } catch (RuntimeException e) {
                LOGGER.warn("Inventory at {} failed while taking back {}", position, stack, e);
            }
            if (!rest.isEmpty())
                rest = insertSlotBySlot(rest.copy(), false);
            if (!rest.isEmpty())
                spill(rest);
        }

        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            if (stack.isEmpty())
                return ItemStack.EMPTY;
            return insertSlotBySlot(stack.copy(), simulate);
        }

        /**
         * {@link ItemHandlerHelper#insertItemStacked} with the same slot order (an unstackable item tries every slot, a
         * stackable one the slots holding the same item first, then the empty slots), one handler call per slot. A real
         * insertion that fails part way (a foreign inventory throws) stops and returns exactly what did not go in, so
         * items already inserted are never counted as held; simulations rethrow.
         */
        private ItemStack insertSlotBySlot(ItemStack stack, boolean simulate) {
            ItemStack rest = stack;
            try {
                int slots = Math.max(0, handler.getSlots());
                if (!rest.isStackable()) {
                    for (int slot = 0; slot < slots && !rest.isEmpty(); slot++)
                        rest = handler.insertItem(slot, rest, simulate);
                    return rest;
                }
                for (int slot = 0; slot < slots && !rest.isEmpty(); slot++) {
                    if (ItemStack.isSameItemSameComponents(handler.getStackInSlot(slot), rest))
                        rest = handler.insertItem(slot, rest, simulate);
                }
                for (int slot = 0; slot < slots && !rest.isEmpty(); slot++) {
                    if (handler.getStackInSlot(slot).isEmpty())
                        rest = handler.insertItem(slot, rest, simulate);
                }
            } catch (RuntimeException e) {
                if (simulate)
                    throw e;
                LOGGER.warn("Inventory at {} failed while inserting {}; {} did not go in", position, stack, rest, e);
            }
            return rest;
        }

        /**
         * Sum of simulated extractions per slot. A slot that holds more than one stack (drawer-like) counts one stack,
         * so the result may be lower than the real total, never higher: planning then carries less, never promises
         * items that are not there.
         */
        @Override
        public int simulateExtract(ItemKey key, int maxAmount) {
            int total = 0;
            int slots = Math.max(0, handler.getSlots());
            for (int slot = 0; slot < slots && total < maxAmount; slot++) {
                if (!key.matches(handler.getStackInSlot(slot)))
                    continue;
                ItemStack got = handler.extractItem(slot, maxAmount - total, true);
                if (key.matches(got))
                    total += Math.min(got.getCount(), maxAmount - total);
            }
            return total;
        }

        @Override
        public int simulateInsert(ItemKey key, int amount) {
            if (amount <= 0)
                return 0;
            return accepted(amount, ItemHandlerHelper.insertItemStacked(handler, key.toStack(amount), true));
        }

        @Override
        public void spill(ItemStack stack) {
            spillAt(level, position, stack);
        }
    }

    /** The buffer of a warehouse input through its crane API. */
    private record InputContext(Level level, BlockPos position, WarehouseInputBlockEntity station)
            implements TransferContext {
        @Override
        public LocationKind kind() {
            return LocationKind.INPUT;
        }

        @Override
        public ItemStack extract(ItemKey key, int maxAmount, boolean simulate) {
            return station.extract(key, maxAmount, simulate);
        }

        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            return station.insert(stack, simulate);
        }

        @Override
        public int simulateExtract(ItemKey key, int maxAmount) {
            return (int) Math.max(0L, Math.min(maxAmount, station.countOf(key)));
        }

        @Override
        public int simulateInsert(ItemKey key, int amount) {
            if (amount <= 0)
                return 0;
            return accepted(amount, station.insert(key.toStack(amount), true));
        }

        @Override
        public void spill(ItemStack stack) {
            spillAt(level, position, stack);
        }
    }

    /** The buffer of a warehouse output, terminal or production station through its crane API: insert only. */
    private record DeliveryContext(Level level, BlockPos position, WarehouseDeliveryStationBlockEntity station,
            LocationKind kind) implements TransferContext {
        @Override
        public LocationKind kind() {
            return kind;
        }

        @Override
        public ItemStack extract(ItemKey key, int maxAmount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            return station.insert(stack, simulate);
        }

        @Override
        public int simulateExtract(ItemKey key, int maxAmount) {
            return 0;
        }

        @Override
        public int simulateInsert(ItemKey key, int amount) {
            if (amount <= 0)
                return 0;
            return accepted(amount, station.insert(key.toStack(amount), true));
        }

        @Override
        public void spill(ItemStack stack) {
            spillAt(level, position, stack);
        }
    }
}
