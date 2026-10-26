package dev.wareworks.network;

import java.util.Optional;

import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.StockKeeperMenu;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalListOutcome;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The mod's custom payloads and their handlers ({@code docs/architecture.md} ADR-019).
 * <p>
 * Block entity sync covers everything the world renders and the goggles show; payloads exist only for the warehouse
 * terminal screen, whose item list is far too large for a block entity update tag and is needed by exactly one player
 * at a time. All of them are play-phase payloads registered here, on the mod bus, from the {@code Wareworks}
 * constructor.
 * <p>
 * <b>Dist split.</b> The handlers of the clientbound payloads are registered on both sides (the network negotiation
 * requires it) but only ever run on a client, so this class may name the client handler class inside a handler body:
 * the reference is resolved when the method first runs, which never happens on a dedicated server. Nothing else in this
 * package touches client code.
 */
public final class WareworksNetwork {
    /**
     * Payload version; a change makes clients with an older Wareworks fail the connection instead of misbehaving.
     * <p>
     * <b>Bump it whenever the wire form of any payload changes</b> — a field added, removed or reordered, and also a
     * constant inserted into an enum a payload encodes by ordinal ({@link dev.wareworks.content.controller.RequestRejection}),
     * or whenever client and server change which of them applies an interaction. The negotiation only compares this string, and a decoder that reads the fields in the old order fails open rather
     * than loudly: it would show a refusal as an accepted request and then desynchronise the connection.
     * <p>
     * {@code "2"}: M7 added {@code pending} to {@link TerminalResultPayload} and {@code REQUEST_FULL} to
     * {@code RequestRejection}.
     * <p>
     * {@code "3"}: M11 added {@code producing} to {@link TerminalResultPayload}, the producible flag to
     * {@link TerminalStockPayload} and the three production payloads (ADR-024).
     * <p>
     * {@code "4"}: M11 (terminal production UI) added the producible <b>amount</b> to {@link TerminalStockPayload}, so
     * a terminal can offer "everything that can be made" with a number the server computed, and added
     * {@link TerminalOrdersPayload}. An M11 client reading the new stock entries with the old decoder would take the
     * amount's bytes for the next entry's item id, which is exactly the silent misreading this version string exists
     * to prevent.
     * <p>
     * {@code "5"}: M15 (stock rules, issue #3) added {@code RESERVED} to {@code RequestRejection} — a constant an
     * older client would decode as another reason, because {@link TerminalResultPayload} encodes it by ordinal — and
     * the two stock keeper payloads ({@link StockKeeperScreenPayload}, {@link StockKeeperRulePayload}). Part 2 of the
     * same milestone appended three {@code StockRuleStatus} values (also on the wire by ordinal, in both the terminal
     * stock entries and the keeper's screen rows) and gave both payloads a field each — a rule's storage cap and what
     * a paused rule's lost batch cost. Its terminal confirmation then added the acknowledgement to
     * {@link TerminalRequestPayload} — encoded as a tag, so an unknown one reads as "nothing accepted" and the server
     * asks instead of acting — and the new {@link TerminalConfirmPayload}. None of it needs a further bump:
     * {@code "5"} has never been released, so the whole of M15 ships behind one version string.
     * <p>
     * <b>{@code "6"} is M20</b> (recursive production, issue #4, ADR-032): {@code RequestRejection} gained
     * {@code PRODUCTION_PAUSED}, and that enum travels by <b>ordinal</b> in {@link TerminalResultPayload}. Appending a
     * constant shifts nothing, but an older client would read a reason it does not have at all, so the version string
     * makes it fail the connection instead of showing the wrong sentence. The same version covers the four fields an
     * order line gained ({@code ProductionScreenState.OrderView}: the plan it belongs to, its depth in it, its station's
     * address and whether it is waiting for an earlier step), which {@link TerminalOrdersPayload} and
     * {@code ProductionScreenPayload} both carry: they are appended at the end of a line, so a reader of the old shape
     * would stop one line in and desynchronise the rest of the packet. It also covers the stopped products a
     * {@code ProductionScreenPayload} carries after its orders and the new {@link ProductionResumePayload}, which an
     * older client would neither send nor understand. Part 2 appends two more fields to
     * {@link TerminalResultPayload} — the {@code PlanRefusal} behind a refusal and the <b>item it names</b>,
     * which is regularly not the item that was clicked. That enum travels by <b>name</b>, like a
     * {@code StoppedProduct}'s cause, so its declaration order stays free and a client on another build reads an
     * unknown refusal as none rather than as a shifted one. They are written after everything M15 sent, and the item
     * only behind a refusal, so an answer with no plan behind it costs one empty string; a reader of the old shape
     * would still stop at the end of the known fields and leave the rest in the buffer, which is exactly what this
     * version string prevents.
     * <p>
     * <b>{@code "7"} is M23</b> (order a whole list from a clipboard, issue #19, ADR-036): the three list payloads
     * ({@link TerminalListPayload}, {@link TerminalListAnswerPayload}, {@link TerminalListActionPayload}), which an
     * older client would neither send nor understand, and one appended field on {@link TerminalConfirmPayload} — the
     * {@link dev.wareworks.core.terminal.RequestScope} that says whether the answer belongs to the click or to the
     * clipboard order. That field travels by <b>name</b> and is written last, so a reader of the old shape would stop
     * before it and leave bytes in the buffer, which is exactly what this version string prevents.
     * <p>
     * <b>{@code "8"} is M24</b> (sort the terminal's stock list, issue #17, ADR-037): the two sort payloads
     * ({@link TerminalUsagePayload} server → client, {@link TerminalSortPayload} client → server), which an older
     * client would neither send nor understand. Both carry the chosen order by <b>name</b>, and no existing payload's
     * field order moved, so the bump is for the two new types alone.
     * <p>
     * <b>{@code "9"} is M32</b> (crafting at the terminal, issue #24, slice 1, ADR-059): the three crafting payloads
     * ({@link TerminalCraftPayload} client → server, {@link TerminalCraftViewPayload} and
     * {@link TerminalCraftAnswerPayload} server → client), which an older client would neither send nor understand.
     * Their actions and results travel by <b>name</b>, and no existing payload's field order moved, so the bump is for
     * the three new types alone. Each later slice of the issue that changes the wire bumps it again.
     * <p>
     * <b>{@code "10"} is slice 2 of issue #24</b> (Fetch at the crafting panel, ADR-062): the two craft order payloads
     * ({@link TerminalCraftOrderPayload} client → server, {@link TerminalCraftOrderStatePayload} server →
     * client), which an older client would neither send nor understand; two fields appended to
     * {@link TerminalCraftAnswerPayload} (the plan refusal by name and the measured question) and one to the
     * clipboard order's {@link TerminalListPayload} (why its last portion was refused), which a reader of the old shape
     * would stop before and leave in the buffer; the Fetch's values of {@code TerminalCraftResult} and the scope
     * {@link dev.wareworks.core.terminal.RequestScope#CRAFT} on {@link TerminalConfirmPayload}, both by name, which an
     * older client would read as a plain refusal and as a click — and answer in the wrong place; and the question an
     * answer was given to, in {@link TerminalListActionPayload} as in the craft order's payload, before the
     * acknowledgement, which a reader of the old shape would take for the acknowledgement's tag.
     * <p>
     * <b>{@code "11"} is slice 3 of issue #24</b> (missing ingredients and the chain offer): the Fetch question in
     * {@link TerminalCraftAnswerPayload} gained two fields after its named lines — why no production chain makes up
     * for the ingredient that binds, by name, and the item that reason names — which a reader of the old shape would
     * stop before and leave in the buffer. No new payload type.
     * <p>
     * <b>{@code "12"} is slice 4 of issue #24</b> (a recipe viewer's transfer button lays a recipe into the crafting
     * panel): the new {@link TerminalCraftLayPayload} (client → server), which an older server would not understand;
     * one field appended to {@link TerminalCraftViewPayload} — the grid the server laid, with its number, recipe and
     * whether to fold the panel out — which a reader of the old shape would stop before and leave in the buffer; and
     * the values {@code LAID} and {@code NOT_LAID} of {@code TerminalCraftResult}, by name, which an older client
     * would read as a plain refusal.
     * <p>
     * <b>{@code "13"} is M27</b> (order to a package door from the warehouse terminal, issue #18, ADR-065 D12): the
     * package door a click delivers to, as an optional block position, in {@link TerminalRequestPayload} before the
     * acknowledgement — which a reader of the old shape would take for the acknowledgement's tag — echoed in
     * {@link TerminalConfirmPayload} before the scope and in {@link TerminalResultPayload} right after the rejection,
     * both of which a reader of the old shape would misread from there on; the new {@link TerminalDoorsPayload} (server →
     * client), which an older client would not understand; and the two constants {@code NOT_A_DOOR} and
     * {@code DOOR_IGNORES_REDSTONE} appended to {@code RequestRejection}, which travels by ordinal and which an older
     * client would read as no reason at all. Nothing is saved for any of it.
     * <p>
     * <b>{@code "14"} is issue #30</b>: a click with an item on a storage filter slot (Warehouse Interface, Rack Bay,
     * Fluid Bay) is applied by the server on the press ({@code StorageFilterClicks}), and the client no longer starts
     * Create's warmup or sends its release {@code ValueSettingsPacket} for it. No payload's bytes changed, but the two
     * sides disagree about who applies the click: a newer client on an older server would set nothing and say nothing.
     * So the versions must not mix.
     * <p>
     * <b>{@code "15"} is issue #34</b> (the Warehouse Crafter Feeder, ADR-067): {@link ProductionScreenPayload}'s
     * {@code ProductionScreenState} carries the crafter feeder flag and one pattern check per slot, appended after the
     * stopped products, which a reader of the old shape would stop before and leave in the buffer; the check travels by
     * ordinal ({@code CrafterPatternCheck}), which an older client could not read at all. No new payload type.
     */
    public static final String VERSION = "15";

