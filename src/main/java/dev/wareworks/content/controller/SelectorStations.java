package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageHandover;
import dev.wareworks.content.station.SelectorHold;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.production.ProductionOrders;
import dev.wareworks.core.production.SelectorGate;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.core.production.SupplyLine;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The controller's side of the Recipe Selector stations of its aisle (ADR-085, design §5.3): which stations select,
 * what {@link SelectorGate} decides for each of them, the arm and switch pass, the return-input quarantine and the
 * holds.
 * <p>
 * <b>State lives in the stations.</b> Mode, selection, return input and hold are the station block entity's
 * ({@link WarehouseProductionBlockEntity}, full save); this class only remembers <i>which</i> stations select (rebuilt
 * from the stations whenever one of them reports a change), what was last seen of each of them and the holds waiting
 * for an unloaded one (both saved with the controller), and memoises the gate's decisions for one game tick. Every fact it reads is a bounded lookup per selector station: the station's block entity, its buffer, the
 * crane ledger's inbound count, its return input's nine slots, at most six Packager neighbours. Nothing scans the world.
 * <p>
 * <b>Fail closed (I4).</b> A selector station that is not loaded gets no decision: its orders fetch nothing and their
 * deadlines are paused, and its return input stores only what its open orders make.
 */
final class SelectorStations {
    /**
     * What the controller knows about one selector station in this tick.
     *
     * @param rack        the station's rack position
     * @param station     the station, or {@code null} when it is not loaded (or no longer selects)
     * @param returnRack  the station's return input as a rack position of this aisle, if it has one
     * @param inputMember whether {@code returnRack} is a loaded Warehouse Input of this aisle
     * @param decision    the gate's answer, {@code null} without a loaded selecting station
     * @param expected    the items the return input may store: the selection and the results of the station's open
     *                    selector orders that have handed something over (review fix: a queued order's result is no
     *                    reason to store it, nothing of that order is in the line)
     * @param fault       the station's fault the gate was given (empty without a loaded selecting station)
     */
    record View(RackPosition rack, @Nullable WarehouseProductionBlockEntity station, Optional<RackPosition> returnRack,
                boolean inputMember, @Nullable SelectorGate.Decision<ItemKey> decision, Set<ItemKey> expected,
                Optional<SelectorWait> fault) {
    }

    private final WarehouseControllerBlockEntity controller;
    private final ProductionOrders<ItemKey, RackPosition> orders;
    /** Rack positions of stations last seen selecting, plus the stations of open selector orders. Never saved. */
    private final Set<RackPosition> known = new LinkedHashSet<>();
    private boolean stale = true;
    private Map<RackPosition, View> views = Map.of();
    private Map<RackPosition, View> byReturn = Map.of();
    private long viewsTick = Long.MIN_VALUE;
    private boolean dirty = true;
    /**
     * Holds armed while their station was not loaded, applied when it is again. Saved with the controller (review fix:
     * a restart in between must not lose one, I5), at most one per production station.
     */
    private final Map<RackPosition, SelectorHold> pendingHolds = new LinkedHashMap<>();
    /**
     * What was last seen of each loaded selector station: its return input and its selection, so the return input stays
     * guarded while the station is unloaded and has no open order (review fix, I7), and no other station may bind it
     * meanwhile. Refreshed whenever the station is loaded; saved with the controller (re-review fix: a restart must
     * not drop the guard of a station that is still unloaded) and carried through a remap.
     */
    private final Map<RackPosition, LastSeen> lastSeen = new LinkedHashMap<>();

    /** GameTests only ({@link #hideStationForTest}). */
    private final Set<RackPosition> hiddenForTest = new HashSet<>();

    /** What was last seen of a selector station: its return input (a rack of this aisle) and its selection. */
    record LastSeen(Optional<RackPosition> returnRack, Optional<ItemKey> selection) {
        LastSeen {
            Objects.requireNonNull(returnRack, "returnRack");
            Objects.requireNonNull(selection, "selection");
        }
    }
    /** Set while the orders of a removed station are cancelled: a station that is gone gets no hold. */
    private boolean holdsSuppressed;

    SelectorStations(WarehouseControllerBlockEntity controller, ProductionOrders<ItemKey, RackPosition> orders) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    /** A station changed its mode, binding or hold, or the aisle's members changed: rebuild the list. */
    void markStale() {
        stale = true;
        dirty = true;
    }

    /** Something a decision depends on changed within this tick. */
    void invalidate() {
        dirty = true;
    }

