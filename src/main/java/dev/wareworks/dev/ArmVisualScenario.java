package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.CreateClient;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmAngleTarget;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointHandler;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmRenderer;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.item.ItemDescription;
import com.tterrag.registrate.util.entry.BlockEntry;

import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.DeliveryStationArmPoint;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.WarehouseInputArmPoint;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.production.ProductionOrder;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.lang.FontHelper;
import net.createmod.catnip.math.Pointing;
import net.createmod.catnip.outliner.Outline;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "arm": Create mechanical arms at the warehouse stations (M12, {@code docs/warehouse-system.md} §3.2.2), driven
 * like a player in a real client. It covers manual checks 87 to 91 of {@code docs/manual-test-checklist.md} section P and
 * the integrated-server half of check 92.
 * <p>
 * <b>Real clicks.</b> The player is a creative player with the vanilla reach, flying so the camera views hold. A click is
 * exactly what the mouse handler does for a button press ({@code MouseHandler#onPress} calls {@link KeyMapping#click}):
 * the use (or attack) key's click counter goes up, and on the next client tick {@code Minecraft#handleKeybinds} runs
 * {@code startUseItem} with the crosshair target of that tick ({@code Minecraft#hitResult}), which goes through
 * {@code MultiPlayerGameMode#useItemOn} and posts {@code PlayerInteractEvent.RightClickBlock} to Create's
 * {@link ArmInteractionPointHandler}. The camera is aimed so the crosshair really hits the intended face, and the run
 * checks that before every click. The only difference to a person is that no GLFW mouse event starts it and the key is
 * never held down, i.e. every click is a short click. Hotbar slots are chosen with the hotbar keys the same way. Screens
 * are opened by a right-click with an empty hand, and the terminal's requests are made in its screen with mouse input
 * through {@link ScreenInput} (scrolling the amount field, a left click on the item).
 * <p>
 * <b>What is read back.</b> The arm item's selection ({@code ArmInteractionPointHandler.currentSelection}), the colour of
 * each selection outline in Create's {@link Outliner}, the action bar text the player sees ({@code Gui} overlay message),
 * the value box and hint Create shows for the output's filter slot, and on the server the interaction points every placed
 * {@link ArmBlockEntity} resolved from its {@code ArmPlacementPacket} (type id, class and mode). Where a claw reaches is
 * measured on the frozen client with the arm renderer's own transforms ({@link #checkClawAim}). Those fields are private
 * or package-private in Create and Minecraft, so they are read by reflection; this is dev tooling only.
 * <p>
 * <b>Items.</b> Every item-moving phase is bracketed by a {@link SceneItemCensus} of the whole scene (stations, depots,
 * crafter, chests, crane, item entities), taken only while no arm and no crafter holds an item, since an arm's claw and a
 * crafting grid have no item capability.
 * <p>
 * Everything runs in {@link #setup}; each render pass adds one overview shot.
 */
public final class ArmVisualScenario implements VisualScenario {
    public static final String NAME = "arm";

    private static final String WORLD_FOLDER = "wareworks_visual_arm";
    /** Selection, four arms, a production order, a save and rejoin and two language reloads: far above the default. */
    private static final long RUN_TIMEOUT_MILLIS = 15L * 60L * 1000L;

    // --- scene (offsets from the dock; the aisle runs east, so the right rack side is +z and the left one -z) ---------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 8;
    private static final int STORAGE_FIRST_POSITION = 6;
    private static final int CRANE_RPM = 128;
    /** Slow enough that a claw can be frozen just before it reaches a station (1/32 of a movement per tick). */
    private static final int ARM_RPM = 32;
    private static final int CRAFTER_RPM = 128;

    private static final RackPosition INPUT_A_RACK = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = RackPosition.of(3, 0, Side.RIGHT);
    private static final RackPosition TERMINAL_RACK = RackPosition.of(5, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION_RACK = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition INPUT_B_RACK = RackPosition.of(3, 0, Side.LEFT);

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    private static final BlockPos RAIL_PROBE = new BlockPos(2, 0, 0);
    private static final BlockPos INPUT_A = new BlockPos(1, 0, 1);
    private static final BlockPos OUTPUT = new BlockPos(3, 0, 1);
    private static final BlockPos TERMINAL = new BlockPos(5, 0, 1);
    private static final BlockPos INTERFACE_PROBE = new BlockPos(STORAGE_FIRST_POSITION, 0, 1);
    private static final BlockPos PRODUCTION = new BlockPos(1, 0, -1);
    private static final BlockPos INPUT_B = new BlockPos(3, 0, -1);
    private static final BlockPos LOG_CHEST = new BlockPos(6, 0, 2);
    private static final BlockPos DIAMOND_CHEST = new BlockPos(7, 0, 2);
    /** A solid block on top of the input for the second half of check 89. */
    private static final BlockPos ON_INPUT_A = INPUT_A.above();

    private static final BlockPos ARM_A = new BlockPos(2, 0, 3);
    private static final BlockPos ARM_B = new BlockPos(4, 0, 3);
    private static final BlockPos ARM_C = new BlockPos(3, 0, 4);
    private static final BlockPos ARM_COG = new BlockPos(3, 0, 3);
    private static final BlockPos ARM_MOTOR = ARM_COG.below();
    private static final BlockPos DEPOT_A = new BlockPos(1, 0, 4);
    private static final BlockPos DEPOT_B = new BlockPos(5, 0, 4);
    private static final BlockPos DEPOT_C = new BlockPos(3, 0, 5);
    private static final BlockPos ARM_D = new BlockPos(1, 0, -3);
    private static final BlockPos ARM_D_COG = new BlockPos(0, 0, -3);
    private static final BlockPos ARM_D_MOTOR = ARM_D_COG.below();
    /** Faces east (rotation axis x) and points south, into the second warehouse input. */
    private static final BlockPos CRAFTER = new BlockPos(3, 0, -2);
    private static final BlockPos CRAFTER_COG = CRAFTER.above();
    private static final BlockPos CRAFTER_MOTOR = CRAFTER_COG.east();
    private static final BlockPos SPARE_CHEST = new BlockPos(-3, 0, 5);
    /** Where the player is lifted to before it starts flying: in the air beside the scene. */
    private static final BlockPos LIFT = new BlockPos(-3, 4, -4);

    private static final int CLEAR_MIN_X = -5;
    private static final int CLEAR_MAX_X = 12;
    private static final int CLEAR_MIN_Z = -7;
    private static final int CLEAR_MAX_Z = 8;
    private static final int CLEAR_HEIGHT = 7;

    // --- items ---------------------------------------------------------------------------------------------------------
    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey COPPER = ItemKey.of(Items.COPPER_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final int LOGS_IN_STOCK = 16;
    private static final int DIAMONDS_IN_STOCK = 32;
    private static final int FED_IRON = 32;
    private static final int FED_GOLD = 32;
    private static final int FED_COPPER = 16;
    private static final int OUTPUT_IRON = 16;
    private static final int TERMINAL_DIAMONDS = 12;
    private static final int OUTPUT_IRON_AFTER_REJOIN = 8;
    private static final int PLANKS_PER_LOG = 4;
    /** Three runs, so the arm has to carry three logs one at a time and the "waiting for the result" state lasts. */
    private static final int ORDERED_PLANKS = 12;
    private static final int ORDERED_PLANKS_AFTER_REJOIN = 4;

    // --- hotbar --------------------------------------------------------------------------------------------------------
    private static final int SLOT_ARM = 0;
    /** Never filled: selecting it gives the player an empty hand (to open screens, and for the hint control). */
    private static final int SLOT_EMPTY = 8;

    // --- timing --------------------------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int AIM_TIMEOUT_TICKS = 40;
    private static final int CLICK_TIMEOUT_TICKS = 20;
    private static final int OUTLINE_TIMEOUT_TICKS = 10;
    private static final int PLACE_TIMEOUT_TICKS = 200;
    private static final int POWER_TIMEOUT_TICKS = 200;
    private static final int CLAW_TIMEOUT_TICKS = 1200;
    private static final int STORE_TIMEOUT_TICKS = 2400;
    private static final int PRODUCTION_TIMEOUT_TICKS = 4800;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int RELOAD_TIMEOUT_TICKS = 2400;
    private static final int HELD_ITEM_TIMEOUT_TICKS = 100;
    private static final int SETTLE_TICKS = 4;
    /** Longer than Create's hover hint lasts without a refresh (11 ticks) and its outline fade. */
    private static final int HINT_SETTLE_TICKS = 25;
    private static final int HINT_TIMEOUT_TICKS = 60;
    /** A claw this far into its movement is frozen: at {@value #ARM_RPM} RPM one tick before it arrives and acts. */
    private static final float CLAW_FREEZE_PROGRESS = 0.95F;
    /**
     * The client arm must be at least this far into its movement in the frozen frame for the claw measurement: one or
     * two ticks behind the server, the claw is still close enough that its nearest face centre is a meaningful answer.
     */
    private static final float CLIENT_CLAW_MIN_PROGRESS = 0.9F;
    /** How far the claw's axis in the end pose may pass from the top face centre (float rounding of the angles). */
    private static final double CLAW_AXIS_TOLERANCE = 0.02;
    /**
     * How far the claw tip in the end pose may lie from the top face centre. Create aims the claw at the point from half a
     * block above it, but an arm whose reach ends short of that stops early, so the tip lands a little off along the axis.
     */
    private static final double CLAW_TIP_TOLERANCE = 0.2;

    // --- what a player reads -------------------------------------------------------------------------------------------
    private static final String DEPOSIT_TO_INPUT = "Deposit items to Warehouse Input";
    private static final String TAKE_FROM_OUTPUT = "Take items from Warehouse Output";
    private static final String TAKE_FROM_TERMINAL = "Take items from Warehouse Terminal";
    private static final String TAKE_FROM_PRODUCTION = "Take items from Warehouse Production";
    private static final String TAKE_FROM_DEPOT = "Take items from Depot";
    private static final String DEPOSIT_TO_DEPOT = "Deposit items to Depot";
    private static final String DEPOSIT_TO_CRAFTER = "Deposit items to Mechanical Crafter";
    private static final String GERMAN_DEPOSIT_TO_INPUT = "Lege Gegenstände in Lagereingang";
    private static final String GERMAN_HOLD_SHIFT = "Halte [Shift] für eine Zusammenfassung";

    // --- clicks: block offset, face, point on the face (block-local), eye offset from that point ----------------------
    private static final Vec3 SOUTH_EYE = new Vec3(0.0, 2.0, 1.6);
    private static final Vec3 NORTH_EYE = new Vec3(0.0, 2.0, -1.6);
    private static final Click CLICK_INTERFACE = new Click("interface", INTERFACE_PROBE, Direction.UP,
            new Vec3(0.3, 0.5, 0.75), SOUTH_EYE);
    private static final Click CLICK_CONTROLLER = new Click("controller", CONTROLLER, Direction.UP,
            new Vec3(0.2, 0.5, 0.2), new Vec3(-1.6, 2.0, 0.0));
    private static final Click CLICK_DOCK = new Click("dock", DOCK, Direction.UP, new Vec3(0.75, 0.5, 0.2),
            new Vec3(0.0, 2.2, -1.8));
    private static final Click CLICK_RAIL = new Click("rail", RAIL_PROBE, Direction.UP, new Vec3(0.5, 0.5, 0.2),
            new Vec3(0.0, 2.2, -1.8));
    private static final Click CLICK_INPUT_A = new Click("input", INPUT_A, Direction.UP, new Vec3(0.25, 0.5, 0.75),
            SOUTH_EYE);
    private static final Click CLICK_INPUT_A_WEST = new Click("input-west-face", INPUT_A, Direction.WEST,
            new Vec3(0.5, 0.5, 0.5), new Vec3(-2.2, 1.3, 0.0));
    /** Off the centre of the top face: the output's request filter slot sits in that centre. */
    private static final Click CLICK_OUTPUT = new Click("output", OUTPUT, Direction.UP, new Vec3(0.2, 0.5, 0.8),
            SOUTH_EYE);
    private static final Click CLICK_OUTPUT_FILTER_SLOT = new Click("output-filter-slot", OUTPUT, Direction.UP,
            new Vec3(0.5, 0.5, 0.5), SOUTH_EYE);
    private static final Click CLICK_TERMINAL = new Click("terminal", TERMINAL, Direction.UP,
            new Vec3(0.25, 0.5, 0.75), SOUTH_EYE);
    private static final Click CLICK_PRODUCTION = new Click("production", PRODUCTION, Direction.UP,
            new Vec3(0.25, 0.5, 0.25), NORTH_EYE);
    private static final Click CLICK_DEPOT_A = new Click("depot-a", DEPOT_A, Direction.UP, new Vec3(0.5, 0.5, 0.5),
            new Vec3(-1.8, 2.2, 0.0));
    private static final Click CLICK_DEPOT_B = new Click("depot-b", DEPOT_B, Direction.UP, new Vec3(0.5, 0.5, 0.5),
            new Vec3(1.8, 2.2, 0.0));
    private static final Click CLICK_DEPOT_C = new Click("depot-c", DEPOT_C, Direction.UP, new Vec3(0.5, 0.5, 0.5),
            new Vec3(0.0, 2.2, 1.8));
    /** The crafter's top is covered by its cogwheel, so it is clicked on its west face. */
    private static final Click CLICK_CRAFTER = new Click("crafter", CRAFTER, Direction.WEST, new Vec3(0.5, 0.45, 0.5),
            new Vec3(-2.2, 1.3, 0.0));
    private static final Click GROUND_ARM_A = new Click("ground-arm-a", ARM_A.below(), Direction.UP,
            new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 2.4, 1.8));
    private static final Click GROUND_ARM_B = new Click("ground-arm-b", ARM_B.below(), Direction.UP,
            new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 2.4, 1.8));
    private static final Click GROUND_ARM_C = new Click("ground-arm-c", ARM_C.below(), Direction.UP,
            new Vec3(0.5, 0.5, 0.5), new Vec3(-1.8, 2.6, 0.0));
    private static final Click GROUND_ARM_D = new Click("ground-arm-d", ARM_D.below(), Direction.UP,
            new Vec3(0.5, 0.5, 0.5), new Vec3(0.0, 2.4, -1.8));

    // --- camera views (relative to the dock's lower corner) ------------------------------------------------------------
    private static final CameraView CLAW_INPUT_WEST = CameraView.of("input-west", -2.5, 1.75, 1.5, 1.5, 1.1, 1.5);
    private static final CameraView CLAW_INPUT_SOUTHWEST = CameraView.of("input-southwest", -1.0, 2.6, 4.0, 1.5, 1.0,
            1.5);
    private static final CameraView CLAW_OUTPUT_EAST = CameraView.of("output-east", 6.8, 2.9, 1.5, 3.5, 1.0, 1.5);
    private static final CameraView CLAW_OUTPUT_SOUTHEAST = CameraView.of("output-southeast", 5.8, 2.8, 4.2, 3.5, 1.0,
            1.5);
    private static final CameraView CLAW_PRODUCTION_WEST = CameraView.of("production-west", -2.5, 1.75, -0.5, 1.5, 1.1,
            -0.5);
    private static final CameraView CLAW_PRODUCTION_NORTHWEST = CameraView.of("production-northwest", -1.0, 2.6, -3.0,
            1.5, 1.0, -0.5);
    private static final CameraView OVERVIEW = CameraView.of("overview", 3.5, 5.5, 7.5, 2.5, 0.0, -0.5);

    // --- run state -----------------------------------------------------------------------------------------------------
    @Nullable
    private volatile BlockPos origin;
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Client thread: the action bar component shown before the last click, to recognise a new message. */
    @Nullable
    private Component overlayBeforeClick;
    @Nullable
    private CompletableFuture<Void> languageReload;
    /** Server thread: the production order whose states the tick listener records. */
    @Nullable
    private volatile UUID trackedOrder;
    private final List<ProductionOrderState> observedStates = new CopyOnWriteArrayList<>();
    /** Server thread: the production orders that existed before a request in the terminal screen. */
    private volatile List<UUID> ordersBeforeRequest = List.of();
    private volatile int maxHeldByArmD;
    @Nullable
    private volatile ClawRequest clawRequest;
    private volatile boolean clawFrozen;
    private boolean listenerInstalled;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with real interaction: creative and the vanilla reach, unlike the spectator camera profile. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("arm: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "arm run"))
                .client("arm: record production order states on every server tick", this::installListener)
                .server("arm: clear the area and place the motors", this::placeMotors)
                .server("arm: build the aisle, stations, storage, depots, crafter and cogwheels", this::buildScene)
                .serverUntil("arm: wait until the controller has every member and the stock", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                // A client player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so
                // the player is lifted into the air first and only then made to fly.
                .server("arm: lift the player into the air", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    BlockPos above = context.origin().offset(LIFT);
                    player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5,
                            player.getYRot(), player.getXRot());
                })
                .waitTicks(SETTLE_TICKS)
                .server("arm: the player flies and holds a Mechanical Arm", ArmVisualScenario::holdArm)
                .until("arm: the client holds the Mechanical Arm",
                        context -> context.minecraft().player != null
                                && AllBlocks.MECHANICAL_ARM.isIn(context.minecraft().player.getMainHandItem()),
                        HELD_ITEM_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);

        nonTargets(script);
        selectAndPlaceArms(script);
        armDeliversToInput(script);
        armsEmptyOutputAndTerminal(script);
        crafterLoop(script);
        saveQuitAndRejoin(script);
        german(script);

        script.client("arm: every check passed",
                context -> LOGGER.info(PREFIX + "arm: ALL CHECKS PASSED (87, 88, 89, 90, 91, 92 integrated half)"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.shotFrom(OVERVIEW, "scene");
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        Component overlay = overlay(context);
        status.append("selection=").append(selectionSummary()).append(" actionBar='")
                .append(overlay == null ? "" : overlay.getString()).append('\'');
        ClientLevel level = context.minecraft().level;
        BlockPos base = origin;
        if (level != null && base != null) {
            for (Map.Entry<String, BlockPos> arm : Map.of("A", ARM_A, "B", ARM_B, "C", ARM_C, "D", ARM_D).entrySet()) {
                if (level.getBlockEntity(base.offset(arm.getValue())) instanceof ArmBlockEntity be)
                    status.append(String.format(Locale.ROOT, " arm%s=%s/%.2f", arm.getKey(), Reflect.get(Reflect.ARM_PHASE, be),
                            (Float) Reflect.get(Reflect.ARM_PROGRESS, be)));
            }
        }
        return status.toString();
    }

    // --- 88, second half: blocks that are no arm target ------------------------------------------------------------------

    /**
     * Interface, controller, crane dock and rail: a right-click with the arm item selects nothing and shows no selection
     * message, and the arm is simply placed against the block. The selection is empty during these clicks, so the placed
     * arm receives no points; it is removed again right away.
     */
    private void nonTargets(VisualScript script) {
        for (Click click : List.of(CLICK_INTERFACE, CLICK_CONTROLLER, CLICK_DOCK, CLICK_RAIL)) {
            BlockPos placed = click.block().relative(click.face());
            rightClick(script, click, true);
            script.serverUntil("arm: an arm was placed against the " + click.label(),
                            (server, context) -> AllBlocks.MECHANICAL_ARM.has(
                                    server.overworld().getBlockState(at(context, placed))),
                            PLACE_TIMEOUT_TICKS)
                    .client("arm: CHECK 88 the " + click.label() + " is no arm target", context -> {
                        if (!selection().isEmpty())
                            throw new VisualTestException("the click on the " + click.label() + " selected "
                                    + selectionSummary());
                        Component shown = overlay(context);
                        if (shown != overlayBeforeClick)
                            throw new VisualTestException("the click on the " + click.label()
                                    + " showed the action bar message '" + shown.getString() + "'");
                        LOGGER.info(PREFIX + "arm: CHECK 88 PASS: right-click on the {} selected nothing, showed no "
                                + "selection message, and placed an arm at {} instead", click.label(), at(context, placed));
                    });
            if (click == CLICK_RAIL)
                script.shotWithGui("nontarget-rail");
            script.server("arm: remove the arm placed against the " + click.label(), (server, context) -> {
                        ServerLevel level = server.overworld();
                        ArmBlockEntity arm = armAt(level, at(context, placed));
                        if (!arm(arm).inputs().isEmpty() || !arm(arm).outputs().isEmpty())
                            throw new VisualTestException("the arm placed against the " + click.label()
                                    + " has interaction points");
                        level.setBlockAndUpdate(at(context, placed), Blocks.AIR.defaultBlockState());
                    })
                    .until("arm: the client sees the arm against the " + click.label() + " removed",
                            context -> context.minecraft().level != null
                                    && context.minecraft().level.getBlockState(at(context, placed)).isAir(),
                            PLACE_TIMEOUT_TICKS);
        }
        script.serverUntil("arm: the controller is ready again after the probes", this::sceneReady,
                SCENE_READY_TIMEOUT_TICKS);
    }

    // --- 87, 88: selecting targets and placing arms ------------------------------------------------------------------

    private void selectAndPlaceArms(VisualScript script) {
        // Arm A: depot -> warehouse input.
        rightClick(script, CLICK_INPUT_A, true);
        expectSelected(script, CLICK_INPUT_A, Mode.DEPOSIT, WarehouseInputArmPoint.class, DEPOSIT_TO_INPUT, 1, "87");
        script.shotWithGui("select-input-click1");
        for (int click = 2; click <= 3; click++) {
            rightClick(script, CLICK_INPUT_A, false);
            expectSelected(script, CLICK_INPUT_A, Mode.DEPOSIT, WarehouseInputArmPoint.class, DEPOSIT_TO_INPUT, 1, "87");
        }
        script.shotWithGui("select-input-click3");
        // For comparison, as the checklist says: an ordinary depot switches on every click.
        String[] depotMessages = { TAKE_FROM_DEPOT, DEPOSIT_TO_DEPOT, TAKE_FROM_DEPOT };
        Mode[] depotModes = { Mode.TAKE, Mode.DEPOSIT, Mode.TAKE };
        for (int click = 0; click < depotModes.length; click++) {
            rightClick(script, CLICK_DEPOT_A, click == 0);
            expectSelected(script, CLICK_DEPOT_A, depotModes[click], AllArmInteractionPointTypes.DepotPoint.class,
                    depotMessages[click], 2, "87 (depot comparison)");
        }
        script.shotWithGui("select-input-and-depot");
        placeArm(script, "A", GROUND_ARM_A, ARM_A,
                List.of(new PointExpectation(DEPOT_A, "create:depot", Mode.TAKE,
                        AllArmInteractionPointTypes.DepotPoint.class)),
                List.of(new PointExpectation(INPUT_A, "wareworks:warehouse_input", Mode.DEPOSIT,
                        WarehouseInputArmPoint.class)));
        script.shotWithGui("placed-arm-a");

        // Arm B: warehouse output -> depot. The first click lands on the output's request filter slot.
        outputFilterSlot(script);
        for (int click = 2; click <= 3; click++) {
            rightClick(script, CLICK_OUTPUT, click == 2);
            expectSelected(script, CLICK_OUTPUT, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_OUTPUT, 1, "88");
        }
        script.shotWithGui("select-output-click3");
        rightClick(script, CLICK_DEPOT_B, true);
        rightClick(script, CLICK_DEPOT_B, false);
        expectSelected(script, CLICK_DEPOT_B, Mode.DEPOSIT, AllArmInteractionPointTypes.DepotPoint.class,
                DEPOSIT_TO_DEPOT, 2, "88 (depot target)");
        placeArm(script, "B", GROUND_ARM_B, ARM_B,
                List.of(new PointExpectation(OUTPUT, "wareworks:warehouse_output", Mode.TAKE,
                        DeliveryStationArmPoint.class)),
                List.of(new PointExpectation(DEPOT_B, "create:depot", Mode.DEPOSIT,
                        AllArmInteractionPointTypes.DepotPoint.class)));
        // Switching the hand away from the arm item clears Create's selection, so this waits until arm B is placed.
        filterSlotHint(script);

        // Arm C: warehouse terminal -> depot, with a left-click that removes the selection in between.
        for (int click = 1; click <= 3; click++) {
            rightClick(script, CLICK_TERMINAL, click == 1);
            expectSelected(script, CLICK_TERMINAL, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_TERMINAL, 1,
                    "88");
        }
        script.shotWithGui("select-terminal-click3");
        leftClick(script, CLICK_TERMINAL);
        script.client("arm: CHECK 88 a left-click removed the terminal from the selection", context -> {
                    if (!selection().isEmpty())
                        throw new VisualTestException("the left-click did not remove the terminal: " + selectionSummary());
                    if (context.minecraft().level == null || !WareworksBlocks.WAREHOUSE_TERMINAL
                            .has(context.minecraft().level.getBlockState(at(context, TERMINAL))))
                        throw new VisualTestException("the left-click with the arm item broke the terminal");
                    LOGGER.info(PREFIX + "arm: CHECK 88 PASS: left-click removed the terminal selection, the block stays");
                })
                .waitTicks(SETTLE_TICKS)
                .serverUntil("arm: the terminal still exists on the server after the left-click",
                        (server, context) -> WareworksBlocks.WAREHOUSE_TERMINAL.has(
                                server.overworld().getBlockState(at(context, TERMINAL))),
                        CLICK_TIMEOUT_TICKS)
                .shotWithGui("deselect-terminal");
        rightClick(script, CLICK_TERMINAL, false);
        expectSelected(script, CLICK_TERMINAL, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_TERMINAL, 1, "88");
        rightClick(script, CLICK_DEPOT_C, true);
        rightClick(script, CLICK_DEPOT_C, false);
        expectSelected(script, CLICK_DEPOT_C, Mode.DEPOSIT, AllArmInteractionPointTypes.DepotPoint.class,
                DEPOSIT_TO_DEPOT, 2, "88 (depot target)");
        placeArm(script, "C", GROUND_ARM_C, ARM_C,
                List.of(new PointExpectation(TERMINAL, "wareworks:warehouse_terminal", Mode.TAKE,
                        DeliveryStationArmPoint.class)),
                List.of(new PointExpectation(DEPOT_C, "create:depot", Mode.DEPOSIT,
                        AllArmInteractionPointTypes.DepotPoint.class)));

        // Arm D: production station -> mechanical crafter.
        for (int click = 1; click <= 3; click++) {
            rightClick(script, CLICK_PRODUCTION, click == 1);
            expectSelected(script, CLICK_PRODUCTION, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_PRODUCTION, 1,
                    "88");
        }
        script.shotWithGui("select-production-click3");
        rightClick(script, CLICK_CRAFTER, true);
        rightClick(script, CLICK_CRAFTER, false);
        expectSelected(script, CLICK_CRAFTER, Mode.DEPOSIT, AllArmInteractionPointTypes.CrafterPoint.class,
                DEPOSIT_TO_CRAFTER, 2, "90 (crafter target)");
        placeArm(script, "D", GROUND_ARM_D, ARM_D,
                List.of(new PointExpectation(PRODUCTION, "wareworks:warehouse_production", Mode.TAKE,
                        DeliveryStationArmPoint.class)),
                List.of(new PointExpectation(CRAFTER, "create:crafter", Mode.DEPOSIT,
                        AllArmInteractionPointTypes.CrafterPoint.class)));
    }

    /**
     * The output's request filter slot is a Create value box in the centre of its top face (and of three sides), right
     * where a player clicks. A right-click there with the arm item must select the output like anywhere else on the face
     * and must not touch the filter. Before {@code RequestFilterBehaviour#bypassesInput} the value box swallowed this
     * click and nothing happened at all (found by this scenario; GameTest {@code outputfilterslotletsarmselectionthrough}).
     */
    private void outputFilterSlot(VisualScript script) {
        rightClick(script, CLICK_OUTPUT_FILTER_SLOT, true);
        expectSelected(script, CLICK_OUTPUT_FILTER_SLOT, Mode.TAKE, DeliveryStationArmPoint.class, TAKE_FROM_OUTPUT, 1,
                "88 (click on the output's filter slot)");
        script.server("arm: CHECK 88 the click on the filter slot left the request filter alone", (server, context) -> {
                    FilteringBehaviour filter = outputFilter(server.overworld(), context);
                    if (!filter.getFilter().isEmpty())
                        throw new VisualTestException("the click with the arm item set the output's request filter to "
                                + filter.getFilter());
                    LOGGER.info(PREFIX + "arm: CHECK 88 PASS: a right-click on the output's request filter slot "
                            + "selected the output and left its filter empty");
                })
                .shotWithGui("select-output-filter-slot");
    }

    /**
     * While the arm item hovers the output's request filter slot, Create draws neither the slot's value box nor its
     * "Click with item to set" hint ({@code RequestFilterBehaviour#mayInteract}); with an empty hand over the same spot it
     * draws both, which shows that the check can see them. Read from Create's outliner (the value box outline) and its
     * value settings overlay (the hint, counted down every tick while not refreshed); the GUI is hidden in these steps,
     * which only skips drawing the hint, not Create's tick that shows it.
     */
    private void filterSlotHint(VisualScript script) {
        aim(script, CLICK_OUTPUT_FILTER_SLOT, true);
        script.waitTicks(HINT_SETTLE_TICKS)
                .client("arm: CHECK 88 no value box and no hint on the output's filter slot under the arm item", context -> {
                    if (!aimedAt(context, CLICK_OUTPUT_FILTER_SLOT))
                        throw new VisualTestException("the crosshair left the filter slot: " + describeHit(context));
                    if (!AllBlocks.MECHANICAL_ARM.isIn(context.minecraft().player.getMainHandItem()))
                        throw new VisualTestException("the player does not hold the arm item");
                    BlockPos output = at(context, OUTPUT);
                    if (filterValueBoxShown(output))
                        throw new VisualTestException("Create draws the filter slot's value box under the arm item");
                    if (CreateClient.VALUE_SETTINGS_HANDLER.hoverTicks > 0)
                        throw new VisualTestException("Create shows the hint " + hintText() + " under the arm item");
                    LOGGER.info(PREFIX + "arm: CHECK 88 PASS: {} ticks with the arm item on the output's filter slot: no "
                            + "value box outline, no hint", HINT_SETTLE_TICKS);
                });
        selectHotbar(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
        untilOrFail(script, "arm: CHECK 88 (control) with an empty hand the filter slot shows its value box and hint",
                context -> aimedAt(context, CLICK_OUTPUT_FILTER_SLOT) && filterValueBoxShown(at(context, OUTPUT))
                        && CreateClient.VALUE_SETTINGS_HANDLER.hoverTicks > 0,
                HINT_TIMEOUT_TICKS, context -> "value box " + filterValueBoxShown(at(context, OUTPUT)) + ", hint "
                        + hintText() + ", crosshair " + describeHit(context));
        script.client("arm: CHECK 88 (control) log the hint an empty hand gets", context -> LOGGER.info(PREFIX
                        + "arm: CHECK 88 (control) PASS: with an empty hand the same spot shows the value box and the hint {}",
                        hintText()))
                .shotWithGui("output-filter-slot-empty-hand");
        selectHotbar(script, SLOT_ARM, "the Mechanical Arm", AllBlocks.MECHANICAL_ARM::isIn);
    }

    /** Whether Create's outliner holds the value box outline {@code FilteringRenderer} shows for a filter slot at pos. */
    private static boolean filterValueBoxShown(BlockPos pos) {
        for (Object key : Outliner.getInstance().getOutlines().keySet()) {
            if (key instanceof Pair<?, ?> pair && pair.getFirst() instanceof String name && name.startsWith("filter")
                    && pos.equals(pair.getSecond()))
                return true;
        }
        return false;
    }

    private static String hintText() {
        List<MutableComponent> tip = CreateClient.VALUE_SETTINGS_HANDLER.lastHoverTip;
        return tip == null ? "none" : "'" + tip.stream().map(Component::getString).toList() + "' ("
                + CreateClient.VALUE_SETTINGS_HANDLER.hoverTicks + " ticks left)";
    }

    /** Selects a hotbar slot with its hotbar key, as a player does, and waits until the client holds what it should. */
    private static void selectHotbar(VisualScript script, int slot, String what, Predicate<ItemStack> expected) {
        script.client("arm: press hotbar key " + (slot + 1) + " for " + what,
                context -> KeyMapping.click(context.minecraft().options.keyHotbarSlots[slot].getKey()));
        untilOrFail(script, "arm: the player holds " + what, context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getInventory().selected == slot && expected.test(player.getMainHandItem());
        }, HELD_ITEM_TIMEOUT_TICKS, context -> "the player holds " + context.minecraft().player.getMainHandItem()
                + " in slot " + context.minecraft().player.getInventory().selected);
        script.waitTicks(SETTLE_TICKS);
    }

    // --- 89: arm A fills the input, with and without a block on top ----------------------------------------------------

    private void armDeliversToInput(VisualScript script) {
        script.server("arm: baseline census before the arms move anything", (server, context) -> {
                    expected = SceneItemCensus.take(server.overworld(), censusBox);
                    LOGGER.info(PREFIX + "arm: baseline census {}", SceneItemCensus.describe(expected));
                })
                .server("arm: put " + FED_IRON + " iron ingots on depot A",
                        (server, context) -> insertAt(server, context, DEPOT_A, IRON.toStack(FED_IRON)))
                .server("arm: census with the iron on the depot",
                        (server, context) -> census(server, "iron put on depot A"))
                .server("arm: power the four arms and the crafter", ArmVisualScenario::powerMachines)
                .serverUntil("arm: the arms and the crafter turn", ArmVisualScenario::machinesTurn, POWER_TIMEOUT_TICKS);
        clawShot(script, "the warehouse input", ARM_A, INPUT_A, ArmBlockEntity.Phase.MOVE_TO_OUTPUT,
                List.of(CLAW_INPUT_WEST, CLAW_INPUT_SOUTHWEST), "claw", "89");
        script.serverUntil("arm: arm A emptied the depot into the input and the crane stored the iron",
                        (server, context) -> stored(server, context, IRON, FED_IRON), STORE_TIMEOUT_TICKS)
                .server("arm: CHECK 89 census after arm A fed the input", (server, context) -> {
                    census(server, "arm A fed the input, the crane stored it");
                    LOGGER.info(PREFIX + "arm: CHECK 89 PASS: arm A moved {} iron from the depot into the warehouse "
                            + "input and the crane stored all of it", FED_IRON);
                })
                .server("arm: put a solid block on top of the input", (server, context) -> server.overworld()
                        .setBlockAndUpdate(at(context, ON_INPUT_A), Blocks.STONE.defaultBlockState()))
                .server("arm: put " + FED_GOLD + " gold ingots on depot A",
                        (server, context) -> insertAt(server, context, DEPOT_A, GOLD.toStack(FED_GOLD)));
        clawShot(script, "the covered warehouse input", ARM_A, INPUT_A, ArmBlockEntity.Phase.MOVE_TO_OUTPUT,
                List.of(CLAW_INPUT_WEST), "claw-covered", "89");
        script.serverUntil("arm: arm A fed the covered input and the crane stored the gold",
                        (server, context) -> stored(server, context, GOLD, FED_GOLD), STORE_TIMEOUT_TICKS)
                .server("arm: CHECK 89 census after arm A fed the covered input", (server, context) -> {
                    census(server, "arm A fed the input under a stone block");
                    LOGGER.info(PREFIX + "arm: CHECK 89 PASS: with a stone block on top of the input, arm A still "
                            + "delivered {} gold and the crane stored it", FED_GOLD);
                });
    }

    // --- 89: arms B and C empty the output and the terminal ------------------------------------------------------------

    private void armsEmptyOutputAndTerminal(VisualScript script) {
        requestAtTerminal(script, DIAMOND, TERMINAL_DIAMONDS, "diamonds", "89", (server, context) -> {
        }, (server, context) -> {
            for (RetrievalRequest<ItemKey, BlockPos> request : controller(server.overworld(), context)
                    .requestsFor(at(context, TERMINAL)))
                if (request.key().equals(DIAMOND) && request.requested() == TERMINAL_DIAMONDS) {
                    LOGGER.info(PREFIX + "arm: CHECK 89 PASS: the click in the terminal screen queued a request for {} "
                            + "diamonds at the terminal ({})", TERMINAL_DIAMONDS, request.id());
                    return true;
                }
            return false;
        });
        // Requested only now, so arm B's first reach into the output cannot happen before the claw shot is armed.
        script.server("arm: request iron at the output (the controller call its redstone input makes)",
                (server, context) -> requireAccepted(controller(server.overworld(), context).request(at(context, OUTPUT),
                        IRON, OUTPUT_IRON), "iron at the output"));
        clawShot(script, "the warehouse output", ARM_B, OUTPUT, ArmBlockEntity.Phase.MOVE_TO_INPUT,
                List.of(CLAW_OUTPUT_EAST, CLAW_OUTPUT_SOUTHEAST), "claw", "89");
        script.serverUntil("arm: arms B and C moved the delivered items onto their depots", (server, context) -> {
                    ServerLevel level = server.overworld();
                    return countAt(level, at(context, DEPOT_B), Items.IRON_INGOT) == OUTPUT_IRON
                            && countAt(level, at(context, DEPOT_C), Items.DIAMOND) == TERMINAL_DIAMONDS
                            && station(level, context, OUTPUT).bufferedItems().isEmpty()
                            && station(level, context, TERMINAL).bufferedItems().isEmpty() && quiet(level, context);
                }, STORE_TIMEOUT_TICKS)
                .server("arm: CHECK 89 census after arms B and C emptied output and terminal", (server, context) -> {
                    census(server, "arms B and C took the delivered items out of output and terminal");
                    LOGGER.info(PREFIX + "arm: CHECK 89 PASS: arm B took {} iron out of the warehouse output, arm C "
                            + "took {} diamonds out of the warehouse terminal", OUTPUT_IRON, TERMINAL_DIAMONDS);
                });
    }

    // --- 90: the crafter loop ------------------------------------------------------------------------------------------

    private void crafterLoop(VisualScript script) {
        orderPlanks(script, ORDERED_PLANKS, "for the crafter loop");
        clawShot(script, "the production station", ARM_D, PRODUCTION, ArmBlockEntity.Phase.MOVE_TO_INPUT,
                List.of(CLAW_PRODUCTION_WEST, CLAW_PRODUCTION_NORTHWEST), "claw", "90");
        openTerminalScreen(script);
        untilOrFail(script, "arm: the terminal shows the order waiting for the result",
                context -> screenOrderState(context) == ProductionOrderState.WAITING_FOR_RESULT,
                PRODUCTION_TIMEOUT_TICKS, context -> "the order is " + screenOrderState(context));
        script.waitTicks(SETTLE_TICKS).shot("terminal-order-waiting-for-result");
        untilOrFail(script, "arm: the terminal shows the order complete",
                context -> screenOrderState(context) == ProductionOrderState.COMPLETE, PRODUCTION_TIMEOUT_TICKS,
                context -> "the order is " + screenOrderState(context));
        script.waitTicks(SETTLE_TICKS).shot("terminal-order-complete");
        closeScreen(script);
        script.serverUntil("arm: the planks reached the terminal and every machine is idle", (server, context) -> {
                    ServerLevel level = server.overworld();
                    return countAt(level, at(context, TERMINAL), Items.OAK_PLANKS) == ORDERED_PLANKS
                            && quiet(level, context);
                }, STORE_TIMEOUT_TICKS)
                .server("arm: CHECK 90 the crafter loop", (server, context) -> checkProduction(server, context,
                        ORDERED_PLANKS, "the crafter loop"));
    }

    // --- 92 (integrated server): save, quit to title, rejoin ----------------------------------------------------------

    private void saveQuitAndRejoin(VisualScript script) {
        script.server("arm: census before saving", (server, context) -> census(server, "before save and quit"))
                .server("arm: save the world", (server, context) -> server.saveEverything(true, true, true));
        VisualWorld.reload(script, worldProfile());
        script.server("arm: the player flies again", (server, context) -> fly(context.serverPlayer(server)))
                .serverUntil("arm: the scene is loaded and the controller ready after the rejoin", (server, context) ->
                        SceneItemCensus.isFullyLoaded(server.overworld(), censusBox) && sceneReady(server, context),
                        SCENE_READY_TIMEOUT_TICKS)
                .serverUntil("arm: all four arms resolved their saved points", (server, context) -> {
                    ServerLevel level = server.overworld();
                    for (BlockPos arm : List.of(ARM_A, ARM_B, ARM_C, ARM_D)) {
                        ArmBlockEntity be = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, at(context, arm));
                        if (be == null || !resolved(be, 1, 1))
                            return false;
                    }
                    return true;
                }, PLACE_TIMEOUT_TICKS)
                .server("arm: CHECK 92 the arms kept their points and modes", (server, context) -> {
                    ServerLevel level = server.overworld();
                    assertPoints(level, context, "A after rejoin", ARM_A,
                            List.of(new PointExpectation(DEPOT_A, "create:depot", Mode.TAKE,
                                    AllArmInteractionPointTypes.DepotPoint.class)),
                            List.of(new PointExpectation(INPUT_A, "wareworks:warehouse_input", Mode.DEPOSIT,
                                    WarehouseInputArmPoint.class)));
                    assertPoints(level, context, "B after rejoin", ARM_B,
                            List.of(new PointExpectation(OUTPUT, "wareworks:warehouse_output", Mode.TAKE,
                                    DeliveryStationArmPoint.class)),
                            List.of(new PointExpectation(DEPOT_B, "create:depot", Mode.DEPOSIT,
                                    AllArmInteractionPointTypes.DepotPoint.class)));
                    assertPoints(level, context, "C after rejoin", ARM_C,
                            List.of(new PointExpectation(TERMINAL, "wareworks:warehouse_terminal", Mode.TAKE,
                                    DeliveryStationArmPoint.class)),
                            List.of(new PointExpectation(DEPOT_C, "create:depot", Mode.DEPOSIT,
                                    AllArmInteractionPointTypes.DepotPoint.class)));
                    assertPoints(level, context, "D after rejoin", ARM_D,
                            List.of(new PointExpectation(PRODUCTION, "wareworks:warehouse_production", Mode.TAKE,
                                    DeliveryStationArmPoint.class)),
                            List.of(new PointExpectation(CRAFTER, "create:crafter", Mode.DEPOSIT,
                                    AllArmInteractionPointTypes.CrafterPoint.class)));
                    LOGGER.info(PREFIX + "arm: CHECK 92 PASS (integrated): after save, quit and rejoin all four arms "
                            + "resolved their saved points with the same types and modes");
                })
                .server("arm: census after the rejoin", (server, context) -> census(server, "after save, quit and rejoin"))
                // Arm A still feeds the input.
                .server("arm: put " + FED_COPPER + " copper ingots on depot A",
                        (server, context) -> insertAt(server, context, DEPOT_A, COPPER.toStack(FED_COPPER)))
                .serverUntil("arm: arm A fed the copper and the crane stored it",
                        (server, context) -> stored(server, context, COPPER, FED_COPPER), STORE_TIMEOUT_TICKS)
                .server("arm: census after arm A worked after the rejoin",
                        (server, context) -> census(server, "arm A fed the input after the rejoin"))
                // Arms B and C: their depots are full, so empty them like a hopper would, then give them work.
                .server("arm: empty depots B and C into the spare chest", (server, context) -> {
                    moveAll(server.overworld(), at(context, DEPOT_B), at(context, SPARE_CHEST));
                    moveAll(server.overworld(), at(context, DEPOT_C), at(context, SPARE_CHEST));
                })
                .server("arm: census after emptying the depots",
                        (server, context) -> census(server, "depots B and C emptied into the spare chest"))
                .server("arm: request iron at the output again", (server, context) -> requireAccepted(
                        controller(server.overworld(), context).request(at(context, OUTPUT), IRON,
                                OUTPUT_IRON_AFTER_REJOIN), "iron at the output after the rejoin"))
                .serverUntil("arm: arm B took the iron, arm C took the planks out of the terminal",
                        (server, context) -> {
                            ServerLevel level = server.overworld();
                            return countAt(level, at(context, DEPOT_B), Items.IRON_INGOT) == OUTPUT_IRON_AFTER_REJOIN
                                    && countAt(level, at(context, DEPOT_C), Items.OAK_PLANKS) == ORDERED_PLANKS
                                    && station(level, context, OUTPUT).bufferedItems().isEmpty()
                                    && station(level, context, TERMINAL).bufferedItems().isEmpty()
                                    && quiet(level, context);
                        }, STORE_TIMEOUT_TICKS)
                .server("arm: census after arms B and C worked after the rejoin",
                        (server, context) -> census(server, "arms B and C emptied output and terminal after the rejoin"));
        // Arm D and the crafter.
        orderPlanks(script, ORDERED_PLANKS_AFTER_REJOIN, "after the rejoin");
        script.serverUntil("arm: the order after the rejoin is complete, planks delivered, machines idle",
                        (server, context) -> {
                            ServerLevel level = server.overworld();
                            ProductionOrderState state = trackedState(level, context);
                            if (state == ProductionOrderState.TIMED_OUT || state == ProductionOrderState.CANCELLED)
                                throw new VisualTestException("the production order after the rejoin ended " + state);
                            return state == ProductionOrderState.COMPLETE
                                    && countAt(level, at(context, TERMINAL), Items.OAK_PLANKS)
                                            == ORDERED_PLANKS_AFTER_REJOIN
                                    && quiet(level, context);
                        }, PRODUCTION_TIMEOUT_TICKS)
                .server("arm: CHECK 92 the crafter loop after the rejoin", (server, context) -> {
                    checkProduction(server, context, ORDERED_PLANKS_AFTER_REJOIN, "the crafter loop after the rejoin");
                    LOGGER.info(PREFIX + "arm: CHECK 92 PASS (integrated): all four arms kept moving items after the "
                            + "rejoin, the census held throughout");
                });
    }

    // --- 91: tooltips and the selection message, in English and in German -----------------------------------------------

    private void german(VisualScript script) {
        selectHotbar(script, SLOT_ARM, "the Mechanical Arm", AllBlocks.MECHANICAL_ARM::isIn);
        script.client("arm: CHECK 91 English Shift tooltips", context -> checkTooltips(context, List.of(
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_INPUT.asStack(), "Mechanical Arms"),
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_OUTPUT.asStack(), "Mechanical Arms"),
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_TERMINAL.asStack(), "Mechanical Arms"),
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_PRODUCTION.asStack(), "Mechanical Arm")), null));
        switchLanguage(script, "de_de");
        script.client("arm: CHECK 91 German Shift tooltips", context -> checkTooltips(context, List.of(
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_INPUT.asStack(), "Mechanischen Armen"),
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_OUTPUT.asStack(), "Mechanische Arme"),
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_TERMINAL.asStack(), "Mechanische Arme"),
                new TooltipExpectation(WareworksBlocks.WAREHOUSE_PRODUCTION.asStack(), "Mechanischer Arm")),
                GERMAN_HOLD_SHIFT));
        // Item stacks are made inside the steps: the registries are not bound yet while the run is planned.
        for (TooltipShot shot : List.of(new TooltipShot("input", WareworksBlocks.WAREHOUSE_INPUT),
                new TooltipShot("output", WareworksBlocks.WAREHOUSE_OUTPUT),
                new TooltipShot("terminal", WareworksBlocks.WAREHOUSE_TERMINAL),
                new TooltipShot("production", WareworksBlocks.WAREHOUSE_PRODUCTION))) {
            script.client("arm: show the German Shift tooltip of the " + shot.name(),
                            context -> context.minecraft().setScreen(new TooltipScreen(
                                    shiftTooltip(context, shot.block().asStack()), shot.block().asStack())))
                    .until("arm: the tooltip screen is open", context -> context.minecraft().screen instanceof TooltipScreen,
                            SCREEN_TIMEOUT_TICKS)
                    .waitTicks(SETTLE_TICKS)
                    .shot("tooltip-de-" + shot.name())
                    .client("arm: close the tooltip screen", context -> context.minecraft().setScreen(null))
                    .until("arm: the tooltip screen is closed", context -> context.minecraft().screen == null,
                            SCREEN_TIMEOUT_TICKS);
        }
        rightClick(script, CLICK_INPUT_A_WEST, true);
        expectSelected(script, CLICK_INPUT_A_WEST, Mode.DEPOSIT, WarehouseInputArmPoint.class, GERMAN_DEPOSIT_TO_INPUT, 1,
                "91 (German selection message)");
        script.shotWithGui("select-input-german");
        leftClick(script, CLICK_INPUT_A_WEST);
        script.client("arm: the German selection is removed again", context -> {
            if (!selection().isEmpty())
                throw new VisualTestException("the left-click did not remove the input: " + selectionSummary());
        });
        switchLanguage(script, "en_us");
        script.client("arm: the game reads English again", context -> {
            String name = I18n.get("block.wareworks.warehouse_input");
            if (!"Warehouse Input".equals(name))
                throw new VisualTestException("the language did not switch back to English: " + name);
        });
    }

    /**
     * What the language screen does when a player picks a language ({@code LanguageSelectScreen#onDone}): select it in the
     * language manager and the options, then reload the resource packs. The options are not saved.
     */
    private void switchLanguage(VisualScript script, String code) {
        script.client("arm: switch the game language to " + code + " like the language screen does", context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.getLanguageManager().setSelected(code);
            minecraft.options.languageCode = code;
            languageReload = minecraft.reloadResourcePacks();
        });
        untilOrFail(script, "arm: the resource reload for " + code + " finished", context -> {
            CompletableFuture<Void> reload = languageReload;
            return reload != null && reload.isDone() && context.minecraft().getOverlay() == null
                    && code.equals(context.minecraft().getLanguageManager().getSelected());
        }, RELOAD_TIMEOUT_TICKS, context -> "overlay " + context.minecraft().getOverlay());
        script.waitTicks(SETTLE_TICKS);
    }

    /**
     * Reads the tooltips a player gets for the four station items and fails unless each Shift view names the arm as
     * expected. {@link Screen#getTooltipFromItem} is the call every inventory screen makes; it posts
     * {@code ItemTooltipEvent}, where Create's tooltip modifier inserts the item description. Without a physical Shift key
     * ({@code Screen#hasShiftDown} reads the GLFW key state) the modifier inserts the "Hold [Shift]" lines; the Shift view
     * swaps in {@code ItemDescription#linesOnShift()} of the description the modifier builds with the same palette.
     */
    private static void checkTooltips(VisualContext context, List<TooltipExpectation> expectations,
            @Nullable String holdShiftLine) {
        for (TooltipExpectation expectation : expectations) {
            List<Component> real = Screen.getTooltipFromItem(context.minecraft(), expectation.stack());
            String realText = join(real);
            if (holdShiftLine != null && !realText.contains(holdShiftLine))
                throw new VisualTestException("the tooltip of " + expectation.stack() + " lacks '" + holdShiftLine
                        + "': " + realText);
            String shift = join(shiftTooltip(context, expectation.stack()));
            if (!shift.contains(expectation.phrase()))
                throw new VisualTestException("the Shift tooltip of " + expectation.stack().getHoverName().getString()
                        + " does not name '" + expectation.phrase() + "': " + shift);
            LOGGER.info(PREFIX + "arm: CHECK 91 PASS: [{}] Shift tooltip of '{}' names '{}'; without Shift: '{}'; "
                            + "with Shift: '{}'", context.minecraft().getLanguageManager().getSelected(),
                    expectation.stack().getHoverName().getString(), expectation.phrase(), realText, shift);
        }
    }

    /** The tooltip a player sees while holding Shift (see {@link #checkTooltips}). */
    private static List<Component> shiftTooltip(VisualContext context, ItemStack stack) {
        List<Component> real = new ArrayList<>(Screen.getTooltipFromItem(context.minecraft(), stack));
        ItemDescription description = ItemDescription.create(stack.getItem(), FontHelper.Palette.STANDARD_CREATE);
        if (description == null)
            throw new VisualTestException("the item " + stack + " has no Create item description");
        List<Component> plain = description.lines();
        int start = 1;
        if (real.size() < start + plain.size())
            throw new VisualTestException("the tooltip of " + stack + " is shorter than its description: " + join(real));
        for (int line = 0; line < plain.size(); line++) {
            if (!plain.get(line).getString().equals(real.get(start + line).getString()))
                throw new VisualTestException("the tooltip of " + stack + " does not carry Create's description at line "
                        + (start + line) + ": " + join(real));
        }
        List<Component> shift = new ArrayList<>(real.subList(0, start));
        shift.addAll(description.linesOnShift());
        shift.addAll(real.subList(start + plain.size(), real.size()));
        return shift;
    }

    private static String join(List<Component> lines) {
        StringBuilder text = new StringBuilder();
        for (Component line : lines)
            text.append(line.getString()).append(' ');
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    // --- clicks (client thread) --------------------------------------------------------------------------------------

    /**
     * A right-click on {@code click}: optionally moves the camera there first, checks that the crosshair hits the
     * intended face of the intended block, and presses the use key once the way the mouse handler does.
     */
    private void rightClick(VisualScript script, Click click, boolean moveCamera) {
        aim(script, click, moveCamera);
        script.client("arm: right-click " + click.label(), context -> {
            overlayBeforeClick = overlay(context);
            KeyMapping.click(context.minecraft().options.keyUse.getKey());
        });
        untilOrFail(script, "arm: the client handled the right-click on " + click.label(),
                context -> clickCount(context.minecraft().options.keyUse) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
    }

    private void leftClick(VisualScript script, Click click) {
        aim(script, click, false);
        script.client("arm: left-click " + click.label(),
                context -> KeyMapping.click(context.minecraft().options.keyAttack.getKey()));
        untilOrFail(script, "arm: the client handled the left-click on " + click.label(),
                context -> clickCount(context.minecraft().options.keyAttack) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
        script.waitTicks(SETTLE_TICKS);
    }

    private void aim(VisualScript script, Click click, boolean moveCamera) {
        if (moveCamera)
            script.camera(click.label(), context -> clickView(context, click));
        untilOrFail(script, "arm: the crosshair is on the " + click.face() + " face of the " + click.label(),
                context -> aimedAt(context, click), AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + describeHit(context) + " instead of the " + click.face() + " face of "
                        + at(context, click.block()));
    }

    /** Asserts the selection after a click: point, mode, point class, the new action bar text and the outline colour. */
    private void expectSelected(VisualScript script, Click click, Mode mode, Class<?> pointClass, String message,
            int selectionSize, String check) {
        script.client("arm: CHECK " + check + " " + click.label() + " is selected as " + mode, context -> {
            BlockPos pos = at(context, click.block());
            ArmInteractionPoint point = selected(pos).orElseThrow(() -> new VisualTestException(
                    "the " + click.label() + " at " + pos + " is not selected: " + selectionSummary()));
            if (point.getMode() != mode)
                throw new VisualTestException("the " + click.label() + " is selected as " + point.getMode() + ", not "
                        + mode);
            if (point.getClass() != pointClass)
                throw new VisualTestException("the " + click.label() + " is a " + point.getClass().getName() + ", not "
                        + pointClass.getName());
            Component shown = overlay(context);
            if (shown == null || shown == overlayBeforeClick)
                throw new VisualTestException("the click on the " + click.label() + " showed no new action bar message");
            if (!message.equals(shown.getString()))
                throw new VisualTestException("the action bar reads '" + shown.getString() + "', expected '" + message
                        + "'");
            if (selection().size() != selectionSize)
                throw new VisualTestException("the selection has " + selection().size() + " points, expected "
                        + selectionSize + ": " + selectionSummary());
        });
        untilOrFail(script, "arm: the outline of the " + click.label() + " is drawn",
                context -> outlineColour(at(context, click.block())).isPresent(), OUTLINE_TIMEOUT_TICKS,
                context -> "no outline in Create's outliner for " + at(context, click.block()));
        script.client("arm: CHECK " + check + " outline colour of the " + click.label(), context -> {
            int colour = outlineColour(at(context, click.block())).orElseThrow();
            if (colour != mode.getColor())
                throw new VisualTestException(String.format(Locale.ROOT,
                        "the outline of the %s is #%06X, expected #%06X", click.label(), colour, mode.getColor()));
            LOGGER.info(PREFIX + "arm: CHECK {} PASS: {} selected as {} ({}), outline #{} ({}), action bar '{}', "
                            + "selection {}", check, click.label(), mode, pointClass.getSimpleName(),
                    String.format(Locale.ROOT, "%06X", colour), mode == Mode.DEPOSIT ? "yellow" : "light blue",
                    overlay(context).getString(), selectionSummary());
        });
    }

    /** Places an arm with a real click on the ground and checks what the server arm resolved from the placement packet. */
    private void placeArm(VisualScript script, String name, Click ground, BlockPos arm, List<PointExpectation> inputs,
            List<PointExpectation> outputs) {
        rightClick(script, ground, true);
        String summary = "Mechanical Arm has " + inputs.size() + " input(s) and " + outputs.size() + " output(s).";
        script.serverUntil("arm: arm " + name + " was placed and resolved the points of its placement packet",
                        (server, context) -> {
                            ArmBlockEntity be = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(server.overworld(),
                                    at(context, arm));
                            return be != null && resolved(be, inputs.size(), outputs.size());
                        }, PLACE_TIMEOUT_TICKS)
                .server("arm: CHECK the points of arm " + name, (server, context) -> {
                    assertPoints(server.overworld(), context, name, arm, inputs, outputs);
                    LOGGER.info(PREFIX + "arm: CHECK PASS: arm {} placed by a click at {} has the points of its selection",
                            name, at(context, arm));
                });
        untilOrFail(script, "arm: the action bar shows the placement summary of arm " + name, context -> {
            Component shown = overlay(context);
            return shown != null && summary.equals(shown.getString()) && selection().isEmpty();
        }, CLICK_TIMEOUT_TICKS, context -> "the action bar reads '" + overlayText(context) + "', selection "
                + selectionSummary());
    }

    static CameraView clickView(VisualContext context, Click click) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("no client level to aim at " + click.label());
        BlockPos pos = at(context, click.block());
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        Vec3 normal = Vec3.atLowerCornerOf(click.face().getNormal());
        Vec3 through = Vec3.atLowerCornerOf(pos).add(click.local());
        BlockHitResult hit = shape.isEmpty() ? null
                : shape.clip(through.add(normal.scale(2.0)), through.subtract(normal.scale(2.0)), pos);
        if (hit == null || hit.getDirection() != click.face())
            throw new VisualTestException("the " + click.face() + " face of " + state + " at " + pos
                    + " cannot be aimed at through " + click.local());
        Vec3 aim = hit.getLocation().subtract(Vec3.atLowerCornerOf(context.origin()));
        return new CameraView(click.label(), aim.add(click.eyeOffset()), aim);
    }

    static boolean aimedAt(VisualContext context, Click click) {
        return context.minecraft().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(at(context, click.block())) && hit.getDirection() == click.face();
    }

    static String describeHit(VisualContext context) {
        HitResult hit = context.minecraft().hitResult;
        if (hit instanceof BlockHitResult block)
            return block.getType() + " " + block.getBlockPos() + " " + block.getDirection();
        return String.valueOf(hit);
    }

    // --- claws ---------------------------------------------------------------------------------------------------------

    /**
     * Freezes the game ticks the moment the arm at {@code armOffset} is about to reach the point at {@code pointOffset}
     * (before it takes or deposits anything), shoots it from each view with the GUI hidden, and unfreezes.
     */
    private void clawShot(VisualScript script, String what, BlockPos armOffset, BlockPos pointOffset,
            ArmBlockEntity.Phase phase, List<CameraView> views, String moment, String check) {
        // The server tick listener checks the claw after every server tick, so the one tick in which the claw is
        // almost there cannot slip between two polls of a step (a poll reaches the server only every other tick).
        script.server("arm: freeze the game as soon as the claw is about to reach " + what, (server, context) -> {
                    clawFrozen = false;
                    clawRequest = new ClawRequest(at(context, armOffset), at(context, pointOffset), phase, what);
                })
                .serverUntil("arm: wait until the claw is about to reach " + what + " and the game is frozen",
                        (server, context) -> clawFrozen, CLAW_TIMEOUT_TICKS)
                .until("arm: the client sees the freeze", context -> context.minecraft().level != null
                        && context.minecraft().level.tickRateManager().isFrozen(), CLICK_TIMEOUT_TICKS)
                .client("arm: CHECK " + check + " where the renderer draws the claw reaching for " + what,
                        context -> checkClawAim(context, armOffset, pointOffset, phase, what, check,
                                ON_INPUT_A.equals(pointOffset.above()) && covered(context)));
        for (CameraView view : views)
            script.shotFrom(view, moment);
        script.freeze(false);
    }

    /** Whether the client sees a solid block on top of the first warehouse input (second half of check 89). */
    private static boolean covered(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.getBlockState(at(context, ON_INPUT_A)).is(Blocks.STONE);
    }

    /**
     * Measures, on the frozen client, where Create draws the claw of the arm at {@code armOffset} that is about to reach
     * the station at {@code stationOffset}. The claw is placed with {@code ArmRenderer}'s own transforms (which
     * {@code ArmVisual} uses as well), once for the angles Create computes for the arm's target point
     * ({@code ArmInteractionPoint#getTargetAngles}, the pose the movement ends in) and once for the angles the client
     * arm holds in this frozen frame (the pose in the screenshots).
     * <ul>
     * <li>End pose: the claw's axis passes within {@value #CLAW_AXIS_TOLERANCE} of the centre of the station's top face,
     * and the point where the claw holds an item lies within {@value #CLAW_TIP_TOLERANCE} of it.</li>
     * <li>Frozen frame: of the six face centres of the station, that point is nearest to the top face centre, not to the
     * aisle-side opening or another face. While the game is frozen, {@code LevelRenderer} hands block entity renderers
     * the partial tick 1 ({@code DeltaTracker.Timer#getGameTimeDeltaPartialTick}), so the frame shows the arm's current
     * angles; the pose one tick earlier (partial tick 0) is only logged.</li>
     * <li>With a block on top of the station: the claw's grip in the end pose lies inside that block, so only the claw
     * dips into it.</li>
     * </ul>
     */
    private static void checkClawAim(VisualContext context, BlockPos armOffset, BlockPos stationOffset,
            ArmBlockEntity.Phase phase, String what, String check, boolean covered) {
        ClientLevel level = context.minecraft().level;
        BlockPos armPos = at(context, armOffset);
        BlockPos station = at(context, stationOffset);
        if (level == null || !(level.getBlockEntity(armPos) instanceof ArmBlockEntity arm))
            throw new VisualTestException("the client has no arm at " + armPos);
        ArmState state = arm(arm);
        ArmInteractionPoint target = state.target().orElseThrow(() -> new VisualTestException(
                "the client arm at " + armPos + " chases no point (phase " + state.phase() + ")"));
        if (state.phase() != phase || !target.getPos().equals(station))
            throw new VisualTestException("the client arm at " + armPos + " is " + state.phase() + " towards "
                    + target.getPos() + ", expected " + phase + " towards " + station);
        if (state.progress() < CLIENT_CLAW_MIN_PROGRESS)
            throw new VisualTestException("the client arm at " + armPos + " is only at progress " + state.progress()
                    + " in the frozen frame, expected at least " + CLIENT_CLAW_MIN_PROGRESS);
        Vec3 topCentre = Vec3.atLowerCornerOf(station).add(0.5, 1.0, 0.5);

        ArmAngleTarget angles = target.getTargetAngles(armPos, false);
        ClawPose end = ClawPose.of(armPos, Reflect.getFloat(Reflect.TARGET_BASE, angles),
                Reflect.getFloat(Reflect.TARGET_LOWER, angles), Reflect.getFloat(Reflect.TARGET_UPPER, angles),
                Reflect.getFloat(Reflect.TARGET_HEAD, angles));
        double axis = end.axisDistance(topCentre);
        double tip = end.tip().distanceTo(topCentre);
        if (axis > CLAW_AXIS_TOLERANCE || tip > CLAW_TIP_TOLERANCE)
            throw new VisualTestException(String.format(Locale.ROOT, "the claw of the arm at %s ends %.3f off the axis "
                    + "through the top face centre %s of %s and its tip %.3f away (end pose %s)", armPos, axis,
                    topCentre, what, tip, end));
        if (covered && !new AABB(station.above()).contains(end.grip()))
            throw new VisualTestException("with a block on top of " + what + " the claw's grip " + end.grip()
                    + " does not end inside that block " + station.above());

        List<String> frames = new ArrayList<>();
        for (float partialTick : new float[] { 0F, 1F }) {
            ClawPose frame = ClawPose.of(armPos, lerped(Reflect.ARM_BASE, arm, partialTick),
                    lerped(Reflect.ARM_LOWER, arm, partialTick), lerped(Reflect.ARM_UPPER, arm, partialTick),
                    lerped(Reflect.ARM_HEAD, arm, partialTick));
            Direction nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            StringBuilder distances = new StringBuilder();
            for (Direction face : Direction.values()) {
                Vec3 centre = Vec3.atCenterOf(station).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
                double distance = frame.tip().distanceTo(centre);
                distances.append(String.format(Locale.ROOT, " %s %.3f", face.getName(), distance));
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = face;
                }
            }
            if (partialTick == 1F && nearest != Direction.UP)
                throw new VisualTestException("in the frozen frame (partial tick " + partialTick + ") the claw tip of the "
                        + "arm at " + armPos + " is nearest to the " + nearest + " face of " + what + ":" + distances);
            frames.add(String.format(Locale.ROOT, "%s: tip %s, nearest face %s, distances to the face centres%s",
                    partialTick == 1F ? "frozen frame (partial tick 1)" : "one tick earlier (not asserted)",
                    format(frame.tip()), nearest.getName(), distances));
        }
        LOGGER.info(PREFIX + "arm: CHECK {} PASS: claw aim of the arm at {} towards {} at {} (client progress {}): end "
                        + "pose axis {} from the top face centre {}, tip {} away (tip {}, grip {}{}); {}", check,
                armPos.toShortString(), what, station.toShortString(), state.progress(),
                String.format(Locale.ROOT, "%.4f", axis), format(topCentre), String.format(Locale.ROOT, "%.3f", tip),
                format(end.tip()), format(end.grip()), covered ? ", inside the block on top" : "",
                String.join("; ", frames));
    }

    private static float lerped(Field field, ArmBlockEntity arm, float partialTick) {
        return ((LerpedFloat) Reflect.get(field, arm)).getValue(partialTick);
    }

    private static String format(Vec3 vec) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", vec.x, vec.y, vec.z);
    }

    /**
     * Points of an arm's claw in world coordinates, placed with {@code ArmRenderer}'s transforms for the given raw
     * angles (the values an {@link ArmBlockEntity} and an {@link ArmAngleTarget} hold; the renderer subtracts its offsets
     * before calling the transforms, exactly as here). {@code wrist} is the head joint, {@code grip} the middle between
     * the two claw grips, {@code tip} where the renderer puts an item the claw holds.
     */
    private record ClawPose(Vec3 wrist, Vec3 grip, Vec3 tip) {
        static ClawPose of(BlockPos arm, float base, float lower, float upper, float head) {
            PoseStack pose = new PoseStack();
            PoseTransformStack stack = TransformStack.of(pose);
            stack.center();
            ArmRenderer.transformBase(stack, base);
            ArmRenderer.transformLowerArm(stack, lower - 135);
            ArmRenderer.transformUpperArm(stack, upper - 90);
            ArmRenderer.transformHead(stack, head);
            Vec3 wrist = point(pose, arm);
            Vec3 gripSum = Vec3.ZERO;
            for (int flip : new int[] { 1, -1 }) {
                pose.pushPose();
                ArmRenderer.transformClawHalf(stack, false, false, flip);
                gripSum = gripSum.add(point(pose, arm));
                pose.popPose();
            }
            pose.pushPose();
            stack.rotateXDegrees(90);
            pose.translate(0, -10 / 16f, 0);
            Vec3 tip = point(pose, arm);
            pose.popPose();
            return new ClawPose(wrist, gripSum.scale(0.5), tip);
        }

        private static Vec3 point(PoseStack pose, BlockPos arm) {
            Vector3f local = pose.last().pose().transformPosition(new Vector3f());
            return Vec3.atLowerCornerOf(arm).add(local.x(), local.y(), local.z());
        }

        /** Distance of {@code point} from the line through the wrist and the grip (the claw's axis). */
        double axisDistance(Vec3 point) {
            Vec3 axis = grip.subtract(wrist).normalize();
            Vec3 offset = point.subtract(wrist);
            return offset.subtract(axis.scale(offset.dot(axis))).length();
        }

        @Override
        public String toString() {
            return "wrist " + format(wrist) + ", grip " + format(grip) + ", tip " + format(tip);
        }
    }

    /** A pending {@link #clawShot}: freeze when the arm at {@code arm} is about to reach {@code point}. */
    private record ClawRequest(BlockPos arm, BlockPos point, ArmBlockEntity.Phase phase, String what) {
    }

    /** Server thread, after a server tick: freezes the game when the requested claw is almost at its point. */
    private void checkClaw(MinecraftServer server, ServerLevel level) {
        ClawRequest request = clawRequest;
        if (request == null || !level.isLoaded(request.arm()))
            return;
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, request.arm());
        if (arm == null)
            return;
        ArmState state = arm(arm);
        Optional<ArmInteractionPoint> target = state.target();
        if (state.phase() != request.phase() || target.isEmpty() || !target.get().getPos().equals(request.point())
                || state.progress() < CLAW_FREEZE_PROGRESS)
            return;
        server.tickRateManager().setFrozen(true);
        clawRequest = null;
        clawFrozen = true;
        LOGGER.info(PREFIX + "arm: froze the game with the claw of the arm at {} at progress {} towards {} ({}), "
                + "aiming at {}", request.arm(), state.progress(), request.what(), request.phase(), target.get().getPos());
    }

    // --- production ----------------------------------------------------------------------------------------------------

    /**
     * Orders {@code amount} oak planks at the terminal screen ({@link #requestAtTerminal}) and tracks the production order
     * the click started: its states on every server tick and the most logs arm D holds at once.
     */
    private void orderPlanks(VisualScript script, int amount, String when) {
        requestAtTerminal(script, PLANKS, amount, "oak planks " + when, "90", (server, context) -> {
            ordersBeforeRequest = controller(server.overworld(), context).productionOrders().stream()
                    .map(ProductionOrder::id).toList();
        }, (server, context) -> {
            WarehouseControllerBlockEntity controller = controller(server.overworld(), context);
            Optional<ProductionOrder<ItemKey, RackPosition>> started = controller.productionOrders().stream()
                    .filter(order -> !ordersBeforeRequest.contains(order.id())).findFirst();
            if (started.isEmpty())
                return false;
            ProductionOrder<ItemKey, RackPosition> order = started.get();
            if (!order.result().equals(PLANKS) || order.resultAmount() != amount)
                throw new VisualTestException("the click started a production order for " + order.resultAmount() + " "
                        + order.result() + ", expected " + amount + " " + PLANKS);
            observedStates.clear();
            maxHeldByArmD = 0;
            trackedOrder = order.id();
            LOGGER.info(PREFIX + "arm: CHECK 90 PASS: the click in the terminal screen started production order {} for {} "
                    + "oak planks ({} runs, state {})", order.id(), amount, amount / PLANKS_PER_LOG, order.state());
            return true;
        });
    }

    /**
     * Requests {@code amount} of {@code key} in the terminal screen the way a player does: an empty hand, a right-click on
     * the terminal opens its screen, the mouse wheel over the amount field sets the amount, and a plain left click on the
     * item in the stock grid sends the request ({@code TerminalRequestPayload}, which the server hands to
     * {@code WarehouseTerminalMenu#submitRequest}). Mouse input goes through {@link ScreenInput}. {@code before} runs on
     * the server before the click, {@code accepted} must then hold on the server; Escape closes the screen.
     */
    private void requestAtTerminal(VisualScript script, ItemKey key, int amount, String what, String check,
            VisualScript.ServerAction before, VisualScript.ServerCondition accepted) {
        selectHotbar(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
        script.server("arm: before requesting " + what + " at the terminal", before);
        openTerminalScreen(script);
        untilOrFail(script, "arm: the terminal screen lists " + what, context -> stockCell(context, key) >= 0,
                SCREEN_TIMEOUT_TICKS, context -> "the terminal lists " + terminalScreen(context).visibleEntries().stream()
                        .map(line -> line.name() + " x" + line.available()).toList());
        untilOrFail(script, "arm: scroll the terminal's amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(terminalScreen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, SCREEN_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(terminalScreen(context)).getState());
        script.shot("terminal-request-" + what.replace(' ', '-'))
                .client("arm: CHECK " + check + " left-click " + what + " in the terminal's stock grid", context -> {
                    WarehouseTerminalScreen screen = terminalScreen(context);
                    int cell = stockCell(context, key);
                    ScreenInput.Point point = ScreenInput.terminalCell(screen, cell);
                    ScreenInput.click(context.minecraft(), point);
                    LOGGER.info(PREFIX + "arm: clicked {} (grid cell {} at {}) in the terminal screen with the amount "
                            + "field at x{}", what, cell, point, ScreenInput.terminalAmount(screen).getState());
                })
                .serverUntil("arm: CHECK " + check + " the server accepted the request for " + what, accepted,
                        SCREEN_TIMEOUT_TICKS);
        closeScreen(script);
    }

    /** A right-click with an empty hand on the terminal opens its screen; waits until the stock list arrived. */
    private void openTerminalScreen(VisualScript script) {
        rightClick(script, CLICK_TERMINAL, true);
        untilOrFail(script, "arm: the terminal screen is open and has its stock list",
                context -> context.minecraft().screen instanceof WarehouseTerminalScreen screen && screen.hasStock(),
                SCREEN_TIMEOUT_TICKS, context -> "the screen is " + context.minecraft().screen);
    }

    /** Closes the open screen with the Escape key. */
    private static void closeScreen(VisualScript script) {
        script.client("arm: press Escape to close the screen",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("arm: the screen is closed", context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS);
    }

    private static WarehouseTerminalScreen terminalScreen(VisualContext context) {
        if (context.minecraft().screen instanceof WarehouseTerminalScreen screen)
            return screen;
        throw new VisualTestException("the terminal screen is not open: " + context.minecraft().screen);
    }

    /** The visible grid cell showing {@code key}, or -1. */
    private static int stockCell(VisualContext context, ItemKey key) {
        List<StockLine<ItemKey>> visible = terminalScreen(context).visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++)
            if (visible.get(cell).key().equals(key))
                return cell;
        return -1;
    }

    /** CHECK 90: the order went through "waiting for the result" to "complete", one log at a time, nothing lost. */
    private void checkProduction(MinecraftServer server, VisualContext context, int planks, String step) {
        ServerLevel level = server.overworld();
        List<ProductionOrderState> states = List.copyOf(observedStates);
        int waiting = states.indexOf(ProductionOrderState.WAITING_FOR_RESULT);
        int complete = states.indexOf(ProductionOrderState.COMPLETE);
        if (waiting < 0 || complete < 0 || waiting > complete || complete != states.size() - 1)
            throw new VisualTestException(step + ": the order states were " + states
                    + ", expected WAITING_FOR_RESULT before a final COMPLETE");
        if (maxHeldByArmD != 1)
            throw new VisualTestException(step + ": arm D carried up to " + maxHeldByArmD
                    + " logs at once, expected exactly one");
        int logs = planks / PLANKS_PER_LOG;
        expected = SceneItemCensus.plus(SceneItemCensus.plus(expected, LOG, -logs), PLANKS, planks);
        census(server, step);
        List<ItemEntity> loose = level.getEntitiesOfClass(ItemEntity.class, censusBox);
        if (!loose.isEmpty())
            throw new VisualTestException(step + ": items lie in the world: " + loose);
        LOGGER.info(PREFIX + "arm: CHECK 90 PASS: {}: order states {}, arm D carried at most {} log at a time, {} logs "
                + "became {} planks, no item entity in the scene", step, states, maxHeldByArmD, logs, planks);
    }

    private void installListener(VisualContext context) {
        if (listenerInstalled)
            return;
        listenerInstalled = true;
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, this::onServerTick);
    }

    /** Server thread, every tick: the tracked order's state transitions and the most logs arm D ever held. */
    private void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null)
            return;
        checkClaw(event.getServer(), level);
        UUID id = trackedOrder;
        BlockPos base = origin;
        if (id == null || base == null || !level.isLoaded(base))
            return;
        if (level.getBlockEntity(base.relative(AISLE.getOpposite())) instanceof WarehouseControllerBlockEntity controller) {
            controller.productionOrders().stream().filter(order -> order.id().equals(id)).findFirst().ifPresent(order -> {
                if (observedStates.isEmpty() || observedStates.getLast() != order.state()) {
                    observedStates.add(order.state());
                    LOGGER.info(PREFIX + "arm: production order {} is now {} (game time {})", id, order.state(),
                            level.getGameTime());
                }
            });
        }
        if (level.getBlockEntity(base.offset(ARM_D)) instanceof ArmBlockEntity armD) {
            int held = arm(armD).held().getCount();
            if (held > maxHeldByArmD)
                maxHeldByArmD = held;
        }
    }

    @Nullable
    private ProductionOrderState trackedState(ServerLevel level, VisualContext context) {
        UUID id = trackedOrder;
        return controller(level, context).productionOrders().stream().filter(order -> order.id().equals(id))
                .map(ProductionOrder::state).findFirst().orElse(null);
    }

    @Nullable
    private static ProductionOrderState screenOrderState(VisualContext context) {
        if (!(context.minecraft().screen instanceof WarehouseTerminalScreen terminal))
            throw new VisualTestException("the terminal screen closed: " + context.minecraft().screen);
        List<ProductionScreenState.OrderView> orders = terminal.productionOrders();
        if (orders.isEmpty())
            return null;
        ProductionOrderState state = orders.getLast().state();
        if (state == ProductionOrderState.TIMED_OUT || state == ProductionOrderState.CANCELLED)
            throw new VisualTestException("the production order ended " + state);
        return state;
    }

    // --- build (server thread) -----------------------------------------------------------------------------------------

    private void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!level.isLoaded(new BlockPos(0, level.getMinBuildHeight(), 0)))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(dock);
        origin = dock;
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(CLEAR_MIN_X, -2, CLEAR_MIN_Z),
                dock.offset(CLEAR_MAX_X, CLEAR_HEIGHT, CLEAR_MAX_Z));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(CLEAR_MIN_X, 0, CLEAR_MIN_Z),
                dock.offset(CLEAR_MAX_X, CLEAR_HEIGHT, CLEAR_MAX_Z)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        BlockState motorUp = AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP);
        level.setBlockAndUpdate(dock.below(), motorUp);
        level.setBlockAndUpdate(dock.offset(ARM_MOTOR), motorUp);
        level.setBlockAndUpdate(dock.offset(ARM_D_MOTOR), motorUp);
        level.setBlockAndUpdate(dock.offset(CRAFTER_MOTOR),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.WEST));
    }

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        for (BlockPos motor : List.of(ARM_MOTOR, ARM_D_MOTOR, CRAFTER_MOTOR))
            motor(level, dock.offset(motor)).generatedSpeed.setValue(0);

        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.offset(CONTROLLER),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        requireRack(layout, dock, INPUT_A_RACK, INPUT_A);
        requireRack(layout, dock, OUTPUT_RACK, OUTPUT);
        requireRack(layout, dock, TERMINAL_RACK, TERMINAL);
        requireRack(layout, dock, PRODUCTION_RACK, PRODUCTION);
        requireRack(layout, dock, INPUT_B_RACK, INPUT_B);
        level.setBlockAndUpdate(dock.offset(INPUT_A), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, towardsAisle(layout, INPUT_A_RACK)));
        level.setBlockAndUpdate(dock.offset(INPUT_B), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, towardsAisle(layout, INPUT_B_RACK)));
        level.setBlockAndUpdate(dock.offset(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, towardsAisle(layout, OUTPUT_RACK)));
        level.setBlockAndUpdate(dock.offset(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, towardsAisle(layout, TERMINAL_RACK)));
        level.setBlockAndUpdate(dock.offset(PRODUCTION), WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                .setValue(WarehouseProductionBlock.FACING, towardsAisle(layout, PRODUCTION_RACK)));

        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                BlockPos rack = layout.rackPos(RackPosition.of(x, 0, side));
                level.setBlockAndUpdate(rack.relative(outward),
                        Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                        .setValue(WarehouseInterfaceBlock.FACING, outward));
            }
        }
        if (!dock.offset(INTERFACE_PROBE).equals(layout.rackPos(RackPosition.of(STORAGE_FIRST_POSITION, 0, Side.RIGHT))))
            throw new VisualTestException("the interface probe offset is not the first storage interface");
        insertAt(server, context, LOG_CHEST, LOG.toStack(LOGS_IN_STOCK));
        insertAt(server, context, DIAMOND_CHEST, DIAMOND.toStack(DIAMONDS_IN_STOCK));

        WarehouseProductionBlockEntity station = WareworksBlockEntityTypes.WAREHOUSE_PRODUCTION.getNullable(level,
                dock.offset(PRODUCTION));
        if (station == null || !station.setPatternEntry(0, 0, LOG, 1)
                || !station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANKS, PLANKS_PER_LOG))
            throw new VisualTestException("the production pattern 1 oak log -> 4 oak planks could not be written");

        for (BlockPos depot : List.of(DEPOT_A, DEPOT_B, DEPOT_C))
            level.setBlockAndUpdate(dock.offset(depot), AllBlocks.DEPOT.getDefaultState());
        BlockState verticalCog = AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y);
        level.setBlockAndUpdate(dock.offset(ARM_COG), verticalCog);
        level.setBlockAndUpdate(dock.offset(ARM_D_COG), verticalCog);
        level.setBlockAndUpdate(dock.offset(CRAFTER), crafterState(Direction.EAST, Direction.SOUTH));
        level.setBlockAndUpdate(dock.offset(CRAFTER_COG), AllBlocks.COGWHEEL.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
        level.setBlockAndUpdate(dock.offset(SPARE_CHEST), Blocks.CHEST.defaultBlockState());
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.offset(CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        int storage = (RAILS - STORAGE_FIRST_POSITION + 1) * Side.values().length;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == storage
                && controller.inputStations().size() == 2
                // The terminal counts as an output as well (ADR-018).
                && controller.outputStations().size() == 2 && controller.productionStations().size() == 1
                && crane.isControllerLinked() && crane.aisleLength() == RAILS
                && controller.producibleKeys().contains(PLANKS);
    }

    private static void holdArm(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        fly(player);
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, AllBlocks.MECHANICAL_ARM.asStack());
        player.connection.send(new ClientboundSetCarriedItemPacket(0));
        player.inventoryMenu.broadcastChanges();
    }

    private static void fly(ServerPlayer player) {
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
    }

    private static void powerMachines(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        motor(level, at(context, ARM_MOTOR)).generatedSpeed.setValue(ARM_RPM);
        motor(level, at(context, ARM_D_MOTOR)).generatedSpeed.setValue(ARM_RPM);
        motor(level, at(context, CRAFTER_MOTOR)).generatedSpeed.setValue(CRAFTER_RPM);
    }

    private static boolean machinesTurn(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        for (BlockPos arm : List.of(ARM_A, ARM_B, ARM_C, ARM_D))
            if (armAt(level, at(context, arm)).getSpeed() == 0f)
                return false;
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                at(context, CRAFTER));
        return crafter != null && crafter.getSpeed() != 0f;
    }

    /** The crafter block state whose arrow points at {@code target} (looked up through Create, as the showcase does). */
    private static BlockState crafterState(Direction facing, Direction target) {
        for (Pointing pointing : Pointing.values()) {
            BlockState state = AllBlocks.MECHANICAL_CRAFTER.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing)
                    .setValue(MechanicalCrafterBlock.POINTING, pointing);
            if (MechanicalCrafterBlock.getTargetDirection(state) == target)
                return state;
        }
        throw new VisualTestException("no crafter pointing makes a crafter facing " + facing + " target " + target);
    }

    private static void requireRack(AisleLayout layout, BlockPos dock, RackPosition rack, BlockPos offset) {
        if (!layout.rackPos(rack).equals(dock.offset(offset)))
            throw new VisualTestException("offset " + offset + " is not rack position " + rack);
    }

    private static Direction towardsAisle(AisleLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    // --- items and census (server thread) ----------------------------------------------------------------------------

    private void census(MinecraftServer server, String step) {
        SceneItemCensus.assertEquals(server.overworld(), censusBox, expected, "arm: " + step);
    }

    /** Inserts through the block's item capability, as a funnel or hopper would; the census expectation follows. */
    private void insertAt(MinecraftServer server, VisualContext context, BlockPos offset, ItemStack stack) {
        ServerLevel level = server.overworld();
        BlockPos pos = at(context, offset);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
        if (!expected.isEmpty())
            expected = SceneItemCensus.plus(expected, ItemKey.of(stack), stack.getCount());
    }

    /** Moves everything from one inventory into another through their item capabilities (census neutral). */
    private static void moveAll(ServerLevel level, BlockPos from, BlockPos to) {
        IItemHandler source = level.getCapability(Capabilities.ItemHandler.BLOCK, from, null);
        IItemHandler target = level.getCapability(Capabilities.ItemHandler.BLOCK, to, null);
        if (source == null || target == null)
            throw new VisualTestException("no item handler at " + from + " or " + to);
        for (int slot = 0; slot < source.getSlots(); slot++) {
            ItemStack taken = source.extractItem(slot, source.getStackInSlot(slot).getCount(), false);
            ItemStack rest = ItemHandlerHelper.insertItem(target, taken, false);
            if (!rest.isEmpty())
                throw new VisualTestException("the inventory at " + to + " refused " + rest);
        }
    }

    private static int countAt(ServerLevel level, BlockPos pos, Item item) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return 0;
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    /** The depot is empty, the aisle holds {@code amount} of {@code key} and nothing is on its way. */
    private static boolean stored(MinecraftServer server, VisualContext context, ItemKey key, int amount) {
        ServerLevel level = server.overworld();
        return countAt(level, at(context, DEPOT_A), key.toStack().getItem()) == 0
                && controller(level, context).countOf(key) == amount && quiet(level, context);
    }

    /**
     * No item is in motion: the crane is idle and empty, no arm holds anything, the crafter is idle and empty, and the
     * two inputs and the production station hold nothing.
     */
    private static boolean quiet(ServerLevel level, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        if (crane == null || crane.craneState().phase() != CranePhase.IDLE || crane.currentJob().isPresent()
                || !crane.heldItems().isEmpty())
            return false;
        for (BlockPos arm : List.of(ARM_A, ARM_B, ARM_C, ARM_D)) {
            ArmBlockEntity be = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, at(context, arm));
            if (be != null && !arm(be).held().isEmpty())
                return false;
        }
        MechanicalCrafterBlockEntity crafter = AllBlockEntityTypes.MECHANICAL_CRAFTER.getNullable(level,
                at(context, CRAFTER));
        if (crafter == null || crafter.craftingItemPresent()
                || !"IDLE".equals(((Enum<?>) Reflect.get(Reflect.CRAFTER_PHASE, crafter)).name()))
            return false;
        for (BlockPos station : List.of(INPUT_A, INPUT_B, PRODUCTION))
            if (!station(level, context, station).bufferedItems().isEmpty())
                return false;
        return true;
    }

    // --- arms (server thread) ------------------------------------------------------------------------------------------

    private record PointExpectation(BlockPos offset, String typeId, Mode mode, Class<?> pointClass) {
    }

    private static boolean resolved(ArmBlockEntity arm, int inputs, int outputs) {
        ArmState state = arm(arm);
        return !state.updatePending() && state.inputs().size() == inputs && state.outputs().size() == outputs;
    }

    private static void assertPoints(ServerLevel level, VisualContext context, String name, BlockPos armOffset,
            List<PointExpectation> inputs, List<PointExpectation> outputs) {
        ArmBlockEntity arm = armAt(level, at(context, armOffset));
        ArmState state = arm(arm);
        assertPointList(context, name + " inputs (take)", state.inputs(), inputs);
        assertPointList(context, name + " outputs (deposit)", state.outputs(), outputs);
        ListTag saved = arm.saveWithoutMetadata(level.registryAccess()).getList("InteractionPoints", Tag.TAG_COMPOUND);
        LOGGER.info(PREFIX + "arm: arm {} at {}: inputs {}, outputs {}, saved InteractionPoints {}", name,
                at(context, armOffset), describe(state.inputs()), describe(state.outputs()), saved);
    }

    private static void assertPointList(VisualContext context, String what, List<ArmInteractionPoint> actual,
            List<PointExpectation> expectations) {
        if (actual.size() != expectations.size())
            throw new VisualTestException("arm " + what + ": " + describe(actual) + ", expected " + expectations);
        for (PointExpectation expectation : expectations) {
            BlockPos pos = at(context, expectation.offset());
            ArmInteractionPoint point = actual.stream().filter(candidate -> candidate.getPos().equals(pos)).findFirst()
                    .orElseThrow(() -> new VisualTestException("arm " + what + " lacks a point at " + pos + ": "
                            + describe(actual)));
            String typeId = String.valueOf(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(point.getType()));
            if (!expectation.typeId().equals(typeId) || point.getMode() != expectation.mode()
                    || point.getClass() != expectation.pointClass())
                throw new VisualTestException("arm " + what + " point at " + pos + " is " + typeId + " "
                        + point.getMode() + " " + point.getClass().getSimpleName() + ", expected " + expectation);
        }
    }

    private static String describe(List<ArmInteractionPoint> points) {
        List<String> parts = new ArrayList<>();
        for (ArmInteractionPoint point : points)
            parts.add(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(point.getType()) + "@"
                    + point.getPos().toShortString() + ":" + point.getMode() + ":" + point.getClass().getSimpleName());
        return parts.toString();
    }

    /** The state Create keeps package-private in an arm (dev tooling reads it by reflection). */
    private record ArmState(List<ArmInteractionPoint> inputs, List<ArmInteractionPoint> outputs, ItemStack held,
            ArmBlockEntity.Phase phase, float progress, int index, boolean updatePending) {
        Optional<ArmInteractionPoint> target() {
            List<ArmInteractionPoint> list = phase == ArmBlockEntity.Phase.MOVE_TO_INPUT ? inputs
                    : phase == ArmBlockEntity.Phase.MOVE_TO_OUTPUT ? outputs : List.of();
            return index >= 0 && index < list.size() ? Optional.of(list.get(index)) : Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static ArmState arm(ArmBlockEntity arm) {
        return new ArmState(List.copyOf((List<ArmInteractionPoint>) Reflect.get(Reflect.ARM_INPUTS, arm)),
                List.copyOf((List<ArmInteractionPoint>) Reflect.get(Reflect.ARM_OUTPUTS, arm)),
                (ItemStack) Reflect.get(Reflect.ARM_HELD, arm), (ArmBlockEntity.Phase) Reflect.get(Reflect.ARM_PHASE, arm),
                (Float) Reflect.get(Reflect.ARM_PROGRESS, arm), (Integer) Reflect.get(Reflect.ARM_INDEX, arm),
                (Boolean) Reflect.get(Reflect.ARM_UPDATE, arm));
    }

    private static ArmBlockEntity armAt(ServerLevel level, BlockPos pos) {
        ArmBlockEntity arm = AllBlockEntityTypes.MECHANICAL_ARM.getNullable(level, pos);
        if (arm == null)
            throw new VisualTestException("no mechanical arm at " + pos);
        return arm;
    }

    // --- lookups -------------------------------------------------------------------------------------------------------

    private static BlockPos at(VisualContext context, BlockPos offset) {
        return context.origin().offset(offset);
    }

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                at(context, CONTROLLER));
        if (controller == null)
            throw new VisualTestException("the warehouse controller is missing");
        return controller;
    }

    private static WarehouseStationBlockEntity station(ServerLevel level, VisualContext context, BlockPos offset) {
        if (level.getBlockEntity(at(context, offset)) instanceof WarehouseStationBlockEntity station)
            return station;
        throw new VisualTestException("no warehouse station at " + at(context, offset));
    }

    private static FilteringBehaviour outputFilter(ServerLevel level, VisualContext context) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                at(context, OUTPUT));
        FilteringBehaviour filter = output == null ? null : BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output or its request filter is missing");
        return filter;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static void requireAccepted(RequestResult result, String what) {
        if (!result.isAccepted())
            throw new VisualTestException("the request for " + what + " was refused: "
                    + result.rejection().map(Enum::name).orElse("?"));
        LOGGER.info(PREFIX + "arm: request for {} accepted (granted {}, producing {})", what, result.granted(),
                result.producing());
    }

    // --- client state --------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<ArmInteractionPoint> selection() {
        return (List<ArmInteractionPoint>) Reflect.get(Reflect.SELECTION, null);
    }

    static Optional<ArmInteractionPoint> selected(BlockPos pos) {
        return selection().stream().filter(point -> point.getPos().equals(pos)).findFirst();
    }

    static String selectionSummary() {
        List<String> parts = new ArrayList<>();
        for (ArmInteractionPoint point : selection())
            parts.add(point.getPos().toShortString() + ":" + point.getMode());
        return parts.toString();
    }

    /** The action bar message the player sees, or {@code null} when none is shown. */
    @Nullable
    static Component overlay(VisualContext context) {
        Gui gui = context.minecraft().gui;
        int time = (Integer) Reflect.get(Reflect.OVERLAY_TIME, gui);
        return time > 0 ? (Component) Reflect.get(Reflect.OVERLAY, gui) : null;
    }

    static String overlayText(VisualContext context) {
        Component shown = overlay(context);
        return shown == null ? "" : shown.getString();
    }

    static int clickCount(KeyMapping key) {
        return (Integer) Reflect.get(Reflect.CLICK_COUNT, key);
    }

    /** The RGB colour of the selection outline Create's outliner draws for the selected point at {@code pos}. */
    static Optional<Integer> outlineColour(BlockPos pos) {
        Optional<ArmInteractionPoint> point = selected(pos);
        if (point.isEmpty())
            return Optional.empty();
        Outliner.OutlineEntry entry = Outliner.getInstance().getOutlines().get(point.get());
        if (entry == null)
            return Optional.empty();
        Color colour = (Color) Reflect.get(Reflect.OUTLINE_RGB, entry.getOutline().getParams());
        return Optional.of(colour.getRGB() & 0xFFFFFF);
    }

    // --- small types ---------------------------------------------------------------------------------------------------

    /**
     * A click target.
     *
     * @param label     name in step descriptions
     * @param block     block offset from the dock
     * @param face      face the crosshair must hit
     * @param local     block-local point the aim ray runs through (its coordinate along the face normal is ignored)
     * @param eyeOffset where the eye stands, relative to the aimed point on the face
     */
    record Click(String label, BlockPos block, Direction face, Vec3 local, Vec3 eyeOffset) {
        Click {
            Objects.requireNonNull(label, "label");
        }
    }

    private record TooltipExpectation(ItemStack stack, String phrase) {
    }

    private record TooltipShot(String name, BlockEntry<?> block) {
    }

    /**
     * Draws one item tooltip on an otherwise empty, non-pausing screen, for a shot of what the player reads. A tooltip
     * taller than the screen is scaled down to fit and pinned to the top left corner; vanilla's positioner would move
     * its top above the screen edge.
     */
    private static final class TooltipScreen extends Screen {
        private static final int MARGIN = 8;
        /** Tooltip text line height plus the gap after the title line and the frame, as {@code GuiGraphics} draws it. */
        private static final int LINE_HEIGHT = 10;
        private static final int FRAME_HEIGHT = 12;
        private final List<Component> lines;

        TooltipScreen(List<Component> lines, ItemStack stack) {
            super(Component.empty());
            this.lines = List.copyOf(lines);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            List<FormattedCharSequence> sequences = lines.stream().map(Component::getVisualOrderText).toList();
            float needed = sequences.size() * LINE_HEIGHT + FRAME_HEIGHT;
            float scale = Math.min(1.0F, (height - 2.0F * MARGIN) / needed);
            int corner = Math.round(MARGIN / scale);
            graphics.pose().pushPose();
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.renderTooltip(font, sequences,
                    (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) -> new Vector2i(x, y), corner, corner);
            graphics.pose().popPose();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** Fails with {@code failure} instead of the harness's generic step timeout. */
    static void untilOrFail(VisualScript script, String description, Predicate<VisualContext> condition,
            int timeoutTicks, Function<VisualContext, String> failure) {
        int[] polls = { 0 };
        script.until(description, context -> {
            if (condition.test(context))
                return true;
            if (++polls[0] >= timeoutTicks)
                throw new VisualTestException(description + ": " + failure.apply(context));
            return false;
        }, timeoutTicks + 10);
    }

    /** Reflection into Create and Minecraft internals that the checks read (dev tooling only). */
    static final class Reflect {
        static final Field SELECTION = field(ArmInteractionPointHandler.class, "currentSelection");
        static final Field ARM_INPUTS = field(ArmBlockEntity.class, "inputs");
        static final Field ARM_OUTPUTS = field(ArmBlockEntity.class, "outputs");
        static final Field ARM_HELD = field(ArmBlockEntity.class, "heldItem");
        static final Field ARM_PHASE = field(ArmBlockEntity.class, "phase");
        static final Field ARM_PROGRESS = field(ArmBlockEntity.class, "chasedPointProgress");
        static final Field ARM_INDEX = field(ArmBlockEntity.class, "chasedPointIndex");
        static final Field ARM_UPDATE = field(ArmBlockEntity.class, "updateInteractionPoints");
        static final Field CRAFTER_PHASE = field(MechanicalCrafterBlockEntity.class, "phase");
        static final Field OVERLAY = field(Gui.class, "overlayMessageString");
        static final Field OVERLAY_TIME = field(Gui.class, "overlayMessageTime");
        static final Field CLICK_COUNT = field(KeyMapping.class, "clickCount");
        static final Field OUTLINE_RGB = field(Outline.OutlineParams.class, "rgb");
        static final Field ARM_BASE = field(ArmBlockEntity.class, "baseAngle");
        static final Field ARM_LOWER = field(ArmBlockEntity.class, "lowerArmAngle");
        static final Field ARM_UPPER = field(ArmBlockEntity.class, "upperArmAngle");
        static final Field ARM_HEAD = field(ArmBlockEntity.class, "headAngle");
        static final Field TARGET_BASE = field(ArmAngleTarget.class, "baseAngle");
        static final Field TARGET_LOWER = field(ArmAngleTarget.class, "lowerArmAngle");
        static final Field TARGET_UPPER = field(ArmAngleTarget.class, "upperArmAngle");
        static final Field TARGET_HEAD = field(ArmAngleTarget.class, "headAngle");

        private Reflect() {
        }

        private static Field field(Class<?> owner, String name) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalStateException("cannot reach " + owner.getName() + "#" + name, error);
            }
        }

        static float getFloat(Field field, Object owner) {
            return (Float) get(field, owner);
        }

        static Object get(Field field, @Nullable Object owner) {
            try {
                return field.get(owner);
            } catch (IllegalAccessException error) {
                throw new VisualTestException("cannot read " + field + ": " + error);
            }
        }
    }
}