    private WareworksNetwork() {
    }

    /** Mod-bus listener, added in the {@code Wareworks} constructor. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(TerminalStockPayload.TYPE, TerminalStockPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalStock);
        registrar.playToClient(TerminalStatusPayload.TYPE, TerminalStatusPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalStatus);
        registrar.playToClient(TerminalResultPayload.TYPE, TerminalResultPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalResult);
        registrar.playToClient(TerminalOrdersPayload.TYPE, TerminalOrdersPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalOrders);
        registrar.playToClient(TerminalConfirmPayload.TYPE, TerminalConfirmPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalConfirm);
        registrar.playToClient(TerminalDoorsPayload.TYPE, TerminalDoorsPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalDoors);
        registrar.playToServer(TerminalRequestPayload.TYPE, TerminalRequestPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalRequest);
        registrar.playToClient(TerminalListPayload.TYPE, TerminalListPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalList);
        registrar.playToClient(TerminalListAnswerPayload.TYPE, TerminalListAnswerPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalListAnswer);
        registrar.playToServer(TerminalListActionPayload.TYPE, TerminalListActionPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalListAction);
        registrar.playToClient(TerminalUsagePayload.TYPE, TerminalUsagePayload.STREAM_CODEC,
                WareworksNetwork::onTerminalUsage);
        registrar.playToServer(TerminalSortPayload.TYPE, TerminalSortPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalSort);
        registrar.playToServer(TerminalCraftPayload.TYPE, TerminalCraftPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalCraft);
        registrar.playToClient(TerminalCraftViewPayload.TYPE, TerminalCraftViewPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalCraftView);
        registrar.playToClient(TerminalCraftAnswerPayload.TYPE, TerminalCraftAnswerPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalCraftAnswer);
        registrar.playToServer(TerminalCraftOrderPayload.TYPE, TerminalCraftOrderPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalCraftOrder);
        registrar.playToClient(TerminalCraftOrderStatePayload.TYPE, TerminalCraftOrderStatePayload.STREAM_CODEC,
                WareworksNetwork::onTerminalCraftOrderState);
        registrar.playToServer(TerminalCraftLayPayload.TYPE, TerminalCraftLayPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalCraftLay);
        registrar.playToClient(ProductionScreenPayload.TYPE, ProductionScreenPayload.STREAM_CODEC,
                WareworksNetwork::onProductionScreen);
        registrar.playToServer(ProductionPatternPayload.TYPE, ProductionPatternPayload.STREAM_CODEC,
                WareworksNetwork::onProductionPattern);
        registrar.playToServer(ProductionCancelPayload.TYPE, ProductionCancelPayload.STREAM_CODEC,
                WareworksNetwork::onProductionCancel);
        registrar.playToServer(ProductionResumePayload.TYPE, ProductionResumePayload.STREAM_CODEC,
                WareworksNetwork::onProductionResume);
        registrar.playToClient(StockKeeperScreenPayload.TYPE, StockKeeperScreenPayload.STREAM_CODEC,
                WareworksNetwork::onStockKeeperScreen);
        registrar.playToServer(StockKeeperRulePayload.TYPE, StockKeeperRulePayload.STREAM_CODEC,
                WareworksNetwork::onStockKeeperRule);
    }

    // --- server --------------------------------------------------------------------------------------------------

    /**
     * A player asked their open terminal screen for items, delivered here or to one of the warehouse's package doors
     * (M27, issue #18, ADR-065). The payload is only a hint: the menu the player really has open decides, and the
     * terminal validates player, amount, aisle, door and item against the server's own state.
     * <p>
     * A cost the player has not accepted is answered with the question and nothing else: no request was made (M15
     * part 2, docs/warehouse-system.md §3.6.6). The screen puts it up, and a confirmation comes back as another request
     * payload, which is measured again. Either answer names the door the server measured the click for
     * ({@link TerminalRequestAnswers}).
     */
    private static void onTerminalRequest(TerminalRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        Optional<TerminalRequestOutcome> outcome = WarehouseTerminalMenu.submitRequest(player, payload.containerId(),
                payload.key(), payload.amount(), payload.acknowledged(), payload.door());
        if (outcome.isEmpty() || !(player instanceof ServerPlayer serverPlayer))
            return;
        PacketDistributor.sendToPlayer(serverPlayer, TerminalRequestAnswers.answer(payload, outcome.get()));
    }

