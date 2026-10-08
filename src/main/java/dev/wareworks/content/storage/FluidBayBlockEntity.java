package dev.wareworks.content.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.LocationReservationSummary;
import dev.wareworks.content.controller.StorageMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.content.fluid.FluidDedication;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of a fluid bay ({@code docs/warehouse-system.md} §3.9, M30, issue #21): a storage location that <b>is</b>
 * a tank, holding one fluid as a millibucket count.
 * <p>
 * It is the rack bay of fluids ({@link RackBayBlockEntity}) and shares everything about a bay that is not about goods:
 * the tier, the column rule, the shared uprights, the fill level, the store filter slot and the storage priority are the
 * same code ({@link BayColumn}, {@link TieredBay}, {@link StorageFilterBehaviour}). What is different is what it holds
 * and how a fluid gets in and out.
 *
 * <h2>No item capability at all, and what that buys</h2>
 * A fluid bay deliberately exposes <b>no</b> {@code Capabilities.ItemHandler.BLOCK} and carries no
 * {@code DirectBeltInputBehaviour}, which is the exact opposite of its item sibling's headline (M28: "a funnel, a
 * chute, a belt or a hopper fills a bay directly"). It is forced, and the reason is the <b>insert remainder</b>: a bay
 * that took a lava bucket would have to hand back an empty bucket as the remainder of the insert, and a funnel does not
 * read a remainder of a different item — it would take the lava and <b>destroy the bucket</b>. Exposing no item
 * capability makes "a container reaches a bay only through the crane's handling head or a player's hand" structural
 * rather than a rule somebody has to remember, and it keeps the bay out of the item census's capability sweep
 * ({@code gametest.ItemCensus}) for free, where it would otherwise have to be excluded by name.
 * <p>
 * The compensation is the back face: Create's <b>pipes</b> connect to a fluid bay on every face except the one towards
 * the aisle ({@link #registerCapabilities}), which is the fluid analogue and strictly better for bulk — one Mechanical
 * Pump within 16 blocks fills a brass bay from a lava lake. Whether a pipe may also <b>draw off</b> is one server
 * config key, {@code storage.fluidBayPipeExtraction}, default on ({@link FluidBayHandler.PipeView}); the bay's own
 * operations and a player's own bucket go through the ungated {@link #handler()} and are never affected by it.
 *
 * <h2>No ticker, and nothing that wants one</h2>
 * {@code FluidBayBlock.getTicker} returns {@code null} on both sides, so a basement of a thousand bays costs nothing
 * per tick — the rack bay's promise, and the reason this block draws its level with no interpolation at all
 * (a {@code LerpedFloat} like Create's tank uses is advanced from a tick). A bay's contents change only through its own
 * handler, a pipe, or a player's hands, and each change tells the controllers of this rack position at once
 * ({@link WarehouseRegistry#contentChanged}).
 *
 * <h2>Standalone</h2>
 * With no controller, no crane and no aisle anywhere a fluid bay is still a tank: it is filled and emptied through its
 * fluid capability, it keeps its contents through a save, and it shows its level. That is what a tickerless block
 * entity owning its own handler is by construction — every notification above is a no-op when no controller's aisle
 * contains this position.
 *
 * <h2>A storage location whose inventory is empty by design</h2>
 * It is a {@link StorageMember}, so it joins an aisle, gets an address and is reached by the crane's existing reach
 * rules with no change ({@code LocationKind.STORAGE}, D2). But it holds no <b>items</b>, so
 * {@link #attachedHandler()} is empty and {@link #snapshot()} has zero slots — which is precisely the state a
 * warehouse interface whose chest was taken away reports, i.e. a state the controller and the planner have always
 * handled: nothing is counted at it, and no ordinary item can be stored in it, because there is no room for one
 * anywhere in it. A <b>filled container</b> does reach it, by the crane's container exchange and by that alone (D1):
 * the store gate tells a bay apart from a chestless interface by what it <i>is</i> and answers "ask the live
 * inventory" for it, and the live answer measures the container and the room together.
 *
 * <h2>How a bay learns its fluid</h2>
 * The filter slot decides what may enter at all, and it names a <b>fluid</b> rather than a container
 * ({@link #filterFluid()}); the first fluid that really lands fixes what the bay holds, and a bay that empties
 * <b>forgets</b> it again ({@code BayContents}). The stored fluid is therefore the learned fluid and nothing else — no
 * fourth field, and nothing is ever written into the filter slot, which would make a fluid the bay decided look like
 * one a player set.
 *
 * <h2>What is saved, and what crosses the network</h2>
 * One {@link FluidKey} and one {@code int} of millibuckets ({@link FluidBayHandler#writeTo}), plus Create's filter and
 * the storage priority — never a {@code FluidStack}, whose {@code save} throws on an empty one. A <b>client packet</b>
 * carries the fluid's registry <b>id</b> and the amount ({@link FluidBayHandler#writeClientPacket}, §3.1.1 — an update
 * tag is part of every chunk packet), and it is sent on <b>every</b> content change, unthrottled, because the level is
 * the readout and is read off the block by anyone walking past ({@link #onContentsChanged()}).
 *
 * <h2>Hands, goggles and the break</h2>
 * A right-click with a <b>container</b> empties it into the bay or fills it from the bay, in one vanilla call and with
 * the bay's <b>ungated</b> handler, so the pipe-extraction config never refuses a player's bucket
 * ({@link FluidBayGestures}). Through Engineer's Goggles the bay names its own material, says where it stands — or
 * that no warehouse serves it, which for this block is <b>working as intended</b> — which fluid belongs here, what is
 * in it, how much it holds, whether pipes may draw from the back, and that breaking it would lose the contents
 * ({@link #addToGoggleTooltip}, {@link #ownGoggleRows()}).
 * <p>
 * <b>Breaking it loses the fluid</b> ({@link #destroy()}, D7), which is the one place a fluid bay is deliberately
 * worse than its item sibling: a fluid has no drop form, so there is nothing a pallet could carry. The loss is logged
 * with the fluid, the amount and the position, and <b>three places say so before a player can hit it</b> — the item
 * description, the bay's own goggle line while it holds anything, and the action-bar warning on the first punch
 * ({@link FluidBayBlock#attack}).
 */
public class FluidBayBlockEntity extends SmartBlockEntity
        implements StorageMember, Clearable, IHaveGoggleInformation, GoggleObservers.Observable {
    /** Client-packet key of the aisle assignment: the address, "misaligned" or "not part of an aisle". */
    public static final String ASSIGNMENT_TAG = "AisleAssignment";
    /** Client-packet key of this bay's reservations; absent while nothing is reserved, as at a rack bay. */
    public static final String RESERVATIONS_TAG = "Reservations";

    /**
     * Store filter and storage priority slot, the rack bay's behaviour verbatim. Assigned in {@link #addBehaviours},
     * which {@code SmartBlockEntity} calls from its constructor, so this field must not have an initializer (it would
     * reset the behaviour to {@code null}).
     */
    protected StorageFilterBehaviour storeFilterBehaviour;

    /**
     * The bay's own fluid, ungated. A <b>final</b> field, so NeoForge's automatic capability invalidation (placement,
     * removal, chunk load and unload) is all the invalidation this block needs.
     */
    private final FluidBayHandler handler = new FluidBayHandler(new Rules());
    /** What pipes reach: the same contents with {@code drain} behind the server config (D4). Final for the same reason. */
    private final FluidBayHandler.PipeView pipeView =
            new FluidBayHandler.PipeView(handler, WareworksConfig::fluidBayPipeExtraction);

    /** The filter stack {@link #filterFluid} was read from; an empty stack means "nothing resolved yet". */
    private ItemStack filterSource = ItemStack.EMPTY;
    /** The fluid the filter stack names, resolved only when that stack really changed; empty means "no fluid". */
    private Optional<FluidKey> filterFluid = Optional.empty();
    /** The fluid this bay was dedicated to when its controllers were last told; see {@link #onContentsChanged()}. */
    @Nullable
    private FluidKey publishedDedication;

    /** Address of this bay as of the last goggle observation (server) or sync (client). */
    private AisleAssignment assignment = AisleAssignment.NONE;
    /** What is reserved at this bay as of the last goggle observation (server) or sync (client). */
    private LocationReservationSummary reservations = LocationReservationSummary.NONE;

    public FluidBayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // The aisle face, exactly as on a rack bay: FACING points into the rack depth, so the value box sits on
        // FACING.getOpposite() below the arm port, and the storage priority shares it as a hold-to-edit board row
        // (ADR-028). StorageFilterValueBox reads HorizontalDirectionalBlock.FACING and therefore needs no change.
        storeFilterBehaviour = new StorageFilterBehaviour(this, new StorageFilterValueBox());
        storeFilterBehaviour.setLabel(WareworksLang.translateDirect(WareworksLang.INTERFACE_STORE_FILTER));
        storeFilterBehaviour.withCallback(stack -> onStoreSettingsChanged());
        storeFilterBehaviour.withPriorityCallback(priority -> onStoreSettingsChanged());
        behaviours.add(storeFilterBehaviour);
        // No DirectBeltInputBehaviour: a belt, a tunnel or an ejector hands over ITEMS, and a fluid bay takes none
        // (see the class comment). Neither this behaviour nor the filter overrides tick() or initialize(), so the bay
        // stays tickerless.
    }

    // --- the bay ---------------------------------------------------------------------------------------------------

    /** The material this bay is built from, read off the block; {@link FluidBayTier#COPPER} for a state that is not one. */
    public FluidBayTier tier() {
        FluidBayTier tier = FluidBayBlock.tierOf(getBlockState());
        return tier == null ? FluidBayTier.COPPER : tier;
    }

    /** How many buckets this bay holds, from the server config, clamped ({@code storage.<tier>FluidBayBuckets}). */
    public int buckets() {
        return WareworksConfig.fluidBayBuckets(tier());
    }

    /** How many <b>millibuckets</b> this bay holds in total; what the handler measures every fill against. */
    public long capacity() {
        return WareworksConfig.fluidBayCapacity(tier());
    }

    /**
     * The bay's own fluid handler, <b>ungated</b>: the whole of its contents, in both directions, whatever
     * {@code storage.fluidBayPipeExtraction} says. The bay's own operations use this one; what pipes reach is
     * {@link #pipeView()}.
     */
    public IFluidHandler handler() {
        return handler;
    }

    /**
     * What pipes reach: the same contents with {@code drain} behind the server config
     * ({@link FluidBayHandler.PipeView}).
     */
    public IFluidHandler pipeView() {
        return pipeView;
    }

    /** The stored fluid, empty while the bay is empty. */
    public Optional<FluidKey> storedFluid() {
        return handler.stored();
    }

    /** {@link #storedFluid()} without the {@link Optional}, for the renderer and the fluid census. */
    @Nullable
    public FluidKey storedFluidOrNull() {
        return handler.storedOrNull();
    }

    /** How many millibuckets are stored. */
    public int millibuckets() {
        return handler.millibuckets();
    }

    /**
     * Server: fills as much of {@code stack} as fits and answers how much was really accepted, the way a fluid handler
     * does. The caller's stack is not modified. Used by tests, scripted scenes and the bay's own gestures, and it is
     * <b>ungated</b> — a player's bucket is never refused by the pipe-extraction config.
     */
    public int fill(FluidStack stack, boolean simulate) {
        return handler.fill(stack, simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
    }

    /** Server: drains up to {@code millibuckets} of whatever is stored, ungated ({@link #fill}'s twin). */
    public FluidStack drain(int millibuckets, boolean simulate) {
        return handler.drain(millibuckets,
                simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
    }

    /**
     * The fluid a player's filter names, or empty for an unfiltered bay — and for a filter that names <b>no</b> fluid
     * at all, which is a Create list or attribute filter (the goggles say so in gold).
     * <p>
     * The slot's stack is read <b>only</b> as a fluid container ({@code FluidContainers}, i.e.
     * {@code FluidUtil.getFluidContained}) and is deliberately <b>never</b> evaluated as a Create
     * {@code FilterItemStack}: a filter built from a water bucket matches the <i>item</i> {@code water_bucket} and
     * would route containers instead of fluids, which is the dangerous kind of free. The slot, the priority board and
     * the clipboard are reused; the evaluation is not (D6).
     * <p>
     * The answer is cached, because the handler asks it on <b>every</b> fill: reading a container means building a
     * probe stack and a capability lookup.
     */
    public Optional<FluidKey> filterFluid() {
        ItemStack filter = storeFilterBehaviour.getFilter();
        if (filter.isEmpty()) {
            filterSource = ItemStack.EMPTY;
            filterFluid = Optional.empty();
            return Optional.empty();
        }
        if (filterSource.isEmpty() || !ItemStack.isSameItemSameComponents(filterSource, filter)) {
            filterSource = filter.copy();
            filterFluid = FluidContainers.contentsOf(filter).map(FluidContainers.Contents::fluid);
        }
        return filterFluid;
    }

    /**
     * The one fluid this bay is dedicated to right now: the fluid its filter names, or — for an unfiltered bay — the
     * fluid it currently holds, and empty while an unfiltered bay is empty. "Takes the first fluid that arrives" is
     * therefore exact, and so is the gate for both cases.
     * <p>
     * This is what the planner's store gate will read (D6, M30 step 9); it changes only on the empty ↔ non-empty
     * transition and on a fluid change, never per millibucket, which is what {@link #onContentsChanged()} notifies on.
     */
    public Optional<FluidKey> dedicatedFluid() {
        Optional<FluidKey> filtered = filterFluid();
        return filtered.isPresent() ? filtered : storedFluid();
    }

    /** Whether a player gave this bay a filter at all — whatever that filter names. */
    public boolean hasStoreFilter() {
        return !storeFilterBehaviour.getFilter().isEmpty();
    }

    /** Server: sets the filter as a player click would (an empty stack clears it). */
    public boolean setStoreFilter(ItemStack filter) {
        return storeFilterBehaviour.setFilter(filter);
    }

    /** Server: sets the storage priority as the hold-to-edit board would (clamped to 0..9). */
    public boolean setStorePriority(int priority) {
        return storeFilterBehaviour.setPriority(priority);
    }

    /** The filter or the priority changed: drop the resolved fluid and let the controllers re-read both. */
    private void onStoreSettingsChanged() {
        filterSource = ItemStack.EMPTY;
        filterFluid = Optional.empty();
        if (level instanceof ServerLevel && !isRemoved()) {
            publishedDedication = dedicatedFluid().orElse(null);
            WarehouseRegistry.filterChanged(level, worldPosition);
        }
    }

    // --- storage location ------------------------------------------------------------------------------------------

    /** Direction into the rack depth, away from the aisle — the alignment rule of {@code LocationKind.STORAGE}. */
    @Override
    public Direction facing() {
        return getBlockState().getOptionalValue(FluidBayBlock.FACING).orElse(Direction.NORTH);
    }

    /** The face towards the aisle: the readable front, the crane's arm port, the value box — and <b>no</b> pipe. */
    public Direction aisleSide() {
        return facing().getOpposite();
    }

    /** A bay <b>is</b> its own tank, so the position the controller watches for loading is the bay's own. */
    @Override
    public BlockPos attachedPos() {
        return worldPosition;
    }

    /**
     * <b>Empty, by design</b>: a fluid bay holds no items at all, so there is no item handler to hand over — the same
     * answer a warehouse interface whose chest was broken gives, i.e. a state the controller has always handled by
     * counting nothing at the location.
     * <p>
     * The crane does <b>not</b> reach a bay through this, and {@code TransferContexts.resolve} therefore never looks
     * for a handler here: it gives a fluid bay a context of its own, whose one operation exchanges a filled container
     * for an empty one (D1). That is a different operation from an insert and is deliberately not reachable through an
     * {@code IItemHandler}.
     */
    @Override
    public Optional<IItemHandler> attachedHandler() {
        return Optional.empty();
    }

    /**
     * <b>Zero slots</b>, the documented "no inventory is attached" ({@link StorageMember#snapshot()}): a fluid bay
     * counts no items, so the controller's item stock index learns nothing from it and the planner finds no room for an
     * item at it. Its fluid is counted in a parallel index of fluid keys (M30 step 10, D10), never in the item one,
     * because {@code StockView.totalItems()} feeds the controller's goggle lines and summing millibuckets into an item
     * count would corrupt every readout downstream.
     */
    @Override
    public InventorySnapshot<ItemKey> snapshot() {
        return InventorySnapshot.empty();
    }

    /**
     * The other half of {@link #snapshot()}: one entry while this bay holds anything, in <b>millibuckets</b>, which is
     * what the controller puts straight into its parallel fluid stock index (M30 step 10, D10).
     * <p>
     * A bay holds exactly one fluid, so this is empty or a single entry, and it is empty the moment the bay empties —
     * {@link dev.wareworks.core.storage.BayContents} forgets its key with its last drop, which is the same rule that
     * makes an unfiltered bay take whatever arrives next. Two field reads, because the controller asks it on every
     * path on which it resolves this member and caches nothing.
     */
    @Override
    public Map<FluidKey, Long> fluidStock() {
        FluidKey stored = handler.storedOrNull();
        int millibuckets = handler.millibuckets();
        return stored == null || millibuckets <= 0 ? Map.of() : Map.of(stored, (long) millibuckets);
    }

    /**
     * <b>No.</b> The question is about item types, and a fluid bay holds none — a {@code true} answer would cost the
     * planner a stock read per candidate for a location that can never hold an item. The one-fluid rule is a fluid
     * filter in the store gate instead (D6), which is a gate on the fluid inside an arriving container.
     */
    @Override
    public boolean holdsOneTypeOnly() {
        return false;
    }

    /**
     * <b>No</b> while this bay carries something stronger above it in its column ({@link TieredBay#OVERLOADED},
     * ADR-044), exactly as for a rack bay: the warehouse plans nothing towards it while everything already inside
     * stays retrievable and nothing is moved, dropped or destroyed.
     */
    @Override
    public boolean acceptsStoring() {
        return !overloaded();
    }

    /** Whether this bay carries something stronger above it in its column; read off the block state. */
    public boolean overloaded() {
        return getBlockState().getOptionalValue(FluidBayBlock.OVERLOADED).orElse(false);
    }

    /** A copy of the filter stack a player put in the slot; empty means "accepts the first fluid that arrives". */
    @Override
    public ItemStack storeFilter() {
        return storeFilterBehaviour.getFilter().copy();
    }

    /**
     * <b>Always present</b>, which is what tells the planner that this location takes filled containers and no items
     * at all (M30 step 9, D6): {@link #dedicatedFluid()} when the bay knows its fluid, {@link FluidDedication#ANY}
     * while an unfiltered bay is still empty.
     * <p>
     * The empty answer of {@link StorageMember#storeFluidFilter()} means "not a fluid location", so a fluid bay never
     * gives it — not while it is empty, not while its filter names no fluid, and not while the column rule has taken it
     * out of service ({@link #acceptsStoring()} is the gate for that, and it decides first).
     */
    @Override
    public Optional<FluidDedication> storeFluidFilter() {
        return Optional.of(FluidDedication.from(dedicatedFluid()));
    }

    /** The storage priority a player gave this bay (0..9, higher fills first); it never affects retrieval. */
    @Override
    public int storePriority() {
        return storeFilterBehaviour.priority();
    }

    /**
     * Whether this bay has room left and still takes <b>no</b> bucket: less than one whole bucket is free, and a
     * container is emptied whole or refused (D5). The state the goggles explain in gold
     * ({@link WareworksLang#GOGGLES_FLUID_BAY_NO_BUCKET_ROOM}).
     * <p>
     * The question is asked about a <b>bucket</b> and not about "a container", because a bucket is the container every
     * player has and the one whose rule is a hard 1 000 mB; a modded 500 mB container would still fit in 999 mB of
     * room, and the line never claims otherwise. The arithmetic is the store gate's own
     * ({@link FluidBayTier#wholeContainers}), so the readout cannot disagree with the refusal.
     */
    public boolean noRoomForAWholeBucket() {
        long room = capacity() - millibuckets();
        return room > 0 && FluidBayTier.wholeContainers(FluidBayTier.MILLIBUCKETS_PER_BUCKET, room) == 0;
    }

    /**
     * The contents really changed (a pipe, a player's hand, a command): save, sync, bring the fill level in line and
     * tell the controllers of this rack position.
     * <p>
     * <b>The sync is deliberately unthrottled</b>, which is {@code RackBayBlockEntity#onContentsChanged}'s argument
     * verbatim: a bay's level is read off the block itself by anyone walking past, so it has to be right when nobody
     * wears goggles — and a throttle on a block with no ticker and no observer could never be flushed at all. The tag
     * is a fluid id and an int ({@link FluidBayHandler#writeClientPacket}), asserted against
     * {@code MAX_FLUID_BAY_SYNC_BYTES}.
     * <p>
     * The <b>store settings</b> are a different matter and are notified only when they really change: an unfiltered
     * bay's dedication is the fluid it holds, so it changes on the empty ↔ non-empty transition and on a fluid change
     * and <b>never per millibucket</b> — a pump filling a bay a bucket at a time must not make the controller re-read
     * its store settings twenty times a second (D6).
     */
    private void onContentsChanged() {
        setChanged(); // null-safe; also lets comparators and neighbours notice
        if (!(level instanceof ServerLevel) || isRemoved())
            return;
        // No BayColumn.contentsChanged here: an item bay's fill level is block state geometry, a fluid bay's level is
        // drawn from this block entity by FluidBayRenderer, and this family therefore publishes no TieredBay#FILL at
        // all (TieredBay#publishesFillLevel, ADR-053). Writing one anyway cost a client block-state change and a
        // chunk-section recompile into a byte-identical mesh on every fill step, beside the sync below that already
        // carries the level (M30 review fix).
        sendData();
        WarehouseRegistry.contentChanged(level, worldPosition);
        FluidKey dedication = dedicatedFluid().orElse(null);
        if (!Objects.equals(dedication, publishedDedication)) {
            publishedDedication = dedication;
            WarehouseRegistry.filterChanged(level, worldPosition);
        }
    }

    // --- goggles ---------------------------------------------------------------------------------------------------

    /** The address of this bay as of the last goggle observation (server) or sync (client). */
    public AisleAssignment aisleAssignment() {
        return assignment;
    }

    /** The reservations of this bay as of the last goggle observation (server) or sync (client). */
    public LocationReservationSummary reservationSummary() {
        return reservations;
    }

    /**
     * A player looks at this bay through goggles (server, from {@link GoggleObservers}): resolves the address in one
     * registry scan and syncs it if it changed.
     * <p>
     * The rack bay's method, less one thing it has and this block cannot. The <b>reservations</b> are read and synced
     * exactly as there: a store job towards a bay holds a {@code CAPACITY} reservation at its rack position while the
     * crane carries the container, and "Incoming: Lava Bucket x1" is the one answer to "why is nothing happening at my
     * bay" (M30 review fix — until the container exchange existed nothing could reserve here, and the javadoc that
     * said so outlived it). The <b>shadowed filter</b> warning is the thing that cannot apply: that flag means another
     * storage location counts the same <i>inventory</i>, and a fluid bay's inventory is empty by design — its own
     * position, with no item handler on it, can never be another location's attachment.
     * <p>
     * Nothing is re-read here: an address is geometry, and geometry changes notify by themselves.
     */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        WarehouseRegistry.StorageObservation next = WarehouseRegistry.observeStorage(level, worldPosition, this);
        if (next.assignment().equals(assignment) && next.reservations().equals(reservations))
            return;
        assignment = next.assignment();
        reservations = next.reservations();
        sendData();
    }

    /**
     * What a fluid bay says through Engineer's Goggles: the material, where it stands, which fluid belongs here, what
     * is in it, how much it holds, which way pipes may move fluid at the back, and — while there is anything to lose —
     * that breaking it loses the contents.
     * <p>
     * The header is the <b>block's own name</b> ("Copper Fluid Bay:") for the rack bay's reason: the material decides
     * the capacity and the column rule, and the block already says it in every language. Everything below it is
     * {@link #ownGoggleRows()}, which is where the decisions are and which is readable without a client font; this
     * method is only the address block every warehouse member shares and the indenting.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        WareworksLang.fluidBay(getBlockState().getBlock().getName()).forGoggles(tooltip);
        assignment.addGoggleLines(tooltip, WareworksLang.GOGGLES_BAY_MISALIGNED_HINT, 1);
        for (LangBuilder row : ownGoggleRows())
            row.forGoggles(tooltip, 1);
        // Last, as at a rack bay: what the warehouse has promised to bring here is news about a job and not about the
        // tank, so it reads after everything the block itself is.
        reservations.addGoggleLines(tooltip, 1);
        return true;
    }

    /**
     * The bay's <b>own</b> goggle rows, in the order they are drawn, each at one indent — everything but the header and
     * the address block.
     * <p>
     * It is a method of its own and not inlined above for one reason worth the extra name: {@code LangBuilder#forGoggles}
     * measures the client's font ({@code Minecraft.getInstance()}), so a tooltip assembled in one piece can only ever
     * be checked on a client, while <b>every decision of this block's readout is in this list</b>. Built this way it is
     * a server-safe answer a GameTest asserts row by row.
     * <p>
     * Five of the rows are decisions rather than phrasing:
     * <ul>
     * <li>a bay no warehouse serves adds one dark-grey line saying that it works by hand anyway. "Not part of an
     * aisle" is a defect for every other member of a warehouse and a plain fact for a tank that pipes fill and a
     * bucket empties;</li>
     * <li>the filter row says <b>"Holds: Lava"</b> and not "Filter: Lava Bucket", because the slot holds a container
     * and what is read from it is the fluid inside (D6). A slot that names no fluid at all — a Create list or
     * attribute filter, an empty bucket — gets a gold line saying so <b>and</b> the row for what the bay really does,
     * because such a bay is unfiltered and a player who thought they had dedicated it needs both halves;</li>
     * <li>the contents are one line of fluid, amount and capacity in <b>buckets</b> rather than Create's millibucket
     * pair: a bay has one tank and holds 64 000 mB, so "Slots: 1 / 1" would be useless and "64000 mB" unreadable
     * ({@link WareworksLang#fluidBayContents});</li>
     * <li>a bay with room on paper and <b>no room for a whole bucket</b> says so in gold
     * ({@link #noRoomForAWholeBucket()}). It is the one refusal of this block nothing else explains: the contents row
     * of a bay at 63 001 of 64 000 mB reads "63.00 / 64 buckets", so a refused bucket looks like a lost one;</li>
     * <li>the <b>pipe</b> row is shown whichever way {@code storage.fluidBayPipeExtraction} stands, always, because a
     * rule about where fluid may leave a warehouse must never be invisible — and the two answers are indistinguishable
     * from the outside until a pipe is already draining the wall (D4).</li>
     * </ul>
     * The <b>break</b> row is shown only while the bay holds something, so an empty tank wall says nothing about it.
     */
    public List<LangBuilder> ownGoggleRows() {
        List<LangBuilder> rows = new ArrayList<>();
        if (assignment.state() == AisleAssignment.State.NONE)
            rows.add(WareworksLang.translate(WareworksLang.GOGGLES_FLUID_BAY_NO_WAREHOUSE)
                    .style(ChatFormatting.DARK_GRAY));
        // What may enter. The filter stack is synced by Create's FilteringBehaviour itself, so the client can read the
        // fluid out of it exactly as the server does.
        Optional<FluidKey> filtered = filterFluid();
        Optional<FluidKey> stored = storedFluid();
        if (filtered.isPresent()) {
            rows.add(WareworksLang.fluidBayFilter(filtered.get().hoverName()));
        } else {
            if (hasStoreFilter())
                rows.add(WareworksLang.translate(WareworksLang.GOGGLES_FLUID_BAY_FILTER_NO_FLUID)
                        .style(ChatFormatting.GOLD));
            if (stored.isPresent())
                rows.add(WareworksLang.fluidBayLearned(stored.get().hoverName()));
            else
                rows.add(WareworksLang.translate(WareworksLang.GOGGLES_FLUID_BAY_ACCEPTS_FIRST)
                        .style(ChatFormatting.DARK_GRAY));
        }
        int priority = storeFilterBehaviour.priority();
        if (priority != StorageFilterBehaviour.MIN_PRIORITY)
            rows.add(WareworksLang.countLine(WareworksLang.GOGGLES_STORAGE_PRIORITY, priority));
        // The column rule, in gold: this bay is out of the warehouse's store plans and still keeps every drop and
        // still works by hand and by pipe, so it is a warning and not an error (ADR-044).
        if (overloaded())
            rows.add(WareworksLang.translate(WareworksLang.GOGGLES_FLUID_BAY_OVERLOADED).style(ChatFormatting.GOLD));
        if (stored.isEmpty()) {
            rows.add(WareworksLang.translate(WareworksLang.GOGGLES_EMPTY).style(ChatFormatting.DARK_GRAY));
        } else {
            rows.add(WareworksLang.fluidBayContents(stored.get().hoverName(), millibuckets(), buckets()));
            // Directly under the number that looks like it has room: a bay at 63 001 of 64 000 mB draws
            // "63.00 / 64 buckets" and takes nothing, because a container is emptied whole or refused (D5). The
            // condition is the store gate's own arithmetic, so the line cannot disagree with the refusal.
            if (noRoomForAWholeBucket())
                rows.add(WareworksLang.translate(WareworksLang.GOGGLES_FLUID_BAY_NO_BUCKET_ROOM)
                        .style(ChatFormatting.GOLD));
            // Directly under the contents it is about, and only while there are contents to lose (D7).
            rows.add(WareworksLang.translate(WareworksLang.GOGGLES_FLUID_BAY_BREAK_LOSES).style(ChatFormatting.GOLD));
        }
        rows.add(WareworksLang.countLine(WareworksLang.GOGGLES_FLUID_BAY_CAPACITY, buckets()));
        rows.add(WareworksLang.translate(WareworksConfig.fluidBayPipeExtraction()
                ? WareworksLang.GOGGLES_FLUID_BAY_PIPES_DRAW : WareworksLang.GOGGLES_FLUID_BAY_PIPES_FILL)
                .style(ChatFormatting.DARK_GRAY));
        return rows;
    }

    // --- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * There is no <b>fill level</b> to bring back in line, unlike at a rack bay: this family publishes none
     * ({@code TieredBay#publishesFillLevel}), because a fluid's level is drawn from this block entity rather than
     * baked into the chunk mesh (ADR-053). The level a client draws rides the update tag, which a chunk packet carries
     * anyway.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (!(level instanceof ServerLevel))
            return;
        publishedDedication = dedicatedFluid().orElse(null);
        onMembershipRelevantChange();
    }

    /**
     * The block state changed under this block entity, and whoever reads it has to be told in the same tick — the rack
     * bay's two cases, for the same two reasons: a <b>facing</b> change (a wrench, a structure) swaps aligned and
     * misaligned, so the rack position has to be probed again, while a changed {@link TieredBay#OVERLOADED} leaves the
     * same location with a flipped answer to "may I store here", which is exactly what a store filter change is. A
     * changed fill level matches neither and notifies nothing.
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
            onMembershipRelevantChange();
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
     * Block broken or replaced (server, via {@code IBE.onRemove}): <b>breaking a fluid bay loses the fluid</b>, and
     * the loss is logged with the fluid, the amount and the position (D7).
     * <p>
     * This is the one place a fluid bay is deliberately worse than its item sibling, and the reason is an asymmetry in
     * the world rather than a shortcut. A rack bay's load leaves as one {@link PalletEntity} because 65 536 item
     * entities was unacceptable — <b>not</b> because items needed a world form invented; items already drop. A fluid
     * has none, and every way of making one is worse: filled containers would create items from nothing, source blocks
     * would mean 256 lava sources in a wooden warehouse, a filled bay item is the pocketable removal crate ADR-046
     * refused, and a fluid tote is a second carrier with its own renderer, its own census branch and its own
     * conservation story. It is also <b>parity with the block this bay is measured against</b>: Create's own Fluid
     * Tank drops nothing when it is broken, and drains the overflow away when its configured size shrinks.
     * <p>
     * <b>Three places say so before a player can hit it</b>: the item description, the bay's own goggle line while it
     * holds anything, and the action-bar warning on the first punch ({@link FluidBayBlock#attack}). This method is
     * what happens after all three were ignored, so it is <b>loud</b>: one {@code WARN} naming the fluid, the
     * millibuckets and the position, because the one allowed loss in this mod must be greppable in a server log that
     * nobody was watching.
     * <p>
     * The contents are taken <b>first</b> ({@link FluidBayHandler#takeAll()}), so a second pass over this block entity
     * finds nothing and the bay is reset exactly as its item sibling is — the discipline the station buffer and the
     * rack bay already use. The loot table is the plain block, with no {@code copy_nbt} and no
     * {@code setBlockEntityData}, so the bay item carries nothing either.
     * <p>
     * It is not reached by a block state change of the bay itself: {@code IBE.onRemove} returns early when the block
     * stays the same and the new state still has a block entity, so a fill level that changes loses nothing.
     * {@code /setblock}, {@code /fill}, {@code /clone} and structure placement do not reach it either — they call
     * {@link #clearContent()} first — and a <b>Create schematic print</b> over a standing bay reaches neither, which is
     * what {@link FluidBayHandler#readFrom} is guarded for.
     */
    @Override
    public void destroy() {
        super.destroy();
        if (level == null || level.isClientSide)
            return;
        FluidKey lost = handler.storedOrNull();
        int millibuckets = handler.millibuckets();
        handler.takeAll(); // clears the contents BEFORE anything else can see them
        if (lost == null || millibuckets <= 0)
            return; // an empty bay loses nothing and says nothing
        // No section sign in the text: Minecraft's own log4j layout strips formatting codes, so a "§3.9" would be
        // printed as ".9" in the very log line this warning exists to be found in.
        Wareworks.LOGGER.warn("A fluid bay at {} was broken holding {} mB of {}; a fluid has no drop form, so it is "
                + "lost (docs/warehouse-system.md, section 3.9)", worldPosition, millibuckets, lost);
    }

    /**
     * {@link Clearable}: {@code /setblock}, {@code /fill}, {@code /clone} and structure placement call this before they
     * replace the block, and the bay is emptied without placing or dropping anything — deliberate parity with a vanilla
     * chest, and with Create's own Fluid Tank, which loses its fluid to the same commands.
     * <p>
     * Deliberately <b>silent</b>, unlike {@link #destroy()}: a command that empties a block is the caller's own doing
     * and happens by the thousand in a structure placement, so a warning per bay would bury the one line that is
     * about a player losing something.
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
     * A save carries the real contents; a <b>client packet</b> carries the fluid's registry <b>id</b> and the
     * millibuckets, and never a {@link FluidKey} ({@link FluidBayHandler#writeClientPacket}, §3.1.1 — an update tag is
     * part of every chunk packet).
     * <p>
     * {@code writeSafe} is deliberately left at Create's default, which does <b>not</b> call {@link #write}: a
     * schematic of a tank wall carries the filters and the priorities and not one drop of fluid, so a schematicannon
     * can never print a full bay.
     * <p>
     * The <b>address</b> is derived state: a client needs it for the goggles, a save does not. It is written whatever
     * it is, because a bay whose warehouse was broken has to be able to go back to "not part of an aisle".
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
        // Skipped while empty, which is every bay of every warehouse that is not currently being served, so an idle
        // tank wall pays only for the assignment (the rack bay's own rule).
        if (!reservations.isEmpty()) {
            CompoundTag reservationsTag = new CompoundTag();
            reservations.write(reservationsTag);
            tag.put(RESERVATIONS_TAG, reservationsTag);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            handler.readClientPacket(tag);
            assignment = AisleAssignment.read(tag.getCompound(ASSIGNMENT_TAG));
            reservations = LocationReservationSummary.read(tag.getCompound(RESERVATIONS_TAG));
            return;
        }
        handler.readFrom(tag, registries);
        // No scheduled repair, which a rack bay does need here: a save read into a bay that already stands in a level
        // is a command or a schematic print, and such a tag brings a block state of its own whose FILL may disagree
        // with the contents. A fluid bay publishes no FILL (TieredBay#publishesFillLevel), so there is nothing in its
        // block state the contents could contradict; the level a client draws is this block entity's own data.
    }

    /** What {@link FluidBayHandler} has to ask the bay; see {@link FluidBayHandler.Rules}. */
    private final class Rules implements FluidBayHandler.Rules {
        @Override
        public long capacity() {
            return FluidBayBlockEntity.this.capacity();
        }

        /**
         * The filter's fluid, if it names one. A filter that names no fluid at all — an empty slot, or a Create list
         * or attribute filter — accepts every fluid, which is the Create convention for an empty filter slot and the
         * honest answer for a filter this block cannot read as a fluid.
         */
        @Override
        public boolean passesFilter(FluidKey fluid) {
            return filterFluid().map(fluid::equals).orElse(true);
        }

        @Override
        public void onContentsChanged() {
            FluidBayBlockEntity.this.onContentsChanged();
        }

        @Override
        public BlockPos position() {
            return worldPosition;
        }
    }

    /**
     * Mod-bus listener: the bay answers {@code Capabilities.FluidHandler.BLOCK} on <b>every face except the one towards
     * the aisle</b>, plus for a {@code null} query, with the pipe view whose {@code drain} follows
     * {@code storage.fluidBayPipeExtraction} (D4).
     * <p>
     * Three things about that shape are decisions:
     * <ul>
     * <li><b>This is the mod's first sided registrar</b> — every other entry in {@code WareworksCapabilities} answers
     * on all sides. The aisle face is excluded so that a pipe can never stand in the crane's lane and so that the one
     * face a player clicks keeps its own meaning; the lateral, top, bottom and back faces are included for the rack
     * bay's own reason, that in a real wall most faces are covered by neighbours. Create asks exactly this question
     * with a real {@code Direction} ({@code FluidPropagator.hasFluidCapability}), and a face that answers
     * {@code null} is also a face at which a pipe end does not place a source block.</li>
     * <li><b>The {@code null} query is answered, and it is not optional.</b> A fluid census sweeps with {@code null}
     * ({@code gametest.FluidCensus}), and a block that answered a handler on a face but nothing for {@code null} would
     * be invisible to it while every conservation test stayed green — which is why that census fails a test outright
     * when it finds one. Every registration in Create answers {@code null} as well, even where it refuses a face.</li>
     * <li><b>The {@code null} query gets the same gated view</b>, so nothing reached through it can ever do more than
     * a pipe can.</li>
     * </ul>
     * Both views are final fields, so NeoForge's automatic invalidation is enough.
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, WareworksBlockEntityTypes.FLUID_BAY.get(),
                (be, side) -> side == null || side != be.aisleSide() ? be.pipeView : null);
    }
}
