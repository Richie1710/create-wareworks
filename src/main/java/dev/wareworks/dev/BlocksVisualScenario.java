package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiConsumer;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.tterrag.registrate.util.entry.BlockEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.WarehouseHomePointBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksCreativeTabs;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Scenario "blocks": close-ups of every static Wareworks block model and the item icons (M4, visual
 * polish).
 * <p>
 * A row of exhibits along +X, {@value #SPACING} blocks apart, each turned so that the side a player stands at faces the
 * front camera (+Z): the dock with its parked crane (aisle side), a straight run of rails and the corner, tee, cross
 * and closed shapes a rail draws itself as (M21, ADR-033), the controller (display),
 * the interface (framed aisle plate; brass port at the back), input and output (aisle openings; intake on top, pull port at
 * the back), since M11 the copper-bodied production station (aisle opening) and, since M20, the same station with its
 * <b>stopped</b> lamp burning. The warehouse terminal is the one
 * exhibit whose front is <b>not</b> its aisle side: since ADR-022 a player stands at its screen and the crane loads it
 * through the opposite face, so the front camera sees the screen and the back camera sees the arm port.
 * <p>
 * Since M28 the three <b>rack bays</b> are exhibits too, one per material and each at a different fill level, because
 * the two things only a human can judge about them are whether the material reads from across the room and whether the
 * load on the pallet reads as a fill level at all. A fourth exhibit is a three by three <b>wall</b> of wooden bays
 * at every step from empty to full, with its own camera far enough back to hold all nine: a single bay says nothing
 * about the question the block exists for, which is whether a wall of them reads as racking.
 * <p>
 * Since M29 a <b>rack yard</b> stands well past the right end of the row, far enough out that none of the row's own
 * shots can see it: a lone bay, the same three by three wall, a long run, a wall of mixed tiers and two runs meeting
 * at a corner. It exists for the one question M29 asks — whether bays <b>join</b> into a rack wall — and it answers
 * it twice: the shots are judged by eye, and the yard also asserts the derived join flags of every bay it builds, so
 * a wall that looks right for the wrong reason still fails the run.
 * <p>
 * Shots per pass: the row from above and from far away (distant z-fighting shows there), a front and a back
 * close-up of every exhibit, a front view of every rack yard exhibit plus a look along the long run at the height the
 * crane's arm works at, two <b>player-eye</b> views of the terminal (M10 look pass: the screen side and the aisle side
 * as a standing player sees them, which the slightly raised close-ups above flatten), and a chest screen with every
 * Wareworks item in creative tab order (item icons). Before the screen the client rebuilds the creative tabs and checks
 * that the Wareworks tab lists the items in building order with the stacker crane as icon.
 */
public final class BlocksVisualScenario implements VisualScenario {
    public static final String NAME = "blocks";

    /** Blocks between two exhibits along X. */
    private static final int SPACING = 3;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 200;
    private static final int SCREEN_TIMEOUT_TICKS = 100;
    /** Ticks with the screen open before the shot, so item icons and the background have rendered. */
    private static final int SCREEN_SETTLE_TICKS = 10;
    private static final int CHEST_SLOTS = 27;
    /** Second slot of the chest's middle row. */
    private static final int FIRST_ITEM_SLOT = 10;