    /**
     * A player pressed one of the clipboard order's buttons in their open terminal screen (M23, issue #19,
     * {@code docs/warehouse-system.md} §3.4.4).
     * <p>
     * The payload is only a hint: the menu the player really has open decides which terminal is meant, the terminal
     * re-validates reach and aisle, and it acts on the clipboard in its <b>own</b> list slot. A Fetch whose three
     * numbers do not cover what the list really costs is answered with the question and nothing else — no request, no
     * production order, no promise — exactly as a click that crosses a reserve is ({@code §3.6.6}).
     */
    private static void onTerminalListAction(TerminalListActionPayload payload, IPayloadContext context) {
        Player player = context.player();
        Optional<TerminalListOutcome> outcome = WarehouseTerminalMenu.submitListAction(player, payload.containerId(),
                payload.action(), payload.acceptedMissing(), payload.acceptedProducing(), payload.acceptedDropped(),
                payload.acknowledged(), payload.question());
        if (outcome.isEmpty() || !(player instanceof ServerPlayer serverPlayer))
            return;
        PacketDistributor.sendToPlayer(serverPlayer, new TerminalListAnswerPayload(payload.containerId(),
                outcome.get().result(), outcome.get().question()));
    }

    /**
     * A player pressed the sort button on their open terminal screen (M24, issue #17, ADR-037).
     * <p>
     * The payload is only a hint: the menu the player really has open decides, the order is read by name and an
     * unknown one reads as the default. Nothing is requested and no item moves — this stores one enum constant on that
     * player, so their terminals open in the same order next time, on any terminal and after a reconnect.
     */
    private static void onTerminalSort(TerminalSortPayload payload, IPayloadContext context) {
        WarehouseTerminalMenu.submitSort(context.player(), payload.containerId(), payload.sort());
    }

