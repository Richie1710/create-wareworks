package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;

import dev.wareworks.client.gui.WarehouseStockKeeperScreen;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.StockKeeperScreenState;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.network.StockKeeperRulePayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Scenario "keeper": the warehouse stock keeper and its rule screen ({@code docs/warehouse-system.md} §3.6, M15).
 * <p>
 * It builds one aisle with a stock keeper beside the dock and four stocked racks, and writes five rules that are
 * deliberately in <b>five different states</b> — below its minimum, at its maximum, down to its reserve, satisfied and
 * shadowed by an earlier rule for the same item — so that one screenshot shows every colour the screen can draw and
 * one world shot shows the lamp burning. The server asserts every one of those states <b>before</b> a shot is taken,
 * the way the filters scenario does: a screenshot cannot tell a lit lamp from a wrong one.
 * <p>
 * The screen is then edited through the real payload path — the client sends a {@code StockKeeperRulePayload}, the
 * server clamps it and pushes the whole list back — so the shots also prove that a rule a player scrolls to really
 * arrives, and that the numbers on screen are the server's and not the client's guess.
 * <p>
 * The camera stands in the aisle, about two blocks from the keeper: a screen closes itself as soon as the player
 * leaves the vanilla container range.
 */
public final class StockKeeperVisualScenario implements VisualScenario {
    public static final String NAME = "keeper";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 6;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    /** The keeper sits beside the dock, on the right rack side, where the camera sees its panel and the aisle. */
    private static final RackPosition KEEPER = RackPosition.of(1, 0, Side.RIGHT);
    /** The stocked racks, one item type each. */
    private static final List<RackPosition> STORAGE = List.of(RackPosition.of(3, 0, Side.LEFT),
            RackPosition.of(4, 0, Side.LEFT), RackPosition.of(5, 0, Side.LEFT), RackPosition.of(6, 0, Side.LEFT));

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey COPPER = ItemKey.of(Items.COPPER_INGOT);

    private static final int IRON_IN_STOCK = 32;
    private static final int DIAMONDS_IN_STOCK = 24;
    private static final int GOLD_IN_STOCK = 64;
    private static final int COPPER_IN_STOCK = 64;

    /** Row 0: the warehouse holds less iron than this, so the rule calls for it and the lamp burns. */
    private static final int IRON_MINIMUM = 64;
    private static final int IRON_MAXIMUM = 512;
    private static final int IRON_RESERVE = 32;
    /** Row 1: every diamond in stock is reserved, so automation gets none of them. */
    private static final int DIAMOND_RESERVE = DIAMONDS_IN_STOCK;
    /** Row 2: less than what the warehouse already holds, so nothing more of it is stored. */
    private static final int GOLD_MAXIMUM = 16;
    /** Row 3: met, so this rule simply sits there and says so. */
    private static final int COPPER_MINIMUM = 8;
    /** Row 4: a second rule for iron — the one above already governs it. */
    private static final int SHADOWED_ROW = 4;
    /** What the edited shot raises the gold maximum to, well above the stock, so the rule stops biting. */
    private static final int RAISED_GOLD_MAXIMUM = 256;

    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int EDIT_TIMEOUT_TICKS = 200;
    private static final int LAMP_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 4;

