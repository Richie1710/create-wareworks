package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.tterrag.registrate.util.entry.BlockEntry;

import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.crane.head.HeldItems;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.handtruck.HandTruckCarry;
import dev.wareworks.content.handtruck.HandTruckItem;
import dev.wareworks.content.handtruck.HandTruckPallet;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.BayColumn;
import dev.wareworks.content.storage.BayUpgrades;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.storage.BayUpgrade;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario {@code bayupgrade} (ADR-083, ADR-084): rack bay upgrade kits and pallets on the hand truck, played by a
 * survival player with real clicks in a powered aisle, in English and German.
 * <p>
 * <b>The scene.</b> A short aisle running east on a creative motor: the input and the bays on the right wall, the
 * output (with a lever behind it and its request preset to one stack of cobblestone), the terminal and two more bays on
 * the left. Bay A is a wooden bay with {@value #A_STOCK} cobblestone, a List Filter of cobblestone and priority
 * {@value #A_PRIORITY}, the only cobblestone location; B is two empty wooden bays stacked; C is a full andesite bay of
 * diorite; D an empty brass bay filtered to diorite; E an empty wooden bay; F a copper fluid bay with
 * {@value #WATER_BUCKETS} buckets of water. The player wears Engineer's Goggles and carries three andesite kits, one
 * fluid kit and an empty hand truck.
 * <p>
 * <b>The story</b> (first pass). Every world click is {@link KeyMapping#click} on the use or attack key with the
 * crosshair checked on the intended face (and, where it matters, inside or outside the bay's filter slot sphere) and
 * the server's confirmation of the last camera teleport awaited ({@code requester-make}'s pattern); walking is the
 * movement keys held down; screens go through {@link ScreenInput}:
 * <ol>
 * <li>A kit on A, beside its filter slot: A becomes andesite with the <b>same block entity</b>, goods, filter, priority,
 * address and index; nothing drops; the kit is used up. A second kit clicked right into A's filter slot is refused
 * ("fits") and does not become the filter. Goggles of A in English and German, read off the client block entity.</li>
 * <li>Cobblestone fed through the input: the crane stores it into A (frozen {@code crane-stores}); the player flips the
 * lever and the crane brings one stack to the output (frozen {@code crane-retrieves}).</li>
 * <li>A kit on B's upper bay: refused by the column rule, in English and German.</li>
 * <li>B upgraded bottom-up: both andesite.</li>
 * <li>The fluid kit on F: brass, the water kept (fluid census exact).</li>
 * <li>In creative mode one attack click breaks C: exactly one pallet with the whole load.</li>
 * <li>Back in survival, the empty truck lifts the pallet with a click on the entity; shots in first and third person
 * and of the tooltip in English and German.</li>
 * <li>A walk of about five blocks along the aisle with the forward and sprint keys held: no sprint, slow.</li>
 * <li>A real save, quit and rejoin while carrying: the count above 99 survives.</li>
 * <li>Refusals: into A ("not empty") and into E ("too small"), in English and German.</li>
 * <li>Into D with the click right in D's filter slot: D holds the whole pallet, its filter is unchanged, the truck is
 * empty and the warehouse indexes it; goggles of D in English and German.</li>
 * </ol>
 * The second pass (Flywheel off) re-shoots A, then breaks D again in creative mode, lifts that pallet, shoots the
 * truck in third person and sets the pallet down on the floor beyond the aisle.
 * <p>
 * <b>Items.</b> {@link SceneItemCensus} (the player included) is exact before and after every act: a pallet and a
 * loaded truck count as their contents. The declared changes are the kits a survival upgrade uses up and the
 * cobblestone fed into the input.
 */
public final class BayUpgradeVisualScenario implements VisualScenario {
    public static final String NAME = "bayupgrade";

    private static final String WORLD_FOLDER = "wareworks_visual_bayupgrade";
    /** Two crane trips, a rejoin, five language round trips and a second pass. */
    private static final long RUN_TIMEOUT_MILLIS = 12L * 60L * 1000L;

    // --- the warehouse (offsets from the dock; the aisle runs east, so the right rack wall is +z) ---------------------
    private static final Direction AISLE = Direction.EAST;
    private static final int RAILS = 6;
    private static final int CRANE_RPM = 64;
    private static final RackPosition INPUT = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition RACK_A = RackPosition.of(2, 0, Side.RIGHT);
    private static final RackPosition RACK_B_LOW = RackPosition.of(3, 0, Side.RIGHT);
    private static final RackPosition RACK_B_HIGH = RackPosition.of(3, 1, Side.RIGHT);
    private static final RackPosition RACK_F = RackPosition.of(4, 0, Side.RIGHT);
    private static final RackPosition RACK_E = RackPosition.of(5, 0, Side.RIGHT);
    private static final RackPosition RACK_C = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition OUTPUT = RackPosition.of(4, 0, Side.LEFT);
    private static final RackPosition TERMINAL = RackPosition.of(3, 0, Side.LEFT);
    private static final RackPosition RACK_D = RackPosition.of(6, 0, Side.LEFT);
    /** A, both B, C, D, E and the fluid bay F: a fluid bay is a storage location too. */
    private static final int STORAGE_LOCATIONS = 7;

    private static final BlockPos DOCK = BlockPos.ZERO;
    private static final BlockPos CONTROLLER = new BlockPos(-1, 0, 0);
    private static final BlockPos BAY_A = new BlockPos(2, 0, 1);
    private static final BlockPos BAY_B_LOW = new BlockPos(3, 0, 1);
    private static final BlockPos BAY_B_HIGH = new BlockPos(3, 1, 1);
    private static final BlockPos BAY_F = new BlockPos(4, 0, 1);
    private static final BlockPos BAY_E = new BlockPos(5, 0, 1);
    private static final BlockPos BAY_C = new BlockPos(1, 0, -1);
    private static final BlockPos OUTPUT_POS = new BlockPos(4, 0, -1);
    private static final BlockPos BAY_D = new BlockPos(6, 0, -1);
    /** On the ground behind the output, outside the left rack wall. */
    private static final BlockPos LEVER = new BlockPos(4, 0, -2);
    /** The ground block beyond the aisle the second pass sets its pallet down on. */
    private static final BlockPos FLOOR = new BlockPos(11, -1, 0);

    // --- the goods -------------------------------------------------------------------------------------------------
    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey DIORITE = ItemKey.of(Items.DIORITE);
    private static final int A_STOCK = 3000;
    private static final int A_PRIORITY = 3;
    private static final int FED = 64;
    private static final int RETRIEVED = 64;
    private static final int WATER_BUCKETS = 40;
    private static final int WATER = WATER_BUCKETS * 1000;

    // --- where a player stands (eye heights relative to the dock's lower corner) -------------------------------------
    /** On a rail: the rail is 3 px high. */
    private static final double RAIL_EYE = WarehouseRailBlock.HEIGHT_PIXELS / 16.0 + 1.62;
    /** On the ground beside the aisle. */
    private static final double GROUND_EYE = 1.62;
    private static final double AISLE_Z = 0.5;
    /** How far along the aisle the eye stands from the point it clicks, so the view is not straight down. */
    private static final double EYE_SHIFT = 0.8;
    /** The filter slot's centre: 8 px across, 5 px up the aisle face ({@code StorageFilterValueBox}). */
    private static final double SLOT_HEIGHT = 5.0 / 16.0;
    /** A point on the aisle face well beside the filter slot: 5 px across, 12 px up. */
    private static final double BESIDE_ACROSS = 5.0 / 16.0;
    private static final double BESIDE_HEIGHT = 12.0 / 16.0;

    // --- clicks ----------------------------------------------------------------------------------------------------
    private static final Aim AIM_A = Aim.beside("bay A", BAY_A, -EYE_SHIFT);
    private static final Aim AIM_A_SLOT = Aim.slot("bay A's filter slot", BAY_A, -EYE_SHIFT);
    private static final Aim AIM_B_LOW = Aim.beside("bay B below", BAY_B_LOW, -EYE_SHIFT);
    private static final Aim AIM_B_HIGH = Aim.beside("bay B above", BAY_B_HIGH, -EYE_SHIFT);
    private static final Aim AIM_F = Aim.beside("fluid bay F", BAY_F, -EYE_SHIFT);
    private static final Aim AIM_E = Aim.beside("bay E", BAY_E, -EYE_SHIFT);
    private static final Aim AIM_C = Aim.beside("bay C", BAY_C, EYE_SHIFT);
    private static final Aim AIM_D = Aim.beside("bay D", BAY_D, -EYE_SHIFT);
    private static final Aim AIM_D_SLOT = Aim.slot("bay D's filter slot", BAY_D, -EYE_SHIFT);

    // --- camera views ----------------------------------------------------------------------------------------------
    // The world shots stand on the ground behind a rack wall: once the crane has worked it parks in the aisle, where
    // its mast would fill any view along it.
    private static final CameraView AT_A = CameraView.of("a", 3.9, GROUND_EYE, 3.7, 2.5, 0.5, 1.5);
    private static final CameraView AT_B = CameraView.of("b", 4.9, GROUND_EYE, 3.9, 3.5, 1.0, 1.5);
    private static final CameraView AT_F = CameraView.of("f", 5.9, GROUND_EYE, 3.7, 4.5, 0.5, 1.5);
    private static final CameraView AT_C = CameraView.of("c", 2.6, GROUND_EYE, -3.4, 1.5, 0.3, -0.5);
    private static final CameraView AT_D = CameraView.of("d", 7.6, GROUND_EYE, -3.4, 6.5, 0.4, -0.5);
    /** Where the empty truck lifts a pallet from: on the rails beside it, looking down at its deck. */
    private static final CameraView LIFT_C = CameraView.of("lift-c", 2.6, RAIL_EYE, 0.55, 1.5, 0.3, -0.5);
    private static final CameraView LIFT_D = CameraView.of("lift-d", 6.8, RAIL_EYE, 0.55, 6.5, 0.3, -0.5);
    /** Down the aisle from its far end, standing on the last rail: the crane between the rack walls. */
    private static final CameraView ALONG_AISLE = CameraView.of("aisle", 6.6, RAIL_EYE, 0.5, 2.0, 0.9, 0.5);
    /** On the open ground south of the aisle, looking east: room for the third person camera behind the player. */
    private static final CameraView RUNWAY = CameraView.of("runway", 9.5, GROUND_EYE, 5.5, 19.5, GROUND_EYE - 2.0,
            5.5);
    /** Standing in the aisle where C was, looking east along it: the start of the walk. */
    private static final CameraView WALK_START = CameraView.of("walk", 1.5, RAIL_EYE, 0.5, 14.5, RAIL_EYE, 0.5);
    /** On the ground behind the output, looking down at the lever. */
    private static final CameraView AT_LEVER = CameraView.of("lever", 4.5, GROUND_EYE, -3.6, 4.5, 0.2, -1.5);
    /** On the ground beyond the aisle, looking at the floor block the pallet is set down on. */
    private static final CameraView AT_FLOOR = CameraView.of("floor", 9.5, GROUND_EYE, 0.5, 11.5, 0.0, 0.5);
    private static final CameraView FLOOR_SHOT = CameraView.of("floor", 9.2, GROUND_EYE, 2.6, 11.5, 0.3, 0.5);

    // --- the walk --------------------------------------------------------------------------------------------------
    private static final double WALK_SHOT_X = 3.8;
    private static final double WALK_END_X = 6.0;
    /** Plain walking is about 0.216 blocks per tick and sprinting 0.28; the loaded truck halves the walk. */
    private static final double WALK_MAX_SPEED = 0.16;
    private static final double WALK_MIN_SPEED = 0.05;

    // --- the area --------------------------------------------------------------------------------------------------
    private static final BlockPos CLEAR_FROM = new BlockPos(-4, 0, -6);
    private static final BlockPos CLEAR_TO = new BlockPos(22, 6, 8);
    private static final BlockPos CENSUS_FROM = new BlockPos(-3, -1, -5);
    private static final BlockPos CENSUS_TO = new BlockPos(16, 3, 7);

    // --- hotbar ----------------------------------------------------------------------------------------------------
    private static final int SLOT_KITS = 0;
    private static final int SLOT_FLUID_KIT = 1;
    private static final int SLOT_TRUCK = 2;
    private static final int SLOT_EMPTY = 8;
    private static final int ANDESITE_KITS = 3;

    // --- timing ----------------------------------------------------------------------------------------------------
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int AIM_TIMEOUT_TICKS = 60;
    private static final int CLICK_TIMEOUT_TICKS = 40;
    private static final int RESULT_TIMEOUT_TICKS = 200;
    private static final int CRANE_TIMEOUT_TICKS = 1200;
    private static final int WALK_TIMEOUT_TICKS = 200;
    private static final int LANGUAGE_RELOAD_TIMEOUT_TICKS = 600;
    private static final int LANGUAGE_SETTLE_TICKS = 20;
    private static final int CAMERA_TYPE_TICKS = 8;
    private static final int SETTLE_TICKS = 6;
    /** A frozen carry shows the crane between two rack positions: this far from where it started and from its goal. */
    private static final double MIN_TRAVELLED = 0.2;
    private static final double MIN_REMAINING = 0.1;
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    /** The position a server waits for the client to confirm after a teleport, {@code null} once it has. */
    private static final Field AWAITING_TELEPORT = awaitingTeleportField();

    // --- state shared by the steps -----------------------------------------------------------------------------------
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** Server thread: what the census must find right now. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    private volatile Map<FluidKey, Long> fluidsBefore = Map.of();
    /** Server thread: what C holds at the start, i.e. the pallet's count. */
    private volatile int palletCount;
    /** Server thread: A before the kit, so the check after it can compare identity, filter and address. */
    @Nullable
    private volatile BlockEntity aBefore;
    @Nullable
    private volatile ItemStack aFilterBefore;
    @Nullable
    private volatile AisleAssignment aAssignmentBefore;
    @Nullable
    private volatile BlockEntity fBefore;
    @Nullable
    private volatile ItemStack dFilterBefore;
    /** Client thread: the walk. */
    @Nullable
    private Vec3 walkStart;
    private long walkStartTime;
    private boolean walkSprinted;
    /** Client thread: the English lines, so the German ones can prove they are a translation. */
    private final Map<String, String> englishLines = new HashMap<>();
    private final List<String> passed = new ArrayList<>();
    private final VisualLanguage language = new VisualLanguage(LANGUAGE_RELOAD_TIMEOUT_TICKS, LANGUAGE_SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /** A survival player with the vanilla reach: kits are used up and the truck's penalty applies in survival only. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.SURVIVAL);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("bayupgrade: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "bayupgrade run"))
                .server("bayupgrade: clear the area, build the aisle and the bays", this::build)
                .server("bayupgrade: the player wears goggles and carries kits and an empty hand truck",
                        this::equipPlayer)
                .serverUntil("bayupgrade: wait until the warehouse has indexed every bay and station",
                        this::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .until("bayupgrade: the client sees the bays with their contents and the truck in hand", context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && clientRack(context, BAY_A) != null
                            && clientRack(context, BAY_A).storedCount() == A_STOCK && clientRack(context, BAY_C) != null
                            && clientRack(context, BAY_C).storedCount() > 0
                            && player.getInventory().getItem(SLOT_TRUCK).is(WareworksItems.HAND_TRUCK.get());
                }, RESULT_TIMEOUT_TICKS)
                .server("bayupgrade: the baseline census", (server, context) -> {
                    expected = census(server, context);
                    LOGGER.info(PREFIX + "bayupgrade: baseline census {}", SceneItemCensus.describe(expected));
                })
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            upgradeA(script);
            craneUsesA(script);
            columnRefuses(script);
            bottomUp(script);
            fluidBay(script);
            breakC(script);
            liftThePallet(script);
            walkToD(script);
            rejoinWhileCarrying(script);
            bayRefusals(script);
            intoD(script);
            return;
        }
        secondPass(script);
        script.client("bayupgrade: every check passed", context -> LOGGER.info(PREFIX
                + "bayupgrade: ALL CHECKS PASSED in English and German ({})", String.join("; ", passed)));
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        LocalPlayer player = context.minecraft().player;
        if (player != null) {
            ItemStack held = player.getMainHandItem();
            HandTruckPallet pallet = HandTruckItem.palletLoad(held);
            status.append("held=").append(held.getCount()).append('x').append(held.getHoverName().getString())
                    .append(" pallet=").append(pallet == null ? "none" : pallet.count())
                    .append(" carrying=").append(HandTruckCarry.isCarrying(player))
                    .append(" sprinting=").append(player.isSprinting());
        }
        ClientLevel level = context.minecraft().level;
        if (level != null) {
            status.append(" a=").append(level.getBlockState(at(context, BAY_A)).getBlock().getDescriptionId()
                    .replace("block.wareworks.", ""));
            if (level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane) {
                CranePose pose = crane.craneState().pose();
                status.append(String.format(Locale.ROOT, " crane=%s posX=%.2f posY=%.2f arm=%.2f held=%d",
                        crane.craneState().phase(), pose.x(), pose.y(), pose.arm(), crane.goggleInfo().heldCount()));
            }
        }
        status.append(" actionBar='").append(ArmVisualScenario.overlayText(context)).append('\'');
        status.append(" lang=").append(context.minecraft().getLanguageManager().getSelected());
        return status + GoggleShots.describeHover(context);
    }

    // --- 1: a kit on A ---------------------------------------------------------------------------------------------

    private void upgradeA(VisualScript script) {
        worldShots(script, "upgrade-before", AT_A);
        selectHotbar(script, SLOT_KITS, "the andesite kits", WareworksItems.RACK_UPGRADE_KIT_ANDESITE::isIn);
        script.server("bayupgrade: remember A and take the census before the kit", (server, context) -> {
            ServerLevel level = server.overworld();
            RackBayBlockEntity bay = rack(level, context, BAY_A);
            aBefore = bay;
            aFilterBefore = bay.storeFilter();
            aAssignmentBefore = WarehouseRegistry.observeStorage(level, bay.getBlockPos(), bay).assignment();
            if (aAssignmentBefore.state() != AisleAssignment.State.ASSIGNED)
                throw new VisualTestException("A is not part of the aisle before the kit: " + aAssignmentBefore);
            assertCensus(server, context, "before the kit on A");
        });
        click(script, AIM_A, false, KeyKind.USE);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the client sees A as an andesite bay with its goods",
                context -> {
                    RackBayBlockEntity bay = clientRack(context, BAY_A);
                    return context.minecraft().level.getBlockState(at(context, BAY_A))
                            .is(WareworksBlocks.RACK_BAY_ANDESITE.get()) && bay != null
                            && bay.storedCount() == A_STOCK;
                }, RESULT_TIMEOUT_TICKS, context -> "the client sees " + context.minecraft().level.getBlockState(
                        at(context, BAY_A)) + " holding " + describe(clientRack(context, BAY_A)));
        script.serverUntil("bayupgrade: the controller has re-read A", (server, context) ->
                controller(server.overworld(), context).pendingSnapshotCount() == 0, RESULT_TIMEOUT_TICKS);
        script.server("bayupgrade: CHECK A is andesite with the same block entity, goods, filter, priority and address",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    BlockPos pos = at(context, BAY_A);
                    if (!level.getBlockState(pos).is(WareworksBlocks.RACK_BAY_ANDESITE.get()))
                        throw new VisualTestException("A is " + level.getBlockState(pos) + " after the kit");
                    RackBayBlockEntity bay = rack(level, context, BAY_A);
                    if (bay != aBefore)
                        throw new VisualTestException("A has a new block entity after the kit: " + bay + " instead of "
                                + aBefore);
                    if (!bay.storedKey().map(COBBLESTONE::equals).orElse(false) || bay.storedCount() != A_STOCK)
                        throw new VisualTestException("A holds " + describe(bay) + " after the kit");
                    if (!ItemStack.matches(bay.storeFilter(), Objects.requireNonNull(aFilterBefore, "filter")))
                        throw new VisualTestException("A's filter changed to " + bay.storeFilter());
                    if (bay.storePriority() != A_PRIORITY)
                        throw new VisualTestException("A's priority is " + bay.storePriority());
                    AisleAssignment assignment = WarehouseRegistry.observeStorage(level, pos, bay).assignment();
                    if (!assignment.equals(aAssignmentBefore))
                        throw new VisualTestException("A's address changed from " + aAssignmentBefore + " to "
                                + assignment);
                    long capacity = bay.capacityFor(COBBLESTONE);
                    if (capacity <= A_STOCK + FED || bay.overloaded())
                        throw new VisualTestException("A holds " + capacity + " cobblestone after the kit, overloaded "
                                + bay.overloaded());
                    long indexed = controller(level, context).countOf(COBBLESTONE);
                    if (indexed != A_STOCK)
                        throw new VisualTestException("the controller indexes " + indexed + " cobblestone");
                    assertNothingDropped(level, "the kit on A");
                    requireKits(context.serverPlayer(server), ANDESITE_KITS - 1);
                    expected = SceneItemCensus.plus(expected, kitKey(WareworksItems.RACK_UPGRADE_KIT_ANDESITE.get()),
                            -1);
                    assertCensus(server, context, "the kit on A");
                    pass("A wood -> andesite in place: same block entity, " + A_STOCK + " cobblestone, filter, "
                            + "priority " + A_PRIORITY + ", address " + assignment.address().orElseThrow()
                            + ", capacity " + capacity + ", index " + indexed + ", nothing dropped, kit used up");
                });
        script.client("bayupgrade: CHECK no line for a successful upgrade", context -> {
            if (!ArmVisualScenario.overlayText(context).isEmpty())
                throw new VisualTestException("the upgrade showed '" + ArmVisualScenario.overlayText(context) + "'");
        });
        worldShots(script, "upgrade-after", AT_A);

        // A second kit, clicked right into A's filter slot: A is andesite now, so the kit does not fit — and it must
        // not have become A's filter on the way.
        click(script, AIM_A_SLOT, true, KeyKind.USE);
        actionBar(script, "the kit that does not fit", context -> WareworksLang.translateDirect(
                WareworksLang.BAY_UPGRADE_FITS, BayUpgrades.sourceBlock(BayUpgrade.ANDESITE_RACK).getName()));
        guiShot(script, "refused-fits");
        script.server("bayupgrade: CHECK the kit in the filter slot changed nothing", (server, context) -> {
            RackBayBlockEntity bay = rack(server.overworld(), context, BAY_A);
            if (!ItemStack.matches(bay.storeFilter(), Objects.requireNonNull(aFilterBefore, "filter")))
                throw new VisualTestException("the kit became A's filter: " + bay.storeFilter());
            if (!bay.getBlockState().is(WareworksBlocks.RACK_BAY_ANDESITE.get()))
                throw new VisualTestException("A is " + bay.getBlockState());
            requireKits(context.serverPlayer(server), ANDESITE_KITS - 1);
            assertCensus(server, context, "the kit in A's filter slot");
            pass("a kit clicked into A's filter slot was refused as not fitting and did not become the filter");
        });

        // A's aisle face from the aisle, well above its filter slot, so Create draws the goggle tooltip.
        goggles(script, "goggles-a", AIM_A.view("goggles-a"), BAY_A, context -> {
            RackBayBlockEntity bay = clientRack(context, BAY_A);
            return bay != null && bay.storedCount() == A_STOCK && bay.storePriority() == A_PRIORITY
                    && bay.getBlockState().is(WareworksBlocks.RACK_BAY_ANDESITE.get());
        }, context -> {
            List<String> lines = GoggleShots.lines(context, at(context, BAY_A));
            RackBayBlockEntity bay = Objects.requireNonNull(clientRack(context, BAY_A), "client A");
            String header = WareworksLang.rackBay(WareworksBlocks.RACK_BAY_ANDESITE.get().getName()).component()
                    .getString();
            String contents = WareworksLang.bayContents(Items.COBBLESTONE, A_STOCK, bay.capacityFor(COBBLESTONE))
                    .component().getString();
            GoggleShots.requireLine(lines, header);
            GoggleShots.requireLine(lines, contents);
            GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_STORAGE_PRIORITY, A_PRIORITY));
            requireTranslated(context, "goggles-a header", header);
            pass("[" + selectedLanguage(context) + "] the client's goggles of A: " + lines);
        });
    }

    // --- 2: the crane stores into A and takes a stack out of it -----------------------------------------------------

    private void craneUsesA(VisualScript script) {
        script.server("bayupgrade: feed " + FED + " cobblestone through the input", (server, context) -> {
            insert(server.overworld(), layout(context.origin()).rackPos(INPUT), COBBLESTONE.toStack(FED));
            expected = SceneItemCensus.plus(expected, COBBLESTONE, FED);
            assertCensus(server, context, "the cobblestone fed into the input");
        });
        script.serverUntil("bayupgrade: freeze while the crane carries the cobblestone to A",
                        (server, context) -> freezeWhileCarrying(server, context, INPUT, RACK_A), CRANE_TIMEOUT_TICKS)
                .until("bayupgrade: the client sees the frozen carry", BayUpgradeVisualScenario::clientFrozen,
                        RESULT_TIMEOUT_TICKS)
                .server("bayupgrade: CHECK the crane carries the cobblestone to A", (server, context) -> {
                    assertCensus(server, context, "the crane carries the cobblestone to A");
                    pass("the crane carries " + held(crane(server.overworld(), context), COBBLESTONE)
                            + " cobblestone to the upgraded A");
                });
        worldShots(script, "crane-stores", ALONG_AISLE);
        script.freeze(false)
                .serverUntil("bayupgrade: the crane stored everything into A and is idle", (server, context) ->
                        rack(server.overworld(), context, BAY_A).storedCount() == A_STOCK + FED
                                && craneIdle(server, context) && inputEmpty(server, context), CRANE_TIMEOUT_TICKS)
                .server("bayupgrade: CHECK A holds the stored stack", (server, context) -> {
                    assertCensus(server, context, "the crane stored into A");
                    pass("the crane stored " + FED + " cobblestone into A: " + (A_STOCK + FED));
                });

        // The lever: one stack of cobblestone, preset at the output.
        selectHotbar(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
        script.camera(AT_LEVER);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the crosshair is on the lever",
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(at(context, LEVER)),
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context));
        pressKey(script, "the lever", KeyKind.USE);
        script.serverUntil("bayupgrade: freeze while the crane brings one stack to the output",
                        (server, context) -> freezeWhileCarrying(server, context, RACK_A, OUTPUT), CRANE_TIMEOUT_TICKS)
                .until("bayupgrade: the client sees the frozen retrieval", BayUpgradeVisualScenario::clientFrozen,
                        RESULT_TIMEOUT_TICKS)
                .server("bayupgrade: CHECK the lever pulled one stack out of A", (server, context) -> {
                    ServerLevel level = server.overworld();
                    if (!level.getBlockState(at(context, LEVER)).getValue(LeverBlock.POWERED))
                        throw new VisualTestException("the lever is not on");
                    assertCensus(server, context, "the crane brings one stack to the output");
                    pass("the lever's request: the crane carries " + held(crane(level, context), COBBLESTONE)
                            + " cobblestone out of A to the output");
                });
        worldShots(script, "crane-retrieves", ALONG_AISLE);
        script.freeze(false)
                .serverUntil("bayupgrade: the stack reached the output and the crane is idle", (server, context) -> {
                    ServerLevel level = server.overworld();
                    return outputHolds(level, context) == RETRIEVED && craneIdle(server, context)
                            && rack(level, context, BAY_A).storedCount() == A_STOCK + FED - RETRIEVED;
                }, CRANE_TIMEOUT_TICKS)
                .server("bayupgrade: CHECK the output holds one stack and A the rest", (server, context) -> {
                    assertCensus(server, context, "the retrieval to the output");
                    pass("one stack retrieved to the output; A holds " + (A_STOCK + FED - RETRIEVED));
                });
    }

    // --- 3 and 4: the column rule, then bottom-up -------------------------------------------------------------------

    private void columnRefuses(VisualScript script) {
        selectHotbar(script, SLOT_KITS, "the andesite kits", WareworksItems.RACK_UPGRADE_KIT_ANDESITE::isIn);
        for (String code : List.of(ENGLISH, GERMAN)) {
            if (code.equals(GERMAN))
                language.switchTo(script, "bayupgrade: ", GERMAN);
            click(script, AIM_B_HIGH, false, KeyKind.USE);
            actionBar(script, "the column rule (" + code + ")",
                    context -> WareworksLang.translateDirect(WareworksLang.BAY_UPGRADE_COLUMN));
            script.client("bayupgrade: CHECK the column line is translated (" + code + ")",
                    context -> requireTranslated(context, "column", ArmVisualScenario.overlayText(context)));
            guiShot(script, code.equals(ENGLISH) ? "refused-column" : "refused-column-de");
            script.server("bayupgrade: CHECK the column refused the upper bay (" + code + ")", (server, context) -> {
                ServerLevel level = server.overworld();
                if (!level.getBlockState(at(context, BAY_B_HIGH)).is(WareworksBlocks.RACK_BAY_WOOD.get())
                        || !level.getBlockState(at(context, BAY_B_LOW)).is(WareworksBlocks.RACK_BAY_WOOD.get()))
                    throw new VisualTestException("the B column changed: " + level.getBlockState(at(context,
                            BAY_B_LOW)) + " / " + level.getBlockState(at(context, BAY_B_HIGH)));
                requireKits(context.serverPlayer(server), ANDESITE_KITS - 1);
                assertCensus(server, context, "the refused kit on B's upper bay");
                pass("[" + code + "] the upper B bay refused with the column line, the kit kept");
            });
            if (code.equals(GERMAN))
                language.switchTo(script, "bayupgrade: ", ENGLISH);
        }
    }

    private void bottomUp(VisualScript script) {
        selectHotbar(script, SLOT_KITS, "the andesite kits", WareworksItems.RACK_UPGRADE_KIT_ANDESITE::isIn);
        upgradeAndCheck(script, AIM_B_LOW, BAY_B_LOW, ANDESITE_KITS - 2);
        upgradeAndCheck(script, AIM_B_HIGH, BAY_B_HIGH, ANDESITE_KITS - 3);
        script.server("bayupgrade: CHECK both B bays are andesite and neither is overloaded", (server, context) -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : List.of(BAY_B_LOW, BAY_B_HIGH)) {
                BlockState state = level.getBlockState(at(context, pos));
                if (!state.is(WareworksBlocks.RACK_BAY_ANDESITE.get()) || BayColumn.isOverloaded(state))
                    throw new VisualTestException("B at " + pos + " is " + state);
            }
            pass("B upgraded bottom-up: both andesite, none overloaded, all three kits used");
        });
        worldShots(script, "bottom-up", AT_B);
    }

    private void upgradeAndCheck(VisualScript script, Aim aim, BlockPos bay, int kitsLeft) {
        click(script, aim, false, KeyKind.USE);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the client sees " + aim.label() + " as andesite",
                context -> context.minecraft().level.getBlockState(at(context, bay))
                        .is(WareworksBlocks.RACK_BAY_ANDESITE.get()), RESULT_TIMEOUT_TICKS,
                context -> "the client sees " + context.minecraft().level.getBlockState(at(context, bay))
                        + ", action bar '" + ArmVisualScenario.overlayText(context) + "'");
        script.server("bayupgrade: CHECK " + aim.label() + " is andesite", (server, context) -> {
            requireKits(context.serverPlayer(server), kitsLeft);
            expected = SceneItemCensus.plus(expected, kitKey(WareworksItems.RACK_UPGRADE_KIT_ANDESITE.get()), -1);
            assertNothingDropped(server.overworld(), "the kit on " + aim.label());
            assertCensus(server, context, "the kit on " + aim.label());
        });
    }

    // --- 5: the fluid kit -------------------------------------------------------------------------------------------

    private void fluidBay(VisualScript script) {
        selectHotbar(script, SLOT_FLUID_KIT, "the fluid kit", WareworksItems.FLUID_BAY_UPGRADE_KIT_BRASS::isIn);
        script.server("bayupgrade: remember F and its water", (server, context) -> {
            ServerLevel level = server.overworld();
            fBefore = fluidBay(level, context);
            fluidsBefore = SceneFluidCensus.take(level, censusBox);
            assertCensus(server, context, "before the fluid kit");
        });
        click(script, AIM_F, false, KeyKind.USE);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the client sees F as a brass fluid bay",
                context -> context.minecraft().level.getBlockState(at(context, BAY_F))
                        .is(WareworksBlocks.FLUID_BAY_BRASS.get()), RESULT_TIMEOUT_TICKS,
                context -> "the client sees " + context.minecraft().level.getBlockState(at(context, BAY_F)));
        script.server("bayupgrade: CHECK F is brass with the same block entity and all its water", (server, context) -> {
            ServerLevel level = server.overworld();
            FluidBayBlockEntity bay = fluidBay(level, context);
            if (bay != fBefore)
                throw new VisualTestException("F has a new block entity after the kit");
            if (bay.millibuckets() != WATER || !bay.storedFluid().map(FluidKey::getFluid).map(Fluids.WATER::equals)
                    .orElse(false))
                throw new VisualTestException("F holds " + bay.millibuckets() + " mB of " + bay.storedFluid());
            SceneFluidCensus.assertEquals(level, censusBox, fluidsBefore, "bayupgrade: the fluid kit on F");
            if (!context.serverPlayer(server).getInventory().getItem(SLOT_FLUID_KIT).isEmpty())
                throw new VisualTestException("the fluid kit was not used up");
            expected = SceneItemCensus.plus(expected, kitKey(WareworksItems.FLUID_BAY_UPGRADE_KIT_BRASS.get()), -1);
            assertCensus(server, context, "the fluid kit on F");
            pass("F copper -> brass in place with " + WATER_BUCKETS + " buckets of water, capacity " + bay.capacity()
                    + " mB, fluid census exact");
        });
        worldShots(script, "fluid-after", AT_F);
    }

    // --- 6: a broken bay leaves a pallet ----------------------------------------------------------------------------

    private void breakC(VisualScript script) {
        breakBay(script, AIM_C, BAY_C, "C");
        worldShots(script, "pallet-on-floor", AT_C);
    }

    /** In creative mode, one attack click breaks the bay; then back to survival. Exactly one pallet with everything. */
    private void breakBay(VisualScript script, Aim aim, BlockPos bay, String name) {
        selectHotbar(script, SLOT_EMPTY, "an empty hand", ItemStack::isEmpty);
        gameMode(script, GameType.CREATIVE);
        click(script, aim, null, KeyKind.ATTACK);
        script.serverUntil("bayupgrade: " + name + " is broken", (server, context) ->
                server.overworld().getBlockState(at(context, bay)).isAir(), RESULT_TIMEOUT_TICKS);
        gameMode(script, GameType.SURVIVAL);
        script.server("bayupgrade: CHECK breaking " + name + " left exactly one pallet with its whole load",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    List<PalletEntity> pallets = level.getEntitiesOfClass(PalletEntity.class, censusBox);
                    if (pallets.size() != 1)
                        throw new VisualTestException(pallets.size() + " pallets after breaking " + name);
                    PalletEntity pallet = pallets.getFirst();
                    if (!pallet.carriedKey().map(DIORITE::equals).orElse(false)
                            || pallet.carriedCount() != palletCount)
                        throw new VisualTestException("the pallet carries " + pallet.carriedCount() + " x "
                                + pallet.carriedKey());
                    if (!pallet.blockPosition().equals(at(context, bay)))
                        throw new VisualTestException("the pallet lies at " + pallet.blockPosition());
                    if (!level.getEntitiesOfClass(ItemEntity.class, censusBox).isEmpty())
                        throw new VisualTestException("items were dropped: "
                                + level.getEntitiesOfClass(ItemEntity.class, censusBox));
                    assertCensus(server, context, "breaking " + name);
                    pass("breaking " + name + " in creative mode left one pallet of " + palletCount + " diorite");
                });
        script.until("bayupgrade: the client sees the pallet", context -> clientPallet(context) != null,
                RESULT_TIMEOUT_TICKS);
    }

    // --- 7: the empty truck lifts the pallet ------------------------------------------------------------------------

    private void liftThePallet(VisualScript script) {
        lift(script, LIFT_C, "C");
        guiShot(script, "truck-first-person");
        thirdPerson(script, "truck-third-person", CameraType.THIRD_PERSON_FRONT);
        thirdPerson(script, "truck-third-back", CameraType.THIRD_PERSON_BACK);
        tooltip(script, ENGLISH, "truck-tooltip");
        language.switchTo(script, "bayupgrade: ", GERMAN);
        tooltip(script, GERMAN, "truck-tooltip-de");
        language.switchTo(script, "bayupgrade: ", ENGLISH);
    }

    private void lift(VisualScript script, CameraView view, String name) {
        selectHotbar(script, SLOT_TRUCK, "the empty hand truck",
                stack -> stack.is(WareworksItems.HAND_TRUCK.get()) && !HandTruckItem.isLoaded(stack));
        script.client("bayupgrade: first person", context -> context.minecraft().options
                .setCameraType(CameraType.FIRST_PERSON)).camera(view);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the crosshair is on the pallet of " + name,
                context -> clientPallet(context) != null && context.minecraft().hitResult instanceof EntityHitResult hit
                        && hit.getEntity() instanceof PalletEntity, AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + context.minecraft().hitResult);
        pressKey(script, "the pallet of " + name, KeyKind.USE);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the client's truck carries the pallet", context -> {
            LocalPlayer player = context.minecraft().player;
            HandTruckPallet pallet = player == null ? null : HandTruckItem.palletLoad(player.getMainHandItem());
            return pallet != null && pallet.count() == palletCount && clientPallet(context) == null;
        }, RESULT_TIMEOUT_TICKS, context -> "the client holds " + context.minecraft().player.getMainHandItem()
                + ", action bar '" + ArmVisualScenario.overlayText(context) + "'");
        script.waitTicks(SETTLE_TICKS);
        script.server("bayupgrade: CHECK the truck carries the whole pallet of " + name, (server, context) -> {
            ServerLevel level = server.overworld();
            ServerPlayer player = context.serverPlayer(server);
            HandTruckPallet pallet = HandTruckItem.palletLoad(player.getMainHandItem());
            if (pallet == null || !pallet.item().equals(DIORITE) || pallet.count() != palletCount)
                throw new VisualTestException("the server's truck carries " + pallet);
            if (!level.getEntitiesOfClass(PalletEntity.class, censusBox).isEmpty())
                throw new VisualTestException("a pallet is still lying in the scene");
            if (!HandTruckCarry.isCarrying(player))
                throw new VisualTestException("the carry penalty is not stamped");
            assertCensus(server, context, "lifting the pallet of " + name);
            pass("the empty truck lifted the pallet of " + name + ": " + pallet.count() + " diorite on the truck, "
                    + "pallet gone, penalty stamped");
        });
    }

    /** From the runway, in third person, then back to first person. */
    private static void thirdPerson(VisualScript script, String label, CameraType type) {
        script.camera(RUNWAY)
                .client("bayupgrade: camera " + type, context -> context.minecraft().options.setCameraType(type))
                .waitTicks(CAMERA_TYPE_TICKS)
                .shot(label)
                .client("bayupgrade: back to first person",
                        context -> context.minecraft().options.setCameraType(CameraType.FIRST_PERSON))
                .waitTicks(CAMERA_TYPE_TICKS);
    }

    /** Opens the inventory with its key, rests the mouse on the truck and checks its name and pallet line. */
    private void tooltip(VisualScript script, String code, String label) {
        script.client("bayupgrade: open the inventory with its key (" + code + ")",
                        context -> KeyMapping.click(context.minecraft().options.keyInventory.getKey()))
                .until("bayupgrade: the inventory screen is open",
                        context -> context.minecraft().screen instanceof InventoryScreen, RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("bayupgrade: CHECK rest the mouse on the truck and read its tooltip (" + code + ")",
                        context -> {
                            Minecraft minecraft = context.minecraft();
                            InventoryScreen screen = (InventoryScreen) minecraft.screen;
                            ScreenInput.hover(minecraft, ScreenInput.hotbarSlot(screen, SLOT_TRUCK));
                            LocalPlayer player = Objects.requireNonNull(minecraft.player, "player");
                            ItemStack truck = player.getInventory().getItem(SLOT_TRUCK);
                            List<String> lines = truck.getTooltipLines(Item.TooltipContext.of(minecraft.level), player,
                                    TooltipFlag.Default.NORMAL).stream().map(Component::getString).toList();
                            String name = WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_LOADED_PALLET,
                                    DIORITE.toStack().getHoverName()).getString();
                            String count = WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_CARRYING_PALLET,
                                    palletCount).getString();
                            String slowed = WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_SLOWED).getString();
                            if (lines.isEmpty() || !lines.getFirst().equals(name) || !lines.contains(count)
                                    || !lines.contains(slowed))
                                throw new VisualTestException("[" + code + "] the truck's tooltip is " + lines
                                        + ", expected '" + name + "', '" + count + "' and '" + slowed + "'");
                            requireTranslated(context, "truck name", name);
                            requireTranslated(context, "truck count", count);
                            pass("[" + code + "] truck tooltip " + lines);
                        })
                .waitTicks(SETTLE_TICKS)
                .shot(label)
                .client("bayupgrade: close the inventory",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("bayupgrade: the inventory is closed", context -> context.minecraft().screen == null,
                        RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    // --- 8: the walk ------------------------------------------------------------------------------------------------

    /** Forward and sprint held along the aisle from C towards D: no sprint ever starts and the walk is slow. */
    private void walkToD(VisualScript script) {
        script.client("bayupgrade: first person", context -> context.minecraft().options
                        .setCameraType(CameraType.FIRST_PERSON))
                .camera(WALK_START)
                .client("bayupgrade: hold the forward and the sprint key", context -> {
                    LocalPlayer player = Objects.requireNonNull(context.minecraft().player, "player");
                    walkStart = player.position();
                    walkStartTime = context.minecraft().level.getGameTime();
                    walkSprinted = false;
                    context.minecraft().options.keyUp.setDown(true);
                    context.minecraft().options.keySprint.setDown(true);
                })
                .until("bayupgrade: walk to the middle of the aisle", context -> walked(context, WALK_SHOT_X),
                        WALK_TIMEOUT_TICKS)
                .client("bayupgrade: clear the chat", BayUpgradeVisualScenario::clearChat)
                .shotWithGui("carrying")
                .until("bayupgrade: walk on to D", context -> walked(context, WALK_END_X), WALK_TIMEOUT_TICKS)
                .client("bayupgrade: CHECK release the keys and judge the walk", context -> {
                    context.minecraft().options.keyUp.setDown(false);
                    context.minecraft().options.keySprint.setDown(false);
                    LocalPlayer player = Objects.requireNonNull(context.minecraft().player, "player");
                    Vec3 start = Objects.requireNonNull(walkStart, "walk start");
                    long ticks = context.minecraft().level.getGameTime() - walkStartTime;
                    double distance = Math.hypot(player.getX() - start.x, player.getZ() - start.z);
                    double speed = distance / Math.max(1, ticks);
                    walkSprinted |= player.isSprinting();
                    if (walkSprinted)
                        throw new VisualTestException("a sprint started while carrying a pallet");
                    if (speed > WALK_MAX_SPEED || speed < WALK_MIN_SPEED)
                        throw new VisualTestException(String.format(Locale.ROOT, "the walk with the pallet made %.3f "
                                + "blocks per tick, expected %.2f to %.2f", speed, WALK_MIN_SPEED, WALK_MAX_SPEED));
                    pass(String.format(Locale.ROOT, "walked %.1f blocks with the pallet in %d ticks (%.3f b/t), "
                            + "the sprint key held and no sprint", distance, ticks, speed));
                })
                .waitTicks(SETTLE_TICKS);
    }

    private boolean walked(VisualContext context, double toX) {
        LocalPlayer player = Objects.requireNonNull(context.minecraft().player, "player");
        walkSprinted |= player.isSprinting();
        return player.getX() >= context.origin().getX() + toX;
    }

    // --- 9: a save, quit and rejoin while carrying ------------------------------------------------------------------

    private void rejoinWhileCarrying(VisualScript script) {
        script.server("bayupgrade: census before saving", (server, context) ->
                        assertCensus(server, context, "before the save"))
                .server("bayupgrade: save the world", (server, context) -> server.saveEverything(true, true, true));
        VisualWorld.reload(script, worldProfile());
        script.serverUntil("bayupgrade: the scene is loaded and the controller ready after the rejoin",
                        (server, context) -> SceneItemCensus.isFullyLoaded(server.overworld(), censusBox)
                                // C is broken by now, so one storage location fewer.
                                && controllerReady(server, context, STORAGE_LOCATIONS - 1), SCENE_READY_TIMEOUT_TICKS)
                .until("bayupgrade: the client holds the pallet truck again", context -> {
                    LocalPlayer player = context.minecraft().player;
                    HandTruckPallet pallet = player == null ? null : HandTruckItem.palletLoad(player.getMainHandItem());
                    return pallet != null && pallet.count() == palletCount;
                }, RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .server("bayupgrade: CHECK the pallet survived the save, quit and rejoin", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    HandTruckPallet pallet = HandTruckItem.palletLoad(player.getMainHandItem());
                    if (pallet == null || !pallet.item().equals(DIORITE) || pallet.count() != palletCount)
                        throw new VisualTestException("after the rejoin the truck carries " + pallet);
                    if (!HandTruckCarry.isCarrying(player))
                        throw new VisualTestException("the carry penalty is not stamped after the rejoin");
                    assertCensus(server, context, "after the save, quit and rejoin");
                    pass("a save, quit and rejoin kept " + pallet.count() + " diorite on the truck");
                });
        guiShot(script, "rejoined");
    }

    // --- 10: refusals -----------------------------------------------------------------------------------------------

    private void bayRefusals(VisualScript script) {
        for (String code : List.of(ENGLISH, GERMAN)) {
            String suffix = code.equals(ENGLISH) ? "" : "-de";
            if (code.equals(GERMAN))
                language.switchTo(script, "bayupgrade: ", GERMAN);
            refusal(script, AIM_A, "not empty (" + code + ")", "refused-not-empty" + suffix,
                    context -> WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_BAY_NOT_EMPTY));
            refusal(script, AIM_E, "too small (" + code + ")", "refused-too-small" + suffix,
                    context -> WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_BAY_TOO_SMALL,
                            Objects.requireNonNull(clientRack(context, BAY_E), "client E").capacityFor(DIORITE),
                            palletCount));
            if (code.equals(GERMAN))
                language.switchTo(script, "bayupgrade: ", ENGLISH);
        }
    }

    private void refusal(VisualScript script, Aim aim, String what, String label,
            Function<VisualContext, Component> message) {
        click(script, aim, false, KeyKind.USE);
        actionBar(script, what, message);
        script.client("bayupgrade: CHECK the line is translated (" + what + ")", context -> requireTranslated(context,
                label.replace("-de", ""), ArmVisualScenario.overlayText(context)));
        guiShot(script, label);
        script.server("bayupgrade: CHECK nothing moved (" + what + ")", (server, context) -> {
            ServerLevel level = server.overworld();
            HandTruckPallet pallet = HandTruckItem.palletLoad(context.serverPlayer(server).getMainHandItem());
            if (pallet == null || pallet.count() != palletCount)
                throw new VisualTestException("the truck carries " + pallet + " after the refusal");
            RackBayBlockEntity a = rack(level, context, BAY_A);
            RackBayBlockEntity e = rack(level, context, BAY_E);
            if (a.storedCount() != A_STOCK + FED - RETRIEVED || !e.storedKey().isEmpty())
                throw new VisualTestException("A holds " + describe(a) + ", E holds " + describe(e));
            assertCensus(server, context, "the refusal " + what);
            pass("refused " + what + " with '" + label + "'");
        });
    }

    // --- 11: into D -------------------------------------------------------------------------------------------------

    private void intoD(VisualScript script) {
        script.server("bayupgrade: remember D's filter", (server, context) -> {
            RackBayBlockEntity bay = rack(server.overworld(), context, BAY_D);
            dFilterBefore = bay.storeFilter();
            if (!bay.storedKey().isEmpty())
                throw new VisualTestException("D is not empty: " + describe(bay));
        });
        click(script, AIM_D_SLOT, true, KeyKind.USE);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the client sees D full and the truck empty", context -> {
            RackBayBlockEntity bay = clientRack(context, BAY_D);
            LocalPlayer player = context.minecraft().player;
            return bay != null && bay.storedCount() == palletCount && player != null
                    && !HandTruckItem.isLoaded(player.getMainHandItem());
        }, RESULT_TIMEOUT_TICKS, context -> "D holds " + describe(clientRack(context, BAY_D)) + ", action bar '"
                + ArmVisualScenario.overlayText(context) + "'");
        script.serverUntil("bayupgrade: the controller indexes D's diorite", (server, context) ->
                controller(server.overworld(), context).countOf(DIORITE) == palletCount, RESULT_TIMEOUT_TICKS);
        script.server("bayupgrade: CHECK D holds the whole pallet, its filter unchanged, the truck empty",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    RackBayBlockEntity bay = rack(level, context, BAY_D);
                    if (!bay.storedKey().map(DIORITE::equals).orElse(false) || bay.storedCount() != palletCount)
                        throw new VisualTestException("D holds " + describe(bay));
                    if (!ItemStack.matches(bay.storeFilter(), Objects.requireNonNull(dFilterBefore, "filter")))
                        throw new VisualTestException("D's filter became " + bay.storeFilter());
                    ItemStack truck = context.serverPlayer(server).getMainHandItem();
                    if (!truck.is(WareworksItems.HAND_TRUCK.get()) || HandTruckItem.isLoaded(truck))
                        throw new VisualTestException("the hand holds " + truck);
                    assertCensus(server, context, "the pallet into D");
                    pass("the pallet went whole into the empty brass bay D through its filter slot: " + palletCount
                            + " diorite, filter unchanged, truck empty, index "
                            + controller(level, context).countOf(DIORITE));
                });
        worldShots(script, "into-bay", AT_D);
        goggles(script, "goggles-d", AIM_D.view("goggles-d"), BAY_D, context -> {
            RackBayBlockEntity bay = clientRack(context, BAY_D);
            return bay != null && bay.storedCount() == palletCount;
        }, context -> {
            List<String> lines = GoggleShots.lines(context, at(context, BAY_D));
            RackBayBlockEntity bay = Objects.requireNonNull(clientRack(context, BAY_D), "client D");
            String contents = WareworksLang.bayContents(Items.DIORITE, palletCount, bay.capacityFor(DIORITE))
                    .component().getString();
            String header = WareworksLang.rackBay(WareworksBlocks.RACK_BAY_BRASS.get().getName()).component()
                    .getString();
            GoggleShots.requireLine(lines, header);
            GoggleShots.requireLine(lines, contents);
            requireTranslated(context, "goggles-d header", header);
            pass("[" + selectedLanguage(context) + "] the client's goggles of D: " + lines);
        });
    }

    // --- the second pass: Flywheel off ------------------------------------------------------------------------------

    private void secondPass(VisualScript script) {
        worldShots(script, "upgrade-after", AT_A);
        breakBay(script, AIM_D, BAY_D, "D");
        worldShots(script, "pallet-on-floor", AT_D);
        lift(script, LIFT_D, "D");
        thirdPerson(script, "truck-third-person", CameraType.THIRD_PERSON_FRONT);
        // Set down on the floor beyond the aisle: the other unload.
        script.camera(AT_FLOOR);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the crosshair is on the top of the floor block",
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(at(context, FLOOR))
                        && hit.getDirection() == Direction.UP, AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context));
        pressKey(script, "the floor", KeyKind.USE);
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the client sees the pallet set down", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && !HandTruckItem.isLoaded(player.getMainHandItem()) && clientPallet(context) != null;
        }, RESULT_TIMEOUT_TICKS, context -> "action bar '" + ArmVisualScenario.overlayText(context) + "'");
        script.server("bayupgrade: CHECK the pallet stands on the floor with the whole load", (server, context) -> {
            ServerLevel level = server.overworld();
            List<PalletEntity> pallets = level.getEntitiesOfClass(PalletEntity.class, censusBox);
            if (pallets.size() != 1)
                throw new VisualTestException(pallets.size() + " pallets after setting one down");
            PalletEntity pallet = pallets.getFirst();
            if (!pallet.blockPosition().equals(at(context, FLOOR.above())) || pallet.carriedCount() != palletCount
                    || !pallet.carriedKey().map(DIORITE::equals).orElse(false))
                throw new VisualTestException("the pallet at " + pallet.blockPosition() + " carries "
                        + pallet.carriedCount() + " x " + pallet.carriedKey());
            assertCensus(server, context, "setting the pallet down on the floor");
            pass("(Flywheel off) D broken, its pallet lifted and set down on the floor with " + palletCount
                    + " diorite");
        });
        worldShots(script, "set-down", FLOOR_SHOT);
    }

    // --- shots ------------------------------------------------------------------------------------------------------

    /** World shots by a player who reaches no block, so no outline or value box is drawn; the reach comes back. */
    private static void worldShots(VisualScript script, String moment, CameraView... views) {
        GoggleShots.reach(script, NAME, 0.0);
        for (CameraView view : views)
            script.camera(view).shot(moment);
        GoggleShots.reach(script, NAME, GoggleShots.vanillaReach());
    }

    /** A shot with the GUI, what a player sees; the chat (the pass's Flywheel command answer) is cleared first. */
    private static void guiShot(VisualScript script, String label) {
        script.client("bayupgrade: clear the chat before " + label, BayUpgradeVisualScenario::clearChat)
                .shotWithGui(label);
    }

    private static void clearChat(VisualContext context) {
        context.minecraft().gui.getChat().clearMessages(true);
    }

    /** A goggle shot of {@code bay} in English and in German, back to English afterwards. */
    private void goggles(VisualScript script, String label, CameraView view, BlockPos bay,
            Predicate<VisualContext> synced, VisualScript.ClientAction check) {
        GoggleShots.shot(script, NAME, view, label, origin -> origin.offset(bay), synced, check);
        language.switchTo(script, "bayupgrade: ", GERMAN);
        GoggleShots.shot(script, NAME, view, label + "-de", origin -> origin.offset(bay), synced, check);
        language.switchTo(script, "bayupgrade: ", ENGLISH);
    }

    // --- input (client thread) --------------------------------------------------------------------------------------

    private enum KeyKind {
        USE, ATTACK
    }

    /**
     * Camera, crosshair checked on the aisle face of the aimed block (inside or outside its filter slot when
     * {@code inSlot} says so), the server's confirmation of the teleport, then one press of the key.
     */
    private void click(VisualScript script, Aim aim, @Nullable Boolean inSlot, KeyKind key) {
        script.client("bayupgrade: first person", context -> context.minecraft().options
                .setCameraType(CameraType.FIRST_PERSON)).camera(aim.view(aim.label().replace(' ', '-')));
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the crosshair is on the " + aim.face() + " face of "
                + aim.label() + (inSlot == null ? "" : inSlot ? ", inside the filter slot" : ", beside the filter slot"),
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(at(context, aim.block()))
                        && hit.getDirection() == aim.face()
                        && (inSlot == null || inSlot == inFilterSlot(context, hit)),
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context)
                        + (context.minecraft().hitResult instanceof BlockHitResult hit ? " at " + hit.getLocation()
                                : ""));
        pressKey(script, aim.label(), key);
    }

    /** The teleport confirmed, the action bar cleared, one press of {@code key}, consumed by the client. */
    private void pressKey(VisualScript script, String what, KeyKind key) {
        script.serverUntil("bayupgrade: the server has the player's answer to every teleport",
                (server, context) -> teleportConfirmed(context.serverPlayer(server)), RESULT_TIMEOUT_TICKS);
        script.client("bayupgrade: clear the action bar before the click on " + what,
                context -> context.minecraft().gui.setOverlayMessage(Component.empty(), false));
        script.client("bayupgrade: " + (key == KeyKind.USE ? "right" : "left") + "-click " + what, context -> {
            KeyMapping mapping = key == KeyKind.USE ? context.minecraft().options.keyUse
                    : context.minecraft().options.keyAttack;
            KeyMapping.click(mapping.getKey());
        });
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the client handled the click on " + what,
                context -> ArmVisualScenario.clickCount(key == KeyKind.USE ? context.minecraft().options.keyUse
                        : context.minecraft().options.keyAttack) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
        script.waitTicks(SETTLE_TICKS);
    }

    /** Whether {@code hit} lies in the 4 px sphere of the bay's filter slot, as Create's own hit test answers. */
    private static boolean inFilterSlot(VisualContext context, BlockHitResult hit) {
        ClientLevel level = Objects.requireNonNull(context.minecraft().level, "level");
        if (!(BlockEntityBehaviour.get(level, hit.getBlockPos(), FilteringBehaviour.TYPE) instanceof StorageFilterBehaviour
                slot) || !(slot.getSlotPositioning() instanceof ValueBoxTransform.Sided sided))
            throw new VisualTestException("no store filter slot at " + hit.getBlockPos());
        sided.fromSide(hit.getDirection());
        return slot.testHit(hit.getLocation());
    }

    private void actionBar(VisualScript script, String what, Function<VisualContext, Component> message) {
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the action bar tells about " + what,
                context -> ArmVisualScenario.overlayText(context).equals(message.apply(context).getString()),
                RESULT_TIMEOUT_TICKS, context -> "the action bar reads '" + ArmVisualScenario.overlayText(context)
                        + "', not '" + message.apply(context).getString() + "'");
        script.client("bayupgrade: CHECK the action bar after " + what, context -> pass("action bar '"
                + ArmVisualScenario.overlayText(context) + "' for " + what));
    }

    /** Selects a hotbar slot with its hotbar key, as a player does, and waits until the client holds what it should. */
    private static void selectHotbar(VisualScript script, int slot, String what, Predicate<ItemStack> expected) {
        script.client("bayupgrade: press hotbar key " + (slot + 1) + " for " + what,
                context -> KeyMapping.click(context.minecraft().options.keyHotbarSlots[slot].getKey()));
        ArmVisualScenario.untilOrFail(script, "bayupgrade: the player holds " + what, context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getInventory().selected == slot && expected.test(player.getMainHandItem());
        }, RESULT_TIMEOUT_TICKS, context -> "the player holds " + context.minecraft().player.getMainHandItem()
                + " in slot " + context.minecraft().player.getInventory().selected);
        script.waitTicks(SETTLE_TICKS);
    }

    private static void gameMode(VisualScript script, GameType mode) {
        script.server("bayupgrade: game mode " + mode, (server, context) -> context.serverPlayer(server)
                        .setGameMode(mode))
                .until("bayupgrade: the client is in " + mode, context -> context.minecraft().gameMode != null
                        && context.minecraft().gameMode.getPlayerMode() == mode, RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    /**
     * Whether the server has the client's confirmation of the last teleport: until then vanilla drops every click on a
     * block without a word ({@code ServerGamePacketListenerImpl#handleUseItemOn}), and every camera move is a teleport.
     */
    private static boolean teleportConfirmed(ServerPlayer player) {
        try {
            return AWAITING_TELEPORT.get(player.connection) == null;
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read whether the server waits for a teleport: " + error);
        }
    }

    private static Field awaitingTeleportField() {
        try {
            Field field = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach the server's pending teleport", error);
        }
    }

    private static boolean clientFrozen(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    // --- build (server thread) --------------------------------------------------------------------------------------

    private void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK.getX(), level.getMinBuildHeight(), DOCK.getZ());
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK.getX(), DOCK.getZ()), DOCK.getZ());
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(CENSUS_FROM), dock.offset(CENSUS_TO));
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(CLEAR_FROM), dock.offset(CLEAR_TO)))
            if (!level.getBlockState(pos).isAir())
                level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());

        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        motor(level, dock.below()).generatedSpeed.setValue(CRANE_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.offset(CONTROLLER),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        BranchLayout layout = layout(dock);
        checkPosition(layout, dock, RACK_A, BAY_A);
        checkPosition(layout, dock, RACK_B_LOW, BAY_B_LOW);
        checkPosition(layout, dock, RACK_B_HIGH, BAY_B_HIGH);
        checkPosition(layout, dock, RACK_F, BAY_F);
        checkPosition(layout, dock, RACK_E, BAY_E);
        checkPosition(layout, dock, RACK_C, BAY_C);
        checkPosition(layout, dock, RACK_D, BAY_D);
        checkPosition(layout, dock, OUTPUT, OUTPUT_POS);

        // The stations, each facing into the aisle; the lever behind the output, on the ground.
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, inward(layout, OUTPUT)));
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, inward(layout, TERMINAL)));
        level.setBlockAndUpdate(dock.offset(LEVER), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, false));
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout.rackPos(OUTPUT));
        FilteringBehaviour request = output == null ? null : BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (request == null || !request.setFilter(COBBLESTONE.toStack()))
            throw new VisualTestException("the output's request could not be preset");
        request.count = RETRIEVED; // after setFilter, which may clamp the count

        // A: wood, cobblestone, a List Filter of cobblestone and priority 3 — the only cobblestone location.
        RackBayBlockEntity a = rackBay(level, layout, RACK_A, WareworksBlocks.RACK_BAY_WOOD);
        fill(a, COBBLESTONE, A_STOCK);
        ItemStack listFilter = AllItems.FILTER.asStack();
        listFilter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(
                List.of(new ItemStack(Items.COBBLESTONE))));
        if (!a.setStoreFilter(listFilter) || !a.setStorePriority(A_PRIORITY))
            throw new VisualTestException("A refused its filter or priority");
        // B: two empty wooden bays, one above the other.
        rackBay(level, layout, RACK_B_LOW, WareworksBlocks.RACK_BAY_WOOD);
        rackBay(level, layout, RACK_B_HIGH, WareworksBlocks.RACK_BAY_WOOD);
        // C: a full andesite bay of diorite, the pallet of the story.
        RackBayBlockEntity c = rackBay(level, layout, RACK_C, WareworksBlocks.RACK_BAY_ANDESITE);
        palletCount = (int) c.capacityFor(DIORITE);
        fill(c, DIORITE, palletCount);
        // D: an empty brass bay filtered to diorite, which holds C's load whole; E: an empty wooden one, which does not.
        RackBayBlockEntity d = rackBay(level, layout, RACK_D, WareworksBlocks.RACK_BAY_BRASS);
        if (!d.setStoreFilter(DIORITE.toStack()))
            throw new VisualTestException("D refused its filter");
        RackBayBlockEntity e = rackBay(level, layout, RACK_E, WareworksBlocks.RACK_BAY_WOOD);
        if (d.capacityFor(DIORITE) < palletCount || e.capacityFor(DIORITE) >= palletCount)
            throw new VisualTestException("the capacities do not tell the story: C " + palletCount + ", D "
                    + d.capacityFor(DIORITE) + ", E " + e.capacityFor(DIORITE));
        // F: a copper fluid bay with water.
        BlockPos f = layout.rackPos(RACK_F);
        level.setBlockAndUpdate(f, BayColumn.withJoins(level, f, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, layout.sideDirection(RACK_F.side()))));
        if (!(level.getBlockEntity(f) instanceof FluidBayBlockEntity fluid)
                || fluid.fill(new FluidStack(Fluids.WATER, WATER), false) != WATER)
            throw new VisualTestException("the fluid bay at " + f + " did not take its water");
        LOGGER.info(PREFIX + "bayupgrade: scene at {}: A {} cobblestone, C {} diorite, F {} mB of water", dock,
                A_STOCK, palletCount, WATER);
    }

    private static void checkPosition(BranchLayout layout, BlockPos dock, RackPosition rack, BlockPos expected) {
        if (!layout.rackPos(rack).equals(dock.offset(expected)))
            throw new VisualTestException("the rack position " + rack + " stands at " + layout.rackPos(rack)
                    + ", not at " + dock.offset(expected));
    }

    private static RackBayBlockEntity rackBay(ServerLevel level, BranchLayout layout, RackPosition rack,
            BlockEntry<RackBayBlock> tier) {
        BlockPos pos = layout.rackPos(rack);
        BlockState state = tier.getDefaultState().setValue(RackBayBlock.FACING, layout.sideDirection(rack.side()));
        level.setBlockAndUpdate(pos, BayColumn.withJoins(level, pos, state));
        if (!(level.getBlockEntity(pos) instanceof RackBayBlockEntity bay))
            throw new VisualTestException("no rack bay block entity at " + pos);
        if (BayColumn.isOverloaded(level.getBlockState(pos)))
            throw new VisualTestException("the bay at " + pos + " came out overloaded");
        return bay;
    }

    private static void fill(RackBayBlockEntity bay, ItemKey key, int count) {
        ItemStack rest = bay.insert(key.toStack(count), false);
        if (!rest.isEmpty() || bay.storedCount() != count)
            throw new VisualTestException("the bay at " + bay.getBlockPos() + " took " + bay.storedCount() + " of "
                    + count + " " + key);
    }

    private void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        // clearContent() empties the armour slots too, so the goggles go on afterwards.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.getInventory().setItem(SLOT_KITS, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(ANDESITE_KITS));
        player.getInventory().setItem(SLOT_FLUID_KIT, WareworksItems.FLUID_BAY_UPGRADE_KIT_BRASS.asStack(1));
        player.getInventory().setItem(SLOT_TRUCK, WareworksItems.HAND_TRUCK.asStack());
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
        player.getFoodData().setFoodLevel(20);
        Vec3 feet = Vec3.atLowerCornerOf(context.origin()).add(WALK_START.eye()).subtract(0, player.getEyeHeight(), 0);
        player.teleportTo(server.overworld(), feet.x, feet.y, feet.z, -90.0F, 30.0F);
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        if (!SceneItemCensus.isFullyLoaded(server.overworld(), censusBox) || !controllerReady(server, context, STORAGE_LOCATIONS))
            return false;
        WarehouseControllerBlockEntity controller = controller(server.overworld(), context);
        return controller.countOf(COBBLESTONE) == A_STOCK && controller.countOf(DIORITE) == palletCount;
    }

    private boolean controllerReady(MinecraftServer server, VisualContext context, int storageLocations) {
        ServerLevel level = server.overworld();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                at(context, CONTROLLER));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        return controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == storageLocations
                && controller.inputStations().size() == 1
                // The terminal reports LocationKind.OUTPUT as well (ADR-018).
                && controller.outputStations().size() == 2 && crane.isControllerLinked()
                && crane.aisleLength() == RAILS;
    }

    // --- census and server checks -----------------------------------------------------------------------------------

    private Map<ItemKey, Long> census(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded");
        return SceneItemCensus.take(level, censusBox, context.serverPlayer(server));
    }

    private void assertCensus(MinecraftServer server, VisualContext context, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded at: " + step);
        SceneItemCensus.assertEquals(level, censusBox, expected, "bayupgrade: " + step,
                context.serverPlayer(server));
        LOGGER.info(PREFIX + "bayupgrade: census PASS: {}", step);
    }

    private void assertNothingDropped(ServerLevel level, String step) {
        if (!level.getEntitiesOfClass(PalletEntity.class, censusBox).isEmpty()
                || !level.getEntitiesOfClass(ItemEntity.class, censusBox).isEmpty())
            throw new VisualTestException("something was dropped by " + step + ": "
                    + level.getEntitiesOfClass(PalletEntity.class, censusBox) + " "
                    + level.getEntitiesOfClass(ItemEntity.class, censusBox));
    }

    private static void requireKits(ServerPlayer player, int count) {
        ItemStack kits = player.getInventory().getItem(SLOT_KITS);
        int has = kits.is(WareworksItems.RACK_UPGRADE_KIT_ANDESITE.get()) ? kits.getCount() : 0;
        if (has != count)
            throw new VisualTestException("the player has " + has + " andesite kits, expected " + count);
    }

    private static ItemKey kitKey(Item kit) {
        return ItemKey.of(new ItemStack(kit));
    }

    /**
     * Freezes the ticks while the crane carries cobblestone from {@code from} towards {@code to}, its arm retracted,
     * once it has left {@code from} and before it arrives.
     */
    private static boolean freezeWhileCarrying(MinecraftServer server, VisualContext context, RackPosition from,
            RackPosition to) {
        StackerCraneBlockEntity crane = crane(server.overworld(), context);
        CranePose target = crane.craneState().target();
        CranePose pose = crane.craneState().pose();
        if (held(crane, COBBLESTONE) <= 0 || crane.craneState().phase() != CranePhase.TRAVEL_TO_TARGET
                || Math.abs(target.x() - to.x()) > 1.0e-6 || target.side() != to.side()
                || Math.abs(pose.x() - from.x()) < MIN_TRAVELLED || Math.abs(target.x() - pose.x()) < MIN_REMAINING)
            return false;
        server.tickRateManager().setFrozen(true);
        return true;
    }

    private static boolean craneIdle(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = crane(server.overworld(), context);
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private static boolean inputEmpty(MinecraftServer server, VisualContext context) {
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(server.overworld(),
                layout(context.origin()).rackPos(INPUT));
        return input != null && input.bufferedItems().isEmpty();
    }

    private static long outputHolds(ServerLevel level, VisualContext context) {
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(context.origin()).rackPos(OUTPUT));
        return output == null ? 0 : output.bufferedItems().count(COBBLESTONE);
    }

    private static long held(StackerCraneBlockEntity crane, ItemKey key) {
        long held = 0;
        for (HeldItems.Entry entry : crane.heldItems().entries())
            if (entry.key().equals(key))
                held += entry.count();
        return held;
    }

    // --- lookups ----------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    private static BlockPos at(VisualContext context, BlockPos offset) {
        return context.origin().offset(offset);
    }

    private static RackBayBlockEntity rack(ServerLevel level, VisualContext context, BlockPos offset) {
        if (level.getBlockEntity(at(context, offset)) instanceof RackBayBlockEntity bay)
            return bay;
        throw new VisualTestException("no rack bay at " + at(context, offset) + ": "
                + level.getBlockState(at(context, offset)));
    }

    private static FluidBayBlockEntity fluidBay(ServerLevel level, VisualContext context) {
        if (level.getBlockEntity(at(context, BAY_F)) instanceof FluidBayBlockEntity bay)
            return bay;
        throw new VisualTestException("no fluid bay at " + at(context, BAY_F));
    }

    @Nullable
    private static RackBayBlockEntity clientRack(VisualContext context, BlockPos offset) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.getBlockEntity(at(context, offset)) instanceof RackBayBlockEntity bay ? bay : null;
    }

    @Nullable
    private static PalletEntity clientPallet(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return null;
        List<PalletEntity> pallets = level.getEntitiesOfClass(PalletEntity.class,
                AABB.encapsulatingFullBlocks(at(context, CENSUS_FROM), at(context, CENSUS_TO)));
        return pallets.isEmpty() ? null : pallets.getFirst();
    }

    private static StackerCraneBlockEntity crane(ServerLevel level, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        return crane;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                at(context, CONTROLLER));
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

    private static void insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("there is no item handler at " + pos);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    private static String describe(@Nullable RackBayBlockEntity bay) {
        return bay == null ? "no bay" : bay.storedCount() + " x " + bay.storedKey().map(Object::toString)
                .orElse("nothing");
    }

    // --- small helpers ----------------------------------------------------------------------------------------------

    private synchronized void pass(String what) {
        passed.add(what);
        LOGGER.info(PREFIX + "bayupgrade: CHECK PASS: {}", what);
    }

    private static String selectedLanguage(VisualContext context) {
        return context.minecraft().getLanguageManager().getSelected();
    }

    /**
     * Remembers {@code line} in English; in German it must exist and differ from the English one, so a key missing from
     * {@code de_de.json} cannot pass as a translation.
     */
    private void requireTranslated(VisualContext context, String moment, String line) {
        String selected = selectedLanguage(context);
        if (ENGLISH.equals(selected)) {
            englishLines.put(moment, line);
            return;
        }
        String english = englishLines.get(moment);
        if (english == null || line.isBlank() || line.equals(english))
            throw new VisualTestException("'" + line + "' at '" + moment + "' is no " + selected
                    + " translation of '" + english + "'");
        pass("'" + line + "' is the " + selected + " line of '" + english + "'");
    }

    /**
     * A click on the aisle face of a bay, seen from a player standing on the rails in the aisle.
     *
     * @param label    name in step descriptions
     * @param block    the bay, from the dock
     * @param across   how far from the face's centre towards its clockwise side the click lands, in blocks
     * @param height   how high on the face the click lands, in blocks from its bottom
     * @param eyeShift how far along the aisle (east) from the clicked point the eye stands, so the view is not
     *                 straight down
     */
    private record Aim(String label, BlockPos block, double across, double height, double eyeShift) {
        static Aim beside(String label, BlockPos block, double eyeShift) {
            return new Aim(label, block, BESIDE_ACROSS, BESIDE_HEIGHT, eyeShift);
        }

        static Aim slot(String label, BlockPos block, double eyeShift) {
            return new Aim(label, block, 0.0, SLOT_HEIGHT, eyeShift);
        }

        /** The aisle face: a bay on the right wall (+z) shows it to the north, one on the left wall to the south. */
        Direction face() {
            return block.getZ() > 0 ? Direction.NORTH : Direction.SOUTH;
        }

        CameraView view(String viewLabel) {
            Direction face = face();
            Direction side = face.getClockWise();
            Vec3 point = new Vec3(block.getX() + 0.5 + face.getStepX() * 0.5 + side.getStepX() * across,
                    block.getY() + height, block.getZ() + 0.5 + face.getStepZ() * 0.5 + side.getStepZ() * across);
            return new CameraView(viewLabel, new Vec3(point.x + eyeShift, RAIL_EYE, AISLE_Z), point);
        }
    }
}
