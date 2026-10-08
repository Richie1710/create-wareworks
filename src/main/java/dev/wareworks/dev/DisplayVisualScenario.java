package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.simibubi.create.foundation.utility.FluidFormatter;
import com.tterrag.registrate.util.entry.RegistryEntry;

import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.NetworkGoggleInfo;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
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
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.core.crane.CraneThroughput;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.inventory.StockView;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDisplaySources;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.data.Couple;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "display": the six Display Link sources on real display targets ({@code docs/warehouse-system.md} §10, M14,
 * M25, M30).
 * <p>
 * Layout, relative to the dock (the scene origin; the aisle runs east, so a positive x offset is along the aisle and a
 * negative z offset is the left rack side): the usual powered aisle with {@value #RAILS} rails, a controller behind the
 * dock, a terminal at {@code 1/0 L}, an input at {@code 1/0 R}, an output at {@code 2/0 R}, two <b>fluid bays</b> at
 * {@code 2/0 L} and {@code 3/0 L} holding lava and water (M30, issue #21) and storage locations on both
 * sides at positions {@value #STORAGE_FIRST_POSITION}..{@value #RAILS}. North of it, across a cleared plaza, stands a
 * wall of display boards at {@code z = }{@value #WALL_Z}, each driven by its own creative motor through a cogwheel:
 * <ul>
 * <li>"Crane Status" on the dock, shown on a {@value #BOARD_WIDTH}x{@value #BOARD_HEIGHT} board,</li>
 * <li>"Aisle Summary" on the controller, on a board one row <b>taller</b> ({@value #AISLE_BOARD_HEIGHT}), because that
 * is the one source with more than four lines to give: below the four core ones it names the aisles a player
 * <b>named</b> (M25, issue #15), and a four-line board is exactly where that line is dropped,</li>
 * <li>"Stock List" on the terminal, on a {@value #BOARD_WIDTH}x{@value #BOARD_HEIGHT} board,</li>
 * <li>"Aisle Summary" on a stray terminal that belongs to no aisle, on a small
 * {@value #SMALL_BOARD_WIDTH}x1 board — the degraded case, which must read "No aisle",</li>
 * <li>"Crane Throughput" on the dock's <b>second</b> face (M25, issue #16): what the machine got done in the minute
 * behind it, on a board of the standard size, because the source writes exactly four rows — or the single word
 * "Measuring" while its window is still short, which is what a freshly built warehouse really shows,</li>
 * <li>"Fluid Stock" on the controller's <b>second</b> face (M30, issue #21): one row per fluid the warehouse's bays
 * hold, with the amount and <b>its unit</b> in front of the fluid's name. It is the one board whose rows carry a unit
 * at all, which is why it is worth looking at: the number is millibuckets inside the mod and the row has to read in
 * buckets, and the unit comes from Create's own {@code fluid_units} flap cycle rather than from a word this mod
 * invented.</li>
 * </ul>
 * "Stock of the Filtered Item" sits on the output, whose request filter names {@link #FILTERED_ITEM}, and writes to a row
 * of {@value #NIXIE_TUBES} nixie tube blocks on a pedestal south of the aisle, where that output stands.
 * <p>
 * Tour per pass: the whole wall, an overview of warehouse and wall, then one close-up per board and one of the nixie row.
 * Then the aisle changes — the first pass powers the crane on, the second refills the input, and both top up a fluid
 * bay as a pump would — and the same displays are read and shot again, so a screenshot pair shows that the text
 * follows the warehouse in items <b>and</b> in fluid. The first pass then feeds the
 * warehouse once more and takes the whole wall again <b>in German</b>, which is the only gate on whether a translated
 * row fits the flaps of a real board. Every shot is preceded by an
 * assertion of what the displays really carry: the lines are compared against the controller's own numbers (letter,
 * status, locations in use, item types, stock, the names, the filtered item's total, the crane's minute) and every
 * section must fit the width of its
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
    /**
     * Characters one row of a {@value #BOARD_WIDTH}-block board holds, by Create's own arithmetic
     * ({@code FlapDisplayBlockEntity#getMaxCharCount()}): what a source is handed as {@code maxColumns}, and what the
     * names row of the Warehouse Summary source sizes itself to (M25, issue #15).
     */
    private static final int BOARD_CHARS = (int) ((BOARD_WIDTH * 16f - 2f) / 3.5f);
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

    /**
     * The aisle summary board is one row <b>taller</b> than the others, because it is the one source that has more
     * than four lines to give: the four a board of every size shows, and below them the optional ones — the aisles a
     * warehouse is made of, and the <b>names</b> a player gave them (M25, issue #15). On a four-line board the names
     * line is exactly what {@link WarehouseDisplays#limit} drops, which is the whole point of putting it last, so a
     * board that could never show it would prove nothing about it.
     */
    private static final int AISLE_BOARD_HEIGHT = 3;

    /** The five boards of the wall, west to east, with a free column between them so none merges into its neighbour. */
    private static final Board CRANE_BOARD = new Board("crane", 0, BOARD_WIDTH, BOARD_HEIGHT);
    private static final Board AISLE_BOARD = new Board("aisle", 8, BOARD_WIDTH, AISLE_BOARD_HEIGHT);
    private static final Board STOCK_BOARD = new Board("stock", 16, BOARD_WIDTH, BOARD_HEIGHT);
    private static final Board NO_AISLE_BOARD = new Board("noaisle", 24, SMALL_BOARD_WIDTH, 1);
    /** "Crane Throughput" on the dock, the second source that block offers (M25, issue #16). */
    private static final Board THROUGHPUT_BOARD = new Board("throughput", 28, BOARD_WIDTH, BOARD_HEIGHT);
    /** "Fluid Stock" on the controller, the third source that block offers (M30, issue #21). */
    private static final Board FLUID_BOARD = new Board("fluid", 36, BOARD_WIDTH, BOARD_HEIGHT);
    private static final List<Board> BOARDS =
            List.of(CRANE_BOARD, AISLE_BOARD, STOCK_BOARD, NO_AISLE_BOARD, THROUGHPUT_BOARD, FLUID_BOARD);
    /** West end of the backing wall: one column before the westmost board, so no board ends at a bare edge. */
    private static final int WALL_WEST = -2;
    /** East end of the backing wall and of the cleared plaza: one column past the easternmost board. */
    private static final int WALL_EAST =
            BOARDS.stream().mapToInt(board -> board.x0() + board.width()).max().orElseThrow();
    /** The middle of the wall, which is what the camera that shows all of it has to be centred on. */
    private static final double WALL_CENTRE = (WALL_WEST + WALL_EAST) / 2.0;

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

    /**
     * The name a player gave this warehouse's aisle (M25, issue #15). Player text, so it is the same in both
     * languages — which is exactly what makes the German board pass worth taking: what changes around it is the
     * translated {@code Names:} label, and a label is what a board row runs out of flaps for.
     */
    private static final String AISLE_NAME = "Ores";

    /**
     * The two fluid bays of the aisle and what is in them (M30, issue #21): unfiltered copper bays, filled through
     * their own handler the way a Mechanical Pump or a player's bucket fills them.
     * <p>
     * Two of them, with <b>different</b> amounts, so the fluid stock board has rows to put in order and a total to add
     * up; and the lava is deliberately the larger, so the first row of that board is decided by the amount and not by
     * the index's iteration order.
     */
    private static final List<FluidBay> FLUID_BAYS = List.of(
            new FluidBay(RackPosition.of(2, 0, Side.LEFT), Fluids.LAVA, 48 * FluidType.BUCKET_VOLUME),
            new FluidBay(RackPosition.of(3, 0, Side.LEFT), Fluids.WATER, 12 * FluidType.BUCKET_VOLUME));
    /**
     * What a pump adds to the first bay between the two readings of a pass, so the fluid rows and the warehouse
     * summary's fluid line really move between the "resting" and the "updated" shots. Small enough that two passes
     * and a copper bay's 64 buckets leave room.
     */
    private static final int BAY_TOP_UP = 4 * FluidType.BUCKET_VOLUME;

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
    /**
     * Fed in the German chapter, so the German boards carry <b>numbers a crane really earned</b> rather than the row
     * of zeroes a machine that has stood still through a resource reload would otherwise report. Every amount stays
     * below the pre-stocked coal, so the stock list keeps its one unmistakable first row throughout the run.
     */
    private static final List<ItemStack> GERMAN_DELIVERY = List.of(new ItemStack(Items.LAPIS_LAZULI, 24),
            new ItemStack(Items.AMETHYST_SHARD, 16), new ItemStack(Items.BRICK, 32));

    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 8;
    /**
     * How far south of the nixie row the plaza is cleared as well: the camera that shows the whole wall stands there,
     * and the wall grew six columns with the fluid board (M30), so that camera had to step back to keep all of it in
     * frame. A camera inside a block photographs the inside of a block.
     */
    private static final int CAMERA_CLEAR_Z = NIXIE_CLEAR_Z + 8;

    /** Five boards, two passes and a German chapter with a resource reload in it: far above the default four minutes. */
    private static final long RUN_TIMEOUT_MILLIS = 12L * 60L * 1000L;

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int BOARDS_READY_TIMEOUT_TICKS = 400;
    private static final int PULL_TIMEOUT_TICKS = 400;
    private static final int JOB_TIMEOUT_TICKS = 1200;
    private static final int ALL_STORED_TIMEOUT_TICKS = 2400;
    /**
     * Until the crane's rolling window holds a whole minute, which is when the throughput source stops saying
     * "Measuring" and starts giving numbers ({@code CraneThroughputDisplaySource}). A minute of ticks plus room for
     * the pull that follows and for a server that is not running at twenty ticks a second.
     */
    private static final int FULL_MINUTE_TIMEOUT_TICKS = 2400;
    /** Longer than the slowest passive refresh of the five sources (100 ticks), plus room for the sync to the client. */
    private static final int REFRESH_WAIT_TICKS = 130;
    private static final int SETTLE_TICKS = 4;
    /** A language switch rebuilds every texture atlas, so the reload needs its own, longer budget. */
    private static final int RELOAD_TIMEOUT_TICKS = 1200;
    private static final String ENGLISH = "en_us";
    private static final String GERMAN = "de_de";
    /** Polls between two "still waiting" lines of a wait step. */
    private static final int UNREADY_LOG_INTERVAL = 20;

    // --- camera views (relative to the lower corner of the dock) -----------------------------------------------------

    /**
     * The whole wall from the south, high enough that the racks between camera and wall stay below the sight line.
     * Centred on the wall and far enough back for all of it: the fifth board (M25) put six more columns on the east
     * end and the fluid board (M30) six more, and a camera that kept the old framing would have left the newest board
     * out of the one shot that claims to show the wall. The plaza is cleared further south than the nixie row for it
     * ({@link #CAMERA_CLEAR_Z}), because a camera inside a block photographs the inside of a block.
     */
    private static final CameraView WALL = CameraView.of("wall", WALL_CENTRE, 6.1, 11.0, WALL_CENTRE, 2.4, WALL_Z);
    /** Warehouse and wall together, from above the west end of the aisle. */
    private static final CameraView OVERVIEW = CameraView.of("overview", -4.0, 9.0, 10.0, 16.0, 2.5, -5.0);
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
    /** Rows the throughput source writes once its window holds a whole minute ({@code CraneThroughputDisplaySource}). */
    private static final int ROWS_WITH_NUMBERS = 4;
    /** Shot label of the German chapter, which reads and photographs the same wall in the other language. */
    private static final String GERMAN_MOMENT = "german";

    /** Switches the client's language for the German chapter ({@link VisualLanguage}). */
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    /** What the displays must carry, built from the controller's own numbers on the server thread. */
    private volatile Expectation expectation = Expectation.EMPTY;
    /** The total stock of the previous reading, so the second half of a pass can prove that the displays moved. */
    private volatile long previousItems = -1L;
    /** The same for the fluid, so the fluid board's pair of shots has to differ too (M30, issue #21). */
    private volatile long previousFluid = -1L;
    /** Polls of a wait step, so a step that hangs says why instead of only timing out. */
    private int unreadyPolls;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.client("display: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "display run"))
                .server("display: clear the area and place the creative motor", DisplayVisualScenario::placeMotor)
                .server("display: build the aisle with its stations and pre-stocked racks",
                        DisplayVisualScenario::buildAisle)
                .server("display: build the display wall, the nixie row and the stray terminal",
                        DisplayVisualScenario::buildDisplays)
                .serverUntil("display: wait until the controller has indexed every rack", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("display: a player names the aisle", DisplayVisualScenario::nameTheAisle)
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
        // And the fluid moves too, so the "updated" shots of the fluid board and of the summary's fluid line show
        // something the "resting" ones did not (M30, issue #21). A pipe is what would do this in a real warehouse;
        // the bay's own handler is the same call with no plumbing to build.
        script.server("display: a pump tops up the lava bay", DisplayVisualScenario::topUpFirstBay)
                .serverUntil("display: wait until the crane stored everything", DisplayVisualScenario::allStored,
                        ALL_STORED_TIMEOUT_TICKS)
                // The "resting" moment above read the throughput board while its window was still short, which is the
                // honest "Measuring" case. The numbers are what the source is for, so the run waits for them before
                // the second reading (M25, issue #16).
                .serverUntil("display: wait until the crane's window holds a whole minute", this::windowIsAFullMinute,
                        FULL_MINUTE_TIMEOUT_TICKS)
                .waitTicks(REFRESH_WAIT_TICKS);
        readAndCheck(script, UPDATED);
        script.client("display: check that the displays followed the warehouse", this::checkChanged)
                .shotFrom(WALL, UPDATED).shotFrom(AISLE_BOARD.view(), UPDATED).shotFrom(STOCK_BOARD.view(), UPDATED)
                .shotFrom(THROUGHPUT_BOARD.view(), UPDATED).shotFrom(FLUID_BOARD.view(), UPDATED)
                .shotFrom(NIXIE, UPDATED);
        if (pass == VisualPass.FLYWHEEL)
            germanChapter(script);
    }

    @Override
    public String status(VisualContext context) {
        Expectation expected = expectation;
        String crane = clientCrane(context).map(be -> be.craneState().phase().name()).orElse("missing");
        return String.format(Locale.ROOT,
                "crane=%s items=%d types=%d fluid=%dmB filtered=%s aisle='%s' stock0='%s' crane0='%s' fluid0='%s'",
                crane, expected.items(), expected.itemTypes(), expected.fluidMillibuckets(), expected.filtered(),
                boardLine(context, AISLE_BOARD, 0), boardLine(context, STOCK_BOARD, 0),
                boardLine(context, CRANE_BOARD, 0), boardLine(context, FLUID_BOARD, 0));
    }

    /**
     * The same wall in German, which is the only gate on whether a translated row <b>fits the flaps of a real
     * board</b>.
     * <p>
     * A display board caches the string it draws when the packet arrives ({@code FlapDisplaySection#refresh}), so a
     * language switch alone changes nothing a camera could see: every link is pulled again afterwards, and only then
     * are the boards read, checked and photographed. {@link #checkFits} is what this chapter is for — German is where
     * a row runs out of flaps first, and "Kein Regalbediengerät" is 21 characters against "No crane"'s 8.
     * <p>
     * The expectation is re-read too, because the sources hand out <b>translatable components</b> and the integrated
     * server resolves them through the very language the client just loaded: the German check is therefore German on
     * both sides, and not a German board compared against English text.
     */
    private void germanChapter(VisualScript script) {
        language.switchTo(script, "display: ", GERMAN);
        // A resource reload costs real seconds, and the crane's window is a rolling minute: without fresh work the
        // German throughput board would honestly, and uselessly, read four zeroes. So the warehouse is given something
        // to do first, and the boards are only read once it has done it.
        script.server("display: feed the warehouse once more, so the German boards carry earned numbers",
                        DisplayVisualScenario::feedGermanDelivery)
                .serverUntil("display: wait until the crane stored the German delivery",
                        DisplayVisualScenario::allStored, ALL_STORED_TIMEOUT_TICKS)
                .server("display: pull every link again, so the boards rebuild their rows in German",
                        DisplayVisualScenario::repullEveryLink)
                .waitTicks(REFRESH_WAIT_TICKS);
        readAndCheck(script, GERMAN_MOMENT);
        script.shotFrom(WALL, GERMAN_MOMENT).shotFrom(AISLE_BOARD.view(), GERMAN_MOMENT)
                .shotFrom(THROUGHPUT_BOARD.view(), GERMAN_MOMENT).shotFrom(CRANE_BOARD.view(), GERMAN_MOMENT)
                .shotFrom(STOCK_BOARD.view(), GERMAN_MOMENT).shotFrom(FLUID_BOARD.view(), GERMAN_MOMENT)
                .shotFrom(NO_AISLE_BOARD.view(), GERMAN_MOMENT);
        language.switchTo(script, "display: ", ENGLISH);
        script.server("display: pull every link again, so the boards read English for the next pass",
                        DisplayVisualScenario::repullEveryLink)
                .waitTicks(REFRESH_WAIT_TICKS);
        readAndCheck(script, "back in english");
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
                dock.offset(WALL_EAST + CLEAR_MARGIN, CLEAR_HEIGHT, CAMERA_CLEAR_Z)))
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

        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, layout.sideDirection(OUTPUT.side()).getOpposite()));
        // Two fluid bays, filled through their own handler the way a pump or a bucket fills them (M30, issue #21).
        // They are storage locations that hold no items, so every item number of this scene is unaffected by them,
        // which is exactly what the warehouse summary's four core lines have to go on showing.
        for (FluidBay bay : FLUID_BAYS) {
            level.setBlockAndUpdate(layout.rackPos(bay.rack()), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                    .setValue(FluidBayBlock.FACING, layout.sideDirection(bay.rack().side())));
            fillBay(level, layout.rackPos(bay.rack()), bay.fluid(), bay.millibuckets());
        }

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
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(WALL_WEST, 0, WALL_Z - 1),
                dock.offset(WALL_EAST, BOARD_TOP_Y + 1, WALL_Z - 1)))
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

    /**
     * Names the warehouse's one aisle, by the letter the warehouse itself gave it (M25, issue #15, ADR-038) — never by
     * a letter this file assumed, which is the one way a naming check can pass while naming the wrong aisle.
     * <p>
     * It is the <b>name</b> and not the gesture that the board is about, so the name goes in through the controller's
     * own setter rather than through a right-click; the click itself is covered by the GameTests, which can assert
     * what the player was told as well.
     */
    private static void nameTheAisle(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        char letter = controller.aisleLetter();
        String stored = controller.setAisleName(letter, AISLE_NAME);
        if (!stored.equals(AISLE_NAME))
            throw new VisualTestException("aisle " + letter + " was stored as '" + stored + "' instead of '"
                    + AISLE_NAME + "'");
        if (controller.namedAisles().size() != 1)
            throw new VisualTestException("this warehouse has one aisle and should carry one name, but carries "
                    + controller.namedAisles());
        LOGGER.info(PREFIX + "display: aisle {} is now called '{}'", letter, stored);
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
        BranchLayout layout = layout(dock);

        // The dock's north face: its top carries the crane's mast, and the rack position beside it is never visited.
        link(level, dock, Direction.NORTH, CRANE_BOARD.controllerPos(dock), WareworksDisplaySources.CRANE_STATUS);
        // The dock's south face, for its second source (M25, issue #16): one block can drive two boards, and the two
        // sources are deliberately separate rather than four more lines on "Crane Status", which already fills a
        // four-row board.
        link(level, dock, Direction.SOUTH, THROUGHPUT_BOARD.controllerPos(dock),
                WareworksDisplaySources.CRANE_THROUGHPUT);
        link(level, dock.relative(AISLE.getOpposite()), Direction.UP, AISLE_BOARD.controllerPos(dock),
                WareworksDisplaySources.AISLE_SUMMARY);
        link(level, layout.rackPos(TERMINAL), Direction.UP, STOCK_BOARD.controllerPos(dock),
                WareworksDisplaySources.STOCK_LIST);
        link(level, layout.rackPos(OUTPUT), Direction.UP, dock.offset(NIXIE_FIRST_X, NIXIE_Y, NIXIE_Z),
                WareworksDisplaySources.FILTERED_STOCK);
        link(level, strayTerminal(dock), Direction.UP, NO_AISLE_BOARD.controllerPos(dock),
                WareworksDisplaySources.AISLE_SUMMARY);
        // The controller's north face, for its third source (M30, issue #21): one block drives two boards here as the
        // dock does, and "Fluid Stock" is deliberately a source of its own rather than more rows on "Stock List",
        // because that list renders one number column and 64 iron ingots must not stand in it beside 64 000 mB.
        link(level, dock.relative(AISLE.getOpposite()), Direction.NORTH, FLUID_BOARD.controllerPos(dock),
                WareworksDisplaySources.FLUID_STOCK);
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
        // The chest-backed interfaces, plus the two fluid bays: a fluid bay is a storage location that holds no items.
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length
                + FLUID_BAYS.size();
        // A terminal takes items out of the aisle as well, so it counts as an output station beside the output block.
        boolean ready = controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocationCount() == expectedStorage
                && controller.inputStations().size() == 1 && controller.outputStations().size() == EXPECTED_OUTPUTS
                && crane.isControllerLinked() && controller.stockIndex().distinctKeys() == PRESTOCKED.size()
                && controller.fluidStockIndex().distinctKeys() == FLUID_BAYS.size();
        return ready || logUnready(String.format(Locale.ROOT,
                "status=%s dirty=%s pending=%d storage=%d/%d inputs=%d outputs=%d/%d linked=%s keys=%d/%d",
                controller.status(), controller.isMembershipDirty(), controller.pendingSnapshotCount(),
                controller.storageLocationCount(), expectedStorage, controller.inputStations().size(),
                controller.outputStations().size(), EXPECTED_OUTPUTS, crane.isControllerLinked(),
                controller.stockIndex().distinctKeys(), PRESTOCKED.size())
                + " fluids=" + controller.fluidStockIndex().distinctKeys() + "/" + FLUID_BAYS.size());
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

    private static void feedGermanDelivery(MinecraftServer server, VisualContext context) {
        fillInput(server.overworld(), layout(context.origin()).rackPos(INPUT), GERMAN_DELIVERY);
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
        StockView<FluidKey, RackPosition> fluid = controller.fluidStockIndex();

        // The four lines a board of every size shows, and below them the names a player gave the aisles — the one
        // optional line of this source that this warehouse has anything to say on (M25, issue #15). It is last on
        // purpose, so a four-row board drops it rather than a number a player asked for, which is why the aisle board
        // of this wall is a row taller than the others.
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
                        WareworksLang.number(stock.totalItems())).getString(),
                // Built with the board's own character count, exactly as the source builds it: the row is bounded by
                // the width of the target as well as by an entry count (M25 review fix), so an expectation that left
                // the width out would be a second, kinder rule than the one that runs.
                // The fluid line, the FIRST of this source's optional ones since M30 (issue #21): it is the fifth
                // stock number, and a board is hung on a warehouse that holds fluid because of it. The names line
                // keeps the last place, which is the one a short board drops.
                WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_LINE_FLUIDS,
                        WareworksLang.number(fluid.distinctKeys()),
                        fluidAmount(fluid.totalItems())).getString(),
                WareworksLang.aisleNamesLine(controller.namedAisles(), NetworkGoggleInfo.NAMES_LISTED, BOARD_CHARS)
                        .getString());
        List<String> throughputLines = readThroughputLines(level, dock);
        List<String> fluidLines = readFluidLines(fluid);

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
        previousFluid = expectation.fluidMillibuckets();
        expectation = new Expectation(aisleLines, stock.distinctKeys(), stock.totalItems(),
                top == null ? "" : top.getItem().getDescription().getString(), topCount, filtered, "",
                throughputLines, fluidLines, fluid.totalItems());
        LOGGER.info(PREFIX + "display: the warehouse reads {}", expectation);
    }

    /**
     * What the throughput board must carry, read off the dock and <b>pulled in the same server tick</b> (M25, issue
     * #16).
     * <p>
     * The pull is what makes this check possible at all: the window is a <b>rolling</b> minute, so every share moves
     * while it rolls, and a reading taken a few ticks before or after the board's own pull would differ from it by a
     * percent or two for no reason a reader could ever see. Reading and pulling together is the same trick
     * {@link #expectJob} uses on the crane's job.
     * <p>
     * Four rows once the window holds a whole minute, and the single {@code Measuring} line before that — a board
     * cannot carry the "of the last 23 s" caveat the goggles carry, so it says nothing rather than something
     * misleading. Which of the two is expected comes from the dock's own answer, never from an assumption about how
     * long the run has taken.
     */
    private static List<String> readThroughputLines(ServerLevel level, BlockPos dock) {
        CraneThroughput measured = crane(level, dock).throughput();
        linkAt(level, dock.relative(Direction.SOUTH)).updateGatheredData();
        // This warehouse is one straight aisle, so the machine structurally never yaws: a turn here would be a bug in
        // the measurement, and the zero it prints instead is exactly what the fixed-row rule is for.
        if (measured.turnTicks() != 0 || measured.corners() != 0)
            throw new VisualTestException("the crane of a straight aisle reports " + measured.turnTicks()
                    + " turning tick(s) and " + measured.corners() + " corner(s): " + measured);
        if (!measured.isFullMinute())
            return List.of(
                    WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_MEASURING).getString());
        return List.of(
                WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_TRIPS,
                        WareworksLang.number(measured.trips())).getString(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_ITEMS,
                        WareworksLang.number(measured.items())).getString(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_BUSY,
                        WareworksLang.percent(measured.busyShare())).getString(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_TURNING,
                        WareworksLang.percent(measured.turnShare())).getString());
    }

    /**
     * What the fluid stock board must carry: one row per fluid, most first, as the source writes it — the amount, its
     * unit and then the fluid's name (M30, issue #21).
     * <p>
     * Built with Create's own {@code FluidFormatter} rather than written out here, so what is asserted is the
     * <b>source's</b> choice of unit and not this file's idea of it: {@code create.generic.unit.buckets} is "B" and
     * {@code millibuckets} is "mB" in every language Create ships, which is the whole reason the unit is not a word
     * this mod spells. A row written out here would pass while the real one said something else.
     */
    private static List<String> readFluidLines(StockView<FluidKey, RackPosition> fluid) {
        Map<FluidKey, Long> totals = new HashMap<>();
        for (FluidKey key : fluid.keys())
            totals.put(key, fluid.count(key));
        List<String> rows = new ArrayList<>(totals.size());
        for (KeyCount<FluidKey> entry : KeyCount.largestFirst(totals, totals.size(), FluidKey.ORDER)) {
            Couple<MutableComponent> amount =
                    FluidFormatter.asComponents((int) Math.min(Integer.MAX_VALUE, entry.count()), true);
            rows.add(amount.getFirst().getString() + amount.getSecond().getString() + " "
                    + entry.key().hoverName().getString());
        }
        return rows;
    }

    /** The warehouse summary's fluid amount: Create's pre-formatted number and unit, as that line carries it. */
    private static MutableComponent fluidAmount(long millibuckets) {
        Couple<MutableComponent> amount =
                FluidFormatter.asComponents((int) Math.min(Integer.MAX_VALUE, millibuckets), true);
        return amount.getFirst().append(DisplaySource.WHITESPACE).append(amount.getSecond());
    }

    /** A pump tops up the first fluid bay, so the fluid readouts move between the two readings of a pass. */
    private static void topUpFirstBay(MinecraftServer server, VisualContext context) {
        FluidBay bay = FLUID_BAYS.getFirst();
        fillBay(server.overworld(), layout(context.origin()).rackPos(bay.rack()), bay.fluid(), BAY_TOP_UP);
    }

    /** Pulls every link again, so every board rebuilds the text it draws — e.g. after a language switch. */
    private static void repullEveryLink(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        for (BlockPos source : List.of(dock.relative(Direction.NORTH), dock.relative(Direction.SOUTH),
                dock.relative(AISLE.getOpposite()).above(),
                dock.relative(AISLE.getOpposite()).relative(Direction.NORTH),
                layout.rackPos(TERMINAL).above(),
                layout.rackPos(OUTPUT).above(), strayTerminal(dock).above()))
            linkAt(level, source).updateGatheredData();
    }

    /**
     * Whether the crane's rolling window holds a whole minute, i.e. whether the throughput board has stopped saying
     * "Measuring". A board placed in a freshly built world says it for the first 1200 ticks, which is honest and is
     * asserted at the "resting" moment — but the numbers are what the source is for, so the run waits for them.
     */
    private boolean windowIsAFullMinute(MinecraftServer server, VisualContext context) {
        CraneThroughput measured = crane(server.overworld(), context.origin()).throughput();
        return measured.isFullMinute()
                || logUnready("the crane's window holds " + measured.observedSeconds() + " s of a minute");
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
                previous.topName(), previous.topCount(), previous.filtered(), String.join(" | ", lines),
                previous.throughputLines(), previous.fluidLines(), previous.fluidMillibuckets());
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

        List<String> fluidRows = boardLines(context, FLUID_BOARD);
        for (int i = 0; i < fluidRows.size(); i++)
            assertLine(fluidRows.get(i), i < expected.fluidLines().size() ? expected.fluidLines().get(i) : "",
                    "fluid stock row " + i);

        assertLine(nixieText(context), expected.filtered(), "the nixie row of the output");
        assertLine(boardLines(context, NO_AISLE_BOARD).getFirst(),
                WareworksLang.translateDirect(WareworksLang.DISPLAY_AISLE_NO_AISLE).getString(),
                "the board of the terminal without an aisle");
        checkThroughput(context);

        for (Board board : BOARDS)
            checkFits(context, board);
        LOGGER.info(PREFIX + "display: aisle {} | stock {} | crane {} | throughput {} | fluid {} | nixie '{}'", aisle,
                stock, boardLines(context, CRANE_BOARD), boardLines(context, THROUGHPUT_BOARD), fluidRows,
                nixieText(context));
    }

    /**
     * The throughput board against the dock's own reading of the same server tick (M25, issue #16).
     * <p>
     * Its two rules are deliberately the opposite of the goggle lines' and both are asserted here. <b>The rows are
     * fixed and a zero is printed</b> — {@code Turning: 0 %} on this straight aisle, which is the whole reason the
     * rule exists: a row that came and went with its value would move the three below it and push one off a four-row
     * board. And <b>while the window is short the board says one word</b> and no numbers, because a number about
     * twenty seconds shown as if it were about a minute is the one thing this source must not do.
     */
    private void checkThroughput(VisualContext context) {
        List<String> expected = expectation.throughputLines();
        List<String> shown = boardLines(context, THROUGHPUT_BOARD);
        for (int i = 0; i < shown.size(); i++) {
            String want = i < expected.size() ? expected.get(i) : "";
            assertLine(shown.get(i).trim(), want.trim(), "throughput board line " + i);
        }
        if (!expected.isEmpty() && expected.size() != 1 && expected.size() != ROWS_WITH_NUMBERS)
            throw new VisualTestException("the throughput source writes either one line or " + ROWS_WITH_NUMBERS
                    + " of them, never " + expected.size() + ": " + expected);
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
        // And the same for the fluid, or the fluid board's own pair of shots would prove nothing (M30, issue #21).
        long fluidBefore = previousFluid;
        long fluidNow = expectation.fluidMillibuckets();
        if (fluidNow <= fluidBefore)
            throw new VisualTestException("the warehouse holds " + fluidNow + " mB of fluid, so it did not grow past "
                    + "the " + fluidBefore + " of the first check; the fluid rows would show nothing new");
        LOGGER.info(PREFIX + "display: the aisle grew from {} to {} items and from {} to {} mB, and the displays "
                + "followed", before, now, fluidBefore, fluidNow);
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

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
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

    /**
     * Fills a fluid bay through its own <b>ungated</b> handler, which is the call a Mechanical Pump at its back face
     * and a player's bucket at its front both end up making. It refuses nothing a bay would not refuse, so a bay that
     * does not take the whole amount fails the run instead of quietly holding less than the expectation says.
     */
    private static void fillBay(ServerLevel level, BlockPos pos, Fluid fluid, int millibuckets) {
        FluidBayBlockEntity bay = WareworksBlockEntityTypes.FLUID_BAY.getNullable(level, pos);
        if (bay == null)
            throw new VisualTestException("the fluid bay at " + pos + " has no block entity");
        int filled = bay.fill(new FluidStack(fluid, millibuckets), false);
        if (filled != millibuckets)
            throw new VisualTestException("the fluid bay at " + pos + " took " + filled + " of " + millibuckets
                    + " mB");
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
     * What the displays must carry: the aisle summary lines (the four core ones, the fluid line and the names line
     * below them), the numbers behind them, the first stock list row, the output's filtered total, — while a job runs
     * — the crane status lines, joined for the log, the rows of the throughput board, and the rows of the fluid stock
     * board with the millibuckets behind them.
     */
    private record Expectation(List<String> aisleLines, int itemTypes, long items, String topName, long topCount,
            String filtered, String craneLines, List<String> throughputLines, List<String> fluidLines,
            long fluidMillibuckets) {
        static final Expectation EMPTY =
                new Expectation(List.of(), 0, 0L, "", 0L, "", "", List.of(), List.of(), 0L);
    }

    /**
     * One fluid bay of the aisle: where it stands and what is put in it (M30, issue #21).
     *
     * @param rack         its rack position, which is also its address
     * @param fluid        the fluid it is filled with; the bays are unfiltered, so the first fluid to arrive decides
     * @param millibuckets how much of it, below a copper bay's 64 buckets with room left for the top-up of two passes
     */
    private record FluidBay(RackPosition rack, Fluid fluid, int millibuckets) {
    }
}
