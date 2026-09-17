package dev.wareworks.content.station;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ExtractOnlyItemHandler;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Common block entity of the aisle members retrieved items are delivered to ({@code docs/warehouse-system.md} §3.2,
 * §3.4): the warehouse output and the warehouse terminal.
 * <p>
 * Both are {@link LocationKind#OUTPUT} members, so everything downstream of a request — the controller's request queue,
 * the planner, the reservation ledger, reroutes and the crane's transfer contexts — treats them alike and cannot tell
 * where a request came from (ADR-018). What differs is only how a request is <b>entered</b>: the output has a filter
 * slot plus a redstone rising edge ({@link WarehouseOutputBlockEntity}), the terminal a screen
 * ({@link WarehouseTerminalBlockEntity}).
 * <p>
 * <b>The kind is a subclass decision</b> (M11, ADR-024). The warehouse production station is a third station the crane
 * delivers into and reuses everything physical here, but reports {@link LocationKind#PRODUCTION}: it is no request
 * destination, so retrieve leftovers must never be rerouted into it
 * ({@link WarehouseProductionBlockEntity#locationKind()}).
 * <p>
 * Shared here: the extract-only automation capability over the station buffer, the crane's {@link #insert} API, the last
 * rejection (saved, shown in goggles) and the request lines of the goggle tooltip.
 */
public abstract class WarehouseDeliveryStationBlockEntity extends WarehouseStationBlockEntity {
    /** NBT key of the last rejection reason. */
    public static final String LAST_REJECTION_TAG = "LastRejection";

    private final IItemHandler externalHandler;
    @Nullable
    private RequestRejection lastRejection;

    /**
     * @param configuredSlots buffer slots from the config (read by the caller through the safe getter)
     */
    protected WarehouseDeliveryStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
            int configuredSlots) {
        super(type, pos, state, configuredSlots);
        externalHandler = new ExtractOnlyItemHandler(buffer);
    }

    // --- crane API -----------------------------------------------------------------------------------------------

    /**
     * Crane: drops {@code stack} into the buffer, filling matching stacks first. The caller's stack is not modified.
     *
     * @return what did not fit (a new stack, or empty)
     */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        return buffer.insert(stack, simulate);
    }

    // --- requests ------------------------------------------------------------------------------------------------

    /** Why the last request of this station was refused; empty if it was accepted or none was made. */
    public Optional<RequestRejection> lastRejection() {
        return Optional.ofNullable(lastRejection);
    }

    /**
     * Stores the rejection of {@code result} (or clears it after an accepted request) and returns {@code result}
     * unchanged, so callers can {@code return rememberRejection(...)}.
     */
    protected RequestResult rememberRejection(RequestResult result) {
        RequestRejection rejection = result.rejection().orElse(null);
        if (rejection != lastRejection) {
            lastRejection = rejection;
            setChanged();
        }
        return result;
    }

    /** The controller of the aisle this station is an aligned member of (server). */
    protected Optional<WarehouseControllerBlockEntity> controller() {
        if (level == null || level.isClientSide || isRemoved())
            return Optional.empty();
        return WarehouseRegistry.findController(level, worldPosition);
    }

    // --- station -------------------------------------------------------------------------------------------------

    @Override
    public LocationKind locationKind() {
        return LocationKind.OUTPUT;
    }

    @Override
    public IItemHandler externalHandler() {
        return externalHandler;
    }

    @Override
    protected StationGoggleSummary createSummary() {
        StationGoggleSummary base = super.createSummary();
        Optional<WarehouseControllerBlockEntity> controller = controller();
        int open = controller.map(c -> c.requestsFor(worldPosition).size()).orElse(0);
        long requested = controller.map(c -> c.requestedFor(worldPosition)).orElse(0L);
        long delivered = controller.map(c -> c.deliveredFor(worldPosition)).orElse(0L);
        return base.withRequests(open, requested, delivered, lastRejection());
    }

    @Override
    protected void addStationGoggleLines(List<Component> tooltip, StationGoggleSummary shown) {
        if (shown.openRequests() > 0) {
            WareworksLang.pendingRequests(shown.requestedItems(), shown.openRequests()).forGoggles(tooltip, 1);
            WareworksLang.countLine(WareworksLang.GOGGLES_DELIVERED_ITEMS, shown.deliveredItems()).forGoggles(tooltip, 2);
        } else
            WareworksLang.translate(WareworksLang.GOGGLES_NO_PENDING_REQUEST).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, 1);
        shown.lastRejection().ifPresent(rejection -> WareworksLang.lastRejection(rejection.langKey())
                .forGoggles(tooltip, 1));
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    @Override
    protected void writeStationData(CompoundTag tag, HolderLookup.Provider registries) {
        if (lastRejection != null)
            tag.putString(LAST_REJECTION_TAG, lastRejection.name());
    }

    @Override
    protected void readStationData(CompoundTag tag, HolderLookup.Provider registries) {
        lastRejection = tag.contains(LAST_REJECTION_TAG, Tag.TAG_STRING)
                ? RequestRejection.byName(tag.getString(LAST_REJECTION_TAG)).orElse(null) : null;
    }
}
