package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.redstone.DirectedDirectionalBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageHandover;
import dev.wareworks.content.station.PackageUnpackSummary;
import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.port.PackagerSignAddress;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "packages": a real Create package <b>leaves one warehouse and arrives at another</b>
 * ({@code docs/warehouse-system.md} §3.2.5, M26, issue #18).
 * <p>
 * Two complete warehouses stand ten blocks apart, and the only thing between them is a belt. The out door of the first
 * is a Create Packager whose back touches a warehouse port, with a Smart Observer watching that port and a plain vanilla
 * sign spelling the address; the in door of the second is a Packager whose back touches a warehouse input. Not one line
 * of Wareworks code is in that item path — the whole handover is two mods fitting together, which is exactly why it is
 * worth photographing and counting.
 * <p>
 * <b>What the run proves, and asserts on the server before the shot that shows it:</b>
 * <ol>
 *   <li><b>the goods leave as an addressed box</b> — the crane fills the port, the Smart Observer powers the Packager,
 *       and the box in its tray carries the string from the sign and the iron from the rack (the {@code tray} shot, with
 *       the ticks frozen mid-animation);</li>
 *   <li><b>the box really travels</b> — it rides the belt as an ordinary item, which the frozen {@code box-on-belt} shot
 *       catches and the belt's own inventory confirms;</li>
 *   <li><b>the other warehouse takes it apart and stores it</b> — the belt funnel hands the box to the second
 *       Packager, the warehouse input counts one package opened, and the second crane puts the iron into its racks;</li>
 *   <li><b>both doors say what they are doing</b> — five goggle tooltips: the address, the missing address, the
 *       {@code LINKED} dead end, the in door's counter, and the refusal nothing else in the game diagnoses;</li>
 *   <li><b>and they say it in German too</b> — four more shots after a real language switch, because
 *       {@code de_de.json} is hand-written and nothing else in the build ever renders these rows.</li>
 * </ol>
 * An {@link SceneItemCensus} spanning <b>both</b> warehouses and the belt between them is taken around every move. It is
 * the reason this scenario could only be written after the census learned that a package counts as its contents and the
 * box as nothing: before that, sixteen iron ingots disappearing into a box would have read as sixteen items lost.
 * <p>
 * <b>Why the doors stand one level up.</b> A Packager has to be drained by something, and the honest drain for a block
 * whose only capability is an item handler is a vanilla hopper underneath it ({@link PortsVisualScenario} uses the same
 * shape for its ports). So the port and the input sit at rack level 1, the hopper and the belt at level 0, and the belt
 * funnel that unloads at the far end sits back at level 1 beside the second Packager.
 * <p>
 * <b>Why this scenario needs a real player.</b> Five of its shots are goggle tooltips, and Create only draws one for a
 * non-spectator who looks at a block within reach, so it runs with {@link VisualWorldProfile#playable} and sets that
 * reach back to 0 for the world shots — a value box under the crosshair would otherwise cover the very block the shot is
 * about. The design pass called the default camera profile correct here; it is not, for that reason.
 */
public final class PackagesVisualScenario implements VisualScenario {
    public static final String NAME = "packages";

    /** Its own throw-away world: a scenario with a player must not reuse the camera world of another one. */
    private static final String WORLD_FOLDER = "wareworks_visual_packages";
    /** Two warehouses, a belt, a crane trip at each end and nine goggle shots need more than the default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 20L * 60L * 1000L;

    private static final Direction AISLE = Direction.EAST;
    /** Z of the second warehouse's dock; the belt crosses the whole gap between the two. */
    private static final int FAR_DOCK_Z = 10;
    private static final int RAILS = 6;
    private static final int MOTOR_RPM = 128;
    private static final int BELT_RPM = 32;
    /** The level both doors stand on, so a hopper fits underneath the out door's Packager. */
    private static final int DOOR_LEVEL = 1;

    /** The out door: far enough from the dock that the crane really travels to it. */
    private static final RackPosition PORT = RackPosition.of(4, DOOR_LEVEL, Side.RIGHT);
    /** The in door of the second warehouse, on the plane the belt arrives at. */
    private static final RackPosition INPUT = RackPosition.of(5, DOOR_LEVEL, Side.LEFT);
    /** Storage of either warehouse: two locations near its dock, so both crane trips cross the aisle. */
    private static final int STORAGE_FIRST = 1;
    private static final int STORAGE_LAST = 2;

    /** What the first warehouse holds and the door sends: one stack, so the whole story is one box. */
    private static final ItemKey SENT = ItemKey.of(Items.IRON_INGOT);
    private static final int IN_STOCK = 16;
    /** The address a player wrote on the sign at the out door. */
    private static final String ADDRESS = "Base North";
    private static final String ENGLISH = "en_us";
    private static final String GERMAN = "de_de";

    /** Blocks cleared to air around the two aisles and above the floor. */
    private static final int CLEAR_MARGIN = 5;
    private static final int CLEAR_HEIGHT = 10;
    /** Blocks added around the scene bounds for the census box, so a dropped item would be inside it. */
    private static final int CENSUS_MARGIN = 2;

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int PORT_READY_TIMEOUT_TICKS = 400;
    private static final int MOMENT_TIMEOUT_TICKS = 2400;
    private static final int ARRIVED_TIMEOUT_TICKS = 3600;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 10;
    /** More than Create's lazy tick rate of 10, so {@code recheckIfLinksPresent} has certainly run. */
    private static final int LAZY_TICKS = 25;
    /**
     * Animation ticks left when the tray shot is taken. The Packager's outward cycle counts {@code CYCLE} down to 0 and
     * the box rises out of the tray as it does, so the box is only worth photographing in the second half of it.
     */
    private static final int TRAY_HALF_OUT_TICKS = 8;

    // --- cameras, all relative to the first warehouse's dock ----------------------------------------------------------

    /** Everything at once, from the side: two warehouses with the belt running the whole way between them. */
    private static final CameraView WHOLE = CameraView.of("whole", -5.0, 5.5, 4.5, 2.5, 1.5, 5.0);
    /**
     * The out door from outside the first warehouse: port, Smart Observer, Packager, wire, sign, the hopper that drains
     * it and the belt it loads. Steeply enough from above that the observer on top of the port is not hidden behind the
     * Packager in front of it — a shallow angle photographs one grey block covering another.
     */
    private static final CameraView OUT_DOOR = CameraView.of("out-door", 6.4, 4.2, 4.0, 4.6, 1.7, 2.0);
    /**
     * The in door: looked at <b>along the belt</b>, because the funnel that unloads it and the Packager it hands to
     * stand side by side across the lane, and any view along that pair photographs one covering the other.
     */
    private static final CameraView IN_DOOR = CameraView.of("in-door", 6.4, 3.6, 5.0, 4.9, 1.4, 8.6);
    /** Down the belt from beside the out door: the whole way a box travels, in one frame. */
    private static final CameraView LANE = CameraView.of("lane", 8.0, 5.2, -1.5, 4.2, 0.9, 7.5);
    /** The racks of the second warehouse, where what arrived ends up. */
    private static final CameraView FAR_RACKS = CameraView.of("far-racks", 5.2, 3.6, 14.2, 1.8, 1.2, 11.4);
    /**
     * Goggle shot of the out door's port: from above the aisle, aimed at the <b>aisle face</b>, which is the one face
     * the port carries no value box on ({@code CenteredSideValueBoxTransform} excludes {@code DOWN} and {@code FACING}),
     * and a value box the crosshair really hits makes Create's overlay bail out before it draws a line.
     */
    private static final CameraView AT_PORT = CameraView.of("at-port", 2.2, 3.4, 0.5, 4.5, 1.5, 1.0);
    /** Goggle shot of the in door's input, from above the second warehouse's aisle, aimed at its aisle face. */
    private static final CameraView AT_INPUT = CameraView.of("at-input", 3.0, 3.4, 10.5, 5.5, 1.5, 10.0);

    private static final List<CameraView> ALL_VIEWS = List.of(WHOLE, OUT_DOOR, IN_DOOR, LANE, FAR_RACKS, AT_PORT,
            AT_INPUT);

    /** Following camera for the frozen moments. */
    private static final double BLOCK_CENTER = 0.5;

    /** Distinct, storable items that fill the second input's buffer for the refusal shot. */
    private static final List<Item> FILLERS = List.of(Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL,
            Items.OAK_LOG, Items.STONE, Items.GLASS, Items.BRICK, Items.CLAY_BALL, Items.FLINT, Items.COAL,
            Items.STICK, Items.BONE, Items.LEATHER, Items.PAPER, Items.WHEAT, Items.CARROT, Items.POTATO,
            Items.APPLE, Items.EGG, Items.FEATHER, Items.STRING, Items.SUGAR_CANE, Items.KELP, Items.CACTUS,
            Items.PUMPKIN, Items.MELON_SLICE);
    private static final int STACK = 64;
    /** How long a language switch may take; it rebuilds every texture atlas. */
    private static final int RELOAD_TIMEOUT_TICKS = 600;

    /** The census box of both warehouses and the belt, set once the origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene holds, for the census; the items only ever move inside the box. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Switches the client's language for the second goggle pass ({@link VisualLanguage}). */
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: the goggle shots need a non-spectator with the vanilla reach. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("packages: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "packages run"))
                .server("packages: clear the area and place both creative motors", this::placeMotors)
                .server("packages: build the sending warehouse with its out door",
                        PackagesVisualScenario::buildSender)
                .server("packages: build the receiving warehouse with its in door",
                        PackagesVisualScenario::buildReceiver)
                .server("packages: build the belt between the two doors", PackagesVisualScenario::buildLane)
                .serverUntil("packages: wait until both controllers are ready with every member",
                        PackagesVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("packages: aim the belt towards the receiving warehouse", PackagesVisualScenario::aimBelt)
                .serverUntil("packages: wait until the belt really carries that way",
                        PackagesVisualScenario::beltAimed, SYNC_TIMEOUT_TICKS)
                // A creative player standing on the ground switches flying off at once, so it is lifted first.
                .server("packages: lift the player into the air", PackagesVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("packages: the player flies and wears Engineer's Goggles",
                        PackagesVisualScenario::equipPlayer)
                .server("packages: the out door asks for " + IN_STOCK + " " + SENT + ", unwired",
                        PackagesVisualScenario::configurePort)
                .serverUntil("packages: wait until the controller has read the out door",
                        PackagesVisualScenario::portReady, PORT_READY_TIMEOUT_TICKS)
                .server("packages: put " + IN_STOCK + " " + SENT + " into the sending warehouse", this::stockSender)
                .server("packages: check that every camera stands in air",
                        PackagesVisualScenario::assertCamerasAreFree)
                .server("packages: take the baseline census", (server, context) -> census(server, "before sending"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, and both doors carry
        // one, so the world shots are taken by a player who reaches nothing.
        reach(script, 0.0);
        fly(script);
        for (CameraView view : List.of(WHOLE, OUT_DOOR, IN_DOOR, LANE))
            script.shotFrom(view, "idle");

        if (pass == VisualPass.FLYWHEEL) {
            script.server("packages: both creative motors to " + MOTOR_RPM + " RPM",
                    PackagesVisualScenario::powerOn);
            // The one moment that is the whole feature: the box in the Packager's tray, mid-animation.
            moment(script, "tray", PackagesVisualScenario::boxInTheTray, PackagesVisualScenario::trayView);
            // And the moment that proves it travels: the box riding the belt as an ordinary item.
            moment(script, "box-on-belt", PackagesVisualScenario::boxOnTheBelt, PackagesVisualScenario::beltView);
            script.serverUntil("packages: wait until the iron has arrived in the other warehouse's racks",
                            PackagesVisualScenario::arrived, ARRIVED_TIMEOUT_TICKS)
                    .server("packages: census after the whole package made the journey",
                            (server, context) -> census(server, "after arriving"))
                    .server("packages: check what left, what travelled and what arrived", this::assertArrived);
        }

        fly(script);
        for (CameraView view : List.of(OUT_DOOR, IN_DOOR, FAR_RACKS))
            script.shotFrom(view, "done");

        if (pass == VisualPass.FLYWHEEL)
            goggles(script);
    }

    @Override
    public String status(VisualContext context) {
        return clientPackager(context, PackagesVisualScenario::senderPackagerPos)
                .map(packager -> String.format(Locale.ROOT, "tray=%s anim=%d",
                        packager.heldBox.isEmpty() ? "empty" : "box", packager.animationTicks))
                .orElse("tray=?") + GoggleShots.describeHover(context);
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

    /** The first warehouse: aisle, rack wall, and the out door with everything a package door needs. */
    private static void buildSender(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        buildAisle(level, dock);
        buildStorage(level, senderLayout(dock), Side.LEFT);

        BranchLayout layout = senderLayout(dock);
        BlockPos port = layout.rackPos(PORT);
        level.setBlockAndUpdate(port, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, layout.sideDirection(PORT.side()).getOpposite()));

        // The Packager's back touches the port, which is the whole rule of a package door: its target inventory is the
        // block at packagerPos - FACING (PackageHandover), so FACING points from the station towards the Packager.
        Direction outward = layout.sideDirection(PORT.side());
        BlockPos packager = port.relative(outward);
        level.setBlockAndUpdate(packager,
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, outward));
        // A Smart Observer looking straight down into the port sustains a signal while anything is extractable there,
        // so the door empties itself; one block of wire carries that signal to the Packager, which the observer cannot
        // touch directly (two orthogonally adjacent blocks share no common neighbour).
        level.setBlockAndUpdate(port.above(), AllBlocks.SMART_OBSERVER.getDefaultState()
                .setValue(DirectedDirectionalBlock.TARGET, AttachFace.FLOOR)
                .setValue(DirectedDirectionalBlock.FACING, AISLE));
        level.setBlockAndUpdate(packager.above(), Blocks.REDSTONE_WIRE.defaultBlockState());
        // The address: a plain vanilla sign on a post beside the Packager. A standing sign needs a solid block under
        // it or the next neighbour update pops it off, so the post is part of the build.
        BlockPos sign = signPos(dock);
        level.setBlockAndUpdate(sign.below(), Blocks.POLISHED_ANDESITE.defaultBlockState());
        placeSign(level, sign);
    }

    /** The second warehouse: aisle, rack wall, and the in door the belt delivers to. */
    private static void buildReceiver(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = farDock(context.origin());
        buildAisle(level, dock);
        buildStorage(level, receiverLayout(context.origin()), Side.RIGHT);

        BranchLayout layout = receiverLayout(context.origin());
        BlockPos input = layout.rackPos(INPUT);
        level.setBlockAndUpdate(input, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        Direction outward = layout.sideDirection(INPUT.side());
        level.setBlockAndUpdate(input.relative(outward),
                AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, outward));
    }

    /** Dock, controller, rails and the crane's creative motor of one warehouse. */
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
     * The belt between the two doors, and the two blocks that load and unload it.
     * <p>
     * A vanilla <b>hopper</b> under the out door's Packager is the drain: a Packager answers
     * {@code Capabilities.ItemHandler.BLOCK} with its tray, so anything that pulls from above empties it, and a hopper
     * needs neither rotation nor a filter. At the far end an <b>andesite belt funnel</b> in the {@code PULLING} shape
     * takes whatever arrives off the belt and hands it to the block at {@code facing.getOpposite()} — here the second
     * Packager, which unwraps it into the warehouse input behind it. The funnel faces along the aisle axis, i.e.
     * perpendicular to the belt's own direction, which is what makes it take items off rather than put them on.
     */
    private static void buildLane(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BlockPos drain = drainPos(dock);
        level.setBlockAndUpdate(drain, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, laneDirection()));

        BlockPos start = beltStart(dock);
        BlockPos end = beltEnd(dock);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the belt was not created between " + start + " and " + end);
        // A belt along Z turns on the axis across it, so its motor stands beside the belt's first pulley.
        // On the far side of the belt from the doors: a creative motor is bright magenta, and beside the out door it
        // would be the loudest thing in every shot of it.
        BlockPos beltMotor = start.relative(AISLE.getOpposite());
        level.setBlockAndUpdate(beltMotor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE));
        motor(level, beltMotor).generatedSpeed.setValue(BELT_RPM);
        // A belt that ends in open air ejects its items onto the floor; a solid face makes them queue instead, which is
        // the right picture if the door at the far end is ever busy.
        level.setBlockAndUpdate(end.relative(laneDirection()), Blocks.POLISHED_ANDESITE.defaultBlockState());

        BlockPos funnel = end.above();
        Direction towardsPackager = AISLE;
        // Brass rather than andesite, which does the same job here (an empty filter takes everything): the Packager
        // beside it is grey and open too, and two grey open boxes side by side read as one block in a screenshot.
        level.setBlockAndUpdate(funnel, AllBlocks.BRASS_BELT_FUNNEL.getDefaultState()
                .setValue(BeltFunnelBlock.HORIZONTAL_FACING, towardsPackager.getOpposite())
                .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.Shape.PULLING));
        if (!funnel.relative(towardsPackager).equals(receiverPackagerPos(dock)))
            throw new VisualTestException("the belt funnel at " + funnel + " does not hand over to the in door's "
                    + "Packager at " + receiverPackagerPos(dock));
    }

    /** Both controllers know their whole layout, both cranes are linked, and both doors are members. */
    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        return aisleReady(level, dock, 1, 0) && aisleReady(level, farDock(dock), 0, 1);
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

    /** The out door asks for one stack of iron and works unwired, so the only redstone in the scene is the observer's. */
    private static void configurePort(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity port = port(server.overworld(), context.origin());
        RequestFilterBehaviour filter = filterOf(port);
        if (!filter.setFilter(SENT.toStack()))
            throw new VisualTestException("the out door refused the filter " + SENT);
        filter.count = IN_STOCK;
        port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
        LOGGER.info(PREFIX + "packages: the out door asks for {} x {} on {}", filter.count, SENT,
                port.redstoneMode());
    }

    private static boolean portReady(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity port = port(server.overworld(), context.origin());
        return port.requestAmount() == IN_STOCK && port.filterKey().isPresent();
    }

    /** Fills the first warehouse through its own storage location, exactly as a crane would have stored it. */
    private void stockSender(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = senderLayout(context.origin());
        RackPosition rack = RackPosition.of(STORAGE_FIRST, 0, Side.LEFT);
        BlockPos chest = layout.rackPos(rack).relative(layout.sideDirection(Side.LEFT));
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (handler == null)
            throw new VisualTestException("no chest behind the storage location at " + chest);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, SENT.toStack(IN_STOCK), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the sending warehouse's chest refused " + rest);
        expected = SceneItemCensus.plus(Map.of(), SENT, IN_STOCK);
    }

    private static void powerOn(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(MOTOR_RPM);
        motor(level, farDock(dock).below()).generatedSpeed.setValue(MOTOR_RPM);
    }

    // --- the belt ------------------------------------------------------------------------------------------------

    private static void aimBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos start = beltStart(context.origin());
        Direction actual = beltMovement(level, start);
        LOGGER.info(PREFIX + "packages: the belt carries {} and must carry {}", actual, laneDirection());
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

    // --- the journey ---------------------------------------------------------------------------------------------

    /** Everything arrived: the first warehouse is empty, nothing is on the belt, and the second holds the iron. */
    private static boolean arrived(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity receiver = controller(level, farDock(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, farDock(dock));
        return crane != null && receiver.countOf(SENT) == IN_STOCK && crane.craneState().phase() == CranePhase.IDLE
                && crane.heldItems().isEmpty();
    }

    /** The claim every later shot rests on: the iron left one warehouse in a box and is stock in the other. */
    private void assertArrived(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        long left = controller(level, dock).countOf(SENT);
        long stored = controller(level, farDock(dock)).countOf(SENT);
        PackageUnpackSummary packages = observedPackages(input(level, dock));
        LOGGER.info(PREFIX + "packages: sender holds {}, receiver holds {}, packages opened {}", left, stored,
                packages.opened());
        if (left != 0)
            throw new VisualTestException("the sending warehouse still holds " + left + " " + SENT);
        if (stored != IN_STOCK)
            throw new VisualTestException("the receiving warehouse holds " + stored + " " + SENT + ", not " + IN_STOCK);
        // The address really travelled on the box, which is the only thing a sign can ever be worth.
        BlockPos packager = senderPackagerPos(dock);
        String address = PackageHandover.addressAt(level, packager);
        if (!ADDRESS.equals(address))
            throw new VisualTestException("the out door names the address '" + address + "', not '" + ADDRESS + "'");
        if (packages.opened() < 1)
            throw new VisualTestException("the in door reports " + packages.opened() + " packages opened");
        LOGGER.info(PREFIX + "packages: CHECK a package addressed '{}' carried {} {} from one warehouse to the other",
                address, IN_STOCK, SENT);
    }

    private void census(MinecraftServer server, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        SceneItemCensus.assertEquals(level, censusBox, expected, step);
        LOGGER.info(PREFIX + "packages: census {} -> {}", step, SceneItemCensus.describe(expected));
    }

    // --- frozen moments (client thread, synced block entities) ------------------------------------------------------

    private static void moment(VisualScript script, String moment, java.util.function.Predicate<VisualContext> condition,
            java.util.function.Function<VisualContext, CameraView> view) {
        script.until("packages: wait for the moment '" + moment + "'", condition, MOMENT_TIMEOUT_TICKS)
                .freeze(true)
                .camera(moment, view)
                .shot(moment)
                .freeze(false);
    }

    /** The out door's Packager with a box in its tray, caught while the tray is still moving. */
    private static boolean boxInTheTray(VisualContext context) {
        return clientPackager(context, PackagesVisualScenario::senderPackagerPos)
                .filter(packager -> !packager.heldBox.isEmpty() && packager.animationTicks > 0
                        && packager.animationTicks <= TRAY_HALF_OUT_TICKS).isPresent();
    }

    /** A Create package really riding the belt, which is what tells a handover from a teleport. */
    private static boolean boxOnTheBelt(VisualContext context) {
        return packageOnTheBelt(context).isPresent();
    }

    /** Where the package is on the belt right now, in world coordinates relative to the scene origin. */
    private static Optional<Double> packageOnTheBelt(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        BeltBlockEntity first = AllBlockEntityTypes.BELT.getNullable(level, beltStart(context.origin()));
        BeltBlockEntity belt = first == null ? null : first.getControllerBE();
        if (belt == null || belt.getInventory() == null)
            return Optional.empty();
        for (TransportedItemStack transported : belt.getInventory().getTransportedItems())
            if (PackageItem.isPackage(transported.stack))
                return Optional.of((double) transported.beltPosition);
        return Optional.empty();
    }

    /** Close beside the out door, looking into the Packager's tray while the tray is still moving. */
    private static CameraView trayView(VisualContext context) {
        return CameraView.of("tray", 5.9, 4.4, 3.9, 4.5, 1.8, 2.5);
    }

    /**
     * Across the belt near its loading end, where the box always is when the moment triggers: the condition fires as
     * soon as a package is on the belt, and the freeze that follows holds it there.
     */
    private static CameraView beltView(VisualContext context) {
        return CameraView.of("box-on-belt", PORT.x() + 3.4, 2.9, 4.5, PORT.x() + BLOCK_CENTER, 1.1, 4.5);
    }

    private static Optional<PackagerBlockEntity> clientPackager(VisualContext context,
            java.util.function.Function<BlockPos, BlockPos> where) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(where.apply(context.origin())) instanceof PackagerBlockEntity packager
                ? Optional.of(packager) : Optional.empty();
    }

    // --- the seven goggle rows, in both languages ---------------------------------------------------------------

    /**
     * Every line M26 added, each on the state that makes it true: the address, the missing address, the {@code LINKED}
     * dead end, the in door's counter and the refusal.
     * <p>
     * They are grouped at the end rather than told along the way, because the sign and the Stock Link are taken off and
     * put back between the shots, and doing that mid-journey would be a story about a player fiddling rather than about
     * a package travelling.
     */
    private void goggles(VisualScript script) {
        reach(script, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, AT_PORT, "goggles-door", PackagesVisualScenario::portPos,
                PackagesVisualScenario::doorReadable, PackagesVisualScenario::checkAddressedDoor);

        script.server("packages: take the sign off the out door's Packager",
                (server, context) -> server.overworld().setBlockAndUpdate(signPos(context.origin()),
                        Blocks.AIR.defaultBlockState()));
        fly(script);
        GoggleShots.shot(script, NAME, AT_PORT, "goggles-door-no-address", PackagesVisualScenario::portPos,
                PackagesVisualScenario::doorReadable, PackagesVisualScenario::checkUnaddressedDoor);

        script.server("packages: put the sign back and a Stock Link on the Packager", this::linkThePackager)
                .waitTicks(LAZY_TICKS)
                .serverUntil("packages: wait until the Packager really reports itself linked",
                        PackagesVisualScenario::packagerLinked, SYNC_TIMEOUT_TICKS);
        fly(script);
        GoggleShots.shot(script, NAME, AT_PORT, "goggles-door-linked", PackagesVisualScenario::portPos,
                PackagesVisualScenario::doorReadable, PackagesVisualScenario::checkLinkedDoor);

        fly(script);
        GoggleShots.shot(script, NAME, AT_INPUT, "goggles-in-door", PackagesVisualScenario::inputPos,
                PackagesVisualScenario::openedPackageSynced, PackagesVisualScenario::checkInDoor);

        script.server("packages: fill the in door's buffer and offer it a package it cannot hold whole",
                        this::refuseAPackage)
                .server("packages: census after the refused package", (server, context) -> census(server,
                        "after a package was refused whole"));
        fly(script);
        GoggleShots.shot(script, NAME, AT_INPUT, "goggles-in-door-refused", PackagesVisualScenario::inputPos,
                PackagesVisualScenario::refusalSynced, PackagesVisualScenario::checkRefusedInDoor);

        germanGoggles(script);

        reach(script, 0.0);
        script.client("packages: every check passed",
                context -> LOGGER.info(PREFIX + "packages: ALL CHECKS PASSED (a package left one warehouse addressed "
                        + "by a sign, rode a belt to another, was taken apart and stored there, the census never "
                        + "moved, and both doors say what they are doing, in both languages)"));
    }

    /**
     * All seven rows again, in <b>German</b>: {@code de_de.json} is hand-written, and
     * {@code LangConsistencyTest#thePackageDoorRowsFitAGoggleTooltip} only measures its strings as text — nothing
     * rendered them until this pass, so a row that reads wrongly or draws past its box would have shipped unseen.
     * <p>
     * It re-uses the states the English pass left standing rather than rebuilding them: the in door still holds the
     * refused package on record (nothing has opened one since), and the out door is still linked. Only the two states
     * the gold {@code LINKED} row stands in place of have to be undone again — the link comes off for the address row
     * and the sign for the one that asks for a sign — which is also the one order in which no state is built twice.
     */
    private void germanGoggles(VisualScript script) {
        language.switchTo(script, "packages: ", GERMAN);
        fly(script);
        // The in door first, because its three rows are all on screen at once while the refusal is on record.
        GoggleShots.shot(script, NAME, AT_INPUT, "goggles-in-door-refused-de", PackagesVisualScenario::inputPos,
                PackagesVisualScenario::refusalSynced, PackagesVisualScenario::checkRefusedInDoor);
        fly(script);
        GoggleShots.shot(script, NAME, AT_PORT, "goggles-door-linked-de", PackagesVisualScenario::portPos,
                PackagesVisualScenario::doorReadable, PackagesVisualScenario::checkLinkedDoor);

        script.server("packages: take the Stock Link off the out door's Packager again",
                        (server, context) -> server.overworld().setBlockAndUpdate(
                                senderPackagerPos(context.origin()).above(), Blocks.AIR.defaultBlockState()))
                .waitTicks(LAZY_TICKS);
        fly(script);
        GoggleShots.shot(script, NAME, AT_PORT, "goggles-door-de", PackagesVisualScenario::portPos,
                PackagesVisualScenario::doorOnRedstone, PackagesVisualScenario::checkAddressedDoor);

        script.server("packages: take the sign off once more",
                (server, context) -> server.overworld().setBlockAndUpdate(signPos(context.origin()),
                        Blocks.AIR.defaultBlockState()));
        fly(script);
        GoggleShots.shot(script, NAME, AT_PORT, "goggles-door-no-address-de", PackagesVisualScenario::portPos,
                PackagesVisualScenario::doorOnRedstone, PackagesVisualScenario::checkUnaddressedDoor);

        language.switchTo(script, "packages: ", ENGLISH);
    }

    /** A goggle tooltip of a door is read from the world on the client, so there is nothing to wait for but the block. */
    private static boolean doorReadable(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null
                && PackageHandover.packagerFor(level, portPos(context.origin())).isPresent();
    }

    /**
     * The out door is readable <b>and</b> back on redstone. Create only rechecks the links around a Packager in its
     * lazy tick, so the {@code LINKED} state outlives a removed Stock Link by up to ten ticks — and the address rows
     * exist only while the door answers redstone at all.
     */
    private static boolean doorOnRedstone(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return doorReadable(context) && level != null
                && !PackageHandover.ignoresRedstone(level, senderPackagerPos(context.origin()));
    }

    private static void checkAddressedDoor(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, portPos(context.origin()));
        LOGGER.info(PREFIX + "packages: out door goggles {}", lines);
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGE_HANDOVER).getString());
        GoggleShots.requireLine(lines, WareworksLang.packageAddress(ADDRESS).component().getString());
        GoggleShots.requireNoLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGE_NO_ADDRESS).getString());
        GoggleShots.requireNoLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGER_LINKED).getString());
    }

    private static void checkUnaddressedDoor(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, portPos(context.origin()));
        LOGGER.info(PREFIX + "packages: out door goggles without a sign {}", lines);
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGE_HANDOVER).getString());
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGE_NO_ADDRESS).getString());
    }

    /**
     * With a Stock Link on the Packager the gold warning stands <b>in place of</b> the address row, not beside it: a
     * linked Packager never reaches the branch that applies a sign, so an address row there would predict an address
     * no box will carry, and a "hang a sign" row would ask for a block that changes nothing.
     */
    private static void checkLinkedDoor(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, portPos(context.origin()));
        LOGGER.info(PREFIX + "packages: out door goggles with a Stock Link {}", lines);
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGE_HANDOVER).getString());
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGER_LINKED).getString());
        GoggleShots.requireNoLine(lines, WareworksLang.packageAddress(ADDRESS).component().getString());
        GoggleShots.requireNoLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_PACKAGE_NO_ADDRESS).getString());
    }

    private static boolean openedPackageSynced(VisualContext context) {
        return clientInput(context).filter(input -> input.summary().packages().opened() >= 1).isPresent();
    }

    private static void checkInDoor(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, inputPos(context.origin()));
        LOGGER.info(PREFIX + "packages: in door goggles {}", lines);
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_INPUT_PACKAGE_UNPACKING).getString());
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_INPUT_PACKAGES_OPENED, 1));
    }

    private static boolean refusalSynced(VisualContext context) {
        return clientInput(context).filter(input -> input.summary().packages().hasRefusal()).isPresent();
    }

    /**
     * The line nothing else in the game draws. The two numbers are the stacks the package brought against the slots
     * that were free at that moment, so the check reads them off the summary the tooltip is built from and asserts
     * that the sentence really renders with them, rather than guessing at the package's size.
     */
    private static void checkRefusedInDoor(VisualContext context) {
        BlockPos pos = inputPos(context.origin());
        PackageUnpackSummary packages = clientInput(context)
                .orElseThrow(() -> new VisualTestException("no in door on the client")).summary().packages();
        if (packages.refusedPackageStacks() <= 0)
            throw new VisualTestException("the in door records no refusal: " + packages);
        List<String> lines = GoggleShots.lines(context, pos);
        LOGGER.info(PREFIX + "packages: in door goggles after a refusal {}", lines);
        GoggleShots.requireLine(lines, WareworksLang
                .packageRefused(packages.refusedPackageStacks(), packages.freeSlotsAtRefusal()).component()
                .getString());
    }

    private static Optional<WarehouseInputBlockEntity> clientInput(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(inputPos(context.origin())) instanceof WarehouseInputBlockEntity input
                ? Optional.of(input) : Optional.empty();
    }

    // --- the two states a goggle shot needs built --------------------------------------------------------------------

    /**
     * Puts the sign back and a Create Stock Link on top of the Packager, which is what takes a door off redstone for
     * ever with nothing in the game to read. The wire has to go, because the link stands where it ran.
     * <p>
     * A real block, not a hand-set {@code LINKED} flag: Create heals that flag back in {@code lazyTick} and at the top
     * of {@code activate()} itself, so a faked one would be gone again within ten ticks.
     */
    private void linkThePackager(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        placeSign(level, signPos(dock));
        BlockPos packager = senderPackagerPos(dock);
        level.setBlockAndUpdate(packager.above(), AllBlocks.STOCK_LINK.getDefaultState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, AISLE));
        if (PackagerLinkBlock.getConnectedDirection(level.getBlockState(packager.above())) != Direction.UP)
            throw new VisualTestException("the Stock Link above " + packager + " does not connect downwards");
    }

    private static boolean packagerLinked(MinecraftServer server, VisualContext context) {
        return PackageHandover.ignoresRedstone(server.overworld(), senderPackagerPos(context.origin()));
    }

    /**
     * Fills the in door's buffer until one slot is free and then offers it a package of three item types through the
     * Packager's own capability, exactly as a funnel would. Create consumes a package <b>whole</b> or not at all, so the
     * box comes straight back and the refusal is on record.
     * <p>
     * The receiving crane is stopped first, or it would empty the buffer again before the shot.
     */
    private void refuseAPackage(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, farDock(dock).below()).generatedSpeed.setValue(0);
        WarehouseInputBlockEntity input = input(level, dock);
        int toFill = input.bufferSlots() - 1;
        if (toFill < 1 || toFill > FILLERS.size())
            throw new VisualTestException("the in door has " + input.bufferSlots() + " buffer slots, which this "
                    + "scenario cannot fill");
        Map<ItemKey, Long> filled = expected;
        for (int slot = 0; slot < toFill; slot++) {
            ItemStack filler = new ItemStack(FILLERS.get(slot), STACK);
            if (!input.insert(filler.copy(), false).isEmpty())
                throw new VisualTestException("the in door refused the filler " + filler);
            filled = SceneItemCensus.plus(filled, ItemKey.of(filler), STACK);
        }
        expected = filled;

        BlockPos packager = receiverPackagerPos(dock);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, packager, null);
        if (handler == null)
            throw new VisualTestException("no Packager at the in door of the receiving warehouse");
        ItemStack box = packageOf(new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.GOLD_INGOT, 4),
                new ItemStack(Items.DIAMOND, 2));
        ItemStack back = handler.insertItem(0, box.copy(), false);
        if (back.isEmpty())
            throw new VisualTestException("the in door swallowed a package it has no room for");
        PackageUnpackSummary packages = observedPackages(input);
        if (packages.refusedPackageStacks() <= 0)
            throw new VisualTestException("the in door recorded no refusal: " + packages);
        LOGGER.info(PREFIX + "packages: CHECK a package of three stacks was refused whole ({} stacks in it, {} slots "
                + "free)", packages.refusedPackageStacks(), packages.freeSlotsAtRefusal());
    }

    // --- the player ----------------------------------------------------------------------------------------------

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(6);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    private static void fly(VisualScript script) {
        script.server("packages: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("packages: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, NAME, range);
    }

    /**
     * Every camera of this scenario stands in air, checked while the scene is fresh: a camera view is a place a real,
     * colliding player is teleported to, so a block in the way fails the step minutes later with "the camera did not
     * arrive" and names a position rather than the block that is in the way.
     */
    private static void assertCamerasAreFree(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ServerPlayer player = context.serverPlayer(server);
        for (CameraView view : ALL_VIEWS) {
            CameraView.Placement placement = view.placement(context.origin(), player.getEyeHeight());
            AABB box = player.getDimensions(Pose.STANDING)
                    .makeBoundingBox(placement.x(), placement.y(), placement.z());
            if (!level.noCollision(box))
                throw new VisualTestException("the camera view '" + view.label() + "' stands in a block: a player at "
                        + placement.feet() + " collides with the scene");
        }
        LOGGER.info(PREFIX + "packages: CHECK all {} camera positions are free of blocks", ALL_VIEWS.size());
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

    private static BlockPos inputPos(BlockPos dock) {
        return receiverLayout(dock).rackPos(INPUT);
    }

    /** The out door's Packager: behind the port, where a storage location's inventory would stand. */
    private static BlockPos senderPackagerPos(BlockPos dock) {
        BranchLayout layout = senderLayout(dock);
        return layout.rackPos(PORT).relative(layout.sideDirection(PORT.side()));
    }

    /** The in door's Packager: behind the input, where the belt funnel hands over. */
    private static BlockPos receiverPackagerPos(BlockPos dock) {
        BranchLayout layout = receiverLayout(dock);
        return layout.rackPos(INPUT).relative(layout.sideDirection(INPUT.side()));
    }

    /** The sign that addresses the boxes: on a post along the aisle from the Packager. */
    private static BlockPos signPos(BlockPos dock) {
        return senderPackagerPos(dock).relative(AISLE);
    }

    /** The hopper that drains the out door's Packager: directly underneath it. */
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

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the warehouse controller at " + dock + " is missing");
        return controller;
    }

    private static WarehouseOutputBlockEntity port(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level, portPos(dock));
        if (port == null)
            throw new VisualTestException("the out door's port is missing");
        return port;
    }

    /**
     * The in door's package numbers as the goggle tooltip would show them. A station's summary is <b>throttled</b>: it
     * holds whatever the last observation built, so a server-side read without an observation first answers an empty
     * one — which is exactly the throttle that stops a funnel retrying every tick from sending a packet every tick.
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

    private static RequestFilterBehaviour filterOf(WarehouseOutputBlockEntity port) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(port, FilteringBehaviour.TYPE);
        if (!(behaviour instanceof RequestFilterBehaviour filter))
            throw new VisualTestException("the port at " + port.getBlockPos() + " has no request filter behaviour");
        return filter;
    }

    /**
     * A standing sign carrying {@link #ADDRESS}, turned so its writing faces the out door's cameras.
     * {@code ROTATION} is the yaw the written face points along (0 south, 4 west, 8 north, 12 east).
     */
    private static void placeSign(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 12));
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign))
            throw new VisualTestException("the sign at " + pos + " has no block entity");
        if (!sign.setText(new SignText().setMessage(0, Component.literal(ADDRESS)), true))
            throw new VisualTestException("the sign at " + pos + " did not take its text");
        if (!ADDRESS.equals(PackagerSignAddress.ofSign(List.of(ADDRESS), List.of())))
            throw new VisualTestException("the sign rule no longer reads a one-line sign as its own text");
    }

    /** A deterministic Create package holding {@code contents}, built from the component and never through Create. */
    private static ItemStack packageOf(ItemStack... contents) {
        ItemStack box = PackageStyles.getDefaultBox();
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }
}