    /** Forgets everything (the aisle is cleared or rebuilt). */
    void clear() {
        known.clear();
        pendingHolds.clear();
        lastSeen.clear();
        views = Map.of();
        byReturn = Map.of();
        markStale();
    }

    /** The views of every selector station, recomputed at most once per tick unless invalidated. */
    Map<RackPosition, View> views() {
        Level level = controller.getLevel();
        if (level == null || level.isClientSide || controller.warehouse().isEmpty())
            return Map.of();
        long now = level.getGameTime();
        if (!dirty && now == viewsTick)
            return views;
        refreshKnown();
        Map<RackPosition, View> next = new LinkedHashMap<>();
        Map<RackPosition, View> returns = new LinkedHashMap<>();
        for (RackPosition rack : known) {
            View view = viewOf(level, rack);
            next.put(rack, view);
            // A loaded station's own binding wins over what was remembered of an unloaded one (re-review fix).
            view.returnRack().ifPresent(input -> {
                if (view.station() != null)
                    returns.put(input, view);
                else
                    returns.putIfAbsent(input, view);
            });
        }
        views = next;
        byReturn = returns;
        viewsTick = now;
        dirty = false;
        return views;
    }

    /**
     * Whether this aisle has any selector station at all: the cheap question the controller asks every tick. It reads
     * no station (the list is only rebuilt after a station reported a change); the views are built when needed.
     */
    boolean any() {
        Level level = controller.getLevel();
        if (level == null || level.isClientSide || controller.warehouse().isEmpty())
            return false;
        refreshKnown();
        return !known.isEmpty();
    }

    /**
     * Whether {@code order} may fetch its ingredients now, and how much: {@code -1} for no gate (an ordinary order at an
     * ordinary station), {@code 0} for "nothing now", else the run cap (1 for a probe) or {@link Integer#MAX_VALUE}.
     */
    int admission(ProductionOrder<ItemKey, RackPosition> order) {
        View view = views().get(order.station());
        if (view == null)
            return order.selectorBound() ? 0 : -1;
        if (view.decision() == null || !view.decision().admitted().contains(order.id()))
            return 0;
        return view.decision().runCaps().getOrDefault(order.id(), Integer.MAX_VALUE);
    }

    /** How much of {@code line} an admitted order with run cap {@code cap} may still fetch. */
    static int remainingUnderCap(ProductionOrder<ItemKey, RackPosition> order, SupplyLine<ItemKey> line, int cap) {
        int remaining = line.remaining();
        if (cap == Integer.MAX_VALUE || cap < 0 || order.selectorRuns() <= 0)
            return remaining;
        long perRun = Math.ceilDiv((long) line.required(), order.selectorRuns());
        long allowed = Math.max(0L, perRun * cap - line.delivered());
        return (int) Math.min(remaining, allowed);
    }

