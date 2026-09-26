package dev.wareworks.network;

import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.StockKeeperMenu;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.terminal.RequestConfirmation;
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
     * constant inserted into an enum a payload encodes by ordinal ({@link dev.wareworks.content.controller.RequestRejection}).
     * The negotiation only compares this string, and a decoder that reads the fields in the old order fails open rather
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
     */
    public static final String VERSION = "5";

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
        registrar.playToServer(TerminalRequestPayload.TYPE, TerminalRequestPayload.STREAM_CODEC,
                WareworksNetwork::onTerminalRequest);
        registrar.playToClient(ProductionScreenPayload.TYPE, ProductionScreenPayload.STREAM_CODEC,
                WareworksNetwork::onProductionScreen);
        registrar.playToServer(ProductionPatternPayload.TYPE, ProductionPatternPayload.STREAM_CODEC,
                WareworksNetwork::onProductionPattern);
        registrar.playToServer(ProductionCancelPayload.TYPE, ProductionCancelPayload.STREAM_CODEC,
                WareworksNetwork::onProductionCancel);
        registrar.playToClient(StockKeeperScreenPayload.TYPE, StockKeeperScreenPayload.STREAM_CODEC,
                WareworksNetwork::onStockKeeperScreen);
        registrar.playToServer(StockKeeperRulePayload.TYPE, StockKeeperRulePayload.STREAM_CODEC,
                WareworksNetwork::onStockKeeperRule);
    }

    // --- server --------------------------------------------------------------------------------------------------

    /**
     * A player asked their open terminal screen for items. The payload is only a hint: the menu the player really has
     * open decides, and the terminal validates player, amount, aisle and item against the server's own state.
     */
    private static void onTerminalRequest(TerminalRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        Optional<TerminalRequestOutcome> outcome = WarehouseTerminalMenu.submitRequest(player, payload.containerId(),
                payload.key(), payload.amount(), payload.acknowledged());
        if (outcome.isEmpty() || !(player instanceof ServerPlayer serverPlayer))
            return;
        // A cost the player has not accepted is answered with the question and nothing else: no request was made
        // (M15 part 2, docs/warehouse-system.md §3.6.6). The screen puts it up, and a confirmation comes back as
        // another request payload — which is measured again.
        Optional<RequestConfirmation<ItemKey>> question = outcome.get().question();
        if (question.isPresent()) {
            PacketDistributor.sendToPlayer(serverPlayer,
                    new TerminalConfirmPayload(payload.containerId(), question.get()));
            return;
        }
        PacketDistributor.sendToPlayer(serverPlayer, TerminalResultPayload.of(payload.containerId(), payload.key(),
                outcome.get().result().orElseThrow()));
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

    private static void onProductionScreen(ProductionScreenPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.ProductionScreenUpdates.onState(payload);
    }

    private static void onStockKeeperScreen(StockKeeperScreenPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient())
            dev.wareworks.client.gui.StockKeeperScreenUpdates.onState(payload);
    }
}
