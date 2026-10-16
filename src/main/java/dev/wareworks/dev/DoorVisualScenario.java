package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageHandover;
import dev.wareworks.content.station.PackageUnpackSummary;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.port.BoxWait;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "door": goods <b>ordered at the warehouse terminal to a package door</b>, packed by the player's own
 * Packager and carried to another warehouse ({@code docs/warehouse-system.md} §3.4.6, M27, issue #18, ADR-065).
 * <p>
 * The scene is the M26 handover of {@link PackagesVisualScenario} with the one thing M27 adds in front of it: a
 * warehouse terminal in the sending warehouse. Its door is a requesting warehouse port with nothing set on it, a
 * Create Packager whose back touches the port, a sign reading {@value #ADDRESS} and a <b>lever on the Packager's
 * front</b> — the player's redstone, which the run flips the way the lever's own use does. A hopper under the
 * Packager loads a belt that runs to the second warehouse's in door, where a belt funnel hands the box to a Packager
 * that unpacks it into a warehouse input.
 * <p>
 * <b>What the run proves, each asserted before the shot that shows it:</b>
 * <ol>
 *   <li><b>the door is chosen like a player chooses it</b> — the real terminal screen, opened through
 *       {@code WarehouseTerminalBlockEntity#openScreen}; the door button hovered and clicked through the mouse handler
 *       ({@link ScreenInput}, every point checked against the screen's own hit test); its tooltip, the gold title cue,
 *       the gold tint and line on the stock cell under the mouse;</li>
 *   <li><b>the button holds its place at the 320 x 240 floor</b> — 1280 x 960 at GUI scale 4, folded in and out, the
 *       button inside the window, on no slot, its tooltip inside the screen, and the choice kept through both folds and
 *       the resize back;</li>
 *   <li><b>one click orders sixteen iron to the door</b> — the amount field scrolled to 16 and the iron cell clicked;
 *       the answer names the door, the server holds exactly one request for the door and none for the terminal, and
 *       the standing line says what the door is still owed while the crane is still off;</li>
 *   <li><b>the crane carries it into the port</b> (frozen while it travels), and the terminal's buffer stays empty on
 *       every poll until the port holds the sixteen;</li>
 *   <li><b>the player's lever fires the Packager</b> — the box in the tray carries the sign's address and exactly the
 *       sixteen iron, and no order data, because Wareworks never writes any (ADR-065 D3);</li>
 *   <li><b>the box leaves and arrives</b> — on the belt (frozen), unpacked by the other warehouse's in door and stored
 *       by its crane;</li>
 *   <li><b>a box left waiting is reported</b> — with the hopper under the Packager taken away, a stack put into the
 *       port and the lever flipped, the box rests in the tray; the door's row on the real screen turns to
 *       {@link DoorState#BOX_WAITING} once the box has rested {@value BoxWait#REPORT_AFTER_TICKS} ticks, and the
 *       button's tooltip shows the gold state word and the gold advice line ({@code door-box-waiting}); the box is
 *       then taken out of the tray through its capability, as a hopper would, the hopper put back, and the row is
 *       ready again;</li>
 *   <li><b>in German as well</b> — after a real language switch the screen is opened anew, starts at here again, and
 *       the tooltip, the cues, the answer, the standing line and the box left waiting are measured and shot once more,
 *       with a second order that makes the same journey.</li>
 * </ol>
 * A {@link SceneItemCensus} spanning both warehouses and the belt is taken around every move — a package counting as
 * its contents — and the run ends with {@code door: ALL CHECKS PASSED in English and German}.
 * <p>
 * The camera is the default spectator ({@link VisualWorldProfile#camera()}): the screen needs no reach beyond the
 * vanilla container range, and the world shots need a player whose crosshair targets nothing.
 */
public final class DoorVisualScenario implements VisualScenario {
    public static final String NAME = "door";

    /** Two warehouses, two orders, two box journeys and a language switch need more than the default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 20L * 60L * 1000L;

    private static final Direction AISLE = Direction.EAST;
    /** Z of the second warehouse's dock; the belt crosses the whole gap between the two. */
    private static final int FAR_DOCK_Z = 10;
    private static final int RAILS = 6;
    private static final int MOTOR_RPM = 128;
    private static final int BELT_RPM = 32;
    /** The level both doors stand on, so a hopper fits underneath the door's Packager. */
    private static final int DOOR_LEVEL = 1;

    /** The package door: far enough from the stock that the crane really travels to it. */
    private static final RackPosition PORT = RackPosition.of(4, DOOR_LEVEL, Side.RIGHT);
    /** The terminal, beside the dock, where the camera player stands to use it. */
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    /** The in door of the second warehouse, on the plane the belt arrives at. */
    private static final RackPosition INPUT = RackPosition.of(5, DOOR_LEVEL, Side.LEFT);
    /** Storage of either warehouse: two locations near its dock, so both crane trips cross the aisle. */
    private static final int STORAGE_FIRST = 1;
    private static final int STORAGE_LAST = 2;

    /**
     * What is put into the door's port for the box that is left waiting at the Packager, and how much: an item nothing
     * else in the scene holds, so the census follows it exactly.
     */
    private static final ItemKey LEFT_WAITING = ItemKey.of(Items.AMETHYST_SHARD);
    private static final int LEFT_WAITING_AMOUNT = 8;
    /** Long enough for the box to come to rest, the report's wait, and a build and a push after it. */
    private static final int BOX_WAITING_TIMEOUT_TICKS = BoxWait.REPORT_AFTER_TICKS + 600;
    /** What a click orders to the door, and how much: one box per order. */
    private static final ItemKey ORDERED = ItemKey.of(Items.IRON_INGOT);
    private static final int ORDER_AMOUNT = 16;
    /** One order in English and one in German. */
    private static final int ROUNDS = 2;
    private static final int IRON_IN_STOCK = ORDER_AMOUNT * ROUNDS;
    /** The rest of the sending warehouse's stock, so the grid looks like a warehouse and the iron has to be found. */
    private static final List<ItemStack> OTHER_STOCK = List.of(new ItemStack(Items.COPPER_INGOT, 64),
            new ItemStack(Items.GOLD_INGOT, 24), new ItemStack(Items.COAL, 48), new ItemStack(Items.REDSTONE, 40),
            new ItemStack(Items.LAPIS_LAZULI, 20), new ItemStack(Items.QUARTZ, 12),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.ANDESITE, 64));
    /** What the door's sign says, and so what the screen calls the door and the box carries. */
    private static final String ADDRESS = "Base North";
    private static final String ENGLISH = "en_us";
    private static final String GERMAN = "de_de";

    /** Blocks cleared to air around the two aisles and above the floor. */
    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 10;
    /** Blocks added around the scene bounds for the census box, so a dropped item would be inside it. */
    private static final int CENSUS_MARGIN = 2;

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int REQUEST_TIMEOUT_TICKS = 200;
    private static final int AMOUNT_TIMEOUT_TICKS = 200;
    private static final int RESIZE_TIMEOUT_TICKS = 200;
    private static final int DELIVERY_TIMEOUT_TICKS = 1200;
    private static final int MOMENT_TIMEOUT_TICKS = 2400;
    private static final int ARRIVED_TIMEOUT_TICKS = 3600;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 10;
    /** How long a door's answer stays in the status row, and a little more: then the standing line has the row. */
    private static final int FEEDBACK_GONE_TICKS = 120;
    /**
     * Animation ticks left when the tray shot is taken. The Packager's outward cycle counts {@code CYCLE} down to 0 and
     * the box rises out of the tray as it does, so the box is only worth photographing in the second half of it.
     */
    private static final int TRAY_HALF_OUT_TICKS = 8;
    /** How far along the belt the box must be for its shot: past the loading end, in the middle of the view. */
    private static final double BELT_SHOT_POSITION = 1.0;
    /**
     * Distance the crane still has to travel when the carry shot is taken: half-way from the stock to the door, so it
     * is caught on the way and near enough to the camera for the iron on its arm to read. At 128 RPM the crane travels
     * a third of a block per tick, so the window is several polls wide.
     */
    private static final double MIN_REMAINING_TRAVEL = 0.9;
    private static final double MAX_REMAINING_TRAVEL = 2.3;
    /** Where the mouse is parked for a shot without a tooltip: the window's title row, on nothing at all. */
    private static final int TITLE_CORNER = 5;
    /** Create's icon buttons are 18 x 18 GUI px. */
    private static final int BUTTON_SIZE = 18;
    /** Vanilla's tooltip box: 12 px right of the cursor, 4 px of frame on either side of the text. */
    private static final int TOOLTIP_OFFSET = 12;
    private static final int TOOLTIP_FRAME = 4;
    /** The width of the window's text rows, which every door text is measured against. */
    private static final int ROW_WIDTH = 216;

    // --- the 320 x 240 floor (warehouse-system.md 3.4.6, "The screen") -----------------------------------------------
    private static final int FLOOR_WIDTH = 1280;
    private static final int FLOOR_HEIGHT = 960;
    private static final int FLOOR_GUI_SCALE = 4;
    private static final int FLOOR_GUI_WIDTH = 320;
    private static final int FLOOR_GUI_HEIGHT = 240;
    /** Where the window stands at the floor: folded in it is centred, folded out it moves left. */
    private static final int FLOOR_LEFT_FOLDED_IN = 45;
    private static final int FLOOR_LEFT_FOLDED_OUT = 18;
    /** The door button's left edge inside the window (TerminalMenuLayout#doorButtonX). */
    private static final int DOOR_BUTTON_X = 11;

    // --- cameras, all relative to the first warehouse's dock ----------------------------------------------------------

    /** In the aisle, two blocks from the terminal, looking at it; inside the vanilla container range. */
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 3.1, 1.6, 0.5, 1.4, 0.9, 1.2);
    /** Everything at once, from the side: two warehouses with the belt running the whole way between them. */
    private static final CameraView WHOLE = CameraView.of("whole", -5.0, 5.5, 4.5, 2.5, 1.5, 5.0);
    /** The door from outside the warehouse: port, Packager with its lever, sign, hopper and the belt it loads. */
    private static final CameraView OUT_DOOR = CameraView.of("out-door", 6.4, 4.2, 4.0, 4.6, 1.7, 2.0);
    /** The in door, looked at along the belt (the funnel and its Packager stand side by side across the lane). */
    private static final CameraView IN_DOOR = CameraView.of("in-door", 6.4, 3.6, 5.0, 4.9, 1.4, 8.6);
    /** The racks of the second warehouse, where what arrived ends up. */
    private static final CameraView FAR_RACKS = CameraView.of("far-racks", 5.2, 3.6, 14.2, 1.8, 1.2, 11.4);
    /** Close beside the door, looking into the Packager's tray while the tray is still moving. */
    private static final CameraView TRAY = CameraView.of("tray", 5.9, 4.4, 3.9, 4.5, 1.8, 2.5);
    /** Across the belt near its loading end, where the box is when its moment triggers. */
    private static final CameraView ON_BELT = CameraView.of("box-on-belt", PORT.x() + 3.4, 2.9, 4.5,
            PORT.x() + 0.5, 1.1, 4.5);

    /**
     * Following camera of the carry: down the aisle from beyond the door, a little to the stock side and above, so the
     * door's port stands between the camera and the crane that brings the iron to it.
     */
    private static final double CARRY_AHEAD = 3.6;
    private static final double CARRY_ABOVE = 2.4;
    private static final double CARRY_ASIDE = -1.3;
    private static final double CARRY_LOOK_AHEAD = 0.6;
    private static final double CARRY_LOOK_HEIGHT = 0.8;
    private static final double CARRY_LOOK_ASIDE = 0.1;
    private static final double BLOCK_CENTER = 0.5;

    /** The census box of both warehouses and the belt, set once the origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene holds, for the census; the items only ever move inside the box. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Switches the client's language for the German round ({@link VisualLanguage}). */
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    /** The window and GUI scale before the floor steps, so they can be put back (client thread only). */
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
        script.client("door: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "door run"))
                .server("door: clear the area and place both creative motors", this::placeMotors)
                .server("door: build the sending warehouse with its terminal and its package door",
                        DoorVisualScenario::buildSender)
                .server("door: build the receiving warehouse with its in door", DoorVisualScenario::buildReceiver)
                .server("door: build the belt between the two doors", DoorVisualScenario::buildLane)
                .serverUntil("door: wait until both controllers are ready with every member",
                        DoorVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("door: aim the belt towards the receiving warehouse", DoorVisualScenario::aimBelt)
                .serverUntil("door: wait until the belt really carries that way", DoorVisualScenario::beltAimed,
                        SYNC_TIMEOUT_TICKS)
                .server("door: check that the door is a door and nothing else asks for anything",
                        DoorVisualScenario::checkDoorBuilt)
                .server("door: put " + IRON_IN_STOCK + " " + ORDERED + " and the rest of the stock into the "
                        + "sending warehouse", this::stockSender)
                .server("door: take the baseline census", (server, context) -> census(server, "before ordering"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            script.shotFrom(WHOLE, "idle").shotFrom(OUT_DOOR, "idle");
            // The receiving crane has to store what arrives; the sending crane stays off until each order has been
            // placed, so the standing line can be read before the crane serves it.
            script.server("door: the receiving crane to " + MOTOR_RPM + " RPM",
                    (server, context) -> motor(server.overworld(), farDock(context.origin()).below()).generatedSpeed
                            .setValue(MOTOR_RPM));
            round(script, 1, "", true);
            language.switchTo(script, "door: ", GERMAN);
            round(script, 2, "-de", false);
            language.switchTo(script, "door: ", ENGLISH);
            script.shotFrom(FAR_RACKS, "done")
                    .client("door: every check passed", context -> LOGGER.info(PREFIX + "door: ALL CHECKS PASSED in "
                            + "English and German (a door chosen with the terminal's button, " + ROUNDS + " clicks of "
                            + ORDER_AMOUNT + " " + ORDERED + " delivered into the door and none into the terminal, "
                            + "each boxed by the player's lever with the sign's address and stored by the other "
                            + "warehouse, a box left waiting at the Packager reported in gold, the census never "
                            + "moved)"));
            return;
        }
        script.shotFrom(WHOLE, "done").shotFrom(OUT_DOOR, "done");
        // A GUI does not depend on Flywheel; one shot proves the screen still opens at here after both orders.
        openScreen(script);
        script.client("door: a new screen delivers here", DoorVisualScenario::checkDoorAtHere)
                .client("door: park the mouse in the title row", DoorVisualScenario::parkMouse)
                .waitTicks(SETTLE_TICKS)
                .shot("door-reopened");
        closeScreen(script);
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            status.append(String.format(Locale.ROOT, "screen=terminal door=%s green=%s title='%s' feedback='%s' "
                            + "status='%s' ", terminal.chosenDoor().map(BlockPos::toShortString).orElse("here"),
                    terminal.isDoorButtonGreen(), terminal.titleRowLabel().getString(), feedbackText(terminal),
                    terminal.shownStatusLine().getString()));
        else
            status.append("screen=").append(open == null ? "none" : open.getClass().getSimpleName()).append(' ');
        clientCrane(context).ifPresent(crane -> status.append(String.format(Locale.ROOT, "phase=%s held=%d ",
                crane.craneState().phase(), crane.goggleInfo().heldCount())));
        status.append(clientPackager(context)
                .map(packager -> String.format(Locale.ROOT, "tray=%s anim=%d",
                        packager.heldBox.isEmpty() ? "empty" : "box", packager.animationTicks))
                .orElse("tray=?"));
        return status.toString();
    }

    // --- one order, from the screen to the other warehouse's racks ---------------------------------------------------

    /**
     * One order to the door: the screen, the click, the crane, the lever, the box. The first round, in English, also
     * shoots the moments in the world and the floor sizes; the second, in German, measures and shoots the screen again
     * and checks the same journey without photographing it twice.
     */
    private void round(VisualScript script, int round, String suffix, boolean english) {
        String lang = english ? "English" : "German";
        openScreen(script);
        script.until("door: wait until the screen lists the iron", DoorVisualScenario::listsTheOrderedItem,
                SCREEN_TIMEOUT_TICKS);
        script.client("door: the new screen delivers here (" + lang + ")", DoorVisualScenario::checkDoorAtHere)
                .client("door: hover the door button", DoorVisualScenario::hoverDoorButton)
                .waitTicks(SETTLE_TICKS)
                .client("door: check the door button's tooltip at here", context -> checkDoorTooltip(context, false))
                .shot("door-tooltip" + suffix)
                .client("door: click the door button", DoorVisualScenario::clickDoorButton)
                .client("door: the click chose the door", DoorVisualScenario::checkDoorChosen)
                .client("door: hover the iron in the stock list", DoorVisualScenario::hoverOrderedCell)
                .waitTicks(SETTLE_TICKS)
                .client("door: check the door button's tooltip with the door chosen",
                        context -> checkDoorTooltip(context, true))
                .client("door: check the gold cues on the cell", DoorVisualScenario::checkCellCue)
                .shot("door-chosen" + suffix);
        if (english)
            floorSteps(script);
        amount(script, ORDER_AMOUNT);
        script.server("door: census before the click (" + lang + ")",
                        (server, context) -> census(server, "before the door click " + round))
                .client("door: click the iron for the door", DoorVisualScenario::clickOrderedCell);
        ArmVisualScenario.untilOrFail(script, "door: wait until the answer names the door",
                DoorVisualScenario::doorAnswerShown, REQUEST_TIMEOUT_TICKS,
                context -> "the status row reads '" + feedbackText(screen(context)) + "'");
        script.client("door: check the door's answer", DoorVisualScenario::checkDoorAnswer)
                .client("door: park the mouse in the title row", DoorVisualScenario::parkMouse)
                .waitTicks(SETTLE_TICKS)
                .shot("door-sent" + suffix)
                .server("door: the click is one request for the door and none for the terminal",
                        (server, context) -> checkRequestForDoor(server, context, round))
                .waitTicks(FEEDBACK_GONE_TICKS)
                .until("door: wait until the door's row counts the request",
                        context -> chosenRow(context).waiting() > 0L, SCREEN_TIMEOUT_TICKS)
                .client("door: the status row says what the door is still owed",
                        DoorVisualScenario::checkStandingLine)
                .shot("door-standing" + suffix);
        closeScreen(script);
        script.server("door: the sending crane to " + MOTOR_RPM + " RPM",
                (server, context) -> motor(server.overworld(), context.origin().below()).generatedSpeed
                        .setValue(MOTOR_RPM));
        if (english)
            script.until("door: wait until the crane carries the iron towards the door",
                            DoorVisualScenario::carryingToTheDoor, MOMENT_TIMEOUT_TICKS)
                    .freeze(true)
                    .camera("door-carry", DoorVisualScenario::carryView)
                    .shot("door-carry")
                    .freeze(false);
        script.serverUntil("door: wait until the crane put the iron into the door's port, with the terminal empty",
                        (server, context) -> portFilled(server, context, round), DELIVERY_TIMEOUT_TICKS)
                .server("door: census after the crane filled the door",
                        (server, context) -> census(server, "after the crane filled the door " + round))
                .server("door: the player's lever on", (server, context) -> lever(server, context, true));
        if (english) {
            script.until("door: wait until the box rises out of the Packager's tray", DoorVisualScenario::boxInTheTray,
                            MOMENT_TIMEOUT_TICKS)
                    .freeze(true)
                    .server("door: check the box in the tray", DoorVisualScenario::checkBoxInTheTray)
                    .server("door: census with the box in the tray",
                            (server, context) -> census(server, "with the box in the tray " + round))
                    .camera(TRAY)
                    .shot("door-tray")
                    .freeze(false)
                    .until("door: wait until the box rides the belt", DoorVisualScenario::boxOnTheBelt,
                            MOMENT_TIMEOUT_TICKS)
                    .freeze(true)
                    .server("door: census with the box on the belt",
                            (server, context) -> census(server, "with the box on the belt " + round))
                    .camera(ON_BELT)
                    .shot("door-box-on-belt")
                    .freeze(false);
        } else {
            script.serverUntil("door: wait until the Packager holds the box, then check it and take the census",
                    (server, context) -> boxPacked(server, context, round), MOMENT_TIMEOUT_TICKS);
        }
        script.serverUntil("door: wait until the iron has arrived in the other warehouse's racks",
                        (server, context) -> arrived(server, context, round), ARRIVED_TIMEOUT_TICKS)
                .server("door: census after the box arrived",
                        (server, context) -> census(server, "after the box arrived " + round))
                .server("door: check what left, what travelled and what arrived",
                        (server, context) -> checkArrived(server, context, round))
                .server("door: the player's lever off again", (server, context) -> lever(server, context, false))
                .server("door: the sending crane off again, so the next order waits on screen",
                        (server, context) -> motor(server.overworld(), context.origin().below()).generatedSpeed
                                .setValue(0));
        if (english)
            script.shotFrom(IN_DOOR, "arrived").shotFrom(FAR_RACKS, "arrived");
        boxLeftWaiting(script, round, suffix, lang);
    }

    /**
     * A box left waiting at the door's Packager (ADR-065 D14): the hopper that takes the boxes is taken away, a stack
     * is put into the port and the lever flipped, so the Packager boxes it and holds the box. The door's row on the real
     * screen turns to {@link DoorState#BOX_WAITING} once the box has rested long enough, and the button's tooltip shows
     * the gold state word and the gold advice line. Then the box is taken out of the tray through the Packager's own
     * capability, as a hopper would, the hopper is put back and the door reads ready again. The census follows the
     * stack in and the box out.
     */
    private void boxLeftWaiting(VisualScript script, int round, String suffix, String lang) {
        script.server("door: take the hopper away from under the door's Packager", DoorVisualScenario::takeTheDrainAway)
                .server("door: put " + LEFT_WAITING_AMOUNT + " " + LEFT_WAITING + " into the door's port",
                        this::fillThePortForTheWait)
                .server("door: the player's lever on, so the Packager boxes them",
                        (server, context) -> lever(server, context, true))
                .serverUntil("door: wait until the box rests in the Packager's tray, with nothing under it",
                        DoorVisualScenario::boxResting, MOMENT_TIMEOUT_TICKS)
                .server("door: census with a box left waiting",
                        (server, context) -> census(server, "with a box left waiting " + round));
        openScreen(script);
        script.client("door: choose the door for the box left waiting (" + lang + ")",
                        DoorVisualScenario::clickDoorButton)
                .client("door: the click chose the door", DoorVisualScenario::checkDoorChosen)
                .client("door: hover the door button", DoorVisualScenario::hoverDoorButton);
        ArmVisualScenario.untilOrFail(script, "door: wait until the door's row says a box is waiting",
                context -> chosenRow(context).state() == DoorState.BOX_WAITING, BOX_WAITING_TIMEOUT_TICKS,
                context -> "the door's row reads " + chosenRow(context));
        script.waitTicks(SETTLE_TICKS)
                .client("door: check the gold state word and advice line in the button's tooltip",
                        DoorVisualScenario::checkBoxWaitingTooltip)
                .shot("door-box-waiting" + suffix);
        closeScreen(script);
        script.server("door: take the box out of the Packager's tray as a hopper would, and put the hopper back",
                        this::takeTheWaitingBox)
                .server("door: the player's lever off again", (server, context) -> lever(server, context, false))
                .server("door: census once the box has left the scene",
                        (server, context) -> census(server, "after the waiting box was taken " + round))
                .serverUntil("door: wait until the door reads ready again", DoorVisualScenario::doorReadyAgain,
                        SCREEN_TIMEOUT_TICKS);
    }

    private static void openScreen(VisualScript script) {
        script.camera(AT_TERMINAL)
                .server("door: open the terminal screen", DoorVisualScenario::openScreen)
                .until("door: wait for the screen with its stock and the door",
                        context -> screenReady(context) && doorListed(context), SCREEN_TIMEOUT_TICKS);
    }

    private static void closeScreen(VisualScript script) {
        script.client("door: close the screen", context -> {
            LocalPlayer player = context.minecraft().player;
            if (player != null)
                player.closeContainer();
        }).until("door: wait until the screen is closed", context -> context.minecraft().screen == null,
                SCREEN_TIMEOUT_TICKS);
    }

    /**
     * Amount field to {@code amount}, one notch of the wheel per tick over the field, the way a player sets it; the
     * field remembers its value for the next screen, so the German round finds it already set and checks that.
     */
    private static void amount(VisualScript script, int amount) {
        ArmVisualScenario.untilOrFail(script, "door: scroll the amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(screen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, AMOUNT_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
    }

    // --- the 320 x 240 floor -----------------------------------------------------------------------------------------

    /**
     * The door button at the smallest GUI any window gets (the table in {@code docs/warehouse-system.md} 3.4.6, "The
     * screen"): 1280 x 960 at scale 4, folded in and out by real clicks on the crafting toggle. Then the window and the
     * scale are put back, and the choice has to have outlived all of it, because it belongs to the screen object and
     * every one of these steps lays it out anew.
     */
    private void floorSteps(VisualScript script) {
        script.client("door: remember the window and the GUI scale", this::rememberWindow)
                .client("door: park the mouse in the title row", DoorVisualScenario::parkMouse);
        resize(script, FLOOR_WIDTH, FLOOR_HEIGHT, FLOOR_GUI_SCALE);
        script.client("door: make sure the crafting panel is folded in", context -> {
                    if (screen(context).isCraftPanelOpen())
                        clickCraftToggle(context);
                })
                .until("door: the panel is folded in", context -> !screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .client("door: hover the door button at the floor", DoorVisualScenario::hoverDoorButton)
                .waitTicks(SETTLE_TICKS)
                .client("door: CHECK the door button at the floor, folded in",
                        context -> checkAtFloor(context, false))
                .shot("door-floor")
                .client("door: fold the crafting panel out at the floor", DoorVisualScenario::clickCraftToggle)
                .until("door: the panel is folded out", context -> screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .client("door: hover the door button with the panel folded out", DoorVisualScenario::hoverDoorButton)
                .waitTicks(SETTLE_TICKS)
                .client("door: CHECK the door button at the floor, folded out",
                        context -> checkAtFloor(context, true))
                .shot("door-floor-out")
                .client("door: fold the crafting panel back in", DoorVisualScenario::clickCraftToggle)
                .until("door: the panel is folded in again", context -> !screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .client("door: park the mouse in the title row", DoorVisualScenario::parkMouse);
        script.client("door: put the window back",
                context -> context.minecraft().getWindow().setWindowed(windowWidth, windowHeight));
        ArmVisualScenario.untilOrFail(script, "door: the window is back",
                context -> TooltipsVisualScenario.synced(context)
                        && context.minecraft().getWindow().getScreenWidth() == windowWidth
                        && context.minecraft().getWindow().getScreenHeight() == windowHeight,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("door: put the GUI scale back", context -> {
            context.minecraft().options.guiScale().set(guiScaleOption);
            context.minecraft().resizeDisplay();
        });
        ArmVisualScenario.untilOrFail(script, "door: the GUI is as large as before",
                context -> context.minecraft().getWindow().getGuiScaledWidth() == guiWidth
                        && context.minecraft().getWindow().getGuiScaledHeight() == guiHeight
                        && screen(context).width == guiWidth,
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.waitTicks(SETTLE_TICKS)
                .client("door: the door is still chosen after the floor and the resize back",
                        DoorVisualScenario::checkDoorChosen);
    }

    private void rememberWindow(VisualContext context) {
        Window window = context.minecraft().getWindow();
        windowWidth = window.getScreenWidth();
        windowHeight = window.getScreenHeight();
        guiWidth = window.getGuiScaledWidth();
        guiHeight = window.getGuiScaledHeight();
        guiScaleOption = context.minecraft().options.guiScale().get();
        LOGGER.info(PREFIX + "door: the window is {}x{} ({}x{} GUI px, scale option {})", windowWidth, windowHeight,
                guiWidth, guiHeight, guiScaleOption);
    }

    /**
     * {@code Window#setWindowed}, then the GUI scale, in that order: the option clamps a scale to what the window
     * allows <i>now</i>, so scale 4 set on the 1600 x 900 window would quietly become 3.
     */
    private static void resize(VisualScript script, int width, int height, int scale) {
        script.client("door: make the window " + width + "x" + height,
                context -> context.minecraft().getWindow().setWindowed(width, height));
        ArmVisualScenario.untilOrFail(script, "door: the framebuffer is " + width + "x" + height,
                context -> TooltipsVisualScenario.synced(context)
                        && TooltipsVisualScenario.framebufferIs(context.minecraft().getWindow(), width, height),
                RESIZE_TIMEOUT_TICKS, context -> describeWindow(context.minecraft().getWindow()));
        script.client("door: GUI scale " + scale, context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.options.guiScale().set(scale);
            minecraft.resizeDisplay();
            if (minecraft.getWindow().getGuiScale() != scale)
                throw new VisualTestException("the GUI scale is " + minecraft.getWindow().getGuiScale() + ", not "
                        + scale + ", at " + describeWindow(minecraft.getWindow()));
        }).waitTicks(SETTLE_TICKS);
    }

    /**
     * At the floor: the GUI is 320 x 240, the window stands where warehouse-system.md 3.4.6 puts it, the door button
     * sits inside the
     * window at its own column and on no slot, the choice is still the door, and the hovered button's tooltip fits
     * the row and opens to the right of the cursor without leaving the screen.
     */
    private static void checkAtFloor(VisualContext context, boolean foldedOut) {
        Window window = context.minecraft().getWindow();
        WarehouseTerminalScreen terminal = screen(context);
        if (window.getGuiScaledWidth() != FLOOR_GUI_WIDTH || window.getGuiScaledHeight() != FLOOR_GUI_HEIGHT
                || terminal.width != FLOOR_GUI_WIDTH || terminal.height != FLOOR_GUI_HEIGHT)
            throw new VisualTestException("the GUI is " + describeWindow(window) + " (screen " + terminal.width + "x"
                    + terminal.height + "), not " + FLOOR_GUI_WIDTH + "x" + FLOOR_GUI_HEIGHT);
        if (terminal.isCraftPanelOpen() != foldedOut)
            throw new VisualTestException("the crafting panel is not folded " + (foldedOut ? "out" : "in"));
        int left = foldedOut ? FLOOR_LEFT_FOLDED_OUT : FLOOR_LEFT_FOLDED_IN;
        if (terminal.getGuiLeft() != left)
            throw new VisualTestException("at the floor the window stands at " + terminal.getGuiLeft() + ", not "
                    + left);
        checkDoorChosen(context);
        ScreenInput.Point centre = ScreenInput.terminalDoorButton(terminal);
        int buttonLeft = (int) Math.round(centre.x() - BUTTON_SIZE / 2.0);
        int buttonTop = (int) Math.round(centre.y() - BUTTON_SIZE / 2.0);
        if (buttonLeft != terminal.getGuiLeft() + DOOR_BUTTON_X)
            throw new VisualTestException("the door button stands at x " + buttonLeft + ", not "
                    + (terminal.getGuiLeft() + DOOR_BUTTON_X));
        if (buttonLeft < terminal.getGuiLeft() || buttonTop < terminal.getGuiTop()
                || buttonLeft + BUTTON_SIZE > terminal.getGuiLeft() + terminal.getXSize()
                || buttonTop + BUTTON_SIZE > terminal.getGuiTop() + terminal.getYSize())
            throw new VisualTestException("the door button [" + buttonLeft + ", " + buttonTop + "] leaves the window "
                    + "at " + terminal.getGuiLeft() + ", " + terminal.getGuiTop() + " (" + terminal.getXSize() + "x"
                    + terminal.getYSize() + ")");
        for (Slot slot : terminal.getMenu().slots) {
            int slotLeft = terminal.getGuiLeft() + slot.x;
            int slotTop = terminal.getGuiTop() + slot.y;
            if (slotLeft < buttonLeft + BUTTON_SIZE && buttonLeft < slotLeft + 16 && slotTop < buttonTop + BUTTON_SIZE
                    && buttonTop < slotTop + 16)
                throw new VisualTestException("the door button [" + buttonLeft + ", " + buttonTop + "] covers the "
                        + "slot at " + slotLeft + ", " + slotTop);
        }
        List<Component> tooltip = checkDoorTooltipFits(context);
        int widest = 0;
        for (Component line : tooltip)
            widest = Math.max(widest, context.minecraft().font.width(line));
        int right = (int) Math.round(centre.x()) + TOOLTIP_OFFSET + widest + TOOLTIP_FRAME;
        if (right > FLOOR_GUI_WIDTH)
            throw new VisualTestException("the door button's tooltip would reach x " + right + " of a "
                    + FLOOR_GUI_WIDTH + " px screen");
        LOGGER.info(PREFIX + "door: CHECK PASS at the floor folded {}: the window at x {}, the door button at ({}, {}) "
                + "on no slot, its tooltip of {} lines ends at x {} of {}", foldedOut ? "out" : "in",
                terminal.getGuiLeft(), buttonLeft, buttonTop, tooltip.size(), right, FLOOR_GUI_WIDTH);
    }

    private static String describeWindow(Window window) {
        return String.format(Locale.ROOT, "window %dx%d, framebuffer %dx%d, GUI %dx%d at scale %.0f",
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale());
    }

    // --- the screen (client thread) ----------------------------------------------------------------------------------

    private static boolean screenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && !terminal.matchingEntries().isEmpty();
    }

    /** The screen lists the iron a round orders; after both rounds the sending warehouse holds none. */
    private static boolean listsTheOrderedItem(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal
                && terminal.entry(ORDERED).isPresent();
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + open + ")");
    }

    /** Whether the screen lists the door, by its sign, with the door button shown. */
    private static boolean doorListed(VisualContext context) {
        if (!(context.minecraft().screen instanceof WarehouseTerminalScreen terminal))
            return false;
        return terminal.isDoorButtonShown()
                && terminal.doors().rows().stream().anyMatch(row -> row.label().equals(ADDRESS));
    }

    /** The door's row as the screen last got it. */
    private static TerminalDoorRow doorRow(VisualContext context) {
        return screen(context).doors().rows().stream().filter(row -> row.label().equals(ADDRESS)).findFirst()
                .orElseThrow(() -> new VisualTestException("the screen does not list the door '" + ADDRESS + "': "
                        + screen(context).doors()));
    }

    /** The chosen door's row; fails while the choice is here. */
    private static TerminalDoorRow chosenRow(VisualContext context) {
        TerminalDoorRow row = doorRow(context);
        if (!screen(context).chosenDoor().equals(Optional.of(row.pos())))
            throw new VisualTestException("the door is not chosen: " + screen(context).chosenDoor());
        return row;
    }

    /** Here: no door chosen, the button shown but not green, the title row naming no door, the door ready. */
    private static void checkDoorAtHere(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.isDoorChosen() || terminal.isDoorButtonGreen() || !terminal.isDoorButtonShown())
            throw new VisualTestException("the screen should deliver here with the door button shown, not green: "
                    + "chosen " + terminal.chosenDoor() + ", green " + terminal.isDoorButtonGreen() + ", shown "
                    + terminal.isDoorButtonShown());
        String label = terminal.titleRowLabel().getString();
        String indicator = WarehouseTerminalScreen.doorIndicatorText(Component.empty()).getString().trim();
        if (label.startsWith(indicator))
            throw new VisualTestException("the title row names a door while the screen delivers here: " + label);
        TerminalDoorRow row = doorRow(context);
        if (row.state() != DoorState.READY)
            throw new VisualTestException("the door should be ready: " + row);
        LOGGER.info(PREFIX + "door: the screen delivers here; the door '{}' at {} is listed {} ({})", row.label(),
                row.rackAddress(), row.state(), context.minecraft().getLanguageManager().getSelected());
    }

    /** The door chosen: the button green, the title row naming the door behind its arrow. */
    private static void checkDoorChosen(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        TerminalDoorRow row = chosenRow(context);
        if (!terminal.isDoorButtonGreen())
            throw new VisualTestException("the door button is not green while the door is chosen");
        String label = terminal.titleRowLabel().getString();
        String expected = WarehouseTerminalScreen.doorIndicatorText(Component.literal(ADDRESS)).getString();
        if (!label.equals(expected))
            throw new VisualTestException("the title row reads '" + label + "', not '" + expected + "'");
        LOGGER.info(PREFIX + "door: the door {} is chosen; the title row reads '{}'", row.rackAddress(), label);
    }

    /**
     * The door button's tooltip: where a click delivers in its first line, the current entry marked, the hint and
     * Create's own scroll line last, every line inside the row.
     */
    private static void checkDoorTooltip(VisualContext context, boolean chosen) {
        WarehouseTerminalScreen terminal = screen(context);
        List<String> lines = checkDoorTooltipFits(context).stream().map(Component::getString).toList();
        String here = text(WareworksLang.TERMINAL_DOOR_HERE);
        TerminalDoorRow row = doorRow(context);
        String title = WarehouseTerminalScreen.doorTitleText(Component.literal(chosen ? ADDRESS : here)).getString()
                + (chosen ? " " + row.rackAddress() : "");
        String current = "-> " + (chosen ? ADDRESS + "  " + row.rackAddress() : here);
        String hint = text(WareworksLang.TERMINAL_DOOR_HINT);
        String scroll = Component.translatable(WareworksLang.CREATE_SCROLL_TO_SELECT_KEY).getString();
        if (lines.isEmpty() || !lines.getFirst().equals(title) || !lines.contains(current) || !lines.contains(hint)
                || !lines.getLast().equals(scroll))
            throw new VisualTestException("the door button's tooltip should start with '" + title + "', mark '"
                    + current + "' and end with '" + hint + "' and '" + scroll + "': " + lines);
        if (!terminal.isDoorButtonShown())
            throw new VisualTestException("the door button is hidden");
        LOGGER.info(PREFIX + "door: the door button's tooltip ({} lines, {}): {}", lines.size(),
                context.minecraft().getLanguageManager().getSelected(), lines);
    }

    /**
     * Every line of the door button's tooltip inside the row, and at most
     * {@link WarehouseTerminalScreen#MAX_DOOR_TOOLTIP_LINES} lines.
     */
    private static List<Component> checkDoorTooltipFits(VisualContext context) {
        List<Component> tooltip = screen(context).doorTooltip();
        if (tooltip.isEmpty() || tooltip.size() > WarehouseTerminalScreen.MAX_DOOR_TOOLTIP_LINES)
            throw new VisualTestException("the door button's tooltip has " + tooltip.size() + " lines: "
                    + widths(context, tooltip));
        for (Component line : tooltip) {
            if (context.minecraft().font.width(line) > ROW_WIDTH)
                throw new VisualTestException("a line of the door button's tooltip is wider than the window's row ("
                        + ROW_WIDTH + " px): " + widths(context, tooltip));
        }
        LOGGER.info(PREFIX + "door: CHECK door tooltip PASS: {} lines fit {} px in {}: {}", tooltip.size(), ROW_WIDTH,
                context.minecraft().getLanguageManager().getSelected(), widths(context, tooltip));
        return tooltip;
    }

    /** The cell under the mouse carries the door's cues: the gold tint is on, and its tooltip names the door. */
    private static void checkCellCue(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (!terminal.doorCueShown())
            throw new VisualTestException("the stock cells show no door cue while the door is chosen");
        ScreenInput.Point cell = orderedCell(context);
        List<String> tooltip = terminal.tooltipAt((int) cell.x(), (int) cell.y()).stream().map(Component::getString)
                .toList();
        String line = WareworksLang.translateDirect(WareworksLang.TERMINAL_DOOR_CELL, ADDRESS).getString();
        if (!tooltip.contains(line))
            throw new VisualTestException("the cell's tooltip does not say '" + line + "': " + tooltip);
        LOGGER.info(PREFIX + "door: the hovered cell is tinted gold and its tooltip says '{}'", line);
    }

    /**
     * The button's tooltip while the chosen door's box waits: the door's entry carries the state word in gold, and the
     * advice line under the entries is gold too, both in the language that is loaded; every line still fits the row.
     */
    private static void checkBoxWaitingTooltip(VisualContext context) {
        List<Component> tooltip = checkDoorTooltipFits(context);
        String word = text(DoorState.BOX_WAITING.langKey().orElseThrow());
        String advice = text(DoorState.BOX_WAITING.detailKey().orElseThrow());
        if (tooltip.stream().noneMatch(line -> isGold(line, word) && line.getString().contains(ADDRESS)))
            throw new VisualTestException("the door's entry does not say '" + word + "' in gold: "
                    + widths(context, tooltip));
        if (tooltip.stream().noneMatch(line -> isGold(line, advice)))
            throw new VisualTestException("the tooltip does not say '" + advice + "' in gold: "
                    + widths(context, tooltip));
        LOGGER.info(PREFIX + "door: CHECK PASS box waiting: the door's entry says '{}' and the advice line '{}', both "
                + "in gold ({})", word, advice, context.minecraft().getLanguageManager().getSelected());
    }

    /** Whether the piece of {@code line} that holds {@code text} is drawn in gold. */
    private static boolean isGold(Component line, String text) {
        TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
        return line.visit((style, piece) -> piece.contains(text) && gold.equals(style.getColor())
                ? FormattedText.STOP_ITERATION : Optional.<Unit>empty(), Style.EMPTY).isPresent();
    }

    /** The click's answer, once it names the door: the server's echo reached the screen. */
    private static boolean doorAnswerShown(VisualContext context) {
        return isDoorAnswer(screen(context), feedbackText(screen(context)));
    }

    /**
     * Whether {@code shown} is the answer to the door click, <b>whole</b>: {@code "Iron Ingot x16 to Base North"} in
     * the language that is loaded. Both languages' answers fit the row with a sign as long as this one ("Eisenbarren
     * x16 an Base North" is 160 px of 216), so an answer that no longer fits it whole fails the run instead of being
     * accepted cut: the item's name would give way first ({@code WarehouseTerminalScreen#fitDoorLine}), and a frame
     * that grows is exactly what this guards against.
     */
    private static boolean isDoorAnswer(WarehouseTerminalScreen terminal, String shown) {
        Component item = ORDERED.toStack().getHoverName();
        Component full = WarehouseTerminalScreen.doorRequestedText(item, ORDER_AMOUNT, Component.literal(ADDRESS));
        if (!terminal.fitsStatusRow(full))
            throw new VisualTestException("the door's answer '" + full.getString() + "' no longer fits the row whole");
        return shown.equals(full.getString());
    }

    private static void checkDoorAnswer(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        Component shown = terminal.feedbackLine()
                .orElseThrow(() -> new VisualTestException("the door's answer left the status row"));
        if (!terminal.statusTextsFit() || !terminal.fitsStatusRow(shown))
            throw new VisualTestException("the door's answer '" + shown.getString() + "' does not fit the row");
        checkDoorChosen(context);
        LOGGER.info(PREFIX + "door: PASS the answer reads '{}' ({} px of {}, {})", shown.getString(),
                context.minecraft().font.width(shown), ROW_WIDTH,
                context.minecraft().getLanguageManager().getSelected());
    }

    /** The status row's standing line about the chosen door, built from the door's own row on the screen. */
    private static void checkStandingLine(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        TerminalDoorRow row = chosenRow(context);
        if (terminal.hasFeedback())
            throw new VisualTestException("an answer still holds the status row: " + feedbackText(terminal));
        if (row.waiting() != ORDER_AMOUNT)
            throw new VisualTestException("the door's row should wait for " + ORDER_AMOUNT + ": " + row);
        Component full = WarehouseTerminalScreen.doorStandingText(Component.literal(ADDRESS), row.waiting(),
                row.delivered());
        if (!terminal.fitsStatusRow(full))
            throw new VisualTestException("the full standing line '" + full.getString() + "' does not fit the row");
        String shown = terminal.shownStatusLine().getString();
        if (!shown.equals(full.getString()))
            throw new VisualTestException("the status row reads '" + shown + "', not '" + full.getString() + "'");
        List<String> tooltip = terminal.doorTooltip().stream().map(Component::getString).toList();
        String owed = WareworksLang.translateDirect(WareworksLang.TERMINAL_WAITING,
                LangNumberFormat.format(row.waiting()), LangNumberFormat.format(row.delivered())).getString();
        if (!tooltip.contains(owed))
            throw new VisualTestException("the door button's tooltip does not say '" + owed + "': " + tooltip);
        LOGGER.info(PREFIX + "door: PASS the status row reads '{}', and the button's tooltip '{}'", shown, owed);
    }

    private static void hoverDoorButton(VisualContext context) {
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalDoorButton(screen(context)));
    }

    private static void clickDoorButton(VisualContext context) {
        ScreenInput.click(context.minecraft(), ScreenInput.terminalDoorButton(screen(context)));
    }

    private static void clickCraftToggle(VisualContext context) {
        ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftToggle(screen(context)));
    }

    private static void hoverOrderedCell(VisualContext context) {
        ScreenInput.hover(context.minecraft(), orderedCell(context));
    }

    private static void clickOrderedCell(VisualContext context) {
        ScreenInput.Point cell = orderedCell(context);
        ScreenInput.click(context.minecraft(), cell);
        LOGGER.info(PREFIX + "door: clicked {} at {} with the amount field at x{}", ORDERED, cell,
                ScreenInput.terminalAmount(screen(context)).getState());
    }

    /**
     * The cell the iron is drawn in right now, checked with the screen's own hit test. Looked up by its key rather
     * than by a fixed position, because the grid's order follows the amounts and what this player orders most.
     */
    private static ScreenInput.Point orderedCell(VisualContext context) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int index = 0; index < visible.size(); index++) {
            if (visible.get(index).key().equals(ORDERED))
                return ScreenInput.terminalCell(screen(context), index);
        }
        throw new VisualTestException("the grid shows no " + ORDERED + ": " + visible.size() + " cells");
    }

    private static void parkMouse(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.hover(context.minecraft(), new ScreenInput.Point(terminal.getGuiLeft() + TITLE_CORNER,
                terminal.getGuiTop() + TITLE_CORNER));
    }

    private static String feedbackText(WarehouseTerminalScreen terminal) {
        return terminal.feedbackLine().map(Component::getString).orElse("");
    }

    /** Every tooltip line with the width it is drawn at, for the message of a failed width check. */
    private static List<String> widths(VisualContext context, List<Component> tooltip) {
        List<String> measured = new ArrayList<>(tooltip.size());
        for (Component line : tooltip)
            measured.add("'" + line.getString() + "' " + context.minecraft().font.width(line) + " px");
        return measured;
    }

    private static String text(String relativeKey) {
        return WareworksLang.translateDirect(relativeKey).getString();
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!level.isLoaded(new BlockPos(0, level.getMinBuildHeight(), 0)))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        censusBox = sceneBounds(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, FAR_DOCK_Z + CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (BlockPos pos : List.of(dock, farDock(dock))) {
            level.setBlockAndUpdate(pos.below(),
                    AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
            motor(level, pos.below()).generatedSpeed.setValue(0);
        }
    }

    /** The first warehouse: aisle, rack wall, terminal, and the door with everything a package door needs. */
    private static void buildSender(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        buildAisle(level, dock);
        BranchLayout layout = senderLayout(dock);
        buildStorage(level, layout, Side.LEFT);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));

        // The door: a warehouse port with nothing set on it, which requests by default and asks for nothing itself.
        BlockPos port = layout.rackPos(PORT);
        Direction outward = layout.sideDirection(PORT.side());
        level.setBlockAndUpdate(port, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
        // The Packager's back touches the port, which is the whole rule of a package door: its target inventory is
        // the block at packagerPos - FACING (PackageHandover), so FACING points away from the port.
        BlockPos packager = senderPackagerPos(dock);
        level.setBlockAndUpdate(packager,
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, outward));
        // The player's redstone: a lever on the Packager's front face, which powers the block it hangs on directly.
        // Not on top: the box rises out of the Packager's top, and a lever there would stand in front of it.
        level.setBlockAndUpdate(leverPos(dock), Blocks.LEVER.defaultBlockState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, outward)
                .setValue(LeverBlock.POWERED, false));
        // The address: a plain vanilla sign on a post beside the Packager. A standing sign needs a solid block under
        // it or the next neighbour update pops it off, so the post is part of the build.
        BlockPos sign = signPos(dock);
        level.setBlockAndUpdate(sign.below(), Blocks.POLISHED_ANDESITE.defaultBlockState());
        level.setBlockAndUpdate(sign, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 12));
        if (!(level.getBlockEntity(sign) instanceof SignBlockEntity entity)
                || !entity.setText(new SignText().setMessage(0, Component.literal(ADDRESS)), true))
            throw new VisualTestException("the door's sign at " + sign + " did not take its text");
    }

    /** The second warehouse: aisle, rack wall, and the in door the belt delivers to. */
    private static void buildReceiver(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = farDock(context.origin());
        buildAisle(level, dock);
        BranchLayout layout = receiverLayout(context.origin());
        buildStorage(level, layout, Side.RIGHT);
        BlockPos input = layout.rackPos(INPUT);
        level.setBlockAndUpdate(input, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        Direction outward = layout.sideDirection(INPUT.side());
        level.setBlockAndUpdate(input.relative(outward),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, outward));
    }

    /** Dock, controller and rails of one warehouse. */
    private static void buildAisle(ServerLevel level, BlockPos dock) {
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()), WareworksBlocks.WAREHOUSE_CONTROLLER
                .getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
    }

    /** A rack wall of storage locations with chests behind them, on {@code side} of {@code layout}. */
    private static void buildStorage(ServerLevel level, BranchLayout layout, Side side) {
        Direction outward = layout.sideDirection(side);
        for (int x = STORAGE_FIRST; x <= STORAGE_LAST; x++) {
            BlockPos rack = layout.rackPos(RackPosition.of(x, 0, side));
            level.setBlockAndUpdate(rack.relative(outward), Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, outward.getOpposite()));
            level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
    }

    /**
     * The belt between the two doors and the blocks that load and unload it, as in {@link PackagesVisualScenario}:
     * a vanilla hopper under the door's Packager drains its tray onto the belt, and a brass belt funnel at the far end
     * hands the box to the in door's Packager, which unpacks it into the warehouse input behind it.
     */
    private static void buildLane(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        level.setBlockAndUpdate(drainPos(dock),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, laneDirection()));
        BlockPos start = beltStart(dock);
        BlockPos end = beltEnd(dock);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the belt was not created between " + start + " and " + end);
        // A belt along Z turns on the axis across it, so its motor stands beside the belt's first pulley, on the far
        // side from the door.
        BlockPos beltMotor = start.relative(AISLE.getOpposite());
        level.setBlockAndUpdate(beltMotor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE));
        motor(level, beltMotor).generatedSpeed.setValue(BELT_RPM);
        // A belt that ends in open air ejects its items onto the floor; a solid face makes them queue instead.
        level.setBlockAndUpdate(end.relative(laneDirection()), Blocks.POLISHED_ANDESITE.defaultBlockState());
        BlockPos funnel = end.above();
        level.setBlockAndUpdate(funnel, AllBlocks.BRASS_BELT_FUNNEL.getDefaultState()
                .setValue(BeltFunnelBlock.HORIZONTAL_FACING, AISLE.getOpposite())
                .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.Shape.PULLING));
        if (!funnel.relative(AISLE).equals(receiverPackagerPos(dock)))
            throw new VisualTestException("the belt funnel at " + funnel + " does not hand over to the in door's "
                    + "Packager at " + receiverPackagerPos(dock));
    }

    /** Both controllers know their whole layout, both cranes are linked, and the door and the in door are members. */
    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        // The sending warehouse records two output stations: the terminal and the door.
        return aisleReady(level, dock, 2, 0) && aisleReady(level, farDock(dock), 0, 1);
    }

    private static boolean aisleReady(ServerLevel level, BlockPos dock, int outputs, int inputs) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        int storage = STORAGE_LAST - STORAGE_FIRST + 1;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == storage
                && controller.outputStations().size() == outputs && controller.inputStations().size() == inputs
                && crane.isControllerLinked();
    }

    /**
     * The door as the server sees it before anything is ordered: a requesting port with no request of its own, a
     * Packager at its back that answers redstone, unpowered, and the sign's address.
     */
    private static void checkDoorBuilt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseOutputBlockEntity port = port(level, dock);
        if (port.portDirection() != PortDirection.REQUEST || port.filterKey().isPresent())
            throw new VisualTestException("the door's port should request and ask for nothing itself: "
                    + port.portDirection() + ", filter " + port.filterKey());
        if (!PackageHandover.packagerFor(level, portPos(dock)).equals(Optional.of(senderPackagerPos(dock))))
            throw new VisualTestException("the door's port has no Packager at its back");
        if (PackageHandover.ignoresRedstone(level, senderPackagerPos(dock)))
            throw new VisualTestException("the door's Packager ignores redstone");
        if (packagerPowered(level, dock))
            throw new VisualTestException("the door's Packager is powered before the lever was touched");
        String address = PackageHandover.addressAt(level, senderPackagerPos(dock));
        if (!ADDRESS.equals(address))
            throw new VisualTestException("the door's Packager reads the address '" + address + "', not '" + ADDRESS
                    + "'");
        if (!controller(level, dock).openRequests().isEmpty())
            throw new VisualTestException("the sending warehouse has open requests before anything was ordered: "
                    + controller(level, dock).openRequests());
        LOGGER.info(PREFIX + "door: CHECK the door at {} requests, asks for nothing, and its Packager reads '{}'",
                portPos(dock).toShortString(), address);
    }

    /** Fills the first warehouse through its own storage chests, exactly as a crane would have stored it. */
    private void stockSender(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = senderLayout(context.origin());
        Map<ItemKey, Long> stocked = Map.of();
        List<ItemStack> iron = List.of(ORDERED.toStack(IRON_IN_STOCK));
        for (int x = STORAGE_FIRST; x <= STORAGE_LAST; x++) {
            BlockPos chest = layout.rackPos(RackPosition.of(x, 0, Side.LEFT)).relative(layout.sideDirection(Side.LEFT));
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
            if (handler == null)
                throw new VisualTestException("no chest behind the storage location at " + chest);
            // The iron in the chest nearest the dock, so the crane carries it the whole way to the door.
            for (ItemStack stack : x == STORAGE_FIRST ? iron : OTHER_STOCK) {
                ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
                if (!rest.isEmpty())
                    throw new VisualTestException("the sending warehouse's chest refused " + rest);
            }
            stocked = SceneItemCensus.plusAll(stocked, x == STORAGE_FIRST ? iron : OTHER_STOCK);
        }
        expected = stocked;
    }

    // --- the belt ------------------------------------------------------------------------------------------------

    private static void aimBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos start = beltStart(context.origin());
        Direction actual = beltMovement(level, start);
        LOGGER.info(PREFIX + "door: the belt carries {} and must carry {}", actual, laneDirection());
        if (actual == laneDirection())
            return;
        CreativeMotorBlockEntity motor = motor(level, start.relative(AISLE.getOpposite()));
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static boolean beltAimed(MinecraftServer server, VisualContext context) {
        return beltMovement(server.overworld(), beltStart(context.origin())) == laneDirection();
    }

    private static Direction beltMovement(ServerLevel level, BlockPos pos) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, pos);
        if (belt == null)
            throw new VisualTestException("no belt at " + pos);
        return belt.getMovementFacing();
    }

    // --- the order on the server ---------------------------------------------------------------------------------

    /**
     * Server: the click made exactly one open request, for the door, of {@value #ORDER_AMOUNT} iron and nothing
     * delivered yet; the terminal has none. The door's own goggles list it, as they list every request of the port.
     */
    private static void checkRequestForDoor(MinecraftServer server, VisualContext context, int round) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        List<RetrievalRequest<ItemKey, BlockPos>> forDoor = controller.requestsFor(portPos(dock));
        List<RetrievalRequest<ItemKey, BlockPos>> forHere = controller.requestsFor(terminalPos(dock));
        if (forDoor.size() != 1 || !forDoor.getFirst().key().equals(ORDERED)
                || forDoor.getFirst().requested() != ORDER_AMOUNT || forDoor.getFirst().delivered() != 0
                || !forHere.isEmpty() || controller.openRequests().size() != 1)
            throw new VisualTestException("the door click of round " + round + " should be one request of "
                    + ORDER_AMOUNT + " " + ORDERED + " for the door and none for the terminal: door " + forDoor
                    + ", terminal " + forHere + ", all " + controller.openRequests());
        WarehouseOutputBlockEntity port = port(level, dock);
        port.onGoggleObserved();
        if (port.summary().openRequests() != 1 || port.summary().requestedItems() != ORDER_AMOUNT)
            throw new VisualTestException("the door's goggles should list the one request for " + ORDER_AMOUNT
                    + " items: " + port.summary());
        LOGGER.info(PREFIX + "door: PASS round {}: the click is the door's request for {} {}, and the door's goggles "
                + "list it", round, ORDER_AMOUNT, ORDERED);
    }

    /**
     * The port holds the order. Checked on every poll until then: the terminal's buffer stays empty, because a door
     * click must never deliver here.
     */
    private static boolean portFilled(MinecraftServer server, VisualContext context, int round) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseTerminalBlockEntity terminal = terminal(level, dock);
        if (terminal.bufferedItems().totalItems() != 0)
            throw new VisualTestException("a door click delivered into the terminal: " + terminal.bufferedItems());
        long inPort = port(level, dock).bufferedItems().count(ORDERED);
        if (inPort > ORDER_AMOUNT)
            throw new VisualTestException("the door's port holds " + inPort + " " + ORDERED + ", more than ordered");
        if (inPort < ORDER_AMOUNT)
            return false;
        StackerCraneBlockEntity crane = crane(level, dock);
        if (!crane.heldItems().isEmpty())
            return false;
        if (packagerPowered(level, dock))
            throw new VisualTestException("the door's Packager is powered before the player's lever was flipped");
        LOGGER.info(PREFIX + "door: PASS round {}: the door's port holds {} {} and the terminal holds nothing", round,
                inPort, ORDERED);
        return true;
    }

    /**
     * Flips the lever the way its own use does ({@code LeverBlock#pull}, what {@code useWithoutItem} runs on the
     * server), and checks that the Packager followed: on is the rising edge that makes it pack the port.
     */
    private static void lever(MinecraftServer server, VisualContext context, boolean on) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BlockPos pos = leverPos(dock);
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LeverBlock lever))
            throw new VisualTestException("the lever on the door's Packager is gone: " + state);
        if (state.getValue(LeverBlock.POWERED) == on)
            throw new VisualTestException("the lever is already " + (on ? "on" : "off"));
        lever.pull(state, level, pos, null);
        if (packagerPowered(level, dock) != on)
            throw new VisualTestException("the door's Packager did not follow the lever " + (on ? "on" : "off"));
        LOGGER.info(PREFIX + "door: the player's lever is {}, and the door's Packager is {}", on ? "on" : "off",
                on ? "powered" : "unpowered");
    }

    /** Takes the hopper under the door's Packager away; it must be empty, so nothing leaves the scene with it. */
    private static void takeTheDrainAway(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos drain = drainPos(context.origin());
        IItemHandler hopper = level.getCapability(Capabilities.ItemHandler.BLOCK, drain, null);
        if (hopper == null)
            throw new VisualTestException("no hopper under the door's Packager at " + drain);
        for (int slot = 0; slot < hopper.getSlots(); slot++) {
            if (!hopper.getStackInSlot(slot).isEmpty())
                throw new VisualTestException("the hopper under the door's Packager still holds "
                        + hopper.getStackInSlot(slot));
        }
        level.setBlockAndUpdate(drain, Blocks.AIR.defaultBlockState());
    }

    /** Puts the stack the waiting box will hold into the door's port, as a crane's drop would, and counts it in. */
    private void fillThePortForTheWait(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity port = port(server.overworld(), context.origin());
        if (!port.insert(LEFT_WAITING.toStack(LEFT_WAITING_AMOUNT), false).isEmpty())
            throw new VisualTestException("the door's port refused " + LEFT_WAITING_AMOUNT + " " + LEFT_WAITING);
        expected = SceneItemCensus.plus(expected, LEFT_WAITING, LEFT_WAITING_AMOUNT);
    }

    /** The door's Packager holds a box with its tray at rest, and nothing is under it to take the box. */
    private static boolean boxResting(MinecraftServer server, VisualContext context) {
        PackagerBlockEntity packager = packager(server.overworld(), context.origin());
        return !packager.heldBox.isEmpty() && packager.animationTicks == 0;
    }

    /**
     * Takes the waiting box out of the tray through the Packager's own item capability, which is what a hopper below
     * does ({@code PackagerItemHandler#extractItem}), checks that it is the stack that was put in, counts it out of the
     * scene and puts the hopper back.
     */
    private void takeTheWaitingBox(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        IItemHandler tray = level.getCapability(Capabilities.ItemHandler.BLOCK, senderPackagerPos(dock), null);
        if (tray == null)
            throw new VisualTestException("the door's Packager offers no item handler");
        ItemStack box = tray.extractItem(0, 1, false);
        if (!PackageItem.isPackage(box) || !ADDRESS.equals(PackageItem.getAddress(box)))
            throw new VisualTestException("the waiting box is not the door's package: " + box);
        List<ItemStack> contents = box.getOrDefault(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.EMPTY)
                .nonEmptyStream().toList();
        if (contents.size() != 1 || !ItemKey.of(contents.getFirst()).equals(LEFT_WAITING)
                || contents.getFirst().getCount() != LEFT_WAITING_AMOUNT)
            throw new VisualTestException("the waiting box should hold " + LEFT_WAITING_AMOUNT + " " + LEFT_WAITING
                    + ": " + contents);
        expected = SceneItemCensus.plus(expected, LEFT_WAITING, -LEFT_WAITING_AMOUNT);
        level.setBlockAndUpdate(drainPos(dock),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, laneDirection()));
        LOGGER.info(PREFIX + "door: took the waiting box addressed '{}' with {} {} out of the tray, and put the hopper "
                + "back", ADDRESS, LEFT_WAITING_AMOUNT, LEFT_WAITING);
    }

    /** The door's row is ready again once the box has left the tray: the port's memory of the wait is gone. */
    private static boolean doorReadyAgain(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        return controller.doorDirectory().terminalRows(level.getGameTime()).rows().stream()
                .anyMatch(row -> row.pos().equals(portPos(dock)) && row.state() == DoorState.READY);
    }

    private static boolean packagerPowered(ServerLevel level, BlockPos dock) {
        return level.getBlockState(senderPackagerPos(dock)).getValue(PackagerBlock.POWERED);
    }

    /** Server, ticks frozen: the box in the door's tray. */
    private static void checkBoxInTheTray(MinecraftServer server, VisualContext context) {
        PackagerBlockEntity packager = packager(server.overworld(), context.origin());
        if (packager.heldBox.isEmpty())
            throw new VisualTestException("the door's Packager holds no box although the client shows one");
        checkBox(packager.heldBox, "in the tray");
    }

    /**
     * Server, German round: polls until the door's Packager holds the box, then checks it and takes the census while
     * it is there — the box stays in the tray for the Packager's whole animation, so a poll per tick always sees it.
     */
    private boolean boxPacked(MinecraftServer server, VisualContext context, int round) {
        PackagerBlockEntity packager = packager(server.overworld(), context.origin());
        if (packager.heldBox.isEmpty())
            return false;
        checkBox(packager.heldBox, "in the tray");
        census(server, "with the box in the tray " + round);
        return true;
    }

    /**
     * A box that left the door: the sign's address, exactly the order inside, and no order data, because Wareworks
     * never writes any and a box the player's redstone makes carries none (ADR-065 D3).
     */
    private static void checkBox(ItemStack box, String where) {
        if (!PackageItem.isPackage(box))
            throw new VisualTestException("not a package " + where + ": " + box);
        String address = PackageItem.getAddress(box);
        if (!ADDRESS.equals(address))
            throw new VisualTestException("the box " + where + " is addressed '" + address + "', not '" + ADDRESS
                    + "'");
        List<ItemStack> contents = box.getOrDefault(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.EMPTY)
                .nonEmptyStream().toList();
        if (contents.size() != 1 || !ItemKey.of(contents.getFirst()).equals(ORDERED)
                || contents.getFirst().getCount() != ORDER_AMOUNT)
            throw new VisualTestException("the box " + where + " should hold " + ORDER_AMOUNT + " " + ORDERED + ": "
                    + contents);
        if (box.has(AllDataComponents.PACKAGE_ORDER_DATA) || box.has(AllDataComponents.PACKAGE_ORDER_CONTEXT))
            throw new VisualTestException("the box " + where + " carries order data: " + box.getComponents());
        LOGGER.info(PREFIX + "door: PASS the box {} is addressed '{}', holds {} {} and carries no order data", where,
                address, ORDER_AMOUNT, ORDERED);
    }

    /** Everything arrived: the second warehouse holds the iron of every round so far and its crane is done. */
    private static boolean arrived(MinecraftServer server, VisualContext context, int round) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = crane(level, farDock(dock));
        return controller(level, farDock(dock)).countOf(ORDERED) == (long) ORDER_AMOUNT * round
                && crane.craneState().phase() == CranePhase.IDLE && crane.heldItems().isEmpty();
    }

    /** What left, what travelled and what arrived, after round {@code round}. */
    private static void checkArrived(MinecraftServer server, VisualContext context, int round) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long left = controller(level, dock).countOf(ORDERED);
        long stored = controller(level, farDock(dock)).countOf(ORDERED);
        PackageUnpackSummary packages = observedPackages(input(level, dock));
        if (left != IRON_IN_STOCK - (long) ORDER_AMOUNT * round)
            throw new VisualTestException("the sending warehouse holds " + left + " " + ORDERED + " after round "
                    + round);
        if (stored != (long) ORDER_AMOUNT * round)
            throw new VisualTestException("the receiving warehouse holds " + stored + " " + ORDERED + " after round "
                    + round);
        if (packages.opened() < round)
            throw new VisualTestException("the in door reports " + packages.opened() + " packages opened after round "
                    + round);
        if (port(level, dock).bufferedItems().totalItems() != 0 || !packager(level, dock).heldBox.isEmpty())
            throw new VisualTestException("the door still holds something: port " + port(level, dock).bufferedItems()
                    + ", tray " + packager(level, dock).heldBox);
        if (terminal(level, dock).bufferedItems().totalItems() != 0)
            throw new VisualTestException("the terminal holds " + terminal(level, dock).bufferedItems());
        if (!controller(level, dock).openRequests().isEmpty())
            throw new VisualTestException("requests are still open: " + controller(level, dock).openRequests());
        LOGGER.info(PREFIX + "door: CHECK round {}: a box addressed '{}' carried {} {} from the door to the other "
                + "warehouse ({} left, {} stored, {} packages opened)", round, ADDRESS, ORDER_AMOUNT, ORDERED, left,
                stored, packages.opened());
    }

    private void census(MinecraftServer server, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        SceneItemCensus.assertEquals(level, censusBox, expected, "door: " + step);
        LOGGER.info(PREFIX + "door: census {} -> {}", step, SceneItemCensus.describe(expected));
    }

    // --- frozen moments (client thread, synced block entities) ------------------------------------------------------

    /** The sending crane on its way to the door with the iron on its arm, still some way off. */
    private static boolean carryingToTheDoor(VisualContext context) {
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                && crane.goggleInfo().heldCount() > 0 && remainingTravel(crane.craneState()) > MIN_REMAINING_TRAVEL
                && remainingTravel(crane.craneState()) <= MAX_REMAINING_TRAVEL).isPresent();
    }

    private static double remainingTravel(CraneState<?, ?> state) {
        return Math.max(Math.abs(state.target().x() - state.pose().x()),
                Math.abs(state.target().y() - state.pose().y()));
    }

    /** Down the aisle from beyond the door, looking back at the frozen crane and the iron on its arm. */
    private static CameraView carryView(VisualContext context) {
        CranePose pose = clientCrane(context).map(crane -> crane.craneState().pose())
                .orElseThrow(() -> new VisualTestException("no client crane to follow"));
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        return CameraView.of("door-carry", craneX + CARRY_AHEAD, pose.y() + CARRY_ABOVE, BLOCK_CENTER + CARRY_ASIDE,
                craneX + CARRY_LOOK_AHEAD, pose.y() + CARRY_LOOK_HEIGHT, BLOCK_CENTER + CARRY_LOOK_ASIDE);
    }

    /** The door's Packager with a box in its tray, caught while the tray is still moving. */
    private static boolean boxInTheTray(VisualContext context) {
        return clientPackager(context).filter(packager -> !packager.heldBox.isEmpty() && packager.animationTicks > 0
                && packager.animationTicks <= TRAY_HALF_OUT_TICKS).isPresent();
    }

    /** A Create package riding the belt, past its loading end. */
    private static boolean boxOnTheBelt(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BeltBlockEntity first = AllBlockEntityTypes.BELT.getNullable(level, beltStart(context.origin()));
        BeltBlockEntity belt = first == null ? null : first.getControllerBE();
        if (belt == null || belt.getInventory() == null)
            return false;
        for (TransportedItemStack transported : belt.getInventory().getTransportedItems())
            if (PackageItem.isPackage(transported.stack) && transported.beltPosition >= BELT_SHOT_POSITION)
                return true;
        return false;
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    private static Optional<PackagerBlockEntity> clientPackager(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(senderPackagerPos(context.origin())) instanceof PackagerBlockEntity packager
                ? Optional.of(packager) : Optional.empty();
    }

    // --- geometry ----------------------------------------------------------------------------------------------------

    private static BlockPos farDock(BlockPos dock) {
        return dock.offset(0, 0, FAR_DOCK_Z);
    }

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static BranchLayout senderLayout(BlockPos dock) {
        return layout(dock);
    }

    private static BranchLayout receiverLayout(BlockPos dock) {
        return layout(farDock(dock));
    }

    private static BlockPos portPos(BlockPos dock) {
        return senderLayout(dock).rackPos(PORT);
    }

    private static BlockPos terminalPos(BlockPos dock) {
        return senderLayout(dock).rackPos(TERMINAL);
    }

    private static BlockPos inputPos(BlockPos dock) {
        return receiverLayout(dock).rackPos(INPUT);
    }

    /** The door's Packager: behind the port, where a storage location's inventory would stand. */
    private static BlockPos senderPackagerPos(BlockPos dock) {
        BranchLayout layout = senderLayout(dock);
        return layout.rackPos(PORT).relative(layout.sideDirection(PORT.side()));
    }

    /** The in door's Packager: behind the input, where the belt funnel hands over. */
    private static BlockPos receiverPackagerPos(BlockPos dock) {
        BranchLayout layout = receiverLayout(dock);
        return layout.rackPos(INPUT).relative(layout.sideDirection(INPUT.side()));
    }

    /** The player's lever: on the door's Packager, on the face that points away from the port. */
    private static BlockPos leverPos(BlockPos dock) {
        BranchLayout layout = senderLayout(dock);
        return senderPackagerPos(dock).relative(layout.sideDirection(PORT.side()));
    }

    /** The sign that names the door: on a post along the aisle from the Packager. */
    private static BlockPos signPos(BlockPos dock) {
        return senderPackagerPos(dock).relative(AISLE);
    }

    /** The hopper that drains the door's Packager: directly underneath it. */
    private static BlockPos drainPos(BlockPos dock) {
        return senderPackagerPos(dock).below();
    }

    /** Which way the belt carries: from the sending warehouse towards the receiving one. */
    private static Direction laneDirection() {
        return Direction.SOUTH;
    }

    private static BlockPos beltStart(BlockPos dock) {
        return drainPos(dock).relative(laneDirection());
    }

    private static BlockPos beltEnd(BlockPos dock) {
        return receiverPackagerPos(dock).below().relative(AISLE.getOpposite());
    }

    private static AABB sceneBounds(BlockPos dock) {
        BlockPos from = dock.offset(-2, -1, -3);
        BlockPos to = dock.offset(RAILS + 2, CLEAR_HEIGHT, FAR_DOCK_Z + 3);
        return AABB.encapsulatingFullBlocks(from, to).inflate(CENSUS_MARGIN);
    }

    // --- lookups -----------------------------------------------------------------------------------------------------

    private static void openScreen(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        if (!terminal.openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
        LOGGER.info(PREFIX + "door: opened the terminal screen with {} item types in stock",
                terminal.stockSnapshot().size());
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the warehouse controller at " + dock + " is missing");
        return controller;
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the crane at " + dock + " is missing");
        return crane;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                terminalPos(dock));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal is missing");
        return terminal;
    }

    private static WarehouseOutputBlockEntity port(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level, portPos(dock));
        if (port == null)
            throw new VisualTestException("the door's port is missing");
        return port;
    }

    private static PackagerBlockEntity packager(ServerLevel level, BlockPos dock) {
        PackagerBlockEntity packager = AllBlockEntityTypes.PACKAGER.getNullable(level, senderPackagerPos(dock));
        if (packager == null)
            throw new VisualTestException("the door's Packager is missing");
        return packager;
    }

    /**
     * The in door's package numbers as the goggle tooltip would show them. A station's summary is throttled: it holds
     * whatever the last observation built, so a server-side read observes first.
     */
    private static PackageUnpackSummary observedPackages(WarehouseInputBlockEntity input) {
        input.onGoggleObserved();
        return input.summary().packages();
    }

    private static WarehouseInputBlockEntity input(ServerLevel level, BlockPos dock) {
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level, inputPos(dock));
        if (input == null)
            throw new VisualTestException("the in door's warehouse input is missing");
        return input;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }
}