    /** The orders whose deadlines the selector stations hold back (design §9). */
    Set<UUID> paused() {
        Map<RackPosition, View> all = views();
        if (all.isEmpty())
            return Set.of();
        Set<UUID> paused = new HashSet<>();
        for (View view : all.values()) {
            if (view.decision() != null) {
                paused.addAll(view.decision().paused());
                continue;
            }
            // Not loaded, or not selecting any more: nothing of it can move, so nothing of it may time out (I4).
            for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(view.rack())) {
                if (order.isOpen() && order.selectorBound())
                    paused.add(order.id());
            }
        }
        return paused;
    }

    /** Why {@code order} waits at its selector station, {@link SelectorWait#NONE} if it does not. */
    SelectorWait waitOf(ProductionOrder<ItemKey, RackPosition> order) {
        if (order == null || !order.isOpen())
            return SelectorWait.NONE;
        View view = views().get(order.station());
        if (view == null || view.decision() == null)
            return order.selectorBound() ? SelectorWait.SELECTOR_NOT_LOADED : SelectorWait.NONE;
        return view.decision().waitOf(order.id());
    }

    /**
     * Quarantine (I7, design §3.5): whether the items of {@code key} must stay in the input at {@code input} because
     * it is the return input of a selector station that does not expect them.
     */
    boolean storeHeld(RackPosition input, ItemKey key) {
        if (views().isEmpty())
            return false;
        View view = byReturn.get(input);
        return view != null && !view.expected().contains(key);
    }

    /** The selector station whose return input is at {@code input}, if one is. */
    Optional<View> stationReturningAt(RackPosition input) {
        views();
        return Optional.ofNullable(byReturn.get(input));
    }

    /**
     * The dispatch pass of the selector stations (design §5.3, §6.1, §7.4): pending holds, the return input's
     * quarantine check, the orders' return input, and the line's switch and arm step.
     *
     * @return whether anything that is saved changed
     */
    boolean run(long now) {
        Map<RackPosition, View> all = views();
        if (all.isEmpty())
            return false;
        boolean changed = false;
        for (View view : List.copyOf(all.values())) {
            WarehouseProductionBlockEntity station = view.station();
            if (station == null)
                continue;
            SelectorHold pending = pendingHolds.remove(view.rack());
            if (pending != null)
                changed |= station.armSelectorHold(pending);
            if (view.returnRack().isPresent())
                changed |= !orders.bindSelectorReturn(view.rack(), view.returnRack().get()).isEmpty();
            if (station.selectorHold().isEmpty()) {
                Optional<ItemKey> unexpected = unexpectedIn(view);
                if (unexpected.isPresent()) {
                    ItemKey making = station.selection().or(() -> firstOpenResult(view.rack()))
                            .orElse(unexpected.get());
                    changed |= station.armSelectorHold(SelectorHold.unexpectedReturn(making, unexpected.get(), now));
                    Wareworks.LOGGER.info("Recipe Selector station {} holds: {} came back through its return input",
                            station.getBlockPos(), unexpected.get());
                }
            }
        }
        invalidate();
        for (View view : List.copyOf(views().values())) {
            WarehouseProductionBlockEntity station = view.station();
            SelectorGate.Decision<ItemKey> decision = view.decision();
            if (station == null || decision == null)
                continue;
            if (station.selectorMode() == SelectorMode.SLIPS) {
                changed |= slipPass(view, station, decision, now);
                continue;
            }
            if (station.selectorMode() != SelectorMode.LOCAL)
                continue;
            boolean hasOpen = false;
            for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(view.rack()))
                hasOpen |= order.isOpen();
            Optional<ItemKey> target = decision.switchTo().or(station::selection);
            if (target.isEmpty() || (!hasOpen && decision.switchTo().isEmpty()))
                continue;
            boolean switching = decision.switchTo().isPresent();
            Optional<SelectorWait> fault = station.armLocalSelector(target.get(), switching);
            if (fault.isEmpty()) {
                if (switching) {
                    station.selectorSwitched(target.get(), now);
                    changed = true;
                }
                station.setSelectorArmed(true, Optional.empty(), switching);
            } else {
                station.setSelectorArmed(false, fault, switching);
            }
        }
        invalidate();
        return changed;
    }

    /**
     * The switch and probe step of a station in slip mode (design §7.4, §7.5). No handshake with the factory: the slip
     * is the message, the factory's veto and the return input's quarantine are the guards.
     * <ul>
     * <li>The probe order has brought a result back (only the station's own return input can credit it, with the
     * expected product): the line is trusted and the probe is over.</li>
     * <li>A switch: the line's selection and switch time change (the slips of every later box), and the head order
     * becomes the probe ({@code selector.remoteProbe}: always, while the line is untrusted, or never), so a factory
     * without a working selector costs one run of junk at most.</li>
     * </ul>
     */
    private boolean slipPass(View view, WarehouseProductionBlockEntity station, SelectorGate.Decision<ItemKey> decision,
            long now) {
        boolean changed = false;
        Optional<UUID> probe = station.selectorProbeOrder();
        if (probe.isPresent()) {
            ProductionOrder<ItemKey, RackPosition> order = orderAt(view.rack(), probe.get());
            if (order == null || !order.isOpen()) {
                station.setSelectorProbeOrder(Optional.empty());
                changed = true;
            } else if (order.produced() > 0L) {
                station.setSelectorSlipsTrusted(true);
                station.setSelectorProbeOrder(Optional.empty());
                Wareworks.LOGGER.debug("Recipe slip station {}: the probe order {} came back right, the line is trusted",
                        station.getBlockPos(), order.id());
                changed = true;
            }
        }
        if (decision.switchTo().isEmpty())
            return changed;
        ItemKey target = decision.switchTo().get();
        station.selectorSwitched(target, now);
        station.setSelectorArmed(true, Optional.empty(), true);
        boolean probing = switch (WareworksConfig.selectorRemoteProbe()) {
            case EVERY_SWITCH -> true;
            case UNTRUSTED -> !station.selectorSlipsTrusted();
            case OFF -> false;
        };
        station.setSelectorProbeOrder(probing ? headOf(view.rack(), target) : Optional.empty());
        return true;
    }

    /** The order {@code id} of the station at {@code rack}, or {@code null}. */
    @Nullable
    private ProductionOrder<ItemKey, RackPosition> orderAt(RackPosition rack, UUID id) {
        for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(rack)) {
            if (order.id().equals(id))
                return order;
        }
        return null;
    }

    /** The head order of the station at {@code rack} making {@code result}: the one the gate switched for. */
    private Optional<UUID> headOf(RackPosition rack, ItemKey result) {
        for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(rack)) {
            if (order.isOpen() && !orders.hasOpenChildren(order.id()))
                return order.result().equals(result) ? Optional.of(order.id()) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * An order ended (design §5.3, I5): a selector order that ended without completing after it handed ingredients
     * over stops its station until a player has looked at the line.
     */
    void orderEnded(ProductionOrder<ItemKey, RackPosition> order, long now) {
        invalidate();
        if (!order.selectorBound() || holdsSuppressed)
            return;
        Optional<WarehouseProductionBlockEntity> station = stationAt(order.station());
        station.filter(found -> found.selectorProbeOrder().filter(order.id()::equals).isPresent())
                .ifPresent(found -> {
                    // A probe of one run ends with its first arrival, before slipPass could see it (re-review fix):
                    // what came back through its own return input was the right product, so the line is trusted.
                    if (order.produced() > 0L)
                        found.setSelectorSlipsTrusted(true);
                    found.setSelectorProbeOrder(Optional.empty());
                });
        if (!order.endedWithLostIngredients())
            return;
        SelectorHold hold = SelectorHold.lostIngredients(order.result(), order.id(), now, order.deliveredIngredients());
        if (station.isPresent()) {
            station.get().armSelectorHold(hold);
        } else if (controller.kindAt(order.station()).orElse(null) == LocationKind.PRODUCTION
                && pendingHolds.putIfAbsent(order.station(), hold) == null) {
            controller.setChanged(); // saved with the controller
        }
        Wareworks.LOGGER.info("Recipe Selector station at {} holds: the order for {} ended {} with {} ingredient items "
                + "in the line", order.station(), order.result(), order.state().name().toLowerCase(java.util.Locale.ROOT),
                order.deliveredIngredients());
    }

    /** What was last seen of each selector station, for the save and the remap (re-review fix). */
    Map<RackPosition, LastSeen> lastSeen() {
        return Map.copyOf(lastSeen);
    }

    /**
     * Restores what was last seen of the selector stations (after {@link #clear()}, on load or after a remap). Each of
     * them is a selector station again until it is seen loaded and not selecting.
     */
    void restoreLastSeen(Map<RackPosition, LastSeen> saved) {
        lastSeen.clear();
        lastSeen.putAll(saved);
        known.addAll(saved.keySet());
        markStale();
    }

    /**
     * Whether a production station of this aisle other than {@code station} returns through the Warehouse Input at
     * {@code inputPos} (rack {@code input}): a loaded one says so itself, an unloaded one through what was last seen of
     * it and through the orders it still has open (re-review fix: its remembered return input is reserved as well).
     */
    boolean returnInputTaken(RackPosition station, BlockPos inputPos, RackPosition input) {
        for (var record : controller.productionStations()) {
            RackPosition rack = record.position();
            if (rack.equals(station))
                continue;
            Optional<WarehouseProductionBlockEntity> other = stationAt(rack);
            boolean taken = other.isPresent()
                    ? other.get().selectorReturnInput().filter(inputPos::equals).isPresent()
                    : Optional.ofNullable(lastSeen.get(rack)).flatMap(LastSeen::returnRack).filter(input::equals)
                            .isPresent();
            for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(rack))
                taken |= order.isOpen() && order.selectorReturn().filter(input::equals).isPresent();
            if (taken)
                return true;
        }
        return false;
    }

    /** The holds waiting for their station to load, for the save (review fix). */
    Map<RackPosition, SelectorHold> pendingHolds() {
        return Map.copyOf(pendingHolds);
    }

    /** Restores the saved pending holds (after {@link #clear()}, on load). */
    void restorePendingHolds(Map<RackPosition, SelectorHold> saved) {
        pendingHolds.clear();
        pendingHolds.putAll(saved);
        markStale();
    }

    /**
     * GameTests only: arms {@code hold} for the station at {@code rack} exactly as {@link #orderEnded} does for a station
     * that is not loaded.
     */
    void pendHoldForTest(RackPosition rack, SelectorHold hold) {
        pendingHolds.putIfAbsent(rack, hold);
        controller.setChanged();
    }

    /** Runs {@code action} without arming holds: the orders of a station that is gone end with it. */
    void withoutHolds(Runnable action) {
        boolean before = holdsSuppressed;
        holdsSuppressed = true;
        try {
            action.run();
        } finally {
            holdsSuppressed = before;
        }
        invalidate();
    }

    /** Whether any open order of the station at {@code rack} has handed something over. */
    boolean engaged(RackPosition rack) {
        for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(rack)) {
            if (order.isOpen() && order.engaged())
                return true;
        }
        return false;
    }

    /** An item in {@code view}'s return input that the station does not expect, if there is one (bounded read). */
    Optional<ItemKey> unexpectedIn(View view) {
        Optional<WarehouseInputBlockEntity> input = returnInputOf(view);
        if (input.isEmpty())
            return Optional.empty();
        for (SlotView<ItemKey> slot : input.get().bufferedItems().slots()) {
            if (slot.key() != null && !view.expected().contains(slot.key()))
                return Optional.of(slot.key());
        }
        return Optional.empty();
    }

    /** The loaded Warehouse Input that is {@code view}'s return input. */
    Optional<WarehouseInputBlockEntity> returnInputOf(View view) {
        Level level = controller.getLevel();
        if (level == null || view.returnRack().isEmpty() || !view.inputMember())
            return Optional.empty();
        Optional<BlockPos> pos = controller.warehouse().flatMap(layout -> layout.worldPosOf(view.returnRack().get()));
        if (pos.isEmpty() || !level.isLoaded(pos.get()))
            return Optional.empty();
        return level.getBlockEntity(pos.get()) instanceof WarehouseInputBlockEntity input && !input.isRemoved()
                ? Optional.of(input) : Optional.empty();
    }

    /**
     * GameTests only: the selector side treats the station at {@code rack} as not loaded ({@code hidden}) or as itself
     * again, so the fail-closed paths (I4, I5, I7) can be tested in a structure whose chunks never unload. Never saved.
     */
    void hideStationForTest(RackPosition rack, boolean hidden) {
        if (hidden)
            hiddenForTest.add(rack);
        else
            hiddenForTest.remove(rack);
        markStale();
    }

    /** The loaded production station at {@code rack}, as the selector side sees it. */
    private Optional<WarehouseProductionBlockEntity> stationAt(RackPosition rack) {
        return hiddenForTest.contains(rack) ? Optional.empty() : controller.productionStationAt(rack);
    }

    // --- building the views ------------------------------------------------------------------------------------------

    private void refreshKnown() {
        if (stale) {
            stale = false;
            Level level = controller.getLevel();
            Set<RackPosition> production = new HashSet<>();
            for (var record : controller.productionStations())
                production.add(record.position());
            known.retainAll(production);
            pendingHolds.keySet().retainAll(production);
            lastSeen.keySet().retainAll(production);
            for (RackPosition rack : production) {
                if (level == null)
                    break;
                Optional<WarehouseProductionBlockEntity> station = stationAt(rack);
                if (station.isEmpty())
                    continue; // not loaded: keep what was known
                if (station.get().selectorMode().selects()) {
                    known.add(rack);
                } else {
                    known.remove(rack);
                    if (lastSeen.remove(rack) != null)
                        controller.setChanged(); // saved with the controller
                }
            }
        }
        for (ProductionOrder<ItemKey, RackPosition> order : orders.open()) {
            if (order.selectorBound())
                known.add(order.station());
        }
    }

    private View viewOf(Level level, RackPosition rack) {
        List<ProductionOrder<ItemKey, RackPosition>> open = new ArrayList<>();
        Set<ItemKey> expected = new LinkedHashSet<>();
        Optional<RackPosition> orderReturn = Optional.empty();
        for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(rack)) {
            if (!order.isOpen())
                continue;
            open.add(order);
            if (order.selectorBound()) {
                // Only what is in the line may come back (review fix): an order that handed nothing over yet has no
                // product on its way, so a B made out of A's ingredients is quarantined while B merely waits in the queue.
                if (order.engaged())
                    expected.add(order.result());
                if (orderReturn.isEmpty())
                    orderReturn = order.selectorReturn();
            }
        }
        Optional<WarehouseProductionBlockEntity> found = stationAt(rack);
        if (found.isEmpty()) {
            // Not loaded (I4, I7): its return input stays guarded with what was last seen of it, even when no order is
            // open any more.
            LastSeen seen = lastSeen.get(rack);
            if (seen != null)
                seen.selection().ifPresent(expected::add);
            Optional<RackPosition> guarded = orderReturn.isPresent() || seen == null ? orderReturn : seen.returnRack();
            return new View(rack, null, guarded, false, null, Set.copyOf(expected), Optional.empty());
        }
        if (!found.get().selectorMode().selects()) {
            if (lastSeen.remove(rack) != null)
                controller.setChanged();
            return new View(rack, null, orderReturn, false, null, Set.copyOf(expected), Optional.empty());
        }
        WarehouseProductionBlockEntity station = found.get();
        station.selection().ifPresent(expected::add);
        Optional<RackPosition> returnRack = station.selectorReturnInput().flatMap(controller::rackAt);
        LastSeen seen = new LastSeen(returnRack, station.selection());
        if (!seen.equals(lastSeen.put(rack, seen)))
            controller.setChanged(); // saved with the controller (re-review fix)
        boolean inputMember = returnRack.isPresent()
                && controller.kindAt(returnRack.get()).orElse(null) == LocationKind.INPUT
                && station.selectorReturnInput().map(level::isLoaded).orElse(false);
        SelectorMode mode = station.selectorMode();
        BlockPos stationPos = station.getBlockPos();
        boolean slips = mode == SelectorMode.SLIPS;
        List<BlockPos> packagers = slips ? PackageHandover.packagersOf(level, stationPos) : List.of();
        Optional<SelectorWait> fault = faultOf(level, station, returnRack, inputMember, packagers);
        SelectorGate.StationFacts<ItemKey> facts = new SelectorGate.StationFacts<>(mode, station.selection(),
                mode != SelectorMode.LOCAL || station.selectorArmed(), fault,
                station.selectorHold().isPresent() || pendingHolds.containsKey(rack), station.selectorProbeOrder(),
                station.bufferIsEmpty(), controller.reservations().reservedInbound(rack) == 0L,
                !slips || PackageHandover.packagersIdle(level, packagers));
        SelectorGate.Decision<ItemKey> decision = SelectorGate.decide(open, orders::hasOpenChildren, facts);
        return new View(rack, station, returnRack, inputMember, decision, Set.copyOf(expected), fault);
    }

    /** The station's fault, in the order a player has to fix them. */
    private static Optional<SelectorWait> faultOf(Level level, WarehouseProductionBlockEntity station,
            Optional<RackPosition> returnRack, boolean inputMember, List<BlockPos> packagers) {
        if (station.attachedFeeder().isPresent())
            return Optional.of(SelectorWait.FEEDER_ATTACHED);
        if (station.selectorReturnInput().isEmpty())
            return Optional.of(SelectorWait.NO_RETURN_INPUT);
        if (returnRack.isEmpty() || !inputMember)
            return Optional.of(SelectorWait.RETURN_INPUT_MISSING);
        if (station.selectorMode() == SelectorMode.SLIPS) {
            if (packagers.isEmpty())
                return Optional.of(SelectorWait.NO_PACKAGER);
            for (BlockPos packager : packagers) {
                if (PackageHandover.ignoresRedstone(level, packager))
                    return Optional.of(SelectorWait.PACKAGER_LINKED);
            }
            return Optional.empty();
        }
        Optional<SelectorWait> armFault = station.selectorArmFault();
        // A busy machine delays a switch (the gate keeps the switch pending), but one whose filter is out of step with
        // the selection must stop the line as well: the gate lets MACHINE_BUSY through to the current lane, so it is
        // reported as ARMING, which stops the lane and still lets a due switch be retried (DEVIATION, design §6.1).
        if (armFault.filter(SelectorWait.MACHINE_BUSY::equals).isPresent() && !station.selectorArmFaultOnSwitch())
            return Optional.of(SelectorWait.ARMING);
        return armFault;
    }

    private Optional<ItemKey> firstOpenResult(RackPosition rack) {
        for (ProductionOrder<ItemKey, RackPosition> order : orders.ordersFor(rack)) {
            if (order.isOpen() && order.state() != ProductionOrderState.COMPLETE)
                return Optional.of(order.result());
        }
        return Optional.empty();
    }
}
