package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.schematics.SchematicExport;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.cannon.MaterialChecklist;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonMenu;
import com.simibubi.create.foundation.utility.CreatePaths;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ClipboardList;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalListState;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.ListOrderState;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.network.TerminalConfirmPayload;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "checklist": a <b>real Create Schematicannon</b> prints the material checklist of a build site onto a
 * clipboard, and the warehouse works that clipboard off (M23, issue #19, {@code docs/warehouse-system.md} §3.4.4).
 * <p>
 * It is the one scenario that plays the whole feature through the way a player does, and every claim one of its
 * screenshots makes is asserted on the server first — which entries are ticked off, what has really been delivered
 * into the terminal, what the crane is carrying and what the aisle still holds — because a screenshot cannot be
 * trusted to prove a number.
 * <p>
 * <b>The story.</b> A {@value #SITE_SIZE}x{@value #SITE_SIZE} hut is built beside the warehouse, saved as a schematic
 * the way a player's upload leaves it on the server ({@link SchematicExport}, {@code schematics/uploaded/<player>})
 * and cleared again. The schematic goes into the Schematicannon, a blank clipboard into its paper slot, and Create's
 * own paper printer writes the checklist: {@value #LISTED_ENTRIES} entries, {@value #LISTED_ITEMS} items, read-only,
 * exactly as {@code MaterialChecklist#createWrittenClipboard} leaves it. The player carries that clipboard to the
 * warehouse terminal, drops it into the list slot and presses Fetch. The warehouse is <b>short of glass</b>
 * ({@value #GLASS_IN_STOCK} of {@value #ROOF_BLOCKS}), so the M20 panel asks the partial question first; after the Yes
 * the crane fetches the list in portions and ticks each entry off on the clipboard as it delivers it. The terminal's
 * buffer is deliberately <b>full</b> when the list is started, which proves what the issue insists on: the list is
 * never refused because it does not fit, nothing is delivered while there is no room, and the first freed slot
 * continues it. The glass the warehouse was short of is then fed into the warehouse input, the crane stores it, the
 * last entry follows, and the finished clipboard is pulled out of the slot and opened in Create's own clipboard
 * screen — the order and its receipt in one.
 * <p>
 * <b>Real input.</b> The world is creative with the vanilla reach ({@link #worldProfile()}), so every screen is opened
 * by a real right-click ({@link KeyMapping#click} on the use key, aimed and checked like {@link ArmVisualScenario})
 * and every item is carried from slot to slot by real left clicks through {@link ScreenInput} — pick the stack up, put
 * it down, which is what a player does when no shift-click is available to the harness. The list button and the
 * panel's Yes are clicked the same way, at the point the screen's own hit test confirms.
 * <p>
 * <b>Items.</b> {@link SceneItemCensus} brackets every phase. Its box is the aisle alone: the Schematicannon and the
 * build site stand outside it on purpose, because the schematic item and the blank clipboard pass through the cannon's
 * own slots, and a clipboard changes its item identity by design whenever an entry is ticked off — the same reason the
 * GameTests of this feature leave it out of their census. The clipboard is asserted explicitly instead, from the
 * printed checklist to the receipt the player carries away.
 */
public final class ChecklistVisualScenario implements VisualScenario {
    public static final String NAME = "checklist";

    private static final String WORLD_FOLDER = "wareworks_visual_checklist";
    /** A cannon print, a dialog, a blocked list, a refill and two screens full of clicks: far above the default. */
    private static final long RUN_TIMEOUT_MILLIS = 15L * 60L * 1000L;

    // --- the warehouse (offsets from the dock; the aisle runs east, so the right rack side is +z) --------------------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    private static final RackPosition TERMINAL_RACK = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = RackPosition.of(2, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 4;
    private static final int STORAGE_LEVELS = 2;

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    private static final BlockPos TERMINAL = new BlockPos(1, 0, 1);
    /** Where the player puts what they took out of the terminal; inside the census box, so nothing is lost. */
    private static final BlockPos SPILL_CHEST = new BlockPos(-2, 0, 2);
    /** The Schematicannon, well clear of the aisle and of any inventory it could count as gathered material. */
    private static final BlockPos CANNON = new BlockPos(2, 0, 5);
    /** Where the player is lifted to before it starts flying: in the air beside the scene. */
    private static final BlockPos LIFT = new BlockPos(-3, 4, 4);
    /** A patch of ground the finished clipboard is right-clicked over, which is what opens it. */
    private static final BlockPos GROUND = new BlockPos(-1, -1, 3);

    // --- the build site the checklist is for ------------------------------------------------------------------------
    /** Lower corner of the hut: a cobblestone floor, oak plank walls, an iron block core and a glass roof. */
    private static final BlockPos SITE = new BlockPos(0, 0, 9);
    private static final int SITE_SIZE = 5;
    private static final int SITE_HEIGHT = 3;
    private static final String SCHEMATIC_FILE = "wareworks_checklist.nbt";

    private static final int FLOOR_BLOCKS = SITE_SIZE * SITE_SIZE;
    private static final int ROOF_BLOCKS = SITE_SIZE * SITE_SIZE;
    private static final int WALL_BLOCKS = 4 * (SITE_SIZE - 1);
    private static final int CORE_BLOCKS = 1;
    /** Items the whole checklist asks for. */
    private static final int LISTED_ITEMS = FLOOR_BLOCKS + ROOF_BLOCKS + WALL_BLOCKS + CORE_BLOCKS;
    /** Entries the checklist has: one per item type of the hut. */
    private static final int LISTED_ENTRIES = 4;

    private static final ItemKey FLOOR_ITEM = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey ROOF_ITEM = ItemKey.of(Items.GLASS);
    private static final ItemKey WALL_ITEM = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey CORE_ITEM = ItemKey.of(Items.IRON_BLOCK);
    private static final List<ItemKey> LISTED_KEYS = List.of(FLOOR_ITEM, ROOF_ITEM, WALL_ITEM, CORE_ITEM);

    private static final int FLOOR_IN_STOCK = 64;
    private static final int WALL_IN_STOCK = 64;
    private static final int CORE_IN_STOCK = CORE_BLOCKS;
    /** The partial case: the roof wants {@value #ROOF_BLOCKS} panes of glass and the warehouse holds this many. */
    private static final int GLASS_IN_STOCK = 12;
    /** What the warehouse falls short of, i.e. what the dialog asks about. */
    private static final int GLASS_MISSING = ROOF_BLOCKS - GLASS_IN_STOCK;
    /** What the racks can give right now, over the whole list. */
    private static final long SERVEABLE_ITEMS = LISTED_ITEMS - GLASS_MISSING;
    /** Entries the racks cover in full: everything but the glass. */
    private static final int ENTRIES_SERVED = LISTED_ENTRIES - 1;

    /** What the player left in the terminal, so its buffer is full when the list is started. */
    private static final ItemKey FILLER_ITEM = ItemKey.of(Items.REDSTONE);
    private static final int FILLER_STACK = 64;

    // --- the Schematicannon's slots ({@code SchematicannonInventory#isItemValid}, {@code tickPaperPrinter}) ----------
    private static final int CANNON_SLOT_SCHEMATIC = 0;
    private static final int CANNON_SLOT_PAPER = 2;
    private static final int CANNON_SLOT_CHECKLIST = 3;

    // --- the area ----------------------------------------------------------------------------------------------------
    private static final int CLEAR_MIN_X = -4;
    private static final int CLEAR_MAX_X = RAILS + 3;
    private static final int CLEAR_MIN_Z = -4;
    private static final int CLEAR_MAX_Z = 15;
    private static final int CLEAR_HEIGHT = 7;
    /** The census box is the aisle alone (see the class comment); the cannon and the site lie outside it. */
    private static final int CENSUS_MIN_X = -3;
    private static final int CENSUS_MAX_X = RAILS + 2;
    private static final int CENSUS_MIN_Y = -1;
    private static final int CENSUS_MAX_Y = 5;
    private static final int CENSUS_MIN_Z = -3;
    private static final int CENSUS_MAX_Z = 2;

    // --- timing ------------------------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int AIM_TIMEOUT_TICKS = 60;
    private static final int CLICK_TIMEOUT_TICKS = 40;
    private static final int HELD_ITEM_TIMEOUT_TICKS = 100;
    private static final int PRINT_TIMEOUT_TICKS = 400;
    private static final int MOMENT_TIMEOUT_TICKS = 1800;
    private static final int DELIVERY_TIMEOUT_TICKS = 3600;
    private static final int STORE_TIMEOUT_TICKS = 2400;
    /** Game ticks the full destination is watched before the player empties it. */
    private static final int BLOCKED_TICKS = 80;
    private static final int SETTLE_TICKS = 4;
    /** Client ticks between two clicks on slots, kept above vanilla's 250 ms double-click window. */
    private static final int CLICK_GAP_TICKS = 8;
    /** What one portion of a list order would ask for in {@link #checkPortionQuestionPanel}. */
    private static final long PORTION_WANTED = 1L;
    /** What a whole run of a pattern would make for it, i.e. more than that portion asked for. */
    private static final long PORTION_MADE = 4L;

    // --- hotbar ------------------------------------------------------------------------------------------------------
    private static final int SLOT_SCHEMATIC = 0;
    private static final int SLOT_CLIPBOARD = 1;
    /** Never filled: selecting it gives the player an empty hand, which is what opens a block's own screen. */
    private static final int SLOT_EMPTY = 8;

    // --- clicks ------------------------------------------------------------------------------------------------------
    private static final ArmVisualScenario.Click CLICK_CANNON = new ArmVisualScenario.Click("cannon", CANNON,
            Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 1.9, 1.8));
    private static final ArmVisualScenario.Click CLICK_TERMINAL = new ArmVisualScenario.Click("terminal", TERMINAL,
            Direction.UP, new Vec3(0.3, 0.5, 0.7), new Vec3(0.0, 1.9, 1.7));
    private static final ArmVisualScenario.Click CLICK_GROUND = new ArmVisualScenario.Click("ground", GROUND,
            Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 1.8, 1.6));

    // --- camera views (relative to the dock's lower corner) -----------------------------------------------------------
    /** The working scene: the aisle with its rack wall, the terminal and the Schematicannon beside it. */
    private static final CameraView OVERVIEW = CameraView.of("overview", 6.5, 4.5, 6.5, 3.0, 0.8, 0.0);
    /** The build site, with the Schematicannon in front of it: the same view before and after the hut is cleared. */
    private static final CameraView AT_SITE = CameraView.of("site", 7.5, 5.0, 3.0, 2.5, 1.0, 10.5);
    /** Down the aisle from its far end, so a travelling crane is seen with the rack wall beside it. */
    private static final CameraView ALONG_AISLE = CameraView.of("aisle", RAILS + 3.0, 3.6, 3.5, 2.0, 1.0, 0.0);

    /** The view that follows the loaded carriage: beside the aisle, on the open side opposite the rack wall. */
    private static final String BESIDE = "beside";
    private static final double BLOCK_CENTER = 0.5;
    private static final double BESIDE_DISTANCE = 2.6;
    private static final double BESIDE_ABOVE = 1.3;
    private static final double CARRIAGE_LOOK_HEIGHT = 0.85;
    /** A travelling crane this far from its target is far enough into the aisle for a picture. */
    private static final double MIN_REMAINING_TRAVEL = 1.5;
    /**
     * Items the crane must be carrying for the carry shot. The list's smallest entry is a single iron block, which is
     * a speck on the carriage; this waits for a load a picture can show.
     */
    private static final int CARRY_SHOT_ITEMS = 8;

    /** Server thread: the census box of the aisle, set once the origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Server thread: what the aisle must hold, grown by everything the scenario feeds in. */
    private volatile Map<ItemKey, Long> expected = Map.of();

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A throw-away world with real interaction: creative and the vanilla reach, because every screen of this scenario
     * is opened by a right-click and every item is moved by a click in a menu — and a spectator may do neither.
     */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("checklist: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "checklist run"))
                .server("checklist: clear the area and place the creative motor", this::placeMotor)
                .server("checklist: build the aisle, the terminal, the input and the stocked racks", this::buildAisle)
                // The player flies before the first camera move: a camera view above the ground only holds while it
                // does (a client player standing on the ground switches flying off again at once, LocalPlayer#aiStep).
                .server("checklist: lift the player into the air", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    BlockPos above = context.origin().offset(LIFT);
                    player.teleportTo(server.overworld(), above.getX() + BLOCK_CENTER, above.getY(),
                            above.getZ() + BLOCK_CENTER, player.getYRot(), player.getXRot());
                })
                .waitTicks(SETTLE_TICKS)
                .server("checklist: the player flies and carries a blank clipboard",
                        ChecklistVisualScenario::equipPlayer)
                .server("checklist: build the hut at the build site", ChecklistVisualScenario::buildSite)
                .shotFrom(AT_SITE, "hut")
                .server("checklist: save the hut as a schematic and clear the site again", this::saveSchematic)
                .server("checklist: place the Schematicannon", ChecklistVisualScenario::placeCannon)
                .serverUntil("checklist: wait until the controller has indexed every rack", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("checklist: baseline census of the aisle", (server, context) -> {
                    expected = SceneItemCensus.take(server.overworld(), censusBox);
                    LOGGER.info(PREFIX + "checklist: baseline census {} in {}", SceneItemCensus.describe(expected),
                            censusBox);
                });
        // The site is empty again and the schematic is deployed over it: holding the schematic is what makes Create
        // draw its ghost, so the shot shows exactly what the cannon is about to measure.
        selectHotbar(script, SLOT_SCHEMATIC, "the deployed schematic",
                stack -> stack.has(AllDataComponents.SCHEMATIC_FILE));
        script.until("checklist: Create draws the deployed schematic over the empty site",
                        ChecklistVisualScenario::schematicGhostShown, HELD_ITEM_TIMEOUT_TICKS)
                .shotFrom(AT_SITE, "deployed");
        takeAnEmptyHand(script);

        printTheChecklist(script);
        handTheClipboardOver(script);
        fetchTheList(script);
        waitForTheFullDestination(script);
        carryTheGoods(script);
        tickTheEntriesOff(script);
        bringTheMissingGlass(script);
        readTheReceipt(script);

        script.client("checklist: every check passed",
                context -> LOGGER.info(PREFIX + "checklist: ALL CHECKS PASSED"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.shotFrom(OVERVIEW, "scene").shotFrom(ALONG_AISLE, "warehouse");
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        Screen open = context.minecraft().screen;
        status.append("screen=").append(open == null ? "none" : open.getClass().getSimpleName());
        if (open instanceof WarehouseTerminalScreen terminal) {
            TerminalListState list = terminal.listState();
            status.append(String.format(Locale.ROOT,
                    " list=%s %d/%d outstanding=%d inFlight=%d asking=%s button=%s line='%s' fits=%s",
                    list.active() ? list.state().name() : "none", list.entriesComplete(), list.entries(),
                    list.outstanding(), list.inFlight(), terminal.isAskingSomething(), terminal.listAction(),
                    terminal.shownStatusLine().getString(), terminal.statusTextsFit()));
        }
        if (open instanceof ClipboardScreen clipboard)
            status.append(" clipboard=").append(describe(ClipboardList.read(stackOf(clipboard.content))));
        clientCrane(context).ifPresent(crane -> {
            CranePose pose = crane.craneState().pose();
            status.append(String.format(Locale.ROOT, " crane=%s posX=%.2f posY=%.2f arm=%.2f held=%d",
                    crane.craneState().phase(), pose.x(), pose.y(), pose.arm(), crane.goggleInfo().heldCount()));
        });
        return status.toString();
    }

    // --- 1: the cannon prints the checklist --------------------------------------------------------------------------

    /**
     * The real printing path: the schematic into the cannon's blueprint slot, a blank clipboard into its paper slot,
     * and Create's own {@code tickPaperPrinter} writes the checklist into the output slot — every item moved with
     * clicks in the cannon's own screen.
     */
    private void printTheChecklist(VisualScript script) {
        rightClick(script, CLICK_CANNON);
        ArmVisualScenario.untilOrFail(script, "checklist: the Schematicannon's screen is open",
                ChecklistVisualScenario::cannonScreenOpen, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        moveInMenu(script, "the schematic", "the cannon's blueprint slot", hotbar(SLOT_SCHEMATIC),
                menuSlot(CANNON_SLOT_SCHEMATIC));
        // The mouse rests on the blueprint slot after the click, so the shot carries the schematic's own file name.
        script.serverUntil("checklist: the cannon read the schematic and measured the hut's material",
                        this::checklistMeasured, PRINT_TIMEOUT_TICKS)
                .shot("cannon-schematic");
        moveInMenu(script, "the blank clipboard", "the cannon's paper slot", hotbar(SLOT_CLIPBOARD),
                menuSlot(CANNON_SLOT_PAPER));
        script.serverUntil("checklist: the cannon printed the material checklist onto the clipboard",
                        this::checklistPrinted, PRINT_TIMEOUT_TICKS)
                .server("checklist: check the printed checklist entry by entry", this::checkPrintedChecklist);
        hoverNothing(script, "so the printed checklist in the output slot is not covered by a tooltip");
        script.shot("cannon-printed");
        moveInMenu(script, "the printed checklist", "the player's own inventory", menuSlot(CANNON_SLOT_CHECKLIST),
                hotbar(SLOT_CLIPBOARD));
        script.serverUntil("checklist: the player carries the printed checklist",
                        (server, context) -> ClipboardList.isClipboard(
                                context.serverPlayer(server).getInventory().getItem(SLOT_CLIPBOARD)),
                        SCREEN_TIMEOUT_TICKS)
                .shotWithGui("carried")
                .client("checklist: close the cannon's screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("checklist: the cannon's screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    // --- 2: the clipboard goes into the terminal's list slot ---------------------------------------------------------

    private void handTheClipboardOver(VisualScript script) {
        openTerminalScreen(script);
        script.server("checklist: the player left a full buffer of " + FILLER_ITEM + " in the terminal",
                        this::fillBuffer)
                // The empty list slot is the one place the whole feature is explained in words, so it is read and shot
                // before anything goes in.
                .client("checklist: rest the mouse on the empty list slot",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.menuSlot(terminalScreen(context), listSlotIndex(terminalScreen(context)))))
                .waitTicks(SETTLE_TICKS)
                .client("checklist: check what the empty list slot says",
                        ChecklistVisualScenario::checkEmptyListSlotTooltip)
                .shot("list-slot-empty");
        moveInMenu(script, "the checklist", "the terminal's list slot", hotbar(SLOT_CLIPBOARD), terminalListSlot());
        script.serverUntil("checklist: the clipboard lies in the terminal's list slot", (server, context) -> {
                    WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context);
                    if (!ClipboardList.isClipboard(terminal.listClipboard()))
                        return false;
                    if (terminal.hasListOrder())
                        throw new VisualTestException("a clipboard in the slot must order nothing by itself");
                    LOGGER.info(PREFIX + "checklist: the list slot holds the checklist and nothing was ordered: {}",
                            describe(ClipboardList.read(terminal.listClipboard())));
                    return true;
                }, SCREEN_TIMEOUT_TICKS)
                .client("checklist: rest the mouse on the list slot, so it says what it is for",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.menuSlot(terminalScreen(context), listSlotIndex(terminalScreen(context)))))
                .waitTicks(SETTLE_TICKS)
                .client("checklist: check the list slot's own words", ChecklistVisualScenario::checkListSlotTooltip)
                .shot("list-slot");
    }

    // --- 3: Fetch, the partial question, and the Yes -----------------------------------------------------------------

    /**
     * The one dialog of the feature: the warehouse is short of glass, so pressing Fetch measures the whole list and
     * asks before anything is requested. The numbers behind the panel and the lines it draws are both checked, and the
     * server is checked to have started nothing at all while the question is up.
     */
    private void fetchTheList(VisualScript script) {
        script.client("checklist: check the panel a single portion's question draws",
                        ChecklistVisualScenario::checkPortionQuestionPanel)
                .waitTicks(SETTLE_TICKS)
                .shot("portion-dialog")
                .client("checklist: drop that panel again", ChecklistVisualScenario::dropPortionQuestionPanel)
                .until("checklist: the panel is gone", context -> !terminalScreen(context).isAskingSomething(),
                        SCREEN_TIMEOUT_TICKS)
                .client("checklist: press the terminal's list button",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.terminalListButton(terminalScreen(context))))
                .until("checklist: the partial question is on screen",
                        context -> terminalScreen(context).listConfirmation() != null, SCREEN_TIMEOUT_TICKS)
                .client("checklist: check what the question says", ChecklistVisualScenario::checkListQuestion)
                .server("checklist: check that asking started nothing", this::checkNothingStarted)
                .waitTicks(SETTLE_TICKS)
                .shot("dialog")
                .client("checklist: answer the question with Yes",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.terminalConfirmButton(terminalScreen(context), true)))
                .serverUntil("checklist: the list order is running", this::orderStarted, SCREEN_TIMEOUT_TICKS)
                .until("checklist: the screen shows the running order",
                        context -> terminalScreen(context).listState().isOpen(), SCREEN_TIMEOUT_TICKS)
                .client("checklist: check that the status row still fits", ChecklistVisualScenario::checkStatusFits)
                .waitTicks(SETTLE_TICKS)
                .shot("started");
    }

    // --- 4: the full destination ------------------------------------------------------------------------------------

    /**
     * The terminal's buffer is full, so the list waits: nothing is delivered, nothing is ticked off and the order is
     * never refused — the "no 'your output cannot hold 40 stacks' refusal" of issue #19. Then the player takes the
     * items out and the list continues.
     */
    private void waitForTheFullDestination(VisualScript script) {
        long[] since = { Long.MIN_VALUE };
        script.serverUntil("checklist: watch " + BLOCKED_TICKS + " ticks of a full destination", (server, context) -> {
                    ServerLevel level = server.overworld();
                    WarehouseTerminalBlockEntity terminal = terminal(level, context);
                    TerminalListState list = terminal.listState();
                    if (!list.isOpen())
                        throw new VisualTestException("the order gave up because the destination was full: " + list);
                    if (list.entriesComplete() != 0 || list.delivered() != 0L)
                        throw new VisualTestException("the full terminal delivered " + list.delivered()
                                + " items and ticked " + list.entriesComplete() + " entries off");
                    for (ItemKey listed : LISTED_KEYS) {
                        if (terminal.bufferedItems().count(listed) > 0)
                            throw new VisualTestException("the full terminal took " + listed + " all the same");
                    }
                    long now = level.getGameTime();
                    if (since[0] == Long.MIN_VALUE)
                        since[0] = now;
                    if (now - since[0] < BLOCKED_TICKS)
                        return false;
                    census(level, "a full destination blocked the list for " + (now - since[0]) + " ticks");
                    LOGGER.info(PREFIX + "checklist: {} ticks with a full destination: nothing delivered, nothing "
                            + "ticked off, the order still {}", now - since[0], list.state());
                    return true;
                }, BLOCKED_TICKS * 4)
                .client("checklist: check that the status row still fits", ChecklistVisualScenario::checkStatusFits)
                .shot("waiting")
                .server("checklist: the player takes the items out of the terminal", this::emptyBuffer)
                .serverUntil("checklist: the terminal's buffer is empty",
                        (server, context) -> !terminal(server.overworld(), context).hasBufferedItems(),
                        SCREEN_TIMEOUT_TICKS)
                .client("checklist: close the terminal's screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("checklist: the terminal's screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    // --- 5: the crane carries the goods -----------------------------------------------------------------------------

    /**
     * The crane with the list's goods on its arm, frozen for two views. What it holds is checked on the server: a
     * picture of a loaded crane is only evidence if what it carries is really on the clipboard.
     */
    private void carryTheGoods(VisualScript script) {
        script.until("checklist: wait until the crane carries the list's goods towards the terminal",
                        ChecklistVisualScenario::carrying, MOMENT_TIMEOUT_TICKS)
                .freeze(true)
                .server("checklist: check what the crane is carrying", this::checkCarriedItems)
                .camera(BESIDE, ChecklistVisualScenario::besideView)
                .shot("carry-" + BESIDE)
                .shotFrom(ALONG_AISLE, "carry")
                .freeze(false);
    }

    // --- 6: the entries tick off, one after another -----------------------------------------------------------------

    private void tickTheEntriesOff(VisualScript script) {
        script.serverUntil("checklist: wait until the first entry is ticked off on the clipboard",
                (server, context) -> ticksMatchDeliveries(server, context, 1), DELIVERY_TIMEOUT_TICKS);
        openTerminalScreen(script);
        // The screen is pushed at most every WarehouseTerminalMenu.REFRESH_INTERVAL_TICKS ticks, so the shot waits for
        // the client to show the progress the server has already been asserted to have: a picture may not claim a
        // number the window beside it does not carry.
        shownProgress(script, 1);
        script.client("checklist: check that the status row still fits", ChecklistVisualScenario::checkStatusFits)
                .waitTicks(SETTLE_TICKS)
                .shot("ticked-first")
                .serverUntil("checklist: wait until every entry the racks cover is ticked off",
                        (server, context) -> ticksMatchDeliveries(server, context, ENTRIES_SERVED),
                        DELIVERY_TIMEOUT_TICKS)
                .server("checklist: check that the glass entry got what there was and stayed unticked",
                        this::checkPartialEntry);
        shownProgress(script, ENTRIES_SERVED);
        script.client("checklist: check that the status row still fits", ChecklistVisualScenario::checkStatusFits)
                .waitTicks(SETTLE_TICKS)
                .shot("ticked-rest");
    }

    /** Waits until the open terminal screen itself shows at least {@code complete} finished entries. */
    private void shownProgress(VisualScript script, int complete) {
        script.until("checklist: the screen shows " + complete + " of " + LISTED_ENTRIES + " entries done",
                context -> terminalScreen(context).listState().entriesComplete() >= complete, SCREEN_TIMEOUT_TICKS);
    }

    // --- 7: the missing glass is brought in -------------------------------------------------------------------------

    /**
     * The one entry the warehouse could not cover is not forgotten: the missing glass is fed into the warehouse input,
     * the crane stores it, and the still-running order fetches it and ticks the last entry off.
     */
    private void bringTheMissingGlass(VisualScript script) {
        script.server("checklist: feed the missing " + GLASS_MISSING + " glass into the warehouse input",
                        this::feedMissingGlass)
                .serverUntil("checklist: the crane stored the glass and the aisle offers it",
                        (server, context) -> controller(server.overworld(), context)
                                .availableStock(ROOF_ITEM) >= GLASS_MISSING, STORE_TIMEOUT_TICKS)
                .serverUntil("checklist: the list is done and every entry is ticked off", this::listDone,
                        DELIVERY_TIMEOUT_TICKS)
                .until("checklist: the screen shows the list done",
                        context -> !terminalScreen(context).listState().isOpen(), SCREEN_TIMEOUT_TICKS)
                .client("checklist: check that the status row still fits", ChecklistVisualScenario::checkStatusFits)
                .waitTicks(SETTLE_TICKS)
                .shot("done");
    }

    // --- 8: the receipt ---------------------------------------------------------------------------------------------

    /**
     * The finished clipboard is taken out of the slot and opened in Create's own clipboard screen: every entry ticked
     * off, with the amounts the Schematicannon wrote. That is the "order and receipt in one" of issue #19, and the one
     * shot a player could take themselves.
     */
    private void readTheReceipt(VisualScript script) {
        moveInMenu(script, "the finished checklist", "the player's own inventory", terminalListSlot(),
                hotbar(SLOT_CLIPBOARD));
        script.serverUntil("checklist: the player carries the finished checklist", this::receiptCarried,
                        SCREEN_TIMEOUT_TICKS)
                .client("checklist: close the terminal's screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("checklist: the terminal's screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
        selectHotbar(script, SLOT_CLIPBOARD, "the finished checklist", ClipboardList::isClipboard);
        rightClick(script, CLICK_GROUND);
        ArmVisualScenario.untilOrFail(script, "checklist: Create's clipboard screen is open",
                context -> context.minecraft().screen instanceof ClipboardScreen, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.client("checklist: check every tick mark the clipboard screen draws",
                        ChecklistVisualScenario::checkReceiptScreen)
                .waitTicks(SETTLE_TICKS)
                .shot("receipt")
                .client("checklist: close the clipboard screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("checklist: the clipboard screen is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    // --- build (server thread) --------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK.getX(), level.getMinBuildHeight(), DOCK.getZ());
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK.getX(), DOCK.getZ()), DOCK.getZ());
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(CENSUS_MIN_X, CENSUS_MIN_Y, CENSUS_MIN_Z),
                dock.offset(CENSUS_MAX_X, CENSUS_MAX_Y, CENSUS_MAX_Z));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(CLEAR_MIN_X, 0, CLEAR_MIN_Z),
                dock.offset(CLEAR_MAX_X, CLEAR_HEIGHT, CLEAR_MAX_Z)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL_RACK), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL_RACK.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(INPUT_RACK), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT_RACK.side()).getOpposite()));
        level.setBlockAndUpdate(dock.offset(SPILL_CHEST),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, AISLE.getOpposite()));

        // The storage wall on the side the terminal is not on, so one camera in the aisle sees both.
        Direction outward = layout.sideDirection(Side.LEFT);
        List<ItemStack> stock = new ArrayList<>(List.of(FLOOR_ITEM.toStack(FLOOR_IN_STOCK),
                ROOF_ITEM.toStack(GLASS_IN_STOCK), WALL_ITEM.toStack(WALL_IN_STOCK),
                CORE_ITEM.toStack(CORE_IN_STOCK)));
        int next = 0;
        for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
            for (int y = 0; y < STORAGE_LEVELS; y++) {
                BlockPos rack = layout.rackPos(RackPosition.of(x, y, Side.LEFT));
                BlockPos chest = rack.relative(outward);
                level.setBlockAndUpdate(chest,
                        Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                        .setValue(WarehouseInterfaceBlock.FACING, outward));
                if (next < stock.size())
                    insert(level, chest, stock.get(next++));
            }
        }
        if (next < stock.size())
            throw new VisualTestException("only " + next + " of " + stock.size() + " item types fit into the racks");
    }

    /** The hut the checklist is for: a cobblestone floor, oak plank walls, an iron block core and a glass roof. */
    private static void buildSite(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos site = context.origin().offset(SITE);
        int last = SITE_SIZE - 1;
        for (int x = 0; x < SITE_SIZE; x++) {
            for (int z = 0; z < SITE_SIZE; z++) {
                boolean edge = x == 0 || z == 0 || x == last || z == last;
                boolean centre = x == SITE_SIZE / 2 && z == SITE_SIZE / 2;
                level.setBlockAndUpdate(site.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState());
                if (edge)
                    level.setBlockAndUpdate(site.offset(x, 1, z), Blocks.OAK_PLANKS.defaultBlockState());
                else if (centre)
                    level.setBlockAndUpdate(site.offset(x, 1, z), Blocks.IRON_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(site.offset(x, SITE_HEIGHT - 1, z), Blocks.GLASS.defaultBlockState());
            }
        }
    }

    /**
     * Saves the hut through Create's own exporter into the directory a player's <b>upload</b> leaves it in
     * ({@code schematics/uploaded/<player>}, {@code ServerSchematicLoader}) and into the client's own schematic folder,
     * hands the player the schematic item that points at it, deploys it at the site and clears the site again — so
     * every block of it is missing and the checklist is the whole hut.
     * <p>
     * Writing the file is the one step of this story that is not a click: a schematic reaches a server by an upload
     * over Create's own channel, and no action in the world can produce the file. Everything the checklist is made of
     * afterwards is Create's own code — {@code SchematicPrinter#markAllBlockRequirements} walks the deployed schematic
     * against the world, {@code MaterialChecklist#createWrittenClipboard} writes it.
     */
    private void saveSchematic(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ServerPlayer player = context.serverPlayer(server);
        String owner = player.getGameProfile().getName();
        BlockPos site = context.origin().offset(SITE);
        BlockPos far = site.offset(SITE_SIZE - 1, SITE_HEIGHT - 1, SITE_SIZE - 1);
        for (Path directory : List.of(CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(owner), CreatePaths.SCHEMATICS_DIR)) {
            SchematicExport.SchematicExportResult result = SchematicExport.saveSchematic(directory, SCHEMATIC_FILE,
                    true, level, site, far);
            if (result == null)
                throw new VisualTestException("the hut could not be saved as a schematic in " + directory);
            LOGGER.info(PREFIX + "checklist: saved the hut as {} (bounds {})", result.file(), result.bounds());
        }
        ItemStack schematic = SchematicItem.create(level, SCHEMATIC_FILE, owner);
        schematic.set(AllDataComponents.SCHEMATIC_ANCHOR, site);
        schematic.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        player.getInventory().setItem(SLOT_SCHEMATIC, schematic);
        for (BlockPos pos : BlockPos.betweenClosed(site, far))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    private static void placeCannon(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        level.setBlockAndUpdate(context.origin().offset(CANNON), AllBlocks.SCHEMATICANNON.getDefaultState());
        LOGGER.info(PREFIX + "checklist: the Schematicannon starts in state {}", cannon(level, context).state);
    }

    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().setItem(SLOT_CLIPBOARD, AllBlocks.CLIPBOARD.asStack());
        player.getInventory().setItem(SLOT_EMPTY, ItemStack.EMPTY);
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        int storage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == storage
                && controller.inputStations().size() == 1 && controller.outputStations().size() == 1
                && crane.isControllerLinked() && crane.aisleLength() == RAILS
                && controller.countOf(FLOOR_ITEM) == FLOOR_IN_STOCK && controller.countOf(ROOF_ITEM) == GLASS_IN_STOCK
                && controller.countOf(WALL_ITEM) == WALL_IN_STOCK && controller.countOf(CORE_ITEM) == CORE_IN_STOCK;
    }

    // --- the cannon (server thread) ---------------------------------------------------------------------------------

    /** Whether the cannon has read the deployed schematic and measured the hut's material, entry by entry. */
    private boolean checklistMeasured(MinecraftServer server, VisualContext context) {
        SchematicannonBlockEntity cannon = cannon(server.overworld(), context);
        if (cannon.printer.isErrored())
            throw new VisualTestException("the Schematicannon could not read the schematic: " + cannon.statusMsg);
        if (!cannon.printer.isLoaded())
            return false;
        MaterialChecklist checklist = cannon.checklist;
        if (checklist.required.isEmpty())
            return false;
        checkRequirement(checklist, Items.COBBLESTONE, FLOOR_BLOCKS);
        checkRequirement(checklist, Items.GLASS, ROOF_BLOCKS);
        checkRequirement(checklist, Items.OAK_PLANKS, WALL_BLOCKS);
        checkRequirement(checklist, Items.IRON_BLOCK, CORE_BLOCKS);
        if (checklist.required.size() != LISTED_ENTRIES)
            throw new VisualTestException("the checklist names " + checklist.required.size() + " item types, expected "
                    + LISTED_ENTRIES + ": " + checklist.required);
        if (checklist.blocksNotLoaded)
            throw new VisualTestException("the Schematicannon could not see the whole build site");
        LOGGER.info(PREFIX + "checklist: the cannon measured {} ({} blocks to place, status {})", checklist.required,
                cannon.blocksToPlace, cannon.statusMsg);
        return true;
    }

    private static void checkRequirement(MaterialChecklist checklist, Item item, int expectedAmount) {
        int required = checklist.getRequiredAmount(item);
        if (required != expectedAmount)
            throw new VisualTestException("the checklist asks for " + required + " " + item + ", expected "
                    + expectedAmount);
    }

    /** Whether the cannon's paper printer has written the whole checklist onto the clipboard in its output slot. */
    private boolean checklistPrinted(MinecraftServer server, VisualContext context) {
        ItemStack printed = cannon(server.overworld(), context).inventory.getStackInSlot(CANNON_SLOT_CHECKLIST);
        return ClipboardList.isClipboard(printed) && byItem(ClipboardList.read(printed)).size() >= LISTED_ENTRIES;
    }

    /**
     * Fails the run unless the clipboard the cannon printed really is the hut's checklist: one entry per item type,
     * the hut's own amounts, nothing ticked off yet, and the read-only, written shape
     * {@code MaterialChecklist#createWrittenClipboard} produces — the very shape the GameTests of this feature are
     * written against.
     */
    private void checkPrintedChecklist(MinecraftServer server, VisualContext context) {
        ItemStack printed = cannon(server.overworld(), context).inventory.getStackInSlot(CANNON_SLOT_CHECKLIST);
        ClipboardContent content = printed.get(AllDataComponents.CLIPBOARD_CONTENT);
        if (content == null)
            throw new VisualTestException("the printed clipboard carries no clipboard content at all");
        if (content.type() != ClipboardType.WRITTEN || !content.readOnly())
            throw new VisualTestException("the Schematicannon's checklist is " + content.type() + ", read-only "
                    + content.readOnly() + "; expected a written, read-only clipboard");
        List<ListEntry<ItemKey>> entries = ClipboardList.read(printed);
        Map<ItemKey, ListEntry<ItemKey>> byItem = byItem(entries);
        checkEntry(byItem, FLOOR_ITEM, FLOOR_BLOCKS);
        checkEntry(byItem, ROOF_ITEM, ROOF_BLOCKS);
        checkEntry(byItem, WALL_ITEM, WALL_BLOCKS);
        checkEntry(byItem, CORE_ITEM, CORE_BLOCKS);
        if (byItem.size() != LISTED_ENTRIES)
            throw new VisualTestException("the printed checklist has " + byItem.size() + " item entries, expected "
                    + LISTED_ENTRIES + ": " + describe(entries));
        LOGGER.info(PREFIX + "checklist: the cannon printed a written, read-only checklist of {} entries: {}",
                byItem.size(), describe(entries));
    }

    private static void checkEntry(Map<ItemKey, ListEntry<ItemKey>> byItem, ItemKey key, int amount) {
        ListEntry<ItemKey> entry = byItem.get(key);
        if (entry == null)
            throw new VisualTestException("the printed checklist has no entry for " + key + ": " + byItem.keySet());
        if (entry.amount() != amount)
            throw new VisualTestException("the checklist's " + key + " entry asks for " + entry.amount()
                    + ", expected " + amount);
        if (entry.checked())
            throw new VisualTestException("the checklist's " + key + " entry is ticked off before anything was fetched");
        if (!entry.orderable())
            throw new VisualTestException("the checklist's " + key + " entry would not be ordered: " + entry);
    }

    // --- the list order (server thread) -----------------------------------------------------------------------------

    /** Fills every buffer slot of the terminal, i.e. leaves the destination of the whole list with no room at all. */
    private void fillBuffer(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context);
        long filler = 0L;
        for (int slot = 0; slot < terminal.bufferSlots(); slot++) {
            ItemStack rest = terminal.insert(FILLER_ITEM.toStack(FILLER_STACK), false);
            filler += FILLER_STACK - rest.getCount();
        }
        if (terminal.insert(FLOOR_ITEM.toStack(1), true).isEmpty())
            throw new VisualTestException("the terminal still has room for the list, so the destination is not full");
        expected = SceneItemCensus.plus(expected, FILLER_ITEM, filler);
        census(level, "the terminal's buffer was filled with " + filler + " " + FILLER_ITEM);
        LOGGER.info(PREFIX + "checklist: the terminal holds {} {} in all {} buffer slots", filler, FILLER_ITEM,
                terminal.bufferSlots());
    }

    /** The player takes everything out of the terminal by hand and puts it into the chest beside the controller. */
    private void emptyBuffer(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context);
        IItemHandler out = terminal.externalHandler();
        for (int slot = 0; slot < out.getSlots(); slot++) {
            ItemStack taken = out.extractItem(slot, FILLER_STACK, false);
            if (taken.isEmpty())
                continue;
            ItemStack rest = ItemHandlerHelper.insertItem(handler(level, context.origin().offset(SPILL_CHEST)), taken,
                    false);
            if (!rest.isEmpty())
                throw new VisualTestException("the chest beside the controller refused " + rest);
        }
        census(level, "the player emptied the terminal into the chest");
    }

    /** Feeds the glass the warehouse was short of into the warehouse input, the way a funnel or a player would. */
    private void feedMissingGlass(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos input = layout(context.origin()).rackPos(INPUT_RACK);
        ItemStack rest = ItemHandlerHelper.insertItem(handler(level, input), ROOF_ITEM.toStack(GLASS_MISSING), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the warehouse input refused " + rest);
        expected = SceneItemCensus.plus(expected, ROOF_ITEM, GLASS_MISSING);
        census(level, "the missing glass was fed into the warehouse input");
        LOGGER.info(PREFIX + "checklist: fed {} {} into the warehouse input", GLASS_MISSING, ROOF_ITEM);
    }

    /** Fails the run unless the Yes started exactly the order the clipboard describes, and nothing more. */
    private boolean orderStarted(MinecraftServer server, VisualContext context) {
        TerminalListState list = terminal(server.overworld(), context).listState();
        if (!list.active())
            return false;
        if (list.entries() != LISTED_ENTRIES)
            throw new VisualTestException("the order took " + list.entries() + " entries, expected " + LISTED_ENTRIES);
        if (list.wanted() != LISTED_ITEMS)
            throw new VisualTestException("the order wants " + list.wanted() + " items, expected " + LISTED_ITEMS);
        if (list.entriesComplete() != 0)
            throw new VisualTestException("nothing can be delivered yet, but " + list.entriesComplete()
                    + " entries are complete");
        if (list.truncated())
            throw new VisualTestException("the order left " + list.dropped() + " entries on the clipboard");
        LOGGER.info(PREFIX + "checklist: the Yes started the order: {} entries, {} items, state {}", list.entries(),
                list.wanted(), list.state());
        return true;
    }

    /**
     * Whether at least {@code atLeast} entries are delivered in full, with the one assertion that makes a shot of tick
     * marks evidence: <b>every ticked entry was delivered in full and every unticked one was not</b>, and the number of
     * tick marks on the clipboard is the number of complete entries the order reports.
     */
    private boolean ticksMatchDeliveries(MinecraftServer server, VisualContext context, int atLeast) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context);
        TerminalListState list = terminal.listState();
        if (!list.active())
            throw new VisualTestException("the clipboard order is gone");
        Map<ItemKey, ListEntry<ItemKey>> byItem = byItem(ClipboardList.read(terminal.listClipboard()));
        int ticked = 0;
        for (Map.Entry<ItemKey, ListEntry<ItemKey>> entry : byItem.entrySet()) {
            long delivered = terminal.bufferedItems().count(entry.getKey());
            if (!entry.getValue().checked())
                continue;
            ticked++;
            if (delivered < entry.getValue().amount())
                throw new VisualTestException("the entry for " + entry.getKey() + " is ticked off, but only "
                        + delivered + " of " + entry.getValue().amount() + " arrived in the terminal");
        }
        if (ticked != list.entriesComplete())
            throw new VisualTestException("the clipboard carries " + ticked + " tick marks while the order reports "
                    + list.entriesComplete() + " complete entries");
        if (ticked < atLeast)
            return false;
        census(level, ticked + " of " + list.entries() + " entries ticked off");
        LOGGER.info(PREFIX + "checklist: {} of {} entries are ticked off; the terminal holds {}; the clipboard reads {}",
                ticked, list.entries(), describeBuffer(terminal),
                describe(ClipboardList.read(terminal.listClipboard())));
        return true;
    }

    /**
     * Fails the run unless the one entry the warehouse fell short of got exactly what there was and stayed unticked —
     * the half of the partial case a tick mark could never say ({@code ListLine}: the line is the truth, the tick mark
     * is the receipt).
     */
    private void checkPartialEntry(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context);
        ListEntry<ItemKey> glass = byItem(ClipboardList.read(terminal.listClipboard())).get(ROOF_ITEM);
        if (glass == null)
            throw new VisualTestException("the clipboard lost its glass entry");
        if (glass.checked())
            throw new VisualTestException("the glass entry is ticked off although the warehouse was short of it");
        long delivered = terminal.bufferedItems().count(ROOF_ITEM);
        if (delivered != GLASS_IN_STOCK)
            throw new VisualTestException("the glass entry got " + delivered + " of " + ROOF_BLOCKS + ", expected the "
                    + GLASS_IN_STOCK + " the warehouse held");
        TerminalListState list = terminal.listState();
        if (list.outstanding() != GLASS_MISSING)
            throw new VisualTestException("the order is still missing " + list.outstanding() + " items, expected "
                    + GLASS_MISSING);
        if (!list.isOpen())
            throw new VisualTestException("the order gave up on the glass instead of waiting: " + list);
        LOGGER.info(PREFIX + "checklist: the glass entry got the {} there were and stays unticked, {} outstanding, "
                + "state {}", delivered, list.outstanding(), list.state());
    }

    /** Whether the order is done, every entry is ticked off and the terminal really holds the whole list. */
    private boolean listDone(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseTerminalBlockEntity terminal = terminal(level, context);
        TerminalListState list = terminal.listState();
        if (list.isOpen())
            return false;
        if (list.state() != ListOrderState.DONE)
            throw new VisualTestException("the order ended as " + list.state() + ", expected DONE");
        if (list.entriesComplete() != LISTED_ENTRIES || list.delivered() != LISTED_ITEMS)
            throw new VisualTestException("the order delivered " + list.delivered() + " items in "
                    + list.entriesComplete() + " entries, expected " + LISTED_ITEMS + " in " + LISTED_ENTRIES);
        List<ListEntry<ItemKey>> entries = ClipboardList.read(terminal.listClipboard());
        assertEveryEntryTicked(entries, "the finished list");
        for (Map.Entry<ItemKey, ListEntry<ItemKey>> entry : byItem(entries).entrySet()) {
            long delivered = terminal.bufferedItems().count(entry.getKey());
            if (delivered < entry.getValue().amount())
                throw new VisualTestException("the finished list delivered only " + delivered + " of "
                        + entry.getValue().amount() + " " + entry.getKey());
        }
        census(level, "the whole list was delivered");
        LOGGER.info(PREFIX + "checklist: the list is done, {} entries ticked off, the terminal holds {}",
                list.entriesComplete(), describeBuffer(terminal));
        return true;
    }

    /** Whether the player really carries the finished receipt and the list slot is empty again. */
    private boolean receiptCarried(MinecraftServer server, VisualContext context) {
        ItemStack carried = context.serverPlayer(server).getInventory().getItem(SLOT_CLIPBOARD);
        if (!ClipboardList.isClipboard(carried))
            return false;
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context);
        if (ClipboardList.isClipboard(terminal.listClipboard()))
            throw new VisualTestException("the clipboard is still in the terminal's list slot");
        List<ListEntry<ItemKey>> entries = ClipboardList.read(carried);
        assertEveryEntryTicked(entries, "the checklist the player carries away");
        LOGGER.info(PREFIX + "checklist: the player carries the finished receipt: {}", describe(entries));
        return true;
    }

    private static void assertEveryEntryTicked(List<ListEntry<ItemKey>> entries, String what) {
        for (ListEntry<ItemKey> entry : entries) {
            if (entry.key().isPresent() && !entry.checked())
                throw new VisualTestException(what + " still has an unticked entry: " + entry);
        }
    }

    /** Fails the run unless nothing at all was started while the question about the list is up. */
    private void checkNothingStarted(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (terminal(level, context).hasListOrder())
            throw new VisualTestException("asking about the list started an order");
        WarehouseControllerBlockEntity controller = controller(level, context);
        if (!controller.openRequests().isEmpty())
            throw new VisualTestException("asking about the list made " + controller.openRequests().size()
                    + " requests");
        if (!controller.openProductionOrders().isEmpty())
            throw new VisualTestException("asking about the list started a production order");
        for (ItemKey key : LISTED_KEYS) {
            long available = controller.availableStock(key);
            long held = controller.countOf(key);
            if (available != held)
                throw new VisualTestException("asking about the list promised " + key + " away: " + available + " of "
                        + held + " left");
        }
        LOGGER.info(PREFIX + "checklist: the question started nothing: no order, no request, nothing promised");
    }

    /** Fails the run unless the crane really carries what the clipboard asked for. */
    private void checkCarriedItems(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(server.overworld(),
                context.origin());
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        Map<ItemKey, Long> held = new LinkedHashMap<>();
        for (HeldItems.Entry entry : crane.heldItems().entries())
            held.merge(entry.key(), (long) entry.count(), Long::sum);
        if (held.isEmpty())
            throw new VisualTestException("the crane carries nothing");
        for (ItemKey key : held.keySet()) {
            if (!LISTED_KEYS.contains(key))
                throw new VisualTestException("the crane carries " + key + ", which is not on the clipboard");
        }
        census(server.overworld(), "the crane carries the list's goods");
        LOGGER.info(PREFIX + "checklist: the crane carries {} towards the terminal", held);
    }

    /** Fails the run unless every item of the aisle is still accounted for, and logs the census it checked. */
    private void census(ServerLevel level, String step) {
        Map<ItemKey, Long> actual = SceneItemCensus.take(level, censusBox);
        if (!actual.equals(expected))
            throw new VisualTestException("item conservation violated, " + step + ": expected "
                    + SceneItemCensus.describe(expected) + " but found " + SceneItemCensus.describe(actual));
        LOGGER.info(PREFIX + "checklist: census PASS: {} (items {})", step, SceneItemCensus.describe(actual));
    }

    // --- the screens (client thread) --------------------------------------------------------------------------------

    /**
     * Fails the run unless the <b>empty</b> list slot explains itself, which is the one place the whole feature is put
     * into words for a player ({@code gui.terminal.list.slot}). A tooltip only exists while a mouse rests on the slot,
     * so the run reads it rather than only photographing it.
     */
    private static void checkEmptyListSlotTooltip(VisualContext context) {
        WarehouseTerminalScreen screen = terminalScreen(context);
        ItemStack inSlot = screen.getMenu().slots.get(listSlotIndex(screen)).getItem();
        if (!inSlot.isEmpty())
            throw new VisualTestException("the list slot is not empty any more: " + inSlot);
        List<String> tooltip = screen.listSlotTooltip(inSlot).stream().map(Component::getString).toList();
        String explanation = WareworksLang.translateDirect(WareworksLang.TERMINAL_LIST_SLOT).getString();
        if (tooltip.stream().noneMatch(line -> line.contains(explanation)))
            throw new VisualTestException("the empty list slot does not say what it is for: " + tooltip);
        LOGGER.info(PREFIX + "checklist: the empty list slot says {}", tooltip);
    }

    /** Fails the run unless the list slot shows the printed checklist, with the name the Schematicannon gave it. */
    private static void checkListSlotTooltip(VisualContext context) {
        WarehouseTerminalScreen screen = terminalScreen(context);
        ItemStack inSlot = screen.getMenu().slots.get(listSlotIndex(screen)).getItem();
        if (!ClipboardList.isClipboard(inSlot))
            throw new VisualTestException("the list slot does not show the clipboard: " + inSlot);
        List<String> tooltip = screen.listSlotTooltip(inSlot).stream().map(Component::getString).toList();
        if (tooltip.isEmpty())
            throw new VisualTestException("the list slot says nothing at all");
        LOGGER.info(PREFIX + "checklist: the list slot says {}", tooltip);
    }

    /**
     * Fails the run unless the dialog is the partial question of issue #19, both in the numbers behind it and in the
     * lines it draws: what the list wants, what the racks can give, what is missing, and the entry it is about.
     */
    private static void checkListQuestion(VisualContext context) {
        WarehouseTerminalScreen screen = terminalScreen(context);
        ListOrderConfirmation<ItemKey> question = screen.listConfirmation();
        if (question == null)
            throw new VisualTestException("the terminal is not asking about the list");
        if (question.wanted() != LISTED_ITEMS || question.serveable() != SERVEABLE_ITEMS
                || question.missing() != GLASS_MISSING || question.producing() != 0L)
            throw new VisualTestException("the question says wanted=" + question.wanted() + " serveable="
                    + question.serveable() + " missing=" + question.missing() + " producing=" + question.producing()
                    + ", expected " + LISTED_ITEMS + "/" + SERVEABLE_ITEMS + "/" + GLASS_MISSING + "/0");
        if (question.entriesServed() != ENTRIES_SERVED || question.entriesShort() != 1
                || question.entriesProducing() != 0 || question.entriesImpossible() != 0)
            throw new VisualTestException("the question counts served=" + question.entriesServed() + " short="
                    + question.entriesShort() + " producing=" + question.entriesProducing() + " impossible="
                    + question.entriesImpossible() + ", expected " + ENTRIES_SERVED + "/1/0/0");
        ListOrderConfirmation.Line<ItemKey> named = question.named().stream()
                .filter(line -> line.key().equals(ROOF_ITEM)).findFirst()
                .orElseThrow(() -> new VisualTestException("the question does not name the glass entry: "
                        + question.named()));
        if (named.wanted() != ROOF_BLOCKS || named.serveable() != GLASS_IN_STOCK || named.missing() != GLASS_MISSING)
            throw new VisualTestException("the named glass entry reads " + named + ", expected " + ROOF_BLOCKS + "/"
                    + GLASS_IN_STOCK + "/" + GLASS_MISSING);
        // What a player really reads: the panel's own lines, which no screenshot can be trusted to prove.
        List<String> lines = ScreenInput.terminalListConfirmationLines(screen).stream().map(Component::getString)
                .toList();
        String text = String.join(" | ", lines);
        for (String number : List.of(String.valueOf(GLASS_MISSING), String.valueOf(LISTED_ITEMS)))
            if (!text.contains(number))
                throw new VisualTestException("the panel does not name " + number + ": " + text);
        if (!text.contains(ROOF_ITEM.toStack().getHoverName().getString()))
            throw new VisualTestException("the panel does not name the item it is about: " + text);
        LOGGER.info(PREFIX + "checklist: the dialog reads {}", lines);
    }

    /**
     * Fails the run unless the panel a <b>single portion</b> of a list order raises really says something (M23 review
     * fix).
     * <p>
     * This warehouse has no production patterns, so no portion of its list can raise that question — but the panel is
     * client code, and the one shape it used to draw <b>empty</b> is precisely the one only a portion can have: a
     * request that crosses no reserve and no maximum and whose only cost is that machines would be started for it
     * ({@code RequestConfirmation#required(RequestScope.LIST)}). So the question the server would send is handed to the
     * screen's own packet handler here, and what it then draws is read back: the item, the amount, what would be made,
     * and <b>no</b> Alt hint, because there is no click to hold Alt on when the order asked. The server side of the same
     * case is {@code TerminalListGameTests#terminalListPortionQuestionIsShownAgainAndCanBeRefused}.
     */
    private static void checkPortionQuestionPanel(VisualContext context) {
        WarehouseTerminalScreen screen = terminalScreen(context);
        RequestConfirmation<ItemKey> question = new RequestConfirmation<>(ROOF_ITEM, PORTION_WANTED, 0L, 0L, 0L,
                PORTION_MADE, StockRule.UNSET, List.of());
        if (!question.required(RequestScope.LIST))
            throw new VisualTestException("this question would not be asked about at all: " + question);
        if (question.required())
            throw new VisualTestException("a click would never be asked about it, which is the point: " + question);
        screen.onConfirm(new TerminalConfirmPayload(screen.getMenu().containerId, question, RequestScope.LIST));
        if (!screen.isAskingSomething())
            throw new VisualTestException("the screen did not put the portion's question up");
        List<String> lines = ScreenInput.terminalConfirmationLines(screen).stream().map(Component::getString).toList();
        String text = String.join(" | ", lines);
        for (String named : List.of(ROOF_ITEM.toStack().getHoverName().getString(), String.valueOf(PORTION_WANTED),
                String.valueOf(PORTION_MADE)))
            if (!text.contains(named))
                throw new VisualTestException("the portion's panel does not name " + named + ": " + text);
        String skip = WareworksLang.translateDirect(WareworksLang.TERMINAL_CONFIRM_SKIP).getString();
        if (text.contains(skip))
            throw new VisualTestException("the portion's panel offers the Alt skip, which cannot answer it: " + text);
        LOGGER.info(PREFIX + "checklist: a portion's question reads {}", lines);
    }

    /**
     * Takes the portion's panel down again without telling the server anything: the question was never the server's, so
     * the Cancel that would park a real order must not be sent. Overwriting it with a {@code CLICK} question first is
     * what makes the dismissal a plain client one.
     */
    private static void dropPortionQuestionPanel(VisualContext context) {
        WarehouseTerminalScreen screen = terminalScreen(context);
        screen.onConfirm(new TerminalConfirmPayload(screen.getMenu().containerId,
                RequestConfirmation.none(ROOF_ITEM, PORTION_WANTED), RequestScope.CLICK));
        screen.cancelConfirmation();
    }

    /** Fails the run when a text of the two rows below the buffer does not fit its row (the M7/M23 status row). */
    private static void checkStatusFits(VisualContext context) {
        WarehouseTerminalScreen screen = terminalScreen(context);
        if (!screen.statusTextsFit())
            throw new VisualTestException("the terminal's status texts do not fit their rows: '"
                    + screen.shownStatusLine().getString() + "'");
        LOGGER.info(PREFIX + "checklist: the status row reads '{}'", screen.shownStatusLine().getString());
    }

    /**
     * Fails the run unless Create's own clipboard screen draws the receipt: every entry of the checklist ticked off,
     * with the hut's amounts. It is read from the screen's own content, so the shot beside it shows what it asserts.
     */
    private static void checkReceiptScreen(VisualContext context) {
        if (!(context.minecraft().screen instanceof ClipboardScreen clipboard))
            throw new VisualTestException("Create's clipboard screen is not open: " + context.minecraft().screen);
        List<ListEntry<ItemKey>> entries = ClipboardList.read(stackOf(clipboard.content));
        Map<ItemKey, ListEntry<ItemKey>> byItem = byItem(entries);
        if (byItem.size() != LISTED_ENTRIES)
            throw new VisualTestException("the clipboard screen shows " + byItem.size() + " item entries, expected "
                    + LISTED_ENTRIES + ": " + describe(entries));
        Map<ItemKey, Integer> wanted = new LinkedHashMap<>();
        wanted.put(FLOOR_ITEM, FLOOR_BLOCKS);
        wanted.put(ROOF_ITEM, ROOF_BLOCKS);
        wanted.put(WALL_ITEM, WALL_BLOCKS);
        wanted.put(CORE_ITEM, CORE_BLOCKS);
        for (Map.Entry<ItemKey, Integer> expectedEntry : wanted.entrySet()) {
            ListEntry<ItemKey> entry = byItem.get(expectedEntry.getKey());
            if (entry == null || entry.amount() != expectedEntry.getValue())
                throw new VisualTestException("the clipboard screen shows " + entry + " for " + expectedEntry.getKey()
                        + ", expected " + expectedEntry.getValue());
            if (!entry.checked())
                throw new VisualTestException("the clipboard screen draws no tick mark for " + expectedEntry.getKey());
        }
        LOGGER.info(PREFIX + "checklist: Create's clipboard screen shows the finished receipt: {}", describe(entries));
    }

    /** A stack carrying {@code content}, so a clipboard screen's own pages can be read with {@link ClipboardList}. */
    private static ItemStack stackOf(@Nullable ClipboardContent content) {
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        if (content != null)
            stack.set(AllDataComponents.CLIPBOARD_CONTENT, content);
        return stack;
    }

    // --- input (client thread) --------------------------------------------------------------------------------------

    /**
     * A right-click on {@code click}: the camera moves there, the crosshair is checked to be on the intended face, and
     * the use key is pressed once the way the mouse handler does ({@link ArmVisualScenario}).
     */
    private void rightClick(VisualScript script, ArmVisualScenario.Click click) {
        script.camera(click.label(), context -> ArmVisualScenario.clickView(context, click));
        ArmVisualScenario.untilOrFail(script, "checklist: the crosshair is on the " + click.face() + " face of the "
                        + click.label(), context -> ArmVisualScenario.aimedAt(context, click), AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context) + " instead of the "
                        + click.face() + " face of " + click.block());
        script.client("checklist: right-click " + click.label(),
                context -> KeyMapping.click(context.minecraft().options.keyUse.getKey()));
        ArmVisualScenario.untilOrFail(script, "checklist: the client handled the right-click on " + click.label(),
                context -> ArmVisualScenario.clickCount(context.minecraft().options.keyUse) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
        script.waitTicks(SETTLE_TICKS);
    }

    /**
     * Carries a stack from one place in the open screen to another with two real left clicks — pick it up, put it
     * down — which is how a player moves an item when no shift-click is available to the harness
     * ({@link ScreenInput#requireNoModifiers}).
     */
    private void moveInMenu(VisualScript script, String what, String where, Place from, Place to) {
        script.client("checklist: pick " + what + " up",
                        context -> ScreenInput.click(context.minecraft(), from.point(containerScreen(context))))
                .until("checklist: the cursor carries " + what,
                        context -> !containerScreen(context).getMenu().getCarried().isEmpty(), CLICK_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("checklist: put " + what + " into " + where,
                        context -> ScreenInput.click(context.minecraft(), to.point(containerScreen(context))))
                .until("checklist: the cursor is empty again after putting " + what + " into " + where,
                        context -> containerScreen(context).getMenu().getCarried().isEmpty(), CLICK_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    /** Moves the mouse onto an empty slot, where no tooltip is drawn, so a shot shows the window and nothing else. */
    private void hoverNothing(VisualScript script, String why) {
        script.client("checklist: move the mouse onto an empty slot " + why,
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.hotbarSlot(containerScreen(context), SLOT_EMPTY)))
                .waitTicks(SETTLE_TICKS);
    }

    /** A clickable place in the open container screen, resolved when the click is sent. */
    private record Place(Function<AbstractContainerScreen<?>, ScreenInput.Point> lookup) {
        private Place {
            Objects.requireNonNull(lookup, "lookup");
        }

        ScreenInput.Point point(AbstractContainerScreen<?> screen) {
            return lookup.apply(screen);
        }
    }

    private static Place menuSlot(int index) {
        return new Place(screen -> ScreenInput.menuSlot(screen, index));
    }

    private static Place hotbar(int hotbarIndex) {
        return new Place(screen -> ScreenInput.hotbarSlot(screen, hotbarIndex));
    }

    /** The terminal's list slot, whose index depends on how many buffer slots the terminal has. */
    private static Place terminalListSlot() {
        return new Place(screen -> {
            if (!(screen.getMenu() instanceof WarehouseTerminalMenu menu))
                throw new VisualTestException("the open screen is not a warehouse terminal: " + screen);
            return ScreenInput.menuSlot(screen, menu.listSlotIndex());
        });
    }

    /** Opens the terminal's screen with a right-click with an empty hand and waits for its stock list. */
    private void openTerminalScreen(VisualScript script) {
        takeAnEmptyHand(script);
        rightClick(script, CLICK_TERMINAL);
        ArmVisualScenario.untilOrFail(script, "checklist: the terminal's screen is open with its stock list",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen screen && screen.hasStock(),
                SCREEN_TIMEOUT_TICKS, context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
    }

    /**
     * Whether Create's own schematic handler has the deployed hut in hand and is drawing its ghost: the client reads
     * the file from its own {@code schematics} folder, which is where {@link #saveSchematic} also wrote it.
     */
    private static boolean schematicGhostShown(VisualContext context) {
        if (context.minecraft().player == null
                || !context.minecraft().player.getMainHandItem().has(AllDataComponents.SCHEMATIC_FILE))
            return false;
        return CreateClient.SCHEMATIC_HANDLER.isActive() && CreateClient.SCHEMATIC_HANDLER.isDeployed();
    }

    /**
     * The player takes hotbar slot {@code slot} in hand, which is what a hotbar key does, and the run waits until the
     * client really holds it — a camera shot or a right-click taken before that would be about the item before.
     */
    private void selectHotbar(VisualScript script, int slot, String what, Predicate<ItemStack> held) {
        script.server("checklist: take " + what + " in hand", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    player.getInventory().selected = slot;
                    player.connection.send(new ClientboundSetCarriedItemPacket(slot));
                })
                .until("checklist: the client holds " + what, context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && player.getInventory().selected == slot
                            && held.test(player.getMainHandItem());
                }, HELD_ITEM_TIMEOUT_TICKS);
    }

    /** An empty hand, which is what lets a right-click open a block's own screen instead of using the held item. */
    private void takeAnEmptyHand(VisualScript script) {
        selectHotbar(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
    }

    private static boolean cannonScreenOpen(VisualContext context) {
        return context.minecraft().screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() instanceof SchematicannonMenu;
    }

    private static AbstractContainerScreen<?> containerScreen(VisualContext context) {
        if (context.minecraft().screen instanceof AbstractContainerScreen<?> screen)
            return screen;
        throw new VisualTestException("no container screen is open: " + context.minecraft().screen);
    }

    private static WarehouseTerminalScreen terminalScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen screen)
            return screen;
        throw new VisualTestException("the terminal screen is not open: " + context.minecraft().screen);
    }

    private static int listSlotIndex(WarehouseTerminalScreen screen) {
        return screen.getMenu().listSlotIndex();
    }

    // --- the crane moment (client thread) ---------------------------------------------------------------------------

    private static boolean carrying(VisualContext context) {
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                && crane.goggleInfo().heldCount() >= CARRY_SHOT_ITEMS
                && remainingTravel(crane) > MIN_REMAINING_TRAVEL).isPresent();
    }

    private static double remainingTravel(StackerCraneBlockEntity crane) {
        return Math.max(Math.abs(crane.craneState().target().x() - crane.craneState().pose().x()),
                Math.abs(crane.craneState().target().y() - crane.craneState().pose().y()));
    }

    /**
     * Beside the crane, on the side the rack wall is <b>not</b> on, looking across the aisle at the carriage: the one
     * view in which the items on it are seen in profile instead of behind the mast.
     */
    private static CameraView besideView(VisualContext context) {
        CranePose pose = clientCrane(context).map(crane -> crane.craneState().pose())
                .orElseThrow(() -> new VisualTestException("no client crane to follow"));
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        double craneZ = BLOCK_CENTER + AISLE.getStepZ() * pose.x();
        Direction open = AISLE.getClockWise(); // the storage wall is on Side.LEFT, so the right side stays open
        return CameraView.of(BESIDE, craneX + open.getStepX() * BESIDE_DISTANCE, pose.y() + BESIDE_ABOVE,
                craneZ + open.getStepZ() * BESIDE_DISTANCE, craneX, pose.y() + CARRIAGE_LOOK_HEIGHT, craneZ);
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    // --- small helpers ----------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(context.origin()).rackPos(TERMINAL_RACK));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                context.origin().offset(CONTROLLER));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private static SchematicannonBlockEntity cannon(ServerLevel level, VisualContext context) {
        SchematicannonBlockEntity cannon = AllBlockEntityTypes.SCHEMATICANNON.getNullable(level,
                context.origin().offset(CANNON));
        if (cannon == null)
            throw new VisualTestException("the Schematicannon is missing");
        return cannon;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static IItemHandler handler(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("there is no item handler at " + pos);
        return handler;
    }

    private static void insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler(level, pos), stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    /** The entries of a clipboard by their item, so no check depends on the order the checklist was sorted in. */
    private static Map<ItemKey, ListEntry<ItemKey>> byItem(List<ListEntry<ItemKey>> entries) {
        Map<ItemKey, ListEntry<ItemKey>> byItem = new LinkedHashMap<>();
        for (ListEntry<ItemKey> entry : entries)
            entry.key().ifPresent(key -> byItem.put(key, entry));
        return byItem;
    }

    private static String describe(List<ListEntry<ItemKey>> entries) {
        List<String> described = new ArrayList<>(entries.size());
        for (ListEntry<ItemKey> entry : entries)
            described.add(entry.key().map(Object::toString).orElse(">>>") + " x" + entry.amount()
                    + (entry.checked() ? " [ticked]" : ""));
        return described.toString();
    }

    private static String describeBuffer(WarehouseTerminalBlockEntity terminal) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ItemKey key : terminal.bufferedItems().keys())
            counts.put(key.toString(), terminal.bufferedItems().count(key));
        return counts.toString();
    }
}
