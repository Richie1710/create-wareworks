package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.client.gui.TerminalListTable;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ListTableLayout;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalListState;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.terminal.ListEntryStatus;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "listtable": the clipboard list question as a table (issue #36, ADR-069, {@code docs/warehouse-system.md}
 * §3.4.4), on the real terminal screen, in English and in German.
 * <p>
 * <b>The scene.</b> A stocked aisle with a warehouse terminal and one production station carrying the owner's chain:
 * one iron ingot makes nine nuggets, coal and a stick make four torches, and eight nuggets around a torch make a
 * lantern. The racks hold one ingot, two coal and two sticks, so the clipboard's {@value #ENTRIES} entries cover every
 * status the table knows: Diamond, Iron Nugget and Chest cannot be had at all (red, with the planner's reason and the
 * item it names), Glass and Lantern only partly (gold — the second lantern is short of an iron ingot), the torches are
 * made (blue, "0 from stock, 4 produced"), and everything else comes from the racks (green). The first entry is
 * Polished Blackstone Bricks, whose name is too long for its column in either language, so the table has to shorten
 * it. The Missing section sums what the list is short of: Glass 44, Diamond 3, Iron Ingot 2 (one for the lantern, one
 * for the nuggets) and Chest 2.
 * <p>
 * <b>Real input.</b> The list button, the Cancel and Confirm buttons are clicked, the wheel is turned over the table
 * and the mouse rests on a name and on a material, all through {@link ScreenInput} at points the table and the screen
 * compute themselves; nothing calls the table's scroll or tooltip methods to pretend a player did. The window is put
 * to the floor every layout is measured against (1280 x 960 at GUI scale 4, 320 x 240) and back.
 * <p>
 * <b>Every number is asserted before it is photographed</b>: the question's totals as the server measured them, each
 * row's status in table order, the reasons as the planner names them, the Missing totals, that no drawn text is wider
 * than its column, and that asking started nothing on the server. {@link SceneItemCensus} brackets the Cancel and the
 * Confirm. The first render pass runs the English round and then the German one, both ending with Cancel; the second
 * pass (Flywheel off) asks once more in German and answers Confirm, which must start the list without a second
 * question.
 */