    /**
     * A player used the crafting panel of their open terminal screen (M32, issue #24, ADR-059): a changed grid to
     * preview, or a click on the result to craft.
     * <p>
     * The payload is only a hint: the menu the player really has open decides which terminal is meant, the terminal
     * re-validates reach, and the recipe is resolved on the server for the grid's exact keys. A preview is answered by
     * the menu's next push ({@link TerminalCraftViewPayload}); a craft is answered here, to the acting player only.
     * The crafted items need no packet of their own: they are in the menu's slots, which vanilla syncs.
     */
    private static void onTerminalCraft(TerminalCraftPayload payload, IPayloadContext context) {
        Player player = context.player();
        Optional<TerminalCraftOutcome> outcome = WarehouseTerminalMenu.submitCraft(player, payload.containerId(),
                payload.action(), payload.cells(), payload.hint());
        if (outcome.isEmpty() || !(player instanceof ServerPlayer serverPlayer))
            return;
        PacketDistributor.sendToPlayer(serverPlayer, TerminalCraftAnswerPayload.of(payload.containerId(),
                outcome.get()));
    }

    /**
     * A player pressed Fetch on the crafting panel of their open terminal screen, or one of the buttons of the craft
     * order it started (issue #24, slice 2, ADR-062).
     * <p>
     * The payload is only a hint: the menu the player really has open decides which terminal is meant, the terminal
     * re-validates reach and aisle, the recipe is resolved on the server, and the whole fetch is measured on the server
     * before anything is requested. A Fetch whose consent does not cover what it would really bring is answered with
     * the question and nothing else — no request, no promise — exactly as a clipboard's Fetch is. The answer goes to
     * the acting player only; the order's state reaches every screen at the terminal with the menus' own pushes.
     */
    private static void onTerminalCraftOrder(TerminalCraftOrderPayload payload, IPayloadContext context) {
        Player player = context.player();
        Optional<TerminalCraftOutcome> outcome = WarehouseTerminalMenu.submitCraftOrder(player, payload);
        if (outcome.isEmpty() || !(player instanceof ServerPlayer serverPlayer))
            return;
        PacketDistributor.sendToPlayer(serverPlayer, TerminalCraftAnswerPayload.of(payload.containerId(),
                outcome.get()));
    }

