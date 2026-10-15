package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket;
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
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.TerminalCraftOrderState;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftFetchQuestion;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.network.TerminalCraftAnswerPayload;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalCraftViewPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
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
 * It builds one short aisle with a powered crane, a warehouse terminal beside the dock, a rack of chests holding
 * what a chest and a cake are made of, and one machine of the player's behind the rack wall: a production station whose
 * pattern turns an iron ingot into nine nuggets, a Mechanical Arm and a Mechanical Crafter that really do it, and the
 * warehouse input the crafter fills. It opens the real screen through {@code WarehouseTerminalBlockEntity#openScreen}
 * and plays one <b>round</b> ({@link #round}): the toggle folds the panel out, a right-click on Oak Planks in the stock
 * list picks a ghost and left clicks stamp the chest's ring with it, Escape drops it, and the server's preview names
 * the Chest. The planks then come the honest way, through the panel's <b>Fetch</b> (slice 2 of the issue, ADR-062): the
 * amount field is scrolled to x3 and a click on Fetch starts the terminal's craft order, which the crane works off
 * into the buffer while the grid is locked; the scene is frozen while the crane carries the planks, so the progress
 * row is shot mid-delivery. A click on the result crafts one chest, a Shift-click the other two. A second Fetch is
 * given up with the progress row's cancel mark while the crane carries its planks, which go back into the racks. A
 * chain x4 takes eight iron nuggets, of which the racks hold only a chain's worth (slice 3 of the issue,
 * {@link #offerSteps}): the nugget cells are drawn makeable, the Fetch asks whether nine nuggets should be made first,
 * the Yes has the player's machine make them (a production chain, ADR-032), and the four chains are crafted from the
 * buffer. A cake x4 does not fit the buffer: the server asks, the question names the room ("only x2 of x4"), Yes
 * fetches two cakes' worth, and the cake crafted from them shows the buckets it leaves in the buffer. Last, a crafting
 * table is refused because neither the buffer nor the inventory has room for it.
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
 * {@code #resultAt}, {@code #fetchButtonAt}, {@code #cancelAt}, and the confirmation panel's buttons) — and checked to
 * be nothing else, no stock cell and no menu slot. Two gestures need Shift, which
 * the harness can never hold ({@link ScreenInput#requireNoModifiers}): the Shift-click on the result and the Shift +
 * right-click that writes an item into the next free cell. For those two the run checks the point the same way and
 * then calls the branch the screen takes for them ({@code TerminalCraftPanel#craft(true)},
 * {@code #fillNextFree}), and its step says so.
 * <p>
 * <b>Items.</b> A {@link SceneItemCensus} of the aisle <b>and of the player</b> brackets every craft: before the click
 * it must equal what the run expects, and after the answer it must have moved by exactly the recipe — eight planks out
 * and a chest in, or three milk buckets, two sugar, an egg and three wheat out and a cake and three buckets in — and by
 * nothing for the refused craft. It brackets every Fetch as well: the crane carries items only inside the census box,
 * so the census stays exactly what it was before the click, after the delivery, after the question and after the fetch
 * that was given up, while the buffer is checked to hold exactly what the fetch was for. Only three things change the
 * scene's items in a way no crane trip does, and all three are declared in the census: the player's crafter turning an
 * iron ingot into nine nuggets ({@link #declareMachineRun}, once its production order is complete), the full buffer
 * and the full inventory the refusal needs ({@link #fillForRefusal}, seeded on the server thread, because a crane
 * delivers into a buffer but never into a player's pockets), and the emptying between the two rounds
 * ({@link #emptyForNextRound}).
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
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey NUGGET = ItemKey.of(Items.IRON_NUGGET);
    /** The chain offer's result: vanilla's Chain item, not to be confused with the production chain that offers it. */
    private static final ItemKey IRON_CHAIN = ItemKey.of(Items.CHAIN);
    /** What the refusal fills the player's free inventory slots with: an item that is nowhere else in the scene. */
    private static final ItemKey INVENTORY_FILLER = ItemKey.of(Items.COBBLESTONE);
    private static final int FULL_STACK = 64;

    /** Nuggets in the racks before the first round: one chain's worth, so the question says what a No still leaves. */
    private static final int NUGGETS_IN_STOCK = 2;
    /**
     * Item types in the racks: everything the four recipes take, and a few more so the list looks like a store. The
     * iron nuggets are a single small stack: one chain's worth, short of what the chain offer asks for.
     */
    private static final List<ItemStack> STOCK = List.of(new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.MILK_BUCKET, 1), new ItemStack(Items.SUGAR, 64), new ItemStack(Items.EGG, 16),
            new ItemStack(Items.WHEAT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_LOG, 32),
            new ItemStack(Items.GLASS, 48), new ItemStack(Items.STRING, 16), new ItemStack(Items.REDSTONE, 64),
            new ItemStack(Items.COAL, 64), new ItemStack(Items.LEATHER, 8),
            new ItemStack(Items.IRON_NUGGET, NUGGETS_IN_STOCK));
    private static final int ITEM_TYPES = 13;
    /** Stacks of the same item put into one chest; a full stack of milk is one bucket, so 20 buckets. */
    private static final int STACKS_PER_LOCATION = 20;

    /** The chest's ring: every cell but the middle one. */
    private static final int[] CHEST_RING = { 0, 1, 2, 3, 5, 6, 7, 8 };
    private static final CraftGrid<ItemKey> CHEST_GRID = CraftGrid.of(PLANKS, PLANKS, PLANKS, PLANKS, null, PLANKS,
            PLANKS, PLANKS, PLANKS);
    private static final CraftGrid<ItemKey> CAKE_GRID = CraftGrid.of(MILK, MILK, MILK, SUGAR, EGG, SUGAR, WHEAT, WHEAT,
            WHEAT);
    private static final CraftGrid<ItemKey> TABLE_GRID = CraftGrid.of(PLANKS, PLANKS, null, PLANKS, PLANKS);
    /** Vanilla's Chain, laid down the middle column: an iron nugget above and below an iron ingot. */
    private static final CraftGrid<ItemKey> CHAIN_GRID = CraftGrid.of(null, NUGGET, null, null, IRON, null, null,
            NUGGET);
    /**
     * The nugget cell whose tooltip the makeable shot shows: the lower one, so the tooltip covers the rows below the
     * stock list rather than the list itself.
     */
    private static final int HOVERED_NUGGET_CELL = 7;
    /** Two planks one above the other, where the table is laid from: sticks. */
    private static final CraftGrid<ItemKey> STICK_GRID = CraftGrid.of(PLANKS, null, null, PLANKS);
    /** The same two planks side by side: an oak pressure plate. */
    private static final CraftGrid<ItemKey> PLATE_GRID = CraftGrid.of(PLANKS, PLANKS);
    /** Three chests are fetched for, so one click and one Shift-click both have something to craft. */
    private static final int CHESTS = 3;
    private static final int PLANKS_PER_CHEST = 8;
    private static final int PLANKS_FETCHED = CHESTS * PLANKS_PER_CHEST;
    /**
     * The terminal's buffer slots the cake's question is measured against: the shipped default of
     * {@code terminalBufferSlots}, which the run checks the menu really has.
     */
    private static final int BUFFER_SLOTS = 9;
    /** The cakes asked for: more than fit the buffer, because every milk bucket takes a slot of its own. */
    private static final int CAKES_ASKED = 4;
    /** Milk buckets, sugar, eggs and wheat one cake takes. */
    private static final int MILK_PER_CAKE = 3;
    private static final int SUGAR_PER_CAKE = 2;
    private static final int EGGS_PER_CAKE = 1;
    private static final int WHEAT_PER_CAKE = 3;
    /**
     * The cakes whose ingredients fit the empty buffer: a slot per milk bucket, and one slot each for the sugar, the
     * eggs and the wheat, which stack ({@code 3n + 3 <= 9}).
     */
    private static final int CAKES_FITTING = (BUFFER_SLOTS - 3) / MILK_PER_CAKE;

    // --- the chain offer (slice 3 of the issue): the player's machine and what it makes ------------------------------
    /** The production station of the player's machine: the crane delivers iron here, a Mechanical Arm takes it on. */
    private static final RackPosition STATION = RackPosition.of(4, 0, Side.RIGHT);
    /** The warehouse input the machine's Mechanical Crafter points into: the nuggets come back like any delivery. */
    private static final RackPosition MACHINE_INPUT = RackPosition.of(5, 0, Side.RIGHT);
    /** Chains the offer fetches for: eight nuggets, which the racks cannot give. */
    private static final int CHAINS = 4;
    private static final int NUGGETS_PER_CHAIN = 2;
    private static final int IRON_PER_CHAIN = 1;
    /** What the player's crafter really does (vanilla {@code minecraft:iron_nugget}): one ingot makes nine nuggets. */
    private static final int NUGGETS_PER_INGOT = 9;
    /** The station's pattern and the crafter's recipe take one ingot per run. */
    private static final int IRON_PER_RUN = 1;

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
    /**
     * The width a line of the confirmation panel is drawn at before it wraps: the row less the panel's padding of 6 px
     * on either side ({@code WarehouseTerminalScreen#confirmTextWidth}; {@code TerminalCraftTextWidthTest} measures the
     * same width).
     */
    private static final int DIALOG_WIDTH = ROW_WIDTH - 2 * 6;

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int PREVIEW_TIMEOUT_TICKS = 200;
    private static final int ANSWER_TIMEOUT_TICKS = 200;
    private static final int AMOUNT_TIMEOUT_TICKS = 200;
    private static final int DELIVERY_TIMEOUT_TICKS = 1200;
    /** A chain: a trip to the station, the arm and the crafter, a store trip and the delivery of what was made. */
    private static final int CHAIN_TIMEOUT_TICKS = 2400;
    private static final int MACHINE_READY_TIMEOUT_TICKS = 400;
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
    /** Client thread: the answer to a Fetch or an order button before the last such click, likewise. */
    @Nullable
    private TerminalCraftAnswerPayload fetchAnswerBefore;
    /** Server thread: the nuggets and the iron the racks held at the chain offer's Fetch. */
    private volatile long nuggetsBefore;
    private volatile long ironBefore;
    /** Server thread: the production order the Yes to the offer created, followed until it is complete. */
    @Nullable
    private volatile UUID chainRoot;
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
        script.server("craft: clear the area and place the creative motors", this::placeMotor)
                .server("craft: build the aisle, the terminal, the stocked racks and the player's machine",
                        CraftVisualScenario::buildAisle)
                .serverUntil("craft: wait until the controller has indexed every rack", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("craft: write the station's pattern, one iron ingot makes nine nuggets",
                        CraftVisualScenario::writePattern)
                .serverUntil("craft: wait until the aisle knows the nuggets can be made",
                        CraftVisualScenario::patternLive, MACHINE_READY_TIMEOUT_TICKS)
                .server("craft: give the Mechanical Arm its station and its crafter", CraftVisualScenario::teachTheArm)
                .serverUntil("craft: wait until the arm has resolved both points and turns",
                        CraftVisualScenario::armReady, MACHINE_READY_TIMEOUT_TICKS)
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
        return String.format(Locale.ROOT,
                "screen=terminal %s left=%d top=%d width=%d %s asking=%s status='%s' statusFits=%s %s",
                terminal.isCraftPanelOpen() ? "panel=open" : "panel=closed", terminal.getGuiLeft(),
                terminal.getGuiTop(), terminal.getXSize(), describePanel(terminal), terminal.isAskingSomething(),
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

        // The ingredients come the honest way: the panel's Fetch, which the crane works off into the buffer.
        fetchChestSteps(script, suffix);
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

        cancelSteps(script, suffix);
        offerSteps(script, suffix);
        cakeSteps(script, suffix);
        refusalSteps(script, suffix);
        sizeSteps(script, suffix);
    }

    /**
     * The chain offer (slice 3 of the issue, {@code docs/warehouse-system.md} §3.4.5, ADR-062): vanilla's Chain, an
     * iron nugget above and below an iron ingot, fetched for x{@value #CHAINS}. The racks hold only a chain's worth of
     * nuggets, but the aisle's production station has the pattern "1 iron ingot makes 9 nuggets", so the nugget cells
     * are drawn <b>makeable</b>: the stock list's "+" on its faint blue, and a tooltip that says what the racks hold
     * and that the Fetch asks first ({@code craft-makeable}). A click on Fetch requests and orders nothing: the server
     * measures the chain without changing anything and asks ({@code craft-offer}), naming the nine nuggets the player's
     * crafter will make first and the one chain the racks could serve without making anything.
     * <p>
     * The Yes starts the craft order. Its nugget line takes what the racks hold and has the rest made, as one
     * production order at the player's station that backs that line's request; the grid's Chain is never ordered. The
     * crane hands the station an ingot, the Mechanical Arm carries it into the crafter, the nuggets come back through
     * the warehouse input, are stored and fetched into the buffer with the iron, and the rest of the whole run stays in
     * the racks. A Shift-click crafts all four chains.
     * <p>
     * The census brackets every step: the question and every crane trip leave it as it was, the crafter's one run moves
     * it once (declared once the production order is complete: one ingot out, nine nuggets in), and the craft once.
     */
    private void offerSteps(VisualScript script, String suffix) {
        String what = CHAINS + " chains";
        clearGrid(script);
        pickGhost(script, NUGGET);
        stamp(script, 1, 7);
        switchGhost(script, IRON);
        stamp(script, 4);
        dropGhost(script);
        laidSteps(script, "chain", CHAIN_GRID, Items.CHAIN);
        script.server("craft: CHECK laying the chain requested and ordered nothing",
                CraftVisualScenario::checkNothingOrdered);
        amount(script, CHAINS);
        ArmVisualScenario.untilOrFail(script, "craft: the nugget cells are drawn makeable",
                context -> screen(context).craftPanel().cellStates().equals(chainCells()), PREVIEW_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: CHECK the makeable cell's tooltip and the Fetch tooltip for " + what,
                        CraftVisualScenario::checkMakeable)
                .client("craft: hover the lower nugget cell", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.terminalCraftCell(screen(context), HOVERED_NUGGET_CELL)))
                .waitTicks(SETTLE_TICKS)
                .shot("craft-makeable" + suffix);

        script.server("craft: remember what the racks hold before the Fetch for " + what, this::rememberStock);
        fetch(script, what);
        fetchAnswered(script, "the Fetch for " + what, TerminalCraftResult.ASKING, CHAINS);
        script.client("craft: CHECK the question offers to make the nuggets first",
                        CraftVisualScenario::checkOfferQuestion)
                .server("craft: CHECK the question requested and ordered nothing",
                        CraftVisualScenario::checkNothingOrdered)
                .server("craft: CHECK the census after the offer",
                        (server, context) -> assertCensus(server, context, "after the offer for " + what));
        shot(script, "craft-offer" + suffix);

        script.client("craft: click Yes in the offer", this::clickYes);
        fetchAnswered(script, "the Yes to the offer", TerminalCraftResult.STARTED, CHAINS);
        orderOpened(script, CHAIN_GRID, CHAINS);
        script.serverUntil("craft: CHECK the Yes has the nuggets made at the player's machine, never the chain",
                        this::chainStarted, ANSWER_TIMEOUT_TICKS)
                .serverUntil("craft: wait until the player's crafter has made the nuggets", this::chainComplete,
                        CHAIN_TIMEOUT_TICKS)
                .server("craft: the crafter turned one iron ingot into nine nuggets (declared in the census)",
                        this::declareMachineRun);
        fetchDone(script, "the iron for " + what, CHAIN_GRID, Map.of(NUGGET, (long) NUGGETS_PER_CHAIN * CHAINS, IRON,
                (long) IRON_PER_CHAIN * CHAINS));
        script.serverUntil("craft: CHECK the rest of the crafter's run stays in the racks", this::surplusStored,
                SCREEN_TIMEOUT_TICKS);
        ArmVisualScenario.untilOrFail(script, "craft: the panel counts " + what + " ready",
                context -> screen(context).craftPanel().readyCrafts() == CHAINS, SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: check the chain's cells with everything here",
                context -> checkCells(context, CHAIN_GRID, TerminalCraftPanel.CellState.HERE, CHAINS));
        craft(script, new CraftCheck("craft every chain with a Shift-click on the result", TerminalCraftResult.CRAFTED,
                CHAINS, IRON_CHAIN, Map.of(NUGGET, (long) -NUGGETS_PER_CHAIN * CHAINS, IRON,
                        (long) -IRON_PER_CHAIN * CHAINS, IRON_CHAIN, (long) CHAINS)), this::shiftClickResult);
        ArmVisualScenario.untilOrFail(script, "craft: the buffer holds no chain any more",
                context -> screen(context).craftPanel().readyCrafts() == 0, SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
    }

    /** What the chain's cells are drawn as before the Fetch: the nuggets makeable, the ingot available. */
    private static List<TerminalCraftPanel.CellState> chainCells() {
        List<TerminalCraftPanel.CellState> cells = new ArrayList<>(CraftGrid.SIZE);
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            Optional<ItemKey> key = CHAIN_GRID.cell(cell);
            cells.add(key.isEmpty() ? TerminalCraftPanel.CellState.EMPTY
                    : key.get().equals(NUGGET) ? TerminalCraftPanel.CellState.MAKEABLE
                            : TerminalCraftPanel.CellState.AVAILABLE);
        }
        return cells;
    }

    /**
     * The cake: three milk buckets, two sugar, an egg and three wheat, crafted from the buffer, and the three empty
     * buckets it leaves there. The milk is picked as a ghost, the sugar and the egg by a left click on the stock list
     * <b>while</b> a ghost is on the cursor — which picks and never requests — and the wheat row with Shift +
     * right-click, which fills the next free cells in row-major order. With the milk ghost held, and again once it is
     * dropped, a stock cell's and an inventory slot's tooltip are checked ({@link #tooltipSteps}): the chests of the
     * first craft are in the inventory by now.
     * <p>
     * The ingredients are fetched for {@value #CAKES_ASKED} cakes, which do not fit: every milk bucket takes a buffer
     * slot of its own. The server requests nothing and asks instead ({@link TerminalCraftResult#ASKING}); the question
     * names the room and the {@value #CAKES_FITTING} cakes that fit, and the Yes in the confirmation panel fetches
     * exactly those. One of them is crafted.
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

        // x4 does not fit the buffer: the server asks before it requests anything, and the Yes fetches what fits.
        String asked = CAKES_ASKED + " cakes";
        amount(script, CAKES_ASKED);
        script.client("craft: check the Fetch button's tooltip for " + asked, context -> checkFetchTooltip(context,
                CAKE, CAKES_ASKED, List.of(Map.entry(MILK, MILK_PER_CAKE * CAKES_ASKED),
                        Map.entry(SUGAR, SUGAR_PER_CAKE * CAKES_ASKED), Map.entry(EGG, EGGS_PER_CAKE * CAKES_ASKED),
                        Map.entry(WHEAT, WHEAT_PER_CAKE * CAKES_ASKED))));
        fetch(script, asked);
        fetchAnswered(script, "the Fetch for " + asked, TerminalCraftResult.ASKING, CAKES_FITTING);
        script.client("craft: CHECK the question names the buffer's room", this::checkRoomQuestion)
                .server("craft: CHECK the question requested nothing", CraftVisualScenario::checkNothingRequested)
                .server("craft: CHECK the census after the question",
                        (server, context) -> assertCensus(server, context, "after the question about " + asked));
        shot(script, "craft-question-room" + suffix);
        script.client("craft: click Yes in the question", this::clickYes);
        String fitting = CAKES_FITTING + " cakes";
        fetchAnswered(script, "the Yes for " + fitting, TerminalCraftResult.STARTED, CAKES_FITTING);
        orderOpened(script, CAKE_GRID, CAKES_FITTING);
        fetchDone(script, "the ingredients of " + fitting, CAKE_GRID, Map.of(MILK, (long) MILK_PER_CAKE * CAKES_FITTING,
                SUGAR, (long) SUGAR_PER_CAKE * CAKES_FITTING, EGG, (long) EGGS_PER_CAKE * CAKES_FITTING, WHEAT,
                (long) WHEAT_PER_CAKE * CAKES_FITTING));
        ArmVisualScenario.untilOrFail(script, "craft: the panel counts " + fitting + " ready",
                context -> screen(context).craftPanel().readyCrafts() == CAKES_FITTING, SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: check the cake's cells with everything here",
                context -> checkCells(context, CAKE_GRID, TerminalCraftPanel.CellState.HERE, CAKES_FITTING));

        craft(script, new CraftCheck("craft one cake", TerminalCraftResult.CRAFTED, 1, CAKE,
                Map.of(MILK, (long) -MILK_PER_CAKE, SUGAR, (long) -SUGAR_PER_CAKE, EGG, (long) -EGGS_PER_CAKE, WHEAT,
                        (long) -WHEAT_PER_CAKE, CAKE, 1L, BUCKET, (long) MILK_PER_CAKE)), this::clickResult);
        int left = CAKES_FITTING - 1;
        Map<ItemKey, Long> remainder = Map.of(MILK, (long) MILK_PER_CAKE * left, SUGAR, (long) SUGAR_PER_CAKE * left,
                EGG, (long) EGGS_PER_CAKE * left, WHEAT, (long) WHEAT_PER_CAKE * left, BUCKET, (long) MILK_PER_CAKE);
        script.serverUntil("craft: CHECK the three empty buckets stay in the buffer beside the other cake's "
                + "ingredients", (server, context) -> bufferIs(server, context, remainder), SCREEN_TIMEOUT_TICKS);
        ArmVisualScenario.untilOrFail(script, "craft: the screen shows the buckets in the buffer",
                context -> clientBufferCount(context, BUCKET) == 3, SCREEN_TIMEOUT_TICKS,
                context -> "the buffer slots show " + clientBufferCount(context, BUCKET) + " buckets");
        shot(script, "craft-remainder" + suffix);
    }

    /**
     * A crafting table nobody has room for: the buffer is full — the other cake's ingredients and the buckets, and a
     * stack of planks in every slot left, so the four it takes leave every stack standing — and the inventory has no
     * free slot and no crafting table to stack onto. The click must be refused and move nothing.
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

        // Two planks laid diagonally over a buffer full of planks: no recipe. The Fetch tooltip says so, and never
        // "Already here: click the result to craft", which would point at a result that is not there.
        clearGrid(script);
        pickGhost(script, PLANKS);
        stamp(script, 0, 4);
        dropGhost(script);
        ArmVisualScenario.untilOrFail(script, "craft: the server answered the diagonal planks with no recipe",
                context -> {
                    TerminalCraftPanel panel = screen(context).craftPanel();
                    return !panel.previewPending() && panel.describesGrid() && panel.shownResult().isEmpty();
                }, SCREEN_TIMEOUT_TICKS, context -> describePanel(screen(context)));
        script.client("craft: CHECK the Fetch tooltip of a grid without a recipe",
                CraftVisualScenario::checkNoRecipeFetchTooltip);
        // The crafting table again, so the grid this player leaves at the terminal is the one the second pass expects
        // the reopened screen to remember.
        clearGrid(script);
        pickGhost(script, PLANKS);
        stamp(script, 0, 1, 3, 4);
        dropGhost(script);
        ArmVisualScenario.untilOrFail(script, "craft: the crafting table is laid again",
                context -> previewShows(context, TABLE_GRID, Items.CRAFTING_TABLE), SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
    }

    /**
     * A grid the server answered with no recipe shows that refusal in the Fetch tooltip and none of the lines of a
     * Fetch that could start — above all not "Already here: click the result to craft", whatever the buffer holds.
     */
    private static void checkNoRecipeFetchTooltip(VisualContext context) {
        List<String> lines = texts(screen(context).craftPanel().fetchTooltip());
        String noRecipe = WareworksLang.translateDirect(TerminalCraftResult.NO_RECIPE.langKey()).getString();
        String nothing = WareworksLang.translateDirect(TerminalCraftResult.NOTHING_TO_FETCH.langKey()).getString();
        if (!lines.contains(noRecipe) || lines.contains(nothing))
            throw new VisualTestException("the Fetch tooltip of a grid without a recipe is " + lines);
        LOGGER.info(PREFIX + "craft: CHECK PASS a grid without a recipe shows {} in its Fetch tooltip", lines);
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

    /** A real click on the question's Yes; the question must be gone at once. */
    private void clickYes(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        fetchAnswerBefore = screen.craftFetchAnswer();
        ScreenInput.click(context.minecraft(), ScreenInput.terminalConfirmButton(screen, true));
        if (screen.isAskingSomething())
            throw new VisualTestException("the question is still up after its Yes");
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
     * Amount field to {@code amount}, one notch of the wheel per tick: the number of crafts the panel's Fetch is for
     * ({@code WarehouseTerminalScreen#fetchCrafts}), the field a click in the list requests with.
     */
    private static void amount(VisualScript script, int amount) {
        ArmVisualScenario.untilOrFail(script, "craft: scroll the amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(screen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, AMOUNT_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
        script.client("craft: CHECK a Fetch is for x" + amount + " now", context -> {
            int crafts = screen(context).fetchCrafts();
            if (crafts != amount)
                throw new VisualTestException("the amount field shows " + amount + ", but a Fetch would be for x"
                        + crafts);
        });
    }

    // --- Fetch (slice 2 of the issue, ADR-062) -----------------------------------------------------------------------

    /**
     * The chest's planks fetched through the panel: the Fetch button's tooltip names what a Fetch for
     * {@value #CHESTS} chests asks for, a click on it starts the craft order, and the order locks the grid, owns the
     * status row and shows its progress row. The ticks are frozen while the crane carries the planks, so the progress
     * row is shot mid-delivery ({@code craft-fetching}). Then the order finishes with exactly the planks in the buffer,
     * gives the grid back — still laid — and says the result can be clicked.
     */
    private void fetchChestSteps(VisualScript script, String suffix) {
        String what = CHESTS + " chests";
        amount(script, CHESTS);
        script.client("craft: check the Fetch button's tooltip for " + what, context -> checkFetchTooltip(context,
                        CHEST, CHESTS, List.of(Map.entry(PLANKS, PLANKS_FETCHED))))
                .client("craft: hover the Fetch button", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.terminalCraftFetch(screen(context))))
                .waitTicks(SETTLE_TICKS)
                .shot("craft-fetch-tooltip" + suffix);
        fetch(script, what);
        fetchAnswered(script, "the Fetch for " + what, TerminalCraftResult.STARTED, CHESTS);
        orderOpened(script, CHEST_GRID, CHESTS);
        script.client("craft: CHECK the running fetch locks the grid and owns the status row",
                context -> checkFetching(context, CHEST_GRID, CHEST, CHESTS));
        carrying(script, PLANKS);
        script.client("craft: CHECK the progress row while the crane carries the planks",
                CraftVisualScenario::checkProgress);
        shot(script, "craft-fetching" + suffix);
        script.freeze(false);
        fetchDone(script, "the planks for " + what, CHEST_GRID, Map.of(PLANKS, (long) PLANKS_FETCHED));
    }

    /**
     * A Fetch given up: the same chests once more, the locked grid refusing a right-click with the reason in the
     * status row, and — with the ticks frozen while the crane carries the planks — a real click on the progress row's
     * cancel mark. The server gives back the order's own share, which is the whole request, so the crane takes the
     * planks back into the racks instead of delivering them: the buffer stays empty and the census unchanged. The grid
     * unlocks and keeps the recipe.
     */
    private void cancelSteps(VisualScript script, String suffix) {
        String what = CHESTS + " more chests";
        fetch(script, what);
        fetchAnswered(script, "the Fetch for " + what, TerminalCraftResult.STARTED, CHESTS);
        orderOpened(script, CHEST_GRID, CHESTS);
        script.client("craft: right-click a cell of the locked grid", CraftVisualScenario::checkLockedEdit);
        carrying(script, PLANKS);
        script.client("craft: click the cancel mark on the progress row while the crane carries the planks",
                context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    fetchAnswerBefore = screen.craftFetchAnswer();
                    ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftCancelMark(screen));
                });
        fetchAnswered(script, "the cancel mark", TerminalCraftResult.CANCELLED, 0);
        // The answer comes back at once, but the menu pushes the order's state once per game tick, and the game time
        // stands still while the ticks are frozen: the order's end reaches the screen after the ticks run again.
        script.freeze(false);
        ArmVisualScenario.untilOrFail(script, "craft: the given-up fetch is gone from the screen",
                context -> !screen(context).craftOrderState().active(), SCREEN_TIMEOUT_TICKS,
                context -> describePanel(screen(context)));
        script.client("craft: CHECK the given-up fetch unlocked the grid and kept the recipe",
                context -> checkCancelled(context, CHEST_GRID));
        shot(script, "craft-cancelled" + suffix);
        script.serverUntil("craft: wait until the crane has taken the planks back and nothing is requested",
                        CraftVisualScenario::cancelSettled, DELIVERY_TIMEOUT_TICKS)
                .server("craft: CHECK the census after the fetch that was given up",
                        (server, context) -> assertCensus(server, context, "after the fetch that was given up"));
    }

    /** The census, then a real click on the Fetch button, which must be Fetch now and have a result to fetch for. */
    private void fetch(VisualScript script, String what) {
        script.server("craft: census before the Fetch for " + what,
                        (server, context) -> assertCensus(server, context, "before the Fetch for " + what))
                .client("craft: click Fetch for " + what, context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    if (screen.craftOrderAction() != TerminalCraftOrderPayload.Action.FETCH)
                        throw new VisualTestException("the order button is " + screen.craftOrderAction()
                                + ", not Fetch");
                    if (screen.craftPanel().shownResult().isEmpty())
                        throw new VisualTestException("the panel shows no result for its grid, so Fetch would only "
                                + "ask for a view: " + describePanel(screen));
                    fetchAnswerBefore = screen.craftFetchAnswer();
                    ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftFetch(screen));
                });
    }

    /** Waits for the answer to the last Fetch or order click and checks it is {@code result} x{@code crafts}. */
    private void fetchAnswered(VisualScript script, String what, TerminalCraftResult result, int crafts) {
        ArmVisualScenario.untilOrFail(script, "craft: the server answered " + what,
                context -> screen(context).craftFetchAnswer() != fetchAnswerBefore, ANSWER_TIMEOUT_TICKS,
                context -> "no answer arrived: " + describePanel(screen(context)));
        script.client("craft: CHECK the answer to " + what, context -> {
            TerminalCraftAnswerPayload answer = screen(context).craftFetchAnswer();
            if (answer == null || answer.result() != result || answer.crafted() != crafts)
                throw new VisualTestException(what + ": the server answered " + answer + ", expected " + result
                        + " x" + crafts);
            LOGGER.info(PREFIX + "craft: CHECK PASS {} answered {} x{}", what, answer.result(), answer.crafted());
        });
    }

    /** Waits until the screen shows the craft order open for {@code grid} and {@code crafts} crafts. */
    private static void orderOpened(VisualScript script, CraftGrid<ItemKey> grid, int crafts) {
        ArmVisualScenario.untilOrFail(script, "craft: the screen shows the craft order for x" + crafts, context -> {
            TerminalCraftOrderState order = screen(context).craftOrderState();
            return order.isOpen() && order.crafts() == crafts && order.cells().equals(grid);
        }, SCREEN_TIMEOUT_TICKS, context -> describePanel(screen(context)));
    }

    /**
     * Waits until the screen shows the fetch's items on their way, then on the server until the crane holds
     * {@code key}, then freezes the ticks and checks it still holds them and nothing has been delivered yet: the moment
     * between the pick and the drop, held still.
     * <p>
     * The screen is waited for <b>first</b>: the menu pushes the order's state at most once per game tick, and the
     * game time stands still while the ticks are frozen, so what the screen shows during the freeze is what it had
     * before it.
     */
    private static void carrying(VisualScript script, ItemKey key) {
        ArmVisualScenario.untilOrFail(script, "craft: the screen shows the " + id(key) + " on their way", context -> {
            TerminalCraftOrderState order = screen(context).craftOrderState();
            return order.isOpen() && order.inFlight() > 0L && order.delivered() == 0L;
        }, SCREEN_TIMEOUT_TICKS, context -> describePanel(screen(context)));
        script.serverUntil("craft: wait until the crane carries the " + id(key) + " of the fetch",
                        (server, context) -> craneCarries(server, context, key), DELIVERY_TIMEOUT_TICKS)
                .freeze(true)
                .server("craft: CHECK the crane still carries them with the ticks frozen", (server, context) -> {
                    StackerCraneBlockEntity crane = crane(server, context);
                    TerminalCraftOrderState order = terminal(server.overworld(), context.origin()).craftOrderState();
                    if (crane.heldItems().count(key) <= 0 || !order.isOpen() || order.delivered() > 0)
                        throw new VisualTestException("the crane was not caught carrying the " + id(key) + ": it holds "
                                + crane.heldItems() + ", the order is " + order.state() + " with " + order.delivered()
                                + " delivered");
                    LOGGER.info(PREFIX + "craft: frozen with the crane carrying {} {} in phase {}",
                            crane.heldItems().count(key), id(key), crane.craneState().phase());
                });
    }

    /**
     * Waits until the screen counts the order done, then until the buffer holds exactly {@code counts}, and checks
     * that the census did not move, that nothing is left requested, and that the grid is unlocked, still laid, and the
     * status row says to click the result.
     */
    private void fetchDone(VisualScript script, String what, CraftGrid<ItemKey> grid, Map<ItemKey, Long> counts) {
        ArmVisualScenario.untilOrFail(script, "craft: the fetch for " + what + " is done", context -> {
            TerminalCraftOrderState order = screen(context).craftOrderState();
            return order.active() && order.state() == ListOrderState.DONE;
        }, DELIVERY_TIMEOUT_TICKS, context -> describePanel(screen(context)));
        delivered(script, what, counts);
        script.server("craft: CHECK the census after the fetch for " + what,
                        (server, context) -> assertCensus(server, context, "after the fetch for " + what))
                .server("craft: CHECK the finished fetch left nothing requested",
                        CraftVisualScenario::checkNothingRequested)
                .client("craft: CHECK the finished fetch gave the grid back", context -> checkFetchDone(context,
                        grid));
    }

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

    /**
     * The Fetch button's tooltip before the click: {@code "Fetch the ingredients for Chest x3"}, one line per item a
     * Fetch asks for, in grid order and topped up against the buffer, and the hint about the amount field — every
     * line inside the terminal's row, in the language that is loaded.
     */
    private static void checkFetchTooltip(VisualContext context, ItemKey result, int crafts,
            List<Map.Entry<ItemKey, Integer>> toFetch) {
        WarehouseTerminalScreen screen = screen(context);
        if (screen.craftOrderAction() != TerminalCraftOrderPayload.Action.FETCH)
            throw new VisualTestException("the order button is " + screen.craftOrderAction() + ", not Fetch");
        List<String> wanted = new ArrayList<>();
        wanted.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_FETCH, result.toStack().getHoverName(),
                LangNumberFormat.format(crafts)).getString());
        for (Map.Entry<ItemKey, Integer> line : toFetch)
            wanted.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_TO_FETCH,
                    line.getKey().toStack().getHoverName(), LangNumberFormat.format(line.getValue())).getString());
        wanted.add(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_FETCH_HINT).getString());
        List<Component> tooltip = screen.craftPanel().fetchTooltip();
        if (!texts(tooltip).equals(wanted))
            throw new VisualTestException("the Fetch button's tooltip is " + texts(tooltip) + ", not " + wanted);
        // The button's place answers with the same lines, which is what the screen draws for a mouse resting on it.
        ScreenInput.Point button = ScreenInput.terminalCraftFetch(screen);
        if (!texts(screen.craftPanel().tooltipAt(button.x(), button.y())).equals(wanted))
            throw new VisualTestException("the panel draws another tooltip over the Fetch button: "
                    + texts(screen.craftPanel().tooltipAt(button.x(), button.y())));
        checkLines(context, "the Fetch button's tooltip", tooltip, wanted.size());
    }

    /**
     * The running fetch on screen: the grid is the order's and locked, the order button is Cancel and its tooltip says
     * so, the cells not yet here are plain ghosts, and the status row says what is being fetched for — first as the
     * Fetch's answer, then as the order's own line, both inside the row.
     */
    private static void checkFetching(VisualContext context, CraftGrid<ItemKey> grid, ItemKey result, int crafts) {
        WarehouseTerminalScreen screen = screen(context);
        TerminalCraftOrderState order = screen.craftOrderState();
        if (order.state() != ListOrderState.RUNNING || !screen.isCraftGridLocked()
                || !screen.craftPanel().grid().equals(grid))
            throw new VisualTestException("the fetch does not run with its grid locked: " + describePanel(screen));
        if (screen.craftOrderAction() != TerminalCraftOrderPayload.Action.CANCEL)
            throw new VisualTestException("the order button is " + screen.craftOrderAction() + " while the fetch runs");
        String cancel = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CANCEL).getString();
        if (!texts(screen.craftPanel().fetchTooltip()).equals(List.of(cancel)))
            throw new VisualTestException("the order button's tooltip is " + texts(screen.craftPanel().fetchTooltip())
                    + ", not '" + cancel + "'");
        checkCells(context, grid, TerminalCraftPanel.CellState.AVAILABLE, 0);
        Component name = result.toStack().getHoverName();
        String started = WarehouseTerminalScreen.craftFetchText(TerminalCraftResult.STARTED, crafts, name).getString();
        String shown = screen.feedbackLine().map(Component::getString).orElse("");
        if (!shown.equals(started))
            throw new VisualTestException("the status row says '" + shown + "', not '" + started + "'");
        Component running = WarehouseTerminalScreen.craftStatusText(ListOrderState.RUNNING, crafts, name);
        if (!screen.statusTextsFit() || !screen.fitsStatusRow(running))
            throw new VisualTestException("the fetch's status line does not fit the row: '" + shown + "', '"
                    + running.getString() + "'");
        LOGGER.info(PREFIX + "craft: CHECK PASS the fetch runs locked, the status row says '{}', its own line '{}'",
                shown, running.getString());
    }

    /**
     * The progress row while the crane carries the planks, the ticks frozen: nothing delivered yet, the planks on the
     * way, and the row's tooltip — with and without the cancel mark under the mouse — naming both numbers in full,
     * every line inside the terminal's row.
     */
    private static void checkProgress(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        TerminalCraftOrderState order = screen.craftOrderState();
        TerminalCraftPanel panel = screen.craftPanel();
        String delivered = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_PROGRESS,
                LangNumberFormat.format(order.delivered()), LangNumberFormat.format(order.wanted())).getString();
        String onTheWay = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_IN_FLIGHT,
                LangNumberFormat.format(order.inFlight())).getString();
        String cancelHint = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_CANCEL_MARK).getString();
        if (!texts(panel.progressTooltip(false)).equals(List.of(delivered, onTheWay)))
            throw new VisualTestException("the progress row's tooltip is " + texts(panel.progressTooltip(false)));
        ScreenInput.Point mark = ScreenInput.terminalCraftCancelMark(screen);
        List<Component> overMark = panel.tooltipAt(mark.x(), mark.y());
        if (!texts(overMark).equals(List.of(delivered, onTheWay, cancelHint)))
            throw new VisualTestException("the cancel mark's tooltip is " + texts(overMark));
        checkLines(context, "the cancel mark's tooltip", overMark, 3);
        LOGGER.info(PREFIX + "craft: CHECK PASS the progress row reads {} delivered of {}, {} on the way",
                order.delivered(), order.wanted(), order.inFlight());
    }

    /**
     * A real right-click on a cell of the locked grid, which would clear it: nothing changes, and the status row says
     * the fetch has to be given up first.
     */
    private static void checkLockedEdit(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        CraftGrid<ItemKey> before = screen.craftPanel().grid();
        ScreenInput.rightClick(context.minecraft(), ScreenInput.terminalCraftCell(screen, 0));
        if (!screen.craftPanel().grid().equals(before))
            throw new VisualTestException("a right-click changed the locked grid to " + screen.craftPanel().grid());
        String locked = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_GRID_LOCKED).getString();
        String shown = screen.feedbackLine().map(Component::getString).orElse("");
        if (!shown.equals(locked) || !screen.statusTextsFit())
            throw new VisualTestException("after an edit of the locked grid the status row says '" + shown + "', not '"
                    + locked + "' inside the row");
        LOGGER.info(PREFIX + "craft: CHECK PASS the locked grid refused a right-click: '{}'", shown);
    }

    /**
     * The fetch was given up: no order on screen any more, the grid unlocked with the recipe still laid, the order
     * button Fetch again, no progress row, and the status row says the fetch was given up.
     */
    private static void checkCancelled(VisualContext context, CraftGrid<ItemKey> grid) {
        WarehouseTerminalScreen screen = screen(context);
        if (screen.isCraftGridLocked() || !screen.craftPanel().grid().equals(grid)
                || screen.craftOrderAction() != TerminalCraftOrderPayload.Action.FETCH)
            throw new VisualTestException("after the cancel the panel is " + describePanel(screen));
        if (!screen.craftPanel().progressTooltip(false).isEmpty())
            throw new VisualTestException("the progress row is still there after the cancel");
        String cancelled = WarehouseTerminalScreen.craftFetchText(TerminalCraftResult.CANCELLED, 0, Component.empty())
                .getString();
        String shown = screen.feedbackLine().map(Component::getString).orElse("");
        if (!shown.equals(cancelled) || !screen.statusTextsFit())
            throw new VisualTestException("after the cancel the status row says '" + shown + "', not '" + cancelled
                    + "' inside the row");
        LOGGER.info(PREFIX + "craft: CHECK PASS the fetch was given up, the grid unlocked, the status row says '{}'",
                shown);
    }

    /**
     * The finished fetch: the order is done and locks nothing, the grid keeps its cells, the order button is Fetch
     * again, and the status row says to click the result.
     */
    private static void checkFetchDone(VisualContext context, CraftGrid<ItemKey> grid) {
        WarehouseTerminalScreen screen = screen(context);
        if (screen.isCraftGridLocked() || !screen.craftPanel().grid().equals(grid)
                || screen.craftOrderAction() != TerminalCraftOrderPayload.Action.FETCH)
            throw new VisualTestException("after the fetch the panel is " + describePanel(screen));
        String done = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_DONE).getString();
        String shown = screen.feedbackLine().map(Component::getString).orElse("");
        if (!shown.equals(done) || !screen.statusTextsFit())
            throw new VisualTestException("after the fetch the status row says '" + shown + "', not '" + done
                    + "' inside the row");
        LOGGER.info(PREFIX + "craft: CHECK PASS the fetch is done, the grid unlocked and kept, the status row says "
                + "'{}'", shown);
    }

    /**
     * The question a Fetch for {@value #CAKES_ASKED} cakes raises: {@value #CAKES_FITTING} of them fit, bound by the
     * room, none ready yet — and the lines the panel draws name exactly that, each inside the panel's text width, with
     * no "changed" line, since the player was asked nothing before.
     */
    private void checkRoomQuestion(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        if (screen.getMenu().bufferSlots() != BUFFER_SLOTS)
            throw new VisualTestException("the terminal has " + screen.getMenu().bufferSlots() + " buffer slots, but "
                    + "the cake's question is worked out for the shipped " + BUFFER_SLOTS);
        CraftFetchQuestion<ItemKey> question = screen.craftConfirmation();
        if (question == null || !screen.isAskingSomething())
            throw new VisualTestException("the Fetch question is not up: " + describePanel(screen));
        if (question.asked() != CAKES_ASKED || question.possible() != CAKES_FITTING || question.ready() != 0
                || question.bound() != CraftFetchQuestion.Bound.ROOM || question.producing() != 0L)
            throw new VisualTestException("the question is " + question + ", not x" + CAKES_FITTING + " of x"
                    + CAKES_ASKED + " bound by the room");
        Component cake = CAKE.toStack().getHoverName();
        String possible = LangNumberFormat.format(CAKES_FITTING);
        List<String> wanted = List.of(
                WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_QUESTION_TITLE, cake, possible).getString(),
                WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_QUESTION_ROOM, possible,
                        LangNumberFormat.format(CAKES_ASKED)).getString());
        List<Component> lines = ScreenInput.terminalCraftConfirmationLines(screen);
        if (!texts(lines).equals(wanted))
            throw new VisualTestException("the question reads " + texts(lines) + ", not " + wanted);
        Font font = context.minecraft().font;
        for (Component line : lines)
            if (font.width(line) > DIALOG_WIDTH)
                throw new VisualTestException("the question's line '" + line.getString() + "' is " + font.width(line)
                        + " px, wider than the panel's " + DIALOG_WIDTH + " px: it would wrap");
        LOGGER.info(PREFIX + "craft: CHECK PASS the question reads {}", texts(lines));
    }

    /**
     * The nugget cells before the Fetch: the server describes the nuggets as producible, not stopped, and short of what
     * x{@value #CHAINS} take; a nugget cell's tooltip says how many the racks hold of how many are needed and then that
     * the item can be made here and the Fetch asks first, and never that nothing makes it. The Fetch tooltip names
     * both items, the nuggets in the blue of their cells and the iron not.
     */
    private static void checkMakeable(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        TerminalCraftPanel panel = screen.craftPanel();
        int needed = NUGGETS_PER_CHAIN * CHAINS;
        TerminalCraftViewPayload.KeyView nuggets = panel.view().keyView(NUGGET)
                .orElseThrow(() -> new VisualTestException("the server has not described the nuggets"));
        if (!nuggets.producible() || nuggets.paused() || nuggets.available() <= 0L
                || nuggets.available() >= needed)
            throw new VisualTestException("the server describes the nuggets as " + nuggets + ", not as short of "
                    + needed + " but makeable");
        List<Component> tooltip = panel.cellTooltip(HOVERED_NUGGET_CELL);
        List<String> lines = texts(tooltip);
        String shortLine = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_SHORT,
                LangNumberFormat.format(nuggets.available()), LangNumberFormat.format(needed)).getString();
        String makeable = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_MAKEABLE).getString();
        String missing = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_MISSING).getString();
        int shortAt = lines.indexOf(shortLine);
        if (shortAt < 0 || lines.indexOf(makeable) != shortAt + 1 || lines.contains(missing))
            throw new VisualTestException("a makeable cell's tooltip is " + lines + ", not '" + shortLine
                    + "' followed by '" + makeable + "'");
        checkLines(context, "a makeable cell's tooltip", tooltip, 5);
        checkFetchTooltip(context, IRON_CHAIN, CHAINS, List.of(Map.entry(NUGGET, needed),
                Map.entry(IRON, IRON_PER_CHAIN * CHAINS)));
        TextColor blue = tooltip.get(shortAt + 1).getStyle().getColor();
        List<Component> fetch = panel.fetchTooltip();
        if (blue == null || !blue.equals(fetch.get(1).getStyle().getColor())
                || blue.equals(fetch.get(2).getStyle().getColor()))
            throw new VisualTestException("the Fetch tooltip colours the nuggets " + fetch.get(1).getStyle().getColor()
                    + " and the iron " + fetch.get(2).getStyle().getColor() + ", the makeable line is " + blue);
        LOGGER.info(PREFIX + "craft: CHECK PASS the nuggets are makeable ({} of {} in the racks), the cell says {}",
                nuggets.available(), needed, lines);
    }

    /**
     * The question a Fetch for {@value #CHAINS} chains raises: every craft is possible, but only with production, so
     * the player is asked before anything is made. It names the nuggets with the nine the crafter's whole run makes
     * and no chain refusal, counts the racks' nuggets as they are, says what the racks alone could serve — as many
     * chains as they hold pairs of nuggets, at least one and fewer than asked for — and the lines the panel draws say
     * exactly that, each inside the panel's text width: the title, "Iron Nugget x9 will be made first.", "Without
     * making: x1" and its hint.
     */
    private static void checkOfferQuestion(VisualContext context) {
        WarehouseTerminalScreen screen = screen(context);
        CraftFetchQuestion<ItemKey> question = screen.craftConfirmation();
        if (question == null || !screen.isAskingSomething())
            throw new VisualTestException("the Fetch question is not up: " + describePanel(screen));
        long inStock = screen.craftPanel().view().keyView(NUGGET).map(TerminalCraftViewPayload.KeyView::available)
                .orElse(-1L);
        int needed = NUGGETS_PER_CHAIN * CHAINS;
        int withoutMaking = (int) (inStock / NUGGETS_PER_CHAIN);
        if (withoutMaking < 1 || withoutMaking >= CHAINS)
            throw new VisualTestException("the racks hold " + inStock + " nuggets, not some but too few for x"
                    + CHAINS);
        List<CraftFetchQuestion.Line<ItemKey>> named = List.of(new CraftFetchQuestion.Line<>(NUGGET, needed, inStock,
                NUGGETS_PER_INGOT));
        if (question.asked() != CHAINS || question.possible() != CHAINS || question.ready() != 0
                || question.bound() != CraftFetchQuestion.Bound.NONE || question.producing() != NUGGETS_PER_INGOT
                || question.withoutMaking() != withoutMaking || !question.named().equals(named)
                || question.chainRefusal().isPresent())
            throw new VisualTestException("the question is " + question + ", not x" + CHAINS + " with "
                    + NUGGETS_PER_INGOT + " nuggets made first and x" + withoutMaking + " without making");
        String without = LangNumberFormat.format(withoutMaking);
        List<String> wanted = List.of(
                WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_QUESTION_TITLE,
                        IRON_CHAIN.toStack().getHoverName(), LangNumberFormat.format(CHAINS)).getString(),
                WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_QUESTION_MADE,
                        NUGGET.toStack().getHoverName(), LangNumberFormat.format(NUGGETS_PER_INGOT)).getString(),
                WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_QUESTION_WITHOUT, without).getString(),
                WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_QUESTION_WITHOUT_HINT, without)
                        .getString());
        List<Component> lines = ScreenInput.terminalCraftConfirmationLines(screen);
        if (!texts(lines).equals(wanted))
            throw new VisualTestException("the offer reads " + texts(lines) + ", not " + wanted);
        Font font = context.minecraft().font;
        for (Component line : lines)
            if (font.width(line) > DIALOG_WIDTH)
                throw new VisualTestException("the offer's line '" + line.getString() + "' is " + font.width(line)
                        + " px, wider than the panel's " + DIALOG_WIDTH + " px: it would wrap");
        LOGGER.info(PREFIX + "craft: CHECK PASS the offer reads {}", texts(lines));
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
        // The machine's two motors sit in the ground, below the cogwheels that drive the arm and the crafter.
        level.setBlockAndUpdate(armMotorPos(dock),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(crafterMotorPos(dock),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, AISLE.getOpposite()));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        for (BlockPos pos : List.of(dock.below(), armMotorPos(dock), crafterMotorPos(dock))) {
            CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
            if (motor == null)
                throw new VisualTestException("the creative motor at " + pos + " is missing");
            motor.generatedSpeed.setValue(MOTOR_RPM);
        }
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
        buildMachine(level, dock);

        int next = 0;
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                for (int y = 0; y < STORAGE_LEVELS; y++) {
                    RackPosition position = RackPosition.of(x, y, side);
                    if (position.equals(STATION) || position.equals(MACHINE_INPUT))
                        continue;
                    BlockPos rack = layout.rackPos(position);
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

    /**
     * The player's machine behind the right-hand rack wall, the same machine the chain scenario's first one is: the
     * production station the crane delivers iron to, a Mechanical Arm that carries whatever the station holds into a
     * Mechanical Crafter, and the warehouse input the crafter's arrow points into. No warehouse block changes an item
     * here: the arm and the crafter are Create's, and the input takes the nuggets like any other delivery (ADR-024).
     * The crafter is driven from below, so the chests of the second rack level stand above the machine as everywhere.
     */
    private static void buildMachine(ServerLevel level, BlockPos dock) {
        BranchLayout layout = layout(dock);
        Direction inward = layout.sideDirection(Side.RIGHT).getOpposite();
        level.setBlockAndUpdate(layout.rackPos(STATION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, inward));
        level.setBlockAndUpdate(layout.rackPos(MACHINE_INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward));
        level.setBlockAndUpdate(crafterPos(dock), ChainVisualScenario.crafterState(AISLE.getOpposite(), inward));
        level.setBlockAndUpdate(crafterCogPos(dock), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(armPos(dock), AllBlocks.MECHANICAL_ARM.getDefaultState());
        level.setBlockAndUpdate(armCogPos(dock), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
    }

    /**
     * The station's pattern: what the player's crafter really does with an iron ingot. A pattern is the player's own
     * declaration; the grid's recipe never becomes one.
     */
    private static void writePattern(MinecraftServer server, VisualContext context) {
        WarehouseProductionBlockEntity station = station(server, context);
        if (!station.setPatternEntry(0, 0, IRON, IRON_PER_RUN)
                || !station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, NUGGET, NUGGETS_PER_INGOT))
            throw new VisualTestException("the pattern " + IRON_PER_RUN + " iron ingot -> " + NUGGETS_PER_INGOT
                    + " iron nuggets was refused");
    }

    private static boolean patternLive(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        return controller.aislePatterns().size() == 1 && controller.producibleKeys().equals(Set.of(NUGGET));
    }

    /**
     * Gives the arm its two points through Create's own placement packet, the message a player's click sends after
     * selecting blocks with a Mechanical Arm in hand, as the chain scenario does: take from the station, deposit into
     * the crafter.
     */
    private static void teachTheArm(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        ArmInteractionPoint take = ChainVisualScenario.point(level, layout(dock).rackPos(STATION), Mode.TAKE);
        ArmInteractionPoint deposit = ChainVisualScenario.point(level, crafterPos(dock), Mode.DEPOSIT);
        new ArmPlacementPacket(List.of(take, deposit), armPos(dock)).handle(context.serverPlayer(server));
    }

    /** The arm has resolved its two points into its own lists, and both the arm and the crafter turn. */
    private static boolean armReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, armPos(dock));
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                crafterPos(dock));
        if (arm == null || crafter == null)
            throw new VisualTestException("the player's machine is missing its arm or its crafter");
        return !(Boolean) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_UPDATE, arm)
                && ((List<?>) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_INPUTS, arm)).size() == 1
                && ((List<?>) ArmVisualScenario.Reflect.get(ArmVisualScenario.Reflect.ARM_OUTPUTS, arm)).size() == 1
                && arm.getSpeed() != 0.0F && crafter.getSpeed() != 0.0F;
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null || !SceneItemCensus.isFullyLoaded(level, censusBox))
            return false;
        // Every rack position of the two storage levels holds a chest, but the machine's station and its input.
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length - 2;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == 1 && controller.inputStations().size() == 1
                && controller.productionStations().size() == 1 && crane.isControllerLinked()
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

    /** Laying the chain, and the question about it, order nothing: no request to the terminal, no production. */
    private static void checkNothingOrdered(MinecraftServer server, VisualContext context) {
        checkNothingRequested(server, context);
        List<ProductionOrder<ItemKey, RackPosition>> open = controller(server, context).openProductionOrders();
        if (!open.isEmpty())
            throw new VisualTestException("something was ordered at a production station: " + open);
    }

    /** What the racks hold of the chain's two items before its Fetch: the numbers the whole run is checked against. */
    private void rememberStock(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        nuggetsBefore = controller.countOf(NUGGET);
        ironBefore = controller.countOf(IRON);
        chainRoot = null;
        if (nuggetsBefore <= 0L || nuggetsBefore >= (long) NUGGETS_PER_CHAIN * CHAINS)
            throw new VisualTestException("the racks hold " + nuggetsBefore + " nuggets, not some but too few for x"
                    + CHAINS);
        LOGGER.info(PREFIX + "craft: the racks hold {} nuggets and {} iron ingots before the chain offer",
                nuggetsBefore, ironBefore);
    }

    /**
     * The production the Yes started, checked in the server tick that first sees it: exactly one open production order,
     * the root of its plan, at the player's station, making the nuggets, backing the craft order's nugget line and
     * promising it what the racks do not hold. No production order anywhere makes the grid's Chain.
     */
    private boolean chainStarted(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        List<ProductionOrder<ItemKey, RackPosition>> open = controller.openProductionOrders();
        if (open.isEmpty())
            return false;
        BlockPos terminal = layout(context.origin()).rackPos(TERMINAL);
        RetrievalRequest<ItemKey, BlockPos> line = controller.requestsFor(terminal).stream()
                .filter(request -> request.key().equals(NUGGET)).findFirst()
                .orElseThrow(() -> new VisualTestException("the craft order asks for no nuggets: "
                        + controller.requestsFor(terminal)));
        ProductionOrder<ItemKey, RackPosition> root = open.getFirst();
        long promised = (long) NUGGETS_PER_CHAIN * CHAINS - nuggetsBefore;
        if (open.size() != 1 || !root.result().equals(NUGGET) || !root.station().equals(STATION) || root.isStep()
                || root.resultAmount() != NUGGETS_PER_INGOT || !root.backingRequest().equals(Optional.of(line.id()))
                || root.promisedToRequest() != promised)
            throw new VisualTestException("the Yes ordered " + open + " for the craft line " + line + ", not one run "
                    + "of nuggets at " + STATION + " promising it " + promised);
        if (controller.productionOrders().stream().anyMatch(order -> order.result().equals(IRON_CHAIN)))
            throw new VisualTestException("the grid's Chain was ordered: " + controller.productionOrders());
        chainRoot = root.id();
        LOGGER.info(PREFIX + "craft: CHECK PASS the Yes ordered one run of {} nuggets at the player's station, backing "
                + "the craft line and promising it {}", root.resultAmount(), promised);
        return true;
    }

    /** Whether the chain's production order is complete; fails if it ended any other way. */
    private boolean chainComplete(MinecraftServer server, VisualContext context) {
        UUID root = chainRoot;
        if (root == null)
            throw new VisualTestException("no production order of the chain was recorded");
        ProductionOrder<ItemKey, RackPosition> order = controller(server, context).productionOrder(root)
                .orElseThrow(() -> new VisualTestException("the chain's production order is gone"));
        if (order.state() == ProductionOrderState.COMPLETE)
            return true;
        if (!order.isOpen())
            throw new VisualTestException("the chain's production order ended " + order.state());
        return false;
    }

    /**
     * The one move in this scene that is not a move: the player's crafter turned an iron ingot into nine nuggets. It is
     * declared once the production order is complete — the nuggets are in the warehouse by then — and the census must
     * agree at once.
     */
    private void declareMachineRun(MinecraftServer server, VisualContext context) {
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, IRON, -IRON_PER_RUN), NUGGET,
                NUGGETS_PER_INGOT);
        assertCensus(server, context, "after the player's crafter made the nuggets");
    }

    /**
     * The racks after the chain's fetch: the nuggets they held plus the crafter's whole run less what the buffer took,
     * the iron less the ingot the crafter used and the four in the buffer; nothing is still being made and nothing
     * is stopped.
     */
    private boolean surplusStored(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        long nuggets = nuggetsBefore + NUGGETS_PER_INGOT - (long) NUGGETS_PER_CHAIN * CHAINS;
        long iron = ironBefore - IRON_PER_RUN - (long) IRON_PER_CHAIN * CHAINS;
        if (!controller.openProductionOrders().isEmpty() || controller.pausedStockRuleCount() != 0)
            throw new VisualTestException("after the chain " + controller.openProductionOrders().size()
                    + " production orders are open and " + controller.pausedStockRuleCount() + " items stopped");
        if (controller.countOf(NUGGET) != nuggets || controller.countOf(IRON) != iron)
            return false;
        LOGGER.info(PREFIX + "craft: CHECK PASS the racks hold {} nuggets and {} iron ingots after the chain", nuggets,
                iron);
        return true;
    }

    /**
     * Whether the crane holds the craft order's {@code key} right now: picked, and not yet delivered. Fails once the
     * order is no longer open, because then the moment has passed.
     */
    private static boolean craneCarries(MinecraftServer server, VisualContext context, ItemKey key) {
        TerminalCraftOrderState order = terminal(server.overworld(), context.origin()).craftOrderState();
        if (!order.isOpen())
            throw new VisualTestException("the fetch ended (" + order.state() + ", " + order.delivered() + " of "
                    + order.wanted() + " delivered) before the crane was seen carrying " + id(key));
        return crane(server, context).heldItems().count(key) > 0;
    }

    /**
     * Whether everything a given-up fetch set going has come to rest: the crane has no job and holds nothing, and no
     * request to the terminal is left. Fails if the buffer received anything meanwhile — the planks were carried for
     * a request that is gone, so they go back into the racks.
     */
    private static boolean cancelSettled(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        if (!terminal.bufferedItems().keys().isEmpty())
            throw new VisualTestException("the buffer received " + terminal.bufferedItems().keys() + " of a fetch "
                    + "that was given up");
        if (terminal.hasOpenCraftOrder())
            throw new VisualTestException("the terminal still has an open craft order after the cancel");
        StackerCraneBlockEntity crane = crane(server, context);
        return crane.currentJob().isEmpty() && crane.heldItems().isEmpty()
                && controller(server, context).requestsFor(terminal.getBlockPos()).isEmpty();
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

    private static WarehouseProductionBlockEntity station(MinecraftServer server, VisualContext context) {
        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION
                .getNullable(server.overworld(), layout(context.origin()).rackPos(STATION));
        if (station == null)
            throw new VisualTestException("the production station of the player's machine is missing");
        return station;
    }

    /** Behind the machine's input, one step outside the rack wall: the crafter's arrow points back into that input. */
    private static BlockPos crafterPos(BlockPos dock) {
        BranchLayout layout = layout(dock);
        return layout.rackPos(MACHINE_INPUT).relative(layout.sideDirection(MACHINE_INPUT.side()));
    }

    /** Below the crafter: a crafter is a small cogwheel on the axis of its facing. */
    private static BlockPos crafterCogPos(BlockPos dock) {
        return crafterPos(dock).below();
    }

    private static BlockPos crafterMotorPos(BlockPos dock) {
        return crafterCogPos(dock).relative(AISLE);
    }

    /** Two steps behind the station, within reach of the station and of the crafter. */
    private static BlockPos armPos(BlockPos dock) {
        BranchLayout layout = layout(dock);
        return layout.rackPos(STATION).relative(layout.sideDirection(STATION.side()), 2);
    }

    private static BlockPos armCogPos(BlockPos dock) {
        return armPos(dock).relative(AISLE.getOpposite());
    }

    private static BlockPos armMotorPos(BlockPos dock) {
        return armCogPos(dock).below();
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

    private static StackerCraneBlockEntity crane(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(server.overworld(),
                context.origin());
        if (crane == null)
            throw new VisualTestException("the stacker crane of the aisle is missing");
        return crane;
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
                case MAKEABLE -> '+';
                case STOPPED -> 'P';
                case MISSING -> 'M';
                case UNKNOWN -> '?';
            });
        TerminalCraftAnswerPayload answer = screen.craftAnswer();
        TerminalCraftAnswerPayload fetched = screen.craftFetchAnswer();
        TerminalCraftOrderState order = screen.craftOrderState();
        String orderText = !order.active() ? "none" : String.format(Locale.ROOT, "%s(x%d %d/%d inFlight=%d)",
                order.state(), order.crafts(), order.delivered(), order.wanted(), order.inFlight());
        return String.format(Locale.ROOT,
                "cells=%s ghost=%s result=%s ready=%d preview=%s answer=%s fetch=%s order=%s locked=%s",
                cells, panel.ghost().map(CraftVisualScenario::id).orElse("none"),
                panel.shownResult().isEmpty() ? "none" : id(ItemKey.of(panel.shownResult())), panel.readyCrafts(),
                panel.view().recipe().map(ResourceLocation::toString).orElse("none"),
                answer == null ? "none" : answer.result() + "x" + answer.crafted(),
                fetched == null ? "none" : fetched.result() + "x" + fetched.crafted(), orderText,
                screen.isCraftGridLocked());
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
