package dev.wareworks.content.station;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Common block entity of the warehouse stations ({@code docs/warehouse-system.md} §3.2): an item buffer, aisle
 * membership, goggle data, persistence and drops.
 * <p>
 * <b>Buffer.</b> A {@link StationBuffer} with the configured number of slots, read once at construction through the safe
 * config getter; a save with more used slots keeps them, so a lowered config never deletes items. Automation only sees
 * {@link #externalHandler()} (insert-only for inputs, extract-only for outputs), a stable view registered as the item
 * capability; the crane uses the subclass API.
 * <p>
 * <b>Membership.</b> A {@link WarehouseMember} of kind input or output: {@code FACING} must point towards the aisle. The
 * station notifies the {@link WarehouseRegistry} on load (placement, chunk load), after a facing change and on removal,
 * exactly like the warehouse interface ({@code docs/warehouse-system.md} §4).
 * <p>
 * <b>Goggles.</b> Driven by {@link GoggleObservers}: while a player looks at the station through goggles, the server
 * builds a bounded {@link StationGoggleSummary} (aisle assignment, buffer by item type; request data for outputs) and
 * syncs it only when it changed, at most once per {@link GoggleObservers#SUMMARY_SYNC_MIN_INTERVAL_TICKS}
 * ({@link SyncThrottle}). Nothing is read while
 * nobody looks, and buffer contents never go into client packets (no item components in chunk data).
 * <p>
 * <b>Item conservation.</b> {@link #destroy()} (real break, server) drops the buffer with {@code Containers.dropItemStack};
 * {@link #clearContent()} empties it for commands and structure placement that replace the block ({@link Clearable}), so
 * {@code /clone ... move} cannot duplicate buffered items. Saving never throws.
 * <p>
 * No ticker: all work happens on events.
 */
public abstract class WarehouseStationBlockEntity extends SmartBlockEntity
        implements WarehouseMember, IHaveGoggleInformation, GoggleObservers.Observable, Clearable {
    /** Number of item types listed in the goggle tooltip; the bound that {@link ItemTypeSummaries} also reads with. */
    public static final int GOGGLE_TOP_ENTRIES = ItemTypeSummaries.MAX_ENTRIES;
    /** NBT key of the saved buffer. */
    public static final String BUFFER_TAG = "Buffer";
    /** NBT key of the goggle summary in client packets. */
    public static final String SUMMARY_TAG = "GoggleSummary";

    private static final int BUFFER_GOGGLE_INDENT = 2;

    protected final StationBuffer buffer;
    private final int configuredSlots;

    private StationGoggleSummary summary = StationGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    /**
     * @param configuredSlots buffer slots from the config (read by the caller through the safe getter)
     */
    protected WarehouseStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int configuredSlots) {
        super(type, pos, state);
        this.configuredSlots = Math.max(StationBuffer.MIN_SLOTS, configuredSlots);
        this.buffer = new StationBuffer(this.configuredSlots, this::onBufferChanged);
    }

    // --- station -------------------------------------------------------------------------------------------------

    /** Direction from the station towards the aisle. */
    @Override
    public Direction facing() {
        return getBlockState().getOptionalValue(HorizontalDirectionalBlock.FACING).orElse(Direction.NORTH);
    }

    /** The view registered as the item capability: a stable instance for every side. */
    public abstract IItemHandler externalHandler();

    /** Snapshot of the buffer (reads every slot once; call on demand only). */
    public InventorySnapshot<ItemKey> bufferedItems() {
        return buffer.snapshot();
    }

    public boolean hasBufferedItems() {
        return !buffer.isEmpty();
    }

    /** Current number of buffer slots (the configured count, or more if a save used more). */
    public int bufferSlots() {
        return buffer.getSlots();
    }

    /** The buffer slot count from the config at construction. */
    public int configuredBufferSlots() {
        return configuredSlots;
    }

    private void onBufferChanged() {
        setChanged(); // null-safe; also lets comparators and neighbours notice the change
    }

    // --- goggles -------------------------------------------------------------------------------------------------

    /** The goggle summary as of the last observation (server) or sync (client). */
    public StationGoggleSummary summary() {
        return summary;
    }

    /** A player looks at the station through goggles (server): rebuild the summary and sync a change (throttled). */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        StationGoggleSummary next = createSummary();
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    /**
     * Marks the client packet as pending, so the next observation syncs it (subject to the same throttle). A subclass
     * with goggle data of its own calls this when <b>its</b> data changed, because the base only compares the shared
     * {@link StationGoggleSummary} ({@code WarehouseProductionBlockEntity#onGoggleObserved}).
     */
    protected void markSummaryDirty() {
        summarySync.markPending();
    }

    /** Server: the goggle summary from the current state. Subclasses add their own data. */
    protected StationGoggleSummary createSummary() {
        return new StationGoggleSummary(WarehouseRegistry.assignmentOf(level, worldPosition, this),
                ItemTypeSummaries.of(buffer.snapshot(), GOGGLE_TOP_ENTRIES), 0, 0L, 0L, Optional.empty());
    }

    /** Relative lang key of the goggle header, e.g. "Warehouse Input:". */
    protected abstract String goggleHeaderKey();

    /**
     * Relative lang key of the hint shown below "Misaligned": which side of this station has to face the aisle. Input
     * and output say "opening", the terminal "screen".
     */
    protected String misalignedHintKey() {
        return WareworksLang.GOGGLES_STATION_MISALIGNED_HINT;
    }

    /** Client: lines between the aisle assignment and the buffer listing. */
    protected void addStationGoggleLines(List<Component> tooltip, StationGoggleSummary shown) {
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        StationGoggleSummary shown = summary;
        WareworksLang.translate(goggleHeaderKey()).forGoggles(tooltip);
        shown.assignment().addGoggleLines(tooltip, misalignedHintKey(), 1);
        addStationGoggleLines(tooltip, shown);
        WareworksLang.translate(WareworksLang.GOGGLES_BUFFER).style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
        WareworksLang.addInventorySummary(tooltip, shown.buffer(), BUFFER_GOGGLE_INDENT);
        return true;
    }

    // --- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel)
            notifyMembership();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction before = facing();
        super.setBlockState(state);
        if (facing() != before && level instanceof ServerLevel)
            notifyMembership(); // rotated: aligned and misaligned swap
    }

    /** Real removal. Chunk unloads do not notify: an unloaded position keeps its record ({@code warehouse-system.md} §4). */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel)
            notifyMembership();
    }

    /** Block broken or replaced (server, via {@code IBE.onRemove}): drop the buffer, never delete it. */
    @Override
    public void destroy() {
        super.destroy();
        if (level != null && !level.isClientSide)
            buffer.dropAll(level, worldPosition);
    }

    /** {@link Clearable}: commands and structure placement replace the block; the buffer is emptied, not dropped. */
    @Override
    public void clearContent() {
        buffer.clear();
    }

    /** Server: controllers whose aisle contains this position re-probe it on their next tick. */
    protected void notifyMembership() {
        WarehouseRegistry.memberChanged(level, worldPosition);
    }

    // --- persistence and sync ------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            // Clients only need the bounded goggle summary; the buffer (with item components) stays on the server.
            CompoundTag summaryTag = new CompoundTag();
            summary.write(summaryTag);
            tag.put(SUMMARY_TAG, summaryTag);
            return;
        }
        tag.put(BUFFER_TAG, buffer.save(registries));
        writeStationData(tag, registries);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            summary = StationGoggleSummary.read(tag.getCompound(SUMMARY_TAG));
            return;
        }
        if (tag.contains(BUFFER_TAG, Tag.TAG_COMPOUND)) {
            int slotsBefore = buffer.getSlots();
            buffer.load(tag.getCompound(BUFFER_TAG), registries, configuredSlots);
            // The capability object stays the same, but its slot count changed: let cached consumers re-query.
            if (level != null && buffer.getSlots() != slotsBefore)
                invalidateCapabilities();
        }
        readStationData(tag, registries);
    }

    /** Saves subclass state (not written into client packets). Must not throw. */
    protected void writeStationData(CompoundTag tag, HolderLookup.Provider registries) {
    }

    /** Loads subclass state written by {@link #writeStationData}. Must not throw. */
    protected void readStationData(CompoundTag tag, HolderLookup.Provider registries) {
    }
}
