package dev.wareworks.content.storage;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.LocationReservationSummary;
import dev.wareworks.content.controller.StorageMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.head.TransferContexts;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.LogThrottle;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of a rack bay ({@code docs/warehouse-system.md} §3.8, M28, issue #20): the storage location
 * <b>itself</b>, holding one item type as a count.
 * <p>
 * It is the first storage location of this mod that owns its items. A warehouse interface reads a foreign inventory
 * through a capability cache and exposes nothing; a bay <i>is</i> the inventory, so {@link #attachedPos()} is its own
 * position and {@link #attachedHandler()} is its own {@link RackBayHandler}. Everything the controller and the crane do
 * with a storage location then works unchanged: the crane transfers through that handler, the controller snapshots it,
 * and the store filter and the storage priority sit on the same value box they sit on for an interface.
 *
 * <h2>No ticker, and nothing that wants one</h2>
 * {@code RackBayBlock.getTicker} returns {@code null} on both sides, so a basement of a thousand bays costs nothing per
 * tick. That is affordable only because nothing here polls: a bay's contents change only through its own handler or a
 * player's hands, and each change tells the controllers of this rack position at once
 * ({@link WarehouseRegistry#contentChanged}), which is a <b>one-key</b> index diff — the cheapest stock update in the
 * mod, and strictly better than the neighbour hint plus round-robin re-read a chest behind an interface needs. The
 * {@link StorageFilterBehaviour} has no {@code tick()} and no {@code initialize()} either.
 *
 * <h2>Standalone</h2>
 * A wooden bay is a better barrel, cheap enough to build long before there is a warehouse, so it has to work with no
 * controller, no crane and no aisle anywhere: filled and emptied by hand, readable from the outside. That is what a
 * tickerless block entity owning its own handler is by construction — every notification above is a no-op when no
 * controller's aisle contains this position, and no code path here needs one. Only when a crane can reach it does the
 * bay <i>also</i> become a storage location with an address.
 *
 * <h2>Machines may fill it, and what that opens</h2>
 * The bay exposes its whole handler as {@code Capabilities.ItemHandler.BLOCK} on every face
 * ({@link #registerCapabilities}) and carries a {@code DirectBeltInputBehaviour} for the callers that do not use the
 * capability at all, so a funnel, a chute, a vanilla hopper, a belt, a belt tunnel or a weighted ejector can put items
 * straight into it. That is new: the warehouse interface deliberately exposes <b>no</b>
 * capability of its own, so until now the only automated way into a warehouse was the input station and the crane. A
 * bay is therefore the first <b>storage location</b> a machine can fill directly, and it is the first one a player can
 * fill with no warehouse around it at all.
 * <p>
 * A Create <b>mechanical arm</b> is not among them, and the capability is not what decides that: an arm only reaches a
 * block that some registered {@code ArmInteractionPointType} accepts, every one of Create's types is a block-identity
 * test, and there is no fallback for "anything with an item capability" (§3.2.2). M28 registers none for the bay, so
 * the Mechanical Arm <b>item</b> on a bay places an arm rather than aiming one, which is exactly what
 * {@link RackBayGestures} lets through — a funnel between the two does the job, as it did for the stations before
 * M12.
 * <p>
 * Nothing about the mod's rules bends for it. No item teleports: a machine moves items into the block in front of it,
 * one real {@code IItemHandler} call at a time, and the controller is only <i>told</i>:
 * <ul>
 * <li>a machine that fills a bay directly reaches {@link #onContentsChanged()} in the same tick, so the stock index
 * catches up with a one-key diff rather than through a neighbour hint and a re-read cycle;</li>
 * <li>a machine that <b>drains</b> a bay the crane has already planned a job out of changes nothing about item
 * conservation: the real extract result is authoritative, the crane simply picks less, and a zero pick aborts the job
 * after retracting;</li>
 * <li>a machine offered the wrong item is refused by {@link RackBayHandler#isItemValid} at its own simulate, so a
 * funnel backs up instead of hammering a bay that will never take its item.</li>
 * </ul>
 * <b>Open, and the owner's to decide:</b> whether the <b>aisle</b> face should refuse insertion from anything but the
 * crane, to keep that lane clear and the input station the only machine route into a warehouse. It is deliberately not
 * decided here — every face accepts items, because in a real rack wall the lateral and rear faces are covered by
 * neighbours and the aisle face is often the only reachable one.
 *
 * <h2>How a bay learns its item type</h2>
 * The store filter decides what may enter at all, and the first type that really lands fixes what the bay holds; a bay
 * that empties <b>forgets</b> it again and takes whatever comes next ({@code BayContents}). The learned type is
 * therefore the stored type and nothing else — no fourth field, and nothing is ever written into the filter slot, which
 * would make a type the bay decided look like one a player set.
 *
 * <h2>Hands, goggles and what crosses the network</h2>
 * A plain right-click moves <b>one item</b>, Shift moves <b>one stack</b>, in both directions, and there is no "take
 * everything" ({@link RackBayGestures}, ADR-045). Through Engineer's Goggles the bay names its own material, says
 * where it stands — or that no warehouse serves it, which for this block is <b>working as intended</b> — what may
 * enter it, what is in it and how full it is ({@link #addToGoggleTooltip}).
 * <p>
 * Its client packet is the item's registry <b>id</b>, the count and the goggle state, and never an {@link ItemKey}: a
 * block entity's update tag is part of every chunk packet (§3.1.1). It is sent on <b>every</b> content change,
 * unthrottled, because the contents are read off the block by anyone walking past and not only through goggles — see
 * {@link #onContentsChanged()}.
 *
 * <h2>The column rule</h2>
 * A bay may carry nothing stronger above it ({@link RackBayBlock}, ADR-044). Placement refuses it; a bay a command
 * brought into that state keeps its items and its address and only stops being a <b>store</b> target
 * ({@link #acceptsStoring()}), which is why the flag lives in the block state and not here: it is the block's
 * geometry, it costs no save bytes of ours, and it reaches the client through the ordinary chunk path.
 *
 * <h2>What is saved</h2>
 * One {@link ItemKey} and one {@code int} ({@link RackBayHandler#writeTo}), plus Create's filter and the storage
 * priority. {@code ItemStack.save} is never called, because its codec bounds a count at 99 and a brass bay holds
 * 65 536 (ADR-013). {@code writeSafe} is deliberately left at Create's default, which does <b>not</b> call
 * {@link #write}: a schematic of a rack wall carries the filters and the priorities and not one item, so a
 * schematicannon can never print a full bay.
 *
 * <h2>Breaking it</h2>
 * Breaking a bay <b>resets</b> it: an empty bay item and the whole load as one {@link PalletEntity} on the floor,
 * which is refilled by hand a stack at a time — never both the items and a filled block, and never a filled block in
 * a pocket ({@link #destroy()}, ADR-046).
 */
public class RackBayBlockEntity extends SmartBlockEntity
        implements StorageMember, Clearable, IHaveGoggleInformation, GoggleObservers.Observable {
    /** Client-packet key of the aisle assignment: the address, "misaligned" or "not part of an aisle". */
    public static final String ASSIGNMENT_TAG = "AisleAssignment";
    /** Client-packet key of this location's reservations; absent while nothing is reserved. */
    public static final String RESERVATIONS_TAG = "Reservations";
    /** Client-packet key of the shared-inventory warning; absent, i.e. false, for every ordinary bay. */
    public static final String FILTER_SHADOWED_TAG = "FilterShadowed";

    /**
     * Store filter and storage priority slot, the warehouse interface's behaviour verbatim. Assigned in
     * {@link #addBehaviours}, which {@code SmartBlockEntity} calls from its constructor, so this field must not have an
     * initializer (it would reset the behaviour to {@code null}).
     */
    protected StorageFilterBehaviour storeFilterBehaviour;

    /**
     * The bay's own items. A <b>final</b> field, so NeoForge's automatic capability invalidation (placement, removal,
     * chunk load and unload) is all the invalidation this block needs.
     */
    private final RackBayHandler handler = new RackBayHandler(new Rules());

    /** The untrimmed filter stack {@link #resolvedFilter} was built from; {@code FilterItemStack.of} trims in place. */
    private ItemStack filterSource = ItemStack.EMPTY;
    /** The resolved Create filter, rebuilt only when the filter stack really changed. */
    @Nullable
    private FilterItemStack resolvedFilter;
    /** Rate limit for a filter that throws while being evaluated (a modded item attribute); a second stays loggable. */
    private final LogThrottle filterFailures = new LogThrottle();

    /** Address of this bay as of the last goggle observation (server) or sync (client). */
    private AisleAssignment assignment = AisleAssignment.NONE;
    /** Reservations of this location as of the last goggle observation (server) or sync (client). */
    private LocationReservationSummary reservations = LocationReservationSummary.NONE;
    /**
     * Whether another storage location counts this bay's inventory and the planner therefore never asks this bay's own
     * store settings. Practically always false and still worth reading: it needs a warehouse interface standing in
     * front of a bay at a rack position of a second aisle, which M28 does not make deterministic — but a store filter
     * or a priority that is silently dead is exactly what a goggle line exists for (§3.1.1).
     */
    private boolean filterShadowed;

    public RackBayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // The aisle face, exactly as on a warehouse interface: FACING points into the rack depth, so the value box sits
        // on FACING.getOpposite() below the arm port, and the storage priority shares it as a hold-to-edit board row
        // (ADR-028). StorageFilterValueBox reads HorizontalDirectionalBlock.FACING and therefore needs no change.
        storeFilterBehaviour = new StorageFilterBehaviour(this, new StorageFilterValueBox());
        storeFilterBehaviour.setLabel(WareworksLang.translateDirect(WareworksLang.INTERFACE_STORE_FILTER));
        storeFilterBehaviour.withCallback(stack -> onStoreSettingsChanged());
        storeFilterBehaviour.withPriorityCallback(priority -> onStoreSettingsChanged());
        behaviours.add(storeFilterBehaviour);
        // Belts, belt tunnels and weighted ejectors do not use the item capability: they look for this behaviour at the
        // block they hand items to, and a belt whose end finds none does not even resolve that block as an ending
        // (Create's BeltInventory#resolveEnding). The warehouse input has one for the same reason; a bay needs no
        // insertion callback of its own, because the capability a funnel uses and the handler this inserts into are the
        // same object. Neither this behaviour nor the filter above overrides tick() or initialize(), so the bay stays
        // tickerless.
        behaviours.add(new DirectBeltInputBehaviour(this).setInsertionHandler(this::insertFromBelt));
    }

    /**
     * A belt, belt tunnel or weighted ejector hands items to the bay. The direction Create passes is not consistent
     * across its callers and is ignored, as it is at the warehouse input; the bay accepts items on every face anyway.
     * <p>
     * Returns the <b>remainder</b>, as the callback contract requires, and never modifies the caller's stack — the
     * handler keeps a count-less {@link ItemKey} and hands back a copy.
     */
    private ItemStack insertFromBelt(TransportedItemStack transported, Direction side, boolean simulate) {
        return handler.insertItem(RackBayHandler.SLOT, transported.stack, simulate);
    }

    // --- the bay ---------------------------------------------------------------------------------------------------

    /** The material this bay is built from, read off the block; {@link BayTier#WOOD} for a state that is not a bay. */
    public BayTier tier() {
        BayTier tier = RackBayBlock.tierOf(getBlockState());
        return tier == null ? BayTier.WOOD : tier;
    }

    /** How many stacks this bay holds, from the server config, clamped ({@code storage.<tier>BayStacks}). */
    public int stacks() {
        return WareworksConfig.bayStacks(tier());
    }

    /** How many <b>items</b> this bay holds of {@code key}: its stack count times how far that item stacks. */
    public long capacityFor(ItemKey key) {
        return WareworksConfig.bayCapacity(tier(), key.getMaxStackSize());
    }

    /** The bay's item handler — the whole of its contents, and what the crane transfers through. */
    public IItemHandler handler() {
        return handler;
    }

    /** The stored item type, empty while the bay is empty. */
    public Optional<ItemKey> storedKey() {
        return handler.stored();
    }

    /** How many items are stored. */
    public int storedCount() {
        return handler.count();
    }

    /**
     * Server: inserts as much of {@code stack} as fits and answers what is left over, the way an item handler does.
     * The caller's stack is not modified. Used by the bay's own gestures, by tests and by scripted scenes.
     */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        return handler.insertItem(RackBayHandler.SLOT, stack, simulate);
    }

    /** Server: takes up to {@code amount} items, at most one stack per call ({@link RackBayHandler#extractItem}). */
    public ItemStack extract(int amount, boolean simulate) {
        return handler.extractItem(RackBayHandler.SLOT, amount, simulate);
    }

    // --- storage location ------------------------------------------------------------------------------------------

    /** Direction into the rack depth, away from the aisle — the alignment rule of {@code LocationKind.STORAGE}. */
    @Override
    public Direction facing() {
        return getBlockState().getOptionalValue(RackBayBlock.FACING).orElse(Direction.NORTH);
    }

    /**
     * A bay <b>is</b> its own inventory, so the position the controller watches for loading is the bay's own. Nothing
     * is attached behind it and no capability cache is involved.
     */
    @Override
    public BlockPos attachedPos() {
        return worldPosition;
    }

    /** The bay's own handler, empty only once the block entity is gone. */
    @Override
    public Optional<IItemHandler> attachedHandler() {
        return isRemoved() ? Optional.empty() : Optional.of(handler);
    }

    /**
     * One slot, whose limit is the bay's capacity in items — built directly rather than through
     * {@code ItemHandlerSnapshots.capture}, which would build an oversized {@code ItemStack} for nothing.
     */
    @Override
    public InventorySnapshot<ItemKey> snapshot() {
        return handler.snapshot();
    }

    /**
     * <b>Yes</b>: this is the block the question exists for. Everything already inside a bay, and everything a planned
     * job is about to bring, has to be the same item as the next delivery, so the planner never offers a bay a second
     * type at all ({@code AisleFilters#match}).
     */
    @Override
    public boolean holdsOneTypeOnly() {
        return true;
    }

    /**
     * <b>No</b> while this bay carries something stronger above it in its column ({@link RackBayBlock#OVERLOADED},
     * ADR-044): the warehouse plans no store job towards it, for a reason no filter could express, while everything
     * already inside stays retrievable and nothing is moved, dropped or destroyed. A bay that is not overloaded answers
     * the inherited "yes".
     * <p>
     * Only a command, a schematic or WorldEdit can bring a standing bay into this state — a placement that would is
     * refused — and the moment the bay above is gone it answers yes again.
     */
    @Override
    public boolean acceptsStoring() {
        return !overloaded();
    }

    /** Whether this bay carries something stronger above it in its column; read off the block state. */
    public boolean overloaded() {
        return getBlockState().getOptionalValue(RackBayBlock.OVERLOADED).orElse(false);
    }

    /**
     * How full this bay should <b>look</b>: {@code 0} for an empty one up to {@code RackBayBlock.FILL_LEVELS} for a
     * full one ({@link RackBayBlock#fillStep}). It is derived from the contents and the configured capacity on every
     * call rather than stored, so a modpack that moves the capacity curve moves the look with it; the block state
     * carries it only so that the chunk mesh can draw it ({@link RackBayBlock#FILL}).
     */
    public int fillStep() {
        return storedKey().map(key -> RackBayBlock.fillStep(storedCount(), capacityFor(key))).orElse(0);
    }

    /** A copy of the filter stack that decides what may be stored here; empty means "accepts everything". */
    @Override
    public ItemStack storeFilter() {
        return storeFilterBehaviour.getFilter().copy();
    }

    /** The storage priority a player gave this bay (0..9, higher fills first); it never affects retrieval. */
    @Override
    public int storePriority() {
        return storeFilterBehaviour.priority();
    }

    /** Whether a player gave this bay a store filter at all. */
    public boolean hasStoreFilter() {
        return !storeFilterBehaviour.getFilter().isEmpty();
    }

    /** Server: sets the store filter as a player click would (an empty stack clears it). */
    public boolean setStoreFilter(ItemStack filter) {
        return storeFilterBehaviour.setFilter(filter);
    }

    /** Server: sets the storage priority as the hold-to-edit board would (clamped to 0..9). */
    public boolean setStorePriority(int priority) {
        return storeFilterBehaviour.setPriority(priority);
    }

    /** The filter or the priority changed: drop the resolved filter and let the controllers re-read both. */
    private void onStoreSettingsChanged() {
        resolvedFilter = null;
        filterSource = ItemStack.EMPTY;
        if (level instanceof ServerLevel && !isRemoved())
            WarehouseRegistry.filterChanged(level, worldPosition);
    }

    /**
     * Whether the store filter lets {@code stack} in. An empty slot accepts everything (the Create convention), and a
     * filter that throws while being evaluated refuses — the same answer {@code AisleFilters} gives, so the bay's own
     * handler and the planner can never disagree about a broken filter.
     * <p>
     * The resolved {@link FilterItemStack} is cached: resolving a Create list filter allocates a slot handler and a
     * nested wrapper per entry, and this is asked on <b>every</b> insert.
     */
    private boolean passesFilter(ItemStack stack) {
        ItemStack filter = storeFilterBehaviour.getFilter();
        if (filter.isEmpty())
            return true;
        if (level == null)
            return true; // nothing to evaluate against; the real insert still decides
        try {
            return resolvedFilter(filter).test(level, stack);
        } catch (RuntimeException e) {
            if (filterFailures.tryLog(level.getGameTime()))
                Wareworks.LOGGER.warn("Rack bay filter at {} could not be evaluated for {}", worldPosition, stack, e);
            return false;
        }
    }

    private FilterItemStack resolvedFilter(ItemStack filter) {
        if (resolvedFilter == null || !ItemStack.isSameItemSameComponents(filterSource, filter)) {
            filterSource = filter.copy();
            // of() trims enchantments and attribute modifiers in place, so it never sees the behaviour's own stack.
            resolvedFilter = FilterItemStack.of(filter.copy());
        }
        return resolvedFilter;
    }

    /**
     * The contents really changed (a transfer through the handler, a player's hand, a command): save, sync, and tell
     * the controllers of this rack position at once, which is a one-key index diff. A bay with no warehouse around it
     * notifies nothing and costs nothing.
     * <p>
     * <b>The sync is deliberately unthrottled</b>, which no other block of this mod does for its contents (ADR-045).
     * A warehouse interface throttles its goggle summary and flushes it on the next goggle observation, because a
     * summary is only ever read through goggles; a bay's contents are read off the block itself by anyone walking
     * past, so they have to be right when nobody wears goggles — and a throttle on a block with no ticker and no
     * observer could never be flushed at all. The price is one block-entity update per real change, which is bounded
     * by what can change a bay: the crane touches one at most once per {@code transferTicks}, a hopper once every
     * eight ticks, a hand once per click. The tag itself is an item id and an int
     * ({@link RackBayHandler#writeClientPacket}), asserted against {@code MAX_RACK_BAY_SYNC_BYTES}.
     */
    private void onContentsChanged() {
        setChanged(); // null-safe; also lets comparators and neighbours notice
        if (!(level instanceof ServerLevel) || isRemoved())
            return;
        // What a player reads off the front without goggles, in the block state so that it costs nothing to draw at
        // any distance (M28 step 9, RackBayBlock#FILL). It writes only when the step really changed, which is at most
        // four times over a whole bay's worth of goods.
        RackBayBlock.contentsChanged(level, worldPosition);
        sendData();
        WarehouseRegistry.contentChanged(level, worldPosition);
    }

    // --- goggles ---------------------------------------------------------------------------------------------------

    /** The aisle assignment as of the last goggle observation (server) or sync (client). */
    public AisleAssignment aisleAssignment() {
        return assignment;
    }

    /** The reservations of this storage location as of the last goggle observation (server) or sync (client). */
    public LocationReservationSummary reservationSummary() {
        return reservations;
    }

    /** Whether another storage location counts this bay's inventory, so its own store settings are never asked. */
    public boolean isFilterShadowed() {
        return filterShadowed;
    }

    /**
     * A player looks at this bay through goggles (server, from {@link GoggleObservers}): resolves the address and the
     * reservations in one registry scan and syncs them if they changed.
     * <p>
     * <b>The whole observer apparatus of the warehouse interface is deliberately absent</b> — the dirty flag, the
     * two-rate refresh, the throttle, the {@code LogThrottle} around an inventory that throws and the
     * {@code BlockCapabilityCache} listener. All of it exists for one stated reason: goggles run on the client, which
     * cannot see a <b>foreign</b> inventory's contents. A bay's contents are its own block entity's data and are
     * already on the client ({@link #onContentsChanged()}), so a copy of that machinery here would be dead code
     * wearing the clothes of caution. Nothing is re-read here: an address is geometry, and geometry changes notify by
     * themselves.
     */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        WarehouseRegistry.StorageObservation next = WarehouseRegistry.observeStorage(level, worldPosition, this);
        if (next.assignment().equals(assignment) && next.reservations().equals(reservations)
                && next.filterShadowed() == filterShadowed)
            return;
        assignment = next.assignment();
        reservations = next.reservations();
        filterShadowed = next.filterShadowed();
        sendData();
    }

    /**
     * What a bay says through Engineer's Goggles: the material, where it stands, what may enter, what is in it and how
     * full it is.
     * <p>
     * Three things about the wording are decisions rather than phrasing (ADR-045):
     * <ul>
     * <li>the header is the <b>block's own name</b> — "Brass Rack Bay:" — because the material decides the capacity
     * and the column rule, and the block already says it in every language;</li>
     * <li>a bay no warehouse serves adds one dark-grey line saying that it works by hand anyway. "Not part of an
     * aisle" is a defect for every other member of a warehouse and a plain fact for a bay, which is an early-game
     * barrel long before there is a crane — and many players meet this block first;</li>
     * <li>an <b>unfiltered</b> bay never reads "Accepts everything": empty it <i>takes the first item that arrives</i>,
     * and once something is in it it <i>holds that until it is empty</i>. That is also how a player tells the type the
     * bay learned from the one they set, which stays after the bay drains.</li>
     * </ul>
     * The contents are one line of item, count and capacity rather than Create's slot usage: a bay has exactly one
     * slot, so "Slots: 1 / 1" would be true and useless. Client only, like every {@code addToGoggleTooltip}.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        WareworksLang.rackBay(getBlockState().getBlock().getName()).forGoggles(tooltip);
        assignment.addGoggleLines(tooltip, WareworksLang.GOGGLES_BAY_MISALIGNED_HINT, 1);
        if (assignment.state() == AisleAssignment.State.NONE)
            WareworksLang.translate(WareworksLang.GOGGLES_BAY_NO_WAREHOUSE).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
        // What may enter. The filter stack is synced by Create's FilteringBehaviour itself, so the client can name it.
        ItemStack filter = storeFilterBehaviour.getFilter();
        Optional<ItemKey> stored = storedKey();
        if (!filter.isEmpty()) {
            WareworksLang.storageFilter(filter.getHoverName()).forGoggles(tooltip, 1);
        } else if (stored.isPresent()) {
            WareworksLang.bayLearned(stored.get().getItem()).forGoggles(tooltip, 1);
        } else {
            WareworksLang.translate(WareworksLang.GOGGLES_BAY_ACCEPTS_FIRST).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
        }
        int priority = storeFilterBehaviour.priority();
        if (priority != StorageFilterBehaviour.MIN_PRIORITY)
            WareworksLang.countLine(WareworksLang.GOGGLES_STORAGE_PRIORITY, priority).forGoggles(tooltip, 1);
        if (filterShadowed && (!filter.isEmpty() || priority != StorageFilterBehaviour.MIN_PRIORITY))
            WareworksLang.translate(WareworksLang.GOGGLES_STORAGE_FILTER_SHADOWED).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
        // The column rule, in gold: this bay is out of the warehouse's store plans and still works by hand and still
        // gives its items back, so it is a warning and not an error (ADR-044).
        if (overloaded())
            WareworksLang.translate(WareworksLang.GOGGLES_BAY_OVERLOADED).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        reservations.addGoggleLines(tooltip, 1);
        // What is in it, last, where the interface also keeps its contents.
        if (stored.isEmpty())
            WareworksLang.translate(WareworksLang.GOGGLES_EMPTY).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
        else
            WareworksLang.bayContents(stored.get().getItem(), storedCount(), capacityFor(stored.get()))
                    .forGoggles(tooltip, 1);
        WareworksLang.countLine(WareworksLang.GOGGLES_BAY_CAPACITY, stacks()).forGoggles(tooltip, 1);
        return true;
    }

    // --- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * On the server, also brings a <b>stale fill level</b> back in line, through a scheduled tick rather than a write
     * from here ({@link RackBayBlock#tick}): {@code onLoad} runs while the chunk is being assembled, and a block state
     * written at that moment would race the chunk's own. One tick later there is no such window, and nothing is
     * scheduled at all when the level already agrees — which is every bay of every ordinary load.
     * <p>
     * It can only disagree by a route that wrote the contents without going through the handler: a lowered
     * {@code storage.<tier>BayStacks} under a saved bay (the step is a fraction of the capacity, so the capacity
     * moving moves it), {@code /data merge} on a bay's block entity, or a crafted block entity tag.
     * <p>
     * This runs <b>once</b>, on the tick after the block entity became fresh ({@code Level#tickBlockEntities}), so it
     * cannot answer for a tag read into a bay that has been standing for minutes — a schematic print, {@code /data
     * merge}. {@link #read} schedules the same repair for exactly that case.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (!(level instanceof ServerLevel))
            return;
        onMembershipRelevantChange();
        if (getBlockState().getOptionalValue(RackBayBlock.FILL).orElse(fillStep()) != fillStep())
            level.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
    }

    /**
     * The block state changed under this block entity, and whoever reads it has to be told in the same tick.
     * <p>
     * Two of the bay's three state changes mean something to a controller, and they are told in different ways because
     * they change different things:
     * <ul>
     * <li><b>facing</b> (a wrench, a structure): aligned and misaligned swap, so the rack position has to be probed
     * again — {@code memberChanged};</li>
     * <li><b>{@link RackBayBlock#OVERLOADED}</b> (a command broke or repaired the column above): the location is the
     * same one, only its answer to "may I store here" flipped, which is exactly what a store filter change is —
     * {@code filterChanged}, a synchronous re-read of this one location's store settings. Without it the controller's
     * cached verdict would stay stale until the next {@code refreshLocation}, which can be a round robin away, and the
     * planner would keep offering a bay that refuses;</li>
     * <li>a <b>fill level</b> (M28 step 9) matches neither and notifies nothing, which is correct and free — the
     * contents have their own notification.</li>
     * </ul>
     */
    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction oldFacing = facing();
        boolean wasOverloaded = overloaded();
        super.setBlockState(state);
        if (!(level instanceof ServerLevel))
            return;
        if (facing() != oldFacing)
            onMembershipRelevantChange(); // rotated (wrench, structure placement): aligned and misaligned swap
        else if (overloaded() != wasOverloaded && !isRemoved())
            WarehouseRegistry.filterChanged(level, worldPosition);
    }

    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel)
            onMembershipRelevantChange();
    }

    /**
     * Block broken or replaced (server, via {@code IBE.onRemove}): breaking a bay <b>resets</b> it. The block becomes
     * a plain, empty bay item from its loot table and the whole load leaves as <b>one</b> {@link PalletEntity} lying on
     * the floor, which a player empties by hand, a stack at a time, with the bay's own gesture (ADR-046).
     * <p>
     * <b>Never both.</b> The loot table is the plain block, with no {@code copy_nbt} and no {@code setBlockEntityData},
     * so the bay item carries nothing; the pallet carries everything. A creative break skips the loot drop entirely
     * ({@code ServerPlayerGameMode} returns before {@code playerDestroy}) and therefore yields a pallet and no bay
     * item, which is right: the goods were never the creative player's to conjure away.
     * <p>
     * <b>The fields are cleared before anything is spawned</b> ({@link RackBayHandler#takeAll}), so a second pass over
     * this block entity finds nothing — the discipline the station buffer already uses. And
     * {@link PalletEntity#spawn}'s answer <b>is</b> checked: {@code addFreshEntity} can be refused (the join event is
     * cancellable, the UUID set can reject), so the fallback is the old spill — deliberately ugly, a few thousand item
     * entities for a full brass bay, because a loud mess is the right failure mode for an item-conservation event and
     * a silent loss is not.
     * <p>
     * This is the <b>only</b> place a bay gives its contents to the world, and it is not reached by a block state
     * change of the bay itself: {@code IBE.onRemove} returns early when the block stays the same and the new state
     * still has a block entity, so a fill level that changes spawns nothing (M29 rests on that). {@code /setblock},
     * {@code /fill}, {@code /clone} and structure placement do not reach it either — they call
     * {@link #clearContent()} first.
     * <p>
     * A <b>Create schematic print</b> over a standing bay reaches <i>neither</i>: the early return above keeps the
     * block entity, and the print calls {@code Clearable.tryClear} nowhere. It therefore leaves a stocked bay exactly
     * as it was, which is what {@link RackBayHandler#readFrom} is guarded for — before that guard it emptied the bay
     * in place, with no pallet and no drop.
     */
    @Override
    public void destroy() {
        super.destroy();
        if (level == null || level.isClientSide)
            return;
        ItemKey key = handler.stored().orElse(null);
        int load = handler.count();
        ItemStack spill = handler.takeAll(); // clears the contents BEFORE anything else can see them
        if (spill.isEmpty())
            return; // an empty bay leaves no pallet
        if (PalletEntity.spawn(level, worldPosition, key, load))
            return;
        Wareworks.LOGGER.warn("A pallet for {} x {} at {} was refused by the level; the load is spilled as item "
                + "entities instead", load, key, worldPosition);
        TransferContexts.spillAt(level, worldPosition, spill);
    }

    /**
     * {@link Clearable}: {@code /setblock}, {@code /fill}, {@code /clone} and structure placement call this before they
     * replace the block, and the bay is emptied without dropping anything — deliberate parity with a vanilla chest,
     * which loses its contents to the same commands.
     */
    @Override
    public void clearContent() {
        handler.clear();
    }

    /** Server: controllers whose aisle contains this position re-probe it on their next tick. */
    protected void onMembershipRelevantChange() {
        WarehouseRegistry.memberChanged(level, worldPosition);
    }

    // --- persistence and sync --------------------------------------------------------------------------------------

    /**
     * A save carries the real contents; a <b>client packet</b> carries the item's registry id, the count and the
     * goggle state, and never an {@link ItemKey} ({@link RackBayHandler#writeClientPacket}, §3.1.1 — an update tag is
     * part of every chunk packet).
     * <p>
     * The address and the reservations are derived state: a client needs them for the goggles, a save does not. They
     * are written whatever they are, because a bay whose warehouse was broken has to be able to go back to "not part
     * of an aisle"; the reservations and the shadowed flag are skipped while they are empty or false, which is every
     * bay of every warehouse that is not currently being served, so an idle rack wall pays only for the assignment.
     */
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!clientPacket) {
            handler.writeTo(tag, registries);
            return;
        }
        handler.writeClientPacket(tag);
        CompoundTag assignmentTag = new CompoundTag();
        assignment.write(assignmentTag);
        tag.put(ASSIGNMENT_TAG, assignmentTag);
        if (!reservations.isEmpty()) {
            CompoundTag reservationsTag = new CompoundTag();
            reservations.write(reservationsTag);
            tag.put(RESERVATIONS_TAG, reservationsTag);
        }
        if (filterShadowed)
            tag.putBoolean(FILTER_SHADOWED_TAG, true);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (!clientPacket) {
            handler.readFrom(tag, registries);
            // A save read into a bay that already stands in a level is a command or a schematic print and never a
            // world load, which reads before the level is set (BlockEntity.loadStatic). Such a tag brings a block
            // state of its own, whose FILL may disagree with what is really in the bay, so the repair is scheduled
            // exactly as RackBayBlock#onPlace schedules it for OVERLOADED - from here rather than with a write, for
            // the same reason: the caller writes its own state into this block entity afterwards. onLoad() cannot
            // stand in for it: it runs once, on the tick after the block entity became fresh (Level#tickBlockEntities),
            // and a bay a schematic is printed over has stood there for minutes.
            if (level instanceof ServerLevel server && !isRemoved() && getBlockState().getBlock() instanceof RackBayBlock
                    && getBlockState().getValue(RackBayBlock.FILL) != fillStep())
                server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
            return;
        }
        handler.readClientPacket(tag);
        assignment = AisleAssignment.read(tag.getCompound(ASSIGNMENT_TAG));
        reservations = LocationReservationSummary.read(tag.getCompound(RESERVATIONS_TAG));
        filterShadowed = tag.getBoolean(FILTER_SHADOWED_TAG);
    }

    /** What {@link RackBayHandler} has to ask the bay; see {@link RackBayHandler.Rules}. */
    private final class Rules implements RackBayHandler.Rules {
        @Override
        public long capacity(ItemKey key) {
            return capacityFor(key);
        }

        /**
         * The most an <b>empty</b> bay could hold of any item: its stack count times the largest stack Minecraft
         * allows. An empty bay does not know its key, so there is no exact number to report, and the honest answer is
         * the upper bound rather than a guess at 64.
         */
        @Override
        public int emptySlotLimit() {
            return stacks() * Item.ABSOLUTE_MAX_STACK_SIZE;
        }

        @Override
        public boolean passesFilter(ItemStack stack) {
            return RackBayBlockEntity.this.passesFilter(stack);
        }

        @Override
        public void onContentsChanged() {
            RackBayBlockEntity.this.onContentsChanged();
        }

        @Override
        public BlockPos position() {
            return worldPosition;
        }
    }

    /**
     * Mod-bus listener: the bay exposes its own item handler on <b>every</b> side ({@code Capabilities.ItemHandler.BLOCK}),
     * which the mod's hard rule requires of a block holding items (ADR-005) and which is the only way the item census of
     * a conservation test can see a bay's contents at all. <b>Every</b> face, because in a real rack wall the lateral
     * and rear faces are covered by neighbours and the aisle face is often the only reachable one. The handler is a
     * final field, so NeoForge's automatic invalidation is enough.
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WareworksBlockEntityTypes.RACK_BAY.get(),
                (be, side) -> be.handler);
    }
}
