package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ClipboardList;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.optimizer.OptimizerOrder;
import dev.wareworks.core.optimizer.OptimizerOrders;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.warehouse.LocationKind;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the Warehouse Optimizer (issue #46, ADR-079): the aisle member that lists the items a player wants
 * moved up to better storage locations, and asks its warehouse to do it on request.
 * <p>
 * <b>It holds no items.</b> No buffer, no item capability, no belt input, no mechanical arm point, and the crane never
 * has anything to do here — the rows are ghost items like a stock keeper's rules ({@link OptimizerRows}), and a
 * clipboard is read where the player carries it, in the screen, and never taken into the block. That is why it is a
 * {@link LocationKind#OPTIMIZER} and not a station.
 * <p>
 * <b>The controller owns the order.</b> Pressing Optimise hands the listed items to the warehouse's controller
 * ({@code WarehouseControllerBlockEntity#startOptimizerOrder}), which keeps the order, plans its trips only while a
 * crane has nothing else to do and saves it with itself, so the trips go on while this block's chunk is unloaded.
 * This block entity keeps the list and nothing else; what the order did is read back for the screen and the goggles.
 * <p>
 * <b>No ticker.</b> The list is edited in the screen ({@link #openScreen}), whose menu also pushes the breakdown of each
 * item while it is open ({@link OptimizerMenu}) — so an optimizer nobody looks at costs nothing at all.
 */
public class WarehouseOptimizerBlockEntity extends SmartBlockEntity
        implements WarehouseMember, IHaveGoggleInformation, GoggleObservers.Observable, Clearable, IInteractionChecker {
    /** NBT key of the goggle summary in client packets. */
    public static final String SUMMARY_TAG = "GoggleSummary";

    private final OptimizerRows rows = new OptimizerRows();

    /** Derived goggle state; synced to clients, never authoritative and never saved. */
    private OptimizerGoggleSummary summary = OptimizerGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    public WarehouseOptimizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** No behaviours: the optimizer is operated through its screen, and like every member block it has no ticker. */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    // --- membership ----------------------------------------------------------------------------------------------

    @Override
    public LocationKind locationKind() {
        return LocationKind.OPTIMIZER;
    }

    /** Direction from the optimizer towards the aisle, like every other member that faces it. */
    @Override
    public Direction facing() {
        return getBlockState().getOptionalValue(HorizontalDirectionalBlock.FACING).orElse(Direction.NORTH);
    }

    /** The controller of the warehouse this optimizer is an aligned member of (server). */
    public Optional<WarehouseControllerBlockEntity> controller() {
        if (level == null || level.isClientSide || isRemoved())
            return Optional.empty();
        return WarehouseRegistry.findController(level, worldPosition);
    }

    // --- the list ------------------------------------------------------------------------------------------------

    /** The optimizer's item rows (server). */
    public OptimizerRows rows() {
        return rows;
    }

    /**
     * Server: puts {@code key} into {@code row}, or empties it for {@code null}, and saves the change. The order a
     * press of Optimise started is not touched: it moves what was listed when it was pressed.
     */
    public OptimizerRows.Edit editRow(int row, @Nullable ItemKey key) {
        if (level == null || level.isClientSide || isRemoved())
            return OptimizerRows.Edit.UNCHANGED;
        OptimizerRows.Edit edit = rows.set(row, key);
        if (edit == OptimizerRows.Edit.CHANGED)
            setChanged();
        return edit;
    }

    /**
     * Server: adds the items a filled-in clipboard lists to the free rows — the orderable entries only
     * ({@link ListEntry#orderable()}: an item, not ticked off), each item once, in the clipboard's order. The clipboard
     * itself is only read: it stays exactly where it is.
     *
     * @return what was added, or empty when {@code clipboard} is no clipboard at all
     */
    public Optional<OptimizerRows.Added> importClipboard(ItemStack clipboard) {
        if (level == null || level.isClientSide || isRemoved() || !ClipboardList.isClipboard(clipboard))
            return Optional.empty();
        Set<ItemKey> keys = new LinkedHashSet<>();
        for (ListEntry<ItemKey> entry : ClipboardList.read(clipboard)) {
            if (entry.orderable())
                entry.key().ifPresent(keys::add);
        }
        OptimizerRows.Added added = rows.addAll(new ArrayList<>(keys));
        if (added.added() > 0)
            setChanged();
        return Optional.of(added);
    }

    /**
     * Server: asks the warehouse to move {@code keys} up to better storage locations — a fresh order that replaces
     * whatever this optimizer had running. Empty when no controller serves this optimizer.
     */
    public Optional<OptimizerOrders.Start> optimise(List<ItemKey> keys) {
        Objects.requireNonNull(keys, "keys");
        return controller().flatMap(controller -> controller.startOptimizerOrder(worldPosition, keys));
    }

    /** Server: stops this optimizer's running order; a trip under way is finished. */
    public boolean cancel() {
        return controller().map(controller -> controller.cancelOptimizerOrder(worldPosition)).orElse(false);
    }

    /** Server: this optimizer's order as its warehouse keeps it, running or finished. */
    public Optional<OptimizerOrder<ItemKey, RackPosition>> order(@Nullable WarehouseControllerBlockEntity controller) {
        if (controller == null || controller.isRemoved())
            return Optional.empty();
        return controller.optimizerOrder(worldPosition);
    }

    // --- screen ------------------------------------------------------------------------------------------------

    /**
     * Server: the rows as the screen shows them — each item with its breakdown by priority, measured against
     * {@code rooms} ({@code WarehouseControllerBlockEntity#optimizerView}), the order's state and the last message.
     * Built by the menu for the one player who has the screen open, so the items travel in a menu payload and never in
     * a chunk packet.
     */
    public OptimizerScreenState screenState(@Nullable WarehouseControllerBlockEntity controller,
            Map<ItemKey, List<PriorityBreakdown.Room>> rooms, OptimizerScreenState.Message message, int first,
            int second) {
        boolean linked = controller != null && !controller.isRemoved();
        List<ItemKey> keys = rows.keys();
        List<PriorityBreakdown> views = linked ? controller.optimizerView(keys, rooms) : List.of();
        Optional<OptimizerOrder<ItemKey, RackPosition>> order = order(controller);
        List<OptimizerScreenState.RowView> shown = new ArrayList<>(rows.size());
        int index = 0;
        for (int row = 0; row < rows.size(); row++) {
            Optional<ItemKey> key = rows.keyAt(row);
            if (key.isEmpty()) {
                shown.add(OptimizerScreenState.RowView.empty(row));
                continue;
            }
            int viewIndex = index++;
            PriorityBreakdown view = viewIndex < views.size() ? views.get(viewIndex) : PriorityBreakdown.NONE;
            shown.add(new OptimizerScreenState.RowView(row, key, view,
                    OptimizerScreenState.Pip.of(order, key.get())));
        }
        return new OptimizerScreenState(shown, linked, OptimizerScreenState.OrderLine.of(order), message, first,
                second);
    }

    /**
     * Opens the optimizer's screen for {@code player} (server). The extra data carries the block position, as the
     * keeper's does, so the client finds the block entity behind it.
     *
     * @return whether a screen was opened
     */
    public boolean openScreen(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved())
            return false;
        return player.openMenu(new SimpleMenuProvider(
                (id, inventory, owner) -> OptimizerMenu.create(id, inventory, this),
                getBlockState().getBlock().getName()), extra -> extra.writeBlockPos(worldPosition)).isPresent();
    }

    /**
     * Whether {@code player} may use this optimizer, by the same rule vanilla containers use
     * ({@code Container#stillValidBlockEntity}). Create's {@code MenuBase} asks it every tick, so a broken, replaced or
     * unloaded optimizer closes its screen by itself. Never throws.
     */
    @Override
    public boolean canPlayerUse(Player player) {
        Objects.requireNonNull(player, "player");
        return !isRemoved() && Container.stillValidBlockEntity(this, player);
    }

    // --- goggles -----------------------------------------------------------------------------------------------

    /** The goggle data as of the last observation (server) or sync (client). */
    public OptimizerGoggleSummary summary() {
        return summary;
    }

    /** A player looks at the optimizer through goggles (server): rebuild the summary and sync a change (throttled). */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        WarehouseControllerBlockEntity controller = controller().orElse(null);
        Optional<OptimizerOrder<ItemKey, RackPosition>> order = order(controller);
        OptimizerGoggleSummary next = new OptimizerGoggleSummary(
                WarehouseRegistry.assignmentOf(level, worldPosition, this), rows.count(), controller != null,
                order.map(OptimizerOrder::isRunning).orElse(false), order.map(OptimizerOrder::moved).orElse(0L),
                order.map(OptimizerOrder::tripsLeft).orElse(0));
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        OptimizerGoggleSummary shown = summary;
        WareworksLang.translate(WareworksLang.GOGGLES_WAREHOUSE_OPTIMIZER).forGoggles(tooltip);
        shown.assignment().addGoggleLines(tooltip, WareworksLang.GOGGLES_STATION_MISALIGNED_HINT, 1);
        if (shown.items() == 0)
            WareworksLang.translate(WareworksLang.GOGGLES_OPTIMIZER_NO_ITEMS).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        else
            WareworksLang.countLine(WareworksLang.GOGGLES_OPTIMIZER_ITEMS, shown.items()).forGoggles(tooltip, 1);
        if (!shown.linked()) {
            WareworksLang.translate(WareworksLang.GOGGLES_OPTIMIZER_NO_WAREHOUSE).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            return true;
        }
        if (shown.running())
            WareworksLang.translate(WareworksLang.GOGGLES_OPTIMIZER_RUNNING,
                    WareworksLang.number(shown.moved()).style(ChatFormatting.GOLD),
                    WareworksLang.number(shown.tripsLeft()).style(ChatFormatting.GOLD))
                    .style(ChatFormatting.AQUA).forGoggles(tooltip, 1);
        return true;
    }

    // --- lifecycle ---------------------------------------------------------------------------------------------

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
     * Real removal. Chunk unloads do not notify: an unloaded position keeps its record, and the controller keeps this
     * optimizer's order until the optimizer really leaves the warehouse, which is when the order is forgotten
     * ({@code WarehouseControllerBlockEntity#processMembership}).
     */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel)
            WarehouseRegistry.memberChanged(level, worldPosition);
    }

    /**
     * {@link Clearable}: commands and structure placement replace the block. An optimizer holds <b>no items</b>, so
     * there is nothing to clear — and its list is configuration, not contents.
     */
    @Override
    public void clearContent() {
    }

    // --- persistence and sync ------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            // Only the bounded numbers: a listed item never goes into a chunk packet (see OptimizerGoggleSummary).
            CompoundTag summaryTag = new CompoundTag();
            summary.write(summaryTag);
            tag.put(SUMMARY_TAG, summaryTag);
            return;
        }
        tag.put(OptimizerRows.ROWS_TAG, rows.save(registries));
    }

    /**
     * What a <b>schematic</b> carries along ({@code PartialSafeNBT}): the list a player wrote, and nothing else — the
     * stock keeper's rule ({@code WarehouseStockKeeperBlockEntity#writeSafe}). An optimizer without a single item writes
     * nothing, so an unconfigured one adds nothing to a schematic, and a print without a list over a standing optimizer
     * leaves that optimizer's list alone ({@link #read}). The order is the controller's and never travels.
     */
    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        if (!rows.isEmpty())
            tag.put(OptimizerRows.ROWS_TAG, rows.save(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            summary = OptimizerGoggleSummary.read(tag.getCompound(SUMMARY_TAG));
            return;
        }
        // Every save writes the list, even an empty one. A tag without it is a schematic print without a list landing
        // on an optimizer that is already standing, and must not wipe the list a player wrote (the keeper's rule).
        if (tag.contains(OptimizerRows.ROWS_TAG, Tag.TAG_LIST))
            rows.load(tag.getList(OptimizerRows.ROWS_TAG, Tag.TAG_COMPOUND), registries);
    }
}
