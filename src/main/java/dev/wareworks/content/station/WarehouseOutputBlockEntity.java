package dev.wareworks.content.station;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ExtractOnlyItemHandler;
import dev.wareworks.content.item.ItemHandlerSnapshots;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.AttachedInventoryCache;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of the warehouse port ({@code docs/warehouse-system.md} §3.2, §7.2): a buffer of
 * {@code outputBufferSlots} slots that the crane fills and automation empties, plus the port's three settings.
 * <p>
 * Buffer, extract-only capability, the crane's {@code insert} API, the last rejection and the request goggle lines come
 * from {@link WarehouseDeliveryStationBlockEntity}; this class adds only how a port is <b>configured</b> and what it does
 * with a redstone signal.
 * <p>
 * <b>Automation.</b> The item capability is an {@link ExtractOnlyItemHandler}: funnels, chutes and hoppers can pull,
 * nothing can be pushed in. There is no belt input. A Create mechanical arm can take items out through the same view
 * ({@link DeliveryStationArmPoint}, take only; M12).
 * <p>
 * <b>Three settings, two value boxes</b> (M17, issue #12):
 * <ul>
 * <li>the <b>filter</b> and the requested <b>amount</b> on the filter slot, as before
 * ({@link RequestFilterBehaviour}): the exact filter stack as {@link ItemKey} (item and components) and up to the count,
 * at most one stack; unstackable items request 1. List, attribute and package filter items are refused, because a request
 * needs one concrete item; a clipboard carrying one is refused before Create takes anything from the player;</li>
 * <li>the <b>redstone behaviour</b> on the rows of that slot's hold-to-edit board ({@link PortRedstone});</li>
 * <li>the <b>direction and rank</b> on a second, <b>wrench-only</b> box ({@link PortRankBehaviour},
 * {@link PortRankValueBox}). Exactly one of the two boxes is eligible per hand state, which is why they may share the
 * same faces: hold a wrench to configure the port, anything else to set the filter.</li>
 * </ul>
 * Both boxes sit on the top, back and side faces, never on the bottom and never on the aisle side, where the crane
 * reaches into the opening.
 * <p>
 * <b>What a redstone change does</b> ({@link #onRedstoneChanged}, and only in the {@link PortDirection#REQUEST}
 * direction, which is all M17 wires up):
 * <ul>
 * <li>{@link PortRedstone#PULSE} — one request per rising edge, byte for byte what every warehouse output did before;</li>
 * <li>{@link PortRedstone#WHILE_POWERED} — requests whenever the signal is high <b>and this port has no open request</b>,
 * so a machine is kept supplied without a clock and never more than one trip is promised at a time. The controller tops
 * it up again on its own pass ({@code WarehouseControllerBlockEntity}), because a request can only be re-submitted once
 * the previous one closed;</li>
 * <li>{@link PortRedstone#UNLESS_POWERED} — the same with the signal inverted, so the falling edge is what starts it.</li>
 * </ul>
 * Switching a continuous port off never recalls what is already on its way: cancelling an open request would strand
 * reservations and surprise the player. In the {@link PortDirection#ACCEPT} and {@link PortDirection#COLLECT} directions a
 * rising edge only <b>arms</b> the port ({@link #isArmed()}); the store plan and the collect plan consume that token.
 * <p>
 * <b>Collecting</b> ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13) adds the third direction and nothing else
 * to this block: the crane reaches <b>through</b> the port into the inventory behind it ({@link #attachedPos()}), so the
 * port's own buffer stays unused in that direction — moving items into it would move items without the crane's handling
 * head, which the hard rules forbid — and the filter, the rank box and the redstone rows are the same three settings. The
 * inventory is read through a {@link AttachedInventoryCache}, the same helper the warehouse interface uses, so presence
 * needs no polling; contents are read only when the controller asks ({@link #collectSnapshot()}).
 * <p>
 * <b>Repeated pulses merge</b> ({@code docs/warehouse-system.md} §7.2, ADR-020): a pulse for an item this port already
 * waits for grows that request instead of queueing a second one, so a pulse clock produces one trip rather than one per
 * pulse. Because a merge takes no queue slot, the amount is bounded by {@link #maxRequestAmount()} instead of by the
 * per-port slot cap. A continuous port never merges, because it only ever submits while it waits for nothing.
 */
public class WarehouseOutputBlockEntity extends WarehouseDeliveryStationBlockEntity {
    /** NBT key of the unused rising edge of an accepting port in pulse mode. */
    public static final String ARMED_TAG = "PortArmed";
    /** NBT key of how many items this port has handed over as an accepting port. */
    public static final String EXPORTED_TAG = "PortExported";
    /** NBT key of how many items this port has fetched into the warehouse as a collecting port (M18, issue #13). */
    public static final String COLLECTED_TAG = "PortCollected";

    /**
     * Request filter, redstone behaviour and requested amount. Assigned in {@link #addBehaviours}, which
     * {@code SmartBlockEntity} calls from its constructor, so this field must not have an initializer.
     */
    protected RequestFilterBehaviour requestFilter;
    /** Direction and rank of the port, on its own wrench-only box. Assigned in {@link #addBehaviours}. */
    protected PortRankBehaviour portRank;

    /**
     * An accepting port in {@link PortRedstone#PULSE} mode saw a rising edge that no plan has used yet.
     * <p>
     * A boolean, not a counter: further pulses while armed do nothing, so a clock cannot accumulate an unbounded export
     * promise (the mirror image of the bounded pulse merge above). Saved, because a pulse a player gave must survive a
     * reload; cleared when the port becomes a requesting one again.
     */
    private boolean armed;

    /**
     * Items this port has handed over while accepting ({@code docs/warehouse-system.md} §3.2, M17). Saved, and only while
     * it is not 0, so a port that never exported anything writes nothing; a missing key reads as 0, which is why no
     * migration exists. It is the one number that says whether an overflow ever did anything — and, when a player piped
     * the port's chest back into an input, how much it is churning.
     */
    private long exported;

    /**
     * Items this port has fetched into the warehouse while collecting ({@code docs/warehouse-system.md} §3.2.4, M18,
     * issue #13). Counted at the <b>pick</b>, not at the drop, because that is when the items crossed the port's
     * threshold — and because the drop may end up in an input buffer after a reroute, which is still a collection.
     * <p>
     * Saved like {@link #exported}, and only while it is not 0, so a port that never collected anything writes nothing
     * and a missing key reads as 0: no migration exists.
     */
    private long collected;

    /**
     * The capability cache of the inventory <b>behind</b> this port ({@code pos − FACING}), the same helper and the same
     * lifecycle the warehouse interface uses for the inventory in front of it ({@link AttachedInventoryCache}, M18).
     * Presence therefore needs no polling at all; contents are read on demand only ({@link #collectSnapshot()}).
     * <p>
     * Its invalidation listener only marks the goggle packet pending — no level access, the listener runs while chunks
     * unload — so an inventory that appears or vanishes shows up on the port's tooltip at the next observation. What
     * tells the <b>controller</b> to read the inventory again are the block's neighbour hints
     * ({@link #onAttachedBlockChanged()}) and its throttled poll, never this listener.
     */
    private final AttachedInventoryCache attachedCache =
            new AttachedInventoryCache(() -> !isRemoved(), this::markSummaryDirty);

    public WarehouseOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, WareworksConfig.outputBufferSlots());
    }

    /** Registers the extract-only view for every side (listed in {@code WareworksCapabilities}). */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.get(),
                (be, side) -> be.externalHandler());
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Top, back and side faces carry both boxes: not the bottom, and not the aisle side, where the crane reaches
        // into the opening. Faces covered by rack neighbours simply cannot be clicked; a clipboard pastes on any face.
        portRank = new PortRankBehaviour(this, new PortRankValueBox());
        portRank.withCallback(rank -> onPortSettingsChanged());
        requestFilter = new RequestFilterBehaviour(this, new CenteredSideValueBoxTransform((state, side) ->
                side != Direction.DOWN && side != state.getOptionalValue(WarehouseStationBlock.FACING).orElse(null)),
                WarehouseOutputBlockEntity::isRequestable, this::portDirection);
        requestFilter.withRedstoneCallback(this::onPortSettingsChanged);
        // The filter is part of the port's policy too (M17): an accepting port's filter decides which items it takes at
        // all, and the controller caches it, so a changed filter has to reach the cache like a changed rank does.
        requestFilter.withCallback(stack -> onPortSettingsChanged());
        requestFilter.showCount();
        // No setLabel: the slot's name follows the port's direction, so RequestFilterBehaviour#getLabel answers it live
        // ("Requested Item" while it asks, "Accepted Item" while it takes items in; M17).
        // The filter slot goes first, so it keeps the place in getAllBehaviours() it has always had. The two can never
        // both take one click anyway: the port box is wrench-only and the filter slot refuses a wrench.
        behaviours.add(requestFilter);
        behaviours.add(portRank);
    }

    /** Whether {@code stack} can define a request: any concrete item, but no list, attribute or package filter. */
    public static boolean isRequestable(ItemStack stack) {
        return !(stack.getItem() instanceof FilterItem);
    }

    // --- port settings -------------------------------------------------------------------------------------------

    /** Direction, rank and redstone behaviour of this port. */
    public PortSettings portSettings() {
        return new PortSettings(portRank.rank(), requestFilter.redstoneMode());
    }

    /** What this port does. */
    public PortDirection portDirection() {
        return portRank.direction();
    }

    /** When this port acts. */
    public PortRedstone redstoneMode() {
        return requestFilter.redstoneMode();
    }

    /** The signed rank, {@code 0} for a requesting port. */
    public int portRank() {
        return portRank.rank();
    }

    /**
     * Sets direction and rank as the wrench board would: out-of-range values are clamped into the accept band, so only
     * {@link PortSettings#COLLECT_RANK} itself ever makes a collecting port ({@link PortSettings#clampRank}).
     *
     * @return whether it changed
     */
    public boolean setPortRank(int rank) {
        return portRank.setRank(rank);
    }

    /**
     * Sets the redstone behaviour as the filter board's rows would.
     *
     * @return whether it changed
     */
    public boolean setRedstoneMode(PortRedstone mode) {
        return requestFilter.setRedstoneMode(mode);
    }

    /** The port's stored redstone signal ({@link WarehouseOutputBlock#POWERED}). */
    public boolean isPowered() {
        return getBlockState().getOptionalValue(WarehouseOutputBlock.POWERED).orElse(false);
    }

    /** An accepting pulse port holds an unused rising edge. */
    public boolean isArmed() {
        return armed;
    }

    /** How many items this port has handed over while accepting (M17). */
    public long exportedItems() {
        return exported;
    }

    /** How many items this port has fetched into the warehouse while collecting (M18, issue #13). */
    public long collectedItems() {
        return collected;
    }

    /** Whether the crane fetches items out of the inventory behind this port (M18, issue #13). */
    public boolean isCollecting() {
        return portDirection() == PortDirection.COLLECT;
    }

    // --- the inventory a collecting port reaches into (M18, issue #13) -------------------------------------------

    /**
     * Position of the inventory this port collects from: the block <b>behind</b> it.
     * <p>
     * {@code FACING} points at the aisle, so this is {@code pos − FACING} — the same world position and the same
     * attached-block face a warehouse interface at this rack position would read ({@code pos + FACING} for a member that
     * faces away from the aisle). The crane's arm therefore reaches the rack position and transfers one block further,
     * exactly as it does for a storage location; nothing reaches past this block, and nothing is searched for.
     */
    public BlockPos attachedPos() {
        return worldPosition.relative(facing().getOpposite());
    }

    /**
     * The item handler of the inventory behind this port, queried from the face that touches the port — the side a funnel
     * on that face would take items from, which is what makes a furnace answer "the result slot" rather than "the fuel
     * slot".
     * <p>
     * Server: cached ({@link AttachedInventoryCache}); empty while the attached position is not loaded or nothing there
     * offers an item handler. Call it every time and never keep the handler across ticks.
     */
    public Optional<IItemHandler> attachedHandler() {
        if (level == null || isRemoved())
            return Optional.empty();
        Direction facing = facing();
        return attachedCache.handler(level, worldPosition.relative(facing.getOpposite()), facing);
    }

    /** Whether an inventory is attached behind this port right now (live query, see {@link #attachedHandler()}). */
    public boolean hasAttachedInventory() {
        return attachedHandler().isPresent();
    }

    /**
     * Reads the inventory behind this port slot by slot into a snapshot, for the controller's throttled collect queue
     * ({@code AisleCollections}). On demand only, never per tick.
     * <p>
     * {@link InventorySnapshot#empty()} (zero slots) when nothing is attached or the attached position is not loaded;
     * {@link #hasAttachedInventory()} tells "empty inventory" and "no inventory" apart.
     */
    public InventorySnapshot<ItemKey> collectSnapshot() {
        if (level == null || !level.isLoaded(attachedPos()))
            return InventorySnapshot.empty();
        return attachedHandler().map(ItemHandlerSnapshots::capture).orElseGet(InventorySnapshot::empty);
    }

    /**
     * Hint from the block: the inventory behind this port, or its contents, changed (a neighbour change or a block
     * update). Asks the controllers of this rack position to read it again within a few ticks, throttled and
     * de-duplicated on their side — the same channel the warehouse interface has always used for its own inventory
     * ({@code WarehouseRegistry#contentChanged}). Server only.
     * <p>
     * The <b>content</b> hint reaches this only for a port whose block state says {@code collecting}: the state is the
     * cheapest test there is, and without it every item moved in the chest behind an ordinary requesting port would walk a
     * hint through the registry into every controller of the level (M18 review). A <b>block</b> update still arrives
     * whatever the direction is, because it is rare; the controller drops the hint for a port that does not collect, and
     * the throttled collect poll covers the one case the state can lag behind these settings.
     */
    void onAttachedBlockChanged() {
        markSummaryDirty();
        if (level instanceof ServerLevel && !isRemoved())
            WarehouseRegistry.contentChanged(level, worldPosition);
    }

    /**
     * Server: the crane picked {@code amount} items out of the inventory behind this port as a <b>collect</b> job
     * ({@code WarehouseControllerBlockEntity#onCranePicked}, M18). The mirror of {@link #recordExport}, counted at the
     * real pick, which is when the items really crossed the port's threshold.
     */
    public void recordCollected(int amount) {
        if (amount < 1 || level == null || level.isClientSide || isRemoved())
            return;
        collected += amount;
        setChanged();
    }

    /**
     * Server: the crane dropped {@code amount} items here as the target of a <b>store</b> job, i.e. the warehouse handed
     * them over instead of storing them ({@code WarehouseControllerBlockEntity#onCraneDelivered}, M17). Only real drops
     * are counted, exactly as a request's delivered amount is.
     */
    public void recordExport(int amount) {
        if (amount < 1 || level == null || level.isClientSide || isRemoved())
            return;
        exported += amount;
        setChanged();
    }

    /**
     * Whether this port may act right now: the redstone gate, with the pulse token for an accepting port. A requesting
     * pulse port is never "open" — it acts on the edge itself.
     */
    public boolean isGateOpen() {
        return portSettings().gateOpen(isPowered(), armed);
    }

    /**
     * Server: takes the unused rising edge of an accepting pulse port, if there is one. Called by the planner step of
     * M17 when it turns the token into a job.
     *
     * @return whether a token was consumed
     */
    public boolean consumeArmed() {
        if (!armed)
            return false;
        setArmed(false);
        return true;
    }

    private void setArmed(boolean value) {
        if (armed == value)
            return;
        armed = value;
        setChanged();
    }

    /**
     * A setting changed (server): a requesting port holds no pulse token, the block's own direction display is brought
     * in step, and every controller whose aisle contains this port re-reads its policy at once, so the next planning run
     * and the next continuous pass already obey it.
     * <p>
     * Called from the value-box callbacks <b>and</b> from {@link #readStationData}, which is the only other way the
     * settings of a live port can change (a command, a schematic or the zapper overwriting its block entity data).
     */
    private void onPortSettingsChanged() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        if (portDirection() == PortDirection.REQUEST)
            setArmed(false);
        refreshDirectionState();
        WarehouseRegistry.portChanged(level, worldPosition);
    }

    /**
     * Brings {@link WarehouseOutputBlock#ACCEPTING} and {@link WarehouseOutputBlock#COLLECTING} in step with the rank,
     * which is the only source of truth. One write at most, and with {@code UPDATE_CLIENTS} alone, because the direction
     * is something a player reads and not something a neighbour reacts to (the stock keeper's lamp argument).
     * <p>
     * Two booleans for three directions allow one illegal state — both true — which only a {@code /setblock} or a
     * schematic can produce. It is <b>re-asserted from the rank</b> here, on every settings change, on load and from
     * {@link #readStationData}, so it is corrected rather than believed (M18, issue #13).
     */
    private void refreshDirectionState() {
        if (level == null || level.isClientSide || isRemoved())
            return;
        BlockState state = getBlockState();
        if (!state.hasProperty(WarehouseOutputBlock.ACCEPTING)
                || !state.hasProperty(WarehouseOutputBlock.COLLECTING))
            return;
        PortDirection direction = portDirection();
        boolean accepting = direction == PortDirection.ACCEPT;
        boolean collecting = direction == PortDirection.COLLECT;
        if (state.getValue(WarehouseOutputBlock.ACCEPTING) == accepting
                && state.getValue(WarehouseOutputBlock.COLLECTING) == collecting)
            return;
        level.setBlock(worldPosition, state.setValue(WarehouseOutputBlock.ACCEPTING, accepting)
                .setValue(WarehouseOutputBlock.COLLECTING, collecting), Block.UPDATE_CLIENTS);
    }

    // --- request -------------------------------------------------------------------------------------------------

    /**
     * The request filter slot's transform, for the block renderer: it positions the rank it paints on the back plate
     * against this box, exactly as the warehouse interface positions its priority digit (client).
     */
    public ValueBoxTransform requestFilterSlot() {
        return requestFilter.getSlotPositioning();
    }

    /** Whether a filter item is set. Hands out no copy, so it is free to ask per frame. */
    public boolean hasRequestFilter() {
        return !requestFilter.getFilter().isEmpty();
    }

    /** A copy of the filter stack (empty without filter). */
    public ItemStack requestedItem() {
        return requestFilter.getFilter().copy();
    }

    /**
     * The exact item the filter slot names, or empty for a port without a filter ({@code docs/warehouse-system.md} §3.2,
     * M17). One concrete item is all a filter slot of a port can hold, which is what lets the controller cache it as one
     * key and answer an accepting port's store filter with an equality test ({@code AislePorts#filterMatch}).
     */
    public Optional<ItemKey> filterKey() {
        ItemStack filter = requestFilter.getFilter();
        return filter.isEmpty() ? Optional.empty() : Optional.of(ItemKey.of(filter));
    }

    /**
     * The amount a request asks for, up to which the controller grants what is in stock: the filter count, clamped to
     * 1..one stack of the filter item; 0 without filter.
     */
    public int requestAmount() {
        ItemStack filter = requestFilter.getFilter();
        if (filter.isEmpty())
            return 0;
        return Mth.clamp(requestFilter.getAmount(), 1, filter.getMaxStackSize());
    }

    /**
     * The largest amount one open request of this port may wait for ({@code docs/warehouse-system.md} §7.2): as much
     * as its {@code maxOpenRequestsPerOutput} request slots could hold before repeated pulses merged, i.e. that many
     * pulses of {@link #requestAmount()}.
     * <p>
     * Since repeated pulses for one item grow a single request instead of taking a slot each, the per-output slot cap
     * no longer bounds what a pulse clock promises; this is that bound, so a clock is refused with
     * {@link RequestRejection#REQUEST_FULL} exactly where {@link RequestRejection#OUTPUT_FULL} refused it before,
     * instead of promising the aisle's whole stock of that item. A single pulse is never refused by it (the factor is
     * at least 1), and deliveries free room again. A continuous port never reaches it, because it submits only while it
     * waits for nothing at all.
     */
    public int maxRequestAmount() {
        long perOutput = Math.max(RequestQueue.MIN_OPEN_REQUESTS, WareworksConfig.maxOpenRequestsPerOutput());
        return (int) Math.min(Integer.MAX_VALUE, perOutput * Math.max(1, requestAmount()));
    }

    /**
     * Called by the block on <b>either</b> redstone edge (server). What it does is the port's redstone behaviour: a
     * pulse port acts on the rising edge, a continuous one starts or stops acting, and an accepting pulse port only
     * arms itself (see the class comment).
     */
    public void onRedstoneChanged(boolean powered) {
        if (level == null || level.isClientSide || isRemoved())
            return;
        PortSettings settings = portSettings();
        // Neither of the two directions that move items on the warehouse's own initiative submits a request: an edge only
        // arms them, and the plan that spends the token is the store plan (M17) or the collect plan (M18, issue #13).
        if (settings.direction() != PortDirection.REQUEST) {
            if (settings.redstone() == PortRedstone.PULSE && powered)
                setArmed(true);
            // A continuous port needs no action here: the planner reads the gate from the block state.
            return;
        }
        switch (settings.redstone()) {
            case PULSE -> {
                if (powered)
                    submitRequest();
            }
            case WHILE_POWERED -> {
                if (powered)
                    submitIfIdle();
            }
            case UNLESS_POWERED -> {
                if (!powered)
                    submitIfIdle();
            }
        }
    }

    /**
     * Server: submits the request defined by the filter slot to the controller of this port's aisle and remembers a
     * refusal. See {@link WarehouseControllerBlockEntity#request} for the rules.
     */
    public RequestResult submitRequest() {
        if (level == null || level.isClientSide || isRemoved())
            return RequestResult.rejected(RequestRejection.NO_CONTROLLER);
        return rememberRejection(resolveRequest());
    }

    /**
     * Server: submits <b>unless this port already waits for something</b> — the "at most one open request at a time"
     * rule of a continuous requesting port ({@code docs/warehouse-system.md} §7.2, M17). It is enforced on the open
     * requests rather than on the merge cap, so a continuous port is topped up trip by trip and never queues up.
     *
     * @return the result, or empty if the port still waits for a delivery or is no member of a live aisle
     */
    public Optional<RequestResult> submitIfIdle() {
        if (level == null || level.isClientSide || isRemoved())
            return Optional.empty();
        Optional<WarehouseControllerBlockEntity> controller = controller();
        if (controller.isEmpty()) {
            rememberRejection(RequestResult.rejected(RequestRejection.NO_CONTROLLER));
            return Optional.empty();
        }
        if (!controller.get().requestsFor(worldPosition).isEmpty())
            return Optional.empty();
        return Optional.of(submitRequest());
    }

    private RequestResult resolveRequest() {
        ItemStack filter = requestFilter.getFilter();
        if (filter.isEmpty())
            return RequestResult.rejected(RequestRejection.NO_FILTER);
        Optional<WarehouseControllerBlockEntity> controller = controller();
        if (controller.isEmpty())
            return RequestResult.rejected(RequestRejection.NO_CONTROLLER);
        // The cap is passed to the controller rather than applied here: repeated pulses for one item are merged into
        // the open request, so it must bound the merged remaining amount, not this pulse (§7.2, ADR-020).
        // A redstone pulse is the warehouse's own automation, so a stock rule's reserve holds items back from it: a
        // port must not be able to empty a buffer a player set aside overnight (M15, issue #3).
        return controller.get().request(worldPosition, ItemKey.of(filter), requestAmount(), maxRequestAmount(),
                StockAccess.AUTOMATION);
    }

    // --- station -------------------------------------------------------------------------------------------------

    @Override
    protected String goggleHeaderKey() {
        return WareworksLang.GOGGLES_WAREHOUSE_OUTPUT;
    }

    /**
     * Client: the port's own lines. A requesting port shows its request lines as before, plus what its redstone
     * behaviour is; an accepting port shows its rank and what it accepts instead — the request lines would be nonsense
     * there, and so would "last request refused".
     * <p>
     * The rank, the filter and the redstone mode are synced by the behaviours themselves (they travel in the block
     * entity's client packet) and the signal by the block state, so none of those needs a goggle summary field. The
     * <b>pulse token</b> does: it is a server field that no client packet carries, so it is read from {@code shown}
     * ({@link StationGoggleSummary#portArmed()}) and never from {@link #armed}, which on a client is always false.
     * <p>
     * Last of all, and only when a player built one, what a Create Packager behind the port makes of it
     * ({@link #addPackageLines}, M26) — the one group of lines that is read out of the world rather than out of the
     * port's own state.
     */
    @Override
    protected void addStationGoggleLines(List<Component> tooltip, StationGoggleSummary shown) {
        PortSettings settings = portSettings();
        switch (settings.direction()) {
            case REQUEST -> super.addStationGoggleLines(tooltip, shown);
            case ACCEPT -> addAcceptLines(tooltip, settings, shown);
            case COLLECT -> addCollectLines(tooltip, shown);
        }
        WareworksLang.translate(WareworksLang.GOGGLES_PORT_REDSTONE,
                WareworksLang.translateDirect(settings.redstone().langKey())).forGoggles(tooltip, 1);
        // Whether the gate is open right now, wherever that is a state at all: for every continuous port, and for an
        // accepting pulse port, whose unused rising edge is otherwise invisible — it is the one thing that says
        // whether the next store plan may export. A requesting pulse port is left out, because it acts on the edge
        // itself and is never "open": that is the line it never had before M17.
        if (settings.redstone().isContinuous() || settings.direction() != PortDirection.REQUEST)
            WareworksLang.translate(settings.gateOpen(isPowered(), shown.portArmed())
                            ? WareworksLang.GOGGLES_PORT_ACTIVE : WareworksLang.GOGGLES_PORT_WAITING)
                    .style(ChatFormatting.DARK_GRAY).forGoggles(tooltip, 2);
        addPackageLines(tooltip, settings);
    }

    /**
     * Client: what a Create <b>Packager</b> behind this port makes of it ({@code docs/warehouse-system.md} §3.2.5, M26,
     * issue #18) — that the goods leave as an addressed package, which address the next box the door sends will carry,
     * and the one failure of such a door that nothing else in the game diagnoses.
     * <p>
     * <b>Why the port says it at all.</b> Not one line of Wareworks code is in that item path: a Packager whose back
     * touches a port can only pack, because the port's capability is extract-only on every side, and the address is the
     * vanilla sign Create itself reads (ADR-040). The capability is therefore real and completely invisible — no
     * tooltip, no block, nothing — until these lines. They cost <b>no sync</b>: a goggle tooltip is built on the
     * client, and the client already has the Packager's state and the signs ({@link PackageHandover}).
     * <p>
     * <b>Why not for a collecting port.</b> A collecting port's buffer stays unused on purpose — the crane reaches
     * <b>through</b> it into the inventory behind it ({@link #attachedPos()}) — so a Packager there would pack nothing
     * but the odd leftover of an earlier direction, and "hands over as a package" would be a promise about an empty
     * buffer. A requesting port (the out door of the design) and an accepting one (an overflow that leaves as packages)
     * both really do hand their buffer over, so both say so.
     * <p>
     * <b>Why a linked Packager is told about instead of addressed.</b> The sign is read on exactly one branch of
     * Create's code, {@code if (!requestQueue && !signBasedAddress.isBlank())}
     * ({@code PackagerBlockEntity#attemptToSend:516-517}), and a {@code LINKED} Packager can never reach it: its two
     * redstone callers return at {@code !redstoneModeActive()} before {@code updateSignAddress()} and
     * {@code attemptToSend(null)} ({@code lazyTick:290-293}, {@code activate:352-356}), and the only other caller,
     * {@code LogisticsManager#performPackageRequests:212}, always passes a non-null request list, so
     * {@code requestQueue} is true and the box carries the <b>network order's</b> address instead. So while the link is
     * on, the sign row would predict an address no box will carry and the "hang a sign" row would ask for a block that
     * provably changes nothing — the gold line stands in their place, and the address returns with the next signal the
     * door answers.
     */
    private void addPackageLines(List<Component> tooltip, PortSettings settings) {
        if (level == null || settings.direction() == PortDirection.COLLECT)
            return;
        if (PackageHandover.packagerFor(level, worldPosition).isEmpty())
            return;
        WareworksLang.translate(WareworksLang.GOGGLES_PORT_PACKAGE_HANDOVER).forGoggles(tooltip, 1);
        Optional<String> address = packageAddressShown();
        // The line this whole step exists for: a Stock Link on the Packager turns every pulse and every lever into
        // nothing at all, for ever, and no block in the game says a word about it. It replaces the address rows rather
        // than following them, because a linked Packager never applies a sign (see above).
        if (address.isEmpty())
            WareworksLang.translate(WareworksLang.GOGGLES_PORT_PACKAGER_LINKED).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
        else if (address.get().isEmpty())
            // Gold: the build looks finished, and an unaddressed box is only ever delivered to a Package Port that has
            // no name of its own, or one named "*".
            WareworksLang.translate(WareworksLang.GOGGLES_PORT_PACKAGE_NO_ADDRESS).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
        else
            WareworksLang.packageAddress(address.get()).forGoggles(tooltip, 2);
    }

    /**
     * Whether this port's goggles name an address at all, and which — the decision {@link #addPackageLines} draws, as
     * a value a test can read.
     * <p>
     * It is a method of its own because a goggle tooltip cannot be built outside a client: {@code LangBuilder#forGoggles}
     * loads {@code Minecraft} for the indent, which a dedicated server refuses. So a GameTest asserts this instead,
     * which is the same decision and not a second copy of it.
     *
     * @return empty when no address row is drawn — no Packager stands there, this is a collecting port, or the
     *         Packager is {@code LINKED} and will never apply a sign, so the gold warning stands in their place;
     *         otherwise the address, which is the empty string for the gold "No address" row
     */
    public Optional<String> packageAddressShown() {
        if (level == null || portDirection() == PortDirection.COLLECT)
            return Optional.empty();
        Optional<BlockPos> packager = PackageHandover.packagerFor(level, worldPosition);
        if (packager.isEmpty() || PackageHandover.ignoresRedstone(level, packager.get()))
            return Optional.empty();
        return Optional.of(PackageHandover.addressAt(level, packager.get()));
    }

    /**
     * Client: what a <b>collecting</b> port fetches, out of what, and how much it has fetched (M18, issue #13).
     * <p>
     * There is no rank line: a collecting port has no magnitude at all, so there would be no number to show. Everything
     * a client cannot know by itself — the inventory behind the port, what its last read found and whether that inventory
     * is one this aisle already counts — comes from {@link PortCollectSummary} in {@code shown}.
     */
    private void addCollectLines(List<Component> tooltip, StationGoggleSummary shown) {
        WareworksLang.translate(WareworksLang.GOGGLES_PORT_COLLECTING).forGoggles(tooltip, 1);
        ItemStack filter = requestFilter.getFilter();
        WareworksLang.translate(WareworksLang.GOGGLES_PORT_COLLECTS, filter.isEmpty()
                        ? WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_ACCEPTS_ANY) : filter.getHoverName())
                .forGoggles(tooltip, 2);
        PortCollectSummary collect = shown.collect();
        if (collect.hasInventory()) {
            collect.attachedBlockName()
                    .ifPresent(name -> WareworksLang.attachedInventory(name).forGoggles(tooltip, 2));
            WareworksLang.countLine(WareworksLang.GOGGLES_PORT_COLLECT_READY, collect.ready()).forGoggles(tooltip, 2);
        } else {
            WareworksLang.translate(WareworksLang.GOGGLES_PORT_NO_INVENTORY).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
        }
        // The one mistake that would otherwise do nothing at all, silently: a port pointed at an inventory this aisle
        // already indexes is refused, because collecting from it would be an endless crane shuffle (§5, guard 3).
        if (collect.ownStorage())
            WareworksLang.translate(WareworksLang.GOGGLES_PORT_COLLECT_OWN_STORAGE).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
        // And the refusal a player hits most often, which only the controller knows: the warehouse is full, a stock rule
        // is at its maximum, or no storage filter accepts what is waiting (M18 review). Without it a player standing at
        // their machine reads "Ready: 24" and nothing that says why it stays 24.
        collect.refusalReason().ifPresent(reason -> WareworksLang
                .translate(WareworksLang.GOGGLES_PORT_COLLECT_REFUSED,
                        WareworksLang.translateDirect(WareworksLang.noJobReasonKey(reason)))
                .style(ChatFormatting.GOLD).forGoggles(tooltip, 2));
        WareworksLang.countLine(WareworksLang.GOGGLES_PORT_COLLECTED, collect.collected()).forGoggles(tooltip, 2);
    }

    private void addAcceptLines(List<Component> tooltip, PortSettings settings, StationGoggleSummary shown) {
        WareworksLang.translate(settings.isDiversion() ? WareworksLang.GOGGLES_PORT_DIVERSION
                        : WareworksLang.GOGGLES_PORT_OVERFLOW, PortSettings.formatRank(settings.rank()))
                .forGoggles(tooltip, 1);
        ItemStack filter = requestFilter.getFilter();
        WareworksLang.translate(WareworksLang.GOGGLES_PORT_ACCEPTS, filter.isEmpty()
                        ? WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_ACCEPTS_ANY) : filter.getHoverName())
                .forGoggles(tooltip, 2);
        WareworksLang.countLine(WareworksLang.GOGGLES_PORT_EXPORTED, shown.exportedItems()).forGoggles(tooltip, 2);
    }

    /**
     * Adds the two pieces of port state no other channel carries to a client (M17): what an accepting port has handed
     * over, and whether it holds an unspent rising edge.
     */
    @Override
    protected StationGoggleSummary createSummary() {
        StationGoggleSummary base = super.createSummary().withPort(exported, armed);
        return isCollecting() ? base.withCollect(collectSummary()) : base;
    }

    /**
     * Server: what a collecting port's goggles need and a client cannot know (M18, issue #13). Built only for a port that
     * really collects, so a requesting or accepting port's packet is byte for byte its own.
     * <p>
     * The <b>ready</b> count is the controller's <i>cached</i> snapshot, not a fresh read: it is the number the warehouse
     * really plans from, so a player sees what the machinery sees — including that it can lag by one poll interval — and
     * a goggle observation costs no inventory read at all.
     */
    private PortCollectSummary collectSummary() {
        Optional<WarehouseControllerBlockEntity> controller = controller();
        long ready = controller.map(c -> c.collectableAt(worldPosition)).orElse(0L);
        boolean ownStorage = controller.map(c -> c.collectsFromOwnStorage(worldPosition)).orElse(false);
        NoJobReason refusal = controller.flatMap(c -> c.collectRefusalAt(worldPosition)).orElse(null);
        ResourceLocation block = null;
        BlockPos attached = attachedPos();
        if (level != null && level.isLoaded(attached) && hasAttachedInventory())
            block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(attached).getBlock());
        return new PortCollectSummary(collected, ready, block, ownStorage, refusal);
    }

    // --- lifecycle and persistence -------------------------------------------------------------------------------

    /**
     * Brings the direction the block <b>shows</b> in step with the rank the block entity <b>holds</b>, for the one case
     * the two can disagree: a {@code /setblock} or a schematic that places the default state together with a configured
     * block entity. The guard on {@code isLoaded} keeps this out of chunk loading, where a {@code setBlock} has no
     * business and where the saved state is right anyway.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel && level.isLoaded(worldPosition))
            refreshDirectionState();
    }

    @Override
    protected void writeStationData(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeStationData(tag, registries);
        // Only while they are set: a port nobody armed and that exported nothing writes neither key, so a pre-M17
        // output's tag stays byte for byte its own.
        if (armed)
            tag.putBoolean(ARMED_TAG, true);
        if (exported > 0)
            tag.putLong(EXPORTED_TAG, exported);
        if (collected > 0)
            tag.putLong(COLLECTED_TAG, collected);
    }

    /**
     * {@code SmartBlockEntity} reads the behaviours — the rank, the filter and the redstone mode — before this, so the
     * whole policy is in place here, and this is where the block entity's data can change <b>without</b> anything else
     * noticing: {@code /data merge block}, a schematic print and Create's zapper all overwrite an existing block entity in
     * place ({@code BlockEntity#loadWithComponents}), which calls neither {@link #onLoad} nor any value-box callback. The
     * controller's port cache would then plan against a policy the block no longer has — and because a requesting port
     * writes no rank key at all, the cached policy can be the permissive one. Announcing the settings closes that: the
     * cache is re-read, the block's own direction display is brought in step, and a token a requesting port must not hold
     * is dropped.
     * <p>
     * Nothing happens while a save is being loaded, which is what {@code onPortSettingsChanged} guards against: a block
     * entity read from a chunk has no level yet, and that path is covered by the controller marking its ports unread.
     */
    @Override
    protected void readStationData(CompoundTag tag, HolderLookup.Provider registries) {
        super.readStationData(tag, registries);
        armed = tag.getBoolean(ARMED_TAG);
        exported = Math.max(0L, tag.getLong(EXPORTED_TAG));
        collected = Math.max(0L, tag.getLong(COLLECTED_TAG));
        onPortSettingsChanged();
    }

    /**
     * Rotated (wrench, structure placement): the inventory a collecting port reaches into is somewhere else now, so the
     * capability cache and its query side are void and the controllers of this rack position re-probe it (M18, issue
     * #13).
     */
    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction oldFacing = facing();
        super.setBlockState(state);
        if (facing() == oldFacing)
            return;
        attachedCache.drop();
        if (level instanceof ServerLevel && !isRemoved())
            WarehouseRegistry.contentChanged(level, worldPosition);
    }

    /** Removal or chunk unload: a cache of a removed owner can be permanently disabled, so it is dropped. */
    @Override
    public void invalidate() {
        super.invalidate();
        attachedCache.drop();
    }
}
