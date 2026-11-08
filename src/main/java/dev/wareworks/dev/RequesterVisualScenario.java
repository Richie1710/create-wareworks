package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.schematics.SchematicExport;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonMenu;
import com.simibubi.create.content.schematics.cannon.SchematicannonScreen;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.utility.CreatePaths;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.remote.RemoteLink;
import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.content.requester.CannonView;
import dev.wareworks.content.requester.RequesterLines;
import dev.wareworks.content.requester.RequesterSummary;
import dev.wareworks.content.requester.SchematicannonRequesterBlock;
import dev.wareworks.content.requester.SchematicannonRequesterBlockEntity;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.requester.RequesterOrder;
import dev.wareworks.core.requester.RequesterStatus;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "requester" (issue #45, ADR-080): a Schematicannon Requester at a <b>real Create Schematicannon</b>, played
 * through the way a player sets it up — with real clicks, a real belt, a real print and a real save, quit and rejoin
 * in the middle of an order — in English and German.
 * <p>
 * <b>The scene.</b> A short powered aisle with a warehouse terminal and a warehouse input beside the dock, a rack wall
 * holding {@value #STONE_IN_STOCK} stone and one patterned banner, and <b>no glass</b>. Behind the terminal the
 * player's own route out of its buffer: an andesite belt funnel over the start of a belt on a creative motor, the belt,
 * and an andesite belt funnel at its end that hands everything into a chest. A lever beside the first funnel is on, so
 * that funnel is paused. A hut of {@value #STONE_BLOCKS} stone, {@value #GLASS_BLOCKS} glass and one patterned banner
 * is built, saved as a schematic the way a player's upload leaves it on the server ({@link SchematicExport}) and
 * cleared again; so is a three-block step for the second pass.
 * <p>
 * <b>The story</b> (first pass, Flywheel on), every step of it a player's:
 * <ol>
 * <li>The Schematicannon is placed from its item by a right-click on the ground beside the chest — which is why it
 * <b>skips missing blocks</b>, as Create sets it up for every player — and the hut's schematic and one gunpowder are
 * carried into it with clicks in its own screen. The requester is placed the same way on the cannon's other side and
 * turns to face it.</li>
 * <li>A sneaking right-click links the Warehouse Remote to the terminal, a right-click on the requester puts it in:
 * the action bar names the terminal and the chat tells the player to turn "Skip missing Blocks" off. The lamp is red
 * ({@code requester-skip-missing}, goggles in English and German).</li>
 * <li>The option is clicked off and Play is pressed in the cannon's screen. The cannon waits for stone, the requester
 * orders {@value #FIRST_STONE_ORDER} (one stack) and the frozen moment of the crane carrying it is shot with the
 * goggles in English and German ({@code requester-ordered}).</li>
 * <li>The crane delivers into the terminal's buffer, where the stone stays while the lever pauses the funnel; three
 * polls order nothing more ({@code requester-in-buffer}, {@code CHECK no duplicate order while the stone lies in the
 * buffer}). A real click on the lever opens the belt. An andesite funnel hands the belt one item at a time and the belt
 * takes the next only once the last has moved on a block, so the stack leaves the buffer over most of a minute — the
 * order stays "in the buffer" all that while — and its last items still ride the belt when the grace begins: frozen
 * there ({@code requester-belt-grace}, goggles in English and German).</li>
 * <li>The second order is exactly the rest of the stone, {@value #SECOND_STONE_ORDER}. Its stone reaches the cannon
 * one item at a time, slower than the cannon prints, so the cannon misses stone again and again while the order waits
 * out its grace — the moment a requester without its grace would order the stone twice. Every poll of that wait is
 * checked ({@code CHECK no duplicate order during the grace while the cannon misses stone}).</li>
 * <li>The cannon then waits for glass, which the warehouse does not have: the lamp is red
 * ({@code requester-out-of-stock}, goggles in English and German) and nothing at all is ordered or produced. Glass is
 * fed into the warehouse input, and the order follows: exactly {@value #GLASS_BLOCKS}.</li>
 * <li>With that order still running the world is saved, left to the title screen and opened again. The order comes
 * back with its request id and completes. Create's cannon reloads its schematic but keeps the item it last missed, so
 * it searches on ({@code PAUSED 'searching'}) without printing — not idle, so the requester gives nothing back — until
 * the player presses Stop and Play in its screen.</li>
 * <li>The banner is ordered with exactly its patterns, the hut is finished ({@code requester-finished}), and the stock
 * fell by exactly the hut.</li>
 * </ol>
 * The second pass (Flywheel off) loads the step's schematic into the same cannon and re-shoots the moment of an order
 * carried by the crane ({@code nofw-requester-ordered}).
 * <p>
 * <b>Items.</b> {@link #census} counts the whole scene and the player ({@link SceneItemCensus}), which takes in the
 * racks, the crane, the terminal and the input, the chest and every item riding the belt (one capability per belt
 * segment); and on top of that what no capability reports: an item a funnel handed the belt in the tick that just
 * ended, the cannon's own slots, every block it has in flight, the remote in the requester's cradle, and every block
 * printed on the two sites as the items its requirement took. The only declared changes are the gunpowder the cannon
 * burns as fuel, the remote's link (part of its item key), the remote copied into the cradle in creative mode, the
 * glass fed into the input, and each schematic Create turns into an empty one when its print is done.
 */
public final class RequesterVisualScenario implements VisualScenario {
    public static final String NAME = "requester";

    private static final String WORLD_FOLDER = "wareworks_visual_requester";
    /** Two prints, four crane trips, a rejoin and eight language switches: far above the default. */
    private static final long RUN_TIMEOUT_MILLIS = 20L * 60L * 1000L;

    // --- the warehouse (offsets from the dock; the aisle runs east, so the right rack side is +z) --------------------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 6;
    /** A crane slow enough that a camera can be in place before it carries the first order. */
    private static final int MOTOR_RPM = 32;
    private static final RackPosition TERMINAL_RACK = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = RackPosition.of(2, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 3;
    private static final int STORAGE_LEVELS = 2;
    /** The stone at the far end of the aisle, so every stone order is a long crane trip. */
    private static final RackPosition STONE_RACK = RackPosition.of(RAILS, 0, Side.LEFT);
    private static final RackPosition BANNER_RACK = RackPosition.of(RAILS - 1, 0, Side.LEFT);

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    /** Where {@link #TERMINAL_RACK} stands; {@link #buildAisle} checks the two agree. */
    private static final BlockPos TERMINAL = new BlockPos(1, 0, 1);

    // --- the player's belt from the terminal's buffer to the cannon's chest ------------------------------------------
    /** The belt runs south, from below the funnel behind the terminal to below the funnel in front of the chest. */
    private static final BlockPos BELT_START = new BlockPos(1, -1, 2);
    private static final BlockPos BELT_END = new BlockPos(1, -1, 8);
    private static final Direction BELT_MOVEMENT = Direction.SOUTH;
    /**
     * The belt's motor beside its first pulley; a belt along z turns on the x axis. A motor facing west turns it
     * north at a positive speed, so the speed is negative ({@link #sceneReady} checks the direction).
     */
    private static final BlockPos BELT_MOTOR = new BlockPos(2, -1, 2);
    private static final int BELT_RPM = -32;
    /** The funnel that takes the buffer onto the belt, and the one that hands the belt's items into the chest. */
    private static final BlockPos OUT_FUNNEL = BELT_START.above();
    private static final BlockPos IN_FUNNEL = BELT_END.above();
    /** A solid face after the belt's end, so nothing is ever thrown off it. */
    private static final BlockPos BELT_STOP = BELT_END.relative(BELT_MOVEMENT);
    /** The lever that pauses the first funnel, on at the start. */
    private static final BlockPos LEVER = new BlockPos(0, 0, 2);
    private static final BlockPos CHEST = IN_FUNNEL.relative(BELT_MOVEMENT);
    private static final BlockPos CANNON = CHEST.east();
    private static final BlockPos REQUESTER = CANNON.east();
    /** Where the player is lifted to before it starts flying: in the air beside the scene. */
    private static final BlockPos LIFT = new BlockPos(-3, 6, 4);
    /** How far west of the terminal's west face the player stands to link the remote, well inside the reach. */
    private static final double LINK_DISTANCE = 2.0;

    // --- the schematics ----------------------------------------------------------------------------------------------
    /** The hut: a stone floor, four glass posts and a patterned banner in the middle; well clear of the cannon. */
    private static final BlockPos SITE = new BlockPos(4, 0, 12);
    private static final int SITE_LENGTH = 10;
    private static final int SITE_WIDTH = 8;
    private static final int SITE_HEIGHT = 2;
    private static final BlockPos BANNER_SPOT = SITE.offset(SITE_LENGTH / 2, 1, SITE_WIDTH / 2);
    private static final List<BlockPos> GLASS_SPOTS = List.of(SITE.offset(0, 1, 0), SITE.offset(SITE_LENGTH - 1, 1, 0),
            SITE.offset(0, 1, SITE_WIDTH - 1), SITE.offset(SITE_LENGTH - 1, 1, SITE_WIDTH - 1));
    /** The second pass's print: three stone in a row beyond the hut. */
    private static final BlockPos STEP = new BlockPos(4, 0, 22);
    private static final int STEP_BLOCKS = 3;
    private static final String HUT_FILE = "wareworks_requester_hut.nbt";
    private static final String STEP_FILE = "wareworks_requester_step.nbt";

    private static final int STONE_BLOCKS = SITE_LENGTH * SITE_WIDTH;
    private static final int GLASS_BLOCKS = 4;
    private static final int STONE_IN_STOCK = 200;
    /** The first order is one stack, the second the rest of what the hut needs. */
    private static final int FIRST_STONE_ORDER = 64;
    private static final int SECOND_STONE_ORDER = STONE_BLOCKS - FIRST_STONE_ORDER;
    /** Twice what the hut needs, so the order is the hut's four and not the stock. */
    private static final int GLASS_FED = 2 * GLASS_BLOCKS;
    private static final ItemKey STONE = ItemKey.of(Items.STONE);
    private static final ItemKey GLASS = ItemKey.of(Items.GLASS);
    private static final ItemKey GUNPOWDER = ItemKey.of(Items.GUNPOWDER);

    // --- the Schematicannon's slots ({@code SchematicannonInventory#isItemValid}) ------------------------------------
    private static final int CANNON_SLOT_SCHEMATIC = 0;
    private static final int CANNON_SLOT_GUNPOWDER = 4;

    // --- the area ----------------------------------------------------------------------------------------------------
    private static final int CLEAR_MIN_X = -4;
    private static final int CLEAR_MAX_X = SITE.getX() + SITE_LENGTH + 2;
    private static final int CLEAR_MIN_Z = -4;
    private static final int CLEAR_MAX_Z = STEP.getZ() + 2;
    private static final int CLEAR_HEIGHT = 8;
    /** The census box: everything above, from the ground the belt lies in to above the hut. */
    private static final int CENSUS_MIN_X = -3;
    private static final int CENSUS_MAX_X = SITE.getX() + SITE_LENGTH + 1;
    private static final int CENSUS_MIN_Y = -1;
    private static final int CENSUS_MAX_Y = 5;
    private static final int CENSUS_MIN_Z = -3;
    private static final int CENSUS_MAX_Z = STEP.getZ() + 1;

    // --- timing ------------------------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int AIM_TIMEOUT_TICKS = 60;
    private static final int CLICK_TIMEOUT_TICKS = 40;
    private static final int HELD_ITEM_TIMEOUT_TICKS = 100;
    private static final int MESSAGE_TIMEOUT_TICKS = 60;
    private static final int SNEAK_TIMEOUT_TICKS = 40;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int CANNON_TIMEOUT_TICKS = 400;
    private static final int MOMENT_TIMEOUT_TICKS = 1200;
    private static final int ORDER_TIMEOUT_TICKS = 3600;
    private static final int PRINT_TIMEOUT_TICKS = 4800;
    private static final int LANGUAGE_RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    /** Ticks after a language switch before anything draws new text: the font atlas is rebuilt by the reload. */
    private static final int LANGUAGE_SETTLE_TICKS = 20;
    /** Client ticks between two clicks on slots, kept above vanilla's 250 ms double-click window. */
    private static final int CLICK_GAP_TICKS = 8;
    /** Polls of the requester the run watches for a second order that must not come. */
    private static final int WATCHED_POLLS = 3;
    /** A crane this far from its target is far enough into the aisle for a picture. */
    private static final double MIN_REMAINING_TRAVEL = 1.5;
    /** Where on the belt the stone must ride for the grace shot: clear of both funnels. */
    private static final float BELT_SHOT_FROM = 1.0f;
    private static final float BELT_SHOT_TO = 5.5f;
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    // --- hotbar ------------------------------------------------------------------------------------------------------
    private static final int SLOT_CANNON = 0;
    private static final int SLOT_REQUESTER = 1;
    private static final int SLOT_REMOTE = 2;
    private static final int SLOT_SCHEMATIC = 3;
    private static final int SLOT_GUNPOWDER = 4;
    private static final int SLOT_STEP_SCHEMATIC = 5;
    /** Never filled: selecting it gives the player an empty hand, which is what opens a block's own screen. */
    private static final int SLOT_EMPTY = 8;

    // --- clicks ------------------------------------------------------------------------------------------------------
    private static final ArmVisualScenario.Click CLICK_CANNON_SPOT = new ArmVisualScenario.Click("ground for the cannon",
            CANNON.below(), Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 2.2, -2.2));
    private static final ArmVisualScenario.Click CLICK_REQUESTER_SPOT = new ArmVisualScenario.Click(
            "ground for the requester", REQUESTER.below(), Direction.UP, new Vec3(0.5, 0.5, 0.5),
            new Vec3(0.0, 2.2, -2.2));
    private static final ArmVisualScenario.Click CLICK_CANNON = new ArmVisualScenario.Click("cannon", CANNON,
            Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 1.9, -1.8));
    private static final ArmVisualScenario.Click CLICK_REQUESTER = new ArmVisualScenario.Click("requester", REQUESTER,
            Direction.UP, new Vec3(0.5, 0.5, 0.5), new Vec3(1.8, 1.9, 0.0));
    private static final ArmVisualScenario.Click CLICK_LEVER = new ArmVisualScenario.Click("lever", LEVER,
            Direction.UP, new Vec3(0.5, 0.2, 0.5), new Vec3(-1.8, 1.9, 0.0));

    // --- camera views (relative to the dock's lower corner) -----------------------------------------------------------
    /**
     * The whole working scene from above the hut's site: the requester's back with its lamp, the cannon and its chest,
     * the belt up to the terminal and the aisle behind it.
     */
    private static final CameraView OVERVIEW = CameraView.of("overview", 8.5, 5.5, REQUESTER.getZ() + 2.5, 2.0, 0.0,
            4.5);
    /** Close to the requester's back face, its lamp and the remote in its cradle, with the cannon behind it. */
    private static final CameraView REQUESTER_CLOSE = CameraView.of("close", REQUESTER.getX() + 3.2, 1.8,
            REQUESTER.getZ() + 2.4, REQUESTER.getX(), 0.5, REQUESTER.getZ());
    /** Along the belt from the west, from the funnel behind the terminal to the chest. */
    private static final CameraView ALONG_BELT = CameraView.of("belt", -2.8, 3.4, 5.0, 1.5, -0.4, 5.7);
    /** Down the aisle from its far end, so the crane carrying an order is seen with the rack wall beside it. */
    private static final CameraView ALONG_AISLE = CameraView.of("aisle", RAILS + 3.0, 3.6, 3.5, 2.0, 1.0, 0.0);
    /** The site of the hut, seen over the cannon. */
    private static final CameraView AT_SITE = CameraView.of("site", 10.0, 6.5, SITE.getZ() - 5.0, 8.5, 0.5,
            SITE.getZ() + 4.0);
    /** The requester's top face from its back, within reach: what a player with goggles sees. */
    private static final CameraView GOGGLE_VIEW = CameraView.of("goggles", REQUESTER.getX() + 2.6, 2.3,
            REQUESTER.getZ() + 0.5, REQUESTER.getX() + 0.5, 1.0, REQUESTER.getZ() + 0.5);

    /** The items a belt takes on in its next tick, which its item capability does not report yet. */
    private static final Field BELT_ARRIVALS = beltArrivalsField();

    /** Server thread: the census box of the scene, set once the origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Server thread: what the census must find right now. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Server thread: the remote's item key, which its link is part of. */
    @Nullable
    private volatile ItemKey remoteKey;
    /** Server thread: the patterned banner as the hut's block entity names it, i.e. the strict requirement. */
    @Nullable
    private volatile ItemKey bannerKey;
    /** Server thread: the deployed schematic items the save produced, handed to the player as they are. */
    private volatile ItemStack hutSchematic = ItemStack.EMPTY;
    private volatile ItemStack stepSchematic = ItemStack.EMPTY;
    /** Server thread: the glass order's request id, followed across the save, quit and rejoin. */
    @Nullable
    private volatile UUID glassRequest;
    /** Server thread: the game time the watch for a duplicate order started at. */
    private volatile long watchSince = Long.MIN_VALUE;
    /** Server thread: how often the watch saw the order in its grace while the cannon missed stone. */
    private volatile int graceSamples;
    private final VisualLanguage language = new VisualLanguage(LANGUAGE_RELOAD_TIMEOUT_TICKS, LANGUAGE_SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A throw-away world with real interaction: creative and the vanilla reach, because every block of the story is
     * placed, clicked and opened by a right-click, and a spectator may do none of that.
     */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("requester: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "requester run"))
                .server("requester: clear the area and place the creative motor", this::placeMotor)
                .server("requester: build the hut and the step, save them as schematics and clear the sites",
                        this::saveSchematics)
                .server("requester: build the aisle, the terminal, the input and the stocked racks", this::buildAisle)
                .server("requester: build the belt from the terminal's buffer to the chest", this::buildBelt)
                .server("requester: lift the player into the air", this::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("requester: the player flies, wears Engineer's Goggles and carries what the story needs",
                        this::equipPlayer)
                .serverUntil("requester: wait until the warehouse has indexed every rack and the belt runs",
                        this::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("requester: baseline census of the scene and the player", (server, context) -> {
                    expected = census(server, context);
                    LOGGER.info(PREFIX + "requester: baseline census {}", SceneItemCensus.describe(expected));
                });
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            theFirstPrint(script);
            return;
        }
        theSecondPrint(script);
        script.client("requester: every check passed", context -> LOGGER.info(PREFIX + "requester: ALL CHECKS PASSED "
                + "in English and German (a hand-placed cannon and its requester set up with real clicks, the first "
                + "order one stack, nothing ordered again while it lay in the buffer or rode the belt, the second "
                + "exactly the rest, nothing ordered or produced while the warehouse had no glass, the glass order "
                + "followed by its request id across a save, quit and rejoin, the banner ordered with its patterns, "
                + "and every census exact)"));
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        Optional<SchematicannonRequesterBlockEntity> requester = clientRequester(context);
        if (requester.isPresent()) {
            RequesterSummary summary = requester.get().summary();
            BlockState state = requester.get().getBlockState();
            status.append(String.format(Locale.ROOT, "requester=%s cannon=%s rows=%d lit=%s refused=%s",
                    summary.status(), summary.cannon(), summary.rows().size(),
                    state.getOptionalValue(SchematicannonRequesterBlock.LIT).orElse(false),
                    state.getOptionalValue(SchematicannonRequesterBlock.REFUSED).orElse(false)));
        } else {
            status.append("requester=none");
        }
        clientCrane(context).ifPresent(crane -> {
            CranePose pose = crane.craneState().pose();
            status.append(String.format(Locale.ROOT, " crane=%s posX=%.2f posY=%.2f arm=%.2f held=%d",
                    crane.craneState().phase(), pose.x(), pose.y(), pose.arm(), crane.goggleInfo().heldCount()));
        });
        Screen open = context.minecraft().screen;
        status.append(" screen=").append(open == null ? "none" : open.getClass().getSimpleName());
        status.append(" lang=").append(context.minecraft().getLanguageManager().getSelected());
        return status + GoggleShots.describeHover(context);
    }

    // --- the first pass: the hut -------------------------------------------------------------------------------------

    private void theFirstPrint(VisualScript script) {
        worldShots(script, "scene", OVERVIEW, AT_SITE);
        placeTheCannon(script);
        loadTheCannon(script);
        placeTheRequester(script);
        linkTheRemote(script);
        insertTheRemote(script);
        showTheSkipMissingCannon(script);
        startThePrint(script);
        theFirstOrder(script);
        theStoneInTheBuffer(script);
        openTheBelt(script);
        theStoneOnTheBelt(script);
        theSecondOrder(script);
        theGlassOutOfStock(script);
        bringTheGlass(script);
        saveQuitAndRejoin(script);
        restartTheCannon(script);
        theBannerOrder(script);
        theHutIsFinished(script);
    }

    /** 1: the Schematicannon placed from its item, which leaves it skipping missing blocks as for every player. */
    private void placeTheCannon(VisualScript script) {
        selectHotbar(script, SLOT_CANNON, "the Schematicannon", AllBlocks.SCHEMATICANNON::isIn);
        rightClick(script, CLICK_CANNON_SPOT);
        script.serverUntil("requester: the Schematicannon stands where the player placed it",
                        (server, context) -> AllBlocks.SCHEMATICANNON.has(
                                server.overworld().getBlockState(context.origin().offset(CANNON))),
                        CLICK_TIMEOUT_TICKS)
                .server("requester: CHECK the hand-placed cannon skips missing blocks", (server, context) -> {
                    SchematicannonBlockEntity cannon = cannon(server.overworld(), context);
                    if (!cannon.skipMissing)
                        throw new VisualTestException("a Schematicannon placed from its item should skip missing "
                                + "blocks (Create's SchematicannonOptions default), but it does not");
                    assertCensus(server, context, "the cannon was placed");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the cannon placed by a click skips missing blocks, "
                            + "state {} '{}'", cannon.state, cannon.statusMsg);
                });
    }

    /** The hut's schematic and one gunpowder go into the cannon's screen with clicks; the gunpowder is burnt at once. */
    private void loadTheCannon(VisualScript script) {
        openCannonScreen(script);
        moveInMenu(script, "the hut's schematic", "the cannon's blueprint slot", hotbar(SLOT_SCHEMATIC),
                menuSlot(CANNON_SLOT_SCHEMATIC));
        moveInMenu(script, "the gunpowder", "the cannon's gunpowder slot", hotbar(SLOT_GUNPOWDER),
                menuSlot(CANNON_SLOT_GUNPOWDER));
        script.serverUntil("requester: the cannon read the hut and burnt the gunpowder as fuel",
                this::hutLoaded, CANNON_TIMEOUT_TICKS);
        closeScreen(script, "the cannon's screen");
    }

    private boolean hutLoaded(MinecraftServer server, VisualContext context) {
        SchematicannonBlockEntity cannon = cannon(server.overworld(), context);
        if (cannon.printer.isErrored())
            throw new VisualTestException("the Schematicannon could not read the hut: " + cannon.statusMsg);
        if (!cannon.printer.isLoaded() || !cannon.inventory.getStackInSlot(CANNON_SLOT_GUNPOWDER).isEmpty()
                || cannon.remainingFuel != cannon.getShotsPerGunpowder())
            return false;
        requireAmount(cannon, Items.STONE, STONE_BLOCKS);
        requireAmount(cannon, Items.GLASS, GLASS_BLOCKS);
        requireAmount(cannon, Items.WHITE_BANNER, 1);
        // The one item the cannon uses up of its own: a gunpowder buys getShotsPerGunpowder() shots, far more than both
        // prints of this run take.
        expected = SceneItemCensus.plus(expected, GUNPOWDER, -1);
        assertCensus(server, context, "the cannon burnt one gunpowder as fuel");
        LOGGER.info(PREFIX + "requester: CHECK PASS the cannon read the hut ({} blocks to place: {}) and burnt one "
                + "gunpowder for {} shots, state {} '{}'", cannon.blocksToPlace, cannon.checklist.required,
                cannon.remainingFuel, cannon.state, cannon.statusMsg);
        return true;
    }

    private static void requireAmount(SchematicannonBlockEntity cannon, Item item, int amount) {
        int required = cannon.checklist.getRequiredAmount(item);
        if (required != amount)
            throw new VisualTestException("the cannon's material list asks for " + required + " " + item
                    + ", expected " + amount + ": " + cannon.checklist.required);
    }

    /** The requester placed by a click on the ground beside the cannon: it finds the cannon and faces it. */
    private void placeTheRequester(VisualScript script) {
        selectHotbar(script, SLOT_REQUESTER, "the Schematicannon Requester",
                WareworksBlocks.SCHEMATICANNON_REQUESTER::isIn);
        rightClick(script, CLICK_REQUESTER_SPOT);
        script.serverUntil("requester: the requester stands where the player placed it",
                        (server, context) -> requesterOrNull(server.overworld(), context) != null, CLICK_TIMEOUT_TICKS)
                .server("requester: CHECK the requester faces the cannon and is empty", (server, context) -> {
                    SchematicannonRequesterBlockEntity requester = requester(server.overworld(), context);
                    if (!requester.cannonPos().equals(context.origin().offset(CANNON)))
                        throw new VisualTestException("the requester faces " + requester.facing()
                                + ", not the cannon at " + context.origin().offset(CANNON));
                    if (requester.hasRemote() || requester.owner().isPresent())
                        throw new VisualTestException("a new requester holds a remote or an owner");
                    assertCensus(server, context, "the requester was placed");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the requester placed on the ground beside the cannon "
                            + "faces {}, towards the cannon", requester.facing());
                });
    }

    /**
     * Standing on the ground beside the terminal, with the sneak key held and a real click on the use key, the
     * player links the Warehouse Remote to the terminal (issue #44).
     */
    private void linkTheRemote(VisualScript script) {
        selectHotbar(script, SLOT_REMOTE, "the Warehouse Remote", WareworksItems.WAREHOUSE_REMOTE::isIn);
        // Standing, not flying: a flying player who holds the sneak key sinks instead of crouching.
        script.server("requester: the player stops flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }).camera("link", context -> linkView());
        ArmVisualScenario.untilOrFail(script, "requester: the player stands on the ground beside the terminal",
                context -> context.minecraft().player.onGround() && !context.minecraft().player.getAbilities().flying,
                SNEAK_TIMEOUT_TICKS, context -> "the player is at " + context.minecraft().player.position()
                        + ", flying " + context.minecraft().player.getAbilities().flying);
        sneak(script, true);
        ArmVisualScenario.untilOrFail(script, "requester: the crosshair is on the terminal",
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(context.origin().offset(TERMINAL)),
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context)
                        + " instead of the terminal");
        useKey(script, "the terminal while sneaking");
        ArmVisualScenario.untilOrFail(script, "requester: the client's remote is linked to the terminal", context -> {
            RemoteLink link = WarehouseRemoteItem.link(context.minecraft().player.getMainHandItem());
            return link != null && link.terminal().pos().equals(context.origin().offset(TERMINAL));
        }, MESSAGE_TIMEOUT_TICKS, context -> "the remote in hand is " + context.minecraft().player.getMainHandItem());
        actionBar(script, "the link", context -> {
            BlockPos pos = context.origin().offset(TERMINAL);
            return WareworksLang.translateDirect(WareworksLang.REMOTE_LINKED, pos.getX(), pos.getY(), pos.getZ());
        });
        script.server("requester: CHECK the server's link and the census", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context);
            RemoteLink wanted = new RemoteLink(GlobalPos.of(Level.OVERWORLD, terminal.getBlockPos()),
                    terminal.terminalId());
            RemoteLink link = WarehouseRemoteItem.link(player.getMainHandItem());
            if (!wanted.equals(link))
                throw new VisualTestException("the remote is linked to " + link + ", not to " + wanted);
            // The link is part of the remote's item key: the census swaps the old key for the new one, nothing else.
            Map<ItemKey, Long> next = SceneItemCensus.plus(expected, Objects.requireNonNull(remoteKey, "remoteKey"), -1);
            remoteKey = ItemKey.of(player.getMainHandItem());
            expected = SceneItemCensus.plus(next, remoteKey, 1);
            assertCensus(server, context, "the remote was linked");
            LOGGER.info(PREFIX + "requester: CHECK PASS a sneaking right-click linked the remote to the terminal at {}",
                    terminal.getBlockPos().toShortString());
        });
        sneak(script, false);
        fly(script);
    }

    /**
     * A right-click on the requester with the linked remote: it goes into the cradle, the action bar names the terminal
     * and the chat says, once, to turn the cannon's "Skip missing Blocks" off.
     */
    private void insertTheRemote(VisualScript script) {
        script.client("requester: clear the chat before the remote goes in",
                context -> context.minecraft().gui.getChat().clearMessages(true));
        rightClick(script, CLICK_REQUESTER);
        actionBar(script, "the insert", context -> {
            BlockPos pos = context.origin().offset(TERMINAL);
            return WareworksLang.translateDirect(WareworksLang.REQUESTER_LINKED, pos.getX(), pos.getY(), pos.getZ());
        });
        ArmVisualScenario.untilOrFail(script, "requester: the chat says to turn Skip missing Blocks off",
                context -> ChainVisualScenario.chatLines(context).contains(skipMissingWarning()), MESSAGE_TIMEOUT_TICKS,
                context -> "the chat reads " + ChainVisualScenario.chatLines(context) + ", not '" + skipMissingWarning()
                        + "'");
        script.server("requester: CHECK the remote is in the cradle and the player owns the orders",
                (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    SchematicannonRequesterBlockEntity requester = requester(server.overworld(), context);
                    ItemKey linked = Objects.requireNonNull(remoteKey, "remoteKey");
                    if (!requester.hasRemote() || !ItemKey.of(requester.remote()).equals(linked))
                        throw new VisualTestException("the cradle holds " + requester.remote() + ", not the remote");
                    if (!requester.owner().equals(Optional.of(player.getUUID())))
                        throw new VisualTestException("the orders belong to " + requester.owner() + ", not to "
                                + player.getGameProfile().getName());
                    if (!linked.equals(ItemKey.of(player.getMainHandItem())))
                        throw new VisualTestException("in creative mode the hand keeps its remote, but it holds "
                                + player.getMainHandItem());
                    // Creative mode puts a copy in and leaves the hand as it was (SchematicannonRequesterBlockEntity
                    // #putIn), exactly as it places blocks: the cradle's remote is the one item it adds.
                    expected = SceneItemCensus.plus(expected, linked, 1);
                    assertCensus(server, context, "the remote went into the cradle");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the remote is in the cradle, owned by {}, and the chat "
                            + "warned about Skip missing Blocks", player.getGameProfile().getName());
                })
                .shotWithGui("requester-inserted");
    }

    /** 2: the red lamp of a cannon that skips missing blocks, and the goggles that say what to do. */
    private void showTheSkipMissingCannon(VisualScript script) {
        script.serverUntil("requester: the requester shows the cannon's Skip missing Blocks with its red lamp",
                        (server, context) -> showing(server, context, RequesterStatus.SKIP_MISSING),
                        SYNC_TIMEOUT_TICKS)
                .server("requester: CHECK nothing was ordered for a cannon that skips missing blocks",
                        (server, context) -> {
                            assertNoRequests(server, context, "while the cannon skips missing blocks");
                            LOGGER.info(PREFIX + "requester: CHECK PASS the lamp is red and nothing is ordered "
                                    + "while the cannon skips missing blocks");
                        });
        worldShots(script, "requester-skip-missing", REQUESTER_CLOSE, OVERVIEW);
        goggles(script, "skip-missing", context -> synced(context, RequesterStatus.SKIP_MISSING, 0),
                context -> checkGoggles(context, "skip-missing", List.of(text(
                        WareworksLang.requesterStatusKey(RequesterStatus.SKIP_MISSING),
                        Component.translatable(RequesterLines.SKIP_MISSING_OPTION)))), true);
    }

    /** 3: "Skip missing Blocks" clicked off and Play pressed, both in the cannon's own screen. */
    private void startThePrint(VisualScript script) {
        openCannonScreen(script);
        script.client("requester: click the cannon's placement settings",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.cannonSettingsButton(cannonScreen(context))))
                .waitTicks(SETTLE_TICKS)
                .client("requester: click Skip missing Blocks",
                        context -> ScreenInput.click(context.minecraft(),
                                ScreenInput.cannonSkipMissingButton(cannonScreen(context))))
                .serverUntil("requester: the cannon no longer skips missing blocks",
                        (server, context) -> !cannon(server.overworld(), context).skipMissing, CLICK_TIMEOUT_TICKS)
                .until("requester: the cannon's screen shows the option off",
                        context -> !cannonScreen(context).getMenu().contentHolder.skipMissing, SYNC_TIMEOUT_TICKS)
                .client("requester: rest the mouse on the option, so its name shows",
                        context -> ScreenInput.hover(context.minecraft(),
                                ScreenInput.cannonSkipMissingButton(cannonScreen(context))))
                .waitTicks(SETTLE_TICKS)
                .shot("cannon-skip-missing-off")
                .serverUntil("requester: the requester sees a cannon that waits to be started",
                        (server, context) -> showing(server, context, RequesterStatus.NOT_PRINTING),
                        SYNC_TIMEOUT_TICKS)
                .server("requester: CHECK the lamp went dark", (server, context) -> LOGGER.info(PREFIX
                        + "requester: CHECK PASS with Skip missing Blocks off the lamp is dark: the cannon waits for "
                        + "Play"))
                .client("requester: click Play", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.cannonPlayButton(cannonScreen(context))))
                .serverUntil("requester: the cannon runs", (server, context) -> !"ready".equals(
                        cannon(server.overworld(), context).statusMsg), CLICK_TIMEOUT_TICKS);
        closeScreen(script, "the cannon's screen");
    }

    /**
     * The first order, frozen while the crane carries it: one stack of stone, the requester's alone. The goggles are
     * looked at before the moment, so the summary they show is already on the client when the ticks stop.
     */
    private void theFirstOrder(VisualScript script) {
        fly(script);
        script.camera(GOGGLE_VIEW);
        ArmVisualScenario.untilOrFail(script, "requester: the goggles show the first order",
                context -> synced(context, RequesterStatus.ORDERED, FIRST_STONE_ORDER), MOMENT_TIMEOUT_TICKS,
                context -> "the requester shows " + clientRequester(context).map(r -> r.summary().toString())
                        .orElse("nothing"));
        script.serverUntil("requester: freeze the moment the crane carries the stone to the terminal",
                        (server, context) -> freezeWhileCarrying(server, context, FIRST_STONE_ORDER),
                        MOMENT_TIMEOUT_TICKS)
                .until("requester: the client sees the frozen moment", RequesterVisualScenario::clientFrozen,
                        SYNC_TIMEOUT_TICKS)
                .server("requester: CHECK the first order is one stack of stone, ahead of nobody",
                        (server, context) -> checkCarriedOrder(server, context, FIRST_STONE_ORDER));
        goggles(script, "ordered", context -> synced(context, RequesterStatus.ORDERED, FIRST_STONE_ORDER),
                context -> checkGoggles(context, "ordered", List.of(
                        text(WareworksLang.GOGGLES_REQUESTER_CANNON_MISSING, Items.STONE.getDescription(),
                                WareworksLang.number(1)),
                        orderedRow(context))), true);
        worldShots(script, "requester-ordered", OVERVIEW, ALONG_AISLE);
        script.freeze(false);
    }

    /**
     * 4: the stone lies in the buffer, because the lever pauses the funnel. Three polls of the requester order nothing
     * more: the stone in the buffer is the order, not a reason for another.
     */
    private void theStoneInTheBuffer(VisualScript script) {
        script.serverUntil("requester: the stone lies in the terminal's buffer",
                        (server, context) -> showing(server, context, RequesterStatus.IN_BUFFER)
                                && terminal(server.overworld(), context).bufferedItems().count(STONE)
                                == FIRST_STONE_ORDER, ORDER_TIMEOUT_TICKS)
                .server("requester: start watching for a second order", (server, context) -> {
                    watchSince = server.overworld().getGameTime();
                    assertCensus(server, context, "the stone was delivered into the buffer");
                })
                .serverUntil("requester: CHECK no duplicate order while the stone lies in the buffer",
                        (server, context) -> {
                            ServerLevel level = server.overworld();
                            assertSingleOrder(server, context, RequesterOrder.Phase.IN_BUFFER,
                                    "while the stone lies in the buffer");
                            if (CannonView.read(level, context.origin().offset(CANNON)).phase()
                                    != CannonView.Phase.MISSING)
                                throw new VisualTestException("the cannon stopped missing stone while the stone lay "
                                        + "in the buffer");
                            long watched = level.getGameTime() - watchSince;
                            if (watched < (long) WATCHED_POLLS * WareworksConfig.requesterPollIntervalTicks())
                                return false;
                            LOGGER.info(PREFIX + "requester: CHECK PASS no duplicate order while the stone lies in the "
                                    + "buffer: {} ticks, the cannon still missing stone, one order, no request",
                                    watched);
                            return true;
                        }, ORDER_TIMEOUT_TICKS);
        goggles(script, "in-buffer", context -> synced(context, RequesterStatus.IN_BUFFER, FIRST_STONE_ORDER),
                context -> checkGoggles(context, "in-buffer", List.of(text(
                        WareworksLang.requesterStatusKey(RequesterStatus.IN_BUFFER), Items.STONE.getDescription()))),
                false);
        worldShots(script, "requester-in-buffer", REQUESTER_CLOSE, ALONG_BELT);
    }

    /** The player switches the lever off with a real click, and the funnel takes the stone onto the belt. */
    private void openTheBelt(VisualScript script) {
        takeAnEmptyHand(script);
        rightClick(script, CLICK_LEVER);
        script.serverUntil("requester: the lever is off and the funnel runs", (server, context) -> {
            ServerLevel level = server.overworld();
            return !level.getBlockState(context.origin().offset(LEVER)).getValue(LeverBlock.POWERED)
                    && !level.getBlockState(context.origin().offset(OUT_FUNNEL)).getValue(AbstractFunnelBlock.POWERED);
        }, CLICK_TIMEOUT_TICKS).server("requester: CHECK the lever opened the belt", (server, context) -> LOGGER.info(
                PREFIX + "requester: CHECK PASS a click switched the lever off and the funnel behind the terminal "
                        + "runs"));
    }

    /**
     * The stone rides the belt while the requester waits out its grace. The funnel takes one item at a time, and the
     * belt takes the next only once the last has moved on a block, so the stack leaves the buffer over most of a
     * minute — the order stays "in the buffer" all that while — and its last items are still on the belt when the
     * grace begins. Frozen there: the order neither running nor in the buffer, and still only one order.
     */
    private void theStoneOnTheBelt(VisualScript script) {
        fly(script);
        script.camera(GOGGLE_VIEW);
        ArmVisualScenario.untilOrFail(script, "requester: the goggles show the grace",
                context -> synced(context, RequesterStatus.GRACE, FIRST_STONE_ORDER), ORDER_TIMEOUT_TICKS,
                context -> "the requester shows " + clientRequester(context).map(r -> r.summary().toString())
                        .orElse("nothing"));
        script.serverUntil("requester: freeze the moment the stone rides the belt", this::freezeOnTheBelt,
                        MOMENT_TIMEOUT_TICKS)
                .until("requester: the client sees the frozen moment", RequesterVisualScenario::clientFrozen,
                        SYNC_TIMEOUT_TICKS)
                .server("requester: CHECK no duplicate order during the grace", (server, context) -> {
                    assertSingleOrder(server, context, RequesterOrder.Phase.GRACE, "during the grace");
                    if (terminal(server.overworld(), context).hasBufferedItems())
                        throw new VisualTestException("the buffer still holds "
                                + terminal(server.overworld(), context).bufferedItems());
                    assertCensus(server, context, "the stone rides the belt");
                    LOGGER.info(PREFIX + "requester: CHECK PASS no duplicate order during the grace: the buffer is "
                            + "empty, {} stone ride the belt, one order waits out its grace, the cannon is {}",
                            beltStone(server.overworld(), context).stream().mapToInt(riding -> riding.stack.getCount())
                                    .sum(), CannonView.read(server.overworld(), context.origin().offset(CANNON)).phase());
                });
        goggles(script, "grace", context -> synced(context, RequesterStatus.GRACE, FIRST_STONE_ORDER),
                context -> checkGoggles(context, "grace", List.of(graceRow(context))), true);
        worldShots(script, "requester-belt-grace", ALONG_BELT);
        script.freeze(false);
    }

    /** 5: the second order is exactly what the hut still needs of stone. */
    private void theSecondOrder(VisualScript script) {
        script.serverUntil("requester: the cannon printed the stack and the requester ordered the rest",
                        (server, context) -> runningOrder(server, context, STONE).isPresent(), ORDER_TIMEOUT_TICKS)
                .server("requester: CHECK the second order is the rest of the stone", (server, context) -> {
                    RequesterOrder<ItemKey> order = runningOrder(server, context, STONE).orElseThrow();
                    RetrievalRequest<ItemKey, BlockPos> request = request(server, context, order.requestId());
                    if (order.share() != SECOND_STONE_ORDER || request.requested() != SECOND_STONE_ORDER
                            || order.ahead() != 0)
                        throw new VisualTestException("the second order is " + order + " for " + request
                                + ", expected " + SECOND_STONE_ORDER + " stone ahead of nobody");
                    int counted = requester(server.overworld(), context).orderedSinceList(Items.STONE);
                    if (counted != STONE_BLOCKS)
                        throw new VisualTestException("the requester counts " + counted + " stone ordered against "
                                + "the material list, expected " + STONE_BLOCKS);
                    assertCensus(server, context, "the second stone order was placed");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the second order is {} stone, the rest of {}",
                            order.share(), STONE_BLOCKS);
                })
                .serverUntil("requester: the crane delivered the second order", (server, context) -> {
                    List<RequesterOrder<ItemKey>> orders = requester(server.overworld(), context).orders();
                    return orders.size() == 1 && !orders.getFirst().isRunning();
                }, ORDER_TIMEOUT_TICKS)
                .server("requester: start watching the second order's way to the cannon", (server, context) -> {
                    graceSamples = 0;
                    watchSince = server.overworld().getGameTime();
                })
                .serverUntil("requester: CHECK no duplicate order during the grace while the cannon misses stone",
                        this::watchTheGrace, ORDER_TIMEOUT_TICKS);
    }

    /**
     * Polled while the second order's stone leaves the buffer one item at a time and rides the belt: the cannon waits
     * for stone all the while, so this is where a requester without its grace would order the stone again. Nothing may
     * be ordered until the order is done, and the cannon has to be seen missing stone while the order is in its grace.
     */
    private boolean watchTheGrace(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        assertNoRequests(server, context, "while the second order's stone is on its way");
        List<RequesterOrder<ItemKey>> orders = requester(level, context).orders();
        if (orders.size() > 1)
            throw new VisualTestException("a second order was placed while the stone was on its way: " + orders);
        if (orders.isEmpty()) {
            if (graceSamples == 0)
                throw new VisualTestException("the cannon was never seen missing stone during the grace, so the run "
                        + "proved nothing about it");
            assertCensus(server, context, "the second order is done");
            LOGGER.info(PREFIX + "requester: CHECK PASS no duplicate order during the grace while the cannon misses "
                    + "stone: seen {} times in {} ticks, nothing ordered", graceSamples,
                    level.getGameTime() - watchSince);
            return true;
        }
        if (orders.getFirst().phase() == RequesterOrder.Phase.GRACE
                && CannonView.read(level, context.origin().offset(CANNON)).phase() == CannonView.Phase.MISSING)
            graceSamples++;
        return false;
    }

    /** 6: the cannon waits for glass the warehouse does not have; nothing is ordered and nothing is produced. */
    private void theGlassOutOfStock(VisualScript script) {
        script.serverUntil("requester: the cannon waits for glass and the requester says it is out of stock",
                        (server, context) -> showing(server, context, RequesterStatus.OUT_OF_STOCK),
                        PRINT_TIMEOUT_TICKS)
                .server("requester: CHECK nothing was ordered or produced for the missing glass",
                        (server, context) -> {
                            ServerLevel level = server.overworld();
                            CannonView cannon = CannonView.read(level, context.origin().offset(CANNON));
                            if (cannon.phase() != CannonView.Phase.MISSING || !GLASS.matches(cannon.missing()))
                                throw new VisualTestException("the cannon is " + cannon.phase() + ", missing "
                                        + cannon.missing() + ", not glass");
                            assertNoRequests(server, context, "while the warehouse has no glass");
                            if (!controller(level, context).openProductionOrders().isEmpty())
                                throw new VisualTestException("the requester started production: "
                                        + controller(level, context).openProductionOrders());
                            if (!requester(level, context).orders().isEmpty())
                                throw new VisualTestException("orders are open: " + requester(level, context).orders());
                            assertCensus(server, context, "the cannon waits for glass");
                            LOGGER.info(PREFIX + "requester: CHECK PASS the warehouse has no glass: red lamp, no "
                                    + "order, no production");
                        });
        worldShots(script, "requester-out-of-stock", REQUESTER_CLOSE);
        goggles(script, "out-of-stock", context -> synced(context, RequesterStatus.OUT_OF_STOCK, 0),
                context -> checkGoggles(context, "out-of-stock", List.of(
                        text(WareworksLang.GOGGLES_REQUESTER_CANNON_MISSING, Items.GLASS.getDescription(),
                                WareworksLang.number(1)),
                        text(WareworksLang.requesterStatusKey(RequesterStatus.OUT_OF_STOCK),
                                Items.GLASS.getDescription()))), true);
    }

    /** Glass fed into the warehouse input, the way a funnel or a player would: the order follows, for the hut's four. */
    private void bringTheGlass(VisualScript script) {
        script.server("requester: feed " + GLASS_FED + " glass into the warehouse input", (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos input = layout(context.origin()).rackPos(INPUT_RACK);
                    ItemStack rest = ItemHandlerHelper.insertItem(handler(level, input), GLASS.toStack(GLASS_FED),
                            false);
                    if (!rest.isEmpty())
                        throw new VisualTestException("the warehouse input refused " + rest);
                    expected = SceneItemCensus.plus(expected, GLASS, GLASS_FED);
                    assertCensus(server, context, "glass was fed into the warehouse input");
                })
                .serverUntil("requester: the crane stored the glass and the requester ordered it",
                        (server, context) -> runningOrder(server, context, GLASS).isPresent(), ORDER_TIMEOUT_TICKS)
                .server("requester: CHECK the glass order is what the hut needs", (server, context) -> {
                    RequesterOrder<ItemKey> order = runningOrder(server, context, GLASS).orElseThrow();
                    RetrievalRequest<ItemKey, BlockPos> request = request(server, context, order.requestId());
                    if (order.share() != GLASS_BLOCKS || request.requested() != GLASS_BLOCKS)
                        throw new VisualTestException("the glass order is " + order + " for " + request
                                + ", expected " + GLASS_BLOCKS);
                    glassRequest = order.requestId();
                    assertCensus(server, context, "the glass order was placed");
                    LOGGER.info(PREFIX + "requester: CHECK PASS once the glass is in stock the requester orders {} "
                            + "of the {} in stock, request {}", order.share(), GLASS_FED, order.requestId());
                });
    }

    /**
     * 7: a real save, quit and rejoin while the glass order runs. The requester comes back with the same order and
     * request id and follows it to the end; nothing is ordered twice.
     */
    private void saveQuitAndRejoin(VisualScript script) {
        script.server("requester: census before saving", (server, context) -> {
                    RequesterOrder<ItemKey> order = runningOrder(server, context, GLASS).orElseThrow(
                            () -> new VisualTestException("the glass order is no longer running before the save"));
                    RetrievalRequest<ItemKey, BlockPos> request = request(server, context, order.requestId());
                    assertCensus(server, context, "before save and quit");
                    LOGGER.info(PREFIX + "requester: saving with the glass order running: request {}, {} of {} "
                            + "delivered", request.id(), request.delivered(), request.requested());
                })
                .server("requester: save the world", (server, context) -> server.saveEverything(true, true, true));
        VisualWorld.reload(script, worldProfile());
        fly(script);
        script.serverUntil("requester: the scene is loaded and the warehouse ready after the rejoin",
                        (server, context) -> SceneItemCensus.isFullyLoaded(server.overworld(), censusBox)
                                && controller(server.overworld(), context).status() == ControllerStatus.READY
                                && requesterOrNull(server.overworld(), context) != null, SCENE_READY_TIMEOUT_TICKS)
                .server("requester: CHECK the glass order came back with its request id", (server, context) -> {
                    UUID id = Objects.requireNonNull(glassRequest, "glassRequest");
                    RequesterOrder<ItemKey> order = requester(server.overworld(), context).orders().stream()
                            .filter(candidate -> candidate.requestId().equals(id)).findFirst()
                            .orElseThrow(() -> new VisualTestException("after the rejoin the requester lost the glass "
                                    + "order " + id + ": " + requester(server.overworld(), context).orders()));
                    if (!order.key().equals(GLASS) || order.share() != GLASS_BLOCKS)
                        throw new VisualTestException("after the rejoin the glass order reads " + order);
                    // The crane resumes its trip at once, so by the requester's first poll the request may already be
                    // delivered and closed: the order's own phase follows on that poll. Where the glass is the census
                    // answers, and the steps after this one wait for all of it at the cannon.
                    ServerLevel level = server.overworld();
                    assertCensus(server, context, "after save, quit and rejoin");
                    Optional<RetrievalRequest<ItemKey, BlockPos>> open = controller(level, context)
                            .requestsFor(context.origin().offset(TERMINAL)).stream()
                            .filter(request -> request.id().equals(id)).findFirst();
                    if (open.isPresent() && (!open.get().key().equals(GLASS)
                            || open.get().requested() != GLASS_BLOCKS))
                        throw new VisualTestException("after the rejoin request " + id + " reads " + open.get());
                    SchematicannonBlockEntity cannon = cannon(level, context);
                    LOGGER.info(PREFIX + "requester: CHECK PASS after the rejoin the requester follows the same glass "
                            + "order {} ({}); the request is {}, {} glass past the crane; Create's cannon is {} '{}', "
                            + "missing {}", id, order.phase(), open.map(request -> "open, " + request.delivered()
                                    + " of " + request.requested() + " delivered").orElse("delivered and closed"),
                            glassOnItsWay(level, context), cannon.state, cannon.statusMsg, cannon.missingItem);
                });
        worldShots(script, "requester-rejoined", OVERVIEW);
        script.serverUntil("requester: the glass order is done and the glass has reached the cannon",
                        (server, context) -> requester(server.overworld(), context).orders().isEmpty()
                                && glassAtTheCannon(server.overworld(), context) == GLASS_BLOCKS, ORDER_TIMEOUT_TICKS)
                .server("requester: CHECK the glass was ordered once", (server, context) -> {
                    ServerLevel level = server.overworld();
                    long left = controller(level, context).countOf(GLASS);
                    if (left != GLASS_FED - GLASS_BLOCKS)
                        throw new VisualTestException("the warehouse holds " + left + " glass, expected "
                                + (GLASS_FED - GLASS_BLOCKS) + ": the glass was ordered more than once");
                    assertCensus(server, context, "the glass reached the chest");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the glass order ran across the rejoin and was placed "
                            + "once: {} glass at the cannon, {} still in the warehouse", GLASS_BLOCKS, left);
                });
    }

    /**
     * Create's cannon reloads its schematic after a rejoin but keeps the item it last missed, so it searches on without
     * printing until the player stops and starts it again — which the player does, in its screen.
     */
    private void restartTheCannon(VisualScript script) {
        openCannonScreen(script);
        script.client("requester: click Stop", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.cannonStopButton(cannonScreen(context))))
                .serverUntil("requester: the stopped cannon has read the hut again", (server, context) -> {
                    SchematicannonBlockEntity cannon = cannon(server.overworld(), context);
                    return cannon.printer.isLoaded() && cannon.state == SchematicannonBlockEntity.State.PAUSED
                            && "ready".equals(cannon.statusMsg) && cannon.missingItem == null;
                }, CANNON_TIMEOUT_TICKS)
                .until("requester: the screen offers Play again", context -> cannonScreen(context).getMenu()
                        .contentHolder.state == SchematicannonBlockEntity.State.PAUSED, SYNC_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("requester: click Play", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.cannonPlayButton(cannonScreen(context))))
                .serverUntil("requester: the cannon runs again", (server, context) -> !"ready".equals(
                        cannon(server.overworld(), context).statusMsg), CLICK_TIMEOUT_TICKS);
        closeScreen(script, "the cannon's screen");
    }

    /** 8: the banner, a strict requirement, is ordered with exactly its patterns. */
    private void theBannerOrder(VisualScript script) {
        script.serverUntil("requester: the cannon printed the glass and the requester ordered the banner",
                        (server, context) -> runningOrder(server, context,
                                Objects.requireNonNull(bannerKey, "bannerKey")).isPresent(), ORDER_TIMEOUT_TICKS)
                .server("requester: CHECK the banner is ordered with exactly its patterns", (server, context) -> {
                    ItemKey banner = Objects.requireNonNull(bannerKey, "bannerKey");
                    RequesterOrder<ItemKey> order = runningOrder(server, context, banner).orElseThrow();
                    RetrievalRequest<ItemKey, BlockPos> request = request(server, context, order.requestId());
                    if (!order.strict() || order.share() != 1 || !request.key().equals(banner))
                        throw new VisualTestException("the banner order is " + order + " for " + request
                                + ", expected the strict " + banner);
                    if (ItemKey.of(Items.WHITE_BANNER).equals(banner))
                        throw new VisualTestException("the hut's banner carries no patterns");
                    assertCensus(server, context, "the banner order was placed");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the banner is ordered strictly, with its patterns: {}",
                            banner);
                });
    }

    /** The hut is finished: every block printed, the stock fell by exactly the hut, nothing is left on the way. */
    private void theHutIsFinished(VisualScript script) {
        script.serverUntil("requester: the hut is finished and nothing is left on the way", (server, context) -> {
                    ServerLevel level = server.overworld();
                    SchematicannonBlockEntity cannon = cannon(level, context);
                    return "finished".equals(cannon.statusMsg) && cannon.flyingBlocks.isEmpty()
                            && requester(level, context).orders().isEmpty();
                }, PRINT_TIMEOUT_TICKS)
                .server("requester: CHECK the hut and the stock", (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos origin = context.origin();
                    assertNothingOnTheWay(level, context, "after the hut");
                    int stone = 0;
                    for (int x = 0; x < SITE_LENGTH; x++)
                        for (int z = 0; z < SITE_WIDTH; z++)
                            if (level.getBlockState(origin.offset(SITE).offset(x, 0, z)).is(Blocks.STONE))
                                stone++;
                    int glass = 0;
                    for (BlockPos spot : GLASS_SPOTS)
                        if (level.getBlockState(origin.offset(spot)).is(Blocks.GLASS))
                            glass++;
                    if (stone != STONE_BLOCKS || glass != GLASS_BLOCKS
                            || !(level.getBlockEntity(origin.offset(BANNER_SPOT)) instanceof BannerBlockEntity banner)
                            || !ItemKey.of(banner.getItem()).equals(bannerKey))
                        throw new VisualTestException("the printed hut has " + stone + " stone, " + glass
                                + " glass and " + level.getBlockState(origin.offset(BANNER_SPOT)) + " at the banner");
                    WarehouseControllerBlockEntity controller = controller(level, context);
                    if (controller.countOf(STONE) != STONE_IN_STOCK - STONE_BLOCKS
                            || controller.countOf(GLASS) != GLASS_FED - GLASS_BLOCKS
                            || controller.countOf(Objects.requireNonNull(bannerKey, "bannerKey")) != 0)
                        throw new VisualTestException("the stock is " + controller.countOf(STONE) + " stone, "
                                + controller.countOf(GLASS) + " glass and " + controller.countOf(bannerKey)
                                + " banners after the hut");
                    // Create turns a printed schematic into an empty one (SchematicannonBlockEntity#finishedPrinting).
                    expected = SceneItemCensus.plus(SceneItemCensus.plus(expected,
                            ItemKey.of(hutSchematic), -1), emptySchematic(), 1);
                    assertCensus(server, context, "the hut is finished");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the hut is printed ({} stone, {} glass, the patterned "
                            + "banner) and the warehouse gave exactly that", stone, glass);
                });
        worldShots(script, "requester-finished", OVERVIEW, AT_SITE);
        goggles(script, "finished", context -> synced(context, RequesterStatus.NOT_PRINTING, 0),
                context -> checkGoggles(context, "finished",
                        List.of(text(WareworksLang.requesterStatusKey(RequesterStatus.NOT_PRINTING)))), false);
    }

    // --- the second pass: the step, and the moment of an order once more without Flywheel ---------------------------

    private void theSecondPrint(VisualScript script) {
        worldShots(script, "scene", OVERVIEW);
        openCannonScreen(script);
        moveInMenu(script, "the step's schematic", "the cannon's blueprint slot", hotbar(SLOT_STEP_SCHEMATIC),
                menuSlot(CANNON_SLOT_SCHEMATIC));
        script.serverUntil("requester: the cannon read the step", (server, context) -> {
                    SchematicannonBlockEntity cannon = cannon(server.overworld(), context);
                    return cannon.printer.isLoaded() && "ready".equals(cannon.statusMsg);
                }, CANNON_TIMEOUT_TICKS)
                .server("requester: CHECK the step's material list", (server, context) -> {
                    requireAmount(cannon(server.overworld(), context), Items.STONE, STEP_BLOCKS);
                    assertCensus(server, context, "the step's schematic is in the cannon");
                })
                .client("requester: click Play", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.cannonPlayButton(cannonScreen(context))))
                .serverUntil("requester: the cannon runs", (server, context) -> !"ready".equals(
                        cannon(server.overworld(), context).statusMsg), CLICK_TIMEOUT_TICKS);
        closeScreen(script, "the cannon's screen");
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        script.camera(OVERVIEW)
                .serverUntil("requester: freeze the moment the crane carries the step's stone",
                        (server, context) -> freezeWhileCarrying(server, context, STEP_BLOCKS), ORDER_TIMEOUT_TICKS)
                .until("requester: the client sees the frozen moment", RequesterVisualScenario::clientFrozen,
                        SYNC_TIMEOUT_TICKS)
                .server("requester: CHECK the step's order is its three stone",
                        (server, context) -> checkCarriedOrder(server, context, STEP_BLOCKS))
                .shotFrom(OVERVIEW, "requester-ordered")
                .shotFrom(ALONG_AISLE, "requester-ordered")
                .freeze(false);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
        script.serverUntil("requester: the step is finished", (server, context) -> {
                    ServerLevel level = server.overworld();
                    SchematicannonBlockEntity cannon = cannon(level, context);
                    return "finished".equals(cannon.statusMsg) && cannon.flyingBlocks.isEmpty()
                            && requester(level, context).orders().isEmpty();
                }, PRINT_TIMEOUT_TICKS)
                .server("requester: CHECK the step and the stock", (server, context) -> {
                    ServerLevel level = server.overworld();
                    assertNothingOnTheWay(level, context, "after the step");
                    for (int x = 0; x < STEP_BLOCKS; x++)
                        if (!level.getBlockState(context.origin().offset(STEP).offset(x, 0, 0)).is(Blocks.STONE))
                            throw new VisualTestException("the step has no stone at " + x);
                    long stone = controller(level, context).countOf(STONE);
                    if (stone != STONE_IN_STOCK - STONE_BLOCKS - STEP_BLOCKS)
                        throw new VisualTestException("the warehouse holds " + stone + " stone after the step");
                    expected = SceneItemCensus.plus(SceneItemCensus.plus(expected,
                            ItemKey.of(stepSchematic), -1), emptySchematic(), 1);
                    assertCensus(server, context, "the step is finished");
                    LOGGER.info(PREFIX + "requester: CHECK PASS the step is printed and the warehouse gave {} stone",
                            STEP_BLOCKS);
                });
    }

    // --- frozen moments (server thread) ------------------------------------------------------------------------------

    /**
     * Freezes the ticks the moment the crane carries the requester's order of {@code share} stone towards the
     * terminal, well inside the aisle; fails once the order has left its running phase without that moment.
     */
    private boolean freezeWhileCarrying(MinecraftServer server, VisualContext context, int share) {
        ServerLevel level = server.overworld();
        SchematicannonRequesterBlockEntity requester = requester(level, context);
        Optional<RequesterOrder<ItemKey>> order = runningOrder(server, context, STONE);
        if (order.isEmpty()) {
            if (!requester.orders().isEmpty())
                throw new VisualTestException("the order left its running phase before the crane was seen carrying "
                        + "it: " + requester.orders());
            return false;
        }
        StackerCraneBlockEntity crane = crane(level, context);
        long held = 0;
        for (HeldItems.Entry entry : crane.heldItems().entries())
            if (entry.key().equals(STONE))
                held += entry.count();
        if (order.get().share() != share || held <= 0 || crane.craneState().phase() != CranePhase.TRAVEL_TO_TARGET
                || remainingTravel(crane) <= MIN_REMAINING_TRAVEL)
            return false;
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /**
     * Freezes the ticks while the order waits out its grace with stone riding the middle of the belt: the funnel takes
     * one item every few ticks, so the last ones are still on their way when the buffer runs empty.
     */
    private boolean freezeOnTheBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        List<RequesterOrder<ItemKey>> orders = requester(level, context).orders();
        if (orders.size() != 1)
            throw new VisualTestException("the requester has " + orders.size() + " orders on the way: " + orders);
        boolean midBelt = beltStone(level, context).stream().anyMatch(
                riding -> riding.beltPosition >= BELT_SHOT_FROM && riding.beltPosition <= BELT_SHOT_TO);
        if (!midBelt || orders.getFirst().phase() != RequesterOrder.Phase.GRACE)
            return false;
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /** The frozen moment of {@link #freezeWhileCarrying}: the order, its request, and what the crane holds. */
    private void checkCarriedOrder(MinecraftServer server, VisualContext context, int share) {
        RequesterOrder<ItemKey> order = runningOrder(server, context, STONE)
                .orElseThrow(() -> new VisualTestException("no stone order runs at the frozen moment"));
        RetrievalRequest<ItemKey, BlockPos> request = request(server, context, order.requestId());
        if (order.share() != share || order.ahead() != 0 || order.strict() || request.requested() != share
                || !request.key().equals(STONE))
            throw new VisualTestException("the order is " + order + " for " + request + ", expected " + share
                    + " plain stone ahead of nobody");
        if (requester(server.overworld(), context).orders().size() != 1)
            throw new VisualTestException("more than one order is open: "
                    + requester(server.overworld(), context).orders());
        assertCensus(server, context, "the crane carries " + share + " stone");
        LOGGER.info(PREFIX + "requester: CHECK PASS the crane carries the requester's order of {} stone, request {} "
                + "asks for {}, ahead of nobody", share, request.id(), request.requested());
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    // --- the goggles (client thread) ---------------------------------------------------------------------------------

    /** Whether the client's goggle summary shows {@code status} (with one row of {@code share}, unless 0). */
    private static boolean synced(VisualContext context, RequesterStatus status, int share) {
        return clientRequester(context).map(SchematicannonRequesterBlockEntity::summary)
                .filter(summary -> summary.status() == status && !summary.ownerName().isEmpty())
                .filter(summary -> share == 0 ? summary.rows().isEmpty()
                        : summary.rows().size() == 1 && summary.rows().getFirst().share() == share)
                .isPresent();
    }

    /**
     * Fails the run unless the goggles say what the header, the linked terminal, the owner and {@code wanted} say, in
     * the language the client runs in right now.
     */
    static void checkGoggles(VisualContext context, String moment, List<String> wanted) {
        BlockPos terminal = context.origin().offset(TERMINAL);
        String owner = context.minecraft().player.getGameProfile().getName();
        List<String> lines = GoggleShots.lines(context, context.origin().offset(REQUESTER));
        List<String> all = new ArrayList<>(List.of(text(WareworksLang.GOGGLES_SCHEMATICANNON_REQUESTER),
                text(WareworksLang.GOGGLES_REQUESTER_TERMINAL, Integer.toString(terminal.getX()),
                        Integer.toString(terminal.getY()), Integer.toString(terminal.getZ())),
                text(WareworksLang.GOGGLES_REQUESTER_OWNER, owner)));
        all.addAll(wanted);
        for (String line : all)
            GoggleShots.requireLine(lines, line);
        LOGGER.info(PREFIX + "requester: CHECK PASS the goggles at '{}' ({}) read {}", moment,
                context.minecraft().getLanguageManager().getSelected(), lines);
    }

    /** The running order's row, from the very numbers the client was sent: the share, what arrived, and the clock. */
    private static String orderedRow(VisualContext context) {
        RequesterSummary.Row row = clientRow(context);
        int seconds = row.liveTicks() / 20;
        String clock = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        return text(WareworksLang.requesterStatusKey(RequesterStatus.ORDERED), row.item().getDescription(),
                WareworksLang.number(row.share()), WareworksLang.number(row.delivered()), WareworksLang.text(clock));
    }

    /** The grace row: the seconds left and the item on its way. */
    private static String graceRow(VisualContext context) {
        RequesterSummary.Row row = clientRow(context);
        return text(WareworksLang.requesterStatusKey(RequesterStatus.GRACE), WareworksLang.number(row.graceLeft()),
                row.item().getDescription());
    }

    private static RequesterSummary.Row clientRow(VisualContext context) {
        return clientRequester(context).map(requester -> requester.summary().rows()).filter(rows -> rows.size() == 1)
                .map(List::getFirst)
                .orElseThrow(() -> new VisualTestException("the client's goggle summary has no single order row"));
    }

    /** What Create leaves of a schematic once its print is done; Create's items are only bound after loading. */
    private static ItemKey emptySchematic() {
        return ItemKey.of(AllItems.EMPTY_SCHEMATIC.asStack());
    }

    static String text(String key, Object... args) {
        return WareworksLang.translate(key, args).component().getString();
    }

    private static String skipMissingWarning() {
        return WareworksLang.translateDirect(WareworksLang.REQUESTER_SKIP_MISSING_WARNING,
                Component.translatable(RequesterLines.SKIP_MISSING_OPTION)).getString();
    }

    /**
     * The requester's goggle tooltip from {@link #GOGGLE_VIEW}, in English and, with {@code german}, in German too;
     * the language is switched while the scene stands still, and back to English afterwards.
     */
    private void goggles(VisualScript script, String label, Predicate<VisualContext> synced,
            VisualScript.ClientAction check, boolean german) {
        // A toast (a recipe unlocked by an item the player picked up) would sit across the corner of the shot.
        script.client("requester: clear the toasts before " + label, context -> context.minecraft().getToasts().clear());
        fly(script);
        GoggleShots.shot(script, NAME, GOGGLE_VIEW, "goggles-" + label,
                origin -> origin.offset(REQUESTER), synced, check);
        if (!german)
            return;
        language.switchTo(script, "requester: ", GERMAN);
        fly(script);
        GoggleShots.shot(script, NAME, GOGGLE_VIEW, "goggles-" + label + "-de",
                origin -> origin.offset(REQUESTER), synced, check);
        language.switchTo(script, "requester: ", ENGLISH);
    }

    /**
     * World shots, taken by a player who reaches nothing: Create draws the value box of whatever the crosshair targets
     * even with the GUI hidden. The reach comes back afterwards, for the clicks and goggles that follow.
     */
    private void worldShots(VisualScript script, String moment, CameraView... views) {
        GoggleShots.reach(script, NAME, 0.0);
        fly(script);
        for (CameraView view : views)
            script.shotFrom(view, moment);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    // --- input (client thread) ---------------------------------------------------------------------------------------

    /**
     * A right-click on {@code click}: the camera moves there, the crosshair is checked to be on the intended face, and
     * the use key is pressed once the way the mouse handler does ({@link ArmVisualScenario}).
     */
    private void rightClick(VisualScript script, ArmVisualScenario.Click click) {
        fly(script);
        script.camera(click.label(), context -> ArmVisualScenario.clickView(context, click));
        ArmVisualScenario.untilOrFail(script, "requester: the crosshair is on the " + click.face() + " face of the "
                        + click.label(), context -> ArmVisualScenario.aimedAt(context, click), AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context) + " instead of the "
                        + click.face() + " face of " + click.block());
        useKey(script, "the " + click.label());
        script.waitTicks(SETTLE_TICKS);
    }

    /** One press of the use key, the way the mouse handler does it, and a wait until the client handled it. */
    static void useKey(VisualScript script, String what) {
        script.client("requester: right-click " + what,
                context -> KeyMapping.click(context.minecraft().options.keyUse.getKey()));
        ArmVisualScenario.untilOrFail(script, "requester: the client handled the right-click on " + what,
                context -> ArmVisualScenario.clickCount(context.minecraft().options.keyUse) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
    }

    /**
     * Holds or releases the sneak key, and waits until the client crouches and the server has heard it: the server
     * learns it from the player's next movement packet, which a click in the same tick would overtake.
     */
    static void sneak(VisualScript script, boolean down) {
        String what = down ? "sneaks" : "stands up";
        script.client("requester: " + (down ? "hold" : "release") + " the sneak key",
                context -> context.minecraft().options.keyShift.setDown(down));
        ArmVisualScenario.untilOrFail(script, "requester: the client " + what,
                context -> context.minecraft().player.isShiftKeyDown() == down, SNEAK_TIMEOUT_TICKS,
                context -> "the client's sneak state is " + context.minecraft().player.isShiftKeyDown());
        script.serverUntil("requester: the server sees that the player " + what,
                (server, context) -> context.serverPlayer(server).isShiftKeyDown() == down, SNEAK_TIMEOUT_TICKS);
    }

    /** Waits until the action bar shows {@code message} in the current language. */
    static void actionBar(VisualScript script, String what, Function<VisualContext, Component> message) {
        ArmVisualScenario.untilOrFail(script, "requester: the action bar tells about " + what,
                context -> ArmVisualScenario.overlayText(context).equals(message.apply(context).getString()),
                MESSAGE_TIMEOUT_TICKS, context -> "the action bar reads '" + ArmVisualScenario.overlayText(context)
                        + "', not '" + message.apply(context).getString() + "'");
        script.client("requester: CHECK the action bar after " + what, context -> LOGGER.info(PREFIX
                + "requester: CHECK PASS the action bar reads '{}'", ArmVisualScenario.overlayText(context)));
    }

    /**
     * Opens the cannon's own screen with a right-click with an empty hand, and waits for the server's first contents
     * of its menu: a click sent before them is answered with a cursor the contents then overwrite.
     */
    private void openCannonScreen(VisualScript script) {
        takeAnEmptyHand(script);
        rightClick(script, CLICK_CANNON);
        ArmVisualScenario.untilOrFail(script, "requester: the Schematicannon's screen is open with its contents",
                context -> context.minecraft().screen instanceof SchematicannonScreen screen
                        && screen.getMenu().getStateId() > 0, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS);
    }

    static void closeScreen(VisualScript script, String what) {
        script.client("requester: close " + what, context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("requester: " + what + " is closed", context -> context.minecraft().screen == null,
                        SCREEN_TIMEOUT_TICKS);
    }

    /**
     * Carries a stack from one place in the open screen to another with two real left clicks — pick it up, put it
     * down — which is how a player moves an item when no shift-click is available to the harness.
     */
    static void moveInMenu(VisualScript script, String what, String where, Place from, Place to) {
        script.client("requester: pick " + what + " up",
                        context -> ScreenInput.click(context.minecraft(), from.point(containerScreen(context))))
                .until("requester: the cursor carries " + what,
                        context -> !containerScreen(context).getMenu().getCarried().isEmpty(), CLICK_TIMEOUT_TICKS)
                .waitTicks(CLICK_GAP_TICKS)
                .client("requester: put " + what + " into " + where,
                        context -> ScreenInput.click(context.minecraft(), to.point(containerScreen(context))))
                .until("requester: the cursor is empty again after putting " + what + " into " + where,
                        context -> containerScreen(context).getMenu().getCarried().isEmpty(), CLICK_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    /** A clickable place in the open container screen, resolved when the click is sent. */
    record Place(Function<AbstractContainerScreen<?>, ScreenInput.Point> lookup) {
        Place {
            Objects.requireNonNull(lookup, "lookup");
        }

        ScreenInput.Point point(AbstractContainerScreen<?> screen) {
            return lookup.apply(screen);
        }
    }

    static Place menuSlot(int index) {
        return new Place(screen -> ScreenInput.menuSlot(screen, index));
    }

    static Place hotbar(int hotbarIndex) {
        return new Place(screen -> ScreenInput.hotbarSlot(screen, hotbarIndex));
    }

    /**
     * The player takes hotbar slot {@code slot} in hand, which is what a hotbar key does, and the run waits until the
     * client really holds it.
     */
    static void selectHotbar(VisualScript script, int slot, String what, Predicate<ItemStack> held) {
        script.server("requester: take " + what + " in hand", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    player.getInventory().selected = slot;
                    player.connection.send(new ClientboundSetCarriedItemPacket(slot));
                })
                .until("requester: the client holds " + what, context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && player.getInventory().selected == slot
                            && held.test(player.getMainHandItem());
                }, HELD_ITEM_TIMEOUT_TICKS);
    }

    /** An empty hand, which is what lets a right-click open a block's own screen or flip a lever. */
    static void takeAnEmptyHand(VisualScript script) {
        selectHotbar(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
    }

    /** The player in the air and flying, which every view above the ground needs. */
    private void fly(VisualScript script) {
        script.server("requester: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("requester: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    static AbstractContainerScreen<?> containerScreen(VisualContext context) {
        if (context.minecraft().screen instanceof AbstractContainerScreen<?> screen)
            return screen;
        throw new VisualTestException("no container screen is open: " + context.minecraft().screen);
    }

    static SchematicannonScreen cannonScreen(VisualContext context) {
        if (context.minecraft().screen instanceof SchematicannonScreen screen
                && screen.getMenu() instanceof SchematicannonMenu)
            return screen;
        throw new VisualTestException("the Schematicannon's screen is not open: " + context.minecraft().screen);
    }

    /**
     * Standing on the ground west of the terminal, looking at its west face within reach: a crouch lowers the eye but
     * keeps the crosshair on the terminal.
     */
    private static CameraView linkView() {
        return new CameraView("link", new Vec3(TERMINAL.getX() - LINK_DISTANCE, Player.DEFAULT_EYE_HEIGHT,
                TERMINAL.getZ() + 0.5), new Vec3(TERMINAL.getX(), 0.5, TERMINAL.getZ() + 0.5));
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

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

    /**
     * Builds the hut and the step, saves each through Create's own exporter into the directory a player's upload
     * leaves it in ({@code schematics/uploaded/<player>}), hands the player the deployed schematic items, and clears
     * both sites again — so every block of them is missing and the cannon has to be fed all of it.
     * <p>
     * The banner's patterns are written the way a placed banner gets them ({@code BannerBlockEntity#fromItem}), and
     * the item the banner block entity names is the strict requirement the run expects to see ordered.
     */
    private void saveSchematics(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = context.origin();
        BlockPos site = origin.offset(SITE);
        for (int x = 0; x < SITE_LENGTH; x++)
            for (int z = 0; z < SITE_WIDTH; z++)
                level.setBlockAndUpdate(site.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        for (BlockPos spot : GLASS_SPOTS)
            level.setBlockAndUpdate(origin.offset(spot), Blocks.GLASS.defaultBlockState());
        BlockPos bannerPos = origin.offset(BANNER_SPOT);
        level.setBlockAndUpdate(bannerPos, Blocks.WHITE_BANNER.defaultBlockState());
        ItemStack patterned = new ItemStack(Items.WHITE_BANNER);
        patterned.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
                .addIfRegistered(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN),
                        BannerPatterns.STRIPE_BOTTOM, DyeColor.RED)
                .addIfRegistered(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN),
                        BannerPatterns.CROSS, DyeColor.BLUE)
                .build());
        if (!(level.getBlockEntity(bannerPos) instanceof BannerBlockEntity banner))
            throw new VisualTestException("the hut's banner has no block entity");
        banner.fromItem(patterned, DyeColor.WHITE);
        bannerKey = ItemKey.of(banner.getItem());
        BlockPos step = origin.offset(STEP);
        for (int x = 0; x < STEP_BLOCKS; x++)
            level.setBlockAndUpdate(step.offset(x, 0, 0), Blocks.STONE.defaultBlockState());

        String owner = context.serverPlayer(server).getGameProfile().getName();
        BlockPos siteFar = site.offset(SITE_LENGTH - 1, SITE_HEIGHT - 1, SITE_WIDTH - 1);
        BlockPos stepFar = step.offset(STEP_BLOCKS - 1, 0, 0);
        hutSchematic = saveSchematic(level, owner, HUT_FILE, site, siteFar);
        stepSchematic = saveSchematic(level, owner, STEP_FILE, step, stepFar);
        // Top layer first, so the banner never loses the block it stands on.
        for (BlockPos pos : BlockPos.betweenClosed(site.above(), siteFar))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (BlockPos pos : BlockPos.betweenClosed(site, siteFar.below()))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (BlockPos pos : BlockPos.betweenClosed(step, stepFar))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        LOGGER.info(PREFIX + "requester: saved the hut and the step; the banner is {}", bannerKey);
    }

    /** One schematic saved as an upload of {@code owner}, and the deployed schematic item that points at it. */
    static ItemStack saveSchematic(ServerLevel level, String owner, String file, BlockPos first,
            BlockPos second) {
        Path directory = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(owner);
        SchematicExport.SchematicExportResult result = SchematicExport.saveSchematic(directory, file, true, level,
                first, second);
        if (result == null)
            throw new VisualTestException("Create could not save the schematic " + file + " in " + directory);
        ItemStack schematic = SchematicItem.create(level, file, owner);
        schematic.set(AllDataComponents.SCHEMATIC_ANCHOR, first);
        schematic.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        LOGGER.info(PREFIX + "requester: saved {} (bounds {})", result.file(), result.bounds());
        return schematic;
    }

    private void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.offset(CONTROLLER),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        if (!layout.rackPos(TERMINAL_RACK).equals(dock.offset(TERMINAL)))
            throw new VisualTestException("the terminal's rack position " + layout.rackPos(TERMINAL_RACK)
                    + " is not where the belt starts behind it: " + dock.offset(TERMINAL));
        level.setBlockAndUpdate(layout.rackPos(TERMINAL_RACK), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL_RACK.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(INPUT_RACK), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT_RACK.side()).getOpposite()));

        Direction outward = layout.sideDirection(Side.LEFT);
        for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
            for (int y = 0; y < STORAGE_LEVELS; y++) {
                RackPosition position = RackPosition.of(x, y, Side.LEFT);
                BlockPos rack = layout.rackPos(position);
                BlockPos chest = rack.relative(outward);
                level.setBlockAndUpdate(chest,
                        Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                        .setValue(WarehouseInterfaceBlock.FACING, outward));
                if (position.equals(STONE_RACK)) {
                    for (int left = STONE_IN_STOCK; left > 0; left -= Math.min(left, STONE.getMaxStackSize()))
                        insert(level, chest, STONE.toStack(Math.min(left, STONE.getMaxStackSize())));
                } else if (position.equals(BANNER_RACK)) {
                    insert(level, chest, Objects.requireNonNull(bannerKey, "bannerKey").toStack(1));
                }
            }
        }
    }

    /**
     * The player's route out of the terminal's buffer: an andesite belt funnel on the start of a belt, which takes the
     * buffer onto it (it faces along the belt, so it pushes), the belt on its creative motor, a stop after its end and
     * an andesite belt funnel at the end that faces back along it and hands what arrives into the chest. The lever
     * beside the first funnel is on, so the funnel waits.
     */
    private void buildBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = context.origin();
        BlockPos start = origin.offset(BELT_START);
        BlockPos end = origin.offset(BELT_END);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the belt was not created between " + start + " and " + end);
        BlockPos motor = origin.offset(BELT_MOTOR);
        level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.WEST));
        motor(level, motor).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(origin.offset(BELT_STOP), Blocks.POLISHED_ANDESITE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(CHEST),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        beltFunnel(level, origin.offset(OUT_FUNNEL), BELT_MOVEMENT, true);
        beltFunnel(level, origin.offset(IN_FUNNEL), BELT_MOVEMENT.getOpposite(), false);
        level.setBlockAndUpdate(origin.offset(LEVER), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, true));
    }

    static void beltFunnel(ServerLevel level, BlockPos pos, Direction facing, boolean extracting) {
        level.setBlockAndUpdate(pos, AllBlocks.ANDESITE_BELT_FUNNEL.getDefaultState()
                .setValue(BeltFunnelBlock.HORIZONTAL_FACING, facing)
                .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.getShapeForPosition(level, pos, facing, extracting)));
    }

    private void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().offset(LIFT);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /** Flying, wearing Engineer's Goggles, and the hotbar of the story; the selected slot is the empty one. */
    private void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
        remoteKey = ItemKey.of(remote);
        player.getInventory().setItem(SLOT_CANNON, AllBlocks.SCHEMATICANNON.asStack());
        player.getInventory().setItem(SLOT_REQUESTER, WareworksBlocks.SCHEMATICANNON_REQUESTER.asStack());
        player.getInventory().setItem(SLOT_REMOTE, remote);
        player.getInventory().setItem(SLOT_SCHEMATIC, hutSchematic.copy());
        player.getInventory().setItem(SLOT_GUNPOWDER, GUNPOWDER.toStack(1));
        player.getInventory().setItem(SLOT_STEP_SCHEMATIC, stepSchematic.copy());
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            return false;
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        BeltBlockEntity belt = BeltHelper.getSegmentBE(level, dock.offset(BELT_START));
        if (controller == null || crane == null || belt == null)
            return false;
        int storage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS;
        boolean ready = controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == storage
                && controller.inputStations().size() == 1 && controller.outputStations().size() == 1
                && crane.isControllerLinked() && crane.aisleLength() == RAILS
                && controller.countOf(STONE) == STONE_IN_STOCK
                && controller.countOf(Objects.requireNonNull(bannerKey, "bannerKey")) == 1
                && belt.getSpeed() != 0;
        if (!ready)
            return false;
        if (belt.getMovementFacing() != BELT_MOVEMENT)
            throw new VisualTestException("the belt moves " + belt.getMovementFacing() + ", not " + BELT_MOVEMENT
                    + ": turn the belt motor the other way");
        if (!level.getBlockState(dock.offset(OUT_FUNNEL)).getValue(AbstractFunnelBlock.POWERED))
            throw new VisualTestException("the lever does not pause the funnel behind the terminal");
        return true;
    }

    // --- the census (server thread) ----------------------------------------------------------------------------------

    /**
     * Everything in the scene and on the player ({@link SceneItemCensus}), plus what no item capability reports: the
     * cannon's own slots, every block it has in flight, the remote in the requester's cradle, and every block printed
     * on the two sites as the items its requirement took (see the class comment).
     */
    private Map<ItemKey, Long> census(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos origin = context.origin();
        Map<ItemKey, Long> counts = SceneItemCensus.take(level, censusBox, context.serverPlayer(server));
        if (level.getBlockEntity(origin.offset(CANNON)) instanceof SchematicannonBlockEntity cannon) {
            for (int slot = 0; slot < cannon.inventory.getSlots(); slot++)
                counts = plus(counts, cannon.inventory.getStackInSlot(slot));
            for (LaunchedItem flying : cannon.flyingBlocks)
                counts = plus(counts, flying.stack);
        }
        SchematicannonRequesterBlockEntity requester = requesterOrNull(level, context);
        if (requester != null)
            counts = plus(counts, requester.remote());
        for (TransportedItemStack arriving : beltArrivals(level, context))
            counts = plus(counts, arriving.stack);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(SITE),
                origin.offset(SITE).offset(SITE_LENGTH - 1, SITE_HEIGHT - 1, SITE_WIDTH - 1)))
            counts = plusPrinted(level, counts, pos);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(STEP), origin.offset(STEP).offset(STEP_BLOCKS - 1, 0, 0)))
            counts = plusPrinted(level, counts, pos);
        return counts;
    }

    /**
     * What a funnel handed the belt in the tick that just ended: the belt keeps it apart until its own next tick
     * ({@code BeltInventory#toInsert}), and its item capability reports only what already rides it.
     */
    @SuppressWarnings("unchecked")
    static List<TransportedItemStack> beltArrivals(ServerLevel level, VisualContext context) {
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, context.origin().offset(BELT_START));
        if (belt == null || belt.getInventory() == null)
            return List.of();
        try {
            return List.copyOf((List<TransportedItemStack>) BELT_ARRIVALS.get(belt.getInventory()));
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read what the belt is about to take on: " + error);
        }
    }

    static Field beltArrivalsField() {
        try {
            Field field = BeltInventory.class.getDeclaredField("toInsert");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach the belt's items about to be inserted", error);
        }
    }

    /** {@code counts} plus the items a printed block's requirement consumed, nothing for air. */
    static Map<ItemKey, Long> plusPrinted(ServerLevel level, Map<ItemKey, Long> counts, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
            return counts;
        Map<ItemKey, Long> result = counts;
        for (ItemRequirement.StackRequirement required : ItemRequirement.of(state, level.getBlockEntity(pos))
                .getRequiredItems())
            if (required.usage == ItemRequirement.ItemUseType.CONSUME)
                result = plus(result, required.stack);
        return result;
    }

    static Map<ItemKey, Long> plus(Map<ItemKey, Long> counts, ItemStack stack) {
        return stack.isEmpty() ? counts : SceneItemCensus.plus(counts, ItemKey.of(stack), stack.getCount());
    }

    private void assertCensus(MinecraftServer server, VisualContext context, String step) {
        if (!SceneItemCensus.isFullyLoaded(server.overworld(), censusBox))
            throw new VisualTestException("the census box is not fully loaded at: " + step);
        Map<ItemKey, Long> actual = census(server, context);
        if (!actual.equals(expected))
            throw new VisualTestException("item conservation violated, " + step + ": expected "
                    + SceneItemCensus.describe(expected) + " but found " + SceneItemCensus.describe(actual));
        LOGGER.info(PREFIX + "requester: census PASS: {} (items {})", step, SceneItemCensus.describe(actual));
    }

    // --- server checks -----------------------------------------------------------------------------------------------

    /** Whether the requester shows {@code status} with the lamp that status has. */
    private static boolean showing(MinecraftServer server, VisualContext context, RequesterStatus status) {
        SchematicannonRequesterBlockEntity requester = requester(server.overworld(), context);
        BlockState state = requester.getBlockState();
        return requester.status() == status
                && state.getValue(SchematicannonRequesterBlock.LIT) == status.isLit()
                && state.getValue(SchematicannonRequesterBlock.REFUSED) == status.isRefused();
    }

    /** The requester's one running order of {@code key}, if it has one. */
    private static Optional<RequesterOrder<ItemKey>> runningOrder(MinecraftServer server, VisualContext context,
            ItemKey key) {
        return requester(server.overworld(), context).orders().stream()
                .filter(order -> order.isRunning() && order.key().equals(key)).findFirst();
    }

    /** The terminal's open request {@code id}; fails the run without one. */
    private static RetrievalRequest<ItemKey, BlockPos> request(MinecraftServer server, VisualContext context, UUID id) {
        ServerLevel level = server.overworld();
        return controller(level, context).requestsFor(context.origin().offset(TERMINAL)).stream()
                .filter(request -> request.id().equals(id)).findFirst()
                .orElseThrow(() -> new VisualTestException("the terminal has no open request " + id + ": "
                        + controller(level, context).requestsFor(context.origin().offset(TERMINAL))));
    }

    /** Fails the run while the terminal has any open request at all. */
    private static void assertNoRequests(MinecraftServer server, VisualContext context, String when) {
        List<RetrievalRequest<ItemKey, BlockPos>> open = controller(server.overworld(), context)
                .requestsFor(context.origin().offset(TERMINAL));
        if (!open.isEmpty())
            throw new VisualTestException("the terminal has open requests " + when + ": " + open);
    }

    /** Fails the run unless the requester has exactly one order, in {@code phase}, and the terminal no request. */
    private static void assertSingleOrder(MinecraftServer server, VisualContext context, RequesterOrder.Phase phase,
            String when) {
        List<RequesterOrder<ItemKey>> orders = requester(server.overworld(), context).orders();
        if (orders.size() != 1 || orders.getFirst().phase() != phase)
            throw new VisualTestException("the requester has " + orders + " " + when + ", expected one order "
                    + phase);
        assertNoRequests(server, context, when);
    }

    /** Fails the run unless the buffer, the belt and the chest are empty and no request is open. */
    private static void assertNothingOnTheWay(ServerLevel level, VisualContext context, String when) {
        if (terminal(level, context).hasBufferedItems())
            throw new VisualTestException("the buffer holds " + terminal(level, context).bufferedItems() + " " + when);
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, context.origin().offset(BELT_START));
        if (belt != null && !belt.getInventory().getTransportedItems().isEmpty())
            throw new VisualTestException("the belt still carries items " + when);
        IItemHandler chest = handler(level, context.origin().offset(CHEST));
        for (int slot = 0; slot < chest.getSlots(); slot++)
            if (!chest.getStackInSlot(slot).isEmpty())
                throw new VisualTestException("the chest still holds " + chest.getStackInSlot(slot) + " " + when);
        if (!controller(level, context).requestsFor(context.origin().offset(TERMINAL)).isEmpty())
            throw new VisualTestException("requests are still open " + when);
    }

    /** The stone riding the belt. */
    private static List<TransportedItemStack> beltStone(ServerLevel level, VisualContext context) {
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, context.origin().offset(BELT_START));
        if (belt == null)
            return List.of();
        return belt.getInventory().getTransportedItems().stream().filter(riding -> STONE.matches(riding.stack))
                .toList();
    }

    /** The glass in the terminal's buffer, on the belt and at the cannon: everything the glass order delivered. */
    private static long glassOnItsWay(ServerLevel level, VisualContext context) {
        long glass = terminal(level, context).bufferedItems().count(GLASS) + glassAtTheCannon(level, context);
        BeltBlockEntity belt = BeltHelper.getControllerBE(level, context.origin().offset(BELT_START));
        if (belt != null)
            for (TransportedItemStack riding : belt.getInventory().getTransportedItems())
                if (GLASS.matches(riding.stack))
                    glass += riding.stack.getCount();
        for (TransportedItemStack arriving : beltArrivals(level, context))
            if (GLASS.matches(arriving.stack))
                glass += arriving.stack.getCount();
        return glass;
    }

    /** The glass in the cannon's chest, in flight and printed: everything the glass order brought. */
    private static long glassAtTheCannon(ServerLevel level, VisualContext context) {
        long glass = count(handler(level, context.origin().offset(CHEST)), GLASS);
        for (LaunchedItem flying : cannon(level, context).flyingBlocks)
            if (GLASS.matches(flying.stack))
                glass += flying.stack.getCount();
        for (BlockPos spot : GLASS_SPOTS)
            if (level.getBlockState(context.origin().offset(spot)).is(Blocks.GLASS))
                glass++;
        return glass;
    }

    private static long count(IItemHandler handler, ItemKey key) {
        long count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (key.matches(handler.getStackInSlot(slot)))
                count += handler.getStackInSlot(slot).getCount();
        return count;
    }

    private static double remainingTravel(StackerCraneBlockEntity crane) {
        return Math.max(Math.abs(crane.craneState().target().x() - crane.craneState().pose().x()),
                Math.abs(crane.craneState().target().y() - crane.craneState().pose().y()));
    }

    // --- lookups -----------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    @Nullable
    private static SchematicannonRequesterBlockEntity requesterOrNull(ServerLevel level, VisualContext context) {
        return WareworksBlockEntityTypes.SCHEMATICANNON_REQUESTER.getNullable(level,
                context.origin().offset(REQUESTER));
    }

    private static SchematicannonRequesterBlockEntity requester(ServerLevel level, VisualContext context) {
        SchematicannonRequesterBlockEntity requester = requesterOrNull(level, context);
        if (requester == null)
            throw new VisualTestException("the Schematicannon Requester is missing");
        return requester;
    }

    private static Optional<SchematicannonRequesterBlockEntity> clientRequester(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin().offset(REQUESTER)) instanceof SchematicannonRequesterBlockEntity
                requester ? Optional.of(requester) : Optional.empty();
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    private static SchematicannonBlockEntity cannon(ServerLevel level, VisualContext context) {
        SchematicannonBlockEntity cannon = AllBlockEntityTypes.SCHEMATICANNON.getNullable(level,
                context.origin().offset(CANNON));
        if (cannon == null)
            throw new VisualTestException("the Schematicannon is missing");
        return cannon;
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        return crane;
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, VisualContext context) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                context.origin().offset(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal is missing");
        return terminal;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                context.origin().offset(CONTROLLER));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
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
}
