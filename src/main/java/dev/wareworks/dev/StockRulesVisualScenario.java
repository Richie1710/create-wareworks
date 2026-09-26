package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.client.gui.WarehouseStockKeeperScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperGoggleSummary;
import dev.wareworks.content.station.StockKeeperMenuLayout;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.StockKeeperScreenState;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.stock.StockAccess;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.core.terminal.TerminalAmounts;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "rules": the three numbers of a warehouse stock keeper doing their work in a real warehouse
 * ({@code docs/warehouse-system.md} §3.6, M15, issue #3).
 * <p>
 * One aisle holds a powered stacker crane, a <b>warehouse stock keeper</b> with three rules, a warehouse terminal, a
 * warehouse input fed by a <b>belt</b>, a warehouse output with a lever beside it, and the keeper's comparator wired to
 * a redstone lamp. Each rule is deliberately in a different state, and each of the three is <b>asserted on the server
 * before the shot that claims it</b>:
 * <ul>
 *   <li><b>minimum</b> — the aisle holds {@value #GOLD_IN_STOCK} gold ingots and the rule asks for
 *       {@value #GOLD_MINIMUM}: the lamp burns and the comparator reads exactly the number of unmet minimums. Lowering
 *       the minimum below the stock puts the lamp out again in the same run, which is the only way a screenshot of a
 *       lit lamp proves anything;</li>
 *   <li><b>maximum</b> — {@value #IRON_FED} iron ingots are fed into the input with a maximum of
 *       {@value #IRON_MAXIMUM}: exactly {@value #IRON_MAXIMUM} are stored, the rest stays in the input, and once the
 *       input's buffer is full the belt in front of it <b>backs up on purpose</b>. That is what the shot
 *       {@code backed-up} shows, and the run counts every iron ingot of the scene before and after;</li>
 *   <li><b>reserve</b> — {@value #DIAMOND_IN_STOCK} diamonds with a reserve of {@value #DIAMOND_RESERVE}: a redstone
 *       request at the output is served exactly {@code 24 − 16 = 8} of them and the next pulse is refused with
 *       {@link RequestRejection#RESERVED}, while a <b>player</b> at the terminal is served out of the reserve and is
 *       told so in the row.</li>
 * </ul>
 * <b>Why this scenario needs a real player.</b> A goggle tooltip is a GUI layer that Create only draws for a
 * non-spectator who looks at a block within reach ({@code GoggleOverlayRenderer}), so unlike every other screenshot
 * scenario this one runs with {@link VisualWorldProfile#playable} — a creative, flying player with the vanilla reach.
 * Its reach is set to 0 again for the world shots, because Create draws the value box of whatever the crosshair
 * targets even with the GUI hidden. Before a goggle shot the run waits until Create's overlay really has the block
 * under the crosshair and is fully faded in, and asserts the tooltip's own lines on the client block entity: a
 * screenshot cannot tell a right number from a wrong one.
 */
public final class StockRulesVisualScenario implements VisualScenario {
    public static final String NAME = "rules";

    /** Its own throw-away world: a creative player with the vanilla reach, deleted and rebuilt on every run. */
    private static final String WORLD_FOLDER = "wareworks_visual_rules";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    private static final int BELT_RPM = 32;

    /** Beside the dock, on the rack side the comparator and the lamp stand behind. */
    private static final RackPosition KEEPER = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition TERMINAL = RackPosition.of(2, 0, Side.RIGHT);
    /** The belt feeds this one; its buffer is what backs up when the maximum stops the crane. */
    private static final RackPosition INPUT = RackPosition.of(4, 0, Side.RIGHT);
    /** On the other rack side, clear of the belt, with a lever beside it for the redstone request. */
    private static final RackPosition OUTPUT = RackPosition.of(6, 0, Side.LEFT);
    /** Aisle positions whose rack holds storage, per side. */
    private static final List<Integer> STORAGE_LEFT = List.of(1, 2, 3, 4, 5, 7, 8);
    private static final List<Integer> STORAGE_RIGHT = List.of(3, 5, 6, 7, 8);
    private static final int STORAGE_LOCATIONS = 12;

    /** Blocks of belt in front of the input; long enough that a backed-up belt is unmistakable in a screenshot. */
    private static final int BELT_LENGTH = 6;

    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    /** Rule 0: the warehouse holds far less gold than the rule asks for, so the lamp burns and the comparator reads 1. */
    private static final int GOLD_IN_STOCK = 16;
    private static final int GOLD_MINIMUM = 64;
    /** What the minimum is lowered to for the {@code lamp-off} shot: below the stock, so the rule stops calling. */
    private static final int GOLD_MINIMUM_MET = 8;

    /** Rule 1: the crane stores exactly this many iron ingots of the {@value #IRON_FED} that are fed in. */
    private static final int IRON_MAXIMUM = 32;
    private static final int IRON_FED = 128;
    /** Iron put into the feed chest for the belt, once the input's buffer is full. */
    private static final int IRON_ON_THE_BELT = 64;
    /** Iron stacks offered to the input while topping it up; far more than its {@code inputBufferSlots} can hold. */
    private static final int TOP_UP_STACKS = 64;
    /** Items that must be standing on the belt before the {@code backed-up} shot is taken. */
    private static final int BACKED_UP_ITEMS = 3;

    /** Rule 2: everything above the reserve is what a redstone request may have, i.e. 24 − 16 = 8. */
    private static final int DIAMOND_IN_STOCK = 24;
    private static final int DIAMOND_RESERVE = 16;
    private static final int DIAMONDS_TO_AUTOMATION = DIAMOND_IN_STOCK - DIAMOND_RESERVE;
    /** What the output asks for on every pulse: more than automation may ever get, so the clamp is what answers. */
    private static final int OUTPUT_REQUEST_AMOUNT = 32;
    /** Narrows the terminal's grid to the reserved item, so the row the shot is about fills the frame. */
    private static final String DIAMOND_SEARCH = "diamond";

    private static final int RULES = 3;
    private static final int RULE_GOLD = 0;
    private static final int RULE_IRON = 1;
    private static final int RULE_DIAMOND = 2;


    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int RULE_TIMEOUT_TICKS = 300;
    private static final int STORE_TIMEOUT_TICKS = 2400;
    private static final int DELIVERY_TIMEOUT_TICKS = 2400;
    private static final int BACKED_UP_TIMEOUT_TICKS = 2400;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 6;
    /** The belt, the redstone pulses and two screens take longer than the harness's default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    // Cameras, relative to the lower corner of the dock block. The player is a real creature here, so every eye sits
    // at least 1.62 + 0.2 blocks above the floor: its feet must clear the floor and the 3-pixel rails, or the
    // teleport is undone by the collision and the camera never arrives.
    /** The whole scene from outside the rack wall: controller, aisle, keeper with its lamp, and the feed belt. */
    private static final CameraView OVERVIEW = CameraView.of("overview", 0.0, 3.8, 7.0, 4.2, 0.8, 1.2);
    /**
     * Along the belt into the warehouse input: the items standing still on it are the maximum at work. Almost in line
     * with the belt, because the barrels of the rack wall stand right beside its last block and hide the meeting point
     * from any camera further to the side.
     */
    private static final CameraView AT_BELT = CameraView.of("belt", 6.0, 2.8, 8.2, 4.4, 0.8, 1.6);
    /**
     * Across the keeper's redstone line rather than down it: keeper, comparator and lamp stand in a row, so a camera
     * behind the lamp would photograph the lamp alone.
     */
    private static final CameraView AT_LAMP = CameraView.of("lamp", -0.6, 2.4, 5.4, 1.5, 0.6, 2.1);
    /** In the aisle, two blocks from the keeper: close enough for its goggles and for its screen to stay open. */
    private static final CameraView AT_KEEPER = CameraView.of("keeper", 3.8, 2.0, 0.35, 1.5, 0.5, 1.05);
    private static final CameraView AT_TERMINAL = CameraView.of("terminal", 4.8, 2.0, 0.35, 2.5, 0.5, 1.05);
    /**
     * West of the controller, aimed at the <b>lower left corner</b> of its back face rather than at its middle: the
     * controller's aisle letter is a scroll value box on every face but the dock's, and a value box the crosshair
     * really hits turns from passive to active — which makes Create's goggle overlay bail out before it draws a
     * single line ({@code GoggleOverlayRenderer}). A quarter of a block off centre is outside its hit radius.
     */
    private static final CameraView AT_CONTROLLER = CameraView.of("controller", -3.6, 1.9, 0.4, -1.0, 0.25, 0.25);

    /** Iron put into the scene in total; the top-up is measured, so it is only known at run time. */
    private long ironInTheScene;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: goggles need a non-spectator with the vanilla reach. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("rules: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "rules run"))
                .server("rules: clear the area and place the motors", StockRulesVisualScenario::placeMotors)
                .server("rules: build the aisle, its stations, the racks, the redstone and the belt",
                        StockRulesVisualScenario::buildScene)
                .serverUntil("rules: wait until the controller has every member and the stock",
                        StockRulesVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("rules: aim the feed belt at the warehouse input", StockRulesVisualScenario::aimBelt)
                .serverUntil("rules: wait until the belt really carries towards the input",
                        StockRulesVisualScenario::beltAimed, RULE_TIMEOUT_TICKS)
                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so
                // it is lifted into the air first and only then made to fly.
                .server("rules: lift the player into the air", StockRulesVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("rules: the player flies and wears Engineer's Goggles", StockRulesVisualScenario::equipPlayer)
                .server("rules: write the three stock rules", StockRulesVisualScenario::writeRules)
                .serverUntil("rules: wait until the aisle enforces all three", StockRulesVisualScenario::rulesLive,
                        RULE_TIMEOUT_TICKS)

                // The maximum: feed more iron than the rule allows and watch the crane stop at exactly the number.
                .server("rules: feed " + IRON_FED + " iron ingots into the warehouse input",
                        StockRulesVisualScenario::feedIron)
                .serverUntil("rules: wait until the crane has stored everything it may",
                        StockRulesVisualScenario::ironStoredUpToMaximum, STORE_TIMEOUT_TICKS)
                .server("rules: check that storing stopped exactly at the maximum",
                        StockRulesVisualScenario::assertMaximumHeld)

                // The reserve: one redstone pulse gets what is above it, the next one gets nothing.
                .server("rules: ask the output for diamonds with a redstone pulse",
                        StockRulesVisualScenario::firstRedstonePulse)
                .serverUntil("rules: wait until the crane delivered what automation may have",
                        StockRulesVisualScenario::reserveDelivered, DELIVERY_TIMEOUT_TICKS)
                .server("rules: drop the lever again", (server, context) -> setLever(server, context, false))
                .waitTicks(SETTLE_TICKS)
                .server("rules: pulse the lever a second time", (server, context) -> setLever(server, context, true))
                .serverUntil("rules: wait until the second pulse is refused",
                        StockRulesVisualScenario::secondPulseRefused, RULE_TIMEOUT_TICKS)
                .server("rules: drop the lever again", (server, context) -> setLever(server, context, false))
                .server("rules: check that the redstone output stopped exactly at the reserve",
                        StockRulesVisualScenario::assertReserveHeld)

                // Every rule is now in the state its shot is about; the comparator says how many minimums are unmet.
                .serverUntil("rules: wait until all three rules are in the state the shots claim",
                        StockRulesVisualScenario::rulesInShotStates, RULE_TIMEOUT_TICKS)

                // The picture of the maximum: fill the input to the brim, then let the belt run into a closed door.
                .server("rules: fill the input's buffer to the brim", this::fillInput)
                .server("rules: put iron into the feed chest", StockRulesVisualScenario::fillFeedChest)
                .serverUntil("rules: wait until the belt in front of the input has backed up",
                        StockRulesVisualScenario::beltBackedUp, BACKED_UP_TIMEOUT_TICKS)
                .server("rules: count every iron ingot of the scene", this::assertIronConserved);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, so the world shots
        // are taken by a player who reaches nothing at all.
        reach(script, 0.0);
        script.shotFrom(OVERVIEW, "scene");
        script.camera(AT_BELT).shot("backed-up");
        script.camera(AT_LAMP).shot("lamp-on");
        if (pass == VisualPass.FLYWHEEL) {
            script.server("rules: lower the gold minimum below what the aisle holds",
                            (server, context) -> setGoldMinimum(server, context, GOLD_MINIMUM_MET))
                    .serverUntil("rules: wait until the comparator and the lamp went quiet",
                            (server, context) -> minimumsUnmet(server, context, 0), RULE_TIMEOUT_TICKS)
                    .until("rules: wait until the client sees the lamp go out",
                            context -> !clientLampLit(context), SYNC_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("lamp-off")
                    .server("rules: ask for the gold again",
                            (server, context) -> setGoldMinimum(server, context, GOLD_MINIMUM))
                    .serverUntil("rules: wait until the comparator calls for it again",
                            (server, context) -> minimumsUnmet(server, context, 1), RULE_TIMEOUT_TICKS)
                    .until("rules: wait until the client sees the lamp burn again",
                            StockRulesVisualScenario::clientLampLit, SYNC_TIMEOUT_TICKS);

            // The goggles. These are the only shots with the GUI shown, and the only ones that need the real reach.
            reach(script, vanillaReach());
            GoggleShots.shot(script, "rules", AT_KEEPER, "goggles-keeper", StockRulesVisualScenario::keeperPos,
                    StockRulesVisualScenario::keeperNumbersSynced, StockRulesVisualScenario::checkKeeperGoggles);
            GoggleShots.shot(script, "rules", AT_CONTROLLER, "goggles-controller",
                    StockRulesVisualScenario::controllerPos, StockRulesVisualScenario::controllerNumbersSynced,
                    StockRulesVisualScenario::checkControllerGoggles);
            reach(script, 0.0);

            // The keeper's own screen, opened the way a right-click opens it.
            script.camera(AT_KEEPER)
                    .server("rules: open the stock keeper screen", StockRulesVisualScenario::openKeeperScreen)
                    .until("rules: wait for the screen with its three rules",
                            StockRulesVisualScenario::keeperScreenReady, SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("rules: check every number the keeper screen shows",
                            StockRulesVisualScenario::checkKeeperScreen)
                    .shot("keeper-screen")
                    // Real mouse buttons on a number field, because the whole point of the three numbers is that a
                    // player can set them and cannot lose them by accident. A left-click switches an off number on, a
                    // second left-click on the same field must change nothing at all, and only a right-click switches
                    // it off again — asserted on the server, which is the only side that stores anything.
                    .client("rules: left-click the gold rule's reserve to switch it on",
                            context -> clickKeeperNumber(context, RULE_GOLD, StockKeeperMenuLayout.FIELD_RESERVE,
                                    false))
                    .serverUntil("rules: wait until the server stored the switched-on reserve",
                            (server, context) -> goldReserveIs(server, context, 0L), SCREEN_TIMEOUT_TICKS)
                    .client("rules: left-click the very same number again",
                            context -> clickKeeperNumber(context, RULE_GOLD, StockKeeperMenuLayout.FIELD_RESERVE,
                                    false))
                    .waitTicks(SETTLE_TICKS)
                    .server("rules: check that a left-click never deletes a number that is set",
                            StockRulesVisualScenario::assertLeftClickKeptTheNumber)
                    .client("rules: right-click it to switch it off again",
                            context -> clickKeeperNumber(context, RULE_GOLD, StockKeeperMenuLayout.FIELD_RESERVE, true))
                    .serverUntil("rules: wait until the reserve is off again",
                            (server, context) -> goldReserveIs(server, context, StockRule.UNSET), SCREEN_TIMEOUT_TICKS)
                    .client("rules: close the keeper screen", StockRulesVisualScenario::closeScreen)
                    .until("rules: wait until the keeper screen is closed",
                            context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);

            // The terminal: the same reserve seen from the side a player is on.
            script.camera(AT_TERMINAL)
                    .server("rules: open the terminal screen", StockRulesVisualScenario::openTerminalScreen)
                    .until("rules: wait for the terminal screen with its stock",
                            StockRulesVisualScenario::terminalScreenReady, SCREEN_TIMEOUT_TICKS)
                    .client("rules: check that the capped row names its cap",
                            StockRulesVisualScenario::checkCappedRow)
                    .client("rules: search for the reserved item",
                            context -> typeSearch(context, DIAMOND_SEARCH))
                    .waitTicks(SETTLE_TICKS)
                    // The row's tooltip is the point of the shot, and a tooltip is only drawn while a mouse rests on
                    // the cell, so the cursor is put there (the screen's own hit test confirms the cell).
                    .client("rules: rest the cursor on the reserved item",
                            StockRulesVisualScenario::hoverReservedCell)
                    .waitTicks(SETTLE_TICKS)
                    .client("rules: check the reserved row before a player touches it",
                            StockRulesVisualScenario::checkReservedRow)
                    .shot("terminal-reserve")
                    .client("rules: take the reserved diamonds as a player",
                            StockRulesVisualScenario::requestReservedDiamonds)
                    // M15 part 2: a click that reaches into a reserve is asked about before it is made, and the
                    // question names the number. Nothing has been requested yet at this point.
                    .until("rules: wait until the terminal asks about the reserve",
                            context -> screen(context).confirmationQuestion() != null, SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .client("rules: check what the confirmation names",
                            StockRulesVisualScenario::checkReserveConfirmation)
                    .shot("terminal-confirm")
                    .client("rules: confirm it", context -> screen(context).confirmRequest())
                    .until("rules: wait until the terminal accepted the request",
                            context -> screen(context).status().requestsHere() > 0, SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("terminal-request")
                    .serverUntil("rules: wait until the crane delivered them into the terminal",
                            StockRulesVisualScenario::reservedDiamondsDelivered, DELIVERY_TIMEOUT_TICKS)
                    .server("rules: check that the player really went below the reserve",
                            StockRulesVisualScenario::assertPlayerTookTheReserve)
                    .until("rules: wait until the screen shows the emptied, still ruled row",
                            StockRulesVisualScenario::ruledRowEmptyOnScreen, SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("terminal-delivered")
                    .client("rules: close the terminal screen", StockRulesVisualScenario::closeScreen)
                    .until("rules: wait until the terminal screen is closed",
                            context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS)
                    .client("rules: every check passed",
                            context -> LOGGER.info(PREFIX + "rules: ALL CHECKS PASSED (maximum, reserve, minimum, "
                                    + "comparator, goggles, keeper screen, terminal, confirmation)"));
        }
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        StringBuilder status = new StringBuilder();
        if (level != null) {
            BlockPos dock = context.origin();
            BlockState lamp = level.getBlockState(lampPos(dock));
            status.append("lamp=").append(lamp.hasProperty(RedstoneLampBlock.LIT)
                    ? String.valueOf(lamp.getValue(RedstoneLampBlock.LIT)) : "none");
            if (level.getBlockEntity(keeperPos(dock)) instanceof WarehouseStockKeeperBlockEntity keeper)
                status.append(" keeper=").append(keeper.summary().rules()).append("r/")
                        .append(keeper.summary().belowMinimum()).append("min/")
                        .append(keeper.summary().atMaximum()).append("max/")
                        .append(keeper.summary().atReserve()).append("res");
        }
        BlockPos hovered = GoggleOverlayRenderer.lastHovered;
        status.append(" hovered=").append(hovered == null ? "none" : hovered)
                .append(" hoverTicks=").append(GoggleOverlayRenderer.hoverTicks)
                .append(" gui=").append(!context.minecraft().options.hideGui);
        if (context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper)
            status.append(" screen=keeper rows=").append(describeRows(keeper.state()));
        else if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            status.append(String.format(Locale.ROOT, " screen=terminal shown=%d ruled=%d requestsHere=%d",
                    terminal.visibleEntries().size(),
                    terminal.matchingEntries().stream().filter(StockLine::ruled).count(),
                    terminal.status().requestsHere()));
        else if (context.minecraft().screen != null)
            status.append(" screen=").append(context.minecraft().screen.getClass().getSimpleName());
        return status.toString();
    }

    private static String describeRows(StockKeeperScreenState state) {
        StringBuilder rows = new StringBuilder();
        for (StockKeeperScreenState.RowView row : state.rows()) {
            if (row.key().isEmpty())
                continue;
            rows.append(rows.isEmpty() ? "" : ",").append(row.row()).append(':').append(row.status()).append('/')
                    .append(row.minimum()).append('/').append(row.maximum()).append('/').append(row.reserve());
        }
        return "[" + rows + "]";
    }

    // --- build (server thread) --------------------------------------------------------------------------------------

    private static void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        Direction right = AISLE.getClockWise();
        for (BlockPos pos : BlockPos.betweenClosed(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, BELT_LENGTH + CLEAR_MARGIN)
                        .above(CLEAR_HEIGHT)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(controllerPos(dock),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(KEEPER), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, inward(layout, KEEPER)));
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL)));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, inward(layout, OUTPUT)));
        // A lever on the floor beside the output reports signal 15 to every side, so the station sees the rising edge
        // of a real redstone request (LeverBlock#getSignal), exactly as in the showcase world.
        level.setBlockAndUpdate(leverPos(dock), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, AISLE)
                .setValue(LeverBlock.POWERED, false));

        // The keeper's redstone line: a comparator reading the keeper (its FACING points at what it reads, DiodeBlock)
        // and a lamp in front of it. Nothing else in the scene emits redstone.
        level.setBlockAndUpdate(comparatorPos(dock), Blocks.COMPARATOR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, AISLE.getClockWise().getOpposite()));
        level.setBlockAndUpdate(lampPos(dock), Blocks.REDSTONE_LAMP.defaultBlockState());

        buildStorage(level, layout);
        buildFeed(level, dock);
    }

    /** Storage behind both rack planes: barrels rather than chests, so nothing ever merges into a double chest. */
    private static void buildStorage(ServerLevel level, AisleLayout layout) {
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x : side == Side.LEFT ? STORAGE_LEFT : STORAGE_RIGHT) {
                BlockPos rack = layout.rackPos(RackPosition.of(x, 0, side));
                level.setBlockAndUpdate(rack.relative(outward), Blocks.BARREL.defaultBlockState());
                level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                        .setValue(WarehouseInterfaceBlock.FACING, outward));
            }
        }
        // The stock the minimum and the reserve are judged against; the maximum's item arrives through the input.
        Direction left = layout.sideDirection(Side.LEFT);
        insertInto(level, layout.rackPos(RackPosition.of(STORAGE_LEFT.getFirst(), 0, Side.LEFT)).relative(left),
                GOLD.toStack(GOLD_IN_STOCK));
        insertInto(level, layout.rackPos(RackPosition.of(STORAGE_LEFT.get(1), 0, Side.LEFT)).relative(left),
                DIAMOND.toStack(DIAMOND_IN_STOCK));
    }

    /**
     * The feed: a chest over a hopper that pushes onto a belt, and the belt runs into the warehouse input. This is the
     * one thing a screenshot of the maximum can show — while the rule bites, the crane stops draining the input, the
     * input refuses, and the items simply stand still on the belt.
     */
    private static void buildFeed(ServerLevel level, BlockPos dock) {
        BlockPos start = beltStartPos(dock);
        BlockPos end = beltEndPos(dock);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the feed belt was not created between " + start + " and " + end);
        // A belt along the rack side turns on the axis across it, so its motor sits beside the belt's first pulley.
        level.setBlockAndUpdate(beltMotorPos(dock), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE.getOpposite()));
        motor(level, beltMotorPos(dock)).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(feedHopperPos(dock), Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, AISLE.getClockWise().getOpposite()));
        level.setBlockAndUpdate(feedChestPos(dock),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, AISLE.getClockWise()));
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == STORAGE_LOCATIONS
                && controller.inputStations().size() == 1
                // The terminal counts as an output as well (ADR-018).
                && controller.outputStations().size() == 2 && crane.isControllerLinked() && crane.aisleLength() == RAILS
                && controller.countOf(GOLD) == GOLD_IN_STOCK && controller.countOf(DIAMOND) == DIAMOND_IN_STOCK;
    }

    /** Which way a belt carries follows the sign of its rotation, so it is read back and the motor reversed if wrong. */
    private static void aimBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        Direction wanted = AISLE.getClockWise().getOpposite();
        Direction actual = beltMovement(level, dock);
        LOGGER.info(PREFIX + "rules: the feed belt carries {} and must carry {}", actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motor(level, beltMotorPos(dock));
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static boolean beltAimed(MinecraftServer server, VisualContext context) {
        return beltMovement(server.overworld(), context.origin()) == AISLE.getClockWise().getOpposite();
    }

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(4);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /**
     * The player of a goggle shot: flying (so a camera view in mid-air holds), wearing Engineer's Goggles (Create asks
     * {@code GogglesItem.isWearingGoggles}, which reads the head slot) and holding nothing, so no item covers a corner
     * of a shot with the GUI shown.
     */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    // --- the three rules ----------------------------------------------------------------------------------------------

    private static void writeRules(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        rule(keeper, RULE_GOLD, GOLD, StockKeeperRules.FIELD_MINIMUM, GOLD_MINIMUM);
        rule(keeper, RULE_IRON, IRON, StockKeeperRules.FIELD_MAXIMUM, IRON_MAXIMUM);
        rule(keeper, RULE_DIAMOND, DIAMOND, StockKeeperRules.FIELD_RESERVE, DIAMOND_RESERVE);
        LOGGER.info(PREFIX + "rules: the keeper holds {} rules", keeper.rules().ruleCount());
    }

    private static void rule(WarehouseStockKeeperBlockEntity keeper, int row, ItemKey key, int field, long value) {
        if (!keeper.editRule(row, StockKeeperRules.FIELD_ITEM, key, 0L).changed()
                || !keeper.editRule(row, field, null, value).changed())
            throw new VisualTestException("the rule in row " + row + " could not be written");
    }

    /** The controller's own copy governs all three, which is what enforcement reads. */
    private static boolean rulesLive(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context.origin());
        return controller.stockRules().governingCount() == RULES;
    }

    private static void setGoldMinimum(MinecraftServer server, VisualContext context, long minimum) {
        keeper(server.overworld(), context.origin()).editRule(RULE_GOLD, StockKeeperRules.FIELD_MINIMUM, null, minimum);
    }

    /** The keeper's comparator value is the number of its governing rules that are below their minimum. */
    private static boolean minimumsUnmet(MinecraftServer server, VisualContext context, int expected) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseStockKeeperBlockEntity keeper = keeper(level, dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        int below = 0;
        for (int rule = 0; rule < RULES; rule++) {
            if (controller.stockRuleStatus(keeperPos(dock), rule) == StockRuleStatus.BELOW_MINIMUM)
                below++;
        }
        if (below != expected || keeper.comparatorSignal() != expected)
            return false;
        // The redstone line itself, not only the block entity: the comparator's own output and the lamp it drives.
        if (!(level.getBlockEntity(comparatorPos(dock)) instanceof ComparatorBlockEntity comparator))
            throw new VisualTestException("no comparator beside the stock keeper at " + comparatorPos(dock));
        if (comparator.getOutputSignal() != expected)
            return false;
        BlockState lamp = level.getBlockState(lampPos(dock));
        if (!lamp.is(Blocks.REDSTONE_LAMP) || lamp.getValue(RedstoneLampBlock.LIT) != (expected > 0))
            return false;
        LOGGER.info(PREFIX + "rules: {} rule(s) below their minimum, comparator {}, lamp {}", below,
                comparator.getOutputSignal(), lamp.getValue(RedstoneLampBlock.LIT) ? "lit" : "dark");
        return true;
    }

    // --- the maximum --------------------------------------------------------------------------------------------------

    private static void feedIron(MinecraftServer server, VisualContext context) {
        insertInto(server.overworld(), inputPos(context.origin()), IRON.toStack(IRON_FED));
    }

    private static boolean ironStoredUpToMaximum(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        return craneIdle(crane) && controller(level, dock).countOf(IRON) == IRON_MAXIMUM;
    }

    /**
     * Exactly the maximum is in the racks, the rest is still in the input, and the planner says why. A picture of a
     * belt that backed up for any other reason would be worthless, so the reason is read too.
     */
    private static void assertMaximumHeld(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long stored = controller.countOf(IRON);
        long waiting = input(level, dock).bufferedItems().count(IRON);
        if (stored != IRON_MAXIMUM || waiting != IRON_FED - IRON_MAXIMUM)
            throw new VisualTestException("the maximum stored " + stored + " iron and left " + waiting
                    + " in the input, expected " + IRON_MAXIMUM + " and " + (IRON_FED - IRON_MAXIMUM));
        if (controller.stockRules().headroom(IRON, controller.stockLevelsOf(IRON)) != 0L)
            throw new VisualTestException("the iron rule still leaves headroom at its maximum");
        Optional<NoJobReason> reason = controller.lastPlanReason();
        if (reason.filter(NoJobReason.AT_MAXIMUM::equals).isEmpty())
            throw new VisualTestException("the controller stopped planning for the reason " + reason
                    + ", expected " + NoJobReason.AT_MAXIMUM);
        if (controller.stockRuleStatus(keeperPos(dock), RULE_IRON) != StockRuleStatus.AT_MAXIMUM)
            throw new VisualTestException("the iron rule does not report itself at its maximum");
        LOGGER.info(PREFIX + "rules: CHECK the maximum holds: {} of {} iron stored, {} left in the input, last plan {}",
                stored, IRON_FED, waiting, NoJobReason.AT_MAXIMUM);
    }

    /** Offers the input far more iron than it can hold, so it refuses every further insertion from the belt. */
    private void fillInput(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        IItemHandler handler = handlerAt(level, inputPos(dock));
        long added = 0;
        for (int stack = 0; stack < TOP_UP_STACKS; stack++) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, IRON.toStack(IRON.getMaxStackSize()), false);
            added += IRON.getMaxStackSize() - rest.getCount();
            if (!rest.isEmpty())
                break;
        }
        ironInTheScene = IRON_FED + added + IRON_ON_THE_BELT;
        long buffered = input(level, dock).bufferedItems().count(IRON);
        if (!inputRefusesIron(level, dock))
            throw new VisualTestException("the input still accepts iron after " + buffered + " of it went in");
        LOGGER.info(PREFIX + "rules: the input holds {} iron and accepts no more; {} iron in the scene in total",
                buffered, ironInTheScene);
    }

    private static void fillFeedChest(MinecraftServer server, VisualContext context) {
        insertInto(server.overworld(), feedChestPos(context.origin()), IRON.toStack(IRON_ON_THE_BELT));
    }

    /** The belt really is standing still in front of a door the maximum closed, and the feed behind it is stalled. */
    private static boolean beltBackedUp(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long onBelt = beltItems(level, dock);
        long inChest = countAt(level, feedChestPos(dock), Items.IRON_INGOT);
        if (onBelt < BACKED_UP_ITEMS || inChest <= 0 || !inputRefusesIron(level, dock))
            return false;
        LOGGER.info(PREFIX + "rules: CHECK the belt backed up: {} items standing on it, {} still in the feed chest, "
                + "the input takes nothing more", onBelt, inChest);
        return true;
    }

    /** Whether the warehouse input takes no further iron at all, i.e. whether the belt in front of it must back up. */
    private static boolean inputRefusesIron(ServerLevel level, BlockPos dock) {
        return !ItemHandlerHelper.insertItem(handlerAt(level, inputPos(dock)), IRON.toStack(1), true).isEmpty();
    }

    /**
     * Every iron ingot of the scene is still there: in the racks, in the input, on the belt, in the feed, in the
     * crane's head or on the floor. The maximum holds items back; it never eats them.
     */
    private void assertIronConserved(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long stored = controller(level, dock).countOf(IRON);
        long buffered = input(level, dock).bufferedItems().count(IRON);
        long onBelt = beltItems(level, dock);
        long inChest = countAt(level, feedChestPos(dock), Items.IRON_INGOT);
        long inHopper = countAt(level, feedHopperPos(dock), Items.IRON_INGOT);
        long held = craneHeld(level, dock);
        long dropped = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, sceneBounds(dock))) {
            if (entity.getItem().is(Items.IRON_INGOT))
                dropped += entity.getItem().getCount();
        }
        long total = stored + buffered + onBelt + inChest + inHopper + held + dropped;
        if (stored != IRON_MAXIMUM || total != ironInTheScene)
            throw new VisualTestException("iron: " + stored + " stored (expected " + IRON_MAXIMUM + "), " + buffered
                    + " in the input, " + onBelt + " on the belt, " + inChest + " in the chest, " + inHopper
                    + " in the hopper, " + held + " in the crane, " + dropped + " on the floor: " + total + " of "
                    + ironInTheScene);
        LOGGER.info(PREFIX + "rules: CHECK every iron ingot accounted for: {} stored, {} in the input, {} on the belt, "
                + "{} in the feed, {} in total", stored, buffered, onBelt, inChest + inHopper, total);
    }

    // --- the reserve --------------------------------------------------------------------------------------------------

    /** Sets what the output asks for on a pulse and flips the lever, i.e. a real redstone rising edge. */
    private static void firstRedstonePulse(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseOutputBlockEntity output = output(level, context.origin());
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output has no filtering behaviour");
        if (!filter.setFilter(DIAMOND.toStack(1)))
            throw new VisualTestException("the warehouse output refused the request filter");
        filter.count = OUTPUT_REQUEST_AMOUNT; // after setFilter, which may clamp the count
        setLever(server, context, true);
    }

    private static boolean reserveDelivered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        return crane != null && craneIdle(crane)
                && output(level, dock).bufferedItems().count(DIAMOND) == DIAMONDS_TO_AUTOMATION;
    }

    private static boolean secondPulseRefused(MinecraftServer server, VisualContext context) {
        return output(server.overworld(), context.origin()).lastRejection().isPresent();
    }

    /**
     * The redstone output stopped exactly at the reserve: it got everything above it, the next pulse was refused with
     * the reason that says why, and what is left is what a player may still take.
     */
    private static void assertReserveHeld(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long delivered = output(level, dock).bufferedItems().count(DIAMOND);
        long left = controller.countOf(DIAMOND);
        RequestRejection rejection = output(level, dock).lastRejection().orElse(null);
        if (delivered != DIAMONDS_TO_AUTOMATION || left != DIAMOND_RESERVE)
            throw new VisualTestException("automation got " + delivered + " diamonds and left " + left
                    + " in stock, expected " + DIAMONDS_TO_AUTOMATION + " and " + DIAMOND_RESERVE);
        if (rejection != RequestRejection.RESERVED)
            throw new VisualTestException("the second pulse was refused with " + rejection + ", expected "
                    + RequestRejection.RESERVED);
        if (controller.availableTo(StockAccess.AUTOMATION, DIAMOND) != 0L)
            throw new VisualTestException("automation may still claim diamonds above the reserve");
        if (controller.availableTo(StockAccess.PLAYER, DIAMOND) != DIAMOND_RESERVE)
            throw new VisualTestException("a player should still be offered the whole reserve");
        LOGGER.info(PREFIX + "rules: CHECK the reserve holds: automation got {} diamonds, was then refused with {}, "
                + "and the {} reserved ones are still offered to a player", delivered, rejection, left);
    }

    /** Every rule in the state its shot is about, plus the lamp and the comparator that say so in the world. */
    private static boolean rulesInShotStates(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(server.overworld(), dock);
        StockRuleStatus[] expected = {StockRuleStatus.BELOW_MINIMUM, StockRuleStatus.AT_MAXIMUM,
                StockRuleStatus.AT_RESERVE};
        for (int rule = 0; rule < expected.length; rule++) {
            if (controller.stockRuleStatus(keeperPos(dock), rule) != expected[rule])
                return false;
        }
        return minimumsUnmet(server, context, 1);
    }

    // --- goggles ------------------------------------------------------------------------------------------------------

    /** The keeper's synced summary on the client already says what the shot is about to claim. */
    private static boolean keeperNumbersSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null
                || !(level.getBlockEntity(keeperPos(context.origin())) instanceof WarehouseStockKeeperBlockEntity keeper))
            return false;
        StockKeeperGoggleSummary summary = keeper.summary();
        return summary.linked() && summary.rules() == RULES && summary.belowMinimum() == 1
                && summary.atMaximum() == 1 && summary.atReserve() == 1;
    }

    private static boolean controllerNumbersSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller))
            return false;
        ControllerGoggleSummary summary = controller.summary();
        return summary.stockRules() == RULES && summary.rulesBelowMinimum() == 1 && summary.rulesAtMaximum() == 1;
    }

    private static void checkKeeperGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, keeperPos(context.origin()));
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_WAREHOUSE_STOCK_KEEPER).getString());
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_RULES, RULES));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_BELOW_MINIMUM, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_AT_MAXIMUM, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_KEEPER_AT_RESERVE, 1));
        LOGGER.info(PREFIX + "rules: CHECK the keeper's goggles say {}", lines);
    }

    private static void checkControllerGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_STOCK_RULES, RULES));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_RULES_BELOW_MINIMUM, 1));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_RULES_AT_MAXIMUM, 1));
        LOGGER.info(PREFIX + "rules: CHECK the controller's goggles say {}", lines);
    }

    // --- the keeper's screen -------------------------------------------------------------------------------------------

    private static void openKeeperScreen(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        if (!keeper.openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the stock keeper screen could not be opened for the camera player");
    }

    private static boolean keeperScreenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper && keeper.state().linked()
                && keeper.state().rows().size() > RULE_DIAMOND;
    }

    /** Every number the shot shows is the server's, and every row is in the state the scenario set up. */
    private static void checkKeeperScreen(VisualContext context) {
        StockKeeperScreenState state = keeperScreen(context).state();
        requireRow(state, RULE_GOLD, GOLD, StockRuleStatus.BELOW_MINIMUM, GOLD_MINIMUM, StockRule.UNSET,
                StockRule.UNSET);
        requireRow(state, RULE_IRON, IRON, StockRuleStatus.AT_MAXIMUM, StockRule.UNSET, IRON_MAXIMUM, StockRule.UNSET);
        requireRow(state, RULE_DIAMOND, DIAMOND, StockRuleStatus.AT_RESERVE, StockRule.UNSET, StockRule.UNSET,
                DIAMOND_RESERVE);
        StockKeeperScreenState.RowView gold = state.row(RULE_GOLD).orElseThrow();
        if (gold.shortfall() != GOLD_MINIMUM - GOLD_IN_STOCK)
            throw new VisualTestException("the gold row is short of " + gold.shortfall() + ", expected "
                    + (GOLD_MINIMUM - GOLD_IN_STOCK));
        StockKeeperScreenState.RowView diamond = state.row(RULE_DIAMOND).orElseThrow();
        if (diamond.heldBack() != DIAMOND_RESERVE)
            throw new VisualTestException("the diamond row holds back " + diamond.heldBack() + ", expected "
                    + DIAMOND_RESERVE);
        LOGGER.info(PREFIX + "rules: CHECK the keeper screen shows {}", describeRows(state));
    }

    /** Clicks one of the three number fields of a rule row with a real mouse button. */
    private static void clickKeeperNumber(VisualContext context, int row, int field, boolean rightButton) {
        ScreenInput.Point point = ScreenInput.keeperNumberField(keeperScreen(context), row, field);
        if (rightButton)
            ScreenInput.rightClick(context.minecraft(), point);
        else
            ScreenInput.click(context.minecraft(), point);
        LOGGER.info(PREFIX + "rules: {}-clicked row {} field {} at {}", rightButton ? "right" : "left", row, field,
                point);
    }

    private static long goldReserve(MinecraftServer server, VisualContext context) {
        return keeper(server.overworld(), context.origin()).rules().ruleAt(RULE_GOLD)
                .map(StockRule::reserve).orElse(StockRule.UNSET);
    }

    private static boolean goldReserveIs(MinecraftServer server, VisualContext context, long expected) {
        return goldReserve(server, context) == expected;
    }

    /** The second left-click on a number that is already set must leave it exactly as it was. */
    private static void assertLeftClickKeptTheNumber(MinecraftServer server, VisualContext context) {
        long reserve = goldReserve(server, context);
        if (reserve != 0L)
            throw new VisualTestException("a second left-click changed the reserve to " + reserve
                    + "; a left-click must never delete a number a player set");
        LOGGER.info(PREFIX + "rules: CHECK a left-click on a number that is set changes nothing (reserve {})", reserve);
    }

    private static void requireRow(StockKeeperScreenState state, int row, ItemKey key, StockRuleStatus status,
            long minimum, long maximum, long reserve) {
        StockKeeperScreenState.RowView view = state.row(row)
                .orElseThrow(() -> new VisualTestException("the keeper screen has no row " + row));
        if (view.key().filter(key::equals).isEmpty() || view.status() != status || view.minimum() != minimum
                || view.maximum() != maximum || view.reserve() != reserve)
            throw new VisualTestException("row " + row + " shows " + view + ", expected " + key + " " + status + " "
                    + minimum + "/" + maximum + "/" + reserve);
    }

    private static WarehouseStockKeeperScreen keeperScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper)
            return keeper;
        throw new VisualTestException("the stock keeper screen is not open (screen: " + context.minecraft().screen + ")");
    }

    // --- the terminal ------------------------------------------------------------------------------------------------

    private static void openTerminalScreen(MinecraftServer server, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
        if (!terminal.openScreen(context.serverPlayer(server)))
            throw new VisualTestException("the terminal screen could not be opened for the camera player");
    }

    private static boolean terminalScreenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                && terminal.entry(DIAMOND).isPresent();
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + context.minecraft().screen + ")");
    }

    private static void typeSearch(VisualContext context, String text) {
        WarehouseTerminalScreen terminal = screen(context);
        terminal.focusSearch();
        for (char typed : text.toCharArray())
            terminal.charTyped(typed, 0);
    }

    /**
     * The row a player sees before they click: the reserve is a <b>part of</b> what they may claim, automation would
     * get nothing more, and the row says in words that a click goes below the reserve. That sentence is the user's
     * decision for M15 and the one thing a photograph of a coloured badge could never prove.
     */
    private static void checkReservedRow(VisualContext context) {
        StockLine<ItemKey> line = screen(context).entry(DIAMOND)
                .orElseThrow(() -> new VisualTestException("the terminal does not show the reserved item"));
        if (line.rule().filter(StockRuleStatus.AT_RESERVE::equals).isEmpty())
            throw new VisualTestException("the reserved row reports " + line.rule() + ", expected AT_RESERVE");
        if (line.ruleReserved() != DIAMOND_RESERVE || line.available() != DIAMOND_RESERVE)
            throw new VisualTestException("the reserved row shows " + line.ruleReserved() + " of " + line.available()
                    + " held back, expected " + DIAMOND_RESERVE + " of " + DIAMOND_RESERVE);
        if (line.availableToAutomation() != 0L)
            throw new VisualTestException("automation should get nothing more of " + line.name());
        List<String> tooltip = screen(context).itemTooltip(line).stream().map(Component::getString).toList();
        String hint = WareworksLang.translateDirect(WareworksLang.TERMINAL_BELOW_RESERVE).getString();
        if (tooltip.stream().noneMatch(text -> text.contains(hint)))
            throw new VisualTestException("the row does not say that a request goes below the reserve: " + tooltip);
        if (screen(context).visibleEntries().stream().noneMatch(entry -> entry.key().equals(DIAMOND)))
            throw new VisualTestException("the reserved item is not in the visible grid");
        LOGGER.info(PREFIX + "rules: CHECK the terminal row: {} of {} available are reserved, automation gets {}, "
                + "and the row says: {}", line.ruleReserved(), line.available(), line.availableToAutomation(), tooltip);
    }

    /**
     * The row of the item a <b>maximum</b> governs: it names the cap in words, the way the reserved row names the
     * reserve. Both halves of a rule a player can still act on have to be readable from the row they clicked, and the
     * cap is the only one of the three numbers that no other terminal surface ever says (M15 part 2).
     */
    private static void checkCappedRow(VisualContext context) {
        StockLine<ItemKey> line = screen(context).entry(IRON)
                .orElseThrow(() -> new VisualTestException("the terminal does not show the capped item"));
        if (line.ruleMaximum() != IRON_MAXIMUM)
            throw new VisualTestException("the capped row reports a maximum of " + line.ruleMaximum() + ", expected "
                    + IRON_MAXIMUM);
        List<String> tooltip = screen(context).itemTooltip(line).stream().map(Component::getString).toList();
        String cap = WareworksLang.translateDirect(WareworksLang.TERMINAL_RULE_MAXIMUM,
                LangNumberFormat.format(IRON_MAXIMUM)).getString();
        if (tooltip.stream().noneMatch(text -> text.contains(cap)))
            throw new VisualTestException("the capped row does not name its maximum: " + tooltip);
        LOGGER.info(PREFIX + "rules: CHECK the capped terminal row says: {}", tooltip);
    }

    /** Puts the cursor on the reserved item's cell, so the shot carries the row's own words rather than a log line. */
    private static void hoverReservedCell(VisualContext context) {
        ScreenInput.hover(context.minecraft(), ScreenInput.terminalCell(screen(context), reservedCell(context)));
    }

    private static int reservedCell(VisualContext context) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++) {
            if (visible.get(cell).key().equals(DIAMOND))
                return cell;
        }
        throw new VisualTestException("the reserved item is not in the visible grid");
    }

    /** A shift-click on the reserved item: a player is served out of the reserve a redstone request was refused. */
    private static void requestReservedDiamonds(VisualContext context) {
        if (!screen(context).requestVisible(reservedCell(context), TerminalAmounts.Click.STACK))
            throw new VisualTestException("the reserved item could not be requested");
    }

    /**
     * The confirmation the terminal puts up before a click goes below a reserve (M15 part 2): it has to be there, and
     * it has to name the number, because "are you sure?" alone is not something a player can act on.
     */
    private static void checkReserveConfirmation(VisualContext context) {
        Component question = screen(context).confirmationQuestion();
        if (question == null)
            throw new VisualTestException("the terminal asked nothing before going below the reserve");
        String text = question.getString();
        if (!text.contains(String.valueOf(DIAMOND_RESERVE)))
            throw new VisualTestException("the confirmation does not name the " + DIAMOND_RESERVE
                    + " reserved diamonds: " + text);
        if (screen(context).status().requestsHere() > 0)
            throw new VisualTestException("the request was made before the player answered the question");
        LOGGER.info(PREFIX + "rules: CHECK the terminal asks before a click goes below the reserve: \"{}\"", text);
    }

    private static boolean reservedDiamondsDelivered(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return terminal(level, dock).bufferedItems().count(DIAMOND) == DIAMOND_RESERVE;
    }

    private static void assertPlayerTookTheReserve(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long left = controller(level, dock).countOf(DIAMOND);
        long inTerminal = terminal(level, dock).bufferedItems().count(DIAMOND);
        if (left != 0L || inTerminal != DIAMOND_RESERVE)
            throw new VisualTestException("the player got " + inTerminal + " diamonds and left " + left
                    + " in stock, expected " + DIAMOND_RESERVE + " and 0");
        LOGGER.info(PREFIX + "rules: CHECK a player went below the reserve: all {} reserved diamonds were delivered "
                + "to the terminal, while a redstone pulse had been refused with {}", inTerminal,
                RequestRejection.RESERVED);
    }

    /** The row of the emptied item is still there, because a rule governs it (M15.5: a ruled row is pinned). */
    private static boolean ruledRowEmptyOnScreen(VisualContext context) {
        Optional<StockLine<ItemKey>> line = screen(context).entry(DIAMOND);
        return line.filter(entry -> entry.total() == 0L && entry.ruled()).isPresent();
    }

    private static void closeScreen(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player != null)
            player.closeContainer();
    }

    // --- the camera player --------------------------------------------------------------------------------------------

    /** The player's block reach, set and waited for through the shared goggle helper ({@link GoggleShots#reach}). */
    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, "rules", range);
    }

    private static double vanillaReach() {
        return GoggleShots.vanillaReach();
    }

    private static boolean clientLampLit(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockState lamp = level.getBlockState(lampPos(context.origin()));
        return lamp.hasProperty(RedstoneLampBlock.LIT) && lamp.getValue(RedstoneLampBlock.LIT);
    }

    // --- positions ----------------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    /** The direction a member at {@code rack} faces: towards the aisle, like every other station. */
    private static Direction inward(AisleLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    private static BlockPos keeperPos(BlockPos dock) {
        return layout(dock).rackPos(KEEPER);
    }

    private static BlockPos terminalPos(BlockPos dock) {
        return layout(dock).rackPos(TERMINAL);
    }

    private static BlockPos inputPos(BlockPos dock) {
        return layout(dock).rackPos(INPUT);
    }

    private static BlockPos outputPos(BlockPos dock) {
        return layout(dock).rackPos(OUTPUT);
    }

    private static BlockPos leverPos(BlockPos dock) {
        return outputPos(dock).relative(layout(dock).sideDirection(OUTPUT.side()));
    }

    private static BlockPos comparatorPos(BlockPos dock) {
        return keeperPos(dock).relative(layout(dock).sideDirection(KEEPER.side()));
    }

    private static BlockPos lampPos(BlockPos dock) {
        return comparatorPos(dock).relative(layout(dock).sideDirection(KEEPER.side()));
    }

    /** Last belt block, one step outside the rack wall: the block it hands its items to is the warehouse input. */
    private static BlockPos beltEndPos(BlockPos dock) {
        return inputPos(dock).relative(layout(dock).sideDirection(INPUT.side()));
    }

    private static BlockPos beltStartPos(BlockPos dock) {
        return inputPos(dock).relative(layout(dock).sideDirection(INPUT.side()), BELT_LENGTH);
    }

    private static BlockPos beltMotorPos(BlockPos dock) {
        return beltStartPos(dock).relative(AISLE);
    }

    private static BlockPos feedHopperPos(BlockPos dock) {
        return beltStartPos(dock).relative(layout(dock).sideDirection(INPUT.side()));
    }

    private static BlockPos feedChestPos(BlockPos dock) {
        return feedHopperPos(dock).above();
    }

    private static AABB sceneBounds(BlockPos dock) {
        Direction right = AISLE.getClockWise();
        return AABB.encapsulatingFullBlocks(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN).below(),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, BELT_LENGTH + CLEAR_MARGIN)
                        .above(CLEAR_HEIGHT));
    }

    // --- block entities and inventories ------------------------------------------------------------------------------

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    private static WarehouseStockKeeperBlockEntity keeper(ServerLevel level, BlockPos dock) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER.getNullable(level,
                keeperPos(dock));
        if (keeper == null)
            throw new VisualTestException("the warehouse stock keeper of the aisle is missing");
        return keeper;
    }

    private static WarehouseInputBlockEntity input(ServerLevel level, BlockPos dock) {
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level, inputPos(dock));
        if (input == null)
            throw new VisualTestException("the warehouse input of the aisle is missing");
        return input;
    }

    private static WarehouseOutputBlockEntity output(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                outputPos(dock));
        if (output == null)
            throw new VisualTestException("the warehouse output of the aisle is missing");
        return output;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                terminalPos(dock));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static Direction beltMovement(ServerLevel level, BlockPos dock) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, beltStartPos(dock));
        if (belt == null)
            throw new VisualTestException("no belt at " + beltStartPos(dock));
        return belt.getMovementFacing();
    }

    /** Items standing on the feed belt (the belt's inventory lives on its controller segment). */
    private static long beltItems(ServerLevel level, BlockPos dock) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, beltStartPos(dock));
        if (belt == null || belt.getInventory() == null)
            return 0L;
        long items = 0;
        for (TransportedItemStack transported : belt.getInventory().getTransportedItems()) {
            if (transported.stack.is(Items.IRON_INGOT))
                items += transported.stack.getCount();
        }
        return items;
    }

    private static long craneHeld(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            return 0L;
        long held = 0;
        for (HeldItems.Entry entry : crane.heldItems().entries()) {
            if (entry.key().equals(IRON))
                held += entry.count();
        }
        return held;
    }

    private static boolean craneIdle(StackerCraneBlockEntity crane) {
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    /**
     * Flips the lever beside the output. {@code Block.UPDATE_ALL} notifies the neighbours, which is what makes the
     * station see the edge and submit (or drop) the request.
     */
    private static void setLever(MinecraftServer server, VisualContext context, boolean powered) {
        ServerLevel level = server.overworld();
        BlockPos pos = leverPos(context.origin());
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER))
            throw new VisualTestException("no lever beside the warehouse output at " + pos + ", found " + state);
        level.setBlock(pos, state.setValue(LeverBlock.POWERED, powered), Block.UPDATE_ALL);
    }

    private static void insertInto(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handlerAt(level, pos), stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    private static long countAt(ServerLevel level, BlockPos pos, Item item) {
        IItemHandler handler = handlerAt(level, pos);
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        return handler;
    }
}
