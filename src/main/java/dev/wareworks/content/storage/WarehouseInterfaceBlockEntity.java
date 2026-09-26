package dev.wareworks.content.storage;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.LocationReservationSummary;
import dev.wareworks.content.controller.StorageMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemHandlerSnapshots;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.LogThrottle;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of the warehouse interface: turns the inventory in front of it ({@code pos + FACING}) into a storage
 * location ({@code docs/warehouse-system.md} §3.1).
 * <p>
 * It owns no items and exposes no capability. It reads the attached inventory through a
 * {@link BlockCapabilityCache} (server) that NeoForge invalidates when the inventory appears, disappears or is
 * replaced, so presence needs no polling. Contents are read only on demand ({@link #snapshot()}), never per tick.
 * <p>
 * <b>Aisle membership.</b> As a {@link StorageMember} it reports load (placement, chunk load), rotation and removal to
 * the {@link WarehouseRegistry}, so the controller of an aisle containing its position re-probes it. Its address is
 * resolved through the registry while a player observes it through goggles and synced with the goggle data.
 * <p>
 * <b>Goggle summary.</b> Goggles run on the client, which cannot see foreign inventory contents, so the server keeps a
 * compact {@link AttachedInventorySummary} (by item type, ids only) and an {@link AisleAssignment} and syncs them in the
 * update tag. The summary only matters while someone reads it, so all work is driven by goggle observation
 * ({@link GoggleObservers}): content hints (capability invalidation, a neighbour-change hint from the attached block, a
 * facing change, load) only mark it dirty. While a player looks at the interface through goggles, a dirty summary is
 * re-read at most once per {@link #OBSERVED_DIRTY_REFRESH_MIN_INTERVAL_TICKS}, and a clean one once per
 * {@link #OBSERVED_REFRESH_INTERVAL_TICKS} (which catches inventories that change silently); the assignment is resolved
 * on every observation. Changes are synced only on observation, at most once per
 * {@link GoggleObservers#SUMMARY_SYNC_MIN_INTERVAL_TICKS} ({@link SyncThrottle}); a throttled sync is sent on a later
 * observation. Any {@link #snapshot()} (e.g. by the controller) also refreshes the summary. The interface therefore needs
 * no ticker.
 * <p>
 * <b>Reserved amounts (M4).</b> On the same observations, in the same registry scan as the assignment
 * ({@link WarehouseRegistry#observeStorage}), the interface gets the reservations of its location from its controller:
 * items a crane job brings and items reserved for a pick. They are synced with the goggle data as a
 * {@link LocationReservationSummary} of at most two item ids and counts per direction, so the update tag stays bounded;
 * nothing is saved.
 * <p>
 * <b>Store filter (M8, ADR-021).</b> A Create {@link FilteringBehaviour} on the aisle face ({@link StorageFilterValueBox})
 * decides which items may be <b>stored</b> here. An empty slot accepts everything (the Create convention), so warehouses
 * built before M8 behave exactly as before. All three Create filter items work through the one code path Create provides
 * ({@code FilterItemStack.of} resolves list, attribute and package filters). The filter never restricts <b>retrieval</b>:
 * items already inside can always be fetched, and changing or clearing a filter moves nothing that is already stored
 * ({@code docs/warehouse-system.md} §3.1). A change is reported to the controllers of this rack position
 * ({@link WarehouseRegistry#filterChanged}), which cache it for planning. The behaviour needs no ticker: it has no
 * {@code tick()} or {@code initialize()}, and Create drives its value box from client events. An <b>empty</b> filter is
 * left out of client packets entirely ({@link StorageFilterBehaviour}), so an unfiltered rack wall costs no update-tag
 * bytes.
 * <p>
 * <b>Storage priority (M16, ADR-028).</b> The same value box carries a second setting on a hold-to-edit board: a
 * priority 0..9 that decides which of the <b>equally suitable</b> storage locations the crane fills first. It is a
 * property of the location, like the filter, and applies only when <b>storing</b>: retrieval always takes the shortest
 * path, and raising a priority never moves what is already stored. The plate of the aisle face is too small for a second
 * value box, which is why both settings share one ({@link StorageFilterBehaviour}). A change is reported through the
 * same registry notification as a filter change, and the number travels in the update tag only while it is not 0, so an
 * unprioritised rack wall still costs nothing.
 * <p>
 * <b>Stock hints.</b> A neighbour-change hint or a block update from the attached position also tells the controllers
 * whose aisle contains this interface ({@link WarehouseRegistry#contentChanged}), which re-read the location within a
 * few ticks instead of waiting for their round robin ({@code docs/warehouse-system.md} §5, ADR-013).
 */
public class WarehouseInterfaceBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation, GoggleObservers.Observable, StorageMember {
    /** While observed: minimum game ticks between two re-reads of a dirty summary. */
    public static final int OBSERVED_DIRTY_REFRESH_MIN_INTERVAL_TICKS = GoggleObservers.SCAN_INTERVAL_TICKS;
    /** While observed: game ticks after which a clean summary is re-read (for inventories that change silently). */
    public static final int OBSERVED_REFRESH_INTERVAL_TICKS = 20;
    /** Number of item entries shown in the goggle tooltip; the bound that {@code ItemTypeSummaries} also reads with. */
    public static final int GOGGLE_TOP_ENTRIES = ItemTypeSummaries.MAX_ENTRIES;
    /** Lines of a Create filter item's own summary shown under the filter line (its mode plus its first entries). */
    public static final int MAX_FILTER_DETAIL_LINES = 4;

    private static final String SUMMARY_TAG = "GoggleSummary";
    private static final String ASSIGNMENT_TAG = "AisleAssignment";
    /** Client packet key of the "this filter has no effect" flag; written only while it is set. */
    private static final String FILTER_SHADOWED_TAG = "FilterShadowed";
    /** Client packet key of the reservation summary; written only while something is reserved. */
    public static final String RESERVATIONS_TAG = "Reservations";
    private static final long NEVER = Long.MIN_VALUE;

    /**
     * Store filter and storage priority slot. Assigned in {@link #addBehaviours}, which {@code SmartBlockEntity} calls
     * from its constructor, so this field must not have an initializer (it would reset the behaviour to {@code null}).
     */
    protected StorageFilterBehaviour storeFilterBehaviour;

    @Nullable
    private BlockCapabilityCache<IItemHandler, @Nullable Direction> attachedCache;
    @Nullable
    private Direction cacheFacing;

    private AttachedInventorySummary summary = AttachedInventorySummary.NONE;
    private AisleAssignment assignment = AisleAssignment.NONE;
    private LocationReservationSummary reservations = LocationReservationSummary.NONE;
    /** Another storage location counts this one's inventory, so this filter is never asked (§3.1.1). */
    private boolean filterShadowed;
    private boolean summaryDirty = true;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);
    private long lastSummaryRefreshTick = NEVER;
    /** Rate limit for the "could not read the inventory" line: a second, different fault must still be reportable. */
    private final LogThrottle refreshFailures = new LogThrottle();

    public WarehouseInterfaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Aisle face only: every other face already carries an interaction (see StorageFilterValueBox). One behaviour,
        // not two: the plate is 6 px tall and two value boxes need 8 px between their centres, so the storage priority
        // is a board row on this very box (M16, ADR-028, see StorageFilterBehaviour).
        storeFilterBehaviour = new StorageFilterBehaviour(this, new StorageFilterValueBox());
        storeFilterBehaviour.setLabel(WareworksLang.translateDirect(WareworksLang.INTERFACE_STORE_FILTER));
        // No withPredicate: list, attribute and package filters are exactly what this slot is for.
        storeFilterBehaviour.withCallback(stack -> onStoreSettingsChanged());
        storeFilterBehaviour.withPriorityCallback(priority -> onStoreSettingsChanged());
        behaviours.add(storeFilterBehaviour);
    }

    // --- store filter and storage priority -----------------------------------------------------------------------

    /**
     * A copy of the filter stack that decides what may be stored here; empty means "accepts everything". A copy,
     * because Create's {@code FilterItemStack.of} trims components of a filter item in place.
     */
    @Override
    public ItemStack storeFilter() {
        return storeFilterBehaviour.getFilter().copy();
    }

    /** Whether this location only accepts certain items for storing. */
    public boolean hasStoreFilter() {
        return !storeFilterBehaviour.getFilter().isEmpty();
    }

    /**
     * The storage priority of this location ({@code docs/warehouse-system.md} §3.1, ADR-028): 0..9, higher fills first.
     * It decides only <b>where new items go</b>, among the locations the store filter, consolidation and item-type
     * grouping left equal; it never affects retrieval and never re-shuffles what is already stored.
     */
    @Override
    public int storePriority() {
        return storeFilterBehaviour.priority();
    }

    /** Whether a player gave this location a storage priority at all (M16). */
    public boolean hasStorePriority() {
        return storeFilterBehaviour.priority() != StorageFilterBehaviour.MIN_PRIORITY;
    }

    /**
     * The value box the store filter and the storage priority share, for
     * {@code client.render.WarehouseInterfaceRenderer} (which draws the priority digit on the block itself, because
     * anything Create draws for a value box is part of the outliner box and only appears for the block under the
     * crosshair).
     */
    public ValueBoxTransform storeSettingsSlot() {
        return storeFilterBehaviour.getSlotPositioning();
    }

    /**
     * Whether this location's store filter <b>and</b> its storage priority have <b>no effect</b>, because another
     * storage location counts the inventory it reads (double chest, item vault) and the planner only ever asks that one
     * ({@code docs/warehouse-system.md} §3.1.1). Resolved on the server at every goggle observation and synced with the
     * rest of the goggle data, so the tooltip can say which of two interfaces on one inventory is the effective one —
     * which of them is canonical depends on the order {@code SharedInventories.assign} saw them, so this hint is the
     * player's only way to tell.
     */
    public boolean isStoreFilterShadowed() {
        return filterShadowed;
    }

    /**
     * Server: sets the store filter as a player click would (an empty stack clears it). Used by tests and scripted
     * scenes; players use the value box.
     *
     * @return whether the filter was accepted
     */
    public boolean setStoreFilter(ItemStack filter) {
        return storeFilterBehaviour.setFilter(filter);
    }

    /**
     * Server: sets the storage priority as the hold-to-edit board would (clamped to 0..9). Used by tests and scripted
     * scenes; players hold the click on the filter slot.
     *
     * @return whether it changed
     */
    public boolean setStorePriority(int priority) {
        return storeFilterBehaviour.setPriority(priority);
    }

    /**
     * The filter or the priority changed: the controllers of this rack position re-read both, so the next planning run
     * honours the change.
     */
    private void onStoreSettingsChanged() {
        if (level instanceof ServerLevel && !isRemoved())
            WarehouseRegistry.filterChanged(level, worldPosition);
    }

    // --- attached inventory --------------------------------------------------------------------------------------

    /** Direction from the interface to its attached inventory. */
    @Override
    public Direction facing() {
        return getBlockState().getOptionalValue(WarehouseInterfaceBlock.FACING).orElse(Direction.NORTH);
    }

    /** Position of the attached inventory. */
    @Override
    public BlockPos attachedPos() {
        return worldPosition.relative(facing());
    }

    /**
     * The item handler of the attached inventory, queried from the side that touches this interface.
     * <p>
     * Server: cached ({@link BlockCapabilityCache}, recreated when {@code FACING} changes); empty while the attached
     * position is not loaded. Client and Ponder: an uncached query, guarded by {@code level.isLoaded}. Call it every
     * time and never keep the handler across ticks.
     */
    @Override
    public Optional<IItemHandler> attachedHandler() {
        if (level == null || isRemoved())
            return Optional.empty();
        Direction facing = facing();
        BlockPos target = worldPosition.relative(facing);
        if (!(level instanceof ServerLevel serverLevel)) {
            if (!level.isLoaded(target))
                return Optional.empty();
            return Optional.ofNullable(level.getCapability(Capabilities.ItemHandler.BLOCK, target, facing.getOpposite()));
        }
        if (attachedCache == null || cacheFacing != facing)
            createCache(serverLevel, target, facing);
        try {
            return Optional.ofNullable(attachedCache.getCapability());
        } catch (IllegalStateException e) {
            // The cache was disabled while this block entity was marked removed (e.g. moved by a command); rebuild it.
            createCache(serverLevel, target, facing);
            return Optional.ofNullable(attachedCache.getCapability());
        }
    }

    private void createCache(ServerLevel serverLevel, BlockPos target, Direction facing) {
        cacheFacing = facing;
        // The listener may run while chunks unload: it only sets a flag (no level access, no getCapability()).
        attachedCache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, serverLevel, target,
                facing.getOpposite(), () -> !isRemoved(), () -> summaryDirty = true);
    }

    /** Whether an inventory is attached right now (live query, see {@link #attachedHandler()}). */
    public boolean hasAttachedInventory() {
        return attachedHandler().isPresent();
    }

    /**
     * Reads the attached inventory slot by slot into a snapshot. Call on demand only (planning, interaction, throttled
     * refresh), never per tick.
     * <p>
     * Returns {@link InventorySnapshot#empty()} (zero slots) when no inventory is attached or the attached position is
     * not loaded; {@link #hasAttachedInventory()} tells the two cases "empty inventory" and "no inventory" apart. On the
     * server, a loaded read also refreshes the goggle summary (clients receive it on the next goggle observation).
     */
    @Override
    public InventorySnapshot<ItemKey> snapshot() {
        if (level == null || !level.isLoaded(attachedPos()))
            return InventorySnapshot.empty();
        Optional<IItemHandler> handler = attachedHandler();
        InventorySnapshot<ItemKey> snapshot = handler.map(ItemHandlerSnapshots::capture)
                .orElseGet(InventorySnapshot::empty);
        if (!level.isClientSide)
            publishSummary(handler.isPresent(), snapshot);
        return snapshot;
    }

    // --- goggle summary ------------------------------------------------------------------------------------------

    /** The last known goggle summary: computed on the server, synced on the client. */
    public AttachedInventorySummary summary() {
        return summary;
    }

    /** The aisle assignment as of the last goggle observation (server) or sync (client). */
    public AisleAssignment aisleAssignment() {
        return assignment;
    }

    /** The reservations of this storage location as of the last goggle observation (server) or sync (client). */
    public LocationReservationSummary reservationSummary() {
        return reservations;
    }

    /** Name of the attached inventory block from the last summary; empty when no inventory is attached. */
    public Optional<Component> attachedBlockName() {
        return summary.attachedBlockName();
    }

    /**
     * Whether the goggle summary is known to be outdated (a content or presence hint arrived since the last read).
     * Server only; the summary is re-read on the next goggle observation or {@link #snapshot()}.
     */
    public boolean isSummaryDirty() {
        return summaryDirty;
    }

    /**
     * Hint from the block: the attached block or its contents changed (neighbour change or block update). Marks the
     * goggle summary dirty and asks the controllers of this rack position to re-read it (throttled on their side).
     * Server only.
     */
    void onAttachedBlockChanged() {
        summaryDirty = true;
        if (level instanceof ServerLevel && !isRemoved())
            WarehouseRegistry.contentChanged(level, worldPosition);
    }

    /**
     * A player looks at this interface through goggles (server, from {@link GoggleObservers}): re-reads the summary if
     * it is dirty or old, resolves the aisle assignment and the reservations, then sends a pending sync unless throttled.
     */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        long now = level.getGameTime();
        int refreshInterval = summaryDirty ? OBSERVED_DIRTY_REFRESH_MIN_INTERVAL_TICKS : OBSERVED_REFRESH_INTERVAL_TICKS;
        if (lastSummaryRefreshTick == NEVER || now - lastSummaryRefreshTick >= refreshInterval)
            refreshSummary();
        refreshAisleMembership();
        flushSummarySync();
    }

    /**
     * Server: the address and the reservations of this location in one warehouse registry scan (a few containment tests
     * and, when assigned, one ledger query over the aisle's reservations; none unless assigned). A change is synced.
     */
    private void refreshAisleMembership() {
        WarehouseRegistry.StorageObservation next = WarehouseRegistry.observeStorage(level, worldPosition, this);
        if (next.assignment().equals(assignment) && next.reservations().equals(reservations)
                && next.filterShadowed() == filterShadowed)
            return;
        assignment = next.assignment();
        reservations = next.reservations();
        filterShadowed = next.filterShadowed();
        summarySync.markPending();
    }

    private void refreshSummary() {
        if (level == null || !level.isLoaded(attachedPos()))
            return; // stay dirty: unloaded is not "removed", and loading the chunk invalidates the cache anyway
        // Cleared before reading, so an inventory that keeps throwing is retried only at the clean refresh rate.
        summaryDirty = false;
        lastSummaryRefreshTick = level.getGameTime();
        try {
            snapshot();
        } catch (RuntimeException e) {
            // A foreign inventory threw while being read; keep the last summary instead of crashing the server tick.
            if (refreshFailures.tryLog(lastSummaryRefreshTick))
                Wareworks.LOGGER.warn("Warehouse interface at {} could not read the inventory at {}", worldPosition,
                        attachedPos(), e);
        }
    }

    /** Stores the summary of a fresh read (which counts as a refresh) and marks a sync pending if it changed. */
    private void publishSummary(boolean hasInventory, InventorySnapshot<ItemKey> snapshot) {
        summaryDirty = false;
        lastSummaryRefreshTick = level.getGameTime();
        AttachedInventorySummary next = hasInventory
                ? AttachedInventorySummary.attached(
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(attachedPos()).getBlock()), snapshot,
                        GOGGLE_TOP_ENTRIES)
                : AttachedInventorySummary.NONE;
        if (next.equals(summary))
            return;
        summary = next;
        summarySync.markPending();
    }

    private void flushSummarySync() {
        if (level != null && !level.isClientSide && summarySync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        WareworksLang.translate(WareworksLang.GOGGLES_STORAGE_LOCATION).forGoggles(tooltip);
        assignment.addGoggleLines(tooltip, WareworksLang.GOGGLES_MISALIGNED_HINT, 1);
        // The filter stack is synced by Create's FilteringBehaviour itself, so the client can name it directly.
        ItemStack filter = storeFilterBehaviour.getFilter();
        if (filter.isEmpty()) {
            WareworksLang.translate(WareworksLang.GOGGLES_STORAGE_FILTER_NONE).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
        } else {
            WareworksLang.storageFilter(filter.getHoverName()).forGoggles(tooltip, 1);
            addFilterDetails(tooltip, filter);
        }
        // Only while it is set, like the reservation lines: an unprioritised location says nothing about priorities.
        int priority = storeFilterBehaviour.priority();
        if (priority != StorageFilterBehaviour.MIN_PRIORITY)
            WareworksLang.countLine(WareworksLang.GOGGLES_STORAGE_PRIORITY, priority).forGoggles(tooltip, 1);
        // A priority on a shared-inventory alias is as ineffective as a filter there, so the hint covers both: without
        // it a priority set on the wrong half of a double chest would be invisibly dead (§3.1.1).
        if (filterShadowed && (!filter.isEmpty() || priority != StorageFilterBehaviour.MIN_PRIORITY))
            WareworksLang.translate(WareworksLang.GOGGLES_STORAGE_FILTER_SHADOWED).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
        reservations.addGoggleLines(tooltip, 1);
        AttachedInventorySummary shown = summary;
        if (!shown.hasInventory()) {
            WareworksLang.translate(WareworksLang.GOGGLES_NO_INVENTORY).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            return true;
        }
        shown.attachedBlockName()
                .ifPresent(name -> WareworksLang.attachedInventory(name).forGoggles(tooltip, 1));
        WareworksLang.addInventorySummary(tooltip, shown.contents(), 1);
        return true;
    }

    /**
     * Names what a Create filter item selects, under the "Filter: ..." line (M8 review fix). All three filter items
     * share one generic name and one icon, so several chests dedicated with List Filters would otherwise all read
     * "Filter: List Filter" and be indistinguishable from the aisle — which is the headline use case of the feature.
     * Create's own {@code FilterItem#makeSummary} is reused, so the lines are the ones the filter item's tooltip
     * already shows (allow or deny list and its first entries, the attribute rules, a package address), translated and
     * bounded; at most {@link #MAX_FILTER_DETAIL_LINES} of them are kept so a goggle tooltip stays short. A plain item
     * in the slot needs none: its own line already names it.
     * <p>
     * An <b>empty</b> summary is a warning, not a missing detail. {@code FilterItemStack.of} only wraps a filter item
     * whose component patch is non-empty, so a freshly crafted filter matches by item like a plain stack and accepts
     * nothing but other filter items; a list filter whose entries were all removed matches nothing either. Both turn
     * the location into dead storage while the line above looks perfectly normal (§3.1.1).
     */
    private static void addFilterDetails(List<Component> tooltip, ItemStack filter) {
        if (!(filter.getItem() instanceof FilterItem filterItem))
            return;
        List<Component> summary = filterItem.makeSummary(filter);
        if (summary.isEmpty()) {
            WareworksLang.translate(WareworksLang.GOGGLES_STORAGE_FILTER_EMPTY).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 2);
            return;
        }
        for (Component line : summary.subList(0, Math.min(summary.size(), MAX_FILTER_DETAIL_LINES)))
            WareworksLang.builder().add(line.copy()).forGoggles(tooltip, 2);
    }

    // --- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        summaryDirty = true;
        if (level instanceof ServerLevel)
            onMembershipRelevantChange();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction oldFacing = facing();
        super.setBlockState(state);
        if (facing() == oldFacing)
            return;
        // Rotated (wrench, structure placement): the attached position and query side changed.
        attachedCache = null;
        cacheFacing = null;
        summaryDirty = true;
        if (level instanceof ServerLevel)
            onMembershipRelevantChange();
    }

    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel)
            onMembershipRelevantChange();
    }

    /**
     * Removal or chunk unload. Membership is not notified here: a real removal already notified from {@link #remove()},
     * and an unloaded position keeps its record at the controller ({@code docs/warehouse-system.md} §4).
     */
    @Override
    public void invalidate() {
        super.invalidate();
        // A cache of a removed owner can be permanently disabled; drop it and rebuild lazily if needed.
        attachedCache = null;
        cacheFacing = null;
    }

    /**
     * Server: the interface was loaded or placed, rotated, or removed. Marks this rack position dirty at every
     * controller whose aisle contains it ({@code docs/warehouse-system.md} §4).
     */
    protected void onMembershipRelevantChange() {
        WarehouseRegistry.memberChanged(level, worldPosition);
    }

    // --- persistence and sync ------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        // Summary, assignment and reservations are derived state: clients need them for goggles, saves do not.
        if (clientPacket) {
            CompoundTag summaryTag = new CompoundTag();
            summary.write(summaryTag);
            tag.put(SUMMARY_TAG, summaryTag);
            CompoundTag assignmentTag = new CompoundTag();
            assignment.write(assignmentTag);
            tag.put(ASSIGNMENT_TAG, assignmentTag);
            // Only while something is reserved: most interfaces have no reservation, and a missing tag reads as none.
            if (!reservations.isEmpty()) {
                CompoundTag reservationsTag = new CompoundTag();
                reservations.write(reservationsTag);
                tag.put(RESERVATIONS_TAG, reservationsTag);
            }
            // Likewise only in the rare shared-inventory case; a missing flag reads as "the filter applies".
            if (filterShadowed)
                tag.putBoolean(FILTER_SHADOWED_TAG, true);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            summary = AttachedInventorySummary.read(tag.getCompound(SUMMARY_TAG));
            assignment = AisleAssignment.read(tag.getCompound(ASSIGNMENT_TAG));
            reservations = LocationReservationSummary.read(tag.getCompound(RESERVATIONS_TAG));
            filterShadowed = tag.getBoolean(FILTER_SHADOWED_TAG);
        }
    }
}
