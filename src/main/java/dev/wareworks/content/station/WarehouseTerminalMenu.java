package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.gui.menu.MenuBase;

import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.FixedSlotsItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.crafting.IngredientPicker;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.terminal.FluidAcknowledgement;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.core.terminal.StockDiff;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.network.TerminalConfirmPayload;
import dev.wareworks.network.TerminalCraftLayPayload;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalCraftOrderStatePayload;
import dev.wareworks.network.TerminalCraftPayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import dev.wareworks.network.TerminalDoorsPayload;
import dev.wareworks.network.TerminalListActionPayload;
import dev.wareworks.network.TerminalListPayload;
import dev.wareworks.network.TerminalOrdersPayload;
import dev.wareworks.network.TerminalStatusPayload;
import dev.wareworks.network.TerminalStockPayload;
import dev.wareworks.network.TerminalUsagePayload;
import dev.wareworks.registry.WareworksMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.util.FakePlayer;
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
 * The client is never trusted. A request that would cross a stock keeper's reserve or maximum is answered with the
 * terminal's <b>question</b> instead of being made ({@link TerminalRequestOutcome}, {@code warehouse-system.md}
 * §3.6.6); asking costs one click of the same per-tick budget, so a crafted flood cannot make the server measure more
 * often than a clicking player could.
 * <p>
 * <b>Crafting</b> (M32, issue #24, ADR-059) arrives as {@code TerminalCraftPayload} through {@link #submitCraft}, under
 * the same rules and the same budget. The grid is the player's client's and is <b>not</b> a set of menu slots, so the
 * slot list above is unchanged; the menu keeps only the last grid it was sent and the server's reading of it, and
 * pushes that reading with everything else. The craft itself is the terminal's ({@link TerminalCrafting}).
 * <p>
 * <b>The craft order</b> (issue #24, slice 2, ADR-062) — the panel's Fetch and the order it starts — arrives as
 * {@code TerminalCraftOrderPayload} through {@link #submitCraftOrder}, under the same rules and the same budget. Its
 * state is pushed to every screen open at the terminal, grid and lock included, and the question one of its portions
 * raises goes out like the clipboard order's, with {@link RequestScope#CRAFT}.
 * <p>
 * <b>A recipe laid from a recipe viewer</b> (issue #24, slice 4) arrives as {@code TerminalCraftLayPayload} through
 * {@link #submitCraftLay}, under the same rules and the same budget, and at most {@value #MAX_LAYS_PER_TICK} per tick
 * on top: the menu picks one item per cell
 * ({@link IngredientPicker}, {@link TerminalCraftLay}), checks the grid against the recipe manager like a preview, and
 * hands the grid it accepted back with the crafting view ({@link TerminalCraftViewPayload#lay()}).
 * <p>
 * <b>Package doors</b> (M27, issue #18, ADR-065): a click may name one, and it costs the same click of the budget. The
 * doors a screen may offer are pushed with everything else ({@code TerminalDoorsPayload}), from a build the whole
 * warehouse shares and that nothing but these pushes ever asks for. The menu holds no chosen door: the choice lives in
 * the player's screen, and a request names the door it is for.
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
    /**
     * Recipes a recipe viewer may lay into one menu's grid per tick, on top of {@link #MAX_REQUESTS_PER_TICK}: a lay
     * picks among up to nine times {@value IngredientPicker#MAX_CANDIDATES} candidates, and a player presses a
     * viewer's button once per click.
     */
    public static final int MAX_LAYS_PER_TICK = 1;
    /** Slots of the terminal's list slot: one clipboard (M23, issue #19). */
    public static final int LIST_SLOTS = 1;

    /**
     * Game time of {@link #lastPushGameTime}, {@link #requestBudgetGameTime} and {@link #layBudgetGameTime} before the
     * first one.
     */
    private static final long NEVER = Long.MIN_VALUE;

    /** Set during the super constructor (see the class comment): no initializers here. */
    private int bufferSlots;
    private TerminalMenuLayout layout;
    private IItemHandler bufferHandler;
    private IItemHandler listHandler;

    /** Server side only; also set during the super constructor, so likewise without initializers. */
    private StockDiff<ItemKey> stockDiff;
    private boolean fullSyncPending;
    private long lastPushGameTime;
    private boolean pushPending;
    private long requestBudgetGameTime;
    private int requestsThisTick;
    private long layBudgetGameTime;
    private int laysThisTick;
    @Nullable
    private TerminalScreenStatus lastStatus;
    @Nullable
    private List<ProductionScreenState.OrderView> lastOrders;
    /** The clipboard order's state as last pushed, so a terminal whose list did not move costs no packet (M23). */
    @Nullable
    private TerminalListState lastList;
    /**
     * The order and the counts as last pushed, so a player who requests nothing costs no packet (M24, issue #17). It is
     * the payload rather than the store, because the store is mutated in place and could not be compared with itself.
     */
    @Nullable
    private TerminalUsagePayload lastUsage;
    /**
     * The question the clipboard order is waiting on goes out with the next push, although the order did not change
     * state: the list button's <b>Answer</b> ({@link #resendListQuestion()}, M23 review fix).
     */
    private boolean listQuestionPending;
    /**
     * The clipboard order's question as last pushed, or {@code null} while it asks nothing (issue #24 review fix).
     * Compared by <b>identity</b>: every question an order asks is a new object, so a question that replaced another
     * within one tick — another player answered the first, and the next portion asked at once — is pushed although the
     * order never left {@code ASKING}, even when its numbers happen to equal the last one's.
     */
    @Nullable
    private RequestConfirmation<ItemKey> lastListQuestion;
    /** The craft order's state as last pushed, so a terminal whose craft order did not move costs no packet. */
    @Nullable
    private TerminalCraftOrderState lastCraftOrder;
    /**
     * The package doors as last pushed (M27, issue #18, ADR-065 D9), so a warehouse whose doors and their counts did
     * not change costs no packet. Transient per-menu state like every field here.
     */
    @Nullable
    private TerminalDoors lastDoors;
    /** The fluid half last pushed (M31); {@code null} before the first push, so the first one always goes out. */
    private TerminalFluids lastFluids;
    /** The question the craft order is waiting on goes out with the next push: its order button's <b>Answer</b>. */
    private boolean craftQuestionPending;
    /** The craft order's question as last pushed; see {@link #lastListQuestion}. */
    @Nullable
    private RequestConfirmation<ItemKey> lastCraftQuestion;
    /**
     * The crafting panel's grid as this player's screen last sent it, the recipe hint that came with it, and the
     * server's reading of the two (M32, issue #24, {@code docs/warehouse-system.md} §3.4.5). <b>Transient</b> per-menu
     * state: never saved, and gone when the menu closes. The grid itself belongs to the player's client; the server
     * keeps only what it needs to answer the panel.
     */
    private CraftGrid<ItemKey> craftCells;
    private Optional<ResourceLocation> craftHint;
    private TerminalCrafting.Preview craftPreview;
    /** The crafting view as last pushed, so a panel whose grid and aisle did not change costs no packet (M32). */
    private TerminalCraftViewPayload lastCraftView;
    /** How often this menu has resolved a recipe for its preview: one per changed grid, for the GameTests. */
    private int craftPreviewLookups;
    /**
     * The last recipe a recipe viewer laid into this menu's grid and the server accepted, which every crafting view
     * carries from then on (issue #24, slice 4), and how many were accepted. Transient like the grid.
     */
    private TerminalCraftViewPayload.Lay craftLay;
    private int craftLays;

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
        // The clipboard the list order is read from and ticked off on lives on the block entity (M23, issue #19); a
        // client without it gets a one-slot stand-in, so both sides still build the same slots.
        listHandler = terminal != null ? terminal.listSlot() : new ItemStackHandler(LIST_SLOTS);
        layout = new TerminalMenuLayout(bufferSlots);
        stockDiff = new StockDiff<>();
        fullSyncPending = true;
        lastUsage = null;
        listQuestionPending = false;
        lastListQuestion = null;
        lastCraftOrder = null;
        lastDoors = null;
        craftQuestionPending = false;
        lastCraftQuestion = null;
        craftCells = CraftGrid.empty();
        craftHint = Optional.empty();
        craftPreview = TerminalCrafting.Preview.NONE;
        // An empty grid is what the screen starts from, so a player who never opens the panel costs no packet for it.
        lastCraftView = TerminalCraftViewPayload.none(containerId);
        craftPreviewLookups = 0;
        craftLay = TerminalCraftViewPayload.Lay.NONE;
        craftLays = 0;
        lastPushGameTime = NEVER;
        pushPending = true;
        requestBudgetGameTime = NEVER;
        requestsThisTick = 0;
        layBudgetGameTime = NEVER;
        laysThisTick = 0;
    }

    @Override
    protected void addSlots() {
        // Exactly the announced number of buffer slots, then the one list slot, so bufferSlots is the index of the
        // list slot and bufferSlots + LIST_SLOTS the index of the first player slot.
        for (int slot = 0; slot < bufferSlots; slot++)
            addSlot(new TerminalBufferSlot(bufferHandler, slot, layout.bufferSlotX(slot), layout.bufferSlotY(slot)));
        addSlot(new TerminalListSlot(listHandler, layout.listSlotX(), layout.listSlotY()));
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

    /** Number of buffer slots, i.e. the number of menu slots before the list slot. */
    public int bufferSlots() {
        return bufferSlots;
    }

    /** Index of the terminal's list slot: the one clipboard a list order is read from and ticked off on (M23). */
    public int listSlotIndex() {
        return bufferSlots;
    }

    /** Index of the first player inventory slot. */
    public int firstPlayerSlot() {
        return bufferSlots + LIST_SLOTS;
    }

    // --- shift-clicking --------------------------------------------------------------------------------------------

    /**
     * Delivered items can be taken out of the terminal and a clipboard can be put into the list slot, and that is all.
     * <p>
     * A shift-click in the buffer or in the list slot moves the stack into the player inventory; a shift-click on a
     * <b>clipboard</b> in the player inventory puts it into the empty list slot, which is how a player hands a
     * material checklist over without dragging (M23, issue #19). Everything else in the player inventory does nothing,
     * exactly as before.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem())
            return ItemStack.EMPTY;
        // The stack of a SlotItemHandler must not be modified in place, so everything works on copies.
        ItemStack remainder = slot.getItem().copy();
        ItemStack moved = remainder.copy();
        // addSlots() adds bufferSlots buffer slots, then the list slot, then the player inventory.
        int target = index < firstPlayerSlot() ? firstPlayerSlot() : listSlotIndex();
        int targetEnd = index < firstPlayerSlot() ? slots.size() : listSlotIndex() + LIST_SLOTS;
        if (!moveItemStackTo(remainder, target, targetEnd, true))
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
        return submitRequest(player, containerId, key, amount, RequestAcknowledgement.ANY)
                .map(outcome -> outcome.result().orElseThrow());
    }

    /**
     * Server: {@link #submitRequest(Player, int, ItemKey, int)} with what the player has already agreed to pay for the
     * request ({@code docs/warehouse-system.md} §3.6.6, M15 part 2). This is the path the payload handler takes.
     * <p>
     * A click the player has not agreed to yet answers with the terminal's <b>question</b> and requests nothing
     * ({@link TerminalRequestOutcome}). Asking costs one click of the same per-tick budget a request costs: the server
     * measures what the click would cross, which is bounded work, and a crafted flood can therefore not make it
     * measure more often than a clicking player could.
     */
    public static Optional<TerminalRequestOutcome> submitRequest(@Nullable Player player, int containerId, ItemKey key,
            int amount, RequestAcknowledgement acknowledged) {
        return submitRequest(player, containerId, key, amount, acknowledged, Optional.empty());
    }

    /**
     * Server: {@link #submitRequest(Player, int, ItemKey, int, RequestAcknowledgement)} delivered to the package door
     * the click named (M27, issue #18, ADR-065), or here for an empty {@code door}. This is the path the payload handler
     * takes.
     * <p>
     * The door is only a hint like everything else in the payload: the terminal checks it against its own warehouse
     * after reach, amount and controller and before the stock is looked at
     * ({@link WarehouseTerminalBlockEntity#requestFromTerminal(Player, ItemKey, int, RequestAcknowledgement,
     * Optional)}), and checking it costs the same one click of the per-tick budget as the
     * request itself, so a crafted flood of door positions cannot make the server look at the world more often than a
     * clicking player could.
     */
    public static Optional<TerminalRequestOutcome> submitRequest(@Nullable Player player, int containerId, ItemKey key,
            int amount, RequestAcknowledgement acknowledged, Optional<BlockPos> door) {
        if (player == null || player.level() == null || player.level().isClientSide || key == null
                || acknowledged == null)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != containerId)
            return Optional.empty();
        if (!menu.takeRequestBudget())
            return Optional.empty();
        return Optional.of(menu.request(player, key, amount, acknowledged, door == null ? Optional.empty() : door));
    }

    /** Server: asks this menu's terminal for items, accepting every boundary; every check happens in the terminal. */
    public RequestResult request(Player requester, ItemKey key, int amount) {
        return request(requester, key, amount, RequestAcknowledgement.ANY).result().orElseThrow();
    }

    /** Server: asks this menu's terminal for items; every check, and the question, happens in the terminal. */
    public TerminalRequestOutcome request(Player requester, ItemKey key, int amount,
            RequestAcknowledgement acknowledged) {
        return request(requester, key, amount, acknowledged, Optional.empty());
    }

    /**
     * Server: asks this menu's terminal for items delivered to {@code door}, or here for an empty one (M27, ADR-065);
     * every check, the door's included, happens in the terminal.
     */
    public TerminalRequestOutcome request(Player requester, ItemKey key, int amount,
            RequestAcknowledgement acknowledged, Optional<BlockPos> door) {
        if (contentHolder == null || contentHolder.isRemoved())
            return TerminalRequestOutcome.of(RequestResult.rejected(RequestRejection.NO_CONTROLLER)).withDoor(door);
        TerminalRequestOutcome outcome = contentHolder.requestFromTerminal(requester, key, amount, acknowledged, door);
        if (outcome.result().filter(RequestResult::isAccepted).isPresent())
            markDirty(); // the availability changed: push it with the next tick instead of waiting for the interval
        return outcome;
    }

    /**
     * Server: a {@code TerminalFluidRequestPayload}, resolved against the menu {@code player} really has open (M31, issue
     * #21, ADR-073): empty (and nothing done) when the player has no terminal menu open, the id does not match, or this
     * menu has already answered {@value #MAX_REQUESTS_PER_TICK} actions in the current tick — a fluid click shares the
     * item clicks' budget, so a crafted flood cannot make the server measure containers more often than a clicking
     * player could. Every other check, and the question, happens in the terminal
     * ({@link WarehouseTerminalBlockEntity#requestFluidFromTerminal}).
     */
    public static Optional<TerminalFluidOutcome> submitFluidRequest(@Nullable Player player, int containerId,
            FluidKey fluid, long amountMb, FluidAcknowledgement acknowledged, Optional<BlockPos> door) {
        if (player == null || player.level() == null || player.level().isClientSide || fluid == null
                || acknowledged == null)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != containerId)
            return Optional.empty();
        if (!menu.takeRequestBudget())
            return Optional.empty();
        return Optional.of(menu.requestFluid(player, fluid, amountMb, acknowledged,
                door == null ? Optional.empty() : door));
    }

    /** Server: asks this menu's terminal for fluid; every check, and the question, happens in the terminal. */
    public TerminalFluidOutcome requestFluid(Player requester, FluidKey fluid, long amountMb,
            FluidAcknowledgement acknowledged, Optional<BlockPos> door) {
        if (contentHolder == null || contentHolder.isRemoved())
            return TerminalFluidOutcome.of(RequestResult.rejected(RequestRejection.NO_CONTROLLER)).withDoor(door);
        TerminalFluidOutcome outcome = contentHolder.requestFluidFromTerminal(requester, fluid, amountMb, acknowledged,
                door);
        if (outcome.result().filter(RequestResult::isAccepted).isPresent())
            markDirty(); // the availability changed: push it with the next tick instead of waiting for the interval
        return outcome;
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

    /**
     * Server: the order of a {@code TerminalSortPayload}, stored on the player it came from (M24, issue #17,
     * ADR-037).
     * <p>
     * Empty (and nothing done) when the player has no terminal menu open or the id does not match, like every other
     * payload here. It deliberately spends <b>no</b> request budget: pressing the sort button asks the warehouse for
     * nothing, measures nothing and walks no pattern — it writes one enum constant onto the player — so charging it
     * against the eight clicks a tick would let a crafted flood of them block real requests. The work it can cause is
     * bounded by {@link #broadcastChanges()}, which pushes at most one packet per tick whatever happens here.
     *
     * @return the order that is now stored, or empty when nothing was reached
     */
    public static Optional<TerminalSort> submitSort(@Nullable Player player, int containerId,
            @Nullable TerminalSort sort) {
        if (player == null || player.level() == null || player.level().isClientSide)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != containerId)
            return Optional.empty();
        TerminalPreferences preferences = TerminalPreferences.of(player);
        if (preferences.setSort(sort))
            menu.markDirty(); // confirm the stored order with the next push, so the two sides cannot drift apart
        return Optional.of(preferences.sort());
    }

    /**
     * Server: a {@code TerminalListActionPayload}, resolved against the menu {@code player} really has open (M23,
     * issue #19, {@code docs/warehouse-system.md} §3.4.4).
     * <p>
     * Empty (and nothing done) when the player has no terminal menu open, the id does not match, the action is one
     * this build does not know, or this menu has already answered {@value #MAX_REQUESTS_PER_TICK} actions in the
     * current tick — pressing Fetch is a click like a request and shares that budget, so a crafted flood cannot make
     * the server measure a whole list more often than a clicking player could. Everything else — reach, aisle, the
     * clipboard, the numbers — is validated by the terminal.
     */
    public static Optional<TerminalListOutcome> submitListAction(@Nullable Player player, int containerId,
            TerminalListActionPayload.Action action, long acceptedMissing, long acceptedProducing,
            long acceptedDropped, RequestAcknowledgement acknowledged) {
        return submitListAction(player, containerId, action, acceptedMissing, acceptedProducing, acceptedDropped,
                acknowledged, Optional.empty());
    }

    /**
     * {@link #submitListAction(Player, int, TerminalListActionPayload.Action, long, long, long, RequestAcknowledgement)}
     * with the question an {@code ANSWER} was given to (issue #24 review fix), which the terminal compares with the
     * question it is asking now.
     */
    public static Optional<TerminalListOutcome> submitListAction(@Nullable Player player, int containerId,
            TerminalListActionPayload.Action action, long acceptedMissing, long acceptedProducing,
            long acceptedDropped, RequestAcknowledgement acknowledged,
            @Nullable Optional<RequestConfirmation<ItemKey>> answered) {
        if (player == null || player.level() == null || player.level().isClientSide || action == null
                || action == TerminalListActionPayload.Action.NONE)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != containerId)
            return Optional.empty();
        if (!menu.takeRequestBudget())
            return Optional.empty();
        return Optional.of(menu.listAction(player, action, acceptedMissing, acceptedProducing, acceptedDropped,
                acknowledged == null ? RequestAcknowledgement.NONE : acknowledged,
                answered == null ? Optional.empty() : answered));
    }

    /**
     * Server: carries one list action out on this menu's terminal, an {@code ANSWER} naming no question (the list
     * button's "put the question up again").
     */
    public TerminalListOutcome listAction(Player requester, TerminalListActionPayload.Action action,
            long acceptedMissing, long acceptedProducing, long acceptedDropped,
            RequestAcknowledgement acknowledged) {
        return listAction(requester, action, acceptedMissing, acceptedProducing, acceptedDropped, acknowledged,
                Optional.empty());
    }

    /**
     * Server: carries one list action out on this menu's terminal; every check happens in the terminal. An
     * {@code ANSWER} is taken only for {@code answered}, the question the screen showed, while it is still the one
     * the order asks; otherwise the current question is put up again.
     */
    public TerminalListOutcome listAction(Player requester, TerminalListActionPayload.Action action,
            long acceptedMissing, long acceptedProducing, long acceptedDropped,
            RequestAcknowledgement acknowledged, Optional<RequestConfirmation<ItemKey>> answered) {
        Objects.requireNonNull(answered, "answered");
        if (contentHolder == null || contentHolder.isRemoved())
            return TerminalListOutcome.of(TerminalListResult.NO_AISLE);
        TerminalListOutcome outcome = switch (action) {
            case FETCH -> contentHolder.fetchList(requester, acceptedMissing, acceptedProducing, acceptedDropped);
            case ANSWER -> contentHolder.answerListQuestion(requester, answered, acknowledged);
            case DECLINE -> contentHolder.declineListQuestion(requester);
            case RESUME -> contentHolder.resumeListOrder(requester);
            case CANCEL -> contentHolder.canPlayerUse(requester)
                    ? TerminalListOutcome.of(contentHolder.cancelListOrder() ? TerminalListResult.CANCELLED
                            : TerminalListResult.NOT_RUNNING)
                    : TerminalListOutcome.of(TerminalListResult.OUT_OF_REACH);
            case NONE -> TerminalListOutcome.of(TerminalListResult.NOT_RUNNING);
        };
        // "Put that question up again" changes nothing and is therefore not a state push: the question itself is sent,
        // which is the whole point of the action (M23 review fix).
        if (outcome.result() == TerminalListResult.QUESTION)
            resendListQuestion();
        else if (outcome.isSuccess())
            markDirty(); // the list state changed: push it with the next tick instead of waiting for the interval
        return outcome;
    }

    /**
     * Server: send the question the clipboard order is waiting on with the next push, even though the order did not
     * change state (M23 review fix).
     * <p>
     * The question is pushed on the <b>edge</b> into {@link dev.wareworks.core.terminal.ListOrderState#ASKING} and on a
     * full sync, so a player who dismissed the panel without answering would otherwise have to close and reopen the
     * screen to see it again. This is the list button's <b>Answer</b>.
     */
    private void resendListQuestion() {
        listQuestionPending = true;
        markDirty();
    }

    /** Server: gives up on a production order of this terminal's aisle; every check happens in the terminal. */
    public boolean cancel(Player requester, UUID orderId) {
        if (contentHolder == null || contentHolder.isRemoved())
            return false;
        // The cancel mark of an order line names a production order, or — since M31 — one of this terminal's fill
        // requests ("Filling from Lava"); each of the two re-validates the id against its own state.
        boolean cancelled = contentHolder.cancelProductionOrder(requester, orderId)
                || contentHolder.cancelFillRequest(requester, orderId);
        if (cancelled)
            markDirty(); // the promised ingredients are free again: push the new availability with the next tick
        return cancelled;
    }

    // --- crafting (M32, issue #24, ADR-059) ------------------------------------------------------------------------

    /**
     * Server: a {@code TerminalCraftPayload}, resolved against the menu {@code player} really has open (M32, issue
     * #24, {@code docs/warehouse-system.md} §3.4.5).
     * <p>
     * Checked in this order, and empty (with nothing done) at the first that fails: the player has a terminal menu open
     * whose id matches; the player is not a {@link FakePlayer}; this menu has not answered
     * {@value #MAX_REQUESTS_PER_TICK} actions in the current tick. A preview and a craft are clicks like a request and
     * share that budget — one action whatever the number of crafts — so a crafted flood cannot make the server look up
     * recipes or plan crafts more often than a clicking player could.
     * <p>
     * <b>The fake player is refused here and only here</b>, as defence in depth: a craft is a player's own hand at a
     * screen, and no automation that drives a fake player has one. The method behind it ({@link #craft}) does not
     * refuse one, so a GameTest can still exercise the server-player path (limited crafting, the recipe award).
     *
     * @return the outcome of a craft; empty for a preview, which the next push answers, and for everything dropped
     */
    public static Optional<TerminalCraftOutcome> submitCraft(@Nullable Player player, int containerId,
            TerminalCraftPayload.Action action, CraftGrid<ItemKey> cells, Optional<ResourceLocation> hint) {
        if (player == null || player.level() == null || player.level().isClientSide || action == null
                || action == TerminalCraftPayload.Action.NONE || cells == null || hint == null)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != containerId)
            return Optional.empty();
        if (player instanceof FakePlayer)
            return Optional.empty();
        if (!menu.takeRequestBudget())
            return Optional.empty();
        return switch (action) {
            case PREVIEW -> {
                menu.previewCraft(player, cells, hint);
                yield Optional.empty();
            }
            case CRAFT -> Optional.of(menu.craft(player, cells, hint, false));
            case CRAFT_ALL -> Optional.of(menu.craft(player, cells, hint, true));
            case NONE -> Optional.empty();
        };
    }

    /**
     * Server: the grid {@code player}'s screen shows now, and the server's reading of it for the next push.
     * <p>
     * The recipe is looked up <b>only when the grid or the hint changed</b> — and a hint that names the very recipe the
     * last lookup found is no change, because looking it up again could only find that recipe. The lookup never runs
     * inside {@link #broadcastChanges()}: the push only reads what was resolved here.
     */
    public void previewCraft(Player player, CraftGrid<ItemKey> cells, Optional<ResourceLocation> hint) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(hint, "hint");
        // A screen that shows the craft order's grid resolves the recipe the order fetches for, not the first of two
        // recipes the grid matches: without a hint of its own (it adopted the locked grid, or was opened again), and
        // while the order is open whatever it last showed (issue #24 review fix).
        if (contentHolder != null) {
            Optional<ResourceLocation> orderHint = contentHolder.craftOrderHint(cells);
            if (orderHint.isPresent() && (hint.isEmpty() || contentHolder.hasOpenCraftOrder()))
                hint = orderHint;
        }
        boolean sameHint = hint.equals(craftHint) || hint.isPresent() && hint.equals(craftPreview.recipe());
        if (cells.equals(craftCells) && sameHint)
            return;
        craftCells = cells;
        craftHint = hint;
        resolveCraftPreview(player);
    }

    /**
     * Server: crafts {@code cells} from this menu's terminal buffer for {@code player}, once or with {@code all} as
     * often as the delivered ingredients allow; every check happens in the terminal
     * ({@link WarehouseTerminalBlockEntity#craftFromBuffer}). No aisle is needed: a misaligned terminal still crafts
     * from its own buffer.
     * <p>
     * The grid the click was made on is the one the panel shows from now on ({@link #previewCraft}), and a craft that
     * tells something new about the recipe — refused as locked, or crafted although the preview said locked — has the
     * preview resolved again, so the panel's lock mark cannot outlive what the recipe book really says.
     */
    public TerminalCraftOutcome craft(Player player, CraftGrid<ItemKey> cells, Optional<ResourceLocation> hint,
            boolean all) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(hint, "hint");
        if (contentHolder == null || contentHolder.isRemoved())
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        previewCraft(player, cells, hint);
        TerminalCraftOutcome outcome = contentHolder.craftFromBuffer(player, this, cells, hint, all);
        boolean lockedNow = outcome.result() == TerminalCraftResult.LOCKED;
        if ((lockedNow || outcome.crafted() > 0) && lockedNow != craftPreview.locked())
            resolveCraftPreview(player);
        return outcome;
    }

    /**
     * Server: a {@code TerminalCraftOrderPayload} — the crafting panel's Fetch, or the Answer, No, Resume or Cancel of
     * the craft order it starts — resolved against the menu {@code player} really has open (issue #24, slice 2,
     * {@code docs/warehouse-system.md} §3.4.5, ADR-062).
     * <p>
     * Checked exactly like {@link #submitCraft}, in the same order, and empty (with nothing done) at the first check
     * that fails: the player has a terminal menu open whose id matches; the action is one this build knows; the player
     * is not a {@link FakePlayer}; this menu has not answered {@value #MAX_REQUESTS_PER_TICK} actions in the current
     * tick. A Fetch measures the whole grid against the warehouse, so it is a click like a request and costs one of
     * that budget, as every other action here does. Everything else — reach, aisle, the grid, the numbers — is
     * validated by the terminal.
     *
     * @return what the action became, for the acting player; empty for everything dropped
     */
    public static Optional<TerminalCraftOutcome> submitCraftOrder(@Nullable Player player,
            @Nullable TerminalCraftOrderPayload payload) {
        if (player == null || player.level() == null || player.level().isClientSide || payload == null
                || payload.action() == TerminalCraftOrderPayload.Action.NONE)
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != payload.containerId())
            return Optional.empty();
        if (player instanceof FakePlayer)
            return Optional.empty();
        if (!menu.takeRequestBudget())
            return Optional.empty();
        return Optional.of(menu.craftOrderAction(player, payload));
    }

    /**
     * Server: carries one craft order action out on this menu's terminal; every check happens in the terminal.
     * <p>
     * A <b>Fetch</b> is measured on the grid it was sent for: that grid becomes the panel's ({@link #previewCraft}), so
     * the recipe is resolved here — where ADR-059 allows it — at most once per changed grid, and the terminal reads the
     * grid and its recipe from this menu ({@link WarehouseTerminalBlockEntity#fetchCraft}). An Answer that accepts
     * nothing puts the waiting question up again ({@link #resendCraftQuestion()}); every other success pushes the
     * order's new state with the next tick.
     */
    public TerminalCraftOutcome craftOrderAction(Player requester, TerminalCraftOrderPayload payload) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(payload, "payload");
        if (contentHolder == null || contentHolder.isRemoved())
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        TerminalCraftOutcome outcome = switch (payload.action()) {
            case FETCH -> {
                previewCraft(requester, payload.cells(), payload.hint());
                yield contentHolder.fetchCraft(requester, this, payload.crafts(), payload.acceptedProducing(),
                        payload.answering());
            }
            case ANSWER -> contentHolder.answerCraftQuestion(requester, payload.question(), payload.acknowledged());
            case DECLINE -> contentHolder.declineCraftQuestion(requester);
            case RESUME -> contentHolder.resumeCraftOrder(requester);
            case CANCEL -> contentHolder.cancelCraftOrder(requester);
            case NONE -> TerminalCraftOutcome.of(TerminalCraftResult.NOT_RUNNING);
        };
        // "Put that question up again" changes nothing and is therefore not a state push: the question itself is sent.
        if (payload.action() == TerminalCraftOrderPayload.Action.ANSWER
                && outcome.result() == TerminalCraftResult.ASKING)
            resendCraftQuestion();
        else if (outcome.isSuccess())
            markDirty(); // the order's state changed: push it with the next tick instead of waiting for the interval
        return outcome;
    }

    /**
     * Server: send the question the craft order is waiting on with the next push, even though the order did not change
     * state — the order button's <b>Answer</b>, exactly as {@link #resendListQuestion()} is the list button's.
     */
    private void resendCraftQuestion() {
        craftQuestionPending = true;
        markDirty();
    }

    // --- a recipe laid from a recipe viewer (issue #24, slice 4) ---------------------------------------------------

    /**
     * Server: a {@code TerminalCraftLayPayload} — a recipe viewer's transfer button — resolved against the menu
     * {@code player} really has open (issue #24, slice 4, {@code docs/warehouse-system.md} §3.4.5).
     * <p>
     * Checked like {@link #submitCraft}, and empty (with nothing done) at the first check that fails: the payload has
     * the one shape a decoder lets through ({@link TerminalCraftLayPayload#wellFormed()}: nine cells of at most
     * {@value TerminalCraftLayPayload#MAX_CANDIDATES} candidates); the player has a terminal menu open whose id
     * matches; that menu belongs to the terminal the payload names; the player is not a {@link FakePlayer}; this menu
     * has not answered {@value #MAX_REQUESTS_PER_TICK} actions in the current tick, a lay counting as one; and it has
     * taken no lay in the current tick ({@value #MAX_LAYS_PER_TICK} per tick, refused ones included).
     * <p>
     * <b>What a lay costs.</b> More than a preview: the pick reads up to nine times
     * {@value IngredientPicker#MAX_CANDIDATES} candidate keys and asks the aisle about the ones it compares, before the
     * one recipe lookup a preview also makes. The candidates are bounded on the way in — at most
     * {@value TerminalCraftLayPayload#MAX_BYTES} bytes, so a client cannot make the keys large, and at most
     * {@value IngredientPicker#MAX_SAME_HASH} distinct keys per hash code kept by the picker, so keys it makes share
     * one cost a few comparisons each — and one lay per tick bounds how often it is paid. A player presses a viewer's
     * button once per click. Everything else — reach, the lock of a running craft order, the grid — is checked by
     * {@link #layRecipe}.
     *
     * @return what the lay became, for the acting player; empty for everything dropped
     */
    public static Optional<TerminalCraftOutcome> submitCraftLay(@Nullable Player player,
            @Nullable TerminalCraftLayPayload payload) {
        if (player == null || player.level() == null || player.level().isClientSide || payload == null
                || !payload.wellFormed())
            return Optional.empty();
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || menu.containerId != payload.containerId())
            return Optional.empty();
        if (menu.contentHolder == null || !menu.contentHolder.getBlockPos().equals(payload.terminal()))
            return Optional.empty();
        if (player instanceof FakePlayer)
            return Optional.empty();
        if (!menu.takeRequestBudget() || !menu.takeLayBudget())
            return Optional.empty();
        return Optional.of(menu.layRecipe(player, payload.cells(), payload.hint(), payload.foldOut()));
    }

    /**
     * Server: lays a recipe into this menu's crafting grid — one item per cell, picked from {@code cells}' candidates
     * — and makes it the grid the panel shows (issue #24, slice 4).
     * <p>
     * In this order, and with nothing changed by any refusal:
     * <ol>
     * <li>the player must have this menu open and still be able to use the terminal, otherwise
     * {@link TerminalCraftResult#OUT_OF_REACH};</li>
     * <li>no craft order may be open at the terminal: its grid is the one every screen there shows, locked, until it
     * ends or is given up ({@link TerminalCraftResult#ALREADY_RUNNING});</li>
     * <li>at least one cell must name a candidate ({@link TerminalCraftResult#GRID_EMPTY});</li>
     * <li>{@link IngredientPicker} picks one key per cell — what the buffer holds first, then what the aisle can hand a
     * player here, then what its production stations make, a plain item before a worn one, cells of the same
     * ingredient alike, and the recipe's first candidate where nothing tells them apart, so a missing ingredient
     * still lays;</li>
     * <li>the picked grid must match a crafting recipe of the server's ({@link TerminalCrafting#preview}, the panel's
     * own lookup, with the viewer's recipe as the hint), otherwise {@link TerminalCraftResult#NOT_LAID}, the lay's
     * own refusal, which says that the viewer's recipe was not laid rather than that the grid on screen makes nothing.
     * A recipe id from the client is never trusted alone: a grid that matches nothing is refused whatever the hint
     * names. Cells of another shape than a payload has, which only an in-process caller can hand over, are refused the
     * same way.</li>
     * </ol>
     * The accepted grid becomes this menu's grid, with the recipe it matched as its hint and the preview already
     * resolved, so the screen's preview of it costs no second lookup. It goes out with the next push as
     * {@link TerminalCraftViewPayload#lay()} under a new number, together with {@code foldOut}. Nothing moves and
     * nothing is requested or ordered: the buffer, the stock and the aisle's requests stay exactly as they were.
     *
     * @param cells   per cell, row-major, the candidates its ingredient accepts in the recipe's order
     * @param hint    the recipe the viewer showed; a hint only
     * @param foldOut whether the player asked for the panel to fold out
     * @return {@link TerminalCraftResult#LAID} naming the result, or the refusal
     */
    public TerminalCraftOutcome layRecipe(Player player, List<? extends List<ItemKey>> cells,
            Optional<ResourceLocation> hint, boolean foldOut) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(hint, "hint");
        if (contentHolder == null || contentHolder.isRemoved())
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        Level level = contentHolder.getLevel();
        if (level == null || level.isClientSide || player.containerMenu != this || !contentHolder.canPlayerUse(player))
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        if (contentHolder.hasOpenCraftOrder())
            return TerminalCraftOutcome.of(TerminalCraftResult.ALREADY_RUNNING);
        // A shape the payload decoder refuses never gets this far from the network; a caller in-process is held to it
        // all the same, as a lay that matches nothing.
        if (!TerminalCraftLayPayload.wellFormed(cells))
            return TerminalCraftOutcome.of(TerminalCraftResult.NOT_LAID);
        if (cells.stream().allMatch(List::isEmpty))
            return TerminalCraftOutcome.of(TerminalCraftResult.GRID_EMPTY);
        CraftGrid<ItemKey> picked = IngredientPicker.pick(cells,
                TerminalCraftLay.supply(bufferHandler, bufferSlots, contentHolder.controller()));
        TerminalCrafting.Preview preview = TerminalCrafting.preview(level, player, picked, hint);
        craftPreviewLookups++;
        if (preview.recipe().isEmpty())
            return TerminalCraftOutcome.of(TerminalCraftResult.NOT_LAID);
        craftCells = picked;
        craftHint = preview.recipe();
        craftPreview = preview;
        craftLay = new TerminalCraftViewPayload.Lay(++craftLays, picked, preview.recipe(), foldOut);
        markDirty(); // the laid grid goes out with the next tick instead of waiting for the interval
        return new TerminalCraftOutcome(TerminalCraftResult.LAID, 0, ItemKey.fromStack(preview.result()));
    }

    /** The server's reading of the grid as last resolved; {@link TerminalCrafting.Preview#NONE} for an empty grid. */
    public TerminalCrafting.Preview craftPreview() {
        return craftPreview;
    }

    /** The grid this menu last received from its screen; empty until the panel was used. */
    public CraftGrid<ItemKey> craftCells() {
        return craftCells;
    }

    /**
     * Server: the crafting panel's view exactly as the next push would build it, without sending it (the GameTests
     * read it here, because a test player has no connection to receive it on). It reads only what
     * {@link #previewCraft} resolved, so asking for it never looks a recipe up.
     */
    public TerminalCraftViewPayload craftView() {
        if (contentHolder == null || contentHolder.isRemoved())
            return TerminalCraftViewPayload.none(containerId, craftLay);
        return craftView(contentHolder.producibleKeys());
    }

    /**
     * How often this menu has looked a recipe up for its preview (the GameTests: at most one per change), a laid
     * recipe's check included.
     */
    public int craftPreviewLookups() {
        return craftPreviewLookups;
    }

    /** The last recipe a recipe viewer laid into this menu's grid; {@link TerminalCraftViewPayload.Lay#NONE} before. */
    public TerminalCraftViewPayload.Lay craftLay() {
        return craftLay;
    }

    /** Resolves the preview for the current grid and hint, and pushes it with the next tick. */
    private void resolveCraftPreview(Player player) {
        Level level = contentHolder == null ? null : contentHolder.getLevel();
        if (level == null || craftCells.isEmpty()) {
            craftPreview = TerminalCrafting.Preview.NONE;
        } else {
            craftPreview = TerminalCrafting.preview(level, player, craftCells, craftHint);
            craftPreviewLookups++;
        }
        markDirty();
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

    /**
     * Server: whether this menu may take another recipe viewer's lay in the current tick, counting it
     * ({@value #MAX_LAYS_PER_TICK} per tick, {@link #submitCraftLay}). Without a level there is no tick to count in;
     * such a lay is refused by {@link #layRecipe} anyway.
     */
    private boolean takeLayBudget() {
        long now = gameTime();
        if (now != layBudgetGameTime) {
            layBudgetGameTime = now;
            laysThisTick = 0;
        }
        return ++laysThisTick <= MAX_LAYS_PER_TICK;
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
        // The order this player chose and what they ask for most often (M24, issue #17, ADR-037). It goes out before
        // the stock, so the very first list a screen draws is already in the right order, and it is compared by value:
        // a player who requests nothing costs no packet, exactly like an aisle whose stock does not change.
        sendUsage(target);

        List<StockCount<ItemKey>> current = stockCounts();
        // An item type that only fell outside the reported window (maxTerminalStockEntries) has not left the index:
        // reporting it as gone would delete a stocked item from the screen, which could then not be requested.
        // The producible key set is resolved once for the whole push and closed over, instead of rebuilding every
        // production station's pattern list again for each item type that dropped out of the window.
        Set<ItemKey> producible = contentHolder.producibleKeys();
        List<StockCount<ItemKey>> changes = stockDiff.commit(current,
                key -> contentHolder.holdsInStock(key, producible));
        // The fluid half (M31, issue #21): the fluid cells and this terminal's fill lines, compared by value and sent
        // whole with the first stock page whenever they changed. A warehouse without fluid compares NONE with NONE.
        TerminalFluids fluids = contentHolder.fluidSnapshot();
        Optional<TerminalFluids> fluidNews = reset || !fluids.equals(lastFluids) ? Optional.of(fluids)
                : Optional.empty();
        lastFluids = fluids;
        if (reset || !changes.isEmpty() || fluidNews.isPresent())
            sendStock(target, reset, changes, fluidNews);

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

        // How far the clipboard order has got (M23, issue #19). Compared by value like the two above, so a terminal
        // without a list — and one whose list did not move — costs no packet; and with it, whenever the order is
        // waiting for an answer, the question itself, because a player who opens the screen later has to see it.
        TerminalListState list = contentHolder.listState();
        if (reset || !list.equals(lastList)) {
            lastList = list;
            PacketDistributor.sendToPlayer(target, new TerminalListPayload(containerId, list));
        }
        // Whenever the question is a new one, on a full sync, and whenever the player asked for it again
        // (resendListQuestion): the question lives on the server, so the screen can never be the only place it exists
        // (M23 review fix). "A new one" and not "the order started asking": another player's answer can move the order
        // on to the next portion's question within one tick, and a screen still showing the first one would otherwise
        // never hear of the second (issue #24 review fix).
        RequestConfirmation<ItemKey> listQuestion = list.asking() ? contentHolder.listQuestion().orElse(null) : null;
        if (listQuestion != null && (reset || listQuestionPending || listQuestion != lastListQuestion))
            PacketDistributor.sendToPlayer(target, new TerminalConfirmPayload(containerId, listQuestion,
                    RequestScope.LIST));
        listQuestionPending = false;
        lastListQuestion = listQuestion;

        // What the crafting panel's grid makes and what the aisle says about each of its items (M32, issue #24).
        // Compared by value like everything above; the recipe was resolved when the grid changed, never here.
        TerminalCraftViewPayload view = craftView(producible);
        if (!view.equals(lastCraftView)) {
            lastCraftView = view;
            PacketDistributor.sendToPlayer(target, view);
        }

        // The craft order (issue #24, slice 2): how far it has got and the grid it was started for, which every screen
        // at this terminal shows and locks while the order is open. Compared by value like the clipboard order's, and
        // its question goes out the same way, with the scope that sends the answer back to the craft order.
        TerminalCraftOrderState craftOrder = contentHolder.craftOrderState();
        if (reset || !craftOrder.equals(lastCraftOrder)) {
            lastCraftOrder = craftOrder;
            PacketDistributor.sendToPlayer(target, new TerminalCraftOrderStatePayload(containerId, craftOrder));
        }
        RequestConfirmation<ItemKey> craftQuestion = craftOrder.asking()
                ? contentHolder.craftQuestion().orElse(null) : null;
        if (craftQuestion != null && (reset || craftQuestionPending || craftQuestion != lastCraftQuestion))
            PacketDistributor.sendToPlayer(target, new TerminalConfirmPayload(containerId, craftQuestion,
                    RequestScope.CRAFT));
        craftQuestionPending = false;
        lastCraftQuestion = craftQuestion;

        // The package doors a click may deliver to (M27, issue #18, ADR-065 D9): the warehouse's shared build, which
        // this push is the only thing that ever asks for, with each door's counts read live. Compared by value like
        // everything above, and sent on every full sync, so a screen always knows whether it has a choice to offer.
        TerminalDoors doors = contentHolder.doorRows();
        if (reset || !doors.equals(lastDoors)) {
            lastDoors = doors;
            PacketDistributor.sendToPlayer(target, new TerminalDoorsPayload(containerId, doors));
        }
    }

    /**
     * Server: the package doors this menu last pushed to its screen (M27, issue #18), or {@code null} before the first
     * push. For the GameTests: a fake player's connection sends nothing, so this is where a test reads what the screen
     * was told.
     */
    @Nullable
    public TerminalDoors lastDoorRows() {
        return lastDoors;
    }

    /**
     * The crafting panel's view of the current grid (M32, issue #24): the grid itself, so the panel can tell it from
     * a view of an earlier one, the resolved recipe, and per distinct grid item what a click could be granted of it,
     * whether the aisle can make it and whether the safety stop holds it.
     * <p>
     * At most {@value CraftGrid#SIZE} lookups of a few map reads each, with the producible keys the push already
     * resolved, and nothing at all while the grid is empty — so the panel adds no scan of anything. Without an aisle
     * every item reads as unavailable and the view says so; crafting from the buffer needs no aisle.
     */
    private TerminalCraftViewPayload craftView(Set<ItemKey> producible) {
        if (craftCells.isEmpty())
            return TerminalCraftViewPayload.none(containerId, craftLay);
        Optional<WarehouseControllerBlockEntity> controller = contentHolder.controller();
        List<TerminalCraftViewPayload.KeyView> keys = new ArrayList<>(CraftGrid.SIZE);
        for (ItemKey key : craftCells.perCraft().keySet()) {
            keys.add(controller.map(found -> new TerminalCraftViewPayload.KeyView(key,
                    found.availableTo(StockAccess.PLAYER, key), producible.contains(key),
                    found.stockRulePause(key).isPresent()))
                    .orElseGet(() -> new TerminalCraftViewPayload.KeyView(key, 0L, false, false)));
        }
        return new TerminalCraftViewPayload(containerId, craftCells, craftPreview.recipe(), craftPreview.result(),
                craftPreview.locked(), controller.isEmpty(), keys, craftLay);
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
                    entry.producibleAmount(), entry.rule(), entry.ruleReserved(), entry.ruleMaximum()));
        return counts;
    }

    /**
     * Sends this player's chosen order and request counts, if either changed since the last push (M24, issue #17).
     * <p>
     * The counts are read from the player, not from the terminal: they are the player's own and the same at every
     * terminal of the world ({@link TerminalPreferences}). Reading them is a map walk bounded by
     * {@code maxTerminalUsageEntries}, and it happens at most once per tick like every other part of a push, so this
     * adds no per-tick scan of anything.
     */
    private void sendUsage(ServerPlayer target) {
        TerminalPreferences preferences = TerminalPreferences.of(target);
        TerminalUsagePayload usage = TerminalUsagePayload.of(containerId, preferences.sort(), preferences.counts());
        if (usage.equals(lastUsage))
            return;
        lastUsage = usage;
        PacketDistributor.sendToPlayer(target, usage);
    }

    /**
     * Sends {@code changes} in pages of at most {@link TerminalStockPayload#MAX_ENTRIES}; only the first page resets,
     * and only the first carries the fluid half.
     */
    private void sendStock(ServerPlayer target, boolean reset, List<StockCount<ItemKey>> changes,
            Optional<TerminalFluids> fluids) {
        if (changes.isEmpty()) {
            PacketDistributor.sendToPlayer(target, new TerminalStockPayload(containerId, reset, List.of(), fluids));
            return;
        }
        boolean first = true;
        for (int from = 0; from < changes.size(); from += TerminalStockPayload.MAX_ENTRIES) {
            List<StockCount<ItemKey>> page = changes.subList(from,
                    Math.min(changes.size(), from + TerminalStockPayload.MAX_ENTRIES));
            PacketDistributor.sendToPlayer(target, new TerminalStockPayload(containerId, reset && first, page,
                    first ? fluids : Optional.empty()));
            first = false;
        }
    }

    /**
     * Server: the fluid half this menu last pushed to its screen (M31), or {@code null} before the first push. For the
     * GameTests: a fake player's connection sends nothing, so this is where a test reads what the screen was told.
     */
    @Nullable
    public TerminalFluids lastFluidRows() {
        return lastFluids;
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

    /**
     * The list slot: one clipboard, in and out by hand (M23, issue #19).
     * <p>
     * What may go in is decided by the handler on the block entity
     * ({@code WarehouseTerminalBlockEntity.ListSlotHandler#isItemValid}), which {@link SlotItemHandler} asks, so the
     * rule holds for the drag, the shift-click and every other path at once — and on the server, which is the only
     * side that decides anything here.
     */
    private static final class TerminalListSlot extends SlotItemHandler {
        private TerminalListSlot(IItemHandler handler, int x, int y) {
            super(handler, 0, x, y);
        }
    }
}
