package dev.wareworks.content.station;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the warehouse home point ({@code docs/stacker-crane.md} §4.7, M21, issue #1, ADR-034): the rack
 * position a stacker crane with nothing to do waits at.
 * <p>
 * <b>It holds nothing and decides nothing.</b> No buffer, no item capability, no mechanical arm point, no ticker, no
 * saved data at all: the block's own position <i>is</i> its whole content, and everything a player reads off it — which
 * crane uses it, and why one does not — is decided by the controller that serves it
 * ({@code WarehouseControllerBlockEntity#refreshHomePoints}) and pushed here through {@link #applyStatus}. That is the
 * same division as the warehouse stock keeper's lamp, and for the same reason: the controller is the only place that
 * knows the whole warehouse.
 * <p>
 * <b>Lamps are written only on a real change</b>, with {@code UPDATE_CLIENTS} alone, because a lamp is something a
 * player looks at and not something a neighbour reacts to. The lamp state lives in the block state and is therefore
 * saved by the world; it is re-established within one geometry refresh after every load, and a home point whose
 * warehouse took it away has its lamps cleared by the controller that lit them ({@code writtenHomePoints}).
 */
public class WarehouseHomePointBlockEntity extends SmartBlockEntity
        implements WarehouseMember, IHaveGoggleInformation, GoggleObservers.Observable, Clearable {
    /** NBT key of the goggle summary in client packets. */
    public static final String SUMMARY_TAG = "GoggleSummary";

    /** Derived goggle state; synced to clients, never authoritative and never saved. */
    private HomePointGoggleSummary summary = HomePointGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    public WarehouseHomePointBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** No behaviours: a home point is placed and read, never operated, and like every member block it has no ticker. */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    // --- membership ----------------------------------------------------------------------------------------------

    @Override
    public LocationKind locationKind() {
        return LocationKind.HOME;
    }

    /** Direction from the home point towards the aisle, like every other member that faces it. */
    @Override
    public Direction facing() {
        return getBlockState().getOptionalValue(HorizontalDirectionalBlock.FACING).orElse(Direction.NORTH);
    }

    /** The controller of the warehouse this home point is an aligned member of (server). */
    public Optional<WarehouseControllerBlockEntity> controller() {
        if (level == null || level.isClientSide || isRemoved())
            return Optional.empty();
        return WarehouseRegistry.findController(level, worldPosition);
    }

    // --- status --------------------------------------------------------------------------------------------------

    /** What the warehouse does with this home point, as of the last refresh (server) or sync (client). */
    public HomePointStatus status() {
        return summary.status();
    }

    /** The goggle data as of the last refresh (server) or sync (client). */
    public HomePointGoggleSummary summary() {
        return summary;
    }

    /**
     * Server: the controller that serves this home point decided what it is doing. Writes the two lamps, and only when
     * one of them really changed.
     */
    public void applyStatus(HomePointStatus status) {
        Objects.requireNonNull(status, "status");
        apply(summary.withStatus(status));
    }

    /**
     * Server: nothing uses this home point any more — its warehouse lost the aisle it stands on, its controller was
     * broken, or it was turned away from the rails — so both lamps go out.
     * <p>
     * Called by the controller only on a <b>real</b> loss: a chunk unload leaves the block exactly as it is (ADR-013),
     * because a lamp that flickers off every time a player walks away says nothing true.
     */
    public void clearStatus() {
        apply(summary.withStatus(HomePointStatus.NO_WAREHOUSE));
    }

    private void apply(HomePointGoggleSummary next) {
        if (level == null || level.isClientSide || isRemoved())
            return;
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        BlockState state = getBlockState();
        BlockState wanted = state;
        if (state.hasProperty(WarehouseHomePointBlock.LIT))
            wanted = wanted.setValue(WarehouseHomePointBlock.LIT, next.status().isLit());
        if (state.hasProperty(WarehouseHomePointBlock.REFUSED))
            wanted = wanted.setValue(WarehouseHomePointBlock.REFUSED, next.status().isRefused());
        if (wanted != state)
            // UPDATE_CLIENTS and nothing else: the lamps are what a player sees, not something neighbours react to.
            level.setBlock(worldPosition, wanted, Block.UPDATE_CLIENTS);
    }

    // --- goggles -------------------------------------------------------------------------------------------------

    /** A player looks at the home point through goggles (server): rebuild the summary and sync a change (throttled). */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        AisleAssignment assignment = WarehouseRegistry.assignmentOf(level, worldPosition, this);
        HomePointStatus status = controller()
                .map(controller -> controller.homePointStatusAt(worldPosition))
                .orElse(HomePointStatus.NO_WAREHOUSE);
        HomePointGoggleSummary next = new HomePointGoggleSummary(assignment, status);
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        HomePointGoggleSummary shown = summary;
        WareworksLang.translate(WareworksLang.GOGGLES_WAREHOUSE_HOME_POINT).forGoggles(tooltip);
        shown.assignment().addGoggleLines(tooltip, WareworksLang.GOGGLES_STATION_MISALIGNED_HINT, 1);
        WareworksLang.translate(shown.status().langKey()).style(colourOf(shown.status())).forGoggles(tooltip, 1);
        return true;
    }

    /** Green while the crane really waits here, red for the two states a player has to fix, grey for the rest. */
    private static ChatFormatting colourOf(HomePointStatus status) {
        if (status.isLit())
            return ChatFormatting.GREEN;
        if (status.isRefused())
            return ChatFormatting.RED;
        return ChatFormatting.GOLD;
    }

    // --- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel)
            WarehouseRegistry.memberChanged(level, worldPosition);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction before = facing();
        super.setBlockState(state);
        if (facing() != before && level instanceof ServerLevel)
            WarehouseRegistry.memberChanged(level, worldPosition); // rotated: aligned and misaligned swap
    }

    /**
     * Real removal: the warehouse loses its home point and its crane falls back to the dock. Chunk unloads do not
     * notify — an unloaded position keeps its record ({@code docs/warehouse-system.md} §4).
     */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel)
            WarehouseRegistry.memberChanged(level, worldPosition);
    }

    /** {@link Clearable}: a home point holds no items, so there is nothing to clear. */
    @Override
    public void clearContent() {
    }

    // --- persistence and sync ------------------------------------------------------------------------------------

    /**
     * Nothing is saved: a home point's whole content is where it stands, and its lamps live in the block state. Only
     * the client packet carries the derived goggle summary.
     */
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!clientPacket)
            return;
        CompoundTag summaryTag = new CompoundTag();
        summary.write(summaryTag);
        tag.put(SUMMARY_TAG, summaryTag);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket)
            summary = HomePointGoggleSummary.read(tag.getCompound(SUMMARY_TAG));
    }
}