    /** In the aisle, two blocks from the keeper, looking at its panel; inside the vanilla container range. */
    private static final CameraView AT_KEEPER = CameraView.of("keeper", 3.1, 1.6, 0.5, 1.4, 0.9, 1.2);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("keeper: clear the area", StockKeeperVisualScenario::clearArea)
                .server("keeper: build the aisle, the stock keeper and the stocked racks",
                        StockKeeperVisualScenario::buildAisle)
                .serverUntil("keeper: wait until the controller has indexed every rack",
                        StockKeeperVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("keeper: write the five rules", StockKeeperVisualScenario::writeRules)
                .serverUntil("keeper: wait until every rule is in the state the shots need",
                        StockKeeperVisualScenario::rulesInExpectedStates, LAMP_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.camera(AT_KEEPER)
                .shot("block")
                .server("keeper: open the rule screen", StockKeeperVisualScenario::openScreen)
                .until("keeper: wait for the screen with its rules", StockKeeperVisualScenario::screenReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("rules");
        if (pass == VisualPass.FLYWHEEL) {
            script.client("keeper: raise the gold maximum through a rule payload",
                            StockKeeperVisualScenario::raiseGoldMaximum)
                    .until("keeper: wait until the server pushed the new maximum back",
                            StockKeeperVisualScenario::goldMaximumRaised, EDIT_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("edited")
                    .client("keeper: ask for a number the keeper cannot store",
                            StockKeeperVisualScenario::askForTooMuch)
                    .until("keeper: wait until the screen reports the correction",
                            StockKeeperVisualScenario::correctionShown, EDIT_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("corrected")
                    .client("keeper: clear the shadowed row", StockKeeperVisualScenario::clearShadowedRow)
                    .until("keeper: wait until the row is empty on screen",
                            StockKeeperVisualScenario::shadowedRowCleared, EDIT_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("cleared");
        }
        script.client("keeper: close the screen", StockKeeperVisualScenario::closeScreen)
                .until("keeper: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (!(open instanceof WarehouseStockKeeperScreen keeper))
            return "screen=" + (open == null ? "none" : open.getClass().getSimpleName());
        StockKeeperScreenState state = keeper.state();
        StringBuilder rows = new StringBuilder();
        for (StockKeeperScreenState.RowView row : state.rows()) {
            if (row.key().isEmpty())
                continue;
            rows.append(rows.isEmpty() ? "" : ",").append(row.row()).append(':').append(row.status())
                    .append('/').append(row.minimum()).append('/').append(row.maximum()).append('/')
                    .append(row.reserve());
        }
        return String.format(Locale.ROOT, "screen=keeper linked=%s adjustment=%s row=%d rows=[%s]", state.linked(),
                state.adjustment(), state.adjustedRow(), rows);
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void clearArea(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(KEEPER), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, layout.sideDirection(KEEPER.side()).getOpposite()));

        List<ItemStack> stock = List.of(IRON.toStack(IRON_IN_STOCK), DIAMOND.toStack(DIAMONDS_IN_STOCK),
                GOLD.toStack(GOLD_IN_STOCK), COPPER.toStack(COPPER_IN_STOCK));
        for (int i = 0; i < STORAGE.size(); i++) {
            RackPosition rack = STORAGE.get(i);
            Direction outward = layout.sideDirection(rack.side());
            BlockPos chest = layout.rackPos(rack).relative(outward);
            level.setBlockAndUpdate(chest,
                    Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
            level.setBlockAndUpdate(layout.rackPos(rack), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
            if (handler == null)
                throw new VisualTestException("the chest at " + chest + " has no item handler");
            if (!ItemHandlerHelper.insertItem(handler, stock.get(i).copy(), false).isEmpty())
                throw new VisualTestException("the chest at " + chest + " did not take its stock");
        }
    }

    /** Five rules in five different states, so one shot shows every colour the screen draws. */
    private static void writeRules(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        set(keeper, 0, IRON, IRON_MINIMUM, IRON_MAXIMUM, IRON_RESERVE);
        set(keeper, 1, DIAMOND, StockRule.UNSET, StockRule.UNSET, DIAMOND_RESERVE);
        set(keeper, 2, GOLD, StockRule.UNSET, GOLD_MAXIMUM, StockRule.UNSET);
        set(keeper, 3, COPPER, COPPER_MINIMUM, StockRule.UNSET, StockRule.UNSET);
        set(keeper, SHADOWED_ROW, IRON, StockRule.UNSET, StockRule.UNSET, 1);
    }

    private static void set(WarehouseStockKeeperBlockEntity keeper, int row, ItemKey key, long minimum, long maximum,
            long reserve) {
        keeper.editRule(row, StockKeeperRules.FIELD_ITEM, key, 0L);
        keeper.editRule(row, StockKeeperRules.FIELD_MINIMUM, null, minimum);
        keeper.editRule(row, StockKeeperRules.FIELD_MAXIMUM, null, maximum);
        keeper.editRule(row, StockKeeperRules.FIELD_RESERVE, null, reserve);
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == STORAGE.size()
                && crane.isControllerLinked() && controller.countOf(IRON) == IRON_IN_STOCK
                && controller.countOf(DIAMOND) == DIAMONDS_IN_STOCK && controller.countOf(GOLD) == GOLD_IN_STOCK
                && controller.countOf(COPPER) == COPPER_IN_STOCK;
    }

    /**
     * Every rule really is in the state its shot is about, and the lamp really burns — asserted on the server before a
     * single shot is taken, because a screenshot cannot tell a lit lamp from a wrong one.
     */
    private static boolean rulesInExpectedStates(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        BlockPos keeperPos = layout(dock).rackPos(KEEPER);
        if (controller == null)
            return false;
        StockRuleStatus[] expected = {StockRuleStatus.BELOW_MINIMUM, StockRuleStatus.AT_RESERVE,
                StockRuleStatus.AT_MAXIMUM, StockRuleStatus.SATISFIED, StockRuleStatus.SHADOWED};
        for (int rule = 0; rule < expected.length; rule++) {
            if (controller.stockRuleStatus(keeperPos, rule) != expected[rule])
                return false;
        }
        WarehouseStockKeeperBlockEntity keeper = keeper(level, dock);
        boolean lit = level.getBlockState(keeperPos).getValue(WarehouseStockKeeperBlock.LIT);
        if (!lit || keeper.comparatorSignal() != 1)
            return false;
        LOGGER.info(PREFIX + "keeper: five rules in five states, lamp lit, comparator {}", keeper.comparatorSignal());
        return true;
    }

    private static void openScreen(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        ServerPlayer player = context.serverPlayer(server);
        if (!keeper.openScreen(player))
            throw new VisualTestException("the stock keeper screen could not be opened for the camera player");
        LOGGER.info(PREFIX + "keeper: opened the screen with {} rules", keeper.rules().ruleCount());
    }

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static WarehouseStockKeeperBlockEntity keeper(ServerLevel level, BlockPos dock) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER
                .getNullable(level, layout(dock).rackPos(KEEPER));
        if (keeper == null)
            throw new VisualTestException("the warehouse stock keeper of the aisle is missing");
        return keeper;
    }

    // --- screen (client thread) ------------------------------------------------------------------------------------

    private static boolean screenReady(VisualContext context) {
        Screen open = context.minecraft().screen;
        return open instanceof WarehouseStockKeeperScreen keeper && !keeper.state().rows().isEmpty()
                && keeper.state().linked();
    }

    private static WarehouseStockKeeperScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseStockKeeperScreen keeper)
            return keeper;
        throw new VisualTestException("the stock keeper screen is not open (screen: " + open + ")");
    }

    /** The real client → server path: what a player produces by scrolling on the maximum of the gold row. */
    private static void raiseGoldMaximum(VisualContext context) {
        WarehouseStockKeeperScreen keeper = screen(context);
        PacketDistributor.sendToServer(StockKeeperRulePayload.setNumber(keeper.getMenu().containerId, 2,
                StockKeeperRules.FIELD_MAXIMUM, RAISED_GOLD_MAXIMUM));
    }

    private static boolean goldMaximumRaised(VisualContext context) {
        return maximumOf(context, 2) == RAISED_GOLD_MAXIMUM;
    }

    /** A number no rule may store: the server clamps it and the screen says which correction it had to make. */
    private static void askForTooMuch(VisualContext context) {
        WarehouseStockKeeperScreen keeper = screen(context);
        PacketDistributor.sendToServer(StockKeeperRulePayload.setNumber(keeper.getMenu().containerId, 2,
                StockKeeperRules.FIELD_MAXIMUM, Long.MAX_VALUE));
    }

    private static boolean correctionShown(VisualContext context) {
        return screen(context).state().adjustedRow() == 2 && maximumOf(context, 2) == StockRule.MAX_AMOUNT;
    }

    private static void clearShadowedRow(VisualContext context) {
        WarehouseStockKeeperScreen keeper = screen(context);
        PacketDistributor.sendToServer(StockKeeperRulePayload.clearRow(keeper.getMenu().containerId, SHADOWED_ROW));
    }

    private static boolean shadowedRowCleared(VisualContext context) {
        return screen(context).state().row(SHADOWED_ROW).map(row -> row.key().isEmpty()).orElse(false);
    }

    private static long maximumOf(VisualContext context, int row) {
        Optional<StockKeeperScreenState.RowView> view = screen(context).state().row(row);
        return view.map(StockKeeperScreenState.RowView::maximum).orElse(StockRule.UNSET);
    }

    private static void closeScreen(VisualContext context) {
        if (context.minecraft().player != null)
            context.minecraft().player.closeContainer();
    }
}
