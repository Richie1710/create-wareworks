package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.item.ExtractOnlyItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.job.ReservationView;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Block entity of the warehouse terminal ({@code docs/warehouse-system.md} §3.4, ADR-018): the player-facing request
 * station of an aisle.
 * <p>
 * It is an <b>output-style member</b> ({@code LocationKind.OUTPUT}, from
 * {@link WarehouseDeliveryStationBlockEntity}): it sits at a rack position with its <b>intake port</b> towards the
 * aisle and its screen on one of the other faces ({@code docs/warehouse-system.md} §3.4.3, ADR-022), owns an
 * extract-only buffer and the crane physically delivers into it. Nothing is teleported — a request from the screen
 * creates the same {@code RETRIEVE} job as a redstone request at a warehouse output, goes through the same
 * {@code RequestQueue} and {@code ReservationLedger} with the same clamping and rejection reasons, and is
 * indistinguishable downstream.
 * <p>
 * <b>Automation.</b> The item capability is an {@link ExtractOnlyItemHandler}, exactly as on the warehouse output:
 * funnels, chutes, hoppers and Create mechanical arms ({@link DeliveryStationArmPoint}, take only; M12) pull delivered
 * items out, nothing can be pushed in.
 * <p>
 * <b>Server-side API for the screen</b> (the GUI itself comes in the next task). All of it runs on the server and is
 * called on demand, never per tick:
 * <ul>
 * <li>{@link #stockSnapshot()} — the aisle's stock as a bounded, ordered list of {@link TerminalStockEntry};</li>
 * <li>{@link #bufferedItems()} (inherited) — what already arrived here;</li>
 * <li>{@link #status()} — controller link, totals, this terminal's requests and the crane's state;</li>
 * <li>{@link #requestFromTerminal(Player, ItemKey, int)} — the request itself.</li>
 * </ul>
 * A request is validated before it reaches the controller: the player must be allowed to use the block (the vanilla
 * container rule, {@link #canPlayerUse}), the amount must be positive, and the item must be one the <b>server's own</b>
 * stock index holds — a client-sent item is never trusted, only matched against that index. The controller then clamps
 * the amount to the stock that is not promised yet, bounds this terminal's open request for that item by
 * {@code maxTerminalRequestAmount} (repeated clicks are merged into it, {@code warehouse-system.md} §7.2) and applies
 * the queue caps.
 */
public class WarehouseTerminalBlockEntity extends WarehouseDeliveryStationBlockEntity implements IInteractionChecker {
    public WarehouseTerminalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, WareworksConfig.terminalBufferSlots());
    }

    /** Registers the extract-only view for every side (listed in {@code WareworksCapabilities}). */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.get(),
                (be, side) -> be.externalHandler());
    }

    /**
     * No behaviours: unlike the warehouse output the terminal has no filter slot and no value box, because it is
     * operated through its screen. Like every station it has no ticker either.
     */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    // --- intake port and screen ------------------------------------------------------------------------------------

    /**
     * The world direction of the screen (and of the take-out tray below it), resolved from the intake port
     * ({@link #facing()}) and the relative {@link WarehouseTerminalBlock#DISPLAY} value. Never equal to the port
     * ({@link TerminalDisplaySide}), so the block always has a readable face.
     */
    public Direction displaySide() {
        BlockState state = getBlockState();
        Direction intake = state.getOptionalValue(WarehouseTerminalBlock.FACING).orElse(Direction.NORTH);
        return state.getOptionalValue(WarehouseTerminalBlock.DISPLAY).orElse(TerminalDisplaySide.BACK).of(intake);
    }

    /**
     * Server: moves the intake port onto the aisle side, keeping the screen where the player put it (the relative
     * {@link WarehouseTerminalBlock#DISPLAY} value is recomputed for the new port, so the screen does not travel with
     * it).
     * <p>
     * <b>Alignment itself is the plain station rule</b> ({@link WarehouseMember#isAlignedWith}): the port looks into
     * the aisle. ADR-022 described it as two-sided ("and the screen is not on the aisle side"), but the screen can
     * never sit on the port at all ({@link TerminalDisplaySide}), so that second half was a conjunct that could never
     * fail and is gone (M10 review fix). The screen-on-the-aisle case is produced <i>here</i> instead, by the refusal
     * below to move the port onto the screen's face, which leaves the port wrong and therefore the terminal
     * misaligned.
     * <p>
     * Three reasons not to write, checked in this order because each is cheaper than the next:
     * <ol>
     * <li>the port already looks into the aisle — the settled case, and the reason a quiet terminal costs nothing;</li>
     * <li>the screen occupies the aisle side: the port would need that face, so the terminal is reported misaligned
     * and the wrench is the fix the goggle hint names;</li>
     * <li>another aisle owns this position's block state ({@link WarehouseRegistry#ownsMemberState}). Two
     * <b>parallel</b> aisles two blocks apart share the rack plane between them
     * ({@code docs/warehouse-system.md} §4, §8) and want <b>opposite</b> ports there; without this guard both
     * controllers would rewrite the block on alternating ticks for ever (M10 review finding). A contested terminal
     * keeps the port of its owning aisle and is simply reported misaligned in the other one, exactly like every other
     * member on a shared plane.</li>
     * </ol>
     * Called from the controller's membership probe, i.e. only while membership is dirty, never per tick.
     * <p>
     * A terminal saved before M10 has no {@code DISPLAY} value and reads back as {@link TerminalDisplaySide#BACK}: its
     * port keeps the direction it always had (the property's meaning is unchanged) and its screen lands on the face
     * opposite the aisle, where its player already stood. Nothing is migrated (ADR-022).
     */
    @Override
    public void alignToAisle(BlockPos controller, AisleLayout layout, Side side) {
        if (!(level instanceof ServerLevel) || isRemoved() || isVirtual())
            return;
        BlockState state = getBlockState();
        if (!state.hasProperty(WarehouseTerminalBlock.FACING) || !state.hasProperty(WarehouseTerminalBlock.DISPLAY))
            return;
        Direction towardsAisle = layout.sideDirection(side).getOpposite();
        if (state.getValue(WarehouseTerminalBlock.FACING) == towardsAisle)
            return; // the port already looks into the aisle
        Direction screen = displaySide();
        if (screen == towardsAisle)
            return; // the screen is in the way: misaligned, and only the player's wrench can decide where it goes
        if (!WarehouseRegistry.ownsMemberState(level, worldPosition, controller))
            return; // a neighbouring aisle owns this port: misaligned here rather than fought over on every tick
        level.setBlock(worldPosition, state.setValue(WarehouseTerminalBlock.FACING, towardsAisle)
                .setValue(WarehouseTerminalBlock.DISPLAY,
                        TerminalDisplaySide.of(towardsAisle, screen).orElse(TerminalDisplaySide.BACK)),
                Block.UPDATE_CLIENTS);
    }

    /**
     * The base notifies the registry when the <b>port</b> turns; for a terminal the <b>screen</b> decides alignment as
     * well ({@link #isAlignedWith}), so a wrench that only moved the screen must re-probe too — otherwise a terminal
     * turned out of the crane's way would stay "misaligned" until something else marked its position dirty.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction screenBefore = displaySide();
        super.setBlockState(state);
        if (displaySide() != screenBefore && level instanceof ServerLevel)
            notifyMembership();
    }

    // --- screen API ----------------------------------------------------------------------------------------------

    /**
     * Server: what the aisle has in stock, ordered by {@link TerminalStockEntry#ORDER} and cut off at
     * {@code maxTerminalStockEntries} entries. Empty without an aisle.
     * <p>
     * This is the only item list a screen may offer, and {@link #requestFromTerminal} accepts nothing that is not in the
     * index behind it.
     * <p>
     * <b>A cut-off list is not a shrunken warehouse.</b> The cap bounds what a screen is <i>told</i>, never what the
     * aisle holds, so an item type outside the window must not be reported as gone — {@link #holdsInStock} is what the
     * menu asks about such a key, and {@code TerminalStockEntry.ORDER} leads with the stored amount so that the window
     * does not reshuffle while the crane promises and delivers.
     * <p>
     * <b>Cost:</b> one pass over the stock index's item types and one over the open requests, whose promised amounts are
     * collected once ({@code WarehouseControllerBlockEntity#remainingRequestedByKey}) instead of scanning the queue
     * again for every key; each item type then costs two map lookups. It is called when a screen opens or refreshes
     * (every {@link WarehouseTerminalMenu#REFRESH_INTERVAL_TICKS} ticks per open screen), never per tick.
     */
    public List<TerminalStockEntry> stockSnapshot() {
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return List.of();
        WarehouseControllerBlockEntity controller = found.get();
        StockView<ItemKey, RackPosition> stock = controller.stockIndex();
        ReservationView<ItemKey, RackPosition> reservations = controller.reservations();
        Map<ItemKey, Long> promised = controller.remainingRequestedByKey();
        // One pass over the aisle's patterns for both questions the screen asks: which keys are producible at all, and
        // how many of each could be made right now (M11, ADR-024). The second number is the server's own, because a
        // client knows neither the patterns nor what their ingredients are already promised to.
        Map<ItemKey, Long> producible = controller.producibleAmounts();
        List<TerminalStockEntry> entries = new ArrayList<>(stock.distinctKeys());
        for (ItemKey key : stock.keys()) {
            long total = stock.count(key);
            if (total > 0)
                entries.add(new TerminalStockEntry(key, total,
                        reservations.availableStock(key, total, promised.getOrDefault(key, 0L)),
                        producible.containsKey(key), producible.getOrDefault(key, 0L)));
        }
        // Items the aisle can make but does not hold. They are offered at zero stock on purpose (M11, ADR-024):
        // ordering one starts a production order, which is the whole point of a production station.
        List<TerminalStockEntry> offers = new ArrayList<>(producible.size());
        for (Map.Entry<ItemKey, Long> entry : producible.entrySet()) {
            if (stock.count(entry.getKey()) <= 0)
                offers.add(new TerminalStockEntry(entry.getKey(), 0L, 0L, true, entry.getValue()));
        }
        return window(entries, offers);
    }

    /**
     * The reported window: the stocked entries in {@link TerminalStockEntry#ORDER}, cut to
     * {@code maxTerminalStockEntries}, with the producible-only offers <b>reserved from that cut</b>.
     * <p>
     * An offer has no stock, so {@code ORDER} — which leads with the stored amount, for the reasons given there —
     * sorts it behind every stocked entry, and a plain cut would drop the offers first. On an aisle with more item
     * types than the cap the terminal would then silently offer nothing it can make: no tinted cell, no "Can be
     * produced here", nothing to ctrl-click, and no message saying why (M11 review fix). The offers are bounded on
     * their own by the aisle's patterns and never take more than half the window, so many patterns cannot push the
     * stock off the screen either.
     */
    private static List<TerminalStockEntry> window(List<TerminalStockEntry> stocked, List<TerminalStockEntry> offers) {
        int max = Math.max(1, WareworksConfig.maxTerminalStockEntries());
        stocked.sort(TerminalStockEntry.ORDER);
        offers.sort(TerminalStockEntry.ORDER);
        int offerRoom = Math.min(offers.size(), Math.max(1, max / 2));
        int stockedRoom = Math.max(0, max - offerRoom);
        List<TerminalStockEntry> reported = new ArrayList<>(Math.min(max, stocked.size() + offers.size()));
        reported.addAll(stocked.subList(0, Math.min(stocked.size(), stockedRoom)));
        reported.addAll(offers.subList(0, offerRoom));
        reported.sort(TerminalStockEntry.ORDER);
        return List.copyOf(reported);
    }

    /**
     * Server: whether {@code key} is still worth a row on a screen — the aisle holds it, or a production station can
     * make it (M11, ADR-024) — whether or not it is inside the last {@link #stockSnapshot()}. The menu asks this
     * before it tells a screen that an item type is gone.
     */
    public boolean holdsInStock(ItemKey key) {
        return holdsInStock(key, producibleKeys());
    }

    /**
     * The item keys this terminal's aisle can produce; empty without an aisle. Resolved <b>once per push</b> and
     * handed to {@link #holdsInStock(ItemKey, Set)}, because building it walks every production station's block
     * entity and rebuilds its pattern list — work whose answer is the same for every key of that push.
     */
    public Set<ItemKey> producibleKeys() {
        return controller().map(WarehouseControllerBlockEntity::producibleKeys).orElse(Set.of());
    }

    /** {@link #holdsInStock(ItemKey)} with the aisle's producible keys already resolved ({@link #producibleKeys()}). */
    public boolean holdsInStock(ItemKey key, Set<ItemKey> producible) {
        if (key == null)
            return false;
        return producible.contains(key) || controller().map(controller -> controller.countOf(key) > 0).orElse(false);
    }

    /**
     * Server: the production orders of this terminal's <b>aisle</b>, as the screen shows them
     * ({@code docs/warehouse-system.md} §3.4.2): open ones and the recently finished ones the controller still keeps,
     * newest last, cut to {@value ProductionScreenState#MAX_ORDERS} entries.
     * <p>
     * The scope is the aisle, not this terminal, because that is what a player ordering at a terminal is waiting for:
     * an order is started by a request <i>here</i> but runs at a production station somewhere else in the aisle, and
     * there is no per-player ownership to scope it by. The <b>newest</b> are kept when there are more than fit, since
     * those are the ones a player just placed; the production station's own screen keeps the oldest, which is the
     * right choice there (the station's backlog).
     */
    public List<ProductionScreenState.OrderView> productionOrderViews() {
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return List.of();
        List<ProductionOrder<ItemKey, RackPosition>> orders = found.get().productionOrders();
        int from = Math.max(0, orders.size() - ProductionScreenState.MAX_ORDERS);
        List<ProductionScreenState.OrderView> views = new ArrayList<>(orders.size() - from);
        for (ProductionOrder<ItemKey, RackPosition> order : orders.subList(from, orders.size()))
            views.add(new ProductionScreenState.OrderView(order.id(), order.state(), order.result(),
                    order.resultAmount(), order.produced(), order.outstandingIngredients(),
                    order.deliveredIngredients()));
        return List.copyOf(views);
    }

    /**
     * Server: gives up on production order {@code orderId} on behalf of {@code player}, who has this terminal's screen
     * open ({@code docs/warehouse-system.md} §3.4.2).
     * <p>
     * Everything is re-validated here, because the payload carries nothing but an id: the player must still be allowed
     * to use this block ({@link #canPlayerUse}), this terminal must belong to a loaded aisle, and the order must be an
     * <b>open order of that aisle</b> ({@code WarehouseControllerBlockEntity#productionOrder}). A crafted id therefore
     * cannot reach another aisle's orders, and a finished one changes nothing.
     * <p>
     * Cancelling moves no item. It releases what the order still promised and hands its backing request the unproduced
     * amount back; ingredients the crane already delivered stay where they are, which is the boundary §3.5 documents.
     *
     * @return whether an order was cancelled
     */
    public boolean cancelProductionOrder(Player player, UUID orderId) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved() || orderId == null)
            return false;
        if (!canPlayerUse(player))
            return false;
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return false;
        WarehouseControllerBlockEntity controller = found.get();
        if (controller.productionOrder(orderId).filter(ProductionOrder::isOpen).isEmpty())
            return false;
        return controller.cancelProductionOrder(orderId).isPresent();
    }

    /** Server: the aisle's controller and crane state for the screen; {@link TerminalStatus#NONE} without an aisle. */
    public TerminalStatus status() {
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return TerminalStatus.NONE;
        WarehouseControllerBlockEntity controller = found.get();
        StockView<ItemKey, RackPosition> stock = controller.stockIndex();
        return new TerminalStatus(true, controller.status(), controller.aisleLetter(), stock.distinctKeys(),
                stock.totalItems(), controller.openRequestCount(), controller.requestsFor(worldPosition).size(),
                controller.requestedFor(worldPosition), controller.deliveredFor(worldPosition),
                controller.linkedDockEntity().map(StackerCraneBlockEntity::goggleInfo));
    }

    /**
     * Opens the terminal's screen for {@code player} (server). The extra data carries the block position, so the client
     * menu finds this block entity, and the buffer slot count, so both sides build the same slots even if the client's
     * block entity is missing.
     *
     * @return whether a screen was opened
     */
    public boolean openScreen(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved())
            return false;
        return player.openMenu(new SimpleMenuProvider(
                (id, inventory, owner) -> WarehouseTerminalMenu.create(id, inventory, this),
                getBlockState().getBlock().getName()), buffer -> {
                    buffer.writeBlockPos(worldPosition);
                    buffer.writeVarInt(bufferSlots());
                }).isPresent();
    }

    /**
     * The buffer behind the screen's slots. The menu's slots only ever <b>take</b> items out of it; insertion is refused
     * there exactly as it is for funnels and chutes ({@link #externalHandler()}).
     */
    public IItemHandler menuBuffer() {
        return buffer;
    }

    /**
     * Whether {@code player} may use this terminal, by the same rule vanilla containers use
     * ({@code Container#stillValidBlockEntity}): the block entity must still be the one at its position and the player
     * must be within their block interaction range plus four blocks of it. Never throws.
     * <p>
     * This is also what keeps an open screen honest: Create's {@code MenuBase} asks it every tick, so the menu closes by
     * itself when the block is broken, replaced or unloaded, or when the player walks away.
     */
    @Override
    public boolean canPlayerUse(Player player) {
        Objects.requireNonNull(player, "player");
        return !isRemoved() && Container.stillValidBlockEntity(this, player);
    }

    /**
     * Server: requests up to {@code amount} items of {@code key} to be delivered into this terminal's buffer by the
     * crane. The refusal of a rejected request is remembered like an output's ({@code lastRejection()}, saved and shown
     * in goggles) and cleared by the next accepted one — <b>except</b> the two refusals that are about the asking
     * player rather than about this terminal ({@link RequestRejection#OUT_OF_REACH},
     * {@link RequestRejection#INVALID_AMOUNT}): they reach that player through the result, and storing them would
     * show one player's mistake in every goggle wearer's tooltip ("you are too far away") and save it to disk.
     * <p>
     * Validation, in this order:
     * <ol>
     * <li>server side and not removed, otherwise {@link RequestRejection#NO_CONTROLLER};</li>
     * <li>{@link #canPlayerUse(Player)}, otherwise {@link RequestRejection#OUT_OF_REACH};</li>
     * <li>{@code amount >= 1}, otherwise {@link RequestRejection#INVALID_AMOUNT}. The amount itself is <b>not</b>
     * clamped here: {@code maxTerminalRequestAmount} is handed to the controller as the bound on the <b>merged</b>
     * remaining amount of this terminal's open request for that item, because repeated clicks grow that one request
     * ({@code warehouse-system.md} §7.2, ADR-020);</li>
     * <li>an aisle whose controller is loaded and where this terminal is an aligned output-style member, otherwise
     * {@link RequestRejection#NO_CONTROLLER} (this also covers a misaligned terminal and one outside any aisle);</li>
     * <li>{@code key} is held by that controller's stock index, otherwise {@link RequestRejection#NOT_IN_STOCK}. The
     * client's item is only ever <b>matched</b> against the server's own index, never trusted as a description of what
     * exists.</li>
     * </ol>
     * The controller then clamps the amount to the stock that is not promised to another request and can still refuse
     * with {@link RequestRejection#NOT_IN_STOCK}, {@link RequestRejection#OUTPUT_FULL},
     * {@link RequestRejection#QUEUE_FULL} or {@link RequestRejection#REQUEST_FULL} (the open request here already waits
     * for {@code maxTerminalRequestAmount}; delivered items free room again) — the same set a redstone request at a
     * warehouse output can get, which bounds its own merged request by its filter amount.
     *
     * @return the accepted request with the amount <b>this call</b> granted ({@code RequestResult#granted()}) and what
     *         the request waits for now ({@code pending()}), or the reason it was refused
     */
    public RequestResult requestFromTerminal(Player player, ItemKey key, int amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        if (level == null || level.isClientSide || isRemoved())
            return RequestResult.rejected(RequestRejection.NO_CONTROLLER);
        // Player-scoped refusals are answered, not remembered: they say nothing about this terminal (see above).
        if (!canPlayerUse(player))
            return RequestResult.rejected(RequestRejection.OUT_OF_REACH);
        if (amount < 1)
            return RequestResult.rejected(RequestRejection.INVALID_AMOUNT);
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return rememberRejection(RequestResult.rejected(RequestRejection.NO_CONTROLLER));
        WarehouseControllerBlockEntity controller = found.get();
        // Resolve against the server's own state: a key the aisle neither holds nor can produce is refused before it
        // reaches the queue, whatever the client claimed. ItemKey compares item and components, so a matching key is
        // the indexed one. A producible key is allowed through here and the controller decides how much of it can
        // really be promised (M11, ADR-024).
        if (controller.countOf(key) <= 0 && !controller.producibleKeys().contains(key))
            return rememberRejection(RequestResult.rejected(RequestRejection.NOT_IN_STOCK));
        // The cap goes to the controller instead of clamping here, because a repeated request for the same item is
        // merged into the open one and the cap must bound the merged total, not this click (§7.2, ADR-020).
        return rememberRejection(controller.request(worldPosition, key, amount,
                Math.max(1, WareworksConfig.maxTerminalRequestAmount())));
    }

    // --- station -------------------------------------------------------------------------------------------------

    @Override
    protected String goggleHeaderKey() {
        return WareworksLang.GOGGLES_WAREHOUSE_TERMINAL;
    }

    @Override
    protected String misalignedHintKey() {
        return WareworksLang.GOGGLES_TERMINAL_MISALIGNED_HINT;
    }
}
