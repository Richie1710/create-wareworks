package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.gui.menu.MenuBase;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.FixedSlotsItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.network.ProductionScreenPayload;
import dev.wareworks.registry.WareworksMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The menu behind a warehouse production station's screen ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * It owns the same two things the terminal's menu owns, for the same reasons:
 * <ul>
 * <li><b>Real slots</b> for the station's buffer, so a player can take delivered ingredients back out by hand
 * (insertion is refused, exactly as it is for automation), plus the player inventory;</li>
 * <li>the <b>throttled push</b> of the patterns and the station's production orders to the one player who has this
 * menu open ({@link ProductionScreenPayload}). Pattern entries are items with data components and must never travel in
 * a block entity update tag, which is part of every chunk packet.</li>
 * </ul>
 * <b>The pattern cells are not slots.</b> They are ghost items: a click sends {@code ProductionPatternPayload} and the
 * server writes the entry, so editing a pattern can never consume, duplicate or swallow an item. The client is only
 * allowed to <i>ask</i>; {@link #submitPattern} and {@link #submitCancel} resolve the request against the menu the
 * sending player really has open and the station validates the rest.
 * <p>
 * Fields set while the super constructor runs must not have initializers, for the reason spelled out in
 * {@code WarehouseTerminalMenu}: a subclass initializer runs after {@code super(...)} and would overwrite them.
 */
public class ProductionMenu extends MenuBase<WarehouseProductionBlockEntity> {
    /** How often the server pushes patterns and orders to an open screen (game ticks). */
    public static final int REFRESH_INTERVAL_TICKS = 10;
    /** Pattern edits and cancellations of one menu answered in the same tick; a player clicks at most once per tick. */
    public static final int MAX_EDITS_PER_TICK = 8;
    /** {@code entry} of a {@code ProductionPatternPayload} that clears a whole pattern slot instead of one cell. */
    public static final int CLEAR_WHOLE_PATTERN = -1;

    private static final long NEVER = Long.MIN_VALUE;

    /** Set during the super constructor (see the class comment): no initializers here. */
    private int bufferSlots;
    private int patternSlots;
    private ProductionMenuLayout layout;
    private IItemHandler bufferHandler;

    /** Server side only; likewise without initializers. */
    private long lastPushGameTime;
    private boolean pushPending;
    private long editBudgetGameTime;
    private int editsThisTick;
    @Nullable
    private ProductionScreenState lastState;

    /** Client: Registrate's menu factory, with the extra data the server wrote when the screen was opened. */
    public ProductionMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(type, id, inv, extraData);
    }

    /** Server: built by the station's menu provider. */
    public ProductionMenu(MenuType<?> type, int id, Inventory inv, WarehouseProductionBlockEntity station) {
        super(type, id, inv, station);
    }

    /** Server: the menu of {@code station} for {@code inventory}'s player. */
    public static ProductionMenu create(int id, Inventory inventory, WarehouseProductionBlockEntity station) {
        return new ProductionMenu(WareworksMenuTypes.WAREHOUSE_PRODUCTION.get(), id, inventory, station);
    }

    /**
     * Client: the block entity behind the screen, at the position the server wrote into the extra data. Named by its
     * full name for the dist reason spelled out in {@code WarehouseTerminalMenu#createOnClient}.
     */
    @OnlyIn(Dist.CLIENT)
    @Override
    protected WarehouseProductionBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
        BlockPos pos = extraData.readBlockPos();
        bufferSlots = Math.max(1, extraData.readVarInt());
        patternSlots = Math.max(1, extraData.readVarInt());
        return dev.wareworks.client.gui.ClientProductionStations.at(pos);
    }

    @Override
    protected void initAndReadInventory(WarehouseProductionBlockEntity station) {
        if (station != null) {
            if (bufferSlots <= 0)
                bufferSlots = station.bufferSlots(); // server side: the block entity is authoritative
            if (patternSlots <= 0)
                patternSlots = station.patterns().size();
        }
        bufferSlots = Math.max(1, bufferSlots);
        patternSlots = Math.max(1, Math.min(patternSlots, ProductionPatterns.MAX_SLOTS));
        // One size for both sides, whatever the local handler has (FixedSlotsItemHandler, as for the terminal).
        bufferHandler = new FixedSlotsItemHandler(
                station != null ? station.menuBuffer() : new ItemStackHandler(bufferSlots), bufferSlots);
        layout = new ProductionMenuLayout(bufferSlots, patternSlots);
        lastPushGameTime = NEVER;
        pushPending = true;
        editBudgetGameTime = NEVER;
        editsThisTick = 0;
        lastState = null;
    }

    @Override
    protected void addSlots() {
        for (int slot = 0; slot < bufferSlots; slot++)
            addSlot(new BufferSlot(bufferHandler, slot, layout.bufferSlotX(slot), layout.bufferSlotY(slot)));
        addPlayerSlots(layout.playerSlotsX(), layout.playerSlotsY());
    }

    @Override
    protected void saveData(WarehouseProductionBlockEntity station) {
    }

    /** Where everything sits in the window; the same on both sides. */
    public ProductionMenuLayout layout() {
        return layout;
    }

    /** The station this menu belongs to; {@code null} on a client whose block entity is not loaded. */
    @Nullable
    public WarehouseProductionBlockEntity station() {
        return contentHolder;
    }

    public int bufferSlots() {
        return bufferSlots;
    }

    public int patternSlots() {
        return patternSlots;
    }

    /**
     * Delivered ingredients can be taken out by hand, nothing can be put in: a shift-click in the buffer moves items
     * into the player inventory, a shift-click in the player inventory does nothing.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || index >= bufferSlots)
            return ItemStack.EMPTY;
        ItemStack remainder = slot.getItem().copy();
        ItemStack moved = remainder.copy();
        if (!moveItemStackTo(remainder, bufferSlots, slots.size(), true))
            return ItemStack.EMPTY;
        slot.setByPlayer(remainder.isEmpty() ? ItemStack.EMPTY : remainder.copy());
        slot.setChanged();
        return moved;
    }

    // --- pattern editing -----------------------------------------------------------------------------------------

    /**
     * Server: the edit of a {@code ProductionPatternPayload}, resolved against the menu {@code player} really has open.
     * Returns empty (and does nothing) when the player has no production menu open, the id does not match, or this menu
     * has already answered {@value #MAX_EDITS_PER_TICK} edits in the current tick — so neither a crafted payload nor a
     * flood of them can reach a station.
     *
     * @param entry {@value #CLEAR_WHOLE_PATTERN} clears the whole pattern slot; otherwise the entry to write
     * @param key   the item to put into the entry, or empty to clear it
     * @return whether anything changed
     */
    public static Optional<Boolean> submitPattern(@Nullable Player player, int containerId, int pattern, int entry,
            Optional<ItemKey> key, int count) {
        ProductionMenu menu = resolve(player, containerId);
        if (menu == null)
            return Optional.empty();
        return Optional.of(menu.edit(player, pattern, entry, key.orElse(null), count));
    }

    /**
     * Server: the cancellation of a {@code ProductionCancelPayload}, resolved the same way. The order is only cancelled
     * when it really runs at <b>this</b> station, so a crafted payload cannot reach into another aisle.
     *
     * @return whether an order was cancelled
     */
    public static Optional<Boolean> submitCancel(@Nullable Player player, int containerId, UUID orderId) {
        ProductionMenu menu = resolve(player, containerId);
        if (menu == null || orderId == null)
            return Optional.empty();
        return Optional.of(menu.cancel(player, orderId));
    }

    /** The menu {@code player} really has open, if it is this kind and still has budget in this tick. */
    @Nullable
    private static ProductionMenu resolve(@Nullable Player player, int containerId) {
        if (player == null || player.level() == null || player.level().isClientSide)
            return null;
        if (!(player.containerMenu instanceof ProductionMenu menu) || menu.containerId != containerId)
            return null;
        return menu.takeEditBudget() ? menu : null;
    }

    /** Server: writes one pattern entry, or clears the whole slot; every check happens in the station. */
    private boolean edit(Player player, int pattern, int entry, @Nullable ItemKey key, int count) {
        if (!mayEdit(player))
            return false;
        boolean changed = entry == CLEAR_WHOLE_PATTERN
                ? contentHolder.clearPattern(pattern)
                : contentHolder.setPatternEntry(pattern, entry, key, count);
        if (changed)
            markDirty();
        return changed;
    }

    /** Server: cancels a production order of this station. */
    private boolean cancel(Player player, UUID orderId) {
        if (!mayEdit(player))
            return false;
        Level level = contentHolder.getLevel();
        BlockPos pos = contentHolder.getBlockPos();
        Optional<WarehouseControllerBlockEntity> controller = WarehouseRegistry.findController(level, pos);
        if (controller.isEmpty())
            return false;
        boolean atThisStation = controller.get().productionOrdersAt(pos).stream()
                .anyMatch(order -> order.id().equals(orderId));
        if (!atThisStation)
            return false;
        boolean cancelled = controller.get().cancelProductionOrder(orderId).isPresent();
        if (cancelled)
            markDirty();
        return cancelled;
    }

    private boolean mayEdit(Player player) {
        return contentHolder != null && !contentHolder.isRemoved() && contentHolder.getLevel() != null
                && contentHolder.canPlayerUse(player);
    }

    /** Server: push the new state with the next tick instead of waiting for the interval. */
    public void markDirty() {
        pushPending = true;
    }

    private boolean takeEditBudget() {
        long now = gameTime();
        if (now != editBudgetGameTime) {
            editBudgetGameTime = now;
            editsThisTick = 0;
        }
        return ++editsThisTick <= MAX_EDITS_PER_TICK;
    }

    // --- sync ----------------------------------------------------------------------------------------------------

    /**
     * Pushes the patterns and orders to the player who has this menu open, at most once per game tick and at most
     * every {@value #REFRESH_INTERVAL_TICKS} ticks unless an edit marked the menu dirty. Nothing is sent while nothing
     * changed. The two early returns are the terminal's, for the same reasons.
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (layout == null || contentHolder == null || contentHolder.isRemoved())
            return;
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.containerMenu != this)
            return;
        Level level = contentHolder.getLevel();
        if (level == null)
            return;
        long now = level.getGameTime();
        if (now == lastPushGameTime)
            return;
        boolean due = pushPending || lastPushGameTime == NEVER || now < lastPushGameTime
                || now - lastPushGameTime >= REFRESH_INTERVAL_TICKS;
        if (!due)
            return;
        lastPushGameTime = now;
        pushPending = false;
        ProductionScreenState state = currentState();
        if (state.equals(lastState))
            return;
        lastState = state;
        PacketDistributor.sendToPlayer(serverPlayer, new ProductionScreenPayload(containerId, state));
    }

    /** Server: the patterns of the station and the production orders running at it. */
    private ProductionScreenState currentState() {
        ProductionPatterns patterns = contentHolder.patterns();
        List<ProductionScreenState.PatternView> views = new ArrayList<>(patterns.size());
        for (int pattern = 0; pattern < patterns.size(); pattern++) {
            int slot = pattern;
            List<ProductionScreenState.EntryView> entries = new ArrayList<>(ProductionPatterns.ENTRIES_PER_PATTERN);
            for (int entry = 0; entry < ProductionPatterns.ENTRIES_PER_PATTERN; entry++) {
                int index = entry;
                patterns.keyAt(slot, index).ifPresent(key -> entries.add(
                        new ProductionScreenState.EntryView(index, key, patterns.countAt(slot, index))));
            }
            views.add(new ProductionScreenState.PatternView(entries));
        }
        List<ProductionScreenState.OrderView> orders = new ArrayList<>();
        BlockPos pos = contentHolder.getBlockPos();
        Optional<WarehouseControllerBlockEntity> controller =
                WarehouseRegistry.findController(contentHolder.getLevel(), pos);
        if (controller.isPresent()) {
            for (ProductionOrder<ItemKey, RackPosition> order : controller.get().productionOrdersAt(pos)) {
                if (orders.size() >= ProductionScreenState.MAX_ORDERS)
                    break;
                orders.add(new ProductionScreenState.OrderView(order.id(), order.state(), order.result(),
                        order.resultAmount(), order.produced(), order.outstandingIngredients(),
                        order.deliveredIngredients()));
            }
        }
        return new ProductionScreenState(views, orders);
    }

    private long gameTime() {
        Level level = contentHolder == null ? null : contentHolder.getLevel();
        return level == null ? NEVER : level.getGameTime();
    }

    /** A buffer slot: the player may take items out, but nothing can be put in. */
    private static final class BufferSlot extends SlotItemHandler {
        private BufferSlot(IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
