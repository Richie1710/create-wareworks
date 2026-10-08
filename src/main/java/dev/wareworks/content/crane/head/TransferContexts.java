package dev.wareworks.content.crane.head;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.StorageMember;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseDeliveryStationBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.util.LogThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Resolves aisle locations to {@link TransferContext}s and implements the five kinds (the four item ones, plus the
 * fluid bay of M30 whose one real operation is a container exchange).
 * <p>
 * {@link #resolve} never loads a chunk: a rack position (or, for storage, the attached inventory position) that is not
 * loaded gives {@link Status#UNLOADED}, so the crane waits and retries; a position outside the aisle geometry, without
 * an aligned member of the expected kind or without an attached inventory gives {@link Status#MISSING}, so the crane
 * reroutes or aborts ({@code docs/warehouse-system.md} §8). Cost: at most two {@code isLoaded} checks, one block entity
 * lookup and a capability cache read.
 */
public final class TransferContexts {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Rate limit for the one line {@link FluidBayContext#insert} can log. Deliberately <b>static</b>, i.e. one line a
     * minute for the whole server rather than per bay: it reports that a caller reached a code path which by design has
     * no caller at all, so the first line is the whole message and a second bay saying the same thing adds nothing.
     */
    private static final LogThrottle FLUID_BAY_INSERT_LOG = new LogThrottle();

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
     * The context of the member of {@code kind} at {@code rack} of a whole warehouse (server): the same rule as
     * {@link #resolve(Level, BranchLayout, RackPosition, LocationKind)}, applied to the aisle the position names
     * (M21, ADR-033).
     * <p>
     * A position naming an aisle this warehouse does not have is {@link Resolution#MISSING}, whatever block lies where
     * the label used to point: a vanished aisle's label stands for no block at all, and the crane's existing
     * source-or-target-missing ladder is what handles it.
     */
    public static Resolution resolve(Level level, WarehouseLayout warehouse, RackPosition rack, LocationKind kind) {
        Objects.requireNonNull(warehouse, "warehouse");
        Objects.requireNonNull(rack, "rack");
        return warehouse.branchOf(rack).map(branch -> resolve(level, branch, rack, kind)).orElse(Resolution.MISSING);
    }

    /**
     * The context of the member of {@code kind} at {@code rack} of <b>one aisle</b> (server). The member must stand at
     * the rack position with the facing rule of its kind ({@link WarehouseMember#isAlignedWith}).
     * <p>
     * A position of <b>another aisle</b> is {@link Resolution#MISSING} here, whatever stands at the block this aisle
     * would read it as: a {@code BranchLayout} maps exactly one aisle, so a position of another one means nothing to
     * it. Callers that may see any aisle of a warehouse take
     * {@link #resolve(Level, WarehouseLayout, RackPosition, LocationKind)} instead.
     */
    public static Resolution resolve(Level level, BranchLayout layout, RackPosition rack, LocationKind kind) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(kind, "kind");
        // The branch is compared against the layout's own, and only the position against its size: AisleGeometry
        // describes one aisle and its RackPosition overload answers false for every position that is not on the first
        // one, which would make every rack of a bent warehouse missing (M21 part two, ADR-033).
        if (rack.branch() != layout.branch() || !layout.geometry().contains(rack.x(), rack.y()))
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
                // A fluid bay IS its own tank: it holds no items, offers no item handler and has nothing attached, so
                // the test below would answer MISSING for a location that is perfectly reachable. It gets a context of
                // its own, whose one real operation is the container exchange (M30, issue #21, D2). Its own position
                // was checked for loading above, so no second check is needed.
                if (blockEntity instanceof FluidBayBlockEntity bay)
                    yield Resolution.available(ofFluidBay(level, pos, bay));
                if (!level.isLoaded(storage.attachedPos()))
                    yield Resolution.UNLOADED;
                Optional<IItemHandler> handler = storage.attachedHandler();
                yield handler.isPresent() ? Resolution.available(ofHandler(level, pos, handler.get()))
                        : Resolution.MISSING;
            }
            case INPUT -> blockEntity instanceof WarehouseInputBlockEntity input
                    ? Resolution.available(ofInput(level, input)) : Resolution.MISSING;
            // A COLLECTING warehouse port is the one output station the crane takes items OUT of (M18, issue #13): it
            // resolves to the inventory behind the port instead of to the port's own insert-only buffer, so the crane
            // reaches through the port exactly as it reaches through a warehouse interface. Every other output — a
            // requesting or accepting port, and a warehouse terminal (ADR-018) — and a production station (ADR-024) are
            // stations the crane delivers into, so one context serves all of those; only the kind it reports differs.
            case OUTPUT, PRODUCTION -> {
                if (!(blockEntity instanceof WarehouseDeliveryStationBlockEntity delivery))
                    yield Resolution.MISSING;
                if (kind == LocationKind.OUTPUT && delivery instanceof WarehouseOutputBlockEntity port
                        && port.isCollecting())
                    yield resolveCollect(level, pos, port);
                yield Resolution.available(ofDelivery(level, delivery, kind));
            }
            // A warehouse stock keeper and a home point hold no items at all, so no job can ever name one as a source
            // or a target (M15, M21): a crane that somehow asked for one is told the member is not there, which is
            // exactly true.
            case KEEPER, HOME -> Resolution.MISSING;
        };
    }

    /**
     * The inventory behind a <b>collecting</b> warehouse port (M18, issue #13): {@link Status#UNLOADED} while that
     * position is not loaded, so the crane waits and retries, and {@link Status#MISSING} when nothing there offers an item
     * handler — the port is then treated exactly like a storage location whose chest a player broke.
     */
    private static Resolution resolveCollect(Level level, BlockPos pos, WarehouseOutputBlockEntity port) {
        if (!level.isLoaded(port.attachedPos()))
            return Resolution.UNLOADED;
        return port.attachedHandler().map(handler -> Resolution.available(ofCollect(level, pos, handler)))
                .orElse(Resolution.MISSING);
    }

    /** A storage context over a live item handler; {@code position} is where stray items are spilled. */
    public static TransferContext ofHandler(Level level, BlockPos position, IItemHandler handler) {
        return new HandlerContext(level, position.immutable(), handler);
    }

    /**
     * A collect context over the item handler behind a collecting warehouse port: <b>extract only</b> (M18, issue #13).
     *
     * @param position the port's own position, where stray items are spilled — never inside the player's machine
     */
    public static TransferContext ofCollect(Level level, BlockPos position, IItemHandler handler) {
        return new CollectContext(new HandlerContext(level, position.immutable(), handler));
    }

    /**
     * A fluid bay context: a storage location that holds <b>no items at all</b> and whose one real operation is the
     * container exchange (M30, issue #21).
     *
     * @param position the bay's own position, which is where the arm reaches in and where anything is spilled
     */
    public static TransferContext ofFluidBay(Level level, BlockPos position, FluidBayBlockEntity bay) {
        return new FluidBayContext(level, position.immutable(), bay);
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

    /**
     * The inventory behind a <b>collecting</b> warehouse port ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13):
     * the extract half of {@link HandlerContext} and <b>nothing else</b>.
     * <p>
     * Extraction is delegated, so a machine's own rules are respected exactly as a chest's are: slot by slot through the
     * item capability only, real results, over-delivered stacks given back, and a foreign inventory that throws never
     * throws into the crane. {@link #insert} <b>refuses everything</b> — it returns the stack unchanged — so nothing the
     * warehouse carries can ever be pushed back into a player's machine, whatever a reroute, a hold retry or a future
     * caller asks for. That is the structural half of "a collect job can never export" ({@code JobType#COLLECT} is the
     * other half), and it is also why a Create machine inserting into the same inventory is never fought over: the crane
     * only ever takes.
     *
     * @param inventory the delegate over the live handler, whose {@code position} is the <b>port's</b> position, so
     *                  anything that has to be spilled lands in the aisle and never inside the machine
     */
    private record CollectContext(HandlerContext inventory) implements TransferContext {
        @Override
        public LocationKind kind() {
            // The location IS an output station of the aisle; only what the crane does there differs.
            return LocationKind.OUTPUT;
        }

        @Override
        public BlockPos position() {
            return inventory.position();
        }

        @Override
        public ItemStack extract(ItemKey key, int maxAmount, boolean simulate) {
            return inventory.extract(key, maxAmount, simulate);
        }

        /** Never: a collecting port takes items out of a machine and never puts anything into one. */
        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public int simulateExtract(ItemKey key, int maxAmount) {
            return inventory.simulateExtract(key, maxAmount);
        }

        @Override
        public int simulateInsert(ItemKey key, int amount) {
            return 0;
        }

        @Override
        public void spill(ItemStack stack) {
            inventory.spill(stack);
        }
    }

    /**
     * A <b>fluid bay</b> ({@code docs/warehouse-system.md} §3.9, M30, issue #21): a storage location that is a tank.
     * <p>
     * Every item operation answers "nothing", because a fluid bay holds no items: there is nothing to extract, and an
     * insertion is refused by returning the caller's stack unchanged — never by swallowing it and never by handing a
     * <i>different</i> item back as the remainder, which is the trap the whole exchange primitive exists to avoid
     * ({@link TransferContext#exchange}). The one real operation is {@link #exchange}.
     *
     * <h2>The two asymmetries, said out loud</h2>
     * <ul>
     * <li>{@link #simulateInsert} is <b>monotone</b>: it answers how many <i>whole</i> containers of the key the bay
     * would take right now, up to the amount asked, because every caller of it wants a bound — the planner's live
     * callbacks bound a job by it, and a bound that collapsed to 0 as soon as one container too many was offered would
     * make a half-full bay look full.</li>
     * <li>{@link #exchange} is <b>all or nothing</b>: it exchanges the amount asked for or nothing, because a partial
     * exchange would leave two item keys in one handling head.</li>
     * </ul>
     * So the two deliberately disagree, and they disagree in the safe direction: a caller that asks
     * {@code simulateInsert} first and then exchanges exactly what it answered always gets an exchange. That holds
     * because both measure the container the same way, by really draining a probe
     * ({@link FluidContainers#drained}) — the whole of the M30 review fix in this class.
     *
     * @param bay the live bay; its <b>ungated</b> API is used, so {@code storage.fluidBayPipeExtraction} never changes
     *            what the crane can do — that key is about pipes
     */
    private record FluidBayContext(Level level, BlockPos position, FluidBayBlockEntity bay)
            implements TransferContext {
        @Override
        public LocationKind kind() {
            return LocationKind.STORAGE;
        }

        /** <b>Yes</b>: this is the one location whose items arrive by exchange and never by insertion. */
        @Override
        public boolean exchangesOnly() {
            return true;
        }

        /** Nothing: a fluid bay holds no items, so there is never an item in it to take. */
        @Override
        public ItemStack extract(ItemKey key, int maxAmount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        /**
         * <b>Refuses everything</b>, by giving the caller its own stack back untouched — the one answer that loses
         * nothing whoever asks. A container reaches a bay through {@link #exchange} or a player's hand and in no other
         * way (D1/D3), so a real call here is a caller using the wrong operation rather than a situation; it is logged
         * once in a while instead of being silently correct.
         */
        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            if (!simulate && !stack.isEmpty() && FLUID_BAY_INSERT_LOG.tryLog(level.getGameTime()))
                LOGGER.warn("Something tried to insert {} into the fluid bay at {}; a fluid bay holds no items, so the"
                        + " stack was given back untouched. A filled container is exchanged, never inserted", stack,
                        position);
            return stack;
        }

        @Override
        public int simulateExtract(ItemKey key, int maxAmount) {
            return 0;
        }

        /**
         * How many <b>whole</b> containers of {@code key} this bay would exchange right now, at most {@code amount}:
         * 0 for anything that is not a filled container (so an <i>empty</i> one is never planned into a bay, which
         * closes the churn loop structurally), 0 for a fluid the bay's filter or its current contents refuse, and 0
         * for a bay with less than one whole container of room — a copper bay with 999 mB free takes <b>nothing</b>
         * from a bucket, which is the vanilla bucket's own rule too.
         * <p>
         * The container is measured by {@link FluidContainers#drained}, which is the <b>same authority</b>
         * {@link #exchange} asks and not the cheaper {@code contents} read beside it (M30 review fix, issue #21). The
         * two differ for a container {@code contents} can read and a drain cannot honour — a per-call cap, a
         * multi-tank item, a consumable, a handler answering with two items — and a container measured by the lenient
         * one would be planned into a bay, driven there and refused on arrival, against the issue's own settled rule
         * that a container which does not fit is never sent in the first place. The cost is the same order either way:
         * both build a probe stack and resolve the item's fluid capability on it.
         * <p>
         * No change: the container is drained on a throw-away probe that exists only inside that call, and the room is
         * measured with a simulated fill.
         */
        @Override
        public int simulateInsert(ItemKey key, int amount) {
            if (amount <= 0)
                return 0;
            FluidContainers.Drained drained = FluidContainers.drained(key).orElse(null);
            if (drained == null)
                return 0;
            int room = bay.fill(drained.fluid().toStack(Integer.MAX_VALUE), true);
            return Math.min(amount, FluidBayTier.wholeContainers(drained.millibuckets(), room));
        }

        /**
         * The real thing: {@code amount} containers of {@code held} are emptied into this bay's tank and the item they
         * turn into is reported back, all of them or none ({@link TransferContext#exchange}).
         * <p>
         * In order, and the order is the contract:
         * <ol>
         * <li><b>What one container holds and becomes</b> ({@code FluidContainers.drained}), measured by really
         * draining a probe stack of exactly one item that exists only inside that call — so this step changes nothing
         * while still being the truth rather than a prediction, which a simulated drain could not be because it leaves
         * {@code getContainer()} holding the filled item. Everything the all-or-nothing rule refuses is refused
         * here: a non-container, an empty container, a per-call cap, a container that is not empty afterwards, a
         * consumable that leaves nothing to carry back.</li>
         * <li><b>Whether the whole load fits</b>, with a simulated fill of all {@code amount} containers' worth at
         * once. The bay's filter and its stored fluid are part of that answer, so a wrong fluid and a full bay are the
         * same refusal, and both happen before anything moves.</li>
         * <li><b>The fill</b>, for a real call. A fill that accepted less than the whole load — which this bay's own
         * handler cannot do, because its arithmetic is the same pure function for a simulation and for a real call and
         * nothing runs between the two — has exactly that much <b>drained back out</b> and is reported as a refusal,
         * so this method keeps its promise that a refusal moved nothing.</li>
         * </ol>
         */
        @Override
        public Optional<TransferContext.ContainerExchange> exchange(ItemKey held, int amount, boolean simulate) {
            Objects.requireNonNull(held, "held");
            if (amount <= 0)
                return Optional.empty();
            FluidContainers.Drained drained = FluidContainers.drained(held).orElse(null);
            if (drained == null)
                return Optional.empty();
            long total = (long) amount * drained.millibuckets();
            if (total <= 0 || total > Integer.MAX_VALUE)
                return Optional.empty();
            int load = (int) total;
            // A fresh stack per call: a FluidStack is mutable and no handler promises not to touch what it is given.
            if (bay.fill(drained.fluid().toStack(load), true) != load)
                return Optional.empty();
            if (simulate)
                return Optional.of(new TransferContext.ContainerExchange(drained.emptied(), amount, load));
            int accepted = bay.fill(drained.fluid().toStack(load), false);
            if (accepted == load)
                return Optional.of(new TransferContext.ContainerExchange(drained.emptied(), amount, accepted));
            rollBack(drained, accepted, load);
            return Optional.empty();
        }

        /**
         * Undoes a fill that took less than the whole load, so that the refusal this method's caller reports is the
         * truth. Unreachable with this mod's own handler; a log line either way, because a bay contradicting its own
         * simulation is a defect whether the roll-back worked or not.
         */
        private void rollBack(FluidContainers.Drained drained, int accepted, int load) {
            FluidStack back = accepted > 0 ? bay.drain(accepted, false) : FluidStack.EMPTY;
            boolean whole = accepted <= 0 || (drained.fluid().matches(back) && back.getAmount() == accepted);
            LOGGER.error("The fluid bay at {} simulated room for {} mB of {} and then took only {}; {}", position, load,
                    drained.fluid(), accepted,
                    whole ? "the exchange was refused and the difference drained back out"
                            : "draining it back out gave " + back + ", so that much fluid is unaccounted for");
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