public final class ListTableVisualScenario implements VisualScenario {
    public static final String NAME = "listtable";

    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION = RackPosition.of(2, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 3;
    private static final int STORAGE_LEVELS = 2;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;
    /** A run of two language rounds and two window sizes, well above the harness's four minutes. */
    private static final long RUN_TIMEOUT_MILLIS = 8L * 60L * 1000L;

    // --- the chain ----------------------------------------------------------------------------------------------------
    private static final ItemKey INGOT = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey NUGGET = ItemKey.of(Items.IRON_NUGGET);
    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey STICK = ItemKey.of(Items.STICK);
    private static final ItemKey TORCH = ItemKey.of(Items.TORCH);
    private static final ItemKey LANTERN = ItemKey.of(Items.LANTERN);
    private static final ItemKey GLASS = ItemKey.of(Items.GLASS);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    /** The entry whose name no column of the table can hold. */
    private static final ItemKey LONG_NAME = ItemKey.of(Items.POLISHED_BLACKSTONE_BRICKS);

    /** One entry of the clipboard, and what the racks hold of its item. */
    private record Entry(ItemKey key, int wanted, int inStock) {
    }

    /** The clipboard, in list order: the long name first, so its row is on the first page of green rows. */
    private static final List<Entry> CLIPBOARD = List.of(new Entry(LONG_NAME, 16, 16),
            new Entry(ItemKey.of(Items.COBBLESTONE), 256, 256), new Entry(GLASS, 64, 20), new Entry(LANTERN, 2, 0),
            new Entry(TORCH, 4, 0), new Entry(DIAMOND, 3, 0), new Entry(ItemKey.of(Items.STONE), 32, 32),
            new Entry(ItemKey.of(Items.OAK_PLANKS), 16, 16), new Entry(NUGGET, 9, 0), new Entry(CHEST, 2, 0),
            new Entry(ItemKey.of(Items.ANDESITE), 16, 16), new Entry(ItemKey.of(Items.GRANITE), 16, 16),
            new Entry(ItemKey.of(Items.DIORITE), 16, 16), new Entry(ItemKey.of(Items.BRICKS), 16, 16),
            new Entry(ItemKey.of(Items.SANDSTONE), 16, 16), new Entry(ItemKey.of(Items.SMOOTH_STONE), 16, 16));
    private static final int ENTRIES = 16;
    /** Entries per clipboard page: two pages, so the list order reads across a page break. */
    private static final int PER_PAGE = 8;
    /** The chain's raw materials, which no entry asks for. */
    private static final Map<ItemKey, Integer> CHAIN_STOCK = Map.of(INGOT, 1, COAL, 2, STICK, 2);

    // --- what the question must say (see the class comment) ---------------------------------------------------------
    private static final long WANTED = 500L;
    private static final long FROM_STOCK = 436L;
    private static final long PRODUCED = 5L;
    private static final long MISSING = 59L;
    /** The table's rows: the entries, the Missing heading and its four materials. */
    private static final int ROWS = ENTRIES + 1 + 4;
    private static final List<ItemKey> IMPOSSIBLE = List.of(DIAMOND, NUGGET, CHEST);
    private static final List<ItemKey> PARTLY = List.of(GLASS, LANTERN);
    private static final List<ItemKey> CRAFTED = List.of(TORCH);
    private static final List<ItemKey> SHORT_OF = List.of(GLASS, DIAMOND, INGOT, CHEST);
    private static final List<Long> SHORT_AMOUNTS = List.of(44L, 3L, 2L, 2L);
    /** Wheel notches turned down in the first scroll step. */
    private static final int NOTCHES = 3;

    // --- windows ------------------------------------------------------------------------------------------------------
    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int FLOOR_GUI_SCALE = 4;
    private static final int FLOOR_GUI_WIDTH = 320;
    private static final int FLOOR_GUI_HEIGHT = 240;

    // --- timing -------------------------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int START_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 4;
    /** Ticks the screen is watched after the Yes: a second list question would have arrived by then. */
    private static final int NO_SECOND_QUESTION_TICKS = 40;
    private static final String GERMAN = "de_de";

    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.1, 1.6, 0.5, 1.4, 0.9, 1.2);

    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);
    /** Server thread: the census box of the aisle and what it held after the build. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    private volatile Map<ItemKey, Long> baseline = Map.of();
    /** The English status words, so the German round can prove it reads other ones. */
    private final List<String> englishStatus = new ArrayList<>();
    private int windowWidth;
    private int windowHeight;
    private int guiWidth;
    private int guiHeight;
    private int guiScaleOption;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.client("listtable: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "listtable run"))
                .server("listtable: clear the area and place the creative motor", this::placeMotor)
                .server("listtable: build the aisle, the terminal, the station and the stocked racks",
                        ListTableVisualScenario::buildAisle)
                .serverUntil("listtable: wait until the controller has indexed every rack",
                        ListTableVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("listtable: put the clipboard into the terminal's list slot",
                        ListTableVisualScenario::putClipboard)
                .server("listtable: baseline census of the aisle", (server, context) -> {
                    baseline = SceneItemCensus.take(server.overworld(), censusBox);
                    LOGGER.info(PREFIX + "listtable: baseline census {}", SceneItemCensus.describe(baseline));
                });
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.camera(AT_TERMINAL);
        if (pass == VisualPass.FLYWHEEL) {
            round(script, "");
            language.switchTo(script, "listtable: ", GERMAN);
            round(script, "-de");
            return;
        }
        // Flywheel off, still in German: ask once more and say Yes. The order starts, and no second question follows.
        ask(script);
        parkAndShoot(script, "table-top");
        script.client("listtable: answer the question with Confirm", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.terminalConfirmButton(screen(context), true)))
                .serverUntil("listtable: the list order is running", ListTableVisualScenario::orderStarted,
                        START_TIMEOUT_TICKS)
                .waitTicks(NO_SECOND_QUESTION_TICKS)
                .client("listtable: no second list question came up", context -> {
                    if (screen(context).listConfirmation() != null)
                        throw new VisualTestException("the Yes was asked about a second time");
                    if (!screen(context).listState().active())
                        throw new VisualTestException("the screen does not show the running list");
                })
                .server("listtable: census after the Yes", (server, context) -> SceneItemCensus.assertEquals(
                        server.overworld(), censusBox, baseline, "listtable: the Yes moved no item out of the aisle"))
                .client("listtable: close the screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("listtable: the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS)
                .client("listtable: every check passed",
                        context -> LOGGER.info(PREFIX + "listtable: ALL CHECKS PASSED in English and German"));
    }

    @Override
    public String status(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (!(open instanceof WarehouseTerminalScreen terminal))
            return "screen=" + (open == null ? "none" : open.getClass().getSimpleName());
        TerminalListTable table = terminal.listTable();
        if (table == null)
            return "screen=terminal table=none list=" + terminal.listState().active();
        return String.format(Locale.ROOT, "screen=terminal rows=%d visible=%d scroll=%d/%d overflows=%s %s",
                table.rowCount(), table.visibleRows(), table.scrollRow(), table.maxScroll(), table.overflows(),
                table.describeColumns());
    }

    // --- one language round -----------------------------------------------------------------------------------------

    /** Ask, read, shoot, scroll, hover, shrink the window, and say no. */
    private void round(VisualScript script, String suffix) {
        boolean german = !suffix.isEmpty();
        ask(script);
        script.client("listtable: check the status words are " + (german ? "German" : "English"), context -> {
            List<String> words = statusWords(ScreenInput.terminalListTable(screen(context)));
            if (!german) {
                englishStatus.clear();
                englishStatus.addAll(words);
            } else if (words.equals(englishStatus)) {
                throw new VisualTestException("the German table shows the English status words " + words);
            }
            LOGGER.info(PREFIX + "listtable: status words {}", words);
        });
        parkAndShoot(script, "table-top" + suffix);

        // The long name, shortened in its column and whole in the tooltip over it.
        script.client("listtable: rest the mouse on the shortened name", context -> {
            TerminalListTable table = ScreenInput.terminalListTable(screen(context));
            int row = rowOf(table, LONG_NAME, TerminalListTable.Kind.ENTRY);
            ScreenInput.hover(context.minecraft(), ScreenInput.terminalListPoint(table.nameCentre(row), "that name"));
        }).waitTicks(SETTLE_TICKS).client("listtable: the tooltip names it in full", context -> {
            TerminalListTable table = ScreenInput.terminalListTable(screen(context));
            int row = rowOf(table, LONG_NAME, TerminalListTable.Kind.ENTRY);
            TerminalListTable.RowText text = table.rowTexts().get(row);
            String full = LONG_NAME.toStack().getHoverName().getString();
            if (text.nameShown().equals(full) || !text.nameShown().endsWith(CommonComponents.ELLIPSIS.getString()))
                throw new VisualTestException("the long name is not shortened: '" + text.nameShown() + "'");
            List<Component> tooltip = tooltipUnderMouse(context, table);
            if (tooltip.isEmpty() || !tooltip.get(0).getString().equals(full))
                throw new VisualTestException("the name's tooltip is " + strings(tooltip) + ", expected '" + full
                        + "'");
            LOGGER.info(PREFIX + "listtable: '{}' is shown as '{}' with the tooltip {}", full, text.nameShown(),
                    strings(tooltip));
        }).shot("table-name" + suffix);

        // The real wheel: three notches down, then to the end, where the Missing section is.
        for (int notch = 1; notch <= NOTCHES; notch++) {
            int number = notch;
            script.client("listtable: one wheel notch down over the table (" + number + " of " + NOTCHES + ")",
                    context -> ScreenInput.scroll(context.minecraft(), centre(context), -1.0));
        }
        script.client("listtable: the table moved " + NOTCHES + " rows", context -> requireScroll(context, NOTCHES));
        parkAndShoot(script, "table-scrolled" + suffix);
        script.client("listtable: turn the wheel down to the end of the table", context -> {
            TerminalListTable table = ScreenInput.terminalListTable(screen(context));
            for (int i = 0; i < table.rowCount(); i++)
                ScreenInput.scroll(context.minecraft(), centre(context), -1.0);
            requireScroll(context, table.maxScroll());
        }).client("listtable: rest the mouse on the missing iron ingot", context -> {
            TerminalListTable table = ScreenInput.terminalListTable(screen(context));
            int row = rowOf(table, INGOT, TerminalListTable.Kind.SHORTAGE);
            ScreenInput.hover(context.minecraft(), ScreenInput.terminalListPoint(table.statusCentre(row),
                    "the iron ingot's row"));
        }).waitTicks(SETTLE_TICKS).client("listtable: the tooltip names the entries that need it", context -> {
            TerminalListTable table = ScreenInput.terminalListTable(screen(context));
            List<String> tooltip = strings(tooltipUnderMouse(context, table));
            String neededBy = WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_MISSING_NEEDED_BY)
                    .getString();
            String lantern = LANTERN.toStack().getHoverName().getString();
            String nugget = NUGGET.toStack().getHoverName().getString();
            if (!tooltip.contains(neededBy) || tooltip.stream().noneMatch(line -> line.startsWith(lantern + " ("))
                    || tooltip.stream().noneMatch(line -> line.startsWith(nugget + " (")))
                throw new VisualTestException("the iron ingot's tooltip is " + tooltip);
            LOGGER.info(PREFIX + "listtable: the missing iron ingot's tooltip {}", tooltip);
        }).shot("table-missing" + suffix);
        script.client("listtable: turn the wheel up past the top", context -> {
            TerminalListTable table = ScreenInput.terminalListTable(screen(context));
            for (int i = 0; i < table.rowCount() + 2; i++)
                ScreenInput.scroll(context.minecraft(), centre(context), 1.0);
            requireScroll(context, 0);
        });

        floor(script, suffix);

        script.client("listtable: answer the question with Cancel", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalConfirmButton(screen(context), false)))
                .until("listtable: the question is gone", context -> !screen(context).isAskingSomething(),
                        SCREEN_TIMEOUT_TICKS)
                .server("listtable: Cancel started nothing", ListTableVisualScenario::checkNothingStarted)
                .server("listtable: census after Cancel", (server, context) -> SceneItemCensus.assertEquals(
                        server.overworld(), censusBox, baseline, "listtable: Cancel moved nothing"));
    }

    /** Opens the screen when it is not open, presses Fetch with a real click and checks the table it raises. */
    private void ask(VisualScript script) {
        script.server("listtable: open the terminal screen", (server, context) -> {
                    if (!(context.minecraft().screen instanceof WarehouseTerminalScreen)
                            && !terminal(server.overworld(), context.origin()).openScreen(context.serverPlayer(server)))
                        throw new VisualTestException("the terminal screen could not be opened");
                })
                .until("listtable: the screen shows its stock and the clipboard",
                        context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                                && terminal.hasStock() && !terminal.isAskingSomething(),
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("listtable: press the terminal's list button", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.terminalListButton(screen(context))))
                .until("listtable: the list question is on screen", context -> screen(context).listTable() != null,
                        SCREEN_TIMEOUT_TICKS)
                .client("listtable: check what the question and its table say", ListTableVisualScenario::checkTable)
                .server("listtable: asking started nothing", ListTableVisualScenario::checkNothingStarted);
    }

    /** The floor every layout is measured against: 1280 x 960 at GUI scale 4, i.e. 320 x 240, and back again. */
    private void floor(VisualScript script, String suffix) {
        script.client("listtable: remember the window and the GUI scale", context -> {
            Window window = context.minecraft().getWindow();
            windowWidth = window.getScreenWidth();
            windowHeight = window.getScreenHeight();
            guiWidth = window.getGuiScaledWidth();
            guiHeight = window.getGuiScaledHeight();
            guiScaleOption = context.minecraft().options.guiScale().get();
        }).client("listtable: make the window " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                context -> context.minecraft().getWindow().setWindowed(FLOOR_WIDTH, FLOOR_HEIGHT));
        ArmVisualScenario.untilOrFail(script, "listtable: the framebuffer is " + FLOOR_WIDTH + "x" + FLOOR_HEIGHT,
                context -> TooltipsVisualScenario.synced(context) && TooltipsVisualScenario.framebufferIs(
                        context.minecraft().getWindow(), FLOOR_WIDTH, FLOOR_HEIGHT),
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("listtable: GUI scale " + FLOOR_GUI_SCALE, context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.options.guiScale().set(FLOOR_GUI_SCALE);
            minecraft.resizeDisplay();
        }).waitTicks(SETTLE_TICKS).client("listtable: the table fits the floor", context -> {
            Window window = context.minecraft().getWindow();
            if (window.getGuiScaledWidth() != FLOOR_GUI_WIDTH || window.getGuiScaledHeight() != FLOOR_GUI_HEIGHT)
                throw new VisualTestException("the floor is " + describeWindow(window));
            WarehouseTerminalScreen screen = screen(context);
            TerminalListTable table = ScreenInput.terminalListTable(screen);
            TerminalListTable.Bounds bounds = table.panelBounds();
            if (table.visibleRows() < ListTableLayout.MIN_VISIBLE_ROWS || bounds.x() < 0 || bounds.y() < 0
                    || bounds.x() + bounds.width() > FLOOR_GUI_WIDTH || bounds.y() + bounds.height() > FLOOR_GUI_HEIGHT)
                throw new VisualTestException("at the floor the table shows " + table.visibleRows()
                        + " rows in the panel " + bounds);
            // Both buttons are where the screen's own hit test finds them, below the rows.
            ScreenInput.Point confirm = ScreenInput.terminalConfirmButton(screen, true);
            ScreenInput.Point cancel = ScreenInput.terminalConfirmButton(screen, false);
            if (confirm.y() <= table.tableCentre().y() || cancel.y() != confirm.y())
                throw new VisualTestException("the buttons are not below the table: " + confirm + " " + cancel);
            if (table.overflows())
                throw new VisualTestException("a text of the table is wider than its column at the floor");
            LOGGER.info(PREFIX + "listtable: at the floor the panel is {} with {} rows, {}", bounds,
                    table.visibleRows(), table.describeColumns());
        });
        parkAndShoot(script, "table-floor" + suffix);
        script.client("listtable: put the window back",
                context -> context.minecraft().getWindow().setWindowed(windowWidth, windowHeight));
        ArmVisualScenario.untilOrFail(script, "listtable: the window is back",
                context -> TooltipsVisualScenario.synced(context)
                        && context.minecraft().getWindow().getScreenWidth() == windowWidth
                        && context.minecraft().getWindow().getScreenHeight() == windowHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("listtable: put the GUI scale back", context -> {
            context.minecraft().options.guiScale().set(guiScaleOption);
            context.minecraft().resizeDisplay();
        });
        ArmVisualScenario.untilOrFail(script, "listtable: the GUI is as large as before",
                context -> context.minecraft().getWindow().getGuiScaledWidth() == guiWidth
                        && context.minecraft().getWindow().getGuiScaledHeight() == guiHeight
                        && screen(context).width == guiWidth && screen(context).listTable() != null,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
    }

    // --- checks (client thread) -------------------------------------------------------------------------------------

    /**
     * The question as the server measured it and the table as it is drawn: the totals, every row's status in table
     * order with its reason, the Missing section, and no text wider than its column.
     */
    private static void checkTable(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        ListOrderConfirmation<ItemKey> question = screen.listConfirmation();
        if (question == null)
            throw new VisualTestException("the terminal is not asking about the list");
        if (question.wanted() != WANTED || question.serveable() != FROM_STOCK || question.producing() != PRODUCED
                || question.missing() != MISSING || question.entries() != ENTRIES || question.truncated())
            throw new VisualTestException(String.format(Locale.ROOT,
                    "the question says wanted=%d fromStock=%d produced=%d missing=%d entries=%d dropped=%d, "
                            + "expected %d/%d/%d/%d/%d/0: %s", question.wanted(), question.serveable(),
                    question.producing(), question.missing(), question.entries(), question.entriesDropped(), WANTED,
                    FROM_STOCK, PRODUCED, MISSING, ENTRIES, question.lines()));
        TerminalListTable table = ScreenInput.terminalListTable(screen);
        List<TerminalListTable.RowText> rows = table.rowTexts();
        if (rows.size() != ROWS)
            throw new VisualTestException("the table has " + rows.size() + " rows, expected " + ROWS + ": " + rows);
        List<ListOrderConfirmation.Line<ItemKey>> sorted = question.tableOrder();
        int row = 0;
        row = expectRows(rows, sorted, row, ListEntryStatus.IMPOSSIBLE, IMPOSSIBLE);
        row = expectRows(rows, sorted, row, ListEntryStatus.PARTLY, PARTLY);
        row = expectRows(rows, sorted, row, ListEntryStatus.CRAFTED, CRAFTED);
        for (; row < ENTRIES; row++)
            if (rows.get(row).statusKind() != ListEntryStatus.AVAILABLE)
                throw new VisualTestException("row " + row + " should be available: " + rows.get(row));
        // The reasons, in the planner's own sentences about the item they name.
        expectDetail(rows, DIAMOND, reason(PlanRefusal.NO_PATTERN, DIAMOND));
        expectDetail(rows, NUGGET, reason(PlanRefusal.MISSING_INGREDIENT, INGOT));
        expectDetail(rows, TORCH, WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_DETAIL_CRAFTED,
                "0", "4").getString());
        expectDetail(rows, LANTERN, WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_DETAIL_PARTLY,
                "1", "2", Component.literal(reason(PlanRefusal.MISSING_INGREDIENT, INGOT))).getString());
        expectDetail(rows, GLASS, WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_TABLE_DETAIL_PARTLY,
                "20", "64", Component.literal(reason(PlanRefusal.NO_PATTERN, GLASS))).getString());
        TerminalListTable.RowText section = rows.get(ENTRIES);
        if (section.kind() != TerminalListTable.Kind.SECTION)
            throw new VisualTestException("the Missing heading is not after the entries: " + section);
        for (int i = 0; i < SHORT_OF.size(); i++) {
            TerminalListTable.RowText material = rows.get(ENTRIES + 1 + i);
            String name = SHORT_OF.get(i).toStack().getHoverName().getString();
            if (material.kind() != TerminalListTable.Kind.SHORTAGE || !material.nameFull().equals(name)
                    || !material.wanted().equals(String.valueOf(SHORT_AMOUNTS.get(i))))
                throw new VisualTestException("Missing row " + i + " should be " + name + " x" + SHORT_AMOUNTS.get(i)
                        + ": " + material);
        }
        for (TerminalListTable.RowText text : rows)
            if (text.kind() == TerminalListTable.Kind.ENTRY && text.statusKind() != null
                    && !text.status().equals(WareworksLang.translateDirect(text.statusKind().langKey()).getString()))
                throw new VisualTestException("a status word is shortened or wrong: " + text);
        if (table.overflows())
            throw new VisualTestException("a text of the table is wider than its column: " + table.describeColumns());
        if (table.visibleRows() < ListTableLayout.MIN_VISIBLE_ROWS || table.scrollRow() != 0)
            throw new VisualTestException("the table shows " + table.visibleRows() + " rows from row "
                    + table.scrollRow());
        LOGGER.info(PREFIX + "listtable: the question reads {} rows, {}; rows {}", rows.size(),
                table.describeColumns(), rows);
    }

    private static int expectRows(List<TerminalListTable.RowText> rows,
            List<ListOrderConfirmation.Line<ItemKey>> sorted, int start, ListEntryStatus status, List<ItemKey> keys) {
        for (int i = 0; i < keys.size(); i++) {
            int row = start + i;
            TerminalListTable.RowText text = rows.get(row);
            String name = keys.get(i).toStack().getHoverName().getString();
            if (text.statusKind() != status || !text.nameFull().equals(name)
                    || !sorted.get(row).key().equals(keys.get(i)))
                throw new VisualTestException("row " + row + " should be " + name + " " + status + ": " + text);
        }
        return start + keys.size();
    }

    private static void expectDetail(List<TerminalListTable.RowText> rows, ItemKey key, String detail) {
        String name = key.toStack().getHoverName().getString();
        TerminalListTable.RowText text = rows.stream()
                .filter(row -> row.kind() == TerminalListTable.Kind.ENTRY && row.nameFull().equals(name)).findFirst()
                .orElseThrow(() -> new VisualTestException("the table has no row for " + name));
        if (!text.detailFull().equals(detail))
            throw new VisualTestException("the detail of " + name + " is '" + text.detailFull() + "', expected '"
                    + detail + "'");
    }

    private static String reason(PlanRefusal refusal, ItemKey about) {
        return WareworksLang.translateDirect(refusal.langKey(), about.toStack().getHoverName()).getString();
    }

    private static List<String> statusWords(TerminalListTable table) {
        List<String> words = new ArrayList<>();
        for (TerminalListTable.RowText row : table.rowTexts())
            if (row.kind() == TerminalListTable.Kind.ENTRY && !words.contains(row.status()))
                words.add(row.status());
        return words;
    }

    private static int rowOf(TerminalListTable table, ItemKey key, TerminalListTable.Kind kind) {
        String name = key.toStack().getHoverName().getString();
        List<TerminalListTable.RowText> rows = table.rowTexts();
        for (int row = 0; row < rows.size(); row++)
            if (rows.get(row).kind() == kind && rows.get(row).nameFull().equals(name))
                return row;
        throw new VisualTestException("the table has no " + kind + " row for " + name + ": " + rows);
    }

    private static void requireScroll(VisualContext context, int expected) {
        TerminalListTable table = ScreenInput.terminalListTable(screen(context));
        if (table.scrollRow() != expected)
            throw new VisualTestException("the table is scrolled to row " + table.scrollRow() + ", expected "
                    + expected + " (max " + table.maxScroll() + ")");
        LOGGER.info(PREFIX + "listtable: the wheel scrolled the table to row {} of {}", table.scrollRow(),
                table.maxScroll());
    }

    /** The tooltip the table draws for the mouse where the mouse handler has it, in GUI coordinates. */
    private static List<Component> tooltipUnderMouse(VisualContext context, TerminalListTable table) {
        Minecraft minecraft = context.minecraft();
        Window window = minecraft.getWindow();
        int x = (int) (minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth());
        int y = (int) (minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
        return table.tooltipAt(x, y);
    }

    private static List<String> strings(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    private static ScreenInput.Point centre(VisualContext context) {
        TerminalListTable.Spot spot = ScreenInput.terminalListTable(screen(context)).tableCentre();
        return new ScreenInput.Point(spot.x(), spot.y());
    }

    /** Puts the cursor on the panel's padding, where nothing has a tooltip, then shoots. */
    private static void parkAndShoot(VisualScript script, String label) {
        script.client("listtable: park the mouse on the panel's edge", context -> {
            TerminalListTable.Bounds bounds = ScreenInput.terminalListTable(screen(context)).panelBounds();
            ScreenInput.hover(context.minecraft(), new ScreenInput.Point(bounds.x() + 2, bounds.y() + 2));
        }).waitTicks(SETTLE_TICKS).shot(label);
    }

    private static String describeWindow(Window window) {
        return String.format(Locale.ROOT, "window %dx%d, framebuffer %dx%d, GUI %dx%d at scale %.0f",
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale());
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + open + ")");
    }

    // --- checks (server thread) -------------------------------------------------------------------------------------

    private static void checkNothingStarted(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (terminal(level, context.origin()).hasListOrder())
            throw new VisualTestException("asking about the list started an order");
        WarehouseControllerBlockEntity controller = controller(level, context.origin());
        if (!controller.openRequests().isEmpty() || !controller.openProductionOrders().isEmpty())
            throw new VisualTestException("asking about the list made " + controller.openRequests().size()
                    + " requests and " + controller.openProductionOrders().size() + " production orders");
    }

    private static boolean orderStarted(MinecraftServer server, VisualContext context) {
        TerminalListState list = terminal(server.overworld(), context.origin()).listState();
        if (!list.active())
            return false;
        if (list.entries() != ENTRIES || list.wanted() != WANTED || list.truncated())
            throw new VisualTestException("the Yes started an order of " + list.entries() + " entries and "
                    + list.wanted() + " items, expected " + ENTRIES + " and " + WANTED);
        LOGGER.info(PREFIX + "listtable: the Yes started the list: {} entries, {} items, state {}", list.entries(),
                list.wanted(), list.state());
        return true;
    }

    // --- build (server thread) --------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-2, -1, -CLEAR_MARGIN),
                dock.offset(RAILS + 1, CLEAR_HEIGHT - 2, CLEAR_MARGIN));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        motor.generatedSpeed.setValue(MOTOR_RPM);
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
        level.setBlockAndUpdate(layout.rackPos(PRODUCTION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, layout.sideDirection(PRODUCTION.side()).getOpposite()));
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                layout.rackPos(PRODUCTION));
        if (station == null)
            throw new VisualTestException("the production station is missing");
        pattern(station, 0, NUGGET, 9, INGOT, 1);
        pattern(station, 1, TORCH, 4, COAL, 1, STICK, 1);
        pattern(station, 2, LANTERN, 1, NUGGET, 8, TORCH, 1);

        List<ItemStack> stock = new ArrayList<>();
        for (Entry entry : CLIPBOARD)
            if (entry.inStock() > 0)
                stock.add(entry.key().toStack(entry.inStock()));
        CHAIN_STOCK.forEach((key, count) -> stock.add(key.toStack(count)));
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
                    if (next < stock.size())
                        fill(level, chest, stock.get(next++));
                }
            }
        }
        if (next < stock.size())
            throw new VisualTestException("only " + next + " of " + stock.size() + " item types fit into the racks");
    }

    /** Pattern {@code slot}: {@code count} of {@code result} from the ingredient pairs, one grid cell each. */
    private static void pattern(WarehouseProductionBlockEntity station, int slot, ItemKey result, int count,
            Object... ingredients) {
        for (int i = 0; i < ingredients.length / 2; i++)
            if (!station.setPatternEntry(slot, i, (ItemKey) ingredients[2 * i], (Integer) ingredients[2 * i + 1]))
                throw new VisualTestException("ingredient " + i + " of pattern " + slot + " was not written");
        if (!station.setPatternEntry(slot, ProductionPatterns.RESULT_ENTRY, result, count))
            throw new VisualTestException("the result of pattern " + slot + " was not written");
    }

    /** Exactly {@code stack.getCount()} items of the stack's item into the chest, in as many stacks as it takes. */
    private static void fill(ServerLevel level, BlockPos chest, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("the chest at " + chest + " has no item handler");
        int left = stack.getCount();
        while (left > 0) {
            int portion = Math.min(left, stack.getMaxStackSize());
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copyWithCount(portion), false);
            if (!rest.isEmpty())
                throw new VisualTestException("the chest at " + chest + " is full");
            left -= portion;
        }
    }

    private static void putClipboard(MinecraftServer server, VisualContext context) {
        List<List<ClipboardEntry>> pages = new ArrayList<>();
        List<ClipboardEntry> page = new ArrayList<>();
        for (Entry entry : CLIPBOARD) {
            page.add(new ClipboardEntry(false, Component.literal(entry.key().toStack().getHoverName().getString()))
                    .displayItem(entry.key().toStack(), entry.wanted()));
            if (page.size() == PER_PAGE) {
                pages.add(List.copyOf(page));
                page.clear();
            }
        }
        if (!page.isEmpty())
            pages.add(List.copyOf(page));
        ItemStack clipboard = AllBlocks.CLIPBOARD.asStack();
        clipboard.set(AllDataComponents.CLIPBOARD_CONTENT, new ClipboardContent(ClipboardType.WRITTEN, pages, true));
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        ItemStack rest = terminal.listSlot().insertItem(WarehouseTerminalBlockEntity.LIST_SLOT, clipboard, false);
        if (!rest.isEmpty())
            throw new VisualTestException("the list slot did not take the clipboard");
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        int storage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        int types = (int) CLIPBOARD.stream().filter(entry -> entry.inStock() > 0).count() + CHAIN_STOCK.size();
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == storage
                && crane.isControllerLinked() && controller.stockIndex().distinctKeys() >= types
                && controller.productionStations().size() == 1 && controller.aislePatterns().size() == 3
                && controller.producibleKeys().contains(LANTERN);
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the controller is missing");
        return controller;
    }
}