    /** Close-up cameras relative to an exhibit's block centre: to the side, above and in front of or behind it. */
    private static final double CLOSE_SIDE = 1.4;
    private static final double CLOSE_HEIGHT = 1.8;
    private static final double FRONT_DISTANCE = 2.3;
    private static final double BACK_DISTANCE = 2.1;
    private static final double LOOK_HEIGHT = 0.45;
    private static final double BLOCK_CENTER = 0.5;
    /**
     * Overview cameras: above the row in front of it, and far away.
     * <p>
     * The distance is what the widest exhibit row needs, not a round number: with the terminal as the seventh exhibit
     * (M6) the row spanned {@code 6 · }{@value #SPACING}{@code  + 1} blocks, and from 8 blocks away the dock's mast ran
     * out of the frame at the left edge — which is what the committed {@code docs/screenshots/blocks.png} showed until
     * M8. Since M17 the accepting warehouse port is the <b>tenth</b> exhibit and the row spans
     * {@code 9 · }{@value #SPACING}{@code  + 1} blocks, and the row distance grew with it, because at 10 blocks the
     * dock's mast ran out of the frame again, exactly as it did before M8. Since M20 the stopped production station is
     * the twelfth exhibit and the row spans {@code 11 · }{@value #SPACING}{@code  + 1} blocks, which is what these
     * distances were sized for.
     * <p>
     * <b>They no longer frame the whole row</b>, and deliberately so: M21 gave the rail its four junction shapes and a
     * closed one, and M21's home point two lamp states, so the row is now <b>nineteen</b> exhibits and
     * {@code 18 · }{@value #SPACING}{@code  + 1} blocks wide. Pulling the overview cameras back far enough for that
     * would make every block in {@code blocks-row} too small to judge, which is the one thing those two shots exist
     * for; the per-exhibit close-ups are what carries the check now. The overviews stay as the two shots that show
     * z-fighting and the row's left end.
     */
    private static final double ROW_HEIGHT = 5.5;
    private static final double ROW_DISTANCE = 15.0;
    private static final double FAR_HEIGHT = 10.0;
    private static final double FAR_DISTANCE = 26.0;

    /**
     * Player-eye views of the warehouse terminal (M10 look pass). The close-ups above stand 1.8 blocks up and look down
     * at the block, which is exactly the angle that hides whether a screen reads as a screen; these two stand on the
     * ground at vanilla eye height and look almost straight at the face a player would be reading.
     */
    private static final String TERMINAL_LABEL = "terminal";
    private static final double EYE_HEIGHT = 1.62;
    private static final double EYE_DISTANCE = 2.0;
    /** Looked at slightly above the block centre, so the screen rather than the floor sits in the middle of the frame. */
    private static final double EYE_LOOK_HEIGHT = 0.62;

    /** Rank of the accepting port exhibit: an overflow of strength 0, i.e. the weakest one a board can name (M17). */
    private static final int ACCEPT_EXHIBIT_RANK = -1;

    /**
     * The rack wall exhibit (M28): three bays wide and three high, so the uprights and shelves of neighbouring bays
     * meet and a player can see whether that reads as racking rather than as a row of boxes.
     */
    private static final String WALL_LABEL = "bay_wall";
    private static final int WALL_WIDTH = 3;
    private static final int WALL_HEIGHT = 3;
    /** Camera for the wall: far enough back and high enough to hold all nine bays, which the close-ups cannot. */
    private static final double WALL_DISTANCE = 6.0;
    private static final double WALL_HEIGHT_EYE = 2.0;
    private static final double WALL_LOOK_HEIGHT = 1.2;

    /**
     * The rack yard (M29 step 12): the exhibits that only make sense wider than one block, standing
     * {@value #YARD_GAP} blocks past the right end of the row so that neither overview shot reaches them.
     */
    private static final int YARD_GAP = 16;
    /** Blocks of air between two yard exhibits, so one wall's uprights never touch the next one's. */
    private static final int YARD_SPACING = 4;
    /** Yard cameras: player eye height, and far enough back that the exhibit's own width fits the frame. */
    private static final double YARD_MARGIN = 2.6;
    private static final double YARD_LOOK_HEIGHT = 1.4;
    /** The long run seen from inside the aisle at the height the crane's arm reaches through a bay. */
    private static final String RUN_LABEL = "bay_run";
    private static final double AISLE_EYE_OFFSET = 1.6;
    private static final double AISLE_ALONG_OFFSET = 2.2;
    private static final double ARM_HEIGHT = 1.65;
    /** The corner seen from the inside of the L, where a player would stand. */
    private static final String CORNER_LABEL = "bay_corner";
    private static final double CORNER_EYE = 6.0;
    private static final double CORNER_SIDE = 2.5;

