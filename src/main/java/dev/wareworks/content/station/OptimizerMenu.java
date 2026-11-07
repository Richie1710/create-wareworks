package dev.wareworks.content.station;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.gui.menu.MenuBase;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.optimizer.OptimizerOrder;
import dev.wareworks.core.optimizer.OptimizerOrders;
import dev.wareworks.core.stock.PriorityBreakdown;
import dev.wareworks.network.OptimizerScreenPayload;
import dev.wareworks.registry.WareworksMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The menu behind a Warehouse Optimizer's screen (issue #46, ADR-079), a copy of the stock keeper's
 * ({@link StockKeeperMenu}).
 * <p>
 * It owns two things:
 * <ul>
 * <li>the <b>player inventory slots</b>, so a player can pick an item or a clipboard up and click it into the window,
 * or shift-click an item straight into the first free row. The optimizer itself has no slots, because it holds no
 * items;</li>
 * <li>the <b>throttled push</b> of the rows and what the warehouse holds of their items, to the one player who has this
 * menu open ({@link OptimizerScreenPayload}).</li>
 * </ul>
 * <b>The view runs only while the screen is open</b>, and only as often as it has to. The counts are pushed at most
 * every {@value #REFRESH_INTERVAL_TICKS} ticks; the walk over the storage locations that finds the rooms
 * ({@code WarehouseControllerBlockEntity#optimizerRooms}) is kept and repeated at most every
 * {@value #ROOM_INTERVAL_TICKS} ticks — sooner only when the list changed or a trip of the order put items down. Nothing
 * of it runs while nobody looks, and nothing of it ever runs per tick.
 * <p>
 * <b>The rows are not slots.</b> A click sends {@code OptimizerActionPayload} and the server writes the row, so editing
 * the list can never consume, duplicate or swallow an item. The client is only allowed to <i>ask</i>; {@link #submit}
 * resolves the request against the menu the sending player really has open and spends that menu's per-tick budget.
 * <p>
 * Fields set while the super constructor runs must not have initializers, for the reason spelled out in
 * {@code WarehouseTerminalMenu}: a subclass initializer runs after {@code super(...)} and would overwrite them.
 */
public class OptimizerMenu extends MenuBase<WarehouseOptimizerBlockEntity> {
    /** How often the server pushes the rows and their counts to an open screen (game ticks). */
    public static final int REFRESH_INTERVAL_TICKS = 10;
    /** How often, at most, the open screen walks the storage locations again for the rooms (game ticks). */
    public static final int ROOM_INTERVAL_TICKS = 40;
    /** Actions of one menu answered in the same tick; a player clicks at most once per tick. */
    public static final int MAX_EDITS_PER_TICK = 8;

    private static final long NEVER = Long.MIN_VALUE;

    /** Set during the super constructor (see the class comment): no initializers here. */
    private OptimizerMenuLayout layout;

    /** Server side only; likewise without initializers. */
    private long lastPushGameTime;
    private boolean pushPending;
    private long editBudgetGameTime;
    private int editsThisTick;
    private OptimizerScreenState.Message message;
    private int messageFirst;
    private int messageSecond;
    @Nullable
    private OptimizerScreenState lastState;
    /** The rooms of the last walk, for the keys it was made for, and when it was made. */
    @Nullable
    private Map<ItemKey, List<PriorityBreakdown.Room>> rooms;
    @Nullable
    private List<ItemKey> roomKeys;
    private long roomsGameTime;
    /** What the order had moved at the last walk: a trip that put items down changes the rooms it filled. */
    private long roomsMoved;

    /** Client: Registrate's menu factory, with the extra data the server wrote when the screen was opened. */
    public OptimizerMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(type, id, inv, extraData);
    }

    /** Server: built by the optimizer's menu provider. */
    public OptimizerMenu(MenuType<?> type, int id, Inventory inv, WarehouseOptimizerBlockEntity optimizer) {
        super(type, id, inv, optimizer);
    }

    /** Server: the menu of {@code optimizer} for {@code inventory}'s player. */
    public static OptimizerMenu create(int id, Inventory inventory, WarehouseOptimizerBlockEntity optimizer) {
        return new OptimizerMenu(WareworksMenuTypes.WAREHOUSE_OPTIMIZER.get(), id, inventory, optimizer);
    }

    /**
     * Client: the block entity behind the screen, at the position the server wrote into the extra data. Named by its
     * full name for the dist reason spelled out in {@code WarehouseTerminalMenu#createOnClient}.
     */
    @OnlyIn(Dist.CLIENT)
    @Override
    protected WarehouseOptimizerBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
        BlockPos pos = extraData.readBlockPos();
        return dev.wareworks.client.gui.ClientOptimizers.at(pos);
    }

    @Override
    protected void initAndReadInventory(WarehouseOptimizerBlockEntity optimizer) {
        layout = new OptimizerMenuLayout();
        lastPushGameTime = NEVER;
        pushPending = true;
        editBudgetGameTime = NEVER;
        editsThisTick = 0;
        message = OptimizerScreenState.Message.NONE;
        messageFirst = 0;
        messageSecond = 0;
        lastState = null;
        rooms = null;
        roomKeys = null;
        roomsGameTime = NEVER;
        roomsMoved = 0L;
    }

    @Override
    protected void addSlots() {
        addPlayerSlots(layout.playerSlotsX(), layout.playerSlotsY());
    }

    @Override
    protected void saveData(WarehouseOptimizerBlockEntity optimizer) {
    }

    /** Where everything sits in the window; the same on both sides. */
    public OptimizerMenuLayout layout() {
        return layout;
    }

    /** The optimizer this menu belongs to; {@code null} on a client whose block entity is not loaded. */
    @Nullable
    public WarehouseOptimizerBlockEntity optimizer() {
        return contentHolder;
    }

    /** Item rows the optimizer offers. */
    public int rowCount() {
        return layout.rows();
    }

    /** The optimizer holds no items, so a shift-click has nothing to move; the screen writes a row instead. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    // --- actions -------------------------------------------------------------------------------------------------

    /**
     * Server: the action of an {@code OptimizerActionPayload}, resolved against the menu {@code player} really has open.
     * Returns empty (and does nothing) when the player has no optimizer menu open, the id does not match, or this menu
     * has already answered {@value #MAX_EDITS_PER_TICK} actions in the current tick — so neither a crafted payload nor
     * a flood of them can reach an optimizer.
     *
     * @param action what to do
     * @param row    the row it is about (set, clear, optimise one row)
     * @param key    the item to set (only read for {@link OptimizerAction#SET_ITEM})
     * @return whether anything changed
     */
    public static Optional<Boolean> submit(@Nullable Player player, int containerId, OptimizerAction action, int row,
            Optional<ItemKey> key) {
        OptimizerMenu menu = resolve(player, containerId);
        if (menu == null || action == null)
            return Optional.empty();
        return Optional.of(menu.act(player, action, row, key.orElse(null)));
    }

    /** The menu {@code player} really has open, if it is this kind and still has budget in this tick. */
    @Nullable
    private static OptimizerMenu resolve(@Nullable Player player, int containerId) {
        if (player == null || player.level() == null || player.level().isClientSide)
            return null;
        if (!(player.containerMenu instanceof OptimizerMenu menu) || menu.containerId != containerId)
            return null;
        return menu.takeEditBudget() ? menu : null;
    }

    /** Server: carries out one action and records what the status line says about it. */
    private boolean act(Player player, OptimizerAction action, int row, @Nullable ItemKey key) {
        if (!mayEdit(player))
            return false;
        say(OptimizerScreenState.Message.NONE, 0, 0);
        boolean changed = switch (action) {
            case SET_ITEM -> key != null && edited(contentHolder.editRow(row, key));
            case CLEAR_ROW -> edited(contentHolder.editRow(row, null));
            case IMPORT_CLIPBOARD -> importClipboard();
            case OPTIMISE -> started(contentHolder.rows().keys());
            case OPTIMISE_ROW -> started(contentHolder.rows().keyAt(row).map(List::of).orElse(List.of()));
            case CANCEL -> contentHolder.cancel();
        };
        markDirty(); // also pushes back a refused action, so the screen never shows a row nobody stored
        return changed;
    }

    private boolean edited(OptimizerRows.Edit edit) {
        if (edit == OptimizerRows.Edit.ALREADY_LISTED)
            say(OptimizerScreenState.Message.ALREADY_LISTED, 0, 0);
        return edit == OptimizerRows.Edit.CHANGED;
    }

    /**
     * Reads the clipboard the player carries on the cursor: the server's own copy of it ({@link #getCarried()}), never
     * an item the client names. The clipboard stays on the cursor.
     */
    private boolean importClipboard() {
        Optional<OptimizerRows.Added> added = contentHolder.importClipboard(getCarried());
        if (added.isEmpty()) {
            say(OptimizerScreenState.Message.NOT_A_CLIPBOARD, 0, 0);
            return false;
        }
        OptimizerRows.Added result = added.get();
        if (result.added() == 0 && result.noRoom() == 0)
            say(result.alreadyListed() > 0 ? OptimizerScreenState.Message.ALL_LISTED
                    : OptimizerScreenState.Message.CLIPBOARD_EMPTY, 0, 0);
        else if (result.noRoom() > 0)
            say(OptimizerScreenState.Message.ADDED_NO_ROOM, result.added(), result.noRoom());
        else
            say(OptimizerScreenState.Message.ADDED, result.added(), 0);
        return result.added() > 0;
    }

    private boolean started(List<ItemKey> keys) {
        if (keys.isEmpty()) {
            say(OptimizerScreenState.Message.NO_ITEMS, 0, 0);
            return false;
        }
        Optional<OptimizerOrders.Start> start = contentHolder.optimise(keys);
        if (start.isEmpty())
            return false; // no warehouse: the status line already says so
        switch (start.get()) {
            case STARTED -> {
                return true;
            }
            case NO_ITEMS -> say(OptimizerScreenState.Message.NO_ITEMS, 0, 0);
            case TOO_MANY_ORDERS -> say(OptimizerScreenState.Message.TOO_MANY_ORDERS, 0, 0);
        }
        return false;
    }

    private void say(OptimizerScreenState.Message next, int first, int second) {
        message = next;
        messageFirst = first;
        messageSecond = second;
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
     * Pushes the rows and their breakdown to the player who has this menu open, at most once per game tick and at most
     * every {@value #REFRESH_INTERVAL_TICKS} ticks unless an action marked the menu dirty. Nothing is sent while nothing
     * changed. The two early returns are the keeper's, for the same reasons.
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
        WarehouseControllerBlockEntity controller = contentHolder.controller().orElse(null);
        OptimizerScreenState state = contentHolder.screenState(controller, roomsFor(controller, now), message,
                messageFirst, messageSecond);
        if (state.equals(lastState))
            return;
        lastState = state;
        PacketDistributor.sendToPlayer(serverPlayer, new OptimizerScreenPayload(containerId, state));
    }

    /**
     * The rooms the counts are measured against: the last walk's, or a fresh walk when the list changed, a trip of the
     * order put items down, or {@value #ROOM_INTERVAL_TICKS} ticks have passed.
     */
    private Map<ItemKey, List<PriorityBreakdown.Room>> roomsFor(@Nullable WarehouseControllerBlockEntity controller,
            long now) {
        if (controller == null || controller.isRemoved()) {
            rooms = null;
            return Map.of();
        }
        List<ItemKey> keys = contentHolder.rows().keys();
        long moved = contentHolder.order(controller).map(OptimizerOrder::moved).orElse(0L);
        boolean due = rooms == null || !keys.equals(roomKeys) || moved != roomsMoved || now < roomsGameTime
                || now - roomsGameTime >= ROOM_INTERVAL_TICKS;
        if (due) {
            rooms = controller.optimizerRooms(keys);
            roomKeys = List.copyOf(keys);
            roomsGameTime = now;
            roomsMoved = moved;
        }
        return rooms;
    }

    private long gameTime() {
        Level level = contentHolder == null ? null : contentHolder.getLevel();
        return level == null ? NEVER : level.getGameTime();
    }
}
