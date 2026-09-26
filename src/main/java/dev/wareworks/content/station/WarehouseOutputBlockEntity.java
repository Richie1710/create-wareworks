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
import dev.wareworks.content.item.ItemKey;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

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
 * reservations and surprise the player. In the {@link PortDirection#ACCEPT} direction a rising edge only <b>arms</b> the
 * port ({@link #isArmed()}); the store plan that will consume that token arrives in the next step of M17.
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
     * Sets direction and rank as the wrench board would (out-of-range values are clamped).
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
     * Brings {@link WarehouseOutputBlock#ACCEPTING} in step with the rank, which is the only source of truth. One write
     * at most, and with {@code UPDATE_CLIENTS} alone, because the direction is something a player reads and not
     * something a neighbour reacts to (the stock keeper's lamp argument).
     */
    private void refreshDirectionState() {
        if (level == null || level.isClientSide || isRemoved())
            return;
        BlockState state = getBlockState();
        if (!state.hasProperty(WarehouseOutputBlock.ACCEPTING))
            return;
        boolean accepting = portDirection() == PortDirection.ACCEPT;
        if (state.getValue(WarehouseOutputBlock.ACCEPTING) == accepting)
            return;
        level.setBlock(worldPosition, state.setValue(WarehouseOutputBlock.ACCEPTING, accepting), Block.UPDATE_CLIENTS);
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
        if (settings.direction() == PortDirection.ACCEPT) {
            if (settings.redstone() == PortRedstone.PULSE && powered)
                setArmed(true);
            // A continuous accepting port needs no action here: the planner reads the gate from the block state.
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
     */
    @Override
    protected void addStationGoggleLines(List<Component> tooltip, StationGoggleSummary shown) {
        PortSettings settings = portSettings();
        if (settings.direction() == PortDirection.REQUEST)
            super.addStationGoggleLines(tooltip, shown);
        else
            addAcceptLines(tooltip, settings, shown);
        WareworksLang.translate(WareworksLang.GOGGLES_PORT_REDSTONE,
                WareworksLang.translateDirect(settings.redstone().langKey())).forGoggles(tooltip, 1);
        // Whether the gate is open right now, wherever that is a state at all: for every continuous port, and for an
        // accepting pulse port, whose unused rising edge is otherwise invisible — it is the one thing that says
        // whether the next store plan may export. A requesting pulse port is left out, because it acts on the edge
        // itself and is never "open": that is the line it never had before M17.
        if (settings.redstone().isContinuous() || settings.direction() == PortDirection.ACCEPT)
            WareworksLang.translate(settings.gateOpen(isPowered(), shown.portArmed())
                            ? WareworksLang.GOGGLES_PORT_ACTIVE : WareworksLang.GOGGLES_PORT_WAITING)
                    .style(ChatFormatting.DARK_GRAY).forGoggles(tooltip, 2);
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
        return super.createSummary().withPort(exported, armed);
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
        onPortSettingsChanged();
    }
}
