package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.filter.PackageFilterScreen;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.client.gui.WarehouseStockKeeperScreen;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.packagemode.PackageGroupKey;
import dev.wareworks.content.packagemode.PackageModeGate;
import dev.wareworks.content.station.KeeperRule;
import dev.wareworks.content.station.PackageUnpackSummary;
import dev.wareworks.content.station.StockKeeperMenuLayout;
import dev.wareworks.content.station.StockKeeperScreenState;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.TerminalPackageOrderState;
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
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.stock.RuleUnit;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "packagemode" (issue #40, M37, ADR-086): a warehouse in <b>package mode</b>, as a player meets it, in its
 * own world {@value #WORLD_FOLDER}.
 * <p>
 * The world is built and stocked with loose goods while the switch is off (the global config is never touched); then
 * the run writes {@code [packageMode] enabled = true} into <b>this world's own</b> {@code serverconfig} folder while the
 * world is closed, and opens it again: the switch takes effect at a world load ({@code worldRestart()}), exactly as a
 * player of a single-player world or a server owner switches it. Everything after that is real input: the use key
 * through {@code KeyMapping} ({@link RealClicks}) and screen clicks, wheel turns and typed characters through the
 * screens' own handlers ({@link ScreenInput}), never the mouse grab or the window focus.
 * <p>
 * <b>What the run proves, each asserted before the shot that shows it:</b>
 * <ol>
 *   <li>the switch is per world: off at the start, on after the rejoin, read from the world's own file;</li>
 *   <li>sealed boxes ride the player's belt into the input and are stored as themselves (address and contents);</li>
 *   <li>a Package Filter addressed in Create's own screen (typed) and clicked into a stock keeper row makes a package
 *       rule: its minimum lights the lamp and drives the comparator in the world until enough boxes are in, and nothing
 *       is ever made for it;</li>
 *   <li>loose iron on the same belt is refused at the input and waits on the belt (the input's goggles name it);</li>
 *   <li>the terminal lists one row per address with the contents in the tooltip, the loose stock from before the switch
 *       after them, and refuses to fold out its crafting panel with the red reason;</li>
 *   <li>a Packager door without a sign is listed as refused and a click never chooses it, and the server refuses an
 *       order naming it;</li>
 *   <li>three boxes of one address are ordered with one click to a door with a sign; a save, quit and rejoin while the
 *       order is half delivered; the order resumes and ends; the player's lever sends each box through the door's
 *       Packager sealed, with its address and contents;</li>
 *   <li>the loose iron from before the switch is still served by a click;</li>
 *   <li>in English and German.</li>
 * </ol>
 * A {@link SceneItemCensus} under the package mode rule ({@link SceneItemCensus#takeWithBoxes}: a box counts as its
 * contents <b>and</b> as itself) is exact after every act, and a {@link ServerTickWatch} checks on every server tick that
 * no production order exists, no loose item reaches storage or the input's buffer, and no address order asks for more
 * than was clicked. The run ends with {@code packagemode: ALL CHECKS PASSED in English and German}.
 */
public final class PackageModeVisualScenario implements VisualScenario {
    public static final String NAME = "packagemode";
    static final String WORLD_FOLDER = "wareworks_visual_packagemode";

    /** A save and rejoin twice, a belt, a crane trip per box and a language switch: well over the default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 15L * 60L * 1000L;

    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    private static final int BELT_RPM = 48;
    private static final int BELT_LENGTH = 3;

    private static final List<Integer> STORAGE = List.of(1, 2, 3, 4);
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition KEEPER = RackPosition.of(2, 0, Side.RIGHT);
    private static final RackPosition INPUT = RackPosition.of(4, 0, Side.RIGHT);
    /** The door with a sign: a requesting port, a Packager at its back, a sign beside the Packager, a lever on it. */
    private static final RackPosition DOOR = RackPosition.of(6, 1, Side.RIGHT);
    /** The door without a sign: in package mode a Packager would strip a box's address, so it is refused (E7). */
    private static final RackPosition SIGNLESS = RackPosition.of(8, 1, Side.RIGHT);

    private static final BranchLayout LAYOUT_AT_ZERO = layout(BlockPos.ZERO);
    private static final Direction RIGHT_OUT = LAYOUT_AT_ZERO.sideDirection(Side.RIGHT);

    // --- what the scene holds -----------------------------------------------------------------------------------------
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey COPPER = ItemKey.of(Items.COPPER_INGOT);
    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    /** Loose stock from before the switch, per storage barrel. */
    private static final long OLD_IRON = 48L;
    private static final long OLD_COPPER = 32L;
    private static final long OLD_COAL = 40L;
    private static final String BASE_NORTH = "Base North";
    private static final String MINE = "Mine";
    /** Boxes of "Base North" on the belt before the rule is written; the rule's minimum is one more. */
    private static final int FIRST_BOXES = 5;
    private static final long RULE_MINIMUM = FIRST_BOXES + 1L;
    /** Boxes one click orders to the door. */
    private static final int ORDERED_BOXES = 3;
    /** Loose iron put on the belt in package mode; all of it must stay outside. */
    private static final int LOOSE_ON_BELT = 8;
    /** Old loose iron a click takes out of the warehouse. */
    private static final int LOOSE_RETRIEVED = 16;

    // --- hotbar ------------------------------------------------------------------------------------------------------
    private static final int SLOT_FILTER = 1;
    private static final int SLOT_EMPTY = 8;

    private static final String ENGLISH = "en_us";
    private static final String GERMAN = "de_de";

    private static final int CLEAR_MARGIN = 6;
    private static final int CLEAR_HEIGHT = 9;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SCREEN_TIMEOUT_TICKS = 300;
    private static final int STORE_TIMEOUT_TICKS = 2400;
    private static final int DELIVERY_TIMEOUT_TICKS = 3000;
    private static final int LEVER_TIMEOUT_TICKS = 600;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 10;
    /** How long the loose iron must have stood still on the belt before it counts as refused rather than slow. */
    private static final int REFUSED_FOR_TICKS = 120;
    /** Longer than vanilla's 250 ms double-click window ({@code AbstractContainerScreen#mouseClicked}). */
    private static final int DOUBLE_CLICK_GAP_TICKS = 10;
    /** Where the mouse is parked for a shot without a tooltip: the window's title row. */
    private static final int TITLE_CORNER = 5;

    // --- cameras (relative to the dock; built from the layout, so they follow the rack sides) -----------------------
    private static final CameraView HALL = view("hall", 4.0, 5.0, 7.0, 4.0, 0.5, 0.5);
    private static final CameraView BELT = view("belt", 1.6, 3.2, 4.6, 4.0, 0.6, 2.0);
    private static final CameraView DOOR_VIEW = view("door", 3.4, 4.2, 4.6, 6.0, 1.4, 1.6);
    private static final CameraView KEEPER_VIEW = view("keeper", 0.0, 3.4, 5.0, 2.0, 0.6, 2.0);
    /** Over the aisle beside the input, looking at its aisle face from within the vanilla reach, for its goggles. */
    private static final CameraView INPUT_GOGGLES = view("input-goggles", 4.0, 1.6, -0.2, 4.0, 0.5, 1.0);
    /** Straight up into the sky, so the use key hits nothing and the held Package Filter opens its own screen. */
    private static final CameraView SKY = view("sky", 2.0, 6.0, -3.0, 2.0, 30.0, -3.0);

    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene holds, under the package mode rule: every box as its contents and as itself. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Every box this run made, by address, in the order they went onto the belt. */
    private final Map<String, List<ItemKey>> boxes = new LinkedHashMap<>();
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);
    private volatile ServerTickWatch watch;
    /** The address order as it was saved, compared with what the rejoined world loads. */
    private volatile TerminalPackageOrderState savedOrder = TerminalPackageOrderState.NONE;
    private volatile String serverStatus = "";
    /** The boxes that left through the door, so the terminal's row is checked against what is still in stock. */
    private volatile Set<ItemKey> sent = Set.of();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    // =================================================================================================================
    // setup: the warehouse as it was before the switch
    // =================================================================================================================

    @Override
    public void setup(VisualScript script) {
        script.client("packagemode: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "packagemode run"))
                .server("packagemode: package mode is off in a new world", (server, context) -> {
                    if (WareworksConfig.packageModeEnabled() || PackageModeGate.enabled())
                        throw new VisualTestException("package mode is on in a fresh world: the global config "
                                + "must not have it, this run switches it on for its own world only");
                    LOGGER.info(PREFIX + "packagemode: CHECK PASS package mode is off before the switch");
                })
                .server("packagemode: build the warehouse, its doors, the keeper and the belt", this::build)
                .server("packagemode: the player flies, wears goggles and carries a blank Package Filter",
                        PackageModeVisualScenario::equip)
                .serverUntil("packagemode: wait until the controller knows every member",
                        PackageModeVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("packagemode: aim the belt at the input", PackageModeVisualScenario::aimBelt)
                .serverUntil("packagemode: wait until the belt really carries towards the input",
                        PackageModeVisualScenario::beltAimed, SYNC_TIMEOUT_TICKS)
                .server("packagemode: stock loose goods, as a warehouse had them before the switch",
                        this::stockLooseGoods)
                .serverUntil("packagemode: wait until the index counts the loose goods",
                        (server, context) -> controller(server.overworld(), context.origin()).countOf(IRON) == OLD_IRON
                                && controller(server.overworld(), context.origin()).countOf(COAL) == OLD_COAL,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("packagemode: census before the switch", (server, context) -> census(server, "before the "
                        + "switch"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            script.shotFrom(HALL, "before-switch");
            switchOn(script);
            boxesIn(script);
            keeperRule(script);
            looseRefused(script);
            terminalEnglish(script);
            addressOrder(script);
            looseRetrieved(script);
            terminalGerman(script);
            script.server("packagemode: every check passed", (server, context) -> {
                watch().check();
                census(server, "at the end");
                LOGGER.info(PREFIX + "packagemode: ALL CHECKS PASSED in English and German (the switch read from "
                        + "this world's own serverconfig after a rejoin, {} boxes stored sealed from the belt, a "
                        + "package rule's minimum on the lamp and the comparator, {} loose iron refused and left on "
                        + "the belt, one terminal row per address with its contents, the crafting panel refused with "
                        + "its reason, a signless Packager door refused, {} boxes ordered to a signed door across a "
                        + "save and rejoin and sent sealed, {} old loose iron served, every census exact with boxes "
                        + "counted as themselves)", FIRST_BOXES + 2, LOOSE_ON_BELT, ORDERED_BOXES, LOOSE_RETRIEVED);
            });
            return;
        }
        script.shotFrom(HALL, "end");
        openTerminal(script);
        script.client("packagemode: hover the Base North row once more", context -> hoverRow(context, BASE_NORTH))
                .waitTicks(SETTLE_TICKS)
                .shot("pm-groups");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
    }

    @Override
    public void finish(VisualScript script) {
        script.server("packagemode: stop the per-tick watch", (server, context) -> {
            ServerTickWatch running = watch;
            if (running != null)
                running.stop();
        });
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder(serverStatus);
        Screen open = context.minecraft().screen;
        status.append(" screen=").append(open == null ? "none" : open.getClass().getSimpleName());
        if (open instanceof WarehouseTerminalScreen terminal)
            status.append(" feedback='").append(terminal.feedbackLine().map(Component::getString).orElse(""))
                    .append("' order=").append(terminal.packageOrderState().delivered()).append('/')
                    .append(terminal.packageOrderState().wanted());
        status.append(" lang=").append(context.minecraft().getLanguageManager().getSelected())
                .append(" packageMode=").append(PackageModeGate.enabledOnClient());
        return status.toString();
    }

    // =================================================================================================================
    // act 1: the switch, in this world's own file, read at a world load
    // =================================================================================================================

    private void switchOn(VisualScript script) {
        script.server("packagemode: save the world with the switch still off",
                (server, context) -> server.saveEverything(true, true, true));
        VisualWorld.reload(script, worldProfile(), atTitle -> atTitle.client(
                "packagemode: write [packageMode] enabled = true into this world's serverconfig while it is closed",
                PackageModeVisualScenario::writeWorldConfig));
        script.server("packagemode: the rejoined world reads the switch from its own file", (server, context) -> {
                    if (!WareworksConfig.packageModeEnabled() || !PackageModeGate.enabled())
                        throw new VisualTestException("the rejoined world reads packageMode.enabled = "
                                + WareworksConfig.packageModeEnabled() + " although its own serverconfig says true");
                    LOGGER.info(PREFIX + "packagemode: CHECK PASS package mode is on, read from {}/serverconfig at "
                            + "the world load", WORLD_FOLDER);
                })
                .until("packagemode: the client was told the switch by the server at login",
                        context -> PackageModeGate.enabledOnClient(), SYNC_TIMEOUT_TICKS)
                .serverUntil("packagemode: wait until the rejoined controller knows every member again",
                        PackageModeVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("packagemode: census after the switch", (server, context) -> census(server, "after the "
                        + "switch and the rejoin"))
                .server("packagemode: start the per-tick watch", this::startWatch);
    }

    /**
     * NeoForge reads a world's server configs from {@code <world>/serverconfig/}, where a file overrides the global one
     * in {@code <instance>/config/}. The world is closed while it is written, so nothing is ever reloaded under a
     * running server, and nothing outside this throw-away world is touched.
     */
    private static void writeWorldConfig(VisualContext context) throws IOException {
        Path folder = context.minecraft().getLevelSource().getBaseDir().resolve(WORLD_FOLDER).resolve("serverconfig");
        Files.createDirectories(folder);
        Path file = folder.resolve(Wareworks.ID + "-server.toml");
        Files.writeString(file, """
                # Written by the Wareworks visual test scenario "packagemode" (issue #40): package mode is a server
                # setting read at the world load, so this throw-away world gets it in its own serverconfig folder.
                [packageMode]
                \tenabled = true
                """, StandardCharsets.UTF_8);
        LOGGER.info(PREFIX + "packagemode: wrote {}", file);
    }

    private void startWatch(MinecraftServer server, VisualContext context) {
        if (watch != null)
            watch.stop();
        BlockPos dock = context.origin();
        watch = ServerTickWatch.start(NAME, tickServer -> watchTick(tickServer, dock));
    }

    private ServerTickWatch watch() {
        ServerTickWatch running = watch;
        if (running == null)
            throw new VisualTestException("the per-tick watch is not running");
        return running;
    }

    /**
     * Every server tick in package mode: no production order, no loose item more in storage than before the switch,
     * no loose item in the input's buffer, and the address order never asks for more boxes than were clicked.
     */
    private void watchTick(MinecraftServer server, BlockPos dock) {
        ServerLevel level = server.overworld();
        if (!level.isLoaded(dock) || !level.isLoaded(layout(dock).rackPos(INPUT)))
            return;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            return;
        if (!controller.productionOrders().isEmpty())
            throw new VisualTestException("a production order exists in package mode: "
                    + controller.productionOrders());
        if (controller.countOf(IRON) > OLD_IRON || controller.countOf(COPPER) > OLD_COPPER
                || controller.countOf(COAL) > OLD_COAL || controller.countOf(DIAMOND) > 0L)
            throw new VisualTestException("a loose item reached storage in package mode: iron "
                    + controller.countOf(IRON) + ", copper " + controller.countOf(COPPER) + ", coal "
                    + controller.countOf(COAL) + ", diamond " + controller.countOf(DIAMOND));
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout(dock).rackPos(INPUT));
        if (input != null)
            for (ItemKey key : input.bufferedItems().keys())
                if (!PackageModeGate.isPackage(key))
                    throw new VisualTestException("the input's buffer holds a loose " + key + " in package mode");
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal != null && terminal.packageOrderState().wanted() > ORDERED_BOXES)
            throw new VisualTestException("the address order wants " + terminal.packageOrderState().wanted()
                    + " boxes, more than the " + ORDERED_BOXES + " clicked");
    }

    // =================================================================================================================
    // act 2: sealed boxes from the player's belt
    // =================================================================================================================

    private void boxesIn(VisualScript script) {
        script.server("packagemode: put " + FIRST_BOXES + " boxes for " + BASE_NORTH + " and one for " + MINE
                        + " into the feed barrel", (server, context) -> {
                    List<ItemStack> fed = new ArrayList<>();
                    for (int box = 0; box < FIRST_BOXES; box++)
                        fed.add(box(BASE_NORTH, IRON.toStack(4 + box), COAL.toStack(2)));
                    fed.add(box(MINE, DIAMOND.toStack(3)));
                    feed(server, context, fed);
                })
                .serverUntil("packagemode: wait until every box is stored and the belt is empty",
                        (server, context) -> allStored(server, context), STORE_TIMEOUT_TICKS)
                .server("packagemode: check that the boxes are stored as themselves", this::checkBoxesStored)
                .server("packagemode: census with the boxes stored", (server, context) -> census(server,
                        "after " + (FIRST_BOXES + 1) + " boxes came in by belt"))
                .shotFrom(HALL, "pm-boxes-stored");
    }

    private static ItemStack box(String address, ItemStack... contents) {
        ItemStack box = PackageItem.containing(List.of(contents));
        PackageItem.addAddress(box, address);
        return box;
    }

    /** Puts {@code stacks} into the feed barrel over the belt's hopper and declares them in the census. */
    private void feed(MinecraftServer server, VisualContext context, List<ItemStack> stacks) {
        IItemHandler barrel = server.overworld().getCapability(Capabilities.ItemHandler.BLOCK,
                feedBarrelPos(context.origin()), null);
        if (barrel == null)
            throw new VisualTestException("no feed barrel over the belt's hopper");
        for (ItemStack stack : stacks) {
            ItemStack rest = ItemHandlerHelper.insertItem(barrel, stack.copy(), false);
            if (!rest.isEmpty())
                throw new VisualTestException("the feed barrel refused " + rest);
            if (PackageItem.isPackage(stack))
                boxes.computeIfAbsent(PackageItem.getAddress(stack), address -> new ArrayList<>())
                        .add(ItemKey.of(stack));
        }
        expected = SceneItemCensus.plusAllWithBoxes(expected, stacks);
        LOGGER.info(PREFIX + "packagemode: fed {} stack(s) into the feed barrel: {}", stacks.size(), stacks);
    }

    private boolean allStored(MinecraftServer server, VisualContext context) {
        watch().check();
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = controller(level, dock);
        for (List<ItemKey> keys : boxes.values())
            for (ItemKey key : keys)
                if (controller.countOf(key) != 1L)
                    return false;
        StackerCraneBlockEntity crane = crane(level, dock);
        return input(level, dock).bufferedItems().totalItems() == 0 && crane.heldItems().isEmpty()
                && crane.craneState().phase() == CranePhase.IDLE && itemsOnBelt(level, dock) == 0
                && countIn(level, feedBarrelPos(dock)) == 0 && countIn(level, feedHopperPos(dock)) == 0;
    }

    /** Every box the index holds is in a storage barrel, sealed: the exact box, with its address and its contents. */
    private void checkBoxesStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        Map<ItemKey, Long> inBarrels = new HashMap<>();
        for (int x : STORAGE) {
            IItemHandler barrel = level.getCapability(Capabilities.ItemHandler.BLOCK,
                    layout.rackPos(RackPosition.of(x, 0, Side.LEFT)).relative(layout.sideDirection(Side.LEFT)), null);
            if (barrel == null)
                throw new VisualTestException("no storage barrel at position " + x);
            for (int slot = 0; slot < barrel.getSlots(); slot++) {
                ItemStack stack = barrel.getStackInSlot(slot);
                if (PackageItem.isPackage(stack))
                    inBarrels.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
            }
        }
        int count = 0;
        for (Map.Entry<String, List<ItemKey>> entry : boxes.entrySet())
            for (ItemKey key : entry.getValue()) {
                if (inBarrels.getOrDefault(key, 0L) != 1L)
                    throw new VisualTestException("the box " + key + " for '" + entry.getKey() + "' is not in storage "
                            + "exactly once: " + inBarrels);
                ItemStack stack = key.toStack();
                if (!entry.getKey().equals(PackageItem.getAddress(stack)))
                    throw new VisualTestException("a stored box lost its address: " + stack);
                count++;
            }
        LOGGER.info(PREFIX + "packagemode: CHECK PASS {} boxes stored sealed, each exactly once, address and contents "
                + "as they went onto the belt", count);
    }

    // =================================================================================================================
    // act 3: a package rule, written with a Package Filter addressed in Create's own screen
    // =================================================================================================================

    private void keeperRule(VisualScript script) {
        // The Package Filter's address, typed into Create's own filter screen, opened by a use of the item in the air.
        hotbarSlot(script, SLOT_FILTER, "the blank Package Filter",
                stack -> AllItems.PACKAGE_FILTER.isIn(stack));
        script.camera(SKY);
        RealClicks.fly(script, NAME);
        RequesterVisualScenario.useKey(script, "the air with the Package Filter");
        ArmVisualScenario.untilOrFail(script, "packagemode: Create's Package Filter screen is open",
                context -> context.minecraft().screen instanceof PackageFilterScreen, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS)
                .client("packagemode: type '" + BASE_NORTH + "' into the filter's address field", context -> {
                    Screen screen = context.minecraft().screen;
                    for (char c : BASE_NORTH.toCharArray())
                        screen.charTyped(c, 0);
                })
                .waitTicks(SETTLE_TICKS)
                .shotWithGui("pm-filter-address");
        RequesterVisualScenario.closeScreen(script, "the Package Filter screen");
        script.serverUntil("packagemode: the filter in hand carries the address " + BASE_NORTH,
                (server, context) -> BASE_NORTH.equals(context.serverPlayer(server).getInventory()
                        .getItem(SLOT_FILTER).get(AllDataComponents.PACKAGE_ADDRESS)), SYNC_TIMEOUT_TICKS);

        // The keeper's screen: the filter picked up from the hotbar and clicked into row 0, the minimum scrolled.
        hotbarSlot(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
        RealClicks.rightClick(script, NAME, RealClicks.click("the stock keeper", offset(KEEPER), Direction.UP,
                new Vec3(-0.6, 2.2, 0.0)));
        ArmVisualScenario.untilOrFail(script, "packagemode: the stock keeper's screen is open",
                context -> context.minecraft().screen instanceof WarehouseStockKeeperScreen, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.client("packagemode: pick the addressed filter up from the hotbar", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.hotbarSlot(keeperScreen(context), SLOT_FILTER)))
                .until("packagemode: the cursor carries the filter",
                        context -> AllItems.PACKAGE_FILTER.isIn(keeperScreen(context).getMenu().getCarried()),
                        SYNC_TIMEOUT_TICKS)
                .client("packagemode: click the filter into row 0 of the keeper", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.keeperItemCell(keeperScreen(context), 0)))
                .serverUntil("packagemode: row 0 is a package rule for " + BASE_NORTH,
                        (server, context) -> {
                            KeeperRule rule = keeper(server.overworld(), context.origin()).rules().keeperRules()
                                    .stream().findFirst().orElse(null);
                            return rule != null && rule.unit() == RuleUnit.PACKAGES
                                    && rule.packageAddress().equals(Optional.of(BASE_NORTH));
                        }, SYNC_TIMEOUT_TICKS)
                // A second click on the same slot within vanilla's double-click window would collect instead of place.
                .waitTicks(DOUBLE_CLICK_GAP_TICKS)
                .client("packagemode: put the filter back into its hotbar slot", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.hotbarSlot(keeperScreen(context), SLOT_FILTER)))
                .until("packagemode: the cursor is empty again",
                        context -> keeperScreen(context).getMenu().getCarried().isEmpty(), SYNC_TIMEOUT_TICKS)
                .client("packagemode: click the minimum of row 0 on (it starts at 0)", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.keeperNumberField(keeperScreen(context), 0,
                                StockKeeperMenuLayout.FIELD_MINIMUM)));
        ArmVisualScenario.untilOrFail(script, "packagemode: scroll the minimum to " + RULE_MINIMUM, context -> {
            Optional<StockKeeperScreenState.RowView> row = keeperScreen(context).state().row(0);
            if (row.isEmpty() || row.get().minimum() == StockRule.UNSET)
                return false;
            long minimum = row.get().minimum();
            if (minimum == RULE_MINIMUM)
                return true;
            // One notch, then wait for the server's push before the next: each notch steps from the shown value.
            if (minimum != lastScrolledFrom) {
                lastScrolledFrom = minimum;
                ScreenInput.scroll(context.minecraft(), ScreenInput.keeperNumberField(keeperScreen(context), 0,
                        StockKeeperMenuLayout.FIELD_MINIMUM), minimum < RULE_MINIMUM ? 1.0 : -1.0);
            }
            return false;
        }, SCREEN_TIMEOUT_TICKS, context -> "the keeper shows " + keeperScreen(context).state().row(0));
        script.serverUntil("packagemode: the keeper's lamp burns and its comparator calls for boxes",
                        (server, context) -> keeperSignals(server, context, true), SYNC_TIMEOUT_TICKS)
                .client("packagemode: check the package row on the keeper's screen", this::checkKeeperRow)
                .client("packagemode: hover the package row", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.keeperItemCell(keeperScreen(context), 0)))
                .waitTicks(SETTLE_TICKS)
                .shot("pm-keeper");
        RequesterVisualScenario.closeScreen(script, "the stock keeper's screen");
        script.shotFrom(KEEPER_VIEW, "pm-keeper-calls")
                .server("packagemode: one more box for " + BASE_NORTH + " onto the belt",
                        (server, context) -> feed(server, context,
                                List.of(box(BASE_NORTH, IRON.toStack(9), COAL.toStack(2)))))
                .serverUntil("packagemode: wait until it is stored",
                        (server, context) -> allStored(server, context), STORE_TIMEOUT_TICKS)
                .serverUntil("packagemode: the lamp goes dark and the comparator falls to 0 at the minimum",
                        (server, context) -> keeperSignals(server, context, false), SYNC_TIMEOUT_TICKS)
                .server("packagemode: census after the sixth box", (server, context) -> census(server,
                        "with the package rule satisfied"))
                .shotFrom(KEEPER_VIEW, "pm-keeper-satisfied");
    }

    private volatile long lastScrolledFrom = Long.MIN_VALUE;

    /**
     * The rule in the world: the keeper's own signal, the comparator that reads it and the lamp it drives, all on
     * ({@code calling}) or all off. Nothing is made for a package rule: the watch checks that on every tick.
     */
    private boolean keeperSignals(MinecraftServer server, VisualContext context, boolean calling) {
        watch().check();
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseStockKeeperBlockEntity keeper = keeper(level, dock);
        int expectedSignal = calling ? 1 : 0;
        if (keeper.comparatorSignal() != expectedSignal)
            return false;
        if (level.getBlockState(keeperPos(dock)).getValue(WarehouseStockKeeperBlock.LIT) != calling)
            return false;
        if (!(level.getBlockEntity(comparatorPos(dock)) instanceof ComparatorBlockEntity comparator))
            throw new VisualTestException("no comparator behind the keeper");
        if (comparator.getOutputSignal() != expectedSignal)
            return false;
        BlockState lamp = level.getBlockState(lampPos(dock));
        if (!lamp.is(Blocks.REDSTONE_LAMP) || lamp.getValue(RedstoneLampBlock.LIT) != calling)
            return false;
        long stored = boxes.getOrDefault(BASE_NORTH, List.of()).stream()
                .mapToLong(key -> controller(level, dock).countOf(key)).sum();
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the package rule {} ({} boxes for {} stored, minimum {}): keeper "
                + "{}, comparator {}, lamp {}", calling ? "calls" : "is satisfied", stored, BASE_NORTH, RULE_MINIMUM,
                keeper.comparatorSignal(), comparator.getOutputSignal(), calling ? "lit" : "dark");
        return true;
    }

    private void checkKeeperRow(VisualContext context) {
        StockKeeperScreenState.RowView row = keeperScreen(context).state().row(0)
                .orElseThrow(() -> new VisualTestException("the keeper screen has no row 0"));
        if (row.unit() != RuleUnit.PACKAGES || row.minimum() != RULE_MINIMUM)
            throw new VisualTestException("row 0 should be a package row with minimum " + RULE_MINIMUM + ": " + row);
        if (row.stocked() != FIRST_BOXES)
            throw new VisualTestException("row 0 should count " + FIRST_BOXES + " boxes for " + BASE_NORTH + ": "
                    + row);
        List<String> tooltip = ScreenInput.keeperTooltip(keeperScreen(context),
                ScreenInput.keeperItemCell(keeperScreen(context), 0)).stream().map(Component::getString).toList();
        if (tooltip.stream().noneMatch(line -> line.contains(BASE_NORTH)))
            throw new VisualTestException("the package row's tooltip does not name " + BASE_NORTH + ": " + tooltip);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the keeper's row 0 counts {} boxes for {} against a minimum of "
                + "{}; tooltip {}", row.stocked(), BASE_NORTH, row.minimum(), tooltip);
    }

    // =================================================================================================================
    // act 4: loose iron on the same belt is refused and waits
    // =================================================================================================================

    private void looseRefused(VisualScript script) {
        script.server("packagemode: put " + LOOSE_ON_BELT + " loose iron into the feed barrel",
                        (server, context) -> feed(server, context, List.of(IRON.toStack(LOOSE_ON_BELT))))
                .serverUntil("packagemode: wait until the iron has backed up on the belt in front of the input",
                        (server, context) -> {
                            watch().check();
                            ServerLevel level = server.overworld();
                            return countOf(level, List.of(beltEndPos(context.origin())), IRON) > 0
                                    && looseOutside(level, context.origin()) == LOOSE_ON_BELT
                                    && countOf(level, List.of(feedBarrelPos(context.origin())), IRON) == 0;
                        }, STORE_TIMEOUT_TICKS)
                .waitTicks(REFUSED_FOR_TICKS)
                .server("packagemode: the iron is still on the belt and the input took none",
                        PackageModeVisualScenario::checkLooseRefused)
                .server("packagemode: census with the iron waiting on the belt", (server, context) -> census(server,
                        "with loose iron refused at the input"))
                .shotFrom(BELT, "pm-belt-refused");
        GoggleShots.shot(script, NAME, INPUT_GOGGLES, "pm-input-goggles", origin -> origin.offset(offset(INPUT)),
                context -> context.minecraft().level != null && context.minecraft().level.getBlockEntity(
                        context.origin().offset(offset(INPUT))) instanceof WarehouseInputBlockEntity input
                        && !input.summary().packages().lastLooseRefused().isEmpty(),
                context -> {
                    List<String> lines = GoggleShots.lines(context, context.origin().offset(offset(INPUT)));
                    GoggleShots.requireLine(lines, WareworksLang.translateDirect(
                            WareworksLang.GOGGLES_INPUT_PACKAGES_ONLY).getString());
                    GoggleShots.requireLine(lines, Items.IRON_INGOT.getDescription().getString());
                    LOGGER.info(PREFIX + "packagemode: CHECK PASS the input's goggles: {}", lines);
                });
    }

    /**
     * The loose iron outside the warehouse: on the belt, in the feed's hopper and in its barrel. The belt in front of a
     * door that refuses backs up, so the last few ingots wait in the hopper behind it, exactly as on a player's line.
     */
    private static long looseOutside(ServerLevel level, BlockPos dock) {
        List<BlockPos> outside = new ArrayList<>(beltBlocks(dock));
        outside.add(feedHopperPos(dock));
        outside.add(feedBarrelPos(dock));
        return countOf(level, outside, IRON);
    }

    private static void checkLooseRefused(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long onBelt = countOf(level, beltBlocks(dock), IRON);
        long outside = looseOutside(level, dock);
        WarehouseInputBlockEntity input = input(level, dock);
        if (outside != LOOSE_ON_BELT || onBelt == 0L || countOf(level, List.of(beltEndPos(dock)), IRON) == 0L
                || input.bufferedItems().count(IRON) != 0L)
            throw new VisualTestException("all " + LOOSE_ON_BELT + " loose iron should still wait outside, the first "
                    + "at the belt's end, and the input hold none: outside " + outside + " (belt " + onBelt
                    + "), input " + input.bufferedItems());
        if (controller(level, dock).countOf(IRON) != OLD_IRON)
            throw new VisualTestException("the warehouse holds " + controller(level, dock).countOf(IRON)
                    + " loose iron, not the " + OLD_IRON + " from before the switch");
        input.onGoggleObserved();
        PackageUnpackSummary packages = input.summary().packages();
        if (!"minecraft:iron_ingot".equals(packages.lastLooseRefused()))
            throw new VisualTestException("the input's goggles should name the refused iron: " + packages);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS {} loose iron wait outside ({} on the belt, the rest in the "
                + "hopper behind it) for {} ticks; the input took none and names it ({})", outside, onBelt,
                REFUSED_FOR_TICKS, packages.lastLooseRefused());
    }

    // =================================================================================================================
    // act 5: the terminal, in English
    // =================================================================================================================

    private void terminalEnglish(VisualScript script) {
        openTerminal(script);
        script.client("packagemode: CHECK the address rows come first, the loose stock after",
                        PackageModeVisualScenario::checkSections)
                .client("packagemode: hover the " + BASE_NORTH + " row", context -> hoverRow(context, BASE_NORTH))
                .waitTicks(SETTLE_TICKS)
                .client("packagemode: CHECK the address row's tooltip", context -> checkGroupTooltip(context))
                .shot("pm-groups")
                .client("packagemode: hover the loose iron from before the switch",
                        context -> ScreenInput.hover(context.minecraft(), cell(context, IRON)))
                .waitTicks(SETTLE_TICKS)
                .client("packagemode: CHECK the loose row's tooltip", PackageModeVisualScenario::checkLooseTooltip)
                .shot("pm-loose")
                .client("packagemode: click the crafting panel's toggle", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.terminalCraftToggle(screen(context))))
                .waitTicks(SETTLE_TICKS)
                .client("packagemode: CHECK the panel stays folded in with the red reason",
                        PackageModeVisualScenario::checkCraftRefused)
                .shot("pm-craft-off");
        doors(script, "");
        script.client("packagemode: choose the door with the sign", PackageModeVisualScenario::clickDoorButton)
                .until("packagemode: the door with the sign is chosen",
                        context -> screen(context).chosenDoor().equals(Optional.of(context.origin()
                                .offset(offset(DOOR)))), SYNC_TIMEOUT_TICKS)
                .server("packagemode: the server refuses an order naming the signless door",
                        PackageModeVisualScenario::checkSignlessRefusedByServer);
    }

    private void openTerminal(VisualScript script) {
        hotbarSlot(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
        RealClicks.rightClick(script, NAME, RealClicks.click("the terminal", offset(TERMINAL), Direction.UP,
                new Vec3(-0.6, 2.2, 0.0)));
        ArmVisualScenario.untilOrFail(script, "packagemode: the terminal screen is open with its rows",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.hasStock()
                        && terminal.entry(PackageGroupKey.of(BASE_NORTH)).isPresent()
                        && terminal.groupSummary(PackageGroupKey.of(BASE_NORTH)).isPresent(),
                SCREEN_TIMEOUT_TICKS, context -> "the screen is " + context.minecraft().screen);
    }

    /** Every address row is drawn before every loose row; the loose rows are the stock from before the switch. */
    private static void checkSections(VisualContext context) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        boolean looseSeen = false;
        List<String> order = new ArrayList<>();
        for (StockLine<ItemKey> line : visible) {
            boolean group = PackageGroupKey.isGroup(line.key());
            order.add((group ? "group " : "loose ") + line.key().toStack().getHoverName().getString());
            if (!group)
                looseSeen = true;
            else if (looseSeen)
                throw new VisualTestException("an address row stands after a loose row: " + order);
            if (PackageModeGate.isPackage(line.key()) && !group)
                throw new VisualTestException("a single box has a row of its own: " + line.key());
        }
        for (ItemKey loose : List.of(IRON, COPPER, COAL))
            if (screen(context).entry(loose).isEmpty())
                throw new VisualTestException("the loose stock from before the switch lacks " + loose + ": " + order);
        if (screen(context).entry(PackageGroupKey.of(MINE)).isEmpty())
            throw new VisualTestException("no row for the address " + MINE + ": " + order);
        // Nothing is cut, so the screen must not claim it was: "+N not shown" is measured against the folded rows.
        if (screen(context).status().itemTypes() != visible.size())
            throw new VisualTestException("the screen counts " + screen(context).status().itemTypes()
                    + " item types for " + visible.size() + " rows, so it would say some are not shown");
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the terminal shows {}", order);
    }

    private static void hoverRow(VisualContext context, String address) {
        ScreenInput.hover(context.minecraft(), cell(context, PackageGroupKey.of(address)));
    }

    /** The address row's tooltip: its name, "Inside:", the summed contents of its boxes and the click hint. */
    private void checkGroupTooltip(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ItemKey group = PackageGroupKey.of(BASE_NORTH);
        StockLine<ItemKey> line = terminal.entry(group)
                .orElseThrow(() -> new VisualTestException("no row for " + BASE_NORTH));
        List<ItemKey> inStock = boxes.get(BASE_NORTH).stream().filter(key -> !sent.contains(key)).toList();
        long expectedBoxes = inStock.size();
        if (line.total() != expectedBoxes)
            throw new VisualTestException("the " + BASE_NORTH + " row counts " + line.total() + " boxes, not "
                    + expectedBoxes);
        long iron = 0L;
        for (ItemKey key : inStock)
            iron += key.toStack().getOrDefault(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.EMPTY)
                    .nonEmptyStream().filter(stack -> stack.is(Items.IRON_INGOT)).mapToLong(ItemStack::getCount).sum();
        List<String> lines = terminal.itemTooltip(line).stream().map(Component::getString).toList();
        String inside = WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_GROUP_INSIDE).getString();
        String ironLine = " " + WareworksLang.translateDirect(WareworksLang.GOGGLES_ITEM_COUNT,
                Items.IRON_INGOT.getDescription(), LangNumberFormat.format(iron)).getString();
        String hint = WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_GROUP_HINT).getString();
        String name = WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_GROUP, BASE_NORTH).getString();
        if (lines.isEmpty() || !lines.getFirst().equals(name) || !lines.contains(inside) || !lines.contains(ironLine)
                || !lines.contains(hint))
            throw new VisualTestException("the " + BASE_NORTH + " row's tooltip should be named '" + name + "' and "
                    + "say '" + inside + "', '" + ironLine + "' and '" + hint + "': " + lines);
        // Review fixes: the row's own click hint instead of the item one, and its name not in italics.
        String groupHint = WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_GROUP_AMOUNT_HINT).getString();
        String itemHint = WareworksLang.translateDirect(WareworksLang.TERMINAL_AMOUNT_HINT).getString();
        if (!lines.contains(groupHint) || lines.contains(itemHint))
            throw new VisualTestException("the " + BASE_NORTH + " row's tooltip should give the hint '" + groupHint
                    + "' and not '" + itemHint + "': " + lines);
        Component title = terminal.itemTooltip(line).getFirst();
        boolean italic = title.getStyle().isItalic() || title.toFlatList().stream()
                .anyMatch(part -> part.getStyle().isItalic());
        if (italic)
            throw new VisualTestException("the " + BASE_NORTH + " row's name is drawn in italics: " + title);
        List<String> shown = terminal.tooltipAt((int) cell(context, group).x(), (int) cell(context, group).y())
                .stream().map(Component::getString).toList();
        if (!shown.equals(lines))
            throw new VisualTestException("the tooltip under the mouse is not the row's: " + shown);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the {} row counts {} boxes and its tooltip reads {} ({})",
                BASE_NORTH, line.total(), lines, context.minecraft().getLanguageManager().getSelected());
    }

    private static void checkLooseTooltip(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        StockLine<ItemKey> line = terminal.entry(IRON)
                .orElseThrow(() -> new VisualTestException("no row for the loose iron"));
        List<String> lines = terminal.itemTooltip(line).stream().map(Component::getString).toList();
        String loose = WareworksLang.translateDirect(WareworksLang.TERMINAL_LOOSE_STOCK).getString();
        if (!lines.contains(loose))
            throw new VisualTestException("the loose iron's tooltip does not say '" + loose + "': " + lines);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the loose iron's tooltip says '{}' ({})", loose,
                context.minecraft().getLanguageManager().getSelected());
    }

    /** The crafting panel stays folded in; the status row and the toggle's tooltip give the red reason. */
    private static void checkCraftRefused(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        if (terminal.isCraftPanelOpen())
            throw new VisualTestException("the crafting panel folded out in package mode");
        String reason = WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_MODE_REFUSED).getString();
        String shown = terminal.feedbackLine().map(Component::getString).orElse("");
        if (!shown.equals(reason))
            throw new VisualTestException("the status row reads '" + shown + "', not '" + reason + "'");
        List<String> tooltip = ScreenInput.terminalCraftToggleTooltip(terminal).stream().map(Component::getString)
                .toList();
        if (!tooltip.contains(reason))
            throw new VisualTestException("the toggle's tooltip does not give the reason '" + reason + "': " + tooltip);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the crafting panel stays folded in: '{}' ({})", reason,
                context.minecraft().getLanguageManager().getSelected());
    }

    /**
     * The doors: the signed door is ready, the signless one is listed as refused and greyed; clicking the door button
     * goes here -> signed door -> here, never to the signless one.
     */
    private void doors(VisualScript script, String suffix) {
        script.client("packagemode: hover the door button", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.terminalDoorButton(screen(context))))
                .waitTicks(SETTLE_TICKS)
                .client("packagemode: CHECK both doors are listed, the signless one refused",
                        PackageModeVisualScenario::checkDoorRows)
                .shot("pm-doors" + suffix)
                .client("packagemode: click the door button", PackageModeVisualScenario::clickDoorButton)
                .until("packagemode: a door is chosen", context -> screen(context).chosenDoor().isPresent(),
                        SYNC_TIMEOUT_TICKS)
                .client("packagemode: CHECK the click chose the signed door",
                        context -> checkChosen(context, Optional.of(context.origin().offset(offset(DOOR)))))
                .client("packagemode: click the door button again", PackageModeVisualScenario::clickDoorButton)
                .until("packagemode: the choice moved on", context -> !screen(context).chosenDoor()
                        .equals(Optional.of(context.origin().offset(offset(DOOR)))), SYNC_TIMEOUT_TICKS)
                .client("packagemode: CHECK the second click went back here, past the signless door",
                        context -> checkChosen(context, Optional.empty()));
    }

    private static void checkDoorRows(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        BlockPos signed = context.origin().offset(offset(DOOR));
        BlockPos signless = context.origin().offset(offset(SIGNLESS));
        TerminalDoorRow signedRow = doorRow(terminal, signed);
        TerminalDoorRow signlessRow = doorRow(terminal, signless);
        if (signedRow.state() != DoorState.READY || !BASE_NORTH.equals(signedRow.label()))
            throw new VisualTestException("the signed door should be ready as '" + BASE_NORTH + "': " + signedRow);
        if (signlessRow.state() != DoorState.STRIPS_ADDRESS || signlessRow.state().selectable())
            throw new VisualTestException("the signless Packager door should be refused as STRIPS_ADDRESS: "
                    + signlessRow);
        List<String> tooltip = terminal.doorTooltip().stream().map(Component::getString).toList();
        String word = WareworksLang.translateDirect(DoorState.STRIPS_ADDRESS.langKey().orElseThrow()).getString();
        if (tooltip.stream().noneMatch(line -> line.contains(word)))
            throw new VisualTestException("the door button's tooltip does not say '" + word + "': " + tooltip);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the doors: {} ready, the signless one at {} '{}'; tooltip {}",
                BASE_NORTH, signlessRow.rackAddress(), word, tooltip);
    }

    private static TerminalDoorRow doorRow(WarehouseTerminalScreen terminal, BlockPos pos) {
        return terminal.doors().rows().stream().filter(row -> row.pos().equals(pos)).findFirst()
                .orElseThrow(() -> new VisualTestException("the screen lists no door at " + pos + ": "
                        + terminal.doors()));
    }

    private static void checkChosen(VisualContext context, Optional<BlockPos> expectedDoor) {
        Optional<BlockPos> chosen = screen(context).chosenDoor();
        if (chosen.equals(Optional.of(context.origin().offset(offset(SIGNLESS)))))
            throw new VisualTestException("a click chose the signless Packager door");
        if (!chosen.equals(expectedDoor))
            throw new VisualTestException("the door choice is " + chosen + ", not " + expectedDoor);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the door choice is {}", chosen.map(BlockPos::toShortString)
                .orElse("here"));
    }

    private static void clickDoorButton(VisualContext context) {
        ScreenInput.click(context.minecraft(), ScreenInput.terminalDoorButton(screen(context)));
    }

    /** What a crafted click naming the signless door would get: the server's own refusal, and no request. */
    private static void checkSignlessRefusedByServer(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseTerminalBlockEntity terminal = terminal(level, dock);
        RequestResult result = terminal.requestFromTerminal(context.serverPlayer(server), PackageGroupKey.of(
                        BASE_NORTH), 1, dev.wareworks.core.terminal.RequestAcknowledgement.NONE,
                Optional.of(dock.offset(offset(SIGNLESS)))).result()
                .orElseThrow(() -> new VisualTestException("the terminal asked a question instead of refusing"));
        if (result.isAccepted() || !result.rejection().equals(Optional.of(RequestRejection.DOOR_STRIPS_ADDRESS)))
            throw new VisualTestException("an order to the signless door should be refused as DOOR_STRIPS_ADDRESS: "
                    + result);
        if (!controller(level, dock).openRequests().isEmpty() || terminal.hasOpenPackageOrder())
            throw new VisualTestException("the refused order left a request: " + controller(level, dock)
                    .openRequests());
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the server refuses an order to the signless door: {}",
                result.rejection().orElseThrow());
    }

    // =================================================================================================================
    // act 6: an address order to the signed door, across a save and rejoin
    // =================================================================================================================

    private void addressOrder(VisualScript script) {
        script.server("packagemode: the crane stops, so the order is seen open first",
                        (server, context) -> motor(server.overworld(), context.origin().below()).generatedSpeed
                                .setValue(0));
        ArmVisualScenario.untilOrFail(script, "packagemode: scroll the amount field to " + ORDERED_BOXES, context -> {
            var input = ScreenInput.terminalAmount(screen(context));
            if (input.getState() == ORDERED_BOXES)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input),
                    input.getState() < ORDERED_BOXES ? 1.0 : -1.0);
            return false;
        }, SCREEN_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
        script.server("packagemode: census before the order", (server, context) -> census(server, "before the "
                        + "address order"))
                .client("packagemode: click the " + BASE_NORTH + " row for the door", context -> ScreenInput.click(
                        context.minecraft(), cell(context, PackageGroupKey.of(BASE_NORTH))))
                .until("packagemode: the screen shows the address order",
                        context -> screen(context).packageOrderState().isOpen(), SYNC_TIMEOUT_TICKS)
                .client("packagemode: CHECK the order's line names its address", context -> {
                    String line = screen(context).packageOrderLine().getString();
                    String expected = WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_ORDER_ADDRESS,
                            WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_GROUP, BASE_NORTH),
                            LangNumberFormat.format(ORDERED_BOXES)).getString();
                    if (!line.equals(expected))
                        throw new VisualTestException("the address order's line reads '" + line + "', not '"
                                + expected + "'");
                    LOGGER.info(PREFIX + "packagemode: CHECK PASS the order's line reads '{}'", line);
                })
                .server("packagemode: CHECK the order: " + ORDERED_BOXES + " boxes for the signed door",
                        PackageModeVisualScenario::checkOrderPlaced)
                .client("packagemode: park the mouse", PackageModeVisualScenario::parkMouse)
                .waitTicks(SETTLE_TICKS)
                .shot("pm-ordered");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        script.server("packagemode: the crane runs again", (server, context) -> motor(server.overworld(),
                        context.origin().below()).generatedSpeed.setValue(MOTOR_RPM))
                .serverUntil("packagemode: wait until the first box is at the door and the order is still open",
                        this::halfDelivered, DELIVERY_TIMEOUT_TICKS)
                .server("packagemode: census with the order half delivered", (server, context) -> census(server,
                        "with the address order half delivered"))
                .server("packagemode: save the world mid order", (server, context) -> {
                    savedOrder = terminal(server.overworld(), context.origin()).packageOrderState();
                    watch().pause(true);
                    server.saveEverything(true, true, true);
                    LOGGER.info(PREFIX + "packagemode: saved with the address order at {} of {}",
                            savedOrder.delivered(), savedOrder.wanted());
                });
        VisualWorld.reload(script, worldProfile());
        script.serverUntil("packagemode: wait until the rejoined warehouse is ready",
                        PackageModeVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("packagemode: CHECK the order came back as it was saved", this::checkOrderAfterRejoin)
                .server("packagemode: census after the rejoin", (server, context) -> census(server,
                        "after the rejoin mid order"))
                .server("packagemode: the crane runs again", (server, context) -> motor(server.overworld(),
                        context.origin().below()).generatedSpeed.setValue(MOTOR_RPM))
                .serverUntil("packagemode: wait until all " + ORDERED_BOXES + " boxes are at the door",
                        this::orderDelivered, DELIVERY_TIMEOUT_TICKS)
                .server("packagemode: census with the boxes at the door", (server, context) -> census(server,
                        "with the boxes at the door"))
                .shotFrom(DOOR_VIEW, "pm-at-the-door");
        for (int box = 1; box <= ORDERED_BOXES; box++) {
            int sent = box;
            script.server("packagemode: the player's lever on (box " + sent + ")",
                            (server, context) -> lever(server, context, true))
                    .serverUntil("packagemode: wait until box " + sent + " went through the Packager into the drain",
                            (server, context) -> boxesDrained(server, context) == sent, LEVER_TIMEOUT_TICKS)
                    .server("packagemode: the player's lever off", (server, context) -> lever(server, context,
                            false))
                    .server("packagemode: census after box " + sent + " left the door",
                            (server, context) -> census(server, "after box " + sent + " left the door"));
        }
        script.server("packagemode: CHECK the boxes left sealed, with their address and contents",
                        this::checkDrained)
                .shotFrom(DOOR_VIEW, "pm-sent");
    }

    private static void checkOrderPlaced(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        TerminalPackageOrderState state = terminal(server.overworld(), dock).packageOrderState();
        if (!state.isOpen() || state.wanted() != ORDERED_BOXES
                || !state.door().equals(Optional.of(dock.offset(offset(DOOR))))
                || !state.addresses().equals(List.of(BASE_NORTH)))
            throw new VisualTestException("the click should be an address order of " + ORDERED_BOXES + " boxes for "
                    + BASE_NORTH + " to the signed door: " + state);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the click is an address order of {} boxes for {} to the door",
                state.wanted(), state.addresses());
    }

    private boolean halfDelivered(MinecraftServer server, VisualContext context) {
        watch().check();
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        TerminalPackageOrderState state = terminal(level, dock).packageOrderState();
        if (!state.isOpen())
            throw new VisualTestException("the address order ended before it could be saved half delivered: "
                    + state);
        if (state.delivered() < 1L)
            return false;
        // Stop the crane here, so the world is saved with the order open and nothing moves during the quit.
        motor(level, dock.below()).generatedSpeed.setValue(0);
        return crane(level, dock).heldItems().isEmpty();
    }

    private void checkOrderAfterRejoin(MinecraftServer server, VisualContext context) {
        watch().pause(false);
        if (!PackageModeGate.enabled())
            throw new VisualTestException("package mode is off after the second rejoin");
        TerminalPackageOrderState loaded = terminal(server.overworld(), context.origin()).packageOrderState();
        if (!loaded.isOpen() || !loaded.id().equals(savedOrder.id()) || loaded.wanted() != savedOrder.wanted()
                || loaded.delivered() != savedOrder.delivered() || !loaded.door().equals(savedOrder.door()))
            throw new VisualTestException("the address order changed across the rejoin: saved " + savedOrder
                    + ", loaded " + loaded);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS the address order came back open at {} of {}, door {}",
                loaded.delivered(), loaded.wanted(), loaded.door().map(BlockPos::toShortString).orElse("here"));
    }

    private boolean orderDelivered(MinecraftServer server, VisualContext context) {
        watch().check();
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (terminal(level, dock).hasOpenPackageOrder())
            return false;
        long atTheDoor = port(level, dock).bufferedItems().keys().stream().filter(PackageModeGate::isPackage)
                .mapToLong(key -> port(level, dock).bufferedItems().count(key)).sum();
        if (atTheDoor != ORDERED_BOXES)
            throw new VisualTestException("the order ended with " + atTheDoor + " boxes at the door, not "
                    + ORDERED_BOXES);
        StackerCraneBlockEntity crane = crane(level, dock);
        return crane.heldItems().isEmpty() && controller(level, dock).openRequests().isEmpty();
    }

    /** Boxes in the drain barrel under the door's Packager, once its tray is empty again. */
    private static long boxesDrained(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!packager(level, dock).heldBox.isEmpty())
            return -1L;
        return countIn(level, drainBarrelPos(dock));
    }

    /**
     * Every box that left is one of the {@value #BASE_NORTH} boxes that came in by belt, sealed exactly as it was (the
     * door's sign gives it the same address), and the door and the Packager are empty.
     */
    private void checkDrained(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        IItemHandler drain = level.getCapability(Capabilities.ItemHandler.BLOCK, drainBarrelPos(dock), null);
        if (drain == null)
            throw new VisualTestException("no drain barrel under the door");
        List<ItemKey> left = new ArrayList<>();
        for (int slot = 0; slot < drain.getSlots(); slot++) {
            ItemStack stack = drain.getStackInSlot(slot);
            if (stack.isEmpty())
                continue;
            if (!PackageItem.isPackage(stack) || !BASE_NORTH.equals(PackageItem.getAddress(stack))
                    || PackageItem.hasOrderData(stack))
                throw new VisualTestException("something other than a sealed " + BASE_NORTH + " box left the door: "
                        + stack);
            for (int copy = 0; copy < stack.getCount(); copy++)
                left.add(ItemKey.of(stack));
        }
        if (left.size() != ORDERED_BOXES || !boxes.get(BASE_NORTH).containsAll(left) || Set.copyOf(left).size()
                != ORDERED_BOXES)
            throw new VisualTestException("the door should have sent " + ORDERED_BOXES + " different boxes that came "
                    + "in by belt: " + left);
        if (port(level, dock).bufferedItems().totalItems() != 0 || !packager(level, dock).heldBox.isEmpty())
            throw new VisualTestException("the door still holds something");
        for (ItemKey key : left)
            if (controller(level, dock).countOf(key) != 0L)
                throw new VisualTestException("a box that left is still counted in stock: " + key);
        sent = Set.copyOf(left);
        LOGGER.info(PREFIX + "packagemode: CHECK PASS {} boxes left through the signed door sealed, addressed '{}', "
                + "contents unchanged: {}", left.size(), BASE_NORTH, left);
    }

    private static void lever(MinecraftServer server, VisualContext context, boolean on) {
        ServerLevel level = server.overworld();
        BlockPos pos = leverPos(context.origin());
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LeverBlock lever))
            throw new VisualTestException("the lever on the door's Packager is gone: " + state);
        if (state.getValue(LeverBlock.POWERED) == on)
            throw new VisualTestException("the lever is already " + (on ? "on" : "off"));
        lever.pull(state, level, pos, null);
    }

    // =================================================================================================================
    // act 7: the loose stock from before the switch is still served
    // =================================================================================================================

    private void looseRetrieved(VisualScript script) {
        openTerminal(script);
        script.client("packagemode: a new screen delivers here", context -> checkChosen(context, Optional.empty()));
        ArmVisualScenario.untilOrFail(script, "packagemode: scroll the amount field to " + LOOSE_RETRIEVED, context -> {
            var input = ScreenInput.terminalAmount(screen(context));
            if (input.getState() == LOOSE_RETRIEVED)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input),
                    input.getState() < LOOSE_RETRIEVED ? 1.0 : -1.0);
            return false;
        }, SCREEN_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
        script.client("packagemode: click the loose iron", context -> ScreenInput.click(context.minecraft(),
                        cell(context, IRON)))
                .serverUntil("packagemode: wait until the crane delivered the iron into the terminal",
                        (server, context) -> {
                            watch().check();
                            ServerLevel level = server.overworld();
                            return terminal(level, context.origin()).bufferedItems().count(IRON) == LOOSE_RETRIEVED
                                    && crane(level, context.origin()).heldItems().isEmpty()
                                    && controller(level, context.origin()).openRequests().isEmpty();
                        }, DELIVERY_TIMEOUT_TICKS)
                .server("packagemode: CHECK the old loose iron was served", (server, context) -> {
                    long left = controller(server.overworld(), context.origin()).countOf(IRON);
                    if (left != OLD_IRON - LOOSE_RETRIEVED)
                        throw new VisualTestException("the warehouse holds " + left + " loose iron after the click, "
                                + "not " + (OLD_IRON - LOOSE_RETRIEVED));
                    if (looseOutside(server.overworld(), context.origin()) != LOOSE_ON_BELT)
                        throw new VisualTestException("the refused iron did not stay outside the warehouse");
                    LOGGER.info(PREFIX + "packagemode: CHECK PASS {} loose iron from before the switch delivered into "
                            + "the terminal, {} left in stock; the refused iron still waits on the belt",
                            LOOSE_RETRIEVED, left);
                })
                .server("packagemode: census after the loose iron was served", (server, context) -> census(server,
                        "after the old loose iron was served"))
                .client("packagemode: park the mouse", PackageModeVisualScenario::parkMouse)
                .waitTicks(SETTLE_TICKS)
                .shot("pm-loose-retrieved");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
    }

    // =================================================================================================================
    // act 8: the terminal in German
    // =================================================================================================================

    private void terminalGerman(VisualScript script) {
        language.switchTo(script, "packagemode: ", GERMAN);
        openTerminal(script);
        script.client("packagemode: hover the " + BASE_NORTH + " row (German)", context -> hoverRow(context,
                        BASE_NORTH))
                .waitTicks(SETTLE_TICKS)
                .client("packagemode: CHECK the address row's tooltip in German", this::checkGroupTooltip)
                .shot("pm-groups-de")
                .client("packagemode: hover the loose iron (German)",
                        context -> ScreenInput.hover(context.minecraft(), cell(context, IRON)))
                .waitTicks(SETTLE_TICKS)
                .client("packagemode: CHECK the loose row's tooltip in German",
                        PackageModeVisualScenario::checkLooseTooltip)
                .shot("pm-loose-de")
                .client("packagemode: click the crafting panel's toggle (German)", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.terminalCraftToggle(screen(context))))
                .waitTicks(SETTLE_TICKS)
                .client("packagemode: CHECK the panel's refusal in German",
                        PackageModeVisualScenario::checkCraftRefused)
                .shot("pm-craft-off-de");
        doors(script, "-de");
        RequesterVisualScenario.closeScreen(script, "the terminal screen");
        language.switchTo(script, "packagemode: ", ENGLISH);
    }

    // =================================================================================================================
    // screen helpers (client thread)
    // =================================================================================================================

    private static WarehouseTerminalScreen screen(VisualContext context) {
        return TerminalOrder.terminalScreen(context);
    }

    private static WarehouseStockKeeperScreen keeperScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseStockKeeperScreen keeper)
            return keeper;
        throw new VisualTestException("the stock keeper's screen is not open: " + context.minecraft().screen);
    }

    private static ScreenInput.Point cell(VisualContext context, ItemKey key) {
        WarehouseTerminalScreen terminal = screen(context);
        return ScreenInput.terminalCell(terminal, TerminalOrder.cellOf(terminal, key));
    }

    private static void parkMouse(VisualContext context) {
        WarehouseTerminalScreen terminal = screen(context);
        ScreenInput.hover(context.minecraft(), new ScreenInput.Point(terminal.getGuiLeft() + TITLE_CORNER,
                terminal.getGuiTop() + TITLE_CORNER));
    }

    /** The player takes hotbar slot {@code slot} in hand, and the run waits until the client holds it. */
    private static void hotbarSlot(VisualScript script, int slot, String what,
            java.util.function.Predicate<ItemStack> held) {
        script.server("packagemode: take " + what + " in hand", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.getInventory().selected = slot;
            player.connection.send(new ClientboundSetCarriedItemPacket(slot));
        }).until("packagemode: the client holds " + what, context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getInventory().selected == slot && held.test(player.getMainHandItem());
        }, SYNC_TIMEOUT_TICKS);
    }

    // =================================================================================================================
    // build (server thread)
    // =================================================================================================================

    private void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!level.isLoaded(new BlockPos(0, level.getMinBuildHeight(), 0)))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        BranchLayout layout = layout(dock);
        Direction right = layout.sideDirection(Side.RIGHT);
        Direction left = layout.sideDirection(Side.LEFT);
        BlockPos from = dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(left, CLEAR_MARGIN);
        BlockPos to = dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, CLEAR_MARGIN + 2).above(CLEAR_HEIGHT);
        for (BlockPos pos : BlockPos.betweenClosed(from, to))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        censusBox = AABB.encapsulatingFullBlocks(from.below(), to);

        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        motor(level, dock.below()).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()), WareworksBlocks.WAREHOUSE_CONTROLLER
                .getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        // Storage: barrels behind interfaces (a barrel never merges into a double chest).
        for (int x : STORAGE) {
            BlockPos rack = layout.rackPos(RackPosition.of(x, 0, Side.LEFT));
            level.setBlockAndUpdate(rack.relative(left), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, left));
        }

        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, right.getOpposite()));
        // The keeper with its redstone line: a comparator reading it (FACING points at what it reads) and a lamp.
        level.setBlockAndUpdate(keeperPos(dock), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, right.getOpposite()));
        level.setBlockAndUpdate(comparatorPos(dock), Blocks.COMPARATOR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, right.getOpposite()));
        level.setBlockAndUpdate(lampPos(dock), Blocks.REDSTONE_LAMP.defaultBlockState());

        // The input and the player's belt: a barrel over a hopper that pushes onto the belt, the belt into the input.
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, right.getOpposite()));
        BlockPos beltStart = beltStartPos(dock);
        BlockPos beltEnd = beltEndPos(dock);
        BeltConnectorItem.createBelts(level, beltStart, beltEnd);
        if (!AllBlocks.BELT.has(level.getBlockState(beltStart)) || !AllBlocks.BELT.has(level.getBlockState(beltEnd)))
            throw new VisualTestException("the belt was not created between " + beltStart + " and " + beltEnd);
        level.setBlockAndUpdate(beltMotorPos(dock), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE));
        motor(level, beltMotorPos(dock)).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(feedHopperPos(dock),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, right.getOpposite()));
        level.setBlockAndUpdate(feedBarrelPos(dock), Blocks.BARREL.defaultBlockState());

        // The door with a sign: port, Packager at its back, sign beside it, the player's lever on its front, a hopper
        // under it draining into a barrel.
        buildDoor(level, layout, DOOR);
        level.setBlockAndUpdate(leverPos(dock), Blocks.LEVER.defaultBlockState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, right)
                .setValue(LeverBlock.POWERED, false));
        BlockPos sign = signPos(dock);
        level.setBlockAndUpdate(sign.below(), Blocks.POLISHED_ANDESITE.defaultBlockState());
        level.setBlockAndUpdate(sign, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0));
        if (!(level.getBlockEntity(sign) instanceof SignBlockEntity entity)
                || !entity.setText(new SignText().setMessage(0, Component.literal(BASE_NORTH)), true))
            throw new VisualTestException("the door's sign did not take its text");
        level.setBlockAndUpdate(drainHopperPos(dock),
                Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, right));
        level.setBlockAndUpdate(drainBarrelPos(dock), Blocks.BARREL.defaultBlockState());
        // The door without a sign.
        buildDoor(level, layout, SIGNLESS);
    }

    private static void buildDoor(ServerLevel level, BranchLayout layout, RackPosition rack) {
        Direction outward = layout.sideDirection(rack.side());
        BlockPos port = layout.rackPos(rack);
        level.setBlockAndUpdate(port, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
        level.setBlockAndUpdate(port.relative(outward),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, outward));
    }

    private static void equip(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos lift = context.origin().above(5);
        player.teleportTo(server.overworld(), lift.getX() + 0.5, lift.getY(), lift.getZ() + 0.5, player.getYRot(),
                player.getXRot());
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.getInventory().setItem(SLOT_FILTER, AllItems.PACKAGE_FILTER.asStack());
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!level.isLoaded(dock) || !level.isLoaded(layout(dock).rackPos(SIGNLESS)))
            return false;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        // Outputs: the terminal and both doors; the input; the keeper is a member of its own kind.
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == STORAGE.size()
                && controller.outputStations().size() == 3 && controller.inputStations().size() == 1
                && crane.isControllerLinked();
    }

    private static void aimBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        Direction wanted = layout(dock).sideDirection(Side.RIGHT).getOpposite();
        Direction actual = beltMovement(level, dock);
        LOGGER.info(PREFIX + "packagemode: the belt carries {} and must carry {}", actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motor(level, beltMotorPos(dock));
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static boolean beltAimed(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        return beltMovement(server.overworld(), dock) == layout(dock).sideDirection(Side.RIGHT).getOpposite();
    }

    private static Direction beltMovement(ServerLevel level, BlockPos dock) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, beltStartPos(dock));
        if (belt == null)
            throw new VisualTestException("no belt at " + beltStartPos(dock));
        return belt.getMovementFacing();
    }

    /** Loose goods as a warehouse held them before the switch: straight into its storage barrels. */
    private void stockLooseGoods(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        List<List<ItemStack>> perBarrel = List.of(List.of(IRON.toStack(32), IRON.toStack(16)),
                List.of(COPPER.toStack(32)), List.of(COAL.toStack(40)));
        List<ItemStack> all = new ArrayList<>();
        for (int index = 0; index < perBarrel.size(); index++) {
            BlockPos barrel = layout.rackPos(RackPosition.of(STORAGE.get(index), 0, Side.LEFT))
                    .relative(layout.sideDirection(Side.LEFT));
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, barrel, null);
            if (handler == null)
                throw new VisualTestException("no storage barrel at " + barrel);
            for (ItemStack stack : perBarrel.get(index)) {
                ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
                if (!rest.isEmpty())
                    throw new VisualTestException("a storage barrel refused " + rest);
                all.add(stack);
            }
        }
        expected = SceneItemCensus.plusAllWithBoxes(expected, all);
    }

    private void census(MinecraftServer server, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        SceneItemCensus.assertEqualsWithBoxes(level, censusBox, expected, "packagemode: " + step);
        serverStatus = "census '" + step + "' " + expected.size() + " keys";
        LOGGER.info(PREFIX + "packagemode: census PASS {} (boxes counted as themselves and as their contents)", step);
    }

    // =================================================================================================================
    // geometry
    // =================================================================================================================

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    /** {@code rack}'s block relative to the dock. */
    private static BlockPos offset(RackPosition rack) {
        return LAYOUT_AT_ZERO.rackPos(rack);
    }

    /**
     * A camera relative to the dock in aisle terms: {@code along} the aisle, {@code up}, and {@code out} towards the
     * RIGHT rack side (negative towards the LEFT one), all measured from the dock block's centre column.
     */
    private static CameraView view(String label, double eyeAlong, double eyeUp, double eyeOut, double targetAlong,
            double targetUp, double targetOut) {
        Vec3 eye = point(eyeAlong, eyeUp, eyeOut);
        Vec3 target = point(targetAlong, targetUp, targetOut);
        return CameraView.of(label, eye.x, eye.y, eye.z, target.x, target.y, target.z);
    }

    private static Vec3 point(double along, double up, double out) {
        Direction right = BranchLayout.of(BlockPos.ZERO, AISLE, AisleGeometry.of(RAILS,
                StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT)).sideDirection(Side.RIGHT);
        return new Vec3(0.5 + along * AISLE.getStepX() + out * right.getStepX(), up,
                0.5 + along * AISLE.getStepZ() + out * right.getStepZ());
    }

    private static BlockPos keeperPos(BlockPos dock) {
        return layout(dock).rackPos(KEEPER);
    }

    private static BlockPos comparatorPos(BlockPos dock) {
        return keeperPos(dock).relative(layout(dock).sideDirection(Side.RIGHT));
    }

    private static BlockPos lampPos(BlockPos dock) {
        return comparatorPos(dock).relative(layout(dock).sideDirection(Side.RIGHT));
    }

    private static BlockPos beltEndPos(BlockPos dock) {
        return layout(dock).rackPos(INPUT).relative(layout(dock).sideDirection(Side.RIGHT));
    }

    private static BlockPos beltStartPos(BlockPos dock) {
        return layout(dock).rackPos(INPUT).relative(layout(dock).sideDirection(Side.RIGHT), BELT_LENGTH);
    }

    private static List<BlockPos> beltBlocks(BlockPos dock) {
        List<BlockPos> belt = new ArrayList<>();
        for (int step = 1; step <= BELT_LENGTH; step++)
            belt.add(layout(dock).rackPos(INPUT).relative(layout(dock).sideDirection(Side.RIGHT), step));
        return belt;
    }

    /** Beside the belt's first block, along the aisle: a belt across the rack side turns on the aisle's axis. */
    private static BlockPos beltMotorPos(BlockPos dock) {
        return beltStartPos(dock).relative(AISLE.getOpposite());
    }

    private static BlockPos feedHopperPos(BlockPos dock) {
        return beltStartPos(dock).relative(layout(dock).sideDirection(Side.RIGHT));
    }

    private static BlockPos feedBarrelPos(BlockPos dock) {
        return feedHopperPos(dock).above();
    }

    private static BlockPos packagerPos(BlockPos dock) {
        return layout(dock).rackPos(DOOR).relative(layout(dock).sideDirection(Side.RIGHT));
    }

    private static BlockPos leverPos(BlockPos dock) {
        return packagerPos(dock).relative(layout(dock).sideDirection(Side.RIGHT));
    }

    /** The door's sign: beside its Packager towards the dock, so no other Packager stands next to it. */
    private static BlockPos signPos(BlockPos dock) {
        return packagerPos(dock).relative(AISLE.getOpposite());
    }

    private static BlockPos drainHopperPos(BlockPos dock) {
        return packagerPos(dock).below();
    }

    private static BlockPos drainBarrelPos(BlockPos dock) {
        return drainHopperPos(dock).relative(layout(dock).sideDirection(Side.RIGHT));
    }

    // =================================================================================================================
    // lookups
    // =================================================================================================================

    private static long countIn(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no inventory at " + pos);
        long count = 0L;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            count += handler.getStackInSlot(slot).getCount();
        return count;
    }

    private static long itemsOnBelt(ServerLevel level, BlockPos dock) {
        long count = 0L;
        for (BlockPos pos : beltBlocks(dock))
            count += countIn(level, pos);
        return count;
    }

    private static long countOf(ServerLevel level, List<BlockPos> positions, ItemKey key) {
        long count = 0L;
        for (BlockPos pos : positions) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null)
                continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && ItemKey.of(stack).equals(key))
                    count += stack.getCount();
            }
        }
        return count;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, BlockPos dock) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the crane is missing");
        return crane;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the terminal is missing");
        return terminal;
    }

    private static WarehouseInputBlockEntity input(ServerLevel level, BlockPos dock) {
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout(dock).rackPos(INPUT));
        if (input == null)
            throw new VisualTestException("the input is missing");
        return input;
    }

    private static WarehouseOutputBlockEntity port(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(dock).rackPos(DOOR));
        if (port == null)
            throw new VisualTestException("the door's port is missing");
        return port;
    }

    private static WarehouseStockKeeperBlockEntity keeper(ServerLevel level, BlockPos dock) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER.getNullable(level,
                keeperPos(dock));
        if (keeper == null)
            throw new VisualTestException("the stock keeper is missing");
        return keeper;
    }

    private static PackagerBlockEntity packager(ServerLevel level, BlockPos dock) {
        PackagerBlockEntity packager = AllBlockEntityTypes.PACKAGER.getNullable(level, packagerPos(dock));
        if (packager == null)
            throw new VisualTestException("the door's Packager is missing");
        return packager;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }
}
