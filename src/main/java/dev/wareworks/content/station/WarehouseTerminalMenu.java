package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.gui.menu.MenuBase;

import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.FixedSlotsItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.core.terminal.StockDiff;
import dev.wareworks.network.TerminalOrdersPayload;
import dev.wareworks.network.TerminalStatusPayload;
import dev.wareworks.network.TerminalStockPayload;
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
 * The menu behind a warehouse terminal's screen ({@code docs/warehouse-system.md} §3.4.2, ADR-019).
 * <p>
 * It owns two things:
 * <ul>
 * <li><b>Real slots</b> for the terminal's own buffer, so a player can take delivered items out by hand (insertion is
 * refused, exactly as for automation), plus the player inventory. Their positions come from the shared
 * {@link TerminalMenuLayout}, because {@code Slot#x}/{@code y} are final and both sides must agree.</li>
 * <li>The <b>throttled push</b> of the item list and the aisle status to the one player who has this menu open. Every
 * {@value #REFRESH_INTERVAL_TICKS} <b>game ticks</b> the menu builds the terminal's bounded stock snapshot, sends what
 * changed since the last push ({@link StockDiff}) and the status if it changed. Nothing is sent while nothing changes,
 * and nothing is ever sent to a player without this menu.</li>
 * </ul>
 * <b>The throttle counts ticks, not calls.</b> Vanilla calls {@link #broadcastChanges()} once per player tick, but also
 * once per container click packet and once from the super constructor, so counting calls would let a client choose how
 * often the server builds a snapshot. Every push is therefore stamped with {@code Level#getGameTime()}, which bounds it
 * to one push per tick even when an accepted request asks for an early one.
 * <p>
 * <b>Requests</b> arrive as {@code TerminalRequestPayload} and are resolved through {@link #submitRequest}: the menu
 * the sending player really has open decides which terminal is asked, that terminal validates player, amount, aisle
 * and item against the server's own state, and at most {@value #MAX_REQUESTS_PER_TICK} of them are answered per tick.
 * The client is never trusted.
 * <p>
 * <b>Slot count.</b> The number of buffer slots is the one the server announced in the menu's extra data, on both
 * sides. The handler behind them is wrapped in a {@link FixedSlotsItemHandler} of exactly that size, because a
 * terminal's own buffer may have another one (grown past the config by save data, or shrunk by {@code /data merge}
 * while a screen is open) and a client menu with fewer slots than the server's throws while it reads the first content
 * packet.
 * <p>
 * Fields set while the super constructor runs ({@link #createOnClient}, {@link #initAndReadInventory}, {@link #addSlots})
 * must not have initializers: a subclass initializer runs <b>after</b> {@code super(...)} and would overwrite them.
 * That is why the sync fields below are assigned in {@link #initAndReadInventory}, which {@code MenuBase#init} calls
 * before it calls {@link #broadcastChanges()}.
 */
public class WarehouseTerminalMenu extends MenuBase<WarehouseTerminalBlockEntity> {
    /** How often the server pushes changes to an open screen (game ticks). */
    public static final int REFRESH_INTERVAL_TICKS = 10;
    /** Requests of one menu that are answered in the same tick; a player can click at most once per tick. */
    public static final int MAX_REQUESTS_PER_TICK = 8;

    /** Game time of {@link #lastPushGameTime} and {@link #requestBudgetGameTime} before the first one. */
    private static final long NEVER = Long.MIN_VALUE;

    /** Set during the super constructor (see the class comment): no initializers here. */
    private int bufferSlots;
    private TerminalMenuLayout layout;
    private IItemHandler bufferHandler;

    /** Server side only; also set during the super constructor, so likewise without initializers. */
    private StockDiff<ItemKey> stockDiff;
    private boolean fullSyncPending;
    private long lastPushGameTime;
    private boolean pushPending;
    private long requestBudgetGameTime;
    private int requestsThisTick;
    @Nullable
    private TerminalScreenStatus lastStatus;
    @Nullable
    private List<ProductionScreenState.OrderView> lastOrders;

    /** Client: Registrate's menu factory, with the extra data the server wrote when the screen was opened. */
    public WarehouseTerminalMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(type, id, inv, extraData);
    }

    /** Server: built by the terminal's menu provider. */
    public WarehouseTerminalMenu(MenuType<?> type, int id, Inventory inv, WarehouseTerminalBlockEntity terminal) {
        super(type, id, inv, terminal);
    }

    /** Server: the menu of {@code terminal} for {@code inventory}'s player. */
    public static WarehouseTerminalMenu create(int id, Inventory inventory, WarehouseTerminalBlockEntity terminal) {
        return new WarehouseTerminalMenu(WareworksMenuTypes.WAREHOUSE_TERMINAL.get(), id, inventory, terminal);
    }

    /**
     * Client: the block entity behind the screen, at the position the server wrote into the extra data.
     * <p>
     * {@code @OnlyIn(Dist.CLIENT)} mirrors the annotation on {@code MenuBase#createOnClient},
     * and the lookup itself lives in {@code client.gui} and is named
     * by its full name, so this common-package class never mentions a class a dedicated server does not have.
     */
    @OnlyIn(Dist.CLIENT)
    @Override
    protected WarehouseTerminalBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
        BlockPos pos = extraData.readBlockPos();
        // The slot count comes from the server, so both sides build the same slots even if the block entity is missing
        // or its own buffer has a different size.
        bufferSlots = Math.max(1, extraData.readVarInt());
        return dev.wareworks.client.gui.ClientTerminals.at(pos);
    }

    @Override
    protected void initAndReadInventory(WarehouseTerminalBlockEntity terminal) {
        if (bufferSlots <= 0 && terminal != null)
            bufferSlots = terminal.bufferSlots(); // server side: the block entity is authoritative
        bufferSlots = Math.max(1, bufferSlots);
        // One size for both sides, whatever the local handler has (see the class comment).
        bufferHandler = new FixedSlotsItemHandler(
                terminal != null ? terminal.menuBuffer() : new ItemStackHandler(bufferSlots), bufferSlots);
        layout = new TerminalMenuLayout(bufferSlots);
        stockDiff = new StockDiff<>();
        fullSyncPending = true;
        lastPushGameTime = NEVER;
        pushPending = true;
        requestBudgetGameTime = NEVER;
        requestsThisTick = 0;
    }

    @Override
    protected void addSlots() {
        // Exactly the announced number of buffer slots, so bufferSlots is also the index of the first player slot.
        for (int slot = 0; slot < bufferSlots; slot++)
            addSlot(new TerminalBufferSlot(bufferHandler, slot, layout.bufferSlotX(slot), layout.bufferSlotY(slot)));
        addPlayerSlots(layout.playerSlotsX(), layout.playerSlotsY());
    }

    @Override
    protected void saveData(WarehouseTerminalBlockEntity terminal) {
    }

    /** Where everything sits in the window; the same on both sides. */
    public TerminalMenuLayout layout() {
        return layout;
    }

    /** The terminal this menu belongs to; {@code null} on a client whose block entity is not loaded. */
    @Nullable
    public WarehouseTerminalBlockEntity terminal() {
        return contentHolder;
    }

    /** Number of buffer slots, i.e. the number of menu slots before the player inventory. */
    public int bufferSlots() {
        return bufferSlots;
    }

    // --- shift-clicking --------------------------------------------------------------------------------------------

    /**
     * Delivered items can be taken out of the terminal, nothing can be put in: a shift-click in the buffer moves items
     * into the player inventory, a shift-click in the player inventory does nothing.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        // addSlots() adds exactly bufferSlots buffer slots, so this is the boundary of the player inventory.
        if (!slot.hasItem() || index >= bufferSlots)
            return ItemStack.EMPTY;
        // The stack of a SlotItemHandler must not be modified in place, so everything works on copies.
        ItemStack remainder = slot.getItem().copy();
        ItemStack moved = remainder.copy();
        if (!moveItemStackTo(remainder, bufferSlots, slots.size(), true))
            return ItemStack.EMPTY;
        slot.setByPlayer(remainder.isEmpty() ? ItemStack.EMPTY : remainder.copy());
        slot.setChanged();
        return moved;
    }

    // --- requests --------------------------------------------------------------------------------------------------

    /**
     * Server: the request of a {@code TerminalRequestPayload}, resolved against the menu {@code player} really has
     * open. Returns empty (and does nothing) when the player has no terminal menu open, the id does not match, or this
     * menu has already answered {@value #MAX_REQUESTS_PER_TICK} requests in the current tick — so neither a crafted
     * payload nor a flood of them can reach a terminal.
     */
    public static Optional<RequestResult> submitRequest(@Nullable Player player, int containerId, ItemKey key,
            int amount) {
        if (player == null || player.level() == null || player.level().isClientSide || key == null)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != containerId)
            return Optional.empty();
        if (!menu.takeRequestBudget())
            return Optional.empty();
        return Optional.of(menu.request(player, key, amount));
    }

    /** Server: asks this menu's terminal for items; every check happens there. */
    public RequestResult request(Player requester, ItemKey key, int amount) {
        if (contentHolder == null || contentHolder.isRemoved())
            return RequestResult.rejected(RequestRejection.NO_CONTROLLER);
        RequestResult result = contentHolder.requestFromTerminal(requester, key, amount);
        if (result.isAccepted())
            markDirty(); // the availability changed: push it with the next tick instead of waiting for the interval
        return result;
    }

    /**
     * Server: the cancellation of a {@code ProductionCancelPayload} sent from a <b>terminal</b> screen, resolved
     * against the menu {@code player} really has open ({@code docs/warehouse-system.md} §3.4.2, ADR-024).
     * <p>
     * Empty (and nothing done) when the player has no terminal menu open, the id does not match, or this menu has
     * already answered {@value #MAX_REQUESTS_PER_TICK} actions in the current tick — a cancellation is a click like a
     * request and shares that budget, so a crafted flood cannot make the server work more often than a clicking player
     * could. The order itself is validated by the terminal
     * ({@code WarehouseTerminalBlockEntity#cancelProductionOrder}: reach, aisle, and an open order of <b>that</b>
     * aisle).
     *
     * @return whether an order was cancelled
     */
    public static Optional<Boolean> submitCancel(@Nullable Player player, int containerId, UUID orderId) {
        if (player == null || player.level() == null || player.level().isClientSide || orderId == null)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != containerId)
            return Optional.empty();
        if (!menu.takeRequestBudget())
            return Optional.empty();
        return Optional.of(menu.cancel(player, orderId));
    }

    /** Server: gives up on a production order of this terminal's aisle; every check happens in the terminal. */
    public boolean cancel(Player requester, UUID orderId) {
        if (contentHolder == null || contentHolder.isRemoved())
            return false;
        boolean cancelled = contentHolder.cancelProductionOrder(requester, orderId);
        if (cancelled)
            markDirty(); // the promised ingredients are free again: push the new availability with the next tick
        return cancelled;
    }

    /**
     * Server: send the changed stock and status with the next tick instead of at the end of the interval. Still at most
     * one push per tick ({@link #broadcastChanges()}).
     */
    public void markDirty() {
        pushPending = true;
    }

    /**
     * Server: whether this menu may answer another request in the current tick, counting it. A player can click at most
     * once per tick, so only a crafted flood ever reaches the limit.
     */
    private boolean takeRequestBudget() {
        long now = gameTime();
        if (now != requestBudgetGameTime) {
            requestBudgetGameTime = now;
            requestsThisTick = 0;
        }
        return ++requestsThisTick <= MAX_REQUESTS_PER_TICK;
    }

    // --- sync ------------------------------------------------------------------------------------------------------

    /**
     * Pushes what changed to the player who has this menu open, at most once per game tick and at most every
     * {@value #REFRESH_INTERVAL_TICKS} ticks unless an accepted request marked the menu dirty.
     * <p>
     * The two early returns are deliberate: during {@code MenuBase#init} (inside the super constructor) the player does
     * not have this menu open yet, and a push then would reach the client <b>before</b> its screen exists and be
     * dropped; and a call without a server player (or before the sync fields are set) must do nothing at all.
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (stockDiff == null || contentHolder == null || contentHolder.isRemoved())
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
        pushUpdates(serverPlayer);
    }

    private void pushUpdates(ServerPlayer target) {
        boolean reset = fullSyncPending;
        if (reset) {
            fullSyncPending = false;
            stockDiff.reset();
        }
        List<StockCount<ItemKey>> current = stockCounts();
        // An item type that only fell outside the reported window (maxTerminalStockEntries) has not left the index:
        // reporting it as gone would delete a stocked item from the screen, which could then not be requested.
        // The producible key set is resolved once for the whole push and closed over, instead of rebuilding every
        // production station's pattern list again for each item type that dropped out of the window.
        Set<ItemKey> producible = contentHolder.producibleKeys();
        List<StockCount<ItemKey>> changes = stockDiff.commit(current,
                key -> contentHolder.holdsInStock(key, producible));
        if (reset || !changes.isEmpty())
            sendStock(target, reset, changes);

        TerminalScreenStatus status = TerminalScreenStatus.of(contentHolder.status());
        if (reset || !status.equals(lastStatus)) {
            lastStatus = status;
            PacketDistributor.sendToPlayer(target, new TerminalStatusPayload(containerId, status));
        }

        // The aisle's production orders (M11, ADR-024). They live in their own payload because a line carries an item
        // with data components, unlike the fixed-size status, and they are compared by value: an aisle whose orders do
        // not change costs no packet, exactly like an aisle whose stock does not change.
        List<ProductionScreenState.OrderView> orders = contentHolder.productionOrderViews();
        if (reset || !orders.equals(lastOrders)) {
            lastOrders = orders;
            PacketDistributor.sendToPlayer(target, new TerminalOrdersPayload(containerId, orders));
        }
    }

    /**
     * Server: the terminal's stock snapshot in the pure form the screen gets ({@code core.terminal.StockCount}).
     * <p>
     * It is a method of its own so that a test can exercise the conversion: every field of an entry has to survive it,
     * and one that does not is invisible to both a server-side test of the snapshot and a client-side test of the
     * screen. The producible amount was dropped here exactly once (M11), and only a screenshot found it.
     */
    public List<StockCount<ItemKey>> stockCounts() {
        if (contentHolder == null)
            return List.of();
        List<StockCount<ItemKey>> counts = new ArrayList<>();
        for (TerminalStockEntry entry : contentHolder.stockSnapshot())
            counts.add(new StockCount<>(entry.key(), entry.total(), entry.available(), entry.producible(),
                    entry.producibleAmount()));
        return counts;
    }

    /** Sends {@code changes} in pages of at most {@link TerminalStockPayload#MAX_ENTRIES}; only the first page resets. */
    private void sendStock(ServerPlayer target, boolean reset, List<StockCount<ItemKey>> changes) {
        if (changes.isEmpty()) {
            PacketDistributor.sendToPlayer(target, new TerminalStockPayload(containerId, reset, List.of()));
            return;
        }
        boolean first = true;
        for (int from = 0; from < changes.size(); from += TerminalStockPayload.MAX_ENTRIES) {
            List<StockCount<ItemKey>> page = changes.subList(from,
                    Math.min(changes.size(), from + TerminalStockPayload.MAX_ENTRIES));
            PacketDistributor.sendToPlayer(target, new TerminalStockPayload(containerId, reset && first, page));
            first = false;
        }
    }

    /** The game time of the terminal's level, or {@link #NEVER} while there is none. */
    private long gameTime() {
        Level level = contentHolder == null ? null : contentHolder.getLevel();
        return level == null ? NEVER : level.getGameTime();
    }

    /** A buffer slot: the player may take items out, but nothing can be put in (as for funnels and chutes). */
    private static final class TerminalBufferSlot extends SlotItemHandler {
        private TerminalBufferSlot(IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
