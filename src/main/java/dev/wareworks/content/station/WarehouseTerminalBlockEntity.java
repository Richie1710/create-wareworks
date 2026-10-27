package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.item.ClipboardList;
import dev.wareworks.content.item.ExtractOnlyItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftFetchQuestion;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.ReservationView;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockLevels;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.stock.StockRules;
import dev.wareworks.core.terminal.ListCredit;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.ListLine;
import dev.wareworks.core.terminal.ListOrder;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.ListPass;
import dev.wareworks.core.terminal.ListPortion;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;

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
 * <li>{@link #requestFromTerminal(Player, ItemKey, int)} — the request itself, delivered here or, since M27, to one of
 * the warehouse's package doors ({@link #requestFromTerminal(Player, ItemKey, int, RequestAcknowledgement, Optional)},
 * with the doors a screen may offer from {@link #doorRows()});</li>
 * <li>{@link #craftFromBuffer} — a player's craft from what was delivered here (M32, issue #24, ADR-059);</li>
 * <li>{@link #fetchCraft} — the crafting panel's Fetch: the grid's ingredients brought here by a <b>craft order</b>,
 * the second list order a terminal can run beside its clipboard's (issue #24, slice 2, ADR-062).</li>
 * </ul>
 * A request is validated before it reaches the controller: the player must be allowed to use the block (the vanilla
 * container rule, {@link #canPlayerUse}), the amount must be positive, and the item must be one the <b>server's own</b>
 * stock index holds — a client-sent item is never trusted, only matched against that index. The controller then clamps
 * the amount to the stock that is not promised yet, bounds this terminal's open request for that item by
 * {@code maxTerminalRequestAmount} (repeated clicks are merged into it, {@code warehouse-system.md} §7.2) and applies
 * the queue caps.
 */
public class WarehouseTerminalBlockEntity extends WarehouseDeliveryStationBlockEntity implements IInteractionChecker {
    /** The one slot of the list handler; the terminal holds exactly one clipboard (M23, issue #19). */
    public static final int LIST_SLOT = 0;
    /** NBT key of the clipboard in the list slot. */
    public static final String LIST_SLOT_TAG = "ListSlot";
    /** NBT key of the bounded list state in client packets (goggles). */
    public static final String LIST_STATE_TAG = "ListState";
    /** NBT key of the bounded craft order numbers in client packets (goggles; issue #24). */
    public static final String CRAFT_STATE_TAG = "CraftState";

    /**
     * The clipboard a list order is read from and ticked off on ({@link #listSlot()}). Its own handler rather than a
     * slot of the station buffer: the buffer is what the crane delivers into and what automation empties, and a
     * clipboard must be neither.
     */
    private final ListSlotHandler listSlot = new ListSlotHandler();
    /** The clipboard order, or {@code null} while there is none. Saved with this block entity. */
    @Nullable
    private ListOrder<ItemKey> listOrder;
    /** Whether the tick marks of the delivered lines still have to be re-asserted after a load (server). */
    private boolean listTicksPending;
    /** The bounded list state: built on the server when goggles are observed, received on the client. */
    private TerminalListState shownList = TerminalListState.NONE;
    /**
     * Why the clipboard order's last portion was refused, while that still holds (issue #24, ADR-062). Transient: it is
     * measured against the warehouse as it is, so a reloaded order finds out again with its next portion.
     */
    private Optional<TerminalPortionRefusal> listRefusal = Optional.empty();
    /**
     * The craft order — the crafting panel's Fetch (issue #24, slice 2, ADR-062) — or {@code null} while there is
     * none. It runs beside {@link #listOrder} and never blocks it or is blocked by it. Saved with this block entity.
     */
    @Nullable
    private TerminalCraftOrder craftOrder;
    /** The craft order's numbers for the goggles: built on the server when observed, received on the client. */
    private TerminalCraftOrderState shownCraft = TerminalCraftOrderState.NONE;
    /** The last {@link #computerStockSnapshot()}, transient (issue #9); {@code null} until a computer asks. */
    @Nullable
    private List<TerminalStockEntry> computerSnapshot;
    /** The game time {@link #computerSnapshot} was built at. */
    private long computerSnapshotTime;

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
    public void alignToAisle(BlockPos controller, BranchLayout layout, Side side) {
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
        // Both halves of what is already spoken for, each collected in one pass: the open requests and the ingredients
        // the open production orders still have to fetch. A row that leaves the second out advertises items a click can
        // never be granted — and since M20 a blocked parent holds its ingredients promised for the whole chain rather
        // than for one crane trip, so the row was wrong for minutes instead of for a trip (M20 review fix).
        Map<ItemKey, Long> promised = controller.remainingRequestedByKey();
        Map<ItemKey, Long> ordered = controller.outstandingIngredientsByKey();
        // What a click may still be granted is measured against both; the levels below are given the requests alone,
        // because stockLevelsOf adds the orders' own promise itself.
        Map<ItemKey, Long> spoken = new HashMap<>(promised);
        ordered.forEach((key, items) -> spoken.merge(key, items, Long::sum));
        // One pass over the aisle's patterns for both questions the screen asks: which keys are producible at all, and
        // how many of each could be made right now (M11, ADR-024). The second number is the server's own, because a
        // client knows neither the patterns nor what their ingredients are already promised to.
        Map<ItemKey, Long> producible = controller.producibleAmounts();
        // The aisle's stock rules (M15, issue #3) and the levels they are judged against. The levels are read at most
        // once per item type and only for an item a rule really governs; the open requests are already collected
        // above, so no key costs a second pass over the queue.
        StockRules<ItemKey> rules = controller.stockRules();
        // The levels a rule is judged against, read at most once per item type and only for an item a rule really
        // governs: the open requests are already collected above, so no key costs a second pass over the queue.
        Map<ItemKey, StockLevels> levelCache = new HashMap<>();
        Function<ItemKey, StockLevels> levels = key -> levelCache.computeIfAbsent(key,
                item -> controller.stockLevelsOf(item, promised.getOrDefault(item, 0L)));
        List<TerminalStockEntry> entries = new ArrayList<>(stock.distinctKeys());
        // The totals walked as entries, not every key looked up again: keys that share a hash code (ADR-064) would make
        // a lookup per key cost the square of their number.
        for (Map.Entry<ItemKey, Long> stored : stock.totals().entrySet()) {
            ItemKey key = stored.getKey();
            long total = stored.getValue();
            if (total > 0)
                entries.add(entry(controller, rules, levels, key, total,
                        reservations.availableStock(key, total, spoken.getOrDefault(key, 0L)),
                        producible.containsKey(key), producible.getOrDefault(key, 0L)));
        }
        // Rows that exist although the aisle holds none of the item, and that would each become unreachable without
        // one: what a production station can make (M11, ADR-024) — ordering it starts a production order — and what a
        // stock rule governs (M15) — a warehouse that is calling for an item it has run out of has to say so where a
        // player looks, and a row that has vanished can never be requested again.
        List<TerminalStockEntry> pinned = new ArrayList<>(producible.size() + rules.governedKeys().size());
        for (Map.Entry<ItemKey, Long> entry : producible.entrySet()) {
            if (stock.count(entry.getKey()) <= 0)
                pinned.add(entry(controller, rules, levels, entry.getKey(), 0L, 0L, true, entry.getValue()));
        }
        for (ItemKey key : rules.governedKeys()) {
            if (stock.count(key) <= 0 && !producible.containsKey(key))
                pinned.add(entry(controller, rules, levels, key, 0L, 0L, false, 0L));
        }
        return window(entries, pinned);
    }

    /**
     * Server: {@link #stockSnapshot()} for computers (issue #9, ADR-074), built at most once per
     * {@link WarehouseTerminalMenu#REFRESH_INTERVAL_TICKS} ticks — the rate of one open screen — however often the
     * computers attached to this terminal ask. The same list instance is returned until it is that old.
     * <p>
     * The cache is transient: plain fields, never saved or synced, and dropped when the block entity is invalidated
     * (removal, chunk unload). Requests never read it; they check the live state themselves.
     */
    public List<TerminalStockEntry> computerStockSnapshot() {
        long now = level == null ? 0L : level.getGameTime();
        if (computerSnapshot == null || now < computerSnapshotTime
                || now - computerSnapshotTime >= WarehouseTerminalMenu.REFRESH_INTERVAL_TICKS) {
            computerSnapshot = stockSnapshot();
            computerSnapshotTime = now;
        }
        return computerSnapshot;
    }

    /**
     * One snapshot entry with what a stock rule says about its item added to it (M15, issue #3).
     * <p>
     * Nothing is read per key that is not O(1): an aisle without stock keepers — and every unruled item of one with
     * them — costs exactly what it cost before M15.
     */
    private static TerminalStockEntry entry(WarehouseControllerBlockEntity controller, StockRules<ItemKey> rules,
            Function<ItemKey, StockLevels> levels, ItemKey key, long total, long available, boolean producible,
            long producibleAmount) {
        Optional<StockRuleStatus> rule = rules.governingStatusOf(key, levels);
        if (rule.isEmpty())
            return new TerminalStockEntry(key, total, available, producible, producibleAmount);
        // Refined by what automatic restocking is doing about the item, so a row can say "being made now" and
        // "paused" as well as what the three numbers say (M15 part 2).
        return new TerminalStockEntry(key, total, available, producible, producibleAmount,
                rule.map(status -> controller.refineStockRuleStatus(key, status)),
                rules.heldBack(key, available),
                rules.ruleFor(key).map(StockRule::maximum).orElse(StockRule.UNSET));
    }

    /**
     * The reported window: the stocked entries in {@link TerminalStockEntry#ORDER}, cut to
     * {@code maxTerminalStockEntries}, with the rows that have no stock of their own — producible offers and ruled
     * items — <b>reserved from that cut</b>.
     * <p>
     * Such a row has no stock, so {@code ORDER} — which leads with the stored amount, for the reasons given there —
     * sorts it behind every stocked entry, and a plain cut would drop exactly those rows first. On an aisle with more
     * item types than the cap the terminal would then silently offer nothing it can make: no tinted cell, no "Can be
     * produced here", nothing to ctrl-click, and no message saying why (M11 review fix) — and a reserve on an item the
     * warehouse has just run out of would delete the row that explains it (M15). Both groups are bounded on their own
     * (by the aisle's patterns and by {@code maxStockRules}) and together never take more than half the window, so
     * neither can push the stock off the screen.
     */
    private static List<TerminalStockEntry> window(List<TerminalStockEntry> stocked, List<TerminalStockEntry> pinned) {
        int max = Math.max(1, WareworksConfig.maxTerminalStockEntries());
        stocked.sort(TerminalStockEntry.ORDER);
        pinned.sort(TerminalStockEntry.ORDER);
        int pinnedRoom = Math.min(pinned.size(), Math.max(1, max / 2));
        int stockedRoom = Math.max(0, max - pinnedRoom);
        List<TerminalStockEntry> reported = new ArrayList<>(Math.min(max, stocked.size() + pinned.size()));
        reported.addAll(stocked.subList(0, Math.min(stocked.size(), stockedRoom)));
        reported.addAll(pinned.subList(0, pinnedRoom));
        reported.sort(TerminalStockEntry.ORDER);
        return List.copyOf(reported);
    }

    /**
     * Server: whether {@code key} is still worth a row on a screen — the aisle holds it, a production station can make
     * it (M11, ADR-024), or a stock rule governs it (M15) — whether or not it is inside the last
     * {@link #stockSnapshot()}. The menu asks this before it tells a screen that an item type is gone.
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

    /**
     * {@link #holdsInStock(ItemKey)} with the aisle's producible keys already resolved ({@link #producibleKeys()}).
     * <p>
     * A key a stock rule governs answers {@code true} at zero stock, for the same reason a producible one does: its
     * row is pinned into the snapshot, so reporting it as gone would delete a row the server keeps sending and leave
     * a reserved or capped item unreachable at the screen (M15).
     */
    public boolean holdsInStock(ItemKey key, Set<ItemKey> producible) {
        if (key == null)
            return false;
        if (producible.contains(key))
            return true;
        return controller().map(controller -> controller.countOf(key) > 0 || controller.stockRules().governsKey(key))
                .orElse(false);
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
     * <p>
     * Every row carries the production plan it belongs to, its depth in it, its station's address and whether it is
     * waiting for an earlier step (M20, issue #4, ADR-032). The controller fills those in
     * ({@code WarehouseControllerBlockEntity#productionOrderViews}), because only it knows the chain and only it can
     * turn a rack position into an address. A cut that falls inside a chain keeps the newer end of it, so the screen
     * shows a plan with fewer steps than it has rather than a plan it cannot name — {@code core.terminal.PlanLines}
     * groups whatever really arrived.
     */
    public List<ProductionScreenState.OrderView> productionOrderViews() {
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return List.of();
        WarehouseControllerBlockEntity controller = found.get();
        List<ProductionOrder<ItemKey, RackPosition>> orders = controller.productionOrders();
        int from = Math.max(0, orders.size() - ProductionScreenState.MAX_ORDERS);
        return controller.productionOrderViews(orders.subList(from, orders.size()));
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
     * <p>
     * <b>This overload accepts every boundary the request crosses</b>, exactly as a ctrl-click does
     * ({@link RequestAcknowledgement#ANY}), so it can never end in a question and keeps the meaning it had before M15
     * part 2. It is for callers with nobody to ask — the dev harness and the GameTests of the request itself. The
     * screen's path is {@link #requestFromTerminal(Player, ItemKey, int, RequestAcknowledgement)}.
     *
     * @return the accepted request with the amount <b>this call</b> granted ({@code RequestResult#granted()}) and what
     *         the request waits for now ({@code pending()}), or the reason it was refused
     */
    public RequestResult requestFromTerminal(Player player, ItemKey key, int amount) {
        return requestFromTerminal(player, key, amount, RequestAcknowledgement.ANY).result()
                .orElseThrow(() -> new IllegalStateException("an acknowledged request is never asked about"));
    }

    /**
     * Server: {@link #requestFromTerminal(Player, ItemKey, int)} with what the player has already agreed to pay for it
     * ({@code docs/warehouse-system.md} §3.6.6, M15 part 2, issue #3).
     * <p>
     * <b>The server decides whether a question is needed</b>, and it decides it here rather than on the screen: a
     * client knows neither the aisle's production patterns nor what their ingredients are promised to, so it could
     * never work out that a click for four planks spends a log a rule protects. The cost is measured
     * ({@code WarehouseControllerBlockEntity#confirmationFor}) and compared with {@code acknowledged}; a cost the
     * player has not accepted leaves this method with <b>nothing requested at all</b> and the question as the answer.
     * <p>
     * <b>A confirmed request is measured again.</b> The acknowledgement carries numbers, not a flag
     * ({@link RequestAcknowledgement}), so a warehouse that moved between the question and the answer — a crane that
     * promised the items, a reserve somebody raised, a pattern somebody rewrote — is asked about a second time instead
     * of being handed an old "yes". Nothing is trusted from the client but the consent itself, and consent is not
     * permission: a reserve never holds anything back from a player anyway, which is why a ctrl-click may send
     * {@link RequestAcknowledgement#ANY} and skip the question the way the design asks.
     * <p>
     * The question is asked <b>after</b> the cheap validations (reach, amount, aisle, item) and before anything is
     * promised, so an out-of-reach or unstocked click still gets its plain refusal rather than a dialog.
     *
     * @param acknowledged what the player accepted, {@link RequestAcknowledgement#NONE} for a plain click
     * @return the resolved request, or the question the terminal asks first ({@link TerminalRequestOutcome})
     */
    public TerminalRequestOutcome requestFromTerminal(Player player, ItemKey key, int amount,
            RequestAcknowledgement acknowledged) {
        return requestFromTerminal(player, key, amount, acknowledged, Optional.empty());
    }

    /**
     * Server: {@link #requestFromTerminal(Player, ItemKey, int, RequestAcknowledgement)} delivered to a <b>package
     * door</b> of this terminal's warehouse instead of here (M27, issue #18, ADR-065, {@code docs/warehouse-system.md}
     * §3.4.6): a requesting warehouse port with a plain Create Packager at its back, which boxes what the crane hands it
     * when the player's own redstone fires it.
     * <p>
     * <b>The door is a position and nothing else.</b> No address is sent, stored, matched or trusted, and the request
     * that is made is an ordinary request with that port as its destination — the very request the port's own redstone
     * makes, with the same clamping, merging, reserves, chains, questions and caps. The crane carries into the port only
     * what the port can take (ADR-065 D5), and the request is credited at the drop into the port's buffer, which is the
     * last point Wareworks sees.
     * <p>
     * Checked in this order, each a refusal of its own:
     * <ol>
     * <li>server side and not removed, otherwise {@link RequestRejection#NO_CONTROLLER};</li>
     * <li>{@link #canPlayerUse(Player)}, otherwise {@link RequestRejection#OUT_OF_REACH};</li>
     * <li>{@code amount >= 1}, otherwise {@link RequestRejection#INVALID_AMOUNT};</li>
     * <li>a loaded controller, otherwise {@link RequestRejection#NO_CONTROLLER};</li>
     * <li><b>the door</b> ({@link PackageDoors#refusal}, the rule the screen's door rows are built by):
     * {@link RequestRejection#NOT_A_DOOR} or {@link RequestRejection#DOOR_IGNORES_REDSTONE};</li>
     * <li>the item in stock or producible, otherwise {@link RequestRejection#NOT_IN_STOCK};</li>
     * <li>the controller's own request, which checks the destination once more (G-dest).</li>
     * </ol>
     * The door comes <b>before</b> the stock, so a click to something that is no door is answered as such and not as a
     * missing item. <b>Nothing about a door is remembered</b> (ADR-065 D6): every refusal of a click that named one is
     * answered and never stored in this terminal's {@code lastRejection}, which its goggles show and its save carries —
     * the choice of door is the player's, not the terminal's — and the door's own {@code lastRejection} belongs to its
     * redstone and is never touched either. A door equal to this terminal's own position is here.
     *
     * @param door the package door to deliver to; empty for here
     * @return the resolved request or the question, echoing the door the click was really measured for
     */
    public TerminalRequestOutcome requestFromTerminal(Player player, ItemKey key, int amount,
            RequestAcknowledgement acknowledged, Optional<BlockPos> door) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(acknowledged, "acknowledged");
        Optional<BlockPos> target = door == null ? Optional.empty()
                : door.filter(pos -> !pos.equals(worldPosition)).map(BlockPos::immutable);
        return requestTo(player, key, amount, acknowledged, target).withDoor(target);
    }

    /**
     * The checks and the request of
     * {@link #requestFromTerminal(Player, ItemKey, int, RequestAcknowledgement, Optional)}, for {@code door} or here.
     */
    private TerminalRequestOutcome requestTo(Player player, ItemKey key, int amount, RequestAcknowledgement acknowledged,
            Optional<BlockPos> door) {
        RequestCheck checked = checkRequest(player, key, amount, door);
        if (checked.refusal().isPresent())
            return TerminalRequestOutcome.of(checked.refusal().get());
        WarehouseControllerBlockEntity controller = checked.controller();
        // One call for the question and the request: the controller measures what this click would cross from the very
        // snapshot it would then start the order against, so what the player is told and what happens cannot differ and
        // a click walks the aisle's patterns once (M15 review fix). It is also the re-check of an answer that was given
        // a moment ago against a warehouse that has moved since.
        // The cap goes to the controller instead of clamping here, because a repeated request for the same item is
        // merged into the open one and the cap must bound the merged total, not this click (§7.2, ADR-020).
        // A terminal request is a player's own: a stock rule's reserve does not hold it back, and the row tells them
        // that they are going below it (M15, issue #3).
        // A door click is the same request with the door as its destination: the same cap on the merged amount, the same
        // reserves and questions. The controller checks the destination itself as well (G-dest), so a door that stopped
        // requesting between the check above and here is still no destination.
        TerminalRequestOutcome outcome = controller.request(door.orElse(worldPosition), key, amount,
                Math.max(1, WareworksConfig.maxTerminalRequestAmount()), StockAccess.PLAYER, acknowledged);
        // What a door's answer says ("that door waits for too many item types", or anything else) is about the door the
        // player chose, and the door's own refusal belongs to its redstone (ADR-065 D6).
        outcome.result().filter(result -> door.isEmpty()).ifPresent(this::rememberRejection);
        // One accepted click counts once towards this player's "most used" order, whatever amount it asked for
        // (M24, issue #17, ADR-037). A question and a refusal count nothing: the question made no request at all, and
        // a mis-click is not a habit. This is the whole of the counting on the click path: it moves no item, it changes
        // nothing about the request it reports, and it cannot throw (TerminalPreferences#countRequest).
        if (outcome.result().filter(RequestResult::isAccepted).isPresent())
            TerminalPreferences.countRequest(player, key);
        return outcome;
    }

    /**
     * Server: an automation request from a computer attached to this terminal (issue #9, ADR-074,
     * {@code docs/computercraft.md}) for up to {@code amount} items of {@code key}, delivered here or to the package
     * door at {@code door}.
     * <p>
     * <b>An ordinary request, and the very one a warehouse output's redstone pulse makes</b>: the checks of a click
     * ({@link #checkRequest}) minus the player's reach, then the controller's request with
     * {@link StockAccess#AUTOMATION} and {@link RequestAcknowledgement#ANY}. So a stock rule's reserve holds items back
     * from a computer as it does from redstone, {@code maxTerminalRequestAmount} bounds the merged amount, the open
     * request caps bound how many a computer can queue, a production chain starts exactly as for a redstone request,
     * and only the crane moves the items. A computer is asked nothing, so the outcome is always a result.
     * <p>
     * Nothing is remembered: no refusal goes into this terminal's {@code lastRejection} (its goggle line is about the
     * players at it, as ADR-065 D6 says for doors), and nothing counts towards anyone's "most used" order. The request
     * itself is saved with the controller's queue like every other.
     *
     * @param door the package door to deliver to; empty, or this terminal's own position, for here
     * @return the accepted request with the amount this call granted, or the reason it was refused
     */
    public RequestResult requestFromComputer(ItemKey key, int amount, Optional<BlockPos> door) {
        Objects.requireNonNull(key, "key");
        Optional<BlockPos> target = door == null ? Optional.empty()
                : door.filter(pos -> !pos.equals(worldPosition)).map(BlockPos::immutable);
        RequestCheck checked = checkRequest(null, key, amount, target);
        if (checked.refusal().isPresent())
            return checked.refusal().get();
        return checked.controller().request(target.orElse(worldPosition), key, amount,
                Math.max(1, WareworksConfig.maxTerminalRequestAmount()), StockAccess.AUTOMATION);
    }

    /**
     * The checks every request at this terminal passes before it reaches the controller, in this order: server side and
     * not removed ({@link RequestRejection#NO_CONTROLLER}); for a player, {@link #canPlayerUse}
     * ({@link RequestRejection#OUT_OF_REACH}); {@code amount >= 1} ({@link RequestRejection#INVALID_AMOUNT}); a loaded
     * controller ({@link RequestRejection#NO_CONTROLLER}); the door ({@link PackageDoors#refusal}); the item held or
     * producible ({@link RequestRejection#NOT_IN_STOCK}).
     * <p>
     * A player's refusals of the aisle and of the item, for a request to here, are remembered ({@link #rememberHere});
     * a computer's ({@code player == null}) never are.
     *
     * @param player the clicking player, or {@code null} for a computer
     */
    private RequestCheck checkRequest(@Nullable Player player, ItemKey key, int amount, Optional<BlockPos> door) {
        if (level == null || level.isClientSide || isRemoved())
            return RequestCheck.refused(RequestResult.rejected(RequestRejection.NO_CONTROLLER));
        // Player-scoped refusals are answered, not remembered: they say nothing about this terminal (see above).
        if (player != null && !canPlayerUse(player))
            return RequestCheck.refused(RequestResult.rejected(RequestRejection.OUT_OF_REACH));
        if (amount < 1)
            return RequestCheck.refused(RequestResult.rejected(RequestRejection.INVALID_AMOUNT));
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return RequestCheck.refused(remembered(player, door,
                    RequestResult.rejected(RequestRejection.NO_CONTROLLER)));
        WarehouseControllerBlockEntity controller = found.get();
        // G-door (ADR-065 D4): the one rule the screen's door rows are built by, before the stock is looked at, and
        // answered rather than remembered (D6).
        if (door.isPresent()) {
            Optional<RequestRejection> notADoor = PackageDoors.refusal(level, controller, door.get());
            if (notADoor.isPresent())
                return RequestCheck.refused(RequestResult.rejected(notADoor.get()));
        }
        // Resolve against the server's own state: a key the aisle neither holds nor can produce is refused before it
        // reaches the queue, whatever the client claimed. ItemKey compares item and components, so a matching key is
        // the indexed one. A producible key is allowed through here and the controller decides how much of it can
        // really be promised (M11, ADR-024).
        if (controller.countOf(key) <= 0 && !controller.producibleKeys().contains(key))
            return RequestCheck.refused(remembered(player, door,
                    RequestResult.rejected(RequestRejection.NOT_IN_STOCK)));
        return new RequestCheck(controller, Optional.empty());
    }

    /** A player's refusal goes through {@link #rememberHere}; a computer's ({@code player == null}) is never stored. */
    private RequestResult remembered(@Nullable Player player, Optional<BlockPos> door, RequestResult result) {
        return player == null ? result : rememberHere(door, result);
    }

    /** What {@link #checkRequest} found: the controller to ask, or the refusal. */
    private record RequestCheck(@Nullable WarehouseControllerBlockEntity controller, Optional<RequestResult> refusal) {
        static RequestCheck refused(RequestResult result) {
            return new RequestCheck(null, Optional.of(result));
        }
    }

    /**
     * Stores the refusal of a click that delivers here, and never one of a click that named a package door (ADR-065
     * D6), then returns {@code result} unchanged.
     */
    private RequestResult rememberHere(Optional<BlockPos> door, RequestResult result) {
        return door.isEmpty() ? rememberRejection(result) : result;
    }

    /**
     * Server: the package doors of this terminal's warehouse a click may deliver to, with what each still waits for
     * (M27, issue #18, ADR-065 D9); {@link TerminalDoors#NONE} without a warehouse.
     * <p>
     * Called by the menu's push only, i.e. only while a player has this terminal's screen open. The rows themselves come
     * from the controller's {@code DoorDirectory}, which every screen of the warehouse shares and which builds at most
     * once per {@value dev.wareworks.content.controller.DoorDirectory#MAX_AGE_TICKS} ticks; the two counts per row are
     * read from the request queue in memory on every call.
     */
    public TerminalDoors doorRows() {
        if (level == null)
            return TerminalDoors.NONE;
        long now = level.getGameTime();
        return controller().map(found -> found.doorDirectory().terminalRows(now)).orElse(TerminalDoors.NONE);
    }

    // --- crafting at the terminal (M32, issue #24, ADR-059) --------------------------------------------------------

    /**
     * Server: crafts the grid {@code player} laid in the crafting panel of {@code menu}, from the items the crane
     * delivered into this terminal's buffer ({@code docs/warehouse-system.md} §3.4.5) — once, or with {@code all} as
     * often as the buffer allows.
     * <p>
     * <b>The player's own hand, and nothing else.</b> A craft runs only for a player who has <b>this</b> terminal's
     * menu open right now and may still use the block ({@link #canPlayerUse}: the block is still here and within their
     * reach), which is exactly the player who could take the same items out of the buffer by hand. Anything else is
     * {@link TerminalCraftResult#OUT_OF_REACH} and moves nothing. This is the only entry into {@link TerminalCrafting},
     * the one class of the mod that runs a recipe (ADR-059), and it is reached only from the menu's payload entry
     * ({@link WarehouseTerminalMenu#submitCraft}): no tick, order, rule or automation path calls it.
     * <p>
     * No aisle is needed: the ingredients are already here, so a misaligned terminal still crafts from its own buffer.
     * Only the buffer slots the menu shows are used, which is all the player's hand can reach.
     */
    public TerminalCraftOutcome craftFromBuffer(Player player, WarehouseTerminalMenu menu, CraftGrid<ItemKey> grid,
            Optional<ResourceLocation> hint, boolean all) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(hint, "hint");
        if (level == null || level.isClientSide || isRemoved())
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        if (menu.terminal() != this || player.containerMenu != menu || !canPlayerUse(player))
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        return TerminalCrafting.craft(level, player, buffer, menu.bufferSlots(), grid, hint, all);
    }

    /**
     * The controller of this terminal's aisle (server); empty while the terminal is misaligned, outside a warehouse or
     * its controller is not loaded. Public on a terminal because its menu reads the crafting panel's per-item numbers
     * from it once per push (M32, issue #24), as the stock keeper's and the home point's screens read theirs.
     */
    @Override
    public Optional<WarehouseControllerBlockEntity> controller() {
        return super.controller();
    }

    // --- the craft order: Fetch at the crafting panel (issue #24, slice 2, ADR-062) --------------------------------

    /** Whether a craft order exists at all, finished ones included. */
    public boolean hasCraftOrder() {
        return craftOrder != null;
    }

    /** Whether a craft order still has work to do: Fetch is then refused, and every screen here shows its recipe. */
    public boolean hasOpenCraftOrder() {
        return craftOrder != null && craftOrder.isOpen();
    }

    /**
     * How far the craft order has got, in the bounded form every screen open at this terminal is given — including
     * the grid it was started for, which those screens show and lock while the order is open.
     */
    public TerminalCraftOrderState craftOrderState() {
        TerminalCraftOrder craft = craftOrder;
        return craft == null ? TerminalCraftOrderState.NONE : craft.state();
    }

    /**
     * The recipe the craft order was started for, while {@code cells} is that order's grid (open or finished), and
     * otherwise none (issue #24 review fix): a menu that previews the order's grid without a hint of its own — a second
     * screen that adopted the locked grid, or one reopened — resolves the recipe being fetched for, not whichever
     * recipe the manager finds first for a grid two recipes match. A hint only: the menu still looks it up and falls
     * back to the first match when it no longer matches.
     */
    public Optional<ResourceLocation> craftOrderHint(CraftGrid<ItemKey> cells) {
        TerminalCraftOrder craft = craftOrder;
        return craft != null && craft.cells().equals(cells) ? craft.recipe() : Optional.empty();
    }

    /**
     * The question one portion of the craft order raised, while the order is {@link ListOrderState#ASKING}: an ordinary
     * {@link RequestConfirmation}, the very question a click raises, which the screen shows in the same panel and
     * answers with {@link RequestScope#CRAFT} ({@code §3.6.6}).
     */
    public Optional<RequestConfirmation<ItemKey>> craftQuestion() {
        TerminalCraftOrder craft = craftOrder;
        return craft == null ? Optional.empty() : craft.order().question();
    }

    /**
     * Server: the crafting panel's <b>Fetch</b> — bring what the buffer is missing for {@code crafts} crafts of the
     * grid {@code menu} shows ({@code docs/warehouse-system.md} §3.4.5, issue #24, slices 2 and 3).
     * <p>
     * <b>Measured before anything is requested</b> ({@link CraftFetchQuestion}). The demand is topped up against the
     * buffer and nothing else — per ingredient {@code max(0, uses × crafts − inBuffer)} — and it counts whole crafts
     * only, bounded by what the racks may give a player and by the buffer room that is left once every open request to
     * this terminal has put its items down. Then, in this order:
     * <ul>
     * <li>the buffer already holds every craft: {@link TerminalCraftResult#NOTHING_TO_FETCH}, and the player can craft
     * right away;</li>
     * <li>not one craft beyond what the buffer holds can be supplied, or fit: refused, naming the reason
     * ({@link TerminalCraftResult#NOT_ENOUGH} with the ingredient, {@link TerminalCraftResult#NO_ROOM_FOR_ONE});</li>
     * <li>every craft asked for is possible and nothing would be made: the order starts at once, without a question
     * ({@link TerminalCraftResult#STARTED});</li>
     * <li>otherwise the player is asked once ({@link TerminalCraftResult#ASKING}), with the numbers a Yes starts. The
     * Yes comes back as a Fetch for exactly {@link CraftFetchQuestion#possible()} crafts with {@code answering} set,
     * and is measured again: a warehouse that changed in between is asked about again
     * ({@link TerminalCraftResult#CHANGED}) — a client never decides a cost.</li>
     * </ul>
     * Nothing is promised by measuring, and every answer but {@link TerminalCraftResult#STARTED} leaves the aisle's
     * requests exactly as they were.
     * <p>
     * <b>Missing ingredients can be made first</b> (slice 3). When the racks fall short of an ingredient that a
     * production station of the same aisle has a pattern for, the measurement asks the controller what the chain
     * behind it would make ({@link WarehouseControllerBlockEntity#productionPreviews}, the ADR-032 planner with one
     * shared budget, ordering nothing), and the question offers it: "Oak Planks x8 will be made first", with the
     * crafts the player gets without having anything made. A Fetch that would make something always asks, and its Yes
     * carries the production total it agreed to, which becomes the order's consent budget
     * ({@link CraftFetchQuestion#budget()}). A chain that does not hold is refused with the planner's own reason and
     * item ("Oak Log is missing"; {@link TerminalCraftOutcome#refusal()}). The chain makes <b>ingredients</b>: each
     * portion's request claims from the racks first and has only the rest made, as one atomic chain whose root is the
     * ingredient; its product is stored and then fetched into this buffer like any other item. The grid's own recipe is
     * never a pattern, a production order or an order's completion — only the player crafts (ADR-024, ADR-059).
     * <p>
     * <b>The order is a second list order</b> (ADR-062): one line per ingredient, worked off in portions that are each
     * an ordinary {@code controller.request(..., StockAccess.PLAYER, ..., RequestScope.CRAFT)}, the call a click
     * makes, at most {@code terminalListOpenRequests} of them open at a time. A clipboard order never blocks it, and
     * only an <b>open</b> craft order refuses another Fetch ({@link TerminalCraftResult#ALREADY_RUNNING}); a finished
     * one is replaced. It moves no item and crafts nothing: it ends with the ingredients in the buffer.
     * <p>
     * Who may: a player who has <b>this</b> terminal's menu open right now and may still use the block, exactly as for
     * {@link #craftFromBuffer}. The grid and the recipe are the menu's — its last preview, which the menu resolved for
     * the Fetch's own grid right before calling this — so the recipe is looked up where ADR-059 allows it and nowhere
     * else. No aisle, no Fetch ({@link TerminalCraftResult#NO_AISLE}).
     *
     * @param crafts            how many crafts to fetch for: the amount field's result items rounded up to whole
     *                          crafts by the client (issue #33), clamped to {@code 1..maxTerminalRequestAmount}
     * @param acceptedProducing the production total a Yes agreed to; 0 for a Fetch nobody was asked about
     * @param answering         whether this Fetch is the Yes to a question the player was shown
     */
    public TerminalCraftOutcome fetchCraft(Player player, WarehouseTerminalMenu menu, int crafts,
            long acceptedProducing, boolean answering) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");
        if (level == null || level.isClientSide || isRemoved())
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        if (menu.terminal() != this || player.containerMenu != menu || !canPlayerUse(player))
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        if (hasOpenCraftOrder())
            return TerminalCraftOutcome.of(TerminalCraftResult.ALREADY_RUNNING);
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return TerminalCraftOutcome.of(TerminalCraftResult.NO_AISLE);
        WarehouseControllerBlockEntity controller = found.get();
        CraftGrid<ItemKey> grid = menu.craftCells();
        TerminalCrafting.Preview preview = menu.craftPreview();
        if (grid.isEmpty())
            return TerminalCraftOutcome.of(TerminalCraftResult.GRID_EMPTY);
        if (preview.recipe().isEmpty())
            return TerminalCraftOutcome.of(TerminalCraftResult.NO_RECIPE);
        if (preview.locked())
            return TerminalCraftOutcome.of(TerminalCraftResult.LOCKED);
        int asked = Math.max(1, Math.min(crafts, Math.max(1, WareworksConfig.maxTerminalRequestAmount())));
        // One set of slot views for the measurement and the order's lines, so what the player is told and what is
        // requested are worked out from the very same buffer.
        List<SlotView<ItemKey>> views = bufferViews();
        // An ingredient the racks are short of can be made first at the aisle's own machines (slice 3, ADR-032): the
        // chains are previewed only when the racks fall short of what fits, from one snapshot for the whole
        // measurement, and nothing is ordered by it — the portions make the chains, the player makes the craft.
        CraftFetchQuestion<ItemKey> question = CraftFetchQuestion.measure(grid, asked, views,
                key -> controller.availableTo(StockAccess.PLAYER, key), openRequestCounts(controller),
                ItemKey::getMaxStackSize, controller.productionPreviews(StockAccess.PLAYER));
        Optional<ItemKey> made = ItemKey.fromStack(preview.result());
        switch (question.verdict(answering, acceptedProducing)) {
            case NOTHING_TO_FETCH:
                return new TerminalCraftOutcome(TerminalCraftResult.NOTHING_TO_FETCH, question.ready(), made);
            case NOT_ENOUGH:
                return TerminalCraftOutcome.measured(TerminalCraftResult.NOT_ENOUGH, question);
            case NO_ROOM_FOR_ONE:
                return TerminalCraftOutcome.measured(TerminalCraftResult.NO_ROOM_FOR_ONE, question);
            case ASK:
                return TerminalCraftOutcome.measured(TerminalCraftResult.ASKING, question);
            case CHANGED:
                return TerminalCraftOutcome.measured(TerminalCraftResult.CHANGED, question);
            case START:
            default:
                break;
        }
        List<ListEntry<ItemKey>> entries = CraftFetchQuestion.entries(grid, asked, views);
        if (entries.isEmpty())
            return new TerminalCraftOutcome(TerminalCraftResult.NOTHING_TO_FETCH, question.ready(), made);
        long now = level.getGameTime();
        // The exact clipboard sequence (fetchList): resolved once, running from this tick, with the production total
        // the player agreed to as its budget. A finished craft order is simply replaced.
        TerminalCraftOrder started = new TerminalCraftOrder(ListOrder.of(entries, CraftGrid.SIZE, question.budget(),
                now), grid, asked, preview.recipe());
        craftOrder = started;
        setChanged();
        controller.onListOrderStarted(worldPosition);
        markSummaryDirty();
        // Every ingredient counts once towards this player's "most used" order, as a clipboard's items do (M24): they
        // asked for each of them, here, and the order then runs on with nobody at the terminal.
        TerminalPreferences.countListOrder(player, started.order().lines().stream().map(ListLine::key).toList());
        return new TerminalCraftOutcome(TerminalCraftResult.STARTED, asked, made);
    }

    /**
     * Server: the player's answer to the question one portion of the craft order raised ({@link #craftQuestion()}). It
     * tops the order's consent up for that ingredient — it never replaces it ({@link ListOrder#answer}) — and the order
     * runs again from this tick, offering that portion first and measuring it against the warehouse as it is then.
     * <p>
     * An answer that accepts <b>nothing</b> is the order button's "show me that question again": it changes no state
     * and answers {@link TerminalCraftResult#ASKING}, and the menu puts the question up once more. So does an answer to
     * a question the order is no longer asking ({@code answered} differs from {@link #craftQuestion()}, issue #24
     * review fix): another player's answer moved the order on to the next portion's question, and a Yes given to the
     * first one is consent about another item or other numbers, never about this one.
     *
     * @param answered the question the player answered, as their screen showed it
     */
    public TerminalCraftOutcome answerCraftQuestion(Player player, Optional<RequestConfirmation<ItemKey>> answered,
            RequestAcknowledgement acknowledged) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(answered, "answered");
        Objects.requireNonNull(acknowledged, "acknowledged");
        if (level == null || level.isClientSide || isRemoved() || !canPlayerUse(player))
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        TerminalCraftOrder craft = craftOrder;
        if (craft == null || craft.order().state() != ListOrderState.ASKING)
            return TerminalCraftOutcome.of(TerminalCraftResult.NOT_RUNNING);
        if (!acknowledged.given()
                || !craft.order().answer(answered.orElse(null), acknowledged, level.getGameTime()))
            return TerminalCraftOutcome.of(TerminalCraftResult.ASKING);
        setChanged();
        markSummaryDirty();
        controller().ifPresent(controller -> controller.onListOrderStarted(worldPosition));
        return TerminalCraftOutcome.of(TerminalCraftResult.ANSWERED);
    }

    /**
     * Server: the player's <b>no</b> to the question one portion of the craft order raised. Nothing is accepted and
     * nothing is requested; the order parks ({@link ListOrderState#PARKED}), so the order button becomes Resume, and a
     * player who changes their mind gets the same question again.
     */
    public TerminalCraftOutcome declineCraftQuestion(Player player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved() || !canPlayerUse(player))
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        TerminalCraftOrder craft = craftOrder;
        if (craft == null || !craft.order().decline(level.getGameTime()))
            return TerminalCraftOutcome.of(TerminalCraftResult.NOT_RUNNING);
        // The order is parked by this no, not by a portion refused before the question: the row says "paused".
        craft.lastRefusal(Optional.empty());
        setChanged();
        markSummaryDirty();
        return TerminalCraftOutcome.of(TerminalCraftResult.DECLINED);
    }

    /** Server: gives a parked or stalled craft order another try. */
    public TerminalCraftOutcome resumeCraftOrder(Player player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved() || !canPlayerUse(player))
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        TerminalCraftOrder craft = craftOrder;
        if (craft == null || !craft.isOpen())
            return TerminalCraftOutcome.of(TerminalCraftResult.NOT_RUNNING);
        craft.order().resume(level.getGameTime());
        setChanged();
        markSummaryDirty();
        controller().ifPresent(controller -> controller.onListOrderStarted(worldPosition));
        return TerminalCraftOutcome.of(TerminalCraftResult.RESUMED);
    }

    /**
     * Server: {@code player} gives the craft order up — the order button's Cancel, or the cancel mark on the progress
     * row, in any state. Any player who may use the terminal may, because requests have no owner here.
     *
     * @see #cancelCraftOrder()
     */
    public TerminalCraftOutcome cancelCraftOrder(Player player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved() || !canPlayerUse(player))
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        return TerminalCraftOutcome.of(cancelCraftOrder() ? TerminalCraftResult.CANCELLED
                : TerminalCraftResult.NOT_RUNNING);
    }

    /**
     * Server: gives the open craft order up (a player's Cancel, the terminal broken or cleared).
     * <p>
     * <b>Only the order's own share is given back</b> ({@link ListOrder#releaseShares()}): a portion may have merged
     * into a player's own click or into the clipboard order's portion (ADR-020), and those keep their amounts. Items
     * the crane already delivered stay in the buffer — there is no way back (issue #24). When the order's share was the
     * whole request, the request is removed like any cancelled one, and a crane already carrying its items takes them
     * back into storage. When another asker's amount keeps the request open, a crane that has already picked delivers
     * what it carries, and that counts towards what is left. The grid the order was started for unlocks.
     *
     * @return whether an open order was given up
     */
    public boolean cancelCraftOrder() {
        TerminalCraftOrder craft = craftOrder;
        if (craft == null || !craft.isOpen())
            return false;
        giveBackShares(craft.order());
        craftOrder = null;
        setChanged();
        markSummaryDirty();
        return true;
    }

    /**
     * One craft order pass: the same top-up pass the clipboard order runs ({@link #runPass}), with
     * {@link RequestScope#CRAFT} and without any of the clipboard's checks — the grid's lines are the whole truth.
     *
     * @return whether the craft order is still open
     */
    private boolean tickCraftOrder(long now) {
        TerminalCraftOrder craft = craftOrder;
        if (craft == null || !craft.isOpen())
            return false;
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return true; // no aisle right now (misaligned, unloaded): wait, never give the order up for it
        ListOrder<ItemKey> order = craft.order();
        if (!order.due(now))
            return true;
        PassOutcome pass = runPass(order, RequestScope.CRAFT, found.get(), now);
        craft.lastRefusal(pass.refusalAfter(craft.lastRefusal()));
        if (pass.pass() != ListPass.WAITING)
            setChanged();
        markSummaryDirty();
        return order.state().isOpen();
    }

    /** The terminal's buffer slots as a plan reads them: one view per slot, built from the live stacks, none copied. */
    private List<SlotView<ItemKey>> bufferViews() {
        int slots = buffer.getSlots();
        List<SlotView<ItemKey>> views = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = buffer.getStackInSlot(slot);
            int limit = buffer.getSlotLimit(slot);
            views.add(stack.isEmpty() ? SlotView.empty(limit)
                    : SlotView.of(ItemKey.of(stack), stack.getCount(), limit, stack.getMaxStackSize()));
        }
        return views;
    }

    /**
     * What every open request to this terminal still brings, oldest first — a player's own clicks, other players'
     * clicks, the clipboard order's portions and the craft order's own — for the room a Fetch is measured against.
     */
    private List<KeyCount<ItemKey>> openRequestCounts(WarehouseControllerBlockEntity controller) {
        List<RetrievalRequest<ItemKey, BlockPos>> open = controller.requestsFor(worldPosition);
        List<KeyCount<ItemKey>> counts = new ArrayList<>(open.size());
        for (RetrievalRequest<ItemKey, BlockPos> request : open) {
            if (request.remaining() > 0)
                counts.add(new KeyCount<>(request.key(), request.remaining()));
        }
        return counts;
    }

    // --- clipboard orders (M23, issue #19, ADR-036) ----------------------------------------------------------------

    /**
     * The terminal's <b>list slot</b>: one clipboard, the order and its receipt in one
     * ({@code docs/warehouse-system.md} §3.4.4).
     * <p>
     * It is a real slot on the block entity rather than an item in the player's hand, and that is what makes the
     * feature server-authoritative: the order runs on while the player walks to the building site, the entries are
     * ticked off where the clipboard lies, and the screen can show how far the list has got. Automation never sees it —
     * the item capability is the extract-only buffer view and nothing else ({@link #registerCapabilities}) — so a
     * funnel can neither feed a clipboard in nor pull the receipt out.
     */
    public IItemHandlerModifiable listSlot() {
        return listSlot;
    }

    /** The clipboard in the list slot, or an empty stack. The returned stack is the live one; do not keep it. */
    public ItemStack listClipboard() {
        return listSlot.getStackInSlot(LIST_SLOT);
    }

    /** Whether a clipboard order exists at all, finished ones included. */
    public boolean hasListOrder() {
        return listOrder != null;
    }

    /**
     * Whether a list order still has work to do here — the clipboard's <b>or</b> the craft order (issue #24, ADR-062) —
     * i.e. whether the controller has to look in on this terminal. It is one of the three hooks the controller calls
     * ({@link #tickListOrder}, {@link #creditListDelivery}), and covering both orders here is what lets the controller
     * run them without knowing there are two.
     */
    public boolean hasOpenListOrder() {
        return hasOpenClipboardOrder() || hasOpenCraftOrder();
    }

    /** Whether the clipboard order still has work to do. */
    public boolean hasOpenClipboardOrder() {
        return listOrder != null && listOrder.state().isOpen();
    }

    /**
     * How far the clipboard order has got, in the bounded form the screen and the goggles are given, with why its last
     * portion was refused while that still holds (issue #24).
     */
    public TerminalListState listState() {
        return TerminalListState.of(listOrder, listRefusal);
    }

    /**
     * The question one portion of the clipboard order raised, while the order is
     * {@link dev.wareworks.core.terminal.ListOrderState#ASKING}.
     * <p>
     * It is an ordinary {@link RequestConfirmation}, the very question a click raises, so the screen shows it in the
     * panel M15 built and the answer travels back through the same shape ({@code §3.6.6}) — the "use the confirmation
     * mechanism the terminal already has" of issue #19.
     */
    public Optional<RequestConfirmation<ItemKey>> listQuestion() {
        return listOrder == null ? Optional.empty() : listOrder.question();
    }

    /**
     * Server: what pressing <b>Fetch</b> on the clipboard in the list slot would mean, and the order itself once the
     * player has agreed to it ({@code docs/warehouse-system.md} §3.4.4, issue #19).
     * <p>
     * The two answers the project owner settled on are one dialog, measured over the whole list before anything is
     * requested ({@link ListOrderConfirmation}): what the racks fall short of ("the list wants 2000 cobblestone and
     * the warehouse holds 1300 — fetch what there is?") and what would have to be <b>made</b> ("4 entries are not in
     * stock but can be produced"). A list a stocked warehouse covers from the racks starts without any dialog at all.
     * <p>
     * <b>The answer is measured again</b>, exactly as a click's is: the numbers the player saw are compared with the
     * numbers the server measures now ({@link ListOrderConfirmation#covers}), so a warehouse that lost stock in
     * between asks a second time instead of quietly ordering more production than was agreed to. Nothing is promised
     * by measuring, and {@link TerminalListResult#ASKING} means that no request and no production order exists.
     * <p>
     * A Yes becomes the order's consent <b>budget</b>, which its portions spend down one by one
     * ({@link ListOrder#spend}): one Yes cannot pay for an unbounded stream of requests, and a portion the remainder
     * no longer covers stops the order and asks again.
     *
     * @param acceptedMissing   items the player accepts not getting, i.e. what they saw as missing
     * @param acceptedProducing items the player accepts having made for them
     * @param acceptedDropped   entries the player accepts the entry cap leaving on the clipboard, i.e. what they saw as
     *                          not taken (M23 review fix)
     */
    public TerminalListOutcome fetchList(Player player, long acceptedMissing, long acceptedProducing,
            long acceptedDropped) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved())
            return TerminalListOutcome.of(TerminalListResult.NO_AISLE);
        if (!canPlayerUse(player))
            return TerminalListOutcome.of(TerminalListResult.OUT_OF_REACH);
        // Only another clipboard order blocks this one: a craft fetch runs beside it (issue #24, ADR-062).
        if (hasOpenClipboardOrder())
            return TerminalListOutcome.of(TerminalListResult.ALREADY_RUNNING);
        ItemStack clipboard = listClipboard();
        if (!ClipboardList.isClipboard(clipboard))
            return TerminalListOutcome.of(TerminalListResult.NO_CLIPBOARD);
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return TerminalListOutcome.of(TerminalListResult.NO_AISLE);
        WarehouseControllerBlockEntity controller = found.get();
        long now = level.getGameTime();
        // Resolved once, here: from now on the order's own lines are the truth about what is being fetched, and the
        // clipboard is only ever read again to check that it is still the same list and to write the tick marks.
        ListOrder<ItemKey> resolved = ListOrder.of(ClipboardList.read(clipboard),
                WareworksConfig.maxTerminalListEntries(), RequestAcknowledgement.NONE, now);
        if (resolved.entries() == 0)
            return TerminalListOutcome.of(TerminalListResult.EMPTY_LIST);
        // Every entry is measured with the click's own planner against one ledger (issue #36, ADR-069): what could be
        // produced is the server's own number, which no screen could derive, and it is the number a click would get.
        // The entries the cap left behind are part of the question, not only of a tooltip: a player presses Fetch on
        // "this list" and has to be told in the dialog they consent in that part of it was left out (M23 review fix).
        ListOrderConfirmation<ItemKey> question = controller.listConfirmation(resolved.lines(), resolved.dropped());
        if (question.required() && !question.covers(acceptedMissing, acceptedProducing, acceptedDropped))
            return TerminalListOutcome.asking(question);
        listOrder = ListOrder.restore(resolved.lines(), resolved.dropped(), ListOrderState.RUNNING, question.budget(),
                now, now);
        listTicksPending = false;
        setChanged();
        controller.onListOrderStarted(worldPosition);
        markSummaryDirty();
        // The player asked for every item on the list, so each item type counts once towards their "most used" order
        // (M24, issue #17). It is counted here, where the player is, and not line by line in tickListOrder: the order
        // then runs on for minutes with nobody at the terminal, and a line the warehouse cannot cover still says what
        // the player wanted.
        TerminalPreferences.countListOrder(player, listOrder.lines().stream().map(ListLine::key).toList());
        return TerminalListOutcome.of(TerminalListResult.STARTED);
    }

    /**
     * Server: the player's answer to the question one portion raised ({@link #listQuestion()}). It tops the order's
     * consent up — it never replaces it — and the order runs again from this tick, which offers <b>that</b> portion
     * once more and measures it against the warehouse as it is then.
     * <p>
     * An answer that accepts <b>nothing</b> is not a Yes and not a No: it is the list button asking for the question to
     * be put up again ({@link TerminalListResult#QUESTION}), because the question lives here and a player who closed
     * the panel would otherwise have no way back to it (M23 review fix). It changes no state at all. Neither does an
     * answer to a question the order is no longer asking (issue #24 review fix): the current one is put up instead,
     * because a Yes is consent about the item and the numbers it was given for ({@link ListOrder#answer}).
     *
     * @param answered the question the player answered, as their screen showed it
     */
    public TerminalListOutcome answerListQuestion(Player player, Optional<RequestConfirmation<ItemKey>> answered,
            RequestAcknowledgement acknowledged) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(answered, "answered");
        Objects.requireNonNull(acknowledged, "acknowledged");
        if (level == null || level.isClientSide || isRemoved())
            return TerminalListOutcome.of(TerminalListResult.NO_AISLE);
        if (!canPlayerUse(player))
            return TerminalListOutcome.of(TerminalListResult.OUT_OF_REACH);
        ListOrder<ItemKey> order = listOrder;
        if (order == null || order.state() != ListOrderState.ASKING)
            return TerminalListOutcome.of(TerminalListResult.NOT_RUNNING);
        if (!acknowledged.given() || !order.answer(answered.orElse(null), acknowledged, level.getGameTime()))
            return TerminalListOutcome.of(TerminalListResult.QUESTION);
        setChanged();
        controller().ifPresent(controller -> controller.onListOrderStarted(worldPosition));
        return TerminalListOutcome.of(TerminalListResult.ANSWERED);
    }

    /**
     * Server: the player's <b>no</b> to the question one portion raised — the Cancel button of that panel (M23 review
     * fix).
     * <p>
     * Nothing is accepted and nothing is requested; the order parks ({@link ListOrderState#PARKED}), so the list button
     * becomes <b>Resume</b>, every line and every tick mark stays as it is, and a player who changes their mind gets
     * the same question again. Before this, a dismissed panel left the order standing in
     * {@link ListOrderState#ASKING} with no way on screen to answer or refuse it.
     */
    public TerminalListOutcome declineListQuestion(Player player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved())
            return TerminalListOutcome.of(TerminalListResult.NO_AISLE);
        if (!canPlayerUse(player))
            return TerminalListOutcome.of(TerminalListResult.OUT_OF_REACH);
        ListOrder<ItemKey> order = listOrder;
        if (order == null || !order.decline(level.getGameTime()))
            return TerminalListOutcome.of(TerminalListResult.NOT_RUNNING);
        // Parked by this no, not by a portion refused before the question: the row says "paused".
        listRefusal = Optional.empty();
        setChanged();
        markSummaryDirty();
        return TerminalListOutcome.of(TerminalListResult.DECLINED);
    }

    /** Server: gives a parked clipboard order another try — the "click to try again" of an order that gave up. */
    public TerminalListOutcome resumeListOrder(Player player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved())
            return TerminalListOutcome.of(TerminalListResult.NO_AISLE);
        if (!canPlayerUse(player))
            return TerminalListOutcome.of(TerminalListResult.OUT_OF_REACH);
        ListOrder<ItemKey> order = listOrder;
        if (order == null || !order.state().isOpen())
            return TerminalListOutcome.of(TerminalListResult.NOT_RUNNING);
        order.resume(level.getGameTime());
        setChanged();
        controller().ifPresent(controller -> controller.onListOrderStarted(worldPosition));
        return TerminalListOutcome.of(TerminalListResult.RESUMED);
    }

    /**
     * Server: gives the clipboard order up (the Cancel button, the clipboard taken out of the slot, the terminal
     * broken).
     * <p>
     * <b>Only the order's own share of its requests is given back</b> ({@link ListOrder#releaseShares()}, issue #24,
     * ADR-062), so nothing it asked for is left promised. A portion may have merged into a player's own click or into
     * the craft order's portion (ADR-020), and before issue #24 cancelling the whole merged request took that click
     * down with the list; now the click keeps its own amount. Items the crane already delivered stay in the buffer. A
     * crane already carrying items of a request takes them back into storage when the order's share was the whole
     * request, and delivers them, counting towards what is left, when another asker keeps the request open. The tick
     * marks the clipboard carries stay as they are — they are a receipt for what really arrived, and a cancelled order
     * may never un-tick a delivered entry.
     * A production order one of those requests started keeps running and its result lands in stock, exactly as for any
     * other cancelled request ({@code §3.5}).
     *
     * @return whether an order was given up
     */
    public boolean cancelListOrder() {
        ListOrder<ItemKey> order = listOrder;
        if (order == null)
            return false;
        giveBackShares(order);
        listOrder = null;
        listTicksPending = false;
        listRefusal = Optional.empty();
        setChanged();
        markSummaryDirty();
        return true;
    }

    /**
     * Gives the controller back exactly what {@code order} was still owed by each of its open requests, and nothing
     * more ({@code WarehouseControllerBlockEntity#reduceRequestShare}), together with the part of each share production
     * was making for the order (issue #24 review fix), so a click left in a merged request never pays for that
     * production falling short later. The order forgets those requests whether or not a controller is there to tell:
     * an unloaded aisle keeps its queue, and the requests are then simply served.
     */
    private void giveBackShares(ListOrder<ItemKey> order) {
        Map<UUID, ListOrder.Share> shares = order.releaseShareParts();
        if (shares.isEmpty())
            return;
        controller().ifPresent(controller -> shares.forEach(
                (id, share) -> controller.reduceRequestShare(id, share.amount(), share.producing())));
    }

    /**
     * Server: production behind request {@code id} fell short by {@code owed} items, and the controller has reduced the
     * request by that much ({@code WarehouseControllerBlockEntity#onProductionOrderFinished}, issue #24 review fix).
     * <p>
     * The orders here that share the request take the shortfall off their own lines' made part, in the turn deliveries
     * are credited in — the craft order first, then the clipboard order ({@link ListOrder#shortfallInTurn}). A
     * player's click merged into a portion is credited after the orders' lines, so without this the orders would claim
     * the click's items when they arrive, and the click would get nothing; an order makes up its own loss with its
     * next portion.
     */
    public void onRequestShortfall(UUID id, int owed) {
        if (id == null || owed < 1 || level == null || level.isClientSide)
            return;
        ListOrder<ItemKey> craft = hasOpenCraftOrder() ? craftOrder.order() : null;
        ListOrder<ItemKey> clipboard = hasOpenClipboardOrder() ? listOrder : null;
        if (craft == null && clipboard == null)
            return;
        if (ListOrder.shortfallInTurn(id, owed, Arrays.asList(craft, clipboard)) > 0) {
            setChanged();
            markSummaryDirty();
        }
    }

    /**
     * Server: one top-up pass of each list order this terminal runs, called by the controller — first the clipboard
     * order ({@code docs/warehouse-system.md} §3.4.4), then the craft order (§3.4.5, issue #24, ADR-062).
     * <p>
     * It is the whole "worked off in portions" of issue #19, and every portion goes through precisely the call a click
     * goes through ({@code WarehouseControllerBlockEntity#request} with {@link RequestScope#LIST} or
     * {@link RequestScope#CRAFT}): the same clamping, the same merging (ADR-020), the same reserves, maxima, filters,
     * priorities, chains and safety stop ({@link #runPass}). A full buffer is a planner skip that leaves the request
     * open — {@code NoJobReason.OUTPUT_FULL} arms no back-off — so the order simply waits, it never refuses because a
     * whole schematic does not fit, and the first freed slot restarts the trip.
     * <p>
     * <b>The clipboard is checked before anything is asked for.</b> A clipboard that was taken out, swapped or edited
     * no longer shows this order's items ({@link ClipboardList#stillShowsAll}), and the order is then given up rather
     * than carried on against a list nobody can read any more. The craft order has no such check: its lines are the
     * whole truth about what it fetches.
     *
     * @return whether this terminal is still worth looking in on, i.e. whether either order is still open
     */
    public boolean tickListOrder(long now) {
        if (level == null || level.isClientSide || isRemoved())
            return false;
        boolean clipboard = tickClipboardOrder(now);
        boolean craft = tickCraftOrder(now);
        return clipboard || craft;
    }

    /**
     * One top-up pass of the clipboard order, with the clipboard's own checks around the shared pass.
     *
     * @return whether the clipboard order is still open
     */
    private boolean tickClipboardOrder(long now) {
        ListOrder<ItemKey> order = listOrder;
        if (order == null || !order.state().isOpen())
            return false;
        ItemStack clipboard = listClipboard();
        // After a reload the lines are back but the clipboard was saved with the item: re-assert every tick mark the
        // order has earned, once. Writing a mark twice is harmless (ClipboardList#tickOff), and this is what makes a
        // save written between a delivery and its tick mark come back consistent.
        if (listTicksPending) {
            listTicksPending = false;
            if (!stillThisList(clipboard, order))
                return false;
            writeTickMarks(clipboard, order.completedLines());
        }
        Optional<WarehouseControllerBlockEntity> found = controller();
        if (found.isEmpty())
            return true; // no aisle right now (misaligned, unloaded): wait, never give the order up for it
        // The clipboard is checked once per due pass rather than once per walk: the slot's own change hook
        // ({@link #onListSlotChanged()}) catches everything a player can do, so this is the bounded safety net for
        // what a command or another mod can — and reading a clipboard's pages is a copy of all of them.
        if (!order.due(now))
            return true;
        if (!stillThisList(clipboard, order))
            return false;
        PassOutcome pass = runPass(order, RequestScope.LIST, found.get(), now);
        listRefusal = pass.refusalAfter(listRefusal);
        if (pass.pass() != ListPass.WAITING)
            setChanged();
        markSummaryDirty();
        return order.state().isOpen();
    }

    /**
     * The top-up pass both list orders share: {@link ListOrder#beginPass}, portions until there is nothing more to ask
     * for right now, {@link ListOrder#finishPass}.
     * <p>
     * Every portion is a player's own request: a stock rule's reserve does not hold it back, and the one thing the
     * scope adds is that production has to be agreed to first, because nobody is at the terminal
     * ({@link RequestScope}). What it may spend is what the player accepted for <b>this item</b> plus the order's own
     * production total: a Yes about one item never pays for another's reserve ({@link ListOrder#budgetFor}, M23 review
     * fix). Each order keeps at most {@code terminalListOpenRequests} portions open, so the clipboard and the craft
     * order together never take more than twice that of the terminal's request slots, and neither keeps the other
     * from starting; both share the terminal's {@code maxOpenRequestsPerOutput}, so a portion refused there waits for
     * a slot.
     * <p>
     * Merging with a player's own click (ADR-020) stays safe: {@code ListLine#inFlight()} bounds every credit, and a
     * cancel gives back only that share. {@code REQUEST_FULL} and {@code OUTPUT_FULL} step over a line for one pass;
     * nothing is refused for good, and the last refusal is remembered so the screen can name it.
     */
    private PassOutcome runPass(ListOrder<ItemKey> order, RequestScope scope, WarehouseControllerBlockEntity controller,
            long now) {
        order.beginPass(controller::hasOpenRequest);
        int perRequest = Math.max(1, WareworksConfig.maxTerminalRequestAmount());
        int openLimit = Math.max(1, WareworksConfig.terminalListOpenRequests());
        Optional<TerminalPortionRefusal> refused = Optional.empty();
        boolean granted = false;
        Optional<ListPortion<ItemKey>> next;
        while ((next = order.nextPortion(openLimit, perRequest)).isPresent()) {
            ListPortion<ItemKey> portion = next.get();
            TerminalRequestOutcome outcome = controller.request(worldPosition, portion.key(), portion.amount(),
                    perRequest, StockAccess.PLAYER, order.budgetFor(portion.key()), scope);
            if (outcome.isAsking()) {
                order.ask(outcome.question().orElseThrow());
                break;
            }
            RequestResult result = outcome.result().orElseThrow();
            // The part production makes for the portion is kept on the line, so giving the order up can give that
            // promise back with the share, and a shortfall comes off the line rather than off a merged click.
            if (result.isAccepted() && order.granted(portion, result.request().orElseThrow().id(), result.granted(),
                    Math.min(result.producing(), result.granted()), now)) {
                // What the portion really cost comes out of the budget, so one Yes cannot pay for the next portion too.
                outcome.cost().ifPresent(order::spend);
                granted = true;
            } else {
                order.refused(portion);
                Optional<TerminalPortionRefusal> refusal = TerminalPortionRefusal.of(result, portion.key());
                if (refusal.isPresent())
                    refused = refusal;
            }
        }
        ListPass pass = order.finishPass(now, WareworksConfig.terminalListIntervalTicks(),
                WareworksConfig.terminalListStallTicks());
        return new PassOutcome(pass, refused, granted);
    }

    /**
     * What one pass of a list order found.
     *
     * @param pass    the order's own answer ({@link ListOrder#finishPass})
     * @param refused the last portion of the pass that was refused, if any
     * @param granted whether the pass made at least one portion
     */
    private record PassOutcome(ListPass pass, Optional<TerminalPortionRefusal> refused, boolean granted) {
        /**
         * The refusal to remember after this pass: the newest one when the pass was refused something; none once the
         * pass made a portion or the order is complete, because the warehouse has moved since; otherwise
         * {@code before}, since a pass that only waited for items on their way has learnt nothing new.
         * <p>
         * A pass that stopped on a portion's question keeps a refusal from earlier in the same pass: the ingredient it
         * names really is missing, and the panel marks it. The status row does not show it while the order asks
         * ({@code WarehouseTerminalScreen#rowRefusal}), and a no to the question forgets it, because the order is then
         * parked by the no and not by that refusal.
         */
        Optional<TerminalPortionRefusal> refusalAfter(Optional<TerminalPortionRefusal> before) {
            if (refused.isPresent())
                return refused;
            if (granted || pass == ListPass.COMPLETE)
                return Optional.empty();
            return before == null ? Optional.empty() : before;
        }
    }

    /**
     * Server: {@code amount} items of request {@code id} really arrived in this terminal's buffer
     * ({@code WarehouseControllerBlockEntity#onCraneDelivered}) — the one event that moves a list order forward.
     * <p>
     * <b>Credited in turn</b> ({@link ListOrder#creditInTurn}, issue #24, ADR-062): the craft order first, then the
     * clipboard order with what the craft order did not claim. Both are streams of ordinary requests, so a portion of
     * one and a portion of the other can merge into the same request id (ADR-020); the delivery is counted once, by the
     * controller, and the two orders together never claim more than that count. Within an order, the items go to the
     * lines that really asked for them, and a clipboard line that reaches its amount is ticked off on the clipboard
     * ({@link ListCredit#completed()}). A delivery no line was waiting for is claimed as nothing, which is what makes a
     * request shared with a player's own click safe.
     *
     * @return whether either order claimed any of the delivery
     */
    public boolean creditListDelivery(UUID id, int amount, long now) {
        if (id == null || amount < 1)
            return false;
        ListOrder<ItemKey> craft = hasOpenCraftOrder() ? craftOrder.order() : null;
        ListOrder<ItemKey> clipboard = hasOpenClipboardOrder() ? listOrder : null;
        if (craft == null && clipboard == null)
            return false;
        List<ListCredit<ItemKey>> credits = ListOrder.creditInTurn(id, amount, now, Arrays.asList(craft, clipboard));
        ListCredit<ItemKey> craftCredit = credits.get(0);
        ListCredit<ItemKey> clipboardCredit = credits.get(1);
        if (craftCredit.isEmpty() && clipboardCredit.isEmpty())
            return false;
        if (!clipboardCredit.completed().isEmpty())
            writeTickMarks(listClipboard(), clipboardCredit.completed());
        setChanged();
        markSummaryDirty();
        return true;
    }

    /**
     * Whether {@code clipboard} is still the list {@code order} was started for, giving the order up when it is not.
     * <p>
     * A clipboard that was taken out, swapped or edited no longer shows this order's items
     * ({@link ClipboardList#stillShowsAll}), and an order carried on against a list nobody can read any more could
     * neither be ticked off nor explained to the player.
     *
     * @return whether the order may carry on
     */
    private boolean stillThisList(ItemStack clipboard, ListOrder<ItemKey> order) {
        if (ClipboardList.stillShowsAll(clipboard, order.lines()))
            return true;
        cancelListOrder();
        return false;
    }

    /**
     * Writes the tick marks of {@code lines} onto {@code clipboard} and tells the world about the stack change, so an
     * open screen and the block's own save see the receipt.
     * <p>
     * A mark that could not be written means the clipboard no longer shows that entry's item — swapped or edited
     * between the delivery and this write — which the next pass answers by giving the order up
     * ({@link #tickListOrder}). It is logged rather than swallowed, because the order's own lines stay authoritative
     * either way and the player has to be able to find out why their receipt is incomplete.
     * <p>
     * A mark that was <b>already there</b> is not that, and is not reported: the re-assertion after a reload passes
     * every completed line again, and on a consistent save every one of them is already ticked
     * ({@link ClipboardList.Ticks}, M23 review fix).
     */
    private void writeTickMarks(ItemStack clipboard, List<ListLine<ItemKey>> lines) {
        if (lines.isEmpty())
            return;
        ClipboardList.Ticks ticks = ClipboardList.tickOff(clipboard, lines);
        if (!ticks.complete())
            Wareworks.LOGGER.warn("Could not tick {} of {} delivered entries off the clipboard in the terminal at {}",
                    ticks.missed(), lines.size(), worldPosition);
        if (ticks.written() > 0)
            setChanged();
    }

    /**
     * The handler behind the list slot: one clipboard and nothing else.
     * <p>
     * {@link #isItemValid} is what keeps anything but a clipboard out of it, on <b>both</b> sides of the menu and for
     * every path that touches the handler, and the stack limit of one is what makes "the clipboard in the slot"
     * unambiguous. Every change runs {@link #onListSlotChanged()}, which is where a clipboard that was taken out or
     * swapped ends the order.
     */
    private final class ListSlotHandler extends ItemStackHandler {
        private ListSlotHandler() {
            super(1);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return ClipboardList.isClipboard(stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
            onListSlotChanged();
        }
    }

    /** The slot change hook: a clipboard that is no longer this order's list ends the order. */
    private void onListSlotChanged() {
        setChanged();
        if (level == null || level.isClientSide || isRemoved() || listOrder == null)
            return;
        if (!listOrder.state().isOpen()) {
            // A finished order is only the receipt of the clipboard that is gone: forget it with the item.
            if (!ClipboardList.stillShowsAll(listClipboard(), listOrder.lines())) {
                listOrder = null;
                listTicksPending = false;
                listRefusal = Optional.empty();
                markSummaryDirty();
            }
            return;
        }
        if (!ClipboardList.stillShowsAll(listClipboard(), listOrder.lines()))
            cancelListOrder();
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

    /**
     * Goggles: the clipboard order's own lines (M23, issue #19) and the craft order's one line of numbers (issue #24),
     * between the aisle assignment and the request lines.
     * <p>
     * A terminal without an order adds nothing at all, so the tooltip every terminal had before M23 is unchanged. A
     * craft order shows only while it is open: it has no clipboard to stand as its receipt, and a finished one is
     * nothing a goggle wearer has to know about.
     */
    @Override
    protected void addStationGoggleLines(List<Component> tooltip, StationGoggleSummary shown) {
        TerminalListState list = shownList;
        if (list.active()) {
            WareworksLang.listProgress(list.entriesComplete(), list.entries(), list.state().langKey())
                    .forGoggles(tooltip, 1);
            if (list.outstanding() > 0)
                WareworksLang.countLine(WareworksLang.GOGGLES_TERMINAL_LIST_LEFT, list.outstanding())
                        .forGoggles(tooltip, 2);
        }
        TerminalCraftOrderState craft = shownCraft;
        if (craft.isOpen())
            WareworksLang.craftFetchProgress(craft.delivered(), craft.wanted(), craft.state().langKey())
                    .forGoggles(tooltip, 1);
        super.addStationGoggleLines(tooltip, shown);
    }

    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        // The goggles never show why a portion was refused, so a new refusal is no reason to send a packet.
        TerminalListState next = TerminalListState.of(listOrder);
        if (!next.equals(shownList)) {
            shownList = next;
            markSummaryDirty();
        }
        TerminalCraftOrderState craft = craftOrderState().forGoggles();
        if (!craft.equals(shownCraft)) {
            shownCraft = craft;
            markSummaryDirty();
        }
        super.onGoggleObserved();
    }

    /**
     * Real break (server): the clipboard is dropped with the buffer — a receipt is never deleted — and both list
     * orders are given up, each giving back only its own share of its requests (issue #24, ADR-062). The clipboard
     * order ends with its clipboard ({@link #onListSlotChanged()}), the craft order here.
     */
    @Override
    public void destroy() {
        super.destroy();
        if (level == null || level.isClientSide)
            return;
        cancelCraftOrder();
        craftOrder = null;
        ItemStack clipboard = listSlot.extractItem(LIST_SLOT, Integer.MAX_VALUE, false);
        if (!clipboard.isEmpty())
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                    clipboard);
    }

    /**
     * {@link net.minecraft.world.Clearable}: commands and structure placement empty the list slot with the buffer, and
     * both list orders end with them — on a server, giving back only their own share of their requests.
     */
    @Override
    public void clearContent() {
        super.clearContent();
        listSlot.setStackInSlot(LIST_SLOT, ItemStack.EMPTY);
        listOrder = null;
        listTicksPending = false;
        listRefusal = Optional.empty();
        if (level != null && !level.isClientSide)
            cancelCraftOrder();
        craftOrder = null;
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!clientPacket)
            return;
        // Only the bounded numbers, and only while a list is really being worked off: the clipboard itself never goes
        // into a chunk packet (TerminalListState).
        CompoundTag listTag = new CompoundTag();
        shownList.write(listTag);
        if (!listTag.isEmpty())
            tag.put(LIST_STATE_TAG, listTag);
        // The same for the craft order (issue #24): numbers only, while it is open — never its grid or an item.
        CompoundTag craftTag = new CompoundTag();
        shownCraft.write(craftTag);
        if (!craftTag.isEmpty())
            tag.put(CRAFT_STATE_TAG, craftTag);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            shownList = TerminalListState.read(tag.getCompound(LIST_STATE_TAG));
            shownCraft = TerminalCraftOrderState.read(tag.getCompound(CRAFT_STATE_TAG));
        }
    }

    @Override
    protected void writeStationData(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeStationData(tag, registries);
        if (!listClipboard().isEmpty())
            tag.put(LIST_SLOT_TAG, listSlot.serializeNBT(registries));
        TerminalListPersistence.write(tag, listOrder, registries);
        // The craft order beside it, under a compound of its own (issue #24, ADR-062). A finished one is kept, as the
        // clipboard's is, until the next Fetch replaces it.
        TerminalCraftOrder.write(tag, craftOrder, registries);
    }

    @Override
    protected void readStationData(CompoundTag tag, HolderLookup.Provider registries) {
        super.readStationData(tag, registries);
        // A tag that is no full save says nothing about the clipboard or either order: it is a schematic print landing
        // on a terminal that is already standing (isFullSave, issue #25, ADR-061). Clearing the slot here deleted the
        // player's clipboard without a drop and cancelled its order, and reading the craft order here would drop an
        // open fetch without giving its share of its requests back. Every key is written only while it is set, so a
        // full save without them still clears the slot and both orders, and a world load reads into a fresh terminal
        // anyway.
        if (!isFullSave(tag))
            return;
        listSlot.setStackInSlot(LIST_SLOT, ItemStack.EMPTY);
        if (tag.contains(LIST_SLOT_TAG, Tag.TAG_COMPOUND))
            listSlot.deserializeNBT(registries, tag.getCompound(LIST_SLOT_TAG));
        // The interval and the stall timer start over from the world's current time (TerminalListPersistence), so a
        // world that was closed for an hour does not come back with every order already parked.
        long now = level == null ? 0L : level.getGameTime();
        listOrder = TerminalListPersistence.read(tag, registries, now).orElse(null);
        listRefusal = Optional.empty();
        shownList = TerminalListState.of(listOrder);
        // A terminal saved before issue #24 has no CraftOrder compound and therefore no craft order; one that was
        // asking comes back running and asks again (TerminalCraftOrder#read). Reading never throws.
        craftOrder = TerminalCraftOrder.read(tag, registries, now).orElse(null);
        shownCraft = craftOrderState().forGoggles();
        // The clipboard and the order were saved together, but a tick mark is written to the item and the lines live in
        // the block entity: re-asserting them once after the load is what makes the two agree whatever the save caught.
        listTicksPending = listOrder != null;
    }

    /** Removal or chunk unload: the computers' stock cache is dropped with the block entity (issue #9). */
    @Override
    public void invalidate() {
        super.invalidate();
        computerSnapshot = null;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // An order is back — the clipboard's or the craft order, hasOpenListOrder covers both: the controller of this
        // aisle has to know that this terminal is worth looking in on. Its own membership probe would find it anyway
        // (WarehouseControllerBlockEntity#onMemberChanged, which the base's onLoad fires), so this is the cheap, direct
        // half of the same answer.
        if (level instanceof ServerLevel && hasOpenListOrder())
            controller().ifPresent(controller -> controller.onListOrderStarted(worldPosition));
    }
}
