package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.terminal.TerminalAmounts;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "terminal": the warehouse terminal's screen, driven like a player ({@code docs/warehouse-system.md} §3.4.2).
 * <p>
 * It builds one aisle with a powered crane, a warehouse terminal beside the dock and a rack of chests holding
 * {@value #ITEM_TYPES} item types (from single items to several thousand, so the compact counts and the scrollbar are
 * on screen), then opens the real screen through {@code WarehouseTerminalBlockEntity#openScreen} and shoots it at five
 * moments: the full list, the list with a search typed character by character, right after a request was accepted,
 * after the same item was clicked {@value #MERGE_CLICKS} more times (the M7 merging case,
 * {@code docs/warehouse-system.md} §7.2: the status line names the pending total and "Open requests" stays 1), and
 * after the crane delivered the items into the terminal's buffer slots.
 * <p>
 * The camera stands inside the aisle, about two blocks from the terminal: a screen closes itself as soon as the player
 * leaves the vanilla container range, so the shots also prove that the menu stays open while the crane works. The second
 * render pass only opens the screen once and takes a single shot, because a GUI does not depend on Flywheel.
 */
public final class TerminalVisualScenario implements VisualScenario {
    public static final String NAME = "terminal";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    /** The terminal sits beside the dock, on the right rack side, so the camera can see it and the aisle behind it. */
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    /** The production station beside it: what makes the terminal offer an item the aisle does not hold (M11). */
    private static final RackPosition PRODUCTION = RackPosition.of(2, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 3;
    private static final int STORAGE_LEVELS = 2;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    /** Item types placed into the racks; the grid shows 36 cells, so this fills it and leaves room to scroll. */
    private static final List<ItemStack> STOCK = List.of(new ItemStack(Items.IRON_INGOT, 64),
            new ItemStack(Items.COPPER_INGOT, 64), new ItemStack(Items.GOLD_INGOT, 48),
            new ItemStack(Items.REDSTONE, 64), new ItemStack(Items.LAPIS_LAZULI, 32), new ItemStack(Items.DIAMOND, 24),
            new ItemStack(Items.EMERALD, 12), new ItemStack(Items.COAL, 64), new ItemStack(Items.QUARTZ, 40),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.GLASS, 48),
            new ItemStack(Items.WHEAT, 56), new ItemStack(Items.STRING, 16), new ItemStack(Items.BONE, 20),
            new ItemStack(Items.LEATHER, 8), new ItemStack(Items.PAPER, 64), new ItemStack(Items.BRICK, 36),
            new ItemStack(Items.FLINT, 5), new ItemStack(Items.SLIME_BALL, 3), new ItemStack(Items.ANDESITE, 64),
            new ItemStack(Items.SAND, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.KELP, 1));
    /** How many item types the racks hold; the screen must list at least this many. */
    private static final int ITEM_TYPES = 24;
    /** Stacks of the same item put into one chest, so a few entries show four-digit, compacted amounts. */
    private static final int STACKS_PER_LOCATION = 20;

    /**
     * The production pattern the station carries: one log makes {@value #PLANKS_PER_RUN} planks. Planks are
     * deliberately <b>not</b> in {@link #STOCK}, so the terminal offers an item the aisle holds none of (M11,
     * ADR-024), and logs are, so the offer has ingredients behind it.
     */
    private static final ItemKey INGREDIENT = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PRODUCT = ItemKey.of(Items.OAK_PLANKS);
    private static final int LOG_PER_RUN = 1;
    private static final int PLANKS_PER_RUN = 4;
    /** Narrows the grid to the producible item, so the marking is unmistakable in the shot. */
    private static final String PRODUCT_SEARCH = "plank";

    private static final String SEARCH_TEXT = "iron";
    /** The reported M7 case: the same item clicked ten more times while the first request is still open. */
    private static final int MERGE_CLICKS = 10;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int REQUEST_TIMEOUT_TICKS = 400;
    private static final int DELIVERY_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 4;

    /** In the aisle, two blocks from the terminal, looking at its screen; inside the vanilla container range. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.1, 1.6, 0.5, 1.4, 0.9, 1.2);

    /** The item of the first request; the repeated clicks look it up by key, not by grid position. */
    @Nullable
    private ItemKey requestedKey;
    /** The status line before the repeated clicks, so the step after them waits for the new answer. */
    private String feedbackBeforeMerge = "";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("terminal: clear the area and place the creative motor", TerminalVisualScenario::placeMotor)
                .server("terminal: build the aisle, the terminal and the stocked racks",
                        TerminalVisualScenario::buildAisle)
                .serverUntil("terminal: wait until the controller has indexed every rack",
                        TerminalVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("terminal: open the terminal screen", TerminalVisualScenario::openScreen)
                .until("terminal: wait for the screen with its stock", TerminalVisualScenario::screenReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("list");
        if (pass == VisualPass.FLYWHEEL) {
            script.client("terminal: type '" + SEARCH_TEXT + "' into the search box",
                            context -> typeSearch(context, SEARCH_TEXT))
                    .waitTicks(SETTLE_TICKS)
                    .shot("search")
                    .client("terminal: clear the search", context -> screen(context).setSearch(""))
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: request a stack of the first item", this::requestFirstItem)
                    .until("terminal: wait until the server accepted the request",
                            context -> screen(context).status().requestsHere() > 0, REQUEST_TIMEOUT_TICKS)
                    .client("terminal: check that the status texts fit their rows",
                            TerminalVisualScenario::checkStatusFits)
                    .shot("request");
            // One click per tick, the way a player clicks: a menu answers at most
            // WarehouseTerminalMenu.MAX_REQUESTS_PER_TICK requests per tick, so ten clicks fired inside a single tick
            // would be answered eight times and the shot would show a total that no player can produce.
            for (int click = 1; click <= MERGE_CLICKS; click++)
                script.client("terminal: click the same item again (" + click + " of " + MERGE_CLICKS + ")",
                        this::requestSameItemOnce);
            script.until("terminal: wait until the merged answer is on screen", this::mergedAnswerShown,
                            REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check that the merged status texts fit their rows",
                            TerminalVisualScenario::checkStatusFits)
                    .shot("merged")
                    .serverUntil("terminal: wait until the crane delivered into the terminal",
                            TerminalVisualScenario::delivered, DELIVERY_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check that the status texts still fit their rows",
                            TerminalVisualScenario::checkStatusFits)
                    .shot("delivered")
                    // Production (M11, ADR-024): the terminal offers an item the aisle can only make, ordering it
                    // starts an order, and the section under the status line is where its states are read.
                    .client("terminal: search for the producible item",
                            context -> typeSearch(context, PRODUCT_SEARCH))
                    .waitTicks(SETTLE_TICKS)
                    .client("terminal: check that the producible item is offered at zero stock",
                            TerminalVisualScenario::checkProducibleOffered)
                    .shot("producible")
                    .client("terminal: order everything that can be made",
                            TerminalVisualScenario::orderProducible)
                    .until("terminal: wait until the production order is on screen",
                            context -> orderState(context) != null, REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("ordered")
                    .serverUntil("terminal: wait until the crane delivered the ingredients",
                            TerminalVisualScenario::ingredientsDelivered, DELIVERY_TIMEOUT_TICKS)
                    .until("terminal: wait until the screen shows the delivered state",
                            context -> orderState(context) == ProductionOrderState.DELIVERED, REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("orderdelivered")
                    .client("terminal: give up on the order", TerminalVisualScenario::cancelOrder)
                    .until("terminal: wait until the screen shows it cancelled",
                            context -> orderState(context) == ProductionOrderState.CANCELLED, REQUEST_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("ordercancelled")
                    .client("terminal: clear the search after the production shots",
                            context -> screen(context).setSearch(""));
        }
        script.client("terminal: close the screen", TerminalVisualScenario::closeScreen)
                .until("terminal: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (!(open instanceof WarehouseTerminalScreen terminal))
            return "screen=" + (open == null ? "none" : open.getClass().getSimpleName());
        return String.format(Locale.ROOT,
                "screen=terminal entries=%d shown=%d requestsHere=%d requestedHere=%d openRequests=%d orders=%d "
                        + "orderState=%s feedback='%s' status='%s' statusFits=%s",
                terminal.matchingEntries().size(), terminal.visibleEntries().size(), terminal.status().requestsHere(),
                terminal.status().requestedHere(), terminal.status().openRequests(),
                terminal.productionOrders().size(), newestOrderState(terminal), feedbackText(terminal),
                terminal.shownStatusLine().getString(), terminal.statusTextsFit());
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(PRODUCTION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, layout.sideDirection(PRODUCTION.side()).getOpposite()));
        WarehouseProductionBlockEntity station = production(level, dock);
        if (!station.setPatternEntry(0, 0, INGREDIENT, LOG_PER_RUN)
                || !station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PRODUCT, PLANKS_PER_RUN))
            throw new VisualTestException("the production pattern could not be written");

        int next = 0;
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                for (int y = 0; y < STORAGE_LEVELS; y++) {
                    BlockPos rack = layout.rackPos(RackPosition.of(x, y, side));
                    BlockPos chest = rack.relative(outward);
                    level.setBlockAndUpdate(chest,
                            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                    level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                            .setValue(WarehouseInterfaceBlock.FACING, outward));
                    if (next < STOCK.size())
                        fillChest(level, chest, STOCK.get(next++));
                }
            }
        }
        if (next < STOCK.size())
            throw new VisualTestException("only " + next + " of " + STOCK.size() + " item types fit into the racks");
    }

    /** One item type per chest: several stacks of it, so the grid shows amounts well above one stack. */
    private static void fillChest(ServerLevel level, BlockPos chest, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("the chest at " + chest + " has no item handler");
        int stacks = stack.getCount() >= stack.getMaxStackSize() ? STACKS_PER_LOCATION : 1;
        for (int i = 0; i < stacks; i++) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
            if (!rest.isEmpty())
                break; // the chest is full; the remaining stacks are not needed for the picture
        }
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == 1 && crane.isControllerLinked()
                && controller.stockIndex().distinctKeys() >= ITEM_TYPES
                // The station is recorded and its pattern is readable, so the terminal really offers the product.
                && controller.productionStations().size() == 1 && controller.producibleKeys().contains(PRODUCT);
    }

    private static void openScreen(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context.origin());
        ServerPlayer player = context.serverPlayer(server);
        if (!terminal.openScreen(player))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
        LOGGER.info(PREFIX + "terminal: opened the screen with {} item types in stock",
                terminal.stockSnapshot().size());
    }

    private static boolean delivered(MinecraftServer server, VisualContext context) {
        return terminal(server.overworld(), context.origin()).bufferedItems().totalItems() > 0;
    }

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseProductionBlockEntity production(ServerLevel level, BlockPos dock) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                layout(dock).rackPos(PRODUCTION));
        if (station == null)
            throw new VisualTestException("the production station of the aisle is missing");
        return station;
    }

    /** Whether the crane has brought every ingredient of a production order to the station (server side). */
    private static boolean ingredientsDelivered(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), context.origin().relative(AISLE.getOpposite()));
        return controller != null && controller.productionOrders().stream()
                .anyMatch(order -> order.state() == ProductionOrderState.DELIVERED);
    }

    // --- screen (client thread) ------------------------------------------------------------------------------------

    private static boolean screenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && !terminal.matchingEntries().isEmpty();
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + open + ")");
    }

    /** Types the text character by character, exactly as a player would, and logs what the search kept. */
    private static void typeSearch(VisualContext context, String text) {
        WarehouseTerminalScreen terminal = screen(context);
        terminal.focusSearch();
        for (char typed : text.toCharArray())
            terminal.charTyped(typed, 0);
        LOGGER.info(PREFIX + "terminal: search '{}' keeps {} of the entries", text, terminal.matchingEntries().size());
    }

    /** One stack of the first item in the grid; its key is remembered, so the repeated clicks hit the same item. */
    private void requestFirstItem(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        StockLine<ItemKey> first = terminal.visibleEntries().stream().findFirst()
                .orElseThrow(() -> new VisualTestException("the terminal screen shows no item to request"));
        requestedKey = first.key();
        if (!terminal.requestVisible(0, TerminalAmounts.Click.STACK))
            throw new VisualTestException("the terminal screen shows no item to request");
        LOGGER.info(PREFIX + "terminal: requested one stack of {}", first.name());
    }

    /**
     * One more click on the item of the first request, the reported M7 case clicked exactly as a player does. The item
     * is looked up by its remembered key instead of by grid position, because the grid is sorted by amount and
     * re-orders as soon as the crane takes items out of a chest.
     */
    private void requestSameItemOnce(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        feedbackBeforeMerge = feedbackText(terminal);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        int cell = -1;
        for (int index = 0; index < visible.size() && cell < 0; index++) {
            if (visible.get(index).key().equals(requestedKey))
                cell = index;
        }
        if (cell < 0)
            throw new VisualTestException("the item of the first request left the visible grid");
        if (!terminal.requestVisible(cell, TerminalAmounts.Click.SELECTED))
            throw new VisualTestException("the repeated click could not be sent");
    }

    /**
     * Fails the run when a text of the two rows below the buffer does not fit its row. The M7 merged answer used to be
     * drawn straight through the right-aligned "Open requests" of the same row, which no screenshot can be trusted to
     * show — so the run asserts the widths instead of only photographing them ({@code docs/warehouse-system.md}
     * §3.4.2).
     */
    private static void checkStatusFits(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (!terminal.statusTextsFit())
            throw new VisualTestException(
                    "the terminal's status texts do not fit their rows: '" + terminal.shownStatusLine().getString()
                            + "' (window " + TerminalMenuLayout.WIDTH + " px wide)");
    }

    /** Whether the answer to the repeated clicks has replaced the first one in the status line. */
    private boolean mergedAnswerShown(VisualContext context) {
        String shown = feedbackText(screen(context));
        return !shown.isEmpty() && !shown.equals(feedbackBeforeMerge);
    }

    private static String feedbackText(WarehouseTerminalScreen terminal) {
        return terminal.feedbackLine().map(Component::getString).orElse("");
    }

    // --- production (M11, ADR-024) -----------------------------------------------------------------------------------

    /**
     * Fails the run unless the terminal really offers the producible item <b>at zero stock</b>, marked as producible
     * and with an amount behind it. A screenshot alone could not tell the marking from a normal empty cell, so the
     * claim the shot illustrates is asserted first.
     */
    private static void checkProducibleOffered(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        StockLine<ItemKey> line = terminal.entry(PRODUCT)
                .orElseThrow(() -> new VisualTestException("the terminal does not offer the producible item"));
        if (line.total() > 0L || !line.producible() || line.producibleAmount() <= 0L)
            throw new VisualTestException("the producible item is not offered as one: " + line);
        if (terminal.visibleEntries().stream().noneMatch(entry -> entry.key().equals(PRODUCT)))
            throw new VisualTestException("the producible item is not in the visible grid");
        LOGGER.info(PREFIX + "terminal: {} is offered at zero stock, {} of it can be made now", line.name(),
                line.producibleAmount());
    }

    /** Control-clicks the producible item, i.e. asks for everything the ingredients in stock allow. */
    private static void orderProducible(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        List<StockLine<ItemKey>> visible = terminal.visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (!visible.get(cell).key().equals(PRODUCT))
                continue;
            if (!terminal.requestVisible(cell, TerminalAmounts.Click.ALL))
                throw new VisualTestException("the producible item could not be ordered");
            LOGGER.info(PREFIX + "terminal: ordered everything that can be made of {}", visible.get(cell).name());
            return;
        }
        throw new VisualTestException("the producible item left the visible grid before it could be ordered");
    }

    /** Gives up on the newest order through the screen's own cancel control. */
    private static void cancelOrder(VisualContext context) {
        if (!screen(context).cancelVisibleOrder(0))
            throw new VisualTestException("the production order could not be cancelled from the screen");
    }

    /** The state of the newest order the screen knows about, or {@code null} while it knows none. */
    @Nullable
    private static ProductionOrderState orderState(VisualContext context) {
        List<ProductionScreenState.OrderView> orders = screen(context).productionOrders();
        return orders.isEmpty() ? null : orders.getLast().state();
    }

    private static String newestOrderState(WarehouseTerminalScreen terminal) {
        List<ProductionScreenState.OrderView> orders = terminal.productionOrders();
        return orders.isEmpty() ? "none" : orders.getLast().state().name();
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }
}
