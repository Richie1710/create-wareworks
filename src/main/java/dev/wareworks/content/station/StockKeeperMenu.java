package dev.wareworks.content.station;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.gui.menu.MenuBase;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.StockRuleAdjustment;
import dev.wareworks.network.StockKeeperScreenPayload;
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
 * The menu behind a warehouse stock keeper's screen ({@code docs/warehouse-system.md} §3.6, M15).
 * <p>
 * It owns two things:
 * <ul>
 * <li>the <b>player inventory slots</b>, so a player can pick an item up and click it into a rule row, or shift-click
 * it straight into the first free row. The keeper itself has no slots, because it holds no items;</li>
 * <li>the <b>throttled push</b> of the rules and what the warehouse currently holds of them, to the one player who has
 * this menu open ({@link StockKeeperScreenPayload}). A rule's item is an {@link ItemKey} with data components and must
 * never travel in a block entity update tag, which is part of every chunk packet.</li>
 * </ul>
 * <b>The rule rows are not slots.</b> They are ghost items and three numbers: a click or a scroll sends
 * {@code StockKeeperRulePayload} and the server writes the rule, so editing a policy can never consume, duplicate or
 * swallow an item. The client is only allowed to <i>ask</i>; {@link #submitRule} resolves the request against the menu
 * the sending player really has open, spends that menu's per-tick edit budget and lets the keeper clamp the rest.
 * <p>
 * Fields set while the super constructor runs must not have initializers, for the reason spelled out in
 * {@code WarehouseTerminalMenu}: a subclass initializer runs after {@code super(...)} and would overwrite them.
 */
public class StockKeeperMenu extends MenuBase<WarehouseStockKeeperBlockEntity> {
    /** How often the server pushes the rules and their state to an open screen (game ticks). */
    public static final int REFRESH_INTERVAL_TICKS = 10;
    /** Rule edits of one menu answered in the same tick; a player clicks or scrolls at most once per tick. */
    public static final int MAX_EDITS_PER_TICK = 8;

    private static final long NEVER = Long.MIN_VALUE;

    /** Set during the super constructor (see the class comment): no initializers here. */
    private int rowCount;
    private StockKeeperMenuLayout layout;

    /** Server side only; likewise without initializers. */
    private long lastPushGameTime;
    private boolean pushPending;
    private long editBudgetGameTime;
    private int editsThisTick;
    private StockRuleAdjustment lastAdjustment;
    private int lastAdjustedRow;
    /** Whether the last accepted edit lifted a rule's safety stop (M15 part 2); the screen says so once. */
    private boolean lastResumed;
    @Nullable
    private StockKeeperScreenState lastState;

    /** Client: Registrate's menu factory, with the extra data the server wrote when the screen was opened. */
    public StockKeeperMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(type, id, inv, extraData);
    }

    /** Server: built by the keeper's menu provider. */
    public StockKeeperMenu(MenuType<?> type, int id, Inventory inv, WarehouseStockKeeperBlockEntity keeper) {
        super(type, id, inv, keeper);
    }

    /** Server: the menu of {@code keeper} for {@code inventory}'s player. */
    public static StockKeeperMenu create(int id, Inventory inventory, WarehouseStockKeeperBlockEntity keeper) {
        return new StockKeeperMenu(WareworksMenuTypes.WAREHOUSE_STOCK_KEEPER.get(), id, inventory, keeper);
    }

    /**
     * Client: the block entity behind the screen, at the position the server wrote into the extra data. Named by its
     * full name for the dist reason spelled out in {@code WarehouseTerminalMenu#createOnClient}.
     */
    @OnlyIn(Dist.CLIENT)
    @Override
    protected WarehouseStockKeeperBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
        BlockPos pos = extraData.readBlockPos();
        rowCount = Math.max(1, extraData.readVarInt());
        return dev.wareworks.client.gui.ClientStockKeepers.at(pos);
    }

    @Override
    protected void initAndReadInventory(WarehouseStockKeeperBlockEntity keeper) {
        if (keeper != null && rowCount <= 0)
            rowCount = keeper.rowCount(); // server side: the block entity is authoritative
        rowCount = Math.max(1, Math.min(rowCount, StockKeeperRules.MAX_ROWS));
        layout = new StockKeeperMenuLayout(rowCount);
        lastPushGameTime = NEVER;
        pushPending = true;
        editBudgetGameTime = NEVER;
        editsThisTick = 0;
        lastAdjustment = StockRuleAdjustment.NONE;
        lastAdjustedRow = StockKeeperScreenState.NO_ROW;
        lastState = null;
    }

    @Override
    protected void addSlots() {
        addPlayerSlots(layout.playerSlotsX(), layout.playerSlotsY());
    }

    @Override
    protected void saveData(WarehouseStockKeeperBlockEntity keeper) {
    }

    /** Where everything sits in the window; the same on both sides. */
    public StockKeeperMenuLayout layout() {
        return layout;
    }

    /** The keeper this menu belongs to; {@code null} on a client whose block entity is not loaded. */
    @Nullable
    public WarehouseStockKeeperBlockEntity keeper() {
        return contentHolder;
    }

    /** Rule rows this keeper offers. */
    public int rowCount() {
        return rowCount;
    }

    /** The keeper holds no items, so a shift-click has nothing to move; the screen writes a rule instead. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    // --- rule editing --------------------------------------------------------------------------------------------

    /**
     * Server: the edit of a {@code StockKeeperRulePayload}, resolved against the menu {@code player} really has open.
     * Returns empty (and does nothing) when the player has no stock keeper menu open, the id does not match, or this
     * menu has already answered {@value #MAX_EDITS_PER_TICK} edits in the current tick — so neither a crafted payload
     * nor a flood of them can reach a keeper.
     *
     * @param row   the rule row
     * @param field {@code StockKeeperRules#FIELD_ITEM} and friends
     * @param key   the item to put into the row, or empty to clear it
     * @param value the number, for the three number fields
     * @return whether anything changed
     */
    public static Optional<Boolean> submitRule(@Nullable Player player, int containerId, int row, int field,
            Optional<ItemKey> key, long value) {
        StockKeeperMenu menu = resolve(player, containerId);
        if (menu == null)
            return Optional.empty();
        return Optional.of(menu.edit(player, row, field, key.orElse(null), value));
    }

    /** The menu {@code player} really has open, if it is this kind and still has budget in this tick. */
    @Nullable
    private static StockKeeperMenu resolve(@Nullable Player player, int containerId) {
        if (player == null || player.level() == null || player.level().isClientSide)
            return null;
        if (!(player.containerMenu instanceof StockKeeperMenu menu) || menu.containerId != containerId)
            return null;
        return menu.takeEditBudget() ? menu : null;
    }

    /** Server: writes one rule field; the keeper clamps the numbers and reports what it had to correct. */
    private boolean edit(Player player, int row, int field, @Nullable ItemKey key, long value) {
        if (!mayEdit(player))
            return false;
        StockKeeperRules.Edit edit = contentHolder.editRule(row, field, key, value);
        lastAdjustment = edit.adjustment();
        lastAdjustedRow = edit.adjustment() == StockRuleAdjustment.NONE ? StockKeeperScreenState.NO_ROW : row;
        // Re-arming an automatic order is never silent (M15 part 2): the next push says that the safety stop was
        // lifted, whether the player clicked the mark or simply rewrote the rule.
        lastResumed = edit.resumed();
        markDirty(); // also pushes back a refused or corrected edit, so the screen never shows a value nobody stored
        return edit.changed();
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
     * Pushes the rules and their current state to the player who has this menu open, at most once per game tick and at
     * most every {@value #REFRESH_INTERVAL_TICKS} ticks unless an edit marked the menu dirty. Nothing is sent while
     * nothing changed. The two early returns are the terminal's, for the same reasons.
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
        StockKeeperScreenState state = contentHolder.screenState(lastAdjustment, lastAdjustedRow, lastResumed);
        if (state.equals(lastState))
            return;
        lastState = state;
        PacketDistributor.sendToPlayer(serverPlayer, new StockKeeperScreenPayload(containerId, state));
    }

    private long gameTime() {
        Level level = contentHolder == null ? null : contentHolder.getLevel();
        return level == null ? NEVER : level.getGameTime();
    }
}