    /**
     * A recipe viewer laid a recipe into the crafting panel of the player's open terminal screen (issue #24, slice 4).
     * <p>
     * The payload is only a hint: the menu the player really has open decides which terminal is meant and must be the
     * terminal the payload names, the terminal re-validates reach and the lock of a running craft order, the server
     * picks one item per cell from the payload's candidates by its own buffer and stock, and the picked grid is checked
     * against the server's recipe manager. Nothing moves and nothing is requested. The answer goes to the acting player
     * only; the laid grid itself follows with the menu's next push ({@link TerminalCraftViewPayload#lay()}).
     */
    private static void onTerminalCraftLay(TerminalCraftLayPayload payload, IPayloadContext context) {
        Player player = context.player();
        Optional<TerminalCraftOutcome> outcome = WarehouseTerminalMenu.submitCraftLay(player, payload);
        if (outcome.isEmpty() || !(player instanceof ServerPlayer serverPlayer))
            return;
        PacketDistributor.sendToPlayer(serverPlayer, TerminalCraftAnswerPayload.of(payload.containerId(),
                outcome.get()));
    }

    /**
     * A player edited a pattern in their open production station screen. The payload moves no item: the server writes
     * a ghost entry into the station's pattern slot after resolving the menu the player really has open
     * ({@code docs/warehouse-system.md} §3.5).
     */
    private static void onProductionPattern(ProductionPatternPayload payload, IPayloadContext context) {
        ProductionMenu.submitPattern(context.player(), payload.containerId(), payload.pattern(), payload.entry(),
                payload.key(), payload.count());
    }

