package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.TerminalCraftPanel;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.network.TerminalCraftAnswerPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
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
 * Scenario "craft": the crafting panel of the warehouse terminal (M32, issue #24, {@code docs/warehouse-system.md}
 * §3.4.5), driven like a player in the real screen.
 * <p>
 * It builds one short aisle with a powered crane, a warehouse terminal beside the dock and a rack of chests holding
 * what a chest and a cake are made of, opens the real screen through {@code WarehouseTerminalBlockEntity#openScreen}
 * and plays one <b>round</b> ({@link #round}): the toggle folds the panel out, a right-click on Oak Planks in the stock
 * list picks a ghost and left clicks stamp the chest's ring with it, Escape drops it, and the server's preview names
 * the Chest. The planks then come the honest way: the amount field is scrolled to 24 and a plain left click on Oak
 * Planks in the list <b>requests</b> them, so the crane really carries them into the buffer. A click on the result
 * crafts one chest, a Shift-click the other two; a cake shows the buckets it leaves in the buffer; and a crafting table
 * is refused because neither the buffer nor the inventory has room for it.
 * <p>
 * <b>A view of an earlier grid is never shown.</b> The crafting table is laid in three steps that keep the item and
 * change what the grid makes — sticks, the same two planks moved side by side into a pressure plate, then two planks
 * more — and each edit is checked in the same client step it is made in, before any tick could bring a new view: the
 * panel shows no result, and a click on the result crafts nothing but asks for the view at once, so no answer comes
 * back. A ghost hides every tooltip, the stock list's and the inventory slots' included, which is checked through the
 * screen's own tooltip methods and, as a control, again without the ghost.
 * <p>
 * <b>Input.</b> Every click, right-click, scroll and key press goes through {@link ScreenInput}, i.e. through the mouse
 * and keyboard handlers, at a point checked against the panel's own hit tests first ({@code TerminalCraftPanel#cellAt},
 * {@code #resultAt}) — and checked to be nothing else, no stock cell and no menu slot. Two gestures need Shift, which
 * the harness can never hold ({@link ScreenInput#requireNoModifiers}): the Shift-click on the result and the Shift +
 * right-click that writes an item into the next free cell. For those two the run checks the point the same way and
 * then calls the branch the screen takes for them ({@code TerminalCraftPanel#craft(true)},
 * {@code #fillNextFree}), and its step says so.
 * <p>
 * <b>Items.</b> A {@link SceneItemCensus} of the aisle <b>and of the player</b> brackets every craft: before the click
 * it must equal what the run expects, and after the answer it must have moved by exactly the recipe — eight planks out
 * and a chest in, or three milk buckets, two sugar, an egg and three wheat out and a cake and three buckets in — and by
 * nothing for the refused craft. Only two things put items into the scene that no crane carried, and both are declared
 * in the census: the full buffer and the full inventory the refusal needs ({@link #fillForRefusal}, seeded on the
 * server thread, because a crane delivers into a buffer but never into a player's pockets), and the emptying between
 * the two rounds ({@link #emptyForNextRound}).
 * <p>
 * <b>Languages and sizes.</b> The round is played twice, in English and in German (switched as the language screen
 * does, {@link VisualLanguage}), and every shot is taken in both; the German ones end in {@code -de}. Each round ends
 * at two more window sizes ({@link #sizeSteps}): 1280 x 960 at GUI scale 4, the 320 x 240 floor every scale is clamped
 * to, where the folded-out window has to move left and still keep its margin, and 1920 x 1080 at scale 4, where it
 * must not move at all. The window is put back afterwards.
 * <p>
 * The second render pass opens the screen once more and shoots it, because a GUI does not depend on Flywheel: the
 * panel and the grid this player laid come back by themselves, and the server previews the grid without a click.
 */
public final class CraftVisualScenario implements VisualScenario {
    public static final String NAME = "craft";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 5;
    private static final int MOTOR_RPM = 128;
    /** The terminal sits beside the dock, on the right rack side, as in the terminal scenario. */
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 2;
    private static final int STORAGE_LEVELS = 2;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    private static final ItemKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    private static final ItemKey MILK = ItemKey.of(Items.MILK_BUCKET);
    private static final ItemKey SUGAR = ItemKey.of(Items.SUGAR);
    private static final ItemKey EGG = ItemKey.of(Items.EGG);
    private static final ItemKey WHEAT = ItemKey.of(Items.WHEAT);
    private static final ItemKey CAKE = ItemKey.of(Items.CAKE);
    private static final ItemKey BUCKET = ItemKey.of(Items.BUCKET);
    private static final ItemKey CRAFTING_TABLE = ItemKey.of(Items.CRAFTING_TABLE);
    /** What the refusal fills the player's free inventory slots with: an item that is nowhere else in the scene. */
    private static final ItemKey INVENTORY_FILLER = ItemKey.of(Items.COBBLESTONE);
    private static final int FULL_STACK = 64;

    /** Item types in the racks: everything the three recipes take, and a few more so the list looks like a store. */
    private static final List<ItemStack> STOCK = List.of(new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.MILK_BUCKET, 1), new ItemStack(Items.SUGAR, 64), new ItemStack(Items.EGG, 16),
            new ItemStack(Items.WHEAT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_LOG, 32),
            new ItemStack(Items.GLASS, 48), new ItemStack(Items.STRING, 16), new ItemStack(Items.REDSTONE, 64),
            new ItemStack(Items.COAL, 64), new ItemStack(Items.LEATHER, 8));
    private static final int ITEM_TYPES = 12;
    /** Stacks of the same item put into one chest; a full stack of milk is one bucket, so 20 buckets. */
    private static final int STACKS_PER_LOCATION = 20;

    /** The chest's ring: every cell but the middle one. */
    private static final int[] CHEST_RING = { 0, 1, 2, 3, 5, 6, 7, 8 };
    private static final CraftGrid<ItemKey> CHEST_GRID = CraftGrid.of(PLANKS, PLANKS, PLANKS, PLANKS, null, PLANKS,
            PLANKS, PLANKS, PLANKS);
    private static final CraftGrid<ItemKey> CAKE_GRID = CraftGrid.of(MILK, MILK, MILK, SUGAR, EGG, SUGAR, WHEAT, WHEAT,
            WHEAT);
    private static final CraftGrid<ItemKey> TABLE_GRID = CraftGrid.of(PLANKS, PLANKS, null, PLANKS, PLANKS);
    /** Two planks one above the other, where the table is laid from: sticks. */
    private static final CraftGrid<ItemKey> STICK_GRID = CraftGrid.of(PLANKS, null, null, PLANKS);
    /** The same two planks side by side: an oak pressure plate. */
    private static final CraftGrid<ItemKey> PLATE_GRID = CraftGrid.of(PLANKS, PLANKS);
    /** Three chests' worth of planks, so one click and one Shift-click both have something to craft. */
    private static final int PLANKS_REQUESTED = 24;
    private static final int PLANKS_PER_CHEST = 8;
    private static final int CHESTS = PLANKS_REQUESTED / PLANKS_PER_CHEST;
    private static final int PLANKS_PER_TABLE = 4;

    // --- window sizes (design table 3.2: the 320 x 240 floor and 480 x 270) ------------------------------------------
    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int WIDE_WIDTH = 1920;
    private static final int WIDE_HEIGHT = 1080;
    private static final int SIZE_GUI_SCALE = 4;
    private static final int FLOOR_GUI_WIDTH = 320;
    private static final int FLOOR_GUI_HEIGHT = 240;
    /** Where the window stands at the floor: folded in it is centred, folded out it moves left and keeps 4 px. */
    private static final int FLOOR_LEFT_FOLDED_IN = 45;
    private static final int FLOOR_LEFT_FOLDED_OUT = 18;
    private static final int FLOOR_RIGHT_EDGE = 316;
    private static final int WIDE_GUI_WIDTH = 480;
    private static final int WIDE_GUI_HEIGHT = 270;
    /** At 480 GUI px the panel folds out to the right of a window that does not move. */
    private static final int WIDE_LEFT = 125;

    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";
    /** The width every tooltip line of the panel has to fit: the terminal's status row. */
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int PREVIEW_TIMEOUT_TICKS = 200;
    private static final int ANSWER_TIMEOUT_TICKS = 200;
    private static final int AMOUNT_TIMEOUT_TICKS = 200;
    private static final int REQUEST_TIMEOUT_TICKS = 200;
    private static final int DELIVERY_TIMEOUT_TICKS = 1200;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    /** Where the mouse is parked for a shot without a tooltip: the window's title row, on nothing at all. */
    private static final int TITLE_CORNER = 5;

    /** In the aisle, two blocks from the terminal, looking at its screen; inside the vanilla container range. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.1, 1.6, 0.5, 1.4, 0.9, 1.2);

    /** The aisle the census counts, around the whole scene; set by the build. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Server thread: what the census of the aisle and the player has to be right now. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Client thread: the answer on screen before the last craft click, so the step after it waits for a new one. */
    @Nullable
    private TerminalCraftAnswerPayload answerBefore;
    /** Client thread: where the window stood before the last fold, so a fold can be checked for a jump. */
    private int leftBeforeFold;
    /** Client thread: the window, its GUI size and the GUI scale option before the size checks, put back after. */
    private int windowWidth;
    private int windowHeight;
    private int guiWidth;
    private int guiHeight;
    private int guiScaleOption;
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    /** What one craft click is expected to end in, and what it does to the census. */
    private record CraftCheck(String what, TerminalCraftResult result, int crafted, ItemKey about,
            Map<ItemKey, Long> delta) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("craft: clear the area and place the creative motor", this::placeMotor)
                .server("craft: build the aisle, the terminal and the stocked racks", CraftVisualScenario::buildAisle)
                .serverUntil("craft: wait until the controller has indexed every rack", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("craft: the census every craft is measured against", (server, context) -> {
                    expected = SceneItemCensus.take(server.overworld(), censusBox, context.serverPlayer(server));
                    LOGGER.info(PREFIX + "craft: baseline census {}", SceneItemCensus.describe(expected));
                });
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("craft: open the terminal screen", CraftVisualScenario::openScreen)
                .until("craft: wait for the screen with its stock", CraftVisualScenario::screenReady,
                        SCREEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
        if (pass == VisualPass.FLYWHEEL) {
            round(script, "");
            emptyForNextRound(script);
            language.switchTo(script, "craft: ", GERMAN);
            round(script, "-de");
            language.switchTo(script, "craft: ", ENGLISH);
            script.client("craft: every check passed",
                    context -> LOGGER.info(PREFIX + "craft: ALL CHECKS PASSED in English and German"));
        } else {
            // A GUI does not depend on Flywheel, so the second pass only shows that the panel and the grid this player
            // laid come back with the screen, and that the server previews the grid without anybody clicking.
            ArmVisualScenario.untilOrFail(script, "craft: the reopened screen previews the remembered grid",
                    context -> previewShows(context, TABLE_GRID, Items.CRAFTING_TABLE), PREVIEW_TIMEOUT_TICKS,
                    context -> "the panel shows " + describePanel(screen(context)));
            script.client("craft: park the mouse in the title row", CraftVisualScenario::parkMouse)
                    .waitTicks(SETTLE_TICKS)
                    .shot("craft-reopened");
        }
        script.client("craft: close the screen", CraftVisualScenario::closeScreen)
                .until("craft: wait until the screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        Window window = context.minecraft().getWindow();
        String size = String.format(Locale.ROOT, "gui=%dx%d scale=%.0f lang=%s", window.getGuiScaledWidth(),
                window.getGuiScaledHeight(), window.getGuiScale(),
                context.minecraft().getLanguageManager().getSelected());
        Screen open = context.minecraft().screen;
        if (!(open instanceof WarehouseTerminalScreen terminal))
            return "screen=" + (open == null ? "none" : open.getClass().getSimpleName()) + " " + size;
        return String.format(Locale.ROOT, "screen=terminal %s left=%d top=%d width=%d %s status='%s' statusFits=%s %s",
                terminal.isCraftPanelOpen() ? "panel=open" : "panel=closed", terminal.getGuiLeft(),
                terminal.getGuiTop(), terminal.getXSize(), describePanel(terminal),
                terminal.shownStatusLine().getString(), terminal.statusTextsFit(), size);
    }

    // --- one round ---------------------------------------------------------------------------------------------------

    /**
     * Everything a player does at the panel once, with a shot of every moment worth seeing. {@code suffix} goes onto
     * every shot label ("" in English, "-de" in German).
     */
    private void round(VisualScript script, String suffix) {
        // Closed: the window as it was before M32, plus the toggle in the free column right of the inventory.
        script.client("craft: check the screen with the panel folded in", this::checkFoldedIn);
        shot(script, "craft-closed" + suffix);

        script.client("craft: click the crafting toggle", this::clickToggle)
                .until("craft: the panel is folded out", context -> screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .client("craft: check the folded-out panel and its words", this::checkFoldedOut);
        shot(script, "craft-open" + suffix);

        // The chest: a ghost from the stock list, stamped into the ring cell by cell.
        pickGhost(script, PLANKS);
        stamp(script, 0, 1, 2);
        script.client("craft: hover the next cell with the ghost on the cursor",
                context -> ScreenInput.hover(context.minecraft(), ScreenInput.terminalCraftCell(screen(context), 3)))
                .client("craft: check that the panel draws no tooltip under the ghost",
                        CraftVisualScenario::checkGhostHidesPanelTooltips)
                .waitTicks(SETTLE_TICKS)
                .shot("craft-ghost" + suffix);
        stamp(script, 3, 5, 6, 7, 8);
        dropGhost(script);
        laidSteps(script, "chest", CHEST_GRID, Items.CHEST);
        script.server("craft: CHECK laying the recipe requested nothing", CraftVisualScenario::checkNothingRequested)
                .client("craft: check the cells before anything was delivered",
                        context -> checkCells(context, CHEST_GRID, TerminalCraftPanel.CellState.AVAILABLE, 0));
        shot(script, "craft-laid" + suffix);

        script.client("craft: hover the first cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.terminalCraftCell(screen(context), 0)))
                .client("craft: check the cell's tooltip", CraftVisualScenario::checkCellTooltip)
                .waitTicks(SETTLE_TICKS)
                .shot("craft-cell-tooltip" + suffix);

        // The ingredients come the honest way: a request, which the crane carries into the buffer.
        request(script, PLANKS, PLANKS_REQUESTED);
        delivered(script, "the planks for " + CHESTS + " chests", Map.of(PLANKS, (long) PLANKS_REQUESTED));
        ArmVisualScenario.untilOrFail(script, "craft: the panel counts " + CHESTS + " crafts ready",
                context -> screen(context).craftPanel().readyCrafts() == CHESTS, SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: check the cells and the result with the planks here",
                        context -> checkCells(context, CHEST_GRID, TerminalCraftPanel.CellState.HERE, CHESTS))
                .client("craft: check the result's tooltip", CraftVisualScenario::checkResultTooltip);
        shot(script, "craft-ready" + suffix);

        craft(script, new CraftCheck("craft one chest with a click on the result", TerminalCraftResult.CRAFTED, 1,
                CHEST, Map.of(PLANKS, (long) -PLANKS_PER_CHEST, CHEST, 1L)), this::clickResult);
        ArmVisualScenario.untilOrFail(script, "craft: the panel counts " + (CHESTS - 1) + " crafts left",
                context -> screen(context).craftPanel().readyCrafts() == CHESTS - 1, SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        shot(script, "craft-crafted-one" + suffix);

        int rest = CHESTS - 1;
        craft(script, new CraftCheck("craft the rest with a Shift-click on the result", TerminalCraftResult.CRAFTED,
                rest, CHEST, Map.of(PLANKS, (long) -PLANKS_PER_CHEST * rest, CHEST, (long) rest)),
                this::shiftClickResult);
        ArmVisualScenario.untilOrFail(script, "craft: the buffer holds no craft any more",
                context -> screen(context).craftPanel().readyCrafts() == 0, SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: check the cells after the buffer was used up",
                context -> checkCells(context, CHEST_GRID, TerminalCraftPanel.CellState.AVAILABLE, 0));
        shot(script, "craft-crafted-all" + suffix);

        cakeSteps(script, suffix);
        refusalSteps(script, suffix);
        sizeSteps(script, suffix);
    }

    /**
     * The cake: three milk buckets, two sugar, an egg and three wheat, crafted from the buffer, and the three empty
     * buckets it leaves there. The milk is picked as a ghost, the sugar and the egg by a left click on the stock list
     * <b>while</b> a ghost is on the cursor — which picks and never requests — and the wheat row with Shift +
     * right-click, which fills the next free cells in row-major order. With the milk ghost held, and again once it is
     * dropped, a stock cell's and an inventory slot's tooltip are checked ({@link #tooltipSteps}): the chests of the
     * first craft are in the inventory by now.
     */
    private void cakeSteps(VisualScript script, String suffix) {
        clearGrid(script);
        pickGhost(script, MILK);
        tooltipSteps(script, true);
        stamp(script, 0, 1, 2);
        switchGhost(script, SUGAR);
        stamp(script, 3, 5);
        switchGhost(script, EGG);
        stamp(script, 4);
        dropGhost(script);
        tooltipSteps(script, false);
        for (int row = 0; row < TerminalMenuLayout.CRAFT_GRID_WIDTH; row++)
            script.client("craft: Shift + right-click Wheat in the stock list (the screen's branch for it, called "
                    + "directly: the harness cannot hold Shift)", context -> fillNextFree(context, WHEAT));
        laidSteps(script, "cake", CAKE_GRID, Items.CAKE);
        script.server("craft: CHECK laying the cake requested nothing", CraftVisualScenario::checkNothingRequested);

        request(script, MILK, 3);
        request(script, SUGAR, 2);
        request(script, EGG, 1);
        request(script, WHEAT, 3);
        delivered(script, "the cake's ingredients", Map.of(MILK, 3L, SUGAR, 2L, EGG, 1L, WHEAT, 3L));
        ArmVisualScenario.untilOrFail(script, "craft: the panel counts one cake ready",
                context -> screen(context).craftPanel().readyCrafts() == 1, SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: check the cake's cells with everything here",
                context -> checkCells(context, CAKE_GRID, TerminalCraftPanel.CellState.HERE, 1));

        craft(script, new CraftCheck("craft the cake", TerminalCraftResult.CRAFTED, 1, CAKE,
                Map.of(MILK, -3L, SUGAR, -2L, EGG, -1L, WHEAT, -3L, CAKE, 1L, BUCKET, 3L)), this::clickResult);
        script.serverUntil("craft: CHECK the three empty buckets stay in the buffer",
                (server, context) -> bufferIs(server, context, Map.of(BUCKET, 3L)), SCREEN_TIMEOUT_TICKS);
        ArmVisualScenario.untilOrFail(script, "craft: the screen shows the buckets in the buffer",
                context -> clientBufferCount(context, BUCKET) == 3, SCREEN_TIMEOUT_TICKS,
                context -> "the buffer slots show " + clientBufferCount(context, BUCKET) + " buckets");
        shot(script, "craft-remainder" + suffix);
    }

    /**
     * A crafting table nobody has room for: the buffer is full of planks — the four it takes leave every stack
     * standing — and the inventory has no free slot and no crafting table to stack onto. The click must be refused and
     * move nothing.
     * <p>
     * The table is laid the long way, so that the panel is caught with a view of an earlier grid of the same item: two
     * planks one above the other (sticks), one of them moved beside the other (a pressure plate), two more planks (the
     * table). The last two edits are the ones the panel once could not tell from the grid before, because the items
     * stayed the same; each is checked in the client step it is made in ({@link #moveAndClickStale},
     * {@link #stampStale}).
     */
    private void refusalSteps(VisualScript script, String suffix) {
        clearGrid(script);
        pickGhost(script, PLANKS);
        stamp(script, 0, 3);
        laidSteps(script, "sticks", STICK_GRID, Items.STICK);
        script.client("craft: move a plank beside the other, drop the ghost and click the result before the server "
                + "has seen the new grid", this::moveAndClickStale);
        laidSteps(script, "pressure plate", PLATE_GRID, Items.OAK_PRESSURE_PLATE);
        script.client("craft: CHECK the click on the stale result crafted nothing", this::checkNoAnswer);
        pickGhost(script, PLANKS);
        script.client("craft: stamp two more planks, checking the panel after each",
                context -> stampStale(context, 3, 4));
        dropGhost(script);
        laidSteps(script, "crafting table", TABLE_GRID, Items.CRAFTING_TABLE);
        script.server("craft: fill the buffer with planks and the player's free slots with cobblestone (seeded on "
                + "the server thread: a crane never delivers into a player's inventory)", this::fillForRefusal);
        ArmVisualScenario.untilOrFail(script, "craft: the screen shows the full buffer",
                context -> clientBufferFull(context) && screen(context).craftPanel().readyCrafts() > 0,
                SCREEN_TIMEOUT_TICKS, context -> describePanel(screen(context)));
        craft(script, new CraftCheck("craft a crafting table without room for it", TerminalCraftResult.NO_ROOM, 0,
                CRAFTING_TABLE, Map.of()), this::clickResult);
        shot(script, "craft-refused" + suffix);
    }

    // --- the gestures (client thread, through ScreenInput) -----------------------------------------------------------

    /** A real click on the toggle; remembers where the window stood, so the fold can be checked for a jump. */
    private void clickToggle(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        leftBeforeFold = screen.getGuiLeft();
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftToggle(screen));
    }

    /** A right-click on {@code key} in the stock list, which puts it on the cursor as a ghost. */
    private static void pickGhost(VisualScript script, ItemKey key) {
        script.client("craft: right-click " + id(key) + " in the stock list to pick it as a ghost", context -> {
            WarehouseTerminalScreen screen = screen(context);
            ScreenInput.rightClick(context.minecraft(), ScreenInput.terminalCell(screen, stockCell(context, key)));
        });
        ArmVisualScenario.untilOrFail(script, "craft: the ghost on the cursor is " + id(key),
                context -> screen(context).craftPanel().ghost().equals(Optional.of(key)), SETTLE_TICKS,
                context -> "the ghost is " + screen(context).craftPanel().ghost());
    }

    /** A left click on {@code key} in the stock list while a ghost is on the cursor: it picks, it never requests. */
    private static void switchGhost(VisualScript script, ItemKey key) {
        script.client("craft: left-click " + id(key) + " in the stock list with a ghost on the cursor", context -> {
            WarehouseTerminalScreen screen = screen(context);
            if (!screen.craftPanel().hasGhost())
                throw new VisualTestException("no ghost on the cursor, so this click would request " + id(key));
            ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(screen, stockCell(context, key)));
        });
        ArmVisualScenario.untilOrFail(script, "craft: the ghost on the cursor is " + id(key),
                context -> screen(context).craftPanel().ghost().equals(Optional.of(key)), SETTLE_TICKS,
                context -> "the ghost is " + screen(context).craftPanel().ghost());
    }

    /** One left click per cell, one per tick, each stamping the ghost and keeping it on the cursor. */
    private static void stamp(VisualScript script, int... cells) {
        for (int cell : cells)
            script.client("craft: stamp the ghost into cell " + cell, context -> {
                WarehouseTerminalScreen screen = screen(context);
                ItemKey ghost = screen.craftPanel().ghost().orElseThrow(
                        () -> new VisualTestException("no ghost on the cursor to stamp into cell " + cell));
                ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftCell(screen, cell));
                if (!screen.craftPanel().grid().cell(cell).equals(Optional.of(ghost)))
                    throw new VisualTestException("cell " + cell + " holds " + screen.craftPanel().grid().cell(cell)
                            + " after the click, not the ghost " + id(ghost));
                if (!screen.craftPanel().ghost().equals(Optional.of(ghost)))
                    throw new VisualTestException("stamping cell " + cell + " took the ghost off the cursor");
            });
    }

    /** Escape drops the ghost; it must not close the screen, which a second Escape would. */
    private static void dropGhost(VisualScript script) {
        script.client("craft: press Escape to drop the ghost", context -> {
            if (!screen(context).craftPanel().hasGhost())
                throw new VisualTestException("no ghost on the cursor: Escape would close the screen");
            ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE);
        });
        ArmVisualScenario.untilOrFail(script, "craft: the ghost is gone and the screen is still open",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen open
                        && !open.craftPanel().hasGhost(),
                SETTLE_TICKS, context -> "the screen is " + context.minecraft().screen);
    }

    /**
     * Shift + right-click on {@code key} in the stock list. The harness cannot hold Shift, so the point is checked the
     * way every other click's is and the screen's conditions for the gesture with it (panel out, empty cursor, no
     * ghost), and then the branch the screen takes for it is called: {@code TerminalCraftPanel#fillNextFree}.
     */
    private static void fillNextFree(VisualContext context, ItemKey key) {
        WarehouseTerminalScreen screen = screen(context);
        int cell = stockCell(context, key);
        ScreenInput.terminalCell(screen, cell);
        if (!screen.isCraftPanelOpen() || !screen.getMenu().getCarried().isEmpty() || screen.craftPanel().hasGhost())
            throw new VisualTestException("the screen would not take a Shift + right-click on the stock list now");
        int free = screen.craftPanel().grid().nextFreeCell().orElse(-1);
        ItemKey shown = screen.visibleEntries().get(cell).key();
        if (!screen.craftPanel().fillNextFree(shown) || !screen.craftPanel().grid().cell(free).equals(Optional.of(key)))
            throw new VisualTestException("Shift + right-click did not write " + id(key) + " into cell " + free);
    }

    /** A real click on the Clear button, which empties the whole grid. */
    private static void clearGrid(VisualScript script) {
        script.client("craft: click the Clear button", context -> ScreenInput.click(context.minecraft(),
                ScreenInput.terminalCraftClear(screen(context))));
        ArmVisualScenario.untilOrFail(script, "craft: the grid is empty",
                context -> screen(context).craftPanel().grid().isEmpty(), SETTLE_TICKS,
                context -> "the grid is " + screen(context).craftPanel().grid());
    }

    /** A real click on the result cell: one craft. */
    private void clickResult(VisualContext context) {
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftResult(screen(context)));
    }

    /**
     * A Shift-click on the result cell. The point is checked with the panel's hit test like a plain click's, and the
     * branch the screen takes for a Shift-click there is called: {@code TerminalCraftPanel#craft(true)}, which must
     * send the craft, because the result on screen is the server's for this very grid.
     */
    private void shiftClickResult(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        ScreenInput.terminalCraftResult(screen);
        if (!screen.craftPanel().craft(true))
            throw new VisualTestException("the panel refused the Shift-click as if its result were of another grid: "
                    + describePanel(screen));
    }

    /**
     * One client step, so no tick and no view can come in between: from the sticks, the lower plank is cleared and
     * one stamped beside the upper one — a pressure plate, the same two planks moved — then the ghost is dropped and
     * the result clicked. After each edit the panel must show no result for the grid on screen; the click must send
     * the waiting preview at once instead of a craft, which {@link #checkNoAnswer} confirms once the view is in.
     */
    private void moveAndClickStale(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        TerminalCraftPanel panel = screen.craftPanel();
        if (!panel.hasGhost() || !panel.describesGrid() || !panel.grid().equals(STICK_GRID))
            throw new VisualTestException("the sticks are not laid and answered with the ghost held: "
                    + describePanel(screen));
        ScreenInput.rightClick(context.minecraft(), ScreenInput.terminalCraftCell(screen, 3));
        requireStale(screen, "the lower plank cleared");
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftCell(screen, 1));
        if (!panel.grid().equals(PLATE_GRID))
            throw new VisualTestException("the grid is " + panel.grid() + ", not the pressure plate's " + PLATE_GRID);
        requireStale(screen, "the planks moved side by side");
        ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE);
        if (panel.hasGhost())
            throw new VisualTestException("Escape did not drop the ghost");
        answerBefore = screen.craftAnswer();
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftResult(screen));
        if (panel.previewPending())
            throw new VisualTestException("the click on a stale result did not ask for the new view at once");
        requireStale(screen, "the click on the result");
        LOGGER.info(PREFIX + "craft: CHECK PASS the moved planks showed no result before their view, and the click on "
                + "the result asked for the view instead of crafting");
    }

    /** No answer came back to the click on the stale result: it sent a preview and no craft. */
    private void checkNoAnswer(VisualContext context) {
        TerminalCraftAnswerPayload answer = screen(context).craftAnswer();
        if (answer != answerBefore)
            throw new VisualTestException("the click on a result of an earlier grid was answered: " + answer);
        LOGGER.info(PREFIX + "craft: CHECK PASS the click on the stale result was not answered");
    }

    /**
     * Stamps the ghost into {@code cells} in one client step, checking after each that the panel shows no result: the
     * view on hand is of the grid before, with the same item, fewer of it.
     */
    private static void stampStale(VisualContext context, int... cells) {
        WarehouseTerminalScreen screen = screen(context);
        if (!screen.craftPanel().describesGrid())
            throw new VisualTestException("the panel has no view of the grid to start from: " + describePanel(screen));
        for (int cell : cells) {
            ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftCell(screen, cell));
            requireStale(screen, "a plank stamped into cell " + cell);
        }
        LOGGER.info(PREFIX + "craft: CHECK PASS more of the same planks showed no result before their view");
    }

    /** Fails unless the panel treats its view as one of an earlier grid: no result, no result tooltip. */
    private static void requireStale(WarehouseTerminalScreen screen, String what) {
        TerminalCraftPanel panel = screen.craftPanel();
        if (panel.describesGrid() || !panel.shownResult().isEmpty() || !panel.resultTooltip().isEmpty())
            throw new VisualTestException("after " + what + " the panel shows the view of the grid before as this "
                    + "grid's: " + describePanel(screen));
    }

    /**
     * Amount field to {@code amount}, one notch of the wheel per tick, then a plain left click on {@code key} in the
     * stock list: a request, because no ghost is on the cursor. The server has to accept it.
     */
    private static void request(VisualScript script, ItemKey key, int amount) {
        ArmVisualScenario.untilOrFail(script, "craft: scroll the amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(screen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, AMOUNT_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
        script.client("craft: left-click " + id(key) + " in the stock list to request " + amount, context -> {
            WarehouseTerminalScreen screen = screen(context);
            if (screen.craftPanel().hasGhost())
                throw new VisualTestException("a ghost is on the cursor, so this click would pick instead of request");
            ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(screen, stockCell(context, key)));
        }).serverUntil("craft: the server accepted the request for " + amount + " " + id(key),
                (server, context) -> requestAccepted(server, context, key, amount), REQUEST_TIMEOUT_TICKS);
    }

    /** Waits until the crane has delivered {@code counts} into the buffer, and nothing else is there. */
    private static void delivered(VisualScript script, String what, Map<ItemKey, Long> counts) {
        script.serverUntil("craft: wait until the crane delivered " + what,
                (server, context) -> bufferIs(server, context, counts), DELIVERY_TIMEOUT_TICKS);
    }

    /** Waits for the server's preview of {@code grid} and checks it names {@code result}. */
    private static void laidSteps(VisualScript script, String what, CraftGrid<ItemKey> grid, Item result) {
        script.client("craft: check the grid of the " + what, context -> {
            CraftGrid<ItemKey> laid = screen(context).craftPanel().grid();
            if (!laid.equals(grid))
                throw new VisualTestException("the grid is " + laid + ", not the " + what + "'s " + grid);
        });
        ArmVisualScenario.untilOrFail(script, "craft: the server previewed the " + what,
                context -> previewShows(context, grid, result), PREVIEW_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: check the recipe the server named for the " + what, context -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(result);
            Optional<ResourceLocation> recipe = screen(context).craftPanel().view().recipe();
            if (!recipe.equals(Optional.of(id)))
                throw new VisualTestException("the server named the recipe " + recipe + " for the " + what);
        });
    }

    /** One craft click with the census around it: unchanged before, moved by exactly the recipe after. */
    private void craft(VisualScript script, CraftCheck check, VisualScript.ClientAction click) {
        script.server("craft: census before: " + check.what(),
                        (server, context) -> assertCensus(server, context, "before: " + check.what()))
                .client("craft: " + check.what(), context -> {
                    answerBefore = screen(context).craftAnswer();
                    click.run(context);
                });
        ArmVisualScenario.untilOrFail(script, "craft: the server answered: " + check.what(),
                context -> screen(context).craftAnswer() != answerBefore, ANSWER_TIMEOUT_TICKS,
                context -> "no answer arrived");
        script.client("craft: CHECK the answer to: " + check.what(), context -> checkAnswer(context, check))
                .server("craft: CHECK the census moved by exactly the recipe: " + check.what(), (server, context) -> {
                    Map<ItemKey, Long> next = expected;
                    for (Map.Entry<ItemKey, Long> change : check.delta().entrySet())
                        next = SceneItemCensus.plus(next, change.getKey(), change.getValue());
                    expected = next;
                    assertCensus(server, context, check.what());
                });
    }

    // --- checks (client thread) --------------------------------------------------------------------------------------

    /** The screen as it was before M32, plus the toggle: 230 px wide, no panel area, no new stock tooltip line. */
    private void checkFoldedIn(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        if (screen.isCraftPanelOpen() || screen.getXSize() != TerminalMenuLayout.WIDTH)
            throw new VisualTestException("the panel is not folded in: " + screen.getXSize() + " px wide");
        if (!screen.getExtraAreas().isEmpty())
            throw new VisualTestException("a folded-in panel still names an area: " + screen.getExtraAreas());
        TerminalMenuLayout layout = screen.getMenu().layout();
        ScreenInput.Point toggle = ScreenInput.terminalCraftToggle(screen);
        if (toggle.x() != screen.getGuiLeft() + layout.craftToggleX() + TerminalMenuLayout.SLOT / 2.0
                || toggle.y() != screen.getGuiTop() + layout.craftToggleY() + TerminalMenuLayout.SLOT / 2.0)
            throw new VisualTestException("the toggle is drawn at " + toggle + ", not where the layout puts it");
        if (stockHintShown(screen))
            throw new VisualTestException("a stock cell offers the crafting grid while the panel is folded in");
        LOGGER.info(PREFIX + "craft: folded in, the window is {} px wide at {}, the toggle at {}", screen.getXSize(),
                screen.getGuiLeft(), toggle);
    }

    /**
     * The folded-out panel: 298 px, no jump at this screen width, the panel's rectangle named for JEI, a stock cell's
     * extra tooltip line — and every word the panel shows before anything is laid, in the language that is loaded.
     */
    private void checkFoldedOut(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        if (screen.getXSize() != TerminalMenuLayout.FOLDED_WIDTH)
            throw new VisualTestException("the folded-out window is " + screen.getXSize() + " px wide");
        if (screen.getGuiLeft() != leftBeforeFold)
            throw new VisualTestException("the window jumped from " + leftBeforeFold + " to " + screen.getGuiLeft()
                    + " on a " + screen.width + " px wide screen");
        TerminalMenuLayout layout = screen.getMenu().layout();
        Rect2i area = new Rect2i(screen.getGuiLeft() + TerminalMenuLayout.PANEL_X, screen.getGuiTop(),
                TerminalMenuLayout.PANEL_WIDTH, layout.panelHeight());
        List<Rect2i> areas = screen.getExtraAreas();
        if (areas.size() != 1 || !sameRect(areas.getFirst(), area))
            throw new VisualTestException("the panel names " + areas.size() + " areas, not its own rectangle");
        if (!screen.craftPanel().grid().isEmpty())
            throw new VisualTestException("the panel opened with a grid: " + screen.craftPanel().grid());
        if (!stockHintShown(screen))
            throw new VisualTestException("the stock cells do not say a right-click lays the item into the grid");
        Font font = context.minecraft().font;
        Component title = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_TITLE);
        if (font.width(title) > TerminalMenuLayout.MAX_PANEL_TITLE_WIDTH)
            throw new VisualTestException("the panel title '" + title.getString() + "' is " + font.width(title)
                    + " px, wider than the panel's " + TerminalMenuLayout.MAX_PANEL_TITLE_WIDTH);
        checkLines(context, "the toggle's tooltip", ScreenInput.terminalCraftToggleTooltip(screen), 2);
        checkLines(context, "an empty cell's tooltip", screen.craftPanel().cellTooltip(0), 2);
        LOGGER.info(PREFIX + "craft: folded out without a jump at {}, title '{}' {} px", screen.getGuiLeft(),
                title.getString(), font.width(title));
    }

    /**
     * A ghost on the cursor hides the panel's own tooltips, as a carried stack does. The stock list's and the slots'
     * are checked with the cake ({@link #tooltipSteps}), where the inventory holds an item to hover.
     */
    private static void checkGhostHidesPanelTooltips(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        Window window = context.minecraft().getWindow();
        double x = context.minecraft().mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        double y = context.minecraft().mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        if (!screen.craftPanel().tooltipAt(x, y).isEmpty() || !screen.tooltipAt((int) x, (int) y).isEmpty())
            throw new VisualTestException("the panel draws a tooltip under the ghost");
    }

    /**
     * Hovers Oak Planks in the stock list and then a chest in the player's inventory, and reads what the screen would
     * draw there ({@code WarehouseTerminalScreen#tooltipAt}, {@code #slotTooltipShown}): nothing at all while
     * {@code ghost} is held, and both tooltips without it — the control that proves the mouse really rests on the cell
     * and on the slot. A hover takes effect with the next frame, hence the wait before each check.
     */
    private static void tooltipSteps(VisualScript script, boolean ghost) {
        String held = ghost ? "with the ghost on the cursor" : "with an empty cursor";
        script.client("craft: hover Oak Planks in the stock list " + held, context -> ScreenInput.hover(
                        context.minecraft(), ScreenInput.terminalCell(screen(context), stockCell(context, PLANKS))))
                .waitTicks(SETTLE_TICKS)
                .client("craft: CHECK the stock cell's tooltip " + held, context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    if (screen.craftPanel().hasGhost() != ghost)
                        throw new VisualTestException("the ghost is " + screen.craftPanel().ghost());
                    ScreenInput.Point point = ScreenInput.terminalCell(screen, stockCell(context, PLANKS));
                    List<Component> tooltip = screen.tooltipAt((int) point.x(), (int) point.y());
                    if (tooltip.isEmpty() != ghost)
                        throw new VisualTestException("the stock cell's tooltip " + held + " is " + texts(tooltip));
                    LOGGER.info(PREFIX + "craft: CHECK PASS the stock cell shows {} tooltip lines {}", tooltip.size(),
                            held);
                })
                .client("craft: hover a chest in the player's inventory " + held, context -> ScreenInput.hover(
                        context.minecraft(), ScreenInput.menuSlot(screen(context), chestSlot(context))))
                .waitTicks(SETTLE_TICKS)
                .client("craft: CHECK the inventory slot's tooltip " + held, context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    Slot slot = screen.getMenu().slots.get(chestSlot(context));
                    if (screen.getSlotUnderMouse() != slot)
                        throw new VisualTestException("the mouse does not rest on the chest's slot but on "
                                + screen.getSlotUnderMouse());
                    if (screen.slotTooltipShown() == ghost)
                        throw new VisualTestException("the slot's tooltip is " + (ghost ? "drawn" : "not drawn") + " "
                                + held);
                    LOGGER.info(PREFIX + "craft: CHECK PASS the inventory slot's tooltip is {} {}",
                            ghost ? "hidden" : "drawn", held);
                });
    }

    /** The menu slot of the first chest in the player's inventory; the chests crafted in this round are there. */
    private static int chestSlot(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        List<Slot> slots = screen.getMenu().slots;
        for (int slot = screen.getMenu().firstPlayerSlot(); slot < slots.size(); slot++) {
            if (CHEST.matches(slots.get(slot).getItem()))
                return slot;
        }
        throw new VisualTestException("the player's inventory holds no chest to hover");
    }

    /** Every laid cell in {@code state}, the middle of a ring empty, and {@code ready} crafts counted here. */
    private static void checkCells(VisualContext context, CraftGrid<ItemKey> grid, TerminalCraftPanel.CellState state,
            int ready) {
        TerminalCraftPanel panel = screen(context).craftPanel();
        List<TerminalCraftPanel.CellState> states = panel.cellStates();
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            TerminalCraftPanel.CellState wanted = grid.cell(cell).isPresent() ? state
                    : TerminalCraftPanel.CellState.EMPTY;
            if (states.get(cell) != wanted)
                throw new VisualTestException("cell " + cell + " is " + states.get(cell) + ", not " + wanted + ": "
                        + describePanel(screen(context)));
        }
        if (panel.readyCrafts() != ready)
            throw new VisualTestException("the panel counts " + panel.readyCrafts() + " crafts ready, not " + ready);
        LOGGER.info(PREFIX + "craft: the cells read {} with x{} ready", states, ready);
    }

    /** The first cell's tooltip: the item, what one craft takes and what is here, the stock line, how to clear. */
    private static void checkCellTooltip(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        ScreenInput.Point point = ScreenInput.terminalCraftCell(screen, 0);
        List<Component> tooltip = screen.craftPanel().tooltipAt(point.x(), point.y());
        String perCraft = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_PER_CRAFT,
                String.valueOf(PLANKS_PER_CHEST), "0").getString();
        if (tooltip.stream().map(Component::getString).noneMatch(perCraft::equals))
            throw new VisualTestException("the cell's tooltip does not say '" + perCraft + "': " + texts(tooltip));
        checkLines(context, "the cell's tooltip", tooltip, 4);
    }

    /** The result's tooltip while crafts are ready: the item, the count, and the two clicks. */
    private static void checkResultTooltip(VisualContext context) {
        checkLines(context, "the result's tooltip", screen(context).craftPanel().resultTooltip(), 4);
    }

    /** At least {@code minimum} lines, none of them a raw lang key, each inside the terminal's row. */
    private static void checkLines(VisualContext context, String what, List<Component> lines, int minimum) {
        String language = context.minecraft().getLanguageManager().getSelected();
        Font font = context.minecraft().font;
        if (lines.size() < minimum)
            throw new VisualTestException(what + " has " + lines.size() + " lines in " + language + ": "
                    + texts(lines));
        List<String> measured = new ArrayList<>();
        for (Component line : lines) {
            String text = line.getString();
            if (text.contains(WareworksLang.key("gui.")))
                throw new VisualTestException(what + " shows a raw lang key in " + language + ": " + text);
            if (font.width(line) > ROW_WIDTH)
                throw new VisualTestException(what + " has a line of " + font.width(line) + " px in " + language
                        + ", wider than the terminal's " + ROW_WIDTH + " px row: '" + text + "'");
            measured.add("'" + text + "' " + font.width(line) + " px");
        }
        LOGGER.info(PREFIX + "craft: {} in {}: {}", what, language, measured);
    }

    /** The answer the click ended in, and the sentence the status row shows for it. */
    private static void checkAnswer(VisualContext context, CraftCheck check) {
        WarehouseTerminalScreen screen = screen(context);
        TerminalCraftAnswerPayload answer = screen.craftAnswer();
        if (answer == null || answer.result() != check.result() || answer.crafted() != check.crafted()
                || !answer.about().equals(Optional.of(check.about())))
            throw new VisualTestException(check.what() + ": the server answered " + answer + ", expected "
                    + check.result() + " x" + check.crafted() + " about " + id(check.about()));
        Component name = check.about().toStack().getHoverName();
        Component wanted = check.result().isSuccess()
                ? WarehouseTerminalScreen.craftAnswerText(check.result(), check.crafted(), name, Component.empty())
                : WarehouseTerminalScreen.craftAnswerText(check.result(), 0, Component.empty(), name);
        String shown = screen.feedbackLine().map(Component::getString).orElse("");
        if (!shown.equals(wanted.getString()))
            throw new VisualTestException(check.what() + ": the status row says '" + shown + "', not '"
                    + wanted.getString() + "'");
        if (!screen.statusTextsFit())
            throw new VisualTestException(check.what() + ": the status row does not fit: '" + shown + "'");
        LOGGER.info(PREFIX + "craft: CHECK PASS {}: {} x{}, the status row says '{}'", check.what(), answer.result(),
                answer.crafted(), shown);
    }

    private static boolean stockHintShown(WarehouseTerminalScreen screen) {
        StockLine<ItemKey> planks = screen.entry(PLANKS)
                .orElseThrow(() -> new VisualTestException("the terminal does not list Oak Planks"));
        String hint = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_STOCK_HINT).getString();
        return screen.itemTooltip(planks).stream().map(Component::getString).anyMatch(hint::equals);
    }

    /** Whether the panel shows the server's result for exactly {@code grid}. */
    private static boolean previewShows(VisualContext context, CraftGrid<ItemKey> grid, Item result) {
        if (!(context.minecraft().screen instanceof WarehouseTerminalScreen screen))
            return false;
        TerminalCraftPanel panel = screen.craftPanel();
        return screen.isCraftPanelOpen() && panel.grid().equals(grid) && !panel.previewPending()
                && panel.describesGrid() && panel.shownResult().is(result);
    }

    // --- the sizes (design table 3.2) --------------------------------------------------------------------------------

    /**
     * The floor and 480 x 270, each reached the way a player gets there — a window size and a GUI scale — with the
     * panel folded in and out again by real clicks on the toggle, so where the window stands is measured both ways.
     */
    private void sizeSteps(VisualScript script, String suffix) {
        script.client("craft: remember the window and the GUI scale", this::rememberWindow)
                .client("craft: park the mouse in the title row", CraftVisualScenario::parkMouse);
        resize(script, FLOOR_WIDTH, FLOOR_HEIGHT, SIZE_GUI_SCALE);
        foldBothWays(script, FLOOR_GUI_WIDTH, FLOOR_GUI_HEIGHT, FLOOR_LEFT_FOLDED_IN, FLOOR_LEFT_FOLDED_OUT);
        shot(script, "craft-floor" + suffix);
        resize(script, WIDE_WIDTH, WIDE_HEIGHT, SIZE_GUI_SCALE);
        foldBothWays(script, WIDE_GUI_WIDTH, WIDE_GUI_HEIGHT, WIDE_LEFT, WIDE_LEFT);
        shot(script, "craft-scale4" + suffix);
        script.client("craft: put the window back", context -> {
            context.minecraft().getWindow().setWindowed(windowWidth, windowHeight);
        });
        ArmVisualScenario.untilOrFail(script, "craft: the window is back",
                context -> TooltipsVisualScenario.synced(context)
                        && context.minecraft().getWindow().getScreenWidth() == windowWidth
                        && context.minecraft().getWindow().getScreenHeight() == windowHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("craft: put the GUI scale back", context -> {
            context.minecraft().options.guiScale().set(guiScaleOption);
            context.minecraft().resizeDisplay();
        });
        ArmVisualScenario.untilOrFail(script, "craft: the GUI is " + "as large as before",
                context -> context.minecraft().getWindow().getGuiScaledWidth() == guiWidth
                        && context.minecraft().getWindow().getGuiScaledHeight() == guiHeight
                        && screen(context).width == guiWidth,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
    }

    private void rememberWindow(VisualContext context) {
        Window window = context.minecraft().getWindow();
        windowWidth = window.getScreenWidth();
        windowHeight = window.getScreenHeight();
        guiWidth = window.getGuiScaledWidth();
        guiHeight = window.getGuiScaledHeight();
        guiScaleOption = context.minecraft().options.guiScale().get();
        LOGGER.info(PREFIX + "craft: the window is {}x{} ({}x{} GUI px, scale option {})", windowWidth, windowHeight,
                guiWidth, guiHeight, guiScaleOption);
    }

    /**
     * {@code Window#setWindowed}, then the GUI scale, in that order: the option clamps a scale to what the window
     * allows <i>now</i> ({@code OptionInstance.ClampingLazyMaxIntRange}), so scale 4 set on the 1600 x 900 window
     * would quietly become 3. The framebuffer is waited for as in the tooltips scenario, which also covers a window
     * that missed GLFW's framebuffer callback.
     */
    private static void resize(VisualScript script, int width, int height, int scale) {
        script.client("craft: make the window " + width + "x" + height,
                context -> context.minecraft().getWindow().setWindowed(width, height));
        ArmVisualScenario.untilOrFail(script, "craft: the framebuffer is " + width + "x" + height,
                context -> TooltipsVisualScenario.synced(context)
                        && TooltipsVisualScenario.framebufferIs(context.minecraft().getWindow(), width, height),
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("craft: GUI scale " + scale, context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.options.guiScale().set(scale);
            minecraft.resizeDisplay();
            if (minecraft.getWindow().getGuiScale() != scale)
                throw new VisualTestException("the GUI scale is " + minecraft.getWindow().getGuiScale() + ", not "
                        + scale + ", at " + describeWindow(minecraft.getWindow()));
        }).waitTicks(SETTLE_TICKS);
    }

    /**
     * Checks the GUI size and where the folded-out window stands, folds the panel in and out again with two real
     * clicks on the toggle, and checks where the window stands each time: {@code leftIn} folded in,
     * {@code leftOut} folded out, the panel's right edge inside the screen's margin and its top on screen.
     */
    private void foldBothWays(VisualScript script, int guiWidth, int guiHeight, int leftIn, int leftOut) {
        String size = guiWidth + "x" + guiHeight;
        script.client("craft: CHECK " + size + " with the panel folded out", context -> checkPlacement(context,
                guiWidth, guiHeight, true, leftOut));
        script.client("craft: fold the panel in at " + size, this::clickToggle)
                .until("craft: the panel is folded in", context -> !screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .client("craft: CHECK " + size + " with the panel folded in", context -> checkPlacement(context,
                        guiWidth, guiHeight, false, leftIn))
                .client("craft: fold the panel out again at " + size, this::clickToggle)
                .until("craft: the panel is folded out", context -> screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .client("craft: CHECK " + size + " with the panel folded out again", context -> checkPlacement(
                        context, guiWidth, guiHeight, true, leftOut))
                .client("craft: park the mouse in the title row", CraftVisualScenario::parkMouse);
    }

    private static void checkPlacement(VisualContext context, int guiWidth, int guiHeight, boolean out, int left) {
        Window window = context.minecraft().getWindow();
        WarehouseTerminalScreen screen = screen(context);
        if (window.getGuiScaledWidth() != guiWidth || window.getGuiScaledHeight() != guiHeight
                || screen.width != guiWidth || screen.height != guiHeight)
            throw new VisualTestException("the GUI is " + window.getGuiScaledWidth() + "x" + window.getGuiScaledHeight()
                    + " (screen " + screen.width + "x" + screen.height + "), not " + guiWidth + "x" + guiHeight);
        if (screen.isCraftPanelOpen() != out)
            throw new VisualTestException("the panel is not folded " + (out ? "out" : "in"));
        int right = screen.getGuiLeft() + screen.getXSize();
        if (screen.getGuiLeft() != left)
            throw new VisualTestException("folded " + (out ? "out" : "in") + " at " + guiWidth + " px the window "
                    + "stands at " + screen.getGuiLeft() + ", not " + left);
        if (screen.getGuiLeft() < TerminalMenuLayout.FOLD_MARGIN || right > guiWidth - TerminalMenuLayout.FOLD_MARGIN
                || (guiWidth == FLOOR_GUI_WIDTH && out && right > FLOOR_RIGHT_EDGE))
            throw new VisualTestException("the window [" + screen.getGuiLeft() + ", " + right + ") leaves less than "
                    + TerminalMenuLayout.FOLD_MARGIN + " px to the edges of a " + guiWidth + " px screen");
        if (screen.getGuiTop() < 0 || screen.getGuiTop() + screen.getYSize() > guiHeight)
            throw new VisualTestException("the window [" + screen.getGuiTop() + ", "
                    + (screen.getGuiTop() + screen.getYSize()) + ") does not fit a " + guiHeight + " px screen");
        LOGGER.info(PREFIX + "craft: CHECK PASS at {}x{} folded {}: the window spans x [{}, {}) and y [{}, {})",
                guiWidth, guiHeight, out ? "out" : "in", screen.getGuiLeft(), right, screen.getGuiTop(),
                screen.getGuiTop() + screen.getYSize());
    }

    private static String describeWindow(Window window) {
        return String.format(Locale.ROOT, "window %dx%d, framebuffer %dx%d, GUI %dx%d at scale %.0f",
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale());
    }

    // --- between the two rounds --------------------------------------------------------------------------------------

    /**
     * Puts the screen back where the first round found it, so the German one plays the same moments: the grid is
     * cleared and the panel folded in by real clicks, and the buffer and the inventory are emptied on the server
     * thread.
     */
    private void emptyForNextRound(VisualScript script) {
        clearGrid(script);
        script.client("craft: click the crafting toggle to fold the panel in", this::clickToggle)
                .until("craft: the panel is folded in", context -> !screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .server("craft: empty the buffer and the player's inventory for the next round (declared in the "
                        + "census)", this::emptyBufferAndInventory)
                .until("craft: the screen shows the empty buffer", context -> clientBufferEmpty(context),
                        SCREEN_TIMEOUT_TICKS);
    }

    // --- server thread -----------------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-CLEAR_MARGIN, -1, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN));
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
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()), WareworksBlocks.WAREHOUSE_CONTROLLER
                .getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));

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

    /** One item type per chest: several stacks of it, so every recipe has plenty in stock. */
    private static void fillChest(ServerLevel level, BlockPos chest, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("the chest at " + chest + " has no item handler");
        int stacks = stack.getCount() >= stack.getMaxStackSize() ? STACKS_PER_LOCATION : 1;
        for (int i = 0; i < stacks; i++) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
            if (!rest.isEmpty())
                break; // the chest is full; the remaining stacks are not needed for the scene
        }
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null || !SceneItemCensus.isFullyLoaded(level, censusBox))
            return false;
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == 1 && crane.isControllerLinked()
                && controller.stockIndex().distinctKeys() >= ITEM_TYPES;
    }

    private static void openScreen(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        if (!terminal.openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
    }

    /** Laying a recipe orders nothing: the terminal has no open request. */
    private static void checkNothingRequested(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        BlockPos terminal = layout(context.origin()).rackPos(TERMINAL);
        if (!controller.requestsFor(terminal).isEmpty())
            throw new VisualTestException("laying the recipe requested something: " + controller.requestsFor(terminal));
        LOGGER.info(PREFIX + "craft: CHECK PASS laying the recipe requested nothing");
    }

    /** Whether the terminal has an open request for {@code key}, or already holds what it asked for. */
    private static boolean requestAccepted(MinecraftServer server, VisualContext context, ItemKey key, int amount) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        BlockPos pos = terminal.getBlockPos();
        if (controller.requestsFor(pos).stream().anyMatch(request -> request.key().equals(key))
                || terminal.bufferedItems().count(key) >= amount)
            return true;
        if (terminal.lastRejection().isPresent())
            throw new VisualTestException("the request for " + amount + " " + id(key) + " was refused: "
                    + terminal.lastRejection().get());
        return false;
    }

    /** Whether the buffer holds exactly {@code counts} and nothing else. */
    private static boolean bufferIs(MinecraftServer server, VisualContext context, Map<ItemKey, Long> counts) {
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        InventorySnapshot<ItemKey> buffered = terminal.bufferedItems();
        if (!buffered.keys().equals(counts.keySet()))
            return false;
        for (Map.Entry<ItemKey, Long> entry : counts.entrySet())
            if (buffered.count(entry.getKey()) != entry.getValue())
                return false;
        return true;
    }

    /**
     * The room the refusal needs taken away: every free main slot of the player's inventory gets a stack of
     * cobblestone and every free buffer slot a stack of planks. The crafting table's four planks then leave every
     * stack standing, and nothing either side holds stacks with a crafting table. Declared in the census.
     */
    private void fillForRefusal(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        List<ItemStack> added = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (!inventory.items.get(slot).isEmpty())
                continue;
            ItemStack filler = INVENTORY_FILLER.toStack(FULL_STACK);
            inventory.setItem(slot, filler.copy());
            added.add(filler);
        }
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        ItemStack planks = PLANKS.toStack(FULL_STACK);
        while (terminal.insert(planks.copy(), true).isEmpty()) {
            terminal.insert(planks.copy(), false);
            added.add(planks.copy());
        }
        IItemHandler buffer = terminal.menuBuffer();
        for (int slot = 0; slot < buffer.getSlots(); slot++)
            if (buffer.getStackInSlot(slot).isEmpty())
                throw new VisualTestException("buffer slot " + slot + " is still empty after the filling");
        expected = SceneItemCensus.plusAll(expected, added);
        assertCensus(server, context, "the scenario filled the buffer and the inventory for the refusal");
    }

    /** Takes everything out of the buffer and the player's inventory again; declared in the census. */
    private void emptyBufferAndInventory(MinecraftServer server, VisualContext context) {
        Map<ItemKey, Long> next = expected;
        IItemHandler buffer = terminal(server.overworld(), context.origin()).menuBuffer();
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            for (ItemStack taken = buffer.extractItem(slot, FULL_STACK, false); !taken.isEmpty();
                    taken = buffer.extractItem(slot, FULL_STACK, false))
                next = SceneItemCensus.plus(next, ItemKey.of(taken), -taken.getCount());
        }
        Inventory inventory = context.serverPlayer(server).getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack taken = inventory.removeItemNoUpdate(slot);
            if (!taken.isEmpty())
                next = SceneItemCensus.plus(next, ItemKey.of(taken), -taken.getCount());
        }
        expected = next;
        assertCensus(server, context, "the scenario emptied the buffer and the inventory for the next round");
    }

    private void assertCensus(MinecraftServer server, VisualContext context, String step) {
        SceneItemCensus.assertEquals(server.overworld(), censusBox, expected, "craft: " + step,
                context.serverPlayer(server));
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

    private static WarehouseControllerBlockEntity controller(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), context.origin().relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    // --- screen (client thread) --------------------------------------------------------------------------------------

    private static boolean screenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && terminal.matchingEntries().size() >= ITEM_TYPES;
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + open + ")");
    }

    /** The visible stock cell showing {@code key}. */
    private static int stockCell(VisualContext context, ItemKey key) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++)
            if (visible.get(cell).key().equals(key))
                return cell;
        throw new VisualTestException("the stock list does not show " + id(key));
    }

    /** How many of {@code key} the buffer's menu slots show on the client. */
    private static int clientBufferCount(VisualContext context, ItemKey key) {
        WarehouseTerminalScreen screen = screen(context);
        int count = 0;
        for (int slot = 0; slot < screen.getMenu().bufferSlots(); slot++) {
            ItemStack stack = screen.getMenu().slots.get(slot).getItem();
            if (key.matches(stack))
                count += stack.getCount();
        }
        return count;
    }

    private static boolean clientBufferFull(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        for (int slot = 0; slot < screen.getMenu().bufferSlots(); slot++)
            if (screen.getMenu().slots.get(slot).getItem().isEmpty())
                return false;
        return true;
    }

    private static boolean clientBufferEmpty(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        for (int slot = 0; slot < screen.getMenu().bufferSlots(); slot++)
            if (!screen.getMenu().slots.get(slot).getItem().isEmpty())
                return false;
        return true;
    }

    /** Parks the mouse, waits for the screen to settle, and shoots. */
    private static void shot(VisualScript script, String label) {
        script.client("craft: park the mouse in the title row", CraftVisualScenario::parkMouse)
                .waitTicks(SETTLE_TICKS)
                .shot(label);
    }

    /** Puts the cursor into the window's title row: inside the screen, on no widget, slot, cell or panel part. */
    private static void parkMouse(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        ScreenInput.hover(context.minecraft(), new ScreenInput.Point(screen.getGuiLeft() + TITLE_CORNER,
                screen.getGuiTop() + TITLE_CORNER));
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    /** The panel in one line: grid, ghost, the cells' states, the result and the last answer. */
    private static String describePanel(WarehouseTerminalScreen screen) {
        TerminalCraftPanel panel = screen.craftPanel();
        StringBuilder cells = new StringBuilder();
        for (TerminalCraftPanel.CellState state : panel.cellStates())
            cells.append(switch (state) {
                case EMPTY -> '.';
                case HERE -> 'H';
                case AVAILABLE -> 'A';
                case SHORT -> 'S';
                case MISSING -> 'M';
                case UNKNOWN -> '?';
            });
        TerminalCraftAnswerPayload answer = screen.craftAnswer();
        return String.format(Locale.ROOT, "cells=%s ghost=%s result=%s ready=%d preview=%s answer=%s",
                cells, panel.ghost().map(CraftVisualScenario::id).orElse("none"),
                panel.shownResult().isEmpty() ? "none" : id(ItemKey.of(panel.shownResult())), panel.readyCrafts(),
                panel.view().recipe().map(ResourceLocation::toString).orElse("none"),
                answer == null ? "none" : answer.result() + "x" + answer.crafted());
    }

    private static String id(ItemKey key) {
        return BuiltInRegistries.ITEM.getKey(key.getItem()).getPath();
    }

    private static List<String> texts(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    private static boolean sameRect(Rect2i one, Rect2i other) {
        return one.getX() == other.getX() && one.getY() == other.getY() && one.getWidth() == other.getWidth()
                && one.getHeight() == other.getHeight();
    }
}
