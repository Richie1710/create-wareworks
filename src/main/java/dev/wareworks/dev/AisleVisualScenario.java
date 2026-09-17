package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "aisle": one complete aisle as a player builds it ({@code docs/warehouse-system.md} §1).
 * <p>
 * Layout, relative to the dock (the scene origin, on the superflat surface near x = 0, z = 0): a creative motor below the
 * dock (shaft up), the dock facing east, {@value #RAILS} rails in front of it, the controller behind it; the output at
 * rack position {@code 1/0 L} and the input at {@code 1/0 R}; storage locations (warehouse interface + chest) on both sides
 * at positions {@value #STORAGE_FIRST_POSITION}..{@value #RAILS}, levels 1-{@value #STORAGE_LEVELS}. The input starts with
 * several item stacks and the motor at 0 RPM.
 * <p>
 * Tour per pass: the crane idle from four views (side, along the aisle, dock close-up, top); then the motor goes to
 * {@value #MOTOR_RPM} RPM (first pass) or the input is refilled (second pass), and the game is frozen for two views at each
 * moment: carrying items to a storage location (following camera ahead of the crane, along the aisle), arm extended at the
 * rack (following close-up of the arm, rack view), travelling empty back to the input (side, following camera ahead).
 * Following cameras are placed from the frozen client crane's pose. Finally it waits until everything is stored.
 */
public final class AisleVisualScenario implements VisualScenario {
    public static final String NAME = "aisle";

    private static final Direction AISLE = Direction.EAST;
    /** World column of the dock. */
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 10;
    private static final int MOTOR_RPM = 128;
    private static final int STORAGE_FIRST_POSITION = 4;
    private static final int STORAGE_LEVELS = 3;
    private static final RackPosition OUTPUT = RackPosition.of(1, 0, Side.LEFT);
    private static final RackPosition INPUT = RackPosition.of(1, 0, Side.RIGHT);
    /** Blocks cleared to air around the aisle (sideways and at both ends) and above the floor. */
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    private static final int FULL_STACK = 64;
    private static final int PART_STACK = 48;
    private static final int HALF_STACK = 32;

    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int MOMENT_TIMEOUT_TICKS = 1200;
    private static final int ALL_STORED_TIMEOUT_TICKS = 2400;
    /** A travel moment needs at least this distance left on one axis, so it is still travelling when the freeze arrives. */
    private static final double MIN_REMAINING_TRAVEL = 1.0;
    private static final double ARM_EXTENDED = 0.9;

    /** From the right (south) side, high enough to look over the near rack (3 blocks) into the aisle. */
    private static final CameraView SIDE = CameraView.of("side", 6.5, 11.0, 7.5, 6.5, 1.0, 0.5);
    /** From beyond the aisle end, along the rails back to the dock. */
    private static final CameraView ALONG = CameraView.of("aisle", 14.5, 3.5, 0.5, 1.0, 1.5, 0.5);
    /** Inside the aisle, close to the first storage positions (where the first items are stored). */
    private static final CameraView RACK = CameraView.of("rack", 8.5, 3.0, 0.5, 4.5, 1.0, 0.5);
    /** Close-up of the dock, input and output from the free positions 2-3. */
    private static final CameraView DOCK = CameraView.of("dock", 3.5, 2.5, 0.5, 0.5, 0.7, 0.5);
    /** Straight down; the tiny northward offset of the target turns north to the top of the image. */
    private static final CameraView TOP = CameraView.of("top", 5.0, 15.0, 0.5, 5.0, 0.0, 0.45);

    /** Following camera ahead of the crane (moments carry and travel): blocks ahead along the aisle and above its level. */
    private static final String FRONT = "front";
    private static final double FRONT_AHEAD = 2.4;
    private static final double FRONT_ABOVE = 1.7;
    /** Following close camera for the arm moment: ahead, above the level, off to the side away from the arm. */
    private static final String CLOSE = "close";
    private static final double CLOSE_AHEAD = 1.6;
    private static final double CLOSE_ABOVE = 1.35;
    private static final double CLOSE_OFFSIDE = 0.3;
    private static final double CLOSE_LOOK_ASIDE = 0.75;
    /** Heights above the crane's level that the following cameras look at: carriage deck, arm stages. */
    private static final double CARRIAGE_LOOK_HEIGHT = 0.7;
    private static final double ARM_LOOK_HEIGHT = 0.55;
    private static final double BLOCK_CENTER = 0.5;

    /**
     * Sound events the crane plays ({@code docs/stacker-crane.md} §8): travel start and stop, rail joints, lift, arm, pick,
     * drop. After the last pass every one of them must have been heard by the client (the cameras stay within the 16 block
     * range of server-played sounds).
     */
    private static final List<String> CRANE_SOUNDS = List.of("create:contraption_assemble",
            "create:contraption_disassemble", "minecraft:block.metal.step", "minecraft:block.chain.step",
            "minecraft:block.piston.extend", "minecraft:block.piston.contract", "minecraft:entity.item.pickup",
            "create:depot_plop");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("aisle: clear the area and place the creative motor", AisleVisualScenario::placeMotor)
                .server("aisle: build dock, rails, controller, stations and storage", AisleVisualScenario::buildAisle)
                .serverUntil("aisle: wait until the controller is ready with every member", AisleVisualScenario::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        for (CameraView view : List.of(SIDE, ALONG, DOCK, TOP))
            script.shotFrom(view, "idle");
        if (pass == VisualPass.FLYWHEEL)
            script.server("aisle: creative motor to " + MOTOR_RPM + " RPM", AisleVisualScenario::powerOn);
        else
            script.server("aisle: refill the warehouse input", AisleVisualScenario::refillInput);
        moment(script, "carry", AisleVisualScenario::carrying, MomentView.following(FRONT, AisleVisualScenario::frontView),
                MomentView.fixed(ALONG));
        moment(script, "arm", AisleVisualScenario::armExtended, MomentView.following(CLOSE, AisleVisualScenario::armView),
                MomentView.fixed(RACK));
        moment(script, "travel", AisleVisualScenario::travellingEmpty, MomentView.fixed(SIDE),
                MomentView.following(FRONT, AisleVisualScenario::frontView));
        script.serverUntil("aisle: wait until the crane is idle and the input is empty", AisleVisualScenario::allStored,
                ALL_STORED_TIMEOUT_TICKS);
        if (pass == VisualPass.values()[VisualPass.values().length - 1])
            script.client("aisle: check that the crane was heard", AisleVisualScenario::assertCraneSounds);
    }

    @Override
    public String status(VisualContext context) {
        return clientCrane(context).map(crane -> {
            CraneState<?, ?> state = crane.craneState();
            CranePose pose = state.pose();
            return String.format(Locale.ROOT, "posX=%.2f posY=%.2f arm=%.2f side=%s phase=%s held=%d", pose.x(), pose.y(),
                    pose.arm(), pose.side(), state.phase(), crane.goggleInfo().heldCount());
        }).orElse("crane=missing");
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(0);
        level.setBlockAndUpdate(dock,
                WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, layout.sideDirection(OUTPUT.side()).getOpposite()));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, layout.sideDirection(INPUT.side()).getOpposite()));
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                for (int y = 0; y < STORAGE_LEVELS; y++) {
                    BlockPos rack = layout.rackPos(RackPosition.of(x, y, side));
                    level.setBlockAndUpdate(rack.relative(outward),
                            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                    level.setBlockAndUpdate(rack,
                            WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState().setValue(WarehouseInterfaceBlock.FACING, outward));
                }
            }
        }
        fillInput(level, layout.rackPos(INPUT), List.of(new ItemStack(Items.IRON_INGOT, FULL_STACK),
                new ItemStack(Items.COPPER_INGOT, FULL_STACK), new ItemStack(Items.GOLD_INGOT, HALF_STACK),
                new ItemStack(Items.REDSTONE, FULL_STACK), new ItemStack(Items.LAPIS_LAZULI, PART_STACK)));
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        return controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == expectedStorage && controller.inputStations().size() == 1
                && controller.outputStations().size() == 1 && crane.isControllerLinked() && crane.aisleLength() == RAILS;
    }

    private static void powerOn(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin()).generatedSpeed.setValue(MOTOR_RPM);
    }

    private static void refillInput(MinecraftServer server, VisualContext context) {
        fillInput(server.overworld(), layout(context.origin()).rackPos(INPUT),
                List.of(new ItemStack(Items.DIAMOND, HALF_STACK), new ItemStack(Items.EMERALD, HALF_STACK),
                        new ItemStack(Items.COAL, FULL_STACK), new ItemStack(Items.QUARTZ, PART_STACK)));
    }

    private static boolean allStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout(dock).rackPos(INPUT));
        if (crane == null || input == null)
            throw new VisualTestException("the dock or the input of the aisle is missing");
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty() && crane.heldItems().isEmpty()
                && input.bufferedItems().isEmpty();
    }

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    /** Inserts through the input's item capability, like a hopper or funnel would. */
    private static void fillInput(ServerLevel level, BlockPos input, List<ItemStack> stacks) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, input, null);
        if (handler == null)
            throw new VisualTestException("the warehouse input at " + input + " has no item handler");
        for (ItemStack stack : stacks) {
            ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
            if (!rest.isEmpty())
                throw new VisualTestException("the warehouse input refused " + rest);
        }
    }

    /** Fails the run unless the client heard every {@link #CRANE_SOUNDS} event at least once. */
    private static void assertCraneSounds(VisualContext context) {
        List<String> missing = CRANE_SOUNDS.stream().filter(sound -> context.soundCount(sound) == 0).toList();
        LOGGER.info(PREFIX + "aisle: crane sounds {}", CRANE_SOUNDS.stream()
                .map(sound -> sound + "=" + context.soundCount(sound)).toList());
        if (!missing.isEmpty())
            throw new VisualTestException("the crane was not heard: " + missing + "; heard " + context.soundCounts());
    }

    // --- moments (client thread, synced crane state) ---------------------------------------------------------------

    private static void moment(VisualScript script, String moment, Predicate<VisualContext> condition, MomentView... views) {
        script.until("aisle: wait for the crane moment '" + moment + "'", condition, MOMENT_TIMEOUT_TICKS).freeze(true);
        for (MomentView view : views)
            script.camera(view.label(), view.view()).shot(moment + "-" + view.label());
        script.freeze(false);
    }

    /** A camera of a moment: a fixed view, or one computed from the frozen client crane when the camera step starts. */
    private record MomentView(String label, Function<VisualContext, CameraView> view) {
        static MomentView fixed(CameraView view) {
            return new MomentView(view.label(), context -> view);
        }

        static MomentView following(String label, Function<VisualContext, CameraView> view) {
            return new MomentView(label, view);
        }
    }

    /** In the aisle ahead of the crane, looking back at the carriage and the items on the arm. */
    private static CameraView frontView(VisualContext context) {
        CranePose pose = frozenPose(context);
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        double craneZ = BLOCK_CENTER + AISLE.getStepZ() * pose.x();
        return CameraView.of(FRONT, craneX + AISLE.getStepX() * FRONT_AHEAD, pose.y() + FRONT_ABOVE,
                craneZ + AISLE.getStepZ() * FRONT_AHEAD, craneX, pose.y() + CARRIAGE_LOOK_HEIGHT, craneZ);
    }

    /** Close in front of the carriage, slightly off to the other side, looking at the arm entering the rack. */
    private static CameraView armView(VisualContext context) {
        CranePose pose = frozenPose(context);
        Direction armSide = pose.side() == Side.LEFT ? AISLE.getCounterClockWise() : AISLE.getClockWise();
        double craneX = BLOCK_CENTER + AISLE.getStepX() * pose.x();
        double craneZ = BLOCK_CENTER + AISLE.getStepZ() * pose.x();
        return CameraView.of(CLOSE,
                craneX + AISLE.getStepX() * CLOSE_AHEAD - armSide.getStepX() * CLOSE_OFFSIDE, pose.y() + CLOSE_ABOVE,
                craneZ + AISLE.getStepZ() * CLOSE_AHEAD - armSide.getStepZ() * CLOSE_OFFSIDE,
                craneX + armSide.getStepX() * CLOSE_LOOK_ASIDE, pose.y() + ARM_LOOK_HEIGHT,
                craneZ + armSide.getStepZ() * CLOSE_LOOK_ASIDE);
    }

    private static CranePose frozenPose(VisualContext context) {
        return clientCrane(context).map(crane -> crane.craneState().pose())
                .orElseThrow(() -> new VisualTestException("no client crane to follow"));
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }

    private static boolean carrying(VisualContext context) {
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_TARGET
                && crane.goggleInfo().heldCount() > 0 && remainingTravel(crane.craneState()) > MIN_REMAINING_TRAVEL)
                .isPresent();
    }

    private static boolean armExtended(VisualContext context) {
        return clientCrane(context).filter(crane -> {
            CranePhase phase = crane.craneState().phase();
            boolean atRack = phase == CranePhase.EXTEND_TARGET || phase == CranePhase.DROP || phase == CranePhase.EXTEND_SOURCE
                    || phase == CranePhase.PICK;
            return atRack && crane.craneState().pose().arm() >= ARM_EXTENDED;
        }).isPresent();
    }

    private static boolean travellingEmpty(VisualContext context) {
        return clientCrane(context).filter(crane -> crane.craneState().phase() == CranePhase.TRAVEL_TO_SOURCE
                && remainingTravel(crane.craneState()) > MIN_REMAINING_TRAVEL).isPresent();
    }

    private static double remainingTravel(CraneState<?, ?> state) {
        return Math.max(Math.abs(state.target().x() - state.pose().x()), Math.abs(state.target().y() - state.pose().y()));
    }
}
