package dev.wareworks.content.selector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of the Recipe Selector (ADR-085, design §2, §3.3, §6): the one writer of its machine's recipe filter on
 * behalf of one production station.
 * <p>
 * <b>No tick.</b> It acts on two events only. The station's controller asks it to arm the machine for a result
 * ({@link #armFor}, once per dispatch pass); and the machine tells it about every change of its own
 * ({@code RecipeSelectorBlock#onNeighborChange}, which {@code BlockEntity#setChanged} of the machine reaches in the
 * same tick), so a filter a hand changed is set back at once ({@link #onMachineChanged}).
 * <p>
 * <b>Pending selection</b> (design §6.1): {@link #applySelection} records the new selection <i>before</i> it writes the
 * filter, because the write notifies this very block synchronously; the set-back then sees "filter == selection" and
 * does nothing. A write that fails rolls the selection back. A guard ({@code applying}) and a cap of
 * {@value #MAX_SET_BACKS_PER_TICK} set-backs per tick keep two writers from recursing (I8).
 * <p>
 * <b>Never</b> overwrites a filter item (I6), never restores a filter when it is removed, and never touches the machine
 * on placement. A selector whose station is gone, unloaded or no longer links back sets nothing back.
 * <p>
 * <b>Persistence:</b> the station link, the selection, the status and the set-back counter under
 * {@value #STATE_TAG} in every full save; a schematic print carries none of it (it writes only the behaviours' safe
 * data), so a printed selector is unlinked. The client gets the selection and the status.
 * <p>
 * <b>Ender tier</b> (design §7.6): the same block entity, sneak-placed on a far factory's unpacking Packager. It never
 * registers anywhere and never asks the warehouse; it only judges each package the Packager is about to unpack
 * ({@code SlipVeto}, through {@code SelectorPackagerItemHandler} and the hand-path listener) and keeps what that needs:
 * its machine link, the station it serves (the <b>latch</b>, the first slip's origin) and the newest switch it has seen
 * from it ({@code LastSeq}), plus counters for the goggles. Link, latch and {@code LastSeq} are in full saves only.
 */
public class RecipeSelectorBlockEntity extends SmartBlockEntity
        implements LocalSelectorLink, IHaveGoggleInformation, GoggleObservers.Observable {
    /** NBT key of the selector's own state; present in every full save, never in a schematic print. */
    public static final String STATE_TAG = "RecipeSelector";
    /** At most this many set-backs per selector and game tick (design §6.2); the next one is CONTESTED. */
    public static final int MAX_SET_BACKS_PER_TICK = 4;

    private static final String STATION = "Station";
    private static final String SELECTION = "Selection";
    private static final String STATUS = "Status";
    private static final String SET_BACK = "SetBack";
    private static final String KIND = "MachineKind";
    private static final String MACHINE = "Machine";
    private static final String LATCH = "Latch";
    private static final String LAST_SEQ = "LastSeq";
    private static final String LAST_SLIP = "LastSlip";
    private static final String LAST_SLIP_TICK = "LastSlipTick";
    private static final String SLIPS_READ = "SlipsRead";
    private static final String WITHOUT_SLIP = "WithoutSlip";
    private static final String REFUSED = "Refused";
    /** NBT key of the goggle data (client packets only). */
    public static final String SUMMARY_TAG = "SelectorSummary";
    /** Refusal reasons the Ender goggles list, the most frequent first. */
    private static final int REFUSALS_SHOWN = 3;

    private Optional<BlockPos> station = Optional.empty();
    private Optional<ItemKey> selection = Optional.empty();
    private RecipeSelectorStatus status = RecipeSelectorStatus.NO_LINK;
    private int setBackCount;
    private Optional<SelectorMachine.Kind> machineKind = Optional.empty();

    /** True while this selector itself writes the filter: the notification that write causes is its own. */
    private boolean applying;
    /** The cap of {@link #MAX_SET_BACKS_PER_TICK}: the tick counted and the set-backs in it. */
    private long setBackTick = Long.MIN_VALUE;
    private int setBacksThisTick;
    /** Set when the cap was hit: no set-back until the next arm pass, which reports CONTESTED once. */
    private boolean contested;

    // Ender tier (design §7.6, §11).
    /** The machine a sneak-click linked the selector to; without one it serves the Packager's unpack target. */
    private Optional<BlockPos> machineLink = Optional.empty();
    /** The station this selector serves: the origin of the first slip it accepted. */
    private Optional<GlobalPos> latch = Optional.empty();
    /** The newest switch of the latched station seen so far (a slip's {@code Seq}). */
    private long lastSeq = Long.MIN_VALUE;
    private Optional<RecipeSlip> lastSlip = Optional.empty();
    private long lastSlipTick;
    private int slipsRead;
    private int withoutSlip;
    private final Map<RecipeSelectorStatus, Integer> refused = new EnumMap<>(RecipeSelectorStatus.class);
    private boolean failureLogged;
    /**
     * The last box counted as refused and why (review fix, Create API 5): a port offers a refused box again on every
     * retry, so the same box for the same reason counts once. Transient; cleared when a box is let through.
     */
    private ItemStack lastRefusedBox = ItemStack.EMPTY;
    @Nullable
    private RecipeSelectorStatus lastRefusedReason;
    /** The names the current status text takes (a refused box's station, the unpack target); transient. */
    private List<Component> statusDetail = List.of();
    /** What the goggles show, as of the last sync (server: as of the last observation). */
    private RecipeSelectorGoggleSummary summary = RecipeSelectorGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    public RecipeSelectorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    /** Ender tier: the Packager's item capability depends on this block, so a load asks NeoForge to look again. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide && isEnder())
            level.invalidateCapabilities(machinePos());
    }

    // --- what it is --------------------------------------------------------------------------------------------------

    /** Whether this is the far tier (Ender Recipe Selector), which never registers with a station. */
    public boolean isEnder() {
        return getBlockState().getBlock() instanceof RecipeSelectorBlock block && block.isEnder();
    }

    /** Where the selector's front faces. */
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(RecipeSelectorBlock.FACING) ? state.getValue(RecipeSelectorBlock.FACING)
                : Direction.NORTH;
    }

    /** The block the selector faces: its machine (local tier). */
    public BlockPos machinePos() {
        return worldPosition.relative(facing());
    }

    @Override
    public Optional<BlockPos> linkedStation() {
        return station;
    }

    /** The result the selector last set its machine to, if any. */
    public Optional<ItemKey> selection() {
        return selection;
    }

    public RecipeSelectorStatus status() {
        return status;
    }

    /** How often a hand change was set back since the selector was placed. */
    public int setBackCount() {
        return setBackCount;
    }

    @Override
    public Optional<SelectorMachine.Kind> machineKind() {
        if (level != null && !level.isClientSide && level.isLoaded(machinePos()))
            machineKind = SelectorMachine.resolve(level, machinePos()).map(SelectorMachine::kind);
        return machineKind;
    }

    // --- placement and removal ---------------------------------------------------------------------------------------

    /**
     * Server, on placement (local tier): links the selector to the station at {@code stationPos} and registers it there
     * (design §3.3). The item checked every rule before the block was placed.
     *
     * @return whether the station took the registration
     */
    public boolean linkAndRegister(BlockPos stationPos) {
        Objects.requireNonNull(stationPos, "stationPos");
        if (level == null || level.isClientSide || isEnder())
            return false;
        station = Optional.of(stationPos.immutable());
        selection = Optional.empty();
        status = RecipeSelectorStatus.IDLE;
        WarehouseProductionBlockEntity found = stationAt(stationPos);
        if (found == null) {
            status = RecipeSelectorStatus.STATION_MISSING;
            notifyUpdate();
            return false;
        }
        WarehouseProductionBlockEntity.SelectorModeChange change = found.registerSelector(worldPosition, machineKind());
        if (change != WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED) {
            Wareworks.LOGGER.warn("Recipe Selector at {}: station {} refused the registration: {}", worldPosition,
                    stationPos, change);
            station = Optional.empty();
            status = RecipeSelectorStatus.NO_LINK;
        }
        notifyUpdate();
        return change == WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED;
    }

    /**
     * Real removal (server, not a chunk unload): a loaded station forgets this selector but stays in local mode, so its
     * orders wait for a new one (design §3.3). The machine's filter stays as it is (I6).
     */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel && isEnder()) {
            level.invalidateCapabilities(machinePos());
            return;
        }
        if (!(level instanceof ServerLevel) || station.isEmpty())
            return;
        WarehouseProductionBlockEntity found = stationAt(station.get());
        if (found != null)
            found.selectorRemoved(worldPosition);
    }

    // --- arming (the station's controller) ---------------------------------------------------------------------------

    @Override
    public Optional<SelectorWait> armFor(ItemKey target, boolean switching) {
        Objects.requireNonNull(target, "target");
        if (level == null || level.isClientSide || isEnder())
            return Optional.of(SelectorWait.NO_SELECTOR);
        BlockPos machinePos = machinePos();
        if (!level.isLoaded(machinePos))
            return fault(RecipeSelectorStatus.MACHINE_NOT_LOADED, SelectorWait.MACHINE_NOT_LOADED);
        Optional<SelectorMachine> machine = SelectorMachine.resolve(level, machinePos);
        machineKind = machine.map(SelectorMachine::kind);
        if (machine.isEmpty())
            return fault(RecipeSelectorStatus.NO_MACHINE, SelectorWait.NO_MACHINE);
        if (machine.get().otherWriter(worldPosition).isPresent())
            return fault(RecipeSelectorStatus.CONTESTED, SelectorWait.CONTESTED);
        if (contested) {
            // The set-back cap was hit since the last pass: say so once, then set back again.
            contested = false;
            return fault(RecipeSelectorStatus.CONTESTED, SelectorWait.CONTESTED);
        }
        boolean filterOff = !machine.get().filterReads(target.getItem());
        SelectorMachine.ApplyResult result = applySelection(machine.get(), target, switching || filterOff);
        return switch (result) {
            case ALREADY, SET -> {
                setStatus(RecipeSelectorStatus.SET);
                yield Optional.empty();
            }
            case BUSY -> Optional.of(SelectorWait.MACHINE_BUSY);
            case FILTER_ITEM -> fault(RecipeSelectorStatus.FILTER_ITEM, SelectorWait.FILTER_ITEM);
            case REFUSED -> fault(RecipeSelectorStatus.FILTER_REFUSED, SelectorWait.FILTER_REFUSED);
        };
    }

    /**
     * The only writer on the local side (design §6.1): records {@code target} as the selection before the filter is
     * written, so the set-back that the write triggers in the same call sees nothing to do; rolls it back if the filter
     * does not read it afterwards.
     */
    private SelectorMachine.ApplyResult applySelection(SelectorMachine machine, ItemKey target, boolean requireIdle) {
        Optional<ItemKey> previous = selection;
        SelectorMachine.ApplyResult result;
        try {
            result = machine.apply(target.getItem(), requireIdle, () -> {
                selection = Optional.of(target);
                applying = true;
            });
        } finally {
            applying = false;
        }
        if (result.reads()) {
            if (!Optional.of(target).equals(previous)) {
                selection = Optional.of(target);
                notifyUpdate();
            }
        } else {
            selection = previous;
        }
        return result;
    }

    @Override
    public void clearSelection() {
        if (selection.isEmpty() && status == RecipeSelectorStatus.STATION_SWITCHED_OFF)
            return;
        selection = Optional.empty();
        status = RecipeSelectorStatus.STATION_SWITCHED_OFF;
        notifyUpdate();
    }

    // --- set-back (the machine changed) ------------------------------------------------------------------------------

    /**
     * Server: the machine in front of the selector changed (design §6.2). A filter that no longer names the selection
     * and is no filter item is set back, at most {@value #MAX_SET_BACKS_PER_TICK} times per tick, and only after the
     * station link and the one-writer rule were checked again. The cheapest check comes first, because a machine
     * changes many times a second.
     */
    public void onMachineChanged() {
        if (level == null || level.isClientSide || applying || selection.isEmpty() || isEnder())
            return;
        BlockPos machinePos = machinePos();
        if (!level.isLoaded(machinePos))
            return;
        Optional<SelectorMachine> machine = SelectorMachine.resolve(level, machinePos);
        if (machine.isEmpty())
            return;
        ItemKey wanted = selection.get();
        if (machine.get().filterReads(wanted.getItem()))
            return;
        if (machine.get().holdsFilterItem()) {
            setStatus(RecipeSelectorStatus.FILTER_ITEM);
            return;
        }
        if (!stationLinksBack()) {
            selection = Optional.empty();
            setStatus(RecipeSelectorStatus.STATION_MISSING);
            notifyUpdate();
            return;
        }
        if (machine.get().otherWriter(worldPosition).isPresent()) {
            setStatus(RecipeSelectorStatus.CONTESTED);
            return;
        }
        long now = level.getGameTime();
        if (now != setBackTick) {
            setBackTick = now;
            setBacksThisTick = 0;
        }
        if (contested || setBacksThisTick >= MAX_SET_BACKS_PER_TICK) {
            contested = true;
            setStatus(RecipeSelectorStatus.CONTESTED);
            return;
        }
        setBacksThisTick++;
        FilteringBehaviour filter = machine.get().filter();
        if (filter == null)
            return;
        applying = true;
        try {
            filter.setFilter(new ItemStack(wanted.getItem()));
        } finally {
            applying = false;
        }
        setBackCount++;
        setStatus(RecipeSelectorStatus.SET_BACK);
        setChanged();
    }

    /** Whether the linked station is loaded, in local mode and links back to this selector; never loads a chunk. */
    private boolean stationLinksBack() {
        if (station.isEmpty() || level == null || !level.isLoaded(station.get()))
            return false;
        WarehouseProductionBlockEntity found = stationAt(station.get());
        return found != null && found.selectorMode() == SelectorMode.LOCAL
                && found.linkedSelector().filter(worldPosition::equals).isPresent();
    }

    private WarehouseProductionBlockEntity stationAt(BlockPos pos) {
        if (level == null || !level.isLoaded(pos))
            return null;
        return level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity found && !found.isRemoved() ? found
                : null;
    }

    private Optional<SelectorWait> fault(RecipeSelectorStatus newStatus, SelectorWait wait) {
        setStatus(newStatus);
        return Optional.of(wait);
    }

    private void setStatus(RecipeSelectorStatus newStatus) {
        if (status == newStatus)
            return;
        status = newStatus;
        statusDetail = List.of();
        setChanged();
    }

    // --- Ender tier (design §7.6) -------------------------------------------------------------------------------------

    /** Ender: the machine a sneak-click linked the selector to, if any (same dimension as the selector). */
    public Optional<BlockPos> machineLink() {
        return machineLink;
    }

    /** Ender: links (or with empty unlinks) the machine this selector sets; the item checked the range. */
    public void setMachineLink(Optional<BlockPos> machine) {
        Objects.requireNonNull(machine, "machine");
        Optional<BlockPos> next = machine.map(BlockPos::immutable);
        if (next.equals(machineLink))
            return;
        machineLink = next;
        status = RecipeSelectorStatus.WAITING_FOR_SLIP;
        notifyUpdate();
    }

    /** Ender: the station this selector serves, the origin of the first slip it accepted. */
    public Optional<GlobalPos> latch() {
        return latch;
    }

    /** Ender: the newest switch of the latched station it has accepted ({@link Long#MIN_VALUE} for none). */
    public long lastSeq() {
        return lastSeq;
    }

    /** Ender: the last slip accepted, for the goggles. */
    public Optional<RecipeSlip> lastSlip() {
        return lastSlip;
    }

    /** Ender: the game time the last slip was accepted at. */
    public long lastSlipTick() {
        return lastSlipTick;
    }

    /** Ender: how many boxes with a slip were let through. */
    public int slipsRead() {
        return slipsRead;
    }

    /** Ender: how many boxes without a slip were let through (the filter was left alone). */
    public int boxesWithoutSlip() {
        return withoutSlip;
    }

    /** Ender: how many boxes were refused for {@code reason}. */
    public int refusedFor(RecipeSelectorStatus reason) {
        return refused.getOrDefault(reason, 0);
    }

    /**
     * Ender, server, on placement: takes the item's machine link (or none: the Packager's unpack target then) and waits
     * for the first slip; NeoForge is asked to look at the Packager's item capability again.
     */
    public void placedAsEnder(Optional<BlockPos> machine) {
        Objects.requireNonNull(machine, "machine");
        machineLink = machine.map(BlockPos::immutable);
        latch = Optional.empty();
        lastSeq = Long.MIN_VALUE;
        status = RecipeSelectorStatus.WAITING_FOR_SLIP;
        notifyUpdate();
        if (level != null && !level.isClientSide)
            level.invalidateCapabilities(machinePos());
    }

    /** Ender: forgets the station it serves, so a slip from any station is taken again (design §3.2). */
    public void clearLatch() {
        latch = Optional.empty();
        lastSeq = Long.MIN_VALUE;
        status = RecipeSelectorStatus.WAITING_FOR_SLIP;
        notifyUpdate();
    }

    /**
     * Ender, server: a box was let through ({@code SlipVeto} accepted it). A box with a slip latches the selector to
     * its station and raises the newest switch seen; one without only counts.
     */
    public void accepted(Optional<RecipeSlip> slip) {
        Objects.requireNonNull(slip, "slip");
        lastRefusedBox = ItemStack.EMPTY;
        lastRefusedReason = null;
        if (slip.isEmpty()) {
            withoutSlip++;
            // Synced (review fix, tests 13): the goggles' "n with slip, m without" must not go stale for these.
            notifyUpdate();
            return;
        }
        RecipeSlip read = slip.get();
        if (latch.isEmpty() || !latch.get().equals(read.origin())) {
            latch = Optional.of(read.origin());
            lastSeq = read.seq();
        } else {
            lastSeq = Math.max(lastSeq, read.seq());
        }
        lastSlip = slip;
        lastSlipTick = level == null ? 0L : level.getGameTime();
        slipsRead++;
        status = RecipeSelectorStatus.SET;
        notifyUpdate();
    }

    /**
     * Ender, server: the box {@code box} was refused for {@code reason}; it stays where it was. {@code count} is false
     * for a simulated hand-over (a port asks before it really gives), so the counters count real attempts only.
     */
    public void refused(RecipeSelectorStatus reason, ItemStack box, boolean count) {
        refused(reason, box, count, List.of());
    }

    /**
     * Ender, server: as {@link #refused(RecipeSelectorStatus, ItemStack, boolean)}, with the names the status text takes
     * (the box's station, the station served, the unpack target), which the goggles show.
     * <p>
     * Counts boxes, not attempts (review fix, Create API 5): a frogport or a bee port offers a refused box again on
     * every retry, so a real refusal counts only when the box (item, count and components) or the reason differs from
     * the last one counted. Marks the selector changed only when its status or a counter changed: every
     * {@code setChanged} notifies the Packager's neighbours.
     */
    public void refused(RecipeSelectorStatus reason, ItemStack box, boolean count, List<Component> detail) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(detail, "detail");
        boolean counted = count && (reason != lastRefusedReason || !ItemStack.matches(box, lastRefusedBox));
        if (counted) {
            refused.merge(reason, 1, Integer::sum);
            lastRefusedBox = box.copy();
            lastRefusedReason = reason;
        }
        if (status != reason || counted) {
            status = reason;
            setChanged();
        }
        statusDetail = List.copyOf(detail.size() <= RecipeSelectorGoggleSummary.MAX_DETAIL ? detail
                : detail.subList(0, RecipeSelectorGoggleSummary.MAX_DETAIL));
    }

    /** Ender, server: the check failed with {@code error}; the box was refused (fail closed). Logged once. */
    public void fail(RuntimeException error, ItemStack box) {
        if (!failureLogged) {
            failureLogged = true;
            Wareworks.LOGGER.error("Ender Recipe Selector at {} refused a package after an error", worldPosition, error);
        }
        refused(RecipeSelectorStatus.FAILED, box, true);
    }

    /** Ender: sets the status without counting anything. */
    public void showStatus(RecipeSelectorStatus newStatus) {
        setStatus(Objects.requireNonNull(newStatus, "newStatus"));
    }

    // --- goggles (ADR-085, design §12) --------------------------------------------------------------------------------

    /** The goggle data as of the last sync (client) or observation (server). */
    public RecipeSelectorGoggleSummary summary() {
        return summary;
    }

    /**
     * A player looks at the selector through goggles (server): rebuild the summary and sync a change, at most once a
     * second ({@link SyncThrottle}). The Ender tier also checks its Packager here, and only here (design §7.6, §14):
     * whether the Packager's item capability is really this selector's veto ({@code NO_VETO}), and whether a package
     * port touching the Packager also pushes into another inventory ({@code PORT_BYPASS}).
     */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        RecipeSelectorGoggleSummary next = createSummary();
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData();
    }

    /** Builds what the goggles show now (server). Bounded reads: the station, the machine, the Packager's neighbours. */
    public RecipeSelectorGoggleSummary createSummary() {
        boolean ender = isEnder();
        Optional<BlockPos> machine = ender ? machineLink : Optional.of(machinePos());
        Optional<SelectorMachine> resolved = machine.filter(level::isLoaded)
                .flatMap(pos -> SelectorMachine.resolve(level, pos));
        Optional<SelectorMachine.Kind> kind = resolved.map(SelectorMachine::kind).or(() -> machineKind);
        boolean filterKnown = resolved.isPresent();
        Optional<ResourceLocation> filter = resolved.map(SelectorMachine::currentFilter)
                .filter(stack -> !stack.isEmpty()).map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()));
        Optional<String> stationLabel;
        Optional<RecipeSelectorStatus> warning = Optional.empty();
        if (ender) {
            stationLabel = latch.map(origin -> lastSlip.filter(slip -> slip.origin().equals(origin))
                    .map(RecipeSlip::from).orElseGet(() -> label(origin.pos())));
            warning = packagerWarning();
        } else {
            stationLabel = station.map(this::stationLabel);
        }
        return new RecipeSelectorGoggleSummary(ender, status, statusDetail, warning, stationLabel, machine, kind,
                filter, filterKnown, lastSlip.map(RecipeSlip::result), lastSlip.map(RecipeSlip::from).orElse(""),
                lastSlipTick, slipsRead, withoutSlip, refused, setBackCount);
    }

    /** The station's address as the warehouse names it, else its position. */
    private String stationLabel(BlockPos pos) {
        if (level != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity
                found && !found.isRemoved()) {
            Optional<String> address = WarehouseRegistry.findController(level, pos)
                    .flatMap(controller -> controller.addressOf(pos)).map(StorageAddress::format);
            if (address.isPresent())
                return address.get();
        }
        return label(pos);
    }

    private static String label(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * Ender: {@code NO_VETO} when the Packager's item capability is not this selector's veto handler (another mod
     * overrides it above us), else {@code PORT_BYPASS} when a package port touching the Packager also pushes boxes into
     * another inventory: a frogport pushes down only, any other port (a postbox, a bee port) into every neighbour. At
     * most 6 x 6 block reads.
     */
    private Optional<RecipeSelectorStatus> packagerWarning() {
        BlockPos packagerPos = machinePos();
        if (!level.isLoaded(packagerPos) || !(level.getBlockEntity(packagerPos) instanceof PackagerBlockEntity))
            return Optional.empty();
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, packagerPos, null);
        if (handler != null && !(handler instanceof SelectorPackagerItemHandler))
            return Optional.of(RecipeSelectorStatus.NO_VETO);
        for (Direction side : Direction.values()) {
            BlockPos portPos = packagerPos.relative(side);
            if (!level.isLoaded(portPos) || !(level.getBlockEntity(portPos) instanceof PackagePortBlockEntity port))
                continue;
            List<Direction> pushes = port instanceof FrogportBlockEntity ? List.of(Direction.DOWN)
                    : List.of(Direction.values());
            for (Direction push : pushes) {
                BlockPos target = portPos.relative(push);
                if (target.equals(packagerPos) || !level.isLoaded(target)
                        || level.getBlockEntity(target) instanceof PackagePortBlockEntity)
                    continue;
                if (level.getBlockEntity(target) != null
                        && level.getCapability(Capabilities.ItemHandler.BLOCK, target, push.getOpposite()) != null)
                    return Optional.of(RecipeSelectorStatus.PORT_BYPASS);
            }
        }
        return Optional.empty();
    }

    /**
     * The selector's goggle lines (client): the header, the status (gold while it stops something), a warning about its
     * Packager, the station, the machine and its filter, and for the Ender tier the last slip, the packages let through
     * and the most frequent refusals.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        RecipeSelectorGoggleSummary shown = summary;
        WareworksLang.translate(shown.ender() ? WareworksLang.GOGGLES_ENDER_RECIPE_SELECTOR
                : WareworksLang.GOGGLES_RECIPE_SELECTOR).forGoggles(tooltip);
        ChatFormatting statusStyle = switch (shown.status()) {
            case SET, IDLE, WAITING_FOR_SLIP -> ChatFormatting.WHITE;
            case SET_BACK, WAITS_FOR_EMPTY_LINE -> ChatFormatting.GRAY;
            default -> ChatFormatting.GOLD;
        };
        WareworksLang.builder().add(shown.statusText()).style(statusStyle).forGoggles(tooltip, 1);
        shown.warning().ifPresent(found -> WareworksLang.translate(found.langKey()).style(ChatFormatting.GOLD)
                .forGoggles(tooltip, 1));
        shown.station().ifPresent(label -> WareworksLang.translate(shown.ender() ? WareworksLang.GOGGLES_SELECTOR_LATCH
                : WareworksLang.GOGGLES_SELECTOR_STATION, label).style(ChatFormatting.GRAY).forGoggles(tooltip, 1));
        shown.machine().ifPresent(pos -> WareworksLang.translate(WareworksLang.GOGGLES_SELECTOR_MACHINE,
                        shown.kind().map(SelectorTexts::machineName).orElse(Component.literal("?")), label(pos))
                .style(ChatFormatting.GRAY).forGoggles(tooltip, 1));
        if (shown.filterKnown()) {
            Optional<Item> filter = shown.filter().flatMap(BuiltInRegistries.ITEM::getOptional);
            if (filter.isPresent())
                WareworksLang.translate(WareworksLang.GOGGLES_SELECTOR_FILTER,
                        new ItemStack(filter.get()).getHoverName()).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
            else
                WareworksLang.translate(WareworksLang.GOGGLES_SELECTOR_FILTER_NONE).style(ChatFormatting.GRAY)
                        .forGoggles(tooltip, 1);
        }
        if (!shown.ender()) {
            if (shown.setBacks() > 0)
                WareworksLang.countLine(WareworksLang.GOGGLES_SELECTOR_SET_BACKS, shown.setBacks())
                        .style(ChatFormatting.DARK_GRAY).forGoggles(tooltip, 1);
            return true;
        }
        shown.lastSlip().flatMap(BuiltInRegistries.ITEM::getOptional).ifPresent(item -> {
            long now = level == null ? shown.lastSlipTick() : level.getGameTime();
            long seconds = Math.max(0L, now - shown.lastSlipTick()) / 20L;
            WareworksLang.translate(WareworksLang.GOGGLES_SELECTOR_LAST_SLIP, new ItemStack(item).getHoverName(),
                    shown.lastSlipFrom(), LangNumberFormat.format(seconds)).style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, 1);
        });
        if (shown.slipsRead() > 0 || shown.withoutSlip() > 0)
            WareworksLang.translate(WareworksLang.GOGGLES_SELECTOR_BOXES, LangNumberFormat.format(shown.slipsRead()),
                    LangNumberFormat.format(shown.withoutSlip())).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
        for (Map.Entry<RecipeSelectorStatus, Integer> entry : shown.topRefusals(REFUSALS_SHOWN))
            WareworksLang.translate(WareworksLang.GOGGLES_SELECTOR_REFUSED,
                            WareworksLang.translateDirect(WareworksLang.selectorReasonKey(entry.getKey())),
                            LangNumberFormat.format(entry.getValue()))
                    .style(ChatFormatting.DARK_GRAY).forGoggles(tooltip, 1);
        return true;
    }

    // --- GameTests ---------------------------------------------------------------------------------------------------

    /**
     * GameTests only (design test 24): makes this selector believe it sets {@code target} without touching the
     * machine, as a second writer forced in past the placement rule would.
     */
    public void forceSelectionForTest(ItemKey target) {
        selection = Optional.of(Objects.requireNonNull(target, "target"));
    }

    /** GameTests only: links to {@code stationPos} without registering, as a selector forced in past the item would. */
    public void forceStationForTest(BlockPos stationPos) {
        station = Optional.of(stationPos.immutable());
    }

    // --- persistence and sync ----------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        CompoundTag state = new CompoundTag();
        selection.ifPresent(key -> state.put(SELECTION, key.save(registries)));
        state.putString(STATUS, status.name());
        if (!clientPacket) {
            station.ifPresent(pos -> state.putLong(STATION, pos.asLong()));
            state.putInt(SET_BACK, setBackCount);
            machineKind.ifPresent(kind -> state.putString(KIND, kind.name()));
            machineLink.ifPresent(pos -> state.putLong(MACHINE, pos.asLong()));
            latch.flatMap(origin -> GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, origin).result())
                    .ifPresent(origin -> state.put(LATCH, origin));
            if (lastSeq != Long.MIN_VALUE)
                state.putLong(LAST_SEQ, lastSeq);
        }
        if (isEnder()) {
            lastSlip.ifPresent(slip -> state.put(LAST_SLIP, slip.toTag()));
            state.putLong(LAST_SLIP_TICK, lastSlipTick);
            state.putInt(SLIPS_READ, slipsRead);
            state.putInt(WITHOUT_SLIP, withoutSlip);
            CompoundTag counts = new CompoundTag();
            refused.forEach((reason, count) -> counts.putInt(reason.name(), count));
            state.put(REFUSED, counts);
        }
        tag.put(STATE_TAG, state);
        if (clientPacket) {
            CompoundTag goggles = new CompoundTag();
            summary.write(goggles, registries);
            tag.put(SUMMARY_TAG, goggles);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        // A tag without the selector's state is no save of one: a schematic print (or a print landing on a standing
        // selector) says nothing about the link, so a printed selector stays unlinked.
        if (clientPacket && tag.contains(SUMMARY_TAG, Tag.TAG_COMPOUND))
            summary = RecipeSelectorGoggleSummary.read(tag.getCompound(SUMMARY_TAG), registries);
        if (!tag.contains(STATE_TAG, Tag.TAG_COMPOUND))
            return;
        CompoundTag state = tag.getCompound(STATE_TAG);
        selection = state.contains(SELECTION) ? ItemKey.load(registries, state.get(SELECTION)) : Optional.empty();
        status = RecipeSelectorStatus.byName(state.getString(STATUS));
        lastSlip = state.contains(LAST_SLIP, Tag.TAG_COMPOUND) ? RecipeSlip.fromTag(state.getCompound(LAST_SLIP))
                : Optional.empty();
        lastSlipTick = state.getLong(LAST_SLIP_TICK);
        slipsRead = Math.max(0, state.getInt(SLIPS_READ));
        withoutSlip = Math.max(0, state.getInt(WITHOUT_SLIP));
        refused.clear();
        CompoundTag counts = state.getCompound(REFUSED);
        for (String name : counts.getAllKeys()) {
            RecipeSelectorStatus reason = RecipeSelectorStatus.byName(name);
            if (reason.name().equals(name) && counts.getInt(name) > 0)
                refused.put(reason, counts.getInt(name));
        }
        if (clientPacket)
            return;
        machineLink = state.contains(MACHINE, Tag.TAG_LONG) ? Optional.of(BlockPos.of(state.getLong(MACHINE)))
                : Optional.empty();
        latch = state.contains(LATCH) ? GlobalPos.CODEC.parse(NbtOps.INSTANCE, state.get(LATCH)).result()
                : Optional.empty();
        lastSeq = state.contains(LAST_SEQ, Tag.TAG_LONG) ? state.getLong(LAST_SEQ) : Long.MIN_VALUE;
        station = state.contains(STATION, Tag.TAG_LONG) ? Optional.of(BlockPos.of(state.getLong(STATION)))
                : Optional.empty();
        setBackCount = Math.max(0, state.getInt(SET_BACK));
        machineKind = SelectorMachine.Kind.byName(state.getString(KIND));
        if (!isEnder() && station.isEmpty() && status != RecipeSelectorStatus.NO_LINK)
            status = RecipeSelectorStatus.NO_LINK;
    }
}
