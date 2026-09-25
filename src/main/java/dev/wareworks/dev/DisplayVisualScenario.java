package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlock;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlock;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayLayout;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.tterrag.registrate.util.entry.RegistryEntry;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDisplaySources;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "display": the four Display Link sources on real display targets ({@code docs/warehouse-system.md} §10, M14).
 * <p>
 * Layout, relative to the dock (the scene origin; the aisle runs east, so a positive x offset is along the aisle and a
 * negative z offset is the left rack side): the usual powered aisle with {@value #RAILS} rails, a controller behind the
 * dock, a terminal at {@code 1/0 L}, an input at {@code 1/0 R}, an output at {@code 2/0 R} and storage locations on both
 * sides at positions {@value #STORAGE_FIRST_POSITION}..{@value #RAILS}. North of it, across a cleared plaza, stands a
 * wall of display boards at {@code z = }{@value #WALL_Z}, each driven by its own creative motor through a cogwheel:
 * <ul>
 * <li>"Crane Status" on the dock, shown on a {@value #BOARD_WIDTH}x{@value #BOARD_HEIGHT} board,</li>
 * <li>"Aisle Summary" on the controller, on a board of the same size,</li>
 * <li>"Stock List" on the terminal, on a board of the same size,</li>
 * <li>"Aisle Summary" on a stray terminal that belongs to no aisle, on a small
 * {@value #SMALL_BOARD_WIDTH}x1 board — the degraded case, which must read "No aisle".</li>
 * </ul>
 * "Stock of the Filtered Item" sits on the output, whose request filter names {@link #FILTERED_ITEM}, and writes to a row
 * of {@value #NIXIE_TUBES} nixie tube blocks on a pedestal south of the aisle, where that output stands.
 * <p>
 * Tour per pass: the whole wall, an overview of warehouse and wall, then one close-up per board and one of the nixie row.
 * Then the aisle changes — the first pass powers the crane on, the second refills the input — and the same displays are
 * read and shot again, so a screenshot pair shows that the text follows the warehouse. Every shot is preceded by an
 * assertion of what the displays really carry: the lines are compared against the controller's own numbers (letter,
 * status, locations in use, item types, stock, the filtered item's total) and every section must fit the width of its
 * board, so a wrong or clipped line fails the run instead of quietly making a bad screenshot.
 */
public final class DisplayVisualScenario implements VisualScenario {
    public static final String NAME = "display";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    private static final int STORAGE_FIRST_POSITION = 4;
    private static final int STORAGE_LEVELS = 3;

    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition INPUT = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = RackPosition.of(2, 0, Side.RIGHT);
    /** The output block and the terminal: a terminal hands items to a player, so the aisle counts it as an output. */
    private static final int EXPECTED_OUTPUTS = 2;

    // --- the display wall (offsets from the dock; the aisle runs east, so -z is the left rack side) ------------------

    /** The display boards stand this far north of the aisle line, with their backing wall one block behind them. */
    private static final int WALL_Z = -9;
    /** Top row of every board: above the racks, so nothing of the warehouse stands in front of them. */
    private static final int BOARD_TOP_Y = 3;
    private static final int BOARD_WIDTH = 6;
    private static final int BOARD_HEIGHT = 2;
    private static final int SMALL_BOARD_WIDTH = 3;
    /**
     * A display board only accepts text above Create's medium speed level, and it flips its flaps one by one over about
     * two seconds — unless it turns faster than 128 RPM, where Create switches the whole board to an instant flip
     * ({@code FlapDisplayBlockEntity#tick}). The boards here run fast on purpose: a screenshot taken a moment after a
     * pull would otherwise photograph half-turned flaps instead of the text the source produced.
     */
    private static final int BOARD_RPM = 192;
    /** The face of every board (and the rotation axis of the cogwheels that drive them). */
    private static final Direction BOARD_FACING = Direction.SOUTH;

    /** The four boards of the wall, west to east, with a free column between them so none merges into its neighbour. */
    private static final Board CRANE_BOARD = new Board("crane", 0, BOARD_WIDTH, BOARD_HEIGHT);
    private static final Board AISLE_BOARD = new Board("aisle", 8, BOARD_WIDTH, BOARD_HEIGHT);
    private static final Board STOCK_BOARD = new Board("stock", 16, BOARD_WIDTH, BOARD_HEIGHT);
    private static final Board NO_AISLE_BOARD = new Board("noaisle", 24, SMALL_BOARD_WIDTH, 1);
    private static final List<Board> BOARDS = List.of(CRANE_BOARD, AISLE_BOARD, STOCK_BOARD, NO_AISLE_BOARD);

    /** The warehouse terminal that belongs to no aisle, in front of the small board. */
    private static final BlockPos STRAY_TERMINAL = new BlockPos(25, 0, -6);

    /**
     * The nixie row of the output, south of the aisle where the output stands: a pedestal with the tubes on top. A row
     * carries two characters per tube, so three of them hold every amount this warehouse can reach.
     */
    private static final int NIXIE_TUBES = 3;
    private static final int NIXIE_FIRST_X = 1;
    private static final int NIXIE_Z = 4;
    /** Level of the tubes: above the racks behind them, so a close-up has the sky and not a chest wall behind it. */
    private static final int NIXIE_Y = 3;
    /** South edge of the cleared area: behind the nixie row, so the camera that reads it stands in open air. */
    private static final int NIXIE_CLEAR_Z = NIXIE_Z + 7;

    // --- stock -------------------------------------------------------------------------------------------------------

    /** The item the output's request filter names, so the nixie row has something to count. */
    private static final Item FILTERED_ITEM = Items.IRON_INGOT;
    /**
     * Put into the racks before the first shot, so the displays are not all zeroes. Coal is deliberately the largest
     * amount in the whole scenario, so the stock list has one unmistakable first row throughout the run.
     */
    private static final List<ItemStack> PRESTOCKED =
            List.of(new ItemStack(Items.COAL, 64), new ItemStack(Items.QUARTZ, 32), new ItemStack(Items.GLASS, 16));
    /** Extra stacks of the first pre-stocked item, so its amount stays ahead of everything the crane stores later. */
    private static final int PRESTOCKED_EXTRA_STACKS = 1;
    /** Fed to the input when the crane is powered on (first pass): the item types the displays must pick up. */
    private static final List<ItemStack> FIRST_DELIVERY = List.of(new ItemStack(FILTERED_ITEM, 64),
            new ItemStack(Items.COPPER_INGOT, 48), new ItemStack(Items.GOLD_INGOT, 24));
    /** Fed to the input in the second pass, so its shots show a warehouse that changed again. */
    private static final List<ItemStack> SECOND_DELIVERY = List.of(new ItemStack(Items.DIAMOND, 32),
            new ItemStack(Items.EMERALD, 16), new ItemStack(Items.REDSTONE, 40));

    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 8;

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int BOARDS_READY_TIMEOUT_TICKS = 400;
    private static final int PULL_TIMEOUT_TICKS = 400;
    private static final int JOB_TIMEOUT_TICKS = 1200;
    private static final int ALL_STORED_TIMEOUT_TICKS = 2400;
    /** Longer than the slowest passive refresh of the four sources (100 ticks), plus room for the sync to the client. */
    private static final int REFRESH_WAIT_TICKS = 130;
    private static final int SETTLE_TICKS = 4;
    /** Polls between two "still waiting" lines of a wait step. */
    private static final int UNREADY_LOG_INTERVAL = 20;

    // --- camera views (relative to the lower corner of the dock) -----------------------------------------------------

    /** The whole wall from the south, high enough that the racks between camera and wall stay below the sight line. */
    private static final CameraView WALL = CameraView.of("wall", 13.0, 4.6, 3.5, 13.0, 2.8, -9.0);
    /** Warehouse and wall together, from above the west end of the aisle. */
    private static final CameraView OVERVIEW = CameraView.of("overview", -2.0, 7.5, 7.5, 12.0, 2.5, -5.0);
    /**
     * Close in front of the nixie row of the output, read from the south like a player standing at the output. A nixie
     * block carries two tubes, so the row is half as wide as it has characters, and it shows them in the upper half of
     * its block, which is where this camera looks.
     */
    private static final CameraView NIXIE = CameraView.of("nixie", NIXIE_FIRST_X + NIXIE_TUBES / 2.0, NIXIE_Y + 0.9,
            NIXIE_Z + 2.2, NIXIE_FIRST_X + NIXIE_TUBES / 2.0, NIXIE_Y + 0.6, NIXIE_Z + 0.5);

    /** The two moments of every pass: the displays as they stand, and again after the warehouse changed under them. */
    private static final String RESTING = "resting";
    private static final String UPDATED = "updated";

    /** What the displays must carry, built from the controller's own numbers on the server thread. */
    private volatile Expectation expectation = Expectation.EMPTY;
    /** The total stock of the previous reading, so the second half of a pass can prove that the displays moved. */
    private volatile long previousItems = -1L;
    /** Polls of a wait step, so a step that hangs says why instead of only timing out. */
    private int unreadyPolls;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("display: clear the area and place the creative motor", DisplayVisualScenario::placeMotor)
                .server("display: build the aisle with its stations and pre-stocked racks",
                        DisplayVisualScenario::buildAisle)
                .server("display: build the display wall, the nixie row and the stray terminal",
                        DisplayVisualScenario::buildDisplays)
                .serverUntil("display: wait until the controller has indexed every rack", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .serverUntil("display: wait until every display board turns fast enough", this::boardsRunning,
                        BOARDS_READY_TIMEOUT_TICKS)
                .server("display: attach the display links to their source blocks",
                        DisplayVisualScenario::attachLinks)
                .serverUntil("display: wait until every link has pulled its source once", this::everyDisplayWritten,
                        PULL_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        readAndCheck(script, RESTING);
        script.shotFrom(WALL, RESTING).shotFrom(OVERVIEW, RESTING);
        if (pass == VisualPass.FLYWHEEL) {
            for (Board board : BOARDS)
                script.shotFrom(board.view(), RESTING);
        } else {
            script.shotFrom(AISLE_BOARD.view(), RESTING);
        }
        script.shotFrom(NIXIE, RESTING);

        // The warehouse now changes under the displays: the first pass starts the crane, the second feeds it again.
        if (pass == VisualPass.FLYWHEEL) {
            script.server("display: creative motor to " + MOTOR_RPM + " RPM", DisplayVisualScenario::powerOn)
                    .until("display: wait until the crane status board shows a job", DisplayVisualScenario::boardShowsJob,
                            JOB_TIMEOUT_TICKS)
                    .server("display: read the crane's job while the board still shows it", this::expectJob)
                    .waitTicks(SETTLE_TICKS)
                    .client("display: check the crane status board against the crane", this::checkCrane)
                    .shotFrom(CRANE_BOARD.view(), "working");
        } else {
            script.server("display: refill the warehouse input", DisplayVisualScenario::refillInput);
        }
        script.serverUntil("display: wait until the crane stored everything", DisplayVisualScenario::allStored,
                        ALL_STORED_TIMEOUT_TICKS)
                .waitTicks(REFRESH_WAIT_TICKS);
        readAndCheck(script, UPDATED);
        script.client("display: check that the displays followed the warehouse", this::checkChanged)
                .shotFrom(WALL, UPDATED).shotFrom(AISLE_BOARD.view(), UPDATED).shotFrom(STOCK_BOARD.view(), UPDATED)
                .shotFrom(NIXIE, UPDATED);
    }

    @Override
    public String status(VisualContext context) {
        Expectation expected = expectation;
        String crane = clientCrane(context).map(be -> be.craneState().phase().name()).orElse("missing");
        return String.format(Locale.ROOT, "crane=%s items=%d types=%d filtered=%s aisle='%s' stock0='%s' crane0='%s'",
                crane, expected.items(), expected.itemTypes(), expected.filtered(), boardLine(context, AISLE_BOARD, 0),
                boardLine(context, STOCK_BOARD, 0), boardLine(context, CRANE_BOARD, 0));
    }

    /** Reads every display on the server, then compares what the client really carries against it. */
    private void readAndCheck(VisualScript script, String moment) {
        script.server("display: read the warehouse for the '" + moment + "' check", this::readExpectation)
                .waitTicks(SETTLE_TICKS)
                .client("display: check every display for the '" + moment + "' moment", this::checkDisplays);
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private static void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, WALL_Z - 2),
                dock.offset(NO_AISLE_BOARD.x0() + SMALL_BOARD_WIDTH + CLEAR_MARGIN, CLEAR_HEIGHT, NIXIE_CLEAR_Z)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(0);
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
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, layout.sideDirection(OUTPUT.side()).getOpposite()));

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
                    if (next < PRESTOCKED.size())
                        fillChest(level, chest, PRESTOCKED.get(next), next == 0 ? 1 + PRESTOCKED_EXTRA_STACKS : 1);
                    next++;
                }
            }
        }
        // The output's filter is the whole configuration of the "Stock of the Filtered Item" source.
        FilteringBehaviour filter = BlockEntityBehaviour.get(output(level, dock), FilteringBehaviour.TYPE);
        if (filter == null || !filter.setFilter(new ItemStack(FILTERED_ITEM)))
            throw new VisualTestException("the output did not take the request filter");
        fillInput(level, layout.rackPos(INPUT), FIRST_DELIVERY);
    }

    /** Builds the backing wall, the four boards with their motors, the nixie row and the terminal without an aisle. */
    private static void buildDisplays(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        int wallEast = NO_AISLE_BOARD.x0() + SMALL_BOARD_WIDTH + 1;
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-2, 0, WALL_Z - 1),
                dock.offset(wallEast, BOARD_TOP_Y + 1, WALL_Z - 1)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.POLISHED_ANDESITE.defaultBlockState());

        for (Board board : BOARDS)
            board.build(level, dock);
        for (Board board : BOARDS)
            board.controllerEntity(level, dock).updateControllerStatus();

        for (int i = 0; i < NIXIE_TUBES; i++) {
            for (int y = 0; y < NIXIE_Y; y++)
                level.setBlockAndUpdate(dock.offset(NIXIE_FIRST_X + i, y, NIXIE_Z),
                        Blocks.SMOOTH_STONE.defaultBlockState());
            // A floor-mounted row runs along its facing axis and reads left to right for a player south of it.
            level.setBlockAndUpdate(dock.offset(NIXIE_FIRST_X + i, NIXIE_Y, NIXIE_Z),
                    AllBlocks.ORANGE_NIXIE_TUBE.getDefaultState().setValue(NixieTubeBlock.FACING, Direction.EAST));
        }

        level.setBlockAndUpdate(strayTerminal(dock), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, Direction.SOUTH));
    }

    /** The terminal on the plaza, far outside every aisle, that makes the small board read "No aisle". */
    private static BlockPos strayTerminal(BlockPos dock) {
        return dock.offset(STRAY_TERMINAL.getX(), STRAY_TERMINAL.getY(), STRAY_TERMINAL.getZ());
    }

    /**
     * Places the five display links, each on a face of its source block, and points it at its target. Setting the source
     * here is what the link screen does for a player; from then on the links pull on their own schedule, exactly as they
     * would in a real world.
     */
    private static void attachLinks(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        AisleLayout layout = layout(dock);

        // The dock's north face: its top carries the crane's mast, and the rack position beside it is never visited.
        link(level, dock, Direction.NORTH, CRANE_BOARD.controllerPos(dock), WareworksDisplaySources.CRANE_STATUS);
        link(level, dock.relative(AISLE.getOpposite()), Direction.UP, AISLE_BOARD.controllerPos(dock),
                WareworksDisplaySources.AISLE_SUMMARY);
        link(level, layout.rackPos(TERMINAL), Direction.UP, STOCK_BOARD.controllerPos(dock),
                WareworksDisplaySources.STOCK_LIST);
        link(level, layout.rackPos(OUTPUT), Direction.UP, dock.offset(NIXIE_FIRST_X, NIXIE_Y, NIXIE_Z),
                WareworksDisplaySources.FILTERED_STOCK);
        link(level, strayTerminal(dock), Direction.UP, NO_AISLE_BOARD.controllerPos(dock),
                WareworksDisplaySources.AISLE_SUMMARY);
    }

    private static DisplayLinkBlockEntity linkAt(ServerLevel level, BlockPos pos) {
        DisplayLinkBlockEntity link = AllBlockEntityTypes.DISPLAY_LINK.getNullable(level, pos);
        if (link == null)
            throw new VisualTestException("the display link at " + pos + " has no block entity");
        return link;
    }

    private static void link(ServerLevel level, BlockPos source, Direction face, BlockPos target,
            RegistryEntry<DisplaySource, ? extends DisplaySource> entry) {
        BlockPos pos = source.relative(face);
        level.setBlockAndUpdate(pos, AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING, face));
        DisplayLinkBlockEntity link = linkAt(level, pos);
        if (!link.getSourcePosition().equals(source))
            throw new VisualTestException("the display link at " + pos + " reads " + link.getSourcePosition()
                    + " instead of " + source);
        if (!DisplaySource.getAll(level, source).contains(entry.get()))
            throw new VisualTestException("the block at " + source + " does not offer the display source " + entry.getId());
        link.activeSource = entry.get();
        link.target(target);
        link.targetLine = 0;
        link.notifyUpdate();
        link.updateGatheredData();
        if (link.activeTarget == null)
            throw new VisualTestException("the block at " + target + " does not accept display text");
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return logUnready("controller=" + controller + " crane=" + crane);
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        // A terminal takes items out of the aisle as well, so it counts as an output station beside the output block.
        boolean ready = controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocationCount() == expectedStorage
                && controller.inputStations().size() == 1 && controller.outputStations().size() == EXPECTED_OUTPUTS
                && crane.isControllerLinked() && controller.stockIndex().distinctKeys() == PRESTOCKED.size();
        return ready || logUnready(String.format(Locale.ROOT,
                "status=%s dirty=%s pending=%d storage=%d/%d inputs=%d outputs=%d/%d linked=%s keys=%d/%d",
                controller.status(), controller.isMembershipDirty(), controller.pendingSnapshotCount(),
                controller.storageLocationCount(), expectedStorage, controller.inputStations().size(),
                controller.outputStations().size(), EXPECTED_OUTPUTS, crane.isControllerLinked(),
                controller.stockIndex().distinctKeys(), PRESTOCKED.size()));
    }

    /** Logs why a wait step is still waiting, about once per second, and always answers "not ready". */
    private boolean logUnready(String reason) {
        if (unreadyPolls++ % UNREADY_LOG_INTERVAL == 0)
            LOGGER.info(PREFIX + "display: still waiting: {}", reason);
        return false;
    }

    private boolean boardsRunning(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (Board board : BOARDS) {
            FlapDisplayBlockEntity controller = board.controllerEntity(level, dock);
            if (!controller.isController || controller.xSize != board.width() || controller.ySize != board.height()
                    || !controller.isSpeedRequirementFulfilled())
                return logUnready(String.format(Locale.ROOT, "board %s controller=%s size=%dx%d (want %dx%d) speed=%.1f",
                        board.label(), controller.isController, controller.xSize, controller.ySize, board.width(),
                        board.height(), controller.getSpeed()));
        }
        return true;
    }

    /** Every board carries text on its first line and the nixie row shows something: every link has pulled. */
    private boolean everyDisplayWritten(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (Board board : BOARDS) {
            FlapDisplaySection first = board.controllerEntity(level, dock).getLines().getFirst().getSections().getFirst();
            if (first.getText() == null || first.getText().getString().isBlank())
                return logUnready("the " + board.label() + " board is still blank");
        }
        NixieTubeBlockEntity tube = AllBlockEntityTypes.NIXIE_TUBE.getNullable(level,
                dock.offset(NIXIE_FIRST_X, NIXIE_Y, NIXIE_Z));
        if (tube == null || tube.getFullText().getString().isBlank())
            return logUnready("the nixie row is still blank");
        return true;
    }

    private static void powerOn(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin()).generatedSpeed.setValue(MOTOR_RPM);
    }

    private static void refillInput(MinecraftServer server, VisualContext context) {
        fillInput(server.overworld(), layout(context.origin()).rackPos(INPUT), SECOND_DELIVERY);
    }

    private static boolean allStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout(dock).rackPos(INPUT));
        if (crane == null || input == null)
            throw new VisualTestException("the dock or the input of the aisle is missing");
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty() && input.bufferedItems().isEmpty();
    }

    // --- expectations (server thread) --------------------------------------------------------------------------------

    /** Builds the lines the displays must carry right now from the controller's own numbers. */
    private void readExpectation(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        StockView<ItemKey, RackPosition> stock = controller.stockIndex();

        List<String> aisleLines = List.of(
                WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_AISLE,
                        String.valueOf(controller.aisleLetter()),
                        WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_STATUS_READY)).getString(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_LOCATIONS,
                        WareworksLang.number(stock.occupiedLocations()),
                        WareworksLang.number(controller.countedStorageLocationCount())).getString(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_ITEM_TYPES,
                        WareworksLang.number(stock.distinctKeys())).getString(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_ITEMS,
                        WareworksLang.number(stock.totalItems())).getString());

        ItemKey top = null;
        long topCount = 0L;
        for (ItemKey key : stock.keys()) {
            long count = stock.count(key);
            if (count > topCount) {
                top = key;
                topCount = count;
            }
        }
        String filtered = WareworksLang.number(controller.countOf(ItemKey.of(filterOf(level, dock)))).component()
                .getString();
        // Kept before this reading replaces it, so the check after the warehouse changed has something to compare with.
        previousItems = expectation.items();
        expectation = new Expectation(aisleLines, stock.distinctKeys(), stock.totalItems(),
                top == null ? "" : top.getItem().getDescription().getString(), topCount, filtered, "");
        LOGGER.info(PREFIX + "display: the warehouse reads {}", expectation);
    }

    /**
     * Reads the crane's activity while the crane status board still shows it. The job itself (type, item, amount and
     * target) stands for the whole job, so the check that follows is stable even though the crane keeps moving; what the
     * head holds changes mid job and is therefore left to the screenshot.
     */
    private void expectJob(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        CraneGoggleInfo info = crane(level, dock).goggleInfo();
        // Read and pull in the same server tick: the crane keeps working while the shot is set up, and without this the
        // board could already carry the next job by the time the check runs.
        linkAt(level, dock.relative(Direction.NORTH)).updateGatheredData();
        List<String> lines = new ArrayList<>(3);
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_STORING).getString());
        info.job().ifPresent(job -> {
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_LINE_JOB, job.item().getDescription(),
                    WareworksLang.number(job.amount())).getString());
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_LINE_TARGET, info.address(job.target()))
                    .getString());
        });
        if (lines.size() < 3)
            throw new VisualTestException("the crane lost its job before it could be read");
        Expectation previous = expectation;
        expectation = new Expectation(previous.aisleLines(), previous.itemTypes(), previous.items(),
                previous.topName(), previous.topCount(), previous.filtered(), String.join(" | ", lines));
        LOGGER.info(PREFIX + "display: the crane reports {}", lines);
    }

    // --- checks (client thread, what a player really sees) -----------------------------------------------------------

    /** Compares every display against {@link #expectation} and fails the run on the first difference. */
    private void checkDisplays(VisualContext context) {
        Expectation expected = expectation;
        List<String> aisle = boardLines(context, AISLE_BOARD);
        for (int i = 0; i < expected.aisleLines().size(); i++)
            assertLine(aisle.get(i), expected.aisleLines().get(i), "aisle summary line " + i);

        List<String> stock = boardLines(context, STOCK_BOARD);
        int rows = Math.min(stock.size(), expected.itemTypes());
        for (int i = 0; i < stock.size(); i++) {
            boolean filled = !stock.get(i).isBlank();
            if (filled != i < rows)
                throw new VisualTestException("the stock list shows " + (filled ? "a filled" : "a blank") + " row " + i
                        + " although the aisle holds " + expected.itemTypes() + " item types: " + stock);
        }
        // Create's value list writes plain digits below 1000 and "1 K" above it; every amount of this scene stays below.
        if (rows > 0 && (!stock.getFirst().contains(expected.topName())
                || !stock.getFirst().contains(String.valueOf(expected.topCount()))))
            throw new VisualTestException("the stock list does not start with " + expected.topCount() + " "
                    + expected.topName() + ": '" + stock.getFirst() + "'");

        assertLine(nixieText(context), expected.filtered(), "the nixie row of the output");
        assertLine(boardLines(context, NO_AISLE_BOARD).getFirst(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_NO_AISLE).getString(),
                "the board of the terminal without an aisle");

        for (Board board : BOARDS)
            checkFits(context, board);
        LOGGER.info(PREFIX + "display: aisle {} | stock {} | crane {} | nixie '{}'", aisle, stock,
                boardLines(context, CRANE_BOARD), nixieText(context));
    }

    /** The crane status board, checked against the job the server read a moment ago. */
    private void checkCrane(VisualContext context) {
        List<String> shown = boardLines(context, CRANE_BOARD);
        List<String> expected = List.of(expectation.craneLines().split(" \\| ", -1));
        for (int i = 0; i < expected.size(); i++)
            assertLine(shown.get(i), expected.get(i), "crane status line " + i);
        checkFits(context, CRANE_BOARD);
        LOGGER.info(PREFIX + "display: the crane status board shows {}", shown);
    }

    /** Fails unless the warehouse really changed between the two checks of a pass, so the shot pair means something. */
    private void checkChanged(VisualContext context) {
        long before = previousItems;
        long now = expectation.items();
        if (now <= before)
            throw new VisualTestException("the aisle holds " + now + " items, so it did not grow past the " + before
                    + " of the first check; the '" + UPDATED + "' shots would show nothing new");
        LOGGER.info(PREFIX + "display: the aisle grew from {} to {} items and the displays followed", before, now);
    }

    /** Every section must fit its flaps, or the board cuts the line and the shot shows what no source ever produced. */
    private void checkFits(VisualContext context, Board board) {
        FlapDisplayBlockEntity controller = clientBoard(context, board);
        for (FlapDisplayLayout line : controller.getLines()) {
            for (FlapDisplaySection section : line.getSections()) {
                Component text = section.getText();
                if (text == null)
                    continue;
                int flaps = (int) (section.getSize() / FlapDisplaySection.MONOSPACE);
                String shown = text.getString().trim();
                if (shown.length() > flaps)
                    throw new VisualTestException("the " + board.label() + " board cuts '" + shown + "' to " + flaps
                            + " characters (the board is " + controller.xSize + " blocks wide)");
            }
        }
    }

    private static void assertLine(String shown, String expected, String what) {
        if (!shown.equals(expected))
            throw new VisualTestException(what + " reads '" + shown + "' instead of '" + expected + "'");
    }

    /** Whether the crane status board already shows a store job, i.e. more than the two lines of an idle crane. */
    private static boolean boardShowsJob(VisualContext context) {
        String storing = WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_STORING).getString();
        List<String> lines = boardLines(context, CRANE_BOARD);
        return lines.getFirst().equals(storing) && !lines.get(1).isBlank() && !lines.get(2).isBlank();
    }

    // --- client access ------------------------------------------------------------------------------------------------

    /** The text of every line of a board, in order, as the client received it (before the flaps uppercase it). */
    private static List<String> boardLines(VisualContext context, Board board) {
        List<String> lines = new ArrayList<>();
        for (FlapDisplayLayout line : clientBoard(context, board).getLines()) {
            StringBuilder text = new StringBuilder();
            for (FlapDisplaySection section : line.getSections())
                text.append(section.getText() == null ? "" : section.getText().getString());
            lines.add(text.toString());
        }
        return lines;
    }

    /** One board line for a log entry; never throws, so a status line cannot fail a run. */
    private static String boardLine(VisualContext context, Board board, int line) {
        try {
            List<String> lines = boardLines(context, board);
            return line < lines.size() ? lines.get(line).trim() : "";
        } catch (RuntimeException error) {
            return "?";
        }
    }

    private static FlapDisplayBlockEntity clientBoard(VisualContext context, Board board) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("no client level to read the " + board.label() + " board from");
        BlockPos pos = board.controllerPos(context.origin());
        if (level.getBlockEntity(pos) instanceof FlapDisplayBlockEntity controller)
            return controller;
        throw new VisualTestException("no display board at " + pos + " on the client (" + board.label() + ")");
    }

    private static String nixieText(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("no client level to read the nixie row from");
        BlockPos pos = context.origin().offset(NIXIE_FIRST_X, NIXIE_Y, NIXIE_Z);
        if (level.getBlockEntity(pos) instanceof NixieTubeBlockEntity tube)
            return tube.getFullText().getString().trim();
        throw new VisualTestException("no nixie tube at " + pos + " on the client");
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    // --- shared helpers ------------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the warehouse controller of the aisle is missing");
        return controller;
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the stacker crane dock of the aisle is missing");
        return crane;
    }

    private static WarehouseOutputBlockEntity output(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(dock).rackPos(OUTPUT));
        if (output == null)
            throw new VisualTestException("the warehouse output of the aisle is missing");
        return output;
    }

    private static ItemStack filterOf(ServerLevel level, BlockPos dock) {
        return output(level, dock).requestedItem();
    }

    private static void fillChest(ServerLevel level, BlockPos chest, ItemStack stack, int stacks) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("the chest at " + chest + " has no item handler");
        for (int i = 0; i < stacks; i++) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
            if (!rest.isEmpty())
                throw new VisualTestException("the chest at " + chest + " refused " + rest);
        }
    }

    private static void fillInput(ServerLevel level, BlockPos input, List<ItemStack> stacks) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, input, null);
        if (handler == null)
            throw new VisualTestException("the warehouse input at " + input + " has no item handler");
        for (ItemStack stack : stacks) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
            if (!rest.isEmpty())
                throw new VisualTestException("the warehouse input refused " + rest);
        }
    }

    // --- records -------------------------------------------------------------------------------------------------------

    /**
     * One display board of the wall: its west column, its size in blocks and the camera that reads it. Boards face
     * {@link #BOARD_FACING}, so their controller block — the one a link targets and the only one that knows the whole
     * board — is the westmost block of the top row.
     */
    private record Board(String label, int x0, int width, int height) {
        BlockPos controllerPos(BlockPos dock) {
            return dock.offset(x0, BOARD_TOP_Y, WALL_Z);
        }

        FlapDisplayBlockEntity controllerEntity(ServerLevel level, BlockPos dock) {
            FlapDisplayBlockEntity board = AllBlockEntityTypes.FLAP_DISPLAY.getNullable(level, controllerPos(dock));
            if (board == null)
                throw new VisualTestException("the " + label + " display board is missing");
            return board;
        }

        /** Straight in front of the board's face, far enough back that the whole board fits and close enough to read. */
        CameraView view() {
            double centre = x0 + width / 2.0;
            double eyeHeight = BOARD_TOP_Y + 1.0 - height / 2.0;
            double face = WALL_Z + 1.0;
            return CameraView.of(label, centre, eyeHeight, face + 0.6 * width + 0.5, centre, eyeHeight, face);
        }

        /** Rows top to bottom, then the cogwheel above the controller block and the motor that drives it. */
        void build(ServerLevel level, BlockPos dock) {
            for (int row = 0; row < height; row++) {
                BlockState state = AllBlocks.DISPLAY_BOARD.getDefaultState()
                        .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, BOARD_FACING)
                        .setValue(FlapDisplayBlock.UP, row > 0).setValue(FlapDisplayBlock.DOWN, row < height - 1);
                for (int i = 0; i < width; i++)
                    level.setBlockAndUpdate(dock.offset(x0 + i, BOARD_TOP_Y - row, WALL_Z), state);
            }
            BlockPos cog = dock.offset(x0, BOARD_TOP_Y + 1, WALL_Z);
            level.setBlockAndUpdate(cog, AllBlocks.COGWHEEL.getDefaultState()
                    .setValue(RotatedPillarKineticBlock.AXIS, BOARD_FACING.getAxis()));
            level.setBlockAndUpdate(cog.relative(BOARD_FACING.getOpposite()), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, BOARD_FACING));
            CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level,
                    cog.relative(BOARD_FACING.getOpposite()));
            if (motor == null)
                throw new VisualTestException("the creative motor of the " + label + " board is missing");
            motor.generatedSpeed.setValue(BOARD_RPM);
        }
    }

    /**
     * What the displays must carry: the four aisle summary lines, the numbers behind them, the first stock list row, the
     * output's filtered total and — while a job runs — the crane status lines, joined for the log.
     */
    private record Expectation(List<String> aisleLines, int itemTypes, long items, String topName, long topCount,
            String filtered, String craneLines) {
        static final Expectation EMPTY = new Expectation(List.of(), 0, 0L, "", 0L, "", "");
    }
}
