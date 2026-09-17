package dev.wareworks.dev;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Scenario "poses": the crane renderer at fixed poses, one crane per aisle direction ({@code docs/stacker-crane.md} §7).
 * <p>
 * Four short aisles (dock + {@value #RAILS} rails, no controller, no rotation) stand in a pinwheel around the scene origin,
 * facing north, east, south and west. After the ticks are frozen, each client crane is shown at a fixed pose through
 * {@link StackerCraneBlockEntity#showClientPose}: arm fully extended to the left and to the right (towards a chest one block
 * beyond the empty rack position, so the grabber must stop at its face), half extended, retracted while lifting between
 * levels, with flat and block items held. The rack positions stay empty, so the extended arm is visible. Real motion is the
 * {@code aisle} scenario's job.
 * <p>
 * Shots per pass: the whole scene from above, one view per crane from in front of it, and a low view of a crane's base on
 * the rail.
 */
public final class CranePosesVisualScenario implements VisualScenario {
    public static final String NAME = "poses";

    private static final int RAILS = 3;
    /** Distance of each dock from the scene origin on both horizontal axes. */
    private static final int DOCK_OFFSET = 6;
    private static final int CLEAR_RADIUS = 11;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_READY_TIMEOUT_TICKS = 600;

    private static final int FULL_STACK = 64;
    private static final int HALF_STACK = 32;
    private static final int FEW_ITEMS = 10;

    /** Camera in front of a crane: this far along the aisle direction, to its right and above its carriage. */
    private static final double FRONT_VIEW_AHEAD = 4.0;
    private static final double FRONT_VIEW_RIGHT = 2.5;
    private static final double FRONT_VIEW_ABOVE = 3.0;
    /** Height of the looked-at point above the carriage level. */
    private static final double CARRIAGE_LOOK_HEIGHT = 0.7;
    /** Low camera at the side of a crane's base. */
    private static final double BASE_VIEW_RIGHT = 2.6;
    private static final double BASE_VIEW_BEHIND = 1.2;
    private static final double BASE_VIEW_HEIGHT = 1.4;
    private static final double BASE_LOOK_HEIGHT = 0.35;
    private static final CameraView TOP = CameraView.of("top", 0.5, 17.0, 0.5, 0.5, 0.0, 0.45);
    /** An item frame with the stacker crane item (its fixed display transform) on a block at the scene centre. */
    private static final BlockPos FRAME_SUPPORT = new BlockPos(0, 1, 0);
    private static final Direction FRAME_FACING = Direction.SOUTH;
    private static final CameraView ITEM_FRAME = CameraView.of("frame", 0.5, 1.6, 2.6, 0.5, 1.5, 1.0);

    /** One posed crane. */
    private record Stand(String label, Direction facing, BlockPos dockOffset, CranePose pose, CranePhase phase,
                         List<KeyCount<Item>> held) {
        AisleLayout layout(BlockPos origin) {
            return AisleLayout.of(origin.offset(dockOffset), facing,
                    AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
        }

        Direction armDirection() {
            return pose.side() == Side.LEFT ? facing.getCounterClockWise() : facing.getClockWise();
        }

        /** Centre of the crane's floor block, relative to the origin block's lower corner. */
        Vec3 craneFloor() {
            return Vec3.atBottomCenterOf(dockOffset).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(pose.x()));
        }

        CameraView frontView() {
            Vec3 floor = craneFloor();
            Vec3 eye = floor.add(Vec3.atLowerCornerOf(facing.getNormal()).scale(FRONT_VIEW_AHEAD))
                    .add(Vec3.atLowerCornerOf(facing.getClockWise().getNormal()).scale(FRONT_VIEW_RIGHT))
                    .add(0.0, pose.y() + FRONT_VIEW_ABOVE, 0.0);
            Vec3 target = floor.add(Vec3.atLowerCornerOf(armDirection().getNormal()).scale(pose.arm() / 2.0))
                    .add(0.0, pose.y() + CARRIAGE_LOOK_HEIGHT, 0.0);
            return CameraView.of(label, eye.x, eye.y, eye.z, target.x, target.y, target.z);
        }

        CameraView baseView() {
            Vec3 floor = craneFloor();
            Vec3 eye = floor.add(Vec3.atLowerCornerOf(facing.getClockWise().getNormal()).scale(BASE_VIEW_RIGHT))
                    .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(-BASE_VIEW_BEHIND)).add(0.0, BASE_VIEW_HEIGHT, 0.0);
            return CameraView.of(label + "-base", eye.x, eye.y, eye.z, floor.x, floor.y + BASE_LOOK_HEIGHT, floor.z);
        }
    }

    private static final List<Stand> STANDS = List.of(
            new Stand("north", Direction.NORTH, new BlockPos(-DOCK_OFFSET, 0, DOCK_OFFSET),
                    new CranePose(2.0, 1.0, CranePose.EXTENDED, Side.LEFT), CranePhase.DROP,
                    List.of(new KeyCount<>(Items.IRON_INGOT, FULL_STACK), new KeyCount<>(Items.REDSTONE, FEW_ITEMS))),
            new Stand("east", Direction.EAST, new BlockPos(-DOCK_OFFSET, 0, -DOCK_OFFSET),
                    new CranePose(3.0, 0.0, CranePose.EXTENDED, Side.RIGHT), CranePhase.PICK, List.of()),
            new Stand("south", Direction.SOUTH, new BlockPos(DOCK_OFFSET, 0, -DOCK_OFFSET),
                    new CranePose(1.0, 2.0, 0.5, Side.RIGHT), CranePhase.EXTEND_TARGET,
                    List.of(new KeyCount<>(Items.DIAMOND, FEW_ITEMS), new KeyCount<>(Items.OAK_PLANKS, FULL_STACK))),
            new Stand("west", Direction.WEST, new BlockPos(DOCK_OFFSET, 0, DOCK_OFFSET),
                    new CranePose(2.5, 1.5, CranePose.RETRACTED, Side.LEFT), CranePhase.TRAVEL_TO_TARGET,
                    List.of(new KeyCount<>(Items.COPPER_INGOT, HALF_STACK))));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("poses: clear the area and build four aisles", CranePosesVisualScenario::build)
                .serverUntil("poses: wait until every dock counted its rails", CranePosesVisualScenario::docksReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .until("poses: wait until the client has every dock with its rails", CranePosesVisualScenario::clientReady,
                        SCENE_READY_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.freeze(true).client("poses: show the crane poses", CranePosesVisualScenario::showPoses);
        script.shotFrom(TOP, "poses");
        for (Stand stand : STANDS)
            script.shotFrom(stand.frontView(), "pose");
        script.shotFrom(STANDS.get(STANDS.size() - 1).baseView(), "pose");
        script.shotFrom(ITEM_FRAME, "item");
        script.freeze(false);
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        StringJoiner joiner = new StringJoiner(" ");
        for (Stand stand : STANDS) {
            if (level == null || !(level.getBlockEntity(context.origin().offset(stand.dockOffset()))
                    instanceof StackerCraneBlockEntity crane)) {
                joiner.add(stand.label() + "=missing");
                continue;
            }
            CranePose pose = crane.craneState().pose();
            joiner.add(String.format(Locale.ROOT, "%s=%.1f/%.1f/%.1f%c(H%d,held%d)", stand.label(), pose.x(), pose.y(),
                    pose.arm(), pose.side().letter(), crane.geometry().height(), crane.goggleInfo().heldCount()));
        }
        return joiner.toString();
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos origin = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(origin);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-CLEAR_RADIUS, 0, -CLEAR_RADIUS),
                origin.offset(CLEAR_RADIUS, CLEAR_HEIGHT, CLEAR_RADIUS)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (Stand stand : STANDS) {
            AisleLayout layout = stand.layout(origin);
            level.setBlockAndUpdate(layout.dock(), WareworksBlocks.STACKER_CRANE.getDefaultState()
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, stand.facing()));
            for (int x = 1; x <= RAILS; x++)
                level.setBlockAndUpdate(layout.aislePos(x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                        .setValue(WarehouseRailBlock.AXIS, stand.facing().getAxis()));
            // The inventory one block beyond the reached rack position: a fully extended grabber stops at its face.
            RackPosition reached = RackPosition.of((int) stand.pose().x(), (int) stand.pose().y(), stand.pose().side());
            Direction outward = stand.armDirection();
            level.setBlockAndUpdate(layout.rackPos(reached).relative(outward),
                    Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
        }
        BlockPos support = origin.offset(FRAME_SUPPORT);
        level.setBlockAndUpdate(support, Blocks.POLISHED_ANDESITE.defaultBlockState());
        ItemFrame frame = new ItemFrame(level, support.relative(FRAME_FACING), FRAME_FACING);
        frame.setItem(WareworksBlocks.STACKER_CRANE.asStack());
        if (!level.addFreshEntity(frame))
            throw new VisualTestException("the item frame with the stacker crane item could not be added");
    }

    private static boolean docksReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        for (Stand stand : STANDS) {
            StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level,
                    stand.layout(context.origin()).dock());
            if (crane == null || crane.aisleLength() != RAILS)
                return false;
        }
        return true;
    }

    // --- client -----------------------------------------------------------------------------------------------------

    private static boolean clientReady(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        for (Stand stand : STANDS) {
            if (!(level.getBlockEntity(stand.layout(context.origin()).dock()) instanceof StackerCraneBlockEntity crane)
                    || crane.aisleLength() != RAILS)
                return false;
        }
        return true;
    }

    private static void showPoses(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("no client level for the crane poses");
        for (Stand stand : STANDS) {
            BlockPos dock = stand.layout(context.origin()).dock();
            if (!(level.getBlockEntity(dock) instanceof StackerCraneBlockEntity crane)
                    || !crane.showClientPose(stand.pose(), stand.phase(), stand.held()))
                throw new VisualTestException("cannot show the " + stand.label() + " crane pose at " + dock);
        }
    }
}