    /** One exhibit: its label and how it is placed at its position (server thread). */
    private record Exhibit(String label, BiConsumer<ServerLevel, BlockPos> placer) {
    }

    /**
     * One rack yard exhibit: its label, how many blocks of the yard's X axis it takes and how it is built from its
     * left-hand bottom corner.
     */
    private record YardExhibit(String label, int width, BiConsumer<ServerLevel, BlockPos> placer) {
    }

    private static final List<YardExhibit> YARD = List.of(
            // A bay on its own: both uprights whole, which is the frame every other exhibit is measured against.
            new YardExhibit("bay_alone", 1, (level, pos) -> run(level, pos, WareworksBlocks.RACK_BAY_WOOD, 1, 1,
                    Direction.NORTH, 2)),
            // The three by three wall the issue was written about, so the M28 shot and this one are the same picture.
            new YardExhibit("bay_square", 3, (level, pos) -> run(level, pos, WareworksBlocks.RACK_BAY_WOOD, 3, 3,
                    Direction.NORTH, -1)),
            // A long run, which is where an upright that is drawn twice or not at all shows up at once.
            new YardExhibit(RUN_LABEL, 7, (level, pos) -> run(level, pos, WareworksBlocks.RACK_BAY_WOOD, 7, 2,
                    Direction.NORTH, -1)),
            // A wall whose rows are three materials: brass at the bottom, then andesite, then wood, which is the one
            // order the column rule allows. The seam between two tiers is the one place a shared upright is half one
            // material and half the other.
            new YardExhibit("bay_tiers", 4, BlocksVisualScenario::tierWall),
            // Two runs meeting at a right angle. They face different ways, so they do not join - which is the point:
            // a corner is two racks, and it has to look like two racks.
            new YardExhibit(CORNER_LABEL, 4, BlocksVisualScenario::corner));

