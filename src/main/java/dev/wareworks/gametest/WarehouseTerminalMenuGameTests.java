package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.item.FixedSlotsItemHandler;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.StationBuffer;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.TerminalScreenStatus;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.StockCount;
import dev.wareworks.core.terminal.TerminalAmounts;
import dev.wareworks.network.TerminalOrdersPayload;
import dev.wareworks.network.TerminalRequestPayload;
import dev.wareworks.network.TerminalResultPayload;
import dev.wareworks.network.TerminalStatusPayload;
import dev.wareworks.network.TerminalStockPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of the warehouse terminal's <b>screen server side</b> ({@code docs/warehouse-system.md} §3.4.2, ADR-019):
 * the menu with its slots, the request path a {@code TerminalRequestPayload} takes, what happens to hostile payloads,
 * and the payload codecs.
 * <p>
 * The screen itself is checked by the {@code terminal} scenario of the dev harness (screenshots); everything a server
 * has to guarantee is here. The request payload is deliberately <b>not</b> handed to a fake network context: its only
 * job is to call {@link WarehouseTerminalMenu#submitRequest}, which is exactly what these tests call, with the same
 * mock players a real handler would pass on.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class WarehouseTerminalMenuGameTests {
    /** Config tests get their own batch: the tests of one batch run at the same time ({@link ConfigOverrides}). */
    private static final String CONFIG_BATCH = "wareworksTerminalMenuConfig";

    // --- single block layout (empty_7x5x7) ---
    private static final BlockPos CENTER = new BlockPos(3, BASE_Y, 3);

    // --- aisle layout (aisle_16x10x7) ---
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition LOG_RACK = new RackPosition(2, 0, Side.LEFT);
    /** A chest of many distinct item types, so the reported window really has to cut something. */
    private static final RackPosition FILLER_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final BlockPos OUTSIDE_TERMINAL = new BlockPos(12, BASE_Y, 0);
    /** The smallest window the config allows ({@code maxTerminalStockEntries}). */
    private static final int SMALL_WINDOW = 16;

    // --- production (M11, ADR-024): one log makes four planks, so the logs in stock bound what may be ordered ---
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final int LOGS_IN_STOCK = 16;
    private static final int LOG_PER_RUN = 1;
    private static final int PLANKS_PER_RUN = 4;
    private static final int PRODUCIBLE_PLANKS = LOGS_IN_STOCK / LOG_PER_RUN * PLANKS_PER_RUN;

    private static final int PLAYER_SLOTS = 36;
    private static final int DIAMONDS_IN_STOCK = 20;
    private static final int DELIVERED_GOLD = 24;
    private static final int FAR_AWAY_BLOCKS = 40;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int OTHER_CONTAINER_ID = 77;
    private static final int TICKS_WITHOUT_A_SERVER_PLAYER = 5;
    /** Smallest scaled screen vanilla allows at any GUI scale ({@code Window#calculateScale}). */
    private static final int MIN_SCALED_WIDTH = 320;
    private static final int MIN_SCALED_HEIGHT = 240;
    /** The largest buffer the config allows ({@code terminalBufferSlots}). */
    private static final int BIG_BUFFER_SLOTS = 27;
    /** More slots than the default config allows, as a save with higher used slots produces. */
    private static final int GROWN_SLOTS = 21;

    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);

    private WarehouseTerminalMenuGameTests() {
    }

    // --- menu ------------------------------------------------------------------------------------------------------

    /**
     * The menu's slots: the terminal's buffer (take out only) plus the player inventory, at the positions both sides
     * compute from {@link TerminalMenuLayout}, inside the window size vanilla guarantees at every GUI scale.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalMenuSlots(GameTestHelper helper) {
        helper.setBlock(CENTER, terminalState(Direction.NORTH));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, CENTER);
            Player player = playerAt(helper, CENTER);
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            TerminalMenuLayout layout = menu.layout();
            helper.assertValueEqual(menu.bufferSlots(), terminal.bufferSlots(), "buffer slots");
            helper.assertValueEqual(menu.slots.size(), terminal.bufferSlots() + PLAYER_SLOTS, "slot count");
            helper.assertTrue(TerminalMenuLayout.WIDTH <= MIN_SCALED_WIDTH, "the window fits the smallest GUI width");
            helper.assertValueEqual(MIN_SCALED_HEIGHT, TerminalMenuLayout.MAX_HEIGHT, "the layout's height budget");
            // Since M11 the window also carries the production section, and the stock grid pays for it (ADR-024).
            helper.assertValueEqual(layout.orderLines(), TerminalMenuLayout.MAX_ORDER_LINES,
                    "the default buffer leaves room for every production order line");
            helper.assertTrue(layout.gridRows() < TerminalMenuLayout.MAX_GRID_ROWS,
                    "the grid gave a row to the production section");
            // Every buffer size the config allows, not only the configured one: a bigger buffer takes rows from the
            // grid instead of pushing the window past the height vanilla guarantees at every GUI scale.
            for (int slots = StationBuffer.MIN_SLOTS; slots <= BIG_BUFFER_SLOTS; slots++) {
                TerminalMenuLayout sized = new TerminalMenuLayout(slots);
                helper.assertTrue(sized.height() <= MIN_SCALED_HEIGHT,
                        "a " + slots + " slot buffer fits the smallest GUI height: " + sized.height());
                helper.assertTrue(sized.gridRows() >= TerminalMenuLayout.MIN_GRID_ROWS,
                        "a " + slots + " slot buffer keeps a row of the grid");
                helper.assertTrue(sized.bufferRows() * sized.bufferColumns() >= slots,
                        "a " + slots + " slot buffer has a place for every slot");
                helper.assertValueEqual(sized.bufferSlotY(slots - 1) + TerminalMenuLayout.SLOT <= sized.statusY(), true,
                        "the last buffer slot stays above the status line");
                // The production section is the part that gives way when a buffer leaves no room for it at all.
                helper.assertTrue(sized.orderLines() >= 0 && sized.orderLines() <= TerminalMenuLayout.MAX_ORDER_LINES,
                        "a " + slots + " slot buffer has a sane number of order lines: " + sized.orderLines());
                if (sized.orderLines() > 0)
                    helper.assertTrue(
                            sized.orderLineY(sized.orderLines() - 1) + TerminalMenuLayout.LABEL_HEIGHT
                                    <= sized.playerLabelY(),
                            "the last order line of a " + slots + " slot buffer stays above the player inventory");
            }
            helper.assertValueEqual(new TerminalMenuLayout(BIG_BUFFER_SLOTS).bufferRows(), 3,
                    "a 27 slot buffer needs three rows");

            Slot first = menu.slots.get(0);
            helper.assertValueEqual(first.x, layout.bufferSlotX(0), "first buffer slot x");
            helper.assertValueEqual(first.y, layout.bufferSlotY(0), "first buffer slot y");
            helper.assertFalse(first.mayPlace(GOLD.toStack(1)), "nothing can be put into a terminal");
            Slot firstPlayerSlot = menu.slots.get(terminal.bufferSlots());
            helper.assertValueEqual(firstPlayerSlot.x, layout.playerSlotsX(), "first player slot x");
            helper.assertValueEqual(firstPlayerSlot.y, layout.playerSlotsY(), "first player slot y");

            // The crane delivers, the player takes out: a shift-click empties the buffer into the inventory.
            helper.assertTrue(terminal.insert(GOLD.toStack(DELIVERED_GOLD), false).isEmpty(), "delivery fits");
            helper.assertTrue(menu.slots.get(0).hasItem(), "the delivery shows up in the menu");
            ItemStack moved = menu.quickMoveStack(player, 0);
            helper.assertValueEqual(moved.getCount(), DELIVERED_GOLD, "shift-clicked out of the terminal");
            helper.assertValueEqual(terminal.bufferedItems().count(GOLD), 0L, "the buffer is empty now");
            helper.assertValueEqual(countIn(player, Items.GOLD_INGOT), DELIVERED_GOLD, "the player has the gold");
            helper.assertTrue(menu.quickMoveStack(player, terminal.bufferSlots()).isEmpty(),
                    "a shift-click in the inventory puts nothing into the terminal");
            helper.assertValueEqual(countIn(player, Items.GOLD_INGOT), DELIVERED_GOLD, "the gold stayed with the player");

            // Without a server player the throttled push does nothing at all (and never throws).
            for (int tick = 0; tick < TICKS_WITHOUT_A_SERVER_PLAYER; tick++)
                menu.broadcastChanges();

            helper.assertTrue(menu.stillValid(player), "the menu is valid while the player stands at the terminal");
            helper.getLevel().destroyBlock(helper.absolutePos(CENTER), false);
            helper.assertFalse(menu.stillValid(player), "a broken terminal closes its screen");
        }).thenSucceed();
    }

    /**
     * A terminal whose buffer grew past the configured slot count (a save used higher slots, {@code StationBuffer})
     * still builds a menu both sides agree on: the slot count the server announces decides, and a local handler of
     * another size can neither add nor remove slots. A client menu with fewer slots than the server's would throw while
     * it reads the first content packet, and one whose buffer shrinks while the screen is open would throw on every
     * slot read.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalMenuGrownBuffer(GameTestHelper helper) {
        helper.setBlock(CENTER, terminalState(Direction.NORTH));

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, CENTER);
            int configured = WareworksConfig.terminalBufferSlots();
            helper.assertValueEqual(terminal.bufferSlots(), configured, "a fresh terminal has the configured slots");
            helper.assertTrue(GROWN_SLOTS > configured, "the test needs a buffer above the configured count");

            terminal.loadWithComponents(bufferTag(registries, GROWN_SLOTS), registries);
            helper.assertValueEqual(terminal.bufferSlots(), GROWN_SLOTS, "a save with higher slots keeps them");

            Player player = playerAt(helper, CENTER);
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            helper.assertValueEqual(menu.bufferSlots(), GROWN_SLOTS, "the menu announces the grown count");
            helper.assertValueEqual(menu.slots.size(), GROWN_SLOTS + PLAYER_SLOTS, "slot count");
            helper.assertValueEqual(menu.slots.get(GROWN_SLOTS - 1).getItem().getCount(), 1, "the last buffer slot");
            helper.assertValueEqual(menu.slots.get(GROWN_SLOTS).x, menu.layout().playerSlotsX(),
                    "the player inventory starts right after the buffer");

            // What a client builds: its block entity always has the configured count, but the menu has the announced
            // one, so the slots the handler lacks must read as empty instead of shrinking the menu or throwing.
            IItemHandler clientSized = new FixedSlotsItemHandler(new ItemStackHandler(configured), GROWN_SLOTS);
            helper.assertValueEqual(clientSized.getSlots(), GROWN_SLOTS, "a smaller handler keeps the announced slots");
            helper.assertTrue(clientSized.getStackInSlot(GROWN_SLOTS - 1).isEmpty(), "a missing slot reads as empty");
            helper.assertValueEqual(clientSized.insertItem(GROWN_SLOTS - 1, GOLD.toStack(1), false).getCount(), 1,
                    "and takes nothing");
            helper.assertTrue(clientSized.extractItem(GROWN_SLOTS - 1, 1, false).isEmpty(), "and gives nothing");

            // Save data can also shrink a buffer while a screen is open (/data merge): reading must not throw.
            terminal.loadWithComponents(bufferTag(registries, 0), registries);
            helper.assertValueEqual(terminal.bufferSlots(), configured, "shrunk back to the configured count");
            helper.assertTrue(menu.slots.get(GROWN_SLOTS - 1).getItem().isEmpty(), "a vanished slot reads as empty");
            menu.broadcastChanges();
        }).thenSucceed();
    }

    // --- requests --------------------------------------------------------------------------------------------------

    /** The path of a request payload: the menu the player has open decides, and the terminal serves it. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalMenuRequestPath(GameTestHelper helper) {
        AisleFixture fixture = buildAisle(helper);

        helper.startSequence().thenWaitUntil(() -> fixture.assertReady(1, 0, 1)).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            Optional<RequestResult> result = WarehouseTerminalMenu.submitRequest(player, menu.containerId, DIAMOND, 5);
            helper.assertTrue(result.isPresent(), "the payload reached the terminal");
            helper.assertTrue(result.get().isAccepted(), "accepted: " + result.get());
            helper.assertValueEqual(result.get().request().orElseThrow().requested(), 5, "granted amount");

            WarehouseControllerBlockEntity controller = fixture.controller();
            helper.assertValueEqual(controller.requestsFor(fixture.absoluteRackPos(TERMINAL_RACK)).size(), 1,
                    "the controller queued exactly one request");
            helper.assertValueEqual(terminal.status().requestsHere(), 1, "the screen status shows it");
            helper.assertValueEqual(TerminalScreenStatus.of(terminal.status()).requestsHere(), 1,
                    "and so does the payload form");
        }).thenSucceed();
    }

    /**
     * Hostile payloads: no menu, another menu, a player who is out of reach, an item the index does not hold, an
     * amount of zero and an amount of {@code Integer.MAX_VALUE}, and a terminal that belongs to no aisle.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalMenuHostileRequests(GameTestHelper helper) {
        AisleFixture fixture = buildAisle(helper);

        helper.startSequence().thenWaitUntil(() -> fixture.assertReady(1, 0, 1)).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
            WarehouseControllerBlockEntity controller = fixture.controller();
            BlockPos terminalPos = fixture.absoluteRackPos(TERMINAL_RACK);
            Player player = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            // No menu open at all, and a menu whose id does not match: silently dropped, nothing is queued.
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, DIAMOND, 1).isEmpty(),
                    "a player without a terminal menu cannot request anything");
            player.containerMenu = menu;
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, OTHER_CONTAINER_ID, DIAMOND, 1).isEmpty(),
                    "a payload for another menu is ignored");
            Player stranger = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(stranger, menu.containerId, DIAMOND, 1).isEmpty(),
                    "another player cannot use this player's menu");
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(null, menu.containerId, DIAMOND, 1).isEmpty(),
                    "no player, no request");
            helper.assertValueEqual(controller.openRequestCount(), 0, "nothing was queued so far");

            // Amounts and items are settled by the terminal, not by the payload.
            helper.assertValueEqual(rejection(helper, player, menu, DIAMOND, 0), Optional.of(RequestRejection.INVALID_AMOUNT),
                    "amount 0");
            helper.assertValueEqual(rejection(helper, player, menu, DIAMOND, -1000),
                    Optional.of(RequestRejection.INVALID_AMOUNT), "a negative amount");
            helper.assertValueEqual(rejection(helper, player, menu, EMERALD, 1),
                    Optional.of(RequestRejection.NOT_IN_STOCK), "an item the aisle does not hold");
            ItemStack namedDiamond = DIAMOND.toStack(1);
            namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("not the indexed diamond"));
            helper.assertValueEqual(rejection(helper, player, menu, ItemKey.of(namedDiamond), 1),
                    Optional.of(RequestRejection.NOT_IN_STOCK), "an item with other components");
            helper.assertValueEqual(controller.openRequestCount(), 0, "still nothing queued");

            // A player who walked away keeps their menu for a moment; the terminal refuses them anyway.
            Player far = helper.makeMockPlayer(GameType.SURVIVAL);
            Vec3 center = Vec3.atCenterOf(terminalPos);
            far.moveTo(center.x + FAR_AWAY_BLOCKS, center.y, center.z);
            WarehouseTerminalMenu farMenu = openMenu(far, terminal);
            helper.assertValueEqual(rejection(helper, far, farMenu, DIAMOND, 1),
                    Optional.of(RequestRejection.OUT_OF_REACH), "too far away");
            helper.assertFalse(farMenu.stillValid(far), "and their menu is closed on the next tick");

            // A huge amount is clamped by the config cap and then by the stock.
            Optional<RequestResult> huge = WarehouseTerminalMenu.submitRequest(player, menu.containerId, DIAMOND,
                    Integer.MAX_VALUE);
            helper.assertTrue(huge.isPresent() && huge.get().isAccepted(), "accepted: " + huge);
            helper.assertValueEqual(huge.get().request().orElseThrow().requested(), DIAMONDS_IN_STOCK,
                    "clamped to what the aisle really holds");

            // A terminal outside every aisle answers with "no controller", whatever the payload says.
            helper.setBlock(OUTSIDE_TERMINAL, terminalState(Direction.NORTH));
            WarehouseTerminalBlockEntity outside = terminalAt(helper, OUTSIDE_TERMINAL);
            Player atOutside = playerAt(helper, OUTSIDE_TERMINAL);
            WarehouseTerminalMenu outsideMenu = openMenu(atOutside, outside);
            helper.assertValueEqual(rejection(helper, atOutside, outsideMenu, DIAMOND, 1),
                    Optional.of(RequestRejection.NO_CONTROLLER), "outside every aisle");
        }).thenIdle(1).thenExecute(() -> {
            // A flood of payloads in one tick is answered up to the menu's budget and then dropped, so a crafted
            // client cannot make the server work more often than a clicking player could.
            WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            for (int i = 0; i < WarehouseTerminalMenu.MAX_REQUESTS_PER_TICK; i++)
                helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, DIAMOND, 0).isPresent(),
                        "request " + i + " is inside the budget and is answered");
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, DIAMOND, 0).isEmpty(),
                    "the request after the budget is dropped");
        }).thenIdle(1).thenExecute(() -> {
            // The budget is per tick, so the next tick answers again.
            WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, DIAMOND, 0).isPresent(),
                    "a new tick, a new budget");
        }).thenSucceed();
    }

    // --- production (M11, ADR-024) -----------------------------------------------------------------------------------

    /**
     * A terminal offers what its aisle can <b>make</b>, not only what it holds: the producible item is in the snapshot
     * at zero stock with the amount the ingredients allow right now, ordering it starts a production order, and an
     * amount beyond what the ingredients allow is clamped instead of promised.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalProducibleItems(GameTestHelper helper) {
        AisleFixture fixture = buildProductionAisle(helper);

        helper.startSequence().thenWaitUntil(() -> {
            fixture.assertReady(2, 0, 1);
            helper.assertValueEqual(fixture.controller().countOf(LOG), (long) LOGS_IN_STOCK, "logs in stock");
            helper.assertValueEqual(fixture.controller().productionStations().size(), 1, "the station is recorded");
        }).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
            TerminalStockEntry planks = entry(helper, terminal, PLANK);
            helper.assertValueEqual(planks.total(), 0L, "no planks are stored");
            helper.assertTrue(planks.producible(), "but a pattern makes them, so the terminal offers them");
            helper.assertValueEqual(planks.producibleAmount(), (long) PRODUCIBLE_PLANKS,
                    "as many as the logs in stock allow");
            helper.assertValueEqual(planks.orderable(), (long) PRODUCIBLE_PLANKS, "which is what may be ordered");

            TerminalStockEntry logs = entry(helper, terminal, LOG);
            helper.assertFalse(logs.producible(), "an ingredient is not producible itself in stage 1");
            helper.assertValueEqual(logs.producibleAmount(), 0L, "so nothing of it can be made");

            // The "everything possible" amount is the server's number; the screen only applies it.
            helper.assertValueEqual(TerminalAmounts.amountFor(TerminalAmounts.Click.ALL, 1, PLANK.getMaxStackSize(),
                    planks.available(), planks.producibleAmount(), WareworksConfig.maxTerminalRequestAmount()),
                    PRODUCIBLE_PLANKS, "a control-click asks for everything that can be made");

            Player player = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            // What the screen really receives, not only what the block entity knows: the conversion into the pure
            // form is where the amount was dropped once, and neither a snapshot test nor a screen test could see it.
            StockCount<ItemKey> pushed = menu.stockCounts().stream().filter(count -> count.key().equals(PLANK))
                    .findFirst().orElse(null);
            if (pushed == null)
                helper.fail("the menu does not push the producible item");
            helper.assertTrue(pushed.producible(), "the pushed entry is marked producible");
            helper.assertValueEqual(pushed.producibleAmount(), (long) PRODUCIBLE_PLANKS,
                    "and carries the amount the server computed");
            helper.assertFalse(pushed.isGone(), "an offer at zero stock is not a removal");

            // More than the ingredients allow: clamped to what can really be made, never promised beyond it.
            Optional<RequestResult> huge = WarehouseTerminalMenu.submitRequest(player, menu.containerId, PLANK,
                    Integer.MAX_VALUE);
            helper.assertTrue(huge.isPresent() && huge.get().isAccepted(), "accepted: " + huge);
            helper.assertValueEqual(huge.get().granted(), PRODUCIBLE_PLANKS, "clamped to what the ingredients allow");
            helper.assertValueEqual(huge.get().producing(), PRODUCIBLE_PLANKS, "all of it is being made");
            helper.assertValueEqual(fixture.controller().openProductionOrders().size(), 1, "one production order");

            // The logs are promised to that order now, so a second one is refused instead of promising them twice.
            helper.assertValueEqual(rejection(helper, player, menu, PLANK, 1),
                    Optional.of(RequestRejection.NOT_IN_STOCK), "the ingredients are already spoken for");
            helper.assertValueEqual(rejection(helper, player, menu, EMERALD, 1),
                    Optional.of(RequestRejection.NOT_IN_STOCK), "an item no pattern makes is not producible");
            helper.assertValueEqual(fixture.controller().openProductionOrders().size(), 1, "still one order");
        }).thenSucceed();
    }

    /**
     * The terminal's production section and its cancel control: the terminal reports its <b>aisle's</b> orders, a
     * cancellation is re-validated against that aisle, and nothing a crafted payload says reaches another one.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalProductionOrders(GameTestHelper helper) {
        AisleFixture fixture = buildProductionAisle(helper);

        helper.startSequence().thenWaitUntil(() -> fixture.assertReady(2, 0, 1)).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
            WarehouseControllerBlockEntity controller = fixture.controller();
            Player player = playerAt(helper, fixture.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            Optional<RequestResult> ordered = WarehouseTerminalMenu.submitRequest(player, menu.containerId, PLANK,
                    PLANKS_PER_RUN);
            helper.assertTrue(ordered.isPresent() && ordered.get().isAccepted(), "the order was accepted: " + ordered);

            List<ProductionScreenState.OrderView> views = terminal.productionOrderViews();
            helper.assertValueEqual(views.size(), 1, "the screen is told about one order");
            ProductionScreenState.OrderView view = views.getFirst();
            helper.assertValueEqual(view.result(), PLANK, "what it makes");
            helper.assertValueEqual(view.amount(), PLANKS_PER_RUN, "how much of it");
            helper.assertValueEqual(view.state(), ProductionOrderState.WAITING_FOR_INGREDIENTS, "where it stands");
            helper.assertValueEqual(view.missing(), (long) LOG_PER_RUN, "ingredients still to fetch");
            UUID order = view.id();

            // Nothing without the right menu, and none of these spends the menu's per-tick budget.
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(WarehouseTerminalMenu.submitCancel(player, menu.containerId, order).isEmpty(),
                    "a player without a terminal menu cancels nothing");
            player.containerMenu = menu;
            helper.assertTrue(WarehouseTerminalMenu.submitCancel(player, OTHER_CONTAINER_ID, order).isEmpty(),
                    "a payload for another menu is ignored");
            helper.assertTrue(WarehouseTerminalMenu.submitCancel(player, menu.containerId, null).isEmpty(),
                    "a payload without an order is ignored");
            helper.assertTrue(WarehouseTerminalMenu.submitCancel(null, menu.containerId, order).isEmpty(),
                    "no player, no cancellation");

            // An order this aisle does not have is refused, and so is every order for a terminal that belongs to no
            // aisle at all: a crafted id cannot reach into another warehouse's orders.
            helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(player, menu.containerId, UUID.randomUUID()),
                    Optional.of(false), "an unknown order is refused");
            helper.setBlock(OUTSIDE_TERMINAL, terminalState(Direction.NORTH));
            WarehouseTerminalBlockEntity outside = terminalAt(helper, OUTSIDE_TERMINAL);
            Player atOutside = playerAt(helper, OUTSIDE_TERMINAL);
            WarehouseTerminalMenu outsideMenu = openMenu(atOutside, outside);
            helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(atOutside, outsideMenu.containerId, order),
                    Optional.of(false), "a terminal outside every aisle cancels nothing");
            helper.assertTrue(outside.productionOrderViews().isEmpty(), "and reports no orders either");
            helper.assertValueEqual(controller.openProductionOrders().size(), 1, "the order is intact");

            // A player who walked away keeps their menu for a moment; the terminal refuses them anyway.
            Player far = helper.makeMockPlayer(GameType.SURVIVAL);
            Vec3 center = Vec3.atCenterOf(fixture.absoluteRackPos(TERMINAL_RACK));
            far.moveTo(center.x + FAR_AWAY_BLOCKS, center.y, center.z);
            WarehouseTerminalMenu farMenu = openMenu(far, terminal);
            helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(far, farMenu.containerId, order),
                    Optional.of(false), "too far away to cancel");
            helper.assertValueEqual(controller.openProductionOrders().size(), 1, "still intact");

            // The real one goes through: the order ends, and the request it was made for stops waiting for items
            // nobody will make any more.
            helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(player, menu.containerId, order),
                    Optional.of(true), "the aisle's own order is cancelled");
            helper.assertValueEqual(controller.openProductionOrders().size(), 0, "nothing open any more");
            helper.assertValueEqual(controller.openRequestCount(), 0, "and the request got its amount back");
            helper.assertValueEqual(terminal.productionOrderViews().getFirst().state(), ProductionOrderState.CANCELLED,
                    "the line stays for a while, so a player can read what happened");
            helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(player, menu.containerId, order),
                    Optional.of(false), "a finished order cannot be cancelled again");
        }).thenSucceed();
    }

    /**
     * A producible offer has no stock, so {@code TerminalStockEntry.ORDER} — which leads with the stored amount —
     * sorts it behind every stocked entry, and a plain cut at {@code maxTerminalStockEntries} would drop exactly the
     * offers. On an aisle with more item types than the window the terminal would then silently offer nothing it can
     * make, with no message saying why. The offers are reserved from the cut instead (M11 review fix).
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalProducibleItemsSurviveTheCut(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTerminalStockEntries, SMALL_WINDOW);
        AisleFixture fixture = buildProductionAisle(helper);
        fixture.storage(FILLER_RACK, fillerStacks());

        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                fixture.controller().stockIndex().distinctKeys() > SMALL_WINDOW,
                "the aisle has to hold more item types than the window shows")).thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = fixture.terminalAt(TERMINAL_RACK);
                    List<TerminalStockEntry> snapshot = terminal.stockSnapshot();
                    helper.assertValueEqual(snapshot.size(), SMALL_WINDOW,
                            "the window still bounds what the screen is told");

                    TerminalStockEntry planks = entry(helper, terminal, PLANK);
                    helper.assertTrue(planks.producible(), "the offer survived the cut");
                    helper.assertValueEqual(planks.total(), 0L, "although it has no stock at all");
                    helper.assertValueEqual(planks.producibleAmount(), (long) PRODUCIBLE_PLANKS,
                            "with the amount the ingredients allow");
                    // And the row is not reported as gone either, which would delete it from the screen.
                    helper.assertTrue(terminal.holdsInStock(PLANK), "a producible key is never 'gone'");
                }).thenSucceed();
    }

    /** Restores the config even when a config-batch test fails before its own restore. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreTerminalMenuConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- payloads --------------------------------------------------------------------------------------------------

    /** Every terminal payload survives a round trip, and the stock payload never decodes more entries than it may. */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalPayloadCodecs(GameTestHelper helper) {
        List<StockCount<ItemKey>> entries = new ArrayList<>();
        for (int i = 0; i < TerminalStockPayload.MAX_ENTRIES + 10; i++)
            entries.add(new StockCount<>(DIAMOND, 100 + i, i));
        TerminalStockPayload stock = new TerminalStockPayload(3, true, entries);
        helper.assertValueEqual(stock.entries().size(), TerminalStockPayload.MAX_ENTRIES,
                "a payload never carries more entries than it may");

        TerminalStockPayload decodedStock = roundTrip(helper, stock, TerminalStockPayload.STREAM_CODEC);
        helper.assertValueEqual(decodedStock.containerId(), stock.containerId(), "stock container id");
        helper.assertTrue(decodedStock.reset(), "stock reset flag");
        helper.assertValueEqual(decodedStock.entries(), stock.entries(), "stock entries");
        TerminalStockPayload gone = roundTrip(helper, new TerminalStockPayload(3, false,
                List.of(StockCount.gone(GOLD))), TerminalStockPayload.STREAM_CODEC);
        helper.assertTrue(gone.entries().getFirst().isGone(), "an item type that left the index");

        TerminalScreenStatus status = new TerminalScreenStatus(true, ControllerStatus.READY, 'C', 12, 3456L, 4, 2, 64L,
                16L, true, CranePhase.TRAVEL_TO_TARGET, CranePauseReason.OVERSTRESSED);
        TerminalStatusPayload decodedStatus = roundTrip(helper, new TerminalStatusPayload(3, status),
                TerminalStatusPayload.STREAM_CODEC);
        helper.assertValueEqual(decodedStatus.status(), status, "status round trip");

        TerminalRequestPayload request = new TerminalRequestPayload(3, DIAMOND, 42);
        TerminalRequestPayload decodedRequest = roundTrip(helper, request, TerminalRequestPayload.STREAM_CODEC);
        helper.assertValueEqual(decodedRequest, request, "request round trip");

        TerminalResultPayload refused = roundTrip(helper, TerminalResultPayload.of(3, DIAMOND,
                RequestResult.rejected(RequestRejection.QUEUE_FULL)), TerminalResultPayload.STREAM_CODEC);
        helper.assertValueEqual(refused.rejection(), Optional.of(RequestRejection.QUEUE_FULL), "refusal round trip");
        helper.assertFalse(refused.isAccepted(), "a refusal is not accepted");
        // The reason travels as an enum ordinal, so the last constant proves the whole range survives the round trip.
        TerminalResultPayload capped = roundTrip(helper, TerminalResultPayload.of(3, DIAMOND,
                RequestResult.rejected(RequestRejection.REQUEST_FULL)), TerminalResultPayload.STREAM_CODEC);
        helper.assertValueEqual(capped.rejection(), Optional.of(RequestRejection.REQUEST_FULL),
                "the last reason of the enum");

        // An accepted answer carries two different numbers in a fixed order: what this call granted, then what the
        // request waits for now. The screen compares them to tell a merged click from a fresh one, so a swapped or
        // dropped field is a client-visible bug that only these two values can catch (a refusal has 0 in both).
        int granted = 1;
        int pending = 10;
        RetrievalRequest<ItemKey, BlockPos> open = new RetrievalRequest<>(UUID.randomUUID(), DIAMOND, pending, pending,
                BlockPos.ZERO);
        TerminalResultPayload merged = roundTrip(helper, TerminalResultPayload.of(3, DIAMOND,
                RequestResult.accepted(open, granted, true)), TerminalResultPayload.STREAM_CODEC);
        helper.assertTrue(merged.isAccepted(), "an accepted result");
        helper.assertValueEqual(merged.key(), DIAMOND, "the requested item");
        helper.assertValueEqual(merged.amount(), granted, "the granted increment");
        helper.assertValueEqual(merged.pending(), pending, "the pending total the screen names");

        // A producible item travels with the amount the server computed for it, and is not "gone" at zero stock
        // (M11, ADR-024): the client would otherwise drop the row of everything the aisle can only make.
        StockCount<ItemKey> offer = new StockCount<>(PLANK, 0L, 0L, true, PRODUCIBLE_PLANKS);
        TerminalStockPayload offered = roundTrip(helper, new TerminalStockPayload(3, false, List.of(offer)),
                TerminalStockPayload.STREAM_CODEC);
        helper.assertValueEqual(offered.entries().getFirst(), offer, "a producible entry round trip");
        helper.assertFalse(offered.entries().getFirst().isGone(), "an offer at zero stock is not gone");

        List<ProductionScreenState.OrderView> orders = new ArrayList<>();
        for (int i = 0; i < TerminalOrdersPayload.MAX_ORDERS + 3; i++)
            orders.add(new ProductionScreenState.OrderView(UUID.randomUUID(), ProductionOrderState.WAITING_FOR_RESULT,
                    PLANK, 8, 4L, 2L, 3L));
        TerminalOrdersPayload ordersPayload = new TerminalOrdersPayload(3, orders);
        helper.assertValueEqual(ordersPayload.orders().size(), TerminalOrdersPayload.MAX_ORDERS,
                "a payload never carries more orders than it may");
        TerminalOrdersPayload decodedOrders = roundTrip(helper, ordersPayload, TerminalOrdersPayload.STREAM_CODEC);
        helper.assertValueEqual(decodedOrders.orders(), ordersPayload.orders(), "order lines round trip");
        helper.succeed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Controller, dock with motor, rails, a chest of diamonds behind an interface and an aligned terminal. */
    private static AisleFixture buildAisle(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        fixture.terminal(TERMINAL_RACK);
        return fixture;
    }

    /**
     * The same aisle plus a chest of logs and a production station whose first pattern turns one log into
     * {@value #PLANKS_PER_RUN} planks. Deliberately <b>without</b> a motor: the crane never moves, so the tests see the
     * order exactly as it was created instead of racing a supply job.
     */
    private static AisleFixture buildProductionAisle(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK));
        fixture.storage(LOG_RACK, LOG.toStack(LOGS_IN_STOCK));
        fixture.terminal(TERMINAL_RACK);
        fixture.production(PRODUCTION_RACK);
        WarehouseProductionBlockEntity station = fixture.productionAt(PRODUCTION_RACK);
        helper.assertTrue(station.setPatternEntry(0, 0, LOG, LOG_PER_RUN), "the pattern's ingredient");
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, PLANKS_PER_RUN),
                "the pattern's result");
        return fixture;
    }

    /**
     * Twenty distinct item types for one chest, with distinct amounts so the order of the cut is deterministic. None
     * of them is an item another test in this class asserts about.
     */
    private static ItemStack[] fillerStacks() {
        Item[] items = {Items.STONE, Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.GLASS,
                Items.BRICK, Items.CLAY_BALL, Items.COAL, Items.CHARCOAL, Items.IRON_INGOT, Items.GOLD_NUGGET,
                Items.WHEAT, Items.BREAD, Items.APPLE, Items.STICK, Items.BONE, Items.STRING, Items.FEATHER,
                Items.LEATHER};
        ItemStack[] stacks = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++)
            stacks[i] = new ItemStack(items[i], 8 + i);
        return stacks;
    }

    /** The terminal's snapshot entry for {@code key}; fails the test when the terminal does not offer it. */
    private static TerminalStockEntry entry(GameTestHelper helper, WarehouseTerminalBlockEntity terminal, ItemKey key) {
        for (TerminalStockEntry entry : terminal.stockSnapshot()) {
            if (entry.key().equals(key))
                return entry;
        }
        helper.fail("the terminal does not offer " + key);
        throw new IllegalStateException("unreachable");
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    /** Sends a request through the payload entry point and returns why it was refused (or empty when accepted). */
    private static Optional<RequestRejection> rejection(GameTestHelper helper, Player player,
            WarehouseTerminalMenu menu, ItemKey key, int amount) {
        Optional<RequestResult> result = WarehouseTerminalMenu.submitRequest(player, menu.containerId, key, amount);
        if (result.isEmpty())
            helper.fail("the request was dropped instead of answered");
        return result.get().rejection();
    }

    /**
     * A block entity tag whose buffer holds one gold ingot in each of {@code slots} slots (the NBT shape
     * {@code StationBuffer#save} writes; the key names are repeated here on purpose, so a change to the save format
     * fails this test instead of passing silently).
     */
    private static CompoundTag bufferTag(HolderLookup.Provider registries, int slots) {
        ListTag items = new ListTag();
        for (int slot = 0; slot < slots; slot++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.put("Item", GOLD.save(registries));
            entry.putInt("Count", 1);
            items.add(entry);
        }
        CompoundTag buffer = new CompoundTag();
        buffer.putInt("Size", slots);
        buffer.put("Items", items);
        CompoundTag tag = new CompoundTag();
        tag.put(WarehouseStationBlockEntity.BUFFER_TAG, buffer);
        return tag;
    }

    private static <T> T roundTrip(GameTestHelper helper, T payload, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        codec.encode(buffer, payload);
        T decoded = codec.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the codec read everything it wrote");
        return decoded;
    }

    private static BlockState terminalState(Direction facing) {
        return WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState().setValue(WarehouseTerminalBlock.FACING, facing);
    }

    private static WarehouseTerminalBlockEntity terminalAt(GameTestHelper helper, BlockPos pos) {
        WarehouseTerminalBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse terminal block entity", pos);
        return be;
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static int countIn(Player player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }
}