    /**
     * A player gave up on a production order, from the station's pattern screen or from a warehouse terminal
     * ({@code docs/warehouse-system.md} §3.5, §3.4.2). One payload serves both screens because the request is the same
     * one — an order id — and because <b>which</b> screen may cancel it is not decided by the payload but by the menu
     * that player really has open: each of the two resolves the id against its own state and refuses everything else
     * (the station its own orders, the terminal its aisle's). A player with neither screen open reaches nothing, and a
     * menu that is not the named one answers empty without spending any budget, which is why trying both is safe.
     * <p>
     * Cancelling moves no item.
     */
    private static void onProductionCancel(ProductionCancelPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (ProductionMenu.submitCancel(player, payload.containerId(), payload.orderId()).isEmpty())
            WarehouseTerminalMenu.submitCancel(player, payload.containerId(), payload.orderId());
    }

    /**
     * A player lifted the <b>safety stop</b> from inside a production station's screen (M20, issue #4, ADR-032): the
     * stopped row says which of this station's products the warehouse has stopped making, and a click on it says that the
     * machine behind the station is worth another batch.
     * <p>
     * The payload carries no item ({@link ProductionResumePayload}): the station's own patterns decide what may be
     * resumed, so nothing here is trusted. It moves no item and answers the player with what was lifted and what it cost.
     */
    private static void onProductionResume(ProductionResumePayload payload, IPayloadContext context) {
        ProductionMenu.submitResume(context.player(), payload.containerId());
    }

    /**
     * A player edited a rule in their open warehouse stock keeper screen. The payload moves no item: the server writes
     * a ghost item and three clamped numbers into the keeper's row after resolving the menu the player really has open
     * ({@code docs/warehouse-system.md} §3.6).
     */
    private static void onStockKeeperRule(StockKeeperRulePayload payload, IPayloadContext context) {
        StockKeeperMenu.submitRule(context.player(), payload.containerId(), payload.row(), payload.field(),
                payload.key(), payload.value());
    }

    // --- client --------------------------------------------------------------------------------------------------

    private static void onTerminalStock(TerminalStockPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onStock(payload);
    }

    private static void onTerminalStatus(TerminalStatusPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onStatus(payload);
    }

    private static void onTerminalResult(TerminalResultPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onResult(payload);
    }

    private static void onTerminalOrders(TerminalOrdersPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onOrders(payload);
    }

    private static void onTerminalConfirm(TerminalConfirmPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onConfirm(payload);
    }

    /** The package doors a click at the open screen may deliver to (M27, issue #18). */
    private static void onTerminalDoors(TerminalDoorsPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onDoors(payload);
    }

    private static void onTerminalList(TerminalListPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onList(payload);
    }

    private static void onTerminalListAnswer(TerminalListAnswerPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onListAnswer(payload);
    }

    /** The order this player chose and how often they have asked for each item type (M24, issue #17). */
    private static void onTerminalUsage(TerminalUsagePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onUsage(payload);
    }

    /** What the open screen's crafting grid makes and what the aisle says about its items (M32, issue #24). */
    private static void onTerminalCraftView(TerminalCraftViewPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onCraftView(payload);
    }

    /** The answer to a craft click or a craft order action of the open screen (M32, issue #24). */
    private static void onTerminalCraftAnswer(TerminalCraftAnswerPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onCraftAnswer(payload);
    }

    /** How far the open screen's terminal has got with its craft order, and the grid it locks (issue #24). */
    private static void onTerminalCraftOrderState(TerminalCraftOrderStatePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.TerminalScreenUpdates.onCraftOrder(payload);
    }

    private static void onProductionScreen(ProductionScreenPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.ProductionScreenUpdates.onState(payload);
    }

    private static void onStockKeeperScreen(StockKeeperScreenPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.StockKeeperScreenUpdates.onState(payload);
    }
}