    private static final List<Exhibit> EXHIBITS = List.of(
            new Exhibit("dock", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.STACKER_CRANE.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, Direction.SOUTH))),
            // Since M21 the rail's picture follows the rails around it (ADR-033), so every shape it can draw gets an
            // exhibit: a straight run with an end at each end, a corner, a tee, a cross, and a closed rail cutting a
            // line in two. The connection flags are never set here - they are derived when the block is placed, which
            // is exactly what these five exhibits check.
            new Exhibit("rail", (level, pos) -> rails(level, pos, Direction.NORTH, Direction.SOUTH)),
            new Exhibit("rail_corner", (level, pos) -> rails(level, pos, Direction.NORTH, Direction.EAST)),
            new Exhibit("rail_tee", (level, pos) -> rails(level, pos, Direction.NORTH, Direction.EAST,
                    Direction.SOUTH)),
            new Exhibit("rail_cross", (level, pos) -> rails(level, pos, Direction.NORTH, Direction.EAST,
                    Direction.SOUTH, Direction.WEST)),
            new Exhibit("rail_closed", (level, pos) -> {
                rails(level, pos, Direction.NORTH, Direction.SOUTH);
                level.setBlockAndUpdate(pos, rail().setValue(WarehouseRailBlock.CLOSED, true));
            }),
            new Exhibit("controller", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_CONTROLLER
                    .getDefaultState().setValue(WarehouseControllerBlock.FACING, Direction.NORTH))),
            new Exhibit("interface", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INTERFACE
                    .getDefaultState().setValue(WarehouseInterfaceBlock.FACING, Direction.NORTH))),
            // The three rack bays (M28, issue #20). FACING points into the rack depth exactly as an interface's does,
            // so NORTH turns the open front - the window onto the load, with the arm port above it - towards the
            // front camera. Each one is shown at a different fill level, so one row of shots answers both questions
            // at once: whether the three materials are told apart, and whether a load can be read as a level.
            new Exhibit("bay_wood", (level, pos) -> bay(level, pos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), 0)),
            new Exhibit("bay_andesite",
                    (level, pos) -> bay(level, pos, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState(), 2)),
            new Exhibit("bay_brass", (level, pos) -> bay(level, pos,
                    WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), RackBayBlock.FILL_LEVELS)),
            new Exhibit("input", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_INPUT
                    .getDefaultState().setValue(WarehouseInputBlock.FACING, Direction.SOUTH))),
            new Exhibit("output", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_OUTPUT
                    .getDefaultState().setValue(WarehouseOutputBlock.FACING, Direction.SOUTH))),
            // The same port in the accepting direction (M17, issue #12): the andesite ring around the aisle opening and
            // the andesite spout on the back are the one cue a player reads without goggles and without the crosshair
            // resting on the block. Placed by setting the <b>rank</b>, never the block state, because the state is
            // derived from it — which is also what makes this exhibit a check of that derivation.
            new Exhibit("port_accept", (level, pos) -> {
                level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                        .setValue(WarehouseOutputBlock.FACING, Direction.SOUTH));
                if (!(level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port)
                        || !port.setPortRank(ACCEPT_EXHIBIT_RANK))
                    throw new VisualTestException("the accepting port exhibit could not be configured at " + pos);
            }),
            // And in the collecting direction (M18, issue #13): the same geometry with a <b>copper</b> ring and spout,
            // Create's "moves things through itself" material, which is what a port that fetches out of a machine does.
            // Placed by setting the rank to the collect sentinel, so this exhibit checks that derivation as well.
            new Exhibit("port_collect", (level, pos) -> {
                level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                        .setValue(WarehouseOutputBlock.FACING, Direction.SOUTH));
                if (!(level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity port)
                        || !port.setPortRank(PortSettings.COLLECT_RANK))
                    throw new VisualTestException("the collecting port exhibit could not be configured at " + pos);
            }),
            // The terminal's FACING is its intake port, and its screen sits opposite (display = back, the default), so a
            // port towards -Z turns the screen towards the front camera: the side a player reads (ADR-022).
            new Exhibit(TERMINAL_LABEL, (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_TERMINAL
                    .getDefaultState().setValue(WarehouseTerminalBlock.FACING, Direction.NORTH))),
            // The production station faces the aisle like input and output, so its opening looks at the front camera.
            new Exhibit("production", (level, pos) -> level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_PRODUCTION
                    .getDefaultState().setValue(WarehouseProductionBlock.FACING, Direction.SOUTH))),
            // The same station with the safety stop holding one of its products (M20): the brass ring around both
            // openings becomes a lit rose quartz lamp, the colour the stock keeper's pause lamp burns in. It is the only
            // state of this block that differs from its item model, which is why it gets an exhibit of its own.
            new Exhibit("production_stopped", (level, pos) -> level.setBlockAndUpdate(pos,
                    WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                            .setValue(WarehouseProductionBlock.FACING, Direction.SOUTH)
                            .setValue(WarehouseProductionBlock.STOPPED, true))),
            // The stock keeper faces the aisle like the stations, so its panel looks at the front camera. Its lamp is
            // shown lit, which is the only state that differs from the item model (M15).
            new Exhibit("stock_keeper", (level, pos) -> level.setBlockAndUpdate(pos,
                    WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                            .setValue(WarehouseStockKeeperBlock.FACING, Direction.SOUTH)
                            .setValue(WarehouseStockKeeperBlock.LIT, true))),
            // The home point faces the aisle like the stations, so its plate looks at the front camera. Shown with the
            // green lamp burning, i.e. "the crane waits here" (M21, ADR-034).
            new Exhibit("home_point", (level, pos) -> level.setBlockAndUpdate(pos,
                    WareworksBlocks.WAREHOUSE_HOME_POINT.getDefaultState()
                            .setValue(WarehouseHomePointBlock.FACING, Direction.SOUTH)
                            .setValue(WarehouseHomePointBlock.LIT, true))),
            // And with the red lamp, the one state a player has to act on: a second home point, or one the crane
            // cannot drive to. It is the only state of this block that differs from its item model.
            new Exhibit("home_point_refused", (level, pos) -> level.setBlockAndUpdate(pos,
                    WareworksBlocks.WAREHOUSE_HOME_POINT.getDefaultState()
                            .setValue(WarehouseHomePointBlock.FACING, Direction.SOUTH)
                            .setValue(WarehouseHomePointBlock.REFUSED, true))),
            // Last, because it is the only exhibit wider than its own block: a wall of wooden bays running through
            // every fill step from empty to full, left to right and bottom to top. It is built from the middle
            // column outwards and upwards so that every bay is placed beside one that is already standing, which is
            // how a player builds one (RackBayBlock#placementFacing).
            new Exhibit(WALL_LABEL, BlocksVisualScenario::wall));

    /** A rail at {@code pos} with one rail on each named side, so the middle one draws the shape those sides make. */
    private static void rails(ServerLevel level, BlockPos pos, Direction... sides) {
        level.setBlockAndUpdate(pos, rail());
        for (Direction side : sides)
            level.setBlockAndUpdate(pos.relative(side), rail());
    }

    /** One rack bay exhibit: facing the front camera, filled to {@code fillStep} of its own capacity. */
    private static void bay(ServerLevel level, BlockPos pos, BlockState bay, int fillStep) {
        bay(level, pos, bay, Direction.NORTH, fillStep);
    }

    /**
     * One rack bay, turned onto {@code facing} and filled to {@code fillStep} of its own capacity. The joins are
     * derived here rather than left to the scheduled repair tick, so a wall is right in the same server step it is
     * built in and no shot can be taken of a half-finished one.
     */
    private static void bay(ServerLevel level, BlockPos pos, BlockState bay, Direction facing, int fillStep) {
        level.setBlockAndUpdate(pos, RackBayBlock.withJoins(level, pos,
                bay.setValue(RackBayBlock.FACING, facing)));
        if (fillStep <= 0)
            return;
        if (!(level.getBlockEntity(pos) instanceof RackBayBlockEntity entity))
            throw new VisualTestException("no rack bay block entity at " + pos);
        long capacity = entity.capacityFor(ItemKey.of(Items.COBBLESTONE));
        int amount = (int) Math.max(1, capacity * fillStep / RackBayBlock.FILL_LEVELS);
        ItemStack rest = entity.insert(new ItemStack(Items.COBBLESTONE, amount), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the bay at " + pos + " refused " + rest.getCount() + " of its own capacity");
        int shown = level.getBlockState(pos).getValue(RackBayBlock.FILL);
        if (shown != fillStep)
            throw new VisualTestException("the bay at " + pos + " shows fill " + shown + " instead of " + fillStep);
    }

    /**
     * A rectangle of bays of one tier, {@code width} by {@code height}, all facing {@code facing} and filled to
     * {@code fill} — or, for {@code fill < 0}, through every step from empty to full, which is what makes one shot
     * answer both questions a wall raises.
     * <p>
     * It is built left to right and bottom to top, so every bay but the first is placed beside one that already
     * stands, and then every bay's derived join flags are <b>asserted</b> against the wall it was built into. That
     * assertion is the automated half of M29 step 12: a wall whose uprights happen to look right while the flags are
     * wrong fails here rather than two milestones later.
     */
    private static void run(ServerLevel level, BlockPos pos, BlockEntry<RackBayBlock> tier, int width, int height,
                            Direction facing, int fill) {
        int steps = RackBayBlock.FILL_LEVELS + 1;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                BlockPos at = along(pos, facing, column).above(row);
                bay(level, at, tier.getDefaultState(), facing,
                        fill >= 0 ? fill : (row * width + column) % steps);
            }
        }
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++)
                assertJoins(level, along(pos, facing, column).above(row), column > 0, column < width - 1);
        }
    }

    /** {@code pos} moved {@code steps} bays to the right of a rack facing {@code facing}, as the aisle sees it. */
    private static BlockPos along(BlockPos pos, Direction facing, int steps) {
        return pos.relative(facing.getClockWise(), steps);
    }

    /**
     * Fails unless the bay at {@code pos} derived exactly the joins the wall around it calls for
     * ({@code RackBayBlock#LEFT}).
     */
    private static void assertJoins(ServerLevel level, BlockPos pos, boolean left, boolean right) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RackBayBlock))
            throw new VisualTestException("no rack bay at " + pos);
        if (state.getValue(RackBayBlock.LEFT) != left || state.getValue(RackBayBlock.RIGHT) != right)
            throw new VisualTestException("the bay at " + pos + " joined left="
                    + state.getValue(RackBayBlock.LEFT) + " right=" + state.getValue(RackBayBlock.RIGHT)
                    + " instead of left=" + left + " right=" + right);
    }

    /** The mixed-tier exhibit: brass on the ground, andesite over it, wood on top - the one legal order. */
    private static void tierWall(ServerLevel level, BlockPos pos) {
        List<BlockEntry<RackBayBlock>> rows = List.of(WareworksBlocks.RACK_BAY_BRASS,
                WareworksBlocks.RACK_BAY_ANDESITE, WareworksBlocks.RACK_BAY_WOOD);
        for (int row = 0; row < rows.size(); row++)
            run(level, pos.above(row), rows.get(row), 4, 1, Direction.NORTH, rows.size() - row);
    }

    /** The corner exhibit: a run facing the camera and a second one turning away from it, two bays high. */
    private static void corner(ServerLevel level, BlockPos pos) {
        run(level, pos, WareworksBlocks.RACK_BAY_WOOD, 4, 2, Direction.NORTH, 2);
        // The second run starts one block in front of the first one's left end and runs towards the camera, so the
        // inside of the L is the aisle a player stands in.
        run(level, pos.south(), WareworksBlocks.RACK_BAY_WOOD, 3, 2, Direction.EAST, 3);
    }

    /** The rack wall exhibit: {@value #WALL_WIDTH} by {@value #WALL_HEIGHT} wooden bays, every fill step once. */
    private static void wall(ServerLevel level, BlockPos pos) {
        int steps = RackBayBlock.FILL_LEVELS + 1;
        for (int row = 0; row < WALL_HEIGHT; row++) {
            for (int column = 0; column < WALL_WIDTH; column++) {
                int index = row * WALL_WIDTH + column;
                bay(level, pos.offset(column - WALL_WIDTH / 2, row, 0),
                        WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), index % steps);
            }
        }
    }

    private static BlockState rail() {
        return WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                .setValue(WarehouseRailBlock.AXIS, Direction.Axis.Z);
    }

    /** The creative tab contents as checked on the client; the chest screen shows them in this order. */
    private volatile List<Item> tabItems = List.of();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("blocks: clear the area and place the exhibits", BlocksVisualScenario::build)
                .until("blocks: wait until the client has every exhibit", BlocksVisualScenario::clientReady,
                        SCENE_READY_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        double rowCenter = (EXHIBITS.size() - 1) * SPACING / 2.0 + BLOCK_CENTER;
        script.shotFrom(CameraView.of("row", rowCenter, ROW_HEIGHT, ROW_DISTANCE, rowCenter, BLOCK_CENTER, BLOCK_CENTER),
                "blocks");
        script.shotFrom(CameraView.of("far", rowCenter, FAR_HEIGHT, FAR_DISTANCE, rowCenter, BLOCK_CENTER, BLOCK_CENTER),
                "blocks");
        for (int i = 0; i < EXHIBITS.size(); i++) {
            double x = i * SPACING + BLOCK_CENTER;
            String label = EXHIBITS.get(i).label();
            script.shotFrom(CameraView.of("front", x - CLOSE_SIDE, CLOSE_HEIGHT, BLOCK_CENTER + FRONT_DISTANCE, x, LOOK_HEIGHT,
                    BLOCK_CENTER), label);
            script.shotFrom(CameraView.of("back", x + CLOSE_SIDE, CLOSE_HEIGHT, BLOCK_CENTER - BACK_DISTANCE, x, LOOK_HEIGHT,
                    BLOCK_CENTER), label);
            if (label.equals(TERMINAL_LABEL))
                terminalEyeShots(script, x);
            if (label.equals(WALL_LABEL))
                script.shotFrom(CameraView.of("wall", x, WALL_HEIGHT_EYE, BLOCK_CENTER + WALL_DISTANCE, x,
                        WALL_LOOK_HEIGHT, BLOCK_CENTER), WALL_LABEL);
        }
        yardShots(script);
        script.client("blocks: check the creative tab on the client", this::checkCreativeTab)
                .server("blocks: open a chest screen with every Wareworks item", this::openItemScreen)
                .until("blocks: the client shows the chest screen",
                        context -> context.minecraft().screen instanceof ContainerScreen, SCREEN_TIMEOUT_TICKS)
                .waitTicks(SCREEN_SETTLE_TICKS)
                .shot("items-gui")
                .client("blocks: close the chest screen", context -> {
                    LocalPlayer player = context.minecraft().player;
                    if (player != null)
                        player.closeContainer();
                })
                .until("blocks: the chest screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    /**
     * The rack yard (M29 step 12): every wall seen from the aisle at player eye height, plus a look <b>along</b> the
     * long run at the height the crane's arm works at — the view a player has while walking a warehouse, and the one
     * that says whether the uprights line up into a rack rather than into a fence.
     */
    private static void yardShots(VisualScript script) {
        for (int i = 0; i < YARD.size(); i++) {
            YardExhibit exhibit = YARD.get(i);
            double center = yardX(i) + (exhibit.width() - 1) / 2.0 + BLOCK_CENTER;
            double distance = BLOCK_CENTER + YARD_MARGIN + exhibit.width();
            script.shotFrom(CameraView.of("front", center, EYE_HEIGHT, distance, center, YARD_LOOK_HEIGHT,
                    BLOCK_CENTER), exhibit.label());
            if (exhibit.label().equals(RUN_LABEL)) {
                double left = yardX(i) + BLOCK_CENTER;
                double right = yardX(i) + exhibit.width() - 1 + BLOCK_CENTER;
                script.shotFrom(CameraView.of("aisle", left - AISLE_ALONG_OFFSET, ARM_HEIGHT,
                        BLOCK_CENTER + AISLE_EYE_OFFSET, right, ARM_HEIGHT, BLOCK_CENTER), exhibit.label());
            }
            if (exhibit.label().equals(CORNER_LABEL)) {
                // The inside of the L, where the aisles of both runs meet: the second run starts at the first one's
                // left end and turns towards the camera, so a player stands off the left end and looks back at it.
                double corner = yardX(i) + BLOCK_CENTER;
                script.shotFrom(CameraView.of("inside", corner - CORNER_SIDE, EYE_HEIGHT, BLOCK_CENTER + CORNER_EYE,
                        corner + 1.5, YARD_LOOK_HEIGHT, BLOCK_CENTER + 1.5), exhibit.label());
            }
        }
    }

    /**
     * The two player-eye views of the terminal at column {@code x}: {@code eye-display} from the screen side (+Z, where
     * the player stands) and {@code eye-aisle} from the intake side (-Z, where the crane's arm comes from).
     */
    private static void terminalEyeShots(VisualScript script, double x) {
        script.shotFrom(CameraView.of("eye-display", x, EYE_HEIGHT, BLOCK_CENTER + EYE_DISTANCE, x, EYE_LOOK_HEIGHT,
                BLOCK_CENTER), TERMINAL_LABEL);
        script.shotFrom(CameraView.of("eye-aisle", x, EYE_HEIGHT, BLOCK_CENTER - EYE_DISTANCE, x, EYE_LOOK_HEIGHT,
                BLOCK_CENTER), TERMINAL_LABEL);
    }

    @Override
    public String status(VisualContext context) {
        return "exhibits=" + EXHIBITS.size() + " yard=" + YARD.size() + " screen="
                + (context.minecraft().screen == null ? "none" : context.minecraft().screen.getClass().getSimpleName());
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos origin = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(origin);
        int lastX = (EXHIBITS.size() - 1) * SPACING;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                origin.offset(yardEnd() + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN + YARD_SPACING)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (int i = 0; i < EXHIBITS.size(); i++)
            EXHIBITS.get(i).placer().accept(level, origin.offset(i * SPACING, 0, 0));
        for (int i = 0; i < YARD.size(); i++)
            YARD.get(i).placer().accept(level, origin.offset(yardX(i), 0, 0));
        LOGGER.info(PREFIX + "blocks: {} exhibits and {} rack yard walls, last column x={}", EXHIBITS.size(),
                YARD.size(), lastX);
    }

    /** The x offset of yard exhibit {@code index} from the scene origin. */
    private static int yardX(int index) {
        int x = (EXHIBITS.size() - 1) * SPACING + YARD_GAP;
        for (int i = 0; i < index; i++)
            x += YARD.get(i).width() + YARD_SPACING;
        return x;
    }

    /** The rightmost block the scene uses, which is what the cleared area has to reach. */
    private static int yardEnd() {
        return yardX(YARD.size() - 1) + YARD.getLast().width() + YARD_SPACING;
    }

    private void openItemScreen(MinecraftServer server, VisualContext context) {
        List<Item> items = tabItems;
        if (items.isEmpty())
            throw new VisualTestException("the creative tab was not checked before the item screen");
        SimpleContainer container = new SimpleContainer(CHEST_SLOTS);
        for (int i = 0; i < items.size(); i++)
            container.setItem(FIRST_ITEM_SLOT + i, new ItemStack(items.get(i)));
        ServerPlayer player = context.serverPlayer(server);
        OptionalInt menu = player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, owner) -> ChestMenu.threeRows(containerId, inventory, container),
                Component.literal(Wareworks.NAME)));
        if (menu.isEmpty())
            throw new VisualTestException("the chest screen with the Wareworks items could not be opened");
    }

    // --- client ---------------------------------------------------------------------------------------------------------

    private static boolean clientReady(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        for (int i = 0; i < EXHIBITS.size(); i++) {
            if (level.getBlockState(context.origin().offset(i * SPACING, 0, 0)).isAir())
                return false;
        }
        for (int i = 0; i < YARD.size(); i++) {
            if (level.getBlockState(context.origin().offset(yardX(i), 0, 0)).isAir())
                return false;
        }
        return true;
    }

    /** Rebuilds the creative tabs like the creative inventory does and checks the Wareworks tab's order and icon. */
    private void checkCreativeTab(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("no client level for the creative tab check");
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true, level.registryAccess());
        CreativeModeTab tab = WareworksCreativeTabs.BASE.get();
        List<Item> shown = tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        Item icon = tab.getIconItem().getItem();
        LOGGER.info(PREFIX + "blocks: creative tab icon {}, items {}", BuiltInRegistries.ITEM.getKey(icon),
                shown.stream().map(BuiltInRegistries.ITEM::getKey).toList());
        List<Item> expected = List.of(WareworksBlocks.STACKER_CRANE.asItem(), WareworksBlocks.WAREHOUSE_RAIL.asItem(),
                WareworksBlocks.WAREHOUSE_CONTROLLER.asItem(), WareworksBlocks.WAREHOUSE_INTERFACE.asItem(),
                WareworksBlocks.RACK_BAY_WOOD.asItem(), WareworksBlocks.RACK_BAY_ANDESITE.asItem(),
                WareworksBlocks.RACK_BAY_BRASS.asItem(),
                WareworksBlocks.WAREHOUSE_INPUT.asItem(), WareworksBlocks.WAREHOUSE_OUTPUT.asItem(),
                WareworksBlocks.WAREHOUSE_TERMINAL.asItem(), WareworksBlocks.WAREHOUSE_PRODUCTION.asItem(),
                WareworksBlocks.WAREHOUSE_STOCK_KEEPER.asItem(), WareworksBlocks.WAREHOUSE_HOME_POINT.asItem());
        if (!shown.equals(expected) || icon != WareworksBlocks.STACKER_CRANE.asItem())
            throw new VisualTestException("unexpected creative tab: icon " + icon + ", items " + shown);
        tabItems = shown;
    }
}
